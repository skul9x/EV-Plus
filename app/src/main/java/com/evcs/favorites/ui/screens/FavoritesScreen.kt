package com.evcs.favorites.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import com.evcs.favorites.ui.theme.AppIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.ui.components.FocusModePermissionDialog
import com.evcs.favorites.ui.components.NativeStationDetailContent
import com.evcs.favorites.ui.components.NativeStationDetailSheet
import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import com.evcs.favorites.ui.components.RoutingSettingsModal
import com.evcs.favorites.ui.components.StationCard
import com.evcs.favorites.ui.layout.AdaptiveLayoutHelper
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.state.StationDetailUiState
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.theme.StatusOffline

import com.evcs.favorites.domain.model.AuthUser
import com.evcs.favorites.ui.components.FavoritesProfileHeader

/**
 * Main Favorites Screen presenting the list of saved EV charging stations
 * with live availability metrics, sync status, and responsive layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    uiState: FavoritesUiState,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onNavigateClick: (Station) -> Unit,
    onRemoveFavoriteClick: (Station) -> Unit,
    onStationClick: (Station) -> Unit = {},
    selectedStationForDetail: Station? = null,
    onDismissDetail: () -> Unit = {},
    cookieHeader: String? = null,
    stationDetailState: StationDetailUiState = StationDetailUiState(),
    onRefreshDetail: () -> Unit = {},
    onToggleFavoriteDetail: ((Station) -> Unit)? = null,
    onShareDetail: ((Station) -> Unit)? = null,
    routingSettings: RoutingSettings = RoutingSettings(),
    onSaveRoutingSettings: (RoutingSettings) -> Unit = {},
    onValidateGoogleApiKey: (suspend (String) -> Result<Boolean>)? = null,
    authUser: AuthUser? = null,
    onSignInClick: () -> Unit = {},
    onSignOutClick: () -> Unit = onLogout,
    isSyncing: Boolean = false,
    isSigningIn: Boolean = false,
    togglingStationIds: Set<String> = emptySet(),
    isLandscape: Boolean? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showRoutingSettings by remember { mutableStateOf(false) }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var pendingFocusStation by remember { mutableStateOf<Station?>(null) }
    var hasAutoSelected by remember { mutableStateOf(false) }

    val memoizedNavigateClick = remember(onNavigateClick) { onNavigateClick }
    val memoizedRemoveFavoriteClick = remember(onRemoveFavoriteClick) { onRemoveFavoriteClick }
    val memoizedStationClick = remember(onStationClick) { onStationClick }

    val handleStartFocusMode: (Station) -> Unit = remember(context) {
        { st ->
            NativeStationDetailSheetHelper.startFocusMode(context, st)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingFocusStation?.let { handleStartFocusMode(it) }
    }

    val handleStartFocusModeAndNavigate: (Station) -> Unit = remember(handleStartFocusMode, context) {
        { st ->
            pendingFocusStation = st
            if (NativeStationDetailSheetHelper.canDrawOverlays(context)) {
                handleStartFocusMode(st)
            } else {
                showOverlayPermissionDialog = true
            }
        }
    }

    val onShareClick: (Station) -> Unit = remember(onShareDetail, context) {
        { st ->
            if (onShareDetail != null) {
                onShareDetail(st)
            } else {
                NativeStationDetailSheetHelper.launchShare(context, st)
            }
        }
    }

    val activeStationForDetail = stationDetailState.station
        ?: selectedStationForDetail
        ?: (uiState as? FavoritesUiState.Success)?.selectedStationForDetail

    val successStations = (uiState as? FavoritesUiState.Success)?.stations ?: emptyList()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val effectiveIsLandscape = isLandscape ?: AdaptiveLayoutHelper.isLandscapeMode(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value
        )

        val allocation = remember(maxWidth.value) {
            AdaptiveLayoutHelper.calculateMasterDetailWidths(
                totalWidthDp = maxWidth.value,
                navRailWidthDp = 0f
            )
        }

        LaunchedEffect(effectiveIsLandscape, successStations, activeStationForDetail, hasAutoSelected) {
            if (effectiveIsLandscape && !hasAutoSelected && activeStationForDetail == null && successStations.isNotEmpty()) {
                hasAutoSelected = true
                val nearest = AdaptiveLayoutHelper.resolveAutoSelectedStation(
                    isLandscape = true,
                    currentSelection = null,
                    stations = successStations
                )
                if (nearest != null) {
                    memoizedStationClick(nearest)
                }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldContainerDark)
                            ) {
                                Icon(
                                    imageVector = AppIcons.Bolt,
                                    contentDescription = null,
                                    tint = EmeraldPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column {
                                Text(
                                    text = "Trạm Yêu Thích",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    )
                                )

                                if (uiState is FavoritesUiState.Success) {
                                    Text(
                                        text = "${uiState.stations.size} trạm đã lưu",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Routing settings button
                        IconButton(onClick = { showRoutingSettings = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Cài đặt lộ trình",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Refresh action
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Làm mới dữ liệu",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Logout button
                        IconButton(onClick = onLogout) {
                            Icon(
                                imageVector = AppIcons.Logout,
                                contentDescription = "Đăng xuất",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            if (!effectiveIsLandscape) {
                val maxContentWidth = if (maxWidth > 600.dp) 680.dp else maxWidth

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .widthIn(max = maxContentWidth)
                        .align(Alignment.TopCenter)
                ) {
                    when (uiState) {
                        is FavoritesUiState.Loading -> {
                            LoadingContent()
                        }

                        is FavoritesUiState.Error -> {
                            ErrorContent(
                                errorMessage = uiState.message,
                                onRetry = onRefresh
                            )
                        }

                        is FavoritesUiState.Success -> {
                            FavoritesListContent(
                                stations = uiState.stations,
                                isRefreshing = uiState.isRefreshing,
                                onNavigateClick = memoizedNavigateClick,
                                onRemoveFavoriteClick = memoizedRemoveFavoriteClick,
                                onStationClick = memoizedStationClick,
                                onRefresh = onRefresh,
                                authUser = authUser,
                                onSignInClick = onSignInClick,
                                onSignOutClick = onSignOutClick,
                                isSyncing = isSyncing,
                                togglingStationIds = togglingStationIds,
                                selectedStationId = activeStationForDetail?.id,
                                isLandscape = false
                            )
                        }

                        is FavoritesUiState.LoggedOut -> {
                            FavoritesListContent(
                                stations = emptyList(),
                                isRefreshing = false,
                                onNavigateClick = memoizedNavigateClick,
                                onRemoveFavoriteClick = memoizedRemoveFavoriteClick,
                                onStationClick = memoizedStationClick,
                                onRefresh = onRefresh,
                                authUser = authUser,
                                onSignInClick = onSignInClick,
                                onSignOutClick = onSignOutClick,
                                isSyncing = isSyncing,
                                togglingStationIds = togglingStationIds,
                                selectedStationId = activeStationForDetail?.id,
                                isLandscape = false
                            )
                        }

                        is FavoritesUiState.RequestingOtp,
                        is FavoritesUiState.VerifyingOtp -> {
                            // Handled at navigation/host level
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Left Column (Master - 35% to 40%)
                    Box(
                        modifier = Modifier
                            .width(allocation.masterWidthDp.dp)
                            .fillMaxHeight()
                    ) {
                        when (uiState) {
                            is FavoritesUiState.Loading -> {
                                LoadingContent()
                            }

                            is FavoritesUiState.Error -> {
                                ErrorContent(
                                    errorMessage = uiState.message,
                                    onRetry = onRefresh
                                )
                            }

                            is FavoritesUiState.Success -> {
                                FavoritesListContent(
                                    stations = uiState.stations,
                                    isRefreshing = uiState.isRefreshing,
                                    onNavigateClick = memoizedNavigateClick,
                                    onRemoveFavoriteClick = memoizedRemoveFavoriteClick,
                                    onStationClick = memoizedStationClick,
                                    onRefresh = onRefresh,
                                    authUser = authUser,
                                    onSignInClick = onSignInClick,
                                    onSignOutClick = onSignOutClick,
                                    isSyncing = isSyncing,
                                    togglingStationIds = togglingStationIds,
                                    selectedStationId = activeStationForDetail?.id,
                                    isLandscape = true
                                )
                            }

                            is FavoritesUiState.LoggedOut -> {
                                FavoritesListContent(
                                    stations = emptyList(),
                                    isRefreshing = false,
                                    onNavigateClick = memoizedNavigateClick,
                                    onRemoveFavoriteClick = memoizedRemoveFavoriteClick,
                                    onStationClick = memoizedStationClick,
                                    onRefresh = onRefresh,
                                    authUser = authUser,
                                    onSignInClick = onSignInClick,
                                    onSignOutClick = onSignOutClick,
                                    isSyncing = isSyncing,
                                    togglingStationIds = togglingStationIds,
                                    selectedStationId = activeStationForDetail?.id,
                                    isLandscape = true
                                )
                            }

                            is FavoritesUiState.RequestingOtp,
                            is FavoritesUiState.VerifyingOtp -> {
                                // Handled at navigation/host level
                            }
                        }
                    }

                    // Right Column (Detail - 60% to 65%)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    ) {
                        if (activeStationForDetail != null) {
                            val effectiveDetailState = if (stationDetailState.station != null) {
                                stationDetailState
                            } else {
                                stationDetailState.copy(station = activeStationForDetail)
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 8.dp)
                            ) {
                                // Prominent Automotive Action Button: ⚡ DẪN ĐƯỜNG & THEO DÕI
                                Button(
                                    onClick = { handleStartFocusModeAndNavigate(activeStationForDetail) },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = EmeraldPrimary,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                        .height(56.dp)
                                ) {
                                    Icon(
                                        imageVector = AppIcons.Bolt,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "⚡ DẪN ĐƯỜNG & THEO DÕI",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                NativeStationDetailContent(
                                    station = activeStationForDetail,
                                    uiState = effectiveDetailState,
                                    isFavorite = true,
                                    isToggleInProgress = activeStationForDetail.id.let { togglingStationIds.contains(it) },
                                    onRefresh = onRefreshDetail,
                                    onDismiss = onDismissDetail,
                                    onNavigate = memoizedNavigateClick,
                                    onToggleFavorite = onToggleFavoriteDetail ?: memoizedRemoveFavoriteClick,
                                    onShare = onShareClick,
                                    onStartFocusMode = { handleStartFocusModeAndNavigate(it) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            FavoritesDetailEmptyState(
                                isEmptyList = (uiState as? FavoritesUiState.Success)?.stations?.isEmpty() ?: true
                            )
                        }
                    }
                }
            }
        }

        // Station Detail Bottom Sheet (100% Native Jetpack Compose, shown only in portrait mode)
        if (activeStationForDetail != null && !effectiveIsLandscape) {
            val effectiveDetailState = if (stationDetailState.station != null) {
                stationDetailState
            } else {
                stationDetailState.copy(station = activeStationForDetail)
            }
            NativeStationDetailSheet(
                uiState = effectiveDetailState,
                onDismiss = onDismissDetail,
                onRefresh = onRefreshDetail,
                isFavorite = true,
                isToggleInProgress = activeStationForDetail.id.let { togglingStationIds.contains(it) },
                onNavigate = onNavigateClick,
                onToggleFavorite = onToggleFavoriteDetail ?: onRemoveFavoriteClick,
                onShare = onShareDetail
            )
        }

        // Routing & BYOK Settings Bottom Sheet
        if (showRoutingSettings) {
            RoutingSettingsModal(
                settings = routingSettings,
                onSaveSettings = { newSettings ->
                    onSaveRoutingSettings(newSettings)
                },
                onValidateKey = onValidateGoogleApiKey ?: { Result.success(true) },
                onDismiss = { showRoutingSettings = false }
            )
        }

        // Focus Mode Overlay Permission Dialog
        if (showOverlayPermissionDialog) {
            FocusModePermissionDialog(
                onGrantOverlayPermission = {
                    showOverlayPermissionDialog = false
                    NativeStationDetailSheetHelper.openOverlaySettings(context)
                },
                onUseNotificationFallback = {
                    showOverlayPermissionDialog = false
                    if (NativeStationDetailSheetHelper.shouldRequestNotificationPermission(context)) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        pendingFocusStation?.let { handleStartFocusMode(it) }
                    }
                },
                onDismiss = {
                    showOverlayPermissionDialog = false
                }
            )
        }
    }
}

/**
 * Clean empty state placeholder displayed in the detail pane when no favorite station is selected.
 */
