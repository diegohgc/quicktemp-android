plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.diegohg.quicktemp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.diegohg.quicktemp"
        minSdk = 23
        targetSdk = 36
        versionCode = 10
        versionName = "1.9.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../quicktemp.jks")
            storePassword = System.getenv("QUICKTEMP_STORE_PASS") ?: project.findProperty("QUICKTEMP_STORE_PASS") as String? ?: ""
            keyAlias = "quicktemp"
            keyPassword = System.getenv("QUICKTEMP_KEY_PASS") ?: project.findProperty("QUICKTEMP_KEY_PASS") as String? ?: ""
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
            }
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
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.play.services.ads)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
