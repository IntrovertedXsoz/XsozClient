using System.Windows;
using System.Windows.Input;
using Xsoz.Launcher.Controls;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Models;
using Xsoz.Launcher.Services;

namespace Xsoz.Launcher.ViewModels;

/// <summary>One labelled tick in the install ladder.</summary>
public sealed class InstallStepTick
{
    /// <summary>Creates a tick.</summary>
    public InstallStepTick(string label, bool isDone, bool isCurrent)
    {
        Label = label;
        IsDone = isDone;
        IsCurrent = isCurrent;
    }

    /// <summary>The step's name.</summary>
    public string Label { get; }

    /// <summary>True once the step has finished.</summary>
    public bool IsDone { get; }

    /// <summary>True on the step that is running now.</summary>
    public bool IsCurrent { get; }
}

/// <summary>
/// The Home screen.
///
/// TWO PATHS, IN THIS ORDER, AND THE ORDER IS THE POINT.
///
/// 1. THE OFFICIAL LAUNCHER (primary). Provision Fabric and the mod layer into the user's own
///    <c>%APPDATA%\.minecraft</c>, register one <c>custom</c> profile, and open the official
///    launcher. It signs the user in and it launches the game. This launcher reads no credentials,
///    holds no token, and makes no authentication call of any kind.
///
/// 2. THIS LAUNCHER'S OWN INSTANCE (fallback). Provision a private Fabric instance under
///    <c>%LOCALAPPDATA%\XsozClient</c> and start the JVM directly, offline. Kept because it is
///    proven on this machine, because it needs nobody else's GUI to be openable, and because a
///    product whose only route to the game is a hand-off is one launcher update away from having
///    none.
///
/// ISOLATION. The mod layer lands in
/// <c>%LOCALAPPDATA%\XsozClient\instances\xsoz-1.21.11\.minecraft</c>, never in the user's
/// <c>mods\</c>. Their saves, mods and options are never read for writing and never written to.
///
/// Disposal matters here because both of this screen's subscriptions outlive it: the game process
/// stays alive after the launcher window closes, and the install keeps ticking while the user closes
/// the window mid-download. Neither callback may touch a screen that is no longer there.
/// </summary>
public sealed class HomeViewModel : ObservableObject, IDisposable
{
    private readonly ProfileService _store;
    private readonly InstallService _install;
    private readonly LaunchService _launch;
    private readonly VanillaProvisioner _vanilla;
    private LaunchPlan? _plan;
    private CancellationTokenSource? _installCts;
    private bool _isBusy;
    private string _installTitle = string.Empty;
    private string _installLogLine = string.Empty;
    private string _installFile = string.Empty;
    private double _installFraction;
    private string _installEta = string.Empty;
    private string _installBytes = string.Empty;
    private bool _disposed;
    private string _handOffLine = string.Empty;
    private string _provisionLine = string.Empty;
    private bool _launcherOpen;

    /// <summary>Creates the screen.</summary>
    public HomeViewModel(ProfileService store, InstallService install, LaunchService launch, VanillaProvisioner vanilla)
    {
        _store = store;
        _install = install;
        _launch = launch;
        _vanilla = vanilla;

        PlayOrInstallCommand = RelayCommand.Create(() => _ = PlayOrInstallAsync(), () => !IsBusy || IsInstalling);
        CancelInstallCommand = RelayCommand.Create(() => _installCts?.Cancel(), () => IsInstalling);
        AcceptDisclaimerCommand = RelayCommand.Create(() => DisclaimerAccepted = true);
        OpenInstanceFolderCommand = RelayCommand.Create(OpenInstanceFolder, () => IsInstalled);

        // The three new commands. Provision writes into the official launcher's directory, so it is
        // gated; the re-check re-asks that gate; the hand-off opens the launcher.
        ProvisionForVanillaCommand = RelayCommand.Create(() => _ = ProvisionAsync(), () => !IsBusy && !IsLaunchingVanilla);
        ReCheckLauncherCommand = RelayCommand.Create(ReCheckLauncherState, () => !IsBusy);
        OpenVanillaLauncherCommand = RelayCommand.Create(OpenVanillaLauncher, () => !IsBusy);

        // Held in a field rather than passed as a lambda so Dispose can take it off again. The
        // process exits long after this window does, and the event on it belongs to a service the
        // launcher does not own.
        _launch.GameExited += OnGameExited;

        Steps.ReplaceAll(InstallSteps);
        ReCheckLauncherState();
        Refresh();
    }

