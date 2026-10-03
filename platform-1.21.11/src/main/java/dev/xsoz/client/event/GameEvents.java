package dev.xsoz.client.event;

import dev.xsoz.client.XsozClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;

/**
 * Game events the client cares about, all delivered on the client thread. Fed by Fabric API
 * callbacks and by a few narrow mixins (entity status / damage packets, attack).
 */
public final class GameEvents {
    public interface Listener {
        default void onAttack(Entity target) { }

        default void onAfterAttack(Entity target) { }

        default void onUseBlock(Hand hand, BlockHitResult hit, ItemStack held) { }

        default void onEntityLoad(Entity entity) { }

        default void onEntityUnload(Entity entity) { }

        /** Entity status byte (35 = totem pop, 3 = death) for any entity. */
        default void onEntityStatus(Entity entity, byte status) { }

        /** A damage packet: {@code type} is the damage type path (e.g. "player_explosion"). */
        default void onEntityDamage(Entity target, String type, int causeId, int directId) { }

        default void onWorldLeave() { }

        /** The attack key was pressed with nothing under the crosshair (a dead click). */
        default void onMissClick() { }
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private GameEvents() { }

    public static void register(Listener l) { LISTENERS.add(l); }

    public static void attack(Entity target) {
        for (Listener l : LISTENERS) safe(() -> l.onAttack(target));
    }

    public static void missClick() {
        for (Listener l : LISTENERS) safe(l::onMissClick);
    }

    public static void afterAttack(Entity target) {
        for (Listener l : LISTENERS) safe(() -> l.onAfterAttack(target));
    }

    public static void useBlock(Hand hand, BlockHitResult hit, ItemStack held) {
        for (Listener l : LISTENERS) safe(() -> l.onUseBlock(hand, hit, held));
    }

    public static void entityLoad(Entity e) {
        for (Listener l : LISTENERS) safe(() -> l.onEntityLoad(e));
    }

    public static void entityUnload(Entity e) {
        for (Listener l : LISTENERS) safe(() -> l.onEntityUnload(e));
    }

    public static void entityStatus(Entity e, byte status) {
        for (Listener l : LISTENERS) safe(() -> l.onEntityStatus(e, status));
    }

    public static void entityDamage(Entity target, String type, int causeId, int directId) {
        for (Listener l : LISTENERS) safe(() -> l.onEntityDamage(target, type, causeId, directId));
    }

    public static void worldLeave() {
        for (Listener l : LISTENERS) safe(l::onWorldLeave);
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ex) {
            XsozClient.LOG.warn("Event listener failed: {}", ex.toString());
        }
    }
}
