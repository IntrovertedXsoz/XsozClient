package dev.xsoz.client.trainer;

import java.util.Locale;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

/**
 * Where coaching is allowed to look at the opponent.
 *
 * <p>FULL (opponent-aware strategy cues) only in singleplayer, on LAN, or on a server the player
 * has explicitly marked as a practice server. Everywhere else the ceiling is SELF: the trainer
 * still records and analyses fights, and still coaches from the player's own state, but no live
 * cue reacts to the opponent. The request can only lower the ceiling, never raise it.</p>
 */
public final class TrainingContext {
    public enum Mode { OFF, SELF, FULL }

    /** Mandatory copy, shown next to the mode toggle (docs/trainer-contract.md C11.1.4). */
    public static final String MODE_COPY =
            "Full coaching is available on your own maps, on LAN, and on servers you have explicitly armed. "
            + "On any other server this falls back to your own data only. That is a safety limit inside our "
            + "client, not permission from the server. No ruleset we studied permits a feature because it is off "
            + "in ranked - Hypixel counts an advantage 'anywhere on our server', MCC Island counts one 'in one or "
            + "more of our games', and CubeCraft bans on the mere presence of a module. If you are not sure, ask "
            + "that server's staff first.";

    private TrainingContext() { }

    public static Mode ceiling(MinecraftClient mc, Set<String> practiceServers) {
        if (mc.world == null || mc.player == null) return Mode.OFF;
        if (mc.isInSingleplayer() || mc.isIntegratedServerRunning()) return Mode.FULL;
        ServerInfo info = mc.getCurrentServerEntry();
        if (info == null) return Mode.SELF;
        if (info.isLocal()) return Mode.FULL;
        String addr = normalize(info.address);
        return practiceServers.contains(addr) ? Mode.FULL : Mode.SELF;
    }

    /** min(requested, ceiling). */
    public static Mode resolve(Mode requested, Mode ceiling) {
        return requested.ordinal() <= ceiling.ordinal() ? requested : ceiling;
    }

    public static String currentServer(MinecraftClient mc) {
        ServerInfo info = mc.getCurrentServerEntry();
        return info == null ? null : normalize(info.address);
    }

    public static String normalize(String address) {
        if (address == null) return "";
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
        return a;
    }
}
