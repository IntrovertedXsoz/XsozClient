package dev.xsoz.client.mixin;

import dev.xsoz.client.modules.visuals.Particles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Particles module: hidden particle types are never created (so they cost nothing either). */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
    @Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("HEAD"), cancellable = true)
    private void xsoz$hideParticle(ParticleEffect effect, double x, double y, double z, double vx, double vy, double vz, CallbackInfoReturnable<Particle> cir) {
        if (Particles.hides(effect.getType())) cir.setReturnValue(null);
    }

    @Inject(method = "addEmitter(Lnet/minecraft/entity/Entity;Lnet/minecraft/particle/ParticleEffect;I)V", at = @At("HEAD"), cancellable = true)
    private void xsoz$hideEmitter(Entity entity, ParticleEffect effect, int maxAge, CallbackInfo ci) {
        if (Particles.hides(effect.getType())) ci.cancel();
    }

    @Inject(method = "addEmitter(Lnet/minecraft/entity/Entity;Lnet/minecraft/particle/ParticleEffect;)V", at = @At("HEAD"), cancellable = true)
    private void xsoz$hideEmitter2(Entity entity, ParticleEffect effect, CallbackInfo ci) {
        if (Particles.hides(effect.getType())) ci.cancel();
    }
}
