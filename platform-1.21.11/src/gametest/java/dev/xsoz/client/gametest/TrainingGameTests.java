package dev.xsoz.client.gametest;

import com.mojang.authlib.GameProfile;
import dev.xsoz.client.training.CombatMath;
import dev.xsoz.client.training.Kit;
import dev.xsoz.client.training.KitSpec;
import dev.xsoz.client.training.TrainingHooks;
import dev.xsoz.client.training.drill.AnchorChainDrill;
import dev.xsoz.client.training.drill.CrystalCycleDrill;
import dev.xsoz.client.training.drill.CrystalSpotDrill;
import dev.xsoz.client.training.drill.Drill;
import dev.xsoz.client.training.drill.HoleBreakerDrill;
import dev.xsoz.client.training.drill.KeybindReflexDrill;
import dev.xsoz.client.training.drill.LayoutRecallDrill;
import dev.xsoz.client.training.drill.ObbyCrystalDrill;
import dev.xsoz.client.training.drill.PearlAimDrill;
import dev.xsoz.client.training.drill.RangeControlDrill;
import dev.xsoz.client.training.drill.RefillDrill;
import dev.xsoz.client.training.drill.RetotemDrill;
import dev.xsoz.client.training.drill.SafeGappleDrill;
import dev.xsoz.client.training.drill.Scene;
import dev.xsoz.client.training.drill.ShieldReadDrill;
import dev.xsoz.client.training.drill.SparringDrill;
import dev.xsoz.client.training.session.Session;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * Runs the training drills on a real headless server with a FakePlayer. Each test builds its arena
 * high above the test area (its own height band, so tests never overlap) and drives the drill tick
 * by tick on the server thread, then checks scoring and that the world was put back exactly.
 */
public final class TrainingGameTests {

