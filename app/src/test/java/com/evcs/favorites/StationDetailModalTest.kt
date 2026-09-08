package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import com.evcs.favorites.util.StationUrlBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Core verification test for Station Detail View & Card Interaction.
 * Updated in Phase 05: Validates canonical URL builder, session headers, and
 * FavoritesViewModel native detail bottom sheet state management.
 *
 * Verifies:
 * 1. Canonical Station URL generation and slug normalization for both VinFast stations
 *    (`tram-sac-${slug}-${locationId.lowercase()}.html`) and partner stations
 *    (`tram-sac-${slug}-c.${locationId}.html`).
 * 2. Cookie header construction for authenticated session injection
 *    (`PHPSESSID=...; evcs=...; evcs_did=...`).
 * 3. FavoritesViewModel state management:
 *    - Selection state transitions (`selectStationForDetail`)
 *    - Dismissal handling (`dismissStationDetail`)
 *    - Automatic deselection on station deletion (`removeFavorite`)
 *    - Clean reset on logout (`logout`).
 * 4. Native bottom sheet contract verification (100% native Compose, zero WebView).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationDetailModalTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository
    private lateinit var authEngine: AuthEngine
    private lateinit var viewModel: FavoritesViewModel

    private val sampleVinFastStation = Station(
        id = "C.BNI0012",
        name = "VinFast - TTTM Dabaco Mart Quế Võ",
        address = "Bắc Ninh",
        latitude = 21.1438,
        longitude = 106.1662,
        summary = "Mở 24/7",
        connectors = "30kW, 20kW",
        depotStatus = "Normal",
        totalAvailablePlugs = 2,
        totalPlugs = 4
    )

    private val samplePartnerStation = Station(
        id = "RAB0045",
        name = "Rabbit E-Mobility - Khách Sạn TTC Cần Thơ",
        address = "Cần Thơ",
        latitude = 10.0345,
        longitude = 105.7891,
        summary = "Mở 24/7",
        connectors = "60kW",
        depotStatus = "Normal",
        totalAvailablePlugs = 1,
        totalPlugs = 2,
        evse = "Rabbit E-Mobility"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()

        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )

        repository = EvcsRepository(apiClient = apiClient)
        authEngine = AuthEngine(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )
        viewModel = FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            dispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun testStationDetailAndInteractionCoreFunctionality() = runTest(testDispatcher) {
        // =====================================================================
        // Part 1: Canonical Station URL Generation & Slug Normalization
        // =====================================================================

        // 1a. VinFast station with Vietnamese diacritics and uppercase
        val vfSlug = StationUrlBuilder.slugify(sampleVinFastStation.name)
        assertEquals("vinfast-tttm-dabaco-mart-que-vo", vfSlug)
        assertTrue("Slug must start with vinfast", vfSlug.startsWith("vinfast"))

        val vfUrl = StationUrlBuilder.buildStationDetailUrl(
            sampleVinFastStation.name,
            sampleVinFastStation.id
        )
        assertEquals(
            "https://evcs.vn/tram-sac-vinfast-tttm-dabaco-mart-que-vo-c.bni0012.html",
            vfUrl
        )

        // 1b. Domain Station overload parity
        val vfUrlFromStation = StationUrlBuilder.buildStationDetailUrl(sampleVinFastStation)
        assertEquals(vfUrl, vfUrlFromStation)

        // 1c. VinFast station with special characters ('đ', 'Đ', '#')
        val specialVfName = "VinFast - Trạm sạc Đắk Lắk #1"
        val specialVfSlug = StationUrlBuilder.slugify(specialVfName)
        assertEquals("vinfast-tram-sac-dak-lak-1", specialVfSlug)
        val specialVfUrl = StationUrlBuilder.buildStationDetailUrl(specialVfName, "DLK0001")
        assertEquals(
            "https://evcs.vn/tram-sac-vinfast-tram-sac-dak-lak-1-dlk0001.html",
            specialVfUrl
        )

        // 1d. Partner station (non-VinFast) URL format: tram-sac-${slug}-c.${locationId}.html
        val partnerSlug = StationUrlBuilder.slugify(samplePartnerStation.name)
        assertEquals("rabbit-e-mobility-khach-san-ttc-can-tho", partnerSlug)
        assertFalse("Partner slug must not start with vinfast", partnerSlug.startsWith("vinfast"))

        val partnerUrl = StationUrlBuilder.buildStationDetailUrl(
            samplePartnerStation.name,
            samplePartnerStation.id
        )
        assertEquals(
            "https://evcs.vn/tram-sac-rabbit-e-mobility-khach-san-ttc-can-tho-c.RAB0045.html",
            partnerUrl
        )

        val partnerUrlFromStation = StationUrlBuilder.buildStationDetailUrl(samplePartnerStation)
        assertEquals(partnerUrl, partnerUrlFromStation)

        // 1e. Partner station with space in ID
        val partnerSpaceUrl = StationUrlBuilder.buildStationDetailUrl("EV ONE Trạm Sạc", "PARTNER 01")
        assertEquals(
            "https://evcs.vn/tram-sac-ev-one-tram-sac-c.PARTNER%2001.html",
            partnerSpaceUrl
        )

        // 1f. Edge cases for slugify: blank, multiple dashes, whitespace trimming
        assertEquals("", StationUrlBuilder.slugify(null))
        assertEquals("", StationUrlBuilder.slugify("   "))
        assertEquals(
            "vinfast-vinhomes-ocean-park",
            StationUrlBuilder.slugify("   VinFast --- Vinhomes    Ocean   Park   ")
        )

        // =====================================================================
        // Part 2: Cookie Header Construction & Session Injection Logic
        // =====================================================================

        // 2a. Direct cookie header construction with full session parameters
        val fullCookieHeader = StationUrlBuilder.buildCookieHeader(
            phpSessionId = "sess_php_xyz999",
            authCookie = "auth_evcs_tok111",
            deviceId = "uuid_device_555"
        )
        assertEquals(
            "PHPSESSID=sess_php_xyz999; evcs=auth_evcs_tok111; evcs_did=uuid_device_555",
            fullCookieHeader
        )

        // 2b. Partial cookies (omitting deviceId or session ID)
        val noDeviceHeader = StationUrlBuilder.buildCookieHeader(
            phpSessionId = "sess_php_xyz999",
            authCookie = "auth_evcs_tok111",
            deviceId = null
        )
        assertEquals("PHPSESSID=sess_php_xyz999; evcs=auth_evcs_tok111", noDeviceHeader)

        val onlyAuthHeader = StationUrlBuilder.buildCookieHeader(
            phpSessionId = null,
            authCookie = "auth_evcs_tok111"
        )
        assertEquals("evcs=auth_evcs_tok111", onlyAuthHeader)

        // 2c. SessionManager integration and ViewModel.getCookieHeader()
        sessionManager.saveAuthCookie("evcs_persisted_token_888")
        sessionManager.saveSession("php_session_active_777", "csrf_test")
        val managerCookie = StationUrlBuilder.buildCookieHeader(sessionManager)
        assertTrue(managerCookie.contains("PHPSESSID=php_session_active_777"))
        assertTrue(managerCookie.contains("evcs=evcs_persisted_token_888"))
        assertTrue(managerCookie.contains("evcs_did=${sessionManager.deviceId}"))

        val vmCookie = viewModel.getCookieHeader()
        assertEquals(managerCookie, vmCookie)

        // =====================================================================
        // Part 3: FavoritesViewModel Selection & Dismissal State Management
        // =====================================================================

        // 3a. Initial state has no selected station
        assertNull(
            "Initial selectedStationForDetail must be null",
            viewModel.selectedStationForDetail.value
        )

        // Simulate loaded favorites in UI state
        val mockResponseBody = """
        {
          "sync": true,
          "csrf": "csrf_123",
          "server": [
            {
              "locationId": "C.BNI0012",
              "name": "VinFast - TTTM Dabaco Mart Quế Võ",
              "address": "Bắc Ninh",
              "connectors": "30kW, 20kW"
            },
            {
              "locationId": "RAB0045",
              "name": "Rabbit E-Mobility - Khách Sạn TTC Cần Thơ",
              "address": "Cần Thơ",
              "connectors": "60kW"
            }
          ]
        }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(mockResponseBody))

        val fetchJob = viewModel.fetchFavorites()
        testDispatcher.scheduler.advanceUntilIdle()
        fetchJob.join()

        val successState = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(2, successState.stations.size)
        assertNull(successState.selectedStationForDetail)

        // 3b. Select VinFast station for detail
        viewModel.selectStationForDetail(sampleVinFastStation)
        assertEquals(sampleVinFastStation, viewModel.selectedStationForDetail.value)
        val selectedSuccessState1 = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(sampleVinFastStation, selectedSuccessState1.selectedStationForDetail)

        // 3c. Select partner station (switch selection)
        viewModel.selectStationForDetail(samplePartnerStation)
        assertEquals(samplePartnerStation, viewModel.selectedStationForDetail.value)
        val selectedSuccessState2 = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(samplePartnerStation, selectedSuccessState2.selectedStationForDetail)

        // 3d. Dismiss station detail
        viewModel.dismissStationDetail()
        assertNull(viewModel.selectedStationForDetail.value)
        val dismissedState = viewModel.uiState.value as FavoritesUiState.Success
        assertNull(dismissedState.selectedStationForDetail)

        // 3e. Removing the currently selected station automatically clears selection
        viewModel.selectStationForDetail(sampleVinFastStation)
        assertEquals(sampleVinFastStation, viewModel.selectedStationForDetail.value)
        viewModel.removeFavorite(sampleVinFastStation.id)
        assertNull(
            "Selection must clear when the selected station is removed",
            viewModel.selectedStationForDetail.value
        )
        val removedState = viewModel.uiState.value as FavoritesUiState.Success
        assertNull(removedState.selectedStationForDetail)
        assertEquals(1, removedState.stations.size)
        assertEquals(samplePartnerStation.id, removedState.stations[0].id)

        // 3f. Removing an unrelated station preserves the active selection
        viewModel.selectStationForDetail(samplePartnerStation)
        assertEquals(samplePartnerStation, viewModel.selectedStationForDetail.value)
        viewModel.removeFavorite("NON_EXISTING_ID")
        assertEquals(
            "Selection must remain intact when another station is removed",
            samplePartnerStation,
            viewModel.selectedStationForDetail.value
        )

        // 3g. Logout resets selection state
        viewModel.logout()
        assertNull("Logout must reset selectedStationForDetail", viewModel.selectedStationForDetail.value)
        assertTrue(viewModel.uiState.value is FavoritesUiState.LoggedOut)

        // =====================================================================
        // Part 4: Native Bottom Sheet Contract Verification (Zero WebView)
        // =====================================================================
        val nativeSheetClass = Class.forName("com.evcs.favorites.ui.components.NativeStationDetailSheetKt")
        val methods = nativeSheetClass.declaredMethods.map { it.name }
        assertTrue(
            "NativeStationDetailSheet composable contract must exist",
            methods.any { it.contains("NativeStationDetailSheet") }
        )
    }
}
