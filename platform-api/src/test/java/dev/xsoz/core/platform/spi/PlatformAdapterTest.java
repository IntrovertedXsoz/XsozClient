package dev.xsoz.core.platform.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract C10.1 - the SPI surface exists, is pure Java, and names the lifecycle methods
 * the bootstrap order depends on.
 */
class PlatformAdapterTest {

    @Test
    @DisplayName("the three lifecycle methods are declared with no arguments and no return")
    void lifecycleMethodsExist() {
        for (String name : new String[] {"install", "start", "shutdown"}) {
            Method method = findMethod(name);
            assertEquals(0, method.getParameterCount(), name + "() takes no arguments.");
            assertEquals(void.class, method.getReturnType(), name + "() returns nothing.");
        }
    }

    @Test
    @DisplayName("PlatformAdapter is an interface with no superclass of its own")
    void isAPlainInterface() {
        assertTrue(PlatformAdapter.class.isInterface());
        assertTrue(java.lang.reflect.Modifier.isPublic(PlatformAdapter.class.getModifiers()));
        assertEquals("dev.xsoz.core.platform.spi", PlatformAdapter.class.getPackage().getName());
    }

    @Test
    @DisplayName("no declared method references a game or loader type")
    void noGameTypesInSignatures() {
        for (Method method : PlatformAdapter.class.getDeclaredMethods()) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertFalse(isGameType(parameterType.getName()),
                        method.getName() + " takes " + parameterType.getName());
            }
            assertFalse(isGameType(method.getReturnType().getName()),
                    method.getName() + " returns " + method.getReturnType().getName());
        }
        for (Class<?> field : PlatformAdapter.class.getDeclaredClasses()) {
            assertFalse(isGameType(field.getName()));
        }
        assertEquals(3, PlatformAdapter.class.getDeclaredMethods().length,
                "Only install(), start() and shutdown() are declared today. The C10.1 accessors "
                        + "land with the C1/C7/C8 value types they reference.");
    }

    @Test
    @DisplayName("the interface carries the frozen signature set as documentation")
    void frozenSignatureSetIsDocumented() {
        String javadoc = readJavadocHint();
        assertTrue(javadoc.contains("C10.1"), "The interface must name its contract section.");
    }

    private static boolean isGameType(String internalName) {
        return internalName.startsWith("net.minecraft")
                || internalName.startsWith("net.minecraftforge")
                || internalName.startsWith("net.neoforged")
                || internalName.startsWith("net.fabricmc")
                || internalName.startsWith("org.legacyfabric")
                || internalName.startsWith("org.spongepowered");
    }

    private static Method findMethod(String name) {
        List<Method> matches = new ArrayList<Method>();
        for (Method method : PlatformAdapter.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                matches.add(method);
            }
        }
        assertEquals(1, matches.size(), "Expected exactly one " + name + "(), found " + matches);
        return matches.get(0);
    }

    private static String readJavadocHint() {
        try {
            // The compiled class retains no javadoc, so assert on the source file next to it.
            java.io.File source = new java.io.File(
                    "src/main/java/dev/xsoz/core/platform/spi/PlatformAdapter.java");
            if (!source.isFile()) {
                java.io.File cursor = new java.io.File("").getAbsoluteFile();
                while (cursor != null && !source.isFile()) {
                    source = new java.io.File(
                            cursor, "src/main/java/dev/xsoz/core/platform/spi/PlatformAdapter.java");
                    cursor = cursor.getParentFile();
                }
            }
            assertTrue(source.isFile(), "Could not find PlatformAdapter.java");
            String text = new String(java.nio.file.Files.readAllBytes(source.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            return text;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read PlatformAdapter.java", e);
        }
    }

    @Test
    @DisplayName("no game tokens appear in the SPI source either")
    void noGameTokensInSpiSource() {
        String text = readJavadocHint();
        for (String forbidden : Arrays.asList(
                "net.minecraft", "net.minecraftforge", "net.neoforged", "net.fabricmc",
                "org.legacyfabric", "org.spongepowered")) {
            assertFalse(text.contains(forbidden),
                    "contracts.md 0.3 forbids game package tokens in the shared SPI surface.");
        }
    }
}
