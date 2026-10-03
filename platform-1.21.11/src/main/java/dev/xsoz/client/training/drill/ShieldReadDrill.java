package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.CombatMath;
import dev.xsoz.client.training.DrillDef;
import java.util.Locale;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * The dummy really blocks: it raises its shield (the vanilla "using a shield" state, visible to
 * the player) and keeps turning to face you, like a real player would. A raised shield blocks any
 * explosion whose source is in its front half (BlocksAttacksComponent, 90 degrees either side), so
 * the anchor has to go beside or behind it - placed from the front, around the dummy.
 *
 * <p>Every detonation is scored like Best Crystal: net damage (dummy minus you, both in your kit)
 * against the best anchor spot available from where you stood, plus your own damage rating.</p>
 */
public final class ShieldReadDrill extends Drill {
    private int size;
    private BlockPos origin;
    private MannequinEntity dummy;
    private Vec3d dummyAt;
    private Vec3d dummyGoal;
    private float dummyYaw;
    private BlockPos bestSpot;
    private float bestNet;
    private int sceneAt;
    private int reviewUntil = -1;
    private int nextSwingAt;
    private Vec3d lastSeen;
    private boolean shieldUp = true;
    private int shieldToggleAt;
    public volatile String liveLine = "";
    public volatile int liveColor = 0xFFECEEF2;
    public volatile boolean shownShieldDown;

