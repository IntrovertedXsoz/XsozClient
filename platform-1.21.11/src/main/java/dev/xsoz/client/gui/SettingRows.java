package dev.xsoz.client.gui;

import dev.xsoz.client.module.Module;
import dev.xsoz.client.render.Anim;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.setting.ActionSetting;
import dev.xsoz.client.setting.BoolSetting;
import dev.xsoz.client.setting.ColorSetting;
import dev.xsoz.client.setting.ModeSetting;
import dev.xsoz.client.setting.NumberSetting;
import dev.xsoz.client.setting.Setting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvents;
import org.lwjgl.glfw.GLFW;

/**
 * Renders and edits a module's settings as compact rows. Layout is recorded during render and
 * hit-tested on input, so one instance can serve any screen.
 */
public final class SettingRows {
    public static final int ROW = 14;

    private record Hit(Setting<?> setting, Module bindOwner, int x, int y, int w, int h) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private final Map<Setting<?>, Anim> toggles = new HashMap<>();
    private NumberSetting dragging;
    private ColorSetting draggingColor;
    private int dragX;
    private int dragW;
    private Module listening;

    public void beginFrame() { hits.clear(); }

    public boolean isListening() { return listening != null; }

    /** Draws the rows; returns the height used. */
    public int render(DrawContext c, Module owner, List<Setting<?>> settings, int x, int y, int w, int mx, int my, boolean includeBind) {
        int cy = y;
        for (Setting<?> s : settings) {
            if (!s.isVisible()) continue;
            boolean hover = Gfx.hovered(mx, my, x, cy, w, ROW);
            if (hover) Gfx.rect(c, x, cy, w, ROW, 0x14FFFFFF);
            if (s instanceof BoolSetting b) {
                Gfx.text(c, Gfx.fit(s.name(), w - 30), x + 6, cy + 3, Theme.TEXT_2);
                Anim an = toggles.computeIfAbsent(s, k -> new Anim(b.on() ? 1f : 0f, 18f));
                float t = an.target(b.on() ? 1f : 0f).get();
                int tx = x + w - 22;
                Gfx.round(c, tx, cy + 3, 16, 8, 4, Theme.lerp(Theme.LINE, Theme.accent(), t));
                Gfx.round(c, tx + 1 + Math.round(t * 8), cy + 4, 6, 6, 3, Theme.lerp(Theme.TEXT_3, Theme.ON_ACCENT, t));
            } else if (s instanceof NumberSetting n) {
                String v = n.display();
                Gfx.text(c, Gfx.fit(s.name(), w - Gfx.width(v) - 16), x + 6, cy + 1, Theme.TEXT_2);
                Gfx.text(c, v, x + w - 6 - Gfx.width(v), cy + 1, Theme.TEXT);
                int bx = x + 6;
                int bw = w - 12;
                Gfx.rect(c, bx, cy + 11, bw, 2, Theme.LINE);
                int fill = (int) Math.round(bw * n.fraction());
                Gfx.rect(c, bx, cy + 11, fill, 2, Theme.accent());
                Gfx.rect(c, bx + fill - 1, cy + 10, 3, 4, Theme.TEXT);
            } else if (s instanceof ModeSetting m) {
                Gfx.text(c, Gfx.fit(s.name(), w / 2 - 8), x + 6, cy + 3, Theme.TEXT_2);
                String v = m.get();
                int vw = Gfx.width(v);
                Gfx.round(c, x + w - vw - 14, cy + 1, vw + 10, ROW - 2, 3, Theme.RAISED);
                Gfx.text(c, v, x + w - vw - 9, cy + 3, Theme.accent());
            } else if (s instanceof ColorSetting col) {
                Gfx.text(c, s.name(), x + 6, cy + 1, Theme.TEXT_2);
                Gfx.round(c, x + w - 16, cy + 2, 10, 7, 2, col.argb());
                int bx = x + 6;
                int bw = w - 26;
                for (int i = 0; i < bw; i++) Gfx.rect(c, bx + i, cy + 10, 1, 3, Theme.hsv((float) i / bw, 0.75f, 1f));
                int hx = bx + Math.round(Theme.hue(col.argb()) * bw);
                Gfx.rect(c, hx - 1, cy + 9, 3, 5, Theme.TEXT);
            } else if (s instanceof ActionSetting) {
                Gfx.round(c, x + 4, cy + 1, w - 8, ROW - 2, 3, hover ? Theme.HOVER : Theme.RAISED);
                Gfx.textCentered(c, s.name(), x + w / 2, cy + 3, hover ? Theme.TEXT : Theme.TEXT_2);
            }
            hits.add(new Hit(s, null, x, cy, w, ROW));
            cy += ROW;
        }
        if (includeBind && owner != null) {
            boolean hover = Gfx.hovered(mx, my, x, cy, w, ROW);
            if (hover) Gfx.rect(c, x, cy, w, ROW, 0x14FFFFFF);
            Gfx.text(c, "Keybind", x + 6, cy + 3, Theme.TEXT_2);
            String v = listening == owner ? "Press a key..." : keyName(owner.bind());
            int vw = Gfx.width(v);
            Gfx.round(c, x + w - vw - 14, cy + 1, vw + 10, ROW - 2, 3, listening == owner ? Theme.accent() : Theme.RAISED);
            Gfx.text(c, v, x + w - vw - 9, cy + 3, listening == owner ? Theme.ON_ACCENT : Theme.TEXT);
            hits.add(new Hit(null, owner, x, cy, w, ROW));
            cy += ROW;
        }
        return cy - y;
    }

