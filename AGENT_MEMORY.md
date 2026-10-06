# Controller App — Agent Memory

## Project Mission
The Controller App is being rebuilt from scratch as a secure, reliable, high-performance controller for the existing Tracker App.

## Current Status
Phase 0.0 - Foundation implemented.

## Current Development Phase
Phase 0 — Foundation + Security

Status:
IN PROGRESS (0.0 Foundation completed)

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

## Architecture Decisions
- Strict separation between `app` (presentation) and `core` (shared/domain) modules.
- Gradle version catalog used for standardized dependency management.

## Tracker ↔ Controller Contracts
Communication is via existing control/signaling connection. Tracker app provides capabilities: Live Location, Camera, Microphone Audio, Device Audio, Screen Capture, and a newly added Remote File Access subsystem. File transfers use chunking and integrity verification.

## Known Issues
- `gradle` is not available in the global path.
- `ANDROID_HOME` is not set. Had to create `local.properties` manually with `sdk.dir=C\:\\Users\\ZNS\\AppData\\Local\\Android\\Sdk`.
- Android Lint is failing to execute because of a known compatibility issue between AGP's bundled lint tool and JDK 25.0.3. Linting was temporarily skipped during verification.

## Failed Approaches
- Initial build failed because SDK was not found. Fixed by creating `local.properties`.
- Build failed on manifest icon references. Fixed by defaulting to standard android framework icon.

## Test Status
Basic foundation unit tests passed (no tests exist yet, but execution completes successfully).

## Build Status
`assembleDebug` completes successfully.

## Pending Work
- Proceed to Phase 0.1 / Security foundation
- Inspect Tracker contracts before transport integration

## Important Rule
After every meaningful project change, update this file before reporting the task as complete.
