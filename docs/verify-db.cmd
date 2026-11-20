@echo off
REM Import database.sql into a clean MySQL instance and print its self-check block.
REM Needs: mysql client (MYSQL_BIN), DB_PASSWORD, server on DB_PORT.
REM Usage: docs\verify-db.cmd ; log saved to temporary\dbpractice-m0\.
REM Keep comments ASCII: cmd.exe reads this file as GBK.
setlocal
set "DIR=%~dp0..\..\..\..\temporary\dbpractice-m0"
set "LOG=%DIR%\verify-db.log"
if not exist "%DIR%" mkdir "%DIR%"
if not defined DB_PORT set "DB_PORT=3307"
if not defined MYSQL_BIN set "MYSQL_BIN=D:\AAA_Software\MySQL\MySQL Server 8.0\bin\mysql.exe"
set "OPTS=--protocol=TCP -h 127.0.0.1 -P %DB_PORT% -u root -p%DB_PASSWORD% --default-character-set=utf8mb4"
"%MYSQL_BIN%" %OPTS% -e "DROP DATABASE IF EXISTS ferry_system;" > "%LOG%" 2>&1
"%MYSQL_BIN%" %OPTS% < "%~dp0..\database.sql" >> "%LOG%" 2>&1
set "CODE=%ERRORLEVEL%"
echo. >> "%LOG%"
echo --- structure check --- >> "%LOG%"
"%MYSQL_BIN%" %OPTS% -e "USE ferry_system; SHOW TABLES; SHOW TRIGGERS; SHOW PROCEDURE STATUS WHERE Db='ferry_system'; SELECT COUNT(*) AS seats FROM seat;" >> "%LOG%" 2>&1
type "%LOG%"
exit /b %CODE%