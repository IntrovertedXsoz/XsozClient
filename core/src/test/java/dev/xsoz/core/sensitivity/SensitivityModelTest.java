package dev.xsoz.core.sensitivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xsoz.core.XsozContractException;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract C6.0 - the canonical sensitivity math.
 *
 * <p><strong>Every assertion in the "anchors" block is a regression lock on the 1.2
 * coefficient.</strong> This project has been wrong three times about this number (a 5.8x
 * error, then a 6.667x error, then a linear-FOV error), and each time the wrong value
 * compiled cleanly and looked plausible. The values below are the correct ones.</p>
 */
class SensitivityModelTest {

    @Nested
    @DisplayName("C6.0 anchors - the three values the contract freezes")
    class Anchors {

        @Test
        @DisplayName("s = 0.5 (vanilla default) is 0.15 deg/count")
        void vanillaDefaultDegPerCount() {
            assertEquals(0.15, SensitivityModel.degPerCount(0.5), 1e-9);
        }

        @Test
        @DisplayName("s = 0.5 at 1000 DPI is 6.096 cm/360")
        void vanillaDefaultCmPer360At1000Dpi() {
            assertEquals(6.096, SensitivityModel.cmPer360(0.5, 1000), 1e-3);
        }

        @Test
        @DisplayName("s = 1.0 (slider top) is 0.6144 deg/count")
        void sliderTopDegPerCount() {
            assertEquals(0.6144, SensitivityModel.degPerCount(1.0), 1e-9);
        }

        @Test
        @DisplayName("s = 1.0 at 2000 DPI is exactly 0.744140625 cm/360 (Marlow's published point)")
        void marlowPointCmPer360() {
            assertEquals(0.744140625, SensitivityModel.cmPer360(1.0, 2000), 1e-6);
        }

        @Test
        @DisplayName("s = 1.0 at 1000 DPI is 1.48828 cm/360")
        void sliderTopCmPer360At1000Dpi() {
            assertEquals(1.48828, SensitivityModel.cmPer360(1.0, 1000), 1e-5);
        }

        @Test
        @DisplayName("s = 0.0 (slider floor) is 0.0096 deg/count")
        void sliderFloorDegPerCount() {
            assertEquals(0.0096, SensitivityModel.degPerCount(0.0), 1e-9);
        }

        @Test
        @DisplayName("s = 0.0 at 2000 DPI is 47.625 cm/360")
        void sliderFloorCmPer360At2000Dpi() {
            assertEquals(47.625, SensitivityModel.cmPer360(0.0, 2000), 1e-6);
        }

        @Test
        @DisplayName("degPerInch at the Marlow point is 1228.8 deg/inch")
        void marlowPointDegPerInch() {
            assertEquals(1228.8, SensitivityModel.degPerInch(1.0, 2000), 1e-9);
        }

        @Test
        @DisplayName("mmPer90Deg at the Marlow point is 1.8604 mm")
        void marlowPointMmPer90Deg() {
            SensitivityModel model = SensitivityModel.ofVanillaSlider(
                    1.0, 2000, 90, FovRelativeMode.PHYSICAL);
            assertEquals(1.8604, model.mmPer90Deg(), 1e-4);
        }
    }

    @Nested
    @DisplayName("THE 8.0 TRAP - this must never regress")
    class Trap {

        @Test
        @DisplayName("the coefficient constant is 1.2, never 8.0")
        void coefficientIsOnePointTwo() {
            assertEquals(1.2, SensitivityModel.DEG_PER_COUNT_COEFFICIENT, 0.0,
                    "The coefficient is the product of vanilla's 8.0F in the input handler and "
                            + "vanilla's 0.15F in the entity turn method: 8.0 * 0.15 = 1.2.");
        }

        @Test
        @DisplayName("the 8.0 value is exactly 1/0.15 = 6.66667x too large")
        void eightPointZeroIsWrongByTheDroppedEntityTurnFactor() {
            double wrongCoefficientDegPerCount =
                    8.0 * Math.pow(0.6 * 1.0 + 0.2, 3);
            double rightCoefficientDegPerCount = SensitivityModel.degPerCount(1.0);

            assertEquals(4.096, wrongCoefficientDegPerCount, 1e-9,
                    "Truncating the vanilla call chain at the input handler yields 4.096 deg/count.");
            assertEquals(6.66667, wrongCoefficientDegPerCount / rightCoefficientDegPerCount, 1e-5,
                    "The error factor is exactly 1 / 0.15, the Entity.turn factor that was dropped.");
            assertEquals(1.0 / 0.15, wrongCoefficientDegPerCount / rightCoefficientDegPerCount, 1e-12);
        }

