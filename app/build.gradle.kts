plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")   // Kotlin 2.0 — replaces composeOptions block
    id("org.jetbrains.kotlin.kapt")             // was: id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace  = "com.notyet.terraria"
    compileSdk = 35                             // 34 → 35

    defaultConfig {
        applicationId             = "com.notyet.terraria"
        minSdk                    = 26
        targetSdk                 = 35          // 34 → 35
        versionCode               = 1
        versionName               = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // Extract .so files to nativeLibraryDir at install time (required for exec)
            // Without this, proot stays compressed inside APK and can't be run
            useLegacyPackaging = true
            // Don't strip libproot.so — it's a pre-built executable, not a real .so
            keepDebugSymbols += "**/libproot.so"
        }
    }

    // ❌ composeOptions { kotlinCompilerExtensionVersion = "1.5.1" } ← REMOVED
    // With Kotlin 2.0 + org.jetbrains.kotlin.plugin.compose, the compiler
    // is bundled — setting kotlinCompilerExtensionVersion causes a build crash.
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Jetpack Compose — BOM pins all compose library versions
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")  // 2024.02.00 → 2024.09.00
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Coroutines & Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")  // 1.7.3 → 1.8.1

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")             // 2.50 → 2.51.1
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")              // 2.50 → 2.51.1
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")       // 1.1.0 → 1.2.0

    // Testing
    testImplementation("junit:junit:4.13.2")
}

// ─────────────────────────────────────────────────────────────────────────────
// Task: downloadProotBinary
// Downloads the proot ARM64 static binary from Termux packages and places it
// in src/main/jniLibs/arm64-v8a/libproot.so
//
// Why jniLibs and not assets?
//   Android 10+ mounts filesDir with noexec — binaries extracted from assets
//   to filesDir get "Permission denied" (error=13) when executed.
//   jniLibs are extracted to nativeLibraryDir which is always executable.
//
// Runs automatically before every build (preBuild dependency below).
// Skips silently if the binary is already present (idempotent).
//
// Requirements on build machine: curl, ar, tar
//   macOS:  xcode-select --install (provides all three)
//   Linux:  sudo apt install curl binutils tar
//   zstd (only if termux switched to data.tar.zst format):
//     macOS: brew install zstd
//     Linux: sudo apt install zstd
// ─────────────────────────────────────────────────────────────────────────────

