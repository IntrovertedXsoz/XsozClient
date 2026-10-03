using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Globalization;
using System.Windows.Input;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Models;
using Xsoz.Launcher.Services;

namespace Xsoz.Launcher.ViewModels;

/// <summary>One row in the JVM flag catalogue, read-only: the flag and why it is there.</summary>
public sealed class FlagRow
{
    /// <summary>Creates the row.</summary>
    public FlagRow(JvmFlag flag)
    {
        Flag = flag;
    }

    /// <summary>The catalogue entry.</summary>
    public JvmFlag Flag { get; }

    /// <summary>The literal flag.</summary>
    public string Resolved => Flag.Flag;

    /// <summary>
    /// The first sentence of the catalogue entry, which is the whole explanation the design's
    /// table has room for. The full two or three sentences are what the entry carries in code;
    /// truncating at the first full stop keeps the promise of the table without editing the
    /// reasoning.
    /// </summary>
    public string Explanation
    {
        get
        {
            var text = Flag.Explanation.Replace('\n', ' ').Trim();
            var stop = text.IndexOf(". ", StringComparison.Ordinal);
            return stop > 0 ? text[..(stop + 1)] : text;
        }
    }
}

/// <summary>
/// One of the four JVM flags that has a real boolean setting behind it, so it gets a real switch.
///
/// The design's table shows a switch in the first column of every row. Only four of the flags in
/// this launcher's actual catalogue are governed by a boolean in <see cref="LauncherSettings"/>,
/// so only those four get one. The rest of the catalogue is listed underneath without switches,
/// because a switch that does not switch anything is worse than no switch.
/// </summary>
public sealed class JvmFlagToggle : ObservableObject
{
    private readonly Func<bool> _get;
    private readonly Action<bool> _set;

    /// <summary>Creates a toggle row.</summary>
    public JvmFlagToggle(string label, string explanation, Func<bool> get, Action<bool> set)
    {
        Label = label;
        Explanation = explanation;
        _get = get;
        _set = set;
    }

    /// <summary>The literal flag, in the mono face.</summary>
    public string Label { get; }

    /// <summary>One line saying what it does.</summary>
    public string Explanation { get; }

    /// <summary>Whether the flag is in the set.</summary>
    public bool IsEnabled
    {
        get => _get();
        set
        {
            if (_get() == value)
            {
                return;
            }

            _set(value);
            Raise();
        }
    }
}

/// <summary>
/// The Settings screen: strictly flat. Section headings separated by 72px of whitespace, rows
/// with the label left and the value or control right, minimum 64px a row, and the launcher's
/// own log at the bottom of Advanced rather than behind a fifth navigation item.
///
/// Two rows in the design's mockup are not here, and their absence is deliberate rather than an
/// oversight: simulation distance and particle count have no persisted setting behind them in
/// this build - the writer emits fixed values for both - so a segmented control for either would
/// be a control that changes nothing on disk. Everything on this screen writes somewhere.
/// </summary>
public sealed class SettingsViewModel : ObservableObject, IDisposable
{
    private readonly ProfileService _store;
    private readonly InstallService _install;
    private int _heapMb;
    private string _probeResult = string.Empty;
    private string _statusMessage = string.Empty;
    private bool _disposed;

    /// <summary>Creates the screen.</summary>
    public SettingsViewModel(ProfileService store, InstallService install)
    {
        _store = store;
        _install = install;
        _heapMb = JvmFlagBuilder.ClampHeap(store.Settings.MaxHeapMb, MachineInfo.PhysicalMemoryMb());
        Diagnostics = new DiagnosticsViewModel(store);

        ProbeJavaCommand = RelayCommand.Create(() => _ = ProbeJavaAsync());
        OpenLogFolderCommand = RelayCommand.Create(() => AppLog.Shared.OpenLogFolder());
        OpenRuntimesCommand = RelayCommand.Create(OpenRuntimes);
        OpenInstanceCommand = RelayCommand.Create(OpenInstance);
        ResetJvmCommand = RelayCommand.Create(ResetJvm);
        AddFlagCommand = RelayCommand.Create(AddFlag, () => NewFlag.Trim().Length > 0);
        CopyLaunchCommandCommand = RelayCommand.Create(CopyLaunchCommand);
        LogFilterAllCommand = RelayCommand.Create(() => Diagnostics.MinimumLevel = null);
        LogFilterProblemsCommand = RelayCommand.Create(() => Diagnostics.MinimumLevel = LogLevel.Warn);

        BuildFlagRows();
    }

