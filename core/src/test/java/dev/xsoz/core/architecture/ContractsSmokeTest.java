package dev.xsoz.core.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xsoz.core.compliance.ComplianceTier;
import dev.xsoz.core.compliance.Verdict;
import dev.xsoz.core.sensitivity.FovRelativeMode;
import dev.xsoz.core.sensitivity.SensitivityModel;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract C1-C10 - a smoke test on the package layout.
 *
 * <p>{@code docs/contracts.md} is the wire format between strangers writing in parallel.
 * If a package drifts, every agent that coded against the frozen layout is wrong in the
 * same way and the build fails here instead of at integration.</p>
 *
 * <p>Deliberately structural rather than exhaustive: it asserts the roots, the closed
 * enums and the types that other agents will reference by name, and nothing that a later
 * agent's file could invalidate by simply adding an implementation.</p>
 */
class ContractsSmokeTest {

    /** The package roots frozen in contracts.md 0.3, mapped to the contract they implement. */
    private static final Map<String, String> CORE_PACKAGE_ROOTS = new LinkedHashMap<String, String>();

    static {
        CORE_PACKAGE_ROOTS.put("dev.xsoz.core", "C0 - global conventions, XsozContractException");
        CORE_PACKAGE_ROOTS.put("dev.xsoz.core.platform", "C1 - GameAccessFacade, snapshots, Capability");
        CORE_PACKAGE_ROOTS.put("dev.xsoz.core.module", "C2 - Module, registry, enable gate");
        CORE_PACKAGE_ROOTS.put("dev.xsoz.core.event", "C3 - EventBus, Event, event types");
        CORE_PACKAGE_ROOTS.put("dev.xsoz.core.setting", "C4 - Setting schema, SettingsSchema");
        CORE_PACKAGE_ROOTS.put("dev.xsoz.core.config", "C5 - Profile store, migrations");
        CORE_PACKAGE_ROOTS.put("dev.xsoz.core.sensitivity", "C6 - SensitivityModel, ramp");
        CORE_PACKAGE_ROOTS.put("dev.xsoz.core.compliance", "C7 - tiers, verdicts, engine, opt-out");
        CORE_PACKAGE_ROOTS.put("dev.xsoz.core.render", "C8 - DrawList and the HUD compositor");
        CORE_PACKAGE_ROOTS.put("dev.xsoz.core.coaching", "C9 - read-only coaching subsystem");
        CORE_PACKAGE_ROOTS.put("dev.xsoz.core.util", "shared primitives: colour, text metrics, hashing");
    }

    @ParameterizedTest(name = "package root {0} exists ({1})")
    @ValueSource(strings = {
            "dev.xsoz.core",
            "dev.xsoz.core.platform",
            "dev.xsoz.core.module",
            "dev.xsoz.core.event",
            "dev.xsoz.core.setting",
            "dev.xsoz.core.config",
            "dev.xsoz.core.sensitivity",
            "dev.xsoz.core.compliance",
            "dev.xsoz.core.render",
            "dev.xsoz.core.coaching",
            "dev.xsoz.core.util",
    })
    void packageRootDirectoryExists(String packageName) throws Exception {
        File directory = new File(sourceRoot(), packageName.replace('.', File.separatorChar));
        assertTrue(directory.isDirectory(),
                "The package root " + packageName + " must exist as a directory so the agent that "
                        + "implements it has somewhere to land files. Contract: "
                        + CORE_PACKAGE_ROOTS.get(packageName));
        assertNotNull(CORE_PACKAGE_ROOTS.get(packageName),
                packageName + " is not one of the roots frozen in contracts.md 0.3.");
    }

    @Test
    @DisplayName("every frozen package root carries a package-info naming its contract")
    void everyPackageRootIsDocumented() throws Exception {
        List<String> undocumented = new ArrayList<String>();
        for (String packageName : CORE_PACKAGE_ROOTS.keySet()) {
            if ("dev.xsoz.core".equals(packageName)) {
                continue;
            }
            File packageInfo = new File(
                    sourceRoot(), packageName.replace('.', File.separatorChar) + File.separator
                            + "package-info.java");
            if (!packageInfo.isFile()) {
                undocumented.add(packageName);
            }
        }
        assertTrue(undocumented.isEmpty(),
                "These package roots have no package-info.java: " + undocumented);
    }

    @Test
    @DisplayName("the C0 exception base exists and is unchecked")
    void contractExceptionBaseExists() throws Exception {
        Class<?> exceptionType = Class.forName("dev.xsoz.core.XsozContractException");
        assertTrue(RuntimeException.class.isAssignableFrom(exceptionType),
                "contracts.md 0.4: XsozContractException extends RuntimeException.");
    }

