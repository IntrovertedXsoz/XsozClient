package dev.xsoz.client.mixin;

import dev.xsoz.client.training.TrainingManager;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Integrated-server side, training sessions only: a crystal broken inside a training arena is
 * scored by the drill and removed without a real explosion, so the player's world is never blown
 * up and the player is never hurt by practice. Outside a session this does nothing.
 */
@Mixin(EndCrystalEntity.class)
public abstract class EndCrystalEntityMixin {
    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void xsoz$trainingCrystal(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (TrainingManager.running() && TrainingManager.claimCrystal((EndCrystalEntity) (Object) this, source)) {
            cir.setReturnValue(true);
        }
    }
}
