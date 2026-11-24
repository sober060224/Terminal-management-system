@echo off
REM M3 real-browser acceptance: build a fresh paid order via the fixture, then drive the UI.
REM Chrome: FERRY_CHROME override, else Playwright's bundled Chromium.
REM Keep comments ASCII (cmd reads GBK).
REM Usage: verify/verify-m3-browser.cmd
REM Needs: backend on 8089, database.sql imported, npm install in frontend, python on PATH,
REM        DB_PASSWORD set, and a mysql client (for cleaning up the fixture data).
setlocal
if not defined FERRY_ARTIFACTS set "FERRY_ARTIFACTS=%~dp0..\temporary\dbpractice-m3"
set "DIR=%FERRY_ARTIFACTS%"
set "LOG=%FERRY_ARTIFACTS%\verify-m3-browser.log"
set "FIXTURE=%DIR%\fixture.json"
if not exist "%DIR%" mkdir "%DIR%"

if not defined DB_PASSWORD (
    echo [ERROR] DB_PASSWORD is not set. Set it as an environment variable and retry.
    exit /b 1
)
if defined FERRY_MYSQL_BIN goto :mysql_ready
where mysql >nul 2>&1
if not errorlevel 1 (
    set "MYSQL_BIN=mysql"
    goto :mysql_ok
)
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\mysql-bin.ps1" client 2^>nul') do set "MYSQL_BIN=%%i"
if defined MYSQL_BIN goto :mysql_ok
echo [ERROR] mysql client not found: add MySQL bin to PATH or set FERRY_MYSQL_BIN
exit /b 1
:mysql_ready
set "MYSQL_BIN=%FERRY_MYSQL_BIN%"
:mysql_ok
set "FERRY_MYSQL_BIN=%MYSQL_BIN%"

REM 1) Build a fresh paid order + rebook target, save JSON for the browser script.
python "%~dp0m3_fixture.py" > "%FIXTURE%" 2>"%DIR%\fixture.err"
if errorlevel 1 (
    type "%DIR%\fixture.err"
    echo [ERROR] fixture failed, see %FIXTURE%
    exit /b 1
)

REM 2) Drive the browser with the fixture's account and order.
pushd "%~dp0..\frontend"
set "FERRY_FIXTURE=%FIXTURE%"
node e2e\m3-browser.mjs > "%LOG%" 2>&1
set "CODE=%ERRORLEVEL%"
popd
type "%LOG%"

REM The browser script refunds one ticket and rebooks the other, so the database
REM keeps one valid ticket plus two test accounts. Running the suite twice would
REM make the leak counters grow, so clean up right after (P2-7).
if not defined DB_PORT set "DB_PORT=3307"
python "%~dp0m3_fixture.py" --cleanup "%FIXTURE%" >> "%LOG%" 2>&1
if errorlevel 1 echo [WARN] fixture cleanup failed, see %LOG%
exit /b %CODE%
