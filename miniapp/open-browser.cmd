@echo off
setlocal
cd /d "%~dp0"
if errorlevel 1 goto failed
if exist ".task-log\browser-demo\h5\index.html" goto preview
call npm.cmd run build:demo
if errorlevel 1 goto failed
:preview
call npm.cmd run preview:demo
if errorlevel 1 goto failed
start "" "http://127.0.0.1:5196/"
exit /b 0
:failed
echo Preview could not start. Keep this window open for the error above.
pause
exit /b 1
