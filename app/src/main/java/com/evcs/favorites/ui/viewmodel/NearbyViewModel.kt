package com.evcs.favorites.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.preferences.NearbyFilterPreferences
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.domain.filter.NearbyStationFilter
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.ui.state.NearbyUiEvent
import com.evcs.favorites.ui.state.NearbyUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val prefsManager: RoutingPreferencesManager =
        routingPreferencesManager ?: RoutingPreferencesManager(storage = InMemorySessionStorage())

    private val filterPrefs: NearbyFilterPreferences =
        filterPreferences ?: NearbyFilterPreferences(storage = InMemorySessionStorage())

    private val _uiState = MutableStateFlow(
        NearbyUiState(
            favoriteStationIds = repository.favoriteIdsState.value,
            selectedWattages = filterPrefs.getSelectedWattages()
        )
    )
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    private val _events = Channel<NearbyUiEvent>(Channel.BUFFERED)
    val events: Flow<NearbyUiEvent> = _events.receiveAsFlow()

    var scanJob: Job? = null
        private set

    var routingJob: Job? = null
        private set

    var forecastJob: Job? = null
        private set

    init {
        // Observe repository favorite IDs to keep UI state automatically in sync
        viewModelScope.launch(dispatcher) {
            repository.favoriteIdsState.collect { ids ->
                _uiState.update { it.copy(favoriteStationIds = ids) }
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
        scanJob?.cancel()
        routingJob?.cancel()
        forecastJob?.cancel()

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
                selectedWattages = _uiState.value.selectedWattages
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
            forecastJob?.cancel()
            val job = viewModelScope.launch(dispatcher) {
                executeFilterAndRoutingPipeline(
                    rawStations = raw,
                    userLat = lat,
                    userLon = lon,
                    selectedWattages = newSelected,
                    forceRefreshForecast = false
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
            forecastJob?.cancel()
            val job = viewModelScope.launch(dispatcher) {
                executeFilterAndRoutingPipeline(
                    rawStations = raw,
                    userLat = lat,
                    userLon = lon,
                    selectedWattages = emptySet(),
                    forceRefreshForecast = false
                )
            }
            routingJob = job
            return job
        }
        return null
    }

    /**
     * Toggles favorite status for a station.
     *
     * Guards:
     * - If user is unauthenticated ([SessionManager.hasAuthCookie] is false), emits [NearbyUiEvent.ShowLoginRequired].
     * - If authenticated, toggles favorite via [EvcsRepository] and emits [NearbyUiEvent.ShowToast].
     */
    fun toggleFavorite(station: Station): Job {
        if (!sessionManager.hasAuthCookie()) {
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
     * Refreshes nearby stations.
     * Re-scans using existing GPS coordinates if valid; otherwise re-requests fresh location.
     */
    fun refresh(): Job {
        val lat = _uiState.value.userLatitude
        val lon = _uiState.value.userLongitude

        return if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
            scanJob?.cancel()
            routingJob?.cancel()
            forecastJob?.cancel()
            val job = viewModelScope.launch(dispatcher) {
                _uiState.update { it.copy(isSearching = true, errorMessage = null) }

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
                    forceRefreshForecast = true
                )
            }
            scanJob = job
            job
        } else {
            scanNearbyStations()
        }
    }

    /**
     * Clears any active error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
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
        forceRefreshForecast: Boolean = false
    ) {
        // 1. Client-side wattage and port availability filtering (includes full stations for forecast enrichment)
        val filtered = NearbyStationFilter.filterStations(rawStations, selectedWattages, includeFullStations = true)

        // 2. Haversine distance computation and Top 10 extraction
        val top10 = NearbyStationFilter.extractTopNearest(userLat, userLon, filtered, limit = 10)

        // 3. Immediately show Top 10 with Haversine distance while routing computes
        _uiState.update {
            it.copy(
                top10DisplayStations = top10,
                isRoutingLoading = top10.isNotEmpty()
            )
        }

        if (top10.isEmpty()) {
            _uiState.update {
                it.copy(
                    top10DisplayStations = emptyList(),
                    routingMetrics = emptyMap(),
                    isRoutingLoading = false
                )
            }
            return
        }

        // 4. Map to strictly <= 10 destinations
        val destinations = top10.take(10).map { station ->
            RoutingDestination(
                id = station.id,
                latitude = station.latitude,
                longitude = station.longitude
            )
        }

        // 5. Dispatch multi-tier routing calculation
        val metrics = withContext(ioDispatcher) {
            routingCoordinator.calculateRoutes(
                originLat = userLat,
                originLng = userLon,
                destinations = destinations,
                settings = prefsManager.settings.value
            )
        }

        // 6. Merge drivingMetrics into Top 10 stations and update routingMetrics map
        val routedTop10 = top10.map { station ->
            val m = metrics[station.id]
            if (m != null) station.copy(drivingMetrics = m) else station
        }
        val sortedRoutedTop10 = NearbyStationFilter.sortByDrivingDistance(routedTop10)

        _uiState.update {
            it.copy(
                top10DisplayStations = sortedRoutedTop10,
                routingMetrics = metrics,
                isRoutingLoading = false
            )
        }

        // 7. Trigger background forecast enrichment for strictly Top 5 stations
        enrichTopStationsWithForecast(forceRefresh = forceRefreshForecast)
    }

    /**
     * Targeted background forecast enrichment unconditionally for the Top 5 nearest stations
     * from visible top10DisplayStations list.
     * Progressively updates stations in UI state as each forecast arrives, preserving driving metrics,
     * connectors, and display sort order.
     */
    fun enrichTopStationsWithForecast(forceRefresh: Boolean = false): Job {
        forecastJob?.cancel()
        val job = viewModelScope.launch(dispatcher) {
            val targetStations = _uiState.value.top10DisplayStations.take(5)

            if (targetStations.isEmpty()) return@launch

            withContext(ioDispatcher) {
                repository.enrichStationsWithForecast(
                    stations = targetStations,
                    forceRefresh = forceRefresh,
                    onStationUpdated = { updatedStation ->
                        launch(dispatcher) {
                            _uiState.update { currentState ->
                                val updatedTop10 = currentState.top10DisplayStations.map { st ->
                                    if (st.id == updatedStation.id) {
                                        st.copy(forecast = updatedStation.forecast)
                                    } else {
                                        st
                                    }
                                }
                                val updatedRaw = currentState.rawStations.map { st ->
                                    if (st.id == updatedStation.id) {
                                        st.copy(forecast = updatedStation.forecast)
                                    } else {
                                        st
                                    }
                                }
                                currentState.copy(
                                    top10DisplayStations = updatedTop10,
                                    rawStations = updatedRaw
                                )
                            }
                        }
                    }
                )
            }
        }
        forecastJob = job
        return job
    }

    fun enrichTopFullStationsWithForecast(forceRefresh: Boolean = false): Job =
        enrichTopStationsWithForecast(forceRefresh)

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        routingJob?.cancel()
        forecastJob?.cancel()
    }

    companion object {
        fun provideFactory(
            repository: EvcsRepository,
            sessionManager: SessionManager,
            locationService: LocationService,
            routingCoordinator: MultiTierRoutingCoordinator = MultiTierRoutingCoordinator(),
            routingPreferencesManager: RoutingPreferencesManager? = null,
            filterPreferences: NearbyFilterPreferences? = null,
            dispatcher: CoroutineDispatcher = Dispatchers.Main,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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
                    dispatcher = dispatcher,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}
