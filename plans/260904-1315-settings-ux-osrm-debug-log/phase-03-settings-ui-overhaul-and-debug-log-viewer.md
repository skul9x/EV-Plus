# Phase 03: Settings UI Overhaul, Min/Max Labels & Debug Log Viewer
Status: ✅ Completed
Dependencies: `phase-02-debug-log-subsystem-and-forecast-capture.md`

## Objective
Overhaul the Settings bottom sheet UI to:
1. Eliminate the visible custom scrollbar indicator (`.verticalScrollbar`) so the modal is visually clean while keeping smooth vertical scroll functionality (`Modifier.verticalScroll`).
2. Remove Google BYOK API Key input, Paste button, Test Connection button, error remediation card, and Google API Key 5-step guide banner from the settings modal.
3. Remove the routing engine selection radio buttons and auto-fallback toggle from the UI (since routing is now standardized on OSRM + Haversine fallback).
4. Update custom power filter input labels from "Tối thiểu" to "Min" and "Tối đa" to "Max".
5. Integrate the "Nhật ký gỡ lỗi (Debug Log)" viewer section into the settings modal with real-time log entries, status pills, and 3 functional buttons: **Chia sẻ (Share)** via `Intent.ACTION_SEND`, **Sao chép (Copy)** to Clipboard, and **Xóa log (Delete / Clear)**.

## Requirements
### Functional
- [x] Remove `.verticalScrollbar(scrollState)` modifier at line 159 in `RoutingSettingsModal.kt`. The Column continues scrolling smoothly with standard `Modifier.verticalScroll(scrollState)` while completely hiding any scrollbar indicator.
- [x] In `CustomFilterSettingsCard.kt`:
  - Change text field labels:
    - `label = { Text("Tối thiểu") }` -> `label = { Text("Min") }`
    - `label = { Text("Tối đa") }` -> `label = { Text("Max") }`
  - Update clear icon content descriptions: "Xóa Min" and "Xóa Max".
  - Update validation error messages to refer consistently to "Min" and "Max" (e.g. "Min phải từ 1 đến 500 kW", "Min không được lớn hơn Max").
- [x] Clean up `RoutingSettingsModal.kt`:
  - Update header title from "Cài đặt Lộ trình & BYOK" to "Cài đặt" (subtitle: "Cấu hình bộ lọc công suất và nhật ký gỡ lỗi").
  - Remove Google Maps API Key section, paste button, connection testing button, error remediation card, 5-step guide banner, and `GoogleApiKeyGuideModal` dialog.
  - Remove routing engine radio buttons (AUTO, GOOGLE_ONLY, OSRM_ONLY, HAVERSINE_ONLY) and auto-fallback toggle switch.
  - Retain CustomFilterSettingsCard and bottom "Đóng" / "Lưu cài đặt" buttons.
- [x] Create and integrate `DebugLogViewerCard` inside the settings modal:
  - Header with terminal icon, title "Nhật ký gỡ lỗi (Debug Log)", and total entries badge.
  - 3 Action buttons:
    - **Chia sẻ (Share)**: Creates and starts an `ACTION_SEND` chooser Intent with formatted log text (`AppDebugLogger.getFormattedLogText()`).
    - **Sao chép (Copy)**: Copies formatted log text to Android `ClipboardManager` and displays feedback Toast ("Đã sao chép nhật ký").
    - **Xóa log (Delete / Clear)**: Calls `AppDebugLogger.clear()` to clear all logs immediately.
  - Scrollable terminal-style live log viewer:
    - **Layout constraint**: Bound with fixed or max height (`Modifier.heightIn(max = 360.dp)`) to avoid Compose nested infinite height crash inside `verticalScroll`.
    - Live list collecting from `AppDebugLogger.logsFlow`.
    - Color-coded badges for tags (`FORECAST` in Emerald, `ERROR` in Red, `NETWORK`/`SEARCH`/`ROUTING` in Blue/Purple).
    - Prominent display for charging forecast messages (e.g. "⏱️ Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 7 phút nữa").
    - Expandable detail per entry showing HTTP method, full URL, status code, latency (ms), request/response body snippets, and error traces.
    - Empty state when no logs: "Chưa có nhật ký hoạt động mạng".

### Non-Functional
- [x] Compose gesture safety: No scroll conflict or measurement crash between parent `verticalScroll` and log viewer list.
- [x] Smooth UI performance: capped list rendering in the log viewer to prevent UI jank.
- [x] Consistent dark theme styling matching EV Emerald design system (`EmeraldPrimary`, `EmeraldContainerDark`, `DarkCardBackground`).

## Implementation Steps
1. [x] Edit `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt` to update labels to "Min" and "Max" and refine error messages.
2. [x] Edit `app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt`:
   - Remove `.verticalScrollbar(scrollState)`.
   - Remove Google BYOK section, connection test, guide banner, and engine radio buttons.
   - Embed `DebugLogViewerCard`.
3. [x] Implement `DebugLogViewerCard.kt` in `app/src/main/java/com/evcs/favorites/ui/components/` with Share, Copy, and Delete buttons, bounded height log inspection list, and expandable item details.
4. [x] Create single comprehensive test `app/src/test/java/com/evcs/favorites/ui/components/SettingsModalRedesignTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt` - Update "Tối thiểu" / "Tối đa" to "Min" / "Max"
- `app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt` - Remove scrollbar, remove BYOK, embed Debug Log viewer
- `app/src/main/java/com/evcs/favorites/ui/components/DebugLogViewerCard.kt` - [NEW] Debug Log UI component with Share, Copy, Clear actions & live viewer
- `app/src/test/java/com/evcs/favorites/ui/components/SettingsModalRedesignTest.kt` - [NEW] Single comprehensive verification test

## Test Criteria (Single Test File)
- **Test File**: `app/src/test/java/com/evcs/favorites/ui/components/SettingsModalRedesignTest.kt`
- [x] Verify updated settings structure defaults cleanly to OSRM without requiring Google Key.
- [x] Verify `CustomFilterSettingsCard` state accepts Min and Max validation logic with updated label texts.
- [x] Verify Debug Log actions: Share Intent data creation formatting, Clipboard text generation, and Clear log dispatch.
- [x] Verify absence of custom scrollbar indicator modifier while confirming scroll state tracking.
- [x] Verify log entry formatting in viewer handles charging forecast messages ("Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 7 phút nữa") and error states.

---
Plan Complete!
