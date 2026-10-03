package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract C7.2 - {@code Citation}. A verdict without a citation is invalid and must throw.
 */
class CitationTest {

    private static final LocalDate RETRIEVED = LocalDate.of(2026, 9, 28);

    @Test
    @DisplayName("a well-formed citation keeps all four fields")
    void wellFormedCitation() {
        Citation citation = new Citation("pvphq-website",
                "improve FPS or client performance without affecting gameplay",
                "https://pvphq.com/rules", RETRIEVED);
        assertEquals("pvphq-website", citation.rulesetId());
        assertEquals("improve FPS or client performance without affecting gameplay",
                citation.clause());
        assertEquals("https://pvphq.com/rules", citation.url());
        assertEquals(RETRIEVED, citation.retrievedOn());
        // improve(1) FPS(2) or(3) client(4) performance(5) without(6) affecting(7) gameplay(8)
        assertEquals(8, citation.clauseWordCount());
    }

    @Test
    @DisplayName("a clause of exactly 15 words is accepted; 16 is not")
    void wordLimitIsHardFifteen() {
        // 15 words: the boundary case must pass, or the limit is off by one.
        new Citation("derivation", "one two three four five six seven eight nine ten eleven twelve "
                + "thirteen fourteen fifteen", "https://example.test/r", RETRIEVED);

        // 16 words: the same sentence plus one more.
        CitationTooLongException thrown = assertThrows(CitationTooLongException.class,
                () -> new Citation("derivation", "one two three four five six seven eight nine ten "
                        + "eleven twelve thirteen fourteen fifteen sixteen",
                        "https://example.test/r", RETRIEVED));
        assertTrue(thrown.getMessage().contains("16"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("15"), thrown.getMessage());
    }

    @Test
    @DisplayName("the 15-word limit counts whitespace RUNS, so reformatting cannot change it")
    void wordCountIgnoresFormatting() {
        assertEquals(4, Citation.countWords("  a   b\tc\nd  "), "five whitespace chars, three runs");
        assertEquals(1, Citation.countWords("word"));
        assertEquals(0, Citation.countWords("   "));
    }

    @Test
    @DisplayName("a missing or blank clause throws MissingCitationException")
    void missingClauseThrows() {
        assertThrows(MissingCitationException.class,
                () -> new Citation("pvphq-website", null, "https://pvphq.com/rules", RETRIEVED));
        assertThrows(MissingCitationException.class,
                () -> new Citation("pvphq-website", "   ", "https://pvphq.com/rules", RETRIEVED));
    }

    @Test
    @DisplayName("a missing ruleset id throws: a clause with no source is not evidence")
    void missingRulesetThrows() {
        assertThrows(MissingCitationException.class,
                () -> new Citation(null, "Totem Pop Counter", "https://pvp.land/rules", RETRIEVED));
    }

    @ParameterizedTest(name = "rejects the non-https URL {0}")
    @ValueSource(strings = {
            "http://pvphq.com/rules",
            "//pvphq.com/rules",
            "pvphq.com/rules",
            "ftp://pvphq.com/rules",
    })
    void nonHttpsUrlThrows(String url) {
        assertThrows(XsozContractException.class,
                () -> new Citation("pvphq-website", "Totem Pop Counter", url, RETRIEVED));
    }

    @Test
    @DisplayName("a missing URL throws")
    void missingUrlThrows() {
        assertThrows(XsozContractException.class,
                () -> new Citation("pvphq-website", "Totem Pop Counter", null, RETRIEVED));
        assertThrows(XsozContractException.class,
                () -> new Citation("pvphq-website", "Totem Pop Counter", "  ", RETRIEVED));
    }

    @Test
    @DisplayName("a missing retrieval date throws: a verdict without a date is a lie about freshness")
    void missingDateThrows() {
        MissingCitationException thrown = assertThrows(MissingCitationException.class,
                () -> new Citation("pvphq-website", "Totem Pop Counter", "https://pvp.land/rules", null));
        assertTrue(thrown.getMessage().contains("retrieval date"), thrown.getMessage());
    }

    @Test
    @DisplayName("value equality: equal field values are equal citations, and the date is part of it")
    void valueEquality() {
        Citation a = new Citation("pvp-land", "Totem Pop Counter", "https://pvp.land/rules", RETRIEVED);
        Citation b = new Citation("pvp-land", "Totem Pop Counter", "https://pvp.land/rules", RETRIEVED);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        Citation older = new Citation("pvp-land", "Totem Pop Counter", "https://pvp.land/rules",
                RETRIEVED.minusDays(1));
        assertNotEquals(a, older, "the retrieval date is part of the citation's identity");
    }

    @Test
    @DisplayName("immutability: a citation has no setters and its fields never change")
    void immutability() throws Exception {
        Citation citation = ComplianceCitations.STRICTEST_KNOWN;
        for (java.lang.reflect.Method method : Citation.class.getMethods()) {
            String name = method.getName();
            assertTrue(!(name.startsWith("set") || name.startsWith("with")),
                    "Citation must not expose a mutator, found " + method);
        }
        for (java.lang.reflect.Field field : Citation.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()),
                        "Citation field " + field.getName() + " must be final");
            }
        }
        assertEquals(ComplianceCitations.STRICTEST_KNOWN, citation);
    }

    @Test
    @DisplayName("toString always carries the retrieval date (contracts.md 0.5)")
    void toStringCarriesTheDate() {
        String rendered = ComplianceCitations.STRICTEST_KNOWN.toString();
        assertTrue(rendered.contains("2026-09-28"), rendered);
        assertTrue(rendered.contains("pvphq-website"), rendered);
        assertTrue(rendered.contains("https://pvphq.com/rules"), rendered);
    }

    @Test
    @DisplayName("every shipped standing citation satisfies the 15-word limit at class load")
    void shippedCitationsAreWithinTheLimit() {
        for (java.lang.reflect.Field field : ComplianceCitations.class.getDeclaredFields()) {
            if (!Citation.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Citation citation = (Citation) field.get(null);
                assertTrue(citation != null, field.getName() + " is null");
                assertTrue(citation.clauseWordCount() <= Citation.MAX_CLAUSE_WORDS,
                        field.getName() + " is " + citation.clauseWordCount() + " words: "
                                + citation.clause());
            } catch (IllegalAccessException e) {
                throw new AssertionError("Could not read " + field.getName(), e);
            }
        }
    }
}
