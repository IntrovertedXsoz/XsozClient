package dev.xsoz.client.trainer.crystal;

import dev.xsoz.client.event.GameEvents;
import dev.xsoz.client.trainer.TrainingContext;
import dev.xsoz.client.trainer.Visibility;
import dev.xsoz.client.trainer.crystal.FightEvent.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Detects when you are in a fight, records it, and hands the finished recording to the analyser.
 *
 * <p>A fight starts after two interactions with the same player inside 5 seconds: a hit either
 * way, a crystal/anchor hit either way, a pop near you. It ends on a death, when they are gone for
 * 5 seconds, or after 15 seconds with no interaction. Everything recorded about the opponent is
 * something that happened on your screen (a hit, a pop animation, a pearl, a raised shield) -
 * their health, armour and totem count are never read.</p>
 */
public final class FightTracker implements GameEvents.Listener {
    static final int START_WINDOW = 100;
    /** No interaction for this long ends the fight, however close you still are (45 s). */
    static final int DISENGAGE_TICKS = 900;
    /** No interaction for this long AND more than FAR_BLOCKS apart ends it sooner (20 s). */
    static final int DISENGAGE_FAR_TICKS = 400;
    static final double FAR_BLOCKS = 32.0;
    /** Opponent out of your loaded area this long (30 s) - pearled off, ran, or got out of render. */
    static final int MISSING_TICKS = 600;
    static final int MIN_FIGHT_TICKS = 60;
    static final double START_RANGE = 16.0;

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Map<UUID, Interaction> interactions = new HashMap<>();
    private final Map<Integer, Long> myCrystals = new HashMap<>();
    private final Map<Integer, Long> oppPearls = new HashMap<>();
    private final Deque<Integer> recentCycles = new ArrayDeque<>();
    private final Deque<FightEvent> preFight = new ArrayDeque<>();

    private BiConsumer<FightRecord, FightReport> onFinished = (r, p) -> { };
    private boolean allowMobs = true;
    private TrainingContext.Mode mode = TrainingContext.Mode.SELF;

    long tick;
    private FightRecord current;
    private UUID opponentId;
    private long lastInteraction;
    private float lastOppHealth = 20f;
    private long missingSince = -1;

    // own state
    private boolean lastOffhandTotem;
    private float lastHealth = -1;
    private long pendingSelfExplosion = -100;
    private boolean selfEating;
    private long anchorPlacedAt = -1000;
    private long offhandEmptiedAt = -1;

    // opponent edges (what was visible)
    private boolean oppBlocking;
    private boolean oppEating;

    // pending crystal placement
    private long crystalUseTick = -100;
    private Vec3d crystalUsePos = Vec3d.ZERO;

    // read by the live coach
    long lastOppPearl = -1000;
    long lastOppPop = -1000;
    long lastOppXp = -1000;
    long lastSelfDamage = -1000;
    long lastDamageTaken = -1000;
    Snapshot self = new Snapshot();

    private static final class Interaction {
        int count;
        long first;
        long last;
    }

    /** The player's own state this tick. */
    public static final class Snapshot {
        public float hp;
        public float absorption;
        public boolean offhandTotem;
        public int totems;
        public int crystals;
        public int anchors;
        public int glowstone;
        public int gapples;
        public int pearls;
        public float armor = 1f;
    }

    public void setOnFinished(BiConsumer<FightRecord, FightReport> c) { this.onFinished = c; }


    public void setAllowMobs(boolean allow) { this.allowMobs = allow; }

    public void setMode(TrainingContext.Mode mode) { this.mode = mode; }

    public FightRecord current() { return current; }

    public boolean inFight() { return current != null; }

    public long tick() { return tick; }

    public Snapshot self() { return self; }

    public double averageRecentCycle() {
        if (recentCycles.size() < 4) return -1;
        double s = 0;
        for (int c : recentCycles) s += c;
        return s / recentCycles.size();
    }

