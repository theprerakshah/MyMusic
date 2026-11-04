import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing credentials live in keystore.properties (git-ignored), so the
// keystore passwords never reach GitHub. Without that file, release builds are
// simply left unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.prerak.mymusic"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.prerak.mymusic"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "TEST_HOOKS", "false")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Shrink + optimize: smaller, faster APK. @JavascriptInterface
            // methods are kept by the default proguard-android-optimize rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Release plus the failure-injection hooks used by scripts/device-test.sh.
        // Signed the same, so it installs over release without losing the login.
        // Never share this build: R8 only removes the hooks from "release".
        create("qa") {
            initWith(getByName("release"))
            buildConfigField("boolean", "TEST_HOOKS", "true")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.media)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
