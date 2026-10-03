package dev.xsoz.client.module;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.setting.BoolSetting;
import dev.xsoz.client.setting.NumberSetting;
import net.minecraft.client.gui.DrawContext;

/**
 * A module that draws one draggable element on the HUD. Position is stored as a fraction of the
 * scaled screen so a layout survives a resolution or GUI-scale change.
 */
public abstract class HudModule extends Module {
    private float fx;
    private float fy;
    protected int width = 40;
    protected int height = 12;

    public final NumberSetting scale = add(new NumberSetting("Scale", "Size of this element", 1.0, 0.5, 2.0, 0.05, "x"));
    public final BoolSetting background = add(new BoolSetting("Background", "Draw a panel behind it", true));

    protected HudModule(String name, String description, boolean defaultEnabled, float defaultFx, float defaultFy) {
        super(name, description, Category.HUD, defaultEnabled);
        this.fx = defaultFx;
        this.fy = defaultFy;
    }

    protected HudModule(String name, String description, Category category, boolean defaultEnabled, float defaultFx, float defaultFy) {
        super(name, description, category, defaultEnabled);
        this.fx = defaultFx;
        this.fy = defaultFy;
    }

    public float fx() { return fx; }

    public float fy() { return fy; }

    public void setFraction(float fx, float fy) {
        this.fx = Math.max(0f, Math.min(1f, fx));
        this.fy = Math.max(0f, Math.min(1f, fy));
    }

    public int scaledWidth() { return Math.round(width * scale.floatValue()); }

    public int scaledHeight() { return Math.round(height * scale.floatValue()); }

    public int screenX() {
        int sw = mc.getWindow().getScaledWidth();
        return Math.max(0, Math.min(sw - scaledWidth(), Math.round(fx * sw)));
    }

    public int screenY() {
        int sh = mc.getWindow().getScaledHeight();
        return Math.max(0, Math.min(sh - scaledHeight(), Math.round(fy * sh)));
    }

    /** True when the element should draw outside the editor (e.g. only during a fight). */
    public boolean shouldRender() { return true; }

    /** Draws at (0,0) in element space; the caller has already translated and scaled. */
    public final void renderAt(DrawContext c, boolean editor) {
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(screenX(), screenY());
        m.scale(scale.floatValue(), scale.floatValue());
        render(c, editor);
        m.popMatrix();
    }

    protected abstract void render(DrawContext c, boolean editor);

    /** Standard panel used by most HUD elements. */
    protected void panel(DrawContext c, int w, int h) {
        this.width = w;
        this.height = h;
        if (background.on()) {
            Gfx.round(c, 0, 0, w, h, 3, Theme.alpha(Theme.BG, 0.72f));
        }
    }

    /** One-line label/value element: "Label value". */
    protected void line(DrawContext c, String label, String value) {
        int lw = label.isEmpty() ? 0 : Gfx.width(label) + 4;
        int w = 8 + lw + Gfx.widthBold(value);
        panel(c, w, 13);
        int x = 4;
        if (!label.isEmpty()) {
            Gfx.text(c, label, x, 3, Theme.TEXT_3);
            x += lw;
        }
        Gfx.textBold(c, value, x, 3, Theme.TEXT);
    }
}
