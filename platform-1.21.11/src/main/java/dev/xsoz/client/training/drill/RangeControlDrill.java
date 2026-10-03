package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.DrillDef;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * The dummy strafes, rushes and retreats around the arena. Stay 3-6 blocks from it (crystal
 * range) for a 20 s run. Speed goes up to 5.6 b/s - vanilla sprinting.
 */
public final class RangeControlDrill extends Drill {
    private static final int RUN_TICKS = 400;
    private MannequinEntity dummy;
    private Vec3d center;
    private Vec3d pos;
    private Vec3d target;
    private int retargetAt;
    private int runStart = -1;
    private int restUntil;
    private int inRange;
    private int counted;
    public volatile double liveDistance = -1;
    // level 2+: sprint bursts, jumps, looking around
    private int sprintUntil;
    private double jumpY;
    private double jumpV;
    private float lookYaw;
    private int lookUntil;

    public RangeControlDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.RANGE_CONTROL, mode, p, hints);
    }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        giveKit(pl, GameMode.ADVENTURE);
        Vec3d look = pl.getRotationVector();
        center = new Vec3d(pl.getX() + look.x * 5, pl.getY(), pl.getZ() + look.z * 5);
        // flat, clear ground for the whole chase
        net.minecraft.util.math.BlockPos c = net.minecraft.util.math.BlockPos.ofFloored(center);
        s.snapshotBox(c.add(-16, -2, -16), c.add(16, 4, 16));
        floor(c.add(-15, -1, -15), 31, 31, net.minecraft.block.Blocks.STONE.getDefaultState());
        clearAbove(c.add(-15, -1, -15), 31, 31, 4);
        pos = center;
        target = center;
        dummy = Scene.spawnDummy(s, pos, 0f);
        restUntil = 40;
        status = "Stay 3-6 blocks from the dummy. Run starts in 2 s.";
    }

    @Override
    protected void tick() {
        ServerPlayerEntity pl = player();
        if (pl == null || dummy == null) return;
        Vec3d me = new Vec3d(pl.getX(), pl.getY(), pl.getZ());
        if (ticks >= retargetAt || pos.distanceTo(target) < 0.3) {
            int roll = rng.nextInt(10);
            Vec3d toMe = me.subtract(pos).normalize();
            if (roll < 2) target = pos.add(toMe.multiply(4)); // rush
            else if (roll < 4) target = pos.subtract(toMe.multiply(4)); // retreat
            else target = center.add(rng.nextDouble() * 12 - 6, 0, rng.nextDouble() * 12 - 6);
            if (target.distanceTo(center) > 8) target = center.add(target.subtract(center).normalize().multiply(8));
            target = new Vec3d(target.x, center.y, target.z);
            retargetAt = ticks + randBetween(20, 60);
        }
        double step = speed() / 20.0;
        if (level >= 2) {
            if (ticks >= sprintUntil && rng.nextInt(60) == 0) sprintUntil = ticks + randBetween(15, 40);
            if (ticks < sprintUntil) step *= 1.3;
            if (jumpY <= 0 && rng.nextInt(level >= 3 ? 25 : 45) == 0) jumpV = 0.42;
            if (jumpV != 0 || jumpY > 0) {
                jumpY += jumpV;
                jumpV = (jumpV - 0.08) * 0.98;
                if (jumpY <= 0) {
                    jumpY = 0;
                    jumpV = 0;
                }
            }
            if (ticks >= lookUntil && rng.nextInt(50) == 0) {
                lookUntil = ticks + randBetween(10, 30);
                lookYaw = rng.nextFloat() * 360f - 180f;
            }
        }
        if (level >= 3) {
            // sharp direction changes and sudden pearl-like dashes
            if (rng.nextInt(30) == 0) retargetAt = ticks;
            if (rng.nextInt(140) == 0) {
                Vec3d dir = new Vec3d(rng.nextDouble() - 0.5, 0, rng.nextDouble() - 0.5).normalize().multiply(6);
                Vec3d to = pos.add(dir);
                if (to.distanceTo(center) <= 8) {
                    Scene.particles(s.world, net.minecraft.particle.ParticleTypes.PORTAL, pos.add(0, 1, 0), 20, 0.4);
                    pos = to;
                    target = to;
                }
            }
        }
        Vec3d d = target.subtract(pos);
        if (d.length() > step) d = d.normalize().multiply(step);
        pos = pos.add(d);
        float yaw = level >= 2 && ticks < lookUntil ? lookYaw : Scene.yawTowards(pos, me);
        Scene.pin(dummy, pos.add(0, jumpY, 0), yaw);

        double dist = Math.sqrt(pl.squaredDistanceTo(pos));
        liveDistance = dist;
        if (runStart < 0) {
            if (ticks >= restUntil) {
                runStart = ticks;
                inRange = 0;
                counted = 0;
                status = "";
            }
            return;
        }
        counted++;
        if (dist >= 3.0 && dist <= 6.0) inRange++;
        if (ticks - runStart >= RUN_TICKS) {
            int pct = (int) Math.round(100.0 * inRange / Math.max(1, counted));
            rep(pct >= 75, pct, String.format(Locale.ROOT, "%d%% of the run in range", pct));
            runStart = -1;
            restUntil = ticks + 60;
            status = "Next run in 3 s.";
        }
    }

    @Override
    public void renderExtra(DrawContext c, int x, int y) {
        double d = liveDistance;
        if (d < 0) return;
        boolean ok = d >= 3 && d <= 6;
        Gfx.text(c, String.format(Locale.ROOT, "Distance %.1f blocks %s", d, ok ? "- in range" : d < 3 ? "- too close" : "- too far"),
                x, y, ok ? Theme.accent() : 0xFFFFB020);
    }
}
