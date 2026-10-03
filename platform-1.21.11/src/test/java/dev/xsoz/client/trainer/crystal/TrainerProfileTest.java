package dev.xsoz.client.trainer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrainerProfileTest {

    private static FightRecord fight(String id) {
        FightRecord r = new FightRecord();
        r.id = id;
        r.opponent = "Opp";
        r.outcome = FightRecord.Outcome.WIN;
        return r;
    }

    private static FightReport report(int cycle) {
        FightReport rep = new FightReport();
        rep.scores.put(Skill.CYCLE, cycle);
        rep.overall = cycle;
        rep.grade = FightReport.grade(cycle);
        return rep;
    }

    @Test
    void skillBecomesSolidOnlyAfterRepeatedEvidence() {
        TrainerProfile p = new TrainerProfile();
        assertTrue(p.record(fight("a"), report(95)).isEmpty());
        assertTrue(p.record(fight("b"), report(95)).isEmpty());
        List<String> changes = p.record(fight("c"), report(95));
        assertEquals(List.of("+Cycle speed"), changes);
        assertTrue(p.solid(Skill.CYCLE));
    }

    @Test
    void solidSkillIsDroppedAfterAPoorStreak() {
        TrainerProfile p = new TrainerProfile();
        for (int i = 0; i < 4; i++) p.record(fight("g" + i), report(95));
        assertTrue(p.solid(Skill.CYCLE));
        p.record(fight("p1"), report(30));
        p.record(fight("p2"), report(30));
        List<String> changes = p.record(fight("p3"), report(30));
        assertEquals(List.of("-Cycle speed"), changes);
        assertFalse(p.solid(Skill.CYCLE));
    }

    @Test
    void focusIsTheWeakestMeasuredSkill() {
        TrainerProfile p = new TrainerProfile();
        FightReport rep = new FightReport();
        rep.scores.put(Skill.CYCLE, 90);
        rep.scores.put(Skill.TOTEM, 40);
        rep.grade = "B";
        p.record(fight("x"), rep);
        assertEquals(Skill.TOTEM, p.focus());
        assertEquals(-1, p.level(Skill.SPACING));
    }

    @Test
    void trendComparesRecentToOlder() {
        TrainerProfile p = new TrainerProfile();
        for (int i = 0; i < 5; i++) p.record(fight("o" + i), report(40));
        for (int i = 0; i < 5; i++) p.record(fight("n" + i), report(80));
        assertEquals(40, p.trend(Skill.CYCLE));
    }

    @Test
    void profileRoundTripsThroughJson() {
        TrainerProfile p = new TrainerProfile();
        p.record(fight("a"), report(70));
        p.practiceServers.add("localhost");
        p.drillBests.put("CYCLE", 3.5);
        Gson g = new Gson();
        TrainerProfile back = g.fromJson(g.toJson(p), TrainerProfile.class);
        assertEquals(1, back.fights);
        assertEquals(70, back.level(Skill.CYCLE));
        assertTrue(back.practiceServers.contains("localhost"));
        assertEquals(3.5, back.drillBests.get("CYCLE"));
        assertEquals("a", back.history.get(0).id());
    }

    @Test
    void fightRecordRoundTripsThroughJson() {
        FightRecord r = fight("rt");
        r.samples.add(new FightSample(2, 20, 4, true, 3, 64, 0.9f, 4.2f, true, 0.5f));
        r.event(5, FightEvent.Type.OPP_POP, 0, 0);
        Gson g = new Gson();
        FightRecord back = g.fromJson(g.toJson(r), FightRecord.class);
        assertEquals(1, back.samples.size());
        assertEquals(FightEvent.Type.OPP_POP, back.events.get(0).type());
        assertEquals(4.2f, back.samples.get(0).distance());
    }
}
