package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.DrillDef;
import dev.xsoz.client.training.Kit;
import dev.xsoz.client.training.bot.BotWorld;
import dev.xsoz.client.training.bot.Fighter;
import dev.xsoz.client.training.bot.PvpBot;
import dev.xsoz.client.training.session.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;

/**
 * A real fight with bots: everything explodes for real, the player takes real damage and pops real
 * totems, bots are {@link PvpBot}s. Runs on a sky arena with glass walls down to bedrock. This base
 * owns the fighters and teams, routes damage to the bots, credits kills and keeps the scoreboard;
 * Sparring (rounds) and Free Roam (endless, respawns) decide what a death means.
 */
public abstract class BotFightDrill extends Drill implements BotWorld {
    protected final List<Fighter> fighters = new CopyOnWriteArrayList<>();
    protected Fighter you;
    protected final List<PvpBot> bots = new ArrayList<>();
    private final java.util.Map<PvpBot, Integer> respawnAt = new java.util.HashMap<>();
    private int lastPlayerTotems;
    /** Totem recovery: ticks from your offhand going empty to a totem back in it. */
    private int offhandEmptyAt = -1;
    private final List<Integer> retotemMs = new ArrayList<>();
    public volatile List<String> board = List.of();

    protected BotFightDrill(DrillDef def, Mode mode, double p, boolean hints) {
        super(def, mode, p, hints);
    }

    // ------------------------------------------------------------------ BotWorld

    @Override
    public Session session() { return s; }

    @Override
    public int ticks() { return age(); }

    @Override
    public Random rng() { return rng; }

    @Override
    public List<Fighter> fighters() { return fighters; }

    @Override
    public Fighter fighterOf(Entity e) {
        if (e == null) return null;
        for (Fighter f : fighters) {
            if (f.isPlayer()) {
                if (e instanceof ServerPlayerEntity p && f.player() != null && p.getUuid().equals(f.player().getUuid())) return f;
            } else if (f.bot.body() == e) {
                return f;
            }
        }
        return null;
    }

    @Override
    public BlockPos center() { return arena != null ? arena.center : centerForTests; }

    @Override
    public int radius() { return arena != null ? arena.radius : arenaRadius(); }

    /** GameTests run without a sky platform: the arena is wherever the player stood. */
    protected BlockPos centerForTests;

    // ------------------------------------------------------------------ drill plumbing

    @Override
    public boolean realExplosions() { return true; }

    @Override
    protected boolean autoRefillTotems() { return false; }

    @Override
    protected boolean keepAlive() { return false; }

    @Override
    protected boolean deepWalls() { return true; }

    @Override
    protected int arenaHeight() { return 30; }

