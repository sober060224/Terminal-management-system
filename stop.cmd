@echo off
REM Stop backend (8089) and the workspace MySQL instance (3307).
REM The system MySQL80 service on 3306 is left alone.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1"
ping -n 3 127.0.0.1 >nul
