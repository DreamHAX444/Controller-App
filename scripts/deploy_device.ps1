# Automated build, device discovery, installation, and launch script for Trace Controller.
# Prioritizes Redmi 11 Prime (22071219AI / rock_in), Pixel 10 Pro Android Emulator, or any connected ADB device.

param(
    [switch]$SkipBuild = $false,
    [string]$TargetSerial = "",
    [switch]$Emulator = $false,
    [switch]$LaunchEmulator = $false,
    [int]$MemoryMb = 0
)

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $ProjectRoot

Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host " Trace Controller - ADB Auto Deploy and Launch System" -ForegroundColor Cyan
Write-Host "=====================================================" -ForegroundColor Cyan

# 1. Locate ADB
$adbPath = $null
$possibleAdbPaths = @(
    "C:\Users\ZNS\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)

# Also check local.properties
$localProperties = Join-Path $ProjectRoot "local.properties"
if (Test-Path $localProperties) {
    Get-Content $localProperties | ForEach-Object {
        if ($_ -match "sdk\.dir=(.*)") {
            $rawDir = $matches[1].Replace("\\", "\").Replace("\:", ":")
            $possibleAdbPaths = @((Join-Path $rawDir "platform-tools\adb.exe")) + $possibleAdbPaths
        }
    }
}

foreach ($p in $possibleAdbPaths) {
    if (Test-Path $p) {
        $adbPath = $p
        break
    }
}

if (-not $adbPath) {
    $cmd = Get-Command "adb" -ErrorAction SilentlyContinue
    if ($cmd) {
        $adbPath = $cmd.Source
    }
}

if (-not $adbPath) {
    Write-Error "Could not locate adb.exe. Please ensure Android SDK platform-tools are installed."
    exit 1
}

Write-Host "[+] ADB Path: $adbPath" -ForegroundColor Green

# Resilient ADB execution helper to avoid PowerShell NativeCommandError stderr stream pollution
function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$AdbArgs
    )
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $adbPath
    $formattedArgs = foreach ($arg in $AdbArgs) {
        if ($arg -match '\s' -and -not ($arg.StartsWith('"') -and $arg.EndsWith('"'))) {
            "`"$arg`""
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
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    $combined = ($stdout + "`n" + $stderr).Trim()
    return [PSCustomObject]@{
        ExitCode = $process.ExitCode
        Output   = $combined
        StdOut   = $stdout.Trim()
        StdErr   = $stderr.Trim()
    }
}

# Function to get active devices list
function Get-ConnectedDevicesList {
    $devicesRes = Invoke-Adb @("devices", "-l")
    $lines = $devicesRes.Output -split "`r?`n"
    $devices = @()

    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ($trimmed -and -not ($trimmed.StartsWith("List of devices")) -and -not ($trimmed.StartsWith("* daemon"))) {
            $parts = -split $trimmed
            if ($parts.Count -ge 2 -and $parts[1] -eq "device") {
                $serial = $parts[0]
                $details = $trimmed
                
                # Check market name / model via getprop
                $marketName = (Invoke-Adb @("-s", $serial, "shell", "getprop", "ro.product.marketname")).Output
                if (-not $marketName) {
                    $marketName = (Invoke-Adb @("-s", $serial, "shell", "getprop", "ro.product.model")).Output
                }
                
                $isRedmi = ($details -match "22071219AI") -or ($details -match "rock_in") -or ($marketName -match "Redmi")
                $isEmulator = ($serial -match "^emulator-") -or ($details -match "sdk_gphone") -or ($details -match "emulator") -or ($marketName -match "sdk_gphone")
                
                $devices += [PSCustomObject]@{
                    Serial = $serial
                    Details = $details
                    MarketName = $marketName
                    IsRedmi = $isRedmi
                    IsEmulator = $isEmulator
                }
            }
        }
    }
    return $devices
}

# 2. Query Connected Devices
$connectedDevices = @(Get-ConnectedDevicesList)

# If explicitly requested -Emulator or -LaunchEmulator, or if no devices found, attempt auto-launching emulator
if ((@($connectedDevices).Count -eq 0) -or ($Emulator -and (-not ($connectedDevices | Where-Object { $_.IsEmulator })))) {
    Write-Host "[*] Looking for Pixel 10 Pro Android Emulator..." -ForegroundColor Cyan
    $launchScript = Join-Path $PSScriptRoot "launch_emulator.ps1"
    if (Test-Path $launchScript) {
        Write-Host "[*] Launching emulator via launch_emulator.ps1..." -ForegroundColor Yellow
        $launchParams = @()
        if ($MemoryMb -gt 0) { $launchParams += @("-MemoryMb", $MemoryMb) }
        & powershell -NoProfile -ExecutionPolicy Bypass -File $launchScript @launchParams
        # Refresh device list
        $connectedDevices = @(Get-ConnectedDevicesList)
    }
}

if (@($connectedDevices).Count -eq 0) {
    Write-Host "[!] No active ADB devices found." -ForegroundColor Yellow
    exit 0
}

Write-Host "[+] Found $(@($connectedDevices).Count) active device(s):" -ForegroundColor Green
foreach ($d in $connectedDevices) {
    $tag = if ($d.IsRedmi) { " (TARGET: Redmi 11 Prime)" } elseif ($d.IsEmulator) { " (TARGET: Pixel 10 Pro Emulator)" } else { "" }
    Write-Host "    - $($d.Serial) [$($d.MarketName)]$tag" -ForegroundColor Cyan
}

# 3. Select Target Device
$target = $null
if ($TargetSerial) {
    $target = $connectedDevices | Where-Object { $_.Serial -eq $TargetSerial } | Select-Object -First 1
} elseif ($Emulator) {
    $target = $connectedDevices | Where-Object { $_.IsEmulator } | Select-Object -First 1
}

if (-not $target) {
    # Prioritize Redmi 11 Prime if available, then Emulator
    $target = $connectedDevices | Where-Object { $_.IsRedmi } | Select-Object -First 1
}

if (-not $target) {
    # Target Emulator if present
    $target = $connectedDevices | Where-Object { $_.IsEmulator } | Select-Object -First 1
}

if (-not $target) {
    # Fallback to first available device
    $target = $connectedDevices[0]
}

Write-Host "[*] Selected Target Device: $($target.Serial) ($($target.MarketName))" -ForegroundColor Magenta

# 4. Build Fresh APK via Gradle Assemble
$apkPath = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"

if ($SkipBuild -and (Test-Path $apkPath)) {
    Write-Host "[*] Skipping Gradle build (using existing APK: $apkPath)..." -ForegroundColor Yellow
} else {
    Write-Host "[*] Running Gradle assemble to build fresh APK (:app:assembleDebug)..." -ForegroundColor Yellow
    $gradlew = Join-Path $ProjectRoot "gradlew.bat"
    & $gradlew :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Gradle assemble failed."
        exit $LASTEXITCODE
    }
}

