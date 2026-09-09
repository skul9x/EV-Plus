package com.evcs.favorites.ui.screens

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.evcs.favorites.car.CarNavigationDispatcher
import com.evcs.favorites.data.locations.AdministrativeDistrict
import com.evcs.favorites.data.locations.LocationCoordinate
import com.evcs.favorites.data.locations.VietnamLocationsRepository
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DeadZoneWarning
import com.evcs.favorites.data.routing.EvRouteStop
import com.evcs.favorites.data.routing.EvRoutingSettings
import com.evcs.favorites.data.routing.EvSmartRoutePlan
import com.evcs.favorites.data.routing.EvSmartRoutePlanner
import com.evcs.favorites.data.routing.RoutePathResult
import com.evcs.favorites.data.routing.RouteSessionData
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.domain.location.LocationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for Insufficient Power Fallback Alert Dialog.
 */
@Immutable
data class InsufficientPowerDialogState(
    val isVisible: Boolean = false,
    val requiredPowerKw: Double = 60.0,
    val fallbackStation: Station? = null,
    val fallbackPowerKw: Double = 30.0,
    val stopIndex: Int = 1,
    val message: String = ""
)

/**
 * UI State for the Route Planning & Navigation Tab.
 */
@Immutable
data class RouteUiState(
    val safeRangeKm: Int = 200,
    val startBatteryPercent: Int = 100,
    val minChargerPowerKw: Double = EvRoutingSettings.DEFAULT_MIN_CHARGER_POWER_KW,
    val originProvince: String = "Hà Nội",
    val originDistrict: String = "Hoàn Kiếm",
    val originCoordinate: LocationCoordinate? = null,
    val isOriginCurrentLocation: Boolean = false,
    val destinationProvince: String = "Đà Nẵng",
    val destinationDistrict: String = "Hải Châu",
    val destinationCoordinate: LocationCoordinate? = null,
    val availableProvinces: List<String> = emptyList(),
    val originDistricts: List<AdministrativeDistrict> = emptyList(),
    val destinationDistricts: List<AdministrativeDistrict> = emptyList(),
    val originDistrictNames: List<String> = emptyList(),
    val destinationDistrictNames: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val routePlan: EvSmartRoutePlan? = null,
    val selectedStopForSwap: EvRouteStop? = null,
    val errorMessage: String? = null,
    val insufficientPowerDialog: InsufficientPowerDialogState = InsufficientPowerDialogState()
)

/**
 * ViewModel orchestrating EV smart corridor route planning, vehicle range configuration,
 * administrative origin/destination selection, vertical stop timeline rendering, and
 * alternate charging station swapping.
 */
