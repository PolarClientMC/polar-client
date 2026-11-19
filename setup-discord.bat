@echo off
rem Builds the Polar Client Discord server (channels, roles, permissions, posts). See tools\discord\setup_discord.py.
title Polar Client - Discord server setup
chcp 65001 >nul
set "SCRIPT=%~dp0tools\discord\setup_discord.py"
if not exist "%SCRIPT%" set "SCRIPT=%~dp0setup_discord.py"
set "PY="
where py >/dev/null 2>/dev/null && set "PY=py -3"
if not defined PY where python >/dev/null 2>/dev/null && set "PY=python"
if not defined PY (
  echo Python 3 is needed. Get it from https://www.python.org/downloads/ ^(tick "Add python.exe to PATH"^), then run this again.
  pause
  exit /b 1
)
%PY% "%SCRIPT%" %*
echo.
pause
