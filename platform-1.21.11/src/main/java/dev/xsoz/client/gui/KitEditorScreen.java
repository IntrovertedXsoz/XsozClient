package dev.xsoz.client.gui;

import dev.xsoz.client.modules.combat.ComboBinds;
import dev.xsoz.client.render.Anim;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.Kit;
import dev.xsoz.client.training.KitSpec;
import dev.xsoz.client.training.KitStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Select Kit: build the kit every drill hands out - any item or block in the game, any count, any
 * enchantment the item can actually take.
 *
 * <ul>
 *   <li>Left: your inventory (armour, offhand, 27 slots, hotbar). Click an item on the right to pick
 *   it up, click a slot to put it there. Click a filled slot to pick its item back up. Drop it
 *   outside to delete it. Scroll over a slot to change the count.</li>
 *   <li>Whatever you place (or right-click) opens in the inspector below: count, and every
 *   enchantment that item can take, as level chips. Picking an enchantment that conflicts with one
 *   already on the item (Sharpness vs Smite...) swaps them. Enchantments the item can't take are
 *   listed greyed out with the reason.</li>
 * </ul>
 * Every change saves immediately. For Crystal PvP, Combo Binds follow the kit's hotbar.
 */
public final class KitEditorScreen extends Screen {
    private enum Cat {
        PVP("PvP"), ALL("All"), COMBAT("Combat"), BLOCKS("Blocks"), FOOD("Food"), TOOLS("Tools"), POTIONS("Potions"), MISC("Misc");

        final String title;

        Cat(String t) { title = t; }
    }

    private static RegistryWrapper.WrapperLookup builtin;
    private static final int SLOT = 18;

    private final Screen parent;
    private final Anim open = new Anim(0f, 10f);
    private final HoverTip tip = new HoverTip();
    private final List<int[]> clickRects = new ArrayList<>();
    private final List<Runnable> clickActions = new ArrayList<>();
    private KitSpec.Mode mode = KitSpec.Mode.CRYSTAL;
    private KitSpec work;
    private boolean use;
    private KitSpec.Entry cursor;
    private int selected = -1;
    private String search = "";
    private Cat cat = Cat.PVP;
    private int browserScroll;
    private int browserRows;
    private int enchScroll;
    private boolean showAll;
    private final List<KitSpec.Entry> catalogue = new ArrayList<>();
    private final Map<String, ItemStack> stackCache = new LinkedHashMap<>();
    private int x0, y0, w, h;
    private boolean compact;
    // hit areas computed while drawing
    private int invX, invY, browX, browY, browW, browH, cols;
    private String note;
    private long noteAt;

    public KitEditorScreen(Screen parent) { this(parent, KitSpec.Mode.CRYSTAL); }

    public KitEditorScreen(Screen parent, KitSpec.Mode mode) {
        super(Text.literal("Kits"));
        this.parent = parent;
        load(mode);
    }

    /** For the client GameTest: open a slot in the inspector. */
    public void selectSlotForTests(int slot) {
        selected = slot;
        enchScroll = 0;
    }

    // ------------------------------------------------------------------ data

    private RegistryWrapper.WrapperLookup lookup() {
        if (client != null && client.world != null) return client.world.getRegistryManager();
        if (builtin == null) builtin = BuiltinRegistries.createWrapperLookup();
        return builtin;
    }

    private void load(KitSpec.Mode m) {
        mode = m;
        KitSpec saved = KitStore.custom(m);
        use = KitStore.usesCustom(m);
        if (saved != null) work = saved.copy();
        else work = standardFor(m);
        selected = -1;
        cursor = null;
        enchScroll = 0;
    }

    private static KitSpec standardFor(KitSpec.Mode m) {
        Map<String, Integer> ids = new LinkedHashMap<>();
        ComboBinds cb = ComboBinds.INSTANCE;
        for (Kit.Role r : Kit.Role.values()) ids.put(r.comboId, cb == null ? r.defaultSlot() : cb.slotOf(r.comboId) - 1);
        return KitSpec.defaultFor(m, ids);
    }

    private void changed() {
        if (!use) use = true;
        KitStore.put(mode, work, use);
        if (mode == KitSpec.Mode.CRYSTAL && use && ComboBinds.INSTANCE != null) ComboBinds.INSTANCE.syncSlots(work.roleSlots());
    }

    private void say(String s) {
        note = s;
        noteAt = System.currentTimeMillis();
    }

    private ItemStack stackOf(KitSpec.Entry e) {
        if (e == null) return ItemStack.EMPTY;
        String key = e.item + "|" + e.count + "|" + e.ench + "|" + e.potion;
        return stackCache.computeIfAbsent(key, k -> Kit.toStack(e, lookup())).copy();
    }

