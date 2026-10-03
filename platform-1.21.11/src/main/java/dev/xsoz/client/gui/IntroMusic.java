package dev.xsoz.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

/**
 * The intro's music: C418's "Sweden", straight from the game's own music files (assets/xsoz/
 * sounds.json points at it - nothing is bundled). While it plays, the normal menu music waits.
 */
public final class IntroMusic {
    private static final Identifier ID = Identifier.of("xsoz", "intro_music");
    private static SoundInstance current;
    private static long startedAt;

    private IntroMusic() { }

    public static void start() {
        if (current != null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.getMusicTracker().stop();
        current = new PositionedSoundInstance(ID, SoundCategory.MUSIC, 1f, 1f, Random.create(), false, 0,
                SoundInstance.AttenuationType.NONE, 0, 0, 0, true);
        startedAt = System.currentTimeMillis();
        mc.getSoundManager().play(current);
    }

    /** True while the intro song plays (the menu music must not start over it). */
    public static boolean playing() {
        if (current == null) return false;
        if (System.currentTimeMillis() - startedAt < 3000) return true;
        boolean on = MinecraftClient.getInstance().getSoundManager().isPlaying(current);
        if (!on) current = null;
        return on;
    }
}
