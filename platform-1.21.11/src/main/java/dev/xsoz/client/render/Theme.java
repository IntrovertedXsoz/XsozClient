package dev.xsoz.client.render;

/**
 * Design tokens, mirrored from the launcher's palette (ClaudeDesign/in-game-menu.html). All colours
 * are ARGB: 1.21.6+ draws a colour with a zero alpha byte as fully transparent, so every constant
 * here carries an explicit alpha.
 */
public final class Theme {
    private Theme() { }

    public static final int BG        = 0xFF101114;
    public static final int RAIL      = 0xFF0D0E11;
    public static final int SURFACE   = 0xFF15171C;
    public static final int RAISED    = 0xFF1A1D23;
    public static final int HOVER     = 0xFF1F2329;
    public static final int LINE      = 0xFF2A3040;
    public static final int LINE_SOFT = 0xFF1E222B;
    public static final int EDGE      = 0xFF5D677C;
    public static final int TEXT      = 0xFFECEEF2;
    public static final int TEXT_2    = 0xFFA9B0BE;
    public static final int TEXT_3    = 0xFF7E8799;
    public static final int ACCENT       = 0xFF4CD765;
    public static final int ACCENT_HOVER = 0xFF63E27B;
    public static final int ACCENT_DEEP  = 0xFF2C8C3F;
    public static final int ON_ACCENT    = 0xFF08120B;
    public static final int WARN      = 0xFFD4A94F;
    public static final int DANGER    = 0xFFFF5A5A;

    /** The user-chosen accent (Client Settings, Accent). Defaults to the launcher's green. */
    private static int accent = ACCENT;

    public static int accent() { return accent; }

    public static void setAccent(int argb) { accent = 0xFF000000 | argb; }

    public static int alpha(int argb, float a) {
        int base = (argb >>> 24) & 0xFF;
        int na = Math.max(0, Math.min(255, Math.round(base * a)));
        return (na << 24) | (argb & 0x00FFFFFF);
    }

    public static int withAlpha(int rgb, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0x00FFFFFF);
    }

    public static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24)
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    /** HSV (0..1) to opaque ARGB. */
    public static int hsv(float h, float s, float v) {
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(h, s, v) & 0x00FFFFFF);
    }

    /** Hue (0..1) of an ARGB colour. */
    public static float hue(int argb) {
        float[] hsb = java.awt.Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, null);
        return hsb[0];
    }
}
