package dev.xsoz.client.mixin;

import dev.xsoz.client.event.GameEvents;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Totem pops, deaths and damage events, read from the packets the server already sends every
 * client. Injected right after forceMainThread so each fires once, on the client thread.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Shadow
    private ClientWorld world;

    @Inject(method = "onEntityStatus", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/network/PacketApplyBatcher;)V",
            shift = At.Shift.AFTER))
    private void xsoz$status(EntityStatusS2CPacket packet, CallbackInfo ci) {
        if (world == null) return;
        Entity e = packet.getEntity(world);
        if (e != null) GameEvents.entityStatus(e, packet.getStatus());
    }

    @Inject(method = "onEntityDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/network/PacketApplyBatcher;)V",
            shift = At.Shift.AFTER))
    private void xsoz$damage(EntityDamageS2CPacket packet, CallbackInfo ci) {
        if (world == null) return;
        Entity target = world.getEntityById(packet.entityId());
        if (target == null) return;
        String type = packet.sourceType().getKey().map(k -> k.getValue().getPath()).orElse("");
        GameEvents.entityDamage(target, type, packet.sourceCauseId(), packet.sourceDirectId());
    }
}
