package com.evcs.favorites.car

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.RouteSessionData
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Specification of an automotive navigation intent enabling deterministic JVM testing
 * decoupled from the Android Auto projection runtime.
 */
data class CarNavigationIntentSpec(
    val action: String,
    val uriString: String,
    val isCarApp: Boolean = true
) {
    fun toIntent(): Intent {
        return Intent(action, Uri.parse(uriString))
    }
}

/**
 * Dispatches turn-by-turn navigation intents for in-car vehicle screens and mobile fallbacks.
 *
 * In Android Auto Car App Library, cross-app navigation on the head unit MUST use
 * [CarContext.ACTION_NAVIGATE] with a `geo:` URI (`geo:0,0?q=lat,lng(Label)`).
 * Standard [Intent.ACTION_VIEW] with `google.navigation:` is rejected by the Android Auto projection host.
 *
 * When dispatched outside car projection or as fallback, standard mobile navigation intent is used.
 */
object CarNavigationDispatcher {

    const val ACTION_NAVIGATE = CarContext.ACTION_NAVIGATE
    const val ACTION_VIEW = Intent.ACTION_VIEW

    internal var testCarAppLauncher: ((Intent) -> Unit)? = null
    internal var testActivityLauncher: ((Intent) -> Unit)? = null
    internal var testCarAppSpecLauncher: ((CarNavigationIntentSpec) -> Unit)? = null
    internal var testActivitySpecLauncher: ((CarNavigationIntentSpec) -> Unit)? = null

    fun resetTestLaunchers() {
        testCarAppLauncher = null
        testActivityLauncher = null
        testCarAppSpecLauncher = null
        testActivitySpecLauncher = null
    }

