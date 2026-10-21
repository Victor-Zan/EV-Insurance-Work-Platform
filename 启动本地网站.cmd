@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Manage-LocalWebsite.ps1" -Action Start
if errorlevel 1 (
  pause
  exit /b 1
)
start "" "http://127.0.0.1:5173/login"
