package dev.xsoz.client.training;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A kit as plain data: which item (registry id), how many and which enchantments go in which
 * inventory slot. Gson-friendly, no Minecraft types, unit tested. Turned into real ItemStacks on
 * the server thread by {@link Kit}.
 *
 * <p>Slots: 0-8 hotbar, 9-35 main inventory, {@link #FEET}..{@link #HEAD} armour, {@link #OFFHAND}.</p>
 */
public final class KitSpec {
    public static final int FEET = 36;
    public static final int LEGS = 37;
    public static final int CHEST = 38;
    public static final int HEAD = 39;
    public static final int OFFHAND = 40;
    public static final int SLOTS = 41;

    /** Game modes a kit can be made for. Crystal PvP drives the trainer today. */
    public enum Mode {
        CRYSTAL("Crystal PvP"), SWORD("Sword"), MACE("Mace + Elytra"), EVERYTHING("Everything"), UHC("UHC");

        public final String title;

        Mode(String t) { title = t; }
    }

    public static final class Entry {
        public int slot;
        public String item;
        public int count = 1;
        /** Enchantment id -> level. */
        public Map<String, Integer> ench = new LinkedHashMap<>();
        /** Potion id for potions and tipped arrows (null otherwise). */
        public String potion;

        public Entry() { }

        public Entry(int slot, String item, int count) {
            this.slot = slot;
            this.item = item;
            this.count = count;
        }

        public Entry ench(String id, int level) {
            ench.put(id, level);
            return this;
        }

        public Entry copy() {
            Entry e = new Entry(slot, item, count);
            e.ench = new LinkedHashMap<>(ench);
            e.potion = potion;
            return e;
        }
    }

    public String name = "My kit";
    public List<Entry> entries = new ArrayList<>();

    public Entry at(int slot) {
        for (Entry e : entries) if (e.slot == slot) return e;
        return null;
    }

    public void put(Entry e) {
        remove(e.slot);
        entries.add(e);
    }

    public void remove(int slot) { entries.removeIf(e -> e.slot == slot); }

    public KitSpec copy() {
        KitSpec k = new KitSpec();
        k.name = name;
        for (Entry e : entries) k.entries.add(e.copy());
        return k;
    }

    /** Counts of an item across the kit (all slots). */
    public int count(String item) {
        int n = 0;
        for (Entry e : entries) if (item.equals(e.item)) n += e.count;
        return n;
    }

    /** First hotbar slot (0-8) holding item, or -1. */
    public int hotbarSlotOf(String item) {
        for (int s = 0; s < 9; s++) {
            Entry e = at(s);
            if (e != null && item.equals(e.item)) return s;
        }
        return -1;
    }

    // ------------------------------------------------------------------ the default crystal kit

    private static final String PROT = "minecraft:protection";
    private static final String BLAST = "minecraft:blast_protection";
    private static final String UNB = "minecraft:unbreaking";
    private static final String MEND = "minecraft:mending";

    /**
     * The standard crystal PvP kit (what practice servers hand out): netherite armour with
     * Protection IV (Blast Protection IV on the leggings), Unbreaking III and Mending, a Sharpness V
     * sword, a full hotbar, and spare totems, crystals, obsidian and gapples.
     *
     * @param roleSlots role id (Kit.Role.comboId) -> hotbar slot 0-8, the player's own layout.
     */
    public static KitSpec crystalDefault(Map<String, Integer> roleSlots) {
        KitSpec k = new KitSpec();
        k.name = "Standard crystal kit";
        String[][] roles = {
                {"xp", "minecraft:experience_bottle", "64"},
                {"sword", "minecraft:netherite_sword", "1"},
                {"obsidian", "minecraft:obsidian", "64"},
                {"anchor", "minecraft:respawn_anchor", "64"},
                {"glowstone", "minecraft:glowstone", "64"},
                {"crystal", "minecraft:end_crystal", "64"},
                {"gapple", "minecraft:golden_apple", "64"},
                {"pearl", "minecraft:ender_pearl", "16"},
                {"totem", "minecraft:totem_of_undying", "1"}};
        for (int i = 0; i < roles.length; i++) {
            int slot = roleSlots.getOrDefault(roles[i][0], i);
            Entry e = new Entry(slot, roles[i][1], Integer.parseInt(roles[i][2]));
            if (roles[i][0].equals("sword")) e.ench("minecraft:sharpness", 5).ench(UNB, 3).ench(MEND, 1);
            k.put(e);
        }
        for (int i = 9; i < 15; i++) k.put(new Entry(i, "minecraft:totem_of_undying", 1));
        k.put(new Entry(15, "minecraft:end_crystal", 64));
        k.put(new Entry(16, "minecraft:obsidian", 64));
        k.put(new Entry(17, "minecraft:golden_apple", 64));
        k.put(new Entry(18, "minecraft:experience_bottle", 64));
        k.put(new Entry(19, "minecraft:respawn_anchor", 64));
        k.put(new Entry(20, "minecraft:glowstone", 64));
        k.put(pickaxe(21));
        k.put(new Entry(HEAD, "minecraft:netherite_helmet", 1).ench(PROT, 4).ench(UNB, 3).ench(MEND, 1));
        k.put(new Entry(CHEST, "minecraft:netherite_chestplate", 1).ench(PROT, 4).ench(UNB, 3).ench(MEND, 1));
        k.put(new Entry(LEGS, "minecraft:netherite_leggings", 1).ench(BLAST, 4).ench(UNB, 3).ench(MEND, 1));
        k.put(new Entry(FEET, "minecraft:netherite_boots", 1).ench(PROT, 4).ench("minecraft:feather_falling", 4).ench(UNB, 3).ench(MEND, 1));
        k.put(new Entry(OFFHAND, "minecraft:totem_of_undying", 1));
        return k;
    }

    /** For mining out of traps and cities: Efficiency V netherite. */
    private static Entry pickaxe(int slot) {
        return new Entry(slot, "minecraft:netherite_pickaxe", 1).ench("minecraft:efficiency", 5).ench(UNB, 3).ench(MEND, 1);
    }

    private static Entry armour(int slot, String piece, boolean blast) {
        return new Entry(slot, "minecraft:netherite_" + piece, 1).ench(blast ? BLAST : PROT, 4).ench(UNB, 3).ench(MEND, 1);
    }

    private static void netheriteArmour(KitSpec k) {
        k.put(armour(HEAD, "helmet", false));
        k.put(armour(CHEST, "chestplate", false));
        k.put(armour(LEGS, "leggings", false));
        k.put(armour(FEET, "boots", false).ench("minecraft:feather_falling", 4));
    }

    /** Sword fights: sword, axe (shield breaker), shield, gapples, pearls, a few totems. */
    public static KitSpec swordDefault() {
        KitSpec k = new KitSpec();
        k.name = "Standard sword kit";
        netheriteArmour(k);
        k.put(new Entry(0, "minecraft:netherite_sword", 1).ench("minecraft:sharpness", 5).ench(UNB, 3).ench(MEND, 1));
        k.put(new Entry(1, "minecraft:netherite_axe", 1).ench("minecraft:sharpness", 5).ench(UNB, 3).ench(MEND, 1));
        k.put(new Entry(2, "minecraft:golden_apple", 16));
        k.put(new Entry(3, "minecraft:ender_pearl", 16));
        k.put(new Entry(4, "minecraft:experience_bottle", 64));
        k.put(new Entry(5, "minecraft:cooked_beef", 64));
        k.put(new Entry(8, "minecraft:totem_of_undying", 1));
        for (int i = 9; i < 12; i++) k.put(new Entry(i, "minecraft:totem_of_undying", 1));
        k.put(pickaxe(13));
        k.put(new Entry(OFFHAND, "minecraft:shield", 1).ench(UNB, 3).ench(MEND, 1));
        return k;
    }

    /** Mace fights: mace (density / wind burst), sword, elytra, rockets, wind charges, totems. */
    public static KitSpec maceDefault() {
        KitSpec k = new KitSpec();
        k.name = "Standard mace kit";
        netheriteArmour(k);
        k.put(new Entry(0, "minecraft:mace", 1).ench("minecraft:density", 5).ench("minecraft:wind_burst", 1).ench(UNB, 3).ench(MEND, 1));
        k.put(new Entry(1, "minecraft:netherite_sword", 1).ench("minecraft:sharpness", 5).ench(UNB, 3).ench(MEND, 1));
        k.put(new Entry(2, "minecraft:wind_charge", 64));
        k.put(new Entry(3, "minecraft:firework_rocket", 64));
        k.put(new Entry(4, "minecraft:elytra", 1).ench(UNB, 3).ench(MEND, 1));
        k.put(new Entry(5, "minecraft:golden_apple", 32));
        k.put(new Entry(6, "minecraft:ender_pearl", 16));
        k.put(new Entry(7, "minecraft:experience_bottle", 64));
        k.put(new Entry(8, "minecraft:totem_of_undying", 1));
        for (int i = 9; i < 15; i++) k.put(new Entry(i, "minecraft:totem_of_undying", 1));
        k.put(pickaxe(15));
        k.put(new Entry(OFFHAND, "minecraft:totem_of_undying", 1));
        return k;
    }

    /** Crystal + anchor + mace + elytra: everything at once. */
    public static KitSpec everythingDefault(Map<String, Integer> roleSlots) {
        KitSpec k = crystalDefault(roleSlots);
        k.name = "Everything kit";
        k.put(new Entry(26, "minecraft:mace", 1).ench("minecraft:density", 5).ench("minecraft:wind_burst", 1).ench(UNB, 3).ench(MEND, 1));
        k.put(new Entry(22, "minecraft:elytra", 1).ench(UNB, 3).ench(MEND, 1));
        k.put(new Entry(23, "minecraft:firework_rocket", 64));
        k.put(new Entry(24, "minecraft:wind_charge", 64));
        k.put(new Entry(25, "minecraft:netherite_axe", 1).ench("minecraft:sharpness", 5));
        return k;
    }

    /** The standard kit for a mode (UHC uses the sword kit for now). */
    public static KitSpec defaultFor(Mode mode, Map<String, Integer> roleSlots) {
        return switch (mode) {
            case CRYSTAL -> crystalDefault(roleSlots);
            case SWORD, UHC -> swordDefault();
            case MACE -> maceDefault();
            case EVERYTHING -> everythingDefault(roleSlots);
        };
    }

    /**
     * Hotbar positions of the Combo Binds roles in this kit (role id -> slot 1-9), for syncing the
     * binds to a custom kit. Roles whose item is not on the hotbar are left out.
     */
    public Map<String, Integer> roleSlots() {
        Map<String, Integer> out = new LinkedHashMap<>();
        String[][] roles = {
                {"xp", "minecraft:experience_bottle"}, {"sword", "minecraft:netherite_sword"}, {"obsidian", "minecraft:obsidian"},
                {"anchor", "minecraft:respawn_anchor"}, {"glowstone", "minecraft:glowstone"}, {"crystal", "minecraft:end_crystal"},
                {"gapple", "minecraft:golden_apple"}, {"pearl", "minecraft:ender_pearl"}, {"totem", "minecraft:totem_of_undying"}};
        for (String[] r : roles) {
            int s = hotbarSlotOf(r[1]);
            if (s < 0 && r[0].equals("sword")) s = firstSword();
            if (s >= 0) out.put(r[0], s + 1);
        }
        return out;
    }

    private int firstSword() {
        for (int s = 0; s < 9; s++) {
            Entry e = at(s);
            if (e != null && e.item.endsWith("_sword")) return s;
        }
        return -1;
    }
}
