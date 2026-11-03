@echo off
setlocal
set "TRACE_IS_DOUBLE_CLICK=0"
echo %cmdcmdline% | find /i "%~f0" >nul
if not errorlevel 1 set "TRACE_IS_DOUBLE_CLICK=1"

call "%~dp0..\trace.bat" hyperv %*
set "TRACE_EXIT_CODE=%ERRORLEVEL%"

if %TRACE_IS_DOUBLE_CLICK% equ 1 (
    echo.
    echo ------------------------------------------------------------------------------
    echo Execution finished with exit code %TRACE_EXIT_CODE%.
    echo Press any key to exit . . .
    pause >nul
)
endlocal & exit /b %TRACE_EXIT_CODE%
