package dev.xsoz.client.mixin;

import dev.xsoz.client.modules.visuals.LowFire;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameOverlayRenderer.class)
public abstract class InGameOverlayRendererMixin {
    @Inject(method = "renderFireOverlay", at = @At("HEAD"))
    private static void xsoz$lowFirePush(MatrixStack matrices, VertexConsumerProvider vcp, Sprite sprite, CallbackInfo ci) {
        matrices.push();
        if (LowFire.INSTANCE != null) matrices.translate(0.0, -LowFire.INSTANCE.offset(), 0.0);
    }

    @Inject(method = "renderFireOverlay", at = @At("RETURN"))
    private static void xsoz$lowFirePop(MatrixStack matrices, VertexConsumerProvider vcp, Sprite sprite, CallbackInfo ci) {
        matrices.pop();
    }
}
