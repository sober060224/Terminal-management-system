@echo off
REM Import database.sql into a clean MySQL instance and print its self-check block.
REM Needs: mysql client (FERRY_MYSQL_BIN, else mysql on PATH), DB_PASSWORD, a server on DB_PORT.
REM Usage: verify\verify-db.cmd ; log saved to workspace temporary\dbpractice-m3\.
REM Keep comments ASCII: cmd.exe reads this file as GBK.
setlocal
if not defined FERRY_ARTIFACTS set "FERRY_ARTIFACTS=%~dp0..\..\..\..\temporary\dbpractice-m3\"
set "DIR=%FERRY_ARTIFACTS%"
set "LOG=%DIR%\verify-db.log"
if not exist "%DIR%" mkdir "%DIR%"

REM Auto-detect port: system MySQL80 on 3306, else workspace instance on 3307
if not defined DB_PORT (
    netstat -ano > "%TEMP%\ferry_net.txt"
    findstr "0.0.0.0:3306 " "%TEMP%\ferry_net.txt" | findstr "LISTENING" >nul
    if not errorlevel 1 (set "DB_PORT=3306") else (set "DB_PORT=3307")
)

REM mysql client: FERRY_MYSQL_BIN wins, else PATH lookup; fail with a Chinese hint.
if defined FERRY_MYSQL_BIN goto :mysql_ready
where mysql >nul 2>&1
if errorlevel 1 (
    echo 未找到 mysql 客户端，请把 MySQL bin 目录加入 PATH 或设置 FERRY_MYSQL_BIN
    exit /b 1
)
set "MYSQL_BIN=mysql"
goto :mysql_ok
:mysql_ready
set "MYSQL_BIN=%FERRY_MYSQL_BIN%"
:mysql_ok

set "OPTS=--protocol=TCP -h 127.0.0.1 -P %DB_PORT% -u root -p%DB_PASSWORD% --default-character-set=utf8mb4"
echo using port %DB_PORT%
"%MYSQL_BIN%" %OPTS% -e "DROP DATABASE IF EXISTS ferry_system;" > "%LOG%" 2>&1
"%MYSQL_BIN%" %OPTS% < "%~dp0..\database.sql" >> "%LOG%" 2>&1
set "CODE=%ERRORLEVEL%"
echo. >> "%LOG%"
echo --- structure check --- >> "%LOG%"
"%MYSQL_BIN%" %OPTS% -e "USE ferry_system; SHOW TABLES; SHOW TRIGGERS; SHOW PROCEDURE STATUS WHERE Db='ferry_system'; SELECT COUNT(*) AS seats FROM seat;" >> "%LOG%" 2>&1
type "%LOG%"
exit /b %CODE%