    /** Runs once per server tick while set (tests that need the world to tick between drill ticks). */
    private static final java.util.List<Runnable> TICKERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static {
        // the death / damage hooks the real game registers from the client entrypoint
        TrainingHooks.register();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Runnable r : TICKERS) r.run();
        });
    }

    private static int band;

    private static synchronized BlockPos high(TestContext ctx) {
        band++;
        // one height band per test, 16 apart: arenas reach at most 6 below and 10 above their base
        // 15 height bands, reused after 15 tests: test structures sit ~26 blocks apart in a row, so a
        // test 15 places later is hundreds of blocks away. (Band 15 is kept for ticking().)
        return ctx.getAbsolutePos(BlockPos.ORIGIN).up(20 + (band % 15) * 16);
    }

    /** A band right above the test structure, where entities tick (the shield's block delay needs it). */
    private static BlockPos ticking(TestContext ctx) {
        return ctx.getAbsolutePos(BlockPos.ORIGIN).up(20 + 15 * 16);
    }

    private static FakePlayer player(TestContext ctx, BlockPos at) {
        FakePlayer p = new VulnerableFakePlayer(ctx.getWorld(), new GameProfile(UUID.randomUUID(), "xsoz_test_" + band));
        p.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        p.changeGameMode(GameMode.SURVIVAL);
        p.getInventory().setStack(0, new ItemStack(Items.DIAMOND, 3));
        return p;
    }

    private static Map<BlockPos, BlockState> snapshot(ServerWorld w, BlockPos center) {
        Map<BlockPos, BlockState> m = new HashMap<>();
        for (BlockPos p : BlockPos.iterate(center.add(-14, -4, -14), center.add(14, 9, 14))) m.put(p.toImmutable(), w.getBlockState(p));
        return m;
    }

    private static void check(TestContext ctx, boolean ok, String what) {
        if (!ok) ctx.throwGameTestException(what);
    }

    private static void assertRestored(TestContext ctx, Map<BlockPos, BlockState> before, FakePlayer p) {
        ServerWorld w = ctx.getWorld();
        int changed = 0;
        BlockPos first = null;
        for (var e : before.entrySet()) {
            if (!w.getBlockState(e.getKey()).equals(e.getValue())) {
                changed++;
                if (first == null) first = e.getKey();
            }
        }
        check(ctx, changed == 0, changed + " block(s) not restored, first at " + first);
        BlockPos c = first == null ? before.keySet().iterator().next() : first;
        int dummies = w.getEntitiesByClass(MannequinEntity.class, new Box(c).expand(40, 6, 40), m -> true).size();
        check(ctx, dummies == 0, dummies + " training dummies left behind");
        check(ctx, p.getInventory().getStack(0).isOf(Items.DIAMOND) && p.getInventory().getStack(0).getCount() == 3,
                "the player's own inventory was not restored");
    }

    private static Session session(TestContext ctx, FakePlayer p) {
        return new Session(ctx.getWorld().getServer(), p, true);
    }

    private static void run(Drill d, int ticks) {
        for (int i = 0; i < ticks && !d.finished; i++) d.serverTick();
    }

    private static EndCrystalEntity crystalOn(ServerWorld w, BlockPos b) {
        EndCrystalEntity c = new EndCrystalEntity(w, b.getX() + 0.5, b.getY() + 1.0, b.getZ() + 0.5);
        w.spawnEntity(c);
        return c;
    }

    // ------------------------------------------------------------------ the damage model

    @GameTest(maxTicks = 40)
    public void stoneCoverShieldsOnceObsidianKeepsShielding(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        Map<BlockPos, BlockState> before = snapshot(w, base);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            s.setBlock(base.add(x, -1, z), Blocks.STONE.getDefaultState());
            for (int y = 0; y <= 3; y++) s.setBlock(base.add(x, y, z), Blocks.AIR.getDefaultState());
        }
        Kit.current().fill(p);
        Scene.Armour a = Scene.armourOf(w, p);
        check(ctx, a.armor() >= 19f && a.blastEpf() >= 16f, "kit armour not read: " + a);
        Vec3d blast = Vec3d.ofBottomCenter(base.add(0, 0, 3)); // a crystal 3 blocks in front, on the floor
        float open = Scene.damage(blast, CombatMath.CRYSTAL_POWER, p, a);
        check(ctx, open > 3f, "an open crystal at 3 blocks did only " + open);
        // a full stone wall in between: this blast is blocked, the next one is not
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 2; y++) s.setBlock(base.add(x, y, 2), Blocks.STONE.getDefaultState());
        float covered = Scene.damage(blast, CombatMath.CRYSTAL_POWER, p, a);
        float next = Scene.damageAfterWeakCoverBreaks(w, blast, CombatMath.CRYSTAL_POWER, p, a);
        check(ctx, covered < 0.5f, "stone cover let through " + covered);
        check(ctx, next > open * 0.8f, "after the stone breaks the next blast should hit fully, got " + next + " vs " + open);
        // the same wall in obsidian survives
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 2; y++) s.setBlock(base.add(x, y, 2), Blocks.OBSIDIAN.getDefaultState());
        float obby = Scene.damageAfterWeakCoverBreaks(w, blast, CombatMath.CRYSTAL_POWER, p, a);
        check(ctx, obby < 0.5f, "obsidian cover should survive and keep shielding, next blast did " + obby);
        // hypothetical-position damage matches the entity's own
        float at = Scene.damageAt(w, blast, CombatMath.CRYSTAL_POWER, new Vec3d(p.getX(), p.getY(), p.getZ()), p, a);
        check(ctx, Math.abs(at - Scene.damage(blast, CombatMath.CRYSTAL_POWER, p, a)) < 0.01f, "damageAt disagrees with damage");
        s.restore();
        assertRestored(ctx, before, p);
        ctx.complete();
    }

    // ------------------------------------------------------------------ kits

    @GameTest(maxTicks = 40)
    public void customKitKeepsItemsEnchantmentsAndRefillsTotems(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        KitSpec spec = new KitSpec();
        spec.put(new KitSpec.Entry(4, "minecraft:diamond_sword", 1).ench("minecraft:sharpness", 5));
        KitSpec.Entry pot = new KitSpec.Entry(5, "minecraft:splash_potion", 1);
        pot.potion = "minecraft:strong_strength";
        spec.put(pot);
        spec.put(new KitSpec.Entry(KitSpec.HEAD, "minecraft:diamond_helmet", 1).ench("minecraft:protection", 4));
        spec.put(new KitSpec.Entry(10, "minecraft:totem_of_undying", 1));
        spec.put(new KitSpec.Entry(11, "minecraft:totem_of_undying", 1));
        spec.put(new KitSpec.Entry(12, "minecraft:totem_of_undying", 1));
        Kit kit = Kit.of(spec);
        CrystalCycleDrill d = new CrystalCycleDrill(Drill.Mode.FIXED, 0.0, false);
        d.start(s, kit);
        d.skipCountdownForTests();
        var sharp = ctx.getWorld().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT)
                .getOptional(net.minecraft.registry.RegistryKey.of(RegistryKeys.ENCHANTMENT, net.minecraft.util.Identifier.ofVanilla("sharpness"))).orElseThrow();
        ItemStack sword = p.getInventory().getStack(4);
        check(ctx, sword.isOf(Items.DIAMOND_SWORD) && sword.getEnchantments().getLevel(sharp) == 5, "custom sword not given with Sharpness V: " + sword);
        check(ctx, kit.slot(Kit.Role.SWORD) == 4, "the diamond sword should fill the sword role");
        check(ctx, p.getInventory().getStack(5).contains(DataComponentTypes.POTION_CONTENTS), "potion contents missing");
        check(ctx, p.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).isOf(Items.DIAMOND_HELMET), "helmet not worn");
        // roles the custom kit lacks are added so every drill still works
        check(ctx, p.getInventory().getStack(kit.slot(Kit.Role.CRYSTAL)).isOf(Items.END_CRYSTAL), "missing crystals were not added");
        // only 1-2 totems left -> refilled
        p.getInventory().setStack(10, ItemStack.EMPTY);
        p.getInventory().setStack(11, ItemStack.EMPTY);
        check(ctx, Kit.totems(p) <= 2, "setup: expected 2 or fewer totems, have " + Kit.totems(p));
        run(d, 6);
        check(ctx, Kit.totems(p) >= 3, "totems were not refilled at 2 left: " + Kit.totems(p));
        s.restore();
        check(ctx, p.getInventory().getStack(0).isOf(Items.DIAMOND), "inventory not restored");
        ctx.complete();
    }

    // ------------------------------------------------------------------ Fight IQ

    @GameTest(maxTicks = 40)
    public void bestCrystalScenesAreSoundAndTheBestSpotScoresFull(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        Map<BlockPos, BlockState> before = snapshot(w, base);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        CrystalSpotDrill d = new CrystalSpotDrill(Drill.Mode.FIXED, 0.0, false);
        d.level(2);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        // many scenes: the dummy and the player must always stand in free space, never in a wall
        for (int scene = 0; scene < 12; scene++) {
            MannequinEntity dummy = d.dummyForTests();
            BlockPos df = dummy.getBlockPos();
            check(ctx, Scene.standable(w, df), "scene " + scene + ": dummy is inside a block or floating at " + df);
            check(ctx, Scene.standable(w, p.getBlockPos()), "scene " + scene + ": player is inside a block at " + p.getBlockPos());
            CrystalSpotDrill.Eval best = d.bestForTests();
            check(ctx, best != null && best.net() > 0 && best.target() > best.self(), "scene " + scene + ": no sound best spot " + best);
            check(ctx, Scene.reachTo(p.getEyePos(), best.block()) <= 4.5, "scene " + scene + ": best spot out of reach");
            // time out to get the next scene
            run(d, (int) Math.round(d.speed() * 20) + 60);
        }
        CrystalSpotDrill.Eval best = d.bestForTests();
        if (best.build()) w.setBlockState(best.block(), Blocks.OBSIDIAN.getDefaultState());
        int before2 = d.reps.size();
        EndCrystalEntity c = crystalOn(w, best.block());
        d.onCrystalSpawn(c);
        d.onCrystalBreak(c, p);
        check(ctx, d.reps.size() == before2 + 1, "placing and breaking did not score a rep");
        check(ctx, d.reps.get(d.reps.size() - 1).value() >= 0.99, "the best spot scored " + d.reps.get(d.reps.size() - 1).value() + " of best");
        s.restore();
        assertRestored(ctx, before, p);
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void bestCrystalCallsOutAWeakPlacement(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        CrystalSpotDrill d = new CrystalSpotDrill(Drill.Mode.FIXED, 0.0, false);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        // the worst spot the player could use
        CrystalSpotDrill.Eval worst = null;
        for (CrystalSpotDrill.Eval e : d.evaluateForTests()) if (worst == null || e.net() < worst.net()) worst = e;
        check(ctx, worst != null, "no candidates at all");
        if (worst.build()) w.setBlockState(worst.block(), Blocks.OBSIDIAN.getDefaultState());
        EndCrystalEntity c = crystalOn(w, worst.block());
        d.onCrystalSpawn(c);
        d.onCrystalBreak(c, p);
        Drill.Rep r = d.reps.get(0);
        check(ctx, !r.hit() && r.note().startsWith("Placement"), "a weak spot was not called out as placement: " + r);
        s.restore();
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void holeBreakerNeedsABuiltSpot(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        Map<BlockPos, BlockState> before = snapshot(w, base);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        HoleBreakerDrill d = new HoleBreakerDrill(Drill.Mode.FIXED, 0.0, false);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        CrystalSpotDrill.Eval best = d.bestForTests();
        check(ctx, best != null && best.build() && best.target() >= 3.0f, "no buildable spot worth teaching: " + best);
        w.setBlockState(best.block(), Blocks.OBSIDIAN.getDefaultState());
        EndCrystalEntity c = crystalOn(w, best.block());
        d.onCrystalSpawn(c);
        d.onCrystalBreak(c, p);
        check(ctx, d.reps.size() == 1 && d.reps.get(0).value() >= 0.85, "built spot scored " + (d.reps.isEmpty() ? -1 : d.reps.get(0).value()));
        s.restore();
        assertRestored(ctx, before, p);
        ctx.complete();
    }

    @GameTest(maxTicks = 80)
    public void shieldReadDummyReallyBlocks(TestContext ctx) {
        BlockPos base = ticking(ctx);
        ServerWorld w = ctx.getWorld();
        Map<BlockPos, BlockState> before = snapshot(w, base);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        ShieldReadDrill d = new ShieldReadDrill(Drill.Mode.FIXED, 0.0, false);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        run(d, 2); // raises the shield
        MannequinEntity dummy = d.dummyForTests();
        check(ctx, dummy.isUsingItem() && dummy.getActiveItem().isOf(Items.SHIELD), "the dummy is not holding its shield up");
        // the block delay runs on the world's own ticks
        ctx.waitAndRun(12, () -> {
            run(d, 1);
            check(ctx, dummy.isBlocking(), "the dummy's raised shield does not block");
            BlockPos best = d.bestSpotForTests();
            check(ctx, best != null && !d.bestSpotBlockedForTests(), "no unblocked anchor spot");
            Vec3d mid = dummy.getEntityPos().add(p.getEntityPos()).multiply(0.5);
            BlockPos front = BlockPos.ofFloored(mid.x, dummy.getY(), mid.z);
            w.setBlockState(front, Blocks.RESPAWN_ANCHOR.getDefaultState());
            d.onAnchorExplode(front);
            check(ctx, d.reps.size() == 1 && !d.reps.get(0).hit() && d.reps.get(0).note().contains("blocked"), "front anchor was not blocked: " + d.reps);
            run(d, 45);
            BlockPos best2 = d.bestSpotForTests();
            w.setBlockState(best2, Blocks.RESPAWN_ANCHOR.getDefaultState());
            d.onAnchorExplode(best2);
            check(ctx, d.reps.size() == 2 && d.reps.get(1).value() >= 0.99, "best anchor scored " + d.reps.get(1).value() + " " + d.reps.get(1).note());
            s.restore();
            assertRestored(ctx, before, p);
            ctx.complete();
        });
    }

    // ------------------------------------------------------------------ Foundations and core

    @GameTest(maxTicks = 40)
    public void keybindReflexScoresTheGreenSlot(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        KeybindReflexDrill d = new KeybindReflexDrill(Drill.Mode.FIXED, 0.0, false);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        for (int i = 0; i < 9; i++) check(ctx, p.getInventory().getStack(i).isOf(Items.RED_WOOL), "slot " + i + " is not red wool");
        int guard = 0;
        while (d.greenSlot < 0 && guard++ < 200) d.serverTick();
        int g = d.greenSlot;
        check(ctx, g >= 0, "no slot turned green in 10 s");
        check(ctx, p.getInventory().getStack(g).isOf(Items.LIME_WOOL), "green slot is not lime wool");
        p.getInventory().setSelectedSlot(g);
        d.serverTick();
        check(ctx, d.reps.size() == 1 && d.reps.get(0).hit(), "pressing the green slot was not a hit");
        check(ctx, p.getInventory().getStack(g).isOf(Items.RED_WOOL), "slot did not turn back red");
        s.restore();
        check(ctx, p.getInventory().getStack(0).isOf(Items.DIAMOND), "inventory not restored");
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void keybindReflexDecoysAreMisses(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        KeybindReflexDrill d = new KeybindReflexDrill(Drill.Mode.FIXED, 0.0, false);
        d.level(2);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        int guard = 0;
        while (d.greenSlot < 0 && guard++ < 200) d.serverTick();
        int decoy = -1;
        for (int i = 0; i < 9; i++) if (p.getInventory().getStack(i).isOf(Items.YELLOW_WOOL)) decoy = i;
        check(ctx, decoy >= 0, "level 2 showed no yellow decoy");
        p.getInventory().setSelectedSlot(decoy);
        d.serverTick();
        check(ctx, d.reps.size() == 1 && !d.reps.get(0).hit() && d.reps.get(0).note().contains("Decoy"), "pressing a decoy was not a miss: " + d.reps);
        s.restore();
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void layoutRecallScoresTheNamedItem(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        LayoutRecallDrill d = new LayoutRecallDrill(Drill.Mode.FIXED, 0.0, false);
        Kit kit = Kit.current();
        d.start(s, kit);
        d.skipCountdownForTests();
        int guard = 0;
        while (d.shownCue == null && guard++ < 200) d.serverTick();
        check(ctx, d.shownCue != null, "no cue shown");
        p.getInventory().setSelectedSlot(kit.slot(d.shownCue));
        d.serverTick();
        check(ctx, d.reps.size() == 1 && d.reps.get(0).hit(), "selecting the named item was not a hit");
        s.restore();
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void crystalCycleTimesPlaceToBreak(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        Map<BlockPos, BlockState> before = snapshot(w, base);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        CrystalCycleDrill d = new CrystalCycleDrill(Drill.Mode.FIXED, 0.0, false);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        EndCrystalEntity c = new EndCrystalEntity(w, base.getX() + 0.5, base.getY(), base.getZ() + 3.5);
        w.spawnEntity(c);
        d.onCrystalSpawn(c);
        run(d, 3);
        d.onCrystalBreak(c, p);
        check(ctx, d.reps.size() == 1 && d.reps.get(0).hit() && d.reps.get(0).value() == 3, "cycle was " + d.reps);
        s.restore();
        assertRestored(ctx, before, p);
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void retotemPopsAndTimesTheSwap(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        RetotemDrill d = new RetotemDrill(Drill.Mode.FIXED, 0.0, false);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        check(ctx, p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING), "no totem in offhand");
        int guard = 0;
        while (p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) && guard++ < 220) d.serverTick();
        check(ctx, !p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING), "the drill never popped a totem");
        check(ctx, p.isAlive() && p.getHealth() > 0, "the pop killed the player");
        run(d, 2);
        p.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        d.serverTick();
        check(ctx, d.reps.size() == 1 && d.reps.get(0).hit(), "a 3-tick retotem was not a hit: " + d.reps);
        s.restore();
        check(ctx, p.getInventory().getStack(0).isOf(Items.DIAMOND), "inventory not restored");
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void refillDetectsTheRefilledSlot(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        RefillDrill d = new RefillDrill(Drill.Mode.FIXED, 0.0, false);
        Kit kit = Kit.current();
        d.start(s, kit);
        d.skipCountdownForTests();
        int guard = 0;
        while (d.shownMissing.isEmpty() && guard++ < 200) d.serverTick();
        check(ctx, d.shownMissing.size() == 1, "level 1 should empty exactly one slot: " + d.shownMissing);
        Kit.Role r = d.shownMissing.get(0);
        int hot = kit.slot(r);
        check(ctx, p.getInventory().getStack(hot).isEmpty(), "the slot is not empty");
        int found = -1;
        for (int i = 9; i < 36; i++) if (r.matches(p.getInventory().getStack(i)) && i != hot) found = i;
        check(ctx, found >= 9, "the item was not moved into the inventory");
        p.getInventory().setStack(hot, p.getInventory().getStack(found));
        p.getInventory().setStack(found, ItemStack.EMPTY);
        d.serverTick();
        check(ctx, d.reps.size() == 1 && d.reps.get(0).hit(), "refill not scored: " + d.reps);
        s.restore();
        ctx.complete();
    }

    // ------------------------------------------------------------------ combinations

    @GameTest(maxTicks = 40)
    public void obsidianCrystalShowsALimeBlockAndCleansUp(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        Map<BlockPos, BlockState> before = snapshot(w, base);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        ObbyCrystalDrill d = new ObbyCrystalDrill(Drill.Mode.FIXED, 0.0, false);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        int guard = 0;
        while (d.markerForTests() == null && guard++ < 100) d.serverTick();
        BlockPos m = d.markerForTests();
        check(ctx, m != null, "no marker");
        check(ctx, w.getBlockState(m.down()).isOf(Blocks.LIME_CONCRETE), "the spot is not shown as a lime block");
        // a stray block the player placed somewhere else in the arena
        BlockPos stray = base.add(-3, 0, 2);
        w.setBlockState(stray, Blocks.OBSIDIAN.getDefaultState());
        w.setBlockState(m, Blocks.OBSIDIAN.getDefaultState());
        EndCrystalEntity c = crystalOn(w, m);
        d.onCrystalSpawn(c);
        d.serverTick();
        check(ctx, d.reps.size() == 1 && d.reps.get(0).hit(), "obsidian + crystal on the marker not scored");
        check(ctx, w.getBlockState(m).isAir() && w.getBlockState(stray).isAir(), "placed obsidian was not cleared for the next spot");
        check(ctx, w.getBlockState(m.down()).isOf(Blocks.STONE), "the lime block was not reset to stone");
        check(ctx, c.isRemoved(), "the crystal was not removed for the next spot");
        s.restore();
        assertRestored(ctx, before, p);
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void anchorChainRatesSelfDamage(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        Map<BlockPos, BlockState> before = snapshot(w, base);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        AnchorChainDrill d = new AnchorChainDrill(Drill.Mode.FIXED, 0.0, false);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        int guard = 0;
        while (d.markerForTests() == null && guard++ < 100) d.serverTick();
        BlockPos m = d.markerForTests();
        check(ctx, m != null && w.getBlockState(m.down()).isOf(Blocks.LIME_CONCRETE), "no lime marker");
        // stand right next to it in the open: that must be "too close"
        Scene.teleport(p, Vec3d.ofBottomCenter(m.offset(Direction.NORTH)), 0f, 0f);
        float expected = Scene.damage(Vec3d.ofCenter(m), CombatMath.ANCHOR_POWER, p, Scene.armourOf(w, p));
        w.setBlockState(m, Blocks.RESPAWN_ANCHOR.getDefaultState());
        d.onAnchorExplode(m);
        d.serverTick();
        check(ctx, d.reps.size() == 1, "detonation not scored");
        String note = d.reps.get(0).note();
        check(ctx, CombatMath.anchorSelfRating(expected) == 2 && note.contains("Too close") && !d.reps.get(0).hit(),
                "an anchor right next to you (" + expected + " HP) should be too close and a miss: " + note);
        // next spot: box the anchor in - a block between you and it = just right
        guard = 0;
        while (d.markerForTests() == null && guard++ < 100) d.serverTick();
        BlockPos m2 = d.markerForTests();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (x != 0 || z != 0) w.setBlockState(m2.add(x, 0, z), Blocks.OBSIDIAN.getDefaultState());
            w.setBlockState(m2.add(x, 1, z), Blocks.OBSIDIAN.getDefaultState());
        }
        w.setBlockState(m2, Blocks.RESPAWN_ANCHOR.getDefaultState());
        d.onAnchorExplode(m2);
        d.serverTick();
        check(ctx, d.reps.size() == 2 && d.reps.get(1).hit() && d.reps.get(1).note().contains("Just right"), "covered anchor: " + d.reps.get(1));
        s.restore();
        assertRestored(ctx, before, p);
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void gappleTimingChasesAndJudgesEachGapple(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        SafeGappleDrill g = new SafeGappleDrill(Drill.Mode.FIXED, 0.0, false);
        g.start(s, Kit.current());
        g.skipCountdownForTests();
        TrainingHooks.bind(g, s);
        try {
            dev.xsoz.client.training.bot.PvpBot bot = g.botForTests();
            check(ctx, bot != null && bot.alive(), "no chaser");
            double d0 = bot.pos().distanceTo(new Vec3d(p.getX(), p.getY(), p.getZ()));
            run(g, 80);
            double d1 = bot.pos().distanceTo(new Vec3d(p.getX(), p.getY(), p.getZ()));
            check(ctx, d1 < d0 - 1 || d1 < 3.5, "the chaser did not chase: " + d0 + " -> " + d1 + " " + bot.debug());
            // eating with it close is judged as caught
            int slot = -1;
            for (int i = 0; i < 36; i++) if (p.getInventory().getStack(i).isOf(Items.GOLDEN_APPLE)) slot = i;
            check(ctx, slot >= 0, "no gapples in the kit");
            p.getInventory().getStack(slot).decrement(1);
            g.serverTick();
            check(ctx, g.reps.size() == 1 && !g.reps.get(0).hit(), "eating next to the chaser was not called out: " + g.reps);
        } finally {
            TrainingHooks.bind(null, null);
            s.restore();
        }
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void pearlAimFreezesThePlayerAndUsesDistanceBands(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        PearlAimDrill d = new PearlAimDrill(Drill.Mode.FIXED, 0.0, false);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        check(ctx, w.getBlockState(base.down()).isOf(Blocks.STONE), "no flat stone under the player");
        run(d, 2);
        BlockPos pad = d.padForTests();
        check(ctx, pad != null && w.getBlockState(pad).isOf(Blocks.GOLD_BLOCK), "no pad");
        PearlAimDrill.Band band = d.bandForTests();
        double dist = Math.sqrt(Math.pow(pad.getX() - base.getX(), 2) + Math.pow(pad.getZ() - base.getZ(), 2));
        check(ctx, dist >= band.min - 1.5 && dist <= band.max + 1.5, "pad at " + dist + " is outside its band " + band);
        // walking is undone at once
        p.refreshPositionAndAngles(p.getX() + 3, p.getY(), p.getZ(), p.getYaw(), p.getPitch());
        d.serverTick();
        check(ctx, p.squaredDistanceTo(Vec3d.ofBottomCenter(base)) < 0.01, "the player could move away");
        // no throw: the pad closes after the band's window
        int window = (int) Math.ceil(d.speed() * band.timeFactor * 20) + 3;
        run(d, window);
        check(ctx, d.reps.size() == 1 && !d.reps.get(0).hit() && d.reps.get(0).note().contains("closed"), "pad did not close: " + d.reps);
        check(ctx, d.goalMode() && d.targetReps() == 70, "practice should be a race to 70 hits");
        s.restore();
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void rangeControlRunsCleanlyAtLevelThree(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        FakePlayer p = player(ctx, base);
        Session s2 = session(ctx, p);
        RangeControlDrill r = new RangeControlDrill(Drill.Mode.FIXED, 1.0, false);
        r.level(3);
        r.start(s2, Kit.current());
        r.skipCountdownForTests();
        run(r, 520);
        check(ctx, r.reps.size() == 1, "a 20 s range run did not score");
        check(ctx, r.reps.get(0).value() >= 0 && r.reps.get(0).value() <= 100, "range % out of bounds");
        s2.restore();
        int dummies = w.getEntitiesByClass(MannequinEntity.class, new Box(base).expand(40, 6, 40), m -> true).size();
        check(ctx, dummies == 0, "range dummy left behind");
        ctx.complete();
    }

    // ------------------------------------------------------------------ the sparring bot

    @GameTest(maxTicks = 40)
    public void sparringBotFightsForReal(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (BlockPos q : BlockPos.iterate(base.add(-17, -6, -17), base.add(17, 10, 17))) before.put(q.toImmutable(), w.getBlockState(q));
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        SparringDrill d = new SparringDrill(Drill.Mode.FIXED, 1.0, false);
        d.level(2);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        TrainingHooks.bind(d, s);
        try {
            run(d, 61);
            check(ctx, d.fightingForTests(), "the round never started");
            MannequinEntity bot = d.botForTests();
            check(ctx, bot != null && Scene.standable(w, bot.getBlockPos()), "the bot is not standing in the arena");
            // A GameTest FakePlayer is not in the world's entity list, so vanilla explosions can't reach
            // it. A real mannequin standing in the player's spot can be hit: it proves the blasts are real.
            MannequinEntity victim = net.minecraft.entity.EntityType.MANNEQUIN.create(w, net.minecraft.entity.SpawnReason.COMMAND);
            victim.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), 0f, 0f);
            w.spawnEntity(victim);
            int guard = 0;
            while (d.crystalsBrokenForTests() < 3 && guard++ < 1200) {
                p.setVelocity(Vec3d.ZERO);
                victim.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), 0f, 0f);
                d.serverTick();
            }
            check(ctx, d.crystalsBrokenForTests() >= 3, "the bot did not crystal the player in 60 s: " + d.debugForTests());
            check(ctx, victim.isRemoved() || victim.getHealth() < victim.getMaxHealth(), "the bot's crystals did no real damage where the player stands");
            victim.discard();
            // hit the bot with a real explosion next to it: its simulated health must drop
            for (int i = 0; i < 11; i++) d.serverTick();
            float botHp = d.botHealthForTests();
            int botTotems = d.botTotemsForTests();
            Vec3d at = bot.getEntityPos().add(0.6, 1.0, 0);
            w.createExplosion(p, at.x, at.y, at.z, 6f, net.minecraft.world.World.ExplosionSourceType.NONE);
            check(ctx, d.botHealthForTests() < botHp || d.botTotemsForTests() < botTotems || !d.fightingForTests(), "an explosion next to the bot did nothing to it");
            check(ctx, !bot.isRemoved(), "the bot entity died for real");
        } finally {
            TrainingHooks.bind(null, null);
            s.restore();
        }
        int changed = 0;
        for (var e : before.entrySet()) if (!w.getBlockState(e.getKey()).equals(e.getValue())) changed++;
        check(ctx, changed == 0, changed + " arena block(s) not restored after real explosions");
        ctx.complete();
    }

    // ------------------------------------------------------------------ bot brain

    @GameTest(maxTicks = 40)
    public void pathfinderGoesAroundWallsAndUpSteps(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
            s.setBlock(base.add(x, -1, z), Blocks.STONE.getDefaultState());
            for (int y = 0; y <= 4; y++) s.setBlock(base.add(x, y, z), Blocks.AIR.getDefaultState());
        }
        // a wall between start and goal, with a one-block step at its end
        for (int z = -4; z <= 4; z++) for (int y = 0; y <= 2; y++) s.setBlock(base.add(0, y, z), Blocks.OBSIDIAN.getDefaultState());
        s.setBlock(base.add(2, 0, 6), Blocks.STONE.getDefaultState());
        var path = dev.xsoz.client.training.bot.Pathfinder.find(w, base.add(-3, 0, 0), base.add(3, 0, 0), 2000, base, 8);
        check(ctx, path != null && !path.isEmpty() && path.get(path.size() - 1).equals(base.add(3, 0, 0)), "no path around the wall: " + path);
        for (BlockPos q : path) check(ctx, dev.xsoz.client.training.bot.Pathfinder.standable(w, q), "path goes through a block at " + q);
        var up = dev.xsoz.client.training.bot.Pathfinder.find(w, base.add(2, 0, 5), base.add(2, 1, 6), 500, base, 8);
        check(ctx, up != null && !up.isEmpty(), "can't step up one block");
        s.restore();
        ctx.complete();
    }

    /** A mace fight: bots of the given personality; runs the fight and watches every bot each tick. */
    private void maceFight(TestContext ctx, dev.xsoz.client.training.bot.Personality who, int bots, int ticks,
                           java.util.function.BiConsumer<Integer, java.util.List<dev.xsoz.client.training.bot.PvpBot>> each) {
        fight(ctx, dev.xsoz.client.training.bot.FreeRoamConfig.Fight.MACE, dev.xsoz.client.training.KitSpec.Mode.MACE, who, 4, bots, ticks, each);
    }

    /** A free roam fight (FFA, small arena, you standing still) that runs and watches every bot each tick. */
    private void fight(TestContext ctx, dev.xsoz.client.training.bot.FreeRoamConfig.Fight kind, dev.xsoz.client.training.KitSpec.Mode kit,
                       dev.xsoz.client.training.bot.Personality who, int level, int bots, int ticks,
                       java.util.function.BiConsumer<Integer, java.util.List<dev.xsoz.client.training.bot.PvpBot>> each) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        var cfg = new dev.xsoz.client.training.bot.FreeRoamConfig();
        cfg.bots = bots;
        cfg.teams = dev.xsoz.client.training.bot.FreeRoamConfig.Teams.FFA;
        cfg.abilities = new java.util.ArrayList<>(kind.abilities());
        cfg.size = dev.xsoz.client.training.bot.FreeRoamConfig.Size.SMALL;
        cfg.level = level;
        cfg.personalityMode = dev.xsoz.client.training.bot.FreeRoamConfig.PersonalityMode.SAME;
        cfg.samePersonality = who;
        dev.xsoz.client.training.drill.FreeRoamDrill.configure(cfg);
        dev.xsoz.client.training.drill.FreeRoamDrill d = new dev.xsoz.client.training.drill.FreeRoamDrill(Drill.Mode.FIXED, 0.5, false);
        d.start(s, Kit.standard(kit));
        d.skipCountdownForTests();
        TrainingHooks.bind(d, s);
        try {
            for (int i = 0; i < ticks; i++) {
                p.setVelocity(Vec3d.ZERO);
                d.serverTick();
                each.accept(i, d.botsForTests());
            }
        } finally {
            TrainingHooks.bind(null, null);
            s.restore();
        }
    }

    @GameTest(maxTicks = 60)
    public void maceBotsNeverSitOnTheGroundWithWingsOpen(TestContext ctx) {
        // two bots taking off at each other at once used to fall and lie there, wings open, looking up
        Map<Object, Integer> stuck = new HashMap<>();
        Map<Object, Vec3d> last = new HashMap<>();
        int[] worst = {0};
        String[] who = {""};
        for (var kind : new dev.xsoz.client.training.bot.Personality[] {dev.xsoz.client.training.bot.Personality.ALL_ROUNDER,
                dev.xsoz.client.training.bot.Personality.SKY_FIGHTER}) {
            maceFight(ctx, kind, 3, 500, (i, list) -> {
                for (var b : list) {
                    if (!b.alive()) continue;
                    Vec3d before = last.put(b, b.pos());
                    boolean still = before != null && before.squaredDistanceTo(b.pos()) < 0.0025;
                    int n = b.body().isGliding() && still ? stuck.getOrDefault(b, 0) + 1 : 0;
                    stuck.put(b, n);
                    if (n > worst[0]) {
                        worst[0] = n;
                        who[0] = b.debug();
                    }
                }
            });
        }
        check(ctx, worst[0] < 10, "a bot sat still with its wings open for " + worst[0] + " ticks: " + who[0]);
        ctx.complete();
    }

    @GameTest(maxTicks = 60)
    public void skyFighterStaysInTheAir(TestContext ctx) {
        int[] flying = {0};
        int[] counted = {0};
        String[] dbg = {""};
        maceFight(ctx, dev.xsoz.client.training.bot.Personality.SKY_FIGHTER, 1, 700, (i, list) -> {
            if (i < 60 || list.isEmpty() || !list.get(0).alive()) return;
            var b = list.get(0);
            counted[0]++;
            if (!b.onGround()) flying[0]++; // gliding, or dropping on you with the wings shut
            dbg[0] = b.debug();
        });
        check(ctx, counted[0] > 0, "the sky fighter died straight away: " + dbg[0]);
        check(ctx, flying[0] > counted[0] * 0.85, "a sky fighter was only in the air " + flying[0] + " of " + counted[0] + " ticks: " + dbg[0]);
        ctx.complete();
    }

    private static net.minecraft.entity.Entity cam(dev.xsoz.client.training.drill.FreeRoamDrill d, FakePlayer p) {
        return d.cameraForTests == null ? p : d.cameraForTests;
    }

    @GameTest(maxTicks = 40)
    public void watchModeArrowKeysSwitchBotsAndViews(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        var cfg = new dev.xsoz.client.training.bot.FreeRoamConfig();
        cfg.bots = 3;
        cfg.watch = true;
        cfg.teams = dev.xsoz.client.training.bot.FreeRoamConfig.Teams.FFA;
        cfg.abilities = new java.util.ArrayList<>(dev.xsoz.client.training.bot.FreeRoamConfig.Fight.SWORD.abilities());
        cfg.size = dev.xsoz.client.training.bot.FreeRoamConfig.Size.SMALL;
        cfg.level = 3;
        dev.xsoz.client.training.drill.FreeRoamDrill.configure(cfg);
        var d = new dev.xsoz.client.training.drill.FreeRoamDrill(Drill.Mode.FIXED, 0.5, false);
        d.start(s, Kit.standard(dev.xsoz.client.training.KitSpec.Mode.SWORD));
        d.skipCountdownForTests();
        TrainingHooks.bind(d, s);
        try {
            var bots = d.botsForTests();
            check(ctx, d.watched() == null && cam(d, p) == p, "watch mode should start on the free camera");
            d.watchNext(1);
            check(ctx, d.watched() == bots.get(0) && cam(d, p) == bots.get(0).body(), "Right should go to the first bot");
            d.watchNext(1);
            check(ctx, d.watched() == bots.get(1) && cam(d, p) == bots.get(1).body(), "Right again: the second bot");
            d.watchNext(-1);
            d.watchNext(-1);
            check(ctx, d.watched() == bots.get(2), "Left from the first bot wraps round to the last");
            d.watchView(1);
            check(ctx, d.view() == dev.xsoz.client.training.drill.FreeRoamDrill.View.BEHIND && cam(d, p) == bots.get(2).body(), "Down: from behind, same bot");
            d.watchView(1);
            check(ctx, d.view() == dev.xsoz.client.training.drill.FreeRoamDrill.View.FREE && cam(d, p) == p, "Down again: free camera");
            d.watchView(1);
            check(ctx, d.view() == dev.xsoz.client.training.drill.FreeRoamDrill.View.EYES && cam(d, p) == bots.get(2).body(), "Down wraps to its eyes");
            // the watched bot dies: after a moment the camera moves on to a bot that's alive
            bots.get(2).kill();
            for (int i = 0; i < 60; i++) d.serverTick();
            var now = d.watched();
            check(ctx, now != null && now.alive() && now != bots.get(2) && cam(d, p) == now.body(),
                    "after the watched bot died the camera should move to a live one, got " + (now == null ? "none" : now.name));
            // Shift (vanilla: the camera goes back to you) means you left it
            d.cameraForTests = p;
            d.serverTick();
            check(ctx, d.view() == dev.xsoz.client.training.drill.FreeRoamDrill.View.FREE, "Shift out of a bot should go to the free camera");
        } finally {
            TrainingHooks.bind(null, null);
            s.restore();
        }
        ctx.complete();
    }

    @GameTest(maxTicks = 60)
    public void skyFighterAttacksInsteadOfCircling(TestContext ctx) {
        // it used to circle above you for ever: it must come down on you, again and again
        int[] smashes = {0};
        String[] dbg = {""};
        maceFight(ctx, dev.xsoz.client.training.bot.Personality.SKY_FIGHTER, 1, 900, (i, list) -> {
            if (list.isEmpty()) return;
            smashes[0] = list.get(0).maceSmashes;
            dbg[0] = list.get(0).debug();
        });
        System.out.println("[sky] smashes in 45 s: " + smashes[0]);
        check(ctx, smashes[0] >= 3, "a sky fighter smashed only " + smashes[0] + " times in 45 s: " + dbg[0]);
        ctx.complete();
    }

    @GameTest(maxTicks = 60)
    public void hackersDontBlowThemselvesUp(TestContext ctx) {
        float[] lowest = {99f};
        int[] blasts = {0};
        int[] actions = {0};
        String[] who = {""};
        for (var kind : new dev.xsoz.client.training.bot.FreeRoamConfig.Fight[] {dev.xsoz.client.training.bot.FreeRoamConfig.Fight.CRYSTAL,
                dev.xsoz.client.training.bot.FreeRoamConfig.Fight.EVERYTHING}) {
            fight(ctx, kind, kind == dev.xsoz.client.training.bot.FreeRoamConfig.Fight.CRYSTAL ? dev.xsoz.client.training.KitSpec.Mode.CRYSTAL
                            : dev.xsoz.client.training.KitSpec.Mode.EVERYTHING,
                    dev.xsoz.client.training.bot.Personality.ALL_ROUNDER, 6, 3, 800, (i, list) -> {
                        if (i != 799) return;
                        for (var b : list) {
                            blasts[0] += b.selfBlasts;
                            actions[0] += b.crystalsPlaced + b.anchorsBlown;
                            if (b.lowestAfterOwnBlast < lowest[0]) {
                                lowest[0] = b.lowestAfterOwnBlast;
                                who[0] = b.lowestNote + " | " + b.debug();
                            }
                        }
                    });
        }
        System.out.println("[hacker] blasts placed " + actions[0] + ", hurt itself " + blasts[0] + " times, lowest after its own " + lowest[0]);
        check(ctx, actions[0] > 10, "hackers barely fought: " + actions[0]);
        check(ctx, lowest[0] >= 1.9f, "a hacker's own blast left it on " + lowest[0] + " (0 = popped or died): " + who[0]);
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void freeRoamBotsFightEachOtherAndRestoreTheWorld(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (BlockPos q : BlockPos.iterate(base.add(-21, -4, -21), base.add(21, 12, 21))) before.put(q.toImmutable(), w.getBlockState(q));
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        var cfg = new dev.xsoz.client.training.bot.FreeRoamConfig();
        cfg.bots = 3;
        cfg.teams = dev.xsoz.client.training.bot.FreeRoamConfig.Teams.FFA;
        cfg.abilities = new java.util.ArrayList<>(dev.xsoz.client.training.bot.FreeRoamConfig.Fight.EVERYTHING.abilities());
        cfg.size = dev.xsoz.client.training.bot.FreeRoamConfig.Size.SMALL;
        cfg.level = 4;
        dev.xsoz.client.training.drill.FreeRoamDrill.configure(cfg);
        dev.xsoz.client.training.drill.FreeRoamDrill d = new dev.xsoz.client.training.drill.FreeRoamDrill(Drill.Mode.FIXED, 0.5, false);
        d.start(s, Kit.standard(dev.xsoz.client.training.KitSpec.Mode.EVERYTHING));
        d.skipCountdownForTests();
        TrainingHooks.bind(d, s);
        try {
            check(ctx, d.botsForTests().size() == 3, "expected 3 bots");
            for (int i = 0; i < 600; i++) {
                p.setVelocity(Vec3d.ZERO);
                d.serverTick();
            }
            int actions = 0;
            StringBuilder dbg = new StringBuilder();
            for (var b : d.botsForTests()) {
                actions += b.crystalsPlaced + b.meleeHits + b.anchorsBlown + b.maceSmashes + b.pearlsThrown;
                dbg.append(b.debug()).append(" | ");
            }
            check(ctx, actions > 0, "three bots in 30 s did nothing: " + dbg);
            for (var b : d.botsForTests()) {
                check(ctx, b.maxClickReach <= 4.51, "a bot clicked a block " + b.maxClickReach + " blocks away (players: 4.5)");
                check(ctx, b.maxHitReach <= 3.01, "a bot hit something " + b.maxHitReach + " blocks away (players: 3)");
            }
        } finally {
            TrainingHooks.bind(null, null);
            s.restore();
        }
        int changed = 0;
        for (var e : before.entrySet()) if (!w.getBlockState(e.getKey()).equals(e.getValue())) changed++;
        check(ctx, changed == 0, changed + " block(s) not restored after a free roam fight");
        int dummies = w.getEntitiesByClass(MannequinEntity.class, new Box(base).expand(30, 8, 30), m -> true).size();
        check(ctx, dummies == 0, dummies + " bots left behind");
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void botMinesOutOfAHoleInsteadOfJumpingForever(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        var cfg = new dev.xsoz.client.training.bot.FreeRoamConfig();
        cfg.bots = 1;
        cfg.abilities = new java.util.ArrayList<>(dev.xsoz.client.training.bot.FreeRoamConfig.Fight.SWORD.abilities());
        cfg.size = dev.xsoz.client.training.bot.FreeRoamConfig.Size.SMALL;
        cfg.level = 5;
        dev.xsoz.client.training.drill.FreeRoamDrill.configure(cfg);
        dev.xsoz.client.training.drill.FreeRoamDrill d = new dev.xsoz.client.training.drill.FreeRoamDrill(Drill.Mode.FIXED, 0.5, false);
        d.start(s, Kit.standard(dev.xsoz.client.training.KitSpec.Mode.SWORD));
        d.skipCountdownForTests();
        TrainingHooks.bind(d, s);
        try {
            var bot = d.botsForTests().get(0);
            // box the bot into a 2-deep obsidian hole, the player far away
            BlockPos hole = BlockPos.ofFloored(bot.pos());
            for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                for (int y = 0; y <= 2; y++) s.setBlock(hole.offset(dir).up(y), Blocks.OBSIDIAN.getDefaultState());
            }
            int dx = d.center().getX() > hole.getX() ? 8 : -8;
            Scene.teleport(p, Vec3d.ofBottomCenter(hole.add(dx, 0, 0)), 0f, 0f);
            for (int i = 0; i < 400 && bot.blocksMined == 0 && bot.pearlsThrown == 0; i++) {
                p.setVelocity(Vec3d.ZERO);
                d.serverTick();
            }
            check(ctx, bot.blocksMined > 0 || bot.pearlsThrown > 0, "the bot stayed stuck in the hole: " + bot.debug());
        } finally {
            TrainingHooks.bind(null, null);
            s.restore();
        }
        ctx.complete();
    }

    @GameTest(maxTicks = 300)
    public void botsFinishEatingWhenTheirBodiesTick(TestContext ctx) {
        BlockPos base = ticking(ctx).up(18);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        var cfg = new dev.xsoz.client.training.bot.FreeRoamConfig();
        cfg.bots = 1;
        cfg.abilities = new java.util.ArrayList<>(dev.xsoz.client.training.bot.FreeRoamConfig.Fight.CRYSTAL.abilities());
        cfg.size = dev.xsoz.client.training.bot.FreeRoamConfig.Size.SMALL;
        cfg.level = 4;
        dev.xsoz.client.training.drill.FreeRoamDrill.configure(cfg);
        dev.xsoz.client.training.drill.FreeRoamDrill d = new dev.xsoz.client.training.drill.FreeRoamDrill(Drill.Mode.FIXED, 0.5, false);
        d.start(s, Kit.standard(dev.xsoz.client.training.KitSpec.Mode.CRYSTAL));
        d.skipCountdownForTests();
        TrainingHooks.bind(d, s);
        var bot = d.botsForTests().get(0);
        // hurt, no golden hearts, nobody near: it must eat (and finish eating) with its body ticking in the world
        Scene.teleport(p, Vec3d.ofBottomCenter(BlockPos.ofFloored(bot.pos()).add(14, 0, 0)), 0f, 0f);
        bot.healthForTests(9f);
        Runnable tk = () -> {
            p.setVelocity(Vec3d.ZERO);
            d.serverTick();
        };
        TICKERS.add(tk);
        ctx.waitAndRun(160, () -> {
            TICKERS.remove(tk);
            String dbg = bot.debug() + " eating=" + bot.eating();
            TrainingHooks.bind(null, null);
            s.restore();
            check(ctx, bot.gapplesEaten > 0, "the bot did not eat a gapple: " + dbg);
            ctx.complete();
        });
    }

    // ------------------------------------------------------------------ new drill behaviour

    @GameTest(maxTicks = 40)
    public void keybindReflexLocksTheInventory(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        KeybindReflexDrill d = new KeybindReflexDrill(Drill.Mode.FIXED, 0.0, false);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        p.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, p.getInventory().getStack(3).copy());
        p.getInventory().setStack(3, ItemStack.EMPTY);
        p.getInventory().setStack(20, new ItemStack(Items.RED_WOOL));
        d.serverTick();
        check(ctx, p.getOffHandStack().isEmpty(), "wool could be moved into the offhand");
        check(ctx, p.getInventory().getStack(3).isOf(Items.RED_WOOL), "a hotbar slot stayed empty after moving its wool");
        check(ctx, p.getInventory().getStack(20).isEmpty(), "wool could be put in the inventory");
        s.restore();
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void obsidianCrystalLevelThreeSpotsMove(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        ObbyCrystalDrill d = new ObbyCrystalDrill(Drill.Mode.FIXED, 0.0, false);
        d.level(3);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        int guard = 0;
        while (d.targetsForTests().size() < 2 && guard++ < 100) d.serverTick();
        var first = d.targetsForTests();
        check(ctx, first.size() == 2, "level 3 should show two spots");
        for (int i = 0; i < 120 && d.targetsForTests().equals(first); i++) d.serverTick();
        var later = d.targetsForTests();
        check(ctx, !later.isEmpty() && !later.equals(first), "the spots never moved: " + first + " -> " + later);
        for (BlockPos t : later) check(ctx, ctx.getWorld().getBlockState(t.down()).isOf(Blocks.LIME_CONCRETE), "a moved spot lost its lime block");
        s.restore();
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void hitCrystalScoresAnAirborneBlast(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        dev.xsoz.client.training.drill.HitCrystalDrill d = new dev.xsoz.client.training.drill.HitCrystalDrill(
                dev.xsoz.client.training.DrillDef.HIT_CRYSTAL, Drill.Mode.FIXED, 0.0, false);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        TrainingHooks.bind(d, s);
        try {
            MannequinEntity dummy = w.getEntitiesByClass(MannequinEntity.class, new Box(base).expand(8), m -> true).get(0);
            d.onTrackedDamage(dummy, w.getDamageSources().playerAttack(p), 8f);
            run(d, 3);
            check(ctx, dummy.getY() > base.getY() + 0.3, "the hit did not knock the dummy up");
            BlockPos obby = null;
            for (BlockPos q : BlockPos.iterate(base.add(-5, -1, -5), base.add(5, -1, 5))) if (w.getBlockState(q).isOf(Blocks.OBSIDIAN)) obby = q.toImmutable();
            check(ctx, obby != null, "no obsidian next to the dummy on level 1");
            EndCrystalEntity c = crystalOn(w, obby);
            d.onCrystalBreak(c, p);
            check(ctx, d.reps.size() == 1 && d.reps.get(0).hit(), "an air blast 150 ms after the hit was not a hit: " + d.reps);
        } finally {
            TrainingHooks.bind(null, null);
            s.restore();
        }
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void layoutRecallCuesHappenInTheWorld(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        LayoutRecallDrill d = new LayoutRecallDrill(Drill.Mode.FIXED, 0.0, false);
        d.level(2);
        Kit kit = Kit.current();
        d.start(s, kit);
        d.skipCountdownForTests();
        for (int rep = 0; rep < 6; rep++) {
            int guard = 0;
            while (d.shownCue == null && guard++ < 200) d.serverTick();
            check(ctx, d.shownCue != null, "no cue");
            p.getInventory().setSelectedSlot(kit.slot(d.shownCue));
            d.serverTick();
        }
        check(ctx, d.reps.size() == 6 && d.hits() == 6, "answering each cue with its item was not a hit: " + d.reps);
        s.restore();
        check(ctx, p.getInventory().getStack(0).isOf(Items.DIAMOND), "inventory not restored");
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void situationsBuildPlayableScenes(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        Map<BlockPos, BlockState> before = snapshot(w, base);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        dev.xsoz.client.training.drill.SituationsDrill d = new dev.xsoz.client.training.drill.SituationsDrill(Drill.Mode.FIXED, 0.0, false);
        d.start(s, Kit.current());
        d.skipCountdownForTests();
        TrainingHooks.bind(d, s);
        try {
            // let several scenes time out: each must start with the player in free space
            for (int scene = 0; scene < 5; scene++) {
                int guard = 0;
                while (d.shownTitle.isEmpty() && guard++ < 100) d.serverTick();
                check(ctx, !d.shownTitle.isEmpty(), "no scene started");
                check(ctx, Scene.standable(w, p.getBlockPos()) || w.getBlockState(p.getBlockPos().down()).isOf(Blocks.STONE),
                        "scene " + d.shownTitle + " put the player inside a block at " + p.getBlockPos());
                int reps = d.reps.size();
                guard = 0;
                while (d.reps.size() == reps && guard++ < 400) {
                    p.setVelocity(Vec3d.ZERO);
                    d.serverTick();
                }
                check(ctx, d.reps.size() == reps + 1, "scene " + d.shownTitle + " never ended");
            }
        } finally {
            TrainingHooks.bind(null, null);
            s.restore();
        }
        assertRestored(ctx, before, p);
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void skyArenaRestoresAndProtectsItsWalls(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        BlockPos c = base.up(4);
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (BlockPos q : BlockPos.iterate(c.add(-10, -6, -10), c.add(10, 10, 10))) before.put(q.toImmutable(), w.getBlockState(q));
        var arena = dev.xsoz.client.training.session.SkyArena.build(s, c, 8, 8, false, dev.xsoz.client.training.session.SkyArena.Floor.STONE);
        check(ctx, w.getBlockState(c.down(4)).isOf(Blocks.BEDROCK) && w.getBlockState(c.down()).isOf(Blocks.STONE), "no platform");
        BlockPos wall = c.add(9, 2, 0);
        check(ctx, w.getBlockState(wall).isOf(Blocks.LIGHT_GRAY_STAINED_GLASS), "no glass wall");
        check(ctx, s.isProtected(wall) && !s.isProtected(c.add(3, 0, 3)), "the wall is not protected (or the floor is)");
        w.setBlockState(c.down(), Blocks.AIR.getDefaultState());
        arena.refresh(w, dev.xsoz.client.training.session.SkyArena.Floor.STONE);
        check(ctx, w.getBlockState(c.down()).isOf(Blocks.STONE), "refresh did not repaint the floor");
        s.restore();
        int changed = 0;
        for (var e : before.entrySet()) if (!w.getBlockState(e.getKey()).equals(e.getValue())) changed++;
        check(ctx, changed == 0, changed + " block(s) of the sky arena not restored");
        ctx.complete();
    }

    /** Far from every other test: deep arenas reach down through the height bands. */
    private static BlockPos faraway(TestContext ctx, int dx) {
        return ctx.getAbsolutePos(BlockPos.ORIGIN).add(dx, 0, 0);
    }

    @GameTest(maxTicks = 40)
    public void deepGroundArenaHasOresRoofAndRestores(TestContext ctx) {
        ServerWorld w = ctx.getWorld();
        BlockPos c = faraway(ctx, 700).withY(150);
        FakePlayer p = player(ctx, c);
        Session s = session(ctx, p);
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (BlockPos q : BlockPos.iterate(c.add(-11, -25, -11), c.add(11, 10, 11))) before.put(q.toImmutable(), w.getBlockState(q));
        var arena = dev.xsoz.client.training.session.SkyArena.build(s, c, 10, 8, false, dev.xsoz.client.training.session.SkyArena.Theme.GRASS,
                dev.xsoz.client.training.session.SkyArena.Depth.DEEP, dev.xsoz.client.training.session.SkyArena.Bedrock.NATURAL);
        arena.finishBuild();
        check(ctx, w.getBlockState(c.down()).isOf(Blocks.GRASS_BLOCK), "no grass on top");
        check(ctx, w.getBlockState(c.down(24)).isOf(Blocks.BEDROCK), "no bedrock at the bottom of the deep ground");
        int air = 0;
        int other = 0;
        for (BlockPos q : BlockPos.iterate(c.add(-9, -22, -9), c.add(9, -6, 9))) {
            BlockState st = w.getBlockState(q);
            if (st.isAir()) air++;
            else if (!st.isOf(Blocks.STONE) && !st.isOf(Blocks.DIRT) && !st.isOf(Blocks.BEDROCK)) other++;
        }
        check(ctx, air == 0, air + " air blocks inside the deep ground");
        check(ctx, other > 0, "no ores, gravel or rock patches underground");
        BlockPos roof = c.up(9);
        check(ctx, w.getBlockState(roof).isOf(Blocks.LIGHT_GRAY_STAINED_GLASS) && s.isProtected(roof), "no protected glass roof");
        s.restore();
        int changed = 0;
        for (var e : before.entrySet()) if (!w.getBlockState(e.getKey()).equals(e.getValue())) changed++;
        check(ctx, changed == 0, changed + " block(s) of the deep arena not restored");
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void fullWorldGroundGoesDownToNaturalBedrock(TestContext ctx) {
        ServerWorld w = ctx.getWorld();
        BlockPos near = faraway(ctx, 1100);
        BlockPos c = dev.xsoz.client.training.session.SkyArena.skyCenter(w, near, 8, dev.xsoz.client.training.session.SkyArena.Depth.FULL);
        FakePlayer p = player(ctx, c);
        Session s = session(ctx, p);
        int bottom = w.getBottomY();
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (BlockPos q : BlockPos.iterate(new BlockPos(c.getX() - 7, bottom, c.getZ() - 7), c.add(7, 10, 7))) before.put(q.toImmutable(), w.getBlockState(q));
        long t0 = System.nanoTime();
        var arena = dev.xsoz.client.training.session.SkyArena.build(s, c, 6, 8, true, dev.xsoz.client.training.session.SkyArena.Theme.STONE,
                dev.xsoz.client.training.session.SkyArena.Depth.FULL, dev.xsoz.client.training.session.SkyArena.Bedrock.NATURAL);
        arena.finishBuild();
        long built = System.nanoTime() - t0;
        check(ctx, arena.groundBottom == bottom, "the ground doesn't reach the world's bottom");
        int bedrockLayer0 = 0;
        int bedrockLayer3 = 0;
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                if (w.getBlockState(new BlockPos(c.getX() + x, bottom, c.getZ() + z)).isOf(Blocks.BEDROCK)) bedrockLayer0++;
                if (w.getBlockState(new BlockPos(c.getX() + x, bottom + 3, c.getZ() + z)).isOf(Blocks.BEDROCK)) bedrockLayer3++;
            }
        }
        check(ctx, bedrockLayer0 == 169, "the bottom bedrock layer has holes");
        check(ctx, bedrockLayer3 > 0 && bedrockLayer3 < 169, "the bedrock is not natural (layer 3 has " + bedrockLayer3 + " bedrock)");
        if (bottom < -10) check(ctx, w.getBlockState(new BlockPos(c.getX(), bottom + 20, c.getZ())).isOf(Blocks.DEEPSLATE)
                || !w.getBlockState(new BlockPos(c.getX(), bottom + 20, c.getZ())).isAir(), "no deepslate deep down");
        long t1 = System.nanoTime();
        s.restore();
        long restored = System.nanoTime() - t1;
        int changed = 0;
        for (var e : before.entrySet()) if (!w.getBlockState(e.getKey()).equals(e.getValue())) changed++;
        check(ctx, changed == 0, changed + " block(s) of the full-world arena not restored");
        System.out.printf("[xsoz] full-world 13x13 arena: built %.0f ms, restored %.0f ms%n", built / 1e6, restored / 1e6);
        ctx.complete();
    }

    @GameTest(maxTicks = 300)
    public void botPearlsLandWhereItAims(TestContext ctx) {
        BlockPos base = ticking(ctx).up(40);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        var cfg = new dev.xsoz.client.training.bot.FreeRoamConfig();
        cfg.bots = 1;
        cfg.abilities = new java.util.ArrayList<>(dev.xsoz.client.training.bot.FreeRoamConfig.Fight.CRYSTAL.abilities());
        cfg.size = dev.xsoz.client.training.bot.FreeRoamConfig.Size.SMALL;
        cfg.level = 5;
        dev.xsoz.client.training.drill.FreeRoamDrill.configure(cfg);
        dev.xsoz.client.training.drill.FreeRoamDrill d = new dev.xsoz.client.training.drill.FreeRoamDrill(Drill.Mode.FIXED, 0.5, false);
        d.start(s, Kit.standard(dev.xsoz.client.training.KitSpec.Mode.CRYSTAL));
        d.skipCountdownForTests();
        TrainingHooks.bind(d, s);
        var bot = d.botsForTests().get(0);
        // the player far off to one side so the bot isn't busy fighting
        BlockPos from = BlockPos.ofFloored(bot.pos());
        // the pearl is an entity: it only flies in a chunk that ticks (a fake player doesn't make
        // chunks tick, a real one does) - force the chunks around the throw
        java.util.List<net.minecraft.util.math.ChunkPos> forced = new java.util.ArrayList<>();
        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                var cp = new net.minecraft.util.math.ChunkPos((from.getX() >> 4) + cx, (from.getZ() >> 4) + cz);
                if (ctx.getWorld().setChunkForced(cp.x, cp.z, true)) forced.add(cp);
            }
        }
        Scene.teleport(p, Vec3d.ofBottomCenter(from.add(0, 0, 17)), 0f, 0f);
        // a spot ~13 blocks away, toward the middle of the floor
        Vec3d toMid = Vec3d.ofBottomCenter(d.center()).subtract(Vec3d.ofBottomCenter(from)).multiply(1, 0, 1);
        toMid = toMid.lengthSquared() < 1 ? new Vec3d(1, 0, 0) : toMid.normalize();
        Vec3d goal = Vec3d.ofBottomCenter(from.add((int) Math.round(toMid.x * 13), 0, (int) Math.round(toMid.z * 13)));
        boolean queued = bot.pearlToForTests(goal);
        int[] age = {0};
        Runnable tk = () -> {
            p.setVelocity(Vec3d.ZERO);
            d.serverTick();
            age[0]++;
        };
        TICKERS.add(tk);
        ctx.waitAndRun(160, () -> {
            TICKERS.remove(tk);
            double off = bot.firstLanding == null ? 99 : bot.firstLanding.subtract(goal).horizontalLength();
            var fly = bot.pearlInFlight();
            String dbg = bot.debug() + " goal " + goal + " from " + from + " pearl " + (fly == null ? "none" : fly.getEntityPos() + " removed " + fly.isRemoved() + " v " + fly.getVelocity());
            TrainingHooks.bind(null, null);
            s.restore();
            for (var cp : forced) ctx.getWorld().setChunkForced(cp.x, cp.z, false);
            check(ctx, queued, "the bot could not work out a throw to a spot 14 blocks away");
            check(ctx, bot.pearlsThrown > 0, "the bot never threw: " + dbg);
            check(ctx, off < 3.0, "the pearl landed " + off + " blocks from where it aimed: " + dbg);
            ctx.complete();
        });
    }

    @GameTest(maxTicks = 40)
    public void everyTutorialDemoPlaysThroughAndRestores(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (BlockPos q : BlockPos.iterate(base.add(-10, -3, -10), base.add(10, 9, 10))) before.put(q.toImmutable(), w.getBlockState(q));
        for (var id : dev.xsoz.client.trainer.crystal.Tutorials.Id.values()) {
            FakePlayer p = player(ctx, base);
            Session s = session(ctx, p);
            dev.xsoz.client.training.drill.TutorialDrill.configure(id);
            var d = new dev.xsoz.client.training.drill.TutorialDrill(Drill.Mode.FIXED, 0, false);
            d.start(s, Kit.standard(id.kit));
            d.skipCountdownForTests();
            TrainingHooks.bind(d, s);
            try {
                for (int i = 0; i < 1500 && d.phase != dev.xsoz.client.training.drill.TutorialDrill.Phase.TRY; i++) {
                    p.setVelocity(Vec3d.ZERO);
                    d.serverTick();
                }
                check(ctx, d.phase == dev.xsoz.client.training.drill.TutorialDrill.Phase.TRY, id + ": the demo never got to your turn");
                check(ctx, !d.keyRow.isEmpty() || d.phase == dev.xsoz.client.training.drill.TutorialDrill.Phase.TRY, id + ": no keys shown");
            } finally {
                TrainingHooks.bind(null, null);
                s.restore();
            }
            check(ctx, !p.isInvulnerable(), id + ": the player stayed invulnerable");
        }
        int changed = 0;
        for (var e : before.entrySet()) if (!w.getBlockState(e.getKey()).equals(e.getValue())) changed++;
        check(ctx, changed == 0, changed + " block(s) not restored after the tutorials");
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void tutorialYourTurnCountsARealDoubleTap(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        dev.xsoz.client.training.drill.TutorialDrill.configure(dev.xsoz.client.trainer.crystal.Tutorials.Id.ANCHOR_DTAP);
        var d = new dev.xsoz.client.training.drill.TutorialDrill(Drill.Mode.FIXED, 0, false);
        d.start(s, Kit.standard(dev.xsoz.client.training.KitSpec.Mode.CRYSTAL));
        d.skipCountdownForTests();
        try {
            for (int i = 0; i < 1500 && d.phase != dev.xsoz.client.training.drill.TutorialDrill.Phase.TRY; i++) d.serverTick();
            BlockPos spot = base.add(2, 0, 2);
            d.observeAnchor(spot);
            for (int i = 0; i < 5; i++) d.serverTick();
            d.observeAnchor(spot);
            check(ctx, d.hits() == 1, "two anchors 5 ticks apart in one spot did not count as a double tap");
            d.observeAnchor(base.add(4, 0, 4));
            for (int i = 0; i < 60; i++) d.serverTick();
            d.observeAnchor(base.add(4, 0, 4));
            check(ctx, d.hits() == 1, "two anchors 3 s apart counted as a double tap");
        } finally {
            s.restore();
        }
        ctx.complete();
    }

    @GameTest(maxTicks = 40)
    public void watchModeBotsFightEachOtherAndYouGetYourGameModeBack(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        var cfg = new dev.xsoz.client.training.bot.FreeRoamConfig();
        cfg.bots = 2;
        cfg.watch = true;
        cfg.mixedLevels = true;
        cfg.abilities = new java.util.ArrayList<>(dev.xsoz.client.training.bot.FreeRoamConfig.Fight.CRYSTAL.abilities());
        cfg.size = dev.xsoz.client.training.bot.FreeRoamConfig.Size.SMALL;
        cfg.level = 4;
        dev.xsoz.client.training.drill.FreeRoamDrill.configure(cfg);
        var d = new dev.xsoz.client.training.drill.FreeRoamDrill(Drill.Mode.FIXED, 0.5, false);
        d.start(s, Kit.standard(dev.xsoz.client.training.KitSpec.Mode.CRYSTAL));
        d.skipCountdownForTests();
        TrainingHooks.bind(d, s);
        try {
            check(ctx, p.isSpectator(), "watch mode did not make you a spectator");
            check(ctx, d.youForTests() == null, "you were added as a fighter in watch mode");
            int actions = 0;
            for (int i = 0; i < 800 && actions == 0; i++) {
                d.serverTick();
                actions = 0;
                for (var b : d.botsForTests()) actions += b.crystalsPlaced + b.anchorsBlown + b.meleeHits;
            }
            StringBuilder dbg = new StringBuilder();
            for (var b : d.botsForTests()) dbg.append(b.debug()).append(" | ");
            check(ctx, actions > 0, "the bots didn't fight each other: " + dbg);
        } finally {
            TrainingHooks.bind(null, null);
            s.restore();
        }
        check(ctx, !p.isSpectator(), "your game mode did not come back");
        ctx.complete();
    }

    @GameTest(maxTicks = 100)
    public void tutorialDummyShieldStaysUp(TestContext ctx) {
        BlockPos base = ticking(ctx).up(60);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        dev.xsoz.client.training.drill.TutorialDrill.configure(dev.xsoz.client.trainer.crystal.Tutorials.Id.SHIELD_BREAK);
        var d = new dev.xsoz.client.training.drill.TutorialDrill(Drill.Mode.FIXED, 0, false);
        d.start(s, Kit.standard(dev.xsoz.client.training.KitSpec.Mode.SWORD));
        d.skipCountdownForTests();
        MannequinEntity m = ctx.getWorld().getEntitiesByClass(MannequinEntity.class, new Box(base).expand(6), x -> true).stream().findFirst().orElse(null);
        Runnable tk = d::serverTick;
        TICKERS.add(tk);
        ctx.waitAndRun(20, () -> {
            TICKERS.remove(tk);
            String state = m == null ? "no dummy" : "using " + m.isUsingItem() + " blocking " + m.isBlocking() + " hand " + m.getActiveHand()
                    + " offhand " + m.getOffHandStack() + " time " + m.getItemUseTime();
            s.restore();
            check(ctx, m != null && m.isUsingItem() && m.isBlocking(), "the dummy's shield is not up: " + state);
            ctx.complete();
        });
    }

    // ------------------------------------------------------------------ start sequence and endless

    @GameTest(maxTicks = 40)
    public void nothingCountsBeforeGoAndEndlessNeverStops(TestContext ctx) {
        BlockPos base = high(ctx);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        KeybindReflexDrill d = new KeybindReflexDrill(Drill.Mode.FIXED, 0.0, false);
        d.endless(true);
        d.start(s, Kit.current());
        int pre = Drill.INTRO_TICKS + Drill.COUNT_TICKS;
        for (int i = 0; i < pre - 1; i++) d.serverTick();
        check(ctx, !d.live() && d.age() == 0 && d.greenSlot < 0, "the drill started before the countdown ended");
        d.serverTick();
        check(ctx, d.live(), "the drill did not go live after the countdown");
        int reps = 0;
        int guard = 0;
        while (reps < d.def.reps + 5 && guard++ < 20000) {
            d.serverTick();
            if (d.greenSlot >= 0) {
                p.getInventory().setSelectedSlot(d.greenSlot);
                d.serverTick();
                reps = d.reps.size();
            }
        }
        check(ctx, d.reps.size() > d.def.reps, "endless stopped producing reps at " + d.reps.size());
        check(ctx, !d.finished, "endless finished on its own");
        s.restore();
        ctx.complete();
    }

    // ------------------------------------------------------------------ crash safety

    @GameTest(maxTicks = 40)
    public void crashBackupRestoresTerrainAndInventory(TestContext ctx) {
        BlockPos base = high(ctx);
        ServerWorld w = ctx.getWorld();
        BlockPos probe = base.add(2, 0, 2);
        BlockState original = w.getBlockState(probe);
        FakePlayer p = player(ctx, base);
        Session s = session(ctx, p);
        s.setBlock(probe, Blocks.OBSIDIAN.getDefaultState());
        s.clearInventory(p);
        s.checkpoint();
        Session.recoverIfNeeded(w.getServer(), p);
        check(ctx, w.getBlockState(probe).equals(original), "terrain was not recovered from the backup");
        check(ctx, p.getInventory().getStack(0).isOf(Items.DIAMOND), "inventory was not recovered from the backup");
        ctx.complete();
    }

    @SuppressWarnings("unused")
    private static Direction unused() { return Direction.NORTH; }
}