@Composable
private fun FavoritesDetailEmptyState(
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

/**
 * Renders the scrollable list of favorite stations or an empty state illustration.
 */
@Composable
private fun FavoritesListContent(
    stations: List<Station>,
    isRefreshing: Boolean,
    onNavigateClick: (Station) -> Unit,
    onRemoveFavoriteClick: (Station) -> Unit,
    onStationClick: (Station) -> Unit,
    onRefresh: () -> Unit,
    authUser: AuthUser? = null,
    onSignInClick: () -> Unit = {},
    onSignOutClick: () -> Unit = {},
    isSyncing: Boolean = false,
    isSigningIn: Boolean = false,
    togglingStationIds: Set<String> = emptySet(),
    selectedStationId: String? = null,
    isLandscape: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isRefreshing,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LinearProgressIndicator(
                color = EmeraldPrimary,
                modifier = Modifier.fillMaxWidth()
            )
        }

        FavoritesProfileHeader(
            authUser = authUser,
            onSignInClick = onSignInClick,
            onSignOutClick = onSignOutClick,
            isSyncing = isSyncing,
            isSigningIn = isSigningIn
        )

        if (stations.isEmpty()) {
            EmptyFavoritesContent(onRefresh = onRefresh)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = stations,
                    key = { it.id },
                    contentType = { "station_card" }
                ) { station ->
                    StationCard(
                        station = station,
                        onNavigateClick = onNavigateClick,
                        onRemoveFavoriteClick = onRemoveFavoriteClick,
                        isToggleInProgress = togglingStationIds.contains(station.id),
                        onStationClick = onStationClick,
                        isSelected = isLandscape && station.id == selectedStationId,
                        isCarMode = isLandscape
                    )
                }
            }
        }
    }
}

/**
 * Animated Loading placeholder.
 */
@Composable
private fun LoadingContent(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = EmeraldPrimary,
            strokeWidth = 3.dp,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Đang tải trạm sạc yêu thích...",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Đang đồng bộ số lượng cổng trống và tính khoảng cách GPS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Clean Empty State Illustration.
 */
@Composable
private fun EmptyFavoritesContent(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                imageVector = AppIcons.EvStation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(52.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Chưa có trạm yêu thích nào",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Bạn có thể thêm các trạm thường xuyên sạc vào danh sách yêu thích trên hệ thống EVCS.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onRefresh,
            colors = ButtonDefaults.buttonColors(
                containerColor = EmeraldPrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Làm mới danh sách",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

/**
 * Error state with retry action.
 */
@Composable
private fun ErrorContent(
    errorMessage: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = AppIcons.CloudOff,
            contentDescription = null,
            tint = StatusOffline,
            modifier = Modifier.size(56.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Không thể tải dữ liệu",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = EmeraldPrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Thử lại",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}