        @Test
        @DisplayName("the 8.0 value also puts cm/360 out by the same 6.66667 factor")
        void eightPointZeroIsAlsoWrongOnCmPer360() {
            // Arithmetic, from the two vanilla source lines (contracts.md C6.0):
            //
            //   truncated call chain, s = 0.5:  (0.6*0.5 + 0.2)^3 = 0.5^3 = 0.125
            //                                 8.0 * 0.125        = 1.0     deg/count
            //   full call chain,     s = 0.5:  1.2 * 0.5^3       = 0.15    deg/count
            //
            //   cm/360 is INVERSELY proportional to deg/count, and the coefficient error
            //   is a factor of 8.0 / 1.2 = 6.666667 = 1 / 0.15 too LARGE, so the travel
            //   error is the same magnitude and points the other way - 6.666667x too SMALL:
            //
            //   914.4 / (1.0  * 1000)  = 0.9144  cm/360        (WRONG, 0.15x the right value)
            //   914.4 / (0.15 * 1000)  = 6.096   cm/360        (RIGHT)
            //   0.9144 / 6.096         = 0.15    = 1 / 6.666667
            //   6.096   / 0.9144       = 6.666667
            //
            // Nothing here equals 1.52 or 4.0: those were the two published misconceptions
            // of this very ratio, and both are wrong. The factor is 6.66667, always.
            double wrongCmPer360 = 914.4 / ((8.0 * Math.pow(0.6 * 0.5 + 0.2, 3)) * 1000);
            double rightCmPer360 = SensitivityModel.cmPer360(0.5, 1000);
            assertEquals(0.9144, wrongCmPer360, 1e-9,
                    "Truncating the vanilla call chain at the input handler yields 0.9144 cm/360.");
            assertEquals(0.15, wrongCmPer360 / rightCmPer360, 1e-12,
                    "The truncated chain turns the mouse 6.6667x too fast, so the travel it "
                            + "reports is 1 / 6.6667 = 0.15x the true travel.");
            assertEquals(6.66667, rightCmPer360 / wrongCmPer360, 1e-5,
                    "Read the other way, the travel is out by 8.0 / 1.2 = 1 / 0.15 = 6.66667x - "
                            + "the same factor as the deg/count error, because cm/360 is its "
                            + "reciprocal.");
            assertEquals(8.0 / 1.2, rightCmPer360 / wrongCmPer360, 1e-12);
            assertEquals(1.0 / 0.15, rightCmPer360 / wrongCmPer360, 1e-12);
        }
    }

    @Nested
    @DisplayName("round trips - s -> cm/360 -> s must be lossless")
    class RoundTrips {

        @ParameterizedTest(name = "s = {0}")
        @ValueSource(doubles = {0.0, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45,
                0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0})
        void sensSurvivesDegPerCountRoundTrip(double sensRatio) {
            assertEquals(sensRatio,
                    SensitivityModel.sFromDegPerCount(SensitivityModel.degPerCount(sensRatio)),
                    1e-9);
        }

        @ParameterizedTest(name = "s = {0}")
        @ValueSource(doubles = {0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0})
        void sensSurvivesCmPer360RoundTrip(double sensRatio) {
            assertEquals(sensRatio,
                    SensitivityModel.sFromCmPer360(SensitivityModel.cmPer360(sensRatio, 2000), 2000),
                    1e-9);
        }

        @ParameterizedTest(name = "dpi = {0}")
        @ValueSource(ints = {400, 800, 1000, 1600, 2000, 4000, 8000})
        void dpiOnlyScalesCmPer360Linearly(int mouseDpi) {
            double atDpi = SensitivityModel.cmPer360(1.0, mouseDpi);
            double atDoubleDpi = SensitivityModel.cmPer360(1.0, mouseDpi * 2);
            assertEquals(atDpi / 2.0, atDoubleDpi, 1e-9);
        }
    }

    @Nested
    @DisplayName("C6.1 hard limits - the achievable range, refused not clamped")
    class HardLimits {

