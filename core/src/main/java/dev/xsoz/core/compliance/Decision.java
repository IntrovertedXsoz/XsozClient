package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A resolved, computed compliance outcome for one feature (contracts.md C7.3).
 *
 * <p><strong>Construction is the enforcement point.</strong> A {@code Decision} cannot be
 * built without at least one {@link Citation}; the constructor throws
 * {@link MissingCitationException} if the list is {@code null} or empty. There is no
 * factory, no builder and no subclass (C7.3's {@code DecisionRequiresCitationTest} asserts
 * that no subclass exists) that can produce a citation-free decision.</p>
 *
 * <p><strong>Never persisted</strong> - {@code [CONTRACT DECISION] D5}. A cached decision
 * goes stale silently as its citation ages, and the whole value of this system is that the
 * citation and its date travel with the verdict. {@code ComplianceEngine.authorize} is
 * called fresh on every gate consultation.</p>
 *
 * <p>Deeply immutable: final fields, defensive copies, unmodifiable collections.</p>
 */
public final class Decision {

    private final boolean allow;
    private final ComplianceTier tier;
    private final Verdict verdict;
    private final String reason;
    private final List<Citation> citations;
    private final Set<String> appliedConditions;
    private final DecisionSource source;

    /**
     * Builds a decision. Every construction site is an enforcement site.
     *
     * @param allow             whether the feature may be effective in the current context
     * @param tier              the feature's compliance tier
     * @param verdict           the resolved verdict
     * @param reason            one human line, shown next to the toggle
     * @param citations         the evidence; <strong>at least one, or this throws</strong>
     * @param appliedConditions condition ids that were satisfied for this decision; defensively copied
     * @param source            which rule produced the decision
     * @throws MissingCitationException if {@code citations} is {@code null} or empty
     * @throws XsozContractException   if any other argument is absent
     * @throws IllegalArgumentException if a {@link Verdict#DENIED} row is marked {@code allow = true}
     */
    public Decision(boolean allow,
                    ComplianceTier tier,
                    Verdict verdict,
                    String reason,
                    List<Citation> citations,
                    Set<String> appliedConditions,
                    DecisionSource source) {
        if (citations == null || citations.isEmpty()) {
            throw new MissingCitationException(
                    "A Decision cannot be constructed without a Citation. contracts.md C7.3: "
                            + "citations is ALWAYS >= 1 and the empty list is impossible.");
        }
        for (int i = 0; i < citations.size(); i++) {
            if (citations.get(i) == null) {
                throw new MissingCitationException(
                        "Decision citation list holds null at index " + i
                                + ". contracts.md 0.4: null appears in exactly two deliberate places.");
            }
        }
        if (tier == null) {
            throw new XsozContractException("Decision.tier must not be null (contracts.md C7.3).");
        }
        if (verdict == null) {
            throw new XsozContractException("Decision.verdict must not be null (contracts.md C7.3).");
        }
        if (source == null) {
            throw new XsozContractException("Decision.source must not be null (contracts.md C7.3).");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new XsozContractException(
                    "Decision.reason must be a non-empty human line. A refused feature with no stated "
                            + "reason is a silent refusal, which contracts.md C2.4 calls out as the exact "
                            + "failure mode a player cannot explain to staff. tier=" + tier
                            + " verdict=" + verdict + " source=" + source);
        }
        if (verdict == Verdict.DENIED && allow) {
            throw new IllegalArgumentException(
                    "Verdict.DENIED is terminal and can never allow=true. tier=" + tier
                            + " source=" + source + " reason=" + reason);
        }
        this.allow = allow;
        this.tier = tier;
        this.verdict = verdict;
        this.reason = reason;
        this.citations = Collections.unmodifiableList(new ArrayList<Citation>(citations));
        this.appliedConditions = Collections.unmodifiableSet(
                new LinkedHashSet<String>(appliedConditions == null
                        ? Collections.<String>emptySet() : appliedConditions));
        this.source = source;
    }

    /** @return {@code true} only when the feature may be effective right now */
    public boolean allow() {
        return allow;
    }

    /** @return the feature's compliance tier */
    public ComplianceTier tier() {
        return tier;
    }

    /** @return the resolved verdict */
    public Verdict verdict() {
        return verdict;
    }

    /** @return one human line explaining the decision, shown next to the toggle */
    public String reason() {
        return reason;
    }

    /** @return the evidence, never empty, each carrying its own retrieval date */
    public List<Citation> citations() {
        return citations;
    }

    /** @return the condition ids that held for this decision, possibly empty */
    public Set<String> appliedConditions() {
        return appliedConditions;
    }

    /** @return which rule produced this decision */
    public DecisionSource source() {
        return source;
    }

    /**
     * The freshness of a decision: the OLDEST retrieval date among its citations, which is
     * the same rule {@link ComplianceProfile#retrievedOn()} uses (contracts.md C7.4).
     *
     * <p>Never {@code null}: the constructor has already proved the list is non-empty.</p>
     *
     * @return the oldest citation's retrieval date
     */
    public java.time.LocalDate oldestCitationDate() {
        java.time.LocalDate oldest = citations.get(0).retrievedOn();
        for (int i = 1; i < citations.size(); i++) {
            java.time.LocalDate candidate = citations.get(i).retrievedOn();
            if (candidate.isBefore(oldest)) {
                oldest = candidate;
            }
        }
        return oldest;
    }

    @Override
    public String toString() {
        return (allow ? "ALLOW" : "DENY")
                + " tier=" + tier
                + " verdict=" + verdict
                + " source=" + source
                + " conditions=" + appliedConditions
                + " reason=\"" + reason + "\""
                + " citations=" + citations;
    }
}
