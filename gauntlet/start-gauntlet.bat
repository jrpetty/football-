@echo off
rem Gauntlet launcher for Windows: double-click to start.
rem  - Installs Node.js for you if it is missing (asks first).
rem  - Installs and builds on first run AND after you unzip a new version over the old folder.
rem  - Puts a "Gauntlet" shortcut on your desktop.
rem  - Opens the dashboard (on the API Keys page if no keys are saved yet).
title Gauntlet
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 goto :nonode

node scripts\prepare.mjs
if errorlevel 3 goto :running
if errorlevel 1 goto :fail

rem One-time desktop shortcut (plain lines, not an if-block: the PowerShell command contains brackets).
if exist "%~dp0.shortcut-made" goto :launch
powershell -NoProfile -Command "$d=[Environment]::GetFolderPath('Desktop'); $s=(New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $d 'Gauntlet.lnk')); $s.TargetPath='%~f0'; $s.WorkingDirectory='%~dp0'; $s.IconLocation='%SystemRoot%\System32\shell32.dll,137'; $s.Save()" >nul 2>nul
if errorlevel 1 goto :launch
echo shortcut> "%~dp0.shortcut-made"
echo  A "Gauntlet" shortcut is now on your desktop: double-click it next time.

:launch

set "PAGE=/"
if not exist ".env" set "PAGE=/#/keys"

echo.
echo  Gauntlet is starting at http://localhost:7777
echo  Keep this window open while you use it. Close it (or press Ctrl+C) to stop.
echo.
start "" /min powershell -NoProfile -WindowStyle Hidden -Command "Start-Sleep -Seconds 3; Start-Process 'http://localhost:7777%PAGE%'"
node src\cli.ts serve
goto :eof

:running
echo  Gauntlet is already running: opening it in your browser.
start "" "http://localhost:7777/"
timeout /t 3 >nul
goto :eof

:nonode
echo.
echo  Node.js (the engine Gauntlet runs on) is not installed.
where winget >nul 2>nul
if errorlevel 1 goto :manualnode
choice /c YN /m "  Install it now automatically (free, from nodejs.org)"
if errorlevel 2 goto :manualnode
winget install -e --id OpenJS.NodeJS.LTS --accept-source-agreements --accept-package-agreements
echo.
echo  Done. Close this window and double-click start-gauntlet.bat again.
pause
exit /b 0

:manualnode
echo  Download the LTS version from https://nodejs.org , install it, then double-click this file again.
echo.
pause
exit /b 1

:fail
echo.
echo  Something went wrong above. Copy the red text and ask for help.
pause
exit /b 1
