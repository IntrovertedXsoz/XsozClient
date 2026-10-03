package dev.xsoz.client.modules.hud;

import dev.xsoz.client.XsozClient;
import dev.xsoz.client.event.GameEvents;
import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.HudModule;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.module.ModuleManager;
import dev.xsoz.client.render.Anim;
import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.setting.BoolSetting;
import dev.xsoz.client.setting.ModeSetting;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/** Every HUD element, one small class each. */
public final class HudElements {
    private HudElements() { }

    // ------------------------------------------------------------------------------ CPS tracking

    /** Click timestamps, sampled every frame from GLFW (cheap: two calls per frame). */
    public static final class Cps {
        private static final Deque<Long> LEFT = new ArrayDeque<>();
        private static final Deque<Long> RIGHT = new ArrayDeque<>();
        private static boolean leftDown;
        private static boolean rightDown;

        private Cps() { }

        public static void sample(long window) {
            long now = System.currentTimeMillis();
            boolean l = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            boolean r = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            if (l && !leftDown) LEFT.addLast(now);
            if (r && !rightDown) RIGHT.addLast(now);
            leftDown = l;
            rightDown = r;
            while (!LEFT.isEmpty() && now - LEFT.peekFirst() > 1000) LEFT.removeFirst();
            while (!RIGHT.isEmpty() && now - RIGHT.peekFirst() > 1000) RIGHT.removeFirst();
        }

        public static int left() { return LEFT.size(); }

        public static int right() { return RIGHT.size(); }
    }

    // ------------------------------------------------------------------------------ elements

    public static final class Watermark extends HudModule {
        public Watermark() { super("Watermark", "Xsoz logo and FPS", true, 0.005f, 0.008f); }

        @Override
        protected void render(DrawContext c, boolean editor) {
            String fps = mc.getCurrentFps() + " fps";
            int lw = Math.round(Gfx.displayWidth("XSOZ") * 1.2f);
            int w = 8 + lw + 6 + Gfx.width(fps) + 6;
            panel(c, w, 16);
            Gfx.display(c, "XSOZ", 5, 4, 1.2f, Theme.accent());
            Gfx.text(c, fps, 5 + lw + 6, 4, Theme.TEXT_2);
        }
    }

    public static final class ModuleList extends HudModule {
        private final BoolSetting showHud = add(new BoolSetting("Show HUD modules", "Include HUD elements in the list", false));
        private final Map<Module, Anim> slide = new HashMap<>();

        public ModuleList() {
            super("Active Mods", "A small list of the mods you have on, top right", false, 0.86f, 0.008f);
            background.set(false);
        }

        @Override
        protected void render(DrawContext c, boolean editor) {
            List<Module> list = new ArrayList<>();
            for (Module m : ModuleManager.all()) {
                if (m == this || m.category() == Category.CLIENT) continue;
                if (!showHud.on() && m.category() == Category.HUD) continue;
                if (m.isEnabled() || slide.containsKey(m) && slide.get(m).peek() > 0.02f) list.add(m);
            }
            list.sort(Comparator.comparingInt((Module m) -> -Gfx.width(label(m))));
            int maxW = 60;
            for (Module m : list) maxW = Math.max(maxW, Gfx.width(label(m)) + 10);
            int y = 0;
            for (Module m : list) {
                float a = slide.computeIfAbsent(m, k -> new Anim(0f, 12f)).target(m.isEnabled() ? 1f : 0f).get();
                if (a < 0.02f) continue;
                String l = label(m);
                int lw = Gfx.width(l) + 8;
                int x = maxW - Math.round(lw * a);
                int h = Math.round(11 * a);
                Gfx.rect(c, x, y, lw, h, Theme.alpha(0xB0101114, a));
                Gfx.rect(c, maxW - 1, y, 1, h, Theme.alpha(Theme.accent(), a));
                Gfx.text(c, m.name(), x + 3, y + 2, Theme.alpha(Theme.TEXT, a));
                String suf = m.suffix();
                if (suf != null) Gfx.text(c, suf, x + 3 + Gfx.width(m.name() + " "), y + 2, Theme.alpha(Theme.TEXT_3, a));
                y += h;
            }
            this.width = maxW;
            this.height = Math.max(11, y);
        }

        private static String label(Module m) {
            String s = m.suffix();
            return s == null ? m.name() : m.name() + " " + s;
        }
    }

    public static final class Fps extends HudModule {
        public Fps() { super("FPS", "Frames per second", false, 0.005f, 0.06f); }

        @Override
        protected void render(DrawContext c, boolean editor) { line(c, "FPS", String.valueOf(mc.getCurrentFps())); }
    }

    public static final class Ping extends HudModule {
        public Ping() { super("Ping", "Your latency to the server", true, 0.005f, 0.10f); }

