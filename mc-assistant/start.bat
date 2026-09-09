@echo off
REM ---------------------------------------------------------------
REM  mc-assistant one-click start (Windows)
REM  Needs Node.js installed once: https://nodejs.org (LTS version)
REM ---------------------------------------------------------------
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo Node.js is not installed. Download the LTS version from:
  echo    https://nodejs.org
  echo Install it, then double-click this file again.
  echo.
  pause
  exit /b 1
)

if not exist node_modules (
  echo First run - installing dependencies, give it a minute...
  call npm install --no-audit --no-fund
)

if not exist .env (
  copy .env.example .env >nul
  echo.
  echo Created your .env config. Set MC_HOST to your server address and
  echo MC_OWNER to your Minecraft name, save, close Notepad, and the bot
  echo will start.
  echo.
  notepad .env
)

node src\index.js
pause