    /** Builds the floor of a GameTest arena (the real game uses the sky platform). */
    protected final void testFloor(BlockPos c, int r) {
        centerForTests = c;
        s.snapshotBox(c.add(-r - 1, -4, -r - 1), c.add(r + 1, 12, r + 1));
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                set(c.add(x, -4, z), net.minecraft.block.Blocks.BEDROCK.getDefaultState());
                for (int y = -3; y <= -1; y++) set(c.add(x, y, z), net.minecraft.block.Blocks.STONE.getDefaultState());
                for (int y = 0; y <= 12; y++) set(c.add(x, y, z), net.minecraft.block.Blocks.AIR.getDefaultState());
            }
        }
    }

    /** Explosions barely hurt players on Easy and not at all on Peaceful: fight on Normal, put it back after. */
    protected final void normalDifficulty() {
        Difficulty was = s.world.getDifficulty();
        if (was.getId() < Difficulty.NORMAL.getId()) {
            s.server.setDifficulty(Difficulty.NORMAL, true);
            s.onRestore(() -> s.server.setDifficulty(was, true));
            feedback("World set to Normal for the fight (was " + was.asString() + ")", 0xFFFFD166);
        }
    }

    protected final Fighter addPlayer() {
        ServerPlayerEntity pl = player();
        you = Fighter.player("You", pl.getUuid(), s.server, s.isTest() ? pl : null);
        fighters.add(you);
        lastPlayerTotems = Kit.totems(pl);
        return you;
    }

    protected final PvpBot addBot(PvpBot b) {
        Fighter f = Fighter.bot(b);
        b.bind(f);
        fighters.add(f);
        bots.add(b);
        return b;
    }

    protected final Fighter fighter(PvpBot b) {
        for (Fighter f : fighters) if (f.bot == b) return f;
        return null;
    }

    /** A free spot far from everyone on other teams. */
    protected final Vec3d spawnPointFor(int team) {
        List<Vec3d> avoid = new ArrayList<>();
        for (Fighter f : fighters) {
            if (f.team == team) continue;
            LivingEntity e = f.entity();
            if (e != null) avoid.add(e.getEntityPos());
        }
        BlockPos c = center();
        int r = radius() - 3;
        Vec3d best = Vec3d.ofBottomCenter(c);
        double bestD = -1;
        for (int i = 0; i < 40; i++) {
            BlockPos p = floorSpot(c.getX() + rng.nextInt(2 * r + 1) - r, c.getZ() + rng.nextInt(2 * r + 1) - r);
            if (p == null) continue;
            Vec3d at = Vec3d.ofBottomCenter(p);
            double d = 999;
            for (Vec3d a : avoid) d = Math.min(d, a.distanceTo(at));
            if (d > bestD) {
                bestD = d;
                best = at;
            }
            if (d > 22) break;
        }
        return best;
    }

    /** The real ground at x/z - never a tree top, a cactus or the top of a wall. */
    private BlockPos floorSpot(int x, int z) {
        BlockPos c = center();
        for (int y = c.getY() - 6; y <= c.getY() + 10; y++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!dev.xsoz.client.training.bot.Pathfinder.standable(s.world, p)) continue;
            var below = s.world.getBlockState(p.down());
            if (below.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) || below.isIn(net.minecraft.registry.tag.BlockTags.LOGS)
                    || below.isOf(net.minecraft.block.Blocks.CACTUS)) return null;
            return p;
        }
        return null;
    }

    protected final void respawnBotLater(PvpBot b, int delay) { respawnAt.put(b, age() + delay); }

    protected final void respawnPlayer(ServerPlayerEntity pl) {
        pl.setHealth(pl.getMaxHealth());
        pl.clearStatusEffects();
        pl.extinguish();
        pl.getHungerManager().setFoodLevel(20);
        pl.getHungerManager().setSaturationLevel(20f);
        giveKit(pl, GameMode.SURVIVAL);
        Vec3d at = spawnPointFor(0);
        Scene.teleport(pl, at, Scene.yawTowards(at, Vec3d.ofBottomCenter(center())), 0f);
        pl.fallDistance = 0;
        // three seconds of spawn protection
        pl.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 60, 4, false, false));
        lastPlayerTotems = Kit.totems(pl);
    }

    // ------------------------------------------------------------------ the fight tick

    /** Call every tick from tick(): bots think and move, deaths are noticed, the board is updated. */
    protected final void tickFight() {
        ServerPlayerEntity pl = player();
        if (pl != null && you != null) {
            int t = Kit.totems(pl);
            if (t < lastPlayerTotems) {
                you.pops += lastPlayerTotems - t;
                feed("You popped a totem", 0xFFFF6D7E);
            }
            lastPlayerTotems = t;
            boolean totem = pl.getOffHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING);
            if (!totem && offhandEmptyAt < 0) offhandEmptyAt = age();
            if (totem && offhandEmptyAt >= 0) {
                retotemMs.add((age() - offhandEmptyAt) * 50);
                offhandEmptyAt = -1;
            }
        }
        for (PvpBot b : bots) {
            boolean was = b.alive();
            Fighter f = fighter(b);
            int pops = f == null ? 0 : f.pops;
            b.tick();
            if (f != null && f.pops > pops) {
                sound(net.minecraft.sound.SoundEvents.ITEM_TOTEM_USE, f.team == 0 ? 0.8f : 1.2f);
                feed(f.name + " popped a totem", 0xFFFFD166);
            }
            if (was && !b.alive()) onBotDied(b, f);
        }
        for (var e : new ArrayList<>(respawnAt.entrySet())) {
            if (age() >= e.getValue()) {
                respawnAt.remove(e.getKey());
                Vec3d at = spawnPointFor(e.getKey().team);
                e.getKey().spawn(this, at, Scene.yawTowards(at, Vec3d.ofBottomCenter(center())));
            }
        }
        if (age() % 20 == 0) {
            Box box = new Box(center()).expand(radius() + 2, 40, radius() + 2);
            for (ItemEntity i : s.world.getEntitiesByClass(ItemEntity.class, box, x -> true)) i.discard();
        }
        if (age() % 5 == 0) updateBoard();
    }

    /** Who killed whom, newest last: text, time (ms), colour. */
    public final List<Object[]> killFeed = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void feed(String text, int color) {
        killFeed.add(new Object[] {text, System.currentTimeMillis(), color});
        while (killFeed.size() > 5) killFeed.remove(0);
    }

    @Override
    public void renderOverlay(net.minecraft.client.gui.DrawContext c, int sw, int sh) {
        long now = System.currentTimeMillis();
        int y = 46;
        for (Object[] e : killFeed) {
            long age = now - (Long) e[1];
            if (age > 7000) continue;
            float a = age < 6000 ? 1f : 1f - (age - 6000) / 1000f;
            String text = (String) e[0];
            int w = dev.xsoz.client.render.Gfx.width(text) + 12;
            dev.xsoz.client.render.Gfx.round(c, sw - w - 8, y, w, 14, 4, dev.xsoz.client.render.Theme.alpha(0xC0101114, a));
            dev.xsoz.client.render.Gfx.text(c, text, sw - w - 2, y + 3, dev.xsoz.client.render.Theme.alpha((Integer) e[2], a));
            y += 16;
        }
        renderBoard(c, sw, Math.max(y + 4, 46));
    }

    /** Kill credit: whoever hit the victim last in the last 10 s. */
    protected final Fighter creditKill(Fighter victim) {
        victim.deaths++;
        Fighter k = victim.lastHitBy != null && age() - victim.lastHitAt < 200 ? victim.lastHitBy : null;
        if (k != null && k != victim) k.kills++;
        victim.lastHitBy = null;
        int col = victim == you ? 0xFFFF6D7E : k == you ? 0xFF4CD765 : victim.team == 0 ? 0xFFFFB020 : 0xFFECEEF2;
        feed(k != null ? k.name + " killed " + victim.name : victim.name + " died", col);
        // you always hear a death: a kill of yours, a teammate down, an enemy down, or you
        if (victim == you) sound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_HURT_ON_FIRE, 0.5f);
        else if (k == you) sound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP, 1.4f);
        else if (victim.team == 0) sound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_DIDGERIDOO.value(), 0.7f);
        else sound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.2f);
        return k;
    }

    protected abstract void onBotDied(PvpBot b, Fighter f);

    /** "Retotem avg 420 ms (5)" - the gap a double pop gets through. */
    protected final String retotemLine() {
        if (retotemMs.isEmpty()) return "";
        double avg = retotemMs.stream().mapToInt(Integer::intValue).average().orElse(0);
        return String.format(Locale.ROOT, "retotem avg %.0f ms (%d)", avg, retotemMs.size());
    }

    // ------------------------------------------------------------------ hooks

    @Override
    public boolean onTrackedDamage(Entity e, DamageSource source, float amount) {
        for (PvpBot b : bots) if (b.body() == e) return b.onDamage(source, amount);
        return true;
    }

    /** Damage to the player: remember who did it (kill credit). */
    @Override
    public void onPlayerDamaged(ServerPlayerEntity p, DamageSource source) {
        if (you == null) return;
        Fighter by = fighterOf(source.getAttacker());
        if (by != null && by != you) {
            you.lastHitBy = by;
            you.lastHitAt = age();
        }
    }

    @Override
    public void onCrystalSpawn(EndCrystalEntity crystal) { }

    @Override
    public boolean onCrystalBreak(EndCrystalEntity crystal, Entity attacker) { return false; }

    // ------------------------------------------------------------------ HUD

    private void updateBoard() {
        List<String> lines = new ArrayList<>();
        for (Fighter f : fighters) {
            String hp = f.alive() ? String.format(Locale.ROOT, "%.0f hp", f.health()) : "dead";
            String side = f.isPlayer() ? "" : f.team == 0 ? " (ally)" : "";
            lines.add(String.format(Locale.ROOT, "%s%s  K %d  D %d  pops %d  %s", f.name, side, f.kills, f.deaths, f.pops, hp));
        }
        board = lines;
    }

    @Override
    public void renderExtra(DrawContext c, int x, int y) { }

    /** The scoreboard, top right under the kill feed (the left side belongs to keystrokes and the drill card). */
    private void renderBoard(DrawContext c, int sw, int y) {
        List<String> b = board;
        int n = Math.min(9, b.size());
        if (n == 0) return;
        int w = 0;
        for (int i = 0; i < n; i++) w = Math.max(w, Gfx.width(b.get(i)));
        w += 12;
        Gfx.round(c, sw - w - 8, y, w, n * 10 + 6, 4, 0xA0101114);
        for (int i = 0; i < n; i++) Gfx.text(c, b.get(i), sw - w - 2, y + 4 + i * 10, i == 0 ? Theme.accent() : Theme.TEXT_2);
    }

    // ------------------------------------------------------------------ GameTest access

    public List<PvpBot> botsForTests() { return bots; }

    public Fighter youForTests() { return you; }
}
