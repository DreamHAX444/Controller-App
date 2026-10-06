# Controller App — AI Development Rules

## 1. Project Mission

This project is a complete rebuild of the Live Tracker Controller App from scratch.

The Controller must be:
- Modular
- Secure
- Permission-aware
- Event-driven
- State-driven
- Session-isolated
- Failure-resilient
- Performance-conscious
- Mobile-first
- Production-oriented

The PRD, TRD, and UI/UX Design Specification are the source of truth.

Do not inherit architectural debt from the previous Controller implementation.

---

## 2. Mandatory Skills

Two skills are mandatory for this project:

1. `ponytail`
2. `ui-ux-pro-max-skill`

### Ponytail

Use the `ponytail` skill continuously for normal development work.

Use it for:
- Planning
- Repository/project inspection
- Architecture decisions
- Code implementation
- Refactoring
- Debugging
- Testing
- Verification
- Build analysis
- Dependency decisions
- General engineering tasks

Do not bypass the skill when it is relevant.

### UI/UX Pro Max

Use `ui-ux-pro-max-skill` whenever the task involves UI/UX.

This includes:
- Screen design
- Layout
- Components
- Design system
- Colors
- Typography
- Spacing
- Navigation
- Interaction design
- Animations
- Accessibility
- Responsive behavior
- Compose UI
- UI states
- Visual polish
- UI review/audit

Before implementing or significantly changing UI, consult and use the UI/UX skill.

Do not create UI based only on personal assumptions when the UI/UX skill can provide guidance.

---

## 3. Skill Usage Rule

For every task:

1. Determine whether the task is general engineering or UI/UX-related.
2. Use `ponytail` for general engineering work.
3. If UI/UX is involved, also use `ui-ux-pro-max-skill`.
4. If a task contains both engineering and UI work, use both.
5. Do not replace the approved skills with improvised processes when the skills are available.

---

## 4. Source of Truth

Before implementation, inspect the available project documentation.

Priority:

1. PRD
2. TRD
3. UI/UX Design Specification
4. Existing Tracker App compatibility/contracts
5. Existing source code only when needed for integration knowledge

The old Controller implementation is NOT an architectural source of truth.

If a requirement conflicts with an older implementation, follow the current PRD/TRD/design specification unless the conflict is explicitly reported.

---

## 5. No Premature Coding

Do not immediately start coding a large feature.

For significant tasks:

1. Inspect relevant files.
2. Inspect the applicable specification.
3. Identify dependencies and contracts.
4. Produce a concise implementation plan.
5. Implement the smallest coherent unit.
6. Test it.
7. Review lifecycle/resource handling.
8. Verify the build.
9. Report what changed and what remains.

---

## 6. Architecture Rules

The application must remain modular.

Use clear boundaries between:

- Presentation
- Features
- Domain
- Data
- Realtime state
- Transport
- Session management
- Security
- Authentication
- Authorization
- Diagnostics

UI must not directly own WebRTC/transport state.

Commands must pass through authorization before execution.

Tracker sessions must be isolated per device.

A failure in one tracker session must not corrupt another session.

---

## 7. Security Rules

Security is a core requirement.

Never:
- Store PINs in plaintext.
- Log passwords, tokens, private keys, or sensitive credentials.
- Trust UI visibility as authorization.
- Execute privileged commands without permission validation.
- Bypass the authorization pipeline for convenience.

Use Android Keystore-backed mechanisms for sensitive secrets.

The four-digit PIN is local app-entry protection and does not replace account authentication/authorization.

---

## 8. Permission Model

Permissions operate at two levels:

### Device Permission

Which trackers a user can access.

### Service Permission

Which services the user can access on an authorized tracker:

- Location
- Camera
- Audio
- Screen
- Files

Effective action availability must consider:

Capability AND Permission AND Connection State

UI restrictions alone are insufficient.

---

## 9. Real-Time Architecture

The application is state-driven and event-driven.

Incoming flow:

Tracker
→ Transport
→ Event Processor
→ Central State Store
→ ViewModel
→ UI

Outgoing flow:

UI
→ ViewModel
→ Use Case
→ Authorization
→ Command Router
→ Tracker Session
→ Transport

Avoid unnecessary polling.

Events should support identity, ordering/reconciliation where required, and duplicate detection.

---

## 10. Connection Reliability

Connection behavior must account for:

- Signaling failure
- WebRTC failure
- Network changes
- Temporary disconnection
- Reconnection
- Heartbeat
- Session recovery
- Timeouts
- Duplicate commands
- Resource cleanup

Use bounded exponential backoff where appropriate.

Every connection state must be observable.

---

## 11. Lifecycle and Resource Safety

Every long-running resource must have a clear owner and cleanup path.

Pay special attention to:

- PeerConnection
- Media tracks
- Data channels
- Video surfaces
- Audio resources
- File transfers
- Coroutines
- Flow collectors
- Map observers

Do not retain Activity/Context references accidentally.

---

## 12. Performance

Maintain a smooth UI.

Rules:
- Keep heavy work off the main thread.
- Avoid unnecessary Compose recomposition.
- Use stable state where appropriate.
- Use lazy rendering for large lists.
- Batch rapid map updates when appropriate.
- Avoid recreating the map unnecessarily.
- Cancel obsolete coroutines/jobs.
- Release WebRTC/media resources deterministically.
- Avoid unnecessary network requests.

