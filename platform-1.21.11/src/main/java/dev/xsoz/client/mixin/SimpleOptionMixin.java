package dev.xsoz.client.mixin;

import dev.xsoz.client.modules.visuals.Fullbright;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fullbright: reads of the gamma option return a high value; the stored value is untouched. */
@Mixin(SimpleOption.class)
public abstract class SimpleOptionMixin {
    @Inject(method = "getValue", at = @At("HEAD"), cancellable = true)
    private void xsoz$gamma(CallbackInfoReturnable<Object> cir) {
        if (!Fullbright.active || Fullbright.writing) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return;
        if ((Object) this == mc.options.getGamma()) cir.setReturnValue(16.0);
    }
}
