namespace Xsoz.Launcher.Core;

/// <summary>
/// Resolves every on-disk location the launcher owns. Everything lives under
/// %LOCALAPPDATA%\XsozClient so a launcher uninstall never touches a user file.
/// </summary>
public static class AppPaths
{
    private const string VendorFolder = "XsozClient";

    /// <summary>Root of all launcher-owned state.</summary>
    public static string Root { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), VendorFolder);

    /// <summary>Single-file .NET bundle extraction target, so a locked-down %TEMP% cannot break startup.</summary>
    public static string BundleExtract { get; } = Path.Combine(Root, ".net");

    /// <summary>Launcher-managed instance roots (%LOCALAPPDATA%\XsozClient\instances).</summary>
    public static string Instances { get; } = Path.Combine(Root, "instances");

    /// <summary>Cache of provisioned JREs, keyed by major version.</summary>
    public static string Runtimes { get; } = Path.Combine(Root, "runtimes");

    /// <summary>Our own mod layer, versioned by game version, never presented to the user as a mods folder.</summary>
    public static string ModLayer { get; } = Path.Combine(Root, "modlayer");

    /// <summary>Launcher-owned logs. Ours to rotate, ours to delete. The game's logs are never touched.</summary>
    public static string Logs { get; } = Path.Combine(Root, "logs");

    /// <summary>Persisted launcher state: settings, profiles, module toggles, accounts.</summary>
    public static string State { get; } = Path.Combine(Root, "state");

    /// <summary>User-exported profile bundles.</summary>
    public static string Exports { get; } = Path.Combine(Root, "exports");

    /// <summary>
    /// Reusable downloaded bytes that are not a game file: rendered skin heads, and nothing else
    /// yet. Under our own root, so nothing here can ever be mistaken for something that belongs in
    /// the official Minecraft launcher's directory.
    /// </summary>
    public static string Cache { get; } = Path.Combine(Root, "cache");

    /// <summary>
    /// Rendered skin-head PNGs, one per resolved account UUID. A head at 128px is about 700 bytes,
    /// so it is small enough to be worth keeping and large enough that re-fetching it every launch
    /// would be a network round trip to draw a 32px square. It is created lazily by
    /// <c>Services\HeadService</c> rather than here, so a launcher that never asks for a head does
    /// not create the folder.
    /// </summary>
    public static string Heads { get; } = Path.Combine(Cache, "heads");

    /// <summary>
    /// Pre-edit copies of files this launcher modified inside the official Minecraft launcher's
    /// directory, kept here rather than beside the original. The official launcher's own directory
    /// gets nothing added to it except the files we own: a backup folder there would be a second
    /// thing for the launcher to trip over, and this launcher promises not to litter somebody
    /// else's install.
    /// </summary>
    public static string Backups { get; } = Path.Combine(Root, "backups");

    /// <summary>
    /// Scratch space for a merge performed against a COPY of a file outside our control, used by
    /// the diagnostic preflight. Nothing in it is ever promoted to a real location.
    /// </summary>
    public static string Verification { get; } = Path.Combine(Root, "verification");

    /// <summary>Application data directory of the running executable, used for read-only data files.</summary>
    public static string AppData { get; } = AppContext.BaseDirectory;

    /// <summary>
    /// Creates the launcher-owned directory tree. Safe to call repeatedly; never touches
    /// anything outside <see cref="Root"/>.
    /// </summary>
    public static void EnsureCreated()
    {
        foreach (var dir in new[] { Root, BundleExtract, Instances, Runtimes, ModLayer, Logs, State, Exports, Backups, Verification })
        {
            Directory.CreateDirectory(dir);
        }
    }

    /// <summary>
    /// Guards a delete against escaping its intended root. Resolves the full path and
    /// compares the resolved prefix - string prefix checks are bypassable via '..' and junctions.
    /// </summary>
    public static bool IsInside(string root, string candidate)
    {
        try
        {
            var fullRoot = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            var fullCandidate = Path.GetFullPath(candidate);
            var rootWithSep = fullRoot + Path.DirectorySeparatorChar;
            return fullCandidate.StartsWith(rootWithSep, StringComparison.OrdinalIgnoreCase)
                   || string.Equals(fullCandidate, fullRoot, StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception)
        {
            return false;
        }
    }
}