    /// <summary>The one call to action on the FALLBACK path.</summary>
    public RelayCommand PlayOrInstallCommand { get; }

    /// <summary>Cancels a running install cleanly.</summary>
    public RelayCommand CancelInstallCommand { get; }

    /// <summary>One-click EULA acceptance.</summary>
    public RelayCommand AcceptDisclaimerCommand { get; }

    /// <summary>Opens the managed instance in Explorer. Read-only navigation.</summary>
    public RelayCommand OpenInstanceFolderCommand { get; }

    private void OnGameExited(object? sender, int exitCode) =>
        Application.Current?.Dispatcher.BeginInvoke(() => Refresh());

    /// <summary>
    /// Provisions Fabric and our mods into the official launcher, then reports in one line what
    /// happened. Writes nothing at all while that launcher is open - see
    /// <see cref="LauncherProcessWatch"/> for why, and for how the refusal is tested.
    /// </summary>
    public RelayCommand ProvisionForVanillaCommand { get; }

    /// <summary>Re-asks whether the official launcher is running. The re-check the refusal offers.</summary>
    public RelayCommand ReCheckLauncherCommand { get; }

    /// <summary>Opens the official Minecraft launcher. The hand-off.</summary>
    public RelayCommand OpenVanillaLauncherCommand { get; }

    /// <summary>The selected game version descriptor (there is exactly one).</summary>
    public GameVersionDescriptor Version => _store.SelectedVersion;

    /// <summary>The active profile.</summary>
    public Profile Profile => _store.Active;

    /// <summary>
    /// The name the game is launched with on the FALLBACK path. The fallback runs offline, so this
    /// is a display name and nothing else - it is not an account, it is not authenticated, and it
    /// grants nothing. It is not shown on the primary path at all: the official launcher displays
    /// the player's name itself.
    /// </summary>
    public string LaunchPlayerName =>
        string.IsNullOrWhiteSpace(_store.Settings.OfflineName) ? "Player" : _store.Settings.OfflineName;

    /// <summary>
    /// True once every install marker verifies on disk on the FALLBACK path.
    /// </summary>
    public bool IsInstalled => _install.IsInstalled();

    /// <summary>
    /// True once anything at all has been written into the managed instance. Distinguishes "you
    /// have never run the installer" from "the installer ran and something is missing": the first
    /// gets the welcome strip, the second gets one specific line about what is missing.
    /// </summary>
    public bool HasInstance => Directory.Exists(_install.GameRoot);

    /// <summary>Whether the welcome strip is due: nothing installed, nothing running.</summary>
    public bool ShowWelcome => !HasInstance && !IsInstalling;

    /// <summary>Whether an install or a launch is currently owned by this screen.</summary>
    public bool IsBusy
    {
        get => _isBusy;
        private set
        {
            if (Set(ref _isBusy, value))
            {
                Raise(nameof(ShowWelcome));
                Raise(nameof(PlayLabel));
                Raise(nameof(PlayEnabled));
                Raise(nameof(ShowInstallOverlay));
                PlayOrInstallCommand.RaiseCanExecuteChanged();
                CancelInstallCommand.RaiseCanExecuteChanged();
                OpenInstanceFolderCommand.RaiseCanExecuteChanged();
            }
        }
    }

