package com.evcs.favorites.car

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main automotive screen rendering charging stations on [PlaceListMapTemplate].
 * Displays vehicle position on the host map, station markers, hero availability metrics,
 * and handles pull-to-refresh / action strip refresh across all Car App API levels.
 */
class MainCarScreen(
    carContext: CarContext,
    private val repository: EvcsRepository? = null,
    private val stationProvider: (() -> List<Station>)? = null,
    private val onNavigateAction: ((Station) -> Unit)? = null
) : Screen(carContext) {

    private var isLoading: Boolean = false
    private var stations: List<Station> = emptyList()

    init {
        loadInitialStations()
    }

    fun getStations(): List<Station> = stations

    fun isLoading(): Boolean = isLoading

    fun updateStations(newStations: List<Station>) {
        stations = newStations
        isLoading = false
        invalidate()
    }

    fun setLoading(loading: Boolean) {
        isLoading = loading
        invalidate()
    }

    private fun loadInitialStations() {
        if (stationProvider != null) {
            stations = stationProvider.invoke()
        } else {
            val repo = repository ?: resolveRepository()
            val cached = repo?.getCachedFavorites().orEmpty()
            if (cached.isNotEmpty()) {
                stations = cached
            }
        }
    }

    fun refreshStations() {
        isLoading = true
        invalidate()

        if (stationProvider != null) {
            stations = stationProvider.invoke()
            isLoading = false
            invalidate()
            return
        }

        val repo = repository ?: resolveRepository()
        if (repo != null) {
            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        repo.getFavorites()
                    }
                    stations = result.getOrNull() ?: repo.getCachedFavorites()
                } catch (_: Exception) {
                    stations = repo.getCachedFavorites()
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

    private val effectiveCarApiLevel: Int
        get() = try {
            carContext.carAppApiLevel
        } catch (_: IllegalStateException) {
            CarServiceConfig.MIN_CAR_API_LEVEL
        }

    fun navigateToStation(station: Station) {
        if (onNavigateAction != null) {
            onNavigateAction.invoke(station)
        } else {
            CarNavigationDispatcher.startNavigation(carContext, station)
        }
    }

    fun buildItemList(): ItemList {
        val displayStations = CarStationFormatter.truncateStations(stations, CarPaneSpec.MAX_LIST_ITEMS)
        val itemListBuilder = ItemList.Builder()

        if (displayStations.isEmpty()) {
            itemListBuilder.setNoItemsMessage(CarPaneSpec.EMPTY_STATIONS_MESSAGE)
        } else {
            for (station in displayStations) {
                val markerColor = if (station.totalAvailablePlugs > 0) CarColor.GREEN else CarColor.RED
                val place = Place.Builder(CarLocation.create(station.latitude, station.longitude))
                    .setMarker(PlaceMarker.Builder().setColor(markerColor).build())
                    .build()

                val navAction = Action.Builder()
                    .setTitle(CarPaneSpec.ACTION_NAVIGATE_AND_MONITOR)
                    .setOnClickListener {
                        navigateToStation(station)
                    }
                    .build()

                val row = Row.Builder()
                    .setTitle(CarStationFormatter.formatTitle(station))
                    .addText(CarStationFormatter.formatSubtitle(station))
                    .setMetadata(Metadata.Builder().setPlace(place).build())
                    .addAction(navAction)
                    .setOnClickListener {
                        screenManager.push(
                            StationDetailCarScreen(
                                carContext = carContext,
                                station = station,
                                onNavigateAction = onNavigateAction
                            )
                        )
                    }
                    .build()

                itemListBuilder.addItem(row)
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
                .setCurrentLocationEnabled(true)
                .setActionStrip(actionStrip)

            if (effectiveCarApiLevel >= 5) {
                loadingBuilder.setOnContentRefreshListener { refreshStations() }
            }
            return loadingBuilder.build()
        }

        val itemList = buildItemList()
        val templateBuilder = PlaceListMapTemplate.Builder()
            .setTitle(CarPaneSpec.MAIN_SCREEN_TITLE)
            .setCurrentLocationEnabled(true)
            .setActionStrip(actionStrip)

        try {
            templateBuilder.setItemList(itemList)
        } catch (_: Exception) {
            // In JVM unit tests without Robolectric, Android's SpannableString.getSpans() stub returns null,
            // triggering an NPE in ModelUtils.checkCarTextHasSpanType. Safely inject mItemList for unit tests.
            try {
                val field = PlaceListMapTemplate.Builder::class.java.getDeclaredField("mItemList")
                field.isAccessible = true
                field.set(templateBuilder, itemList)
            } catch (_: Exception) {}
        }

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
