package dev.xsoz.client.gui;

import dev.xsoz.client.config.ConfigManager;
import dev.xsoz.client.module.HudModule;
import dev.xsoz.client.module.ModuleManager;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Drag HUD elements around. Snaps to screen edges and centre lines; scroll to resize. */
public final class HudEditorScreen extends Screen {
    private static final int SNAP = 4;
    private final Screen parent;
    private HudModule dragging;
    private double offX;
    private double offY;
    private int guideX = -1;
    private int guideY = -1;

    public HudEditorScreen(Screen parent) {
        super(Text.literal("HUD Editor"));
        this.parent = parent;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Gfx.rect(c, 0, 0, width, height, 0x50000000);
        for (int x = 0; x < width; x += 20) Gfx.rect(c, x, 0, 1, height, 0x0CFFFFFF);
        for (int y = 0; y < height; y += 20) Gfx.rect(c, 0, y, width, 1, 0x0CFFFFFF);
        Gfx.rect(c, width / 2, 0, 1, height, 0x18FFFFFF);
        Gfx.rect(c, 0, height / 2, width, 1, 0x18FFFFFF);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        HudModule hovered = null;
        for (HudModule h : ModuleManager.hud()) {
            if (!h.isEnabled()) continue;
            h.renderAt(c, true);
            int x = h.screenX();
            int y = h.screenY();
            int w = h.scaledWidth();
            int hh = h.scaledHeight();
            boolean hover = dragging == h || (dragging == null && Gfx.hovered(mx, my, x, y, w, hh));
            if (hover) hovered = h;
            Gfx.outline(c, x - 1, y - 1, w + 2, hh + 2, 1, hover ? Theme.accent() : 0x60FFFFFF);
        }
        if (guideX >= 0) Gfx.rect(c, guideX, 0, 1, height, Theme.accent());
        if (guideY >= 0) Gfx.rect(c, 0, guideY, width, 1, Theme.accent());

        String title = "HUD EDITOR";
        Gfx.round(c, width / 2 - 110, 8, 220, 30, 4, 0xE0101114);
        Gfx.textBoldCentered(c, title, width / 2, 13, Theme.accent());
        Gfx.textCentered(c, "Drag to move  -  Scroll to resize  -  Esc to finish", width / 2, 25, Theme.TEXT_3);
        if (hovered != null) {
            String s = hovered.name() + "  " + Math.round(hovered.scale.value() * 100) + "%";
            Gfx.round(c, mx + 8, my + 8, Gfx.width(s) + 10, 14, 3, 0xF0101114);
            Gfx.text(c, s, mx + 13, my + 11, Theme.TEXT);
        }
    }

    private HudModule at(double mx, double my) {
        var list = ModuleManager.hud();
        for (int i = list.size() - 1; i >= 0; i--) {
            HudModule h = list.get(i);
            if (h.isEnabled() && Gfx.hovered(mx, my, h.screenX(), h.screenY(), h.scaledWidth(), h.scaledHeight())) return h;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        HudModule h = at(click.x(), click.y());
        if (h != null && click.button() == 0) {
            dragging = h;
            offX = click.x() - h.screenX();
            offY = click.y() - h.screenY();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging == null) return super.mouseDragged(click, dx, dy);
        int w = dragging.scaledWidth();
        int h = dragging.scaledHeight();
        int x = (int) Math.round(click.x() - offX);
        int y = (int) Math.round(click.y() - offY);
        guideX = -1;
        guideY = -1;
        int cx = width / 2;
        int cy = height / 2;
        if (Math.abs(x) <= SNAP) x = 0;
        if (Math.abs(x + w - width) <= SNAP) x = width - w;
        if (Math.abs(x + w / 2 - cx) <= SNAP) {
            x = cx - w / 2;
            guideX = cx;
        }
        if (Math.abs(y) <= SNAP) y = 0;
        if (Math.abs(y + h - height) <= SNAP) y = height - h;
        if (Math.abs(y + h / 2 - cy) <= SNAP) {
            y = cy - h / 2;
            guideY = cy;
        }
        x = Math.max(0, Math.min(width - w, x));
        y = Math.max(0, Math.min(height - h, y));
        dragging.setFraction((float) x / width, (float) y / height);
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging != null) ConfigManager.markDirty();
        dragging = null;
        guideX = -1;
        guideY = -1;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        HudModule m = at(mx, my);
        if (m != null) {
            m.scale.set(m.scale.value() + (v > 0 ? 0.05 : -0.05));
            ConfigManager.markDirty();
            return true;
        }
        return super.mouseScrolled(mx, my, h, v);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        ConfigManager.save();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
