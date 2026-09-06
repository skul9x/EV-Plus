package com.evcs.favorites.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.navigation.MapNavigator
import com.evcs.favorites.ui.components.CustomConfigPromptDialog
import com.evcs.favorites.ui.components.LoginRequiredDialog
import com.evcs.favorites.ui.components.NativeStationDetailSheet
import com.evcs.favorites.ui.components.RoutingSettingsModal
import com.evcs.favorites.ui.components.SmartFilterBar
import com.evcs.favorites.ui.components.StationCard
import com.evcs.favorites.ui.state.NearbyUiEvent
import com.evcs.favorites.ui.state.NearbyUiState
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.launch

/**
 * Nearby Charging Stations screen presenting:
 * - On-demand GPS scan hero button
 * - Phased loading indicators (GPS -> Search -> Top 10 Multi-Tier Routing)
 * - Sticky wattage filter chips row
 * - Top 10 nearest VinFast stations with live slot availability, driving metrics & ETA
 * - Heart favorite button with 2-way cloud sync and authentication guard
 * - Routing settings and station detail modals
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    viewModel: NearbyViewModel,
    onNavigateToLogin: () -> Unit = {},
    cookieHeader: String? = null,
    routingSettings: RoutingSettings = RoutingSettings(),
    onSaveRoutingSettings: (RoutingSettings) -> Unit = {},
    onValidateGoogleApiKey: (suspend (String) -> Result<Boolean>)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stationDetailState by viewModel.stationDetailState.collectAsStateWithLifecycle()

    var showRoutingSettings by remember { mutableStateOf(false) }
    var showLoginRequiredDialog by remember { mutableStateOf(false) }

    val onNavigateClick: (Station) -> Unit = remember(context) {
        { station: Station ->
            MapNavigator.navigate(
                context = context,
                latitude = station.latitude,
                longitude = station.longitude,
                stationName = station.name
            )
        }
    }
    val onFavoriteClick: (Station) -> Unit = remember(viewModel) {
        { station: Station ->
            viewModel.toggleFavorite(station)
            Unit
        }
    }
    val onStationClick: (Station) -> Unit = remember(viewModel) {
        { station: Station ->
            viewModel.selectStationForDetail(station)
        }
    }
    val onClearFilters: () -> Unit = remember(viewModel) {
        {
            viewModel.clearSmartFilter()
            Unit
        }
    }
    val onCustomFilterClick: () -> Unit = remember(viewModel) {
        {
            viewModel.applyCustomFilter()
            Unit
        }
    }
    val onDcFilterClick: () -> Unit = remember(viewModel) {
        {
            viewModel.enterDcMode()
            Unit
        }
    }
    val onAcFilterClick: () -> Unit = remember(viewModel) {
        {
            viewModel.toggleAcFilter()
            Unit
        }
    }
    val onSelectDcTier: (DcWattageTier) -> Unit = remember(viewModel) {
        { tier: DcWattageTier ->
            viewModel.selectDcTier(tier)
            Unit
        }
    }
    val onBackFromDc: () -> Unit = remember(viewModel) {
        {
            viewModel.exitDcMode()
            Unit
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            viewModel.scanNearbyStations()
        } else {
            coroutineScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Ứng dụng cần quyền vị trí để tìm trạm sạc gần bạn",
                    actionLabel = "Cài đặt",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback gracefully
                    }
                }
            }
        }
    }

    // Collect one-shot events (auth guard, toasts, permission prompt)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is NearbyUiEvent.RequestLocationPermission -> {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
                is NearbyUiEvent.ShowLoginRequired -> {
                    showLoginRequiredDialog = true
                }
                is NearbyUiEvent.ShowToast -> {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = event.message,
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            }
        }
    }

    // Handle error message banner
    LaunchedEffect(uiState.errorMessage) {
        val error = uiState.errorMessage
        if (!error.isNullOrBlank()) {
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(EmeraldContainerDark)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Text(
                                text = "Trạm sạc quanh đây",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )

                            if (uiState.hasSearched) {
                                Text(
                                    text = "${uiState.top10DisplayStations.size} trạm gần nhất",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                actions = {
                    // Settings gear icon
                    IconButton(onClick = { showRoutingSettings = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Cài đặt lộ trình",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Refresh icon (visible when results exist)
                    if (uiState.hasSearched) {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Làm mới dữ liệu",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val maxContentWidth = if (maxWidth > 600.dp) 680.dp else maxWidth

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = maxContentWidth)
                    .align(Alignment.TopCenter)
            ) {
                when {
                    // 1. Initial State (before search)
                    !uiState.hasSearched && !uiState.isLocating && !uiState.isSearching -> {
                        NearbyInitialHeroContent(
                            uiState = uiState,
                            onScanClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            },
                            onCustomFilterClick = onCustomFilterClick,
                            onDcFilterClick = onDcFilterClick,
                            onAcFilterClick = onAcFilterClick,
                            onSelectDcTier = onSelectDcTier,
                            onBackFromDc = onBackFromDc,
                            onClearFilters = onClearFilters
                        )
                    }

                    // 2. Initial Full Loading State (GPS locating or raw station search)
                    !uiState.hasSearched && (uiState.isLocating || uiState.isSearching) -> {
                        NearbyLoadingContent(uiState = uiState)
                    }

                    // 3. Result State (hasSearched == true)
                    uiState.hasSearched -> {
                        NearbyResultContent(
                            uiState = uiState,
                            onCustomFilterClick = onCustomFilterClick,
                            onDcFilterClick = onDcFilterClick,
                            onAcFilterClick = onAcFilterClick,
                            onSelectDcTier = onSelectDcTier,
                            onBackFromDc = onBackFromDc,
                            onClearFilters = onClearFilters,
                            onFavoriteClick = onFavoriteClick,
                            onNavigateClick = onNavigateClick,
                            onStationClick = onStationClick
                        )
                    }
                }
            }
        }

        // Station Detail Bottom Sheet (100% Native Jetpack Compose)
        if (stationDetailState.station != null) {
            val isCurrentStationFavorite = uiState.favoriteStationIds.contains(stationDetailState.station?.id)
            NativeStationDetailSheet(
                uiState = stationDetailState,
                onDismiss = { viewModel.dismissStationDetail() },
                onRefresh = { viewModel.refreshStationDetail() },
                isFavorite = isCurrentStationFavorite,
                onNavigate = onNavigateClick,
                onToggleFavorite = onFavoriteClick
            )
        }

        // Routing & BYOK Settings Bottom Sheet Modal
        if (showRoutingSettings) {
            RoutingSettingsModal(
                settings = routingSettings,
                onSaveSettings = { newSettings ->
                    onSaveRoutingSettings(newSettings)
                },
                onValidateKey = onValidateGoogleApiKey ?: { Result.success(true) },
                onDismiss = { showRoutingSettings = false },
                customFilterConfig = uiState.savedCustomConfig,
                onSaveCustomFilter = { config ->
                    viewModel.saveAndApplyCustomFilter(config)
                }
            )
        }

        // Custom Filter Configuration Prompt Dialog
        if (uiState.showCustomConfigPrompt) {
            CustomConfigPromptDialog(
                onConfirmSetup = {
                    viewModel.dismissCustomPrompt()
                    showRoutingSettings = true
                },
                onDismiss = {
                    viewModel.dismissCustomPrompt()
                }
            )
        }

        // Login Required Dialog
        if (showLoginRequiredDialog) {
            LoginRequiredDialog(
                onConfirmLogin = {
                    showLoginRequiredDialog = false
                    onNavigateToLogin()
                },
                onDismiss = {
                    showLoginRequiredDialog = false
                }
            )
        }
    }
}

/**
 * Initial Hero view displayed before user performs a GPS search.
 */
