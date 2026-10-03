using System.Text.Json;
using System.Text.Json.Nodes;

namespace Xsoz.Launcher.Services;

/// <summary>What the merge produced, and whether the result is provably still a working launch.</summary>
public sealed record JvmMergeReport
{
    /// <summary>True only when every assertion held. Nothing is written when this is false.</summary>
    public required bool Ok { get; init; }

    /// <summary>Why it failed, in one sentence. Empty when <see cref="Ok"/>.</summary>
    public required string Message { get; init; }

    /// <summary>The <c>inheritsFrom</c> the fetched profile declared.</summary>
    public string InheritsFrom { get; init; } = string.Empty;

    /// <summary>The entry point the merged JSON declares.</summary>
    public string MainClass { get; init; } = string.Empty;

    /// <summary>True when the fetched Fabric JSON carried a gameDir before we patched one in.</summary>
    public bool FabricCarriedGameDir { get; init; }

    /// <summary>The game directory written into the version JSON.</summary>
    public string GameDir { get; init; } = string.Empty;

    /// <summary>Mojang's own rule-evaluated JVM arguments, preserved verbatim.</summary>
    public IReadOnlyList<string> VanillaJvmArgs { get; init; } = [];

    /// <summary>Fabric's own JVM arguments, preserved verbatim.</summary>
    public IReadOnlyList<string> FabricJvmArgs { get; init; } = [];

    /// <summary>The flags this launcher injected.</summary>
    public IReadOnlyList<string> InjectedFlags { get; init; } = [];

    /// <summary>Everything the launcher will pass, in order: vanilla, then Fabric, then ours.</summary>
    public IReadOnlyList<string> EffectiveJvmArgs { get; init; } = [];
}

/// <summary>
/// Produces the version JSON the official launcher will execute for our Fabric version.
///
/// WHY THE FLAGS GO HERE. A <c>custom</c> profile has no <c>javaArgsJVM</c> - the field does not
/// exist in the documented schema, in Fabric's own installer source, or in any live profile on
/// this machine - so a profile cannot carry JVM flags. The one artefact we fully own is the
/// version JSON under <c>versions\&lt;id&gt;\&lt;id&gt;.json</c>, and its <c>arguments.jvm</c>
/// array is exactly where a version's JVM flags belong. So the flags are appended there.
///
/// WHY IT IS A MERGE AND NOT A REPLACEMENT. The effective JVM command line is the parent's
/// <c>arguments.jvm</c> followed by the child's, then the main class. Mojang's half carries
/// <c>-Djava.library.path=${natives_directory}</c> and <c>-cp ${classpath}</c>; a Fabric half
/// carries the loader's emu argument. Replacing either with our own list produces a JVM with no
/// classpath, and the game does not start at all. Every merge here therefore ends in assertions
/// over the CONCATENATED list, not over our own contribution:
///
///   * exactly one <c>-cp</c> entry survives, and it still carries <c>${classpath}</c>;
///   * Mojang's <c>-Djava.library.path</c> entry survives verbatim;
///   * the main class is Fabric's <c>KnotClient</c>, not <c>net.minecraft.client.main.Main</c>;
///   * every injected flag is present;
///   * no injected flag touches tick rate, input sampling, mouse debounce or packet pacing.
///
/// A failure here is a refusal to write, not a warning.
///
/// WHY <c>UseCompactObjectHeaders</c> IS ABSENT. It is a Java 25 product flag. 1.21.11 declares
/// <c>javaVersion.majorVersion = 21</c>, and an unknown -XX flag makes the JVM exit rather than
/// fall back - so it is never emitted here, whatever any other part of the product thinks.
/// </summary>
public static class JvmFlagMerge
{
    /// <summary>
    /// The reviewed Java 21 set from docs/performance.md, in order. Memory and garbage collection
    /// only. Nothing in this list changes what the game does.
    /// </summary>
    public static IReadOnlyList<string> ReviewedFlags21 { get; } =
    [
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=50",
        "-XX:InitiatingHeapOccupancyPercent=40",
        "-XX:G1NewSizePercent=20",
        "-XX:G1MaxNewSizePercent=40",
        "-XX:+DisableExplicitGC",
        "-XX:+UseStringDeduplication",
        "-Dsun.java2d.d3d=false",
    ];

