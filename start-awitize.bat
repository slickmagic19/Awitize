@echo off
title Awitize
rem Double-click launcher: starts the UI server, then the desktop window.
rem The window also starts the music backend itself if it is not running.
cd /d "%~dp0apps\desktop"
start "Awitize UI server" /min npm run dev
timeout /t 6 /nobreak >nul
npx electron .
pause
