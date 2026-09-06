package com.evcs.favorites.util

import com.evcs.favorites.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single comprehensive test suite for Phase 01:
 * Station URL Slug Canonicalization & Prefix Handling (ANDROID-LOGIC-004).
 *
 * Verifies:
 * 1. VinFast station with "Trạm sạc" prefix produces canonical single-prefix URL without "tram-sac-tram-sac-".
 * 2. VinFast station with "Trạm sạc xe điện" or "Trụ sạc" prefixes produce canonical scheme ending in "-${id.lowercase()}.html".
 * 3. Partner station with "Trạm sạc" prefix produces canonical partner URL without duplicate "tram-sac-tram-sac-".
 * 4. Raw unsanitized distance prefixes combined with Vietnamese station prefixes generate canonical URLs.
 * 5. Empty, blank, and special character inputs maintain graceful fallback behavior.
 * 6. StationNameSanitizer accurately strips Vietnamese prefixes while preserving milestone and middle names.
 * 7. Station domain model overload produces identical canonical URLs.
 */
class StationUrlCanonicalizationSafetyTest {

    @Test
    fun testVinFastStationWithTramSacPrefix_canonicalUrlWithoutDuplicatePrefix() {
        val stationName = "Trạm sạc VinFast Mega Mall Smart City"
        val locationId = "c.hn005"

        val url = StationUrlBuilder.buildStationDetailUrl(stationName, locationId)

        assertEquals(
            "https://evcs.vn/tram-sac-vinfast-mega-mall-smart-city-c.hn005.html",
            url
        )
        assertFalse("URL must not contain duplicate tram-sac-tram-sac-", url.contains("tram-sac-tram-sac-"))
        assertTrue("URL must follow VinFast canonical scheme", url.startsWith("https://evcs.vn/tram-sac-vinfast-"))

        // Also verify with uppercase location ID
        val urlUpper = StationUrlBuilder.buildStationDetailUrl(stationName, "C.HN005")
        assertEquals(
            "https://evcs.vn/tram-sac-vinfast-mega-mall-smart-city-c.hn005.html",
            urlUpper
        )
    }

    @Test
    fun testVinFastStationWithTramSacXeDienAndTruSacPrefixes() {
        // "Trạm sạc xe điện" prefix
        val xeDienName = "Trạm sạc xe điện VinFast Times City"
        val xeDienUrl = StationUrlBuilder.buildStationDetailUrl(xeDienName, "HN001")
        assertEquals(
            "https://evcs.vn/tram-sac-vinfast-times-city-hn001.html",
            xeDienUrl
        )
        assertTrue("URL must end with lowercase location ID html", xeDienUrl.endsWith("-hn001.html"))
        assertFalse("URL must not have duplicate tram-sac-", xeDienUrl.contains("tram-sac-tram-sac-"))

        // "Trụ sạc" prefix
        val truSacName = "Trụ sạc VinFast Long Biên"
        val truSacUrl = StationUrlBuilder.buildStationDetailUrl(truSacName, "HN003")
        assertEquals(
            "https://evcs.vn/tram-sac-vinfast-long-bien-hn003.html",
            truSacUrl
        )
        assertTrue("URL must end with -hn003.html", truSacUrl.endsWith("-hn003.html"))
    }

    @Test
    fun testPartnerStationWithTramSacPrefix_canonicalPartnerUrl() {
        val partnerName = "Trạm sạc EV One"
        val locationId = "PARTNER01"

        val url = StationUrlBuilder.buildStationDetailUrl(partnerName, locationId)

        assertEquals(
            "https://evcs.vn/tram-sac-ev-one-c.PARTNER01.html",
            url
        )
        assertFalse("Partner URL must not have duplicate tram-sac-", url.contains("tram-sac-tram-sac-"))
        assertTrue("Partner URL must use -c.ID partner scheme", url.contains("-c.PARTNER01.html"))
    }

