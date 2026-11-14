@echo off
setlocal
rem 先切到 TEMP 再启动 PowerShell。
rem 原因：如果当前目录仍是工具目录，cmd.exe（以及继承它工作目录的子进程）会把它
rem 当成"正在使用的文件夹"，用户关掉窗口后也删不掉整个 PJM-Link 目录。
cd /d "%TEMP%"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0PJM-Link.ps1"
endlocal
