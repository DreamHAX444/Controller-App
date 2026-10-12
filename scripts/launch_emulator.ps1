# Launch script for Pixel 10 Pro Android Emulator with Interactive RAM Selection & Maximum Performance Configuration.
# Performance optimizations applied:
#   - Memory: User-selectable RAM with automatic smoothness recommendation (Default: 4096 MB)
#   - Host GPU: Direct GPU hardware acceleration utilizing host AMD Radeon GPU (-gpu host)
#   - Virtualization: Forced hardware virtualization via Windows Hypervisor Platform (-accel on)
#   - Multi-Core: 6 dedicated virtual CPU cores allocated (-cores 6)
#   - Fast Boot: Boot animation disabled (-no-boot-anim)
#   - Network: Full-speed unthrottled networking (-netdelay none -netspeed full)

param(
    [Parameter(Position = 0)]
    [string]$Memory = "",
    [int]$MemoryMb = 0,
    [string]$AvdName = "Pixel_10_Pro",
    [switch]$NoWait = $false,
    [switch]$ColdBoot = $false,
    [switch]$Foreground = $false,
    [int]$TimeoutSeconds = 120
)

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $ProjectRoot

Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host " Trace Controller - High Performance Emulator Launch" -ForegroundColor Cyan
Write-Host "=====================================================" -ForegroundColor Cyan

# Helper function to convert memory inputs (e.g. '4G', '4GB', '4', '4096') to integer MB
function Convert-ToMemoryMb {
    param([string]$inputVal)
    if (-not $inputVal) { return 0 }
    $trimmed = $inputVal.Trim()
    if ($trimmed -match '^(\d+)\s*G(B)?$') {
        return ([int]$matches[1] * 1024)
    }
    if ($trimmed -match '^(\d+)\s*M(B)?$') {
        return ([int]$matches[1])
    }
    if ($trimmed -match '^\d+$') {
        $num = [int]$trimmed
        if ($num -le 32) {
            # Likely entered GB like 2, 4, 6, 8
            return ($num * 1024)
        }
        return $num
    }
    return 0
}

# 1. Locate Emulator and ADB Binaries
$emuPath = $null
$adbPath = $null

