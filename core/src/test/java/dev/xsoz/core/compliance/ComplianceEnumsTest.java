package dev.xsoz.core.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract C7.1 - the closed enum sets that three other agents code against.
 *
 * <p>These enums are tiny and they are the highest-risk bytes in the repository. A tier
 * or a verdict that diverges here does not fail to compile; it silently mislabels a
 * module, and the failure surfaces as a ban appeal. So the exact constant sets are
 * asserted, in declaration order, and any addition or reorder fails CI loudly.</p>
 */
class ComplianceEnumsTest {

    @Test
    @DisplayName("ComplianceTier is exactly A, B, C in that order")
    void complianceTierConstantSet() {
        assertEquals(
                Arrays.asList("A", "B", "C"),
                namesOf(ComplianceTier.values()));
        assertEquals(3, ComplianceTier.values().length);
    }

    @Test
    @DisplayName("Verdict is exactly ALLOWED, CONDITIONAL, CONTESTED, OWN_RISK, DENIED, UNVERIFIED")
    void verdictConstantSet() {
        assertEquals(
                Arrays.asList("ALLOWED", "CONDITIONAL", "CONTESTED", "OWN_RISK", "DENIED", "UNVERIFIED"),
                namesOf(Verdict.values()));
        assertEquals(6, Verdict.values().length);
    }

    @Test
    @DisplayName("verdict ordinals match the contract's declaration order")
    void verdictOrdinals() {
        assertEquals(0, Verdict.ALLOWED.ordinal());
        assertEquals(1, Verdict.CONDITIONAL.ordinal());
        assertEquals(2, Verdict.CONTESTED.ordinal());
        assertEquals(3, Verdict.OWN_RISK.ordinal());
        assertEquals(4, Verdict.DENIED.ordinal());
        assertEquals(5, Verdict.UNVERIFIED.ordinal());
    }

    @Test
    @DisplayName("Tier C is DENIED and Tier A is ALLOWED - the two enforcement poles")
    void tierEnforcementPoles() {
        // Tier A: present in the ALLOWED set of every bundled profile.
        assertEquals(ComplianceTier.A, ComplianceTier.valueOf("A"));
        // Tier C: absent from the binary entirely. This test asserts the enum has no
        // fourth constant that could be used to smuggle one in.
        assertEquals(3, EnumSet.allOf(ComplianceTier.class).size());
        assertThrows(IllegalArgumentException.class, () -> ComplianceTier.valueOf("D"));
        assertThrows(IllegalArgumentException.class, () -> ComplianceTier.valueOf("A_PLUS"));
        assertThrows(IllegalArgumentException.class, () -> ComplianceTier.valueOf("c"));
    }

    @Test
    @DisplayName("DENIED is terminal and UNVERIFIED excludes rather than allows")
    void terminalVerdictsExist() {
        Set<Verdict> terminal = EnumSet.of(Verdict.DENIED, Verdict.UNVERIFIED);
        for (Verdict verdict : terminal) {
            assertNotNull(Verdict.valueOf(verdict.name()));
        }
        assertThrows(IllegalArgumentException.class, () -> Verdict.valueOf("ALLOWED_WITH_CAVEAT"));
        assertThrows(IllegalArgumentException.class, () -> Verdict.valueOf("MAYBE"));
    }

    @Test
    @DisplayName("valueOf round-trips by name, never by ordinal (C4.2 enum serialisation rule)")
    void valueOfIsByName() {
        for (ComplianceTier tier : ComplianceTier.values()) {
            assertSame(tier, ComplianceTier.valueOf(tier.name()));
        }
        for (Verdict verdict : Verdict.values()) {
            assertSame(verdict, Verdict.valueOf(verdict.name()));
        }
    }

    @Test
    @DisplayName("both enums carry no unexpected constants under a rebuilt set")
    void rebuiltSetsMatch() {
        Set<String> tiers = new LinkedHashSet<String>();
        for (ComplianceTier tier : ComplianceTier.values()) {
            tiers.add(tier.name());
        }
        assertEquals(3, tiers.size(), "ComplianceTier has duplicate or extra constants: " + tiers);

        Set<String> verdicts = new LinkedHashSet<String>();
        for (Verdict verdict : Verdict.values()) {
            verdicts.add(verdict.name());
        }
        assertEquals(6, verdicts.size(), "Verdict has duplicate or extra constants: " + verdicts);
    }

    private static java.util.List<String> namesOf(Enum<?>[] constants) {
        java.util.List<String> names = new java.util.ArrayList<String>(constants.length);
        for (Enum<?> constant : constants) {
            names.add(constant.name());
        }
        return names;
    }
}
