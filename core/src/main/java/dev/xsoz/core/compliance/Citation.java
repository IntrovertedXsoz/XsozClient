package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The evidence behind a verdict (contracts.md C7.2).
 *
 * <p><strong>A verdict without a citation is invalid and must throw.</strong> This class is
 * where that rule lives, and it is enforced in the constructor rather than at a call site,
 * because a call site is a code-review question and a constructor is not.</p>
 *
 * <p><strong>The retrieval date travels with the verdict</strong> (contracts.md 0.5). A
 * citation without a date is a lie about how fresh it is, and the whole value of carrying a
 * citation is that the player can check it. A {@link Decision} is therefore never
 * persisted - see C7.3, {@code [CONTRACT DECISION] D5}.</p>
 *
 * <p>Deeply immutable: final fields, no setters, value equality, and a
 * human-readable {@link #toString()} that includes the date, because a citation that is
 * logged without its date is a defect.</p>
 */
public final class Citation {

    /**
     * The hard word limit on {@link #clause()}, enforced in the constructor
     * (contracts.md C7.2).
     */
    public static final int MAX_CLAUSE_WORDS = 15;

    private final String rulesetId;
    private final String clause;
    private final String url;
    private final LocalDate retrievedOn;

    /**
     * Builds a citation, validating every field eagerly.
     *
     * @param rulesetId   the ruleset this clause came from, e.g. {@code "pvphq-website"}; non-blank
     * @param clause      the clause text, VERBATIM, at most {@link #MAX_CLAUSE_WORDS} words; non-blank
     * @param url         where the clause was read; must be {@code https}
     * @param retrievedOn the date it was read; {@code null} is refused
     * @throws MissingCitationException     if the ruleset, clause or retrieval date is absent
     * @throws CitationTooLongException     if the clause exceeds {@link #MAX_CLAUSE_WORDS} words
     * @throws XsozContractException       if the URL is absent or is not {@code https}
     */
    public Citation(String rulesetId, String clause, String url, LocalDate retrievedOn) {
        if (isBlank(rulesetId)) {
            throw new MissingCitationException(
                    "A citation must name the ruleset it came from. contracts.md C7.2.");
        }
        if (isBlank(clause)) {
            throw new MissingCitationException(
                    "A citation must carry a verbatim clause. contracts.md C7.2: a verdict without "
                            + "a citation is invalid and must throw.");
        }
        int wordCount = countWords(clause);
        if (wordCount > MAX_CLAUSE_WORDS) {
            throw new CitationTooLongException(
                    "The clause quoted from " + rulesetId + " is " + wordCount + " words; contracts.md "
                            + "C7.2 hard-enforces at " + MAX_CLAUSE_WORDS + ". A clause that has been "
                            + "paraphrased, summarised or truncated is no longer evidence, which is the "
                            + "whole reason the limit exists. Quoted clause: \"" + clause + "\"");
        }
        this.rulesetId = rulesetId;
        this.clause = clause;
        if (isBlank(url)) {
            throw new XsozContractException(
                    "A citation must carry the URL its clause was read from. rulesetId=" + rulesetId);
        }
        if (!url.startsWith("https://")) {
            throw new XsozContractException(
                    "A citation URL must be https. Got '" + url + "' for rulesetId=" + rulesetId
                            + ". A plain-http or protocol-relative citation is not evidence.");
        }
        if (retrievedOn == null) {
            throw new MissingCitationException(
                    "A citation must carry its retrieval date. contracts.md 0.5: a citation is never "
                            + "logged without its retrievedOn date, because a verdict without a date is "
                            + "a lie about how fresh it is. rulesetId=" + rulesetId);
        }
        this.url = url;
        this.retrievedOn = retrievedOn;
    }

    /** @return the ruleset id, e.g. {@code "pvphq-website"}, {@code "grim-wiki"}, {@code "derivation"} */
    public String rulesetId() {
        return rulesetId;
    }

    /** @return the verbatim clause text, at most {@link #MAX_CLAUSE_WORDS} words */
    public String clause() {
        return clause;
    }

    /** @return the {@code https} URL the clause was read from */
    public String url() {
        return url;
    }

    /** @return the date the clause was read; never {@code null} */
    public LocalDate retrievedOn() {
        return retrievedOn;
    }

    /**
     * Counts the clause's words, the way {@link #MAX_CLAUSE_WORDS} is measured: runs of
     * whitespace separate words, and leading and trailing whitespace is ignored. A run of
     * any length counts as one separator, so reformatting a clause cannot change its length.
     *
     * @return the word count, always at least 1 for a constructed citation
     */
    public int clauseWordCount() {
        return countWords(clause);
    }

    /**
     * Counts words in a clause. Shared with {@link ComplianceProfileJson} so the limit is
     * measured identically on import as on construction.
     *
     * @param text the text to measure; must not be {@code null}
     * @return the number of whitespace-separated words
     */
    public static int countWords(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        int words = 1;
        boolean insideRun = false;
        for (int i = 0; i < trimmed.length(); i++) {
            boolean whitespace = Character.isWhitespace(trimmed.charAt(i));
            if (whitespace && !insideRun) {
                words++;
                insideRun = true;
            } else if (!whitespace) {
                insideRun = false;
            }
        }
        return words;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Citation)) {
            return false;
        }
        Citation that = (Citation) other;
        return rulesetId.equals(that.rulesetId)
                && clause.equals(that.clause)
                && url.equals(that.url)
                && retrievedOn.equals(that.retrievedOn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rulesetId, clause, url, retrievedOn);
    }

    /**
     * @return a single-line rendering that always includes the retrieval date
     *         (contracts.md 0.5)
     */
    @Override
    public String toString() {
        return "Citation[" + rulesetId + " \"" + clause + "\" " + url + " retrieved " + retrievedOn + "]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
