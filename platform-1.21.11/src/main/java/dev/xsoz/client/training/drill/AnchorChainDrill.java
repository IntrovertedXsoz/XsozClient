package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.training.CombatMath;
import dev.xsoz.client.training.DrillDef;
import java.util.Locale;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Lime block in the floor: anchor on it, charge, detonate. The explosion is simulated against the
 * player with the game's own exposure raycast and their real armour, then rated
 * too close / slightly too close / just right. Level 3 chains two spots.
 */
public final class AnchorChainDrill extends MarkerDrill {
    private int detonated;
    private int worstRating;
    private float worstSelf;
    private int chainLeft;
    public volatile String lastRating = "";
    public volatile int lastRatingColor;

    public AnchorChainDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.ANCHOR_CHAIN, mode, p, hints);
    }

    @Override
    protected String intro() { return "Lime block = anchor on it, glowstone, detonate - from a safe distance."; }

    @Override
    protected double window() { return speed() * (level >= 3 ? 2 : 1); }

    @Override
    protected boolean complete() { return detonated > 0 && chainLeft <= 0; }

    @Override
    protected void onNewMarker() {
        chainLeft = level >= 3 ? 2 : 1;
        detonated = 0;
        worstRating = 0;
        worstSelf = 0;
    }

    @Override
    public boolean onAnchorExplode(BlockPos pos) {
        ServerPlayerEntity pl = player();
        // the game removes the anchor before it explodes - so must the simulation
        s.setBlock(pos, Blocks.AIR.getDefaultState());
        if (pl == null || targets.isEmpty() || !targets.get(0).equals(pos)) return true;
        float self = Scene.damage(Vec3d.ofCenter(pos), CombatMath.ANCHOR_POWER, pl, Scene.armourOf(s.world, pl));
        int rating = CombatMath.anchorSelfRating(self);
        if (rating >= worstRating) {
            worstRating = rating;
            worstSelf = Math.max(worstSelf, self);
        }
        lastRating = String.format(Locale.ROOT, "%s - you'd take %.1f HP", CombatMath.ANCHOR_RATING[rating], self);
        lastRatingColor = rating == 0 ? 0xFF4CD765 : rating == 1 ? 0xFFFFB020 : 0xFFFF6D7E;
        detonated++;
        chainLeft--;
        if (chainLeft > 0) {
            // level 3: the next spot of the chain appears at another height
            targets.clear();
            BlockPos t = pickSpot(0);
            if (t != null) targets.add(t);
            else chainLeft = 0;
        }
        return true;
    }

    @Override
    protected int heightFor(int index) {
        if (level >= 3) return randBetween(-1, 1);
        return super.heightFor(index);
    }

    @Override
    protected void scoreComplete(double seconds) {
        boolean inTime = seconds <= window();
        boolean safe = level >= 2 ? worstRating == 0 : worstRating < 2;
        String note = String.format(Locale.ROOT, "%.2f s - %s (%.1f HP)", seconds, CombatMath.ANCHOR_RATING[worstRating], worstSelf);
        if (!safe && level >= 2 && worstRating == 1) note += " - level " + level + " needs just right";
        rep(inTime && safe, seconds, note);
    }

    @Override
    protected void onSpotReset() {
        detonated = 0;
        chainLeft = 0;
    }

    @Override
    public void renderExtra(DrawContext c, int x, int y) {
        String r = lastRating;
        if (!r.isEmpty()) Gfx.text(c, r, x, y, lastRatingColor);
    }
}
