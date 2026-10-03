using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;

namespace Xsoz.Launcher.Core;

/// <summary>Severity of a launcher log line.</summary>
public enum LogLevel
{
    Trace,
    Info,
    Warn,
    Error,
    Success,
}

/// <summary>One line in the launcher's own log.</summary>
/// <param name="Level">Severity.</param>
/// <param name="Message">Already redacted text.</param>
/// <param name="TimestampUtc">When it happened.</param>
public readonly record struct LogEntry(LogLevel Level, string Message, DateTime TimestampUtc)
{
    /// <summary>Local wall-clock time formatted for display.</summary>
    public DateTime TimestampLocal => TimestampUtc.ToLocalTime();

    /// <summary>HH:mm:ss prefix used by the log pane.</summary>
    public string TimeText => TimestampLocal.ToString("HH:mm:ss");
}

/// <summary>
/// The launcher's own log. Writes to a daily file under %LOCALAPPDATA%\XsozClient\logs,
/// keeps a bounded in-memory ring for the log pane, and redacts secrets on the way in
/// (redaction happens here, not in the exporter, so a screenshot of the pane is also safe).
/// </summary>
public sealed class AppLog
{
    private const int Capacity = 4000;
    private const long MaxFileBytes = 4L * 1024 * 1024;

    private static readonly string[] SecretFragments =
    [
        "accessToken", "access_token", "refresh_token", "clientSecret", "client_secret",
        "--accessToken", "Authorization:", "Bearer ", "xsts", "uhs=", "password",
    ];

    private readonly ConcurrentQueue<LogEntry> _entries = new();
    private readonly Lock _fileGate = new();
    private string? _currentFile;

    private AppLog()
    {
    }

    /// <summary>Process-wide log singleton.</summary>
    public static AppLog Shared { get; } = new();

    /// <summary>
    /// Raised for every accepted line so the UI can append without polling.
    ///
    /// Best-effort UI sugar, and it has to stay that way. A subscriber that fails would otherwise
    /// have its failure come back through this same method - the exception handler logs, and logging
    /// raises the event again - so the guard below is what stops a broken subscriber from turning
    /// into a notification loop that runs at the dispatcher's rate forever.
    /// </summary>
    public event EventHandler<LogEntry>? LineAppended;

    /// <summary>
    /// Per-thread, because the guard is about recursion on one thread rather than about concurrency
    /// between threads: two different writers notifying at once is fine, one writer notifying inside
    /// its own notification is not.
    /// </summary>
    [ThreadStatic]
    private static bool _inNotification;

    /// <summary>Snapshot of the ring buffer, oldest first.</summary>
    public IReadOnlyList<LogEntry> Snapshot() => _entries.ToArray();

    /// <summary>Today's launcher log file path, or null before the first write.</summary>
    public string? CurrentFile => _currentFile;

    /// <summary>Writes an informational line.</summary>
    public void Info(string message) => Write(LogLevel.Info, message);

    /// <summary>Writes a success line.</summary>
    public void Success(string message) => Write(LogLevel.Success, message);

    /// <summary>Writes a warning line.</summary>
    public void Warn(string message) => Write(LogLevel.Warn, message);

    /// <summary>Writes an error line. Never throws.</summary>
    public void Error(string message) => Write(LogLevel.Error, message);

    /// <summary>Writes a line at an explicit level after redaction.</summary>
    public void Write(LogLevel level, string message)
    {
        var safe = Redact(message);
        var entry = new LogEntry(level, safe, DateTime.UtcNow);

        _entries.Enqueue(entry);
        while (_entries.Count > Capacity && _entries.TryDequeue(out _))
        {
            // Trim to the ring capacity.
        }

        AppendToFile(entry);
        NotifyLineAppended(entry);
    }

    /// <summary>
    /// Raises <see cref="LineAppended"/> unless this thread is already inside a raise.
    ///
    /// A line produced *by* the notification path - which is exactly what an unhandled UI exception
    /// logged from the handler does - is dropped rather than re-raised, so the notification cannot
    /// feed itself. Dropping it loses nothing: the line is already in the ring buffer and on disk,
    /// which is where anything reading the log would look for it.
    /// </summary>
    private void NotifyLineAppended(LogEntry entry)
    {
        if (_inNotification)
        {
            return;
        }

        _inNotification = true;
        try
        {
            LineAppended?.Invoke(this, entry);
        }
        catch (Exception)
        {
            // The subscriber's own failure. It is not this class's to log - logging it here is the
            // recursion this guard exists to prevent - and it must not propagate to the writer.
        }
        finally
        {
            _inNotification = false;
        }
    }

