package dev.xsoz.client.modules.movement;

import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.setting.ModeSetting;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.util.InputUtil;

/** Holds your sprint key for you: always, or toggled by tapping it. */
public final class ToggleSprint extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Always sprint, or tap the sprint key to toggle", "Toggle", "Toggle", "Always"));
    private boolean toggled = true;
    private boolean wasDown;

    public ToggleSprint() {
        super("Toggle Sprint", "Sprint without holding the key", Category.MOVEMENT, true);
    }

    @Override
    public void onPreTick() {
        if (!inGame() || mc.currentScreen != null) return;
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(mc.options.sprintKey);
        boolean down = key != null && key.getCategory() == InputUtil.Type.KEYSYM && key.getCode() >= 0
                && InputUtil.isKeyPressed(mc.getWindow(), key.getCode());
        if (mode.is("Toggle") && down && !wasDown) toggled = !toggled;
        wasDown = down;
        boolean sprint = mode.is("Always") || toggled || down;
        mc.options.sprintKey.setPressed(sprint);
    }

    @Override
    protected void onDisable() {
        if (inGame()) mc.options.sprintKey.setPressed(false);
    }

    @Override
    public String suffix() { return mode.is("Always") ? "Always" : toggled ? "On" : "Off"; }
}
