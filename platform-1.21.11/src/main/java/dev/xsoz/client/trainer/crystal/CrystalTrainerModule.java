package dev.xsoz.client.trainer.crystal;

import dev.xsoz.client.event.GameEvents;
import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.render.Anim;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.setting.ActionSetting;
import dev.xsoz.client.setting.BoolSetting;
import dev.xsoz.client.setting.ModeSetting;
import dev.xsoz.client.setting.NumberSetting;
import dev.xsoz.client.trainer.CueOverlay;
import dev.xsoz.client.trainer.CueSpec;
import dev.xsoz.client.trainer.LiveCoach;
import dev.xsoz.client.trainer.TrainingContext;
import dev.xsoz.client.trainer.Visibility;
import dev.xsoz.client.trainer.crystal.FightEvent.Type;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;

/**
 * The Crystal Trainer. Detects your fights, records them, grades them on seven skills, keeps a
 * ledger of what you can do consistently, runs drills - and, live, tells you what to do with a
 * coloured border whose colour is the task.
 */
public final class CrystalTrainerModule extends Module {
    public final ModeSetting mode = add(new ModeSetting("Coaching", "Full reacts to what your opponent does (practice contexts only). Own state only works everywhere.",
            "Full", "Full", "Own state only", "Off"));
    public final BoolSetting record = add(new BoolSetting("Record fights", "Detect, record and grade every fight", true));
    public final BoolSetting summary = add(new BoolSetting("Post-fight card", "Show the grade and biggest fix when a fight ends", true));
    public final BoolSetting mobs = add(new BoolSetting("Practice on mobs", "In singleplayer, fights against mobs count (for solo practice)", true));
    public final BoolSetting outsideFights = add(new BoolSetting("Coach outside fights", "Own-state cues even when no fight is detected", false));
    public final NumberSetting thickness = add(new NumberSetting("Border size", "Thickness of the coloured border", 3, 1, 8, 1, "px"));
    public final BoolSetting glow = add(new BoolSetting("Border glow", "Soft glow inside the border", true));
    public final BoolSetting pulse = add(new BoolSetting("Pulse", "Gently pulse the border", true));
    public final BoolSetting label = add(new BoolSetting("Instruction chip", "Show the instruction text at the top", true));
    public final BoolSetting sound = add(new BoolSetting("Cue sounds", "A short tone when a new cue appears", true));
    public final NumberSetting volume = add(new NumberSetting("Cue volume", "Volume of cue sounds", 60, 0, 100, 5, "%"));
    private final Map<CrystalCue.Group, BoolSetting> groups = new EnumMap<>(CrystalCue.Group.class);

    private final FightTracker tracker = new FightTracker();
    private final LiveCoach<CrystalCue> coach = new LiveCoach<>();
    private final CueOverlay overlay = new CueOverlay();
    private final Anim cardAnim = new Anim(0f, 9f);

    private FightReport lastReport;
    private FightRecord lastRecord;
    private List<String> lastChanges = List.of();
    private long cardUntil;
    private TrainingContext.Mode effective = TrainingContext.Mode.OFF;

    public CrystalTrainerModule() {
        super("Crystal Trainer", "Records your crystal fights, grades them, and coaches you live", Category.TRAINER, true);
        for (CrystalCue.Group g : CrystalCue.Group.values()) {
            groups.put(g, add(new BoolSetting("Cues: " + g.title, "Show " + g.title.toLowerCase() + " cues", true)));
        }
        add(new ActionSetting("Open trainer", "Fights, skills, training, ranks and lessons", () -> mc.setScreen(new TrainerScreen(mc.currentScreen))));
        add(new ActionSetting("Mark this server as practice", "Allow Full coaching on the server you are on now", this::togglePractice));

        tracker.setOnFinished(this::fightFinished);
        GameEvents.register(new GameEvents.Listener() {
            @Override public void onAttack(Entity t) { if (live()) tracker.onAttack(t); }

            @Override public void onUseBlock(net.minecraft.util.Hand h, net.minecraft.util.hit.BlockHitResult hit, net.minecraft.item.ItemStack held) {
                if (live()) tracker.onUseBlock(h, hit, held);
            }

            @Override public void onEntityLoad(Entity e) { if (live()) tracker.onEntityLoad(e); }

            @Override public void onEntityUnload(Entity e) { if (live()) tracker.onEntityUnload(e); }

            @Override public void onEntityStatus(Entity e, byte s) { if (live()) tracker.onEntityStatus(e, s); }

            @Override public void onEntityDamage(Entity t, String type, int c, int d) { if (live()) tracker.onEntityDamage(t, type, c, d); }

            @Override public void onWorldLeave() {
                tracker.onWorldLeave();
                coach.clear();
            }
        });
    }

    private boolean live() { return isEnabled() && (record.on() || !mode.is("Off")); }

    public FightTracker tracker() { return tracker; }

