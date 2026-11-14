package com.llsl.viper4android.rootless

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.llsl.viper4android.utils.FileLogger
import java.util.Locale

data class PlaybackSession(
    val uid: Int,
    val packageName: String,
    val usage: String,
) {
    // The usages AudioPlaybackCaptureConfiguration is set to capture.
    val isCapturable: Boolean
        get() =
            usage.uppercase(Locale.ROOT).let {
                "USAGE_MEDIA" in it || "USAGE_GAME" in it || "USAGE_UNKNOWN" in it
            }
}

data class SessionDumpResult(
    val sessions: Map<Int, PlaybackSession>,
    // Package name to whether the app allows its playback to be captured.
    val captureAllowed: Map<String, Boolean>,
)

// Lists other apps' playback sessions by reading system service dumps, which
// needs android.permission.DUMP (granted once over adb). Ported from
// RootlessJamesDSP's AudioPolicyServiceDumpProvider and AudioServiceDumpProvider.
object SessionDump {
    private const val TAG = "SessionDump"

    // API 29 to 32
    private val policySessionRegex =
        """Session Id:\s*(\d+)\s+UID:\s*(\d+)[\S\s]*?Attributes:[\S\s]*?Content type:\s*(\w+)\s*Usage:\s*(\w+)""".toRegex()

    // API 33+
    private val policySessionRegex33 =
        """Session ID:\s*(\d+);\s*uid \s*(\d+);[\S\s]*?Attributes:[\S\s]*?Content type:\s*(\w+)\s*Usage:\s*(\w+)""".toRegex()
    private val captureAllowedRegex = """allowPlaybackCapture=(\S+)(?:\s*,).+packageName=(\S+)""".toRegex()

    // API 31+; earlier releases leave the session id out of this dump.
    private val playbackConfigRegex =
        """AudioPlaybackConfiguration.*u/pid:(\d+)/(\d+).*usage=(\w+).*content=(\w+).*sessionId:(\d+)""".toRegex()

    fun hasDumpPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    fun dump(context: Context): SessionDumpResult? {
        if (!hasDumpPermission(context)) return null
        val policy = dumpService("media.audio_policy")?.let { parsePolicyDump(context, it) }
        if (policy != null && policy.sessions.isNotEmpty()) return policy
        // Fall back to the audio service for sessions, keeping the policy
        // dump's capture log, which only it has.
        val audio = dumpService("audio")?.let { parseAudioDump(context, it) } ?: return policy
        return SessionDumpResult(audio, policy?.captureAllowed.orEmpty())
    }

    private fun parsePolicyDump(
        context: Context,
        dump: String,
    ): SessionDumpResult {
        val sessions = mutableMapOf<Int, PlaybackSession>()
        var matches = policySessionRegex.findAll(dump)
        if (matches.none()) matches = policySessionRegex33.findAll(dump)
        for (m in matches) {
            val sid = m.groupValues[1].toIntOrNull() ?: continue
            val uid = m.groupValues[2].toIntOrNull() ?: continue
            sessions[sid] = PlaybackSession(uid, packageNameOf(context, uid), m.groupValues[4])
        }

        val captureAllowed = mutableMapOf<String, Boolean>()
        for (m in captureAllowedRegex.findAll(dump)) {
            val pkg = m.groupValues[2].removePrefix("shared:")
            captureAllowed[pkg] = m.groupValues[1].trim().lowercase(Locale.ROOT) == "true"
        }
        return SessionDumpResult(sessions, captureAllowed)
    }

    private fun parseAudioDump(
        context: Context,
        dump: String,
    ): Map<Int, PlaybackSession> {
        val sessions = mutableMapOf<Int, PlaybackSession>()
        for (m in playbackConfigRegex.findAll(dump)) {
            val uid = m.groupValues[1].toIntOrNull() ?: continue
            val sid = m.groupValues[5].toIntOrNull() ?: continue
            sessions[sid] = PlaybackSession(uid, packageNameOf(context, uid), m.groupValues[3])
        }
        return sessions
    }

    private fun packageNameOf(
        context: Context,
        uid: Int,
    ): String = context.packageManager.getNameForUid(uid)?.substringBefore(':') ?: uid.toString()

    private fun dumpService(name: String): String? {
        return try {
            // A hidden API: ViPER4AndroidApp exempts the app from the restriction.
            val binder =
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, name) as? IBinder
            if (binder == null) {
                FileLogger.w(TAG, "Service '$name' not found")
                return null
            }
            val (read, write) = ParcelFileDescriptor.createPipe()
            binder.dumpAsync(write.fileDescriptor, arrayOf())
            write.close()
            ParcelFileDescriptor.AutoCloseInputStream(read).use { it.reader().readText() }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to dump '$name'", e)
            null
        }
    }
}
