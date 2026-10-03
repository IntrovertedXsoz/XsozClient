pluginManagement {
    repositories {
        // Fabric's maven carries Loom. Only the platform pole resolves anything from it;
        // `:core` stays Minecraft-free and its own verify tasks still prove that.
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // ---------------------------------------------------------------------------
    // JDK auto-provisioning.
    //
    // The machine currently has ONE usable JDK family for this build (JDK 25), and
    // that is exactly why this plugin is here. Per docs/toolchain.md the product
    // eventually needs FOUR JDKs in ONE Gradle build:
    //
    //   JDK 8   -> the 1.8.9 pole          (Legacy Fabric 0.19.3, Legacy Yarn 604)
    //   JDK 17  -> core's language level   (this repo today)
    //   JDK 21  -> the 1.21.11 pole        (Fabric 0.19.5, Yarn 1.21.11+build.6)
    //   JDK 25  -> the 26.x pole           (Fabric 0.19.5, unobfuscated)
    //
    // With this plugin applied, `java.toolchain.languageVersion = JavaLanguageVersion.of(8)`
    // and `of(21)` resolve automatically the moment the platform subprojects declare
    // them, instead of every agent hand-installing a JDK. Nothing is downloaded today
    // because no toolchain is requested yet: `:core` compiles on the JDK that runs
    // Gradle, with `options.release = 17`.
    // ---------------------------------------------------------------------------
    //
    // The version is spelled out here rather than read from `libs`: Gradle's
    // settings `plugins {}` block is compiled before the version catalog accessor
    // exists, so `alias(libs.plugins.foojay.resolver.convention)` fails to
    // compile with `Unresolved reference 'libs'`. `gradle/libs.versions.toml`
    // (foojayResolver = "1.0.0") stays the declared source of truth; this line
    // must be kept in step with it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_PROJECT
    repositories {
        mavenCentral()
    }
}

rootProject.name = "xsozclient"

// `:core`        - pure Java, zero game imports, ALL logic. Contracts C2/C4/C5/C6/C7/C8/C9.
// `:platform-api`- the SPI surface every Minecraft pole implements. Contract C10.
// The 1.8.9 and 1.21.11 poles do NOT exist yet - see README "What comes next".
include(":core")
include(":platform-api")

// `:platform-1.21.11` - the in-game Fabric mod for Minecraft 1.21.11 (Fabric Loader 0.19.5,
// Fabric API 0.141.6+1.21.11, Yarn 1.21.11+build.6, JDK 21). Produces the jar the launcher
// drops into the isolated instance's mods folder.
include(":platform-1.21.11")
