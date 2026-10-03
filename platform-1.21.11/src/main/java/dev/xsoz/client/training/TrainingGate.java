package dev.xsoz.client.training;

import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;

/**
 * Training sessions build arenas, give kits and pop totems in the world, so they only run where
 * that harms nobody: a SUPERFLAT SINGLEPLAYER world, in its Overworld, with no other player
 * connected (an open-to-LAN world with a guest is refused). Checked before a drill starts and on
 * every server tick while it runs.
 */
public final class TrainingGate {
    public static final String MESSAGE = "Only available on a flat single player world.";

    public record Result(boolean ok, String message, String detail) {
    }

    private TrainingGate() { }

    public static Result check(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            return new Result(false, MESSAGE, "Open a superflat singleplayer world, then start the drill.");
        }
        IntegratedServer server = mc.getServer();
        if (!mc.isIntegratedServerRunning() || server == null) {
            return new Result(false, MESSAGE, "You are on a server. Training needs your own superflat world.");
        }
        return checkServer(server, mc.player.getEntityWorld().getRegistryKey() == World.OVERWORLD);
    }

    /** Server-side half (also used while a drill runs). */
    public static Result checkServer(MinecraftServer server, boolean inOverworld) {
        if (server.getCurrentPlayerCount() > 1) {
            return new Result(false, MESSAGE, "Another player is in this world. Training is solo only.");
        }
        if (!(server.getOverworld().getChunkManager().getChunkGenerator() instanceof FlatChunkGenerator)) {
            return new Result(false, MESSAGE, "This world isn't superflat. Create one: World Type > Superflat.");
        }
        if (!inOverworld) {
            return new Result(false, MESSAGE, "Go back to the Overworld of this world.");
        }
        return new Result(true, "", "");
    }
}
