<#
.SYNOPSIS
  Trace Master CLI -- Production Developer, Test, Deployment, Wireless & Release Toolchain.
.DESCRIPTION
  Unified automation CLI for Trace Android Controller & Live Tracker.
  Consolidates build automation, zero-touch device deployment, wireless ADB pairing,
  scrcpy screen mirroring, hardware-accelerated emulator management, update manifest
  generation, and GitHub Release asset publishing.
#>

# Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Command = "menu",

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
)

# 1. Enforce UTF-8 across console, streams, and process output
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# 2. Determine Repository & Script Paths Deterministically
$ScriptDir = $PSScriptRoot
$ProjectRoot = Resolve-Path (Join-Path $ScriptDir ".")
if (Test-Path (Join-Path $ProjectRoot "app\build.gradle.kts")) {
    # Current dir is controller_app root
} elseif (Test-Path (Join-Path $ProjectRoot "..\app\build.gradle.kts")) {
    $ProjectRoot = Resolve-Path (Join-Path $ScriptDir "..")
}
Set-Location $ProjectRoot

# 3. ANSI Color Tokens & Styling (Compatible with PowerShell 5.1 & PowerShell 7+)
$ESC       = [char]27
$C_RESET   = "$ESC[0m"
$C_BOLD    = "$ESC[1m"
$C_DIM     = "$ESC[2m"
$C_CYAN    = "$ESC[36m"
$C_BLUE    = "$ESC[34m"
$C_GREEN   = "$ESC[32m"
$C_YELLOW  = "$ESC[33m"
$C_RED     = "$ESC[31m"
$C_MAGENTA = "$ESC[35m"
$C_WHITE   = "$ESC[37m"

function Write-Banner([string]$Title, [string]$Subtitle = "") {
    Write-Host ""
    Write-Host "$C_CYAN======================================================================$C_RESET"
    Write-Host "  $C_BOLD$C_WHITE$Title$C_RESET"
    if ($Subtitle) {
        Write-Host "  $C_DIM$Subtitle$C_RESET"
    }
    Write-Host "$C_CYAN----------------------------------------------------------------------$C_RESET"
}

function Write-Success([string]$msg) {
    Write-Host "$C_GREEN[OK] $msg$C_RESET"
}
function Write-Warn([string]$msg) {
    Write-Host "$C_YELLOW[WARN] $msg$C_RESET"
}
function Write-ErrorMsg([string]$msg) {
    Write-Host "$C_RED[ERR] $msg$C_RESET"
}
function Write-Info([string]$msg) {
    Write-Host "$C_CYAN[INFO] $msg$C_RESET"
}
function Write-Step([string]$step, [string]$msg) {
    Write-Host "`n$C_BLUE$C_BOLD[$step]$C_RESET $C_WHITE$msg$C_RESET"
}

# ==============================================================================
# SECTION A: TOOLCHAIN RESOLUTION & PROCESS EXECUTION
# ==============================================================================

$Global:AdbPath = $null
$Global:EmulatorPath = $null
$Global:ApkSignerPath = $null
$Global:ScrcpyPath = $null
$Global:ScrcpyDir = $null