        @Test
        @DisplayName("the model reports inside the slider at the Marlow point")
        void marlowPointIsInsideSlider() {
            SensitivityModel model = SensitivityModel.ofVanillaSlider(
                    1.0, 2000, 90, FovRelativeMode.PHYSICAL);
            assertTrue(model.isInsideVanillaSlider());
            assertFalse(model.rangeHint().isPresent());
        }

        @Test
        @DisplayName("an unreachable target is a VALUE with a hint, not an exception")
        void unreachableTargetIsAValueNotAnException() {
            // Arithmetic (contracts.md C6.1, the hard-limits table), at 1000 DPI:
            //
            //   fastest vanilla reaches:  914.4 / (0.6144 * 1000) =  1.48828125 cm/360
            //   slowest vanilla reaches:  914.4 / (0.0096 * 1000) = 95.25      cm/360
            //
            // 47.625 is the same expression at 2000 DPI, not at 1000 DPI. The reachable
            // range scales exactly with 1/dpi, and 47.625 * 2 = 95.25.
            SensitivityModel model = SensitivityModel.ofCmPer360(
                    0.744140625, 1000, 90, FovRelativeMode.PHYSICAL);
            assertFalse(model.isInsideVanillaSlider());
            assertTrue(model.s() > 1.0,
                    "Holding 0.744 cm/360 at 1000 DPI needs MORE dpi, not less; it is out of range.");

            Optional<RangeHint> hint = model.rangeHint();
            assertTrue(hint.isPresent());
            assertEquals(1.48828125, hint.get().minCmPer360(), 1e-6);
            assertEquals(95.25, hint.get().maxCmPer360(), 1e-6,
                    "914.4 / (1.2 * 0.2^3 * 1000) = 914.4 / 9.6 = 95.25 cm/360 at 1000 DPI.");
            assertEquals(2000, hint.get().minDpi());
            assertEquals(4057, hint.get().recommendedDpi());
            // The sentence the settings screen shows verbatim must name the DPI the player
            // is on and the DPI that would put the target at 72% of the slider.
            String sentence = hint.get().uiSentence(0.744140625, 1000);
            assertTrue(sentence.contains("1000 DPI"), sentence);
            assertTrue(sentence.contains("4057 DPI"), sentence);
            assertTrue(sentence.contains("0.744 cm/360"), sentence);
        }

        @Test
        @DisplayName("both slider endpoints are reachable, so both report INSIDE")
        void bothSliderEndpointsAreInside() {
            // Vanilla's slider is [0,1] INCLUSIVE. The floor and the top are both ordinary
            // documented settings, so neither may read as out of range:
            //
            //   s = 0.0 @ 2000 DPI -> 914.4 / (0.0096 * 2000)  = 47.625      cm/360
            //   s = 1.0 @ 2000 DPI -> 914.4 / (0.6144 * 2000) =  0.744140625 cm/360
            //
            // The top is the Marlow point, and recovering `s` from cm/360 through a divide,
            // a cube root and two affine steps returns 1.0000000000000002 rather than 1.0.
            // A strict bound reads that exact endpoint as outside; the check absorbs float
            // round-off and nothing else.
            SensitivityModel atFloor = SensitivityModel.ofVanillaSlider(
                    0.0, 2000, 90, FovRelativeMode.PHYSICAL);
            assertEquals(47.625, atFloor.cmPer360(), 1e-6);
            assertEquals(0.0, atFloor.s(), 1e-12);
            assertTrue(atFloor.isInsideVanillaSlider(), "s = 0.0 is the slider floor, so it is inside.");
            assertFalse(atFloor.rangeHint().isPresent());

            SensitivityModel atTop = SensitivityModel.ofVanillaSlider(
                    1.0, 2000, 90, FovRelativeMode.PHYSICAL);
            assertEquals(0.744140625, atTop.cmPer360(), 1e-9);
            assertEquals(1.0, atTop.s(), 1e-9);
            assertTrue(atTop.isInsideVanillaSlider(), "s = 1.0 is the slider top, so it is inside.");
            assertFalse(atTop.rangeHint().isPresent());
        }

