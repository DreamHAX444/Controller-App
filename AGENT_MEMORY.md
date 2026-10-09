# Controller App — Agent Memory

## Project Mission
The Controller App is being rebuilt from scratch as a secure, reliable, high-performance controller for the existing Tracker App.

## Current Status
Phase 0.1 - Security Foundation & 4-Digit PIN implemented. Forensic Audit completed and fixes applied.

## Current Development Phase
Phase 4.0 — Device Management & Diagnostics Foundation

Status:
COMPLETE (Built `TrackerDevice`, `DeviceHealthClassifier`, `InMemoryTrackerDeviceRepository`, `DefaultDeviceManager`, `DefaultTrackerDiagnosticsProvider`, `DiagnosticHistoryBuffer`, and `DefaultDeviceSelectionManager`. Verified robust test coverage for domain layers and data repository locking mechanisms).

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
- Phase 1.0 (Auth Domain Foundation) implemented:
  - Created secure domain models: `User`, `Role`, `Session`, `Action`, `DevicePermission`, `ServicePermission`.
  - Implemented `AuthorizationEngine` and `DefaultAuthorizationEngine` with strict deny-by-default logic and role/device/service precedence.
  - Established `SecureCommand` and `CommandExecutor` architectural boundaries.
  - Evaluated permission logic to ensure correctness.
  - Verified logic with exhaustive unit tests (`AuthorizationEngineTest`).
- Phase 1.1 (Auth Data & Repository Foundation) implemented:
  - Added `AccountStatus` (`ACTIVE`, `DISABLED`, `SUSPENDED`, `UNKNOWN`) and integrated into `User`.
  - Enforced `AccountNotActive` state in `AuthorizationEngine`.
  - Created `UserRepository`, `SessionRepository`, and `AuthenticationRepository` interfaces to abstract backend networking details.
  - Designed `AuthenticationResult` sealed class for deterministic auth outcomes without leaking backend specifics.
  - Created strict `CredentialStore` interface to enforce secure Keystore-backed handling of tokens, preventing the domain layer from processing raw tokens/passwords.
  - Created fake repository implementations for tests (`FakeUserRepository`, `FakeSessionRepository`, `FakeAuthenticationRepository`).
  - Auth Integration testing passed (`AuthenticationIntegrationTest`).
- Branding Update implemented:
  - App name changed to `Trace` in user-facing elements.
  - User requested a single, static app icon. The dynamic icon switching system was removed.
  - User provided an edited base icon with a custom pin point.
  - Successfully removed semi-transparent artifacts and background from the provided icon, leaving a perfectly clean transparent edge.
  - Generated and integrated standard PNG mipmaps (`mdpi` through `xxxhdpi`) into the project structure.
  - Set `android:icon` and `android:roundIcon` in `AndroidManifest.xml` to point to the new single icon.
- Phase 1.2 (Authentication & Session Runtime Layer) implemented:
  - Created domain use cases: `AuthenticateUserUseCase`, `LogoutUserUseCase`, `ValidateSessionUseCase`, `RestoreSessionUseCase`.
  - Created Compose-friendly UI state representation: `AuthenticationState`.
  - Refined `RestoreSessionUseCase` to seamlessly try authenticating with an existing secure token.
  - Wrote exhaustive unit tests in `AuthUseCasesTest` covering success, invalid credentials, suspended/disabled accounts, expiration, and session restoration boundaries.
  - Successfully compiled tests and ran them successfully.
- Phase 1.3 (Authentication UI Integration) implemented:
  - Created `AuthenticationViewModel` to manage auth lifecycle states.
  - Implemented UI components: `LoginScreen`, `StatusScreens` (Expired/Disabled/Suspended), `LoadingScreen`, and `MainShellScreen`.
  - Integrated PIN screen gate into `MainActivity` using `AuthenticationViewModel`.
  - Created `StubAuthenticationRepository`, `StubCredentialStore`, and `StubSessionRepository` to support auth flow testing in the absence of networking.
  - Updated `MainActivity` to orchestrate auth state transitions.
  - Added `AuthenticationViewModelTest` to cover core startup and auth transitions.
  - Fixed integration bugs between UseCases and stubs, all tests now pass successfully.
- Phase 1.4 (TrackMate App Navigation & Shell Foundation) implemented:
  - Created `Destination.kt` with a sealed class `Destination` defining primary, service, admin, and system destinations.
  - Implemented `PlaceholderScreen.kt` for destinations that are not yet implemented.
  - Created `TrackMateNavHost.kt` utilizing `androidx.navigation.compose.NavHost` mapping all destinations to their respective placeholder screens.
  - Created `MainShellScreen.kt` which orchestrates `TrackMateNavHost`, `ModalNavigationDrawer`, and `NavigationBar` using Material 3 components.
  - Tested navigation structure successfully.
