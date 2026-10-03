package dev.xsoz.client.trainer.crystal;

import dev.xsoz.client.gui.HoverTip;
import dev.xsoz.client.gui.SettingRows;
import dev.xsoz.client.module.ModuleManager;
import dev.xsoz.client.render.Anim;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.trainer.TrainingContext;
import dev.xsoz.client.training.DrillDef;
import dev.xsoz.client.training.Experience;
import dev.xsoz.client.training.Rank;
import dev.xsoz.client.training.TrainingGate;
import dev.xsoz.client.training.TrainingManager;
import dev.xsoz.client.training.TrainingProgress;
import dev.xsoz.client.training.TrainingStore;
import dev.xsoz.client.training.drill.Drill;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * The Crystal Trainer: rank and next step, the training curriculum, the rank ladder, every recorded
 * fight, lessons, and settings. Asks the player's experience level the first time it opens.
 */
public final class TrainerScreen extends Screen {
    public enum Tab {
        OVERVIEW("Overview", "Your rank, level and what to do next."),
        TRAINING("Training", "Drills that train one skill each. Pass a drill's Level Up to unlock the next."),
        FREE_ROAM("Free Roam", "Fight bots as long as you like: any mode, map, number of bots, teams and skill."),
        RANKS("Ranks", "Every rank and exactly what it takes."),
        FIGHTS("Fights", "Every fight you've had, graded, with what to fix."),
        LEARN("Learn", "Lessons for each kind of PvP, with slow-motion tutorials you can try yourself."),
        SETTINGS("Settings", "Your experience level, the live coach, practice servers.");

        final String title;
        final String tip;

        Tab(String t, String tip) {
            title = t;
            this.tip = tip;
        }
    }

    private final Screen parent;
    private final Anim open = new Anim(0f, 10f);
    private final SettingRows rows = new SettingRows();
    private final HoverTip tip = new HoverTip();
    private Tab tab;
    private int x0, y0, w, h;
    private int contentX, contentY, contentW, contentH;
    private final List<Runnable> clickActions = new ArrayList<>();
    private final List<int[]> clickRects = new ArrayList<>();

    // fights
    private String selectedFight;
    private TrainerStore.Loaded loaded;
    private int fightListScroll;
    private int reportScroll;
    private int reportHeight;
    // learn
    private int learnIndex;
    private int learnScroll;
    private Lessons.Category learnCategory = Lessons.Category.CRYSTAL;
    private int learnListScroll;
    private int learnListHeight;
    private int learnPanelHeight;
    private int learnListW;
    // training
    private DrillDef selectedDrill;
    private Drill.Mode selectedMode;
    private double selectedP = -1;
    private Boolean selectedHints;
    private boolean selectedEndless;
    private int selectedLevel = -1;
    // developer code
    private String devCode = "";
    private boolean devFocused;
    private String nameDraft;
    private boolean nameFocused;
    private String devMessage;
    private boolean draggingSlider;
    private int sliderX, sliderW;
    private String startError;
    private String startDetail;
    private boolean confirmReset;
    private int trainingScroll;
    private int trainingListHeight;
    private int detailScroll;
    private int detailHeight;
    private int detailX;
    private int detailW;
    // generic scroll for the long single-column tabs
    private int pageScroll;
    private int pageHeight;

    public TrainerScreen(Screen parent) { this(parent, null); }

    public TrainerScreen(Screen parent, Tab initial) {
        super(Text.literal("Crystal Trainer"));
        this.parent = parent;
        this.tab = initial != null ? initial : TrainingManager.running() ? Tab.TRAINING : Tab.OVERVIEW;
    }

    /** For the client GameTest: a Learn category and lesson. */
    public void learnForTests(Lessons.Category cat, int index) {
        learnCategory = cat;
        learnIndex = index;
    }

    /** For the client GameTest: show a drill with a level picked. */
    public void selectForTests(DrillDef d, int level) {
        selectedDrill = d;
        resetDrillChoice();
        selectedLevel = level;
    }

    private CrystalTrainerModule module() { return ModuleManager.get(CrystalTrainerModule.class); }

    private Experience experience() {
        Experience e = TrainingStore.progress().experience();
        return e == null ? Experience.LEARNING : e;
    }

    @Override
    protected void init() {
        w = Math.min(width - 20, 640);
        h = Math.min(height - 20, 380);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        contentX = x0 + 116;
        contentY = y0 + 36;
        contentW = w - 116 - 12;
        contentH = h - 36 - 10;
        open.snap(0f);
        open.target(1f);
        TrainerProfile p = TrainerStore.profile();
        if (selectedFight == null && !p.history.isEmpty()) selectedFight = p.history.get(0).id();
        if (selectedDrill == null) selectedDrill = nextDrill();
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Gfx.rect(c, 0, 0, width, height, Theme.alpha(0xC8090A0C, open.get()));
    }

    private boolean button(DrawContext c, String label, int x, int y, int bw, int bh, boolean primary, int mx, int my, Runnable action) {
        boolean hover = Gfx.hovered(mx, my, x, y, bw, bh);
        int bg = primary ? (hover ? Theme.ACCENT_HOVER : Theme.accent()) : (hover ? Theme.HOVER : Theme.RAISED);
        Gfx.round(c, x, y, bw, bh, 4, bg);
        Gfx.textCentered(c, label, x + bw / 2, y + (bh - 8) / 2, primary ? Theme.ON_ACCENT : Theme.TEXT);
        click(x, y, bw, bh, action);
        return hover;
    }

    private void disabledButton(DrawContext c, String label, int x, int y, int bw, int bh) {
        Gfx.round(c, x, y, bw, bh, 4, Theme.LINE_SOFT);
        Gfx.textCentered(c, label, x + bw / 2, y + (bh - 8) / 2, Theme.TEXT_3);
    }

    private void click(int x, int y, int bw, int bh, Runnable action) {
        clickRects.add(new int[] {x, y, bw, bh});
        clickActions.add(action);
    }

    /** Click target inside a scrolled region, clipped to it. */
    private void clipClick(int x, int y, int bw, int bh, int top, int bottom, Runnable r) {
        int t = Math.max(y, top);
        int b = Math.min(y + bh, bottom);
        if (b > t) click(x, t, bw, b - t, r);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        clickRects.clear();
        clickActions.clear();
        rows.beginFrame();
        tip.begin();
        float a = Anim.easeOutCubic(open.get());
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(0, (1 - a) * 12);

        Gfx.shadow(c, x0, y0, w, h, 6, 1f);
        Gfx.round(c, x0, y0, w, h, 6, 0xFA111317);
        Gfx.outline(c, x0, y0, w, h, 1, Theme.LINE_SOFT);
        renderRail(c, mx, my);
        Gfx.boldScaled(c, tab.title, contentX, y0 + 13, 1.25f, Theme.TEXT);
        if (button(c, "Close", x0 + w - 54, y0 + 10, 44, 16, false, mx, my, this::close)) tip.offer("close", "Close the trainer (Esc).");

        boolean onboarding = TrainingStore.progress().experience() == null;
        if (!onboarding) {
            switch (tab) {
                case OVERVIEW -> renderOverview(c, mx, my);
                case TRAINING -> renderTraining(c, mx, my);
                case FREE_ROAM -> renderFreeRoam(c, mx, my);
                case RANKS -> renderRanks(c, mx, my);
                case FIGHTS -> renderFights(c, mx, my);
                case LEARN -> renderLearn(c, mx, my);
                case SETTINGS -> renderSettings(c, mx, my);
            }
        }
        m.popMatrix();
        if (onboarding) {
            clickRects.clear();
            clickActions.clear();
            renderOnboarding(c, mx, my);
        }
        tip.render(c, mx, my);
    }

    // =========================================================================== rail

    private void renderRail(DrawContext c, int mx, int my) {
        Gfx.round(c, x0, y0, 106, h, 6, Theme.RAIL);
        Gfx.display(c, "XSOZ", x0 + 12, y0 + 12, 1.4f, Theme.accent());
        Gfx.text(c, "CRYSTAL TRAINER", x0 + 12, y0 + 26, Theme.TEXT_3);
        if (dev.xsoz.client.training.TrainingFlags.testMode) {
            Gfx.round(c, x0 + 8, y0 + h - 64, 90, 14, 3, 0xFF3A2A0E);
            Gfx.textCentered(c, "TEST MODE", x0 + 53, y0 + h - 61, 0xFFFFB020);
            if (Gfx.hovered(mx, my, x0 + 8, y0 + h - 64, 90, 14)) {
                tip.offer("testmode", "Test mode: every drill is unlocked and nothing is saved (no passes, XP, stats or fights). It ends when you close Minecraft.");
            }
        }
        int ty = y0 + 46;
        for (Tab t : Tab.values()) {
            boolean sel = t == tab;
            boolean hover = Gfx.hovered(mx, my, x0 + 6, ty, 94, 18);
            if (sel) Gfx.round(c, x0 + 6, ty, 94, 18, 4, Theme.withAlpha(Theme.accent(), 0x26));
            else if (hover) Gfx.round(c, x0 + 6, ty, 94, 18, 4, Theme.HOVER);
            if (sel) Gfx.rect(c, x0 + 6, ty + 4, 2, 10, Theme.accent());
            Gfx.text(c, t.title, x0 + 14, ty + 5, sel ? Theme.TEXT : Theme.TEXT_2);
            if (hover) tip.offer("tab-" + t, t.tip);
            final Tab target = t;
            click(x0 + 6, ty, 94, 18, () -> {
                tab = target;
                pageScroll = 0;
                reportScroll = 0;
            });
            ty += 21;
        }
        // rank chip
        Rank rank = Rank.current(rankInputs());
        TrainingProgress prog = TrainingStore.progress();
        int level = TrainingProgress.level(prog.xp);
        int ry = y0 + h - 44;
        Gfx.round(c, x0 + 8, ry, 90, 34, 4, Theme.SURFACE);
        icon(c, rankItem(rank), x0 + 12, ry + 3, 0.75f, false);
        Gfx.textBold(c, rank.title, x0 + 28, ry + 6, rank.color);
        Gfx.text(c, "Level " + level, x0 + 14, ry + 19, Theme.TEXT_2);
        long[] lp = TrainingProgress.levelProgress(prog.xp);
        Gfx.round(c, x0 + 14, ry + 29, 78, 2, 1, Theme.LINE_SOFT);
        Gfx.round(c, x0 + 14, ry + 29, (int) Math.max(2, 78 * lp[0] / Math.max(1, lp[1])), 2, 1, Theme.accent());
        if (Gfx.hovered(mx, my, x0 + 8, ry, 90, 34)) {
            tip.offer("rankchip", "Rank " + rank.title + ". Level " + level + " (" + lp[0] + "/" + lp[1] + " XP to the next level). XP comes from every hit in training; ranks come from passed tests and real fights.");
        }
    }

    // =========================================================================== onboarding

