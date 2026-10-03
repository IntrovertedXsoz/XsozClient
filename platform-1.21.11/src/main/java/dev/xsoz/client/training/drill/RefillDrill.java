package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.DrillDef;
import dev.xsoz.client.training.Kit;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

/** A kit slot is emptied into your inventory; put it back in the same slot before the window closes. */
public final class RefillDrill extends Drill {
    private final java.util.List<Kit.Role> missing = new java.util.ArrayList<>();
    private int missingSince;
    private int nextAt;
    private final java.util.List<Integer> junk = new java.util.ArrayList<>();
    public volatile java.util.List<Kit.Role> shownMissing = java.util.List.of();

    public RefillDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.HOTBAR_REFILL, mode, p, hints);
    }

    @Override
    protected void setup() {
        giveKit(player(), GameMode.ADVENTURE);
        nextAt = randBetween(40, 80);
        status = "A slot will empty. Refill it from your inventory.";
    }

    private int howMany() { return level >= 3 ? 3 : level == 2 ? 2 : 1; }

    @Override
    protected void tick() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        var inv = pl.getInventory();
        if (missing.isEmpty()) {
            if (ticks < nextAt) return;
            java.util.List<Kit.Role> roles = new java.util.ArrayList<>(java.util.List.of(Kit.Role.values()));
            roles.removeIf(r -> kit.slot(r) < 0 || kit.slot(r) > 8);
            java.util.Collections.shuffle(roles, rng);
            for (int n = 0; n < howMany() && n < roles.size(); n++) {
                Kit.Role r = roles.get(n);
                int hot = kit.slot(r);
                ItemStack st = inv.getStack(hot);
                if (st.isEmpty()) st = kit.stack(r);
                int free = freeSlot(inv);
                if (free < 0) break;
                inv.setStack(hot, ItemStack.EMPTY);
                inv.setStack(free, st);
                missing.add(r);
            }
            if (level >= 3) {
                // junk to search through
                net.minecraft.item.Item[] trash = {net.minecraft.item.Items.COBBLESTONE, net.minecraft.item.Items.DIRT,
                        net.minecraft.item.Items.ROTTEN_FLESH, net.minecraft.item.Items.STRING, net.minecraft.item.Items.BONE};
                for (int i = 0; i < 6; i++) {
                    int free = freeSlot(inv);
                    if (free < 0) break;
                    inv.setStack(free, new ItemStack(trash[rng.nextInt(trash.length)], randBetween(1, 16)));
                    junk.add(free);
                }
            }
            pl.currentScreenHandler.sendContentUpdates();
            shownMissing = java.util.List.copyOf(missing);
            missingSince = ticks;
            status = "";
            return;
        }
        double seconds = (ticks - missingSince) / 20.0;
        double window = speed() * missing.size();
        boolean all = true;
        for (Kit.Role r : missing) if (!r.matches(inv.getStack(kit.slot(r)))) all = false;
        if (all) {
            rep(seconds <= window, seconds, String.format(Locale.ROOT, "%.2f s", seconds));
            done(pl);
        } else if (seconds > window) {
            rep(false, seconds, "Too slow");
            // put them back for them
            for (Kit.Role r : missing) {
                if (r.matches(inv.getStack(kit.slot(r)))) continue;
                for (int i = 9; i < 36; i++) {
                    if (r.matches(inv.getStack(i))) {
                        inv.setStack(kit.slot(r), inv.getStack(i));
                        inv.setStack(i, ItemStack.EMPTY);
                        break;
                    }
                }
            }
            pl.currentScreenHandler.sendContentUpdates();
            done(pl);
        }
    }

    private int freeSlot(net.minecraft.entity.player.PlayerInventory inv) {
        for (int tries = 0; tries < 60; tries++) {
            int i = 9 + rng.nextInt(27);
            if (inv.getStack(i).isEmpty()) return i;
        }
        for (int i = 9; i < 36; i++) if (inv.getStack(i).isEmpty()) return i;
        return -1;
    }

    private void done(ServerPlayerEntity pl) {
        missing.clear();
        shownMissing = java.util.List.of();
        for (int j : junk) pl.getInventory().setStack(j, ItemStack.EMPTY);
        junk.clear();
        pl.currentScreenHandler.sendContentUpdates();
        nextAt = ticks + randBetween(40, 120);
        status = "Close your inventory. Next one soon...";
    }

    @Override
    public void renderExtra(DrawContext c, int x, int y) {
        java.util.List<Kit.Role> rs = shownMissing;
        if (rs.isEmpty()) return;
        StringBuilder t = new StringBuilder("Refill: ");
        for (int i = 0; i < rs.size(); i++) {
            Kit.Role r = rs.get(i);
            if (i > 0) t.append(", ");
            t.append(r.label);
            if (hints && kit != null) t.append(" -> slot ").append(kit.slot(r) + 1);
        }
        if (hints) t.append("  (open inventory, hover it, press the slot key)");
        Gfx.text(c, Gfx.fit(t.toString(), 400), x, y, Theme.accent());
    }
}
