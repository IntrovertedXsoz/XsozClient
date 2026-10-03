package dev.xsoz.client.update;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release's version, read from its GitHub tag: {@code v0.4.0} is Stable, {@code v0.4.0-dev.2} is
 * Dev, {@code v0.4.0-exp.1} is Experimental. Newer means a higher number first; for the same number
 * Stable beats Dev beats Experimental (a Stable 0.4.0 is what the 0.4.0 tests led up to), then the
 * build number after the dot.
 */
public record Version(int major, int minor, int patch, Channel kind, int build) implements Comparable<Version> {
    private static final Pattern P = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(dev|exp|experimental)(?:\\.?(\\d+))?)?$");

    /** The version in a tag or a mod version ("0.4.0-dev.2+mc1.21.11" works too), or null. */
    public static Version parse(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        int plus = t.indexOf('+');
        if (plus >= 0) t = t.substring(0, plus);
        Matcher m = P.matcher(t);
        if (!m.matches()) return null;
        Channel kind = m.group(4) == null ? Channel.STABLE : m.group(4).equals("dev") ? Channel.DEV : Channel.EXPERIMENTAL;
        int build = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
        return new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), kind, build);
    }

    @Override
    public int compareTo(Version o) {
        if (major != o.major) return Integer.compare(major, o.major);
        if (minor != o.minor) return Integer.compare(minor, o.minor);
        if (patch != o.patch) return Integer.compare(patch, o.patch);
        if (kind != o.kind) return Integer.compare(kind.rank, o.kind.rank);
        return Integer.compare(build, o.build);
    }

    public boolean newerThan(Version o) { return compareTo(o) > 0; }

    /** "0.4.0", "0.4.0 Dev 2", "0.4.0 Experimental 1". */
    public String pretty() {
        String n = major + "." + minor + "." + patch;
        return kind == Channel.STABLE ? n : n + " " + kind.title + (build > 0 ? " " + build : "");
    }

    @Override
    public String toString() { return pretty(); }
}
