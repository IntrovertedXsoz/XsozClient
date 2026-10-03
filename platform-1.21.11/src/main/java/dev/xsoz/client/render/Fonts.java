package dev.xsoz.client.render;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * The client's own fonts, loaded from this mod's resources (assets/xsoz/font). Never Mojang's
 * font for client UI. Inter sets every sentence; Monocraft is display-only (logo, big numerals).
 */
public final class Fonts {
    private Fonts() { }

    public static final Style UI = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of("xsoz", "ui")));
    public static final Style BOLD = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of("xsoz", "ui_bold")));
    public static final Style DISPLAY = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of("xsoz", "display")));

    /** When false (Client Settings, Custom font off) text falls back to the vanilla font. */
    public static boolean enabled = true;

    public static MutableText ui(String s) { return styled(s, UI); }

    public static MutableText bold(String s) { return styled(s, BOLD); }

    public static MutableText display(String s) { return styled(s, DISPLAY); }

    private static MutableText styled(String s, Style style) {
        MutableText t = Text.literal(s);
        return enabled ? t.setStyle(style) : t;
    }
}
