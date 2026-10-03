package dev.xsoz.client.training.bot;

import java.util.UUID;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/** Someone in a fight: the player or a bot, with a team and a score line. */
public final class Fighter {
    public final String name;
    public final int team;
    public final PvpBot bot;
    private final UUID playerId;
    private final MinecraftServer server;
    public int kills;
    public int deaths;
    public int pops;
    public float dealt;
    /** Who hit this fighter last, and when (server tick) - for kill credit. */
    public Fighter lastHitBy;
    public int lastHitAt;

    private Fighter(String name, int team, PvpBot bot, UUID playerId, MinecraftServer server) {
        this.name = name;
        this.team = team;
        this.bot = bot;
        this.playerId = playerId;
        this.server = server;
    }

    public static Fighter player(String name, UUID id, MinecraftServer server, ServerPlayerEntity boundForTests) {
        Fighter f = new Fighter(name, 0, null, id, server);
        f.testPlayer = boundForTests;
        return f;
    }

    public static Fighter bot(PvpBot bot) { return new Fighter(bot.name, bot.team, bot, null, null); }

    private ServerPlayerEntity testPlayer;

    public boolean isPlayer() { return bot == null; }

    public ServerPlayerEntity player() {
        if (bot != null) return null;
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(playerId);
        return p != null ? p : testPlayer;
    }

    /** The entity in the world, or null while dead / respawning. */
    public LivingEntity entity() {
        if (bot != null) return bot.alive() ? bot.body() : null;
        ServerPlayerEntity p = player();
        return p != null && p.isAlive() ? p : null;
    }

    public boolean alive() { return entity() != null; }

    /** Health + golden hearts. */
    public float health() {
        if (bot != null) return bot.health();
        LivingEntity e = entity();
        return e == null ? 0f : e.getHealth() + e.getAbsorptionAmount();
    }

    public boolean enemyOf(Fighter o) { return o != this && o.team != team; }
}
