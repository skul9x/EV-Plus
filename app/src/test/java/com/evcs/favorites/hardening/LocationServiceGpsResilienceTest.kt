package com.evcs.favorites.hardening

import android.location.Location
import com.evcs.favorites.domain.location.LocationService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationServiceGpsResilienceTest {

    private fun createMockLocation(
        lat: Double,
        lon: Double,
        provider: String = "gps"
    ): Location {
        return object : Location(provider) {
            override fun getLatitude(): Double = lat
            override fun getLongitude(): Double = lon
            override fun getProvider(): String = provider
        }
    }

    @Test
    fun testIsLocationEnabled_DefaultsToTrue_WhenContextIsNull() {
        val service = LocationService()
        assertTrue("isLocationEnabled should default to true when context is null", service.isLocationEnabled())
    }

    @Test
    fun testGetFreshLocation_FallbackToFrameworkLocationManager_WhenFusedClientIsNull() = runTest {
        val expectedLocation = createMockLocation(21.0285, 105.8542)

        // Subclass simulating environment where FusedLocationClient is absent (e.g. AOSP head unit)
        // and framework LocationManager returns the hardware GPS fix
        val service = object : LocationService(
            context = null,
            fusedLocationClient = null,
            locationProvider = null
        ) {
            override fun hasLocationPermission(): Boolean = true
            override fun getLastKnownLocationFromFramework(): Location? = expectedLocation
        }

        val resolved = service.getFreshLocation()

        assertNotNull("Should fallback to hardware LocationManager when Fused client is null", resolved)
        assertEquals(21.0285, resolved!!.latitude, 0.0001)
        assertEquals(105.8542, resolved.longitude, 0.0001)
        assertEquals(21.0285, service.latestLatitude!!, 0.0001)
        assertEquals(105.8542, service.latestLongitude!!, 0.0001)
    }

    @Test
    fun testGetFreshLocation_ReturnsNull_WhenPermissionNotGranted() = runTest {
        val service = object : LocationService() {
            override fun hasLocationPermission(): Boolean = false
        }

        val result = service.getFreshLocation()
        assertNull("getFreshLocation must return null immediately when permissions are absent", result)
    }

    @Test
    fun testGetFreshLocation_CustomProviderUpdatesLocationState() = runTest {
        val mockLoc = createMockLocation(10.7769, 106.7009)
        val service = LocationService(
            locationProvider = { mockLoc }
        )

        assertTrue(service.hasLocationPermission())
        val result = service.getFreshLocation()

        assertNotNull(result)
        assertEquals(10.7769, result!!.latitude, 0.0001)
        assertEquals(106.7009, result.longitude, 0.0001)
        assertNotNull(service.locationState.value)
        assertEquals(10.7769, service.locationState.value!!.latitude, 0.0001)
        assertEquals(106.7009, service.locationState.value!!.longitude, 0.0001)
        assertEquals(10.7769, service.latestLatitude!!, 0.0001)
        assertEquals(106.7009, service.latestLongitude!!, 0.0001)
    }

    @Test
    fun testSetLocation_UpdatesLocationStateAndCoordinatesDirectly() {
        val service = LocationService()
        assertNull(service.locationState.value)
        assertNull(service.latestCoordinates)

        val loc = createMockLocation(16.0544, 108.2022, "simulated")
        service.setLocation(loc)

        assertNotNull(service.locationState.value)
        assertEquals(16.0544, service.locationState.value!!.latitude, 0.0001)
        assertEquals(108.2022, service.locationState.value!!.longitude, 0.0001)
        assertEquals(16.0544, service.latestLatitude!!, 0.0001)
        assertEquals(108.2022, service.latestLongitude!!, 0.0001)
    }
}
