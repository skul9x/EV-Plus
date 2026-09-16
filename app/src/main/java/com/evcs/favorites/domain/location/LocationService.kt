package com.evcs.favorites.domain.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Service managing user GPS location via Google Play Services [FusedLocationProviderClient]
 * with seamless fallback to Android framework [LocationManager] for automotive/car head units.
 *
 * Implements high accuracy positioning for in-car EV navigation, multi-tiered fallback
 * (Fresh GPS -> Fused Cache -> Hardware GNSS/LocationManager), runtime permission checks,
 * and exposes reactive location updates via [StateFlow].
 */
open class LocationService(
    private val context: Context? = null,
    private val fusedLocationClient: FusedLocationProviderClient? = context?.let { LocationServices.getFusedLocationProviderClient(it) },
    private val locationProvider: (suspend () -> Location?)? = null,
    private val locationManager: LocationManager? = context?.let { it.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }
) {
    private val _locationState = MutableStateFlow<Location?>(null)

    /**
     * Emits current user location updates as a reactive [StateFlow].
     */
    val locationState: StateFlow<Location?> = _locationState.asStateFlow()
    val currentLocation: StateFlow<Location?> = _locationState.asStateFlow()
    val userLocation: StateFlow<Location?> = _locationState.asStateFlow()

    private var locationCallback: LocationCallback? = null
    private var isUpdatingLocation: Boolean = false

    /**
     * Checks whether the app currently holds fine or coarse location runtime permission.
     */
    open fun hasLocationPermission(): Boolean {
        val ctx = context ?: return (locationProvider != null)
        val fineGranted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    /**
     * Checks whether system-wide Location Services (GPS or Network) are enabled in device settings.
     */
    open fun isLocationEnabled(): Boolean {
        val ctx = context ?: return true
        val lm = locationManager ?: (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return true
        return try {
            LocationManagerCompat.isLocationEnabled(lm)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Provides latest known coordinates as a (latitude, longitude) Pair,
     * or null if no location is available yet.
     * Can be passed directly to `EvcsRepository.getFavorites(userLat, userLon)`.
     */
    open val latestCoordinates: Pair<Double, Double>?
        get() = _locationState.value?.let { Pair(it.latitude, it.longitude) }

    open val latestLatitude: Double?
        get() = _locationState.value?.latitude

    open val latestLongitude: Double?
        get() = _locationState.value?.longitude

    /**
     * Fetches a single fresh location update using [Priority.PRIORITY_HIGH_ACCURACY]
     * with default 8s timeout.
     */
    open suspend fun getFreshLocation(): Location? = getFreshLocation(timeoutMs = 8_000L)

    /**
     * Fetches a single fresh location update using [Priority.PRIORITY_HIGH_ACCURACY]
     * for automotive EV navigation.
     *
     * Implements a resilient 3-tier fallback strategy:
     * 1. Fresh active GNSS/Fused location via [FusedLocationProviderClient.getCurrentLocation].
     * 2. If fresh location returns null or fails, falls back immediately to [FusedLocationProviderClient.getLastLocation].
     * 3. If Google Play Services is absent, fails, or returns null, falls back to Android framework [LocationManager.getLastKnownLocation].
     *
     * Enforces a configurable timeout (default 8,000ms) to prevent caller freezing.
     *
     * @param timeoutMs Timeout limit in milliseconds (default 8,000ms).
     * @return Fresh or cached [Location], or null if permissions are absent, location is disabled, or acquisition times out.
     */
    @SuppressLint("MissingPermission")
    open suspend fun getFreshLocation(timeoutMs: Long): Location? {
        if (!hasLocationPermission()) {
            return null
        }

        val customProvider = locationProvider
        if (customProvider != null) {
            return withTimeoutOrNull(timeoutMs) {
                val loc = customProvider()
                if (loc != null) {
                    _locationState.value = loc
                }
                loc
            }
        }

        val client = fusedLocationClient
        if (client == null) {
            val fwLoc = getLastKnownLocationFromFramework()
            if (fwLoc != null) {
                _locationState.value = fwLoc
            }
            return fwLoc
        }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val cancellationTokenSource = CancellationTokenSource()
                val resumed = AtomicBoolean(false)

                fun resumeOnce(location: Location?) {
                    if (resumed.compareAndSet(false, true)) {
                        if (location != null) {
                            _locationState.value = location
                        }
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }

                fun fallbackToFramework() {
                    val fwLoc = getLastKnownLocationFromFramework()
                    resumeOnce(fwLoc)
                }

                fun fallbackToLastLocation() {
                    try {
                        client.lastLocation
                            .addOnSuccessListener { lastLoc ->
                                if (lastLoc != null) {
                                    resumeOnce(lastLoc)
                                } else {
                                    fallbackToFramework()
                                }
                            }
                            .addOnFailureListener {
                                fallbackToFramework()
                            }
                    } catch (e: Exception) {
                        fallbackToFramework()
                    }
                }

                try {
                    client.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        cancellationTokenSource.token
                    ).addOnSuccessListener { freshLoc ->
                        if (freshLoc != null) {
                            resumeOnce(freshLoc)
                        } else {
                            // Fresh location returned null (e.g. cold start / satellite acquisition delay)
                            // Immediately fallback to cached lastLocation
                            fallbackToLastLocation()
                        }
                    }.addOnFailureListener {
                        fallbackToLastLocation()
                    }
                } catch (e: Exception) {
                    fallbackToLastLocation()
                }
            }
        }
    }

    /**
     * Fallback to Android framework [LocationManager] enabled providers (GPS, Network, Passive).
     * Especially vital for AOSP car head units, Carlinkit TBox, and non-GAS automotive environments.
     */
    @SuppressLint("MissingPermission")
    open fun getLastKnownLocationFromFramework(): Location? {
        val ctx = context ?: return null
        val lm = locationManager ?: (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return null
        return try {
            val providers = lm.getProviders(true)
            var bestLocation: Location? = null
            for (provider in providers) {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null ||
                    loc.time > bestLocation.time ||
                    (loc.hasAccuracy() && bestLocation.hasAccuracy() && loc.accuracy < bestLocation.accuracy)
                ) {
                    bestLocation = loc
                }
            }
            bestLocation
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Starts listening for high accuracy periodic location updates for active driving/navigation.
     *
     * @param intervalMs Desired update interval in ms (default 10,000ms for car navigation).
     * @param minUpdateDistanceMeters Minimum distance displacement in meters (default 20m).
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(
        intervalMs: Long = 10_000L,
        minUpdateDistanceMeters: Float = 20f
    ) {
        val client = fusedLocationClient ?: return
        if (isUpdatingLocation || !hasLocationPermission()) {
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(minUpdateDistanceMeters)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    _locationState.value = location
                }
            }
        }

        locationCallback = callback
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            isUpdatingLocation = true
        } catch (e: Exception) {
            isUpdatingLocation = false
        }
    }

    /**
     * Stops receiving periodic location updates to conserve battery when idle.
     */
    fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            try {
                fusedLocationClient?.removeLocationUpdates(callback)
            } catch (e: Exception) {
                // Ignore removal failure if client disconnected
            }
            locationCallback = null
        }
        isUpdatingLocation = false
    }

    /**
     * Manually updates the location state (for testing or simulated movement).
     */
    fun setLocation(location: Location?) {
        _locationState.value = location
    }
}
