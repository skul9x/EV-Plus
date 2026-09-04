package com.evcs.favorites

import androidx.compose.runtime.Immutable
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.TrafficCondition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 03 Verification Test:
 * Core Verifications:
 * 1. Station, PowerPort, DrivingMetrics
 *    carry @Immutable annotation.
 * 2. Data class equality and copy contracts are preserved when telemetry or metrics change.
 * 3. Connector power parsing results are identical and idempotent across repeated calls using precompiled KW_REGEX.
 * 4. Station list update isolation: modifying one station in a collection leaves other station identities and hashes unchanged.
 * 5. Serialization and deserialization via @Serializable remain completely intact with @Immutable.
 */
class ComposeStabilityAndHotPathRecompositionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun hasImmutableAnnotation(clazz: Class<*>): Boolean {
        if (clazz.isAnnotationPresent(Immutable::class.java) ||
            clazz.annotations.any { it.annotationClass.java.name == Immutable::class.java.name }
        ) {
            return true
        }

        // Compose @Immutable has AnnotationRetention.BINARY, which is stored in the
        // bytecode RuntimeInvisibleAnnotations attribute and constant pool.
        val resourcePath = clazz.name.replace('.', '/') + ".class"
        val stream = clazz.classLoader?.getResourceAsStream(resourcePath)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)
        if (stream != null) {
            val bytes = stream.use { it.readBytes() }
            val bytecodeString = String(bytes, Charsets.ISO_8859_1)
            return bytecodeString.contains("Landroidx/compose/runtime/Immutable;")
        }
        return false
    }

    @Test
    fun testDomainModelsCarryImmutableAnnotation() {
        val models = listOf(
            Station::class.java,
            PowerPort::class.java,
            DrivingMetrics::class.java
        )

        for (clazz in models) {
            assertTrue(
                "Class ${clazz.simpleName} must be annotated with @Immutable",
                hasImmutableAnnotation(clazz)
            )
        }
    }

    @Test
    fun testDataClassEqualityAndCopyContracts() {
        val originalStation = Station(
            id = "station_01",
            name = "Trạm Vincom Bà Triệu",
            address = "191 Bà Triệu, Hai Bà Trưng, Hà Nội",
            latitude = 21.0115,
            longitude = 105.8501,
            summary = "Trụ sạc nhanh 60kW và 250kW",
            connectors = "60kW, 250kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 2, totalPlugs = 4),
                PowerPort(typeWatts = 250000L, label = "250kW", availablePlugs = 1, totalPlugs = 2)
            ),
            totalAvailablePlugs = 3,
            totalPlugs = 6,
            distanceKm = 1.8,
            drivingMetrics = DrivingMetrics(
                distanceMeters = 2100,
                durationSeconds = 480,
                trafficCondition = TrafficCondition.FREE_FLOW,
                engineUsed = RoutingEngineType.OSRM
            )
        )

        // Verifying exact copy equality
        val identicalStation = originalStation.copy()
        assertEquals(originalStation, identicalStation)
        assertEquals(originalStation.hashCode(), identicalStation.hashCode())

        // Modifying telemetry creates distinct object while preserving unmodified fields
        val updatedTelemetry = originalStation.copy(
            totalAvailablePlugs = 1,
            powers = listOf(
                PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 0, totalPlugs = 4),
                PowerPort(typeWatts = 250000L, label = "250kW", availablePlugs = 1, totalPlugs = 2)
            )
        )

        assertNotEquals(originalStation, updatedTelemetry)
        assertNotEquals(originalStation.powers, updatedTelemetry.powers)
        assertEquals(originalStation.id, updatedTelemetry.id)
        assertEquals(originalStation.name, updatedTelemetry.name)
        assertEquals(originalStation.address, updatedTelemetry.address)
        assertEquals(originalStation.drivingMetrics, updatedTelemetry.drivingMetrics)

        // Modifying drivingMetrics preserves identity and other fields
        val updatedMetrics = originalStation.copy(
            drivingMetrics = DrivingMetrics(
                distanceMeters = 5000,
                durationSeconds = 900,
                trafficCondition = TrafficCondition.HEAVY_CONGESTION,
                engineUsed = RoutingEngineType.OSRM
            )
        )

        assertNotEquals(originalStation, updatedMetrics)
        assertEquals(originalStation.powers, updatedMetrics.powers)
        assertEquals(originalStation.totalAvailablePlugs, updatedMetrics.totalAvailablePlugs)
        assertNotEquals(originalStation.drivingMetrics, updatedMetrics.drivingMetrics)
    }

    @Test
    fun testConnectorPowerParsingIsIdempotentAndUsesPrecompiledRegex() {
        // Verify precompiled regex exists and is properly formed
        assertNotNull(EvcsRepository.KW_REGEX)
        assertTrue(EvcsRepository.KW_REGEX.pattern.contains("kW"))

        val testConnectors = " 120kW, 60kW , 7kW, DC 250 kW, 3.3kW "

        // First pass
        val firstResult = EvcsRepository.parseConnectorsToPowers(testConnectors)
        assertEquals(5, firstResult.size)
        assertEquals(120000L, firstResult[0].typeWatts)
        assertEquals("120kW", firstResult[0].label)
        assertEquals(60000L, firstResult[1].typeWatts)
        assertEquals("60kW", firstResult[1].label)
        assertEquals(7000L, firstResult[2].typeWatts)
        assertEquals("7kW", firstResult[2].label)
        assertEquals(250000L, firstResult[3].typeWatts)
        assertEquals("DC 250 kW", firstResult[3].label)
        assertEquals(3300L, firstResult[4].typeWatts)
        assertEquals("3.3kW", firstResult[4].label)

        // Repeat passes must be completely identical and idempotent
        val secondResult = EvcsRepository.parseConnectorsToPowers(testConnectors)
        assertEquals(firstResult, secondResult)

        // Blank/null cases
        assertTrue(EvcsRepository.parseConnectorsToPowers(null).isEmpty())
        assertTrue(EvcsRepository.parseConnectorsToPowers("   ").isEmpty())
    }

    @Test
    fun testStationListUpdateIsolation() {
        val s1 = Station(
            id = "s1", name = "Station 1", address = "Address 1",
            latitude = 21.0, longitude = 105.0, summary = "", connectors = "60kW", depotStatus = "Normal"
        )
        val s2 = Station(
            id = "s2", name = "Station 2", address = "Address 2",
            latitude = 21.1, longitude = 105.1, summary = "", connectors = "120kW", depotStatus = "Normal"
        )
        val s3 = Station(
            id = "s3", name = "Station 3", address = "Address 3",
            latitude = 21.2, longitude = 105.2, summary = "", connectors = "250kW", depotStatus = "Normal"
        )

        val stationList = listOf(s1, s2, s3)
        val s2OriginalHash = s2.hashCode()
        val s3OriginalHash = s3.hashCode()

        // Update s1 only (e.g. real-time availability update)
        val updatedS1 = s1.copy(totalAvailablePlugs = 5)
        val updatedList = stationList.map { if (it.id == updatedS1.id) updatedS1 else it }

        // S1 is changed
        assertNotEquals(stationList[0], updatedList[0])

        // S2 and S3 instances and hashes are strictly identical, ensuring Compose smart skipping
        assertEquals(s2, updatedList[1])
        assertEquals(s3, updatedList[2])
        assertEquals(s2OriginalHash, updatedList[1].hashCode())
        assertEquals(s3OriginalHash, updatedList[2].hashCode())
        assertTrue(s2 === updatedList[1])
        assertTrue(s3 === updatedList[2])
    }

    @Test
    fun testSerializationDeserializationRemainsIntactWithImmutable() {
        val originalStation = Station(
            id = "ser_station_1",
            name = "Trạm Test Serialization",
            address = "Số 1 Hoàn Kiếm, Hà Nội",
            latitude = 21.0285,
            longitude = 105.8542,
            summary = "Tóm tắt",
            connectors = "60kW, 120kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(
                    typeWatts = 60000L,
                    label = "60kW",
                    availablePlugs = 1,
                    totalPlugs = 2,
                    displayString = "60kW: trống 1/2 cổng"
                )
            ),
            totalAvailablePlugs = 1,
            totalPlugs = 2,
            drivingMetrics = DrivingMetrics(
                distanceMeters = 3500,
                durationSeconds = 600,
                trafficCondition = TrafficCondition.MODERATE_CONGESTION,
                engineUsed = RoutingEngineType.OSRM
            )
        )

        val serialized = json.encodeToString(originalStation)
        assertTrue(serialized.contains("ser_station_1"))
        assertTrue(serialized.contains("Trạm Test Serialization"))

        val deserialized = json.decodeFromString<Station>(serialized)
        assertEquals(originalStation, deserialized)
        assertEquals(originalStation.powers, deserialized.powers)
        assertEquals(originalStation.drivingMetrics, deserialized.drivingMetrics)
    }
}
