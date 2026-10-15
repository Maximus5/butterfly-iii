import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.github.triplet.play")
}

android {
    namespace = "io.github.maximus5.ffnotifyimporter"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.maximus5.butterflyiii"
        minSdk = 26
        targetSdk = 36
        versionCode = 245
        versionName = "0.245"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Upload key for Google Play (Play App Signing re-signs with its own key). The keystore and
    // its passwords live outside the repo; keystore.properties (gitignored, template in
    // keystore.properties.example) only points at them. Without that file the release build
    // is simply left unsigned, so a fresh checkout still builds.
    val keystoreProperties = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val hasUploadKey = keystoreProperties.containsKey("storeFile")
    if (hasUploadKey) {
        signingConfigs {
            create("upload") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasUploadKey) signingConfig = signingConfigs.getByName("upload")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Auto-create (posting.AutoCreateWorker) - retries a failed Firefly call with backoff, and
    // its ExistingWorkPolicy.APPEND queue is what guarantees two notifications arriving at once
    // never get auto-created in parallel.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    // org.json is part of the Android framework at runtime, but JVM unit tests run against a
    // stub android.jar where its methods throw "not mocked" - this is the real implementation,
    // test-scope only, so FireflyUrl's JSON parsing is actually exercised, not silently defaulted.
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// Uploading the bundle to Google Play (Gradle Play Publisher):
//   ./gradlew publishReleaseBundle                 -> the "internal" track (the default below)
//   ./gradlew publishReleaseBundle --track alpha   -> any other track by its name
// Needs a service account with release rights in the Play Console. Its JSON key lives outside the
// repository; keystore.properties (gitignored, see keystore.properties.example) only points at it
// with playServiceAccountKey. Without that line the build is unaffected and publishing fails with
// a clear "no credentials" error instead.
play {
    track.set("internal")
    defaultToAppBundles.set(true)
    val serviceAccountKey = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }.getProperty("playServiceAccountKey")
    if (serviceAccountKey != null) serviceAccountCredentials.set(file(serviceAccountKey))
}
