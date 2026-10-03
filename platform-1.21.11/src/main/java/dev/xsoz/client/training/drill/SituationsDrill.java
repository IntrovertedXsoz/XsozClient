package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.DrillDef;
import java.util.Locale;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * Layout Recall level 3 - Situations. Real fight moments on freshly generated terrain, each one
 * asking for the right item AND the right move, fast:
 *
 * <ul>
 *   <li><b>Ledge</b>: you're on a ledge, they're below and just put obsidian at your feet. Don't
 *   walk (their crystal is coming): pearl down into the open below them - into the hole even
 *   lower than them for the bonus.</li>
 *   <li><b>Mend</b>: your armour is breaking and they pearled away to mend. Get out and mend too:
 *   distance, then XP bottles until your armour is back over 55%.</li>
 *   <li><b>Pop</b>: you pop. Totem back in your offhand fast, then a gapple.</li>
 *   <li><b>Roof</b>: they're under a roof - crystals can't reach. Anchor them.</li>
 *   <li><b>Trapped</b>: you're boxed in obsidian. Pearl out or mine out before their crystal comes.</li>
 * </ul>
 * The time you get follows the speed setting (Natural speeds it up and slows it down).
 */
public final class SituationsDrill extends Drill {
    private enum Kind {
        LEDGE("Ledge escape"), MEND("Mend"), POP("Pop"), ROOF("Under a roof"), TRAP("Trapped");

        final String title;

        Kind(String t) { title = t; }
    }

    private BlockPos base;
    private Direction facing;
    private Kind kind;
    private int startAt;
    private int nextAt;
    private double window;
    private Vec3d startPos;
    private boolean pearled;
    private boolean retotemed;
    private int retotemTicks = -1;
    private int gapples;
    private MannequinEntity enemy;
    private BlockPos pitAt;
    private BlockPos trapMin;
    private BlockPos trapMax;
    public volatile String shownTitle = "";
    public volatile String shownHint = "";

