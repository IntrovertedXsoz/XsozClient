package dev.xsoz.core.config;

/**
 * Thrown when a delete is refused by one of C5.6's four guards.
 *
 * <p>The message states which guard fired and what the caller must supply, because a
 * refusal the caller cannot act on is a refusal the player cannot explain. Named in the
 * contract.</p>
 */
public class ProfileDeleteRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Returned by {@link #refusalReason()} for the "cannot delete the last profile" guard. */
    public static final String LAST_PROFILE = "last-profile";

    /** Returned by {@link #refusalReason()} when the default is being deleted unreassigned. */
    public static final String DEFAULT_NEEDS_REASSIGNMENT = "default-needs-reassignment";

    /** Returned by {@link #refusalReason()} when the active profile is being deleted unswitched. */
    public static final String ACTIVE_NEEDS_SUCCESSOR = "active-needs-successor";

    private final String profileId;
    private final String refusalReason;

    /**
     * @param profileId     the profile that will not be deleted
     * @param refusalReason one of {@link #LAST_PROFILE}, {@link #DEFAULT_NEEDS_REASSIGNMENT},
     *                      {@link #ACTIVE_NEEDS_SUCCESSOR}
     * @param explanation   what the guard is and what would satisfy it
     */
    public ProfileDeleteRefusedException(String profileId, String refusalReason, String explanation) {
        super("Refused to delete profile \"" + profileId + "\" (" + refusalReason + "): " + explanation);
        this.profileId = profileId;
        this.refusalReason = refusalReason;
    }

    /** @return the profile that will not be deleted */
    public String profileId() {
        return profileId;
    }

    /** @return which of C5.6's guards fired */
    public String refusalReason() {
        return refusalReason;
    }
}
