@echo off
setlocal
cd /d "%~dp0"
echo Quran7Hours - original QPC v4 Tajweed from older project
set /p "OLD_QPC=Enter path to Quran7HoursGrantUpd root: "
if "%OLD_QPC%"=="" (echo Missing old project folder & pause & exit /b 1)
py -3 tools\install_original_qpc_tafsir.py --source "%OLD_QPC%" --project "."
if errorlevel 1 (echo ERROR: the original QPC pack was NOT installed & pause & exit /b 1)
echo.
echo SUCCESS. Android Studio: Sync and Rebuild Project.
pause
