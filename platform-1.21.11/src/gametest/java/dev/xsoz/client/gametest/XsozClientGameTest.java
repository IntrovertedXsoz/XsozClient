package dev.xsoz.client.gametest;

import dev.xsoz.client.gui.KitEditorScreen;
import dev.xsoz.client.gui.XsozTitleScreen;
import dev.xsoz.client.trainer.crystal.TrainerScreen;
import dev.xsoz.client.training.DrillDef;
import dev.xsoz.client.training.KitSpec;
import dev.xsoz.client.training.TrainingFlags;
import dev.xsoz.client.training.TrainingGate;
import dev.xsoz.client.training.TrainingManager;
import dev.xsoz.client.training.TrainingStore;
import dev.xsoz.client.training.drill.Drill;
import dev.xsoz.client.training.drill.MarkerDrill;
import dev.xsoz.client.training.drill.SparringDrill;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

/**
 * The real game, end to end: opens a copy of the player's "Practice Trainer" flat world (or a fresh
 * superflat world if there is none), walks through the menus and runs every drill that changed,
 * taking a screenshot of each, then lets the sparring bot fight a player who stands still and
 * checks the player really took damage. Results go to xsoz-client-test-report.txt in the run dir.
 */
public final class XsozClientGameTest implements FabricClientGameTest {
    private final List<String> report = new ArrayList<>();

