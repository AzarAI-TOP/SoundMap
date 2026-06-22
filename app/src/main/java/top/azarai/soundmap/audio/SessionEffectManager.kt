package top.azarai.soundmap.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers the audio sessions that are actually playing and keeps a [SessionEffectChain]
 * attached to each, so SoundMap's effect follows whatever the user is listening to —
 * including over Bluetooth.
 *
 * Three discovery paths, in order of coverage:
 *  1. Active-playback scan via [AudioManager.getActivePlaybackConfigurations] +
 *     [AudioManager.AudioPlaybackCallback]. This sees *every* playing app, but the OS
 *     anonymizes other apps' configs (their real session id is stripped) UNLESS the app
 *     holds the signature-level DUMP permission. That permission can only be granted with:
 *         adb shell pm grant top.azarai.soundmap android.permission.DUMP
 *     This is the same one-time setup Wavelet requires; without it, coverage falls back to
 *     paths 2 and 3 only.
 *  2. The opt-in [AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION] broadcast that some
 *     players send with their session id. No special permission, but few apps send it.
 *  3. The legacy global session 0, attached as a last-resort best effort.
 *
 * Honest limitation: even with all three, whether the effect reaches a Bluetooth headset is
 * device-dependent — A2DP hardware offload can bypass software effects. Some devices need
 * "Disable Bluetooth A2DP hardware offload" toggled in Developer options. No non-root app
 * can remove that constraint.
 */
class SessionEffectManager(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val thread = HandlerThread("SoundMapSessions").apply { start() }
    private val handler = Handler(thread.looper)

    private val chains = ConcurrentHashMap<Int, SessionEffectChain>()
    /** Sessions found via the playback scan, so we only auto-remove ones we auto-added. */
    private val discovered = ConcurrentHashMap.newKeySet<Int>()

    @Volatile private var current: EffectParams = EffectParams.NEUTRAL
    @Volatile private var started = false

    /** Invoked whenever the attached-session set changes, so the UI can refresh status. */
    var onChanged: (() -> Unit)? = null

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            syncDiscovered(configs)
        }
    }

    private val effectSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val sid = intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, INVALID) ?: INVALID
            if (sid <= 0) return
            when (intent?.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attachSession(sid)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> detachSession(sid)
            }
        }
    }

    /** Begins shaping audio. Idempotent; safe to call again to push new [params]. */
    fun start(params: EffectParams) {
        current = params
        if (started) {
            apply(params)
            return
        }
        started = true

        val dump = appContext.checkSelfPermission(Manifest.permission.DUMP) ==
            PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "start: dumpGranted=$dump")

        attachSession(SessionEffectChain.GLOBAL_SESSION) // path 3: best-effort fallback

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(
            appContext, effectSessionReceiver, filter, ContextCompat.RECEIVER_EXPORTED,
        )

        runCatching {
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
            syncDiscovered(audioManager.activePlaybackConfigurations)
        }
        onChanged?.invoke()
    }

    /** Live-updates every attached session as the user moves the dial. */
    fun apply(params: EffectParams) {
        current = params
        chains.values.forEach { it.apply(params) }
    }

    /** Releases everything; all audio returns to normal. Idempotent. */
    fun stop() {
        if (!started) return
        started = false
        runCatching { audioManager.unregisterAudioPlaybackCallback(playbackCallback) }
        runCatching { appContext.unregisterReceiver(effectSessionReceiver) }
        chains.values.forEach { it.release() }
        chains.clear()
        discovered.clear()
        onChanged?.invoke()
    }

    /** Snapshot of what actually attached, for the on-device diagnostics readout. */
    fun status(): EngineStatus = EngineStatus(
        dumpGranted = appContext.checkSelfPermission(Manifest.permission.DUMP) ==
            PackageManager.PERMISSION_GRANTED,
        sessionCount = chains.size,
        attachedSessions = chains.values.count { it.anyAttached },
        anyEffectAttached = chains.values.any { it.anyAttached },
    )

    private fun syncDiscovered(configs: List<AudioPlaybackConfiguration>) {
        val live = configs.mapNotNull { it.sessionIdOrNull() }.toSet()
        Log.i(TAG, "scan: ${configs.size} playback configs -> usable sessionIds=$live")
        var changed = false
        // Drop sessions we discovered earlier that have stopped playing.
        discovered.filter { it !in live }.forEach {
            detachSession(it)
            discovered.remove(it)
            changed = true
        }
        live.forEach {
            if (discovered.add(it)) changed = true
            attachSession(it)
        }
        if (changed) onChanged?.invoke()
    }

    private fun attachSession(sessionId: Int) {
        if (sessionId < 0) return
        var created = false
        chains.computeIfAbsent(sessionId) {
            created = true
            SessionEffectChain(it).apply { attach() }
        }.apply(current)
        if (created) onChanged?.invoke()
    }

    private fun detachSession(sessionId: Int) {
        if (sessionId == SessionEffectChain.GLOBAL_SESSION) return
        chains.remove(sessionId)?.let {
            it.release()
            onChanged?.invoke()
        }
    }

    /**
     * Reads the session id of a playback config. Public-API method is hidden, so reflect it;
     * without DUMP the OS returns a sanitized config whose id is 0/absent, which we drop.
     */
    private fun AudioPlaybackConfiguration.sessionIdOrNull(): Int? = runCatching {
        AudioPlaybackConfiguration::class.java.getMethod("getSessionId").invoke(this) as Int
    }.onFailure {
        Log.w(TAG, "getSessionId() reflection failed (hidden-API block?): ${it.message}")
    }.getOrNull()?.takeIf { it > 0 }

    private companion object {
        const val INVALID = -1
        const val TAG = "SoundMapSession"
    }
}