    /// <summary>True while the installer is running (the overlay shows).</summary>
    public bool IsInstalling
    {
        get => _isInstalling;
        private set
        {
            if (Set(ref _isInstalling, value))
            {
                Raise(nameof(ShowWelcome));
                Raise(nameof(PlayLabel));
                Raise(nameof(PlaySubLabel));
                Raise(nameof(PlayEnabled));
                Raise(nameof(ShowInstallOverlay));
            }
        }
    }

    private bool _isInstalling;

    /// <summary>The install overlay shows while a run is active.</summary>
    public bool ShowInstallOverlay => IsBusy;

    /// <summary>The play button is enabled when the plan allows, or when it is the installer's turn.</summary>
    public bool PlayEnabled => IsInstalling || _plan?.CanLaunch == true || !IsInstalled;

    /// <summary>
    /// The label for the fallback CTA. It says what it is: this launcher's own instance, which it
    /// provisions and starts itself, and which runs offline. A button labelled PLAY that silently
    /// means "a different launch path with a different account model" is how a launcher loses a
    /// user's trust over an afternoon.
    /// </summary>
    public string PlayLabel =>
        IsInstalling ? "CANCEL"
        : _launch.Running is not null ? "RUNNING"
        : _plan?.CanLaunch == true ? "PLAY SELF-MANAGED"
        : "PROVISION FALLBACK";

    /// <summary>
    /// What the CTA does right now, in one line. Deliberately short: when there is a blocker the
    /// card under the button already carries the specific sentence, and repeating it here would
    /// print the same line twice.
    /// </summary>
    public string PlaySubLabel =>
        IsInstalling ? "An install is running - this button stops it cleanly"
        : _launch.Running is not null ? "Minecraft is running"
        : _plan?.CanLaunch == true ? $"{Version.Label} · {Profile.Name} · offline, no account"
        : HasBlocker ? "Downloads only what is missing"
        : "Downloads Java 21, the game, Fabric and the mod layer";

    /// <summary>
    /// True when the plan is blocked by a real problem worth saying out loud. Suppressed while the
    /// welcome strip is up: on a machine with nothing installed the strip already explains what the
    /// button does, and a second paragraph saying the same thing is noise.
    /// </summary>
    public bool HasBlocker =>
        !IsInstalling && !ShowWelcome && _plan is { Blocker: not LaunchBlocker.None, CanLaunch: false };

    /// <summary>The blocker detail, or empty.</summary>
    public string BlockerDetail => _plan?.BlockerDetail ?? string.Empty;

    /// <summary>The disclaimer acceptance state.</summary>
    public bool DisclaimerAccepted
    {
        get => _store.Settings.AcceptedDisclaimer;
        set
        {
            if (_store.Settings.AcceptedDisclaimer == value)
            {
                return;
            }

            _store.Settings.AcceptedDisclaimer = value;
            _store.SaveSettings();
            Raise();
            Raise(nameof(ShowDisclaimerGate));
            AppLog.Shared.Info(value ? "MUG disclaimer accepted." : "MUG disclaimer acceptance withdrawn.");
            Refresh();
        }
    }

    /// <summary>The acceptance card shows until accepted.</summary>
    public bool ShowDisclaimerGate => !_store.Settings.AcceptedDisclaimer;

    /// <summary>Install stage title for the overlay.</summary>
    public string InstallTitle
    {
        get => _installTitle;
        private set => Set(ref _installTitle, value);
    }

    /// <summary>The rolling log line under the bar.</summary>
    public string InstallLogLine
    {
        get => _installLogLine;
        private set => Set(ref _installLogLine, value);
    }

    /// <summary>The file currently being written.</summary>
    public string InstallFile
    {
        get => _installFile;
        private set => Set(ref _installFile, value);
    }

    /// <summary>Overall progress, 0..1.</summary>
    public double InstallFraction
    {
        get => _installFraction;
        private set
        {
            if (Set(ref _installFraction, value))
            {
                Raise(nameof(InstallPercentText));
            }
        }
    }

