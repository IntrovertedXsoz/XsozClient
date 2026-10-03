package dev.xsoz.client.training.bot;

import dev.xsoz.client.training.CombatMath;
import dev.xsoz.client.training.Kit;
import dev.xsoz.client.training.KitSpec;
import dev.xsoz.client.training.drill.Scene;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A PvP bot that plays like a person.
 *
 * <ul>
 *   <li><b>Body</b>: walks, sprints, jumps, steps up, drops into craters, takes vanilla knockback,
 *   glides on an elytra. Moves along real A* paths ({@link Pathfinder}) and never spam-jumps in a
 *   hole: stuck, it mines its way out or pearls out.</li>
 *   <li><b>Head</b>: turns at the level's speed and has to LOOK at a block, crystal or player
 *   before it places, breaks, mines or swings - so you can read what it is about to do. It holds
 *   still while it re-totems.</li>
 *   <li><b>Inventory</b>: real items that run out; hotbar switches and pulling items out of the
 *   inventory take time; totems are re-equipped after a pop; armour wears down and is mended.</li>
 *   <li><b>Health</b>: the player rules - difficulty, its worn armour and enchantments, shields,
 *   the 10-tick hurt window, absorption, totems. Hearts float above its head.</li>
 *   <li><b>Brain</b>: crystals and anchors scored by real exposure (net damage, teammates spared,
 *   lethal spots first), sword-hit-then-crystal combos, crits and W-taps, shield and axe play,
 *   mace wind-jumps and elytra dives, city-mining a surround, reacting to YOUR crystals (breaking
 *   the ones that hurt you more, stepping out of line of the ones that hurt it), eating gapples for
 *   real, running off to mend, pearling out and back in.</li>
 * </ul>
 * Every level knows every technique; the level sets how fast and how precisely it plays.
 * Server thread only.
 */
public final class PvpBot {
    private enum Air { NONE, WIND_UP, BOOST, GLIDE, DIVE }

    private enum Goal { FIGHT, RETREAT, DODGE, HOLD }

    public final String name;
    public final int team;
    public BotLevel level;
    /** Show the level on the name tag (adaptive Free Roam: it changes). */
    public boolean showLevel;
    private final int reactionOverrideMs;
    public final java.util.EnumSet<FreeRoamConfig.Ability> abilities;
    public final Personality personality;
    private int reactionTicks;
    private final Kit kit;
    private BotWorld bw;
    private ServerWorld w;
    private MannequinEntity body;
    private Fighter self;

    // ---- body
    private Vec3d pos = Vec3d.ZERO;
    private Vec3d vel = Vec3d.ZERO;
    private float yaw;
    private float pitch;
    private float bodyYaw;
    private boolean onGround;
    private double peakY;
    private boolean gliding;
    private Air air = Air.NONE;
    private int airUntil;
    private boolean windJump;
    private int boostTicks;
    private Vec3d walk = Vec3d.ZERO;
    private boolean sprinting;
    private boolean wantJump;
    /** Where the legs want to go this tick (unit, flat) and how far; physics picks walk or sprint speed. */
    private Vec3d moveDir = Vec3d.ZERO;
    private double moveLeft;
    private boolean wantSprint;

    // ---- head
    private Vec3d lookTarget;
    private int headLockUntil;
    /** Tick an action last aimed the head (then it doesn't look where it runs). */
    private int lookSetAt = -1;
    private Vec3d lastAim;

    // ---- movement planning
    private Goal goal = Goal.FIGHT;
    private BlockPos goalPos;
    private int goalUntil;
    private List<BlockPos> path;
    private int pathIndex;
    private int pathAt = -999;
    private BlockPos pathGoal;
    private Vec3d lastPos = Vec3d.ZERO;
    private int stuckTicks;
    private int strafeSign = 1;
    private int strafeUntil;
    private int retreatUntil;

    // ---- health
    private boolean alive;
    private float hp;
    private float absorption;
    private int absorptionUntil;
    private int regenUntil;
    private int regenEvery = 25;
    private int fireResUntil;
    private int hurtTicks;
    private float lastHurt;
    private int lastDamagedAt = -999;
    private int extraTotems = -1;

    // ---- inventory
    private final ItemStack[] inv = new ItemStack[36];
    private ItemStack offhand = ItemStack.EMPTY;
    private int selected;
    private int busyUntil;
    private int retotemAt = -1;

    // ---- actions
    private int nextActionAt;
    private int nextSwingAt;
    private int eatingUntil = -1;
    private int shieldUntil = -1;
    private int shieldDisabledUntil;
    private EndCrystalEntity myCrystal;
    private int myCrystalAt;
    private BlockPos buildThenCrystal;
    private BlockPos anchorAt;
    private int anchorStage;
    private EnderPearlEntity pearl;
    private Vec3d pearlLast;
    private boolean surrounded;
    private Fighter target;
    private int targetUntil;
    private int wtapUntil;
    private int nextPearlAt;
    private int comboUntil;
    private BlockPos mining;
    private float mineProgress;
    private int nextThreatCheck;
    private BlockPos myCrystalBase;
    private Plan pending;
    private int pendingUntil;
    private int surroundGiveUpUntil;
    // pearls: the throw being lined up, and the pearl of theirs being watched
    private float pearlYaw;
    private float pearlPitch;
    private int pearlAimUntil = -1;
    private String pearlWhy = "";
    private Vec3d pearlTarget;
    private EnderPearlEntity watched;
    private int watchedNoticeAt;
    private boolean watchedFollow;
    // double taps
    private BlockPos dtapSpot;
    private int dtapUntil;
    private int anchorDtaps;

    // ---- stats (tests and the HUD)
    public int crystalsPlaced;
    public int crystalsBroken;
    public int enemyCrystalsBroken;
    public int anchorsBlown;
    public int meleeHits;
    public int maceSmashes;
    public int pearlsThrown;
    public int gapplesEaten;
    public int blocksMined;
    public int bottlesThrown;
    public int eatStarts;
    public int pearlsFollowed;
    public int dtaps;
    public int anchorDtapsDone;
    /** Longest reach it ever used to click a block / hit something (tests: never more than a player's). */
    public double maxClickReach;
    public double maxHitReach;
    /** Where its first pearl landed (tests). */
    public Vec3d firstLanding;
    /** Last plan: candidates, rejected for no clickable face, rejected by the score. */
    public String planInfo = "";
    public String eatFail = "";
    public String lastAction = "";

    public PvpBot(String name, int team, BotLevel level, FreeRoamConfig.Fight fight, Kit kit, int reactionMsOverride) {
        this(name, team, level, fight.abilities(), Personality.ALL_ROUNDER, kit, reactionMsOverride);
    }

    public PvpBot(String name, int team, BotLevel level, java.util.Set<FreeRoamConfig.Ability> abilities, Personality personality,
                  Kit kit, int reactionMsOverride) {
        this.name = name;
        this.team = team;
        this.level = level;
        this.abilities = java.util.EnumSet.noneOf(FreeRoamConfig.Ability.class);
        this.abilities.addAll(abilities);
        this.personality = personality == null ? Personality.ALL_ROUNDER : personality;
        this.kit = kit;
        this.reactionOverrideMs = reactionMsOverride;
        int ms = reactionMsOverride > 0 ? reactionMsOverride : level.reactionMs;
        this.reactionTicks = Math.max(1, Math.round(ms / 50f));
    }

    /** A new level from now on (adaptive Free Roam). */
    public void setLevel(BotLevel l) {
        level = l;
        int ms = reactionOverrideMs > 0 ? reactionOverrideMs : l.reactionMs;
        reactionTicks = Math.max(1, Math.round(ms / 50f));
        if (alive()) nameTag();
    }

    /** Sparring: a fixed number of totems instead of the kit's. */
    public PvpBot totems(int n) {
        extraTotems = n;
        return this;
    }

    public void bind(Fighter f) { self = f; }

    private boolean can(FreeRoamConfig.Ability a) { return abilities.contains(a); }

    /** Crystals or anchors. */
    private boolean explosives() { return can(FreeRoamConfig.Ability.CRYSTALS) || can(FreeRoamConfig.Ability.ANCHORS); }

    private boolean meleeOn() { return can(FreeRoamConfig.Ability.SWORD) || can(FreeRoamConfig.Ability.AXE_SHIELD) || can(FreeRoamConfig.Ability.MACE); }

    private boolean aerialOn() { return can(FreeRoamConfig.Ability.MACE) || can(FreeRoamConfig.Ability.ELYTRA); }

    /** The pearl in flight, if any (other bots watch it). */
    public EnderPearlEntity pearlInFlight() { return pearl; }

    public MannequinEntity body() { return body; }

    public boolean alive() { return alive && body != null && !body.isRemoved(); }

    public float health() { return alive ? hp + absorption : 0f; }

    public float hp() { return hp; }

    public float absorption() { return absorption; }

    public Vec3d pos() { return pos; }

    public int reactionTicks() { return reactionTicks; }

    public Fighter target() { return target; }

    public boolean eating() { return eatingUntil >= 0; }

    /** GameTests: put the bot at a given health. */
    public void healthForTests(float h) {
        hp = h;
        absorption = 0;
    }

    public int totemCount() {
        int n = offhand.isOf(Items.TOTEM_OF_UNDYING) ? 1 : 0;
        for (ItemStack st : inv) if (st != null && st.isOf(Items.TOTEM_OF_UNDYING)) n += st.getCount();
        return n;
    }

    // =========================================================================== spawning

