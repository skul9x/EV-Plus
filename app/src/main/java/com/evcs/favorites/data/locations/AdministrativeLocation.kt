package com.evcs.favorites.data.locations

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Immutable geographic coordinate representing an administrative centroid in Vietnam.
 *
 * @property lat Centroid latitude in decimal degrees.
 * @property lng Centroid longitude in decimal degrees.
 */
@Serializable
data class LocationCoordinate(
    val lat: Double,
    val lng: Double
) {
    val latitude: Double
        get() = lat

    val longitude: Double
        get() = lng
}

/**
 * Immutable administrative district model (quận, huyện, thị xã, thành phố thuộc tỉnh).
 *
 * @property name District name (e.g., "Ba Đình", "Quận 1", "Hải Châu").
 * @property coordinate Centroid coordinate of the district.
 */
@Serializable
data class AdministrativeDistrict(
    val name: String,
    val coordinate: LocationCoordinate
)

/**
 * Immutable administrative province model (tỉnh, thành phố trực thuộc trung ương).
 *
 * @property name Province name (e.g., "Hà Nội", "Hồ Chí Minh", "Đà Nẵng").
 * @property districts List of administrative districts belonging to this province.
 */
@Serializable
data class AdministrativeProvince(
    val name: String,
    val districts: List<AdministrativeDistrict>
)
