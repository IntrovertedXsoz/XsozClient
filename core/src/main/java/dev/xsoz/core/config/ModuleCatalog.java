package dev.xsoz.core.config;

import dev.xsoz.core.compliance.ComplianceTier;
import dev.xsoz.core.setting.SettingsSchema;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

/**
 * What the config store knows about the modules that exist.
 *
 * <p><strong>The store does not own the module registry</strong> - C2.5 puts that in
 * {@code dev.xsoz.core.module}, another agent's file scope - so it asks. This interface
 * is the whole of the question, which keeps {@code :core}'s config package from importing
 * a type it must not depend on.</p>
 *
 * <p><strong>An empty catalog means "not installed", and the loader must not treat it as
 * "nothing exists".</strong> A store that dropped every module key because the catalog
 * had not been wired yet would silently empty every profile on first run. So
 * {@link #knownModuleIds()} being empty disables the unknown-key checks entirely, and
 * {@link #tierOf(String)} being empty means "unknown tier", not "no tier".</p>
 */
public interface ModuleCatalog {

    /** @return a catalog that knows nothing, which disables the unknown-key checks */
    static ModuleCatalog none() {
        return EmptyCatalog.INSTANCE;
    }

    /**
     * @param moduleId a module id
     * @return the module's declared settings schema, empty when the module declares none
     *         or is not registered
     */
    Optional<SettingsSchema> settingsSchemaFor(String moduleId);

    /**
     * @return every registered module id, unmodifiable. Empty means "not installed", and a
     *         caller must not read that as "no modules exist".
     */
    Set<String> knownModuleIds();

    /**
     * @param moduleId a module id
     * @return the module's compliance tier, empty when the module is not registered
     */
    Optional<ComplianceTier> tierOf(String moduleId);

    /** The "knows nothing" catalog. */
    final class EmptyCatalog implements ModuleCatalog {

        static final EmptyCatalog INSTANCE = new EmptyCatalog();

        private EmptyCatalog() {
        }

        @Override
        public Optional<SettingsSchema> settingsSchemaFor(String moduleId) {
            return Optional.empty();
        }

        @Override
        public Set<String> knownModuleIds() {
            return Collections.emptySet();
        }

        @Override
        public Optional<ComplianceTier> tierOf(String moduleId) {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "ModuleCatalog[none]";
        }
    }
}
