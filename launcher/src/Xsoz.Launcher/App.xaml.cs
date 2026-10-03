using System.Globalization;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Diagnostics;
using Xsoz.Launcher.Views;

namespace Xsoz.Launcher;

/// <summary>
/// Application entry point.
///
/// Startup is wrapped end to end. A launcher that shows a crash dialog because a config file
/// was hand-edited is not shippable, so the theme load, the data directory creation and the
/// view model construction each get their own guard and each degrade to something usable.
///
/// The command line is consulted once, at the top of startup, and only for its first argument. The
/// ordinary path is the same code it has always been: no flag, <c>base.OnStartup</c>, then the
/// interactive window shown explicitly. Every non-interactive mode returns before that, which is
/// also what stops a second, windowed main window being built behind the capture.
/// </summary>
public partial class App : Application
{
    /// <summary>One unhandled interface error, raised once per distinct kind. The UI renders it inline.</summary>
    public static event EventHandler<string>? UiError;

    /// <summary>
    /// Publishes a line to the interface's single quiet channel, which is the same one an
    /// unhandled dispatcher exception uses.
    ///
    /// A method rather than a direct <c>UiError?.Invoke</c> at the call site, because an event
    /// can only be raised from inside its declaring type. This is the one place outside App that
    /// is allowed to put something in the rail, and it is a startup policy violation, which is
    /// the only thing that uses it.
    /// </summary>
    /// <param name="message">The one line to show.</param>
    public static void ReportUiLine(string message) => UiError?.Invoke(null, message);

    /// <summary>
    /// How many distinct error kinds are ever announced. Beyond this the launcher goes quiet in the
    /// interface and leaves the rest to the log; a screen that is showing a different error every
    /// frame is no more use than one showing nothing.
    /// </summary>
    private const int MaxAnnouncedErrorKinds = 20;

    private static readonly HashSet<string> AnnouncedErrorKinds = [];

    /// <summary>Creates the application object.</summary>
    public App()
    {
        // A last-resort handler so an escaped exception on the dispatcher becomes a written log
        // line rather than a modal crash box. Recovery continues with defaults where it can.
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
            AppLog.Shared.Error("Fatal: " + (e.ExceptionObject as Exception));
    }