    private void renderOnboarding(DrawContext c, int mx, int my) {
        Gfx.rect(c, 0, 0, width, height, 0xB0000000);
        int mw = Math.min(width - 40, 420);
        int mh = 232;
        int mx0 = (width - mw) / 2;
        int my0 = (height - mh) / 2;
        Gfx.shadow(c, mx0, my0, mw, mh, 6, 1f);
        Gfx.round(c, mx0, my0, mw, mh, 6, 0xFF14161B);
        Gfx.outline(c, mx0, my0, mw, mh, 1, Theme.LINE);
        Gfx.boldScaled(c, "Welcome to the Crystal Trainer", mx0 + 16, my0 + 14, 1.25f, Theme.TEXT);
        Gfx.text(c, "How much experience do you have? The trainer adapts to you.", mx0 + 16, my0 + 30, Theme.TEXT_2);
        Gfx.text(c, "You can change this any time in Settings.", mx0 + 16, my0 + 41, Theme.TEXT_3);
        int y = my0 + 58;
        for (Experience e : Experience.values()) {
            boolean hov = Gfx.hovered(mx, my, mx0 + 16, y, mw - 32, 36);
            Gfx.round(c, mx0 + 16, y, mw - 32, 36, 5, hov ? Theme.HOVER : Theme.SURFACE);
            if (hov) Gfx.round(c, mx0 + 16, y + 6, 2, 24, 1, Theme.accent());
            Gfx.textBold(c, e.title, mx0 + 26, y + 8, Theme.TEXT);
            Gfx.text(c, e.blurb, mx0 + 26, y + 20, Theme.TEXT_3);
            if (hov) tip.offer("exp-" + e, explainExperience(e));
            click(mx0 + 16, y, mw - 32, 36, () -> chooseExperience(e));
            y += 41;
        }
    }

    private static String explainExperience(Experience e) {
        return switch (e) {
            case NEW -> "Starts with the Minecraft basics lesson, the slowest drill speeds, hints on, and a calm coach (survival cues only).";
            case LEARNING -> "Slow drill speeds with hints, speeds that adapt to you, and the core coaching cues.";
            case REFRESHING -> "Medium starting speeds, no hints, most coaching cues.";
            case PRO -> "Fast starting speeds, no hints, every coaching cue. Go straight for Level Ups.";
        };
    }

    private void chooseExperience(Experience e) {
        TrainingProgress p = TrainingStore.progress();
        p.experience = e.name();
        p.seenIntro = true;
        TrainingStore.save();
        CrystalTrainerModule mod = module();
        if (mod != null) mod.applyExperience(e);
        selectedMode = null;
        selectedP = -1;
        selectedHints = null;
        if (e == Experience.NEW || e == Experience.LEARNING) {
            tab = Tab.LEARN;
            learnCategory = e == Experience.NEW ? Lessons.Category.BASICS : Lessons.Category.CRYSTAL;
            learnIndex = 0;
        } else {
            tab = Tab.TRAINING;
        }
    }

    // =========================================================================== ranking inputs

    private Rank.Inputs rankInputs() {
        TrainerProfile p = TrainerStore.profile();
        List<Rank.FightLite> fights = new ArrayList<>();
        for (TrainerProfile.Summary s : p.history) fights.add(new Rank.FightLite(s.overall(), s.won(), s.vsPlayer()));
        return new Rank.Inputs(TrainingStore.progress(), fights, p.levels(), p.solidCount());
    }

    /** The drill to do next: first unlocked, unpassed drill in curriculum order. */
    private DrillDef nextDrill() {
        TrainingProgress prog = TrainingStore.progress();
        for (DrillDef d : DrillDef.values()) if (d.tier > 0 && dev.xsoz.client.training.TrainingFlags.unlocked(prog, d) && !prog.passed(d)) return d;
        return DrillDef.KEYBIND_REFLEX;
    }

    // =========================================================================== overview

    private void renderOverview(DrawContext c, int mx, int my) {
        TrainerProfile p = TrainerStore.profile();
        TrainingProgress prog = TrainingStore.progress();
        Rank.Inputs in = rankInputs();
        Rank rank = Rank.current(in);
        int x = contentX;
        int y = contentY;

        // rank card
        int cardW = 170;
        Gfx.round(c, x, y, cardW, 74, 5, Theme.SURFACE);
        Gfx.text(c, "Rank", x + 10, y + 8, Theme.TEXT_3);
        icon(c, rankItem(rank), x + cardW - 46, y + 10, 2.25f, false);
        Gfx.boldScaled(c, rank.title, x + 10, y + 20, 1.6f, rank.color);
        int level = TrainingProgress.level(prog.xp);
        long[] lp = TrainingProgress.levelProgress(prog.xp);
        Gfx.text(c, "Level " + level + "  -  " + prog.xp + " XP", x + 10, y + 44, Theme.TEXT_2);
        Gfx.round(c, x + 10, y + 57, cardW - 20, 4, 2, Theme.LINE_SOFT);
        Gfx.round(c, x + 10, y + 57, (int) Math.max(4, (cardW - 20) * lp[0] / Math.max(1, lp[1])), 4, 2, Theme.accent());
        if (Gfx.hovered(mx, my, x, y, cardW, 74)) tip.offer("ov-rank", "Ranks are earned with Level Up tests and, from Gold up, real fights against players. See the Ranks tab for every requirement.");

        // next step card
        int nx = x + cardW + 8;
        int nw = contentW - cardW - 8;
        Gfx.round(c, nx, y, nw, 74, 5, Theme.SURFACE);
        Gfx.text(c, "Next step", nx + 10, y + 8, Theme.TEXT_3);
        DrillDef next = nextDrill();
        Rank nr = rank.next();
        boolean allPassed = true;
        for (DrillDef d : DrillDef.values()) if (d.tier > 0 && !prog.passed(d)) allPassed = false;
        if (!allPassed) {
            Gfx.textBold(c, next.title, nx + 10, y + 20, Theme.TEXT);
            List<String> sl = Gfx.wrap(next.summary, nw - 20);
            for (int i = 0; i < Math.min(2, sl.size()); i++) Gfx.text(c, sl.get(i), nx + 10, y + 32 + i * 10, Theme.TEXT_2);
            if (button(c, "Go to training", nx + 10, y + 54, 90, 14, true, mx, my, () -> {
                tab = Tab.TRAINING;
                selectedDrill = next;
                resetDrillChoice();
            })) tip.offer("ov-go", "Opens this drill in the Training tab.");
        } else if (nr != null) {
            Gfx.textBold(c, "Prove it in fights for " + nr.title, nx + 10, y + 20, Theme.TEXT);
            for (Rank.Requirement r : nr.requirements(in)) {
                if (!r.met()) {
                    Gfx.text(c, Gfx.fit(r.label() + " (" + r.progress() + ")", nw - 20), nx + 10, y + 34, Theme.TEXT_2);
                    break;
                }
            }
        } else {
            Gfx.textBold(c, "Netherite. Every test passed.", nx + 10, y + 20, rank.color);
            Gfx.text(c, "Keep fighting - fight-based ranks are rolling.", nx + 10, y + 34, Theme.TEXT_2);
        }

        // skills
        int by = y + 84;
        Gfx.text(c, "Fight skills (smoothed over your recent recorded fights)", x, by, Theme.TEXT_3);
        by += 13;
        for (Skill s : Skill.values()) {
            int l = p.level(s);
            int trend = p.trend(s);
            Gfx.text(c, s.title, x, by + 2, Theme.TEXT_2);
            int barX = x + 112;
            int barW = contentW - 112 - 78;
            Gfx.round(c, barX, by + 3, barW, 6, 3, Theme.LINE_SOFT);
            if (l >= 0) Gfx.round(c, barX, by + 3, Math.max(6, barW * l / 100), 6, 3, FightReport.gradeColor(FightReport.grade(l)));
            Gfx.textBold(c, l < 0 ? "-" : String.valueOf(l), barX + barW + 6, by + 2, Theme.TEXT);
            if (trend != 0) Gfx.text(c, (trend > 0 ? "+" : "") + trend, barX + barW + 26, by + 2, trend > 0 ? Theme.accent() : 0xFFFF6D7E);
            if (p.solid(s)) {
                Gfx.round(c, barX + barW + 46, by + 1, 28, 10, 3, Theme.withAlpha(Theme.accent(), 0x30));
                Gfx.text(c, "Solid", barX + barW + 49, by + 2, Theme.accent());
            }
            if (Gfx.hovered(mx, my, x, by, contentW, 14)) {
                tip.offer("skill-" + s, s.blurb + ". " + (l < 0 ? "Not measured yet - it needs a recorded fight where it came up." : "Level " + l + " of 100.")
                        + " 'Solid' means 80+ over at least three fights.");
            }
            by += 15;
        }
        by += 4;
        if (!p.history.isEmpty()) {
            TrainerProfile.Summary s = p.history.get(0);
            Gfx.round(c, x, by, contentW, 22, 4, Theme.SURFACE);
            Gfx.textBold(c, s.grade(), x + 8, by + 7, FightReport.gradeColor(s.grade()));
            Gfx.text(c, Gfx.fit("Last fight vs " + s.opponent() + " - " + outcomeWord(s.outcome()) + " - " + s.overall() + "/100", contentW - 110), x + 22, by + 7, Theme.TEXT_2);
            button(c, "Review", x + contentW - 56, by + 4, 50, 14, false, mx, my, () -> {
                selectedFight = s.id();
                loaded = null;
                tab = Tab.FIGHTS;
            });
        } else {
            Gfx.text(c, "No fights recorded yet. Fights are detected automatically.", x, by + 4, Theme.TEXT_3);
        }
    }

    // =========================================================================== training

    private void resetDrillChoice() {
        selectedMode = null;
        selectedP = -1;
        selectedHints = null;
        selectedLevel = -1;
        startError = null;
        startDetail = null;
        confirmReset = false;
    }

