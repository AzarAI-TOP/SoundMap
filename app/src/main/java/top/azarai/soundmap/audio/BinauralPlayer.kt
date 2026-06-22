package top.azarai.soundmap.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/**
 * Renders the selected [DemoSource] through a [BinauralProcessor] and writes float PCM to a
 * stereo [AudioTrack]. Because we output our own samples, the spatial effect is baked into
 * the audio and reaches any device (incl. Bluetooth) without special permissions.
 */
class BinauralPlayer(private val sampleRate: Int = 48000) {

    private val processor = BinauralProcessor(sampleRate)
    // reassigned wholesale from the UI thread; the audio loop picks up the new source on its next block
    @Volatile private var source: DemoSource = DemoSourceId.BEEP.create(sampleRate)
    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun setPosition(p: DialPosition) {
        processor.setTarget(BinauralMapping.map(p, sampleRate))
    }

    fun setSource(id: DemoSourceId) {
        source = id.create(sampleRate)
    }

    /** @return true if playback started (AudioTrack initialized), false otherwise. */
    @Synchronized
    fun start(initial: DialPosition): Boolean {
        if (running) return true
        processor.setTarget(BinauralMapping.map(initial, sampleRate), snap = true)

        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            Log.w(TAG, "getMinBufferSize returned $minBytes")
            return false
        }
        val bufferBytes = maxOf(minBytes, BLOCK_FRAMES * 2 * 4 * 4)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack not initialized (state=${t.state})")
            runCatching { t.release() }
            return false
        }

        track = t
        running = true
        t.play()
        thread = Thread({ loop(t) }, "SoundMapBinaural").also { it.start() }
        return true
    }

    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val mono = FloatArray(BLOCK_FRAMES)
        val stereo = FloatArray(BLOCK_FRAMES * 2)
        while (running) {
            source.read(mono, BLOCK_FRAMES)
            processor.process(mono, BLOCK_FRAMES, stereo)
            val written = t.write(stereo, 0, stereo.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write error $written")
                break
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(200)
        thread = null
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }

    private companion object {
        const val TAG = "SoundMapPlayer"
        const val BLOCK_FRAMES = 480 // 10 ms @ 48 kHz
    }
}
