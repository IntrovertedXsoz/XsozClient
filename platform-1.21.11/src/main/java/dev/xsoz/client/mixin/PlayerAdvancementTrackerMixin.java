package dev.xsoz.client.mixin;

import dev.xsoz.client.training.TrainingHooks;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Training only: the kit a drill hands out (netherite, totems...) must not earn advancements in
 * the player's world. While a drill runs for this player, criteria are not granted.
 */
@Mixin(PlayerAdvancementTracker.class)
public abstract class PlayerAdvancementTrackerMixin {
    @Shadow
    private ServerPlayerEntity owner;

    @Inject(method = "grantCriterion", at = @At("HEAD"), cancellable = true)
    private void xsoz$noTrainingAdvancements(AdvancementEntry advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (owner != null && TrainingHooks.isTrainee(owner)) cir.setReturnValue(false);
    }
}
