@echo off
setlocal
REM ==============================================================================
REM TRACE CONTROLLER - Unified Developer & Device Toolchain
REM ==============================================================================
REM USAGE:
REM   trace.bat [command] [options]
REM
REM COMMON COMMANDS:
REM   trace.bat                       Interactive developer control center menu
REM   trace.bat emu [profile|RAM]     Launch emulator (standard, pro, ultra, light, custom)
REM   trace.bat deploy, d             Build, install, and activate app on target device
REM   trace.bat live [target]         Stream live real-time logcat (controller, tracker, both)
REM                                   Hotkeys: [C] Controller, [T] Tracker, [B] Both, [Tab] Cycle
REM   trace.bat errors, err           Audit warnings, errors, crashes & save report
REM   trace.bat mirror, m             Launch low-latency screen mirror via scrcpy
REM   trace.bat doctor, doc           Run full environment & prerequisite diagnostic
REM ==============================================================================

set "TRACE_IS_DOUBLE_CLICK=0"
echo %cmdcmdline% | find /i "%~f0" >nul
if not errorlevel 1 set "TRACE_IS_DOUBLE_CLICK=1"

call "%~dp0..\trace.bat" %*
set "TRACE_EXIT_CODE=%ERRORLEVEL%"

if %TRACE_IS_DOUBLE_CLICK% equ 1 (
    echo.
    echo ------------------------------------------------------------------------------
    echo Execution finished with exit code %TRACE_EXIT_CODE%.
    echo Press any key to exit . . .
    pause >nul
)
endlocal & exit /b %TRACE_EXIT_CODE%
