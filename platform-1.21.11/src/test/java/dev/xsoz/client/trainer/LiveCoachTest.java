package dev.xsoz.client.trainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.xsoz.client.trainer.crystal.CrystalCue;
import org.junit.jupiter.api.Test;

class LiveCoachTest {

    @Test
    void highestPriorityWins() {
        LiveCoach<CrystalCue> c = new LiveCoach<>();
        c.offer(CrystalCue.CLOSE_GAP);
        c.offer(CrystalCue.RETOTEM_NOW);
        assertSame(CrystalCue.RETOTEM_NOW, c.resolve(0));
        assertSame(CrystalCue.RETOTEM_NOW, c.justStarted());
    }

    @Test
    void cueIsHeldForTheMinimumEvenWhenItsConditionClears() {
        LiveCoach<CrystalCue> c = new LiveCoach<>();
        c.offer(CrystalCue.MAKE_SPACE);
        c.resolve(0);
        for (long t = 1; t < LiveCoach.MIN_HOLD_TICKS; t++) assertSame(CrystalCue.MAKE_SPACE, c.resolve(t));
        assertNull(c.resolve(LiveCoach.MIN_HOLD_TICKS + 5));
    }

    @Test
    void smallPriorityDifferenceDoesNotPreempt() {
        LiveCoach<CrystalCue> c = new LiveCoach<>();
        c.offer(CrystalCue.PUNISH_REPAIR); // 80
        c.resolve(0);
        c.offer(CrystalCue.PUNISH_REPAIR);
        c.offer(CrystalCue.ANCHOR_BEHIND); // 86: within the margin
        assertSame(CrystalCue.PUNISH_REPAIR, c.resolve(1));
    }

    @Test
    void urgentCuePreempts() {
        LiveCoach<CrystalCue> c = new LiveCoach<>();
        c.offer(CrystalCue.CLOSE_GAP); // 36
        c.resolve(0);
        c.offer(CrystalCue.CLOSE_GAP);
        c.offer(CrystalCue.RETOTEM_NOW); // 100
        assertSame(CrystalCue.RETOTEM_NOW, c.resolve(1));
    }

    @Test
    void endedCueCoolsDownSoItCannotStrobe() {
        LiveCoach<CrystalCue> c = new LiveCoach<>();
        c.offer(CrystalCue.SPEED_UP);
        c.resolve(0);
        long end = LiveCoach.MIN_HOLD_TICKS + 5;
        assertNull(c.resolve(end));
        c.offer(CrystalCue.SPEED_UP);
        assertNull(c.resolve(end + 1), "inside cooldown");
        c.offer(CrystalCue.SPEED_UP);
        assertSame(CrystalCue.SPEED_UP, c.resolve(end + LiveCoach.COOLDOWN_TICKS + 1));
        assertEquals(2, c.shownCount());
    }

    @Test
    void modeResolutionIsDownwardOnly() {
        for (TrainingContext.Mode req : TrainingContext.Mode.values()) {
            for (TrainingContext.Mode ceil : TrainingContext.Mode.values()) {
                TrainingContext.Mode r = TrainingContext.resolve(req, ceil);
                assert r.ordinal() <= req.ordinal() && r.ordinal() <= ceil.ordinal();
            }
        }
        assertSame(TrainingContext.Mode.SELF, TrainingContext.resolve(TrainingContext.Mode.FULL, TrainingContext.Mode.SELF));
    }

    @Test
    void opponentCuesAreTheOnlyOnesThatReadTheOpponent() {
        for (CrystalCue cue : CrystalCue.values()) {
            boolean opp = cue.basis() == CueSpec.Basis.VISIBLE_OPPONENT;
            boolean groupIsOwn = cue.group == CrystalCue.Group.SURVIVAL || cue.group == CrystalCue.Group.RESOURCES || cue.group == CrystalCue.Group.TECHNIQUE;
            assertEquals(!opp, groupIsOwn, cue.name());
        }
    }

    @Test
    void addressNormalisation() {
        assertEquals("play.example.net", TrainingContext.normalize(" Play.Example.net:25565 "));
        assertEquals("1.2.3.4:25570", TrainingContext.normalize("1.2.3.4:25570"));
    }
}
