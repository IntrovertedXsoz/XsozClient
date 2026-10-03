package dev.xsoz.client.modules.combat;

import dev.xsoz.client.event.GameEvents;
import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;

/**
 * Removes an end crystal on your screen the moment you hit it, instead of waiting a round trip
 * for the server to say it exploded - so the next placement is not blocked by a crystal that is
 * already gone. The same idea as Marlow's Crystal Optimizer. It sends nothing extra: your attack
 * goes to the server exactly as vanilla sends it.
 */
public final class CrystalOptimizer extends Module {
    public CrystalOptimizer() {
        super("Crystal Optimizer", "Hit crystals disappear instantly on your screen (no ping delay before the next place)", Category.COMBAT, false);
        contested();
        GameEvents.register(new GameEvents.Listener() {
            @Override
            public void onAfterAttack(Entity target) {
                if (isEnabled()) handle(target);
            }
        });
    }

    private void handle(Entity target) {
        if (!(target instanceof EndCrystalEntity) || mc.player == null || mc.world == null) return;
        // With Weakness (and no Strength) a bare hand may not break the crystal server-side.
        if (mc.player.hasStatusEffect(StatusEffects.WEAKNESS) && !mc.player.hasStatusEffect(StatusEffects.STRENGTH)) return;
        mc.world.removeEntity(target.getId(), Entity.RemovalReason.KILLED);
    }
}
