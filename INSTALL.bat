@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
echo =========================================================
echo  Quran7Hours - Al-Fatiha 1:6 and 1:7 QPC Tajweed fix
echo =========================================================
where py >nul 2>nul
if not errorlevel 1 (
  py -3 "%~dp0patch_fatiha_6_7.py" "%~1"
  goto :after
)
where python >nul 2>nul
if not errorlevel 1 (
  python "%~dp0patch_fatiha_6_7.py" "%~1"
  goto :after
)
echo ERROR: Python 3 not found.
set "RESULT=1"
goto :done
:after
set "RESULT=%ERRORLEVEL%"
:done
echo.
if not "%RESULT%"=="0" echo PATCH FAILED. Check error above; source was not modified.
if "%RESULT%"=="0" echo PATCH OK. Rebuild app in Android Studio.
pause
exit /b %RESULT%