    public LivingEntity opponent() {
        if (opponentId == null || mc.world == null) return null;
        for (PlayerEntity p : mc.world.getPlayers()) if (p.getUuid().equals(opponentId)) return p;
        if (allowMobsHere()) {
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof LivingEntity le && e.getUuid().equals(opponentId)) return le;
            }
        }
        return null;
    }

    /** Opponent pearl currently in flight, or null. */
    public Entity opponentPearl() {
        if (mc.world == null) return null;
        for (int id : oppPearls.keySet()) {
            Entity e = mc.world.getEntityById(id);
            if (e != null) return e;
        }
        return null;
    }

    private boolean allowMobsHere() {
        return allowMobs && (mc.isInSingleplayer() || mc.isIntegratedServerRunning());
    }

    // =========================================================================== tick

    public void tickClient() {
        ClientPlayerEntity me = mc.player;
        if (me == null || mc.world == null) {
            if (current != null) finish(FightRecord.Outcome.UNFINISHED);
            resetWorldState();
            return;
        }
        tick++;
        readSelf(me);
        if (dev.xsoz.client.training.TrainingManager.running()) {
            if (current != null) finish(FightRecord.Outcome.UNFINISHED);
            return;
        }

        // Your own death: the client sees it directly (health 0 / the death screen), whether or not
        // the server's death status reaches us first.
        if (current != null && (me.isDead() || me.getHealth() <= 0f || mc.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen)) {
            current.event(tick, Type.SELF_DEATH, 0, 0);
            finish(FightRecord.Outcome.LOSS);
        }

        if (current == null) {
            tryStart(me);
        } else {
            tickFight(me);
        }
        if (tick % 40 == 0) {
            interactions.values().removeIf(i -> tick - i.last > 200);
            myCrystals.values().removeIf(t -> tick - t > 200);
        }
    }

    private void readSelf(ClientPlayerEntity me) {
        Snapshot s = new Snapshot();
        s.hp = me.getHealth();
        s.absorption = me.getAbsorptionAmount();
        s.offhandTotem = me.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        var inv = me.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty()) continue;
            Item it = st.getItem();
            if (it == Items.TOTEM_OF_UNDYING) s.totems += st.getCount();
            else if (it == Items.END_CRYSTAL) s.crystals += st.getCount();
            else if (it == Items.RESPAWN_ANCHOR) s.anchors += st.getCount();
            else if (it == Items.GLOWSTONE) s.glowstone += st.getCount();
            else if (it == Items.GOLDEN_APPLE || it == Items.ENCHANTED_GOLDEN_APPLE) s.gapples += st.getCount();
            else if (it == Items.ENDER_PEARL) s.pearls += st.getCount();
        }
        float minArmor = 1f;
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack a = me.getEquippedStack(slot);
            if (!a.isEmpty() && a.isDamageable() && a.getMaxDamage() > 0) {
                minArmor = Math.min(minArmor, 1f - (float) a.getDamage() / a.getMaxDamage());
            }
        }
        s.armor = minArmor;

        // offhand edges (retotem timing)
        if (lastOffhandTotem && !s.offhandTotem) {
            offhandEmptiedAt = tick;
            log(tick, Type.OFFHAND_EMPTY, 0, s.totems);
        } else if (!lastOffhandTotem && s.offhandTotem) {
            log(tick, Type.OFFHAND_TOTEM, 0, 0);
            offhandEmptiedAt = -1;
        }
        lastOffhandTotem = s.offhandTotem;

        // self-damage: an HP drop within 2 ticks of your own explosion
        float total = s.hp + s.absorption;
        if (lastHealth >= 0 && total < lastHealth - 0.01f && tick - pendingSelfExplosion <= 2) {
            float lost = lastHealth - total;
            lastSelfDamage = tick;
            log(tick, Type.SELF_DAMAGE, lost, 0);
            pendingSelfExplosion = -100;
        }
        lastHealth = total;

        boolean eating = me.isUsingItem() && me.getActiveItem().get(DataComponentTypes.FOOD) != null;
        if (eating && !selfEating && current != null) current.event(tick, Type.SELF_EAT_START, 0, 0);
        selfEating = eating;
        self = s;
    }

    private void tryStart(ClientPlayerEntity me) {
        UUID best = null;
        int bestCount = 0;
        for (var e : interactions.entrySet()) {
            Interaction i = e.getValue();
            if (i.count >= 2 && tick - i.last <= START_WINDOW && i.count > bestCount) {
                best = e.getKey();
                bestCount = i.count;
            }
        }
        if (best == null) return;
        LivingEntity opp = find(best);
        if (opp == null || opp.squaredDistanceTo(me) > START_RANGE * START_RANGE) return;

        current = new FightRecord();
        current.id = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        current.startedAtEpochMs = System.currentTimeMillis();
        String server = TrainingContext.currentServer(mc);
        current.server = server == null ? "Singleplayer" : server;
        current.opponent = opp.getName().getString();
        current.opponentIsPlayer = opp instanceof PlayerEntity;
        current.trainingMode = mode.name();
        current.startTick = interactions.get(best).first;
        for (FightEvent e : preFight) if (e.tick() >= current.startTick) current.events.add(e);
        preFight.clear();
        opponentId = best;
        lastOppHealth = opp.getHealth();
        lastInteraction = tick;
        missingSince = -1;
        oppBlocking = false;
        oppEating = false;
        lastOffhandTotem = self.offhandTotem;
    }

    private void tickFight(ClientPlayerEntity me) {
        LivingEntity opp = opponent();
        float dist = -1;
        boolean visible = false;
        float dy = 0;
        if (opp == null) {
            if (missingSince < 0) missingSince = tick;
            if (lastOppHealth <= 0f) {
                // removed after reaching 0 health: that was a kill
                current.event(tick, Type.OPP_DEATH, 0, 0);
                finish(FightRecord.Outcome.WIN);
                return;
            }
            if (current.opponentIsPlayer && tick - missingSince > 20 && opponentLoggedOut()) {
                finish(FightRecord.Outcome.OPPONENT_LEFT);
                return;
            }
            if (tick - missingSince > MISSING_TICKS) {
                finish(FightRecord.Outcome.DISENGAGE);
                return;
            }
        } else {
            lastOppHealth = opp.getHealth();
            if (opp.isDead() || opp.getHealth() <= 0f) {
                current.event(tick, Type.OPP_DEATH, 0, 0);
                finish(FightRecord.Outcome.WIN);
                return;
            }
            missingSince = -1;
            dist = me.distanceTo(opp);
            visible = Visibility.opponentVisible(mc, opp);
            dy = (float) (opp.getY() - me.getY());
            boolean blocking = opp.isBlocking();
            if (blocking != oppBlocking) current.event(tick, blocking ? Type.OPP_SHIELD_UP : Type.OPP_SHIELD_DOWN, 0, 0);
            oppBlocking = blocking;
            boolean eating = opp.isUsingItem() && opp.getActiveItem().get(DataComponentTypes.FOOD) != null;
            if (eating && !oppEating) current.event(tick, Type.OPP_EAT_START, 0, 0);
            oppEating = eating;
            if (!opp.isAlive()) {
                finish(FightRecord.Outcome.WIN);
                return;
            }
        }

        if (tick % FightRecord.SAMPLE_EVERY == 0) {
            current.samples.add(new FightSample(tick, self.hp, self.absorption, self.offhandTotem, self.totems,
                    self.crystals, self.armor, dist, visible, dy));
        }

        long quiet = tick - lastInteraction;
        boolean far = dist < 0 || dist > FAR_BLOCKS;
        if (quiet > DISENGAGE_TICKS || (far && quiet > DISENGAGE_FAR_TICKS && opp != null)) {
            finish(FightRecord.Outcome.DISENGAGE);
        } else if (tick - current.startTick > 20 * 60 * 20) {
            finish(FightRecord.Outcome.UNFINISHED);
        }
    }

    /** True when a player opponent is no longer on the server's player list (they logged out). */
    private boolean opponentLoggedOut() {
        var net = mc.getNetworkHandler();
        return net != null && opponentId != null && net.getPlayerListEntry(opponentId) == null;
    }

    private void finish(FightRecord.Outcome outcome) {
        FightRecord r = current;
        current = null;
        opponentId = null;
        oppPearls.clear();
        if (r == null) return;
        r.endTick = tick;
        r.outcome = outcome;
        long meaningful = r.events.stream().filter(e -> switch (e.type()) {
            case DAMAGE_DEALT, DAMAGE_TAKEN, SELF_POP, OPP_POP, SELF_CRYSTAL_PLACE, MELEE_HIT -> true;
            default -> false;
        }).count();
        if (r.durationTicks() < MIN_FIGHT_TICKS || meaningful < 3) return;
        FightReport report = FightAnalyzer.analyze(r);
        onFinished.accept(r, report);
    }

    private void resetWorldState() {
        interactions.clear();
        myCrystals.clear();
        oppPearls.clear();
        recentCycles.clear();
        preFight.clear();
        lastHealth = -1;
        lastOffhandTotem = false;
        opponentId = null;
        current = null;
    }

    // =========================================================================== interactions

    private void interact(Entity e) {
        if (!(e instanceof LivingEntity le) || e == mc.player) return;
        // training dummies and drill sessions are never fights
        if (e instanceof net.minecraft.entity.decoration.MannequinEntity || dev.xsoz.client.training.TrainingManager.running()) return;
        boolean isPlayer = e instanceof PlayerEntity;
        if (!isPlayer && (!allowMobsHere() || e instanceof ArmorStandEntity)) return;
        if (isPlayer && ((PlayerEntity) e).isSpectator()) return;
        Interaction i = interactions.computeIfAbsent(e.getUuid(), k -> new Interaction());
        if (tick - i.last > START_WINDOW) {
            i.count = 0;
            i.first = tick;
        }
        i.count++;
        i.last = tick;
        if (current != null && e.getUuid().equals(opponentId)) lastInteraction = tick;
    }

    /** Writes to the fight, or to the pre-fight buffer so the opening exchange is not lost. */
    private void log(long t, Type type, double a, double b) {
        if (current != null) {
            current.event(t, type, a, b);
            return;
        }
        preFight.addLast(new FightEvent(t, type, a, b));
        while (!preFight.isEmpty() && t - preFight.peekFirst().tick() > START_WINDOW) preFight.removeFirst();
    }

    private boolean isOpponent(Entity e) { return current != null && e != null && e.getUuid().equals(opponentId); }

    private LivingEntity find(UUID id) {
        if (mc.world == null) return null;
        for (PlayerEntity p : mc.world.getPlayers()) if (p.getUuid().equals(id)) return p;
        for (Entity e : mc.world.getEntities()) if (e instanceof LivingEntity le && e.getUuid().equals(id)) return le;
        return null;
    }

    private Entity nearestFighter(Vec3d pos, double range) {
        Entity best = null;
        double bestD = range * range;
        if (mc.world == null) return null;
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || !(e instanceof LivingEntity)) continue;
            if (!(e instanceof PlayerEntity) && !allowMobsHere()) continue;
            double d = e.squaredDistanceTo(pos);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    // =========================================================================== events

    @Override
    public void onAttack(Entity target) {
        if (mc.player == null) return;
        if (target instanceof EndCrystalEntity crystal) {
            pendingSelfExplosion = tick;
            Long placedAt = myCrystals.remove(crystal.getId());
            if (placedAt != null) {
                int cycle = (int) (tick - placedAt);
                if (cycle <= FightAnalyzer.MAX_CYCLE_TICKS) {
                    recentCycles.addLast(cycle);
                    while (recentCycles.size() > 6) recentCycles.removeFirst();
                }
            }
            log(tick, Type.SELF_CRYSTAL_BREAK, crystal.getId(), 0);
            Entity near = nearestFighter(new Vec3d(crystal.getX(), crystal.getY(), crystal.getZ()), 7);
            if (near != null) interact(near);
            return;
        }
        if (target instanceof LivingEntity) {
            interact(target);
            if (current == null || isOpponent(target)) log(tick, Type.MELEE_HIT, 0, 0);
        }
    }

    @Override
    public void onUseBlock(Hand hand, BlockHitResult hit, ItemStack held) {
        if (mc.world == null || held == null) return;
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.world.getBlockState(pos);
        if (held.isOf(Items.END_CRYSTAL)) {
            crystalUseTick = tick;
            crystalUsePos = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
            return;
        }
        if (state.isOf(Blocks.RESPAWN_ANCHOR)) {
            int charges = state.get(RespawnAnchorBlock.CHARGES);
            if (held.isOf(Items.GLOWSTONE) && charges < 4) {
                log(tick, Type.SELF_ANCHOR_CHARGE, 0, 0);
            } else if (charges > 0 && !held.isOf(Items.GLOWSTONE)) {
                pendingSelfExplosion = tick;
                log(tick, Type.SELF_ANCHOR_BLOW, 0, 0);
                anchorPlacedAt = -1000;
            }
            return;
        }
        if (held.isOf(Items.RESPAWN_ANCHOR)) {
            anchorPlacedAt = tick;
            log(tick, Type.SELF_ANCHOR_PLACE, 0, 0);
        }
    }

    @Override
    public void onEntityLoad(Entity e) {
        if (mc.player == null) return;
        if (e instanceof EndCrystalEntity) {
            Vec3d p = new Vec3d(e.getX(), e.getY(), e.getZ());
            if (tick - crystalUseTick <= 4 && p.squaredDistanceTo(crystalUsePos) <= 4.0) {
                myCrystals.put(e.getId(), tick);
                log(tick, Type.SELF_CRYSTAL_PLACE, e.getId(), 0);
            } else if (current != null) {
                LivingEntity opp = opponent();
                if (opp != null && opp.squaredDistanceTo(p) <= 36) current.event(tick, Type.OPP_CRYSTAL_PLACE, e.getId(), 0);
            }
        } else if (e instanceof EnderPearlEntity pearl) {
            Entity owner = pearl.getOwner();
            if (owner == mc.player) {
                log(tick, Type.SELF_PEARL, 0, 0);
            } else if (isOpponent(owner)) {
                current.event(tick, Type.OPP_PEARL, 0, 0);
                oppPearls.put(pearl.getId(), tick);
                lastOppPearl = tick;
            }
        } else if (e instanceof ExperienceBottleEntity bottle) {
            Entity owner = bottle.getOwner();
            if (owner == mc.player) {
                log(tick, Type.SELF_XP, 0, 0);
            } else if (isOpponent(owner)) {
                current.event(tick, Type.OPP_XP, 0, 0);
                lastOppXp = tick;
            }
        }
    }

    @Override
    public void onEntityUnload(Entity e) {
        if (e instanceof EnderPearlEntity && oppPearls.remove(e.getId()) != null && current != null && mc.player != null) {
            current.event(tick, Type.OPP_PEARL_LAND, Math.sqrt(mc.player.squaredDistanceTo(e.getX(), e.getY(), e.getZ())), 0);
        }
        if (e instanceof EndCrystalEntity) {
            Iterator<Map.Entry<Integer, Long>> it = myCrystals.entrySet().iterator();
            while (it.hasNext()) if (it.next().getKey() == e.getId()) it.remove();
        }
    }

    @Override
    public void onEntityStatus(Entity e, byte status) {
        if (mc.player == null) return;
        if (status == 35) { // totem pop
            if (e == mc.player) {
                log(tick, Type.SELF_POP, 0, 0);
            } else if (e instanceof LivingEntity) {
                if (isOpponent(e)) {
                    current.event(tick, Type.OPP_POP, 0, 0);
                    lastOppPop = tick;
                    lastInteraction = tick;
                } else if (current == null && e.squaredDistanceTo(mc.player) <= 144) {
                    interact(e);
                    log(tick, Type.OPP_POP, 0, 0);
                }
            }
        } else if (status == 3) { // death
            if (e == mc.player && current != null) {
                current.event(tick, Type.SELF_DEATH, 0, 0);
                finish(FightRecord.Outcome.LOSS);
            } else if (isOpponent(e)) {
                current.event(tick, Type.OPP_DEATH, 0, 0);
                finish(FightRecord.Outcome.WIN);
            }
        }
    }

    @Override
    public void onEntityDamage(Entity target, String type, int causeId, int directId) {
        if (mc.player == null || mc.world == null) return;
        int code = damageCode(type);
        Entity cause = causeId >= 0 ? mc.world.getEntityById(causeId) : null;
        if (target == mc.player) {
            lastDamageTaken = tick;
            if (cause == mc.player && (code == FightEvent.DMG_CRYSTAL || code == FightEvent.DMG_ANCHOR)) pendingSelfExplosion = tick;
            if (cause != null && cause != mc.player) interact(cause);
            log(tick, Type.DAMAGE_TAKEN, code, 0);
            return;
        }
        if (cause == mc.player && target instanceof LivingEntity) {
            interact(target);
            if (current == null || isOpponent(target)) log(tick, Type.DAMAGE_DEALT, code, 0);
        } else if (code == FightEvent.DMG_ANCHOR && isOpponent(target) && tick - pendingSelfExplosion <= 2) {
            // anchor explosions carry no attacker; credit the one you just detonated
            current.event(tick, Type.DAMAGE_DEALT, code, 0);
            lastInteraction = tick;
        }
    }

    @Override
    public void onMissClick() {
        if (current != null) log(tick, Type.MISS_CLICK, 0, 0);
    }

    @Override
    public void onWorldLeave() {
        if (current != null) finish(FightRecord.Outcome.UNFINISHED);
        resetWorldState();
    }

    static int damageCode(String type) {
        if (type == null) return FightEvent.DMG_OTHER;
        if (type.equals("bad_respawn_point")) return FightEvent.DMG_ANCHOR;
        if (type.contains("explosion")) return FightEvent.DMG_CRYSTAL;
        if (type.equals("player_attack") || type.equals("mob_attack") || type.equals("mob_attack_no_aggro")) return FightEvent.DMG_MELEE;
        return FightEvent.DMG_OTHER;
    }

    /** True when the opponent's feet block is walled in on all four sides by blast-proof blocks. */
    public boolean opponentInHole() {
        LivingEntity opp = opponent();
        if (opp == null || mc.world == null) return false;
        BlockPos feet = opp.getBlockPos();
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockState s = mc.world.getBlockState(feet.offset(d));
            if (s.getBlock().getBlastResistance() < 600f) return false;
        }
        return true;
    }
}