    public static int height(Module owner, List<Setting<?>> settings, boolean includeBind) {
        int n = 0;
        for (Setting<?> s : settings) if (s.isVisible()) n++;
        return (n + (includeBind && owner != null ? 1 : 0)) * ROW;
    }

    /** Explanation of the row under the pointer (for hover tips), or null. */
    public String hoveredDescription(double mx, double my) {
        for (Hit h : hits) {
            if (!Gfx.hovered(mx, my, h.x, h.y, h.w, h.h)) continue;
            if (h.bindOwner != null) return "A key that turns this on and off. Click, then press the key. Right-click (or Backspace while waiting) clears it.";
            String d = h.setting.description();
            if (h.setting instanceof NumberSetting) d += ". Drag the slider.";
            else if (h.setting instanceof ModeSetting m) d += ". Click to cycle: " + String.join(", ", m.modes()) + ".";
            return d;
        }
        return null;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        for (Hit h : hits) {
            if (!Gfx.hovered(mx, my, h.x, h.y, h.w, h.h)) continue;
            if (h.bindOwner != null) {
                if (button == 0) listening = listening == h.bindOwner ? null : h.bindOwner;
                else if (button == 1) h.bindOwner.setBind(-1);
                click();
                return true;
            }
            Setting<?> s = h.setting;
            if (s instanceof BoolSetting b) {
                b.toggle();
                click();
            } else if (s instanceof NumberSetting n) {
                dragging = n;
                dragX = h.x + 6;
                dragW = h.w - 12;
                n.setFraction((mx - dragX) / dragW);
            } else if (s instanceof ModeSetting m) {
                m.cycle(button == 1 ? -1 : 1);
                click();
            } else if (s instanceof ColorSetting col) {
                draggingColor = col;
                dragX = h.x + 6;
                dragW = h.w - 26;
                setHue(col, (mx - dragX) / dragW);
            } else if (s instanceof ActionSetting a) {
                click();
                a.run();
            }
            dev.xsoz.client.config.ConfigManager.markDirty();
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx) {
        if (dragging != null) {
            dragging.setFraction((mx - dragX) / dragW);
            dev.xsoz.client.config.ConfigManager.markDirty();
            return true;
        }
        if (draggingColor != null) {
            setHue(draggingColor, (mx - dragX) / dragW);
            dev.xsoz.client.config.ConfigManager.markDirty();
            return true;
        }
        return false;
    }

    public void mouseReleased() {
        dragging = null;
        draggingColor = null;
    }

    /** Returns true when the key was consumed by bind capture. */
    public boolean keyPressed(int key) {
        if (listening == null) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            listening = null;
        } else if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
            listening.setBind(-1);
            listening = null;
        } else {
            listening.setBind(key);
            listening = null;
        }
        dev.xsoz.client.config.ConfigManager.markDirty();
        return true;
    }

    private static void setHue(ColorSetting col, double f) {
        f = Math.max(0, Math.min(0.999, f));
        col.set(Theme.hsv((float) f, 0.72f, 0.95f));
    }

    public static String keyName(int key) {
        if (key < 0) return "None";
        try {
            String n = InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
            return n.length() > 14 ? n.substring(0, 14) : n;
        } catch (RuntimeException ex) {
            return "Key " + key;
        }
    }

    private static void click() {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, 0.25f));
    }
}
