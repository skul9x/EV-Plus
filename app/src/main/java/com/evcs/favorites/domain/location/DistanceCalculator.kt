package com.evcs.favorites.domain.location

import com.evcs.favorites.data.model.Station
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Utility for calculating great-circle distances using the Haversine formula,
 * formatting human-readable distance strings, and sorting charging stations nearest-first.
 */
object DistanceCalculator {

    /** Mean radius of Earth in kilometers. */
    const val EARTH_RADIUS_KM = 6371.0

    /**
     * Calculates great-circle distance in kilometers between two GPS coordinates
     * using the Haversine formula.
     *
     * @param lat1 Latitude of point 1 in degrees
     * @param lon1 Longitude of point 1 in degrees
     * @param lat2 Latitude of point 2 in degrees
     * @param lon2 Longitude of point 2 in degrees
     * @return Distance in kilometers
     */
    fun calculateDistanceKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        if (lat1 == lat2 && lon1 == lon2) {
            return 0.0
        }

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val radLat1 = Math.toRadians(lat1)
        val radLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2.0).pow(2.0) +
                cos(radLat1) * cos(radLat2) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))

        return EARTH_RADIUS_KM * c
    }

    /**
     * Calculates great-circle distance in meters between two GPS coordinates.
     */
    fun calculateDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        return calculateDistanceKm(lat1, lon1, lat2, lon2) * 1000.0
    }

    /**
     * Formats distance into a human-friendly string:
     * - Meters if < 1 km (e.g., "450 m", "850 m")
     * - Kilometers with 1 decimal place if >= 1 km (e.g., "1.2 km", "15.0 km")
     * - Returns empty string if distance is null or NaN
     *
     * @param distanceKm Distance in kilometers
     * @return Human-readable formatted string
     */
    fun formatDistance(distanceKm: Double?): String {
        if (distanceKm == null || distanceKm.isNaN()) {
            return ""
        }
        if (distanceKm <= 0.0) {
            return "0 m"
        }

        return if (distanceKm < 1.0) {
            val meters = (distanceKm * 1000.0).roundToInt()
            if (meters >= 1000) {
                "1.0 km"
            } else {
                "$meters m"
            }
        } else {
            String.format(Locale.US, "%.1f km", distanceKm)
        }
    }

    /**
     * Calculates distance from user to station and returns a copy with [Station.distanceKm] set.
     */
    fun attachDistance(station: Station, userLat: Double?, userLon: Double?): Station {
        if (userLat == null || userLon == null) {
            return station
        }
        if (station.latitude == 0.0 && station.longitude == 0.0) {
            return station.copy(distanceKm = null)
        }

        val distance = calculateDistanceKm(
            lat1 = userLat,
            lon1 = userLon,
            lat2 = station.latitude,
            lon2 = station.longitude
        )
        return station.copy(distanceKm = distance)
    }

    /**
     * Attaches computed distance to each station in the list.
     */
    fun attachDistances(
        stations: List<Station>,
        userLat: Double?,
        userLon: Double?
    ): List<Station> {
        if (userLat == null || userLon == null) {
            return stations
        }
        return stations.map { attachDistance(it, userLat, userLon) }
    }

    /**
     * Computes distances from user location and sorts stations nearest-first (ascending distance).
     * Stations with missing or unknown coordinates are placed at the end of the list.
     *
     * @param stations List of favorite stations
     * @param userLat User's latitude
     * @param userLon User's longitude
     * @return Stations sorted nearest-first with updated distanceKm
     */
    fun sortByDistance(
        stations: List<Station>,
        userLat: Double?,
        userLon: Double?
    ): List<Station> {
        val stationsWithDistances = attachDistances(stations, userLat, userLon)
        return stationsWithDistances.sortedWith(compareBy(nullsLast()) { it.distanceKm })
    }

    /**
     * Groups a collection of GPS coordinate points (lat, lon) into geographic clusters
     * where stations in a cluster are within [maxDistanceKm] (default 15.0 km) of each other.
     * Points with (0.0, 0.0) or invalid values are filtered out.
     *
     * @param points List of (latitude, longitude) coordinate pairs.
     * @param maxDistanceKm Maximum grouping radius in kilometers.
     * @return List of [GeoCluster] objects containing cluster center and constituent points.
     */
    fun clusterPoints(
        points: List<Pair<Double, Double>>,
        maxDistanceKm: Double = 15.0
    ): List<GeoCluster> {
        val validPoints = points.filter { (lat, lon) ->
            (lat != 0.0 || lon != 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0
        }
        if (validPoints.isEmpty()) return emptyList()

        val clusters = mutableListOf<MutableList<Pair<Double, Double>>>()

        for (point in validPoints) {
            val matchedCluster = clusters.firstOrNull { cluster ->
                val centerLat = cluster.map { it.first }.average()
                val centerLon = cluster.map { it.second }.average()
                calculateDistanceKm(centerLat, centerLon, point.first, point.second) <= maxDistanceKm
            }

            if (matchedCluster != null) {
                matchedCluster.add(point)
            } else {
                clusters.add(mutableListOf(point))
            }
        }

        return clusters.map { cluster ->
            val centerLat = cluster.map { it.first }.average()
            val centerLon = cluster.map { it.second }.average()
            GeoCluster(
                center = Pair(centerLat, centerLon),
                points = cluster
            )
        }
    }

    /**
     * Computes the minimal set of search query center coordinates by clustering
     * nearby station coordinates within [maxDistanceKm] (default 15.0 km).
     *
     * @param coordinates List of (latitude, longitude) coordinate pairs.
     * @param maxDistanceKm Maximum grouping radius in kilometers.
     * @return List of cluster center coordinates (latitude, longitude).
     */
    fun clusterCoordinates(
        coordinates: List<Pair<Double, Double>>,
        maxDistanceKm: Double = 15.0
    ): List<Pair<Double, Double>> {
        return clusterPoints(coordinates, maxDistanceKm).map { it.center }
    }
}

/**
 * Geographic cluster grouping nearby coordinates together.
 */
data class GeoCluster(
    val center: Pair<Double, Double>,
    val points: List<Pair<Double, Double>>
)

/**
 * Extension property to retrieve formatted distance string from a [Station].
 */
val Station.formattedDistance: String
    get() = DistanceCalculator.formatDistance(distanceKm)
