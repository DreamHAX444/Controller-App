---
trigger: always_on
---

# Global Project Rules: Ponytail & UI/UX Pro Max

This project strictly follows core guidelines for all coding and design tasks:

## 1. Ponytail (Lazy Senior Dev Mode)
Always apply the Ponytail philosophy to every task. The best code is the code never written.

Before writing any code, stop at the first rung that holds:
1. **YAGNI (You Aren't Gonna Need It)**: Does this need to be built at all? If no, skip it.
2. **Reuse**: Does it already exist in this codebase? Reuse the helper, util, or pattern.
3. **Standard Library**: Does the standard library already do this? Use it.
4. **Native Platform Feature**: Does a native platform feature cover it? Use it.
5. **Existing Dependency**: Does an already-installed dependency solve it? Use it.
6. **One Line**: Can this be one line? Make it one line.
7. **Minimum Viable Code**: Only then, write the absolute minimum code that works.

**Rules:**
- No abstractions that weren't explicitly requested.
- No new dependency if it can be avoided.
- Deletion over addition. Boring over clever. Fewest files possible.
- Bug fix = root cause, not symptom. Grep callers and fix shared functions once.
- *Not lazy about:* Understanding the problem, input validation at trust boundaries, error handling, security, and accessibility.

## 2. UI/UX Pro Max
When working on any UI/UX tasks (building screens, components, layouts, styling), ALWAYS invoke the `ui-ux-pro-max` guidelines:
- Apply the appropriate **Design System** reasoning for the component/feature.
- Ensure proper color palettes, typography, spacing, and micro-interactions.
- Comply with UX guidelines and accessibility (WCAG) standards.
- Use the relevant UI styles (e.g., Glassmorphism, Material Design, Bento Grid, etc.) depending on the task context.

## 3. Agent Memory Updater
You MUST ALWAYS update `.agent_memory/memory.md` at the end of each task or sub-task to record any modifications, tasks, decisions, and updates made during your session. This ensures a continuous record of changes for the tracker app.

**Remember:** 
- If a task involves logic, architecture, or backend -> Think **Ponytail**.
- If a task involves interfaces, design, or user experience -> Think **UI/UX Pro Max** and **Ponytail** (for the implementation).
