/*
 * XsozClient - root build script.
 *
 * Conventions only. This file must NEVER declare a Minecraft, Fabric, Loom or
 * Architectury dependency: the whole architecture (docs/contracts.md 0.3, C10)
 * is "core sees zero game types", and a root-level dependency would silently
 * put one on every subproject's classpath.
 */

plugins {
    base
}

allprojects {
    group = "dev.xsoz"
    version = "0.1.0-SNAPSHOT"
}

/*
 * Convenience aggregate so `./gradlew build` from the root exercises every
 * subproject. `build` already does this via the base plugin, but an explicit
 * alias keeps the CI command readable and stable.
 */
tasks.register("buildAll") {
    group = "build"
    description = "Builds and tests every subproject (same as the root `build` task)."
    dependsOn(subprojects.map { "${it.path}:build" })
}
