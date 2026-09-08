package com.evcs.favorites.focus

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Text-to-Speech (TTS) and Audio Focus Manager for hands-free audio announcements in Focus Mode.
 *
 * Requirements:
 * 1. Initialize Android TextToSpeech with Vietnamese locale (`vi-VN`), falling back to default TTS locale
 *    if the Vietnamese language pack is not installed.
 * 2. Transient audio ducking (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) so navigation apps or music smoothly duck volume during speech and restore afterward.
 * 3. Fallback watchdog timeout to automatically abandon duck focus if TTS engine fails to fire onDone/onError within 6 seconds.
 * 4. Announcement queueing before TTS initialization completes.
 * 5. Audio mute/unmute preference control.
 * 6. Complete release of TTS and audio focus resources on service shutdown.
 */
class FocusModeTtsManager(
    context: Context,
    private val audioManager: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager,
    initTtsImmediately: Boolean = true,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    val watchdogTimeoutMs: Long = WATCHDOG_TIMEOUT_MS,
    private val onDuckFocusAbandoned: (() -> Unit)? = null
) : TextToSpeech.OnInitListener {

    companion object {
        val VIETNAMESE_LOCALE: Locale = Locale("vi", "VN")
        const val UTTERANCE_ID_PREFIX = "evplus_focus_tts_"
        const val WATCHDOG_TIMEOUT_MS = 6000L
    }

    private val appContext = context.applicationContext
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private val pendingSpeechQueue = mutableListOf<String>()
    private val activeUtteranceCount = java.util.concurrent.atomic.AtomicInteger(0)

    private var audioFocusRequest: AudioFocusRequest? = null
    private var watchdogJob: Job? = null
    private var isDuckFocusHeld = false

    var isMuted: Boolean = false
        set(value) {
            field = value
            if (value) {
                stop()
            }
        }

    val isInitialized: Boolean
        get() = isTtsInitialized

    val isAudioDuckingActive: Boolean
        get() = isDuckFocusHeld

    val isWatchdogActive: Boolean
        get() = watchdogJob?.isActive == true

    val activeUtterances: Int
        get() = activeUtteranceCount.get()

    init {
        initAudioFocusRequest()
        if (initTtsImmediately) {
            initTts()
        }
    }

    fun initTts() {
        if (textToSpeech == null) {
            textToSpeech = TextToSpeech(appContext, this)
        }
    }

    private fun initAudioFocusRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* Ducking handled by Android audio server */ }
                .build()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val tts = textToSpeech ?: return
            val langResult = tts.setLanguage(VIETNAMESE_LOCALE)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to default TTS engine locale
                tts.language = Locale.getDefault()
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Audio focus ducking active; ensure watchdog is running
                    startWatchdog()
                }

                override fun onDone(utteranceId: String?) {
                    if (activeUtteranceCount.decrementAndGet() <= 0) {
                        activeUtteranceCount.set(0)
                        abandonDuckAudioFocus()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (activeUtteranceCount.decrementAndGet() <= 0) {
                        activeUtteranceCount.set(0)
                        abandonDuckAudioFocus()
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (activeUtteranceCount.decrementAndGet() <= 0) {
                        activeUtteranceCount.set(0)
                        abandonDuckAudioFocus()
                    }
                }
            })

            isTtsInitialized = true

            // Flush pending speech queue
            synchronized(pendingSpeechQueue) {
                if (!isMuted) {
                    for (text in pendingSpeechQueue) {
                        speakInternal(text)
                    }
                }
                pendingSpeechQueue.clear()
            }
        } else {
            isTtsInitialized = false
            synchronized(pendingSpeechQueue) {
                pendingSpeechQueue.clear()
            }
        }
    }

    /**
     * Speaks the given announcement text. If TTS is not yet initialized, queues it.
     * Respects the [isMuted] preference.
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (isMuted || text.isBlank()) {
            return
        }

        if (!isTtsInitialized) {
            synchronized(pendingSpeechQueue) {
                pendingSpeechQueue.add(text)
            }
            return
        }

        speakInternal(text, queueMode)
    }

    private fun speakInternal(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (isMuted) return
        val tts = textToSpeech ?: return

        activeUtteranceCount.incrementAndGet()
        requestDuckAudioFocus()
        startWatchdog()

        val utteranceId = "$UTTERANCE_ID_PREFIX${UUID.randomUUID()}"
        val params = Bundle()
        val result = tts.speak(text, queueMode, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            if (activeUtteranceCount.decrementAndGet() <= 0) {
                activeUtteranceCount.set(0)
                abandonDuckAudioFocus()
            }
        }
    }

    /**
     * Starts watchdog timer to automatically abandon duck focus after [timeoutMs]
     * if the TTS engine hangs or fails to call onDone/onError.
     */
    fun startWatchdog(timeoutMs: Long = watchdogTimeoutMs): Job {
        watchdogJob?.cancel()
        return coroutineScope.launch {
            delay(timeoutMs)
            if (activeUtteranceCount.get() > 0) {
                activeUtteranceCount.set(0)
                abandonDuckAudioFocus()
            }
        }.also { watchdogJob = it }
    }

    /**
     * Cancels active watchdog timer.
     */
    fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * Requests transient audio ducking focus.
     */
    fun requestDuckAudioFocus(): Boolean {
        isDuckFocusHeld = true
        return try {
            if (audioManager == null) {
                true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                } ?: false
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Releases transient audio ducking focus and restores background audio volume.
     */
    fun abandonDuckAudioFocus() {
        cancelWatchdog()
        isDuckFocusHeld = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager?.abandonAudioFocusRequest(it)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
        onDuckFocusAbandoned?.invoke()
    }

    /**
     * Stops ongoing speech and abandons audio focus ducking.
     */
    fun stop() {
        cancelWatchdog()
        activeUtteranceCount.set(0)
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {
        }
        abandonDuckAudioFocus()
    }

    /**
     * Shuts down TTS engine and releases all audio and system resources.
     */
    fun shutdown() {
        cancelWatchdog()
        isTtsInitialized = false
        activeUtteranceCount.set(0)
        synchronized(pendingSpeechQueue) {
            pendingSpeechQueue.clear()
        }
        stop()
        try {
            textToSpeech?.shutdown()
        } catch (_: Exception) {
        }
        textToSpeech = null
    }
}
