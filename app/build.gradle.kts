plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.elanticristo616.roku"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.elanticristo616.roku"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.1.0"

        // yt-dlp trae Python compilado por ABI (~15 MB por ABI):
        // solo arm64 y armeabi-v7a cubren prácticamente todos los
        // celulares actuales y mantienen el APK en un tamaño razonable.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("SIGNING_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    dependenciesInfo {
       includeInApk = false
       includeInBundle = false
   }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // yt-dlp en Android (el mismo motor que usa Seal): saca el video
    // directo de un enlace sin descargar nada. GPL-3.0: por eso esta
    // versión de la app es de código abierto.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")

    testImplementation("junit:junit:4.13.2")
}
