@echo off
REM M3 API acceptance: seed relative-time schedules, then run Python assertions.
REM Needs: mysql client (MYSQL_BIN), DB_PASSWORD, server on DB_PORT, backend on 8089.
REM Usage: verify\verify-m3.cmd ; log saved to temporary\dbpractice-m3\.
REM Keep comments ASCII: cmd.exe reads this file as GBK.
setlocal
set "DIR=%~dp0..\..\..\..\temporary\dbpractice-m3"
set "LOG=%DIR%\verify-m3.log"
if not exist "%DIR%" mkdir "%DIR%"

REM Auto-detect port: system MySQL80 on 3306, else workspace instance on 3307
if not defined DB_PORT (
    netstat -ano > "%TEMP%\ferry_net.txt"
    findstr "0.0.0.0:3306 " "%TEMP%\ferry_net.txt" | findstr "LISTENING" >nul
    if not errorlevel 1 (set "DB_PORT=3306") else (set "DB_PORT=3307")
)
if not defined MYSQL_BIN set "MYSQL_BIN=D:\AAA_Software\MySQL\MySQL Server 8.0\bin\mysql.exe"
set "OPTS=--protocol=TCP -h 127.0.0.1 -P %DB_PORT% -u root -p%DB_PASSWORD% --default-character-set=utf8mb4"

echo using port %DB_PORT% > "%LOG%"
"%MYSQL_BIN%" %OPTS% < "%~dp0m3-seed.sql" >> "%LOG%" 2>&1
if errorlevel 1 set "CODE=1"
if not defined CODE (
    python "%~dp0verify_m3.py" >> "%LOG%" 2>&1
    set "CODE=%ERRORLEVEL%"
)
type "%LOG%"
exit /b %CODE%