if (-not (Test-Path $apkPath)) {
    Write-Error "APK file not found at: $apkPath"
    exit 1
}

Write-Host "[+] Fresh APK Generated & Verified: $apkPath" -ForegroundColor Green

# 5. Wake screen and unlock keyguard before installation
Invoke-Adb @("-s", $target.Serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
Invoke-Adb @("-s", $target.Serial, "shell", "wm", "dismiss-keyguard") | Out-Null

# 6. Install APK to Target Device
Write-Host "[*] Installing APK to $($target.Serial)..." -ForegroundColor Yellow

$installed = $false

# On Redmi / MIUI devices, direct streamed install ('adb install') is blocked by OEM
# security prompts (INSTALL_FAILED_USER_RESTRICTED). The high-reliability zero-touch path
# is pushing to /data/local/tmp and installing via device package manager ('pm install').
if ($target.IsRedmi) {
    Write-Host "[*] Redmi/MIUI detected: using zero-touch push + pm install..." -ForegroundColor Cyan
    $remoteTmp = "/data/local/tmp/app-debug.apk"
    Invoke-Adb @("-s", $target.Serial, "push", $apkPath, $remoteTmp) | Out-Null
    
    # Attempt pm install with resilient retry for MIUI security prompt delays
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        $pmRes = Invoke-Adb @("-s", $target.Serial, "shell", "pm", "install", "-r", "-d", $remoteTmp)
        if ($pmRes.Output -match "Success") {
            Write-Host "[+] Installation successful via package manager!" -ForegroundColor Green
            $installed = $true
            break
        } elseif ($attempt -lt 2) {
            Start-Sleep -Seconds 1
        }
    }
    
    # Clean up temporary APK on device storage
    Invoke-Adb @("-s", $target.Serial, "shell", "rm", "-f", $remoteTmp) | Out-Null
    
    if (-not $installed) {
        Write-Host "[!] pm install failed: $($pmRes.Output)" -ForegroundColor Yellow
    }
}

if (-not $installed) {
    # Attempt direct install for emulator and non-Redmi devices, or as fallback
    Write-Host "[*] Attempting standard adb install..." -ForegroundColor Yellow
    $directRes = Invoke-Adb @("-s", $target.Serial, "install", "-r", "-t", "-d", $apkPath)
    if ($directRes.Output -match "Success") {
        Write-Host "[+] Installation successful!" -ForegroundColor Green
        $installed = $true
    } else {
        Write-Host "[!] Direct install failed: $($directRes.Output)" -ForegroundColor Yellow
        
        # Fallback to push + pm install if not already attempted
        if (-not $target.IsRedmi) {
            Write-Host "[*] Falling back to push + pm install..." -ForegroundColor Yellow
            $remoteTmp = "/data/local/tmp/app-debug.apk"
            Invoke-Adb @("-s", $target.Serial, "push", $apkPath, $remoteTmp) | Out-Null
            $pmFallbackRes = Invoke-Adb @("-s", $target.Serial, "shell", "pm", "install", "-r", "-t", "-d", $remoteTmp)
            Invoke-Adb @("-s", $target.Serial, "shell", "rm", "-f", $remoteTmp) | Out-Null
            if ($pmFallbackRes.Output -match "Success") {
                Write-Host "[+] Fallback pm install successful!" -ForegroundColor Green
                $installed = $true
            }
        }
    }
}

if (-not $installed) {
    Write-Error "Could not install APK to $($target.Serial)."
    exit 1
}

# 7. Launch MainActivity on Target Device
Write-Host "[*] Launching com.livetracker.controller/.MainActivity..." -ForegroundColor Yellow
$launchRes = Invoke-Adb @("-s", $target.Serial, "shell", "am", "start", "-n", "com.livetracker.controller/.MainActivity")
Write-Host "[+] Launch Output: $($launchRes.Output)" -ForegroundColor Green
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host " App deployed and running on $($target.MarketName) ($($target.Serial))!" -ForegroundColor Cyan
Write-Host "=====================================================" -ForegroundColor Cyan