    /**
     * Encodes station label for inclusion in Geo URI query parameters.
     */
    fun encodeStationName(stationName: String): String {
        return try {
            Uri.encode(stationName.trim()) ?: URLEncoder.encode(stationName.trim(), StandardCharsets.UTF_8.name()).replace("+", "%20")
        } catch (_: Throwable) {
            URLEncoder.encode(stationName.trim(), StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
    }

    /**
     * Builds standard Android Auto navigation URI: `geo:0,0?q=latitude,longitude(StationName)`.
     */
    fun buildCarNavigationUriString(latitude: Double, longitude: Double, stationName: String? = null): String {
        return if (!stationName.isNullOrBlank()) {
            val encoded = encodeStationName(stationName)
            "geo:0,0?q=$latitude,$longitude($encoded)"
        } else {
            "geo:0,0?q=$latitude,$longitude"
        }
    }

    /**
     * Builds mobile fallback Google Maps navigation URI: `google.navigation:q=latitude,longitude&mode=d`.
     */
    fun buildFallbackNavigationUriString(latitude: Double, longitude: Double): String {
        return "google.navigation:q=$latitude,$longitude&mode=d"
    }

    /**
     * Produces intent descriptor for in-car head unit navigation via [CarContext.ACTION_NAVIGATE].
     */
    fun getCarNavigationIntentSpec(station: Station): CarNavigationIntentSpec {
        return CarNavigationIntentSpec(
            action = ACTION_NAVIGATE,
            uriString = buildCarNavigationUriString(station.latitude, station.longitude, station.name),
            isCarApp = true
        )
    }

    /**
     * Produces intent descriptor for mobile fallback navigation via [Intent.ACTION_VIEW].
     */
    fun getFallbackNavigationIntentSpec(station: Station): CarNavigationIntentSpec {
        return CarNavigationIntentSpec(
            action = ACTION_VIEW,
            uriString = buildFallbackNavigationUriString(station.latitude, station.longitude),
            isCarApp = false
        )
    }

    /**
     * Starts navigation on the vehicle screen via [CarContext.startCarApp], with automatic
     * fallback to mobile navigation if in-car dispatch fails, while simultaneously triggering
     * [CarFocusModeBridge] for background live telemetry, HUD, and voice TTS.
     *
     * @return true if dispatched via in-car head unit host, false if fallback was triggered.
     */
    fun startNavigation(carContext: CarContext, station: Station): Boolean {
        var dispatchedInCar: Boolean
        val carSpec = getCarNavigationIntentSpec(station)
        try {
            if (testCarAppSpecLauncher != null) {
                testCarAppSpecLauncher?.invoke(carSpec)
            } else if (testCarAppLauncher != null) {
                testCarAppLauncher?.invoke(carSpec.toIntent())
            } else {
                carContext.startCarApp(carSpec.toIntent())
            }
            dispatchedInCar = true
        } catch (_: Exception) {
            // In-car navigation host unavailable or threw exception -> fallback to mobile intent
            try {
                val fallbackSpec = getFallbackNavigationIntentSpec(station)
                if (testActivitySpecLauncher != null) {
                    testActivitySpecLauncher?.invoke(fallbackSpec)
                } else {
                    val fallbackIntent = fallbackSpec.toIntent().apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (testActivityLauncher != null) {
                        testActivityLauncher?.invoke(fallbackIntent)
                    } else {
                        carContext.startActivity(fallbackIntent)
                    }
                }
            } catch (_: Exception) {
                // Handled gracefully if no activity can handle fallback
            }
            dispatchedInCar = false
        }

        // Simultaneously trigger mobile telemetry, HUD, and voice alerts
        CarFocusModeBridge.startFocusMode(carContext, station)
        return dispatchedInCar
    }

    /**
     * Dispatches reroute coordinates to the car navigation host to reroute Google Maps on the vehicle screen,
     * and notifies [CarFocusModeBridge] of the new destination station.
     */
    fun rerouteNavigation(carContext: CarContext, newStation: Station): Boolean {
        var dispatchedInCar: Boolean
        val carSpec = getCarNavigationIntentSpec(newStation)
        try {
            if (testCarAppSpecLauncher != null) {
                testCarAppSpecLauncher?.invoke(carSpec)
            } else if (testCarAppLauncher != null) {
                testCarAppLauncher?.invoke(carSpec.toIntent())
            } else {
                carContext.startCarApp(carSpec.toIntent())
            }
            dispatchedInCar = true
        } catch (_: Exception) {
            try {
                val fallbackSpec = getFallbackNavigationIntentSpec(newStation)
                if (testActivitySpecLauncher != null) {
                    testActivitySpecLauncher?.invoke(fallbackSpec)
                } else {
                    val fallbackIntent = fallbackSpec.toIntent().apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (testActivityLauncher != null) {
                        testActivityLauncher?.invoke(fallbackIntent)
                    } else {
                        carContext.startActivity(fallbackIntent)
                    }
                }
            } catch (_: Exception) {
                // Handled gracefully
            }
            dispatchedInCar = false
        }

        CarFocusModeBridge.rerouteFocusMode(carContext, newStation)
        return dispatchedInCar
    }

    /**
     * Produces in-car head unit navigation intent descriptor for the current leg of a route session.
     */
    fun getRouteNavigationIntentSpec(routeSession: RouteSessionData): CarNavigationIntentSpec {
        return getCarNavigationIntentSpec(routeSession.currentTargetStation)
    }

    /**
     * Produces mobile fallback navigation intent descriptor for the current leg of a route session.
     */
    fun getFallbackRouteNavigationIntentSpec(routeSession: RouteSessionData): CarNavigationIntentSpec {
        return getFallbackNavigationIntentSpec(routeSession.currentTargetStation)
    }

    /**
     * Starts multi-stop route navigation on the vehicle screen via [CarContext.startCarApp],
     * dispatching Leg 1 and initializing [CarFocusModeBridge] with the complete itinerary.
     */
    fun startRouteNavigation(carContext: CarContext, routeSession: RouteSessionData): Boolean {
        val targetStation = routeSession.currentTargetStation
        val dispatched = startNavigation(carContext, targetStation)
        CarFocusModeBridge.startRouteFocusMode(carContext, routeSession)
        return dispatched
    }

    /**
     * Advances multi-stop route navigation to the next waypoint on the vehicle screen.
     */
    fun advanceRouteNavigation(carContext: CarContext, updatedSession: RouteSessionData): Boolean {
        val targetStation = updatedSession.currentTargetStation
        val dispatched = rerouteNavigation(carContext, targetStation)
        CarFocusModeBridge.advanceRouteFocusMode(carContext, updatedSession)
        return dispatched
    }

    /**
     * Dispatches multi-stop navigation from mobile UI, launching external navigation app
     * and starting [FocusModeForegroundService] with the complete route session itinerary.
     */
    fun dispatchRouteNavigation(context: Context?, routeSession: RouteSessionData): Boolean {
        val fallbackSpec = getFallbackRouteNavigationIntentSpec(routeSession)
        if (testActivitySpecLauncher != null) {
            testActivitySpecLauncher?.invoke(fallbackSpec)
        } else if (testActivityLauncher != null) {
            testActivityLauncher?.invoke(fallbackSpec.toIntent())
        } else if (context != null) {
            try {
                val fallbackIntent = fallbackSpec.toIntent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {}
        }
        CarFocusModeBridge.startRouteFocusMode(context, routeSession)
        return true
    }

    /**
     * Dispatches waypoint progression from mobile UI, updating navigation to the next leg.
     */
    fun dispatchAdvanceRouteLeg(context: Context?, updatedSession: RouteSessionData): Boolean {
        val fallbackSpec = getFallbackRouteNavigationIntentSpec(updatedSession)
        if (testActivitySpecLauncher != null) {
            testActivitySpecLauncher?.invoke(fallbackSpec)
        } else if (testActivityLauncher != null) {
            testActivityLauncher?.invoke(fallbackSpec.toIntent())
        } else if (context != null) {
            try {
                val fallbackIntent = fallbackSpec.toIntent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {}
        }
        CarFocusModeBridge.advanceRouteFocusMode(context, updatedSession)
        return true
    }
}

