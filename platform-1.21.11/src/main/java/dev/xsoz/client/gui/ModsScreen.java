package dev.xsoz.client.gui;

import dev.xsoz.client.config.ConfigManager;
import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.module.ModuleManager;
import dev.xsoz.client.render.Anim;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.trainer.crystal.TrainerScreen;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * The Xsoz menu (Right Shift). A settings-app layout - category sidebar, a grid of mod cards with
 * switches, and an options page per mod - in the same visual language as the Trainer. Hovering
 * anything for a moment explains it.
 */
public final class ModsScreen extends Screen {
    private static final int RAIL_W = 132;
    private static final int CARD_W = 168;
    private static final int CARD_H = 58;
    private static final int GAP = 8;

    private final Screen parent;
    private final Anim open = new Anim(0f, 10f);
    private final HoverTip tip = new HoverTip();
    private final SettingRows rows = new SettingRows();
    private final Map<Module, Anim> hover = new HashMap<>();
    private final Map<Module, Anim> switches = new HashMap<>();
    private Category category; // null = all
    private Module detail;
    private String search = "";
    private boolean searchFocused;
    private int scroll;
    private int contentHeight;
    private int x0, y0, w, h, cx, cy, cw, ch;
    private final List<Runnable> actions = new ArrayList<>();
    private final List<int[]> rects = new ArrayList<>();

