package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.CombatMath;
import dev.xsoz.client.training.DrillDef;
import java.util.Locale;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * Hit-Crystal and Double-Tap: crystals on a target in the air.
 *
 * <ul>
 *   <li><b>Hit-Crystal</b>: hit the dummy with your sword - it flies up, like a player would - then
 *   crystal it before it lands. An airborne target's whole body sees the blast (no ground block
 *   hiding the legs), and it can't step out of line. Scored from the hit to the blast.</li>
 *   <li><b>Double-Tap</b>: the dummy gets launched; land TWO crystal blasts in one air-time. The
 *   catch is the 10-tick hurt window: a second blast inside it only deals the difference, so the
 *   second must come at least 10 ticks after the first and still before the landing. The bar shows
 *   the air time and the moment the window opens.</li>
 * </ul>
 */
public final class HitCrystalDrill extends Drill {
    private final boolean doubleTap;
    private BlockPos base;
    private Direction facing;
    private MannequinEntity dummy;
    private Vec3d dummyAt;
    private double ground;
    private double vy;
    private boolean airborne;
    private int launchedAt = -1;
    private int firstBlastAt = -1;
    private int nextLaunchAt;
    private int strafeDir = 1;
    public volatile int shownAir = -1;
    public volatile int shownFirst = -1;

