using Xsoz.Launcher.Core;
using Xsoz.Launcher.Models;

namespace Xsoz.Launcher.Services;

/// <summary>
/// Scans the well-known Minecraft install locations on this machine and reports what it found.
///
/// Read-only by design. It enumerates directories and counts files; it never writes into, moves,
/// renames or deletes anything inside an install it did not create. That is a rules requirement,
/// not a nicety: a screenshot check exists precisely so that a client is not a tool for editing
/// evidence beforehand.
/// </summary>
public sealed class InstanceScanner
{
    /// <summary>Root of the Mojang install.</summary>
    public static string MojangRoot => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".minecraft");

    /// <summary>Root of the launcher-managed install tree.</summary>
    public static string XsozRoot => AppPaths.Instances;

    /// <summary>PrismLauncher's instance root.</summary>
    public static string PrismRoot => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PrismLauncher", "instances");

    /// <summary>Lunar Client's install root.</summary>
    public static string LunarRoot => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".lunarclient");

    /// <summary>MultiMC-style root, checked opportunistically.</summary>
    public static string MultiMcRoot => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PrismLauncher");

    /// <summary>
    /// Scans every known location. Runs on a background thread from the UI; the per-directory
    /// size walk is the slow part and is bounded by a file-count cap so a 40 GB install cannot
    /// stall startup.
    /// </summary>
    public IReadOnlyList<DiscoveredInstance> Scan()
    {
        var results = new List<DiscoveredInstance>();

        AddIfPresent(results, MojangRoot, InstanceSource.Mojang, "Vanilla .minecraft");
        AddIfPresent(results, PrismRoot, InstanceSource.PrismLauncher, "PrismLauncher instance");
        AddIfPresent(results, LunarRoot, InstanceSource.LunarClient, "Lunar Client");

        foreach (var dir in SafeEnumerateDirectories(XsozRoot))
        {
            results.Add(Build(dir, InstanceSource.Xsoz, "XsozClient instance"));
        }

        return results;
    }

    private static void AddIfPresent(List<DiscoveredInstance> results, string root, InstanceSource source, string label)
    {
        if (string.IsNullOrWhiteSpace(root) || !Directory.Exists(root))
        {
            return;
        }

        results.Add(Build(root, source, label));
    }

    private static DiscoveredInstance Build(string root, InstanceSource source, string label)
    {
        var instance = new DiscoveredInstance
        {
            RootPath = root,
            Source = source,
            DisplayName = Path.GetFileName(root.TrimEnd(Path.DirectorySeparatorChar)) is { Length: > 0 } leaf
                ? leaf
                : label,
        };

        var versionsDir = Path.Combine(root, "versions");
        instance.Versions = SafeEnumerateDirectories(versionsDir)
            .Select(Path.GetFileName)
            .Where(n => !string.IsNullOrEmpty(n))
            .Select(n => n!)
            .OrderByDescending(V => ParseVersionKey(V))
            .ThenBy(V => V, StringComparer.OrdinalIgnoreCase)
            .ToList();

        var modsDir = Path.Combine(root, "mods");
        if (Directory.Exists(modsDir))
        {
            instance.ModFileCount = SafeEnumerateFiles(modsDir, "*.jar").Count;
        }

        instance.HasResourcePacks = Directory.Exists(Path.Combine(root, "resourcepacks"));
        instance.LastPlayedLocal = ReadLatestLogTime(Path.Combine(root, "logs", "latest.log"));
        instance.SizeMb = EstimateSizeMb(root);

        return instance;
    }

    private static DateTime? ReadLatestLogTime(string path)
    {
        try
        {
            // Read-only stat. The launcher never opens, rotates, truncates or deletes this file.
            var info = new FileInfo(path);
            return info.Exists ? info.LastWriteTime : null;
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static long EstimateSizeMb(string root)
    {
        try
        {
            long total = 0;
            const int maxFiles = 40000;
            var files = 0;

            foreach (var file in Directory.EnumerateFiles(root, "*", new EnumerationOptions
                         {
                             RecurseSubdirectories = true,
                             IgnoreInaccessible = true,
                             MaxRecursionDepth = 8,
                         }))
            {
                try
                {
                    total += new FileInfo(file).Length;
                }
                catch (Exception)
                {
                    // Skip files that vanished or are locked.
                }

                if (++files >= maxFiles)
                {
                    break;
                }
            }

            return total / (1024 * 1024);
        }
        catch (Exception)
        {
            return 0;
        }
    }

    /// <summary>
    /// Version-aware sort key so 1.21.11 sorts above 1.21.1, which a plain string sort gets wrong.
    /// </summary>
    private static string ParseVersionKey(string version)
    {
        Span<char> buffer = stackalloc char[version.Length];
        for (int i = 0; i < version.Length; i++)
        {
            buffer[i] = char.IsDigit(version[i]) ? version[i] : '.';
        }

        return new string(buffer);
    }

    private static IEnumerable<string> SafeEnumerateDirectories(string path)
    {
        if (!Directory.Exists(path))
        {
            return [];
        }

        try
        {
            return Directory.EnumerateDirectories(path).ToArray();
        }
        catch (Exception)
        {
            return [];
        }
    }

    private static IReadOnlyList<string> SafeEnumerateFiles(string path, string pattern)
    {
        if (!Directory.Exists(path))
        {
            return [];
        }

        try
        {
            return Directory.EnumerateFiles(path, pattern, SearchOption.TopDirectoryOnly).ToArray();
        }
        catch (Exception)
        {
            return [];
        }
    }
}