    private void log(String s) {
        report.add(s);
        System.out.println("[XsozClientTest] " + s);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean showcase = System.getenv("XSOZ_SHOWCASE") != null;
        if (showcase) {
            ctx.getInput().resizeWindow(1600, 900);
            // GUI scale "Normal" (2) so every page fits on screen
            ctx.runOnClient(c -> {
                c.options.getGuiScale().setValue(2);
                c.onResolutionChanged();
            });
        }
        ctx.waitTicks(20);
        // ---- the first-launch intro, page by page, then the black hole menu
        dev.xsoz.client.gui.IntroScreen[] intro = new dev.xsoz.client.gui.IntroScreen[1];
        ctx.setScreen(() -> intro[0] = new dev.xsoz.client.gui.IntroScreen());
        ctx.waitTicks(30);
        ctx.takeScreenshot("xsoz-00a-intro-forming");
        ctx.waitTicks(80);
        ctx.takeScreenshot("xsoz-00b-intro-welcome");
        int[][] pages = {{1, 0}, {2, 0}, {3, 0}, {4, 0}, {4, 3}, {4, 5}};
        String[] names = {"name", "skill", "ask-tour", "tour-1", "tour-learn", "tour-mods"};
        for (int i = 0; i < pages.length; i++) {
            int[] pg = pages[i];
            ctx.runOnClient(c -> intro[0].goForTests(pg[0], pg[1]));
            ctx.waitTicks(15);
            ctx.takeScreenshot("xsoz-00c-intro-" + names[i]);
        }
        ctx.runOnClient(c -> intro[0].goForTests(5, 0));
        ctx.waitTicks(34);
        ctx.takeScreenshot("xsoz-00d-intro-dive");
        ctx.waitTicks(40);
        log("After the intro: " + ctx.computeOnClient(c -> c.currentScreen == null ? "null" : c.currentScreen.getClass().getSimpleName())
                + ", intro done = " + ctx.computeOnClient(c -> TrainingStore.progress().introDone));
        ctx.waitTicks(20);
        ctx.takeScreenshot("xsoz-01-main-menu");
        ctx.runOnClient(c -> {
            TrainingStore.progress().experience = "PRO";
            TrainingFlags.testMode = !showcase;
        });

        Path saves = ctx.computeOnClient(c -> c.getLevelStorage().getSavesDirectory());
        Path world = saves.resolve("Practice Trainer");
        copyPracticeWorld(world);
        TestSingleplayerContext sp;
        if (Files.isDirectory(world)) {
            log("Opening a copy of the Practice Trainer world");
            sp = new TestWorldSaveImpl(ctx, world).open();
        } else {
            log("No Practice Trainer copy found - creating a superflat world");
            sp = ctx.worldBuilder().adjustSettings(cr -> cr.setWorldType(cr.getNormalWorldTypes().stream()
                    .filter(t -> t.getName().getString().toLowerCase(Locale.ROOT).contains("flat")).findFirst().orElseThrow())).create();
        }
        sp.getClientWorld().waitForChunksRender();
        sp.getServer().runCommand("time set day");
        sp.getServer().runCommand("weather clear");
        ctx.waitTicks(20);
        TrainingGate.Result gate = ctx.computeOnClient(TrainingGate::check);
        log("Training gate: " + (gate.ok() ? "open" : gate.message() + " " + gate.detail()));
        ctx.takeScreenshot("xsoz-02-in-world");

        // ---- menus
        ctx.getInput().pressKey(GLFW.GLFW_KEY_RIGHT_SHIFT);
        ctx.waitTicks(15);
        ctx.takeScreenshot("xsoz-03-right-shift-menu");
        ctx.setScreen(() -> null);
        for (DrillDef d : new DrillDef[] {DrillDef.CRYSTAL_SPOT, DrillDef.SHIELD_READ, DrillDef.SPARRING}) {
            ctx.setScreen(() -> {
                TrainerScreen s = new TrainerScreen(null, TrainerScreen.Tab.TRAINING);
                s.selectForTests(d, 2);
                return s;
            });
            ctx.waitTicks(15);
            ctx.takeScreenshot("xsoz-04-training-" + d.name().toLowerCase(Locale.ROOT));
        }
        for (var cat : dev.xsoz.client.trainer.crystal.Lessons.Category.values()) {
            ctx.setScreen(() -> {
                TrainerScreen s2 = new TrainerScreen(null, TrainerScreen.Tab.LEARN);
                s2.learnForTests(cat, cat == dev.xsoz.client.trainer.crystal.Lessons.Category.CRYSTAL ? 6 : 1);
                return s2;
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("xsoz-04b-learn-" + cat.name().toLowerCase(Locale.ROOT));
        }
        ctx.setScreen(() -> new TrainerScreen(null, TrainerScreen.Tab.FREE_ROAM));
        ctx.waitTicks(10);
        ctx.takeScreenshot("xsoz-04c-free-roam-page");
        ctx.setScreen(() -> new TrainerScreen(null, TrainerScreen.Tab.SETTINGS));
        ctx.waitTicks(10);
        ctx.takeScreenshot("xsoz-04d-settings");
        ctx.setScreen(() -> new KitEditorScreen(null));
        ctx.waitTicks(15);
        ctx.takeScreenshot("xsoz-05-kit-editor");
        ctx.setScreen(() -> {
            KitEditorScreen k = new KitEditorScreen(null);
            k.selectSlotForTests(KitSpec.HEAD);
            return k;
        });
        ctx.waitTicks(15);
        ctx.takeScreenshot("xsoz-06-kit-editor-enchant");
        ctx.setScreen(() -> null);
        ctx.waitTicks(5);

        if (showcase && gate.ok()) {
            showcase(ctx, sp);
        } else if (System.getenv("XSOZ_TUT") != null && gate.ok()) {
            for (var id : new dev.xsoz.client.trainer.crystal.Tutorials.Id[] {dev.xsoz.client.trainer.crystal.Tutorials.Id.CRYSTAL_BASICS,
                    dev.xsoz.client.trainer.crystal.Tutorials.Id.HIT_CRYSTAL,
                    dev.xsoz.client.trainer.crystal.Tutorials.Id.WTAP, dev.xsoz.client.trainer.crystal.Tutorials.Id.CRIT,
                    dev.xsoz.client.trainer.crystal.Tutorials.Id.SHIELD_BREAK}) {
                tutorial(ctx, sp, id, id.name().toLowerCase(Locale.ROOT));
            }
        } else if (System.getenv("XSOZ_QUICK") != null && gate.ok()) {
            sparring(ctx, sp);
        } else if (!gate.ok()) {
            log("Drills skipped: the world is not a superflat single player world.");
        } else {
            tutorial(ctx, sp, dev.xsoz.client.trainer.crystal.Tutorials.Id.ANCHOR_DTAP, "anchor-dtap");
            tutorial(ctx, sp, dev.xsoz.client.trainer.crystal.Tutorials.Id.HIT_CRYSTAL, "hit-crystal");
            tutorial(ctx, sp, dev.xsoz.client.trainer.crystal.Tutorials.Id.CRIT, "crit");
            tutorial(ctx, sp, dev.xsoz.client.trainer.crystal.Tutorials.Id.WTAP, "wtap");
            tutorial(ctx, sp, dev.xsoz.client.trainer.crystal.Tutorials.Id.CRYSTAL_DTAP, "double-tap");
            tutorial(ctx, sp, dev.xsoz.client.trainer.crystal.Tutorials.Id.SHIELD_BREAK, "shield-break");
            runDrill(ctx, sp, DrillDef.OBBY_CRYSTAL, 1, 50, "obby");
            runDrill(ctx, sp, DrillDef.OBBY_CRYSTAL, 2, 50, "obby-l2");
            runDrill(ctx, sp, DrillDef.ANCHOR_CHAIN, 1, 50, "anchor");
            runDrill(ctx, sp, DrillDef.CRYSTAL_SPOT, 1, 20, "best-crystal");
            runDrill(ctx, sp, DrillDef.CRYSTAL_SPOT, 2, 20, "best-crystal-l2");
            runDrill(ctx, sp, DrillDef.FACE_PLACE, 1, 20, "build-crystal");
            runDrill(ctx, sp, DrillDef.SHIELD_READ, 1, 30, "shield");
            runDrill(ctx, sp, DrillDef.SHIELD_READ, 3, 60, "shield-l3");
            runDrill(ctx, sp, DrillDef.SAFE_GAPPLE, 1, 120, "gapple-chase");
            runDrill(ctx, sp, DrillDef.PEARL_AIM, 3, 20, "pearl-pillar");
            runDrill(ctx, sp, DrillDef.RANGE_CONTROL, 2, 60, "range-l2");
            runDrill(ctx, sp, DrillDef.LAYOUT_RECALL, 2, 80, "layout-cues");
            runDrill(ctx, sp, DrillDef.LAYOUT_RECALL, 3, 60, "layout-situations");
            runDrill(ctx, sp, DrillDef.HIT_CRYSTAL, 1, 30, "hit-crystal");
            runDrill(ctx, sp, DrillDef.DOUBLE_TAP, 1, 60, "double-tap");
            runDrill(ctx, sp, DrillDef.KEYBIND_REFLEX, 1, 60, "keybind");
            sparring(ctx, sp);
            freeRoam(ctx, sp, dev.xsoz.client.training.bot.FreeRoamConfig.Fight.EVERYTHING, dev.xsoz.client.training.bot.FreeRoamConfig.Terrain.BIRCH,
                    dev.xsoz.client.training.bot.FreeRoamConfig.Teams.FFA, 4, 0, "ffa-birch");
            freeRoam(ctx, sp, dev.xsoz.client.training.bot.FreeRoamConfig.Fight.CRYSTAL, dev.xsoz.client.training.bot.FreeRoamConfig.Terrain.DESERT,
                    dev.xsoz.client.training.bot.FreeRoamConfig.Teams.TEAMS, 4, 2, "teams-desert");
            freeRoam(ctx, sp, dev.xsoz.client.training.bot.FreeRoamConfig.Fight.MACE, dev.xsoz.client.training.bot.FreeRoamConfig.Terrain.STONE,
                    dev.xsoz.client.training.bot.FreeRoamConfig.Teams.VS_YOU, 1, 0, "mace-stone");
            realDeath(ctx, sp);
        }

        writeReport(ctx);
        XsozTitleScreen.allowVanillaOnce = true;
        sp.close();
        XsozTitleScreen.allowVanillaOnce = true;
        ctx.setScreen(TitleScreen::new);
    }

    /** README pictures: the best moments, in a big window, without test labels. */
    private void showcase(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.getInput().setCursorPos(4, 4); // no hover tooltips in the pictures
        ctx.setScreen(() -> {
            TrainerScreen s2 = new TrainerScreen(null, TrainerScreen.Tab.LEARN);
            s2.learnForTests(dev.xsoz.client.trainer.crystal.Lessons.Category.CRYSTAL, 15);
            return s2;
        });
        ctx.waitTicks(10);
        ctx.takeScreenshot("show-learn");
        ctx.setScreen(() -> new TrainerScreen(null, TrainerScreen.Tab.FREE_ROAM));
        ctx.waitTicks(10);
        ctx.takeScreenshot("show-freeroam-page");
        ctx.setScreen(() -> new TrainerScreen(null, TrainerScreen.Tab.TRAINING));
        ctx.waitTicks(10);
        ctx.takeScreenshot("show-training");
        ctx.setScreen(() -> null);
        ctx.waitTicks(5);
        tutorial(ctx, sp, dev.xsoz.client.trainer.crystal.Tutorials.Id.ANCHOR_DTAP, "anchor-dtap");
        tutorial(ctx, sp, dev.xsoz.client.trainer.crystal.Tutorials.Id.HIT_CRYSTAL, "hit-crystal");
        tutorial(ctx, sp, dev.xsoz.client.trainer.crystal.Tutorials.Id.SHIELD_BREAK, "shield-break");
        // a fight, camera on the nearest bot
        var cfg = new dev.xsoz.client.training.bot.FreeRoamConfig();
        cfg.abilities = new java.util.ArrayList<>(dev.xsoz.client.training.bot.FreeRoamConfig.Fight.EVERYTHING.abilities());
        cfg.terrain = dev.xsoz.client.training.bot.FreeRoamConfig.Terrain.BIRCH;
        cfg.teams = dev.xsoz.client.training.bot.FreeRoamConfig.Teams.FFA;
        cfg.bots = 4;
        cfg.level = 4;
        cfg.size = dev.xsoz.client.training.bot.FreeRoamConfig.Size.SMALL;
        ctx.runOnClient(c -> dev.xsoz.client.training.drill.FreeRoamDrill.configure(cfg));
        start(ctx, DrillDef.FREE_ROAM, 4, 0.5);
        for (int i = 0; i < 3; i++) {
            ctx.waitTicks(120);
            lookAtNearestBot(ctx);
            ctx.waitTicks(3);
            ctx.takeScreenshot("show-freeroam-fight-" + i);
        }
        stop(ctx);
    }

    private static void lookAtNearestBot(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> {
            if (c.player == null || c.world == null) return;
            net.minecraft.entity.Entity best = null;
            double bd = 1e9;
            for (var e : c.world.getEntities()) {
                if (!(e instanceof net.minecraft.entity.decoration.MannequinEntity)) continue;
                double d = e.squaredDistanceTo(c.player);
                if (d < bd) {
                    bd = d;
                    best = e;
                }
            }
            if (best == null) return;
            var d = best.getEyePos().subtract(c.player.getEyePos());
            c.player.setYaw((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            c.player.setPitch((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        });
    }

    /** Plays a tutorial's demo in the real game: screenshots in slow motion, at a pause, and on your turn. */
    private void tutorial(ClientGameTestContext ctx, TestSingleplayerContext sp, dev.xsoz.client.trainer.crystal.Tutorials.Id id, String tag) {
        TrainingGate.Result r = ctx.computeOnClient(c -> TrainingManager.startTutorial(id));
        if (!r.ok()) {
            log("Tutorial " + id + " did not start: " + r.message());
            return;
        }
        ctx.waitTicks(90);
        int shots = 0;
        boolean pausedShot = false;
        boolean slowShot = false;
        for (int i = 0; i < 4000; i++) {
            ctx.waitTick();
            var d = ctx.computeOnClient(c -> TrainingManager.active());
            if (!(d instanceof dev.xsoz.client.training.drill.TutorialDrill t)) {
                log("Tutorial " + id + " ended early");
                return;
            }
            if (t.waiting) {
                if (!pausedShot) {
                    ctx.takeScreenshot("xsoz-20-tut-" + tag + "-pause");
                    pausedShot = true;
                }
                ctx.getInput().pressKey(GLFW.GLFW_KEY_SPACE);
                ctx.waitTicks(3);
            } else if (!slowShot && t.rate < 19f && t.phase == dev.xsoz.client.training.drill.TutorialDrill.Phase.DEMO && !t.caption.isEmpty()) {
                ctx.waitTicks(2);
                ctx.takeScreenshot("xsoz-20-tut-" + tag + "-slowmo");
                slowShot = true;
            }
            if (t.phase == dev.xsoz.client.training.drill.TutorialDrill.Phase.TRY) {
                ctx.waitTicks(10);
                ctx.takeScreenshot("xsoz-20-tut-" + tag + "-your-turn");
                log("Tutorial " + id + ": demo played through (slow motion " + slowShot + ", pause " + pausedShot + String.format(Locale.ROOT, ", hit reach %.2f", t.demoHitReach) + ")");
                if (t.demoHitReach > 3.0) throw new AssertionError("Tutorial " + id + " hit the dummy from " + t.demoHitReach + " blocks away");
                ctx.runOnClient(c -> TrainingManager.stop());
                ctx.waitTicks(30);
                float rate = sp.getServer().computeOnServer(server -> server.getTickManager().getTickRate());
                log("Tutorial " + id + ": tick rate after stop = " + rate);
                return;
            }
            shots++;
        }
        log("Tutorial " + id + " never reached your turn");
        ctx.runOnClient(c -> TrainingManager.stop());
        ctx.waitTicks(30);
    }

    /**
     * Copies the player's own "Practice Trainer" world from the Xsoz instance into the test's run
     * directory. The original is only read - every change the test makes lands in the copy.
     */
    private void copyPracticeWorld(Path dest) {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || Files.isDirectory(dest)) return;
        Path src = Path.of(local, "XsozClient", "instances", "xsoz-1.21.11", ".minecraft", "saves", "Practice Trainer");
        if (!Files.isDirectory(src)) return;
        try (var walk = Files.walk(src)) {
            for (Path f : (Iterable<Path>) walk::iterator) {
                Path rel = src.relativize(f);
                if (rel.toString().equals("session.lock") || rel.toString().startsWith("xsoz-training-backup")) continue;
                Path to = dest.resolve(rel.toString());
                if (Files.isDirectory(f)) Files.createDirectories(to);
                else Files.copy(f, to);
            }
            log("Copied the Practice Trainer world from " + src);
        } catch (IOException ex) {
            log("Could not copy the Practice Trainer world: " + ex);
        }
    }

    private void start(ClientGameTestContext ctx, DrillDef def, int level, double p) {
        TrainingGate.Result r = ctx.computeOnClient(c -> TrainingManager.start(def, Drill.Mode.FIXED, p, true, false, level));
        if (!r.ok()) throw new AssertionError("Could not start " + def.title + ": " + r.message() + " " + r.detail());
        ctx.waitFor(c -> TrainingManager.active() != null && TrainingManager.active().live(), 600);
    }

    private void stop(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> TrainingManager.stop());
        ctx.waitFor(c -> !TrainingManager.running(), 200);
        ctx.waitTicks(10);
    }

    private void runDrill(ClientGameTestContext ctx, TestSingleplayerContext sp, DrillDef def, int level, int settleTicks, String name) {
        start(ctx, def, level, 0.2);
        ctx.waitTicks(settleTicks);
        Drill d = TrainingManager.active();
        ctx.takeScreenshot("xsoz-10-" + name);
        if (d instanceof MarkerDrill m && def == DrillDef.OBBY_CRYSTAL && level == 1) {
            // do the rep on the server, like the player would, and check the arena is cleaned after it
            BlockPos t = sp.getServer().computeOnServer(server -> m.markerForTests());
            if (t != null) {
                sp.getServer().runOnServer(server -> {
                    var w = server.getOverworld();
                    w.setBlockState(t, Blocks.OBSIDIAN.getDefaultState());
                    w.setBlockState(t.add(1, 0, 1), Blocks.OBSIDIAN.getDefaultState()); // a stray block
                    EndCrystalEntity c = new EndCrystalEntity(w, t.getX() + 0.5, t.getY() + 1.0, t.getZ() + 0.5);
                    w.spawnEntity(c);
                });
                ctx.waitTicks(4);
                ctx.takeScreenshot("xsoz-11-obby-done");
                boolean clean = sp.getServer().computeOnServer(server -> server.getOverworld().getBlockState(t).isAir()
                        && server.getOverworld().getBlockState(t.add(1, 0, 1)).isAir());
                log("Obsidian + Crystal: rep scored = " + (d.reps.size() > 0 && d.reps.get(0).hit()) + ", arena cleaned = " + clean);
                if (!clean || d.reps.isEmpty()) throw new AssertionError("Obsidian + Crystal did not score and clean up in the real game");
            } else {
                log("Obsidian + Crystal: no marker yet after " + settleTicks + " ticks");
            }
        }
        log(String.format(Locale.ROOT, "%s L%d: ran %d ticks, %d reps, status '%s'", def.title, level, d == null ? -1 : d.age(),
                d == null ? -1 : d.reps.size(), d == null ? "" : d.status));
        stop(ctx);
    }

    private void freeRoam(ClientGameTestContext ctx, TestSingleplayerContext sp, dev.xsoz.client.training.bot.FreeRoamConfig.Fight fight,
                          dev.xsoz.client.training.bot.FreeRoamConfig.Terrain map, dev.xsoz.client.training.bot.FreeRoamConfig.Teams teams,
                          int bots, int allies, String name) {
        var cfg = new dev.xsoz.client.training.bot.FreeRoamConfig();
        cfg.abilities = new java.util.ArrayList<>(fight.abilities());
        cfg.terrain = map;
        cfg.teams = teams;
        cfg.bots = bots;
        cfg.allies = allies;
        cfg.level = 4;
        cfg.size = dev.xsoz.client.training.bot.FreeRoamConfig.Size.MEDIUM;
        ctx.runOnClient(c -> dev.xsoz.client.training.drill.FreeRoamDrill.configure(cfg));
        start(ctx, DrillDef.FREE_ROAM, 4, 0.5);
        var d = (dev.xsoz.client.training.drill.FreeRoamDrill) TrainingManager.active();
        ctx.waitTicks(100);
        ctx.takeScreenshot("xsoz-30-freeroam-" + name + "-5s");
        ctx.waitTicks(300);
        ctx.takeScreenshot("xsoz-31-freeroam-" + name + "-20s");
        int actions = 0;
        StringBuilder dbg = new StringBuilder();
        for (var b : d.botsForTests()) {
            actions += b.crystalsPlaced + b.meleeHits + b.anchorsBlown + b.maceSmashes + b.pearlsThrown + b.blocksMined;
            dbg.append(" | ").append(b.debug());
        }
        log(String.format(Locale.ROOT, "Free Roam %s: %s; bot actions %d%s", name, d.summaryLine(), actions, dbg));
        stop(ctx);
        if (actions == 0) throw new AssertionError("Free Roam " + name + ": the bots did nothing in 20 s");
    }

    /** A real death in the middle of a drill: the drill ends and your own inventory comes back on respawn. */
    private void realDeath(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String before = sp.getServer().computeOnServer(server -> inventoryOf(server));
        start(ctx, DrillDef.CRYSTAL_SPOT, 1, 0.2);
        ctx.waitTicks(20);
        sp.getServer().runCommand("kill @a");
        ctx.waitFor(c -> c.player == null || c.player.isDead() || c.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen, 200);
        ctx.waitTicks(20);
        ctx.takeScreenshot("xsoz-40-real-death");
        ctx.runOnClient(c -> {
            if (c.player != null) c.player.requestRespawn();
        });
        ctx.waitFor(c -> c.player != null && c.player.isAlive() && c.currentScreen == null || c.player != null && c.player.isAlive(), 200);
        ctx.waitTicks(20);
        ctx.setScreen(() -> null);
        ctx.waitTicks(5);
        ctx.takeScreenshot("xsoz-41-after-respawn");
        String after = sp.getServer().computeOnServer(server -> inventoryOf(server));
        boolean running = ctx.computeOnClient(c -> TrainingManager.running());
        log("Real death: drill still running = " + running + ", inventory back = " + before.equals(after));
        if (running) throw new AssertionError("The drill kept running after a real death");
        if (!before.equals(after)) throw new AssertionError("Inventory not restored after a real death: before " + before + " after " + after);
    }

    private static String inventoryOf(net.minecraft.server.MinecraftServer server) {
        List<ServerPlayerEntity> ps = server.getPlayerManager().getPlayerList();
        if (ps.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        var inv = ps.get(0).getInventory();
        for (int i = 0; i < inv.size(); i++) if (!inv.getStack(i).isEmpty()) b.append(i).append(':').append(inv.getStack(i).getItem()).append('x').append(inv.getStack(i).getCount()).append(' ');
        return b.toString();
    }

    private void sparring(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        start(ctx, DrillDef.SPARRING, 2, 0.6);
        Drill d = TrainingManager.active();
        SparringDrill bot = (SparringDrill) d;
        ctx.waitFor(c -> bot.fightingForTests(), 200);
        float hp0 = sp.getServer().computeOnServer(server -> playerHealth(server));
        ctx.waitTicks(100);
        log("Sparring 5 s: " + bot.debugForTests());
        ctx.takeScreenshot("xsoz-20-sparring-5s");
        ctx.waitTicks(300);
        log("Sparring 20 s: " + bot.debugForTests());
        ctx.takeScreenshot("xsoz-21-sparring-20s");
        ctx.waitTicks(400);
        ctx.takeScreenshot("xsoz-22-sparring-40s");
        float hp1 = sp.getServer().computeOnServer(server -> playerHealth(server));
        int pops = bot.playerPopsForTests();
        log(String.format(Locale.ROOT, "Sparring L2: difficulty %s, lowest player health %.1f", sp.getServer().computeOnServer(sv -> sv.getOverworld().getDifficulty().asString()), bot.lowestPlayerHpForTests()));
        log(String.format(Locale.ROOT, "Sparring L2: player health %.1f -> %.1f, player pops %d, bot crystals %d, rounds %d, bot hp %.1f / totems %d - %s",
                hp0, hp1, pops, bot.crystalsBrokenForTests(), d.reps.size(), bot.botHealthForTests(), bot.botTotemsForTests(), bot.debugForTests()));
        boolean hurt = bot.lowestPlayerHpForTests() < hp0 - 0.5f || pops > 0 || !d.reps.isEmpty();
        stop(ctx);
        if (!hurt) throw new AssertionError("The sparring bot did not hurt a player standing still for 20 s");
    }

    private static float playerHealth(net.minecraft.server.MinecraftServer server) {
        List<ServerPlayerEntity> ps = server.getPlayerManager().getPlayerList();
        return ps.isEmpty() ? -1f : ps.get(0).getHealth() + ps.get(0).getAbsorptionAmount();
    }

    private void writeReport(ClientGameTestContext ctx) {
        try {
            Path run = ctx.computeOnClient(c -> c.runDirectory.toPath());
            Files.write(run.resolve("xsoz-client-test-report.txt"), report);
        } catch (IOException ex) {
            System.out.println("[XsozClientTest] report not written: " + ex);
        }
    }
}
