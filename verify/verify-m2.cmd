@echo off
REM M2 API acceptance: routes / ships / schedules admin endpoints (docs/api.md section 8).
REM Assertions live in verify_m2.py (cookie jar + JSON body, CSRF via XSRF-TOKEN header).
REM Usage: verify\verify-m2.cmd
REM Needs: python on PATH, backend on 8089, database.sql imported. Keep comments ASCII (cmd reads GBK).
REM Log is kept in temporary\dbpractice-m2\ in this repo (git-ignored).
REM FERRY_BASE may override the API base; the reachability probe below uses 8089.
setlocal
if not defined FERRY_ARTIFACTS set "FERRY_ARTIFACTS=%~dp0..\temporary\dbpractice-m2\"
set "DIR=%FERRY_ARTIFACTS%"
set "LOG=%DIR%\verify-m2.log"
if not exist "%DIR%" mkdir "%DIR%"

REM Fail fast if the backend is not listening, otherwise every check reports 000.
python -c "import sys,urllib.request; r=urllib.request.urlopen('http://localhost:8089/api/auth/csrf', timeout=10); sys.exit(0 if r.status==200 else 1)" 2>>"%LOG%"
if errorlevel 1 (
    echo [ERROR] backend API unreachable at http://localhost:8089 - is the backend running?
    type "%LOG%"
    exit /b 1
)

python "%~dp0verify_m2.py" > "%LOG%" 2>&1
set "CODE=%ERRORLEVEL%"
type "%LOG%"
exit /b %CODE%
