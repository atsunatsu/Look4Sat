plugins {
    alias(libs.plugins.convention.coreDataPlugin)
}

android {
    namespace = "com.rtbishop.look4sat.core.data"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    // The FT4 native core (Flang/CMake toolchain) is an enhanced-edition feature:
    // basic must not link it, otherwise liblook4sat_ft4.so would be packaged too.
    // (Build scripts sit outside the convention plugin source set, so the flavor
    // configuration is addressed by name — the documented Kotlin DSL form.)
    "jsuenhancedImplementation"(project(":core:ft4-native"))
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val simulatorPort = providers.environmentVariable("L4S_SIMULATOR_PORT")
    inputs.property("l4sSimulatorPort", simulatorPort.orElse(""))
    // A running external C-core simulator must be exercised, not reused from a skipped/cached run.
    outputs.upToDateWhen { simulatorPort.orNull.isNullOrBlank() }
}
