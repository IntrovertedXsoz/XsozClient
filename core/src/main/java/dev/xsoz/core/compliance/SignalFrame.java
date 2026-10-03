package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * The minimal read-only view of one observed inbound signal, as the compliance subsystem
 * needs it (contracts.md C1.5, C7.8 {@code Predictor.yield(SignalFrame)}).
 *
 * <p><strong>This is the C7-local mirror of C1.5's {@code SignalFrame}</strong>, for the same
 * reason as {@link ServerSignal}: the platform package is owned by another agent. Only the
 * fields a yield path can legally read are present - the tick, the monotonic time, and which
 * signal this is. There is no {@code stringExtra}, and there is no packet.</p>
 *
 * <p>Deeply immutable. No live game object crosses this boundary (contracts.md 0.4).</p>
 */
public final class SignalFrame {

    private final long tickIndex;
    private final long clientTimeMillis;
    private final ServerSignal signal;
    private final int entityId;
    private final boolean entityIdPresent;

    /**
     * Builds a frame for a signal that is not about a particular entity.
     *
     * @param tickIndex        the client tick the signal was observed on; must be non-negative
     * @param clientTimeMillis monotonic client time, never wall clock; must be non-negative
     * @param signal           which signal this is
     * @throws XsozContractException if {@code signal} is {@code null} or a time is negative
     */
    public SignalFrame(long tickIndex, long clientTimeMillis, ServerSignal signal) {
        this(tickIndex, clientTimeMillis, signal, OptionalInt.empty());
    }

    /**
     * Builds a frame for a signal that names one entity.
     *
     * @param tickIndex        the client tick the signal was observed on; must be non-negative
     * @param clientTimeMillis monotonic client time, never wall clock; must be non-negative
     * @param signal           which signal this is
     * @param entityId         the entity id, for {@code ENTITY_REMOVED} and
     *                         {@code PLAYER_METADATA}; empty for every other signal
     * @throws XsozContractException if {@code signal} is {@code null} or a time is negative
     */
    public SignalFrame(long tickIndex, long clientTimeMillis, ServerSignal signal, OptionalInt entityId) {
        if (signal == null) {
            throw new XsozContractException("SignalFrame.signal must not be null (contracts.md C1.5).");
        }
        if (tickIndex < 0L) {
            throw new XsozContractException(
                    "SignalFrame.tickIndex must not be negative. Got " + tickIndex + ".");
        }
        if (clientTimeMillis < 0L) {
            throw new XsozContractException(
                    "SignalFrame.clientTimeMillis must not be negative. Got " + clientTimeMillis + ".");
        }
        this.tickIndex = tickIndex;
        this.clientTimeMillis = clientTimeMillis;
        this.signal = signal;
        OptionalInt present = entityId == null ? OptionalInt.empty() : entityId;
        this.entityIdPresent = present.isPresent();
        this.entityId = present.orElse(0);
    }

    /** @return the client tick the signal was observed on */
    public long tickIndex() {
        return tickIndex;
    }

    /** @return the monotonic client time, never wall clock */
    public long clientTimeMillis() {
        return clientTimeMillis;
    }

    /** @return which signal this is */
    public ServerSignal signal() {
        return signal;
    }

    /**
     * @return the entity id, or empty for signals that are not about one entity. Absence is
     *         an {@code OptionalInt} rather than a {@code -1} sentinel (contracts.md 0.4).
     */
    public OptionalInt entityId() {
        return entityIdPresent ? OptionalInt.of(entityId) : OptionalInt.empty();
    }

    /** @return the same event advanced by exactly one tick, for yield-path assertions */
    public SignalFrame advancedOneTick() {
        return new SignalFrame(tickIndex + 1L, clientTimeMillis + 50L, signal, entityId());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SignalFrame)) {
            return false;
        }
        SignalFrame that = (SignalFrame) other;
        return tickIndex == that.tickIndex
                && clientTimeMillis == that.clientTimeMillis
                && signal == that.signal
                && entityIdPresent == that.entityIdPresent
                && (!entityIdPresent || entityId == that.entityId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tickIndex, clientTimeMillis, signal, entityIdPresent,
                entityIdPresent ? Integer.valueOf(entityId) : null);
    }

    @Override
    public String toString() {
        return "SignalFrame[tick=" + tickIndex + " tMillis=" + clientTimeMillis + " " + signal
                + (entityIdPresent ? " entity=" + entityId : "") + "]";
    }
}
