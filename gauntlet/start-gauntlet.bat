@echo off
rem Gauntlet launcher for Windows: double-click to start. Installs and builds on first run,
rem then opens the dashboard (on the API Keys page if no keys are saved yet).
title Gauntlet
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo  Node.js is not installed.
  echo  Download the LTS version from https://nodejs.org , install it, then double-click this file again.
  echo.
  pause
  exit /b 1
)

if not exist "node_modules\" (
  echo  First run: installing Gauntlet's building blocks. This takes a minute...
  call npm.cmd install --no-audit --no-fund
  if errorlevel 1 goto :fail
)

if not exist "ui\dist\index.html" (
  echo  Building the dashboard...
  call npm.cmd run build:ui
  if errorlevel 1 goto :fail
)

set "PAGE=/"
if not exist ".env" set "PAGE=/#/keys"

echo.
echo  Gauntlet is starting at http://localhost:7777
echo  Keep this window open while you use it. Close it (or press Ctrl+C) to stop.
echo.
start "" /min powershell -NoProfile -WindowStyle Hidden -Command "Start-Sleep -Seconds 3; Start-Process 'http://localhost:7777%PAGE%'"
node src\cli.ts serve
goto :eof

:fail
echo.
echo  Something went wrong above. Copy the red text and ask for help.
pause
exit /b 1
