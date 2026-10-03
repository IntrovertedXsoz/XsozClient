package dev.xsoz.client.mixin;

import dev.xsoz.client.modules.client.Interface;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Xsoz button skin, applied to every vanilla button - like a resource pack, but drawn in code,
 * so no Mojang texture is shipped or needed. Flat dark surface, accent edge on hover, smooth fade.
 */
@Mixin(PressableWidget.class)
public abstract class PressableWidgetMixin {
    @Unique
    private float xsoz$hover;
    @Unique
    private long xsoz$last;

    @Inject(method = "drawButton", at = @At("HEAD"), cancellable = true)
    private void xsoz$drawButton(DrawContext c, CallbackInfo ci) {
        if (!Interface.customButtons()) return;
        ClickableWidget self = (ClickableWidget) (Object) this;
        long now = System.nanoTime();
        float dt = xsoz$last == 0 ? 0f : Math.min(0.1f, (now - xsoz$last) / 1_000_000_000f);
        xsoz$last = now;
        float target = self.active && (self.isHovered() || self.isFocused()) ? 1f : 0f;
        xsoz$hover += (target - xsoz$hover) * (1f - (float) Math.exp(-18f * dt));

        int x = self.getX();
        int y = self.getY();
        int w = self.getWidth();
        int h = self.getHeight();
        float a = Math.max(0f, Math.min(1f, self.getAlpha()));
        int bg = self.active ? Theme.lerp(0xE6171A1F, 0xF0222730, xsoz$hover) : 0xB0121418;
        Gfx.round(c, x, y, w, h, 3, Theme.alpha(bg, a));
        int edge = self.active ? Theme.lerp(Theme.LINE, Theme.accent(), xsoz$hover) : Theme.LINE_SOFT;
        Gfx.rect(c, x + 2, y + h - 1, w - 4, 1, Theme.alpha(edge, a));
        if (xsoz$hover > 0.01f) {
            int barH = Math.round((h - 8) * xsoz$hover);
            Gfx.rect(c, x + 1, y + (h - barH) / 2, 2, barH, Theme.alpha(Theme.accent(), a * xsoz$hover));
        }
        ci.cancel();
    }
}
