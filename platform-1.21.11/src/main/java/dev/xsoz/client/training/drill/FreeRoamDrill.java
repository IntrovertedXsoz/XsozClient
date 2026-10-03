package dev.xsoz.client.training.drill;

import dev.xsoz.client.training.DrillDef;
import dev.xsoz.client.training.bot.ArenaBuilder;
import dev.xsoz.client.training.bot.BotLevel;
import dev.xsoz.client.training.bot.Fighter;
import dev.xsoz.client.training.bot.FreeRoamConfig;
import dev.xsoz.client.training.bot.PvpBot;
import java.util.Locale;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Free Roam: fight bots as long as you like, in the mode, map and team setup you picked. Everyone
 * respawns after a death (you right away with spawn protection, bots after 3 s), kills and deaths
 * are counted, and the bots fight each other too in free-for-all. Drop an item to stop.
 */
public final class FreeRoamDrill extends BotFightDrill {
    /** Set on the client right before the drill starts (TrainingManager reads it). */
    private static volatile FreeRoamConfig pending = new FreeRoamConfig();

    private final FreeRoamConfig cfg;

    /** Watch mode: how you watch the bot you're on. */
    public enum View {
        EYES("Through its eyes"), BEHIND("From behind"), FREE("Free camera");

        public final String title;

        View(String title) { this.title = title; }
    }

    /** Watch mode: the bot you're watching (index into the bots, -1 none) and how. Set on the server thread. */
    private volatile int watching = -1;
    private volatile View view = View.FREE;
    /** The body the camera was put on: a different one means a respawn, the same one gone means you left it. */
    private net.minecraft.entity.Entity attachedTo;
    private int watchedDeadSince = -1;
    /** Tests: a fake player can't carry a camera (the world doesn't track it), so it is only noted here. */
    public net.minecraft.entity.Entity cameraForTests;

