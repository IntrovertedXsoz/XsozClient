package dev.xsoz.core.keybind;

/**
 * The validator's answer for one bind request (contracts.md C7.12).
 *
 * <p>Deeply immutable. {@link #reason()} is {@code null} exactly when {@link #allowed()} is
 * {@code true}, which is the one null permitted here: absence of a reason is meaningful,
 * so absence is a boolean rather than a sentinel enum.</p>
 */
public final class BindVerdict {

    private static final BindVerdict ALLOWED =
            new BindVerdict(true, null, "Allowed. Non-combat keys may be rebound freely.");

    private final boolean allowed;
    private final BindRejection reason;
    private final String message;

    private BindVerdict(boolean allowed, BindRejection reason, String message) {
        this.allowed = allowed;
        this.reason = reason;
        this.message = message;
    }

    /** @return the single allowed verdict, {@link #message()} shown verbatim in the editor */
    public static BindVerdict allow() {
        return ALLOWED;
    }

    /**
     * @param reason  the refusal; must not be {@code null}
     * @param message the text shown in the keybind editor, verbatim
     * @return a refusal carrying its reason
     * @throws IllegalArgumentException if {@code reason} is {@code null}
     */
    public static BindVerdict reject(BindRejection reason, String message) {
        if (reason == null) {
            throw new IllegalArgumentException(
                    "A refusal must name a BindRejection. An unnamed refusal is indistinguishable "
                            + "from a validator that simply does not work.");
        }
        return new BindVerdict(false, reason, message);
    }

    /** @return {@code true} when the bind may be installed */
    public boolean allowed() {
        return allowed;
    }

    /** @return why it was refused; {@code null} iff {@link #allowed()} */
    public BindRejection reason() {
        return reason;
    }

    /** @return the text the keybind editor shows, verbatim */
    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return allowed ? "ALLOWED" : "REJECTED[" + reason + "] " + message;
    }
}
