import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
}

/*
 * ---------------------------------------------------------------------------
 * `:core` is PURE JAVA.
 *
 *   * zero `net.minecraft.*` imports            (contracts.md 0.3, C10.3 rule 1)
 *   * zero Fabric API imports
 *   * Minecraft-independent, because the two poles (1.8.9 and 1.21.11) share no
 *     mapping system and cannot share a mapped type name (docs/toolchain.md 2.2)
 *
 * Both halves of that rule are ENFORCED, not promised:
 *   1. `verifyNoForbiddenGameReferences`  - fails on a forbidden token in any
 *      core source file, including inside a comment or a string literal, which a
 *      constant-pool scan alone cannot see (C10.3 rule 1, second paragraph).
 *   2. `verifyCompileClasspathIsClean`     - fails if any Minecraft-family
 *      artifact is on compileClasspath or runtimeClasspath (C10.3 rule 2).
 * The JUnit twin `CoreHasNoMinecraftImportsTest` scans the compiled constant pool.
 * ---------------------------------------------------------------------------
 */

val forbiddenReferenceTokens: List<String> = listOf(
    "net.minecraft.",
    "net.minecraftforge.",
    "net.neoforged.",
    "net.fabricmc.",
    "net.architectury.",
    "org.legacyfabric.",
    "org.spongepowered.",
    "org.architectury.",
)

val forbiddenClasspathPrefixes: List<String> = listOf(
    "net.minecraft",
    "net.minecraftforge",
    "net.neoforged",
    "net.fabricmc",
    "net.legacyfabric",
    "net.architectury",
    "org.spongepowered",
    "org.architectury",
)

val forbiddenClasspathGroups: List<String> = listOf(
    "net.minecraft",
    "net.minecraftforge",
    "net.neoforged",
    "net.fabricmc",
    "net.legacyfabric",
    "net.architectury",
    "org.spongepowered",
    "org.architectury",
)

val coreSourceRoots: List<File> = listOf(
    layout.projectDirectory.dir("src/main/java").asFile,
    layout.projectDirectory.dir("src/test/java").asFile,
)

/*
 * Rule 1 of C10.3, source half: a forbidden token anywhere in a core source file
 * fails the build. This is deliberately stricter than the bytecode scan, because
 * a reference in a comment, a javadoc `@link` or a string literal is invisible
 * to the constant pool and would otherwise be able to smuggle a game type into a
 * core comment as "just a reference".
 */
val verifyNoForbiddenGameReferences = tasks.register("verifyNoForbiddenGameReferences") {
    group = "verification"
    description = "Fails if a Minecraft-family package token appears in any :core source file."

    val roots = coreSourceRoots
    val tokens = forbiddenReferenceTokens
    inputs.files(roots.map { fileTree(it) { include("**/*.java") } })
    // Never cacheable: this is an architectural gate, not a build optimisation.
    outputs.upToDateWhen { false }

    doLast {
        val offenders = mutableListOf<String>()
        roots.forEach { root ->
            if (!root.isDirectory) {
                return@forEach
            }
            root.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { file ->
                    val relative = file.relativeTo(root).path.replace('\\', '/')
                    file.readLines().forEachIndexed { index, line ->
                        tokens.forEach { token ->
                            if (line.contains(token)) {
                                offenders += "${file.path}:${index + 1}  [$token]  ${line.trim()}"
                            }
                        }
                    }
                }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("C10 VIOLATION: :core must contain zero game references (contracts.md 0.3, C10.3).")
                    appendLine("Found ${offenders.size} occurrence(s):")
                    offenders.forEach { appendLine("  $it") }
                    appendLine("Move the code to the platform subproject, or delete the reference.")
                }
            )
        }
        logger.lifecycle("C10 source gate: :core has zero game references. OK")
    }
}

/*
 * Rule 2 of C10.3: the `:core` subproject must declare none of those artifacts on
 * `compileClasspath` or `runtimeClasspath`, in any configuration, test
 * configurations included.
 */
val verifyCompileClasspathIsClean = tasks.register("verifyCompileClasspathIsClean") {
    group = "verification"
    description = "Fails if a Minecraft-family artifact is on :core's compile or runtime classpath."

    val configurationNames = listOf(
        "compileClasspath",
        "runtimeClasspath",
        "testCompileClasspath",
        "testRuntimeClasspath",
    )
    val groups = forbiddenClasspathGroups
    val prefixes = forbiddenClasspathPrefixes
    // Resolving every classpath is what makes this check real: an undeclared
    // transitive edge still shows up here.
    val classpathConfigurations = configurationNames.map { configurations.getByName(it) }
    inputs.files(classpathConfigurations)
    outputs.upToDateWhen { false }

    doLast {
        val offenders = sortedSetOf<String>()
        classpathConfigurations.forEach { classpath ->
            // `resolvedArtifacts` is the FULL transitive closure, so an artifact that
            // only arrives via some other module's dependency edge is still seen.
            classpath.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                val module = artifact.moduleVersion.id
                if (module.group in groups || prefixes.any { module.name.startsWith(it) }) {
                    offenders += "${module.group}:${module.name}"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("C10 VIOLATION: :core must declare zero game dependencies (contracts.md C10.3 rule 2).")
                    appendLine("Offending coordinates: ${offenders.joinToString(", ")}")
                    appendLine("Game types belong in the platform subprojects, never in :core.")
                }
            )
        }
        logger.lifecycle("C10 classpath gate: :core declares no game dependencies. OK")
    }
}

java {
    /*
     * SCOPE DECISION (recorded here, see README "Deviations from contracts.md"):
     *
     * contracts.md 0.2 requires :core to compile to Java 8 bytecode using JDK-8 APIs,
     * because core.jar runs inside the Java 8 JVM of the 1.8.9 pole. This build
     * targets the Java 17 LANGUAGE LEVEL instead, because Java 8 toolchains are not
     * installed on the machine and the point of this task is that `./gradlew build`
     * and the test suite work TODAY with the installed JDK 25 and no toolchain
     * download. `CoreIsJava8BytecodeTest` and `CoreUsesNoJava9PlusApisTest` are
     * therefore NOT satisfiable yet; they are re-enabled when the platform modules
     * land and foojay can provision JDK 8 on demand.
     *
     * No `java.toolchain` block is declared here on purpose: declaring one would
     * make Gradle hunt for a matching JDK. `:core` compiles on the JDK that runs
     * Gradle, with `--release 17` giving a genuine Java 17 language level and a
     * genuine Java 17 API surface check.
     */
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(providers.gradleProperty("xsoz.javaLanguageRelease").getOrElse("17").toInt())
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
}

dependencies {
    // Boundary for this task: JUnit 5 and nothing else. No Minecraft, no Fabric,
    // no Loom, no logging library - SLF4J arrives with the platform modules.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
    // A silent test run is a lie. If nothing ran, fail the build.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = Unit

        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null && result.testCount == 0L) {
                throw GradleException("The :core test suite executed ZERO tests - refusing to pass.")
            }
        }

        override fun beforeTest(testDescriptor: TestDescriptor) = Unit

        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) = Unit
    })
}

tasks.check {
    dependsOn(verifyNoForbiddenGameReferences, verifyCompileClasspathIsClean)
}

/** Convenience alias: source + bytecode form of the same architectural rule. */
tasks.register("c10Clean") {
    group = "verification"
    description = "Runs both C10.3 core-cleanliness gates."
    dependsOn(verifyNoForbiddenGameReferences, verifyCompileClasspathIsClean, tasks.named("test"))
}
