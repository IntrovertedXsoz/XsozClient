package dev.xsoz.client.training.drill;

import dev.xsoz.client.render.Gfx;
import dev.xsoz.client.render.Theme;
import dev.xsoz.client.training.DrillDef;
import dev.xsoz.client.training.Kit;
import dev.xsoz.client.training.bot.BotLevel;
import dev.xsoz.client.training.bot.Fighter;
import dev.xsoz.client.training.bot.FreeRoamConfig;
import dev.xsoz.client.training.bot.PvpBot;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Gapple Timing - the reset.
 *
 * <p>In a real fight you never eat a gapple with someone on top of you: eating takes 1.6 s, slows
 * you to a crawl and ties up your hands. You break away first - sprint, pearl over a wall - and eat
 * when there is distance. A bot chases you across a big arena hitting you with its sword; every
 * gapple you finish is one rep: clean when nobody hit you while you ate and the bot was at least
 * 8 blocks away when you finished. On level 3 the bot resets the same way (it pearls off and eats
 * when it's low), so you learn both sides: when to run, and when to chase someone who is running.</p>
 */
public final class SafeGappleDrill extends BotFightDrill {
    private PvpBot bot;
    private boolean eating;
    private boolean hitWhileEating;
    private int gapples;
    public volatile double shownDistance = -1;
    public volatile boolean shownEating;

    public SafeGappleDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.SAFE_GAPPLE, mode, p, hints);
    }

    public PvpBot botForTests() { return bot; }

    @Override
    protected int arenaRadius() { return 34; }

    @Override
    protected dev.xsoz.client.training.session.SkyArena.Theme groundTheme() {
        return level >= 2 ? dev.xsoz.client.training.session.SkyArena.Theme.STONE : dev.xsoz.client.training.session.SkyArena.Theme.GRASS;
    }

    private BotLevel botLevel() { return level >= 3 ? BotLevel.PRO : level == 2 ? BotLevel.GOOD : BotLevel.CASUAL; }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        if (s.isTest()) testFloor(pl.getBlockPos(), 20);
        else new dev.xsoz.client.training.bot.ArenaBuilder(s.world, arena, rng).decorate(
                level >= 2 ? FreeRoamConfig.Terrain.RUINS : FreeRoamConfig.Terrain.GRASS);
        normalDifficulty();
        addPlayer();
        giveKit(pl, net.minecraft.world.GameMode.SURVIVAL);
        pl.setHealth(12f);
        // the bot chases with a sword; level 3 also resets itself (pearls off, eats)
        bot = addBot(new PvpBot("Chaser", 1, botLevel(), FreeRoamConfig.Fight.SWORD.abilities(), dev.xsoz.client.training.bot.Personality.RUSHER, kit, (int) Math.round(speed())));
        Vec3d at = spawnPointFor(1);
        if (at.distanceTo(new Vec3d(pl.getX(), pl.getY(), pl.getZ())) > 14) {
            Vec3d d = at.subtract(pl.getX(), pl.getY(), pl.getZ()).normalize().multiply(10);
            Vec3d near = new Vec3d(pl.getX() + d.x, pl.getY(), pl.getZ() + d.z);
            var g = dev.xsoz.client.training.bot.Pathfinder.ground(s.world, net.minecraft.util.math.BlockPos.ofFloored(near).up(3));
            if (g != null) at = Vec3d.ofBottomCenter(g);
        }
        bot.spawn(this, at, Scene.yawTowards(at, new Vec3d(pl.getX(), pl.getY(), pl.getZ())));
        gapples = count(pl);
        status = "It's chasing you. Break away, then eat - never with it on top of you.";
    }

    private static int count(ServerPlayerEntity pl) {
        int n = 0;
        var inv = pl.getInventory();
        for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isOf(Items.GOLDEN_APPLE)) n += inv.getStack(i).getCount();
        return n;
    }

    @Override
    protected void tick() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        tickFight();
        double dist = bot.alive() ? bot.pos().distanceTo(new Vec3d(pl.getX(), pl.getY(), pl.getZ())) : 99;
        shownDistance = dist;
        boolean nowEating = pl.isUsingItem() && pl.getActiveItem().isOf(Items.GOLDEN_APPLE);
        if (nowEating && !eating) hitWhileEating = false;
        eating = nowEating;
        shownEating = nowEating;
        int c = count(pl);
        if (c < gapples) {
            gapples = c;
            if (hitWhileEating) rep(false, dist, "It hit you while you ate - break away first");
            else if (dist < 8) rep(false, dist, String.format(Locale.ROOT, "Too close: it was %.0f blocks away - get 8+ before you eat", dist));
            else rep(true, dist, String.format(Locale.ROOT, "Clean reset - %.0f blocks of space", dist));
            refillGapples(pl);
        } else if (c > gapples) {
            gapples = c;
        }
        if (age() == 80) status = "";
    }

    private void refillGapples(ServerPlayerEntity pl) {
        int slot = kit.slot(Kit.Role.GAPPLE);
        if (slot >= 0 && pl.getInventory().getStack(slot).getCount() < 8) kit.refill(pl, Kit.Role.GAPPLE);
        gapples = count(pl);
        int ps = kit.slot(Kit.Role.PEARL);
        if (ps >= 0 && pl.getInventory().getStack(ps).getCount() < 4) kit.refill(pl, Kit.Role.PEARL);
    }

    @Override
    public void onPlayerDamaged(ServerPlayerEntity p, DamageSource source) {
        super.onPlayerDamaged(p, source);
        if (eating && source.getAttacker() != null) hitWhileEating = true;
    }

    @Override
    protected void onBotDied(PvpBot b, Fighter f) {
        creditKill(f);
        feedback("You beat it - a new one in 3 s", 0xFF4CD765);
        respawnBotLater(b, 60);
    }

    @Override
    public boolean onPlayerWouldDie(ServerPlayerEntity p) {
        creditKill(you);
        rep(false, 0, "Caught - you went down. Break away earlier");
        p.setHealth(12f);
        Vec3d at = spawnPointFor(0);
        Scene.teleportKeepLook(p, at);
        return false;
    }

    @Override
    public void renderExtra(DrawContext c, int x, int y) {
        double d = shownDistance;
        if (d < 0) return;
        String s = shownEating ? String.format(Locale.ROOT, "EATING - chaser %.0f blocks away", d)
                : String.format(Locale.ROOT, "Chaser %.0f blocks away%s", d, d >= 8 ? " - safe to eat" : "");
        Gfx.text(c, s, x, y, d >= 8 ? Theme.accent() : 0xFFFFB020);
        super.renderExtra(c, x, y + 12);
    }
}
