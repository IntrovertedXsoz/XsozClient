package dev.xsoz.client.modules.visuals;

import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;

/** No camera shake when you take damage - your aim stays where you put it. */
public final class NoHurtCam extends Module {
    public static volatile boolean active;

    public NoHurtCam() {
        super("No Hurt Cam", "Remove the camera tilt when you are hit", Category.VISUALS, true);
    }

    @Override
    protected void onEnable() { active = true; }

    @Override
    protected void onDisable() { active = false; }
}
