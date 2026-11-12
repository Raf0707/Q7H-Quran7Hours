@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo =============================================================
echo Quran7Hours / QPC V4 / Fatiha / Tafsir / Audio / Settings
 echo =============================================================
where py >nul 2>nul
if %errorlevel% EQU 0 (
    py -3 tools\install_patch.py --project .
) else (
    python tools\install_patch.py --project .
)
if errorlevel 1 (
    echo.
    echo STOPPED. No files should have been changed. See README_FIX_STEP2_RU.md
) else (
    echo.
    echo FINISHED. Check the output above for diary integration warning.
    echo Android Studio: Build ^> Rebuild Project
)
pause
