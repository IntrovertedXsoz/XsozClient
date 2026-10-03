package dev.xsoz.client.training.drill;

import dev.xsoz.client.training.DrillDef;
import dev.xsoz.client.training.Kit;
import dev.xsoz.client.training.Staircase;
import dev.xsoz.client.training.session.Session;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

/**
 * One running training drill. Game logic runs on the SERVER thread (singleplayer: same process);
 * the HUD reads the volatile fields from the client thread.
 *
 * <p>Subclasses implement {@link #setup()} and {@link #tick()} and call {@link #rep} once per
 * attempt. Everything about speed (Fixed / Natural / Level Up), pass rules, XP and feedback is
 * handled here so every drill behaves the same way.</p>
 */
public abstract class Drill {
    public enum Mode {
        FIXED("Fixed speed"), NATURAL("Natural speed-up / slow-down"), LEVEL_UP("Level Up");

        public final String title;

        Mode(String t) { title = t; }
    }

    public record Rep(boolean hit, double value, String note) {
    }

    public final DrillDef def;
    public final Mode mode;
    public final boolean hints;
    private final double fixedP;
    private final Staircase stair;
    protected final Random rng = new Random();
    protected Session s;
    protected Kit kit;
    protected int ticks;

    public final List<Rep> reps = new CopyOnWriteArrayList<>();
    public volatile String status = "";
    public volatile String feedback = "";
    public volatile int feedbackColor = 0xFFECEEF2;
    public volatile long feedbackAt;
    public volatile boolean finished;
    public volatile boolean passed;
    public volatile String endReason;

    /** Ticks of the instruction card, then of the 3-2-1 countdown, before the drill goes live. */
    public static final int INTRO_TICKS = 80;
    public static final int COUNT_TICKS = 60;
    /** Endless: never finishes on its own; dropping an item stops it. Not available in Level Up. */
    public volatile boolean endless;
    private int prestart;
    private volatile boolean live;
    /** Difficulty level 1-3 (DrillDef.levels() says what each one changes). */
    protected int level = 1;
    private int lastRefillMsgAt = -999;
    /** Where the player is held until they may move (intro, countdown, drills that freeze). */
    private net.minecraft.util.math.Vec3d holdAt;
    /** After the last rep: a few seconds to read its verdict before the results card. */
    private int finishingAt = -1;
    private String finishingReason;
    /** The sky platform this drill runs on (null in GameTests). */
    protected dev.xsoz.client.training.session.SkyArena arena;
    /** Level Up runs every level in turn: "Level 2 of 3". */
    public volatile String stageLabel = "";

    protected Drill(DrillDef def, Mode mode, double p, boolean hints) {
        this.def = def;
        this.mode = mode;
        this.hints = hints && mode != Mode.LEVEL_UP;
        this.fixedP = mode == Mode.LEVEL_UP ? 1.0 : p;
        this.stair = new Staircase(p);
    }

    public final void start(Session session, Kit kit) {
        this.s = session;
        this.kit = kit;
        ServerPlayerEntity pl = s.player();
        if (pl != null) {
            pl.setHealth(pl.getMaxHealth());
            pl.getHungerManager().setFoodLevel(20);
            pl.getHungerManager().setSaturationLevel(20f);
            pl.clearStatusEffects();
            pl.extinguish();
            pl.fallDistance = 0;
            if (!s.isTest()) {
                // every drill runs on a big platform high in the sky, away from the world below
                var depth = dev.xsoz.client.training.session.SkyArena.depthChoice;
                net.minecraft.util.math.BlockPos c = dev.xsoz.client.training.session.SkyArena.skyCenter(s.world, pl.getBlockPos(), arenaHeight(), depth);
                arena = dev.xsoz.client.training.session.SkyArena.build(s, c, arenaRadius(), arenaHeight(), deepWalls(), groundTheme(),
                        depth, dev.xsoz.client.training.session.SkyArena.bedrockChoice);
                Scene.teleport(pl, net.minecraft.util.math.Vec3d.ofBottomCenter(c), pl.getYaw(), pl.getPitch());
            }
        }
        setup();
        pl = s.player();
        if (pl != null) holdAt = new net.minecraft.util.math.Vec3d(pl.getX(), pl.getY(), pl.getZ());
        s.checkpoint();
    }

    /** Ticks of the instruction card before the countdown. */
    public int introTicks() { return INTRO_TICKS; }

    /** Ticks of the 3-2-1 countdown (0: none). */
    public int countTicks() { return COUNT_TICKS; }

    /** What the instruction card says. */
    public String cardTitle() { return def.title; }

    public String cardSummary() { return def.summary; }

    /** The card's last line (null: attempts and level). */
    public String cardMode() { return null; }

    /** Drills with real explosions still hear about them (nothing is taken over). */
    public void observeCrystalBreak(EndCrystalEntity crystal, Entity attacker) { }

    public void observeAnchor(BlockPos pos) { }