    /** Sets which cue groups are on, survival first (beginners get fewer, calmer cues). */
    public void applyExperience(dev.xsoz.client.training.Experience e) {
        int i = 0;
        for (CrystalCue.Group g : CrystalCue.Group.values()) {
            groups.get(g).set(i < e.coachGroups);
            i++;
        }
        dev.xsoz.client.config.ConfigManager.markDirty();
    }

    public TrainingContext.Mode effectiveMode() { return effective; }

    public TrainingContext.Mode requestedMode() {
        return switch (mode.get()) {
            case "Full" -> TrainingContext.Mode.FULL;
            case "Own state only" -> TrainingContext.Mode.SELF;
            default -> TrainingContext.Mode.OFF;
        };
    }

    public void togglePractice() {
        String server = TrainingContext.currentServer(mc);
        if (server == null) return;
        TrainerProfile p = TrainerStore.profile();
        if (!p.practiceServers.remove(server)) p.practiceServers.add(server);
        TrainerStore.saveProfile();
    }

    @Override
    protected void onDisable() {
        coach.clear();
    }

    @Override
    public void onTick() {
        tracker.setAllowMobs(mobs.on());
        TrainingContext.Mode ceiling = TrainingContext.ceiling(mc, TrainerStore.profile().practiceServers);
        effective = TrainingContext.resolve(requestedMode(), ceiling);
        if (mc.currentScreen != null && !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen)) {
            // KILL_SCREEN_OPEN: no live cues while a menu is up.
            coach.clear();
        }
        tracker.setMode(effective);
        tracker.tickClient();
        if (mc.player == null) return;

