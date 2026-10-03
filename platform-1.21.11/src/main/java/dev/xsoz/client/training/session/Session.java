package dev.xsoz.client.training.session;

import dev.xsoz.client.XsozLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

/**
 * Everything a training session changes in the player's singleplayer world, and how to put it
 * back. SERVER THREAD ONLY.
 *
 * <p>On start the player's whole inventory (main, armour, offhand), selected slot, game mode,
 * position, health and food are snapshotted. Every block an arena changes is recorded with its
 * original state the first time it is touched; every spawned dummy is tracked. {@link #restore}
 * reverses all of it. The same data is written to {@code <world>/xsoz-training-backup.dat} as it
 * changes, so even a crash mid-session restores the player and the terrain on the next join.</p>
 */
public final class Session {
    private static final String BACKUP = "xsoz-training-backup.dat";

    public final MinecraftServer server;
    public final ServerWorld world;
    public final UUID playerId;
    private final List<ItemStack> inventory = new ArrayList<>();
    private int selectedSlot;
    private GameMode gameMode;
    private double x, y, z;
    private float yaw, pitch, health;
    private int food;
    private final Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
    /**
     * Big arenas (Free Roam is ~100x100x37) are recorded as compact boxes - a palette plus one
     * index per position - instead of one map entry per block.
     */
    private final List<Box3> boxes = new ArrayList<>();
    private static final int BOX_THRESHOLD = 4096;

    private static final class Box3 {
        final BlockPos min;
        final int sx, sy, sz;
        final List<BlockState> palette = new ArrayList<>();
        final Map<BlockState, Integer> lookup = new java.util.HashMap<>();
        final int[] idx;

        Box3(BlockPos min, int sx, int sy, int sz) {
            this.min = min;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.idx = new int[sx * sy * sz];
        }

        int index(BlockPos p) {
            int x = p.getX() - min.getX();
            int y = p.getY() - min.getY();
            int z = p.getZ() - min.getZ();
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return -1;
            return (y * sz + z) * sx + x;
        }

        BlockState at(int i) { return palette.get(idx[i]); }

        int paletteIndex(BlockState st) {
            Integer i = lookup.get(st);
            if (i != null) return i;
            palette.add(st);
            lookup.put(st, palette.size() - 1);
            return palette.size() - 1;
        }
    }

    private Box3 boxOf(BlockPos p) {
        for (Box3 b : boxes) if (b.index(p) >= 0) return b;
        return null;
    }
    private final List<Entity> spawned = new ArrayList<>();
    private boolean restored;
    /** Only for GameTests: a FakePlayer is not on the player list, so keep the reference. */
    private final ServerPlayerEntity boundForTests;
    /** Boxes nothing may break: arena walls (explosions and players are both stopped). */
    private final List<BlockPos[]> protectedBoxes = new ArrayList<>();
    /** Sessions whose player died: their inventory and position go back on respawn. */
    private static final Map<UUID, Session> AWAITING_RESPAWN = new java.util.concurrent.ConcurrentHashMap<>();

    /** True for GameTest sessions (no sky platform: tests build in their own height bands). */
    public boolean isTest() { return boundForTests != null; }

