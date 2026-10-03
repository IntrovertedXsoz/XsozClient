using System.Collections.ObjectModel;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Models;
using Xsoz.Launcher.Services;
using Xsoz.Launcher.Themes;

namespace Xsoz.Launcher.ViewModels;

/// <summary>The shell's four navigation destinations. There is no fifth.</summary>
public enum Screen
{
    /// <summary>The version card, the one Play/Install button, the profile strip, the status line.</summary>
    Home,

    /// <summary>The module list: search, flat rows, tiers, toggles.</summary>
    Mods,

    /// <summary>JVM, Java, launch and window options; the launcher's own log lives here as a card.</summary>
    Settings,

    /// <summary>What this is and who built what it is made of.</summary>
    About,
}

/// <summary>
/// The shell view model. Owns the four screens, the selected navigation item, the account chip
/// and the global keyboard shortcuts.
/// </summary>
public sealed class MainViewModel : ObservableObject, IDisposable
{
    /// <summary>
    /// The shell's account chip, restated in the shell's own words so no screen has to know where
    /// the text lives.
    /// </summary>
    public const string ChipName = AccountOwnership.ChipName;

    /// <summary>The chip's second line. Nothing here is signed in by this launcher.</summary>
    public const string ChipSubtext = AccountOwnership.ChipSubtext;

    /// <summary>
    /// The chip's button. It opens the official Minecraft launcher, because that is the thing that
    /// actually owns the account - there is no Log In button any more, and pretending there is one
    /// would be a button that cannot do what it says.
    /// </summary>
    public const string ChipAction = AccountOwnership.ChipAction;

    private readonly ProfileService _store;
    private readonly LaunchService _launch;
    private Screen _current = Screen.Home;
    private string _statusLine = string.Empty;
    private string _accountMessage = AccountOwnership.Notice;
    private bool _disposed;
    private string _uiErrorMessage = string.Empty;
    private ImageSource? _accountHead;
    private DispatcherTimer? _headTimer;
    private CancellationTokenSource? _headCts;
    private DateTime _lastHeadAttemptUtc = DateTime.MinValue;
    private int _headInFlight;
    private bool _headDeferredWhilePlaying;