    private void buildCatalogue() {
        catalogue.clear();
        String q = search.trim().toLowerCase(Locale.ROOT);
        List<String> pvp = List.of("end_crystal", "obsidian", "respawn_anchor", "glowstone", "totem_of_undying", "golden_apple",
                "enchanted_golden_apple", "ender_pearl", "experience_bottle", "netherite_sword", "netherite_axe", "mace",
                "netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots", "elytra", "shield",
                "netherite_pickaxe", "cobweb", "wind_charge", "chorus_fruit", "crossbow", "bow", "arrow", "tipped_arrow",
                "splash_potion", "potion", "diamond_sword", "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots",
                "crying_obsidian", "ender_chest", "lava_bucket", "water_bucket", "flint_and_steel", "cooked_beef");
        List<Item> items = new ArrayList<>();
        if (cat == Cat.PVP && q.isEmpty()) {
            for (String id : pvp) items.add(Registries.ITEM.get(Identifier.ofVanilla(id)));
        } else {
            for (Item it : Registries.ITEM) if (it != Items.AIR) items.add(it);
        }
        for (Item it : items) {
            String id = Registries.ITEM.getId(it).toString();
            boolean potionLike = it == Items.POTION || it == Items.SPLASH_POTION || it == Items.LINGERING_POTION || it == Items.TIPPED_ARROW;
            if (potionLike && (cat == Cat.POTIONS || !q.isEmpty())) {
                for (var pe : Registries.POTION.getIndexedEntries()) {
                    KitSpec.Entry e = new KitSpec.Entry(-1, id, 1);
                    e.potion = pe.getKey().map(k -> k.getValue().toString()).orElse(null);
                    if (matches(e, q) && inCat(it, Cat.POTIONS)) catalogue.add(e);
                }
                continue;
            }
            KitSpec.Entry e = new KitSpec.Entry(-1, id, 1);
            if (!matches(e, q) || !inCat(it, cat)) continue;
            catalogue.add(e);
        }
    }

    private boolean matches(KitSpec.Entry e, String q) {
        if (q.isEmpty()) return true;
        String name = stackOf(e).getName().getString().toLowerCase(Locale.ROOT);
        return name.contains(q) || e.item.contains(q);
    }

    private static boolean inCat(Item it, Cat c) {
        ItemStack st = new ItemStack(it);
        boolean armour = st.isIn(ItemTags.HEAD_ARMOR) || st.isIn(ItemTags.CHEST_ARMOR) || st.isIn(ItemTags.LEG_ARMOR) || st.isIn(ItemTags.FOOT_ARMOR)
                || st.contains(DataComponentTypes.EQUIPPABLE) && !(it instanceof BlockItem);
        boolean weapon = st.isIn(ItemTags.SWORDS) || it == Items.MACE || it == Items.BOW || it == Items.CROSSBOW || it == Items.TRIDENT
                || it == Items.SHIELD || st.isIn(ItemTags.ARROWS) || it == Items.TOTEM_OF_UNDYING || it == Items.END_CRYSTAL || it == Items.WIND_CHARGE;
        boolean tool = st.isIn(ItemTags.PICKAXES) || st.isIn(ItemTags.AXES) || st.isIn(ItemTags.SHOVELS) || st.isIn(ItemTags.HOES)
                || it == Items.FLINT_AND_STEEL || it == Items.SHEARS || it == Items.FISHING_ROD;
        boolean food = st.contains(DataComponentTypes.FOOD);
        boolean potion = it == Items.POTION || it == Items.SPLASH_POTION || it == Items.LINGERING_POTION || it == Items.TIPPED_ARROW
                || it == Items.EXPERIENCE_BOTTLE;
        return switch (c) {
            case PVP, ALL -> true;
            case COMBAT -> armour || weapon;
            case BLOCKS -> it instanceof BlockItem;
            case FOOD -> food;
            case TOOLS -> tool;
            case POTIONS -> potion;
            case MISC -> !armour && !weapon && !tool && !food && !potion && !(it instanceof BlockItem);
        };
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        w = Math.min(width - 16, 700);
        h = Math.min(height - 16, 410);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        open.snap(0f);
        open.target(1f);
        buildCatalogue();
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Gfx.rect(c, 0, 0, width, height, Theme.alpha(0xC8090A0C, open.get()));
    }

    private void click(int x, int y, int bw, int bh, Runnable r) {
        clickRects.add(new int[] {x, y, bw, bh});
        clickActions.add(r);
    }

