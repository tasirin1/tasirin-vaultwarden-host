import java.time.LocalDateTime
import java.time.ZoneOffset

plugins {
    id("com.android.application")
}

val now = LocalDateTime.now(ZoneOffset.UTC)
val buildDate = "%04d.%02d.%02d".format(now.year, now.monthValue, now.dayOfMonth)
val buildCode = (now.toEpochSecond(ZoneOffset.UTC) / 60).toInt()

android {
    namespace = "com.tasirin.vaultwardenhost"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tasirin.vaultwardenhost"
        minSdk = 21

        targetSdk = 28
        versionCode = buildCode
        versionName = buildDate

        resConfigs("en", "in")

        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    signingConfigs {
        create("release") {
            val storeFileProp = project.findProperty("storeFile") as String?

            val storePasswordProp = System.getenv("KEYSTORE_PASSWORD")
                ?: (project.findProperty("storePassword") as String?)
            val keyAliasProp = System.getenv("KEY_ALIAS")
                ?: (project.findProperty("keyAlias") as String?)
            val keyPasswordProp = System.getenv("KEY_PASSWORD")
                ?: (project.findProperty("keyPassword") as String?)
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

        abortOnError = true

        error += setOf(
            "UnusedResources",
            "UnusedIds",
            "UnusedQuantity",
            "MissingQuantity",

            "ObsoleteSdkInt",
            "DrawAllocation",
            "HandlerLeak",
            "StaticFieldLeak",
            "Recycle",
            "Wakelock"
        )
        disable += setOf(
            "OldTargetApi",
            "ExpiredTargetSdkVersion",
            "GradleDependency",
            "ChromeOsAbiSupport",
            "SdCardPath",
            "ScopedStorage",
            "BatteryLife",
            "UnusedAttribute",
            "ButtonStyle",
            "Autofill"
        )
    }

    packaging {
        resources.excludes += "META-INF/**"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
