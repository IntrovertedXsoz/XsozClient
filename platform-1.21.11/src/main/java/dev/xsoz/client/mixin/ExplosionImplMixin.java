package dev.xsoz.client.mixin;

import dev.xsoz.client.training.TrainingHooks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.ExplosionImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Training arenas: explosions never break the arena walls. */
@Mixin(ExplosionImpl.class)
public abstract class ExplosionImplMixin {
    @ModifyVariable(method = "destroyBlocks", at = @At("HEAD"), argsOnly = true)
    private List<BlockPos> xsoz$keepWalls(List<BlockPos> positions) {
        if (!TrainingHooks.active()) return positions;
        List<BlockPos> out = new ArrayList<>(positions);
        out.removeIf(TrainingHooks::isProtected);
        return out;
    }
}
