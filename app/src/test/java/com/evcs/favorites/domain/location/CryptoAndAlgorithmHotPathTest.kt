package com.evcs.favorites.domain.location

import com.evcs.favorites.data.crypto.EvcsHmacSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Comprehensive verification test for Phase 01: Cryptographic & Algorithm Hot-Paths.
 *
 * Verifies:
 * 1. PERF-CPU-03: EvcsHmacSigner bit-shift hex formatting correctness across known test vectors,
 *    edge cases (padding, leading zeroes, empty payload, custom secrets) and bit-for-bit parity
 *    with reference javax.crypto.Mac + String.format outputs.
 * 2. PERF-ROUT-02: DistanceCalculator.clusterPoints O(1) center accumulation accuracy,
 *    proper threshold grouping, coordinate filtering, and center coordinate parity with standard averages.
 */
class CryptoAndAlgorithmHotPathTest {

    // Reference implementation of legacy String.format hex formatting
    private fun legacyReferenceHmacSign(body: String, timestamp: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)
        val dataToSign = (body + timestamp).toByteArray(StandardCharsets.UTF_8)
        val hmacBytes = mac.doFinal(dataToSign)
        val sb = StringBuilder(hmacBytes.size * 2)
        for (b in hmacBytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    // =========================================================================
    // 1. EvcsHmacSigner Optimization Tests (PERF-CPU-03)
    // =========================================================================

    @Test
    fun testHmacSha256_knownVerifiedVector() {
        val body = "{\"latitude\":21.0,\"longitude\":105.8}"
        val timestamp = "1725330000000"
        val expectedSignature = "1badb1fcb193889b6baae5fe72eb3437b887bad0f8041123de03aadd8ec45834"

        val signature = EvcsHmacSigner.sign(body, timestamp)
        assertEquals(expectedSignature, signature)

        val headers = EvcsHmacSigner.createSignedHeaders(body, timestamp)
        assertEquals(expectedSignature, headers["X-App-Signature"])
        assertEquals(timestamp, headers["X-App-Timestamp"])
        assertEquals("EVCS/A1.57", headers["User-Agent"])
    }

    @Test
    fun testHmacSha256_exactMatchWithLegacyReferenceAcrossVariousInputs() {
        val testCases = listOf(
            Pair("", "0"),
            Pair("{}", "1725330000000"),
            Pair("{\"query\":\"VinFast\",\"page\":1}", "1725339999999"),
            Pair("Special Characters !@#$%^&*()_+-=[]{}|;':,.<>/?~`", "1234567890"),
            Pair("Tiếng Việt có dấu: Trạm sạc VinFast Landmark 81", "9876543210")
        )

        for ((body, timestamp) in testCases) {
            val expected = legacyReferenceHmacSign(body, timestamp, EvcsHmacSigner.SECRET_KEY)
            val actual = EvcsHmacSigner.sign(body, timestamp)
            assertEquals("Mismatch for body: $body, timestamp: $timestamp", expected, actual)
            assertEquals(64, actual.length)
            assertTrue(actual.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun testHmacSha256_customSecretKey() {
        val body = "custom-body"
        val timestamp = "100"
        val customSecret = "custom-secret-key-12345"

        val expected = legacyReferenceHmacSign(body, timestamp, customSecret)
        val actual = EvcsHmacSigner.sign(body, timestamp, customSecret)
        assertEquals(expected, actual)
    }

    // =========================================================================
    // 2. DistanceCalculator.clusterPoints Optimization Tests (PERF-ROUT-02)
    // =========================================================================

    @Test
    fun testClusterPoints_emptyOrInvalidCoordinatesFiltered() {
        val emptyResult = DistanceCalculator.clusterPoints(emptyList())
        assertTrue(emptyResult.isEmpty())

        val invalidPoints = listOf(
            Pair(0.0, 0.0),
            Pair(91.0, 105.0),
            Pair(-91.0, 105.0),
            Pair(21.0, 181.0),
            Pair(21.0, -181.0)
        )
        val filteredResult = DistanceCalculator.clusterPoints(invalidPoints)
        assertTrue(filteredResult.isEmpty())
    }

    @Test
    fun testClusterPoints_groupsNearbyPointsAndComputesAccurateCenter() {
        // Hanoi area coordinates within ~5-7 km of Hoan Kiem (21.0285, 105.8542)
        val hanoiHoanKiem = Pair(21.0285, 105.8542)
        val hanoiOpera = Pair(21.0245, 105.8575) // ~560m
        val hanoiKeangnam = Pair(21.0168, 105.7839) // ~7.2km

        // Da Nang coordinate (~627 km away from Hanoi)
        val danangDragonBridge = Pair(16.0678, 108.2208)

        // HCMC coordinate (~1138 km away from Hanoi)
        val hcmcBenThanh = Pair(10.7725, 106.6980)

        val input = listOf(hanoiHoanKiem, hanoiOpera, hanoiKeangnam, danangDragonBridge, hcmcBenThanh)

        // Max distance 15.0 km
        val clusters = DistanceCalculator.clusterPoints(input, maxDistanceKm = 15.0)

        // Expected 3 clusters: Hanoi (3 points), Da Nang (1 point), HCMC (1 point)
        assertEquals(3, clusters.size)

        val hanoiCluster = clusters.first { it.points.contains(hanoiHoanKiem) }
        assertEquals(3, hanoiCluster.points.size)
        assertTrue(hanoiCluster.points.contains(hanoiOpera))
        assertTrue(hanoiCluster.points.contains(hanoiKeangnam))

        // Center calculation must strictly match arithmetic average
        val expectedHanoiCenterLat = (hanoiHoanKiem.first + hanoiOpera.first + hanoiKeangnam.first) / 3.0
        val expectedHanoiCenterLon = (hanoiHoanKiem.second + hanoiOpera.second + hanoiKeangnam.second) / 3.0
        assertEquals(expectedHanoiCenterLat, hanoiCluster.center.first, 1e-9)
        assertEquals(expectedHanoiCenterLon, hanoiCluster.center.second, 1e-9)

        val danangCluster = clusters.first { it.points.contains(danangDragonBridge) }
        assertEquals(1, danangCluster.points.size)
        assertEquals(danangDragonBridge.first, danangCluster.center.first, 1e-9)
        assertEquals(danangDragonBridge.second, danangCluster.center.second, 1e-9)

        val hcmcCluster = clusters.first { it.points.contains(hcmcBenThanh) }
        assertEquals(1, hcmcCluster.points.size)
        assertEquals(hcmcBenThanh.first, hcmcCluster.center.first, 1e-9)
        assertEquals(hcmcBenThanh.second, hcmcCluster.center.second, 1e-9)
    }

    @Test
    fun testClusterCoordinates_returnsClusterCenters() {
        val p1 = Pair(21.0285, 105.8542)
        val p2 = Pair(21.0245, 105.8575)
        val p3 = Pair(16.0678, 108.2208)

        val centers = DistanceCalculator.clusterCoordinates(listOf(p1, p2, p3), maxDistanceKm = 15.0)
        assertEquals(2, centers.size)

        val c1 = centers[0]
        assertEquals((p1.first + p2.first) / 2.0, c1.first, 1e-9)
        assertEquals((p1.second + p2.second) / 2.0, c1.second, 1e-9)

        val c2 = centers[1]
        assertEquals(p3.first, c2.first, 1e-9)
        assertEquals(p3.second, c2.second, 1e-9)
    }

    @Test
    fun testClusterPoints_thresholdSplitting() {
        // Two points separated by ~22 km (> 15 km threshold)
        val hoanKiem = Pair(21.0285, 105.8542)
        val noiBai = Pair(21.2187, 105.8042)

        val clustersDefault = DistanceCalculator.clusterPoints(listOf(hoanKiem, noiBai), maxDistanceKm = 15.0)
        assertEquals(2, clustersDefault.size)

        // When threshold is increased to 30 km, they should merge into 1 cluster
        val clustersLarge = DistanceCalculator.clusterPoints(listOf(hoanKiem, noiBai), maxDistanceKm = 30.0)
        assertEquals(1, clustersLarge.size)
        assertEquals(2, clustersLarge[0].points.size)
        assertEquals((hoanKiem.first + noiBai.first) / 2.0, clustersLarge[0].center.first, 1e-9)
        assertEquals((hoanKiem.second + noiBai.second) / 2.0, clustersLarge[0].center.second, 1e-9)
    }
}
