plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.roaming"
}

dependencies {
    implementation(project(":core:data"))
}
