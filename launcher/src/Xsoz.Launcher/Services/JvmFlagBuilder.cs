using System.Runtime.InteropServices;
using Xsoz.Launcher.Models;

namespace Xsoz.Launcher.Services;

/// <summary>One JVM flag with the reason it is in the set.</summary>
/// <param name="Flag">The literal flag, without a leading dash on the name portion.</param>
/// <param name="Explanation">One line. Every flag in the set has to justify itself here.</param>
/// <param name="MinimumJavaMajor">Lowest Java feature release the flag exists on. 0 means every version.</param>
/// <param name="OnlyWhenG1">True for flags the G1 collector alone understands.</param>
/// <param name="Recommended">False for flags that are deliberately not in the default set.</param>
public sealed record JvmFlag(
    string Flag,
    string Explanation,
    int MinimumJavaMajor = 0,
    bool OnlyWhenG1 = false,
    bool Recommended = true);

/// <summary>One validation problem found while assembling the flag set.</summary>
/// <param name="Severity">Either <c>warn</c> or <c>error</c>.</param>
/// <param name="Message">What is wrong, in plain English.</param>
public readonly record struct JvmValidation(string Severity, string Message);

/// <summary>The assembled flag set plus everything needed to explain it.</summary>
public sealed class JvmFlagSet
{
    /// <summary>Flags to append, in order.</summary>
    public IReadOnlyList<string> Flags { get; init; } = [];

    /// <summary>Every flag that was considered, with its explanation, for the settings screen.</summary>
    public IReadOnlyList<JvmFlag> Considered { get; init; } = [];

    /// <summary>Problems found while assembling.</summary>
    public IReadOnlyList<JvmValidation> Validations { get; init; } = [];

    /// <summary>True when nothing blocks the launch.</summary>
    public bool IsUsable => Validations.All(v => !string.Equals(v.Severity, "error", StringComparison.Ordinal));

    /// <summary>The assembled set as one copyable string.</summary>
    public string AsCommandLineString => string.Join(' ', Flags);
}

/// <summary>
/// Builds the JVM flag set for a game version.
///
/// These flags are appended to Mojang's own argument block, never substituted for it. Replacing
/// the block removes the natives paths and the game will not start at all, which is the single
/// most common way a hand-rolled launcher breaks a launch.
///
/// The hard boundary, stated once and enforced here: a JVM flag may change how the JVM allocates,
/// collects, compiles and renders. It may never change what the game does. Nothing in this class
/// touches tick rate, input sampling, mouse debounce or packet pacing, because all four are
/// detectable by a server and all four are on the deny list.
/// </summary>
public static partial class JvmFlagBuilder
{
    /// <summary>The full catalogue, rendered on the settings screen with one-line explanations.</summary>
    public static IReadOnlyList<JvmFlag> Catalogue { get; } =
    [
        new("-Xms{heap}M -Xmx{heap}M",
            "Fixed heap. Equal on both ends so the collector never resizes mid-session, and committed "
            + "up front so there are no first-touch page faults in the middle of a fight."),

        new("-XX:+UseG1GC",
            "Explicitly request G1. On Java 17 and newer this matches the default and is a no-op that "
            + "documents the intent; on Java 8 it is the important flag, because Java 8 defaults to a "
            + "stop-the-world parallel collector.",
            MinimumJavaMajor: 0),

        new("-XX:MaxGCPauseMillis=50",
            "Target a worst-case pause of 50 ms, which is one game tick. A tight target is realistic on "
            + "a small client heap and keeps the worst frame close to the average one."),

        new("-XX:InitiatingHeapOccupancyPercent=40",
            "Start collecting a little before the heap is nearly full. Deliberately gentle: the popular "
            + "aggressive tuning sets collect old-generation too eagerly for a client and produce a long "
            + "stun every few minutes."),

        new("-XX:G1NewSizePercent=20 -XX:G1MaxNewSizePercent=40",
            "Keep the young generation between 20% and 40% of the heap. Narrow young generations are what "
            + "G1 is good at, and a client allocates a lot of short-lived garbage."),

        new("-XX:ParallelGCThreads={parallel}",
            "Four collector threads on eight physical cores. The JDK default would take the logical-core "
            + "count and take cores away from the render thread and the chunk workers."),

        new("-XX:ConcGCThreads={concurrent}",
            "Two concurrent-mark threads. The default is roughly a quarter of the logical cores, which is "
            + "far too many on this machine."),

        new("-XX:+DisableExplicitGC",
            "Make sure nothing in the process can force a collection. The game does not need to, and if "
            + "anything ever did it would show up as a frame-time spike."),

        new("-XX:+AlwaysPreTouch",
            "Commit the whole heap at startup. Costs two to three seconds of launch time and removes a "
            + "class of mid-session stall. A fair trade for a competitive client."),

        new("-XX:+UseStringDeduplication",
            "Hand duplicate-string elimination to the collector's concurrent thread. G1 only, off by "
            + "default, and modern Minecraft is extremely string-heavy.",
            OnlyWhenG1: true),

        new("-XX:+UseCompactObjectHeaders",
            "Compact object headers. A product feature from Java 25 that is off by default and needs no "
            + "experimental unlock flag. On the reference workload: 22% less heap and 8% less CPU. The "
            + "single best flag available on a heap-constrained laptop, and it does not exist before 25.",
            MinimumJavaMajor: 25),

        new("-Dsun.java2d.d3d=false",
            "Drop the Direct3D Java2D pipeline. The resource and font path uses BufferedImage, which on "
            + "Windows otherwise reaches for D3D. This removes a native graphics API, a device-loss "
            + "failure mode and a class of driver crash from the process."),

        new("-XX:+UseZGC",
            "ZGC. Concurrent and low-pause, but it costs 5-15% throughput against G1, its threads compete "
            + "with the render thread for the same eight cores, and at exactly 16 GB its memory overhead "
            + "is not affordable. Available as an experiment; never the default. Does not exist on Java 8.",
            MinimumJavaMajor: 11,
            Recommended: false),

        new("-XX:+UseShenandoahGC",
            "Shenandoah. Genuinely competitive client results, but OpenJDK builds only, never in Java 8, "
            + "and the low value of tuning it on a 3 GB client heap. Experiment only.",
            MinimumJavaMajor: 12,
            Recommended: false),

        new("-XX:+AggressiveOpts / -XX:+UseBiasedLocking / -XX:+UseFastAccessorMethods",
            "Java 7-era flags. Valid on Java 8, removed from modern JDKs, and -XX:+AggressiveOpts was "
            + "removed in Java 12. Setting a removed flag makes the JVM refuse to start, which is why "
            + "they are catalogued and never emitted.",
            Recommended: false),
    ];

