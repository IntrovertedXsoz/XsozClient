using System.Collections.ObjectModel;
using System.Globalization;
using System.Text;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Models;
using Xsoz.Launcher.Services;

namespace Xsoz.Launcher.ViewModels;

/// <summary>One line in the log pane.</summary>
public sealed class LogRow
{
    /// <summary>Wraps a log entry.</summary>
    public LogRow(LogEntry entry)
    {
        Entry = entry;
    }

    /// <summary>The entry.</summary>
    public LogEntry Entry { get; }

    /// <summary>Local timestamp.</summary>
    public string Time => Entry.TimeText;

    /// <summary>Level name.</summary>
    public string Level => Entry.Level.ToString().ToUpperInvariant();

    /// <summary>Palette key for the level colour.</summary>
    public string Accent => Entry.Level switch
    {
        LogLevel.Error => "error",
        LogLevel.Warn => "warn",
        LogLevel.Success => "accent",
        LogLevel.Trace => "muted",
        _ => "text",
    };

    /// <summary>The message body.</summary>
    public string Message => Entry.Message;
}

/// <summary>
/// The launcher log pane. Own log, filtered, bounded and redacted on the way in.
///
/// The subscription to <see cref="AppLog.LineAppended"/> is the delicate part, because
/// <see cref="AppLog"/> is process-wide and raises that event from whichever thread wrote the line.
/// The game process output pump in <see cref="LaunchService"/> is raised by the framework's
/// asynchronous stream reader on a thread-pool thread, so "whichever thread" is a background thread
/// for exactly the lines that arrive fastest, during exactly the session the user is watching.
/// Appending straight from there is what broke this pane; see <see cref="OnLineAppended"/>.
///
/// This used to sit beside a "ready to screenshare" checklist with a pass/fail verdict. That panel
/// is gone: the enforcement it was reporting on still runs in the client, and nothing in the
/// product needs a running tally of it on screen. What is left here is the log, which is the one
/// thing this pane is actually for.
/// </summary>
public sealed class DiagnosticsViewModel : ObservableObject, IDisposable
{
    private const int MaxRows = 1500;
    private const string AllLevels = "ALL";

    private readonly ProfileService _store;
    private readonly Dispatcher _dispatcher;
    private string _filter = string.Empty;
    private LogLevel? _minimumLevel = LogLevel.Trace;
    private bool _disposed;

    /// <summary>Creates the screen and subscribes to the log.</summary>
    public DiagnosticsViewModel(ProfileService store)
    {
        _store = store;
        _dispatcher = Application.Current?.Dispatcher ?? Dispatcher.CurrentDispatcher;

        SetFilterCommand = RelayCommand.CreateWithParameter(p => Filter = p as string ?? string.Empty);
        ClearFilterCommand = RelayCommand.Create(() => Filter = string.Empty);
        CopyAllCommand = RelayCommand.Create(() => CopyAll());
        CopyReportCommand = RelayCommand.Create(() => CopyReport());
        OpenLogFolderCommand = RelayCommand.Create(() => AppLog.Shared.OpenLogFolder());
        SetLevelCommand = RelayCommand.CreateWithParameter(p =>
        {
            _minimumLevel = p switch
            {
                string s when Enum.TryParse<LogLevel>(s, true, out var parsed) => parsed,
                null => LogLevel.Trace,
                _ => LogLevel.Trace,
            };
            Raise(nameof(MinimumLevel));
            Rebuild();
        });

        // The backfill is inline because nothing is bound to Rows yet - there is no visual tree and
        // therefore no generator that could be looking at this collection. Every later mutation goes
        // through the queued path below instead.
        Rows.ReplaceAll(AppLog.Shared.Snapshot().Where(PassesFilter).Select(entry => new LogRow(entry)));
        Raise(nameof(RowCount));

        AppLog.Shared.LineAppended += OnLineAppended;
    }

    /// <summary>Log rows, capped and filtered.</summary>
    public ResetableCollection<LogRow> Rows { get; } = [];

    /// <summary>Sets the free-text log filter.</summary>
    public RelayCommand SetFilterCommand { get; }

    /// <summary>Clears the log filter.</summary>
    public RelayCommand ClearFilterCommand { get; }

    /// <summary>Copies the filtered log to the clipboard.</summary>
    public RelayCommand CopyAllCommand { get; }

    /// <summary>Copies a diagnostics report. Nothing is uploaded.</summary>
    public RelayCommand CopyReportCommand { get; }

    /// <summary>Opens the launcher's log folder.</summary>
    public RelayCommand OpenLogFolderCommand { get; }

    /// <summary>Sets the minimum severity shown.</summary>
    public RelayCommand SetLevelCommand { get; }

    /// <summary>Free-text filter over the message body.</summary>
    public string Filter
    {
        get => _filter;
        set
        {
            if (Set(ref _filter, value ?? string.Empty))
            {
                Rebuild();
            }
        }
    }

