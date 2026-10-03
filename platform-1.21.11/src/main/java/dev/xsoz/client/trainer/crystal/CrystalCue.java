package dev.xsoz.client.trainer.crystal;

import dev.xsoz.client.trainer.CueSpec;

/**
 * Every live callout the Crystal Trainer can make. A closed set: titles are instructions, each
 * with its own border colour so the task can be read from the edge of the screen without reading
 * the text. Opponent-aware cues ({@code VISIBLE_OPPONENT}) only fire in full training contexts
 * and only when the opponent (or their pearl) is actually on your screen.
 */
public enum CrystalCue implements CueSpec {
    // ---- own state: allowed everywhere ---------------------------------------------------------
    RETOTEM_NOW("RETOTEM NOW", "Offhand is empty - swap a totem in before anything else",
            0xFFFF4D5E, 100, Basis.OWN_STATE, Group.SURVIVAL),
    DISENGAGE("NO TOTEMS - DISENGAGE", "You are out of totems. Pearl away and reset",
            0xFFFF2E88, 96, Basis.OWN_STATE, Group.SURVIVAL),
    BACK_OFF_SELF("TOO CLOSE - BACK OFF", "Your own crystal hit you. Place on their side, not your feet",
            0xFFFFB020, 82, Basis.OWN_PERFORMANCE, Group.SURVIVAL),
    SAFE_EAT("SAFE WINDOW - EAT", "No pressure on you and you are low. Eat a golden apple now",
            0xFFFFD84D, 60, Basis.OWN_STATE, Group.SURVIVAL),
    REPAIR_ARMOR("MEND YOUR ARMOR", "A piece is under 25%. Create space and throw XP",
            0xFFA98DFF, 58, Basis.OWN_STATE, Group.RESOURCES),
    LOW_CRYSTALS("REFILL CRYSTALS", "Under 8 crystals left on you - refill before you trade",
            0xFFC9A2FF, 44, Basis.OWN_STATE, Group.RESOURCES),
    SPEED_UP("TIGHTEN YOUR CYCLE", "Your last crystals were slow. Break the instant it appears",
            0xFFFFE066, 40, Basis.OWN_PERFORMANCE, Group.TECHNIQUE),

    // ---- opponent-aware strategy: full training contexts only ----------------------------------
    INTERCEPT_PEARL("INTERCEPT THEIR PEARL", "A pearl is in the air - follow it or pearl to where it lands",
            0xFF4FC3FF, 92, Basis.VISIBLE_OPPONENT, Group.READS),
    PUNISH_POP("PUNISH THE POP", "They just popped. Crystal again immediately - don't back off",
            0xFFFF6B3D, 90, Basis.VISIBLE_OPPONENT, Group.PUNISH),
    ANCHOR_BEHIND("ANCHOR BEHIND THEM", "Shield is up - it only blocks the front. Anchor or crystal behind",
            0xFFFF9F1C, 86, Basis.VISIBLE_OPPONENT, Group.READS),
    FLANK_SHIELD("GO AROUND THE SHIELD", "Shield is up and you have no anchors. Strafe and hit the side",
            0xFFFFC14D, 78, Basis.VISIBLE_OPPONENT, Group.READS),
    PUNISH_REPAIR("PUNISH THE REPAIR", "They are mending with XP - push damage before the armor is back",
            0xFF3DDC84, 80, Basis.VISIBLE_OPPONENT, Group.PUNISH),
    PUNISH_EAT("PUNISH THE EAT", "They are eating - they can't fight back. Pressure now",
            0xFF2EC4B6, 72, Basis.VISIBLE_OPPONENT, Group.PUNISH),
    HOLE_FIGHT("FACE-PLACE THE HOLE", "They are surrounded. Crystal at head height or anchor above",
            0xFF8E7DFF, 62, Basis.VISIBLE_OPPONENT, Group.READS),
    MAKE_SPACE("MAKE SPACE", "Too close to crystal safely. Step back to 3-5 blocks",
            0xFFF6CB60, 52, Basis.VISIBLE_OPPONENT, Group.SPACING),
    CLOSE_GAP("CLOSE THE GAP", "Out of crystal range. Walk in or pearl to 4 blocks",
            0xFF63D7C7, 36, Basis.VISIBLE_OPPONENT, Group.SPACING);

    /** Cue groups the player can switch on and off individually. */
    public enum Group {
        SURVIVAL("Survival"), RESOURCES("Resources"), TECHNIQUE("Technique"),
        READS("Reads (pearls, shields, holes)"), PUNISH("Punish windows"), SPACING("Spacing");

        public final String title;

        Group(String title) { this.title = title; }
    }

    private final String title;
    private final String detail;
    private final int color;
    private final int priority;
    private final Basis basis;
    public final Group group;

    CrystalCue(String title, String detail, int color, int priority, Basis basis, Group group) {
        this.title = title;
        this.detail = detail;
        this.color = color;
        this.priority = priority;
        this.basis = basis;
        this.group = group;
    }

    @Override public String id() { return name(); }

    @Override public String title() { return title; }

    @Override public String detail() { return detail; }

    @Override public int color() { return color; }

    @Override public int priority() { return priority; }

    @Override public Basis basis() { return basis; }
}
