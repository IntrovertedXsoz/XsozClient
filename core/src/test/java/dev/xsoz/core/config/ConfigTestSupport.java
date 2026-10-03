package dev.xsoz.core.config;

import dev.xsoz.core.compliance.ComplianceTier;
import dev.xsoz.core.keybind.DefaultBindPolicy;
import dev.xsoz.core.setting.BooleanSetting;
import dev.xsoz.core.setting.IntSetting;
import dev.xsoz.core.setting.SettingsSchema;
import dev.xsoz.core.setting.StringSetting;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test support for the C5 suite: a pinned clock, a module catalog, and a store builder.
 *
 * <p><strong>The clock is pinned in every test.</strong> A store whose timestamps come
 * from {@code Instant.now()} cannot be asserted about, and an assertion about "when"
 * that passes or fails with the wall clock is not an assertion.</p>
 */
final class ConfigTestSupport {

    /** The instant every test store treats as "now". Unit: ISO-8601 instant, UTC. */
    static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    /** A clock pinned to {@link #NOW}. */
    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /** The module ids the test catalog knows. */
    static final Set<String> KNOWN_MODULES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "hud.totem-counter", "latency.crystal-release", "perf.sodium-tweaks")));

    /** The id the test catalog declares Tier C, so the drop path is reachable. */
    static final String TIER_C_MODULE = "perf.auto-aim";

    /**
     * A catalog that knows three modules, declares real schemas for the two the shipped
     * default profile carries, and reports a Tier C id that is deliberately NOT in
     * {@link #KNOWN_MODULES} so it can be planted in a file.
     *
     * <p>The schemas declare exactly the keys the shipped default stores, so a load of that
     * file is CLEAN and a round-trip of it is lossless. A test catalog that disagreed with
     * the shipped profile would only prove that the loader drops unknown keys.</p>
     */
    static ModuleCatalog catalog() {
        return new ModuleCatalog() {
            @Override
            public Optional<SettingsSchema> settingsSchemaFor(String moduleId) {
                if ("hud.totem-counter".equals(moduleId)) {
                    return Optional.of(SettingsSchema.builder("hud.totem-counter")
                            .add(new BooleanSetting("hud.totem-counter.show-carry-count",
                                    "Show carry count", "Draws the number of totems carried",
                                    true, false))
                            .add(new StringSetting("hud.totem-counter.style", "Style",
                                    "How the readout is laid out", "COMPACT", 16,
                                    Pattern.compile("[A-Z]+"), false))
                            .build());
                }
                if ("latency.crystal-release".equals(moduleId)) {
                    return Optional.of(SettingsSchema.builder("latency.crystal-release")
                            .add(new IntSetting("latency.crystal-release.release-timeout-millis",
                                    "Release timeout", "How long a release stays predicted",
                                    1500, 0, 5000, 50, "millis", false))
                            .build());
                }
                if ("perf.sodium-tweaks".equals(moduleId)) {
                    return Optional.of(SettingsSchema.builder("perf.sodium-tweaks")
                            .add(new IntSetting("perf.sodium-tweaks.frame-cap-fps", "Frame cap",
                                    "Client frame cap; 0 means uncapped", 0, 0, 500, 5, "frames",
                                    false))
                            .build());
                }
                return Optional.empty();
            }

            @Override
            public Set<String> knownModuleIds() {
                return KNOWN_MODULES;
            }

            @Override
            public Optional<ComplianceTier> tierOf(String moduleId) {
                if (TIER_C_MODULE.equals(moduleId)) {
                    return Optional.of(ComplianceTier.C);
                }
                if (!KNOWN_MODULES.contains(moduleId)) {
                    return Optional.empty();
                }
                return Optional.of(ComplianceTier.A);
            }

            @Override
            public String toString() {
                return "ModuleCatalog[test]";
            }
        };
    }

    /**
     * @param configDir the config directory
     * @return an open store with the test catalog and a pinned clock
     */
    static FileProfileStore store(Path configDir) {
        return FileProfileStore.open(configDir, clock(), catalog(),
                FileProfileStore.DEFAULT_BUNDLED_COMPLIANCE_IDS, new DefaultBindPolicy());
    }

    /**
     * @param configDir the config directory
     * @return an open store with NO catalog, which is what a build without a module
     *         registry has
     */
    static FileProfileStore storeWithoutCatalog(Path configDir) {
        return FileProfileStore.open(configDir, clock(), ModuleCatalog.none(),
                FileProfileStore.DEFAULT_BUNDLED_COMPLIANCE_IDS, new DefaultBindPolicy());
    }

    private ConfigTestSupport() {
        throw new AssertionError("ConfigTestSupport is a holder and is not instantiable.");
    }
}
