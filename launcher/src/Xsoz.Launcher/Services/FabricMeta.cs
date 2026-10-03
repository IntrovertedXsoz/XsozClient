using System.Text.Json;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>
/// Resolves the Fabric loader against Fabric's meta API and writes the resulting version profile
/// into the instance.
///
/// The whole path is meta.fabricmc.net's documented surface: the loader list picks the current
/// stable loader for the game version, and the <c>profile/json</c> endpoint returns the ready
/// launch profile, which is written verbatim as the instance's version JSON. That file is the
/// thing an install must end with; nothing else in this class is load-bearing.
/// </summary>
public sealed class FabricMeta
{
    private const string MetaBase = "https://meta.fabricmc.net/v2";

    private readonly DownloadService _net;

    /// <summary>Creates the API wrapper.</summary>
    public FabricMeta(DownloadService net) => _net = net;

    /// <summary>Resolves the current stable Fabric loader for a game version.</summary>
    public async Task<(string LoaderVersion, JsonElement Loader, JsonElement Intermediary)> ResolveLoaderAsync(
        string gameVersion, CancellationToken ct)
    {
        var text = await _net.GetStringAsync($"{MetaBase}/versions/loader/{gameVersion}", ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(text);

        JsonElement? chosen = null;
        foreach (var entry in doc.RootElement.EnumerateArray())
        {
            var loader = entry.GetProperty("loader");
            if (loader.TryGetProperty("stable", out var stable) && stable.GetBoolean())
            {
                chosen = entry;
                break;
            }

            chosen ??= entry;
        }

        if (chosen is null)
        {
            throw new InvalidDataException(
                $"Fabric's meta API lists no loader for {gameVersion}. That usually means the game version id is wrong, "
                + "or meta.fabricmc.net is unreachable and nothing may proceed that cannot be verified.");
        }

        var version = chosen.Value.GetProperty("loader").GetProperty("version").GetString()!;
        return (version, chosen.Value.GetProperty("loader").Clone(), chosen.Value.GetProperty("intermediary").Clone());
    }

    /// <summary>
    /// Fetches the launch profile for (game, loader) and writes it as the instance's version
    /// JSON under the given version id.
    /// </summary>
    /// <returns>The profile's raw JSON and the resolved profile id.</returns>
    public async Task<(string ProfileId, string ProfileJson)> FetchProfileAsync(
        string gameVersion, string loaderVersion, string profileId, string gameRoot, CancellationToken ct)
    {
        var url = $"{MetaBase}/versions/loader/{gameVersion}/{loaderVersion}/profile/json";
        var text = await _net.GetStringAsync(url, ct).ConfigureAwait(false);

        using var doc = JsonDocument.Parse(text);
        var root = doc.RootElement;

        // Re-tag the profile id: Fabric names it fabric-loader-<loader>-<game>, but the instance
        // directory is ours and the directory name is what the launch path looks up.
        text = RewriteProfileId(root, profileId);
        var profileDir = Path.Combine(gameRoot, "versions", profileId);
        Directory.CreateDirectory(profileDir);
        await File.WriteAllTextAsync(Path.Combine(profileDir, profileId + ".json"), text, ct).ConfigureAwait(false);

        return (profileId, text);
    }

    /// <summary>
    /// Builds the download plan for the loader's libraries: every library in the profile's
    /// common+client sections, resolved as maven coordinates against its declared repository.
    /// </summary>
    public static List<DownloadItem> PlanFabricLibraries(string profileJson, string gameRoot, string groupName)
    {
        using var doc = JsonDocument.Parse(profileJson);
        var items = new List<DownloadItem>();

        if (!doc.RootElement.TryGetProperty("libraries", out var libraries))
        {
            return items;
        }

        foreach (var lib in libraries.EnumerateArray())
        {
            var name = lib.GetProperty("name").GetString()!;
            var baseUrl = lib.TryGetProperty("url", out var url) ? url.GetString()! : "https://maven.fabricmc.net/";
            var path = MavenPath(name);
            if (path is null)
            {
                continue;
            }

            items.Add(new DownloadItem(
                baseUrl.TrimEnd('/') + "/" + path,
                Path.Combine(gameRoot, "libraries", path.Replace('/', Path.DirectorySeparatorChar)),
                lib.TryGetProperty("size", out var size) ? size.GetInt64() : -1,
                lib.TryGetProperty("sha1", out var sha1) ? sha1.GetString() : null,
                groupName));
        }

        return items;
    }

    /// <summary>
    /// Maven coordinate to repository-relative path: <c>group:artifact:version[:classifier]</c>
    /// becomes <c>group/artifact/version/artifact-version[-classifier].jar</c>.
    /// </summary>
    public static string? MavenPath(string coordinate)
    {
        var parts = coordinate.Split(':');
        if (parts.Length < 3)
        {
            return null;
        }

        var group = parts[0].Replace('.', '/');
        var artifact = parts[1];
        var version = parts[2];
        var classifier = parts.Length > 3 ? "-" + parts[3] : string.Empty;
        return $"{group}/{artifact}/{version}/{artifact}-{version}{classifier}.jar";
    }

    /// <summary>Preserves the profile byte-for-byte apart from the version id key.</summary>
    private static string RewriteProfileId(JsonElement root, string profileId)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented = true }))
        {
            writer.WriteStartObject();
            foreach (var property in root.EnumerateObject())
            {
                if (property.NameEquals("id"))
                {
                    writer.WriteString("id", profileId);
                }
                else
                {
                    writer.WritePropertyName(property.Name);
                    property.Value.WriteTo(writer);
                }
            }

            writer.WriteEndObject();
        }

        return System.Text.Encoding.UTF8.GetString(stream.ToArray());
    }
}
