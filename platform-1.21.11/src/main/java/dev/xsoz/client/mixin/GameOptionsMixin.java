package dev.xsoz.client.mixin;

import dev.xsoz.client.modules.visuals.Fullbright;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While options.txt is being written, Fullbright's gamma override is suspended so the REAL
 * brightness is saved (the override used to make every save log "Value 16.0 outside of range"
 * and drop the brightness setting).
 */
@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {
    @Inject(method = "write", at = @At("HEAD"))
    private void xsoz$suspendGamma(CallbackInfo ci) {
        Fullbright.writing = true;
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void xsoz$resumeGamma(CallbackInfo ci) {
        Fullbright.writing = false;
    }
}
