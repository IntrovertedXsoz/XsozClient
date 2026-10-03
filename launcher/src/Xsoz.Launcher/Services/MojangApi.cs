using System.Text.Json;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>One file the installer wants, resolved from an upstream manifest.</summary>
/// <param name="Url">Where to fetch it from.</param>
/// <param name="DestinationPath">Where to put it.</param>
/// <param name="SizeBytes">Expected size, or -1.</param>
/// <param name="Sha1Hex">Expected SHA-1, or null.</param>
/// <param name="Group">Which install step it belongs to, for progress attribution.</param>
/// <param name="NativesJar">True for an LWJGL-class natives jar, which also gets extracted.</param>
public sealed record DownloadItem(
    string Url,
    string DestinationPath,
    long SizeBytes,
    string? Sha1Hex,
    string Group,
    bool NativesJar = false);

/// <summary>The la-data the launch path needs from a resolved version pair.</summary>
public sealed class MojangVersion
{
    /// <summary>The vanilla version id, e.g. 1.21.11.</summary>
    public required string Id { get; init; }

    /// <summary>Main class from the vanilla version JSON.</summary>
    public required string MainClass { get; init; }

    /// <summary>The asset index id, e.g. 26.</summary>
    public required string AssetIndexId { get; init; }

    /// <summary>Rule-evaluated JVM argument entries (strings and rule-gated objects), raw JSON.</summary>
    public required JsonElement? JvmArguments { get; init; }

    /// <summary>Rule-evaluated game argument entries, raw JSON.</summary>
    public required JsonElement? GameArguments { get; init; }

    /// <summary>Library classpath relative paths (libraries/…), rule-evaluated, in order.</summary>
    public required List<string> LibraryPaths { get; init; }

    /// <summary>Natives jar paths that were downloaded, for extraction.</summary>
    public required List<string> NativesJarPaths { get; init; }

    /// <summary>The Java major version Mojang declares for this game version.</summary>
    public required int JavaMajor { get; init; }
}

/// <summary>
/// Walks Mojang's launcher metadata and produces the download plan and launch data.
///
/// Nothing about the layout is guessed: the version manifest names the version JSON, the version
/// JSON names the client, the libraries, the natives and the asset index, and the asset index
/// names every asset by hash. SHA-1 and sizes travel with every entry and are verified after
/// download.
/// </summary>
public sealed class MojangApi
{
    private const string VersionManifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private const string ResourceBase = "https://resources.download.minecraft.net/";

    private readonly DownloadService _net;

    /// <summary>Creates the API wrapper.</summary>
    public MojangApi(DownloadService net) => _net = net;

    /// <summary>
    /// Test seam for the failure drill: when set, this URL replaces the version manifest URL.
    /// Never set by the interactive path.
    /// </summary>
    public static string? ManifestUrlOverride { get; set; }

