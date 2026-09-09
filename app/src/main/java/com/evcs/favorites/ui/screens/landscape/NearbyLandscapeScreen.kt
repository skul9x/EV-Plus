package com.evcs.favorites.ui.screens.landscape

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.ui.components.NativeStationDetailContent
import com.evcs.favorites.ui.components.NearbyUiHelper
import com.evcs.favorites.ui.components.SmartFilterBar
import com.evcs.favorites.ui.components.StationCard
import com.evcs.favorites.ui.layout.AdaptiveLayoutHelper
import com.evcs.favorites.ui.state.NearbyUiState
import com.evcs.favorites.ui.state.StationDetailUiState
import com.evcs.favorites.ui.theme.AppIcons
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary

/**
 * Dedicated Automotive Landscape UI for Nearby Charging Stations.
 *
 * Eliminates top TopAppBar and filter summary pill, reclaiming ~95dp vertical space
 * to display 3-4 visible StationCard items alongside the full-height detail pane.
 * Zero nested `if (!isLandscape)` checks.
 */
@Composable
fun NearbyLandscapeScreen(
    uiState: NearbyUiState,
    stationDetailState: StationDetailUiState,
    onCustomFilterClick: () -> Unit,
    onDcFilterClick: () -> Unit,
    onAcFilterClick: () -> Unit,
    onSelectDcTier: (DcWattageTier) -> Unit,
    onBackFromDc: () -> Unit,
    onClearFilters: () -> Unit,
    onFavoriteClick: (Station) -> Unit,
    onNavigateClick: (Station) -> Unit,
    onStationClick: (Station) -> Unit,
    onRefreshDetail: () -> Unit,
    onDismissDetail: () -> Unit,
    onShareClick: (Station) -> Unit,
    onStartFocusMode: (Station) -> Unit,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var hasAutoSelected by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.lastRefreshTimestamp) {
        hasAutoSelected = false
    }

    // Auto-select nearest station upon search completion when selection is null
    LaunchedEffect(uiState.top10DisplayStations, stationDetailState.station, hasAutoSelected) {
        if (!hasAutoSelected && stationDetailState.station == null && uiState.top10DisplayStations.isNotEmpty()) {
            hasAutoSelected = true
            val nearest = AdaptiveLayoutHelper.resolveAutoSelectedStation(
                isLandscape = true,
                currentSelection = null,
                stations = uiState.top10DisplayStations
            )
            if (nearest != null) {
                onStationClick(nearest)
            }
        }
    }

    // Smooth scroll to top on refreshed stations
    var lastHandledRefreshTimestamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(uiState.lastRefreshTimestamp, uiState.top10DisplayStations.size) {
        if (NearbyUiHelper.shouldScrollToTop(
                isUserInitiated = true,
                itemCount = uiState.top10DisplayStations.size,
                lastHandledTimestamp = lastHandledRefreshTimestamp,
                eventTimestamp = uiState.lastRefreshTimestamp
            )
        ) {
            lastHandledRefreshTimestamp = uiState.lastRefreshTimestamp
            listState.animateScrollToItem(0)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val allocation = remember(maxWidth.value) {
            AdaptiveLayoutHelper.calculateMasterDetailWidths(
                totalWidthDp = maxWidth.value,
                navRailWidthDp = 0f
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = LandscapeScreenContracts.SCREEN_HORIZONTAL_PADDING_DP)
                .padding(vertical = LandscapeScreenContracts.SCREEN_VERTICAL_PADDING_DP),
            horizontalArrangement = Arrangement.spacedBy(LandscapeScreenContracts.MASTER_DETAIL_SPACING_DP)
        ) {
            // Master Column (Left: 35% - 40%, clamped to [320dp, 480dp])
            Box(
                modifier = Modifier
                    .width(allocation.masterWidthDp.dp)
                    .fillMaxHeight()
            ) {
                when {
                    // Initial State (before search)
                    !uiState.hasSearched && !uiState.isLocating && !uiState.isSearching -> {
                        NearbyLandscapeInitialHero(
                            uiState = uiState,
                            onScanClick = onScanClick,
                            onCustomFilterClick = onCustomFilterClick,
                            onDcFilterClick = onDcFilterClick,
                            onAcFilterClick = onAcFilterClick,
                            onSelectDcTier = onSelectDcTier,
                            onBackFromDc = onBackFromDc,
                            onClearFilters = onClearFilters
                        )
                    }

                    // Loading State
                    !uiState.hasSearched && (uiState.isLocating || uiState.isSearching) -> {
                        NearbyLandscapeLoading(uiState = uiState)
                    }

                    // Result State
                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Compact SmartFilterBar (4dp vertical padding)
                            SmartFilterBar(
                                activeFilterMode = uiState.activeFilterMode,
                                isDcSubFilterVisible = uiState.isDcSubFilterVisible,
                                selectedDcTier = uiState.selectedDcTier,
                                savedCustomConfig = uiState.savedCustomConfig,
                                onCustomClick = onCustomFilterClick,
                                onDcClick = onDcFilterClick,
                                onAcClick = onAcFilterClick,
                                onSelectDcTier = onSelectDcTier,
                                onBackFromDc = onBackFromDc,
                                onClearFilter = onClearFilters,
                                modifier = Modifier.padding(
                                    vertical = LandscapeScreenContracts.FILTER_BAR_VERTICAL_PADDING_DP
                                )
                            )

                            // Progress bar during background search / routing refresh
                            AnimatedVisibility(
                                visible = uiState.isSearching || uiState.isRoutingLoading,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                LinearProgressIndicator(
                                    color = EmeraldPrimary,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Note: Filter summary count pill is eliminated in landscape mode!
                            // Cards begin directly below filter bar / progress indicator.
                            if (uiState.top10DisplayStations.isEmpty()) {
                                NearbyLandscapeEmptyFilterContent(onClearFilters = onClearFilters)
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(LandscapeScreenContracts.CARD_SPACING_DP)
                                ) {
                                    items(
                                        items = uiState.top10DisplayStations,
                                        key = { it.id },
                                        contentType = { "station_card" }
                                    ) { station ->
                                        StationCard(
                                            station = station,
                                            onNavigateClick = onNavigateClick,
                                            onFavoriteClick = onFavoriteClick,
                                            isFavorite = uiState.favoriteStationIds.contains(station.id),
                                            isToggleInProgress = uiState.togglingStationIds.contains(station.id),
                                            onStationClick = onStationClick,
                                            isSelected = station.id == stationDetailState.station?.id,
                                            isCarMode = true,
                                            isCompact = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Detail Column (Right: 60% - 65% remaining width)
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(LandscapeScreenContracts.DETAIL_SURFACE_CORNER_RADIUS_DP),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = LandscapeScreenContracts.DETAIL_SURFACE_ELEVATION_DP,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            ) {
                val currentStation = stationDetailState.station
                if (currentStation != null) {
                    NativeStationDetailContent(
                        station = currentStation,
                        uiState = stationDetailState,
                        isFavorite = uiState.favoriteStationIds.contains(currentStation.id),
                        isToggleInProgress = uiState.togglingStationIds.contains(currentStation.id),
                        onRefresh = onRefreshDetail,
                        onDismiss = onDismissDetail,
                        onNavigate = onNavigateClick,
                        onToggleFavorite = onFavoriteClick,
                        onShare = onShareClick,
                        onStartFocusMode = onStartFocusMode,
                        isLandscape = true,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    NearbyLandscapeDetailEmptyState(
                        isEmptyResults = uiState.hasSearched && uiState.top10DisplayStations.isEmpty()
                    )
                }
            }
        }
    }
}

/**
 * Compact automotive initial hero state for the master column before scanning.
 */
@Composable
private fun NearbyLandscapeInitialHero(
    uiState: NearbyUiState,
    onScanClick: () -> Unit,
    onCustomFilterClick: () -> Unit,
    onDcFilterClick: () -> Unit,
    onAcFilterClick: () -> Unit,
    onSelectDcTier: (DcWattageTier) -> Unit,
    onBackFromDc: () -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        SmartFilterBar(
            activeFilterMode = uiState.activeFilterMode,
            isDcSubFilterVisible = uiState.isDcSubFilterVisible,
            selectedDcTier = uiState.selectedDcTier,
            savedCustomConfig = uiState.savedCustomConfig,
            onCustomClick = onCustomFilterClick,
            onDcClick = onDcFilterClick,
            onAcClick = onAcFilterClick,
            onSelectDcTier = onSelectDcTier,
            onBackFromDc = onBackFromDc,
            onClearFilter = onClearFilters,
            modifier = Modifier.padding(vertical = LandscapeScreenContracts.FILTER_BAR_VERTICAL_PADDING_DP)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(EmeraldContainerDark.copy(alpha = 0.6f))
            ) {
                Icon(
                    imageVector = AppIcons.NearMe,
                    contentDescription = null,
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Tìm trạm sạc quanh đây",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onScanClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 8.dp
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = AppIcons.NearMe,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Nhấn để tìm trạm quanh đây",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                )
            }
        }
    }
}

/**
 * Compact loading indicator for automotive landscape master column.
 */
@Composable
private fun NearbyLandscapeLoading(
    uiState: NearbyUiState,
    modifier: Modifier = Modifier
) {
    val loadingText = when {
        uiState.isLocating -> "Đang xác định vị trí GPS..."
        uiState.isSearching -> "Đang tải dữ liệu trạm sạc..."
        uiState.isRoutingLoading -> "Đang tính toán lộ trình..."
        else -> "Đang tải dữ liệu..."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = EmeraldPrimary,
            strokeWidth = 3.dp,
            modifier = Modifier.size(44.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = loadingText,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Empty filter results state when wattage filters exclude all stations.
 */
@Composable
private fun NearbyLandscapeEmptyFilterContent(
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                imageVector = AppIcons.FilterListOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Không có trạm phù hợp bộ lọc",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onClearFilters,
            colors = ButtonDefaults.buttonColors(
                containerColor = EmeraldPrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(40.dp)
        ) {
            Text(
                text = "Xóa bộ lọc",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

/**
 * Clean empty state placeholder displayed in the detail pane when no station is selected.
 */
@Composable
private fun NearbyLandscapeDetailEmptyState(
    isEmptyResults: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(EmeraldContainerDark.copy(alpha = 0.5f))
        ) {
            Icon(
                imageVector = AppIcons.EvStation,
                contentDescription = null,
                tint = EmeraldPrimary,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isEmptyResults) "Không có trạm sạc khả dụng" else "Chi tiết trạm sạc",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isEmptyResults)
                "Thử điều chỉnh hoặc xóa bộ lọc để tìm kiếm thêm trạm sạc quanh bạn."
            else
                "Chọn một trạm sạc từ danh sách bên trái để xem trạng thái cổng sạc, đánh giá và dẫn đường nhanh.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
