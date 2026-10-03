using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>
/// Whether THIS PROCESS is allowed to write into the official Minecraft launcher's directory.
///
/// THE DEFAULT IS NO. A static initialiser that grants the permission would make every
/// non-interactive run safe only because somebody remembered to take it away again, which is the
/// failure this replaces. Here the field starts at zero and the only way to reach one is
/// <see cref="AllowWrites"/>, which is called from exactly one place: the interactive branch of
/// <c>App.OnStartup</c>. Every other branch - <c>--screenshot</c>, <c>--motioncheck</c>,
/// <c>--safecheck</c>, <c>--selftest</c>, <c>--help</c>, a malformed switch - falls through to
/// <see cref="ForbidWrites"/>, so "forbidden" is the state a run starts and ends in unless a
/// human asked for the launcher.
///
/// WHAT THIS DOES AND DOES NOT GOVERN. It governs the official launcher's directory and nothing
/// else. The launcher's own tree under <c>%LOCALAPPDATA%\XsozClient</c> is this product's, and
/// <c>--screenshot</c> legitimately writes its PNGs, its log and the copy it merges against. The
/// line being drawn is the one that matters: a diagnostic run may read somebody's installation and
/// may write its own evidence, and it may not touch theirs.
///
/// IT IS NOT AN ENVIRONMENT VARIABLE, and it is not a build flag either. Both of those can be lost
/// between shell sessions, inherited from a parent process, or absent on the machine where it
/// matters. The mode comes from the argument list this process was started with, which is the one
/// fact nobody can forget to pass - and if they do pass the wrong one, the wrong one is
/// <c>--screenshot</c>, and the answer is no.
/// </summary>
public static class GameWritePolicy
{
    /// <summary>
    /// The one line a read-only run prints when provisioning would otherwise have run. It exists
    /// because a guard nobody can see is indistinguishable from a guard that is not there.
    /// </summary>
    public const string ProvisioningSkippedLine =
        "Read-only capture: provisioning skipped (no files written).";

    private static int _allowsGameWrites;

    /// <summary>True only in a run a human started interactively.</summary>
    public static bool AllowsGameWrites => Volatile.Read(ref _allowsGameWrites) == 1;

    /// <summary>Why the permission is in its current state, for the log and the report.</summary>
    public static string Reason { get; private set; } = "unset - denied until a mode grants it";

    /// <summary>What this process would print about its own permission, on one line.</summary>
    public static string Describe() =>
        (AllowsGameWrites ? "game-directory writes ALLOWED" : "game-directory writes FORBIDDEN") + " (" + Reason + ")";

    /// <summary>
    /// Grants the permission. Called from the interactive branch of <c>App.OnStartup</c> and
    /// nowhere else; a second call site is a failure of <c>--safecheck</c>.
    /// </summary>
    public static void AllowWrites(string reason)
    {
        Reason = reason;
        Volatile.Write(ref _allowsGameWrites, 1);
        AppLog.Shared.Info("Game-directory writes allowed: " + reason + ".");
    }

    /// <summary>Denies the permission. Every non-interactive mode calls this, including on error.</summary>
    public static void ForbidWrites(string reason)
    {
        Reason = reason;
        Volatile.Write(ref _allowsGameWrites, 0);
        AppLog.Shared.Info(
            "Read-only mode (" + reason + "): nothing in this process may write into "
            + MinecraftDirectory.Default.Root + ".");
    }

    /// <summary>
    /// The one place in the project that decides which writer a provisioner gets.
    /// <para>
    /// A real one only when the process is interactive; otherwise the no-op sink. This is the whole
    /// reason <c>--safecheck</c> can assert that no code path reachable from a read-only mode can
    /// reach the disk: there is one <c>new RealGameDirectoryWriter</c> in the source, it is here,
    /// and here it is unreachable unless <see cref="AllowsGameWrites"/> is true.
    /// </para>
    /// </summary>
    public static IGameDirectoryWriter CreateWriter(DownloadService net) =>
        AllowsGameWrites ? new RealGameDirectoryWriter(net) : new NoOpGameDirectoryWriter();
}
