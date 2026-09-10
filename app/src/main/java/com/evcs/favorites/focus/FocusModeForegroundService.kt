package com.evcs.favorites.focus

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.evcs.favorites.EvPlusApplication
import com.evcs.favorites.MainActivity
import com.evcs.favorites.R
import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.preferences.FocusModePreferences
import com.evcs.favorites.data.network.here.HereEvApiClient
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.navigation.MapNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Specification representing Intent action and payload parameters,
 * decoupled from Android framework stubs to facilitate deterministic JVM testing.
 */
data class FocusServiceIntentSpec(
    val action: String,
    val targetClass: Class<*>,
    val payloadJson: String? = null
)

/**
 * Android Foreground Service maintaining persistent execution of the Focus Mode
 * telemetry polling loop and notification when Google Maps is navigated in the foreground.
 *
 * Designed with zero memory leaks and explicit coroutine scope lifecycle management.
 */
class FocusModeForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = FocusModeNotificationHelper.CHANNEL_ID
        const val CHANNEL_NAME = FocusModeNotificationHelper.CHANNEL_NAME
        const val NOTIFICATION_ID = FocusModeNotificationHelper.NOTIFICATION_ID

        const val ACTION_START = "com.evcs.favorites.focus.ACTION_START"
        const val ACTION_STOP = "com.evcs.favorites.focus.ACTION_STOP"
        const val ACTION_REROUTE = "com.evcs.favorites.focus.ACTION_REROUTE"
        const val ACTION_SET_MUTED = "com.evcs.favorites.focus.ACTION_SET_MUTED"
        const val EXTRA_STATION_JSON = "extra_station_json"
        const val EXTRA_NEW_STATION_JSON = "extra_new_station_json"
        const val EXTRA_IS_MUTED = "extra_is_muted"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private val _currentEngine = MutableStateFlow<FocusModeTelemetryEngine?>(null)
        val currentEngine: StateFlow<FocusModeTelemetryEngine?> = _currentEngine.asStateFlow()

        private val _currentState = MutableStateFlow<FocusModeState?>(null)
        val currentState: StateFlow<FocusModeState?> = _currentState.asStateFlow()

        /**
         * Returns an intent specification for service startup to support deterministic JVM testing.
         */
        fun getStartIntentSpec(station: Station): FocusServiceIntentSpec {
            return FocusServiceIntentSpec(
                action = ACTION_START,
                targetClass = FocusModeForegroundService::class.java,
                payloadJson = json.encodeToString(Station.serializer(), station)
            )
        }

        /**
         * Returns an intent specification for service termination to support deterministic JVM testing.
         */
        fun getStopIntentSpec(): FocusServiceIntentSpec {
            return FocusServiceIntentSpec(
                action = ACTION_STOP,
                targetClass = FocusModeForegroundService::class.java
            )
        }

        /**
         * Returns an intent specification for 1-tap reroute to support deterministic JVM testing.
         */
        fun getRerouteIntentSpec(newStation: Station): FocusServiceIntentSpec {
            return FocusServiceIntentSpec(
                action = ACTION_REROUTE,
                targetClass = FocusModeForegroundService::class.java,
                payloadJson = json.encodeToString(Station.serializer(), newStation)
            )
        }

        /**
         * Returns an intent specification for audio mute toggle to support deterministic JVM testing.
         */
        fun getSetMutedIntentSpec(isMuted: Boolean): FocusServiceIntentSpec {
            return FocusServiceIntentSpec(
                action = ACTION_SET_MUTED,
                targetClass = FocusModeForegroundService::class.java,
                payloadJson = isMuted.toString()
            )
        }

        /**
         * Helper intent factory to launch Focus Mode foreground service.
         */
        fun createStartIntent(context: Context, station: Station): Intent {
            val spec = getStartIntentSpec(station)
            return Intent(context, spec.targetClass).apply {
                action = spec.action
                putExtra(EXTRA_STATION_JSON, spec.payloadJson)
            }
        }

        /**
         * Helper intent factory to stop Focus Mode foreground service.
         */
        fun createStopIntent(context: Context): Intent {
            val spec = getStopIntentSpec()
            return Intent(context, spec.targetClass).apply {
                action = spec.action
            }
        }

        /**
         * Helper intent factory to trigger 1-tap reroute to an alternative station.
         */
        fun createRerouteIntent(context: Context, newStation: Station): Intent {
            val spec = getRerouteIntentSpec(newStation)
            return Intent(context, spec.targetClass).apply {
                action = spec.action
                putExtra(EXTRA_NEW_STATION_JSON, spec.payloadJson)
            }
        }


        /**
         * Convenience method to start the service in foreground mode across API levels.
         */
        fun start(context: Context, station: Station) {
            val intent = createStartIntent(context, station)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Convenience method to stop the service.
         */
        fun stop(context: Context) {
            val intent = createStopIntent(context)
            context.startService(intent)
        }

        /**
         * Convenience method to update audio mute preference in Focus Mode.
         */
        fun setAudioMuted(context: Context, isMuted: Boolean) {
            val intent = Intent(context, FocusModeForegroundService::class.java).apply {
                action = ACTION_SET_MUTED
                putExtra(EXTRA_IS_MUTED, isMuted)
            }
            context.startService(intent)
        }

        /**
         * Verifies whether notification permission is granted on the current OS version.
         */
        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
    }

    /**
     * Checks whether the service currently has permission to dispatch notifications.
     */
    fun hasNotificationPermission(): Boolean {
        return hasNotificationPermission(applicationContext)
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var engine: FocusModeTelemetryEngine? = null
    private var ttsManager: FocusModeTtsManager? = null
    private var locationService: LocationService? = null
    private var notificationManager: NotificationManager? = null
    private var floatingViewManager: FocusModeFloatingViewManager? = null
    private var stateCollectionJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        FocusModeNotificationHelper.createNotificationChannel(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            AppDebugLogger.log(
                tag = DebugLogTag.FOCUS_MODE,
                level = DebugLogLevel.INFO,
                message = "Focus Mode Service: Nhận lệnh dừng dịch vụ (ACTION_STOP)"
            )
            stopFocusMode()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_SET_MUTED) {
            val isMuted = intent.getBooleanExtra(EXTRA_IS_MUTED, false)
            ttsManager?.isMuted = isMuted
            engine?.setMuted(isMuted)
            AppDebugLogger.log(
                tag = DebugLogTag.FOCUS_MODE,
                level = DebugLogLevel.INFO,
                message = "Focus Mode Service: Thay đổi âm thanh giọng nói -> muted=$isMuted"
            )
            return START_STICKY
        }

        if (intent.action == ACTION_REROUTE) {
            val stationJson = intent.getStringExtra(EXTRA_NEW_STATION_JSON)
            if (stationJson != null) {
                try {
                    val newStation = json.decodeFromString(Station.serializer(), stationJson)
                    handleReroute(newStation)
                } catch (e: Exception) {
                    // Ignore parse errors
                }
            } else {
                _currentState.value?.alternativeStation?.let { alt ->
                    handleFloatingReroute(alt)
                } ?: run {
                    serviceScope.launch {
                        engine?.executeRerouteFlow()?.let { resolved ->
                            val target = resolved.station
                            MapNavigator.navigate(
                                context = applicationContext,
                                latitude = target.latitude,
                                longitude = target.longitude,
                                stationName = target.name
                            )
                        }
                    }
                }
            }
            return START_STICKY
        }

        if (intent.action == ACTION_START) {
            val stationJson = intent.getStringExtra(EXTRA_STATION_JSON)
            if (stationJson != null) {
                try {
                    val station = json.decodeFromString(Station.serializer(), stationJson)
                    initializeAndStartEngine(station)
                } catch (e: Exception) {
                    AppDebugLogger.log(
                        tag = DebugLogTag.FOCUS_MODE,
                        level = DebugLogLevel.ERROR,
                        message = "Focus Mode Service: Lỗi parse Station JSON: ${e.message}"
                    )
                    stopFocusMode()
                    return START_NOT_STICKY
                }
            } else {
                stopFocusMode()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingViewManager?.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFocusMode()
        serviceScope.cancel()
    }

    private fun initializeAndStartEngine(station: Station) {
        // Clean up any existing engine, TTS, location tracking, and coroutine collectors
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        locationService?.stopLocationUpdates()
        locationService = null

        engine?.stop()
        ttsManager?.shutdown()
        floatingViewManager?.removeOverlay()
        floatingViewManager = null

        // Initialize location tracking
        locationService = LocationService(applicationContext).apply {
            startLocationUpdates(intervalMs = 10_000L, minUpdateDistanceMeters = 20f)
        }

        val focusPrefs = FocusModePreferences.create(applicationContext)
        val isVoiceAlertEnabled = focusPrefs.isVoiceAlertEnabled()
        val isMuted = !isVoiceAlertEnabled

        val newTtsManager = FocusModeTtsManager(applicationContext).apply {
            this.isMuted = isMuted
        }
        ttsManager = newTtsManager

        val appContainer = (applicationContext as? EvPlusApplication)?.appContainer
            ?: com.evcs.favorites.di.DefaultAppContainer.getInstance(applicationContext)
        val evcsClient = appContainer.evcsApiClient
        val resolver = appContainer.evcsStationNameResolver
        val newEngine = FocusModeTelemetryEngine(
            initialStation = station,
            evcsApiClient = evcsClient,
            locationProvider = { locationService?.latestCoordinates },
            onVoiceAlert = { alert ->
                newTtsManager.speak(alert.text)
            },
            coroutineScope = serviceScope,
            stationNameResolver = resolver
        ).apply {
            setMuted(isMuted)
        }

        engine = newEngine
        _currentEngine.value = newEngine

        // Determine overlay permission availability
        val canDraw = FocusModeFloatingViewManager.canDrawOverlays(applicationContext)
        AppDebugLogger.log(
            tag = DebugLogTag.FOCUS_MODE,
            level = DebugLogLevel.INFO,
            message = "Focus Mode Service: Khởi chạy cho trạm ${station.name} (id: ${station.id}, toạ độ: ${station.latitude}, ${station.longitude}). Quyền hiển thị cửa sổ nổi (overlay): $canDraw"
        )
        if (canDraw) {
            floatingViewManager = FocusModeFloatingViewManager(
                context = applicationContext,
                onDismiss = { stopFocusMode() },
                onReroute = { rec -> handleFloatingReroute(rec) }
            ).apply {
                showOverlay(newEngine.state.value)
            }
        }

        // Initial foreground notification
        val initialNotification = FocusModeNotificationHelper.buildNotification(
            context = applicationContext,
            state = newEngine.state.value,
            isFallbackMode = !canDraw
        )
        startForegroundCompat(NOTIFICATION_ID, initialNotification)

        // Observe telemetry changes and update overlay and notification
        stateCollectionJob = serviceScope.launch {
            newEngine.state.collectLatest { state ->
                _currentState.value = state
                val canDrawNow = FocusModeFloatingViewManager.canDrawOverlays(applicationContext)

                if (canDrawNow) {
                    if (floatingViewManager == null) {
                        floatingViewManager = FocusModeFloatingViewManager(
                            context = applicationContext,
                            onDismiss = { stopFocusMode() },
                            onReroute = { rec -> handleFloatingReroute(rec) }
                        ).apply {
                            showOverlay(state)
                        }
                    } else {
                        floatingViewManager?.updateView(state)
                    }
                } else {
                    floatingViewManager?.removeOverlay()
                    floatingViewManager = null
                }

                val updatedNotification = FocusModeNotificationHelper.buildNotification(
                    context = applicationContext,
                    state = state,
                    isFallbackMode = !canDrawNow
                )
                if (hasNotificationPermission()) {
                    try {
                        notificationManager?.notify(NOTIFICATION_ID, updatedNotification)
                    } catch (e: Exception) {
                        AppDebugLogger.log(
                            tag = DebugLogTag.FOCUS_MODE,
                            level = DebugLogLevel.WARN,
                            message = "Focus Mode Service: Failed to dispatch notification: ${e.message}"
                        )
                    }
                }
            }
        }

        newEngine.start()
    }

    private fun handleFloatingReroute(recommendation: AlternativeStationRecommendation) {
        AppDebugLogger.log(
            tag = DebugLogTag.FOCUS_MODE,
            level = DebugLogLevel.INFO,
            message = "Focus Mode Service: Nhận lệnh chuyển hướng từ Floating HUD sang ${recommendation.station.name}"
        )
        serviceScope.launch {
            val engineInstance = engine ?: return@launch
            val resolvedRec = engineInstance.executeRerouteFlow()
            val target = (resolvedRec ?: recommendation).station
            if (resolvedRec == null) {
                engineInstance.updateTargetStation(target)
            }
            AppDebugLogger.log(
                tag = DebugLogTag.FOCUS_MODE,
                level = DebugLogLevel.INFO,
                message = "Focus Mode Service: Điều hướng sang trạm mới ${target.name} (id: ${target.id})"
            )
            MapNavigator.navigate(
                context = applicationContext,
                latitude = target.latitude,
                longitude = target.longitude,
                stationName = target.name
            )
        }
    }

    private fun handleReroute(newStation: Station) {
        AppDebugLogger.log(
            tag = DebugLogTag.FOCUS_MODE,
            level = DebugLogLevel.INFO,
            message = "Focus Mode Service: Điều hướng sang trạm mới ${newStation.name} (id: ${newStation.id})"
        )
        engine?.updateTargetStation(newStation)
        MapNavigator.navigate(
            context = applicationContext,
            latitude = newStation.latitude,
            longitude = newStation.longitude,
            stationName = newStation.name
        )
    }

    private fun stopFocusMode() {
        AppDebugLogger.log(
            tag = DebugLogTag.FOCUS_MODE,
            level = DebugLogLevel.INFO,
            message = "Focus Mode Service: Đang tắt dịch vụ & giải phóng tài nguyên"
        )
        stateCollectionJob?.cancel()
        stateCollectionJob = null

        engine?.stop()
        engine = null
        ttsManager?.shutdown()
        ttsManager = null
        _currentEngine.value = null
        _currentState.value = null

        floatingViewManager?.removeOverlay()
        floatingViewManager = null

        locationService?.stopLocationUpdates()
        locationService = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startForegroundCompat(notificationId: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasLocationPerm = LocationService(applicationContext).hasLocationPermission()
                val foregroundServiceType = if (hasLocationPerm) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(notificationId, notification, foregroundServiceType)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            AppDebugLogger.log(
                tag = DebugLogTag.FOCUS_MODE,
                level = DebugLogLevel.WARN,
                message = "Focus Mode Service: startForegroundCompat warning: ${e.message}"
            )
            try {
                startForeground(notificationId, notification)
            } catch (_: Exception) {}
        }
    }
}
