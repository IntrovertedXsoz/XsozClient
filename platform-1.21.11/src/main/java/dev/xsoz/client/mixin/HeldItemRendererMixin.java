package dev.xsoz.client.mixin;

import dev.xsoz.client.modules.visuals.ViewModel;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
    private void xsoz$push(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swing,
                           ItemStack stack, float equip, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        matrices.push();
        if (ViewModel.INSTANCE != null) ViewModel.INSTANCE.apply(hand, matrices);
    }

    @Inject(method = "renderFirstPersonItem", at = @At("RETURN"))
    private void xsoz$pop(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swing,
                          ItemStack stack, float equip, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        matrices.pop();
    }
}
