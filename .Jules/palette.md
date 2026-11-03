## 2023-11-03 - [Missing Icon Content Descriptions]
**Learning:** Found a common pattern of `contentDescription = null` for Icons used within IconButtons and decorative areas. This makes the UI unreadable to screen readers.
**Action:** Always provide meaningful `contentDescription` for icons within `IconButton` or interactive elements. If purely decorative, ensure it does not take focus or have an interactive parent, or pass `null` intentionally only if it is completely non-informational.
