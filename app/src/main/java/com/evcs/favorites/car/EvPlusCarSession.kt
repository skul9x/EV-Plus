package com.evcs.favorites.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session


/**
 * Automotive session managing the lifecycle of EV-Plus in-vehicle display.
 * Initializes the root automotive screen upon connection to the car head unit.
 */
open class EvPlusCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return MainCarScreen(carContext)
    }
}
