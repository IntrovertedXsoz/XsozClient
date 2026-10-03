package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Where a profile's numbers came from (contracts.md C5.2).
 *
 * <p><strong>A value with no provenance is a rumour.</strong> This type travels with the
 * data rather than living in a design document, so a screenshot of the profile screen, an
 * export file and a support conversation all carry the same citation and the same
 * retrieval date.</p>
 *
 * <p><strong>The requirement is asymmetric on purpose.</strong> For
 * {@link ProvenanceKind#PUBLISHED_SETTINGS} and
 * {@link ProvenanceKind#COMMUNITY_CONVENTION}, {@code sourceUrl} and
 * {@code retrievedUtc} are mandatory and {@code sourceUrl} must be {@code https}. For
 * {@link ProvenanceKind#USER_DEFINED} they are absent, and absence is expressed by
 * {@link Optional} rather than by an empty string, because "no source" and "the source
 * is the empty string" are different facts.</p>
 *
 * <p><strong>The retrieval date is not optional decoration.</strong> C0.5: a citation
 * without its {@code retrievedOn} date is a lie about how fresh it is. A rules page
 * changes.</p>
 *
 * <p>Deeply immutable.</p>
 */
public final class Provenance {

    private final ProvenanceKind kind;
    private final String sourceLabel;
    private final String sourceUrl;
    private final LocalDate retrievedOn;
    private final String note;

    private Provenance(ProvenanceKind kind, String sourceLabel, String sourceUrl,
                       LocalDate retrievedOn, String note) {
        this.kind = kind;
        this.sourceLabel = sourceLabel;
        this.sourceUrl = sourceUrl;
        this.retrievedOn = retrievedOn;
        this.note = note;
    }

    /**
     * Provenance for numbers a person or a community published.
     *
     * @param kind       {@link ProvenanceKind#PUBLISHED_SETTINGS} or
     *                   {@link ProvenanceKind#COMMUNITY_CONVENTION}
     * @param sourceLabel a human label for the source, e.g. "YouTube @Marlowww video
     *                    descriptions"
     * @param sourceUrl  the {@code https} source
     * @param retrievedOn the date the source was read
     * @param note       what is published and what is derived, stated in full; must be
     *                   non-blank, because "0.744 is derived, not published" is the
     *                   single most important sentence this block carries
     * @return the provenance
     * @throws XsozContractException if the label or note is blank, or the URL is not
     *                                {@code https}, or the kind is
     *                                {@link ProvenanceKind#USER_DEFINED}
     */
    public static Provenance sourced(ProvenanceKind kind, String sourceLabel, String sourceUrl,
                                     LocalDate retrievedOn, String note) {
        if (kind == null || kind == ProvenanceKind.USER_DEFINED) {
            throw new XsozContractException(
                    "sourced(...) is for PUBLISHED_SETTINGS and COMMUNITY_CONVENTION only; "
                            + "USER_DEFINED has nothing to cite. Was " + kind + ".");
        }
        requireText(sourceLabel, "sourceLabel");
        requireText(note, "note");
        if (sourceUrl == null || !sourceUrl.startsWith("https://")) {
            throw new XsozContractException(
                    "A sourced profile needs an https source URL, not \"" + sourceUrl + "\". C5.2: "
                            + "sourceUrl must be https for the two non-user provenance kinds.");
        }
        if (retrievedOn == null) {
            throw new XsozContractException(
                    "A sourced profile needs the date its source was read. C0.5: a citation without "
                            + "a retrievedOn date is a lie about how fresh it is.");
        }
        return new Provenance(kind, sourceLabel.trim(), sourceUrl.trim(), retrievedOn, note.trim());
    }

    /**
     * Provenance for numbers the player entered.
     *
     * @param note what these numbers are, in the player's own words; must be non-blank
     * @return the provenance
     * @throws XsozContractException if {@code note} is blank
     */
    public static Provenance userDefined(String note) {
        requireText(note, "note");
        return new Provenance(ProvenanceKind.USER_DEFINED, null, null, null, note.trim());
    }

    /**
     * Returns a copy of this provenance re-declared as the player's own.
     *
     * <p>Used by {@code create(name)}, which clones a profile and must therefore stop
     * claiming the clone's numbers came from someone else's published settings.</p>
     *
     * @param note what the new numbers are
     * @return a {@link ProvenanceKind#USER_DEFINED} copy
     */
    public Provenance asUserDefined(String note) {
        return userDefined(note);
    }

    /** @return the provenance kind, never null */
    public ProvenanceKind kind() {
        return kind;
    }

    /**
     * @return the human source label
     * @throws XsozContractException if the kind is {@link ProvenanceKind#USER_DEFINED},
     *                                which has no source
     */
    public String sourceLabel() {
        if (sourceLabel == null) {
            throw new XsozContractException(
                    "A USER_DEFINED profile has no sourceLabel. Ask "
                            + "Provenance.sourceUrl() instead of assuming an empty string means "
                            + "\"none\".");
        }
        return sourceLabel;
    }

    /** @return the source label, empty for {@link ProvenanceKind#USER_DEFINED} */
    public Optional<String> optSourceLabel() {
        return Optional.ofNullable(sourceLabel);
    }

    /** @return the {@code https} source URL */
    public String sourceUrl() {
        if (sourceUrl == null) {
            throw new XsozContractException(
                    "A USER_DEFINED profile has no sourceUrl.");
        }
        return sourceUrl;
    }

    /** @return the source URL, empty for {@link ProvenanceKind#USER_DEFINED} */
    public Optional<String> optSourceUrl() {
        return Optional.ofNullable(sourceUrl);
    }

    /** @return the date the source was read */
    public LocalDate retrievedOn() {
        if (retrievedOn == null) {
            throw new XsozContractException("A USER_DEFINED profile has no retrievedOn date.");
        }
        return retrievedOn;
    }

    /** @return the retrieval date, empty for {@link ProvenanceKind#USER_DEFINED} */
    public Optional<LocalDate> optRetrievedOn() {
        return Optional.ofNullable(retrievedOn);
    }

    /**
     * @return what is published and what is derived. The sentence that stops a derived
     *         number being quoted as a published one.
     */
    public String note() {
        return note;
    }

    private static void requireText(String text, String field) {
        if (text == null || text.trim().isEmpty()) {
            throw new XsozContractException(
                    "Provenance needs a non-blank " + field + ". A provenance block with a hole "
                            + "in it is a block nobody can check.");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Provenance)) {
            return false;
        }
        Provenance that = (Provenance) other;
        return kind == that.kind
                && java.util.Objects.equals(sourceLabel, that.sourceLabel)
                && java.util.Objects.equals(sourceUrl, that.sourceUrl)
                && java.util.Objects.equals(retrievedOn, that.retrievedOn)
                && note.equals(that.note);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(kind, sourceLabel, sourceUrl, retrievedOn, note);
    }

    @Override
    public String toString() {
        return "Provenance[" + kind + (sourceUrl == null ? "" : " " + sourceUrl)
                + (retrievedOn == null ? "" : " retrieved " + retrievedOn) + "]";
    }
}
