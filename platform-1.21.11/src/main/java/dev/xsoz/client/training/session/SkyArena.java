package dev.xsoz.client.training.session;

import java.util.Random;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * A training arena: a floor with real ground under it, lots of air above, a glass roof, and glass
 * walls. How deep the ground goes is the player's choice ({@link Depth}): a thin platform high in
 * the sky, a deep slab of ground with ores, or a full world column down to bedrock. The bedrock at
 * the bottom is flat or natural (vanilla's rough pattern). Walls and roof are protected: explosions
 * don't break them and players can't mine them. Everything is recorded in compact snapshot boxes,
 * so the world comes back exactly.
 *
 * <p>Deep ground is filled in a few ticks at a time ({@link #buildStep()}), top down, so starting a
 * drill never freezes the game; nothing can reach that deep before it is done.
 */
public final class SkyArena {
    public static final int SKY_Y = 200;

    /** How much ground is under the floor. */
    public enum Depth {
        SHALLOW("Shallow", "A thin floor (3 blocks) on bedrock, high in the sky. Fastest to build."),
        DEEP("Deep", "24 blocks of real ground with stone, ores and gravel under the floor, high in the sky."),
        FULL("Full world", "Ground all the way down to the world's bottom, like a real Minecraft world.");

        public final String title;
        public final String detail;

        Depth(String t, String d) {
            title = t;
            detail = d;
        }
    }

    /** What the very bottom looks like. */
    public enum Bedrock {
        NATURAL("Natural", "Rough bedrock, a few layers thick, like a normal world."),
        FLAT("Flat", "One perfectly flat layer of bedrock.");

        public final String title;
        public final String detail;

        Bedrock(String t, String d) {
            title = t;
            detail = d;
        }
    }

    /** What the ground is made of. */
    public enum Theme { STONE, GRASS, DESERT, SNOWY, NETHER }

    /** Kept for callers that only care about the floor's top. */
    public enum Floor { STONE, GRASS }

    /** The player's choice, set on the client before a drill starts (read on the server thread). */
    public static volatile Depth depthChoice = Depth.FULL;
    public static volatile Bedrock bedrockChoice = Bedrock.NATURAL;

    public final BlockPos center;
    public final int radius;
    public final int height;
    public final Depth depth;
    public final Bedrock bedrock;
    public final Theme theme;
    /** Lowest y of the ground (the bedrock bottom). */
    public final int groundBottom;
    private final ServerWorld w;
    private final FastBlocks fb;
    private final Random rng;
    // progressive fill: the next layer to fill, going down; then the blobs
    private int fillY;
    private int blobsLeft;
    private boolean done;

    private SkyArena(ServerWorld w, BlockPos center, int radius, int height, Depth depth, Bedrock bedrock, Theme theme, int groundBottom) {
        this.w = w;
        this.fb = new FastBlocks(w);
        this.center = center;
        this.radius = radius;
        this.height = height;
        this.depth = depth;
        this.bedrock = bedrock;
        this.theme = theme;
        this.groundBottom = groundBottom;
        this.rng = new Random(center.asLong() ^ 0x5DEECE66DL);
    }

    /** Where the arena goes for a player standing at near: same x/z; high up, or on a full world column. */
    public static BlockPos skyCenter(ServerWorld w, BlockPos near, int height) { return skyCenter(w, near, height, depthChoice); }

    public static BlockPos skyCenter(ServerWorld w, BlockPos near, int height, Depth depth) {
        int top = w.getTopYInclusive() - height - 6;
        if (depth == Depth.FULL) {
            // a normal world's surface sits about 128 blocks above its bottom
            int y = Math.min(top, w.getBottomY() + 129);
            return new BlockPos(near.getX(), y, near.getZ());
        }
        return new BlockPos(near.getX(), Math.min(SKY_Y, top), near.getZ());
    }

    public static SkyArena build(Session s, BlockPos center, int radius, int height, boolean deepWalls, Floor floor) {
        return build(s, center, radius, height, deepWalls, floor == Floor.GRASS ? Theme.GRASS : Theme.STONE, Depth.SHALLOW, Bedrock.FLAT);
    }

    /**
     * Builds the arena around centre (centre = the feet position on top of the floor). The floor and
     * everything above are done now; deeper ground follows in {@link #buildStep()}.
     *
     * @param deepWalls glass walls all the way down to the world's bottom (fight arenas)
     */
    public static SkyArena build(Session s, BlockPos center, int radius, int height, boolean deepWalls, Theme theme, Depth depth, Bedrock bedrock) {
        ServerWorld w = s.world;
        int r = radius;
        int bottom = switch (depth) {
            case SHALLOW -> center.getY() - 4;
            case DEEP -> center.getY() - 24;
            case FULL -> w.getBottomY();
        };
        bottom = Math.max(bottom, w.getBottomY());
        SkyArena a = new SkyArena(w, center, radius, height, depth, bedrock, theme, bottom);
        int roofY = center.getY() + height + 1;
        // record first: the whole column, then each wall as a thin box
        s.snapshotBox(new BlockPos(center.getX() - r - 1, bottom, center.getZ() - r - 1), new BlockPos(center.getX() + r + 1, roofY, center.getZ() + r + 1));
        int wallBottom = deepWalls ? w.getBottomY() : bottom;
        BlockPos[][] walls = {
                {new BlockPos(center.getX() - r - 1, wallBottom, center.getZ() - r - 1), new BlockPos(center.getX() + r + 1, roofY, center.getZ() - r - 1)},
                {new BlockPos(center.getX() - r - 1, wallBottom, center.getZ() + r + 1), new BlockPos(center.getX() + r + 1, roofY, center.getZ() + r + 1)},
                {new BlockPos(center.getX() - r - 1, wallBottom, center.getZ() - r), new BlockPos(center.getX() - r - 1, roofY, center.getZ() + r)},
                {new BlockPos(center.getX() + r + 1, wallBottom, center.getZ() - r), new BlockPos(center.getX() + r + 1, roofY, center.getZ() + r)}};
        if (wallBottom < bottom) for (BlockPos[] wall : walls) s.snapshotBox(wall[0], new BlockPos(wall[1].getX(), bottom - 1, wall[1].getZ()));
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState glass = Blocks.LIGHT_GRAY_STAINED_GLASS.getDefaultState();
        int top4 = depth == Depth.SHALLOW ? -4 : -5;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = top4; y <= -1; y++) {
                    BlockPos p = center.add(x, y, z);
                    if (p.getY() < bottom) continue;
                    a.fb.set(p, a.groundAt(p.getY(), -1 - y));
                }
                for (int y = 0; y <= height; y++) a.fb.set(center.add(x, y, z), air);
            }
        }
        for (BlockPos[] wall : walls) {
            for (BlockPos p : BlockPos.iterate(wall[0], wall[1])) {
                BlockState st = w.getBlockState(p);
                if (st.isOf(Blocks.BEDROCK)) continue; // never replace the world's bedrock
                a.fb.set(p, glass);
            }
            s.protect(wall[0], wall[1]);
        }
        // the roof: nobody pearls or flies out
        BlockPos r0 = new BlockPos(center.getX() - r - 1, roofY, center.getZ() - r - 1);
        BlockPos r1 = new BlockPos(center.getX() + r + 1, roofY, center.getZ() + r + 1);
        for (BlockPos p : BlockPos.iterate(r0, r1)) a.fb.set(p, glass);
        s.protect(r0, r1);
        a.fb.flush();
        Box box = new Box(center).expand(r + 2, height + 2, r + 2);
        for (ItemEntity i : w.getEntitiesByClass(ItemEntity.class, box, x -> true)) i.discard();
        a.fillY = center.getY() + top4 - 1;
        a.blobsLeft = depth == Depth.SHALLOW ? 0 : Math.max(10, (2 * r + 1) * (2 * r + 1) * (a.fillY - bottom + 1) / 900);
        a.done = depth == Depth.SHALLOW;
        return a;
    }

    public boolean built() { return done; }

    /** Fills more of the deep ground; call every tick. Spends at most ~15 ms. */
    public void buildStep() {
        if (done) return;
        long until = System.nanoTime() + 15_000_000L;
        int r = radius;
        BlockPos.Mutable m = new BlockPos.Mutable();
        while (System.nanoTime() < until) {
            if (fillY >= groundBottom) {
                int depthBelow = center.getY() - 1 - fillY;
                for (int x = -r; x <= r; x++) {
                    for (int z = -r; z <= r; z++) {
                        m.set(center.getX() + x, fillY, center.getZ() + z);
                        BlockState want = groundAt(fillY, depthBelow);
                        if (fillY == w.getBottomY() && fb.get(m).isOf(Blocks.BEDROCK)) continue;
                        fb.set(m, want);
                    }
                }
                fillY--;
                continue;
            }
            if (blobsLeft > 0) {
                blobsLeft--;
                blob();
                continue;
            }
            done = true;
            break;
        }
        fb.flush();
    }

    /** Finishes the deep ground right now (GameTests). */
    public void finishBuild() {
        while (!done) buildStep();
    }

    /** The block at height y, d blocks under the surface (0 = the top block). */
    private BlockState groundAt(int y, int d) {
        int k = y - groundBottom;
        if (k == 0) return Blocks.BEDROCK.getDefaultState();
        if (bedrock == Bedrock.NATURAL && k <= 4 && rng.nextInt(5) < 5 - k) return Blocks.BEDROCK.getDefaultState();
        if (theme == Theme.NETHER) {
            if (d == 0 && rng.nextInt(7) == 0) return Blocks.SOUL_SOIL.getDefaultState();
            return Blocks.NETHERRACK.getDefaultState();
        }
        BlockState rock = y < 0 && depth == Depth.FULL && (y < -7 || rng.nextInt(8) < -y) ? Blocks.DEEPSLATE.getDefaultState() : Blocks.STONE.getDefaultState();
        return switch (theme) {
            case GRASS -> d == 0 ? Blocks.GRASS_BLOCK.getDefaultState() : d <= 3 ? Blocks.DIRT.getDefaultState() : rock;
            case SNOWY -> d == 0 ? Blocks.SNOW_BLOCK.getDefaultState() : d <= 3 ? Blocks.DIRT.getDefaultState() : rock;
            case DESERT -> d <= 2 ? Blocks.SAND.getDefaultState() : d <= 7 ? Blocks.SANDSTONE.getDefaultState() : rock;
            default -> rock;
        };
    }

    /** One patch of something under the floor: gravel, andesite... or a small vein of ore. */
    private void blob() {
        int r = radius;
        int lo = groundBottom + 5;
        int hi = center.getY() - 6;
        if (hi <= lo) return;
        BlockPos c = center.add(rng.nextInt(2 * r + 1) - r, 0, rng.nextInt(2 * r + 1) - r).withY(lo + rng.nextInt(hi - lo + 1));
        boolean deep = c.getY() < 0 && depth == Depth.FULL;
        BlockState st;
        double size;
        if (theme == Theme.NETHER) {
            int k = rng.nextInt(20);
            st = k < 6 ? Blocks.BASALT.getDefaultState() : k < 10 ? Blocks.BLACKSTONE.getDefaultState() : k < 12 ? Blocks.MAGMA_BLOCK.getDefaultState()
                    : k < 14 ? Blocks.SOUL_SAND.getDefaultState() : k < 18 ? Blocks.NETHER_QUARTZ_ORE.getDefaultState()
                    : k < 19 ? Blocks.NETHER_GOLD_ORE.getDefaultState() : Blocks.ANCIENT_DEBRIS.getDefaultState();
            size = k < 14 ? 1.6 + rng.nextDouble() * 1.6 : 0.9 + rng.nextDouble() * 0.6;
        } else {
            int k = rng.nextInt(30);
            if (k < 12) {
                st = deep ? (k < 6 ? Blocks.TUFF.getDefaultState() : Blocks.GRAVEL.getDefaultState())
                        : switch (k % 5) {
                            case 0 -> Blocks.ANDESITE.getDefaultState();
                            case 1 -> Blocks.DIORITE.getDefaultState();
                            case 2 -> Blocks.GRANITE.getDefaultState();
                            case 3 -> Blocks.GRAVEL.getDefaultState();
                            default -> Blocks.DIRT.getDefaultState();
                        };
                size = 1.6 + rng.nextDouble() * 1.8;
            } else {
                int o = rng.nextInt(20);
                if (deep) {
                    st = o < 5 ? Blocks.DEEPSLATE_IRON_ORE.getDefaultState() : o < 9 ? Blocks.DEEPSLATE_REDSTONE_ORE.getDefaultState()
                            : o < 12 ? Blocks.DEEPSLATE_GOLD_ORE.getDefaultState() : o < 15 ? Blocks.DEEPSLATE_LAPIS_ORE.getDefaultState()
                            : o < 18 ? Blocks.DEEPSLATE_DIAMOND_ORE.getDefaultState() : Blocks.DEEPSLATE_COPPER_ORE.getDefaultState();
                } else {
                    st = o < 8 ? Blocks.COAL_ORE.getDefaultState() : o < 13 ? Blocks.IRON_ORE.getDefaultState()
                            : o < 17 ? Blocks.COPPER_ORE.getDefaultState() : o < 19 ? Blocks.GOLD_ORE.getDefaultState() : Blocks.LAPIS_ORE.getDefaultState();
                }
                size = 0.9 + rng.nextDouble() * 0.7;
            }
        }
        int s = (int) Math.ceil(size);
        for (int x = -s; x <= s; x++) {
            for (int y = -s; y <= s; y++) {
                for (int z = -s; z <= s; z++) {
                    if (x * x + y * y + z * z > size * size + rng.nextDouble()) continue;
                    BlockPos p = c.add(x, y, z);
                    if (Math.abs(p.getX() - center.getX()) > r || Math.abs(p.getZ() - center.getZ()) > r) continue;
                    BlockState was = fb.get(p);
                    if (was.isOf(Blocks.STONE) || was.isOf(Blocks.DEEPSLATE) || was.isOf(Blocks.NETHERRACK)) fb.set(p, st);
                }
            }
        }
    }

    /** Puts a clean floor and empty air back (between rounds); the walls, roof and deep ground stay. */
    public void refresh(ServerWorld world, Floor floor) {
        int r = radius;
        int top = depth == Depth.SHALLOW ? -4 : -6;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = top; y <= -1; y++) {
                    BlockPos p = center.add(x, y, z);
                    if (p.getY() < groundBottom) continue;
                    BlockState want = groundAt(p.getY(), -1 - y);
                    if (y == -1 && floor == Floor.STONE && theme == Theme.STONE) want = Blocks.STONE.getDefaultState();
                    fb.set(p, want);
                }
                for (int y = 0; y <= height; y++) fb.set(center.add(x, y, z), Blocks.AIR.getDefaultState());
            }
        }
        fb.flush();
        Box box = new Box(center).expand(r + 2, height + 2, r + 2);
        for (ItemEntity i : world.getEntitiesByClass(ItemEntity.class, box, x -> true)) i.discard();
        for (var c : world.getEntitiesByClass(net.minecraft.entity.decoration.EndCrystalEntity.class, box, x -> true)) c.discard();
    }

}
