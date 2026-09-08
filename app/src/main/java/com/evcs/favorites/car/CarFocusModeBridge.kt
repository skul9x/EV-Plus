package com.evcs.favorites.car

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.preferences.FocusModePreferences
import com.evcs.favorites.data.routing.RouteSessionData
import com.evcs.favorites.focus.AlternativeStationRecommendation
import com.evcs.favorites.focus.FocusModeForegroundService
import com.evcs.favorites.focus.FocusModeState
import com.evcs.favorites.focus.FocusModeVoiceAlertPolicy
import com.evcs.favorites.focus.FocusServiceIntentSpec
import com.evcs.favorites.focus.FocusVoiceAlert

/**
 * Bridge between Android Auto head unit actions and mobile background services:
 * - Dispatches [FocusModeForegroundService.ACTION_START], [FocusModeForegroundService.ACTION_REROUTE],
 *   and [FocusModeForegroundService.ACTION_START_ROUTE_SESSION].
 * - Synchronizes Voice TTS alerts (Station Full, Alternative Station Found, 2km Proximity Reminder)
 *   with audio ducking and driver preferences.
 */
object CarFocusModeBridge {

    internal var testServiceStarter: ((Intent) -> Unit)? = null
    internal var testServiceSpecStarter: ((FocusServiceIntentSpec) -> Unit)? = null

    fun resetTestStarter() {
        testServiceStarter = null
        testServiceSpecStarter = null
    }

    /**
     * Serializes station payload and creates intent specification for service startup.
     */
    fun getStartIntentSpec(station: Station): FocusServiceIntentSpec {
        return FocusModeForegroundService.getStartIntentSpec(station)
    }

    /**
     * Serializes alternative station payload and creates intent specification for rerouting.
     */
    fun getRerouteIntentSpec(newStation: Station): FocusServiceIntentSpec {
        return FocusModeForegroundService.getRerouteIntentSpec(newStation)
    }

    /**
     * Serializes route session itinerary payload and creates intent specification for starting multi-stop route.
     */
    fun getStartRouteSessionIntentSpec(routeSession: RouteSessionData): FocusServiceIntentSpec {
        return FocusModeForegroundService.getStartRouteSessionIntentSpec(routeSession)
    }

    /**
     * Serializes route session payload and creates intent specification for advancing to next leg.
     */
    fun getAdvanceRouteLegIntentSpec(routeSession: RouteSessionData? = null): FocusServiceIntentSpec {
        return FocusModeForegroundService.getAdvanceRouteLegIntentSpec(routeSession)
    }

    /**
     * Helper factory to build the Intent to start [FocusModeForegroundService].
     */
    fun createStartIntent(context: Context, station: Station): Intent {
        return FocusModeForegroundService.createStartIntent(context, station)
    }

    /**
     * Helper factory to build the Intent to reroute [FocusModeForegroundService].
     */
    fun createRerouteIntent(context: Context, newStation: Station): Intent {
        return FocusModeForegroundService.createRerouteIntent(context, newStation)
    }

    /**
     * Helper factory to build the Intent to start multi-stop route session.
     */
    fun createStartRouteSessionIntent(context: Context, routeSession: RouteSessionData): Intent {
        return FocusModeForegroundService.createStartRouteSessionIntent(context, routeSession)
    }

    /**
     * Helper factory to build the Intent to advance multi-stop route session.
     */
    fun createAdvanceRouteLegIntent(context: Context, routeSession: RouteSessionData? = null): Intent {
        return FocusModeForegroundService.createAdvanceRouteLegIntent(context, routeSession)
    }

