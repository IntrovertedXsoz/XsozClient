package dev.xsoz.core.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract C10.3 rule 1, the bytecode half: {@code CoreHasNoMinecraftImportsTest}.
 *
 * <p>A constant-pool scan of every compiled {@code .class} in {@code :core}'s MAIN source
 * set. Any class reference whose internal name begins with a game or loader package
 * fails the build.</p>
 *
 * <p><strong>THE SCOPE IS THE MAIN SOURCE SET, DELIBERATELY.</strong> The rule C10 states
 * is "core MAIN code must not reference game or loader types". The test source set is
 * not core main code, and it cannot be included: this class has to spell the forbidden
 * prefixes out to search for them, so those strings live in its own constant pool, and a
 * scan that reached the test output would flag this file for the act of checking it.
 * That is a false positive about a class that references nothing.</p>
 *
 * <p>The Gradle task {@code :core:verifyNoForbiddenGameReferences} additionally greps the
 * raw source of BOTH source sets, which covers a reference in a comment, a javadoc
 * {@code @link} or a string literal - none of which a constant pool can distinguish from
 * a real reference. Between them, the two checks are total.</p>
 *
 * <p>The forbidden prefixes are assembled from fragments on purpose. If they were spelled
 * out literally, this file would trip its own source grep.</p>
 */
class CoreHasNoMinecraftImportsTest {

    private static final String[] FORBIDDEN_INTERNAL_PREFIXES = {
            "net/" + "minecraft",
            "net/" + "minecraftforge",
            "net/" + "neoforged",
            "net/" + "fabricmc",
            "net/" + "architectury",
            "org/" + "legacyfabric",
            "org/" + "spongepowered",
    };

    private static final String GUARD_SIMPLE_NAME = "CoreHasNoMinecraftImportsTest";

    /** Where this guard's own compiled class lives, relative to a scanned root. */
    private static final String GUARD_CLASS_RESOURCE =
            "dev" + File.separator + "xsoz" + File.separator + "core" + File.separator
                    + "architecture" + File.separator + GUARD_SIMPLE_NAME + ".class";

    @Test
    @DisplayName("no compiled core class references a game or loader type")
    void noGameTypesInTheConstantPool() throws IOException {
        List<File> classRoots = mainOutputDirectories();
        assertFalse(classRoots.isEmpty(),
                "No compiled :core main classes were found. This test is looking in "
                        + System.getProperty("java.class.path"));

        List<String> offenders = new ArrayList<String>();
        int scanned = 0;
        for (File root : classRoots) {
            for (File classFile : listFiles(root, ".class")) {
                scanned++;
                byte[] bytes = readAllBytes(classFile);
                String pool = new String(bytes, StandardCharsets.ISO_8859_1);
                for (String prefix : FORBIDDEN_INTERNAL_PREFIXES) {
                    if (pool.contains(prefix)) {
                        offenders.add(classFile.getPath() + " references " + prefix);
                    }
                }
            }
        }
        assertTrue(scanned > 0, "The constant-pool scan found zero .class files to inspect.");
        assertTrue(offenders.isEmpty(),
                "C10 VIOLATION - :core main references game types: " + offenders);
    }

    @Test
    @DisplayName("no core source file mentions a game or loader package")
    void noGameTokensInCoreSources() throws IOException {
        List<File> sourceRoots = coreSourceDirectories();
        assertFalse(sourceRoots.isEmpty(), "No :core source roots were found.");

        List<String> offenders = new ArrayList<String>();
        for (File root : sourceRoots) {
            for (File sourceFile : listFiles(root, ".java")) {
                List<String> lines = Files.readAllLines(sourceFile.toPath(), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    for (String prefix : FORBIDDEN_INTERNAL_PREFIXES) {
                        String dotted = prefix.replace('/', '.');
                        if (lines.get(i).contains(dotted)) {
                            offenders.add(sourceFile.getName() + ":" + (i + 1) + " mentions " + dotted);
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "C10 VIOLATION - :core source mentions game packages: " + offenders);
    }

    @Test
    @DisplayName("the forbidden-prefix list itself is what contract C10.3 names")
    void forbiddenPrefixListIsComplete() {
        assertEquals(
                Arrays.asList(
                        "net/minecraft", "net/minecraftforge", "net/neoforged", "net/fabricmc",
                        "net/architectury", "org/legacyfabric", "org/spongepowered"),
                Arrays.asList(FORBIDDEN_INTERNAL_PREFIXES));
    }

    @Test
    @DisplayName("the scan covers the main source set and never the test source set")
    void scanIsScopedToTheMainSourceSet() throws IOException {
        // A scan that reached build/classes/java/test would flag this class for holding
        // the forbidden prefixes it searches for, so the scope is asserted, not assumed.
        List<File> roots = mainOutputDirectories();
        for (File root : roots) {
            assertFalse(root.getPath().replace('\\', '/').contains("classes/java/test"),
                    "C10.3 guards :core MAIN code. The test source set must not be scanned: "
                            + root.getPath());
            assertFalse(new File(root, GUARD_CLASS_RESOURCE).isFile(),
                    "The guard class itself must be outside every scanned root: " + root.getPath());
        }
    }

    private static List<File> mainOutputDirectories() {
        List<File> roots = new ArrayList<File>();
        String[] candidates = {
                "build/classes/java/main",
                "build/classes/kotlin/main",
        };
        Path projectDir = locateProjectDir();
        if (projectDir == null) {
            return roots;
        }
        for (String candidate : candidates) {
            File dir = projectDir.resolve(candidate).toFile();
            if (dir.isDirectory()) {
                roots.add(dir);
            }
        }
        return roots;
    }

    private static List<File> coreSourceDirectories() {
        List<File> roots = new ArrayList<File>();
        Path projectDir = locateProjectDir();
        if (projectDir == null) {
            return roots;
        }
        roots.add(projectDir.resolve("src/main/java").toFile());
        roots.add(projectDir.resolve("src/test/java").toFile());
        return roots;
    }

    private static Path locateProjectDir() {
        Path cursor = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 6 && cursor != null; depth++) {
            if (Files.isDirectory(cursor.resolve("src/main/java"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private static List<File> listFiles(File root, String suffix) throws IOException {
        List<File> found = new ArrayList<File>();
        if (!root.isDirectory()) {
            return found;
        }
        try (Stream<Path> walk = Files.walk(root.toPath())) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .forEach(path -> found.add(path.toFile()));
        }
        return found;
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }
}
