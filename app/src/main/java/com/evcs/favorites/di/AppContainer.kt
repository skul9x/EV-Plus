package com.evcs.favorites.di

import android.content.Context
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.here.HereEvApiClient
import com.evcs.favorites.data.network.here.HereOAuthManager
import com.evcs.favorites.focus.FocusModeTelemetryEngine
import kotlinx.coroutines.CoroutineScope

/**
 * Dependency injection container managing singleton service and client instances
 * for EV-Plus, including live telemetry network clients and Focus Mode engine.
 */
interface AppContainer {
    val hereOAuthManager: HereOAuthManager
    val hereEvApiClient: HereEvApiClient

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

    override fun createFocusModeTelemetryEngine(
        initialStation: Station,
        locationProvider: (() -> Pair<Double, Double>?)?,
        coroutineScope: CoroutineScope?
    ): FocusModeTelemetryEngine {
        return FocusModeTelemetryEngine(
            initialStation = initialStation,
            hereEvApiClient = hereEvApiClient,
            locationProvider = locationProvider,
            coroutineScope = coroutineScope
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
