@echo off
chcp 65001 >nul
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel% EQU 0 (
    py -3 tools\restore_patch.py --project .
) else (
    python tools\restore_patch.py --project .
)
pause