    /// <summary>
    /// The unlock that has to precede the three G1 tuning flags. They are
    /// <c>-XX:UnlockExperimentalVMOptions</c>-gated on modern JDKs, and an experimental flag
    /// passed without the unlock makes the JVM print usage and exit. It is inert in itself - it
    /// grants nothing, it only permits what follows - and the self-provisioned launch path in
    /// this same product emits it for the same three flags.
    /// </summary>
    /// <summary>
    /// Heap sizing, written to the PROFILE's <c>javaArgs</c> and never to the version JSON. The
    /// official launcher appends profile arguments after the version's, so a heap flag in the
    /// version JSON is silently overridden by whatever the profile (or the launcher's own
    /// <c>-Xmx2G</c> default) says - and an <c>-Xms</c> above that <c>-Xmx</c> makes the JVM exit
    /// with code 1 before a window opens. <c>-Xms</c> is kept below <c>-Xmx</c> so a user who edits
    /// the max down in the official launcher still gets a JVM that starts.
    /// </summary>
    public const string ProfileJavaArgs = "-Xms1G -Xmx3G";

    private const string ExperimentalUnlock = "-XX:+UnlockExperimentalVMOptions";

    /// <summary>
    /// Substrings that must never appear in an injected flag. Tick rate, input timing and packet
    /// pacing are server-detectable, so the ban is on the whole family, not on a known list.
    /// </summary>
    private static readonly string[] ForbiddenFlagFragments =
    [
        "tick", "minecraft.tps", "inputlag", "inputlagmultiplier", "mouse", "sensitivity",
        "packet", "disablefastest", "maxpacket", "lag", "-xx:tune",

        // Heap sizing belongs to the profile (see ProfileJavaArgs). In the version JSON it is
        // overridden by the launcher's appended default and can crash the JVM at start-up.
        "-xms", "-xmx", "alwayspretouch",
    ];

    /// <summary>Flags that only exist on Java 25 and are therefore refused outright for a Java 21 target.</summary>
    private static readonly string[] Java25OnlyFlags =
    [
        "-XX:+UseCompactObjectHeaders",
    ];

    /// <summary>The Fabric entry point. Fabric replaces the vanilla one; this is asserted, not assumed.</summary>
    public const string FabricMainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient";

