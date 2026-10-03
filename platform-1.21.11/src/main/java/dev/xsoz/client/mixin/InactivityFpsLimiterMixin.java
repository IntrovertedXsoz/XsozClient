package dev.xsoz.client.mixin;

import dev.xsoz.client.modules.performance.BackgroundThrottle;
import net.minecraft.client.option.InactivityFpsLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InactivityFpsLimiter.class)
public abstract class InactivityFpsLimiterMixin {
    @Inject(method = "update", at = @At("RETURN"), cancellable = true)
    private void xsoz$unfocusedCap(CallbackInfoReturnable<Integer> cir) {
        if (BackgroundThrottle.INSTANCE == null) return;
        int cap = BackgroundThrottle.INSTANCE.capOrNone();
        if (cap > 0 && cir.getReturnValue() > cap) cir.setReturnValue(cap);
    }
}
