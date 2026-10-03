package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

/**
 * Thrown when a verdict would otherwise be recorded without a citation, or without the
 * retrieval date that makes the citation honest (contracts.md C7.2, 0.5).
 *
 * <p>Named in the contract, so this is not an invented subclass (contracts.md 0.4).</p>
 */
public class MissingCitationException extends XsozContractException {

    private static final long serialVersionUID = 1L;

    /**
     * @param explanation what was missing and which contract section requires it
     */
    public MissingCitationException(String explanation) {
        super(explanation);
    }
}
