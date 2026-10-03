package dev.xsoz.client.gui;

import dev.xsoz.client.module.ModuleManager;
import dev.xsoz.client.render.Anim;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.trainer.crystal.CrystalTrainerModule;
import dev.xsoz.client.training.Experience;
import dev.xsoz.client.training.TrainingProgress;
import dev.xsoz.client.training.TrainingStore;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * The first time the client opens: the black hole forms out of nothing while "Sweden" plays, the
 * client asks your name and how good you are, offers a short tour of what's where (skip any
 * time), and then flies the camera into the black hole and out onto the main menu.
 */
public final class IntroScreen extends Screen {
    private enum Page { BOOT, NAME, SKILL, ASK_TOUR, TOUR, CUTSCENE }

    private record Slide(String title, List<String> lines, ItemStack icon, String key) {
    }

    private static final List<Slide> TOUR = List.of(
            new Slide("The main menu", List.of(
                    "Singleplayer and Multiplayer work just like normal Minecraft.",
                    "Crystal Trainer is where you learn and practise PvP.",
                    "Mods has every feature of the client, each one with a short explanation."), new ItemStack(Items.COMPASS), null),
            new Slide("Training", List.of(
                    "Drills each train one skill: placing crystals fast, getting your totem back, pearls and more.",
                    "Every drill has 3 levels. When you're ready, take the Level Up test to unlock the next drills.",
                    "Passing drills raises your rank, from the bottom all the way up."), new ItemStack(Items.END_CRYSTAL), null),
            new Slide("Free Roam", List.of(
                    "Fight bots as long as you like, on lots of maps.",
                    "Pick what everyone fights with, how many bots, teams, and how good they are - from Beginner to Hacker.",
                    "Every bot has a personality: some love anchors, some rush you, some hide in holes."), new ItemStack(Items.NETHERITE_SWORD), null),
            new Slide("Learn", List.of(
                    "Lessons for Crystal PvP and Sword PvP, written in plain words.",
                    "Lessons with a ▶ have a tutorial: you watch the move in slow motion with your own keys on screen, then you try it yourself."), new ItemStack(Items.BOOK), null),
            new Slide("Fights and the coach", List.of(
                    "Every fight you have gets a grade, with what to fix next time.",
                    "During a fight, the live coach gives short tips: totem missing, armour low, and so on."), new ItemStack(Items.TOTEM_OF_UNDYING), null),
            new Slide("Mods", List.of(
                    "Press the key below in game to open the mods menu.",
                    "Hide particles from explosions, zoom, toggle sprint, a cleaner crosshair and lots more.",
                    "Move everything on your HUD with the HUD editor."), new ItemStack(Items.COMPARATOR), "clickgui"),
            new Slide("Your practice world", List.of(
                    "Drills run in your own worlds. A Superflat world works great.",
                    "Every drill builds its own arena and puts your world and inventory back exactly as they were.",
                    "That's it. Have fun!"), new ItemStack(Items.GRASS_BLOCK), null));

    private final BlackHole hole = new BlackHole();
    private final long openedAt = System.currentTimeMillis();
    private Page page = Page.BOOT;
    private long pageAt = System.currentTimeMillis();
    private int slide;
    private TextFieldWidget nameField;
    private final List<int[]> rects = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private long cutsceneAt;

    public IntroScreen() {
        super(Text.literal("Welcome to Xsoz"));
        hole.power = 0f;
    }

    @Override
    protected void init() {
        IntroMusic.start();
        nameField = new TextFieldWidget(textRenderer, width / 2 - 90, height / 2 - 6, 180, 20, Text.literal("Name"));
        nameField.setMaxLength(24);
        String start = TrainingStore.progress().displayName();
        if (start == null) start = client.getSession() == null ? "" : client.getSession().getUsername();
        if (nameField.getText().isEmpty()) nameField.setText(start);
        nameField.setVisible(page == Page.NAME);
        addDrawableChild(nameField);
        if (page == Page.NAME) setFocused(nameField);
    }

    private void go(Page p) {
        page = p;
        pageAt = System.currentTimeMillis();
        nameField.setVisible(p == Page.NAME);
        if (p == Page.NAME) {
            setFocused(nameField);
            nameField.setFocused(true);
        } else {
            nameField.setFocused(false);
            setFocused(null);
        }
        if (p == Page.CUTSCENE) cutsceneAt = System.currentTimeMillis();
    }

