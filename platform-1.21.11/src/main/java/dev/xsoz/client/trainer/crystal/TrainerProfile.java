package dev.xsoz.client.trainer.crystal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The player's crystal PvP ledger, persisted across sessions. Like the Rocket League coach's
 * skill ledger: a skill is only marked "solid" after repeated evidence, and loses that mark after
 * a run of poor fights - it answers "what can I do consistently right now?".
 */
public final class TrainerProfile {
    public static final int RECENT = 10;
    public static final int HISTORY = 200;
    static final double EMA = 0.35;
    static final int SOLID_AT = 80;
    static final int SOLID_MIN_FIGHTS = 3;
    static final int DROP_BELOW = 60;
    static final int DROP_STREAK = 3;

    public int fights;
    public int wins;
    public int losses;
    public final Map<String, SkillTrend> skills = new LinkedHashMap<>();
    public final List<Summary> history = new ArrayList<>();
    public final Map<String, Double> drillBests = new LinkedHashMap<>();
    public final Set<String> practiceServers = new LinkedHashSet<>();

    public static final class SkillTrend {
        public final List<Integer> recent = new ArrayList<>();
        public double smoothed = -1;
        public boolean solid;
        public int poorStreak;
    }

    public record Summary(String id, long epochMs, String opponent, String server, String outcome,
                          int overall, String grade, Map<String, Integer> scores, boolean vsPlayer) {
        public boolean won() { return "WIN".equals(outcome) || "OPPONENT_LEFT".equals(outcome); }
    }

    /** Folds a finished fight in. Returns the skills that changed status ("+Cycle speed", "-Spacing"). */
    public List<String> record(FightRecord fight, FightReport report) {
        List<String> changes = new ArrayList<>();
        fights++;
        if (fight.outcome == FightRecord.Outcome.WIN || fight.outcome == FightRecord.Outcome.OPPONENT_LEFT) wins++;
        if (fight.outcome == FightRecord.Outcome.LOSS) losses++;

        Map<String, Integer> scores = new LinkedHashMap<>();
        for (var e : report.scores.entrySet()) {
            String key = e.getKey().name();
            int score = e.getValue();
            scores.put(key, score);
            SkillTrend t = skills.computeIfAbsent(key, k -> new SkillTrend());
            t.recent.add(score);
            while (t.recent.size() > RECENT) t.recent.remove(0);
            t.smoothed = t.smoothed < 0 ? score : t.smoothed + (score - t.smoothed) * EMA;
            t.poorStreak = score < DROP_BELOW ? t.poorStreak + 1 : 0;
            if (!t.solid && t.recent.size() >= SOLID_MIN_FIGHTS && t.smoothed >= SOLID_AT) {
                t.solid = true;
                changes.add("+" + e.getKey().title);
            } else if (t.solid && t.poorStreak >= DROP_STREAK) {
                t.solid = false;
                changes.add("-" + e.getKey().title);
            }
        }
        history.add(0, new Summary(fight.id, fight.startedAtEpochMs, fight.opponent, fight.server,
                fight.outcome.name(), report.overall, report.grade, scores, fight.opponentIsPlayer));
        while (history.size() > HISTORY) history.remove(history.size() - 1);
        return changes;
    }

    /** Smoothed score for a skill, or -1 when never measured. */
    public int level(Skill s) {
        SkillTrend t = skills.get(s.name());
        return t == null || t.smoothed < 0 ? -1 : (int) Math.round(t.smoothed);
    }

    public boolean solid(Skill s) {
        SkillTrend t = skills.get(s.name());
        return t != null && t.solid;
    }

    /** Average of the last 5 fights' score for a skill minus the 5 before; 0 when not enough data. */
    public int trend(Skill s) {
        List<Integer> xs = new ArrayList<>();
        for (Summary h : history) {
            Integer v = h.scores() == null ? null : h.scores().get(s.name());
            if (v != null) xs.add(v);
            if (xs.size() >= 10) break;
        }
        if (xs.size() < 6) return 0;
        int half = xs.size() / 2;
        double newer = 0, older = 0;
        for (int i = 0; i < half; i++) newer += xs.get(i);
        for (int i = half; i < xs.size(); i++) older += xs.get(i);
        return (int) Math.round(newer / half - older / (xs.size() - half));
    }

    /** The skill to train next: lowest smoothed level among measured skills. */
    public Skill focus() {
        Skill worst = null;
        int worstLevel = 101;
        for (Skill s : Skill.values()) {
            int l = level(s);
            if (l >= 0 && l < worstLevel) {
                worst = s;
                worstLevel = l;
            }
        }
        return worst;
    }

    public int solidCount() {
        int n = 0;
        for (Skill s : Skill.values()) if (solid(s)) n++;
        return n;
    }

    /** Skill name -> smoothed level, for ranking. */
    public java.util.Map<String, Integer> levels() {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (Skill s : Skill.values()) {
            int l = level(s);
            if (l >= 0) m.put(s.name(), l);
        }
        return m;
    }

    public int overallLevel() {
        int n = 0, sum = 0;
        for (Skill s : Skill.values()) {
            int l = level(s);
            if (l >= 0) {
                sum += l;
                n++;
            }
        }
        return n == 0 ? -1 : Math.round((float) sum / n);
    }
}
