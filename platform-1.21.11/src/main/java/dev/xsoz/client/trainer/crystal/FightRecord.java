package dev.xsoz.client.trainer.crystal;

import java.util.ArrayList;
import java.util.List;

/** A whole recorded fight. Serialised to JSON as-is (records and enums are Gson-friendly). */
public final class FightRecord {
    public static final int SAMPLE_EVERY = 2;

    public enum Outcome { WIN, LOSS, DISENGAGE, OPPONENT_LEFT, UNFINISHED }

    public String id;
    public long startedAtEpochMs;
    public String server;
    public String opponent;
    /** False for practice fights against mobs; only player fights count toward ranks. */
    public boolean opponentIsPlayer;
    public String trainingMode;
    public long startTick;
    public long endTick;
    public Outcome outcome = Outcome.UNFINISHED;
    public final List<FightSample> samples = new ArrayList<>();
    public final List<FightEvent> events = new ArrayList<>();

    public int durationTicks() { return (int) Math.max(0, endTick - startTick); }

    public double durationSeconds() { return durationTicks() / 20.0; }

    public void event(long tick, FightEvent.Type type, double a, double b) {
        events.add(new FightEvent(tick, type, a, b));
    }

    public long count(FightEvent.Type type) {
        long n = 0;
        for (FightEvent e : events) if (e.type() == type) n++;
        return n;
    }
}
