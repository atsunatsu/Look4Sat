pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Look4Sat"
include(":app")
include(
    ":core:data",
    ":core:domain",
    ":core:presentation"
)
include(
    ":feature:cw",
    ":feature:map",
    ":feature:mutual",
    ":feature:passes",
    ":feature:radar",
    ":feature:roaming",
    ":feature:satellites",
    ":feature:settings",
    ":feature:status"
)
