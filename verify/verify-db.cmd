@echo off
REM Import database.sql into a throwaway schema (ferry_verify_XXXXXXXX) and print its self-check block.
REM The real ferry_system database is never created or dropped by this script.
REM Port policy: 3306 (explicit or auto-detected) needs BOTH FERRY_ALLOW_3306=1 and
REM   FERRY_VERIFY_DB_OK=1; otherwise the workspace instance on 3307 is used.
REM Schema name: random and checked against @@datadir, so a leftover directory from a
REM   crashed run can never make CREATE DATABASE fail with ER 3681.
REM Needs: mysql client (FERRY_MYSQL_BIN, else PATH, else mysql-bin.ps1), DB_PASSWORD.
REM Usage: verify\verify-db.cmd ; log saved to temporary\dbpractice-m0\ in this repo (git-ignored).
REM Keep comments ASCII: cmd.exe reads this file as GBK.
setlocal
set "CODE="
if not defined FERRY_ARTIFACTS set "FERRY_ARTIFACTS=%~dp0..\temporary\dbpractice-m0\"
set "DIR=%FERRY_ARTIFACTS%"
set "LOG=%DIR%verify-db.log"
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
echo Set FERRY_ALLOW_3306=1 and FERRY_VERIFY_DB_OK=1 to confirm you want to create/drop ferry_verify_* there.
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

set "TMP_SQL=%DIR%ferry_verify.sql"
set "OPTS=--protocol=TCP -h 127.0.0.1 -P %DB_PORT% -u root -p%DB_PASSWORD% --default-character-set=utf8mb4"

echo using port %DB_PORT% > "%LOG%"
"%MYSQL_BIN%" %OPTS% -N -e "SELECT CONCAT('port=', @@port, ' datadir=', @@datadir);" >> "%LOG%" 2>&1
if errorlevel 1 (
    echo [ERROR] cannot connect to MySQL on port %DB_PORT%
    type "%LOG%"
    exit /b 1
)

REM A crashed run can leave a data directory behind without a registered schema, and then
REM CREATE DATABASE fails with ER 3681. Never delete anything: retry with another random
REM name, so a leftover directory can only ever cost one extra attempt.
set /a PICK=0
:pick_schema
set /a PICK+=1
if %PICK% GTR 5 (
    echo [ERROR] import failed 5 times, last schema %VERIFY_DB% >> "%LOG%"
    set "CODE=1"
    goto :cleanup
)
set "VERIFY_DB=ferry_verify_%RANDOM%%RANDOM%"
echo using port %DB_PORT% schema %VERIFY_DB% (attempt %PICK%) >> "%LOG%"

REM database.sql stays untouched: rewrite the schema name in a copy so its DROP/CREATE hit only the temp schema.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$t=[IO.File]::ReadAllText('%~dp0..\database.sql'); $t=$t -replace 'ferry_system','%VERIFY_DB%'; [IO.File]::WriteAllText('%TMP_SQL%', $t, (New-Object Text.UTF8Encoding($false)))"
if errorlevel 1 (
    echo [ERROR] cannot rewrite database.sql into %TMP_SQL% >> "%LOG%"
    set "CODE=1"
    goto :cleanup
)

"%MYSQL_BIN%" %OPTS% < "%TMP_SQL%" >> "%LOG%" 2>&1
set "IMPORT_CODE=%ERRORLEVEL%"
if not "%IMPORT_CODE%"=="0" (
    echo [WARN] import attempt %PICK% failed with exit %IMPORT_CODE%, retrying with a new schema name >> "%LOG%"
    goto :pick_schema
)
echo. >> "%LOG%"
echo --- structure check --- >> "%LOG%"
"%MYSQL_BIN%" %OPTS% -N -e "SELECT CONCAT('db=', DATABASE()) FROM DUAL; USE %VERIFY_DB%; SELECT CONCAT('after USE: ', DATABASE());" >> "%LOG%" 2>&1
if errorlevel 1 set "CODE=1"
"%MYSQL_BIN%" %OPTS% -e "USE %VERIFY_DB%; SHOW TABLES; SHOW TRIGGERS; SHOW PROCEDURE STATUS WHERE Db='%VERIFY_DB%'; SELECT COUNT(*) AS seats FROM seat;" >> "%LOG%" 2>&1
if errorlevel 1 set "CODE=1"
if defined IMPORT_CODE if not "%IMPORT_CODE%"=="0" set "CODE=1"

:cleanup
"%MYSQL_BIN%" %OPTS% -e "DROP DATABASE IF EXISTS %VERIFY_DB%;" >> "%LOG%" 2>&1
if errorlevel 1 set "CODE=1"
type "%LOG%"
if defined CODE exit /b 1
exit /b 0
