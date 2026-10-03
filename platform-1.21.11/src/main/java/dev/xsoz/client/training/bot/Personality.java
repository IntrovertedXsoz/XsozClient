package dev.xsoz.client.training.bot;

/**
 * How a bot likes to fight. The level decides how well it plays; the personality decides what it
 * reaches for first and how it moves, so two bots of the same level feel like different people.
 */
public enum Personality {
    ALL_ROUNDER("All-rounder", "Uses a bit of everything, nothing in particular.",
            0f, 0f, 0f, 0.0, 9f, false, false, 1.0),
    ANCHOR_MAIN("Anchor main", "Mostly anchors. Loves anchoring you when you sit in a hole.",
            3f, -1f, 0f, 0.0, 9f, false, false, 1.0),
    CRYSTAL_SPAMMER("Crystal spammer", "Mostly crystals, fast. Hates holes: never surrounds and won't walk into one.",
            -3f, 1.5f, 0f, 0.0, 7f, true, false, 1.0),
    HOLE_CAMPER("Hole camper", "Gets into a hole early, fights from it and mines your surround.",
            1f, 0f, 0f, -0.4, 15f, false, true, 0.6),
    RUSHER("Rusher", "Runs at you, hits first, pearls after you and rarely backs off.",
            0f, 0.5f, 2f, -1.0, 6f, false, false, 1.6),
    CAREFUL("Careful", "Keeps its distance, heals and mends early, pearls away when it's losing.",
            0f, 0f, -1f, 1.2, 11f, false, false, 0.7),
    SWORDSMAN("Swordsman", "Likes the sword: crits and combos. Explosives mostly to finish you off.",
            -1.5f, -1.5f, 4f, -0.6, 9f, false, false, 1.2);

    public final String title;
    public final String detail;
    /** Score bonus for anchors in the crystal/anchor planner. */
    public final float anchorBias;
    /** Score bonus for crystals. */
    public final float crystalBias;
    /** How much it prefers melee range (positive) over crystal range. */
    public final float meleeBias;
    /** Blocks added to the distance it likes to keep. */
    public final double spacing;
    /** Health (with absorption) under which it surrounds itself. */
    public final float surroundAt;
    /** Never steps into holes and never surrounds. */
    public final boolean avoidsHoles;
    /** Mines surrounds more readily. */
    public final boolean cities;
    /** How readily it chases pearls (times the level's chance). */
    public final double chase;

    Personality(String title, String detail, float anchorBias, float crystalBias, float meleeBias, double spacing,
                float surroundAt, boolean avoidsHoles, boolean cities, double chase) {
        this.title = title;
        this.detail = detail;
        this.anchorBias = anchorBias;
        this.crystalBias = crystalBias;
        this.meleeBias = meleeBias;
        this.spacing = spacing;
        this.surroundAt = surroundAt;
        this.avoidsHoles = avoidsHoles;
        this.cities = cities;
        this.chase = chase;
    }
}