    /// <summary>Creates the shell and its four screens.</summary>
    public MainViewModel()
    {
        // Before anything is built. The rule this checks is a product rule about what ships
        // switched on, and a violation of it belongs in the log from the first line rather than
        // appearing later on a screen somebody may not have open.
        ModulePolicy.AssertAtStartup();

        _store = new ProfileService();
        _store.ApplyRegistryDefaults();

        var scanner = new InstanceScanner();
        var net = new DownloadService();
        _launch = new LaunchService(scanner);
        var install = new InstallService(net);
        // The write capability is asked for, never assumed. On an interactive launch the policy
        // hands back the real writer; in any read-only mode - which includes the headless capture,
        // because it builds this very view model - it hands back a sink that refuses every write.
        // The Home screen's INSTALL FOR MINECRAFT LAUNCHER button therefore exists and is bound in
        // a capture run, and pressing it could not write anything even if something pressed it.
        var vanilla = new VanillaProvisioner(net, GameWritePolicy.CreateWriter(net));

        AppLog.Shared.Info($"XsozClient launcher starting. {MachineInfo.PhysicalMemoryMb() / 1024} GB physical, "
                          + $"{MachineInfo.LogicalProcessors} logical processors.");

        Home = new HomeViewModel(_store, install, _launch, vanilla);
        Mods = new ModsViewModel(_store);
        Settings = new SettingsViewModel(_store, install);
        About = new AboutViewModel();
        ProfilePanel = new ProfilePanelViewModel(_store);

        // The drawer and the top bar both show the active profile, and the ramp inside the drawer
        // writes it. One event, both ends, so the chip can never disagree with the ladder.
        ProfilePanel.ActiveProfileChanged += OnActiveProfileChanged;

        // Two events that decide when the chip's head may be refreshed. The game exiting is when a
        // refresh that was deferred has to happen, and a typed offline player name is when the head
        // has to change at all.
        _launch.GameExited += OnGameExited;
        Settings.OfflineNameChanged += OnOfflineNameChanged;

        OpenProfilePanelCommand = RelayCommand.Create(OpenProfilePanel);
        CloseProfilePanelCommand = RelayCommand.Create(CloseProfilePanel);
        PlayCommand = Home.PlayOrInstallCommand;

        // The chip's action. Opening the official launcher is the whole of the account story now:
        // it is where sign-in happens and where the launch happens, and this button is honest
        // about being a hand-off rather than a sign-in.
        OpenLauncherCommand = RelayCommand.Create(OpenOfficialLauncher);

        Home.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName is nameof(HomeViewModel.PlayLabel)
                or nameof(HomeViewModel.PlayEnabled)
                or nameof(HomeViewModel.StatusSegments))
            {
                Raise(nameof(PlayLabel));
                Raise(nameof(PlayEnabled));
            }
        };

        NavigateCommand = RelayCommand.CreateWithParameter(parameter =>
        {
            if (parameter is Screen screen)
            {
                Current = screen;
            }
            else if (parameter is string name && Enum.TryParse<Screen>(name, out var parsed))
            {
                Current = parsed;
            }
        });

        AppLog.Shared.Success("Launcher ready.");
        UpdateStatus();

        // The only channel through which an unhandled dispatcher exception reaches the interface.
        // The handler in App logs every occurrence and surfaces only the first of each kind; this
        // turns that into one muted line in the footer, replacing the modal box that used to be the
        // alternative and turned a single defect into an unending queue of them.
        App.UiError += OnUiError;
    }

    private void OnUiError(object? sender, string message)
    {
        UiErrorMessage = message;
    }

    /// <summary>The home screen.</summary>
    public HomeViewModel Home { get; }

    /// <summary>The mods screen.</summary>
    public ModsViewModel Mods { get; }

    /// <summary>The settings screen.</summary>
    public SettingsViewModel Settings { get; }

    /// <summary>The about screen.</summary>
    public AboutViewModel About { get; }

    /// <summary>The profile drawer. An overlay, not a fifth navigation item.</summary>
    public ProfilePanelViewModel ProfilePanel { get; }

    /// <summary>Opens the profile drawer.</summary>
    public RelayCommand OpenProfilePanelCommand { get; }

    /// <summary>Closes the profile drawer.</summary>
    public RelayCommand CloseProfilePanelCommand { get; }

    /// <summary>
    /// The compact PLAY that sits in the top right of every non-Home screen.
    ///
    /// It is the same command as the big button, not a second launch path, so the two can never
    /// disagree about whether the game is installed. Its label is Home's label rather than a
    /// fixed "PLAY": a button that says PLAY on a machine with nothing installed would be a lie,
    /// and a lie on a green block is the most expensive kind.
    /// </summary>
    public RelayCommand PlayCommand { get; }

    /// <summary>The compact button's label.</summary>
    public string PlayLabel => Home.PlayLabel;

    /// <summary>Whether the compact button is operable.</summary>
    public bool PlayEnabled => Home.PlayEnabled;

    /// <summary>The profile chip's name.</summary>
    public string ProfileChipName => _store.Active.Name;

    /// <summary>The profile chip's "default" half, or empty.</summary>
    public string ProfileChipKind => _store.Active.IsDefault ? "default" : string.Empty;

    /// <summary>
    /// Whether the profile drawer is open. It is an overlay on top of whichever screen is
    /// showing, which is why it is a flag on the shell rather than a fifth destination: a
    /// destination would put a back button on a panel the design does not have one for.
    /// </summary>
    public bool IsProfilePanelOpen
    {
        get => _isProfilePanelOpen;
        set
        {
            if (!Set(ref _isProfilePanelOpen, value))
            {
                return;
            }

            if (value)
            {
                ProfilePanel.Refresh();
            }

            Raise(nameof(IsProfilePanelVisible));
        }
    }

    private bool _isProfilePanelOpen;

    /// <summary>True when the drawer and its scrim should be on screen.</summary>
    public bool IsProfilePanelVisible => _isProfilePanelOpen;

    private void OpenProfilePanel() => IsProfilePanelOpen = true;

    private void CloseProfilePanel() => IsProfilePanelOpen = false;

    private void OnActiveProfileChanged(object? sender, EventArgs e)
    {
        Raise(nameof(ProfileChipName));
        Raise(nameof(ProfileChipKind));
        Home.Refresh();
    }

    /// <summary>Navigates to a screen.</summary>
    public ICommand NavigateCommand { get; }

    /// <summary>The store, exposed for the shell chrome.</summary>
    public ProfileService Store => _store;

    /// <summary>Currently shown screen.</summary>
    public Screen Current
    {
        get => _current;
        set
        {
            if (!Set(ref _current, value))
            {
                return;
            }

            Raise(nameof(IsHome));
            Raise(nameof(IsMods));
            Raise(nameof(IsSettings));
            Raise(nameof(IsAbout));
            Raise(nameof(ScreenTitle));
            Raise(nameof(ScreenBlurb));
            UpdateStatus();
        }
    }

    /// <summary>Title of the current screen.</summary>
    public string ScreenTitle => Current switch
    {
        Screen.Home => "Home",
        Screen.Mods => "Mods",
        Screen.Settings => "Settings",
        Screen.About => "About",
        _ => "Home",
    };

    /// <summary>One line of context under the screen title.</summary>
    public string ScreenBlurb => Current switch
    {
        Screen.Home => "One version, one button, one profile. Nothing else.",
        Screen.Mods => "Every toggle is real, persisted, and applied when the game starts.",
        Screen.Settings => "JVM, Java, window and launch options, plus the launcher's own log.",
        Screen.About => "What this launcher is, and what it is made of.",
        _ => string.Empty,
    };

    /// <summary>True when the home screen is showing. Settable for the nav group.</summary>
    public bool IsHome
    {
        get => Current == Screen.Home;
        set { if (value) Current = Screen.Home; }
    }

    /// <summary>True when the mods screen is showing. Settable for the nav group.</summary>
    public bool IsMods
    {
        get => Current == Screen.Mods;
        set { if (value) Current = Screen.Mods; }
    }

    /// <summary>True when the settings screen is showing. Settable for the nav group.</summary>
    public bool IsSettings
    {
        get => Current == Screen.Settings;
        set { if (value) Current = Screen.Settings; }
    }

    /// <summary>True when the about screen is showing. Settable for the nav group.</summary>
    public bool IsAbout
    {
        get => Current == Screen.About;
        set { if (value) Current = Screen.About; }
    }

    /// <summary>Bottom-of-window status line.</summary>
    public string StatusLine
    {
        get => _statusLine;
        private set => Set(ref _statusLine, value);
    }

    /// <summary>Version string shown in the window chrome.</summary>
    public string VersionText { get; } =
        "v" + (typeof(MainViewModel).Assembly.GetName().Version?.ToString(3) ?? "0.1.0");

    /// <summary>
    /// The account chip's first line. A description of who owns the account, not a player name.
    /// <para>
    /// This launcher does not know the player's name, does not ask for it, and cannot read it: it
    /// holds no credentials and contacts no authentication endpoint. The official Minecraft
    /// launcher knows all of that and displays it itself. Showing a name here would mean reading it
    /// out of somebody else's account store, which is exactly the credential access this
    /// architecture removed.
    /// </para>
    /// </summary>
    public string AccountName => ChipName;

    /// <summary>The chip's second line: who does the signing in.</summary>
    public string AccountSubtext => ChipSubtext;

    /// <summary>The first letter, in the chip's 32px square. Always "M" for "Minecraft account".</summary>
    public string AccountInitial => "M";

    /// <summary>The chip's action. Opens the official launcher; it does not sign anything in.</summary>
    public string SignInLabel => ChipAction;

    // -------------------------------------------------------------------------------------------
    // The chip's skin head.
    //
    // Every rule this feature has is enforced here rather than in the service, because the service
    // has no idea what a game process is and this class does:
    //
    //   * AT MOST ONE REQUEST IN FLIGHT. An Interlocked exchange, not a bool: the timer, the
    //     game-exit hook and the name hook can all fire within a millisecond of each other and only
    //     one of them is allowed to start anything.
    //   * AT MOST ONE REQUEST PER THIRTY MINUTES. The timer itself ticks every five minutes so a
    //     refresh deferred while the game was running happens promptly afterwards; the half-hour is
    //     enforced against the last attempt, and the service enforces it again against the age of
    //     the cache file, so the rule holds across restarts too.
    //   * NOT WHILE THE GAME IS RUNNING. A launcher sharing a GPU with Minecraft is the last place
    //     to spend a network round trip, so the refresh is deferred rather than skipped and runs
    //     from the game-exit hook instead.
    //   * NOT ON THE UI THREAD. <see cref="HeadService.GetAsync"/> dispatches its own body to the
    //     thread pool; this class awaits it, so the continuation - and therefore the property
    //     assignment the view binds to - comes back to the dispatcher.
    // -------------------------------------------------------------------------------------------

    /// <summary>
    /// The chip's skin head, or null. Bound to the 32px square that already held a letter; the
    /// letter stays underneath it as the plain-avatar fallback, so a head that never arrives
    /// changes nothing about how the chip looks.
    /// </summary>
    public ImageSource? AccountHead
    {
        get => _accountHead;
        private set
        {
            if (!Set(ref _accountHead, value))
            {
                return;
            }

            // The previous image source is dropped here and nothing else holds it, which is how a
            // BitmapSource is released: it has no Dispose, and a frozen object with no references
            // is collected normally. Keeping the old one in a field "just in case" is the leak.
            Raise(nameof(HasAccountHead));
        }
    }

    /// <summary>True when the chip should draw the head rather than the letter.</summary>
    public bool HasAccountHead => _accountHead is not null;

    /// <summary>
    /// The name the head is looked up for, or null when nobody has told us one.
    /// <para>
    /// The only source is <see cref="LauncherSettings.OfflineName"/>, which the user types into
    /// Settings. It is not read from a credential store and it is never invented: the shipped
    /// placeholder "Player" means "nobody has told us a name" and is deliberately treated as none,
    /// so a stock install draws the default head rather than asking an endpoint for the skin of
    /// somebody called Player.
    /// </para>
    /// </summary>
    public string? HeadPlayerName
    {
        get
        {
            var typed = _store.Settings.OfflineName?.Trim();
            return string.IsNullOrEmpty(typed)
                   || string.Equals(typed, AccountOwnership.UnknownPlayerName, StringComparison.OrdinalIgnoreCase)
                ? null
                : typed;
        }
    }

    /// <summary>
    /// Starts the head, once the shell has a display to draw it on.
    /// <para>
    /// Called from the window rather than from the constructor on purpose. The image is rescaled to
    /// the chip's exact device-pixel size, and that size is only known once the window has been
    /// realised and <see cref="DpiMetrics"/> has read the monitor's scale factor - which in the
    /// interactive path happens before the first paint.
    /// </para>
    /// </summary>
    public void BeginAccountHead()
    {
        if (_disposed)
        {
            return;
        }

        _headTimer ??= new DispatcherTimer(
            DispatcherPriority.Background,
            System.Windows.Threading.Dispatcher.CurrentDispatcher)
        {
            Interval = HeadTimerInterval,
        };

        _headTimer.Tick -= OnHeadTimerTick;
        _headTimer.Tick += OnHeadTimerTick;
        _headTimer.Start();

        _lastHeadAttemptUtc = DateTime.MinValue;
        RequestHeadRefresh();
    }

    /// <summary>
    /// Redraws the head at a new device-pixel size after the window moved to a display with a
    /// different scale factor. The name and the cache are unchanged; only the rescale is redone.
    /// <para>
    /// This one path deliberately skips the half-hour gate above. It is not a network refresh in
    /// intent - the service decides that for itself, from the age of the cache file, and answers
    /// from disk when the head is still fresh - so the gate here would only buy a head that is
    /// pixel-exact at 150% and blurry-ish at 100% for the next half hour.
    /// </para>
    /// </summary>
    public void RefreshAccountHeadForDisplay()
    {
        _lastHeadAttemptUtc = DateTime.MinValue;
        RequestHeadRefresh();
    }

    /// <summary>
    /// The chip's tool tip on the head itself: the one thing somebody might wrongly assume about a
    /// picture of a face in the account chip. A tool tip rather than a line of copy, because the
    /// design has no room for one and this is not worth a line of screen to anyone who did not ask.
    /// </summary>
    public string AccountHeadNotice => AccountOwnership.HeadNotice;

    /// <summary>How often the timer wakes up to ask whether a refresh is due.</summary>
    private static readonly TimeSpan HeadTimerInterval = TimeSpan.FromMinutes(5);

    private void OnHeadTimerTick(object? sender, EventArgs e) => RequestHeadRefresh();

    private void OnGameExited(object? sender, int exitCode)
    {
        if (!_headDeferredWhilePlaying)
        {
            return;
        }

        _headDeferredWhilePlaying = false;
        _lastHeadAttemptUtc = DateTime.MinValue;
        RequestHeadRefresh();
    }

    private void OnOfflineNameChanged(object? sender, EventArgs e)
    {
        // A different player is a different cache file, so the half-hour gate does not apply to
        // this one: it is not a refresh, it is a different thing to draw.
        _lastHeadAttemptUtc = DateTime.MinValue;
        RequestHeadRefresh();
    }

    /// <summary>
    /// The single gate every head request goes through, whoever asked for it.
    /// </summary>
    private void RequestHeadRefresh()
    {
        if (_disposed)
        {
            return;
        }

        if (_launch.Running is not null)
        {
            _headDeferredWhilePlaying = true;
            return;
        }

        // One in flight, ever. Whoever loses this exchange simply does nothing; the request that
        // wins covers the same ground anyway.
        if (Interlocked.CompareExchange(ref _headInFlight, 1, 0) != 0)
        {
            return;
        }

        if (DateTime.UtcNow - _lastHeadAttemptUtc < HeadService.RefreshInterval)
        {
            Volatile.Write(ref _headInFlight, 0);
            return;
        }

        _ = LoadAccountHeadAsync();
    }

    /// <summary>
    /// Fetches, decodes and assigns the head. Every step of it is off the UI thread except the
    /// assignment at the end, which the await brings back here.
    /// </summary>
    private async Task LoadAccountHeadAsync()
    {
        var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        _headCts = cts;

        try
        {
            var targetPixels = (int)Math.Round(HeadSlotSize * DpiMetrics.Scale, MidpointRounding.AwayFromZero);
            var head = await HeadService.GetAsync(HeadPlayerName, targetPixels, cts.Token);

            if (_disposed || cts.IsCancellationRequested)
            {
                return;
            }

            if (head.Source == "default")
            {
                AppLog.Shared.Info(
                    "No downloaded skin head"
                    + (HeadPlayerName is { } known ? $" for '{known}'" : " - nobody has told us a player name")
                    + "; the chip shows the drawn default head and its wording is unchanged.");
            }

            // Assigned either way. The drawn default head IS the chip's resting state when no
            // endpoint could answer - it is a picture, not a claim - and the letter underneath it
            // stays as the last-resort fallback for the window between construction and the first
            // answer. On failure the property is simply not reassigned, so whatever was already on
            // screen is still on screen.
            AccountHead = head.Image;
        }
        catch (OperationCanceledException)
        {
            AppLog.Shared.Info("The skin head refresh was cancelled. The chip keeps whatever it had.");
        }
        catch (Exception ex)
        {
            // One line, no dialog, and the chip is untouched. A face that failed to download is not
            // an error the person using the launcher has done anything about.
            AppLog.Shared.Warn("The skin head refresh failed; the chip is unchanged. " + ex.Message);
        }
        finally
        {
            _lastHeadAttemptUtc = DateTime.UtcNow;
            Volatile.Write(ref _headInFlight, 0);

            _headCts = null;
            cts.Dispose();
        }
    }

    /// <summary>The chip's avatar square, in device-independent units. The design's 32.</summary>
    private const double HeadSlotSize = 32;

    /// <summary>
    /// The one line under the chip. It is always present, and it is the honest replacement for the
    /// old "unable to find login token" message - which was the wrong message for months, because
    /// this launcher never looked for a token and so could never fail to find one. The registry
    /// entry it used to carry, and the DPAPI credential access behind it, are deleted.
    /// </summary>
    public string AccountMessage
    {
        get => _accountMessage;
        private set
        {
            if (Set(ref _accountMessage, value))
            {
                Raise(nameof(HasAccountMessage));
            }
        }
    }

    /// <summary>True when the footer should show <see cref="AccountMessage"/> instead.</summary>
    public bool HasAccountMessage => AccountMessage.Length > 0;

    /// <summary>
    /// The one line the footer shows when an unhandled interface error has been reported. Empty
    /// otherwise. It is a label, not a control: there is no dialog to stack up behind it, no button
    /// to click through, and nothing that can take focus away from the launcher.
    /// </summary>
    public string UiErrorMessage
    {
        get => _uiErrorMessage;
        private set
        {
            if (Set(ref _uiErrorMessage, value))
            {
                Raise(nameof(HasUiError));
            }
        }
    }

    /// <summary>True when the footer should show <see cref="UiErrorMessage"/>.</summary>
    public bool HasUiError => UiErrorMessage.Length > 0;

    /// <summary>
    /// Opens the official Minecraft launcher. This is the hand-off, and it is also what the account
    /// chip's button does - one code path, so the chip and the Home screen's primary button can
    /// never disagree about where the game is actually launched from.
    /// </summary>
    public RelayCommand OpenLauncherCommand { get; }

    /// <summary>The MUG disclaimer, shown in the footer on every screen.</summary>
    public string Disclaimer => "Not an official Mojang or Microsoft product.";

    /// <summary>
    /// Opens the official launcher and reports what happened as one line. Never throws, never shows
    /// a dialog, never tries to focus or drive the launcher's window.
    /// </summary>
    private void OpenOfficialLauncher()
    {
        if (_disposed)
        {
            return;
        }

        var message = LauncherHandOff.Open();
        Home.SetHandOffMessage(message);
    }

    /// <summary>
    /// Tears the shell down and disposes the four screens.
    ///
    /// The screens are disposed rather than left to the collector because the log pane holds a
    /// subscription to a process-wide event and the home screen holds one to the game process. Both
    /// outlive the window, and both keep firing. There is no session probe to cancel any more -
    /// nothing in this product reaches the network for an account - and the head's timer and its
    /// in-flight request are torn down with everything else, so a closed shell cannot wake up and
    /// touch the disk.
    /// </summary>
    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        App.UiError -= OnUiError;
        ProfilePanel.ActiveProfileChanged -= OnActiveProfileChanged;
        _launch.GameExited -= OnGameExited;
        Settings.OfflineNameChanged -= OnOfflineNameChanged;

        if (_headTimer is not null)
        {
            _headTimer.Stop();
            _headTimer.Tick -= OnHeadTimerTick;
            _headTimer = null;
        }

        _headCts?.Cancel();
        _headCts = null;
        _accountHead = null;

        ProfilePanel.Dispose();
        Home.Dispose();
        Settings.Dispose();
    }

    private void UpdateStatus()
    {
        var version = _store.SelectedVersion;
        StatusLine = $"XsozClient {VersionText}    ·    {version.Label} — {version.AccentNote}    ·    {DateTime.Now:HH:mm:ss}";
    }

    /// <summary>Handles a global shortcut. Ctrl-digits and plain digits navigate.</summary>
    public bool HandleShortcut(System.Windows.Input.Key key, bool ctrl)
    {
        if (key == System.Windows.Input.Key.D1 && !ctrl) { Current = Screen.Home; return true; }
        if (key == System.Windows.Input.Key.D2 && !ctrl) { Current = Screen.Mods; return true; }
        if (key == System.Windows.Input.Key.D3 && !ctrl) { Current = Screen.Settings; return true; }
        if (key == System.Windows.Input.Key.D4 && !ctrl) { Current = Screen.About; return true; }
        return false;
    }
}
