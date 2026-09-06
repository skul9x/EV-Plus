package com.evcs.favorites

import com.evcs.favorites.data.model.EvsePowerRaw
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.network.vinfast.VinFastConnectorDto
import com.evcs.favorites.data.network.vinfast.VinFastStationMapper
import com.evcs.favorites.data.network.vinfast.VinFastStationStatusDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File-based comprehensive test suite for Phase 02: DTO Mapping & EV Car Port Filtering.
 * Verifies car-only port filtering (type <= 7000 dropped), pure-motorbike station elimination,
 * total and available plug re-aggregation, CDN image URL resolution, location ID preservation,
 * and Tier-2 fallback mapping.
 */
class VinFastStationMapperTest {

    @Test
    fun testCarPortFiltering_excludesMotorbikePlugsAndReaggregatesTotals() {
        val connectors = listOf(
            VinFastConnectorDto(type = 3500, count = 4, total = 8, powerType = "AC"),
            VinFastConnectorDto(type = 7000, count = 2, total = 2, powerType = "AC"),
            VinFastConnectorDto(type = 11000, count = 1, total = 2, powerType = "AC"),
            VinFastConnectorDto(type = 60000, count = 2, total = 4, powerType = "DC"),
            VinFastConnectorDto(type = 120000, count = 3, total = 6, powerType = "DC")
        )

        val dto = VinFastStationStatusDto(
            locationId = "C.HNO11417",
            stationName = "Trạm sạc xe điện Vincom Center Long Biên",
            stationAddress = "Khu đô thị Vinhomes Riverside, Long Biên, Hà Nội",
            numberOfAvailableEvse = 12,
            totalEvse = 22,
            connectors = connectors,
            depotStatus = "Normal",
            isPublic = true,
            isFreeParking = true,
            workingTimeDescription = "24/7"
        )

        val station = VinFastStationMapper.toDomainStation(dto)
        assertNotNull("Station must not be null when car ports exist", station)
        station!!

        // Assert station.powers.size == 3 (only 11kW, 60kW, 120kW present)
        assertEquals(3, station.powers.size)
        assertTrue("No ports <= 7000W should remain", station.powers.none { it.typeWatts <= 7000L })

        val types = station.powers.map { it.typeWatts }
        assertEquals(listOf(11000L, 60000L, 120000L), types)

        // Assert re-aggregated totals
        assertEquals(12, station.totalPlugs)
        assertEquals(6, station.totalAvailablePlugs)

        // Verify PowerPort display string and labels
        val p11 = station.powers.first { it.typeWatts == 11000L }
        assertEquals("11kW", p11.label)
        assertEquals(1, p11.availablePlugs)
        assertEquals(2, p11.totalPlugs)
        assertEquals("11kW: trống 1/2 cổng", p11.displayString)

        val p60 = station.powers.first { it.typeWatts == 60000L }
        assertEquals("60kW", p60.label)
        assertEquals(2, p60.availablePlugs)
        assertEquals(4, p60.totalPlugs)
        assertEquals("60kW: trống 2/4 cổng", p60.displayString)

        val p120 = station.powers.first { it.typeWatts == 120000L }
        assertEquals("120kW", p120.label)
        assertEquals(3, p120.availablePlugs)
        assertEquals(6, p120.totalPlugs)
        assertEquals("120kW: trống 3/6 cổng", p120.displayString)

        // Verify connectors string
        assertEquals("11kW, 60kW, 120kW", station.connectors)
        assertEquals("VinFast", station.evse)
    }

    @Test
    fun testPureMotorbikeStation_returnsNull() {
        val bikeOnlyConnectors = listOf(
            VinFastConnectorDto(type = 3500, count = 4, total = 8, powerType = "AC"),
            VinFastConnectorDto(type = 7000, count = 2, total = 2, powerType = "AC")
        )

        val dto = VinFastStationStatusDto(
            locationId = "M.HNO99999",
            stationName = "Trạm sạc xe máy điện VinFast Times City Basement",
            stationAddress = "Hầm B2, Times City, Hai Bà Trưng, Hà Nội",
            numberOfAvailableEvse = 6,
            totalEvse = 10,
            connectors = bikeOnlyConnectors
        )

        val station = VinFastStationMapper.toDomainStation(dto)
        assertNull("Pure motorbike station must be eliminated and return null", station)
    }

    @Test
    fun testImageCdnResolution_HandlesRelativeAndAbsoluteUrls() {
        val relative = "charging-station-car/depot/images/sample.jpg"
        val resolvedRelative = VinFastStationMapper.resolveImageUrl(relative)
        assertNotNull(resolvedRelative)
        assertTrue("Output must start with https://", resolvedRelative!!.startsWith("https://"))
        assertTrue("Output must contain sample.jpg", resolvedRelative.contains("sample.jpg"))
        assertEquals("https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/sample.jpg", resolvedRelative)

        // Absolute URL
        val absolute = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/sample.jpg"
        val resolvedAbsolute = VinFastStationMapper.resolveImageUrl(absolute)
        assertEquals("Absolute URL must be retained unchanged", absolute, resolvedAbsolute)

        // CloudFront CDN URL
        val cloudfront = "https://d1aza9v8tzxrkt.cloudfront.net/charging-station-car/depot/images/sample.jpg"
        val resolvedCloudfront = VinFastStationMapper.resolveImageUrl(cloudfront)
        assertEquals(cloudfront, resolvedCloudfront)

        // Null and blank URLs
        assertNull(VinFastStationMapper.resolveImageUrl(null))
        assertNull(VinFastStationMapper.resolveImageUrl(""))
        assertNull(VinFastStationMapper.resolveImageUrl("   "))
    }

