namespace Xsoz.Launcher.Services;

/// <summary>
/// The real writer. Every method here is a disk operation and nothing else, because the only thing
/// this type is allowed to add to provisioning is the ability to touch the disk - not a second
/// opinion about what should be written, or a decision about whether it may be.
///
/// Constructed in exactly one place in the whole project: <see cref="GameWritePolicy.CreateWriter"/>,
/// and only when the process has been told it is interactive. A second construction site is a
/// failure of <c>--safecheck</c>, not a style preference - see the remarks there.
/// </summary>
public sealed class RealGameDirectoryWriter : IGameDirectoryWriter
{
    private readonly DownloadService _net;
    private int _attempts;

    /// <summary>Creates the writer over the real filesystem.</summary>
    public RealGameDirectoryWriter(DownloadService net) => _net = net;

    /// <inheritdoc />
    public string Name => "real filesystem";

    /// <inheritdoc />
    public bool CanWrite => true;

    /// <inheritdoc />
    public int WriteAttempts => Volatile.Read(ref _attempts);

    /// <inheritdoc />
    public IReadOnlyList<string> RefusedOperations => [];

    /// <inheritdoc />
    public void CreateDirectory(string path)
    {
        Interlocked.Increment(ref _attempts);
        Directory.CreateDirectory(path);
    }

    /// <inheritdoc />
    public async Task WriteTextAsync(string path, string contents, CancellationToken ct)
    {
        Interlocked.Increment(ref _attempts);
        await File.WriteAllTextAsync(path, contents, ct).ConfigureAwait(false);
    }

    /// <inheritdoc />
    public async Task DownloadToAsync(string url, string path, long sizeBytes, string? sha1Hex, CancellationToken ct)
    {
        Interlocked.Increment(ref _attempts);
        await _net.DownloadAsync(url, path, sizeBytes, sha1Hex, null, ct).ConfigureAwait(false);
    }

    /// <inheritdoc />
    public void CopyFile(string sourcePath, string destinationPath)
    {
        Interlocked.Increment(ref _attempts);
        File.Copy(sourcePath, destinationPath, overwrite: true);
    }

    /// <inheritdoc />
    public async Task WriteBytesAsync(string path, byte[] contents, CancellationToken ct)
    {
        Interlocked.Increment(ref _attempts);
        var temp = path + ".tmp";
        await File.WriteAllBytesAsync(temp, contents, ct).ConfigureAwait(false);
        File.Move(temp, path, overwrite: true);
    }

    /// <inheritdoc />
    public void DeleteFile(string path)
    {
        Interlocked.Increment(ref _attempts);
        if (File.Exists(path))
        {
            File.Delete(path);
        }
    }
}
