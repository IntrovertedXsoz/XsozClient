package dev.xsoz.client.trainer;

/**
 * One live coaching callout. Every trainer defines its cues as an enum implementing this, so the
 * text is a closed set: a cue is an instruction (what to DO), never a free-form string built from
 * an opponent's numbers.
 */
public interface CueSpec {
    /** Stable id, used for per-cue toggles and fight logs. */
    String id();

    /** Short imperative title shown large, e.g. "INTERCEPT THE PEARL". */
    String title();

    /** One line of strategy explaining how. */
    String detail();

    /** Border and title colour, ARGB. Each task has its own colour so it can be read at a glance. */
    int color();

    /** Higher wins. 90+ is urgent and may pre-empt the cue on screen. */
    int priority();

    /** What the cue is allowed to look at. Opponent-aware cues only run in full training contexts. */
    Basis basis();

    enum Basis {
        /** Reads only the player's own state (HP, offhand, inventory, armour). Allowed everywhere. */
        OWN_STATE,
        /** Reads only the player's own performance (cycle speed, self-damage). Allowed everywhere. */
        OWN_PERFORMANCE,
        /** Reacts to an opponent action visible on screen. Full training contexts only. */
        VISIBLE_OPPONENT
    }
}
