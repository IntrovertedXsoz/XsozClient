package dev.xsoz.client.trainer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xsoz.client.trainer.crystal.FightEvent.Type;
import org.junit.jupiter.api.Test;

class FightAnalyzerTest {

    private static FightRecord base() {
        FightRecord r = new FightRecord();
        r.id = "t";
        r.opponent = "Opp";
        r.server = "Singleplayer";
        r.startTick = 0;
        r.endTick = 400;
        r.outcome = FightRecord.Outcome.WIN;
        return r;
    }

    private static void samples(FightRecord r, float dist, boolean totem) {
        for (long t = 0; t < 400; t += FightRecord.SAMPLE_EVERY) {
            r.samples.add(new FightSample(t, 20, 0, totem, 5, 64, 1f, dist, true, 0));
        }
    }

    @Test
    void fastCyclesScoreHighSlowCyclesProduceAnIssue() {
        FightRecord fast = base();
        samples(fast, 4f, true);
        for (int i = 0; i < 10; i++) {
            fast.event(i * 10, Type.SELF_CRYSTAL_PLACE, i, 0);
            fast.event(i * 10 + 2, Type.SELF_CRYSTAL_BREAK, i, 0);
        }
        FightReport rf = FightAnalyzer.analyze(fast);
        assertEquals(100, rf.scores.get(Skill.CYCLE));
        assertTrue(rf.issues.stream().noneMatch(i -> i.skill() == Skill.CYCLE));

        FightRecord slow = base();
        samples(slow, 4f, true);
        for (int i = 0; i < 10; i++) {
            slow.event(i * 30, Type.SELF_CRYSTAL_PLACE, i, 0);
            slow.event(i * 30 + 14, Type.SELF_CRYSTAL_BREAK, i, 0);
        }
        FightReport rs = FightAnalyzer.analyze(slow);
        assertTrue(rs.scores.get(Skill.CYCLE) < 50, "14-tick cycles are slow");
        assertTrue(rs.issues.stream().anyMatch(i -> i.skill() == Skill.CYCLE));
    }

    @Test
    void forgottenCrystalsAreNotCycles() {
        FightRecord r = base();
        samples(r, 4f, true);
        r.event(0, Type.SELF_CRYSTAL_PLACE, 1, 0);
        r.event(200, Type.SELF_CRYSTAL_BREAK, 1, 0);
        assertNull(FightAnalyzer.analyze(r).scores.get(Skill.CYCLE), "one 200-tick gap is not a cycle sample");
    }

    @Test
    void slowRetotemIsFlaggedAndMissedRetotemIsPenalised() {
        FightRecord r = base();
        samples(r, 4f, true);
        r.event(10, Type.OFFHAND_EMPTY, 0, 3);
        r.event(40, Type.OFFHAND_TOTEM, 0, 0);
        r.event(100, Type.OFFHAND_EMPTY, 0, 2);
        FightReport rep = FightAnalyzer.analyze(r);
        assertNotNull(rep.scores.get(Skill.TOTEM));
        assertTrue(rep.scores.get(Skill.TOTEM) < 50);
        assertTrue(rep.issues.stream().anyMatch(i -> i.skill() == Skill.TOTEM));
    }

    @Test
    void emptyOffhandWithNoTotemsLeftIsNotARetotemOpportunity() {
        FightRecord r = base();
        samples(r, 4f, true);
        r.event(10, Type.OFFHAND_EMPTY, 0, 0);
        assertNull(FightAnalyzer.analyze(r).scores.get(Skill.TOTEM));
    }

    @Test
    void punishingCountsDamageInsideTheWindowOnly() {
        FightRecord r = base();
        samples(r, 4f, true);
        r.event(50, Type.OPP_POP, 0, 0);
        r.event(60, Type.DAMAGE_DEALT, FightEvent.DMG_CRYSTAL, 0);
        r.event(200, Type.OPP_POP, 0, 0);
        r.event(300, Type.DAMAGE_DEALT, FightEvent.DMG_CRYSTAL, 0);
        FightReport rep = FightAnalyzer.analyze(r);
        assertEquals(50, rep.scores.get(Skill.PUNISH));
        assertEquals("1 of 2", rep.stats.get("Pops punished"));
    }

    @Test
    void shieldWindowAnsweredByAnchor() {
        FightRecord r = base();
        samples(r, 4f, true);
        r.event(20, Type.OPP_SHIELD_UP, 0, 0);
        r.event(30, Type.SELF_ANCHOR_BLOW, 0, 0);
        r.event(50, Type.OPP_SHIELD_DOWN, 0, 0);
        assertEquals(100, FightAnalyzer.analyze(r).scores.get(Skill.ADAPT));
    }

    @Test
    void spacingRewardsCrystalRange() {
        FightRecord good = base();
        samples(good, 4f, true);
        FightRecord close = base();
        samples(close, 1f, true);
        assertTrue(FightAnalyzer.analyze(good).scores.get(Skill.SPACING) > 90);
        assertTrue(FightAnalyzer.analyze(close).scores.get(Skill.SPACING) < 10);
    }

    @Test
    void selfDamageLowersSafety() {
        FightRecord r = base();
        samples(r, 4f, true);
        for (int i = 0; i < 5; i++) r.event(i * 10, Type.SELF_CRYSTAL_PLACE, i, 0);
        r.event(15, Type.SELF_DAMAGE, 6, 0);
        r.event(25, Type.SELF_DAMAGE, 6, 0);
        FightReport rep = FightAnalyzer.analyze(r);
        assertTrue(rep.scores.get(Skill.SAFETY) < 60);
        assertTrue(rep.issues.stream().anyMatch(i -> i.skill() == Skill.SAFETY));
    }

    @Test
    void reportIsDeterministicAndCapsIssuesAtThree() {
        FightRecord r = base();
        samples(r, 1f, false);
        for (int i = 0; i < 10; i++) {
            r.event(i * 30, Type.SELF_CRYSTAL_PLACE, i, 0);
            r.event(i * 30 + 20, Type.SELF_CRYSTAL_BREAK, i, 0);
        }
        r.event(5, Type.OFFHAND_EMPTY, 0, 3);
        r.event(15, Type.SELF_DAMAGE, 8, 0);
        r.event(35, Type.SELF_DAMAGE, 8, 0);
        r.event(50, Type.OPP_POP, 0, 0);
        FightReport a = FightAnalyzer.analyze(r);
        FightReport b = FightAnalyzer.analyze(r);
        assertEquals(a.overall, b.overall);
        assertEquals(a.scores, b.scores);
        assertTrue(a.issues.size() <= 3);
        assertFalse(a.headline.isEmpty());
    }

    @Test
    void emptyFightHasNoGrade() {
        FightRecord r = base();
        FightReport rep = FightAnalyzer.analyze(r);
        assertTrue(rep.scores.isEmpty());
        assertEquals("-", rep.grade);
    }

    @Test
    void gradeBands() {
        assertEquals("S", FightReport.grade(95));
        assertEquals("A", FightReport.grade(80));
        assertEquals("B", FightReport.grade(65));
        assertEquals("C", FightReport.grade(50));
        assertEquals("D", FightReport.grade(49));
    }
}
