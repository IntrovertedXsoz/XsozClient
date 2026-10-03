package dev.xsoz.client.modules.visuals;

import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;

/**
 * Maximum brightness. Implemented as a gamma override that is never written to options.txt: the
 * real gamma value stays untouched, and turning this off restores it instantly.
 */
public final class Fullbright extends Module {
    public static volatile boolean active;
    /** True while GameOptions.write runs: the real value must be saved, not the override. */
    public static volatile boolean writing;

    public Fullbright() {
        super("Fullbright", "See in the dark (gamma override, your options stay untouched)", Category.VISUALS, false);
    }

    @Override
    protected void onEnable() { active = true; }

    @Override
    protected void onDisable() { active = false; }
}
