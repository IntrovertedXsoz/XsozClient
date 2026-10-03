package dev.xsoz.client.modules.visuals;

import dev.xsoz.client.XsozClient;
import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.render.Anim;
import dev.xsoz.client.setting.BoolSetting;
import dev.xsoz.client.setting.NumberSetting;

/** Hold the Zoom key (default C, rebindable in Controls) to zoom in smoothly. */
public final class Zoom extends Module {
    public static Zoom INSTANCE;
    private final NumberSetting factor = add(new NumberSetting("Factor", "How far to zoom in", 4, 1.5, 10, 0.5, "x"));
    private final BoolSetting smooth = add(new BoolSetting("Smooth", "Ease in and out", true));
    private final Anim anim = new Anim(1f, 14f);

    public Zoom() {
        super("Zoom", "Hold C to zoom (rebind under Controls > Xsoz Client)", Category.VISUALS, true);
        INSTANCE = this;
    }

    /** FOV divisor this frame (1 = no zoom). Called from the GameRenderer mixin. */
    public float divisor() {
        boolean held = isEnabled() && mc.currentScreen == null && XsozClient.zoomKey != null && XsozClient.zoomKey.isPressed();
        float target = held ? factor.floatValue() : 1f;
        if (!smooth.on()) {
            anim.snap(target);
            return target;
        }
        return anim.target(target).get();
    }
}
