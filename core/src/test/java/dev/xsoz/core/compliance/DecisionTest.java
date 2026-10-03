package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract C7.3 - {@code Decision}. Construction is the enforcement point.
 */
class DecisionTest {

    private static final List<Citation> CITATIONS =
            Collections.singletonList(ComplianceCitations.STRICTEST_KNOWN);

    @Test
    @DisplayName("a Decision cannot be constructed without a Citation")
    void emptyCitationListThrows() {
        MissingCitationException thrown = assertThrows(MissingCitationException.class,
                () -> new Decision(false, ComplianceTier.A, Verdict.DENIED, "no evidence",
                        Collections.<Citation>emptyList(), Collections.<String>emptySet(),
                        DecisionSource.PROFILE_DENIED));
        assertTrue(thrown.getMessage().contains("Citation"), thrown.getMessage());
    }

    @Test
    @DisplayName("a null citation list throws, and so does a list holding a null")
    void nullCitationsThrow() {
        assertThrows(MissingCitationException.class,
                () -> new Decision(false, ComplianceTier.A, Verdict.DENIED, "no evidence",
                        null, Collections.<String>emptySet(), DecisionSource.PROFILE_DENIED));
        List<Citation> withHole = new ArrayList<Citation>();
        withHole.add(ComplianceCitations.STRICTEST_KNOWN);
        withHole.add(null);
        assertThrows(MissingCitationException.class,
                () -> new Decision(false, ComplianceTier.A, Verdict.DENIED, "no evidence",
                        withHole, Collections.<String>emptySet(), DecisionSource.PROFILE_DENIED));
    }

    @Test
    @DisplayName("no Decision subclass exists, so no subclass can dodge the constructor")
    void noSubclassExists() {
        assertEquals(0, Decision.class.getDeclaredClasses().length);
        assertTrue(java.lang.reflect.Modifier.isFinal(Decision.class.getModifiers()),
                "Decision must be final so no subclass can exist elsewhere either");
    }

    @Test
    @DisplayName("a blank reason throws: a silent refusal is what C2.4 calls unexplainable")
    void blankReasonThrows() {
        assertThrows(XsozContractException.class,
                () -> new Decision(false, ComplianceTier.A, Verdict.DENIED, "   ", CITATIONS,
                        Collections.<String>emptySet(), DecisionSource.PROFILE_DENIED));
        assertThrows(XsozContractException.class,
                () -> new Decision(false, ComplianceTier.A, Verdict.DENIED, null, CITATIONS,
                        Collections.<String>emptySet(), DecisionSource.PROFILE_DENIED));
    }

    @Test
    @DisplayName("DENIED is terminal and can never carry allow = true")
    void deniedCannotAllow() {
        assertThrows(IllegalArgumentException.class,
                () -> new Decision(true, ComplianceTier.C, Verdict.DENIED, "banned but on",
                        CITATIONS, Collections.<String>emptySet(), DecisionSource.TIER_C_ABSENT));
    }

    @Test
    @DisplayName("ALLOWED with allow = false is legal: that is the server opt-out case (C7.5 step 7)")
    void allowedButNotAllowed() {
        Decision optedOut = new Decision(false, ComplianceTier.B, Verdict.ALLOWED,
                "Allowed by the profile, but a server asked us to disable it.", CITATIONS,
                Collections.<String>emptySet(), DecisionSource.OPT_OUT);
        assertFalse(optedOut.allow());
        assertEquals(Verdict.ALLOWED, optedOut.verdict());
        assertEquals(DecisionSource.OPT_OUT, optedOut.source());
    }

    @Test
    @DisplayName("the citation and condition lists are defensively copied and unmodifiable")
    void defensiveCopies() {
        List<Citation> mutableCitations = new ArrayList<Citation>(CITATIONS);
        Set<String> mutableConditions = new HashSet<String>(Arrays.asList("a", "b"));
        Decision decision = new Decision(true, ComplianceTier.A, Verdict.CONDITIONAL, "ok",
                mutableCitations, mutableConditions, DecisionSource.PROFILE_CONDITIONAL);

        mutableCitations.clear();
        mutableConditions.clear();

        assertEquals(1, decision.citations().size(), "clearing the caller's list must not clear ours");
        assertEquals(2, decision.appliedConditions().size());
        assertThrows(UnsupportedOperationException.class, () -> decision.citations().clear());
        assertThrows(UnsupportedOperationException.class, () -> decision.appliedConditions().clear());
    }

    @Test
    @DisplayName("oldestCitationDate is the oldest of the citations, never null")
    void oldestCitationDate() {
        Citation older = new Citation("derivation", "an older clause", "https://example.test/a",
                LocalDate.of(2020, 1, 1));
        Citation newer = new Citation("derivation", "a newer clause", "https://example.test/b",
                LocalDate.of(2026, 9, 28));
        Decision decision = new Decision(true, ComplianceTier.A, Verdict.ALLOWED, "ok",
                Arrays.asList(newer, older), Collections.<String>emptySet(),
                DecisionSource.PROFILE_ALLOWED);
        assertEquals(LocalDate.of(2020, 1, 1), decision.oldestCitationDate());
    }

    @Test
    @DisplayName("toString names the tier, verdict, source and the dated citation")
    void toStringIsDiagnostic() {
        Decision decision = new Decision(false, ComplianceTier.B, Verdict.DENIED, "denied at install",
                CITATIONS, Collections.<String>emptySet(), DecisionSource.PROFILE_DENIED);
        String rendered = decision.toString();
        assertTrue(rendered.startsWith("DENY"), rendered);
        assertTrue(rendered.contains("tier=B"), rendered);
        assertTrue(rendered.contains("source=PROFILE_DENIED"), rendered);
        assertTrue(rendered.contains("2026-09-28"), rendered);
    }
}