    public void spawn(BotWorld world, Vec3d at, float facing) {
        this.bw = world;
        this.w = world.session().world;
        despawnBody();
        pos = at;
        lastPos = at;
        vel = Vec3d.ZERO;
        yaw = facing;
        bodyYaw = facing;
        pitch = 0;
        peakY = at.y;
        gliding = false;
        air = Air.NONE;
        hp = 20f;
        absorption = 0;
        regenUntil = 0;
        fireResUntil = 0;
        hurtTicks = 0;
        lastHurt = 0;
        eatingUntil = -1;
        shieldUntil = -1;
        retotemAt = -1;
        myCrystal = null;
        buildThenCrystal = null;
        anchorStage = 0;
        pearl = null;
        surrounded = false;
        mining = null;
        target = null;
        path = null;
        goal = Goal.FIGHT;
        for (int i = 0; i < 36; i++) inv[i] = ItemStack.EMPTY;
        Map<Integer, ItemStack> m = kit.materialise(w.getRegistryManager());
        for (var e : m.entrySet()) if (e.getKey() < 36) inv[e.getKey()] = e.getValue().copy();
        offhand = m.getOrDefault(KitSpec.OFFHAND, ItemStack.EMPTY).copy();
        if (!has(s -> s.isIn(ItemTags.PICKAXES))) {
            for (int i = 35; i >= 9; i--) {
                if (inv[i].isEmpty()) {
                    inv[i] = Kit.toStack(new KitSpec.Entry(i, "minecraft:netherite_pickaxe", 1).ench("minecraft:efficiency", 5), w.getRegistryManager());
                    break;
                }
            }
        }
        if (extraTotems >= 0) {
            for (int i = 0; i < 36; i++) if (inv[i].isOf(Items.TOTEM_OF_UNDYING)) inv[i] = ItemStack.EMPTY;
            if (extraTotems == 0 && offhand.isOf(Items.TOTEM_OF_UNDYING)) offhand = ItemStack.EMPTY;
            int left = Math.max(0, extraTotems - (offhand.isOf(Items.TOTEM_OF_UNDYING) ? 1 : 0));
            for (int i = 35; i >= 9 && left > 0; i--) {
                if (inv[i].isEmpty()) {
                    inv[i] = new ItemStack(Items.TOTEM_OF_UNDYING);
                    left--;
                }
            }
        }
        selected = firstHotbar(st -> st.isOf(Items.END_CRYSTAL) || st.isIn(ItemTags.SWORDS) || st.isOf(Items.MACE));
        if (selected < 0 || selected > 8) selected = 0;
        body = EntityType.MANNEQUIN.create(w, SpawnReason.COMMAND);
        if (body == null) return;
        body.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0f);
        body.setNoGravity(true);
        body.setCustomNameVisible(true);
        body.equipStack(EquipmentSlot.HEAD, m.getOrDefault(KitSpec.HEAD, ItemStack.EMPTY).copy());
        body.equipStack(EquipmentSlot.CHEST, m.getOrDefault(KitSpec.CHEST, ItemStack.EMPTY).copy());
        body.equipStack(EquipmentSlot.LEGS, m.getOrDefault(KitSpec.LEGS, ItemStack.EMPTY).copy());
        body.equipStack(EquipmentSlot.FEET, m.getOrDefault(KitSpec.FEET, ItemStack.EMPTY).copy());
        w.spawnEntity(body);
        world.session().track(body);
        alive = true;
        nextActionAt = world.ticks() + 20;
        syncHands();
        nameTag();
    }

    public void despawnBody() {
        if (mining != null && body != null) w.setBlockBreakingInfo(body.getId(), mining, -1);
        if (body != null && !body.isRemoved()) body.discard();
        if (myCrystal != null && !myCrystal.isRemoved()) myCrystal.discard();
        if (pearl != null && !pearl.isRemoved()) pearl.discard();
        myCrystal = null;
        pearl = null;
        mining = null;
    }

    // =========================================================================== inventory

    private int firstHotbar(Predicate<ItemStack> p) {
        for (int i = 0; i < 9; i++) if (!inv[i].isEmpty() && p.test(inv[i])) return i;
        return -1;
    }

    private int first(Predicate<ItemStack> p) {
        for (int i = 0; i < 36; i++) if (!inv[i].isEmpty() && p.test(inv[i])) return i;
        return -1;
    }

    private boolean has(Predicate<ItemStack> p) {
        for (ItemStack st : inv) if (!st.isEmpty() && p.test(st)) return true;
        return false;
    }

    private int count(Predicate<ItemStack> p) {
        int n = 0;
        for (ItemStack st : inv) if (!st.isEmpty() && p.test(st)) n += st.getCount();
        return n;
    }

    private ItemStack held() { return inv[selected]; }

    /**
     * Gets an item into the main hand. True when it is there now; false while the switch is under
     * way (a hotbar switch takes a tick below Pro; pulling it out of the inventory takes the
     * level's refill time).
     */
    private boolean hold(Predicate<ItemStack> p) {
        if (!held().isEmpty() && p.test(held())) return true;
        int ticks = bw.ticks();
        if (ticks < busyUntil) return false;
        int h = firstHotbar(p);
        if (h >= 0) {
            selected = h;
            syncHands();
            if (level.atLeast(BotLevel.PRO)) return true;
            busyUntil = ticks + 1;
            return false;
        }
        int i = -1;
        for (int k = 9; k < 36 && i < 0; k++) if (!inv[k].isEmpty() && p.test(inv[k])) i = k;
        if (i < 0) return false;
        int slot = -1;
        for (int k = 0; k < 9 && slot < 0; k++) if (inv[k].isEmpty()) slot = k;
        if (slot < 0) slot = selected;
        ItemStack tmp = inv[slot];
        inv[slot] = inv[i];
        inv[i] = tmp;
        selected = slot;
        busyUntil = ticks + level.refillTicks;
        lastAction = "refills " + inv[slot].getName().getString();
        syncHands();
        return false;
    }

    private void consumeHeld() {
        if (!held().isEmpty()) held().decrement(1);
        syncHands();
    }

    private void syncHands() {
        if (body == null) return;
        ItemStack main = inv[selected];
        if (!ItemStack.areEqual(body.getMainHandStack(), main)) body.equipStack(EquipmentSlot.MAINHAND, main.copy());
        if (!ItemStack.areEqual(body.getOffHandStack(), offhand)) body.equipStack(EquipmentSlot.OFFHAND, offhand.copy());
    }

    private static boolean isWeapon(ItemStack st) { return st.isIn(ItemTags.SWORDS) || st.isIn(ItemTags.AXES) || st.isOf(Items.MACE); }

    private int enchLevel(ItemStack st, RegistryKey<Enchantment> key) {
        var reg = w.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        return reg.getOptional(key).map(e -> st.getEnchantments().getLevel(e)).orElse(0);
    }

    // =========================================================================== head

    private Vec3d eye() { return pos.add(0, 1.62, 0); }

    /** Turns the head toward the look target at the level's speed (not while re-totem-ing). */
    private void turnHead() {
        if (lookTarget == null || bw.ticks() < headLockUntil) return;
        Vec3d d = lookTarget.subtract(eye());
        float wantYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.max(0.01, Math.sqrt(d.x * d.x + d.z * d.z))));
        float dy = MathHelper.wrapDegrees(wantYaw - yaw);
        float max = level.turnSpeed;
        yaw += MathHelper.clamp(dy, -max, max);
        pitch = MathHelper.clamp(pitch + MathHelper.clamp(wantPitch - pitch, -max, max), -90f, 90f);
    }

    /** True once the head points at p (within 9 degrees); p becomes what it looks at. */
    private boolean aimed(Vec3d p) {
        lookTarget = p;
        lookSetAt = bw.ticks();
        lastAim = p;
        Vec3d d = p.subtract(eye());
        float wantYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.max(0.01, Math.sqrt(d.x * d.x + d.z * d.z))));
        return Math.abs(MathHelper.wrapDegrees(wantYaw - yaw)) < 9 && Math.abs(wantPitch - pitch) < 9;
    }

    // =========================================================================== tick

    public void tick() {
        if (!alive()) return;
        int t = bw.ticks();
        tickEffects(t);
        tickPearl();
        if (retotemAt >= 0 && t >= retotemAt) retotem();
        walk = Vec3d.ZERO;
        moveDir = Vec3d.ZERO;
        wantJump = false;
        chooseTarget(t);
        LivingEntity tgt = target == null ? null : target.entity();
        if (tgt != null) lookTarget = tgt.getEyePos().add(0, -0.3, 0);
        if (tgt != null && t % 2 == 0) watchPearls(t);
        if (tgt != null) think(t, tgt);
        planMovement(t, tgt);
        followPath(t, tgt);
        lookWhereRunning(t, tgt);
        turnHead();
        physics();
        if (alive() && t % 4 == 0) nameTag();
    }

    private void tickEffects(int t) {
        if (hurtTicks > 0) hurtTicks--;
        if (absorption > 0 && t > absorptionUntil) absorption = 0;
        if (t < regenUntil && t % regenEvery == 0) hp = Math.min(20f, hp + 1f);
        if (t - lastDamagedAt > 80 && t % 40 == 0) hp = Math.min(20f, hp + 1f); // natural regeneration, out of combat
        if (body.isOnFire() && t > fireResUntil && t % 20 == 0) applyDamage(1f);
        if (body.isOnFire() && t <= fireResUntil) body.extinguish();
    }

    /** Hearts above its head: red health, gold absorption, then health number and totems left. */
    private void nameTag() {
        Formatting c = team == 0 ? Formatting.GREEN : team == 1 ? Formatting.RED : Formatting.values()[(team * 3) % 14 + 1];
        MutableText t = level.cheats() ? Text.literal("[HACKER] ").formatted(Formatting.DARK_RED, Formatting.BOLD) : Text.literal("");
        t.append(Text.literal(name + " ").formatted(c));
        if (showLevel) t.append(Text.literal("[" + level.title + "] ").formatted(Formatting.GRAY));
        int full = Math.min(10, (int) Math.ceil(hp / 2f));
        int gold = Math.min(10, (int) Math.ceil(absorption / 2f));
        t.append(Text.literal("❤".repeat(Math.max(0, full))).formatted(Formatting.RED));
        if (full < 10) t.append(Text.literal("❤".repeat(10 - full)).formatted(Formatting.DARK_GRAY));
        if (gold > 0) t.append(Text.literal("❤".repeat(gold)).formatted(Formatting.GOLD));
        t.append(Text.literal(String.format(Locale.ROOT, " %.0f  %dT", hp + absorption, totemCount())).formatted(Formatting.GRAY));
        body.setCustomName(t);
    }

    // =========================================================================== targeting

    private List<Fighter> enemies() {
        List<Fighter> out = new ArrayList<>();
        for (Fighter f : bw.fighters()) if (f != self && f.team != team && f.alive()) out.add(f);
        return out;
    }

    private List<Fighter> allies() {
        List<Fighter> out = new ArrayList<>();
        for (Fighter f : bw.fighters()) if (f != self && f.team == team && f.alive()) out.add(f);
        return out;
    }

    private double nearestEnemy() {
        double d = 999;
        for (Fighter f : enemies()) d = Math.min(d, f.entity().getEntityPos().distanceTo(pos));
        return d;
    }

    private void chooseTarget(int t) {
        if (target != null && target.alive() && t < targetUntil) return;
        Fighter best = null;
        double bestScore = Double.MAX_VALUE;
        for (Fighter f : enemies()) {
            double score = f.entity().getEntityPos().distanceTo(pos) + f.health() * 0.25;
            for (Fighter a : allies()) if (a.bot != null && a.bot.target == f) score -= 4; // focus fire
            if (score < bestScore) {
                bestScore = score;
                best = f;
            }
        }
        target = best;
        targetUntil = t + 40;
    }

    // =========================================================================== the brain

    private void think(int t, LivingEntity tgt) {
        double dist = tgt.getEntityPos().distanceTo(pos);
        if (eatingUntil >= 0) {
            if (t >= eatingUntil) finishEating(t);
            return;
        }
        if (retotemAt >= 0) return; // hands busy with the totem
        if (shieldUntil >= 0 && t >= shieldUntil) lowerShield();
        if (air != Air.NONE) {
            aerial(t, tgt);
            return;
        }
        if (mining != null) {
            mineTick(t);
            return;
        }
        if (pearlAimUntil >= 0) {
            pearlStep(t);
            return;
        }
        if (t < nextActionAt || t < busyUntil) return;

        // ---- sequences already under way
        if (myCrystal != null) {
            if (myCrystal.isRemoved()) myCrystal = null;
            else {
                breakMyCrystal(t, tgt);
                return;
            }
        }
        if (anchorStage > 0) {
            anchorStep(t);
            return;
        }
        if (buildThenCrystal != null) {
            BlockPos b = buildThenCrystal;
            Vec3d click = clickFace(b, null);
            if (!w.getBlockState(b).isOf(Blocks.OBSIDIAN) || !w.getBlockState(b.up()).isAir() || click == null) buildThenCrystal = null;
            else if (hold(st -> st.isOf(Items.END_CRYSTAL)) && aimed(click)) {
                buildThenCrystal = null;
                placeCrystal(b, t);
            }
            return;
        }
        // double tap: the next crystal goes straight back on the spot that just blew
        if (dtapSpot != null) {
            BlockPos b = dtapSpot;
            Vec3d click = clickFace(b, null);
            if (t > dtapUntil || click == null || !crystalBase(b)) {
                dtapSpot = null;
            } else {
                if (hold(st -> st.isOf(Items.END_CRYSTAL)) && aimed(click)) {
                    dtapSpot = null;
                    placeCrystal(b, t);
                    dtaps++;
                    lastAction = "double-taps";
                }
                return;
            }
        }
        // ---- their crystals: break the ones that hurt them more, dodge the ones that only hurt us
        if (t >= nextThreatCheck && reactToCrystals(t, tgt)) return;
        float me = health();
        // ---- survival
        if (!offhand.isOf(Items.TOTEM_OF_UNDYING) && has(st -> st.isOf(Items.TOTEM_OF_UNDYING)) && (me < 12 || explosives())) {
            startRetotem(t);
            return;
        }
        if (explosives() && !personality.avoidsHoles && me < personality.surroundAt && !surrounded && onGround && dist < 8
                && t >= surroundGiveUpUntil && count(st -> st.isOf(Items.OBSIDIAN)) >= 4 && canSurround()) {
            if (hold(st -> st.isOf(Items.OBSIDIAN))) surround(t);
            return;
        }
        if (surrounded && (me > (personality == Personality.HOLE_CAMPER ? 19 : 15) || dist > 9)) surrounded = false;
        float pearlOutAt = personality == Personality.CAREFUL ? 9 : personality == Personality.RUSHER ? 4 : 7;
        if (me < pearlOutAt && t >= nextPearlAt && dist < 6 && has(st -> st.isOf(Items.ENDER_PEARL)) && pearlAway(t, tgt)) return;
        boolean gapple = has(st -> st.isOf(Items.GOLDEN_APPLE) || st.isOf(Items.ENCHANTED_GOLDEN_APPLE));
        if (gapple && absorption <= 0 && (me < 16 || dist > 8) && (dist > 4.5 || me < 8 || surrounded)) {
            if (hold(st -> st.isOf(Items.GOLDEN_APPLE) || st.isOf(Items.ENCHANTED_GOLDEN_APPLE))) startEating(t);
            return;
        }
        // golden hearts gone and getting low: step back out of crystal range, then eat
        float backOffAt = personality == Personality.RUSHER ? 8 : personality == Personality.CAREFUL ? 17 : 14;
        if (gapple && absorption <= 0 && me < backOffAt && dist <= 4.5 && t >= retreatUntil) retreatUntil = t + 40;
        if (armourLow() && has(st -> st.isOf(Items.EXPERIENCE_BOTTLE))) {
            if (nearestEnemy() > 9) {
                if (hold(st -> st.isOf(Items.EXPERIENCE_BOTTLE)) && aimed(pos.add(0, 0.1, 0))) mend(t);
                return;
            }
            retreatUntil = t + 80; // run off to mend
            if (dist < 4 && t >= nextPearlAt && has(st -> st.isOf(Items.ENDER_PEARL)) && pearlAway(t, tgt)) return;
        }
        if (t < retreatUntil && dist < 7) return;
        // ---- offence
        if (aerialOn() && onGround && dist > 4 && dist < 18 && t >= nextSwingAt && startAerial(t, dist)) return;
        // a swordsman swings first and keeps explosives for the finish
        if (meleeOn() && personality.meleeBias > 3 && melee(t, tgt, dist)) return;
        if (explosives()) {
            // hit-crystal: a sword hit first when they are on top of us knocks them up into the crystal
            if (level.knows(BotLevel.Tech.HIT_CRYSTAL) && has(PvpBot::isWeapon) && dist <= 3.0 && t >= nextSwingAt && t >= comboUntil && melee(t, tgt, dist)) {
                comboUntil = t + 12;
                return;
            }
            if (offenceExplosive(t, tgt)) return;
            if ((level.knows(BotLevel.Tech.CITY_MINE) || personality.cities) && cityMine(tgt)) return;
        }
        if (meleeOn() && melee(t, tgt, dist)) return;
        // chase with a pearl when far away and walking won't do
        if (dist > 16 && t >= nextPearlAt && has(st -> st.isOf(Items.ENDER_PEARL)) && pearlIn(t, tgt)) return;
        nextActionAt = t + 2;
    }

    // =========================================================================== movement

    /** Where to stand: fight range, away (to heal or mend), out of a crystal's line, or stay (hole). */
    private void planMovement(int t, LivingEntity tgt) {
        if (surrounded || mining != null) {
            goal = Goal.HOLD;
            return;
        }
        if (goal == Goal.DODGE && t < goalUntil) return;
        if (t < retreatUntil) {
            if (goal != Goal.RETREAT || t >= goalUntil) {
                goal = Goal.RETREAT;
                goalPos = farPoint();
                goalUntil = t + 40;
            }
            return;
        }
        goal = Goal.FIGHT;
        if (tgt == null) {
            goalPos = null;
            return;
        }
        Vec3d tp = tgt.getEntityPos();
        double want;
        boolean melee = meleeOn() && personality.meleeBias >= 0;
        if (explosives() && !melee) want = 3.6;
        else if (explosives()) want = t < nextSwingAt && personality.meleeBias < 3 ? 3.6 : 2.4;
        else if (can(FreeRoamConfig.Ability.MACE) && !can(FreeRoamConfig.Ability.SWORD) && t < nextSwingAt) want = 6.5;
        else want = 2.2;
        want = Math.max(1.8, want + personality.spacing);
        if (t >= strafeUntil) {
            strafeUntil = t + 15 + bw.rng().nextInt(30);
            strafeSign = bw.rng().nextInt(5) == 0 ? 0 : bw.rng().nextBoolean() ? 1 : -1;
        }
        Vec3d away = new Vec3d(pos.x - tp.x, 0, pos.z - tp.z);
        if (away.lengthSquared() < 1e-4) away = new Vec3d(1, 0, 0);
        double ang = Math.atan2(away.z, away.x) + strafeSign * 0.5;
        Vec3d spot = tp.add(Math.cos(ang) * want, 0, Math.sin(ang) * want);
        BlockPos g = Pathfinder.ground(w, BlockPos.ofFloored(spot.x, Math.max(spot.y, pos.y) + 1, spot.z));
        // some players never step into a hole: pick the other side instead
        if (g != null && personality.avoidsHoles && isHole(g)) {
            double ang2 = ang + Math.PI * 0.6;
            BlockPos g2 = Pathfinder.ground(w, BlockPos.ofFloored(tp.x + Math.cos(ang2) * want, Math.max(spot.y, pos.y) + 1, tp.z + Math.sin(ang2) * want));
            if (g2 != null && !isHole(g2)) g = g2;
        }
        goalPos = g != null ? g : Pathfinder.ground(w, tgt.getBlockPos().up());
        if (goalPos == null) goalPos = tgt.getBlockPos(); // they're in the air or off an edge: head their way anyway
    }

    /** Three or four solid sides at feet level. */
    private boolean isHole(BlockPos feet) {
        int n = 0;
        for (Direction d : Direction.Type.HORIZONTAL) if (Pathfinder.solid(w, feet.offset(d))) n++;
        return n >= 3;
    }

    /** The standable spot farthest from every enemy, of a few samples. */
    private BlockPos farPoint() {
        BlockPos c = bw.center();
        int r = bw.radius() - 3;
        BlockPos best = BlockPos.ofFloored(pos);
        double bestD = -1;
        for (int i = 0; i < 16; i++) {
            BlockPos p = Pathfinder.ground(w, c.add(bw.rng().nextInt(2 * r) - r, 6, bw.rng().nextInt(2 * r) - r));
            if (p == null) continue;
            double d = 999;
            for (Fighter f : enemies()) d = Math.min(d, f.entity().getEntityPos().distanceTo(Vec3d.ofBottomCenter(p)));
            d -= Vec3d.ofBottomCenter(p).distanceTo(pos) * 0.2;
            if (d > bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Walks the A* path to the goal; recomputes when the goal moves, or every second. */
    private void followPath(int t, LivingEntity tgt) {
        if (pearlAimUntil >= 0) return; // standing still to aim a pearl, like a player
        if (goal == Goal.HOLD || goalPos == null || air != Air.NONE && air != Air.WIND_UP) return;
        BlockPos feet = BlockPos.ofFloored(pos.x, pos.y + 0.01, pos.z);
        if (path == null || pathGoal == null || pathGoal.getSquaredDistance(goalPos) > 2.5 || t - pathAt > 20 || pathIndex >= path.size()) {
            path = Pathfinder.find(w, feet, goalPos, 900, bw.center(), bw.radius(), personality.avoidsHoles ? this::isHole : null);
            pathIndex = 0;
            pathAt = t;
            pathGoal = goalPos;
        }
        if (path == null || path.isEmpty()) {
            // no path (on a tree, a ledge...): walk straight at it and drop down if that's the way
            Vec3d straight = new Vec3d(goalPos.getX() + 0.5 - pos.x, 0, goalPos.getZ() + 0.5 - pos.z);
            if (straight.lengthSquared() > 4) {
                moveDir = straight.normalize();
                moveLeft = straight.length();
                wantSprint = true;
            }
            lastPos = pos;
            // no way there from here (walled in): that is stuck too - mine or pearl out
            if (goalPos.getSquaredDistance(feet) > 6 && onGround && ++stuckTicks > 25) {
                stuckTicks = 0;
                unstuck(t, Vec3d.ofBottomCenter(goalPos));
            }
            return;
        }
        BlockPos wp = path.get(Math.min(pathIndex, path.size() - 1));
        Vec3d c = Vec3d.ofBottomCenter(wp);
        Vec3d d = new Vec3d(c.x - pos.x, 0, c.z - pos.z);
        if (d.length() < 0.35 && Math.abs(wp.getY() - pos.y) < 0.6) {
            pathIndex++;
            if (pathIndex >= path.size()) return;
            wp = path.get(pathIndex);
            c = Vec3d.ofBottomCenter(wp);
            d = new Vec3d(c.x - pos.x, 0, c.z - pos.z);
        }
        if (d.lengthSquared() > 1e-6) {
            moveDir = d.normalize();
            moveLeft = d.length();
        }
        wantSprint = goal == Goal.RETREAT || d.length() > 2 || tgt != null && tgt.getEntityPos().distanceTo(pos) > 4;
        if (wp.getY() > pos.y + 0.5 && onGround && d.length() < 1.3) wantJump = true;
        // crit jumps in melee
        if (onGround && meleeOn() && tgt != null && tgt.getEntityPos().distanceTo(pos) < 3.6 && t + 5 >= nextSwingAt) wantJump = true;
        // stuck: mine out or pearl out - never jump on the spot forever
        if (lastPos.squaredDistanceTo(pos) < 0.0004 && moveDir.lengthSquared() > 1e-4) stuckTicks++;
        else stuckTicks = 0;
        lastPos = pos;
        if (stuckTicks > 25 && eatingUntil < 0) {
            stuckTicks = 0;
            unstuck(t, c);
        }
    }

    private void unstuck(int t, Vec3d toward) {
        // hands busy (eating, re-totem-ing, mid-combo): don't drop what you're doing
        if (eatingUntil >= 0 || retotemAt >= 0 || anchorStage > 0 || myCrystal != null || t < busyUntil) return;
        BlockPos feet = BlockPos.ofFloored(pos);
        Vec3d d = toward.subtract(pos);
        Direction dir = Direction.getFacing(d.x, 0, d.z);
        for (BlockPos b : new BlockPos[] {feet.offset(dir).up(), feet.offset(dir)}) {
            if (Pathfinder.solid(w, b) && !bw.session().isProtected(b) && w.getBlockState(b).getHardness(w, b) >= 0 && has(s -> s.isIn(ItemTags.PICKAXES))) {
                mining = b.toImmutable();
                mineProgress = 0;
                lastAction = "mines out";
                return;
            }
        }
        if (t >= nextPearlAt && has(s -> s.isOf(Items.ENDER_PEARL))) {
            BlockPos g = Pathfinder.ground(w, BlockPos.ofFloored(toward).up(2));
            if (g != null) queuePearl(t, Vec3d.ofBottomCenter(g), "pearls out");
        }
        path = null;
    }

    /**
     * Running somewhere (away, or a long way to the fight): it looks where it runs - so it can
     * sprint - with a glance back now and then. Fighting up close it keeps its eyes on you.
     */
    private void lookWhereRunning(int t, LivingEntity tgt) {
        if (lookSetAt == t || moveDir.lengthSquared() < 1e-6 || air != Air.NONE || pearlAimUntil >= 0 || retotemAt >= 0) return;
        double dist = tgt == null ? 99 : tgt.getEntityPos().distanceTo(pos);
        boolean running = goal == Goal.RETREAT || dist > 9 || tgt == null;
        if (!running) return;
        if (goal == Goal.RETREAT && tgt != null && t % 40 < 7) return; // a look back
        lookTarget = eye().add(moveDir.x * 6, -1.2, moveDir.z * 6);
    }

    // =========================================================================== reacting to their crystals

    /**
     * Every crystal near it that isn't its own: if blowing it up now hurts the target at least as
     * much as itself, it hits it; if it would only hurt itself, it steps to the nearby spot the
     * blast can't reach.
     */
    private boolean reactToCrystals(int t, LivingEntity tgt) {
        nextThreatCheck = t + 2;
        Scene.Armour mine = Scene.armourOf(w, body);
        Scene.Armour theirs = Scene.armourOf(w, tgt);
        EndCrystalEntity worst = null;
        float worstDmg = 3f;
        for (EndCrystalEntity c : w.getEntitiesByClass(EndCrystalEntity.class, body.getBoundingBox().expand(7), x -> x != myCrystal)) {
            Vec3d center = c.getEntityPos();
            float toMe = Scene.damage(center, CombatMath.CRYSTAL_POWER, body, mine);
            if (toMe < 3f) continue;
            float toThem = Scene.damage(center, CombatMath.CRYSTAL_POWER, tgt, theirs);
            if (canHit(c) && (toThem >= toMe * 0.9f || toThem >= tgt.getHealth() + tgt.getAbsorptionAmount())) {
                if (!aimed(c.getBoundingBox().getCenter())) return true;
                body.swingHand(Hand.MAIN_HAND);
                noteHit(c);
                c.damage(w, w.getDamageSources().mobAttack(body), 1f);
                enemyCrystalsBroken++;
                lastAction = "breaks your crystal on you";
                nextActionAt = t + Math.max(1, reactionTicks / 2);
                return true;
            }
            if (toMe > worstDmg) {
                worstDmg = toMe;
                worst = c;
            }
        }
        // a charged anchor of theirs next to us is a bomb about to go off: get away from it too
        Vec3d threat = worst == null ? null : worst.getEntityPos();
        float threatPower = CombatMath.CRYSTAL_POWER;
        BlockPos feet0 = BlockPos.ofFloored(pos);
        for (BlockPos b : BlockPos.iterate(feet0.add(-4, -2, -4), feet0.add(4, 3, 4))) {
            BlockState st = w.getBlockState(b);
            if (!st.isOf(Blocks.RESPAWN_ANCHOR) || st.get(RespawnAnchorBlock.CHARGES) == 0 || b.equals(anchorAt)) continue;
            float toMe = Scene.damage(Vec3d.ofCenter(b), CombatMath.ANCHOR_POWER, body, mine);
            if (toMe > worstDmg) {
                worstDmg = toMe;
                threat = Vec3d.ofCenter(b);
                threatPower = CombatMath.ANCHOR_POWER;
            }
        }
        if (threat != null && !surrounded) {
            BlockPos feet = BlockPos.ofFloored(pos);
            BlockPos best = null;
            float bestDmg = worstDmg - 1.5f;
            for (BlockPos b : BlockPos.iterate(feet.add(-2, -1, -2), feet.add(2, 1, 2))) {
                if (!Pathfinder.standable(w, b)) continue;
                float d = Scene.damageAt(w, threat, threatPower, Vec3d.ofBottomCenter(b), body, mine);
                if (d < bestDmg) {
                    bestDmg = d;
                    best = b.toImmutable();
                }
            }
            if (best != null) {
                goal = Goal.DODGE;
                goalPos = best;
                goalUntil = t + Math.max(8, reactionTicks * 2);
                path = null;
                lastAction = threatPower == CombatMath.ANCHOR_POWER ? "backs off your anchor" : "dodges a crystal";
            }
        }
        return false;
    }

    // =========================================================================== crystals and anchors

    private record Plan(BlockPos block, boolean build, boolean anchor, float score, Vec3d click) {
    }

    private boolean offenceExplosive(int t, LivingEntity tgt) {
        Plan p = pending != null && t < pendingUntil && stillValid(pending) ? pending : null;
        if (p == null) {
            p = plan(tgt);
            pending = p;
            pendingUntil = t + 6;
        }
        if (p == null) return false;
        if (p.click.distanceTo(eye()) > level.blockReach()) {
            pending = null; // it moved out of reach while lining up
            return false;
        }
        if (p.anchor) {
            if (!hold(st -> st.isOf(Items.RESPAWN_ANCHOR))) return true;
            if (!aimed(p.click)) return true;
            pending = null;
            anchorDtaps = 0;
            placeAnchor(p.block, t);
            return true;
        }
        if (p.build) {
            if (!hold(st -> st.isOf(Items.OBSIDIAN))) return true;
            if (!aimed(p.click)) return true;
            pending = null;
            noteClick();
            w.setBlockState(p.block, Blocks.OBSIDIAN.getDefaultState(), 3);
            body.swingHand(Hand.MAIN_HAND);
            consumeHeld();
            w.playSound(null, p.block, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 1f, 1f);
            buildThenCrystal = p.block;
            nextActionAt = t + step();
            return true;
        }
        if (!hold(st -> st.isOf(Items.END_CRYSTAL))) return true;
        if (!aimed(p.click)) return true;
        pending = null;
        placeCrystal(p.block, t);
        return true;
    }

    /** A plan picked a few ticks ago that can still be done (nothing moved into the spot). */
    private boolean stillValid(Plan p) {
        if (p.anchor || p.build) return w.getBlockState(p.block).isReplaceable() && !hitsAnyone(new Box(p.block)) && placeClick(p.block) != null;
        return crystalBase(p.block) && clickFace(p.block, null) != null;
    }

    /** Obsidian or bedrock with air above and nothing in the way: a crystal can go here. */
    private boolean crystalBase(BlockPos b) {
        BlockState st = w.getBlockState(b);
        if (!st.isOf(Blocks.OBSIDIAN) && !st.isOf(Blocks.BEDROCK)) return false;
        if (!w.getBlockState(b.up()).isAir()) return false;
        Box cb = Scene.crystalBox(b);
        return !hitsAnyone(cb) && w.getEntitiesByClass(EndCrystalEntity.class, cb, x -> true).isEmpty();
    }

    /** Ticks between the steps of one combo (place -> break, anchor -> charge -> blow). */
    private int step() { return Math.max(1, reactionTicks / 3); }

    private void noteClick() {
        if (lastAim != null) maxClickReach = Math.max(maxClickReach, lastAim.distanceTo(eye()));
    }

    private void noteHit(Entity e) {
        maxHitReach = Math.max(maxHitReach, Math.sqrt(e.getBoundingBox().squaredMagnitude(eye())));
    }

    /** GameTests: line up a pearl to land at land. */
    public boolean pearlToForTests(Vec3d land) { return queuePearl(bw.ticks(), land, "test pearl"); }

    private void placeAnchor(BlockPos b, int t) {
        noteClick();
        w.setBlockState(b, Blocks.RESPAWN_ANCHOR.getDefaultState(), 3);
        body.swingHand(Hand.MAIN_HAND);
        consumeHeld();
        w.playSound(null, b, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 1f, 1f);
        anchorAt = b;
        anchorStage = 1;
        nextActionAt = t + step();
        lastAction = "anchors";
    }

    private void placeCrystal(BlockPos b, int t) {
        if (!held().isOf(Items.END_CRYSTAL)) return;
        noteClick();
        EndCrystalEntity c = EntityType.END_CRYSTAL.create(w, SpawnReason.COMMAND);
        if (c == null) return;
        c.refreshPositionAndAngles(b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5, 0f, 0f);
        c.setShowBottom(false);
        w.spawnEntity(c);
        body.swingHand(Hand.MAIN_HAND);
        consumeHeld();
        myCrystal = c;
        myCrystalAt = t;
        myCrystalBase = b;
        crystalsPlaced++;
        nextActionAt = t + 1;
        lastAction = "crystals";
        // the fastest players place and break in the same tick (when the hit would count)
        LivingEntity tgt = target == null ? null : target.entity();
        if (level.knows(BotLevel.Tech.INSTA_BREAK) && tgt != null && tgt.timeUntilRegen <= 10 && canHit(c)) {
            body.swingHand(Hand.MAIN_HAND);
            c.damage(w, w.getDamageSources().mobAttack(body), 1f);
            myCrystal = null;
            crystalsBroken++;
            afterBlast(b, t);
        }
    }

    private void breakMyCrystal(int t, LivingEntity tgt) {
        int wait = Math.max(1, reactionTicks / 2);
        // time the break for the end of the target's hurt window: inside it only the difference lands
        if (level.knows(BotLevel.Tech.HURT_TIMING) && tgt.timeUntilRegen > 10 && t - myCrystalAt < wait + 10) return;
        if (t - myCrystalAt < wait) return;
        if (!canHit(myCrystal)) {
            if (t - myCrystalAt > 40) myCrystal = null; // walked away from it
            return;
        }
        if (!aimed(myCrystal.getBoundingBox().getCenter())) return;
        body.swingHand(Hand.MAIN_HAND);
        noteHit(myCrystal);
        myCrystal.damage(w, w.getDamageSources().mobAttack(body), 1f);
        myCrystal = null;
        crystalsBroken++;
        nextActionAt = t + step();
        afterBlast(myCrystalBase, t);
    }

    /** Pro players put the next crystal straight back on the same block (a double tap). */
    private void afterBlast(BlockPos base, int t) {
        if (base != null && level.knows(BotLevel.Tech.CRYSTAL_DTAP) && personality != Personality.SWORDSMAN) {
            dtapSpot = base;
            dtapUntil = t + 14;
            nextActionAt = t + 1;
        }
    }

    private void anchorStep(int t) {
        BlockState st = w.getBlockState(anchorAt);
        if (!st.isOf(Blocks.RESPAWN_ANCHOR)) {
            anchorStage = 0;
            return;
        }
        Vec3d click = clickFace(anchorAt, null);
        if (click == null) {
            anchorStage = 0; // can't see or reach it any more
            return;
        }
        if (!aimed(click)) return;
        if (anchorStage == 1) {
            if (!hold(s -> s.isOf(Items.GLOWSTONE))) return;
            w.setBlockState(anchorAt, st.with(RespawnAnchorBlock.CHARGES, 1), 3);
            consumeHeld();
            body.swingHand(Hand.MAIN_HAND);
            w.playSound(null, anchorAt, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 1f, 1f);
            anchorStage = 2;
            nextActionAt = t + step();
            return;
        }
        // detonate with anything that isn't glowstone: what an anchor does outside the Nether
        if (held().isOf(Items.GLOWSTONE) && !hold(s -> !s.isOf(Items.GLOWSTONE))) return;
        BlockPos p = anchorAt;
        anchorStage = 0;
        w.removeBlock(p, false);
        Vec3d c = Vec3d.ofCenter(p);
        body.swingHand(Hand.MAIN_HAND);
        w.createExplosion(null, w.getDamageSources().explosion(body, body), null, c.x, c.y, c.z, CombatMath.ANCHOR_POWER, true, World.ExplosionSourceType.BLOCK);
        anchorsBlown++;
        nextActionAt = t + step();
        // anchor double tap: the spot it was in can be clicked again right away - a new anchor in the air
        if (level.knows(BotLevel.Tech.ANCHOR_DTAP) && anchorDtaps == 0 && personality != Personality.CRYSTAL_SPAMMER
                && w.getBlockState(p).isReplaceable() && !hitsAnyone(new Box(p))
                && has(s -> s.isOf(Items.GLOWSTONE)) && hold(s -> s.isOf(Items.RESPAWN_ANCHOR))) {
            anchorDtaps++;
            anchorDtapsDone++;
            placeAnchor(p, t);
            nextActionAt = t + 1;
            lastAction = "anchor double-taps";
        }
    }

    /**
     * The best crystal or anchor right now: damage to enemies (the target counts most) minus damage
     * to itself and teammates, real exposure, aimed at where the target will be after its reaction
     * time. Only spots a player could really click: in reach, a face it can see. Branch and bound
     * keeps it cheap; lower levels pick a random good spot instead of the best.
     */
    private Plan plan(LivingEntity tgt) {
        Vec3d eye = eye();
        Map<LivingEntity, Scene.Armour> arm = new HashMap<>();
        List<LivingEntity> foes = new ArrayList<>();
        List<LivingEntity> friends = new ArrayList<>();
        for (Fighter f : enemies()) foes.add(f.entity());
        for (Fighter f : allies()) friends.add(f.entity());
        // where they'll be after our reaction time: horizontal movement only (a player standing on
        // the ground still has a little downward velocity from gravity - that's not "moving into the floor")
        Vec3d v = tgt.getVelocity();
        Vec3d flat = new Vec3d(v.x, tgt.isOnGround() ? 0 : v.y, v.z);
        Vec3d predicted = flat.horizontalLengthSquared() < 0.0009 && tgt.isOnGround() ? tgt.getEntityPos()
                : tgt.getEntityPos().add(flat.multiply(Math.min(reactionTicks, 6)));
        BlockPos pf = BlockPos.ofFloored(predicted);
        if (Pathfinder.solid(w, pf)) predicted = new Vec3d(predicted.x, pf.getY() + 1, predicted.z);
        float lethal = tgt.getHealth() + tgt.getAbsorptionAmount();
        Fighter tf = bw.fighterOf(tgt);
        if (tf != null && tf.bot != null) lethal = tf.bot.health();
        float myHp = health();
        Scene.Armour mine = Scene.armourOf(w, body);
        Scene.Armour theirs = arm.computeIfAbsent(tgt, e -> Scene.armourOf(w, e));
        boolean anchors = can(FreeRoamConfig.Ability.ANCHORS) && has(s -> s.isOf(Items.RESPAWN_ANCHOR)) && has(s -> s.isOf(Items.GLOWSTONE));
        boolean crystals = can(FreeRoamConfig.Ability.CRYSTALS) && has(s -> s.isOf(Items.END_CRYSTAL));
        boolean obsidian = has(s -> s.isOf(Items.OBSIDIAN));
        boolean facePlace = level.knows(BotLevel.Tech.FACE_PLACE);
        int tFeet = tgt.getBlockPos().getY();
        double reach = level.blockReach();
        double hitReach = level.hitReach();
        Box mb = body.getBoundingBox();
        record Cand(BlockPos block, boolean build, boolean anchor, Vec3d center, float power, float bound) {
        }
        List<Cand> cands = new ArrayList<>();
        BlockPos c0 = BlockPos.ofFloored(pos);
        int span = (int) Math.ceil(reach);
        for (BlockPos b : BlockPos.iterate(c0.add(-span, -3, -span), c0.add(span, 3, span))) {
            if (Scene.reachTo(eye, b) > reach) continue;
            if (Vec3d.ofCenter(b).distanceTo(predicted) > 8) continue;
            if (bw.session().isProtected(b)) continue;
            BlockState st = w.getBlockState(b);
            boolean ready = st.isOf(Blocks.OBSIDIAN) || st.isOf(Blocks.BEDROCK);
            boolean air = st.isAir() || st.isReplaceable();
            boolean support = air && supported(b);
            BlockPos bi = b.toImmutable();
            // face-placing (a crystal up at their head) is a skill of its own
            if (crystals && (facePlace || b.getY() < tFeet) && (ready || support && obsidian && !new Box(b).intersects(mb) && !hitsAnyone(new Box(b)))
                    && w.getBlockState(b.up()).isAir()) {
                Box cb = Scene.crystalBox(b);
                Vec3d center = Scene.crystalCenter(b);
                Box hitbox = new Box(b.getX() - 0.5, b.getY() + 1, b.getZ() - 0.5, b.getX() + 1.5, b.getY() + 3, b.getZ() + 1.5);
                if (Math.sqrt(hitbox.squaredMagnitude(eye)) <= hitReach && !hitsAnyone(cb) && w.getEntitiesByClass(EndCrystalEntity.class, cb, x -> true).isEmpty()) {
                    float bound = CombatMath.effective(CombatMath.rawExplosion(center.distanceTo(predicted), 6f, 1.0),
                            theirs.difficulty(), theirs.armor(), theirs.toughness(), theirs.blastEpf());
                    if (bound >= 0.5f) cands.add(new Cand(bi, !ready, false, center, CombatMath.CRYSTAL_POWER, bound));
                }
            }
            if (anchors && support && !new Box(b).intersects(mb) && !hitsAnyone(new Box(b))) {
                Vec3d center = Vec3d.ofCenter(b);
                float bound = CombatMath.effective(CombatMath.rawExplosion(center.distanceTo(predicted), 5f, 1.0),
                        theirs.difficulty(), theirs.armor(), theirs.toughness(), theirs.blastEpf());
                if (bound >= 0.5f) cands.add(new Cand(bi, false, true, center, CombatMath.ANCHOR_POWER, bound));
            }
        }
        cands.sort(Comparator.comparingDouble((Cand c) -> -c.bound));
        List<Plan> plans = new ArrayList<>();
        int evaluated = 0;
        int noClick = 0;
        int noScore = 0;
        for (Cand c : cands) {
            if (plans.size() >= 5) {
                plans.sort(Comparator.comparingDouble((Plan p) -> -p.score));
                if (c.bound + 5f < plans.get(4).score && c.bound < lethal) break;
            }
            if (++evaluated > 60) break;
            // where a player would click: a face of the block it can see and reach
            Vec3d click = c.anchor || c.build ? placeClick(c.block) : clickFace(c.block, null);
            if (click == null) {
                noClick++;
                continue;
            }
            BlockState was = w.getBlockState(c.block);
            if (c.build) w.setBlockState(c.block, Blocks.OBSIDIAN.getDefaultState(), 0);
            Plan p = score(c.block, c.build, c.anchor, c.center, c.power, tgt, predicted, theirs, foes, friends, arm, mine, lethal, myHp, click);
            if (c.build) w.setBlockState(c.block, was, 0);
            if (p == null) {
                noScore++;
                continue;
            }
            float bias = c.anchor ? personality.anchorBias - 0.6f : personality.crystalBias;
            plans.add(new Plan(p.block, p.build, p.anchor, p.score + bias, click));
        }
        planInfo = "cands " + cands.size() + " noClick " + noClick + " noScore " + noScore + " plans " + plans.size()
                + String.format(Locale.ROOT, " dist %.1f", tgt.getEntityPos().distanceTo(pos));
        if (plans.isEmpty()) return null;
        plans.sort(Comparator.comparingDouble((Plan p) -> -p.score));
        if (bw.rng().nextDouble() < level.accuracy || plans.size() == 1) return plans.get(0);
        return plans.get(bw.rng().nextInt(Math.min(5, plans.size())));
    }

    private Plan score(BlockPos b, boolean build, boolean anchor, Vec3d center, float power, LivingEntity tgt, Vec3d predicted,
                       Scene.Armour theirs, List<LivingEntity> foes, List<LivingEntity> friends, Map<LivingEntity, Scene.Armour> arm,
                       Scene.Armour mine, float lethal, float myHp, Vec3d click) {
        float toT = predicted.squaredDistanceTo(tgt.getEntityPos()) > 0.01
                ? Scene.damageAt(w, center, power, predicted, tgt, theirs) : Scene.damage(center, power, tgt, theirs);
        if (toT < 0.5f) return null;
        float toMe = Scene.damage(center, power, body, mine);
        boolean kills = toT >= lethal && totemsOf(tgt) == 0;
        if (toMe >= myHp - 0.5f && !kills) return null;
        if (toMe >= toT && !kills) return null;
        float score = toT - toMe * 0.6f;
        for (LivingEntity f : foes) {
            if (f == tgt || f.squaredDistanceTo(center) > 64) continue;
            score += 0.5f * Scene.damage(center, power, f, arm.computeIfAbsent(f, e -> Scene.armourOf(w, e)));
        }
        for (LivingEntity f : friends) {
            if (f.squaredDistanceTo(center) > 64) continue;
            score -= 1.2f * Scene.damage(center, power, f, arm.computeIfAbsent(f, e -> Scene.armourOf(w, e)));
        }
        if (kills) score += 100f;
        if (toT >= lethal) score += 8f; // forces a pop
        if (build) score -= 0.4f;
        return new Plan(b, build, anchor, score, click);
    }

    // =========================================================================== what a player can reach and see

    /**
     * A point on a face of block n that the bot can see and reach - where a player would click it.
     * Only the faces turned toward its eyes count, and the ray to the point must hit that face first.
     * Hacker skips the line of sight and has 6 blocks of reach.
     */
    private Vec3d clickFace(BlockPos n, Direction only) {
        Vec3d eye = eye();
        double reach = level.blockReach();
        Vec3d best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            if (only != null && d != only) continue;
            Vec3d normal = new Vec3d(d.getOffsetX(), d.getOffsetY(), d.getOffsetZ());
            Vec3d fc = Vec3d.ofCenter(n).add(normal.multiply(0.5));
            if (eye.subtract(fc).dotProduct(normal) <= 0.01) continue; // its back
            if (only == null && Pathfinder.solid(w, n.offset(d)) && w.getBlockState(n.offset(d)).isFullCube(w, n.offset(d))) continue; // covered
            Direction.Axis ax = d.getAxis();
            Vec3d u = ax == Direction.Axis.X ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
            Vec3d v = ax == Direction.Axis.Z ? new Vec3d(0, 1, 0) : new Vec3d(0, 0, 1);
            double[][] offs = {{0, 0}, {0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3}};
            for (double[] o : offs) {
                Vec3d p = fc.add(u.multiply(o[0])).add(v.multiply(o[1]));
                double dist = p.distanceTo(eye);
                if (dist > reach || dist >= bestD) continue;
                if (level.cheats() || sightTo(p.subtract(normal.multiply(0.02)), n, d)) {
                    best = p;
                    bestD = dist;
                    break;
                }
            }
        }
        return best;
    }

    /** Where to click to put a block INTO the empty spot b: a visible face of a solid block next to it. */
    private Vec3d placeClick(BlockPos b) {
        Vec3d best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = b.offset(d);
            if (!Pathfinder.solid(w, n)) continue;
            Vec3d p = clickFace(n, d.getOpposite());
            if (p != null && p.distanceTo(eye()) < bestD) {
                best = p;
                bestD = p.distanceTo(eye());
            }
        }
        return best;
    }

    /** The ray from the eyes to p hits block n on face side (nothing in between). */
    private boolean sightTo(Vec3d p, BlockPos n, Direction side) {
        net.minecraft.util.hit.BlockHitResult hit = w.raycast(new net.minecraft.world.RaycastContext(eye(), p,
                net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.NONE, body));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK && hit.getBlockPos().equals(n) && hit.getSide() == side;
    }

    /** Nothing solid between the eyes and p. */
    private boolean clearTo(Vec3d p) {
        if (level.cheats()) return true;
        net.minecraft.util.hit.BlockHitResult hit = w.raycast(new net.minecraft.world.RaycastContext(eye(), p,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, body));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }

    /** In reach of a hit (3 blocks to the hitbox, like a player) and visible. */
    private boolean canHit(Entity e) {
        if (e == null || e.isRemoved()) return false;
        Box b = e.getBoundingBox();
        if (Math.sqrt(b.squaredMagnitude(eye())) > level.hitReach()) return false;
        return clearTo(b.getCenter()) || clearTo(new Vec3d(b.getCenter().x, b.maxY - 0.2, b.getCenter().z));
    }

    /** It can see p: in front of it (not behind its head) and nothing in the way. */
    private boolean sees(Vec3d p) {
        Vec3d d = p.subtract(eye());
        float toYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        if (Math.abs(MathHelper.wrapDegrees(toYaw - yaw)) > 75 && !level.cheats()) return false;
        return clearTo(p);
    }

    private int totemsOf(LivingEntity e) {
        Fighter f = bw.fighterOf(e);
        if (f != null && f.bot != null) return f.bot.totemCount();
        return e.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) || e.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) ? 1 : 0;
    }

    private boolean hitsAnyone(Box b) {
        for (Fighter f : bw.fighters()) {
            LivingEntity e = f.entity();
            if (e != null && e.getBoundingBox().intersects(b)) return true;
        }
        return false;
    }

    private boolean supported(BlockPos b) {
        for (Direction d : Direction.values()) if (!w.getBlockState(b.offset(d)).isAir()) return true;
        return false;
    }

    private boolean canSurround() {
        BlockPos feet = BlockPos.ofFloored(pos);
        for (Direction d : Direction.Type.HORIZONTAL) if (w.getBlockState(feet.offset(d)).isAir()) return true;
        return false;
    }

    /** One obsidian at a time, looking at each, until all four sides are closed. */
    private void surround(int t) {
        BlockPos feet = BlockPos.ofFloored(pos);
        boolean missing = false;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos b = feet.offset(d);
            if (!w.getBlockState(b).isAir() || hitsAnyone(new Box(b))) continue;
            Vec3d click = placeClick(b);
            if (click == null) {
                missing = true; // nothing it can click to put a block there
                continue;
            }
            if (!aimed(click)) return;
            pos = Vec3d.ofBottomCenter(feet);
            noteClick();
            w.setBlockState(b, Blocks.OBSIDIAN.getDefaultState(), 3);
            consumeHeld();
            body.swingHand(Hand.MAIN_HAND);
            w.playSound(null, b, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 1f, 1f);
            nextActionAt = t + (level.atLeast(BotLevel.PRO) ? 0 : 1);
            lastAction = "surrounds";
            return;
        }
        if (missing) surroundGiveUpUntil = t + 100;
        else surrounded = true;
    }

    /** City: their surround blocks every good spot - mine one of its blocks, then crystal the gap. */
    private boolean cityMine(LivingEntity tgt) {
        if (!has(s -> s.isIn(ItemTags.PICKAXES))) return false;
        BlockPos feet = tgt.getBlockPos();
        int walls = 0;
        BlockPos pick = null;
        double pickD = 99;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos b = feet.offset(d);
            if (!Pathfinder.solid(w, b)) continue;
            walls++;
            if (bw.session().isProtected(b) || w.getBlockState(b).getHardness(w, b) < 0) continue;
            Vec3d click = clickFace(b, null);
            if (click == null) continue;
            double r = click.distanceTo(eye());
            if (r < pickD) {
                pickD = r;
                pick = b.toImmutable();
            }
        }
        if (walls < 3 || pick == null) return false;
        mining = pick;
        mineProgress = 0;
        lastAction = "cities your surround";
        return true;
    }

    /** One tick of mining: look at it, crack it (everyone sees the cracks), break it. */
    private void mineTick(int t) {
        BlockState st = w.getBlockState(mining);
        if (st.isAir() || bw.session().isProtected(mining)) {
            w.setBlockBreakingInfo(body.getId(), mining, -1);
            mining = null;
            return;
        }
        if (!hold(s -> s.isIn(ItemTags.PICKAXES))) return;
        Vec3d click = clickFace(mining, null);
        if (click == null) {
            w.setBlockBreakingInfo(body.getId(), mining, -1);
            mining = null; // out of reach or out of sight
            return;
        }
        if (!aimed(click)) return;
        float hardness = st.getHardness(w, mining);
        if (hardness < 0) {
            mining = null;
            return;
        }
        float speed = held().getMiningSpeedMultiplier(st);
        int eff = enchLevel(held(), Enchantments.EFFICIENCY);
        if (speed > 1f && eff > 0) speed += eff * eff + 1;
        float perTick = hardness == 0 ? 1f : speed / hardness / (held().isSuitableFor(st) ? 30f : 100f);
        if (!onGround) perTick /= 5f;
        mineProgress += perTick;
        if (t % 4 == 0) body.swingHand(Hand.MAIN_HAND);
        w.setBlockBreakingInfo(body.getId(), mining, Math.min(9, (int) (mineProgress * 10)));
        if (mineProgress >= 1f) {
            w.setBlockBreakingInfo(body.getId(), mining, -1);
            w.breakBlock(mining, false, body);
            blocksMined++;
            mining = null;
            path = null;
            surrounded = false;
            nextActionAt = t + 1;
        }
    }

    // =========================================================================== melee

    private boolean melee(int t, LivingEntity tgt, double dist) {
        // shield up while waiting for the attack cooldown
        if (offhand.isOf(Items.SHIELD) && dist < 4 && t + 4 < nextSwingAt && t >= shieldDisabledUntil && shieldUntil < 0) {
            body.setCurrentHand(Hand.OFF_HAND);
            shieldUntil = t + Math.min(14, nextSwingAt - t - 2);
            return true;
        }
        if (t < nextSwingAt || !canHit(tgt)) return false;
        Predicate<ItemStack> weapon;
        if (tgt.isBlocking() && has(s -> s.isIn(ItemTags.AXES))) weapon = s -> s.isIn(ItemTags.AXES);
        else if (has(s -> s.isIn(ItemTags.SWORDS))) weapon = s -> s.isIn(ItemTags.SWORDS);
        else if (has(s -> s.isIn(ItemTags.AXES))) weapon = s -> s.isIn(ItemTags.AXES);
        else weapon = PvpBot::isWeapon;
        if (!hold(weapon)) return true;
        if (!aimed(tgt.getBoundingBox().getCenter().add(0, 0.4, 0))) return true;
        // crits: swing on the way down of a jump
        boolean falling = !onGround && vel.y < -0.05;
        if (!onGround && !falling && vel.y > 0) return true;
        if (shieldUntil >= 0) lowerShield();
        hit(tgt, falling, t);
        return true;
    }

    private void hit(LivingEntity tgt, boolean crit, int t) {
        ItemStack wpn = held();
        float base;
        int cooldown;
        if (wpn.isOf(Items.NETHERITE_SWORD)) { base = 8f; cooldown = 13; }
        else if (wpn.isOf(Items.DIAMOND_SWORD)) { base = 7f; cooldown = 13; }
        else if (wpn.isIn(ItemTags.SWORDS)) { base = 6f; cooldown = 13; }
        else if (wpn.isOf(Items.NETHERITE_AXE)) { base = 10f; cooldown = 20; }
        else if (wpn.isIn(ItemTags.AXES)) { base = 9f; cooldown = 20; }
        else if (wpn.isOf(Items.MACE)) { base = 6f; cooldown = 33; }
        else { base = 1f; cooldown = 5; }
        int sharp = enchLevel(wpn, Enchantments.SHARPNESS);
        float dmg = base + (sharp > 0 ? 0.5f * sharp + 0.5f : 0f);
        if (crit) dmg *= 1.5f;
        body.swingHand(Hand.MAIN_HAND);
        noteHit(tgt);
        Vec3d away = new Vec3d(tgt.getX() - pos.x, 0, tgt.getZ() - pos.z);
        tgt.damage(w, w.getDamageSources().mobAttack(body), dmg);
        if (away.lengthSquared() > 1e-6 && sprinting) {
            Vec3d d = away.normalize();
            tgt.takeKnockback(0.5, -d.x, -d.z); // sprint hit: an extra knockback level
            if (tgt instanceof ServerPlayerEntity sp) sp.knockedBack = true;
        }
        if (crit) w.spawnParticles(ParticleTypes.CRIT, tgt.getX(), tgt.getEyeY() - 0.4, tgt.getZ(), 10, 0.3, 0.3, 0.3, 0.2);
        w.playSound(null, body.getBlockPos(), crit ? SoundEvents.ENTITY_PLAYER_ATTACK_CRIT : SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1f, 1f);
        meleeHits++;
        nextSwingAt = t + cooldown;
        nextActionAt = t + Math.max(1, reactionTicks / 3);
        wtapUntil = t + 3; // let go of W so the next hit knocks back fully
        lastAction = crit ? "crits" : "hits";
    }

    private void lowerShield() {
        if (body.isUsingItem()) body.clearActiveItem();
        shieldUntil = -1;
    }

    // =========================================================================== mace and elytra

    private boolean startAerial(int t, double dist) {
        if (!has(s -> s.isOf(Items.MACE))) return false;
        boolean elytra = has(s -> s.isOf(Items.ELYTRA)) && has(s -> s.isOf(Items.FIREWORK_ROCKET)) && dist > 7;
        boolean wind = has(s -> s.isOf(Items.WIND_CHARGE)) && dist < 9;
        if (elytra) {
            int i = first(s -> s.isOf(Items.ELYTRA));
            ItemStack chest = body.getEquippedStack(EquipmentSlot.CHEST).copy();
            body.equipStack(EquipmentSlot.CHEST, inv[i].copy());
            inv[i] = chest;
            vel = new Vec3d(vel.x, 0.42, vel.z);
            air = Air.WIND_UP;
            airUntil = t + 200;
            lastAction = "takes off";
            busyUntil = t + 2;
            return true;
        }
        if (wind) {
            if (!hold(s -> s.isOf(Items.WIND_CHARGE))) return true;
            if (!aimed(pos)) return true; // looks down at its feet
            consumeHeld();
            Vec3d toward = new Vec3d(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
            vel = new Vec3d(toward.x * 0.35, 1.15, toward.z * 0.35);
            windJump = true;
            air = Air.DIVE;
            airUntil = t + 80;
            w.spawnParticles(ParticleTypes.GUST_EMITTER_SMALL, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
            w.playSound(null, body.getBlockPos(), SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 1f, 1f);
            lastAction = "wind-jumps";
            return true;
        }
        return false;
    }

    private void aerial(int t, LivingEntity tgt) {
        Vec3d tp = tgt.getEntityPos();
        double hd = Math.sqrt((tp.x - pos.x) * (tp.x - pos.x) + (tp.z - pos.z) * (tp.z - pos.z));
        switch (air) {
            case WIND_UP -> {
                if (!onGround && vel.y < 0.1) {
                    if (!hold(s -> s.isOf(Items.FIREWORK_ROCKET))) return;
                    consumeHeld();
                    setGliding(true);
                    boostTicks = 22;
                    air = Air.BOOST;
                    w.playSound(null, body.getBlockPos(), SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.PLAYERS, 1f, 1f);
                }
            }
            case BOOST, GLIDE -> {
                double alt = pos.y - tp.y;
                Vec3d aim = tp.add(0, Math.min(14, 6 + hd * 0.5), 0);
                lookTarget = alt < 8 ? pos.add(aim.subtract(pos).normalize().multiply(5)).add(0, 3, 0) : aim;
                if (boostTicks <= 0) air = Air.GLIDE;
                if (hd < 3.5 && alt > 4) {
                    air = Air.DIVE;
                    hold(s -> s.isOf(Items.MACE));
                }
                if (air == Air.GLIDE && alt < 5 && hd > 6 && t >= busyUntil && hold(s -> s.isOf(Items.FIREWORK_ROCKET))) {
                    consumeHeld();
                    boostTicks = 18;
                    air = Air.BOOST;
                }
            }
            case DIVE -> {
                hold(s -> s.isOf(Items.MACE));
                lookTarget = tgt.getEyePos();
                if (gliding && hd < 3.5) {
                    setGliding(false);
                    vel = new Vec3d((tp.x - pos.x) * 0.25, Math.min(vel.y, -0.6), (tp.z - pos.z) * 0.25);
                } else if (!gliding) {
                    walk = new Vec3d(tp.x - pos.x, 0, tp.z - pos.z).normalize().multiply(0.06);
                }
                double fall = peakY - pos.y;
                boolean close = body.getBoundingBox().expand(1.2, 0.6, 1.2).intersects(tgt.getBoundingBox());
                if (close && vel.y < 0 && held().isOf(Items.MACE) && fall > 1.5) smash(tgt, fall, t);
            }
            default -> { }
        }
        if (onGround && air != Air.WIND_UP) landed(t);
        if (t > airUntil) landed(t);
    }

    private void smash(LivingEntity tgt, double fall, int t) {
        float f = (float) fall;
        float bonus = f <= 3 ? 4 * f : f <= 8 ? 12 + 2 * (f - 3) : 22 + (f - 8);
        bonus += enchLevel(held(), Enchantments.DENSITY) * 0.5f * f;
        body.swingHand(Hand.MAIN_HAND);
        tgt.damage(w, w.getDamageSources().mobAttack(body), 6f + bonus);
        w.playSound(null, tgt.getBlockPos(), f > 5 ? SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY : SoundEvents.ITEM_MACE_SMASH_GROUND, SoundCategory.PLAYERS, 1f, 1f);
        w.spawnParticles(ParticleTypes.CRIT, tgt.getX(), tgt.getEyeY() - 0.4, tgt.getZ(), 16, 0.4, 0.4, 0.4, 0.3);
        maceSmashes++;
        lastAction = String.format(Locale.ROOT, "smashes from %.0f blocks", f);
        peakY = pos.y;
        if (enchLevel(held(), Enchantments.WIND_BURST) > 0) {
            vel = new Vec3d(vel.x * 0.3, 1.0, vel.z * 0.3); // Wind Burst: straight back up for another
            airUntil = t + 60;
        } else {
            vel = new Vec3d(vel.x * 0.2, 0.1, vel.z * 0.2);
            nextSwingAt = t + 33;
            landed(t);
        }
    }

    private void landed(int t) {
        if (gliding) setGliding(false);
        if (body.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            int i = first(s -> s.contains(DataComponentTypes.EQUIPPABLE)
                    && s.get(DataComponentTypes.EQUIPPABLE).slot() == EquipmentSlot.CHEST && !s.isOf(Items.ELYTRA));
            if (i >= 0) {
                ItemStack ely = body.getEquippedStack(EquipmentSlot.CHEST).copy();
                body.equipStack(EquipmentSlot.CHEST, inv[i].copy());
                inv[i] = ely;
            }
        }
        if (air != Air.NONE && nextSwingAt < t + 20) nextSwingAt = t + 20;
        air = Air.NONE;
        windJump = false;
        boostTicks = 0;
    }

    private void setGliding(boolean on) {
        gliding = on;
        try {
            ((dev.xsoz.client.mixin.EntityFlagAccessor) body).xsoz$setFlag(7, on);
        } catch (RuntimeException | LinkageError ignored) {
            // the pose is only cosmetic
        }
    }

    // =========================================================================== pearls, gapples, totems, mending

    /** Pearl away from the fight, to the open spot farthest from enemies. True when a throw is lined up. */
    private boolean pearlAway(int t, LivingEntity tgt) {
        if (pearlAimUntil >= 0) return true;
        Vec3d away = new Vec3d(pos.x - tgt.getX(), 0, pos.z - tgt.getZ());
        if (away.lengthSquared() < 1e-4) away = new Vec3d(1, 0, 0);
        double base = Math.atan2(away.z, away.x);
        BlockPos best = null;
        double bestScore = -1;
        for (int i = 0; i < 12; i++) {
            double ang = base + (bw.rng().nextDouble() - 0.5) * 2.2;
            double dd = 9 + bw.rng().nextDouble() * 8;
            BlockPos g = Pathfinder.ground(w, BlockPos.ofFloored(pos.x + Math.cos(ang) * dd, pos.y + 3, pos.z + Math.sin(ang) * dd));
            if (g == null || !inside(g)) continue;
            double score = 999;
            for (Fighter f : enemies()) score = Math.min(score, f.entity().getEntityPos().distanceTo(Vec3d.ofBottomCenter(g)));
            if (score > bestScore) {
                bestScore = score;
                best = g;
            }
        }
        return best != null && queuePearl(t, Vec3d.ofBottomCenter(best), "pearls away");
    }

    /** Pearl in next to a target that is far away. */
    private boolean pearlIn(int t, LivingEntity tgt) {
        if (pearlAimUntil >= 0) return true;
        Vec3d to = new Vec3d(pos.x - tgt.getX(), 0, pos.z - tgt.getZ());
        to = to.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : to.normalize();
        BlockPos g = Pathfinder.ground(w, BlockPos.ofFloored(tgt.getX() + to.x * 3.5, tgt.getY() + 3, tgt.getZ() + to.z * 3.5));
        return g != null && inside(g) && queuePearl(t, Vec3d.ofBottomCenter(g), "pearls in");
    }

    private boolean inside(BlockPos p) {
        BlockPos c = bw.center();
        int r = bw.radius() - 1;
        return Math.abs(p.getX() - c.getX()) <= r && Math.abs(p.getZ() - c.getZ()) <= r;
    }

    /** Works out the yaw and pitch that land a pearl at land, then lines the head up (pearlStep throws it). */
    private boolean queuePearl(int t, Vec3d land, String why) {
        if (!has(s -> s.isOf(Items.ENDER_PEARL)) || t < nextPearlAt) return false;
        float[] aim = solvePearl(land);
        if (aim == null) return false;
        pearlYaw = aim[0];
        pearlPitch = aim[1];
        pearlTarget = land;
        pearlAimUntil = t + 25;
        pearlWhy = why;
        return true;
    }

    /** Head on the solved angles (the head turns at the level's speed), then the throw. */
    private void pearlStep(int t) {
        if (t > pearlAimUntil) {
            pearlAimUntil = -1;
            return;
        }
        if (!hold(s -> s.isOf(Items.ENDER_PEARL))) return;
        lookTarget = eye().add(direction(pearlYaw, pearlPitch).multiply(10));
        lookSetAt = t;
        if (Math.abs(MathHelper.wrapDegrees(pearlYaw - yaw)) > 2.5f || Math.abs(pearlPitch - pitch) > 2.5f) return;
        // one last check from where it stands now (knockback can move it while it aims)
        float[] again = pearlTarget == null ? null : solvePearl(pearlTarget);
        if (again != null && (Math.abs(MathHelper.wrapDegrees(again[0] - pearlYaw)) > 2.5f || Math.abs(again[1] - pearlPitch) > 2.5f)) {
            pearlYaw = again[0];
            pearlPitch = again[1];
            return;
        }
        pearlAimUntil = -1;
        // vanilla: from the eyes, 1.5 blocks a tick along the look, plus the thrower's own motion
        Vec3d v = direction(yaw, pitch).multiply(1.5).add(inherited());
        EnderPearlEntity p = EntityType.ENDER_PEARL.create(w, SpawnReason.COMMAND);
        if (p == null) return;
        p.refreshPositionAndAngles(pos.x, pos.y + 1.52, pos.z, 0f, 0f);
        p.setVelocity(v);
        w.spawnEntity(p);
        consumeHeld();
        body.swingHand(Hand.MAIN_HAND);
        w.playSound(null, body.getBlockPos(), SoundEvents.ENTITY_ENDER_PEARL_THROW, SoundCategory.PLAYERS, 1f, 1f);
        pearl = p;
        pearlLast = p.getEntityPos();
        pearlsThrown++;
        nextPearlAt = t + 20 * 6;
        surrounded = false;
        lastAction = pearlWhy;
        nextActionAt = t + reactionTicks;
    }

    private static Vec3d direction(float yaw, float pitch) {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        return new Vec3d(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
    }

    private Vec3d inherited() { return new Vec3d(vel.x + walk.x, onGround ? 0 : vel.y, vel.z + walk.z); }

    /** Vanilla pearl flight (drag 0.99, gravity 0.03) until it hits a block; null if it never does. */
    private Vec3d simulatePearl(Vec3d start, Vec3d v) {
        Vec3d p = start;
        int floor = bw.center().getY() - 40;
        for (int i = 0; i < 140; i++) {
            Vec3d next = p.add(v);
            net.minecraft.util.hit.BlockHitResult hit = w.raycast(new net.minecraft.world.RaycastContext(p, next,
                    net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, body));
            if (hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) return hit.getPos();
            p = next;
            v = v.multiply(0.99).add(0, -0.03, 0);
            if (p.y < floor) return null;
        }
        return null;
    }

    /** Yaw and pitch that land a pearl thrown now within ~1.5 blocks of land (the flattest throw that works), or null. */
    private float[] solvePearl(Vec3d land) {
        Vec3d start = pos.add(0, 1.52, 0);
        Vec3d inherit = inherited();
        float yawTo = (float) Math.toDegrees(Math.atan2(-(land.x - start.x), land.z - start.z));
        float bestPitch = 0;
        double bestErr = Double.MAX_VALUE;
        for (float p = 30f; p >= -70f; p -= 2.5f) {
            Vec3d l = simulatePearl(start, direction(yawTo, p).multiply(1.5).add(inherit));
            if (l == null) continue;
            double err = Math.sqrt((l.x - land.x) * (l.x - land.x) + (l.z - land.z) * (l.z - land.z)) + Math.abs(l.y - land.y) * 0.5;
            if (err < bestErr - 0.3) {
                bestErr = err;
                bestPitch = p;
            }
            if (bestErr < 1.0) break; // flattest good throw
        }
        for (float p = bestPitch - 2f; p <= bestPitch + 2f; p += 0.5f) {
            Vec3d l = simulatePearl(start, direction(yawTo, p).multiply(1.5).add(inherit));
            if (l == null) continue;
            double err = Math.sqrt((l.x - land.x) * (l.x - land.x) + (l.z - land.z) * (l.z - land.z)) + Math.abs(l.y - land.y) * 0.5;
            if (err < bestErr) {
                bestErr = err;
                bestPitch = p;
            }
        }
        return bestErr <= 2.0 ? new float[] {yawTo, bestPitch} : null;
    }

    /**
     * Watches for an enemy's pearl. If it SEES one leave (in front of it, nothing in the way) and is
     * healthy, a good player follows: it works out where the pearl will land and pearls there too.
     */
    private void watchPearls(int t) {
        if (watched != null && watched.isRemoved()) watched = null;
        if (watched == null) {
            if (!level.knows(BotLevel.Tech.PEARL_FOLLOW)) return;
            for (Fighter f : enemies()) {
                EnderPearlEntity p = pearlOf(f);
                if (p == null || p.isRemoved() || !sees(p.getEntityPos())) continue;
                watched = p;
                watchedNoticeAt = t + reactionTicks;
                watchedFollow = bw.rng().nextDouble() < level.pearlFollowChance() * personality.chase;
                return;
            }
            return;
        }
        if (!watchedFollow || t < watchedNoticeAt) return;
        watchedFollow = false; // one decision per pearl
        if (health() < 15 || t < nextPearlAt || eatingUntil >= 0 || retotemAt >= 0 || air != Air.NONE || pearlAimUntil >= 0) return;
        Vec3d land = simulatePearl(watched.getEntityPos(), watched.getVelocity());
        if (land == null || land.distanceTo(pos) < 7) return;
        BlockPos g = Pathfinder.ground(w, BlockPos.ofFloored(land.x, land.y + 1.5, land.z));
        if (g != null && inside(g) && queuePearl(t, Vec3d.ofBottomCenter(g), "follows the pearl")) pearlsFollowed++;
    }

    private EnderPearlEntity pearlOf(Fighter f) {
        if (f.bot != null) return f.bot.pearlInFlight();
        LivingEntity e = f.entity();
        if (e == null) return null;
        Box area = new Box(bw.center()).expand(bw.radius() + 2, 60, bw.radius() + 2);
        for (EnderPearlEntity p : w.getEntitiesByClass(EnderPearlEntity.class, area, x -> x.getOwner() == e)) return p;
        return null;
    }

    private void tickPearl() {
        if (pearl == null) return;
        if (!pearl.isRemoved()) {
            pearlLast = pearl.getEntityPos();
            return;
        }
        // like a player: arrive where it hit (under a roof, beside a wall) and fall from there
        Vec3d to = pearlLast;
        BlockPos f = BlockPos.ofFloored(to);
        BlockPos at = null;
        for (int k = 0; k < 3 && at == null; k++) {
            if (Pathfinder.passable(w, f) && Pathfinder.passable(w, f.up())) at = f;
            f = f.down();
        }
        if (at == null) at = Pathfinder.ground(w, BlockPos.ofFloored(to).up());
        if (at != null && inside(at)) pos = new Vec3d(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        firstLanding = firstLanding == null ? pos : firstLanding;
        vel = Vec3d.ZERO;
        peakY = pos.y;
        pearl = null;
        path = null;
        applyDamage(CombatMath.afterProtection(5f, EnchantmentHelper.getProtectionAmount(w, body, w.getDamageSources().fall())));
        w.playSound(null, BlockPos.ofFloored(pos), SoundEvents.ENTITY_PLAYER_TELEPORT, SoundCategory.PLAYERS, 1f, 1f);
    }

    /** Eats for real: the eating animation and sound, 32 ticks, slowed down. */
    private void startEating(int t) {
        eatingUntil = t + 32;
        eatStarts++;
        body.setCurrentHand(Hand.MAIN_HAND);
        lookTarget = null;
        lastAction = "eats a gapple";
    }

    private void finishEating(int t) {
        eatingUntil = -1;
        if (body.isUsingItem()) body.clearActiveItem();
        boolean god = held().isOf(Items.ENCHANTED_GOLDEN_APPLE);
        if (!held().isOf(Items.GOLDEN_APPLE) && !god) {
            eatFail = "held " + held().getItem() + " sel " + selected;
            return;
        }
        consumeHeld();
        absorption = Math.max(absorption, god ? 16f : 4f);
        absorptionUntil = t + 2400;
        regenUntil = Math.max(regenUntil, t + (god ? 400 : 100));
        regenEvery = god ? 12 : 25;
        if (god) fireResUntil = t + 6000;
        gapplesEaten++;
        w.playSound(null, body.getBlockPos(), SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.6f, 1f);
        nextActionAt = t + 2;
    }

    private void startRetotem(int t) {
        if (retotemAt >= 0) return;
        retotemAt = t + level.retotemTicks + (firstHotbar(s -> s.isOf(Items.TOTEM_OF_UNDYING)) < 0 ? level.refillTicks : 0);
        headLockUntil = retotemAt;
    }

    private void retotem() {
        retotemAt = -1;
        if (offhand.isOf(Items.TOTEM_OF_UNDYING)) return;
        int i = firstHotbar(s -> s.isOf(Items.TOTEM_OF_UNDYING));
        if (i < 0) i = first(s -> s.isOf(Items.TOTEM_OF_UNDYING));
        if (i < 0) return;
        ItemStack old = offhand;
        offhand = inv[i].split(1);
        if (!old.isEmpty()) {
            for (int k = 9; k < 36; k++) {
                if (inv[k].isEmpty()) {
                    inv[k] = old;
                    break;
                }
            }
        }
        syncHands();
        lastAction = "retotems";
    }

    private boolean armourLow() {
        for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = body.getEquippedStack(s);
            if (st.isDamageable() && st.getDamage() > st.getMaxDamage() * 0.55) return true;
        }
        return false;
    }

    /** An XP bottle at its own feet: Mending repairs the worn pieces (2 durability per XP point). */
    private void mend(int t) {
        consumeHeld();
        int repair = (3 + bw.rng().nextInt(9)) * 2;
        for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = body.getEquippedStack(s);
            if (repair <= 0 || !st.isDamageable() || st.getDamage() == 0 || enchLevel(st, Enchantments.MENDING) == 0) continue;
            int r = Math.min(repair, st.getDamage());
            st.setDamage(st.getDamage() - r);
            repair -= r;
        }
        w.syncWorldEvent(2002, body.getBlockPos(), 0x7FFF00);
        body.swingHand(Hand.MAIN_HAND);
        bottlesThrown++;
        nextActionAt = t + 2;
        lastAction = "mends";
    }

    // =========================================================================== taking damage

    /**
     * A hit on the bot's body (ALLOW_DAMAGE). Runs the player rules and always cancels the real
     * damage: the bot's health lives here.
     */
    public boolean onDamage(DamageSource source, float amount) {
        if (!alive()) return false;
        boolean explosion = source.isIn(DamageTypeTags.IS_EXPLOSION);
        Entity attacker = source.getAttacker();
        boolean melee = attacker instanceof LivingEntity && !explosion && source.getSource() == attacker;
        if (!explosion && !melee) return false; // falls, suffocation, fire: our own body handles them
        Fighter by = attacker == null ? null : bw.fighterOf(attacker);
        if (by == self && melee) return false;
        Vec3d from = explosion && source.getPosition() != null ? source.getPosition() : attacker != null ? attacker.getEntityPos() : null;
        if (body.isBlocking() && from != null && CombatMath.shieldBlocks(pos.x, pos.z, yaw, from.x, from.z)) {
            if (melee && ((LivingEntity) attacker).getMainHandStack().isIn(ItemTags.AXES)) {
                lowerShield();
                shieldDisabledUntil = bw.ticks() + 100;
                w.playSound(null, body.getBlockPos(), SoundEvents.ITEM_SHIELD_BREAK.value(), SoundCategory.PLAYERS, 1f, 1f);
            } else {
                w.playSound(null, body.getBlockPos(), SoundEvents.ITEM_SHIELD_BLOCK.value(), SoundCategory.PLAYERS, 1f, 1f);
            }
            return false;
        }
        float dmg = amount;
        if (source.isScaledWithDifficulty()) dmg = CombatMath.scaleForDifficulty(dmg, w.getDifficulty().getId());
        if (!source.isIn(DamageTypeTags.BYPASSES_ARMOR)) {
            Scene.Armour a = Scene.armourOf(w, body);
            dmg = CombatMath.afterArmor(dmg, a.armor(), a.toughness());
            wearArmour(amount);
        }
        dmg = CombatMath.afterProtection(dmg, EnchantmentHelper.getProtectionAmount(w, body, source));
        boolean newHit = hurtTicks <= 0;
        // the 10-tick hurt window: only the part above the last hit counts inside it
        if (hurtTicks > 0) {
            if (dmg <= lastHurt) return false;
            float extra = dmg - lastHurt;
            lastHurt = dmg;
            dmg = extra;
        } else {
            lastHurt = dmg;
            hurtTicks = 10;
        }
        if (melee && newHit) knockback(attacker);
        if (by != null && by != self && self != null) {
            self.lastHitBy = by;
            self.lastHitAt = bw.ticks();
            by.dealt += dmg;
        }
        applyDamage(dmg);
        if (alive()) w.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, pos.x, pos.y + 1.2, pos.z, Math.max(1, Math.round(dmg / 2)), 0.3, 0.3, 0.3, 0.1);
        return false;
    }

    /** Vanilla LivingEntity.takeKnockback, with the knockback resistance of the netherite it wears. */
    private void knockback(Entity attacker) {
        double res = Math.min(1, 0.1 * armourPieces(Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS));
        double strength = 0.4 * (1 - res);
        if (attacker instanceof LivingEntity le && le.isSprinting()) strength += 0.5 * (1 - res);
        if (strength <= 0 || surrounded) return;
        double dx = attacker.getX() - pos.x;
        double dz = attacker.getZ() - pos.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) return;
        double push = 0.4 * (1 - res);
        vel = new Vec3d(vel.x / 2 - dx / len * strength, onGround ? Math.min(0.4, vel.y / 2 + push) : vel.y, vel.z / 2 - dz / len * strength);
        onGround = false;
    }

    private int armourPieces(net.minecraft.item.Item... items) {
        int n = 0;
        for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            for (net.minecraft.item.Item it : items) if (body.getEquippedStack(s).isOf(it)) n++;
        }
        return n;
    }

    /** Armour wears like a player's: a quarter of the damage per piece (at least 1), Unbreaking helps. */
    private void wearArmour(float amount) {
        int per = Math.max(1, (int) (amount / 4));
        for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = body.getEquippedStack(s);
            if (!st.isDamageable()) continue;
            int unb = enchLevel(st, Enchantments.UNBREAKING);
            if (unb > 0 && bw.rng().nextDouble() >= 0.6 + 0.4 / (unb + 1)) continue;
            st.setDamage(st.getDamage() + per);
            if (st.getDamage() >= st.getMaxDamage()) {
                body.equipStack(s, ItemStack.EMPTY);
                w.playSound(null, body.getBlockPos(), SoundEvents.ENTITY_ITEM_BREAK.value(), SoundCategory.PLAYERS, 1f, 1f);
            }
        }
    }

    /** Health -> totem -> death. */
    private void applyDamage(float dmg) {
        lastDamagedAt = bw.ticks();
        float soak = Math.min(absorption, dmg);
        absorption -= soak;
        dmg -= soak;
        hp -= dmg;
        if (hp > 0) return;
        boolean offTotem = offhand.isOf(Items.TOTEM_OF_UNDYING);
        boolean mainTotem = held().isOf(Items.TOTEM_OF_UNDYING);
        if (offTotem || mainTotem) {
            if (offTotem) offhand = ItemStack.EMPTY;
            else held().decrement(1);
            hp = 1f;
            absorption = 8f;
            absorptionUntil = bw.ticks() + 100;
            regenUntil = bw.ticks() + 900;
            regenEvery = 25;
            fireResUntil = bw.ticks() + 800;
            if (self != null) self.pops++;
            syncHands();
            w.sendEntityStatus(body, EntityStatuses.USE_TOTEM_OF_UNDYING);
            if (has(s -> s.isOf(Items.TOTEM_OF_UNDYING))) startRetotem(bw.ticks());
            lastAction = "pops";
            return;
        }
        hp = 0;
        die();
    }

    private void die() {
        alive = false;
        w.spawnParticles(ParticleTypes.POOF, pos.x, pos.y + 1, pos.z, 20, 0.3, 0.6, 0.3, 0.02);
        w.playSound(null, BlockPos.ofFloored(pos), SoundEvents.ENTITY_PLAYER_DEATH, SoundCategory.PLAYERS, 1f, 1f);
        despawnBody();
        lastAction = "died";
    }

    /** For the owner: out of the arena, or the round is over. */
    public void kill() {
        if (alive()) {
            hp = 0;
            die();
        }
    }

    // =========================================================================== body physics

    private void physics() {
        if (moveDir.lengthSquared() > 1e-6) {
            int t = bw.ticks();
            boolean using = eatingUntil >= 0 || body.isUsingItem();
            float moveYaw = (float) Math.toDegrees(Math.atan2(-moveDir.x, moveDir.z));
            float off = Math.abs(MathHelper.wrapDegrees(moveYaw - yaw));
            // like a player: sprint only going forward (W, W+A, W+D) - never backwards, sideways,
            // eating or blocking. Released for a moment on a W-tap.
            sprinting = wantSprint && !using && off <= 50f && t >= wtapUntil;
            double speed = using ? 0.043 : sprinting ? 0.28 : 0.216;
            if (t < wtapUntil) speed *= 0.2;
            walk = moveDir.multiply(Math.min(speed, Math.max(0.05, moveLeft)));
        } else if (onGround) {
            sprinting = false;
        }
        Vec3d kb = body.getVelocity();
        if (kb.lengthSquared() > 1e-6) vel = vel.add(kb);
        double floorY = groundBelow(pos);
        boolean wasGround = onGround;
        onGround = pos.y <= floorY + 1e-3 && vel.y <= 0 && !gliding;
        if (onGround) {
            pos = new Vec3d(pos.x, floorY, pos.z);
            if (!wasGround) landing();
            double jump = wantJump ? 0.42 : Math.max(0, vel.y);
            vel = new Vec3d(vel.x * 0.546, jump, vel.z * 0.546);
            if (wantJump) onGround = false;
            peakY = pos.y;
        } else if (gliding) {
            glide();
        } else {
            vel = new Vec3d(vel.x * 0.91, (vel.y - 0.08) * 0.98, vel.z * 0.91);
        }
        if (!onGround) peakY = Math.max(peakY, pos.y);
        Vec3d step = gliding ? vel : new Vec3d(vel.x + walk.x, vel.y, vel.z + walk.z);
        Vec3d next = new Vec3d(pos.x + step.x, pos.y, pos.z);
        if (blocked(next)) {
            next = pos;
            vel = new Vec3d(0, vel.y, vel.z);
            if (gliding) setGliding(false);
        }
        Vec3d next2 = new Vec3d(next.x, next.y, next.z + step.z);
        if (blocked(next2)) {
            next2 = next;
            vel = new Vec3d(vel.x, vel.y, 0);
            if (gliding) setGliding(false);
        }
        double ny = next2.y + step.y;
        double fl = groundBelow(next2);
        if (ny < fl) {
            ny = fl;
            vel = new Vec3d(vel.x, 0, vel.z);
        }
        Vec3d up = new Vec3d(next2.x, ny, next2.z);
        if (step.y > 0 && blocked(up)) {
            vel = new Vec3d(vel.x, 0, vel.z);
            up = new Vec3d(next2.x, next2.y, next2.z);
        }
        pos = up;
        BlockPos c = bw.center();
        int r = bw.radius();
        pos = new Vec3d(MathHelper.clamp(pos.x, c.getX() - r + 0.5, c.getX() + r + 0.5), pos.y,
                MathHelper.clamp(pos.z, c.getZ() - r + 0.5, c.getZ() + r + 0.5));
        if (pos.y < c.getY() - 12) {
            kill();
            return;
        }
        // the body turns toward where it walks, the head toward what it looks at
        if (walk.lengthSquared() > 1e-4) {
            float moveYaw = (float) Math.toDegrees(Math.atan2(-walk.x, walk.z));
            float target = Math.abs(MathHelper.wrapDegrees(moveYaw - yaw)) > 100 ? yaw : moveYaw;
            bodyYaw += MathHelper.clamp(MathHelper.wrapDegrees(target - bodyYaw), -20f, 20f);
        } else {
            bodyYaw += MathHelper.clamp(MathHelper.wrapDegrees(yaw - bodyYaw), -10f, 10f);
        }
        float twist = MathHelper.wrapDegrees(yaw - bodyYaw);
        if (Math.abs(twist) > 75) bodyYaw = yaw - Math.signum(twist) * 75;
        body.setVelocity(Vec3d.ZERO);
        body.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, pitch);
        body.setHeadYaw(yaw);
        body.setBodyYaw(bodyYaw);
        body.setSprinting(sprinting);
        if (boostTicks > 0) boostTicks--;
    }

    /** Vanilla elytra flight, plus a firework boost while it burns. */
    private void glide() {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        Vec3d look = new Vec3d(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        double hl = Math.sqrt(look.x * look.x + look.z * look.z);
        double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        double cos2 = Math.cos(pr) * Math.cos(pr);
        Vec3d v = new Vec3d(vel.x, vel.y + (-0.08 + cos2 * 0.06), vel.z);
        if (v.y < 0 && hl > 0) {
            double d = v.y * -0.1 * cos2;
            v = v.add(look.x * d / hl, d, look.z * d / hl);
        }
        if (pr < 0 && hl > 0) {
            double d = speed * -Math.sin(pr) * 0.04;
            v = v.add(-look.x * d / hl, d * 3.2, -look.z * d / hl);
        }
        if (hl > 0) v = v.add((look.x / hl * speed - v.x) * 0.1, 0, (look.z / hl * speed - v.z) * 0.1);
        v = new Vec3d(v.x * 0.99, v.y * 0.98, v.z * 0.99);
        if (boostTicks > 0) v = v.add(look.x * 0.1 + (look.x * 1.5 - v.x) * 0.5, look.y * 0.1 + (look.y * 1.5 - v.y) * 0.5, look.z * 0.1 + (look.z * 1.5 - v.z) * 0.5);
        vel = v;
    }

    private void landing() {
        double fall = peakY - pos.y;
        if (fall > 3.0 && air == Air.NONE && !windJump) {
            int ff = enchLevel(body.getEquippedStack(EquipmentSlot.FEET), Enchantments.FEATHER_FALLING);
            float dmg = (float) Math.ceil(fall - 3.0);
            dmg = CombatMath.afterProtection(dmg, Math.min(20, ff * 3 + protectionLevels()));
            if (dmg >= 0.5f) applyDamage(dmg);
        }
        windJump = false;
    }

    private int protectionLevels() {
        int n = 0;
        for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            n += enchLevel(body.getEquippedStack(s), Enchantments.PROTECTION);
        }
        return n;
    }

    private boolean blocked(Vec3d feet) {
        Box box = new Box(feet.x - 0.3, feet.y + 0.01, feet.z - 0.3, feet.x + 0.3, feet.y + 1.79, feet.z + 0.3);
        for (BlockPos b : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            if (!Pathfinder.solid(w, b)) continue;
            for (Box s : w.getBlockState(b).getCollisionShape(w, b).getBoundingBoxes()) if (s.offset(b).intersects(box)) return true;
        }
        return false;
    }

    /** Top of the highest solid block under any corner of the body. */
    private double groundBelow(Vec3d feet) {
        double best = feet.y - 40;
        for (double ox : new double[] {-0.29, 0.29}) {
            for (double oz : new double[] {-0.29, 0.29}) {
                BlockPos b = BlockPos.ofFloored(feet.x + ox, feet.y - 0.01, feet.z + oz);
                for (int i = 0; i < 40; i++) {
                    if (Pathfinder.solid(w, b)) {
                        double top = b.getY();
                        for (Box s : w.getBlockState(b).getCollisionShape(w, b).getBoundingBoxes()) top = Math.max(top, b.getY() + s.maxY);
                        best = Math.max(best, top);
                        break;
                    }
                    b = b.down();
                }
            }
        }
        return best;
    }

    public String debug() {
        return String.format(Locale.ROOT, "%s hp %.1f+%.1f totems %d crystals %d/%d theirs %d anchors %d hits %d smashes %d pearls %d gapples %d mined %d xp %d last '%s'",
                name, hp, absorption, totemCount(), crystalsPlaced, crystalsBroken, enemyCrystalsBroken, anchorsBlown, meleeHits, maceSmashes,
                pearlsThrown, gapplesEaten, blocksMined, bottlesThrown, lastAction) + " eatStarts " + eatStarts + " " + eatFail
                + String.format(Locale.ROOT, " [t %d next %d busy %d pearlAim %d retotem %d eat %d mining %s anchor %d air %s goal %s target %s alive %s]",
                bw == null ? -1 : bw.ticks(), nextActionAt, busyUntil, pearlAimUntil, retotemAt, eatingUntil, mining, anchorStage, air, goal,
                target == null ? "-" : target.name, alive()) + " plan[" + planInfo + "] pos " + BlockPos.ofFloored(pos) + " goalPos " + goalPos
                + " path " + (path == null ? "null" : path.size()) + " ground " + onGround;
    }
}
