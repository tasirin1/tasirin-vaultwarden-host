import java.time.LocalDate
import java.time.ZoneOffset

plugins {
    id("com.android.application")
}

// Versi aplikasi mengikuti tanggal build (UTC, konsisten dengan CI).
// versionName "yyyy.MM.dd", versionCode "yyyyMMdd" — jangan ubah manual.
val now = LocalDate.now(ZoneOffset.UTC)
val buildDate = "%04d.%02d.%02d".format(now.year, now.monthValue, now.dayOfMonth)
val buildCode = now.year * 10000 + now.monthValue * 100 + now.dayOfMonth

android {
    namespace = "com.tasirin.vaultwardenhost"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tasirin.vaultwardenhost"
        minSdk = 21
        // targetSdk 28 sengaja: agar binary vaultwarden tetap bisa di-execute dari app data
        targetSdk = 28
        versionCode = buildCode
        versionName = buildDate
    }

    // UI memakai Indonesia (default values/); locale pustaka lain dibuang — hemat ukuran.
    androidResources {
        localeFilters += listOf("id")
    }

    signingConfigs {
        create("release") {
            val storeFileProp = project.findProperty("storeFile") as String?
            val storePasswordProp = project.findProperty("storePassword") as String?
            val keyAliasProp = project.findProperty("keyAlias") as String?
            val keyPasswordProp = project.findProperty("keyPassword") as String?
            if (!storeFileProp.isNullOrBlank() && !storePasswordProp.isNullOrBlank() &&
                !keyAliasProp.isNullOrBlank() && !keyPasswordProp.isNullOrBlank()
            ) {
                storeFile = rootProject.file(storeFileProp)
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val signing = signingConfigs.getByName("release")
            if (signing.storeFile != null && signing.storeFile!!.exists()) {
                signingConfig = signing
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // App lawas Java + layout TV: warning tidak menggagalkan build.
        // OldTargetApi dimatikan: targetSdk 28 sengaja (eksekusi binary Android 10+).
        abortOnError = false
        disable += setOf("OldTargetApi")
    }

    packaging {
        resources.excludes += "META-INF/**"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
