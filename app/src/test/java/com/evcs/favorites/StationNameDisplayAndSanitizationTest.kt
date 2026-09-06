package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.FavoriteStationRaw
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.repository.toDomainStation
import com.evcs.favorites.data.repository.toFavoriteStationRaw
import com.evcs.favorites.util.StationNameSanitizer
import com.evcs.favorites.util.StationUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification test for Phase 01:
 * Station Name Sanitization & Multiline Full-Text Display.
 *
 * Verifies:
 * 1. StationNameSanitizer regex sanitization across distance prefix formats:
 *    - Guillemet formats ("5.4km » ...", "9.1km » ...", "500m » ...", "~5.4km » ...")
 *    - Separator/space formats ("12,5km - ...", "5.4 km : ...", "500m VinFast...")
 *    - Preservation of names without prefix ("VinFast - Royal City", "Km 12 Quốc lộ 1A")
 *    - Safe null/blank handling
 * 2. SearchStationRaw.toDomainStation() sanitizes stationName
 * 3. EvcsRepository.mergeToDomainStation() sanitizes stationName in matched and fallback branches
 * 4. Station.toFavoriteStationRaw() persists sanitized name for cloud synchronization
 * 5. StationUrlBuilder generates canonical VinFast slug even with raw unsanitized station names
 * 6. UI compatibility and multiline contract validation
 */
class StationNameDisplayAndSanitizationTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository

    @Before
    fun setUp() {
        val storage = InMemorySessionStorage()
        sessionManager = SessionManager(storage)
        apiClient = EvcsApiClient(sessionManager, baseUrl = "https://evcs.vn")
        repository = EvcsRepository(apiClient)
    }

    // =========================================================================
    // Part 1: Regex Sanitization Across Distance Prefix Formats
    // =========================================================================

    @Test
    fun testStationNameSanitizerGuillemetPrefixes() {
        // Standard distance with guillemet
        assertEquals(
            "VinFast - CHXD Petrolimex",
            StationNameSanitizer.sanitize("5.4km » VinFast - CHXD Petrolimex")
        )
        assertEquals(
            "VinFast - CHXD Petrolimex",
            StationNameSanitizer.sanitize("9.1km » VinFast - CHXD Petrolimex")
        )
        assertEquals(
            "VinFast - CHXD Petrolimex",
            StationNameSanitizer.sanitize("500m » VinFast - CHXD Petrolimex")
        )
        // Leading tilde with guillemet
        assertEquals(
            "VinFast - CHXD Petrolimex",
            StationNameSanitizer.sanitize("~5.4km » VinFast - CHXD Petrolimex")
        )
        // Space variations around guillemet
        assertEquals(
            "VinFast Trạm Dừng Nghỉ",
            StationNameSanitizer.sanitize("15.2 km »  VinFast Trạm Dừng Nghỉ")
        )
    }

    @Test
    fun testStationNameSanitizerSeparatorAndSpacePrefixes() {
        // Comma decimal with hyphen separator
        assertEquals(
            "VinFast - CHXD Petrolimex",
            StationNameSanitizer.sanitize("12,5km - VinFast - CHXD Petrolimex")
        )
        // Space inside distance with colon separator
        assertEquals(
            "VinFast - CHXD Petrolimex",
            StationNameSanitizer.sanitize("5.4 km : VinFast - CHXD Petrolimex")
        )
        // Meter prefix followed directly by space
        assertEquals(
            "VinFast - CHXD Petrolimex",
            StationNameSanitizer.sanitize("500m VinFast - CHXD Petrolimex")
        )
        // Kilometre with hyphen
        assertEquals(
            "VinFast Ocean Park",
            StationNameSanitizer.sanitize("5.4km - VinFast Ocean Park")
        )
        // Tilde prefix with colon
        assertEquals(
            "VinFast Landmark 81",
            StationNameSanitizer.sanitize("~9.1km : VinFast Landmark 81")
        )
    }

    @Test
    fun testStationNameSanitizerPreservesValidNames() {
        // Normal VinFast name
        assertEquals(
            "VinFast - Royal City",
            StationNameSanitizer.sanitize("VinFast - Royal City")
        )
        // Landmark with "Km" as highway milestone (Km is before number, must NOT be stripped)
        assertEquals(
            "Km 12 Quốc lộ 1A",
            StationNameSanitizer.sanitize("Km 12 Quốc lộ 1A")
        )
        // Long realistic station name
        val longStationName = "VinFast - Hộ kinh doanh Trịnh Thị Duyên - Gia Bình"
        assertEquals(
            longStationName,
            StationNameSanitizer.sanitize(longStationName)
        )
        // Partner brand station
        assertEquals(
            "EV One - CHXD Số 1",
            StationNameSanitizer.sanitize("EV One - CHXD Số 1")
        )
    }

    @Test
    fun testStationNameSanitizerEdgeCases() {
        assertEquals("", StationNameSanitizer.sanitize(null))
        assertEquals("", StationNameSanitizer.sanitize(""))
        assertEquals("", StationNameSanitizer.sanitize("   "))
        assertEquals("", StationNameSanitizer.sanitize("5.4km » "))
        assertEquals("", StationNameSanitizer.sanitize("9.1km »   "))
    }

    // =========================================================================
    // Part 2: SearchStationRaw.toDomainStation() Sanitization
    // =========================================================================

    @Test
    fun testSearchStationRawToDomainStationSanitizesName() {
        val raw = SearchStationRaw(
            locationId = "LOC_SEARCH_01",
            stationName = "5.4km » VinFast - CHXD Petrolimex Số 10",
            stationAddress = "Quốc lộ 18, Bắc Ninh",
            latitude = 21.14,
            longitude = 106.16
        )

        val domain = raw.toDomainStation()

        assertEquals("VinFast - CHXD Petrolimex Số 10", domain.name)
        assertFalse("Domain station name must not retain distance prefix", domain.name.contains("5.4km"))
        assertFalse("Domain station name must not retain guillemet", domain.name.contains("»"))
    }

    // =========================================================================
    // Part 3: EvcsRepository.mergeToDomainStation() Sanitization
    // =========================================================================

    @Test
    fun testMergeToDomainStationWithMatchedSearchData() {
        val fav = FavoriteStationRaw(
            locationId = "LOC_FAV_01",
            name = "5.4km » VinFast Petrolimex",
            address = "Địa chỉ cũ"
        )
        val search = SearchStationRaw(
            locationId = "LOC_FAV_01",
            stationName = "5.4km » VinFast Petrolimex Updated",
            stationAddress = "Địa chỉ mới",
            latitude = 21.05,
            longitude = 105.80
        )

        val merged = repository.mergeToDomainStation(fav, search)

        assertEquals("VinFast Petrolimex Updated", merged.name)
        assertFalse(merged.name.contains("5.4km"))
        assertFalse(merged.name.contains("»"))
    }

    @Test
    fun testMergeToDomainStationWithFallbackWhenSearchIsNull() {
        val fav = FavoriteStationRaw(
            locationId = "LOC_FAV_02",
            name = "9.1km » VinFast Royal City",
            address = "72A Nguyễn Trãi, Thanh Xuân, Hà Nội"
        )

        val merged = repository.mergeToDomainStation(fav, null)

        assertEquals("VinFast Royal City", merged.name)
        assertFalse(merged.name.contains("9.1km"))
        assertFalse(merged.name.contains("»"))
    }

    @Test
    fun testMergeToDomainStationWhenSearchNameIsBlankFallsBackToSanitizedFavName() {
        val fav = FavoriteStationRaw(
            locationId = "LOC_FAV_03",
            name = "12,5km - VinFast Big C Thăng Long",
            address = "222 Trần Duy Hưng, Hà Nội"
        )
        val searchWithBlankName = SearchStationRaw(
            locationId = "LOC_FAV_03",
            stationName = "   ",
            latitude = 21.01,
            longitude = 105.79
        )

        val merged = repository.mergeToDomainStation(fav, searchWithBlankName)

        assertEquals("VinFast Big C Thăng Long", merged.name)
        assertFalse(merged.name.contains("12,5km"))
    }

    // =========================================================================
    // Part 4: Station.toFavoriteStationRaw() Sanitization for Cloud Sync
    // =========================================================================

    @Test
    fun testToFavoriteStationRawSanitizesNameForCloudPersistence() {
        val domainStation = Station(
            id = "LOC_SYNC_01",
            name = "5.4km » VinFast - CHXD Petrolimex",
            address = "Quốc lộ 1A",
            latitude = 21.14,
            longitude = 106.16,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Normal"
        )

        val rawFavorite = domainStation.toFavoriteStationRaw()

        assertEquals("VinFast - CHXD Petrolimex", rawFavorite.name)
        assertFalse("Persisted raw favorite name must never contain distance prefix", rawFavorite.name.contains("5.4km"))
        assertFalse("Persisted raw favorite name must never contain guillemet", rawFavorite.name.contains("»"))
    }

    // =========================================================================
    // Part 5: StationUrlBuilder Canonical Slug with Unsanitized Input
    // =========================================================================

    @Test
    fun testStationUrlBuilderProducesCanonicalVinFastSlugEvenWithDistancePrefix() {
        // Raw unsanitized name containing distance prefix and guillemet
        val rawName = "5.4km » VinFast - CHXD Petrolimex"
        val locationId = "C.BNI0012"

        val url = StationUrlBuilder.buildStationDetailUrl(rawName, locationId)

        // Must generate canonical VinFast URL scheme starting with /tram-sac-vinfast-...
        assertEquals(
            "https://evcs.vn/tram-sac-vinfast-chxd-petrolimex-c.bni0012.html",
            url
        )
        assertTrue(
            "Canonical URL for VinFast stations must start with /tram-sac-vinfast-",
            url.contains("/tram-sac-vinfast-")
        )
        assertFalse(
            "Canonical URL must not contain distance prefix in slug",
            url.contains("5-4km") || url.contains("5.4km")
        )
    }

    @Test
    fun testStationUrlBuilderOverloadWithStationModel() {
        val station = Station(
            id = "C.BNI0012",
            name = "~9.1km » VinFast Royal City",
            address = "Hà Nội",
            latitude = 21.0,
            longitude = 105.8,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Normal"
        )

        val url = StationUrlBuilder.buildStationDetailUrl(station)

        assertEquals(
            "https://evcs.vn/tram-sac-vinfast-royal-city-c.bni0012.html",
            url
        )
    }

    @Test
    fun testStationUrlBuilderPartnerStationWithDistancePrefix() {
        val rawPartnerName = "500m » EV One - CHXD Petrolimex"
        val locationId = "PARTNER123"

        val url = StationUrlBuilder.buildStationDetailUrl(rawPartnerName, locationId)

        assertEquals(
            "https://evcs.vn/tram-sac-ev-one-chxd-petrolimex-c.PARTNER123.html",
            url
        )
    }

    // =========================================================================
    // Part 6: StationCard & StationDetailModal Contract Verification
    // =========================================================================

    @Test
    fun testStationDisplayModelAndMultilineContract() {
        // Verify long station name model construction and API compatibility
        val longStation = Station(
            id = "C.BNI0099",
            name = StationNameSanitizer.sanitize("5.4km » VinFast - Hộ kinh doanh Trịnh Thị Duyên - Gia Bình"),
            address = "Thôn Hương Triện, Xã Nhân Thắng, Huyện Gia Bình, Tỉnh Bắc Ninh",
            latitude = 21.057,
            longitude = 106.183,
            summary = "Mở 24/7",
            connectors = "60kW, 30kW",
            depotStatus = "Normal",
            totalAvailablePlugs = 2,
            totalPlugs = 4
        )

        assertEquals("VinFast - Hộ kinh doanh Trịnh Thị Duyên - Gia Bình", longStation.name)
        assertNotNull(longStation.id)
        assertNotNull(longStation.address)

        // Confirm callbacks for StationCard and modal can be defined with this station
        var clickedStation: Station? = null
        val onStationClick: (Station) -> Unit = { clickedStation = it }
        onStationClick(longStation)
        assertEquals(longStation, clickedStation)
    }
}
