package dev.xsoz.client.training;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted training state: per-drill passes and bests, plus XP. Gson-friendly, pure Java. */
public final class TrainingProgress {
    public final Map<String, DrillRecord> drills = new LinkedHashMap<>();
    public long xp;
    public boolean seenIntro;
    /** The name you picked in the first-launch intro (null: your Minecraft name). */
    public String playerName;
    /** Free Roam ladder: weapon mix title -> highest bot level (1-6) you've killed one of. */
    public java.util.Map<String, Integer> beatenLevels = new java.util.LinkedHashMap<>();
    /** The first-launch intro has been shown (finished or skipped). */
    public boolean introDone;
    /** The last Free Roam setup the player started. */
    public dev.xsoz.client.training.bot.FreeRoamConfig freeRoam;
    /** SkyArena.Depth / Bedrock names: how much ground arenas have under the floor. */
    public String arenaDepth;
    public String arenaBedrock;
    /** Experience.name(), or null until the player has answered. */
    public String experience;

    public Experience experience() { return Experience.parse(experience); }

    public String displayName() { return playerName == null || playerName.isBlank() ? null : playerName.trim(); }

    public dev.xsoz.client.training.session.SkyArena.Depth depth() {
        try {
            return arenaDepth == null ? dev.xsoz.client.training.session.SkyArena.Depth.FULL : dev.xsoz.client.training.session.SkyArena.Depth.valueOf(arenaDepth);
        } catch (IllegalArgumentException e) {
            return dev.xsoz.client.training.session.SkyArena.Depth.FULL;
        }
    }

    public dev.xsoz.client.training.session.SkyArena.Bedrock bedrock() {
        try {
            return arenaBedrock == null ? dev.xsoz.client.training.session.SkyArena.Bedrock.NATURAL : dev.xsoz.client.training.session.SkyArena.Bedrock.valueOf(arenaBedrock);
        } catch (IllegalArgumentException e) {
            return dev.xsoz.client.training.session.SkyArena.Bedrock.NATURAL;
        }
    }

    public static final class DrillRecord {
        public boolean passed;
        public long passedAt;
        public int sessions;
        public int reps;
        public int hits;
        /** Highest difficulty (0..1) reached in Natural mode. */
        public double bestP;
        /** Best Level Up attempt: hits out of levelUpReps. */
        public int bestLevelUpHits;
        /** Last slider position the player used. */
        public double lastP;
        /** Last difficulty level used (1-3). */
        public int lastLevel = 1;
        /** Goal drills (Pearl Aim): fastest time to the goal, in seconds (0 = none yet). */
        public int bestGoalSeconds;
    }

    public DrillRecord get(DrillDef d) { return drills.computeIfAbsent(d.name(), k -> new DrillRecord()); }

    public boolean passed(DrillDef d) {
        DrillRecord r = drills.get(d.name());
        return r != null && r.passed;
    }

    /** True when every prerequisite has been passed. */
    public boolean unlocked(DrillDef d) {
        for (DrillDef p : d.prerequisites()) if (!passed(p)) return false;
        return true;
    }

    /** Removes the pass (the player confirmed the disclaimer). Practice history is kept. */
    public void resetPass(DrillDef d) {
        DrillRecord r = get(d);
        r.passed = false;
        r.passedAt = 0;
        r.bestLevelUpHits = 0;
    }

    public void addXp(long amount) { xp = Math.max(0, xp + amount); }

    /** Level from XP: each level costs 50 XP more than the previous one (lvl 2 = 100, lvl 3 = 250...). */
    public static int level(long xp) {
        int level = 1;
        long need = 100;
        long left = xp;
        while (left >= need) {
            left -= need;
            level++;
            need += 50;
        }
        return level;
    }

    /** XP into the current level and the size of the current level, for a progress bar. */
    public static long[] levelProgress(long xp) {
        long need = 100;
        long left = xp;
        while (left >= need) {
            left -= need;
            need += 50;
        }
        return new long[] {left, need};
    }
}
