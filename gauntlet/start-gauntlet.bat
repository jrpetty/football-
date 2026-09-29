@echo off
rem Gauntlet launcher for Windows: double-click to start.
rem  - Installs Node.js for you if it is missing (asks first).
rem  - Installs and builds on first run AND after you unzip a new version (over the old folder or anywhere else).
rem  - Puts a "Gauntlet" shortcut on your desktop, pointing at THIS folder (refreshed every time).
rem  - Opens the dashboard. With no API key saved yet, it opens on the welcome page.
rem Your keys, runs and settings live in %APPDATA%\Gauntlet, not in this folder, so updating never loses them.
rem Batch note: no brackets inside if-blocks below; folder paths reach PowerShell through environment variables,
rem so spaces, OneDrive folders and apostrophes in the path are all fine.
title Gauntlet
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 goto :nonode

node scripts\prepare.mjs
if errorlevel 3 goto :running
if errorlevel 1 goto :fail

rem Remember where the old desktop shortcut pointed: Gauntlet copies keys, runs and settings from that folder once.
set "GAUNTLET_OLD_FOLDER="
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "$l = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Gauntlet.lnk'; if (Test-Path -LiteralPath $l) { (New-Object -ComObject WScript.Shell).CreateShortcut($l).WorkingDirectory }" 2^>nul`) do set "GAUNTLET_OLD_FOLDER=%%i"

rem Desktop shortcut, re-made every start so it always opens the newest copy of Gauntlet.
set "GAUNTLET_BAT=%~f0"
set "GAUNTLET_DIR=%~dp0"
powershell -NoProfile -Command "$d = [Environment]::GetFolderPath('Desktop'); $s = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $d 'Gauntlet.lnk')); $s.TargetPath = $env:GAUNTLET_BAT; $s.WorkingDirectory = $env:GAUNTLET_DIR; $s.IconLocation = (Join-Path $env:SystemRoot 'System32\shell32.dll') + ',137'; $s.Description = 'Start Gauntlet'; $s.Save()" >nul 2>nul
if errorlevel 1 echo  Note: couldn't make the desktop shortcut. You can always double-click start-gauntlet.bat in this folder.
if not errorlevel 1 echo  The "Gauntlet" shortcut on your desktop opens this copy: double-click it next time.

echo.
echo  Gauntlet is starting at http://localhost:7777
echo  Keep this window open while you use it. Close it (or press Ctrl+C) to stop.
echo.
start "" /min powershell -NoProfile -WindowStyle Hidden -Command "Start-Sleep -Seconds 3; Start-Process 'http://localhost:7777/'"
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
