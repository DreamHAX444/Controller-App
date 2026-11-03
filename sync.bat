@echo off
setlocal enabledelayedexpansion

REM ==============================================================================
REM TRACE CONTROLLER - One-Click GitHub Codebase Synchronizer
REM Pulls remote changes & makes local codebase 100% identical to GitHub
REM ==============================================================================

echo.
echo ==============================================================================
echo   TRACE CONTROLLER -- GitHub Codebase Synchronizer
echo ==============================================================================
echo.

REM 1. Verify Git availability
where git >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Git is not installed or not in your system PATH.
    goto :END
)

REM 2. Determine current branch
for /f "tokens=*" %%b in ('git branch --show-current 2^>nul') do set "CURRENT_BRANCH=%%b"
if "%CURRENT_BRANCH%"=="" set "CURRENT_BRANCH=main"

echo [*] Target Branch : %CURRENT_BRANCH%
echo [*] Remote Origin : Fetching latest changes from GitHub...
echo.

REM 3. Fetch from origin
git fetch origin
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Failed to fetch from GitHub origin.
    echo Please check your internet connection or repository access permissions.
    goto :END
)

REM 4. Check if local is behind, ahead, or identical
for /f "tokens=*" %%c in ('git rev-parse HEAD 2^>nul') do set "LOCAL_HASH=%%c"
for /f "tokens=*" %%r in ('git rev-parse origin/%CURRENT_BRANCH% 2^>nul') do set "REMOTE_HASH=%%r"

echo [*] Local  Commit : !LOCAL_HASH:~0,7!
echo [*] Remote Commit : !REMOTE_HASH:~0,7!
echo.

REM 5. Hard reset local working tree to match GitHub origin exactly
echo [*] Synchronizing codebase to match origin/%CURRENT_BRANCH% exactly...
git reset --hard origin/%CURRENT_BRANCH%
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Git reset failed.
    goto :END
)

echo.
echo ==============================================================================
echo [SUCCESS] Codebase is now 100%% synchronized with GitHub origin/%CURRENT_BRANCH%!
echo ==============================================================================
echo.
echo Latest Commit Details:
git log -n 1 --color=always --stat
echo.

:END
if /i "%~1"=="-q" goto :EXIT
if /i "%~1"=="-y" goto :EXIT
if /i "%~1"=="-nopause" goto :EXIT

REM Pause if launched via double-click from Windows Explorer
echo %cmdcmdline% | findstr /i /c:"\"%~f0\"" >nul
if not errorlevel 1 (
    echo Press any key to close this window...
    pause >nul
)

:EXIT

endlocal
