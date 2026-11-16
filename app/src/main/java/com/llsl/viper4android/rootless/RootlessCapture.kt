package com.llsl.viper4android.rootless

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.llsl.viper4android.utils.FileLogger

// Captures other apps' playback through MediaProjection, runs it through the
// ViPER engine and plays it back, while SessionMuter silences the originals.
// Ported from RootlessJamesDSP's RootlessAudioProcessorService.
//
// Call everything from the main thread; audio runs on its own thread.
class RootlessCapture(
    private val context: Context,
    private val engine: ViperEngine,
    private val projection: MediaProjection,
    bufferFrames: Int,
    private val onFailure: (String) -> Unit,
) {
    private val muter = SessionMuter(context) { recreateRecorder = true }
    private var thread: Thread? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var recreateRecorder = false

    // Frames per read. Smaller means less delay but less headroom for heavy
    // effects such as long convolver kernels.
    @Volatile
    private var bufferFrames = bufferFrames.coerceIn(MIN_BUFFER_FRAMES, MAX_BUFFER_FRAMES)

    @Volatile
    private var rebuildAll = false

    // Ramp the volume up after each (re)start rather than starting abruptly.
    @Volatile
    var fadeIn = true

    // Set when the output moves to another device, so audio queued for the old
    // one isn't played late on the new one.
    @Volatile
    private var routeChanged = false

    val isRunning: Boolean
        get() = thread?.isAlive == true

    val mutedPackages: List<String>
        get() = muter.mutedPackages

    // Apps the user excluded: neither captured nor muted.
    fun setExcludedPackages(packages: Set<String>) {
        val uids =
            packages
                .mapNotNull {
                    try {
                        context.packageManager.getApplicationInfo(it, 0).uid
                    } catch (_: Exception) {
                        null
                    }
                }.toSet()
        if (uids == muter.excludedUids) return
        muter.excludedUids = uids
        recreateRecorder = true
    }

    fun setBufferFrames(frames: Int) {
        val clamped = frames.coerceIn(MIN_BUFFER_FRAMES, MAX_BUFFER_FRAMES)
        if (clamped == bufferFrames) return
        bufferFrames = clamped
        rebuildAll = true
    }

    fun start() {
        if (thread != null) return
        stopping = false
        muter.start()
        thread = Thread(::run, "ViperCapture").also { it.start() }
    }

    fun stop() {
        stopping = true
        thread?.let {
            it.interrupt()
            it.join(1000)
        }
        thread = null
        muter.release()
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sampleRate = sampleRate(context)
        engine.samplingRate = sampleRate
        val fadeSamples = sampleRate * FADE_IN_MS / 1000 * 2
        var fadePos = 0
        var frames = bufferFrames
        var buffer = FloatArray(frames * 2)
        FileLogger.i("Capture", "Starting: ${sampleRate}Hz, $frames frames per buffer")

        var lastDeviceId = 0
        val routingListener =
            AudioRouting.OnRoutingChangedListener { routing ->
                val id = routing.routedDevice?.id ?: return@OnRoutingChangedListener
                if (lastDeviceId != 0 && id != lastDeviceId) routeChanged = true
                lastDeviceId = id
            }
        val handler = Handler(Looper.getMainLooper())

        var recorder: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            recorder = buildRecorder(sampleRate, frames)
            track = buildTrack(sampleRate, frames).also { it.addOnRoutingChangedListener(routingListener, handler) }
            while (!stopping) {
                if (rebuildAll) {
                    rebuildAll = false
                    recreateRecorder = false
                    frames = bufferFrames
                    buffer = FloatArray(frames * 2)
                    recorder?.stop()
                    recorder?.release()
                    track?.removeOnRoutingChangedListener(routingListener)
                    track?.stop()
                    track?.release()
                    recorder = buildRecorder(sampleRate, frames)
                    track = buildTrack(sampleRate, frames).also { it.addOnRoutingChangedListener(routingListener, handler) }
                    fadePos = 0
                    FileLogger.i("Capture", "Rebuilt with $frames frames per buffer")
                }
                if (recreateRecorder) {
                    recreateRecorder = false
                    recorder?.stop()
                    recorder?.release()
                    recorder = buildRecorder(sampleRate, frames)
                    fadePos = 0
                    FileLogger.i("Capture", "Recorder rebuilt")
                }
                val rec = recorder ?: break
                val out = track ?: break

                if (routeChanged) {
                    routeChanged = false
                    out.pause()
                    out.flush()
                    fadePos = 0
                    FileLogger.i("Capture", "Output device changed: dropped queued audio")
                }

                // Nothing to process: let the audio hardware sleep. Drop what's
                // queued for output rather than letting stop() play it out, or
                // the end of the last track plays when the next one starts.
                if (muter.isTracking && !muter.hasMutedSessions) {
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                    if (out.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        out.pause()
                        out.flush()
                    }
                    fadePos = 0
                    try {
                        Thread.sleep(IDLE_SLEEP_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    rec.startRecording()
                    drain(rec, buffer)
                }
                if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()

                val read = rec.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                engine.process(buffer, read / 2)
                // Ramp up after a (re)start so it doesn't begin with a click.
                if (fadeIn && fadePos < fadeSamples) {
                    var i = 0
                    while (i + 1 < read && fadePos < fadeSamples) {
                        val gain = fadePos.toFloat() / fadeSamples
                        buffer[i] *= gain
                        buffer[i + 1] *= gain
                        i += 2
                        fadePos += 2
                    }
                }
                out.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Exception) {
            if (!stopping) {
                FileLogger.e("Capture", "Capture thread failed", e)
                onFailure(e.message ?: e.javaClass.simpleName)
            }
        } finally {
            runCatching { recorder?.stop() }
            runCatching { track?.removeOnRoutingChangedListener(routingListener) }
            runCatching { track?.stop() }
            recorder?.release()
            track?.release()
            FileLogger.i("Capture", "Stopped")
        }
    }

    // Discard what the recorder still holds from before it was stopped.
    private fun drain(
        rec: AudioRecord,
        buffer: FloatArray,
    ) {
        var dropped = 0
        for (i in 0 until MAX_DRAIN_READS) {
            val n = rec.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            if (n <= 0) break
            dropped += n
            if (n < buffer.size) break
        }
        if (dropped > 0) FileLogger.d("Capture", "Dropped ${dropped / 2} stale frames")
    }

    @SuppressLint("MissingPermission")
    private fun buildRecorder(
        sampleRate: Int,
        frames: Int,
    ): AudioRecord {
        // Room for two reads, so a slow pass doesn't drop audio; any more would
        // only let a backlog, and with it the delay, build up.
        val bufferBytes =
            maxOf(
                AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT),
                frames * 2 * FRAME_BYTES,
            )
        val config =
            AudioPlaybackCaptureConfiguration
                .Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        val excluded = muter.excludedUids + muter.restrictedUids + Process.myUid()
        excluded.forEach(config::excludeUid)
        return AudioRecord
            .Builder()
            .setAudioFormat(
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build(),
            ).setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config.build())
            .build()
            .also {
                check(it.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
            }
    }

    private fun buildTrack(
        sampleRate: Int,
        frames: Int,
    ): AudioTrack {
        // The output buffer stays full, so its size is most of the delay.
        val bufferBytes =
            maxOf(
                AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT),
                frames * 2 * FRAME_BYTES,
            )
        val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_UNKNOWN)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                // Never capture our own output back into the input.
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        // Keep the system's spatial audio from processing ViPER's output again.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            attributes.setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER)
        }
        return AudioTrack
            .Builder()
            .setAudioAttributes(attributes.build())
            .setAudioFormat(
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            ).setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
    }

    companion object {
        const val DEFAULT_BUFFER_FRAMES = 1024
        const val MIN_BUFFER_FRAMES = 256
        const val MAX_BUFFER_FRAMES = 4096
        private const val FRAME_BYTES = 2 * Float.SIZE_BYTES
        private const val IDLE_SLEEP_MS = 50L
        private const val FADE_IN_MS = 50
        private const val MAX_DRAIN_READS = 64

        // The output's native rate, limited to the two rates ViPER's DDC
        // coefficients come in.
        fun sampleRate(context: Context): Int =
            context
                .getSystemService(AudioManager::class.java)
                .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.coerceIn(44100, 48000) ?: 48000
    }
}
