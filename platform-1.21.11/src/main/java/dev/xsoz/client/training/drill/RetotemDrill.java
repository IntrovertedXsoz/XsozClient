package dev.xsoz.client.training.drill;

import dev.xsoz.client.training.DrillDef;
import dev.xsoz.client.training.Kit;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

/**
 * Real totem pops at random moments (2-8 s apart). The timer starts at the pop and stops when a
 * totem is back in the offhand. The totem hotbar slot is quietly refilled after each rep so this
 * drill trains the swap alone (Hotbar Refill trains the rest).
 */
public final class RetotemDrill extends Drill {
    private int popAt = -1;
    private int nextAt;
    private int refillAt = -1;
    private boolean secondOfPair;

    public RetotemDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.RETOTEM, mode, p, hints);
    }

    @Override
    protected void setup() {
        giveKit(player(), GameMode.ADVENTURE);
        nextAt = randBetween(40, 100);
        status = "You will pop. Get the next totem into your offhand.";
    }

    @Override
    protected void tick() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        boolean offhandTotem = pl.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        if (refillAt >= 0 && ticks >= refillAt) {
            // level 3: the hotbar totem is the player's job too
            if (level < 3) pl.getInventory().setStack(kit.slot(Kit.Role.TOTEM), new ItemStack(Items.TOTEM_OF_UNDYING));
            pl.setHealth(pl.getMaxHealth());
            pl.clearStatusEffects();
            pl.currentScreenHandler.sendContentUpdates();
            refillAt = -1;
        }
        if (popAt < 0) {
            if (ticks >= nextAt && offhandTotem && refillAt < 0) {
                if (secondOfPair) pl.setHealth(pl.getMaxHealth());
                pop(pl);
            }
            return;
        }
        int window = (int) Math.round(speed());
        int dt = ticks - popAt;
        if (offhandTotem) {
            popAt = -1;
            rep(dt <= window, dt, dt + " ticks (" + dt * 50 + " ms)");
            schedule();
        } else if (dt > window && dt > 0) {
            popAt = -1;
            rep(false, dt, "Too slow - get a totem in");
            // put one in for them so the next rep can start
            pl.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            schedule();
        }
    }

    private void schedule() {
        // level 2+: half of the pops come as a pair, like a crystal chain
        boolean pair = level >= 2 && !secondOfPair && rng.nextBoolean();
        secondOfPair = pair;
        nextAt = pair ? ticks + randBetween(10, 20) : ticks + randBetween(40, 160);
        refillAt = pair ? -1 : ticks + 15;
        status = "Wait for the next pop...";
    }

    private void pop(ServerPlayerEntity pl) {
        pl.timeUntilRegen = 0;
        pl.hurtTime = 0;
        pl.setHealth(1f);
        pl.damage(pl.getEntityWorld(), pl.getEntityWorld().getDamageSources().generic(), 200f);
        if (!pl.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            popAt = ticks;
            status = "";
        } else {
            nextAt = ticks + 10; // the damage did not land (invulnerability frames); try again shortly
        }
    }

    @Override
    protected boolean keepAlive() { return popAt < 0; }
}
