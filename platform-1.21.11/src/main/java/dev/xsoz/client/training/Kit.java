package dev.xsoz.client.training;

import dev.xsoz.client.modules.combat.ComboBinds;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * The kit a drill hands out: the player's custom Crystal PvP kit if they made one (Select Kit),
 * otherwise the standard crystal kit laid out in their Combo Binds order. Chosen on the client
 * thread ({@link #current()}), materialised on the server thread ({@link #fill}).
 *
 * <p>After filling, every {@link Role} is resolved to the slot that really holds it, so drills
 * work with any layout. A role missing from a custom kit is added to a free slot so no drill can
 * break.</p>
 */
public final class Kit {
    public enum Role {
        XP("xp", Items.EXPERIENCE_BOTTLE, 64, "XP"),
        SWORD("sword", Items.NETHERITE_SWORD, 1, "Sword"),
        OBSIDIAN("obsidian", Items.OBSIDIAN, 64, "Obsidian"),
        ANCHOR("anchor", Items.RESPAWN_ANCHOR, 64, "Anchor"),
        GLOWSTONE("glowstone", Items.GLOWSTONE, 64, "Glowstone"),
        CRYSTAL("crystal", Items.END_CRYSTAL, 64, "Crystal"),
        GAPPLE("gapple", Items.GOLDEN_APPLE, 64, "Gapple"),
        PEARL("pearl", Items.ENDER_PEARL, 16, "Pearl"),
        TOTEM("totem", Items.TOTEM_OF_UNDYING, 1, "Totem");

        public final String comboId;
        public final Item item;
        public final int count;
        public final String label;

        Role(String comboId, Item item, int count, String label) {
            this.comboId = comboId;
            this.item = item;
            this.count = count;
            this.label = label;
        }

        public int defaultSlot() { return ordinal(); }

        /** Whether a stack fills this role (any sword counts as the sword). */
        public boolean matches(ItemStack st) {
            if (st.isEmpty()) return false;
            if (this == SWORD) return st.isIn(net.minecraft.registry.tag.ItemTags.SWORDS);
            return st.isOf(item);
        }
    }

    private final KitSpec spec;
    private final boolean custom;
    /** Preferred hotbar slots (Combo Binds) - where a missing role is added. */
    private final Map<Role, Integer> preferred = new EnumMap<>(Role.class);
    /** Resolved after fill: the slot that really holds each role. */
    private final Map<Role, Integer> slots = new EnumMap<>(Role.class);
    /** What fill() put in each slot 0-40, for refills. */
    private final Map<Integer, ItemStack> filled = new LinkedHashMap<>();
    private final List<Integer> totemSlots = new ArrayList<>();
    /** Crystal drills need every role; a sword or mace kit must not grow crystals and anchors. */
    private boolean ensureRoles = true;

    private Kit(KitSpec spec, boolean custom, Map<Role, Integer> preferred) {
        this.spec = spec;
        this.custom = custom;
        this.preferred.putAll(preferred);
        this.slots.putAll(preferred);
    }

    /** The kit training uses right now. Client thread (reads Combo Binds and the kit store). */
    public static Kit current() {
        Map<Role, Integer> pref = comboLayout();
        boolean client = net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
        if (client && KitStore.usesCustom(KitSpec.Mode.CRYSTAL)) return new Kit(KitStore.custom(KitSpec.Mode.CRYSTAL).copy(), true, pref);
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (var e : pref.entrySet()) ids.put(e.getKey().comboId, e.getValue());
        return new Kit(KitSpec.crystalDefault(ids), false, pref);
    }

    /** The kit for a fight mode: the player's custom one for that mode, or the standard one. Client thread. */
    public static Kit forMode(KitSpec.Mode mode) {
        if (mode == KitSpec.Mode.CRYSTAL) return current();
        Map<Role, Integer> pref = comboLayout();
        boolean client = net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
        Kit k;
        if (client && KitStore.usesCustom(mode)) {
            k = new Kit(KitStore.custom(mode).copy(), true, pref);
        } else {
            Map<String, Integer> ids = new LinkedHashMap<>();
            for (var e : pref.entrySet()) ids.put(e.getKey().comboId, e.getValue());
            k = new Kit(KitSpec.defaultFor(mode, ids), false, pref);
        }
        k.ensureRoles = false;
        return k;
    }

    /**
     * The kit for any mix of abilities: the smallest ready-made kit that covers the mix (your custom
     * one if you made one), without the items of the abilities you left out. Client thread.
     */
    public static Kit forAbilities(java.util.Set<dev.xsoz.client.training.bot.FreeRoamConfig.Ability> set) {
        var exact = dev.xsoz.client.training.bot.FreeRoamConfig.Fight.exactly(set);
        if (exact != null) return forMode(exact.kit);
        var base = dev.xsoz.client.training.bot.FreeRoamConfig.Fight.covering(set);
        return forMode(base.kit).only(set, base == dev.xsoz.client.training.bot.FreeRoamConfig.Fight.CRYSTAL);
    }

    /** The standard kit for a mix, without client state (bots, GameTests). */
    public static Kit standardFor(java.util.Set<dev.xsoz.client.training.bot.FreeRoamConfig.Ability> set) {
        var exact = dev.xsoz.client.training.bot.FreeRoamConfig.Fight.exactly(set);
        if (exact != null) return standard(exact.kit);
        var base = dev.xsoz.client.training.bot.FreeRoamConfig.Fight.covering(set);
        return standard(base.kit).only(set, base == dev.xsoz.client.training.bot.FreeRoamConfig.Fight.CRYSTAL);
    }

    /** This kit without the items of abilities that aren't in the set. */
    private Kit only(java.util.Set<dev.xsoz.client.training.bot.FreeRoamConfig.Ability> set, boolean keepSword) {
        KitSpec k = spec.copy();
        k.entries.removeIf(e -> {
            var need = abilityOf(e.item);
            if (need == null) return false;
            if (e.item.equals("minecraft:obsidian")) return !set.contains(dev.xsoz.client.training.bot.FreeRoamConfig.Ability.CRYSTALS)
                    && !set.contains(dev.xsoz.client.training.bot.FreeRoamConfig.Ability.ANCHORS);
            if (keepSword && need == dev.xsoz.client.training.bot.FreeRoamConfig.Ability.SWORD) return false; // crystal players hit-crystal with it
            return !set.contains(need);
        });
        if (set.contains(dev.xsoz.client.training.bot.FreeRoamConfig.Ability.AXE_SHIELD) && k.count("minecraft:shield") == 0) {
            for (int i = 35; i >= 9; i--) {
                if (k.at(i) == null) {
                    k.put(new KitSpec.Entry(i, "minecraft:shield", 1));
                    break;
                }
            }
        }
        if (k.at(KitSpec.OFFHAND) == null) {
            for (int i = 0; i < 36; i++) {
                KitSpec.Entry e = k.at(i);
                if (e != null && e.item.equals("minecraft:totem_of_undying")) {
                    k.remove(i);
                    e.slot = KitSpec.OFFHAND;
                    k.put(e);
                    break;
                }
            }
        }
        Kit out = new Kit(k, custom, preferred);
        out.ensureRoles = false;
        return out;
    }

    private static dev.xsoz.client.training.bot.FreeRoamConfig.Ability abilityOf(String item) {
        return switch (item) {
            case "minecraft:end_crystal", "minecraft:obsidian" -> dev.xsoz.client.training.bot.FreeRoamConfig.Ability.CRYSTALS;
            case "minecraft:respawn_anchor", "minecraft:glowstone" -> dev.xsoz.client.training.bot.FreeRoamConfig.Ability.ANCHORS;
            case "minecraft:netherite_sword", "minecraft:diamond_sword" -> dev.xsoz.client.training.bot.FreeRoamConfig.Ability.SWORD;
            case "minecraft:netherite_axe", "minecraft:diamond_axe", "minecraft:shield" -> dev.xsoz.client.training.bot.FreeRoamConfig.Ability.AXE_SHIELD;
            case "minecraft:mace", "minecraft:wind_charge" -> dev.xsoz.client.training.bot.FreeRoamConfig.Ability.MACE;
            case "minecraft:elytra", "minecraft:firework_rocket" -> dev.xsoz.client.training.bot.FreeRoamConfig.Ability.ELYTRA;
            default -> null;
        };
    }

    /** The standard kit for a mode, without client state (bots, GameTests). */
    public static Kit standard(KitSpec.Mode mode) {
        Kit k = new Kit(KitSpec.defaultFor(mode, new LinkedHashMap<>()), false, defaultLayout());
        k.ensureRoles = mode == KitSpec.Mode.CRYSTAL;
        return k;
    }

    /** A kit from explicit data (GameTests, previews). */
    public static Kit of(KitSpec spec) { return new Kit(spec.copy(), true, defaultLayout()); }

    private static Map<Role, Integer> defaultLayout() {
        Map<Role, Integer> m = new EnumMap<>(Role.class);
        for (Role r : Role.values()) m.put(r, r.defaultSlot());
        return m;
    }

    /** Combo Binds' layout (0-based). Falls back to the default order on a clash. */
    private static Map<Role, Integer> comboLayout() {
        ComboBinds cb = net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT
                ? ComboBinds.INSTANCE : null;
        Map<Role, Integer> m = new EnumMap<>(Role.class);
        boolean[] used = new boolean[9];
        boolean clash = false;
        for (Role r : Role.values()) {
            int s = cb == null ? r.defaultSlot() : cb.slotOf(r.comboId) - 1;
            if (s < 0 || s > 8 || used[s]) clash = true;
            else used[s] = true;
            m.put(r, s);
        }
        return clash ? defaultLayout() : m;
    }

    public boolean isCustom() { return custom; }

    public KitSpec spec() { return spec; }

    public int slot(Role r) { return slots.get(r); }

    public Role roleAt(int slot) {
        for (var e : slots.entrySet()) if (e.getValue() == slot) return e.getKey();
        return null;
    }

    /** A fresh copy of what the kit put in a slot (EMPTY if nothing). */
    public ItemStack stackAt(int slot) {
        ItemStack st = filled.get(slot);
        return st == null ? ItemStack.EMPTY : st.copy();
    }

    /** A fresh copy of the kit's stack for a role. */
    public ItemStack stack(Role r) {
        ItemStack st = stackAt(slot(r));
        return st.isEmpty() ? new ItemStack(r.item, r.count) : st;
    }

    /** Main-inventory slots the kit filled with totems (not the offhand). */
    public List<Integer> totemSlots() { return totemSlots; }

    // ------------------------------------------------------------------ server thread

    /** Turns one entry into a real stack: item, count (capped at the stack size), enchantments. */
    public static ItemStack toStack(KitSpec.Entry e, RegistryWrapper.WrapperLookup lookup) {
        Identifier id = Identifier.tryParse(e.item == null ? "" : e.item);
        if (id == null || !Registries.ITEM.containsId(id)) return ItemStack.EMPTY;
        Item item = Registries.ITEM.get(id);
        if (item == Items.AIR) return ItemStack.EMPTY;
        ItemStack st = new ItemStack(item);
        st.setCount(Math.max(1, Math.min(e.count, st.getMaxCount())));
        if (e.potion != null) {
            Identifier pid = Identifier.tryParse(e.potion);
            if (pid != null) Registries.POTION.getEntry(pid).ifPresent(pe ->
                    st.set(net.minecraft.component.DataComponentTypes.POTION_CONTENTS, new net.minecraft.component.type.PotionContentsComponent(pe)));
        }
        if (e.ench != null && !e.ench.isEmpty() && lookup != null) {
            var reg = lookup.getOrThrow(RegistryKeys.ENCHANTMENT);
            for (var en : e.ench.entrySet()) {
                Identifier eid = Identifier.tryParse(en.getKey());
                if (eid == null || en.getValue() == null || en.getValue() <= 0) continue;
                reg.getOptional(RegistryKey.of(RegistryKeys.ENCHANTMENT, eid)).ifPresent(ref -> st.addEnchantment(ref, en.getValue()));
            }
        }
        return st;
    }

    /** Clears the player and hands out the kit. Server thread. */
    public void fill(ServerPlayerEntity p) {
        var inv = p.getInventory();
        var lookup = p.getEntityWorld().getRegistryManager();
        filled.clear();
        totemSlots.clear();
        for (KitSpec.Entry e : spec.entries) {
            if (e.slot < 0 || e.slot >= KitSpec.SLOTS) continue;
            ItemStack st = toStack(e, lookup);
            if (st.isEmpty()) continue;
            filled.put(e.slot, st.copy());
        }
        // every role must exist somewhere, or a drill would have nothing to train with
        for (Role r : Role.values()) {
            if (!ensureRoles || find(r) >= 0) continue;
            int at = freeSlot(preferred.get(r));
            if (at >= 0) filled.put(at, new ItemStack(r.item, r.count));
        }
        for (int i = 0; i < 36; i++) inv.setStack(i, filled.getOrDefault(i, ItemStack.EMPTY).copy());
        p.equipStack(EquipmentSlot.FEET, filled.getOrDefault(KitSpec.FEET, ItemStack.EMPTY).copy());
        p.equipStack(EquipmentSlot.LEGS, filled.getOrDefault(KitSpec.LEGS, ItemStack.EMPTY).copy());
        p.equipStack(EquipmentSlot.CHEST, filled.getOrDefault(KitSpec.CHEST, ItemStack.EMPTY).copy());
        p.equipStack(EquipmentSlot.HEAD, filled.getOrDefault(KitSpec.HEAD, ItemStack.EMPTY).copy());
        p.equipStack(EquipmentSlot.OFFHAND, filled.getOrDefault(KitSpec.OFFHAND, ItemStack.EMPTY).copy());
        for (Role r : Role.values()) {
            int s = find(r);
            slots.put(r, s >= 0 ? s : ensureRoles ? preferred.get(r) : -1);
        }
        for (int i = 0; i < 36; i++) if (filled.getOrDefault(i, ItemStack.EMPTY).isOf(Items.TOTEM_OF_UNDYING)) totemSlots.add(i);
        int crystal = slot(Role.CRYSTAL);
        inv.setSelectedSlot(crystal >= 0 && crystal < 9 ? crystal : 0);
    }

    /** Hotbar first, then the main inventory. */
    private int find(Role r) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = filled.get(i);
            if (st != null && r.matches(st)) return i;
        }
        return -1;
    }

    private int freeSlot(int preferredSlot) {
        if (!filled.containsKey(preferredSlot)) return preferredSlot;
        for (int i = 0; i < 9; i++) if (!filled.containsKey(i)) return i;
        for (int i = 9; i < 36; i++) if (!filled.containsKey(i)) return i;
        return -1;
    }

    /** Materialises the whole kit (slot 0-40 -> stack) without a player - what a bot carries. */
    public Map<Integer, ItemStack> materialise(RegistryWrapper.WrapperLookup lookup) {
        Map<Integer, ItemStack> out = new LinkedHashMap<>();
        for (KitSpec.Entry e : spec.entries) {
            if (e.slot < 0 || e.slot >= KitSpec.SLOTS) continue;
            ItemStack st = toStack(e, lookup);
            if (!st.isEmpty()) out.put(e.slot, st);
        }
        return out;
    }

    /** Totems left in the inventory and offhand. */
    public static int totems(ServerPlayerEntity p) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) n += inv.getStack(i).getCount();
        if (p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) n += p.getOffHandStack().getCount();
        return n;
    }

    /**
     * Puts the kit's totems back where the kit had them (empty slots only, never the offhand - a
     * retotem is still the player's job). Returns how many were added.
     */
    public int refillTotems(ServerPlayerEntity p) {
        var inv = p.getInventory();
        int added = 0;
        for (int s : totemSlots) {
            if (inv.getStack(s).isEmpty()) {
                inv.setStack(s, filled.get(s).copy());
                added++;
            }
        }
        if (added > 0) p.currentScreenHandler.sendContentUpdates();
        return added;
    }

    /** Puts a role's kit stack back in its slot. */
    public void refill(ServerPlayerEntity p, Role r) {
        int s = slot(r);
        if (s >= 0 && s < 36) p.getInventory().setStack(s, stack(r));
    }
}