    public HitCrystalDrill(DrillDef def, Mode mode, double p, boolean hints) {
        super(def, mode, p, hints);
        this.doubleTap = def == DrillDef.DOUBLE_TAP;
    }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        base = pl.getBlockPos();
        facing = pl.getHorizontalFacing();
        s.snapshotBox(base.add(-10, -2, -10), base.add(10, 8, 10));
        floor(base.add(-9, -1, -9), 19, 19, Blocks.STONE.getDefaultState());
        clearAbove(base.add(-9, -1, -9), 19, 19, 7);
        giveKit(pl, GameMode.SURVIVAL);
        Scene.teleport(pl, Vec3d.ofBottomCenter(base), facing.getPositiveHorizontalDegrees(), 15f);
        newTarget();
        status = doubleTap ? "It gets launched: two blasts in one air time, 10+ ticks apart." : "Hit it up with your sword, then crystal it in the air.";
    }

    private void newTarget() {
        BlockPos at = base.offset(facing, 3);
        dummyAt = Vec3d.ofBottomCenter(at);
        ground = dummyAt.y;
        if (dummy == null || dummy.isRemoved()) {
            dummy = Scene.spawnDummy(s, dummyAt, Scene.yawTowards(dummyAt, Vec3d.ofBottomCenter(base)));
            if (dummy != null) dummy.setInvulnerable(false); // so your hit registers
        }
        // the obsidian is there on level 1; on level 2+ you place it yourself while they fly
        for (int a = -3; a <= 3; a++) for (int b = -3; b <= 3; b++) set(at.add(a, -1, b), Blocks.STONE.getDefaultState());
        if (level <= 1 || doubleTap) {
            set(at.offset(facing.rotateYClockwise()).down(), Blocks.OBSIDIAN.getDefaultState());
            set(at.offset(facing.rotateYCounterclockwise()).down(), Blocks.OBSIDIAN.getDefaultState());
        }
        airborne = false;
        launchedAt = -1;
        firstBlastAt = -1;
        nextLaunchAt = ticks + (doubleTap ? (int) Math.round(speed() * 20 * (level >= 2 ? 0.7 : 1.0)) : 30);
        refillKit();
        cue();
    }

    @Override
    protected void tick() {
        if (dummy == null) return;
        ServerPlayerEntity pl = player();
        // level 3: it strafes side to side between hits
        if (!airborne && level >= 3) {
            Vec3d side = new Vec3d(facing.rotateYClockwise().getOffsetX(), 0, facing.rotateYClockwise().getOffsetZ());
            Vec3d next = dummyAt.add(side.multiply(0.12 * strafeDir));
            if (Scene.standable(s.world, BlockPos.ofFloored(next)) && next.distanceTo(Vec3d.ofBottomCenter(base.offset(facing, 3))) < 2.5) dummyAt = next;
            else strafeDir = -strafeDir;
        }
        if (doubleTap && !airborne && ticks >= nextLaunchAt) launch(0.62, Vec3d.ZERO);
        if (airborne) {
            dummyAt = dummyAt.add(0, vy, 0);
            vy = (vy - 0.08) * 0.98;
            if (dummyAt.y <= ground) {
                dummyAt = new Vec3d(dummyAt.x, ground, dummyAt.z);
                airborne = false;
                shownAir = -1;
                if (doubleTap && launchedAt >= 0) {
                    rep(false, 0, firstBlastAt < 0 ? "It landed - no blast at all" : "It landed before your second blast");
                    newTarget();
                } else if (!doubleTap && launchedAt >= 0) {
                    rep(false, 0, "It landed - crystal it while it's still up");
                    newTarget();
                }
            } else {
                shownAir = ticks - launchedAt;
            }
        }
        Vec3d me = pl == null ? dummyAt : new Vec3d(pl.getX(), pl.getY(), pl.getZ());
        Scene.pin(dummy, dummyAt, Scene.yawTowards(dummyAt, me));
        shownFirst = firstBlastAt < 0 ? -1 : firstBlastAt - launchedAt;
    }

    private void launch(double up, Vec3d push) {
        vy = up;
        airborne = true;
        launchedAt = ticks;
        firstBlastAt = -1;
        dummyAt = dummyAt.add(push);
    }

    /** Your sword hit: it flies up like a player hit by a sprint swing. */
    @Override
    public boolean onTrackedDamage(Entity e, DamageSource source, float amount) {
        if (e != dummy || doubleTap) return false;
        ServerPlayerEntity pl = player();
        if (source.getAttacker() == pl && !airborne && pl != null) {
            Vec3d away = dummyAt.subtract(pl.getX(), dummyAt.y, pl.getZ());
            Vec3d push = away.lengthSquared() > 1e-4 ? away.normalize().multiply(pl.isSprinting() ? 0.5 : 0.25) : Vec3d.ZERO;
            launch(0.42, push);
            s.world.playSound(null, dummy.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, net.minecraft.sound.SoundCategory.PLAYERS, 1f, 1f);
        }
        return false;
    }

    @Override
    public boolean onCrystalBreak(EndCrystalEntity crystal, Entity attacker) {
        ServerPlayerEntity pl = player();
        if (attacker != pl || pl == null) return true;
        if (!airborne) {
            feedback(doubleTap ? "Wait for the launch" : "Hit it up first", 0xFFFFB020);
            return true;
        }
        Vec3d c = new Vec3d(crystal.getX(), crystal.getY(), crystal.getZ());
        float dmg = Scene.damage(c, CombatMath.CRYSTAL_POWER, dummy, Scene.armourOf(s.world, pl));
        int t = ticks - launchedAt;
        if (!doubleTap) {
            int ms = t * 50;
            boolean ok = ms <= speed() && dmg >= 2f;
            rep(ok, ms, String.format(Locale.ROOT, "%d ms hit-to-blast, %.1f damage in the air%s", ms, dmg,
                    dmg < 2f ? " - too far or blocked" : ms > speed() ? " - faster" : ""));
            airborne = false;
            dummyAt = new Vec3d(dummyAt.x, ground, dummyAt.z);
            newTarget();
            return true;
        }
        if (firstBlastAt < 0) {
            firstBlastAt = ticks;
            feedback(String.format(Locale.ROOT, "First blast %.1f - now wait out the hurt window", dmg), 0xFFECEEF2);
            return true;
        }
        int gap = ticks - firstBlastAt;
        boolean ok = gap >= 10 && dmg >= 2f;
        rep(ok, gap, gap < 10 ? String.format(Locale.ROOT, "Second blast %d ticks after the first - inside the hurt window, mostly wasted", gap)
                : String.format(Locale.ROOT, "Double tap: %d ticks apart, second hit %.1f", gap, dmg));
        airborne = false;
        dummyAt = new Vec3d(dummyAt.x, ground, dummyAt.z);
        newTarget();
        return true;
    }

    @Override
    public void renderExtra(DrawContext c, int x, int y) {
        int air = shownAir;
        if (air < 0) return;
        // the air-time bar: total ~17 ticks for a launch, ~12 for a hit; green once the hurt window has passed
        int total = doubleTap ? 18 : 12;
        int w = 180;
        Gfx.round(c, x, y, w, 6, 3, Theme.LINE);
        Gfx.round(c, x, y, Math.max(3, w * Math.min(air, total) / total), 6, 3, Theme.accent());
        int first = shownFirst;
        if (doubleTap && first >= 0) {
            int open = Math.min(total, first + 10);
            Gfx.rect(c, x + w * first / total, y - 2, 2, 10, 0xFFFFB020);
            Gfx.rect(c, x + w * open / total, y - 2, 2, 10, 0xFF4CD765);
            Gfx.text(c, air >= open ? "NOW - second blast" : "wait...", x, y + 9, air >= open ? 0xFF4CD765 : 0xFFFFB020);
        } else {
            Gfx.text(c, "In the air", x, y + 9, Theme.TEXT_2);
        }
    }
}
