@echo off
REM M2 real-browser acceptance: admin opens /admin/routes, /admin/ships, /admin/schedules.
REM Checks page titles, table rendering, no JS errors, no HTTP >= 400 responses.
REM Chrome: FERRY_CHROME override, else Playwright's bundled Chromium.
REM Usage: verify\verify-m2-browser.cmd
REM Needs: backend on 8089, database.sql imported, npm install in frontend, node on PATH.
REM Keep comments ASCII (cmd reads GBK). Log kept in temporary\dbpractice-m2\.
setlocal
if not defined FERRY_ARTIFACTS set "FERRY_ARTIFACTS=%~dp0..\temporary\dbpractice-m2\"
set "DIR=%FERRY_ARTIFACTS%"
set "LOG=%DIR%\verify-m2-browser.log"
if not exist "%DIR%" mkdir "%DIR%"
pushd "%~dp0..\frontend"
node e2e\m2-browser.mjs > "%LOG%" 2>&1
set "CODE=%ERRORLEVEL%"
popd
type "%LOG%"
exit /b %CODE%