    /** Client GameTest: jump to a page (0 boot .. 5 cutscene) and tour slide. */
    public void goForTests(int pageIndex, int slideIndex) {
        slide = slideIndex;
        go(Page.values()[pageIndex]);
    }

    private float pageT() { return (System.currentTimeMillis() - pageAt) / 1000f; }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        float t = (System.currentTimeMillis() - openedAt) / 1000f;
        hole.power = Math.min(1f, Anim.easeOutCubic(Math.min(1f, t / 4.5f)));
        if (page == Page.CUTSCENE) {
            float k = Math.min(1f, (System.currentTimeMillis() - cutsceneAt) / 2600f);
            hole.zoom = 1f + (float) Math.pow(k, 2.4) * 6f;
        }
        hole.render(c, width, height, width / 2f, height / 2f + (page == Page.BOOT ? 0 : 30), mouseX, mouseY);
        // a dark band behind the text
        if (page != Page.BOOT && page != Page.CUTSCENE) c.fillGradient(0, 0, width, height / 2 + 20, 0xC0050608, 0x00050608);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        rects.clear();
        actions.clear();
        super.render(c, mx, my, delta);
        float a = Math.min(1f, pageT() / 0.5f);
        switch (page) {
            case BOOT -> renderBoot(c);
            case NAME -> renderName(c, mx, my, a);
            case SKILL -> renderSkill(c, mx, my, a);
            case ASK_TOUR -> renderAsk(c, mx, my, a);
            case TOUR -> renderTour(c, mx, my, a);
            case CUTSCENE -> renderCutscene(c);
        }
        if (page == Page.TOUR || page == Page.ASK_TOUR) {
            button(c, "Skip", width - 60, 8, 50, 16, false, mx, my, () -> go(Page.CUTSCENE));
        }
    }

    // =========================================================================== pages

    private void renderBoot(DrawContext c) {
        float t = (System.currentTimeMillis() - openedAt) / 1000f;
        String word = "XSOZ";
        float scale = 5f;
        int w = Math.round(Gfx.displayWidth(word) * scale);
        int shown = (int) Math.min(word.length(), Math.max(0, (t - 1.2f) / 0.35f));
        String part = word.substring(0, shown);
        int top = Math.max(6, height / 2 - 150);
        if (!part.isEmpty()) Gfx.display(c, part, width / 2f - w / 2f, top, scale, Theme.accent());
        if (t > 3.0f) {
            float a = Math.min(1f, (t - 3.0f) / 0.8f);
            Gfx.textCentered(c, "C L I E N T", width / 2, top + 48, Theme.alpha(Theme.TEXT_2, a));
        }
        if (t > 4.2f) {
            float a = Math.min(1f, (t - 4.2f) / 0.8f);
            Gfx.boldScaled(c, "Welcome.", width / 2f - Gfx.widthBold("Welcome.") * 0.75f, height - 64, 1.5f, Theme.alpha(Theme.TEXT, a));
            float p = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 300.0);
            Gfx.textCentered(c, "Click or press Space", width / 2, height - 40, Theme.alpha(Theme.TEXT_3, a * p));
        }
        if (t > 9f) go(Page.NAME);
    }

    private void renderName(DrawContext c, int mx, int my, float a) {
        Gfx.boldScaled(c, "What should we call you?", width / 2f - Gfx.widthBold("What should we call you?") * 0.75f, height / 2f - 50, 1.5f, Theme.alpha(Theme.TEXT, a));
        Gfx.textCentered(c, "You can change it later in Crystal Trainer > Settings.", width / 2, height / 2 - 28, Theme.alpha(Theme.TEXT_3, a));
        button(c, "Continue", width / 2 - 50, height / 2 + 24, 100, 20, true, mx, my, this::saveName);
    }

    private void saveName() {
        String n = nameField.getText().trim();
        TrainingProgress p = TrainingStore.progress();
        p.playerName = n.isEmpty() ? null : n;
        TrainingStore.save();
        go(Page.SKILL);
    }

    private String name() {
        String n = TrainingStore.progress().displayName();
        if (n == null && client != null && client.getSession() != null) n = client.getSession().getUsername();
        return n == null ? "friend" : n;
    }

    private void renderSkill(DrawContext c, int mx, int my, float a) {
        String q = "How good are you at PvP, " + name() + "?";
        Gfx.boldScaled(c, q, width / 2f - Gfx.widthBold(q) * 0.7f, 30, 1.4f, Theme.alpha(Theme.TEXT, a));
        Gfx.textCentered(c, "This only changes how the trainer teaches you. You can change it any time.", width / 2, 50, Theme.alpha(Theme.TEXT_3, a));
        Experience[] all = Experience.values();
        int cw = Math.min(150, (width - 40 - 8 * (all.length - 1)) / all.length);
        int x = width / 2 - (cw * all.length + 8 * (all.length - 1)) / 2;
        int y = 70;
        for (Experience e : all) {
            boolean hov = Gfx.hovered(mx, my, x, y, cw, 74);
            Gfx.round(c, x, y, cw, 74, 6, hov ? Theme.withAlpha(Theme.accent(), 0x50) : 0xD0151719);
            Gfx.outline(c, x, y, cw, 74, 1, hov ? Theme.accent() : Theme.LINE);
            int ly = y + 8;
            for (String l : Gfx.wrap(e.title, cw - 12)) {
                Gfx.textBold(c, l, x + 6, ly, Theme.TEXT);
                ly += 10;
            }
            ly += 3;
            for (String l : Gfx.wrap(e.blurb, cw - 12)) {
                if (ly > y + 66) break;
                Gfx.text(c, l, x + 6, ly, Theme.TEXT_2);
                ly += 10;
            }
            rects.add(new int[] {x, y, cw, 74});
            actions.add(() -> pickSkill(e));
            x += cw + 8;
        }
    }

    private void pickSkill(Experience e) {
        TrainingProgress p = TrainingStore.progress();
        p.experience = e.name();
        p.seenIntro = true;
        TrainingStore.save();
        CrystalTrainerModule mod = ModuleManager.get(CrystalTrainerModule.class);
        if (mod != null) mod.applyExperience(e);
        go(Page.ASK_TOUR);
    }

    private void renderAsk(DrawContext c, int mx, int my, float a) {
        String q = "Want a quick look around?";
        Gfx.boldScaled(c, q, width / 2f - Gfx.widthBold(q) * 0.75f, height / 2f - 60, 1.5f, Theme.alpha(Theme.TEXT, a));
        Gfx.textCentered(c, "It takes about a minute. You can skip it at any time.", width / 2, height / 2 - 36, Theme.alpha(Theme.TEXT_3, a));
        button(c, "Show me around", width / 2 - 110, height / 2 - 14, 104, 20, true, mx, my, () -> {
            slide = 0;
            go(Page.TOUR);
        });
        button(c, "Skip", width / 2 + 6, height / 2 - 14, 104, 20, false, mx, my, () -> go(Page.CUTSCENE));
    }

    private void renderTour(DrawContext c, int mx, int my, float a) {
        Slide s = TOUR.get(slide);
        int w = Math.min(width - 40, 380);
        int x = width / 2 - w / 2;
        int y = 26;
        int h = 150;
        Gfx.shadow(c, x, y, w, h, 6, a);
        Gfx.round(c, x, y, w, h, 8, Theme.alpha(0xEE101114, a));
        Gfx.round(c, x, y, w, 3, 1, Theme.alpha(Theme.accent(), a));
        // the icon, big and slowly turning
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(x + 30, y + 34);
        m.rotate((float) Math.sin(System.currentTimeMillis() / 700.0) * 0.15f);
        m.scale(2.4f, 2.4f);
        c.drawItem(s.icon(), -8, -8);
        m.popMatrix();
        Gfx.boldScaled(c, s.title(), x + 58, y + 16, 1.4f, Theme.alpha(Theme.TEXT, a));
        Gfx.text(c, (slide + 1) + " / " + TOUR.size(), x + w - 40, y + 10, Theme.TEXT_3);
        int ly = y + 60;
        for (String line : s.lines()) {
            for (String l : Gfx.wrap(line, w - 40)) {
                Gfx.text(c, l, x + 20, ly, Theme.alpha(Theme.TEXT_2, a));
                ly += 11;
            }
            ly += 3;
        }
        if (s.key() != null && dev.xsoz.client.XsozClient.menuKey != null) {
            String k = dev.xsoz.client.XsozClient.menuKey.getBoundKeyLocalizedText().getString();
            int kw = Gfx.widthBold(k) + 16;
            Gfx.round(c, x + 20, ly + 2, kw, 18, 4, Theme.accent());
            Gfx.textBold(c, k, x + 28, ly + 7, 0xFF101114);
        }
        // dots
        int dx = width / 2 - TOUR.size() * 6;
        for (int i = 0; i < TOUR.size(); i++) Gfx.round(c, dx + i * 12, y + h + 8, 6, 6, 3, i == slide ? Theme.accent() : Theme.LINE);
        int by = y + h + 22;
        if (slide > 0) button(c, "Back", width / 2 - 110, by, 104, 20, false, mx, my, () -> slide--);
        button(c, slide == TOUR.size() - 1 ? "Let's go" : "Next", width / 2 + 6, by, 104, 20, true, mx, my, () -> {
            if (slide < TOUR.size() - 1) slide++;
            else go(Page.CUTSCENE);
        });
    }

    private void renderCutscene(DrawContext c) {
        float k = (System.currentTimeMillis() - cutsceneAt) / 1000f;
        if (k < 0.8f) {
            String hi = "Let's go, " + name() + ".";
            Gfx.boldScaled(c, hi, width / 2f - Gfx.widthBold(hi) * 0.75f, 40, 1.5f, Theme.alpha(Theme.TEXT, 1f - k / 0.8f));
        }
        // dive into the dark; the menu fades in out of it (no bright flash)
        if (k > 1.9f) {
            float b = Math.min(1f, (k - 1.9f) / 0.7f);
            c.fill(0, 0, width, height, ((int) (b * 255) << 24));
        }
        if (k > 2.9f) finish();
    }

    private void finish() {
        TrainingProgress p = TrainingStore.progress();
        p.introDone = true;
        TrainingStore.save();
        XsozTitleScreen.fadeFromBlackAt = System.currentTimeMillis();
        client.setScreen(new XsozTitleScreen());
    }

    // =========================================================================== input

    private void button(DrawContext c, String label, int x, int y, int w, int h, boolean primary, int mx, int my, Runnable r) {
        boolean hov = Gfx.hovered(mx, my, x, y, w, h);
        Gfx.round(c, x, y, w, h, 5, primary ? (hov ? Theme.withAlpha(Theme.accent(), 0xFF) : Theme.withAlpha(Theme.accent(), 0xC8)) : (hov ? 0xE0303238 : 0xD0202226));
        Gfx.textCentered(c, label, x + w / 2, y + (h - 8) / 2, primary ? 0xFF101114 : Theme.TEXT);
        rects.add(new int[] {x, y, w, h});
        actions.add(r);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (page == Page.BOOT) {
            go(Page.NAME);
            return true;
        }
        for (int i = rects.size() - 1; i >= 0; i--) {
            int[] r = rects.get(i);
            if (Gfx.hovered(click.x(), click.y(), r[0], r[1], r[2], r[3])) {
                client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1f));
                actions.get(i).run();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int k = input.key();
        if (page == Page.BOOT && (k == GLFW.GLFW_KEY_SPACE || k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_ESCAPE)) {
            go(Page.NAME);
            return true;
        }
        if (page == Page.NAME && (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER)) {
            saveName();
            return true;
        }
        if (k == GLFW.GLFW_KEY_ESCAPE && (page == Page.TOUR || page == Page.ASK_TOUR)) {
            go(Page.CUTSCENE);
            return true;
        }
        if (page == Page.TOUR && (k == GLFW.GLFW_KEY_RIGHT || k == GLFW.GLFW_KEY_ENTER)) {
            if (slide < TOUR.size() - 1) slide++;
            else go(Page.CUTSCENE);
            return true;
        }
        if (page == Page.TOUR && k == GLFW.GLFW_KEY_LEFT && slide > 0) {
            slide--;
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean shouldPause() { return false; }
}
