using System.Text.Json;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>One mod the installer resolves from Modrinth.</summary>
/// <param name="Slug">The Modrinth project slug.</param>
/// <param name="DisplayName">The name shown in the log and the README.</param>
public sealed record ModDefinition(string Slug, string DisplayName);

/// <summary>What Modrinth reported about a resolved mod, before and after download.</summary>
public sealed class ResolvedMod
{
    /// <summary>The defining slug.</summary>
    public required string Slug { get; init; }

    /// <summary>The display name from the project page.</summary>
    public required string Title { get; set; }

    /// <summary>The licence id from the project page, e.g. LGPL-3.0-only.</summary>
    public string LicenceId { get; set; } = "unknown";

    /// <summary>The licence URL from the project page, for the README.</summary>
    public string LicenceUrl { get; set; } = string.Empty;

    /// <summary>The resolved version number, e.g. mc1.21.11-0.8.14-fabric.</summary>
    public string VersionNumber { get; set; } = string.Empty;

    /// <summary>True when the resolved build was published for a newer game version than asked for.</summary>
    public bool UsedFallbackVersion { get; set; }

    /// <summary>The game version the resolved build actually targets, when it differs.</summary>
    public string ResolvedGameVersion { get; set; } = string.Empty;

    /// <summary>The download.</summary>
    public string Url { get; set; } = string.Empty;

    /// <summary>The local file name.</summary>
    public string FileName { get; set; } = string.Empty;

    /// <summary>Expected size in bytes, or -1.</summary>
    public long SizeBytes { get; set; } = -1;

    /// <summary>Expected SHA-1, lowercase hex.</summary>
    public string Sha1Hex { get; set; } = string.Empty;
}

/// <summary>
/// The Modrinth v2 API, scoped to exactly two calls the installer needs: project metadata (for
/// the licence, which is read and recorded BEFORE anything is downloaded) and the version list
/// (for the actual file). No version number is hard-coded anywhere in this file; every mod is
/// resolved against the live API for the target game version, and a build that only exists for a
/// newer game version is taken with a log line rather than silently.
/// </summary>
public sealed class ModrinthApi
{
    private const string ApiBase = "https://api.modrinth.com/v2";

    /// <summary>
    /// The mod layer, in dependency order. Fabric API first because several others expect it to
    /// be present; the support library Cloth Config before the two mods that consume it.
    ///
    /// Every entry is fetched from Modrinth at install time. Nothing here is bundled, mirrored or
    /// embedded in the launcher, which is not tidiness: Sodium ships under PolyForm Shield,
    /// Entity Culling under its own protective licence, and Flashback and NPC Studio are All
    /// Rights Reserved. Downloading them to the user's own machine on their behalf is the only
    /// distribution any of those licences permits.
    /// </summary>
    public static IReadOnlyList<ModDefinition> Stack { get; } =
    [
        new("fabric-api", "Fabric API"),
        new("sodium", "Sodium"),
        new("lithium", "Lithium"),
        new("ferrite-core", "FerriteCore"),
        new("immediatelyfast", "ImmediatelyFast"),
        new("entityculling", "Entity Culling"),
        new("cloth-config", "Cloth Config"),
        new("iris", "Iris Shaders"),
        new("flashback", "Flashback"),
        new("npc-studio", "NPC Studio"),
    ];

    private readonly DownloadService _net;

    /// <summary>Creates the API wrapper.</summary>
    public ModrinthApi(DownloadService net) => _net = net;