        @Override
        protected void render(DrawContext c, boolean editor) {
            int ping = -1;
            if (mc.getNetworkHandler() != null && mc.player != null) {
                PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
                if (e != null) ping = e.getLatency();
            }
            line(c, "Ping", ping < 0 ? "-" : ping + " ms");
        }
    }

    public static final class CpsDisplay extends HudModule {
        public CpsDisplay() { super("CPS", "Clicks per second", Category.COMBAT, true, 0.005f, 0.14f); }

        @Override
        protected void render(DrawContext c, boolean editor) { line(c, "CPS", Cps.left() + " | " + Cps.right()); }
    }

    public static final class Coordinates extends HudModule {
        private final BoolSetting facing = add(new BoolSetting("Facing", "Show the direction you face", true));

        public Coordinates() { super("Coordinates", "Your position", false, 0.005f, 0.18f); }

        @Override
        protected void render(DrawContext c, boolean editor) {
            if (mc.player == null) return;
            String s = String.format(Locale.ROOT, "%d, %d, %d", mc.player.getBlockPos().getX(), mc.player.getBlockPos().getY(), mc.player.getBlockPos().getZ());
            if (facing.on()) s += "  " + mc.player.getHorizontalFacing().asString().toUpperCase(Locale.ROOT).charAt(0);
            line(c, "XYZ", s);
        }
    }

    public static final class Clock extends HudModule {
        private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm");

        public Clock() { super("Clock", "Real-world time", false, 0.005f, 0.22f); }

        @Override
        protected void render(DrawContext c, boolean editor) { line(c, "", fmt.format(new Date())); }
    }

    public static final class Speed extends HudModule {
        public Speed() { super("Speed", "Horizontal speed in blocks per second", false, 0.005f, 0.26f); }

        @Override
        protected void render(DrawContext c, boolean editor) {
            if (mc.player == null) return;
            Vec3d v = mc.player.getVelocity();
            double bps = Math.sqrt(v.x * v.x + v.z * v.z) * 20.0;
            line(c, "Speed", String.format(Locale.ROOT, "%.1f b/s", bps));
        }
    }

    public static final class Memory extends HudModule {
        public Memory() { super("Memory", "Java heap in use", false, 0.005f, 0.30f); }

        @Override
        protected void render(DrawContext c, boolean editor) {
            Runtime rt = Runtime.getRuntime();
            long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
            long max = rt.maxMemory() >> 20;
            line(c, "Mem", used + " / " + max + " MB");
        }
    }

    public static final class ServerAddress extends HudModule {
        public ServerAddress() { super("Server", "The server you are on", false, 0.005f, 0.34f); }

        @Override
        protected void render(DrawContext c, boolean editor) {
            var info = mc.getCurrentServerEntry();
            line(c, "", info == null ? "Singleplayer" : info.address);
        }
    }

    public static final class Keystrokes extends HudModule {
        private final Map<String, Anim> anims = new HashMap<>();

        public Keystrokes() { super("Keystrokes", "WASD, mouse buttons and CPS", true, 0.005f, 0.70f); }

        private void key(DrawContext c, String label, int x, int y, int w, int h, boolean down) {
            float t = anims.computeIfAbsent(label, k -> new Anim(0f, 22f)).target(down ? 1f : 0f).get();
            Gfx.round(c, x, y, w, h, 3, Theme.lerp(0xA0101114, Theme.withAlpha(Theme.accent(), 0xE0), t));
            int tc = Theme.lerp(Theme.TEXT, Theme.ON_ACCENT, t);
            int tw = Gfx.widthBold(label);
            Gfx.textBold(c, label, x + (w - tw) / 2, y + (h - 8) / 2, tc);
        }

        @Override
        protected void render(DrawContext c, boolean editor) {
            this.width = 64;
            this.height = 76;
            var o = mc.options;
            key(c, "W", 22, 0, 20, 20, o.forwardKey.isPressed());
            key(c, "A", 0, 22, 20, 20, o.leftKey.isPressed());
            key(c, "S", 22, 22, 20, 20, o.backKey.isPressed());
            key(c, "D", 44, 22, 20, 20, o.rightKey.isPressed());
            key(c, "LMB " + Cps.left(), 0, 44, 31, 18, o.attackKey.isPressed());
            key(c, "RMB " + Cps.right(), 33, 44, 31, 18, o.useKey.isPressed());
            float t = anims.computeIfAbsent("space", k -> new Anim(0f, 22f)).target(o.jumpKey.isPressed() ? 1f : 0f).get();
            Gfx.round(c, 0, 64, 64, 12, 3, Theme.lerp(0xA0101114, Theme.withAlpha(Theme.accent(), 0xE0), t));
            Gfx.rect(c, 22, 69, 20, 2, Theme.lerp(Theme.TEXT, Theme.ON_ACCENT, t));
        }
    }

