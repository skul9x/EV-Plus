package com.evcs.favorites.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight, centralized icon registry replacing monolithic material-icons-extended.
 *
 * Exposes custom vector definitions and core Material icons without importing thousands
 * of unused icons into the APK DEX payload.
 */
object AppIcons {

    private fun buildIcon(
        name: String,
        autoMirror: Boolean = false,
        block: PathBuilder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror
    ).path(fill = SolidColor(Color.Black), pathBuilder = block).build()

    // Core icons re-exported for uniform access
    val AccountCircle: ImageVector get() = Icons.Default.AccountCircle
    val CheckCircle: ImageVector get() = Icons.Default.CheckCircle
    val Settings: ImageVector get() = Icons.Default.Settings

    // Outlined navigation icons
    val FavoriteBorder: ImageVector get() = Icons.Outlined.FavoriteBorder
    val LocationOn: ImageVector get() = Icons.Outlined.LocationOn
    val FavoriteBorderOutlined: ImageVector get() = FavoriteBorder
    val LocationOnOutlined: ImageVector get() = LocationOn

    object Outlined {
        val FavoriteBorder: ImageVector get() = AppIcons.FavoriteBorder
        val LocationOn: ImageVector get() = AppIcons.LocationOn
    }

    val AccessTime: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("AccessTime", autoMirror = false) {
            moveTo(11.99f, 2.0f)
            curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
            curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
            reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
            close()
            moveTo(12.0f, 20.0f)
            curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
            reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
            reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
            reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
            close()
            moveTo(12.5f, 7.0f)
            horizontalLineTo(11.0f)
            verticalLineToRelative(6.0f)
            lineToRelative(5.25f, 3.15f)
            lineToRelative(0.75f, -1.23f)
            lineToRelative(-4.5f, -2.67f)
            close()
        }
    }

    val Bolt: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("Bolt", autoMirror = false) {
            moveTo(11.0f, 21.0f)
            horizontalLineToRelative(-1.0f)
            lineToRelative(1.0f, -7.0f)
            horizontalLineTo(7.5f)
            curveToRelative(-0.58f, 0.0f, -0.57f, -0.32f, -0.38f, -0.66f)
            curveToRelative(0.19f, -0.34f, 0.05f, -0.08f, 0.07f, -0.12f)
            curveTo(8.48f, 10.94f, 10.42f, 7.54f, 13.0f, 3.0f)
            horizontalLineToRelative(1.0f)
            lineToRelative(-1.0f, 7.0f)
            horizontalLineToRelative(3.5f)
            curveToRelative(0.49f, 0.0f, 0.56f, 0.33f, 0.47f, 0.51f)
            lineToRelative(-0.07f, 0.15f)
            curveTo(12.96f, 17.55f, 11.0f, 21.0f, 11.0f, 21.0f)
            close()
        }
    }

    val CloudOff: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("CloudOff", autoMirror = false) {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
            curveToRelative(-1.48f, 0.0f, -2.85f, 0.43f, -4.01f, 1.17f)
            lineToRelative(1.46f, 1.46f)
            curveTo(10.21f, 6.23f, 11.08f, 6.0f, 12.0f, 6.0f)
            curveToRelative(3.04f, 0.0f, 5.5f, 2.46f, 5.5f, 5.5f)
            verticalLineToRelative(0.5f)
            horizontalLineTo(19.0f)
            curveToRelative(1.66f, 0.0f, 3.0f, 1.34f, 3.0f, 3.0f)
            curveToRelative(0.0f, 1.13f, -0.64f, 2.11f, -1.56f, 2.62f)
            lineToRelative(1.45f, 1.45f)
            curveTo(23.16f, 18.16f, 24.0f, 16.68f, 24.0f, 15.0f)
            curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
            close()
            moveTo(3.0f, 5.27f)
            lineToRelative(2.75f, 2.74f)
            curveTo(2.56f, 8.15f, 0.0f, 10.77f, 0.0f, 14.0f)
            curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
            horizontalLineToRelative(11.73f)
            lineToRelative(2.0f, 2.0f)
            lineTo(21.0f, 20.73f)
            lineTo(4.27f, 4.0f)
            lineTo(3.0f, 5.27f)
            close()
            moveTo(7.73f, 10.0f)
            lineToRelative(8.0f, 8.0f)
            horizontalLineTo(6.0f)
            curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
            reflectiveCurveToRelative(1.79f, -4.0f, 4.0f, -4.0f)
            horizontalLineToRelative(1.73f)
            close()
        }
    }

    val ContentCopy: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("ContentCopy", autoMirror = false) {
            moveTo(16.0f, 1.0f)
            lineTo(4.0f, 1.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            horizontalLineToRelative(2.0f)
            lineTo(4.0f, 3.0f)
            horizontalLineToRelative(12.0f)
            lineTo(16.0f, 1.0f)
            close()
            moveTo(19.0f, 5.0f)
            lineTo(8.0f, 5.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(11.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(21.0f, 7.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(19.0f, 21.0f)
            lineTo(8.0f, 21.0f)
            lineTo(8.0f, 7.0f)
            horizontalLineToRelative(11.0f)
            verticalLineToRelative(14.0f)
            close()
        }
    }

    val DeleteOutline: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("DeleteOutline", autoMirror = false) {
            moveTo(6.0f, 19.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(8.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(18.0f, 7.0f)
            lineTo(6.0f, 7.0f)
            verticalLineToRelative(12.0f)
            close()
            moveTo(8.0f, 9.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(10.0f)
            lineTo(8.0f, 19.0f)
            lineTo(8.0f, 9.0f)
            close()
            moveTo(15.5f, 4.0f)
            lineToRelative(-1.0f, -1.0f)
            horizontalLineToRelative(-5.0f)
            lineToRelative(-1.0f, 1.0f)
            lineTo(5.0f, 4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(14.0f)
            lineTo(19.0f, 4.0f)
            close()
        }
    }

    val DirectionsCar: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("DirectionsCar", autoMirror = false) {
            moveTo(18.92f, 6.01f)
            curveTo(18.72f, 5.42f, 18.16f, 5.0f, 17.5f, 5.0f)
            horizontalLineToRelative(-11.0f)
            curveToRelative(-0.66f, 0.0f, -1.21f, 0.42f, -1.42f, 1.01f)
            lineTo(3.0f, 12.0f)
            verticalLineToRelative(8.0f)
            curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
            horizontalLineToRelative(1.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
            verticalLineToRelative(-1.0f)
            horizontalLineToRelative(12.0f)
            verticalLineToRelative(1.0f)
            curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
            horizontalLineToRelative(1.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
            verticalLineToRelative(-8.0f)
            lineToRelative(-2.08f, -5.99f)
            close()
            moveTo(6.5f, 16.0f)
            curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
            reflectiveCurveTo(5.67f, 13.0f, 6.5f, 13.0f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
            reflectiveCurveTo(7.33f, 16.0f, 6.5f, 16.0f)
            close()
            moveTo(17.5f, 16.0f)
            curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
            reflectiveCurveToRelative(0.67f, -1.5f, 1.5f, -1.5f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
            reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
            close()
            moveTo(5.0f, 11.0f)
            lineToRelative(1.5f, -4.5f)
            horizontalLineToRelative(11.0f)
            lineTo(19.0f, 11.0f)
            lineTo(5.0f, 11.0f)
            close()
        }
    }

    val Error: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("Error", autoMirror = false) {
            moveTo(12.0f, 2.0f)
            curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
            reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
            reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(13.0f, 17.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(13.0f, 13.0f)
            horizontalLineToRelative(-2.0f)
            lineTo(11.0f, 7.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(6.0f)
            close()
        }
    }

    val ErrorOutline: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("ErrorOutline", autoMirror = false) {
            moveTo(11.0f, 15.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-2.0f)
            close()
            moveTo(11.0f, 7.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(-2.0f)
            close()
            moveTo(11.99f, 2.0f)
            curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
            curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
            reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
            close()
            moveTo(12.0f, 20.0f)
            curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
            reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
            reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
            reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
            close()
        }
    }

    val EvStation: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("EvStation", autoMirror = false) {
            moveTo(19.77f, 7.23f)
            lineToRelative(0.01f, -0.01f)
            lineToRelative(-3.72f, -3.72f)
            lineTo(15.0f, 4.56f)
            lineToRelative(2.11f, 2.11f)
            curveToRelative(-0.94f, 0.36f, -1.61f, 1.26f, -1.61f, 2.33f)
            curveToRelative(0.0f, 1.38f, 1.12f, 2.5f, 2.5f, 2.5f)
            curveToRelative(0.36f, 0.0f, 0.69f, -0.08f, 1.0f, -0.21f)
            verticalLineToRelative(7.21f)
            curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
            reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
            verticalLineTo(14.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            horizontalLineToRelative(-1.0f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            horizontalLineTo(6.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(16.0f)
            horizontalLineToRelative(10.0f)
            verticalLineToRelative(-7.5f)
            horizontalLineToRelative(1.5f)
            verticalLineToRelative(5.0f)
            curveToRelative(0.0f, 1.38f, 1.12f, 2.5f, 2.5f, 2.5f)
            reflectiveCurveToRelative(2.5f, -1.12f, 2.5f, -2.5f)
            verticalLineTo(9.0f)
            curveToRelative(0.0f, -0.69f, -0.28f, -1.32f, -0.73f, -1.77f)
            close()
            moveTo(18.0f, 10.0f)
            curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
            reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
            reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
            reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
            close()
            moveTo(8.0f, 18.0f)
            verticalLineToRelative(-4.5f)
            horizontalLineTo(6.0f)
            lineTo(10.0f, 6.0f)
            verticalLineToRelative(5.0f)
            horizontalLineToRelative(2.0f)
            lineToRelative(-4.0f, 7.0f)
            close()
        }
    }

    val ExpandLess: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("ExpandLess", autoMirror = false) {
            moveTo(12.0f, 8.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(1.41f, 1.41f)
            lineTo(12.0f, 10.83f)
            lineToRelative(4.59f, 4.58f)
            lineTo(18.0f, 14.0f)
            close()
        }
    }

    val ExpandMore: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("ExpandMore", autoMirror = false) {
            moveTo(16.59f, 8.59f)
            lineTo(12.0f, 13.17f)
            lineTo(7.41f, 8.59f)
            lineTo(6.0f, 10.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(6.0f, -6.0f)
            close()
        }
    }

    val FilterListOff: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("FilterListOff", autoMirror = false) {
            moveTo(10.83f, 8.0f)
            horizontalLineTo(21.0f)
            verticalLineTo(6.0f)
            horizontalLineTo(8.83f)
            lineTo(10.83f, 8.0f)
            close()
            moveTo(15.83f, 13.0f)
            horizontalLineTo(18.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-4.17f)
            lineTo(15.83f, 13.0f)
            close()
            moveTo(14.0f, 16.83f)
            verticalLineTo(18.0f)
            horizontalLineToRelative(-4.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(3.17f)
            lineToRelative(-3.0f, -3.0f)
            horizontalLineTo(6.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.17f)
            lineToRelative(-3.0f, -3.0f)
            horizontalLineTo(3.0f)
            verticalLineTo(6.0f)
            horizontalLineToRelative(0.17f)
            lineTo(1.39f, 4.22f)
            lineToRelative(1.41f, -1.41f)
            lineToRelative(18.38f, 18.38f)
            lineToRelative(-1.41f, 1.41f)
            lineTo(14.0f, 16.83f)
            close()
        }
    }

    val Key: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("Key", autoMirror = false) {
            moveTo(21.0f, 10.0f)
            horizontalLineToRelative(-8.35f)
            curveTo(11.83f, 7.67f, 9.61f, 6.0f, 7.0f, 6.0f)
            curveToRelative(-3.31f, 0.0f, -6.0f, 2.69f, -6.0f, 6.0f)
            reflectiveCurveToRelative(2.69f, 6.0f, 6.0f, 6.0f)
            curveToRelative(2.61f, 0.0f, 4.83f, -1.67f, 5.65f, -4.0f)
            horizontalLineTo(13.0f)
            lineToRelative(2.0f, 2.0f)
            lineToRelative(2.0f, -2.0f)
            lineToRelative(2.0f, 2.0f)
            lineToRelative(4.0f, -4.04f)
            lineTo(21.0f, 10.0f)
            close()
            moveTo(7.0f, 15.0f)
            curveToRelative(-1.65f, 0.0f, -3.0f, -1.35f, -3.0f, -3.0f)
            curveToRelative(0.0f, -1.65f, 1.35f, -3.0f, 3.0f, -3.0f)
            reflectiveCurveToRelative(3.0f, 1.35f, 3.0f, 3.0f)
            curveTo(10.0f, 13.65f, 8.65f, 15.0f, 7.0f, 15.0f)
            close()
        }
    }

    val Lightbulb: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("Lightbulb", autoMirror = false) {
            moveTo(9.0f, 21.0f)
            curveToRelative(0.0f, 0.5f, 0.4f, 1.0f, 1.0f, 1.0f)
            horizontalLineToRelative(4.0f)
            curveToRelative(0.6f, 0.0f, 1.0f, -0.5f, 1.0f, -1.0f)
            verticalLineToRelative(-1.0f)
            lineTo(9.0f, 20.0f)
            verticalLineToRelative(1.0f)
            close()
            moveTo(12.0f, 2.0f)
            curveTo(8.1f, 2.0f, 5.0f, 5.1f, 5.0f, 9.0f)
            curveToRelative(0.0f, 2.4f, 1.2f, 4.5f, 3.0f, 5.7f)
            lineTo(8.0f, 17.0f)
            curveToRelative(0.0f, 0.5f, 0.4f, 1.0f, 1.0f, 1.0f)
            horizontalLineToRelative(6.0f)
            curveToRelative(0.6f, 0.0f, 1.0f, -0.5f, 1.0f, -1.0f)
            verticalLineToRelative(-2.3f)
            curveToRelative(1.8f, -1.3f, 3.0f, -3.4f, 3.0f, -5.7f)
            curveToRelative(0.0f, -3.9f, -3.1f, -7.0f, -7.0f, -7.0f)
            close()
        }
    }

    val Logout: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("Logout", autoMirror = true) {
            moveTo(17.0f, 7.0f)
            lineToRelative(-1.41f, 1.41f)
            lineTo(18.17f, 11.0f)
            horizontalLineTo(8.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(10.17f)
            lineToRelative(-2.58f, 2.58f)
            lineTo(17.0f, 17.0f)
            lineToRelative(5.0f, -5.0f)
            close()
            moveTo(4.0f, 5.0f)
            horizontalLineToRelative(8.0f)
            verticalLineTo(3.0f)
            horizontalLineTo(4.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineTo(4.0f)
            verticalLineTo(5.0f)
            close()
        }
    }

    val Navigation: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("Navigation", autoMirror = false) {
            moveTo(12.0f, 2.0f)
            lineTo(4.5f, 20.29f)
            lineToRelative(0.71f, 0.71f)
            lineTo(12.0f, 18.0f)
            lineToRelative(6.79f, 3.0f)
            lineToRelative(0.71f, -0.71f)
            close()
        }
    }

    val NearMe: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("NearMe", autoMirror = false) {
            moveTo(21.0f, 3.0f)
            lineTo(3.0f, 10.53f)
            verticalLineToRelative(0.98f)
            lineToRelative(6.84f, 2.65f)
            lineTo(12.48f, 21.0f)
            horizontalLineToRelative(0.98f)
            lineTo(21.0f, 3.0f)
            close()
        }
    }

    val OpenInNew: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("OpenInNew", autoMirror = true) {
            moveTo(19.0f, 19.0f)
            horizontalLineTo(5.0f)
            verticalLineTo(5.0f)
            horizontalLineToRelative(7.0f)
            verticalLineTo(3.0f)
            horizontalLineTo(5.0f)
            curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineToRelative(-7.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(7.0f)
            close()
            moveTo(14.0f, 3.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(3.59f)
            lineToRelative(-9.83f, 9.83f)
            lineToRelative(1.41f, 1.41f)
            lineTo(19.0f, 6.41f)
            verticalLineTo(10.0f)
            horizontalLineToRelative(2.0f)
            verticalLineTo(3.0f)
            horizontalLineToRelative(-7.0f)
            close()
        }
    }

    val Save: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("Save", autoMirror = false) {
            moveTo(17.0f, 3.0f)
            lineTo(5.0f, 3.0f)
            curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(21.0f, 7.0f)
            lineToRelative(-4.0f, -4.0f)
            close()
            moveTo(12.0f, 19.0f)
            curveToRelative(-1.66f, 0.0f, -3.0f, -1.34f, -3.0f, -3.0f)
            reflectiveCurveToRelative(1.34f, -3.0f, 3.0f, -3.0f)
            reflectiveCurveToRelative(3.0f, 1.34f, 3.0f, 3.0f)
            reflectiveCurveToRelative(-1.34f, 3.0f, -3.0f, 3.0f)
            close()
            moveTo(15.0f, 9.0f)
            lineTo(5.0f, 9.0f)
            lineTo(5.0f, 5.0f)
            horizontalLineToRelative(10.0f)
            verticalLineToRelative(4.0f)
            close()
        }
    }

    val Security: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("Security", autoMirror = false) {
            moveTo(12.0f, 1.0f)
            lineTo(3.0f, 5.0f)
            verticalLineToRelative(6.0f)
            curveToRelative(0.0f, 5.55f, 3.84f, 10.74f, 9.0f, 12.0f)
            curveToRelative(5.16f, -1.26f, 9.0f, -6.45f, 9.0f, -12.0f)
            lineTo(21.0f, 5.0f)
            lineToRelative(-9.0f, -4.0f)
            close()
            moveTo(12.0f, 11.99f)
            horizontalLineToRelative(7.0f)
            curveToRelative(-0.53f, 4.12f, -3.28f, 7.79f, -7.0f, 8.94f)
            lineTo(12.0f, 12.0f)
            lineTo(5.0f, 12.0f)
            lineTo(5.0f, 6.3f)
            lineToRelative(7.0f, -3.11f)
            verticalLineToRelative(8.8f)
            close()
        }
    }

    val Terminal: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("Terminal", autoMirror = false) {
            moveTo(20.0f, 4.0f)
            horizontalLineTo(4.0f)
            curveTo(2.89f, 4.0f, 2.0f, 4.9f, 2.0f, 6.0f)
            verticalLineToRelative(12.0f)
            curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(6.0f)
            curveTo(22.0f, 4.9f, 21.11f, 4.0f, 20.0f, 4.0f)
            close()
            moveTo(20.0f, 18.0f)
            horizontalLineTo(4.0f)
            verticalLineTo(8.0f)
            horizontalLineToRelative(16.0f)
            verticalLineTo(18.0f)
            close()
            moveTo(18.0f, 17.0f)
            horizontalLineToRelative(-6.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(6.0f)
            verticalLineTo(17.0f)
            close()
            moveTo(7.5f, 17.0f)
            lineToRelative(-1.41f, -1.41f)
            lineTo(8.67f, 13.0f)
            lineToRelative(-2.59f, -2.59f)
            lineTo(7.5f, 9.0f)
            lineToRelative(4.0f, 4.0f)
            lineTo(7.5f, 17.0f)
            close()
        }
    }

    val Tune: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("Tune", autoMirror = false) {
            moveTo(3.0f, 17.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(6.0f)
            verticalLineToRelative(-2.0f)
            lineTo(3.0f, 17.0f)
            close()
            moveTo(3.0f, 5.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(10.0f)
            lineTo(13.0f, 5.0f)
            lineTo(3.0f, 5.0f)
            close()
            moveTo(13.0f, 21.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-8.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(2.0f)
            close()
            moveTo(7.0f, 9.0f)
            verticalLineToRelative(2.0f)
            lineTo(3.0f, 11.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(2.0f)
            lineTo(9.0f, 9.0f)
            lineTo(7.0f, 9.0f)
            close()
            moveTo(21.0f, 13.0f)
            verticalLineToRelative(-2.0f)
            lineTo(11.0f, 11.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(10.0f)
            close()
            moveTo(15.0f, 9.0f)
            horizontalLineToRelative(2.0f)
            lineTo(17.0f, 7.0f)
            horizontalLineToRelative(4.0f)
            lineTo(21.0f, 5.0f)
            horizontalLineToRelative(-4.0f)
            lineTo(17.0f, 3.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(6.0f)
            close()
        }
    }

    val VolumeUp: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("VolumeUp", autoMirror = true) {
            moveTo(3.0f, 9.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(4.0f)
            lineToRelative(5.0f, 5.0f)
            verticalLineTo(4.0f)
            lineTo(7.0f, 9.0f)
            horizontalLineTo(3.0f)
            close()
            moveTo(16.5f, 12.0f)
            curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
            verticalLineToRelative(8.05f)
            curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
            close()
            moveTo(14.0f, 3.23f)
            verticalLineToRelative(2.06f)
            curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
            reflectiveCurveToRelative(-2.11f, 5.85f, -5.0f, 6.71f)
            verticalLineToRelative(2.06f)
            curveToRelative(4.01f, -0.91f, 7.0f, -4.49f, 7.0f, -8.77f)
            reflectiveCurveToRelative(-2.99f, -7.86f, -7.0f, -8.77f)
            close()
        }
    }

    val VolumeOff: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("VolumeOff", autoMirror = true) {
            moveTo(16.5f, 12.0f)
            curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
            verticalLineToRelative(2.21f)
            lineToRelative(2.45f, 2.45f)
            curveToRelative(0.03f, -0.2f, 0.05f, -0.41f, 0.05f, -0.63f)
            close()
            moveTo(19.0f, 12.0f)
            curveToRelative(0.0f, 0.94f, -0.2f, 1.82f, -0.54f, 2.64f)
            lineToRelative(1.51f, 1.51f)
            curveTo(20.63f, 14.91f, 21.0f, 13.5f, 21.0f, 12.0f)
            curveToRelative(0.0f, -4.28f, -2.99f, -7.86f, -7.0f, -8.77f)
            verticalLineToRelative(2.06f)
            curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
            close()
            moveTo(4.27f, 3.0f)
            lineTo(3.0f, 4.27f)
            lineToRelative(4.73f, 4.73f)
            horizontalLineTo(3.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(4.0f)
            lineToRelative(5.0f, 5.0f)
            verticalLineToRelative(-6.73f)
            lineToRelative(4.25f, 4.25f)
            curveToRelative(-0.67f, 0.52f, -1.42f, 0.93f, -2.25f, 1.18f)
            verticalLineToRelative(2.06f)
            curveToRelative(1.38f, -0.31f, 2.63f, -0.95f, 3.69f, -1.81f)
            lineTo(19.73f, 21.0f)
            lineTo(21.0f, 19.73f)
            lineToRelative(-9.0f, -9.0f)
            lineTo(4.27f, 3.0f)
            close()
            moveTo(12.0f, 4.0f)
            lineTo(9.91f, 6.09f)
            lineTo(12.0f, 8.18f)
            verticalLineTo(4.0f)
            close()
        }
    }

    val ScreenRotation: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("ScreenRotation", autoMirror = false) {
            moveTo(16.48f, 2.52f)
            curveToRelative(3.27f, 1.55f, 5.61f, 4.72f, 5.97f, 8.48f)
            horizontalLineToRelative(1.5f)
            curveTo(23.44f, 4.84f, 18.29f, 0.0f, 12.0f, 0.0f)
            lineToRelative(-0.66f, 0.03f)
            lineToRelative(3.81f, 3.81f)
            lineToRelative(1.33f, -1.32f)
            close()
            moveTo(10.23f, 1.75f)
            curveToRelative(-0.59f, -0.59f, -1.54f, -0.59f, -2.12f, 0.0f)
            lineTo(1.75f, 8.11f)
            curveToRelative(-0.59f, 0.59f, -0.59f, 1.54f, 0.0f, 2.12f)
            lineToRelative(12.02f, 12.02f)
            curveToRelative(0.59f, 0.59f, 1.54f, 0.59f, 2.12f, 0.0f)
            lineToRelative(6.36f, -6.36f)
            curveToRelative(0.59f, -0.59f, 0.59f, -1.54f, 0.0f, -2.12f)
            lineTo(10.23f, 1.75f)
            close()
            moveTo(14.83f, 21.19f)
            lineTo(2.81f, 9.17f)
            lineToRelative(6.36f, -6.36f)
            lineToRelative(12.02f, 12.02f)
            lineToRelative(-6.36f, 6.36f)
            close()
            moveTo(7.52f, 21.48f)
            curveTo(4.25f, 19.94f, 1.91f, 16.76f, 1.55f, 13.0f)
            horizontalLineTo(0.05f)
            curveTo(0.56f, 19.16f, 5.71f, 24.0f, 12.0f, 24.0f)
            lineToRelative(0.66f, -0.03f)
            lineToRelative(-3.81f, -3.81f)
            lineToRelative(-1.33f, 1.32f)
            close()
        }
    }

    val StayCurrentLandscape: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("StayCurrentLandscape", autoMirror = false) {
            moveTo(1.0f, 19.0f)
            horizontalLineToRelative(22.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
            verticalLineTo(6.0f)
            curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
            horizontalLineTo(1.0f)
            curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
            verticalLineToRelative(12.0f)
            curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
            close()
            moveTo(19.0f, 7.0f)
            verticalLineToRelative(10.0f)
            horizontalLineTo(5.0f)
            verticalLineTo(7.0f)
            horizontalLineToRelative(14.0f)
            close()
        }
    }

    val StayCurrentPortrait: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("StayCurrentPortrait", autoMirror = false) {
            moveTo(17.0f, 1.01f)
            lineTo(7.0f, 1.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(18.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(10.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(3.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -1.99f, -2.0f, -1.99f)
            close()
            moveTo(17.0f, 19.0f)
            horizontalLineTo(7.0f)
            verticalLineTo(5.0f)
            horizontalLineToRelative(10.0f)
            verticalLineToRelative(14.0f)
            close()
        }
    }

    val Route: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("Route", autoMirror = false) {
            moveTo(19.0f, 15.18f)
            verticalLineTo(7.0f)
            curveToRelative(0.0f, -2.21f, -1.79f, -4.0f, -4.0f, -4.0f)
            reflectiveCurveToRelative(-4.0f, 1.79f, -4.0f, 4.0f)
            verticalLineToRelative(10.0f)
            curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
            reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
            verticalLineTo(8.82f)
            curveTo(8.16f, 8.4f, 9.0f, 7.3f, 9.0f, 6.0f)
            curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
            reflectiveCurveTo(3.0f, 4.34f, 3.0f, 6.0f)
            curveToRelative(0.0f, 1.3f, 0.84f, 2.4f, 2.0f, 2.82f)
            verticalLineTo(17.0f)
            curveToRelative(0.0f, 2.21f, 1.79f, 4.0f, 4.0f, 4.0f)
            reflectiveCurveToRelative(4.0f, -1.79f, 4.0f, -4.0f)
            verticalLineTo(7.0f)
            curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
            reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
            verticalLineToRelative(8.18f)
            curveToRelative(-1.16f, 0.41f, -2.0f, 1.51f, -2.0f, 2.82f)
            curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
            reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
            curveToRelative(0.0f, -1.31f, -0.84f, -2.41f, -2.0f, -2.82f)
            close()
        }
    }

    val SwapVert: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("SwapVert", autoMirror = false) {
            moveTo(16.0f, 17.01f)
            verticalLineTo(10.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(7.01f)
            horizontalLineToRelative(-3.0f)
            lineTo(15.0f, 21.0f)
            lineToRelative(4.0f, -3.99f)
            horizontalLineToRelative(-3.0f)
            close()
            moveTo(9.0f, 3.0f)
            lineTo(5.0f, 6.99f)
            horizontalLineToRelative(3.0f)
            verticalLineTo(14.0f)
            horizontalLineToRelative(2.0f)
            verticalLineTo(6.99f)
            horizontalLineToRelative(3.0f)
            lineTo(9.0f, 3.0f)
            close()
        }
    }

    val SwapHoriz: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("SwapHoriz", autoMirror = false) {
            moveTo(6.99f, 11.0f)
            lineTo(3.0f, 15.0f)
            lineToRelative(3.99f, 4.0f)
            verticalLineToRelative(-3.0f)
            horizontalLineTo(14.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineTo(6.99f)
            verticalLineToRelative(-3.0f)
            close()
            moveTo(21.0f, 9.0f)
            lineToRelative(-3.99f, -4.0f)
            verticalLineToRelative(3.0f)
            horizontalLineTo(10.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(7.01f)
            verticalLineToRelative(3.0f)
            lineTo(21.0f, 9.0f)
            close()
        }
    }

    val MyLocation: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildIcon("MyLocation", autoMirror = false) {
            moveTo(12.0f, 8.0f)
            curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
            reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
            reflectiveCurveToRelative(4.0f, -1.79f, 4.0f, -4.0f)
            reflectiveCurveToRelative(-1.79f, -4.0f, -4.0f, -4.0f)
            close()
            moveTo(20.94f, 11.0f)
            curveToRelative(-0.46f, -4.17f, -3.77f, -7.48f, -7.94f, -7.94f)
            verticalLineTo(1.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(2.06f)
            curveTo(6.83f, 3.52f, 3.52f, 6.83f, 3.06f, 11.0f)
            horizontalLineTo(1.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(2.06f)
            curveToRelative(0.46f, 4.17f, 3.77f, 7.48f, 7.94f, 7.94f)
            verticalLineTo(23.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-2.06f)
            curveToRelative(4.17f, -0.46f, 7.48f, -3.77f, 7.94f, -7.94f)
            horizontalLineTo(23.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-2.06f)
            close()
            moveTo(12.0f, 19.0f)
            curveToRelative(-3.87f, 0.0f, -7.0f, -3.13f, -7.0f, -7.0f)
            reflectiveCurveToRelative(3.13f, -7.0f, 7.0f, -7.0f)
            reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
            reflectiveCurveToRelative(-3.13f, 7.0f, -7.0f, 7.0f)
            close()
        }
    }
}