    @Test
    fun testRawUnsanitizedDistanceAndSeparatorPrefixes() {
        // Guillemet distance + "Trạm sạc" prefix
        val rawGuillemet = "5.4km » Trạm sạc VinFast Ocean Park"
        val urlGuillemet = StationUrlBuilder.buildStationDetailUrl(rawGuillemet, "HN002")
        assertEquals(
            "https://evcs.vn/tram-sac-vinfast-ocean-park-hn002.html",
            urlGuillemet
        )

        // Hyphen distance + "Trạm sạc xe điện" prefix
        val rawHyphen = "12,5km - Trạm sạc xe điện VinFast Royal City"
        val urlHyphen = StationUrlBuilder.buildStationDetailUrl(rawHyphen, "HN004")
        assertEquals(
            "https://evcs.vn/tram-sac-vinfast-royal-city-hn004.html",
            urlHyphen
        )

        // Colon distance + "Trụ sạc" prefix
        val rawColon = "~9.1km : Trụ sạc VinFast Landmark 81"
        val urlColon = StationUrlBuilder.buildStationDetailUrl(rawColon, "SGN001")
        assertEquals(
            "https://evcs.vn/tram-sac-vinfast-landmark-81-sgn001.html",
            urlColon
        )

        // Partner with distance + "Trạm sạc" prefix
        val partnerRaw = "500m » Trạm sạc EV One"
        val urlPartnerRaw = StationUrlBuilder.buildStationDetailUrl(partnerRaw, "PARTNER01")
        assertEquals(
            "https://evcs.vn/tram-sac-ev-one-c.PARTNER01.html",
            urlPartnerRaw
        )
    }

    @Test
    fun testEmptyBlankAndSpecialCharacterInputs_gracefulFallback() {
        assertEquals("https://evcs.vn/tram-sac-c.ID001.html", StationUrlBuilder.buildStationDetailUrl("", "ID001"))
        assertEquals("https://evcs.vn/tram-sac-c.ID001.html", StationUrlBuilder.buildStationDetailUrl("   ", "ID001"))
        assertEquals("https://evcs.vn/tram-sac-c.ID002.html", StationUrlBuilder.buildStationDetailUrl("!@#$%^&*", "ID002"))
        assertEquals("https://evcs.vn/tram-sac-c.ID003.html", StationUrlBuilder.buildStationDetailUrl("Trạm sạc", "ID003"))
        assertEquals("https://evcs.vn/tram-sac-c.ID004.html", StationUrlBuilder.buildStationDetailUrl("Trạm sạc xe điện", "ID004"))
    }

    @Test
    fun testStationNameSanitizerPrefixStrippingAndPreservation() {
        // Vietnamese prefix stripping
        assertEquals("VinFast Mega Mall Smart City", StationNameSanitizer.sanitize("Trạm sạc VinFast Mega Mall Smart City"))
        assertEquals("VinFast Times City", StationNameSanitizer.sanitize("Trạm sạc xe điện VinFast Times City"))
        assertEquals("VinFast Long Biên", StationNameSanitizer.sanitize("Trụ sạc VinFast Long Biên"))
        assertEquals("EV One", StationNameSanitizer.sanitize("Trạm sạc EV One"))

        // Hyphen / colon after prefix
        assertEquals("VinFast Mega Mall", StationNameSanitizer.sanitize("Trạm sạc - VinFast Mega Mall"))
        assertEquals("VinFast Mega Mall", StationNameSanitizer.sanitize("Trạm sạc: VinFast Mega Mall"))

        // Combined distance and station prefix
        assertEquals("VinFast Ocean Park", StationNameSanitizer.sanitize("5.4km » Trạm sạc VinFast Ocean Park"))
        assertEquals("VinFast Royal City", StationNameSanitizer.sanitize("12,5km - Trạm sạc xe điện VinFast Royal City"))

        // Preservation of legitimate non-prefix words
        assertEquals("Km 12 Quốc lộ 1A", StationNameSanitizer.sanitize("Km 12 Quốc lộ 1A"))
        assertEquals("VinFast Trạm Dừng Nghỉ", StationNameSanitizer.sanitize("VinFast Trạm Dừng Nghỉ"))
        assertEquals("VinFast - Trạm sạc Đắk Lắk #1", StationNameSanitizer.sanitize("VinFast - Trạm sạc Đắk Lắk #1"))
        assertEquals("EV ONE Trạm Sạc", StationNameSanitizer.sanitize("EV ONE Trạm Sạc"))
    }

    @Test
    fun testDomainStationOverloadConsistency() {
        val station = Station(
            id = "c.hn005",
            name = "Trạm sạc VinFast Mega Mall Smart City",
            address = "Hà Nội",
            latitude = 21.0,
            longitude = 105.8,
            summary = "24/7",
            connectors = "250kW",
            depotStatus = "Normal"
        )

        val urlFromStation = StationUrlBuilder.buildStationDetailUrl(station)
        val urlDirect = StationUrlBuilder.buildStationDetailUrl(station.name, station.id)

        assertEquals("https://evcs.vn/tram-sac-vinfast-mega-mall-smart-city-c.hn005.html", urlFromStation)
        assertEquals(urlDirect, urlFromStation)
    }
}
