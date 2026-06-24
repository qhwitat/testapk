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

        if (!prootOk)  extractFromDeb("proot",     "proot",         prootDest)
        if (!tallocOk) extractFromDeb("libtalloc", "libtalloc.so.2", tallocDest)
    }
}


// Auto-download proot before every build — safe to run repeatedly (idempotent)
tasks.named("preBuild").configure {
    dependsOn("downloadProotBinary")
}
