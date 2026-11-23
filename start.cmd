@echo off
REM Start MySQL + backend (8089). Double-click this file.
REM Uses DB_PORT as given, else system MySQL on 3306 if it is up, else the repo-local instance on 3307.
REM mysqld is looked up via FERRY_MYSQL_HOME, then PATH, then the usual install dirs.
REM Keep comments ASCII: cmd.exe reads this file as GBK.
setlocal
set "CODE=0"
set NETFILE=%TEMP%\ferry_net.txt
netstat -ano > "%NETFILE%"

if defined DB_PORT goto :db_ready
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
if not exist "%MYSQL_DATA_DIR%\ibdata1" goto :nodata
start "ferry-mysql" /min "%MYSQLD_BIN%" --datadir="%MYSQL_DATA_DIR%" --port=3307 --mysqlx=OFF --console
call :wait_port 3307 30
if errorlevel 1 goto :nowait
set "DB_PORT=3307"

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

:wait_port
set "WAIT_PORT=%~1"
set /a WAIT_LEFT=%~2
:wait_loop
netstat -ano > "%NETFILE%"
findstr "0.0.0.0:%WAIT_PORT% " "%NETFILE%" | findstr "LISTENING" >nul
if not errorlevel 1 exit /b 0
set /a WAIT_LEFT=%WAIT_LEFT%-1
if %WAIT_LEFT% leq 0 exit /b 1
ping -n 2 127.0.0.1 >nul
goto :wait_loop

:nomysql
echo [ERROR] mysqld not found. Add MySQL bin to PATH or set FERRY_MYSQL_HOME.
set "CODE=1"
goto :end

:nodata
echo [ERROR] MySQL data dir missing or not initialized: %MYSQL_DATA_DIR%
echo Initialize it once, then rerun this script:
echo   "%MYSQLD_BIN%" --initialize-insecure --datadir="%MYSQL_DATA_DIR%" --console
set "CODE=1"
goto :end

:nowait
echo [ERROR] MySQL on port 3307 did not start listening within 30s.
echo Check the MySQL console window for the error and the data dir above.
set "CODE=1"
goto :end

:nodir
echo [ERROR] cannot enter backend directory
set "CODE=1"

:end
endlocal & exit /b %CODE%