    /// <summary>Copies the launch command to the clipboard. Nothing is uploaded.</summary>
    public RelayCommand CopyLaunchCommandCommand { get; }

    /// <summary>The log viewer's All filter.</summary>
    public RelayCommand LogFilterAllCommand { get; }

    /// <summary>The log viewer's Problems filter: warnings and errors only.</summary>
    public RelayCommand LogFilterProblemsCommand { get; }

    /// <summary>The launcher log, embedded rather than navigated to.</summary>
    public DiagnosticsViewModel Diagnostics { get; }

    /// <summary>The live settings model.</summary>
    public LauncherSettings Settings => _store.Settings;

    /// <summary>The active profile. The video rows below are per profile, not global.</summary>
    public Profile Profile => _store.Active;

    /// <summary>The four governed flags, with a switch each.</summary>
    public ObservableCollection<JvmFlagToggle> FlagToggles { get; } = [];

    /// <summary>Every other flag in the catalogue for this version, read-only.</summary>
    public ResetableCollection<FlagRow> FlagRows { get; } = [];

    /// <summary>Probes the configured Java executable.</summary>
    public RelayCommand ProbeJavaCommand { get; }

    /// <summary>Opens the launcher's log folder.</summary>
    public RelayCommand OpenLogFolderCommand { get; }

    /// <summary>Opens the runtime cache folder.</summary>
    public RelayCommand OpenRuntimesCommand { get; }

    /// <summary>Opens the managed instance in Explorer.</summary>
    public RelayCommand OpenInstanceCommand { get; }

    /// <summary>Restores the recommended flag set.</summary>
    public RelayCommand ResetJvmCommand { get; }

    /// <summary>Adds a hand-written flag to the extra list.</summary>
    public RelayCommand AddFlagCommand { get; }

    // ---------------------------------------------------------------- instance

    /// <summary>"1.21.11 · Fabric 0.19.5 · Java 21".</summary>
    public string InstanceSummary
    {
        get
        {
            var version = _store.SelectedVersion;
            return $"{version.Label} · {version.LoaderName} {version.LoaderVersion} · Java {version.JavaMajor}";
        }
    }

    /// <summary>Where everything this launcher installs lives, in the mono face.</summary>
    public string InstancePath => _install.InstanceRoot;

    // ---------------------------------------------------------------- memory

    /// <summary>Heap in megabytes.</summary>
    public int HeapMb
    {
        get => _heapMb;
        set
        {
            var clamped = Math.Clamp(value, 512, 32768);
            if (!Set(ref _heapMb, clamped))
            {
                return;
            }

        Raise(nameof(HeapGb));
        Raise(nameof(HeapLabel));
        Raise(nameof(HeapFlag));
        Settings.MaxHeapMb = JvmFlagBuilder.ClampHeap(clamped, MachineInfo.PhysicalMemoryMb());
            _store.SaveSettings();
        }
    }

    /// <summary>The slider's value, in gigabytes, which is what the row shows.</summary>
    public double HeapGb => _heapMb / 1024.0;

    /// <summary>"4 GB", in Inter with tabular figures. Never Monocraft.</summary>
    public string HeapLabel => (_heapMb / 1024.0).ToString("0.#", CultureInfo.InvariantCulture) + " GB";

    /// <summary>The low tick under the slider.</summary>
    public string HeapMinLabel => "2 GB";

    /// <summary>The high tick under the slider.</summary>
    public string HeapMaxLabel => "8 GB";

    /// <summary>
    /// The helper under the Memory label. It reports what is actually on this machine rather
    /// than the mockup's illustrative sixteen.
    /// </summary>
    public string HeapNote =>
        $"Your PC has {MachineInfo.PhysicalMemoryMb() / 1024.0:0} GB. Leave room for Windows and your browser; "
        + "more is not always faster. The ceiling is half of it, because the rest belongs to the operating system "
        + "and the integrated GPU's shared memory.";

