package dev.xsoz.client.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrainingLogicTest {

    // ------------------------------------------------------------------ staircase

    @Test
    void staircaseGetsHarderAfterThreeHitsAndEasierAfterAMiss() {
        Staircase s = new Staircase(0.5);
        s.record(true);
        s.record(true);
        assertEquals(0.5, s.p(), 1e-9);
        s.record(true);
        assertEquals(0.55, s.p(), 1e-9);
        s.record(false);
        assertEquals(0.47, s.p(), 1e-9);
        assertEquals(0.55, s.peak(), 1e-9);
    }

    @Test
    void staircaseStaysInBounds() {
        Staircase up = new Staircase(0.98);
        for (int i = 0; i < 30; i++) up.record(true);
        assertEquals(1.0, up.p(), 1e-9);
        Staircase down = new Staircase(0.02);
        for (int i = 0; i < 30; i++) down.record(false);
        assertEquals(0.0, down.p(), 1e-9);
    }

    @Test
    void staircaseConvergesNearSeventyNinePercentSuccess() {
        // simulated player who succeeds with probability 1 - p (harder = more misses)
        java.util.Random r = new java.util.Random(42);
        Staircase s = new Staircase(0.0);
        int hits = 0, n = 0;
        for (int i = 0; i < 6000; i++) {
            boolean hit = r.nextDouble() > s.p();
            s.record(hit);
            if (i > 1000) {
                n++;
                if (hit) hits++;
            }
        }
        double rate = (double) hits / n;
        assertTrue(rate > 0.65 && rate < 0.85, "converged success rate " + rate);
    }

    // ------------------------------------------------------------------ curriculum

    @Test
    void prerequisitesComeFromTheSameOrEarlierTierAndHaveNoCycles() {
        for (DrillDef d : DrillDef.values()) {
            for (DrillDef p : d.prerequisites()) {
                assertTrue(p.tier <= d.tier, d + " depends on later " + p);
                assertTrue(p != d, d + " depends on itself");
            }
        }
        // every drill can be reached by passing drills in order
        Set<DrillDef> passed = new HashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (DrillDef d : DrillDef.values()) {
                if (!passed.contains(d) && passed.containsAll(d.prerequisites())) {
                    passed.add(d);
                    progress = true;
                }
            }
        }
        assertEquals(DrillDef.values().length, passed.size(), "some drills can never unlock");
    }

    @Test
    void tierOneIsOpenToAnAbsoluteBeginner() {
        TrainingProgress fresh = new TrainingProgress();
        for (DrillDef d : DrillDef.tier(1)) assertTrue(fresh.unlocked(d), d.name());
        for (DrillDef d : DrillDef.tier(2)) assertFalse(fresh.unlocked(d), d.name());
    }

    @Test
    void drillNumbersAreSane() {
        for (DrillDef d : DrillDef.values()) {
            assertTrue(d.levelUpMinHits <= d.levelUpReps, d.name());
            // sparring is a fight: best-of rounds, a majority wins it
            if (d == DrillDef.SPARRING) assertTrue(d.levelUpMinHits * 2 > d.levelUpReps, d.name() + " needs a majority");
            else assertTrue(d.levelUpMinHits >= Math.ceil(d.levelUpReps * 0.8), d.name() + " pass bar must be strict");
            assertEquals(d.easy, d.value(0), 1e-9);
            assertEquals(d.limit, d.value(1), 1e-9);
            assertTrue(d.easy != d.limit, d.name());
            assertFalse(d.passRule.isBlank());
            assertFalse(d.tips.isEmpty());
        }
        // the easy end must be genuinely forgiving for a beginner
        assertTrue(DrillDef.KEYBIND_REFLEX.easy >= 2500);
        assertTrue(DrillDef.CRYSTAL_CYCLE.easy >= 15);
    }

    // ------------------------------------------------------------------ progress

    @Test
    void levelsAndUnlocks() {
        assertEquals(1, TrainingProgress.level(0));
        assertEquals(2, TrainingProgress.level(100));
        assertEquals(3, TrainingProgress.level(250));
        TrainingProgress p = new TrainingProgress();
        p.get(DrillDef.KEYBIND_REFLEX).passed = true;
        assertTrue(p.unlocked(DrillDef.CRYSTAL_CYCLE));
        assertFalse(p.unlocked(DrillDef.HOTBAR_REFILL));
        p.resetPass(DrillDef.KEYBIND_REFLEX);
        assertFalse(p.unlocked(DrillDef.CRYSTAL_CYCLE));
    }

    // ------------------------------------------------------------------ ranks

    private static TrainingProgress passTiers(int upTo) {
        TrainingProgress p = new TrainingProgress();
        for (DrillDef d : DrillDef.values()) if (d.tier <= upTo) p.get(d).passed = true;
        return p;
    }

    private static List<Rank.FightLite> fights(int n, int score, boolean won, boolean vsPlayer) {
        List<Rank.FightLite> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Rank.FightLite(score, won, vsPlayer));
        return out;
    }

    @Test
    void testsAloneStopAtIron() {
        Rank.Inputs in = new Rank.Inputs(passTiers(4), List.of(), Map.of(), 0);
        assertEquals(Rank.IRON, Rank.current(in), "Gold and up need real fights");
        assertEquals(Rank.ROOKIE, Rank.current(new Rank.Inputs(new TrainingProgress(), List.of(), Map.of(), 0)));
        assertEquals(Rank.COPPER, Rank.current(new Rank.Inputs(passTiers(1), List.of(), Map.of(), 0)));
    }

    @Test
    void mobFightsNeverCount() {
        Rank.Inputs in = new Rank.Inputs(passTiers(3), fights(20, 95, true, false), Map.of(), 0);
        assertEquals(Rank.IRON, Rank.current(in));
        Rank.Inputs real = new Rank.Inputs(passTiers(3), fights(3, 60, true, true), Map.of(), 0);
        assertEquals(Rank.GOLD, Rank.current(real));
    }

    @Test
    void fightRanksAreRollingAndCanBeLost() {
        Map<String, Integer> skills = Map.of("CYCLE", 70, "TOTEM", 70);
        List<Rank.FightLite> good = fights(10, 70, true, true);
        assertEquals(Rank.LAPIS, Rank.current(new Rank.Inputs(passTiers(4), good, skills, 0)));
        List<Rank.FightLite> recentBad = new ArrayList<>(fights(6, 40, false, true));
        recentBad.addAll(good);
        assertEquals(Rank.IRON, Rank.current(new Rank.Inputs(passTiers(4), recentBad, skills, 0)), "newest fights first: a slump drops the rank");
    }

    @Test
    void netheriteIsGenuinelyHard() {
        Map<String, Integer> all80 = Map.of("CYCLE", 90, "TOTEM", 90, "SPACING", 85, "SAFETY", 88, "PUNISH", 82, "ADAPT", 81, "TRADING", 84);
        Rank.Inputs elite = new Rank.Inputs(passTiers(4), fights(20, 88, true, true), all80, 5);
        assertEquals(Rank.NETHERITE, Rank.current(elite));
        Map<String, Integer> oneWeak = new java.util.HashMap<>(all80);
        oneWeak.put("ADAPT", 70);
        assertEquals(Rank.DIAMOND, Rank.current(new Rank.Inputs(passTiers(4), fights(20, 88, true, true), oneWeak, 5)));
        List<Rank.FightLite> half = new ArrayList<>();
        for (int i = 0; i < 20; i++) half.add(new Rank.FightLite(88, i % 2 == 0, true));
        assertEquals(Rank.DIAMOND, Rank.current(new Rank.Inputs(passTiers(4), half, all80, 5)), "50% wins is not Netherite");
    }

    @Test
    void medianIgnoresOneLuckyFight() {
        List<Rank.FightLite> fs = new ArrayList<>(fights(9, 40, false, true));
        fs.add(0, new Rank.FightLite(100, true, true));
        assertEquals(40, Rank.median(fs));
    }

    // ------------------------------------------------------------------ combat maths

    @Test
    void explosionFormulaMatchesVanilla() {
        assertEquals(85.0f, CombatMath.rawExplosion(0, 6f, 1.0), 1e-4);
        assertEquals(32.5f, CombatMath.rawExplosion(6, 6f, 1.0), 1e-4);
        assertEquals(0f, CombatMath.rawExplosion(12.5, 6f, 1.0), 1e-6);
        assertEquals(0f, CombatMath.rawExplosion(2, 6f, 0.0), 1e-6);
        assertTrue(CombatMath.rawExplosion(3, 6f, 0.5) < CombatMath.rawExplosion(3, 6f, 1.0));
    }

    @Test
    void armourAndBlastProtection() {
        assertEquals(14.95f, CombatMath.afterArmor(32.5f, 20f, 12f), 1e-3);
        assertEquals(2.99f, CombatMath.afterProtection(14.95f, 32f), 1e-3);
        assertEquals(2.99f, CombatMath.effectiveVsCrystalKit(32.5f), 1e-2);
        assertEquals(0f, CombatMath.effectiveVsCrystalKit(0f), 1e-6);
    }

    @Test
    void shieldBlocksTheFrontHalfOnly() {
        // yaw 0 faces +Z
        assertTrue(CombatMath.shieldBlocks(0, 0, 0f, 0, 5));
        assertTrue(CombatMath.shieldBlocks(0, 0, 0f, 4, 1));
        assertFalse(CombatMath.shieldBlocks(0, 0, 0f, 0, -5));
        assertFalse(CombatMath.shieldBlocks(0, 0, 0f, 3, -1));
        // yaw 90 faces -X
        assertTrue(CombatMath.shieldBlocks(0, 0, 90f, -5, 0));
        assertFalse(CombatMath.shieldBlocks(0, 0, 90f, 5, 0));
    }

    @Test
    void experienceOnlyChangesTeachingNotRequirements() {
        assertEquals(0.0, Experience.NEW.startP, 1e-9);
        assertTrue(Experience.NEW.hintsByDefault);
        assertFalse(Experience.PRO.hintsByDefault);
        assertTrue(Experience.PRO.startP > Experience.REFRESHING.startP);
        assertEquals(null, Experience.parse("nonsense"));
    }

    // ------------------------------------------------------------------ difficulty levels, goals

    @Test
    void everyDrillHasThreeDescribedLevels() {
        for (DrillDef d : DrillDef.values()) {
            if (d == DrillDef.FREE_ROAM) assertEquals(6, d.levels().size(), "Free Roam has the 6 bot levels");
            else if (d == DrillDef.TUTORIAL) assertEquals(1, d.levels().size(), "a tutorial has one level");
            else assertEquals(3, d.levels().size(), d + " should have 3 levels");
            for (DrillDef.Level l : d.levels()) {
                assertFalse(l.name().isBlank(), d + " level without a name");
                assertTrue(l.detail().length() > 15, d + " level " + l.name() + " does not say what changes");
            }
        }
    }

    @Test
    void pearlAimIsARaceToSeventy() {
        assertEquals(70, DrillDef.PEARL_AIM.goalHits);
        for (DrillDef d : DrillDef.values()) if (d != DrillDef.PEARL_AIM && d != DrillDef.TUTORIAL) assertEquals(0, d.goalHits, d + " is not a goal drill");
    }

    // ------------------------------------------------------------------ anchor self-damage, difficulty

    @Test
    void anchorRatingsSplitAtThreeAndSixHp() {
        assertEquals(0, CombatMath.anchorSelfRating(0f));
        assertEquals(0, CombatMath.anchorSelfRating(2.9f));
        assertEquals(1, CombatMath.anchorSelfRating(3f));
        assertEquals(1, CombatMath.anchorSelfRating(6f));
        assertEquals(2, CombatMath.anchorSelfRating(6.1f));
        assertEquals("Too close", CombatMath.ANCHOR_RATING[2]);
    }

    @Test
    void difficultyScalesPlayerDamageLikeVanilla() {
        assertEquals(0f, CombatMath.scaleForDifficulty(10f, 0), 1e-6);
        assertEquals(6f, CombatMath.scaleForDifficulty(10f, 1), 1e-6);
        assertEquals(1f, CombatMath.scaleForDifficulty(1f, 1), 1e-6); // min(d/2+1, d) -> d when small
        assertEquals(10f, CombatMath.scaleForDifficulty(10f, 2), 1e-6);
        assertEquals(15f, CombatMath.scaleForDifficulty(10f, 3), 1e-6);
        // the full chain equals the old fixed-kit helper on normal difficulty
        float raw = CombatMath.rawExplosion(3.0, CombatMath.CRYSTAL_POWER, 1.0);
        assertEquals(CombatMath.effectiveVsCrystalKit(raw), CombatMath.effective(raw, 2, 20f, 12f, 32f), 1e-4);
    }

    // ------------------------------------------------------------------ kits

    @Test
    void standardKitFollowsTheComboLayoutAndIsEnchanted() {
        Map<String, Integer> layout = new java.util.LinkedHashMap<>();
        layout.put("totem", 0);
        layout.put("crystal", 8);
        KitSpec k = KitSpec.crystalDefault(layout);
        assertEquals("minecraft:totem_of_undying", k.at(0).item);
        assertEquals("minecraft:end_crystal", k.at(8).item);
        assertEquals(4, (int) k.at(KitSpec.LEGS).ench.get("minecraft:blast_protection"));
        assertEquals(5, (int) k.at(k.hotbarSlotOf("minecraft:netherite_sword")).ench.get("minecraft:sharpness"));
        assertEquals("minecraft:totem_of_undying", k.at(KitSpec.OFFHAND).item);
        assertTrue(k.count("minecraft:totem_of_undying") >= 7);
        Map<String, Integer> roles = k.roleSlots();
        assertEquals(1, (int) roles.get("totem"));
        assertEquals(9, (int) roles.get("crystal"));
    }

    @Test
    void kitSpecCopiesDeeplyAndRolesFindAnySword() {
        KitSpec k = new KitSpec();
        k.put(new KitSpec.Entry(3, "minecraft:diamond_sword", 1).ench("minecraft:sharpness", 5));
        KitSpec c = k.copy();
        c.at(3).ench.put("minecraft:sharpness", 1);
        assertEquals(5, (int) k.at(3).ench.get("minecraft:sharpness"), "copy shares enchantment maps");
        assertEquals(4, (int) k.roleSlots().get("sword"));
        k.put(new KitSpec.Entry(3, "minecraft:obsidian", 64));
        assertEquals(1, k.entries.size(), "put must replace the slot");
        assertFalse(k.roleSlots().containsKey("sword"));
    }
}