    /**
     * Starts mobile FocusModeForegroundService with complete route session itinerary.
     */
    fun startRouteFocusMode(context: Context?, routeSession: RouteSessionData) {
        val spec = getStartRouteSessionIntentSpec(routeSession)
        if (testServiceSpecStarter != null) {
            testServiceSpecStarter?.invoke(spec)
            return
        }
        if (context == null) return
        val intent = createStartRouteSessionIntent(context, routeSession)
        try {
            if (testServiceStarter != null) {
                testServiceStarter?.invoke(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        } catch (_: Exception) {
            // Handled gracefully
        }
    }

    /**
     * Advances mobile FocusModeForegroundService to the next waypoint in the route session.
     */
    fun advanceRouteFocusMode(context: Context?, updatedSession: RouteSessionData) {
        val spec = getAdvanceRouteLegIntentSpec(updatedSession)
        if (testServiceSpecStarter != null) {
            testServiceSpecStarter?.invoke(spec)
            return
        }
        if (context == null) return
        val intent = createAdvanceRouteLegIntent(context, updatedSession)
        try {
            if (testServiceStarter != null) {
                testServiceStarter?.invoke(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        } catch (_: Exception) {
            // Handled gracefully
        }
    }

    /**
     * Starts mobile FocusModeForegroundService to maintain background live polling,
     * floating HUD overlay, and voice alerts while Google Maps navigates on the car screen.
     */
    fun startFocusMode(context: Context, station: Station) {
        val spec = getStartIntentSpec(station)
        if (testServiceSpecStarter != null) {
            testServiceSpecStarter?.invoke(spec)
            return
        }
        val intent = createStartIntent(context, station)
        try {
            if (testServiceStarter != null) {
                testServiceStarter?.invoke(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        } catch (_: Exception) {
            // Handled gracefully if background start restricted
        }
    }

    /**
     * Reroutes active FocusModeForegroundService to track the new destination station.
     */
    fun rerouteFocusMode(context: Context, newStation: Station) {
        val spec = getRerouteIntentSpec(newStation)
        if (testServiceSpecStarter != null) {
            testServiceSpecStarter?.invoke(spec)
            return
        }
        val intent = createRerouteIntent(context, newStation)
        try {
            if (testServiceStarter != null) {
                testServiceStarter?.invoke(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        } catch (_: Exception) {
            // Handled gracefully
        }
    }

    /**
     * Checks whether voice announcements are enabled in driver preferences.
     */
    fun isVoiceAlertEnabled(context: Context): Boolean {
        return try {
            FocusModePreferences.create(context).isVoiceAlertEnabled()
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Factory creating [FocusModeVoiceAlertPolicy] configured for automotive alerts.
     */
    fun createVoiceAlertPolicy(
        context: Context? = null,
        initialAvailableSlots: Int? = null,
        initialDistanceKm: Double? = null,
        debounceWindowMs: Long = FocusModeVoiceAlertPolicy.DEFAULT_DEBOUNCE_WINDOW_MS,
        clock: () -> Long = { System.currentTimeMillis() }
    ): FocusModeVoiceAlertPolicy {
        val isMuted = if (context != null) !isVoiceAlertEnabled(context) else false
        return FocusModeVoiceAlertPolicy(
            initialAvailableSlots = initialAvailableSlots,
            initialDistanceKm = initialDistanceKm,
            debounceWindowMs = debounceWindowMs,
            clock = clock
        ).apply {
            this.isMuted = isMuted
        }
    }

    /**
     * Evaluates whether destination station becoming full (0 vacant ports) triggers voice alert.
     */
    fun evaluateStationFullAlert(
        policy: FocusModeVoiceAlertPolicy,
        previousSlots: Int,
        currentSlots: Int,
        timestamp: Long = System.currentTimeMillis()
    ): FocusVoiceAlert? {
        return policy.evaluateTransition(previousSlots, currentSlots, timestamp)
    }

    /**
     * Evaluates whether alternative station discovery triggers voice alert.
     */
    fun evaluateAlternativeFoundAlert(
        policy: FocusModeVoiceAlertPolicy,
        targetSlots: Int,
        recommendation: AlternativeStationRecommendation?,
        timestamp: Long = System.currentTimeMillis()
    ): FocusVoiceAlert? {
        return policy.evaluateAlternativeStation(targetSlots, recommendation, timestamp)
    }

    /**
     * Evaluates whether 2km arrival threshold crossing triggers voice alert.
     */
    fun evaluateProximityAlert(
        policy: FocusModeVoiceAlertPolicy,
        distanceKm: Double,
        timestamp: Long = System.currentTimeMillis()
    ): FocusVoiceAlert? {
        return policy.evaluateProximity(distanceKm, timestamp)
    }
}
