package dev.xsoz.client.mixin;

import dev.xsoz.client.training.TrainingManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Training sessions only: anchors detonated in an arena are scored and removed, never exploded. */
@Mixin(RespawnAnchorBlock.class)
public abstract class RespawnAnchorBlockMixin {
    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void xsoz$trainingAnchor(BlockState state, ServerWorld world, BlockPos pos, CallbackInfo ci) {
        if (TrainingManager.running() && TrainingManager.claimAnchor(world, pos)) ci.cancel();
    }
}