    private boolean button(DrawContext c, String label, int x, int y, int bw, int bh, boolean primary, int mx, int my, Runnable r) {
        boolean hov = Gfx.hovered(mx, my, x, y, bw, bh);
        Gfx.round(c, x, y, bw, bh, 4, primary ? (hov ? Theme.ACCENT_HOVER : Theme.accent()) : hov ? Theme.HOVER : Theme.RAISED);
        Gfx.textCentered(c, label, x + bw / 2, y + (bh - 8) / 2, primary ? Theme.ON_ACCENT : Theme.TEXT);
        click(x, y, bw, bh, r);
        return hov;
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        clickRects.clear();
        clickActions.clear();
        tip.begin();
        Gfx.shadow(c, x0, y0, w, h, 6, 1f);
        Gfx.round(c, x0, y0, w, h, 6, 0xFA111317);
        Gfx.outline(c, x0, y0, w, h, 1, Theme.LINE_SOFT);
        Gfx.boldScaled(c, "Kits", x0 + 12, y0 + 11, 1.3f, Theme.TEXT);

        // game modes
        int tx = x0 + 60;
        for (KitSpec.Mode m : KitSpec.Mode.values()) {
            int tw = Gfx.width(m.title) + 16;
            boolean sel = m == mode;
            boolean hov = Gfx.hovered(mx, my, tx, y0 + 9, tw, 16);
            Gfx.round(c, tx, y0 + 9, tw, 16, 8, sel ? Theme.withAlpha(Theme.accent(), 0x30) : hov ? Theme.HOVER : Theme.SURFACE);
            Gfx.text(c, m.title, tx + 8, y0 + 13, sel ? Theme.accent() : Theme.TEXT_2);
            if (hov) tip.offer("mode-" + m, m == KitSpec.Mode.CRYSTAL ? "The kit every Crystal PvP drill and the Sparring Bot hand out."
                    : m == KitSpec.Mode.UHC ? "A kit for UHC (saved for UHC training)." : "The kit you (and the bots) get in Free Roam " + m.title + " fights.");
            final KitSpec.Mode mm = m;
            click(tx, y0 + 9, tw, 16, () -> load(mm));
            tx += tw + 5;
        }
        if (button(c, "Done", x0 + w - 56, y0 + 9, 46, 16, true, mx, my, this::close)) tip.offer("done", "Back. Your kit is already saved.");

        int left = x0 + 12;
        int top = y0 + 36;
        int leftW = 214;
        renderInventory(c, mx, my, left, top);
        renderUseToggle(c, mx, my, left, top + 100, leftW);
        int rightX = left + leftW + 12;
        int rightW = w - leftW - 36;
        compact = h < 330;
        if (!compact) {
            renderInspector(c, mx, my, left, top + 124, leftW, y0 + h - 10 - (top + 124));
            renderBrowser(c, mx, my, rightX, top, rightW, h - 46);
        } else if (selected >= 0 && work.at(selected) != null) {
            // small window: the inspector takes the item browser's place until "Items" is clicked
            renderInspector(c, mx, my, rightX, top - 4, rightW, h - 40);
            if (button(c, "Items", rightX + rightW - 44, top, 44, 14, false, mx, my, () -> selected = -1)) {
                tip.offer("items", "Back to the item list.");
            }
        } else {
            renderBrowser(c, mx, my, rightX, top, rightW, h - 46);
            int ly = top + 120;
            for (String l : Gfx.wrap("Right-click a slot to enchant it or set its count.", leftW)) {
                Gfx.text(c, l, left, ly, Theme.TEXT_3);
                ly += 10;
            }
            renderKitCheck(c, left, ly + 4, leftW);
        }

        long age = System.currentTimeMillis() - noteAt;
        if (note != null && age < 3500) {
            int nw = Gfx.width(note) + 16;
            Gfx.round(c, x0 + w / 2 - nw / 2, y0 + h - 22, nw, 14, 4, Theme.alpha(0xF0202630, Math.min(1f, (3500 - age) / 400f)));
            Gfx.textCentered(c, note, x0 + w / 2, y0 + h - 19, Theme.TEXT);
        }
        if (cursor != null) {
            ItemStack st = stackOf(cursor);
            c.drawItem(st, mx - 8, my - 8);
            c.drawStackOverlay(textRenderer, st, mx - 8, my - 8);
        }
        tip.render(c, mx, my);
    }

    // ------------------------------------------------------------------ inventory

