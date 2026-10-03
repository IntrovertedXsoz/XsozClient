package dev.xsoz.client.training.bot;

import dev.xsoz.client.training.session.Session;
import java.util.List;
import java.util.Random;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

/** What a bot needs from the fight it is in. */
public interface BotWorld {
    Session session();

    int ticks();

    Random rng();

    List<Fighter> fighters();

    /** The fighter an entity belongs to (a bot body or the player), or null. */
    Fighter fighterOf(Entity e);

    /** Arena centre and radius - bots stay inside. */
    BlockPos center();

    int radius();
}
