package dev.xsoz.client.training.drill;

import dev.xsoz.client.trainer.crystal.Tutorials;
import dev.xsoz.client.training.CombatMath;
import dev.xsoz.client.training.DrillDef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

/**
 * A tutorial: first a demo - the move done for you, in slow motion, with the keys you'd press lit
 * up on screen and pauses that explain what just happened - then the screen fades, the scene is
 * put back, and it's your turn: do it three times.
 *
 * <p>The demo is a script of {@link Step}s. Each step can change the game speed (the server's
 * tick rate, so everything slows down, not just the picture), point your camera, light up keys,
 * do something in the world as if you did it, and stop the world to explain (Space carries on).
 */
public final class TutorialDrill extends Drill {
    /** A key the demo lights up. HOTBAR carries the slot. */
    public enum Key { ATTACK, USE, JUMP, FORWARD, SPRINT, SWAP, HOTBAR }

    public record KeyCue(Key key, int slot, String label) {
    }

    public enum Phase { DEMO, FADE, TRY }

    /** Set on the client right before the drill starts. */
    private static volatile Tutorials.Id pending = Tutorials.Id.CRYSTAL_BASICS;

    public static void configure(Tutorials.Id id) { pending = id; }

    public static Tutorials.Id pendingId() { return pending; }

    public final Tutorials.Id id;

    // ---- read by the HUD (client thread)
    public volatile Phase phase = Phase.DEMO;
    public volatile String caption = "";
    public volatile boolean waiting;
    public volatile boolean continuePressed;
    public volatile float fade;
    public volatile float rate = 20f;
    public volatile boolean camOn;
    public volatile float camYaw;
    public volatile float camPitch;
    public volatile List<KeyCue> keyRow = List.of();
    public final long[] pressedUntil = new long[16];

    // ---- script
    private final List<Step> script = new ArrayList<>();
    private int stepIndex = -1;
    private int stepEndsAt;
    private long waitUntilMs;
    private int fadeAt;
    private boolean demoMove;

    // ---- scene
    private BlockPos p0;
    private MannequinEntity dummy;
    private Vec3d dummyHome;
    private Vec3d dummyAt;
    private Vec3d dummyVel = Vec3d.ZERO;
    private boolean dummyAir;
    private boolean dummyShield;
    private int shieldDownUntil = -1;
    private EndCrystalEntity demoCrystal;

    // ---- your turn
    private final Map<Integer, Integer> crystalSpawnedAt = new HashMap<>();
    private BlockPos lastBlast;
    private int lastBlastAt = -999;
    private int launchedAt = -1;
    private int popAt = -1;
    private int nextPopAt;
    private int surroundCueAt;
    private int lastSprintHit = -999;
    private int clearSurroundAt = -1;