Do not optimize blindly. Measure or inspect the actual bottleneck first.

---

## 13. UI Rules

The UI is mobile-first and map-first.

Primary viewport target:
375dp.

The main dashboard prioritizes:
- Live map
- Device selection
- Device state
- Location
- Accuracy
- Bearing
- Speed
- Geo-fence state

The interface must clearly distinguish:
- Live
- Stale
- Offline
- Connecting
- Error
- Unauthorized
- Unavailable

Never represent stale data as live data.

---

## 14. UI/UX Implementation Rule

Whenever UI work is requested:

1. Use `ui-ux-pro-max-skill`.
2. Inspect the UI/UX Design Specification.
3. Preserve the established design system.
4. Reuse components instead of creating inconsistent one-off components.
5. Implement all relevant states.
6. Check accessibility.
7. Check light/dark themes where applicable.
8. Check loading/error/empty/offline/permission states.
9. Check mobile layout and touch targets.
10. Review visual consistency before declaring completion.

---

## 15. Testing Requirements

Do not consider a feature complete because it compiles.

Depending on the feature, verify:

- Unit tests
- Integration tests
- UI tests
- Lifecycle behavior
- Failure handling
- Permission enforcement
- Reconnection behavior
- Resource cleanup
- Build verification

A phase is complete only when implementation and verification both pass.

---

## 16. Documentation Rules

Keep project documentation synchronized.

When architecture changes materially:
- Update the relevant documentation.
- Explain the change.
- Do not silently create a new architectural pattern.

When implementation differs from the specification:
- Report the difference.
- Explain why.
- Update the specification only when the change is intentional and approved.

---

## 17. Development Order

Follow the approved phase order unless there is a concrete technical reason to change it:

Phase 0 — Foundation + Security
Phase 1 — Authentication + Authorization
Phase 2 — Connection + Session Layer
Phase 3 — Central State + Event System
Phase 4 — Device Management + Diagnostics
Phase 5 — Map + Live Location
Phase 6 — Geo-Fence + Alerts
Phase 7 — Camera
Phase 8 — Audio
Phase 9 — Screen Capture
Phase 10 — File Transfer
Phase 11 — Admin + Users + Audit
Phase 12 — In-App Updates
Phase 13 — Performance + Security Hardening
Phase 14 — Full Integration + Reliability Testing

Do not skip foundational phases merely to reach visible features faster.

---

## 18. Agent Communication

Before a significant implementation:
- State what you inspected.
- State the plan briefly.
- Implement.

After implementation:
- List files changed.
- Explain the important architectural changes.
- Report tests/build results.
- Report unresolved issues.
- Never claim a test passed unless it was actually run.

Be precise and avoid speculative claims.

---

## 19. Definition of Done

A feature is complete only when:

- Requirements are satisfied.
- Architecture boundaries are preserved.
- Authorization is enforced.
- UI states are complete where applicable.
- Failure paths are handled.
- Lifecycle cleanup is verified.
- Tests pass.
- Build passes.
- No known critical regression remains.

---

## 20. Final Rule

Prefer correctness, isolation, security, maintainability, and verified behavior over speed.

Do not resurrect old Controller architecture simply because it already exists.

Build the new Controller deliberately from the approved specifications.

---

## 21. Persistent Agent Memory

Create and maintain a project memory file at the project root:

`AGENT_MEMORY.md`

This file is mandatory and must be treated as persistent engineering context.

### Memory Update Rule

After **every meaningful change**, update `AGENT_MEMORY.md`.

A meaningful change includes:
- Source-code changes
- Architecture changes
- Dependency changes
- Configuration changes
- Database/schema changes
- API/transport changes
- Security changes
- Permission changes
- UI/UX changes
- Test changes
- Build/toolchain changes
- Bug fixes
- Important discovered issues
- Completed development phases

Do not wait until the end of a phase.

### Memory Contents

Keep the memory concise but useful. It should record:

- Current project status
- Current phase
- Completed work
- Files/modules changed
- Important architectural decisions
- Current contracts/interfaces
- Active known issues
- Failed approaches and why they failed
- Test/build status
- Pending work
- Important compatibility constraints
- Relevant Tracker ↔ Controller contracts
- UI/UX decisions that must remain consistent

Do not store secrets, passwords, PINs, tokens, private keys, or sensitive credentials.

### Before Every New Task

Before implementing a new task:

1. Read `AGENT_MEMORY.md`.
2. Read the relevant PRD/TRD/design specification sections.
3. Inspect the relevant source files.
4. Use the required skills.
5. Only then plan and implement.

### After Every Task

After implementation:

1. Verify the change.
2. Run appropriate tests/build checks.
3. Update `AGENT_MEMORY.md`.
4. Record what changed and its current verification status.
5. Report the memory update in the final task summary.

The memory file must describe the **actual current state**, not an intended state.

Never claim that a feature is complete in memory unless implementation and required verification have actually been completed.

---

## 22. Mandatory Prompt Continuity

Every future development prompt for this project must explicitly instruct the coding agent to:

- Read `GEMINI.md`.
- Read `AGENT_MEMORY.md`.
- Use `ponytail` for the engineering work.
- Use `ui-ux-pro-max-skill` whenever the task involves UI/UX.
- Update `AGENT_MEMORY.md` after the change.
- Verify the change before reporting completion.

This rule exists so that context is preserved across separate prompts, sessions, and development phases.