@Composable
private fun NearbyInitialHeroContent(
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
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Smart Filter Bar with 3-button selector & animated DC sub-filter
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
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(EmeraldContainerDark.copy(alpha = 0.6f))
            ) {
                Icon(
                    imageVector = AppIcons.NearMe,
                    contentDescription = null,
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Tìm trạm sạc quanh đây",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Quét các trạm sạc VinFast gần bạn nhất còn cổng trống với khoảng cách và thời gian lái xe thực tế.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onScanClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 10.dp
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Icon(
                    imageVector = AppIcons.NearMe,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Nhấn để tìm trạm quanh đây",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                )
            }
        }
    }
}

/**
 * Animated Loading placeholder with phased status text.
 */
@Composable
private fun NearbyLoadingContent(
    uiState: NearbyUiState,
    modifier: Modifier = Modifier
) {
    val loadingText = when {
        uiState.isLocating -> "Đang xác định vị trí GPS..."
        uiState.isSearching -> "Đang tải dữ liệu trạm sạc..."
        uiState.isRoutingLoading -> "Đang tính toán lộ trình Top 10..."
        else -> "Đang tải dữ liệu..."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = EmeraldPrimary,
            strokeWidth = 3.dp,
            modifier = Modifier.size(52.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = loadingText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Vui lòng giữ kết nối GPS và mạng internet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Result Content displaying SmartFilterBar, dynamic info pill, and scrollable Top 10 list.
 */
@Composable
private fun NearbyResultContent(
    uiState: NearbyUiState,
    onCustomFilterClick: () -> Unit,
    onDcFilterClick: () -> Unit,
    onAcFilterClick: () -> Unit,
    onSelectDcTier: (DcWattageTier) -> Unit,
    onBackFromDc: () -> Unit,
    onClearFilters: () -> Unit,
    onFavoriteClick: (Station) -> Unit,
    onNavigateClick: (Station) -> Unit,
    onStationClick: (Station) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Smart Filter Bar with 3-button selector & animated DC sub-filter
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
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
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

        // Header info pill: Dynamic feedback reflecting active filter and station count
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(EmeraldContainerDark.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = uiState.filterSummaryPillText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = EmeraldPrimary
                    )
                )
            }

            if (uiState.isRoutingLoading) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = EmeraldPrimary
                )
            }
        }

        // Content: Empty State vs Stations List
        if (uiState.top10DisplayStations.isEmpty()) {
            NearbyEmptyFilterContent(onClearFilters = onClearFilters)
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        onStationClick = onStationClick
                    )
                }
            }
        }
    }
}

/**
 * Clean Empty State when all stations are filtered out by selected wattage options.
 */
@Composable
private fun NearbyEmptyFilterContent(
    onClearFilters: () -> Unit,
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
                .size(90.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                imageVector = AppIcons.FilterListOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(46.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Không có trạm sạc nào phù hợp với bộ lọc công suất",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Thử chọn các mức công suất khác hoặc xóa bộ lọc để hiển thị toàn bộ các trạm quanh đây.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onClearFilters,
            colors = ButtonDefaults.buttonColors(
                containerColor = EmeraldPrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Xóa bộ lọc",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}
