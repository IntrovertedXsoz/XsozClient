package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.DrillDef;
import dev.xsoz.client.training.Kit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * Layout Recall: react with the right hotbar slot - the way you react in a fight, not by reading.
 *
 * <ul>
 *   <li>Level 1 - Icons: the item flashes as an icon. No words to read.</li>
 *   <li>Level 2 - Cues: something happens in the world and you answer with the item it calls for:
 *   a real totem pop, the armour-breaking sound, a hit with no golden hearts left, obsidian appearing
 *   next to the enemy, the enemy under a roof, an uncharged anchor, the enemy in your face, the enemy
 *   in the open, being boxed in.</li>
 * </ul>
 * Level 3 (full situations on real terrain) is {@link SituationsDrill}.
 */
public final class LayoutRecallDrill extends Drill {
    private Kit.Role cue;
    private int cueAt;
    private int nextAt;
    private int selectedAtCue;
    private final List<Integer> reactionMs = new ArrayList<>();
    private final List<BlockPos> sceneBlocks = new ArrayList<>();
    private MannequinEntity enemy;
    private BlockPos base;
    private Direction facing;
    public volatile Kit.Role shownCue;
    public volatile long cueShownAt;

    public LayoutRecallDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.LAYOUT_RECALL, mode, p, hints);
    }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        base = pl.getBlockPos();
        facing = pl.getHorizontalFacing();
        s.snapshotBox(base.add(-8, -2, -8), base.add(8, 5, 8));
        floor(base.add(-7, -1, -7), 15, 15, Blocks.STONE.getDefaultState());
        clearAbove(base.add(-7, -1, -7), 15, 15, 4);
        giveKit(pl, GameMode.ADVENTURE);
        Scene.teleport(pl, Vec3d.ofBottomCenter(base), facing.getPositiveHorizontalDegrees(), 10f);
        dev.xsoz.client.training.TrainingFlags.selectOnly = true;
        nextAt = randBetween(30, 60);
        status = level >= 2 ? "Watch and listen. Pick the item the moment calls for." : "An item will flash. Select it.";
    }

    /** Level 2 never lets you walk: the cue comes to you. */
    @Override
    protected boolean playerMayMove() { return false; }

    @Override
    protected void tick() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        int sel = pl.getInventory().getSelectedSlot();
        if (cue == null) {
            if (ticks >= nextAt) {
                List<Kit.Role> roles = new ArrayList<>();
                for (Kit.Role r : Kit.Role.values()) if (kit.slot(r) >= 0 && kit.slot(r) < 9 && kit.slot(r) != sel) roles.add(r);
                if (roles.isEmpty()) return;
                Kit.Role r = roles.get(rng.nextInt(roles.size()));
                cue = r;
                cueAt = ticks;
                selectedAtCue = sel;
                shownCue = r;
                cueShownAt = System.currentTimeMillis();
                if (level >= 2) stage(pl, r);
                else if (mode != Mode.LEVEL_UP) sound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 1.4f);
                status = "";
            }
            return;
        }
        int windowTicks = (int) Math.ceil(speed() / 50.0) + (level >= 2 ? 10 : 0);
        if (sel == kit.slot(cue)) {
            int ms = (ticks - cueAt) * 50;
            reactionMs.add(ms);
            end(true, ms + " ms - " + cue.label);
        } else if (sel != selectedAtCue) {
            end(false, "Wrong item - that called for " + cue.label);
        } else if (ticks - cueAt > windowTicks) {
            end(false, "Too slow - that called for " + cue.label);
        }
    }

    // ------------------------------------------------------------------ level 2: the cues

    private BlockPos ahead(int n) { return base.offset(facing, n); }

    private void put(BlockPos p, net.minecraft.block.BlockState st) {
        set(p, st);
        sceneBlocks.add(p.toImmutable());
    }

    private void enemyAt(BlockPos feet) {
        Vec3d at = Vec3d.ofBottomCenter(feet);
        enemy = Scene.spawnDummy(s, at, Scene.yawTowards(at, Vec3d.ofBottomCenter(base)));
        if (enemy != null) {
            enemy.setCustomName(net.minecraft.text.Text.literal("Enemy"));
            enemy.equipStack(EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.item.Items.END_CRYSTAL));
            enemy.equipStack(EquipmentSlot.CHEST, new ItemStack(net.minecraft.item.Items.NETHERITE_CHESTPLATE));
            enemy.equipStack(EquipmentSlot.HEAD, new ItemStack(net.minecraft.item.Items.NETHERITE_HELMET));
        }
    }

    /** What happens in the world for each item - the cue you have to read. */
    private void stage(ServerPlayerEntity pl, Kit.Role r) {
        switch (r) {
            case TOTEM -> {
                // a real pop
                pl.timeUntilRegen = 0;
                pl.setHealth(1f);
                pl.damage(pl.getEntityWorld(), pl.getEntityWorld().getDamageSources().generic(), 200f);
            }
            case XP -> {
                // the armour is going: worn pieces and the break sound
                for (EquipmentSlot sl : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    ItemStack st = pl.getEquippedStack(sl);
                    if (st.isDamageable()) st.setDamage((int) (st.getMaxDamage() * 0.9));
                }
                pl.currentScreenHandler.sendContentUpdates();
                sound(SoundEvents.ENTITY_ITEM_BREAK.value(), 1f);
            }
            case GAPPLE -> {
                pl.setAbsorptionAmount(0);
                pl.timeUntilRegen = 0;
                pl.damage(pl.getEntityWorld(), pl.getEntityWorld().getDamageSources().generic(), 5f);
            }
            case CRYSTAL -> {
                enemyAt(ahead(3));
                put(ahead(3).offset(facing.rotateYClockwise()), Blocks.OBSIDIAN.getDefaultState());
                sound(SoundEvents.BLOCK_STONE_PLACE, 1f);
            }
            case ANCHOR -> {
                enemyAt(ahead(3));
                for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) put(ahead(3).add(a, 2, b), Blocks.OBSIDIAN.getDefaultState());
            }
            case GLOWSTONE -> {
                enemyAt(ahead(4));
                put(ahead(2), Blocks.RESPAWN_ANCHOR.getDefaultState());
                sound(SoundEvents.BLOCK_STONE_PLACE, 1f);
            }
            case SWORD -> {
                enemyAt(ahead(2));
                if (enemy != null) enemy.equipStack(EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.item.Items.NETHERITE_SWORD));
                sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1f);
            }
            case OBSIDIAN -> enemyAt(ahead(3));
            case PEARL -> {
                // boxed in
                for (Direction d : Direction.Type.HORIZONTAL) {
                    put(base.offset(d), Blocks.OBSIDIAN.getDefaultState());
                    put(base.offset(d).up(), Blocks.OBSIDIAN.getDefaultState());
                }
                put(base.up(2), Blocks.OBSIDIAN.getDefaultState());
                sound(SoundEvents.BLOCK_STONE_PLACE, 0.8f);
            }
        }
    }

    private void clearScene() {
        for (int i = sceneBlocks.size() - 1; i >= 0; i--) set(sceneBlocks.get(i), Blocks.AIR.getDefaultState());
        sceneBlocks.clear();
        if (enemy != null && !enemy.isRemoved()) enemy.discard();
        enemy = null;
    }

    private void end(boolean hit, String note) {
        cue = null;
        shownCue = null;
        nextAt = ticks + randBetween(25, 60);
        clearScene();
        // keep the kit intact: a pearl thrown, a pop, worn armour - all put back
        refillKit();
        ServerPlayerEntity pl = player();
        if (pl != null) pl.setHealth(pl.getMaxHealth());
        rep(hit, hit ? (ticks - cueAt) * 50 : 0, note);
        status = "";
    }

    private int medianMs() {
        if (reactionMs.isEmpty()) return 0;
        List<Integer> v = new ArrayList<>(reactionMs);
        v.sort(Integer::compare);
        return v.get(v.size() / 2);
    }

    @Override
    protected boolean extraPassCheck() { return medianMs() <= (level >= 2 ? 900 : 600); }

    @Override
    public String summaryLine() {
        return reactionMs.isEmpty() ? "" : String.format(Locale.ROOT, "Median reaction %d ms", medianMs());
    }

    @Override
    public void renderExtra(DrawContext c, int x, int y) {
        Kit.Role r = shownCue;
        if (r == null) return;
        int sw = c.getScaledWindowWidth();
        int sh = c.getScaledWindowHeight();
        int by = sh / 2 - 60;
        if (level == 1) {
            // the icon, big, no words
            int bx = sw / 2 - 22;
            Gfx.round(c, bx, by, 44, 44, 6, 0xE6101114);
            var m = c.getMatrices();
            m.pushMatrix();
            m.translate(bx + 6, by + 6);
            m.scale(2f, 2f);
            c.drawItem(kit == null ? new ItemStack(r.item) : kit.stack(r), 0, 0);
            m.popMatrix();
        }
        if (hints) {
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            int slot = kit == null ? r.defaultSlot() : kit.slot(r);
            String key = slot >= 0 && slot < 9 && mc.options != null ? mc.options.hotbarKeys[slot].getBoundKeyLocalizedText().getString() : null;
            Gfx.textCentered(c, r.label + " - slot " + (slot + 1) + (key != null ? "  (" + key + ")" : ""), sw / 2, by + 50, Theme.accent());
        }
    }
}
