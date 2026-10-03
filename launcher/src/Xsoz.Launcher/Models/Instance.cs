namespace Xsoz.Launcher.Models;

/// <summary>Where a Minecraft installation was found on this machine.</summary>
public enum InstanceSource
{
    /// <summary>A launcher-managed instance under %LOCALAPPDATA%\XsozClient\instances.</summary>
    Xsoz,

    /// <summary>The stock Mojang install at %APPDATA%\.minecraft.</summary>
    Mojang,

    /// <summary>A PrismLauncher instance.</summary>
    PrismLauncher,

    /// <summary>A Lunar Client install.</summary>
    LunarClient,
}

/// <summary>Whether an instance is actually runnable, and if not, why.</summary>
public enum InstanceReadiness
{
    /// <summary>Client jar, libraries, natives and the loader are all present.</summary>
    Ready,

    /// <summary>The game files are there but the mod loader for this version is not installed.</summary>
    LoaderMissing,

    /// <summary>Only part of the install is present.</summary>
    Incomplete,

    /// <summary>The directory exists but has nothing recognisable in it.</summary>
    Empty,
}

/// <summary>A Minecraft installation discovered on this machine.</summary>
public sealed class DiscoveredInstance
{
    /// <summary>Display name for the list.</summary>
    public string DisplayName { get; set; } = string.Empty;

    /// <summary>Root of the instance (the folder containing <c>versions</c> and <c>assets</c>).</summary>
    public string RootPath { get; set; } = string.Empty;

    /// <summary>Which launcher owns it.</summary>
    public InstanceSource Source { get; set; }

    /// <summary>Game version ids found in <c>versions/</c>, newest first.</summary>
    public List<string> Versions { get; set; } = [];

    /// <summary>Number of jars in the instance's <c>mods/</c> folder. Read only, never modified.</summary>
    public int ModFileCount { get; set; }

    /// <summary>Total size on disk, in megabytes.</summary>
    public long SizeMb { get; set; }

    /// <summary>Whether a resource pack folder exists. Read only.</summary>
    public bool HasResourcePacks { get; set; }

    /// <summary>Last write time of the instance's <c>latest.log</c>, if there is one. Read only.</summary>
    public DateTime? LastPlayedLocal { get; set; }

    /// <summary>Computed readiness for a specific game version.</summary>
    public InstanceReadiness ReadinessFor(string versionId)
    {
        if (!Directory.Exists(RootPath))
        {
            return InstanceReadiness.Empty;
        }

        var hasVersions = Directory.Exists(Path.Combine(RootPath, "versions"))
                          && Versions.Count > 0;
        var hasVersion = hasVersions && Versions.Contains(versionId, StringComparer.OrdinalIgnoreCase);
        var hasAssets = Directory.Exists(Path.Combine(RootPath, "assets"));
        var hasLibraries = Directory.Exists(Path.Combine(RootPath, "libraries"));

        if (!hasVersion && !hasAssets && !hasLibraries)
        {
            return InstanceReadiness.Empty;
        }

        // The loader is a separate artifact from the vanilla client. A vanilla install can be
        // complete and still have no loader, and saying "ready" in that case would be a lie.
        var loaderPresent = HasLoader(versionId);
        if (!loaderPresent)
        {
            return hasVersion && hasAssets && hasLibraries ? InstanceReadiness.LoaderMissing : InstanceReadiness.Incomplete;
        }

        return hasVersion && hasAssets && hasLibraries ? InstanceReadiness.Ready : InstanceReadiness.Incomplete;
    }

    private bool HasLoader(string versionId)
    {
        try
        {
            var versionDir = Path.Combine(RootPath, "versions", versionId);
            var json = Path.Combine(versionDir, versionId + ".json");
            if (!File.Exists(json))
            {
                return false;
            }

            var text = File.ReadAllText(json);
            return text.Contains("fabric", StringComparison.OrdinalIgnoreCase)
                   && text.Contains("net.fabricmc.loader.impl.launch.knot", StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>Human-readable path to the launcher that owns this install.</summary>
    public string SourceLabel => Source switch
    {
        InstanceSource.Xsoz => "XsozClient",
        InstanceSource.Mojang => "Mojang",
        InstanceSource.PrismLauncher => "PrismLauncher",
        InstanceSource.LunarClient => "Lunar Client",
        _ => "Unknown",
    };
}

/// <summary>A local, offline-mode account.</summary>
public sealed class LocalAccount
{
    /// <summary>Display name, which is also the in-game name in offline mode.</summary>
    public string Name { get; set; } = "Player";

    /// <summary>
    /// The offline-mode UUID the game derives from the name. Recorded so the launch arguments are
    /// stable; nothing here is a secret and there is no refresh token to store.
    /// </summary>
    public string OfflineUuid { get; set; } = string.Empty;

    /// <summary>When the account was created.</summary>
    public DateTimeOffset CreatedUtc { get; set; } = DateTimeOffset.UtcNow;

    /// <summary>
    /// A blocky initial used as the avatar. Drawn from geometry, not from any game's assets.
    /// </summary>
    public string Initial => string.IsNullOrEmpty(Name) ? "?" : Name[..1].ToUpperInvariant();
}