        if (effective != TrainingContext.Mode.OFF && mc.player.isAlive()
                && (tracker.inFight() || outsideFights.on())
                && (mc.currentScreen == null || mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen)) {
            offerCues();
        }
        CrystalCue active = coach.resolve(tracker.tick());
        CrystalCue started = coach.justStarted();
        if (started != null) {
            if (tracker.current() != null) tracker.current().event(tracker.tick(), Type.CUE_SHOWN, started.ordinal(), 0);
            if (sound.on() && volume.value() > 0) {
                float vol = (float) (volume.value() / 100.0);
                var ev = started.priority() >= 85 ? SoundEvents.BLOCK_NOTE_BLOCK_PLING : SoundEvents.BLOCK_NOTE_BLOCK_HAT;
                mc.getSoundManager().play(PositionedSoundInstance.ui(ev.value(), started.priority() >= 85 ? 1.6f : 1.2f, vol));
            }
        }
        if (active == null) return;
    }

    private void offerCues() {
        FightTracker.Snapshot s = tracker.self();
        long t = tracker.tick();
        boolean inFight = tracker.inFight();
        var me = mc.player;

        // ---- own state (everywhere)
        if (group(CrystalCue.Group.SURVIVAL)) {
            if (!s.offhandTotem && s.totems > 0 && inFight) coach.offer(CrystalCue.RETOTEM_NOW);
            if (!s.offhandTotem && s.totems == 0 && inFight && s.hp + s.absorption < 14) coach.offer(CrystalCue.DISENGAGE);
            if (t - tracker.lastSelfDamage <= 30) coach.offer(CrystalCue.BACK_OFF_SELF);
            if (s.hp + s.absorption < 10 && s.offhandTotem && s.gapples > 0 && t - tracker.lastDamageTaken > 40 && !me.isUsingItem()) {
                coach.offer(CrystalCue.SAFE_EAT);
            }
        }
        if (group(CrystalCue.Group.RESOURCES)) {
            if (s.armor < 0.25f && t - tracker.lastDamageTaken > 40) coach.offer(CrystalCue.REPAIR_ARMOR);
            if (inFight && s.crystals < 8 && tracker.current().count(Type.SELF_CRYSTAL_PLACE) > 0) coach.offer(CrystalCue.LOW_CRYSTALS);
        }
        if (group(CrystalCue.Group.TECHNIQUE)) {
            double avg = tracker.averageRecentCycle();
            if (avg > 8) coach.offer(CrystalCue.SPEED_UP);
        }

        // ---- opponent-aware (full training contexts only, and only what is on your screen)
        if (effective != TrainingContext.Mode.FULL || !inFight) return;
        LivingEntity opp = tracker.opponent();
        boolean oppVisible = opp != null && Visibility.opponentVisible(mc, opp);

        if (group(CrystalCue.Group.READS)) {
            Entity pearl = tracker.opponentPearl();
            if (pearl != null && (Visibility.projectileVisible(mc, pearl) || t - tracker.lastOppPearl <= 6)) {
                coach.offer(CrystalCue.INTERCEPT_PEARL);
            }
            if (oppVisible && opp.isBlocking()) {
                coach.offer(s.anchors > 0 && s.glowstone > 0 ? CrystalCue.ANCHOR_BEHIND : CrystalCue.FLANK_SHIELD);
            }
            if (oppVisible && me.distanceTo(opp) <= 6 && tracker.opponentInHole()) coach.offer(CrystalCue.HOLE_FIGHT);
        }
        if (group(CrystalCue.Group.PUNISH) && oppVisible) {
            if (t - tracker.lastOppPop <= 30) coach.offer(CrystalCue.PUNISH_POP);
            if (t - tracker.lastOppXp <= 40) coach.offer(CrystalCue.PUNISH_REPAIR);
            if (opp.isUsingItem() && opp.getActiveItem().get(DataComponentTypes.FOOD) != null) coach.offer(CrystalCue.PUNISH_EAT);
        }
        if (group(CrystalCue.Group.SPACING) && oppVisible) {
            float d = me.distanceTo(opp);
            boolean crystalInHand = me.getMainHandStack().isOf(Items.END_CRYSTAL) || me.getMainHandStack().isOf(Items.OBSIDIAN);
            if (d < 2.0f && crystalInHand) coach.offer(CrystalCue.MAKE_SPACE);
            if (d > 7.5f) coach.offer(CrystalCue.CLOSE_GAP);
        }
    }

    private boolean group(CrystalCue.Group g) { return groups.get(g).on(); }

    private void fightFinished(FightRecord rec, FightReport rep) {
        if (!record.on()) return;
        if (dev.xsoz.client.training.TrainingFlags.testMode) {
            // Test mode: show the card, keep nothing.
            lastRecord = rec;
            lastReport = rep;
            lastChanges = List.of();
            if (summary.on()) {
                cardUntil = System.currentTimeMillis() + 12_000;
                cardAnim.snap(0f);
            }
            return;
        }
        TrainerProfile p = TrainerStore.profile();
        lastChanges = p.record(rec, rep);
        TrainerStore.saveFight(rec, rep);
        TrainerStore.saveProfile();
        lastRecord = rec;
        lastReport = rep;
        if (summary.on()) {
            cardUntil = System.currentTimeMillis() + 12_000;
            cardAnim.snap(0f);
        }
        coach.clear();
    }

    public FightReport lastReport() { return lastReport; }

    public FightRecord lastRecord() { return lastRecord; }

    /** HUD layer: cue border + chip, drill panel, post-fight card. */
    public void renderOverlay(DrawContext c) {
        if (!isEnabled() || mc.options.hudHidden) return;
        CueSpec active = coach.active();
        String kicker = effective == TrainingContext.Mode.FULL ? "CRYSTAL TRAINER" : "CRYSTAL TRAINER - OWN STATE";
        overlay.render(c, active, kicker, thickness.intValue(), glow.on(), label.on(), pulse.on());
        renderCard(c);
    }

    private void renderCard(DrawContext c) {
        boolean show = lastReport != null && System.currentTimeMillis() < cardUntil;
        cardAnim.target(show ? 1f : 0f);
        float a = cardAnim.get();
        if (a < 0.02f || lastReport == null) return;
        int sw = c.getScaledWindowWidth();
        int w = 196;
        int h = lastChanges.isEmpty() ? 58 : 70;
        int x = sw - w - 8 + Math.round((1f - a) * 30);
        int y = 40;
        Gfx.shadow(c, x, y, w, h, 4, a);
        Gfx.round(c, x, y, w, h, 4, Theme.alpha(0xF0101114, a));
        int gc = FightReport.gradeColor(lastReport.grade);
        Gfx.round(c, x, y, 3, h, 1, Theme.alpha(gc, a));
        Gfx.text(c, "FIGHT ANALYSED", x + 10, y + 6, Theme.alpha(Theme.TEXT_3, a));
        String vs = "vs " + (lastRecord.opponent == null ? "?" : lastRecord.opponent) + " - " + lastReport.stats.get("Result");
        Gfx.text(c, Gfx.fit(vs, w - 60), x + 10, y + 17, Theme.alpha(Theme.TEXT_2, a));
        Gfx.display(c, lastReport.grade, x + w - 34, y + 6, 2.4f, Theme.alpha(gc, a));
        Gfx.text(c, lastReport.overall + "/100", x + w - 38, y + 33, Theme.alpha(Theme.TEXT_3, a));
        Gfx.text(c, Gfx.fit(lastReport.headline, w - 20), x + 10, y + 32, Theme.alpha(Theme.TEXT, a));
        Gfx.text(c, "Right Shift > Crystal Trainer for the full review", x + 10, y + 44, Theme.alpha(Theme.TEXT_3, a));
        if (!lastChanges.isEmpty()) {
            String ch = String.join("  ", lastChanges).replace("+", "Solid: ").replace("-", "Slipping: ");
            Gfx.text(c, Gfx.fit(ch, w - 20), x + 10, y + 56, Theme.alpha(Theme.accent(), a));
        }
    }

    @Override
    public String suffix() {
        if (!isEnabled()) return null;
        return tracker.inFight() ? "Fight" : effective == TrainingContext.Mode.FULL ? "Full" : effective == TrainingContext.Mode.SELF ? "Own" : null;
    }
}
