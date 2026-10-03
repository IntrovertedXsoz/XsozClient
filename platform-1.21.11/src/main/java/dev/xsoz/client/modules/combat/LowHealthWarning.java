package dev.xsoz.client.modules.combat;

import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.setting.BoolSetting;
import dev.xsoz.client.setting.NumberSetting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

/** A red edge vignette (and an optional tone) when YOUR health drops below a threshold. */
public final class LowHealthWarning extends Module {
    private final NumberSetting threshold = add(new NumberSetting("Hearts", "Warn at or below this many hearts", 4, 1, 10, 0.5));
    private final BoolSetting sound = add(new BoolSetting("Sound", "Play a tone when you cross the threshold", true));
    private boolean wasLow;

    public LowHealthWarning() {
        super("Low Health Warning", "Red screen edge when your own health is low", Category.COMBAT, true);
    }

    private boolean low() {
        return mc.player != null && mc.player.isAlive()
                && mc.player.getHealth() + mc.player.getAbsorptionAmount() <= threshold.value() * 2;
    }

    @Override
    public void onTick() {
        boolean low = low();
        if (low && !wasLow && sound.on()) {
            mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7f, 0.6f));
        }
        wasLow = low;
    }

    public void render(DrawContext c) {
        if (!isEnabled() || !low()) return;
        int w = c.getScaledWindowWidth();
        int h = c.getScaledWindowHeight();
        float p = 0.55f + 0.45f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 300.0));
        for (int i = 0; i < 18; i++) {
            int a = (int) (70 * p * (1f - i / 18f) * (1f - i / 18f));
            int col = Theme.withAlpha(0xFF2E3B, a);
            Gfx.rect(c, i, i, w - 2 * i, 1, col);
            Gfx.rect(c, i, h - i - 1, w - 2 * i, 1, col);
            Gfx.rect(c, i, i + 1, 1, h - 2 * i - 2, col);
            Gfx.rect(c, w - i - 1, i + 1, 1, h - 2 * i - 2, col);
        }
    }
}
