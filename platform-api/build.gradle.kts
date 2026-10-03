import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
}

/*
 * ---------------------------------------------------------------------------
 * `:platform-api` - the SPI surface every Minecraft pole implements (contract C10).
 *
 * This subproject is PURE JAVA for the same reason `:core` is (contracts.md 0.3):
 * no game type may appear here either. A pole's adapter module depends on this
 * one and on `:core`; `:core` never depends on this one.
 *
 * Scope note: only `PlatformAdapter` lives here today. `HudRenderer`,
 * `ScreenHost` and `OptOutTransport` are declared in the same C10.1 block but
 * their signatures reference C1/C7/C8 value types (`DrawList`, `HudSurface`,
 * `ScreenModel`) that no agent has delivered yet. Writing those types now would
 * pre-empt the agent that owns them and turn a missing file into a merge fight,
 * so they land with that agent. See README "What comes next".
 * ---------------------------------------------------------------------------
 */

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(providers.gradleProperty("xsoz.javaLanguageRelease").getOrElse("17").toInt())
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
}

dependencies {
    // `:platform-api` declares NO dependency on `:core` yet, precisely so that it
    // compiles from a clean checkout with no ordering dependency on other agents.
    // `api(project(":core"))` is added in the same commit that adds the C1 value
    // types the SPI signatures reference.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
