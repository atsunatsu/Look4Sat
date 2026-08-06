plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.radar"
}

dependencies {
    implementation(project(":feature:mutual"))
    // CW 解码面板: 复用 feature:cw 的 Morse Expert 引擎(布局 cw_panel_main + MainActivity 控制器)
    implementation(project(":feature:cw"))
    // 与 feature:cw 一致(控制器按 ID 递归查找 ConstraintLayout 视图)
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
}