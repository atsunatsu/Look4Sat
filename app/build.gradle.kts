import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.convention.applicationPlugin)
}

// Release signing credentials live in local.properties (gitignored) or
// environment variables — never hardcode passwords in VCS.
val releaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) FileInputStream(propsFile).use { load(it) }
}
fun releaseCred(name: String): String =
    releaseProps.getProperty(name) ?: System.getenv(name) ?: ""

android {
    namespace = libs.versions.packageName.get()
    defaultConfig {
        applicationId = "cn.ba7opf.look4sat"
        // CW decoder ships for both ABIs (see feature/cw jniLibs); the APK must
        // not be pinned to 32-bit only or 64-bit devices fall back to ARM32.
        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/my-release-key.jks")
            storePassword = releaseCred("RELEASE_STORE_PASSWORD")
            keyAlias = releaseCred("RELEASE_KEY_ALIAS").ifEmpty { "look4sat" }
            keyPassword = releaseCred("RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}