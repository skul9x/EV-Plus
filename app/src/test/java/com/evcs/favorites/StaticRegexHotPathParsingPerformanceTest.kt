package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.util.StationUrlBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive verification for Phase 05: Pre-compiled Static Regexes in Hot String Parsing Paths.
 *
 * Verifies:
 * 1. EvcsApiClient extracts coordinates and metadata accurately across all formats (OG meta, geo.position, JSON, maps, data-attributes).
 * 2. StationUrlBuilder.slugify accurately strips Vietnamese diacritics, normalizes spaces, and consolidates hyphens.
 * 3. EvcsRepository.parseConnectorsToPowers accurately extracts wattages across single, decimal, and multi-connector strings.
 * 4. Parsing is deterministic, thread-safe, and idempotent across thousands of concurrent evaluations.
 */
class StaticRegexHotPathParsingPerformanceTest {



    @Test
    fun testEvcsApiClientCoordinateAndMetadataExtraction() {
        // 1. OpenGraph & Meta latitude/longitude
        val metaHtml1 = """
            <html>
                <head>
                    <meta property="og:latitude" content="21.0285" />
                    <meta property="og:longitude" content="105.8542" />
                </head>
            </html>
        """.trimIndent()
        val coords1 = EvcsApiClient.parseCoordinatesFromHtml(metaHtml1)
        assertNotNull(coords1)
        assertEquals(21.0285, coords1!!.first, 0.0001)
        assertEquals(105.8542, coords1.second, 0.0001)

        // 2. Reversed attributes: content before name/property
        val metaHtml2 = """
            <meta content="10.7769" name="place:location:latitude">
            <meta content="106.7009" name="place:location:longitude">
        """.trimIndent()
        val coords2 = EvcsApiClient.parseCoordinatesFromHtml(metaHtml2)
        assertNotNull(coords2)
        assertEquals(10.7769, coords2!!.first, 0.0001)
        assertEquals(106.7009, coords2.second, 0.0001)

        // 3. geo.position with semicolon and comma
        val geoPosSemicolon = """<meta name="geo.position" content="16.0544;108.2022">"""
        val coordsGeoSemi = EvcsApiClient.parseCoordinatesFromHtml(geoPosSemicolon)
        assertNotNull(coordsGeoSemi)
        assertEquals(16.0544, coordsGeoSemi!!.first, 0.0001)
        assertEquals(108.2022, coordsGeoSemi.second, 0.0001)

        val geoPosComma = """<meta property="geo.position" content="20.8449, 106.6881">"""
        val coordsGeoComma = EvcsApiClient.parseCoordinatesFromHtml(geoPosComma)
        assertNotNull(coordsGeoComma)
        assertEquals(20.8449, coordsGeoComma!!.first, 0.0001)
        assertEquals(106.6881, coordsGeoComma.second, 0.0001)

        // 4. Embedded JSON (latitude/longitude and short lat/lng)
        val jsonHtml = """
            <script type="application/ld+json">
                {"latitude": 21.1452, "longitude": 106.1553}
            </script>
        """.trimIndent()
        val coordsJson = EvcsApiClient.parseCoordinatesFromHtml(jsonHtml)
        assertNotNull(coordsJson)
        assertEquals(21.1452, coordsJson!!.first, 0.0001)
        assertEquals(106.1553, coordsJson.second, 0.0001)

        val shortJsonHtml = """{"lat": 10.8231, "lng": 106.6297}"""
        val coordsShort = EvcsApiClient.parseCoordinatesFromHtml(shortJsonHtml)
        assertNotNull(coordsShort)
        assertEquals(10.8231, coordsShort!!.first, 0.0001)
        assertEquals(106.6297, coordsShort.second, 0.0001)

        // 5. Maps / Navigation links
        val mapHtml = """<a href="https://maps.google.com/?q=21.0333,105.8500">Bản đồ</a>"""
        val coordsMap = EvcsApiClient.parseCoordinatesFromHtml(mapHtml)
        assertNotNull(coordsMap)
        assertEquals(21.0333, coordsMap!!.first, 0.0001)
        assertEquals(105.8500, coordsMap.second, 0.0001)

        // 6. Data attributes
        val dataAttrHtml = """<div data-lat="12.2388" data-lng="109.1967">Trạm Nha Trang</div>"""
        val coordsData = EvcsApiClient.parseCoordinatesFromHtml(dataAttrHtml)
        assertNotNull(coordsData)
        assertEquals(12.2388, coordsData!!.first, 0.0001)
        assertEquals(109.1967, coordsData.second, 0.0001)

        // 7. Metadata extraction (address + working-time)
        val fullMetaHtml = """
            <html>
                <head>
                    <meta property="og:latitude" content="21.0285" />
                    <meta property="og:longitude" content="105.8542" />
                    <meta property="business:contact_data:street_address" content="Số 1 Tràng Tiền, Hoàn Kiếm, Hà Nội" />
                    <meta name="working-time" content="24/7 (Cả ngày)" />
                </head>
            </html>
        """.trimIndent()
        val metadata = EvcsApiClient.parseStationMetadataFromHtml(fullMetaHtml)
        assertNotNull(metadata)
        assertEquals(21.0285, metadata!!.latitude, 0.0001)
        assertEquals(105.8542, metadata.longitude, 0.0001)
        assertEquals("Số 1 Tràng Tiền, Hoàn Kiếm, Hà Nội", metadata.address)
        assertEquals("24/7 (Cả ngày)", metadata.workingTime)
    }

