package com.evcs.favorites.car

import android.annotation.SuppressLint
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.di.DefaultAppContainer
import com.evcs.favorites.domain.location.DistanceCalculator
import com.google.android.gms.location.LocationServices
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Main automotive screen rendering charging stations on [PlaceListMapTemplate].
 * Displays vehicle position on the host map, station markers, hero availability metrics,
 * and handles pull-to-refresh / action strip refresh across all Car App API levels.
 */
class MainCarScreen(
    carContext: CarContext,
    private val repository: EvcsRepository? = null,
    private val stationProvider: (() -> List<Station>)? = null,
    private val onNavigateAction: ((Station) -> Unit)? = null,
    private val permissionChecker: ((String) -> Int)? = null,
    private val locationResolver: (suspend () -> Pair<Double, Double>?)? = null,
    private val coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) : Screen(carContext) {

    private var isLoading: Boolean = false
    private var stations: List<Station> = emptyList()
    private var refreshJob: Job? = null
    private var currentUserLocation: Pair<Double, Double>? = null

    private val scope: CoroutineScope
        get() = coroutineScope ?: lifecycleScope

    init {
        loadInitialStations()
    }

    fun getStations(): List<Station> = stations

    fun isLoading(): Boolean = isLoading

    fun getRefreshJob(): Job? = refreshJob

    fun getCurrentUserLocation(): Pair<Double, Double>? = currentUserLocation

    fun updateStations(newStations: List<Station>) {
        stations = CarStationFormatter.sortStationsByProximity(newStations, currentUserLocation)
        isLoading = false
        invalidate()
    }

    fun setLoading(loading: Boolean) {
        isLoading = loading
        invalidate()
    }

    private fun loadInitialStations() {
        if (stationProvider != null) {
            stations = CarStationFormatter.sortStationsByProximity(stationProvider.invoke(), currentUserLocation)
        }
        if (stations.isEmpty()) {
            refreshStations()
        }
    }

    fun refreshStations() {
        refreshJob?.cancel()
        isLoading = true
        invalidate()

        if (stationProvider != null) {
            val rawStations = stationProvider.invoke()
            stations = CarStationFormatter.sortStationsByProximity(rawStations, currentUserLocation)
            isLoading = false
            invalidate()
            return
        }

        val repo = repository ?: resolveRepository()
        if (repo != null) {
            refreshJob = scope.launch {
                try {
                    // Pre-populate with cached stations on IO dispatcher if currently empty
                    if (stations.isEmpty()) {
                        val cached = withContext(ioDispatcher) {
                            repo.getCachedFavorites()
                        }
                        if (cached.isNotEmpty()) {
                            stations = CarStationFormatter.sortStationsByProximity(cached, currentUserLocation)
                            invalidate()
                        }
                    }

                    // Retrieve last known location via location resolver / FusedLocationProviderClient
                    val loc = resolveUserLocation()
                    if (loc != null) {
                        currentUserLocation = loc
                    }

                    // Remote fetch on Dispatchers.IO
                    val result = withContext(ioDispatcher) {
                        repo.getFavorites(currentUserLocation?.first, currentUserLocation?.second)
                    }

                    val freshOrCached = result.getOrNull() ?: withContext(ioDispatcher) {
                        repo.getCachedFavorites()
                    }
                    stations = CarStationFormatter.sortStationsByProximity(freshOrCached, currentUserLocation)
                } catch (_: Exception) {
                    val fallbackCached = withContext(ioDispatcher) {
                        repo.getCachedFavorites()
                    }
                    stations = CarStationFormatter.sortStationsByProximity(fallbackCached, currentUserLocation)
                } finally {
                    isLoading = false
                    invalidate()
                }
            }
        } else {
            isLoading = false
            invalidate()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun resolveUserLocation(): Pair<Double, Double>? {
        if (locationResolver != null) {
            return locationResolver.invoke()
        }
        if (!hasLocationPermission) {
            return null
        }
        return withContext(ioDispatcher) {
            try {
                val client = LocationServices.getFusedLocationProviderClient(carContext)
                withTimeoutOrNull(2_000L) {
                    suspendCancellableCoroutine<android.location.Location?> { cont ->
                        client.lastLocation
                            .addOnSuccessListener { loc ->
                                if (cont.isActive) cont.resume(loc)
                            }
                            .addOnFailureListener {
                                if (cont.isActive) cont.resume(null)
                            }
                    }
                }?.let { Pair(it.latitude, it.longitude) }
            } catch (_: Exception) {
                null
            }
        }
    }

    private val effectiveCarApiLevel: Int
        get() = try {
            carContext.carAppApiLevel
        } catch (_: IllegalStateException) {
            CarServiceConfig.MIN_CAR_API_LEVEL
        }

    val hasLocationPermission: Boolean
        get() {
            val check = permissionChecker ?: { perm: String -> carContext.checkSelfPermission(perm) }
            val fine = check(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = check(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }

    fun navigateToStation(station: Station) {
        if (onNavigateAction != null) {
            onNavigateAction.invoke(station)
        } else {
            CarNavigationDispatcher.startNavigation(carContext, station)
        }
    }

    fun buildItemList(): ItemList {
        val sortedStations = CarStationFormatter.sortStationsByProximity(stations, currentUserLocation)
        val displayStations = CarStationFormatter.truncateStations(sortedStations, CarPaneSpec.MAX_LIST_ITEMS)
        val itemListBuilder = ItemList.Builder()

        if (displayStations.isEmpty()) {
            itemListBuilder.setNoItemsMessage(CarPaneSpec.EMPTY_STATIONS_MESSAGE)
        } else {
            for (station in displayStations) {
                val markerColor = if (station.totalAvailablePlugs > 0) CarColor.GREEN else CarColor.RED
                val place = Place.Builder(CarLocation.create(station.latitude, station.longitude))
                    .setMarker(PlaceMarker.Builder().setColor(markerColor).build())
                    .build()

                val rowBuilder = Row.Builder()
                    .setTitle(CarStationFormatter.formatTitle(station))
                    .addText(CarStationFormatter.formatSubtitleSpannable(station))
                    .setMetadata(Metadata.Builder().setPlace(place).build())
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(
                            StationDetailCarScreen(
                                carContext = carContext,
                                station = station,
                                onNavigateAction = onNavigateAction
                            )
                        )
                    }

                itemListBuilder.addItem(rowBuilder.build())
            }
        }
        return itemListBuilder.build()
    }

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(CarPaneSpec.REFRESH_ACTION_TITLE)
                    .setOnClickListener { refreshStations() }
                    .build()
            )
            .build()

        if (isLoading) {
            val loadingBuilder = PlaceListMapTemplate.Builder()
                .setTitle(CarPaneSpec.MAIN_SCREEN_TITLE)
                .setLoading(true)
                .setCurrentLocationEnabled(hasLocationPermission)
                .setActionStrip(actionStrip)

            if (effectiveCarApiLevel >= 5) {
                loadingBuilder.setOnContentRefreshListener { refreshStations() }
            }
            return loadingBuilder.build()
        }

        val itemList = buildItemList()
        val templateBuilder = PlaceListMapTemplate.Builder()
            .setTitle(CarPaneSpec.MAIN_SCREEN_TITLE)
            .setCurrentLocationEnabled(hasLocationPermission)
            .setActionStrip(actionStrip)

        templateBuilder.setItemList(itemList)

        if (effectiveCarApiLevel >= 5) {
            templateBuilder.setOnContentRefreshListener { refreshStations() }
        }

        return templateBuilder.build()
    }

    private fun resolveRepository(): EvcsRepository? {
        return try {
            DefaultAppContainer.getInstance(carContext).evcsRepository
        } catch (_: Exception) {
            null
        }
    }
}
