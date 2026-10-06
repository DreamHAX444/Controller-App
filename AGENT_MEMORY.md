# Controller App — Agent Memory

## Project Mission
The Controller App is being rebuilt from scratch as a secure, reliable, high-performance controller for the existing Tracker App.

## Current Status
Phase 0.1 - Security Foundation & 4-Digit PIN implemented.

## Current Development Phase
Phase 0 — Foundation + Security

Status:
IN PROGRESS (0.1 Security Foundation & 4-Digit PIN completed)

## Architecture Status
Controller architecture is being rebuilt from scratch.
The old Controller implementation is NOT the architectural source of truth.
The Tracker App is the integration target.

## Source of Truth
- GEMINI.md
- AGENT_MEMORY.md
- PRD
- TRD
- UI/UX Design Specification
- Tracker App contracts/interfaces

## Required Skills
- ponytail
- ui-ux-pro-max-skill

## Completed Work
- Environment inspection: verified
- Toolchain: JDK 25 (Android Studio bundled), Gradle Wrapper (9.1.0) via `tracker_app`
- Project skeleton creation: Kotlin DSL, Multi-module (app, core), Compose + Material 3, centralized versioning (`libs.versions.toml`).
- Security baseline: `network_security_config.xml`, `data_extraction_rules.xml` established.
- `.gitignore` configured for Android.
- Verified build and unit test execution (`assembleDebug` and `testDebugUnitTest` pass).
- Phase 0.1 (Security Foundation + PIN) implemented:
  - Added dependencies for AndroidX Security Crypto, Navigation Compose, Lifecycle ViewModel Compose, and Kotlinx Coroutines Test.
  - Implemented `SecureStorage` interface and `EncryptedPreferencesStorage` via `EncryptedSharedPreferences`.
  - Implemented `PinManager` for PBKDF2WithHmacSHA256 hashing and constant-time comparison, replacing Android `Base64` with Java standard library `java.util.Base64` for pure JVM test compatibility.
  - Developed `PinViewModel` maintaining the auth state machine (`Initializing`, `PinNotConfigured`, `SetupConfirm`, `Locked`, `Verifying`, `Authenticated`, `Error`).
  - Created `PinScreen.kt` using Compose Material 3 based on the UI/UX design spec with custom numeric keypad, visual dots, and error states.
  - Wrote and verified unit tests (`PinManagerTest`, `PinViewModelTest`) using fakes.

## Architecture Decisions
- Strict separation between `app` (presentation) and `core` (shared/domain) modules.
- Gradle version catalog used for standardized dependency management.
- `java.util.Base64` used over `android.util.Base64` in domain/core layers to ensure unit testability on the JVM without Robolectric.

## Tracker ↔ Controller Contracts
Communication is via existing control/signaling connection. Tracker app provides capabilities: Live Location, Camera, Microphone Audio, Device Audio, Screen Capture, and a newly added Remote File Access subsystem. File transfers use chunking and integrity verification.

## Known Issues
- `gradle` is not available in the global path.
- `ANDROID_HOME` is not set. Had to create `local.properties` manually with `sdk.dir=C\:\\Users\\ZNS\\AppData\\Local\\Android\\Sdk`.
- Android Lint is failing to execute because of a known compatibility issue between AGP's bundled lint tool and JDK 25.0.3. Linting was temporarily skipped during verification.
- Release Unit Tests fail because of minification / obfuscation out of the box. `testDebugUnitTest` is strictly used for testing instead.

## Failed Approaches
- Initial build failed because SDK was not found. Fixed by creating `local.properties`.
- Build failed on manifest icon references. Fixed by defaulting to standard android framework icon.
- Mocked Android framework dependencies (`Base64`) threw RuntimeExceptions during `PinManager` unit tests; resolved by swapping to `java.util.Base64`.

## Test Status
- `PinManagerTest` unit tests added and passing successfully.
- `PinViewModelTest` unit tests added and passing successfully.
- Execution completes without errors.

## Build Status
`assembleDebug` completes successfully. Tests pass (`testDebugUnitTest`).

## Pending Work
- Proceed to Phase 1 — Authentication + Authorization
- Inspect Tracker contracts before transport integration

## Important Rule
After every meaningful project change, update this file before reporting the task as complete.
