namespace Xsoz.Launcher.Services;

/// <summary>
/// Locates and describes the official Minecraft launcher's game directory on this machine.
///
/// Every path here is derived from <see cref="Environment.SpecialFolder.ApplicationData"/> or from
/// <c>%PROGRAMFILES%</c>. Nothing is hardcoded to one machine's username, and nothing is ever
/// created without the user being told it would be. The UWP-virtualised path under
/// <c>%LOCALAPPDATA%\Packages\...</c> is Bedrock-only and is deliberately never consulted.
///
/// The profile file name is FEATURE-DETECTED, not assumed: the current Store launcher
/// (bootstrap 2.6.2) keeps installations in the ordinary <c>launcher_profiles.json</c>, while
/// older builds used <c>launcher_profiles_microsoft_store.json</c>. Fabric's own installer still
/// enumerates both names. Assuming either one is how a launcher ends up writing a file the
/// launcher never reads.
/// </summary>
public sealed class MinecraftDirectory
{
    /// <summary>The folder name both launcher variants use for the Java Edition game directory.</summary>
    public const string FolderName = ".minecraft";

    /// <summary>The profile file the current Store launcher reads.</summary>
    public const string DefaultProfilesFileName = "launcher_profiles.json";

    /// <summary>The profile file name some older Store launcher builds used.</summary>
    public const string StoreVariantProfilesFileName = "launcher_profiles_microsoft_store.json";

    /// <summary>Creates the descriptor for a game root.</summary>
    /// <param name="root">The game directory. Never null; existence is a separate fact.</param>
    public MinecraftDirectory(string root) => Root = Path.GetFullPath(root);

    /// <summary>The default location: <c>%APPDATA%\.minecraft</c>.</summary>
    public static MinecraftDirectory Default { get; } = new(
        Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            FolderName));

    /// <summary>The game directory. Exists is a separate question, answered by <see cref="Exists"/>.</summary>
    public string Root { get; }

    /// <summary>True when the directory is actually on disk.</summary>
    public bool Exists => Directory.Exists(Root);

    /// <summary>The <c>versions</c> tree the launcher enumerates to build its version list.</summary>
    public string VersionsRoot => Path.Combine(Root, "versions");

    /// <summary>The shared Maven-style <c>libraries</c> tree. Fabric's jars live here, not in the version folder.</summary>
    public string LibrariesRoot => Path.Combine(Root, "libraries");

    /// <summary>
    /// The profile file to write, decided by what is on disk.
    /// <para>
    /// The ordinary name wins when both exist, because that is what the current build reads. The
    /// legacy store name is only chosen when it is the only one present. A machine with neither
    /// gets the ordinary name, which is correct for every launcher build in current support.
    /// </para>
    /// </summary>
    public string ProfilesFilePath => File.Exists(StoreVariantPath)
                                      && !File.Exists(DefaultProfilesPath)
        ? StoreVariantPath
        : DefaultProfilesPath;

    /// <summary>The ordinary profile file path.</summary>
    public string DefaultProfilesPath => Path.Combine(Root, DefaultProfilesFileName);

    /// <summary>The legacy Store-variant profile file path.</summary>
    public string StoreVariantPath => Path.Combine(Root, StoreVariantProfilesFileName);

    /// <summary>True when the chosen profile file is actually present.</summary>
    public bool ProfilesFileExists => File.Exists(ProfilesFilePath);

    /// <summary>True when the legacy store-named file is the only one present.</summary>
    public bool UsesLegacyStoreVariant => File.Exists(StoreVariantPath) && !File.Exists(DefaultProfilesPath);

    /// <summary>
    /// Every version id present on disk, whether or not it is in Mojang's manifest.
    /// <para>
    /// Read from the directory rather than from the manifest, because that is what the launcher
    /// itself does: its own log on this machine shows <c>GameVersionManager</c> parsing local
    /// Fabric and NeoForge folders that the manifest has never heard of, and then launching one.
    /// </para>
    /// </summary>
    public IReadOnlyList<string> InstalledVersions
    {
        get
        {
            try
            {
                if (!Directory.Exists(VersionsRoot))
                {
                    return [];
                }

                return Directory
                    .EnumerateDirectories(VersionsRoot)
                    .Select(Path.GetFileName)
                    .Where(name => !string.IsNullOrEmpty(name))
                    .Select(name => name!)
                    .OrderBy(name => name, StringComparer.OrdinalIgnoreCase)
                    .ToList();
            }
            catch (Exception)
            {
                return [];
            }
        }
    }

    /// <summary>
    /// True when the launcher has a complete vanilla install of a version: a folder whose
    /// <c>&lt;id&gt;.json</c> is present. The folder alone is not enough - a half-finished
    /// download leaves a directory and no JSON, and inheriting from that is how a launch fails
    /// after the button already said PLAY.
    /// </summary>
    public bool HasVersion(string versionId)
    {
        try
        {
            return !string.IsNullOrWhiteSpace(versionId) && File.Exists(VersionJsonPath(versionId));
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>The version JSON path for an id, whether or not it exists.</summary>
    public string VersionJsonPath(string versionId) =>
        Path.Combine(VersionsRoot, versionId, versionId + ".json");

    /// <summary>The version jar path for an id, whether or not it exists.</summary>
    public string VersionJarPath(string versionId) =>
        Path.Combine(VersionsRoot, versionId, versionId + ".jar");

    /// <summary>
    /// The plain sentence the interface shows when the game directory is not there. It says what
    /// is missing and who creates it, because the launcher does not: the directory only appears
    /// once the user has run the official launcher and signed in.
    /// </summary>
    public string MissingDirectorySentence =>
        $"The Minecraft launcher has no game folder at {Root} yet. "
        + "Open the official Minecraft Launcher, sign in, and let it create it once - then re-check here.";

    /// <summary>Creates the directory tree. Only ever called in response to an explicit user action.</summary>
    public bool TryCreate(out string? error)
    {
        try
        {
            Directory.CreateDirectory(Root);
            Directory.CreateDirectory(VersionsRoot);
            Directory.CreateDirectory(LibrariesRoot);
            error = null;
            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return false;
        }
    }
}