class RouteViewModel(
    private val locationsRepository: VietnamLocationsRepository,
    private val evSmartRoutePlanner: EvSmartRoutePlanner,
    private val evcsRepository: EvcsRepository,
    private val locationService: LocationService? = null,
    private val routingPreferencesManager: RoutingPreferencesManager? = null,
    private val candidateStationsProvider: (suspend () -> List<Station>)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = if (ioDispatcher === Dispatchers.IO) Dispatchers.Default else ioDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouteUiState())
    val uiState: StateFlow<RouteUiState> = _uiState.asStateFlow()

    private var customCandidateStations: List<Station>? = null
    private var swapStationJob: Job? = null

    init {
        initializeLocations()
        observeRoutingPreferences()
    }

    private fun observeRoutingPreferences() {
        val manager = routingPreferencesManager ?: return
        viewModelScope.launch(dispatcher) {
            manager.evRoutingSettings.collect { settings ->
                _uiState.update { current ->
                    if (current.safeRangeKm != settings.vehicleSafeRangeKm ||
                        current.startBatteryPercent != settings.startBatteryPercent ||
                        current.minChargerPowerKw != settings.minChargerPowerKw
                    ) {
                        current.copy(
                            safeRangeKm = settings.vehicleSafeRangeKm,
                            startBatteryPercent = settings.startBatteryPercent,
                            minChargerPowerKw = settings.minChargerPowerKw
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    private fun initializeLocations() {
        val provinces = locationsRepository.getAllProvinces()
        val defaultOriginProv = if ("Hà Nội" in provinces) "Hà Nội" else provinces.firstOrNull().orEmpty()
        val originDistricts = if (defaultOriginProv.isNotEmpty()) locationsRepository.getDistrictsForProvince(defaultOriginProv) else emptyList()
        val defaultOriginDist = originDistricts.firstOrNull { it.name.contains("Hoàn Kiếm", ignoreCase = true) }?.name
            ?: originDistricts.firstOrNull()?.name.orEmpty()
        val originCoord = if (defaultOriginProv.isNotEmpty() && defaultOriginDist.isNotEmpty()) {
            locationsRepository.getCoordinate(defaultOriginProv, defaultOriginDist)
        } else null

        val defaultDestProv = when {
            "Đà Nẵng" in provinces -> "Đà Nẵng"
            "Hồ Chí Minh" in provinces -> "Hồ Chí Minh"
            provinces.size > 1 -> provinces[1]
            else -> provinces.firstOrNull().orEmpty()
        }
        val destDistricts = if (defaultDestProv.isNotEmpty()) locationsRepository.getDistrictsForProvince(defaultDestProv) else emptyList()
        val defaultDestDist = destDistricts.firstOrNull { it.name.contains("Hải Châu", ignoreCase = true) }?.name
            ?: destDistricts.firstOrNull { it.name.contains("Quận 1", ignoreCase = true) }?.name
            ?: destDistricts.firstOrNull()?.name.orEmpty()
        val destCoord = if (defaultDestProv.isNotEmpty() && defaultDestDist.isNotEmpty()) {
            locationsRepository.getCoordinate(defaultDestProv, defaultDestDist)
        } else null

        val savedEvSettings = routingPreferencesManager?.evRoutingSettings?.value
        val initialSafeRange = savedEvSettings?.vehicleSafeRangeKm ?: 200
        val initialStartSoc = savedEvSettings?.startBatteryPercent ?: 100
        val initialMinPower = savedEvSettings?.minChargerPowerKw ?: EvRoutingSettings.DEFAULT_MIN_CHARGER_POWER_KW

        _uiState.update { current ->
            current.copy(
                availableProvinces = provinces,
                originProvince = defaultOriginProv,
                originDistrict = defaultOriginDist,
                originDistricts = originDistricts,
                originDistrictNames = originDistricts.map { it.name },
                originCoordinate = originCoord,
                destinationProvince = defaultDestProv,
                destinationDistrict = defaultDestDist,
                destinationDistricts = destDistricts,
                destinationDistrictNames = destDistricts.map { it.name },
                destinationCoordinate = destCoord,
                safeRangeKm = initialSafeRange,
                startBatteryPercent = initialStartSoc,
                minChargerPowerKw = initialMinPower
            )
        }
    }

    fun onSafeRangeChanged(rangeKm: Int) {
        val clamped = rangeKm.coerceIn(100, 500)
        _uiState.update { current ->
            if (current.safeRangeKm != clamped) {
                current.copy(safeRangeKm = clamped, routePlan = null)
            } else {
                current
            }
        }
        routingPreferencesManager?.updateVehicleSafeRangeKm(clamped)
    }

    fun onStartBatteryPercentChanged(batteryPercent: Int) {
        val clamped = batteryPercent.coerceIn(10, 100)
        _uiState.update { current ->
            if (current.startBatteryPercent != clamped) {
                current.copy(startBatteryPercent = clamped, routePlan = null)
            } else {
                current
            }
        }
        routingPreferencesManager?.updateStartBatteryPercent(clamped)
    }

    fun onMinPowerChanged(powerKw: Double) {
        val clamped = powerKw.coerceIn(
            EvRoutingSettings.MIN_CHARGER_POWER_KW,
            EvRoutingSettings.MAX_CHARGER_POWER_KW
        )
        _uiState.update { current ->
            if (current.minChargerPowerKw != clamped) {
                current.copy(minChargerPowerKw = clamped, routePlan = null)
            } else {
                current
            }
        }
        routingPreferencesManager?.updateMinChargerPowerKw(clamped)
    }

    fun onOriginProvinceSelected(province: String) {
        val districts = locationsRepository.getDistrictsForProvince(province)
        val defaultDistrict = districts.firstOrNull()?.name.orEmpty()
        val coord = districts.firstOrNull()?.coordinate

        _uiState.update { current ->
            current.copy(
                originProvince = province,
                originDistrict = defaultDistrict,
                originDistricts = districts,
                originDistrictNames = districts.map { it.name },
                originCoordinate = coord,
                isOriginCurrentLocation = false,
                routePlan = null
            )
        }
    }

    fun onOriginDistrictSelected(district: String) {
        val current = _uiState.value
        val coord = locationsRepository.getCoordinate(current.originProvince, district)
        _uiState.update {
            it.copy(
                originDistrict = district,
                originCoordinate = coord,
                isOriginCurrentLocation = false,
                routePlan = null
            )
        }
    }

    fun onDestinationProvinceSelected(province: String) {
        val districts = locationsRepository.getDistrictsForProvince(province)
        val defaultDistrict = districts.firstOrNull()?.name.orEmpty()
        val coord = districts.firstOrNull()?.coordinate

        _uiState.update { current ->
            current.copy(
                destinationProvince = province,
                destinationDistrict = defaultDistrict,
                destinationDistricts = districts,
                destinationDistrictNames = districts.map { it.name },
                destinationCoordinate = coord,
                routePlan = null
            )
        }
    }

    fun onDestinationDistrictSelected(district: String) {
        val current = _uiState.value
        val coord = locationsRepository.getCoordinate(current.destinationProvince, district)
        _uiState.update {
            it.copy(
                destinationDistrict = district,
                destinationCoordinate = coord,
                routePlan = null
            )
        }
    }

    fun useCurrentGpsLocation() {
        viewModelScope.launch(ioDispatcher) {
            val location = locationService?.getFreshLocation()
            if (location != null) {
                val closest = locationsRepository.findClosestDistrict(location.latitude, location.longitude)
                withContext(dispatcher) {
                    if (closest != null) {
                        val (province, district) = closest
                        val districts = locationsRepository.getDistrictsForProvince(province)
                        _uiState.update { current ->
                            current.copy(
                                originProvince = province,
                                originDistrict = district.name,
                                originDistricts = districts,
                                originDistrictNames = districts.map { it.name },
                                originCoordinate = LocationCoordinate(location.latitude, location.longitude),
                                isOriginCurrentLocation = true,
                                routePlan = null
                            )
                        }
                    } else {
                        _uiState.update { current ->
                            current.copy(
                                originProvince = "Vị trí hiện tại",
                                originDistrict = "",
                                originDistricts = emptyList(),
                                originDistrictNames = emptyList(),
                                originCoordinate = LocationCoordinate(location.latitude, location.longitude),
                                isOriginCurrentLocation = true,
                                routePlan = null
                            )
                        }
                    }
                }
            } else {
                withContext(dispatcher) {
                    _uiState.update { it.copy(errorMessage = "Không thể xác định vị trí GPS hiện tại") }
                }
            }
        }
    }

    fun swapOriginAndDestination() {
        _uiState.update { current ->
            current.copy(
                originProvince = current.destinationProvince,
                originDistrict = current.destinationDistrict,
                originDistricts = current.destinationDistricts,
                originDistrictNames = current.destinationDistrictNames,
                originCoordinate = current.destinationCoordinate,
                isOriginCurrentLocation = false,
                destinationProvince = current.originProvince,
                destinationDistrict = current.originDistrict,
                destinationDistricts = current.originDistricts,
                destinationDistrictNames = current.originDistrictNames,
                destinationCoordinate = current.originCoordinate,
                routePlan = null
            )
        }
    }

    fun setCandidateStations(stations: List<Station>) {
        this.customCandidateStations = stations
    }

    fun planRoute(customRoutePath: RoutePathResult? = null) {
        val state = _uiState.value
        val originCoord = state.originCoordinate
            ?: locationsRepository.getCoordinate(state.originProvince, state.originDistrict)
        val destCoord = state.destinationCoordinate
            ?: locationsRepository.getCoordinate(state.destinationProvince, state.destinationDistrict)

        if (originCoord == null || destCoord == null) {
            _uiState.update { it.copy(errorMessage = "Vui lòng chọn điểm xuất phát và điểm đến hợp lệ") }
            return
        }

        swapStationJob?.cancel()
        swapStationJob = null

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch(ioDispatcher) {
            try {
                val candidateStations = customCandidateStations
                    ?: candidateStationsProvider?.invoke()?.ifEmpty { null }
                    ?: evcsRepository.getAllKnownStations().ifEmpty {
                        evcsRepository.favoritesState.value.ifEmpty { evcsRepository.getCachedFavorites() }
                    }

                val evSettings = (routingPreferencesManager?.evRoutingSettings?.value ?: EvRoutingSettings()).copy(
                    vehicleSafeRangeKm = state.safeRangeKm,
                    startBatteryPercent = state.startBatteryPercent,
                    minChargerPowerKw = state.minChargerPowerKw
                )
                val baseRoutingSettings = routingPreferencesManager?.settings?.value ?: RoutingSettings()
                val routingSettings = baseRoutingSettings.copy(autoFallbackEnabled = true)

                val plan = evSmartRoutePlanner.planRoute(
                    originLat = originCoord.lat,
                    originLng = originCoord.lng,
                    destLat = destCoord.lat,
                    destLng = destCoord.lng,
                    stations = candidateStations,
                    evSettings = evSettings,
                    routingSettings = routingSettings,
                    customRoutePath = customRoutePath
                )

                withContext(dispatcher) {
                    val dialogState = plan.insufficientPowerWarning?.let { warning ->
                        InsufficientPowerDialogState(
                            isVisible = true,
                            requiredPowerKw = warning.requiredPowerKw,
                            fallbackStation = warning.fallbackStation,
                            fallbackPowerKw = warning.fallbackPowerKw,
                            stopIndex = warning.stopIndex,
                            message = warning.message
                        )
                    } ?: InsufficientPowerDialogState(isVisible = false)

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            routePlan = plan,
                            insufficientPowerDialog = dialogState,
                            errorMessage = null
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(dispatcher) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = e.message ?: "Không thể tính toán lộ trình trạm sạc"
                        )
                    }
                }
            }
        }
    }

    fun onAcceptRelaxedPower() {
        _uiState.update { current ->
            current.copy(
                insufficientPowerDialog = current.insufficientPowerDialog.copy(isVisible = false)
            )
        }
    }

    fun onOpenSwapFromDialog() {
        _uiState.update { current ->
            val targetStop = current.routePlan?.stops?.find { it.stopIndex == current.insufficientPowerDialog.stopIndex }
                ?: current.routePlan?.stops?.getOrNull(current.insufficientPowerDialog.stopIndex - 1)
            current.copy(
                insufficientPowerDialog = current.insufficientPowerDialog.copy(isVisible = false),
                selectedStopForSwap = targetStop
            )
        }
    }

    fun onDismissInsufficientPowerDialog() {
        _uiState.update { current ->
            current.copy(
                insufficientPowerDialog = current.insufficientPowerDialog.copy(isVisible = false)
            )
        }
    }

    fun onRelaxPowerThresholdAndRecalculate(newPowerKw: Double) {
        val clamped = newPowerKw.coerceIn(
            EvRoutingSettings.MIN_CHARGER_POWER_KW,
            EvRoutingSettings.MAX_CHARGER_POWER_KW
        )
        _uiState.update { current ->
            current.copy(
                minChargerPowerKw = clamped,
                insufficientPowerDialog = current.insufficientPowerDialog.copy(isVisible = false)
            )
        }
        routingPreferencesManager?.updateMinChargerPowerKw(clamped)
        planRoute()
    }

    fun selectStopForSwap(stop: EvRouteStop?) {
        _uiState.update { it.copy(selectedStopForSwap = stop) }
    }

    fun dismissSwapBottomSheet() {
        _uiState.update { it.copy(selectedStopForSwap = null) }
    }

    fun swapStation(stopIndex: Int, alternateStation: Station) {
        swapStationJob?.cancel()
        _uiState.update { it.copy(selectedStopForSwap = null) }

        val currentPlan = _uiState.value.routePlan ?: return
        val evSettings = (routingPreferencesManager?.evRoutingSettings?.value ?: EvRoutingSettings()).copy(
            vehicleSafeRangeKm = _uiState.value.safeRangeKm,
            startBatteryPercent = _uiState.value.startBatteryPercent,
            minChargerPowerKw = _uiState.value.minChargerPowerKw
        )

        swapStationJob = viewModelScope.launch(defaultDispatcher) {
            try {
                val updatedPlan = evSmartRoutePlanner.recalculateWithAlternateStop(
                    originalPlan = currentPlan,
                    stopIndex = stopIndex,
                    alternateStation = alternateStation,
                    evSettings = evSettings
                )
                ensureActive()
                _uiState.update {
                    it.copy(routePlan = updatedPlan)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = e.message ?: "Không thể đổi trạm sạc")
                }
            }
        }
    }

    /**
     * Promotes the designated backupStation to become the active stop using [swapStation].
     * Sets the old primary station as the new backup station, enabling 1-tap swap back.
     */
    fun swapStopWithBackup(stopIndex: Int) {
        val currentPlan = _uiState.value.routePlan ?: return
        val targetStop = currentPlan.stops.firstOrNull { it.stopIndex == stopIndex } ?: return
        val backup = targetStop.backupStation ?: return
        swapStation(stopIndex, backup)
    }

    fun buildRouteSession(): RouteSessionData? {
        val currentState = _uiState.value
        val plan = currentState.routePlan ?: return null
        val originName = listOfNotNull(
            currentState.originProvince.takeIf { it.isNotBlank() },
            currentState.originDistrict.takeIf { it.isNotBlank() }
        ).joinToString(", ")
        val destName = listOfNotNull(
            currentState.destinationProvince.takeIf { it.isNotBlank() },
            currentState.destinationDistrict.takeIf { it.isNotBlank() }
        ).joinToString(", ")

        return RouteSessionData(
            plan = plan,
            currentLegIndex = 0,
            originLabel = originName,
            destinationLabel = destName
        )
    }

    fun startNavigation(
        context: Context,
        onStarted: ((RouteSessionData) -> Unit)? = null
    ): RouteSessionData? {
        val session = buildRouteSession() ?: return null
        if (onStarted != null) {
            onStarted(session)
        } else {
            CarNavigationDispatcher.dispatchRouteNavigation(context, session)
        }
        return session
    }

    companion object {
        fun provideFactory(
            locationsRepository: VietnamLocationsRepository,
            evSmartRoutePlanner: EvSmartRoutePlanner,
            evcsRepository: EvcsRepository,
            locationService: LocationService? = null,
            routingPreferencesManager: RoutingPreferencesManager? = null,
            candidateStationsProvider: (suspend () -> List<Station>)? = null,
            dispatcher: CoroutineDispatcher = Dispatchers.Main,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            defaultDispatcher: CoroutineDispatcher = if (ioDispatcher === Dispatchers.IO) Dispatchers.Default else ioDispatcher
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return RouteViewModel(
                    locationsRepository = locationsRepository,
                    evSmartRoutePlanner = evSmartRoutePlanner,
                    evcsRepository = evcsRepository,
                    locationService = locationService,
                    routingPreferencesManager = routingPreferencesManager,
                    candidateStationsProvider = candidateStationsProvider,
                    dispatcher = dispatcher,
                    ioDispatcher = ioDispatcher,
                    defaultDispatcher = defaultDispatcher
                ) as T
            }
        }
    }
}
