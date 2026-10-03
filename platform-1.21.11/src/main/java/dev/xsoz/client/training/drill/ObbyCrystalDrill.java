package dev.xsoz.client.training.drill;

import dev.xsoz.client.training.DrillDef;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.math.BlockPos;

/** Lime block in the floor: obsidian on it, then a crystal on that obsidian. Level 3: two at once. */
public final class ObbyCrystalDrill extends MarkerDrill {
    private final Set<BlockPos> crystalled = new HashSet<>();
    private final java.util.List<EndCrystalEntity> seen = new java.util.ArrayList<>();

    public ObbyCrystalDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.OBBY_CRYSTAL, mode, p, hints);
    }

    @Override
    protected String intro() { return "Lime block = obsidian on it, then a crystal on that obsidian."; }

    @Override
    protected int spotsPerRound() { return level >= 3 ? 2 : 1; }

    @Override
    protected boolean complete() {
        if (targets.isEmpty()) return false;
        for (BlockPos t : targets) {
            if (!crystalled.contains(t) || !s.world.getBlockState(t).isOf(Blocks.OBSIDIAN)) return false;
        }
        return true;
    }

    @Override
    public void onCrystalSpawn(EndCrystalEntity crystal) {
        seen.add(crystal);
        BlockPos under = BlockPos.ofFloored(crystal.getX(), crystal.getY() - 0.5, crystal.getZ());
        for (BlockPos t : targets) if (t.equals(under)) crystalled.add(t);
    }

    @Override
    public boolean onCrystalBreak(EndCrystalEntity crystal, Entity attacker) { return true; }

    @Override
    protected void onSpotReset() {
        crystalled.clear();
        for (EndCrystalEntity c : seen) if (!c.isRemoved()) c.discard();
        seen.clear();
    }

    /** Level 3: both spots keep sliding at a human-trackable pace until obsidian is on them. */
    @Override
    protected void onSpotsTick() {
        if (level < 3) return;
        // one step every 0.9 s at the easy end down to every 0.4 s at the human limit
        int every = (int) Math.round(18 - 10 * p());
        if ((ticks - markerAt) % Math.max(4, every) != every / 2) return;
        for (int i = 0; i < targets.size(); i++) {
            if (s.world.getBlockState(targets.get(i)).isAir()) slideSpot(i);
        }
    }

    @Override
    protected int heightFor(int index) { return level >= 3 ? 0 : super.heightFor(index); }
}