    private int[] slotPos(int slot) {
        int gx = invX + 26;
        if (slot == KitSpec.HEAD) return new int[] {invX, invY};
        if (slot == KitSpec.CHEST) return new int[] {invX, invY + SLOT};
        if (slot == KitSpec.LEGS) return new int[] {invX, invY + SLOT * 2};
        if (slot == KitSpec.FEET) return new int[] {invX, invY + SLOT * 3};
        if (slot == KitSpec.OFFHAND) return new int[] {invX, invY + SLOT * 4 + 4};
        if (slot < 9) return new int[] {gx + slot * SLOT, invY + SLOT * 3 + 4};
        int i = slot - 9;
        return new int[] {gx + (i % 9) * SLOT, invY + (i / 9) * SLOT};
    }

    private void renderInventory(DrawContext c, int mx, int my, int x, int y) {
        invX = x;
        invY = y;
        Gfx.round(c, x - 4, y - 4, 26 + 9 * SLOT + 8, SLOT * 5 + 12, 5, Theme.SURFACE);
        for (int slot = 0; slot < KitSpec.SLOTS; slot++) {
            int[] p = slotPos(slot);
            boolean hov = Gfx.hovered(mx, my, p[0], p[1], SLOT, SLOT);
            boolean sel = slot == selected;
            Gfx.round(c, p[0], p[1], SLOT - 1, SLOT - 1, 2, sel ? Theme.withAlpha(Theme.accent(), 0x55) : hov ? Theme.HOVER : 0xFF0C0D10);
            if (sel) Gfx.outline(c, p[0], p[1], SLOT - 1, SLOT - 1, 1, Theme.accent());
            KitSpec.Entry e = work.at(slot);
            if (e != null) {
                ItemStack st = stackOf(e);
                c.drawItem(st, p[0] + 1, p[1] + 1);
                c.drawStackOverlay(textRenderer, st, p[0] + 1, p[1] + 1);
            } else if (slot >= KitSpec.FEET) {
                String g = slot == KitSpec.HEAD ? "H" : slot == KitSpec.CHEST ? "C" : slot == KitSpec.LEGS ? "L" : slot == KitSpec.FEET ? "B" : "O";
                Gfx.textCentered(c, g, p[0] + 8, p[1] + 5, Theme.LINE);
            }
            if (slot < 9 && e == null) Gfx.text(c, String.valueOf(slot + 1), p[0] + 2, p[1] + 2, Theme.LINE);
            if (hov) {
                String what = e == null ? slotName(slot) + " - empty" : stackOf(e).getName().getString() + " x" + e.count + enchSummary(e);
                tip.offer("slot-" + slot + what, what + ". " + (cursor != null ? "Click to put the held item here." : e != null
                        ? "Click to pick it up, right-click to edit, scroll to change the count, middle-click to delete." : "Pick an item on the right first."));
            }
            final int s = slot;
            click(p[0], p[1], SLOT, SLOT, () -> clickSlot(s, false));
        }
        Gfx.text(c, "Hotbar", invX + 26 + 9 * SLOT - Gfx.width("Hotbar"), invY + SLOT * 4 + 7, Theme.TEXT_3);
    }

    private static String slotName(int slot) {
        if (slot == KitSpec.HEAD) return "Helmet";
        if (slot == KitSpec.CHEST) return "Chestplate";
        if (slot == KitSpec.LEGS) return "Leggings";
        if (slot == KitSpec.FEET) return "Boots";
        if (slot == KitSpec.OFFHAND) return "Offhand";
        return slot < 9 ? "Hotbar " + (slot + 1) : "Inventory";
    }

