# Trace Android Controller: Production-Grade Online Self-Update System

## 1. Architectural Overview

The Trace Android Controller update system provides an automated, secure, and resilient mechanism for sideloaded/independently distributed Android client devices to discover, download, verify, and install application updates directly from online release infrastructure (GitHub Releases CDN).

```
                      +---------------------------------------+
                      |         GitHub Releases / CDN         |
                      |  - update-manifest.json               |
                      |  - trace-controller-vX.Y-release.apk  |
                      +-------------------+-------------------+
                                          |
                                    HTTPS | (ETag / Bound Buffers)
                                          v
+-----------------------------------------------------------------------------------+
| TRACE ANDROID CONTROLLER CLIENT                                                   |
|                                                                                   |
|  +-------------------------+         +-----------------------------------------+  |
|  |   StartupOrchestrator   | ------> |    Update Discovery (Non-blocking)     |  |
|  |  (App Ready State)      |  +2sec  |    CheckForUpdateUseCase                |  |
|  +-------------------------+         +--------------------+--------------------+  |
|                                                           |                       |
|                                                           v                       |
|                                      +-----------------------------------------+  |
|                                      |        DefaultUpdateRepository          |  |
|                                      |  - Mutex thread-safety                  |  |
|                                      |  - Channel & AppId validation           |  |
|                                      |  - Version monotonicity check           |  |
|                                      +--------------------+--------------------+  |
|                                                           |                       |
|                   +---------------------------------------+--------------------+  |
|                   |                                                            |  |
|                   v                                                            v  |
|  +---------------------------------+                  +------------------------+--+
|  |       HttpUpdateDownloader      |                  |   UpdatePromptDialog   |  |
|  |  - Resilient chunked streaming  |                  |   & UpdatesScreen      |  |
|  |  - .part staging & atomic rename|                  |  - Progress bar        |  |
|  |  - Storage quota verification   |                  |  - Release notes       |  |
|  +----------------+----------------+                  |  - Install trigger     |  |
|                   |                                   +------------------------+  |
|                   v                                                               |
|  +---------------------------------+                                              |
|  |       DefaultUpdateVerifier     |                                              |
|  |  - Constant-time SHA-256 match  |                                              |
|  |  - Signing Cert SHA-256 match   |                                              |
|  |  - Package name & versionCode   |                                              |
|  +----------------+----------------+                                              |
|                   |                                                               |
|                   v                                                               |
|  +---------------------------------+                                              |
|  |     AndroidPackageInstaller     |                                              |
|  |  - PackageInstaller.Session API |                                              |
|  |  - Resilient FileProvider fallb.|                                              |
|  |  - Settings permission redirect |                                              |
|  +----------------+----------------+                                              |
|                   |                                                               |
+-------------------|---------------------------------------------------------------+
                    v
    +-------------------------------+
    |  Android OS Package Manager   |
    |  - System Confirmation Dialog |
    |  - Atomic In-Place Upgrade    |
    +-------------------------------+
```

---

## 2. Layer Architecture and Module Responsibilities

### Core Domain & Data Layer (`:core`)
- **`UpdateManifest`**: Strongly-typed, versioned (schema v1) JSON specification modeling update metadata, target application identity, channel, minimum supported versions, SHA-256 digests, and cryptographic signing certificate fingerprints.
- **`UpdateState`**: Sealed class hierarchy capturing explicit, non-overlapping lifecycle states (`Idle`, `Checking`, `UpdateAvailable`, `Downloading`, `Verifying`, `ReadyToInstall`, `AwaitingSystemConfirmation`, `Installing`, `Installed`, `Cancelled`, `UpToDate`, `Failed`).
- **`UpdateProvider`**: Network client enforcing strict HTTPS, 1 MB response size bounds, standard timeouts (15s connect, 30s read), and robust HTTP status / network exception translation.
- **`UpdateDownloader`**: Stream-based downloader that checks storage availability before downloading, stages writes to `*.part` temporary files, enforces streaming buffer reads (16 KB chunks), computes accurate percentage telemetry, and performs atomic rename upon completion.
- **`UpdateVerifier`**: Cryptographic validation engine that enforces:
  1. Exact file size matching.
  2. Constant-time SHA-256 digest calculation (`MessageDigest.isEqual`).
  3. Package archive parsing via `ApkPackageInspector` ensuring package identity matches `com.livetracker.controller`.
  4. Monotonic version incrementation (`versionCode > currentVersionCode`).
  5. Certificate fingerprint verification comparing signing certificate SHA-256 against trusted release digests.