    /// <inheritdoc />
    protected override void OnStartup(StartupEventArgs e)
    {
        var command = LauncherCommandLine.Parse(e.Args);
        var headless = command.Mode is CommandLineMode.Screenshot or CommandLineMode.Help or CommandLineMode.Error or CommandLineMode.MotionCheck or CommandLineMode.SafeCheck;

        // The write permission is settled BEFORE anything is constructed, and it is settled by
        // defaulting to no. GameWritePolicy starts denied; only the interactive branch below
        // grants it. Every other mode - including a malformed or unrecognised switch, which starts
        // the launcher but is not a mode anybody asked to be trusted with a write - stays denied, so
        // there is no window in which a non-interactive run holds the real writer and no switch that
        // can turn one back on.
        if (command.AllowsGameWrites)
        {
            Services.GameWritePolicy.AllowWrites("interactive launch, no diagnostic switch present");
        }
        else
        {
            Services.GameWritePolicy.ForbidWrites(DescribeMode(command.Mode));
        }

        // The account chip's skin head, decided the same way and for the same reason. Only a bare
        // launch may reach the network for one. Every diagnostic mode - the headless capture above
        // all - reads the disk cache if it has one and draws the default head if it does not, so a
        // capture is fast, deterministic, and cannot be held up by somebody else's uptime.
        Services.HeadService.NetworkAllowed = command.Mode == CommandLineMode.Interactive;

        if (command.Mode == CommandLineMode.Screenshot)
        {
            // Software rasterisation only. The whole point of this mode is that it must work where
            // there is no usable display adapter, and asking the GPU for anything is the one thing
            // that would make it fail on exactly the machines it exists for.
            RenderOptions.ProcessRenderMode = RenderMode.SoftwareOnly;
        }

        try
        {
            AppPaths.EnsureCreated();
        }
        catch (Exception ex)
        {
            // Cannot write a log yet, and cannot start without the tree. Fail with a readable
            // message rather than a stack trace - and never with a modal dialog in a mode whose
            // entire purpose is to run where there is no display.
            if (headless)
            {
                ReportToConsole(
                    "XsozClient could not create its data folder under %LOCALAPPDATA%\\XsozClient.\n\n" + ex.Message);
                EndWith(1);
                return;
            }

            MessageBox.Show(
                "XsozClient could not create its data folder under %LOCALAPPDATA%\\XsozClient.\n\n" + ex.Message,
                "XsozClient", MessageBoxButton.OK, MessageBoxImage.Warning);
            EndWith(1);
            return;
        }

        AppLog.Shared.Info("Application starting up.");

        switch (command.Mode)
        {
            case CommandLineMode.Help:
                ReportToConsole(LauncherCommandLine.HelpText);
                EndWith(0);
                return;

            case CommandLineMode.Error:
                ReportToConsole(command.Message ?? "That command line could not be understood.");
                EndWith(2);
                return;

            case CommandLineMode.Screenshot:
                var console = HostConsole.TryAttach();
                var exitCode = 1;
                try
                {
                    exitCode = ScreenshotRunner.Run(command.OutputDirectory!, console);
                }
                finally
                {
                    console?.Dispose();
                }

                EndWith(exitCode);
                return;

            case CommandLineMode.SelfTest:
                var selfConsole = HostConsole.TryAttach();
                if (selfConsole is null)
                {
                    HostConsole.TryAllocate();
                    selfConsole = HostConsole.TryAttach();
                }

                var selfCode = 1;
                try
                {
                    selfCode = SelfTestRunner.Run(selfConsole ?? TextWriter.Null);
                }
                finally
                {
                    selfConsole?.Dispose();
                }

                EndWith(selfCode);
                return;

            case CommandLineMode.MotionCheck:
                var motionConsole = HostConsole.TryAttach();
                var motionCode = 1;
                try
                {
                    motionCode = MotionCheck.Run(motionConsole ?? TextWriter.Null, command.SourceDirectory);
                }
                finally
                {
                    motionConsole?.Dispose();
                }

                EndWith(motionCode);
                return;

            case CommandLineMode.SafeCheck:
                var safeConsole = HostConsole.TryAttach();
                if (safeConsole is null)
                {
                    HostConsole.TryAllocate();
                    safeConsole = HostConsole.TryAttach();
                }

                var safeCode = 1;
                try
                {
                    safeCode = SafetyCheck.Run(safeConsole ?? TextWriter.Null, command.SourceDirectory);
                }
                finally
                {
                    safeConsole?.Dispose();
                }

                EndWith(safeCode);
                return;

            default:
                if (command.Message is not null)
                {
                    AppLog.Shared.Warn(command.Message);
                }

                // One launcher per user session. A second start hands over to the first (which
                // shows its window) and exits, instead of becoming a second copy in the background.
                if (!ClaimSingleInstance())
                {
                    EndWith(0);
                    return;
                }

                base.OnStartup(e);
                Core.JsonStore.ExtractLicences(Core.AppPaths.Root);

                // The shell is shown from here rather than through a StartupUri in App.xaml. The
                // framework acts on StartupUri from DoStartup, which runs *after* OnStartup returns,
                // so returning early did not stop it: a second MainWindow was built behind every
                // non-interactive run, while the process was already shutting down. That threw out
                // of MainViewModel's constructor, then threw once per remaining element as the
                // torn-down tree re-resolved its deferred StaticResources, and finally overflowed
                // the stack during teardown. One place now creates the window, and only the
                // interactive path reaches it.
                // The shipped exe is an installer: it adds Xsoz Client to the official Minecraft
                // Launcher and that's all. The old launcher shell is still here for development
                // (set XSOZ_LAUNCHER=1).
                // Verification only: install into the given folder with no window, write the result, exit.
                var autoTarget = Environment.GetEnvironmentVariable("XSOZ_AUTO_INSTALL");
                if (!string.IsNullOrEmpty(autoTarget))
                {
                    var net = new Services.DownloadService();
                    var prov = new Services.VanillaProvisioner(net, Services.GameWritePolicy.CreateWriter(net), new Services.MinecraftDirectory(autoTarget))
                    {
                        IgnoreRunningLauncher = !string.Equals(Path.GetFullPath(autoTarget), Services.MinecraftDirectory.Default.Root, StringComparison.OrdinalIgnoreCase),
                    };
                    var res = prov.RunAsync(CancellationToken.None).GetAwaiter().GetResult();
                    File.WriteAllText(Path.Combine(autoTarget, "xsoz-install-result.txt"),
                        (res.Succeeded ? "OK" : "FAILED") + Environment.NewLine + res.Message + Environment.NewLine + string.Join(Environment.NewLine, res.WrittenPaths));
                    EndWith(res.Succeeded ? 0 : 1);
                    return;
                }

                // Verification only: render the installer window (and its "done" page) to PNGs, then exit.
                var shot = Environment.GetEnvironmentVariable("XSOZ_INSTALLER_SHOT");
                if (!string.IsNullOrEmpty(shot))
                {
                    var w = new Views.InstallerWindow { ShowInTaskbar = false, Left = -4000, Top = -4000 };
                    w.Show();
                    w.SaveShotForTests(shot + "-setup.png", done: false);
                    w.SaveShotForTests(shot + "-done.png", done: true);
                    w.Close();
                    EndWith(0);
                    return;
                }

                if (Environment.GetEnvironmentVariable("XSOZ_LAUNCHER") is null)
                {
                    var installer = new Views.InstallerWindow();
                    MainWindow = installer;
                    installer.Closed += (_, _) => Shutdown(0);
                    installer.Show();
                    return;
                }

                var window = new MainWindow();
                MainWindow = window;
                Tray = new TrayIcon(ShowShell, QuitFromTray);
                Tray.Show();
                window.Show();
                return;
        }
    }

