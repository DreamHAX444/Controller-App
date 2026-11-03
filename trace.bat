@echo off
setlocal

REM 1. Auto-detect PowerShell Core (pwsh.exe) vs Windows PowerShell (powershell.exe)
where pwsh >nul 2>&1
if %ERRORLEVEL% equ 0 (
    set "TRACE_PS_EXE=pwsh"
) else (
    set "TRACE_PS_EXE=powershell"
)

REM 2. Execute unified master script with NoProfile and ExecutionPolicy Bypass
"%TRACE_PS_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0trace.ps1" %*
set "TRACE_EXIT_CODE=%ERRORLEVEL%"

REM 3. If an error occurred when launched via double-click (no args), pause so the error is visible
if %TRACE_EXIT_CODE% neq 0 (
    if "%~1"=="" (
        echo.
        echo [!] Trace exited with error code %TRACE_EXIT_CODE%.
        pause
    )
)

REM 4. Clean environment and return exit code without terminating interactive terminal sessions
endlocal & exit /b %TRACE_EXIT_CODE%