    /// <summary>
    /// Merges the reviewed flags into the fetched Fabric profile JSON.
    /// </summary>
    /// <param name="fabricProfileJson">The document returned by meta.fabricmc.net, verbatim.</param>
    /// <param name="vanillaVersionJson">
    /// The <c>inheritsFrom</c> version's JSON, used only to assert that Mojang's required JVM
    /// arguments survive the merge. Never modified.
    /// </param>
    /// <param name="versionId">The namespaced version id we own.</param>
    /// <param name="gameDir">Our isolated game directory.</param>
    /// <param name="report">The merge result and its assertions.</param>
    /// <returns>The version JSON to write, or null when an assertion failed.</returns>
    public static string? Merge(
        string fabricProfileJson,
        JsonElement vanillaVersionJson,
        string versionId,
        string gameDir,
        out JvmMergeReport report)
    {
        JsonNode? root;
        try
        {
            root = JsonNode.Parse(fabricProfileJson);
        }
        catch (JsonException ex)
        {
            report = new JvmMergeReport { Ok = false, Message = "Fabric's profile JSON could not be parsed: " + ex.Message };
            return null;
        }

        if (root is not JsonObject fabric)
        {
            report = new JvmMergeReport { Ok = false, Message = "Fabric's profile JSON was not a JSON object." };
            return null;
        }

        var inheritsFrom = ReadString(fabric, "inheritsFrom");
        var mainClass = ReadString(fabric, "mainClass");
        var carriedGameDir = fabric.ContainsKey("gameDir");

        var vanillaJvm = EvaluateVanillaJvmArgs(vanillaVersionJson);
        var fabricJvm = ReadStringArray(fabric, "jvm");
        var injected = BuildInjectedFlags();

        var effective = new List<string>(vanillaJvm.Count + fabricJvm.Count + injected.Count);
        effective.AddRange(vanillaJvm);
        effective.AddRange(fabricJvm);
        effective.AddRange(injected);

        // ---- the assertions. Each one is a way this merge could produce a launcher that looks
        // ---- installed and does not start. None of them is advisory.

        if (!string.Equals(mainClass, FabricMainClass, StringComparison.Ordinal))
        {
            report = new JvmMergeReport
            {
                Ok = false,
                Message = $"Fabric's profile declares mainClass '{mainClass}', not {FabricMainClass}. "
                          + "Nothing was written; a launch would not have loaded the mod layer.",
                InheritsFrom = inheritsFrom,
                MainClass = mainClass,
            };
            return null;
        }

        if (vanillaJvm.Count == 0)
        {
            report = new JvmMergeReport
            {
                Ok = false,
                Message = $"The {inheritsFrom} version JSON has no arguments.jvm block, so Mojang's required JVM "
                          + "arguments could not be confirmed intact. Nothing was written.",
                InheritsFrom = inheritsFrom,
                MainClass = mainClass,
            };
            return null;
        }

        var classpathEntries = effective.Count(a =>
            a.StartsWith("-cp", StringComparison.Ordinal) || a.StartsWith("-classpath", StringComparison.Ordinal));

        if (classpathEntries != 1)
        {
            report = new JvmMergeReport
            {
                Ok = false,
                Message = $"The merged JVM arguments contain {classpathEntries} classpath entries; exactly one is required. "
                          + "A launch with none never starts, and with two takes whichever came last. Nothing was written.",
                InheritsFrom = inheritsFrom,
                MainClass = mainClass,
                VanillaJvmArgs = vanillaJvm,
                FabricJvmArgs = fabricJvm,
                InjectedFlags = injected,
                EffectiveJvmArgs = effective,
            };
            return null;
        }

        if (!effective.Any(a => a.StartsWith("-Djava.library.path=", StringComparison.Ordinal)))
        {
            report = new JvmMergeReport
            {
                Ok = false,
                Message = "Mojang's -Djava.library.path entry did not survive the merge, so the game's native "
                          + "libraries would not load. Nothing was written.",
                InheritsFrom = inheritsFrom,
                MainClass = mainClass,
                VanillaJvmArgs = vanillaJvm,
                FabricJvmArgs = fabricJvm,
                InjectedFlags = injected,
                EffectiveJvmArgs = effective,
            };
            return null;
        }

        foreach (var flag in injected)
        {
            if (!effective.Contains(flag, StringComparer.Ordinal))
            {
                report = new JvmMergeReport
                {
                    Ok = false,
                    Message = $"Flag {flag} did not survive the merge. Nothing was written.",
                    InheritsFrom = inheritsFrom,
                    MainClass = mainClass,
                    VanillaJvmArgs = vanillaJvm,
                    FabricJvmArgs = fabricJvm,
                    InjectedFlags = injected,
                    EffectiveJvmArgs = effective,
                };
                return null;
            }
        }

        var forbidden = FindForbiddenFlag(injected);
        if (forbidden is not null)
        {
            report = new JvmMergeReport
            {
                Ok = false,
                Message = $"Flag {forbidden} touches game behaviour rather than JVM tuning and is never emitted. "
                          + "Nothing was written.",
                InheritsFrom = inheritsFrom,
                MainClass = mainClass,
                InjectedFlags = injected,
                EffectiveJvmArgs = effective,
            };
            return null;
        }

        // ---- the patch. Only these members change; every other member Fabric sent is preserved
        // ---- as-is, including the md5/sha1/sha256 keys the launcher warns about and ignores.

        fabric["id"] = versionId;

        // The isolation, belt and braces. Fabric's profile JSON does not carry a gameDir - verified
        // against the version folder the official launcher actually launched on this machine - so
        // the profile is where the isolation really lives. Setting it here too costs nothing: the
        // launcher warns about a key it does not know and continues, which is exactly the behaviour
        // it already exhibits for Fabric's checksum keys.
        fabric["gameDir"] = gameDir;

        var jvmNode = new JsonArray();
        foreach (var argument in fabricJvm)
        {
            jvmNode.Add(argument);
        }

        foreach (var flag in injected)
        {
            jvmNode.Add(flag);
        }

        if (fabric["arguments"] is JsonObject arguments)
        {
            arguments["jvm"] = jvmNode;
        }
        else
        {
            fabric["arguments"] = new JsonObject
            {
                ["game"] = fabric["arguments"] is JsonObject existing && existing.ContainsKey("game")
                    ? existing["game"]?.DeepClone()
                    : new JsonArray(),
                ["jvm"] = jvmNode,
            };
        }

        report = new JvmMergeReport
        {
            Ok = true,
            Message = $"{vanillaJvm.Count} Mojang JVM argument(s) preserved, {fabricJvm.Count} Fabric argument(s) preserved, "
                      + $"{injected.Count} flag(s) appended. Main class {mainClass}; one -cp entry present.",
            InheritsFrom = inheritsFrom,
            MainClass = mainClass,
            FabricCarriedGameDir = carriedGameDir,
            GameDir = gameDir,
            VanillaJvmArgs = vanillaJvm,
            FabricJvmArgs = fabricJvm,
            InjectedFlags = injected,
            EffectiveJvmArgs = effective,
        };

        // Relaxed escaping so '+' stays a '+'. The default encoder writes -XX:+UseG1GC as
        // -XX:\u002BUseG1GC, which decodes identically for any conforming parser but turns the
        // version JSON into something nobody can read in a bug report.
        return fabric.ToJsonString(new JsonSerializerOptions
        {
            WriteIndented = true,
            Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        });
    }