    private const string InstanceMutexName = @"Local\XsozClient.Launcher";
    private const string ShowSignalName = @"Local\XsozClient.Launcher.Show";
    private Mutex? _instanceMutex;
    private EventWaitHandle? _showSignal;

    /// <summary>The notification-area icon. Null in every non-interactive mode.</summary>
    public static TrayIcon? Tray { get; private set; }

    /// <summary>Set by the tray's Quit so the window's close-to-tray does not intercept it.</summary>
    public static bool QuitRequested { get; private set; }

    /// <summary>
    /// True when this process is the only interactive launcher. Otherwise signals the running one to
    /// show its window and returns false. Only the interactive branch calls this, so headless
    /// diagnostics still run alongside an open launcher.
    /// </summary>
    private bool ClaimSingleInstance()
    {
        _instanceMutex = new Mutex(initiallyOwned: true, InstanceMutexName, out var createdNew);
        if (!createdNew)
        {
            _instanceMutex.Dispose();
            _instanceMutex = null;
            try
            {
                using var signal = EventWaitHandle.OpenExisting(ShowSignalName);
                signal.Set();
                AppLog.Shared.Info("Launcher already running; brought the existing window forward.");
            }
            catch (WaitHandleCannotBeOpenedException)
            {
                AppLog.Shared.Warn("Launcher already running, but its window could not be signalled.");
            }

            return false;
        }

        _showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, ShowSignalName);
        var listener = new Thread(() =>
        {
            while (_showSignal.WaitOne())
            {
                Dispatcher.BeginInvoke(ShowShell);
            }
        })
        {
            IsBackground = true,
            Name = "single-instance listener",
        };
        listener.Start();
        return true;
    }

    /// <summary>Brings the shell back from the tray, minimised or behind other windows.</summary>
    private void ShowShell()
    {
        if (MainWindow is not { } window)
        {
            return;
        }

        window.Show();
        if (window.WindowState == WindowState.Minimized)
        {
            window.WindowState = WindowState.Normal;
        }

        window.Activate();
    }

    private void QuitFromTray()
    {
        QuitRequested = true;
        MainWindow?.Close();
        Shutdown(0);
    }

    /// <inheritdoc />
    protected override void OnExit(ExitEventArgs e)
    {
        Tray?.Dispose();
        Tray = null;
        if (_instanceMutex is not null)
        {
            _instanceMutex.ReleaseMutex();
            _instanceMutex.Dispose();
        }

        base.OnExit(e);
    }

    /// <summary>Ends the process with a specific code, from inside startup.</summary>
    private void EndWith(int code)
    {
        // Belt and braces: Shutdown sets the code the framework returns from Run, and the
        // environment value covers a host that decides the exit code for itself.
        Environment.ExitCode = code;
        Shutdown(code);
    }

    /// <summary>
    /// The mode's own name, for the write policy's log line. Says which switch caused it rather than
    /// just that writes are off, so somebody reading a log from a capture run can tell which of
    /// them it was without counting the arguments.
    /// </summary>
    private static string DescribeMode(CommandLineMode mode) => mode switch
    {
        CommandLineMode.Screenshot => LauncherCommandLine.ScreenshotSwitch,
        CommandLineMode.SelfTest => LauncherCommandLine.SelfTestSwitch,
        CommandLineMode.MotionCheck => LauncherCommandLine.MotionCheckSwitch,
        CommandLineMode.SafeCheck => LauncherCommandLine.SafeCheckSwitch,
        CommandLineMode.Help => "--help",
        CommandLineMode.Error => "malformed command line",
        _ => "non-interactive mode",
    };

    /// <summary>Writes a line to whatever console is available, allocating one if none is.</summary>
    private static void ReportToConsole(string text)
    {
        var writer = HostConsole.TryAttach();
        if (writer is null)
        {
            HostConsole.TryAllocate();
            writer = HostConsole.TryAttach();
        }

        if (writer is null)
        {
            return;
        }

        try
        {
            writer.WriteLine(text);
            writer.Flush();
            writer.Dispose();
        }
        catch (Exception)
        {
            // Nowhere to report it to. The process is exiting with a non-zero code anyway.
        }
    }