    /// <summary>Finds the version JSON URL for a game version id.</summary>
    public async Task<string> FindVersionJsonUrlAsync(string versionId, CancellationToken ct)
    {
        var text = await _net.GetStringAsync(ManifestUrlOverride ?? VersionManifestUrl, ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(text);
        if (!doc.RootElement.TryGetProperty("versions", out var versions))
        {
            throw new InvalidDataException("Mojang's version manifest did not contain a versions list.");
        }

        foreach (var entry in versions.EnumerateArray())
        {
            if (string.Equals(entry.GetProperty("id").GetString(), versionId, StringComparison.Ordinal))
            {
                return entry.GetProperty("url").GetString()
                       ?? throw new InvalidDataException($"The manifest entry for {versionId} has no URL.");
            }
        }

        throw new InvalidDataException(
            $"Mojang's manifest does not list {versionId}. The launcher can only install a version Mojang itself ships.");
    }

    /// <summary>
    /// Fetches the version JSON, writes it into the instance, and builds the download plan for
    /// the client jar, the rule-evaluated libraries, the windows natives and the assets.
    /// </summary>
    public async Task<(MojangVersion Version, List<DownloadItem> Items)> PlanGameDownloadAsync(
        string versionId, string versionJsonUrl, string gameRoot, CancellationToken ct)
    {
        var versionJsonText = await _net.GetStringAsync(versionJsonUrl, ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(versionJsonText, new JsonDocumentOptions
        {
            CommentHandling = JsonCommentHandling.Skip,
            AllowTrailingCommas = true,
        });

        var root = doc.RootElement;
        var items = new List<DownloadItem>();
        var libraryPaths = new List<string>();
        var nativesJars = new List<string>();

        // The version JSON itself travels with the instance; it is the launch definition.
        var versionDir = Path.Combine(gameRoot, "versions", versionId);
        Directory.CreateDirectory(versionDir);
        var versionJsonPath = Path.Combine(versionDir, versionId + ".json");
        await File.WriteAllTextAsync(versionJsonPath, versionJsonText, ct).ConfigureAwait(false);

        var mainClass = root.TryGetProperty("mainClass", out var mc) ? mc.GetString() ?? "net.minecraft.client.main.Main" : "net.minecraft.client.main.Main";
        var javaMajor = 21;
        if (root.TryGetProperty("javaVersion", out var jv) && jv.TryGetProperty("majorVersion", out var major))
        {
            javaMajor = major.GetInt32();
        }

        var assetIndexId = "26";
        if (root.TryGetProperty("assetIndex", out var ai) && ai.TryGetProperty("id", out var aiId))
        {
            assetIndexId = aiId.GetString() ?? assetIndexId;
        }

        // The vanilla client jar.
        if (root.TryGetProperty("downloads", out var downloads)
            && downloads.TryGetProperty("client", out var client))
        {
            items.Add(new DownloadItem(
                client.GetProperty("url").GetString()!,
                Path.Combine(versionDir, versionId + ".jar"),
                GetInt64(client, "size"),
                GetString(client, "sha1"),
                "Game files"));
        }
        else
        {
            throw new InvalidDataException($"The {versionId} version JSON has no client download.");
        }

        // Libraries, rule-evaluated. "Server" artifacts and non-windows natives are excluded;
        // windows natives are downloaded and then extracted so LWJGL can find its DLLs.
        var nativesDir = Path.Combine(gameRoot, "natives", "xsoz-" + versionId);
        if (root.TryGetProperty("libraries", out var libraries))
        {
            foreach (var lib in libraries.EnumerateArray())
            {
                if (!RulesAllow(lib))
                {
                    continue;
                }

                var name = lib.TryGetProperty("name", out var ln) ? ln.GetString() ?? string.Empty : string.Empty;
                if (!lib.TryGetProperty("downloads", out var dl))
                {
                    continue;
                }

                if (dl.TryGetProperty("artifact", out var artifact))
                {
                    var rel = artifact.GetProperty("path").GetString()!;
                    var dest = Path.Combine(gameRoot, "libraries", rel.Replace('/', Path.DirectorySeparatorChar));
                    items.Add(new DownloadItem(
                        artifact.GetProperty("url").GetString()!,
                        dest,
                        GetInt64(artifact, "size"),
                        GetString(artifact, "sha1"),
                        "Game files"));
                    libraryPaths.Add(rel);
                }

                if (lib.TryGetProperty("natives", out var natives))
                {
                    var classifier = ResolveWindowsClassifier(natives);
                    if (classifier is not null
                        && dl.TryGetProperty("classifiers", out var classifiers)
                        && classifiers.TryGetProperty(classifier, out var nativeArtifact))
                    {
                        var rel = nativeArtifact.GetProperty("path").GetString()!;
                        var dest = Path.Combine(gameRoot, "libraries", rel.Replace('/', Path.DirectorySeparatorChar));
                        items.Add(new DownloadItem(
                            nativeArtifact.GetProperty("url").GetString()!,
                            dest,
                            GetInt64(nativeArtifact, "size"),
                            GetString(nativeArtifact, "sha1"),
                            "Game files",
                            NativesJar: true));
                        nativesJars.Add(dest);

                        // The natives jar sits next to the library on the classpath in Mojang's
                        // own layout only for extraction; keep it OUT of the classpath itself.
                        Directory.CreateDirectory(nativesDir);
                    }
                }
            }
        }

        // Assets: the index first, then every object it names, by hash.
        string? assetIndexUrl = null;
        long assetIndexSize = -1;
        string? assetIndexSha1 = null;
        if (root.TryGetProperty("assetIndex", out var index))
        {
            assetIndexUrl = GetString(index, "url");
            assetIndexSize = GetInt64(index, "size");
            assetIndexSha1 = GetString(index, "sha1");
        }

        if (assetIndexUrl is not null)
        {
            items.Add(new DownloadItem(
                assetIndexUrl,
                Path.Combine(gameRoot, "assets", "indexes", assetIndexId + ".json"),
                assetIndexSize,
                assetIndexSha1,
                "Assets"));

            var indexJson = await _net.GetStringAsync(assetIndexUrl, ct).ConfigureAwait(false);
            using var indexDoc = JsonDocument.Parse(indexJson);
            if (indexDoc.RootElement.TryGetProperty("objects", out var objects))
            {
                foreach (var entry in objects.EnumerateObject())
                {
                    var hash = entry.Value.GetProperty("hash").GetString()!;
                    items.Add(new DownloadItem(
                        ResourceBase + hash[..2] + "/" + hash,
                        Path.Combine(gameRoot, "assets", "objects", hash[..2], hash),
                        GetInt64(entry.Value, "size"),
                        hash,
                        "Assets"));
                }
            }
        }

        JsonElement? jvmArgs = root.TryGetProperty("arguments", out var args) && args.TryGetProperty("jvm", out var jvmArgsEl)
            ? jvmArgsEl.Clone()
            : null;
        JsonElement? gameArgs = args.ValueKind != JsonValueKind.Undefined && args.TryGetProperty("game", out var gameArgsEl)
            ? gameArgsEl.Clone()
            : null;

        return (new MojangVersion
        {
            Id = versionId,
            MainClass = mainClass,
            AssetIndexId = assetIndexId,
            JvmArguments = jvmArgs,
            GameArguments = gameArgs,
            LibraryPaths = libraryPaths,
            NativesJarPaths = nativesJars,
            JavaMajor = javaMajor,
        }, items);
    }

    /// <summary>
    /// Mojang rule evaluation for libraries and argument entries: rules are applied in order and
    /// the last matching rule wins; with no match the default is allow. The launcher is a Windows
    /// x64 client and never requests the demo/feature branches.
    /// </summary>
    public static bool RulesAllow(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty("rules", out var rules))
        {
            return true;
        }

        var allowed = true;
        foreach (var rule in rules.EnumerateArray())
        {
            if (!RuleMatches(rule))
            {
                continue;
            }

            allowed = rule.TryGetProperty("action", out var action)
                      && string.Equals(action.GetString(), "allow", StringComparison.OrdinalIgnoreCase);
        }

        return allowed;
    }

    /// <summary>Evaluates the rules of a rule-gated argument entry (feature or os blocks).</summary>
    public static bool ArgumentRuleMatches(JsonElement rules)
    {
        foreach (var rule in rules.EnumerateArray())
        {
            if (!RuleMatches(rule))
            {
                continue;
            }

            return rule.TryGetProperty("action", out var action)
                   && string.Equals(action.GetString(), "allow", StringComparison.OrdinalIgnoreCase);
        }

        return false;
    }

    private static bool RuleMatches(JsonElement rule)
    {
        if (rule.TryGetProperty("features", out var features))
        {
            // We are not a demo, not in quick-play, and the resolution is declared separately.
            // A feature-gated argument that is not the resolution one is dropped.
            foreach (var feature in features.EnumerateObject())
            {
                if (!string.Equals(feature.Name, "has_custom_resolution", StringComparison.Ordinal)
                    && feature.Value.GetBoolean())
                {
                    return false;
                }
            }
        }

        if (rule.TryGetProperty("os", out var os))
        {
            if (os.TryGetProperty("name", out var osName))
            {
                var name = osName.GetString() ?? string.Empty;
                if (!string.Equals(name, "windows", StringComparison.OrdinalIgnoreCase))
                {
                    return false;
                }
            }

            if (os.TryGetProperty("arch", out var osArch))
            {
                var arch = osArch.GetString() ?? string.Empty;
                var onArm64 = System.Runtime.InteropServices.RuntimeInformation.OSArchitecture
                              == System.Runtime.InteropServices.Architecture.Arm64;
                // Mojang names arch "x86" (32-bit) and "arm64"; everything else is x86_64.
                var matches = arch.Equals("x86", StringComparison.OrdinalIgnoreCase)
                    ? System.Runtime.InteropServices.RuntimeInformation.OSArchitecture == System.Runtime.InteropServices.Architecture.X86
                    : arch.Equals("arm64", StringComparison.OrdinalIgnoreCase)
                        ? onArm64
                        : arch.Length == 0 || onArm64 || arch.Equals("x86_64", StringComparison.OrdinalIgnoreCase) || arch.Equals("amd64", StringComparison.OrdinalIgnoreCase);
                if (!matches)
                {
                    return false;
                }
            }
        }

        return true;
    }

    /// <summary>Resolves the natives classifier key for this machine ("natives-windows", etc.).</summary>
    private static string? ResolveWindowsClassifier(JsonElement natives)
    {
        if (!natives.TryGetProperty("windows", out var windows))
        {
            return null;
        }

        var classifier = windows.GetString() ?? string.Empty;
        if (classifier.Length == 0)
        {
            return null;
        }

        return classifier.Replace("${arch}",
            System.Runtime.InteropServices.RuntimeInformation.OSArchitecture == System.Runtime.InteropServices.Architecture.X64
                ? "x86_64"
                : "arm64",
            StringComparison.Ordinal);
    }

    private static string? GetString(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) ? value.GetString() : null;

    private static long GetInt64(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number ? value.GetInt64() : -1;
}