- **`UpdateInstaller`**: Dual-strategy package installer implementing Android 8.0+ `PackageInstaller.Session` streaming commits with automatic `STATUS_PENDING_USER_ACTION` handoff, plus an Android `FileProvider` fallback intent launcher.
- **`PackageInstallerStatusBus` & `PackageInstallerStatusReceiver`**: Manifest-registered broadcast receiver and coroutine event bus decoupling asynchronous OS package manager status broadcasts from UI lifecycles.
- **`UpdatePreferencesRepository`**: Persistent SharedPreferences storage retaining last check timestamps, release channel configurations (`stable`, `beta`), and dismissed update versions.

### Application Presentation & Lifecycle (`:app`)
- **`UpdatesViewModel`**: Unidirectional data flow (UDF) coordinator combining update state, persistence preferences, and package installation permissions into `UpdatesUiState`.
- **`UpdatesScreen`**: Full Material 3 screen located at `Destination.Updates`, providing current version status, update checks, changelog presentation, download progress meters, unknown sources permission guidance, and retry actions.
- **`UpdatePromptDialog`**: Non-disruptive Material 3 dialog rendered after PIN authentication when a new release is detected, supporting optional update dismissal and mandatory update lockouts.
- **`StartupOrchestrator` Integration**: Updates are registered as a discreet non-blocking preparation step during startup. The initial network check is delayed 2 seconds until the application reaches `StartupState.Ready`, guaranteeing that critical map rendering and tracker telemetry startup paths are never delayed.

---

## 3. Online Distribution & Manifest Specification

### Manifest URL
```
https://raw.githubusercontent.com/DreamHAX444/Controller-App/main/release/update-manifest.json
```

### JSON Schema (v1)
```json
{
  "schemaVersion": 1,
  "appId": "com.livetracker.controller",
  "channel": "stable",
  "versionCode": 1,
  "versionName": "1.0.0",
  "minimumSupportedVersionCode": 1,
  "mandatory": false,
  "publishedAt": "2026-10-12T14:01:30Z",
  "releaseNotes": "Trace Controller v1.0.0 stable release. Core telemetry improvements and update engine.",
  "apkUrl": "https://github.com/DreamHAX444/Controller-App/releases/download/v1.0.0/app-release.apk",
  "apkSizeBytes": 44086985,
  "sha256": "ff63144cdec0eda51d44514b942256dec587608854f83ce52a746b6e125b4a30",
  "signingCertSha256": "b1c5128cd81bd989c0dd05d5cd5eb8f332b7283428e28dfe81100d5a0ba606c2"
}
```

### Field Definitions
| Field | Type | Description |
| :--- | :--- | :--- |
| `schemaVersion` | Integer | Manifest specification version (must be `1`). |
| `appId` | String | Must strictly match installed package `com.livetracker.controller`. |
| `channel` | String | Release channel (`stable`, `beta`). Checked against client preference. |
| `versionCode` | Long | Monotonically increasing version integer. Must be `> currentVersionCode`. |
| `versionName` | String | Human-readable version string (e.g. `1.1`). |
| `minimumSupportedVersionCode` | Long | Earliest version permitted to continue running. If current `< min`, update is mandatory. |
| `mandatory` | Boolean | When `true`, prevents dialog dismissal and enforces immediate update. |
| `publishedAt` | String | ISO 8601 release publication timestamp. |
| `releaseNotes` | String | Multi-line release changelog shown to users before download. |
| `apkUrl` | String | Strict HTTPS direct download link to release APK. |
| `apkSizeBytes` | Long | Exact size of the APK in bytes. |
| `sha256` | String | 64-character lowercase hex SHA-256 cryptographic digest of the APK. |
| `signingCertSha256` | String | 64-character lowercase hex SHA-256 digest of the signing certificate. |

---

## 4. Cryptographic Security & Safety Guarantees

