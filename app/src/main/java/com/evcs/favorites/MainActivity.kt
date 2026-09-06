package com.evcs.favorites

import com.evcs.favorites.data.model.Station

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.evcs.favorites.ui.theme.AppIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.EncryptedSharedPrefsStorage
import com.evcs.favorites.data.auth.PlainSharedPrefsStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import com.evcs.favorites.data.network.vinfast.VinFastCAppApiClient
import com.evcs.favorites.data.network.vinfast.VinFastDeviceIdProvider
import com.evcs.favorites.data.network.vinfast.VinFastHeaderInterceptor
import com.evcs.favorites.data.network.vinfast.VinFastStationMapper
import com.evcs.favorites.data.repository.DualTierStationRepository
import java.util.concurrent.TimeUnit
import com.evcs.favorites.data.preferences.NearbyFilterPreferences
import com.evcs.favorites.data.preferences.SmartFilterPreferences
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.navigation.AppNavigationBar
import com.evcs.favorites.navigation.AppTab
import com.evcs.favorites.navigation.MapNavigator
import com.evcs.favorites.ui.screens.FavoritesScreen
import com.evcs.favorites.ui.screens.LoginScreen
import com.evcs.favorites.ui.screens.NearbyScreen
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.theme.EvcsFavoritesTheme
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import com.evcs.favorites.ui.viewmodel.NearbyViewModel

/**
 * Main application entry point for EVCS Favorites & Nearby Charging Stations.
 * Coordinates dependency graph, runtime location permissions, bottom navigation,
 * and Compose screen hierarchy.
 */
class MainActivity : ComponentActivity() {

