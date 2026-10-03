package dev.xsoz.client.training.drill;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;

/** Client-only: kept out of Drill so a dedicated server (GameTests) never loads client classes. */
final class ClientSound {
    private ClientSound() { }

    static void play(SoundEvent ev, float pitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> mc.getSoundManager().play(PositionedSoundInstance.ui(ev, pitch, 0.9f)));
    }
}