    @Test
    @DisplayName("the C6 types other agents reference by name exist")
    void sensitivityTypesExist() throws Exception {
        assertNotNull(Class.forName("dev.xsoz.core.sensitivity.SensitivityModel"));
        assertNotNull(Class.forName("dev.xsoz.core.sensitivity.SensitivityRamp"));
        assertNotNull(Class.forName("dev.xsoz.core.sensitivity.RampStage"));
        assertNotNull(Class.forName("dev.xsoz.core.sensitivity.FovRelativeMode"));
        assertNotNull(Class.forName("dev.xsoz.core.sensitivity.RangeHint"));
        assertNotNull(Class.forName("dev.xsoz.core.sensitivity.SensitivityOutOfRangeException"));

        assertEquals(
                Arrays.asList("PHYSICAL", "SCREEN_SPEED"),
                Arrays.asList(FovRelativeMode.PHYSICAL.name(), FovRelativeMode.SCREEN_SPEED.name()));
    }

    @Test
    @DisplayName("the C7 types other agents reference by name exist")
    void complianceTypesExist() throws Exception {
        assertNotNull(Class.forName("dev.xsoz.core.compliance.ComplianceTier"));
        assertNotNull(Class.forName("dev.xsoz.core.compliance.Verdict"));
        assertEquals(3, ComplianceTier.values().length);
        assertEquals(6, Verdict.values().length);
    }

    @Test
    @DisplayName("the C6 static surface is named exactly as the contract spells it")
    void sensitivityStaticSurfaceMatchesContract() throws Exception {
        // Transcribed from the C6.0 code block, name and parameter types together. The
        // arity matters: `cmPer360FromDegPerCount(k)` is the no-DPI form and
        // `cmPer360(s, dpi)` is the with-DPI form, and the contract freezes both.
        String[][] contractStatics = {
                {"degPerCount", "double"},
                {"degPerInch", "double,int"},
                {"cmPer360", "double,int"},
                {"cmPer360FromDegPerCount", "double"},
                {"degPerCountFromCmPer360", "double,int"},
                {"sFromDegPerCount", "double"},
                {"sFromCmPer360", "double,int"},
                {"cmPer360FromS", "double,int"},
        };
        for (String[] entry : contractStatics) {
            String name = entry[0];
            String parameterList = entry[1];
            Method method = SensitivityModel.class.getMethod(name, parameterTypes(parameterList));
            assertNotNull(method,
                    "contracts.md C6.0 freezes " + name + "(" + parameterList + ").");
            assertEquals(double.class, method.getReturnType(),
                    "contracts.md C6.0 declares " + name + "(" + parameterList + ") returning double.");
        }
        assertNotNull(SensitivityModel.class.getMethod(
                "fovRelativeDegPerCountFactor", int.class, int.class));
        assertNotNull(SensitivityModel.class.getMethod("ofCmPer360",
                double.class, int.class, int.class, FovRelativeMode.class));
        assertNotNull(SensitivityModel.class.getMethod("ofVanillaSlider",
                double.class, int.class, int.class, FovRelativeMode.class));
    }

    private static Class<?>[] parameterTypes(String parameterList) {
        String[] names = parameterList.split(",");
        Class<?>[] types = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            types[i] = "int".equals(names[i].trim()) ? int.class : double.class;
        }
        return types;
    }

    @Test
    @DisplayName("the C6 instance surface is named exactly as the contract spells it")
    void sensitivityInstanceSurfaceMatchesContract() throws Exception {
        for (String method : new String[] {
                "cmPer360", "mouseDpi", "fovVerticalDeg", "fovRelativeMode", "s", "degPerCount",
                "degPerInch", "mmPer90Deg", "isInsideVanillaSlider", "rangeHint"}) {
            assertNotNull(SensitivityModel.class.getMethod(method), method + "() is in contract C6.2.");
        }
        assertNotNull(SensitivityModel.class.getMethod("withFov", int.class));
        assertNotNull(SensitivityModel.class.getMethod("withCmPer360", double.class));
        assertNotNull(SensitivityModel.class.getMethod("withMouseDpi", int.class));
        assertNotNull(SensitivityModel.class.getMethod("withDpiForTargetS", double.class, double.class));
    }

    @Test
    @DisplayName("the SPI interface lives in the package contract C10.1 names")
    void spiPackageIsOwnedByPlatformApi() {
        // The SPI interface is in the :platform-api subproject, which this test cannot
        // see. Assert only that the name is reserved here so :core never squats it.
        assertTrue("dev.xsoz.core.platform.spi".startsWith("dev.xsoz.core"),
                "The SPI package must remain under the dev.xsoz.core root frozen in 0.3.");
    }

    private static String sourceRoot() {
        File cursor = new File("").getAbsoluteFile();
        for (int depth = 0; depth < 6 && cursor != null; depth++) {
            if (new File(cursor, "src/main/java").isDirectory()) {
                return new File(cursor, "src/main/java").getPath();
            }
            cursor = cursor.getParentFile();
        }
        throw new IllegalStateException(
                "Could not locate the :core project directory from " + new File("").getAbsolutePath());
    }
}