    public ShieldReadDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.SHIELD_READ, mode, p, hints);
    }

    // ------------------------------------------------------------------ GameTest access

    public BlockPos bestSpotForTests() { return bestSpot; }

    public boolean bestSpotBlockedForTests() {
        return bestSpot != null && CombatMath.shieldBlocks(dummyAt.x, dummyAt.z, dummyYaw, bestSpot.getX() + 0.5, bestSpot.getZ() + 0.5);
    }

    public MannequinEntity dummyForTests() { return dummy; }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        size = level >= 3 ? 25 : level == 2 ? 17 : 11;
        origin = pl.getBlockPos().add(-size / 2, -1, -size / 2);
        s.snapshotBox(origin.add(-3, -1, -3), origin.add(size + 2, 7, size + 2));
        giveKit(pl, GameMode.SURVIVAL);
        newScene();
    }

    private void newScene() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                set(origin.add(x, 0, z), Blocks.STONE.getDefaultState());
                for (int y = 1; y <= 5; y++) set(origin.add(x, y, z), Blocks.AIR.getDefaultState());
            }
        }
        if (level >= 3) buildWalls();
        dummyAt = Vec3d.ofBottomCenter(origin.add(size / 2, 1, size / 2));
        // the player starts 4 blocks away, never inside a wall
        Vec3d playerAt = null;
        for (int tries = 0; tries < 36 && playerAt == null; tries++) {
            float ang = randBetween(0, 359);
            Vec3d at = Vec3d.ofBottomCenter(BlockPos.ofFloored(dummyAt.add(-Math.sin(Math.toRadians(ang)) * 4, 0, Math.cos(Math.toRadians(ang)) * 4)));
            if (Scene.standable(s.world, BlockPos.ofFloored(at))) playerAt = at;
        }
        if (playerAt == null) {
            playerAt = dummyAt.add(0, 0, 4);
            BlockPos f = BlockPos.ofFloored(playerAt);
            set(f, Blocks.AIR.getDefaultState());
            set(f.up(), Blocks.AIR.getDefaultState());
        }
        dummyGoal = dummyAt;
        dummyYaw = Scene.yawTowards(dummyAt, playerAt);
        if (dummy == null || dummy.isRemoved()) {
            dummy = Scene.spawnDummy(s, dummyAt, dummyYaw);
            if (dummy != null) {
                dummy.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                dummy.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
            }
        }
        Scene.pin(dummy, dummyAt, dummyYaw);
        Scene.teleport(pl, playerAt, Scene.yawTowards(playerAt, dummyAt), 25f);
        shieldUp = true;
        shieldToggleAt = ticks + randBetween(30, 70);
        pl.changeGameMode(GameMode.SURVIVAL);
        sceneAt = ticks;
        reviewUntil = -1;
        bestSpot = null;
        computeBest(pl);
        refillKit();
        cue();
        lastSeen = playerAt;
        status = "It blocks toward you. Anchor it from the side or back.";
    }

    /** Level 3: wall pieces the dummy backs into, so "behind it" is not always possible. */
    private void buildWalls() {
        int walls = randBetween(7, 11);
        for (int w = 0; w < walls; w++) {
            int x = randBetween(1, size - 2);
            int z = randBetween(1, size - 2);
            if (Math.abs(x - size / 2) <= 1 && Math.abs(z - size / 2) <= 1) continue;
            boolean alongX = rng.nextBoolean();
            int len = randBetween(3, 5);
            for (int i = 0; i < len; i++) {
                int cx = alongX ? x + i : x;
                int cz = alongX ? z : z + i;
                if (cx < 0 || cz < 0 || cx >= size || cz >= size) continue;
                if (Math.abs(cx - size / 2) <= 1 && Math.abs(cz - size / 2) <= 1) continue;
                for (int y = 1; y <= 2; y++) set(origin.add(cx, y, cz), Blocks.OBSIDIAN.getDefaultState());
            }
        }
    }

    /** Best anchor spot from where the player stands right now, given the shield state. */
    private void computeBest(ServerPlayerEntity pl) {
        bestSpot = null;
        bestNet = 0;
        if (dummy == null) return;
        Box db = dummy.getBoundingBox();
        Box pb = pl.getBoundingBox();
        Vec3d eye = pl.getEyePos();
        Scene.Armour mine = Scene.armourOf(s.world, pl);
        BlockPos dc = BlockPos.ofFloored(dummyAt);
        for (BlockPos a : BlockPos.iterate(dc.add(-5, -1, -5), dc.add(5, 2, 5))) {
            if (!s.world.getBlockState(a).isAir() || s.world.getBlockState(a.down()).isAir()) continue;
            if (new Box(a).intersects(db) || new Box(a).intersects(pb)) continue;
            if (Scene.reachTo(eye, a) > 4.5) continue;
            Vec3d c = Vec3d.ofCenter(a);
            if (shieldUp && CombatMath.shieldBlocks(dummyAt.x, dummyAt.z, dummyYaw, c.x, c.z)) continue;
            float t = Scene.damage(c, CombatMath.ANCHOR_POWER, dummy, mine);
            float self = Scene.damage(c, CombatMath.ANCHOR_POWER, pl, mine);
            if (t - self > bestNet) {
                bestNet = t - self;
                bestSpot = a.toImmutable();
            }
        }
    }

    @Override
    protected void tick() {
        ServerPlayerEntity pl = player();
        if (pl == null || dummy == null) return;
        Vec3d me = new Vec3d(pl.getX(), pl.getY(), pl.getZ());
        moveDummy(me);
        behave(pl, me);
        Scene.pin(dummy, dummyAt, dummyYaw);
        updateShield();
        shownShieldDown = !shieldUp;
        if (reviewUntil >= 0) {
            if (bestSpot != null && ticks % 3 == 0) Scene.particles(s.world, ParticleTypes.END_ROD, Vec3d.ofCenter(bestSpot), 4, 0.2);
            if (ticks >= reviewUntil) newScene();
            return;
        }
        if ((ticks - sceneAt) / 20.0 > speed()) {
            computeBest(pl);
            rep(false, 0, "Speed: out of time - best spot shown");
            pl.changeGameMode(GameMode.ADVENTURE);
            reviewUntil = ticks + 40;
        }
    }

    /**
     * Level 2+: it plays like a person. It faces the danger - you, or an anchor or crystal you put
     * next to it - so the shield points at it, and backs away from it (slowly: a raised shield
     * slows you to a fifth). It loses you behind walls and looks around for you. And when its shield
     * is down and you are close, it swings at you.
     */
    private void behave(ServerPlayerEntity pl, Vec3d me) {
        Vec3d eye = dummyAt.add(0, 1.62, 0);
        boolean sees = s.world.raycast(new net.minecraft.world.RaycastContext(eye, pl.getEyePos(),
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, dummy)).getType()
                == net.minecraft.util.hit.HitResult.Type.MISS;
        if (sees) lastSeen = me;
        if (level < 2) {
            dummyYaw = Scene.yawTowards(dummyAt, me);
            return;
        }
        Vec3d threat = nearestThreat();
        Vec3d away = null;
        if (threat != null) {
            dummyYaw = Scene.yawTowards(dummyAt, threat);
            away = dummyAt.subtract(threat);
        } else if (sees) {
            dummyYaw = Scene.yawTowards(dummyAt, me);
            if (dummyAt.distanceTo(me) < 3.2 && shieldUp) away = dummyAt.subtract(me);
        } else {
            // lost you: look around, walk to where you were last seen
            dummyYaw += 7f;
            if (lastSeen != null && dummyAt.distanceTo(lastSeen) > 1.5) stepTowards(lastSeen.subtract(dummyAt), false);
        }
        if (away != null) stepTowards(new Vec3d(away.x, 0, away.z), true);
        // shield down and you in reach: it hits you
        if (!shieldUp && sees && dummyAt.distanceTo(me) <= 3.2 && ticks >= nextSwingAt) {
            dummy.swingHand(Hand.MAIN_HAND);
            pl.damage(s.world, s.world.getDamageSources().mobAttack(dummy), 8f);
            nextSwingAt = ticks + 13;
            feedback("It hit you while its shield was down", 0xFFFFB020);
        }
    }

    /** An anchor or crystal within 4.5 blocks of it. */
    private Vec3d nearestThreat() {
        Vec3d best = null;
        double bd = 4.5;
        BlockPos c = BlockPos.ofFloored(dummyAt);
        for (BlockPos b : BlockPos.iterate(c.add(-4, -1, -4), c.add(4, 2, 4))) {
            if (!s.world.getBlockState(b).isOf(Blocks.RESPAWN_ANCHOR)) continue;
            double d = Vec3d.ofCenter(b).distanceTo(dummyAt);
            if (d < bd) {
                bd = d;
                best = Vec3d.ofCenter(b);
            }
        }
        for (var cr : s.world.getEntitiesByClass(net.minecraft.entity.decoration.EndCrystalEntity.class, dummy.getBoundingBox().expand(4.5), x -> true)) {
            double d = cr.getEntityPos().distanceTo(dummyAt);
            if (d < bd) {
                bd = d;
                best = cr.getEntityPos();
            }
        }
        return best;
    }

    /** One tick of walking: 4.3 b/s, or a fifth of that with the shield raised. Never into a wall or off an edge. */
    private void stepTowards(Vec3d dir, boolean backing) {
        if (dir.lengthSquared() < 1e-6) return;
        double speed = (shieldUp ? 0.86 : 4.3) / 20.0;
        Vec3d next = dummyAt.add(dir.normalize().multiply(speed));
        BlockPos f = BlockPos.ofFloored(next);
        if (!Scene.standable(s.world, f) || f.getY() != BlockPos.ofFloored(dummyAt).getY()) return;
        if (Math.abs(f.getX() - origin.getX() - size / 2) > size / 2 - 1 || Math.abs(f.getZ() - origin.getZ() - size / 2) > size / 2 - 1) return;
        dummyAt = next;
    }

    /** Raises (or lowers) the shield for real: the dummy is "using" it, as a player holding right click. */
    private void updateShield() {
        if (level >= 2 && ticks >= shieldToggleAt) {
            shieldUp = !shieldUp;
            // you hear it: the shield going down is your opening
            if (!shieldUp) sound(net.minecraft.sound.SoundEvents.ITEM_ARMOR_EQUIP_GENERIC.value(), 0.6f);
            else sound(net.minecraft.sound.SoundEvents.ITEM_SHIELD_BLOCK.value(), 1.2f);
            // drops are short and get shorter as the speed goes up
            int down = (int) Math.round(18 - 10 * p());
            shieldToggleAt = ticks + (shieldUp ? randBetween(30, 70) : Math.max(6, down + randBetween(-2, 4)));
        }
        if (shieldUp) {
            if (!dummy.isUsingItem()) dummy.setCurrentHand(Hand.OFF_HAND);
        } else if (dummy.isUsingItem()) {
            dummy.clearActiveItem();
        }
    }

    /** Level 3: walk (3 b/s) to spots with a wall behind, as seen from the player. */
    private void moveDummy(Vec3d me) {
        if (level < 3) return;
        if (dummyGoal == null || dummyAt.distanceTo(dummyGoal) < 0.2) {
            if (rng.nextInt(20) == 0) dummyGoal = pickCoverSpot(me);
            return;
        }
        Vec3d d = dummyGoal.subtract(dummyAt);
        // walking speed, a fifth of it with the shield up
        double step = (shieldUp ? 0.86 : 4.3) / 20.0;
        dummyAt = d.length() <= step ? dummyGoal : dummyAt.add(d.normalize().multiply(step));
    }

    private Vec3d pickCoverSpot(Vec3d me) {
        for (int guard = 0; guard < 40; guard++) {
            int x = randBetween(1, size - 2);
            int z = randBetween(1, size - 2);
            BlockPos f = origin.add(x, 1, z);
            if (!Scene.standable(s.world, f)) continue;
            Vec3d at = Vec3d.ofBottomCenter(f);
            double dist = at.distanceTo(me);
            if (dist < 3 || dist > 7) continue;
            Vec3d away = at.subtract(me).normalize();
            BlockPos behind = BlockPos.ofFloored(at.add(away.multiply(1.0)));
            if (s.world.getBlockState(behind).isAir()) continue;
            // straight-line path must be clear
            if (!clearPath(dummyAt, at)) continue;
            return at;
        }
        return dummyAt;
    }

    private boolean clearPath(Vec3d a, Vec3d b) {
        Vec3d d = b.subtract(a);
        int n = (int) Math.ceil(d.length() * 3);
        for (int i = 1; i <= n; i++) {
            BlockPos p = BlockPos.ofFloored(a.add(d.multiply(i / (double) n)));
            if (!Scene.standable(s.world, p)) return false;
        }
        return true;
    }

    @Override
    public boolean onAnchorExplode(BlockPos pos) {
        ServerPlayerEntity pl = player();
        // the game removes the anchor before it explodes - so must the simulation
        s.setBlock(pos, Blocks.AIR.getDefaultState());
        if (reviewUntil >= 0 || pl == null) return true;
        computeBest(pl);
        Vec3d c = Vec3d.ofCenter(pos);
        Scene.Armour mine = Scene.armourOf(s.world, pl);
        boolean blocked = dummy.isBlocking() && CombatMath.shieldBlocks(dummyAt.x, dummyAt.z, dummyYaw, c.x, c.z);
        float t = blocked ? 0f : Scene.damage(c, CombatMath.ANCHOR_POWER, dummy, mine);
        float self = Scene.damage(c, CombatMath.ANCHOR_POWER, pl, mine);
        float net = t - self;
        float q = bestNet <= 0.1f ? (net > 0 ? 1f : 0f) : Math.max(0f, Math.min(1.25f, net / bestNet));
        int rating = CombatMath.anchorSelfRating(self);
        boolean inTime = (ticks - sceneAt) / 20.0 <= speed();
        boolean ok = !blocked && q >= 0.8f && rating < 2 && inTime;
        String note;
        if (blocked) note = "Placement: blocked by the shield - go beside or behind it";
        else if (q < 0.8f) note = String.format(Locale.ROOT, "Placement: %d%% of the best net damage (dummy %.1f, you %.1f)", Math.round(q * 100), t, self);
        else if (rating == 2) note = String.format(Locale.ROOT, "Safety: too close - you'd take %.1f", self);
        else if (!inTime) note = "Speed: too slow";
        else note = String.format(Locale.ROOT, "%d%% - dummy %.1f, you %.1f (%s)", Math.round(q * 100), t, self, CombatMath.ANCHOR_RATING[rating]);
        liveLine = String.format(Locale.ROOT, "Dummy took %.1f, you took %.1f", t, self);
        liveColor = ok ? Theme.accent() : 0xFFFFB020;
        rep(ok, q, note);
        reviewUntil = ticks + 40;
        return true;
    }

    @Override
    public void renderExtra(DrawContext c, int x, int y) {
        String l = liveLine;
        if (!l.isEmpty()) Gfx.text(c, l, x, y, liveColor);
        if (level >= 2 && shownShieldDown) Gfx.textBold(c, "SHIELD DOWN - front is open!", x, y + 11, 0xFF4CD765);
    }
}
