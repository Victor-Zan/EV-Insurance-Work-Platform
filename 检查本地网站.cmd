@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Manage-LocalWebsite.ps1" -Action Status
pause
