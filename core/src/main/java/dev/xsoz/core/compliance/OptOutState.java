package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * The server's instruction about one feature, as received on {@code xsoz:opt_out}
 * (contracts.md C7.10).
 *
 * <p>The opt-out is the single most important non-code asset in the product: it is the only
 * mechanism by which a <em>server operator</em>, not the player, gets the last word on each
 * optimising feature. It ships from day one, is always on, and is not configurable off.</p>
 *
 * <p>Deeply immutable. Absence is an {@link Optional}, never a {@code null} string
 * (contracts.md 0.4).</p>
 */
public final class OptOutState {

    private static final OptOutState NOT_OPTED_OUT = new OptOutState(false, null, null, false);

    private final boolean optedOut;
    private final String sourceChannel;
    private final LocalDateTime receivedAt;
    private final boolean appliesToEveryOptimisingFeature;

    private OptOutState(boolean optedOut,
                        String sourceChannel,
                        LocalDateTime receivedAt,
                        boolean appliesToEveryOptimisingFeature) {
        this.optedOut = optedOut;
        this.sourceChannel = sourceChannel;
        this.receivedAt = receivedAt;
        this.appliesToEveryOptimisingFeature = appliesToEveryOptimisingFeature;
    }

    /** @return the state of a feature no server has asked us to disable */
    public static OptOutState notOptedOut() {
        return NOT_OPTED_OUT;
    }

    /**
     * @param sourceChannel where the instruction arrived, e.g. {@code "xsoz:opt_out"} or a
     *                      MOTD control code
     * @param receivedAt    when it arrived
     * @return a per-feature opt-out
     * @throws XsozContractException if {@code sourceChannel} is blank or {@code receivedAt} is
     *                                {@code null}; a refusal with no provenance cannot be
     *                                shown to a player or to staff
     */
    public static OptOutState optedOut(String sourceChannel, LocalDateTime receivedAt) {
        if (sourceChannel == null || sourceChannel.trim().isEmpty()) {
            throw new XsozContractException(
                    "An opt-out must name the channel it arrived on. contracts.md C7.10: the raw "
                            + "bytes of every opt-out are recorded in the session log.");
        }
        if (receivedAt == null) {
            throw new XsozContractException("An opt-out must carry the time it arrived.");
        }
        return new OptOutState(true, sourceChannel, receivedAt, false);
    }

    /**
     * @param sourceChannel where the instruction arrived
     * @param receivedAt    when it arrived
     * @return the blanket "disable every optimising feature" instruction, which is what a
     *         {@code count == 0} payload means
     */
    public static OptOutState optedOutEverything(String sourceChannel, LocalDateTime receivedAt) {
        OptOutState perFeature = optedOut(sourceChannel, receivedAt);
        return new OptOutState(true, perFeature.sourceChannel, perFeature.receivedAt, true);
    }

    /** @return {@code true} when a server has asked us to disable this feature */
    public boolean optedOut() {
        return optedOut;
    }

    /** @return the channel the instruction arrived on; empty when not opted out */
    public Optional<String> sourceChannel() {
        return Optional.ofNullable(sourceChannel);
    }

    /** @return when it arrived; empty when not opted out */
    public Optional<LocalDateTime> receivedAt() {
        return Optional.ofNullable(receivedAt);
    }

    /** @return {@code true} when this is the blanket instruction covering every optimising feature */
    public boolean appliesToEveryOptimisingFeature() {
        return appliesToEveryOptimisingFeature;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OptOutState)) {
            return false;
        }
        OptOutState that = (OptOutState) other;
        return optedOut == that.optedOut
                && appliesToEveryOptimisingFeature == that.appliesToEveryOptimisingFeature
                && Objects.equals(sourceChannel, that.sourceChannel)
                && Objects.equals(receivedAt, that.receivedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(optedOut, sourceChannel, receivedAt, appliesToEveryOptimisingFeature);
    }

    @Override
    public String toString() {
        return optedOut
                ? "OptOutState[OPTED OUT via " + sourceChannel + " at " + receivedAt
                        + (appliesToEveryOptimisingFeature ? " (every optimising feature)" : "") + "]"
                : "OptOutState[not opted out]";
    }
}
