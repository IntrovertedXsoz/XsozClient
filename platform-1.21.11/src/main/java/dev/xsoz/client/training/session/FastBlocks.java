package dev.xsoz.client.training.session;

import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.poi.PointOfInterestTypes;

/**
 * Writes lots of blocks quickly: straight into the chunk sections, with the light engine told about
 * each change and the client sent the usual block updates, but without the per-block work a normal
 * setBlockState does (neighbour updates, heightmaps per block, block callbacks). Heightmaps are
 * redone per chunk in {@link #flush()}. Blocks with a block entity or a point of interest (chests,
 * beds...) still go through the normal path, so nothing of the world is lost.
 *
 * <p>About 15x faster than setBlockState: arenas with a full world of ground under them build and
 * go back in a moment. Server thread only.
 */
public final class FastBlocks {
    private static final EnumSet<Heightmap.Type> HEIGHTMAPS = EnumSet.of(Heightmap.Type.MOTION_BLOCKING,
            Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, Heightmap.Type.OCEAN_FLOOR, Heightmap.Type.WORLD_SURFACE);

    private final ServerWorld w;
    private final Map<WorldChunk, Boolean> touched = new IdentityHashMap<>();
    private WorldChunk last;
    private int lastX = Integer.MIN_VALUE;
    private int lastZ = Integer.MIN_VALUE;

    public FastBlocks(ServerWorld w) { this.w = w; }

    public BlockState get(BlockPos p) {
        WorldChunk ch = chunk(p);
        ChunkSection sec = ch.getSection(ch.getSectionIndex(p.getY()));
        return sec.getBlockState(p.getX() & 15, p.getY() & 15, p.getZ() & 15);
    }

    public void set(BlockPos p, BlockState st) {
        if (p.getY() < w.getBottomY() || p.getY() > w.getTopYInclusive()) return;
        WorldChunk ch = chunk(p);
        ChunkSection sec = ch.getSection(ch.getSectionIndex(p.getY()));
        int x = p.getX() & 15;
        int y = p.getY() & 15;
        int z = p.getZ() & 15;
        BlockState old = sec.getBlockState(x, y, z);
        if (old == st) return;
        if (old.hasBlockEntity() || st.hasBlockEntity() || PointOfInterestTypes.getTypeForState(old).isPresent()
                || PointOfInterestTypes.getTypeForState(st).isPresent()) {
            w.setBlockState(p, st, net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
            return;
        }
        boolean wasEmpty = sec.isEmpty();
        sec.setBlockState(x, y, z, st);
        var light = w.getChunkManager().getLightingProvider();
        if (wasEmpty != sec.isEmpty()) light.setSectionStatus(p, sec.isEmpty());
        light.checkBlock(p);
        w.getChunkManager().markForUpdate(p);
        touched.put(ch, Boolean.TRUE);
    }

    /** Heightmaps and saving for every chunk written since the last flush. */
    public void flush() {
        for (WorldChunk ch : touched.keySet()) {
            Heightmap.populateHeightmaps(ch, HEIGHTMAPS);
            ch.markNeedsSaving();
        }
        touched.clear();
    }

    private WorldChunk chunk(BlockPos p) {
        int cx = p.getX() >> 4;
        int cz = p.getZ() >> 4;
        if (last == null || cx != lastX || cz != lastZ) {
            last = w.getChunk(cx, cz);
            lastX = cx;
            lastZ = cz;
        }
        return last;
    }
}
