package dev.xsoz.core;

/**
 * Base type for every checked contract violation (contracts.md 0.4).
 *
 * <p>Two rules from that section drive this class:</p>
 * <ul>
 *   <li><strong>Contract violations throw; user-facing states are values.</strong> A
 *       {@code DENIED} compliance verdict is a value. An out-of-range sensitivity is a
 *       value. This exception is for a caller violating a <em>shape</em> - a null where
 *       a non-null is required, a dpi of {@code 0}, a field that cannot hold what it is
 *       being asked to hold.</li>
 *   <li><strong>Adding a subclass is a change request</strong>, not a convenience. Named
 *       subclasses exist in the contracts (for example
 *       {@code SensitivityOutOfRangeException}); they are not invented ad hoc.</li>
 * </ul>
 *
 * <p>Unchecked by design: none of these conditions can be forced by external input in a
 * way a caller could handle by retrying differently. A caller that catches this is
 * swallowing a bug in the caller.</p>
 */
public class XsozContractException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception carrying a human-meaningful explanation.
     *
     * @param explanation what was violated and what the caller must do instead
     */
    public XsozContractException(String explanation) {
        super(explanation);
    }

    /**
     * Creates an exception carrying an explanation and an underlying cause.
     *
     * @param explanation what was violated and what the caller must do instead
     * @param cause       the originating failure, may be {@code null}
     */
    public XsozContractException(String explanation, Throwable cause) {
        super(explanation, cause);
    }
}