/// <summary>
    /// The last line of defence: log it, keep the process alive, and tell the user exactly once.
    ///
    /// This used to open a MessageBox per exception. That is defensible the first time and unusable
    /// the hundredth: a fault that recurs on every layout pass re-raises on every layout pass, so the
    /// launcher produced an unbounded stream of modal windows, each of which the user had to dismiss
    /// before the interface would respond again. Nothing about a launcher justifies being unable to
    /// use it because of a rendering defect it has already been told about.
    ///
    /// So the log keeps *every* occurrence in full - that is the evidence, and it is unbounded on
    /// purpose - while the interface is told about each distinct kind of failure once. The dedupe key
    /// is the exception type and its top-level message only, never the stack or the inner exception,
    /// because the inner text of a collection-consistency failure embeds the two counts that differ
    /// and would otherwise make every occurrence look like a brand new problem.
    /// </summary>
    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        var exception = e.Exception;

        try
        {
            // The full ToString, not just the message: a WPF resource-load failure surfaces as a
            // generic property-set exception whose real cause is several frames deeper, and
            // logging only the outer message makes it undiagnosable from the log alone.
            AppLog.Shared.Error("Unhandled: " + exception);
        }
        catch (Exception)
        {
            // The logger itself failed. There is nowhere left to report this.
        }

        Announce(exception);

        e.Handled = true;
    }

    /// <summary>Publishes an unhandled error to the shell, at most once per distinct kind.</summary>
    private static void Announce(Exception exception)
    {
        string kind = exception.GetType().FullName + "|" + exception.Message;

        // Checked on the dispatcher thread only, because that is the only thread this can be reached
        // from, so the set needs no lock of its own.
        if (AnnouncedErrorKinds.Count >= MaxAnnouncedErrorKinds || !AnnouncedErrorKinds.Add(kind))
        {
            return;
        }

        try
        {
            UiError?.Invoke(null, exception.Message);
        }
        catch (Exception)
        {
            // The shell may already be gone. It is still logged, which is the part that matters.
        }
    }
}
