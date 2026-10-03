package dev.xsoz.client.training;

import dev.xsoz.client.XsozClient;
import dev.xsoz.client.modules.combat.ComboBinds;
import dev.xsoz.client.render.Anim;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.drill.AnchorChainDrill;
import dev.xsoz.client.training.drill.CrystalCycleDrill;
import dev.xsoz.client.training.drill.CrystalSpotDrill;
import dev.xsoz.client.training.drill.Drill;
import dev.xsoz.client.training.drill.HoleBreakerDrill;
import dev.xsoz.client.training.drill.KeybindReflexDrill;
import dev.xsoz.client.training.drill.LayoutRecallDrill;
import dev.xsoz.client.training.drill.ObbyCrystalDrill;
import dev.xsoz.client.training.drill.PearlAimDrill;
import dev.xsoz.client.training.drill.RangeControlDrill;
import dev.xsoz.client.training.drill.RefillDrill;
import dev.xsoz.client.training.drill.RetotemDrill;
import dev.xsoz.client.training.drill.SafeGappleDrill;
import dev.xsoz.client.training.drill.ShieldReadDrill;
import dev.xsoz.client.training.drill.SparringDrill;
import dev.xsoz.client.training.session.Session;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Runs training sessions. The client asks for a drill; the drill itself runs on the integrated
 * server's thread (same process, singleplayer only) where it can build arenas and read exact
 * server state. Results come back to the client thread, which records passes and XP.
 */
public final class TrainingManager {
    private static volatile Drill active;
    private static volatile Session session;
    /** The kit of the running session (Level Up stages reuse it). */
    private static volatile Kit activeKit;
    private static volatile Result last;
    private static final java.util.List<net.minecraft.entity.ItemEntity> dropped = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Anim resultAnim = new Anim(0f, 8f);

    /** What the HUD shows after a drill ends. */
    public record Result(DrillDef def, Drill.Mode mode, long hits, int reps, int target, boolean passed,
                         String reason, String summary, long xp, long shownAt, boolean test, boolean endless, int seconds) {
    }

    private TrainingManager() { }

    public static Drill active() { return active; }

    public static boolean running() { return active != null; }



