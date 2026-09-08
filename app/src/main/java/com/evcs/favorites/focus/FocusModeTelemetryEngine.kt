package com.evcs.favorites.focus

import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.here.HereEvApiClient
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.domain.model.isDc
import com.evcs.favorites.util.StationNameSanitizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale
import java.util.TimeZone

/**
 * Filter rules enforcing strict DC fast charging port categorization (>= 20kW).
 * Excludes AC 7kW/11kW/22kW and motorcycle charging plugs.
 */
object FocusModeDcFilter {
    const val MIN_DC_POWER_WATTS = 20_000L // 20kW

    /**
     * Determines whether a given [PowerPort] is a DC fast charging port.
     */
    fun isDcPort(port: PowerPort): Boolean {
        return port.isDc()
    }

    /**
     * Computes the available and total DC slots from a list of power ports,
     * strictly excluding AC ports (< 20kW or 22kW AC).
     *
     * @return Pair(availableDcSlots, totalDcSlots)
     */
    fun calculateDcSlots(powers: List<PowerPort>): Pair<Int, Int> {
        val dcPorts = powers.filter { isDcPort(it) }
        val available = dcPorts.sumOf { it.availablePlugs }
        val total = dcPorts.sumOf { it.totalPlugs }
        return Pair(available, total)
    }

    /**
     * Computes the available and total DC slots for a given [Station].
     *
     * @return Pair(availableDcSlots, totalDcSlots)
     */
    fun calculateDcSlots(station: Station): Pair<Int, Int> {
        return calculateDcSlots(station.powers)
    }
}

/**
 * Core business engine coordinating live telemetry polling, dynamic intervals,
 * offline fallback detection, and auto-reroute resolution for Focus Mode.
 *
 * Fully decoupled from Android UI to facilitate pure unit testing and headless execution.
 */