    /// <summary>The collector options, in the order the segmented control shows them.</summary>
    public IReadOnlyList<GarbageCollector> CollectorOptions { get; } =
        [GarbageCollector.G1, GarbageCollector.Zgc, GarbageCollector.Shenandoah];

    /// <summary>Index into <see cref="CollectorOptions"/>.</summary>
    public int CollectorIndex
    {
        get
        {
            var index = CollectorOptions.ToList().IndexOf(Settings.Collector);
            return index >= 0 ? index : 0;
        }
        set
        {
            if (value < 0 || value >= CollectorOptions.Count || CollectorOptions[value] == Settings.Collector)
            {
                return;
            }

            Settings.Collector = CollectorOptions[value];
            Commit();
            Raise();
        }
    }

    /// <summary>The flag the chosen collector actually emits, so the row is never a guess.</summary>
    public string CollectorFlag => Settings.Collector switch
    {
        GarbageCollector.Zgc => "-XX:+UseZGC",
        GarbageCollector.Shenandoah => "-XX:+UseShenandoahGC",
        _ => "-XX:+UseG1GC",
    };

    /// <summary>What the collector choice costs, stated before it is chosen.</summary>
    public string CollectorNote => Settings.Collector switch
    {
        GarbageCollector.Zgc =>
            "ZGC is concurrent and low-pause, but it costs throughput against G1 and its threads compete with the "
            + "render thread for the same cores. Measure before you keep it.",
        GarbageCollector.Shenandoah =>
            "Shenandoah posts the best client numbers and is not available in a Java 8 runtime. Experiment only.",
        _ => "G1 is the default collector, and a good balance for this game version.",
    };

    /// <summary>The heap row's flag, with the slider's value already resolved into it.</summary>
    public string HeapFlag => $"-Xms{HeapMb}M -Xmx{HeapMb}M";

    // ---------------------------------------------------------------- video

    /// <summary>The render-distance options the segmented control offers.</summary>
    public IReadOnlyList<int> RenderDistances { get; } = [8, 10, 12, 16];

    /// <summary>Index into <see cref="RenderDistances"/>, bound to the segmented control.</summary>
    public int RenderDistanceIndex
    {
        get
        {
            var index = RenderDistances.ToList().IndexOf(Profile.RenderDistance);
            return index >= 0 ? index : 0;
        }
        set
        {
            if (value < 0 || value >= RenderDistances.Count)
            {
                return;
            }

            Profile.RenderDistance = RenderDistances[value];
            Profile.Touch();
            _store.SaveProfiles();
            Raise();
        }
    }

    /// <summary>
    /// The one place the render-distance clamp is mentioned. The design puts it here and says it
    /// is the only place it is visible, which is what stops "why did my setting change?"
    /// </summary>
    public string RenderDistanceNote => "Also limited by the server's own distance when you join.";

    /// <summary>Frame-rate cap options, in frames per second. 0 is uncapped and is not offered here.</summary>
    public IReadOnlyList<int> FrameRates { get; } = [60, 120, 144, 165, 240, 360];

    /// <summary>Index into <see cref="FrameRates"/>, bound to the slider's snapped value.</summary>
    public int FrameRateIndex
    {
        get => FrameRates.ToList().IndexOf(Profile.MaxFramerate);
        set
        {
            if (value < 0 || value >= FrameRates.Count)
            {
                return;
            }

            Profile.MaxFramerate = FrameRates[value];
            Profile.Touch();
            _store.SaveProfiles();
            Raise();
            Raise(nameof(FrameRateLabel));
        }
    }

    /// <summary>The frame-rate readout, in Inter.</summary>
    public string FrameRateLabel => Profile.MaxFramerate <= 0
        ? "Uncapped"
        : Profile.MaxFramerate.ToString(CultureInfo.InvariantCulture);