    private String enchSummary(KitSpec.Entry e) {
        if (e.ench.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        var reg = lookup().getOrThrow(RegistryKeys.ENCHANTMENT);
        for (var en : e.ench.entrySet()) {
            Identifier id = Identifier.tryParse(en.getKey());
            var ref = id == null ? null : reg.getOptional(net.minecraft.registry.RegistryKey.of(RegistryKeys.ENCHANTMENT, id)).orElse(null);
            parts.add(ref == null ? en.getKey() : Enchantment.getName(ref, en.getValue()).getString());
        }
        return " (" + String.join(", ", parts) + ")";
    }

    private void clickSlot(int slot, boolean right) {
        KitSpec.Entry there = work.at(slot);
        if (right) {
            if (there != null) {
                selected = slot;
                enchScroll = 0;
            }
            return;
        }
        if (cursor != null) {
            if (!fits(cursor, slot)) {
                say(stackOf(cursor).getName().getString() + " can't go in the " + slotName(slot).toLowerCase(Locale.ROOT) + " slot.");
                return;
            }
            KitSpec.Entry placed = cursor.copy();
            placed.slot = slot;
            if (there != null && there.item.equals(placed.item) && there.ench.equals(placed.ench) && java.util.Objects.equals(there.potion, placed.potion)) {
                int max = stackOf(there).getMaxCount();
                there.count = Math.min(max, there.count + placed.count);
                cursor = null;
            } else {
                work.put(placed);
                cursor = there == null ? null : there.copy();
            }
            if (!compact) selected = slot;
            enchScroll = 0;
            changed();
        } else if (there != null) {
            cursor = there.copy();
            work.remove(slot);
            if (selected == slot) selected = -1;
            changed();
        }
    }

    /** Armour only in its own slot, as in the game. */
    private boolean fits(KitSpec.Entry e, int slot) {
        if (slot < KitSpec.FEET || slot == KitSpec.OFFHAND) return true;
        ItemStack st = stackOf(e);
        var eq = st.get(DataComponentTypes.EQUIPPABLE);
        if (eq == null) return false;
        var want = switch (slot) {
            case KitSpec.HEAD -> net.minecraft.entity.EquipmentSlot.HEAD;
            case KitSpec.CHEST -> net.minecraft.entity.EquipmentSlot.CHEST;
            case KitSpec.LEGS -> net.minecraft.entity.EquipmentSlot.LEGS;
            default -> net.minecraft.entity.EquipmentSlot.FEET;
        };
        return eq.slot() == want;
    }

    private void renderUseToggle(DrawContext c, int mx, int my, int x, int y, int w) {
        boolean on = use;
        Gfx.round(c, x, y + 1, 18, 10, 5, on ? Theme.accent() : Theme.LINE);
        Gfx.round(c, x + (on ? 9 : 1), y + 2, 8, 8, 4, on ? Theme.ON_ACCENT : Theme.TEXT_3);
        String label = mode == KitSpec.Mode.CRYSTAL ? "Use this kit in training" : "Use this kit for " + mode.title;
        Gfx.text(c, label, x + 24, y + 2, Theme.TEXT_2);
        boolean hov = Gfx.hovered(mx, my, x, y, w - 60, 12);
        if (hov) tip.offer("use", on ? "On: drills hand out this kit (your Combo Binds follow its hotbar). Off: the standard crystal kit."
                : "Off: drills use the standard crystal kit in your Combo Binds layout. Turn on (or edit the kit) to use yours.");
        click(x, y, w - 60, 12, () -> {
            use = !use;
            KitStore.put(mode, work, use);
            if (use && mode == KitSpec.Mode.CRYSTAL && ComboBinds.INSTANCE != null) ComboBinds.INSTANCE.syncSlots(work.roleSlots());
        });
        if (button(c, "Reset", x + w - 52, y - 2, 48, 15, false, mx, my, () -> {
            work = standardFor(mode);
            selected = -1;
            KitStore.put(mode, work, false);
            use = false;
            say("Back to the standard " + mode.title + " kit.");
        })) tip.offer("reset", "Back to the standard " + mode.title + " kit (and stop using a custom one).");
    }

    // ------------------------------------------------------------------ inspector

    private void renderInspector(DrawContext c, int mx, int my, int x, int y, int iw, int ih) {
        Gfx.round(c, x - 4, y, iw + 8, ih, 5, Theme.SURFACE);
        KitSpec.Entry e = selected < 0 ? null : work.at(selected);
        if (e == null) {
            int ly = y + 10;
            for (String l : Gfx.wrap("Place an item (or right-click one) to edit it here: count and enchantments.", iw - 8)) {
                Gfx.text(c, l, x + 4, ly, Theme.TEXT_3);
                ly += 10;
            }
            ly += 6;
            for (String l : Gfx.wrap("Pick up: click. Delete: drop it outside, or middle-click. Count: scroll over a slot.", iw - 8)) {
                Gfx.text(c, l, x + 4, ly, Theme.TEXT_3);
                ly += 10;
            }
            renderKitCheck(c, x + 4, y + ih - 34, iw - 8);
            return;
        }
        ItemStack st = stackOf(e);
        c.drawItem(st, x + 2, y + 6);
        Gfx.textBold(c, Gfx.fit(st.getName().getString(), iw - 30), x + 22, y + 6, Theme.TEXT);
        Gfx.text(c, slotName(selected), x + 22, y + 16, Theme.TEXT_3);
        // count
        int cy = y + 30;
        int max = Kit.toStack(new KitSpec.Entry(0, e.item, 1), lookup()).getMaxCount();
        Gfx.text(c, "Count", x + 2, cy + 3, Theme.TEXT_2);
        if (max > 1) {
            button(c, "-", x + 40, cy, 16, 14, false, mx, my, () -> {
                e.count = Math.max(1, e.count - 1);
                changed();
            });
            Gfx.textCentered(c, String.valueOf(e.count), x + 70, cy + 3, Theme.TEXT);
            button(c, "+", x + 84, cy, 16, 14, false, mx, my, () -> {
                e.count = Math.min(max, e.count + 1);
                changed();
            });
            button(c, "1", x + 106, cy, 18, 14, false, mx, my, () -> {
                e.count = 1;
                changed();
            });
            button(c, "Max", x + 128, cy, 28, 14, false, mx, my, () -> {
                e.count = max;
                changed();
            });
        } else {
            Gfx.text(c, "1 (doesn't stack)", x + 40, cy + 3, Theme.TEXT_3);
        }
        // enchantments
        cy += 20;
        Gfx.text(c, "Enchantments", x + 2, cy, Theme.TEXT_2);
        String sa = showAll ? "Only applicable" : "Show all";
        int saw = Gfx.width(sa);
        boolean saHov = Gfx.hovered(mx, my, x + iw - saw - 2, cy - 1, saw + 2, 10);
        Gfx.text(c, sa, x + iw - saw, cy, saHov ? Theme.TEXT : Theme.TEXT_3);
        if (saHov) tip.offer("showall", showAll ? "Hide the enchantments this item can't take." : "Also list (greyed out) the enchantments this item can't take, and why.");
        click(x + iw - saw - 2, cy - 1, saw + 4, 10, () -> showAll = !showAll);
        cy += 12;
        int listTop = cy;
        int listBottom = y + ih - 6;
        var reg = lookup().getOrThrow(RegistryKeys.ENCHANTMENT);
        List<RegistryEntry.Reference<Enchantment>> all = new ArrayList<>(reg.streamEntries().toList());
        all.sort((a, b) -> a.value().description().getString().compareTo(b.value().description().getString()));
        ItemStack plain = Kit.toStack(new KitSpec.Entry(0, e.item, 1), null);
        List<RegistryEntry.Reference<Enchantment>> rows = new ArrayList<>();
        for (var en : all) if (showAll || en.value().isAcceptableItem(plain)) rows.add(en);
        if (rows.isEmpty()) {
            Gfx.text(c, "This item can't take any enchantment.", x + 2, cy, Theme.TEXT_3);
            return;
        }
        int rowH = 13;
        int visible = Math.max(1, (listBottom - listTop) / rowH);
        enchScroll = Math.max(0, Math.min(enchScroll, Math.max(0, rows.size() - visible)));
        c.enableScissor(x - 4, listTop, x + iw + 4, listBottom);
        int ry = listTop - 0;
        for (int i = enchScroll; i < rows.size() && ry < listBottom; i++) {
            var en = rows.get(i);
            String id = en.getKey().map(k -> k.getValue().toString()).orElse("?");
            boolean ok = en.value().isAcceptableItem(plain);
            int have = e.ench.getOrDefault(id, 0);
            String conflict = conflictWith(e, en, reg);
            String name = en.value().description().getString();
            int maxL = en.value().getMaxLevel();
            boolean rowHov = Gfx.hovered(mx, my, x, ry, iw, rowH) && my < listBottom;
            if (rowHov) Gfx.round(c, x - 2, ry - 1, iw + 4, rowH, 3, Theme.HOVER);
            int nameCol = !ok ? Theme.LINE : have > 0 ? Theme.accent() : conflict != null ? Theme.TEXT_3 : Theme.TEXT;
            int chipW = maxL > 5 ? 12 : 14;
            int chipsW = maxL * (chipW + 2);
            Gfx.text(c, Gfx.fit(name, iw - chipsW - 8), x + 2, ry + 2, nameCol);
            if (rowHov) {
                String why = !ok ? "Can't go on " + plain.getName().getString() + "."
                        : conflict != null ? "Conflicts with " + conflict + " - choosing a level swaps them." : have > 0 ? "Click the level again to remove it." : "Click a level to add it.";
                tip.offer("ench-" + id, name + " (max " + roman(maxL) + "). " + why);
            }
            if (ok) {
                for (int l = 1; l <= maxL; l++) {
                    int cx = x + iw - chipsW + (l - 1) * (chipW + 2);
                    boolean on = have == l;
                    boolean ch = Gfx.hovered(mx, my, cx, ry, chipW, 11) && my < listBottom;
                    Gfx.round(c, cx, ry, chipW, 11, 3, on ? Theme.accent() : ch ? Theme.HOVER : Theme.RAISED);
                    Gfx.textCentered(c, roman(l), cx + chipW / 2, ry + 2, on ? Theme.ON_ACCENT : Theme.TEXT_2);
                    final int lvl = l;
                    if (ry + 11 <= listBottom) click(cx, ry, chipW, 11, () -> setEnch(e, id, en, lvl, reg));
                }
            }
            ry += rowH;
        }
        c.disableScissor();
        if (rows.size() > visible) {
            int barH = Math.max(10, (listBottom - listTop) * visible / rows.size());
            int barY = listTop + (listBottom - listTop - barH) * enchScroll / Math.max(1, rows.size() - visible);
            Gfx.round(c, x + iw + 1, barY, 2, barH, 1, Theme.LINE);
        }
    }

    private String conflictWith(KitSpec.Entry e, RegistryEntry<Enchantment> en, RegistryWrapper.Impl<Enchantment> reg) {
        for (String other : e.ench.keySet()) {
            Identifier oid = Identifier.tryParse(other);
            if (oid == null) continue;
            var ref = reg.getOptional(net.minecraft.registry.RegistryKey.of(RegistryKeys.ENCHANTMENT, oid)).orElse(null);
            if (ref == null || ref.equals(en)) continue;
            if (!Enchantment.canBeCombined(ref, en)) return ref.value().description().getString();
        }
        return null;
    }

    private void setEnch(KitSpec.Entry e, String id, RegistryEntry<Enchantment> en, int lvl, RegistryWrapper.Impl<Enchantment> reg) {
        if (e.ench.getOrDefault(id, 0) == lvl) {
            e.ench.remove(id);
        } else {
            // a conflicting enchantment is swapped out, like choosing between them
            e.ench.keySet().removeIf(other -> {
                Identifier oid = Identifier.tryParse(other);
                if (oid == null || other.equals(id)) return false;
                var ref = reg.getOptional(net.minecraft.registry.RegistryKey.of(RegistryKeys.ENCHANTMENT, oid)).orElse(null);
                return ref != null && !Enchantment.canBeCombined(ref, en);
            });
            e.ench.put(id, lvl);
        }
        changed();
    }

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    /** Crystal kits: says which drill items are missing (drills add them to a free slot). */
    private void renderKitCheck(DrawContext c, int x, int y, int iw) {
        if (mode != KitSpec.Mode.CRYSTAL) return;
        List<String> missing = new ArrayList<>();
        Map<String, Integer> roles = work.roleSlots();
        for (Kit.Role r : Kit.Role.values()) if (!roles.containsKey(r.comboId)) missing.add(r.label);
        int ty = y;
        if (missing.isEmpty()) {
            Gfx.text(c, "Every drill item is on your hotbar.", x, ty, Theme.accent());
            ty += 10;
            Gfx.text(c, "Combo Binds follow this hotbar.", x, ty, Theme.TEXT_3);
        } else {
            for (String l : Gfx.wrap("Not on your hotbar: " + String.join(", ", missing) + ". Drills that need them add them to a free slot.", iw).stream().limit(3).toList()) {
                Gfx.text(c, l, x, ty, 0xFFFFB020);
                ty += 10;
            }
        }
    }

    // ------------------------------------------------------------------ browser

    private void renderBrowser(DrawContext c, int mx, int my, int x, int y, int bw, int bh) {
        Gfx.round(c, x - 4, y - 4, bw + 8, bh + 8, 5, Theme.SURFACE);
        // search
        Gfx.round(c, x, y, bw, 16, 4, 0xFF0C0D10);
        boolean blink = System.currentTimeMillis() / 500 % 2 == 0;
        String shown = search.isEmpty() ? "Search any item or block..." : search + (blink ? "_" : "");
        Gfx.text(c, Gfx.fit(shown, bw - 10), x + 6, y + 4, search.isEmpty() ? Theme.TEXT_3 : Theme.TEXT);
        // categories
        int cx = x;
        int cy = y + 21;
        for (Cat k : Cat.values()) {
            int tw = Gfx.width(k.title) + 12;
            if (cx + tw > x + bw) break;
            boolean sel = k == cat;
            boolean hov = Gfx.hovered(mx, my, cx, cy, tw, 14);
            Gfx.round(c, cx, cy, tw, 14, 7, sel ? Theme.withAlpha(Theme.accent(), 0x30) : hov ? Theme.HOVER : Theme.RAISED);
            Gfx.text(c, k.title, cx + 6, cy + 3, sel ? Theme.accent() : Theme.TEXT_2);
            final Cat kk = k;
            click(cx, cy, tw, 14, () -> {
                cat = kk;
                browserScroll = 0;
                buildCatalogue();
            });
            cx += tw + 4;
        }
        // grid
        browX = x;
        browY = y + 40;
        browW = bw;
        browH = bh - 40;
        cols = Math.max(1, browW / SLOT);
        int rowsVisible = browH / SLOT;
        browserRows = (catalogue.size() + cols - 1) / cols;
        browserScroll = Math.max(0, Math.min(browserScroll, Math.max(0, browserRows - rowsVisible)));
        c.enableScissor(browX, browY, browX + browW, browY + browH);
        for (int i = browserScroll * cols; i < catalogue.size(); i++) {
            int row = i / cols - browserScroll;
            if (row >= rowsVisible + 1) break;
            int gx = browX + (i % cols) * SLOT;
            int gy = browY + row * SLOT;
            KitSpec.Entry e = catalogue.get(i);
            boolean hov = Gfx.hovered(mx, my, gx, gy, SLOT, SLOT) && my < browY + browH;
            if (hov) Gfx.round(c, gx, gy, SLOT - 1, SLOT - 1, 2, Theme.HOVER);
            ItemStack st = stackOf(e);
            c.drawItem(st, gx + 1, gy + 1);
            if (hov) tip.offer("item-" + e.item + e.potion, st.getName().getString() + "  -  click to pick up, shift-click to add straight to your kit.");
            if (gy + SLOT <= browY + browH) click(gx, gy, SLOT, SLOT, () -> pickFromBrowser(e));
        }
        c.disableScissor();
        if (catalogue.isEmpty()) Gfx.textCentered(c, "Nothing matches \"" + search + "\".", browX + browW / 2, browY + 20, Theme.TEXT_3);
        if (browserRows > rowsVisible) {
            int barH = Math.max(12, browH * rowsVisible / browserRows);
            int barY = browY + (browH - barH) * browserScroll / Math.max(1, browserRows - rowsVisible);
            Gfx.round(c, browX + browW + 1, barY, 2, barH, 1, Theme.LINE);
        }
    }

    private boolean shiftDown;

    private void pickFromBrowser(KitSpec.Entry e) {
        KitSpec.Entry n = e.copy();
        n.count = stackOf(e).getMaxCount();
        if (shiftDown) {
            for (int s = 0; s < 36; s++) {
                if (work.at(s) == null) {
                    n.slot = s;
                    work.put(n);
                    selected = s;
                    changed();
                    say("Added to " + slotName(s).toLowerCase(Locale.ROOT) + ".");
                    return;
                }
            }
            say("Your inventory is full.");
            return;
        }
        cursor = n;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x();
        double my = click.y();
        shiftDown = (click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        int button = click.button();
        // right / middle click on slots
        if (button != 0) {
            for (int slot = 0; slot < KitSpec.SLOTS; slot++) {
                int[] p = slotPos(slot);
                if (!Gfx.hovered(mx, my, p[0], p[1], SLOT, SLOT)) continue;
                if (button == 1) clickSlot(slot, true);
                else if (button == 2 && work.at(slot) != null) {
                    work.remove(slot);
                    if (selected == slot) selected = -1;
                    changed();
                }
                return true;
            }
            if (button == 1 && cursor != null) {
                cursor = null;
                return true;
            }
            return super.mouseClicked(click, doubled);
        }
        for (int i = clickRects.size() - 1; i >= 0; i--) {
            int[] r = clickRects.get(i);
            if (Gfx.hovered(mx, my, r[0], r[1], r[2], r[3])) {
                client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.25f));
                clickActions.get(i).run();
                return true;
            }
        }
        // dropped outside: delete what is held
        if (cursor != null) {
            cursor = null;
            say("Removed.");
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double v) {
        for (int slot = 0; slot < KitSpec.SLOTS; slot++) {
            int[] p = slotPos(slot);
            if (!Gfx.hovered(mx, my, p[0], p[1], SLOT, SLOT)) continue;
            KitSpec.Entry e = work.at(slot);
            if (e != null) {
                int max = stackOf(e).getMaxCount();
                e.count = Math.max(1, Math.min(max, e.count + (v > 0 ? 1 : -1)));
                changed();
            }
            return true;
        }
        if (Gfx.hovered(mx, my, browX, browY, browW, browH)) {
            browserScroll = Math.max(0, browserScroll - (int) Math.signum(v) * 2);
            return true;
        }
        if (mx < browX) {
            enchScroll = Math.max(0, enchScroll - (int) Math.signum(v));
            return true;
        }
        return super.mouseScrolled(mx, my, hAmount, v);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int k = input.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) {
            if (cursor != null) {
                cursor = null;
                return true;
            }
            close();
            return true;
        }
        if (k == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            browserScroll = 0;
            buildCatalogue();
            return true;
        }
        if (k == GLFW.GLFW_KEY_DELETE && selected >= 0) {
            work.remove(selected);
            selected = -1;
            changed();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        String ch = input.asString();
        if (!ch.isEmpty() && search.length() < 32) {
            search += ch;
            browserScroll = 0;
            buildCatalogue();
        }
        return true;
    }

    @Override
    public void close() { client.setScreen(parent); }

    @Override
    public boolean shouldPause() { return false; }
}
