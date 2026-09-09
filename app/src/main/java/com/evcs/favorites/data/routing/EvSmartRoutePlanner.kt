package com.evcs.favorites.data.routing

import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.location.DistanceCalculator
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Internal projection holder for candidate charging stations.
 */
data class CandidateProjection(
    val station: Station,
    val snapCoordinate: RouteCoordinate,
    val perpendicularDistanceKm: Double,
    val distanceAlongRouteKm: Double,
    val maxPowerKw: Double,
    val chargerTier: ChargerTier,
    val isHighwayTrap: Boolean,
    val detourKm: Double
)

/**
 * Spatial bounding box for a route polyline expanded by a buffer distance.
 */
data class RouteBoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double
) {
    /**
     * Checks whether the given GPS coordinate falls within this bounding box.
     */
    fun contains(lat: Double, lng: Double): Boolean {
        return lat in minLat..maxLat && lng in minLng..maxLng
    }

    companion object {
        const val KM_PER_DEGREE_LAT = 111.32

        /**
         * Computes the bounding box enclosing all coordinates of the given polyline,
         * expanded by [bufferKm] with latitude-dependent longitude scaling.
         */
        fun fromPolyline(polyline: List<RouteCoordinate>, bufferKm: Double): RouteBoundingBox {
            if (polyline.isEmpty()) return RouteBoundingBox(0.0, 0.0, 0.0, 0.0)

            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLng = Double.MAX_VALUE
            var maxLng = -Double.MAX_VALUE

            for (coord in polyline) {
                if (coord.latitude < minLat) minLat = coord.latitude
                if (coord.latitude > maxLat) maxLat = coord.latitude
                if (coord.longitude < minLng) minLng = coord.longitude
                if (coord.longitude > maxLng) maxLng = coord.longitude
            }

            val midLatRad = Math.toRadians((minLat + maxLat) * 0.5)
            val cosMidLat = cos(midLatRad).coerceAtLeast(0.01)

            val latBufferDeg = bufferKm / KM_PER_DEGREE_LAT
            val lngBufferDeg = bufferKm / (KM_PER_DEGREE_LAT * cosMidLat)

            return RouteBoundingBox(
                minLat = minLat - latBufferDeg,
                maxLat = maxLat + latBufferDeg,
                minLng = minLng - lngBufferDeg,
                maxLng = maxLng + lngBufferDeg
            )
        }
    }
}

/**
 * Algorithmic corridor route planner and optimizer for EV smart routing.
 *
 * Implements:
 * 1. Base polyline & cumulative prefix-sum distance calculation.
 * 2. Spatial corridor buffer filtering (<= 4.0 km default).
 * 3. Highway dual-carriageway anti-trap detour rejection (> 3.0 km).
 * 4. Charger power hierarchy (Ultra-Fast DC >= 60kW > Standard DC 30kW, exclude slow AC <= 11kW).
 * 5. Multi-stop greedy leapfrog scheduler with safe arrival reserve buffer and dead zone detection.
 * 6. Busy station detection (0 plugs) with queue ETA estimation and alternative station suggestions.
 * 7. Energy corridor profile generation for battery depletion rendering.
 */
