@echo off
setlocal
REM ==============================================================================
REM TRACE CONTROLLER - Android Emulator Hardware Profile Launcher
REM ==============================================================================
REM USAGE:
REM   launch_emulator.bat [profile|RAM] [options]
REM
REM PROFILES:
REM   launch_emulator.bat             Interactive profile menu (1-7) & host specs
REM   launch_emulator.bat standard    Balanced / Standard (4GB RAM, 4 Cores, Host GPU)
REM   launch_emulator.bat pro         High Performance (8GB RAM, 6 Cores, Host GPU)
REM   launch_emulator.bat ultra       Ultra Performance (12-16GB RAM, 8 Cores, Host GPU)
REM   launch_emulator.bat light       Lightweight / Eco (2GB RAM, 2 Cores, No Audio)
REM   launch_emulator.bat cold        Clean Cold Boot (4GB RAM, bypass snapshots)
REM   launch_emulator.bat headless    Headless / Background (SwiftShader, No Window)
REM   launch_emulator.bat custom      Interactive 8-Step Hardware Builder Wizard
REM
REM CUSTOM RAM / HARDWARE FLAGS:
REM   launch_emulator.bat 8G          Direct RAM allocation (e.g. 4G, 8G, 6144M)
REM   launch_emulator.bat -RAM 8G -Cores 6 -ColdBoot -NoAudio
REM ==============================================================================

set "TRACE_IS_DOUBLE_CLICK=0"
echo %cmdcmdline% | find /i "%~f0" >nul
if not errorlevel 1 set "TRACE_IS_DOUBLE_CLICK=1"

call "%~dp0..\trace.bat" emu %*
set "TRACE_EXIT_CODE=%ERRORLEVEL%"

if %TRACE_IS_DOUBLE_CLICK% equ 1 (
    echo.
    echo ------------------------------------------------------------------------------
    echo Execution finished with exit code %TRACE_EXIT_CODE%.
    echo Press any key to exit . . .
    pause >nul
)
endlocal & exit /b %TRACE_EXIT_CODE%
