@echo off
chcp 65001 > nul
cd /d "%~dp0"
py -3 tools\audit_qpc_tafsir_pack.py --project .
if errorlevel 1 (
    echo.
    echo QPC AUDIT FAILED. See QPC_TAFSIR_AUDIT.json
) else (
    echo.
    echo QPC AUDIT PASS: 604 layouts and both font collections are complete.
)
pause
