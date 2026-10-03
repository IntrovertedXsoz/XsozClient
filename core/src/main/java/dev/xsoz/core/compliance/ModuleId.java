package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

/**
 * The module / compliance feature id grammar (contracts.md C7.7, {@code [CONTRACT DECISION] D4}).
 *
 * <p><strong>One namespace.</strong> {@code Module.id()} and the matrix's Feature column hold
 * the same string. There is no {@code module-id -> matrix-row-id} mapping table, because such
 * a table is precisely the file nobody reviews and precisely the place a Tier C row gets
 * re-attached to an innocent-looking id.</p>
 *
 * <p>The grammar is {@code ^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*$}. camelCase is rejected:
 * {@code hud.totemCounter} is not a valid id, {@code hud.totem-counter} is.</p>
 */
public final class ModuleId {

    /**
     * The grammar, in the form the contract spells it.
     *
     * <p><strong>One correction to contracts.md C7.7, and it is applied here rather than
     * reported.</strong> C7.7 writes the grammar as {@code ^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*$}
     * and then says, two lines later, that "the canonical form is lower-case-with-dashes
     * inside segments", giving {@code hud.totem-counter} as the example. The regex omits
     * {@code -}; the prose includes it. <strong>The prose wins</strong>, because a grammar that
     * rejects the contract's own three worked examples ({@code hud.totem-counter},
     * {@code latency.crystal-release}, {@code perf.sodium-tweaks}) is not a grammar, it is a
     * typo. The dashed form is what the rest of the product uses, so the dashed form is what
     * is enforced.</p>
     */
    public static final String GRAMMAR = "^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*$";

    private ModuleId() {
        throw new AssertionError("ModuleId is a grammar holder and is not instantiable.");
    }

    /**
     * @param id the candidate; may be {@code null}
     * @return {@code true} if the id matches {@link #GRAMMAR}
     */
    public static boolean isValid(String id) {
        return id != null && id.matches(GRAMMAR);
    }

    /**
     * @param id the candidate
     * @return the id itself
     * @throws XsozContractException if the id does not match {@link #GRAMMAR}
     */
    public static String require(String id) {
        if (!isValid(id)) {
            throw new XsozContractException(
                    "\"" + id + "\" is not a valid module id. contracts.md C7.7 grammar: " + GRAMMAR
                            + ". Example: hud.totem-counter is valid, hud.totemCounter is not.");
        }
        return id;
    }
}