    /// <summary>
    /// Assembles the flag set for a version.
    /// </summary>
    /// <param name="version">The target game version.</param>
    /// <param name="settings">Launcher settings, which supply the heap and collector choice.</param>
    public static JvmFlagSet Build(GameVersionDescriptor version, LauncherSettings settings)
    {
        var flags = new List<string>();
        var validations = new List<JvmValidation>();
        var javaMajor = version.JavaMajor;

        // Heap. Modest by design: 16 GB shared with the OS, a browser and the integrated GPU's
        // shared-memory carve-out, and an 8-chunk client does not need 6 GB.
        var heap = ClampHeap(settings.MaxHeapMb, systemMemoryMb: MachineInfo.PhysicalMemoryMb());
        flags.Add($"-Xms{heap}M");
        flags.Add($"-Xmx{heap}M");

        // Collector, plus its hard version floor.
        var collector = settings.Collector;
        if (collector != GarbageCollector.G1)
        {
            var minimumJava = collector == GarbageCollector.Zgc ? 11 : 12;
            if (javaMajor < minimumJava)
            {
                validations.Add(new JvmValidation(
                    "error",
                    $"{collector} does not exist on Java {javaMajor}. It was introduced in Java {minimumJava}, "
                    + "and passing an unknown -XX flag makes the JVM refuse to start rather than fall back. "
                    + "Falling back to G1 for this launch."));
                collector = GarbageCollector.G1;
            }
            else
            {
                validations.Add(new JvmValidation(
                    "warn",
                    $"{collector} is selected. On this machine G1 is the recommended collector: ZGC's threads "
                    + "compete with the render thread for the same eight cores and its memory overhead is not "
                    + "affordable at 16 GB. Measure before you keep it."));
            }
        }

        flags.Add(collector switch
        {
            GarbageCollector.Zgc => "-XX:+UseZGC",
            GarbageCollector.Shenandoah => "-XX:+UseShenandoahGC",
            _ => "-XX:+UseG1GC",
        });

        // ZGC is concurrent and low-pause, but it is not the G1 tuning surface: the pause target
        // and the young-generation percentages are G1 knobs and have no effect on it.
        var isG1 = collector == GarbageCollector.G1;

        if (settings.SetMaxGcPause && isG1)
        {
            flags.Add("-XX:MaxGCPauseMillis=50");
        }

        if (isG1)
        {
            // The G1 young-generation percentages are experimental flags on modern JDKs; without
            // the unlock, the JVM exits rather than starting. The unlock itself is inert.
            flags.Add("-XX:+UnlockExperimentalVMOptions");
            flags.Add("-XX:InitiatingHeapOccupancyPercent=40");
            flags.Add("-XX:G1NewSizePercent=20");
            flags.Add("-XX:G1MaxNewSizePercent=40");
            // experimental only up to the unlock; leave it off when the un-tuned flags are not
            // emitted, and emit it only once.
        }

        if (settings.ParallelGcThreads > 0)
        {
            flags.Add($"-XX:ParallelGCThreads={settings.ParallelGcThreads}");
        }

        if (settings.ConcurrentGcThreads > 0)
        {
            flags.Add($"-XX:ConcGCThreads={settings.ConcurrentGcThreads}");
        }

        if (settings.DisableExplicitGc)
        {
            flags.Add("-XX:+DisableExplicitGC");
        }

        if (settings.AlwaysPreTouch)
        {
            flags.Add("-XX:+AlwaysPreTouch");
        }

        if (settings.UseStringDeduplication && isG1)
        {
            flags.Add("-XX:+UseStringDeduplication");
        }

        if (settings.UseCompactObjectHeaders)
        {
            if (javaMajor >= 25)
            {
                flags.Add("-XX:+UseCompactObjectHeaders");
            }
            else
            {
                validations.Add(new JvmValidation(
                    "warn",
                    $"Compact object headers are not available on Java {javaMajor}. They are a Java 25 feature and "
                    + "the flag is not being passed. No action needed."));
            }
        }

        if (settings.DisableJava2dD3d)
        {
            flags.Add("-Dsun.java2d.d3d=false");
        }

        if (heap > SystemMemorySafeHalfMb())
        {
            validations.Add(new JvmValidation(
                "warn",
                $"The heap is {heap / 1024.0:0.#} GB, which is more than half of this machine's physical memory. "
                + "The game, Windows and the browser all compete for the same 16 GB, and overcommitting is how you "
                + "get a swapfile on an SSD. 3 GB is generous for an 8-chunk client."));
        }

        if (isG1)
        {
            var considered = Catalogue.Where(f => f.Recommended || IsOfferedFor(javaMajor, f, settings, collector)).ToList();
            return new JvmFlagSet { Flags = flags, Considered = considered, Validations = validations };
        }

        return new JvmFlagSet { Flags = flags, Considered = Catalogue.ToList(), Validations = validations };
    }

