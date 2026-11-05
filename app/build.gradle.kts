plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ph.edu.mmsu.ee.grades"
    compileSdk = 35

    defaultConfig {
        applicationId = "ph.edu.mmsu.ee.grades"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        // ---------------------------------------------------------------
        // The only line you must edit before the app will talk to anything.
        // Paste the /exec URL of your Apps Script web app deployment here.
        // Manage deployments -> the deployment -> Web app -> URL.
        // ---------------------------------------------------------------
        buildConfigField(
            "String",
            "API_URL",
            "\"${project.findProperty("EE_API_URL") ?: "https://script.google.com/macros/s/PASTE_YOUR_DEPLOYMENT_ID/exec"}\""
        )
    }

    // The permanent release key lives in GitHub secrets, never in the repo.
    // The release workflow decodes it to a temp file and points EE_KEYSTORE_PATH
    // at it. Without it (local builds), release builds come out unsigned.
    val releaseKeystore = System.getenv("EE_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("EE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EE_KEY_ALIAS")
                keyPassword = System.getenv("EE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Test builds install next to the real app instead of clashing with it.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Minification stays off: the release is the same code that was
            // tested as a debug build, just signed with the permanent key.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}
