package dev.xsoz.client.training.bot;

import dev.xsoz.client.training.session.SkyArena;
import java.util.Random;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Turns a sky platform into a Free Roam map: the floor's top layers and everything built on it
 * (hills, craters, ruins, trees, cacti, pillars). The platform's bedrock bottom and glass walls
 * stay; everything here is inside the platform's snapshot, so the world comes back exactly.
 */
public final class ArenaBuilder {
    private final ServerWorld w;
    private final BlockPos center;
    private final int r;
    private final int height;
    private final Random rng;

    public ArenaBuilder(ServerWorld w, SkyArena arena, Random rng) {
        this(w, arena.center, arena.radius, arena.height, rng);
    }

    public ArenaBuilder(ServerWorld w, BlockPos center, int radius, int height, Random rng) {
        this.w = w;
        this.center = center;
        this.r = radius;
        this.height = height;
        this.rng = rng;
    }

    private void put(int x, int y, int z, BlockState st) {
        if (Math.abs(x) > r || Math.abs(z) > r || y < -3 || y > height) return;
        BlockPos p = center.add(x, y, z);
        if (w.getBlockState(p) != st) w.setBlockState(p, st, 2);
    }

    private boolean nearMiddle(int x, int z) { return x * x + z * z < 49; }

    public void decorate(FreeRoamConfig.Terrain t) {
        double p1 = rng.nextDouble() * 6.28;
        double p2 = rng.nextDouble() * 6.28;
        double p3 = rng.nextDouble() * 6.28;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int h = 0;
                if (t == FreeRoamConfig.Terrain.HILLS || t == FreeRoamConfig.Terrain.DESERT || t == FreeRoamConfig.Terrain.SNOWY) {
                    double amp = t == FreeRoamConfig.Terrain.HILLS ? 1.0 : 0.45;
                    double v = 3.4 + 2.6 * Math.sin(x / 7.3 + p1) * Math.cos(z / 6.1 + p2) + 1.6 * Math.sin((x + z) / 4.7 + p3);
                    double mid = Math.min(1.0, Math.sqrt(x * x + z * z) / 8.0);
                    h = (int) Math.round(Math.max(0, Math.min(7, v * mid * amp)));
                }
                for (int y = -3; y <= -1 + h; y++) put(x, y, z, floor(t, y, -1 + h));
            }
        }
        switch (t) {
            case CRATERS -> craters();
            case RUINS -> ruins(false);
            case DESERT -> desert();
            case BIRCH -> trees(false);
            case SNOWY -> trees(true);
            case NETHER -> nether();
            default -> { }
        }
    }

    private BlockState floor(FreeRoamConfig.Terrain t, int y, int top) {
        boolean surface = y == top;
        return switch (t) {
            case STONE -> Blocks.STONE.getDefaultState();
            case OBSIDIAN -> surface ? Blocks.OBSIDIAN.getDefaultState() : Blocks.STONE.getDefaultState();
            case RUINS -> surface ? ruinFloor() : Blocks.STONE.getDefaultState();
            case DESERT -> y >= top - 1 ? Blocks.SAND.getDefaultState() : Blocks.SANDSTONE.getDefaultState();
            case SNOWY -> surface ? Blocks.SNOW_BLOCK.getDefaultState() : Blocks.DIRT.getDefaultState();
            case NETHER -> surface && rng.nextInt(7) == 0 ? Blocks.SOUL_SOIL.getDefaultState() : Blocks.NETHERRACK.getDefaultState();
            default -> surface ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.DIRT.getDefaultState();
        };
    }

    private BlockState ruinFloor() {
        int k = rng.nextInt(10);
        return k < 5 ? Blocks.STONE_BRICKS.getDefaultState() : k < 7 ? Blocks.CRACKED_STONE_BRICKS.getDefaultState()
                : k < 9 ? Blocks.MOSSY_STONE_BRICKS.getDefaultState() : Blocks.COBBLESTONE.getDefaultState();
    }

    private int surfaceY(int x, int z) {
        for (int y = height - 1; y >= -3; y--) {
            if (!w.getBlockState(center.add(x, y, z)).isAir()) return y;
        }
        return -4;
    }

    private void craters() {
        int n = r * r / 40;
        for (int i = 0; i < n; i++) {
            int cx = rng.nextInt(2 * r - 4) - r + 2;
            int cz = rng.nextInt(2 * r - 4) - r + 2;
            if (nearMiddle(cx, cz)) continue;
            double rad = 1.2 + rng.nextDouble() * 2.3;
            int depth = 1 + rng.nextInt(2);
            for (int x = (int) -rad - 1; x <= rad + 1; x++) {
                for (int z = (int) -rad - 1; z <= rad + 1; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    if (d > rad) continue;
                    int dd = (int) Math.round(depth * (1 - d / (rad + 0.5))) + 1;
                    for (int y = -1; y >= -dd && y >= -3; y--) put(cx + x, y, cz + z, Blocks.AIR.getDefaultState());
                }
            }
        }
    }

    private void ruins(boolean sandy) {
        int walls = r * r / 30;
        for (int i = 0; i < walls; i++) {
            int x = rng.nextInt(2 * r - 2) - r + 1;
            int z = rng.nextInt(2 * r - 2) - r + 1;
            if (nearMiddle(x, z)) continue;
            int kind = rng.nextInt(10);
            if (kind < 6) {
                boolean alongX = rng.nextBoolean();
                int len = 3 + rng.nextInt(5);
                int h = 2 + rng.nextInt(3);
                for (int k = 0; k < len; k++) {
                    int px = alongX ? x + k : x;
                    int pz = alongX ? z : z + k;
                    int hh = h - (rng.nextInt(4) == 0 ? rng.nextInt(h) : 0); // broken tops
                    for (int y = 0; y < hh; y++) put(px, y, pz, wallBlock(sandy));
                }
            } else if (kind < 8) {
                int h = 3 + rng.nextInt(4);
                for (int y = 0; y < h; y++) put(x, y, z, sandy ? Blocks.CUT_SANDSTONE.getDefaultState()
                        : rng.nextInt(6) == 0 ? Blocks.OBSIDIAN.getDefaultState() : Blocks.STONE_BRICKS.getDefaultState());
            } else {
                for (int a = 0; a < 4; a++) {
                    for (int b = 0; b < 4; b++) {
                        boolean edge = a == 0 || b == 0 || a == 3 || b == 3;
                        if (!edge || a == 0 && b == 1) continue;
                        for (int y = 0; y < 2; y++) put(x + a, y, z + b, wallBlock(sandy));
                    }
                }
            }
        }
    }

    private BlockState wallBlock(boolean sandy) {
        int k = rng.nextInt(12);
        if (sandy) return k < 8 ? Blocks.SANDSTONE.getDefaultState() : Blocks.SMOOTH_SANDSTONE.getDefaultState();
        return k < 6 ? Blocks.STONE_BRICKS.getDefaultState() : k < 9 ? Blocks.COBBLESTONE.getDefaultState()
                : k < 11 ? Blocks.CRACKED_STONE_BRICKS.getDefaultState() : Blocks.OBSIDIAN.getDefaultState();
    }

    private void desert() {
        int n = r * r / 25;
        for (int i = 0; i < n; i++) {
            int x = rng.nextInt(2 * r - 2) - r + 1;
            int z = rng.nextInt(2 * r - 2) - r + 1;
            if (nearMiddle(x, z)) continue;
            int y = surfaceY(x, z);
            if (rng.nextInt(3) == 0) {
                put(x, y + 1, z, Blocks.DEAD_BUSH.getDefaultState());
            } else if (w.getBlockState(center.add(x + 1, y + 1, z)).isAir() && w.getBlockState(center.add(x - 1, y + 1, z)).isAir()
                    && w.getBlockState(center.add(x, y + 1, z + 1)).isAir() && w.getBlockState(center.add(x, y + 1, z - 1)).isAir()) {
                int h = 1 + rng.nextInt(3);
                for (int k = 1; k <= h; k++) put(x, y + k, z, Blocks.CACTUS.getDefaultState());
            }
        }
        ruinsLight();
    }

    /** A few sandstone ruins in the desert. */
    private void ruinsLight() {
        int n = r / 6;
        for (int i = 0; i < n; i++) {
            int x = rng.nextInt(2 * r - 6) - r + 3;
            int z = rng.nextInt(2 * r - 6) - r + 3;
            if (nearMiddle(x, z)) continue;
            int y0 = surfaceY(x, z) + 1;
            boolean alongX = rng.nextBoolean();
            int len = 3 + rng.nextInt(4);
            for (int k = 0; k < len; k++) {
                int px = alongX ? x + k : x;
                int pz = alongX ? z : z + k;
                for (int y = 0; y < 2 + rng.nextInt(2); y++) put(px, y0 + y, pz, wallBlock(true));
            }
        }
    }

    private void trees(boolean spruce) {
        int n = r * r / (spruce ? 45 : 35);
        for (int i = 0; i < n; i++) {
            int x = rng.nextInt(2 * r - 4) - r + 2;
            int z = rng.nextInt(2 * r - 4) - r + 2;
            if (nearMiddle(x, z)) continue;
            int y = surfaceY(x, z) + 1;
            int h = spruce ? 6 + rng.nextInt(3) : 5 + rng.nextInt(3);
            BlockState log = spruce ? Blocks.SPRUCE_LOG.getDefaultState() : Blocks.BIRCH_LOG.getDefaultState();
            BlockState leaves = (spruce ? Blocks.SPRUCE_LEAVES : Blocks.BIRCH_LEAVES).getDefaultState()
                    .with(net.minecraft.block.LeavesBlock.PERSISTENT, true);
            if (spruce) {
                for (int k = 2; k <= h; k++) {
                    int rad = Math.max(0, (h - k) / 2);
                    if (k == h) rad = 0;
                    for (int a = -rad; a <= rad; a++) for (int b = -rad; b <= rad; b++) if (Math.abs(a) + Math.abs(b) <= rad + 1) put(x + a, y + k, z + b, leaves);
                }
                put(x, y + h + 1, z, leaves);
            } else {
                for (int k = h - 3; k <= h + 1; k++) {
                    int rad = k >= h ? 1 : 2;
                    for (int a = -rad; a <= rad; a++) for (int b = -rad; b <= rad; b++) if (Math.abs(a) + Math.abs(b) <= rad + 1) put(x + a, y + k, z + b, leaves);
                }
            }
            for (int k = 0; k < h; k++) put(x, y + k, z, log);
        }
        if (spruce) {
            for (int i = 0; i < r * r / 30; i++) {
                int x = rng.nextInt(2 * r) - r;
                int z = rng.nextInt(2 * r) - r;
                int y = surfaceY(x, z);
                if (w.getBlockState(center.add(x, y, z)).isOf(Blocks.SNOW_BLOCK) && rng.nextInt(3) == 0) put(x, y, z, Blocks.PACKED_ICE.getDefaultState());
            }
        }
    }

    private void nether() {
        int n = r * r / 40;
        for (int i = 0; i < n; i++) {
            int x = rng.nextInt(2 * r - 2) - r + 1;
            int z = rng.nextInt(2 * r - 2) - r + 1;
            if (nearMiddle(x, z)) continue;
            int h = 2 + rng.nextInt(6);
            BlockState b = rng.nextInt(3) == 0 ? Blocks.BLACKSTONE.getDefaultState() : Blocks.BASALT.getDefaultState();
            for (int y = 0; y < h; y++) put(x, y, z, b);
            if (rng.nextInt(4) == 0) put(x, h, z, Blocks.GLOWSTONE.getDefaultState());
        }
    }
}
