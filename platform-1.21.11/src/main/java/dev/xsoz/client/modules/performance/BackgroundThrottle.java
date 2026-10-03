package dev.xsoz.client.modules.performance;

import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.setting.NumberSetting;
import net.minecraft.client.option.InactivityFpsLimit;

/**
 * Gives the rest of the computer its CPU and GPU back when you tab out: the game drops to a low
 * frame cap while unfocused (and to vanilla's minimum while minimised), and snaps back the moment
 * you click in. Uses vanilla's inactivity limiter plus a focus check.
 */
public final class BackgroundThrottle extends Module {
    public static BackgroundThrottle INSTANCE;
    private final NumberSetting unfocusedFps = add(new NumberSetting("Unfocused FPS", "Frame cap while the window is not focused", 30, 5, 120, 5));
    private InactivityFpsLimit previous;

    public BackgroundThrottle() {
        super("Background Throttle", "Low FPS when you alt-tab, full FPS when you come back", Category.PERFORMANCE, true);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        if (mc.options == null) return;
        previous = mc.options.getInactivityFpsLimit().getValue();
        mc.options.getInactivityFpsLimit().setValue(InactivityFpsLimit.AFK);
    }

    @Override
    public void onClientReady() {
        if (previous == null) onEnable();
    }

    @Override
    protected void onDisable() {
        if (mc.options != null && previous != null) mc.options.getInactivityFpsLimit().setValue(previous);
    }

    /** Frame cap override for the frame limiter mixin, or -1 for none. */
    public int capOrNone() {
        if (!isEnabled() || mc.isWindowFocused()) return -1;
        return unfocusedFps.intValue();
    }
}
