plugins {
    alias(libs.plugins.convention.coreDataPlugin)
}

android {
    namespace = "com.rtbishop.look4sat.core.data"
    testOptions.unitTests.isReturnDefaultValues = true
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val simulatorPort = providers.environmentVariable("L4S_SIMULATOR_PORT")
    val sbcOracle = providers.environmentVariable("N76_SBC_ORACLE_PATH")
    inputs.property("l4sSimulatorPort", simulatorPort.orElse(""))
    inputs.property("n76SbcOracle", sbcOracle.orElse(""))
    outputs.upToDateWhen { simulatorPort.orNull.isNullOrBlank() && sbcOracle.orNull.isNullOrBlank() }
}
