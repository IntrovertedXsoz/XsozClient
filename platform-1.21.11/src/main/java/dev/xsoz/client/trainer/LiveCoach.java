package dev.xsoz.client.trainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks which single cue is on screen. Same idea as the Rocket League coach's cue chip: candidates
 * are offered every tick, the strongest wins, and hysteresis stops the border from flickering.
 *
 * <ul>
 *   <li>a shown cue stays up at least {@link #MIN_HOLD_TICKS} even if its condition clears;</li>
 *   <li>a different cue only pre-empts it when it is {@link #PREEMPT_MARGIN} priority higher;</li>
 *   <li>after a cue ends it cools down for {@link #COOLDOWN_TICKS} so it cannot strobe.</li>
 * </ul>
 * Pure logic - no game types - so it is unit tested directly.
 */
public final class LiveCoach<C extends CueSpec> {
    public static final int MIN_HOLD_TICKS = 24;
    public static final int MAX_HOLD_TICKS = 80;
    public static final int PREEMPT_MARGIN = 15;
    public static final int COOLDOWN_TICKS = 30;

    private final List<C> offered = new ArrayList<>();
    private final Map<String, Long> cooldownUntil = new HashMap<>();
    private C active;
    private long activeSince;
    private long lastOfferedTick;
    private int shownCount;
    private C lastStarted;

    public void offer(C cue) {
        if (cue != null) offered.add(cue);
    }

    /** Resolves the offers made this tick. Returns the cue to show (may be null). */
    public C resolve(long tick) {
        C best = null;
        for (C c : offered) {
            Long cd = cooldownUntil.get(c.id());
            if (cd != null && cd > tick && c != active) continue;
            if (best == null || c.priority() > best.priority()) best = c;
        }
        boolean activeStillOffered = active != null && offered.contains(active);
        offered.clear();
        lastStarted = null;

        if (active != null) {
            long held = tick - activeSince;
            if (activeStillOffered) lastOfferedTick = tick;
            boolean expired = held >= MAX_HOLD_TICKS
                    || (!activeStillOffered && held >= MIN_HOLD_TICKS && tick - lastOfferedTick >= 4);
            boolean preempt = best != null && best != active && best.priority() >= active.priority() + PREEMPT_MARGIN;
            if (preempt || expired) {
                cooldownUntil.put(active.id(), tick + COOLDOWN_TICKS);
                active = null;
            }
        }

        if (active == null && best != null) {
            Long cd = cooldownUntil.get(best.id());
            if (cd == null || cd <= tick) {
                active = best;
                activeSince = tick;
                lastOfferedTick = tick;
                shownCount++;
                lastStarted = best;
            }
        }
        return active;
    }

    public C active() { return active; }

    /** The cue that became active during the last resolve, or null. */
    public C justStarted() { return lastStarted; }

    public int shownCount() { return shownCount; }

    public void clear() {
        offered.clear();
        active = null;
        lastStarted = null;
    }
}
