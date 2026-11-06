
## 2024-11-06 - [Regex Compilation in Recomposition-Heavy Utilities]
**Learning:** Found a critical performance bottleneck in `DeviceNameFormatter.kt` where multiple `Regex` objects were compiled repeatedly on every call during UI recompositions (e.g. inside `LazyColumn` for device lists). This causes unnecessary memory allocations and CPU overhead, especially during scrolling.
**Action:** Always extract inline `Regex` instantiations in utility functions into static `private val` properties at the object level so they are compiled only once. Check utility objects used inside `LazyColumn` or frequent recompositions for this anti-pattern.