$possibleEmuPaths = @(
    "C:\Users\ZNS\AppData\Local\Android\Sdk\emulator\emulator.exe",
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\emulator\emulator.exe")
)
$possibleAdbPaths = @(
    "C:\Users\ZNS\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)

# Check local.properties if available
$localProperties = Join-Path $ProjectRoot "local.properties"
if (Test-Path $localProperties) {
    Get-Content $localProperties | ForEach-Object {
        if ($_ -match "sdk\.dir=(.*)") {
            $rawDir = $matches[1].Replace("\\", "\").Replace("\:", ":")
            $possibleEmuPaths = @((Join-Path $rawDir "emulator\emulator.exe")) + $possibleEmuPaths
            $possibleAdbPaths = @((Join-Path $rawDir "platform-tools\adb.exe")) + $possibleAdbPaths
        }
    }
}

foreach ($p in $possibleEmuPaths) {
    if (Test-Path $p) {
        $emuPath = $p
        break
    }
}
if (-not $emuPath) {
    $cmd = Get-Command "emulator" -ErrorAction SilentlyContinue
    if ($cmd) { $emuPath = $cmd.Source }
}

foreach ($p in $possibleAdbPaths) {
    if (Test-Path $p) {
        $adbPath = $p
        break
    }
}
if (-not $adbPath) {
    $cmd = Get-Command "adb" -ErrorAction SilentlyContinue
    if ($cmd) { $adbPath = $cmd.Source }
}

if (-not $emuPath) {
    Write-Error "Could not locate emulator.exe. Please verify Android SDK is installed."
    exit 1
}

Write-Host "[+] Emulator Binary: $emuPath" -ForegroundColor Green
if ($adbPath) {
    Write-Host "[+] ADB Binary:      $adbPath" -ForegroundColor Green
}

# 2. Verify AVD exists
$installedAvds = & $emuPath -list-avds
if (-not ($installedAvds -contains $AvdName)) {
    Write-Host "[!] Warning: Requested AVD '$AvdName' was not directly found in:" -ForegroundColor Yellow
    $installedAvds | ForEach-Object { Write-Host "    - $_" -ForegroundColor Cyan }
    if ($installedAvds.Count -gt 0) {
        $AvdName = $installedAvds[0]
        Write-Host "[*] Falling back to available AVD: $AvdName" -ForegroundColor Magenta
    } else {
        Write-Error "No Android Virtual Devices (AVD) found on this machine."
        exit 1
    }
} else {
    Write-Host "[+] Target AVD:      $AvdName" -ForegroundColor Green
}

# 3. Check if an emulator process or ADB instance is already running
$runningEmuProcesses = Get-Process -Name "qemu-system-x86_64", "emulator" -ErrorAction SilentlyContinue
$runningEmulators = @()

if ($adbPath) {
    $devicesOutput = & $adbPath devices
    $devicesOutput -split "`r?`n" | ForEach-Object {
        $line = $_.Trim()
        if ($line -match "^(emulator-\d+)\s+(device|offline)") {
            $runningEmulators += $matches[1]
        }
    }
}

$alreadyRunning = ($null -ne $runningEmuProcesses -and @($runningEmuProcesses).Count -gt 0) -or (@($runningEmulators).Count -gt 0)

if ($alreadyRunning) {
    if ($runningEmuProcesses) {
        Write-Host "[+] Emulator process detected (PID: $(@($runningEmuProcesses)[0].Id))" -ForegroundColor Green
    }
    if (@($runningEmulators).Count -gt 0) {
        Write-Host "[+] Connected ADB emulator instance: $($runningEmulators -join ', ')" -ForegroundColor Green
        foreach ($emuSerial in $runningEmulators) {
            $rawBoot = & $adbPath -s $emuSerial shell getprop sys.boot_completed 2>$null
            $bootCompleted = if ($rawBoot) { "$rawBoot".Trim() } else { "" }
            if ($bootCompleted -eq "1") {
                Write-Host "[+] Device $emuSerial is already fully booted and ready!" -ForegroundColor Green
                & $adbPath -s $emuSerial shell input keyevent KEYCODE_WAKEUP 2>$null | Out-Null
                & $adbPath -s $emuSerial shell wm dismiss-keyguard 2>$null | Out-Null
                exit 0
            }
        }
    }

    Write-Host "[*] Emulator is already launching. Waiting for boot completion..." -ForegroundColor Yellow
} else {
    # 4. RAM Selection & Smoothness Recommendation
    if ($Memory -and ($MemoryMb -le 0)) {
        $MemoryMb = Convert-ToMemoryMb $Memory
    }

    if ($MemoryMb -le 0) {
        # Detect host physical RAM
        $hostTotalGb = 14
        try {
            $cs = Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue
            if ($cs -and $cs.TotalPhysicalMemory) {
                $hostTotalGb = [math]::Round($cs.TotalPhysicalMemory / 1GB, 1)
            }
        } catch { }

        $isPromptable = [Environment]::UserInteractive -and (-not [Console]::IsInputRedirected)

        if ($isPromptable) {
            Write-Host ""
            Write-Host "-----------------------------------------------------" -ForegroundColor Yellow
            Write-Host "          EMULATOR RAM ALLOCATION SETUP" -ForegroundColor Yellow
            Write-Host "-----------------------------------------------------" -ForegroundColor Yellow
            Write-Host "[i] Detected Host System RAM: $hostTotalGb GB" -ForegroundColor Cyan
            Write-Host ""
            Write-Host "Select how much RAM you want to allocate for the emulator:" -ForegroundColor White
            Write-Host "  [1] 2048 MB (2 GB) - Low / Minimum" -ForegroundColor Gray
            Write-Host "      Notice: Minimum requirement. 16KB OS preview may experience micro-stutter." -ForegroundColor DarkGray
            Write-Host "  [2] 3072 MB (3 GB) - Balanced" -ForegroundColor Gray
            Write-Host "      Good baseline with low host RAM impact." -ForegroundColor DarkGray
            Write-Host "  [3] 4096 MB (4 GB) - [RECOMMENDED FOR OPTIMAL 60 FPS SMOOTHNESS]" -ForegroundColor Green
            Write-Host "      Sweet spot: Silky 60 FPS Compose map rendering, fast tile caching," -ForegroundColor Green
            Write-Host "      seamless geofence dragging, leaving ample RAM for Android Studio." -ForegroundColor Green
            Write-Host "  [4] 6144 MB (6 GB) - High Performance" -ForegroundColor Cyan
            Write-Host "      High head-room for heavy multi-tasking, WebRTC video & tile prefetching." -ForegroundColor DarkCyan
            Write-Host "  [5] 8192 MB (8 GB) - Extreme Performance" -ForegroundColor Magenta
            Write-Host "      Maximum power. High host RAM consumption." -ForegroundColor DarkMagenta
            Write-Host "  [C] Custom MB entry (e.g. 5120)" -ForegroundColor White
            Write-Host ""
            Write-Host "Press ENTER for the Recommended option [4096 MB]: " -NoNewline -ForegroundColor Green

            $userInput = (Read-Host)
            if ($userInput) { $userInput = $userInput.Trim() }

            switch ($userInput) {
                "1" { $MemoryMb = 2048 }
                "2" { $MemoryMb = 3072 }
                "3" { $MemoryMb = 4096 }
                "4" { $MemoryMb = 6144 }
                "5" { $MemoryMb = 8192 }
                ""  { $MemoryMb = 4096 }
                default {
                    $parsed = Convert-ToMemoryMb $userInput
                    if ($parsed -gt 0) {
                        $MemoryMb = $parsed
                    } else {
                        Write-Host "[!] Unrecognized input '$userInput'. Defaulting to Recommended: 4096 MB." -ForegroundColor Yellow
                        $MemoryMb = 4096
                    }
                }
            }
        } else {
            # Non-interactive / script runner fallback: Default to recommended 4096 MB
            Write-Host "[*] Non-interactive mode: Using recommended 4096 MB RAM for optimal smoothness." -ForegroundColor Yellow
            $MemoryMb = 4096
        }
    }

    Write-Host ""
    Write-Host "[+] RAM Configured: $MemoryMb MB ($([math]::Round($MemoryMb / 1024, 1)) GB)" -ForegroundColor Green
    if ($MemoryMb -lt 3072) {
        Write-Host "    Recommendation: 2048 MB is the bare minimum. If you notice map frame spikes, bump to 4096 MB." -ForegroundColor Yellow
    } elseif ($MemoryMb -eq 4096) {
        Write-Host "    Recommendation: Optimal sweet spot selected! 60 FPS Compose rendering & tile cache active." -ForegroundColor Green
    } elseif ($MemoryMb -gt 4096) {
        Write-Host "    Recommendation: High allocation selected. Ample memory head-room for large tile caches." -ForegroundColor Cyan
    }

    # 5. Persist chosen RAM and compute optimal Dalvik heap in config.ini
    $avdDir = Join-Path $env:USERPROFILE ".android\avd\$AvdName.avd"
    $configIni = Join-Path $avdDir "config.ini"
    $hardwareQemuIni = Join-Path $avdDir "hardware-qemu.ini"

    # Compute Dalvik heap based on selected RAM
    $optimalHeap = 576
    if ($MemoryMb -ge 6144) {
        $optimalHeap = 768
    } elseif ($MemoryMb -lt 3072) {
        $optimalHeap = 256
    }

    if (Test-Path $configIni) {
        $content = Get-Content $configIni -Raw
        $content = $content -replace "hw\.ramSize=\d+", "hw.ramSize=$MemoryMb"
        $content = $content -replace "vm\.heapSize=\d+", "vm.heapSize=$optimalHeap"
        Set-Content -Path $configIni -Value $content -NoNewline
    }

    if (Test-Path $hardwareQemuIni) {
        $content = Get-Content $hardwareQemuIni -Raw
        $content = $content -replace "hw\.ramSize\s*=\s*\d+", "hw.ramSize = $MemoryMb"
        $content = $content -replace "vm\.heapSize\s*=\s*\d+", "vm.heapSize = $optimalHeap"
        Set-Content -Path $hardwareQemuIni -Value $content -NoNewline
    }

    # 6. Assemble Max-Performance Emulator Launch Arguments
    $emuArgs = @(
        "-avd", $AvdName,
        "-gpu", "host",
        "-accel", "on",
        "-cores", "6",
        "-memory", "$MemoryMb",
        "-no-boot-anim",
        "-netdelay", "none",
        "-netspeed", "full"
    )

    if ($ColdBoot) {
        $emuArgs += "-no-snapshot-load"
    }

    Write-Host "[*] Launching $AvdName with Maximum Performance Configuration:" -ForegroundColor Magenta
    Write-Host "    - RAM Allocated:     $MemoryMb MB ($([math]::Round($MemoryMb / 1024, 1)) GB)" -ForegroundColor Gray
    Write-Host "    - Dalvik Heap:       $optimalHeap MB" -ForegroundColor Gray
    Write-Host "    - GPU Mode:          Host Driver (Hardware Accelerated)" -ForegroundColor Gray
    Write-Host "    - Acceleration:      WHPX (Forced On)" -ForegroundColor Gray
    Write-Host "    - CPU Cores:         6 Dedicated Virtual Cores" -ForegroundColor Gray
    Write-Host "    - Boot Animation:    Disabled (Fast Startup)" -ForegroundColor Gray
    Write-Host "    - Network:           Unthrottled (Zero Delay, Full Speed)" -ForegroundColor Gray

    # 7. Launch Emulator Process
    if ($Foreground) {
        Write-Host "[*] Running emulator in foreground process mode..." -ForegroundColor Cyan
        & $emuPath @emuArgs
        exit $LASTEXITCODE
    } else {
        $emuProcess = Start-Process -FilePath $emuPath -ArgumentList $emuArgs -PassThru

        if (-not $emuProcess -or $emuProcess.HasExited) {
            Write-Error "Failed to start the emulator process."
            exit 1
        }

        Write-Host "[+] Emulator process launched (PID: $($emuProcess.Id))" -ForegroundColor Green
    }
}

if ($NoWait -or (-not $adbPath)) {
    Write-Host "[+] Exiting without waiting for boot completion." -ForegroundColor Green
    exit 0
}

# 8. Wait for Emulator to Register and Complete Boot
Write-Host "[*] Waiting for emulator to register on ADB and complete boot (Timeout: ${TimeoutSeconds}s)..." -ForegroundColor Yellow
$elapsed = 0
$targetSerial = $null
$isBooted = $false

while ($elapsed -lt $TimeoutSeconds) {
    Start-Sleep -Seconds 2
    $elapsed += 2

    # Check for attached emulator
    $adbDevices = & $adbPath devices
    foreach ($line in ($adbDevices -split "`r?`n")) {
        $tLine = $line.Trim()
        if ($tLine -match "^(emulator-\d+)\s+(device|offline)") {
            $targetSerial = $matches[1]
            break
        }
    }

    if ($targetSerial) {
        $rawState = & $adbPath -s $targetSerial get-state 2>$null
        $state = if ($rawState) { "$rawState".Trim() } else { "" }
        if ($state -eq "device") {
            $rawBoot = & $adbPath -s $targetSerial shell getprop sys.boot_completed 2>$null
            $boot = if ($rawBoot) { "$rawBoot".Trim() } else { "" }
            if ($boot -eq "1") {
                $isBooted = $true
                break
            }
        }
    }

    Write-Host -NoNewline "."
}
Write-Host ""

if ($isBooted) {
    Write-Host "[+] Emulator ($targetSerial) booted successfully in ${elapsed}s!" -ForegroundColor Green
    # Dismiss lockscreen and wake up display
    & $adbPath -s $targetSerial shell input keyevent KEYCODE_WAKEUP 2>$null | Out-Null
    & $adbPath -s $targetSerial shell wm dismiss-keyguard 2>$null | Out-Null
    Write-Host "[+] Screen awakened and keyguard dismissed. Ready for deployment!" -ForegroundColor Green
} else {
    Write-Host "[!] Emulator process is running, but boot completion was not confirmed within ${TimeoutSeconds}s." -ForegroundColor Yellow
    Write-Host "    It will continue booting in the background." -ForegroundColor Yellow
}
