# Phase 04: Native Compose Bottom Sheet UI
Status: ✅ Completed  
Dependencies: Phase 03

## Objective
Implement `NativeStationDetailSheet` in 100% Jetpack Compose matching the generated visual mockup, guaranteeing zero text clipping, responsive layouts across all phone form factors, smooth native gestures, and instant display of live telemetry and 24h usage statistics.

## Requirements
### Functional & Visual Design (Matching Mockup & EVCS Data)
- [x] Create `NativeStationDetailSheet.kt` using `ModalBottomSheet`:
  - `rememberModalBottomSheetState(skipPartiallyExpanded = true)`.
  - Outer container with `Modifier.verticalScroll(rememberScrollState())` to prevent text cutoff on small screens or large accessibility font sizes.
  - Insets: `windowInsetsPadding(WindowInsets.navigationBars)` and `WindowInsets.ime`.
- [x] Header Section:
  - Centered drag handle.
  - Top action bar:
    - Left: Optional back/dismiss button.
    - Right: Reload `IconButton(onClick = onRefresh)` with `Icons.Default.Refresh` and Close `IconButton(onClick = onDismiss)` with `Icons.Default.Close`.
  - Station name: `Text` with `titleLarge`, `fontWeight = Bold`, `maxLines = 3`, `overflow = TextOverflow.Ellipsis`.
  - Address: `Text` with `bodyMedium`, color `onSurfaceVariant`, `maxLines = 2`, `overflow = TextOverflow.Ellipsis`.
  - Badges Row:
    - Distance & ETA Badge: Chip displaying `📍 2.4 km • 6 phút` (or GPS distance from station object).
    - Rating Badge: If `rating != null && rating.count > 0`, chip displaying `⭐ ${rating.avg.format(1)} (${rating.count} đánh giá)`.
- [x] Quick Action Row:
  - Primary Button "Chỉ đường": Emerald green pill (`EmeraldPrimary`), white text and navigation icon. Triggers Google Maps navigation Intent: `geo:0,0?q=lat,lon(stationName)`.
  - Secondary Button "Yêu thích": Circular/pill action with heart icon (active filled emerald or outline). Triggers favorite toggle.
  - Secondary Button "Chia sẻ": Triggers Android Share Sheet with station name, address, and canonical EVCS URL.
- [x] Charging Ports Section (Hero):
  - Section title: "Cổng sạc" / "Charging Ports".
  - Port Pill Badges: For each kW tier, render an elevated card with status dot indicator:
    - Green dot: `${kw}kW: Trống ${available}/${total} cổng`.
    - Amber dot: `${kw}kW: Hết chỗ (0/${total})`.
    - Gray/Red dot: `${kw}kW: Bảo trì`.
- [x] Live Forecast Capsule:
  - Displayed strictly when `cleanForecast != null` (hidden when locked or null).
  - Amber/orange tinted pill card (`ContainerColor = Amber100/Orange900`):
    - Icon: Clock / Warning indicator.
    - Clean text (e.g. `"⏱️ Dự kiến 2 xe sạc trụ 120kW sẽ xong trong 1-7 phút, 1 xe sạc trụ 60kW sẽ xong trong 1 phút nữa"`).
    - Auto-wrapping text container with zero clipping.
- [x] 24h Usage Stats 2x2 Grid (Native Compose Cards - NO Chart Drawing):
  - Card 1: Header `CAO ĐIỂM`, Large value (e.g. `9`), Footer `ô tô sạc`.
  - Card 2: Header `TRUNG BÌNH`, Large value (e.g. `4`), Footer `ô tô sạc`.
  - Card 3: Header `GIỜ CAO ĐIỂM`, Large value (e.g. `17-18h`), Footer `đông xe nhất`.
  - Card 4: Header `TỈ LỆ LẤP ĐẦY`, Large value (e.g. `67%`), Footer `theo số cổng`.
  - While `isLoadingStats == true`: Shimmer / subtle loading placeholder.
  - If stats query failed or unavailable: Displays `-` gracefully.
- [x] Strict Zero Text Clipping Guarantees:
  - No fixed pixel/dp heights on text cards (use `wrapContentHeight()`).
  - `Modifier.weight(1f, fill = false)` on row elements to prevent overflow.
  - Adapts seamlessly to 320dp narrow screens and large system font scales.

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/ui/components/NativeStationDetailSheetUiTest.kt`
- **Verifications**:
  1. Component renders all expected sections: Header, Badges (Distance, Rating), Action Row, Port Chips, Forecast Capsule, 24h Stats Cards.
  2. Actions trigger their respective callbacks (`onNavigate`, `onToggleFavorite`, `onShare`, `onRefresh`, `onDismiss`).
  3. Forecast capsule correctly appears with active forecast and hides when forecast is null.
  4. 24h Stats cards display values, loading placeholders, and `-` fallback correctly.
  5. Text styling and wrapping constraints conform to zero-clipping invariants (no rigid bounding boxes).

---
Next Phase: [phase-05-screen-integration-and-webview-decoupling.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-0115-native-station-detail-bottom-sheet/phase-05-screen-integration-and-webview-decoupling.md)