tasks.register("downloadProotBinary") {
    description = "Downloads proot + libtalloc from Termux packages (jniLibs + assets)"
    group       = "setup"

    val prootDest  = project.file("src/main/jniLibs/arm64-v8a/libproot.so")
    val tallocDest = project.file("src/main/assets/libtalloc.so.2")
    outputs.files(prootDest, tallocDest)

    doLast {
        val prootOk  = prootDest.exists()  && prootDest.length()  > 100_000L
        val tallocOk = tallocDest.exists() && tallocDest.length() >  10_000L
        if (prootOk && tallocOk) {
            logger.lifecycle("proot + libtalloc already present — skipping")
            return@doLast
        }
        prootDest.parentFile.mkdirs()
        tallocDest.parentFile.mkdirs()

        val tmp = temporaryDir.also { it.mkdirs() }
        val d   = "\$"

        fun extractFromDeb(pkgName: String, findName: String, outFile: java.io.File) {
            exec {
                isIgnoreExitValue = false
                commandLine("bash", "-c", """
                    set -euo pipefail
                    cd '${tmp.absolutePath}'
                    mkdir -p ${pkgName}_ext

                    curl -fsSL -o Packages_${pkgName} \
                        'https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages'

                    DEB_REL=${d}(awk '
                        /^Package: ${pkgName}${'$'}/ { found=1; next }
                        found && /^Filename:/        { print ${'$'}2; exit }
                        /^Package:/                  { found=0 }
                    ' Packages_${pkgName})
                    [ -z "${d}{DEB_REL}" ] && echo "ERROR: ${pkgName} not found" && exit 1

                    DEB_URL="https://packages.termux.dev/apt/termux-main/${d}{DEB_REL}"
                    curl -fsSL -o ${pkgName}.deb "${d}{DEB_URL}"

                    ar x ${pkgName}.deb
                    if   [ -f data.tar.xz  ]; then tar -xJf data.tar.xz  -C ${pkgName}_ext/
                    elif [ -f data.tar.zst ]; then zstd -d data.tar.zst --stdout | tar -x -C ${pkgName}_ext/
                    elif [ -f data.tar.gz  ]; then tar -xzf data.tar.gz  -C ${pkgName}_ext/
                    else echo "ERROR: unknown data.tar format" && exit 1; fi

                    BIN=${d}(find ${pkgName}_ext/ -name '${findName}' | head -1)
                    [ -z "${d}{BIN}" ] && echo "ERROR: ${findName} not found" && exit 1

                    cp "${d}{BIN}" '${outFile.absolutePath}'
                    chmod +x '${outFile.absolutePath}'
                    echo "Done: ${findName} (${d}(du -h '${outFile.absolutePath}' | cut -f1))"
                    rm -f data.tar.* *.deb Packages_${pkgName}
                """.trimIndent())
            }
        }

        // Also extract proot's loader binary — needed to exec programs on noexec filesystems
        // loader is inside the proot .deb at usr/libexec/proot/loader (64-bit ARM)
        val loaderDest = project.file("src/main/jniLibs/arm64-v8a/libproot-loader64.so")
        loaderDest.parentFile.mkdirs()
        val loaderOk = loaderDest.exists() && loaderDest.length() > 1_000L

        if (!prootOk || !loaderOk) {
            // Download proot deb once, extract both proot binary AND loader from it
            exec {
                isIgnoreExitValue = false
                val d = "\$"
                commandLine("bash", "-c", """
                    set -euo pipefail
                    cd '${temporaryDir.absolutePath}'
                    mkdir -p proot_both

                    curl -fsSL -o Packages_proot \
                        'https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages'

                    DEB_REL=${d}(awk '
                        /^Package: proot${'$'}/ { found=1; next }
                        found && /^Filename:/   { print ${'$'}2; exit }
                        /^Package:/            { found=0 }
                    ' Packages_proot)
                    [ -z "${d}{DEB_REL}" ] && echo "ERROR: proot not found" && exit 1

                    DEB_URL="https://packages.termux.dev/apt/termux-main/${d}{DEB_REL}"
                    echo "Downloading ${d}{DEB_URL}..."
                    curl -fsSL -o proot_both.deb "${d}{DEB_URL}"

                    ar x proot_both.deb
                    if   [ -f data.tar.xz  ]; then tar -xJf data.tar.xz  -C proot_both/
                    elif [ -f data.tar.zst ]; then zstd -d data.tar.zst --stdout | tar -x -C proot_both/
                    elif [ -f data.tar.gz  ]; then tar -xzf data.tar.gz  -C proot_both/
                    else echo "ERROR: unknown data.tar format" && exit 1; fi

                    BIN=${d}(find proot_both/ -type f -name 'proot' | head -1)
                    LOADER=${d}(find proot_both/ -type f -name 'loader' | grep -v loader32 | head -1)

                    [ -z "${d}{BIN}"    ] && echo "ERROR: proot binary not found"  && exit 1
                    [ -z "${d}{LOADER}" ] && echo "ERROR: loader binary not found" && exit 1

                    cp "${d}{BIN}"    '${prootDest.absolutePath}'
                    cp "${d}{LOADER}" '${loaderDest.absolutePath}'
                    chmod +x '${prootDest.absolutePath}' '${loaderDest.absolutePath}'
                    echo "proot:  ${d}(du -h '${prootDest.absolutePath}'  | cut -f1)"
                    echo "loader: ${d}(du -h '${loaderDest.absolutePath}' | cut -f1)"
                    rm -f data.tar.* *.deb Packages_proot
                """.trimIndent())
            }
        } else {
            logger.lifecycle("proot + loader already present — skipping")
        }
        if (!tallocOk) extractFromDeb("libtalloc",         "libtalloc.so.2",     tallocDest)

        // libandroid-shmem: POSIX shm_open/shm_unlink compat for Android (required by proot)
        val shmemDest = project.file("src/main/assets/libandroid-shmem.so")
        shmemDest.parentFile.mkdirs()
        if (!shmemDest.exists() || shmemDest.length() < 1_000L)
            extractFromDeb("libandroid-shmem", "libandroid-shmem.so", shmemDest)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Task: downloadIcuLibs
// Downloads libicu70 (Ubuntu 22.04 jammy, arm64) and extracts the shared
// libraries into assets/icu-libs.tar.gz — bundled in the APK, extracted onto
// the rootfs filesystem directly at runtime (plain tar, NO proot involved).
//
// WHY build-time instead of runtime download:
//   Android's toybox shell has NO `ar` command, so a .deb (ar archive) cannot
//   be unpacked on-device without going through proot. Earlier attempts using
//   `dpkg-deb -x` INSIDE proot failed silently (unclear cause — proot syscall
//   translation edge case, or dpkg-deb quirk on Android). This build-time
//   approach sidesteps that entirely: unpack on the CI runner (full toolchain
//   available), ship only the resulting .so files, extract with plain `tar`
//   directly onto rootfsDir on the Android host filesystem — the exact same
//   proven, reliable code path already used for rootfs/.NET/TShock extraction.
//
// WHY real ICU instead of DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1:
//   Terraria's LanguageManager hard-codes creation of CultureInfo("en-US").
//   In invariant mode .NET can ONLY create the invariant culture — any named
//   culture throws CultureNotFoundException. Invariant mode is not viable for
//   TShock; real ICU libraries are required.
//
// Package: libicu70 from ports.ubuntu.com, jammy (22.04), binary-arm64
// Contains: libicuuc.so.70(.1), libicudata.so.70(.1), libicui18n.so.70(.1)
//           + the runtime SONAME symlinks (shipped by the package itself,
//           usable immediately without ldconfig)
// ─────────────────────────────────────────────────────────────────────────────

tasks.register("downloadIcuLibs") {
    description = "Extracts libicu70 .so files (Ubuntu 22.04 arm64) into assets/icu-libs.tar.gz"
    group       = "setup"

    val icuAsset = project.file("src/main/assets/icu-libs.tar.gz")
    outputs.file(icuAsset)

    doLast {
        if (icuAsset.exists() && icuAsset.length() > 1_000_000L) {
            logger.lifecycle("icu-libs.tar.gz already present (${icuAsset.length() / 1024 / 1024} MB) — skipping")
            return@doLast
        }
        icuAsset.parentFile.mkdirs()

        val tmp = temporaryDir.also { it.mkdirs() }
        val d   = "\$"

        // Two mirrors tried in order — ports.ubuntu.com is GeoDNS and has
        // occasionally been flaky from certain cloud datacenter IP ranges.
        // us.ports.ubuntu.com is a fixed, verified-reachable fallback.
        val mirrors = listOf(
            "http://ports.ubuntu.com/ubuntu-ports",
            "http://us.ports.ubuntu.com/ubuntu-ports"
        )

        exec {
            isIgnoreExitValue = false
            commandLine("bash", "-c", """
                set -uo pipefail
                cd '${tmp.absolutePath}'
                rm -rf icu_ext; mkdir -p icu_ext

                MIRRORS=(${mirrors.joinToString(" ") { "'$it'" }})
                OK=0
                for BASE in "${d}{MIRRORS[@]}"; do
                    echo "=== Trying mirror: ${d}BASE ==="

                    echo "[1/6] Fetching package index..."
                    HTTP=${d}(curl -sS -o Packages.gz -w '%{http_code}' \
                        "${d}BASE/dists/jammy/main/binary-arm64/Packages.gz" || echo "000")
                    echo "      HTTP status: ${d}HTTP, size: ${d}(wc -c < Packages.gz 2>/dev/null || echo 0) bytes"
                    if [ "${d}HTTP" != "200" ]; then
                        echo "      FAILED on this mirror, trying next..."
                        continue
                    fi

                    echo "[2/6] Decompressing index..."
                    gunzip -f Packages.gz || { echo "      gunzip FAILED"; continue; }
                    echo "      Packages file: ${d}(wc -l < Packages) lines"

                    echo "[3/6] Locating libicu70 in index..."
                    DEB_REL=${d}(awk '
                        /^Package: libicu70${'$'}/ { found=1; next }
                        found && /^Filename:/       { print ${'$'}2; exit }
                        /^Package:/                 { found=0 }
                    ' Packages)
                    echo "      Filename field: '${d}DEB_REL'"
                    if [ -z "${d}DEB_REL" ]; then
                        echo "      NOT FOUND in index, trying next mirror..."
                        continue
                    fi

                    echo "[4/6] Downloading .deb..."
                    DEB_URL="${d}BASE/${d}DEB_REL"
                    echo "      URL: ${d}DEB_URL"
                    HTTP=${d}(curl -sS -o libicu70.deb -w '%{http_code}' "${d}DEB_URL" || echo "000")
                    echo "      HTTP status: ${d}HTTP, size: ${d}(wc -c < libicu70.deb 2>/dev/null || echo 0) bytes"
                    if [ "${d}HTTP" != "200" ]; then
                        echo "      Download FAILED, trying next mirror..."
                        continue
                    fi

                    echo "[5/6] Extracting .deb (ar + tar)..."
                    ar x libicu70.deb 2>&1
                    ls -la data.tar.* 2>&1 || echo "      no data.tar.* produced by ar!"
                    if   [ -f data.tar.xz  ]; then tar -xJf data.tar.xz  -C icu_ext/
                    elif [ -f data.tar.zst ]; then zstd -d data.tar.zst --stdout | tar -x -C icu_ext/
                    elif [ -f data.tar.gz  ]; then tar -xzf data.tar.gz  -C icu_ext/
                    else echo "      ERROR: unknown data.tar format"; continue; fi

                    ICU_DIR=${d}(dirname "${d}(find icu_ext/ -name 'libicuuc.so*' | head -1)" 2>/dev/null)
                    echo "      ICU dir found: '${d}ICU_DIR'"
                    if [ -z "${d}ICU_DIR" ] || [ "${d}ICU_DIR" = "." ]; then
                        echo "      libicuuc.so NOT found after extraction, trying next mirror..."
                        continue
                    fi

                    echo "[6/6] Packaging into icu-libs.tar.gz..."
                    cd "${d}ICU_DIR"
                    ls -la libicu* 2>&1
                    tar -czf '${icuAsset.absolutePath}' libicu*.so*
                    echo "      Done: ${d}(du -h '${icuAsset.absolutePath}' | cut -f1)"
                    OK=1
                    break
                done

                if [ "${d}OK" != "1" ]; then
                    echo "=== ALL MIRRORS FAILED ==="
                    exit 1
                fi
            """.trimIndent())
        }

        if (!icuAsset.exists() || icuAsset.length() < 1_000_000L) {
            throw GradleException(
                "downloadIcuLibs: icu-libs.tar.gz missing or too small after task ran — check log above"
            )
        }
    }
}




// Auto-download proot + icu before every build — safe to run repeatedly (idempotent)
tasks.named("preBuild").configure {
    dependsOn("downloadProotBinary")
    dependsOn("downloadIcuLibs")
}
