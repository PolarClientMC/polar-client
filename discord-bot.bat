@echo off
setlocal
title Polar Client - set up Polar Bot (Discord)
cd /d "%~dp0"
where node >nul 2>&1 || (echo Node.js is not installed: https://nodejs.org & pause & exit /b 1)
rem Uploads the Polar server (Polar Bot lives in it), then connects the Discord bot to it.
node tools\server.js --discord %*
set "CODE=%ERRORLEVEL%"
echo.
pause
exit /b %CODE%
