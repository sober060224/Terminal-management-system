@echo off
REM Start MySQL + backend (8089). Double-click this file.
REM Uses system MySQL on 3306 if it is up, else starts the workspace instance on 3307.
setlocal
set NETFILE=%TEMP%\ferry_net.txt
netstat -ano > "%NETFILE%"

set DB_PORT=
findstr "0.0.0.0:3306 " "%NETFILE%" | findstr "LISTENING" >nul
if not errorlevel 1 set DB_PORT=3306
if defined DB_PORT goto :db_ready
findstr "0.0.0.0:3307 " "%NETFILE%" | findstr "LISTENING" >nul
if not errorlevel 1 set DB_PORT=3307
if defined DB_PORT goto :db_ready

echo [1/2] starting workspace MySQL on 3307 ...
if not defined MYSQLD_BIN set "MYSQLD_BIN=D:\AAA_Software\MySQL\MySQL Server 8.0\bin\mysqld.exe"
if not defined MYSQL_DATA_DIR set "MYSQL_DATA_DIR=%~dp0..\..\..\temporary\mysql-local\data"
start "ferry-mysql" /min "%MYSQLD_BIN%" --datadir="%MYSQL_DATA_DIR%" --port=3307 --mysqlx=OFF --console
set DB_PORT=3307
ping -n 9 127.0.0.1 >nul

:db_ready
echo [1/2] MySQL port = %DB_PORT%

cd /d "%~dp0backend"
if errorlevel 1 goto :nodir
if exist "target\classes\com\gdou\ferry\FerryApplication.class" goto :run
echo [2/2] first build ...
call mvnw.cmd -B -o compile
call mvnw.cmd -B -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt

:run
set "DB_PORT=%DB_PORT%"
echo [2/2] starting backend on 8089 ...
call run.cmd
goto :end

:nodir
echo [ERROR] cannot enter backend directory
:end
endlocal