    /// <summary>The bar's readout, e.g. "42%".</summary>
    public string InstallPercentText => ((int)Math.Round(InstallFraction * 100)) + "%";

    /// <summary>"2m 10s left" or the fallback.</summary>
    public string InstallEta
    {
        get => _installEta;
        private set => Set(ref _installEta, value);
    }

    /// <summary>"312 MB of 640 MB".</summary>
    public string InstallBytes
    {
        get => _installBytes;
        private set => Set(ref _installBytes, value);
    }

    /// <summary>
    /// The step name in the install block's top row. It is whatever
    /// <see cref="InstallTick.StageTitle"/> said, and nothing else: when the install service does
    /// not know a finer name, the row says what it does know rather than a plausible-sounding
    /// invention.
    /// </summary>
    public string InstallStepName
    {
        get => _installStepName;
        private set
        {
            if (Set(ref _installStepName, value))
            {
                Raise(nameof(HasInstallStepName));
            }
        }
    }

    private string _installStepName = string.Empty;

    /// <summary>True when there is a step name to show.</summary>
    public bool HasInstallStepName => InstallStepName.Length > 0;

    /// <summary>
    /// The six labelled ticks under the block: Java, Game, Fabric, Mods, Settings, Finish.
    ///
    /// These are derived from the real <see cref="InstallStage"/> the install service reports, not
    /// from a counter. The mapping is one-to-one and in order, so "step 2 of 6" is a fact about
    /// which stage is running rather than a percentage divided by six.
    /// </summary>
    public IReadOnlyList<InstallStepTick> InstallSteps { get; } =
    [
        new("Java", false, false),
        new("Game", false, false),
        new("Fabric", false, false),
        new("Mods", false, false),
        new("Settings", false, false),
        new("Finish", false, false),
    ];

    private InstallStage _installStage = InstallStage.NotStarted;

    /// <summary>
    /// Repaints the six ticks from the stage the install service last reported. Called from the
    /// tick handler, on the dispatcher, with the value it was given.
    /// </summary>
    private void ApplyInstallStage(InstallStage stage)
    {
        if (_installStage == stage)
        {
            return;
        }

        _installStage = stage;

        // The install's own enum has five working stages between NotStarted and Done. The design
        // shows six ticks, so the sixth is Finish, which is what Done means to a player.
        var order = stage switch
        {
            InstallStage.NotStarted => -1,
            InstallStage.Java => 0,
            InstallStage.GameFiles => 1,
            InstallStage.FabricLoader => 2,
            InstallStage.ModStack => 3,
            InstallStage.Config => 4,
            _ => 5,
        };

        var steps = new InstallStepTick[InstallSteps.Count];
        for (var i = 0; i < steps.Length; i++)
        {
            steps[i] = new InstallStepTick(
                InstallSteps[i].Label,
                order > i,
                order == i);
        }

        Steps.ReplaceAll(steps);
        Raise(nameof(InstallSteps));
        Raise(nameof(InstallStepNumber));
    }

    /// <summary>The live ticks. Bound to the block; a Reset, never twenty notifications.</summary>
    public ResetableCollection<InstallStepTick> Steps { get; } = [];

    /// <summary>"Step 2 of 6", or empty before anything has started.</summary>
    public string InstallStepNumber =>
        _installStage == InstallStage.NotStarted ? string.Empty : $"Step {InstallStepIndex + 1} of {InstallSteps.Count}";

    private int InstallStepIndex => _installStage switch
    {
        InstallStage.NotStarted => 0,
        InstallStage.Java => 0,
        InstallStage.GameFiles => 1,
        InstallStage.FabricLoader => 2,
        InstallStage.ModStack => 3,
        InstallStage.Config => 4,
        _ => 5,
    };

