# ==============================================================================
# TRACE CONTROLLER - Android Emulator Hardware Profile Launcher (PowerShell)
# ==============================================================================
# Usage:
#   .\launch_emulator.ps1 [profile|RAM] [-Options]
#
# Profiles: standard (4GB), pro (8GB), ultra (12GB/16GB), light (2GB),
#           cold (clean boot), headless (background/CI), custom (wizard)
# Examples:
#   .\launch_emulator.ps1                # Interactive profile menu (1-7)
#   .\launch_emulator.ps1 pro            # High Performance profile (8GB RAM, 6 Cores)
#   .\launch_emulator.ps1 8G             # 8GB RAM allocation
#   .\launch_emulator.ps1 custom         # Interactive 8-step hardware wizard
# ==============================================================================
& "$PSScriptRoot\..\trace.ps1" emu @args; exit $LASTEXITCODE
