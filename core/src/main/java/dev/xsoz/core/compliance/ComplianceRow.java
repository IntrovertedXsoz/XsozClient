package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One feature's row in a {@link ComplianceProfile}'s verdict table (contracts.md C7.4).
 *
 * <p>contracts.md C7.4 sketches this type as a bare {@code Row}. It is a top-level class here
 * named {@code ComplianceRow} so that the unqualified name {@code Row} does not sit in the
 * {@code dev.xsoz.core.compliance} package namespace, where every file that imports this
 * package would collide with the word "row" that {@code docs/rules-matrix.md} already uses
 * for a completely different thing - a matrix row.</p>
 *
 * <p>Deeply immutable: final fields, defensive copies, unmodifiable collections.</p>
 */
public final class ComplianceRow {

    private final Verdict verdict;
    private final ComplianceTier tier;
    private final List<Citation> citations;
    private final Set<String> conditions;
    private final Set<String> prohibitionsAlways;
    private final String summary;

    /**
     * @param verdict            the resolved verdict for this feature under this profile
     * @param tier               the feature's compliance tier
     * @param citations          the evidence; <strong>at least one, or this throws</strong>
     * @param conditions         condition ids that must hold for a CONDITIONAL row; must be
     *                           empty unless the verdict is CONDITIONAL
     * @param prohibitionsAlways ids from the global prohibition set; non-empty means a hard
     *                           deny that nothing can unlock
     * @param summary            one line for the UI
     * @throws MissingCitationException if {@code citations} is {@code null} or empty
     * @throws XsozContractException   if any other argument is absent, or if the row claims
     *                                 a tier the verdict cannot support
     */
    public ComplianceRow(Verdict verdict,
                         ComplianceTier tier,
                         List<Citation> citations,
                         Set<String> conditions,
                         Set<String> prohibitionsAlways,
                         String summary) {
        if (citations == null || citations.isEmpty()) {
            throw new MissingCitationException(
                    "A compliance row must carry at least one citation. contracts.md C7.4: "
                            + "Row.citations is >= 1, and a row without a citation is a claim, "
                            + "not evidence.");
        }
        if (verdict == null || tier == null) {
            throw new XsozContractException("ComplianceRow.verdict and .tier must not be null (C7.4).");
        }
        if (summary == null || summary.trim().isEmpty()) {
            throw new XsozContractException(
                    "ComplianceRow.summary must be a non-empty one-liner; the UI renders it next to "
                            + "the toggle. tier=" + tier + " verdict=" + verdict);
        }
        if (verdict == Verdict.DENIED && tier == ComplianceTier.A) {
            throw new XsozContractException(
                    "A Tier A feature cannot carry a DENIED row: Tier A is legal everywhere studied, "
                            + "which is what Tier A is. Denying it would contradict docs/brief.md 2. "
                            + "summary=\"" + summary + "\"");
        }
        Set<String> conditionSet = conditions == null
                ? Collections.<String>emptySet() : new LinkedHashSet<String>(conditions);
        if (verdict != Verdict.CONDITIONAL && !conditionSet.isEmpty()) {
            throw new XsozContractException(
                    "A " + verdict + " row must not carry conditions. A condition on a terminal or "
                            + "unconditional verdict is a condition nobody checks. summary=\""
                            + summary + "\"");
        }
        if (verdict == Verdict.CONDITIONAL && conditionSet.isEmpty()) {
            throw new XsozContractException(
                    "A CONDITIONAL row must state its conditions in full. contracts.md C7.1: "
                            + "CONDITIONAL means permitted under a stated condition, which is written "
                            + "in full. summary=\"" + summary + "\"");
        }
        this.verdict = verdict;
        this.tier = tier;
        this.citations = Collections.unmodifiableList(new ArrayList<Citation>(citations));
        this.conditions = Collections.unmodifiableSet(conditionSet);
        this.prohibitionsAlways = Collections.unmodifiableSet(prohibitionsAlways == null
                ? Collections.<String>emptySet() : new LinkedHashSet<String>(prohibitionsAlways));
        this.summary = summary;
    }

    /** @return the resolved verdict */
    public Verdict verdict() {
        return verdict;
    }

    /** @return the feature's compliance tier */
    public ComplianceTier tier() {
        return tier;
    }

    /** @return the evidence for this row, never empty */
    public List<Citation> citations() {
        return citations;
    }

    /** @return condition ids that must hold for a CONDITIONAL row, otherwise empty */
    public Set<String> conditions() {
        return conditions;
    }

    /**
     * @return the ids of global prohibitions that apply here. Non-empty means a hard deny:
     *         nothing, and no user-edited profile, can unlock it (contracts.md C7.4 step 6).
     */
    public Set<String> prohibitionsAlways() {
        return prohibitionsAlways;
    }

    /** @return the one-line summary rendered next to the toggle */
    public String summary() {
        return summary;
    }

    /** @return {@code true} when a standing prohibition makes this row a terminal deny */
    public boolean isHardDenied() {
        return !prohibitionsAlways.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ComplianceRow)) {
            return false;
        }
        ComplianceRow that = (ComplianceRow) other;
        return verdict == that.verdict
                && tier == that.tier
                && citations.equals(that.citations)
                && conditions.equals(that.conditions)
                && prohibitionsAlways.equals(that.prohibitionsAlways)
                && summary.equals(that.summary);
    }

    @Override
    public int hashCode() {
        int result = verdict.hashCode();
        result = 31 * result + tier.hashCode();
        result = 31 * result + citations.hashCode();
        result = 31 * result + conditions.hashCode();
        result = 31 * result + prohibitionsAlways.hashCode();
        result = 31 * result + summary.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ComplianceRow[" + tier + " " + verdict
                + (conditions.isEmpty() ? "" : " conditions=" + conditions)
                + (prohibitionsAlways.isEmpty() ? "" : " prohibited=" + prohibitionsAlways)
                + " \"" + summary + "\"]";
    }
}
