package com.notyet.terraria.core.proot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Launches /root/tshock/start.sh inside the Ubuntu proot environment.
 *
 * Proven proot command (Phase 0 — do not change):
 *   proot --kill-on-exit --link2symlink -0
 *         -r <rootfsDir>
 *         -b /dev  -b /proc  -b /sys
 *         -w /root
 *         /bin/bash /root/tshock/start.sh
 *
 * All paths come from [ProotInstaller] — this class only owns the process lifecycle.
 * Call [ProotInstaller.isFullyInstalled] before calling [startServer].
 */
@Singleton
class ProotRunner @Inject constructor(
    private val installer: ProotInstaller
) {
    private var serverProcess: Process? = null

    /**
     * Starts /root/tshock/start.sh inside proot.
     * Emits every line of merged stdout+stderr until the process exits.
     */
    fun startServer(): Flow<String> = flow {
        emit("[SYSTEM] Starting TShock via proot…")

        val cmd = buildProotCommand()
        emit("[SYSTEM] ${cmd.joinToString(" ").take(160)}")

        try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .apply {
                    environment()["PROOT_TMP_DIR"]   = installer.tmpDir.absolutePath
                    environment()["LD_LIBRARY_PATH"] = installer.tallocLib.parent
                    environment()["PROOT_LOADER"]    = installer.loaderBinary.absolutePath
                }
                .start()
                .also { serverProcess = it }

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    emit(line!!)
                }
            }

            val exitCode = process.waitFor()
            emit("[SYSTEM] Server exited (code $exitCode)")

        } catch (e: Exception) {
            emit("[ERROR] ${e.message}")
        } finally {
            serverProcess = null
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Sends SIGTERM to proot.
     * --kill-on-exit in the proot command ensures TShock + all children are killed too.
     */
    fun stopServer() {
        serverProcess?.destroy()
        serverProcess = null
    }

    fun isRunning(): Boolean = serverProcess?.isAlive == true

    // ─────────────────────────────────────────────────────────────────────────

    private fun buildProotCommand() = buildList<String> {
        add(installer.prootBinary.absolutePath)
        add("--kill-on-exit")   // propagates termination to TShock + all child processes
        add("--link2symlink")   // translate hard-links → symlinks (required on Android FS)
        add("-0")               // fake uid 0 (root) inside the container
        add("-r"); add(installer.rootfsDir.absolutePath)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        add("-w"); add("/root") // working directory inside the container
        add("/bin/bash")
        add("/root/tshock/start.sh")
    }
}
