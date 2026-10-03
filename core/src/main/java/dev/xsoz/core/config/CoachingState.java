package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The coaching subsystem's arming state, carried by the profile (contracts.md C5.2,
 * the {@code coaching} object).
 *
 * <p><strong>{@code armedAddresses} is ADD-ONLY in the UI.</strong> The coaching
 * subsystem is the part of this client a player will be asked about by staff, so the
 * rule is "you may arm more servers, never silently disarm the one you are on".
 * Entries may be removed by editing the file, and that is fine: the file is the user's.</p>
 *
 * <p>{@code mouseDebounceMillis} is an {@link OptionalLong}, not a {@code -1} sentinel
 * (contracts.md 0.4): zero is a legal debounce and {@code -1} is a legal attack
 * cooldown percentage elsewhere in the product, so neither can serve as "unset".</p>
 *
 * <p><strong>{@code chatCapture} defaults to false</strong> and is never enabled by a
 * profile load, a migration or an import. A recording switch that a file can flip is not
 * a recording switch the player chose.</p>
 *
 * <p>Deeply immutable.</p>
 */
public final class CoachingState {

    private final Set<String> armedAddresses;
    private final boolean autoPrune;
    private final boolean chatCapture;
    private final OptionalLong mouseDebounceMillis;

    private CoachingState(Set<String> armedAddresses, boolean autoPrune, boolean chatCapture,
                          OptionalLong mouseDebounceMillis) {
        this.armedAddresses = Collections.unmodifiableSet(
                new LinkedHashSet<String>(armedAddresses));
        this.autoPrune = autoPrune;
        this.chatCapture = chatCapture;
        this.mouseDebounceMillis = mouseDebounceMillis;
    }

    /**
     * @param armedAddresses      host literals the coaching subsystem is armed on;
     *                            defensively copied, order preserved
     * @param autoPrune           whether old coaching recordings are pruned automatically
     * @param chatCapture         whether chat is recorded. Only ever {@code true} when the
     *                            player set it themselves.
     * @param mouseDebounceMillis the debounce window. Unit: millis. Absent means "no
     *                            debounce", which is not the same as zero.
     * @return the coaching state
     * @throws XsozContractException if an address is blank or the debounce is negative
     */
    public static CoachingState of(Set<String> armedAddresses, boolean autoPrune,
                                   boolean chatCapture, OptionalLong mouseDebounceMillis) {
        if (armedAddresses == null) {
            throw new XsozContractException(
                    "Coaching arming needs a set, possibly empty. null is not a set.");
        }
        if (armedAddresses.contains(null)) {
            throw new XsozContractException(
                    "Coaching arming contains a null address. \"Armed on no server\" and \"armed "
                            + "on every server\" are different states, and neither is this one.");
        }
        for (String address : armedAddresses) {
            if (address.trim().isEmpty()) {
                throw new XsozContractException(
                        "Coaching arming contains a blank address, which matches nothing and can "
                                + "never be removed from the UI because it has no name.");
            }
        }
        if (mouseDebounceMillis.isPresent() && mouseDebounceMillis.getAsLong() < 0L) {
            throw new XsozContractException(
                    "The mouse debounce cannot be negative. Unit: millis; was "
                            + mouseDebounceMillis.getAsLong() + ".");
        }
        return new CoachingState(armedAddresses, autoPrune, chatCapture, mouseDebounceMillis);
    }

    /** @return the disarmed, no-debounce state a fresh profile starts from */
    public static CoachingState disarmed() {
        return of(Collections.<String>emptySet(), true, false, OptionalLong.empty());
    }

    /** @return the armed host literals, unmodifiable, insertion-ordered */
    public Set<String> armedAddresses() {
        return armedAddresses;
    }

    /**
     * @param host a server host literal, compared case-insensitively
     * @return whether the coaching subsystem is armed on that host
     */
    public boolean isArmedFor(String host) {
        if (host == null) {
            return false;
        }
        String normalised = host.trim().toLowerCase(java.util.Locale.ROOT);
        for (String armed : armedAddresses) {
            if (armed.toLowerCase(java.util.Locale.ROOT).equals(normalised)) {
                return true;
            }
        }
        return false;
    }

    /** @return whether old coaching recordings are pruned automatically */
    public boolean autoPrune() {
        return autoPrune;
    }

    /** @return whether chat recording is on */
    public boolean chatCapture() {
        return chatCapture;
    }

    /**
     * @return the mouse debounce window. Unit: millis. Absent means no debounce, which is
     *         not the same as a zero-millisecond debounce.
     */
    public OptionalLong mouseDebounceMillis() {
        return mouseDebounceMillis;
    }

    /**
     * @param address the host literal to arm
     * @return a copy with that host armed; arming an already-armed host is a no-op
     * @throws XsozContractException if the address is blank
     */
    public CoachingState arm(String address) {
        if (address == null || address.trim().isEmpty()) {
            throw new XsozContractException(
                    "Arming the coaching subsystem needs a host literal; a blank address matches "
                            + "nothing.");
        }
        Set<String> next = new LinkedHashSet<String>(armedAddresses);
        next.add(address.trim().toLowerCase(java.util.Locale.ROOT));
        return of(next, autoPrune, chatCapture, mouseDebounceMillis);
    }

    /**
     * @param newAutoPrune the new auto-prune flag
     * @return a copy with the flag changed
     */
    public CoachingState withAutoPrune(boolean newAutoPrune) {
        return of(armedAddresses, newAutoPrune, chatCapture, mouseDebounceMillis);
    }

    /**
     * @param newChatCapture the new chat-recording flag
     * @return a copy with the flag changed
     */
    public CoachingState withChatCapture(boolean newChatCapture) {
        return of(armedAddresses, autoPrune, newChatCapture, mouseDebounceMillis);
    }

    /**
     * @param newDebounce the new window. Unit: millis. {@link OptionalLong#empty()} clears it.
     * @return a copy with the debounce replaced
     */
    public CoachingState withMouseDebounceMillis(OptionalLong newDebounce) {
        return of(armedAddresses, autoPrune, chatCapture, newDebounce);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CoachingState)) {
            return false;
        }
        CoachingState that = (CoachingState) other;
        return autoPrune == that.autoPrune
                && chatCapture == that.chatCapture
                && armedAddresses.equals(that.armedAddresses)
                && mouseDebounceMillis.equals(that.mouseDebounceMillis);
    }

    @Override
    public int hashCode() {
        int result = armedAddresses.hashCode();
        result = 31 * result + Boolean.valueOf(autoPrune).hashCode();
        result = 31 * result + Boolean.valueOf(chatCapture).hashCode();
        return 31 * result + mouseDebounceMillis.hashCode();
    }

    @Override
    public String toString() {
        return "CoachingState[armed=" + armedAddresses + ", autoPrune=" + autoPrune
                + ", chatCapture=" + chatCapture + ", mouseDebounceMillis=" + mouseDebounceMillis
                + "]";
    }

    /**
     * @param addresses the armed hosts, in any order
     * @return a mutable list copy, for the codec
     */
    static List<String> toList(Set<String> addresses) {
        return new ArrayList<String>(addresses);
    }
}