    /// <summary>Minimum severity shown.</summary>
    public LogLevel? MinimumLevel
    {
        get => _minimumLevel;
        set
        {
            if (Set(ref _minimumLevel, value))
            {
                Rebuild();
            }
        }
    }

    /// <summary>
    /// The minimum severity, as the string the combo box actually holds.
    ///
    /// The combo's items are strings and its selection is compared by value, so binding it
    /// straight to the nullable <see cref="LogLevel"/> failed the type check silently and left the
    /// box blank - a filter control that shows nothing is worse than no filter control. Going
    /// through the same text the items hold makes the selection bind in both directions, and gives
    /// the panel a genuine "show everything" option instead of only showing Trace.
    /// </summary>
    public string MinimumLevelName
    {
        get => _minimumLevel is { } level ? level.ToString().ToUpperInvariant() : AllLevels;
        set
        {
            var name = (value ?? string.Empty).Trim();

            // "ALL" has to be recognised before Enum.TryParse, which would otherwise take it as a
            // member name and quietly reset the filter to Trace.
            if (string.Equals(name, AllLevels, StringComparison.OrdinalIgnoreCase))
            {
                MinimumLevel = null;
                return;
            }

            if (Enum.TryParse<LogLevel>(name, ignoreCase: true, out var parsed) && parsed != _minimumLevel)
            {
                MinimumLevel = parsed;
            }
        }
    }

    /// <summary>Severity options for the combo box, in ascending order, with an explicit all.</summary>
    public IReadOnlyList<string> Levels { get; } =
        [AllLevels, "TRACE", "INFO", "WARN", "ERROR", "SUCCESS"];

    /// <summary>How many rows are currently shown.</summary>
    public int RowCount => Rows.Count;

    /// <summary>Path of today's log file, or a note.</summary>
    public string LogFilePath => AppLog.Shared.CurrentFile ?? "No file written yet.";

