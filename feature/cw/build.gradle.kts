import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.CanProduceConsumerProguardFiles

plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.cw"
    compileOptions {
        encoding = "UTF-8"
    }
    // 照搬 Morse Expert 1.15: native 解码器仅 armeabi-v7a
    defaultConfig {
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
    }
}

// CW 类保持规则(JNI 按类名注册 + 照搬混淆类保逻辑), 由 app 的 R8 消费
// AGP 9: consumer 规则走 variant 级 CanProduceConsumerProguardFiles
androidComponents {
    onVariants(selector().all()) { variant ->
        (variant as? CanProduceConsumerProguardFiles)?.consumerProguardFiles?.add(
            project.layout.projectDirectory.file("proguard-rules.pro")
        )
    }
}

dependencies {
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
}

// 照搬的 Java 源码含中文注释, 强制 UTF-8 编译(compileOptions 在部分 AGP 版本不生效)
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
