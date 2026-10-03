using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>
/// A writer that writes nothing, and says so loudly if it is ever asked to.
///
/// This is what the read-only modes get: <c>--screenshot</c>, <c>--motioncheck</c>, <c>--safecheck</c>
/// and <c>--help</c>. <see cref="VanillaProvisioner"/> is handed one of these, so the screenshot
/// path does not merely decline to provision - it holds an object whose every method is a no-op,
/// and a forgotten flag cannot change that, because there is no flag on this type.
///
/// THE COUNTER IS THE POINT. Every refused call is recorded, and
/// <see cref="VanillaProvisioner"/> returns before it makes any, so after a correct read-only run
/// <see cref="WriteAttempts"/> is zero. That value is asserted by the preflight on every single
/// capture, which turns "the screenshot does not touch your Minecraft folder" from a claim in a
/// comment into a number somebody re-measures each time. A non-zero count is a defect, and it is
/// reported as a failed check rather than swallowed: a silent sink that was asked to write is
/// exactly the bug this whole arrangement exists to make impossible.
/// </summary>
public sealed class NoOpGameDirectoryWriter : IGameDirectoryWriter
{
    private readonly object _gate = new();
    private readonly List<string> _refused = [];
    private int _attempts;

    /// <inheritdoc />
    public string Name => "read-only sink (every write refused)";

    /// <inheritdoc />
    public bool CanWrite => false;

    /// <inheritdoc />
    public int WriteAttempts => Volatile.Read(ref _attempts);

    /// <inheritdoc />
    public IReadOnlyList<string> RefusedOperations
    {
        get
        {
            lock (_gate)
            {
                return _refused.ToArray();
            }
        }
    }

    /// <inheritdoc />
    public void CreateDirectory(string path) => Refuse("CreateDirectory", path);

    /// <inheritdoc />
    public Task WriteTextAsync(string path, string contents, CancellationToken ct)
    {
        Refuse("WriteText", path);
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task DownloadToAsync(string url, string path, long sizeBytes, string? sha1Hex, CancellationToken ct)
    {
        Refuse("DownloadTo", path + " <- " + url);
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public void CopyFile(string sourcePath, string destinationPath) => Refuse("CopyFile", destinationPath);

    /// <inheritdoc />
    public Task WriteBytesAsync(string path, byte[] contents, CancellationToken ct)
    {
        Refuse("WriteBytes", path);
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public void DeleteFile(string path) => Refuse("DeleteFile", path);

    /// <summary>
    /// Records the attempt and returns without touching anything. Warned rather than silently
    /// dropped: a caller that reaches this method has a bug, and a bug that logs is a bug that
    /// gets found.
    /// </summary>
    private void Refuse(string operation, string path)
    {
        Interlocked.Increment(ref _attempts);

        string line;
        lock (_gate)
        {
            _refused.Add(operation + " " + path);
            line = "Read-only sink refused " + operation + " " + path + ".";
        }

        AppLog.Shared.Warn(line);
    }
}