    @Test
    fun testStationUrlBuilderSlugifyVietnameseAndEdgeCases() {
        // Vietnamese diacritics removal and normalization
        val input1 = "Trạm Sạc VinFast Thảo Điền Quận 2"
        val slug1 = StationUrlBuilder.slugify(input1)
        assertEquals("tram-sac-vinfast-thao-dien-quan-2", slug1)

        // đ and Đ character handling
        val input2 = "Đà Nẵng - Điện Biên Phủ (Cổng số #1)!!!"
        val slug2 = StationUrlBuilder.slugify(input2)
        assertEquals("da-nang-dien-bien-phu-cong-so-1", slug2)

        // Consecutive dashes and whitespace consolidation
        val input3 = "  Trạm   Sạc---Bắc   Giang--  "
        val slug3 = StationUrlBuilder.slugify(input3)
        assertEquals("tram-sac-bac-giang", slug3)

        // Empty and blank strings
        assertEquals("", StationUrlBuilder.slugify(null))
        assertEquals("", StationUrlBuilder.slugify(""))
        assertEquals("", StationUrlBuilder.slugify("   "))

        // Verification of pre-compiled regex objects existence
        assertNotNull(StationUrlBuilder.DIACRITICS_REGEX)
        assertNotNull(StationUrlBuilder.NON_ALPHANUMERIC_REGEX)
        assertNotNull(StationUrlBuilder.WHITESPACE_REGEX)
        assertNotNull(StationUrlBuilder.CONSECUTIVE_DASHES_REGEX)

        // Canonical URL creation with slug
        val vfUrl = StationUrlBuilder.buildStationDetailUrl("VinFast Royal City", "VF-001")
        assertEquals("https://evcs.vn/tram-sac-vinfast-royal-city-vf-001.html", vfUrl)

        val partnerUrl = StationUrlBuilder.buildStationDetailUrl("EV One Landmark", "EV-100")
        assertEquals("https://evcs.vn/tram-sac-ev-one-landmark-c.EV-100.html", partnerUrl)
    }

    @Test
    fun testEvcsRepositoryParseConnectorsToPowers() {
        // Single connector
        val single = EvcsRepository.parseConnectorsToPowers("60kW")
        assertEquals(1, single.size)
        assertEquals(60000L, single[0].typeWatts)
        assertEquals("60kW", single[0].displayString)

        // Decimal connector
        val decimal = EvcsRepository.parseConnectorsToPowers("11.5kW")
        assertEquals(1, decimal.size)
        assertEquals(11500L, decimal[0].typeWatts)

        // Multi-connectors with whitespace variations
        val multi = EvcsRepository.parseConnectorsToPowers("250kW, 120kW, 60 kW, 11kW, 7.4 kW")
        assertEquals(5, multi.size)
        assertEquals(250000L, multi[0].typeWatts)
        assertEquals(120000L, multi[1].typeWatts)
        assertEquals(60000L, multi[2].typeWatts)
        assertEquals(11000L, multi[3].typeWatts)
        assertEquals(7400L, multi[4].typeWatts)

        // Empty, blank, or malformed inputs
        assertTrue(EvcsRepository.parseConnectorsToPowers(null).isEmpty())
        assertTrue(EvcsRepository.parseConnectorsToPowers("").isEmpty())
        assertTrue(EvcsRepository.parseConnectorsToPowers("   ").isEmpty())

        val unparsed = EvcsRepository.parseConnectorsToPowers("Type2 AC")
        assertEquals(1, unparsed.size)
        assertEquals(0L, unparsed[0].typeWatts)
        assertEquals("Type2 AC", unparsed[0].displayString)

        // Verification of pre-compiled regex constant
        assertNotNull(EvcsRepository.KW_REGEX)
    }

    @Test
    fun testHighConcurrencyAndDeterminism() {
        runBlocking {
            val iterationsPerThread = 200
            val threads = 10

            val htmlSnippet = """
                <meta property="og:latitude" content="21.0285" />
                <meta property="og:longitude" content="105.8542" />
                <meta property="og:street-address" content="123 Tran Hung Dao" />
                <meta name="working-time" content="24/7" />
            """.trimIndent()

            val sampleConnectorString = "250kW, 120kW, 60kW, 11kW"
            val sampleStationName = "Trạm Sạc VinFast Grand Park Quận 9"

            val jobs = (1..threads).map {
                async(Dispatchers.Default) {
                    repeat(iterationsPerThread) {
                        // 1. EvcsApiClient
                        val coords = EvcsApiClient.parseCoordinatesFromHtml(htmlSnippet)
                        assertNotNull(coords)
                        assertEquals(21.0285, coords!!.first, 0.0001)
                        assertEquals(105.8542, coords.second, 0.0001)

                        val meta = EvcsApiClient.parseStationMetadataFromHtml(htmlSnippet)
                        assertNotNull(meta)
                        assertEquals("123 Tran Hung Dao", meta!!.address)

                        // 2. StationUrlBuilder
                        val slug = StationUrlBuilder.slugify(sampleStationName)
                        assertEquals("tram-sac-vinfast-grand-park-quan-9", slug)

                        // 3. EvcsRepository
                        val powers = EvcsRepository.parseConnectorsToPowers(sampleConnectorString)
                        assertEquals(4, powers.size)
                        assertEquals(250000L, powers[0].typeWatts)
                    }
                }
            }

            jobs.awaitAll()
        }
    }
}
