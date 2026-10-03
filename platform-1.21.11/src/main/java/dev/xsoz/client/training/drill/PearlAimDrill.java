package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.DrillDef;
import dev.xsoz.client.training.Kit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * Pads at four distance bands. The player is frozen on a stone platform (any movement is undone
 * the same tick - only aim counts). Each pad stays open for the throw window scaled by its band:
 * semi-close x0.7, normal x1, far x1.3, very far x1.6 - and the window itself follows the speed
 * setting (Natural speeds it up and slows it down). Practice runs until 70 hits: the score is the
 * time it took.
 */
public final class PearlAimDrill extends Drill {
    public enum Band {
        SEMI_CLOSE("Semi-close", 8, 12, 0.7), NORMAL("Normal", 13, 18, 1.0), FAR("Far", 19, 26, 1.3), VERY_FAR("Very far", 27, 36, 1.6);

        public final String title;
        public final int min;
        public final int max;
        public final double timeFactor;

        Band(String t, int min, int max, double f) {
            title = t;
            this.min = min;
            this.max = max;
            timeFactor = f;
        }
    }

    private static final int PILLAR = 10;
    private BlockPos ground;
    private BlockPos start;
    private Vec3d startAt;
    private float startYaw;
    private BlockPos pad;
    private int padRadius;
    private int padAt;
    private int nextPadAt = -1;
    private int returnAt = -1;
    private boolean thrown;
    private boolean padToClear;
    private Band band;
    private double padWindow;
    private final List<BlockPos> padBlocks = new ArrayList<>();
    public volatile String shownBand = "";
    public volatile long padShownAt;
    public volatile double shownWindow;

