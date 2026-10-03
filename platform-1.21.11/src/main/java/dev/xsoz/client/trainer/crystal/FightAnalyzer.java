package dev.xsoz.client.trainer.crystal;

import dev.xsoz.client.trainer.crystal.FightEvent.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link FightRecord} into a {@link FightReport}. Pure Java, no game types, fully
 * deterministic - the RLAI rule: the numbers decide the findings; text only explains them.
 *
 * <p>Thresholds are in ticks (20 per second). They are aimed at a player trying to get good on
 * mainstream crystal servers: a 3-tick place-to-break is excellent, 10+ is slow.</p>
 */
public final class FightAnalyzer {
    /** A cycle longer than this is a crystal you placed and forgot, not a cycle. */
    static final int MAX_CYCLE_TICKS = 40;
    /** A retotem slower than this is counted as "no retotem". */
    static final int MAX_RETOTEM_TICKS = 100;
    static final int PUNISH_WINDOW_TICKS = 40;
    static final int REPAIR_WINDOW_TICKS = 60;
    static final int PEARL_ANSWER_TICKS = 60;
    static final double OPTIMAL_MIN = 2.5;
    static final double OPTIMAL_MAX = 6.0;

    private FightAnalyzer() { }

    public static FightReport analyze(FightRecord r) {
        FightReport rep = new FightReport();
        rep.fightId = r.id;
        List<FightEvent> ev = new ArrayList<>(r.events);
        ev.sort((x, y) -> Long.compare(x.tick(), y.tick()));

        // ---------------------------------------------------------------- cycle speed
        Map<Integer, Long> placed = new HashMap<>();
        List<Integer> cycles = new ArrayList<>();
        for (FightEvent e : ev) {
            if (e.type() == Type.SELF_CRYSTAL_PLACE) placed.put((int) e.a(), e.tick());
            if (e.type() == Type.SELF_CRYSTAL_BREAK) {
                Long t = placed.remove((int) e.a());
                if (t != null) {
                    long d = e.tick() - t;
                    if (d >= 0 && d <= MAX_CYCLE_TICKS) cycles.add((int) d);
                }
            }
        }
        long crystalsPlaced = r.count(Type.SELF_CRYSTAL_PLACE);
        long crystalsBroken = r.count(Type.SELF_CRYSTAL_BREAK);
        double cycleAvg = avg(cycles);
        int cycleBest = cycles.isEmpty() ? -1 : Collections.min(cycles);
        if (cycles.size() >= 3) {
            rep.scores.put(Skill.CYCLE, clamp(100 - (cycleAvg - 2.0) * 9.0));
        }

        // ---------------------------------------------------------------- totem discipline
        List<Integer> retotems = new ArrayList<>();
        int missedRetotems = 0;
        Long emptyAt = null;
        for (FightEvent e : ev) {
            if (e.type() == Type.OFFHAND_EMPTY && e.b() > 0) emptyAt = e.tick();
            if (e.type() == Type.OFFHAND_TOTEM && emptyAt != null) {
                long d = e.tick() - emptyAt;
                if (d <= MAX_RETOTEM_TICKS) retotems.add((int) d);
                else missedRetotems++;
                emptyAt = null;
            }
        }
        if (emptyAt != null) missedRetotems++;
        int nakedSamples = 0;
        int lowHpNaked = 0;
        for (FightSample s : r.samples) {
            if (!s.offhandTotem() && s.totems() > 0) {
                nakedSamples++;
                if (s.hp() + s.absorption() < 10) lowHpNaked++;
            }
        }
        double retotemAvg = avg(retotems);
        int retotemOpps = retotems.size() + missedRetotems;
        if (retotemOpps >= 1) {
            double base = retotems.isEmpty() ? 20 : 100 - (retotemAvg - 2.0) * 6.0;
            base -= missedRetotems * 20;
            base -= lowHpNaked * FightRecord.SAMPLE_EVERY * 0.6;
            rep.scores.put(Skill.TOTEM, clamp(base));
        }

        // ---------------------------------------------------------------- spacing
        int known = 0, good = 0, close = 0, far = 0;
        for (FightSample s : r.samples) {
            if (s.distance() < 0) continue;
            known++;
            if (s.distance() >= OPTIMAL_MIN && s.distance() <= OPTIMAL_MAX) good++;
            else if (s.distance() < 2.0) close++;
            else if (s.distance() > 8.0) far++;
        }
        double goodPct = known == 0 ? 0 : 100.0 * good / known;
        double closePct = known == 0 ? 0 : 100.0 * close / known;
        double farPct = known == 0 ? 0 : 100.0 * far / known;
        if (known >= 30) {
            rep.scores.put(Skill.SPACING, clamp(goodPct * 1.25 - closePct * 0.3));
        }

        // ---------------------------------------------------------------- self damage
        long selfHits = r.count(Type.SELF_DAMAGE);
        double selfHp = 0;
        for (FightEvent e : ev) if (e.type() == Type.SELF_DAMAGE) selfHp += e.a();
        if (crystalsPlaced + r.count(Type.SELF_ANCHOR_BLOW) >= 3) {
            rep.scores.put(Skill.SAFETY, clamp(100 - selfHits * 12 - selfHp * 2.5));
        }

        // ---------------------------------------------------------------- punishing
        int popWindows = 0, popsPunished = 0, repairWindows = 0, repairsPunished = 0, eatWindows = 0, eatsPunished = 0;
        for (FightEvent e : ev) {
            if (e.type() == Type.OPP_POP) {
                popWindows++;
                if (dealtWithin(ev, e.tick(), e.tick() + PUNISH_WINDOW_TICKS)) popsPunished++;
            } else if (e.type() == Type.OPP_XP) {
                repairWindows++;
                if (dealtWithin(ev, e.tick(), e.tick() + REPAIR_WINDOW_TICKS)) repairsPunished++;
            } else if (e.type() == Type.OPP_EAT_START) {
                eatWindows++;
                if (dealtWithin(ev, e.tick(), e.tick() + 32)) eatsPunished++;
            }
        }
        // Repairs come in bursts of bottles: count each burst once.
        repairWindows = Math.min(repairWindows, Math.max(1, burstCount(ev, Type.OPP_XP, 40)));
        repairsPunished = Math.min(repairsPunished, repairWindows);
        int punishOpps = popWindows + repairWindows + eatWindows;
        int punishHits = popsPunished + repairsPunished + eatsPunished;
        if (punishOpps >= 1 && (popWindows > 0 || r.count(Type.OPP_XP) > 0 || eatWindows > 0)) {
            rep.scores.put(Skill.PUNISH, clamp(100.0 * punishHits / punishOpps));
        }

        // ---------------------------------------------------------------- adapting (shield, pearl)
        int shieldWindows = 0, shieldsAnswered = 0;
        Long shieldUp = null;
        for (FightEvent e : ev) {
            if (e.type() == Type.OPP_SHIELD_UP) shieldUp = e.tick();
            if (e.type() == Type.OPP_SHIELD_DOWN && shieldUp != null) {
                if (e.tick() - shieldUp >= 10) {
                    shieldWindows++;
                    if (dealtWithin(ev, shieldUp, e.tick()) || anyWithin(ev, Type.SELF_ANCHOR_BLOW, shieldUp, e.tick())) {
                        shieldsAnswered++;
                    }
                }
                shieldUp = null;
            }
        }
        int pearlWindows = 0, pearlsAnswered = 0;
        for (FightEvent e : ev) {
            if (e.type() != Type.OPP_PEARL_LAND) continue;
            pearlWindows++;
            boolean answered = anyWithin(ev, Type.SELF_PEARL, e.tick() - 20, e.tick() + PEARL_ANSWER_TICKS)
                    || closedDistance(r.samples, e.tick(), e.tick() + PEARL_ANSWER_TICKS)
                    || e.a() <= OPTIMAL_MAX;
            if (answered) pearlsAnswered++;
        }
        int adaptOpps = shieldWindows + pearlWindows;
        if (adaptOpps >= 1) {
            rep.scores.put(Skill.ADAPT, clamp(100.0 * (shieldsAnswered + pearlsAnswered) / adaptOpps));
        }

        // ---------------------------------------------------------------- trading
        long dealt = r.count(Type.DAMAGE_DEALT);
        long taken = r.count(Type.DAMAGE_TAKEN);
        long popsDealt = r.count(Type.OPP_POP);
        long popsTaken = r.count(Type.SELF_POP);
        if (dealt + taken >= 4) {
            double dmgShare = (double) dealt / (dealt + taken);
            double popShare = popsDealt + popsTaken == 0 ? dmgShare : (double) popsDealt / (popsDealt + popsTaken);
            rep.scores.put(Skill.TRADING, clamp((dmgShare * 0.5 + popShare * 0.5) * 100 * 1.15));
        }

        // ---------------------------------------------------------------- overall
        double sum = 0, weights = 0;
        for (var en : rep.scores.entrySet()) {
            double w = switch (en.getKey()) {
                case CYCLE -> 1.4;
                case TOTEM -> 1.4;
                case TRADING -> 1.2;
                case SAFETY -> 1.0;
                case PUNISH -> 1.0;
                case SPACING -> 0.9;
                case ADAPT -> 0.8;
            };
            sum += en.getValue() * w;
            weights += w;
        }
        rep.overall = weights == 0 ? 0 : (int) Math.round(sum / weights);
        if (r.outcome == FightRecord.Outcome.WIN || r.outcome == FightRecord.Outcome.OPPONENT_LEFT) rep.overall = Math.min(100, rep.overall + 4);
        if (r.outcome == FightRecord.Outcome.LOSS) rep.overall = Math.max(0, rep.overall - 4);
        rep.grade = weights == 0 ? "-" : FightReport.grade(rep.overall);

        // ---------------------------------------------------------------- stats
        rep.stats.put("Result", switch (r.outcome) {
            case WIN -> "Won";
            case LOSS -> "Lost";
            case DISENGAGE -> "Disengaged";
            case OPPONENT_LEFT -> "Opponent logged out";
            case UNFINISHED -> "You left";
        });
        rep.stats.put("Duration", String.format(Locale.ROOT, "%.1fs", r.durationSeconds()));
        rep.stats.put("Crystals placed / broken", crystalsPlaced + " / " + crystalsBroken);
        rep.stats.put("Average cycle", cycles.isEmpty() ? "-" : ticks(cycleAvg));
        rep.stats.put("Fastest cycle", cycleBest < 0 ? "-" : ticks(cycleBest));
        rep.stats.put("Average retotem", retotems.isEmpty() ? "-" : ticks(retotemAvg));
        rep.stats.put("Pops dealt / taken", popsDealt + " / " + popsTaken);
        rep.stats.put("Hits dealt / taken", dealt + " / " + taken);
        rep.stats.put("Self-damage", selfHits == 0 ? "None" : String.format(Locale.ROOT, "%d hits, %.1f HP", selfHits, selfHp));
        rep.stats.put("Time in crystal range", known == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", goodPct));
        rep.stats.put("Pops punished", popWindows == 0 ? "-" : popsPunished + " of " + popWindows);
        rep.stats.put("Shields answered", shieldWindows == 0 ? "-" : shieldsAnswered + " of " + shieldWindows);
        rep.stats.put("Pearls answered", pearlWindows == 0 ? "-" : pearlsAnswered + " of " + pearlWindows);
        rep.stats.put("Anchors detonated", String.valueOf(r.count(Type.SELF_ANCHOR_BLOW)));

        // ---------------------------------------------------------------- issues
        List<FightReport.Issue> issues = new ArrayList<>();
        Integer s;
        if ((s = rep.scores.get(Skill.CYCLE)) != null && s < 70) {
            issues.add(new FightReport.Issue(Skill.CYCLE, sev(s), "Your crystal cycle is slow",
                    String.format(Locale.ROOT, "Average place-to-break was %s over %d crystals (best %s). Good players sit at 2-4 ticks.",
                            ticks(cycleAvg), cycles.size(), ticks(cycleBest)),
                    "Put crystals and obsidian next to each other on your hotbar, keep your crosshair on the block, and click break the moment the crystal appears - don't re-aim between place and break.",
                    "Training > Crystal Cycle (Level Up: average 3.2 ticks)."));
        }
        // dead clicks: left-clicks on empty air lock your attacks for a moment
        long misses = r.count(FightEvent.Type.MISS_CLICK);
        long landed = r.count(FightEvent.Type.SELF_CRYSTAL_BREAK) + r.count(FightEvent.Type.MELEE_HIT);
        if (misses >= 6 && misses * 100 / Math.max(1, misses + landed) >= 25) {
            issues.add(new FightReport.Issue(Skill.CYCLE, sev((int) (100 - misses * 100 / (misses + landed))),
                    "Lots of clicks on empty air",
                    String.format(Locale.ROOT, "%d of your %d left-clicks hit nothing (%d%%).", misses, misses + landed, misses * 100 / (misses + landed)),
                    "A left-click that hits nothing stops your attacks for a moment - right when the crystal appears. Keep your crosshair on the block and only click when there's something to hit.",
                    "Training > Crystal Cycle."));
        }
        if ((s = rep.scores.get(Skill.TOTEM)) != null && s < 70) {
            issues.add(new FightReport.Issue(Skill.TOTEM, sev(s), "Your offhand stayed empty too long",
                    String.format(Locale.ROOT, "Retotem averaged %s%s; you spent %.1fs below 5 hearts with no totem in hand.",
                            retotems.isEmpty() ? "-" : ticks(retotemAvg),
                            missedRetotems > 0 ? " and " + missedRetotems + " pop(s) were never re-totemed" : "",
                            lowHpNaked * FightRecord.SAMPLE_EVERY / 20.0),
                    "Bind swap-offhand to a key your finger already rests on, keep a totem in the slot you swap from, and retotem before anything else after a pop - before you crystal again.",
                    "Training > Retotem, then Hotbar Refill."));
        }
        if ((s = rep.scores.get(Skill.SAFETY)) != null && s < 75) {
            issues.add(new FightReport.Issue(Skill.SAFETY, sev(s), "You are hitting yourself",
                    String.format(Locale.ROOT, "%d of your own explosions hit you for %.1f HP.", selfHits, selfHp),
                    "Place crystals on their side of your body, never at your feet. If they are closer than 2 blocks, back up before you place - or switch to sword/anchor.",
                    "Training > Crystal Placement: it scores where you break from."));
        }
        if ((s = rep.scores.get(Skill.SPACING)) != null && s < 65) {
            String why = closePct > farPct
                    ? String.format(Locale.ROOT, "You were closer than 2 blocks %.0f%% of the time - too close to crystal safely.", closePct)
                    : String.format(Locale.ROOT, "You were further than 8 blocks %.0f%% of the time - out of crystal range.", farPct);
            issues.add(new FightReport.Issue(Skill.SPACING, sev(s), "You lost your range", why,
                    "Place crystals from 2.5 to 6 blocks away. Back up when they run at you, and walk or pearl after them when they back away.",
                    "Training > Range Control."));
        }
        if ((s = rep.scores.get(Skill.PUNISH)) != null && s < 65) {
            issues.add(new FightReport.Issue(Skill.PUNISH, sev(s), "You let free damage go",
                    String.format(Locale.ROOT, "Pops punished %d/%d, repairs punished %d/%d, eats punished %d/%d.",
                            popsPunished, popWindows, repairsPunished, repairWindows, eatsPunished, eatWindows),
                    "A pop, an XP bottle or a gapple is a window: they are busy re-totemming, mending or eating. Place your next crystal immediately instead of backing off.",
                    "Live coach: keep Punish cues on and react to the border colour."));
        }
        if ((s = rep.scores.get(Skill.ADAPT)) != null && s < 65) {
            issues.add(new FightReport.Issue(Skill.ADAPT, sev(s), "You didn't adapt to their defence",
                    String.format(Locale.ROOT, "Shield windows answered %d/%d, pearls answered %d/%d.",
                            shieldsAnswered, shieldWindows, pearlsAnswered, pearlWindows),
                    "A raised shield only blocks from the front: anchor or crystal behind them. A pearl means they are moving - pearl after them or walk to the landing spot before they re-set.",
                    "Training > Shield Read and Pearl Aim."));
        }
        if ((s = rep.scores.get(Skill.TRADING)) != null && s < 55) {
            issues.add(new FightReport.Issue(Skill.TRADING, sev(s), "You lost the trades",
                    String.format(Locale.ROOT, "You dealt %d hits and took %d; pops %d dealt vs %d taken.", dealt, taken, popsDealt, popsTaken),
                    "Trade only when your offhand has a totem and you are in range. If you are at low armour or out of totems, disengage and reset instead of trading.",
                    "Review the fight timeline: find the first pop you took and what you were doing."));
        }
        issues.sort((x, y) -> Integer.compare(rep.scores.getOrDefault(x.skill(), 100), rep.scores.getOrDefault(y.skill(), 100)));
        rep.issues.addAll(issues.subList(0, Math.min(3, issues.size())));

        for (var en : rep.scores.entrySet()) {
            if (en.getValue() >= 80) rep.strengths.add(en.getKey().title + " (" + en.getValue() + ")");
        }

        if (rep.scores.isEmpty()) {
            rep.headline = "Too little happened in this fight to grade it.";
        } else if (!rep.issues.isEmpty()) {
            rep.headline = "Biggest gain: " + rep.issues.get(0).title().toLowerCase(Locale.ROOT) + ".";
        } else {
            rep.headline = "Clean fight - nothing below the bar.";
        }
        return rep;
    }

    // ---------------------------------------------------------------- helpers

    static boolean dealtWithin(List<FightEvent> ev, long from, long to) {
        return anyWithin(ev, Type.DAMAGE_DEALT, from, to) || anyWithin(ev, Type.OPP_POP, from + 1, to);
    }

    static boolean anyWithin(List<FightEvent> ev, Type type, long from, long to) {
        for (FightEvent e : ev) if (e.type() == type && e.tick() > from && e.tick() <= to) return true;
        return false;
    }

    static int burstCount(List<FightEvent> ev, Type type, int gap) {
        int n = 0;
        long last = Long.MIN_VALUE / 2;
        for (FightEvent e : ev) {
            if (e.type() != type) continue;
            if (e.tick() - last > gap) n++;
            last = e.tick();
        }
        return n;
    }

    static boolean closedDistance(List<FightSample> samples, long from, long to) {
        for (FightSample s : samples) {
            if (s.tick() > from && s.tick() <= to && s.distance() >= 0 && s.distance() <= OPTIMAL_MAX) return true;
        }
        return false;
    }

    static double avg(List<Integer> xs) {
        if (xs.isEmpty()) return 0;
        double s = 0;
        for (int x : xs) s += x;
        return s / xs.size();
    }

    static int clamp(double v) { return (int) Math.round(Math.max(0, Math.min(100, v))); }

    static String sev(int score) {
        if (score < 45) return "High";
        if (score < 60) return "Medium";
        return "Low";
    }

    static String ticks(double t) {
        return String.format(Locale.ROOT, "%.1f ticks (%d ms)", t, Math.round(t * 50));
    }
}
