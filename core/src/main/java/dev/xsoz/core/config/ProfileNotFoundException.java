package dev.xsoz.core.config;

/**
 * Thrown when a profile id names no profile in the store (contracts.md C5.6).
 *
 * <p>Named in the contract.</p>
 */
public class ProfileNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String profileId;

    /**
     * @param profileId  the id that was not found
     * @param explanation what the caller can do instead
     */
    public ProfileNotFoundException(String profileId, String explanation) {
        super("No profile with id \"" + profileId + "\": " + explanation);
        this.profileId = profileId;
    }

    /** @return the id that was not found */
    public String profileId() {
        return profileId;
    }
}
