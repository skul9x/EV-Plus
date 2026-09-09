package com.evcs.favorites.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.evcs.favorites.data.model.Station

/**
 * Automotive station detail screen built with [PaneTemplate].
 * Adheres strictly to Car App Library driver distraction constraints:
 * - Constraint 1: Maximum 4 content rows (throws IllegalArgumentException if exceeded).
 * - Constraint 2: Maximum 2 pane actions.
 * - Adaptive header compatibility: Uses Header for API Level 7+, falls back to setHeaderAction for API Level 1-6.
 */
class StationDetailCarScreen(
    carContext: CarContext,
    val station: Station,
    private val onNavigateAction: ((Station) -> Unit)? = null
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val paneSpec = CarStationFormatter.buildPaneSpec(station)
        val paneBuilder = Pane.Builder()

        // Build content rows (strictly <= 4 rows)
        for (rowSpec in paneSpec.rows.take(CarPaneSpec.MAX_PANE_ROWS)) {
            val rowBuilder = Row.Builder()
                .setTitle(rowSpec.title)
            rowSpec.subtitle?.let { rowBuilder.addText(it) }
            paneBuilder.addRow(rowBuilder.build())
        }

        // Primary Action: "⚡ DẪN ĐƯỜNG & THEO DÕI" with CarColor.GREEN
        val primaryAction = Action.Builder()
            .setTitle(paneSpec.primaryActionTitle)
            .setBackgroundColor(CarColor.GREEN)
            .setOnClickListener {
                if (onNavigateAction != null) {
                    onNavigateAction.invoke(station)
                } else {
                    dispatchNavigation(station)
                }
            }
            .build()
        paneBuilder.addAction(primaryAction)

        val pane = paneBuilder.build()
        val templateBuilder = PaneTemplate.Builder(pane)
        val title = paneSpec.title

        // Adaptive Header Action for Car App API Level compatibility
        if (effectiveCarApiLevel >= 7) {
            val header = Header.Builder()
                .setStartHeaderAction(Action.BACK)
                .setTitle(title)
                .build()
            templateBuilder.setHeader(header)
        } else {
            @Suppress("DEPRECATION")
            templateBuilder
                .setHeaderAction(Action.BACK)
                .setTitle(title)
        }

        return templateBuilder.build()
    }

    private val effectiveCarApiLevel: Int
        get() = try {
            carContext.carAppApiLevel
        } catch (_: IllegalStateException) {
            CarServiceConfig.MIN_CAR_API_LEVEL
        }

    fun dispatchNavigation(station: Station) {
        if (onNavigateAction != null) {
            onNavigateAction.invoke(station)
        } else {
            CarNavigationDispatcher.startNavigation(carContext, station)
        }
    }
}
