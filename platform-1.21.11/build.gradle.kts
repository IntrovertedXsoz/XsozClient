/*
 * :platform-1.21.11 - the XsozClient in-game mod (Fabric, Minecraft 1.21.11).
 *
 * Everything Minecraft-facing lives here and ONLY here. `:core` keeps its zero-game-types rule.
 * Versions match what the launcher provisions into the isolated instance:
 *   Fabric Loader 0.19.5 · Fabric API 0.141.6+1.21.11 · Yarn 1.21.11+build.6 · Java 21.
 */
plugins {
    id("fabric-loom") version "1.18.2"
}

base {
    archivesName.set("xsozclient")
}

version = "0.2.0+mc1.21.11"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings("net.fabricmc:yarn:1.21.11+build.6:v2")
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Runs tests inside Fabric's class loader with every mixin applied - MixinsApplyTest uses it to
    // prove each injection target resolves without opening a game window.
    testImplementation("net.fabricmc:fabric-loader-junit:0.19.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/*
 * Server GameTests (src/gametest): a real headless dedicated server - no window - that runs the
 * training drills in a real world with a FakePlayer: arenas are built, best spots are computed with
 * real exposure raycasts, scoring and world restore are checked. Wired into `check` by Loom.
 */
fabricApi {
    configureTests {
        createSourceSet.set(true)
        modId.set("xsozclient-gametest")
        eula.set(true)
        enableGameTests.set(true)
        enableClientGameTests.set(true)
    }
}

// The client GameTest drives real fights (explosions, difficulty changes); Fabric's packet
// synchronizer trips on those, and its own error message says to switch it off.
loom {
    runs.matching { it.name == "clientGameTest" }.configureEach {
        vmArg("-Dfabric.client.gametest.disableNetworkSynchronizer=true")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("fabric.mod.json") {
        expand("version" to v)
    }
}

tasks.test {
    useJUnitPlatform()
}

/*
 * The launcher embeds the mod jar (Assets\Mod\xsozclient.jar -> EmbeddedResource "XsozMod.jar")
 * and writes it into the isolated instance on provisioning. Every build refreshes that copy, so
 * a launcher build can never ship a stale mod.
 */
val deployToLauncher = tasks.register<Copy>("deployToLauncher") {
    group = "build"
    description = "Copies the remapped mod jar into the launcher's embedded assets."
    from(tasks.named("remapJar"))
    into(rootProject.file("launcher/src/Xsoz.Launcher/Assets/Mod"))
    rename { "xsozclient.jar" }
}

tasks.named("build") {
    finalizedBy(deployToLauncher)
}
