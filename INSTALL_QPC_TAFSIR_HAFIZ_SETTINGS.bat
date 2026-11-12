@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ===============================================
echo Quran7Hours - QPC V4 / Hafiz / Tafsir settings
echo ===============================================
where py >nul 2>nul
if %errorlevel% EQU 0 (
    py -3 tools\install_patch.py --project .
) else (
    python tools\install_patch.py --project .
)
if errorlevel 1 (
    echo.
    echo INSTALLATION STOPPED. Project files should be unchanged.
    echo See README_QPC_TAFSIR_HAFIZ_SETTINGS_RU.md
) else (
    echo.
    echo SUCCESS. Use Android Studio - Rebuild Project.
)
pause
