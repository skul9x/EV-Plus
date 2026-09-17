package com.evcs.favorites

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.repository.parseFirestoreFavoritesDocument
import com.evcs.favorites.data.repository.toFirestoreMap
import com.evcs.favorites.domain.StationTelemetryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 01 Verification Test:
 * 1. Full-fidelity Firestore map serialization and deserialization preserving all metadata fields.
 * 2. Legacy Firestore map fallback resilience.
 * 3. Connector string parsing correctly extracting counts from "60kW x 4", "120kW (2)", "30kW: 2 cổng".
 * 4. Port status derivation preserving parsed multiplier counts without clamping to 1.
 */
class FavoritesPersistenceAndConnectorParsingTest {

    @Before
    fun setUp() {
        EvcsRepository.clearParsedConnectorsCache()
    }

    @Test
    fun testFullFidelityFirestoreMapSerializationAndDeserialization() {
        val originalPowers = listOf(
            PowerPort(
                typeWatts = 60000L,
                label = "60kW",
                availablePlugs = 3,
                totalPlugs = 4,
                displayString = "60kW: trống 3/4 cổng"
            ),
            PowerPort(
                typeWatts = 120000L,
                label = "120kW",
                availablePlugs = 1,
                totalPlugs = 2,
                displayString = "120kW: trống 1/2 cổng"
            )
        )

        val originalStation = Station(
            id = "VF_TEST_001",
            name = "Trạm Sạc VinFast Thảo Điền",
            address = "12 Thảo Điền, TP. Thủ Đức, TP. Hồ Chí Minh",
            latitude = 10.801234,
            longitude = 106.732145,
            summary = "24/7, có bảo vệ trông xe",
            connectors = "60kW x 4, 120kW (2)",
            depotStatus = "Maintaining",
            powers = originalPowers,
            totalAvailablePlugs = 4,
            totalPlugs = 6,
            images = listOf("https://example.com/img1.jpg", "https://example.com/img2.jpg"),
            image = "https://example.com/img1.jpg",
            addedAt = 1710000000L,
            isPublic = true,
            isFreeParking = false,
            workingTimeDescription = "06:00 - 22:00",
            evse = "VinFast EV"
        )

        // 1. Serialization
        val firestoreMap = originalStation.toFirestoreMap()
        assertEquals(1710000000L, firestoreMap["added_at"])
        assertEquals("Trạm Sạc VinFast Thảo Điền", firestoreMap["name"])
        assertEquals("12 Thảo Điền, TP. Thủ Đức, TP. Hồ Chí Minh", firestoreMap["address"])
        assertEquals(10.801234, firestoreMap["lat"])
        assertEquals(106.732145, firestoreMap["lon"])
        assertEquals("24/7, có bảo vệ trông xe", firestoreMap["summary"])
        assertEquals("60kW x 4, 120kW (2)", firestoreMap["connectors"])
        assertEquals(6, firestoreMap["total_plugs"])
        assertEquals(4, firestoreMap["total_available_plugs"])
        assertEquals("Maintaining", firestoreMap["depot_status"])
        assertEquals(false, firestoreMap["is_free_parking"])
        assertEquals("06:00 - 22:00", firestoreMap["working_time"])
        assertEquals("VinFast EV", firestoreMap["evse"])

        @Suppress("UNCHECKED_CAST")
        val serializedPowers = firestoreMap["powers"] as? List<Map<String, Any>>
        assertNotNull("Serialized powers must not be null", serializedPowers)
        assertEquals(2, serializedPowers!!.size)

        assertEquals(60000L, serializedPowers[0]["type_watts"])
        assertEquals("60kW", serializedPowers[0]["label"])
        assertEquals(3, serializedPowers[0]["available_plugs"])
        assertEquals(4, serializedPowers[0]["total_plugs"])
        assertEquals("60kW: trống 3/4 cổng", serializedPowers[0]["display_string"])

        assertEquals(120000L, serializedPowers[1]["type_watts"])
        assertEquals("120kW", serializedPowers[1]["label"])
        assertEquals(1, serializedPowers[1]["available_plugs"])
        assertEquals(2, serializedPowers[1]["total_plugs"])
        assertEquals("120kW: trống 1/2 cổng", serializedPowers[1]["display_string"])

        // 2. Deserialization
        val documentData = mapOf(
            "favorites" to mapOf(
                "VF_TEST_001" to firestoreMap
            ),
            "updated_at" to 1710000000L
        )

        val restoredStations = parseFirestoreFavoritesDocument(documentData)
        assertEquals(1, restoredStations.size)

        val restored = restoredStations[0]
        assertEquals("VF_TEST_001", restored.id)
        assertEquals("Trạm Sạc VinFast Thảo Điền", restored.name)
        assertEquals("12 Thảo Điền, TP. Thủ Đức, TP. Hồ Chí Minh", restored.address)
        assertEquals(10.801234, restored.latitude, 0.00001)
        assertEquals(106.732145, restored.longitude, 0.00001)
        assertEquals("24/7, có bảo vệ trông xe", restored.summary)
        assertEquals("60kW x 4, 120kW (2)", restored.connectors)
        assertEquals("Maintaining", restored.depotStatus)
        assertEquals(6, restored.totalPlugs)
        assertEquals(4, restored.totalAvailablePlugs)
        assertFalse(restored.isFreeParking)
        assertEquals("06:00 - 22:00", restored.workingTimeDescription)
        assertEquals("VinFast EV", restored.evse)
        assertEquals(1710000000L, restored.addedAt)
        assertEquals(2, restored.images.size)
        assertEquals("https://example.com/img1.jpg", restored.effectiveImage)

        assertEquals(2, restored.powers.size)
        val p1 = restored.powers[0]
        assertEquals(60000L, p1.typeWatts)
        assertEquals("60kW", p1.label)
        assertEquals(3, p1.availablePlugs)
        assertEquals(4, p1.totalPlugs)
        assertEquals("60kW: trống 3/4 cổng", p1.displayString)
        assertTrue(p1.hasLiveTelemetry)

        val p2 = restored.powers[1]
        assertEquals(120000L, p2.typeWatts)
        assertEquals("120kW", p2.label)
        assertEquals(1, p2.availablePlugs)
        assertEquals(2, p2.totalPlugs)
        assertEquals("120kW: trống 1/2 cổng", p2.displayString)
        assertTrue(p2.hasLiveTelemetry)
    }

