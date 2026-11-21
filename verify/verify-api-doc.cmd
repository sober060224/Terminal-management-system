@echo off
REM Compare response examples in docs/api.md with real API output (backend on 8089).
REM Usage: verify\verify-api-doc.cmd ; log saved to temporary\dbpractice-m0\.
REM Keep comments ASCII: cmd.exe reads this file as GBK, so CJK in REM can break parsing.
setlocal
set "DIR=%~dp0..\..\..\..\temporary\dbpractice-m0"
set "LOG=%DIR%\verify-api-doc.log"
if not exist "%DIR%" mkdir "%DIR%"
set PYTHONIOENCODING=utf-8
python "%~dp0verify_api_doc.py" > "%LOG%" 2>&1
set "CODE=%ERRORLEVEL%"
type "%LOG%"
exit /b %CODE%