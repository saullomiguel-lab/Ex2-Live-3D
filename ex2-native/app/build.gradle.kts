plugins {
    id("com.android.application")
}

val filament = "1.77.3"

android {
    namespace = "br.com.ex2.live3d"
    compileSdk = 37

    defaultConfig {
        applicationId = "br.com.ex2.live3d"
        // central IHU629G: Android 9 (API 28)
        minSdk = 28
        targetSdk = 28
        versionCode = 47
        versionName = "2.9.0"
        // a central é arm64 (MediaTek MT8666): sem bibliotecas das outras arquiteturas
        ndk {
            abiFilters += "arm64-v8a"
            // teste no emulador do PC: gradle -Pemu
            if (project.hasProperty("emu")) abiFilters += "x86_64"
        }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            // assinado depois com a chave de plataforma (build.ps1)
            signingConfig = null
        }
    }

    androidResources {
        noCompress += listOf("glb", "jpg", "ttf")
    }

    lint {
        // targetSdk 28 é exigência da central, não descuido
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("com.google.android.filament:filament-android:$filament")
    implementation("com.google.android.filament:gltfio-android:$filament")
    implementation("com.google.android.filament:filament-utils-android:$filament")
    implementation("com.google.android.filament:filamat-android:$filament")
}
