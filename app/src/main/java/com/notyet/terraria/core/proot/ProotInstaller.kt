package com.notyet.terraria.core.proot

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the one-time installation of the Linux environment needed to run TShock.
 *
 * Layers installed (in order):
 *  1. proot binary   → extracted from APK assets (bundled at build time, ~500 KB)
 *  2. Ubuntu rootfs  → downloaded from proot-distro GitHub releases (~30 MB)
 *  3. System deps    → libicu-dev + unzip + wget via apt inside proot (ICU is required by .NET)
 *  4. .NET 9 Runtime → downloaded manually to /root/.dotnet (apt doesn't have it reliably)
 *  5. TShock 6.x     → latest linux-arm64 release fetched from GitHub API
 *  6. start.sh       → generated with exact env vars proven to work on ARM64
 *
 * Every step is guarded by a flag file — safe to call setup() repeatedly without re-installing.
 *
 * PROVEN on: Termux → proot-distro → Ubuntu ARM64 → .NET 9.0.17 → TShock 6.1.0 → Terraria 1.4.5.6
 * KEY LESSONS from Phase 0:
 *   - apt install dotnet-runtime-9.0 FAILS → must install manually to ~/.dotnet
 *   - apt install libicu-dev is REQUIRED before TShock starts
 *   - DOTNET_GCHeapHardLimit=0x40000000 prevents OOM crash on mobile
 *   - DOTNET_gcServer=0 required for stable single-process GC on Android
 *   - TShock zip contains a nested .tar → double extraction needed
 */
@Singleton
class ProotInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // ── Constants ─────────────────────────────────────────────────────────�[...]

    companion object {
        private const val TAG = "ProotInstaller"

        // Ubuntu 22.04 ARM64 rootfs from proot-distro v4.34.2
        // v4.x is the last version that provides direct GitHub release tarballs
        private const val ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.4-base-arm64.tar.gz"

        // .NET 9 Runtime ARM64 Linux — Microsoft official short URL (follows to latest 9.x patch)
        // Phase 0 confirmed: 9.0.17 works — manual install to ~/.dotnet required
        private const val DOTNET_URL =
            "https://aka.ms/dotnet/9.0/dotnet-runtime-linux-arm64.tar.gz"

        // GitHub Releases API for latest TShock
        private const val TSHOCK_API =
            "https://api.github.com/repos/Pryaxis/TShock/releases/latest"

        // Flag files — existence = step completed successfully
        private const val FLAG_ROOTFS  = ".flag_rootfs_ok"
        private const val FLAG_DEPS    = ".flag_deps_ok"
        private const val FLAG_DOTNET  = ".flag_dotnet_ok"
        private const val FLAG_TSHOCK  = ".flag_tshock_ok"

        // Warn user if device has less than this free storage
        const val REQUIRED_STORAGE_MB = 1800
    }

    // ── Paths ──────────────────────────────────────────────────────────��[...]

    /** Root of the Ubuntu Linux filesystem inside app storage */
    val rootfsDir: File get() = File(context.filesDir, "ubuntu")

    /** proot binary extracted from APK assets */
    val prootBinary: File get() = File(context.filesDir, "proot")

    /** Temp directory for proot internal use */
    val tmpDir: File get() = File(context.cacheDir, "proot_tmp").also { it.mkdirs() }

    private val tshockDir   get() = File(rootfsDir, "root/tshock").also { it.mkdirs() }
    private val dotnetDir   get() = File(rootfsDir, "root/.dotnet").also { it.mkdirs() }
    private val worldsDir   get() = File(tshockDir, "worlds").also { it.mkdirs() }
    private fun flag(name: String) = File(context.filesDir, name)

    // ── Public API ─────────────────────────────────────────────────────────[...]

    /** True only when all 5 installation steps are complete */
    fun isFullyInstalled(): Boolean =
        prootBinary.exists() && prootBinary.canExecute() &&
        flag(FLAG_ROOTFS).exists()  &&
        flag(FLAG_DEPS).exists()    &&
        flag(FLAG_DOTNET).exists()  &&
        flag(FLAG_TSHOCK).exists()

    /**
     * Runs the full installation pipeline.
     * Each step is skipped if its flag file exists — safe to retry on failure.
     *
     * @param onProgress called with human-readable status strings for UI display
     */
    suspend fun setup(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        step1_ExtractProot(onProgress)
        step2_DownloadRootfs(onProgress)
        step3_InstallDeps(onProgress)
        step4_InstallDotnet(onProgress)
        step5_InstallTShock(onProgress)
        step6_WriteStartScript()
        onProgress("✅ Setup complete — ready to start server")
        Log.i(TAG, "Full setup complete")
    }

    // ── Step 1: Extract proot binary from APK assets ─────────────────────────

    private fun step1_ExtractProot(onProgress: (String) -> Unit) {
        if (prootBinary.exists() && prootBinary.canExecute()) {
            Log.d(TAG, "proot already extracted, skipping")
            return
        }
        onProgress("Extracting proot binary…")
        try {
            context.assets.open("proot").use { src ->
                FileOutputStream(prootBinary).use { dst -> src.copyTo(dst) }
            }
            prootBinary.setExecutable(true, false)
            Log.i(TAG, "proot extracted → ${prootBinary.absolutePath}")
        } catch (e: IOException) {
            throw RuntimeException(
                "proot binary not found in APK assets. " +
                "Ensure proot (ARM64) is placed in app/src/main/assets/proot", e
            )
        }
    }

    // ── Step 2: Download Ubuntu ARM64 rootfs ──────────────────────────────────

    private fun step2_DownloadRootfs(onProgress: (String) -> Unit) {
        if (flag(FLAG_ROOTFS).exists()) {
            Log.d(TAG, "Ubuntu rootfs already installed, skipping")
            return
        }
        rootfsDir.mkdirs()

        val tarball = File(context.cacheDir, "ubuntu-rootfs.tar.gz")
        try {
            onProgress("Downloading Ubuntu rootfs… (~30 MB)")
            download(ROOTFS_URL, tarball) { pct ->
                onProgress("Downloading Ubuntu rootfs… $pct%")
            }

            onProgress("Extracting Ubuntu rootfs… (may take a minute)")
            // System tar is available on Android and handles .tar.gz
            runOrThrow("tar", "-xzf", tarball.absolutePath, "-C", rootfsDir.absolutePath)

            flag(FLAG_ROOTFS).createNewFile()
            Log.i(TAG, "Ubuntu rootfs ready → ${rootfsDir.absolutePath}")
        } finally {
            tarball.delete()
        }
    }

    // ── Step 3: Install system deps inside proot ─────────────────────────────

    private fun step3_InstallDeps(onProgress: (String) -> Unit) {
        if (flag(FLAG_DEPS).exists()) {
            Log.d(TAG, "System deps already installed, skipping")
            return
        }
        onProgress("Installing system dependencies (libicu-dev, unzip, wget)…")
        // libicu-dev = REQUIRED for .NET 9 / TShock — app crashes without it
        // Phase 0 lesson: "Couldn't find a valid ICU package" → apt install libicu-dev
        val output = runInProot(
            "apt-get update -qq 2>&1 | tail -2 && " +
            "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " +
            "libicu-dev unzip wget ca-certificates 2>&1 | tail -5"
        )
        Log.i(TAG, "apt output: $output")
        flag(FLAG_DEPS).createNewFile()
    }

    // ── Step 4: Install .NET 9 Runtime manually ───────────────────────────────

    private fun step4_InstallDotnet(onProgress: (String) -> Unit) {
        if (flag(FLAG_DOTNET).exists()) {
            Log.d(TAG, ".NET 9 already installed, skipping")
            return
        }
        // Phase 0 lesson: "apt install dotnet-runtime-9.0" → "Unable to locate package"
        // Solution: download the tarball manually and extract to /root/.dotnet

        val tarball = File(context.cacheDir, "dotnet-runtime.tar.gz")
        try {
            onProgress("Downloading .NET 9 Runtime for ARM64… (~30 MB)")
            download(DOTNET_URL, tarball) { pct ->
                onProgress("Downloading .NET 9… $pct%")
            }

            onProgress("Installing .NET 9 Runtime…")
            dotnetDir.mkdirs()

            // Extract .NET into the dotnetDir inside the rootfs (maps to /root/.dotnet)
            runOrThrow("tar", "-xzf", tarball.absolutePath, "-C", dotnetDir.absolutePath)
            // Sanity check
            val dotnetBin = File(dotnetDir, "dotnet")
            if (!dotnetBin.exists()) {
                throw RuntimeException(".NET binary missing after extraction — download may be corrupt")
            }
            dotnetBin.setExecutable(true, false)

            flag(FLAG_DOTNET).createNewFile()
            Log.i(TAG, ".NET 9 installed → ${dotnetDir.absolutePath}")
        } finally {
            tarball.delete()
        }
    }

    // ── Step 5: Download and install TShock ──────────────────────────────────

    private fun step5_InstallTShock(onProgress: (String) -> Unit) {
        if (flag(FLAG_TSHOCK).exists()) {
            Log.d(TAG, "TShock already installed, skipping")
            return
        }

        onProgress("Fetching latest TShock linux-arm64 release…")
        val tshockUrl = resolveTShockDownloadUrl()
        Log.i(TAG, "TShock download URL: $tshockUrl")

        val zipFile = File(context.cacheDir, "tshock.zip")
        try {
            onProgress("Downloading TShock… (may take a few minutes)")
            download(tshockUrl, zipFile) { pct ->
                onProgress("Downloading TShock… $pct%")
            }

            onProgress("Extracting TShock… (double extraction)")
            // Phase 0 lesson: TShock release is double-compressed → .zip contains a .tar
            //   Step A: unzip the outer .zip
            val extractTemp = File(context.cacheDir, "tshock_tmp").also {
                it.deleteRecursively(); it.mkdirs()
            }
            unzipFile(zipFile, extractTemp)  // ZipInputStream — no system unzip on Android

            //   Step B: find and extract the inner .tar into tshock directory
            val innerTar = extractTemp.walkTopDown()
                .firstOrNull { it.isFile && it.name.endsWith(".tar") }
                ?: throw RuntimeException(
                    "No .tar file found inside TShock zip. " +
                    "Check github.com/Pryaxis/TShock/releases for release format changes."
                )

            tshockDir.mkdirs()
            runOrThrow("tar", "-xf", innerTar.absolutePath, "-C", tshockDir.absolutePath)
            extractTemp.deleteRecursively()

            // Mark TShock.Server as executable
            val serverBin = File(tshockDir, "TShock.Server")
            if (!serverBin.exists()) {
                throw RuntimeException(
                    "TShock.Server binary not found after extraction at ${serverBin.absolutePath}"
                )
            }
            serverBin.setExecutable(true, false)

            flag(FLAG_TSHOCK).createNewFile()
            Log.i(TAG, "TShock installed → ${tshockDir.absolutePath}")
        } finally {
            zipFile.delete()
        }
    }

    // ── Step 6: Write start.sh ────────────────────────────────────────────────

    /**
     * Generates /root/tshock/start.sh with the exact environment variables
     * proven in Phase 0 to work on ARM64 Android.
     *
     * IMPORTANT: update this method with the start.sh that fixed the world-save crash.
     */
    private fun step6_WriteStartScript() {
        // CRITICAL: TShock on ARM64 crashes (NullReferenceException in OTAPI WorldFile)
        // when world path is resolved dynamically via -worldname.
        // FIX: always provide the FULL explicit world path via -world flag.
        // This was confirmed in Phase 0 testing.
        val worldsPath = "/root/.local/share/Terraria/Worlds"
        val worldFile  = "$worldsPath/MyWorld.wld"

        // Pre-create the Terraria worlds directory inside rootfs
        File(rootfsDir, "root/.local/share/Terraria/Worlds").mkdirs()

        val startScript = File(tshockDir, "start.sh")
        startScript.writeText(buildString {
            appendLine("#!/bin/bash")
            appendLine()
            appendLine("# ── .NET 9 Runtime (manual install to ~/.dotnet) ─────────")
            appendLine("export DOTNET_ROOT=/root/.dotnet")
            appendLine("export PATH=\"\$PATH:/root/.dotnet\"")
            appendLine()
            appendLine("# ── GC settings — required for ARM64 Android stability ───")
            appendLine("# Server GC is tuned for datacenter multi-core, not mobile")
            appendLine("export DOTNET_gcServer=0")
            appendLine()
            appendLine("# Hard GC heap limit: 1 GB (0x40000000 bytes)")
            appendLine("# Prevents 0x8007000E virtual memory overcommit crash on Android")
            appendLine("export DOTNET_GCHeapHardLimit=0x40000000")
            appendLine()
            appendLine("# ── Dirs ─────────────────────────────────────────────────")
            appendLine("cd /root/tshock")
            appendLine("mkdir -p $worldsPath")
            appendLine()
            appendLine("# ── Launch ───────────────────────────────────────────────")
            appendLine("# IMPORTANT: use -world with FULL path, NOT -worldname")
            appendLine("# Reason: OTAPI ARM64 NullReferenceException on SaveFileFormatHeader")
            appendLine("# when world path is resolved dynamically. Explicit path = no crash.")
            appendLine("./TShock.Server \\")
            appendLine("    -world $worldFile \\")
            appendLine("    -autocreate 2 \\")
            appendLine("    -maxplayers 8")
        })

        startScript.setExecutable(true, false)
        Log.i(TAG, "start.sh written with world-save fix → ${startScript.absolutePath}")
    }

    // ── Helpers ─────────────────────────────────────────────────────────��[...]

    /**
     * Fetches GitHub API for TShock latest release and returns the linux-arm64 .zip URL.
     * Parses JSON manually to avoid needing a JSON library dependency.
     */
    private fun resolveTShockDownloadUrl(): String {
        val json = try {
            URL(TSHOCK_API).readText()
        } catch (e: Exception) {
            throw RuntimeException("Failed to fetch TShock release info: ${e.message}", e)
        }

        // Match: "browser_download_url": "...linux-arm64....zip"
        val pattern = Regex(
            """"browser_download_url"\s*:\s*"([^\"]+linux-arm64[^\"]*\.zip)"""
        )
        return pattern.find(json)?.groupValues?.get(1)
            ?: throw RuntimeException(
                "No linux-arm64 .zip found in TShock latest release. " +
                "Check github.com/Pryaxis/TShock/releases manually."
            )
    }

    /**
     * Runs a bash command inside the Ubuntu proot environment.
     * Returns combined stdout+stderr output.
     */
    fun runInProot(bashCommand: String): String {
        val cmd = listOf(
            prootBinary.absolutePath,
            "--kill-on-exit",          // kill all children when proot exits
            "--link2symlink",           // handle symlinks inside rootfs
            "-0",                       // fake root user inside proot
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/bin/bash", "-c", bashCommand
        )

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .apply { environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath }
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()

        Log.d(TAG, "proot exit=$exit command=${bashCommand.take(80)}")
        if (exit != 0) Log.w(TAG, "proot non-zero exit $exit: ${output.take(200)}")

        return output
    }

    /**
     * Downloads from [url] to [dest] with progress callbacks.
     * Follows HTTP redirects (needed for Microsoft short URLs like aka.ms).
     */
    private fun download(url: String, dest: File, onProgress: (Int) -> Unit = {}) {
        var connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout    = 60_000
        connection.instanceFollowRedirects = false
        connection.connect()

        // Manual redirect follow (handles aka.ms → visualstudio.com)
        var redirects = 0
        while (connection.responseCode in 301..303 && redirects < 5) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            connection = URL(location).openConnection() as HttpURLConnection
            connection.connect()
            redirects++
        }

        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            throw RuntimeException(
                "Download failed: HTTP ${connection.responseCode} from $url"
            )
        }

        val totalBytes = connection.contentLengthLong
        var downloadedBytes = 0L

        try {
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(16_384)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            onProgress((downloadedBytes * 100L / totalBytes).toInt())
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        Log.i(TAG, "Downloaded ${dest.name}: ${downloadedBytes / 1024}KB")
    }


    /**
     * Extracts a .zip file using Java ZipInputStream.
     * Android has no system 'unzip' command (runOrThrow("unzip"...) FAILS on Android).
     * This handles TShock's outer .zip in step5.
     */
    private fun unzipFile(zip: File, destDir: File) {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(destDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Runs a system-level command (outside proot).
     * Throws [RuntimeException] with output if exit code is non-zero.
     */
    private fun runOrThrow(vararg cmd: String) {
        val process = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw RuntimeException(
                "Command '${cmd.first()}' failed (exit $exit):\n${output.take(500)}"
            )
        }
        Log.d(TAG, "${cmd.first()} → exit 0")
    }

    // ── Reset (for dev/testing) ───────────────────────────────────────────────

    /**
     * Removes all flag files to force a clean reinstall on next setup() call.
     * Does NOT delete the rootfs (that would need re-download of 30MB+).
     */
    fun resetFlags() {
        listOf(FLAG_ROOTFS, FLAG_DEPS, FLAG_DOTNET, FLAG_TSHOCK)
            .forEach { flag(it).delete() }
        Log.w(TAG, "All installation flags reset — next setup() will reinstall everything")
    }

    /**
     * Full wipe — deletes rootfs + all flags. Use only in dev.
     */
    fun fullReset() {
        resetFlags()
        rootfsDir.deleteRecursively()
        prootBinary.delete()
        Log.w(TAG, "Full reset complete")
    }
}
