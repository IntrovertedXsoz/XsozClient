namespace Xsoz.Launcher.Services;

/// <summary>
/// The ONLY way provisioning is allowed to touch the disk.
///
/// WHY THIS EXISTS, and why it is an interface rather than a boolean. Provisioning writes into
/// somebody else's installation: a version folder, a set of libraries, and one member of
/// <c>launcher_profiles.json</c>, all under <c>%APPDATA%\.minecraft</c>. That is the intended
/// product behaviour, and it is correct - but it must be reachable ONLY from a run a human
/// started by clicking a button.
///
/// A boolean argument does not achieve that. A boolean is a value at a call site, and a call site
/// is only as reliable as the day it was written: <c>RunAsync(ct)</c> reads fine, compiles fine and
/// provisions for real. That is exactly how <c>--screenshot</c> came to write a real version folder
/// and eight real jars into a real profile store - the preflight's protection was an opt-OUT
/// environment variable, so the safe state was the one that required remembering something, and the
/// variable was remembered on the day it was added and not on the day it was not.
///
/// So the capability is a constructor argument with no default. <see cref="VanillaProvisioner"/>
/// cannot be built without one, the compiler refuses a call site that forgets, and the read-only
/// modes are handed an implementation whose every method is a no-op. There is no code path from
/// <c>--screenshot</c> to <see cref="System.IO.File"/> that does not go through an object which
/// counts the attempt and does nothing.
///
/// WHAT IS AND IS NOT HERE. These are the four disk primitives provisioning uses, and nothing else:
/// the order, the paths, the gating and the verification all stay in
/// <see cref="VanillaProvisioner"/>, so this interface is a permission and not a second
/// implementation of the same logic. Reads are deliberately absent - the provisioning path is
/// allowed to read the official directory in every mode, because reading it is what makes a
/// read-only report possible.
/// </summary>
public interface IGameDirectoryWriter
{
    /// <summary>What this writer is, for the log and the diagnostic report.</summary>
    string Name { get; }

    /// <summary>
    /// False when every write is refused. <see cref="VanillaProvisioner"/> consults this before it
    /// does anything at all, so a false value means not one byte moves and not one request is sent.
    /// </summary>
    bool CanWrite { get; }

    /// <summary>
    /// How many mutating calls this writer has been asked to perform. For a no-op sink the only
    /// acceptable value after a whole read-only run is ZERO - the point of the counter is that
    /// "nothing was written" is a measurement rather than a promise, and it is asserted on every
    /// single <c>--screenshot</c>.
    /// </summary>
    int WriteAttempts { get; }

    /// <summary>
    /// A description of every mutating call this writer refused. Empty after a correct read-only
    /// run; non-empty means the provisioner tried anyway, which is a defect and is reported as one.
    /// </summary>
    IReadOnlyList<string> RefusedOperations { get; }

    /// <summary>Creates a directory, and any parents.</summary>
    void CreateDirectory(string path);

    /// <summary>Writes a text file, replacing it if it exists.</summary>
    Task WriteTextAsync(string path, string contents, CancellationToken ct);

    /// <summary>
    /// Fetches a URL to a path, with the same resume-and-verify behaviour the interactive path has.
    /// </summary>
    Task DownloadToAsync(string url, string path, long sizeBytes, string? sha1Hex, CancellationToken ct);

    /// <summary>Copies a file, replacing the destination if it exists.</summary>
    void CopyFile(string sourcePath, string destinationPath);

    /// <summary>Writes a binary file atomically (temp file, then move), replacing it if it exists.</summary>
    Task WriteBytesAsync(string path, byte[] contents, CancellationToken ct);

    /// <summary>Deletes one file if it exists. Used only to replace an older copy of our own mod jar.</summary>
    void DeleteFile(string path);
}