    public PearlAimDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.PEARL_AIM, mode, p, hints);
    }

    /** For GameTests. */
    public BlockPos padForTests() { return pad; }

    public Band bandForTests() { return band; }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        ground = pl.getBlockPos();
        start = level >= 3 ? ground.up(PILLAR) : ground;
        startAt = Vec3d.ofBottomCenter(start);
        startYaw = pl.getYaw();
        s.snapshotBox(start.add(-2, -PILLAR - 2, -2), start.add(2, 3, 2));
        if (level >= 3) {
            // a tall pillar: the pads are down on the ground, so a landing is never inside a block
            for (int y = 0; y < PILLAR - 1; y++) set(ground.up(y), Blocks.STONE_BRICKS.getDefaultState());
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(start.add(dx, -1, dz), Blocks.STONE.getDefaultState());
                for (int y = 0; y <= 2; y++) set(start.add(dx, y, dz), Blocks.AIR.getDefaultState());
            }
        }
        giveKit(pl, GameMode.ADVENTURE);
        Scene.teleport(pl, startAt, startYaw, 0f);
        int pearl = kit.slot(Kit.Role.PEARL);
        if (pearl >= 0 && pearl < 9) pl.getInventory().setSelectedSlot(pearl);
        nextPadAt = 0;
        status = "You can't move. Land a pearl on the gold block.";
    }

    private void newPad() {
        clearPad();
        band = Band.values()[rng.nextInt(Band.values().length)];
        int dist = randBetween(band.min, band.max);
        double ang = Math.toRadians(startYaw + randBetween(-55, 55));
        int x = start.getX() + (int) Math.round(-Math.sin(ang) * dist);
        int z = start.getZ() + (int) Math.round(Math.cos(ang) * dist);
        int dy = level >= 3 ? randBetween(-2, 2) : 0;
        pad = new BlockPos(x, ground.getY() - 1 + dy, z);
        padRadius = level >= 2 ? 1 : 2;
        int reach = padRadius + 1;
        for (BlockPos b : BlockPos.iterate(pad.add(-reach, Math.min(0, -dy) - 1, -reach), pad.add(reach, Math.max(0, -dy) + 4, reach))) {
            s.snapshotBox(b, b);
        }
        for (int ox = -padRadius; ox <= padRadius; ox++) {
            for (int oz = -padRadius; oz <= padRadius; oz++) {
                if (ox * ox + oz * oz > padRadius * padRadius + 1) continue;
                BlockPos top = pad.add(ox, 0, oz);
                // pillar under a raised pad, an open pit above a sunk one, clear air above either
                if (dy > 0) for (int y = 1; y <= dy; y++) put(top.down(y), Blocks.STONE.getDefaultState());
                if (dy < 0) for (int y = 1; y <= -dy; y++) put(top.up(y), Blocks.AIR.getDefaultState());
                for (int y = Math.max(1, -dy + 1); y <= Math.max(1, -dy) + 3; y++) put(top.up(y), Blocks.AIR.getDefaultState());
                put(top, Blocks.LIME_CONCRETE.getDefaultState());
            }
        }
        put(pad, Blocks.GOLD_BLOCK.getDefaultState());
        padAt = ticks;
        cue();
        padWindow = speed() * band.timeFactor;
        int below = start.getY() - 1 - pad.getY();
        shownBand = String.format(Locale.ROOT, "%s - %d blocks%s", band.title, dist, below > 0 ? ", " + below + " down" : "");
        shownWindow = padWindow;
        padShownAt = System.currentTimeMillis();
        thrown = false;
    }

    private void put(BlockPos b, net.minecraft.block.BlockState st) {
        set(b, st);
        padBlocks.add(b.toImmutable());
    }

    private void clearPad() {
        for (int i = padBlocks.size() - 1; i >= 0; i--) s.revert(padBlocks.get(i));
        padBlocks.clear();
        pad = null;
    }

    @Override
    protected void tick() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        int ps = kit.slot(Kit.Role.PEARL);
        if (ps >= 0 && pl.getInventory().getStack(ps).getCount() < 4) pl.getInventory().setStack(ps, new ItemStack(Items.ENDER_PEARL, 16));
        if (returnAt >= 0) {
            if (ticks >= returnAt) {
                if (padToClear) {
                    clearPad();
                    padToClear = false;
                }
                Scene.teleportKeepLook(pl, startAt);
                holdAt(startAt);
                pl.setHealth(pl.getMaxHealth());
                pl.fallDistance = 0;
                returnAt = -1;
            }
            return;
        }
        if (pad == null) {
            if (nextPadAt >= 0 && ticks >= nextPadAt) {
                nextPadAt = -1;
                newPad();
            }
            return;
        }
        if (!thrown && (ticks - padAt) / 20.0 > padWindow) {
            rep(false, padWindow, "Too slow - the pad closed (" + band.title.toLowerCase(Locale.ROOT) + ")");
            clearPad();
            nextPadAt = ticks + 10;
        }
    }

    @Override
    public void onEntityUnload(Entity e) {
        if (!(e instanceof EnderPearlEntity pearl) || pearl.getOwner() != player()) return;
        // the pearl has just put the player where it landed: show it for a moment, then go back
        ServerPlayerEntity pl = player();
        if (pl != null) holdAt(new Vec3d(pl.getX(), pl.getY(), pl.getZ()));
        if (pad == null || !thrown) {
            returnAt = ticks + 20;
            return;
        }
        double dx = pearl.getX() - (pad.getX() + 0.5);
        double dz = pearl.getZ() - (pad.getZ() + 0.5);
        double dy = pearl.getY() - (pad.getY() + 1.0);
        double d = Math.sqrt(dx * dx + dz * dz);
        boolean hit = d <= padRadius + 0.5 && dy > -1.5 && dy < 2.5;
        String miss = d <= padRadius + 0.5 ? (dy <= -1.5 ? " - hit the side" : " - too high") : pearlMiss(dx, dz);
        rep(hit, d, String.format(Locale.ROOT, "%.1f blocks from centre%s", d, hit ? "" : miss));
        returnAt = ticks + 24;
        padToClear = true;
        nextPadAt = ticks + 30;
    }

    /** Short or long, seen from the player. */
    private String pearlMiss(double dx, double dz) {
        Vec3d toPad = new Vec3d(pad.getX() + 0.5 - startAt.x, 0, pad.getZ() + 0.5 - startAt.z).normalize();
        double along = dx * toPad.x + dz * toPad.z;
        return along < 0 ? " - short, aim higher" : " - long, aim lower";
    }

    @Override
    protected boolean autoRefillTotems() { return false; }

    /** Never walk: the only way to move is the pearl (and the short look at where it landed). */
    @Override
    protected boolean playerMayMove() { return false; }

    /** Very far pads are 36 blocks out. */
    @Override
    protected int arenaRadius() { return 42; }

    /** The pearl entity appearing = the throw. */
    @Override
    public void onEntityLoad(Entity e) {
        if (e instanceof EnderPearlEntity pearl && pearl.getOwner() == player() && pad != null && !thrown) {
            thrown = true;
            shownWindow = -1;
        }
    }

    @Override
    public String summaryLine() {
        int secs = age() / 20;
        return goalMode() ? String.format(Locale.ROOT, "%d/%d pads in %d:%02d", hits(), def.goalHits, secs / 60, secs % 60) : "";
    }

    @Override
    public void renderExtra(DrawContext c, int x, int y) {
        String b = shownBand;
        if (b.isEmpty()) return;
        if (shownWindow < 0) {
            Gfx.text(c, b + "   thrown", x, y, Theme.TEXT_2);
            return;
        }
        double left = shownWindow - (System.currentTimeMillis() - padShownAt) / 1000.0;
        Gfx.text(c, String.format(Locale.ROOT, "%s   %.1f s", b, Math.max(0, left)), x, y, left > 0.5 ? Theme.accent() : 0xFFFFB020);
    }
}
