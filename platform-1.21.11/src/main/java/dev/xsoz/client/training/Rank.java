package dev.xsoz.client.training;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Crystal PvP ranks. A rank is a claim about what you can do, so it is earned two ways at once:
 *
 * <ul>
 *   <li><b>Tests</b> - passing each tier's Level Up drills at human-limit speed. Passes are
 *   permanent (you never have to re-sit a test).</li>
 *   <li><b>Evidence</b> - from Gold up, recorded fights against real players. These are ROLLING:
 *   they look at your most recent fights, so a rank above Gold reflects how you fight now and can
 *   be lost if your play drops. Fights against mobs never count.</li>
 * </ul>
 * Ranks are cumulative: you hold the highest rank whose requirements, and every lower rank's,
 * are all met right now. Pure Java; unit tested.
 */
public enum Rank {
    ROOKIE("Rookie", 0xFFB8935A),
    COPPER("Copper", 0xFFE07A4F),
    IRON("Iron", 0xFFD8DDE4),
    GOLD("Gold", 0xFFFFD34D),
    LAPIS("Lapis", 0xFF4F7BE8),
    DIAMOND("Diamond", 0xFF5FE3D8),
    NETHERITE("Netherite", 0xFFA48A9E);

    public final String title;
    public final int color;

    Rank(String title, int color) {
        this.title = title;
        this.color = color;
    }

    /** One finished fight, as far as ranking cares. Newest first in lists. */
    public record FightLite(int overall, boolean won, boolean vsPlayer) {
    }

    public record Requirement(String label, boolean met, String progress) {
    }

    /** Everything ranking reads. */
    public record Inputs(TrainingProgress training, List<FightLite> fights, Map<String, Integer> skillLevels, int solidSkills) {
    }

    /** Requirements of THIS rank alone (lower ranks' requirements are checked separately). */
    public List<Requirement> requirements(Inputs in) {
        List<Requirement> out = new ArrayList<>();
        switch (this) {
            case ROOKIE -> out.add(new Requirement("Open the trainer", true, "Done"));
            case COPPER -> passes(out, in, 1);
            case IRON -> passes(out, in, 2);
            case GOLD -> {
                passes(out, in, 3);
                fightMedian(out, in, 3, 50);
            }
            case LAPIS -> {
                passes(out, in, 4);
                fightMedian(out, in, 10, 65);
                skill(out, in, "CYCLE", "Cycle speed", 65);
                skill(out, in, "TOTEM", "Totem discipline", 65);
            }
            case DIAMOND -> {
                fightMedian(out, in, 10, 75);
                winRate(out, in, 20, 10, 50);
                skill(out, in, "CYCLE", "Cycle speed", 75);
                skill(out, in, "TOTEM", "Totem discipline", 75);
                skill(out, in, "SAFETY", "Self-damage control", 75);
                solid(out, in, 2);
            }
            case NETHERITE -> {
                fightMedian(out, in, 15, 85);
                winRate(out, in, 20, 15, 60);
                for (String s : new String[] {"CYCLE", "TOTEM", "SPACING", "SAFETY", "PUNISH", "ADAPT", "TRADING"}) {
                    skill(out, in, s, title(s), 80);
                }
                solid(out, in, 5);
            }
        }
        return out;
    }

    public boolean met(Inputs in) {
        for (Requirement r : requirements(in)) if (!r.met()) return false;
        return true;
    }

    /** The highest rank whose requirements, and those of every rank below it, are all met. */
    public static Rank current(Inputs in) {
        Rank best = ROOKIE;
        for (Rank r : values()) {
            if (!r.met(in)) break;
            best = r;
        }
        return best;
    }

    public Rank next() { return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null; }

    // ------------------------------------------------------------------------------ helpers

    private static void passes(List<Requirement> out, Inputs in, int tier) {
        for (DrillDef d : DrillDef.tier(tier)) {
            boolean ok = in.training().passed(d);
            out.add(new Requirement("Pass " + d.title + " (Level Up)", ok, ok ? "Passed" : "Not passed"));
        }
    }

    private static List<FightLite> playerFights(Inputs in, int n) {
        List<FightLite> out = new ArrayList<>();
        for (FightLite f : in.fights()) {
            if (!f.vsPlayer()) continue;
            out.add(f);
            if (out.size() >= n) break;
        }
        return out;
    }

    static int median(List<FightLite> fs) {
        if (fs.isEmpty()) return 0;
        List<Integer> v = new ArrayList<>();
        for (FightLite f : fs) v.add(f.overall());
        v.sort(Integer::compare);
        int m = v.size() / 2;
        return v.size() % 2 == 1 ? v.get(m) : (v.get(m - 1) + v.get(m)) / 2;
    }

    private static void fightMedian(List<Requirement> out, Inputs in, int n, int min) {
        List<FightLite> fs = playerFights(in, n);
        int med = median(fs);
        boolean ok = fs.size() >= n && med >= min;
        String label = String.format(Locale.ROOT, "Median fight score %d+ over your last %d fights vs players", min, n);
        String prog = fs.size() < n ? fs.size() + "/" + n + " fights recorded" : "Median " + med;
        out.add(new Requirement(label, ok, prog));
    }

    private static void winRate(List<Requirement> out, Inputs in, int window, int minFights, int pct) {
        List<FightLite> fs = playerFights(in, window);
        long wins = fs.stream().filter(FightLite::won).count();
        int rate = fs.isEmpty() ? 0 : (int) Math.round(100.0 * wins / fs.size());
        boolean ok = fs.size() >= minFights && rate >= pct;
        out.add(new Requirement(String.format(Locale.ROOT, "Win %d%%+ of your last %d fights vs players (at least %d)", pct, window, minFights),
                ok, fs.size() < minFights ? fs.size() + "/" + minFights + " fights" : rate + "%"));
    }

    private static void skill(List<Requirement> out, Inputs in, String key, String name, int min) {
        Integer l = in.skillLevels().get(key);
        boolean ok = l != null && l >= min;
        out.add(new Requirement(name + " level " + min + "+", ok, l == null ? "Not measured yet" : "Level " + l));
    }

    private static void solid(List<Requirement> out, Inputs in, int n) {
        out.add(new Requirement(n + "+ skills marked Solid", in.solidSkills() >= n, in.solidSkills() + "/" + n));
    }

    private static String title(String skill) {
        return switch (skill) {
            case "CYCLE" -> "Cycle speed";
            case "TOTEM" -> "Totem discipline";
            case "SPACING" -> "Spacing";
            case "SAFETY" -> "Self-damage control";
            case "PUNISH" -> "Punishing";
            case "ADAPT" -> "Adapting";
            default -> "Trading";
        };
    }
}
