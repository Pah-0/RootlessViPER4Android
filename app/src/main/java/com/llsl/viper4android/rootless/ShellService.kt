package com.llsl.viper4android.rootless

import kotlin.system.exitProcess

// Shizuku user service. Shizuku runs it in its own process as the shell user,
// which may grant the permissions an adb shell can.
class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): Int =
        ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
            .run {
                inputStream.bufferedReader().readText()
                waitFor()
            }
}
