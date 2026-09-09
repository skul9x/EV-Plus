# Phase 01: Station Name Sanitizer VinFast Strip & Marquee Text Support

Status: ✅ Completed
Dependencies: None

## Objective

Clean redundant VinFast brand prefixes from all charging station titles (e.g. converting `"Vinfast - TTTM Dabaco Mart Quế Võ"` into `"TTTM Dabaco Mart Quế Võ"`) across the entire app ecosystem, and enable horizontal `basicMarquee` text scrolling on station titles within `StationCard` to prevent truncation ellipses on long venue names.

## Requirements

### Functional
- [x] Upgrade `StationNameSanitizer.kt` to detect and strip all brand prefix variations (case-insensitive):
  - `VinFast - `, `Vinfast - `, `VINFAST - `, `Vin Fast - `, `Vin-Fast - `
  - `VinFast: `, `Vinfast: `
  - `VinFast `, `Vinfast `
  - Combinations such as `Trạm sạc VinFast - `, `Trụ sạc VinFast: `
- [x] Ensure clean t￼
brainstorm
 tôi muốn cải thiện UI của app này ở màn hình ngang:
1) làm hẳn 1 code riêng biệt cho màn hình ngang
2) button setting sẽ di chuyển xuống dưới button Yêu Thích và Quanh đây (ở bên trái)
3) Button Làm mới ở cạnh button Setting cũng sẽ di chuyển cùng button Setting để dưới button Yêu Thích và Quanh đây và Setting (ở bên trái)
4) Cái thanh "Trạm sạc gần đây \n 10 trạm sạc gần nhất" bỏ đi, mục đích để có nhiều diện thích show info trụ sạc bên dưới hơn, lúc này bỏ được hết vì buttons setting và refresh đã nằm dưới button Yêu Thích và Quanh đây rồi đúng không?
5) Cái trục dọc lúc này sẽ gồm Yêu Thích, Quanh đây, Setting và Làm mới. Content (các button) năm trong cái trục dọc bên trái này sẽ được căn giữa theo trục dọc
6) Xoá cái dòng bên trên info các trạm sạc có dạng "Tìm thấy 10 trạm có cổng DC <=30kW khả dụng"
7) Tiêu đề các trạm sạc cần loại bỏ đi cái tiền tố Vinfast, ví dụ như "Vinfast - TTTM Dabaco Mart Quế Võ" sẽ trở thành "TTTM Dabaco Mart Quế Võ"
8) Tôi muốn app này khi chạy sẽ chạy toàn màn hình thì cái Thanh Dock (Dock Bar / Quick Launch Bar) trong android box sẽ bị ẩn đi, khi nào vuốt thì nó mới hiện ra. tôi đang dùng cái "Android Box Carlinkit Tbox Ambient QCM6225 8G/128GB" để chạy app này và đa phần người dùng ở việt nam đều dùng cái này nên tôi muốn tối ưu cho cái này. search online to find the correct way to do this. note, do not open a browser and directly access the dom. use search_web / read_url_content when you need to look something up. 
Có edge questions gì thì cứ hỏi.rimming of any dangling leading delimiters (`-`, `:`, `–`, `—`, `»`, spaces) after stripping prefixes.
- [x] Preserve the original station name if sanitization produces an empty string (fallback safety).
- [x] In `StationCard.kt`, configure station title Text composables to use `Modifier.basicMarquee()` so that long station names scroll smoothly across the card instead of truncating with `...`.

### Non-Functional
- [x] High-performance regex execution with pre-compiled static regex patterns.
- [x] Zero layout jitter or recomposition lag during list scrolling.
- [x] Single file-based verification test executed in a pure headless JVM environment.

## Implementation Steps
1. [x] Modify `app/src/main/java/com/evcs/favorites/util/StationNameSanitizer.kt`:
   - Define `VINFAST_PREFIX_REGEX` covering case-insensitive combinations of "VinFast", "Vin Fast", "Vin-Fast" followed by optional delimiters (`-`, `:`, `–`, `—`).
   - Order sanitization pipeline: Guillemet removal ➔ Distance prefix removal ➔ Station/Post prefix removal ➔ VinFast brand prefix removal ➔ Secondary station prefix removal ➔ Delimiter trimming.
2. [x] Modify `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt`:
   - Import `androidx.compose.foundation.basicMarquee`.
   - Apply `basicMarquee(iterations = Int.MAX_VALUE, delayMillis = 2000, velocity = 30.dp)` to title text elements in both portrait and car mode cards.
3. [x] Create single verification test `app/src/test/java/com/evcs/favorites/util/StationNameSanitizerTest.kt`:
   - Verify standard VinFast prefixes: `"Vinfast - TTTM Dabaco Mart Quế Võ"` ➔ `"TTTM Dabaco Mart Quế Võ"`.
   - Verify uppercase VinFast prefixes: `"VINFAST - TƯ NHÂN VŨ TIẾN LỰC"` ➔ `"TƯ NHÂN VŨ TIẾN LỰC"`.
   - Verify compound station + brand prefixes: `"Trạm sạc VinFast - Cửa hàng xăng dầu Cách Bi"` ➔ `"Cửa hàng xăng dầu Cách Bi"`.
   - Verify brand prefix without hyphen: `"VinFast TTTM Dabaco Mart Quế Võ"` ➔ `"TTTM Dabaco Mart Quế Võ"`.
   - Verify names without VinFast prefix remain untouched: `"TTTM Vincom Mega Mall"` ➔ `"TTTM Vincom Mega Mall"`.
   - Verify null and blank string safety.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/util/StationNameSanitizer.kt` - [MODIFY] Enhance regex sanitization pipeline
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - [MODIFY] Add `basicMarquee` to station titles
- `app/src/test/java/com/evcs/favorites/util/StationNameSanitizerTest.kt` - [NEW] Single comprehensive verification test

## Single Verification Test
- **Test Class:** `com.evcs.favorites.util.StationNameSanitizerTest`
- **Execution Command:**
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.util.StationNameSanitizerTest"
  ```

---
Next Phase: `phase-02-immersive-mode-and-compact-navigation-rail.md`