    /** Half-width of the sky platform. */
    protected int arenaRadius() { return 30; }

    protected int arenaHeight() { return 28; }

    /** What the arena's ground is made of. */
    protected dev.xsoz.client.training.session.SkyArena.Theme groundTheme() { return dev.xsoz.client.training.session.SkyArena.Theme.STONE; }

    /** Fight arenas: glass walls down to bedrock. */
    protected boolean deepWalls() { return false; }

    /** False while the player must stand still (before the drill goes live it is always false). */
    protected boolean playerMayMove() { return true; }

    /** Hold the player here until playerMayMove() (drills that freeze call this when they move them). */
    protected final void holdAt(net.minecraft.util.math.Vec3d at) { holdAt = at; }

    /** Puts the whole kit back (new scene / new attempt), keeping the selected hotbar slot. */
    protected final void refillKit() {
        ServerPlayerEntity pl = player();
        if (pl == null || kit == null) return;
        int sel = pl.getInventory().getSelectedSlot();
        s.clearInventory(pl);
        kit.fill(pl);
        pl.getInventory().setSelectedSlot(sel);
        pl.currentScreenHandler.sendContentUpdates();
    }

    private void enforceHold(ServerPlayerEntity pl) {
        if (pl == null || holdAt == null) return;
        if (pl.squaredDistanceTo(holdAt) > 0.0025) Scene.teleportKeepLook(pl, holdAt);
    }

