@echo off
setlocal
chcp 65001 >nul
pushd "%~dp0"
echo ========================================================
echo  Quran7Hours - Hafiz QPC V4 Tajweed Fix
 echo ========================================================
where py >nul 2>nul
if not errorlevel 1 (
  py -3 "%~dp0patch.py" "%~1"
  goto :end
)
where python >nul 2>nul
if not errorlevel 1 (
  python "%~dp0patch.py" "%~1"
  goto :end
)
echo ERROR: Python 3 is required.
set "result=1"
goto :finish
:end
set "result=%ERRORLEVEL%"
:finish
popd
echo.
if "%result%"=="0" (echo Patch completed.) else (echo Patch not applied. Read the error message.)
pause
exit /b %result%