    public ModsScreen(Screen parent) {
        super(Text.literal("Xsoz"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(width - 24, 640);
        h = Math.min(height - 24, 370);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        cx = x0 + RAIL_W + 14;
        cy = y0 + 44;
        cw = w - RAIL_W - 28;
        ch = h - 44 - 12;
        open.snap(0f);
        open.target(1f);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Gfx.rect(c, 0, 0, width, height, Theme.alpha(0xB8090A0C, open.get()));
    }

    private void click(int x, int y, int bw, int bh, Runnable r) {
        rects.add(new int[] {x, y, bw, bh});
        actions.add(r);
    }

    /** A click target inside the scrolling content area, clipped to what is visible. */
    private void clipClick(int x, int y, int bw, int bh, Runnable r) {
        int top = Math.max(y, cy);
        int bottom = Math.min(y + bh, cy + ch);
        if (bottom > top) click(x, top, bw, bottom - top, r);
    }

    private boolean button(DrawContext c, String label, int x, int y, int bw, int bh, boolean primary, int mx, int my, Runnable r) {
        boolean hov = Gfx.hovered(mx, my, x, y, bw, bh);
        int bg = primary ? (hov ? Theme.ACCENT_HOVER : Theme.accent()) : (hov ? Theme.HOVER : Theme.RAISED);
        Gfx.round(c, x, y, bw, bh, 4, bg);
        Gfx.textCentered(c, label, x + bw / 2, y + (bh - 8) / 2, primary ? Theme.ON_ACCENT : Theme.TEXT);
        click(x, y, bw, bh, r);
        return hov;
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        rects.clear();
        actions.clear();
        rows.beginFrame();
        tip.begin();
        float a = Anim.easeOutCubic(open.get());
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(0, (1 - a) * 10);

        Gfx.shadow(c, x0, y0, w, h, 6, 1f);
        Gfx.round(c, x0, y0, w, h, 6, 0xFA111317);
        Gfx.outline(c, x0, y0, w, h, 1, Theme.LINE_SOFT);
        renderRail(c, mx, my);
        if (detail != null) renderDetail(c, mx, my);
        else renderGrid(c, mx, my);
        m.popMatrix();
        tip.render(c, mx, my);
    }

    // ------------------------------------------------------------------ rail

    private void renderRail(DrawContext c, int mx, int my) {
        Gfx.round(c, x0, y0, RAIL_W, h, 6, Theme.RAIL);
        Gfx.display(c, "XSOZ", x0 + 14, y0 + 14, 1.5f, Theme.accent());
        Gfx.text(c, "Client", x0 + 14 + Math.round(Gfx.displayWidth("XSOZ") * 1.5f) + 6, y0 + 18, Theme.TEXT_3);
        int y = y0 + 42;
        y = navItem(c, null, "All mods", "Every mod in one list.", y, mx, my);
        y += 4;
        Gfx.text(c, "CATEGORIES", x0 + 14, y + 2, Theme.TEXT_3);
        y += 14;
        for (Category cat : Category.values()) y = navItem(c, cat, cat.title, cat.blurb, y, mx, my);

        int by = y0 + h - 50;
        if (button(c, "Edit HUD layout", x0 + 10, by, RAIL_W - 20, 18, false, mx, my, () -> client.setScreen(new HudEditorScreen(this)))) {
            tip.offer("edithud", "Drag your on-screen elements (FPS, keystrokes, armour...) to where you want them.");
        }
        if (button(c, "Crystal Trainer", x0 + 10, by + 22, RAIL_W - 20, 18, true, mx, my, () -> client.setScreen(new TrainerScreen(this)))) {
            tip.offer("trainer", "Your fights, skills, training drills, ranks and lessons.");
        }
    }

    private int navItem(DrawContext c, Category cat, String label, String blurb, int y, int mx, int my) {
        boolean sel = detail == null && category == cat || detail != null && detail.category() == cat && cat != null;
        boolean hov = Gfx.hovered(mx, my, x0 + 8, y, RAIL_W - 16, 18);
        if (sel) Gfx.round(c, x0 + 8, y, RAIL_W - 16, 18, 4, Theme.withAlpha(Theme.accent(), 0x26));
        else if (hov) Gfx.round(c, x0 + 8, y, RAIL_W - 16, 18, 4, Theme.HOVER);
        if (sel) Gfx.rect(c, x0 + 8, y + 4, 2, 10, Theme.accent());
        Gfx.text(c, label, x0 + 16, y + 5, sel ? Theme.TEXT : Theme.TEXT_2);
        List<Module> mods = cat == null ? ModuleManager.all() : ModuleManager.in(cat);
        long on = mods.stream().filter(Module::isEnabled).count();
        String n = String.valueOf(on);
        Gfx.text(c, n, x0 + RAIL_W - 14 - Gfx.width(n), y + 5, Theme.TEXT_3);
        if (hov) tip.offer("nav-" + label, blurb + " (" + on + " of " + mods.size() + " on)");
        click(x0 + 8, y, RAIL_W - 16, 18, () -> {
            category = cat;
            detail = null;
            scroll = 0;
        });
        return y + 20;
    }

    // ------------------------------------------------------------------ grid

    private List<Module> visible() {
        List<Module> out = new ArrayList<>();
        String q = search.toLowerCase(Locale.ROOT);
        for (Module mod : category == null ? ModuleManager.all() : ModuleManager.in(category)) {
            if (q.isEmpty() || mod.name().toLowerCase(Locale.ROOT).contains(q) || mod.description().toLowerCase(Locale.ROOT).contains(q)) out.add(mod);
        }
        return out;
    }

    private void renderGrid(DrawContext c, int mx, int my) {
        String title = category == null ? "All mods" : category.title;
        Gfx.boldScaled(c, title, cx, y0 + 14, 1.25f, Theme.TEXT);
        Gfx.text(c, category == null ? "Click a switch to turn a mod on. Click a card for its options." : category.blurb, cx, y0 + 29, Theme.TEXT_3);
        renderSearch(c, mx, my);

        List<Module> mods = visible();
        int cols = Math.max(1, (cw + GAP) / (CARD_W + GAP));
        int cardW = (cw - (cols - 1) * GAP) / cols;
        int rowsN = (mods.size() + cols - 1) / cols;
        contentHeight = rowsN * (CARD_H + GAP);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - ch)));
        c.enableScissor(cx, cy, cx + cw, cy + ch);
        boolean inContent = Gfx.hovered(mx, my, cx, cy, cw, ch);
        for (int i = 0; i < mods.size(); i++) {
            Module mod = mods.get(i);
            int x = cx + (i % cols) * (cardW + GAP);
            int y = cy + (i / cols) * (CARD_H + GAP) - scroll;
            if (y + CARD_H < cy || y > cy + ch) continue;
            card(c, mod, x, y, cardW, inContent ? mx : -1, my);
        }
        if (mods.isEmpty()) Gfx.textCentered(c, "Nothing matches \"" + search + "\".", cx + cw / 2, cy + 20, Theme.TEXT_3);
        c.disableScissor();
        if (contentHeight > ch) {
            int thumb = Math.max(16, ch * ch / contentHeight);
            int ty = cy + (ch - thumb) * scroll / Math.max(1, contentHeight - ch);
            Gfx.round(c, cx + cw + 4, ty, 3, thumb, 1, Theme.EDGE);
        }
    }

    private void renderSearch(DrawContext c, int mx, int my) {
        int sw = 150;
        int sx = cx + cw - sw;
        int sy = y0 + 14;
        Gfx.round(c, sx, sy, sw, 18, 4, Theme.SURFACE);
        Gfx.outline(c, sx, sy, sw, 18, 1, searchFocused ? Theme.accent() : Theme.LINE);
        String shown = search.isEmpty() && !searchFocused ? "Search mods" : search + (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0 ? "|" : "");
        Gfx.text(c, Gfx.fit(shown, sw - 14), sx + 7, sy + 5, search.isEmpty() && !searchFocused ? Theme.TEXT_3 : Theme.TEXT);
        if (Gfx.hovered(mx, my, sx, sy, sw, 18)) tip.offer("search", "Type to filter by name or description. You can also just start typing.");
        click(sx, sy, sw, 18, () -> searchFocused = true);
    }

    private void card(DrawContext c, Module mod, int x, int y, int cardW, int mx, int my) {
        boolean hov = Gfx.hovered(mx, my, x, y, cardW, CARD_H);
        float hv = hover.computeIfAbsent(mod, k -> new Anim(0f, 16f)).target(hov ? 1f : 0f).get();
        Gfx.round(c, x, y, cardW, CARD_H, 5, Theme.lerp(Theme.SURFACE, Theme.HOVER, hv));
        if (mod.isEnabled()) Gfx.round(c, x, y + 8, 2, CARD_H - 16, 1, Theme.accent());
        Gfx.textBold(c, Gfx.fit(mod.name(), cardW - 20), x + 10, y + 8, Theme.TEXT);
        List<String> desc = Gfx.wrap(mod.description(), cardW - 20);
        for (int i = 0; i < Math.min(2, desc.size()); i++) {
            String line = desc.get(i);
            if (i == 1 && desc.size() > 2) line = Gfx.fit(line + "...", cardW - 20);
            Gfx.text(c, line, x + 10, y + 20 + i * 10, Theme.TEXT_3);
        }
        // footer
        boolean hasOptions = !mod.settings().isEmpty();
        int fy = y + CARD_H - 15;
        if (hasOptions) Gfx.text(c, "Options", x + 10, fy + 2, hv > 0.5f ? Theme.accent() : Theme.TEXT_3);
        int sx = x + cardW - 30;
        boolean swHov = Gfx.hovered(mx, my, sx - 2, fy - 2, 26, 14);
        float on = switches.computeIfAbsent(mod, k -> new Anim(mod.isEnabled() ? 1f : 0f, 16f)).target(mod.isEnabled() ? 1f : 0f).get();
        Gfx.round(c, sx, fy, 22, 11, 5, Theme.lerp(Theme.LINE, Theme.accent(), on));
        Gfx.round(c, sx + 1 + Math.round(on * 11), fy + 1, 9, 9, 4, Theme.lerp(Theme.TEXT_2, Theme.ON_ACCENT, on));

        if (swHov) tip.offer("sw-" + mod.id(), (mod.isEnabled() ? "Turn " : "Turn on ") + mod.name() + (mod.isEnabled() ? " off." : "."));
        else if (hov) tip.offer("card-" + mod.id(), mod.description() + (hasOptions ? " Click for its options." : ""));
        // switch first (registered after the card so it wins the hit test)
        clipClick(x, y, cardW, CARD_H, () -> {
            detail = mod;
            scroll = 0;
        });
        clipClick(sx - 2, fy - 2, 26, 14, () -> {
            mod.toggle();
            ConfigManager.markDirty();
        });
    }

    // ------------------------------------------------------------------ detail page

    private void renderDetail(DrawContext c, int mx, int my) {
        Module mod = detail;
        if (button(c, "< Back", cx, y0 + 12, 50, 16, false, mx, my, () -> {
            detail = null;
            scroll = 0;
        })) tip.offer("back", "Back to the list.");
        Gfx.boldScaled(c, mod.name(), cx + 60, y0 + 13, 1.25f, Theme.TEXT);
        // enable switch
        int sx = cx + cw - 70;
        float on = switches.computeIfAbsent(mod, k -> new Anim(mod.isEnabled() ? 1f : 0f, 16f)).target(mod.isEnabled() ? 1f : 0f).get();
        Gfx.text(c, mod.isEnabled() ? "On" : "Off", sx, y0 + 16, mod.isEnabled() ? Theme.accent() : Theme.TEXT_3);
        Gfx.round(c, sx + 22, y0 + 14, 26, 13, 6, Theme.lerp(Theme.LINE, Theme.accent(), on));
        Gfx.round(c, sx + 23 + Math.round(on * 13), y0 + 15, 11, 11, 5, Theme.lerp(Theme.TEXT_2, Theme.ON_ACCENT, on));
        if (Gfx.hovered(mx, my, sx, y0 + 12, 52, 17)) tip.offer("dsw", "Turn " + mod.name() + (mod.isEnabled() ? " off." : " on."));
        click(sx, y0 + 12, 52, 17, () -> {
            mod.toggle();
            ConfigManager.markDirty();
        });

        int y = cy - scroll;
        c.enableScissor(cx, cy, cx + cw, cy + ch);
        for (String line : Gfx.wrap(mod.description(), cw - 10)) {
            Gfx.text(c, line, cx, y, Theme.TEXT_2);
            y += 10;
        }
        if (mod.isContested()) {
            y += 2;
            Gfx.text(c, "Some servers don't allow this.", cx, y, Theme.WARN);
            y += 10;
        }
        y += 8;
        int start = y;
        Gfx.round(c, cx, y - 2, cw, SettingRows.height(mod, mod.settings(), true) + 4, 4, Theme.SURFACE);
        int mmx = Gfx.hovered(mx, my, cx, cy, cw, ch) ? mx : -1;
        y += rows.render(c, mod, mod.settings(), cx, y, cw, mmx, my, true);
        c.disableScissor();
        contentHeight = (y - start) + (start - (cy - scroll)) + 10;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - ch)));
        String d = mmx >= 0 ? rows.hoveredDescription(mx, my) : null;
        if (d != null) tip.offer("row-" + d, d);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x();
        double my = click.y() - (1 - Anim.easeOutCubic(open.peek())) * 10;
        searchFocused = false;
        if (detail != null && Gfx.hovered(mx, my, cx, cy, cw, ch) && rows.mouseClicked(mx, my, click.button())) return true;
        for (int i = rects.size() - 1; i >= 0; i--) {
            int[] r = rects.get(i);
            if (Gfx.hovered(mx, my, r[0], r[1], r[2], r[3])) {
                client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.3f));
                actions.get(i).run();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (rows.mouseDragged(click.x())) return true;
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        rows.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double v) {
        scroll = Math.max(0, scroll - (int) Math.round(v * 20));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (rows.keyPressed(key)) return true;
        if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (detail != null) {
                detail = null;
                return true;
            }
            if (!search.isEmpty()) {
                search = "";
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_RIGHT_SHIFT && search.isEmpty()) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        String s = input.asString();
        if (detail == null && !s.isEmpty() && search.length() < 24 && (Character.isLetterOrDigit(s.charAt(0)) || s.equals(" ") && !search.isEmpty())) {
            search += s;
            searchFocused = true;
            scroll = 0;
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public void close() {
        ConfigManager.save();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
