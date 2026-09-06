package com.evcs.favorites.data.network.vinfast

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Base response wrapper for VinFast CAPP APIs matching VinFast's BaseResponse.
 */
@Serializable
data class VinFastBaseResponse<T>(
    val code: Int? = null,
    val message: String? = null,
    val data: T? = null,
    val metadata: JsonElement? = null
)

/**
 * Request body for searching charging stations: POST /ccarcharging/api/v1/stations/search
 * Note: `page` and `size` are passed as URL query parameters, NOT in the JSON body.
 */
@Serializable
data class VinFastSearchRequest(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val province: String? = null,
    val district: String? = null,
    val wattageTypes: List<String>? = null,
    val parkingFee: Boolean? = null,
    val freeParking: Boolean? = null,
    val excludeFavorite: Boolean? = null
)

/**
 * Request body for location info: POST /ccarcharging/api/v1/stations/location-info
 */
@Serializable
data class VinFastLocationInfoRequest(
    val locationIds: List<String>
)

/**
 * Station status DTO mirroring VinFast RemoteChargingStationsStatus.
 */
@Serializable
data class VinFastStationStatusDto(
    val locationId: String? = null,
    val stationName: String? = null,
    val stationAddress: String? = null,
    val hereId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val numberOfAvailableEvse: Int? = null,
    val totalEvse: Int? = null,
    val connectors: List<VinFastConnectorDto>? = null,
    val images: List<VinFastImageDto>? = null,
    val isPublic: Boolean? = null,
    val isFreeParking: Boolean? = null,
    val isInWorkingTime: Boolean? = null,
    val workingTimeDescription: String? = null,
    val depotStatus: String? = null,
    val favorite: Boolean? = null,
    val province: String? = null,
    val district: String? = null
)

/**
 * Connector count DTO mirroring VinFast RemoteChargingStationsStatus.ConnectorCount.
 */
@Serializable
data class VinFastConnectorDto(
    val type: Int? = null,
    val status: String? = null,
    val count: Int? = null,
    val total: Int? = null,
    val isLink: Boolean? = null,
    val powerType: String? = null
)

/**
 * Station image DTO mirroring VinFast RemoteChargingStationsStatus.ImageInfo.
 */
@Serializable
data class VinFastImageDto(
    val url: String? = null,
    val thumbnail: String? = null
)

/**
 * Resilient exception class for VinFast CAPP API errors.
 */
class VinFastApiException(
    val code: Int? = null,
    override val message: String? = null,
    override val cause: Throwable? = null
) : Exception(message ?: "VinFast API error (code: $code)", cause)
