plugins {
    alias(libs.plugins.convention.applicationPlugin)
}

android {
    namespace = libs.versions.packageName.get()
    defaultConfig {
        applicationId = "cn.ba7opf.look4sat"
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/my-release-key.jks")
            storePassword = "look4sat123"
            keyAlias = "look4sat"
            keyPassword = "look4sat123"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}