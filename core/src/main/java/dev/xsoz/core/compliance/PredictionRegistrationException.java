package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

/**
 * Thrown by {@link PredictorRegistry#register(Predictor)} when a prediction feature cannot
 * be shown to yield (contracts.md C7.8).
 *
 * <p><strong>The feature cannot ship.</strong> This is not a warning path and not a disable
 * path: a prediction that cannot state its local action, its falsifier, its bound, or its
 * way of giving up has not answered the yield/fight question, and the answer it has given by
 * construction is <em>fight</em>.</p>
 */
public class PredictionRegistrationException extends XsozContractException {

    private static final long serialVersionUID = 1L;

    /**
     * @param explanation which of the six conditions failed, and why it is terminal
     */
    public PredictionRegistrationException(String explanation) {
        super(explanation);
    }
}
