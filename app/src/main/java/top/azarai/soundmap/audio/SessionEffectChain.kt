package top.azarai.soundmap.audio

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.util.Log

/**
 * The built-in Android effects attached to ONE audio session, plus the logic to push
 * [EffectParams] onto them.
 *
 * Why per-session and not session 0: attaching insert effects to the global output mix
 * (session 0) is deprecated and, crucially, is bypassed entirely on Bluetooth A2DP when
 * hardware offload is active. The reliable way for a non-root app to shape another app's
 * audio — and have it follow to Bluetooth — is to attach to that app's own playback
 * session, the same mechanism Wavelet and other equalizer apps use. [SessionEffectManager]
 * discovers those sessions and owns one chain per session.
 *
 * Each effect is created defensively and simply skipped if the platform refuses it on this
 * session; [anyAttached] / the per-effect flags report what actually took.
 */
class SessionEffectChain(val sessionId: Int) {

    var virtualizerAttached = false
        private set
    var reverbAttached = false
        private set
    var dynamicsAttached = false
        private set
    var error: String? = null
        private set

    val anyAttached: Boolean get() = virtualizerAttached || reverbAttached || dynamicsAttached

    private var virtualizer: Virtualizer? = null
    private var reverb: PresetReverb? = null
    private var dynamics: DynamicsProcessing? = null

    /** Creates and enables the effects on this session. Call once. */
    fun attach() {
        virtualizer = runCatching {
            Virtualizer(PRIORITY, sessionId).apply {
                enabled = true
                // Headphones (incl. Bluetooth) only get virtualization if we force the
                // binaural mode; otherwise setStrength is silently a no-op on many devices.
                runCatching { forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_BINAURAL) }
            }
        }.onFailure { error = appendError(error, "virtualizer", it) }.getOrNull()
        virtualizerAttached = virtualizer != null

        reverb = runCatching {
            PresetReverb(PRIORITY, sessionId).apply {
                preset = PresetReverb.PRESET_NONE
                enabled = true
            }
        }.onFailure { error = appendError(error, "reverb", it) }.getOrNull()
        reverbAttached = reverb != null

        dynamics = runCatching { createDynamics() }
            .onFailure { error = appendError(error, "dynamics", it) }
            .getOrNull()
        dynamicsAttached = dynamics != null

        Log.i(
            TAG,
            "attach(session=$sessionId) virt=$virtualizerAttached " +
                "reverb=$reverbAttached dyn=$dynamicsAttached err=$error",
        )
    }

    /** Pushes a new dial position onto the live effects. */
    fun apply(params: EffectParams) {
        virtualizer?.let { v ->
            runCatching {
                if (v.strengthSupported) v.setStrength(params.virtualizerStrength)
            }
        }
        reverb?.let { r ->
            runCatching { r.preset = params.reverbPreset }
        }
        dynamics?.let { dp ->
            runCatching {
                dp.setInputGainbyChannel(CHANNEL_LEFT, params.leftInputGainDb)
                dp.setInputGainbyChannel(CHANNEL_RIGHT, params.rightInputGainDb)
                setTrebleShelf(dp, params.trebleShelfGainDb)
            }
        }
    }

    /** Releases all effects on this session; its audio returns to normal. Idempotent. */
    fun release() {
        runCatching { virtualizer?.release() }
        runCatching { reverb?.release() }
        runCatching { dynamics?.release() }
        virtualizer = null
        reverb = null
        dynamics = null
        virtualizerAttached = false
        reverbAttached = false
        dynamicsAttached = false
    }

    private fun createDynamics(): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNEL_COUNT,
            /* preEqInUse = */ false, /* preEqBandCount = */ 1,
            /* mbcInUse = */ false, /* mbcBandCount = */ 1,
            /* postEqInUse = */ true, /* postEqBandCount = */ POST_EQ_BANDS,
            /* limiterInUse = */ true,
        ).build()

        val dp = DynamicsProcessing(PRIORITY, sessionId, config)
        // Seed the two-band post EQ: a flat low/mid band and an adjustable treble band.
        for (ch in 0 until CHANNEL_COUNT) {
            val eq = dp.getPostEqByChannelIndex(ch)
            eq.getBand(0).apply {
                cutoffFrequency = TREBLE_CUTOFF_HZ
                gain = 0f
                isEnabled = true
            }
            eq.getBand(1).apply {
                cutoffFrequency = MAX_FREQ_HZ
                gain = 0f
                isEnabled = true
            }
            dp.setPostEqByChannelIndex(ch, eq)
        }
        dp.enabled = true
        return dp
    }

    private fun setTrebleShelf(dp: DynamicsProcessing, gainDb: Float) {
        for (ch in 0 until CHANNEL_COUNT) {
            val eq = dp.getPostEqByChannelIndex(ch)
            val high = eq.getBand(1)
            high.gain = gainDb
            eq.setBand(1, high)
            dp.setPostEqByChannelIndex(ch, eq)
        }
    }

    private fun appendError(existing: String?, name: String, t: Throwable): String {
        Log.w(TAG, "Failed to attach $name on session $sessionId", t)
        val msg = "$name: ${t.message ?: t.javaClass.simpleName}"
        return if (existing == null) msg else "$existing; $msg"
    }

    companion object {
        private const val TAG = "SessionEffectChain"

        /** The legacy global output mix; kept only as a best-effort fallback. */
        const val GLOBAL_SESSION = 0
        private const val PRIORITY = 100

        private const val CHANNEL_COUNT = 2
        private const val CHANNEL_LEFT = 0
        private const val CHANNEL_RIGHT = 1

        private const val POST_EQ_BANDS = 2
        private const val TREBLE_CUTOFF_HZ = 2000f
        private const val MAX_FREQ_HZ = 19000f
    }
}
