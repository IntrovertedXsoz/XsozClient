using System.Diagnostics;
using System.Runtime.InteropServices;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>One process that means the official launcher is open.</summary>
/// <param name="ProcessId">The process id.</param>
/// <param name="ProcessName">File name without extension, e.g. <c>Minecraft</c>.</param>
/// <param name="Reason">Which rule matched, in words, for the log line.</param>
public sealed record LauncherProcessHit(int ProcessId, string ProcessName, string Reason);

/// <summary>
/// The safety gate. Answers one question: is the official Minecraft launcher open right now?
///
/// WHY THE ANSWER IS A HARD GATE. The official launcher read-modify-writes
/// <c>launcher_profiles.json</c> and the <c>versions\</c> tree while it is running. Two writers on
/// those files at once is how a profile store gets truncated, and a truncated profile store on the
/// legacy launcher historically also held the login database. Fabric's own installer refuses to
/// run in this state for exactly the same reason. So this launcher refuses too, and refuses
/// completely: no write of any kind, not a partial one, not a harmless one.
///
/// HOW IT DETECTS. Three signals, in order of how much they prove:
///   1. Process named <c>Minecraft</c> or <c>MinecraftLauncher</c>. Name alone. The Store
///      launcher's main process carries no game-directory argument - observed live on this
///      machine, where only its GPU and renderer children name <c>--workdir=...\ .minecraft</c> -
///      so a command-line-only rule would miss the very process that matters most.
///   2. Process whose command line contains the game directory. This is what catches a
///      <c>javaw</c> the launcher has started and the legacy <c>MinecraftLauncher.exe</c>, and it
///      is deliberately scoped to the launcher game directory so it cannot fire on unrelated Java.
///      Our own fallback instance lives under <c>%LOCALAPPDATA%\XsozClient</c>, not inside
///      <c>.minecraft</c>, so a fallback game running alongside can never trip this.
///   3. Executable path inside the Store package directory or named <c>MinecraftLauncher.exe</c>,
///      used when the command line could not be read at all.
///
/// The caller re-checks this immediately before every individual write, because the user can open
/// the launcher between two steps of a long install.
/// </summary>
public static class LauncherProcessWatch
{
    /// <summary>Executable names that are the launcher itself, regardless of arguments.</summary>
    private static readonly string[] LauncherProcessNames = ["minecraft", "minecraftlauncher"];

    /// <summary>The Store package directory prefix, matched case-insensitively against the exe path.</summary>
    private const string StorePackageMarker = "Microsoft.4297127D64EC6";

    /// <summary>
    /// The single calm line the interface shows when the gate is closed. One sentence, no alarm,
    /// no dialog, and the re-check is offered rather than demanded.
    /// </summary>
    public const string RefusalMessage =
        "The Minecraft Launcher is open. Close it and choose Re-check - nothing was written while it was running.";

    /// <summary>True when at least one process means the launcher is open.</summary>
    public static bool IsOpen(string minecraftRoot) => Find(minecraftRoot).Count > 0;

    /// <summary>
    /// Every process that means the launcher is open. Enumerates once and never throws: process
    /// enumeration races with exits constantly, and a gate that can crash is a gate that gets
    /// skipped.
    /// </summary>
    public static IReadOnlyList<LauncherProcessHit> Find(string minecraftRoot)
    {
        var hits = new List<LauncherProcessHit>();
        var needle = Normalise(minecraftRoot);
        var self = Environment.ProcessId;

        Process[] processes;
        try
        {
            processes = Process.GetProcesses();
        }
        catch (Exception ex)
        {
            AppLog.Shared.Warn("Could not enumerate processes for the launcher-state check (" + ex.GetType().Name + ").");
            return hits;
        }

        try
        {
            foreach (var process in processes)
            {
                try
                {
                    if (process.Id == self)
                    {
                        continue;
                    }

                    var name = process.ProcessName;
                    var bare = name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
                        ? name[..^4]
                        : name;

                    // Rule 1: the launcher's own executable name. Deliberately does not require a
                    // command line; see the type-level comment for why that matters.
                    if (LauncherProcessNames.Contains(bare, StringComparer.OrdinalIgnoreCase))
                    {
                        hits.Add(new LauncherProcessHit(process.Id, bare, "launcher executable name"));
                        continue;
                    }

                    // Rule 2: anything holding the game directory open. Catches the javaw the
                    // launcher started and the legacy launcher.
                    if (ProcessCommandLine.TryRead(process.Id, out var commandLine))
                    {
                        if (needle.Length > 0 && Normalise(commandLine).Contains(needle, StringComparison.Ordinal))
                        {
                            hits.Add(new LauncherProcessHit(process.Id, bare, "command line names the game directory"));
                            continue;
                        }
                    }

                    // Rule 3: the executable path, used only when the command line was unreadable.
                    var module = TryModulePath(process);
                    if (module is not null)
                    {
                        var modulePath = Normalise(module);
                        if (modulePath.Contains(StorePackageMarker, StringComparison.OrdinalIgnoreCase)
                            || bare.Equals("minecraftlauncher", StringComparison.OrdinalIgnoreCase))
                        {
                            hits.Add(new LauncherProcessHit(process.Id, bare, "executable path is the launcher"));
                        }
                    }
                }
                catch (Exception)
                {
                    // One unreadable process must not end the scan. The remaining rules still run
                    // for every other process, and an unreadable process is not evidence of safety.
                }
                finally
                {
                    process.Dispose();
                }
            }
        }
        finally
        {
            foreach (var process in processes)
            {
                process.Dispose();
            }
        }

        return hits;
    }

    /// <summary>
    /// One line describing the gate's state, for the log and for the interface. Never more than a
    /// sentence; the detail lives in the log line underneath it.
    /// </summary>
    public static string Describe(string minecraftRoot, IReadOnlyList<LauncherProcessHit> hits)
    {
        if (hits.Count == 0)
        {
            return "Minecraft Launcher is not running.";
        }

        var names = hits
            .Select(h => h.ProcessName + " (pid " + h.ProcessId + ")")
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Take(4);

        var more = hits.Count > 4 ? " and " + (hits.Count - 4) + " more" : string.Empty;
        return "Minecraft Launcher is running: " + string.Join(", ", names) + more + ".";
    }

    /// <summary>The full detector, for the diagnostic preflight.</summary>
    public static IReadOnlyList<string> RulesInUse { get; } =
    [
        "process name is Minecraft or MinecraftLauncher (name alone, no arguments required)",
        "process command line contains the official game directory (covers javaw and the legacy launcher)",
        "process executable path is the Store package or MinecraftLauncher.exe (command line unreadable)",
    ];

    private static string? TryModulePath(Process process)
    {
        try
        {
            if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            {
                return null;
            }

            return process.MainModule?.FileName;
        }
        catch (Exception)
        {
            // Access denied for a process owned by another user, or the process already exited.
            return null;
        }
    }

    /// <summary>
    /// Lower-cases and collapses separators so that <c>C:\Users\x\.minecraft</c> and
    /// <c>C:/Users/x/.minecraft/</c> compare equal. A string mismatch here would be a silent
    /// failure of the one rule that is supposed to catch the launcher.
    /// </summary>
    private static string Normalise(string path) =>
        path.Replace('/', '\\').TrimEnd('\\').ToLowerInvariant();
}