## 2023-10-27 - [Compose Recomposition Optimization]
**Learning:** Adding a `key` parameter to `items` within a `LazyColumn`/`LazyRow` (e.g. `items(uiState.devices, key = { it.deviceId })`) prevents unnecessary recompositions when the list order changes.
**Action:** When working with lazy collections (LazyColumn/LazyRow) in Jetpack Compose, ensure a unique `key` is always provided if available.
