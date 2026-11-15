#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$script:Path = $PSCommandPath
if ([string]::IsNullOrEmpty($script:Path)) { $script:Path = $MyInvocation.MyCommand.Path }
$script:Dir = Split-Path -Parent $script:Path
$script:Data = Join-Path $script:Dir 'PJM-Transfer'
$script:LogPath = Join-Path $script:Dir 'PJM-Link.log'
$script:Utf8 = [System.Text.UTF8Encoding]::new($false)
$script:Utf8Bom = [System.Text.UTF8Encoding]::new($true)
$script:LogLock = [object]::new()
$script:Categories = @('images','videos','bili_videos','audios','texts','pjm','others')
# 选定的设备序列号；非空时所有 adb 子命令都带 -s
$script:Serial = $null

function Write-Log {
    param([string]$Message, [string]$Level = '信息')
    $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' [' + $Level + '] ' + $Message
    [System.Threading.Monitor]::Enter($script:LogLock)
    try {
        $info = [IO.FileInfo]::new($script:LogPath)
        if ($info.Exists -and $info.Length -gt 5MB) { [IO.File]::Delete($script:LogPath) }
        [IO.File]::AppendAllText($script:LogPath, $line + [Environment]::NewLine, $script:Utf8Bom)
    } catch { }
    finally { [System.Threading.Monitor]::Exit($script:LogLock) }
    try {
        $rtVar = Get-Variable -Name Runtime -Scope Script -ErrorAction SilentlyContinue
        if ($null -ne $rtVar -and $null -ne $rtVar.Value) { $rtVar.Value.LogQueue.Enqueue($line) }
    } catch { }
}

function Format-Bytes {
    param([long]$Bytes)
    if ($Bytes -ge 1GB) { return ('{0:N2} GB' -f ($Bytes / 1GB)) }
    if ($Bytes -ge 1MB) { return ('{0:N1} MB' -f ($Bytes / 1MB)) }
    if ($Bytes -ge 1KB) { return ('{0:N0} KB' -f ($Bytes / 1KB)) }
    return ($Bytes.ToString() + ' B')
}

function Find-Adb {
    $local = Join-Path $script:Dir 'adb\adb.exe'
    if (Test-Path -LiteralPath $local -PathType Leaf) { return $local }
    $cmd = Get-Command 'adb.exe' -CommandType Application -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { return $cmd.Source }
    return $null
}

function Invoke-Adb {
    param([string]$AdbPath, [string]$Arguments, [int]$TimeoutMs = 15000, $Runtime = $null)
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $AdbPath
    # 电脑上同时挂着无线调试/模拟器时，裸 adb 会因为「多于一个设备」直接失败。
    # 选定过一次设备后，所有子命令都显式 -s，避免歧义。
    if (-not [string]::IsNullOrEmpty($script:Serial)) {
        $psi.Arguments = '-s "' + $script:Serial + '" ' + $Arguments
    } else {
        $psi.Arguments = $Arguments
    }
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $p = [System.Diagnostics.Process]::new()
    $p.StartInfo = $psi
    try {
        [void]$p.Start()
        if ($null -ne $Runtime) { $Runtime.CurrentAdb = $p }
        $outTask = $p.StandardOutput.ReadToEndAsync()
        $errTask = $p.StandardError.ReadToEndAsync()
        $finished = $p.WaitForExit($TimeoutMs)
        if (-not $finished) {
            # 超时必须真的杀掉：wait-for-device 这类调用会一直挂着，
            # 若只 Kill 不兜底，进程会残留并锁住本目录的 adb\adb.exe（文件夹就删不掉）。
            try { $p.Kill($true) } catch { try { $p.Kill() } catch { } }
            try { [void]$p.WaitForExit(2000) } catch { }
            if (-not $p.HasExited) { try { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } catch { } }
        }
        try { [void]$outTask.Wait(3000) } catch { }
        try { [void]$errTask.Wait(3000) } catch { }
        $out = ''
        $err = ''
        try { if ($outTask.IsCompleted) { $out = $outTask.Result } } catch { }
        try { if ($errTask.IsCompleted) { $err = $errTask.Result } } catch { }
        $code = -1
        try { if ($finished) { $code = $p.ExitCode } } catch { }
        return [pscustomobject]@{ ExitCode = $code; TimedOut = (-not $finished); Out = $out; Err = $err }
    } finally {
        if ($null -ne $Runtime) { $Runtime.CurrentAdb = $null }
        try { $p.Dispose() } catch { }
    }
}

function Remove-Tunnels {
    param([string]$AdbPath, $Runtime = $null)
    if ([string]::IsNullOrEmpty($AdbPath)) { return }
    $r = Invoke-Adb -AdbPath $AdbPath -Arguments 'forward --remove tcp:8787' -TimeoutMs 4000 -Runtime $Runtime
    if ($r.ExitCode -eq 0) { Write-Log '已移除 adb forward 127.0.0.1:8787' } else { Write-Log ('移除 adb forward 返回：' + ($r.Out + $r.Err).Trim()) '警告' }
    $r = Invoke-Adb -AdbPath $AdbPath -Arguments 'reverse --remove tcp:8788' -TimeoutMs 4000 -Runtime $Runtime
    if ($r.ExitCode -eq 0) { Write-Log '已移除 adb reverse 127.0.0.1:8788' } else { Write-Log ('移除 adb reverse 返回：' + ($r.Out + $r.Err).Trim()) '警告' }
}

