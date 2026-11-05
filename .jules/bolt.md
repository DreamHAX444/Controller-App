## 2024-05-24 - [O(N^2) Map Rendering]
**Learning:** Found an O(N^2) list membership check during overlay reconciliation in MapCanvas.kt, a critical code path that runs on every frame. Kotlin's `in` operator on `List` implies an O(N) scan.
**Action:** Always prefer `Set` when checking for membership in loops. Converted `overlayList` to `overlaySet` resulting in O(N) linear time overall rendering.