    public static final class ArmorStatus extends HudModule {
        private final ModeSetting durability = add(new ModeSetting("Durability", "Show durability as", "Percent", "Percent", "Value", "Off"));

        public ArmorStatus() { super("Armor Status", "Your armour and held item durability", true, 0.88f, 0.80f); }

        @Override
        protected void render(DrawContext c, boolean editor) {
            if (mc.player == null) return;
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};
            int y = 0;
            int maxW = 18;
            List<ItemStack> stacks = new ArrayList<>();
            for (EquipmentSlot s : slots) {
                ItemStack st = mc.player.getEquippedStack(s);
                if (!st.isEmpty()) stacks.add(st);
            }
            if (stacks.isEmpty() && editor) stacks.add(new ItemStack(Items.NETHERITE_CHESTPLATE));
            for (ItemStack st : stacks) maxW = Math.max(maxW, 20 + Gfx.width(dur(st)));
            panel(c, maxW + 4, Math.max(18, stacks.size() * 17 + 2));
            for (ItemStack st : stacks) {
                c.drawItem(st, 2, y + 1);
                String d = dur(st);
                if (!d.isEmpty()) {
                    float frac = st.isDamageable() ? 1f - (float) st.getDamage() / st.getMaxDamage() : 1f;
                    int col = frac > 0.5f ? Theme.TEXT : frac > 0.25f ? 0xFFFFD166 : 0xFFFF5A5A;
                    Gfx.text(c, d, 21, y + 5, col);
                }
                y += 17;
            }
        }