- Phase 2.0 (Tracker Connectivity Architecture & Contracts) implemented:
  - Created domain contracts: `TrackerId`, `ConnectionState`, `TrackerCapabilities`, `HeartbeatState`, `SessionError`, `CommandResult`.
  - Defined `TrackerEvent` and `TrackerCommand` as sealed interfaces with strongly typed data classes, eliminating `Map<String, Any>`.
  - Established `TrackerTransport` interface to abstract underlying signaling and WebRTC layers.
  - Implemented `DefaultTrackerSessionManager` managing device isolation, connection state transitions, and event observation using `ConcurrentHashMap` and Coroutines.
  - Wrote comprehensive unit tests using `FakeTrackerTransport` to verify isolation, invalid transition rejection, and correct coroutine behavior.
  - All tests pass (`TrackerSessionManagerTest`).
- Phase 2.1 (Multi-Device Tracker Session Manager) implemented:
  - Updated `TrackerSessionManager` interface to strictly define `unregisterTracker`, `hasSession`, `getAllSessions`, and `clearSessions`.
  - Implemented thread-safe session registry leveraging `ConcurrentHashMap`.
  - Ensured strict session isolation where state changes, capabilities, and errors in one Tracker do not bleed into others.
  - Implemented transition validation logic to ignore invalid connection state jumps (e.g. `DISCONNECTED` to `CONNECTED`).
  - Added strict connection gating in `sendCommand` rejecting commands for disconnected devices (`SESSION_NOT_CONNECTED`).
  - Prevented zombie state updates by cancelling coroutines and dropping transport listeners immediately upon `unregisterTracker`.
  - Rewrote and expanded `TrackerSessionManagerTest` to cover all 28 requirements spanning Registry, Isolation, Commands, Events, Lifecycle, and Concurrency. All tests pass successfully.
- Phase 2.2 (Tracker Heartbeat & Connection Health) implemented:
  - Created `HeartbeatMonitor` to run isolated asynchronous health checks for each registered tracker.
  - Used `TestTimeSource` abstracting system time for reproducible, exact-time tests in unit tests.
  - Implemented `HeartbeatHealth` state transitions correctly handling multiple concurrent trackers (DEGRADED, LOST, HEALTHY).
  - Designed and executed 20 rigorous test cases in `TrackerSessionManagerHeartbeatTest.kt` verifying timeout behavior, race conditions, edge conditions for revival, and timing nuances related to exact delays on the Coroutine Test Dispatcher.
- Phase 2.3 (Signaling Foundation) implemented:
  - Mapped protocol messages based on Tracker App's existing WebRTC signaling messages (`SESSION_ENDED`, `OFFER`, `ANSWER`, `READY`).
  - Created `SignalingMessage`, `SignalingState`, and `SignalingTransport` abstractions to handle protocol specifics.
  - Implemented `DefaultSignalingManager` acting as an isolated session registry (`ConcurrentHashMap`).
  - Implemented `DefaultSignalingSession` state machine (`DISCONNECTED` -> `CONNECTING` -> `CONNECTED` -> `NEGOTIATING` -> `READY`).
  - Wrote robust tests that uncovered an issue with standard Coroutine `CancellationException` being caught during shutdown sequences; fixed it via proper rethrowing.
  - Test coverage completed for Manager and Session states. All tests pass successfully.
- Phase 2.4 (WebRTC Session Foundation) implemented:
  - Defined Controller-side WebRTC domain abstractions in `core` (`WebRtcSession`, `WebRtcSessionFactory`, `WebRtcSessionManager`, `WebRtcConfiguration`, `WebRtcState`).
  - Created `WebRtcSessionOrchestrator` in `core` to coordinate SDPs and ICE candidates between `SignalingSession` and `WebRtcSession`.
  - Evaluated `tracker_app` compatibility and confirmed identical WebRTC signaling and data channel patterns.
  - Imported `io.getstream:stream-webrtc-android:1.1.1` in the `app` module for infrastructure WebRTC support, matching `tracker_app`.
  - Implemented concrete `AndroidWebRtcSession` and `AndroidWebRtcSessionFactory` in `app` (infrastructure layer).
