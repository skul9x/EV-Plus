package com.evcs.favorites.di

import android.content.Context
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthService
import com.evcs.favorites.data.auth.EncryptedSharedPrefsStorage
import com.evcs.favorites.data.auth.FirebaseAuthManager
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.PlainSharedPrefsStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.here.HereEvApiClient
import com.evcs.favorites.data.network.here.HereOAuthManager
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.repository.FirestoreFavoritesDataSource
import com.evcs.favorites.data.repository.FirestoreFavoritesRepository
import com.evcs.favorites.focus.EvcsStationNameResolver
import com.evcs.favorites.focus.FocusModeTelemetryEngine
import kotlinx.coroutines.CoroutineScope

/**
 * Dependency injection container managing singleton service and client instances
 * for EV-Plus, including live telemetry network clients and Focus Mode engine.
 */
interface AppContainer {
    val hereOAuthManager: HereOAuthManager
    val hereEvApiClient: HereEvApiClient
    val evcsApiClient: EvcsApiClient
    val evcsStationNameResolver: EvcsStationNameResolver
    val sessionManager: SessionManager
    val evcsRepository: EvcsRepository
    val authService: AuthService
    val firestoreFavoritesRepository: FirestoreFavoritesRepository

    fun createFocusModeTelemetryEngine(
        initialStation: Station,
        locationProvider: (() -> Pair<Double, Double>?)? = null,
        coroutineScope: CoroutineScope? = null
    ): FocusModeTelemetryEngine
}

/**
 * Default implementation of [AppContainer] managing lazy singletons.
 */
class DefaultAppContainer(private val context: Context? = null) : AppContainer {

    override val hereOAuthManager: HereOAuthManager by lazy {
        HereOAuthManager()
    }

    override val hereEvApiClient: HereEvApiClient by lazy {
        HereEvApiClient(oauthManager = hereOAuthManager)
    }

    override val sessionManager: SessionManager by lazy {
        context?.let { SessionManager.create(it) }
            ?: SessionManager(InMemorySessionStorage())
    }

    override val evcsApiClient: EvcsApiClient by lazy {
        EvcsApiClient(sessionManager = sessionManager)
    }

    override val evcsStationNameResolver: EvcsStationNameResolver by lazy {
        EvcsStationNameResolver(apiClient = evcsApiClient)
    }

    override val authService: AuthService by lazy {
        FirebaseAuthManager()
    }

    override val firestoreFavoritesRepository: FirestoreFavoritesRepository by lazy {
        val firestoreDataSource = FirestoreFavoritesDataSource.create()
        val localStorage = context?.let { PlainSharedPrefsStorage.getInstance(it) }
        FirestoreFavoritesRepository(
            remoteDataSource = firestoreDataSource,
            localStorage = localStorage,
            authService = authService
        )
    }

    override val evcsRepository: EvcsRepository by lazy {
        val cacheStorage = context?.let { PlainSharedPrefsStorage.getInstance(it) }
        val legacyStorage = context?.let { EncryptedSharedPrefsStorage.getInstance(it) }
        EvcsRepository(
            apiClient = evcsApiClient,
            cacheStorage = cacheStorage,
            legacyStorage = legacyStorage,
            autoResolveCoordinates = true,
            firestoreFavoritesRepository = firestoreFavoritesRepository
        )
    }

    override fun createFocusModeTelemetryEngine(
        initialStation: Station,
        locationProvider: (() -> Pair<Double, Double>?)?,
        coroutineScope: CoroutineScope?
    ): FocusModeTelemetryEngine {
        return FocusModeTelemetryEngine(
            initialStation = initialStation,
            evcsApiClient = evcsApiClient,
            locationProvider = locationProvider,
            coroutineScope = coroutineScope,
            stationNameResolver = evcsStationNameResolver
        )
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun getInstance(context: Context? = null): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: DefaultAppContainer(context?.applicationContext).also { instance = it }
            }
        }

        fun setInstanceForTesting(container: AppContainer?) {
            instance = container
        }
    }
}
