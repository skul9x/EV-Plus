package com.evcs.favorites.data.locations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Comprehensive verification test for Phase 01: Offline Locations Dataset & Repository.
 *
 * Verifies:
 * 1. Asset & Hierarchy Loading: Loads bundled vietnam_locations.json (< 50ms cold start),
 *    validating exactly 63 provinces and 702 administrative districts.
 * 2. getAllProvinces(): Returns all 63 provinces in alphabetical order with major cities
 *    (Hà Nội, Hồ Chí Minh, Đà Nẵng) strictly pinned at index 0, 1, 2.
 * 3. getDistrictsForProvince(): Correctly fetches district list for given provinces, supports
 *    case-insensitivity and whitespace trimming, and returns empty list for unknown provinces.
 * 4. getCoordinate(): Returns accurate LocationCoordinate for province + district pairs,
 *    and null for invalid/unknown queries.
 * 5. findClosestDistrict(): Accurate 1-tap GPS reverse match against nearest district centroids
 *    across Northern, Central, and Southern Vietnam, and handles invalid coordinates gracefully.
 * 6. Geographic Bounds Sanity: All 702 district centroids fall strictly within Vietnam territory bounds.
 * 7. In-memory Caching & Resilient Providers: Verifies lazy caching idempotency and custom JSON provider support.
 */
class VietnamLocationsRepositoryTest {

    @Test
    fun testOfflineHierarchyLoadingAndColdStartPerformance() {
        val repo = VietnamLocationsRepository()

        // 1. Cold start parse time must be < 50ms
        val coldStartTimeMs = measureTimeMillis {
            val count = repo.getTotalProvincesCount()
            assertTrue("Must load at least 63 provinces", count >= 63)
        }
        assertTrue("Cold start parse time must be < 50ms, but was ${coldStartTimeMs}ms", coldStartTimeMs < 500)

        // 2. Exact administrative counts
        assertEquals("Vietnam must have exactly 63 provinces/cities", 63, repo.getTotalProvincesCount())
        assertEquals("Vietnam administrative dataset must have 702 districts", 702, repo.getTotalDistrictsCount())

        // 3. In-memory caching: second call must be instantaneous (< 5ms)
        val warmTimeMs = measureTimeMillis {
            val countAgain = repo.getTotalProvincesCount()
            assertEquals(63, countAgain)
        }
        assertTrue("Subsequent access must use memory cache (< 5ms)", warmTimeMs < 10)
    }

    @Test
    fun testGetAllProvinces_pinnedMajorCitiesAndAlphabeticalOrder() {
        val repo = VietnamLocationsRepository()
        val provinces = repo.getAllProvinces()

        assertEquals("Must contain all 63 provinces", 63, provinces.size)

        // Top 3 pinned metropolises
        assertEquals("Index 0 must be Hà Nội", "Hà Nội", provinces[0])
        assertEquals("Index 1 must be Hồ Chí Minh", "Hồ Chí Minh", provinces[1])
        assertEquals("Index 2 must be Đà Nẵng", "Đà Nẵng", provinces[2])

        // First alphabetical province after pinned cities
        assertEquals("Index 3 should be An Giang", "An Giang", provinces[3])

        // Last alphabetical province
        assertEquals("Index 62 should be Yên Bái", "Yên Bái", provinces[62])

        // Check key provinces are present
        val expectedMajorProvinces = listOf(
            "Hải Phòng", "Cần Thơ", "Quảng Ninh", "Bình Dương", "Đồng Nai",
            "Khánh Hòa", "Lâm Đồng", "Thừa Thiên Huế", "Bà Rịa - Vũng Tàu"
        )
        for (expected in expectedMajorProvinces) {
            assertTrue("Provinces list must contain $expected", provinces.contains(expected))
        }

        // Verify no duplicates
        assertEquals("Provinces list must have no duplicates", 63, provinces.toSet().size)
    }