    /// <summary>
    /// Strips anything token-shaped. Cheap substring scan rather than a regex: this runs on
    /// every line of a game log that can run to thousands of lines a minute.
    /// </summary>
    public static string Redact(string message)
    {
        if (string.IsNullOrEmpty(message))
        {
            return string.Empty;
        }

        var hasSecret = false;
        foreach (var fragment in SecretFragments)
        {
            if (message.Contains(fragment, StringComparison.OrdinalIgnoreCase))
            {
                hasSecret = true;
                break;
            }
        }

        if (!hasSecret)
        {
            return message;
        }

        var sb = new StringBuilder(message.Length);
        foreach (var (segment, secret) in SplitKeepingSecrets(message))
        {
            if (secret)
            {
                sb.Append("[redacted]");
            }
            else
            {
                sb.Append(segment);
            }
        }

        return sb.ToString();
    }

    private static IEnumerable<(string Segment, bool Secret)> SplitKeepingSecrets(string message)
    {
        int index = 0;
        while (index < message.Length)
        {
            int bestStart = -1;
            int bestLength = 0;
            foreach (var fragment in SecretFragments)
            {
                int found = message.IndexOf(fragment, index, StringComparison.OrdinalIgnoreCase);
                if (found >= 0 && (bestStart < 0 || found < bestStart))
                {
                    bestStart = found;
                    bestLength = fragment.Length;
                }
            }

            if (bestStart < 0)
            {
                yield return (message[index..], false);
                yield break;
            }

            if (bestStart > index)
            {
                yield return (message[index..bestStart], false);
            }

            // Consume to the next whitespace so the whole value goes, not just the key.
            int end = bestStart + bestLength;
            while (end < message.Length && !char.IsWhiteSpace(message[end]))
            {
                end++;
            }

            if (end == bestStart + bestLength)
            {
                // Value lives in the next token.
                while (end < message.Length && char.IsWhiteSpace(message[end]))
                {
                    end++;
                }

                while (end < message.Length && !char.IsWhiteSpace(message[end]))
                {
                    end++;
                }
            }

            yield return (message[bestStart..end], true);
            index = end;
        }
    }

    private void AppendToFile(LogEntry entry)
    {
        try
        {
            lock (_fileGate)
            {
                Directory.CreateDirectory(AppPaths.Logs);
                var path = Path.Combine(AppPaths.Logs, $"launcher-{DateTime.Now:yyyyMMdd}.log");

                if (_currentFile != path || !File.Exists(path))
                {
                    RollIfOversized(path);
                    _currentFile = path;
                }

                File.AppendAllText(
                    path,
                    $"{entry.TimestampLocal:yyyy-MM-dd HH:mm:ss.fff} [{entry.Level.ToString().ToUpperInvariant(),-7}] {entry.Message}{Environment.NewLine}",
                    Encoding.UTF8);
            }
        }
        catch (Exception)
        {
            // A log write must never take the app down.
        }
    }

    private void RollIfOversized(string path)
    {
        try
        {
            var info = new FileInfo(path);
            if (info.Exists && info.Length > MaxFileBytes)
            {
                var rolled = Path.Combine(AppPaths.Logs, $"launcher-{DateTime.Now:yyyyMMdd}.{info.LastWriteTime:HHmmss}.log");
                File.Move(path, rolled, overwrite: true);
            }
        }
        catch (Exception)
        {
            // Ignore: rotation is a convenience, not a requirement.
        }
    }

    /// <summary>Opens the launcher's log folder in Explorer. Read-only navigation.</summary>
    public void OpenLogFolder()
    {
        try
        {
            Directory.CreateDirectory(AppPaths.Logs);
            Process.Start(new ProcessStartInfo("explorer.exe", AppPaths.Logs) { UseShellExecute = true })?.Dispose();
        }
        catch (Exception ex)
        {
            Warn($"Could not open the log folder: {ex.Message}");
        }
    }
}
