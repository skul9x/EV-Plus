package com.evcs.favorites.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.evcs.favorites.focus.FocusModeForegroundService
import com.evcs.favorites.focus.FocusServiceIntentSpec

/**
 * Automotive session managing the lifecycle of EV-Plus in-vehicle display.
 * Initializes the root automotive screen upon connection to the car head unit.
 * Automatically halts [FocusModeForegroundService] upon vehicle disconnection (ON_DESTROY)
 * to terminate background mobile telemetry and prevent battery drain.
 */
open class EvPlusCarSession : Session() {

    companion object {
        internal var testServiceStopper: ((Intent) -> Unit)? = null
        internal var testServiceSpecStopper: ((FocusServiceIntentSpec) -> Unit)? = null

        fun resetTestStopper() {
            testServiceStopper = null
            testServiceSpecStopper = null
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                stopFocusModeService()
            }
        })
    }

    internal fun stopFocusModeService() {
        val spec = FocusModeForegroundService.getStopIntentSpec()
        testServiceSpecStopper?.invoke(spec)

        try {
            val context: CarContext? = try { carContext } catch (_: Throwable) { null }
            if (context != null) {
                val stopIntent = FocusModeForegroundService.createStopIntent(context)
                if (testServiceStopper != null) {
                    testServiceStopper?.invoke(stopIntent)
                } else {
                    context.startService(stopIntent)
                }
            } else {
                if (testServiceStopper != null) {
                    testServiceStopper?.invoke(Intent(spec.action))
                }
            }
        } catch (_: Throwable) {
            // carContext may be detached or null in edge test environments
        }
    }

    override fun onCreateScreen(intent: Intent): Screen {
        return MainCarScreen(carContext)
    }
}