    public void protect(BlockPos a, BlockPos b) {
        protectedBoxes.add(new BlockPos[] {
                new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))});
    }

    public boolean isProtected(BlockPos p) {
        for (BlockPos[] b : protectedBoxes) {
            if (p.getX() >= b[0].getX() && p.getX() <= b[1].getX() && p.getY() >= b[0].getY() && p.getY() <= b[1].getY()
                    && p.getZ() >= b[0].getZ() && p.getZ() <= b[1].getZ()) return true;
        }
        return false;
    }

    /** Called on respawn: a player who died in training gets their own things back. */
    public static void onRespawn(ServerPlayerEntity p) {
        Session s = AWAITING_RESPAWN.remove(p.getUuid());
        if (s != null) {
            s.applyPlayer(p);
            s.deleteBackup();
        }
    }

    public Session(MinecraftServer server, ServerPlayerEntity player) {
        this(server, player, false);
    }

    public Session(MinecraftServer server, ServerPlayerEntity player, boolean fakePlayerForTests) {
        this.boundForTests = fakePlayerForTests ? player : null;
        this.server = server;
        this.world = player.getEntityWorld();
        this.playerId = player.getUuid();
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) inventory.add(inv.getStack(i).copy());
        selectedSlot = inv.getSelectedSlot();
        gameMode = player.getGameMode();
        x = player.getX();
        y = player.getY();
        z = player.getZ();
        yaw = player.getYaw();
        pitch = player.getPitch();
        health = player.getHealth();
        food = player.getHungerManager().getFoodLevel();
        writeBackup();
    }

    public ServerPlayerEntity player() {
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(playerId);
        return p != null ? p : boundForTests;
    }

    // ------------------------------------------------------------------ world edits

    /** Sets a block, remembering what was there the first time this position is touched. */
    public void setBlock(BlockPos pos, BlockState state) {
        BlockPos key = pos.toImmutable();
        if (!originals.containsKey(key) && boxOf(key) == null) originals.put(key, world.getBlockState(key));
        world.setBlockState(key, state, 3);
    }

    /**
     * Records every block in a box WITHOUT changing it, so anything the player builds there during
     * the drill (obsidian, anchors...) is also put back on restore.
     */
    public void snapshotBox(BlockPos min, BlockPos max) {
        int sx = Math.abs(max.getX() - min.getX()) + 1;
        int sy = Math.abs(max.getY() - min.getY()) + 1;
        int sz = Math.abs(max.getZ() - min.getZ()) + 1;
        if ((long) sx * sy * sz > BOX_THRESHOLD) {
            BlockPos lo = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
            Box3 b = new Box3(lo, sx, sy, sz);
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        m.set(lo.getX() + x, lo.getY() + y, lo.getZ() + z);
                        // a block already changed by this session keeps its first original
                        BlockState was = originals.containsKey(m) ? originals.remove(m.toImmutable()) : null;
                        Box3 older = was == null ? boxOf(m) : null;
                        if (was == null && older != null) was = older.at(older.index(m));
                        b.idx[(y * sz + z) * sx + x] = b.paletteIndex(was != null ? was : world.getBlockState(m));
                    }
                }
            }
            boxes.add(b);
            return;
        }
        for (BlockPos p : BlockPos.iterate(min, max)) {
            BlockPos key = p.toImmutable();
            if (!originals.containsKey(key) && boxOf(key) == null) originals.put(key, world.getBlockState(key));
        }
    }

    /** Puts one recorded position back to its original state (it stays recorded). */
    public void revert(BlockPos pos) {
        BlockState st = originals.get(pos);
        if (st == null) {
            Box3 b = boxOf(pos);
            if (b != null) st = b.at(b.index(pos));
        }
        if (st != null && world.getBlockState(pos) != st) world.setBlockState(pos, st, 3);
    }

    /** True when pos lies inside a box recorded by this session (the drill's arena). */
    public boolean owns(BlockPos pos) { return originals.containsKey(pos) || boxOf(pos) != null; }

    public void track(Entity e) { spawned.add(e); }

    public boolean tracks(Entity e) { return spawned.contains(e); }

    /** Puts every recorded block back to its original state but keeps recording (between rounds). */
    public void revertAll() {
        List<BlockPos> keys = new ArrayList<>(originals.keySet());
        for (int i = keys.size() - 1; i >= 0; i--) {
            BlockPos k = keys.get(i);
            if (world.getBlockState(k) != originals.get(k)) world.setBlockState(k, originals.get(k), 3);
        }
        for (int i = boxes.size() - 1; i >= 0; i--) restoreBox(boxes.get(i));
    }

    /** Top layer first, so nothing falls or flows into a half-restored box. */
    private void restoreBox(Box3 b) {
        FastBlocks fb = new FastBlocks(world);
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = b.sy - 1; y >= 0; y--) {
            for (int z = 0; z < b.sz; z++) {
                for (int x = 0; x < b.sx; x++) {
                    m.set(b.min.getX() + x, b.min.getY() + y, b.min.getZ() + z);
                    BlockState want = b.at((y * b.sz + z) * b.sx + x);
                    // whole regions go back top-down, straight into the chunks (much faster)
                    fb.set(m, want);
                }
            }
        }
        fb.flush();
    }

    /** Items dropped inside the recorded areas (deaths, broken blocks) are not the world's. */
    private void discardDrops() {
        List<net.minecraft.util.math.Box> areas = new ArrayList<>();
        for (Box3 b : boxes) areas.add(new net.minecraft.util.math.Box(b.min.getX(), b.min.getY(), b.min.getZ(), b.min.getX() + b.sx, b.min.getY() + b.sy, b.min.getZ() + b.sz));
        for (net.minecraft.util.math.Box a : areas) {
            for (var e : world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, a.expand(2), x -> true)) e.discard();
            for (var e : world.getEntitiesByClass(net.minecraft.entity.decoration.EndCrystalEntity.class, a.expand(2), x -> true)) e.discard();
            for (var e : world.getEntitiesByClass(net.minecraft.entity.ExperienceOrbEntity.class, a.expand(2), x -> true)) e.discard();
        }
    }

    public void discardSpawned() {
        for (Entity e : spawned) if (!e.isRemoved()) e.discard();
        spawned.clear();
    }

    /** Puts every recorded block back (newest first) and forgets them. */
    public void restoreBlocks() {
        List<BlockPos> keys = new ArrayList<>(originals.keySet());
        for (int i = keys.size() - 1; i >= 0; i--) world.setBlockState(keys.get(i), originals.get(keys.get(i)), 3);
        originals.clear();
        for (int i = boxes.size() - 1; i >= 0; i--) restoreBox(boxes.get(i));
        boxes.clear();
    }

    /** Called after an arena is built so a crash can still clean it up. */
    public void checkpoint() { writeBackup(); }

    // ------------------------------------------------------------------ player state

    public void clearInventory(ServerPlayerEntity p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) inv.setStack(i, ItemStack.EMPTY);
    }

    private final List<Runnable> onRestore = new ArrayList<>();

    /** Extra things to put back when the session ends (e.g. the world difficulty). */
    public void onRestore(Runnable r) { onRestore.add(r); }

    public void restore() {
        if (restored) return;
        restored = true;
        for (Runnable r : onRestore) {
            try {
                r.run();
            } catch (RuntimeException ex) {
                XsozLog.LOG.warn("Training restore step failed: {}", ex.toString());
            }
        }
        discardSpawned();
        discardDrops();
        restoreBlocks();
        ServerPlayerEntity p = player();
        if (p != null && p.isAlive()) {
            applyPlayer(p);
            deleteBackup();
        } else if (p != null || boundForTests == null) {
            // died for real (void, /kill...): the backup stays until the respawn puts everything back
            AWAITING_RESPAWN.put(playerId, this);
        }
    }

    private void applyPlayer(ServerPlayerEntity p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.size() && i < inventory.size(); i++) inv.setStack(i, inventory.get(i).copy());
        inv.setSelectedSlot(Math.max(0, Math.min(8, selectedSlot)));
        p.changeGameMode(gameMode);
        p.teleport(p.getEntityWorld(), x, y, z, java.util.Set.of(), yaw, pitch, true);
        p.setHealth(Math.max(1f, health));
        p.getHungerManager().setFoodLevel(food);
        p.extinguish();
        p.clearStatusEffects();
        p.currentScreenHandler.sendContentUpdates();
    }

    // ------------------------------------------------------------------ crash safety

    private Path backupPath() { return server.getSavePath(WorldSavePath.ROOT).resolve(BACKUP); }

    private void writeBackup() {
        try {
            var ops = world.getRegistryManager().getOps(NbtOps.INSTANCE);
            NbtCompound root = new NbtCompound();
            root.putString("player", playerId.toString());
            NbtList items = new NbtList();
            for (ItemStack s : inventory) items.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, s).getOrThrow());
            root.put("inventory", items);
            root.putInt("selected", selectedSlot);
            root.putString("gamemode", gameMode.asString());
            root.putDouble("x", x);
            root.putDouble("y", y);
            root.putDouble("z", z);
            root.putFloat("yaw", yaw);
            root.putFloat("pitch", pitch);
            root.putFloat("health", health);
            root.putInt("food", food);
            NbtList blocks = new NbtList();
            for (var e : originals.entrySet()) {
                NbtCompound b = new NbtCompound();
                b.putInt("x", e.getKey().getX());
                b.putInt("y", e.getKey().getY());
                b.putInt("z", e.getKey().getZ());
                b.put("state", NbtHelper.fromBlockState(e.getValue()));
                blocks.add(b);
            }
            root.put("blocks", blocks);
            NbtList bx = new NbtList();
            for (Box3 b : boxes) {
                NbtCompound c = new NbtCompound();
                c.putInt("x", b.min.getX());
                c.putInt("y", b.min.getY());
                c.putInt("z", b.min.getZ());
                c.putInt("sx", b.sx);
                c.putInt("sy", b.sy);
                c.putInt("sz", b.sz);
                NbtList pal = new NbtList();
                for (BlockState st : b.palette) pal.add(NbtHelper.fromBlockState(st));
                c.put("palette", pal);
                c.putIntArray("idx", b.idx);
                bx.add(c);
            }
            root.put("boxes", bx);
            NbtIo.writeCompressed(root, backupPath());
        } catch (IOException | RuntimeException ex) {
            XsozLog.LOG.warn("Training backup could not be written: {}", ex.toString());
        }
    }

    private void deleteBackup() {
        try {
            Files.deleteIfExists(backupPath());
        } catch (IOException ignored) {
            // harmless: the next join restores from it once more, which is idempotent
        }
    }

    /**
     * If a previous session never finished (crash, killed process), put the player and the terrain
     * back from the backup file. Safe to call on every join.
     */
    public static void recoverIfNeeded(MinecraftServer server, ServerPlayerEntity player) {
        Path f = server.getSavePath(WorldSavePath.ROOT).resolve(BACKUP);
        if (!Files.exists(f)) return;
        try {
            NbtCompound root = NbtIo.readCompressed(f, NbtSizeTracker.ofUnlimitedBytes());
            if (!player.getUuid().toString().equals(root.getString("player", ""))) return;
            ServerWorld world = player.getEntityWorld();
            var ops = world.getRegistryManager().getOps(NbtOps.INSTANCE);
            NbtList blocks = root.getListOrEmpty("blocks");
            for (int i = blocks.size() - 1; i >= 0; i--) {
                NbtCompound b = blocks.getCompoundOrEmpty(i);
                BlockPos pos = new BlockPos(b.getInt("x", 0), b.getInt("y", 0), b.getInt("z", 0));
                world.setBlockState(pos, NbtHelper.toBlockState(Registries.BLOCK, b.getCompoundOrEmpty("state")), 3);
            }
            NbtList bx = root.getListOrEmpty("boxes");
            for (int i = bx.size() - 1; i >= 0; i--) {
                NbtCompound c = bx.getCompoundOrEmpty(i);
                int sx = c.getInt("sx", 0);
                int sy = c.getInt("sy", 0);
                int sz = c.getInt("sz", 0);
                NbtList pal = c.getListOrEmpty("palette");
                List<BlockState> palette = new ArrayList<>();
                for (int k = 0; k < pal.size(); k++) palette.add(NbtHelper.toBlockState(Registries.BLOCK, pal.getCompoundOrEmpty(k)));
                int[] idx = c.getIntArray("idx").orElse(new int[0]);
                BlockPos.Mutable m = new BlockPos.Mutable();
                FastBlocks fb = new FastBlocks(world);
                int x0 = c.getInt("x", 0);
                int y0 = c.getInt("y", 0);
                int z0 = c.getInt("z", 0);
                for (int y = sy - 1; y >= 0; y--) {
                    for (int z = 0; z < sz; z++) {
                        for (int x = 0; x < sx; x++) {
                            int k = (y * sz + z) * sx + x;
                            if (k >= idx.length || idx[k] >= palette.size()) continue;
                            m.set(x0 + x, y0 + y, z0 + z);
                            fb.set(m, palette.get(idx[k]));
                        }
                    }
                }
                fb.flush();
            }
            NbtList items = root.getListOrEmpty("inventory");
            var inv = player.getInventory();
            for (int i = 0; i < inv.size() && i < items.size(); i++) {
                NbtElement el = items.get(i);
                inv.setStack(i, ItemStack.OPTIONAL_CODEC.parse(ops, el).result().orElse(ItemStack.EMPTY));
            }
            player.changeGameMode(GameMode.byId(root.getString("gamemode", "survival")));
            player.teleport(world, root.getDouble("x", player.getX()), root.getDouble("y", player.getY()), root.getDouble("z", player.getZ()),
                    java.util.Set.of(), root.getFloat("yaw", 0f), root.getFloat("pitch", 0f), true);
            player.setHealth(Math.max(1f, root.getFloat("health", 20f)));
            player.getHungerManager().setFoodLevel(root.getInt("food", 20));
            Files.deleteIfExists(f);
            XsozLog.LOG.info("Recovered an unfinished training session: inventory, position and terrain restored.");
        } catch (IOException | RuntimeException ex) {
            XsozLog.LOG.warn("Could not recover the training backup ({}); it was kept for another try.", ex.toString());
        }
    }
}
