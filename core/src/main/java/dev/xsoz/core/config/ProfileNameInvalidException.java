package dev.xsoz.core.config;

/**
 * Thrown when a profile name is unusable: blank, over-long, carrying a character a
 * filename cannot hold, or already taken (contracts.md C5.2, C5.6).
 *
 * <p><strong>Named in the contract, so this is not an invented subclass</strong>
 * (contracts.md 0.4). The name is carried because the settings screen has to point at
 * the row that was refused, not just print a sentence.</p>
 */
public class ProfileNameInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String rejectedName;

    /**
     * @param rejectedName the name as supplied, before trimming
     * @param explanation what is wrong with it and what the caller must do
     */
    public ProfileNameInvalidException(String rejectedName, String explanation) {
        super("Profile name \"" + rejectedName + "\" refused: " + explanation);
        this.rejectedName = rejectedName;
    }

    /** @return the name as supplied, before trimming */
    public String rejectedName() {
        return rejectedName;
    }
}