    private void renderTraining(DrawContext c, int mx, int my) {
        TrainingProgress prog = TrainingStore.progress();
        int x = contentX;
        int y = contentY;
        // track selector
        String[] tracks = {"Crystal PvP", "Sword", "Mace", "UHC"};
        int tx = x;
        for (int i = 0; i < tracks.length; i++) {
            int tw = Gfx.width(tracks[i]) + 18;
            boolean active = i == 0;
            Gfx.round(c, tx, y, tw, 16, 8, active ? Theme.withAlpha(Theme.accent(), 0x30) : Theme.SURFACE);
            Gfx.text(c, tracks[i], tx + 9, y + 4, active ? Theme.accent() : Theme.TEXT_3);
            if (Gfx.hovered(mx, my, tx, y, tw, 16)) {
                tip.offer("track-" + i, active ? "Crystal PvP training: every drill below." : tracks[i] + " training is coming next. Crystal PvP is first because it has the most to drill.");
            }
            tx += tw + 6;
        }
        boolean customKit = dev.xsoz.client.training.KitStore.usesCustom(dev.xsoz.client.training.KitSpec.Mode.CRYSTAL);
        String kitLabel = customKit ? "Kit: custom" : "Select Kit";
        int kw = Gfx.width(kitLabel) + 22;
        if (button(c, kitLabel, x + contentW - kw, y - 1, kw, 17, false, mx, my, () -> client.setScreen(new dev.xsoz.client.gui.KitEditorScreen(this)))) {
            tip.offer("kit", "Choose the kit every drill hands you: the standard crystal kit in your Combo Binds layout, or your own - any items, any enchantments. Applies to all drills.");
        }
        y += 22;

        Drill running = TrainingManager.active();
        if (running != null) {
            Gfx.round(c, x, y, contentW, 22, 4, 0xFF1C2A1F);
            Gfx.text(c, "Running now: " + running.def.title + " (" + running.mode.title + ")", x + 8, y + 7, Theme.accent());
            if (button(c, "Stop", x + contentW - 52, y + 4, 46, 14, false, mx, my, () -> {
                TrainingManager.stop();
            })) tip.offer("stop", "Stops the drill now and puts your inventory, position and the world back exactly as they were.");
            y += 28;
        }

        // left: curriculum list
        int listW = contentW < 540 ? 152 : 196;
        int listTop = y;
        int listH = contentY + contentH - y;
        c.enableScissor(x, listTop, x + listW, listTop + listH);
        int ly = listTop - trainingScroll;
        for (int tier = 1; tier <= 4; tier++) {
            Rank tr = Rank.values()[tier];
            icon(c, rankItem(tr), x + 1, ly + 1, 0.75f, false);
            String earns = "-> " + tr.title;
            String head = "TIER " + tier + "  -  " + DrillDef.TIER_NAMES[tier].toUpperCase(Locale.ROOT);
            Gfx.text(c, Gfx.fit(head, listW - 24 - Gfx.width(earns)), x + 17, ly + 3, Theme.TEXT_3);
            Gfx.text(c, earns, x + listW - 4 - Gfx.width(earns), ly + 3, tr.color);
            if (Gfx.hovered(mx, my, x, ly, listW, 14)) {
                tip.offer("tier-" + tier, "Pass every Level Up in this tier to reach " + tr.title + (tier >= 3 ? " (plus the fight evidence in the Ranks tab)." : "."));
            }
            ly += 16;
            for (DrillDef d : DrillDef.tier(tier)) {
                boolean sel = d == selectedDrill;
                boolean unlocked = dev.xsoz.client.training.TrainingFlags.unlocked(prog, d);
                boolean passed = prog.passed(d);
                boolean hov = Gfx.hovered(mx, my, x, ly, listW, 20) && my >= listTop && my < listTop + listH;
                Gfx.round(c, x, ly, listW, 20, 4, sel ? Theme.withAlpha(Theme.accent(), 0x22) : hov ? Theme.HOVER : Theme.SURFACE);
                int ic = passed ? Theme.accent() : unlocked ? 0xFFECEEF2 : Theme.EDGE;
                if (passed) {
                    Gfx.round(c, x + 6, ly + 6, 8, 8, 4, ic);
                    Gfx.rect(c, x + 8, ly + 9, 2, 2, Theme.ON_ACCENT);
                    Gfx.rect(c, x + 10, ly + 8, 2, 2, Theme.ON_ACCENT);
                } else if (unlocked) {
                    Gfx.outline(c, x + 6, ly + 6, 8, 8, 1, ic);
                } else {
                    Gfx.rect(c, x + 7, ly + 9, 6, 5, ic);
                    Gfx.outline(c, x + 8, ly + 5, 4, 5, 1, ic);
                }
                Gfx.text(c, d.title, x + 20, ly + 6, unlocked ? Theme.TEXT : Theme.TEXT_3);
                String right = passed ? "Passed" : unlocked ? "" : "Locked";
                Gfx.text(c, right, x + listW - 8 - Gfx.width(right), ly + 6, passed ? Theme.accent() : Theme.TEXT_3);
                if (hov) {
                    String why = passed ? "Passed - practise it any time." : unlocked ? "Available." : "Locked until you pass: " + names(d.prerequisites()) + ".";
                    tip.offer("drill-" + d, d.summary + " " + why);
                }
                final DrillDef target = d;
                clipClick(x, ly, listW, 20, listTop, listTop + listH, () -> {
                    if (selectedDrill != target) {
                        selectedDrill = target;
                        detailScroll = 0;
                        resetDrillChoice();
                    }
                });
                ly += 23;
            }
            if (tier < 4) {
                String order = tier == 1 ? "Any order within a tier." : "";
                if (!order.isEmpty()) {
                    Gfx.text(c, order, x + 2, ly, Theme.TEXT_3);
                    ly += 10;
                }
            }
            ly += 4;
        }
        trainingListHeight = ly + trainingScroll - listTop;
        c.disableScissor();

        // right: drill detail
        // the detail panel scrolls when the window is small; clicks are clipped to it
        int dx = x + listW + 10;
        int dw = contentW - listW - 10;
        detailX = dx;
        detailW = dw;
        Gfx.round(c, dx, listTop, dw, listH, 5, Theme.SURFACE);
        int before = clickRects.size();
        c.enableScissor(dx, listTop, dx + dw, listTop + listH);
        int used = renderDrillDetail(c, mx, my, dx, listTop - detailScroll, dw, listH);
        c.disableScissor();
        detailHeight = used;
        detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, detailHeight - listH)));
        for (int i = clickRects.size() - 1; i >= before; i--) {
            int[] r = clickRects.get(i);
            int top = Math.max(r[1], listTop);
            int bottom = Math.min(r[1] + r[3], listTop + listH);
            if (bottom <= top) {
                clickRects.remove(i);
                clickActions.remove(i);
            } else {
                r[1] = top;
                r[3] = bottom - top;
            }
        }
        if (detailHeight > listH) {
            int barH = Math.max(12, listH * listH / detailHeight);
            int barY = listTop + (listH - barH) * detailScroll / Math.max(1, detailHeight - listH);
            Gfx.round(c, dx + dw - 3, barY, 2, barH, 1, Theme.LINE);
        }
    }

    // =========================================================================== free roam

    private dev.xsoz.client.training.bot.FreeRoamConfig fr() {
        TrainingProgress prog = TrainingStore.progress();
        if (prog.freeRoam == null) prog.freeRoam = new dev.xsoz.client.training.bot.FreeRoamConfig();
        var f = prog.freeRoam;
        if (f.abilities == null || f.abilities.isEmpty()) f.abilities = new ArrayList<>(dev.xsoz.client.training.bot.FreeRoamConfig.Fight.CRYSTAL.abilities());
        if (f.personalityMode == null) f.personalityMode = dev.xsoz.client.training.bot.FreeRoamConfig.PersonalityMode.MIXED;
        if (f.samePersonality == null) f.samePersonality = dev.xsoz.client.training.bot.Personality.ALL_ROUNDER;
        if (f.terrain == null) f.terrain = dev.xsoz.client.training.bot.FreeRoamConfig.Terrain.STONE;
        if (f.size == null) f.size = dev.xsoz.client.training.bot.FreeRoamConfig.Size.MEDIUM;
        if (f.teams == null) f.teams = dev.xsoz.client.training.bot.FreeRoamConfig.Teams.VS_YOU;
        return f;
    }

    /** A pill you can pick; returns its width. */
    private int chip(DrawContext c, String label, int x, int y, boolean sel, int mx, int my, String tipId, String tipText, Runnable onClick) {
        int w = Gfx.width(label) + 14;
        boolean hov = Gfx.hovered(mx, my, x, y, w, 14);
        Gfx.round(c, x, y, w, 14, 7, sel ? Theme.withAlpha(Theme.accent(), 0x40) : hov ? Theme.HOVER : Theme.RAISED);
        Gfx.text(c, label, x + 7, y + 3, sel ? Theme.TEXT : Theme.TEXT_2);
        if (hov && tipText != null) tip.offer(tipId, tipText);
        clipClick(x, y, w, 14, contentY, contentY + contentH, onClick);
        return w;
    }

    /** Lays out chips over as many lines as needed; returns the y after them. */
    private <E> int chipRow(DrawContext c, String title, E[] values, java.util.function.Function<E, String> name,
                            java.util.function.Function<E, String> detail, E current, java.util.function.Consumer<E> set, int x, int y, int w, int mx, int my) {
        Gfx.text(c, title, x, y + 3, Theme.TEXT_3);
        int cx = x + 74;
        for (E v : values) {
            int cw = Gfx.width(name.apply(v)) + 14;
            if (cx + cw > x + w) {
                cx = x + 74;
                y += 17;
            }
            cx += chip(c, name.apply(v), cx, y, v == current, mx, my, title + v, detail == null ? null : detail.apply(v), () -> {
                set.accept(v);
                TrainingStore.save();
            }) + 4;
        }
        return y + 19;
    }

    /** Chips that switch on and off on their own; returns the y after them. */
    private <E> int toggleRow(DrawContext c, String title, E[] values, java.util.function.Function<E, String> name, java.util.function.Function<E, String> detail,
                              java.util.function.Predicate<E> on, java.util.function.Consumer<E> flip, int x, int y, int w, int mx, int my) {
        Gfx.text(c, title, x, y + 3, Theme.TEXT_3);
        int cx = x + 74;
        for (E v : values) {
            String label = name.apply(v);
            int cw = Gfx.width(label) + 14;
            if (cx + cw > x + w) {
                cx = x + 74;
                y += 17;
            }
            cx += chip(c, label, cx, y, on.test(v), mx, my, title + v, detail.apply(v), () -> {
                flip.accept(v);
                TrainingStore.save();
            }) + 4;
        }
        return y + 19;
    }

    /** Ground depth and bedrock: every arena (drills, sparring, Free Roam) uses these. */
    private int groundRows(DrawContext c, int x, int y, int w, int mx, int my) {
        TrainingProgress prog = TrainingStore.progress();
        y = chipRow(c, "Ground", dev.xsoz.client.training.session.SkyArena.Depth.values(), v -> v.title, v -> v.detail + " Every drill and fight uses this.",
                prog.depth(), v -> prog.arenaDepth = v.name(), x, y, w, mx, my);
        y = chipRow(c, "Bedrock", dev.xsoz.client.training.session.SkyArena.Bedrock.values(), v -> v.title, v -> v.detail,
                prog.bedrock(), v -> prog.arenaBedrock = v.name(), x, y, w, mx, my);
        return y;
    }

    private void renderFreeRoam(DrawContext c, int mx, int my) {
        var f = fr();
        int x = contentX;
        int w = contentW;
        int top = contentY;
        c.enableScissor(x - 2, top, x + w + 2, contentY + contentH);
        int y = top - pageScroll;
        for (String l : Gfx.wrap("Fight bots for as long as you want. Pick what everyone fights with, the map, how good the bots are and how they like to play. Drop any item to stop.", w)) {
            Gfx.text(c, l, x, y, Theme.TEXT_2);
            y += 10;
        }
        y += 6;
        // what everyone fights with: any mix, or a ready-made one
        var abil = f.abilities();
        y = toggleRow(c, "Fight with", dev.xsoz.client.training.bot.FreeRoamConfig.Ability.values(), v -> v.title, v -> v.detail + " Click to add or remove it.",
                abil::contains, f::toggle, x, y, w, mx, my);
        y = chipRow(c, "Quick pick", dev.xsoz.client.training.bot.FreeRoamConfig.Fight.values(), v -> v.title, v -> v.detail,
                dev.xsoz.client.training.bot.FreeRoamConfig.Fight.exactly(abil), v -> f.abilities = new ArrayList<>(v.abilities()), x, y, w, mx, my);
        y = chipRow(c, "Map", dev.xsoz.client.training.bot.FreeRoamConfig.Terrain.values(), v -> v.title, v -> v.detail, f.terrain, v -> f.terrain = v, x, y, w, mx, my);
        y = chipRow(c, "Size", dev.xsoz.client.training.bot.FreeRoamConfig.Size.values(), v -> v.title + " (" + (v.radius * 2 + 1) + ")",
                v -> "A " + (v.radius * 2 + 1) + " x " + (v.radius * 2 + 1) + " arena with glass walls and a glass roof.", f.size, v -> f.size = v, x, y, w, mx, my);
        y = groundRows(c, x, y, w, mx, my);
        y = chipRow(c, "Teams", dev.xsoz.client.training.bot.FreeRoamConfig.Teams.values(), v -> v.title, v -> v.detail, f.teams, v -> f.teams = v, x, y, w, mx, my);
        // bots and allies
        Gfx.text(c, "Bots", x, y + 3, Theme.TEXT_3);
        int bx = x + 74;
        bx += chip(c, "-", bx, y, false, mx, my, "bots-", "One bot fewer.", () -> {
            f.bots = Math.max(1, f.bots - 1);
            f.allies = Math.min(f.allies, Math.max(0, f.bots - 1));
            TrainingStore.save();
        }) + 4;
        Gfx.textBold(c, String.valueOf(f.bots), bx + 4, y + 3, Theme.TEXT);
        bx += 18;
        bx += chip(c, "+", bx, y, false, mx, my, "bots+", "One bot more (up to 8).", () -> {
            f.bots = Math.min(8, f.bots + 1);
            TrainingStore.save();
        }) + 14;
        if (f.teams == dev.xsoz.client.training.bot.FreeRoamConfig.Teams.TEAMS) {
            Gfx.text(c, "On your team:", bx, y + 3, Theme.TEXT_3);
            bx += Gfx.width("On your team:") + 6;
            bx += chip(c, "-", bx, y, false, mx, my, "allies-", "One teammate fewer.", () -> {
                f.allies = Math.max(0, f.allies - 1);
                TrainingStore.save();
            }) + 4;
            Gfx.textBold(c, String.valueOf(f.alliesUsed()), bx + 4, y + 3, Theme.TEXT);
            bx += 18;
            chip(c, "+", bx, y, false, mx, my, "allies+", "One more bot on your side (at least one bot stays an enemy).", () -> {
                f.allies = Math.min(f.bots - 1, f.allies + 1);
                TrainingStore.save();
            });
            y += 19;
            Gfx.text(c, Gfx.fit("You + " + f.alliesUsed() + " teammate(s) vs " + (f.bots - f.alliesUsed()) + " enemy bot(s).", w - 74), x + 74, y, Theme.TEXT_2);
            y += 13;
        } else {
            y += 19;
        }
        // level
        y = chipRow(c, "Bot level", dev.xsoz.client.training.bot.BotLevel.values(), v -> v.n() + " " + v.title, v -> v.detail,
                dev.xsoz.client.training.bot.BotLevel.of(f.level), v -> f.level = v.n(), x, y, w, mx, my);
        for (String l : Gfx.wrap(dev.xsoz.client.training.bot.BotLevel.of(f.level).detail, w - 74)) {
            Gfx.text(c, l, x + 74, y, Theme.TEXT_3);
            y += 10;
        }
        y += 4;
        // the ladder: which levels you've beaten with this mix
        int beaten = TrainingStore.progress().beatenLevels == null ? 0 : TrainingStore.progress().beatenLevels.getOrDefault(f.abilitiesTitle(), 0);
        Gfx.text(c, "Beaten", x, y + 3, Theme.TEXT_3);
        int lx = x + 74;
        for (var lv : dev.xsoz.client.training.bot.BotLevel.values()) {
            boolean done = lv.n() <= beaten;
            int lw = Gfx.width(lv.title) + 12;
            if (lx + lw > x + w) break;
            Gfx.round(c, lx, y, lw, 14, 7, done ? 0xFF2E5E33 : Theme.SURFACE);
            Gfx.text(c, lv.title, lx + 6, y + 3, done ? 0xFFB9F6C0 : Theme.TEXT_3);
            if (Gfx.hovered(mx, my, lx, y, lw, 14)) tip.offer("beat" + lv, done ? "You've killed a " + lv.title + " bot with " + f.abilitiesTitle() + "."
                    : "Kill a " + lv.title + " bot with " + f.abilitiesTitle() + " to tick this off.");
            lx += lw + 4;
        }
        y += 19;
        Integer[] levelModes = {0, 1, 2};
        Integer curMode = f.adaptive ? levelModes[2] : f.mixedLevels ? levelModes[1] : levelModes[0];
        y = chipRow(c, "Levels", levelModes, v -> v == 2 ? "Adaptive" : v == 1 ? "Mixed" : "All the same",
                v -> v == 2 ? "Starts at the level you pick. Every bot you kill makes them a level better, every death makes them a level easier - they find your level."
                        : v == 1 ? "Each bot is one level lower, the same or one higher than the level you pick." : "Every bot plays at the level you pick.",
                curMode, v -> {
                    f.mixedLevels = v == 1;
                    f.adaptive = v == 2;
                }, x, y, w, mx, my);
        y = chipRow(c, "You", new Boolean[] {false, true}, v -> v ? "Watch the bots" : "Fight",
                v -> v ? "You fly around as a spectator and the bots fight each other. Click a bot to see through its eyes - a great way to learn how they play."
                        : "You fight in the arena.",
                f.watch, v -> f.watch = v, x, y, w, mx, my);
        // personalities
        y = chipRow(c, "Personality", dev.xsoz.client.training.bot.FreeRoamConfig.PersonalityMode.values(), v -> v.title, v -> v.detail,
                f.personalityMode, v -> f.personalityMode = v, x, y, w, mx, my);
        if (f.personalityMode == dev.xsoz.client.training.bot.FreeRoamConfig.PersonalityMode.SAME) {
            y = chipRow(c, "", dev.xsoz.client.training.bot.Personality.values(), v -> v.title, v -> v.detail, f.samePersonality, v -> f.samePersonality = v, x, y, w, mx, my);
        } else if (f.personalityMode == dev.xsoz.client.training.bot.FreeRoamConfig.PersonalityMode.PICK) {
            var picks = f.picks();
            int px = x + 74;
            for (int i = 0; i < f.bots; i++) {
                final int idx = i;
                var who = picks.get(i);
                String label = "Bot " + (i + 1) + ": " + who.title;
                int cw = Gfx.width(label) + 14;
                if (px + cw > x + w) {
                    px = x + 74;
                    y += 17;
                }
                px += chip(c, label, px, y, false, mx, my, "pick" + i, who.detail + " Click for the next one.", () -> {
                    var all = dev.xsoz.client.training.bot.Personality.values();
                    picks.set(idx, all[(picks.get(idx).ordinal() + 1) % all.length]);
                    TrainingStore.save();
                }) + 4;
            }
            y += 19;
        }
        Integer[] reactions = {0, 90, 150, 250, 400, 650};
        Integer cur = f.reactionMs;
        for (Integer r : reactions) if (r == f.reactionMs) cur = r;
        y = chipRow(c, "Reaction", reactions, r -> r == 0 ? "Level's own" : r + " ms",
                r -> r == 0 ? "Use the bot level's reaction time." : "Every bot waits " + r + " ms before each action, whatever its level.", cur, r -> f.reactionMs = r, x, y, w, mx, my);
        y += 6;
        // kit + start
        var kitMix = dev.xsoz.client.training.bot.FreeRoamConfig.Fight.covering(abil);
        if (button(c, "Kit: " + kitMix.kit.title, x, y, 150, 18, false, mx, my, () -> client.setScreen(new dev.xsoz.client.gui.KitEditorScreen(this, kitMix.kit)))) {
            tip.offer("frkit", dev.xsoz.client.training.bot.FreeRoamConfig.Fight.exactly(abil) != null
                    ? "You and the bots get this kit. Change it here: any items, any enchantments."
                    : "Your mix starts from this kit, without the items of anything you left out. Change it here: any items, any enchantments.");
        }
        TrainingGate.Result gate = TrainingGate.check(client);
        boolean running = TrainingManager.running();
        int sx = x + 160;
        if (running) {
            if (button(c, "Stop", sx, y, 120, 18, false, mx, my, TrainingManager::stop)) tip.offer("frstop", "Stops and restores everything.");
        } else if (!gate.ok()) {
            disabledButton(c, "Start", sx, y, 120, 18);
            if (Gfx.hovered(mx, my, sx, y, 120, 18)) tip.offer("frgate", gate.message() + " " + gate.detail());
        } else if (button(c, "Start fighting", sx, y, 120, 18, true, mx, my, () -> {
            dev.xsoz.client.training.drill.FreeRoamDrill.configure(f);
            TrainingGate.Result r = TrainingManager.start(DrillDef.FREE_ROAM, Drill.Mode.FIXED, 0, false, true, f.level);
            if (r.ok()) client.setScreen(null);
            else {
                startError = r.message();
                startDetail = r.detail();
            }
        })) tip.offer("frstart", "Builds the arena in the sky above you and starts. Your inventory, position and the world come back when you stop.");
        y += 24;
        if (!gate.ok() && !running) {
            Gfx.textBold(c, gate.message(), x, y, 0xFFFF6D7E);
            y += 11;
        } else if (startError != null) {
            Gfx.textBold(c, startError, x, y, 0xFFFF6D7E);
            y += 11;
        }
        if (running && TrainingManager.active() instanceof dev.xsoz.client.training.drill.BotFightDrill bf) {
            for (String l : bf.board) {
                Gfx.text(c, l, x, y, Theme.TEXT_2);
                y += 10;
            }
        }
        pageHeight = y + pageScroll - top + 10;
        c.disableScissor();
    }

    private static String names(List<DrillDef> ds) {
        List<String> n = new ArrayList<>();
        for (DrillDef d : ds) n.add(d.title);
        return String.join(" and ", n);
    }

    /** Draws the drill panel from y down; returns the height it used. */
    private int renderDrillDetail(DrawContext c, int mx, int my, int x, int y, int dw, int dh) {
        DrillDef d = selectedDrill;
        TrainingProgress prog = TrainingStore.progress();
        TrainingProgress.DrillRecord rec = prog.get(d);
        Experience exp = experience();
        boolean unlocked = dev.xsoz.client.training.TrainingFlags.unlocked(prog, d);
        if (selectedMode == null) selectedMode = exp.naturalByDefault ? Drill.Mode.NATURAL : Drill.Mode.FIXED;
        if (selectedP < 0) selectedP = rec.sessions > 0 ? rec.lastP : exp.startP;
        if (selectedHints == null) selectedHints = exp.hintsByDefault;
        if (selectedLevel < 1) selectedLevel = Math.max(1, Math.min(d.levels().size(), rec.lastLevel));

        int ix = x + 10;
        int iw = dw - 20;
        int cy = y + 8;
        Gfx.boldScaled(c, d.title, ix, cy, 1.2f, Theme.TEXT);
        String st = prog.passed(d) ? "PASSED" : unlocked ? "AVAILABLE" : "LOCKED";
        int stc = prog.passed(d) ? Theme.accent() : unlocked ? Theme.TEXT_2 : Theme.EDGE;
        boolean fits = Math.round(Gfx.widthBold(d.title) * 1.2f) + Gfx.width(st) + 16 <= iw;
        if (fits) Gfx.text(c, st, x + dw - 10 - Gfx.width(st), cy + 2, stc);
        else Gfx.text(c, st, ix, cy + 13, stc);
        cy += fits ? 16 : 26;
        for (String l : Gfx.wrap(d.summary, iw)) {
            Gfx.text(c, l, ix, cy, Theme.TEXT_2);
            cy += 10;
        }
        cy += 3;
        int tipsHeight = 0;
        for (String t : d.tips) tipsHeight += Gfx.wrap(t, iw - 8).size() * 10;
        // controls below need ~150 px plus the stats row; collapse the tips into a hover note if short on space
        if (iw < 200 || cy + tipsHeight + 205 > y + dh) {
            Gfx.text(c, "Tips (hover to read)", ix, cy, Theme.accent());
            if (Gfx.hovered(mx, my, ix, cy, Gfx.width("Tips (hover to read)"), 10)) tip.offer("tips-" + d, String.join(" ", d.tips));
            cy += 10;
        } else {
            for (String t : d.tips) {
                List<String> ls = Gfx.wrap(t, iw - 8);
                Gfx.rect(c, ix + 1, cy + 3, 2, 2, Theme.TEXT_3);
                for (String l : ls) {
                    Gfx.text(c, l, ix + 7, cy, Theme.TEXT_3);
                    cy += 10;
                }
            }
        }
        cy += 3;
        Gfx.text(c, Gfx.fit("Level Up: " + d.passRule, iw), ix, cy, 0xFFFFD166);
        if (Gfx.hovered(mx, my, ix, cy, iw, 10)) tip.offer("rule-" + d, "Level Up is the test: " + d.passRule + ". Pass it once and it stays passed (unless you reset it).");
        cy += 14;

        if (!unlocked) {
            for (String l : Gfx.wrap("Locked. Pass " + names(d.prerequisites()) + " first.", iw)) {
                Gfx.text(c, l, ix, cy, Theme.TEXT_2);
                cy += 10;
            }
            return cy - y + 8;
        }

        // mode selector
        Drill.Mode[] modes = Drill.Mode.values();
        String[] labels = {"Fixed speed", "Natural", "Level Up"};
        String[] tips = {
                "Practise at the speed you set with the slider.",
                "Natural speed-up/slow-down: gets faster after 3 hits in a row, slower after a miss. Finds your edge automatically.",
                "The test: every level in turn, at the fastest humanly-reasonable speed, no hints, no sound cues. Pass all of them to unlock what's next."};
        int segW = (iw - 8) / 3;
        for (int i = 0; i < modes.length; i++) {
            int sx = ix + i * (segW + 4);
            boolean sel = selectedMode == modes[i];
            boolean hov = Gfx.hovered(mx, my, sx, cy, segW, 16);
            Gfx.round(c, sx, cy, segW, 16, 4, sel ? (modes[i] == Drill.Mode.LEVEL_UP ? 0xFF6E4B12 : Theme.withAlpha(Theme.accent(), 0x40)) : hov ? Theme.HOVER : Theme.RAISED);
            Gfx.textCentered(c, Gfx.fit(labels[i], segW - 4), sx + segW / 2, cy + 4, sel ? (modes[i] == Drill.Mode.LEVEL_UP ? 0xFFFFD166 : Theme.TEXT) : Theme.TEXT_2);
            if (hov) tip.offer("mode-" + i, tips[i]);
            final Drill.Mode mm = modes[i];
            click(sx, cy, segW, 16, () -> selectedMode = mm);
        }
        cy += 20;

        // difficulty level
        boolean lvlLocked = selectedMode == Drill.Mode.LEVEL_UP;
        List<DrillDef.Level> lvls = d.levels();
        int lw = (iw - 4 * (lvls.size() - 1)) / lvls.size();
        for (int i = 0; i < lvls.size(); i++) {
            int lx = ix + i * (lw + 4);
            int n = i + 1;
            boolean sel = lvlLocked || selectedLevel == n;
            boolean hov = Gfx.hovered(mx, my, lx, cy, lw, 14);
            Gfx.round(c, lx, cy, lw, 14, 4, sel ? Theme.withAlpha(Theme.accent(), lvlLocked ? 0x18 : 0x36) : hov && !lvlLocked ? Theme.HOVER : Theme.RAISED);
            String t = Gfx.fit("Lv " + n + "  " + lvls.get(i).name(), lw - 6);
            Gfx.textCentered(c, t, lx + lw / 2, cy + 3, sel ? Theme.TEXT : lvlLocked ? Theme.TEXT_3 : Theme.TEXT_2);
            if (hov) tip.offer("lvl-" + d + n, "Level " + n + " - " + lvls.get(i).name() + ": " + lvls.get(i).detail()
                    + (lvlLocked ? " (Level Up tests every level in turn.)" : n > 1 ? " Earns +" + 25 * (n - 1) + "% XP." : ""));
            if (!lvlLocked) click(lx, cy, lw, 14, () -> selectedLevel = n);
        }
        cy += 17;
        DrillDef.Level shown = lvls.get(lvlLocked ? 0 : selectedLevel - 1);
        if (lvlLocked) shown = new DrillDef.Level("All levels", "Level Up runs level 1, then 2, then 3 - each one has to pass.");
        for (String l : Gfx.wrap(shown.detail(), iw).stream().limit(2).toList()) {
            Gfx.text(c, l, ix, cy, Theme.TEXT_3);
            cy += 10;
        }
        cy += 4;

        // speed slider
        boolean levelUp = selectedMode == Drill.Mode.LEVEL_UP;
        double shownP = levelUp ? 1.0 : selectedP;
        String speedLine = d.speedLabel + ": " + d.format(d.value(shownP)) + (selectedMode == Drill.Mode.NATURAL ? "  (starting point)" : "");
        Gfx.text(c, speedLine, ix, cy, levelUp ? 0xFFFFD166 : Theme.TEXT_2);
        cy += 11;
        sliderX = ix;
        sliderW = iw;
        Gfx.round(c, ix, cy + 3, iw, 4, 2, Theme.LINE);
        int fill = (int) Math.round(iw * shownP);
        Gfx.round(c, ix, cy + 3, Math.max(4, fill), 4, 2, levelUp ? 0xFFFFD166 : Theme.accent());
        Gfx.round(c, ix + fill - 4, cy, 8, 10, 3, levelUp ? 0xFF8A6A1E : Theme.TEXT);
        Gfx.text(c, "Easy", ix, cy + 12, Theme.TEXT_3);
        Gfx.text(c, "Human limit", ix + iw - Gfx.width("Human limit"), cy + 12, Theme.TEXT_3);
        if (Gfx.hovered(mx, my, ix, cy - 2, iw, 14)) {
            tip.offer("slider", levelUp ? "Level Up always runs at the human limit." : "Drag to set the speed. Left = forgiving, right = the Level Up speed.");
        }
        if (!levelUp) click(ix, cy - 2, iw, 14, () -> draggingSlider = true);
        cy += 26;

        // hints
        boolean hintsOn = !levelUp && selectedHints;
        boolean hh = Gfx.hovered(mx, my, ix, cy, 110, 12);
        Gfx.round(c, ix, cy + 1, 16, 9, 4, hintsOn ? Theme.accent() : Theme.LINE);
        Gfx.round(c, ix + (hintsOn ? 8 : 1), cy + 2, 7, 7, 3, hintsOn ? Theme.ON_ACCENT : Theme.TEXT_3);
        Gfx.text(c, "Hints" + (levelUp ? " (off in Level Up)" : ""), ix + 22, cy + 1, levelUp ? Theme.TEXT_3 : Theme.TEXT_2);
        if (hh) tip.offer("hints", "Shows extra help while you train: which key to press, which slot to refill, where the best spot was.");
        if (!levelUp) click(ix, cy, 110, 12, () -> selectedHints = !selectedHints);
        // endless
        boolean endlessOn = !levelUp && selectedEndless;
        int ex = ix + 130;
        Gfx.round(c, ex, cy + 1, 16, 9, 4, endlessOn ? Theme.accent() : Theme.LINE);
        Gfx.round(c, ex + (endlessOn ? 8 : 1), cy + 2, 7, 7, 3, endlessOn ? Theme.ON_ACCENT : Theme.TEXT_3);
        Gfx.text(c, "Endless" + (levelUp ? " (not in Level Up)" : ""), ex + 22, cy + 1, levelUp ? Theme.TEXT_3 : Theme.TEXT_2);
        if (Gfx.hovered(mx, my, ex, cy, 110, 12)) {
            tip.offer("endless", "Keeps going until you stop it - a timer at the top shows how long. Drop any item (Q) to end it.");
        }
        if (!levelUp) click(ex, cy, 110, 12, () -> selectedEndless = !selectedEndless);
        cy += 18;

        // start
        TrainingGate.Result gate = TrainingGate.check(client);
        boolean running = TrainingManager.running();
        if (running) {
            disabledButton(c, "A drill is running", ix, cy, 140, 20);
        } else if (!gate.ok()) {
            disabledButton(c, "Start", ix, cy, 140, 20);
            if (Gfx.hovered(mx, my, ix, cy, 140, 20)) tip.offer("gate", gate.message() + " " + gate.detail());
        } else {
            String label = levelUp ? "Start Level Up" : "Start training";
            if (button(c, label, ix, cy, 140, 20, true, mx, my, () -> startDrill(d))) {
                tip.offer("start", "Gives you a training kit and builds the drill around you. Your inventory, position and the world are restored when it ends.");
            }
        }
        int ry = cy + 24;
        if (!gate.ok() && !running) {
            Gfx.textBold(c, gate.message(), ix, ry, 0xFFFF6D7E);
            ry += 11;
            for (String l : Gfx.wrap(gate.detail(), iw)) {
                Gfx.text(c, l, ix, ry, Theme.TEXT_3);
                ry += 10;
            }
        } else if (startError != null) {
            Gfx.textBold(c, startError, ix, ry, 0xFFFF6D7E);
            ry += 11;
            if (startDetail != null) Gfx.text(c, Gfx.fit(startDetail, iw), ix, ry, Theme.TEXT_3);
            ry += 10;
        }

        // stats + reset
        int sy = Math.max(y + dh - 26, ry + 8);
        String stats = rec.sessions == 0 ? "Not practised yet." :
                String.format(Locale.ROOT, "Practised %d time%s  -  %d/%d hits%s%s", rec.sessions, rec.sessions == 1 ? "" : "s", rec.hits, rec.reps,
                        rec.bestP > 0 ? String.format(Locale.ROOT, "  -  best natural speed %d%%", Math.round(rec.bestP * 100)) : "",
                        rec.bestLevelUpHits > 0 ? "  -  best Level Up " + rec.bestLevelUpHits + "/" + d.levelUpReps : "")
                + (rec.bestGoalSeconds > 0 ? String.format(Locale.ROOT, "  -  best %d in %d:%02d", d.goalHits, rec.bestGoalSeconds / 60, rec.bestGoalSeconds % 60) : "");
        Gfx.text(c, Gfx.fit(stats, prog.passed(d) ? iw - 84 : iw), ix, sy + 7, Theme.TEXT_3);
        if (prog.passed(d)) {
            String rl = confirmReset ? "Confirm reset" : "Reset pass";
            if (button(c, rl, x + dw - 86, sy + 3, 76, 16, false, mx, my, () -> {
                if (confirmReset) {
                    prog.resetPass(d);
                    TrainingStore.save();
                    confirmReset = false;
                } else {
                    confirmReset = true;
                }
            })) {
                tip.offer("reset", "Removes this pass. It will NOT come back unless you pass Level Up again, and anything it unlocked locks again. Your practice history is kept.");
            }
            if (confirmReset) Gfx.text(c, "Your pass won't come back unless you pass again.", ix, sy - 4, 0xFFFFB020);
        }
        return sy + 26 - y;
    }

    private void startDrill(DrillDef d) {
        TrainingGate.Result r = TrainingManager.start(d, selectedMode, selectedP, selectedHints, selectedEndless, Math.max(1, selectedLevel));
        if (r.ok()) {
            startError = null;
            client.setScreen(null);
        } else {
            startError = r.message();
            startDetail = r.detail();
        }
    }

    // =========================================================================== ranks

    private void renderRanks(DrawContext c, int mx, int my) {
        Rank.Inputs in = rankInputs();
        Rank current = Rank.current(in);
        int x = contentX;
        int top = contentY;
        c.enableScissor(x, top, x + contentW, top + contentH);
        int y = top - pageScroll;
        for (String l : Gfx.wrap("Ranks are hard on purpose. Tests (Level Ups) prove your hands; from Gold up you also need recent "
                + "fights against real players, judged on your median score - one lucky fight doesn't count, and fight-based ranks "
                + "can drop if your play does. Mobs never count.", contentW - 8)) {
            Gfx.text(c, l, x, y, Theme.TEXT_3);
            y += 10;
        }
        y += 6;
        for (Rank r : Rank.values()) {
            List<Rank.Requirement> reqs = r.requirements(in);
            boolean earned = r.ordinal() <= current.ordinal();
            boolean isNext = r.ordinal() == current.ordinal() + 1;
            int boxH = 34 + (earned && !isNext && r != current ? 0 : reqs.size() * 11 + 6);
            Gfx.round(c, x, y, contentW - 8, boxH, 6, r == current ? Theme.withAlpha(r.color, 0x26) : Theme.SURFACE);
            Gfx.round(c, x, y + 6, 3, boxH - 12, 1, earned ? r.color : Theme.LINE);
            Gfx.round(c, x + 8, y + 5, 24, 24, 4, earned ? Theme.withAlpha(r.color, 0x30) : Theme.RAISED);
            icon(c, rankItem(r), x + 12, y + 9, 1f, !earned);
            Gfx.boldScaled(c, r.title, x + 40, y + 8, 1.25f, earned ? r.color : Theme.TEXT_2);
            Gfx.text(c, Gfx.fit(rankBlurb(r), contentW - 140), x + 40, y + 21, Theme.TEXT_3);
            String state = r == current ? "YOUR RANK" : earned ? "Earned" : isNext ? "Next" : "Locked";
            int stw = Gfx.width(state) + 10;
            Gfx.round(c, x + contentW - 16 - stw, y + 8, stw, 12, 3, r == current ? r.color : Theme.RAISED);
            Gfx.text(c, state, x + contentW - 11 - stw, y + 10, r == current ? 0xFF101114 : Theme.TEXT_2);
            int ry = y + 34;
            if (!(earned && !isNext && r != current)) {
                for (Rank.Requirement q : reqs) {
                    Gfx.text(c, q.met() ? "+" : "-", x + 12, ry, q.met() ? Theme.accent() : 0xFFFF6D7E);
                    Gfx.text(c, Gfx.fit(q.label(), contentW - 150), x + 22, ry, q.met() ? Theme.TEXT_2 : Theme.TEXT);
                    Gfx.text(c, q.progress(), x + contentW - 16 - Gfx.width(q.progress()), ry, Theme.TEXT_3);
                    ry += 11;
                }
            }
            if (Gfx.hovered(mx, my, x, y, contentW - 8, boxH) && my >= top && my < top + contentH) {
                tip.offer("rank-" + r, rankBlurb(r));
            }
            y += boxH + 6;
        }
        pageHeight = y + pageScroll - top;
        c.disableScissor();
    }

    /** The block (or stick) that stands for a rank. */
    static net.minecraft.item.Item rankItem(Rank r) {
        return switch (r) {
            case ROOKIE -> net.minecraft.item.Items.STICK;
            case COPPER -> net.minecraft.item.Items.COPPER_BLOCK;
            case IRON -> net.minecraft.item.Items.IRON_BLOCK;
            case GOLD -> net.minecraft.item.Items.GOLD_BLOCK;
            case LAPIS -> net.minecraft.item.Items.LAPIS_BLOCK;
            case DIAMOND -> net.minecraft.item.Items.DIAMOND_BLOCK;
            case NETHERITE -> net.minecraft.item.Items.NETHERITE_BLOCK;
        };
    }

    /** Draws an item icon at any size (16 px * scale), optionally dimmed for locked ranks. */
    static void icon(DrawContext c, net.minecraft.item.Item item, float x, float y, float scale, boolean dim) {
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);
        c.drawItem(new net.minecraft.item.ItemStack(item), 0, 0);
        m.popMatrix();
        if (dim) Gfx.rect(c, Math.round(x), Math.round(y), Math.round(16 * scale), Math.round(16 * scale), 0xA0111317);
    }

    private static String rankBlurb(Rank r) {
        return switch (r) {
            case ROOKIE -> "Everyone starts here.";
            case COPPER -> "You know your keys and your kit layout without looking.";
            case IRON -> "Fast, reliable crystal cycles, retotems and refills.";
            case GOLD -> "You can chain obsidian, anchors, gapples and pearls - and it shows in real fights.";
            case LAPIS -> "You pick the right spot under pressure, read shields and hold range. Consistently above average in fights.";
            case DIAMOND -> "Strong in real fights: high median score, winning half or more, core skills solid.";
            case NETHERITE -> "Elite. Every skill high, most fights won, recently.";
        };
    }

    // =========================================================================== fights

    private void renderFights(DrawContext c, int mx, int my) {
        TrainerProfile p = TrainerStore.profile();
        int listW = 150;
        int x = contentX;
        int y = contentY;
        Gfx.round(c, x, y, listW, contentH, 4, Theme.SURFACE);
        if (p.history.isEmpty()) {
            Gfx.text(c, "No fights yet.", x + 8, y + 8, Theme.TEXT_2);
            int ty = y + 20;
            for (String line : Gfx.wrap("Fights are detected automatically: two hits, crystals or pops with the same player inside 5 seconds. In singleplayer, mobs count too (but never toward ranks).", listW - 16)) {
                Gfx.text(c, line, x + 8, ty, Theme.TEXT_3);
                ty += 10;
            }
            return;
        }
        int rowH = 24;
        int maxScroll = Math.max(0, p.history.size() * rowH - contentH);
        fightListScroll = Math.max(0, Math.min(fightListScroll, maxScroll));
        c.enableScissor(x, y, x + listW, y + contentH);
        int ry = y - fightListScroll;
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, HH:mm", Locale.ROOT);
        for (TrainerProfile.Summary s : p.history) {
            if (ry + rowH >= y && ry <= y + contentH) {
                boolean sel = s.id().equals(selectedFight);
                boolean hover = Gfx.hovered(mx, my, x, ry, listW, rowH) && my >= y && my < y + contentH;
                if (sel) Gfx.rect(c, x, ry, listW, rowH, Theme.withAlpha(Theme.accent(), 0x22));
                else if (hover) Gfx.rect(c, x, ry, listW, rowH, Theme.HOVER);
                Gfx.textBold(c, s.grade(), x + 8, ry + 8, FightReport.gradeColor(s.grade()));
                Gfx.text(c, Gfx.fit("vs " + s.opponent(), listW - 34), x + 22, ry + 3, Theme.TEXT);
                Gfx.text(c, Gfx.fit(outcomeWord(s.outcome()) + " - " + fmt.format(new Date(s.epochMs())), listW - 34), x + 22, ry + 13, Theme.TEXT_3);
                if (hover) tip.offer("f-" + s.id(), (s.vsPlayer() ? "Against a player - counts toward ranks." : "Against a mob - practice only, never counts toward ranks.") + " Score " + s.overall() + "/100.");
                final String id = s.id();
                clipClick(x, ry, listW, rowH, y, y + contentH, () -> {
                    selectedFight = id;
                    loaded = null;
                    reportScroll = 0;
                });
            }
            ry += rowH;
        }
        c.disableScissor();

        int rx = x + listW + 8;
        int rw = contentW - listW - 8;
        if (selectedFight == null) return;
        if (loaded == null || !loaded.record().id.equals(selectedFight)) loaded = TrainerStore.loadFight(selectedFight);
        if (loaded == null) {
            Gfx.text(c, "That recording could not be read.", rx, y + 6, Theme.TEXT_2);
            return;
        }
        reportScroll = Math.max(0, Math.min(reportScroll, Math.max(0, reportHeight - contentH)));
        c.enableScissor(rx, y, rx + rw, y + contentH);
        reportHeight = renderReport(c, loaded.record(), loaded.report(), rx, y - reportScroll, rw, mx, my, y, y + contentH);
        c.disableScissor();
        if (reportHeight > contentH) {
            int thumb = Math.max(14, contentH * contentH / reportHeight);
            int ty2 = y + (contentH - thumb) * reportScroll / Math.max(1, reportHeight - contentH);
            Gfx.rect(c, rx + rw - 2, ty2, 2, thumb, Theme.EDGE);
        }
    }

    private int renderReport(DrawContext c, FightRecord r, FightReport rep, int x, int y, int w, int mx, int my, int top, int bottom) {
        int start = y;
        int gc = FightReport.gradeColor(rep.grade);
        Gfx.display(c, rep.grade, x, y + 2, 3f, gc);
        Gfx.textBold(c, Gfx.fit("vs " + r.opponent, w - 60), x + 34, y + 2, Theme.TEXT);
        Gfx.text(c, Gfx.fit(r.server + " - " + rep.stats.get("Result") + " - " + rep.overall + "/100", w - 40), x + 34, y + 13, Theme.TEXT_3);
        y += 30;
        for (String line : Gfx.wrap(rep.headline, w - 4)) {
            Gfx.text(c, line, x, y, Theme.TEXT_2);
            y += 10;
        }
        y += 4;

        int gh = 54;
        Gfx.round(c, x, y, w - 4, gh + 14, 4, Theme.SURFACE);
        Gfx.text(c, "Your HP over the fight", x + 6, y + 4, Theme.TEXT_3);
        if (Gfx.hovered(mx, my, x, y, w - 4, gh + 14)) tip.offer("hpgraph", "Your health + absorption over time. Green line = totem in offhand, red = no totem. Vertical marks are pops, self-damage and pearls.");
        int gx = x + 6;
        int gy = y + 14;
        int gw = w - 16;
        long t0 = r.startTick;
        long t1 = Math.max(r.endTick, t0 + 1);
        for (int i = 0; i <= 4; i++) Gfx.rect(c, gx, gy + gh * i / 4 - 1, gw, 1, 0x10FFFFFF);
        int prevX = -1, prevY = -1;
        for (FightSample s : r.samples) {
            int sx = gx + (int) ((s.tick() - t0) * gw / (t1 - t0));
            float hp = Math.min(36f, s.hp() + s.absorption());
            int sy = gy + gh - 2 - Math.round(hp / 36f * (gh - 4));
            if (prevX >= 0) {
                int lo = Math.min(prevY, sy);
                int hi = Math.max(prevY, sy);
                Gfx.rect(c, prevX, lo, Math.max(1, sx - prevX), hi - lo + 1, s.offhandTotem() ? Theme.accent() : 0xFFFF6D7E);
            }
            prevX = sx;
            prevY = sy;
        }
        for (FightEvent e : r.events) {
            int col = switch (e.type()) {
                case SELF_POP -> 0xFFFF4D5E;
                case OPP_POP -> 0xFF4CD765;
                case SELF_DAMAGE -> 0xFFFFB020;
                case OPP_PEARL, SELF_PEARL -> 0xFF4FC3FF;
                case OPP_XP -> 0xFF3DDC84;
                default -> 0;
            };
            if (col == 0) continue;
            int ex = gx + (int) ((e.tick() - t0) * gw / (t1 - t0));
            Gfx.rect(c, ex, gy, 1, gh, Theme.withAlpha(col, 0x90));
        }
        y += gh + 16;
        int lx = x;
        for (Object[] leg : new Object[][] {{"You popped", 0xFFFF4D5E}, {"They popped", 0xFF4CD765}, {"Self-damage", 0xFFFFB020}, {"Pearl", 0xFF4FC3FF}, {"No totem", 0xFFFF6D7E}}) {
            Gfx.rect(c, lx, y + 2, 5, 5, (int) leg[1]);
            Gfx.text(c, (String) leg[0], lx + 7, y, Theme.TEXT_3);
            lx += Gfx.width((String) leg[0]) + 14;
        }
        y += 14;

        for (Skill s : Skill.values()) {
            Integer v = rep.scores.get(s);
            Gfx.text(c, s.title, x, y, Theme.TEXT_2);
            int bx = x + 110;
            int bw = w - 110 - 30;
            Gfx.round(c, bx, y + 2, bw, 5, 2, Theme.LINE_SOFT);
            if (v != null) Gfx.round(c, bx, y + 2, Math.max(5, bw * v / 100), 5, 2, FightReport.gradeColor(FightReport.grade(v)));
            Gfx.text(c, v == null ? "n/a" : String.valueOf(v), bx + bw + 5, y, v == null ? Theme.TEXT_3 : Theme.TEXT);
            if (Gfx.hovered(mx, my, x, y, w, 12) && my >= top && my < bottom) tip.offer("rs-" + s, s.blurb + (v == null ? ". Didn't come up in this fight." : "."));
            y += 12;
        }
        y += 6;

        if (!rep.issues.isEmpty()) {
            Gfx.textBold(c, "What to fix", x, y, Theme.TEXT);
            y += 12;
            for (FightReport.Issue is : rep.issues) {
                List<String> what = Gfx.wrap(is.whatHappened(), w - 20);
                List<String> fix = Gfx.wrap("Fix: " + is.fix(), w - 20);
                List<String> drill = Gfx.wrap("Practise: " + is.drill(), w - 20);
                int ch = 18 + (what.size() + fix.size() + drill.size()) * 10 + 6;
                int sc = switch (is.severity()) {
                    case "High" -> 0xFFFF6D7E;
                    case "Medium" -> 0xFFFFB25A;
                    default -> 0xFFFFD166;
                };
                Gfx.round(c, x, y, w - 4, ch, 4, Theme.SURFACE);
                Gfx.rect(c, x, y + 3, 2, ch - 6, sc);
                Gfx.textBold(c, is.title(), x + 8, y + 5, Theme.TEXT);
                Gfx.text(c, is.severity(), x + w - 12 - Gfx.width(is.severity()), y + 5, sc);
                int ly = y + 17;
                for (String l : what) { Gfx.text(c, l, x + 8, ly, Theme.TEXT_2); ly += 10; }
                for (String l : fix) { Gfx.text(c, l, x + 8, ly, Theme.TEXT); ly += 10; }
                for (String l : drill) { Gfx.text(c, l, x + 8, ly, Theme.accent()); ly += 10; }
                y += ch + 5;
            }
        }
        if (!rep.strengths.isEmpty()) {
            Gfx.textBold(c, "What went well", x, y, Theme.TEXT);
            y += 12;
            for (String s : rep.strengths) {
                Gfx.text(c, "+ " + s, x + 4, y, Theme.accent());
                y += 10;
            }
            y += 4;
        }
        Gfx.textBold(c, "Numbers", x, y, Theme.TEXT);
        y += 12;
        for (Map.Entry<String, String> e : rep.stats.entrySet()) {
            Gfx.text(c, e.getKey(), x, y, Theme.TEXT_3);
            Gfx.text(c, Gfx.fit(e.getValue(), w / 2 - 6), x + w / 2, y, Theme.TEXT);
            y += 10;
        }
        y += 6;
        final String id = r.id;
        int by = y;
        Gfx.round(c, x, by, 96, 14, 4, Gfx.hovered(mx, my, x, by, 96, 14) ? Theme.HOVER : Theme.RAISED);
        Gfx.textCentered(c, "Delete recording", x + 48, by + 3, Theme.TEXT);
        clipClick(x, by, 96, 14, top, bottom, () -> {
            TrainerStore.profile().history.removeIf(s -> s.id().equals(id));
            TrainerStore.saveProfile();
            TrainerStore.deleteFight(id);
            selectedFight = null;
            loaded = null;
        });
        y += 20;
        return y - start;
    }

    // =========================================================================== learn

    private void renderLearn(DrawContext c, int mx, int my) {
        // the kinds of PvP, as tabs
        int tx = contentX;
        for (Lessons.Category cat : Lessons.Category.values()) {
            int tw = Gfx.width(cat.title) + 16;
            boolean sel = cat == learnCategory;
            boolean hov = Gfx.hovered(mx, my, tx, contentY, tw, 16);
            Gfx.round(c, tx, contentY, tw, 16, 8, sel ? Theme.withAlpha(Theme.accent(), 0x40) : hov ? Theme.HOVER : Theme.RAISED);
            Gfx.text(c, cat.title, tx + 8, contentY + 4, sel ? Theme.TEXT : Theme.TEXT_2);
            if (hov) tip.offer("learn-cat-" + cat, cat.detail);
            click(tx, contentY, tw, 16, () -> {
                learnCategory = cat;
                learnIndex = 0;
                learnScroll = 0;
            });
            tx += tw + 4;
        }
        int top = contentY + 22;
        int height = contentH - 22;
        List<Lessons.Lesson> list = Lessons.of(learnCategory);
        int listW = Math.max(110, Math.min(170, (int) (contentW * 0.4)));
        int x = contentX + listW + 10;
        int w2 = contentW - listW - 10;
        if (list.isEmpty()) {
            Gfx.round(c, contentX, top, contentW, height, 5, Theme.SURFACE);
            Gfx.boldScaled(c, learnCategory.title, contentX + 12, top + 12, 1.2f, Theme.TEXT);
            int ly = top + 30;
            for (String s2 : Gfx.wrap(learnCategory.detail + " Lessons and tutorials for this are coming next. Until then, you can fight bots with "
                    + (learnCategory == Lessons.Category.MACE ? "Mace + Elytra" : "swords") + " in Free Roam.", contentW - 24)) {
                Gfx.text(c, s2, contentX + 12, ly, Theme.TEXT_2);
                ly += 10;
            }
            if (button(c, "Open Free Roam", contentX + 12, ly + 8, 110, 18, true, mx, my, () -> {
                var f = fr();
                f.abilities = new ArrayList<>((learnCategory == Lessons.Category.MACE ? dev.xsoz.client.training.bot.FreeRoamConfig.Fight.MACE
                        : dev.xsoz.client.training.bot.FreeRoamConfig.Fight.SWORD).abilities());
                TrainingStore.save();
                tab = Tab.FREE_ROAM;
                pageScroll = 0;
            })) tip.offer("learn-fr", "Fight bots with these weapons.");
            return;
        }
        learnIndex = Math.min(learnIndex, list.size() - 1);
        learnListW = listW;
        learnListHeight = list.size() * 18;
        learnListScroll = Math.max(0, Math.min(learnListScroll, Math.max(0, learnListHeight - height)));
        c.enableScissor(contentX, top, contentX + listW, top + height);
        int y = top - learnListScroll;
        for (int i = 0; i < list.size(); i++) {
            Lessons.Lesson l = list.get(i);
            boolean sel = i == learnIndex;
            boolean hover = my >= top && my < top + height && Gfx.hovered(mx, my, contentX, y, listW, 16);
            if (sel) Gfx.round(c, contentX, y, listW, 16, 4, Theme.withAlpha(Theme.accent(), 0x26));
            else if (hover) Gfx.round(c, contentX, y, listW, 16, 4, Theme.HOVER);
            String mark = l.tutorial() != null ? "\u25B6 " : "";
            Gfx.text(c, Gfx.fit(mark + l.title(), listW - 10), contentX + 6, y + 4, sel ? Theme.TEXT : Theme.TEXT_2);
            if (hover) tip.offer("lesson-" + learnCategory + i, l.title() + ": " + l.summary() + (l.tutorial() != null ? " (Has a tutorial.)" : ""));
            final int idx = i;
            clipClick(contentX, y, listW, 16, top, top + height, () -> {
                learnIndex = idx;
                learnScroll = 0;
            });
            y += 18;
        }
        c.disableScissor();
        Lessons.Lesson l = list.get(learnIndex);
        Gfx.round(c, x, top, w2, height, 5, Theme.SURFACE);

        // the buttons: watch it, drill it, fight with it - laid out in rows that fit
        record Btn(String label, boolean primary, boolean enabled, String tipId, String tipText, Runnable run) {
        }
        List<Btn> btns = new ArrayList<>();
        TrainingGate.Result gate = TrainingGate.check(client);
        if (l.tutorial() != null) {
            Tutorials.Id tid = l.tutorial();
            boolean ok = gate.ok() && !TrainingManager.running();
            btns.add(new Btn("\u25B6 Watch and try", true, ok, ok ? "learn-tut" : "learn-tut-off",
                    ok ? "Shows it in slow motion with your own keys on screen, explains each step, then lets you try it yourself."
                            : TrainingManager.running() ? "Stop the drill that's running first." : gate.message() + " " + gate.detail(),
                    () -> {
                        TrainingGate.Result r = TrainingManager.startTutorial(tid);
                        if (r.ok()) client.setScreen(null);
                        else {
                            startError = r.message();
                            startDetail = r.detail();
                        }
                    }));
        }
        if (l.drill() != null) {
            DrillDef d = l.drill();
            btns.add(new Btn("Train it: " + d.title, l.tutorial() == null, true, "learn-train", "Opens the matching drill in the Training tab.", () -> {
                tab = Tab.TRAINING;
                selectedDrill = d;
                resetDrillChoice();
            }));
        }
        if (l.fight() != null) {
            var fight = l.fight();
            btns.add(new Btn("Fight bots", false, true, "learn-fight", "Opens Free Roam set up for " + fight.title + ".", () -> {
                var f = fr();
                f.abilities = new ArrayList<>(fight.abilities());
                TrainingStore.save();
                tab = Tab.FREE_ROAM;
                pageScroll = 0;
            }));
        }
        int inner = w2 - 24;
        List<int[]> place = new ArrayList<>();
        int rowX = 0;
        int row = 0;
        for (Btn b : btns) {
            int bw = Math.min(inner, Gfx.width(b.label()) + 20);
            if (rowX > 0 && rowX + bw > inner) {
                row++;
                rowX = 0;
            }
            place.add(new int[] {rowX, row, bw});
            rowX += bw + 6;
        }
        int rows = btns.isEmpty() ? 0 : row + 1;
        int btnArea = rows * 22 + (rows > 0 ? 8 : 0);
        int textBottom = top + height - btnArea - (startError != null ? 12 : 0);

        // the lesson text scrolls above the buttons
        c.enableScissor(x, top, x + w2, textBottom);
        int ly = top + 10 - learnScroll;
        int startLy = ly;
        for (String tl : Gfx.wrap(l.title(), (int) (inner / 1.2f))) {
            Gfx.boldScaled(c, tl, x + 12, ly, 1.2f, Theme.TEXT);
            ly += 13;
        }
        ly += 3;
        for (String s2 : Gfx.wrap(l.summary(), inner)) {
            Gfx.text(c, s2, x + 12, ly, Theme.accent());
            ly += 10;
        }
        ly += 6;
        for (String pnt : l.points()) {
            List<String> lines = Gfx.wrap(pnt, w2 - 34);
            Gfx.round(c, x + 14, ly + 3, 3, 3, 1, Theme.TEXT_3);
            for (String s2 : lines) {
                Gfx.text(c, s2, x + 22, ly, Theme.TEXT_2);
                ly += 10;
            }
            ly += 4;
        }
        c.disableScissor();
        learnPanelHeight = ly - startLy + 6;
        int visible = textBottom - top;
        learnScroll = Math.max(0, Math.min(learnScroll, Math.max(0, learnPanelHeight - visible)));
        if (learnPanelHeight > visible) {
            int barH = Math.max(12, visible * visible / learnPanelHeight);
            int barY = top + (visible - barH) * learnScroll / Math.max(1, learnPanelHeight - visible);
            Gfx.round(c, x + w2 - 4, barY, 2, barH, 1, Theme.LINE);
        }
        for (int i = 0; i < btns.size(); i++) {
            Btn b = btns.get(i);
            int bx = x + 12 + place.get(i)[0];
            int by = top + height - btnArea + 4 + place.get(i)[1] * 22;
            int bw = place.get(i)[2];
            String label = Gfx.fit(b.label(), bw - 12);
            if (!b.enabled()) {
                disabledButton(c, label, bx, by, bw, 18);
                if (Gfx.hovered(mx, my, bx, by, bw, 18)) tip.offer(b.tipId(), b.tipText());
            } else if (button(c, label, bx, by, bw, 18, b.primary(), mx, my, b.run())) {
                tip.offer(b.tipId(), b.tipText());
            }
        }
        if (startError != null) Gfx.textBold(c, Gfx.fit(startError, inner), x + 12, top + height - btnArea - 10, 0xFFFF6D7E);
    }

    // =========================================================================== settings

    private void renderSettings(DrawContext c, int mx, int my) {
        CrystalTrainerModule mod = module();
        int x = contentX;
        int top = contentY;
        c.enableScissor(contentX, top, contentX + contentW, top + contentH);
        int y = top - pageScroll;
        boolean inside = my >= top && my < top + contentH;

        Gfx.boldScaled(c, "Profile", x, y, 1.15f, Theme.TEXT);
        y += 16;
        // your name
        Gfx.textBold(c, "Your name", x, y, Theme.TEXT);
        y += 12;
        if (nameDraft == null) nameDraft = TrainingStore.progress().displayName() == null ? "" : TrainingStore.progress().displayName();
        int nfw = 140;
        Gfx.round(c, x, y, nfw, 16, 4, Theme.SURFACE);
        Gfx.outline(c, x, y, nfw, 16, 1, nameFocused ? Theme.accent() : Theme.LINE);
        boolean ncaret = nameFocused && (System.currentTimeMillis() / 500) % 2 == 0;
        String nshown = nameDraft.isEmpty() && !nameFocused ? (client.getSession() == null ? "Your name" : client.getSession().getUsername()) : nameDraft + (ncaret ? "|" : "");
        Gfx.text(c, nshown, x + 6, y + 4, nameDraft.isEmpty() && !nameFocused ? Theme.TEXT_3 : Theme.TEXT);
        if (inside && Gfx.hovered(mx, my, x, y, nfw, 16)) tip.offer("set-name", "What the client calls you (the menu, the intro). Empty: your Minecraft name. Press Enter to save.");
        clipClick(x, y, nfw, 16, top, top + contentH, () -> nameFocused = true);
        if (button(c, "Save", x + nfw + 6, y + 1, 40, 14, nameFocused, mx, my, this::saveName)) tip.offer("set-name-save", "Saves your name.");
        if (button(c, "Replay the intro", x + nfw + 52, y + 1, 100, 14, false, mx, my, () -> client.setScreen(new dev.xsoz.client.gui.IntroScreen())))
            tip.offer("set-intro", "Plays the welcome intro and the tour again.");
        y += 24;
        Gfx.textBold(c, "Your experience", x, y, Theme.TEXT);
        y += 12;
        Experience cur = experience();
        int ew = (contentW - 18) / 4;
        for (int i = 0; i < Experience.values().length; i++) {
            Experience e = Experience.values()[i];
            int ex = x + i * (ew + 6);
            boolean sel = e == cur;
            boolean hov = inside && Gfx.hovered(mx, my, ex, y, ew, 30);
            Gfx.round(c, ex, y, ew, 30, 4, sel ? Theme.withAlpha(Theme.accent(), 0x30) : hov ? Theme.HOVER : Theme.SURFACE);
            List<String> t = Gfx.wrap(e.title, ew - 10);
            for (int k = 0; k < Math.min(2, t.size()); k++) Gfx.text(c, t.get(k), ex + 5, y + 5 + k * 10, sel ? Theme.TEXT : Theme.TEXT_2);
            if (hov) tip.offer("set-exp-" + e, explainExperience(e));
            clipClick(ex, y, ew, 30, top, top + contentH, () -> chooseExperienceFromSettings(e));
        }
        y += 38;
        Gfx.text(c, "Changes how the trainer teaches - never what a pass or rank needs.", x, y, Theme.TEXT_3);
        y += 16;

        Gfx.textBold(c, "Arenas", x, y, Theme.TEXT);
        y += 12;
        y = groundRows(c, x, y, contentW, mx, my);
        y += 6;

        // developer / testing code
        Gfx.textBold(c, "Developer code", x, y, Theme.TEXT);
        y += 12;
        boolean testOn = dev.xsoz.client.training.TrainingFlags.testMode;
        int fw = 120;
        Gfx.round(c, x, y, fw, 16, 4, Theme.SURFACE);
        Gfx.outline(c, x, y, fw, 16, 1, devFocused ? Theme.accent() : Theme.LINE);
        boolean caret = devFocused && (System.currentTimeMillis() / 500) % 2 == 0;
        String shown = devCode.isEmpty() && !devFocused ? "Enter a code" : "*".repeat(devCode.length()) + (caret ? "|" : "");
        Gfx.text(c, shown, x + 6, y + 4, devCode.isEmpty() && !devFocused ? Theme.TEXT_3 : Theme.TEXT);
        if (inside && Gfx.hovered(mx, my, x, y, fw, 16)) {
            tip.offer("devcode", "For testing: a developer code unlocks every drill until you close Minecraft. Nothing done in test mode is saved.");
        }
        clipClick(x, y, fw, 16, top, top + contentH, () -> devFocused = true);
        if (button(c, "Apply", x + fw + 6, y + 1, 46, 14, true, mx, my, this::applyDevCode)) tip.offer("devapply", "Check the code.");
        if (testOn && button(c, "Turn test mode off", x + fw + 58, y + 1, 104, 14, false, mx, my, () -> {
            dev.xsoz.client.training.TrainingFlags.testMode = false;
            devMessage = "Test mode is off.";
        })) {
            tip.offer("devoff", "Back to normal: locks and saving return.");
        }
        y += 20;
        String msg = testOn ? "TEST MODE ON - every drill unlocked, nothing is saved. Ends when Minecraft closes." : devMessage;
        if (msg != null) {
            Gfx.text(c, Gfx.fit(msg, contentW - 10), x, y, testOn ? 0xFFFFB020 : Theme.TEXT_3);
            y += 12;
        }
        y += 8;

        if (mod != null) {
            Gfx.textBold(c, "Live coach", x, y, Theme.TEXT);
            y += 12;
            for (String line : Gfx.wrap(TrainingContext.MODE_COPY, contentW - 10)) {
                Gfx.text(c, line, x, y, Theme.TEXT_3);
                y += 10;
            }
            y += 6;
            String server = TrainingContext.currentServer(client);
            if (server != null) {
                boolean practice = TrainerStore.profile().practiceServers.contains(server);
                Gfx.text(c, Gfx.fit("Server: " + server + (practice ? " (practice)" : ""), contentW - 140), x, y + 4, Theme.TEXT_2);
                if (button(c, practice ? "Unmark practice server" : "Mark as practice server", x + contentW - 130, y, 126, 15, !practice, mx, my, mod::togglePractice)) {
                    tip.offer("practice", "Practice servers get Full coaching (reacting to your opponent). Only mark servers where that is allowed.");
                }
                y += 22;
            }
            int mmx = inside ? mx : -1;
            y += rows.render(c, mod, mod.settings(), x, y, contentW - 4, mmx, my, false);
            String d = inside ? rows.hoveredDescription(mx, my) : null;
            if (d != null) tip.offer("trow-" + d, d);
            y += 8;
            if (!TrainerStore.profile().practiceServers.isEmpty()) {
                Gfx.textBold(c, "Practice servers", x, y, Theme.TEXT);
                y += 12;
                for (String s : new ArrayList<>(TrainerStore.profile().practiceServers)) {
                    Gfx.text(c, s, x + 4, y + 3, Theme.TEXT_2);
                    button(c, "Remove", x + contentW - 60, y, 54, 13, false, mx, my, () -> {
                        TrainerStore.profile().practiceServers.remove(s);
                        TrainerStore.saveProfile();
                    });
                    y += 16;
                }
            }
        }
        c.disableScissor();
        pageHeight = y + pageScroll - top;
    }

    private void chooseExperienceFromSettings(Experience e) {
        TrainingProgress p = TrainingStore.progress();
        p.experience = e.name();
        TrainingStore.save();
        CrystalTrainerModule mod = module();
        if (mod != null) mod.applyExperience(e);
        resetDrillChoice();
    }

    // =========================================================================== input

    private double adjY(double my) { return my - (1 - Anim.easeOutCubic(open.peek())) * 12; }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x();
        double my = adjY(click.y());
        devFocused = false;
        if (nameFocused) saveName();
        if (tab == Tab.SETTINGS && TrainingStore.progress().experience() != null
                && my >= contentY && my < contentY + contentH && rows.mouseClicked(mx, my, click.button())) return true;
        for (int i = clickRects.size() - 1; i >= 0; i--) {
            int[] r = clickRects.get(i);
            if (Gfx.hovered(mx, my, r[0], r[1], r[2], r[3])) {
                client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.3f));
                clickActions.get(i).run();
                if (draggingSlider) updateSlider(mx);
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void updateSlider(double mx) {
        selectedP = Math.max(0, Math.min(1, (mx - sliderX) / Math.max(1, sliderW)));
        selectedP = Math.round(selectedP * 20) / 20.0;
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (draggingSlider) {
            updateSlider(click.x());
            return true;
        }
        if (tab == Tab.SETTINGS && rows.mouseDragged(click.x())) return true;
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        draggingSlider = false;
        rows.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double v) {
        int step = (int) Math.round(v * 18);
        switch (tab) {
            case FIGHTS -> {
                if (mx < contentX + 150) fightListScroll = Math.max(0, fightListScroll - step);
                else reportScroll = Math.max(0, reportScroll - step);
            }
            case TRAINING -> {
                if (mx >= detailX && mx < detailX + detailW) detailScroll = Math.max(0, detailScroll - step);
                else trainingScroll = Math.max(0, Math.min(Math.max(0, trainingListHeight - contentH + 40), trainingScroll - step));
            }
            case LEARN -> {
                if (mx < contentX + learnListW) learnListScroll = Math.max(0, learnListScroll - step);
                else learnScroll = Math.max(0, learnScroll - step);
            }
            case RANKS, SETTINGS, FREE_ROAM -> pageScroll = Math.max(0, Math.min(Math.max(0, pageHeight - contentH), pageScroll - step));
            default -> { }
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (nameFocused) {
            int k = input.key();
            if (k == GLFW.GLFW_KEY_BACKSPACE && !nameDraft.isEmpty()) nameDraft = nameDraft.substring(0, nameDraft.length() - 1);
            else if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER || k == GLFW.GLFW_KEY_ESCAPE) saveName();
            return true;
        }
        if (devFocused) {
            int k = input.key();
            if (k == GLFW.GLFW_KEY_BACKSPACE && !devCode.isEmpty()) devCode = devCode.substring(0, devCode.length() - 1);
            else if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) applyDevCode();
            else if (k == GLFW.GLFW_KEY_ESCAPE) devFocused = false;
            return true;
        }
        if (tab == Tab.SETTINGS && rows.keyPressed(input.key())) return true;
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput input) {
        if (nameFocused) {
            String ch = input.asString();
            if (!ch.isEmpty() && nameDraft.length() < 24) nameDraft += ch;
            return true;
        }
        if (devFocused) {
            String ch = input.asString();
            if (!ch.isEmpty() && devCode.length() < 24) devCode += ch;
            return true;
        }
        return super.charTyped(input);
    }

    private void saveName() {
        nameFocused = false;
        TrainingStore.progress().playerName = nameDraft == null || nameDraft.isBlank() ? null : nameDraft.trim();
        TrainingStore.save();
    }

    private void applyDevCode() {
        boolean ok = devCode.trim().equalsIgnoreCase(dev.xsoz.client.training.TrainingFlags.TEST_CODE);
        if (ok) {
            dev.xsoz.client.training.TrainingFlags.testMode = true;
            devMessage = null;
        } else {
            devMessage = devCode.isEmpty() ? "Type a code first." : "That code is not valid.";
        }
        devCode = "";
        devFocused = false;
    }

    @Override
    public void close() {
        dev.xsoz.client.config.ConfigManager.save();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }

    private static String outcomeWord(String o) {
        return switch (o) {
            case "WIN" -> "Won";
            case "LOSS" -> "Lost";
            case "DISENGAGE" -> "Disengaged";
            case "OPPONENT_LEFT" -> "Opponent left";
            default -> "You left";
        };
    }
}
