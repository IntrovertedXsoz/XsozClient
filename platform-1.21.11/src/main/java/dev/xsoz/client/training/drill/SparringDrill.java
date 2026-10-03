package dev.xsoz.client.training.drill;

import dev.xsoz.client.training.DrillDef;
import dev.xsoz.client.training.bot.BotLevel;
import dev.xsoz.client.training.bot.Fighter;
import dev.xsoz.client.training.bot.FreeRoamConfig;
import dev.xsoz.client.training.bot.PvpBot;
import java.util.Locale;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * A crystal PvP fight in rounds against one {@link PvpBot} wearing your kit. Pop all of its totems
 * (and then kill it) before it does the same to you. The speed slider is the bot's reaction time;
 * the level is how clean it plays and how many totems it has.
 */
public final class SparringDrill extends BotFightDrill {
    private static final int ROUND_LIMIT = 20 * 180;

    private enum State { PREP, FIGHT, BETWEEN }

    private PvpBot bot;
    private State state = State.PREP;
    private int stateAt;
    private int round;
    private int wins;
    private float lowestPlayerHp = 99f;

    public SparringDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.SPARRING, mode, p, hints);
    }

    // ------------------------------------------------------------------ GameTest access

    public MannequinEntity botForTests() { return bot.body(); }

    public PvpBot pvpBotForTests() { return bot; }

    public float botHealthForTests() { return bot.health(); }

    public int botTotemsForTests() { return bot.totemCount(); }

    public boolean fightingForTests() { return state == State.FIGHT; }

    public int playerPopsForTests() { return you.pops; }

    public int crystalsBrokenForTests() { return bot.crystalsBroken; }

    public float lowestPlayerHpForTests() { return lowestPlayerHp; }

    public String debugForTests() { return bot.debug() + " state " + state + " round " + round; }

    // ------------------------------------------------------------------ setup

    @Override
    protected int arenaRadius() { return 24; }

    private BotLevel botLevel() { return level >= 3 ? BotLevel.GODLIKE : level == 2 ? BotLevel.PRO : BotLevel.CASUAL; }

    private int botTotems() { return level >= 3 ? 8 : level == 2 ? 5 : 3; }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        if (s.isTest()) testFloor(pl.getBlockPos(), 16);
        normalDifficulty();
        addPlayer();
        bot = addBot(new PvpBot("Bot", 1, botLevel(), FreeRoamConfig.Fight.CRYSTAL, kit, (int) Math.round(speed())).totems(botTotems()));
        startRound(pl);
    }

    private void startRound(ServerPlayerEntity pl) {
        round++;
        giveKit(pl, GameMode.SURVIVAL);
        pl.setHealth(pl.getMaxHealth());
        pl.setAbsorptionAmount(0);
        pl.clearStatusEffects();
        pl.extinguish();
        pl.getHungerManager().setFoodLevel(20);
        BlockPos c = center();
        Direction f = pl.getHorizontalFacing();
        Vec3d me = Vec3d.ofBottomCenter(c);
        Scene.teleport(pl, me, f.getPositiveHorizontalDegrees(), 10f);
        holdAt(me);
        BlockPos bp = dev.xsoz.client.training.bot.Pathfinder.ground(s.world, c.offset(f, 10).up(2));
        Vec3d botAt = Vec3d.ofBottomCenter(bp != null ? bp : c.offset(f, 10));
        bot.spawn(this, botAt, Scene.yawTowards(botAt, me));
        state = State.PREP;
        stateAt = age();
        status = "Round " + round + ": get ready...";
    }

    @Override
    protected boolean playerMayMove() { return state != State.PREP; }

    // ------------------------------------------------------------------ tick

    @Override
    protected void tick() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        switch (state) {
            case PREP -> {
                int left = 60 - (age() - stateAt);
                status = left > 0 ? "Round " + round + " starts in " + (left / 20 + 1) + "..." : "";
                if (left <= 0) {
                    state = State.FIGHT;
                    stateAt = age();
                    sound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 2f);
                    feedback("FIGHT!", 0xFFFF6D7E);
                }
            }
            case FIGHT -> {
                lowestPlayerHp = Math.min(lowestPlayerHp, pl.getHealth() + pl.getAbsorptionAmount());
                tickFight();
                if (age() - stateAt > ROUND_LIMIT) endRound(false, "Time - a round lasts 3 minutes at most");
            }
            case BETWEEN -> {
                if (age() - stateAt > 60) {
                    // a fresh arena every round
                    if (arena != null) rebuildArena();
                    startRound(pl);
                }
            }
        }
    }

    private void rebuildArena() { arena.refresh(s.world, dev.xsoz.client.training.session.SkyArena.Floor.STONE); }

    @Override
    protected void onBotDied(PvpBot b, Fighter f) {
        creditKill(f);
        endRound(true, "Bot killed");
    }

    @Override
    public boolean onPlayerWouldDie(ServerPlayerEntity p) {
        p.setHealth(p.getMaxHealth());
        creditKill(you);
        if (state == State.FIGHT) endRound(false, "You died - no totem in your hand");
        return false;
    }

    private void endRound(boolean won, String why) {
        if (state != State.FIGHT) return;
        state = State.BETWEEN;
        stateAt = age();
        if (won) wins++;
        sound(won ? net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_NO, 1f);
        bot.despawnBody();
        ServerPlayerEntity pl = player();
        if (pl != null) pl.changeGameMode(GameMode.ADVENTURE);
        Fighter bf = fighter(bot);
        rep(won, won ? 1 : 0, String.format(Locale.ROOT, "%s - %s. Bot popped %d, you popped %d", won ? "Round won" : "Round lost", why,
                bf == null ? 0 : bf.pops, you.pops));
        status = finished ? "" : "Next round in 3 s...";
    }

    @Override
    public String summaryLine() {
        String r = retotemLine();
        return "Rounds won: " + wins + " of " + reps.size() + (r.isEmpty() ? "" : ", " + r);
    }
}
