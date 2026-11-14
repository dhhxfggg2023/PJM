#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$zip = [IO.Path]::GetFullPath((Join-Path (Split-Path $root -Parent) 'PJM-Link.zip'))
$items = @(
    (Join-Path $root 'PJM-Link.ps1'),
    (Join-Path $root 'PJM-Link.cmd'),
    (Join-Path $root 'README.md')
)
$adbDir = Join-Path $root 'adb'
$hasAdb = $false
if (Test-Path -LiteralPath $adbDir) {
    $adbFiles = @(Get-ChildItem -LiteralPath $adbDir -File -Recurse -ErrorAction SilentlyContinue)
    if ($adbFiles.Count -gt 0) { $hasAdb = $true; $items += $adbDir }
}
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
function Format-BytesOnZip { param([long]$b) if ($b -ge 1MB) { return ('{0:N1} MB' -f ($b/1MB)) } return ($b.ToString() + ' B') }
Compress-Archive -Path $items -DestinationPath $zip -CompressionLevel Optimal -Force
$size = Format-BytesOnZip ([IO.FileInfo]::new($zip).Length)
if ($hasAdb) { Write-Host ('已生成：' + $zip + '（含 adb，' + $size + '）') }
else { Write-Host ('已生成：' + $zip + '（' + $size + '）。注意：没有找到 adb\ 目录，电脑上需要另外装 adb。') -ForegroundColor Yellow }