    @Test
    fun testLegacyFirestoreMapFallbackResilience() {
        // Legacy document without powers, total_plugs, depot_status, is_free_parking, working_time, evse
        val legacyDoc = mapOf(
            "favorites" to mapOf(
                "LEGACY_001" to mapOf(
                    "name" to "Trạm Cũ Hà Nội",
                    "address" to "1 Phố Huế, Hoàn Kiếm, Hà Nội",
                    "lat" to 21.0180,
                    "lon" to 105.8520,
                    "summary" to "24/7",
                    "connectors" to "60kW x 4",
                    "added_at" to 1705000000L
                ),
                "LEGACY_SCALAR" to 1706000000L
            )
        )

        val localCache = mapOf(
            "LEGACY_SCALAR" to Station(
                id = "LEGACY_SCALAR",
                name = "Trạm Từ Local Cache",
                address = "Số 10 Lê Duẩn",
                latitude = 21.025,
                longitude = 105.84,
                summary = "24/7",
                connectors = "30kW",
                depotStatus = "Normal",
                powers = emptyList(),
                totalPlugs = 2,
                totalAvailablePlugs = 1,
                addedAt = 100L
            )
        )

        val parsed = parseFirestoreFavoritesDocument(legacyDoc, localCache)
        assertEquals(2, parsed.size)

        val legacyStation = parsed.first { it.id == "LEGACY_001" }
        assertEquals("Trạm Cũ Hà Nội", legacyStation.name)
        assertEquals("1 Phố Huế, Hoàn Kiếm, Hà Nội", legacyStation.address)
        assertEquals("Normal", legacyStation.depotStatus) // Default fallback
        assertTrue(legacyStation.isFreeParking) // Default fallback
        assertEquals("24/7", legacyStation.workingTimeDescription) // Default fallback
        assertEquals("VinFast", legacyStation.evse) // Default fallback
        // Derived from connectors "60kW x 4"
        assertEquals(1, legacyStation.powers.size)
        assertEquals(60000L, legacyStation.powers[0].typeWatts)
        assertEquals(4, legacyStation.powers[0].totalPlugs)
        assertEquals(4, legacyStation.powers[0].availablePlugs)
        assertEquals(4, legacyStation.totalPlugs)
        assertEquals(4, legacyStation.totalAvailablePlugs)

        val scalarStation = parsed.first { it.id == "LEGACY_SCALAR" }
        assertEquals("Trạm Từ Local Cache", scalarStation.name)
        assertEquals(1706000000L, scalarStation.addedAt) // Updated timestamp
        assertEquals(2, scalarStation.totalPlugs)
        assertEquals(1, scalarStation.totalAvailablePlugs)
    }

