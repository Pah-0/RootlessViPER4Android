package com.llsl.viper4android.rootless

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
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
    private val onFailure: (String) -> Unit,
) {
    private val muter = SessionMuter(context) { recreateRecorder = true }
    private var thread: Thread? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var recreateRecorder = false

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
        val buffer = FloatArray(BUFFER_SAMPLES)
        val bufferBytes = BUFFER_SAMPLES * Float.SIZE_BYTES
        FileLogger.i("Capture", "Starting: ${sampleRate}Hz, $BUFFER_SAMPLES samples per buffer")

        var recorder: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            recorder = buildRecorder(sampleRate, bufferBytes)
            track = buildTrack(sampleRate, bufferBytes)
            while (!stopping) {
                if (recreateRecorder) {
                    recreateRecorder = false
                    recorder?.stop()
                    recorder?.release()
                    recorder = buildRecorder(sampleRate, bufferBytes)
                    FileLogger.i("Capture", "Recorder rebuilt")
                }
                val rec = recorder ?: break
                val out = track ?: break

                // Nothing to process: let the audio hardware sleep.
                if (muter.isTracking && !muter.hasMutedSessions) {
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                    if (out.playState != AudioTrack.PLAYSTATE_STOPPED) out.stop()
                    try {
                        Thread.sleep(IDLE_SLEEP_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) rec.startRecording()
                if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()

                val read = rec.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                engine.process(buffer, read / 2)
                out.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Exception) {
            if (!stopping) {
                FileLogger.e("Capture", "Capture thread failed", e)
                onFailure(e.message ?: e.javaClass.simpleName)
            }
        } finally {
            runCatching { recorder?.stop() }
            runCatching { track?.stop() }
            recorder?.release()
            track?.release()
            FileLogger.i("Capture", "Stopped")
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildRecorder(
        sampleRate: Int,
        bufferBytes: Int,
    ): AudioRecord {
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
        bufferBytes: Int,
    ): AudioTrack =
        AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_UNKNOWN)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    // Never capture our own output back into the input.
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    .build(),
            ).setAudioFormat(
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            ).setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()

    companion object {
        // Interleaved stereo floats per read: RootlessJamesDSP's default.
        private const val BUFFER_SAMPLES = 8192
        private const val IDLE_SLEEP_MS = 50L

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
