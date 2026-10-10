import java.time.LocalDateTime
import java.time.ZoneOffset

plugins {
    id("com.android.application")
}

// Versi aplikasi mengikuti waktu build UTC (konsisten dengan CI).
// versionName "yyyy.MM.dd", versionCode menit-epoch UTC — jangan ubah manual.
// Menit-epoch monotonik dan unik per menit: dua push dalam jam yang sama tetap
// beda versionCode (skema yyyyMMddHH lama tabrakan dalam sejam sehingga
// PackageManager mengabaikan sideload baru). Muat int sampai tahun 6055.
val now = LocalDateTime.now(ZoneOffset.UTC)
val buildDate = "%04d.%02d.%02d".format(now.year, now.monthValue, now.dayOfMonth)
val buildCode = (now.toEpochSecond(ZoneOffset.UTC) / 60).toInt()

android {
    namespace = "com.tasirin.vaultwardenhost"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tasirin.vaultwardenhost"
        minSdk = 21
        // SECURITY-EXCEPTION: targetSdk 28 sengaja dipertahankan (bukan kelalaian) —
        // Android 10+ memblokir execve binary dari app home untuk targetSdk >= 29 (W^X),
        // sehingga binary vaultwarden tak bisa jalan. Jangan naikkan tanpa solusi eksekusi.
        // targetSdk 28 sengaja: agar binary vaultwarden tetap bisa di-execute dari app data
        targetSdk = 28
        versionCode = buildCode
        versionName = buildDate
        // Bahasa default aplikasi Inggris (values/); Indonesia (values-in/)
        // dipertahankan, locale lain dibuang — hemat ukuran.
        // Catatan: resource Indonesia memakai qualifier "in" (bukan "id"),
        // jadi "in" wajib dipertahankan agar values-in/strings.xml tidak dibuang.
        resConfigs("en", "in")
        // Hanya STB 32-bit: kunci ABI ke armeabi-v7a agar APK tetap kecil.
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    signingConfigs {
        create("release") {
            val storeFileProp = project.findProperty("storeFile") as String?
            // Password dibaca dari env dulu agar secret CI tidak tertulis di file/argumen,
            // fallback ke properti Gradle bila env kosong (build lokal/kompatibilitas lama).
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
        // Error menggagalkan build; warning yang disengaja dinonaktifkan.
        abortOnError = true
        // Deteksi kode mati otomatis: resource/id/plurals yatim langsung
        // menggagalkan build CI tiap push (bukan sekadar warning).
        error += setOf(
            "UnusedResources", // drawable/warna/string/gaya yatim
            "UnusedIds",       // android:id tak dirujuk kode/layout lain
            "UnusedQuantity",  // item plurals tak relevan untuk locale
            "MissingQuantity", // plurals tanpa quantity wajib (other)
            // Radar buru-bug (SDK GitHub, bukan lokal): lolos audit manual,
            // jadi dipromosi agar push berikut otomatis gagal bila muncul lagi.
            // Import mati dijaga tools/verifikasi-path.py (cek_import_mati):
            // ID "UnusedImports" tak dikenal lint AGP 8.5.2 (warning CI).
            "ObsoleteSdkInt",  // cek SDK basi (minSdk 21; cek >=M/O masih relevan)
            "DrawAllocation",  // alokasi di onDraw (tanpa custom view)
            "HandlerLeak",     // Handler non-statis (tanpa subclass Handler)
            "StaticFieldLeak", // field statis pegang Context/View (nihil)
            "Recycle",         // TypedArray tanpa recycle (tanpa obtain)
            "Wakelock"         // acquire tanpa timeout (pakai timeout 12 jam)
        )
        disable += setOf(
            "OldTargetApi",   // targetSdk 28 sengaja (eksekusi binary Android 10+)
            "ExpiredTargetSdkVersion", // targetSdk 28 sengaja (W^X); bukan untuk Play Store
            "ChromeOsAbiSupport", // armeabi-v7a saja disengaja (STB 32-bit); bukan untuk Chromebook
            "SdCardPath",     // /sdcard/vaultwarden memang folder data publik bawaan
            "ScopedStorage",  // All-files access disengaja (Android 11+); rilis via GitHub, bukan Play Store
            "BatteryLife",    // tombol "Izinkan" ditekan manual oleh pengguna
            "UnusedAttribute", // usesCleartextTraffic untuk Android 6+ (API 23)
            "CustomX509TrustManager",     // trust anchor tambahan + self-signed lokal (bukan trust-all)
            "ButtonStyle",                // tombol log pakai bg kustom agar terlihat di TV
            "Autofill"                    // kolom pengaturan LAN + PIN; autofill tidak relevan
        )
    }

    packaging {
        resources.excludes += "META-INF/**"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
