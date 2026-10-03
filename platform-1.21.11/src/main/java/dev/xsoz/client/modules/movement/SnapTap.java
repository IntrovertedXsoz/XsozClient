package dev.xsoz.client.modules.movement;

import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.setting.BoolSetting;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * Last-input priority for opposite movement keys (like Snappy Tappy). Vanilla cancels A+D out to
 * "standing still"; with SnapTap the key you pressed most recently wins, and releasing it hands
 * control straight back to the one still held. Strafing changes direction with zero dead time.
 *
 * <p>It only decides which of two keys you are physically holding counts - it never presses a key
 * you are not holding.</p>
 */
public final class SnapTap extends Module {
    private final BoolSetting strafe = add(new BoolSetting("Left / Right", "Last pressed wins for A and D", true));
    private final BoolSetting forwardBack = add(new BoolSetting("Forward / Back", "Last pressed wins for W and S", false));

    private final Pair lr = new Pair();
    private final Pair fb = new Pair();

    public SnapTap() {
        super("SnapTap", "Last-pressed movement key wins instead of cancelling out (Snappy Tappy)", Category.MOVEMENT, false);
        contested();
    }

    private static final class Pair {
        boolean aDown;
        boolean bDown;
        long aAt;
        long bAt;
        long clock;
    }

    @Override
    public void onPreTick() {
        if (!inGame() || mc.currentScreen != null) return;
        if (strafe.on()) apply(lr, mc.options.leftKey, mc.options.rightKey);
        if (forwardBack.on()) apply(fb, mc.options.forwardKey, mc.options.backKey);
    }

    private void apply(Pair p, KeyBinding a, KeyBinding b) {
        Boolean aPhys = physical(a);
        Boolean bPhys = physical(b);
        if (aPhys == null || bPhys == null) return; // mouse-bound or unbound: leave vanilla alone
        p.clock++;
        if (aPhys && !p.aDown) p.aAt = p.clock;
        if (bPhys && !p.bDown) p.bAt = p.clock;
        p.aDown = aPhys;
        p.bDown = bPhys;
        if (aPhys && bPhys) {
            boolean aWins = p.aAt >= p.bAt;
            a.setPressed(aWins);
            b.setPressed(!aWins);
        } else {
            a.setPressed(aPhys);
            b.setPressed(bPhys);
        }
    }

    private Boolean physical(KeyBinding kb) {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(kb);
        if (key == null || key.getCategory() != InputUtil.Type.KEYSYM || key.getCode() < 0) return null;
        return InputUtil.isKeyPressed(mc.getWindow(), key.getCode());
    }

    @Override
    protected void onDisable() {
        if (!inGame()) return;
        for (KeyBinding kb : new KeyBinding[] {mc.options.leftKey, mc.options.rightKey, mc.options.forwardKey, mc.options.backKey}) {
            Boolean phys = physical(kb);
            if (phys != null) kb.setPressed(phys);
        }
    }
}
