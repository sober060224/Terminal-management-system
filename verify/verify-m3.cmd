@echo off
REM M3 API acceptance: seed relative-time schedules, then run Python assertions.
REM Port policy: 3306 needs FERRY_ALLOW_3306=1 + FERRY_VERIFY_DB_OK=1; otherwise 3307.
REM Needs: mysql client, DB_PASSWORD, MySQL on DB_PORT, and the backend on 8089 using the SAME database.
REM Usage: verify\verify-m3.cmd ; log saved to temporary\dbpractice-m3\ in this repo (git-ignored).
REM Keep comments ASCII: cmd.exe reads this file as GBK.
setlocal
set "CODE="
if not defined FERRY_ARTIFACTS set "FERRY_ARTIFACTS=%~dp0..\temporary\dbpractice-m3\"
set "DIR=%FERRY_ARTIFACTS%"
set "LOG=%DIR%verify-m3.log"
if not exist "%DIR%" mkdir "%DIR%"

if defined DB_PASSWORD goto :port
echo [ERROR] DB_PASSWORD is not set. Set it as an environment variable and retry.
exit /b 1

:port
if defined DB_PORT goto :port_given
set "DB_PORT=3307"
netstat -ano > "%TEMP%\ferry_net.txt"
findstr "0.0.0.0:3306 " "%TEMP%\ferry_net.txt" | findstr "LISTENING" >nul
if errorlevel 1 goto :port_ready
if not "%FERRY_ALLOW_3306%"=="1" goto :skip_3306
if not "%FERRY_VERIFY_DB_OK%"=="1" goto :skip_3306
set "DB_PORT=3306"
goto :port_ready

:skip_3306
echo [INFO] 3306 is listening but FERRY_ALLOW_3306 + FERRY_VERIFY_DB_OK are not both 1; using 3307.
goto :port_ready

:port_given
if not "%DB_PORT%"=="3306" goto :port_ready
if not "%FERRY_ALLOW_3306%"=="1" goto :refuse_3306
if not "%FERRY_VERIFY_DB_OK%"=="1" goto :refuse_3306
goto :port_ready

:refuse_3306
echo [REFUSED] DB_PORT=3306 may be a shared instance.
echo Set FERRY_ALLOW_3306=1 and FERRY_VERIFY_DB_OK=1 to confirm you want to seed into it.
exit /b 1

:port_ready
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

set "OPTS=--protocol=TCP -h 127.0.0.1 -P %DB_PORT% -u root -p%DB_PASSWORD% --default-character-set=utf8mb4"

echo using port %DB_PORT% > "%LOG%"
"%MYSQL_BIN%" %OPTS% < "%~dp0m3-seed.sql" >> "%LOG%" 2>&1
if errorlevel 1 set "CODE=1"
if defined CODE goto :report

REM The departed schedule never appears in /api/schedules, so the seed tags it with price 99.99.
set "FERRY_PAST_SCHEDULE_ID="
REM for /f cannot carry a quoted executable path, so dump the id to a file and read it back.
"%MYSQL_BIN%" %OPTS% -N -s -e "USE ferry_system; SELECT id FROM schedule WHERE route_id=1 AND price=99.99 ORDER BY id DESC LIMIT 1;" > "%TEMP%\ferry_past_id.txt" 2>>"%LOG%"
set /p FERRY_PAST_SCHEDULE_ID=<"%TEMP%\ferry_past_id.txt"
if not defined FERRY_PAST_SCHEDULE_ID (
    echo [ERROR] seeded departed schedule not found; is the backend using this database?
    set "CODE=1"
    goto :report
)

python "%~dp0verify_m3.py" >> "%LOG%" 2>&1
if errorlevel 1 set "CODE=1"
if defined CODE goto :report
python "%~dp0concurrency_refund_m3.py" >> "%LOG%" 2>&1
if errorlevel 1 set "CODE=1"
if defined CODE goto :report
"%MYSQL_BIN%" %OPTS% < "%~dp0m3_view_check.sql" >> "%LOG%" 2>&1
if errorlevel 1 set "CODE=1"

:report
type "%LOG%"
if defined CODE exit /b 1
exit /b 0
