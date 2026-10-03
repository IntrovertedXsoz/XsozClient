package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

/**
 * Thrown when a {@link Citation} clause exceeds {@link Citation#MAX_CLAUSE_WORDS} words
 * (contracts.md C7.2, "HARD ENFORCED in the constructor").
 *
 * <p>The limit exists because a clause that has been paraphrased, summarised or truncated is
 * no longer evidence. Fifteen words is what a rules clause actually is.</p>
 */
public class CitationTooLongException extends XsozContractException {

    private static final long serialVersionUID = 1L;

    /**
     * @param explanation the measured word count and the limit it exceeded
     */
    public CitationTooLongException(String explanation) {
        super(explanation);
    }
}
