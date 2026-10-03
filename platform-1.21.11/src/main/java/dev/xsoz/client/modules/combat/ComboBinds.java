package dev.xsoz.client.modules.combat;

import com.google.gson.JsonObject;
import dev.xsoz.client.XsozClient;
import dev.xsoz.client.config.ConfigManager;
import dev.xsoz.client.mixin.KeyBindingAccessor;
import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.setting.ActionSetting;
import dev.xsoz.client.setting.NumberSetting;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * One key = select a hotbar slot AND use it, in the same tick.
 *
 * <p>Vanilla already does this for exactly one key (bind a hotbar slot and Use to the same key:
 * the tick processes hotbar keys before uses). Vanilla only allows Use on one key, so this module
 * generalises it: each combo key, at the start of the tick, selects its slot and queues one press
 * of the real action binding. The game then performs that action itself in the same tick, through
 * the normal path - the server sees an ordinary slot change followed by an ordinary use.</p>
 *
 * <p>Same-tick collisions resolve like vanilla: combos are applied slot by slot, left to right, so
 * the rightmost slot wins. The layout puts the totem rightmost (survival beats everything), then
 * pearl, gapple, crystal.</p>
 */
public final class ComboBinds extends Module {
    public static ComboBinds INSTANCE;

    private enum Action { USE, SWAP, ATTACK }

    private record Combo(String id, String label, NumberSetting slot, Action action, KeyBinding key, int defaultCode, boolean mouse) {
    }

