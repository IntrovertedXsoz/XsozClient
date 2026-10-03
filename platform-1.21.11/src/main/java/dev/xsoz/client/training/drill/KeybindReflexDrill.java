package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.DrillDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.GameMode;

/**
 * Hotbar of red wool; every 2-8 s one slot turns green. Press that slot's key before the window
 * closes. Measures choice reaction time (Hick-Hyman: 9 alternatives). Sound cue off in Level Up.
 */
public final class KeybindReflexDrill extends Drill {
    private int green = -1;
    private int greenAt;
    private int nextAt;
    private int selectedAtGreen;
    private final List<Integer> decoys = new ArrayList<>();
    /** What each hotbar slot must hold; anything else (moved wool, offhand, inventory) is put back. */
    private final net.minecraft.item.Item[] layout = new net.minecraft.item.Item[9];
    private final List<Integer> reactionMs = new ArrayList<>();
    public volatile int greenSlot = -1;

    public KeybindReflexDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.KEYBIND_REFLEX, mode, p, hints);
    }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        s.clearInventory(pl);
        for (int i = 0; i < 9; i++) pl.getInventory().setStack(i, new ItemStack(Items.RED_WOOL));
        pl.changeGameMode(GameMode.ADVENTURE);
        pl.currentScreenHandler.sendContentUpdates();
        dev.xsoz.client.training.TrainingFlags.combosOff = true;
        for (int i = 0; i < 9; i++) layout[i] = Items.RED_WOOL;
        nextAt = randBetween(40, 100);
        status = "Watch the hotbar. Press the green slot's key.";
    }

    /** Keeps the wool exactly where the drill put it: no moving it, no offhand, nothing on the cursor. */
    private void lockInventory(ServerPlayerEntity pl) {
        var inv = pl.getInventory();
        boolean changed = false;
        for (int i = 0; i < 9; i++) {
            if (!inv.getStack(i).isOf(layout[i]) || inv.getStack(i).getCount() != 1) {
                inv.setStack(i, new ItemStack(layout[i]));
                changed = true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (!inv.getStack(i).isEmpty()) {
                inv.setStack(i, ItemStack.EMPTY);
                changed = true;
            }
        }
        if (!pl.getOffHandStack().isEmpty()) {
            pl.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            changed = true;
        }
        if (!pl.currentScreenHandler.getCursorStack().isEmpty()) {
            pl.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
            changed = true;
        }
        if (changed) pl.currentScreenHandler.sendContentUpdates();
    }

    private void paint(ServerPlayerEntity pl, int slot, net.minecraft.item.Item item) {
        layout[slot] = item;
        pl.getInventory().setStack(slot, new ItemStack(item));
    }

    @Override
    protected void tick() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        lockInventory(pl);
        int sel = pl.getInventory().getSelectedSlot();
        if (green < 0) {
            if (ticks >= nextAt) {
                int slot;
                do slot = rng.nextInt(9); while (slot == sel);
                green = slot;
                greenSlot = slot;
                greenAt = ticks;
                selectedAtGreen = sel;
                paint(pl, slot, Items.LIME_WOOL);
                decoys.clear();
                if (level >= 2) {
                    int n = randBetween(1, 2);
                    for (int guard = 0; decoys.size() < n && guard < 50; guard++) {
                        int d = rng.nextInt(9);
                        if (d == slot || d == sel || decoys.contains(d)) continue;
                        decoys.add(d);
                        paint(pl, d, Items.YELLOW_WOOL);
                    }
                }
                pl.currentScreenHandler.sendContentUpdates();
                if (mode != Mode.LEVEL_UP) sound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.7f);
                status = "";
            }
            return;
        }
        int windowTicks = (int) Math.ceil(speed() / 50.0);
        if (level >= 3 && ticks - greenAt == 3) {
            // memory: the cue only flashes - the slot is still the answer
            paint(pl, green, Items.RED_WOOL);
            for (int d : decoys) paint(pl, d, Items.RED_WOOL);
            pl.currentScreenHandler.sendContentUpdates();
        }
        if (sel == green) {
            int ms = (ticks - greenAt) * 50;
            reactionMs.add(ms);
            end(true, ms + " ms");
        } else if (sel != selectedAtGreen) {
            end(false, decoys.contains(sel) ? "Decoy - only green counts" : "Wrong slot");
        } else if (ticks - greenAt > windowTicks) {
            end(false, "Too slow");
        }
    }

    private void end(boolean hit, String note) {
        ServerPlayerEntity pl = player();
        if (pl != null && green >= 0) {
            paint(pl, green, Items.RED_WOOL);
            for (int d : decoys) paint(pl, d, Items.RED_WOOL);
            pl.currentScreenHandler.sendContentUpdates();
        }
        decoys.clear();
        green = -1;
        greenSlot = -1;
        nextAt = ticks + randBetween(40, 160);
        rep(hit, hit ? (ticks - greenAt) * 50 : 0, note);
        status = "Wait for the next green slot...";
    }

    int medianMs() {
        if (reactionMs.isEmpty()) return 0;
        List<Integer> v = new ArrayList<>(reactionMs);
        v.sort(Integer::compare);
        return v.get(v.size() / 2);
    }

    @Override
    protected boolean extraPassCheck() { return medianMs() <= 380; }

    @Override
    public String summaryLine() {
        return reactionMs.isEmpty() ? "" : String.format(Locale.ROOT, "Median reaction %d ms", medianMs());
    }

    @Override
    public void renderExtra(DrawContext c, int x, int y) {
        int g = greenSlot;
        if (!hints || g < 0) return;
        // the player's own Minecraft hotbar key for that slot (Controls > Hotbar Slot N)
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        String key = mc.options == null ? null : mc.options.hotbarKeys[g].getBoundKeyLocalizedText().getString();
        String hint = "Slot " + (g + 1) + (key != null ? "  -  your key: " + key : "");
        Gfx.text(c, hint, x, y, Theme.accent());
    }
}
