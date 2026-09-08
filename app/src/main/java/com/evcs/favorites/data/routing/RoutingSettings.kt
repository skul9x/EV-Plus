package com.evcs.favorites.data.routing

import kotlinx.serialization.Serializable

/**
 * User configuration and BYOK preferences for routing calculations,
 * including vehicle safe range, battery SoC buffers, and safety duration buffers.
 *
 * @property googleApiKey Custom Google Cloud BYOK API key for Routes API.
 * @property preferredEngine Preferred engine mode for distance/time matrix calculations.
 * @property autoFallbackEnabled Whether to automatically fallback to lower tier engines on failure.
 * @property customOsrmServerUrl Optional custom self-hosted OSRM endpoint URL.
 * @property evSettings Vehicle profile and EV battery buffer configurations.
 */
@Serializable
data class RoutingSettings(
    val googleApiKey: String = "",
    val preferredEngine: RoutingEngineMode = RoutingEngineMode.OSRM_ONLY,
    val autoFallbackEnabled: Boolean = true,
    val customOsrmServerUrl: String? = null,
    val evSettings: EvRoutingSettings = EvRoutingSettings()
)