    public SituationsDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.LAYOUT_RECALL, mode, p, hints);
        level(3);
    }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        base = pl.getBlockPos();
        facing = pl.getHorizontalFacing();
        s.snapshotBox(base.add(-14, -4, -14), base.add(14, 9, 14));
        giveKit(pl, GameMode.SURVIVAL);
        nextAt = 30;
        status = "Read the moment, react with the right item and move.";
    }

    private double seconds(double factor, double extra) { return speed() / 1000.0 * factor + extra; }

    // ------------------------------------------------------------------ scenes

    private void clearArea() {
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                set(base.add(x, -3, z), Blocks.STONE.getDefaultState());
                set(base.add(x, -2, z), Blocks.STONE.getDefaultState());
                set(base.add(x, -1, z), Blocks.STONE.getDefaultState());
                for (int y = 0; y <= 8; y++) set(base.add(x, y, z), Blocks.AIR.getDefaultState());
            }
        }
        for (EndCrystalEntity c : s.world.getEntitiesByClass(EndCrystalEntity.class, new Box(base).expand(14), x -> true)) c.discard();
        if (enemy != null && !enemy.isRemoved()) enemy.discard();
        enemy = null;
        // a little random terrain at the edges so no two scenes look the same
        for (int i = 0; i < 6; i++) {
            int x = randBetween(-11, 11);
            int z = randBetween(-11, 11);
            if (Math.abs(x) < 7 && Math.abs(z) < 7) continue;
            int h = randBetween(1, 3);
            for (int y = 0; y < h; y++) set(base.add(x, y, z), rng.nextBoolean() ? Blocks.STONE.getDefaultState() : Blocks.COBBLESTONE.getDefaultState());
        }
    }

    private void spawnEnemy(BlockPos feet, net.minecraft.item.Item hand) {
        Vec3d at = Vec3d.ofBottomCenter(feet);
        ServerPlayerEntity pl = player();
        enemy = Scene.spawnDummy(s, at, Scene.yawTowards(at, new Vec3d(pl.getX(), pl.getY(), pl.getZ())));
        if (enemy == null) return;
        enemy.setCustomName(net.minecraft.text.Text.literal("Enemy"));
        enemy.equipStack(EquipmentSlot.MAINHAND, new ItemStack(hand));
        enemy.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
        enemy.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
        enemy.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
        enemy.equipStack(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
        enemy.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
    }

    private void newScene(ServerPlayerEntity pl) {
        clearArea();
        refillKit();
        pl.setHealth(pl.getMaxHealth());
        pl.clearStatusEffects();
        pl.extinguish();
        pl.changeGameMode(GameMode.SURVIVAL);
        kind = Kind.values()[rng.nextInt(Kind.values().length)];
        pearled = false;
        retotemed = false;
        retotemTicks = -1;
        pitAt = null;
        Direction side = facing.rotateYClockwise();
        switch (kind) {
            case LEDGE -> {
                int h = randBetween(3, 5);
                for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) for (int y = 0; y < h; y++) set(base.add(a, y, b), Blocks.STONE.getDefaultState());
                BlockPos top = base.up(h);
                Scene.teleport(pl, Vec3d.ofBottomCenter(top), facing.getPositiveHorizontalDegrees(), 35f);
                spawnEnemy(base.offset(facing, randBetween(4, 6)), Items.END_CRYSTAL);
                // they put obsidian at your feet: a crystal is coming
                set(top.offset(facing), Blocks.OBSIDIAN.getDefaultState());
                sound(SoundEvents.BLOCK_STONE_PLACE, 1f);
                // a hole lower than them: the bonus spot
                pitAt = base.offset(facing, randBetween(6, 9)).offset(side, randBetween(-3, 3));
                set(pitAt.down(), Blocks.AIR.getDefaultState());
                set(pitAt.down(2), Blocks.AIR.getDefaultState());
                window = seconds(1.6, 1.5);
                shownHint = "Pearl down into the open - into the hole for the bonus. Don't walk.";
            }
            case MEND -> {
                Scene.teleport(pl, Vec3d.ofBottomCenter(base), facing.getPositiveHorizontalDegrees(), 10f);
                for (EquipmentSlot sl : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    ItemStack st = pl.getEquippedStack(sl);
                    if (st.isDamageable()) st.setDamage((int) (st.getMaxDamage() * 0.62));
                }
                pl.currentScreenHandler.sendContentUpdates();
                sound(SoundEvents.ENTITY_ITEM_BREAK.value(), 1f);
                // they pearl away and start mending
                BlockPos far = base.offset(facing, 11).offset(side, randBetween(-3, 3));
                spawnEnemy(far, Items.EXPERIENCE_BOTTLE);
                Scene.particles(s.world, ParticleTypes.PORTAL, Vec3d.ofCenter(base.offset(facing, 3)), 30, 0.5);
                sound(SoundEvents.ENTITY_ENDER_PEARL_THROW, 1f);
                window = seconds(2.2, 5);
                shownHint = "Get out, then mend: XP bottles until your armour is back over 55%.";
            }
            case POP -> {
                Scene.teleport(pl, Vec3d.ofBottomCenter(base), facing.getPositiveHorizontalDegrees(), 10f);
                spawnEnemy(base.offset(facing, 4), Items.END_CRYSTAL);
                pl.timeUntilRegen = 0;
                pl.setHealth(1f);
                pl.damage(pl.getEntityWorld(), pl.getEntityWorld().getDamageSources().generic(), 200f);
                gapples = countGapples(pl);
                window = seconds(1.4, 3.5);
                shownHint = "Totem back in your offhand, fast - then a gapple.";
            }
            case ROOF -> {
                Scene.teleport(pl, Vec3d.ofBottomCenter(base), facing.getPositiveHorizontalDegrees(), 15f);
                BlockPos at = base.offset(facing, randBetween(3, 4)).offset(side, randBetween(-1, 1));
                spawnEnemy(at, Items.RESPAWN_ANCHOR);
                for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) set(at.add(a, 2, b), Blocks.OBSIDIAN.getDefaultState());
                window = seconds(1.5, 2.5);
                shownHint = "Crystals can't reach under a roof - anchor them.";
            }
            case TRAP -> {
                Scene.teleport(pl, Vec3d.ofBottomCenter(base), facing.getPositiveHorizontalDegrees(), 10f);
                for (Direction d : Direction.Type.HORIZONTAL) {
                    set(base.offset(d), Blocks.OBSIDIAN.getDefaultState());
                    set(base.offset(d).up(), Blocks.OBSIDIAN.getDefaultState());
                }
                set(base.up(2), Blocks.OBSIDIAN.getDefaultState());
                trapMin = base.add(-1, 0, -1);
                trapMax = base.add(1, 2, 1);
                spawnEnemy(base.offset(facing, 4), Items.END_CRYSTAL);
                sound(SoundEvents.BLOCK_STONE_PLACE, 0.8f);
                window = seconds(1.5, 2.0);
                shownHint = "Boxed in - pearl out (or mine out) before their crystal.";
            }
        }
        startAt = ticks;
        startPos = new Vec3d(pl.getX(), pl.getY(), pl.getZ());
        shownTitle = kind.title;
        cue();
    }

    private static int countGapples(ServerPlayerEntity pl) {
        int n = 0;
        var inv = pl.getInventory();
        for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isOf(Items.GOLDEN_APPLE)) n += inv.getStack(i).getCount();
        return n;
    }

    // ------------------------------------------------------------------ judging

    @Override
    protected void tick() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        if (kind == null) {
            if (ticks >= nextAt) newScene(pl);
            return;
        }
        double t = (ticks - startAt) / 20.0;
        Vec3d me = new Vec3d(pl.getX(), pl.getY(), pl.getZ());
        if (enemy != null) Scene.pin(enemy, enemy.getEntityPos(), Scene.yawTowards(enemy.getEntityPos(), me));
        switch (kind) {
            case LEDGE -> {
                double walked = Math.sqrt(Math.pow(me.x - startPos.x, 2) + Math.pow(me.z - startPos.z, 2));
                if (!pearled && walked > 1.2) {
                    boom(me);
                    done(false, "You walked - their crystal caught you. Pearl, don't walk");
                } else if (pearled && pl.isOnGround() && me.y <= base.getY() + 0.1) {
                    boolean hole = pitAt != null && me.y < base.getY() - 0.5;
                    done(true, String.format(Locale.ROOT, "%.2f s - %s", t, hole ? "into the hole, perfect" : "out and down, good (the hole was the bonus)"));
                }
            }
            case MEND -> {
                double away = me.distanceTo(startPos);
                if (away >= 6 && armourAtLeast(pl, 0.55)) done(true, String.format(Locale.ROOT, "%.1f s - out and mended", t));
            }
            case POP -> {
                if (!retotemed && pl.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                    retotemed = true;
                    retotemTicks = ticks - startAt;
                }
                int g = countGapples(pl);
                if (retotemed && g < gapples) {
                    done(retotemTicks <= 14, String.format(Locale.ROOT, "Retotem %d ms, gapple at %.1f s%s", retotemTicks * 50, t,
                            retotemTicks > 14 ? " - the retotem was too slow" : ""));
                } else if (!retotemed && g < gapples) {
                    done(false, "Gapple before totem - totem first, always");
                }
            }
            case TRAP -> {
                if (me.x < trapMin.getX() || me.x > trapMax.getX() + 1 || me.z < trapMin.getZ() || me.z > trapMax.getZ() + 1) {
                    done(true, String.format(Locale.ROOT, "%.2f s - out of the trap", t));
                }
            }
            default -> { }
        }
        if (kind != null && t > window) {
            if (kind == Kind.LEDGE || kind == Kind.TRAP) boom(me);
            done(false, "Too slow - " + switch (kind) {
                case LEDGE -> "the crystal got you on the ledge";
                case MEND -> "your armour was still breaking";
                case POP -> "you were still open after the pop";
                case ROOF -> "they got away (anchor them under the roof)";
                case TRAP -> "the crystal got you in the box";
            });
        }
    }

    private boolean armourAtLeast(ServerPlayerEntity pl, double frac) {
        for (EquipmentSlot sl : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = pl.getEquippedStack(sl);
            if (st.isDamageable() && st.getMaxDamage() - st.getDamage() < st.getMaxDamage() * frac) return false;
        }
        return true;
    }

    /** Their crystal: shown and heard, never hurting you. */
    private void boom(Vec3d at) {
        s.world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
        s.world.playSound(null, BlockPos.ofFloored(at), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1f, 1f);
    }

    private void done(boolean hit, String note) {
        rep(hit, (ticks - startAt) / 20.0, kind.title + ": " + note);
        kind = null;
        shownTitle = "";
        nextAt = ticks + 40;
    }

    @Override
    public void onEntityLoad(Entity e) {
        if (e instanceof EnderPearlEntity p && p.getOwner() == player()) pearled = true;
    }

    @Override
    public boolean onAnchorExplode(BlockPos pos) {
        s.setBlock(pos, Blocks.AIR.getDefaultState());
        if (kind == Kind.ROOF && enemy != null && Vec3d.ofCenter(pos).distanceTo(enemy.getEntityPos().add(0, 1, 0)) <= 3.0) {
            done(true, String.format(Locale.ROOT, "%.2f s - anchored under the roof", (ticks - startAt) / 20.0));
        }
        return true;
    }

    @Override
    public void onCrystalSpawn(EndCrystalEntity crystal) {
        if (kind == Kind.ROOF) {
            crystal.discard();
            done(false, "A crystal can't reach under a roof - that called for an anchor");
        }
    }

    @Override
    public boolean onCrystalBreak(EndCrystalEntity crystal, Entity attacker) { return true; }

    @Override
    public void renderExtra(DrawContext c, int x, int y) {
        String t = shownTitle;
        if (t.isEmpty()) return;
        Gfx.textBold(c, t, x, y, Theme.accent());
        if (hints) Gfx.text(c, Gfx.fit(shownHint, 360), x, y + 11, Theme.TEXT_2);
    }
}
