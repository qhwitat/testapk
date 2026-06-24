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
    description = "Downloads proot aarch64 static binary from Termux packages into jniLibs"
    group       = "setup"

    val prootAsset = project.file("src/main/jniLibs/arm64-v8a/libproot.so")
    outputs.file(prootAsset)

    doLast {
        if (prootAsset.exists() && prootAsset.length() > 100_000L) {
            logger.lifecycle("✅ proot already in jniLibs (${prootAsset.length() / 1024} KB) — skipping")
            return@doLast
        }
        prootAsset.parentFile.mkdirs()

        val tmp  = temporaryDir.also { it.mkdirs() }
        val dest = prootAsset.absolutePath
        val d    = "\$"    // dollar sign — Kotlin triple-quote can't include $ directly

        exec {
            isIgnoreExitValue = false
            commandLine("bash", "-c", """
                set -euo pipefail
                cd '${tmp.absolutePath}'

                echo "▶ Querying latest proot version from Termux packages…"
                curl -fsSL -o Packages \
                    'https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages'

                DEB_REL=${d}(awk '
                    /^Package: proot${d}/   { found=1; next }
                    found && /^Filename:/   { print ${d}2; exit }
                    /^${d}/                 { found=0 }
                ' Packages)

                [ -z "${d}DEB_REL" ] && echo "ERROR: proot not found in Termux package list" && exit 1

                DEB_URL="https://packages.termux.dev/apt/termux-main/${d}{DEB_REL}"
                echo "▶ Downloading ${d}{DEB_URL}…"
                curl -fsSL -o proot.deb "${d}{DEB_URL}"

                echo "▶ Extracting binary from .deb…"
                mkdir -p extracted
                ar x proot.deb

                if   [ -f data.tar.xz  ]; then
                    tar -xJf  data.tar.xz  -C extracted/
                elif [ -f data.tar.zst ]; then
                    command -v zstd >/dev/null 2>&1 \
                        || { echo "ERROR: zstd needed — brew install zstd / sudo apt install zstd"; exit 1; }
                    zstd -d data.tar.zst --stdout | tar -x -C extracted/
                elif [ -f data.tar.gz  ]; then
                    tar -xzf  data.tar.gz  -C extracted/
                else
                    echo "ERROR: unknown data.tar format in .deb" && exit 1
                fi

                BIN=${d}(find extracted/ -type f -name proot | head -1)
                [ -z "${d}BIN" ] && echo "ERROR: proot binary not found after extraction" && exit 1

                cp "${d}BIN" '$dest'
                chmod +x '$dest'
                echo "✅ libproot.so ready: $dest (${d}(du -h '$dest' | cut -f1))"
            """.trimIndent())
        }
    }
}

// Auto-download proot before every build — safe to run repeatedly (idempotent)
tasks.named("preBuild").configure {
    dependsOn("downloadProotBinary")
}