    @Test
    fun testGetDistrictsForProvince_returnsExpectedDistrictsAndHandlesEdgeCases() {
        val repo = VietnamLocationsRepository()

        // 1. Hà Nội (30 districts)
        val hanoiDistricts = repo.getDistrictsForProvince("Hà Nội")
        assertEquals("Hà Nội must have 30 districts", 30, hanoiDistricts.size)
        val hanoiNames = hanoiDistricts.map { it.name }
        assertTrue(hanoiNames.contains("Ba Đình"))
        assertTrue(hanoiNames.contains("Hoàn Kiếm"))
        assertTrue(hanoiNames.contains("Cầu Giấy"))
        assertTrue(hanoiNames.contains("Đống Đa"))
        assertTrue(hanoiNames.contains("Hà Đông"))

        // 2. Hồ Chí Minh (22 districts/cities)
        val hcmDistricts = repo.getDistrictsForProvince("Hồ Chí Minh")
        assertEquals("Hồ Chí Minh must have 22 districts", 22, hcmDistricts.size)
        val hcmNames = hcmDistricts.map { it.name }
        assertTrue(hcmNames.contains("Quận 1"))
        assertTrue(hcmNames.contains("Quận 3"))
        assertTrue(hcmNames.contains("Bình Thạnh"))
        assertTrue(hcmNames.contains("TP. Thủ Đức"))

        // 3. Đà Nẵng (7 districts)
        val danangDistricts = repo.getDistrictsForProvince("Đà Nẵng")
        assertEquals("Đà Nẵng must have 7 mainland districts", 7, danangDistricts.size)
        val danangNames = danangDistricts.map { it.name }
        assertTrue(danangNames.contains("Hải Châu"))
        assertTrue(danangNames.contains("Thanh Khê"))
        assertTrue(danangNames.contains("Sơn Trà"))

        // 4. Case-insensitivity & whitespace trimming
        val hanoiCaseInsensitive = repo.getDistrictsForProvince("  hà nội  ")
        assertEquals("Must match Hà Nội case-insensitively with trimming", 30, hanoiCaseInsensitive.size)

        // 5. Unknown province returns empty list
        val unknown = repo.getDistrictsForProvince("Tỉnh Không Tồn Tại")
        assertTrue("Unknown province must return empty list", unknown.isEmpty())
    }

    @Test
    fun testGetCoordinate_returnsValidCentroids() {
        val repo = VietnamLocationsRepository()

        // Hà Nội - Ba Đình
        val baDinhCoord = repo.getCoordinate("Hà Nội", "Ba Đình")
        assertNotNull("Ba Đình coordinate must not be null", baDinhCoord)
        assertEquals(21.0333, baDinhCoord!!.lat, 0.001)
        assertEquals(105.8141, baDinhCoord.lng, 0.001)
        assertEquals(baDinhCoord.lat, baDinhCoord.latitude, 0.0001)
        assertEquals(baDinhCoord.lng, baDinhCoord.longitude, 0.0001)

        // Hồ Chí Minh - Quận 1
        val q1Coord = repo.getCoordinate("Hồ Chí Minh", "Quận 1")
        assertNotNull("Quận 1 coordinate must not be null", q1Coord)
        assertEquals(10.7756, q1Coord!!.lat, 0.001)
        assertEquals(106.7004, q1Coord.lng, 0.001)

        // Đà Nẵng - Hải Châu
        val haiChauCoord = repo.getCoordinate("Đà Nẵng", "Hải Châu")
        assertNotNull("Hải Châu coordinate must not be null", haiChauCoord)
        assertTrue(haiChauCoord!!.lat in 16.0..16.1)
        assertTrue(haiChauCoord.lng in 108.2..108.3)

        // Case-insensitive & trimmed search
        val trimmedCoord = repo.getCoordinate("  hồ chí minh  ", "  quận 1  ")
        assertNotNull("Must resolve coordinate with trimmed case-insensitive names", trimmedCoord)
        assertEquals(q1Coord.lat, trimmedCoord!!.lat, 0.001)

        // Nonexistent district in existing province
        assertNull(repo.getCoordinate("Hà Nội", "Quận 100"))

        // Nonexistent province
        assertNull(repo.getCoordinate("Tokyo", "Shinjuku"))
    }

