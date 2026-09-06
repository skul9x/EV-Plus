package com.evcs.favorites.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.cache.BoundedLruMap
import com.evcs.favorites.data.auth.AuthService
import com.evcs.favorites.domain.model.AuthState
import com.evcs.favorites.domain.model.AuthUser
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.repository.EvcsTelemetryRepository
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.ui.state.StationDetailUiState
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.state.FavoritesUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/**
 * ViewModel managing the state of EVCS Favorites, Email OTP authentication flow,
 * and the 2-step hybrid multi-tier routing pipeline with shortest-ETA sorting.
 *
 * Exposes a reactive [FavoritesUiState] via [uiState] StateFlow to drive Compose UI.
 */
class FavoritesViewModel(
    private val repository: EvcsRepository,
    private val authEngine: AuthEngine,
    val authService: AuthService? = null,
    private val locationService: LocationService? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val routingPreferencesManager: RoutingPreferencesManager? = null,
    private val routingCoordinator: MultiTierRoutingCoordinator = MultiTierRoutingCoordinator(),
    telemetryRepository: EvcsTelemetryRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = ioDispatcher
) : ViewModel() {

    val authState: StateFlow<AuthState> = authService?.authState ?: MutableStateFlow(AuthState.Unauthenticated)
    val currentUser: AuthUser? get() = authService?.currentUser

    private val telemetryRepo: EvcsTelemetryRepository = telemetryRepository ?: EvcsTelemetryRepository(
        dataSource = EvcsTelemetryDataSource(sessionManager = authEngine.sessionManager, ioDispatcher = ioDispatcher),
        ioDispatcher = ioDispatcher
    )

    val stationDetailCoordinator: StationDetailCoordinator = StationDetailCoordinator(
        coroutineScope = viewModelScope,
        telemetryRepository = telemetryRepo,
        ioDispatcher = ioDispatcher,
        mainDispatcher = dispatcher
    )

    val stationDetailState: StateFlow<StationDetailUiState> = stationDetailCoordinator.stationDetailState

    private val _uiState = MutableStateFlow<FavoritesUiState>(FavoritesUiState.Loading)
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()
    val isLoggedIn: StateFlow<Boolean> = authEngine.isLoggedIn

    private val _selectedStationForDetail = MutableStateFlow<Station?>(null)
    val selectedStationForDetail: StateFlow<Station?> = _selectedStationForDetail.asStateFlow()

    private var pendingEmail: String = ""

    /**
     * Routing Preferences Manager instance for BYOK settings and engine arbitration.
     */
    private val prefsManager: RoutingPreferencesManager =
        routingPreferencesManager ?: RoutingPreferencesManager(storage = InMemorySessionStorage())

    /**
     * Observes real-time routing preferences.
     */
    val routingSettings: StateFlow<RoutingSettings> = prefsManager.settings

    /**
     * In-memory cache for candidate routing metrics.
     */
    private var cachedOriginLat: Double? = null
    private var cachedOriginLon: Double? = null
    private var cacheTimestamp: Long = 0L
    private val routingCache = BoundedLruMap<String, DrivingMetrics>(maxCapacity = 100)
    private var currentCoordinates: Pair<Double, Double>? = null
    private var lastProcessedCoordinates: Pair<Double, Double>? = null

    /**
     * Time provider hook to facilitate deterministic time-advance unit tests.
     */
    internal var timeProvider: () -> Long = { System.currentTimeMillis() }

    /**
     * Active background routing job.
     */
    var routingJob: Job? = null
        private set

    /**
     * Managed background favorites loading job (consolidating initial load and refresh).
     */
    var favoritesLoadJob: Job? = null
        private set

    /**
     * Backward-compatible alias for initialLoadJob pointing to favoritesLoadJob.
     */
    var initialLoadJob: Job?
        get() = favoritesLoadJob
        private set(value) {
            favoritesLoadJob = value
        }

    /**
     * The active or pending email associated with the login session.
     */
    val email: String
        get() = pendingEmail.ifBlank { authEngine.sessionManager.userEmail.orEmpty() }

    init {
        favoritesLoadJob = viewModelScope.launch(ioDispatcher) {
            if (repository.firestoreFavoritesRepository != null) {
                doFetchFavorites()
            } else {
                val loggedIn = authEngine.checkLoggedInAsync(ioDispatcher)
                if (loggedIn) {
                    doFetchFavorites()
                } else {
                    _uiState.value = FavoritesUiState.LoggedOut
                }
            }
        }
        if (locationService != null) {
            viewModelScope.launch(dispatcher) {
                locationService.locationState.collect { location ->
                    if (location != null) {
                        updateUserLocation(location.latitude, location.longitude)
                    }
                }
            }
        }

        // Observe shared repository favorites state for two-way synchronization
        viewModelScope.launch(dispatcher) {
            repository.favoritesState.collect { repoStations ->
                val coords = locationService?.latestCoordinates ?: currentCoordinates
                val userLat = coords?.first
                val userLon = coords?.second
                if (coords != null) {
                    currentCoordinates = coords
                }

                var hasNewStations = false
                var stationsToEnrich: List<Station>? = null

                _uiState.update { currentState ->
                    hasNewStations = false
                    stationsToEnrich = null
                    if (currentState !is FavoritesUiState.Success) {
                        return@update currentState
                    }

                    val existingMap = currentState.stations.associateBy { it.id }
                    val currentIds = existingMap.keys
                    val repoIds = repoStations.map { it.id }.toSet()

                    val removedIds = currentIds - repoIds
                    removedIds.forEach { routingCache.remove(it) }

                    if ((repoIds - currentIds).isNotEmpty()) {
                        hasNewStations = true
                    }

                    val mergedStations = repoStations.map { repoStation ->
                        val existing = existingMap[repoStation.id]
                        if (existing != null) {
                            repoStation.copy(
                                drivingMetrics = existing.drivingMetrics ?: repoStation.drivingMetrics,
                                distanceKm = existing.distanceKm ?: repoStation.distanceKm
                            )
                        } else {
                            repoStation
                        }
                    }

                    val stationsWithDistances = if (userLat != null && userLon != null) {
                        DistanceCalculator.attachDistances(mergedStations, userLat, userLon)
                    } else {
                        mergedStations
                    }

                    val sortedStations = sortStations(stationsWithDistances)
                    stationsToEnrich = sortedStations

                    currentState.copy(
                        stations = sortedStations,
                        selectedStationForDetail = sortedStations.find { it.id == currentState.selectedStationForDetail?.id }
                    )
                }

                if (_selectedStationForDetail.value != null &&
                    stationsToEnrich?.none { it.id == _selectedStationForDetail.value?.id } == true
                ) {
                    _selectedStationForDetail.value = null
                }

                if (hasNewStations && stationsToEnrich != null) {
                    if (userLat != null && userLon != null) {
                        executeRoutingPipeline(
                            stations = stationsToEnrich!!,
                            userLat = userLat,
                            userLon = userLon,
                            forceRefresh = false
                        )
                    }
                }
            }
        }
    }

    /**
     * Initiates Email OTP request.
     * Transitions state to [FavoritesUiState.RequestingOtp].
     */
    fun requestOtp(emailAddress: String): Job {
        val trimmedEmail = emailAddress.trim()
        pendingEmail = trimmedEmail
        _uiState.value = FavoritesUiState.RequestingOtp

        return viewModelScope.launch(dispatcher) {
            val result = authEngine.sendOtp(trimmedEmail)
            if (result.isSuccess) {
                _uiState.value = FavoritesUiState.RequestingOtp
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Gửi mã OTP thất bại"
                _uiState.value = FavoritesUiState.Error(errorMsg)
            }
        }
    }

    /**
     * Verifies the entered OTP numeric code.
     * Transitions state to [FavoritesUiState.VerifyingOtp] and, upon success, loads favorites.
     */
    fun verifyOtp(otpCode: String, explicitEmail: String? = null): Job {
        val trimmedOtp = otpCode.trim()
        val targetEmail = explicitEmail?.trim()?.ifBlank { null }
            ?: pendingEmail.ifBlank { authEngine.sessionManager.userEmail.orEmpty() }

        _uiState.value = FavoritesUiState.VerifyingOtp

        return viewModelScope.launch(dispatcher) {
            val result = authEngine.verifyOtp(targetEmail, trimmedOtp)
            if (result.isSuccess) {
                fetchFavorites().join()
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Xác thực mã OTP không thành công"
                _uiState.value = FavoritesUiState.Error(errorMsg)
            }
        }
    }

    /**
     * Core favorites fetching and station distance sorting logic.
     */
    suspend fun doFetchFavorites() {
        _uiState.value = FavoritesUiState.Loading
        try {
            val coords = locationService?.latestCoordinates ?: currentCoordinates
            val userLat = coords?.first
            val userLon = coords?.second
            if (coords != null) {
                currentCoordinates = coords
            }

            val result = repository.getFavorites(userLat, userLon)
            if (result.isSuccess) {
                val stations = result.getOrThrow()

                // Step 1: Immediate 0ms Haversine sort
                val step1Stations = if (userLat != null && userLon != null) {
                    withContext(defaultDispatcher) {
                        DistanceCalculator.sortByDistance(stations, userLat, userLon)
                    }
                } else {
                    stations
                }

                _uiState.value = FavoritesUiState.Success(
                    stations = step1Stations,
                    selectedStationForDetail = _selectedStationForDetail.value
                )

                // Step 2: Trigger async coordinator routing if GPS coordinates exist
                if (userLat != null && userLon != null) {
                    executeRoutingPipeline(
                        stations = step1Stations,
                        userLat = userLat,
                        userLon = userLon,
                        forceRefresh = false
                    )
                }
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Không thể tải danh sách trạm sạc yêu thích"
                _uiState.value = FavoritesUiState.Error(errorMsg)
            }
        } catch (e: Exception) {
            _uiState.value = FavoritesUiState.Error(e.message ?: "Đã xảy ra lỗi không mong muốn")
        }
    }

    /**
     * Loads user's favorite stations from the EVCS repository.
     *
     * Implements 2-step hybrid pipeline:
     * - Step 1: Immediate 0ms Haversine calculation and sort.
     * - Step 2: Asynchronous candidate batch routing via MultiTierRoutingCoordinator.
     */
    fun fetchFavorites(): Job {
        favoritesLoadJob?.cancel()
        routingJob?.cancel()
        val job = viewModelScope.launch(dispatcher) {
            doFetchFavorites()
        }
        favoritesLoadJob = job
        return job
    }

    /**
     * Refreshes favorite stations in the background (pull-to-refresh).
     * Invalidates in-memory routing cache and re-queries coordinator.
     */
    fun refresh(): Job {
        val currentState = _uiState.value
        if (currentState is FavoritesUiState.Success) {
            _uiState.value = currentState.copy(isRefreshing = true)
        } else {
            _uiState.value = FavoritesUiState.Loading
        }

        invalidateRoutingCache()
        favoritesLoadJob?.cancel()
        routingJob?.cancel()

        val job = viewModelScope.launch(dispatcher) {
            try {
                locationService?.getFreshLocation()
                val coords = locationService?.latestCoordinates ?: currentCoordinates
                val userLat = coords?.first
                val userLon = coords?.second
                if (coords != null) {
                    currentCoordinates = coords
                }

                val result = repository.getFavorites(userLat, userLon)
                if (result.isSuccess) {
                    val stations = result.getOrThrow()
                    val step1Stations = if (userLat != null && userLon != null) {
                        withContext(defaultDispatcher) {
                            DistanceCalculator.sortByDistance(stations, userLat, userLon)
                        }
                    } else {
                        stations
                    }

                    _uiState.value = FavoritesUiState.Success(
                        stations = step1Stations,
                        isRefreshing = false,
                        selectedStationForDetail = _selectedStationForDetail.value
                    )

                    if (userLat != null && userLon != null) {
                        executeRoutingPipeline(
                            stations = step1Stations,
                            userLat = userLat,
                            userLon = userLon,
                            forceRefresh = true
                        )
                    }
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "Không thể làm mới danh sách trạm"
                    _uiState.value = FavoritesUiState.Error(errorMsg)
                }
            } catch (e: Exception) {
                _uiState.value = FavoritesUiState.Error(e.message ?: "Lỗi làm mới dữ liệu")
            }
        }
        favoritesLoadJob = job
        return job
    }

    /**
     * Alias for [refresh] matching workflow conventions.
     */
    fun refreshFavorites(): Job = refresh()

    /**
     * Step 2: Asynchronous candidate batch routing and ETA sorting pipeline.
     *
     * Extracts candidates (up to 10 nearest valid stations), queries coordinator or cache,
     * enriches candidate stations with DrivingMetrics, and sorts with dual comparator:
     * Shortest ETA arrival time first, followed by Haversine straight-line distance.
     */
    fun executeRoutingPipeline(
        stations: List<Station>,
        userLat: Double,
        userLon: Double,
        forceRefresh: Boolean = false
    ): Job {
        routingJob?.cancel()

        val job = viewModelScope.launch(ioDispatcher) {
            val validStations = stations.filterNot { it.latitude == 0.0 && it.longitude == 0.0 }
            if (validStations.isEmpty()) return@launch

            val candidateCount = minOf(validStations.size, MAX_CANDIDATE_STATIONS)
            val sortedByHaversine = withContext(defaultDispatcher) {
                DistanceCalculator.sortByDistance(validStations, userLat, userLon)
            }
            val candidates = sortedByHaversine.take(candidateCount)

            val candidateDestinations = candidates.map {
                RoutingDestination(
                    id = it.id,
                    latitude = it.latitude,
                    longitude = it.longitude
                )
            }

            val cacheHit = !forceRefresh &&
                isCacheValid(userLat, userLon) &&
                candidates.all { routingCache.containsKey(it.id) }

            val metricsMap: Map<String, DrivingMetrics> = if (cacheHit) {
                candidates.mapNotNull { st -> routingCache[st.id]?.let { st.id to it } }.toMap()
            } else {
                val routes = routingCoordinator.calculateRoutes(
                    originLat = userLat,
                    originLng = userLon,
                    destinations = candidateDestinations,
                    settings = prefsManager.settings.value
                )

                if (!isCacheValid(userLat, userLon) || forceRefresh) {
                    routingCache.clear()
                }
                routingCache.putAll(routes)
                cachedOriginLat = userLat
                cachedOriginLon = userLon
                cacheTimestamp = timeProvider()
                routes
            }

            val enrichedStations = stations.map { station ->
                val metrics = metricsMap[station.id]
                if (metrics != null) {
                    station.copy(drivingMetrics = metrics)
                } else {
                    station
                }
            }

            val sortedStations = withContext(defaultDispatcher) {
                sortStations(enrichedStations)
            }

            _uiState.update { currentState ->
                if (currentState is FavoritesUiState.Success) {
                    currentState.copy(
                        stations = sortedStations,
                        selectedStationForDetail = sortedStations.find { it.id == currentState.selectedStationForDetail?.id }
                            ?: currentState.selectedStationForDetail
                    )
                } else {
                    currentState
                }
            }
        }

        routingJob = job
        return job
    }

    /**
     * Refreshes routing calculations for current stations using active coordinates.
     */
    fun refreshRouting(): Job {
        val currentState = _uiState.value
        if (currentState is FavoritesUiState.Success) {
            val coords = locationService?.latestCoordinates ?: currentCoordinates
            val userLat = coords?.first
            val userLon = coords?.second
            if (userLat != null && userLon != null) {
                return executeRoutingPipeline(
                    stations = currentState.stations,
                    userLat = userLat,
                    userLon = userLon,
                    forceRefresh = true
                )
            }
        }
        return Job().apply { complete() }
    }

    /**
     * Updates routing preferences, invalidates routing cache, and triggers a fresh routing calculation.
     */
    fun updateRoutingSettings(settings: RoutingSettings): Job {
        prefsManager.saveSettings(settings)
        invalidateRoutingCache()
        return refreshRouting()
    }

    /**
     * Proactively validates a Google Cloud Routes API key.
     */
    suspend fun validateGoogleApiKey(key: String): Result<Boolean> {
        return prefsManager.validateGoogleApiKey(key)
    }

    /**
     * Updates user location, handling GPS displacement invalidation (> 200m).
     */
    fun updateUserLocation(latitude: Double, longitude: Double): Job {
        if (lastProcessedCoordinates != null) {
            val displacement = DistanceCalculator.calculateDistanceMeters(
                lastProcessedCoordinates!!.first,
                lastProcessedCoordinates!!.second,
                latitude,
                longitude
            )
            if (displacement <= MIN_DISPLACEMENT_METERS) {
                return Job().apply { complete() }
            }
            if (displacement <= MAX_DISPLACEMENT_METERS && isCacheValid(latitude, longitude)) {
                currentCoordinates = Pair(latitude, longitude)
                lastProcessedCoordinates = Pair(latitude, longitude)
                return Job().apply { complete() }
            }
        }

        val previousCoords = currentCoordinates
        lastProcessedCoordinates = Pair(latitude, longitude)
        currentCoordinates = Pair(latitude, longitude)

        val currentState = _uiState.value
        if (currentState is FavoritesUiState.Success) {
            if (currentState.isRefreshing) {
                return Job().apply { complete() }
            }

            val displaced = previousCoords == null || DistanceCalculator.calculateDistanceMeters(
                previousCoords.first, previousCoords.second, latitude, longitude
            ) > MAX_DISPLACEMENT_METERS

            if (displaced) {
                routingJob?.cancel()
                invalidateRoutingCache()
                val step1Stations = DistanceCalculator.sortByDistance(currentState.stations, latitude, longitude)
                _uiState.value = currentState.copy(stations = step1Stations)
                return executeRoutingPipeline(
                    stations = step1Stations,
                    userLat = latitude,
                    userLon = longitude,
                    forceRefresh = true
                )
            } else {
                return executeRoutingPipeline(
                    stations = currentState.stations,
                    userLat = latitude,
                    userLon = longitude,
                    forceRefresh = false
                )
            }
        }
        return Job().apply { complete() }
    }

    /**
     * Determines whether the current routing cache is valid for given coordinates and TTL.
     */
    private fun isCacheValid(originLat: Double, originLon: Double): Boolean {
        val lastLat = cachedOriginLat ?: return false
        val lastLon = cachedOriginLon ?: return false
        if (routingCache.isEmpty()) return false
        if (timeProvider() - cacheTimestamp > CACHE_TTL_MS) return false
        val displacement = DistanceCalculator.calculateDistanceMeters(lastLat, lastLon, originLat, originLon)
        if (displacement > MAX_DISPLACEMENT_METERS) return false
        return true
    }

    /**
     * Clears in-memory routing cache.
     */
    fun invalidateRoutingCache() {
        cachedOriginLat = null
        cachedOriginLon = null
        cacheTimestamp = 0L
        routingCache.clear()
    }

    /**
     * Selects a station to display its detail modal sheet.
     */
    fun selectStationForDetail(station: Station) {
        _selectedStationForDetail.value = station
        val current = _uiState.value
        if (current is FavoritesUiState.Success) {
            _uiState.value = current.copy(selectedStationForDetail = station)
        }
        stationDetailCoordinator.selectStationForDetail(station)
    }

    /**
     * Manually triggers refresh of live charging telemetry and 24h usage statistics.
     */
    fun refreshStationDetail(): Job? {
        return stationDetailCoordinator.refreshStationDetail()
    }

    /**
     * Dismisses the active station detail modal sheet.
     */
    fun dismissStationDetail() {
        _selectedStationForDetail.value = null
        val current = _uiState.value
        if (current is FavoritesUiState.Success) {
            _uiState.value = current.copy(selectedStationForDetail = null)
        }
        stationDetailCoordinator.dismissStationDetail()
    }

    /**
     * Returns formatted Cookie header string for WebView session injection.
     */
    fun getCookieHeader(): String = authEngine.sessionManager.getCookieHeader()

    /**
     * Removes a station from the UI favorites list and routing cache.
     * Restores the station and detail selection if the repository operation fails and rolls back.
     */
    fun removeFavorite(stationId: String): Job {
        val removedStation = (_uiState.value as? FavoritesUiState.Success)?.stations?.find { it.id == stationId }
        val removedSelected = _selectedStationForDetail.value?.takeIf { it.id == stationId }
        routingCache.remove(stationId)
        if (_selectedStationForDetail.value?.id == stationId) {
            _selectedStationForDetail.value = null
        }
        _uiState.update { currentState ->
            if (currentState is FavoritesUiState.Success) {
                val updated = currentState.stations.filter { it.id != stationId }
                val selected = if (currentState.selectedStationForDetail?.id == stationId) null else currentState.selectedStationForDetail
                currentState.copy(stations = updated, selectedStationForDetail = selected)
            } else {
                currentState
            }
        }
        return viewModelScope.launch(dispatcher) {
            val result = repository.removeFavoriteStation(stationId)
            if (result.isFailure && removedStation != null) {
                _uiState.update { currentState ->
                    if (currentState is FavoritesUiState.Success && currentState.stations.none { it.id == stationId }) {
                        val restoredStations = sortStations(currentState.stations + removedStation)
                        currentState.copy(
                            stations = restoredStations,
                            selectedStationForDetail = currentState.selectedStationForDetail ?: removedSelected
                        )
                    } else {
                        currentState
                    }
                }
                if (removedSelected != null && _selectedStationForDetail.value == null) {
                    _selectedStationForDetail.value = removedSelected
                }
            }
        }
    }

    /**
     * Resets state back to [FavoritesUiState.LoggedOut] (e.g. to re-enter email).
     */
    fun backToEmailInput() {
        _uiState.value = FavoritesUiState.LoggedOut
    }

    /**
     * Logs out user, clears session credentials, cancels background routing, and resets UI state.
     */
    fun logout(activityContext: android.content.Context? = null) {
        favoritesLoadJob?.cancel()
        routingJob?.cancel()
        invalidateRoutingCache()
        authEngine.logout()
        if (authService != null) {
            viewModelScope.launch {
                authService.signOut(activityContext)
            }
        }
        pendingEmail = ""
        _selectedStationForDetail.value = null
        stationDetailCoordinator.dismissStationDetail()
        currentCoordinates = null
        lastProcessedCoordinates = null
        if (repository.firestoreFavoritesRepository == null) {
            _uiState.value = FavoritesUiState.LoggedOut
        }
    }

    private fun sortStations(stations: List<Station>): List<Station> {
        return stations.sortedWith(
            compareBy<Station> { station ->
                val metrics = station.drivingMetrics
                when {
                    metrics == null -> Long.MAX_VALUE
                    metrics.durationSeconds > 0L -> metrics.durationSeconds
                    metrics.distanceMeters > 0L -> {
                        (metrics.distanceMeters / (30.0 * 1000.0 / 3600.0)).roundToLong().coerceAtLeast(60L)
                    }
                    (station.distanceKm ?: 0.0) > 0.0 -> {
                        ((station.distanceKm!! * 1000.0) / (30.0 * 1000.0 / 3600.0)).roundToLong().coerceAtLeast(60L)
                    }
                    else -> 0L
                }
            }.thenBy(nullsLast()) { it.distanceKm }
        )
    }

    override fun onCleared() {
        super.onCleared()
        favoritesLoadJob?.cancel()
        routingJob?.cancel()
        stationDetailCoordinator.dismissStationDetail()
    }

    companion object {
        const val CACHE_TTL_MS = 180_000L // 3 minutes
        const val MIN_DISPLACEMENT_METERS = 20.0
        const val MAX_DISPLACEMENT_METERS = 200.0
        const val MAX_CANDIDATE_STATIONS = 10

        fun provideFactory(
            repository: EvcsRepository,
            authEngine: AuthEngine,
            authService: AuthService? = null,
            locationService: LocationService? = null,
            routingPreferencesManager: RoutingPreferencesManager? = null,
            routingCoordinator: MultiTierRoutingCoordinator = MultiTierRoutingCoordinator(),
            telemetryRepository: EvcsTelemetryRepository? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            defaultDispatcher: CoroutineDispatcher = ioDispatcher
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return FavoritesViewModel(
                    repository = repository,
                    authEngine = authEngine,
                    authService = authService,
                    locationService = locationService,
                    dispatcher = Dispatchers.Main,
                    routingPreferencesManager = routingPreferencesManager,
                    routingCoordinator = routingCoordinator,
                    telemetryRepository = telemetryRepository,
                    ioDispatcher = ioDispatcher,
                    defaultDispatcher = defaultDispatcher
                ) as T
            }
        }
    }
}