# 退出时清掉「本工具目录里」跑起来的 adb.exe 子进程。
# 之所以单独做：wait-for-device 超时残留会让 adb\adb.exe 一直被占用，
# Windows 于是拒绝删除整个 PJM-Link 文件夹（提示"文件正被另一个进程使用"）。
# 只结束本目录 adb\ 下的进程，绝不碰系统/Studio 用的那份 adb。
function Stop-BundledAdb {
    param([string]$Root = $script:Dir)
    try {
        $adbDir = [IO.Path]::GetFullPath((Join-Path $Root 'adb')).TrimEnd('\') + '\'
        $procs = Get-CimInstance Win32_Process -Filter "Name='adb.exe'" -ErrorAction SilentlyContinue
        foreach ($p in $procs) {
            $exe = [string]$p.ExecutablePath
            if ([string]::IsNullOrEmpty($exe)) { continue }
            if ($exe.StartsWith($adbDir, [StringComparison]::OrdinalIgnoreCase)) {
                try {
                    Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
                    Write-Log ('已结束残留 adb 进程：PID ' + $p.ProcessId)
                } catch { }
            }
        }
    } catch { }
}

function Get-DiskInfo {
    $root = $script:Data
    if (-not (Test-Path -LiteralPath $root)) { New-Item -ItemType Directory -Path $root -Force | Out-Null }
    $full = [IO.Path]::GetFullPath($root)
    $fs = '未知'
    $free = [long]0
    $total = [long]0
    try {
        $drive = [System.IO.DriveInfo]::new([IO.Path]::GetPathRoot($full))
        $format = $drive.DriveFormat
        if ($format -eq 'NTFS') { $fs = 'NTFS' }
        elseif ($format -eq 'exFAT') { $fs = 'exFAT' }
        elseif ($format -eq 'FAT32') { $fs = 'FAT32' }
        $free = [long]$drive.AvailableFreeSpace
        $total = [long]$drive.TotalSize
    } catch { }
    $writable = $false
    try {
        $test = Join-Path $full ('.pjm-write-' + [Guid]::NewGuid().ToString('N') + '.tmp')
        [IO.File]::WriteAllText($test, 'pjm', $script:Utf8)
        [IO.File]::Delete($test)
        $writable = $true
    } catch { }
    return [pscustomobject]@{ Root = $full; Fs = $fs; Free = $free; Total = $total; Writable = $writable }
}

function Get-Folders {
    $list = [System.Collections.ArrayList]::new()
    foreach ($cat in $script:Categories) {
        $catDir = Join-Path $script:Data $cat
        if (-not (Test-Path -LiteralPath $catDir)) { continue }
        $dirs = @(Get-ChildItem -LiteralPath $catDir -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
        foreach ($d in $dirs) {
            if ($d.Name -notmatch '^\d{4}-\d{2}-\d{2}$') { continue }
            $files = @(Get-ChildItem -LiteralPath $d.FullName -File -ErrorAction SilentlyContinue)
            if ($files.Count -eq 0) { continue }
            $size = [long]0
            foreach ($f in $files) { $size += $f.Length }
            [void]$list.Add([ordered]@{ cat = $cat; date = $d.Name; count = $files.Count; size = $size })
        }
    }
    return $list
}

function Get-SafeName {
    param([string]$Name)
    if ([string]::IsNullOrWhiteSpace($Name)) { return '未命名文件' }
    $safe = [regex]::Replace($Name, '[\\/:*?"<>|\x00-\x1F]', '_')
    $safe = $safe.Trim()
    $safe = $safe.TrimEnd([char[]]@('.', ' '))
    if ($safe.Length -gt 120) {
        $safe = $safe.Substring(0, 120).TrimEnd([char[]]@('.', ' '))
    }
    if ([string]::IsNullOrWhiteSpace($safe)) { return '未命名文件' }
    $base = [IO.Path]::GetFileNameWithoutExtension($safe).ToUpperInvariant()
    if ($base -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$') { $safe = '_' + $safe }
    return $safe
}

function Get-UniqueTarget {
    param([string]$Directory, [string]$FileName)
    $candidate = Join-Path $Directory $FileName
    if (-not (Test-Path -LiteralPath $candidate)) { return [pscustomobject]@{ Path = $candidate; Name = $FileName } }
    $base = [IO.Path]::GetFileNameWithoutExtension($FileName)
    $ext = [IO.Path]::GetExtension($FileName)
    if ([string]::IsNullOrEmpty($base)) { $base = $FileName; $ext = '' }
    for ($i = 2; $i -lt 9999; $i++) {
        $suffix = ' (' + $i + ')'
        $maxBase = 120 - $suffix.Length - $ext.Length
        $b = $base
        if ($b.Length -gt $maxBase) { $b = $b.Substring(0, [Math]::Max(1, $maxBase)) }
        $name = $b + $suffix + $ext
        $p = Join-Path $Directory $name
        if (-not (Test-Path -LiteralPath $p)) { return [pscustomobject]@{ Path = $p; Name = $name } }
    }
    throw '同名文件过多，无法生成新文件名'
}

function Read-Ledger {
    $path = Join-Path $script:Data '.pjm-transfer.json'
    $map = @{}
    if (-not (Test-Path -LiteralPath $path)) { return $map }
    try {
        $raw = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)
        if ([string]::IsNullOrWhiteSpace($raw)) { return $map }
        $obj = $raw | ConvertFrom-Json
        if ($null -ne $obj.entries) {
            foreach ($prop in $obj.entries.PSObject.Properties) { $map[$prop.Name] = $prop.Value }
        }
    } catch {
        Write-Log ('账本损坏，已备份并重建：' + $_.Exception.Message) '警告'
        try {
            $backup = (Join-Path $script:Data ('.pjm-transfer.json.bad-' + (Get-Date -Format 'yyyyMMddHHmmss')))
            Copy-Item -LiteralPath $path -Destination $backup -Force
        } catch { }
    }
    return $map
}

function Save-Ledger {
    param([hashtable]$Map)
    $path = Join-Path $script:Data '.pjm-transfer.json'
    $tmp = $path + '.tmp'
    $obj = [ordered]@{ version = 1; entries = $Map }
    $json = $obj | ConvertTo-Json -Depth 6
    [IO.File]::WriteAllText($tmp, $json, $script:Utf8)
    if (Test-Path -LiteralPath $path) {
        try { [IO.File]::Replace($tmp, $path, $null) }
        catch { Move-Item -LiteralPath $tmp -Destination $path -Force }
    } else {
        [IO.File]::Move($tmp, $path)
    }
}

function Convert-ToEpochMs {
    param($Value)
    if ($null -eq $Value) { return [long]0 }
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) { return [long]0 }
    $n = [long]0
    if ([long]::TryParse($text, [ref]$n)) {
        if ($n -gt 0 -and $n -lt 100000000000) { $n = $n * 1000 }
        return $n
    }
    try {
        $dto = [DateTimeOffset]::Parse($text, [Globalization.CultureInfo]::InvariantCulture)
        return $dto.ToUnixTimeMilliseconds()
    } catch { return [long]0 }
}

function Get-HttpErrorText {
    param([System.Exception]$Exception)
    if ($Exception -is [System.OperationCanceledException]) { return '已停止' }
    if ($Exception -is [System.Net.WebException]) {
        $we = $Exception
        if ($null -ne $we.Response) {
            $code = [int]$we.Response.StatusCode
            if ($code -eq 404) { return '手机端没有这个接口（HTTP 404），请确认 App 版本支持 PC Link' }
            return ('HTTP ' + $code + '：' + $we.Message)
        }
        if ($we.Status -eq [System.Net.WebExceptionStatus]::ConnectFailure) { return '连不上手机服务 127.0.0.1:8787，请确认 USB 调试已授权、手机端已打开传输服务' }
        if ($we.Status -eq [System.Net.WebExceptionStatus]::Timeout) { return '连接手机超时' }
        return ('网络错误：' + $we.Message)
    }
    return $Exception.Message
}

function Invoke-PullJob {
    param($Runtime, [string[]]$Dates = @())
    $task = $Runtime.Task
    $task.active = $true
    $task.done = 0
    $task.total = 0
    $task.bytes = [long]0
    $task.speedBps = [long]0
    $task.skipped = 0
    $task.failed = 0
    $task.error = $null
    $task.cancel = $false

    try {
        $tmpDir = Join-Path $script:Data '.tmp'
        if (-not (Test-Path -LiteralPath $tmpDir)) { New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null }
        $ledger = Read-Ledger
        $url = 'http://127.0.0.1:8787/api/files?category=&from=0&to=0'
        Write-Log '正在获取手机文件清单…'
        $req = [System.Net.HttpWebRequest]::Create($url)
        $req.Timeout = 20000
        $req.ReadWriteTimeout = 30000
        $Runtime.CurrentWeb = $req
        $resp = $req.GetResponse()
        $reader = [IO.StreamReader]::new($resp.GetResponseStream(), [Text.Encoding]::UTF8)
        $json = $reader.ReadToEnd()
        $reader.Close()
        $resp.Close()
        $resp = $null
        $Runtime.CurrentWeb = $null
        $items = ConvertFrom-JsonFast $json
        # 手机端可以指定「只传这几天」；没指定就是全部
        if ($Dates.Count -gt 0) {
            $picked = @()
            foreach ($it in $items) {
                $mm = Convert-ToEpochMs $it.m
                $dstr = (Get-Date).ToString('yyyy-MM-dd')
                if ($mm -gt 0) { $dstr = ([DateTimeOffset]::FromUnixTimeMilliseconds($mm)).LocalDateTime.ToString('yyyy-MM-dd') }
                if ($Dates -contains $dstr) { $picked += $it }
            }
            $items = $picked
            Write-Log ('按日期筛选：' + ($Dates -join ', ') + '，共 ' + $items.Count + ' 个文件')
        }
        $task.total = $items.Count
        if ($items.Count -eq 0) {
            Write-Log '手机没有可传的文件'
            $task.error = '手机没有可传的文件'
            return
        }
        Write-Log ('开始接收 ' + $items.Count + ' 个文件')
        $samples = [System.Collections.Generic.Queue[object]]::new()
        $window = [long]0
        foreach ($item in $items) {
            if ($task.cancel -or $Runtime.QuitEvent.WaitOne(0)) { throw [System.OperationCanceledException]::new('用户已停止') }
            $p = [string]$item.p
            if ([string]::IsNullOrWhiteSpace($p)) { $task.failed++; $task.done++; continue }
            $s = [long]0
            try { $s = [long]$item.s } catch { }
            $m = Convert-ToEpochMs $item.m
            $key = $p + '|' + $s + '|' + $m
            if ($ledger.ContainsKey($key)) {
                $task.skipped++
                $task.done++
                continue
            }
            $cat = [string]$item.c
            if ($script:Categories -notcontains $cat) { $cat = 'others' }
            $date = (Get-Date).ToString('yyyy-MM-dd')
            if ($m -gt 0) { $date = ([DateTimeOffset]::FromUnixTimeMilliseconds($m)).LocalDateTime.ToString('yyyy-MM-dd') }
            $name = Get-SafeName ([string]$item.n)
            $destDir = Join-Path (Join-Path $script:Data $cat) $date
            if (-not (Test-Path -LiteralPath $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
            $target = Get-UniqueTarget -Directory $destDir -FileName $name
            $part = Join-Path $tmpDir ($target.Name + '.part')
            $fileBytes = [long]0
            $web = $null
            $fs = $null
            try {
                $partLen = [long]0
                if (Test-Path -LiteralPath $part) { $partLen = ([IO.FileInfo]::new($part)).Length }
                if ($s -gt 0 -and $partLen -eq $s) {
                    [IO.File]::Move($part, $target.Path)
                    $ledger[$key] = [ordered]@{ p = $p; s = $s; m = $m; target = $target.Path; at = (Get-Date).ToString('s') }
                    Save-Ledger -Map $ledger
                    $task.done++
                    continue
                }
                if ($s -gt 0 -and $partLen -gt $s) { Remove-Item -LiteralPath $part -Force; $partLen = 0 }
                $downloadUrl = 'http://127.0.0.1:8787/api/file?path=' + [Uri]::EscapeDataString($p)
                $web = [System.Net.HttpWebRequest]::Create($downloadUrl)
                $web.Timeout = 20000
                $web.ReadWriteTimeout = 30000
                $web.KeepAlive = $false
                if ($partLen -gt 0) { [void]$web.AddRange($partLen) }
                $Runtime.CurrentWeb = $web
                $resp = $web.GetResponse()
                $code = [int]$resp.StatusCode
                $append = $false
                if ($code -eq 206) { $append = $true }
                elseif ($code -eq 200) {
                    if ($partLen -gt 0) { Remove-Item -LiteralPath $part -Force; $partLen = 0 }
                } else { throw ('HTTP ' + $code) }
                $mode = [IO.FileMode]::Create
                if ($append) { $mode = [IO.FileMode]::Append }
                $fs = [IO.File]::Open($part, $mode, [IO.FileAccess]::Write, [IO.FileShare]::Read)
                $input = $resp.GetResponseStream()
                $buffer = [byte[]]::new(131072)
                while ($true) {
                    if ($task.cancel -or $Runtime.QuitEvent.WaitOne(0)) { throw [System.OperationCanceledException]::new('用户已停止') }
                    $read = $input.Read($buffer, 0, $buffer.Length)
                    if ($read -le 0) { break }
                    $fs.Write($buffer, 0, $read)
                    $fileBytes += $read
                    $task.bytes = [long]$task.bytes + $read
                    $nowTicks = [DateTime]::UtcNow.Ticks
                    $samples.Enqueue([pscustomobject]@{ Ticks = $nowTicks; Bytes = [long]$read })
                    $window += $read
                    while ($samples.Count -gt 0) {
                        $old = $samples.Peek()
                        if (($nowTicks - $old.Ticks) -gt [TimeSpan]::TicksPerSecond) { [void]$samples.Dequeue(); $window -= $old.Bytes } else { break }
                    }
                    $task.speedBps = [long]$window
                }
                $fs.Flush()
                $fs.Dispose()
                $fs = $null
                $input.Close()
                $resp.Close()
                $resp = $null
                $Runtime.CurrentWeb = $null
                $finalLen = ([IO.FileInfo]::new($part)).Length
                if ($s -gt 0 -and $finalLen -ne $s) { throw ('文件大小不一致：期望 ' + $s + '，实际 ' + $finalLen) }
                [IO.File]::Move($part, $target.Path)
                $ledger[$key] = [ordered]@{ p = $p; s = $s; m = $m; target = $target.Path; at = (Get-Date).ToString('s') }
                Save-Ledger -Map $ledger
                $task.done++
                Write-Log ('已接收：' + $target.Name + '（' + (Format-Bytes $finalLen) + '）')
            } catch {
                $ex = $_.Exception
                if ($task.cancel -or $Runtime.QuitEvent.WaitOne(0) -or $ex -is [System.OperationCanceledException]) { throw }
                $task.failed++
                $task.done++
                $task.error = Get-HttpErrorText -Exception $ex
                Write-Log ('文件接收失败：' + $name + '，原因：' + $task.error) '错误'
            } finally {
                if ($null -ne $fs) { try { $fs.Dispose() } catch { } }
                if ($null -ne $resp) { try { $resp.Close() } catch { } }
                if ($null -ne $web -and $Runtime.CurrentWeb -eq $web) { $Runtime.CurrentWeb = $null }
            }
        }
        if ($task.failed -gt 0) { $task.error = '有 ' + $task.failed + ' 个文件失败' }
        Write-Log ('接收完成：成功 ' + ($task.done - $task.failed - $task.skipped) + '，跳过 ' + $task.skipped + '，失败 ' + $task.failed)
    } catch {
        $ex = $_.Exception
        if ($ex -is [System.OperationCanceledException]) { $task.error = '已停止'; Write-Log '接收已停止' }
        else { $task.error = Get-HttpErrorText -Exception $ex; Write-Log ('接收失败：' + $task.error) '错误' }
    } finally {
        $task.active = $false
        $task.speedBps = 0
        $task.cancel = $false
        try { if ($null -ne $Runtime.CurrentWeb) { $Runtime.CurrentWeb.Abort(); $Runtime.CurrentWeb = $null } } catch { }
    }
}



function Write-HttpText {
    param($Context, [int]$Code, [string]$Text)
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
        $Context.Response.StatusCode = $Code
        $Context.Response.ContentType = 'application/json; charset=utf-8'
        $Context.Response.ContentLength64 = $bytes.Length
        $Context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
        $Context.Response.OutputStream.Close()
    } catch { }
}

function Read-RequestBody {
    param($Context)
    try {
        $stream = $Context.Request.InputStream
        if ($null -eq $stream) { return '' }
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8)
        try { return $reader.ReadToEnd() } finally { $reader.Close() }
    } catch { return '' }
}

# PowerShell 5.1 的 ConvertFrom-Json 在顶层是数组时，会被包成「一个元素是数组」，
# 直接 @() 包起来会得到 Count=1，随后把整个数组拼成字符串当成一个文件名（就出过这个错）。
# 这里统一摊平：确实嵌套就展开，普通对象数组原样返回。
function ConvertFrom-JsonList {
    param([string]$Json)
    if ([string]::IsNullOrWhiteSpace($Json)) { return @() }
    $parsed = $null
    try { $parsed = $Json | ConvertFrom-Json } catch { return @() }
    if ($parsed -is [System.Array]) { return $parsed }
    $list = [System.Collections.Generic.List[object]]::new()
    foreach ($x in @($parsed)) {
        if ($null -eq $x) { continue }
        if ($x -is [System.Array]) { foreach ($y in $x) { [void]$list.Add($y) } } else { [void]$list.Add($x) }
    }
    return , $list.ToArray()
}

# ConvertFrom-Json 在几 MB 的数组上要几十秒（PowerShell 5.1 的实现很慢）。
# 手机文件清单动辄几 MB，用 .NET 的序列化器解析，快一到两个数量级。
function ConvertFrom-JsonFast {
    param([string]$Json)
    if ([string]::IsNullOrWhiteSpace($Json)) { return @() }
    try {
        Add-Type -AssemblyName System.Web.Extensions -ErrorAction SilentlyContinue
        $ser = [System.Web.Script.Serialization.JavaScriptSerializer]::new()
        $ser.MaxJsonLength = [int]::MaxValue
        $obj = $ser.DeserializeObject($Json)
        if ($null -eq $obj) { return @() }
        if ($obj -is [System.Array]) { return $obj }
        return @($obj)
    } catch {
        return (ConvertFrom-JsonList $Json)
    }
}

function Get-RequestJson {
    param($Context)
    return (@{
        root = $script:Data
        disk = @{
            fs = [string](Get-DiskInfo).Fs
            free = [long](Get-DiskInfo).Free
            total = [long](Get-DiskInfo).Total
            writable = [bool](Get-DiskInfo).Writable
        }
        folders = @(Get-Folders)
        push = @{
            active = [bool]$script:Runtime.Push.active
            done = [int]$script:Runtime.Push.done
            total = [int]$script:Runtime.Push.total
            bytes = [long]$script:Runtime.Push.bytes
            skipped = [int]$script:Runtime.Push.skipped
            failed = [int]$script:Runtime.Push.failed
            current = [string]$script:Runtime.Push.current
            lastAt = [long]$script:Runtime.Push.lastAt
        }
    } | ConvertTo-Json -Depth 6 -Compress)
}

function Send-FileResponse {
    param($Context, [string]$Category, [string]$Date, [string]$Name)
    try {
        if ($script:Categories -notcontains $Category) { Write-HttpText $Context 400 '{"ok":false,"error":"分类不合法"}'; return }
        if ($Date -notmatch '^\d{4}-\d{2}-\d{2}$') { Write-HttpText $Context 400 '{"ok":false,"error":"日期不合法"}'; return }
        if ([string]::IsNullOrWhiteSpace($Name) -or $Name.Contains('..') -or $Name.IndexOfAny([char[]]@('\','/')) -ge 0) {
            Write-HttpText $Context 400 '{"ok":false,"error":"文件名不合法"}'
            return
        }
        $path = Join-Path (Join-Path (Join-Path $script:Data $Category) $Date) $Name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Write-HttpText $Context 404 '{"ok":false,"error":"文件不存在"}'; return }
        $file = [IO.FileInfo]::new($path)
        $Context.Response.StatusCode = 200
        $Context.Response.ContentType = 'application/octet-stream'
        $Context.Response.ContentLength64 = $file.Length
        $Context.Response.AddHeader('Content-Disposition', ('attachment; filename*=UTF-8''' + [Uri]::EscapeDataString($Name)))
        $stream = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try {
            $buffer = [byte[]]::new(131072)
            while ($true) {
                $read = $stream.Read($buffer, 0, $buffer.Length)
                if ($read -le 0) { break }
                $Context.Response.OutputStream.Write($buffer, 0, $read)
            }
        } finally { $stream.Dispose() }
        $Context.Response.OutputStream.Close()
    } catch { Write-Log ('发送文件失败：' + $_.Exception.Message) '错误' }
}

function Get-PushLedgerPath {
    return (Join-Path $script:Data '.pjm-push.tsv')
}

function Initialize-PushLedger {
    param($Runtime)
    $ledger = $Runtime.Ledger
    if ([bool]$ledger.loaded) { return }
    $ledger.loaded = $true
    $path = Get-PushLedgerPath
    if (-not (Test-Path -LiteralPath $path)) { return }
    try {
        foreach ($line in [IO.File]::ReadAllLines($path, $script:Utf8)) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $tab = $line.IndexOf([char]9)
            if ($tab -le 0) { continue }
            $key = $line.Substring(0, $tab)
            if (-not $ledger.map.ContainsKey($key)) { $ledger.map[$key] = $line.Substring($tab + 1) }
        }
    } catch {
        Write-Log ('推送记录读取失败：' + $_.Exception.Message) '警告'
    }
}

function Add-PushLedgerEntry {
    param($Runtime, [string]$Key, [string]$RelativePath)
    if ([string]::IsNullOrWhiteSpace($Key)) { return }
    $Runtime.Ledger.map[$Key] = $RelativePath
    try {
        [IO.File]::AppendAllText((Get-PushLedgerPath), $Key + [char]9 + $RelativePath + [Environment]::NewLine, $script:Utf8)
    } catch {
        Write-Log ('推送记录写入失败：' + $_.Exception.Message) '警告'
    }
}

# 推送是否还在进行：手机端 10 秒没有任何字节进来就认为已经停了，
# 否则手机断线会把「接收中」永远挂在那里，电脑端再也点不了接收。
function Test-PushLive {
    param($Runtime)
    $p = $Runtime.Push
    if ($null -eq $p -or -not [bool]$p.active) { return $false }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    return (($now - [long]$p.lastAt) -lt 10000)
}

function Receive-UploadFile {
    param($Context, $Runtime)
    $query = $Context.Request.QueryString
    $cat = [string]$query['cat']
    $date = [string]$query['date']
    $name = [string]$query['name']
    if ($script:Categories -notcontains $cat) { Write-HttpText $Context 400 '{"ok":false,"error":"分类不合法"}'; return }
    if ($date -notmatch '^[0-9]{4}-[0-9]{2}-[0-9]{2}$') { Write-HttpText $Context 400 '{"ok":false,"error":"日期不合法"}'; return }
    $safe = Get-SafeName $name
    $dir = Join-Path (Join-Path $script:Data $cat) $date
    if (-not (Test-Path -LiteralPath $dir)) { [void](New-Item -ItemType Directory -Path $dir -Force) }
    $length = [long]$Context.Request.ContentLength64
    $index = [int]$Context.Request.Headers['X-PJM-Index']
    $number = [int]$Context.Request.Headers['X-PJM-Total']
    $push = $Runtime.Push
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    # 上次推送已经停了 15 秒以上：这次是新一轮，计数从头开始
    if (($now - [long]$push.lastAt) -gt 15000) {
        $push.done = 0
        $push.bytes = [long]0
        $push.skipped = 0
        $push.failed = 0
        $push.startedAt = $now
    }
    $push.active = $true
    $push.current = $safe
    if ($number -gt 0) { $push.total = $number }
    $push.lastAt = $now
    $input = $Context.Request.InputStream

    # 按 uuid 认文件：这个文件以前收过（改名、换入库日期也算同一个），就不再重复落盘
    $uuid = [string]$query['uuid']
    if ([string]::IsNullOrWhiteSpace($uuid)) { $uuid = $safe }
    $ledgerKey = $cat + '|' + $uuid
    Initialize-PushLedger -Runtime $Runtime
    $known = ''
    if ($Runtime.Ledger.map.ContainsKey($ledgerKey)) { $known = [string]$Runtime.Ledger.map[$ledgerKey] }
    if (-not [string]::IsNullOrEmpty($known) -and (Test-Path -LiteralPath (Join-Path $script:Data $known) -PathType Leaf)) {
        $buffer = [byte[]]::new(262144)
        try { while ($input.Read($buffer, 0, $buffer.Length) -gt 0) { } } catch { }
        $push.skipped = [int]$push.skipped + 1
        $push.done = $index
        Write-Log ('已在电脑上，跳过：' + $cat + '/' + $date + '/' + $safe)
        Write-HttpText $Context 200 '{"ok":true,"skipped":true}'
        return
    }

    $target = Get-UniqueTarget -Directory $dir -FileName $safe
    $part = $target.Path + '.part'
    $stream = $null
    try {
        $stream = [IO.File]::Open($part, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $buffer = [byte[]]::new(262144)
        $written = [long]0
        $lastTick = $now
        while ($true) {
            $read = $input.Read($buffer, 0, $buffer.Length)
            if ($read -le 0) { break }
            $stream.Write($buffer, 0, $read)
            $written += $read
            $push.bytes = [long]$push.bytes + $read
            $t = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            if (($t - $lastTick) -gt 400) { $push.lastAt = $t; $lastTick = $t }
        }
        $stream.Dispose()
        $stream = $null
        if ($length -ge 0 -and $written -ne $length) { throw ('接收长度不一致：期望 ' + $length + '，实际 ' + $written) }
        [IO.File]::Move($part, $target.Path)
        Add-PushLedgerEntry -Runtime $Runtime -Key $ledgerKey -RelativePath ($cat + '/' + $date + '/' + $target.Name)
        $push.done = if ($index -gt 0) { $index } else { [int]$push.done + 1 }
        $push.lastAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        Write-Log ('已接收：' + $target.Name + '（' + (Format-Bytes $written) + '）')
        $json = '{"ok":true,"skipped":false,"name":' + (ConvertTo-Json -InputObject $target.Name) + ',"size":' + $written + '}'
        Write-HttpText $Context 200 $json
    } catch {
        $push.failed = [int]$push.failed + 1
        if ($null -ne $stream) { try { $stream.Dispose() } catch { } }
        try { if (Test-Path -LiteralPath $part) { Remove-Item -LiteralPath $part -Force } } catch { }
        Write-Log ('接收文件失败：' + $_.Exception.Message) '错误'
        try { Write-HttpText $Context 500 ('{"ok":false,"error":' + (ConvertTo-Json -InputObject $_.Exception.Message) + '}') } catch { }
    }
}

function Invoke-ServerLoop {
    param($Runtime)
    $listener = $null
    try {
        $listener = [System.Net.HttpListener]::new()
        $listener.Prefixes.Add('http://127.0.0.1:8788/')
        $listener.Start()
        $Runtime.Listener = $listener
        Write-Log '电脑端取回服务已启动：127.0.0.1:8788（手机可读 PJM-Transfer）'
        while (-not $Runtime.QuitEvent.WaitOne(0)) {
            $ctx = $null
            try { $ctx = $listener.GetContext() }
            catch {
                if ($Runtime.QuitEvent.WaitOne(0)) { break }
                Write-Log ('取回服务监听异常：' + $_.Exception.Message) '错误'
                break
            }
            try {
                $path = $ctx.Request.Url.AbsolutePath
                $query = $ctx.Request.QueryString
                switch ($path) {
                    '/api/pc/state' { Write-HttpText $ctx 200 (Get-RequestJson $ctx) }
                    '/api/pc/files' {
                        $cat = [string]$query['cat']
                        $date = [string]$query['date']
                        $dir = Join-Path (Join-Path $script:Data $cat) $date
                        $items = @()
                        if ((Test-Path -LiteralPath $dir) -and $script:Categories -contains $cat -and $date -match '^\d{4}-\d{2}-\d{2}$') {
                            $items = @(Get-ChildItem -LiteralPath $dir -File -ErrorAction SilentlyContinue | ForEach-Object { @{ name = $_.Name; size = [long]$_.Length } })
                        }
                        Write-HttpText $ctx 200 (ConvertTo-Json -InputObject @($items) -Depth 4 -Compress)
                    }
                    '/api/pc/file' {
                        Send-FileResponse $ctx ([string]$query['cat']) ([string]$query['date']) ([string]$query['name'])
                    }
                    '/api/pc/upload' {
                        # 手机端「发到电脑」：手机把文件推进来，电脑只负责落盘
                        Receive-UploadFile $ctx $Runtime
                    }
                    '/api/pc/uploaddone' {
                        $p = $Runtime.Push
                        $p.active = $false
                        $p.current = ''
                        Write-Log ('手机推送结束：完成 ' + $p.done + '/' + $p.total + ' · ' + (Format-Bytes $p.bytes) + ' · 跳过 ' + $p.skipped + ' · 失败 ' + $p.failed)
                        Write-HttpText $ctx 200 '{"ok":true}'
                    }
                    '/api/pc/pull' {
                        # 电脑端可选：主动把手机文件拉过来（手机推送不在进行时）
                        $dates = @()
                        $raw = Read-RequestBody $ctx
                        if (-not [string]::IsNullOrWhiteSpace($raw)) {
                            try {
                                $obj = $raw | ConvertFrom-Json
                                if ($null -ne $obj -and $null -ne $obj.dates) {
                                    foreach ($d in @($obj.dates)) {
                                        $s = [string]$d
                                        if ($s -match '^[0-9]{4}-[0-9]{2}-[0-9]{2}$') { $dates += $s }
                                    }
                                }
                            } catch { Write-Log ('解析请求体失败：' + $_.Exception.Message) '警告' }
                            $dates = @($dates | Select-Object -Unique)
                            if ($dates.Count -eq 0) { Write-Log ('请求体里没有日期：' + $raw) '警告' }
                        }
                        if ($Runtime.Task.active -or (Test-PushLive $Runtime)) { Write-HttpText $ctx 200 '{"ok":false,"error":"正在传输中"}'; break }
                        $started = Start-Pull -Runtime $Runtime -Mode 'pull' -Dates $dates
                        if ($started) {
                            if ($dates.Count -gt 0) { Write-Log ('手机端请求接收：' + ($dates -join ', ')) } else { Write-Log '手机端请求接收：全部文件' }
                            Write-HttpText $ctx 200 '{"ok":true}'
                        } else {
                            Write-HttpText $ctx 200 '{"ok":false,"error":"正在传输中"}'
                        }
                    }
                    '/api/pc/stop' {
                        Stop-Pull -Runtime $Runtime
                        Write-HttpText $ctx 200 '{"ok":true}'
                    }
                    default { Write-HttpText $ctx 404 '{"ok":false,"error":"接口不存在"}' }
                }
            } catch {
                Write-Log ('处理取回请求失败：' + $_.Exception.Message) '错误'
                try { Write-HttpText $ctx 500 '{"ok":false,"error":"内部错误"}' } catch { }
            }
        }
    } catch {
        Write-Log ('取回服务启动失败：' + $_.Exception.Message) '错误'
    } finally {
        if ($null -ne $listener) {
            try { $listener.Stop() } catch { }
            try { $listener.Close() } catch { }
        }
        $Runtime.Listener = $null
    }
}

$script:Runtime = $null
$script:Closing = $false
$script:Ui = @{}

function New-Runtime {
    $state = [hashtable]::Synchronized(@{
        Adb = ''
        Device = '未连接'
        DataRoot = $script:Data
        Fs = ''
        Free = [long]0
        Total = [long]0
        Writable = $false
    })
    $task = [hashtable]::Synchronized(@{
        active = $false
        done = 0
        total = 0
        bytes = [long]0
        speedBps = [long]0
        skipped = 0
        failed = 0
        error = $null
        cancel = $false
    })
    # 手机推送（手机是发送方，电脑只是被动收字节）
    $push = [hashtable]::Synchronized(@{
        active = $false
        done = 0
        total = 0
        bytes = [long]0
        skipped = 0
        failed = 0
        current = ''
        startedAt = [long]0
        lastAt = [long]0
    })
    # 已接收记录：cat|uuid → 相对路径。按 uuid 记账：改名、改入库日期都不会重复接收
    $ledger = [hashtable]::Synchronized(@{ loaded = $false; map = @{} })
    return [hashtable]::Synchronized(@{
        State = $state
        Task = $task
        Push = $push
        Ledger = $ledger
        LogQueue = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
        QuitEvent = [System.Threading.ManualResetEvent]::new($false)
        Listener = $null
        ServerJob = $null
        CurrentWeb = $null
        CurrentAdb = $null
        Job = $null
    })
}

function Select-AdbDevice {
    param([string]$AdbPath, $Runtime)
    $r = Invoke-Adb -AdbPath $AdbPath -Arguments 'devices' -TimeoutMs 8000 -Runtime $Runtime
    if ($r.ExitCode -ne 0) { return $null }
    $usb = $null
    $any = $null
    foreach ($line in $r.Out.Split([char[]]@(13, 10))) {
        $t = $line.Trim()
        if ([string]::IsNullOrEmpty($t) -or $t.StartsWith('List of devices')) { continue }
        if ($t -notmatch '^(\S+)\s+device$') { continue }
        $serial = $Matches[1]
        if ([string]::IsNullOrEmpty($any)) { $any = $serial }
        # USB 连接的序列号不含冒号；无线调试那种（ip:port）优先级更低
        if ($serial -notmatch ':') { $usb = $serial; break }
    }
    if ($null -ne $usb) { return $usb }
    return $any
}

function Initialize-Connection {
    param($Runtime)
    $adb = Find-Adb
    if ([string]::IsNullOrEmpty($adb)) {
        $Runtime.State.Device = '未找到 adb'
        Write-Log '没有找到 adb.exe：请把 adb 放进工具目录的 adb\ 子目录，或装到 PATH' '错误'
        return
    }
    $Runtime.State.Adb = $adb
    Write-Log ('使用 adb：' + $adb)
    $r = Invoke-Adb -AdbPath $adb -Arguments 'start-server' -TimeoutMs 15000 -Runtime $Runtime
    if ($r.ExitCode -ne 0) { Write-Log ('adb start-server 失败：' + ($r.Err + $r.Out).Trim()) '警告' }
    # 多设备（USB + 无线调试 + 模拟器）时先选定一台，否则后面的 forward/reverse 会直接失败
    $script:Serial = Select-AdbDevice -AdbPath $adb -Runtime $Runtime
    if ([string]::IsNullOrEmpty($script:Serial)) {
        $Runtime.State.Device = '未找到手机'
        Write-Log '没有检测到已授权的手机：请插好数据线并在手机上允许 USB 调试' '错误'
        return
    }
    Write-Log ('使用设备：' + $script:Serial)
    $Runtime.State.Device = '等待手机连接...'
    Write-Log '等待手机连接（最多 10 秒）...'
    $r = Invoke-Adb -AdbPath $adb -Arguments 'wait-for-device' -TimeoutMs 10000 -Runtime $Runtime
    if ($r.TimedOut) {
        $Runtime.State.Device = '未连接（等待超时）'
        Write-Log '没有等到手机：请检查数据线，并在手机上允许 USB 调试' '错误'
        return
    }
    $r = Invoke-Adb -AdbPath $adb -Arguments 'forward tcp:8787 tcp:8787' -TimeoutMs 8000 -Runtime $Runtime
    if ($r.ExitCode -ne 0) {
        $Runtime.State.Device = '隧道建立失败'
        Write-Log ('adb forward 失败：' + ($r.Err + $r.Out).Trim()) '错误'
        return
    }
    $Runtime.State.Device = '已连接'
    Write-Log 'USB 隧道已建立：127.0.0.1:8787 → 手机传输服务'
    # 反向隧道：手机访问 127.0.0.1:8788 时走 USB 到电脑的取回服务
    $r = Invoke-Adb -AdbPath $adb -Arguments 'reverse tcp:8788 tcp:8788' -TimeoutMs 8000 -Runtime $Runtime
    if ($r.ExitCode -eq 0) {
        Write-Log 'USB 反向隧道已建立：手机 127.0.0.1:8788 → 电脑取回服务'
    } else {
        Write-Log ('adb reverse 失败：' + ($r.Err + $r.Out).Trim()) '错误'
    }
}

function Start-Pull {
    param($Runtime, [string]$Mode = 'pull', [string[]]$Dates = @())
    if ($Mode -eq 'pull' -and $Runtime.Task.active) { return $false }
    $rs = [runspacefactory]::CreateRunspace()
    $rs.Open()
    $rs.SessionStateProxy.SetVariable('PjmLinkJob', @{ Runtime = $Runtime; Mode = $Mode; Dates = $Dates })
    $ps = [powershell]::Create()
    $ps.Runspace = $rs
    $quoted = "'" + $script:Path.Replace("'", "''") + "'"
    [void]$ps.AddScript('& ' + $quoted)
    $h = $ps.BeginInvoke()
    $entry = @{ Ps = $ps; Handle = $h; Runspace = $rs }
    if ($Mode -eq 'server') { $Runtime.ServerJob = $entry } else { $Runtime.Job = $entry }
    return $true
}

function Stop-Pull {
    param($Runtime)
    try { $Runtime.Task.cancel = $true } catch { }
    try { if ($null -ne $Runtime.CurrentWeb) { $Runtime.CurrentWeb.Abort() } } catch { }
    Write-Log '正在停止接收...'
}

function Wait-PullJob {
    param($Runtime, [int]$TimeoutMs = 5000)
    $job = $Runtime.Job
    if ($null -eq $job) { return }
    try {
        if (-not $job.Handle.IsCompleted) { [void]$job.Handle.AsyncWaitHandle.WaitOne($TimeoutMs) }
        if (-not $job.Handle.IsCompleted) {
            try { $job.Ps.Stop() } catch { }
            [void]$job.Handle.AsyncWaitHandle.WaitOne(1000)
        }
        try { [void]$job.Ps.EndInvoke($job.Handle) } catch { }
    } catch { }
    finally {
        try { $job.Ps.Dispose() } catch { }
        try { $job.Runspace.Dispose() } catch { }
        $Runtime.Job = $null
    }
}

function Start-MainWindow {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [System.Windows.Forms.Application]::EnableVisualStyles()
    [System.Windows.Forms.Application]::SetCompatibleTextRenderingDefault($false)

    $runtime = New-Runtime
    $script:Runtime = $runtime
    $script:Closing = $false
    if (-not (Test-Path -LiteralPath $script:Data)) { New-Item -ItemType Directory -Path $script:Data -Force | Out-Null }
    $disk = Get-DiskInfo
    $runtime.State.Fs = $disk.Fs
    $runtime.State.Free = $disk.Free
    $runtime.State.Total = $disk.Total
    $runtime.State.Writable = $disk.Writable

    # ---------- 配色与字号（统一一套，别再一屏三种蓝） ----------
    $bg = [System.Drawing.Color]::FromArgb(246, 247, 249)
    $card = [System.Drawing.Color]::White
    $line = [System.Drawing.Color]::FromArgb(226, 230, 237)
    $text = [System.Drawing.Color]::FromArgb(31, 36, 48)
    $muted = [System.Drawing.Color]::FromArgb(107, 114, 128)
    $primary = [System.Drawing.Color]::FromArgb(47, 107, 255)
    $danger = [System.Drawing.Color]::FromArgb(200, 60, 60)

    $form = [System.Windows.Forms.Form]::new()
    $form.Text = 'PJM Link'
    $form.ClientSize = [System.Drawing.Size]::new(760, 600)
    $form.FormBorderStyle = [System.Windows.Forms.FormBorderStyle]::FixedSingle
    $form.MaximizeBox = $false
    $form.StartPosition = [System.Windows.Forms.FormStartPosition]::CenterScreen
    $form.BackColor = $bg
    $form.ForeColor = $text
    $form.Font = [System.Drawing.Font]::new('Microsoft YaHei UI', 9)

    $font = [System.Drawing.Font]::new('Microsoft YaHei UI', 9)
    $bold = [System.Drawing.Font]::new('Microsoft YaHei UI', 10, [System.Drawing.FontStyle]::Bold)

    $title = [System.Windows.Forms.Label]::new()
    $title.Text = 'PJM Link'
    $title.Font = [System.Drawing.Font]::new('Microsoft YaHei UI', 16, [System.Drawing.FontStyle]::Bold)
    $title.ForeColor = $text
    $title.Location = [System.Drawing.Point]::new(20, 16)
    $title.Size = [System.Drawing.Size]::new(400, 32)
    $form.Controls.Add($title)

    $subtitle = [System.Windows.Forms.Label]::new()
    $subtitle.Text = 'USB 直连 · 只走线缆，不经过网络'
    $subtitle.Font = $font
    $subtitle.ForeColor = $muted
    $subtitle.Location = [System.Drawing.Point]::new(22, 48)
    $subtitle.Size = [System.Drawing.Size]::new(420, 20)
    $form.Controls.Add($subtitle)

    # 状态卡片：白底 + 1px 描边，四行内容左对齐
    $statusCard = [System.Windows.Forms.Panel]::new()
    $statusCard.BackColor = $card
    $statusCard.Location = [System.Drawing.Point]::new(20, 84)
    $statusCard.Size = [System.Drawing.Size]::new(720, 118)
    # PS 5.1 里 WinForms 事件不是属性：$statusCard.Paint 取出来是 $null，
    # 对它调 .Add() 会抛「不能对 Null 值表达式调用方法」，工具一启动就崩。
    # 必须用事件访问器 add_Paint 对应的 Add_Paint 方法。
    $statusCard.Add_Paint({
        param($sender, $e)
        $pen = [System.Drawing.Pen]::new($line, 1)
        try { $e.Graphics.DrawRectangle($pen, 0, 0, $sender.Width - 1, $sender.Height - 1) } finally { $pen.Dispose() }
    })
    $form.Controls.Add($statusCard)

    $lblDevice = [System.Windows.Forms.Label]::new()
    $lblDevice.Text = '设备：未连接'
    $lblDevice.Font = $bold
    $lblDevice.ForeColor = $text
    $lblDevice.AutoEllipsis = $true
    $lblDevice.Location = [System.Drawing.Point]::new(16, 14)
    $lblDevice.Size = [System.Drawing.Size]::new(688, 22)
    $statusCard.Controls.Add($lblDevice)

    $lblDisk = [System.Windows.Forms.Label]::new()
    $lblDisk.Text = '磁盘：'
    $lblDisk.Font = $font
    $lblDisk.ForeColor = $text
    $lblDisk.AutoEllipsis = $true
    $lblDisk.Location = [System.Drawing.Point]::new(16, 42)
    $lblDisk.Size = [System.Drawing.Size]::new(688, 20)
    $statusCard.Controls.Add($lblDisk)

    $lblData = [System.Windows.Forms.Label]::new()
    $lblData.Text = '保存位置：'
    $lblData.Font = $font
    $lblData.ForeColor = $muted
    $lblData.AutoEllipsis = $true
    $lblData.Location = [System.Drawing.Point]::new(16, 68)
    $lblData.Size = [System.Drawing.Size]::new(688, 20)
    $statusCard.Controls.Add($lblData)

    $lblAdb = [System.Windows.Forms.Label]::new()
    $lblAdb.Text = 'adb：'
    $lblAdb.Font = $font
    $lblAdb.ForeColor = $muted
    $lblAdb.AutoEllipsis = $true
    $lblAdb.Location = [System.Drawing.Point]::new(16, 92)
    $lblAdb.Size = [System.Drawing.Size]::new(688, 18)
    $statusCard.Controls.Add($lblAdb)

    # 主按钮：实心主色；次按钮：白底描边；停止：红字描边
    function New-FlatButton {
        param([string]$Text, [System.Drawing.Point]$Location, [System.Drawing.Size]$Size, [System.Drawing.Font]$Font, [System.Drawing.Color]$Back, [System.Drawing.Color]$Fore, [System.Drawing.Color]$Border)
        $b = [System.Windows.Forms.Button]::new()
        $b.Text = $Text
        $b.Font = $Font
        $b.Location = $Location
        $b.Size = $Size
        $b.FlatStyle = [System.Windows.Forms.FlatStyle]::Flat
        $b.FlatAppearance.BorderSize = 1
        $b.FlatAppearance.BorderColor = $Border
        $b.BackColor = $Back
        $b.ForeColor = $Fore
        $b.Cursor = [System.Windows.Forms.Cursors]::Hand
        $b.UseVisualStyleBackColor = $false
        return $b
    }

    $btnReceive = New-FlatButton '接收手机文件' ([System.Drawing.Point]::new(20, 218)) ([System.Drawing.Size]::new(190, 42)) $bold $primary ([System.Drawing.Color]::White) $primary
    $form.Controls.Add($btnReceive)

    $btnOpen = New-FlatButton '打开文件夹' ([System.Drawing.Point]::new(222, 218)) ([System.Drawing.Size]::new(150, 42)) $font $card $text $line
    $form.Controls.Add($btnOpen)

    $btnStop = New-FlatButton '停止' ([System.Drawing.Point]::new(384, 218)) ([System.Drawing.Size]::new(100, 42)) $font $card $danger $line
    $btnStop.Enabled = $false
    $form.Controls.Add($btnStop)

    $btnHelp = New-FlatButton '使用说明' ([System.Drawing.Point]::new(620, 218)) ([System.Drawing.Size]::new(120, 42)) $font $card $text $line
    $form.Controls.Add($btnHelp)

    $btnExit = New-FlatButton '退出' ([System.Drawing.Point]::new(620, 218)) ([System.Drawing.Size]::new(120, 42)) $font $card $muted $line
    $btnExit.Location = [System.Drawing.Point]::new(620, 218)
    $form.Controls.Add($btnExit)

    # 顶部右侧也放一个「使用说明」，避免按钮挤在一起
    $btnHelp.Location = [System.Drawing.Point]::new(620, 18)
    $btnHelp.Size = [System.Drawing.Size]::new(120, 30)
    $btnExit.Location = [System.Drawing.Point]::new(620, 218)
    $btnExit.Size = [System.Drawing.Size]::new(120, 42)

    $progress = [System.Windows.Forms.ProgressBar]::new()
    $progress.Location = [System.Drawing.Point]::new(20, 280)
    $progress.Size = [System.Drawing.Size]::new(720, 12)
    $progress.Minimum = 0
    $progress.Maximum = 100
    $progress.Style = [System.Windows.Forms.ProgressBarStyle]::Continuous
    $form.Controls.Add($progress)

    $lblSpeed = [System.Windows.Forms.Label]::new()
    $lblSpeed.Text = '就绪'
    $lblSpeed.Font = $font
    $lblSpeed.ForeColor = $muted
    $lblSpeed.AutoEllipsis = $true
    $lblSpeed.Location = [System.Drawing.Point]::new(20, 298)
    $lblSpeed.Size = [System.Drawing.Size]::new(720, 22)
    $form.Controls.Add($lblSpeed)

    $log = [System.Windows.Forms.TextBox]::new()
    $log.Multiline = $true
    $log.ReadOnly = $true
    $log.ScrollBars = [System.Windows.Forms.ScrollBars]::Vertical
    $log.WordWrap = $false
    $log.BorderStyle = [System.Windows.Forms.BorderStyle]::FixedSingle
    $log.Font = [System.Drawing.Font]::new('Consolas', 9)
    $log.Location = [System.Drawing.Point]::new(20, 330)
    $log.Size = [System.Drawing.Size]::new(720, 250)
    $log.BackColor = [System.Drawing.Color]::White
    $log.ForeColor = $text
    $form.Controls.Add($log)

    $tip = [System.Windows.Forms.ToolTip]::new()
    $tip.SetToolTip($lblAdb, '')
    $tip.SetToolTip($lblData, $script:Data)

    $timer = [System.Windows.Forms.Timer]::new()
    $timer.Interval = 300
    $script:Ui = @{
        Form = $form; Device = $lblDevice; Adb = $lblAdb; Data = $lblData; Disk = $lblDisk
        Receive = $btnReceive; Open = $btnOpen; Stop = $btnStop; Help = $btnHelp; Exit = $btnExit
        Progress = $progress; Speed = $lblSpeed; Log = $log; Timer = $timer; Tip = $tip
        Subtitle = $subtitle
    }

    $btnReceive.Add_Click({
        try {
            if ($script:Runtime.State.Device -ne '已连接') {
                [void][System.Windows.Forms.MessageBox]::Show($script:Ui.Form, '手机还没连接：请插好数据线并允许 USB 调试。', 'PJM Link', [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Warning)
                return
            }
            if ($script:Runtime.Task.active) { return }
            Write-Log '用户点击「接收手机文件」'
            [void](Start-Pull -Runtime $script:Runtime)
        } catch { Write-Log ('启动接收失败：' + $_.Exception.Message) '错误' }
    })

    $btnOpen.Add_Click({
        try {
            if (-not (Test-Path -LiteralPath $script:Data)) { New-Item -ItemType Directory -Path $script:Data -Force | Out-Null }
            [System.Diagnostics.Process]::Start('explorer.exe', ('"' + $script:Data + '"')) | Out-Null
        } catch { Write-Log ('打开文件夹失败：' + $_.Exception.Message) '错误' }
    })

    $btnStop.Add_Click({
        try { Stop-Pull -Runtime $script:Runtime } catch { Write-Log ('停止失败：' + $_.Exception.Message) '错误' }
    })

    $btnHelp.Add_Click({
        try {
            $help = @(
                '1. 手机用数据线连电脑，打开开发者选项里的 USB 调试，并在手机上点「允许」。',
                '2. 手机端：设置 → 传输到电脑 → 打开传输服务。',
                '3. 回到这个窗口点「接收手机文件」，文件会出现在本文件夹的 PJM-Transfer\分类\日期\ 下。',
                '4. 整个 PJM-Link 文件夹可以放到移动硬盘或 U 盘，位置随你。',
                '5. 关闭窗口即断开隧道，不会关闭 adb 服务、不会开机自启。'
            ) -join [Environment]::NewLine
            [void][System.Windows.Forms.MessageBox]::Show($script:Ui.Form, $help, '使用说明', [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Information)
        } catch { }
    })

    $btnExit.Add_Click({ $script:Ui.Form.Close() })

    $timer.Add_Tick({
        try {
            $rt = $script:Runtime
            if ($null -eq $rt) { return }
            $st = $rt.State
            $task = $rt.Task
            $deviceText = '设备：' + $st.Device
            $script:Ui.Device.Text = $deviceText
            $script:Ui.Adb.Text = 'adb：' + $st.Adb
            $script:Ui.Data.Text = '数据目录：' + $st.DataRoot
            $script:Ui.Disk.Text = '磁盘：' + $st.Fs + ' · 剩余 ' + (Format-Bytes $st.Free) + ' / ' + (Format-Bytes $st.Total)
            $push = $rt.Push
            $pushLive = Test-PushLive $rt
            if ($pushLive) {
                # 手机正在推送：进度由手机那边驱动，这里只做展示
                $pTotal = [Math]::Max(1, [int]$push.total)
                $script:Ui.Progress.Style = [System.Windows.Forms.ProgressBarStyle]::Continuous
                $script:Ui.Progress.Maximum = $pTotal
                $script:Ui.Progress.Value = [Math]::Min($pTotal, [Math]::Max(0, [int]$push.done))
            } elseif ($task.total -gt 0) {
                $script:Ui.Progress.Style = [System.Windows.Forms.ProgressBarStyle]::Continuous
                $script:Ui.Progress.Maximum = [Math]::Max(1, [int]$task.total)
                $script:Ui.Progress.Value = [Math]::Min([int]$task.total, [int]$task.done)
            } elseif ($task.active) {
                $script:Ui.Progress.Style = [System.Windows.Forms.ProgressBarStyle]::Marquee
            } else {
                $script:Ui.Progress.Style = [System.Windows.Forms.ProgressBarStyle]::Continuous
                $script:Ui.Progress.Value = 0
            }
            if ($pushLive) {
                $script:Ui.Speed.Text = '手机推送中 · ' + $push.done + '/' + $push.total + ' · ' + (Format-Bytes $push.bytes) + ' · 当前 ' + $push.current
            } elseif ($task.active) {
                $script:Ui.Speed.Text = '传输中 · ' + $task.done + '/' + $task.total + ' · ' + (Format-Bytes $task.bytes) + ' · ' + (Format-Bytes $task.speedBps) + '/s · 跳过 ' + $task.skipped + ' · 失败 ' + $task.failed
            } elseif ($task.total -gt 0) {
                $err = ''
                if (-not [string]::IsNullOrEmpty($task.error)) { $err = ' · ' + $task.error }
                $script:Ui.Speed.Text = '上次任务：' + $task.done + '/' + $task.total + ' · ' + (Format-Bytes $task.bytes) + ' · 跳过 ' + $task.skipped + ' · 失败 ' + $task.failed + $err
            } elseif ([int]$push.total -gt 0) {
                $script:Ui.Speed.Text = '上次推送：' + $push.done + '/' + $push.total + ' · ' + (Format-Bytes $push.bytes) + ' · 跳过 ' + $push.skipped + ' · 失败 ' + $push.failed
            } else {
                $script:Ui.Speed.Text = '就绪'
            }
            $script:Ui.Stop.Enabled = [bool]$task.active
            $script:Ui.Receive.Enabled = (-not [bool]$task.active) -and (-not $pushLive)
            $count = 0
            while ($count -lt 200) {
                $line = $null
                if (-not $rt.LogQueue.TryDequeue([ref]$line)) { break }
                $script:Ui.Log.AppendText($line + [Environment]::NewLine)
                $count++
            }
            if ($count -gt 0) {
                if ($script:Ui.Log.TextLength -gt 500000) { $script:Ui.Log.Text = $script:Ui.Log.Text.Substring($script:Ui.Log.TextLength - 400000) }
                $script:Ui.Log.SelectionStart = $script:Ui.Log.TextLength
                $script:Ui.Log.ScrollToCaret()
            }
        } catch { }
    })

    $form.Add_FormClosing({
        param($sender, $e)
        try {
            if ($script:Closing) { return }
            if ($script:Runtime.Task.active) {
                $answer = [System.Windows.Forms.MessageBox]::Show($script:Ui.Form, '正在传输，确定停止并退出吗？', 'PJM Link', [System.Windows.Forms.MessageBoxButtons]::YesNo, [System.Windows.Forms.MessageBoxIcon]::Question, [System.Windows.Forms.MessageBoxDefaultButton]::Button2)
                if ($answer -ne [System.Windows.Forms.DialogResult]::Yes) { $e.Cancel = $true; return }
            }
            $script:Closing = $true
            $script:Ui.Timer.Stop()
            $script:Runtime.QuitEvent.Set()
            try { if ($null -ne $script:Runtime.Listener) { $script:Runtime.Listener.Stop() } } catch { }
            try {
                if ($null -ne $script:Runtime.ServerJob) {
                    $script:Runtime.ServerJob.Ps.Stop()
                    $script:Runtime.ServerJob.Runspace.Dispose()
                }
            } catch { }
            Stop-Pull -Runtime $script:Runtime
            Wait-PullJob -Runtime $script:Runtime -TimeoutMs 5000
            if (-not [string]::IsNullOrEmpty($script:Runtime.State.Adb)) {
                Remove-Tunnels -AdbPath $script:Runtime.State.Adb -Runtime $script:Runtime
            }
            Stop-BundledAdb
            Write-Log 'PJM Link 已退出'
        } catch { }
    })

    Initialize-Connection -Runtime $runtime
    if ($runtime.State.Device -eq '已连接') { [void](Start-Pull -Runtime $runtime -Mode 'server') }
    $timer.Start()
    Write-Log ('PJM Link 启动，数据目录：' + $script:Data)
    [System.Windows.Forms.Application]::Run($form)
    # 兜底：窗口关掉后仍有残留 adb 或后台 runspace 时，让进程一定退出，
    # 否则这些句柄会一直锁着工具目录，用户删不掉文件夹。
    Stop-BundledAdb
    [Environment]::Exit(0)
}

$jobVar = Get-Variable -Name PjmLinkJob -Scope Global -ErrorAction SilentlyContinue
if ($null -ne $jobVar -and $jobVar.Value -is [hashtable]) {
    $script:Runtime = $jobVar.Value.Runtime
    $mode = 'pull'
    if ($null -ne $jobVar.Value.Mode) { $mode = [string]$jobVar.Value.Mode }
    if ($mode -eq 'server') {
        Invoke-ServerLoop -Runtime $script:Runtime
    } else {
        $dates = @()
        if ($null -ne $jobVar.Value.Dates) { $dates = @($jobVar.Value.Dates) }
        Invoke-PullJob -Runtime $script:Runtime -Dates $dates
    }
    return
}

try {
    Start-MainWindow
} catch {
    $errDetail = $_.Exception.Message
    $pos = $_.InvocationInfo.PositionMessage
    if (-not [string]::IsNullOrWhiteSpace($pos)) {
        $errDetail = $errDetail + ' | ' + (($pos -replace '\s+', ' ').Trim())
    }
    Write-Log ('启动失败：' + $errDetail) '错误'
    try {
        Add-Type -AssemblyName System.Windows.Forms
        [void][System.Windows.Forms.MessageBox]::Show('PJM Link 启动失败：' + $_.Exception.Message, 'PJM Link', [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Error)
    } catch { }
}