- Phase 2.5 (WebRTC Data Channel & Command/Event Transport) implemented:
  - Designed `DataChannelTransport` to abstract `TrackerDataChannel` interactions (sending JSON-encoded commands and emitting parsed `TrackerEvent`s).
  - Developed `DefaultDataChannelTransport` utilizing Kotlinx Serialization (`Json`) to encode commands and decode event payloads securely.
  - Implemented command-response correlation using a `MutableMap` of `CompletableDeferred` mapped by `commandId` and guarded by a `Mutex`.
  - Used Kotlin Coroutines `withTimeout` to gracefully handle command timeouts, emitting `CommandResult.Timeout`.
- Phase 2.6 (End-to-End Tracker Session Transport Integration) implemented:
  - Wired `DefaultDataChannelTransport` into `DefaultTrackerTransport`, launching observation loops to collect incoming WebRTC state changes and start the data channel once `CONNECTED`.
  - Fixed test flakiness related to Coroutine Testing dispatchers (`UnconfinedTestDispatcher`) in `DefaultTrackerTransportTest`.
  - Handled DataChannel creation sequence where the Tracker is the initiator, dropping unexpected data channels except for `"commands"`.
  - Fixed `DISCONNECTED` state emission from `SignalingSession.state` colliding with `CONNECTING` phase logic.
  - Confirmed all tests for the session layer pass.
- Phase 3.0 (Tracker Connection Reliability & Recovery Architecture) implemented:
  - Designed `ReconnectPolicy` and `TrackerRecoveryManager` with exponential backoff logic and generation-based transport isolation.
  - Integrated `NetworkStateProvider` to suspend recovery attempts while network is unavailable.
  - Refactored `DefaultTrackerSessionManager` to utilize `TrackerRecoveryManager` instead of managing reconnections manually.
  - Corrected test cases to properly simulate concurrent transport suspensions with `kotlinx.coroutines.yield()` and test scheduler timeline explicit advancing via `testScope.advanceTimeBy()` in place of `advanceUntilIdle()`.
  - 43/43 recovery isolation and reconnection tests passed.
- Phase 4.0 (Device Management & Diagnostics Foundation) implemented:
  - Established `TrackerDevice` and `DeviceHealth` domain models to track connections, batteries, and general health statuses for trackers.
  - Built `DeviceHealthClassifier` mapping low-level transport details (`ConnectionState` and `HeartbeatHealth`) to aggregated high-level user-facing status.
  - Implemented thread-safe `InMemoryTrackerDeviceRepository` using `Mutex` to manage the list of registered devices efficiently.
  - Developed `DefaultTrackerDiagnosticsProvider` and `DiagnosticHistoryBuffer` merging runtime session attributes and stored lifecycle events with limits to provide comprehensive insights.
  - Implemented `DeviceSelectionManager` and `DefaultDeviceSelectionManager` establishing the foundation for active device UI focus.
  - Tested all components thoroughly; unit tests pass in `testDebugUnitTest` variant.
- Updated `.gitignore` to properly ignore sub-module build directories (`build/` instead of `/build`).
- Performed `ponytail-audit`: deleted `convert.py`, `generate_icons.py`, `ControllerApplication.kt`, and untracked `app/build/` and `core/build/` from git.

## Phase 0.1 Forensic Audit Results
- **Authentication Gate:** Fixed. The original implementation bypassed `PinScreen` entirely. `MainActivity` is now properly wired to display `PinScreen` and gate access to the protected content (`Greeting`).
- **PIN Storage:** Passed. Uses `EncryptedSharedPreferences` utilizing Android Keystore-backed keys (`AES256_GCM`).
- **Cryptography / Brute-force Resistance:** Limitation/Fixed. PBKDF2WithHmacSHA256 was used, but iterations were low (10,000). Iterations were bumped to 100,000 for better resistance. However, a 4-digit PIN (10,000 combinations) is still a known security limitation if an attacker gains root access and extracts the Keystore keys or bypasses TEE protections, as offline brute force remains computationally feasible.
- **Lockout:** Limitation. 5-attempt limit and 30s lockout implemented. Currently relies on `System.currentTimeMillis()`, which can be bypassed by changing the device time or clearing app data. Acknowledged as an inherent Android limitation without a trusted execution environment timer or server-side state.
- **Lifecycle Security:** Passed. StateFlow retained during recomposition.
- **UI Security:** Passed. Clean 375dp layout, no cleartext PINs shown/logged.
- **Tests & Build:** Passed. All unit tests (`testDebugUnitTest`) pass.

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

## Test & Build Status
`assembleDebug` completes successfully. Tests pass (`testDebugUnitTest`).

## Pending Work
- Proceed to Phase 5.0 (Map + Live Location) or the next required Phase step.

## Important Rule
After every meaningful project change, update this file before reporting the task as complete.
