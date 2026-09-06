package com.evcs.favorites.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Representation of an Intent descriptor to enable standard JVM unit testing
 * and decouple intent specification from Android runtime framework dependencies.
 */
data class MapIntentSpec(
    val action: String,
    val uriString: String,
    val packageName: String? = null
) {
    fun toIntent(): Intent {
        return Intent(action, Uri.parse(uriString)).apply {
            if (packageName != null) {
                setPackage(packageName)
            }
        }
    }
}

/**
 * Navigation and routing helper dispatching 1-Tap navigation intents to Google Maps,
 * with fallback handling to generic map apps (geo: URI) and web browsers.
 */
object MapNavigator {

    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

    /**
     * Encodes station label for inclusion in Geo URI query parameters.
     */
    fun encodeStationName(stationName: String): String {
        return URLEncoder.encode(stationName, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
    }

    /**
     * Builds standard geo URI: `geo:latitude,longitude?q=latitude,longitude(StationName)`.
     */
    fun buildGeoUriString(latitude: Double, longitude: Double, stationName: String? = null): String {
        return if (!stationName.isNullOrBlank()) {
            val encoded = encodeStationName(stationName.trim())
            "geo:$latitude,$longitude?q=$latitude,$longitude($encoded)"
        } else {
            "geo:$latitude,$longitude?q=$latitude,$longitude"
        }
    }

    /**
     * Builds Google Maps navigation URI: `google.navigation:q=latitude,longitude&mode=d`.
     */
    fun buildGoogleNavigationUriString(latitude: Double, longitude: Double): String {
        return "google.navigation:q=$latitude,$longitude&mode=d"
    }

    /**
     * Builds web browser fallback Google Maps search URL.
     */
    fun buildBrowserMapsUrl(latitude: Double, longitude: Double): String {
        return "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude"
    }

    /**
     * Creates Intent descriptor for Google Maps turn-by-turn navigation.
     */
    fun getGoogleMapsIntentSpec(latitude: Double, longitude: Double): MapIntentSpec {
        return MapIntentSpec(
            action = ACTION_VIEW,
            uriString = buildGoogleNavigationUriString(latitude, longitude),
            packageName = GOOGLE_MAPS_PACKAGE
        )
    }

    /**
     * Creates Intent descriptor for generic geo URI viewer.
     */
    fun getGeoIntentSpec(latitude: Double, longitude: Double, stationName: String? = null): MapIntentSpec {
        return MapIntentSpec(
            action = ACTION_VIEW,
            uriString = buildGeoUriString(latitude, longitude, stationName),
            packageName = null
        )
    }

    /**
     * Creates Intent descriptor for web browser fallback.
     */
    fun getBrowserIntentSpec(latitude: Double, longitude: Double): MapIntentSpec {
        return MapIntentSpec(
            action = ACTION_VIEW,
            uriString = buildBrowserMapsUrl(latitude, longitude),
            packageName = null
        )
    }

    /**
     * Creates [Intent] directed specifically at Google Maps navigation.
     */
    fun createGoogleMapsIntent(latitude: Double, longitude: Double): Intent {
        val uri = Uri.parse(buildGoogleNavigationUriString(latitude, longitude))
        return Intent(ACTION_VIEW, uri).apply {
            setPackage(GOOGLE_MAPS_PACKAGE)
        }
    }

    /**
     * Creates generic [Intent] for standard `geo:` URI handlers (e.g. OsmAnd, Waze).
     */
    fun createGeoIntent(latitude: Double, longitude: Double, stationName: String? = null): Intent {
        val uri = Uri.parse(buildGeoUriString(latitude, longitude, stationName))
        return Intent(ACTION_VIEW, uri)
    }

    /**
     * Creates browser [Intent] targeting web Google Maps as last-resort fallback.
     */
    fun createBrowserIntent(latitude: Double, longitude: Double): Intent {
        val uri = Uri.parse(buildBrowserMapsUrl(latitude, longitude))
        return Intent(ACTION_VIEW, uri)
    }

    /**
     * Dispatches navigation to the requested coordinates:
     * 1. Google Maps direct navigation (`google.navigation:...`)
     * 2. Fallback to generic geo URI (`geo:...`)
     * 3. Fallback to web browser (`https://www.google.com/maps/...`)
     *
     * Never crashes regardless of whether Google Maps or a map viewer is installed.
     *
     * @param context Android Context to start activity.
     * @param latitude Destination latitude.
     * @param longitude Destination longitude.
     * @param stationName Destination label.
     * @param intentLauncher Custom launcher for testing or overriding activity dispatch.
     * @return true if an intent was successfully dispatched; false otherwise.
     */
    fun navigate(
        context: Context,
        latitude: Double,
        longitude: Double,
        stationName: String? = null,
        intentLauncher: ((Intent) -> Unit)? = null
    ): Boolean {
        val launcher: (Intent) -> Unit = intentLauncher ?: { intent ->
            val launchIntent = if (context !is Activity) {
                Intent(intent).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            } else {
                intent
            }
            context.startActivity(launchIntent)
        }

        // 1. Attempt Google Maps navigation
        try {
            val gmapsIntent = createGoogleMapsIntent(latitude, longitude)
            launcher(gmapsIntent)
            return true
        } catch (e: ActivityNotFoundException) {
            // Google Maps not installed; fall through
        } catch (e: Exception) {
            // Resolution blocked; fall through
        }

        // 2. Attempt generic geo intent
        try {
            val geoIntent = createGeoIntent(latitude, longitude, stationName)
            launcher(geoIntent)
            return true
        } catch (e: ActivityNotFoundException) {
            // No native map viewer available; fall through
        } catch (e: Exception) {
            // Resolution blocked; fall through
        }

        // 3. Fallback to browser
        return try {
            val browserIntent = createBrowserIntent(latitude, longitude)
            launcher(browserIntent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
