package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A staff member's explicit clearance for a CONTESTED feature (contracts.md C7.12,
 * {@code ContestedFeatureRequiresStaffClearanceTest}).
 *
 * <p>A CONTESTED verdict is never resolved silently. It resolves only when a person clears
 * it, and the record carries <em>who</em>, <em>when</em>, and <em>on what stated
 * authority</em> - the last of which is a dated {@link Citation}, because "the staff said so"
 * is not something a player can show in a Discord ticket.</p>
 *
 * <p>Deeply immutable.</p>
 */
public final class StaffClearance {

    private final String featureId;
    private final String staffHandle;
    private final LocalDate grantedOn;
    private final Citation citation;
    private final String note;

    /**
     * @param featureId   the cleared feature id
     * @param staffHandle who cleared it, as they would be named to the player
     * @param grantedOn   the date
     * @param citation    the stated authority the staff member relied on; its retrieval date
     *                    is what makes the clearance checkable later
     * @param note        one line the player can read; may be empty but not {@code null}
     * @throws XsozContractException if any argument except {@code note} is absent
     */
    public StaffClearance(String featureId, String staffHandle, LocalDate grantedOn,
                          Citation citation, String note) {
        if (featureId == null || featureId.trim().isEmpty()) {
            throw new XsozContractException("StaffClearance.featureId must not be blank.");
        }
        if (staffHandle == null || staffHandle.trim().isEmpty()) {
            throw new XsozContractException(
                    "StaffClearance.staffHandle must name who cleared the feature. contracts.md C7.12: "
                            + "the record carries who cleared it, when, and the citation.");
        }
        if (grantedOn == null) {
            throw new XsozContractException("StaffClearance.grantedOn must carry the date.");
        }
        if (citation == null) {
            throw new XsozContractException(
                    "StaffClearance must carry the citation the staff member relied on. \"The staff "
                            + "said so\" is not something a player can show in an appeal.");
        }
        this.featureId = featureId;
        this.staffHandle = staffHandle;
        this.grantedOn = grantedOn;
        this.citation = citation;
        this.note = note == null ? "" : note;
    }

    /** @return the cleared feature id */
    public String featureId() {
        return featureId;
    }

    /** @return who cleared it */
    public String staffHandle() {
        return staffHandle;
    }

    /** @return the date it was cleared */
    public LocalDate grantedOn() {
        return grantedOn;
    }

    /** @return the stated authority the staff member relied on */
    public Citation citation() {
        return citation;
    }

    /** @return the one-line note, possibly empty */
    public String note() {
        return note;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StaffClearance)) {
            return false;
        }
        StaffClearance that = (StaffClearance) other;
        return featureId.equals(that.featureId)
                && staffHandle.equals(that.staffHandle)
                && grantedOn.equals(that.grantedOn)
                && citation.equals(that.citation)
                && note.equals(that.note);
    }

    @Override
    public int hashCode() {
        return Objects.hash(featureId, staffHandle, grantedOn, citation, note);
    }

    @Override
    public String toString() {
        return "StaffClearance[" + featureId + " cleared by " + staffHandle + " on " + grantedOn
                + " on the authority of " + citation + "]";
    }
}
