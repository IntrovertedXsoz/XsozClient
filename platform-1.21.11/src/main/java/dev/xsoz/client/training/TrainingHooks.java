package dev.xsoz.client.training;

import dev.xsoz.client.training.drill.Drill;
import dev.xsoz.client.training.session.Session;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Server-side hooks for the running drill, in a class with no client references (so a dedicated
 * GameTest server can use them too): drills never kill the player, damage to drill entities (the
 * sparring bot) is routed to the drill, and advancements are not granted during training.
 */
public final class TrainingHooks {
    private static volatile Drill drill;
    private static volatile Session session;
    private static boolean registered;

    private TrainingHooks() { }

    /** Called when a drill starts or ends (null, null). */
    public static void bind(Drill d, Session s) {
        drill = d;
        session = s;
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> !isProtected(pos));
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> Session.onRespawn(newP));
        // Fires before the totem check, so a held totem still pops normally.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            Drill d = drill;
            Session s = session;
            if (d == null || s == null || !(entity instanceof ServerPlayerEntity p) || !p.getUuid().equals(s.playerId)) return true;
            if (p.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) || p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return true;
            return d.onPlayerWouldDie(p);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            Drill d = drill;
            Session s = session;
            if (d == null || s == null) return true;
            if (entity instanceof ServerPlayerEntity p && p.getUuid().equals(s.playerId)) {
                d.onPlayerDamaged(p, source);
                return true;
            }
            if (!s.tracks(entity)) return true;
            return d.onTrackedDamage(entity, source, amount);
        });
    }

    public static boolean active() { return drill != null && session != null; }

    /** Arena walls: no explosion or player may break them. */
    public static boolean isProtected(net.minecraft.util.math.BlockPos p) {
        Session s = session;
        return s != null && s.isProtected(p);
    }

    /** True when p is the player of the running session. */
    public static boolean isTrainee(ServerPlayerEntity p) {
        Session s = session;
        return drill != null && s != null && p.getUuid().equals(s.playerId);
    }
}
