@echo off
REM Compare response examples in docs/api.md with real API output (backend on 8089).
REM Usage: verify\verify-api-doc.cmd ; log saved to workspace temporary\dbpractice-m3\.
REM Keep comments ASCII: cmd.exe reads this file as GBK, so CJK in REM can break parsing.
setlocal
if not defined FERRY_ARTIFACTS set "FERRY_ARTIFACTS=%~dp0..\..\..\..\temporary\dbpractice-m3\"
set "DIR=%FERRY_ARTIFACTS%"
set "LOG=%DIR%\verify-api-doc.log"
if not exist "%DIR%" mkdir "%DIR%"
set PYTHONIOENCODING=utf-8
python "%~dp0verify_api_doc.py" > "%LOG%" 2>&1
set "CODE=%ERRORLEVEL%"
type "%LOG%"
exit /b %CODE%
