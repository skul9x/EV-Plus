package com.evcs.favorites

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.TrafficCondition
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 07 Comprehensive Verification Test:
 * 1. app/proguard-rules.pro exists and defines valid R8 rules for all key libraries:
 *    - Kotlinx Serialization (Serializable, Companion, serializer(), KSerializer)
 *    - OkHttp & Okio (dontwarn, PublicSuffixDatabase, reflection)
 *    - AndroidX Security Crypto (keepclassmembers, dontwarn)
 *    - Google Play Services Location (location classes and interfaces)
 *    - Jetpack Compose (runtime attributes, preview)
 *    - Kotlinx Coroutines (dontwarn, volatile fields)
 * 2. build.gradle.kts contains active isMinifyEnabled = true and isShrinkResources = true.
 * 3. Kotlinx Serialization json engine parses core domain models (Station,
 *    PowerPort, DrivingMetrics) cleanly and companions have working serializer() access.
 */
class R8ProGuardConfigurationVerificationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun resolveFile(vararg candidatePaths: String): File {
        for (path in candidatePaths) {
            val file = File(path)
            if (file.exists()) return file
        }
        val userDir = System.getProperty("user.dir") ?: "."
        for (path in candidatePaths) {
            val file = File(userDir, path)
            if (file.exists()) return file
        }
        throw AssertionError("Could not find any file from candidates: ${candidatePaths.joinToString()} (user.dir=$userDir)")
    }

    @Test
    fun testProguardRulesFileExistsAndDefinesRequiredLibraryRules() {
        val proguardFile = resolveFile(
            "app/proguard-rules.pro",
            "proguard-rules.pro",
            "../app/proguard-rules.pro"
        )
        assertTrue("proguard-rules.pro must exist", proguardFile.exists())

        val content = proguardFile.readText()

        // 1. Kotlinx Serialization
        assertTrue(
            "Must preserve @Serializable classes or fields",
            content.contains("@kotlinx.serialization.Serializable")
        )
        assertTrue(
            "Must preserve Companion serializer",
            content.contains("serializer(") || content.contains("serializer")
        )
        assertTrue(
            "Must preserve Companion object reference",
            content.contains("Companion")
        )
        assertTrue(
            "Must preserve KSerializer implementations",
            content.contains("kotlinx.serialization.KSerializer")
        )
        assertTrue(
            "Must preserve annotation attributes",
            content.contains("-keepattributes *Annotation*")
        )

        // 2. OkHttp & Okio
        assertTrue("Must include -dontwarn okhttp3", content.contains("-dontwarn okhttp3.**"))
        assertTrue("Must include -dontwarn okio", content.contains("-dontwarn okio.**"))
        assertTrue(
            "Must keep PublicSuffixDatabase",
            content.contains("okhttp3.internal.publicsuffix.PublicSuffixDatabase")
        )

        // 3. AndroidX Security Crypto
        assertTrue(
            "Must preserve androidx.security.crypto members",
            content.contains("androidx.security.crypto.**")
        )
        assertTrue(
            "Must include -dontwarn androidx.security.crypto",
            content.contains("-dontwarn androidx.security.crypto.**")
        )

        // 4. Google Play Services Location
        assertTrue(
            "Must preserve com.google.android.gms.location classes",
            content.contains("com.google.android.gms.location.**")
        )

        // 5. Jetpack Compose
        assertTrue(
            "Must preserve Compose runtime attributes/classes",
            content.contains("androidx.compose")
        )

        // 6. Kotlinx Coroutines
        assertTrue(
            "Must include -dontwarn kotlinx.coroutines",
            content.contains("-dontwarn kotlinx.coroutines.**")
        )
    }

    @Test
    fun testBuildGradleKtsEnablesMinificationAndResourceShrinking() {
        val buildFile = resolveFile(
            "app/build.gradle.kts",
            "build.gradle.kts",
            "../app/build.gradle.kts"
        )
        assertTrue("build.gradle.kts must exist", buildFile.exists())

        val content = buildFile.readText()

        assertTrue(
            "build.gradle.kts must have isMinifyEnabled = true",
            content.contains("isMinifyEnabled = true")
        )
        assertTrue(
            "build.gradle.kts must have isShrinkResources = true",
            content.contains("isShrinkResources = true")
        )
        assertTrue(
            "build.gradle.kts must reference proguard-rules.pro",
            content.contains("proguard-rules.pro")
        )
        assertTrue(
            "build.gradle.kts must reference proguard-android-optimize.txt",
            content.contains("proguard-android-optimize.txt")
        )
    }

    @Test
    fun testSerializationCoreModelsRoundTrip() {
        // 1. PowerPort
        val port = PowerPort(
            typeWatts = 60000L,
            label = "60kW",
            availablePlugs = 2,
            totalPlugs = 4,
            displayString = "60kW: trống 2/4 cổng"
        )
        val portJson = json.encodeToString(port)
        val decodedPort = json.decodeFromString<PowerPort>(portJson)
        assertEquals(port, decodedPort)
        assertTrue(decodedPort.hasLiveTelemetry)
        assertEquals("60kW: trống 2/4", decodedPort.chipDisplayString)

        // 2. DrivingMetrics
        val metrics = DrivingMetrics(
            distanceMeters = 3500L,
            durationSeconds = 480L,
            staticDurationSeconds = 600L,
            trafficCondition = TrafficCondition.FREE_FLOW,
            engineUsed = RoutingEngineType.OSRM
        )
        val metricsJson = json.encodeToString(metrics)
        val decodedMetrics = json.decodeFromString<DrivingMetrics>(metricsJson)
        assertEquals(metrics, decodedMetrics)
        assertEquals(3500L, decodedMetrics.distanceMeters)
        assertEquals(480L, decodedMetrics.durationSeconds)

        // 3. Station (Parent Model with all nested models)
        val station = Station(
            id = "test-station-r8",
            name = "Trạm Sạc EV Plus Landmark",
            address = "720A Điện Biên Phủ, P.22, Bình Thạnh, TP.HCM",
            latitude = 10.7950,
            longitude = 106.7218,
            summary = "Trạm sạc cao cấp",
            connectors = "CCS2, Type 2",
            depotStatus = "Normal",
            powers = listOf(port),
            totalAvailablePlugs = 2,
            totalPlugs = 4,
            image = "https://example.com/landmark.jpg",
            isPublic = true,
            isFreeParking = true,
            workingTimeDescription = "24/7",
            distanceKm = 3.5,
            drivingMetrics = metrics
        )
        val stationJson = json.encodeToString(station)
        val decodedStation = json.decodeFromString<Station>(stationJson)
        assertEquals(station, decodedStation)
        assertTrue(decodedStation.hasLiveTelemetry)
        assertEquals(3.5, decodedStation.effectiveDistanceKm ?: 0.0, 0.001)
        assertEquals(480L, decodedStation.effectiveDurationSeconds)
        assertNotNull(decodedStation.powers)
        assertEquals(1, decodedStation.powers.size)
        assertEquals("60kW", decodedStation.powers[0].label)

        // Verify companion serializers are accessible as expected by Proguard rules
        assertNotNull(PowerPort.serializer())
        assertNotNull(DrivingMetrics.serializer())
        assertNotNull(Station.serializer())
    }
}