    /// <summary>
    /// The one line of facts under the block, and the Home status line when nothing is running.
    /// Its parts are separate so the separators can be drawn quieter than the content.
    /// </summary>
    public IReadOnlyList<DottedSegment> StatusSegments
    {
        get
        {
            if (IsInstalling)
            {
                var parts = new List<DottedSegment>();
                if (InstallStepNumber.Length > 0)
                {
                    parts.Add(new DottedSegment(InstallStepNumber, false));
                }

                if (InstallBytes.Length > 0)
                {
                    parts.Add(new DottedSegment(InstallBytes, false));
                }

                if (InstallEta.Length > 0)
                {
                    parts.Add(new DottedSegment(InstallEta, false));
                }

                if (parts.Count == 0)
                {
                    parts.Add(new DottedSegment(InstallStepName.Length > 0 ? InstallStepName : "Starting", false));
                }

                return parts;
            }

            if (HasProvisionLine)
            {
                return [new DottedSegment(ProvisionLine, false)];
            }

            if (HasProblem)
            {
                return [new DottedSegment(ProblemText, false)];
            }

            return
            [
                new DottedSegment(Version.Label, false),
                new DottedSegment(Version.LoaderName, false),
                new DottedSegment(AccountOwnership.ChipNameLong, false),
                new DottedSegment(IsInstalled ? "fallback ready" : "fallback not installed", true),
            ];
        }
    }

    /// <summary>True when the status line is carrying a problem rather than a state.</summary>
    public bool HasProblem => _problemText.Length > 0;

    /// <summary>One plain sentence about what went wrong, and nothing else.</summary>
    public string ProblemText
    {
        get => _problemText;
        private set
        {
            if (Set(ref _problemText, value))
            {
                Raise(nameof(HasProblem));
                Raise(nameof(StatusSegments));
            }
        }
    }

    private string _problemText = string.Empty;

    /// <summary>The account name the status line shows. The shell owns sign-in; this only reads it.</summary>
    public string AccountName { get; set; } = AccountOwnership.ChipNameLong;

    /// <summary>
    /// Called by the shell when the account changes, so the status line and the rail agree.
    /// </summary>
    public void SetAccountName(string name)
    {
        if (AccountName == name)
        {
            return;
        }

        AccountName = name;
        Raise(nameof(StatusSegments));
    }

    /// <summary>
    /// Shows a failed install as a status line rather than as a dialog, and gives the big button
    /// back so it is also the retry. The install is idempotent, so retrying downloads only what
    /// is actually missing.
    /// </summary>
    public void ShowInstallProblem(string sentence)
    {
        ProblemText = sentence;
        Raise(nameof(StatusSegments));
    }

    /// <summary>Clears a previous problem. Called when an install starts or a launch succeeds.</summary>
    public void ClearInstallProblem()
    {
        if (_problemText.Length == 0)
        {
            return;
        }

        ProblemText = string.Empty;
    }

    /// <summary>The loader line on the version card: "Fabric 0.19.5".</summary>
    public string LoaderText => $"{Version.LoaderName} {Version.LoaderVersion}";

    /// <summary>The Java line on the version card.</summary>
    public string JavaText => Version.JavaMajor.ToString(System.Globalization.CultureInfo.InvariantCulture);

    /// <summary>
    /// Called by the shell when the hand-off button is used, so Home says what happened in the
    /// place the user is already looking.
    /// </summary>
    public void SetHandOffMessage(string message)
    {
        HandOffLine = message;
        Raise(nameof(StatusSegments));
        Raise(nameof(HasHandOffLine));
    }

