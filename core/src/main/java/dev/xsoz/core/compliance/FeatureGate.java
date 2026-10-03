package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What is actually compiled into this build, and at what tier (contracts.md C7.5 step 1-2,
 * C7.1).
 *
 * <p>The gate is deliberately <strong>additive and closed</strong>: a feature is either
 * compiled here with a tier, or it is not compiled and therefore cannot be authorized.
 * Tier C identifiers are held in a separate set precisely so {@link #isCompiled(String)} can
 * return {@code false} for them: a Tier C feature is <em>absent</em>, not disabled, and a
 * shipped-but-disabled module is disqualifying on CubeCraft ("Clients with cheat modules,
 * even if you don't use them").</p>
 *
 * <p>Deeply immutable; a Tier C id can never be registered as compiled.</p>
 */
public final class FeatureGate {

    private final Map<String, ComplianceTier> compiled;
    private final Set<String> tierCIdentifiers;

    private FeatureGate(Map<String, ComplianceTier> compiled, Set<String> tierCIdentifiers) {
        this.compiled = Collections.unmodifiableMap(compiled);
        this.tierCIdentifiers = Collections.unmodifiableSet(tierCIdentifiers);
    }

    /**
     * Builds a gate.
     *
     * @param compiled          featureId to tier, for everything present in the binary
     * @param tierCIdentifiers  the Tier C identifiers; none of them may appear in
     *                          {@code compiled}
     * @throws XsozContractException if a Tier C identifier is also listed as compiled, which
     *                                would be the one thing that must never happen
     */
    public static FeatureGate of(Map<String, ComplianceTier> compiled, Set<String> tierCIdentifiers) {
        Map<String, ComplianceTier> copy = new LinkedHashMap<String, ComplianceTier>();
        if (compiled != null) {
            for (Map.Entry<String, ComplianceTier> entry : compiled.entrySet()) {
                if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                    throw new XsozContractException("FeatureGate holds a blank feature id.");
                }
                if (entry.getValue() == null) {
                    throw new XsozContractException(
                            "FeatureGate has no tier for compiled feature \"" + entry.getKey() + "\".");
                }
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        Set<String> tierC = new LinkedHashSet<String>();
        if (tierCIdentifiers != null) {
            for (String identifier : tierCIdentifiers) {
                if (identifier == null || identifier.trim().isEmpty()) {
                    throw new XsozContractException("FeatureGate holds a blank Tier C identifier.");
                }
                if (copy.containsKey(identifier)) {
                    throw new XsozContractException(
                            "Tier C feature \"" + identifier + "\" cannot be compiled. contracts.md C7.1: "
                                    + "Tier C is never compiled - not disabled, absent from the binary.");
                }
                if (copy.containsValue(ComplianceTier.C)) {
                    throw new XsozContractException(
                            "No compiled feature may carry ComplianceTier.C. contracts.md C7.1.");
                }
                tierC.add(identifier);
            }
        }
        return new FeatureGate(copy, tierC);
    }

    /**
     * @return a gate with nothing compiled and no known Tier C ids. An empty gate denies
     *         everything, which is the correct behaviour for a build before bootstrap
     */
    public static FeatureGate empty() {
        return of(Collections.<String, ComplianceTier>emptyMap(), Collections.<String>emptySet());
    }

    /**
     * @param moduleId the feature id
     * @return {@code true} if the feature exists in this binary
     */
    public boolean isCompiled(String moduleId) {
        return moduleId != null && compiled.containsKey(moduleId);
    }

    /**
     * @param moduleId the feature id
     * @return {@code true} if the id is a known Tier C identifier, which by definition is
     *         never compiled
     */
    public boolean isTierC(String moduleId) {
        return moduleId != null && tierCIdentifiers.contains(moduleId);
    }

    /**
     * @param moduleId the feature id
     * @return the feature's tier, or empty when the feature is not compiled
     */
    public Optional<ComplianceTier> tierOf(String moduleId) {
        return Optional.ofNullable(moduleId == null ? null : compiled.get(moduleId));
    }

    /** @return every compiled feature id, in registration order */
    public Set<String> compiledIds() {
        return compiled.keySet();
    }

    /** @return every known Tier C identifier, in registration order */
    public Set<String> tierCIdentifiers() {
        return tierCIdentifiers;
    }

    @Override
    public String toString() {
        return "FeatureGate[compiled=" + compiled + " tierC=" + tierCIdentifiers + "]";
    }
}
