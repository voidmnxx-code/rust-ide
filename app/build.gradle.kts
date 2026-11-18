plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.rustpad"
    compileSdk = 35

    defaultConfig {
        // 10 أحرف = نفس طول com.termux، لإعادة كتابة المسارات في حزم Termux دون تغيير الحجم.
        applicationId = "dev.rustpd"
        minSdk = 26
        // targetSdk=28 يسمح بتنفيذ الملفات من مجلد التطبيق (أسلوب Termux).
        // التوزيع عبر GitHub/F-Droid فقط، لا Google Play.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters += "arm64-v8a" } // aarch64 فقط
    }

    // مفتاح توقيع ثابت (من سر GitHub) كي تعمل التحديثات فوق النسخة المثبتة.
    // بدونه يُستخدم مفتاح debug العشوائي الخاص بكل بناء.
    val sharedKs: String? = System.getenv("RUSTPAD_KS_FILE")
    if (sharedKs != null) {
        signingConfigs {
            create("shared") {
                storeFile = file(sharedKs)
                storePassword = "rustpad-ks"
                keyAlias = "rustpad"
                keyPassword = "rustpad-ks"
            }
        }
    }

    buildTypes {
        debug {
            if (sharedKs != null) signingConfig = signingConfigs.getByName("shared")
        }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // يستخرج مكتبات jniLibs إلى nativeLibraryDir (مطلوب لحل التغليف كـ lib*.so لاحقاً)
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.6-4@aar")
}
