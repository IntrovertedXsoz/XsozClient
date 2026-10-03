package dev.xsoz.core.compliance;

import java.util.Set;

/**
 * The compliance-relevant view of one registered module (contracts.md C2.1, C7.5 steps 3-4).
 *
 * <p><strong>This is a C7-local interface, declared here rather than imported.</strong> C2's
 * {@code Module} lives in {@code dev.xsoz.core.module}, owned by another agent, and the
 * compliance package must not depend on a package it does not own. Declaring the seam
 * locally keeps the engine's eight-step procedure typed without an import cycle; when C2's
 * interface lands it implements this one.</p>
 *
 * <p>Capabilities are carried as their C1.2 enum <em>names</em> rather than as the enum
 * type, for the same reason: the platform package is not written yet, and a string is a
 * fine seam for "which capability names must be present".</p>
 */
public interface ComplianceSubject {

    /** @return the module id, which is also the compliance feature id (C7.7, one namespace) */
    String moduleId();

    /** @return the module's compliance tier */
    ComplianceTier complianceTier();

    /**
     * The contexts in which the module may be effective. Never includes
     * {@link Context#WORLD_ABSENT} (C2.1).
     *
     * @return the allowed contexts
     */
    Set<Context> allowedContexts();

    /**
     * The C1.2 capability names that must all be present for this module to run here.
     *
     * @return the required capability names, possibly empty
     */
    Set<String> requiredCapabilityNames();
}
