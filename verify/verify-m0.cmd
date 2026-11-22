@echo off
REM M0 API acceptance: assertions live in Python (cookie jar + JSON body).
REM Usage: verify/verify-m0.cmd
REM Needs: python on PATH, backend on 8089, database.sql imported. Keep comments ASCII (cmd reads GBK).
REM Log is kept in verify\artifacts\.
setlocal
set "DIR=%~dp0artifacts"
set "LOG=%DIR%\verify-m0.log"
if not exist "%DIR%" mkdir "%DIR%"
python "%~dp0verify_m0.py" > "%LOG%" 2>&1
type "%LOG%"
exit /b %ERRORLEVEL%
