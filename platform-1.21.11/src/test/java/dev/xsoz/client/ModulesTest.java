package dev.xsoz.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.module.ModuleManager;
import dev.xsoz.client.setting.ActionSetting;
import dev.xsoz.client.setting.Setting;
import dev.xsoz.client.trainer.crystal.CrystalTrainerModule;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs under Knot (fabric-loader-junit): every module builds, and the shipping rules hold. */
class ModulesTest {
    @BeforeAll
    static void register() {
        // Items and other registries need the vanilla bootstrap, exactly as the game does at start.
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
        if (ModuleManager.all().isEmpty()) XsozClient.registerModules();
    }

    @Test
    void thereAreManyModulesAndEveryCategoryIsUsed() {
        assertTrue(ModuleManager.all().size() >= 30, "module count " + ModuleManager.all().size());
        for (Category c : Category.values()) assertFalse(ModuleManager.in(c).isEmpty(), c.name());
    }

    @Test
    void idsAndNamesAreUnique() {
        Set<String> ids = new HashSet<>();
        for (Module m : ModuleManager.all()) assertTrue(ids.add(m.id()), "duplicate id " + m.id());
    }

    @Test
    void contestedModulesShipOff() {
        // The tier-B rule from the launcher's ValidateModuleTierDefaults, enforced in the jar too.
        for (Module m : ModuleManager.all()) {
            if (m.isContested()) assertFalse(m.defaultEnabled(), m.name() + " is contested and must default off");
        }
    }

    @Test
    void crystalTrainerIsOnByDefault() {
        assertTrue(ModuleManager.get(CrystalTrainerModule.class).defaultEnabled());
    }

    @Test
    void everySettingRoundTripsThroughJson() {
        for (Module m : ModuleManager.all()) {
            for (Setting<?> s : m.settings()) {
                if (s instanceof ActionSetting) continue;
                JsonElement e = s.toJson();
                s.fromJson(e);
                assertEquals(e, s.toJson(), m.name() + " / " + s.name());
            }
        }
    }

    @Test
    void descriptionsAreOneLine() {
        for (Module m : ModuleManager.all()) {
            assertFalse(m.description().contains("\n"), m.name());
            assertFalse(m.description().isBlank(), m.name());
        }
    }
}