    /// <summary>
    /// True when the official Minecraft launcher is running. Read at construction and re-read by
    /// <see cref="ReCheckLauncherState"/>; never cached across a provisioning run, because the
    /// user can open it at any moment and the gate is re-consulted immediately before every write
    /// regardless.
    /// </summary>
    public bool IsLauncherOpen
    {
        get => _launcherOpen;
        private set
        {
            if (Set(ref _launcherOpen, value))
            {
                Raise(nameof(ShowReCheck));
                Raise(nameof(LauncherStateLine));
                ReCheckLauncherCommand.RaiseCanExecuteChanged();
                ProvisionForVanillaCommand.RaiseCanExecuteChanged();
            }
        }
    }

    /// <summary>True while a provisioning run for the official launcher is in flight.</summary>
    public bool IsLaunchingVanilla
    {
        get => _isLaunchingVanilla;
        private set
        {
            if (Set(ref _isLaunchingVanilla, value))
            {
                Raise(nameof(ProvisionLabel));
                ProvisionForVanillaCommand.RaiseCanExecuteChanged();
                ReCheckLauncherCommand.RaiseCanExecuteChanged();
                OpenVanillaLauncherCommand.RaiseCanExecuteChanged();
            }
        }
    }

    private bool _isLaunchingVanilla;

    /// <summary>The primary button's label on the official-launcher path.</summary>
    public string ProvisionLabel => IsLaunchingVanilla ? "PROVISIONING…" : "INSTALL FOR MINECRAFT LAUNCHER";

    /// <summary>
    /// True when the re-check is worth showing: the launcher is open, so the last thing that
    /// happened was a refusal, and the useful next action is a re-check rather than another click.
    /// </summary>
    public bool ShowReCheck => IsLauncherOpen;

    /// <summary>
    /// The state of the official launcher, in one line. The refusal case names the process count,
    /// because "the launcher is open" with nothing behind it invites the user to go looking.
    /// </summary>
    public string LauncherStateLine
    {
        get
        {
            var minecraft = _vanilla.Minecraft;
            var presence = minecraft.Exists
                ? "Minecraft folder: " + minecraft.Root
                : "No Minecraft folder yet";

            if (IsLauncherOpen)
            {
                var hits = LauncherProcessWatch.Find(minecraft.Root);
                return presence + "  ·  " + LauncherProcessWatch.Describe(minecraft.Root, hits);
            }

            return presence + "  ·  Minecraft Launcher: " + LauncherHandOff.VariantLabel
                   + "  ·  " + VanillaProvisioner.VersionId;
        }
    }

    /// <summary>The result of the last provisioning run, or the refusal sentence.</summary>
    public string ProvisionLine
    {
        get => _provisionLine;
        private set
        {
            if (Set(ref _provisionLine, value))
            {
                Raise(nameof(HasProvisionLine));
                Raise(nameof(StatusSegments));
            }
        }
    }

    /// <summary>True when there is a provisioning result to show.</summary>
    public bool HasProvisionLine => ProvisionLine.Length > 0;

    /// <summary>The result of the last hand-off, as one line.</summary>
    public string HandOffLine
    {
        get => _handOffLine;
        private set => Set(ref _handOffLine, value);
    }

    /// <summary>True when a hand-off has been attempted.</summary>
    public bool HasHandOffLine => HandOffLine.Length > 0;

    /// <summary>
    /// The honest one-liner about the hand-off. The Microsoft Store launcher has no documented
    /// command line for targeting a chosen installation, so this launcher says so instead of
    /// inventing an argument that might silently do nothing.
    /// </summary>
    public string NoCliNotice => LauncherHandOff.NoCliNotice;

    /// <summary>The isolated game directory our mods go into, for display.</summary>
    public string IsolatedDirText => VanillaProvisioner.IsolatedGameDir;

    /// <summary>Re-asks the gate. Safe to press at any time; it reads processes and nothing else.</summary>
    public void ReCheckLauncherState()
    {
        var minecraft = _vanilla.Minecraft;
        var open = LauncherProcessWatch.IsOpen(minecraft.Root);
        var was = IsLauncherOpen;
        IsLauncherOpen = open;

        if (open && !was)
        {
            ProvisionLine = LauncherProcessWatch.RefusalMessage;
        }
        else if (!open && was)
        {
            ProvisionLine = "The Minecraft Launcher is closed. INSTALL FOR MINECRAFT LAUNCHER can proceed.";
        }

        Raise(nameof(LauncherStateLine));
    }