    /// <summary>
    /// Shaders. This is the Iris Shaders module's own switch, not a second copy of the same
    /// setting: a module that is off in Mods and on in Settings would be two truths about one
    /// thing, and the launcher is not allowed to hold two.
    /// </summary>
    public bool ShadersOn
    {
        get => _store.GetModuleState("vis.iris");
        set
        {
            if (_store.SetModuleState("vis.iris", value))
            {
                Raise();
            }
        }
    }

    /// <summary>Why shaders are off to begin with.</summary>
    public string ShadersNote => "Costs the most frame time, so it starts off.";

    // ---------------------------------------------------------------- account

    /// <summary>
    /// The account line on the Settings screen. It is a statement about ownership, not a name: this
    /// launcher reads no credentials, holds no token and cannot display a player it never asked for.
    /// </summary>
    public string AccountName { get; set; } = AccountOwnership.ChipNameLong;

    /// <summary>The honest one-liner, in place of the removed "unable to find login token" line.</summary>
    public string LoginMessage { get; set; } = AccountOwnership.Notice;

    /// <summary>True when there is an account line to show. It is always true, on purpose.</summary>
    public bool HasLoginMessage => LoginMessage.Length > 0;

    /// <summary>
    /// Kept as a settable seam so the Settings XAML does not have to change shape. Nothing assigns
    /// it any more: the sign-in button is gone, because there is nothing left for it to do.
    /// </summary>
    public RelayCommand? SignInRequest { get; set; }

    // ---------------------------------------------------------------- advanced

    /// <summary>
    /// The launch command, with the access token redacted. Read-only, and it is a real assembly
    /// attempt: the button in Advanced copies exactly the string the game would be started with.
    /// </summary>
    public string LaunchCommand
    {
        get
        {
            try
            {
                var launch = new LaunchService(new InstanceScanner());
                var plan = launch.Plan(_store.SelectedVersion, Settings, Profile, Profile.Name);
                return plan.Command is { } command
                    ? command.Program + " " + command.Arguments
                    : "Not assembled yet: " + (plan.BlockerDetail.Length > 0 ? plan.BlockerDetail : "the instance is not installed.");
            }
            catch (Exception ex)
            {
                return "The launch command is not available yet: " + ex.Message;
            }
        }
    }

    /// <summary>The Java source options.</summary>
    public IReadOnlyList<string> JavaSources { get; } = ["Automatic (provisioned per version)", "Custom path"];

    /// <summary>Selected java source index.</summary>
    public int JavaSourceIndex
    {
        get => Settings.JavaSource == JavaSource.Custom ? 1 : 0;
        set
        {
            Settings.JavaSource = value == 1 ? JavaSource.Custom : JavaSource.Auto;
            Commit();
        }
    }

    /// <summary>The custom java path.</summary>
    public string CustomJavaPath
    {
        get => Settings.CustomJavaPath;
        set
        {
            Settings.CustomJavaPath = value ?? string.Empty;
            Commit();
        }
    }

    /// <summary>The result of the last java probe.</summary>
    public string ProbeResult
    {
        get => _probeResult;
        private set => Set(ref _probeResult, value);
    }

    /// <summary>Status line above the flags list.</summary>
    public string StatusMessage
    {
        get => _statusMessage;
        private set => Set(ref _statusMessage, value);
    }

    /// <summary>Game window width.</summary>
    public int WindowWidth
    {
        get => Settings.WindowWidth;
        set { Settings.WindowWidth = Math.Clamp(value, 640, 7680); Commit(); }
    }

    /// <summary>Game window height.</summary>
    public int WindowHeight
    {
        get => Settings.WindowHeight;
        set { Settings.WindowHeight = Math.Clamp(value, 480, 4320); Commit(); }
    }

    /// <summary>Reduce launcher GPU/game contention while playing.</summary>
    public bool ReduceUsageWhilePlaying
    {
        get => Settings.ReduceUsageWhilePlaying;
        set { Settings.ReduceUsageWhilePlaying = value; Commit(); }
    }

    /// <summary>Close to tray instead of exiting.</summary>
    public bool CloseToTray
    {
        get => Settings.CloseToTray;
        set { Settings.CloseToTray = value; Commit(); }
    }

