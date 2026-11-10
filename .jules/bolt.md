## 2024-03-24 - [Recomposition Optimization]
**Learning:** Found unnecessary recomposition in `DeviceSelectionSheet.kt`. `deviceSelection` and `isMultiSelectMode` were causing all items in `LazyColumn` to recompose whenever any selection changed.
**Action:** Use `derivedStateOf` or check if the device's selection state specifically has changed to avoid unnecessary full-list recompositions.


## 2024-03-24 - [Collection Optimization]
**Learning:** Found O(N^2) memory footprint and unnecessary allocations when using `groupBy` followed by `map` for distincting collections by property in `SupabaseFleetManager.kt`.
**Action:** Replace `all.filter { ... }.groupBy { it.deviceId }.map { (_, list) -> list.first() }` with `all.filter { ... }.distinctBy { it.deviceId }`. This avoids allocating multiple lists in `groupBy` and reduces the operation to O(N) time/memory with a single pass using a HashSet internally.
