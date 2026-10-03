package dev.xsoz.client.mixin;

import dev.xsoz.client.event.GameEvents;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    /** After the attack packet has been sent exactly as vanilla sends it. */
    @Inject(method = "attackEntity", at = @At("TAIL"))
    private void xsoz$afterAttack(PlayerEntity player, Entity target, CallbackInfo ci) {
        GameEvents.afterAttack(target);
    }
}
