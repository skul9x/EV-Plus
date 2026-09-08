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
        const val MIN_MID_TRIP_CHARGER_KW = 11.0 // Strictly exclude <= 11kW AC from mid-trip stops
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

        // 5. Multi-Stop Greedy Leapfrog Scheduler
        val safeRangeKm = sanitizedSettings.vehicleSafeRangeKm.toDouble()
        val arrivalBufferSoc = sanitizedSettings.arrivalBufferSocPercent
        val targetSoc = sanitizedSettings.targetChargingSocPercent
        val startSoc = sanitizedSettings.startBatteryPercent

        var currentDistKm = 0.0
        var currentSoC = startSoc
        val stops = mutableListOf<EvRouteStop>()
        val energyWaypoints = mutableListOf<EnergyWaypoint>()
        energyWaypoints.add(EnergyWaypoint(distanceKm = 0.0, batteryPercent = currentSoC, isChargingStop = false))

        var deadZoneWarning: DeadZoneWarning? = null

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

            // Score and select best candidate in window
            val sortedCandidates = inWindow.sortedByDescending { candidate ->
                scoreCandidate(candidate, currentDistKm, maxSafeTravelKm)
            }

            val bestCandidate = sortedCandidates.first()
            val alternatives = sortedCandidates.drop(1).map { it.station }

            // Leg calculations
            val legDistanceKm = bestCandidate.distanceAlongRouteKm - currentDistKm
            val usedSoc = (legDistanceKm / safeRangeKm) * 100.0
            val arrivalSoc = (currentSoC - usedSoc).roundToInt().coerceIn(0, 100)
            val socToCharge = (targetSoc - arrivalSoc).coerceAtLeast(0)

            // Charging duration estimation
            val packKwh = safeRangeKm * PACK_KWH_PER_KM
            val energyKwh = packKwh * (socToCharge / 100.0)
            val effectivePowerKw = bestCandidate.maxPowerKw.coerceIn(30.0, 250.0)
            val rawMinutes = if (socToCharge > 0) {
                ((energyKwh / effectivePowerKw) * 60.0).roundToInt().coerceAtLeast(5)
            } else 0
            val paddedMinutes = sanitizedSettings.applyDurationBuffer(rawMinutes)

            // Live availability & busy queue time estimation
            val (status, queueMin) = evaluateAvailability(bestCandidate.station)

            val stop = EvRouteStop(
                stopIndex = stops.size + 1,
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
                alternativeStations = alternatives
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
        val effectivePowerKw = powerKw.coerceIn(30.0, 250.0)
        val rawMinutes = if (socToCharge > 0) ((energyKwh / effectivePowerKw) * 60.0).roundToInt().coerceAtLeast(5) else 0
        val paddedMinutes = sanitizedSettings.applyDurationBuffer(rawMinutes)

        val (status, queueMin) = evaluateAvailability(alternateStation)
        val oldStop = originalPlan.stops[targetStopIdx]
        val updatedAlternatives = (listOf(oldStop.station) + oldStop.alternativeStations.filter { it.id != alternateStation.id }).distinctBy { it.id }

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
            alternativeStations = updatedAlternatives
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

        return originalPlan.copy(
            stops = newStops,
            totalChargingDurationMinutes = newStops.sumOf { it.estimatedChargingMinutes },
            energyProfile = newWaypoints,
            finalBatteryPercent = finalSoc
        )
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

            // Charger Power Hierarchy: exclude slow AC (<= 11 kW)
            val maxPowerKw = extractMaxPowerKw(station)
            val chargerTier = ChargerTier.fromKw(maxPowerKw)
            if (chargerTier == ChargerTier.SLOW_AC || maxPowerKw <= MIN_MID_TRIP_CHARGER_KW) {
                continue // Strictly exclude slow AC from mid-trip stop suggestions
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
}

/**
 * Result of projecting a point onto a polyline.
 */
data class ProjectedPoint(
    val snapCoordinate: RouteCoordinate,
    val perpendicularDistanceKm: Double,
    val distanceAlongRouteKm: Double
)
