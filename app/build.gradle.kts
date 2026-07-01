plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.clipcc"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.clipcc"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // ONNX Runtime: engine inference, used by src/main (OrtTower/Engine) and androidTest.
    // Native .so is bundled into the app APK. (Was androidTestImplementation in Phase 0 spikes.)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
    implementation(libs.media3.inspector.frame)
    // Plan 3 UI: ViewModel + Compose state + SavedStateHandle (pinned to the catalog lifecycle 2.6.1)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// --- Classify model auto-provisioning -------------------------------------------------------------
// Classify reads its SigLIP2 bundles from the app's INTERNAL filesDir/models, which Android DELETES on
// every uninstall — including the uninstall `connectedAndroidTest` performs when it finishes, and any
// manual `adb uninstall`. The ~6.6 GB of bundles persist at /data/local/tmp/clipcc_models (survives
// uninstall; only a factory reset clears it). This task restores any MISSING bundle after an install so
// the model picker is never empty again. It runs after `installDebug`.
//
// Must copy AS THE APP UID via `run-as`: on Android 13+ (fuse-bpf) files that plain `adb shell cp` writes
// into the app's dirs are owned by `shell` and unreadable by the app. `run-as` runs as the app uid, so
// files land app-owned. The staging is chmod'd world-readable first so the app uid can read it.
// ponytail: `run-as test -d` guard copies only absent bundles — instant on a keep-data reinstall, a full
// re-copy only after an actual wipe. Never fails the build (no device / no adb / no staged source -> skip).
val clipccModelBundles = listOf(
    "siglip2-base-patch16-256", "siglip2-base-patch16-384",
    "siglip2-large-patch16-384", "siglip2-so400m-patch14-384",
)
val provisionModels = tasks.register<Exec>("provisionModels") {
    group = "install"
    description = "Restore missing Classify model bundles into the app's internal filesDir via run-as (from /data/local/tmp/clipcc_models)"
    val androidSdk = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")?.trim()
        ?: "${System.getProperty("user.home")}/Library/Android/sdk"
    val adb = "$androidSdk/platform-tools/adb"
    val pkg = "com.example.clipcc"
    val src = "/data/local/tmp/clipcc_models"
    val dst = "/data/data/$pkg/files/models"
    val names = clipccModelBundles.joinToString(" ")
    commandLine(
        adb, "shell",
        "if [ ! -d $src ]; then echo 'clipcc: no staged bundles at $src - skip provisioning'; exit 0; fi; " +
            "chmod -R o+rX $src; " +
            "run-as $pkg mkdir -p $dst || { echo 'clipcc: run-as failed (app not installed/debuggable?)'; exit 0; }; " +
            "for m in $names; do " +
            "if run-as $pkg test -d $dst/\$m; then echo \"clipcc: present \$m\"; " +
            "else run-as $pkg cp -r $src/\$m $dst/ && echo \"clipcc: provisioned \$m\"; fi; done",
    )
    isIgnoreExitValue = true
}
tasks.matching { it.name == "installDebug" }.configureEach { finalizedBy(provisionModels) }