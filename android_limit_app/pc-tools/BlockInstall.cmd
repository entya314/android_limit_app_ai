@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0FocusDpcMaintenance.ps1" -Action Close
pause
