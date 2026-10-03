package dev.xsoz.core.sensitivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Contract C6.5, {@code [CONTRACT DECISION] D2} - the adaptation ramp is a LITERAL table,
 * not a generator.
 *
 * <p>These tests exist because a 1.5x generator looks obviously right and is wrong. It
 * lands the final stage on 0.5355, which is <em>past</em> the 0.744140625 target and
 * therefore faster than the target it was supposed to reach. A player following that ramp
 * overshoots and has to back off - exactly the "unusable on day one" problem the ramp
 * exists to solve.</p>
 */
class SensitivityRampTest {

    private static final double TARGET = 0.744140625;

    @Test
    @DisplayName("the table has exactly 7 stages")
    void exactlySevenStages() {
        assertEquals(7, SensitivityRamp.STAGE_COUNT);
        assertEquals(7, SensitivityRamp.STAGES.length);
        assertEquals(7, SensitivityRamp.stages().size());
    }

    @Test
    @DisplayName("the literal values are the contract's, to the last digit")
    void literalValuesAreFrozen() {
        double[] expected = {6.100, 4.070, 2.710, 1.810, 1.200, 0.800, TARGET};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], SensitivityRamp.STAGES[i].cmPer360(), 0.0,
                    "Stage " + i + " must be the frozen literal, not a computed value.");
            assertEquals(i, SensitivityRamp.STAGES[i].index());
        }
    }

    @Test
    @DisplayName("the ramp is monotonically decreasing")
    void rampIsMonotonicDescending() {
        assertTrue(SensitivityRamp.isMonotonicDescending());
        for (int i = 1; i < SensitivityRamp.STAGES.length; i++) {
            assertTrue(SensitivityRamp.STAGES[i].cmPer360() < SensitivityRamp.STAGES[i - 1].cmPer360(),
                    "Stage " + i + " must be faster than stage " + (i - 1) + ".");
        }
    }

    @Test
    @DisplayName("the ramp terminates EXACTLY on the target")
    void terminatesExactlyOnTarget() {
        RampStage terminal = SensitivityRamp.terminalStage();
        assertEquals(6, terminal.index());
        assertEquals(TARGET, terminal.cmPer360(), 0.0);
        assertEquals(TARGET, SensitivityRamp.TARGET_CM_PER_360, 0.0);
        assertTrue(terminal.isTerminal());
    }

    @Test
    @DisplayName("the ramp NEVER overshoots past the target")
    void neverOvershootsTheTarget() {
        for (RampStage stage : SensitivityRamp.stages()) {
            assertTrue(stage.cmPer360() >= TARGET,
                    "Stage " + stage.index() + " at " + stage.cmPer360()
                            + " cm/360 is FASTER than the " + TARGET + " target.");
        }
        // The ceiling is the FROZEN TABLE'S OWN maximum step, not a round 1.5. contracts.md
        // C6.5 describes the steps as "1.5 five times", but the table stores 2-decimal
        // literals, and the two of them divide out to slightly more than 1.5:
        //
        //   6.100/4.070 = 1.498771   4.070/2.710 = 1.501845   2.710/1.810 = 1.497238
        //   1.810/1.200 = 1.508333   1.200/0.800 = 1.500000   0.800/0.744140625 = 1.075066
        //
        // so the largest step in the frozen table is 1.810/1.200 = 1.508333. What must never
        // be relaxed is the direction (every step moves forward) and the FINAL step, which
        // `finalRatioIsPartial` pins to strictly under 1.5 so the ramp lands ON the target.
        double steepestStep = SensitivityRamp.STAGES[3].cmPer360()
                / SensitivityRamp.STAGES[4].cmPer360();
        assertEquals(1.508333, steepestStep, 1e-5);
        for (int i = 1; i < SensitivityRamp.STAGES.length; i++) {
            double ratio = SensitivityRamp.stageRatio(i);
            assertTrue(ratio <= steepestStep + 1e-9,
                    "Stage ratio " + i + " is " + ratio + ", above the table's own steepest step "
                            + steepestStep + ".");
            assertTrue(ratio >= 1.0,
                    "Stage ratio " + i + " is " + ratio + ", which does not move the ramp forward.");
        }
    }

    @Test
    @DisplayName("the final ratio is a PARTIAL step: strictly less than 1.5")
    void finalRatioIsPartial() {
        double finalRatio = SensitivityRamp.stageRatio(SensitivityRamp.TERMINAL_STAGE_INDEX);
        assertTrue(finalRatio < 1.5,
                "The last step must be partial, otherwise the ramp lands past the target. It was "
                        + finalRatio + ".");
        assertEquals(1.0751, finalRatio, 1e-3);
    }

    @Test
    @DisplayName("a geometric 1.5x generator WOULD overshoot - the reason this is a table")
    void aGeneratorWouldOvershoot() {
        double start = 6.100;
        double lastGenerated = start;
        for (int i = 0; i < 6; i++) {
            lastGenerated /= 1.5;
        }
        assertEquals(0.5355, lastGenerated, 1e-3);
        assertTrue(lastGenerated < TARGET,
                "The generated sixth step (" + lastGenerated + ") is faster than the target ("
                        + TARGET + "). That is the exact failure the literal table prevents.");
        assertTrue(start > TARGET);
    }

    @Test
    @DisplayName("every stage is inside the vanilla slider at 2000 DPI")
    void everyStageIsInsideTheSliderAt2000Dpi() {
        // 1e-12 is a double-epsilon allowance and nothing more: `s` is recovered from
        // cm/360 through a divide, a cube root and two affine steps, so the exact
        // slider top (stage 6, the Marlow point) comes back as 1.0000000000000002.
        // The production predicate is the thing under test, so it is asked directly.
        for (RampStage stage : SensitivityRamp.stages()) {
            double sensRatio = SensitivityModel.sFromCmPer360(stage.cmPer360(), 2000);
            assertTrue(sensRatio >= 0.0 - 1e-12 && sensRatio <= 1.0 + 1e-12,
                    "Stage " + stage.index() + " needs s = " + sensRatio
                            + " at 2000 DPI, which the vanilla slider cannot deliver.");
            assertTrue(SensitivityModel.ofCmPer360(
                            stage.cmPer360(), 2000, 90, FovRelativeMode.PHYSICAL)
                            .isInsideVanillaSlider(),
                    "Stage " + stage.index() + " at " + stage.cmPer360()
                            + " cm/360 must report inside the vanilla slider at 2000 DPI.");
        }
        assertEquals(1.0, SensitivityModel.sFromCmPer360(TARGET, 2000), 1e-4);
        assertEquals(0.3279, SensitivityModel.sFromCmPer360(6.100, 2000), 1e-4);
        assertEquals(0.4998, SensitivityModel.sFromCmPer360(6.100, 1000), 1e-4);
        assertEquals(0.7974, SensitivityModel.sFromCmPer360(6.100, 400), 1e-4);
    }

    @Test
    @DisplayName("advance moves forward one stage and clamps at the terminal stage")
    void advanceClampsAtTheTerminalStage() {
        for (int i = 0; i < SensitivityRamp.TERMINAL_STAGE_INDEX; i++) {
            RampStage next = SensitivityRamp.advance(i);
            assertEquals(i + 1, next.index());
        }
        RampStage terminal = SensitivityRamp.advance(SensitivityRamp.TERMINAL_STAGE_INDEX);
        assertEquals(SensitivityRamp.TERMINAL_STAGE_INDEX, terminal.index());
        assertEquals(TARGET, terminal.cmPer360(), 0.0,
                "Advancing past the last stage must not produce a faster-than-target stage.");
    }

    @Test
    @DisplayName("regress clamps at the first stage")
    void regressClampsAtTheStart() {
        assertEquals(0, SensitivityRamp.regress(0).index());
        for (int i = 1; i < SensitivityRamp.STAGE_COUNT; i++) {
            assertEquals(i - 1, SensitivityRamp.regress(i).index());
        }
    }

    // Nearest stage is by ABSOLUTE difference, so 5.000 belongs to stage 1:
    // |5.000 - 6.100| = 1.100 against |5.000 - 4.070| = 0.930. Expecting stage 0 there
    // would be expecting the ramp to round 5.000 UP the travel axis, which is the
    // opposite of the direction the ramp moves in.
    @ParameterizedTest(name = "cm360 {0} is nearest stage {1}")
    @CsvSource({
            "6.100,  0",
            "5.000,  1",
            "4.070,  1",
            "3.000,  2",
            "2.710,  2",
            "1.810,  3",
            "1.200,  4",
            "0.800,  5",
            "0.744140625, 6",
            "0.500,  6",
    })
    void stageForCmPer360FindsTheNearest(double cmPer360, int expectedIndex) {
        assertEquals(expectedIndex, SensitivityRamp.stageIndexForProfile(cmPer360));
        assertEquals(expectedIndex, SensitivityRamp.stageForCmPer360(cmPer360).index());
    }

    @Test
    @DisplayName("stageForCmPer360 returns the table instance, not a copy")
    void stageForCmPer360ReturnsTheFrozenInstance() {
        assertSame(SensitivityRamp.STAGES[4], SensitivityRamp.stageForCmPer360(1.20));
    }

    @Test
    @DisplayName("stages() hands back an unmodifiable copy")
    void stagesIsUnmodifiable() {
        List<RampStage> stages = SensitivityRamp.stages();
        assertThrows(UnsupportedOperationException.class, () -> stages.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> stages.add(
                new RampStage(7, 0.5, 3, 5)));
    }

    @Test
    @DisplayName("RampStage is immutable and validates its arguments")
    void rampStageIsImmutableAndValidated() {
        RampStage stage = SensitivityRamp.STAGES[0];
        assertThrows(IllegalArgumentException.class, () -> new RampStage(-1, 1.0, 3, 5));
        assertThrows(IllegalArgumentException.class, () -> new RampStage(0, 0.0, 3, 5));
        assertThrows(IllegalArgumentException.class, () -> new RampStage(0, -1.0, 3, 5));
        assertThrows(IllegalArgumentException.class, () -> new RampStage(0, Double.NaN, 3, 5));
        assertThrows(IllegalArgumentException.class, () -> new RampStage(0, 1.0, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> new RampStage(0, 1.0, 5, 3));

        assertEquals(new RampStage(0, 6.100, 3, 5), stage);
        assertEquals(new RampStage(0, 6.100, 3, 5).hashCode(), stage.hashCode());
        assertFalse(stage.isTerminal());
        assertEquals(3, stage.minDwellDays());
        assertEquals(5, stage.maxDwellDays());
        assertTrue(stage.toString().contains("cmPer360"));
    }

    @Test
    @DisplayName("every stage carries the 3-5 day dwell window")
    void dwellWindowsAreFrozen() {
        Set<Integer> windows = new HashSet<Integer>();
        for (RampStage stage : SensitivityRamp.stages()) {
            windows.add(Integer.valueOf(stage.minDwellDays() * 100 + stage.maxDwellDays()));
        }
        assertEquals(1, windows.size(), "Every stage uses the same 3-5 day window.");
        assertTrue(windows.contains(Integer.valueOf(305)));
    }

    @Test
    @DisplayName("out-of-range stage indices are rejected, not clamped")
    void outOfRangeStageIndexIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SensitivityRamp.advance(-1));
        assertThrows(IllegalArgumentException.class, () -> SensitivityRamp.advance(7));
        assertThrows(IllegalArgumentException.class, () -> SensitivityRamp.stageRatio(0));
        assertThrows(IllegalArgumentException.class, () -> SensitivityRamp.stageRatio(7));
        assertThrows(IllegalArgumentException.class, () -> SensitivityRamp.stageForCmPer360(0.0));
        assertThrows(IllegalArgumentException.class, () -> SensitivityRamp.stageForCmPer360(Double.NaN));
    }

    @Test
    @DisplayName("an inactive ramp is recorded as -1, which is not a stage index")
    void inactiveRampIndexIsDistinctFromStageZero() {
        assertEquals(-1, SensitivityRamp.RAMP_INACTIVE_STAGE_INDEX);
        assertEquals(0, SensitivityRamp.START_STAGE_INDEX);
    }
}
