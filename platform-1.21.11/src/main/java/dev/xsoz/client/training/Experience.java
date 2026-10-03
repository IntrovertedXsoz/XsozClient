package dev.xsoz.client.training;

/**
 * The player's self-reported experience, asked on first open and editable in Settings. It only
 * changes how the trainer TEACHES (starting speed, hints, default mode, what is recommended, how
 * chatty the live coach is) - never what a pass or a rank requires.
 */
public enum Experience {
    NEW("New to Minecraft", "Start from the very basics: moving, the hotbar, then PvP.",
            0.0, true, true, 2),
    LEARNING("Learning to PvP for the first time", "You can play the game; crystal PvP is new.",
            0.15, true, true, 3),
    REFRESHING("Refreshing my skills", "You've done this before and want to get sharp again.",
            0.4, false, true, 6),
    PRO("Pro", "You fight a lot. Skip the hand-holding; go straight for Level Ups.",
            0.7, false, false, 7);

    public final String title;
    public final String blurb;
    /** Where sliders start for a drill you have never practised (0 = easiest). */
    public final double startP;
    /** Whether drill hints default to on. */
    public final boolean hintsByDefault;
    /** Whether Natural speed-up/slow-down is the default mode (otherwise Fixed). */
    public final boolean naturalByDefault;
    /** How many live-coach cue groups are on by default (survival first, reads last). */
    public final int coachGroups;

    Experience(String title, String blurb, double startP, boolean hintsByDefault, boolean naturalByDefault, int coachGroups) {
        this.title = title;
        this.blurb = blurb;
        this.startP = startP;
        this.hintsByDefault = hintsByDefault;
        this.naturalByDefault = naturalByDefault;
        this.coachGroups = coachGroups;
    }

    public static Experience parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
