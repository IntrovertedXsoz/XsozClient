package dev.xsoz.client.mixin;

import dev.xsoz.client.modules.visuals.NoHurtCam;
import dev.xsoz.client.modules.visuals.Zoom;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void xsoz$zoom(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (Zoom.INSTANCE == null) return;
        float d = Zoom.INSTANCE.divisor();
        if (d > 1.0001f) cir.setReturnValue(cir.getReturnValue() / d);
    }

    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void xsoz$noHurtCam(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        if (NoHurtCam.active) ci.cancel();
    }
}