    public FreeRoamDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.FREE_ROAM, Mode.FIXED, p, hints);
        this.cfg = pending.copy();
        endless(true);
        level(cfg.level);
    }

    public static void configure(FreeRoamConfig c) { pending = c.copy(); }

    public static FreeRoamConfig pendingConfig() { return pending.copy(); }

    public FreeRoamConfig config() { return cfg; }

    @Override
    protected int arenaRadius() { return cfg.size.radius; }

    @Override
    protected int arenaHeight() { return 34; }

    @Override
    protected dev.xsoz.client.training.session.SkyArena.Theme groundTheme() { return cfg.terrain.theme(); }

    @Override
    protected void setup() {
        ServerPlayerEntity pl = player();
        if (s.isTest()) testFloor(pl.getBlockPos(), Math.min(20, cfg.size.radius));
        else new ArenaBuilder(s.world, arena, rng).decorate(cfg.terrain);
        normalDifficulty();
        if (cfg.watch) {
            // you watch: a spectator in the arena, the bots fight each other
            s.clearInventory(pl);
            pl.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
        } else {
            addPlayer();
            giveKit(pl, net.minecraft.world.GameMode.SURVIVAL);
        }
        Vec3d me = Vec3d.ofBottomCenter(dev.xsoz.client.training.bot.Pathfinder.ground(s.world, center().up(8)) != null
                ? dev.xsoz.client.training.bot.Pathfinder.ground(s.world, center().up(8)) : center());
        Scene.teleport(pl, me, pl.getYaw(), 0f);
        int allies = cfg.alliesUsed();
        int count = cfg.watch ? Math.max(2, cfg.bots) : cfg.bots;
        for (int i = 0; i < count; i++) {
            BotLevel lvl = cfg.levelOf(i, rng);
            int team = cfg.teamOf(i);
            String name = team == 0 ? "Ally " + (i + 1) : cfg.teams == FreeRoamConfig.Teams.FFA ? "Bot " + (i + 1) : "Enemy " + (i + 1 - allies);
            var who = cfg.personalityOf(i, rng);
            String tag = lvl == BotLevel.HACKER ? "Hacker" : cfg.mixedLevels ? lvl.title + ", " + who.title : who.title;
            PvpBot b = addBot(new PvpBot(name + " (" + tag + ")", team, lvl, cfg.abilities(), who, kit, cfg.reactionMs));
            b.showLevel = cfg.adaptive;
            Vec3d at = spawnPointFor(team);
            b.spawn(this, at, Scene.yawTowards(at, me));
        }
        status = cfg.watch ? "Watching - arrow keys to pick a bot and a view. Right Shift to stop."
                : cfg.abilitiesTitle() + " on " + cfg.terrain.title + " - drop an item to stop.";
    }

    @Override
    protected void tick() {
        tickFight();
        if (age() == 60 && !cfg.watch) status = "";
        if (cfg.watch) {
            keepInside(player());
            tickWatch();
        }
    }

    // ------------------------------------------------------------------ watch mode

    public View view() { return view; }

    /** The bot being watched, or null (free camera). */
    public PvpBot watched() {
        int i = watching;
        return view == View.FREE || i < 0 || i >= bots.size() ? null : bots.get(i);
    }

    /** Left / right arrow: the previous or next bot that's alive. Server thread. */
    public void watchNext(int dir) {
        if (!cfg.watch || bots.isEmpty()) return;
        int n = bots.size();
        int i = watching < 0 ? (dir > 0 ? -1 : 0) : watching;
        for (int k = 0; k < n; k++) {
            i = Math.floorMod(i + dir, n);
            if (bots.get(i).alive()) break;
        }
        watching = i;
        if (view == View.FREE) view = View.EYES;
        attach();
    }

    /** Up / down arrow: through its eyes, from behind, free camera. Server thread. */
    public void watchView(int dir) {
        if (!cfg.watch || bots.isEmpty()) return;
        View[] all = View.values();
        view = all[Math.floorMod(view.ordinal() + dir, all.length)];
        if (view != View.FREE && (watching < 0 || !bots.get(watching).alive())) {
            watching = -1;
            watchNext(1);
            return;
        }
        attach();
    }

    private net.minecraft.entity.Entity camera(ServerPlayerEntity pl) {
        return s.isTest() ? (cameraForTests == null ? pl : cameraForTests) : pl.getCameraEntity();
    }

    private void setCamera(ServerPlayerEntity pl, net.minecraft.entity.Entity e) {
        if (s.isTest()) cameraForTests = e;
        else pl.setCameraEntity(e);
    }

    private void attach() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        PvpBot b = watched();
        if (b == null || !b.alive()) {
            attachedTo = null;
            if (camera(pl) != pl) setCamera(pl, pl);
            return;
        }
        attachedTo = b.body();
        setCamera(pl, b.body());
        watchedDeadSince = -1;
    }

    /** Keeps the camera on the watched bot through respawns, and follows what you did with the mouse and Shift. */
    private void tickWatch() {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        var cam = camera(pl);
        // clicked a bot yourself (vanilla spectating): that's the one you're watching now
        if (cam != pl) {
            for (int i = 0; i < bots.size(); i++) {
                if (bots.get(i).body() == cam && (watching != i || view == View.FREE)) {
                    watching = i;
                    if (view == View.FREE) view = View.EYES;
                    attachedTo = cam;
                }
            }
        }
        PvpBot b = watched();
        if (b == null) return;
        if (!b.alive()) {
            // it died: stay a moment, then on to the next one
            if (watchedDeadSince < 0) watchedDeadSince = age();
            else if (age() - watchedDeadSince > 40) watchNext(1);
            return;
        }
        watchedDeadSince = -1;
        if (cam == b.body()) return;
        if (cam == pl && attachedTo == b.body()) {
            view = View.FREE; // Shift: you left it
            attachedTo = null;
            return;
        }
        attach(); // it respawned with a new body
    }

    @Override
    public void renderOverlay(net.minecraft.client.gui.DrawContext c, int sw, int sh) {
        super.renderOverlay(c, sw, sh);
        if (!cfg.watch) return;
        PvpBot b = watched();
        java.util.List<String> lines = new java.util.ArrayList<>();
        String head;
        if (b == null) {
            head = "FREE CAMERA";
            lines.add("Left / Right: watch a bot.  Or click one.");
        } else {
            head = "WATCHING  " + b.name + "  -  " + b.level.title;
            lines.add(b.alive() ? String.format(Locale.ROOT, "%.0f health   %d totems   %s", b.health(), b.totemCount(),
                    b.lastAction == null || b.lastAction.isEmpty() ? "" : "last: " + b.lastAction) : "Dead - waiting for the respawn...");
            lines.add("Left / Right: other bot     Up / Down: view (" + view.title + ")");
        }
        lines.add("Right Shift: stop");
        int w = dev.xsoz.client.render.Gfx.widthBold(head);
        for (String l : lines) w = Math.max(w, dev.xsoz.client.render.Gfx.width(l));
        w += 20;
        int h = 16 + lines.size() * 10;
        int x = sw / 2 - w / 2;
        int y = sh - h - 44;
        dev.xsoz.client.render.Gfx.round(c, x, y, w, h, 5, 0xD8101114);
        dev.xsoz.client.render.Gfx.textBold(c, head, x + 10, y + 5, dev.xsoz.client.render.Theme.accent());
        int ly = y + 17;
        for (String l : lines) {
            dev.xsoz.client.render.Gfx.text(c, l, x + 10, ly, dev.xsoz.client.render.Theme.TEXT_2);
            ly += 10;
        }
    }

    @Override
    protected void onBotDied(PvpBot b, Fighter f) {
        Fighter k = creditKill(f);
        if (k == you) {
            rep(true, 1, "You took out " + f.name);
            if (rng.nextInt(10) < 3) say(f.name, rng.nextBoolean() ? "gg" : "gg wp");
            recordBeaten(b.level);
            if (cfg.adaptive) adapt(+1);
        } else {
            feedback(f.name + " died" + (k != null ? " - " + k.name : ""), 0xFFECEEF2);
        }
        respawnBotLater(b, 60);
    }

    @Override
    public boolean onPlayerWouldDie(ServerPlayerEntity p) {
        Fighter k = creditKill(you);
        rep(false, 0, k != null ? "Killed by " + k.name : "You died");
        if (k != null && k != you && rng.nextInt(10) < 4) say(k.name, rng.nextInt(3) == 0 ? "close one" : "gg");
        if (cfg.adaptive) adapt(-1);
        respawnPlayer(p);
        return false;
    }

    /** Adaptive: every bot against you gets one level better (you won) or easier (you lost). */
    private void adapt(int by) {
        BotLevel now = null;
        for (PvpBot b : botsForTests()) {
            if (b.team == 0) continue;
            int n = Math.max(BotLevel.BEGINNER.n(), Math.min(BotLevel.GODLIKE.n(), b.level.n() + by));
            if (n != b.level.n()) b.setLevel(BotLevel.of(n));
            now = b.level;
        }
        if (now == null) return;
        feedback(by > 0 ? "Bots got better: " + now.title : "Bots got easier: " + now.title, by > 0 ? 0xFFFFD166 : 0xFF63D7C7);
        sound(by > 0 ? net.minecraft.sound.SoundEvents.BLOCK_BEACON_POWER_SELECT : net.minecraft.sound.SoundEvents.BLOCK_BEACON_DEACTIVATE, 1.2f);
    }

    /** The ladder: the best bot level you've killed for this weapon mix (saved on the client). */
    private void recordBeaten(BotLevel lvl) {
        if (s.isTest() || dev.xsoz.client.training.TrainingFlags.testMode) return;
        String key = cfg.abilitiesTitle();
        net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
            var prog = dev.xsoz.client.training.TrainingStore.progress();
            if (prog.beatenLevels == null) prog.beatenLevels = new java.util.LinkedHashMap<>();
            int was = prog.beatenLevels.getOrDefault(key, 0);
            if (lvl.n() > was) {
                prog.beatenLevels.put(key, lvl.n());
                dev.xsoz.client.training.TrainingStore.save();
            }
        });
        int best = 0;
        try {
            var m = dev.xsoz.client.training.TrainingStore.progress().beatenLevels;
            best = m == null ? 0 : m.getOrDefault(key, 0);
        } catch (RuntimeException ignored) {
            // the client store is not loaded yet
        }
        if (lvl.n() > best) {
            feedback("New best: you beat a " + lvl.title + " bot!", 0xFFFFD166);
            sound(net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
        }
    }

    /** A line in chat from a bot, like a player would type. */
    private void say(String who, String text) {
        ServerPlayerEntity pl = player();
        if (pl == null) return;
        String name = who.contains(" (") ? who.substring(0, who.indexOf(" (")) : who;
        pl.sendMessage(net.minecraft.text.Text.literal("<" + name + "> " + text), false);
    }

    /** A spectator flies through walls: keep them inside the arena. */
    private void keepInside(ServerPlayerEntity pl) {
        if (pl == null) return;
        var c = center();
        int r = radius();
        double x = Math.max(c.getX() - r + 0.5, Math.min(c.getX() + r + 0.5, pl.getX()));
        double z = Math.max(c.getZ() - r + 0.5, Math.min(c.getZ() + r + 0.5, pl.getZ()));
        double y = Math.max(c.getY() - 2, Math.min(c.getY() + arenaHeight() - 1, pl.getY()));
        if (pl.getCameraEntity() == pl && (x != pl.getX() || y != pl.getY() || z != pl.getZ())) Scene.teleportKeepLook(pl, new Vec3d(x, y, z));
    }

    @Override
    public String summaryLine() {
        if (you == null) {
            int k = 0;
            for (var f : fighters()) k += f.kills;
            return "Watched " + k + " kill" + (k == 1 ? "" : "s");
        }
        String r = retotemLine();
        return String.format(Locale.ROOT, "%d kills, %d deaths, %d pops%s", you.kills, you.deaths, you.pops, r.isEmpty() ? "" : ", " + r);
    }
}
