plugins {
    alias(libs.plugins.convention.coreDomainPlugin)
}

dependencies {
    // 编译期使用 org.json(构造/解析 WaveLog API 请求体); 运行时用 Android 系统自带的 org.json
    compileOnly("org.json:json:20240303")
}