    /// <summary>
    /// The offline account name the game launches with.
    /// <para>
    /// This is a display name the user typed, not a credential and not an account: it names the
    /// offline instance and it picks which skin the account chip draws. It is the ONLY thing in this
    /// product that can produce a player name, and it produces one because the user gave it.
    /// </para>
    /// </summary>
    public string OfflineName
    {
        get => Settings.OfflineName;
        set
        {
            var trimmed = (value ?? string.Empty).Trim();
            if (string.Equals(Settings.OfflineName, trimmed, StringComparison.Ordinal))
            {
                return;
            }

            Settings.OfflineName = trimmed;
            _store.Account.Name = trimmed.Length > 0 ? trimmed : "Player";
            _store.SaveAccount();
            Commit();
            Raise();

            // The chip's head is a function of this string, so a change to it has to reach the
            // shell rather than waiting for the next half-hour refresh. One event, one listener.
            OfflineNameChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    /// <summary>
    /// Raised when <see cref="OfflineName"/> really changed. The account chip's head listens for
    /// it, because that string is the only thing that decides whose face the chip draws.
    /// </summary>
    public event EventHandler? OfflineNameChanged;

    /// <summary>A flag the user typed. Cleared when it is added.</summary>
    public string NewFlag
    {
        get => _newFlag;
        set
        {
            if (Set(ref _newFlag, value ?? string.Empty))
            {
                AddFlagCommand.RaiseCanExecuteChanged();
            }
        }
    }

    private string _newFlag = string.Empty;

    /// <summary>The flags the user has added by hand, which are appended verbatim.</summary>
    public ObservableCollection<string> ExtraFlags { get; } = [];

    /// <summary>True when there is a hand-added flag to show.</summary>
    public bool HasExtraFlags => ExtraFlags.Count > 0;

    private void CopyLaunchCommand()
    {
        try
        {
            System.Windows.Clipboard.SetText(LaunchCommand);
            AppLog.Shared.Info("The launch command was copied to the clipboard. Nothing was uploaded.");
        }
        catch (Exception ex)
        {
            AppLog.Shared.Warn("Could not write to the clipboard: " + ex.Message);
        }
    }

    private void AddFlag()    {
        var flag = NewFlag.Trim();
        if (flag.Length == 0 || ExtraFlags.Contains(flag, StringComparer.Ordinal))
        {
            return;
        }

        ExtraFlags.Add(flag);
        NewFlag = string.Empty;
        AppLog.Shared.Warn($"Extra JVM flag added by hand: {flag}. It is passed to the JVM exactly as typed.");
        Raise(nameof(HasExtraFlags));
    }

    private async Task ProbeJavaAsync()
    {
        var launch = new LaunchService(new InstanceScanner());
        var java = launch.ResolveJava(Settings, _store.SelectedVersion);
        if (java is null)
        {
            ProbeResult = Settings.JavaSource == JavaSource.Custom
                ? "The custom path does not point at a java.exe. Nothing was run."
                : "No provisioned Java 21 yet. The big button on Home downloads Temurin 21 from Adoptium.";
            return;
        }

        var banner = await JavaProvisioner.ProbeVersionTextAsync(java, CancellationToken.None);
        if (_disposed)
        {
            return;
        }

        ProbeResult = banner is null ? java : $"{java}  ·  {banner}";
    }

    private void ResetJvm()
    {
        Settings.Collector = GarbageCollector.G1;
        Settings.SetMaxGcPause = true;
        Settings.ParallelGcThreads = Math.Max(2, MachineInfo.LogicalProcessors / 2);
        Settings.ConcurrentGcThreads = 2;
        Settings.DisableExplicitGc = true;
        Settings.AlwaysPreTouch = true;
        Settings.UseStringDeduplication = true;
        Settings.DisableJava2dD3d = true;
        Settings.MaxHeapMb = JvmFlagBuilder.ClampHeap(3072, MachineInfo.PhysicalMemoryMb());
        _heapMb = Settings.MaxHeapMb;
        Commit();
        BuildFlagRows();
        Raise(nameof(HeapGb));
        Raise(nameof(HeapLabel));
        StatusMessage = "The recommended flag set is back in place: G1, a 50 ms pause target, pre-touched, explicit GC off.";
    }

    private void Commit()
    {
        _store.SaveSettings();
        BuildFlagRows();
    }

    /// <summary>
    /// Rebuilds the flag list from the real catalogue for the selected version, and wires the
    /// switches to the settings that actually govern them.
    ///
    /// The collector gets a segmented control rather than a switch, because it is a choice of one
    /// of three and a switch that could only ever say "G1" would be a control that looks
    /// operable and is not.
    /// </summary>
    private void BuildFlagRows()
    {
        if (_disposed)
        {
            return;
        }

        var set = JvmFlagBuilder.Build(_store.SelectedVersion, Settings);

        // Flags that already have a control elsewhere on this screen, and so must not also appear
        // in the table: the heap is the slider, the collector is the segmented control, and the
        // rest are the switches.
        var handled = new HashSet<string>(StringComparer.Ordinal)
        {
            "-Xms{heap}M -Xmx{heap}M",
            "-XX:+UseG1GC",
            "-XX:+UseZGC",
            "-XX:+UseShenandoahGC",
            "-XX:MaxGCPauseMillis=50",
            "-XX:+DisableExplicitGC",
            "-XX:+AlwaysPreTouch",
            "-XX:+UseStringDeduplication",
            "-Dsun.java2d.d3d=false",
        };

        FlagRows.ReplaceAll(set.Considered
            .Where(f => !handled.Contains(f.Flag))
            .Select(f => new FlagRow(f)));

        // Built first, published second, so a switch is never clicked against a list that is
        // being replaced underneath it.
        var toggles = new List<JvmFlagToggle>
        {
            new("-XX:MaxGCPauseMillis=50",
                "Asks the collector to keep pauses under 50 ms, which is one game tick.",
                () => Settings.SetMaxGcPause,
                v => Settings.SetMaxGcPause = v),

            new("-XX:+DisableExplicitGC",
                "Ignores anything in the process that forces a cleanup mid-game.",
                () => Settings.DisableExplicitGc,
                v => Settings.DisableExplicitGc = v),

            new("-XX:+AlwaysPreTouch",
                "Claims all memory at start. Slower launch, steadier frames.",
                () => Settings.AlwaysPreTouch,
                v => Settings.AlwaysPreTouch = v),

            new("-XX:+UseStringDeduplication",
                "Hands duplicate-string elimination to the collector's concurrent thread.",
                () => Settings.UseStringDeduplication,
                v => Settings.UseStringDeduplication = v),

            new("-Dsun.java2d.d3d=false",
                "Drops the Direct3D Java2D pipeline from the process.",
                () => Settings.DisableJava2dD3d,
                v => Settings.DisableJava2dD3d = v),
        };

        FlagToggles.Clear();
        foreach (var toggle in toggles)
        {
            FlagToggles.Add(toggle);
        }

        Raise(nameof(FlagToggles));
        Raise(nameof(FlagRows));
    }

    /// <summary>
    /// Releases the log pane. The pane holds a subscription to a process-wide event, so this is
    /// not tidiness - without it a rebuilt shell leaves the previous shell's pane subscribed and
    /// still appending rows to a collection no window is showing.
    /// </summary>
    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        Diagnostics.Dispose();
    }

    private static void OpenRuntimes()
    {
        Directory.CreateDirectory(AppPaths.Runtimes);
        Process.Start(new ProcessStartInfo("explorer.exe", AppPaths.Runtimes) { UseShellExecute = true })?.Dispose();
    }

    private void OpenInstance()
    {
        try
        {
            if (Directory.Exists(_install.InstanceRoot))
            {
                Process.Start(new ProcessStartInfo("explorer.exe", _install.InstanceRoot) { UseShellExecute = true })?.Dispose();
                return;
            }

            AppLog.Shared.Info("Nothing is installed yet, so there is no instance folder to open.");
        }
        catch (Exception ex)
        {
            AppLog.Shared.Warn("Could not open the instance folder: " + ex.Message);
        }
    }
}
