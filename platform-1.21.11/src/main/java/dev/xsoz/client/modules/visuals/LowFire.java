package dev.xsoz.client.modules.visuals;

import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.setting.NumberSetting;

/** Lowers the first-person fire overlay so burning doesn't hide the fight. */
public final class LowFire extends Module {
    public static LowFire INSTANCE;
    private final NumberSetting height = add(new NumberSetting("Lower by", "How far to push the flames down", 0.3, 0.0, 0.6, 0.05));

    public LowFire() {
        super("Low Fire", "Lower the burning overlay", Category.VISUALS, true);
        INSTANCE = this;
    }

    public float offset() { return isEnabled() ? height.floatValue() : 0f; }
}