    @Test
    fun testLocationIdPreservation() {
        val locationId = "C.HNO11417"
        val dto = VinFastStationStatusDto(
            locationId = locationId,
            stationName = "VinFast Vincom Long Biên",
            connectors = listOf(
                VinFastConnectorDto(type = 60000, count = 2, total = 2)
            )
        )

        val station = VinFastStationMapper.toDomainStation(dto)
        assertNotNull(station)
        assertEquals("C.HNO11417", station!!.id)

        // Verify compatibility with Firestore document paths (users/{uid}/userdata/favorites/{stationId})
        assertTrue("Station ID must not be blank", station.id.isNotBlank())
        assertFalse("Firestore doc path prohibits forward slashes in ID", station.id.contains("/"))
        val firestorePath = "users/sampleUid123/userdata/favorites/${station.id}"
        assertEquals("users/sampleUid123/userdata/favorites/C.HNO11417", firestorePath)
    }

    @Test
    fun testStationNameSanitizationAndDistanceCalculation() {
        val dto = VinFastStationStatusDto(
            locationId = "C.HNO11417",
            stationName = "Trạm sạc xe điện Vincom Center Metropolis Liễu Giai",
            stationAddress = "29 Liễu Giai, Ba Đình, Hà Nội",
            latitude = 21.0313,
            longitude = 105.8152,
            connectors = listOf(
                VinFastConnectorDto(type = 60000, count = 1, total = 2)
            )
        )

        // Provide user coordinates near Hanoi Hoan Kiem (~3.5 km away)
        val userLat = 21.0285
        val userLon = 105.8542
        val station = VinFastStationMapper.toDomainStation(dto, userLat = userLat, userLon = userLon)

        assertNotNull(station)
        // Sanitizer strips "Trạm sạc xe điện"
        assertEquals("Vincom Center Metropolis Liễu Giai", station!!.name)
        assertNotNull("Distance should be computed when user coordinates provided", station.distanceKm)
        assertTrue("Distance should be around 4.0 km", station.distanceKm!! in 3.5..4.5)
    }

    @Test
    fun testWattageFormattingAcrossTiers() {
        assertEquals("11kW", VinFastStationMapper.formatWattageLabel(11000L))
        assertEquals("20kW", VinFastStationMapper.formatWattageLabel(20000L))
        assertEquals("22kW", VinFastStationMapper.formatWattageLabel(22000L))
        assertEquals("30kW", VinFastStationMapper.formatWattageLabel(30000L))
        assertEquals("60kW", VinFastStationMapper.formatWattageLabel(60000L))
        assertEquals("120kW", VinFastStationMapper.formatWattageLabel(120000L))
        assertEquals("150kW", VinFastStationMapper.formatWattageLabel(150000L))
        assertEquals("180kW", VinFastStationMapper.formatWattageLabel(180000L))
        assertEquals("250kW", VinFastStationMapper.formatWattageLabel(250000L))
        assertEquals("360kW", VinFastStationMapper.formatWattageLabel(360000L))
        assertEquals("7.4kW", VinFastStationMapper.formatWattageLabel(7400L))
        assertEquals("3.5kW", VinFastStationMapper.formatWattageLabel(3500L))
        assertEquals("", VinFastStationMapper.formatWattageLabel(0L))
        assertEquals("", VinFastStationMapper.formatWattageLabel(-1000L))
    }

    @Test
    fun testBatchMappingDiscardsMotorbikeStations() {
        val carStation = VinFastStationStatusDto(
            locationId = "C.01",
            stationName = "Station Car",
            connectors = listOf(VinFastConnectorDto(type = 60000, count = 2, total = 2))
        )
        val bikeStation = VinFastStationStatusDto(
            locationId = "M.02",
            stationName = "Station Bike",
            connectors = listOf(VinFastConnectorDto(type = 3500, count = 2, total = 2))
        )

        val result = VinFastStationMapper.toDomainStations(listOf(carStation, bikeStation))
        assertEquals(1, result.size)
        assertEquals("C.01", result[0].id)
    }

    @Test
    fun testTier2FallbackMapping() {
        val raw = SearchStationRaw(
            locationId = "C.HNO11417",
            stationName = "Trạm sạc Vincom Long Biên",
            stationAddress = "Long Biên",
            latitude = 21.0456,
            longitude = 105.9012,
            evsePowers = listOf(
                EvsePowerRaw(type = 3500, numberOfAvailableEvse = 4, totalEvse = 4), // Motorbike -> dropped
                EvsePowerRaw(type = 60000, numberOfAvailableEvse = 2, totalEvse = 4, powerType = "DC") // Kept
            )
        )

        val station = VinFastStationMapper.toDomainStation(raw)
        assertNotNull(station)
        assertEquals(1, station!!.powers.size)
        assertEquals(60000L, station.powers[0].typeWatts)
        assertEquals(2, station.totalAvailablePlugs)
        assertEquals(4, station.totalPlugs)

        // Pure motorbike SearchStationRaw
        val bikeRaw = SearchStationRaw(
            locationId = "M.001",
            stationName = "Bike only",
            evsePowers = listOf(EvsePowerRaw(type = 3500, numberOfAvailableEvse = 2, totalEvse = 2))
        )
        assertNull(VinFastStationMapper.toDomainStation(bikeRaw))
    }
}