    private final List<Combo> combos = new ArrayList<>();
    private final KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("xsoz", "combo"));
    private final List<KeyBinding> heldUse = new ArrayList<>();

    public ComboBinds() {
        super("Combo Binds", "One key selects a hotbar slot and uses it in the same tick (set keys under Controls > Xsoz Combo Binds)", Category.COMBAT, false);
        contested();
        INSTANCE = this;
        // Hotbar layout, lowest priority left -> highest right (rightmost wins a same-tick collision).
        combo("xp", "XP bottles", 1, Action.USE, GLFW.GLFW_KEY_G, false);
        combo("sword", "Sword (select + hit)", 2, Action.ATTACK, GLFW.GLFW_KEY_E, false);
        combo("obsidian", "Obsidian", 3, Action.USE, GLFW.GLFW_MOUSE_BUTTON_5, true);
        combo("anchor", "Respawn anchor", 4, Action.USE, GLFW.GLFW_KEY_R, false);
        combo("glowstone", "Glowstone", 5, Action.USE, GLFW.GLFW_KEY_F, false);
        combo("crystal", "End crystal", 6, Action.USE, GLFW.GLFW_MOUSE_BUTTON_4, true);
        combo("gapple", "Golden apple (hold to eat)", 7, Action.USE, GLFW.GLFW_KEY_C, false);
        combo("pearl", "Ender pearl", 8, Action.USE, GLFW.GLFW_KEY_Q, false);
        combo("totem", "Totem (select + swap to offhand)", 9, Action.SWAP, GLFW.GLFW_KEY_CAPS_LOCK, false);

        add(new ActionSetting("Apply crystal layout", "Bind every combo key and move the vanilla keys that clash (your current binds are saved)", this::applyLayout));
        add(new ActionSetting("Restore my old binds", "Put back the binds saved by Apply crystal layout", this::restoreBinds));
    }

    private void combo(String id, String label, int slot, Action action, int code, boolean mouse) {
        NumberSetting s = add(new NumberSetting("Slot: " + label, "Hotbar slot this combo selects", slot, 1, 9, 1));
        // Registered unbound: the keys only take effect after "Apply crystal layout" or a manual bind,
        // so turning the module on never silently steals E, Q, F or R from vanilla.
        KeyBinding kb = new KeyBinding("key.xsoz.combo." + id,
                mouse ? InputUtil.Type.MOUSE : InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category);
        combos.add(new Combo(id, label, s, action, kb, code, mouse));
    }

    /** The hotbar slot (1-9) a combo selects, by combo id ("crystal", "totem"...). */
    public int slotOf(String id) {
        for (Combo c : combos) if (c.id.equals(id)) return c.slot.intValue();
        return -1;
    }

    /**
     * Points each combo at the hotbar slot its item has in a kit (role id -> slot 1-9), so a
     * custom kit and the combo keys always agree. Roles missing from the map keep their slot.
     */
    public void syncSlots(java.util.Map<String, Integer> roleSlots) {
        for (Combo c : combos) {
            Integer s = roleSlots.get(c.id);
            if (s != null && s >= 1 && s <= 9) c.slot.set((double) s);
        }
        ConfigManager.markDirty();
    }

    /** The physical key bound to a combo, for hints ("Mouse 4", "R"...). */
    public String keyLabel(String id) {
        for (Combo c : combos) {
            if (c.id.equals(id)) return c.key.isUnbound() ? null : c.key.getBoundKeyLocalizedText().getString();
        }
        return null;
    }

    /** Adds the combo keys to Controls. Called once from the client entrypoint. */
    public void registerKeys() {
        for (Combo c : combos) KeyBindingHelper.registerKeyBinding(c.key);
    }

    /** Start of tick, before the game handles input. */
    @Override
    public void onPreTick() {
        if (!inGame()) return;
        if (mc.currentScreen != null || dev.xsoz.client.training.TrainingFlags.combosOff) {
            if (dev.xsoz.client.training.TrainingFlags.combosOff) for (Combo c : combos) while (c.key.wasPressed()) { }
            releaseHeldUse();
            return;
        }
        var inv = mc.player.getInventory();
        // Slot order = vanilla order: the rightmost combo pressed this tick ends up selected.
        List<Combo> sorted = new ArrayList<>(combos);
        sorted.sort((a, b) -> Integer.compare(a.slot.intValue(), b.slot.intValue()));
        List<Action> queued = new ArrayList<>();
        int finalSlot = -1;
        for (Combo c : sorted) {
            if (c.key.wasPressed()) {
                // drain extra presses so one tick never fires a combo twice
                while (c.key.wasPressed()) { }
                finalSlot = c.slot.intValue() - 1;
                queued.add(c.action);
            }
        }
        if (finalSlot >= 0) {
            inv.setSelectedSlot(finalSlot);
            if (!dev.xsoz.client.training.TrainingFlags.selectOnly) for (Action a : queued) press(target(a));
        }

        // Hold-to-use (eating, drinking, spamming XP): keep Use held while a USE combo key is held.
        boolean anyHeld = false;
        if (dev.xsoz.client.training.TrainingFlags.selectOnly) {
            releaseHeldUse();
            return;
        }
        for (Combo c : combos) {
            if (c.action == Action.USE && c.key.isPressed()) anyHeld = true;
        }
        KeyBinding use = mc.options.useKey;
        if (anyHeld) {
            use.setPressed(true);
            if (!heldUse.contains(use)) heldUse.add(use);
        } else {
            releaseHeldUse();
        }
    }

    private KeyBinding target(Action a) {
        return switch (a) {
            case USE -> mc.options.useKey;
            case SWAP -> mc.options.swapHandsKey;
            case ATTACK -> mc.options.attackKey;
        };
    }

    private static void press(KeyBinding kb) {
        KeyBindingAccessor acc = (KeyBindingAccessor) kb;
        acc.xsoz$setTimesPressed(acc.xsoz$getTimesPressed() + 1);
    }

    private void releaseHeldUse() {
        if (heldUse.isEmpty()) return;
        KeyBinding use = mc.options.useKey;
        use.setPressed(physicallyDown(use));
        heldUse.clear();
    }

    private boolean physicallyDown(KeyBinding kb) {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(kb);
        if (key == null || key.getCode() < 0) return false;
        long window = mc.getWindow().getHandle();
        if (key.getCategory() == InputUtil.Type.MOUSE) return GLFW.glfwGetMouseButton(window, key.getCode()) == GLFW.GLFW_PRESS;
        if (key.getCategory() == InputUtil.Type.KEYSYM) return InputUtil.isKeyPressed(mc.getWindow(), key.getCode());
        return false;
    }

    @Override
    protected void onDisable() {
        if (inGame()) releaseHeldUse();
    }

    // ------------------------------------------------------------------ layout

    /** Vanilla keys the layout needs freed, and where they move to. */
    private record Move(KeyBinding kb, int code) {
    }

    private List<Move> vanillaMoves() {
        var o = mc.options;
        List<Move> moves = new ArrayList<>();
        moves.add(new Move(o.inventoryKey, GLFW.GLFW_KEY_TAB));          // E -> Tab
        moves.add(new Move(o.playerListKey, GLFW.GLFW_KEY_GRAVE_ACCENT)); // Tab -> `
        moves.add(new Move(o.dropKey, GLFW.GLFW_KEY_K));                  // Q -> K (no accidental totem drops)
        moves.add(new Move(o.swapHandsKey, GLFW.GLFW_KEY_UNKNOWN));       // F -> unbound (the totem combo swaps)
        if (XsozClient.zoomKey != null) moves.add(new Move(XsozClient.zoomKey, GLFW.GLFW_KEY_Z)); // C -> Z
        return moves;
    }

    private void applyLayout() {
        if (mc.options == null) return;
        JsonObject saved = new JsonObject();
        for (Move m : vanillaMoves()) saved.addProperty(m.kb.getId(), m.kb.getBoundKeyTranslationKey());
        for (Combo c : combos) saved.addProperty(c.key.getId(), c.key.getBoundKeyTranslationKey());
        if (!ConfigManager.extra().has("comboRestore")) ConfigManager.extra().add("comboRestore", saved);

        for (Move m : vanillaMoves()) m.kb.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(m.code));
        for (Combo c : combos) {
            c.key.setBoundKey((c.mouse ? InputUtil.Type.MOUSE : InputUtil.Type.KEYSYM).createFromCode(c.defaultCode));
            c.slot.reset();
        }
        KeyBinding.updateKeysByCode();
        mc.options.write();
        if (!isEnabled()) setEnabled(true);
        ConfigManager.markDirty();
        XsozClient.LOG.info("Combo Binds: crystal layout applied.");
    }

    private void restoreBinds() {
        if (mc.options == null || !ConfigManager.extra().has("comboRestore")) return;
        JsonObject saved = ConfigManager.extra().getAsJsonObject("comboRestore");
        List<KeyBinding> all = new ArrayList<>();
        for (Move m : vanillaMoves()) all.add(m.kb);
        for (Combo c : combos) all.add(c.key);
        for (KeyBinding kb : all) {
            if (saved.has(kb.getId())) kb.setBoundKey(InputUtil.fromTranslationKey(saved.get(kb.getId()).getAsString()));
        }
        KeyBinding.updateKeysByCode();
        mc.options.write();
        ConfigManager.extra().remove("comboRestore");
        ConfigManager.markDirty();
    }
}
