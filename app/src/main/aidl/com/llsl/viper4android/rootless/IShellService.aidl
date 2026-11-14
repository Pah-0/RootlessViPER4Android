package com.llsl.viper4android.rootless;

// Shizuku user service: runs in a process with shell (adb) privileges.
interface IShellService {
    // Shizuku calls this transaction to stop the service.
    void destroy() = 16777114;

    // Runs `command` with sh -c and returns its exit code.
    int exec(String command) = 1;
}
