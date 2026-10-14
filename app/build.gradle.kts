import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.convention.applicationPlugin)
}

// Release signing credentials live in local.properties (gitignored) or environment
// variables — never hardcode passwords in VCS. Both the fork's historical names
// (RELEASE_*) and the CI names (LOOK4SAT_RELEASE_*) are accepted so that a developer
// machine and GitHub Actions share one code path, and the keystore path defaults to
// the fork's usual ~/my-release-key.jks.
val releaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) FileInputStream(propsFile).use { load(it) }
}

fun releaseCred(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    (releaseProps.getProperty(name) ?: System.getenv(name))?.takeIf { it.isNotBlank() }
}

val releaseStoreFile = releaseCred("LOOK4SAT_RELEASE_STORE_FILE", "RELEASE_STORE_FILE")
    ?: File(System.getProperty("user.home"), "my-release-key.jks").absolutePath
val releaseStorePassword = releaseCred("LOOK4SAT_RELEASE_STORE_PASSWORD", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseCred("LOOK4SAT_RELEASE_KEY_ALIAS", "RELEASE_KEY_ALIAS") ?: "look4sat"
val releaseKeyPassword = releaseCred("LOOK4SAT_RELEASE_KEY_PASSWORD", "RELEASE_KEY_PASSWORD")
val hasReleaseSigning = File(releaseStoreFile).isFile &&
    !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

// The L4S interop build used to ride on its own applicationId via -Pl4sDevelopment.
// That channel is not part of the release scope any more, so the flag must fail
// loudly: Gradle silently ignores unknown -P properties, and an ignored flag would
// produce a stable-package APK that overwrites the installed app without warning.
if (providers.gradleProperty("l4sDevelopment").isPresent) {
    error(
        "l4sDevelopment has been removed. Build the jsuenhanced edition instead " +
            "(see AGENTS.md → 'Dual edition builds')."
    )
}

android {
    namespace = libs.versions.packageName.get()
    defaultConfig {
        // applicationId, versionCode and versionName come from the convention plugin so
        // that both editions stay byte-identical in identity (same package, same version).
        // CW decoder ships for both ABIs (see core/cw jniLibs); the APK must not be pinned
        // to 32-bit only or 64-bit devices fall back to ARM32.
        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            // Local CAT field-test builds only; never set on a real release run.
            if (providers.gradleProperty("catBluetoothTest").orNull == "true") {
                versionNameSuffix = "-ic705-test1"
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

// An unsigned release APK is easy to ship by accident and worthless to users, so a
// release assembly without usable signing credentials stops with an explanation
// instead of quietly emitting an unsigned artifact.
tasks.matching { it.name.startsWith("assemble") && it.name.endsWith("Release") }.configureEach {
    doFirst {
        check(hasReleaseSigning) {
            "Release signing is not configured: set RELEASE_STORE_PASSWORD/RELEASE_KEY_PASSWORD " +
                "(or LOOK4SAT_RELEASE_*) in local.properties or the environment."
        }
    }
}
