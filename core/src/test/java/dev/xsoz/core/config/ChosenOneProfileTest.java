package dev.xsoz.core.config;

import dev.xsoz.core.sensitivity.FovRelativeMode;
import dev.xsoz.core.sensitivity.SensitivityModel;
import dev.xsoz.core.sensitivity.SensitivityRamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The deliverable: the shipped default profile, the sourced values it carries, and the
 * adaptation ramp it exposes.
 *
 * <p>Every number asserted here is asserted against the arithmetic that produced it, not
 * against a remembered constant.</p>
 */
class ChosenOneProfileTest {

    private final Profile chosenOne = ChosenOneProfile.create();

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("the default profile exists and is named EXACTLY \"Chosen One's Profile\"")
        void nameIsExact() {
            assertEquals("Chosen One's Profile", chosenOne.name());
            assertEquals("Chosen One's Profile", ChosenOneProfile.PROFILE_NAME);
            assertEquals("chosen-one", chosenOne.id());
            assertTrue(chosenOne.isDefault(), "C5.7: the shipped default is registered as the default");
        }

        @Test
        @DisplayName("the apostrophe is legal: C5.2 forbids / \\ : * ? \" < > | and nothing else")
        void apostropheIsLegalInAName() {
            ProfileNames.requireValidName("Chosen One's Profile");
            for (String forbidden : new String[] {"/", "\\", ":", "*", "?", "\"", "<", ">", "|"}) {
                assertThrows(ProfileNameInvalidException.class,
                        () -> ProfileNames.requireValidName("bad" + forbidden + "name"),
                        "'" + forbidden + "' must be refused in a profile name");
            }
        }
    }

    @Nested
    @DisplayName("the sourced sensitivity: FOV 90, DPI 2000, 100% == 0.744140625 cm/360")
    class SourcedSensitivity {

        @Test
        @DisplayName("the STORED value is cm/360, and it is exactly 0.744140625")
        void storedValueIsCmPer360() {
            assertEquals(0.744140625d, chosenOne.sensitivity().cmPer360(), 0.0d);
            assertEquals(0.744140625d, ChosenOneProfile.CM_PER_360, 0.0d);
            assertEquals(2000, chosenOne.sensitivity().mouseDpi());
            assertEquals(90, chosenOne.sensitivity().fovVerticalDeg());
            assertEquals(FovRelativeMode.PHYSICAL, chosenOne.sensitivity().fovRelativeMode());
        }

        @Test
        @DisplayName("the arithmetic, shown: 0.8^3 * 1.2 = 0.6144 deg/count, then 914.4 / (0.6144*2000)")
        void theArithmeticIsReproduced() {
            SensitivityModel model = chosenOne.sensitivity().targetModel();
            // deg/count = 1.2 * (0.6 * s + 0.2)^3, with s = 1.0 (the 100% slider top)
            double scaled = 0.6 * ChosenOneProfile.PUBLISHED_SLIDER_RATIO + 0.2;
            assertEquals(0.8d, scaled, 1e-12d);
            assertEquals(0.512d, scaled * scaled * scaled, 1e-12d);
            assertEquals(0.6144d, 1.2d * scaled * scaled * scaled, 1e-12d);
            assertEquals(0.6144d, model.degPerCount(), 1e-9d);
            // deg/inch = deg/count * DPI
            assertEquals(1228.8d, model.degPerInch(), 1e-9d);
            // inch per 360 = 360 / deg-per-inch
            assertEquals(0.29296875d, 360.0d / 1228.8d, 1e-12d);
            // cm per 360 = inch per 360 * 2.54
            assertEquals(0.744140625d, 0.29296875d * 2.54d, 0.0d);
            assertEquals(0.744140625d, model.cmPer360(), 0.0d);
            // a 90 degree turn is a quarter of that, in millimetres
            assertEquals(0.744140625d / 4.0d * 10.0d, model.mmPer90Deg(), 0.0d);
            assertEquals(1.8603515625d, model.mmPer90Deg(), 0.0d,
                    "1.86 mm, quoted to two decimals everywhere a human reads it");
        }

        @Test
        @DisplayName("the derived slider value is 1.0, and it is NOT persisted anywhere")
        void sliderIsDerivedNotStored() {
            assertEquals(1.0d, chosenOne.sensitivity().targetModel().s(), 1e-9d);
            assertTrue(chosenOne.sensitivity().targetModel().isInsideVanillaSlider(),
                    "s = 1.0 is the top of the slider and is inclusive (C6.2)");
            String json = ProfileJson.encode(chosenOne).toJson();
            assertFalse(json.contains("\"s\""), "no bare slider key may be stored");
            assertFalse(json.contains("sensitivityRaw"),
                    "C6.1: a raw slider percentage is never persisted");
            assertFalse(json.contains("\"sens\""), "no short-form slider key may be stored");
        }

        @Test
        @DisplayName("the stored sensitivity object holds exactly the five contract fields")
        void storedSensitivityShape() {
            dev.xsoz.core.setting.JsonObject sensitivity =
                    ProfileJson.encode(chosenOne).getObject("sensitivity");
            assertEquals(5, sensitivity.size());
            assertTrue(sensitivity.has("cmPer360"));
            assertTrue(sensitivity.has("mouseDpi"));
            assertTrue(sensitivity.has("fovVertical"));
            assertTrue(sensitivity.has("fovRelativeMode"));
            assertTrue(sensitivity.has("rampStageIndex"));
        }

        @Test
        @DisplayName("it re-derives the same model from a vanilla slider value, so nothing was re-implemented")
        void agreesWithTheVanillaSliderPath() {
            // 1e-12, not 0.0: the vanilla slider path is (0.6*1.0 + 0.2)^3 = 0.7999999999999999^3
            // in binary floating point, so 0.6144 arrives a few ULP out. The stored value is the
            // exact decimal 0.744140625 and is what the file holds; this assertion is only
            // proving the two paths agree.
            assertEquals(chosenOne.sensitivity().targetModel().cmPer360(),
                    SensitivityModel.ofVanillaSlider(1.0d, 2000, 90, FovRelativeMode.PHYSICAL)
                            .cmPer360(),
                    1e-12d);
        }
    }

    @Nested
    @DisplayName("the adaptation ramp is first-class and exposed on the profile")
    class Ramp {

        @Test
        @DisplayName("the shipped profile has the ramp ACTIVE at stage 0, so day one is 6.10 cm/360")
        void rampIsActiveAtStageZero() {
            assertTrue(chosenOne.sensitivity().rampActive());
            assertEquals(0, chosenOne.sensitivity().rampStageIndex());
            assertEquals(6.100d, chosenOne.sensitivity().currentStage().cmPer360(), 0.0d);
            assertEquals(6.100d, chosenOne.sensitivity().effectiveCmPer360(), 0.0d);
            assertNotEquals(chosenOne.sensitivity().cmPer360(),
                    chosenOne.sensitivity().effectiveCmPer360(),
                    "the target and today's travel are different numbers while a ramp runs; that is "
                            + "the entire point of a ramp");
        }

        @Test
        @DisplayName("the seven stages are EXACTLY the frozen values, ending at 0.744140625")
        void sevenFrozenStages() {
            double[] expected = {6.100d, 4.070d, 2.710d, 1.810d, 1.200d, 0.800d, 0.744140625d};
            assertEquals(7, SensitivityRamp.STAGE_COUNT);
            assertEquals(7, SensitivityRamp.stages().size());
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], SensitivityRamp.STAGES[i].cmPer360(), 0.0d,
                        "stage " + i + " is a frozen literal, not a generated value");
                assertEquals(i, SensitivityRamp.STAGES[i].index());
            }
        }

        @Test
        @DisplayName("the last step is PARTIAL: it stops on the target and does not overshoot it")
        void lastStepIsPartialAndDoesNotOvershoot() {
            assertTrue(SensitivityRamp.isMonotonicDescending());
            // The exact ratios of the FROZEN table, which is the authoritative artefact:
            //   6.100/4.070 = 1.49877   4.070/2.710 = 1.50185   2.710/1.810 = 1.49724
            //   1.810/1.200 = 1.50833   1.200/0.800 = 1.50000   0.800/0.744140625 = 1.07511
            //
            // C6.5's prose says "then 1.5 five times". The LITERAL steps are printed to three
            // decimals, so two of them round the wrong way (1.50185 and 1.50833). Per the
            // contract's own precedence rule a formula beats prose, and here the literal table
            // is the frozen artefact: changing it would move the last stage off the target,
            // which is the one thing D2 exists to prevent. So the assertion below is on what
            // the table actually says, and it is asserted to 1.51 rather than 1.5 so a future
            // edit that pushes a real step further out is still caught.
            double[] expectedRatios = {1.49877d, 1.50185d, 1.49724d, 1.50833d, 1.5d, 1.07511d};
            for (int i = 1; i < SensitivityRamp.STAGE_COUNT; i++) {
                double ratio = SensitivityRamp.stageRatio(i);
                assertTrue(ratio >= 1.0d,
                        "stage " + i + " ratio " + ratio + " is below 1.0, so the table ascends");
                assertTrue(ratio <= 1.51d,
                        "stage " + i + " ratio " + ratio + " is beyond the 1.5 nominal step by more "
                                + "than the three-decimal rounding of the literal table can explain");
                assertEquals(expectedRatios[i - 1], ratio, 1e-4d);
            }
            double lastRatio = SensitivityRamp.stageRatio(6);
            assertTrue(lastRatio < 1.5d,
                    "the final ratio is " + lastRatio + "; a 1.5x step would land on 0.5355, which "
                            + "is FASTER than the 0.744140625 target. The step must be partial.");
            assertEquals(0.744140625d, SensitivityRamp.terminalStage().cmPer360(), 0.0d);
            assertEquals(0.744140625d, ChosenOneProfile.CM_PER_360, 0.0d);
            // The arithmetic that a generator gets wrong, shown so it cannot be repeated:
            //   0.800 / 1.5 = 0.53333... , which is FASTER (lower cm/360) than the target
            double generated = 0.800d / 1.5d;
            assertTrue(generated < 0.744140625d,
                    "a 1.5x generator from 0.800 lands on " + generated + ", which is faster than "
                            + "the target. This is exactly why the table is baked.");
        }

        @Test
        @DisplayName("every stage is reachable inside the vanilla slider at the profile's DPI")
        void everyStageIsInsideTheSlider() {
            for (dev.xsoz.core.sensitivity.RampStage stage : SensitivityRamp.stages()) {
                SensitivityModel model = SensitivityModel.ofCmPer360(
                        stage.cmPer360(), 2000, 90, FovRelativeMode.PHYSICAL);
                assertTrue(model.isInsideVanillaSlider(),
                        "stage " + stage.index() + " at " + stage.cmPer360()
                                + " cm/360 needs s = " + model.s() + ", outside the vanilla slider");
            }
        }

        @Test
        @DisplayName("advancing walks the table and clamps at the target, never past it")
        void advanceClampsAtTheTarget() {
            ProfileSensitivity block = chosenOne.sensitivity();
            double[] seen = new double[7];
            ProfileSensitivity cursor = block;
            seen[0] = cursor.effectiveCmPer360();
            for (int i = 0; i < 6; i++) {
                cursor = cursor.advanced();
                seen[i + 1] = cursor.effectiveCmPer360();
                assertEquals(i + 1, cursor.rampStageIndex());
            }
            assertEquals(6.100d, seen[0], 0.0d);
            assertEquals(4.070d, seen[1], 0.0d);
            assertEquals(2.710d, seen[2], 0.0d);
            assertEquals(1.810d, seen[3], 0.0d);
            assertEquals(1.200d, seen[4], 0.0d);
            assertEquals(0.800d, seen[5], 0.0d);
            assertEquals(0.744140625d, seen[6], 0.0d);
            assertEquals(0, cursor.remainingStages());
            // Already terminal: advancing again changes nothing and never overshoots.
            assertEquals(0.744140625d, cursor.advanced().effectiveCmPer360(), 0.0d);
            assertEquals(0.744140625d, cursor.advanced().advanced().effectiveCmPer360(), 0.0d);
        }

        @Test
        @DisplayName("deactivating the ramp puts the stored target in force from today")
        void rampCanBeDeactivated() {
            ProfileSensitivity off = chosenOne.sensitivity().withRampInactive();
            assertFalse(off.rampActive());
            assertEquals(-1, off.rampStageIndex());
            assertEquals(0.744140625d, off.effectiveCmPer360(), 0.0d);
            assertEquals(chosenOne.sensitivity().cmPer360(), off.cmPer360(), 0.0d);
            assertFalse(off.optCurrentStage().isPresent());
        }

        @Test
        @DisplayName("a ramp stage index outside the table is refused")
        void stageIndexIsBounded() {
            assertThrows(dev.xsoz.core.XsozContractException.class,
                    () -> chosenOne.sensitivity().withRampStageIndex(7));
            assertThrows(dev.xsoz.core.XsozContractException.class,
                    () -> chosenOne.sensitivity().withRampStageIndex(-2));
            assertThrows(dev.xsoz.core.XsozContractException.class,
                    () -> ProfileSensitivity.withRampStage(0.744140625d, 2000, 90,
                            FovRelativeMode.PHYSICAL, 7));
        }

        @Test
        @DisplayName("the block re-derives nothing: every number comes from SensitivityModel")
        void noNewMaths() {
            ProfileSensitivity shifted = chosenOne.sensitivity().withMouseDpi(4000);
            assertEquals(0.744140625d, shifted.cmPer360(), 0.0d,
                    "C6.3: a DPI change holds cm/360 constant and only recomputes the slider");
            // C6.1's table, reproduced. Holding 0.744140625 cm/360 at 4000 DPI needs
            //   deg/count = 914.4 / (0.744140625 * 4000) = 914.4 / 2976.5625 = 0.3072,
            // which is 0.6144 / 2 because 4000 DPI is double 2000 DPI: more counts per inch
            // means fewer DEGREES needed for the same physical distance. The slider value
            // for 0.3072 deg/count is 0.724934, and C6.1 lists "4000 -> 0.724934" for exactly
            // this reason.
            assertEquals(0.3072d, shifted.targetModel().degPerCount(), 1e-9d);
            assertEquals(0.6144d / 2.0d, shifted.targetModel().degPerCount(), 1e-9d);
            assertEquals(0.724934d, shifted.targetModel().s(), 1e-6d);
            assertTrue(shifted.targetModel().isInsideVanillaSlider(),
                    "which is the point of the DPI advice: a faster target needs MORE dpi");
        }
    }

    @Nested
    @DisplayName("provenance travels with the data")
    class ProvenanceBlock {

        @Test
        @DisplayName("the citation is complete, https, and dated")
        void citationIsComplete() {
            Provenance provenance = chosenOne.provenance();
            assertEquals(ProvenanceKind.PUBLISHED_SETTINGS, provenance.kind());
            assertEquals("YouTube @Marlowww video descriptions", provenance.sourceLabel());
            assertEquals("https://www.youtube.com/@Marlowww/videos", provenance.sourceUrl());
            assertEquals(LocalDate.of(2026, 9, 29), provenance.retrievedOn());
        }

        @Test
        @DisplayName("the note says the 0.744140625 is DERIVED and not published")
        void noteStatesDerivedNotPublished() {
            String note = chosenOne.provenance().note();
            assertTrue(note.contains("DERIVED"), note);
            assertTrue(note.contains("NOT published"), note);
            assertTrue(note.contains("0.744140625"), note);
            assertTrue(note.contains("NOT published either"), note);
        }

        @Test
        @DisplayName("a sourced profile with no URL, no date or a non-https URL cannot be built")
        void sourcedProvenanceIsEnforced() {
            assertThrows(dev.xsoz.core.XsozContractException.class, () -> Provenance.sourced(
                    ProvenanceKind.PUBLISHED_SETTINGS, "label", "http://example.com",
                    LocalDate.of(2026, 9, 29), "note"));
            assertThrows(dev.xsoz.core.XsozContractException.class, () -> Provenance.sourced(
                    ProvenanceKind.PUBLISHED_SETTINGS, "label", "https://example.com", null, "note"));
            assertThrows(dev.xsoz.core.XsozContractException.class, () -> Provenance.sourced(
                    ProvenanceKind.USER_DEFINED, "label", "https://example.com",
                    LocalDate.of(2026, 9, 29), "note"));
            assertThrows(dev.xsoz.core.XsozContractException.class, () -> Provenance.sourced(
                    ProvenanceKind.PUBLISHED_SETTINGS, "  ", "https://example.com",
                    LocalDate.of(2026, 9, 29), "note"));
        }

        @Test
        @DisplayName("a USER_DEFINED profile needs no source, and absence is Optional not \"\"")
        void userDefinedNeedsNoSource() {
            Provenance mine = Provenance.userDefined("My own numbers");
            assertEquals(ProvenanceKind.USER_DEFINED, mine.kind());
            assertFalse(mine.optSourceUrl().isPresent());
            assertFalse(mine.optRetrievedOn().isPresent());
            assertThrows(dev.xsoz.core.XsozContractException.class, mine::sourceUrl);
        }

        @Test
        @DisplayName("a profile with no provenance cannot be built at all")
        void provenanceIsMandatory() {
            assertThrows(dev.xsoz.core.XsozContractException.class,
                    () -> Profile.builder("no-source").name("No source")
                            .createdUtc(ChosenOneProfile.CREATED_UTC)
                            .modifiedUtc(ChosenOneProfile.CREATED_UTC)
                            .sensitivity(chosenOne.sensitivity())
                            .build());
        }
    }

    @Nested
    @DisplayName("the shipped resource and the code agree, byte for byte")
    class ShippedResource {

        @Test
        @DisplayName("core/src/main/resources/.../chosen-one.json decodes to exactly this profile")
        void resourceMatchesTheCode() throws Exception {
            String text = readResource();
            Profile fromResource = ProfileJson.decode(
                    dev.xsoz.core.setting.JsonObject.parse(text));
            assertEquals(chosenOne, fromResource,
                    "the shipped file and ChosenOneProfile.create() have drifted. One of them is "
                            + "the source of truth and it cannot be both.");
            assertEquals(ProfileJson.encode(chosenOne).toJson(), text,
                    "the shipped file is not in canonical key order");
        }

        @Test
        @DisplayName("the shipped file declares schema 1 and a default-deny compliance profile")
        void resourceDeclaresTheContract() throws Exception {
            dev.xsoz.core.setting.JsonObject document = dev.xsoz.core.setting.JsonObject
                    .parse(readResource());
            assertEquals(1, document.getInt("schema"));
            assertEquals("default-deny", document.getString("complianceProfileId"));
            assertTrue(document.getBoolean("isDefault"));
            assertEquals("Chosen One's Profile", document.getString("name"));
        }

        private String readResource() throws Exception {
            try (InputStream in = ChosenOneProfileTest.class
                    .getResourceAsStream(ChosenOneProfile.RESOURCE_PATH)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "The bundled resource " + ChosenOneProfile.RESOURCE_PATH
                                    + " is missing from the classpath. C5.7 requires it to be "
                                    + "copied to config/profiles/chosen-one.json on first run.");
                }
                byte[] bytes = new byte[8192];
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                int read = in.read(bytes);
                while (read > 0) {
                    buffer.write(bytes, 0, read);
                    read = in.read(bytes);
                }
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    @Nested
    @DisplayName("the rest of the profile")
    class Rest {

        @Test
        @DisplayName("the compliance profile is default-deny, the strictest known behaviour")
        void complianceIsDefaultDeny() {
            assertEquals("default-deny", chosenOne.complianceProfileId());
        }

        @Test
        @DisplayName("coaching is disarmed, chat capture is off, and no debounce is set")
        void coachingIsDisarmed() {
            assertFalse(chosenOne.coaching().armedAddresses().iterator().hasNext());
            assertTrue(chosenOne.coaching().autoPrune());
            assertFalse(chosenOne.coaching().chatCapture());
            assertFalse(chosenOne.coaching().mouseDebounceMillis().isPresent());
        }

        @Test
        @DisplayName("module ids are the dash-separated form C7.7 requires, not C5.2's camelCase")
        void moduleIdsFollowC77() {
            assertTrue(chosenOne.modules().containsKey("hud.totem-counter"));
            assertTrue(chosenOne.modules().containsKey("latency.crystal-release"));
            assertFalse(chosenOne.modules().containsKey("hud.totemCounter"),
                    "C7.7's grammar ^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$ rejects camelCase");
        }

        @Test
        @DisplayName("the HUD element z and order are inside C5.2's ranges")
        void hudRangesHold() {
            dev.xsoz.core.config.HudElement element = chosenOne.hud().elements().get(0);
            assertEquals("hud.totem-counter", element.id());
            assertEquals(HudAnchor.TOP_RIGHT, element.anchor());
            assertEquals(100, element.z());
            assertEquals(0, element.order());
            assertTrue(element.offsetXGu() < 0.0d && element.offsetYGu() < 0.0d);
            assertEquals(1.0d, chosenOne.hud().scale(), 0.0d);
        }

        @Test
        @DisplayName("the profile is deeply immutable: the collections it hands out are unmodifiable")
        void immutable() {
            assertThrows(UnsupportedOperationException.class, () -> chosenOne.modules().clear());
            assertThrows(UnsupportedOperationException.class, () -> chosenOne.keybinds().clear());
            assertThrows(UnsupportedOperationException.class,
                    () -> chosenOne.hud().elements().clear());
            assertThrows(UnsupportedOperationException.class,
                    () -> chosenOne.coaching().armedAddresses().clear());
            assertThrows(UnsupportedOperationException.class,
                    () -> chosenOne.modules().get("hud.totem-counter").settings().clear());
        }
    }
}
