using System.Text;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>
/// Writes the vanilla defaults and the JVM flag template into the instance.
///
/// options.txt is approached the way every launcher approaches it: a managed block, so our
/// defaults re-apply on every launch without stomping the keys the game owns. The block markers
/// are ours; anything vanilla writes elsewhere in the file survives untouched, and whatever
/// vanilla does to a managed key (a user changed their FOV in-game) gets corrected back next
/// launch rather than fought over mid-session.
///
/// The values are the legal-and-useful set from docs/performance.md and docs/rules-matrix.md:
/// nothing here is a mod, and nothing here touches timing, packets or tick rate. Fullbright is
/// the vanilla gamma slider at its own maximum, not a modded one.
/// </summary>
public static class GameConfigWriter
{
    /// <summary>The managed keys, in file order. Values are options.txt encodings.</summary>
    private static readonly (string Key, string Value)[] ManagedDefaults =
    [
        ("fov", "0.75"),                 // 90 degrees on the 30-110 scale
        ("guiScale", "2"),
        ("bobView", "false"),            // no view bobbing
        ("enableVsync", "true"),
        ("particles", "2"),              // minimal
        ("gamma", "1.0"),                // "fullbright" — the vanilla slider's own maximum
        ("maxFps", "60"),                // docs/performance.md recommends uncapped on this machine; see jvm-args note
        ("renderDistance", "8"),
        ("simulationDistance", "8"),
        ("renderClouds", "\"false\""),      // 1.21.11's actual cloud toggle (string enum)
        ("cloudRange", "2"),               // and its range floor, belt and braces
        ("cloudStatus", "\"off\""),        // written for older 1.21.x layouts; dropped silently when unused
        ("entityShadows", "false"),
        ("fullscreen", "true"),          // comment in the file says how to undo it: F11, or video settings
    ];

    private const string BeginMarker = "# xsoz-managed-defaults-begin";
    private const string EndMarker = "# xsoz-managed-defaults-end";

    /// <summary>Applies the defaults and writes the template copies the launcher re-reads.</summary>
    public static async Task ApplyDefaultsAsync(string gameRoot, CancellationToken ct)
    {
        Directory.CreateDirectory(gameRoot);
        var optionsPath = Path.Combine(gameRoot, "options.txt");
        var templateDir = Path.Combine(gameRoot, "config", "xsozclient");
        Directory.CreateDirectory(templateDir);

        var managed = BuildManagedBlock();

        // Write the managed block into the real options.txt, preserving everything else.
        var existing = File.Exists(optionsPath) ? await File.ReadAllTextAsync(optionsPath, ct).ConfigureAwait(false) : string.Empty;
        var merged = MergeManagedBlock(existing, managed);
        await File.WriteAllTextAsync(optionsPath, merged, ct).ConfigureAwait(false);

        // The template copies: what the launcher re-applies from, and the readable note about it.
        await File.WriteAllTextAsync(
            Path.Combine(templateDir, "options.defaults.txt"),
            managed, ct).ConfigureAwait(false);

        // The JVM argument template, exactly the reviewed set for Java 21 on a 16 GB machine.
        // docs/performance.md is the source; docs/rules-matrix.md is why nothing in here touches
        // timing. maxFps is set to 60 above because a brand-new machine's thermal story is easier
        // to explain than a cap removal; performance.md recommends raising it, and the Settings
        // screen says so.
        var jvmArgs = new StringBuilder()
            .AppendLine("# XsozClient managed JVM arguments for Java 21 (16 GB machine).")
            .AppendLine("# Memory/GC only. No flag here touches tick rate, input timing or packets -")
            .AppendLine("# see docs/rules-matrix.md section 2.2 for why those are never emitted.")
            .AppendLine("-Xms3G -Xmx3G")
            .AppendLine("-XX:+UseG1GC")
            .AppendLine("-XX:MaxGCPauseMillis=50")
            .AppendLine("-XX:InitiatingHeapOccupancyPercent=40")
            .AppendLine("-XX:G1NewSizePercent=20 -XX:G1MaxNewSizePercent=40")
            .AppendLine("-XX:+DisableExplicitGC")
            .AppendLine("-XX:+AlwaysPreTouch")
            .AppendLine("-XX:+UseStringDeduplication")
            .AppendLine("-Dsun.java2d.d3d=false")
            .ToString();
        await File.WriteAllTextAsync(Path.Combine(templateDir, "jvm-args.txt"), jvmArgs, ct).ConfigureAwait(false);

        AppLog.Shared.Info("Wrote managed defaults to options.txt and the config template under config\\xsozclient.");
    }

    /// <summary>The managed block as it appears in options.txt between the markers.</summary>
    private static string BuildManagedBlock()
    {
        var sb = new StringBuilder();
        sb.AppendLine(BeginMarker + "  (XsozClient re-applies these on every launch; edit from the launcher or the game)");
        foreach (var (key, value) in ManagedDefaults)
        {
            sb.Append(key).Append(':').AppendLine(value);
        }

        sb.AppendLine(EndMarker);
        return sb.ToString();
    }

    /// <summary>Replaces the managed block inside an options.txt, or appends it when absent.</summary>
    private static string MergeManagedBlock(string existing, string managed)
    {
        var lines = existing.Replace("\r\n", "\n").Split('\n').ToList();
        var begin = lines.FindIndex(l => l.StartsWith(BeginMarker, StringComparison.Ordinal));
        var end = begin >= 0
            ? lines.FindIndex(begin + 1, l => l.StartsWith(EndMarker, StringComparison.Ordinal))
            : -1;

        var managedKeys = ManagedDefaults.Select(kv => kv.Key).ToHashSet(StringComparer.Ordinal);

        if (begin >= 0 && end > begin)
        {
            lines.RemoveRange(begin, end - begin + 1);
        }
        else
        {
            // Strip stray duplicates of our managed keys left by an interrupted earlier write.
            lines = lines
                .Where(l => !l.StartsWith(BeginMarker, StringComparison.Ordinal) && !l.StartsWith(EndMarker, StringComparison.Ordinal))
                .Where(l => !managedKeys.Contains(KeyOf(l)))
                .ToList();
            begin = lines.Count;
        }

        lines.InsertRange(begin, managed.Replace("\r\n", "\n").TrimEnd('\n').Split('\n'));
        return string.Join("\n", lines).TrimEnd('\n') + "\n";
    }

    private static string KeyOf(string line)
    {
        var colon = line.IndexOf(':', StringComparison.Ordinal);
        return colon > 0 ? line[..colon] : string.Empty;
    }
}
