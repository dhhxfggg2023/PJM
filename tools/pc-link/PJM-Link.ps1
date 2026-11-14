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
    $psi.Arguments = $Arguments
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
        if (-not $finished) { try { $p.Kill() } catch { } }
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
    param($Runtime)
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
        $items = @()
        if (-not [string]::IsNullOrWhiteSpace($json)) { $items = @($json | ConvertFrom-Json) }
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
    return [hashtable]::Synchronized(@{
        State = $state
        Task = $task
        LogQueue = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
        QuitEvent = [System.Threading.ManualResetEvent]::new($false)
        CurrentWeb = $null
        CurrentAdb = $null
        Job = $null
    })
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
}

function Start-Pull {
    param($Runtime)
    if ($Runtime.Task.active) { return $false }
    $rs = [runspacefactory]::CreateRunspace()
    $rs.Open()
    $rs.SessionStateProxy.SetVariable('PjmLinkJob', @{ Runtime = $Runtime })
    $ps = [powershell]::Create()
    $ps.Runspace = $rs
    $quoted = "'" + $script:Path.Replace("'", "''") + "'"
    [void]$ps.AddScript('& ' + $quoted)
    $h = $ps.BeginInvoke()
    $Runtime.Job = @{ Ps = $ps; Handle = $h; Runspace = $rs }
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

    $form = [System.Windows.Forms.Form]::new()
    $form.Text = 'PJM Link'
    $form.ClientSize = [System.Drawing.Size]::new(720, 420)
    $form.FormBorderStyle = [System.Windows.Forms.FormBorderStyle]::FixedSingle
    $form.MaximizeBox = $false
    $form.StartPosition = [System.Windows.Forms.FormStartPosition]::CenterScreen
    $form.BackColor = [System.Drawing.Color]::FromArgb(250, 250, 252)

    $font = [System.Drawing.Font]::new('Microsoft YaHei UI', 9)
    $bold = [System.Drawing.Font]::new('Microsoft YaHei UI', 10, [System.Drawing.FontStyle]::Bold)

    $group = [System.Windows.Forms.GroupBox]::new()
    $group.Text = '连接状态'
    $group.Font = $font
    $group.Location = [System.Drawing.Point]::new(12, 8)
    $group.Size = [System.Drawing.Size]::new(696, 96)
    $form.Controls.Add($group)

    $lblDevice = [System.Windows.Forms.Label]::new()
    $lblDevice.Text = '设备：未连接'
    $lblDevice.Font = $bold
    $lblDevice.AutoEllipsis = $true
    $lblDevice.Location = [System.Drawing.Point]::new(12, 22)
    $lblDevice.Size = [System.Drawing.Size]::new(670, 20)
    $group.Controls.Add($lblDevice)

    $lblAdb = [System.Windows.Forms.Label]::new()
    $lblAdb.Text = 'adb：'
    $lblAdb.AutoEllipsis = $true
    $lblAdb.Location = [System.Drawing.Point]::new(12, 44)
    $lblAdb.Size = [System.Drawing.Size]::new(670, 18)
    $group.Controls.Add($lblAdb)

    $lblData = [System.Windows.Forms.Label]::new()
    $lblData.Text = '数据目录：'
    $lblData.AutoEllipsis = $true
    $lblData.Location = [System.Drawing.Point]::new(12, 62)
    $lblData.Size = [System.Drawing.Size]::new(670, 18)
    $group.Controls.Add($lblData)

    $lblDisk = [System.Windows.Forms.Label]::new()
    $lblDisk.Text = '磁盘：'
    $lblDisk.AutoEllipsis = $true
    $lblDisk.Location = [System.Drawing.Point]::new(12, 78)
    $lblDisk.Size = [System.Drawing.Size]::new(670, 18)
    $group.Controls.Add($lblDisk)

    $btnReceive = [System.Windows.Forms.Button]::new()
    $btnReceive.Text = '接收手机文件'
    $btnReceive.Font = $bold
    $btnReceive.Location = [System.Drawing.Point]::new(12, 112)
    $btnReceive.Size = [System.Drawing.Size]::new(240, 60)
    $btnReceive.FlatStyle = [System.Windows.Forms.FlatStyle]::Flat
    $btnReceive.FlatAppearance.BorderColor = [System.Drawing.Color]::FromArgb(70, 110, 200)
    $btnReceive.BackColor = [System.Drawing.Color]::FromArgb(235, 242, 255)
    $form.Controls.Add($btnReceive)

    $btnOpen = [System.Windows.Forms.Button]::new()
    $btnOpen.Text = '打开文件夹'
    $btnOpen.Font = $font
    $btnOpen.Location = [System.Drawing.Point]::new(264, 112)
    $btnOpen.Size = [System.Drawing.Size]::new(190, 60)
    $btnOpen.FlatStyle = [System.Windows.Forms.FlatStyle]::Flat
    $form.Controls.Add($btnOpen)

    $btnStop = [System.Windows.Forms.Button]::new()
    $btnStop.Text = '停止'
    $btnStop.Font = $font
    $btnStop.Enabled = $false
    $btnStop.Location = [System.Drawing.Point]::new(468, 112)
    $btnStop.Size = [System.Drawing.Size]::new(110, 29)
    $form.Controls.Add($btnStop)

    $btnHelp = [System.Windows.Forms.Button]::new()
    $btnHelp.Text = '使用说明'
    $btnHelp.Font = $font
    $btnHelp.Location = [System.Drawing.Point]::new(468, 143)
    $btnHelp.Size = [System.Drawing.Size]::new(110, 29)
    $form.Controls.Add($btnHelp)

    $btnExit = [System.Windows.Forms.Button]::new()
    $btnExit.Text = '退出'
    $btnExit.Font = $font
    $btnExit.Location = [System.Drawing.Point]::new(586, 112)
    $btnExit.Size = [System.Drawing.Size]::new(122, 60)
    $btnExit.FlatStyle = [System.Windows.Forms.FlatStyle]::Flat
    $form.Controls.Add($btnExit)

    $progress = [System.Windows.Forms.ProgressBar]::new()
    $progress.Location = [System.Drawing.Point]::new(12, 182)
    $progress.Size = [System.Drawing.Size]::new(696, 20)
    $progress.Minimum = 0
    $progress.Maximum = 100
    $form.Controls.Add($progress)

    $lblSpeed = [System.Windows.Forms.Label]::new()
    $lblSpeed.Text = '就绪'
    $lblSpeed.AutoEllipsis = $true
    $lblSpeed.Location = [System.Drawing.Point]::new(12, 204)
    $lblSpeed.Size = [System.Drawing.Size]::new(696, 20)
    $form.Controls.Add($lblSpeed)

    $log = [System.Windows.Forms.TextBox]::new()
    $log.Multiline = $true
    $log.ReadOnly = $true
    $log.ScrollBars = [System.Windows.Forms.ScrollBars]::Vertical
    $log.WordWrap = $false
    $log.Font = [System.Drawing.Font]::new('Consolas', 9)
    $log.Location = [System.Drawing.Point]::new(12, 230)
    $log.Size = [System.Drawing.Size]::new(696, 178)
    $log.BackColor = [System.Drawing.Color]::FromArgb(252, 252, 252)
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
            if ($task.total -gt 0) {
                $script:Ui.Progress.Style = [System.Windows.Forms.ProgressBarStyle]::Continuous
                $script:Ui.Progress.Maximum = [Math]::Max(1, [int]$task.total)
                $script:Ui.Progress.Value = [Math]::Min([int]$task.total, [int]$task.done)
            } elseif ($task.active) {
                $script:Ui.Progress.Style = [System.Windows.Forms.ProgressBarStyle]::Marquee
            } else {
                $script:Ui.Progress.Style = [System.Windows.Forms.ProgressBarStyle]::Continuous
                $script:Ui.Progress.Value = 0
            }
            if ($task.active) {
                $script:Ui.Speed.Text = '传输中 · ' + $task.done + '/' + $task.total + ' · ' + (Format-Bytes $task.bytes) + ' · ' + (Format-Bytes $task.speedBps) + '/s · 跳过 ' + $task.skipped + ' · 失败 ' + $task.failed
            } elseif ($task.total -gt 0) {
                $err = ''
                if (-not [string]::IsNullOrEmpty($task.error)) { $err = ' · ' + $task.error }
                $script:Ui.Speed.Text = '上次任务：' + $task.done + '/' + $task.total + ' · ' + (Format-Bytes $task.bytes) + ' · 跳过 ' + $task.skipped + ' · 失败 ' + $task.failed + $err
            } else {
                $script:Ui.Speed.Text = '就绪'
            }
            $script:Ui.Stop.Enabled = [bool]$task.active
            $script:Ui.Receive.Enabled = -not [bool]$task.active
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
            Stop-Pull -Runtime $script:Runtime
            $script:Runtime.QuitEvent.Set()
            Wait-PullJob -Runtime $script:Runtime -TimeoutMs 5000
            if (-not [string]::IsNullOrEmpty($script:Runtime.State.Adb)) {
                Remove-Tunnels -AdbPath $script:Runtime.State.Adb -Runtime $script:Runtime
            }
            Write-Log 'PJM Link 已退出'
        } catch { }
    })

    Initialize-Connection -Runtime $runtime
    $timer.Start()
    Write-Log ('PJM Link 启动，数据目录：' + $script:Data)
    [System.Windows.Forms.Application]::Run($form)
}

$jobVar = Get-Variable -Name PjmLinkJob -Scope Global -ErrorAction SilentlyContinue
if ($null -ne $jobVar -and $jobVar.Value -is [hashtable]) {
    $script:Runtime = $jobVar.Value.Runtime
    Invoke-PullJob -Runtime $script:Runtime
    return
}

try {
    Start-MainWindow
} catch {
    Write-Log ('启动失败：' + $_.Exception.Message) '错误'
    try {
        Add-Type -AssemblyName System.Windows.Forms
        [void][System.Windows.Forms.MessageBox]::Show('PJM Link 启动失败：' + $_.Exception.Message, 'PJM Link', [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Error)
    } catch { }
}