    /// <summary>
    /// Queues the row. Never touches <see cref="Rows"/> itself.
    ///
    /// Two separate rules are being enforced here, and both of them are about *when* the mutation
    /// runs rather than about what it does:
    ///
    /// It must not run off the UI thread. <see cref="AppLog.Shared"/> raises this event on whichever
    /// thread wrote the line, and the game's stdout/stderr pump is a thread-pool thread. An
    /// <see cref="ObservableCollection{T}"/> bound to a WPF list cannot be changed from there: the
    /// <c>ListCollectionView</c> refuses the notification and throws <c>NotSupportedException</c>
    /// *after* the underlying list has already taken the item. The generator never processes that
    /// event, so from then on its running count and the real count disagree permanently - and the
    /// next time a layout pass walks the list, <c>ItemContainerGenerator.Verify()</c> fails with
    /// "An ItemsControl is inconsistent with its items source". The cross-thread mutation is the
    /// cause; the consistency error is the symptom surfacing a screen later.
    ///
    /// It must not run during a layout or render pass either, which is why this is queued even when
    /// the caller is already on the UI thread. Background priority sits below Render, so the append
    /// always lands after the measure pass that was in flight when the line arrived. The check
    /// inside <see cref="Append"/> is the backstop for the case where the dispatcher is shut down
    /// before the queued callback gets its turn.
    /// </summary>
    private void OnLineAppended(object? sender, LogEntry entry)
    {
        if (_disposed)
        {
            return;
        }

        _dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() => Append(entry)));
    }

    private void Append(LogEntry entry)
    {
        // Reached only from the dispatcher, and only while this view model still exists: a queued
        // callback outlives the window that queued it, and the log keeps being written after the
        // pane has gone.
        if (_disposed || !_dispatcher.CheckAccess())
        {
            return;
        }

        if (!PassesFilter(entry))
        {
            return;
        }

        // Cap the collection so a long session cannot grow it without bound. Trimming only once the
        // row is known to be wanted: the old order evicted the oldest visible row for every line
        // the filter rejected, so typing into the filter box quietly shortened the log.
        if (Rows.Count >= MaxRows)
        {
            Rows.RemoveAt(0);
        }

        Rows.Add(new LogRow(entry));
        Raise(nameof(RowCount));
    }

    private bool PassesFilter(LogEntry entry)
    {
        if (_minimumLevel is { } level && entry.Level < level)
        {
            return false;
        }

        return string.IsNullOrWhiteSpace(_filter)
               || entry.Message.Contains(_filter.Trim(), StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Refills the pane from the log ring after a filter change.
    ///
    /// Safe to call synchronously, unlike <see cref="Append"/>, because it replaces the contents in
    /// one Reset rather than announcing a long sequence of individual changes. A Reset makes no
    /// claim about which rows are where, so there is no intermediate state a layout pass can catch
    /// half applied - which is exactly the guarantee a Clear-then-refill run does not give.
    /// </summary>
    private void Rebuild()
    {
        if (_disposed || !_dispatcher.CheckAccess())
        {
            return;
        }

        Rows.ReplaceAll(AppLog.Shared.Snapshot().Where(PassesFilter).Select(entry => new LogRow(entry)));
        Raise(nameof(RowCount));
    }

    /// <summary>
    /// Detaches from the process-wide log and refuses every later mutation.
    ///
    /// <see cref="AppLog.Shared"/> is a singleton with a static event, so a pane that subscribes
    /// and never unsubscribes stays reachable for the life of the process. That costs nothing while
    /// exactly one shell exists; it costs everything once a second shell is built over the first, as
    /// the headless capture path does, because every stale pane is then still appending to a
    /// collection whose window has gone. Called from the window's Closed handler.
    /// </summary>
    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        AppLog.Shared.LineAppended -= OnLineAppended;
    }

    private void CopyAll()
    {
        try
        {
            var builder = new StringBuilder();
            foreach (var row in Rows)
            {
                builder.Append(row.Time).Append(" [").Append(row.Level).Append("] ").AppendLine(row.Message);
            }

            Clipboard.SetText(builder.ToString());
            SetFeedback($"{Rows.Count} lines copied to the clipboard. Nothing was uploaded.");
        }
        catch (Exception ex)
        {
            SetFeedback("Could not write to the clipboard: " + ex.Message, isError: true);
        }
    }

    private void CopyReport()
    {
        try
        {
            var version = _store.SelectedVersion;
            var profile = _store.Active;
            var jvm = JvmFlagBuilder.Build(version, _store.Settings);
            var builder = new StringBuilder();

            builder.AppendLine("──────── XsozClient diagnostics ────────");
            builder.AppendLine("Generated:      " + DateTime.Now.ToString("f", CultureInfo.CurrentCulture) + " local");
            builder.AppendLine("Launcher:       " + (typeof(DiagnosticsViewModel).Assembly.GetName().Version?.ToString(3) ?? "0.1.0"));
            builder.AppendLine("Machine:        " + MachineInfo.PhysicalMemoryMb() / 1024 + " GB physical, "
                               + MachineInfo.LogicalProcessors + " logical processors");
            builder.AppendLine("Version:        " + version.Label + " — " + version.AccentNote);
            builder.AppendLine("  loader:       " + version.LoaderName + " " + version.LoaderVersion);
            builder.AppendLine("  java:         " + version.JavaMajor);
            builder.AppendLine("  mod layer:    " + version.ModLayerCount + " components");
            builder.AppendLine("Profile:        " + profile.Name + (profile.IsDefault ? " (default)" : string.Empty));
            builder.AppendLine("  input:        " + SensitivityModel.FormatCentimetresPer360(profile.CentimetresPer360)
                               + " cm/360 @ " + profile.MouseDpi + " DPI, FOV " + profile.VerticalFov.ToString("0", CultureInfo.InvariantCulture));
            builder.AppendLine("  sensitivity:  " + (SensitivityModel.SensitivityForCentimetresPer360(profile.CentimetresPer360, profile.MouseDpi) is { } s
                ? SensitivityModel.FormatPercent(s) + " (derived, vanilla range)"
                : "outside the vanilla range at this DPI"));
            builder.AppendLine("  fov mode:     " + profile.FovRelativeMode);
            builder.AppendLine("JVM flags:      " + jvm.AsCommandLineString);
            builder.AppendLine("State paths:    %LOCALAPPDATA%\\XsozClient (instances, profiles, logs, runtimes, modlayer)");
            builder.AppendLine("Telemetry:      none. This report is written to your clipboard and nowhere else.");
            builder.AppendLine("MUG disclaimer: this is not an official Mojang or Microsoft product.");
            builder.AppendLine("Log (last 60 lines):");
            foreach (var row in Rows.TakeLast(60))
            {
                builder.AppendLine("  " + row.Time + " [" + row.Level + "] " + row.Message);
            }

            Clipboard.SetText(builder.ToString());
            SetFeedback("Diagnostics report copied. It was written to your clipboard and nowhere else — there is no "
                        + "upload, and no crash report is ever sent automatically.");
        }
        catch (Exception ex)
        {
            SetFeedback("Could not build the report: " + ex.Message, isError: true);
        }
    }

    private string _feedback = string.Empty;
    private bool _feedbackIsError;

    /// <summary>Transient status line under the log header.</summary>
    public string Feedback
    {
        get => _feedback;
        private set => Set(ref _feedback, value);
    }

    /// <summary>True when the feedback line describes a failure.</summary>
    public bool FeedbackIsError
    {
        get => _feedbackIsError;
        private set => Set(ref _feedbackIsError, value);
    }

    private void SetFeedback(string message, bool isError = false)
    {
        Feedback = message;
        FeedbackIsError = isError;
    }
}