    /// <summary>
    /// Runs the provisioning against the official launcher. Every write inside it is gated, and it
    /// reports one line either way. Nothing here can close the user's launcher.
    /// </summary>
    private async Task ProvisionAsync()
    {
        if (_disposed || IsBusy || IsLaunchingVanilla)
        {
            return;
        }

        if (_vanilla.Minecraft is { Exists: false })
        {
            ProvisionLine = _vanilla.Minecraft.MissingDirectorySentence;
            return;
        }

        IsLaunchingVanilla = true;
        try
        {
            var result = await _vanilla.RunAsync(CancellationToken.None).ConfigureAwait(true);
            if (_disposed)
            {
                return;
            }

            ProvisionLine = result.Message;
            Refresh();
        }
        catch (OperationCanceledException)
        {
            ProvisionLine = "Provisioning was cancelled. Nothing was left half-written.";
        }
        catch (Exception ex)
        {
            ProvisionLine = "Provisioning stopped: " + ex.Message;
            AppLog.Shared.Error("Vanilla provisioning failed: " + ex.Message);
        }
        finally
        {
            if (!_disposed)
            {
                IsLaunchingVanilla = false;
                ReCheckLauncherState();
            }
        }
    }

    /// <summary>
    /// Opens the official launcher. One line back, never a dialog.
    /// </summary>
    private void OpenVanillaLauncher()
    {
        if (_disposed)
        {
            return;
        }

        var message = LauncherHandOff.Open();
        HandOffLine = message + " " + LauncherHandOff.NoCliNotice;
        Raise(nameof(HasHandOffLine));
        Raise(nameof(StatusSegments));
    }

    /// <summary>Re-reads the plan and every derived property. Called on activation and after any state change.</summary>
    public void Refresh()
    {
        if (_disposed)
        {
            return;
        }

        _plan = _launch.Plan(Version, _store.Settings, Profile, LaunchPlayerName);

        Raise(nameof(IsInstalled));
        Raise(nameof(HasInstance));
        Raise(nameof(ShowWelcome));
        Raise(nameof(PlayEnabled));
        Raise(nameof(PlayLabel));
        Raise(nameof(PlaySubLabel));
        Raise(nameof(HasBlocker));
        Raise(nameof(BlockerDetail));
        Raise(nameof(StatusSegments));
        Raise(nameof(HasProblem));
        Raise(nameof(LoaderText));
        Raise(nameof(JavaText));
        Raise(nameof(ShowDisclaimerGate));
        OpenInstanceFolderCommand.RaiseCanExecuteChanged();
        PlayOrInstallCommand.RaiseCanExecuteChanged();
    }