    private val sessionManager by lazy { SessionManager.create(applicationContext) }
    private val apiClient by lazy { EvcsApiClient(sessionManager) }
    private val authService by lazy { com.evcs.favorites.data.auth.FirebaseAuthManager() }
    private val firestoreDataSource by lazy { com.evcs.favorites.data.repository.FirestoreFavoritesDataSource.create() }
    val firestoreFavoritesRepository by lazy {
        com.evcs.favorites.data.repository.FirestoreFavoritesRepository(
            remoteDataSource = firestoreDataSource,
            localStorage = PlainSharedPrefsStorage.getInstance(applicationContext),
            authService = authService
        )
    }
    private val vinFastHeaderInterceptor by lazy {
        VinFastHeaderInterceptor(
            deviceIdProvider = { VinFastDeviceIdProvider.getDeviceId(applicationContext) }
        )
    }
    private val vinFastApiClient by lazy {
        val okHttpClient = AppOkHttpClientProvider.newSharedClientBuilder()
            .connectTimeout(VinFastCAppApiClient.DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(VinFastCAppApiClient.DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(vinFastHeaderInterceptor)
            .build()
        VinFastCAppApiClient(
            client = okHttpClient,
            headerInterceptor = vinFastHeaderInterceptor
        )
    }
    private val vinFastStationMapper by lazy { VinFastStationMapper }
    private val repository: DualTierStationRepository by lazy {
        DualTierStationRepository(
            apiClient = apiClient,
            vinFastApiClient = vinFastApiClient,
            stationMapper = vinFastStationMapper,
            cacheStorage = PlainSharedPrefsStorage.getInstance(applicationContext),
            legacyStorage = EncryptedSharedPrefsStorage.getInstance(applicationContext),
            autoResolveCoordinates = true,
            firestoreFavoritesRepository = firestoreFavoritesRepository
        )
    }
    private val authEngine by lazy { AuthEngine(sessionManager) }
    private val locationService by lazy { LocationService(applicationContext) }
    private val routingPreferencesManager by lazy { RoutingPreferencesManager.create(applicationContext) }
    private val nearbyFilterPreferences by lazy { NearbyFilterPreferences.create(applicationContext) }
    private val smartFilterPreferences by lazy { SmartFilterPreferences.create(applicationContext) }
    private val telemetryRepository by lazy {
        com.evcs.favorites.data.repository.EvcsTelemetryRepository(
            com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource(sessionManager)
        )
    }

    private val favoritesViewModel by viewModels<FavoritesViewModel> {
        FavoritesViewModel.provideFactory(
            repository = repository,
            authEngine = authEngine,
            authService = authService,
            locationService = locationService,
            routingPreferencesManager = routingPreferencesManager,
            telemetryRepository = telemetryRepository
        )
    }

    private val nearbyViewModel by viewModels<NearbyViewModel> {
        NearbyViewModel.provideFactory(
            repository = repository,
            sessionManager = sessionManager,
            locationService = locationService,
            routingPreferencesManager = routingPreferencesManager,
            filterPreferences = nearbyFilterPreferences,
            smartFilterPreferences = smartFilterPreferences,
            telemetryRepository = telemetryRepository
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch(Dispatchers.IO) {
            EncryptedSharedPrefsStorage.getInstance(applicationContext).warmUp()
            PlainSharedPrefsStorage.getInstance(applicationContext).warmUp()
            repository.initializeAsync()
        }

        setContent {
            EvcsFavoritesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FavoritesApp(
                        viewModel = favoritesViewModel,
                        nearbyViewModel = nearbyViewModel,
                        locationService = locationService
                    )
                }
            }
        }
    }
}

/**
 * Root Composable orchestrating authentication state, bottom navigation bar,
 * location permission rationale, and navigation transitions between
 * [LoginScreen], [FavoritesScreen], and [NearbyScreen].
 */
@Composable
fun FavoritesApp(
    viewModel: FavoritesViewModel,
    nearbyViewModel: NearbyViewModel? = null,
    locationService: LocationService? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val routingSettings by viewModel.routingSettings.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(AppTab.NEARBY) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var rationaleDismissed by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            viewModel.refresh()
        }
    }

    // Check location permissions when user enters Favorites screen
    LaunchedEffect(isLoggedIn, currentTab) {
        if (isLoggedIn && currentTab == AppTab.FAVORITES) {
            val hasPerm = locationService?.hasLocationPermission() ?: false
            if (!hasPerm && !rationaleDismissed) {
                showPermissionRationale = true
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            AppNavigationBar(
                currentTab = currentTab,
                onTabSelected = { currentTab = it }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            when (currentTab) {
                AppTab.FAVORITES -> {
                    val selectedStation by viewModel.selectedStationForDetail.collectAsStateWithLifecycle()
                    val stationDetailState by viewModel.stationDetailState.collectAsStateWithLifecycle()
                    val authState by viewModel.authState.collectAsStateWithLifecycle()
                    val authUser = (authState as? com.evcs.favorites.domain.model.AuthState.Authenticated)?.user ?: viewModel.currentUser
                    val activity = context as? ComponentActivity

                    val onNavigate: (Station) -> Unit = remember(context) {
                        { station: Station ->
                            MapNavigator.navigate(
                                context = context,
                                latitude = station.latitude,
                                longitude = station.longitude,
                                stationName = station.name
                            )
                        }
                    }
                    val onRemoveFavorite: (Station) -> Unit = remember(viewModel) {
                        { station: Station ->
                            viewModel.removeFavorite(station.id)
                            Unit
                        }
                    }
                    val onStationClick: (Station) -> Unit = remember(viewModel) {
                        { station: Station ->
                            viewModel.selectStationForDetail(station)
                        }
                    }

                    FavoritesScreen(
                        uiState = uiState,
                        onRefresh = { viewModel.refresh() },
                        onLogout = { viewModel.logout(activity) },
                        onNavigateClick = onNavigate,
                        onRemoveFavoriteClick = onRemoveFavorite,
                        onStationClick = onStationClick,
                        selectedStationForDetail = selectedStation,
                        onDismissDetail = {
                            viewModel.dismissStationDetail()
                        },
                        stationDetailState = stationDetailState,
                        onRefreshDetail = {
                            viewModel.refreshStationDetail()
                        },
                        cookieHeader = viewModel.getCookieHeader(),
                        routingSettings = routingSettings,
                        onSaveRoutingSettings = { viewModel.updateRoutingSettings(it) },
                        onValidateGoogleApiKey = { viewModel.validateGoogleApiKey(it) },
                        authUser = authUser,
                        onSignInClick = {
                            activity?.let { act ->
                                act.lifecycleScope.launch {
                                    viewModel.authService?.signInWithGoogle(act)
                                }
                            }
                        },
                        onSignOutClick = {
                            activity?.let { act ->
                                act.lifecycleScope.launch {
                                    viewModel.authService?.signOut(act)
                                }
                            }
                        }
                    )
                }

                AppTab.NEARBY -> {
                    if (nearbyViewModel != null) {
                        NearbyScreen(
                            viewModel = nearbyViewModel,
                            onNavigateToLogin = {
                                currentTab = AppTab.FAVORITES
                            },
                            cookieHeader = viewModel.getCookieHeader(),
                            routingSettings = routingSettings,
                            onSaveRoutingSettings = { nearbyViewModel.updateRoutingSettings(it) },
                            onValidateGoogleApiKey = { nearbyViewModel.validateGoogleApiKey(it) }
                        )
                    }
                }
            }
        }

        // Location Permission Rationale Dialog (Favorites screen)
        if (showPermissionRationale) {
            AlertDialog(
                onDismissRequest = {
                    showPermissionRationale = false
                    rationaleDismissed = true
                },
                icon = {
                    Icon(
                        imageVector = AppIcons.NearMe,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = "Cho phép truy cập vị trí",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                text = {
                    Text(
                        text = "EV+ cần quyền vị trí để tự động tính khoảng cách từ bạn đến từng trạm sạc và dẫn đường nhanh chóng.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showPermissionRationale = false
                            rationaleDismissed = true
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldPrimary,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cho phép", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showPermissionRationale = false
                            rationaleDismissed = true
                        }
                    ) {
                        Text("Để sau", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}
