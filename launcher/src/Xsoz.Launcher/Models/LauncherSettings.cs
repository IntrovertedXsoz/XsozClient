namespace Xsoz.Launcher.Models;

/// <summary>Launcher-wide settings. Persisted as a single JSON document.</summary>
public sealed class LauncherSettings
{
    /// <summary>Schema version, for forward migration.</summary>
    public int SchemaVersion { get; set; } = 1;

    /// <summary>Currently selected profile id.</summary>
    public string ActiveProfileId { get; set; } = string.Empty;

    /// <summary>Currently selected game version id.</summary>
    public string SelectedVersionId { get; set; } = "1.21.11";

    /// <summary>Maximum heap in megabytes. The minimum is always set equal to it.</summary>
    public int MaxHeapMb { get; set; } = 3072;

    /// <summary>Which collector to request.</summary>
    public GarbageCollector Collector { get; set; } = GarbageCollector.G1;

    /// <summary>Request the long-pause target. One tick is 50 ms.</summary>
    public bool SetMaxGcPause { get; set; } = true;

    /// <summary>Parallel GC worker threads. Half the physical cores on this machine.</summary>
    public int ParallelGcThreads { get; set; } = 4;

    /// <summary>Concurrent GC threads. The JDK default of cores/4 is far too many here.</summary>
    public int ConcurrentGcThreads { get; set; } = 2;

    /// <summary>Make sure nothing in the process can force a collection.</summary>
    public bool DisableExplicitGc { get; set; } = true;

    /// <summary>Commit the whole heap at startup so there are no first-touch faults mid-fight.</summary>
    public bool AlwaysPreTouch { get; set; } = true;

    /// <summary>Offload duplicate-string elimination to the collector's concurrent thread. G1 only.</summary>
    public bool UseStringDeduplication { get; set; } = true;

    /// <summary>
    /// Compact object headers. A product feature from Java 25 that is off by default and needs no
    /// unlock flag; roughly 22% less heap and 8% less CPU on the reference workload.
    /// </summary>
    public bool UseCompactObjectHeaders { get; set; } = true;

    /// <summary>Drop the Direct3D Java2D pipeline. Removes a native graphics API from the process.</summary>
    public bool DisableJava2dD3d { get; set; } = true;

    /// <summary>Where the JVM comes from.</summary>
    public JavaSource JavaSource { get; set; } = JavaSource.Auto;

    /// <summary>Absolute path to a custom java executable, when <see cref="JavaSource"/> is Custom.</summary>
    public string CustomJavaPath { get; set; } = string.Empty;

    /// <summary>Game window width in physical pixels.</summary>
    public int WindowWidth { get; set; } = 1920;

    /// <summary>Game window height in physical pixels.</summary>
    public int WindowHeight { get; set; } = 1080;

    /// <summary>Fullscreen mode shown in the status strip. Cosmetic readout; the real value lives in the mod config.</summary>
    public string FullscreenMode { get; set; } = "Windowed";

    /// <summary>
    /// Drop the launcher to a tray icon and stop all visual work while the game runs.
    /// On by default, and visible in the UI, because being conservative on a shared GPU is a
    /// feature rather than a limitation.
    /// </summary>
    public bool ReduceUsageWhilePlaying { get; set; } = true;

    /// <summary>Close to tray instead of exiting.</summary>
    public bool CloseToTray { get; set; } = true;

    /// <summary>Play the eight-pixel blocky caret blink on focused text fields. Off by default.</summary>
    public bool BlockyCaret { get; set; }

    /// <summary>Account mode. Online sign-in is not implemented; see <see cref="AccountMode"/>.</summary>
    public AccountMode AccountMode { get; set; } = AccountMode.Offline;

    /// <summary>The offline account's display name.</summary>
    public string OfflineName { get; set; } = "Player";

    /// <summary>Accept the MUG disclaimer. Required before the first launch.</summary>
    public bool AcceptedDisclaimer { get; set; }

    /// <summary>Has the first-run instance scan already happened? Keeps startup fast on subsequent runs.</summary>
    public bool HasScannedInstances { get; set; }

    /// <summary>Physical RAM in megabytes, recorded at first run for the heap recommendation.</summary>
    public long PhysicalMemoryMb { get; set; }
}

/// <summary>Which authentication path the launcher is using.</summary>
public enum AccountMode
{
    /// <summary>
    /// Local account. Single-player and offline-mode servers work. This is the shipped default
    /// because a third-party Microsoft client id cannot be reliably allowlisted - see the
    /// Accounts panel, which explains the blocker rather than hiding it.
    /// </summary>
    Offline,
}
