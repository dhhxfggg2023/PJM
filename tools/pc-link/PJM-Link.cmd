@echo off
setlocal
rem 先切到 TEMP 再启动 PowerShell。
rem 原因：如果当前目录仍是工具目录，cmd.exe（以及继承它工作目录的子进程）会把它
rem 当成"正在使用的文件夹"，用户关掉窗口后也删不掉整个 PJM-Link 目录。
cd /d "%TEMP%"
rem 失败时不要窗口瞬关：PowerShell 非零退出（$ErrorActionPreference='Stop' 抛错）时
rem 把提示留在屏幕上并等按键，用户才有机会看到 PJM-Link.log 这行提示。
rem 注意：这里刻意**不加 chcp** —— 在批处理中间切换代码页会让 cmd 按旧代码页算出的
rem 文件偏移错位，进而报出莫名其妙的"xxx 不是内部或外部命令"。
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0PJM-Link.ps1" || (echo PJM-Link 启动失败，请查看 PJM-Link.log & pause)
endlocal