    // ------------------------------------------------------------------ registration

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TrainingManager::serverTick);
        ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            Drill d = active;
            if (d == null) return;
            if (e instanceof EndCrystalEntity c && d.live() && inSession(w, c.getBlockPos())) d.onCrystalSpawn(c);
            if (d.live() && inSession(w, e.getBlockPos())) d.onEntityLoad(e);
            // Dropping an item: the item is taken back (the session restores the inventory anyway)
            // and an Endless drill stops - that is how Endless is ended.
            if (e instanceof net.minecraft.entity.ItemEntity item && session != null && item.getOwner() != null
                    && item.getOwner().getUuid().equals(session.playerId)) {
                dropped.add(item);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((e, w) -> {
            Drill d = active;
            if (d != null && d.live() && session != null && w == session.world) d.onEntityUnload(e);
        });
        TrainingHooks.register();
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (active != null) end("The world closed.", false);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> Session.recoverIfNeeded(server, handler.player)));
    }

    private static boolean inSession(World w, BlockPos pos) {
        Session s = session;
        if (s == null || w != s.world) return false;
        ServerPlayerEntity p = s.player();
        return p != null && p.getBlockPos().isWithinDistance(pos, 48);
    }

    // ------------------------------------------------------------------ client API

    /** Starts a drill. Returns null on success, or the reason it cannot start. */
    public static TrainingGate.Result start(DrillDef def, Drill.Mode mode, double p, boolean hints, boolean endless) {
        return start(def, mode, p, hints, endless, 1);
    }

    public static TrainingGate.Result start(DrillDef def, Drill.Mode mode, double p, boolean hints, boolean endless, int level) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TrainingGate.Result gate = TrainingGate.check(mc);
        if (!gate.ok()) return gate;
        if (active != null) return new TrainingGate.Result(false, "A drill is already running.", "Stop it first.");
        TrainingProgress prog = TrainingStore.progress();
        if (!TrainingFlags.unlocked(prog, def)) {
            return new TrainingGate.Result(false, "Locked.", "Pass " + def.prerequisites().get(0).title + " first.");
        }
        if (!TrainingFlags.testMode) {
            prog.get(def).lastP = p;
            prog.get(def).lastLevel = level;
            TrainingStore.save();
        }
        dev.xsoz.client.training.session.SkyArena.depthChoice = TrainingStore.progress().depth();
        dev.xsoz.client.training.session.SkyArena.bedrockChoice = TrainingStore.progress().bedrock();
        Kit kit = def == DrillDef.FREE_ROAM ? Kit.forAbilities(dev.xsoz.client.training.drill.FreeRoamDrill.pendingConfig().abilities())
                : def == DrillDef.TUTORIAL ? Kit.forMode(dev.xsoz.client.training.drill.TutorialDrill.pendingId().kit) : Kit.current();
        UUID id = mc.player.getUuid();
        MinecraftServer server = mc.getServer();
        server.execute(() -> {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(id);
            if (pl == null || active != null) return;
            try {
                session = new Session(server, pl);
                int first = mode == Drill.Mode.LEVEL_UP ? 1 : level;
                Drill d = create(def, mode, p, hints, first).endless(endless || def == DrillDef.FREE_ROAM).level(first);
                if (mode == Drill.Mode.LEVEL_UP) d.stageLabel = "Level 1 of " + def.levels().size();
                activeKit = kit;
                dropped.clear();
                active = d;
                TrainingHooks.bind(d, session);
                d.start(session, kit);
                XsozClient.LOG.info("Training: {} started ({}, {}).", def.title, mode.title, def.format(d.speed()));
            } catch (RuntimeException ex) {
                XsozClient.LOG.warn("Training could not start: {}", ex.toString());
                end("Could not start: " + ex.getMessage(), false);
            }
        });
        return gate;
    }

    /** Starts a tutorial from the Learn tab. */
    public static TrainingGate.Result startTutorial(dev.xsoz.client.trainer.crystal.Tutorials.Id id) {
        dev.xsoz.client.training.drill.TutorialDrill.configure(id);
        return start(DrillDef.TUTORIAL, Drill.Mode.FIXED, 0, false, false, 1);
    }

    public static void stop() {
        MinecraftClient mc = MinecraftClient.getInstance();
        MinecraftServer server = mc.getServer();
        if (server == null) {
            active = null;
            return;
        }
        server.execute(() -> {
            if (active != null) end("Stopped.", false);
        });
    }

    private static Drill create(DrillDef def, Drill.Mode mode, double p, boolean hints, int level) {
        if (def == DrillDef.LAYOUT_RECALL && level >= 3) return new dev.xsoz.client.training.drill.SituationsDrill(mode, p, hints);
        return switch (def) {
            case KEYBIND_REFLEX -> new KeybindReflexDrill(mode, p, hints);
            case LAYOUT_RECALL -> new LayoutRecallDrill(mode, p, hints);
            case CRYSTAL_CYCLE -> new CrystalCycleDrill(mode, p, hints);
            case RETOTEM -> new RetotemDrill(mode, p, hints);
            case HOTBAR_REFILL -> new RefillDrill(mode, p, hints);
            case OBBY_CRYSTAL -> new ObbyCrystalDrill(mode, p, hints);
            case ANCHOR_CHAIN -> new AnchorChainDrill(mode, p, hints);
            case SAFE_GAPPLE -> new SafeGappleDrill(mode, p, hints);
            case PEARL_AIM -> new PearlAimDrill(mode, p, hints);
            case CRYSTAL_SPOT -> new CrystalSpotDrill(mode, p, hints);
            case FACE_PLACE -> new HoleBreakerDrill(mode, p, hints);
            case SHIELD_READ -> new ShieldReadDrill(mode, p, hints);
            case RANGE_CONTROL -> new RangeControlDrill(mode, p, hints);
            case SPARRING -> new SparringDrill(mode, p, hints);
            case FREE_ROAM -> new dev.xsoz.client.training.drill.FreeRoamDrill(mode, p, hints);
            case TUTORIAL -> new dev.xsoz.client.training.drill.TutorialDrill(mode, p, hints);
            case HIT_CRYSTAL, DOUBLE_TAP -> new dev.xsoz.client.training.drill.HitCrystalDrill(def, mode, p, hints);
        };
    }

    // ------------------------------------------------------------------ server side

    private static void serverTick(MinecraftServer server) {
        Drill d = active;
        Session s = session;
        if (d == null || s == null || server != s.server) return;
        ServerPlayerEntity pl = s.player();
        if (pl == null) {
            end("You left the world.", false);
            return;
        }
        if (!pl.isAlive()) {
            end("You died - the drill was stopped.", false);
            return;
        }
        TrainingGate.Result gate = TrainingGate.checkServer(server, pl.getEntityWorld().getRegistryKey() == World.OVERWORLD);
        if (!gate.ok()) {
            end(gate.detail(), false);
            return;
        }
        if (!dropped.isEmpty()) {
            for (var item : dropped) if (!item.isRemoved()) item.discard();
            dropped.clear();
            if (d.endless) {
                end("Stopped: you dropped an item.", false);
                return;
            }
        }
        try {
            d.serverTick();
        } catch (RuntimeException ex) {
            XsozClient.LOG.warn("Drill {} failed: {}", d.def.title, ex.toString());
            end("Something went wrong in the drill; your world was restored.", false);
            return;
        }
        if (d.finished) {
            // Level Up tests every level in turn: pass one, the next one starts right away
            if (d.mode == Drill.Mode.LEVEL_UP && d.passed && d.level() < d.def.levels().size()) {
                try {
                    s.discardSpawned();
                    Drill next = create(d.def, Drill.Mode.LEVEL_UP, 1.0, false, d.level() + 1).level(d.level() + 1);
                    next.stageLabel = "Level " + next.level() + " of " + d.def.levels().size();
                    active = next;
                    TrainingHooks.bind(next, s);
                    next.start(s, activeKit);
                } catch (RuntimeException ex) {
                    XsozClient.LOG.warn("Level Up stage could not start: {}", ex.toString());
                    end("Something went wrong in the drill; your world was restored.", false);
                }
                return;
            }
            if (d.mode == Drill.Mode.LEVEL_UP && !d.passed && d.endReason == null && d.level() > 1) {
                d.endReason = "Not passed at level " + d.level() + " - every level has to pass.";
            }
            end(null, true);
        }
    }

    /** Server thread. Restores the world and player, then records the result on the client. */
    private static void end(String reason, boolean completed) {
        Drill d = active;
        active = null;
        TrainingHooks.bind(null, null);
        dev.xsoz.client.training.TrainingFlags.selectOnly = false;
        dev.xsoz.client.training.TrainingFlags.combosOff = false;
        Session s = session;
        session = null;
        if (s != null) {
            try {
                s.restore();
            } catch (RuntimeException ex) {
                XsozClient.LOG.warn("Training restore failed (the backup file will retry on next join): {}", ex.toString());
            }
        }
        if (d == null) return;
        MinecraftClient.getInstance().execute(() -> record(d, completed ? d.endReason : reason));
    }

    // ------------------------------------------------------------------ results (client thread)

    private static void record(Drill d, String reason) {
        int seconds = d.age() / 20;
        if (TrainingFlags.testMode) {
            // Test mode: nothing touches passes, XP or stats.
            last = new Result(d.def, d.mode, d.hits(), d.reps.size(), d.targetReps(), d.passed, reason, d.summaryLine(), 0,
                    System.currentTimeMillis(), true, d.endless, seconds);
            resultAnim.snap(0f);
            return;
        }
        TrainingProgress prog = TrainingStore.progress();
        TrainingProgress.DrillRecord r = prog.get(d.def);
        long hits = d.hits();
        r.sessions++;
        r.reps += d.reps.size();
        r.hits += (int) hits;
        if (d.mode == Drill.Mode.NATURAL) r.bestP = Math.max(r.bestP, d.peakP());
        if (d.goalMode() && hits >= d.def.goalHits && !d.endless && (r.bestGoalSeconds <= 0 || seconds < r.bestGoalSeconds)) r.bestGoalSeconds = seconds;
        long xp = hits * 10 + Math.round(d.peakP() * hits * 10);
        xp = Math.round(xp * (1.0 + 0.25 * (d.level() - 1)));
        if (d.mode == Drill.Mode.LEVEL_UP) {
            r.bestLevelUpHits = Math.max(r.bestLevelUpHits, (int) hits);
            if (d.passed && !r.passed) {
                r.passed = true;
                r.passedAt = System.currentTimeMillis();
                xp += 250L * d.def.tier;
            }
        }
        prog.addXp(xp);
        TrainingStore.save();
        last = new Result(d.def, d.mode, hits, d.reps.size(), d.targetReps(), d.passed, reason, d.summaryLine(), xp,
                System.currentTimeMillis(), false, d.endless, seconds);
        resultAnim.snap(0f);
        XsozClient.LOG.info("Training: {} ended - {}/{} hits{}{}", d.def.title, hits, d.reps.size(),
                d.passed ? ", PASSED" : "", reason != null ? " (" + reason + ")" : "");
    }

    // ------------------------------------------------------------------ explosion takeover (server thread, mixins)

    /** Returns true when the crystal belongs to the training session and must not explode. */
    public static boolean claimCrystal(EndCrystalEntity crystal, DamageSource source) {
        Drill d = active;
        if (d == null || !inSession(crystal.getEntityWorld(), crystal.getBlockPos())) return false;
        if (d.realExplosions()) {
            if (d.live()) d.observeCrystalBreak(crystal, source.getAttacker());
            return false;
        }
        try {
            if (d.live()) d.onCrystalBreak(crystal, source.getAttacker());
        } catch (RuntimeException ex) {
            XsozClient.LOG.warn("Drill crystal hook failed: {}", ex.toString());
        }
        if (!crystal.isRemoved()) crystal.discard();
        return true;
    }

    /** Returns true when the anchor belongs to the training session and must not explode. */
    public static boolean claimAnchor(ServerWorld world, BlockPos pos) {
        Drill d = active;
        Session s = session;
        if (d == null || s == null || !inSession(world, pos)) return false;
        if (d.realExplosions()) {
            if (d.live()) d.observeAnchor(pos);
            return false;
        }
        try {
            if (d.live()) d.onAnchorExplode(pos);
        } catch (RuntimeException ex) {
            XsozClient.LOG.warn("Drill anchor hook failed: {}", ex.toString());
        }
        if (!world.getBlockState(pos).isAir()) s.setBlock(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
        return true;
    }

    // ------------------------------------------------------------------ HUD (client thread)

    public static void render(DrawContext c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;
        Drill d = active;
        if (d != null) renderDrill(c, d);
        if (d == null || !d.live()) renderResult(c); // a new drill running: the last result card goes
    }

    private static void renderDrill(DrawContext c, Drill d) {
        if (!d.live()) {
            // Phase 1: the instruction card. Phase 2: 3-2-1. Nothing else competes for the screen.
            int pre = d.prestartTicks();
            if (pre < d.introTicks()) renderIntro(c, d, pre);
            else renderCountdown(c, 3 - (pre - d.introTicks()) / 20, (pre - d.introTicks()) % 20);
            return;
        }
        if (d instanceof dev.xsoz.client.training.drill.TutorialDrill t) {
            dev.xsoz.client.training.drill.TutorialHud.render(c, t);
            return;
        }
        if (d.age() < 20) renderGo(c, d.age());
        renderTimer(c, d);
        int x = 8;
        int w = 200;
        int y = c.getScaledWindowHeight() / 2 - 70;
        int h = 92;
        Gfx.shadow(c, x, y, w, h, 4, 1f);
        Gfx.round(c, x, y, w, h, 5, 0xEE101114);
        Gfx.round(c, x, y, 3, h, 1, Theme.accent());
        Gfx.text(c, TrainingFlags.testMode ? "TRAINING - TEST MODE" : "TRAINING", x + 10, y + 6, TrainingFlags.testMode ? 0xFFFFB020 : Theme.TEXT_3);
        String chip = d.endless ? "Endless" : d.mode.title;
        int cw = Gfx.width(chip) + 10;
        Gfx.round(c, x + w - cw - 6, y + 4, cw, 12, 3, d.mode == Drill.Mode.LEVEL_UP ? 0xFF6E4B12 : Theme.RAISED);
        Gfx.text(c, chip, x + w - cw - 1, y + 6, d.mode == Drill.Mode.LEVEL_UP ? 0xFFFFD166 : Theme.TEXT_2);
        Gfx.textBold(c, d.def.title, x + 10, y + 17, Theme.TEXT);
        if (d.def.levels().size() > 1) {
            String lv = d.mode == Drill.Mode.LEVEL_UP ? d.stageLabel : "Lv " + d.level();
            Gfx.text(c, lv, x + w - 8 - Gfx.width(lv), y + 18, Theme.TEXT_3);
        }

        int done = d.reps.size();
        int target = d.targetReps();
        Gfx.round(c, x + 10, y + 31, w - 20, 4, 2, Theme.LINE_SOFT);
        int progress = d.goalMode() ? (int) d.hits() : done;
        int fill = d.endless ? (w - 20) : (w - 20) * progress / Math.max(1, target);
        Gfx.round(c, x + 10, y + 31, Math.max(4, fill), 4, 2, d.endless ? Theme.withAlpha(Theme.accent(), 0x80) : Theme.accent());
        String counts = d.endless ? done + " reps   hits " + d.hits()
                : d.goalMode() ? d.hits() + " / " + target + " hits   (" + done + " tries)"
                : done + " / " + target + "   hits " + d.hits();
        if (d.mode == Drill.Mode.LEVEL_UP) counts += "  (need " + d.def.levelUpMinHits + ")";
        Gfx.text(c, counts, x + 10, y + 39, Theme.TEXT_2);
        String speed = d instanceof dev.xsoz.client.training.drill.FreeRoamDrill fr
                ? "Bots: " + dev.xsoz.client.training.bot.BotLevel.of(fr.config().level).title + "  -  " + fr.config().abilitiesTitle()
                : d.def.speedLabel + ": " + d.def.format(d.speed());
        if (d.mode == Drill.Mode.NATURAL) speed += String.format(Locale.ROOT, "  (%d%%)", Math.round(d.p() * 100));
        Gfx.text(c, speed, x + 10, y + 50, Theme.TEXT_3);
        String st = d.status;
        if (st != null && !st.isEmpty()) Gfx.text(c, Gfx.fit(st, w - 20), x + 10, y + 62, Theme.TEXT_2);
        long age = System.currentTimeMillis() - d.feedbackAt;
        if (!d.feedback.isEmpty() && age < 2600) {
            float a = age < 2000 ? 1f : 1f - (age - 2000) / 600f;
            Gfx.textBold(c, Gfx.fit(d.feedback, w - 20), x + 10, y + 74, Theme.alpha(d.feedbackColor, a));
        }
        Gfx.text(c, "Right Shift: stop or change drill", x + 2, y + h + 5, Theme.TEXT_3);
        d.renderExtra(c, x + 2, y + h + 18);
        d.renderOverlay(c, c.getScaledWindowWidth(), c.getScaledWindowHeight());
    }

    /** Time on task, at the very top. Endless mode also says how to stop. */
    private static void renderTimer(DrawContext c, Drill d) {
        int sw = c.getScaledWindowWidth();
        int secs = d.age() / 20;
        String t = String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60);
        int tw = Math.round(Gfx.widthBold(t) * 1.4f);
        String sub = d.endless ? "ENDLESS  -  drop any item to stop" : null;
        int w = Math.max(tw, sub == null ? 0 : Gfx.width(sub)) + 20;
        int h = sub == null ? 18 : 28;
        Gfx.round(c, sw / 2 - w / 2, 3, w, h, 4, 0xD0101114);
        Gfx.boldScaled(c, t, sw / 2f - tw / 2f, 6, 1.4f, Theme.TEXT);
        if (sub != null) Gfx.textCentered(c, sub, sw / 2, 19, Theme.accent());
    }

    private static void renderCountdown(DrawContext c, int n, int sub) {
        int sw = c.getScaledWindowWidth();
        int sh = c.getScaledWindowHeight();
        String s = String.valueOf(Math.max(1, n));
        float scale = 6f - sub * 0.08f;
        float a = 1f - Math.max(0, sub - 12) / 8f;
        int w = Math.round(Gfx.displayWidth(s) * scale);
        Gfx.display(c, s, sw / 2f - w / 2f, sh / 2f - 60, scale, Theme.alpha(0xFFECEEF2, a));
        Gfx.textCentered(c, "Get ready", sw / 2, sh / 2 - 74, Theme.alpha(Theme.TEXT_2, a));
    }

    private static void renderGo(DrawContext c, int age) {
        int sw = c.getScaledWindowWidth();
        int sh = c.getScaledWindowHeight();
        float a = 1f - age / 20f;
        float scale = 6f + age * 0.1f;
        int w = Math.round(Gfx.displayWidth("GO!") * scale);
        Gfx.display(c, "GO!", sw / 2f - w / 2f, sh / 2f - 60, scale, Theme.alpha(Theme.accent(), a));
    }

    /** Before the countdown: what to do, in plain words, big and centred. */
    private static void renderIntro(DrawContext c, Drill d, int pre) {
        float a = Math.min(1f, Math.min(pre / 8f, (d.introTicks() - pre) / 12f));
        if (a <= 0.02f) return;
        int sw = c.getScaledWindowWidth();
        int w = Math.min(sw - 40, 320);
        java.util.List<String> lines = Gfx.wrap(d.cardSummary(), w - 24);
        java.util.List<String> tipLines = new java.util.ArrayList<>();
        if (d.hints && !d.def.tips.isEmpty()) tipLines.addAll(Gfx.wrap("Tip: " + d.def.tips.get(0), w - 24));
        DrillDef.Level lv = d.def.levels().get(Math.max(0, Math.min(d.def.levels().size() - 1, d.level() - 1)));
        String mode = d.cardMode() != null ? d.cardMode() : d.mode == Drill.Mode.LEVEL_UP ? "Level Up test, " + d.stageLabel + " - " + d.def.passRule
                : (d.endless ? "Endless - drop any item to stop" : d.goalMode() ? "Land " + d.def.goalHits + " as fast as you can" : d.targetReps() + " attempts")
                + "  -  Level " + d.level() + ": " + lv.name();
        java.util.List<String> modeLines = Gfx.wrap(mode, w - 24);
        int h = 36 + (lines.size() + tipLines.size() + modeLines.size()) * 10 + 8;
        int x = sw / 2 - w / 2;
        int y = c.getScaledWindowHeight() / 2 - h / 2 - 20;
        Gfx.shadow(c, x, y, w, h, 5, a);
        Gfx.round(c, x, y, w, h, 6, Theme.alpha(0xF2101114, a));
        Gfx.round(c, x, y, w, 3, 1, Theme.alpha(d.mode == Drill.Mode.LEVEL_UP ? 0xFFFFD166 : Theme.accent(), a));
        Gfx.boldScaled(c, d.cardTitle(), sw / 2f - Gfx.widthBold(d.cardTitle()) * 0.65f, y + 10, 1.3f, Theme.alpha(Theme.TEXT, a));
        int ly = y + 28;
        for (String l : lines) {
            Gfx.textCentered(c, l, sw / 2, ly, Theme.alpha(Theme.TEXT_2, a));
            ly += 10;
        }
        ly += 4;
        for (String l : tipLines) {
            Gfx.textCentered(c, l, sw / 2, ly, Theme.alpha(Theme.accent(), a));
            ly += 10;
        }
        for (String l : modeLines) {
            Gfx.textCentered(c, l, sw / 2, ly, Theme.alpha(d.mode == Drill.Mode.LEVEL_UP ? 0xFFFFD166 : Theme.TEXT_3, a));
            ly += 10;
        }
    }

    private static void renderResult(DrawContext c) {
        Result r = last;
        boolean show = r != null && System.currentTimeMillis() - r.shownAt() < 9000;
        float a = resultAnim.target(show ? 1f : 0f).get();
        if (r == null || a < 0.02f) return;
        int sw = c.getScaledWindowWidth();
        int w = 240;
        int h = 62;
        int x = sw / 2 - w / 2;
        int y = 28 + Math.round((1 - a) * -10);
        int col = r.passed() ? 0xFF4CD765 : r.mode() == Drill.Mode.LEVEL_UP ? 0xFFFF6D7E : Theme.accent();
        Gfx.shadow(c, x, y, w, h, 4, a);
        Gfx.round(c, x, y, w, h, 5, Theme.alpha(0xF2101114, a));
        Gfx.round(c, x, y, w, 3, 1, Theme.alpha(col, a));
        String head = (r.test() ? "[TEST] " : "") + (r.passed() ? "PASSED - " + r.def().title
                : r.mode() == Drill.Mode.LEVEL_UP ? "Not passed yet - " + r.def().title
                : (r.endless() ? "Endless done - " : "Practice done - ") + r.def().title);
        Gfx.textBoldCentered(c, head, sw / 2, y + 9, Theme.alpha(col, a));
        String line = r.hits() + " / " + r.reps() + " hits"
                + (r.mode() == Drill.Mode.LEVEL_UP && !r.passed() ? "  (need " + r.def().levelUpMinHits + " of " + r.target() + ")" : "")
                + (r.test() ? "   test mode - nothing saved" : "   +" + r.xp() + " XP")
                + String.format(Locale.ROOT, "   %d:%02d", r.seconds() / 60, r.seconds() % 60);
        Gfx.textCentered(c, line, sw / 2, y + 23, Theme.alpha(Theme.TEXT, a));
        if (!r.summary().isEmpty()) Gfx.textCentered(c, r.summary(), sw / 2, y + 35, Theme.alpha(Theme.TEXT_2, a));
        if (r.reason() != null) Gfx.textCentered(c, Gfx.fit(r.reason(), w - 16), sw / 2, y + 47, Theme.alpha(Theme.TEXT_3, a));
    }
}