    /// <summary>Reads the project record: name and licence. This happens before any download.</summary>
    public async Task<(string Title, string LicenceId, string LicenceUrl)> ReadProjectAsync(string slug, CancellationToken ct)
    {
        var text = await _net.GetStringAsync($"{ApiBase}/project/{slug}", ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(text);
        var root = doc.RootElement;

        var title = root.TryGetProperty("title", out var t) ? t.GetString() ?? slug : slug;
        string licenceId = "unknown";
        string licenceUrl = string.Empty;
        if (root.TryGetProperty("license", out var licence))
        {
            licenceId = licence.TryGetProperty("id", out var id) ? id.GetString() ?? "unknown" : "unknown";
            licenceUrl = licence.TryGetProperty("url", out var u) ? u.GetString() ?? string.Empty : string.Empty;
        }

        return (title, licenceId, licenceUrl);
    }

    /// <summary>
    /// Resolves a build of the project for the game version. Prefer an exact 1.21.11 match; if
    /// none exists, take the newest build that declares support for any game version numerically
    /// newer than the target (Fabric mods for adjacent minor versions are routinely compatible,
    /// and the log says which one was substituted).
    /// </summary>
    public async Task<ResolvedMod> ResolveAsync(string slug, string gameVersion, CancellationToken ct)
    {
        var (title, licenceId, licenceUrl) = await ReadProjectAsync(slug, ct).ConfigureAwait(false);

        var exact = await FetchVersionListAsync(slug, gameVersion, ct).ConfigureAwait(false);
        if (exact.Count > 0)
        {
            // A matching build that is a beta/alpha/pre/rc is never taken over a stable one -
            // a competitive client does not ship a beta renderer (docs/performance.md).
            var stable = exact.Where(v => !IsPrerelease(v.VersionNumber)).ToList();
            return Select(stable.Count > 0 ? stable : exact, slug, title, licenceId, licenceUrl, gameVersion, usedFallback: false);
        }

        AppLog.Shared.Warn($"{title}: no build tagged {gameVersion} on Modrinth; asking for the nearest newer build instead.");

        var all = await FetchVersionListAsync(slug, null, ct).ConfigureAwait(false);
        var newer = all
            .Where(v => v.GameVersions.Any(g => CompareVersions(g, gameVersion) >= 0))
            .OrderByDescending(v => v.DatePublished)
            .ToList();

        if (newer.Count == 0)
        {
            throw new InvalidDataException(
                $"Modrinth lists no Fabric build of {title} for {gameVersion} or anything newer. "
                + "Without it the mod layer is incomplete, so the install stops here rather than shipping half a stack.");
        }

        // Prefer a stable build; a beta is never shipped where a stable exists.
        var chosen = newer.FirstOrDefault(v => !IsPrerelease(v.VersionNumber)) ?? newer[0];
        var resolvedFor = chosen.GameVersions.OrderByDescending(g => g, StringComparer.OrdinalIgnoreCase).First();
        return Select([chosen, ..newer.Where(v => !ReferenceEquals(v, chosen))], slug, title, licenceId, licenceUrl, resolvedFor, usedFallback: true);
    }

    /// <summary>True for a pre-release version number (beta, alpha, pre, rc, snapshot).</summary>
    private static bool IsPrerelease(string versionNumber) =>
        versionNumber.Contains("beta", StringComparison.OrdinalIgnoreCase)
        || versionNumber.Contains("alpha", StringComparison.OrdinalIgnoreCase)
        || versionNumber.Contains("pre", StringComparison.OrdinalIgnoreCase)
        || versionNumber.Contains("rc", StringComparison.OrdinalIgnoreCase)
        || versionNumber.Contains("snapshot", StringComparison.OrdinalIgnoreCase);

    private static ResolvedMod Select(
        List<ModVersion> versions, string slug, string title, string licenceId, string licenceUrl,
        string resolvedGameVersion, bool usedFallback)
    {
        // The API returns newest-first; within a tied list the loader matrix rarely changes, so
        // the head of the filtered list is the build to take.
        var version = versions[0];
        var file = version.Files.FirstOrDefault(f => f.Primary) ?? version.Files[0];
        return new ResolvedMod
        {
            Slug = slug,
            Title = title,
            LicenceId = licenceId,
            LicenceUrl = licenceUrl,
            VersionNumber = version.VersionNumber,
            UsedFallbackVersion = usedFallback,
            ResolvedGameVersion = resolvedGameVersion,
            Url = file.Url,
            FileName = file.FileName,
            SizeBytes = file.Size,
            Sha1Hex = file.Sha1,
        };
    }

    private sealed record ModVersion(string VersionNumber, List<string> GameVersions, DateTime DatePublished, List<ModFile> Files);

    private sealed record ModFile(string Url, string FileName, long Size, string Sha1, bool Primary);

    private async Task<List<ModVersion>> FetchVersionListAsync(string slug, string? gameVersion, CancellationToken ct)
    {
        var url = $"{ApiBase}/project/{slug}/version?loaders=[%22fabric%22]";
        if (gameVersion is not null)
        {
            url += $"&game_versions=[%22{gameVersion}%22]";
        }

        var text = await _net.GetStringAsync(url, ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(text);

        var list = new List<ModVersion>();
        foreach (var v in doc.RootElement.EnumerateArray())
        {
            var files = new List<ModFile>();
            foreach (var f in v.GetProperty("files").EnumerateArray())
            {
                var sha1 = f.TryGetProperty("hashes", out var hashes) && hashes.TryGetProperty("sha1", out var s)
                    ? s.GetString() ?? string.Empty
                    : string.Empty;
                files.Add(new ModFile(
                    f.GetProperty("url").GetString()!,
                    f.GetProperty("filename").GetString()!,
                    f.TryGetProperty("size", out var size) ? size.GetInt64() : -1,
                    sha1,
                    f.TryGetProperty("primary", out var primary) && primary.GetBoolean()));
            }

            if (files.Count == 0)
            {
                continue;
            }

            list.Add(new ModVersion(
                v.TryGetProperty("version_number", out var vn) ? vn.GetString() ?? string.Empty : string.Empty,
                v.TryGetProperty("game_versions", out var gv)
                    ? gv.EnumerateArray().Select(e => e.GetString() ?? string.Empty).ToList()
                    : [],
                v.TryGetProperty("date_published", out var dp) && DateTime.TryParse(dp.GetString(), out var when)
                    ? when
                    : DateTime.MinValue,
                files));
        }

        return list;
    }

    /// <summary>
    /// Minecraft version ordering. The 26.x line is newer than the whole 1.x line; inside 1.x the
    /// components compare numerically. Deliberately simple - it exists to pick a fallback build,
    /// not to be a general semver.
    /// </summary>
    internal static int CompareVersions(string left, string right)
    {
        static int[] Parts(string v) =>
            v.Split(new[] { '.', '-', '+', ' ' }, StringSplitOptions.RemoveEmptyEntries)
             .Select(s => int.TryParse(s, out var n) ? n : 0)
             .ToArray();

        var a = Parts(left);
        var b = Parts(right);
        if (a.Length > 0 && b.Length > 0 && a[0] != b[0])
        {
            return a[0].CompareTo(b[0]);
        }

        var count = Math.Max(a.Length, b.Length);
        for (var i = 1; i < count; i++)
        {
            var av = i < a.Length ? a[i] : 0;
            var bv = i < b.Length ? b[i] : 0;
            if (av != bv)
            {
                return av.CompareTo(bv);
            }
        }

        return 0;
    }
}