        @Test
        @DisplayName("a hair beyond either endpoint is still OUTSIDE, not tolerated")
        void justOutsideTheEndpointsIsStillOutside() {
            // The inclusive bound must not become a loophole. Nudging cm/360 by one part in
            // a thousand past either end moves `s` by a few parts in ten thousand - roughly
            // five orders of magnitude above the 1e-9 round-off the inclusive check absorbs.
            assertFalse(SensitivityModel.ofCmPer360(
                    914.4 / (0.6144 * 2000) * 0.999, 2000, 90, FovRelativeMode.PHYSICAL)
                    .isInsideVanillaSlider(),
                    "0.999 * 0.744140625 needs s = 1.0003, past the slider top: out of range.");
            assertFalse(SensitivityModel.ofCmPer360(
                    914.4 / (0.0096 * 2000) * 1.001, 2000, 90, FovRelativeMode.PHYSICAL)
                    .isInsideVanillaSlider(),
                    "1.001 * 47.625 needs s = -0.00008, past the slider floor: out of range.");
        }

        @ParameterizedTest(name = "cm360 = {0} @ {1} DPI")
        @CsvSource({
                "0.744140625, 1000",
                "0.744140625, 800",
                "0.744140625, 400",
        })
        void lowerDpiNeverMakesAFastTargetReachable(double targetCmPer360, int mouseDpi) {
            SensitivityModel model = SensitivityModel.ofCmPer360(
                    targetCmPer360, mouseDpi, 90, FovRelativeMode.PHYSICAL);
            assertFalse(model.isInsideVanillaSlider(),
                    "Lowering DPI raises the required slider value; it never helps a fast target.");
        }

        @Test
        @DisplayName("minimum DPI for the Marlow target with a 0.90 slider ceiling is 2527")
        void minDpiForTargetWithCeiling() {
            assertEquals(2527,
                    SensitivityModel.minDpiForCmPer360(0.744140625, 0.90));
            assertEquals(4297,
                    SensitivityModel.minDpiForCmPer360(0.744140625, 0.70));
        }
    }

    @Nested
    @DisplayName("C6.3 DPI changes never change feel")
    class DpiStability {

        @Test
        @DisplayName("withMouseDpi holds cmPer360 constant and moves only the slider value")
        void dpiChangeHoldsCmPer360() {
            SensitivityModel at2000 = SensitivityModel.ofVanillaSlider(
                    1.0, 2000, 90, FovRelativeMode.PHYSICAL);
            SensitivityModel at4000 = at2000.withMouseDpi(4000);

            assertEquals(at2000.cmPer360(), at4000.cmPer360(), 0.0);
            assertEquals(0.744140625, at4000.cmPer360(), 1e-6);
            assertEquals(4000, at4000.mouseDpi());
            assertTrue(at4000.s() < at2000.s(),
                    "Same travel, more counts per inch, means a lower slider value.");
        }

        @Test
        @DisplayName("withCmPer360 changes the target and nothing else")
        void cmPer360ChangeIsIsolated() {
            SensitivityModel base = SensitivityModel.ofCmPer360(
                    6.1, 2000, 90, FovRelativeMode.PHYSICAL);
            SensitivityModel changed = base.withCmPer360(2.71);
            assertEquals(2.71, changed.cmPer360(), 1e-12);
            assertEquals(base.mouseDpi(), changed.mouseDpi());
            assertEquals(base.fovVerticalDeg(), changed.fovVerticalDeg());
            assertEquals(base.fovRelativeMode(), changed.fovRelativeMode());
        }
    }

    @Nested
    @DisplayName("C6.4 FOV-relative mode - the tan rule and its direction")
    class FovRelative {

        @Test
        @DisplayName("70 -> 90 multiplies deg/count by 1.42815")
        void seventyToNinety() {
            assertEquals(1.42815,
                    SensitivityModel.fovRelativeDegPerCountFactor(70, 90), 1e-5);
        }

        @Test
        @DisplayName("90 -> 110 multiplies deg/count by 1.42815 (tan 45 = 1 makes 45 the pivot)")
        void ninetyToOneTen() {
            assertEquals(1.42815,
                    SensitivityModel.fovRelativeDegPerCountFactor(90, 110), 1e-5);
        }

        @ParameterizedTest(name = "{0} -> {1} is x{2}")
        @CsvSource({
                "70,  110, 2.03961",
                "90,  70,  0.70021",
                "80,  90,  1.19175",
                "100, 90,  0.83910",
        })
        void everyContractRow(int fovOldDeg, int fovNewDeg, double expectedFactor) {
            assertEquals(expectedFactor,
                    SensitivityModel.fovRelativeDegPerCountFactor(fovOldDeg, fovNewDeg), 1e-5);
        }

