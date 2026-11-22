@echo off
REM M3 real-browser acceptance with local Chrome (override via CHROME_PATH).
REM Covers passenger refund/rebook entry points and non-empty rebook candidates.
REM Keep comments ASCII (cmd reads GBK).
REM Usage: verify/verify-m3-browser.cmd
REM Needs: backend on 8089, database.sql imported, npm install in frontend.
setlocal
set "DIR=%~dp0..\..\..\..\temporary\dbpractice-m3"
set "LOG=%DIR%\verify-m3-browser.log"
if not exist "%DIR%" mkdir "%DIR%"
pushd "%~dp0..\frontend"
node e2e\m3-browser.mjs > "%LOG%" 2>&1
set "CODE=%ERRORLEVEL%"
popd
type "%LOG%"
exit /b %CODE%
