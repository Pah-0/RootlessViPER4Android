package com.llsl.viper4android.rootless

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.llsl.viper4android.utils.FileLogger
import java.util.concurrent.Executors

// Silences other apps' own output while their audio is captured, processed and
// played back by RootlessCapture, so it isn't heard twice. Each playback
// session gets a DynamicsProcessing effect with its input gain at -200 dB;
// playback capture taps the audio before session effects, so the captured copy
// is unaffected. Ported from RootlessJamesDSP's RootlessSessionDatabase and
// MutedAudioEffectFactory.
//
// Everything but the dump itself runs on the main thread.
class SessionMuter(
    private val context: Context,
    // Apps whose capture policy changed: the AudioRecord must be rebuilt to
    // exclude (or stop excluding) them.
    private val onRestrictedUidsChanged: () -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dumpExecutor = Executors.newSingleThreadExecutor()
    private val muted = mutableMapOf<Int, MutedSession>()
    // Sessions seen by callback but not yet confirmed by dump.
    private val pendingMutes = mutableMapOf<Int, PlaybackSession>()
    private var released = false

    private data class MutedSession(
        val uid: Int,
        val packageName: String,
        val effect: AudioEffect,
    )

    // Apps whose playback isn't captured: they must be left audible.
    @Volatile
    var excludedUids: Set<Int> = emptySet()
        set(value) {
            field = value
            muted.filterValues { it.uid in value }.keys.forEach(::unmute)
        }

    // Apps that refuse playback capture; muting them would silence them.
    @Volatile
    var restrictedUids: Set<Int> = emptySet()
        private set

    val mutedPackages: List<String>
        get() = muted.values.map { it.packageName }.distinct()

    // Readable from any thread.
    @Volatile
    var hasMutedSessions = false
        private set

    // The apps with a muted session: the only ones worth capturing.
    @Volatile
    var mutedUids: Set<Int> = emptySet()
        private set

    // Whether a session dump has worked, so sessions are being tracked.
    @Volatile
    var isTracking = false
        private set

    private val playbackCallback =
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                configs?.forEach { config ->
                    val sid = config.audioSessionId
                    if (sid == 0 || sid in muted || sid in pendingMutes) return@forEach
                    val uid = config.uid
                    val pkg = uidOf(uid) ?: return@forEach
                    if (uid == Process.myUid() || uid in excludedUids || uid in restrictedUids) return@forEach
                    val usage = config.playbackAttributes.usage
                    val capturable = usage == AudioAttributes.USAGE_MEDIA
                        || usage == AudioAttributes.USAGE_GAME
                        || usage == AudioAttributes.USAGE_UNKNOWN
                    if (!capturable) return@forEach
                    // Immediately mute; dump will confirm or clean up.
                    mute(sid, PlaybackSession(uid, pkg, "callback"))
                    pendingMutes[sid] = PlaybackSession(uid, pkg, "callback")
                }
                poll()
            }
        }

    fun start() {
        audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
        poll()
    }

    fun release() {
        released = true
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        mainHandler.removeCallbacksAndMessages(null)
        dumpExecutor.shutdownNow()
        muted.keys.toList().forEach(::unmute)
    }

    fun poll() {
        if (released) return
        dumpExecutor.execute {
            val result = SessionDump.dump(context) ?: return@execute
            mainHandler.post { if (!released) update(result) }
        }
    }

    private fun update(result: SessionDumpResult) {
        isTracking = true
        val restricted =
            result.captureAllowed
                .filter { (pkg, allowed) -> !allowed && pkg != context.packageName }
                .keys
                .mapNotNull(::uidOf)
                .toSet()
        if (restricted != restrictedUids) {
            restrictedUids = restricted
            muted.filterValues { it.uid in restricted }.keys.forEach(::unmute)
            onRestrictedUidsChanged()
        }

        muted.keys.filter { it !in result.sessions }.forEach(::unmute)
        // Clear pending mutes for sessions that the dump now knows about.
        pendingMutes.keys.filter { it !in result.sessions }.forEach { pendingMutes.remove(it) }
        for ((sid, session) in result.sessions) {
            if (sid == 0 || sid in muted || sid in pendingMutes) continue
            if (session.uid == Process.myUid() || session.uid in excludedUids || session.uid in restrictedUids) continue
            if (!session.isCapturable) continue
            mute(sid, session)
        }
    }

    private fun mute(
        sid: Int,
        session: PlaybackSession,
    ) {
        val effect =
            try {
                DynamicsProcessing(Int.MAX_VALUE, sid, null).apply {
                    setInputGainAllChannelsTo(MUTE_GAIN_DB)
                    enabled = true
                }
            } catch (e: Exception) {
                FileLogger.e("SessionMuter", "Can't mute session $sid (${session.packageName})", e)
                return
            }
        effect.setEnableStatusListener { fx, enabled ->
            if (!enabled) mainHandler.post { reenable(sid, fx) }
        }
        effect.setControlStatusListener { _, controlGranted ->
            // Another app took over the session; drop ours and try again.
            if (!controlGranted) {
                mainHandler.post {
                    unmute(sid)
                    mainHandler.postDelayed(::poll, 1000)
                }
            }
        }
        muted[sid] = MutedSession(session.uid, session.packageName, effect)
        hasMutedSessions = true
        mutedUids = muted.values.mapTo(mutableSetOf()) { it.uid }
        FileLogger.i("SessionMuter", "Muted session $sid (${session.packageName})")
    }

    private fun reenable(
        sid: Int,
        effect: AudioEffect,
    ) {
        if (muted[sid]?.effect !== effect) return
        try {
            (effect as DynamicsProcessing).setInputGainAllChannelsTo(MUTE_GAIN_DB)
            effect.enabled = true
        } catch (e: Exception) {
            FileLogger.w("SessionMuter", "Can't re-enable mute on session $sid: ${e.message}")
            unmute(sid)
        }
    }

    private fun unmute(sid: Int) {
        val session = muted.remove(sid) ?: return
        hasMutedSessions = muted.isNotEmpty()
        mutedUids = muted.values.mapTo(mutableSetOf()) { it.uid }
        try {
            session.effect.enabled = false
            session.effect.release()
        } catch (e: Exception) {
            FileLogger.d("SessionMuter", "Mute effect for session $sid already gone: ${e.message}")
        }
    }

    private fun uidOf(packageName: String): Int? =
        try {
            context.packageManager.getApplicationInfo(packageName, 0).uid
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val MUTE_GAIN_DB = -200f
    }
}