        @ParameterizedTest(name = "widening {0} -> {1} RAISES sensitivity")
        @CsvSource({"70, 90", "80, 90", "90, 110", "70, 110", "60, 100"})
        void wideningFovRaisesSensitivity(int fovOldDeg, int fovNewDeg) {
            double factor = SensitivityModel.fovRelativeDegPerCountFactor(fovOldDeg, fovNewDeg);
            assertTrue(factor > 1.0,
                    "Widening the FOV must RAISE deg/count, not lower it. Factor was " + factor
                            + ". If this fails, the linear rule has been substituted for the tan rule.");
        }

        @ParameterizedTest(name = "narrowing {0} -> {1} LOWERS sensitivity")
        @CsvSource({"90, 70", "90, 80", "110, 90"})
        void narrowingFovLowersSensitivity(int fovOldDeg, int fovNewDeg) {
            assertTrue(SensitivityModel.fovRelativeDegPerCountFactor(fovOldDeg, fovNewDeg) < 1.0);
        }

        @Test
        @DisplayName("the linear rule is a bug and fails every row it is checked against")
        void linearRuleFailsTheContractTable() {
            int failures = 0;
            int rows = 0;
            int[][] wideningPairs = {{70, 90}, {90, 110}, {70, 110}, {80, 90}};
            for (int[] pair : wideningPairs) {
                rows++;
                double linearFactor = linearRuleFactor(pair[0], pair[1]);
                double tanFactor = SensitivityModel.fovRelativeDegPerCountFactor(pair[0], pair[1]);
                if (Math.abs(linearFactor - tanFactor) > 0.01) {
                    failures++;
                }
            }
            assertTrue(failures >= 2,
                    "The contract requires the linear rule to fail at least two rows; it failed "
                            + failures + " of " + rows + ".");
            // The headline error the contract quotes for 70 -> 90.
            assertEquals(0.77778, linearRuleFactor(70, 90), 1e-5);
            assertEquals(-45.5,
                    (linearRuleFactor(70, 90)
                            / SensitivityModel.fovRelativeDegPerCountFactor(70, 90) - 1.0) * 100.0,
                    0.1);
        }

        /**
         * The linear rule, defined exactly as contracts.md C6.4's error column defines it.
         *
         * <p><strong>This exists ONLY so this test can prove the linear rule is wrong.</strong>
         * The real rule is the tan rule, {@code k_new = k_old * tan(fovNew/2) / tan(fovOld/2)},
         * and it is the only one this codebase implements. The linear rule is a
         * Quake/Source {@code cfg_fov}-era heuristic from engines that couple FOV to input;
         * modern vanilla does not, and even inside its own premise tan is the right term.</p>
         *
         * <p>The contract's error column is the definition, and it is unambiguous:
         * "70 -&gt; 90, correct factor 1.42815, linear gives x0.77778, error -45.5%". So the
         * linear multiplier is {@code fovOld / fovNew}, not {@code fovNew / fovOld} - the
         * inversion is what made the earlier draft of this test print 1.2857142857142858
         * (which is 90/70) and then assert 0.77778 against it. Both figures are 90 and 70;
         * only the order was wrong, and the order is the whole claim.</p>
         *
         * <p>Checked against the contract's own rows: 70-&gt;90 gives 0.77778 (error -45.5%),
         * 90-&gt;110 gives 0.81818 (error -42.7%).</p>
         *
         * @param fovOldVerticalDeg the current vertical FOV, in degrees
         * @param fovNewVerticalDeg the requested vertical FOV, in degrees
         * @return the linear rule's multiplier on deg/count
         */
        private static double linearRuleFactor(int fovOldVerticalDeg, int fovNewVerticalDeg) {
            return (double) fovOldVerticalDeg / fovNewVerticalDeg;
        }

        @Test
        @DisplayName("PHYSICAL mode changes nothing when the FOV changes")
        void physicalModeIsInert() {
            SensitivityModel at70 = SensitivityModel.ofVanillaSlider(
                    1.0, 2000, 70, FovRelativeMode.PHYSICAL);
            SensitivityModel at90 = at70.withFov(90);
            assertEquals(at70.cmPer360(), at90.cmPer360(), 0.0);
            assertEquals(at70.s(), at90.s(), 0.0);
            assertEquals(90, at90.fovVerticalDeg());
        }

