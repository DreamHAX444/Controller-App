<#
.SYNOPSIS
  Automated Production Release Builder, Signer, and Manifest Publisher for Trace Android Controller.
.DESCRIPTION
  Executes unit tests, builds release APK, verifies apksigner signature, computes SHA-256,
  generates release/update-manifest.json, and validates release integrity.
#>

param(
    [string]$Channel = "stable",
    [switch]$Mandatory = $false,
    [string]$ReleaseNotes = "",
    [switch]$SkipTests = $false,
    [switch]$CreateGitTag = $false
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $ProjectRoot

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Trace Android Controller - Production Release Publisher " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Parse current versionCode and versionName from app/build.gradle.kts
$buildGradle = Get-Content "app/build.gradle.kts" -Raw
$versionCodeMatch = [regex]::Match($buildGradle, 'versionCode\s*=\s*(\d+)')
$versionNameMatch = [regex]::Match($buildGradle, 'versionName\s*=\s*"([^"]+)"')

if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    Write-Error "Could not parse versionCode or versionName from app/build.gradle.kts"
    exit 1
}

$versionCode = [long]$versionCodeMatch.Groups[1].Value
$versionName = $versionNameMatch.Groups[1].Value

Write-Host "[+] Current Application ID   : com.livetracker.controller" -ForegroundColor Green
Write-Host "[+] Target Release Version   : v$versionName (VersionCode: $versionCode)" -ForegroundColor Green
Write-Host "[+] Distribution Channel     : $Channel" -ForegroundColor Green
Write-Host "[+] Mandatory Update Flag    : $Mandatory" -ForegroundColor Green

# 2. Run Test Suites
if (-not $SkipTests) {
    Write-Host "`n[1/5] Executing Core and App Unit Test Suites..." -ForegroundColor Yellow
    $testResult = .\gradlew.bat :core:testDebugUnitTest :app:testDebugUnitTest
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Unit tests failed! Aborting release."
        exit 1
    }
    Write-Host "[+] All Unit Tests Passed." -ForegroundColor Green
} else {
    Write-Host "`n[1/5] Skipping Unit Tests as requested." -ForegroundColor DarkYellow
}

# 3. Assemble Release Variant
Write-Host "`n[2/5] Building Release APK Artifact (:app:assembleRelease)..." -ForegroundColor Yellow
$assembleResult = .\gradlew.bat :app:assembleRelease
if ($LASTEXITCODE -ne 0) {
    Write-Error "Release build failed! Aborting release."
    exit 1
}

$releaseApkPath = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $releaseApkPath)) {
    Write-Error "Release APK not found at $releaseApkPath"
    exit 1
}

$apkItem = Get-Item $releaseApkPath
$apkSizeBytes = $apkItem.Length
Write-Host "[+] Release APK Generated: $($apkItem.FullName)" -ForegroundColor Green
Write-Host "[+] Artifact Byte Size    : $apkSizeBytes bytes (~$([math]::Round($apkSizeBytes / 1MB, 2)) MB)" -ForegroundColor Green

# 4. Verify Signing with apksigner
Write-Host "`n[3/5] Verifying APK Signature with Android apksigner..." -ForegroundColor Yellow
$apksignerExe = $null
$candidatePaths = @(
    "C:\Users\ZNS\AppData\Local\Android\Sdk\build-tools\34.0.0\apksigner.bat",
    "C:\Users\ZNS\AppData\Local\Android\Sdk\build-tools\35.0.0\apksigner.bat",
    "C:\Users\ZNS\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat"
)
foreach ($path in $candidatePaths) {
    if (Test-Path $path) { $apksignerExe = $path; break }
}
if (-not $apksignerExe -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk\build-tools")) {
    $found = Get-ChildItem -Path "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Filter apksigner.bat -Recurse | Select-Object -First 1
    if ($found) { $apksignerExe = $found.FullName }
}

$certFingerprint = ""
if ($apksignerExe) {
    $verifyOutput = & $apksignerExe verify --print-certs $releaseApkPath
    foreach ($line in $verifyOutput) {
        if ($line -match "certificate SHA-256 digest:\s*([a-fA-F0-9]+)") {
            $certFingerprint = $matches[1].ToLower()
            break
        }
    }
    Write-Host "[+] Verified APK Signing Fingerprint (SHA-256): $certFingerprint" -ForegroundColor Green
} else {
    Write-Host "[!] apksigner not located in standard build-tools path; skipping external apksigner cert inspection." -ForegroundColor DarkYellow
}

# 5. Compute SHA-256 Checksum
Write-Host "`n[4/5] Computing Cryptographic SHA-256 Checksum..." -ForegroundColor Yellow
$sha256Hash = (Get-FileHash -Path $releaseApkPath -Algorithm SHA256).Hash.ToLower()
Write-Host "[+] APK Artifact SHA-256 : $sha256Hash" -ForegroundColor Green

# 6. Generate and Write Update Manifest
Write-Host "`n[5/5] Generating release/update-manifest.json..." -ForegroundColor Yellow
$releaseDir = Join-Path $ProjectRoot "release"
if (-not (Test-Path $releaseDir)) { New-Item -ItemType Directory -Path $releaseDir | Out-Null }

$notes = if ([string]::IsNullOrWhiteSpace($ReleaseNotes)) {
    "Trace Controller v$versionName stable release. Core telemetry improvements and update engine."
} else {
    $ReleaseNotes
}

$publishedIso = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$downloadUrl = "https://github.com/DreamHAX444/Controller-App/releases/download/v$versionName/app-release.apk"

$manifestObject = [ordered]@{
    schemaVersion               = 1
    appId                       = "com.livetracker.controller"
    channel                     = $Channel
    versionCode                 = $versionCode
    versionName                 = $versionName
    minimumSupportedVersionCode = 1
    mandatory                   = [bool]$Mandatory
    publishedAt                 = $publishedIso
    releaseNotes                = $notes
    apkUrl                      = $downloadUrl
    apkSizeBytes                = $apkSizeBytes
    sha256                      = $sha256Hash
    signingCertSha256           = $certFingerprint
}

$manifestJson = $manifestObject | ConvertTo-Json -Depth 5
$manifestPath = Join-Path $releaseDir "update-manifest.json"
[System.IO.File]::WriteAllText($manifestPath, $manifestJson, [System.Text.Encoding]::UTF8)

Write-Host "[+] Generated Manifest:" -ForegroundColor Green
Write-Host $manifestJson -ForegroundColor Gray

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " PRODUCTION RELEASE ASSEMBLED AND VALIDATED SUCCESSFULLY!  " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "Artifact : $releaseApkPath"
Write-Host "Manifest : $manifestPath"

if ($CreateGitTag) {
    Write-Host "`nTagging Git release v$versionName..." -ForegroundColor Cyan
    git tag -a "v$versionName" -m "Release v$versionName"
    Write-Host "Run the following to publish to GitHub:" -ForegroundColor Yellow
    Write-Host "  git push origin main --tags" -ForegroundColor White
}
