package com.evcs.favorites.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point service for Android Auto projection and Desktop Head Unit (DHU).
 * Discovered and bound by the Android Auto host via [CarServiceConfig.CAR_APP_SERVICE_ACTION].
 */
class EvPlusCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return CarServiceConfig.createHostValidator(applicationContext)
    }

    override fun onCreateSession(): Session {
        return EvPlusCarSession()
    }
}