class FocusModeTelemetryEngine(
    val initialStation: Station,
    private val fetchStationTelemetry: suspend (stationId: String, lat: Double, lon: Double) -> Result<Station>,
    private val fetchNearbyCandidates: (suspend (lat: Double, lon: Double) -> Result<List<Station>>)? = null,
    private val locationProvider: (() -> Pair<Double, Double>?)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val locale: Locale = Locale.getDefault(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    coroutineScope: CoroutineScope? = null,
    val voiceAlertPolicy: FocusModeVoiceAlertPolicy? = FocusModeVoiceAlertPolicy(
        initialAvailableSlots = FocusModeDcFilter.calculateDcSlots(initialStation).first,
        initialDistanceKm = initialStation.effectiveDistanceKm,
        clock = clock
    ),
    private val onVoiceAlert: ((FocusVoiceAlert) -> Unit)? = null,
    val stationNameResolver: EvcsStationNameResolver? = null
) {
    companion object {
        const val INTERVAL_FAR_MS = 15_000L     // Distance > 3.0km -> 15s
        const val INTERVAL_MEDIUM_MS = 10_000L  // Distance 1.5km - 3.0km -> 10s
        const val INTERVAL_NEAR_MS = 5_000L     // Distance < 1.5km -> 5s

        const val DISTANCE_THRESHOLD_FAR_KM = 3.0
        const val DISTANCE_THRESHOLD_NEAR_KM = 1.5

        /**
         * Computes the dynamic polling interval based on the remaining distance to target station.
         *
         * - > 3.0 km -> 15s (15,000 ms)
         * - 1.5 km to 3.0 km -> 10s (10,000 ms)
         * - < 1.5 km -> 5s (5,000 ms)
         * - null distance -> 15s fallback
         */
        fun calculatePollingIntervalMs(distanceKm: Double?): Long {
            if (distanceKm == null || distanceKm.isNaN()) {
                return INTERVAL_FAR_MS
            }
            return when {
                distanceKm > DISTANCE_THRESHOLD_FAR_KM -> INTERVAL_FAR_MS
                distanceKm >= DISTANCE_THRESHOLD_NEAR_KM -> INTERVAL_MEDIUM_MS
                else -> INTERVAL_NEAR_MS
            }
        }

        /**
         * Resolves the best alternative station when target station DC availability drops to 0.
         * Finds the closest candidate having a matching DC power tier with available slots
         * based on the driver's current GPS position.
         */
        fun findAlternativeStation(
            targetStation: Station,
            candidates: List<Station>,
            driverLat: Double,
            driverLon: Double,
            stationNameResolver: EvcsStationNameResolver? = null
        ): AlternativeStationRecommendation? {
            // Target highest DC tier watts (e.g. 150_000L for 150kW, 60_000L for 60kW)
            val targetDcPorts = targetStation.powers.filter { FocusModeDcFilter.isDcPort(it) }
            val targetMaxDcWatts = targetDcPorts.maxOfOrNull { it.typeWatts } ?: FocusModeDcFilter.MIN_DC_POWER_WATTS

            // Filter eligible candidates:
            // 1. Not the target station
            // 2. Not maintaining or out of service
            // 3. Has overall DC available slots > 0
            // 4. Has matching DC power tier with available slots
            val validCandidates = candidates.filter { candidate ->
                if (candidate.id == targetStation.id) return@filter false
                if (candidate.depotStatus.equals("Maintaining", ignoreCase = true) ||
                    candidate.depotStatus.equals("OutOfService", ignoreCase = true)
                ) {
                    return@filter false
                }

                val (availDc, _) = FocusModeDcFilter.calculateDcSlots(candidate)
                if (availDc <= 0) return@filter false

                // Must offer matching or higher DC power tier with available plugs
                candidate.powers.any { port ->
                    FocusModeDcFilter.isDcPort(port) &&
                            port.typeWatts >= targetMaxDcWatts &&
                            port.availablePlugs > 0
                }
            }

            if (validCandidates.isEmpty()) {
                return null
            }

            // Find closest candidate by road distance or great-circle Haversine distance
            val closestCandidateWithDist = validCandidates.map { candidate ->
                val dist = DistanceCalculator.calculateDistanceKm(
                    driverLat,
                    driverLon,
                    candidate.latitude,
                    candidate.longitude
                )
                candidate.copy(distanceKm = dist) to dist
            }.minByOrNull { it.second } ?: return null

            val (chosenStation, distanceKm) = closestCandidateWithDist
            val resolvedStation = if (stationNameResolver != null) {
                stationNameResolver.resolveStationSync(chosenStation)
            } else {
                val clean = StationNameSanitizer.sanitize(chosenStation.name).ifBlank { chosenStation.name }
                chosenStation.copy(name = clean)
            }
            val (availDc, totalDc) = FocusModeDcFilter.calculateDcSlots(resolvedStation)

            return AlternativeStationRecommendation(
                station = resolvedStation,
                distanceKm = distanceKm,
                matchingPowerWatts = targetMaxDcWatts,
                availableDcSlots = availDc,
                totalDcSlots = totalDc
            )
        }
    }

    /**
     * Convenience constructor integrating directly with [HereEvApiClient].
     */
    constructor(
        initialStation: Station,
        hereEvApiClient: HereEvApiClient,
        locationProvider: (() -> Pair<Double, Double>?)? = null,
        clock: () -> Long = { System.currentTimeMillis() },
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
        defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
        coroutineScope: CoroutineScope? = null,
        voiceAlertPolicy: FocusModeVoiceAlertPolicy? = FocusModeVoiceAlertPolicy(
            initialAvailableSlots = FocusModeDcFilter.calculateDcSlots(initialStation).first,
            initialDistanceKm = initialStation.effectiveDistanceKm,
            clock = clock
        ),
        onVoiceAlert: ((FocusVoiceAlert) -> Unit)? = null,
        stationNameResolver: EvcsStationNameResolver? = null
    ) : this(
        initialStation = initialStation,
        fetchStationTelemetry = { stationId, lat, lon ->
            val res = hereEvApiClient.fetchStationById(stationId, lat, lon, radiusMeters = 1000, dcOnly = true)
            if (res.isSuccess) {
                val st = res.getOrNull()
                if (st != null) {
                    Result.success(st)
                } else {
                    Result.success(initialStation)
                }
            } else {
                Result.failure(res.exceptionOrNull() ?: IOException("Network error"))
            }
        },
        fetchNearbyCandidates = { lat, lon ->
            hereEvApiClient.fetchNearbyStations(lat, lon, radiusMeters = 10_000, dcOnly = true)
        },
        locationProvider = locationProvider,
        clock = clock,
        timeZone = timeZone,
        locale = locale,
        defaultDispatcher = defaultDispatcher,
        coroutineScope = coroutineScope,
        voiceAlertPolicy = voiceAlertPolicy,
        onVoiceAlert = onVoiceAlert,
        stationNameResolver = stationNameResolver
    )

    private val scope = coroutineScope ?: CoroutineScope(defaultDispatcher)
    private var pollingJob: Job? = null

    private var currentDriverLat: Double? = null
    private var currentDriverLon: Double? = null
    private var lastSuccessfulTelemetryTimestamp: Long = clock()

    private val initialTargetStation: Station = run {
        val authenticName = if (!EvcsStationNameResolver.isGenericStationName(initialStation.name)) {
            initialStation.name
        } else {
            stationNameResolver?.resolveFromCache(initialStation.id, initialStation.latitude, initialStation.longitude)
                ?: initialStation.name
        }
        val cleanName = StationNameSanitizer.sanitize(authenticName).ifBlank { authenticName }
        initialStation.copy(name = cleanName)
    }

    init {
        if (!EvcsStationNameResolver.isGenericStationName(initialStation.name)) {
            stationNameResolver?.cacheStation(initialStation)
        }
    }

    private val _state = MutableStateFlow(
        FocusModeState.createInitial(
            targetStation = initialTargetStation,
            distanceRemainingKm = initialTargetStation.effectiveDistanceKm,
            timestamp = clock()
        )
    )

    /**
     * Observable reactive stream of the latest Focus Mode telemetry state.
     */
    val state: StateFlow<FocusModeState> = _state.asStateFlow()

    private val _voiceAlertEvents = MutableSharedFlow<FocusVoiceAlert>(extraBufferCapacity = 16)

    /**
     * SharedFlow broadcasting discrete voice alert events triggered during telemetry transitions.
     */
    val voiceAlertEvents: SharedFlow<FocusVoiceAlert> = _voiceAlertEvents.asSharedFlow()

    /**
     * Sets mute preference for voice alerts and reflects in state.
     */
    fun setMuted(muted: Boolean) {
        voiceAlertPolicy?.isMuted = muted
        _state.value = _state.value.copy(isAudioMuted = muted)
    }

    /**
     * Updates manual or GPS driver location coordinates.
     */
    fun updateDriverLocation(latitude: Double, longitude: Double) {
        currentDriverLat = latitude
        currentDriverLon = longitude

        val target = _state.value.targetStation
        if (target.latitude != 0.0 && target.longitude != 0.0) {
            val dist = DistanceCalculator.calculateDistanceKm(latitude, longitude, target.latitude, target.longitude)
            _state.value = _state.value.copy(distanceRemainingKm = dist)
        }
    }

    /**
     * Dynamically updates the target station during active focus navigation (e.g. on reroute).
     */
    fun updateTargetStation(newStation: Station) {
        val authenticName = when {
            !EvcsStationNameResolver.isGenericStationName(newStation.name) -> newStation.name
            stationNameResolver != null -> stationNameResolver.resolveStationSync(newStation).name
            else -> newStation.name
        }
        val cleanName = StationNameSanitizer.sanitize(authenticName).ifBlank { authenticName }
        val preservedStation = newStation.copy(name = cleanName)

        val (avail, total) = FocusModeDcFilter.calculateDcSlots(preservedStation)
        val dist = if (currentDriverLat != null && currentDriverLon != null && preservedStation.latitude != 0.0 && preservedStation.longitude != 0.0) {
            DistanceCalculator.calculateDistanceKm(currentDriverLat!!, currentDriverLon!!, preservedStation.latitude, preservedStation.longitude)
        } else {
            preservedStation.effectiveDistanceKm
        }
        _state.value = _state.value.copy(
            targetStation = preservedStation,
            availableDcSlots = avail,
            totalDcSlots = total,
            distanceRemainingKm = dist,
            alternativeStation = null,
            offlineMessage = null,
            connectionStatus = FocusConnectionStatus.CONNECTED
        )
    }


    /**
     * Executes a single polling and evaluation cycle.
     * Useful for deterministic testing or manual refresh triggers.
     *
     * @return Updated [FocusModeState]
     */
    suspend fun pollOnce(): FocusModeState {
        val currentState = _state.value
        val target = currentState.targetStation

        // Determine current driver coordinates
        val coords = locationProvider?.invoke()
            ?: if (currentDriverLat != null && currentDriverLon != null) {
                Pair(currentDriverLat!!, currentDriverLon!!)
            } else {
                Pair(target.latitude, target.longitude)
            }

        val driverLat = coords.first
        val driverLon = coords.second
        currentDriverLat = driverLat
        currentDriverLon = driverLon

        val remainingDist = if (target.latitude != 0.0 && target.longitude != 0.0) {
            DistanceCalculator.calculateDistanceKm(driverLat, driverLon, target.latitude, target.longitude)
        } else {
            currentState.distanceRemainingKm
        }

        // Fetch fresh station telemetry
        val distStr = remainingDist?.let { String.format(locale, "%.1fkm", it) } ?: "N/A"
        AppDebugLogger.log(
            tag = DebugLogTag.FOCUS_MODE,
            level = DebugLogLevel.INFO,
            message = "Focus Mode: Đang lấy dữ liệu trạm ${target.name} (id: ${target.id}, khoảng cách: $distStr)"
        )

        val result = fetchStationTelemetry(target.id, target.latitude, target.longitude)

        if (result.isSuccess) {
            val updatedStation = result.getOrThrow()
            val (availDc, totalDc) = FocusModeDcFilter.calculateDcSlots(updatedStation)
            lastSuccessfulTelemetryTimestamp = clock()

            // Resolve and preserve authentic station name (evcs.vn authentic name preservation)
            val authenticName = when {
                !EvcsStationNameResolver.isGenericStationName(target.name) -> target.name
                !EvcsStationNameResolver.isGenericStationName(initialStation.name) -> initialStation.name
                stationNameResolver != null -> {
                    stationNameResolver.resolveStationName(
                        stationId = updatedStation.id.ifBlank { target.id },
                        latitude = if (updatedStation.latitude != 0.0) updatedStation.latitude else target.latitude,
                        longitude = if (updatedStation.longitude != 0.0) updatedStation.longitude else target.longitude,
                        fallbackName = if (!EvcsStationNameResolver.isGenericStationName(updatedStation.name)) updatedStation.name else target.name
                    )
                }
                !EvcsStationNameResolver.isGenericStationName(updatedStation.name) -> updatedStation.name
                else -> target.name
            }
            val sanitizedAuthenticName = StationNameSanitizer.sanitize(authenticName).ifBlank { authenticName }

            AppDebugLogger.log(
                tag = DebugLogTag.FOCUS_MODE,
                level = DebugLogLevel.SUCCESS,
                message = "Focus Mode: Cập nhật thành công trạm $sanitizedAuthenticName -> Trống $availDc/$totalDc cổng DC"
            )

            var recommendation: AlternativeStationRecommendation? = null

            // If target DC availability is 0, attempt auto-reroute resolution
            if (availDc == 0 && fetchNearbyCandidates != null) {
                AppDebugLogger.log(
                    tag = DebugLogTag.FOCUS_MODE,
                    level = DebugLogLevel.WARN,
                    message = "Focus Mode: Trạm $sanitizedAuthenticName đã hết cổng sạc DC! Bắt đầu tìm trạm thay thế trong bán kính 10km..."
                )
                val candidatesResult = fetchNearbyCandidates.invoke(driverLat, driverLon)
                if (candidatesResult.isSuccess) {
                    val candidates = candidatesResult.getOrThrow()
                    val enrichedCandidates = if (stationNameResolver != null) {
                        candidates.map { candidate ->
                            stationNameResolver.enrichCandidateStation(candidate)
                        }
                    } else {
                        candidates
                    }
                    recommendation = findAlternativeStation(
                        targetStation = updatedStation.copy(name = sanitizedAuthenticName, distanceKm = remainingDist),
                        candidates = enrichedCandidates,
                        driverLat = driverLat,
                        driverLon = driverLon,
                        stationNameResolver = stationNameResolver
                    )
                    if (recommendation != null) {
                        val recDistStr = String.format(locale, "%.1fkm", recommendation.distanceKm)
                        AppDebugLogger.log(
                            tag = DebugLogTag.FOCUS_MODE,
                            level = DebugLogLevel.WARN,
                            message = "Focus Mode: Tìm thấy trạm thay thế: ${recommendation.station.name} (${recommendation.availableDcSlots}/${recommendation.totalDcSlots} cổng trống, cách $recDistStr)"
                        )
                    } else {
                        AppDebugLogger.log(
                            tag = DebugLogTag.FOCUS_MODE,
                            level = DebugLogLevel.WARN,
                            message = "Focus Mode: Không tìm thấy trạm thay thế phù hợp có sẵn cổng sạc."
                        )
                    }
                }
            }

            val updatedWithDist = updatedStation.copy(
                name = sanitizedAuthenticName,
                distanceKm = remainingDist
            )

            _state.value = FocusModeState(
                targetStation = updatedWithDist,
                availableDcSlots = availDc,
                totalDcSlots = totalDc,
                distanceRemainingKm = remainingDist,
                connectionStatus = FocusConnectionStatus.CONNECTED,
                alternativeStation = recommendation,
                offlineMessage = null,
                lastUpdatedTimestamp = lastSuccessfulTelemetryTimestamp,
                isAudioMuted = voiceAlertPolicy?.isMuted ?: currentState.isAudioMuted
            )

            voiceAlertPolicy?.let { policy ->
                val alerts = policy.evaluateAll(_state.value, timestamp = lastSuccessfulTelemetryTimestamp)
                for (alert in alerts) {
                    AppDebugLogger.log(
                        tag = DebugLogTag.FOCUS_MODE,
                        level = DebugLogLevel.INFO,
                        message = "Focus Mode: Phát âm thanh cảnh báo: \"${alert.text}\""
                    )
                    onVoiceAlert?.invoke(alert)
                    _voiceAlertEvents.tryEmit(alert)
                }
            }
        } else {
            // Network failure detection: retain last valid telemetry and flag offline state with timestamp
            val err = result.exceptionOrNull()
            val offlineMsg = FocusModeState.formatOfflineTimestamp(
                timestampMillis = lastSuccessfulTelemetryTimestamp,
                timeZone = timeZone,
                locale = locale
            )
            val currentTarget = currentState.targetStation
            val authenticName = when {
                !EvcsStationNameResolver.isGenericStationName(currentTarget.name) -> currentTarget.name
                !EvcsStationNameResolver.isGenericStationName(initialStation.name) -> initialStation.name
                stationNameResolver != null -> {
                    stationNameResolver.resolveFromCache(currentTarget.id, currentTarget.latitude, currentTarget.longitude)
                        ?: currentTarget.name
                }
                else -> currentTarget.name
            }
            val sanitizedName = StationNameSanitizer.sanitize(authenticName).ifBlank { authenticName }
            val preservedTarget = currentTarget.copy(name = sanitizedName)

            AppDebugLogger.log(
                tag = DebugLogTag.FOCUS_MODE,
                level = DebugLogLevel.ERROR,
                message = "Focus Mode: Lỗi kết nối trạm ${preservedTarget.name} (id: ${preservedTarget.id}): ${err?.message ?: "Lỗi mạng"}. Chuyển sang chế độ ngoại tuyến: \"$offlineMsg\"",
                errorDetails = err?.stackTraceToString()
            )

            _state.value = currentState.copy(
                targetStation = preservedTarget,
                distanceRemainingKm = remainingDist,
                connectionStatus = FocusConnectionStatus.OFFLINE,
                offlineMessage = offlineMsg
            )
        }

        return _state.value
    }

    /**
     * Starts the continuous dynamic polling loop.
     */
    fun start() {
        if (pollingJob != null && pollingJob?.isActive == true) {
            return
        }

        pollingJob = scope.launch {
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    AppDebugLogger.log(
                        tag = DebugLogTag.FOCUS_MODE,
                        level = DebugLogLevel.WARN,
                        message = "Focus Mode: Cảnh báo trong chu kỳ lấy dữ liệu: ${e.message}"
                    )
                }
                val currentDist = _state.value.distanceRemainingKm
                val delayMs = calculatePollingIntervalMs(currentDist)
                delay(delayMs)
            }
        }
    }

    /**
     * Stops polling and releases coroutine resources with zero memory leaks.
     */
    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * True if the polling loop is currently actively running.
     */
    val isRunning: Boolean
        get() = pollingJob?.isActive == true
}
