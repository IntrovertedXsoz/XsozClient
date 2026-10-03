package dev.xsoz.client.training.bot;

/**
 * How fast and how precise a bot is, and which advanced tricks it knows. Every level knows the
 * basics - crystals, anchors, obsidian, sword hits, shields and axes, mace and elytra, pearls,
 * gapples, mending, surrounds, mining. The advanced tricks unlock with skill, like they do for
 * real players ({@link Tech}).
 *
 * <p>Every level except Hacker plays by a player's rules: 4.5 block reach for blocks, 3 for hits,
 * it can only click what it can see. Hacker plays like a cheater.
 */
public enum BotLevel {
    BEGINNER("Beginner", 650, 0.45, 26, 30, 14f,
            "Slow: 650 ms reactions, a slow head, often a weaker spot, retotems in 1.3 s. Only the basics."),
    CASUAL("Casual", 420, 0.65, 16, 22, 22f,
            "420 ms reactions, decent spots, retotems in 0.8 s. Mines your surround and follows some of your pearls."),
    GOOD("Good", 280, 0.82, 9, 14, 34f,
            "280 ms, good spots, quick retotems. Times crystals to your hurt time, hit-crystals and face-places."),
    PRO("Pro", 170, 0.93, 5, 9, 50f,
            "170 ms, near-best spots. Double-taps with crystals and anchors and follows almost every pearl."),
    GODLIKE("Godlike", 90, 1.0, 2, 5, 80f,
            "90 ms, always the best spot, retotems in 2 ticks, places and breaks a crystal in the same tick."),
    HACKER("Hacker", 50, 1.0, 1, 1, 180f,
            "Cheats like a hacked client: 6 block reach, hits and places through walls, instant everything.");

    /** The advanced tricks, and the first level that knows each. */
    public enum Tech {
        CITY_MINE(CASUAL), PEARL_FOLLOW(CASUAL), HURT_TIMING(GOOD), HIT_CRYSTAL(GOOD), FACE_PLACE(GOOD),
        CRYSTAL_DTAP(PRO), ANCHOR_DTAP(PRO), INSTA_BREAK(GODLIKE);

        final BotLevel from;

        Tech(BotLevel from) { this.from = from; }
    }

    public final String title;
    public final int reactionMs;
    /** Chance to choose the best option rather than a random good one. */
    public final double accuracy;
    /** Ticks to put a new totem in the offhand after a pop (hotbar totem). */
    public final int retotemTicks;
    /** Ticks to move an item from the inventory to the hotbar (inventory open + click). */
    public final int refillTicks;
    /** Degrees the head turns per tick. */
    public final float turnSpeed;
    public final String detail;

    BotLevel(String title, int reactionMs, double accuracy, int retotemTicks, int refillTicks, float turnSpeed, String detail) {
        this.title = title;
        this.reactionMs = reactionMs;
        this.accuracy = accuracy;
        this.retotemTicks = retotemTicks;
        this.refillTicks = refillTicks;
        this.turnSpeed = turnSpeed;
        this.detail = detail;
    }

    public int n() { return ordinal() + 1; }

    public boolean atLeast(BotLevel l) { return ordinal() >= l.ordinal(); }

    public boolean knows(Tech t) { return atLeast(t.from); }

    /** Hacker: ignores a player's limits. */
    public boolean cheats() { return this == HACKER; }

    /** How far it can click a block (to the point it clicks). Vanilla survival: 4.5. */
    public double blockReach() { return cheats() ? 6.0 : 4.5; }

    /** How far it can hit an entity (to its hitbox). Vanilla survival: 3. */
    public double hitReach() { return cheats() ? 6.0 : 3.0; }

    /** Chance it notices and follows your pearl (when it is healthy enough to chase). */
    public double pearlFollowChance() {
        return switch (this) {
            case BEGINNER -> 0.0;
            case CASUAL -> 0.35;
            case GOOD -> 0.6;
            case PRO -> 0.85;
            default -> 1.0;
        };
    }

    public static BotLevel of(int n) { return values()[Math.max(1, Math.min(values().length, n)) - 1]; }
}
