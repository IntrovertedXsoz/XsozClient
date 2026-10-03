package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A host-pattern to compliance-profile binding (contracts.md C7.4).
 *
 * <p>A pattern is a literal host, optionally with a single leading {@code "*."} wildcard
 * meaning "this domain and any subdomain of it". The longest matching pattern wins, so
 * {@code eu.mcpvp.club} beats {@code *.mcpvp.club}.</p>
 *
 * <p>Deeply immutable.</p>
 */
public final class HostBinding {

    private final String hostPattern;
    private final String profileId;
    private final String retrievedFrom;
    private final LocalDate learnedOn;

    /**
     * @param hostPattern    the pattern, lower-case, optional leading {@code "*."}
     * @param profileId      the compliance profile a match resolves to
     * @param retrievedFrom  the URL the pattern was learned from; must be {@code https}
     * @param learnedOn      the date it was learned
     * @throws XsozContractException if any argument is absent or the pattern is malformed
     */
    public HostBinding(String hostPattern, String profileId, String retrievedFrom, LocalDate learnedOn) {
        this.hostPattern = normalisePattern(hostPattern);
        if (profileId == null || profileId.trim().isEmpty()) {
            throw new XsozContractException("HostBinding.profileId must not be blank (contracts.md C7.4).");
        }
        this.profileId = profileId;
        if (retrievedFrom == null || !retrievedFrom.startsWith("https://")) {
            throw new XsozContractException(
                    "HostBinding.retrievedFrom must be the https URL the host pattern was learned from. "
                            + "Got " + retrievedFrom + " for pattern " + this.hostPattern);
        }
        this.retrievedFrom = retrievedFrom;
        if (learnedOn == null) {
            throw new XsozContractException(
                    "HostBinding.learnedOn must not be null. A host binding without a date is a "
                            + "claim about a server we never read (contracts.md 0.5).");
        }
        this.learnedOn = learnedOn;
    }

    /** @return the normalised pattern, lower-case, with an optional leading {@code "*."} */
    public String hostPattern() {
        return hostPattern;
    }

    /** @return the profile id a match resolves to */
    public String profileId() {
        return profileId;
    }

    /** @return the https URL the pattern was learned from */
    public String retrievedFrom() {
        return retrievedFrom;
    }

    /** @return the date the pattern was learned */
    public LocalDate learnedOn() {
        return learnedOn;
    }

    /**
     * Whether this binding matches a host.
     *
     * <p>The caller is expected to have already stripped the port and lower-cased the host;
     * {@link #normaliseHost(String)} does both and is the same function the resolver uses.</p>
     *
     * @param host a normalised host
     * @return {@code true} if this pattern matches
     */
    public boolean matches(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (hostPattern.startsWith("*.")) {
            String suffix = hostPattern.substring(1);
            return host.endsWith(suffix);
        }
        return host.equals(hostPattern);
    }

    /**
     * How specific this pattern is; the longest wins (contracts.md C7.4).
     *
     * @return the number of characters in the pattern after the wildcard, which is the
     *         quantity that decides a tie
     */
    public int specificity() {
        return hostPattern.startsWith("*.") ? hostPattern.length() - 2 : hostPattern.length();
    }

    /**
     * Normalises a typed server address into the form {@link #matches} compares against:
     * the port is stripped and the host is lower-cased.
     *
     * @param serverAddress exactly what the player typed, optionally with a port
     * @return the normalised host, possibly empty
     */
    public static String normaliseHost(String serverAddress) {
        if (serverAddress == null) {
            return "";
        }
        String host = serverAddress.trim().toLowerCase(java.util.Locale.ROOT);
        int schemeEnd = host.indexOf("://");
        if (schemeEnd >= 0) {
            host = host.substring(schemeEnd + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && colon < host.length() - 1 && isAllDigits(host.substring(colon + 1))) {
            host = host.substring(0, colon);
        }
        return host;
    }

    private static String normalisePattern(String hostPattern) {
        if (hostPattern == null || hostPattern.trim().isEmpty()) {
            throw new XsozContractException("HostBinding.hostPattern must not be blank (contracts.md C7.4).");
        }
        String pattern = hostPattern.trim().toLowerCase(java.util.Locale.ROOT);
        String bare = pattern.startsWith("*.") ? pattern.substring(2) : pattern;
        if (bare.isEmpty()) {
            throw new XsozContractException(
                    "HostBinding.hostPattern must name a host after the wildcard. Got \"" + hostPattern + "\".");
        }
        for (int i = 0; i < bare.length(); i++) {
            char c = bare.charAt(i);
            boolean legal = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-';
            if (!legal) {
                throw new XsozContractException(
                        "HostBinding.hostPattern may only contain a-z, 0-9, '.' and '-'. Got \""
                                + hostPattern + "\".");
            }
        }
        return pattern;
    }

    private static boolean isAllDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return !text.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HostBinding)) {
            return false;
        }
        HostBinding that = (HostBinding) other;
        return hostPattern.equals(that.hostPattern)
                && profileId.equals(that.profileId)
                && retrievedFrom.equals(that.retrievedFrom)
                && learnedOn.equals(that.learnedOn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hostPattern, profileId, retrievedFrom, learnedOn);
    }

    @Override
    public String toString() {
        return "HostBinding[" + hostPattern + " -> " + profileId + " (learned " + learnedOn
                + " from " + retrievedFrom + ")]";
    }
}
