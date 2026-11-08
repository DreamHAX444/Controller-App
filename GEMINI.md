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

## 2. Search-First & Partial-Reuse Protocol (Codebase-Aware DRY Execution)
Before generating, scaffolding, or modifying ANY code for a new request, you MUST follow this sequence:
1. **Search First (Codebase Discovery)**: Thoroughly search the codebase (`grep`, file search) for related utilities, models, composables, or patterns before writing a single line.
2. **Full Reuse**: If an existing component, util, or helper exists, REUSE it directly and adapt it to the user's requirement.
3. **Partial Reuse (The 50% Rule)**: If a task has multiple parts (e.g. 3 sub-tasks) and one part is already half-implemented or exists in another file/screen, PULL that existing code, complete the missing piece, and wire it together. Never rewrite or duplicate what is already partially built!
4. **Write New ONLY When Truly Absent**: Only write new code if zero reusable or adaptable patterns exist in the repository.
5. **No Clutter ("বেশি বেশি" নিষিদ্ধ)**: Keep code lines minimal, cohesive, and aligned with existing project conventions. Zero unnecessary wrappers, zero speculative abstractions.

## 3. UI/UX Pro Max
When working on any UI/UX tasks (building screens, components, layouts, styling), ALWAYS invoke the `ui-ux-pro-max` guidelines:
- Apply the appropriate **Design System** reasoning for the component/feature.
- Ensure proper color palettes, typography, spacing, and micro-interactions.
- Comply with UX guidelines and accessibility (WCAG) standards.
- Use the relevant UI styles (e.g., Glassmorphism, Material Design, Bento Grid, etc.) depending on the task context.

## 4. Agent Memory Updater
You MUST ALWAYS update `.agent_memory/memory.md` at the end of each task or sub-task to record any modifications, tasks, decisions, and updates made during your session. This ensures a continuous record of changes for the tracker app.

**Remember:** 
- If a task involves logic, architecture, or backend -> Think **Ponytail**.
- If a task involves interfaces, design, or user experience -> Think **UI/UX Pro Max** and **Ponytail** (for the implementation).
