package com.evcs.favorites.car

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.testing.TestCarContext
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Single comprehensive verification test for Phase 02:
 * - Verifies station list truncation strictly complies with the 6-item automotive safety limit.
 * - Verifies row title, metadata, and subtitle text formatting includes hero availability counts and power tiers.
 * - Verifies POI coordinates, CarLocation, and PlaceMarker color mapping for host map placement.
 * - Verifies station detail PaneTemplate adheres to the <= 4 rows and <= 2 actions limits.
 * - Verifies action button text is "⚡ DẪN ĐƯỜNG & THEO DÕI".
 * - Verifies empty and loading state handling when no stations or network is available.
 * - Verifies MainCarScreen and StationDetailCarScreen template construction under CarContext.
 */
class CarScreensTemplateTest {

    private lateinit var testCarContext: CarContext

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })

        val fakeContext = object : ContextWrapper(null) {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo().apply {
                    flags = ApplicationInfo.FLAG_DEBUGGABLE
                }
            }
            override fun getPackageName(): String = "com.evplus.app"
        }
        testCarContext = TestCarContext.createCarContext(fakeContext)
    }

    @org.junit.After
    fun tearDown() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun createSampleStation(
        id: String = "station_1",
        name: String = "5.4km » Trạm sạc Vincom Metropolis",
        address: String = "29 Liễu Giai, Ba Đình, Hà Nội",
        lat: Double = 21.0313,
        lon: Double = 105.8152,
        availablePlugs: Int = 4,
        totalPlugs: Int = 8,
        powers: List<PowerPort> = listOf(
            PowerPort(typeWatts = 250000L, label = "250kW", availablePlugs = 2, totalPlugs = 2),
            PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 2, totalPlugs = 2),
            PowerPort(typeWatts = 11000L, label = "11kW", availablePlugs = 0, totalPlugs = 4)
        ),
        distanceKm: Double? = 1.8,
        evse: String = "VinFast"
    ): Station {
        return Station(
            id = id,
            name = name,
            address = address,
            latitude = lat,
            longitude = lon,
            summary = "Trạm sạc nhanh VinFast",
            connectors = "250kW, 60kW, 11kW",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = availablePlugs,
            totalPlugs = totalPlugs,
            distanceKm = distanceKm,
            evse = evse
        )
    }

    @Test
    fun testStationListTruncationToSafetyLimit() {
        val stations = (1..12).map { index ->
            createSampleStation(id = "station_$index", name = "Trạm số $index")
        }
        assertEquals(12, stations.size)

        val truncated = CarStationFormatter.truncateStations(stations)
        assertEquals("List must be strictly capped at 6 items for driver safety", 6, truncated.size)
        assertEquals("station_1", truncated.first().id)
        assertEquals("station_6", truncated.last().id)

        val smallList = (1..3).map { index -> createSampleStation(id = "st_$index") }
        val smallTruncated = CarStationFormatter.truncateStations(smallList)
        assertEquals(3, smallTruncated.size)
    }

    @Test
    fun testTitleFormattingAndProviderBadge() {
        val station = createSampleStation(
            name = "5.4km » Trạm sạc Vincom Metropolis",
            evse = "VinFast"
        )
        val formattedTitle = CarStationFormatter.formatTitle(station)
        assertEquals("[VinFast] Vincom Metropolis", formattedTitle)

        // If provider already in sanitized title, don't duplicate badge
        val stationAlreadyBadged = createSampleStation(
            name = "[VinFast] Landmark 81",
            evse = "VinFast"
        )
        assertEquals("[VinFast] Landmark 81", CarStationFormatter.formatTitle(stationAlreadyBadged))

        // Fallback when blank name
        val blankStation = createSampleStation(name = "", evse = "EV-Plus")
        assertEquals("[EV-Plus] Trạm sạc", CarStationFormatter.formatTitle(blankStation))
    }

    @Test
    fun testSubtitleHeroMetricAndPowerTiersAndDistance() {
        // Healthy station (4/8 available)
        val stationHealthy = createSampleStation(
            availablePlugs = 4,
            totalPlugs = 8,
            distanceKm = 1.8
        )
        val subtitleHealthy = CarStationFormatter.formatSubtitle(stationHealthy)
        assertTrue("Subtitle should contain 🟢 indicator and ratio", subtitleHealthy.contains("🟢 4/8 TRỐNG"))
        assertTrue("Subtitle should contain power tiers", subtitleHealthy.contains("250kW, 60kW, 11kW"))
        assertTrue("Subtitle should contain distance", subtitleHealthy.contains("1.8 km"))

        // Full station (0 available)
        val stationFull = createSampleStation(
            availablePlugs = 0,
            totalPlugs = 6,
            distanceKm = 0.5
        )
        val subtitleFull = CarStationFormatter.formatSubtitle(stationFull)
        assertTrue("Full station must show 🔴 indicator", subtitleFull.contains("🔴 0/6 TRỐNG"))
        assertTrue("Distance under 1km must be formatted in meters", subtitleFull.contains("500m"))

        // Limited station (1 available out of 4)
        val stationLimited = createSampleStation(
            availablePlugs = 1,
            totalPlugs = 4,
            distanceKm = null
        )
        val subtitleLimited = CarStationFormatter.formatSubtitle(stationLimited)
        assertTrue("Low capacity station must show 🟡 indicator", subtitleLimited.contains("🟡 1/4 TRỐNG"))
        assertFalse("Subtitle without distance must not contain null/km", subtitleLimited.contains("km"))

        // Unverified station (0 total plugs)
        val stationUnverified = createSampleStation(
            availablePlugs = 0,
            totalPlugs = 0,
            powers = emptyList()
        )
        val subtitleUnverified = CarStationFormatter.formatSubtitle(stationUnverified)
        assertTrue("Unverified station displays depot status gracefully", subtitleUnverified.contains("Trạng thái: Normal"))
    }

    @Test
    fun testStationDetailPaneAdheresToSafetyLimits() {
        val station = createSampleStation(
            address = "123 Nguyễn Trãi, Q.1",
            availablePlugs = 4,
            totalPlugs = 8,
            distanceKm = 1.2
        )
        val paneSpec = CarStationFormatter.buildPaneSpec(station)

        // Safety Constraint 1: Maximum 4 Content Rows
        assertTrue("Detail pane must not exceed 4 rows", paneSpec.rows.size <= CarPaneSpec.MAX_PANE_ROWS)
        assertEquals(4, paneSpec.rows.size)

        // Row 1 (Location): Street address and live distance
        val row1 = paneSpec.rows[0]
        assertEquals("Vị trí & Khoảng cách", row1.title)
        assertEquals("1.2 km • 123 Nguyễn Trãi, Q.1", row1.subtitle)

        // Row 2 (Hero Availability): 🟢 4/8 Trụ trống (Tổng 8 trụ sạc)
        val row2 = paneSpec.rows[1]
        assertEquals("Tình trạng trụ", row2.title)
        assertTrue(row2.subtitle?.contains("🟢 4/8 Trụ trống (Tổng 8 trụ sạc)") == true)

        // Row 3 (DC Fast Charging): High-power breakdown
        val row3 = paneSpec.rows[2]
        assertEquals("Sạc nhanh DC", row3.title)
        assertTrue(row3.subtitle?.contains("⚡ DC: 250kW (2 trụ), 60kW (2 trụ)") == true)

        // Row 4 (AC Charging): Standard power breakdown
        val row4 = paneSpec.rows[3]
        assertEquals("Sạc chuẩn AC", row4.title)
        assertTrue(row4.subtitle?.contains("🔌 AC: 11kW (4 trụ)") == true)

        // Safety Constraint 2: Primary action must be "⚡ DẪN ĐƯỜNG & THEO DÕI"
        assertEquals("⚡ DẪN ĐƯỜNG & THEO DÕI", paneSpec.primaryActionTitle)
        assertTrue(paneSpec.primaryActionEnabled)

        // Verify safety check enforcement in CarPaneSpec constructor
        try {
            val fiveRows = paneSpec.rows + CarRowSpec(title = "Extra 5th Row")
            CarPaneSpec(title = "Invalid Pane", rows = fiveRows)
            fail("CarPaneSpec must throw IllegalArgumentException when exceeding 4 rows")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("cannot exceed 4 rows") == true)
        }
    }

    @Test
    fun testPlaceMarkerColorMappingAndCarLocation() {
        val availableStation = createSampleStation(availablePlugs = 3, totalPlugs = 4, lat = 10.7769, lon = 106.7009)
        val fullStation = createSampleStation(availablePlugs = 0, totalPlugs = 4, lat = 21.0285, lon = 105.8542)

        // Available station -> Green PlaceMarker
        val availableMarkerColor = if (availableStation.totalAvailablePlugs > 0) CarColor.GREEN else CarColor.RED
        assertEquals(CarColor.GREEN, availableMarkerColor)

        val availableLocation = CarLocation.create(availableStation.latitude, availableStation.longitude)
        assertEquals(10.7769, availableLocation.latitude, 0.0001)
        assertEquals(106.7009, availableLocation.longitude, 0.0001)

        val availablePlace = Place.Builder(availableLocation)
            .setMarker(PlaceMarker.Builder().setColor(availableMarkerColor).build())
            .build()
        assertNotNull(availablePlace.location)
        assertEquals(availableMarkerColor, availablePlace.marker?.color)

        // Full station -> Red PlaceMarker
        val fullMarkerColor = if (fullStation.totalAvailablePlugs > 0) CarColor.GREEN else CarColor.RED
        assertEquals(CarColor.RED, fullMarkerColor)

        val fullPlace = Place.Builder(CarLocation.create(fullStation.latitude, fullStation.longitude))
            .setMarker(PlaceMarker.Builder().setColor(fullMarkerColor).build())
            .build()
        assertEquals(CarColor.RED, fullPlace.marker?.color)
    }

    @Test
    fun testStationDetailCarScreenPaneTemplateCreation() {
        var navigatedStation: Station? = null
        val station = createSampleStation(
            name = "Trạm Landmark 81",
            address = "720A Điện Biên Phủ, Bình Thạnh",
            availablePlugs = 2,
            totalPlugs = 4
        )

        val detailScreen = StationDetailCarScreen(
            carContext = testCarContext,
            station = station,
            onNavigateAction = { navigatedStation = it }
        )

        val template = detailScreen.onGetTemplate()
        assertTrue("Template must be an instance of PaneTemplate", template is PaneTemplate)
        val paneTemplate = template as PaneTemplate

        val pane = paneTemplate.pane
        assertNotNull(pane)
        val rows = pane.rows
        assertTrue("PaneTemplate rows must not exceed 4 rows", rows.size <= 4)
        assertEquals(4, rows.size)

        // Verify primary action
        val actions = pane.actions
        assertTrue("PaneTemplate actions must not exceed 2", actions.size <= 2)
        assertEquals(1, actions.size)
        val action = actions[0]
        assertEquals("⚡ DẪN ĐƯỜNG & THEO DÕI", action.title?.toString())
        assertEquals(CarColor.GREEN, action.backgroundColor)

        // Trigger action click
        action.onClickDelegate?.sendClick(object : androidx.car.app.OnDoneCallback {
            override fun onSuccess(response: androidx.car.app.serialization.Bundleable?) {}
            override fun onFailure(response: androidx.car.app.serialization.Bundleable?) {}
        })
        assertEquals(station.id, navigatedStation?.id)
    }

    @Test
    fun testMainCarScreenPlaceListMapTemplateStates() {
        // 1. Loading State
        val mainScreen = MainCarScreen(
            carContext = testCarContext,
            stationProvider = { emptyList() }
        )
        mainScreen.setLoading(true)
        val loadingTemplate = mainScreen.onGetTemplate()
        assertTrue(loadingTemplate is PlaceListMapTemplate)
        val placeListLoading = loadingTemplate as PlaceListMapTemplate
        assertTrue(placeListLoading.isLoading)
        assertEquals("EV-Plus - Trạm sạc", placeListLoading.title?.toString())
        assertTrue(placeListLoading.isCurrentLocationEnabled)
        assertNotNull(placeListLoading.actionStrip)

        // 2. Empty State
        mainScreen.setLoading(false)
        mainScreen.updateStations(emptyList())
        val emptyTemplate = mainScreen.onGetTemplate() as PlaceListMapTemplate
        assertFalse(emptyTemplate.isLoading)
        val emptyItemList = emptyTemplate.itemList
        assertNotNull(emptyItemList)
        assertEquals("Không tìm thấy trạm sạc khả dụng", emptyItemList?.noItemsMessage?.toString())
        assertTrue(emptyItemList?.items.isNullOrEmpty())

        // 3. Populated State (8 stations provided -> only 6 shown in template)
        val populatedStations = (1..8).map { idx ->
            createSampleStation(
                id = "station_$idx",
                name = "Trạm $idx",
                availablePlugs = idx % 3,
                totalPlugs = 4
            )
        }
        mainScreen.updateStations(populatedStations)
        val populatedTemplate = mainScreen.onGetTemplate() as PlaceListMapTemplate
        assertFalse(populatedTemplate.isLoading)
        val populatedItemList = populatedTemplate.itemList
        assertNotNull(populatedItemList)
        assertEquals(
            "Host-rendered ItemList must strictly cap at 6 items",
            6,
            populatedItemList?.items?.size
        )

        // Verify first row contains Metadata with Place
        val firstRow = populatedItemList?.items?.get(0) as Row
        assertNotNull(firstRow.metadata)
        val place = firstRow.metadata?.place
        assertNotNull(place)
        assertNotNull(place?.location)
        assertNotNull(place?.marker)
    }

    @Test
    fun testEvPlusCarSessionReturnsMainCarScreen() {
        val session = EvPlusCarSession()
        // Reflectively set carContext on session to verify onCreateScreen
        val field = androidx.car.app.Session::class.java.getDeclaredField("mCarContext")
        field.isAccessible = true
        field.set(session, testCarContext)

        val screen = session.onCreateScreen(android.content.Intent())
        assertNotNull(screen)
        assertTrue("Session must initialize MainCarScreen as root", screen is MainCarScreen)
    }
}
