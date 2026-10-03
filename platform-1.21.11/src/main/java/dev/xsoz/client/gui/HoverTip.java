package dev.xsoz.client.gui;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import java.util.List;
import net.minecraft.client.gui.DrawContext;

/**
 * "Hover for a moment to learn what this is." Anything a screen draws can offer an explanation
 * for the frame it is hovered; the tip appears only after the pointer has rested on the SAME
 * thing for {@link #DELAY_MS}, fades in, and never covers the pointer.
 */
public final class HoverTip {
    public static final long DELAY_MS = 450;
    private String key;
    private String text;
    private long since;
    private String offeredKey;
    private String offeredText;

    /** Call at the start of a frame. */
    public void begin() {
        offeredKey = null;
        offeredText = null;
    }

    /** Offer a tip for something hovered this frame (the last offer wins: draw order = topmost). */
    public void offer(String id, String explanation) {
        if (explanation == null || explanation.isBlank()) return;
        offeredKey = id;
        offeredText = explanation;
    }

    /** Call at the end of a frame, after everything else is drawn. */
    public void render(DrawContext c, int mx, int my) {
        long now = System.currentTimeMillis();
        if (offeredKey == null) {
            key = null;
            return;
        }
        if (!offeredKey.equals(key)) {
            key = offeredKey;
            since = now;
        }
        text = offeredText;
        long held = now - since;
        if (held < DELAY_MS) return;
        float a = Math.min(1f, (held - DELAY_MS) / 140f);
        List<String> lines = Gfx.wrap(text, 200);
        int w = 0;
        for (String l : lines) w = Math.max(w, Gfx.width(l));
        w += 14;
        int h = lines.size() * 10 + 10;
        int sw = c.getScaledWindowWidth();
        int sh = c.getScaledWindowHeight();
        int x = mx + 12;
        int y = my + 14;
        if (x + w > sw - 4) x = mx - w - 8;
        if (y + h > sh - 4) y = my - h - 6;
        Gfx.shadow(c, x, y, w, h, 3, a);
        Gfx.round(c, x, y, w, h, 4, Theme.alpha(0xF7171A1F, a));
        Gfx.outline(c, x, y, w, h, 1, Theme.alpha(Theme.LINE, a));
        for (int i = 0; i < lines.size(); i++) Gfx.text(c, lines.get(i), x + 7, y + 6 + i * 10, Theme.alpha(Theme.TEXT_2, a));
    }
}
