package dev.xsoz.client.update;

/** Which releases a player wants: each channel also gets everything from the steadier ones. */
public enum Channel {
    STABLE("Stable", 2, "Finished, tested releases. Recommended."),
    DEV("Dev", 1, "Things that are going into Stable, still being polished. Stable releases too."),
    EXPERIMENTAL("Experimental", 0, "Ideas from suggestions, tried out to see if people like them. Can have bugs, "
            + "and an idea can be taken out again. Dev and Stable releases too.");

    public final String title;
    /** Higher is steadier. */
    public final int rank;
    public final String detail;

    Channel(String title, int rank, String detail) {
        this.title = title;
        this.rank = rank;
        this.detail = detail;
    }

    /** A player on this channel gets releases of this kind. */
    public boolean gets(Channel kind) { return kind.rank >= rank; }

    public static Channel of(String name) {
        for (Channel c : values()) if (c.name().equalsIgnoreCase(name)) return c;
        return STABLE;
    }
}
