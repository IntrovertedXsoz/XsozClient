package dev.xsoz.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Loads every class a mixin targets through Fabric's Knot class loader (fabric-loader-junit), which
 * applies the mixins as the class is defined. With "defaultRequire": 1 any injection whose target
 * method or descriptor does not resolve throws here - the same failure that would otherwise crash
 * the game on start-up, caught without opening a window.
 */
class MixinsApplyTest {
    private static final List<String> TARGETS = List.of(
            "net.minecraft.client.MinecraftClient",
            "net.minecraft.client.gui.widget.PressableWidget",
            "net.minecraft.client.render.GameRenderer",
            "net.minecraft.client.render.item.HeldItemRenderer",
            "net.minecraft.client.gui.hud.InGameOverlayRenderer",
            "net.minecraft.client.network.ClientPlayNetworkHandler",
            "net.minecraft.client.network.ClientPlayerInteractionManager",
            "net.minecraft.client.option.SimpleOption",
            "net.minecraft.client.option.InactivityFpsLimiter",
            "net.minecraft.client.option.GameOptions",
            "net.minecraft.entity.decoration.EndCrystalEntity",
            "net.minecraft.block.RespawnAnchorBlock");

    @Test
    void everyMixinTargetLoadsWithItsMixinApplied() {
        ClassLoader loader = MixinsApplyTest.class.getClassLoader(); // Knot (the context loader is the app loader)
        for (String target : TARGETS) {
            Class<?> c = assertDoesNotThrow(() -> Class.forName(target, false, loader), target);
            assertTrue(c.getName().equals(target));
        }
    }

    @Test
    void mixinsWereActuallyMerged() throws Exception {
        ClassLoader loader = MixinsApplyTest.class.getClassLoader(); // Knot (the context loader is the app loader)
        // Each mixin adds a uniquely named handler method to its target; its presence proves the
        // mixin ran (not merely that the class loaded).
        assertHas(loader, "net.minecraft.client.gui.widget.PressableWidget", "xsoz$drawButton");
        assertHas(loader, "net.minecraft.client.render.GameRenderer", "xsoz$zoom");
        assertHas(loader, "net.minecraft.client.network.ClientPlayNetworkHandler", "xsoz$status");
        assertHas(loader, "net.minecraft.client.network.ClientPlayNetworkHandler", "xsoz$damage");
        assertHas(loader, "net.minecraft.client.option.InactivityFpsLimiter", "xsoz$unfocusedCap");
        assertHas(loader, "net.minecraft.client.MinecraftClient", "xsoz$replaceTitle");
        assertHas(loader, "net.minecraft.client.render.GameRenderer", "xsoz$noHurtCam");
        assertHas(loader, "net.minecraft.client.render.item.HeldItemRenderer", "xsoz$push");
        assertHas(loader, "net.minecraft.client.render.item.HeldItemRenderer", "xsoz$pop");
        assertHas(loader, "net.minecraft.client.gui.hud.InGameOverlayRenderer", "xsoz$lowFirePush");
        assertHas(loader, "net.minecraft.client.network.ClientPlayerInteractionManager", "xsoz$afterAttack");
        assertHas(loader, "net.minecraft.client.option.SimpleOption", "xsoz$gamma");
        assertHas(loader, "net.minecraft.client.option.GameOptions", "xsoz$suspendGamma");
        assertHas(loader, "net.minecraft.entity.decoration.EndCrystalEntity", "xsoz$trainingCrystal");
        assertHas(loader, "net.minecraft.block.RespawnAnchorBlock", "xsoz$trainingAnchor");
    }

    @Test
    void queuedPressIsSeenByWasPressedExactlyOnce() {
        // Combo Binds queues the real Use/Swap/Attack press through this accessor; the game then
        // drains it with wasPressed() in the same tick.
        var kb = new net.minecraft.client.option.KeyBinding("key.xsoz.test", org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN,
                net.minecraft.client.option.KeyBinding.Category.MISC);
        var acc = (dev.xsoz.client.mixin.KeyBindingAccessor) kb;
        acc.xsoz$setTimesPressed(acc.xsoz$getTimesPressed() + 1);
        assertTrue(kb.wasPressed());
        assertTrue(!kb.wasPressed());
    }

    private static void assertHas(ClassLoader loader, String cls, String prefix) throws Exception {
        Class<?> c = Class.forName(cls, false, loader);
        boolean found = false;
        for (var m : c.getDeclaredMethods()) {
            if (m.getName().contains(prefix)) {
                found = true;
                break;
            }
        }
        assertTrue(found, cls + " has no " + prefix + " - the mixin was not applied");
    }
}
