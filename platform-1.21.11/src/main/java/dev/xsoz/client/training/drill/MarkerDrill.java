package dev.xsoz.client.training.drill;

import dev.xsoz.client.training.DrillDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * Shared base for the speed drills that mark a spot: Obsidian + Crystal and Anchor Chain.
 *
 * <p>A 15x15 stone arena. Each spot is shown as a LIME CONCRETE block (plus sparkles): the
 * obsidian or anchor goes directly on top of it. After every spot the whole arena is reset to
 * clean stone - every block the player placed, every crystal, every dropped item - and the player
 * is put back in the middle (keeping their view). Out of time: a short Adventure lock (no
 * placing) and a new spot.</p>
 */
public abstract class MarkerDrill extends Drill {
    private static final int R = 7;
    protected BlockPos base;
    /** The block(s) where the obsidian / anchor must go (each sits on a lime block). */
    protected final List<BlockPos> targets = new ArrayList<>();
    protected int markerAt;
    private int nextAt;
    private int lockedUntil = -1;
    private Direction facing;

    protected MarkerDrill(DrillDef def, Mode mode, double p, boolean hints) {
        super(def, mode, p, hints);
    }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        base = pl.getBlockPos();
        facing = pl.getHorizontalFacing();
        s.snapshotBox(base.add(-R - 2, -4, -R - 2), base.add(R + 2, 7, R + 2));
        resetArena();
        giveKit(pl, GameMode.SURVIVAL);
        Scene.teleport(pl, Vec3d.ofBottomCenter(base), facing.getPositiveHorizontalDegrees(), 35f);
        nextAt = 30;
        status = intro();
    }

    /** For GameTests: the first current target (the block on top of the lime block), or null. */
    public BlockPos markerForTests() { return targets.isEmpty() ? null : targets.get(0); }

    public List<BlockPos> targetsForTests() { return List.copyOf(targets); }

    protected abstract String intro();

    /** Called every tick while spots are up; return true once every spot is complete. */
    protected abstract boolean complete();

    /** The rep for a completed spot. Default: in time = hit. */
    protected void scoreComplete(double seconds) {
        rep(seconds <= window(), seconds, String.format(Locale.ROOT, "%.2f s", seconds));
    }

    /** Seconds allowed for the current spot(s). */
    protected double window() { return speed() * Math.max(1, spotsPerRound()); }

    /** How many lime blocks to show at once (1, or 2 for Obsidian + Crystal level 3). */
    protected int spotsPerRound() { return 1; }

    /** Clean stone floor, air above, the layer under the floor as it was. */
    protected final void resetArena() {
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                BlockPos col = base.add(x, 0, z);
                s.revert(col.down(3));
                s.revert(col.down(2));
                set(col.down(), Blocks.STONE.getDefaultState());
                for (int y = 0; y <= 5; y++) set(col.up(y), Blocks.AIR.getDefaultState());
            }
        }
        Box box = new Box(base).expand(R + 2, 6, R + 2);
        for (EndCrystalEntity c : s.world.getEntitiesByClass(EndCrystalEntity.class, box, c -> true)) c.discard();
        for (ItemEntity i : s.world.getEntitiesByClass(ItemEntity.class, box, i -> true)) i.discard();
    }

    @Override
    protected void tick() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        if (lockedUntil >= 0 && ticks >= lockedUntil) {
            pl.changeGameMode(GameMode.SURVIVAL);
            lockedUntil = -1;
        }
        if (targets.isEmpty()) {
            if (ticks >= nextAt && lockedUntil < 0) newSpots(pl);
            return;
        }
        if (ticks % 4 == 0) {
            for (BlockPos t : targets) {
                if (s.world.getBlockState(t).isAir()) Scene.particles(s.world, ParticleTypes.HAPPY_VILLAGER, Vec3d.ofCenter(t), 4, 0.3);
            }
        }
        onSpotsTick();
        double seconds = (ticks - markerAt) / 20.0;
        if (complete()) {
            scoreComplete(seconds);
            nextSpot();
        } else if (seconds > window()) {
            rep(false, seconds, "Out of time");
            pl.changeGameMode(GameMode.ADVENTURE);
            lockedUntil = ticks + 12;
            nextSpot();
        }
    }

    private void nextSpot() {
        targets.clear();
        onSpotReset();
        resetArena();
        refillKit();
        ServerPlayerEntity pl = player();
        if (pl != null && pl.squaredDistanceTo(Vec3d.ofBottomCenter(base)) > 0.5) Scene.teleportKeepLook(pl, Vec3d.ofBottomCenter(base));
        nextAt = ticks + randBetween(8, 24);
        status = "";
    }

    protected void onSpotReset() { }

    /** Every tick while spots are up (moving spots live here). */
    protected void onSpotsTick() { }

    /**
     * Slides spot i one block sideways (the lime block moves with it), staying 2-6 blocks from the
     * middle and clear of the player and the other spots. Only for flat spots.
     */
    protected final void slideSpot(int i) {
        BlockPos t = targets.get(i);
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (int tries = 0; tries < 8; tries++) {
            Direction d = dirs[rng.nextInt(4)];
            BlockPos n = t.offset(d);
            int dx = n.getX() - base.getX();
            int dz = n.getZ() - base.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 2 || dist > 6) continue;
            if (!s.world.getBlockState(n).isAir() || !s.world.getBlockState(n.down()).isOf(Blocks.STONE)) continue;
            ServerPlayerEntity pl = player();
            if (pl != null && new net.minecraft.util.math.Box(n).intersects(pl.getBoundingBox())) continue;
            boolean clash = false;
            for (int j = 0; j < targets.size(); j++) if (j != i && targets.get(j).getManhattanDistance(n) <= 1) clash = true;
            if (clash) continue;
            set(t.down(), Blocks.STONE.getDefaultState());
            set(n.down(), Blocks.LIME_CONCRETE.getDefaultState());
            targets.set(i, n.toImmutable());
            return;
        }
    }

    private void newSpots(ServerPlayerEntity pl) {
        int n = spotsPerRound();
        for (int i = 0; i < n; i++) {
            BlockPos t = pickSpot(i);
            if (t != null) targets.add(t);
        }
        markerAt = ticks;
        cue();
        onNewMarker();
    }

    /** Builds the lime block for one spot and returns the target above it. */
    protected BlockPos pickSpot(int index) {
        boolean anyDir = level >= 2;
        for (int tries = 0; tries < 40; tries++) {
            int dx;
            int dz;
            if (anyDir) {
                double ang = rng.nextDouble() * Math.PI * 2;
                double dist = 2 + rng.nextDouble() * 3;
                dx = (int) Math.round(Math.cos(ang) * dist);
                dz = (int) Math.round(Math.sin(ang) * dist);
            } else {
                Direction right = facing.rotateYClockwise();
                int dist = randBetween(2, 4);
                int side = randBetween(-2, 2);
                dx = facing.getOffsetX() * dist + right.getOffsetX() * side;
                dz = facing.getOffsetZ() * dist + right.getOffsetZ() * side;
            }
            if (dx * dx + dz * dz < 4 || Math.abs(dx) > R - 1 || Math.abs(dz) > R - 1) continue;
            BlockPos col = base.add(dx, 0, dz);
            boolean clash = false;
            for (BlockPos t : targets) if (Math.abs(t.getX() - col.getX()) <= 1 && Math.abs(t.getZ() - col.getZ()) <= 1) clash = true;
            if (clash) continue;
            int h = heightFor(index);
            BlockPos lime = col.down().up(h);
            if (h > 0) for (int y = 0; y < h; y++) set(col.up(y - 1), Blocks.STONE.getDefaultState());
            if (h < 0) for (int y = h; y < 0; y++) set(col.up(y), Blocks.AIR.getDefaultState());
            set(lime, Blocks.LIME_CONCRETE.getDefaultState());
            return lime.up();
        }
        return null;
    }

    /** Height of the lime block relative to the floor: 0 on level 1, -1..+1 from level 2. */
    protected int heightFor(int index) { return level >= 2 ? randBetween(-1, 1) : 0; }

    protected void onNewMarker() { }
}
