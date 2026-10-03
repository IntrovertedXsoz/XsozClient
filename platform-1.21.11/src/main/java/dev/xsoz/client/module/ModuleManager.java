package dev.xsoz.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;

/** Owns every module. Single-threaded: everything here runs on the render/client thread. */
public final class ModuleManager {
    private static final List<Module> MODULES = new ArrayList<>();
    private static final Map<Class<? extends Module>, Module> BY_CLASS = new HashMap<>();
    private static final Map<Integer, Boolean> LAST_KEY_STATE = new HashMap<>();

    private ModuleManager() { }

    public static <M extends Module> M register(M module) {
        MODULES.add(module);
        BY_CLASS.put(module.getClass(), module);
        return module;
    }

    public static List<Module> all() { return Collections.unmodifiableList(MODULES); }

    public static List<Module> in(Category c) {
        List<Module> out = new ArrayList<>();
        for (Module m : MODULES) if (m.category() == c) out.add(m);
        return out;
    }

    public static List<HudModule> hud() {
        List<HudModule> out = new ArrayList<>();
        for (Module m : MODULES) if (m instanceof HudModule h) out.add(h);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static <M extends Module> M get(Class<M> type) { return (M) BY_CLASS.get(type); }

    public static Module byId(String id) {
        for (Module m : MODULES) if (m.id().equals(id)) return m;
        return null;
    }

    public static void clientReady() {
        for (Module m : MODULES) {
            if (!m.isEnabled()) continue;
            try {
                m.onClientReady();
            } catch (RuntimeException ex) {
                dev.xsoz.client.XsozClient.LOG.warn("Module {} start-up failed: {}", m.name(), ex.toString());
            }
        }
    }

    /** Start-of-tick: before the player reads its input. */
    public static void preTick() {
        for (Module m : MODULES) {
            if (!m.isEnabled()) continue;
            try {
                m.onPreTick();
            } catch (RuntimeException ex) {
                dev.xsoz.client.XsozClient.LOG.warn("Module {} pre-tick failed: {}", m.name(), ex.toString());
            }
        }
    }

    /** End-of-tick: module ticks and keybind toggles (edge-triggered, only with no screen open). */
    public static void tick(MinecraftClient mc) {
        for (Module m : MODULES) {
            if (m.isEnabled()) {
                try {
                    m.onTick();
                } catch (RuntimeException ex) {
                    dev.xsoz.client.XsozClient.LOG.warn("Module {} tick failed: {}", m.name(), ex.toString());
                }
            }
        }

        if (mc.currentScreen != null || mc.getWindow() == null) {
            LAST_KEY_STATE.clear();
            return;
        }

        for (Module m : MODULES) {
            int key = m.bind();
            if (key < 0) continue;
            boolean down = InputUtil.isKeyPressed(mc.getWindow(), key);
            boolean was = LAST_KEY_STATE.getOrDefault(key, down);
            if (down && !was) m.toggle();
        }
        LAST_KEY_STATE.clear();
        for (Module m : MODULES) {
            if (m.bind() >= 0) LAST_KEY_STATE.put(m.bind(), InputUtil.isKeyPressed(mc.getWindow(), m.bind()));
        }
    }

    public static void renderHud(DrawContext c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.player == null) return;
        if (mc.currentScreen instanceof dev.xsoz.client.gui.HudEditorScreen) return;
        for (HudModule h : hud()) {
            if (!h.isEnabled() || !h.shouldRender()) continue;
            try {
                h.renderAt(c, false);
            } catch (RuntimeException ex) {
                dev.xsoz.client.XsozClient.LOG.warn("HUD {} failed: {}", h.name(), ex.toString());
            }
        }
    }
}