function Resolve-Toolchain {
    # 1. Locate Android SDK
    $possibleSdkPaths = @()
    
    # Check local.properties
    $localProp = Join-Path $ProjectRoot "local.properties"
    if (Test-Path $localProp) {
        Get-Content $localProp | ForEach-Object {
            if ($_ -match '^\s*sdk\.dir\s*=\s*(.*)') {
                $rawDir = $matches[1].Trim().Replace("\\", "\").Replace("\:", ":")
                $possibleSdkPaths += $rawDir
            }
        }
    }
    if ($env:ANDROID_HOME) { $possibleSdkPaths += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $possibleSdkPaths += $env:ANDROID_SDK_ROOT }
    $possibleSdkPaths += (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    $possibleSdkPaths += "C:\Users\ZNS\AppData\Local\Android\Sdk"

    $sdkPath = $null
    foreach ($p in $possibleSdkPaths) {
        if ($p -and (Test-Path $p)) {
            $sdkPath = (Resolve-Path $p).Path
            break
        }
    }

    # 2. Locate ADB
    $adbCandidates = @()
    if ($sdkPath) {
        $adbCandidates += (Join-Path $sdkPath "platform-tools\adb.exe")
    }
    $adbCandidates += "C:\Users\ZNS\Documents\phone\adb.exe"
    $adbCandidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")

    foreach ($cand in $adbCandidates) {
        if ($cand -and (Test-Path $cand)) {
            $Global:AdbPath = (Resolve-Path $cand).Path
            break
        }
    }
    if (-not $Global:AdbPath) {
        $cmd = Get-Command "adb" -ErrorAction SilentlyContinue
        if ($cmd) { $Global:AdbPath = $cmd.Source }
    }

    # Export $env:ADB for scrcpy integration
    if ($Global:AdbPath) {
        $env:ADB = $Global:AdbPath
    }

    # 3. Locate Emulator
    if ($sdkPath) {
        $emuCandidate = Join-Path $sdkPath "emulator\emulator.exe"
        if (Test-Path $emuCandidate) {
            $Global:EmulatorPath = (Resolve-Path $emuCandidate).Path
        }
    }
    if (-not $Global:EmulatorPath) {
        $cmd = Get-Command "emulator" -ErrorAction SilentlyContinue
        if ($cmd) { $Global:EmulatorPath = $cmd.Source }
    }

    # 4. Locate apksigner (Sort SemVer descending: 36 -> 35 -> 34)
    if ($sdkPath) {
        $buildToolsDir = Join-Path $sdkPath "build-tools"
        if (Test-Path $buildToolsDir) {
            $versions = Get-ChildItem -Path $buildToolsDir -Directory | Sort-Object {
                try { [version]$_.Name } catch { [version]"0.0.0" }
            } -Descending
            foreach ($v in $versions) {
                $cand = Join-Path $v.FullName "apksigner.bat"
                if (Test-Path $cand) {
                    $Global:ApkSignerPath = $cand
                    break
                }
            }
        }
    }

    # 5. Locate scrcpy
    $scrcpyCandidates = @(
        "C:\Users\ZNS\Documents\phone\scrcpy.exe",
        (Join-Path $env:USERPROFILE "Documents\phone\scrcpy.exe")
    )
    foreach ($sc in $scrcpyCandidates) {
        if (Test-Path $sc) {
            $Global:ScrcpyPath = (Resolve-Path $sc).Path
            $Global:ScrcpyDir = Split-Path -Parent $Global:ScrcpyPath
            break
        }
    }
    if (-not $Global:ScrcpyPath) {
        $cmd = Get-Command "scrcpy" -ErrorAction SilentlyContinue
        if ($cmd) {
            $Global:ScrcpyPath = $cmd.Source
            $Global:ScrcpyDir = Split-Path -Parent $Global:ScrcpyPath
        }
    }
}

Resolve-Toolchain

# Resilient non-deadlocking asynchronous ProcessStartInfo execution for ADB
function Invoke-Adb {
    param([string[]]$AdbArgs)
    if (-not $Global:AdbPath) {
        throw "ADB executable not found. Please install Android SDK platform-tools."
    }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Global:AdbPath
    $formattedArgs = foreach ($arg in $AdbArgs) {
        if ($arg -match '[\s"]' -or $arg -eq "") {
            "`"$($arg -replace '(\\*)(")', '$1$1\"' -replace '(\\+)$', '$1$1')`""
        } else {
            $arg
        }
    }
    $psi.Arguments = $formattedArgs -join ' '
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::Start($psi)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()

    $stdout = $stdoutTask.GetAwaiter().GetResult().Trim()
    $stderr = $stderrTask.GetAwaiter().GetResult().Trim()

    return [PSCustomObject]@{
        ExitCode = $process.ExitCode
        StdOut   = $stdout
        StdErr   = $stderr
        Output   = if ($stdout) { $stdout } else { $stderr }
    }
}

# Resilient Native Command Invoker for Gradle, Git, etc.
function Invoke-NativeTool {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$ScriptBlock,
        [string]$ErrorMsg = "Command failed",
        [int[]]$ValidCodes = @(0)
    )
    $prevNativePref = $false
    if (Test-Path Variable:PSNativeCommandUseErrorActionPreference) {
        $prevNativePref = $PSNativeCommandUseErrorActionPreference
        $PSNativeCommandUseErrorActionPreference = $false
    }
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $ScriptBlock
        $code = $LASTEXITCODE
        if ($ValidCodes -notcontains $code) {
            throw "$ErrorMsg (Exit code: $code)"
        }
        return $code
    } finally {
        $ErrorActionPreference = $prevEAP
        if (Test-Path Variable:PSNativeCommandUseErrorActionPreference) {
            $PSNativeCommandUseErrorActionPreference = $prevNativePref
        }
    }
}

# ==============================================================================
# SECTION B: CONNECTED DEVICES & FLEET DISCOVERY
# ==============================================================================

function Get-TraceDevices {
    $devices = @()
    if (-not $Global:AdbPath) { return $devices }

    $res = Invoke-Adb @("devices", "-l")
    $lines = $res.Output -split "`r?`n"

    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ($trimmed -and -not ($trimmed.StartsWith("List of devices")) -and -not ($trimmed.StartsWith("* daemon"))) {
            $parts = -split $trimmed
            if ($parts.Count -ge 2) {
                $serial = $parts[0]
                $status = $parts[1]
                
                $modelMatch = [regex]::Match($trimmed, 'model:([^\s]+)')
                $deviceMatch = [regex]::Match($trimmed, 'device:([^\s]+)')
                $productMatch = [regex]::Match($trimmed, 'product:([^\s]+)')
                
                $model = if ($modelMatch.Success) { $modelMatch.Groups[1].Value } else { "" }
                $product = if ($productMatch.Success) { $productMatch.Groups[1].Value } else { "" }
                
                $isRedmi = ($trimmed -match "22071219AI") -or ($trimmed -match "rock_in") -or ($model -match "22071219AI")
                $isEmulator = ($serial -match "^emulator-") -or ($trimmed -match "sdk_gphone") -or ($product -match "sdk_gphone")
                $isWireless = ($serial -match ':\d+$') -or ($serial -match '_adb-tls-connect')

                $marketName = if ($isRedmi) { "Xiaomi Redmi 11 Prime" }
                              elseif ($isEmulator) { "Pixel 10 Pro Emulator" }
                              elseif ($model) { $model }
                              else { "Android Device" }

                $devices += [PSCustomObject]@{
                    Serial      = $serial
                    Status      = $status
                    Model       = $model
                    Product     = $product
                    MarketName  = $marketName
                    IsRedmi     = $isRedmi
                    IsEmulator  = $isEmulator
                    IsWireless  = $isWireless
                    Raw         = $trimmed
                }
            }
        }
    }
    return $devices
}

# ==============================================================================
# SUBCOMMAND: STATUS & DOCTOR
# ==============================================================================

function Invoke-TraceStatus {
    Write-Banner "Fleet & System Status" "Live operational telemetry across devices, builds, and tools"

    # 1. Device Fleet
    $devs = @(Get-TraceDevices)
    Write-Host "`n$C_BOLD$C_WHITE  ATTACHED ADB DEVICES ($($devs.Count)):$C_RESET"
    Write-Host "  ------------------------------------------------------------------------------"
    Write-Host "  $($C_DIM)SERIAL             MODEL                  TYPE       STATUS    TRANSPORT$C_RESET"
    Write-Host "  ------------------------------------------------------------------------------"
    if ($devs.Count -eq 0) {
        Write-Host "  $C_YELLOW(No active devices connected. Run 'trace pair' or 'trace emu')$C_RESET"
    } else {
        foreach ($d in $devs) {
            $typeStr = if ($d.IsEmulator) { "Emulator " } elseif ($d.IsRedmi) { "Physical " } else { "Generic  " }
            $transStr = if ($d.IsWireless) { "Wi-Fi (TLS)" } else { "USB / Local" }
            $statColor = if ($d.Status -eq "device") { $C_GREEN } else { $C_RED }
            Write-Host "  $($d.Serial.PadRight(18)) $($d.MarketName.PadRight(22)) $typeStr $statColor$($d.Status.PadRight(9))$C_RESET $transStr"
        }
    }
    Write-Host "  ------------------------------------------------------------------------------`n"

    # 2. Build Artifacts
    $debugApk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
    $releaseApk = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk"
    $manifestFile = Join-Path $ProjectRoot "release\update-manifest.json"

    Write-Host "$C_BOLD$C_WHITE  BUILD ARTIFACTS:$C_RESET"
    if (Test-Path $debugApk) {
        $item = Get-Item $debugApk
        $sizeMb = [math]::Round($item.Length / 1048576, 2)
        $minsAgo = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes)
        Write-Host "  - Debug APK:   $C_GREEN$($item.Name)$C_RESET ($sizeMb MB) - $minsAgo mins ago"
    } else {
        Write-Host "  - Debug APK:   $C_DIM(Not assembled yet. Run 'trace deploy')$C_RESET"
    }
    if (Test-Path $releaseApk) {
        $item = Get-Item $releaseApk
        $sizeMb = [math]::Round($item.Length / 1048576, 2)
        $minsAgo = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes)
        Write-Host "  - Release APK: $C_GREEN$($item.Name)$C_RESET ($sizeMb MB) - $minsAgo mins ago"
    } else {
        Write-Host "  - Release APK: $C_DIM(Not assembled yet. Run 'trace release')$C_RESET"
    }
    if (Test-Path $manifestFile) {
        Write-Host "  - Manifest:    $C_GREEN$manifestFile$C_RESET"
    }
}

function Invoke-TraceDoctor {
    Write-Banner "Environment Doctor" "Verifying toolchains, drivers, and runtime prerequisites"

    $checks = @()

    # 1. Android SDK
    $sdkOk = $null -ne $Global:AdbPath
    $checks += [PSCustomObject]@{
        Component = "Android Platform Tools"
        Status    = if ($sdkOk) { "PASS" } else { "FAIL" }
        Details   = if ($sdkOk) { $Global:AdbPath } else { "Missing platform-tools/adb.exe" }
        Fix       = "Install Android SDK or set local.properties with sdk.dir=<path>"
    }

    # 2. ADB Daemon
    $adbVer = ""
    if ($Global:AdbPath) {
        $res = Invoke-Adb @("version")
        if ($res.Output -match 'Android Debug Bridge version ([\d\.]+)') {
            $adbVer = "v" + $matches[1]
        }
    }
    $checks += [PSCustomObject]@{
        Component = "ADB Daemon"
        Status    = if ($adbVer) { "PASS" } else { "FAIL" }
        Details   = if ($adbVer) { "$adbVer ($Global:AdbPath)" } else { "ADB failed to execute" }
        Fix       = "Run 'adb kill-server' followed by 'adb start-server'"
    }

    # 3. Android Emulator
    $emuOk = $null -ne $Global:EmulatorPath
    $checks += [PSCustomObject]@{
        Component = "Android Emulator"
        Status    = if ($emuOk) { "PASS" } else { "FAIL" }
        Details   = if ($emuOk) { $Global:EmulatorPath } else { "emulator.exe missing from SDK" }
        Fix       = "Install Android Emulator component via SDK Manager"
    }

    # 4. apksigner
    $signerOk = $null -ne $Global:ApkSignerPath
    $checks += [PSCustomObject]@{
        Component = "Android apksigner"
        Status    = if ($signerOk) { "PASS" } else { "FAIL" }
        Details   = if ($signerOk) { $Global:ApkSignerPath } else { "apksigner.bat not located in build-tools" }
        Fix       = "Install build-tools 34, 35, or 36 via SDK Manager"
    }

    # 5. Java Development Kit
    $jdkPath = $null
    $gradleProp = Join-Path $ProjectRoot "gradle.properties"
    if (Test-Path $gradleProp) {
        Get-Content $gradleProp | ForEach-Object {
            if ($_ -match '^\s*org\.gradle\.java\.home\s*=\s*(.*)') {
                $jdkPath = $matches[1].Trim()
            }
        }
    }
    $jdkOk = $jdkPath -and (Test-Path (Join-Path $jdkPath "bin\java.exe"))
    $checks += [PSCustomObject]@{
        Component = "Java Development Kit (JDK 17+)"
        Status    = if ($jdkOk) { "PASS" } else { "WARN" }
        Details   = if ($jdkOk) { "JBR: $jdkPath" } else { "org.gradle.java.home unset or invalid" }
        Fix       = "Set org.gradle.java.home in gradle.properties pointing to Android Studio JBR"
    }

    # 6. scrcpy Screen Mirroring
    $scrcpyOk = $null -ne $Global:ScrcpyPath
    $checks += [PSCustomObject]@{
        Component = "scrcpy Screen Mirror"
        Status    = if ($scrcpyOk) { "PASS" } else { "WARN" }
        Details   = if ($scrcpyOk) { $Global:ScrcpyPath } else { "scrcpy.exe not found in Documents\phone" }
        Fix       = "Extract scrcpy 4.1 to C:\Users\ZNS\Documents\phone"
    }

    # 7. Hardware Virtualization (AEHD)
    $aehdSvc = Get-Service -Name "aehd" -ErrorAction SilentlyContinue
    $aehdOk = $aehdSvc -and ($aehdSvc.Status -eq "Running")
    $checks += [PSCustomObject]@{
        Component = "Hypervisor Driver (AEHD)"
        Status    = if ($aehdOk) { "PASS" } else { "WARN" }
        Details   = if ($aehdOk) { "Google AEHD Driver Active" } else { "AEHD driver service not running" }
        Fix       = "Run 'trace aehd' to install and start the driver"
    }

    # 8. Git & Credentials
    $gitCmd = Get-Command "git" -ErrorAction SilentlyContinue
    $gcm = if ($gitCmd) { git config --get credential.helper } else { "" }
    $gitOk = $null -ne $gitCmd
    $checks += [PSCustomObject]@{
        Component = "Git & Credential Helper"
        Status    = if ($gitOk) { "PASS" } else { "FAIL" }
        Details   = if ($gitOk) { "Git present (helper: $gcm)" } else { "git not found in PATH" }
        Fix       = "Install Git for Windows from https://git-scm.com"
    }

    # Render Table
    Write-Host "`n  COMPONENT                 STATUS    DETAILS"
    Write-Host "  ------------------------------------------------------------------------------"
    foreach ($c in $checks) {
        $stColor = if ($c.Status -eq "PASS") { $C_GREEN } elseif ($c.Status -eq "WARN") { $C_YELLOW } else { $C_RED }
        Write-Host "  $($c.Component.PadRight(26)) $stColor$($c.Status.PadRight(9))$C_RESET $($c.Details)"
    }
    Write-Host "  ------------------------------------------------------------------------------`n"

    $failures = $checks | Where-Object { $_.Status -eq "FAIL" }
    if ($failures) {
        Write-Host "$C_RED[!] DOCTOR ACTION ITEMS:$C_RESET"
        foreach ($f in $failures) {
            Write-Host "  - $($f.Component): $($f.Fix)"
        }
        exit 1
    } else {
        Write-Success "All critical development components are verified and healthy!"
    }
}

function Resolve-TraceTargetDevice([string]$ExplicitSerial = "", [switch]$Emulator = $false) {
    $devs = @(Get-TraceDevices)
    if ($devs.Count -eq 0) {
        Write-ErrorMsg "No active ADB devices found. Ensure device is plugged in or run 'trace pair' for Wi-Fi."
        return $null
    }

    if ($ExplicitSerial) {
        $target = $devs | Where-Object { $_.Serial -eq $ExplicitSerial } | Select-Object -First 1
        if (-not $target) {
            Write-ErrorMsg "Specified -TargetSerial '$ExplicitSerial' is not connected."
            return $null
        }
        return $target
    }

    if ($Emulator) {
        $target = $devs | Where-Object { $_.IsEmulator -and $_.Status -eq "device" } | Select-Object -First 1
        if ($target) { return $target }
    }

    # 1. Prioritize physical Xiaomi Redmi 11 Prime
    $target = $devs | Where-Object { $_.IsRedmi -and $_.Status -eq "device" } | Select-Object -First 1
    if ($target) { return $target }

    # 2. Fallback to emulator
    $target = $devs | Where-Object { $_.IsEmulator -and $_.Status -eq "device" } | Select-Object -First 1
    if ($target) { return $target }

    # 3. Fallback to first ready device
    return ($devs | Where-Object { $_.Status -eq "device" } | Select-Object -First 1)
}

# ==============================================================================
# SUBCOMMAND: DEPLOY & RUN
# ==============================================================================

function Invoke-TraceDeploy {
    [CmdletBinding()]
    param(
        [switch]$SkipBuild = $false,
        [string]$TargetSerial = "",
        [switch]$Emulator = $false,
        [switch]$LaunchEmulator = $false,
        [int]$MemoryMb = 0,
        [switch]$Run = $false
    )

    Write-Banner "Deploy & Launch" "Building, installing (zero-touch), and activating on Android"

    # 1. Discover devices
    $devs = @(Get-TraceDevices)
    
    # Auto-launch emulator if requested or if zero devices are online
    if ($devs.Count -eq 0 -or ($Emulator -and (-not ($devs | Where-Object { $_.IsEmulator })))) {
        Write-Info "No active ADB devices found. Spawning Pixel 10 Pro Emulator..."
        Invoke-TraceEmulator -MemoryMb $MemoryMb -NoWait
        Start-Sleep -Seconds 3
        # Wait up to 30s for emulator to register
        for ($i = 0; $i -lt 15; $i++) {
            $devs = @(Get-TraceDevices)
            if ($devs | Where-Object { $_.IsEmulator }) { break }
            Start-Sleep -Seconds 2
        }
    }

    $target = Resolve-TraceTargetDevice -ExplicitSerial $TargetSerial -Emulator:$Emulator
    if (-not $target) {
        exit 1
    }

    Write-Success "Selected Target: $($C_BOLD)$($target.MarketName)$C_RESET ($($target.Serial))"

    # 2. Build Debug APK
    $apkPath = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
    if ($SkipBuild -and (Test-Path $apkPath)) {
        Write-Info "Reusing existing APK: $apkPath"
    } else {
        Write-Step "1/4" "Compiling Kotlin & Assembling Debug APK (:app:assembleDebug)..."
        $gradlew = Join-Path $ProjectRoot "gradlew.bat"
        Invoke-NativeTool -ScriptBlock { & $gradlew :app:assembleDebug --build-cache } -ErrorMsg "Gradle assembleDebug failed"
    }

    if (-not (Test-Path $apkPath)) {
        Write-ErrorMsg "APK not found at $apkPath"
        exit 1
    }

    $apkSize = [math]::Round((Get-Item $apkPath).Length / 1MB, 2)
    Write-Success "APK Verified: $apkPath ($apkSize MB)"

    # 3. Wake screen and unlock keyguard
    Write-Step "2/4" "Waking device display & dismissing keyguard..."
    Invoke-Adb @("-s", $target.Serial, "shell", "input", "keyevent", "224") | Out-Null
    Invoke-Adb @("-s", $target.Serial, "shell", "wm", "dismiss-keyguard") | Out-Null

    # 4. Zero-Touch Installation
    Write-Step "3/4" "Installing APK to $($target.Serial)..."
    $installed = $false

    # On Redmi / MIUI, streamed 'adb install' triggers restricted permission modal.
    # We use zero-touch push + pm install -r -t -d -g
    $remoteTmp = "/data/local/tmp/trace_deploy_$([System.Guid]::NewGuid().ToString('N').Substring(0, 8)).apk"
    try {
        $pushRes = Invoke-Adb @("-s", $target.Serial, "push", $apkPath, $remoteTmp)
        if ($pushRes.ExitCode -ne 0) {
            # Check if remote file size matches local APK size despite socket EOF
            $localSize = (Get-Item $apkPath).Length.ToString()
            $statRes = Invoke-Adb @("-s", $target.Serial, "shell", "stat", "-c", "%s", $remoteTmp)
            $remoteSize = $statRes.StdOut.Trim()
            if ($remoteSize -ne $localSize) {
                throw "Failed to push APK to device: $($pushRes.Output)"
            } else {
                Write-Info "Pushed $remoteSize bytes successfully."
            }
        }

        # Restrict permissions
        Invoke-Adb @("-s", $target.Serial, "shell", "chmod", "644", $remoteTmp) | Out-Null

        # Install via package manager with test-only and grant-permissions flags
        $pmRes = Invoke-Adb @("-s", $target.Serial, "shell", "pm", "install", "-r", "-t", "-d", "-g", $remoteTmp)
        if ($pmRes.Output -match "Success") {
            $installed = $true
            Write-Success "Installation successful via package manager!"
        } elseif ($pmRes.Output -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
            Write-Warn "Signature mismatch detected. Uninstalling previous build..."
            Invoke-Adb @("-s", $target.Serial, "uninstall", "com.livetracker.controller") | Out-Null
            $pmRetry = Invoke-Adb @("-s", $target.Serial, "shell", "pm", "install", "-r", "-t", "-d", "-g", $remoteTmp)
            if ($pmRetry.Output -match "Success") {
                $installed = $true
                Write-Success "Installation successful after uninstalling old version!"
            }
        }
    }
    finally {
        # Guaranteed cleanup of temporary APK on device storage
        Invoke-Adb @("-s", $target.Serial, "shell", "rm", "-f", $remoteTmp) | Out-Null
    }

    # Fallback to direct install if push+pm didn't succeed
    if (-not $installed) {
        Write-Info "Attempting standard adb install..."
        $directRes = Invoke-Adb @("-s", $target.Serial, "install", "-r", "-t", "-d", "-g", $apkPath)
        if ($directRes.Output -match "Success") {
            $installed = $true
            Write-Success "Direct installation successful!"
        }
    }

    if (-not $installed) {
        Write-ErrorMsg "Installation failed: $($pmRes.Output)"
        exit 1
    }

    # 5. Launch MainActivity & Verify Process Liveness
    Write-Step "4/4" "Activating com.livetracker.controller/.MainActivity..."
    $launchRes = Invoke-Adb @("-s", $target.Serial, "shell", "am", "start", "-n", "com.livetracker.controller/.MainActivity")
    if ($launchRes.Output -match "Error:" -or $launchRes.Output -match "Exception:") {
        Write-ErrorMsg "Failed to start activity: $($launchRes.Output)"
        exit 1
    }

    Start-Sleep -Milliseconds 800
    $pidCheck = Invoke-Adb @("-s", $target.Serial, "shell", "pidof", "com.livetracker.controller")
    $appPid = $pidCheck.StdOut.Trim()

    if ($appPid) {
        Write-Success "App is running! Process ID (PID): $C_BOLD$appPid$C_RESET on $($target.MarketName)"
    } else {
        Write-Warn "Activity dispatched. Verify screen or inspect logcat."
    }

    if ($Run) {
        Invoke-TraceLogcat -TargetSerial $target.Serial -App "Controller"
    }
}

# ==============================================================================
# SUBCOMMAND: LOGCAT / LIVE CATALOG (NEVER-ENDING INTELLIGENT STREAM ENGINE)
# ==============================================================================

function Get-TraceLogCategory([string]$Tag, [string]$Msg) {
    if ($Tag -match '(?i)(location|fused|geofence|osmdroid|mapcanvas|mapmarker|gps)' -or $Msg -match '(?i)(lat=|lon=|latitude|longitude|acc=|coords?|heading|speed|bearing|location event)') {
        return @{ Badge = "[LOCA]"; Color = $C_GREEN; Type = "Location" }
    }
    if ($Tag -match '(?i)(supabase|webrtc|signaling|datachannel|ice|socket|ktor|okhttp|network|connectivity)' -or $Msg -match '(?i)(public:locations|public:commands|webrtc-|ice candidate|sdp|subscribed|presence)') {
        return @{ Badge = "[NETW]"; Color = $C_CYAN; Type = "Network" }
    }
    if ($Tag -match '(?i)(camera|video|audio|codec|encoder|surfaceview)' -or $Msg -match '(?i)(camera|lens|viewfinder|fps|preview|bitrate|stream)') {
        return @{ Badge = "[CAMR]"; Color = $C_YELLOW; Type = "Camera" }
    }
    if ($Tag -match '(?i)(alert|alarm|notification)' -or $Msg -match '(?i)(panic|sos|critical|battery low|breach)') {
        return @{ Badge = "[ALRT]"; Color = $C_MAGENTA; Type = "Alert" }
    }
    if ($Tag -match '(?i)(traceapp|mainactivity|viewmodel|lifecycle|startup|service)' -or $Msg -match '(?i)(onresume|onstart|onpause|onstop|oncreate|initialized|resuming|service started|foregroundservice)') {
        return @{ Badge = "[LIFE]"; Color = $C_BLUE; Type = "Lifecycle" }
    }
    return @{ Badge = "[RUNT]"; Color = $C_WHITE; Type = "Runtime" }
}

function Test-TraceAnomalyAlert([string]$Tag, [string]$Msg, [string]$Lvl) {
    if ($Msg -match '(?i)\bANR in ([\w\.]+)\b' -or $Msg -match '(?i)Input dispatching timed out') {
        return @{
            IsAnomaly   = $true
            Title       = "CRITICAL: APPLICATION NOT RESPONDING (ANR)"
            Diagnostic  = "Main UI thread blocked for >= 5000ms. Potential thread deadlock or I/O on main thread."
            Action      = "Trace thread dump via 'adb bugreport'; move blocking calls to Dispatchers.IO."
            Details     = $Msg
        }
    }
    if ($Msg -match '(?i)FATAL EXCEPTION' -or ($Lvl -in @("E", "F") -and $Tag -match '(?i)(AndroidRuntime|FATAL)')) {
        return @{
            IsAnomaly   = $true
            Title       = "CRITICAL: UNCAUGHT APPLICATION EXCEPTION"
            Diagnostic  = "Process terminated abnormally by OS AndroidRuntime supervisor."
            Action      = "Inspect stack trace below. Check for unhandled exceptions or null state."
            Details     = $Msg
        }
    }
    if ($Msg -match '(?i)(ICE connection timed out|IceConnectionState.*FAILED|PeerConnection.*FAILED)') {
        return @{
            IsAnomaly   = $true
            Title       = "WEBRTC FAULT: ICE CONNECTION FAILED / TIMED OUT"
            Diagnostic  = "P2P STUN/TURN candidate gathering or NAT traversal handshake failed."
            Action      = "Verify TURN server credentials in WebRtcConfiguration and firewall UDP rules."
            Details     = $Msg
        }
    }
    if ($Msg -match '(?i)\bacc=([5-9]\d{1,}|\d{3,})(\.\d+)?\b' -or $Msg -match '(?i)accuracy degraded|Rejected invalid telemetry') {
        return @{
            IsAnomaly   = $true
            Title       = "TELEMETRY WARNING: GPS ACCURACY DEGRADED"
            Diagnostic  = "Reported GPS fix accuracy exceeds operational threshold (<= 50.0m)."
            Action      = "Coordinates flagged as UNRELIABLE. Awaiting high-precision satellite lock."
            Details     = $Msg
        }
    }
    if ($Msg -match '(?i)\b(unauthorized|permission denied|SecurityException)\b') {
        return @{
            IsAnomaly   = $true
            Title       = "SECURITY ALERT: PERMISSION / AUTHENTICATION DENIED"
            Diagnostic  = "Action rejected by AuthorizationEngine or Android permission manager."
            Action      = "Verify active session credentials or runtime app permissions."
            Details     = $Msg
        }
    }
    return @{ IsAnomaly = $false }
}

function Format-TraceDiagnosticCallout([string]$Time, [string]$Tag, [hashtable]$Alert) {
    $width = 74
    $title = $Alert.Title
    $topLine    = "$C_RED+-- " + "$C_BOLD$title$C_RESET$C_RED " + ("-" * [Math]::Max(2, ($width - $title.Length - 5))) + "+$C_RESET"
    $bottomLine = "$C_RED+" + ("-" * ($width - 2)) + "+$C_RESET"

    Write-Host ""
    Write-Host "  $topLine"
    Write-Host "  $C_RED|$C_RESET $C_WHITE$C_BOLD Time       :$C_RESET $C_DIM$Time$C_RESET"
    Write-Host "  $C_RED|$C_RESET $C_WHITE$C_BOLD Component  :$C_RESET $C_CYAN$Tag$C_RESET"
    if ($Alert.Details) {
        $det = if ($Alert.Details.Length -gt 60) { $Alert.Details.Substring(0, 57) + "..." } else { $Alert.Details }
        Write-Host "  $C_RED|$C_RESET $C_WHITE$C_BOLD Trigger    :$C_RESET $C_YELLOW$det$C_RESET"
    }
    Write-Host "  $C_RED|$C_RESET $C_WHITE$C_BOLD Diagnostic :$C_RESET $C_WHITE$($Alert.Diagnostic)$C_RESET"
    Write-Host "  $C_RED|$C_RESET $C_WHITE$C_BOLD Action     :$C_RESET $C_GREEN$($Alert.Action)$C_RESET"
    Write-Host "  $bottomLine"
    Write-Host ""
}

function Format-TraceSemanticMessage([string]$Msg) {
    $m = $Msg
    # Highlight lat / lon
    $m = [regex]::Replace($m, '(?i)\blat=(-?\d+\.\d+)', "${C_DIM}lat=${C_RESET}${C_BOLD}${C_CYAN}`$1${C_RESET}")
    $m = [regex]::Replace($m, '(?i)\blon=(-?\d+\.\d+)', "${C_DIM}lon=${C_RESET}${C_BOLD}${C_CYAN}`$1${C_RESET}")
    $m = [regex]::Replace($m, '\((-?\d+\.\d+),\s*(-?\d+\.\d+)\)', "(${C_BOLD}${C_CYAN}`$1${C_RESET}, ${C_BOLD}${C_CYAN}`$2${C_RESET})")

    # Adaptive accuracy coloring (Green <=15m, Yellow 15-50m, Red >50m)
    $m = [regex]::Replace($m, '(?i)\bacc=(-?\d+(\.\d+)?)m?\b', {
        param($match)
        $num = 0.0
        if ([float]::TryParse($match.Groups[1].Value, [ref]$num)) {
            if ($num -lt 0) {
                return "${C_DIM}acc=${C_RESET}${C_BOLD}${C_RED}${num}m${C_RESET}"
            } elseif ($num -le 15.0) {
                return "${C_DIM}acc=${C_RESET}${C_BOLD}${C_GREEN}${num}m${C_RESET}"
            } elseif ($num -le 50.0) {
                return "${C_DIM}acc=${C_RESET}${C_BOLD}${C_YELLOW}${num}m${C_RESET}"
            } else {
                return "${C_DIM}acc=${C_RESET}${C_BOLD}${C_RED}${num}m${C_RESET}"
            }
        }
        return $match.Value
    })

    # Speed & bearing
    $m = [regex]::Replace($m, '(?i)\bspeed=(-?\d+(\.\d+)?)(m/s|km/h)?', "${C_DIM}speed=${C_RESET}${C_BOLD}${C_WHITE}`$1`$3${C_RESET}")
    $m = [regex]::Replace($m, '(?i)\bbearing=(-?\d+(\.\d+)?)', "${C_DIM}bearing=${C_RESET}${C_BOLD}${C_WHITE}`$1°${C_RESET}")

    # Channels & Device IDs
    $m = [regex]::Replace($m, '(?i)\b(public:locations|public:commands|webrtc-[a-zA-Z0-9_-]+|presence-[a-zA-Z0-9_-]+)\b', "${C_BOLD}${C_MAGENTA}`$1${C_RESET}")
    $m = [regex]::Replace($m, "('([^']+)')", "'${C_BOLD}${C_YELLOW}`$2${C_RESET}'")

    # Connection States
    $m = [regex]::Replace($m, '\b(CONNECTED|SUBSCRIBED|ONLINE)\b', "${C_BOLD}${C_GREEN}`$1${C_RESET}")
    $m = [regex]::Replace($m, '\b(CONNECTING|RECONNECTING|CHECKING)\b', "${C_BOLD}${C_YELLOW}`$1${C_RESET}")
    $m = [regex]::Replace($m, '\b(DISCONNECTED|FAILED|CLOSED)\b', "${C_BOLD}${C_RED}`$1${C_RESET}")

    # Camera FPS
    $m = [regex]::Replace($m, '(?i)\b(\d+(\.\d+)?)\s*fps\b', {
        param($match)
        $fps = 0.0
        if ([float]::TryParse($match.Groups[1].Value, [ref]$fps)) {
            if ($fps -ge 24.0) {
                return "${C_BOLD}${C_GREEN}$($match.Value)${C_RESET}"
            } elseif ($fps -ge 15.0) {
                return "${C_BOLD}${C_YELLOW}$($match.Value)${C_RESET}"
            } else {
                return "${C_BOLD}${C_RED}$($match.Value)${C_RESET}"
            }
        }
        return $match.Value
    })

    # Highlight error words
    if ($m -match '(?i)\b(exception|fatal|error|fail(ed)?|crash|timed? out|unauthorized)\b') {
        $m = "$C_RED$m$C_RESET"
    }

    return $m
}

function Show-LiveCatalogHeader(
    [string]$TargetName,
    [string]$ActiveTarget = "Controller",
    [string]$AppInfo = "",
    [string]$Filter = "None",
    [string]$MinLevel = "V",
    [bool]$IsDump = $false
) {
    $modeText = if ($IsDump) { "Dump Buffer" } else { "Continuous Never-Ending Stream" }
    
    $targetBadge = switch ($ActiveTarget) {
        "Controller" { "$C_CYAN[CTRL] Controller App$C_RESET" }
        "Tracker"    { "$C_GREEN[TRCK] Tracker App$C_RESET" }
        "Both"       { "$C_YELLOW[DUAL] Controller & Tracker (Both)$C_RESET" }
        default      { "$C_WHITE[ALL ] System-wide Android Logs$C_RESET" }
    }

    Write-Host ""
    Write-Host "$C_CYAN+=============================================================================+$C_RESET"
    Write-Host "$C_CYAN|$C_RESET  $C_BOLD$C_WHITE TRACE LIVE APPLICATION CATALOG$C_RESET $C_DIM-- Telemetry & Event Stream$C_RESET         $C_CYAN|$C_RESET"
    Write-Host "$C_CYAN|$C_RESET  Device : $C_GREEN$($TargetName.PadRight(28))$C_RESET Mode: $C_CYAN$($modeText.PadRight(32))$C_RESET$C_CYAN|$C_RESET"
    Write-Host "$C_CYAN|$C_RESET  Scope  : $($targetBadge.PadRight(37))$C_RESET Min : [$MinLevel]  Filter: '$Filter'$C_CYAN|$C_RESET"
    if ($AppInfo) {
        Write-Host "$C_CYAN|$C_RESET  Status : $C_YELLOW$($AppInfo.PadRight(68))$C_RESET$C_CYAN|$C_RESET"
    }
    Write-Host "$C_CYAN+-----------------------------------------------------------------------------+$C_RESET"
    if (-not $IsDump) {
        Write-Host "  $C_DIM Hotkeys: [C] Controller | [T] Tracker | [B] Dual Both | [Tab] Cycle Scope$C_RESET"
        Write-Host "  $C_DIM          [P] Pause/Resume | [K] Clear | [L] Launch | [1-5] Min Lvl | [Q] Exit$C_RESET`n"
    }
}

function Show-LiveSessionSummary(
    [timespan]$Duration,
    [int]$Total,
    [int]$Ctrl,
    [int]$Trck,
    [int]$Net,
    [int]$Loc,
    [int]$Warn,
    [int]$Err,
    [int]$Restarts,
    [string]$TargetName,
    [string]$ActiveTarget
) {
    $durStr = "{0:D2}:{1:D2}:{2:D2}" -f $Duration.Hours, $Duration.Minutes, $Duration.Seconds
    $rate = if ($Duration.TotalSeconds -gt 0) { [math]::Round($Total / $Duration.TotalSeconds, 1) } else { $Total }

    Write-Host ""
    Write-Host "$C_CYAN+=============================================================================+$C_RESET"
    Write-Host "$C_CYAN|$C_RESET  $C_BOLD$C_WHITE LIVE STREAM SESSION CONCLUDED$C_RESET $C_DIM[Status: Completed]$C_RESET                         $C_CYAN|$C_RESET"
    Write-Host "$C_CYAN+-----------------------------------------------------------------------------+$C_RESET"
    Write-Host "  $C_DIM Target Device    :$C_RESET $C_WHITE$TargetName$C_RESET"
    Write-Host "  $C_DIM Stream Scope     :$C_RESET $C_CYAN$ActiveTarget$C_RESET"
    Write-Host "  $C_DIM Session Duration :$C_RESET $C_WHITE$durStr$C_RESET ($rate events/sec)"
    Write-Host "  $C_DIM Total Log Events :$C_RESET $C_BOLD$C_WHITE$Total$C_RESET"
    Write-Host "  $C_DIM App Distribution :$C_RESET $C_CYAN$Ctrl Controller$C_RESET  |  $C_GREEN$Trck Tracker$C_RESET"
    Write-Host "  $C_DIM Telemetry Events :$C_RESET $C_CYAN$Net Network$C_RESET  |  $C_GREEN$Loc Location$C_RESET"
    Write-Host "  $C_DIM System Health    :$C_RESET $C_YELLOW$Warn Warnings$C_RESET  |  $C_RED$Err Errors$C_RESET  |  $C_WHITE$Restarts Process Restarts$C_RESET"
    Write-Host "$C_CYAN+=============================================================================+$C_RESET`n"
}

function Invoke-TraceLogcat {
    [CmdletBinding()]
    param(
        [Parameter(Position = 0)]
        [Alias("f")]
        [string]$Filter = "",

        [Alias("a", "Target", "Package")]
        [string]$App = "",

        [Alias("d")]
        [switch]$Dump,

        [Alias("c")]
        [switch]$Clear,

        [Alias("lvl")]
        [string]$Level = "V",

        [Alias("n")]
        [int]$Lines = 20,

        [Alias("cat")]
        [string]$Category = "",

        [switch]$All,
        [switch]$Dual,
        [string]$TargetSerial = ""
    )

    $devs = @(Get-TraceDevices)
    if ($devs.Count -eq 0) {
        Write-Warn "No active ADB devices found."
        $ans = Read-Host "  Launch Android Emulator (Pixel 10 Pro)? [Y/n]"
        if (-not $ans -or $ans.Trim().ToUpper() -eq "Y") {
            Invoke-TraceEmulator -NoWait
            Write-Info "Awaiting emulator boot..."
            for ($i = 0; $i -lt 30; $i++) {
                Start-Sleep -Seconds 2
                $devs = @(Get-TraceDevices)
                if ($devs | Where-Object { $_.IsEmulator -and $_.Status -eq "device" }) { break }
            }
        }
    }

    $target = Resolve-TraceTargetDevice -ExplicitSerial $TargetSerial
    if (-not $target) { return }

    if ($Clear) {
        Write-Info "Clearing device logcat buffer on $($target.MarketName)..."
        & $Global:AdbPath -s $target.Serial logcat -c
        Write-Success "Logcat buffer cleared."
        if ($Dump) { return }
    }

    $pkgController = "com.livetracker.controller"
    $pkgTracker = "com.example.livelocationservice"

    # Discover Package UIDs (Invariant across app restarts & crashes)
    $ctrlUid = $null
    $uidResCtrl = Invoke-Adb @("-s", $target.Serial, "shell", "pm", "list", "packages", "-U", $pkgController)
    if ($uidResCtrl.Output -match 'package:[^\s]+\s+uid:(\d+)') {
        $ctrlUid = $Matches[1].Trim()
    }

    $trckUid = $null
    $uidResTrck = Invoke-Adb @("-s", $target.Serial, "shell", "pm", "list", "packages", "-U", $pkgTracker)
    if ($uidResTrck.Output -match 'package:[^\s]+\s+uid:(\d+)') {
        $trckUid = $Matches[1].Trim()
    } else {
        # Fallback discovery for any tracker package on device
        $altPackages = Invoke-Adb @("-s", $target.Serial, "shell", "pm", "list", "packages", "-U")
        if ($altPackages.Output -match 'package:(com\.[^\s]*livelocation[^\s]*)\s+uid:(\d+)') {
            $pkgTracker = $Matches[1].Trim()
            $trckUid = $Matches[2].Trim()
        } elseif ($altPackages.Output -match 'package:(com\.[^\s]*tracker[^\s]*)\s+uid:(\d+)') {
            $pkgTracker = $Matches[1].Trim()
            $trckUid = $Matches[2].Trim()
        }
    }

    # Discover active process PIDs
    $ctrlPid = (Invoke-Adb @("-s", $target.Serial, "shell", "pidof", $pkgController)).StdOut.Trim()
    $trckPid = (Invoke-Adb @("-s", $target.Serial, "shell", "pidof", $pkgTracker)).StdOut.Trim()

    # Handle Positional or Named App selection
    if (-not $App -and $Filter) {
        switch ($Filter.ToLower()) {
            { $_ -in "controller", "ctrl", "c", "1" } { $App = "Controller"; $Filter = "" }
            { $_ -in "tracker", "trck", "track", "t", "2" } { $App = "Tracker"; $Filter = "" }
            { $_ -in "both", "dual", "interleaved", "d", "b", "3" } { $App = "Both"; $Filter = "" }
            { $_ -in "all", "sys", "system", "a", "4" } { $App = "All"; $All = $true; $Filter = "" }
        }
    }
    if ($Dual) { $App = "Both" }
    if ($All) { $App = "All" }

    # Interactive Target App Selector Menu if not explicitly specified
    if (-not $App -and -not $Dump -and -not [Console]::IsInputRedirected) {
        $ctrlStatusText = if ($ctrlPid) { "$C_GREEN(Running | PID: $ctrlPid)$C_RESET" } else { "$C_YELLOW(Not Running)$C_RESET" }
        $trckStatusText = if ($trckPid) { "$C_GREEN(Running | PID: $trckPid)$C_RESET" } else { "$C_YELLOW(Not Running)$C_RESET" }

        Write-Host ""
        Write-Host "$C_CYAN+=============================================================================+$C_RESET"
        Write-Host "$C_CYAN|$C_RESET  $C_BOLD$C_WHITE TRACE LIVE LOGCAT -- Target Application Selector$C_RESET                     $C_CYAN|$C_RESET"
        Write-Host "$C_CYAN+=============================================================================+$C_RESET"
        Write-Host "  $C_DIM Target Device : $C_GREEN$($target.MarketName) ($($target.Serial))$C_RESET"
        Write-Host ""
        Write-Host "  $C_CYAN$C_BOLD AVAILABLE LOGCAT TARGETS:$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[1]$C_RESET Controller App      $C_CYAN$pkgController$C_RESET $ctrlStatusText"
        Write-Host "    $C_BOLD$C_WHITE[2]$C_RESET Tracker App         $C_GREEN$pkgTracker$C_RESET $trckStatusText"
        Write-Host "    $C_BOLD$C_WHITE[3]$C_RESET Dual Stream (Both)  $C_YELLOW Interleaved Telemetry & Handshakes$C_RESET  $C_BOLD[RECOMMENDED]$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[4]$C_RESET All System Logs     $C_WHITE System-wide unconstrained Android logcat$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[Q]$C_RESET Return to Menu"
        Write-Host ""
        Write-Host "$C_CYAN+-----------------------------------------------------------------------------+$C_RESET"
        Write-Host "  $C_DIM Tip: You can switch between apps at ANY time while streaming using [C], [T], [B]!$C_RESET"

        $rawChoice = Read-Host "  Select Target [1-4, Q] (Default: 1 - Controller)"
        if (-not $rawChoice) { $rawChoice = "1" }
        $choice = $rawChoice.Trim().ToLower()

        switch ($choice) {
            { $_ -in "1", "c", "controller", "ctrl" } { $App = "Controller" }
            { $_ -in "2", "t", "tracker", "trck" }    { $App = "Tracker" }
            { $_ -in "3", "b", "both", "dual", "d" }  { $App = "Both" }
            { $_ -in "4", "a", "all", "sys" }         { $App = "All"; $All = $true }
            { $_ -in "q", "exit", "quit" }            { return }
            default { $App = "Controller" }
        }
    }

    if (-not $App) { $App = "Controller" }

    # Offer to launch targeted application if not currently running
    if ($App -eq "Controller" -and -not $ctrlPid -and -not $All) {
        Write-Warn "Controller App '$pkgController' is not running on $($target.MarketName)."
        $ans = Read-Host "  Launch Controller application now? [Y/n]"
        if (-not $ans -or $ans.Trim().ToUpper() -eq "Y") {
            & $Global:AdbPath -s $target.Serial shell monkey -p $pkgController -c android.intent.category.LAUNCHER 1 | Out-Null
            Start-Sleep -Seconds 1
            $ctrlPid = (Invoke-Adb @("-s", $target.Serial, "shell", "pidof", $pkgController)).StdOut.Trim()
        }
    } elseif ($App -eq "Tracker" -and -not $trckPid -and -not $All) {
        Write-Warn "Tracker App '$pkgTracker' is not running on $($target.MarketName)."
        $ans = Read-Host "  Launch Tracker application now? [Y/n]"
        if (-not $ans -or $ans.Trim().ToUpper() -eq "Y") {
            & $Global:AdbPath -s $target.Serial shell monkey -p $pkgTracker -c android.intent.category.LAUNCHER 1 | Out-Null
            Start-Sleep -Seconds 1
            $trckPid = (Invoke-Adb @("-s", $target.Serial, "shell", "pidof", $pkgTracker)).StdOut.Trim()
        }
    }

    # Hardware / OEM / Render / Audio / Framework noise suppression regex
    $hardSilenceRegex = '(?i)\b(gralloc[34]?|hwcomposer|displayfeaturehal|openglrenderer|bufferqueue(producer|consumer)?|blastbufferqueue|renderinspector|choreographer|looper|perfmonitor|insets?controller|inputtransport|surfacesyncgroup|surfacecontrol|decorview|colorx|timeline|chatty|surfaceview|host-connection|ion|mali|ged|gles|graphicbuffer|egl|surfaceflinger|miuistubregistry|dynamicfps|libperfctl|libmigl|libegl|lb|qt|compatibilitychangereporter)\b'

    # Telemetry Counters
    $totalLines = 0
    $ctrlCount  = 0
    $trckCount  = 0
    $netCount   = 0
    $locCount   = 0
    $errorCount = 0
    $warnCount  = 0
    $restarts   = 0
    $startTime  = [System.DateTime]::UtcNow
    $isPaused   = $false
    $activeTarget = $App
    $streamMode = if ($All -or $App -eq "All") { "All" } else { "DualPipe" }
    $dumpItems = if ($Dump) { New-Object System.Collections.Generic.List[string] } else { $null }

    $exitRequested = $false

    while (-not $exitRequested) {
        $requestStreamRestart = $false

        # Build ADB arguments:
        # When in DualPipe mode, pipe both UIDs simultaneously so live switching is instantaneous!
        $adbArgs = @("-s", $target.Serial, "logcat", "-v", "threadtime", "-v", "uid")
        if ($streamMode -ne "All") {
            $targetUids = @()
            if ($ctrlUid) { $targetUids += $ctrlUid }
            if ($trckUid) { $targetUids += $trckUid }
            if ($targetUids.Count -gt 0) {
                $adbArgs += "--uid=$($targetUids -join ',')"
            } elseif ($ctrlPid) {
                $adbArgs += "--pid=$ctrlPid"
            }
        }

        if ($Dump) {
            $adbArgs += "-d"
            $dumpItems.Clear()
        } else {
            $adbArgs += @("-T", "200")
        }

        # Noise suppression daemon tags
        if ($streamMode -ne "All") {
            $adbArgs += @(
                "gralloc4:S", "gralloc:S", "Gralloc3:S", "DisplayFeatureHal:S", "hwcomposer:S",
                "OpenGLRenderer:S", "Choreographer:S", "BufferQueueProducer:S", "BufferQueueConsumer:S", "BufferQueue:S",
                "BLASTBufferQueue:S", "RenderInspector:S", "Looper:S", "PerfMonitor:S",
                "InsetsController:S", "InputTransport:S", "SurfaceSyncGroup:S",
                "SurfaceControl:S", "DecorView:S", "ColorX:S", "Timeline:S", "chatty:S",
                "SurfaceView:S", "host-connection:S", "ion:S", "mali:S", "GED:S",
                "GLES:S", "GraphicBuffer:S", "EGL:S", "SurfaceFlinger:S",
                "MiuiStubRegistry:S", "DynamicFPS:S", "libPerfCtl:S", "libMiGL:S", "libEGL:S",
                "LB:S", "QT:S", "CompatibilityChangeReporter:S", "*:V"
            )
        }

        $appInfoStr = switch ($activeTarget) {
            "Controller" { "Controller PID: $(if ($ctrlPid) { $ctrlPid } else { 'None' }) (UID: $ctrlUid)" }
            "Tracker"    { "Tracker PID: $(if ($trckPid) { $trckPid } else { 'None' }) (UID: $trckUid)" }
            "Both"       { "Controller PID: $(if ($ctrlPid) { $ctrlPid } else { '-' }) | Tracker PID: $(if ($trckPid) { $trckPid } else { '-' })" }
            default      { "System-wide unfiltered logging" }
        }

        $filterDisplay = if ($Filter) { $Filter } else { "None" }
        $minLvlDisplay = if ($Level) { $Level.ToUpper() } else { "V" }
        $levelOrder = @{ "V"=0; "D"=1; "I"=2; "W"=3; "E"=4; "F"=5 }
        $minLevelVal = if ($levelOrder.ContainsKey($minLvlDisplay)) { $levelOrder[$minLvlDisplay] } else { 0 }

        Show-LiveCatalogHeader -TargetName "$($target.MarketName)" -ActiveTarget $activeTarget -AppInfo $appInfoStr -Filter $filterDisplay -MinLevel $minLvlDisplay -IsDump $Dump

        $regThreadTimeUid = '^\d{2}-\d{2}\s+(\d{2}:\d{2}:\d{2})\.\d{3}\s+(\S+)\s+(\d+)\s+\d+\s+([VDIWEF])\s+([^:\s]+)\s*:\s*(.*)$'
        $regThreadTime    = '^\d{2}-\d{2}\s+(\d{2}:\d{2}:\d{2})\.\d{3}\s+(\d+)\s+\d+\s+([VDIWEF])\s+([^:\s]+)\s*:\s*(.*)$'
        $regTime          = '^\d{2}-\d{2}\s+(\d{2}:\d{2}:\d{2})\.\d{3}(?:\s+(\d+)\s+\d+)?\s+([VDIWEF])/([^(\s:]+)(?:\(\s*\d+\))?:\s*(.*)$'
        $regBrief         = '^([VDIWEF])/([^(\s:]+)(?:\(\s*(\d+)\))?:\s*(.*)$'

        try {
            & $Global:AdbPath $adbArgs | ForEach-Object {
                $rawLine = $_
                if (-not $rawLine) { return }

                # Safe interactive hotkey poll (non-blocking)
                try {
                    if (-not [Console]::IsInputRedirected -and [Console]::KeyAvailable) {
                        $key = [Console]::ReadKey($true)
                        switch ($key.Key) {
                            ([ConsoleKey]::C) {
                                $activeTarget = "Controller"
                                Write-Host "`n  $C_CYAN$C_BOLD>>> SWITCHED LIVE VIEW: CONTROLLER APP ($pkgController | PID: $ctrlPid) <<<$C_RESET`n"
                            }
                            ([ConsoleKey]::T) {
                                $activeTarget = "Tracker"
                                Write-Host "`n  $C_GREEN$C_BOLD>>> SWITCHED LIVE VIEW: TRACKER APP ($pkgTracker | PID: $trckPid) <<<$C_RESET`n"
                            }
                            ([ConsoleKey]::B) {
                                $activeTarget = "Both"
                                Write-Host "`n  $C_YELLOW$C_BOLD>>> SWITCHED LIVE VIEW: DUAL STREAM (Both Apps Interleaved) <<<$C_RESET`n"
                            }
                            ([ConsoleKey]::Tab) {
                                if ($activeTarget -eq "Controller") {
                                    $activeTarget = "Tracker"
                                    Write-Host "`n  $C_GREEN$C_BOLD>>> SWITCHED LIVE VIEW: TRACKER APP ($pkgTracker | PID: $trckPid) <<<$C_RESET`n"
                                } elseif ($activeTarget -eq "Tracker") {
                                    $activeTarget = "Both"
                                    Write-Host "`n  $C_YELLOW$C_BOLD>>> SWITCHED LIVE VIEW: DUAL STREAM (Both Apps Interleaved) <<<$C_RESET`n"
                                } else {
                                    $activeTarget = "Controller"
                                    Write-Host "`n  $C_CYAN$C_BOLD>>> SWITCHED LIVE VIEW: CONTROLLER APP ($pkgController | PID: $ctrlPid) <<<$C_RESET`n"
                                }
                            }
                            ([ConsoleKey]::A) {
                                if ($streamMode -ne "All") {
                                    $activeTarget = "All"
                                    $streamMode = "All"
                                    $requestStreamRestart = $true
                                    break
                                } else {
                                    Write-Host "`n  $C_WHITE$C_BOLD>>> STREAM IS ALREADY SET TO ALL SYSTEM LOGS <<<$C_RESET`n"
                                }
                            }
                            ([ConsoleKey]::P) {
                                $isPaused = -not $isPaused
                                if ($isPaused) {
                                    Write-Host "`n  $C_YELLOW[STREAM PAUSED -- Press 'P' to resume]$C_RESET`n"
                                } else {
                                    Write-Host "`n  $C_GREEN[STREAM RESUMED]$C_RESET`n"
                                }
                            }
                            ([ConsoleKey]::K) {
                                Clear-Host
                                Show-LiveCatalogHeader -TargetName "$($target.MarketName)" -ActiveTarget $activeTarget -AppInfo $appInfoStr -Filter $filterDisplay -MinLevel $minLvlDisplay -IsDump $Dump
                            }
                            ([ConsoleKey]::L) {
                                if ($activeTarget -eq "Tracker" -or ($activeTarget -eq "Both" -and -not $trckPid)) {
                                    Write-Host "  $C_CYAN Launching Tracker App on device...$C_RESET"
                                    & $Global:AdbPath -s $target.Serial shell monkey -p $pkgTracker -c android.intent.category.LAUNCHER 1 | Out-Null
                                }
                                if ($activeTarget -eq "Controller" -or ($activeTarget -eq "Both" -and -not $ctrlPid)) {
                                    Write-Host "  $C_CYAN Launching Controller App on device...$C_RESET"
                                    & $Global:AdbPath -s $target.Serial shell monkey -p $pkgController -c android.intent.category.LAUNCHER 1 | Out-Null
                                }
                            }
                            ([ConsoleKey]::D1) { $minLevelVal = 0; $minLvlDisplay = "V"; Write-Host "  $C_DIM[Min Level: Verbose (V)]$C_RESET" }
                            ([ConsoleKey]::D2) { $minLevelVal = 1; $minLvlDisplay = "D"; Write-Host "  $C_DIM[Min Level: Debug (D)]$C_RESET" }
                            ([ConsoleKey]::D3) { $minLevelVal = 2; $minLvlDisplay = "I"; Write-Host "  $C_DIM[Min Level: Info (I)]$C_RESET" }
                            ([ConsoleKey]::D4) { $minLevelVal = 3; $minLvlDisplay = "W"; Write-Host "  $C_DIM[Min Level: Warn (W)]$C_RESET" }
                            ([ConsoleKey]::D5) { $minLevelVal = 4; $minLvlDisplay = "E"; Write-Host "  $C_DIM[Min Level: Error (E)]$C_RESET" }
                            ([ConsoleKey]::Q) {
                                $exitRequested = $true
                                break
                            }
                        }
                    }
                } catch {}

                if ($isPaused) { return }

                # Silence OEM spam
                if ($streamMode -ne "All" -and ($rawLine -match $hardSilenceRegex -or $rawLine -match 'Access denied finding property "(ro\.)?vendor\.' -or $rawLine -match 'DynamicFPS\s+DF\s+open\s+fail' -or $rawLine.StartsWith("--------- beginning"))) {
                    return
                }

                $matched = $false
                $time    = ""
                $lineUid = ""
                $linePid = ""
                $lvl     = ""
                $tag     = ""
                $msg     = ""

                if ($rawLine -match $regThreadTimeUid) {
                    $time = $Matches[1]; $lineUid = $Matches[2]; $linePid = $Matches[3]; $lvl = $Matches[4]; $tag = $Matches[5]; $msg = $Matches[6]; $matched = $true
                } elseif ($rawLine -match $regThreadTime) {
                    $time = $Matches[1]; $linePid = $Matches[2]; $lvl = $Matches[3]; $tag = $Matches[4]; $msg = $Matches[5]; $matched = $true
                } elseif ($rawLine -match $regTime) {
                    $time = $Matches[1]; $linePid = $Matches[2]; $lvl = $Matches[3]; $tag = $Matches[4]; $msg = $Matches[5]; $matched = $true
                } elseif ($rawLine -match $regBrief) {
                    $time = (Get-Date -Format "HH:mm:ss"); $lvl = $Matches[1]; $tag = $Matches[2]; $msg = $Matches[4]; $matched = $true
                }

                if ($matched) {
                    # Classify package origin
                    $isCtrl = ($ctrlUid -and $lineUid -eq $ctrlUid) -or ($ctrlPid -and $linePid -eq $ctrlPid) -or ($tag -in "SupabaseFleetManager", "TraceApplication", "MainShellScreen", "MapViewModel", "DevicesViewModel", "AlertsViewModel")
                    $isTrck = ($trckUid -and $lineUid -eq $trckUid) -or ($trckPid -and $linePid -eq $trckPid) -or ($tag -in "LiveLocationService", "TrackerSignaling", "TrackerWebRtcManager", "CommandPipeline")

                    # Self-Healing PID Detection
                    if (-not $Dump) {
                        if ($isCtrl -and $linePid -and ($linePid -ne $ctrlPid)) {
                            $restarts++
                            Write-Host "`n  $C_YELLOW$C_BOLD[LIFECYCLE]$C_RESET $C_WHITE Controller Process Re-attached: PID $ctrlPid -> $linePid$C_RESET`n"
                            $ctrlPid = $linePid
                        }
                        if ($isTrck -and $linePid -and ($linePid -ne $trckPid)) {
                            $restarts++
                            Write-Host "`n  $C_YELLOW$C_BOLD[LIFECYCLE]$C_RESET $C_WHITE Tracker Process Re-attached: PID $trckPid -> $linePid$C_RESET`n"
                            $trckPid = $linePid
                        }
                    } else {
                        if ($isCtrl -and $linePid -and ($linePid -ne $ctrlPid)) { $ctrlPid = $linePid; $restarts++ }
                        if ($isTrck -and $linePid -and ($linePid -ne $trckPid)) { $trckPid = $linePid; $restarts++ }
                    }

                    # Filter based on active target selection
                    if ($activeTarget -eq "Controller" -and -not $isCtrl) { return }
                    if ($activeTarget -eq "Tracker" -and -not $isTrck) { return }
                    if ($activeTarget -eq "Both" -and -not $isCtrl -and -not $isTrck) { return }

                    # Text/Regex filter
                    if ($Filter -and ($tag -notmatch $Filter -and $msg -notmatch $Filter)) { return }

                    # Minimum log level
                    $currentLevelVal = if ($levelOrder.ContainsKey($lvl)) { $levelOrder[$lvl] } else { 0 }
                    if ($currentLevelVal -lt $minLevelVal) { return }

                    # Semantic category
                    $cat = Get-TraceLogCategory $tag $msg
                    if ($Category -and ($cat.Type -notlike "*$Category*" -and $cat.Badge -notlike "*$Category*")) { return }

                    $totalLines++
                    if ($isCtrl) { $ctrlCount++ }
                    if ($isTrck) { $trckCount++ }
                    if ($lvl -in "E", "F") { $errorCount++ }
                    if ($lvl -eq "W") { $warnCount++ }
                    if ($cat.Type -eq "Network") { $netCount++ }
                    elseif ($cat.Type -eq "Location") { $locCount++ }

                    # Anomaly alert callout (only active in live mode to prevent dump spam)
                    $alert = Test-TraceAnomalyAlert $tag $msg $lvl
                    if ($alert.IsAnomaly -and -not $Dump) {
                        Format-TraceDiagnosticCallout $time $tag $alert
                    }

                    # Format Level Badge
                    $lvlBadge = switch ($lvl) {
                        "E" { "$C_RED[ERR ]$C_RESET" }
                        "F" { "$C_RED[FATL]$C_RESET" }
                        "W" { "$C_YELLOW[WARN]$C_RESET" }
                        "I" { "$C_GREEN[INFO]$C_RESET" }
                        "D" { "$C_CYAN[DBUG]$C_RESET" }
                        default { "$C_DIM[VERB]$C_RESET" }
                    }

                    $catBadge = "$($cat.Color)$($cat.Badge)$C_RESET"
                    $tagPadded = if ($tag.Length -gt 16) { $tag.Substring(0, 15) + "." } else { $tag.PadRight(16) }
                    $msgFormatted = Format-TraceSemanticMessage $msg

                    # App badge for Dual or All modes
                    $appBadge = if ($activeTarget -in "Both", "All") {
                        if ($isCtrl) { "$C_CYAN[CTRL]$C_RESET " }
                        elseif ($isTrck) { "$C_GREEN[TRCK]$C_RESET " }
                        else { "$C_DIM[SYST]$C_RESET " }
                    } else { "" }

                    # OSC 0 terminal title update every 5 events
                    if ($totalLines % 5 -eq 0) {
                        $titleString = "TRACE LIVE [$activeTarget | Ctrl: $ctrlCount | Trck: $trckCount | Warn: $warnCount | Err: $errorCount]"
                        Write-Host -NoNewline "$ESC]0;$titleString$ESC\"
                    }

                    $formattedEntry = "  $C_DIM$time$C_RESET $appBadge$lvlBadge $catBadge $($cat.Color)$tagPadded$C_RESET : $msgFormatted"
                    if ($Dump -and ($null -ne $dumpItems)) {
                        $dumpItems.Add($formattedEntry)
                    } else {
                        Write-Host $formattedEntry
                    }
                } elseif ($rawLine -match '^\s+(at\s+.*|Caused by:.*)$' -or $rawLine -match 'FATAL EXCEPTION') {
                    $totalLines++
                    $errorCount++
                    $formattedStack = "                                   $C_RED$rawLine$C_RESET"
                    if ($Dump -and ($null -ne $dumpItems)) {
                        $dumpItems.Add($formattedStack)
                    } else {
                        Write-Host $formattedStack
                    }
                }
            }
        } catch {
            $exitRequested = $true
        }

        if ($Dump -and ($null -ne $dumpItems)) {
            $startIdx = [Math]::Max(0, $dumpItems.Count - $Lines)
            for ($k = $startIdx; $k -lt $dumpItems.Count; $k++) {
                Write-Host $dumpItems[$k]
            }
        }

        if ($Dump -or -not $requestStreamRestart) {
            $exitRequested = $true
        }
    }

    $duration = [System.DateTime]::UtcNow - $startTime
    Write-Host -NoNewline "$ESC]0;TRACE CONTROLLER$ESC\"
    Write-Host -NoNewline "$C_RESET"

    Show-LiveSessionSummary -Duration $duration `
                            -Total $totalLines `
                            -Ctrl $ctrlCount `
                            -Trck $trckCount `
                            -Net $netCount `
                            -Loc $locCount `
                            -Warn $warnCount `
                            -Err $errorCount `
                            -Restarts $restarts `
                            -TargetName "$($target.MarketName)" `
                            -ActiveTarget $activeTarget
}

# ==============================================================================
# SUBCOMMAND: ERRORS & WARNINGS LOGGER / AUDITOR
# ==============================================================================

function Invoke-TraceErrors {
    [CmdletBinding()]
    param(
        [Parameter(Position = 0)]
        [Alias("f")]
        [string]$Filter = "",

        [Alias("s")]
        [switch]$Save,

        [string]$File = "",

        [Alias("n")]
        [int]$Lines = 200,

        [Alias("c")]
        [switch]$Clear,

        [switch]$Stream = $false,
        [switch]$All,
        [string]$TargetSerial = ""
    )

    $devs = @(Get-TraceDevices)
    if ($devs.Count -eq 0) {
        Write-Warn "No active ADB devices found."
        $ans = Read-Host "  Launch Android Emulator (Pixel 10 Pro)? [Y/n]"
        if (-not $ans -or $ans.Trim().ToUpper() -eq "Y") {
            Invoke-TraceEmulator -NoWait
            Write-Info "Awaiting emulator boot..."
            for ($i = 0; $i -lt 30; $i++) {
                Start-Sleep -Seconds 2
                $devs = @(Get-TraceDevices)
                if ($devs | Where-Object { $_.IsEmulator -and $_.Status -eq "device" }) { break }
            }
        }
    }

    $target = Resolve-TraceTargetDevice -ExplicitSerial $TargetSerial
    if (-not $target) { return }

    if ($Clear) {
        Write-Info "Clearing device logcat buffer on $($target.MarketName)..."
        & $Global:AdbPath -s $target.Serial logcat -c
        Write-Success "Logcat buffer cleared."
    }

    $pidRes = Invoke-Adb @("-s", $target.Serial, "shell", "pidof", "com.livetracker.controller")
    $appPid = $pidRes.StdOut.Trim()

    $pidDisplay = if ($All) { "PID: All (System-wide)" } elseif ($appPid) { "PID: $appPid" } else { "PID: Broad Mode" }
    $filterDisplay = if ($Filter) { "  |  Filter: '$Filter'" } else { "" }
    $modeDisplay = if ($Stream) { "Live Stream" } else { "Recent Buffer ($Lines lines)" }
    Write-Banner "Errors & Warnings Audit -- com.livetracker.controller ($pidDisplay)" "Target: $($target.MarketName) ($($target.Serial))  |  $modeDisplay$filterDisplay"

    # Noise suppression regex (Hardware / OEM / Render / Audio / Framework spam)
    $hardSilenceRegex = '(?i)\b(gralloc[34]?|hwcomposer|displayfeaturehal|openglrenderer|bufferqueue(producer|consumer)?|blastbufferqueue|renderinspector|choreographer|looper|perfmonitor|insets?controller|inputtransport|surfacesyncgroup|surfacecontrol|decorview|colorx|timeline|chatty|surfaceview|host-connection|ion|mali|ged|gles|graphicbuffer|egl|surfaceflinger|miuistubregistry|dynamicfps|libperfctl|libmigl|libegl|lb|qt|compatibilitychangereporter)\b'

    # Build ADB arguments: request only Warnings and Errors at daemon level
    $adbArgs = @("-s", $target.Serial, "logcat")
    if ($appPid -and (-not $All)) {
        $adbArgs += "--pid=$appPid"
    }
    if (-not $Stream) {
        $adbArgs += @("-d", "-t", "$Lines")
    }

    if (-not $All) {
        $adbArgs += @(
            "gralloc4:S", "gralloc:S", "Gralloc3:S", "DisplayFeatureHal:S", "hwcomposer:S",
            "OpenGLRenderer:S", "Choreographer:S", "BufferQueueProducer:S", "BufferQueueConsumer:S", "BufferQueue:S",
            "BLASTBufferQueue:S", "RenderInspector:S", "Looper:S", "PerfMonitor:S",
            "InsetsController:S", "InputTransport:S", "SurfaceSyncGroup:S",
            "SurfaceControl:S", "DecorView:S", "ColorX:S", "Timeline:S", "chatty:S",
            "SurfaceView:S", "host-connection:S", "ion:S", "mali:S", "GED:S",
            "GLES:S", "GraphicBuffer:S", "EGL:S", "SurfaceFlinger:S",
            "MiuiStubRegistry:S", "DynamicFPS:S", "libPerfCtl:S", "libMiGL:S", "libEGL:S",
            "LB:S", "QT:S", "CompatibilityChangeReporter:S", "*:W"
        )
    } else {
        $adbArgs += "*:W"
    }

    $regTime = '^\d{2}-\d{2}\s+(\d{2}:\d{2}:\d{2})\.\d{3}(?:\s+\d+\s+\d+)?\s+([VDIWEF])/([^(\s:]+)(?:\(\s*\d+\))?:\s*(.*)$'
    $regThreadTime = '^\d{2}-\d{2}\s+(\d{2}:\d{2}:\d{2})\.\d{3}\s+\d+\s+\d+\s+([VDIWEF])\s+([^:\s]+)\s*:\s*(.*)$'
    $regBrief = '^([VDIWEF])/([^(\s:]+)(?:\(\s*(\d+)\))?:\s*(.*)$'

    $warnCount = 0
    $errCount = 0
    $fatalCount = 0
    $frameCount = 0
    $tagCounts = @{}
    $savedLines = [System.Collections.Generic.List[string]]::new()

    # Determine log file path if saving
    $logFilePath = $File
    if (-not $logFilePath -and $Save) {
        $logsDir = Join-Path $ProjectRoot "logs"
        if (-not (Test-Path $logsDir)) { New-Item -ItemType Directory -Path $logsDir | Out-Null }
        $timestamp = (Get-Date).ToString("yyyyMMdd_HHmmss")
        $logFilePath = Join-Path $logsDir "errors_$timestamp.log"
    }

    if ($Stream) {
        Write-Host "  $C_DIM[i] Streaming incoming warnings & errors (Press Ctrl+C to stop)...$C_RESET`n"
    }

    try {
        & $Global:AdbPath $adbArgs | ForEach-Object {
            $rawLine = $_
            if (-not $rawLine) { return }

            # Layer 2 Silence: immediate hard drop on OEM / HAL / gralloc spam
            if (-not $All -and ($rawLine -match $hardSilenceRegex -or $rawLine -match 'Access denied finding property "(ro\.)?vendor\.' -or $rawLine -match 'DynamicFPS\s+DF\s+open\s+fail')) {
                return
            }

            $matched = $false
            $time = ""
            $lvl  = ""
            $tag  = ""
            $msg  = ""

            if ($rawLine -match $regThreadTime) {
                $time = $Matches[1]; $lvl = $Matches[2]; $tag = $Matches[3]; $msg = $Matches[4]; $matched = $true
            } elseif ($rawLine -match $regTime) {
                $time = $Matches[1]; $lvl = $Matches[2]; $tag = $Matches[3]; $msg = $Matches[4]; $matched = $true
            } elseif ($rawLine -match $regBrief) {
                $time = (Get-Date -Format "HH:mm:ss"); $lvl = $Matches[1]; $tag = $Matches[2]; $msg = $Matches[4]; $matched = $true
            }

            # Only accept Warning (W), Error (E), Fatal (F)
            if ($matched) {
                if ($lvl -notin @("W", "E", "F")) { return }

                if ($Filter -and ($tag -notmatch $Filter -and $msg -notmatch $Filter)) {
                    return
                }

                if ($lvl -eq "W") { $warnCount++ }
                if ($lvl -eq "E") { $errCount++ }
                if ($lvl -eq "F") { $fatalCount++ }

                if (-not $tagCounts.ContainsKey($tag)) { $tagCounts[$tag] = 0 }
                $tagCounts[$tag]++

                $lvlBadge = switch ($lvl) {
                    "E" { "$C_RED[ERR] $C_RESET" }
                    "F" { "$C_RED[FATL]$C_RESET" }
                    "W" { "$C_YELLOW[WARN]$C_RESET" }
                }

                $cleanBadge = switch ($lvl) {
                    "E" { "[ERR] " }
                    "F" { "[FATL]" }
                    "W" { "[WARN]" }
                }

                $tagColor = switch -Wildcard ($tag) {
                    "*Supabase*"        { $C_CYAN }
                    "*TraceApplication*"{ $C_GREEN }
                    "*OsmDroid*"        { $C_MAGENTA }
                    "*WebRtc*"          { $C_BLUE }
                    "*Location*"        { $C_GREEN }
                    "*Camera*"          { $C_YELLOW }
                    "*Alert*"           { $C_RED }
                    "*Geofence*"        { $C_YELLOW }
                    "*AndroidRuntime*"  { $C_RED }
                    "*FATAL*"           { $C_RED }
                    default             { $C_WHITE }
                }

                $tagPadded = if ($tag.Length -gt 18) { $tag.Substring(0, 18) } else { $tag.PadRight(18) }
                $msgFormatted = "$C_RED$msg$C_RESET"

                Write-Host "  $C_DIM$time$C_RESET $lvlBadge $tagColor$tagPadded$C_RESET : $msgFormatted"

                if ($logFilePath) {
                    $savedLines.Add("$time $cleanBadge $($tag.PadRight(18)) : $msg")
                }
            } elseif ($rawLine -match '^\s+(at\s+.*|Caused by:.*|\.\.\.\s+\d+\s+more|Suppressed:.*)$' -or $rawLine -match 'FATAL EXCEPTION') {
                $frameCount++
                Write-Host "         $C_RED$rawLine$C_RESET"
                if ($logFilePath) {
                    $savedLines.Add("         $rawLine")
                }
            }
        }
    } catch {
        # Catch Ctrl+C cleanly
    } finally {
        Write-Host ""
        Write-Host "  $C_DIM----------------------------------------------------------------------$C_RESET"
        $statusSummary = "Warnings: $warnCount  |  Errors: $errCount"
        if ($fatalCount -gt 0) { $statusSummary += "  |  Fatals: $fatalCount" }
        if ($frameCount -gt 0) { $statusSummary += "  |  Stack Frames: $frameCount" }
        
        if ($warnCount -eq 0 -and $errCount -eq 0 -and $fatalCount -eq 0) {
            Write-Success "No errors or warnings detected! Application runtime is clean."
        } else {
            Write-Host "  $C_BOLD$C_WHITE[Audit Summary]$C_RESET $statusSummary"
            
            # Print top reporting components
            if ($tagCounts.Count -gt 0) {
                Write-Host "  $C_DIM Top Components:$C_RESET"
                $top = $tagCounts.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 3
                foreach ($t in $top) {
                    Write-Host "    - $($t.Key): $($t.Value)"
                }
            }
        }

        # Save to file if requested or prompted
        if ($logFilePath -and $savedLines.Count -gt 0) {
            $header = @(
                "================================================================================",
                "TRACE CONTROLLER -- ERRORS & WARNINGS LOG",
                "Captured At: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
                "Target Device: $($target.MarketName) ($($target.Serial))",
                "Application PID: $appPid",
                "Summary: Warnings=$warnCount, Errors=$errCount, Fatals=$fatalCount",
                "================================================================================",
                ""
            )
            $allContent = ($header + $savedLines) -join "`r`n"
            [System.IO.File]::WriteAllText($logFilePath, $allContent, [System.Text.Encoding]::UTF8)
            Write-Success "Log exported successfully to $logFilePath"
        } elseif (-not $logFilePath -and $savedLines.Count -gt 0 -and -not $Stream) {
            $ans = Read-Host "  Export this error report to a log file? [y/N]"
            if ($ans -and $ans.Trim().ToUpper() -eq "Y") {
                $logsDir = Join-Path $ProjectRoot "logs"
                if (-not (Test-Path $logsDir)) { New-Item -ItemType Directory -Path $logsDir | Out-Null }
                $timestamp = (Get-Date).ToString("yyyyMMdd_HHmmss")
                $targetFile = Join-Path $logsDir "errors_$timestamp.log"
                $header = @(
                    "================================================================================",
                    "TRACE CONTROLLER -- ERRORS & WARNINGS LOG",
                    "Captured At: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
                    "Target Device: $($target.MarketName) ($($target.Serial))",
                    "Application PID: $appPid",
                    "Summary: Warnings=$warnCount, Errors=$errCount, Fatals=$fatalCount",
                    "================================================================================",
                    ""
                )
                $allContent = ($header + $savedLines) -join "`r`n"
                [System.IO.File]::WriteAllText($targetFile, $allContent, [System.Text.Encoding]::UTF8)
                Write-Success "Log exported successfully to $targetFile"
            }
        }
        Write-Host "  $C_DIM----------------------------------------------------------------------$C_RESET"
    }
}

# ==============================================================================
# SUBCOMMAND: EMULATOR & HYPERVISOR MANAGEMENT
# ==============================================================================

function Get-HostSystemSpecs {
    $cores = [Environment]::ProcessorCount
    $totalRamMb = 8192
    try {
        $os = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
        if ($os -and $os.TotalVisibleMemorySize) {
            $totalRamMb = [int][Math]::Round($os.TotalVisibleMemorySize / 1024)
        } else {
            $cs = Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue
            if ($cs -and $cs.TotalPhysicalMemory) {
                $totalRamMb = [int][Math]::Round($cs.TotalPhysicalMemory / (1024 * 1024))
            }
        }
    } catch {
        $totalRamMb = 8192
    }
    return [PSCustomObject]@{
        Cores      = $cores
        TotalRamMb = $totalRamMb
        TotalRamGb = [Math]::Round($totalRamMb / 1024, 1)
    }
}

function Convert-ToMemoryMb([string]$inputVal, [int]$defaultMb = 4096) {
    if (-not $inputVal) { return $defaultMb }
    $trimmed = $inputVal.Trim()
    if ($trimmed -match '^(\d+)\s*G(B)?$') { return ([int]$matches[1] * 1024) }
    if ($trimmed -match '^(\d+)\s*M(B)?$') { return ([int]$matches[1]) }
    if ($trimmed -match '^\d+$') {
        $num = [int]$trimmed
        if ($num -le 32) { return ($num * 1024) }
        return $num
    }
    return $defaultMb
}

function Show-TraceEmulatorProfileMenu([PSCustomObject]$HostSpecs, [string]$DefaultAvd) {
    Write-Host ""
    Write-Host "$C_CYAN======================================================================$C_RESET"
    Write-Host "  $C_BOLD$C_WHITE TRACE EMULATOR ENGINE$C_RESET $C_DIM-- Hardware Profile & Configuration Selector$C_RESET"
    Write-Host "  $C_DIM Host System: $C_GREEN$($HostSpecs.Cores) CPU Cores$C_DIM | $C_GREEN$($HostSpecs.TotalRamGb) GB Physical RAM$C_DIM | Target AVD: $C_WHITE$DefaultAvd$C_RESET"
    Write-Host "$C_CYAN======================================================================$C_RESET"
    Write-Host ""
    Write-Host "  $C_CYAN$C_BOLD PRECONFIGURED HARDWARE PROFILES$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[1] Balanced / Standard$C_RESET  $C_GREEN[RECOMMENDED]$C_RESET"
    Write-Host "        $C_DIM RAM: 4096 MB (4 GB) | Cores: 4 | GPU: Host | Boot: Fast Snapshot$C_RESET"
    Write-Host "        $C_DIM Ideal for general controller app dev & silky-smooth 60 FPS Compose map rendering$C_RESET"
    Write-Host ""
    Write-Host "    $C_BOLD$C_WHITE[2] High Performance / Pro$C_RESET"
    Write-Host "        $C_DIM RAM: 8192 MB (8 GB) | Cores: 6 | GPU: Host | Boot: Fast Snapshot$C_RESET"
    Write-Host "        $C_DIM Ideal for heavy multitasking, camera video streaming & Artemis AI automation$C_RESET"
    Write-Host ""
    Write-Host "    $C_BOLD$C_WHITE[3] Ultra Performance / Studio$C_RESET"
    Write-Host "        $C_DIM RAM: 12288 MB (12 GB) | Cores: 8 | GPU: Host | Boot: Fast Snapshot$C_RESET"
    Write-Host "        $C_DIM Maximum heap & thread bandwidth for stress tests, memory audits & benchmarks$C_RESET"
    Write-Host ""
    Write-Host "    $C_BOLD$C_WHITE[4] Lightweight / Eco$C_RESET"
    Write-Host "        $C_DIM RAM: 2048 MB (2 GB) | Cores: 2 | GPU: Host | Audio: Off$C_RESET"
    Write-Host "        $C_DIM Low memory footprint; perfect when running alongside Docker, Chrome & heavy IDEs$C_RESET"
    Write-Host ""
    Write-Host "    $C_BOLD$C_WHITE[5] Clean Cold Boot$C_RESET"
    Write-Host "        $C_DIM RAM: 4096 MB (4 GB) | Cores: 4 | GPU: Host | Boot: Cold (-no-snapshot-load)$C_RESET"
    Write-Host "        $C_DIM Bypasses saved snapshots to boot the OS fresh from scratch for clean-slate testing$C_RESET"
    Write-Host ""
    Write-Host "    $C_BOLD$C_WHITE[6] Headless / Background (CI & Automation)$C_RESET"
    Write-Host "        $C_DIM RAM: 4096 MB (4 GB) | Cores: 4 | GPU: SwiftShader | Headless (-no-window)$C_RESET"
    Write-Host "        $C_DIM Runs silently in the background with zero GUI overhead; ideal for MCP & test runners$C_RESET"
    Write-Host ""
    Write-Host "  $C_CYAN$C_BOLD CUSTOM CONFIGURATION$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[7] Custom Hardware Builder$C_RESET  $C_DIM(or 'C')$C_RESET"
    Write-Host "        $C_DIM Interactively choose RAM, CPU Cores, GPU, Window mode, Audio & Network simulation$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[Q] Cancel / Return to Menu$C_RESET"
    Write-Host ""
    Write-Host "$C_CYAN----------------------------------------------------------------------$C_RESET"
}

function Invoke-TraceCustomEmulatorBuilder([PSCustomObject]$HostSpecs, [string[]]$InstalledAvds, [string]$DefaultAvd) {
    Write-Host ""
    Write-Host "$C_CYAN======================================================================$C_RESET"
    Write-Host "  $C_BOLD$C_WHITE CUSTOM EMULATOR CONFIGURATION BUILDER$C_RESET"
    Write-Host "  $C_DIM Host System: $($HostSpecs.Cores) Cores | $($HostSpecs.TotalRamGb) GB Physical RAM$C_RESET"
    Write-Host "$C_CYAN----------------------------------------------------------------------$C_RESET"

    # 1. Target AVD
    $chosenAvd = $DefaultAvd
    if ($InstalledAvds.Count -gt 1) {
        Write-Host "`n  $C_BOLD$C_WHITE[1/8] Select Target Android Virtual Device (AVD):$C_RESET"
        for ($i = 0; $i -lt $InstalledAvds.Count; $i++) {
            $defTag = if ($InstalledAvds[$i] -eq $DefaultAvd) { " $C_GREEN(Default)$C_RESET" } else { "" }
            Write-Host "    $C_BOLD$C_WHITE[$($i + 1)]$C_RESET $($InstalledAvds[$i])$defTag"
        }
        $avdIdx = Read-Host "  Select AVD [1-$($InstalledAvds.Count)] (Press Enter for '$DefaultAvd')"
        if ($avdIdx -and $avdIdx.Trim() -match '^\d+$') {
            $idx = [int]$avdIdx.Trim() - 1
            if ($idx -ge 0 -and $idx -lt $InstalledAvds.Count) {
                $chosenAvd = $InstalledAvds[$idx]
            }
        }
    }
    Write-Success "Target AVD: $chosenAvd"

    # 2. RAM Allocation
    Write-Host "`n  $C_BOLD$C_WHITE[2/8] Select RAM Allocation (Host RAM: $($HostSpecs.TotalRamGb) GB):$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[1]$C_RESET 2048 MB (2 GB)   $C_DIM-- Low memory footprint$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[2]$C_RESET 3072 MB (3 GB)   $C_DIM-- Light dev baseline$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[3]$C_RESET 4096 MB (4 GB)   $C_GREEN-- [Recommended] Silky 60 FPS Map UI$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[4]$C_RESET 6144 MB (6 GB)   $C_DIM-- High performance$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[5]$C_RESET 8192 MB (8 GB)   $C_DIM-- Pro performance & multitasking$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[6]$C_RESET 12288 MB (12 GB) $C_DIM-- Ultra studio / stress testing$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[7]$C_RESET 16384 MB (16 GB) $C_DIM-- Extreme memory allocation$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[C]$C_RESET Custom MB or GB input (e.g. 5G, 5120, 10GB)"
    
    $ramChoice = Read-Host "  Select RAM option [1-7, C] (Default: 3 [4 GB])"
    $chosenRamMb = 4096
    if (-not $ramChoice -or $ramChoice.Trim() -eq "3") {
        $chosenRamMb = 4096
    } elseif ($ramChoice.Trim() -eq "1") {
        $chosenRamMb = 2048
    } elseif ($ramChoice.Trim() -eq "2") {
        $chosenRamMb = 3072
    } elseif ($ramChoice.Trim() -eq "4") {
        $chosenRamMb = 6144
    } elseif ($ramChoice.Trim() -eq "5") {
        $chosenRamMb = 8192
    } elseif ($ramChoice.Trim() -eq "6") {
        $chosenRamMb = 12288
    } elseif ($ramChoice.Trim() -eq "7") {
        $chosenRamMb = 16384
    } elseif ($ramChoice.Trim().ToUpper() -eq "C" -or $ramChoice.Trim() -match '^\d+\s*[GM]?B?$') {
        $customVal = if ($ramChoice.Trim().ToUpper() -eq "C") {
            Read-Host "  Enter custom RAM (e.g. 5120, 5G, 6GB)"
        } else {
            $ramChoice.Trim()
        }
        $chosenRamMb = Convert-ToMemoryMb $customVal 4096
    }
    
    # Host RAM safety check
    if ($chosenRamMb -gt ($HostSpecs.TotalRamMb - 2048)) {
        Write-Warn "Allocating ${chosenRamMb}MB on a $($HostSpecs.TotalRamGb)GB host leaves less than 2GB for Windows/IDEs."
    }
    Write-Success "RAM Allocation: ${chosenRamMb} MB ($([Math]::Round($chosenRamMb / 1024, 1)) GB)"

    # 3. CPU Cores
    $defaultCores = [Math]::Max(2, [Math]::Min(4, $HostSpecs.Cores - 2))
    Write-Host "`n  $C_BOLD$C_WHITE[3/8] Select CPU Cores (Host Cores: $($HostSpecs.Cores)):$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[1]$C_RESET 2 Cores  $C_DIM-- Minimum$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[2]$C_RESET 4 Cores  $C_GREEN-- [Recommended] Optimal balance$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[3]$C_RESET 6 Cores  $C_DIM-- High performance$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[4]$C_RESET 8 Cores  $C_DIM-- Maximum recommended$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[C]$C_RESET Custom Core Count (1-$($HostSpecs.Cores))"
    $coreChoice = Read-Host "  Select Core option [1-4, C] (Default: 2 [4 Cores])"
    $chosenCores = $defaultCores
    if (-not $coreChoice -or $coreChoice.Trim() -eq "2") {
        $chosenCores = [Math]::Min(4, $HostSpecs.Cores)
    } elseif ($coreChoice.Trim() -eq "1") {
        $chosenCores = 2
    } elseif ($coreChoice.Trim() -eq "3") {
        $chosenCores = [Math]::Min(6, $HostSpecs.Cores)
    } elseif ($coreChoice.Trim() -eq "4") {
        $chosenCores = [Math]::Min(8, $HostSpecs.Cores)
    } elseif ($coreChoice.Trim().ToUpper() -eq "C" -or $coreChoice.Trim() -match '^\d+$') {
        $val = if ($coreChoice.Trim().ToUpper() -eq "C") {
            Read-Host "  Enter number of CPU cores (1-$($HostSpecs.Cores))"
        } else {
            $coreChoice.Trim()
        }
        if ($val -match '^\d+$') {
            $chosenCores = [Math]::Max(1, [Math]::Min($HostSpecs.Cores, [int]$val))
        }
    }
    Write-Success "CPU Cores: $chosenCores"

    # 4. GPU Mode
    Write-Host "`n  $C_BOLD$C_WHITE[4/8] Select GPU Emulation Mode:$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[1]$C_RESET host                  $C_GREEN-- [Recommended] Direct hardware acceleration (OpenGL/Vulkan)$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[2]$C_RESET swiftshader_indirect  $C_DIM-- Software rendering fallback (safe for crash recovery)$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[3]$C_RESET angle_indirect        $C_DIM-- Direct3D 11 via ANGLE$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[4]$C_RESET auto                  $C_DIM-- Automatic engine selection$C_RESET"
    $gpuChoice = Read-Host "  Select GPU mode [1-4] (Default: 1 [host])"
    $chosenGpu = "host"
    switch ($gpuChoice.Trim()) {
        "2" { $chosenGpu = "swiftshader_indirect" }
        "3" { $chosenGpu = "angle_indirect" }
        "4" { $chosenGpu = "auto" }
        default { $chosenGpu = "host" }
    }
    Write-Success "GPU Mode: $chosenGpu"

    # 5. Display Window Mode (GUI vs Headless)
    Write-Host "`n  $C_BOLD$C_WHITE[5/8] Select Display Mode:$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[1]$C_RESET Standard GUI Window   $C_GREEN-- [Default] Visible emulator on desktop$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[2]$C_RESET Headless (-no-window) $C_DIM-- Runs silently in background (MCP/CI)$C_RESET"
    $winChoice = Read-Host "  Select Display mode [1-2] (Default: 1)"
    $chosenNoWindow = ($winChoice.Trim() -eq "2")
    Write-Success "Display Mode: $(if ($chosenNoWindow) { 'Headless (-no-window)' } else { 'Standard GUI Window' })"

    # 6. Audio Mode
    Write-Host "`n  $C_BOLD$C_WHITE[6/8] Select Audio Output:$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[1]$C_RESET Audio Enabled         $C_GREEN-- [Default]$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[2]$C_RESET Audio Disabled        $C_DIM-- (-no-audio, reduces CPU usage)$C_RESET"
    $audChoice = Read-Host "  Select Audio mode [1-2] (Default: 1)"
    $chosenNoAudio = ($audChoice.Trim() -eq "2")
    Write-Success "Audio: $(if ($chosenNoAudio) { 'Disabled (-no-audio)' } else { 'Enabled' })"

    # 7. Boot Snapshot Mode
    Write-Host "`n  $C_BOLD$C_WHITE[7/8] Select Boot & Snapshot Mode:$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[1]$C_RESET Fast Snapshot Load    $C_GREEN-- [Default] Instant resume from saved snapshot$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[2]$C_RESET Clean Cold Boot       $C_DIM-- (-no-snapshot-load) Fresh OS reboot$C_RESET"
    $bootChoice = Read-Host "  Select Boot mode [1-2] (Default: 1)"
    $chosenColdBoot = ($bootChoice.Trim() -eq "2")
    Write-Success "Boot Mode: $(if ($chosenColdBoot) { 'Clean Cold Boot (-no-snapshot-load)' } else { 'Fast Snapshot Load' })"

    # 8. Wipe Data Option
    Write-Host "`n  $C_BOLD$C_WHITE[8/8] User Data & Storage Reset:$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[1]$C_RESET Preserve User Data    $C_GREEN-- [Default] Keep installed apps & test databases$C_RESET"
    Write-Host "    $C_BOLD$C_WHITE[2]$C_RESET Factory Reset         $C_RED-- (-wipe-data) Erase all emulator user data$C_RESET"
    $wipeChoice = Read-Host "  Select option [1-2] (Default: 1)"
    $chosenWipe = ($wipeChoice.Trim() -eq "2")
    if ($chosenWipe) {
        Write-Warn "Factory Reset selected: all user data will be wiped upon boot!"
    } else {
        Write-Success "User Data: Preserved"
    }

    return @{
        AvdName  = $chosenAvd
        RAM      = $chosenRamMb
        Cores    = $chosenCores
        GPU      = $chosenGpu
        Accel    = "auto"
        NoWindow = $chosenNoWindow
        NoAudio  = $chosenNoAudio
        ColdBoot = $chosenColdBoot
        WipeData = $chosenWipe
        NetSpeed = "full"
        NetDelay = "none"
    }
}

function Invoke-TraceEmulator {
    [CmdletBinding()]
    param(
        [Parameter(Position = 0)][string]$Profile = "",
        [Alias("RAM")][string]$Memory = "",
        [int]$MemoryMb = 0,
        [int]$Cores = 0,
        [string]$GPU = "",
        [string]$AvdName = "",
        [switch]$ColdBoot = $false,
        [switch]$NoWindow = $false,
        [switch]$Headless = $false,
        [switch]$NoAudio = $false,
        [switch]$WipeData = $false,
        [string]$NetSpeed = "full",
        [string]$NetDelay = "none",
        [switch]$NoWait = $false,
        [switch]$Interactive = $false,
        [switch]$Custom = $false
    )

    Write-Banner "Emulator Engine" "Hardware-Accelerated Android Virtual Device Controller"

    if (-not $Global:EmulatorPath) {
        Write-ErrorMsg "emulator.exe not found in Android SDK."
        exit 1
    }

    # 1. Verify Installed AVDs
    $installedAvds = @(& $Global:EmulatorPath -list-avds)
    if ($installedAvds.Count -eq 0) {
        Write-ErrorMsg "No Android Virtual Devices (AVD) found. Create one in Android Studio."
        exit 1
    }

    $defaultAvd = if ($AvdName -and ($installedAvds -contains $AvdName)) {
        $AvdName
    } elseif ($installedAvds -contains "Pixel_10_Pro") {
        "Pixel_10_Pro"
    } else {
        $installedAvds[0]
    }

    $hostSpecs = Get-HostSystemSpecs

    # Quick Help Dispatch
    if ($Profile -in "help", "-h", "--help", "/?") {
        Show-TraceEmulatorProfileMenu -HostSpecs $hostSpecs -DefaultAvd $defaultAvd
        Write-Host "  $C_CYAN$C_BOLD DIRECT LAUNCH SHORTCUTS:$C_RESET"
        Write-Host "    $C_WHITE trace emu standard$C_RESET      $C_DIM# Balanced / 4GB RAM / 4 Cores$C_RESET"
        Write-Host "    $C_WHITE trace emu pro$C_RESET           $C_DIM# High Performance / 8GB RAM / 6 Cores$C_RESET"
        Write-Host "    $C_WHITE trace emu ultra$C_RESET         $C_DIM# Ultra Performance / 12-16GB RAM / 8 Cores$C_RESET"
        Write-Host "    $C_WHITE trace emu light$C_RESET         $C_DIM# Eco / 2GB RAM / 2 Cores / No Audio$C_RESET"
        Write-Host "    $C_WHITE trace emu cold$C_RESET          $C_DIM# Clean Cold Boot (bypasses saved snapshot)$C_RESET"
        Write-Host "    $C_WHITE trace emu headless$C_RESET      $C_DIM# Background / No Window / SwiftShader$C_RESET"
        Write-Host "    $C_WHITE trace emu custom$C_RESET        $C_DIM# Interactive 8-step hardware wizard$C_RESET"
        Write-Host "    $C_WHITE trace emu 8G$C_RESET            $C_DIM# Allocate 8GB RAM directly$C_RESET"
        Write-Host "    $C_WHITE trace emu -RAM 8G -Cores 6 -ColdBoot -NoAudio$C_RESET"
        Write-Host ""
        return
    }

    # 2. Check if an emulator is already running
    $devs = @(Get-TraceDevices)
    $runningEmu = $devs | Where-Object { $_.IsEmulator } | Select-Object -First 1
    if ($runningEmu) {
        if ($NoWait) {
            Write-Success "Emulator is already active: $($runningEmu.Serial)"
            return
        }
        Write-Host ""
        Write-Host "  $C_YELLOW[!] Android Emulator is currently RUNNING on $($runningEmu.Serial) ($($runningEmu.MarketName))$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[1]$C_RESET Keep active emulator (Return to work)$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[2]$C_RESET Restart emulator with a new profile (Kills running instance)$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[3]$C_RESET Force stop / Kill active emulator$C_RESET"
        Write-Host ""
        $emuAction = Read-Host "  Select option [1-3] (Default: 1)"
        if ($emuAction -eq "2") {
            Write-Info "Terminating running emulator ($($runningEmu.Serial))..."
            Invoke-Adb @("-s", $runningEmu.Serial, "emu", "kill") | Out-Null
            for ($k = 0; $k -lt 15; $k++) {
                Start-Sleep -Seconds 1
                $curDevs = @(Get-TraceDevices)
                if (-not ($curDevs | Where-Object { $_.Serial -eq $runningEmu.Serial })) { break }
            }
            Write-Success "Running emulator stopped successfully."
        } elseif ($emuAction -eq "3") {
            Write-Info "Terminating running emulator ($($runningEmu.Serial))..."
            Invoke-Adb @("-s", $runningEmu.Serial, "emu", "kill") | Out-Null
            Write-Success "Emulator terminated."
            return
        } else {
            Write-Success "Using active emulator: $($runningEmu.Serial)"
            return
        }
    }

    $hostSpecs = Get-HostSystemSpecs
    if ($Headless) { $NoWindow = $true }

    # 3. Determine if we should prompt interactively
    # Check if a profile or explicit hardware flags were provided via CLI
    $isDirectCli = ($Profile -or $Memory -or ($MemoryMb -gt 0) -or ($Cores -gt 0) -or $GPU -or $ColdBoot -or $NoWindow -or $WipeData -or $NoAudio)
    
    # Check if the positional $Profile parameter is actually a RAM string (e.g., '4G', '8GB', '6144')
    if ($Profile -and ($Profile -match '^(\d+)\s*[GM]?B?$')) {
        $Memory = $Profile
        $Profile = ""
        $isDirectCli = $true
    }

    $selectedConfig = $null

    # If user passed -Custom switch
    if ($Custom -or ($Profile -in "7", "custom", "c", "manual", "builder")) {
        $selectedConfig = Invoke-TraceCustomEmulatorBuilder -HostSpecs $hostSpecs -InstalledAvds $installedAvds -DefaultAvd $defaultAvd
        $selectedConfig.ProfileName = "Custom Configuration"
    } elseif (-not $isDirectCli -and -not $NoWait -or $Interactive) {
        # Show Interactive Profile Menu
        Show-TraceEmulatorProfileMenu -HostSpecs $hostSpecs -DefaultAvd $defaultAvd
        $choice = Read-Host "  Select profile [1-6], [7] Custom, or [Q]uit (Default: 1)"
        if (-not $choice) { $choice = "1" }
        $choice = $choice.Trim()

        if ($choice.ToUpper() -eq "Q") {
            Write-Info "Emulator launch cancelled."
            return
        }

        switch ($choice.ToLower()) {
            { $_ -in "1", "standard", "balanced", "default" } {
                $selectedConfig = @{
                    AvdName  = $defaultAvd
                    RAM      = 4096
                    Cores    = 4
                    GPU      = "host"
                    Accel    = "auto"
                    ColdBoot = $false
                    NoWindow = $false
                    NoAudio  = $false
                    NetSpeed = "full"
                    NetDelay = "none"
                    ProfileName = "Balanced / Standard"
                }
            }
            { $_ -in "2", "pro", "high", "performance", "perf" } {
                $pCores = [Math]::Max(4, [Math]::Min(6, $hostSpecs.Cores - 2))
                $selectedConfig = @{
                    AvdName  = $defaultAvd
                    RAM      = 8192
                    Cores    = $pCores
                    GPU      = "host"
                    Accel    = "auto"
                    ColdBoot = $false
                    NoWindow = $false
                    NoAudio  = $false
                    NetSpeed = "full"
                    NetDelay = "none"
                    ProfileName = "High Performance / Pro"
                }
            }
            { $_ -in "3", "ultra", "max", "extreme", "studio" } {
                $uCores = [Math]::Max(4, [Math]::Min(8, $hostSpecs.Cores - 2))
                $uRam = if ($hostSpecs.TotalRamMb -gt 16000) { 16384 } else { 12288 }
                $selectedConfig = @{
                    AvdName  = $defaultAvd
                    RAM      = $uRam
                    Cores    = $uCores
                    GPU      = "host"
                    Accel    = "auto"
                    ColdBoot = $false
                    NoWindow = $false
                    NoAudio  = $false
                    NetSpeed = "full"
                    NetDelay = "none"
                    ProfileName = "Ultra Performance / Studio"
                }
            }
            { $_ -in "4", "light", "eco", "low", "min" } {
                $selectedConfig = @{
                    AvdName  = $defaultAvd
                    RAM      = 2048
                    Cores    = 2
                    GPU      = "host"
                    Accel    = "auto"
                    ColdBoot = $false
                    NoWindow = $false
                    NoAudio  = $true
                    NetSpeed = "full"
                    NetDelay = "none"
                    ProfileName = "Lightweight / Eco"
                }
            }
            { $_ -in "5", "cold", "coldboot", "clean", "fresh" } {
                $selectedConfig = @{
                    AvdName  = $defaultAvd
                    RAM      = 4096
                    Cores    = 4
                    GPU      = "host"
                    Accel    = "auto"
                    ColdBoot = $true
                    NoWindow = $false
                    NoAudio  = $false
                    NetSpeed = "full"
                    NetDelay = "none"
                    ProfileName = "Clean Cold Boot"
                }
            }
            { $_ -in "6", "headless", "ci", "bg", "background" } {
                $selectedConfig = @{
                    AvdName  = $defaultAvd
                    RAM      = 4096
                    Cores    = 4
                    GPU      = "swiftshader_indirect"
                    Accel    = "auto"
                    ColdBoot = $false
                    NoWindow = $true
                    NoAudio  = $true
                    NetSpeed = "full"
                    NetDelay = "none"
                    ProfileName = "Headless / Background"
                }
            }
            { $_ -in "7", "c", "custom", "manual", "builder" } {
                $selectedConfig = Invoke-TraceCustomEmulatorBuilder -HostSpecs $hostSpecs -InstalledAvds $installedAvds -DefaultAvd $defaultAvd
                $selectedConfig.ProfileName = "Custom Configuration"
            }
            default {
                Write-Warn "Unrecognized choice '$choice'. Defaulting to Balanced / Standard profile."
                $selectedConfig = @{
                    AvdName  = $defaultAvd
                    RAM      = 4096
                    Cores    = 4
                    GPU      = "host"
                    Accel    = "auto"
                    ColdBoot = $false
                    NoWindow = $false
                    NoAudio  = $false
                    NetSpeed = "full"
                    NetDelay = "none"
                    ProfileName = "Balanced / Standard"
                }
            }
        }
    } else {
        # Direct CLI invocation / Non-interactive / Scripting mode
        # Map named profile if specified
        if ($Profile) {
            switch ($Profile.ToLower()) {
                { $_ -in "pro", "high", "performance", "perf", "2" } {
                    $selectedConfig = @{
                        RAM      = 8192
                        Cores    = [Math]::Max(4, [Math]::Min(6, $hostSpecs.Cores - 2))
                        GPU      = "host"
                        ProfileName = "High Performance / Pro"
                    }
                }
                { $_ -in "ultra", "max", "extreme", "studio", "3" } {
                    $uRam = if ($hostSpecs.TotalRamMb -gt 16000) { 16384 } else { 12288 }
                    $selectedConfig = @{
                        RAM      = $uRam
                        Cores    = [Math]::Max(4, [Math]::Min(8, $hostSpecs.Cores - 2))
                        GPU      = "host"
                        ProfileName = "Ultra Performance / Studio"
                    }
                }
                { $_ -in "light", "eco", "low", "min", "4" } {
                    $selectedConfig = @{
                        RAM      = 2048
                        Cores    = 2
                        GPU      = "host"
                        NoAudio  = $true
                        ProfileName = "Lightweight / Eco"
                    }
                }
                { $_ -in "cold", "coldboot", "clean", "fresh", "5" } {
                    $selectedConfig = @{
                        RAM      = 4096
                        Cores    = 4
                        GPU      = "host"
                        ColdBoot = $true
                        ProfileName = "Clean Cold Boot"
                    }
                }
                { $_ -in "headless", "ci", "bg", "background", "6" } {
                    $selectedConfig = @{
                        RAM      = 4096
                        Cores    = 4
                        GPU      = "swiftshader_indirect"
                        NoWindow = $true
                        NoAudio  = $true
                        ProfileName = "Headless / Background"
                    }
                }
                default {
                    # Standard / Balanced
                    $selectedConfig = @{
                        RAM      = 4096
                        Cores    = 4
                        GPU      = "host"
                        ProfileName = "Balanced / Standard"
                    }
                }
            }
        } else {
            $selectedConfig = @{
                RAM      = 4096
                Cores    = [Math]::Max(2, [Math]::Min(4, $hostSpecs.Cores - 2))
                GPU      = "host"
                ProfileName = "CLI Custom Configuration"
            }
        }

        # Override with explicit CLI flags if provided
        if ($MemoryMb -gt 0) { $selectedConfig.RAM = $MemoryMb }
        elseif ($Memory) { $selectedConfig.RAM = Convert-ToMemoryMb $Memory $selectedConfig.RAM }

        if ($Cores -gt 0) { $selectedConfig.Cores = $Cores }
        if ($GPU) { $selectedConfig.GPU = $GPU }
        if ($ColdBoot) { $selectedConfig.ColdBoot = $true }
        if ($NoWindow) { $selectedConfig.NoWindow = $true }
        if ($NoAudio) { $selectedConfig.NoAudio = $true }
        if ($WipeData) { $selectedConfig.WipeData = $true }
        if ($NetSpeed) { $selectedConfig.NetSpeed = $NetSpeed }
        if ($NetDelay) { $selectedConfig.NetDelay = $NetDelay }
        if ($AvdName) { $selectedConfig.AvdName = $AvdName }
    }

    # Ensure all required fields exist
    $targetAvd  = if ($selectedConfig.AvdName) { $selectedConfig.AvdName } else { $defaultAvd }
    $finalRam   = if ($selectedConfig.RAM) { [int]$selectedConfig.RAM } else { 4096 }
    $finalCores = if ($selectedConfig.Cores) { [int]$selectedConfig.Cores } else { [Math]::Max(2, [Math]::Min(4, $hostSpecs.Cores - 2)) }
    $finalGpu   = if ($selectedConfig.GPU) { $selectedConfig.GPU } else { "host" }
    $finalAccel = if ($selectedConfig.Accel) { $selectedConfig.Accel } else { "auto" }
    $isCold     = if ($selectedConfig.ColdBoot) { $true } else { $false }
    $isNoWin    = if ($selectedConfig.NoWindow) { $true } else { $false }
    $isNoAud    = if ($selectedConfig.NoAudio) { $true } else { $false }
    $isWipe     = if ($selectedConfig.WipeData) { $true } else { $false }
    $sNetSpeed  = if ($selectedConfig.NetSpeed) { $selectedConfig.NetSpeed } else { "full" }
    $sNetDelay  = if ($selectedConfig.NetDelay) { $selectedConfig.NetDelay } else { "none" }
    $profTitle  = if ($selectedConfig.ProfileName) { $selectedConfig.ProfileName } else { "Custom Hardware Profile" }

    # Clamp cores safely to host capabilities
    if ($finalCores -gt $hostSpecs.Cores) {
        Write-Warn "Requested $finalCores CPU cores exceeds host capacity ($($hostSpecs.Cores)). Capping to $($hostSpecs.Cores)."
        $finalCores = $hostSpecs.Cores
    }

    Write-Host ""
    Write-Info "Profile:   $C_BOLD$C_WHITE$profTitle$C_RESET"
    Write-Info "Target:    AVD=$C_BOLD$targetAvd$C_RESET, RAM=$C_GREEN${finalRam}MB ($([Math]::Round($finalRam / 1024, 1)) GB)$C_RESET, Cores=$C_GREEN$finalCores$C_RESET"
    Write-Info "Graphics:  GPU=$finalGpu, Accel=$finalAccel, Display=$(if ($isNoWin) { 'Headless' } else { 'GUI Window' }), Audio=$(if ($isNoAud) { 'Disabled' } else { 'Enabled' })"
    if ($isCold) { Write-Info "Boot Mode: Cold Boot (-no-snapshot-load)" }
    if ($isWipe) { Write-Warn "Storage:   Factory Reset (-wipe-data)" }

    # 4. Construct Emulator Command Arguments
    $emuArgs = @(
        "-avd", $targetAvd,
        "-memory", "$finalRam",
        "-cores", "$finalCores",
        "-gpu", $finalGpu,
        "-accel", $finalAccel
    )
    if ($isCold) { $emuArgs += "-no-snapshot-load" }
    if ($isNoWin) { $emuArgs += "-no-window" }
    if ($isNoAud) { $emuArgs += "-no-audio" }
    if ($isWipe) { $emuArgs += "-wipe-data" }
    if ($sNetDelay -and $sNetDelay -ne "none") { $emuArgs += @("-netdelay", $sNetDelay) }
    if ($sNetSpeed -and $sNetSpeed -ne "full") { $emuArgs += @("-netspeed", $sNetSpeed) }
    
    # Fast boot optimizations unless headless
    if (-not $isNoWin) {
        $emuArgs += "-no-boot-anim"
    }
    if (-not ($emuArgs -contains "-netdelay")) { $emuArgs += @("-netdelay", "none") }
    if (-not ($emuArgs -contains "-netspeed")) { $emuArgs += @("-netspeed", "full") }

    # 5. Launch Emulator Process
    $proc = Start-Process -FilePath $Global:EmulatorPath -ArgumentList $emuArgs -PassThru

    if ($NoWait) {
        Write-Success "Emulator process spawned in background (PID: $($proc.Id))."
        return
    }

    # 6. Await boot completion with live progress indicator
    Write-Info "Awaiting emulator boot completion property (sys.boot_completed)..."
    $booted = $false
    $timeoutSeconds = if ($isCold -or $isWipe) { 120 } else { 90 }
    $elapsed = 0
    $stepInterval = 3

    while ($elapsed -lt $timeoutSeconds) {
        Start-Sleep -Seconds $stepInterval
        $elapsed += $stepInterval
        
        # Verify process is still alive
        if ($proc.HasExited) {
            Write-ErrorMsg "Emulator process exited unexpectedly with code $($proc.ExitCode)."
            Write-Warn "Common causes: insufficient host RAM, incompatible GPU setting, or WHPX/Hyper-V conflict."
            Write-Info "Try launching with Profile 4 (Lightweight) or Profile 5 (Clean Cold Boot)."
            return
        }

        $devs = @(Get-TraceDevices)
        $emu = $devs | Where-Object { $_.IsEmulator } | Select-Object -First 1
        if ($emu) {
            $bootRes = Invoke-Adb @("-s", $emu.Serial, "shell", "getprop", "sys.boot_completed")
            if ($bootRes.StdOut -eq "1") {
                $booted = $true
                Write-Success "$targetAvd is fully booted and ready on $($emu.Serial) (PID: $($proc.Id))!"
                # Wake screen and dismiss keyguard
                Invoke-Adb @("-s", $emu.Serial, "shell", "input", "keyevent", "224") | Out-Null
                Invoke-Adb @("-s", $emu.Serial, "shell", "wm", "dismiss-keyguard") | Out-Null
                break
            }
        }
        Write-Host -NoNewline "$C_DIM."
    }
    Write-Host ""

    if (-not $booted) {
        Write-Warn "Boot wait timed out after ${timeoutSeconds}s. The emulator may still be initializing in the background."
    }
}

function Invoke-TraceAehd {
    Write-Banner "AEHD Driver Setup" "Installing official Google Android Emulator Hypervisor Driver"
    $driverDir = Join-Path $env:LOCALAPPDATA "Android\Sdk\extras\google\Android_Emulator_Hypervisor_Driver"
    $infFile = Join-Path $driverDir "aehd.Inf"
    if (-not (Test-Path $infFile)) {
        Write-ErrorMsg "Driver files not found at $driverDir"
        exit 1
    }

    Write-Info "Requesting Administrator privileges to install AEHD driver..."
    $cmd = "sc stop aehd >nul 2>&1; rundll32.exe setupapi.dll,InstallHinfSection DefaultInstall 132 `"$infFile`"; sc start aehd"
    Start-Process cmd -ArgumentList "/c $cmd" -Verb RunAs -Wait
    Write-Success "AEHD driver installation command executed."
}

function Invoke-TraceHyperv {
    Write-Banner "Windows Hypervisor" "Configuring WHPX boot launch type"
    Write-Info "Requesting Administrator privileges to set bcdedit hypervisorlaunchtype auto..."
    Start-Process cmd -ArgumentList "/c bcdedit /set hypervisorlaunchtype auto" -Verb RunAs -Wait
    Write-Success "Windows hypervisor configured to auto. A system restart is required."
}

# ==============================================================================
# SUBCOMMAND: WIRELESS PAIRING & SCREEN MIRRORING
# ==============================================================================

function Invoke-TracePair {
    [CmdletBinding()]
    param([switch]$Web = $false)

    Write-Banner "Wireless Debugging" "Android 11+ Wi-Fi Easy Connect Pairing Engine"

    # Quick check if already connected
    $devs = @(Get-TraceDevices)
    $activeWireless = $devs | Where-Object { $_.IsWireless -and $_.Status -eq "device" }
    if ($activeWireless) {
        Write-Success "Device is already connected wirelessly: $($activeWireless.Serial) ($($activeWireless.MarketName))"
        return
    }

    # Check for mDNS connectable device first (zero-touch reconnect without QR code)
    Write-Info "Checking for already-paired wireless devices on local network..."
    $mdnsRes = Invoke-Adb @("mdns", "services")
    $connectLines = $mdnsRes.Output -split "`r?`n" | Where-Object { $_ -match '_adb-tls-connect\._tcp' }
    if ($connectLines) {
        foreach ($cLine in $connectLines) {
            if ($cLine -match '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}):(\d+)') {
                $endpoint = "$($matches[1]):$($matches[2])"
                Write-Info "Discovered paired device at $endpoint. Reconnecting..."
                $connRes = Invoke-Adb @("connect", $endpoint)
                if ($connRes.Output -match "connected") {
                    Write-Success "Reconnected wirelessly to $endpoint without re-pairing!"
                    return
                }
            }
        }
    }

    # Start pairing server & UI
    $pairScript = "C:\Users\ZNS\Documents\phone\wireless_pair.js"
    if (Test-Path $pairScript) {
        Write-Info "Launching pairing server at http://localhost:5055..."
        Start-Process "node" -ArgumentList "`"$pairScript`"" -WindowStyle Hidden
        Start-Sleep -Seconds 2
        Start-Process "http://localhost:5055"
        Write-Success "Pairing dashboard launched in browser!"
        Write-Host "  $C_WHITE 1. On phone: Go to Developer Options > Wireless Debugging.$C_RESET"
        Write-Host "  $C_WHITE 2. Tap 'Pair device with QR code' and scan your browser screen.$C_RESET"
    } else {
        Write-ErrorMsg "wireless_pair.js not found at $pairScript"
    }
}

function Invoke-TraceMirror {
    [CmdletBinding()]
    param(
        [switch]$StayAwake = $true,
        [switch]$TurnScreenOff = $false,
        [string]$MaxSize = "1280",
        [string]$BitRate = "6M"
    )

    Write-Banner "Screen Mirror (scrcpy)" "Direct3D 11 Hardware-Accelerated Phone Controller"

    if (-not $Global:ScrcpyPath) {
        Write-ErrorMsg "scrcpy.exe not found. Expected in C:\Users\ZNS\Documents\phone\scrcpy.exe"
        exit 1
    }

    # Select target
    $devs = @(Get-TraceDevices)
    $target = $devs | Where-Object { $_.Status -eq "device" -and $_.IsWireless } | Select-Object -First 1
    if (-not $target) {
        $target = $devs | Where-Object { $_.Status -eq "device" -and -not $_.IsEmulator } | Select-Object -First 1
    }
    if (-not $target) {
        $target = $devs | Where-Object { $_.Status -eq "device" } | Select-Object -First 1
    }

    if (-not $target) {
        Write-ErrorMsg "No device connected to mirror. Run 'trace deploy' or 'trace pair'."
        exit 1
    }

    Write-Success "Mirroring Target: $($target.MarketName) ($($target.Serial))"

    $scrcpyArgs = @(
        "-s", $target.Serial,
        "--max-size", $MaxSize,
        "--video-bit-rate", $BitRate,
        "--max-fps", "60",
        "--window-title", "Trace Controller - $($target.MarketName)"
    )
    if ($StayAwake) { $scrcpyArgs += "--stay-awake" }
    if ($TurnScreenOff) { $scrcpyArgs += "--turn-screen-off" }

    # Launch with scrcpy directory as working directory to load DLLs cleanly
    Start-Process -FilePath $Global:ScrcpyPath -ArgumentList $scrcpyArgs -WorkingDirectory $Global:ScrcpyDir
    Write-Success "scrcpy mirror active on desktop!"
}

# ==============================================================================
# SUBCOMMAND: TEST & BUILD
# ==============================================================================

function Invoke-TraceTest {
    [CmdletBinding()]
    param([string]$Target = "controller", [switch]$Rerun = $false)

    Write-Banner "Unit Test Runner" "Executing core domain, state store, and pipeline verification tests"

    $gradlew = Join-Path $ProjectRoot "gradlew.bat"
    $tasks = @(":core:testDebugUnitTest", ":app:testDebugUnitTest")
    $extraArgs = @("--build-cache", "--parallel", "--continue")
    if ($Rerun) { $extraArgs += "--rerun-tasks" }

    Write-Info "Running: gradlew $($tasks -join ' ') $($extraArgs -join ' ')..."
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-NativeTool -ScriptBlock { & $gradlew @tasks @extraArgs } -ErrorMsg "Unit tests failed"
    $sw.Stop()

    Write-Success "All unit tests in :core and :app passed cleanly in $([math]::Round($sw.Elapsed.TotalSeconds, 1))s!"
}

function Invoke-TraceBuild {
    [CmdletBinding()]
    param([switch]$Release = $false, [switch]$Clean = $false)

    Write-Banner "Build Assembler" "Generating APK artifacts via Gradle"

    $gradlew = Join-Path $ProjectRoot "gradlew.bat"
    if ($Clean) {
        Invoke-NativeTool -ScriptBlock { & $gradlew clean } -ErrorMsg "Gradle clean failed"
    }

    $targetTask = if ($Release) { ":app:assembleRelease" } else { ":app:assembleDebug" }
    Write-Info "Executing $targetTask..."
    Invoke-NativeTool -ScriptBlock { & $gradlew $targetTask --build-cache } -ErrorMsg "Build failed"

    $outputApk = if ($Release) {
        Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk"
    } else {
        Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
    }

    if (Test-Path $outputApk) {
        $size = [math]::Round((Get-Item $outputApk).Length / 1MB, 2)
        Write-Success "Artifact assembled: $outputApk ($size MB)"
    }
}

# ==============================================================================
# SUBCOMMAND: RELEASE & PUBLISH
# ==============================================================================

function Invoke-TraceRelease {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$false)][switch]$SkipTests = $false,
        [Parameter(Mandatory=$false)][string]$Channel = "stable",
        [Parameter(Mandatory=$false)][switch]$Mandatory = $false,
        [Parameter(Mandatory=$false)][string]$ReleaseNotes = ""
    )

    if ($Channel -match '^-.*skiptest') {
        $SkipTests = $true
        $Channel = "stable"
    }

    Write-Banner "Release Pipeline" "Test, assemble, verify signature, compute hashes & update manifest"

    # 1. Parse Version Code and Name Scoped to defaultConfig
    $buildGradle = Get-Content "app/build.gradle.kts" -Raw
    $versionCodeMatch = [regex]::Match($buildGradle, 'defaultConfig\s*\{[^}]*versionCode\s*=\s*(\d+)')
    if (-not $versionCodeMatch.Success) {
        $versionCodeMatch = [regex]::Match($buildGradle, 'versionCode\s*=\s*(\d+)')
    }
    $versionNameMatch = [regex]::Match($buildGradle, 'defaultConfig\s*\{[^}]*versionName\s*=\s*"([^"]+)"')
    if (-not $versionNameMatch.Success) {
        $versionNameMatch = [regex]::Match($buildGradle, 'versionName\s*=\s*"([^"]+)"')
    }

    $versionCode = [long]$versionCodeMatch.Groups[1].Value
    $versionName = $versionNameMatch.Groups[1].Value

    Write-Info "Target Version: v$versionName (VersionCode: $versionCode) | Channel: $Channel"

    # 2. Run Test Suites
    if (-not $SkipTests) {
        Write-Step "1/5" "Executing Core & App Unit Tests..."
        Invoke-TraceTest
    }

    # 3. Assemble Release
    Write-Step "2/5" "Assembling Release APK (:app:assembleRelease)..."
    Invoke-TraceBuild -Release

    $releaseApk = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $releaseApk)) {
        Write-ErrorMsg "Release APK not found at $releaseApk"
        exit 1
    }
    $apkItem = Get-Item $releaseApk
    $apkSizeBytes = $apkItem.Length

    # 4. Verify Signing with apksigner
    Write-Step "3/5" "Verifying APK Signature via apksigner..."
    $certFingerprint = $null
    if ($Global:ApkSignerPath) {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $Global:ApkSignerPath
        $psi.Arguments = "verify --print-certs `"$releaseApk`""
        $psi.RedirectStandardOutput = $true
        $psi.UseShellExecute = $false
        $p = [System.Diagnostics.Process]::Start($psi)
        $out = $p.StandardOutput.ReadToEnd()
        $p.WaitForExit()

        if ($out -match 'certificate SHA-256 digest:\s*([a-fA-F0-9]{64})') {
            $certFingerprint = $matches[1].ToLower()
            Write-Success "Verified Signing Certificate: $certFingerprint"
        }
    }
    if (-not $certFingerprint) {
        Write-Warn "apksigner cert fingerprint not obtained; emitting null in manifest to prevent schema invalidation."
    }

    # 5. Compute SHA-256 Checksum
    Write-Step "4/5" "Computing cryptographic SHA-256 checksum..."
    $sha256 = (Get-FileHash -Path $releaseApk -Algorithm SHA256).Hash.ToLower()
    Write-Success "SHA-256: $sha256"

    # 6. Generate update-manifest.json
    Write-Step "5/5" "Writing release/update-manifest.json..."
    $releaseDir = Join-Path $ProjectRoot "release"
    if (-not (Test-Path $releaseDir)) { New-Item -ItemType Directory -Path $releaseDir | Out-Null }

    $notes = if ($ReleaseNotes) { $ReleaseNotes } else { "Trace Controller v$versionName stable release." }
    $publishedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    $downloadUrl = "https://github.com/DreamHAX444/Controller-App/releases/download/v$versionName/app-release.apk"

    $manifest = [ordered]@{
        schemaVersion               = 1
        appId                       = "com.livetracker.controller"
        channel                     = $Channel
        versionCode                 = $versionCode
        versionName                 = $versionName
        minimumSupportedVersionCode = 1
        mandatory                   = [bool]$Mandatory
        publishedAt                 = $publishedAt
        releaseNotes                = $notes
        apkUrl                      = $downloadUrl
        apkSizeBytes                = $apkSizeBytes
        sha256                      = $sha256
        signingCertSha256           = $certFingerprint
    }

    $manifestJson = $manifest | ConvertTo-Json -Depth 5
    $manifestPath = Join-Path $releaseDir "update-manifest.json"
    
    # Write clean UTF-8 without BOM
    [System.IO.File]::WriteAllText($manifestPath, $manifestJson, (New-Object System.Text.UTF8Encoding($false)))
    Write-Success "Generated Manifest at $manifestPath"
    Write-Host $manifestJson -ForegroundColor Gray
}

function Invoke-TracePublishApi {
    Write-Banner "Publish to GitHub" "Uploading release artifacts to GitHub Releases API"

    $manifestPath = Join-Path $ProjectRoot "release\update-manifest.json"
    if (-not (Test-Path $manifestPath)) {
        Write-ErrorMsg "Manifest not found. Run 'trace release' first."
        exit 1
    }

    $manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json
    $versionName = $manifest.versionName
    $tag = "v$versionName"
    $repo = "DreamHAX444/Controller-App"

    # 1. Resolve GitHub Token
    $token = $env:GITHUB_TOKEN
    if (-not $token) { $token = $env:GH_TOKEN }
    if (-not $token) {
        $ghCmd = Get-Command "gh" -ErrorAction SilentlyContinue
        if ($ghCmd) {
            $token = (& gh auth token 2>$null).Trim()
        }
    }
    if (-not $token) {
        $p = Start-Process git -ArgumentList "credential fill" -NoNewWindow -PassThru -RedirectStandardInput ([System.IO.Path]::GetTempFileName()) -RedirectStandardOutput ([System.IO.Path]::GetTempFileName())
        # Fallback to python publisher if needed
    }

    $pyPublisher = Join-Path $ProjectRoot "scripts\publish_release_api.py"
    if (Test-Path $pyPublisher) {
        Write-Info "Executing GitHub Releases publisher..."
        & python $pyPublisher
    }
}

# ==============================================================================
# SUBCOMMAND: HELP & ENTRYPOINT DISPATCH
# ==============================================================================

function Show-TraceHelp {
    Write-Banner "CLI Help & Command Index" "Unified Developer Toolchain for Trace Android Systems"
    Write-Host @"
USAGE:
  trace <command> [options]

CORE WORKFLOWS:
  deploy, d        Build, install (MIUI zero-touch), and activate application
  run, r           Deploy application and attach streaming colorized logcat
  live, l [target] Stream live clean app logs (aliases: logcat, catlog, logs)
                   Targets: controller, tracker, both (dual stream), all
                   Hotkeys in stream: [C] Controller, [T] Tracker, [B] Dual Both, [Tab] Cycle
  errors, err      Audit and log application warnings, errors & crashes (aliases: warn)
  mirror, m        Launch low-latency screen mirroring & mouse control via scrcpy
  pair, p          Pair with phone over Wi-Fi using Android 11+ QR code / mDNS
  status, s        Display connected devices, battery, APKs, and backend health
  doctor, doc      Comprehensive environment & prerequisite diagnostics

BUILD & RELEASE:
  test, t          Execute unit tests across :core and :app modules
  build, b         Compile and assemble debug or release APKs
  release, rel     Test, build release APK, verify apksigner, and generate manifest
  publish, pub     Upload release APK and update-manifest.json to GitHub Releases

EMULATOR & HYPERVISOR:
  emu [profile|RAM] Launch Android emulator with preconfigured profile or custom specs
                    Profiles: standard (4GB), pro (8GB), ultra (12GB), light (2GB),
                              cold (clean boot), headless (CI/background), custom (wizard)
                    Examples: 'trace emu', 'trace emu 8G', 'trace emu pro', 'trace emu custom'
  aehd              Install official Google Android Emulator Hypervisor Driver
  hyperv            Enable Windows Hypervisor Platform (WHPX) boot launch type

OPTIONS:
  -RAM, -MemoryMb  Set custom emulator RAM allocation (e.g. -RAM 8G or -MemoryMb 6144)
  -Cores           Allocate virtual CPU cores (capped at host logical core count)
  -GPU <mode>      Set GPU emulation mode: host, swiftshader_indirect, angle_indirect
  -ColdBoot        Force clean cold boot ignoring saved snapshots (-no-snapshot-load)
  -NoWindow        Run emulator headlessly in background without graphical window
  -NoAudio         Disable emulator audio input and output (-no-audio)
  -WipeData        Factory-reset emulator userdata partition (-wipe-data)
  -Custom          Launch interactive step-by-step hardware builder wizard
  -App <target>    Target app for live logs: controller, tracker, both, all
  -Dual            Interleaved dual-app telemetry stream (controller + tracker)
  -Dump, -d        Dump recent logs and exit (non-streaming)
  -Save, -s        Automatically export error audit report to logs/errors_<timestamp>.log
  -File <path>     Specify custom destination file for error log export
  -Stream          Continuously stream errors/warnings in real-time
  -Clear, -c       Clear device logcat buffer
  -Filter, -f      Filter logs by keyword or regex pattern
  -Level, -lvl     Minimum log level (V, D, I, W, E)
  -Lines, -n       Number of log lines to inspect (default: 100 for live, 200 for errors)
  -SkipBuild       Skip Gradle compilation and install existing APK artifact
  -TargetSerial    Specify exact target device serial or Wi-Fi IP
  -Emulator        Target Pixel 10 Pro Android Emulator explicitly
  -Release         Target production release variant instead of debug

EXAMPLES:
  trace emu                     # Open interactive hardware profile selector (1-7)
  trace emu pro                 # Launch High-Performance profile (8GB RAM, 6 Cores)
  trace emu 8G -Cores 6         # Launch with 8GB RAM and 6 virtual CPU cores
  trace emu custom              # Launch interactive 8-step hardware builder wizard
  trace emu headless            # Launch lightweight background instance for CI/testing
  trace live                    # Stream live logs with interactive app target selector
  trace live tracker            # Stream Tracker App live logs (com.example.livelocationservice)
  trace live controller         # Stream Controller App live logs (com.livetracker.controller)
  trace live both               # Stream Dual interleaved logs (both apps simultaneously)
  trace live -Filter "coord"    # Filter live logcat for location coordinate logs
  trace live -Dump              # Quick dump of recent app logs without streaming
  trace errors                  # Audit and display recent warnings, errors & crashes
  trace errors -Save            # Audit warnings/errors and save clean log to disk
  trace errors -Stream          # Stream warnings and errors continuously
  trace mirror                  # Open live phone screen on desktop with scrcpy
  trace pair                    # Pair wirelessly with Android 11+ QR code
  trace test                    # Run all 499 unit tests
  trace release                 # Prepare signed release artifact and update-manifest.json
  trace doctor                  # Run full environment health diagnostic
"@
}

function Invoke-DispatchedCommand([string]$FnName, [string[]]$ArgsList) {
    if ($ArgsList -and $ArgsList.Count -gt 0) {
        $formatted = foreach ($a in $ArgsList) {
            if ($a -match '\s' -and -not ($a.StartsWith("'") -and $a.EndsWith("'"))) {
                "'$a'"
            } else {
                $a
            }
        }
        $cmdLine = "$FnName " + ($formatted -join ' ')
        & ([scriptblock]::Create($cmdLine))
    } else {
        & $FnName
    }
}

function Invoke-TraceInteractiveMenu {
    while ($true) {
        $devs = @(Get-TraceDevices)
        $targetInfo = if ($devs.Count -gt 0) {
            $d = $devs[0]
            "$C_GREEN$($d.MarketName) ($($d.Serial))$C_RESET"
        } else {
            "$C_YELLOWNo device attached (Run [5] to pair Wi-Fi or [0] for emulator)$C_RESET"
        }

        Write-Host ""
        Write-Host "$C_CYAN======================================================================$C_RESET"
        Write-Host "  $C_BOLD$C_WHITE TRACE CONTROLLER$C_RESET $C_DIM-- Developer & Device Toolchain$C_RESET"
        Write-Host "  $C_DIM Target: $C_RESET$targetInfo"
        Write-Host "$C_CYAN======================================================================$C_RESET"
        Write-Host ""
        Write-Host "  $C_CYAN$C_BOLD DEVELOPMENT & DEVICE$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[1]$C_RESET Deploy App          $C_DIM Zero-touch install & launch on phone$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[2]$C_RESET Stream Live Logcat  $C_DIM Live real-time app events & telemetry$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[3]$C_RESET Errors & Warnings   $C_DIM Audit W/E, crashes & save report to disk$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[4]$C_RESET Mirror Screen       $C_DIM scrcpy 60 FPS low-latency display$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[5]$C_RESET Wireless Pair       $C_DIM Android 11+ Wi-Fi QR / mDNS scanner$C_RESET"
        Write-Host ""
        Write-Host "  $C_CYAN$C_BOLD TEST & RELEASE$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[6]$C_RESET Run Unit Tests      $C_DIM Execute 499 tests across :core & :app$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[7]$C_RESET Build Release       $C_DIM Signed APK + update-manifest.json$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[8]$C_RESET Fleet Status        $C_DIM Attached devices, battery & APK artifacts$C_RESET"
        Write-Host ""
        Write-Host "  $C_CYAN$C_BOLD SYSTEM & TOOLS$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[9]$C_RESET Doctor Diagnostics  $C_DIM Check SDK, JDK, ADB, drivers$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[0]$C_RESET Launch Emulator     $C_DIM Pixel 10 Pro hardware-accelerated AVD$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[H]$C_RESET CLI Help            $C_DIM View full command-line flags$C_RESET"
        Write-Host "    $C_BOLD$C_WHITE[Q]$C_RESET Exit Control Center$C_RESET"
        Write-Host ""
        Write-Host "$C_CYAN----------------------------------------------------------------------$C_RESET"

        $rawChoice = Read-Host "  Select option [0-9, H, Q]"
        if (-not $rawChoice) { continue }
        $choice = $rawChoice.Trim().ToUpper()

        switch ($choice) {
            "1" { Write-Host ""; Invoke-TraceDeploy }
            "2" { Write-Host ""; Invoke-TraceLogcat }
            "3" { Write-Host ""; Invoke-TraceErrors }
            "4" { Write-Host ""; Invoke-TraceMirror }
            "5" { Write-Host ""; Invoke-TracePair }
            "6" { Write-Host ""; Invoke-TraceTest }
            "7" { Write-Host ""; Invoke-TraceRelease }
            "8" { Write-Host ""; Invoke-TraceStatus }
            "9" { Write-Host ""; Invoke-TraceDoctor }
            { $_ -in "0", "E" } { Write-Host ""; Invoke-TraceEmulator }
            "H" { Write-Host ""; Show-TraceHelp }
            "Q" { return }
            default {
                Write-Host "  $C_YELLOW[!] Unrecognized option '$rawChoice'. Select 0-9, H, or Q.$C_RESET"
            }
        }

        Write-Host ""
        Write-Host "  $C_DIM----------------------------------------------------------------------$C_RESET"
        $next = Read-Host "  Press Enter to return to menu (or 'q' to exit)"
        if ($next -and $next.Trim().ToUpper() -eq "Q") {
            return
        }
    }
}

# Dispatch Command
switch ($Command.ToLower()) {
    { $_ -in "menu", "" }           { Invoke-TraceInteractiveMenu }
    { $_ -in "deploy", "d" }        { Invoke-DispatchedCommand "Invoke-TraceDeploy" $RemainingArgs }
    { $_ -in "run", "r" }           { Invoke-DispatchedCommand "Invoke-TraceDeploy" (@("-Run") + $RemainingArgs) }
    { $_ -in "live", "logcat", "catlog", "log", "logs", "l" } { Invoke-DispatchedCommand "Invoke-TraceLogcat" $RemainingArgs }
    { $_ -in "errors", "error", "err", "warn", "warnings", "w" } { Invoke-DispatchedCommand "Invoke-TraceErrors" $RemainingArgs }
    { $_ -in "mirror", "m" }        { Invoke-DispatchedCommand "Invoke-TraceMirror" $RemainingArgs }
    { $_ -in "pair", "p" }          { Invoke-DispatchedCommand "Invoke-TracePair" $RemainingArgs }
    { $_ -in "status", "s", "fleet"}{ Invoke-TraceStatus }
    { $_ -in "doctor", "doc" }      { Invoke-TraceDoctor }
    { $_ -in "test", "t" }          { Invoke-DispatchedCommand "Invoke-TraceTest" $RemainingArgs }
    { $_ -in "build", "b" }         { Invoke-DispatchedCommand "Invoke-TraceBuild" $RemainingArgs }
    { $_ -in "release", "rel" }     { Invoke-DispatchedCommand "Invoke-TraceRelease" $RemainingArgs }
    { $_ -in "publish", "pub" }     { Invoke-DispatchedCommand "Invoke-TracePublishApi" $RemainingArgs }
    { $_ -in "publish-api" }        { Invoke-DispatchedCommand "Invoke-TracePublishApi" $RemainingArgs }
    { $_ -in "emu", "emulator" }    { Invoke-DispatchedCommand "Invoke-TraceEmulator" $RemainingArgs }
    { $_ -in "aehd" }               { Invoke-TraceAehd }
    { $_ -in "hyperv" }             { Invoke-TraceHyperv }
    { $_ -in "help", "-h", "--help"}{ Show-TraceHelp }
    default {
        Write-ErrorMsg "Unknown command '$Command'."
        Show-TraceHelp
        exit 1
    }
}

