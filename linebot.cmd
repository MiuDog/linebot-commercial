@echo off
chcp 65001 >nul
REM Launch the customer console without requiring manual Docker commands.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\customer-console.ps1" %*
if "%~1"=="" pause
