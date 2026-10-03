package dev.xsoz.client.modules.visuals;

import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.setting.BoolSetting;
import dev.xsoz.client.setting.ColorSetting;
import dev.xsoz.client.setting.ModeSetting;
import dev.xsoz.client.setting.NumberSetting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.hit.HitResult;

/** A crisp competitive crosshair that replaces the vanilla one. */
public final class Crosshair extends Module {
    public static Crosshair INSTANCE;
    private final ModeSetting style = add(new ModeSetting("Style", "Shape", "Cross", "Cross", "Dot", "Cross + dot", "Plus"));
    private final NumberSetting length = add(new NumberSetting("Length", "Arm length", 4, 1, 10, 1, "px"));
    private final NumberSetting gap = add(new NumberSetting("Gap", "Centre gap", 2, 0, 8, 1, "px"));
    private final NumberSetting thickness = add(new NumberSetting("Thickness", "Line thickness", 1, 1, 4, 1, "px"));
    private final ColorSetting color = add(new ColorSetting("Color", "Crosshair colour", 0xECEEF2));
    private final BoolSetting outline = add(new BoolSetting("Outline", "Dark outline for contrast", true));
    private final BoolSetting targetColor = add(new BoolSetting("Target colour", "Turn red when aiming at an entity", true));

    public Crosshair() {
        super("Crosshair", "Clean competitive crosshair", Category.VISUALS, true);
        INSTANCE = this;
    }

    /** Returns true when it drew (and the vanilla crosshair should be skipped). */
    public boolean render(DrawContext c) {
        if (!isEnabled()) return false;
        if (mc.options.getPerspective() != Perspective.FIRST_PERSON || mc.options.hudHidden) return true;
        if (mc.player != null && mc.player.isSpectator()) return true;
        int cx = c.getScaledWindowWidth() / 2;
        int cy = c.getScaledWindowHeight() / 2;
        int col = color.argb();
        if (targetColor.on() && mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY) col = 0xFFFF5A5A;
        int t = thickness.intValue();
        int len = length.intValue();
        int g = gap.intValue();
        int half = t / 2;
        String s = style.get();
        boolean cross = s.equals("Cross") || s.equals("Cross + dot") || s.equals("Plus");
        boolean dot = s.equals("Dot") || s.equals("Cross + dot");
        if (s.equals("Plus")) g = 0;
        if (cross) {
            bar(c, cx - g - len, cy - half, len, t, col);
            bar(c, cx + g + (t % 2 == 1 ? 1 : 0), cy - half, len, t, col);
            bar(c, cx - half, cy - g - len, t, len, col);
            bar(c, cx - half, cy + g + (t % 2 == 1 ? 1 : 0), t, len, col);
        }
        if (dot) bar(c, cx - half, cy - half, Math.max(1, t), Math.max(1, t), col);
        return true;
    }

    private void bar(DrawContext c, int x, int y, int w, int h, int col) {
        if (outline.on()) Gfx.rect(c, x - 1, y - 1, w + 2, h + 2, 0xB0000000);
        Gfx.rect(c, x, y, w, h, col);
    }
}
