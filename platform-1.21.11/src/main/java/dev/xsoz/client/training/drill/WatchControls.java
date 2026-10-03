package dev.xsoz.client.training.drill;

import dev.xsoz.client.training.TrainingManager;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Watch mode's keys (client thread): left / right arrow picks the bot to watch, up / down the view
 * (through its eyes, from behind, free camera). They're normal key bindings, so they can be changed
 * in Controls. The choice runs on the game's server thread; "from behind" is the client's own third
 * person, put back when you leave it.
 */
public final class WatchControls {
    private static KeyBinding prev;
    private static KeyBinding next;
    private static KeyBinding viewPrev;
    private static KeyBinding viewNext;
    private static FreeRoamDrill.View applied;

    private WatchControls() { }

    public static void register(KeyBinding.Category category) {
        prev = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.xsoz.watch.prev", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, category));
        next = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.xsoz.watch.next", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, category));
        viewPrev = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.xsoz.watch.view_prev", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UP, category));
        viewNext = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.xsoz.watch.view_next", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, category));
    }

    /** Presses since the last tick (always read, so old presses don't fire later). */
    private static int presses(KeyBinding k) {
        int n = 0;
        while (k != null && k.wasPressed()) n++;
        return n;
    }

    public static void tick(MinecraftClient mc) {
        int left = presses(prev);
        int right = presses(next);
        int up = presses(viewPrev);
        int down = presses(viewNext);
        FreeRoamDrill fr = TrainingManager.active() instanceof FreeRoamDrill d && d.config().watch && d.live() ? d : null;
        if (fr == null) {
            // left watch mode while looking from behind: back to normal
            if (applied == FreeRoamDrill.View.BEHIND && mc.options.getPerspective() == Perspective.THIRD_PERSON_BACK) {
                mc.options.setPerspective(Perspective.FIRST_PERSON);
            }
            applied = null;
            return;
        }
        var server = mc.getServer();
        if (server != null && left + right + up + down > 0) {
            server.execute(() -> {
                for (int i = 0; i < left; i++) fr.watchNext(-1);
                for (int i = 0; i < right; i++) fr.watchNext(1);
                for (int i = 0; i < up; i++) fr.watchView(-1);
                for (int i = 0; i < down; i++) fr.watchView(1);
            });
        }
        // the view decides first or third person (only when it changes, so F5 still works)
        FreeRoamDrill.View v = fr.view();
        if (v != applied) {
            mc.options.setPerspective(v == FreeRoamDrill.View.BEHIND ? Perspective.THIRD_PERSON_BACK : Perspective.FIRST_PERSON);
            applied = v;
        }
    }
}
