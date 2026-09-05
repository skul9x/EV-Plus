package com.evcs.favorites.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.preferences.NearbyFilterPreferences
import com.evcs.favorites.data.preferences.SmartFilterPreferences
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.domain.filter.NearbyStationFilter
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.data.repository.EvcsTelemetryRepository
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.ui.state.StationDetailUiState
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.ui.state.NearbyUiEvent
import com.evcs.favorites.ui.state.NearbyUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel orchestrating the Nearby Charging Stations feature.
 *
 * Responsibilities:
 * - On-demand GPS location acquisition via [LocationService.getFreshLocation] (no idle/driving GPS polling).
 * - Raw nearby station retrieval from [EvcsRepository.searchNearbyVinFast].
 * - Instant client-side wattage and port availability filtering via [NearbyStationFilter].
 * - Top 10 nearest stations extraction via Haversine calculation.
 * - Targeted driving metrics dispatch strictly for the Top 10 nearest stations via [MultiTierRoutingCoordinator].
 * - Cloud favorites toggle with authentication guard and two-way state synchronization.
 */
class NearbyViewModel(
    private val repository: EvcsRepository,
    private val sessionManager: SessionManager,
    private val locationService: LocationService,
    private val routingPreferencesManager: RoutingPreferencesManager? = null,
    private val routingCoordinator: MultiTierRoutingCoordinator = MultiTierRoutingCoordinator(),
    private val filterPreferences: NearbyFilterPreferences? = null,
    private val smartFilterPreferences: SmartFilterPreferences? = null,
    telemetryRepository: EvcsTelemetryRepository? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val routingDebounceMs: Long = 300L
) : ViewModel() {

    private val telemetryRepo: EvcsTelemetryRepository = telemetryRepository ?: EvcsTelemetryRepository(
        dataSource = EvcsTelemetryDataSource(sessionManager = sessionManager, ioDispatcher = ioDispatcher),
        ioDispatcher = ioDispatcher
    )

    val stationDetailCoordinator: StationDetailCoordinator = StationDetailCoordinator(
        coroutineScope = viewModelScope,
        telemetryRepository = telemetryRepo,
        ioDispatcher = ioDispatcher,
        mainDispatcher = dispatcher
    )

    val stationDetailState: StateFlow<StationDetailUiState> = stationDetailCoordinator.stationDetailState

    private val prefsManager: RoutingPreferencesManager =
        routingPreferencesManager ?: RoutingPreferencesManager(storage = InMemorySessionStorage())

    val routingSettings: StateFlow<RoutingSettings> = prefsManager.settings

    private var previousRoutingSettings: RoutingSettings = prefsManager.settings.value

    private val filterPrefs: NearbyFilterPreferences =
        filterPreferences ?: NearbyFilterPreferences(storage = InMemorySessionStorage())

    private val smartFilterPrefs: SmartFilterPreferences =
        smartFilterPreferences ?: SmartFilterPreferences(storage = InMemorySessionStorage())

    private val rawInitialSmartMode: SmartFilterMode = smartFilterPrefs.getActiveFilterMode()
    private val initialDcTier: DcWattageTier? = smartFilterPrefs.getSelectedDcTier()
    private val initialCustomConfig: CustomFilterConfig? = smartFilterPrefs.getCustomConfig()

    private val initialSmartMode: SmartFilterMode = when {
        rawInitialSmartMode == SmartFilterMode.DC && initialDcTier == null -> {
            smartFilterPrefs.saveActiveFilterMode(SmartFilterMode.NONE)
            smartFilterPrefs.saveSelectedDcTier(null)
            SmartFilterMode.NONE
        }
        rawInitialSmartMode == SmartFilterMode.CUSTOM && (initialCustomConfig == null || !initialCustomConfig.isValid()) -> {
            smartFilterPrefs.saveActiveFilterMode(SmartFilterMode.NONE)
            SmartFilterMode.NONE
        }
        else -> rawInitialSmartMode
    }
    private val initialDcVisible: Boolean = initialSmartMode == SmartFilterMode.DC && initialDcTier != null

    private val initialSelectedWattages: Set<WattageOption> = if (initialSmartMode != SmartFilterMode.NONE) {
        filterPrefs.clear()
        emptySet()
    } else {
        filterPrefs.getSelectedWattages()
    }

    private val _uiState = MutableStateFlow(
        NearbyUiState(
            favoriteStationIds = repository.favoriteIdsState.value,
            selectedWattages = initialSelectedWattages,
            activeFilterMode = initialSmartMode,
            selectedDcTier = if (initialSmartMode == SmartFilterMode.DC) initialDcTier else null,
            isDcSubFilterVisible = initialDcVisible,
            savedCustomConfig = initialCustomConfig
        )
    )
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    private val _events = Channel<NearbyUiEvent>(Channel.BUFFERED)
    val events: Flow<NearbyUiEvent> = _events.receiveAsFlow()

    var scanJob: Job? = null
        private set

    var routingJob: Job? = null
        private set

    var routingDebounceJob: Job? = null
        private set

    init {
        // Observe repository favorite IDs to keep UI state automatically in sync
        viewModelScope.launch(dispatcher) {
            repository.favoriteIdsState.collect { ids ->
                _uiState.update { it.copy(favoriteStationIds = ids) }
            }
        }

        // Observe routing preferences changes
        viewModelScope.launch(dispatcher) {
            prefsManager.settings.collect { newSettings ->
                val engineChanged = newSettings.preferredEngine != previousRoutingSettings.preferredEngine
                val keyChanged = newSettings.googleApiKey != previousRoutingSettings.googleApiKey
                val osrmUrlChanged = newSettings.customOsrmServerUrl != previousRoutingSettings.customOsrmServerUrl
                val fallbackChanged = newSettings.autoFallbackEnabled != previousRoutingSettings.autoFallbackEnabled

                if (engineChanged || keyChanged || osrmUrlChanged || fallbackChanged) {
                    previousRoutingSettings = newSettings
                    if (_uiState.value.top10DisplayStations.isNotEmpty() &&
                        _uiState.value.userLatitude != null &&
                        _uiState.value.userLongitude != null
                    ) {
                        recalculateRouting(newSettings)
                    }
                }
            }
        }
    }

    /**
     * Initiates manual on-demand GPS scanning for nearby VinFast stations.
     *
     * 1. Checks location permission; emits [NearbyUiEvent.RequestLocationPermission] if not granted.
     * 2. Sets [NearbyUiState.isLocating] to true and acquires fresh GPS fix.
     * 3. Sets [NearbyUiState.isSearching] to true and queries [EvcsRepository.searchNearbyVinFast].
     * 4. Updates raw stations and dispatches filter and Top 10 routing pipeline.
     */
    fun scanNearbyStations(): Job {
        scanJob?.let { activeJob ->
            if (activeJob.isActive && (_uiState.value.isLocating || _uiState.value.isSearching)) {
                return activeJob
            }
        }
        scanJob?.cancel()
        routingJob?.cancel()
        routingDebounceJob?.cancel()

        val job = viewModelScope.launch(dispatcher) {
            if (!locationService.hasLocationPermission()) {
                _events.send(NearbyUiEvent.RequestLocationPermission)
                return@launch
            }

            _uiState.update { it.copy(isLocating = true, errorMessage = null) }

            val location = locationService.getFreshLocation()
            if (location == null) {
                _uiState.update {
                    it.copy(
                        isLocating = false,
                        errorMessage = "Không thể xác định vị trí hiện tại"
                    )
                }
                return@launch
            }

            val lat = location.latitude
            val lon = location.longitude

            _uiState.update {
                it.copy(
                    isLocating = false,
                    isSearching = true,
                    userLatitude = lat,
                    userLongitude = lon,
                    errorMessage = null
                )
            }

            val searchResult = repository.searchNearbyVinFast(lat, lon)
            if (searchResult.isFailure) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        errorMessage = searchResult.exceptionOrNull()?.message ?: "Lỗi tải trạm sạc quanh đây"
                    )
                }
                return@launch
            }

            val raw = searchResult.getOrThrow()
            _uiState.update {
                it.copy(
                    rawStations = raw,
                    hasSearched = true,
                    isSearching = false
                )
            }

            executeFilterAndRoutingPipeline(
                rawStations = raw,
                userLat = lat,
                userLon = lon,
                selectedWattages = _uiState.value.selectedWattages,
                debounce = false
            )
        }
        scanJob = job
        return job
    }

    /**
     * Toggles a wattage rating filter chip (e.g. 250kW, 60kW, etc.).
     * Adds or removes [option] from [NearbyUiState.selectedWattages], then re-executes
     * the filtering pipeline to extract a fresh Top 10 and re-calculates driving routes.
     */
    fun toggleWattageFilter(option: WattageOption): Job? {
        val currentSelected = _uiState.value.selectedWattages.toMutableSet()
        if (currentSelected.contains(option)) {
            currentSelected.remove(option)
        } else {
            currentSelected.add(option)
        }
        val newSelected = currentSelected.toSet()
        _uiState.update { it.copy(selectedWattages = newSelected) }
        filterPrefs.saveSelectedWattages(newSelected)

        val lat = _uiState.value.userLatitude
        val lon = _uiState.value.userLongitude
        val raw = _uiState.value.rawStations

        if (lat != null && lon != null && _uiState.value.hasSearched) {
            routingJob?.cancel()
            val job = viewModelScope.launch(dispatcher) {
                executeFilterAndRoutingPipeline(
                    rawStations = raw,
                    userLat = lat,
                    userLon = lon,
                    selectedWattages = newSelected
                )
            }
            routingJob = job
            return job
        }
        return null
    }

    /**
     * Clears all selected wattage filters and re-executes the filtering/routing pipeline.
     */
    fun clearWattageFilters(): Job? {
        if (_uiState.value.selectedWattages.isEmpty()) return null
        _uiState.update { it.copy(selectedWattages = emptySet()) }
        filterPrefs.clear()

        val lat = _uiState.value.userLatitude
        val lon = _uiState.value.userLongitude
        val raw = _uiState.value.rawStations

        if (lat != null && lon != null && _uiState.value.hasSearched) {
            routingJob?.cancel()
            val job = viewModelScope.launch(dispatcher) {
                executeFilterAndRoutingPipeline(
                    rawStations = raw,
                    userLat = lat,
                    userLon = lon,
                    selectedWattages = emptySet()
                )
            }
            routingJob = job
            return job
        }
        return null
    }

    /**
     * Toggles AC filter mode.
     * If AC active, resets to NONE; if not active, sets to AC and triggers pipeline.
     */
    fun toggleAcFilter(): Job? {
        val isAcActive = _uiState.value.activeFilterMode == SmartFilterMode.AC
        val newMode = if (isAcActive) SmartFilterMode.NONE else SmartFilterMode.AC
        filterPrefs.clear()
        _uiState.update {
            it.copy(
                activeFilterMode = newMode,
                isDcSubFilterVisible = false,
                selectedDcTier = null,
                selectedWattages = emptySet()
            )
        }
        smartFilterPrefs.saveActiveFilterMode(newMode)
        smartFilterPrefs.saveSelectedDcTier(null)
        return triggerFilterPipeline()
    }

    /**
     * Enters DC mode: sets isDcSubFilterVisible = true, activeFilterMode = DC,
     * without filtering until a tier is selected.
     */
    fun enterDcMode() {
        filterPrefs.clear()
        _uiState.update {
            it.copy(
                isDcSubFilterVisible = true,
                activeFilterMode = SmartFilterMode.DC,
                selectedDcTier = null,
                selectedWattages = emptySet()
            )
        }
        smartFilterPrefs.saveActiveFilterMode(SmartFilterMode.DC)
        smartFilterPrefs.saveSelectedDcTier(null)
        if (_uiState.value.rawStations.isNotEmpty()) {
            triggerFilterPipeline()
        }
    }

    /**
     * Exits DC mode: sets isDcSubFilterVisible = false, resets selectedDcTier = null,
     * sets activeFilterMode = NONE, and triggers pipeline with unfiltered stations.
     */
    fun exitDcMode(): Job? {
        filterPrefs.clear()
        _uiState.update {
            it.copy(
                isDcSubFilterVisible = false,
                selectedDcTier = null,
                activeFilterMode = SmartFilterMode.NONE,
                selectedWattages = emptySet()
            )
        }
        smartFilterPrefs.saveActiveFilterMode(SmartFilterMode.NONE)
        smartFilterPrefs.saveSelectedDcTier(null)
        return triggerFilterPipeline()
    }

    /**
     * Selects DC wattage tier, updates preferences, and triggers filter pipeline.
     */
    fun selectDcTier(tier: DcWattageTier): Job? {
        filterPrefs.clear()
        _uiState.update {
            it.copy(
                selectedDcTier = tier,
                activeFilterMode = SmartFilterMode.DC,
                isDcSubFilterVisible = true,
                selectedWattages = emptySet()
            )
        }
        smartFilterPrefs.saveActiveFilterMode(SmartFilterMode.DC)
        smartFilterPrefs.saveSelectedDcTier(tier)
        return triggerFilterPipeline()
    }

    /**
     * Applies custom filter if configured. If not configured, prompts user to configure.
     */
    fun applyCustomFilter(): Job? {
        if (smartFilterPrefs.hasCustomConfig()) {
            val config = smartFilterPrefs.getCustomConfig()
            filterPrefs.clear()
            _uiState.update {
                it.copy(
                    activeFilterMode = SmartFilterMode.CUSTOM,
                    isDcSubFilterVisible = false,
                    selectedDcTier = null,
                    savedCustomConfig = config,
                    showCustomConfigPrompt = false,
                    selectedWattages = emptySet()
                )
            }
            smartFilterPrefs.saveActiveFilterMode(SmartFilterMode.CUSTOM)
            smartFilterPrefs.saveSelectedDcTier(null)
            return triggerFilterPipeline()
        } else {
            _uiState.update {
                it.copy(showCustomConfigPrompt = true)
            }
            return null
        }
    }

    /**
     * Persists new custom configuration and directly applies CUSTOM mode.
     */
    fun saveAndApplyCustomFilter(config: CustomFilterConfig): Job? {
        filterPrefs.clear()
        smartFilterPrefs.saveCustomConfig(config)
        smartFilterPrefs.saveActiveFilterMode(SmartFilterMode.CUSTOM)
        smartFilterPrefs.saveSelectedDcTier(null)
        _uiState.update {
            it.copy(
                savedCustomConfig = config,
                activeFilterMode = SmartFilterMode.CUSTOM,
                isDcSubFilterVisible = false,
                selectedDcTier = null,
                showCustomConfigPrompt = false,
                selectedWattages = emptySet()
            )
        }
        return triggerFilterPipeline()
    }

    /**
     * Dismisses the custom config prompt dialog.
     */
    fun dismissCustomPrompt() {
        _uiState.update { it.copy(showCustomConfigPrompt = false) }
    }

    /**
     * Resets smart filter to NONE and refreshes pipeline.
     */
    fun clearSmartFilter(): Job? {
        filterPrefs.clear()
        _uiState.update {
            it.copy(
                activeFilterMode = SmartFilterMode.NONE,
                selectedDcTier = null,
                isDcSubFilterVisible = false,
                selectedWattages = emptySet()
            )
        }
        smartFilterPrefs.saveActiveFilterMode(SmartFilterMode.NONE)
        smartFilterPrefs.saveSelectedDcTier(null)
        return triggerFilterPipeline()
    }


    /**
     * Toggles favorite status for a station.
     *
     * Guards:
     * - If user is unauthenticated ([SessionManager.hasAuthCookie] is false), emits [NearbyUiEvent.ShowLoginRequired].
     * - If authenticated, toggles favorite via [EvcsRepository] and emits [NearbyUiEvent.ShowToast].
     */
    fun toggleFavorite(station: Station): Job {
        if (repository.firestoreFavoritesRepository == null && !sessionManager.hasAuthCookie()) {
            return viewModelScope.launch(dispatcher) {
                _events.send(NearbyUiEvent.ShowLoginRequired(station.name))
            }
        }

        return viewModelScope.launch(dispatcher) {
            val isFavorite = _uiState.value.favoriteStationIds.contains(station.id)
            val result = if (isFavorite) {
                repository.removeFavoriteStation(station.id)
            } else {
                repository.addFavoriteStation(station)
            }

            if (result.isSuccess) {
                val msg = if (isFavorite) {
                    "Đã xóa khỏi danh sách yêu thích"
                } else {
                    "Đã thêm vào danh sách yêu thích"
                }
                _events.send(NearbyUiEvent.ShowToast(msg))
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Thao tác không thành công"
                _events.send(NearbyUiEvent.ShowToast(errorMsg))
            }
        }
    }

    /**
     * Selects a station to display its native detail bottom sheet.
     */
    fun selectStationForDetail(station: Station): Job {
        return stationDetailCoordinator.selectStationForDetail(station)
    }

    /**
     * Manually refreshes live charging telemetry and 24h usage statistics for the selected station.
     */
    fun refreshStationDetail(): Job? {
        return stationDetailCoordinator.refreshStationDetail()
    }

    /**
     * Dismisses the active station detail modal sheet.
     */
    fun dismissStationDetail() {
        stationDetailCoordinator.dismissStationDetail()
    }

    /**
     * Refreshes nearby stations.
     * Always re-acquires fresh real-time GPS coordinates via [locationService.getFreshLocation]
     * rather than reusing stale in-memory coordinates. If location cannot be determined,
     * updates error message requesting GPS check and retry.
     */
    fun refresh(): Job {
        scanJob?.cancel()
        routingJob?.cancel()
        routingDebounceJob?.cancel()

        val job = viewModelScope.launch(dispatcher) {
            if (!locationService.hasLocationPermission()) {
                _events.send(NearbyUiEvent.RequestLocationPermission)
                return@launch
            }

            _uiState.update { it.copy(isLocating = true, errorMessage = null) }

            val location = locationService.getFreshLocation()
            if (location == null) {
                _uiState.update {
                    it.copy(
                        isLocating = false,
                        isSearching = false,
                        errorMessage = "Không thể lấy vị trí hiện tại. Vui lòng kiểm tra GPS và thử lại."
                    )
                }
                return@launch
            }

            val newLat = location.latitude
            val newLon = location.longitude

            _uiState.update {
                it.copy(
                    userLatitude = newLat,
                    userLongitude = newLon,
                    isLocating = false,
                    isSearching = true,
                    errorMessage = null
                )
            }

            val searchResult = repository.searchNearbyVinFast(newLat, newLon)
            if (searchResult.isFailure) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        errorMessage = searchResult.exceptionOrNull()?.message ?: "Lỗi tải trạm sạc quanh đây"
                    )
                }
                return@launch
            }

            val raw = searchResult.getOrThrow()
            _uiState.update {
                it.copy(
                    rawStations = raw,
                    hasSearched = true,
                    isSearching = false
                )
            }

            executeFilterAndRoutingPipeline(
                rawStations = raw,
                userLat = newLat,
                userLon = newLon,
                selectedWattages = _uiState.value.selectedWattages,
                debounce = false
            )
        }
        scanJob = job
        return job
    }

    /**
     * Updates routing preferences, saves them to [prefsManager], and triggers immediate
     * re-calculation of driving metrics for visible top 10 stations.
     */
    fun updateRoutingSettings(settings: RoutingSettings): Job {
        previousRoutingSettings = settings
        prefsManager.saveSettings(settings)
        return recalculateRouting(settings)
    }

    /**
     * Proactively validates a Google Cloud Routes API key via [prefsManager].
     */
    suspend fun validateGoogleApiKey(key: String): Result<Boolean> {
        return prefsManager.validateGoogleApiKey(key)
    }

    /**
     * Recalculates driving metrics for the currently visible top 10 stations with the specified [settings].
     * Avoids GPS hardware re-acquisition and preserves existing station data (e.g. connectors).
     */
    fun recalculateRouting(settings: RoutingSettings = prefsManager.settings.value): Job {
        val lat = _uiState.value.userLatitude
        val lon = _uiState.value.userLongitude
        val top10 = _uiState.value.top10DisplayStations

        if (lat == null || lon == null || top10.isEmpty()) {
            return Job().apply { complete() }
        }

        routingJob?.cancel()
        routingDebounceJob?.cancel()
        val job = viewModelScope.launch(dispatcher) {
            _uiState.update { it.copy(isRoutingLoading = true) }

            val destinations = top10.take(10).map { station ->
                RoutingDestination(
                    id = station.id,
                    latitude = station.latitude,
                    longitude = station.longitude
                )
            }

            val metrics = withContext(ioDispatcher) {
                routingCoordinator.calculateRoutes(
                    originLat = lat,
                    originLng = lon,
                    destinations = destinations,
                    settings = settings
                )
            }

            val routedTop10 = top10.map { station ->
                val m = metrics[station.id]
                if (m != null) station.copy(drivingMetrics = m) else station
            }
            val sortedRoutedTop10 = withContext(defaultDispatcher) {
                NearbyStationFilter.sortByDrivingDistance(routedTop10)
            }

            _uiState.update {
                it.copy(
                    top10DisplayStations = sortedRoutedTop10,
                    routingMetrics = metrics,
                    isRoutingLoading = false
                )
            }
        }
        routingJob = job
        return job
    }

    /**
     * Clears any active error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Re-runs the filtering and routing pipeline using current state.
     */
    private fun triggerFilterPipeline(): Job? {
        val lat = _uiState.value.userLatitude
        val lon = _uiState.value.userLongitude
        val raw = _uiState.value.rawStations

        if (raw.isNotEmpty()) {
            routingJob?.cancel()
            val job = viewModelScope.launch(dispatcher) {
                executeFilterAndRoutingPipeline(
                    rawStations = raw,
                    userLat = lat ?: 0.0,
                    userLon = lon ?: 0.0,
                    selectedWattages = _uiState.value.selectedWattages
                )
            }
            routingJob = job
            return job
        }
        return null
    }

    /**
     * Internal filtering and multi-tier routing pipeline.
     * Strictly enforces <= 10 destinations sent to [MultiTierRoutingCoordinator].
     */
    private suspend fun executeFilterAndRoutingPipeline(
        rawStations: List<Station>,
        userLat: Double,
        userLon: Double,
        selectedWattages: Set<WattageOption>,
        debounce: Boolean = true
    ) {
        val currentMode = _uiState.value.activeFilterMode
        val currentDcTier = _uiState.value.selectedDcTier
        val currentCustomConfig = _uiState.value.savedCustomConfig ?: smartFilterPrefs.getCustomConfig()

        // 1. Client-side smart/wattage and port availability filtering & 2. Haversine distance computation and Top 10 extraction
        val top10 = withContext(defaultDispatcher) {
            val f = if (currentMode != SmartFilterMode.NONE) {
                NearbyStationFilter.filterSmartStations(
                    stations = rawStations,
                    mode = currentMode,
                    dcTier = currentDcTier,
                    customConfig = currentCustomConfig,
                    includeFullStations = true
                )
            } else if (selectedWattages.isNotEmpty()) {
                NearbyStationFilter.filterStations(rawStations, selectedWattages, includeFullStations = true)
            } else {
                NearbyStationFilter.filterSmartStations(
                    stations = rawStations,
                    mode = SmartFilterMode.NONE,
                    includeFullStations = true
                )
            }
            NearbyStationFilter.extractTopNearest(userLat, userLon, f, limit = 10)
        }

        if (top10.isEmpty()) {
            routingDebounceJob?.cancel()
            _uiState.update {
                it.copy(
                    top10DisplayStations = emptyList(),
                    routingMetrics = emptyMap(),
                    isRoutingLoading = false
                )
            }
            return
        }

        // 3. Immediately show Top 10 with Haversine distance while routing computes
        _uiState.update {
            it.copy(
                top10DisplayStations = top10,
                isRoutingLoading = true
            )
        }

        // 4. Debounced Remote Route Calculation
        routingDebounceJob?.cancel()

        val destinations = top10.take(10).map { station ->
            RoutingDestination(
                id = station.id,
                latitude = station.latitude,
                longitude = station.longitude
            )
        }

        suspend fun calculateAndApplyRoutes() {
            val metrics = withContext(ioDispatcher) {
                routingCoordinator.calculateRoutes(
                    originLat = userLat,
                    originLng = userLon,
                    destinations = destinations,
                    settings = prefsManager.settings.value
                )
            }
            val routedTop10 = top10.map { station ->
                val m = metrics[station.id]
                if (m != null) station.copy(drivingMetrics = m) else station
            }
            val sorted = withContext(defaultDispatcher) {
                NearbyStationFilter.sortByDrivingDistance(routedTop10)
            }
            _uiState.update {
                it.copy(
                    top10DisplayStations = sorted,
                    routingMetrics = metrics,
                    isRoutingLoading = false
                )
            }
        }

        if (!debounce || routingDebounceMs <= 0L) {
            calculateAndApplyRoutes()
        } else {
            routingDebounceJob = viewModelScope.launch(dispatcher) {
                delay(routingDebounceMs)
                calculateAndApplyRoutes()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        routingJob?.cancel()
        routingDebounceJob?.cancel()
        stationDetailCoordinator.dismissStationDetail()
    }

    companion object {
        fun provideFactory(
            repository: EvcsRepository,
            sessionManager: SessionManager,
            locationService: LocationService,
            routingCoordinator: MultiTierRoutingCoordinator = MultiTierRoutingCoordinator(),
            routingPreferencesManager: RoutingPreferencesManager? = null,
            filterPreferences: NearbyFilterPreferences? = null,
            smartFilterPreferences: SmartFilterPreferences? = null,
            telemetryRepository: EvcsTelemetryRepository? = null,
            dispatcher: CoroutineDispatcher = Dispatchers.Main,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            routingDebounceMs: Long = 300L
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return NearbyViewModel(
                    repository = repository,
                    sessionManager = sessionManager,
                    locationService = locationService,
                    routingPreferencesManager = routingPreferencesManager,
                    routingCoordinator = routingCoordinator,
                    filterPreferences = filterPreferences,
                    smartFilterPreferences = smartFilterPreferences,
                    telemetryRepository = telemetryRepository,
                    dispatcher = dispatcher,
                    ioDispatcher = ioDispatcher,
                    defaultDispatcher = defaultDispatcher,
                    routingDebounceMs = routingDebounceMs
                ) as T
            }
        }
    }
}

