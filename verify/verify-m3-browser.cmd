@echo off
REM M3 real-browser acceptance: build a fresh paid order via the fixture, then drive the UI.
REM Chrome: FERRY_CHROME override, else Playwright's bundled Chromium.
REM Keep comments ASCII (cmd reads GBK).
REM Usage: verify/verify-m3-browser.cmd
REM Needs: backend on 8089, database.sql imported, npm install in frontend, python on PATH.
setlocal
if not defined FERRY_ARTIFACTS set "FERRY_ARTIFACTS=%~dp0..\temporary\dbpractice-m3"
set "DIR=%FERRY_ARTIFACTS%"
set "LOG=%FERRY_ARTIFACTS%\verify-m3-browser.log"
set "FIXTURE=%DIR%\fixture.json"
if not exist "%DIR%" mkdir "%DIR%"

REM 1) Build a fresh paid order + rebook target, save JSON for the browser script.
python "%~dp0m3_fixture.py" > "%FIXTURE%" 2>&1
if errorlevel 1 (
    type "%FIXTURE%"
    echo [ERROR] fixture 生成失败，见 %FIXTURE%
    exit /b 1
)

REM 2) Drive the browser with the fixture's account and order.
pushd "%~dp0..\frontend"
set "FERRY_FIXTURE=%FIXTURE%"
node e2e\m3-browser.mjs > "%LOG%" 2>&1
set "CODE=%ERRORLEVEL%"
popd
type "%LOG%"
exit /b %CODE%
