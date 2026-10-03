package dev.xsoz.client.training;

/** Flags shared between server-side drills and client modules, in a class with no client references. */
public final class TrainingFlags {
    /** Selection-only drills: Combo Binds keys select their slot but do not use the item. */
    public static volatile boolean selectOnly;

    /** Keybind Reflex: Combo Binds are off - the drill trains the player's own Minecraft hotbar keys. */
    public static volatile boolean combosOff;

    /**
     * Test mode (developer code in Settings > Profile): every drill unlocked, nothing saved - no
     * passes, XP, stats or fights. Lives only in memory, so it ends when the game closes.
     */
    public static volatile boolean testMode;

    /** The code that turns test mode on. */
    public static final String TEST_CODE = "test";

    /** Unlock check that honours test mode. */
    public static boolean unlocked(TrainingProgress p, DrillDef d) { return testMode || p.unlocked(d); }

    private TrainingFlags() { }
}