    @Test
    fun testConnectorStringParsingExtractingCounts() {
        val connectorString = "60kW x 4, 120kW (2), 30kW: 2 cổng"
        val parsed = EvcsRepository.parseConnectorsToPowers(connectorString)

        assertEquals(3, parsed.size)

        // Port 1: 60kW x 4
        val p60 = parsed[0]
        assertEquals(60000L, p60.typeWatts)
        assertEquals("60kW x 4", p60.label)
        assertEquals(4, p60.totalPlugs)
        assertEquals(4, p60.availablePlugs)
        assertTrue(p60.hasLiveTelemetry)

        // Port 2: 120kW (2)
        val p120 = parsed[1]
        assertEquals(120000L, p120.typeWatts)
        assertEquals("120kW (2)", p120.label)
        assertEquals(2, p120.totalPlugs)
        assertEquals(2, p120.availablePlugs)
        assertTrue(p120.hasLiveTelemetry)

        // Port 3: 30kW: 2 cổng
        val p30 = parsed[2]
        assertEquals(30000L, p30.typeWatts)
        assertEquals("30kW: 2 cổng", p30.label)
        assertEquals(2, p30.totalPlugs)
        assertEquals(2, p30.availablePlugs)
        assertTrue(p30.hasLiveTelemetry)

        // Additional formats: slash and multiplier before
        val additionalString = "60kW / 4, 4 x 120kW"
        val parsedAdditional = EvcsRepository.parseConnectorsToPowers(additionalString)
        assertEquals(2, parsedAdditional.size)
        assertEquals(4, parsedAdditional[0].totalPlugs)
        assertEquals(4, parsedAdditional[1].totalPlugs)

        // Baseline string without multipliers (must default to 0 plugs)
        val baselineString = "30kW, 20kW, 3.5kW"
        val parsedBaseline = EvcsRepository.parseConnectorsToPowers(baselineString)
        assertEquals(3, parsedBaseline.size)
        assertEquals(0, parsedBaseline[0].totalPlugs)
        assertEquals(0, parsedBaseline[0].availablePlugs)
        assertFalse(parsedBaseline[0].hasLiveTelemetry)
        assertEquals("30kW", parsedBaseline[0].chipDisplayString)
    }

    @Test
    fun testPortStatusDerivationPreservingMultiplierCountsWithoutClampingToOne() {
        // Case 1: PowerPort with totalPlugs <= 0, but label contains multiplier "60kW x 4"
        val powersWithLabel = listOf(
            PowerPort(typeWatts = 60000L, label = "60kW x 4", availablePlugs = 0, totalPlugs = 0)
        )
        val statuses1 = StationTelemetryParser.derivePortStatuses(
            powers = powersWithLabel,
            busyByKw = emptyMap()
        )
        assertEquals(1, statuses1.size)
        assertEquals(60, statuses1[0].kw)
        assertEquals("Total ports must be 4 from label, not clamped to 1", 4, statuses1[0].totalPorts)
        assertEquals(4, statuses1[0].availablePorts)
        assertEquals(0, statuses1[0].busyCount)

        // Case 2: PowerPort with totalPlugs <= 0, connectors string explicitly passed
        val powersUnverified = listOf(
            PowerPort(typeWatts = 120000L, label = "120kW", availablePlugs = 0, totalPlugs = 0)
        )
        val statuses2 = StationTelemetryParser.derivePortStatuses(
            powers = powersUnverified,
            busyByKw = mapOf(120 to 2),
            connectors = "120kW (6)"
        )
        assertEquals(1, statuses2.size)
        assertEquals(120, statuses2[0].kw)
        assertEquals("Total ports must be 6 from connectors, not clamped to 1 or busyCount", 6, statuses2[0].totalPorts)
        assertEquals(2, statuses2[0].busyCount)
        assertEquals(4, statuses2[0].availablePorts)

        // Case 3: PowerPort with totalPlugs already set to 8
        val powersVerified = listOf(
            PowerPort(typeWatts = 250000L, label = "250kW", availablePlugs = 5, totalPlugs = 8)
        )
        val statuses3 = StationTelemetryParser.derivePortStatuses(
            powers = powersVerified,
            busyByKw = mapOf(250 to 3)
        )
        assertEquals(1, statuses3.size)
        assertEquals(250, statuses3[0].kw)
        assertEquals(8, statuses3[0].totalPorts)
        assertEquals(3, statuses3[0].busyCount)
        assertEquals(5, statuses3[0].availablePorts)

        // Case 4: PowerPort with displayString containing "30kW: 2 cổng", busy = 1
        val powersWithColon = listOf(
            PowerPort(typeWatts = 30000L, label = "30kW", availablePlugs = 0, totalPlugs = 0, displayString = "30kW: 2 cổng")
        )
        val statuses4 = StationTelemetryParser.derivePortStatuses(
            powers = powersWithColon,
            busyByKw = mapOf(30 to 1)
        )
        assertEquals(1, statuses4.size)
        assertEquals(30, statuses4[0].kw)
        assertEquals(2, statuses4[0].totalPorts)
        assertEquals(1, statuses4[0].busyCount)
        assertEquals(1, statuses4[0].availablePorts)
    }
}
