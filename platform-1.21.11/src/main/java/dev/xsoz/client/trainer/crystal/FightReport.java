package dev.xsoz.client.trainer.crystal;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The analysis of one fight. Deterministic: the same record always produces the same report. */
public final class FightReport {
    public String fightId;
    public int overall;
    public String grade;
    public String headline;
    /** Skill score 0..100; absent when the fight gave no opportunity to judge that skill. */
    public final Map<Skill, Integer> scores = new EnumMap<>(Skill.class);
    /** Human-readable numbers, in display order. */
    public final Map<String, String> stats = new LinkedHashMap<>();
    public final List<Issue> issues = new ArrayList<>();
    public final List<String> strengths = new ArrayList<>();

    public record Issue(Skill skill, String severity, String title, String whatHappened, String fix, String drill) {
    }

    public static String grade(int score) {
        if (score >= 90) return "S";
        if (score >= 80) return "A";
        if (score >= 65) return "B";
        if (score >= 50) return "C";
        return "D";
    }

    public static int gradeColor(String grade) {
        return switch (grade) {
            case "S" -> 0xFFFFD166;
            case "A" -> 0xFF4CD765;
            case "B" -> 0xFF63D7C7;
            case "C" -> 0xFFFFB25A;
            default -> 0xFFFF6D7E;
        };
    }
}