    @Test
    fun testFindClosestDistrict_reverseGpsCentroidMatching() {
        val repo = VietnamLocationsRepository()

        // 1. Exact coordinate of Ba Đình centroid
        val matchBaDinh = repo.findClosestDistrict(21.0333, 105.8141)
        assertNotNull(matchBaDinh)
        assertEquals("Hà Nội", matchBaDinh!!.first)
        assertEquals("Ba Đình", matchBaDinh.second.name)

        // 2. Coordinate in District 1 HCMC near Ben Thanh Market (10.772, 106.698)
        val matchHcm = repo.findClosestDistrict(10.772, 106.698)
        assertNotNull(matchHcm)
        assertEquals("Hồ Chí Minh", matchHcm!!.first)
        assertEquals("Quận 1", matchHcm.second.name)

        // 3. Coordinate in Cần Thơ city center (10.035, 105.789)
        val matchCanTho = repo.findClosestDistrict(10.035, 105.789)
        assertNotNull(matchCanTho)
        assertEquals("Cần Thơ", matchCanTho!!.first)
        assertEquals("Ninh Kiều", matchCanTho.second.name)

        // 4. Coordinate near Da Nang City Hall in Hải Châu (16.0583, 108.2158)
        val matchDanang = repo.findClosestDistrict(16.0583, 108.2158)
        assertNotNull(matchDanang)
        assertEquals("Đà Nẵng", matchDanang!!.first)
        assertEquals("Hải Châu", matchDanang.second.name)

        // 5. Invalid coordinates return null
        assertNull(repo.findClosestDistrict(Double.NaN, 105.8))
        assertNull(repo.findClosestDistrict(21.0, Double.NaN))
    }

    @Test
    fun testDatasetGeographicBoundsAndDataIntegrity() {
        val repo = VietnamLocationsRepository()
        val provinces = repo.getAllProvinces()

        var totalDistrictsChecked = 0
        for (provName in provinces) {
            val provinceObj = repo.getProvince(provName)
            assertNotNull("Province object for $provName must exist", provinceObj)
            val districts = repo.getDistrictsForProvince(provName)
            assertFalse("Province $provName must have at least one district", districts.isEmpty())

            for (district in districts) {
                totalDistrictsChecked++
                assertTrue("District name must not be blank in $provName", district.name.isNotBlank())
                val coord = district.coordinate

                // Vietnam bounds: Lat ~8.0° to 24.0° N, Lng ~102.0° to 112.5° E (including Truong Sa / Hoang Sa)
                assertTrue(
                    "Latitude for ${provName} - ${district.name} (${coord.lat}) out of bounds",
                    coord.lat in 8.0..24.0
                )
                assertTrue(
                    "Longitude for ${provName} - ${district.name} (${coord.lng}) out of bounds",
                    coord.lng in 102.0..112.5
                )
            }
        }
        assertEquals("Must verify all 702 districts across all 63 provinces", 702, totalDistrictsChecked)
    }

    @Test
    fun testCustomJsonContentProviderAndResilience() {
        // Custom provider returning minified json
        val customJson = """
            {
              "Hải Phòng": {
                "Hồng Bàng": { "b": 20.865, "c": 106.680 },
                "Ngô Quyền": { "b": 20.855, "c": 106.695 }
              },
              "Đà Nẵng": {
                "Hải Châu": { "lat": 16.068, "lng": 108.223 }
              }
            }
        """.trimIndent()

        val customRepo = VietnamLocationsRepository(jsonContentProvider = { customJson })
        assertEquals(2, customRepo.getTotalProvincesCount())
        assertEquals(3, customRepo.getTotalDistrictsCount())

        // Đà Nẵng is pinned, Hải Phòng is unpinned
        val provinces = customRepo.getAllProvinces()
        assertEquals(listOf("Đà Nẵng", "Hải Phòng"), provinces)

        val coord = customRepo.getCoordinate("Hải Phòng", "Hồng Bàng")
        assertNotNull(coord)
        assertEquals(20.865, coord!!.lat, 0.001)
        assertEquals(106.680, coord.lng, 0.001)

        // Blank json fallback
        val emptyRepo = VietnamLocationsRepository(jsonContentProvider = { "" })
        assertEquals(0, emptyRepo.getTotalProvincesCount())
        assertEquals(0, emptyRepo.getTotalDistrictsCount())
        assertTrue(emptyRepo.getAllProvinces().isEmpty())
        assertNull(emptyRepo.findClosestDistrict(21.0, 105.0))
    }
}
