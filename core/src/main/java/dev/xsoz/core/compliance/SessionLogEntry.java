package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One line of the append-only, hash-chained session log (contracts.md C7.9).
 *
 * <p>The log is what makes a staff conversation survivable: profile id and citation, feature
 * state at join, every opt-out with its raw bytes, every prediction later contradicted by a
 * server packet, and every block placement made while a prediction was pending. It exports
 * to a single text file, because a Discord ticket is where appeals are actually handled.</p>
 *
 * <p>Each entry hashes its predecessor's hash, so removing or editing an earlier line breaks
 * every hash after it. That is the whole point: a log that can be silently edited is not
 * evidence.</p>
 */
public final class SessionLogEntry {

    /** The hash of the first entry's predecessor, so the chain has a fixed genesis value. */
    public static final String GENESIS_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private final long sequenceNumber;
    private final long tickIndex;
    private final long clientTimeMillis;
    private final String kind;
    private final String detail;
    private final String previousHash;
    private final String hash;

    private SessionLogEntry(long sequenceNumber, long tickIndex, long clientTimeMillis, String kind,
                            String detail, String previousHash, String hash) {
        this.sequenceNumber = sequenceNumber;
        this.tickIndex = tickIndex;
        this.clientTimeMillis = clientTimeMillis;
        this.kind = kind;
        this.detail = detail;
        this.previousHash = previousHash;
        this.hash = hash;
    }

    /**
     * Appends the next entry to a chain.
     *
     * @param previous        the last entry, or {@code null} for the first
     * @param tickIndex       the client tick
     * @param clientTimeMillis monotonic client time
     * @param kind            a short machine-readable event kind, e.g. {@code "PROFILE_RESOLVED"}
     * @param detail          the human line; must never contain the value of a {@code sensitive}
     *                        setting (contracts.md 0.5)
     * @return the appended entry
     * @throws XsozContractException if {@code kind} is blank, or if {@code previous} is not the
     *                                tail of a well-formed chain
     */
    public static SessionLogEntry append(SessionLogEntry previous, long tickIndex, long clientTimeMillis,
                                         String kind, String detail) {
        if (kind == null || kind.trim().isEmpty()) {
            throw new XsozContractException("A session log entry needs a kind. \"entry\" is not a kind.");
        }
        long sequence = previous == null ? 0L : previous.sequenceNumber + 1L;
        String previousHash = previous == null ? GENESIS_HASH : previous.hash;
        String detailText = detail == null ? "" : detail;
        String hash = sha256Hex(sequence + "|" + tickIndex + "|" + clientTimeMillis + "|"
                + kind + "|" + detailText + "|" + previousHash);
        return new SessionLogEntry(sequence, tickIndex, clientTimeMillis, kind, detailText,
                previousHash, hash);
    }

    /** @return the zero-based position in the chain */
    public long sequenceNumber() {
        return sequenceNumber;
    }

    /** @return the client tick the entry was written on */
    public long tickIndex() {
        return tickIndex;
    }

    /** @return monotonic client time, never wall clock */
    public long clientTimeMillis() {
        return clientTimeMillis;
    }

    /** @return the machine-readable event kind */
    public String kind() {
        return kind;
    }

    /** @return the human line */
    public String detail() {
        return detail;
    }

    /** @return the predecessor's hash, or {@link #GENESIS_HASH} for the first entry */
    public String previousHash() {
        return previousHash;
    }

    /** @return this entry's SHA-256, over its own content and its predecessor's hash */
    public String hash() {
        return hash;
    }

    /**
     * Verifies a whole chain. Any edited, reordered, removed or inserted line fails here.
     *
     * @param entries the chain, oldest first
     * @return {@code true} if every hash matches its content and its predecessor
     */
    public static boolean verifyChain(List<SessionLogEntry> entries) {
        if (entries == null) {
            return false;
        }
        SessionLogEntry previous = null;
        for (int i = 0; i < entries.size(); i++) {
            SessionLogEntry entry = entries.get(i);
            if (entry.sequenceNumber != i) {
                return false;
            }
            String expectedPrevious = previous == null ? GENESIS_HASH : previous.hash;
            if (!expectedPrevious.equals(entry.previousHash())) {
                return false;
            }
            String recomputed = sha256Hex(entry.sequenceNumber + "|" + entry.tickIndex + "|"
                    + entry.clientTimeMillis + "|" + entry.kind + "|" + entry.detail + "|"
                    + entry.previousHash());
            if (!recomputed.equals(entry.hash())) {
                return false;
            }
            previous = entry;
        }
        return true;
    }

    /**
     * @param entries the chain, oldest first
     * @return an immutable copy
     */
    public static List<SessionLogEntry> immutable(List<SessionLogEntry> entries) {
        return Collections.unmodifiableList(new ArrayList<SessionLogEntry>(entries));
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new XsozContractException(
                    "SHA-256 is required by every JVM and its absence is a broken platform, not a "
                            + "recoverable condition.", e);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionLogEntry)) {
            return false;
        }
        SessionLogEntry that = (SessionLogEntry) other;
        return sequenceNumber == that.sequenceNumber
                && tickIndex == that.tickIndex
                && clientTimeMillis == that.clientTimeMillis
                && kind.equals(that.kind)
                && detail.equals(that.detail)
                && previousHash.equals(that.previousHash)
                && hash.equals(that.hash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequenceNumber, tickIndex, clientTimeMillis, kind, detail, previousHash, hash);
    }

    @Override
    public String toString() {
        return "#" + sequenceNumber + " t=" + tickIndex + " " + kind + ": " + detail
                + " sha=" + hash.substring(0, 12) + "...";
    }
}
