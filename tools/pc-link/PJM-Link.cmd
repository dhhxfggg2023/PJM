@echo off
setlocal
cd /d "%TEMP%"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0PJM-Link.ps1"
if errorlevel 1 (
  echo PJM-Link failed to start. See PJM-Link.log in the tool folder.
  pause
)
endlocal