class EvSmartRoutePlanner(
    private val coordinator: MultiTierRoutingCoordinator = MultiTierRoutingCoordinator(),
    private val corridorBufferDistanceKm: Double = DEFAULT_CORRIDOR_BUFFER_KM,
    private val maxHighwayDetourKm: Double = DEFAULT_MAX_HIGHWAY_DETOUR_KM
) {
    companion object {
        const val DEFAULT_CORRIDOR_BUFFER_KM = 4.0
        const val DEFAULT_MAX_HIGHWAY_DETOUR_KM = 3.0
        const val MIN_FALLBACK_CHARGER_KW = 20.0
        const val MIN_MID_TRIP_CHARGER_KW = 20.0 // Strictly exclude < 20kW from mid-trip stops
        const val PACK_KWH_PER_KM = 0.18 // Approximate EV battery capacity formula: range * 0.18 kWh
    }

    /**
     * Counter tracking candidate stations evaluated by polyline projection (post bounding-box filter).
     */
    internal var projectionCallCount: Int = 0

    /**
     * Computes the spatial bounding box for a polyline route expanded by [bufferKm].
     */
    fun computeRouteBoundingBox(
        polyline: List<RouteCoordinate>,
        bufferKm: Double = corridorBufferDistanceKm
    ): RouteBoundingBox = RouteBoundingBox.fromPolyline(polyline, bufferKm)

    /**
     * Plans an optimal EV route with required charging stops from origin to destination.
     *
     * @param originLat Origin latitude.
     * @param originLng Origin longitude.
     * @param destLat Destination latitude.
     * @param destLng Destination longitude.
     * @param stations Candidate VinFast charging stations database.
     * @param evSettings Vehicle range, starting SoC, reserve buffer, and target charging SoC.
     * @param routingSettings General routing engine preferences and API keys.
     * @param customRoutePath Optional pre-calculated polyline route (useful for testing or cached paths).
     * @return [EvSmartRoutePlan] containing stops, energy profile, polyline, and warnings if any.
     */
    suspend fun planRoute(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        stations: List<Station>,
        evSettings: EvRoutingSettings = EvRoutingSettings(),
        routingSettings: RoutingSettings = RoutingSettings(),
        customRoutePath: RoutePathResult? = null
    ): EvSmartRoutePlan {
        val sanitizedSettings = evSettings.sanitized()

        // 1. Base Polyline & Distance Profiling
        val routePath = customRoutePath ?: coordinator.calculateRoutePath(
            originLat = originLat,
            originLng = originLng,
            destLat = destLat,
            destLng = destLng,
            settings = routingSettings
        )

        val polyline = if (routePath.coordinates.size >= 2) {
            routePath.coordinates
        } else {
            listOf(
                RouteCoordinate(originLat, originLng),
                RouteCoordinate(destLat, destLng)
            )
        }

        // Prefix-sum cumulative distances: H[i] = sum_{k=1}^i dist(P_{k-1}, P_k)
        val cumulativeDistances = computePrefixSumDistances(polyline)
        val totalDistanceKm = cumulativeDistances.lastOrNull() ?: 0.0

        val drivingDurationSeconds = if (routePath.durationSeconds > 0) {
            routePath.durationSeconds
        } else {
            (totalDistanceKm / (50.0 / 3.6)).roundToLong().coerceAtLeast(60L)
        }

        if (totalDistanceKm <= 0.0) {
            return EvSmartRoutePlan(
                originLat = originLat,
                originLng = originLng,
                destinationLat = destLat,
                destinationLng = destLng,
                totalDistanceKm = 0.0,
                totalDrivingDurationSeconds = 0L,
                totalChargingDurationMinutes = 0,
                stops = emptyList(),
                energyProfile = listOf(
                    EnergyWaypoint(0.0, sanitizedSettings.startBatteryPercent, false)
                ),
                polylineCoordinates = polyline,
                finalBatteryPercent = sanitizedSettings.startBatteryPercent
            )
        }

        // 2 & 3 & 4. Spatial Corridor Buffer, Anti-Trap & Charger Hierarchy Filtering
        val eligibleCandidates = filterAndProjectCandidates(
            stations = stations,
            polyline = polyline,
            cumulativeDistances = cumulativeDistances,
            totalDistanceKm = totalDistanceKm
        )

        // 5. Multi-Stop Greedy Leapfrog Scheduler with Lookahead & Fallback Detection
        val safeRangeKm = sanitizedSettings.vehicleSafeRangeKm.toDouble()
        val arrivalBufferSoc = sanitizedSettings.arrivalBufferSocPercent
        val targetSoc = sanitizedSettings.targetChargingSocPercent
        val startSoc = sanitizedSettings.startBatteryPercent
        val minChargerPowerKw = sanitizedSettings.minChargerPowerKw

        // Subsequent leg reach limits assuming replenishment to targetSoc (85%): D_k = Range_safe * (targetSoc - arrivalBufferSoc) / 100
        val nextLegCapacityKm = safeRangeKm * ((targetSoc - arrivalBufferSoc).coerceAtLeast(0) / 100.0)

        var currentDistKm = 0.0
        var currentSoC = startSoc
        val stops = mutableListOf<EvRouteStop>()
        val energyWaypoints = mutableListOf<EnergyWaypoint>()
        energyWaypoints.add(EnergyWaypoint(distanceKm = 0.0, batteryPercent = currentSoC, isChargingStop = false))

        var deadZoneWarning: DeadZoneWarning? = null
        var insufficientPowerWarning: InsufficientPowerWarning? = null

        while (true) {
            val usableSoc = (currentSoC - arrivalBufferSoc).coerceAtLeast(0)
            val maxSafeTravelKm = safeRangeKm * (usableSoc / 100.0)
            val reachLimitKm = currentDistKm + maxSafeTravelKm

            val remainingToDest = totalDistanceKm - currentDistKm

            // Destination reached safely
            if (remainingToDest <= maxSafeTravelKm) {
                val usedSoc = (remainingToDest / safeRangeKm) * 100.0
                val finalSoc = (currentSoC - usedSoc).roundToInt().coerceIn(arrivalBufferSoc, 100)
                energyWaypoints.add(EnergyWaypoint(distanceKm = totalDistanceKm, batteryPercent = finalSoc, isChargingStop = false))
                break
            }

            // Must select charging stop in reach window: (currentDistKm + 1.0, reachLimitKm]
            val inWindow = eligibleCandidates.filter { candidate ->
                candidate.distanceAlongRouteKm > currentDistKm + 1.0 &&
                        candidate.distanceAlongRouteKm <= reachLimitKm &&
                        candidate.distanceAlongRouteKm <= totalDistanceKm - 0.5
            }

            if (inWindow.isEmpty()) {
                // DEAD ZONE DETECTED: No station reachable before reserve exhaustion
                val nextStation = eligibleCandidates.firstOrNull { it.distanceAlongRouteKm > reachLimitKm }
                val gapEndKm = nextStation?.distanceAlongRouteKm ?: totalDistanceKm
                val gapStartKm = reachLimitKm
                val missingKm = (gapEndKm - gapStartKm).coerceAtLeast(0.1)

                deadZoneWarning = DeadZoneWarning(
                    gapStartKm = gapStartKm,
                    gapEndKm = gapEndKm,
                    missingRangeKm = missingKm,
                    safeRangeKm = sanitizedSettings.vehicleSafeRangeKm,
                    message = "Cảnh báo vùng trắng sạc: Khoảng cách giữa các trạm vượt quá tầm vận hành an toàn ${sanitizedSettings.vehicleSafeRangeKm} km (thiếu ${missingKm.roundToInt().coerceAtLeast(1)} km)."
                )

                // Add battery exhaustion waypoint at gapStartKm
                energyWaypoints.add(EnergyWaypoint(distanceKm = gapStartKm, batteryPercent = arrivalBufferSoc, isChargingStop = false))
                break
            }

            // Classify projected corridor candidates into two tiers:
            // Tier 1 (Target Power): maxPowerKw >= minChargerPowerKw
            // Tier 2 (Fallback Power): 20.0 kW <= maxPowerKw < minChargerPowerKw
            val tier1Candidates = inWindow.filter { it.maxPowerKw >= minChargerPowerKw }
            val tier2Candidates = inWindow.filter { it.maxPowerKw < minChargerPowerKw }

            // Forward-Reachability Lookahead: verify that selecting candidate leaves at least
            // one subsequent station (or destination) reachable on the next leg (D_k).
            val tier1Viable = tier1Candidates.filter { candidate ->
                hasForwardConnectivity(candidate, eligibleCandidates, totalDistanceKm, nextLegCapacityKm)
            }

            val (bestCandidate, isFallback) = when {
                tier1Viable.isNotEmpty() -> {
                    val best = tier1Viable.maxByOrNull { scoreCandidate(it, currentDistKm, maxSafeTravelKm) }!!
                    Pair(best, false)
                }
                tier1Candidates.isNotEmpty() -> {
                    // All Tier 1 candidates in window lead to an avoidable dead-end.
                    // Check if an earlier Tier 2 candidate maintains forward connectivity.
                    val tier2Viable = tier2Candidates.filter { candidate ->
                        hasForwardConnectivity(candidate, eligibleCandidates, totalDistanceKm, nextLegCapacityKm)
                    }
                    if (tier2Viable.isNotEmpty()) {
                        val best = tier2Viable.maxByOrNull { scoreCandidate(it, currentDistKm, maxSafeTravelKm) }!!
                        Pair(best, true)
                    } else {
                        // Unavoidable dead end ahead, pick best Tier 1 candidate to maximize progress
                        val best = tier1Candidates.maxByOrNull { scoreCandidate(it, currentDistKm, maxSafeTravelKm) }!!
                        Pair(best, false)
                    }
                }
                else -> {
                    // No Tier 1 candidate available in window: seamlessly fallback to Tier 2 candidate
                    val tier2Viable = tier2Candidates.filter { candidate ->
                        hasForwardConnectivity(candidate, eligibleCandidates, totalDistanceKm, nextLegCapacityKm)
                    }
                    val candidatePool = if (tier2Viable.isNotEmpty()) tier2Viable else tier2Candidates
                    val best = candidatePool.maxByOrNull { scoreCandidate(it, currentDistKm, maxSafeTravelKm) }!!
                    Pair(best, true)
                }
            }

            val alternatives = inWindow
                .filter { it.station.id != bestCandidate.station.id }
                .sortedWith(
                    compareByDescending<CandidateProjection> { it.maxPowerKw >= minChargerPowerKw }
                        .thenByDescending { scoreCandidate(it, currentDistKm, maxSafeTravelKm) }
                )
                .map { it.station }

            // Leg calculations
            val legDistanceKm = bestCandidate.distanceAlongRouteKm - currentDistKm
            val usedSoc = (legDistanceKm / safeRangeKm) * 100.0
            val arrivalSoc = (currentSoC - usedSoc).roundToInt().coerceIn(0, 100)
            val socToCharge = (targetSoc - arrivalSoc).coerceAtLeast(0)

            // Charging duration estimation
            val packKwh = safeRangeKm * PACK_KWH_PER_KM
            val energyKwh = packKwh * (socToCharge / 100.0)
            val effectivePowerKw = bestCandidate.maxPowerKw.coerceIn(20.0, 250.0)
            val rawMinutes = if (socToCharge > 0) {
                ((energyKwh / effectivePowerKw) * 60.0).roundToInt().coerceAtLeast(5)
            } else 0
            val paddedMinutes = sanitizedSettings.applyDurationBuffer(rawMinutes)

            // Live availability & busy queue time estimation
            val (status, queueMin) = evaluateAvailability(bestCandidate.station)

            val currentStopIndex = stops.size + 1
            if (isFallback && insufficientPowerWarning == null) {
                insufficientPowerWarning = InsufficientPowerWarning(
                    requiredPowerKw = minChargerPowerKw,
                    fallbackStation = bestCandidate.station,
                    fallbackPowerKw = bestCandidate.maxPowerKw,
                    stopIndex = currentStopIndex,
                    legDistanceKm = legDistanceKm,
                    message = "Không tìm thấy trạm sạc đạt công suất yêu cầu ${minChargerPowerKw.toInt()} kW trong tầm pin. Đã chọn trạm thay thế ${bestCandidate.station.name} (${bestCandidate.maxPowerKw.toInt()} kW) để tiếp tục lộ trình."
                )
            }

            val backupStation = selectBackupStation(
                primaryCandidate = bestCandidate,
                eligibleCandidates = eligibleCandidates,
                allStations = stations,
                polyline = polyline,
                cumulativeDistances = cumulativeDistances,
                currentDistKm = currentDistKm,
                currentSoC = currentSoC,
                safeRangeKm = safeRangeKm
            )

            val stop = EvRouteStop(
                stopIndex = currentStopIndex,
                station = bestCandidate.station,
                distanceFromOriginKm = bestCandidate.distanceAlongRouteKm,
                distanceFromPreviousStopKm = legDistanceKm,
                arrivalBatteryPercent = arrivalSoc,
                targetBatteryPercent = targetSoc,
                estimatedChargingMinutes = paddedMinutes,
                rawChargingMinutes = rawMinutes,
                maxPowerKw = bestCandidate.maxPowerKw,
                chargerTier = bestCandidate.chargerTier,
                availabilityStatus = status,
                estimatedQueueMinutes = queueMin,
                alternativeStations = alternatives,
                backupStation = backupStation
            )
            stops.add(stop)

            // Energy corridor waypoints: arrival point, then replenishment jump at departure
            energyWaypoints.add(EnergyWaypoint(distanceKm = bestCandidate.distanceAlongRouteKm, batteryPercent = arrivalSoc, isChargingStop = false))
            energyWaypoints.add(EnergyWaypoint(distanceKm = bestCandidate.distanceAlongRouteKm, batteryPercent = targetSoc, isChargingStop = true))

            currentDistKm = bestCandidate.distanceAlongRouteKm
            currentSoC = targetSoc
        }

        val totalChargingMinutes = stops.sumOf { it.estimatedChargingMinutes }
        val finalBatteryPercent = energyWaypoints.lastOrNull()?.batteryPercent ?: 0

        return EvSmartRoutePlan(
            originLat = originLat,
            originLng = originLng,
            destinationLat = destLat,
            destinationLng = destLng,
            totalDistanceKm = totalDistanceKm,
            totalDrivingDurationSeconds = drivingDurationSeconds,
            totalChargingDurationMinutes = totalChargingMinutes,
            stops = stops,
            energyProfile = energyWaypoints,
            polylineCoordinates = polyline,
            deadZoneWarning = deadZoneWarning,
            insufficientPowerWarning = insufficientPowerWarning,
            finalBatteryPercent = finalBatteryPercent
        )
    }

    /**
     * Recomputes an existing route plan by swapping a specific stop with an alternate station.
     */
    fun recalculateWithAlternateStop(
        originalPlan: EvSmartRoutePlan,
        stopIndex: Int,
        alternateStation: Station,
        evSettings: EvRoutingSettings = EvRoutingSettings()
    ): EvSmartRoutePlan {
        val sanitizedSettings = evSettings.sanitized()
        val safeRangeKm = sanitizedSettings.vehicleSafeRangeKm.toDouble()
        val targetSoc = sanitizedSettings.targetChargingSocPercent

        val targetStopIdx = stopIndex - 1
        if (targetStopIdx !in originalPlan.stops.indices) {
            return originalPlan
        }

        // Project alternate station onto polyline
        val prefixSums = computePrefixSumDistances(originalPlan.polylineCoordinates)
        val proj = projectPointOntoPolyline(
            alternateStation.latitude,
            alternateStation.longitude,
            originalPlan.polylineCoordinates,
            prefixSums
        ) ?: return originalPlan

        val prevDist = if (targetStopIdx == 0) 0.0 else originalPlan.stops[targetStopIdx - 1].distanceFromOriginKm
        val prevSoc = if (targetStopIdx == 0) sanitizedSettings.startBatteryPercent else targetSoc

        val legDistance = proj.distanceAlongRouteKm - prevDist
        val usedSoc = (legDistance / safeRangeKm) * 100.0
        val arrivalSoc = (prevSoc - usedSoc).roundToInt().coerceIn(0, 100)
        val socToCharge = (targetSoc - arrivalSoc).coerceAtLeast(0)

        val powerKw = extractMaxPowerKw(alternateStation)
        val packKwh = safeRangeKm * PACK_KWH_PER_KM
        val energyKwh = packKwh * (socToCharge / 100.0)
        val effectivePowerKw = powerKw.coerceIn(20.0, 250.0)
        val rawMinutes = if (socToCharge > 0) ((energyKwh / effectivePowerKw) * 60.0).roundToInt().coerceAtLeast(5) else 0
        val paddedMinutes = sanitizedSettings.applyDurationBuffer(rawMinutes)

        val (status, queueMin) = evaluateAvailability(alternateStation)
        val oldStop = originalPlan.stops[targetStopIdx]
        val updatedAlternatives = (listOf(oldStop.station) + oldStop.alternativeStations.filter { it.id != alternateStation.id }).distinctBy { it.id }
        val newBackupStation = if (oldStop.station.id != alternateStation.id) oldStop.station else oldStop.backupStation

        val newStop = EvRouteStop(
            stopIndex = stopIndex,
            station = alternateStation,
            distanceFromOriginKm = proj.distanceAlongRouteKm,
            distanceFromPreviousStopKm = legDistance,
            arrivalBatteryPercent = arrivalSoc,
            targetBatteryPercent = targetSoc,
            estimatedChargingMinutes = paddedMinutes,
            rawChargingMinutes = rawMinutes,
            maxPowerKw = powerKw,
            chargerTier = ChargerTier.fromKw(powerKw),
            availabilityStatus = status,
            estimatedQueueMinutes = queueMin,
            alternativeStations = updatedAlternatives,
            backupStation = newBackupStation
        )

        val newStops = originalPlan.stops.toMutableList()
        newStops[targetStopIdx] = newStop

        // Rebuild energy profile
        val newWaypoints = mutableListOf<EnergyWaypoint>()
        newWaypoints.add(EnergyWaypoint(0.0, sanitizedSettings.startBatteryPercent, false))
        for (st in newStops) {
            newWaypoints.add(EnergyWaypoint(st.distanceFromOriginKm, st.arrivalBatteryPercent, false))
            newWaypoints.add(EnergyWaypoint(st.distanceFromOriginKm, st.targetBatteryPercent, true))
        }

        val lastStopDist = newStops.last().distanceFromOriginKm
        val distToDest = originalPlan.totalDistanceKm - lastStopDist
        val usedSocFinal = (distToDest / safeRangeKm) * 100.0
        val finalSoc = (targetSoc - usedSocFinal).roundToInt().coerceIn(0, 100)
        newWaypoints.add(EnergyWaypoint(originalPlan.totalDistanceKm, finalSoc, false))

        val firstFallback = newStops.firstOrNull { it.maxPowerKw < sanitizedSettings.minChargerPowerKw }
        val updatedWarning = if (firstFallback != null) {
            InsufficientPowerWarning(
                requiredPowerKw = sanitizedSettings.minChargerPowerKw,
                fallbackStation = firstFallback.station,
                fallbackPowerKw = firstFallback.maxPowerKw,
                stopIndex = firstFallback.stopIndex,
                legDistanceKm = firstFallback.distanceFromPreviousStopKm,
                message = "Không tìm thấy trạm sạc đạt công suất yêu cầu ${sanitizedSettings.minChargerPowerKw.toInt()} kW trong tầm pin. Đã chọn trạm thay thế ${firstFallback.station.name} (${firstFallback.maxPowerKw.toInt()} kW) để tiếp tục lộ trình."
            )
        } else {
            null
        }

        return originalPlan.copy(
            stops = newStops,
            totalChargingDurationMinutes = newStops.sumOf { it.estimatedChargingMinutes },
            energyProfile = newWaypoints,
            insufficientPowerWarning = updatedWarning,
            finalBatteryPercent = finalSoc
        )
    }

    /**
     * Recalculates route with a relaxed minimum charger power threshold.
     */
    suspend fun planRouteWithRelaxedPower(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        stations: List<Station>,
        relaxedPowerKw: Double,
        evSettings: EvRoutingSettings = EvRoutingSettings(),
        routingSettings: RoutingSettings = RoutingSettings(),
        customRoutePath: RoutePathResult? = null
    ): EvSmartRoutePlan {
        val updatedSettings = evSettings.copy(minChargerPowerKw = relaxedPowerKw)
        return planRoute(
            originLat = originLat,
            originLng = originLng,
            destLat = destLat,
            destLng = destLng,
            stations = stations,
            evSettings = updatedSettings,
            routingSettings = routingSettings,
            customRoutePath = customRoutePath
        )
    }

    /**
     * Convenience overload to re-plan an existing [originalPlan] with relaxed power threshold,
     * reusing its existing polyline coordinates and duration.
     */
    suspend fun planRouteWithRelaxedPower(
        originalPlan: EvSmartRoutePlan,
        stations: List<Station>,
        relaxedPowerKw: Double,
        evSettings: EvRoutingSettings = EvRoutingSettings(),
        routingSettings: RoutingSettings = RoutingSettings()
    ): EvSmartRoutePlan {
        return planRouteWithRelaxedPower(
            originLat = originalPlan.originLat,
            originLng = originalPlan.originLng,
            destLat = originalPlan.destinationLat,
            destLng = originalPlan.destinationLng,
            stations = stations,
            relaxedPowerKw = relaxedPowerKw,
            evSettings = evSettings,
            routingSettings = routingSettings,
            customRoutePath = RoutePathResult(
                coordinates = originalPlan.polylineCoordinates,
                distanceMeters = (originalPlan.totalDistanceKm * 1000).roundToLong(),
                durationSeconds = originalPlan.totalDrivingDurationSeconds
            )
        )
    }

    /**
     * Lookahead forward-reachability check: verifies that selecting [candidate] leaves at least
     * one subsequent station (or destination) reachable on the subsequent leg.
     */
    internal fun hasForwardConnectivity(
        candidate: CandidateProjection,
        allCandidates: List<CandidateProjection>,
        totalDistanceKm: Double,
        nextLegCapacityKm: Double
    ): Boolean {
        val nextReachLimit = candidate.distanceAlongRouteKm + nextLegCapacityKm
        if (nextReachLimit >= totalDistanceKm) {
            return true
        }
        return allCandidates.any { nextStation ->
            nextStation.distanceAlongRouteKm > candidate.distanceAlongRouteKm + 1.0 &&
                    nextStation.distanceAlongRouteKm <= nextReachLimit &&
                    nextStation.distanceAlongRouteKm <= totalDistanceKm - 0.5
        }
    }

    /**
     * Computes prefix-sum cumulative distances along the polyline.
     */
    fun computePrefixSumDistances(polyline: List<RouteCoordinate>): DoubleArray {
        if (polyline.isEmpty()) return DoubleArray(0)
        val distances = DoubleArray(polyline.size)
        distances[0] = 0.0
        for (i in 1 until polyline.size) {
            val prev = polyline[i - 1]
            val curr = polyline[i]
            val segDist = DistanceCalculator.calculateDistanceKm(
                prev.latitude, prev.longitude,
                curr.latitude, curr.longitude
            )
            distances[i] = distances[i - 1] + segDist
        }
        return distances
    }

    /**
     * Filters candidate stations within corridor buffer, rejects highway traps, and excludes slow AC.
     * Uses spatial bounding box O(1) early rejection before evaluating polyline segments.
     */
    internal fun filterAndProjectCandidates(
        stations: List<Station>,
        polyline: List<RouteCoordinate>,
        cumulativeDistances: DoubleArray,
        totalDistanceKm: Double
    ): List<CandidateProjection> {
        val candidates = mutableListOf<CandidateProjection>()
        val boundingBox = computeRouteBoundingBox(polyline, corridorBufferDistanceKm)
        projectionCallCount = 0

        for (station in stations) {
            if (station.latitude == 0.0 && station.longitude == 0.0) continue

            // 1. Spatial bounding box O(1) early candidate discard
            if (!boundingBox.contains(station.latitude, station.longitude)) {
                continue
            }

            projectionCallCount++

            val projection = projectPointOntoPolyline(
                station.latitude,
                station.longitude,
                polyline,
                cumulativeDistances
            ) ?: continue

            // Spatial Corridor Buffer filtering (<= 4.0 km)
            if (projection.perpendicularDistanceKm > corridorBufferDistanceKm) {
                continue
            }

            // Bound checks along the route
            if (projection.distanceAlongRouteKm < 0.5 || projection.distanceAlongRouteKm > totalDistanceKm - 0.5) {
                continue
            }

            // Highway Dual-Carriageway Anti-Trap evaluation
            val (isTrap, detourKm) = evaluateHighwayDetour(
                station = station,
                perpendicularDistanceKm = projection.perpendicularDistanceKm
            )
            if (isTrap) {
                continue // Reject opposite-carriageway or detour > 3km station immediately
            }

            // Charger Power Hierarchy: exclude slow chargers (< 20 kW)
            val maxPowerKw = extractMaxPowerKw(station)
            val chargerTier = ChargerTier.fromKw(maxPowerKw)
            if (chargerTier == ChargerTier.SLOW_AC || maxPowerKw < MIN_FALLBACK_CHARGER_KW) {
                continue // Strictly exclude slow chargers (< 20 kW) from mid-trip stop suggestions
            }

            candidates.add(
                CandidateProjection(
                    station = station,
                    snapCoordinate = projection.snapCoordinate,
                    perpendicularDistanceKm = projection.perpendicularDistanceKm,
                    distanceAlongRouteKm = projection.distanceAlongRouteKm,
                    maxPowerKw = maxPowerKw,
                    chargerTier = chargerTier,
                    isHighwayTrap = isTrap,
                    detourKm = detourKm
                )
            )
        }

        return candidates.sortedBy { it.distanceAlongRouteKm }
    }

    /**
     * Projects a GPS coordinate onto polyline segments to find minimum perpendicular distance and distance along route.
     * Employs localized Euclidean squared distance during segment traversal to eliminate intermediate trigonometric calls,
     * computing exact Haversine distance only once on the winning snap coordinate.
     */
    fun projectPointOntoPolyline(
        pointLat: Double,
        pointLng: Double,
        polyline: List<RouteCoordinate>,
        cumulativeDistances: DoubleArray
    ): ProjectedPoint? {
        if (polyline.size < 2 || cumulativeDistances.size != polyline.size) return null

        var minDistanceSqKm = Double.MAX_VALUE
        var bestAlongRouteKm = 0.0
        var bestSnapCoord = polyline[0]

        for (i in 1 until polyline.size) {
            val a = polyline[i - 1]
            val b = polyline[i]

            val midLatRad = Math.toRadians((a.latitude + b.latitude) * 0.5)
            val degToKmLat = RouteBoundingBox.KM_PER_DEGREE_LAT
            val degToKmLng = RouteBoundingBox.KM_PER_DEGREE_LAT * cos(midLatRad)

            val dx = (b.longitude - a.longitude) * degToKmLng
            val dy = (b.latitude - a.latitude) * degToKmLat

            val sx = (pointLng - a.longitude) * degToKmLng
            val sy = (pointLat - a.latitude) * degToKmLat

            val segLenSq = dx * dx + dy * dy
            val t = if (segLenSq < 1e-10) {
                0.0
            } else {
                ((sx * dx + sy * dy) / segLenSq).coerceIn(0.0, 1.0)
            }

            // Localized flat Euclidean perpendicular offset vector from snap point to target point
            val px = sx - t * dx
            val py = sy - t * dy
            val distSqKm = px * px + py * py

            if (distSqKm < minDistanceSqKm) {
                minDistanceSqKm = distSqKm
                val snapLat = a.latitude + t * (b.latitude - a.latitude)
                val snapLng = a.longitude + t * (b.longitude - a.longitude)
                bestSnapCoord = RouteCoordinate(snapLat, snapLng)
                bestAlongRouteKm = cumulativeDistances[i - 1] + t * (cumulativeDistances[i] - cumulativeDistances[i - 1])
            }
        }

        // Exact Haversine distance computed once on the best projected segment
        val exactPerpDistKm = DistanceCalculator.calculateDistanceKm(
            pointLat, pointLng,
            bestSnapCoord.latitude, bestSnapCoord.longitude
        )

        return ProjectedPoint(
            snapCoordinate = bestSnapCoord,
            perpendicularDistanceKm = exactPerpDistKm,
            distanceAlongRouteKm = bestAlongRouteKm
        )
    }

    /**
     * Evaluates whether a station is caught in an opposite-lane highway trap with detour > 3.0 km.
     */
    fun evaluateHighwayDetour(
        station: Station,
        perpendicularDistanceKm: Double
    ): Pair<Boolean, Double> {
        val text = "${station.name} ${station.address} ${station.summary}".lowercase()

        // Explicit opposite direction mentions
        val hasOppositeKeywords = text.contains("hướng ngược lại") ||
                text.contains("chiều ngược lại") ||
                text.contains("opposite direction") ||
                text.contains("làn ngược") ||
                text.contains("bên kia đường cao tốc") ||
                text.contains("ngược chiều") ||
                text.contains("dual-carriageway trap")

        if (hasOppositeKeywords) {
            return Pair(true, 8.0) // Highway barrier trap
        }

        // Check if station has drivingMetrics with detour > 3.0 km
        val drivingMetrics = station.drivingMetrics
        if (drivingMetrics != null) {
            val drivingDistKm = drivingMetrics.distanceMeters / 1000.0
            val directKm = perpendicularDistanceKm
            val detourKm = (drivingDistKm - directKm).coerceAtLeast(0.0)
            val isHighway = text.contains("cao tốc") || text.contains("expressway") ||
                    text.contains("ct01") || text.contains("ct02") || text.contains("đct")
            if (isHighway && detourKm > maxHighwayDetourKm) {
                return Pair(true, detourKm)
            }
        }

        // Textual detour annotation (e.g. "detour: 4.5km")
        val detourMatch = Regex("""detour[:\s]+(\d+(?:\.\d+)?)\s*km""", RegexOption.IGNORE_CASE).find(text)
        if (detourMatch != null) {
            val detourKm = detourMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            if (detourKm > maxHighwayDetourKm) {
                return Pair(true, detourKm)
            }
        }

        return Pair(false, perpendicularDistanceKm)
    }

    /**
     * Extracts peak charging power in kW from station powers, labels, and text descriptions.
     */
    fun extractMaxPowerKw(station: Station): Double {
        val maxWatts = station.powers.maxOfOrNull { it.typeWatts } ?: 0L
        if (maxWatts > 0) {
            return maxWatts / 1000.0
        }

        val allText = buildString {
            station.powers.forEach { append("${it.label} ${it.displayString} ") }
            append("${station.connectors} ${station.summary} ${station.name}")
        }

        val kwMatches = Regex("""(\d+(?:\.\d+)?)\s*k[wW]""").findAll(allText)
        val maxFromText = kwMatches.mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull()
        if (maxFromText != null) return maxFromText

        if (allText.contains("DC", ignoreCase = true) || allText.contains("Super", ignoreCase = true)) {
            return 60.0
        }

        return 11.0
    }

    /**
     * Evaluates live telemetry availability and calculates queue ETA if station is busy.
     */
    private fun evaluateAvailability(station: Station): Pair<StopAvailabilityStatus, Int> {
        val hasTelemetry = station.totalPlugs > 0
        return when {
            !hasTelemetry -> Pair(StopAvailabilityStatus.UNKNOWN, 0)
            station.totalAvailablePlugs > 0 -> Pair(StopAvailabilityStatus.AVAILABLE, 0)
            else -> {
                // Busy station (0 live plugs available)
                val estimatedQueueMinutes = (30 / station.totalPlugs.coerceAtLeast(1)).coerceIn(10, 30)
                Pair(StopAvailabilityStatus.STATION_BUSY, estimatedQueueMinutes)
            }
        }
    }

    /**
     * Scores a candidate station within reach window:
     * - Power hierarchy (Ultra-Fast DC 100 pts, Standard DC 50 pts)
     * - Live availability (Available 30 pts, Busy 0 pts)
     * - Leapfrog progress (progress along window * 40 pts)
     * - Off-route corridor penalty (- perpendicularKm * 2 pts)
     */
    private fun scoreCandidate(
        candidate: CandidateProjection,
        currentDistKm: Double,
        maxSafeTravelKm: Double
    ): Double {
        val powerScore = when (candidate.chargerTier) {
            ChargerTier.ULTRA_FAST_DC -> 100.0
            ChargerTier.STANDARD_DC -> 50.0
            ChargerTier.SLOW_AC -> 0.0
        }

        val availabilityScore = if (candidate.station.totalAvailablePlugs > 0) 30.0 else 0.0

        val progressFraction = if (maxSafeTravelKm > 0) {
            ((candidate.distanceAlongRouteKm - currentDistKm) / maxSafeTravelKm).coerceIn(0.0, 1.0)
        } else 0.0
        val progressScore = progressFraction * 40.0

        val corridorPenalty = candidate.perpendicularDistanceKm * 2.0

        return powerScore + availabilityScore + progressScore - corridorPenalty
    }

    /**
     * Straight-line distance between primary and backup stations in kilometers.
     */
    fun distanceFromPrimaryStationKm(primary: Station, backup: Station): Double {
        return com.evcs.favorites.data.routing.distanceFromPrimaryStationKm(primary, backup)
    }

    private data class BackupCandidate(
        val station: Station,
        val maxPowerKw: Double,
        val distanceFromPrimaryKm: Double,
        val hasPlugs: Boolean
    )

    /**
     * Identifies the optimal backup station adjacent to [primaryCandidate]:
     * 1. Proximity: Within <= 10.0 km straight-line or corridor distance from primary station.
     * 2. Highway safety: Must NOT be an opposite-carriageway highway trap (isHighwayTrap == false).
     * 3. Detour penalty: Perpendicular distance from route corridor <= 5.0 km.
     * 4. Energy reachability: Must be safely reachable from previous stop (arrivalSoc >= 5%).
     * 5. Ranking: Prioritize live available plugs (totalAvailablePlugs > 0), higher power, and lower diversion distance.
     */
    internal fun selectBackupStation(
        primaryCandidate: CandidateProjection,
        eligibleCandidates: List<CandidateProjection>,
        allStations: List<Station>,
        polyline: List<RouteCoordinate>,
        cumulativeDistances: DoubleArray,
        currentDistKm: Double,
        currentSoC: Int,
        safeRangeKm: Double
    ): Station? {
        val eligibleMap = eligibleCandidates.associateBy { it.station.id }
        val backupCandidates = mutableListOf<BackupCandidate>()

        // 1. Evaluate all candidates already projected along route corridor
        for (candidate in eligibleCandidates) {
            if (candidate.station.id == primaryCandidate.station.id) continue

            val straightDistKm = distanceFromPrimaryStationKm(primaryCandidate.station, candidate.station)
            val corridorDistKm = kotlin.math.abs(candidate.distanceAlongRouteKm - primaryCandidate.distanceAlongRouteKm)

            // Proximity: straight-line or corridor distance <= 10.0 km
            if (straightDistKm > 10.0 && corridorDistKm > 10.0) continue

            // Highway safety & corridor detour
            if (candidate.isHighwayTrap) continue
            if (candidate.perpendicularDistanceKm > 5.0) continue

            // Energy reachability from previous stop: arrivalSoc >= 5%
            val legDistKm = candidate.distanceAlongRouteKm - currentDistKm
            if (legDistKm <= 0.0) continue
            val arrivalSoc = currentSoC - (legDistKm / safeRangeKm) * 100.0
            if (arrivalSoc < 5.0) continue

            // Charger power hierarchy (must not be slow AC < 20kW)
            if (candidate.maxPowerKw < MIN_FALLBACK_CHARGER_KW) continue

            backupCandidates.add(
                BackupCandidate(
                    station = candidate.station,
                    maxPowerKw = candidate.maxPowerKw,
                    distanceFromPrimaryKm = straightDistKm,
                    hasPlugs = candidate.station.totalAvailablePlugs > 0
                )
            )
        }

        // 2. Also inspect any stations in allStations within straight-line 10.0 km that might not be in eligibleCandidates
        for (st in allStations) {
            if (st.id == primaryCandidate.station.id || eligibleMap.containsKey(st.id)) continue
            if (st.latitude == 0.0 && st.longitude == 0.0) continue

            val straightDistKm = distanceFromPrimaryStationKm(primaryCandidate.station, st)
            if (straightDistKm > 10.0) continue

            val proj = projectPointOntoPolyline(st.latitude, st.longitude, polyline, cumulativeDistances) ?: continue
            if (proj.perpendicularDistanceKm > 5.0) continue

            val (isTrap, _) = evaluateHighwayDetour(st, proj.perpendicularDistanceKm)
            if (isTrap) continue

            val legDistKm = proj.distanceAlongRouteKm - currentDistKm
            if (legDistKm <= 0.0) continue
            val arrivalSoc = currentSoC - (legDistKm / safeRangeKm) * 100.0
            if (arrivalSoc < 5.0) continue

            val powerKw = extractMaxPowerKw(st)
            if (powerKw < MIN_FALLBACK_CHARGER_KW) continue

            backupCandidates.add(
                BackupCandidate(
                    station = st,
                    maxPowerKw = powerKw,
                    distanceFromPrimaryKm = straightDistKm,
                    hasPlugs = st.totalAvailablePlugs > 0
                )
            )
        }

        return backupCandidates
            .distinctBy { it.station.id }
            .sortedWith(
                compareByDescending<BackupCandidate> { it.hasPlugs }
                    .thenByDescending { it.maxPowerKw }
                    .thenBy { it.distanceFromPrimaryKm }
            )
            .firstOrNull()?.station
    }
}

/**
 * Result of projecting a point onto a polyline.
 */
data class ProjectedPoint(
    val snapCoordinate: RouteCoordinate,
    val perpendicularDistanceKm: Double,
    val distanceAlongRouteKm: Double
)