        @Test
        @DisplayName("SCREEN_SPEED at the Marlow point throws at FOV 90 instead of clamping")
        void screenSpeedThrowsRatherThanClamps() {
            // Arithmetic, from the tan rule (contracts.md C6.4) at the Marlow point:
            //
            //   k_old  = 0.6144 deg/count           (s = 1.0, the slider top)
            //   factor = tan(45) / tan(35) = 1 / 0.7002075 = 1.4281480
            //   k_new  = 0.6144 * 1.4281480           = 0.8774541  deg/count
            //   s_new  = ( cbrt(0.8774541 / 1.2) - 0.2 ) / 0.6
            //          = ( 0.9019091 - 0.2 ) / 0.6    = 1.1681821
            //
            // contracts.md quotes the same k_new ("0.6144 * 1.42815 = 0.877445 deg/count") and
            // then rounds the required slider value to 1.1693 in prose. Its own k_new and the
            // frozen C6.0 inverse disagree at the third decimal, so the required value is
            // asserted here from the formula the contract freezes, at 1e-3.
            SensitivityModel at70 = SensitivityModel.ofVanillaSlider(
                    1.0, 2000, 70, FovRelativeMode.SCREEN_SPEED);
            SensitivityOutOfRangeException thrown =
                    assertThrows(SensitivityOutOfRangeException.class, () -> at70.withFov(90));
            assertEquals(1.16818, thrown.requiredS(), 1e-3);
            assertTrue(thrown.requiredS() > 1.0,
                    "SCREEN_SPEED pushes the Marlow point PAST the top of the slider; that is why "
                            + "it throws instead of clamping.");
            assertTrue(thrown.explanation().contains("SCREEN_SPEED"));
            assertTrue(thrown.minDpiForVanillaRange() > 2000,
                    "The remedy is a DPI increase; a lower DPI cannot reach a faster target.");
        }

        @Test
        @DisplayName("SCREEN_SPEED that stays in range actually raises deg/count")
        void screenSpeedRaisesDegPerCountWhenReachable() {
            SensitivityModel at70 = SensitivityModel.ofVanillaSlider(
                    0.5, 8000, 70, FovRelativeMode.SCREEN_SPEED);
            SensitivityModel at90 = at70.withFov(90);
            assertTrue(at90.degPerCount() > at70.degPerCount());
            assertEquals(1.42815,
                    at90.degPerCount() / at70.degPerCount(), 1e-5);
            assertEquals(0.5, at70.s(), 1e-9);
        }

        @Test
        @DisplayName("withFov to the same FOV is a no-op that preserves the mode")
        void sameFovIsANoOp() {
            SensitivityModel base = SensitivityModel.ofVanillaSlider(
                    0.5, 2000, 70, FovRelativeMode.SCREEN_SPEED);
            SensitivityModel same = base.withFov(70);
            assertEquals(base.cmPer360(), same.cmPer360(), 0.0);
            assertSame(FovRelativeMode.SCREEN_SPEED, same.fovRelativeMode());
        }
    }

    @Nested
    @DisplayName("validation - violations throw, nothing is clamped")
    class Validation {

        @ParameterizedTest(name = "dpi = {0}")
        @ValueSource(ints = {0, -1, -400, Integer.MIN_VALUE})
        void nonPositiveDpiIsRejected(int mouseDpi) {
            XsozContractException thrown = assertThrows(XsozContractException.class,
                    () -> SensitivityModel.ofVanillaSlider(0.5, mouseDpi, 90, FovRelativeMode.PHYSICAL));
            assertTrue(thrown.getMessage().contains("mouseDpi"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("strictly positive"), thrown.getMessage());
        }

        @ParameterizedTest(name = "s = {0}")
        @ValueSource(doubles = {-0.0001, 1.0001, 5.0, -1.0})
        void sensOutsideVanillaSliderIsRejected(double sensRatio) {
            SensitivityOutOfRangeException thrown = assertThrows(SensitivityOutOfRangeException.class,
                    () -> SensitivityModel.ofVanillaSlider(
                            sensRatio, 2000, 90, FovRelativeMode.PHYSICAL));
            assertEquals(sensRatio, thrown.requiredS(), 1e-12);
            assertTrue(thrown.explanation().contains("0.0 to 1.0"), thrown.explanation());
        }

        @ParameterizedTest(name = "fov = {0}")
        @ValueSource(ints = {0, 29, 111, 360, Integer.MIN_VALUE, Integer.MAX_VALUE})
        void fovOutsideTheSaneRangeIsRejected(int fovVerticalDeg) {
            XsozContractException thrown = assertThrows(XsozContractException.class,
                    () -> SensitivityModel.ofVanillaSlider(0.5, 2000, fovVerticalDeg, FovRelativeMode.PHYSICAL));
            assertTrue(thrown.getMessage().contains("fovVerticalDeg"), thrown.getMessage());
        }

        @ParameterizedTest(name = "cm360 = {0}")
        @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY})
        void nonPositiveCmPer360IsRejected(double cmPer360) {
            assertThrows(XsozContractException.class, () -> SensitivityModel.ofCmPer360(
                    cmPer360, 2000, 90, FovRelativeMode.PHYSICAL));
        }

