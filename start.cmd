@echo off
REM Start MySQL + backend (8089). Double-click this file.
REM Uses system MySQL on 3306 if it is up, else starts the repo-local instance on 3307.
REM mysqld is looked up via FERRY_MYSQL_HOME, then PATH, then the usual install dirs.
REM Keep comments ASCII: cmd.exe reads this file as GBK.
setlocal
set "CODE=0"
set NETFILE=%TEMP%\ferry_net.txt
netstat -ano > "%NETFILE%"

set DB_PORT=
findstr "0.0.0.0:3306 " "%NETFILE%" | findstr "LISTENING" >nul
if not errorlevel 1 set DB_PORT=3306
if defined DB_PORT goto :db_ready
findstr "0.0.0.0:3307 " "%NETFILE%" | findstr "LISTENING" >nul
if not errorlevel 1 set DB_PORT=3307
if defined DB_PORT goto :db_ready

echo [1/2] starting repo-local MySQL on 3307 ...
if not defined MYSQL_DATA_DIR set "MYSQL_DATA_DIR=%~dp0temporary\mysql-local\data"
call :find_mysqld
if not defined MYSQLD_BIN goto :nomysql
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
echo [2/2] starting backend on 8089 ...
call run.cmd
set "CODE=%ERRORLEVEL%"
goto :end

:find_mysqld
if defined MYSQLD_BIN exit /b 0
if defined FERRY_MYSQL_HOME if exist "%FERRY_MYSQL_HOME%\bin\mysqld.exe" set "MYSQLD_BIN=%FERRY_MYSQL_HOME%\bin\mysqld.exe"
if defined MYSQLD_BIN exit /b 0
for /f "delims=" %%i in ('where mysqld 2^>nul') do if not defined MYSQLD_BIN set "MYSQLD_BIN=%%i"
if defined MYSQLD_BIN exit /b 0
for %%r in ("%ProgramFiles%\MySQL" "%ProgramFiles(x86)%\MySQL") do if exist "%%~r" for /d %%v in ("%%~r\MySQL Server*") do if not defined MYSQLD_BIN if exist "%%~v\bin\mysqld.exe" set "MYSQLD_BIN=%%~v\bin\mysqld.exe"
if defined MYSQLD_BIN exit /b 0
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0mysql-bin.ps1" server 2^>nul') do if not defined MYSQLD_BIN set "MYSQLD_BIN=%%i"
exit /b 0

:nomysql
echo [ERROR] mysqld not found. Add MySQL bin to PATH or set FERRY_MYSQL_HOME.
set "CODE=1"
goto :end

:nodir
echo [ERROR] cannot enter backend directory
set "CODE=1"

:end
endlocal & exit /b %CODE%