    private static bool IsOfferedFor(int javaMajor, JvmFlag flag, LauncherSettings settings, GarbageCollector collector) =>
        flag.Flag.StartsWith("-XX:+UseZGC", StringComparison.Ordinal) && collector == GarbageCollector.Zgc
        || flag.Flag.StartsWith("-XX:+UseShenandoahGC", StringComparison.Ordinal) && collector == GarbageCollector.Shenandoah
        || flag.MinimumJavaMajor != 0 && javaMajor >= flag.MinimumJavaMajor;

    /// <summary>
    /// Clamps the requested heap to a sane band. The floor exists because a modern client heap
    /// below 2 GB will start collecting constantly; the ceiling is half of physical RAM, because
    /// the rest belongs to the operating system, the browser and the GPU's shared memory.
    /// </summary>
    public static int ClampHeap(int requestedMb, long systemMemoryMb)
    {
        var floor = 2048;
        var ceiling = (int)Math.Max(floor, Math.Min(8192, systemMemoryMb / 2));
        return Math.Clamp(requestedMb <= 0 ? 3072 : requestedMb, floor, ceiling);
    }

    private static long SystemMemorySafeHalfMb() => MachineInfo.PhysicalMemoryMb() / 2;
}

/// <summary>
/// Physical memory lookup. Wrapped and P/Invoked rather than read from a framework helper,
/// because a launcher that throws on a capability query during startup is not shippable.
/// </summary>
public static partial class MachineInfo
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct MemoryStatusEx
    {
        public uint Length;
        public uint MemoryLoad;
        public ulong TotalPhys;
        public ulong AvailablePhys;
        public ulong TotalPageFile;
        public ulong AvailablePageFile;
        public ulong TotalVirtual;
        public ulong AvailableVirtual;
        public ulong AvailableExtended;
    }

    // Classic DllImport rather than LibraryImport: the source-generated variant requires
    // enabling unsafe code for the whole project to project one bool-returning kernel32 call,
    // which is not a trade worth making in a project that will grow.
    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MemoryStatusEx buffer);

    /// <summary>
    /// Total physical memory in megabytes, floored at 2 GB so heap clamping always has a usable
    /// band even if the counter fails.
    /// </summary>
    public static long PhysicalMemoryMb()
    {
        try
        {
            var status = new MemoryStatusEx { Length = (uint)Marshal.SizeOf<MemoryStatusEx>() };
            if (GlobalMemoryStatusEx(ref status))
            {
                return Math.Max(2048, (long)(status.TotalPhys / (1024 * 1024)));
            }
        }
        catch (Exception)
        {
            // Fall through to the conservative default.
        }

        try
        {
            return Math.Max(2048, GC.GetGCMemoryInfo().TotalAvailableMemoryBytes / (1024 * 1024));
        }
        catch (Exception)
        {
            return 8192;
        }
    }

    /// <summary>Logical processor count, floored at 1.</summary>
    public static int LogicalProcessors => Math.Max(1, System.Environment.ProcessorCount);
}