        @Test
        @DisplayName("the boundary FOVs are accepted, only outside them is rejected")
        void boundaryFovsAreAccepted() {
            assertEquals(30, SensitivityModel.ofVanillaSlider(
                    0.5, 2000, 30, FovRelativeMode.PHYSICAL).fovVerticalDeg());
            assertEquals(110, SensitivityModel.ofVanillaSlider(
                    0.5, 2000, 110, FovRelativeMode.PHYSICAL).fovVerticalDeg());
        }

        @Test
        @DisplayName("the boundary slider values are accepted")
        void boundarySliderValuesAreAccepted() {
            assertEquals(0.0, SensitivityModel.ofVanillaSlider(
                    0.0, 2000, 90, FovRelativeMode.PHYSICAL).s(), 1e-12);
            assertEquals(1.0, SensitivityModel.ofVanillaSlider(
                    1.0, 2000, 90, FovRelativeMode.PHYSICAL).s(), 1e-9);
        }

        @Test
        @DisplayName("a null FOV mode is a null-pointer violation, named as such")
        void nullModeIsRejected() {
            assertThrows(NullPointerException.class,
                    () -> SensitivityModel.ofVanillaSlider(0.5, 2000, 90, null));
        }

        @Test
        @DisplayName("a non-positive deg/count has no finite cm/360 and is rejected")
        void nonPositiveDegPerCountIsRejected() {
            assertThrows(XsozContractException.class, () -> SensitivityModel.cmPer360FromDegPerCount(0.0));
            assertThrows(XsozContractException.class, () -> SensitivityModel.cmPer360FromDegPerCount(-1.0));
            assertThrows(XsozContractException.class,
                    () -> SensitivityModel.sFromDegPerCount(Double.NaN));
        }

        @Test
        @DisplayName("a slider ceiling outside (0,1] is rejected")
        void invalidSliderCeilingIsRejected() {
            SensitivityModel base = SensitivityModel.ofVanillaSlider(
                    0.5, 2000, 90, FovRelativeMode.PHYSICAL);
            assertThrows(XsozContractException.class, () -> base.withDpiForTargetS(0.744, 0.0));
            assertThrows(XsozContractException.class, () -> base.withDpiForTargetS(0.744, 1.5));
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equal models are equal and hash the same")
        void equalsAndHashCode() {
            SensitivityModel a = SensitivityModel.ofCmPer360(2.71, 2000, 90, FovRelativeMode.PHYSICAL);
            SensitivityModel b = SensitivityModel.ofCmPer360(2.71, 2000, 90, FovRelativeMode.PHYSICAL);
            SensitivityModel c = SensitivityModel.ofCmPer360(2.72, 2000, 90, FovRelativeMode.PHYSICAL);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertFalse(a.equals(c));
            assertFalse(a.equals(null));
            assertFalse(a.equals("not a model"));
        }

        @Test
        @DisplayName("toString names every field with its unit")
        void toStringNamesUnits() {
            String text = SensitivityModel.ofVanillaSlider(1.0, 2000, 90, FovRelativeMode.PHYSICAL)
                    .toString();
            assertTrue(text.contains("cmPer360"), text);
            assertTrue(text.contains("mouseDpi"), text);
            assertTrue(text.contains("fovVerticalDeg"), text);
            assertTrue(text.contains("mode"), text);
        }
    }
}