    /// <summary>
    /// The exact flag list to append: the reviewed set, behind the experimental unlock the three
    /// G1 tuning flags require. No other flag is ever added.
    /// </summary>
    public static IReadOnlyList<string> BuildInjectedFlags()
    {
        var flags = new List<string> { ExperimentalUnlock };
        flags.AddRange(ReviewedFlags21);
        return flags;
    }

    /// <summary>The first injected flag that is banned, or null.</summary>
    public static string? FindForbiddenFlag(IEnumerable<string> flags)
    {
        foreach (var flag in flags)
        {
            foreach (var fragment in ForbiddenFlagFragments)
            {
                if (flag.Contains(fragment, StringComparison.OrdinalIgnoreCase))
                {
                    return flag;
                }
            }

            foreach (var banned in Java25OnlyFlags)
            {
                if (string.Equals(flag, banned, StringComparison.Ordinal))
                {
                    return flag;
                }
            }
        }

        return null;
    }

    /// <summary>
    /// Mojang's own <c>arguments.jvm</c>, rule-evaluated for this machine. The entries are kept as
    /// raw strings with their <c>${...}</c> placeholders intact - the launcher substitutes them,
    /// not this code, and a substituted value written back into the version JSON would be wrong for
    /// every machine but this one.
    /// </summary>
    public static List<string> EvaluateVanillaJvmArgs(JsonElement vanillaVersionJson)
    {
        var result = new List<string>();
        if (vanillaVersionJson.ValueKind != JsonValueKind.Object
            || !vanillaVersionJson.TryGetProperty("arguments", out var arguments)
            || !arguments.TryGetProperty("jvm", out var jvm)
            || jvm.ValueKind != JsonValueKind.Array)
        {
            return result;
        }

        foreach (var entry in jvm.EnumerateArray())
        {
            if (entry.ValueKind == JsonValueKind.String)
            {
                result.Add(entry.GetString() ?? string.Empty);
                continue;
            }

            if (entry.ValueKind != JsonValueKind.Object)
            {
                continue;
            }

            if (entry.TryGetProperty("rules", out var rules) && !MojangApi.ArgumentRuleMatches(rules))
            {
                continue;
            }

            if (!entry.TryGetProperty("value", out var value))
            {
                continue;
            }

            if (value.ValueKind == JsonValueKind.String)
            {
                result.Add(value.GetString() ?? string.Empty);
            }
            else if (value.ValueKind == JsonValueKind.Array)
            {
                result.AddRange(value.EnumerateArray().Select(v => v.GetString() ?? string.Empty));
            }
        }

        return result;
    }

    private static string ReadString(JsonObject obj, string name) =>
        obj.TryGetPropertyValue(name, out var node) && node is JsonValue value && value.TryGetValue<string>(out var text)
            ? text
            : string.Empty;

    private static List<string> ReadStringArray(JsonObject obj, string name)
    {
        var result = new List<string>();
        if (!obj.TryGetPropertyValue("arguments", out var argumentsNode) || argumentsNode is not JsonObject arguments)
        {
            return result;
        }

        if (!arguments.TryGetPropertyValue(name, out var arrayNode) || arrayNode is not JsonArray array)
        {
            return result;
        }

        foreach (var node in array)
        {
            if (node is JsonValue value && value.TryGetValue<string>(out var text))
            {
                result.Add(text);
            }
        }

        return result;
    }
}