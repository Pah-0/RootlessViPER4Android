package com.llsl.viper4android.rootless

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.llsl.viper4android.utils.FileLogger
import rikka.shizuku.Shizuku

// The two grants only adb (or Shizuku) can give:
// - DUMP, to find the sessions to mute (SessionDump);
// - the PROJECT_MEDIA app-op, so Android approves capture without asking
//   each time, and on Android 15+ keeps it going while the screen is locked.
object AdbGrants {
    private const val TAG = "AdbGrants"
    private const val PREFS = "adb_grants"
    private const val PREF_SHIZUKU_DECLINED = "shizuku_declined"
    private const val SHIZUKU_REQUEST_CODE = 7301

    fun hasDump(context: Context): Boolean = SessionDump.hasDumpPermission(context)

    fun hasProjectMedia(context: Context): Boolean =
        context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
            "android:project_media",
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED

    fun allGranted(context: Context): Boolean = hasDump(context) && hasProjectMedia(context)

    fun commands(packageName: String): List<String> =
        listOf(
            "pm grant $packageName android.permission.DUMP",
            "appops set $packageName PROJECT_MEDIA allow",
        )

    // Grants whatever is missing through Shizuku, if it's running and the user
    // lets us use it, then calls onDone on the main thread with whether
    // everything is now granted. Asks for Shizuku access at most until the
    // user refuses once.
    fun grantWithShizuku(
        context: Context,
        onDone: (Boolean) -> Unit,
    ) {
        val app = context.applicationContext
        if (allGranted(app)) return onDone(true)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!shizukuRunning()) return onDone(false)
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            runGrants(app, onDone)
            return
        }
        if (prefs.getBoolean(PREF_SHIZUKU_DECLINED, false)) return onDone(false)

        val listener =
            object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(
                    requestCode: Int,
                    grantResult: Int,
                ) {
                    if (requestCode != SHIZUKU_REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        runGrants(app, onDone)
                    } else {
                        prefs.edit().putBoolean(PREF_SHIZUKU_DECLINED, true).apply()
                        onDone(false)
                    }
                }
            }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    }

    private fun shizukuRunning(): Boolean =
        try {
            Shizuku.pingBinder() && !Shizuku.isPreV11()
        } catch (_: Exception) {
            false
        }

    private fun runGrants(
        context: Context,
        onDone: (Boolean) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        val args =
            Shizuku
                .UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
                .daemon(false)
                .processNameSuffix("shell")
                .version(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    val shell = IShellService.Stub.asInterface(binder)
                    val connection = this
                    Thread {
                        for (command in commands(context.packageName)) {
                            val code = runCatching { shell.exec(command) }.getOrElse { -1 }
                            FileLogger.i(TAG, "Shizuku: '$command' exited with $code")
                        }
                        main.post {
                            runCatching { Shizuku.unbindUserService(args, connection, true) }
                            onDone(allGranted(context))
                        }
                    }.start()
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Can't start the Shizuku user service", e)
            onDone(false)
        }
    }
}