        private String dur(ItemStack st) {
            if (durability.is("Off") || !st.isDamageable() || st.getMaxDamage() <= 0) return st.getCount() > 1 ? String.valueOf(st.getCount()) : "";
            int left = st.getMaxDamage() - st.getDamage();
            return durability.is("Percent") ? Math.round(100f * left / st.getMaxDamage()) + "%" : String.valueOf(left);
        }
    }

    public static final class PotionEffects extends HudModule {
        public PotionEffects() { super("Potion Effects", "Your active effects and time left", true, 0.88f, 0.40f); }

        @Override
        protected void render(DrawContext c, boolean editor) {
            if (mc.player == null) return;
            List<String> lines = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (StatusEffectInstance e : mc.player.getStatusEffects()) {
                String name = e.getEffectType().value().getName().getString();
                if (e.getAmplifier() > 0) name += " " + (e.getAmplifier() + 1);
                String time;
                if (e.isInfinite()) time = "inf";
                else {
                    int s = e.getDuration() / 20;
                    time = String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
                }
                lines.add(name + "  " + time);
                colors.add(e.getDuration() < 200 && !e.isInfinite() ? 0xFFFFD166 : Theme.TEXT);
            }
            if (lines.isEmpty()) {
                if (!editor) {
                    this.width = 60;
                    this.height = 12;
                    return;
                }
                lines.add("Strength 2  1:30");
                colors.add(Theme.TEXT);
            }
            int w = 0;
            for (String l : lines) w = Math.max(w, Gfx.width(l));
            panel(c, w + 8, lines.size() * 11 + 3);
            for (int i = 0; i < lines.size(); i++) Gfx.text(c, lines.get(i), 4, 3 + i * 11, colors.get(i));
        }
    }

    /** Your crystal PvP inventory at a glance: totems, crystals, obsidian, anchors, pearls... */
    public static final class PvpInventory extends HudModule {
        private static final Item[] ITEMS = {Items.TOTEM_OF_UNDYING, Items.END_CRYSTAL, Items.OBSIDIAN, Items.RESPAWN_ANCHOR,
                Items.GLOWSTONE, Items.ENDER_PEARL, Items.EXPERIENCE_BOTTLE, Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE};
        private final ModeSetting layout = add(new ModeSetting("Layout", "Row or column", "Row", "Row", "Column"));
        private final BoolSetting hideZero = add(new BoolSetting("Hide empty", "Hide items you have none of", true));

        public PvpInventory() { super("PvP Inventory", "Counts of totems, crystals, obsidian, anchors, pearls and more", Category.COMBAT, true, 0.36f, 0.86f); }

        @Override
        protected void render(DrawContext c, boolean editor) {
            if (mc.player == null) return;
            var inv = mc.player.getInventory();
            List<Item> shown = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            for (Item it : ITEMS) {
                int n = 0;
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack st = inv.getStack(i);
                    if (st.isOf(it)) n += st.getCount();
                }
                if (n == 0 && hideZero.on() && !editor) continue;
                shown.add(it);
                counts.add(n);
            }
            if (shown.isEmpty()) {
                this.width = 20;
                this.height = 20;
                return;
            }
            boolean row = layout.is("Row");
            int cell = 20;
            int w = row ? shown.size() * cell + 2 : cell + 26;
            int h = row ? cell + 2 : shown.size() * cell + 2;
            panel(c, w, h);
            for (int i = 0; i < shown.size(); i++) {
                int x = row ? 2 + i * cell : 2;
                int y = row ? 2 : 2 + i * cell;
                ItemStack st = new ItemStack(shown.get(i));
                c.drawItem(st, x, y);
                String n = String.valueOf(counts.get(i));
                int col = counts.get(i) == 0 ? 0xFFFF5A5A : shown.get(i) == Items.TOTEM_OF_UNDYING && counts.get(i) <= 2 ? 0xFFFFD166 : Theme.TEXT;
                if (row) {
                    Gfx.textShadow(c, n, x + 17 - Gfx.width(n), y + 9, col);
                } else {
                    Gfx.textBold(c, n, x + 20, y + 5, col);
                }
            }
        }
    }

    public static final class ComboCounter extends HudModule {
        private int combo;
        private long lastHit;

        public ComboCounter() {
            super("Combo Counter", "Consecutive hits you land without being hit", Category.COMBAT, false, 0.46f, 0.62f);
            GameEvents.register(new GameEvents.Listener() {
                @Override
                public void onEntityDamage(Entity target, String type, int causeId, int directId) {
                    if (mc.player == null) return;
                    if (target == mc.player) combo = 0;
                    else if (causeId == mc.player.getId() && target instanceof LivingEntity) {
                        combo++;
                        lastHit = System.currentTimeMillis();
                    }
                }
            });
        }

        @Override
        public boolean shouldRender() {
            if (System.currentTimeMillis() - lastHit > 2500) combo = 0;
            return combo > 0;
        }

        @Override
        protected void render(DrawContext c, boolean editor) { line(c, "Combo", String.valueOf(editor && combo == 0 ? 3 : combo)); }
    }

    public static final class ReachDisplay extends HudModule {
        private double reach;
        private long at;

        public ReachDisplay() {
            super("Reach Display", "Distance of your last hit", Category.COMBAT, false, 0.46f, 0.66f);
            GameEvents.register(new GameEvents.Listener() {
                @Override
                public void onAttack(Entity target) {
                    if (mc.player == null || !(target instanceof LivingEntity)) return;
                    Vec3d eye = mc.player.getEyePos();
                    var box = target.getBoundingBox();
                    double x = Math.max(box.minX, Math.min(eye.x, box.maxX));
                    double y = Math.max(box.minY, Math.min(eye.y, box.maxY));
                    double z = Math.max(box.minZ, Math.min(eye.z, box.maxZ));
                    reach = eye.distanceTo(new Vec3d(x, y, z));
                    at = System.currentTimeMillis();
                }
            });
        }

        @Override
        public boolean shouldRender() { return System.currentTimeMillis() - at < 3000; }

        @Override
        protected void render(DrawContext c, boolean editor) {
            line(c, "Reach", String.format(Locale.ROOT, "%.2f", editor && at == 0 ? 2.95 : reach));
        }
    }

    public static final class AttackCooldown extends HudModule {
        public AttackCooldown() { super("Attack Cooldown", "Your own attack charge as a bar under the crosshair", Category.COMBAT, false, 0.46f, 0.53f); }

        @Override
        protected void render(DrawContext c, boolean editor) {
            if (mc.player == null) return;
            float p = mc.player.getAttackCooldownProgress(0f);
            this.width = 40;
            this.height = 4;
            if (p >= 1f && !editor) return;
            Gfx.round(c, 0, 0, 40, 4, 2, 0xA0101114);
            Gfx.round(c, 0, 0, Math.max(2, Math.round(40 * p)), 4, 2, p >= 1f ? Theme.accent() : 0xFFECEEF2);
        }
    }

    public static void registerAll() {
        ModuleManager.register(new Watermark());
        ModuleManager.register(new ModuleList());
        ModuleManager.register(new Fps());
        ModuleManager.register(new Ping());
        ModuleManager.register(new CpsDisplay());
        ModuleManager.register(new Coordinates());
        ModuleManager.register(new Clock());
        ModuleManager.register(new Speed());
        ModuleManager.register(new Memory());
        ModuleManager.register(new ServerAddress());
        ModuleManager.register(new Keystrokes());
        ModuleManager.register(new ArmorStatus());
        ModuleManager.register(new PotionEffects());
        ModuleManager.register(new PvpInventory());
        ModuleManager.register(new ComboCounter());
        ModuleManager.register(new ReachDisplay());
        ModuleManager.register(new AttackCooldown());
        XsozClient.LOG.debug("HUD elements registered");
    }
}