    /** Server tick. The first ticks are the instruction card and the 3-2-1 countdown. */
    public final void serverTick() {
        if (finished) return;
        if (arena != null) arena.buildStep();
        if (!live) {
            enforceHold(s.player());
            prestart++;
            if (prestart >= introTicks()) {
                int into = prestart - introTicks();
                if (into % 20 == 0 && into < countTicks()) sound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 1.0f);
                if (into >= countTicks()) {
                    live = true;
                    sound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 2.0f);
                }
            }
            return;
        }
        ticks++;
        ServerPlayerEntity pl = s.player();
        if (finishingAt >= 0) {
            if (ticks >= finishingAt) {
                finishingAt = -1;
                finish(finishingReason);
            }
            return;
        }
        if (!playerMayMove()) enforceHold(pl);
        else if (pl != null && holdAt != null) holdAt = new net.minecraft.util.math.Vec3d(pl.getX(), pl.getY(), pl.getZ());
        if (pl != null && pl.getHealth() < 4f && keepAlive()) pl.setHealth(Math.min(pl.getMaxHealth(), 6f));
        if (pl != null && kit != null && autoRefillTotems() && ticks % 5 == 0 && Kit.totems(pl) <= 2 && kit.refillTotems(pl) > 0
                && ticks - lastRefillMsgAt > 100) {
            lastRefillMsgAt = ticks;
            feedback("Totems refilled", 0xFFFFD166);
        }
        tick();
    }

    protected abstract void setup();

    protected abstract void tick();

    /** Kit drills top the totems back up when only 1 or 2 are left. */
    protected boolean autoRefillTotems() { return true; }

    /** Drills that let crystals and anchors really explode (the sparring bot). */
    public boolean realExplosions() { return false; }

    /** Most drills never let the player drop under 2 hearts (no deaths, no item drops). */
    protected boolean keepAlive() { return true; }

    // ------------------------------------------------------------------ hooks (server thread)

    public void onCrystalSpawn(EndCrystalEntity crystal) { }

    /** Return true to take over the crystal (no real explosion). */
    public boolean onCrystalBreak(EndCrystalEntity crystal, Entity attacker) { return false; }

    /** Return true to take over the anchor explosion. */
    public boolean onAnchorExplode(BlockPos pos) { return false; }

    public void onEntityUnload(Entity e) { }

    /**
     * The player took lethal damage with no totem in either hand. Default: they live on at 4
     * hearts (drills never kill). Return true to let them die.
     */
    public boolean onPlayerWouldDie(ServerPlayerEntity p) {
        p.setHealth(Math.min(p.getMaxHealth(), 8f));
        feedback("That would have killed you - keep a totem in your offhand", 0xFFFF6D7E);
        return false;
    }

    /** The player was hurt (all sources) - fight drills track who did it. */
    public void onPlayerDamaged(ServerPlayerEntity p, net.minecraft.entity.damage.DamageSource source) { }

    /** Damage to an entity this drill spawned. Return false to cancel it (the drill handles it). */
    public boolean onTrackedDamage(Entity e, net.minecraft.entity.damage.DamageSource source, float amount) { return true; }

    /** Any entity appearing near the player during a live drill. */
    public void onEntityLoad(Entity e) { }

    // ------------------------------------------------------------------ speed and scoring

    /** Current difficulty 0..1. */
    public final double p() {
        return switch (mode) {
            case LEVEL_UP -> 1.0;
            case NATURAL -> stair.p();
            case FIXED -> fixedP;
        };
    }

    public double peakP() { return mode == Mode.NATURAL ? stair.peak() : p(); }

    /** The drill's speed value right now (window, limit, radius...). */
    public final double speed() { return def.value(p()); }

    /** Sets the difficulty level (1-3). Level Up always tests level 1. Call before start. */
    public final Drill level(int lvl) {
        this.level = Math.max(1, Math.min(def.levels().size(), lvl));
        return this;
    }

    public final int level() { return level; }

    /** Turns endless mode on (ignored for Level Up). Call before start. */
    public final Drill endless(boolean on) {
        this.endless = on && mode != Mode.LEVEL_UP;
        return this;
    }

    /** Server ticks since "Go!" (0 before). */
    public final int age() { return ticks; }

    /** True once the countdown has finished and attempts count. */
    public final boolean live() { return live; }

    /** Ticks spent in the intro + countdown so far. */
    public final int prestartTicks() { return prestart; }

    /** For GameTests: skip the intro and countdown. */
    public final void skipCountdownForTests() { live = true; }

    /** "How fast can you get N hits" drills (Pearl Aim): practice ends on the Nth hit, not after N tries. */
    public final boolean goalMode() { return def.goalHits > 0 && mode != Mode.LEVEL_UP; }

    public final int targetReps() { return mode == Mode.LEVEL_UP ? def.levelUpReps : goalMode() ? def.goalHits : def.reps; }

    public final long hits() { return reps.stream().filter(Rep::hit).count(); }

    /** Record one attempt. Finishes the drill when the target count is reached. */
    protected final void rep(boolean hit, double value, String note) {
        if (finished || finishingAt >= 0) return;
        reps.add(new Rep(hit, value, note));
        // every rep is heard: a bright ding for a hit, a low thud for a miss
        if (hit) sound(net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.3f);
        else sound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.6f);
        if (mode == Mode.NATURAL) stair.record(hit);
        feedback(note, hit ? 0xFF4CD765 : 0xFFFF6D7E);
        if (endless) return;
        if (goalMode()) {
            if (hits() >= def.goalHits) windDown(null);
            return;
        }
        if (reps.size() >= targetReps()) windDown(null);
        else if (mode == Mode.LEVEL_UP && reps.size() - hits() > targetReps() - def.levelUpMinHits) {
            windDown("Too many misses for a pass - try again when you're ready.");
        }
    }

    /** A new target / scene / pad: a short chime so you know to look. */
    protected final void cue() { sound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.6f); }

    protected final void feedback(String text, int color) {
        feedback = text;
        feedbackColor = color;
        feedbackAt = System.currentTimeMillis();
    }

    /** Ends after ~3 s so the last rep's verdict can be read. */
    protected final void windDown(String reason) {
        if (finishingAt >= 0 || finished) return;
        finishingReason = reason;
        finishingAt = ticks + 60;
    }

    protected final void finish(String reason) {
        if (finished) return;
        endReason = reason;
        passed = mode == Mode.LEVEL_UP && reason == null && hits() >= def.levelUpMinHits && extraPassCheck();
        finished = true;
        if (passed) sound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f);
        else sound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 0.8f);
    }

    /** Extra Level Up conditions beyond the hit count (median reaction, average cycle...). */
    protected boolean extraPassCheck() { return true; }

    /** A line under the score, e.g. "median 352 ms". */
    public String summaryLine() { return ""; }

    // ------------------------------------------------------------------ helpers for subclasses

    protected final ServerPlayerEntity player() { return s.player(); }

    protected final void giveKit(ServerPlayerEntity p, GameMode gm) {
        s.clearInventory(p);
        kit.fill(p);
        p.changeGameMode(gm);
        p.currentScreenHandler.sendContentUpdates();
    }

    protected final void sound(SoundEvent ev, float pitch) {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() != net.fabricmc.api.EnvType.CLIENT) return;
        ClientSound.play(ev, pitch);
    }

    protected final void set(BlockPos pos, BlockState st) { s.setBlock(pos, st); }

    /** Fills a horizontal rectangle (inclusive). */
    protected final void floor(BlockPos a, int w, int d, BlockState st) {
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) set(a.add(x, 0, z), st);
    }

    /** Clears a box of air above a floor so nothing from the world interferes. */
    protected final void clearAbove(BlockPos a, int w, int d, int h) {
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) for (int y = 1; y <= h; y++) set(a.add(x, y, z), Blocks.AIR.getDefaultState());
    }

    protected final int randBetween(int lo, int hi) { return lo + rng.nextInt(hi - lo + 1); }

    protected static ItemStack stack(net.minecraft.item.Item item, int n) { return new ItemStack(item, n); }

    /** Drawing anywhere on the screen (kill feed...). Client thread, read-only. */
    public void renderOverlay(DrawContext c, int sw, int sh) { }

    /** Extra HUD drawing for this drill (client thread, read-only). */
    public void renderExtra(DrawContext c, int x, int y) { }
}