    private async Task PlayOrInstallAsync()
    {
        if (IsInstalling)
        {
            return;
        }

        if (IsInstalled && _plan is { CanLaunch: true })
        {
            AppLog.Shared.Info($"Launch requested: {Version.Label}, profile '{Profile.Name}'.");
            _plan = _launch.Start(_plan);
            Refresh();
            return;
        }

        await RunInstallAsync();
    }

private async Task RunInstallAsync()
    {
        if (_disposed || _isBusy)
        {
            return;
        }

        IsBusy = true;
        IsInstalling = true;
        _installCts = new CancellationTokenSource();
        InstallTitle = "Setting up";
        InstallLogLine = "Starting";
        InstallFraction = 0;
        InstallEta = string.Empty;
        InstallBytes = string.Empty;
        InstallStepName = string.Empty;
        ClearInstallProblem();
        ApplyInstallStage(InstallStage.NotStarted);

        void OnTick(object? sender, InstallTick tick)
        {
            // Queued, not invoked synchronously. The tick is raised from the download threads, and a
            // blocking Dispatcher.Invoke makes every one of them wait on the UI thread - which is
            // exactly the wrong thing to be waiting on while the window is closing. A tick that misses
            // the boat is a slightly stale progress bar; the download must not stop for it.
            Application.Current?.Dispatcher.BeginInvoke(() =>
            {
                if (_disposed)
                {
                    return;
                }

                InstallTitle = tick.StageTitle;
                InstallLogLine = tick.LogLine;
                InstallFile = tick.CurrentFile;
                InstallFraction = tick.OverallFraction;

                // The step name is the install service's own, not a phrase invented here. If the
                // service knows something more specific than its stage title, the current file is
                // that; otherwise the stage title is. Nothing is fabricated to fill the row.
                InstallStepName = tick.CurrentFile.Length > 0 && tick.StageTitle.Length > 0
                    ? $"{tick.StageTitle} · {tick.CurrentFile}"
                    : tick.StageTitle;

                ApplyInstallStage(tick.Stage);
                Raise(nameof(StatusSegments));

                InstallBytes = tick.TotalBytes > 0
                    ? $"{tick.ReceivedBytes / 1048576.0:0.#} MB of {tick.TotalBytes / 1048576.0:0.#} MB"
                    : $"{tick.ReceivedBytes / 1048576.0:0.#} MB";
                InstallEta = tick.EtaSeconds is { } eta && eta > 1
                    ? eta >= 90 ? $"about {eta / 60.0:0} min left" : $"about {eta:0} sec left"
                    : string.Empty;
            });
        }

        _install.Tick += OnTick;
        try
        {
            var result = await _install.RunAsync(_installCts.Token);
            if (_disposed)
            {
                return;
            }

            if (result.Cancelled)
            {
                InstallLogLine = result.Summary;
            }
            else if (!result.Succeeded)
            {
                InstallLogLine = result.Summary;
                AppLog.Shared.Error(result.Summary);

                // A failed install is a status line, not a dialog. One plain sentence, the big
                // button comes back and is also the retry, and the install is idempotent so a
                // retry downloads only what is actually missing.
                ShowInstallProblem(
                    $"The install stopped at {InstallPercentText}: {result.ErrorMessage} "
                    + "It will pick up where it left off.");
            }
            else
            {
                AppLog.Shared.Success(result.Summary);
                ClearInstallProblem();

                // Install finished; straight into the game. This is the promise on the button.
                Refresh();
                if (_plan is { CanLaunch: true })
                {
                    _plan = _launch.Start(_plan);
                }
            }
        }
        finally
        {
            _install.Tick -= OnTick;
            _installCts.Dispose();
            _installCts = null;

            // Behind the disposal check, not in front of it: the properties above are bound to the
            // block, and raising them for a screen that has been torn down is the NullReference
            // storm that a closing window used to produce.
            if (!_disposed)
            {
                IsInstalling = false;
                IsBusy = false;
                Refresh();
            }
        }
    }

    /// <summary>
    /// Detaches from the game process and stops answering the install.
    ///
    /// Cancellation rather than just unsubscribing: the install is real work against the disk, and
    /// the right response to the window closing is to stop it cleanly, not to leave a download
    /// running behind a launcher that is no longer showing anything.
    /// </summary>
    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _launch.GameExited -= OnGameExited;

        try
        {
            _installCts?.Cancel();
        }
        catch (ObjectDisposedException)
        {
            // The install already finished and released its source.
        }
    }

    private void OpenInstanceFolder()
    {
        try
        {
            if (Directory.Exists(_install.InstanceRoot))
            {
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("explorer.exe", _install.InstanceRoot) { UseShellExecute = true });
            }
        }
        catch (Exception ex)
        {
            AppLog.Shared.Warn("Could not open the instance folder: " + ex.Message);
        }
    }
}
