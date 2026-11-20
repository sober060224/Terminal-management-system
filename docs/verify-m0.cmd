@echo off
REM M0 API acceptance: assertions live in Python (cookie jar + JSON body).
REM Usage: docs/verify-m0.cmd
REM Needs: python on PATH, backend on 8089, database.sql imported. Keep comments ASCII (cmd reads GBK).
REM Log is kept in temporary\dbpractice-m0\ at the repo root.
setlocal
set "LOG=%~dp0..\..\..\..\temporary\dbpractice-m0\verify-m0.log"
if not exist "%~dp0..\..\..\..\temporary\dbpractice-m0" mkdir "%~dp0..\..\..\..\temporary\dbpractice-m0"
python "%~dp0verify_m0.py" > "%LOG%" 2>&1
type "%LOG%"
exit /b %ERRORLEVEL%