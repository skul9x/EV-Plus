package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Single Comprehensive Verification Test for Phase 04: Jetpack Compose UI & ViewModel.
 *
 * Validates:
 * 1. Initial UI state is `LoggedOut` when unauthenticated, and transitions to `RequestingOtp`
 *    upon calling `requestOtp(email)`, and then to `Success` upon successful `verifyOtp(otpCode)`.
 * 2. ViewModel initialization with an existing logged-in session immediately fetches favorites.
 * 3. `fetchFavorites` data loading, live plug enrichment, and state emission to Compose UI.
 * 4. Error handling when network or authentication operations fail (`FavoritesUiState.Error`).
 * 5. Pull-to-refresh (`refresh()`) background loading without discarding existing state.
 * 6. Local favorite deletion (`removeFavorite`) updating UI state reactively.
 * 7. User logout (`logout()`) resetting session credentials and transitioning state back to `LoggedOut`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var baseUrl: String
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository
    private lateinit var authEngine: AuthEngine

    private val sampleFavoritesJson = """
    {
      "sync": true,
      "csrf": "csrf_token_fav_123",
      "server": [
        {
          "locationId": "C.BNI0012",
          "name": "VinFast - TTTM Dabaco Mart Quế Võ",
          "address": "Bãi đỗ xe ngoài trời, thị trấn Phố Mới, Bắc Ninh",
          "summary": "Mở 24/7 • Công cộng • Gửi xe tính phí",
          "connectors": "60kW, 30kW",
          "image": "https://media.evcs.vn/dabaco.jpg"
        },
        {
          "locationId": "C.HNI0099",
          "name": "VinFast - Royal City",
          "address": "72A Nguyễn Trãi, Thanh Xuân, Hà Nội",
          "summary": "Mở 24/7 • Miễn phí đỗ xe",
          "connectors": "120kW, 60kW",
          "image": null
        }
      ],
      "limits": {
        "fav": 10,
        "notifyFree": 0
      }
    }
    """.trimIndent()

    private val sampleSearchJson = """
    {
      "code": 200000,
      "data": [
        {
          "locationId": "C.BNI0012",
          "stationName": "VinFast - TTTM Dabaco Mart Quế Võ",
          "stationAddress": "Bãi đỗ xe ngoài trời, thị trấn Phố Mới, Bắc Ninh",
          "latitude": 21.1452,
          "longitude": 106.1553,
          "depotStatus": "Normal",
          "evsePowers": [
            {
              "type": 60000,
              "numberOfAvailableEvse": 2,
              "totalEvse": 4
            },
            {
              "type": 30000,
              "numberOfAvailableEvse": 1,
              "totalEvse": 2
            }
          ],
          "isPublic": true,
          "isFreeParking": false,
          "workingTimeDescription": "24/7"
        }
      ]
    }
    """.trimIndent()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        baseUrl = mockServer.url("/").toString().removeSuffix("/")

        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )

        repository = EvcsRepository(apiClient)

        authEngine = AuthEngine(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        Dispatchers.resetMain()
    }

    private fun createViewModel(customAuthEngine: AuthEngine = authEngine): FavoritesViewModel {
        return FavoritesViewModel(
            repository = repository,
            authEngine = customAuthEngine,
            locationService = null,
            dispatcher = testDispatcher
        )
    }

    // =========================================================================
    // 1. Initial State & Transition to RequestingOtp and Success upon Login
    // =========================================================================

    @Test
    fun testInitialStateAndLoginFlowTransitionsToRequestingOtpAndSuccess() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        // 1. Verify initial state is LoggedOut
        assertEquals(FavoritesUiState.LoggedOut, viewModel.uiState.value)

        // Mock fetchCsrfToken & sendOtp responses
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "PHPSESSID=session_init; path=/")
                .setBody("""<script>window.EVCS_REWARD={csrf:"csrf_test_abc"};</script>""")
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok": true, "resend_in": 60}""")
        )

        // 2. Trigger requestOtp -> immediate transition to RequestingOtp
        val requestJob = viewModel.requestOtp("skul9x@gmail.com")
        assertEquals(FavoritesUiState.RequestingOtp, viewModel.uiState.value)
        assertEquals("skul9x@gmail.com", viewModel.email)

        requestJob.join()
        assertEquals(FavoritesUiState.RequestingOtp, viewModel.uiState.value)

        // Mock verifyOtp response with 1-year auth cookie
        val oneYearCookie = "evcs=auth_cookie_token_123; expires=Fri, 03 Sep 2027 03:00:00 GMT; path=/;"
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", oneYearCookie)
                .setBody("""{"ok": true}""")
        )

        // Mock fetchFavorites response & search API enrichment
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleFavoritesJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleSearchJson))

        // 3. Trigger verifyOtp -> transitions via VerifyingOtp to Success
        val verifyJob = viewModel.verifyOtp("123456")
        assertEquals(FavoritesUiState.VerifyingOtp, viewModel.uiState.value)

        verifyJob.join()

        // 4. Verify final state is Success
        val finalState = viewModel.uiState.value
        assertTrue("State must be Success after successful login", finalState is FavoritesUiState.Success)

        val successState = finalState as FavoritesUiState.Success
        assertEquals(2, successState.stations.size)
        assertFalse(successState.isRefreshing)

        val enriched = successState.stations.first { it.id == "C.BNI0012" }
        assertEquals("VinFast - TTTM Dabaco Mart Quế Võ", enriched.name)
        assertEquals(21.1452, enriched.latitude, 0.001)
        assertEquals(3, enriched.totalAvailablePlugs)
        assertEquals(6, enriched.totalPlugs)
    }

    // =========================================================================
    // 2. ViewModel Initialization with Existing Session
    // =========================================================================

    @Test
    fun testInitWithExistingSessionImmediatelyFetchesFavorites() = runTest(testDispatcher) {
        // Pre-populate session before creating AuthEngine
        sessionManager.authCookie = "existing_valid_session"
        sessionManager.phpSessionId = "existing_php"
        sessionManager.csrfToken = "existing_csrf"

        val authWithSession = AuthEngine(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )
        assertTrue(authWithSession.isLoggedIn.value)

        // Mock responses for auto-fetch
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleFavoritesJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleSearchJson))

        val viewModel = createViewModel(customAuthEngine = authWithSession)

        // Initial state is Loading
        assertEquals(FavoritesUiState.Loading, viewModel.uiState.value)

        viewModel.initialLoadJob?.join()

        // Emits Success
        val state = viewModel.uiState.value
        assertTrue(state is FavoritesUiState.Success)
        assertEquals(2, (state as FavoritesUiState.Success).stations.size)
    }

    // =========================================================================
    // 3. fetchFavorites Data Loading & Error Handling
    // =========================================================================

    @Test
    fun testFetchFavoritesHandlesNetworkErrorGracefully() = runTest(testDispatcher) {
        sessionManager.authCookie = "valid_cookie"
        val viewModel = createViewModel()

        // Mock 500 server error
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val job = viewModel.fetchFavorites()
        assertEquals(FavoritesUiState.Loading, viewModel.uiState.value)

        job.join()

        // Emits Error state with non-empty error message
        val state = viewModel.uiState.value
        assertTrue("State must be Error when network request fails", state is FavoritesUiState.Error)
        val errorState = state as FavoritesUiState.Error
        assertTrue(errorState.message.isNotEmpty())
    }

    @Test
    fun testVerifyOtpFailureTransitionsToError() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "PHPSESSID=session_init; path=/")
                .setBody("""<script>window.EVCS_REWARD={csrf:"csrf_test_abc"};</script>""")
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok": true, "resend_in": 60}""")
        )

        viewModel.requestOtp("user@evcs.vn").join()

        // Mock verify_otp failure (invalid OTP)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok": false, "error": "Mã xác thực không chính xác"}""")
        )

        val verifyJob = viewModel.verifyOtp("000000")
        assertEquals(FavoritesUiState.VerifyingOtp, viewModel.uiState.value)

        verifyJob.join()

        val state = viewModel.uiState.value
        assertTrue("State must be Error upon invalid OTP", state is FavoritesUiState.Error)
        assertEquals("Mã xác thực không chính xác", (state as FavoritesUiState.Error).message)
    }

    // =========================================================================
    // 4. Background Refresh Flow
    // =========================================================================

    @Test
    fun testRefreshUpdatesDataWithoutClearingCurrentList() = runTest(testDispatcher) {
        sessionManager.authCookie = "valid_cookie"
        val viewModel = createViewModel()

        // Initial fetch
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleFavoritesJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleSearchJson))
        viewModel.fetchFavorites().join()

        val initialSuccess = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(2, initialSuccess.stations.size)
        assertFalse(initialSuccess.isRefreshing)

        // Queue responses for refresh
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleFavoritesJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleSearchJson))

        // Trigger refresh
        val refreshJob = viewModel.refresh()

        // State retains stations with isRefreshing = true
        val refreshingState = viewModel.uiState.value
        assertTrue(refreshingState is FavoritesUiState.Success)
        assertTrue((refreshingState as FavoritesUiState.Success).isRefreshing)
        assertEquals(2, refreshingState.stations.size)

        refreshJob.join()

        // Refresh completes with isRefreshing = false
        val refreshedState = viewModel.uiState.value as FavoritesUiState.Success
        assertFalse(refreshedState.isRefreshing)
        assertEquals(2, refreshedState.stations.size)
    }

    // =========================================================================
    // 5. Local Favorite Removal
    // =========================================================================

    @Test
    fun testRemoveFavoriteUpdatesUiState() = runTest(testDispatcher) {
        sessionManager.authCookie = "valid_cookie"
        val viewModel = createViewModel()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleFavoritesJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleSearchJson))
        viewModel.fetchFavorites().join()

        assertEquals(2, (viewModel.uiState.value as FavoritesUiState.Success).stations.size)

        // Remove station "C.BNI0012"
        viewModel.removeFavorite("C.BNI0012")

        val stateAfterRemoval = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(1, stateAfterRemoval.stations.size)
        assertEquals("C.HNI0099", stateAfterRemoval.stations[0].id)
    }

    // =========================================================================
    // 6. User Logout Resetting State to LoggedOut
    // =========================================================================

    @Test
    fun testUserLogoutResetsSessionAndStateBackToLoggedOut() = runTest(testDispatcher) {
        sessionManager.authCookie = "valid_cookie"
        val viewModel = createViewModel()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleFavoritesJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleSearchJson))
        viewModel.fetchFavorites().join()

        assertTrue(viewModel.uiState.value is FavoritesUiState.Success)

        // Call logout
        viewModel.logout()

        // UI state must immediately reset to LoggedOut
        assertEquals(FavoritesUiState.LoggedOut, viewModel.uiState.value)
        assertFalse(authEngine.isLoggedIn.value)
        assertFalse(sessionManager.hasAuthCookie())
        assertEquals("", viewModel.email)
    }
}
