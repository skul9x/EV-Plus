# Phase 02: Logging Buffer Churn Reduction & Lazy UI Rendering

Status: ✅ Completed
Issue IDs: PERF-MEM-01, PERF-UI-04
Dependencies: Phase 01

## Objective
Eliminate severe UI freeze (FPS < 15) and GC allocation churn caused by copying 500-item ArrayLists on every log event in `AppDebugLogger` and rendering 500 debug log rows simultaneously inside a non-lazy `Column` in `DebugLogViewerCard`.

---

## Requirements

### Functional
- [x] In `AppDebugLogger.kt`, eliminate unconditional `buffer.toList()` allocation on every individual `log()` call:
  - Check `_logsFlow.subscriptionCount.value`: if 0 (no active observers, e.g. settings modal closed), do NOT allocate and emit a new 500-item list.
  - When active observers are present, apply a throttle/debounce mechanism (e.g., minimum 300ms interval between snapshot emissions during rapid bursts) while keeping thread safety under `lock`.
  - Provide a synchronous `flush()` function to immediately push the latest snapshot to `_logsFlow` for unit tests and deterministic assertions.
- [x] In `DebugLogViewerCard.kt`, replace the non-lazy `Column(modifier.verticalScroll(...))` with a memory-efficient `LazyColumn`.
- [x] To prevent `IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints` inside `RoutingSettingsModal` (which has an outer `verticalScroll`), the `LazyColumn` must be bounded with an explicit constraint (`Modifier.fillMaxWidth().heightIn(max = 360.dp)`).
- [x] Configure `LazyColumn` with stable item keys (`key = { it.id }`), item spacing (`verticalArrangement = Arrangement.spacedBy(6.dp)`), and remove the obsolete `logScrollState = rememberScrollState()`.
- [x] Ensure full text export (`getFormattedLogText()`), log clearing (`clear()`), and expand/collapse toggles remain 100% functional.

### Non-Functional
- [x] Allocation reduction: >95% reduction in object allocations during rapid bursts of logs (e.g., Top 5 station forecast enrichment).
- [x] Frame rate: Smooth 60 FPS scrolling in Settings tab even with 500 logs loaded.

---

## Implementation Steps
1. **Update `AppDebugLogger.kt`**:
   - Add throttle/dirty-flag logic for `_logsFlow` updates: avoid copying `buffer.toList()` when `_logsFlow.subscriptionCount.value == 0`.
   - Add `flush()` method for immediate snapshot propagation.
   - Maintain synchronous FIFO buffer eviction under `lock`.
2. **Update `DebugLogViewerCard.kt`**:
   - Replace inner `Column + verticalScroll` with bounded `LazyColumn`.
   - Set item keys (`key = { it.id }`) for recycling.
   - Remove unused `rememberScrollState()` for log entries.
3. **Verify Functionality**:
   - Verify logs display, filter, toggle expansion, clear, and export without error.

---

## Files to Modify/Create
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/logging/AppDebugLogger.kt` - Optimize snapshot emissions with observer check, throttling, and flush().
- [MODIFY] `app/src/main/java/com/evcs/favorites/ui/components/DebugLogViewerCard.kt` - Replace `Column` with bounded `LazyColumn`.
- [NEW] `app/src/test/java/com/evcs/favorites/DebugLoggingMemoryAndLazyUiTest.kt` - Exactly one comprehensive test for Phase 02.

---

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/DebugLoggingMemoryAndLazyUiTest.kt`
- **Core Verifications**:
  1. High-frequency logging (100+ rapid events) does not allocate intermediate lists when there are no observers.
  2. Calling `flush()` immediately synchronizes `_logsFlow.value` with buffer contents.
  3. Buffer strictly enforces `MAX_CAPACITY = 500` with FIFO eviction.
  4. Lazy item key generator in `DebugLogViewerCard` guarantees distinct, stable keys for Compose recycling.

---

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.DebugLoggingMemoryAndLazyUiTest
```
