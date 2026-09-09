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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.evcs.favorites.domain.model.AuthUser
import com.evcs.favorites.ui.components.FavoritesProfileHeader
import com.evcs.favorites.ui.components.NativeStationDetailContent
import com.evcs.favorites.ui.components.StationCard
import com.evcs.favorites.ui.layout.AdaptiveLayoutHelper
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.state.StationDetailUiState
import com.evcs.favorites.ui.theme.AppIcons
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary

/**
 * Dedicated Automotive Landscape UI for Saved Favorite Stations.
 *
 * Eliminates top TopAppBar, utilizing full vertical screen height.
 * Features a 2-column master-detail layout with cloud sync status and
 * embedded NativeStationDetailContent. Zero nested `if (!isLandscape)` checks.
 */
@Composable
fun FavoritesLandscapeScreen(
    uiState: FavoritesUiState,
    stationDetailState: StationDetailUiState,
    selectedStationForDetail: Station? = null,
    onRefresh: () -> Unit,
    onNavigateClick: (Station) -> Unit,
    onRemoveFavoriteClick: (Station) -> Unit,
    onStationClick: (Station) -> Unit,
    onDismissDetail: () -> Unit,
    onRefreshDetail: () -> Unit,
    onToggleFavoriteDetail: ((Station) -> Unit)? = null,
    onShareClick: (Station) -> Unit,
    onStartFocusMode: ((Station) -> Unit)? = null,
    authUser: AuthUser? = null,
    onSignInClick: () -> Unit = {},
    onSignOutClick: () -> Unit = {},
    isSyncing: Boolean = false,
    isSigningIn: Boolean = false,
    togglingStationIds: Set<String> = emptySet(),
    onEnrichFavorites: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val activeStationForDetail = stationDetailState.station
        ?: selectedStationForDetail
        ?: (uiState as? FavoritesUiState.Success)?.selectedStationForDetail

    val stations = (uiState as? FavoritesUiState.Success)?.stations ?: emptyList()
    var hasAutoSelected by rememberSaveable { mutableStateOf(false) }

    // Trigger on-demand live telemetry enrichment if data contains unverified stations
    LaunchedEffect(stations) {
        if (stations.isNotEmpty() && stations.any { !it.hasLiveTelemetry }) {
            onEnrichFavorites?.invoke()
        }
    }

    // Auto-select first favorite station when results are loaded and selection is empty
    LaunchedEffect(stations, activeStationForDetail, hasAutoSelected) {
        if (!hasAutoSelected && activeStationForDetail == null && stations.isNotEmpty()) {
            hasAutoSelected = true
            val nearest = AdaptiveLayoutHelper.resolveAutoSelectedStation(
                isLandscape = true,
                currentSelection = null,
                stations = stations
            )
            if (nearest != null) {
                onStationClick(nearest)
            }
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
                Column(modifier = Modifier.fillMaxSize()) {
                    val isRefreshing = (uiState as? FavoritesUiState.Success)?.isRefreshing == true || uiState is FavoritesUiState.Loading

                    AnimatedVisibility(
                        visible = isRefreshing || isSyncing,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        LinearProgressIndicator(
                            color = EmeraldPrimary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Cloud sync status and auth profile header
                    FavoritesProfileHeader(
                        authUser = authUser,
                        onSignInClick = onSignInClick,
                        onSignOutClick = onSignOutClick,
                        isSyncing = isSyncing,
                        isSigningIn = isSigningIn
                    )

                    when (uiState) {
                        is FavoritesUiState.Loading -> {
                            FavoritesLandscapeLoading()
                        }

                        is FavoritesUiState.Error -> {
                            FavoritesLandscapeError(
                                message = uiState.message,
                                onRetry = onRefresh
                            )
                        }

                        is FavoritesUiState.Success -> {
                            if (uiState.stations.isEmpty()) {
                                FavoritesLandscapeEmpty(onRefresh = onRefresh)
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(LandscapeScreenContracts.CARD_SPACING_DP)
                                ) {
                                    items(
                                        items = uiState.stations,
                                        key = { it.id },
                                        contentType = { "station_card" }
                                    ) { station ->
                                        StationCard(
                                            station = station,
                                            onNavigateClick = onNavigateClick,
                                            onRemoveFavoriteClick = onRemoveFavoriteClick,
                                            isToggleInProgress = togglingStationIds.contains(station.id),
                                            onStationClick = onStationClick,
                                            isSelected = station.id == activeStationForDetail?.id,
                                            isCarMode = true,
                                            isCompact = true
                                        )
                                    }
                                }
                            }
                        }

                        is FavoritesUiState.LoggedOut -> {
                            FavoritesLandscapeEmpty(onRefresh = onRefresh)
                        }

                        is FavoritesUiState.RequestingOtp,
                        is FavoritesUiState.VerifyingOtp -> {
                            // Handled at parent host
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
                if (activeStationForDetail != null) {
                    val effectiveDetailState = if (stationDetailState.station?.id == activeStationForDetail.id) {
                        stationDetailState
                    } else {
                        stationDetailState.copy(station = activeStationForDetail)
                    }

                    NativeStationDetailContent(
                        station = activeStationForDetail,
                        uiState = effectiveDetailState,
                        isFavorite = true,
                        isToggleInProgress = activeStationForDetail.id.let { togglingStationIds.contains(it) },
                        onRefresh = onRefreshDetail,
                        onDismiss = onDismissDetail,
                        onNavigate = onNavigateClick,
                        onToggleFavorite = onToggleFavoriteDetail ?: onRemoveFavoriteClick,
                        onShare = onShareClick,
                        onStartFocusMode = onStartFocusMode,
                        isLandscape = true,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    FavoritesLandscapeDetailEmptyState(
                        isEmptyList = (uiState as? FavoritesUiState.Success)?.stations?.isEmpty() ?: true
                    )
                }
            }
        }
    }
}

/**
 * Compact loading indicator for favorite stations in automotive landscape.
 */
@Composable
private fun FavoritesLandscapeLoading(
    modifier: Modifier = Modifier
) {
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
            text = "Đang tải trạm sạc yêu thích...",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Compact error state for favorite stations in automotive landscape.
 */
@Composable
private fun FavoritesLandscapeError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = AppIcons.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
        ) {
            Text("Thử lại")
        }
    }
}

/**
 * Empty favorite stations list state in automotive landscape.
 */
@Composable
private fun FavoritesLandscapeEmpty(
    onRefresh: () -> Unit,
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
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                imageVector = AppIcons.EvStation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Chưa có trạm yêu thích nào",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Bấm biểu tượng trái tim ở trạm sạc bất kỳ để lưu lại tại đây.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = onRefresh,
            colors = ButtonDefaults.buttonColors(
                containerColor = EmeraldPrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(40.dp)
        ) {
            Text("Làm mới", fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Empty detail pane placeholder when no favorite station is selected.
 */
@Composable
private fun FavoritesLandscapeDetailEmptyState(
    isEmptyList: Boolean,
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
                imageVector = AppIcons.Bolt,
                contentDescription = null,
                tint = EmeraldPrimary,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isEmptyList) "Chưa có trạm yêu thích" else "Chi tiết trạm sạc",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isEmptyList)
                "Các trạm sạc bạn bấm yêu thích sẽ hiển thị tại đây để dẫn đường nhanh."
            else
                "Chọn một trạm sạc từ danh sách bên trái để xem thông tin chi tiết và dẫn đường.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
