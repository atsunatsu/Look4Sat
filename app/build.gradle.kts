import java.util.Properties

plugins {
    alias(libs.plugins.convention.applicationPlugin)
}

// Load signing config from keystore.properties (gitignored, never commit credentials)
val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

android {
    // CW 解码 native 库仅 armeabi-v7a(照搬 Morse Expert 1.15): 全 ABI 打包会在
    // arm64 设备 loadLibrary 失败, 强制 32 位兼容(用户设备为 32 位软件)
    defaultConfig {
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
    }
    signingConfigs {
        if (keystoreProperties["storeFile"] != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
}
