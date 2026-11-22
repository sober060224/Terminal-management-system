@echo off
REM M0 real-browser acceptance with local Chrome (override via CHROME_PATH).
REM Covers three-role landing plus logout-then-login. Keep comments ASCII (cmd reads GBK).
REM Usage: verify/verify-m0-browser.cmd
REM Needs: backend on 8089, database.sql imported, npm install in frontend.
setlocal
if not defined FERRY_ARTIFACTS set "FERRY_ARTIFACTS=%~dp0..\..\..\..\temporary\dbpractice-m3\"
set "DIR=%FERRY_ARTIFACTS%"
set "LOG=%DIR%\verify-m0-browser.log"
if not exist "%DIR%" mkdir "%DIR%"
pushd "%~dp0..\frontend"
node e2e\m0-browser.mjs > "%LOG%" 2>&1
set "CODE=%ERRORLEVEL%"
popd
type "%LOG%"
exit /b %CODE%
