package dev.xsoz.client.trainer;

import dev.xsoz.client.render.Anim;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import net.minecraft.client.gui.DrawContext;

/**
 * Draws the active cue: a coloured border around the whole screen (each task has its own colour)
 * and a compact instruction chip at the top. Fades and cross-fades; never covers the crosshair.
 */
public final class CueOverlay {
    private final Anim alpha = new Anim(0f, 10f);
    private final Anim slide = new Anim(0f, 12f);
    private CueSpec shown;
    private int fromColor = Theme.ACCENT;
    private int toColor = Theme.ACCENT;
    private long colorChangedAt;

    public void render(DrawContext c, CueSpec active, String kicker, int thickness, boolean glow, boolean label, boolean pulse) {
        if (active != null && active != shown) {
            fromColor = shown == null ? active.color() : currentColor();
            toColor = active.color();
            colorChangedAt = System.currentTimeMillis();
            shown = active;
            slide.snap(0f);
        }
        alpha.target(active != null ? 1f : 0f);
        slide.target(active != null ? 1f : 0f);
        float a = alpha.get();
        if (a <= 0.01f || shown == null) {
            if (active == null) shown = null;
            return;
        }

        int sw = c.getScaledWindowWidth();
        int sh = c.getScaledWindowHeight();
        int color = currentColor();
        float p = pulse ? 0.78f + 0.22f * (float) Math.sin(System.currentTimeMillis() / 160.0) : 1f;
        float ba = a * p;

        // border
        int t = Math.max(1, thickness);
        int solid = Theme.alpha(color, ba);
        Gfx.rect(c, 0, 0, sw, t, solid);
        Gfx.rect(c, 0, sh - t, sw, t, solid);
        Gfx.rect(c, 0, t, t, sh - 2 * t, solid);
        Gfx.rect(c, sw - t, t, t, sh - 2 * t, solid);
        if (glow) {
            int layers = 10;
            for (int i = 0; i < layers; i++) {
                float fa = ba * 0.32f * (1f - (float) i / layers) * (1f - (float) i / layers);
                int g = Theme.alpha(color, fa);
                int o = t + i;
                Gfx.rect(c, o, o, sw - 2 * o, 1, g);
                Gfx.rect(c, o, sh - o - 1, sw - 2 * o, 1, g);
                Gfx.rect(c, o, o + 1, 1, sh - 2 * o - 2, g);
                Gfx.rect(c, sw - o - 1, o + 1, 1, sh - 2 * o - 2, g);
            }
        }

        if (!label) return;

        // chip
        String title = shown.title();
        String detail = shown.detail();
        int titleW = Gfx.widthBold(title);
        int detailW = Gfx.width(detail);
        int kickW = Gfx.width(kicker);
        int w = Math.max(Math.max(titleW, detailW), kickW) + 26;
        int h = 40;
        int x = sw / 2 - w / 2;
        int y = Math.round(6 + (slide.get() - 1f) * 10f) + t;
        Gfx.shadow(c, x, y, w, h, 4, a);
        Gfx.round(c, x, y, w, h, 4, Theme.alpha(0xF0101114, a));
        Gfx.round(c, x, y, 3, h, 1, Theme.alpha(color, a));
        Gfx.rect(c, x + 3, y, w - 3, 1, Theme.alpha(color, a * 0.35f));
        Gfx.text(c, kicker, x + 12, y + 5, Theme.alpha(Theme.TEXT_3, a));
        Gfx.textBold(c, title, x + 12, y + 15, Theme.alpha(color, a));
        Gfx.text(c, detail, x + 12, y + 27, Theme.alpha(Theme.TEXT_2, a));
    }

    private int currentColor() {
        float t = Math.min(1f, (System.currentTimeMillis() - colorChangedAt) / 180f);
        return Theme.lerp(fromColor, toColor, t);
    }
}
