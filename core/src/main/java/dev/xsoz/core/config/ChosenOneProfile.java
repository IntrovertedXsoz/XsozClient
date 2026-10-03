package dev.xsoz.core.config;

import dev.xsoz.core.sensitivity.FovRelativeMode;
import dev.xsoz.core.sensitivity.SensitivityRamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The shipped default profile, {@code chosen-one} (contracts.md C5.7,
 * {@code docs/brief.md} 5, {@code docs/marlowww-profile.md} 1.5).
 *
 * ============================ THE SOURCED VALUES ============================
 *
 * <pre>
 *   FOV                       90          vertical, published
 *   DPI                    2000          published
 *   Sensitivity             100%          published, the top of the vanilla slider
 *     => deg/count         0.6144        = 1.2 * (0.6*1.0 + 0.2)^3
 *     => deg/inch          1228.8        = 0.6144 * 2000
 *     => inch per 360      0.29296875    = 360 / 1228.8
 *     => cm per 360        0.744140625   = 0.29296875 * 2.54      <-- STORED
 *     => mm per 90 deg     1.86          = 0.744140625 / 4 * 10
 * </pre>
 *
 * <p><strong>{@code 0.744140625} is the STORED value and it is a
 * {@code cm/360}</strong> - not a percentage, and never a second number alongside one.
 * {@code [CONTRACT DECISION] D1} (C6.1). A percentage is unintelligible across DPI:
 * "100%" means something different at 400 DPI than at 2000, and storing both the
 * percentage and the DPI is precisely how this project produced a 5.8x error and then a
 * 6.667x one.</p>
 *
 * <p>Every derived number here comes from {@link dev.xsoz.core.sensitivity.SensitivityModel}.
 * This class contains no arithmetic: a second formula in a second place is how the 8.0
 * trap gets walked into twice.</p>
 *
 * ============================ WHY THE RAMP STARTS ACTIVE ============================
 *
 * <p>The profile is created with {@code rampStageIndex == 0} and the ramp <em>active</em>,
 * so the travel the game gets on day one is stage 0's {@code 6.100} cm/360 - the
 * vanilla default's own speed - while the stored target stays
 * {@code 0.744140625}.</p>
 *
 * <p><strong>0.744 cm/360 is unusable on day one.</strong> A 90-degree turn is 1.86 mm of
 * mouse travel; that is a wrist flick, not an aim. C5.7 requires the About page to say
 * that the profile "starts 8.2x slower than her published setting and that closing that
 * gap is a 3-5 week ramp, not a settings change", and a profile that quietly opened at
 * 100% would be that lie in file form.</p>
 *
 * <p>The stage table is <em>not</em> stored here and is not generated: it lives in
 * {@link SensitivityRamp}, because a 1.5x generator anchored at 6.10 lands on 0.5355 and
 * overshoots the target. This profile stores an index into it.</p>
 *
 * ============================ THE PROVENANCE BLOCK ============================
 *
 * <p>A profile named after a real person must carry its citation (C5.2, brief 5). The
 * note states in full that 0.744140625 is <strong>derived</strong> from vanilla's
 * deg-per-count formula and <strong>not published</strong> - which is the single most
 * important sentence in the file, because a derived number quoted as a published one is
 * how a "sourced" profile becomes a rumour.</p>
 *
 * <p><strong>Module ids are dash-separated, not the camelCase of C5.2's
 * example.</strong> C7.7's grammar {@code ^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*$} rejects
 * camelCase outright, and the contract's precedence rule is that a formula beats prose.
 * The example is prose inside a JSON sample; the grammar is the frozen rule.</p>
 *
 * <p>Deeply immutable: {@link #create()} returns a fresh value and nothing here is
 * mutable.</p>
 */
public final class ChosenOneProfile {

    /** The default profile's id. Stable, and the filename stem. */
    public static final String PROFILE_ID = "chosen-one";

    /**
     * The default profile's name, exactly. {@code docs/brief.md} 5 records this as the
     * user's explicit request, and C5.7 honours it while keeping the {@code [ASK]} open.
     */
    public static final String PROFILE_NAME = "Chosen One's Profile";

    /** The published vertical FOV. Unit: degrees. */
    public static final int FOV_VERTICAL_DEG = 90;

    /** The published mouse resolution. Unit: counts per inch. */
    public static final int MOUSE_DPI = 2000;

    /**
     * The stored canonical sensitivity: centimetres of travel for a full 360 degree
     * turn. Unit: centimetres.
     *
     * <p>{@code 0.744140625}, which is {@code 914.4 / (0.6144 * 2000)} exactly. Never
     * rounded to {@code 0.744} in storage: a rounded stored value is a different
     * sensitivity, and the ramp's terminal stage is the unrounded number.</p>
     */
    public static final double CM_PER_360 = 0.744140625d;

    /** The published slider position, kept for the About page. Not stored. Unit: ratio. */
    public static final double PUBLISHED_SLIDER_RATIO = 1.0d;

    /** The date the sourcing videos were read. */
    public static final LocalDate RETRIEVED_ON = LocalDate.of(2026, 9, 29);

    /**
     * The bundled profile's creation instant. Fixed rather than "now", because this is
     * shipped content rather than a user action, and a shipped file whose timestamp moves
     * on every fresh install is a shipped file that cannot be diffed.
     */
    public static final Instant CREATED_UTC = Instant.parse("2026-09-29T00:00:00Z");

    /** The bundled profile's modification instant, identical to its creation instant. */
    public static final Instant MODIFIED_UTC = CREATED_UTC;

    /** The human label for the source of the published numbers. */
    public static final String SOURCE_LABEL = "YouTube @Marlowww video descriptions";

    /** The {@code https} source of the published numbers. */
    public static final String SOURCE_URL = "https://www.youtube.com/@Marlowww/videos";

    /** The bundled profile's compliance profile id: the strictest known behaviour. */
    public static final String COMPLIANCE_PROFILE_ID = Profile.DEFAULT_DENY_COMPLIANCE_PROFILE_ID;

    /**
     * The resource path the profile is copied to on first run (C5.7).
     *
     * <p>Shipped so the store and the packaging agree on one path, and so a test can read
     * the resource and compare it against {@link #create()}. A default profile built in
     * two places is a default profile that will drift.</p>
     */
    public static final String RESOURCE_PATH = "/dev/xsoz/core/profiles/chosen-one.json";

    private static final String NOTE =
            "FOV 90 / DPI 2000 / Sensitivity 100%, published in three video descriptions "
                    + "spanning 2022-10-29 (P1uWDYbbZvk), 2024-10-05 (j1cLw2QnCGw) and "
                    + "2025-06-14 (WC8e5p09xLU). The 0.744140625 cm/360 figure is DERIVED from "
                    + "vanilla's deg-per-count formula, 914.4 / (1.2 * (0.6*1.0 + 0.2)^3 * 2000), "
                    + "and is NOT published. Her keybinds, mouse, polling rate, monitor and every "
                    + "cycle timing are NOT published either, and none of them is guessed here.";

    private ChosenOneProfile() {
        throw new AssertionError("ChosenOneProfile is a factory and is not instantiable.");
    }

    /**
     * @return the shipped default profile: id {@value #PROFILE_ID}, name
     *         {@value #PROFILE_NAME}, {@code isDefault == true}, sensitivity
     *         {@value #CM_PER_360} cm/360 at {@value #MOUSE_DPI} DPI and FOV
     *         {@value #FOV_VERTICAL_DEG}, adaptation ramp active at stage 0
     */
    public static Profile create() {
        return Profile.builder(PROFILE_ID)
                .name(PROFILE_NAME)
                .createdUtc(CREATED_UTC)
                .modifiedUtc(MODIFIED_UTC)
                .isDefault(true)
                .provenance(provenance())
                .modules(defaultModules())
                .keybinds(defaultKeybinds())
                .hud(defaultHud())
                .sensitivity(defaultSensitivity())
                .complianceProfileId(COMPLIANCE_PROFILE_ID)
                .coaching(CoachingState.disarmed())
                .build();
    }

    /**
     * @return the provenance block: {@link ProvenanceKind#PUBLISHED_SETTINGS}, the three
     *         video descriptions, and the statement that the cm/360 figure is derived
     */
    public static Provenance provenance() {
        return Provenance.sourced(ProvenanceKind.PUBLISHED_SETTINGS, SOURCE_LABEL, SOURCE_URL,
                RETRIEVED_ON, NOTE);
    }

    /**
     * @return the sensitivity block: the sourced target, and the adaptation ramp
     *         <em>active</em> at stage 0, so day one is 6.100 cm/360 rather than
     *         0.744 cm/360
     */
    public static ProfileSensitivity defaultSensitivity() {
        return ProfileSensitivity.withRampStage(CM_PER_360, MOUSE_DPI, FOV_VERTICAL_DEG,
                FovRelativeMode.PHYSICAL, SensitivityRamp.START_STAGE_INDEX);
    }

    /**
     * The two module entries C5.2's example carries.
     *
     * <p>Ids are the dash-separated form C7.7 requires, and setting keys are the
     * lower-case-with-dashes form C4.1's grammar requires; C5.2's sample spells both in
     * camelCase, which both grammars reject.</p>
     *
     * @return the module map, in declaration order
     */
    public static Map<String, ModuleState> defaultModules() {
        Map<String, ModuleState> modules = new LinkedHashMap<String, ModuleState>();
        Map<String, Object> totemSettings = new LinkedHashMap<String, Object>();
        totemSettings.put("hud.totem-counter.show-carry-count", Boolean.TRUE);
        totemSettings.put("hud.totem-counter.style", "COMPACT");
        modules.put("hud.totem-counter", ModuleState.of(true, totemSettings));

        Map<String, Object> releaseSettings = new LinkedHashMap<String, Object>();
        // Integer, not Long: the loader resolves a stored number to the boxed type the setting
        // declares, and the shipped profile must equal what the loader produces or the shipped
        // file and the shipped code have drifted.
        releaseSettings.put("latency.crystal-release.release-timeout-millis", Integer.valueOf(1500));
        modules.put("latency.crystal-release", ModuleState.of(false, releaseSettings));
        return Collections.unmodifiableMap(modules);
    }

    /**
     * The two keybinds C5.2's example carries.
     *
     * <p>{@code open-settings} starts on the right shift, code 344, which is GLFW's
     * {@code GLFW_KEY_RIGHT_SHIFT}. The code is written literally rather than looked up
     * from a table: {@code :core} sees no loader, and a table of key names in
     * {@code :core} would be a second source of truth for what a key is.</p>
     *
     * @return the keybind map, in declaration order
     */
    public static Map<String, dev.xsoz.core.keybind.Keybind> defaultKeybinds() {
        Map<String, dev.xsoz.core.keybind.Keybind> keybinds =
                new LinkedHashMap<String, dev.xsoz.core.keybind.Keybind>();
        keybinds.put("hud.totem-counter", dev.xsoz.core.keybind.Keybind.unbound());
        keybinds.put("open-settings", dev.xsoz.core.keybind.Keybind.of(
                dev.xsoz.core.keybind.InputKey.keyboard(344, "RSHIFT")));
        return Collections.unmodifiableMap(keybinds);
    }

    /**
     * @return the HUD layout C5.2's example carries: one element, top right, 8 GUI-scaled
     *         pixels in from each edge
     */
    public static HudLayout defaultHud() {
        List<HudElement> elements = Arrays.asList(HudElement.of(
                "hud.totem-counter", HudAnchor.TOP_RIGHT, -8.0d, -8.0d, 100, 0, true));
        return HudLayout.of(HudLayout.DEFAULT_SCALE, elements);
    }

    /**
     * The About-page sentence C5.7 requires beside the profile.
     *
     * @return one line stating how much slower the profile starts than the published
     *         setting, and how long closing that gap takes
     */
    public static String aboutPageHonestyLine() {
        return "This profile starts at " + defaultSensitivity().effectiveCmPer360()
                + " cm/360, 8.2x slower than the published 0.744140625 cm/360, and closing that "
                + "gap is a " + SensitivityRamp.STAGE_COUNT + "-stage, 3-5 week ramp - not a "
                + "settings change. A profile named after a real person is marketing that invites "
                + "a ban dispute; the citation above travels with the file.";
    }

    /**
     * @return the armed-address set a fresh default profile carries: empty
     */
    public static Set<String> defaultArmedAddresses() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>());
    }

    /**
     * @return the coaching state a fresh default profile carries: disarmed, auto-pruning
     *         on, chat capture off, no mouse debounce
     */
    public static CoachingState defaultCoaching() {
        return CoachingState.of(defaultArmedAddresses(), true, false, OptionalLong.empty());
    }
}
