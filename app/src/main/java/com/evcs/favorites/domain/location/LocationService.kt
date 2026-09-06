package com.evcs.favorites.domain.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
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
import kotlin.coroutines.resume

/**
 * Service managing user GPS location via Google Play Services [FusedLocationProviderClient].
 *
 * Implements low battery consumption strategies (balanced power accuracy, sensible intervals),
 * runtime permission checking, and exposes reactive location updates via [StateFlow].
 */
open class LocationService(
    private val context: Context? = null,
    private val fusedLocationClient: FusedLocationProviderClient? = context?.let { LocationServices.getFusedLocationProviderClient(it) }
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
        val ctx = context ?: return false
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
     * Fetches a single fresh location update using [Priority.PRIORITY_BALANCED_POWER_ACCURACY]
     * for low battery consumption. If fresh location fails, falls back to last known location.
     *
     * @return Fresh or cached [Location], or null if permissions are absent or location is disabled.
     */
    @SuppressLint("MissingPermission")
    open suspend fun getFreshLocation(): Location? {
        val client = fusedLocationClient ?: return null
        if (!hasLocationPermission()) {
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()
            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }

            client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location ->
                if (location != null) {
                    _locationState.value = location
                }
                if (continuation.isActive) {
                    continuation.resume(location)
                }
            }.addOnFailureListener {
                // Fallback to last known location
                client.lastLocation.addOnSuccessListener { lastLoc ->
                    if (lastLoc != null) {
                        _locationState.value = lastLoc
                    }
                    if (continuation.isActive) {
                        continuation.resume(lastLoc)
                    }
                }.addOnFailureListener {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    /**
     * Starts listening for balanced periodic location updates.
     *
     * @param intervalMs Desired update interval in ms (default 30,000ms for power saving).
     * @param minUpdateDistanceMeters Minimum distance displacement in meters.
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(
        intervalMs: Long = 30_000L,
        minUpdateDistanceMeters: Float = 50f
    ) {
        val client = fusedLocationClient ?: return
        if (isUpdatingLocation || !hasLocationPermission()) {
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
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
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        isUpdatingLocation = true
    }

    /**
     * Stops receiving periodic location updates to conserve battery.
     */
    fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
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
