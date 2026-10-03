package dev.xsoz.client.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Small 2D drawing kit on top of DrawContext. Integer GUI coordinates throughout. */
public final class Gfx {
    private Gfx() { }

    public static TextRenderer font() { return MinecraftClient.getInstance().textRenderer; }

    public static void rect(DrawContext c, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        c.fill(x, y, x + w, y + h, color);
    }

    /** A filled rectangle with rounded corners (radius clamped to half the short side). */
    public static void round(DrawContext c, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            rect(c, x, y, w, h, color);
            return;
        }
        c.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            c.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            c.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
    }

    public static void outline(DrawContext c, int x, int y, int w, int h, int t, int color) {
        rect(c, x, y, w, t, color);
        rect(c, x, y + h - t, w, t, color);
        rect(c, x, y + t, t, h - 2 * t, color);
        rect(c, x + w - t, y + t, t, h - 2 * t, color);
    }

    /** A soft drop shadow made of stacked translucent rounded rects. */
    public static void shadow(DrawContext c, int x, int y, int w, int h, int spread, float strength) {
        for (int i = spread; i > 0; i--) {
            int a = Math.round(strength * 36f * (1f - (float) i / (spread + 1)));
            round(c, x - i, y - i + 1, w + 2 * i, h + 2 * i, Math.min(6, i + 2), a << 24);
        }
    }

    public static void vGradient(DrawContext c, int x, int y, int w, int h, int top, int bottom) {
        c.fillGradient(x, y, x + w, y + h, top, bottom);
    }

    public static void hGradient(DrawContext c, int x, int y, int w, int h, int left, int right) {
        int steps = Math.max(1, Math.min(w, 48));
        for (int i = 0; i < steps; i++) {
            int x0 = x + w * i / steps;
            int x1 = x + w * (i + 1) / steps;
            c.fill(x0, y, x1, y + h, Theme.lerp(left, right, (i + 0.5f) / steps));
        }
    }

    // ---- text ------------------------------------------------------------------------------

    public static int text(DrawContext c, String s, int x, int y, int color) {
        Text t = Fonts.ui(s);
        c.drawText(font(), t, x, y, color, false);
        return font().getWidth(t);
    }

    public static int textBold(DrawContext c, String s, int x, int y, int color) {
        Text t = Fonts.bold(s);
        c.drawText(font(), t, x, y, color, false);
        return font().getWidth(t);
    }

    public static int textShadow(DrawContext c, String s, int x, int y, int color) {
        Text t = Fonts.ui(s);
        c.drawText(font(), t, x, y, color, true);
        return font().getWidth(t);
    }

    public static int textBoldShadow(DrawContext c, String s, int x, int y, int color) {
        Text t = Fonts.bold(s);
        c.drawText(font(), t, x, y, color, true);
        return font().getWidth(t);
    }

    public static void textCentered(DrawContext c, String s, int cx, int y, int color) {
        Text t = Fonts.ui(s);
        c.drawText(font(), t, cx - font().getWidth(t) / 2, y, color, false);
    }

    public static void textBoldCentered(DrawContext c, String s, int cx, int y, int color) {
        Text t = Fonts.bold(s);
        c.drawText(font(), t, cx - font().getWidth(t) / 2, y, color, false);
    }

    public static int width(String s) { return font().getWidth(Fonts.ui(s)); }

    public static int widthBold(String s) { return font().getWidth(Fonts.bold(s)); }

    /** Display text (Monocraft), scaled. Logos and big numerals only - never a sentence. */
    public static void display(DrawContext c, String s, float x, float y, float scale, int color) {
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);
        c.drawText(font(), Fonts.display(s), 0, 0, color, false);
        m.popMatrix();
    }

    public static int displayWidth(String s) { return font().getWidth(Fonts.display(s)); }

    /** Bold text, scaled. */
    public static void boldScaled(DrawContext c, String s, float x, float y, float scale, int color) {
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);
        c.drawText(font(), Fonts.bold(s), 0, 0, color, false);
        m.popMatrix();
    }

    /** Trims with an ellipsis so it fits maxW. */
    public static String fit(String s, int maxW) {
        if (width(s) <= maxW) return s;
        String e = "...";
        int end = s.length();
        while (end > 0 && width(s.substring(0, end) + e) > maxW) end--;
        return s.substring(0, end) + e;
    }

    /** Word-wraps to maxW. */
    public static List<String> wrap(String s, int maxW) {
        List<String> lines = new ArrayList<>();
        for (String para : s.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String trial = line.isEmpty() ? word : line + " " + word;
                if (width(trial) > maxW && !line.isEmpty()) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(trial);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    public static boolean hovered(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }
}
