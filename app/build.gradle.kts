plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/** Builds web/ (Vite + React + assistant-ui) into app/src/main/assets/web. */
val buildWeb by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the assistant-ui WebView bundle with npm + Vite"
    workingDir = rootProject.file("web")
    // Keep the committed assets as a fallback when node/npm is unavailable.
    commandLine(
        "bash", "-c",
        """
        if ! command -v npm >/dev/null 2>&1; then
          echo "[buildWeb] npm not found - packaging committed web assets"
          exit 0
        fi
        if [ ! -d node_modules ]; then
          echo "[buildWeb] installing web dependencies (npm ci)"
          if ! npm ci --no-audit --no-fund; then
            echo "[buildWeb] npm ci failed - packaging committed web assets"
            exit 0
          fi
        fi
        if ! npm run build; then
          echo "[buildWeb] web build failed - packaging committed web assets"
        fi
        exit 0
        """.trimIndent(),
    )
}

tasks.named("preBuild").configure { dependsOn(buildWeb) }

android {
    namespace = "io.nous.andagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.nous.andagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.8.0"
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Shizuku: shell-uid process execution (screen recording). Apache-2.0.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Health Connect step data.
    // NOTE: 1.2.0-alpha06 (mentioned in the brief) declares minCompileSdk=37 and
    // AGP 9.1.0; this project is compileSdk 34 / AGP 8.7.3. 1.1.0-alpha07 is the
    // newest connect-client whose AAR metadata still allows compileSdk 34 and
    // exposes the same StepsRecord/PermissionController APIs used here.
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
}