    public TutorialDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.TUTORIAL, Mode.FIXED, 0, false);
        this.id = pending;
    }

    @Override
    public int introTicks() { return 60; }

    @Override
    public int countTicks() { return 0; }

    @Override
    public String cardTitle() { return "Tutorial: " + id.title; }

    @Override
    public String cardSummary() { return id.summary; }

    @Override
    public String cardMode() { return "Watch first, then do it yourself 3 times. Space carries on when it stops to explain."; }

    @Override
    public boolean realExplosions() { return true; }

    @Override
    protected boolean autoRefillTotems() { return false; }

    @Override
    protected int arenaRadius() { return 12; }

    @Override
    protected int arenaHeight() { return 14; }

    @Override
    protected boolean playerMayMove() { return phase == Phase.TRY || demoMove; }

    // =========================================================================== geometry

    /** right / up / forward from where you stand (you face south). */
    private BlockPos rel(int right, int up, int fwd) { return p0.add(-right, up, fwd); }

    private Vec3d top(BlockPos b) { return new Vec3d(b.getX() + 0.5, b.getY() + 1.0, b.getZ() + 0.5); }

    private void look(Vec3d at) {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        Vec3d d = at.subtract(pl.getEyePos());
        camYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        camPitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        camOn = true;
    }

    // =========================================================================== setup

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        p0 = arena != null ? arena.center : pl.getBlockPos();
        if (arena == null) {
            // GameTests: a small floor where the player stands
            s.snapshotBox(p0.add(-9, -2, -9), p0.add(9, 8, 9));
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    set(p0.add(x, -1, z), Blocks.STONE.getDefaultState());
                    for (int y = 0; y <= 6; y++) set(p0.add(x, y, z), Blocks.AIR.getDefaultState());
                }
            }
        }
        center0 = p0;
        room = arena != null ? arena.radius : 8;
        // W-tapping needs a long run: start at one glass wall, facing the length of the arena
        if (id == Tutorials.Id.WTAP) p0 = p0.add(0, 0, -(room - 3));
        giveKit(pl, GameMode.SURVIVAL);
        Scene.teleport(pl, Vec3d.ofBottomCenter(p0), 0f, 20f);
        holdAt(Vec3d.ofBottomCenter(p0));
        pl.setInvulnerable(true);
        s.onRestore(() -> {
            ServerPlayerEntity p = s.player();
            if (p != null) p.setInvulnerable(false);
            if (!s.isTest()) {
                s.server.getTickManager().setFrozen(false);
                s.server.getTickManager().setTickRate(20f);
            }
        });
        buildScene();
        switch (id) {
            case CRYSTAL_BASICS -> scriptCrystal();
            case ANCHOR -> scriptAnchor();
            case ANCHOR_DTAP -> scriptAnchorDtap();
            case HIT_CRYSTAL -> scriptHitCrystal();
            case CRYSTAL_DTAP -> scriptDoubleTap();
            case RETOTEM -> scriptRetotem();
            case SURROUND -> scriptSurround();
            case CRIT -> scriptCrit();
            case WTAP -> scriptWtap();
            case SHIELD_BREAK -> scriptShield();
        }
        status = id.title;
    }

    /** The blocks and the dummy each tutorial needs (also put back before your turn). */
    private void buildScene() {
        if (arena != null) arena.refresh(s.world, dev.xsoz.client.training.session.SkyArena.Floor.STONE);
        for (EndCrystalEntity c : s.world.getEntitiesByClass(EndCrystalEntity.class, new net.minecraft.util.math.Box(p0).expand(12), x -> true)) c.discard();
        switch (id) {
            case CRYSTAL_BASICS -> set(rel(0, 0, 3), Blocks.OBSIDIAN.getDefaultState());
            case HIT_CRYSTAL, CRYSTAL_DTAP -> {
                set(rel(1, 0, 3), Blocks.OBSIDIAN.getDefaultState());
                set(rel(-1, 0, 3), Blocks.OBSIDIAN.getDefaultState());
            }
            default -> { }
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) if (x != 0 || z != 0) set(p0.add(x, 0, z), Blocks.AIR.getDefaultState());
        Vec3d home = switch (id) {
            case CRYSTAL_BASICS -> Vec3d.ofBottomCenter(rel(0, 0, 5));
            case ANCHOR, ANCHOR_DTAP -> Vec3d.ofBottomCenter(rel(0, 0, 4));
            case HIT_CRYSTAL, CRYSTAL_DTAP -> Vec3d.ofBottomCenter(rel(0, 0, 3));
            case CRIT, SHIELD_BREAK -> Vec3d.ofBottomCenter(rel(0, 0, 2));
            case WTAP -> Vec3d.ofBottomCenter(rel(0, 0, 4));
            default -> null;
        };
        if (home != null) {
            dummyHome = home;
            dummyAt = home;
            dummyVel = Vec3d.ZERO;
            dummyAir = false;
            if (dummy == null || dummy.isRemoved()) {
                dummy = Scene.spawnDummy(s, home, 180f);
                if (dummy != null) {
                    dummy.setInvulnerable(false); // so your hits reach the drill
                    dummy.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
                }
            }
            if (id == Tutorials.Id.SHIELD_BREAK && dummy != null) {
                dummy.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                raiseShield(true);
            }
        }
        ServerPlayerEntity pl = player();
        if (pl != null) {
            refillKit();
            Scene.teleport(pl, Vec3d.ofBottomCenter(p0), 0f, 20f);
            holdAt(Vec3d.ofBottomCenter(p0));
        }
    }

    private void raiseShield(boolean up) {
        dummyShield = up;
        if (dummy == null) return;
        if (up) dummy.setCurrentHand(Hand.OFF_HAND);
        else if (dummy.isUsingItem()) dummy.clearActiveItem();
    }

    // =========================================================================== the script engine

    private final class Step {
        final int ticks;
        float speed = -1;
        String say;
        boolean pause;
        Vec3d look;
        KeyCue[] keys = {};
        Runnable act;
        java.util.function.BooleanSupplier until;

        Step(int ticks) { this.ticks = ticks; }

        Step say(String t) { say = t; return this; }

        Step pause() { pause = true; return this; }

        Step look(Vec3d v) { look = v; return this; }

        Step keys(KeyCue... k) { keys = k; return this; }

        Step speed(float r) { speed = r; return this; }

        Step then(Runnable r) { act = r; return this; }

        /** The step also lasts until this is true (at most 4 s). */
        Step until(java.util.function.BooleanSupplier u) { until = u; return this; }
    }

    private Step step(int ticks) {
        Step st = new Step(ticks);
        script.add(st);
        return st;
    }

    private KeyCue k(Key key, String label) { return new KeyCue(key, -1, label); }

    private KeyCue slotKey(Predicate<ItemStack> item, String label) { return new KeyCue(Key.HOTBAR, hotbarSlot(item), label); }

    private void setRate(float r) {
        rate = r;
        if (!s.isTest()) s.server.getTickManager().setTickRate(r);
    }

    private void freeze(boolean f) {
        if (!s.isTest()) s.server.getTickManager().setFrozen(f);
    }

    private void press(KeyCue cue) {
        int i = keyRow.indexOf(cue);
        if (i < 0) {
            for (int j = 0; j < keyRow.size(); j++) if (keyRow.get(j).key() == cue.key() && keyRow.get(j).slot() == cue.slot()) i = j;
        }
        if (i < 0 || i >= pressedUntil.length) return;
        sound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 1.8f); // a soft key click
        long ms = (long) Math.min(1400, 260 * 20f / Math.max(1f, rate));
        pressedUntil[i] = System.currentTimeMillis() + ms;
    }

    /** Real time a caption needs on screen to be read comfortably (about 200 words a minute, plus a beat). */
    private static long readMs(String text) {
        if (text == null || text.isBlank()) return 0;
        return 1400L + 300L * text.trim().split(" +").length;
    }

    /** The current caption may be replaced from this time on (ms). */
    private long captionReadUntil;
    /** Holding the world still so the caption can be read. */
    public volatile boolean reading;

    /** True while the caption still needs reading time: the world holds still (frozen) meanwhile. */
    private boolean holdForReading(boolean nextHasCaption) {
        if (s.isTest() || !nextHasCaption) return false;
        if (System.currentTimeMillis() < captionReadUntil) {
            if (!reading) {
                reading = true;
                freeze(true);
                ServerPlayerEntity pl = player();
                if (pl != null && walking) {
                    // stand still while reading
                    pl.setVelocity(0, 0, 0);
                    pl.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket(pl));
                }
            }
            return true;
        }
        if (reading) {
            reading = false;
            freeze(false);
        }
        return false;
    }

    private void runDemo() {
        if (waiting) {
            if (continuePressed || System.currentTimeMillis() > waitUntilMs || s.isTest()) {
                continuePressed = false;
                waiting = false;
                freeze(false);
                setRate(pausedRate);
                stepEndsAt = ticks + 2;
                captionReadUntil = 0; // they read it before pressing Space
            }
            return;
        }
        if (ticks < stepEndsAt) return;
        Step cur = stepIndex >= 0 && stepIndex < script.size() ? script.get(stepIndex) : null;
        if (cur != null && cur.until != null && !cur.until.getAsBoolean() && ticks < stepStartedAt + 80) return;
        // never wipe a caption before it could be read: the next caption (or the fade) waits
        Step next = stepIndex + 1 < script.size() ? script.get(stepIndex + 1) : null;
        if (holdForReading(next == null || next.say != null || next.pause)) return;
        stepIndex++;
        if (stepIndex >= script.size()) {
            phase = Phase.FADE;
            fadeAt = ticks;
            sound(net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f);
            setRate(20f);
            return;
        }
        Step st = script.get(stepIndex);
        if (st.speed > 0) setRate(st.speed);
        if (st.say != null) {
            caption = st.say;
            captionReadUntil = st.pause ? 0 : System.currentTimeMillis() + readMs(st.say);
        }
        if (st.look != null) look(st.look);
        for (KeyCue kc : st.keys) press(kc);
        if (st.act != null) st.act.run();
        stepEndsAt = ticks + st.ticks;
        stepStartedAt = ticks;
        if (st.pause) {
            pausedRate = rate;
            setRate(20f);
            freeze(true);
            waiting = true;
            continuePressed = false;
            sound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.2f);
            waitUntilMs = System.currentTimeMillis() + 25_000;
        }
    }

    private float pausedRate = 20f;
    private int stepStartedAt;

    // ---- the demo walking you: forward (and sprint) toward the dummy, really moving
    private boolean walking;
    private double walkStop;
    private double walkSpeed;
    private KeyCue[] walkKeys = {};

    /** Walk (sprint) toward the dummy until you're stopAt blocks from it, holding the given keys. */
    private void walkTo(double stopAt, double speed, KeyCue... keys) {
        demoMove = true;
        walking = true;
        walkStop = stopAt;
        walkSpeed = speed;
        walkKeys = keys;
    }

    private double dummyDistance() {
        ServerPlayerEntity pl = player();
        if (pl == null || dummyAt == null) return 0;
        return Math.sqrt((dummyAt.x - pl.getX()) * (dummyAt.x - pl.getX()) + (dummyAt.z - pl.getZ()) * (dummyAt.z - pl.getZ()));
    }

    /** One tick of walking: your client moves you, so it looks and feels like you pressed W. */
    private void tickWalk() {
        ServerPlayerEntity pl = player();
        if (!walking || pl == null || dummyAt == null) return;
        Vec3d d = new Vec3d(dummyAt.x - pl.getX(), 0, dummyAt.z - pl.getZ());
        if (d.length() <= walkStop) {
            stopWalking();
            return;
        }
        Vec3d v = d.normalize().multiply(walkSpeed);
        pl.setVelocity(v.x, 0, v.z);
        pl.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket(pl));
        look(dummyAt.add(0, 1.2, 0));
        for (KeyCue k : walkKeys) press(k);
    }

    private void stopWalking() {
        walking = false;
        walkKeys = new KeyCue[0];
    }

    // =========================================================================== doing things "as you"

    private int hotbarSlot(Predicate<ItemStack> item) {
        ServerPlayerEntity pl = player();
        if (pl == null) return 0;
        var inv = pl.getInventory();
        for (int i = 0; i < 9; i++) if (!inv.getStack(i).isEmpty() && item.test(inv.getStack(i))) return i;
        for (int i = 9; i < 36; i++) {
            if (!inv.getStack(i).isEmpty() && item.test(inv.getStack(i))) {
                // not on the hotbar: move it to slot 8 so the demo (and you) can use it
                ItemStack a = inv.getStack(8);
                inv.setStack(8, inv.getStack(i));
                inv.setStack(i, a);
                pl.currentScreenHandler.sendContentUpdates();
                return 8;
            }
        }
        return 0;
    }

    private void select(Predicate<ItemStack> item) {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        int slot = hotbarSlot(item);
        pl.getInventory().setSelectedSlot(slot);
        pl.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(slot));
    }

    private void swing() {
        ServerPlayerEntity pl = player();
        if (pl != null) pl.swingHand(Hand.MAIN_HAND, true);
    }

    private void placeBlock(BlockPos b, net.minecraft.block.BlockState st) {
        set(b, st);
        swing();
        s.world.playSound(null, b, st.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1f, 0.8f);
    }

    private void placeCrystal(BlockPos base) {
        EndCrystalEntity c = new EndCrystalEntity(s.world, base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
        c.setShowBottom(false);
        s.world.spawnEntity(c);
        s.track(c);
        demoCrystal = c;
        swing();
    }

    private void breakCrystal() {
        ServerPlayerEntity pl = player();
        if (demoCrystal == null || demoCrystal.isRemoved() || pl == null) return;
        swing();
        demoCrystal.damage(s.world, s.world.getDamageSources().playerAttack(pl), 1f);
        demoCrystal = null;
    }

    private void anchorCharge(BlockPos b) {
        var st = s.world.getBlockState(b);
        if (!st.isOf(Blocks.RESPAWN_ANCHOR)) return;
        set(b, st.with(RespawnAnchorBlock.CHARGES, 1));
        swing();
        s.world.playSound(null, b, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 1f, 1f);
    }

    private void anchorBlow(BlockPos b) {
        if (!s.world.getBlockState(b).isOf(Blocks.RESPAWN_ANCHOR)) return;
        swing();
        set(b, Blocks.AIR.getDefaultState());
        Vec3d c = Vec3d.ofCenter(b);
        s.world.createExplosion(null, s.world.getDamageSources().badRespawnPoint(c), null, c.x, c.y, c.z, CombatMath.ANCHOR_POWER, false,
                World.ExplosionSourceType.BLOCK);
    }

    /** Your sword hits the dummy: the hurt flash, the sound, and a push (up and back). */
    /** Tests: the farthest the demo was from the dummy's hitbox when it hit (a player can't hit past 3). */
    public volatile double demoHitReach;

    private void hitDummy(boolean crit, double push, double up) {
        ServerPlayerEntity pl = player();
        if (dummy == null || pl == null) return;
        if (phase == Phase.DEMO) demoHitReach = Math.max(demoHitReach, Math.sqrt(dummy.getBoundingBox().squaredMagnitude(pl.getEyePos())));
        swing();
        s.world.sendEntityDamage(dummy, s.world.getDamageSources().playerAttack(pl));
        s.world.playSound(null, dummy.getBlockPos(), crit ? SoundEvents.ENTITY_PLAYER_ATTACK_CRIT
                : push > 0.5 ? SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK : SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1f, 1f);
        if (crit) s.world.spawnParticles(ParticleTypes.CRIT, dummy.getX(), dummy.getEyeY() - 0.4, dummy.getZ(), 14, 0.3, 0.3, 0.3, 0.2);
        Vec3d away = dummyAt.subtract(pl.getX(), dummyAt.y, pl.getZ());
        away = away.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : away.normalize();
        dummyVel = new Vec3d(away.x * push, up, away.z * push);
        dummyAir = up > 0;
    }

    private void pop() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        pl.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
        s.world.sendEntityStatus(pl, EntityStatuses.USE_TOTEM_OF_UNDYING);
        pl.currentScreenHandler.sendContentUpdates();
    }

    private void swapToOffhand() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        ItemStack main = pl.getMainHandStack();
        ItemStack off = pl.getOffHandStack();
        pl.setStackInHand(Hand.OFF_HAND, main);
        pl.setStackInHand(Hand.MAIN_HAND, off);
        pl.currentScreenHandler.sendContentUpdates();
    }

    private static boolean crystal(ItemStack s) { return s.isOf(Items.END_CRYSTAL); }

    private static boolean anchor(ItemStack s) { return s.isOf(Items.RESPAWN_ANCHOR); }

    private static boolean glow(ItemStack s) { return s.isOf(Items.GLOWSTONE); }

    private static boolean sword(ItemStack s) { return s.isIn(ItemTags.SWORDS); }

    // =========================================================================== the tutorials

    private void scriptCrystal() {
        BlockPos obi = rel(0, 0, 3);
        KeyCue slot = slotKey(TutorialDrill::crystal, "Crystal");
        KeyCue use = k(Key.USE, "Place");
        KeyCue hit = k(Key.ATTACK, "Hit");
        keyRow = List.of(slot, use, hit);
        step(30).look(top(obi)).say("A crystal can only go on obsidian or bedrock. Look at the top of the block.");
        step(14).keys(slot).then(() -> select(TutorialDrill::crystal)).say("Pick your crystals.");
        step(8).speed(5f).keys(use).then(() -> placeCrystal(obi)).say("Right-click the obsidian to place it.");
        step(1).pause().say("The crystal is down. Now hit it straight away. Don't move your mouse in between.");
        step(14).look(top(obi).add(0, 0.8, 0)).keys(hit).then(this::breakCrystal).say("Left-click the crystal. It blows up.");
        step(30).speed(20f).say("Place, hit. Place, hit. Fast players do both in 2 to 4 ticks.");
    }

    private void scriptAnchor() {
        BlockPos spot = rel(1, 0, 3);
        KeyCue aSlot = slotKey(TutorialDrill::anchor, "Anchor");
        KeyCue gSlot = slotKey(TutorialDrill::glow, "Glowstone");
        KeyCue use = k(Key.USE, "Use");
        keyRow = List.of(aSlot, gSlot, use);
        step(26).look(Vec3d.ofCenter(spot)).say("A respawn anchor blows up when you use it outside the Nether.");
        step(10).keys(aSlot).then(() -> select(TutorialDrill::anchor)).say("Pick your anchors.");
        step(10).keys(use).then(() -> placeBlock(spot, Blocks.RESPAWN_ANCHOR.getDefaultState())).say("Place it near them.");
        step(8).speed(6f).keys(gSlot).then(() -> select(TutorialDrill::glow)).say("Switch to glowstone...");
        step(8).keys(use).then(() -> anchorCharge(spot)).say("...and right-click the anchor to charge it.");
        step(1).pause().say("It's charged. Right-click it again with anything that isn't glowstone and it explodes.");
        step(6).keys(aSlot).then(() -> select(TutorialDrill::anchor));
        step(16).keys(use).then(() -> anchorBlow(spot)).say("Boom.");
        step(30).speed(20f).say("Place, glowstone, use. Three right-clicks.");
    }

    private void scriptAnchorDtap() {
        BlockPos spot = rel(1, 0, 3);
        KeyCue aSlot = slotKey(TutorialDrill::anchor, "Anchor");
        KeyCue gSlot = slotKey(TutorialDrill::glow, "Glowstone");
        KeyCue use = k(Key.USE, "Use");
        keyRow = List.of(aSlot, gSlot, use);
        step(26).look(Vec3d.ofCenter(spot)).say("Watch: two anchors in the same spot, one right after the other.");
        step(6).keys(aSlot).then(() -> select(TutorialDrill::anchor));
        step(6).keys(use).then(() -> placeBlock(spot, Blocks.RESPAWN_ANCHOR.getDefaultState())).say("Place an anchor...");
        step(6).keys(gSlot).then(() -> select(TutorialDrill::glow));
        step(8).keys(use).then(() -> anchorCharge(spot)).say("...and charge it.");
        step(6).keys(aSlot).then(() -> select(TutorialDrill::anchor)).say("Hold an anchor and right-click the charged one...");
        step(3).speed(4f).keys(use).then(() -> anchorBlow(spot)).say("It blows up.");
        step(1).pause().say("When a block breaks, the spot it was in can still be clicked for a split second. "
                + "If you right-click again right away, you place a new block there.");
        step(8).keys(use).then(() -> placeBlock(spot, Blocks.RESPAWN_ANCHOR.getDefaultState()))
                .say("So the second right-click puts a new anchor right where the first one was - floating in the air.");
        step(1).pause().say("That's a fresh anchor, ready to go. Charge it and blow it up again.");
        step(5).speed(20f).keys(gSlot).then(() -> select(TutorialDrill::glow));
        step(5).keys(use).then(() -> anchorCharge(spot));
        step(5).keys(aSlot).then(() -> select(TutorialDrill::anchor));
        step(30).keys(use).then(() -> anchorBlow(spot)).say("Two big explosions, one right after the other. Very hard to survive.");
    }

    private void scriptHitCrystal() {
        BlockPos obi = rel(1, 0, 3);
        KeyCue sw = slotKey(TutorialDrill::sword, "Sword");
        KeyCue cr = slotKey(TutorialDrill::crystal, "Crystal");
        KeyCue fwd = k(Key.FORWARD, "Forward");
        KeyCue spr = k(Key.SPRINT, "Sprint");
        KeyCue hit = k(Key.ATTACK, "Hit");
        KeyCue use = k(Key.USE, "Place");
        keyRow = List.of(fwd, spr, sw, cr, hit, use);
        step(24).look(dummyHome.add(0, 1.2, 0)).say("Knock them into the air, then crystal them before they land.");
        step(8).keys(sw).then(() -> select(TutorialDrill::sword));
        step(4).speed(5f).then(() -> walkTo(2.4, 0.28, fwd, spr)).until(() -> !walking).say("Sprint at them...");
        step(5).keys(hit).then(() -> hitDummy(false, 0.45, 0.5)).say("...and hit when you're close. A sprint hit knocks them up and back.");
        step(1).pause().say("They're in the air now. Nothing under their feet protects them from a crystal.");
        step(3).keys(cr).then(() -> select(TutorialDrill::crystal)).look(top(obi));
        step(3).keys(use).then(() -> placeCrystal(obi)).say("Place a crystal next to them...");
        step(10).keys(hit).then(this::breakCrystal).say("...and break it before they land.");
        step(30).speed(20f).say("Hit, place, break. You have about half a second.");
    }

    private void scriptDoubleTap() {
        BlockPos left = rel(1, 0, 3);
        BlockPos right = rel(-1, 0, 3);
        KeyCue cr = slotKey(TutorialDrill::crystal, "Crystal");
        KeyCue use = k(Key.USE, "Place");
        KeyCue hit = k(Key.ATTACK, "Hit");
        keyRow = List.of(cr, use, hit);
        step(24).look(dummyHome.add(0, 1.0, 0)).say("Watch: two crystals on one player in a single jump - and both count.");
        step(6).keys(cr).then(() -> select(TutorialDrill::crystal));
        step(3).speed(4f).then(() -> {
            dummyVel = new Vec3d(0, 0.72, 0);
            dummyAir = true;
        }).say("They're knocked into the air.");
        step(3).look(top(left)).keys(use).then(() -> placeCrystal(left));
        step(3).keys(hit).then(this::breakCrystal).say("First crystal.");
        step(1).pause().say("They just got hurt. For the next 10 ticks - half a second - another hit only counts for the part that's bigger than this one. "
                + "Blow up a second crystal now and it's mostly wasted.");
        step(4).look(top(right)).keys(use).then(() -> placeCrystal(right)).say("So put the next crystal down straight away, but don't hit it yet...");
        step(6).say("...wait for the half second to end...");
        step(14).keys(hit).then(this::breakCrystal).say("...now! The second blast counts in full.");
        step(30).speed(20f).say("Blast, place, wait half a second, blast. Two full hits before they land.");
    }

    private void scriptRetotem() {
        KeyCue tSlot = slotKey(s -> s.isOf(Items.TOTEM_OF_UNDYING), "Totem");
        KeyCue swap = k(Key.SWAP, "Offhand swap");
        keyRow = List.of(tSlot, swap);
        step(20).look(Vec3d.ofBottomCenter(rel(0, 1, 5))).say("Your totem saves you from dying once. Then it's gone.");
        step(12).speed(5f).then(this::pop).say("Pop! The totem in your offhand is used up.");
        step(1).pause().say("Your offhand is empty now. If you get hit hard again, you die. Get the next totem in before anything else.");
        step(8).keys(tSlot).then(() -> select(s -> s.isOf(Items.TOTEM_OF_UNDYING))).say("Pick the totem in your hotbar...");
        step(10).keys(swap).then(this::swapToOffhand).say("...and press your offhand swap key to move it to your other hand.");
        step(30).speed(20f).say("Two key presses. Aim for under 6 ticks - a third of a second.");
    }

    private void scriptSurround() {
        KeyCue oSlot = slotKey(s -> s.isOf(Items.OBSIDIAN), "Obsidian");
        KeyCue use = k(Key.USE, "Place");
        keyRow = List.of(oSlot, use);
        step(22).look(Vec3d.ofBottomCenter(rel(0, 0, 1)).add(0, 0.1, 0)).say("Blocks on all four sides of your feet stop crystals placed at your feet.");
        step(8).keys(oSlot).then(() -> select(s -> s.isOf(Items.OBSIDIAN)));
        Direction[] sides = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};
        boolean first = true;
        for (Direction d : sides) {
            BlockPos b = p0.offset(d);
            Step st = step(6).look(Vec3d.ofCenter(b).add(0, -0.4, 0)).keys(use).then(() -> placeBlock(b, Blocks.OBSIDIAN.getDefaultState()));
            if (first) {
                st.speed(5f).say("Look down at the floor next to your feet and place obsidian there.");
                first = false;
            }
        }
        step(1).pause().say("All four sides done. That's called a surround. Now only crystals above the obsidian can really hurt you.");
        step(20).speed(20f).say("Four quick clicks, turning your mouse a little each time.");
    }

    private void scriptCrit() {
        KeyCue sw = slotKey(TutorialDrill::sword, "Sword");
        KeyCue jump = k(Key.JUMP, "Jump");
        KeyCue hit = k(Key.ATTACK, "Hit");
        keyRow = List.of(sw, k(Key.FORWARD, "Forward"), jump, hit);
        step(20).look(dummyHome.add(0, 1.3, 0)).say("A hit while you're falling is a critical hit: 50% more damage.");
        step(8).keys(sw).then(() -> select(TutorialDrill::sword));
        KeyCue fwdC = k(Key.FORWARD, "Forward");
        step(2).then(() -> walkTo(2.2, 0.2, fwdC)).until(() -> !walking);
        step(5).speed(5f).keys(jump).then(() -> {
            demoMove = true;
            ServerPlayerEntity pl = player();
            if (pl != null) {
                pl.setVelocity(0, 0.42, 0);
                pl.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket(pl));
            }
        }).say("Jump...");
        step(1).pause().say("You're going up. Wait until you start to fall.");
        step(4);
        step(10).keys(hit).then(() -> hitDummy(true, 0.25, 0.1)).say("...and hit on the way down. See the stars? That's a crit.");
        step(30).speed(20f).then(() -> {
            demoMove = false;
            holdAt(Vec3d.ofBottomCenter(p0));
        }).say("Jump, wait, hit while falling - right when your sword is charged.");
    }

    private void scriptWtap() {
        KeyCue sw = slotKey(TutorialDrill::sword, "Sword");
        KeyCue fwd = k(Key.FORWARD, "Forward");
        KeyCue spr = k(Key.SPRINT, "Sprint");
        KeyCue hit = k(Key.ATTACK, "Hit");
        keyRow = List.of(sw, fwd, spr, hit);
        step(20).look(dummyHome.add(0, 1.3, 0)).say("A hit while sprinting knocks them back extra far.");
        step(8).keys(sw).then(() -> select(TutorialDrill::sword));
        step(4).speed(5f).then(() -> walkTo(2.4, 0.28, fwd, spr)).until(() -> !walking).say("Sprint at them...");
        step(10).keys(hit).then(() -> hitDummy(false, 0.9, 0.42)).say("...and hit. Big knockback.");
        step(1).pause().say("That hit stopped your sprint. Hit again now and the knockback is small.");
        step(8).say("So let go of W for a moment...");
        step(4).then(() -> walkTo(2.4, 0.28, fwd, spr)).until(() -> !walking).say("...and press it again. You're sprinting again.");
        step(10).keys(hit).then(() -> hitDummy(false, 0.9, 0.42)).say("Hit: big knockback again. That's a W-tap.");
        step(1).then(() -> dummyChase = true).until(() -> !dummyChase).say("They run back at you to hit you...");
        step(4).then(() -> walkTo(2.4, 0.28, fwd, spr)).until(() -> !walking).say("...but you W-tap again...");
        step(10).keys(hit).then(() -> hitDummy(false, 0.9, 0.42)).say("...and the sprint hit sends them away before they can swing.");
        step(30).speed(20f).say("Hit, let go of W, press W, hit. They can't get close enough to hit you back.");
    }

    private void scriptShield() {
        KeyCue sw = slotKey(TutorialDrill::sword, "Sword");
        KeyCue axe = slotKey(s -> s.isIn(ItemTags.AXES), "Axe");
        KeyCue hit = k(Key.ATTACK, "Hit");
        keyRow = List.of(k(Key.FORWARD, "Forward"), sw, axe, hit);
        step(20).look(dummyHome.add(0, 1.3, 0)).say("Their shield is up. It blocks every hit from the front.");
        step(8).keys(sw).then(() -> select(TutorialDrill::sword));
        step(2).then(() -> walkTo(2.2, 0.2, k(Key.FORWARD, "Forward"))).until(() -> !walking).say("Walk up to them...");
        step(14).speed(6f).keys(hit).then(() -> {
            swing();
            s.world.playSound(null, dummy.getBlockPos(), SoundEvents.ITEM_SHIELD_BLOCK.value(), SoundCategory.PLAYERS, 1f, 1f);
        }).say("Your sword just bounces off.");
        step(8).keys(axe).then(() -> select(s -> s.isIn(ItemTags.AXES))).say("Switch to your axe...");
        step(10).keys(hit).then(() -> {
            swing();
            raiseShield(false);
            s.world.playSound(null, dummy.getBlockPos(), SoundEvents.ITEM_SHIELD_BREAK.value(), SoundCategory.PLAYERS, 1f, 1f);
        }).say("...and hit the shield.");
        step(1).pause().say("An axe hit turns their shield off for 5 seconds. Now switch back and attack.");
        step(6).keys(sw).then(() -> select(TutorialDrill::sword));
        step(30).speed(20f).keys(hit).then(() -> hitDummy(false, 0.4, 0.3)).say("Axe to break it, sword to hit. Keep an axe in your hotbar.");
    }

    // =========================================================================== tick

    @Override
    protected void tick() {
        if (!waiting && !reading) tickDummy(); // the world is frozen while it explains
        if (!waiting && !reading && phase == Phase.DEMO) tickWalk();
        // the client gets "shield up" just before it gets the shield when the dummy first appears,
        // so it never shows the pose: lower it for a tick and raise it again once it's there
        if (id == Tutorials.Id.SHIELD_BREAK && dummy != null && dummyShield && ticks == 2) dummy.clearActiveItem();
        switch (phase) {
            case DEMO -> runDemo();
            case FADE -> {
                int t = ticks - fadeAt;
                fade = t < 10 ? t / 10f : Math.max(0f, 1f - (t - 14) / 10f);
                if (t == 12) {
                    // the scene goes back to how it was, then your turn
                    freeze(false);
                    setRate(20f);
                    camOn = false;
                    demoMove = false;
                    reading = false;
                    dummyChase = false;
                    stopWalking();
                    buildScene();
                    keyRow = List.of();
                    caption = "Your turn: " + id.goal + ".";
                }
                if (t >= 24) {
                    fade = 0f;
                    phase = Phase.TRY;
                    status = "Your turn: " + id.goal;
                    startTry();
                    cue();
                }
            }
            case TRY -> tickTry();
        }
    }

    /** The dummy runs at you and swings (the W-tap demo), until it's close enough to try a hit. */
    private boolean dummyChase;

    private BlockPos center0;
    private int room = 8;

    /** The dummy never leaves the arena, however hard it's hit. */
    private Vec3d insideArena(Vec3d v) {
        if (center0 == null) return v;
        double lim = room - 1.0;
        double x = Math.max(center0.getX() + 0.5 - lim, Math.min(center0.getX() + 0.5 + lim, v.x));
        double z = Math.max(center0.getZ() + 0.5 - lim, Math.min(center0.getZ() + 0.5 + lim, v.z));
        if (x != v.x) dummyVel = new Vec3d(0, dummyVel.y, dummyVel.z);
        if (z != v.z) dummyVel = new Vec3d(dummyVel.x, dummyVel.y, 0);
        return new Vec3d(x, v.y, z);
    }

    private void tickDummy() {
        if (dummy == null || dummy.isRemoved() || dummyAt == null) return;
        ServerPlayerEntity me0 = player();
        if (dummyChase && !dummyAir && me0 != null) {
            Vec3d d = new Vec3d(me0.getX() - dummyAt.x, 0, me0.getZ() - dummyAt.z);
            if (d.length() > 3.2) {
                dummyAt = dummyAt.add(d.normalize().multiply(0.26));
            } else {
                // in range: it swings - and the next sprint hit sends it away
                dummy.swingHand(Hand.MAIN_HAND);
                s.world.playSound(null, dummy.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.PLAYERS, 0.8f, 1f);
                dummyChase = false;
            }
            Scene.pin(dummy, dummyAt, Scene.yawTowards(dummyAt, new Vec3d(me0.getX(), me0.getY(), me0.getZ())));
            return;
        }
        if (dummyAir || dummyVel.lengthSquared() > 1e-5) {
            dummyAt = insideArena(dummyAt.add(dummyVel));
            dummyVel = new Vec3d(dummyVel.x * 0.91, (dummyVel.y - 0.08) * 0.98, dummyVel.z * 0.91);
            if (dummyAt.y <= dummyHome.y) {
                dummyAt = new Vec3d(dummyAt.x, dummyHome.y, dummyAt.z);
                dummyVel = new Vec3d(dummyVel.x * 0.5, 0, dummyVel.z * 0.5);
                if (dummyAir) {
                    dummyAir = false;
                    onDummyLanded();
                }
            }
        } else if (dummyAt.squaredDistanceTo(dummyHome) > 0.01) {
            // walks back to its spot
            Vec3d d = dummyHome.subtract(dummyAt);
            dummyAt = dummyAt.add(d.normalize().multiply(Math.min(0.12, d.length())));
        }
        ServerPlayerEntity pl = player();
        Vec3d me = pl == null ? dummyAt.add(0, 0, -1) : new Vec3d(pl.getX(), pl.getY(), pl.getZ());
        Scene.pin(dummy, dummyAt, Scene.yawTowards(dummyAt, me));
        if (id == Tutorials.Id.SHIELD_BREAK && !dummyShield && shieldDownUntil >= 0 && ticks >= shieldDownUntil) {
            shieldDownUntil = -1;
            raiseShield(true);
        }
        if (dummyShield && dummy != null && !dummy.isUsingItem()) dummy.setCurrentHand(Hand.OFF_HAND);
    }

    // =========================================================================== your turn

    private void startTry() {
        nextPopAt = ticks + 40;
        surroundCueAt = ticks;
        launchedAt = -1;
        lastBlastAt = -999;
        lastSprintHit = -999;
    }

    private void tickTry() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        switch (id) {
            case RETOTEM -> {
                if (popAt < 0 && ticks >= nextPopAt) {
                    refillKit();
                    pop();
                    popAt = ticks;
                    feedback("Pop! Totem back in your offhand, quick", 0xFFFFD166);
                } else if (popAt >= 0) {
                    int dt = ticks - popAt;
                    if (pl.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                        rep(dt <= 20, dt * 50, String.format(Locale.ROOT, "Totem back in %d ticks%s", dt, dt <= 20 ? "" : " - try to be quicker (under 20)"));
                        popAt = -1;
                        nextPopAt = ticks + 50;
                    } else if (dt > 80) {
                        rep(false, dt * 50, "Too slow - pick the totem with its key, then press your swap key");
                        popAt = -1;
                        nextPopAt = ticks + 50;
                    }
                }
            }
            case SURROUND -> {
                if (clearSurroundAt >= 0) {
                    if (ticks >= clearSurroundAt) {
                        clearSurroundAt = -1;
                        buildScene();
                        surroundCueAt = ticks;
                        cue();
                    }
                    return;
                }
                BlockPos feet = pl.getBlockPos();
                int solid = 0;
                for (Direction d : Direction.Type.HORIZONTAL) if (!s.world.getBlockState(feet.offset(d)).getCollisionShape(s.world, feet.offset(d)).isEmpty()) solid++;
                int dt = ticks - surroundCueAt;
                if (solid == 4) {
                    rep(dt <= 80, dt * 50, String.format(Locale.ROOT, "Surrounded in %.1f s%s", dt / 20.0, dt <= 80 ? "" : " - aim for under 4 s"));
                    clearSurroundAt = ticks + 30;
                }
            }
            case HIT_CRYSTAL -> {
                if (dummyAir) status = "It's in the air - crystal it!";
            }
            case CRYSTAL_DTAP -> {
                if (!dummyAir && ticks >= nextPopAt) {
                    dummyVel = new Vec3d(0, 0.72, 0);
                    dummyAir = true;
                    launchedAt = ticks;
                    firstBlastAt = -1;
                    s.world.playSound(null, dummy.getBlockPos(), SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.6f, 1.2f);
                }
            }
            default -> { }
        }
    }

    private int firstBlastAt = -1;

    private void onDummyLanded() {
        if (phase == Phase.TRY && id == Tutorials.Id.CRYSTAL_DTAP && launchedAt >= 0) {
            launchedAt = -1;
            nextPopAt = ticks + 50;
            if (firstBlastAt >= 0) feedback("It landed before your second blast", 0xFFFFB020);
            firstBlastAt = -1;
            refillKit();
            return;
        }
        if (phase == Phase.TRY && id == Tutorials.Id.HIT_CRYSTAL && launchedAt >= 0) {
            launchedAt = -1;
            rep(false, 0, "It landed first - place and break the crystal while it's still up");
            status = "Your turn: " + id.goal;
            refillKit();
        }
    }

    @Override
    public void onCrystalSpawn(EndCrystalEntity crystal) {
        if (phase == Phase.TRY) crystalSpawnedAt.put(crystal.getId(), ticks);
    }

    @Override
    public void observeCrystalBreak(EndCrystalEntity crystal, Entity attacker) {
        if (phase != Phase.TRY || attacker != player()) return;
        Integer at = crystalSpawnedAt.remove(crystal.getId());
        if (id == Tutorials.Id.CRYSTAL_BASICS) {
            int dt = at == null ? 99 : ticks - at;
            rep(dt <= 20, dt * 50, dt <= 20 ? String.format(Locale.ROOT, "Placed and broken in %d ticks", dt) : "Hit it right after you place it");
            refillKit();
        } else if (id == Tutorials.Id.CRYSTAL_DTAP) {
            if (!dummyAir || launchedAt < 0) {
                feedback("Wait for the jump", 0xFFFFB020);
            } else if (firstBlastAt < 0) {
                firstBlastAt = ticks;
                feedback("First one - now wait half a second", 0xFFECEEF2);
            } else {
                int gap = ticks - firstBlastAt;
                rep(gap >= 10, gap * 50, gap >= 10 ? String.format(Locale.ROOT, "Double tap! %d ticks apart", gap)
                        : String.format(Locale.ROOT, "Only %d ticks apart - wait until 10", gap));
                firstBlastAt = -1;
                launchedAt = -1;
            }
        } else if (id == Tutorials.Id.HIT_CRYSTAL) {
            if (dummyAir && launchedAt >= 0) {
                rep(true, (ticks - launchedAt) * 50, String.format(Locale.ROOT, "Caught it in the air, %d ticks after your hit", ticks - launchedAt));
                launchedAt = -1;
                status = "Your turn: " + id.goal;
            } else {
                feedback("Hit it into the air with your sword first", 0xFFFFB020);
            }
            refillKit();
        }
    }

    @Override
    public void observeAnchor(BlockPos pos) {
        if (phase != Phase.TRY) return;
        if (id == Tutorials.Id.ANCHOR) {
            rep(true, 0, "Boom - that's an anchor");
            refillKit();
        } else if (id == Tutorials.Id.ANCHOR_DTAP) {
            int dt = ticks - lastBlastAt;
            if (pos.equals(lastBlast) && dt <= 30) {
                rep(true, dt * 50, String.format(Locale.ROOT, "Double tap! Second anchor %d ticks after the first", dt));
                lastBlastAt = -999;
                refillKit();
            } else {
                if (pos.equals(lastBlast) && dt <= 80) feedback("Same spot, but too slow - right-click again straight away", 0xFFFFB020);
                else feedback("One down - now another anchor in the same spot, fast", 0xFFECEEF2);
                lastBlast = pos;
                lastBlastAt = ticks;
            }
        }
    }

    @Override
    public boolean onTrackedDamage(Entity e, DamageSource source, float amount) {
        if (e != dummy) return true;
        ServerPlayerEntity pl = player();
        if (phase != Phase.TRY || pl == null || source.getAttacker() != pl) return false;
        ItemStack held = pl.getMainHandStack();
        switch (id) {
            case HIT_CRYSTAL -> {
                if (!dummyAir) {
                    hitDummy(false, pl.isSprinting() ? 0.45 : 0.3, 0.5);
                    launchedAt = ticks;
                }
            }
            case CRIT -> {
                boolean crit = pl.fallDistance > 0 && !pl.isOnGround() && !pl.isSprinting() && !pl.isClimbing() && !pl.isTouchingWater();
                hitDummy(crit, 0.2, 0.15);
                rep(crit, 0, crit ? "Critical hit!" : pl.isOnGround() ? "Not a crit - jump first and hit while you fall"
                        : pl.isSprinting() ? "Not a crit - you can't crit while sprinting" : "Not a crit - hit on the way down, not up");
            }
            case WTAP -> {
                boolean sprint = pl.isSprinting();
                hitDummy(false, sprint ? 0.9 : 0.35, sprint ? 0.36 : 0.2);
                if (!sprint) {
                    feedback("That wasn't a sprint hit - let go of W and press it again before you hit", 0xFFFFB020);
                } else if (ticks - lastSprintHit <= 60) {
                    rep(true, (ticks - lastSprintHit) * 50, "Two sprint hits in a row - that's a W-tap");
                    lastSprintHit = -999;
                } else {
                    feedback("Sprint hit! Now let go of W, press it again and hit", 0xFFECEEF2);
                    lastSprintHit = ticks;
                }
            }
            case SHIELD_BREAK -> {
                swing();
                if (dummyShield) {
                    if (held.isIn(ItemTags.AXES)) {
                        raiseShield(false);
                        shieldDownUntil = ticks + 100;
                        s.world.playSound(null, dummy.getBlockPos(), SoundEvents.ITEM_SHIELD_BREAK.value(), SoundCategory.PLAYERS, 1f, 1f);
                        feedback("Shield's down for 5 s - switch to your sword and hit", 0xFFECEEF2);
                    } else {
                        s.world.playSound(null, dummy.getBlockPos(), SoundEvents.ITEM_SHIELD_BLOCK.value(), SoundCategory.PLAYERS, 1f, 1f);
                        feedback("Blocked - use your axe on the shield", 0xFFFFB020);
                    }
                } else if (held.isIn(ItemTags.SWORDS)) {
                    hitDummy(false, 0.4, 0.3);
                    rep(true, 0, "Shield broken, sword hit landed");
                    shieldDownUntil = ticks + 30;
                } else {
                    hitDummy(false, 0.3, 0.2);
                    feedback("Now switch to your sword", 0xFFFFB020);
                }
            }
            default -> { }
        }
        return false;
    }

    @Override
    public String summaryLine() { return id.title; }
}
