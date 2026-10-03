package dev.xsoz.client.mixin;

import dev.xsoz.client.gui.XsozTitleScreen;
import dev.xsoz.client.modules.client.Interface;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Swaps the vanilla title screen for the Xsoz main menu (unless "Classic menu" was chosen). */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method = "doAttack", at = @At("HEAD"))
    private void xsoz$deadClick(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        var hit = ((MinecraftClient) (Object) this).crosshairTarget;
        if (hit == null || hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) dev.xsoz.client.event.GameEvents.missClick();
    }

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen xsoz$replaceTitle(Screen screen) {
        if (screen instanceof TitleScreen) {
            if (XsozTitleScreen.allowVanillaOnce) {
                XsozTitleScreen.allowVanillaOnce = false;
                return screen;
            }
            if (Interface.customMainMenu()) {
                // the very first launch: the intro, then the menu
                if (!dev.xsoz.client.training.TrainingStore.progress().introDone) return new dev.xsoz.client.gui.IntroScreen();
                return new XsozTitleScreen();
            }
        }
        return screen;
    }
}