1. **HTTPS Enforcement**: Manifest queries and APK downloads reject insecure HTTP endpoints.
2. **Digest Verification**: Downloaded APKs are verified using constant-time `MessageDigest.isEqual()` against the manifest SHA-256. Corrupted or modified downloads are immediately deleted from disk.
3. **Certificate Fingerprint Pinning**: The client verifies that the downloaded APK's signing certificate digest matches the expected release key fingerprint before initiating package installation.
4. **Downgrade Rejection**: The client strictly rejects any manifest or APK whose `versionCode` is less than or equal to the currently installed version.
5. **No Blind Uninstalls**: The system never uninstalls the current application, completely preventing database and offline map data loss.
6. **Isolated Staging**: Downloads are staged in the application's internal cache directory (`context.cacheDir/updates/`) with `0600` file permissions, preventing other apps from accessing or tampering with the payload during download.

---

## 5. Native Android Package Installation Flow

Android 8.0 (API 26) through Android 15 (API 35) enforce explicit permissions for installing unknown apps:
1. **Permission Check**: The app inspects `PackageManager.canRequestPackageInstalls()`.
2. **Settings Redirection**: If permission has not been granted, the app provides an inline banner and deep-links directly to `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` targeting `package:com.livetracker.controller`.
3. **Installation Initiation**:
   - Primary: Uses `PackageInstaller.createSession()`, streams the APK into the session, and calls `session.commit(statusReceiverPendingIntent)`.
   - Secondary: Falls back to `Intent(Intent.ACTION_VIEW)` with `FileProvider` URI and `FLAG_GRANT_READ_URI_PERMISSION`.
4. **System Confirmation**: Android displays the standard system confirmation dialog asking the user to update the app.
5. **Result Handling**: `PackageInstallerStatusReceiver` captures OS install results and broadcasts them back to `PackageInstallerStatusBus`.

---

## 6. Automated Release Pipeline

### GitHub Actions Workflow (`.github/workflows/release.yml`)
1. Checks out repository and configures JDK 17.
2. Runs unit test suites (`:core:testDebugUnitTest`, `:app:testDebugUnitTest`).
3. Builds production release APK (`:app:assembleRelease`).
4. Signs APK using release keystore secrets configured in repository settings.
5. Validates APK signature using Android SDK `apksigner verify`.
6. Computes exact APK file size, SHA-256 hash, and signing certificate fingerprint.
7. Automatically generates and commits `release/update-manifest.json`.
8. Creates a tagged GitHub Release with the APK artifact attached.

### Local PowerShell Release Script (`scripts/publish_release.ps1`)
```powershell
.\scripts\publish_release.ps1 -Channel "stable" -ReleaseNotes "Trace Controller v1.0.0 stable release." -CreateGitTag
```
This script:
1. Parses `versionCode` and `versionName` directly from `app/build.gradle.kts`.
2. Runs unit tests across all modules and builds `:app:assembleRelease`.
3. Computes SHA-256 checksum and exact byte size.
4. Extracts signing certificate SHA-256 fingerprint using `apksigner`.
5. Updates `release/update-manifest.json`.
6. Generates a release report.

---

## 7. Rollback & Troubleshooting Runbook

### Rollback Strategy
If a release introduces a regression:
1. Android OS prevents automatic downgrading without user data wiping.
2. To roll back, create a new release with an incremented `versionCode` (e.g. `versionCode = 3`) containing the previous stable codebase.
3. Mark `mandatory: true` in `release/update-manifest.json` if the regression is critical.
4. Clients will immediately identify version 3 as an update and prompt users to upgrade.

### Common Issues & Remedies
- **Checksum Mismatch (`CHECKSUM_MISMATCH`)**: Verify that the uploaded APK was not modified after manifest generation. Re-run `scripts/publish_release.ps1` to recalculate hashes.
- **Permission Denied (`INSTALL_PERMISSION_DENIED`)**: Direct user to Settings > Apps > Special App Access > Install unknown apps > Trace Controller.
- **Signature Mismatch (`SIGNATURE_VERIFICATION_FAILED`)**: Verify that the APK was signed with the identical release keystore as the currently installed build. Android will reject updates with different keys.
