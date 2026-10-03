package dev.xsoz.client.mixin;

import dev.xsoz.client.gui.IntroMusic;
import net.minecraft.client.sound.MusicTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The menu music waits while the intro's song plays. */
@Mixin(MusicTracker.class)
public abstract class MusicTrackerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void xsoz$quietDuringIntro(CallbackInfo ci) {
        if (IntroMusic.playing()) ci.cancel();
    }
}
