package dev.xsoz.client.training.drill;

import dev.xsoz.client.training.DrillDef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/** A 3x3 obsidian pad; every place -> break is timed in ticks against the cycle limit. */
public final class CrystalCycleDrill extends Drill {
    private BlockPos base;
    private BlockPos padCenter;
    private final Map<Integer, Integer> placedAt = new HashMap<>();
    private final List<Integer> cycles = new ArrayList<>();
    private Direction facing;
    private BlockPos padB;
    private int lastPad = -1;
    private int cyclesOnPad;

    public CrystalCycleDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.CRYSTAL_CYCLE, mode, p, hints);
    }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        base = pl.getBlockPos();
        Direction f = pl.getHorizontalFacing();
        facing = f;
        s.snapshotBox(base.add(-8, -2, -8), base.add(8, 5, 8));
        floor(base.add(-6, -1, -6), 13, 13, Blocks.STONE.getDefaultState());
        clearAbove(base.add(-6, -1, -6), 13, 13, 4);
        if (level >= 3) {
            Direction right = f.rotateYClockwise();
            padCenter = base.offset(f, 3).offset(right, -2).down();
            padB = base.offset(f, 3).offset(right, 2).down();
            buildPad(padCenter, 0);
            buildPad(padB, 0);
        } else {
            padCenter = base.offset(f, 3).down();
            buildPad(padCenter, 1);
        }
        giveKit(pl, GameMode.SURVIVAL);
        Scene.teleport(pl, Vec3d.ofBottomCenter(base), f.getPositiveHorizontalDegrees(), 45f);
        status = "Place a crystal on the obsidian, then break it. Repeat.";
    }

    private void buildPad(BlockPos center, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                set(center.add(dx, 0, dz), Blocks.OBSIDIAN.getDefaultState());
                for (int y = 1; y <= 3; y++) set(center.add(dx, y, dz), Blocks.AIR.getDefaultState());
            }
        }
    }

    /** Level 2: the pad jumps somewhere new every 5 cycles. */
    private void movePad() {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(padCenter.add(dx, 0, dz), Blocks.STONE.getDefaultState());
        for (EndCrystalEntity c : s.world.getEntitiesByClass(EndCrystalEntity.class, new net.minecraft.util.math.Box(base).expand(8), x -> true)) c.discard();
        placedAt.clear();
        Direction right = facing.rotateYClockwise();
        padCenter = base.offset(facing, randBetween(2, 4)).offset(right, randBetween(-3, 3)).down();
        buildPad(padCenter, 1);
    }

    /** Which pad (0 = A, 1 = B) a crystal sits on, level 3. */
    private int padOf(EndCrystalEntity c) {
        BlockPos under = BlockPos.ofFloored(c.getX(), c.getY() - 0.5, c.getZ());
        return under.equals(padB) ? 1 : 0;
    }

    @Override
    protected void tick() {
        int limit = (int) Math.round(speed());
        // a crystal left standing past twice the limit is a failed cycle
        placedAt.entrySet().removeIf(e -> {
            if (ticks - e.getValue() > Math.max(limit * 2, 20)) {
                Entity c = s.world.getEntityById(e.getKey());
                if (c != null) c.discard();
                rep(false, 0, "Crystal left standing");
                return true;
            }
            return false;
        });
    }

    @Override
    public void onCrystalSpawn(EndCrystalEntity crystal) {
        placedAt.put(crystal.getId(), ticks);
    }

    @Override
    public boolean onCrystalBreak(EndCrystalEntity crystal, Entity attacker) {
        Integer at = placedAt.remove(crystal.getId());
        if (at == null || attacker != player()) return true;
        int cycle = ticks - at;
        int limit = (int) Math.round(speed());
        if (level >= 3) {
            int pad = padOf(crystal);
            boolean alternated = lastPad < 0 || pad != lastPad;
            lastPad = pad;
            if (!alternated) {
                rep(false, cycle, "Same pad twice - alternate left and right");
                return true;
            }
        }
        cycles.add(cycle);
        rep(cycle <= limit, cycle, cycle + (cycle == 1 ? " tick" : " ticks"));
        if (level == 2 && ++cyclesOnPad >= 5) {
            cyclesOnPad = 0;
            movePad();
        }
        return true;
    }

    double average() {
        return cycles.stream().mapToInt(Integer::intValue).average().orElse(99);
    }

    @Override
    protected boolean extraPassCheck() { return average() <= 3.2; }

    @Override
    public String summaryLine() {
        return cycles.isEmpty() ? "" : String.format(Locale.ROOT, "Average cycle %.1f ticks (%d ms)", average(), Math.round(average() * 50));
    }
}
