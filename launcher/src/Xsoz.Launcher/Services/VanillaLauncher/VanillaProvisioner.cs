using System.Text.Json;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>The end state of a provisioning run.</summary>
public sealed class VanillaProvisionResult
{
    /// <summary>True when the official launcher now has an installation it can launch.</summary>
    public required bool Succeeded { get; init; }

    /// <summary>One or two plain sentences for the interface. Never a stack trace.</summary>
    public required string Message { get; init; }

    /// <summary>True when the only thing standing between the user and a launch is closing the launcher.</summary>
    public bool BlockedByRunningLauncher { get; init; }

    /// <summary>True when the official launcher's game folder does not exist on this machine.</summary>
    public bool MissingMinecraftDirectory { get; init; }

    /// <summary>
    /// True when the run was refused before it began because the process holds a read-only writer.
    /// Not an error condition and not a failure to report to a user: it is the answer to "what
    /// happens if a diagnostic run tries to provision", and the only acceptable value for the
    /// capture path is that it comes back true having written nothing.
    /// </summary>
    public bool ReadOnlyMode { get; init; }

    /// <summary>
    /// The version <c>inheritsFrom</c> names that the official launcher does not have installed,
    /// or empty. When set, <see cref="Message"/> says which version and how to install it.
    /// </summary>
    public string MissingInheritedVersion { get; init; } = string.Empty;

    /// <summary>The profile merge result, when the run got that far.</summary>
    public ProfileMergeOutcome? Profile { get; init; }

    /// <summary>The JVM merge report, when the run got that far.</summary>
    public JvmMergeReport? Jvm { get; init; }

    /// <summary>Every absolute path this run wrote, for the log and for the report.</summary>
    public IReadOnlyList<string> WrittenPaths { get; init; } = [];
}

/// <summary>
/// Provisions our Fabric version and mod layer into the OFFICIAL Minecraft launcher's game
/// directory, then registers one installation and hands off.
///
/// WHAT THIS DOES NOT DO, which is the whole architecture:
///
///   * It does not authenticate. Not a client id, not a token, not a refresh, not a credential
///     file. The official launcher signs the user in; that is the entire reason this architecture
///     exists.
///   * It does not read the user's saves, mods, options or any other profile's data.
///   * It does not write outside two places: our own tree under <c>%LOCALAPPDATA%\XsozClient</c>,
///     and namespaced new files inside <c>%APPDATA%\.minecraft</c>.
///   * It never runs elevated and never asks to.
///
/// THE WRITES INTO THE OFFICIAL DIRECTORY, in order, each guarded individually:
///
///   1. <c>versions\&lt;id&gt;\&lt;id&gt;.json</c>   the version definition we own
///   2. <c>libraries\net\fabricmc\...</c>   the loader and intermediary jars, at the paths the
///      fetched JSON's own libraries entries specify
///   3. <c>versions\&lt;id&gt;\&lt;id&gt;.jar</c>   a copy of the vanilla client jar, because every
///      Fabric version folder on this machine has one and its absence is an unquantified risk for
///      a few megabytes
///   4. <c>launcher_profiles.json</c>     one merged member, atomically, with a backup
///
/// Nothing else. The user's existing versions, profiles, saves and mods are read to be preserved,
/// never written.
///
/// HOW ANY OF IT IS ALLOWED TO HAPPEN. Every one of those writes goes through an
/// <see cref="IGameDirectoryWriter"/>, which is a required constructor argument with no default.
/// A run that was not started by a human - <c>--screenshot</c>, <c>--motioncheck</c>,
/// <c>--safecheck</c>, <c>--help</c> - is handed a <see cref="NoOpGameDirectoryWriter"/>, and this
/// method then returns having touched neither the disk nor the network. That is deliberately not a
/// flag: a flag is a value somebody has to remember to pass at a call site, and the call site that
/// forgot one is how a screenshot run once provisioned a real version folder and eight real jars
/// into a real profile store. <see cref="GameWritePolicy"/> is the default-deny switch that picks
/// the writer, and it starts denied.
///
/// WHY THE GAME IS NOT RE-DOWNLOADED. When the official launcher already has 1.21.11 installed -
/// and it does, on this machine - there is nothing to fetch: the launcher will resolve
/// <c>inheritsFrom</c> itself, including the client jar, the 107 libraries, the asset index and the
/// natives. When it does not, this class says so and says how to fix it, rather than writing a
/// 200 MB game into somebody's live installation on their behalf.
/// </summary>
public sealed class VanillaProvisioner
{
    /// <summary>The game version our Fabric profile inherits from.</summary>
    public const string VanillaVersionId = "1.21.11";

    /// <summary>The Fabric loader version this build pins.</summary>
    public const string FabricLoaderVersion = "0.19.5";

    /// <summary>
    /// The version id we own. Namespaced, obviously ours, and impossible to collide with a Mojang,
    /// Fabric, Forge or NeoForge id - which matters because the official launcher builds its
    /// version list from a directory sweep and would show ours next to theirs with no way to tell
    /// them apart otherwise.
    /// </summary>
    public const string VersionId = "xsoz-fabric-" + FabricLoaderVersion + "-" + VanillaVersionId;

    /// <summary>The profile key we own in the official launcher's store.</summary>
    public const string ProfileKey = "xsoz-client";

    /// <summary>
    /// Our isolated game directory. This is the point of the whole design: the user's live
    /// <c>.minecraft</c> holds <c>saves\</c>, <c>mods\</c>, <c>meteor-client\</c> and
    /// <c>baritone\</c>, and a competitive client must never write a jar into it. Everything we
    /// would otherwise drop into their <c>mods\</c> - mods, config, options, logs, saves - lands
    /// here instead.
    /// </summary>
    public static string IsolatedGameDir => Path.Combine(AppPaths.Instances, "xsoz-" + VanillaVersionId, ".minecraft");

    private readonly DownloadService _net;
    private readonly MinecraftDirectory _minecraft;
    private readonly IGameDirectoryWriter _writer;

    /// <summary>
    /// Creates the provisioner.
    /// <para>
    /// <paramref name="writer"/> is REQUIRED and has no default, and that is the whole point: every
    /// call site in the project names the capability it is being given, so a new one cannot be
    /// written that silently inherits the ability to write. The interactive path gets
    /// <see cref="GameWritePolicy.CreateWriter"/>, which hands back the real writer for an
    /// interactive launch and the no-op sink for every read-only mode.
    /// </para>
    /// </summary>
    public VanillaProvisioner(DownloadService net, IGameDirectoryWriter writer, MinecraftDirectory? minecraft = null)
    {
        _net = net;
        _writer = writer;
        _minecraft = minecraft ?? MinecraftDirectory.Default;
    }

    /// <summary>The game directory being provisioned into.</summary>
    public MinecraftDirectory Minecraft => _minecraft;

    /// <summary>
    /// The writer this provisioner was given. Exposed so a diagnostic run can report what it holds
    /// and, for the no-op sink, count what it was asked to do.
    /// </summary>
    public IGameDirectoryWriter Writer => _writer;

    /// <summary>The black-hole icon the installation shows in the Minecraft Launcher (a data URI), or null.</summary>
    private static string? ProfileIcon()
    {
        try
        {
            using var stream = System.Reflection.Assembly.GetExecutingAssembly().GetManifestResourceStream("XsozProfileIcon.png");
            if (stream is null)
            {
                return null;
            }

            using var buffer = new MemoryStream();
            stream.CopyTo(buffer);
            return "data:image/png;base64," + Convert.ToBase64String(buffer.ToArray());
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>
    /// Mojang's own version JSON and client jar for <paramref name="versionId"/>, written where the
    /// official launcher keeps them (<c>versions\id\id.json</c> and <c>.jar</c>). The launcher
    /// fetches the rest (libraries, sounds) itself the first time the installation is played.
    /// </summary>
    private async Task<List<string>> FetchVanillaVersionAsync(string versionId, CancellationToken ct)
    {
        var written = new List<string>();
        var url = await new MojangApi(_net).FindVersionJsonUrlAsync(versionId, ct).ConfigureAwait(false);
        var json = await _net.GetStringAsync(url, ct).ConfigureAwait(false);
        var dir = Path.Combine(_minecraft.VersionsRoot, versionId);
        _writer.CreateDirectory(dir);
        var jsonPath = _minecraft.VersionJsonPath(versionId);
        using (var doc = JsonDocument.Parse(json))
        {
            if (doc.RootElement.TryGetProperty("downloads", out var dl) && dl.TryGetProperty("client", out var client))
            {
                var jarUrl = client.GetProperty("url").GetString() ?? string.Empty;
                var sha1 = client.TryGetProperty("sha1", out var h) ? h.GetString() : null;
                long size = client.TryGetProperty("size", out var sz) ? sz.GetInt64() : 0;
                var jarPath = _minecraft.VersionJarPath(versionId);
                if (jarUrl.Length > 0 && !File.Exists(jarPath))
                {
                    await _writer.DownloadToAsync(jarUrl, jarPath, size, sha1, ct).ConfigureAwait(false);
                    written.Add(jarPath);
                }
            }
        }

        // the JSON last: the launcher treats a version as present once its JSON exists
        await _writer.WriteTextAsync(jsonPath, json, ct).ConfigureAwait(false);
        written.Add(jsonPath);
        AppLog.Shared.Info($"Vanilla install: fetched Minecraft {versionId} from Mojang into {dir}");
        return written;
    }

    /// <summary>The Fabric profile endpoint. Verified 200 with valid JSON on this machine.</summary>
    public static string FabricProfileUrl =>
        $"https://meta.fabricmc.net/v2/versions/loader/{VanillaVersionId}/{FabricLoaderVersion}/profile/json";

    /// <summary>
    /// The gate every write into the official launcher's directory goes through. Returns false
    /// while the launcher is open. Consulted again immediately before each individual write,
    /// because this run is multi-second and the user can open the launcher in the middle of one.
    /// <para>
    /// This is about the LAUNCHER being open, not about the MODE. It is a second line of defence
    /// behind <see cref="IGameDirectoryWriter.CanWrite"/>, not a replacement for it: the correct
    /// reading of this class is that a read-only run cannot write at all, and that an interactive
    /// run additionally will not write while somebody else is using the same directory.
    /// </para>
    /// </summary>
    public static bool WritesAllowed() => !LauncherProcessWatch.IsOpen(MinecraftDirectory.Default.Root);

    /// <summary>
    /// Both gates, as one answer. False in a read-only process whatever the launcher is doing, and
    /// false in an interactive process while the launcher is open.
    /// </summary>
    private bool WritePermitted() => _writer.CanWrite && (IgnoreRunningLauncher || !LauncherProcessWatch.IsOpen(_minecraft.Root));

    /// <summary>Verification runs into a throwaway folder only: the open launcher isn't using it.</summary>
    public bool IgnoreRunningLauncher { get; init; }

    /// <summary>
    /// Runs the whole provision. Nothing is written until the gate is open, and every write is
    /// re-gated. Idempotent: a second run finds the version already correct, refreshes nothing
    /// under <c>versions\</c> that already matches, and leaves the profile alone if it is already
    /// registered correctly.
    /// </summary>
    public async Task<VanillaProvisionResult> RunAsync(CancellationToken ct)
    {
        var written = new List<string>();

        // ---- the capability gate, before anything at all. Not after the directory check, not after
        // ---- the fetch: FIRST, so a read-only run reads nothing, requests nothing and writes
        // ---- nothing, and the return is reached without a single disk or network operation. The
        // ---- writer is the no-op sink in a read-only process, so this is not a check somebody can
        // ---- forget to perform - it is the object refusing.
        if (!_writer.CanWrite)
        {
            // Deliberately not GameWritePolicy.ProvisioningSkippedLine: that sentence is printed
            // once, by the capture, so that "provisioning was skipped" appears exactly one time in
            // a run's output rather than once per caller. This line is the launcher's own log
            // saying which object refused and what it was protecting.
            AppLog.Shared.Info(
                $"Vanilla provisioning refused: this process is read-only ({GameWritePolicy.Reason}); "
                + $"the writer is '{_writer.Name}'. Target {_minecraft.Root} was not touched.");

            return new VanillaProvisionResult
            {
                Succeeded = false,
                ReadOnlyMode = true,
                Message = "This process is read-only for the official launcher's directory, so "
                          + $"nothing was written into {_minecraft.Root}.",
                WrittenPaths = written,
            };
        }

        if (!_minecraft.Exists)
        {
            return new VanillaProvisionResult
            {
                Succeeded = false,
                MissingMinecraftDirectory = true,
                Message = _minecraft.MissingDirectorySentence,
            };
        }

        // ---- the launcher-running gate, before anything at all.
        if (!WritePermitted())
        {
            return Refused(written);
        }

        // ---- Fabric. Fetched, never hand-rolled.
        string fabricJson;
        try
        {
            fabricJson = await _net.GetStringAsync(FabricProfileUrl, ct).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            return new VanillaProvisionResult
            {
                Succeeded = false,
                Message = "Fabric's meta service could not be reached: " + ex.Message + " Nothing was written.",
                WrittenPaths = written,
            };
        }

        using (var fabricProbe = JsonDocument.Parse(fabricJson))
        {
            var inheritsFrom = fabricProbe.RootElement.TryGetProperty("inheritsFrom", out var i)
                ? i.GetString() ?? string.Empty
                : string.Empty;

            // Never played this Minecraft version in the official launcher? Fetch it from Mojang the
            // way the launcher itself would (same files, same place), so a brand-new user needs
            // nothing else.
            if (inheritsFrom.Length > 0 && !_minecraft.HasVersion(inheritsFrom))
            {
                try
                {
                    if (!WritePermitted())
                    {
                        return Refused(written);
                    }

                    written.AddRange(await FetchVanillaVersionAsync(inheritsFrom, ct).ConfigureAwait(false));
                }
                catch (OperationCanceledException)
                {
                    throw;
                }
                catch (Exception ex)
                {
                    AppLog.Shared.Warn($"Vanilla install: could not fetch Minecraft {inheritsFrom}: {ex.Message}");
                }
            }

            if (inheritsFrom.Length > 0 && !_minecraft.HasVersion(inheritsFrom))
            {
                return new VanillaProvisionResult
                {
                    Succeeded = false,
                    MissingInheritedVersion = inheritsFrom,
                    Message = $"Fabric's profile inherits from Minecraft {inheritsFrom}, and the official launcher does not "
                              + $"have {inheritsFrom} installed. Open the Minecraft Launcher, install {inheritsFrom} once, "
                              + "then choose Re-check. Nothing was written.",
                    WrittenPaths = written,
                };
            }

            // Mojang's own JVM arguments, read from the installed vanilla version JSON purely so the
            // merge can be asserted against them.
            var vanillaJson = _minecraft.VersionJsonPath(inheritsFrom);
            using var vanillaDoc = JsonDocument.Parse(
                await File.ReadAllTextAsync(vanillaJson, ct).ConfigureAwait(false),
                new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip });

            var merged = JvmFlagMerge.Merge(fabricJson, vanillaDoc.RootElement, VersionId, IsolatedGameDir, out var jvmReport);
            if (merged is null)
            {
                return new VanillaProvisionResult
                {
                    Succeeded = false,
                    Message = jvmReport.Message,
                    Jvm = jvmReport,
                    WrittenPaths = written,
                };
            }

            AppLog.Shared.Info(
                $"Vanilla install: Fabric {FabricLoaderVersion} for {VanillaVersionId} resolves to '{inheritsFrom}' with main class "
                + $"{jvmReport.MainClass}. {jvmReport.Message}");

            // ---- write 1: the version JSON. Guarded again, immediately.
            if (!WritePermitted())
            {
                return Refused(written);
            }

            var versionDir = Path.Combine(_minecraft.VersionsRoot, VersionId);
            _writer.CreateDirectory(versionDir);
            var versionJsonPath = Path.Combine(versionDir, VersionId + ".json");
            await _writer.WriteTextAsync(versionJsonPath, merged, ct).ConfigureAwait(false);
            written.Add(versionJsonPath);
            AppLog.Shared.Info($"Vanilla install: wrote {versionJsonPath}");

            // ---- write 2: the loader and intermediary jars, at the paths the fetched JSON names.
            foreach (var item in FabricLibraryPlan(merged, _minecraft.LibrariesRoot))
            {
                ct.ThrowIfCancellationRequested();
                if (!WritePermitted())
                {
                    return Refused(written);
                }

                _writer.CreateDirectory(Path.GetDirectoryName(item.DestinationPath)!);
                await _writer.DownloadToAsync(item.Url, item.DestinationPath, item.SizeBytes, item.Sha1Hex, ct).ConfigureAwait(false);
                written.Add(item.DestinationPath);
                AppLog.Shared.Info($"Vanilla install: {(item.AlreadyPresent ? "verified" : "fetched")} {item.DestinationPath}");
            }

            // ---- write 3: the version jar, a copy of the vanilla client jar.
            var vanillaClientJar = _minecraft.VersionJarPath(inheritsFrom);
            var versionJarPath = _minecraft.VersionJarPath(VersionId);
            if (!File.Exists(versionJarPath) && File.Exists(vanillaClientJar))
            {
                if (!WritePermitted())
                {
                    return Refused(written);
                }

                _writer.CopyFile(vanillaClientJar, versionJarPath);
                written.Add(versionJarPath);
                AppLog.Shared.Info($"Vanilla install: copied the vanilla client jar to {versionJarPath}");
            }

            // ---- our isolated tree. Entirely under %LOCALAPPDATA%, so the launcher-running gate
            // ---- does not apply: the official launcher is not reading or writing any of it while
            // ---- it is closed or open. The writer still does, because "read-only" in this product
            // ---- means read-only everywhere, and a capture must not create a game directory either.
            written.AddRange(await CreateIsolatedTreeAsync(ct).ConfigureAwait(false));

            // ---- the in-game client itself, into the isolated mods folder.
            var modJar = await XsozModPayload.InstallAsync(_writer, IsolatedGameDir, ct).ConfigureAwait(false);
            if (modJar is not null)
            {
                written.Add(modJar);
            }

            // ---- write 4: the profile. The gate is consulted inside the merge, twice.
            var outcome = LauncherProfilesWriter.Merge(
                _minecraft.ProfilesFilePath,
                new VanillaProfileSpec(
                    ProfileKey,
                    "Xsoz Client",
                    VersionId,
                    IsolatedGameDir,
                    DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:sszzz", System.Globalization.CultureInfo.InvariantCulture),
                    ProfileIcon()),
                WritePermitted,
                AppPaths.Backups);

            if (outcome.BackupPath is not null)
            {
                written.Add(outcome.BackupPath);
            }

            // ---- verify what we claim. Re-read from disk, not from memory.
            var verified = VerifyOnDisk(out var verificationLine);

            AppLog.Shared.Info($"Vanilla install: {verificationLine} {outcome.Message}");
            var message = outcome.Succeeded
                ? "Xsoz Client is installed. It's now one of your Minecraft versions in the official Minecraft "
                  + "Launcher: open the Minecraft Launcher, click the version picker next to the green Play button, "
                  + "choose 'Xsoz Client', and press Play."
                : outcome.Message;

            if (outcome.Succeeded && !verified)
            {
                message = "The installation was written but did not verify on disk. "
                          + "Re-check, and use Restore in Settings if the launcher complains about its profile list.";
            }

            return new VanillaProvisionResult
            {
                Succeeded = outcome.Succeeded && verified,
                Message = message,
                Profile = outcome,
                Jvm = jvmReport,
                WrittenPaths = written,
            };
        }
    }

    /// <summary>
    /// Creates our own game directory tree and writes the mod layer into it. Never touches the
    /// user's <c>mods\</c> folder, and never touches anything at all outside
    /// <c>%LOCALAPPDATA%\XsozClient</c>.
    /// <para>
    /// Every call goes through the writer, even though nothing here is near the official
    /// launcher's directory. "Read-only" in this product means the run writes nothing anywhere, and
    /// the cheapest way to keep that promise honest is that there is no second, unguarded way to
    /// create a directory.
    /// </para>
    /// </summary>
    private async Task<IReadOnlyList<string>> CreateIsolatedTreeAsync(CancellationToken ct)
    {
        var created = new List<string>();
        foreach (var relative in new[] { "mods", "config", "saves", "logs", "resourcepacks", "shaderpacks", "config/xsozclient" })
        {
            var path = Path.Combine(IsolatedGameDir, relative.Replace('/', Path.DirectorySeparatorChar));
            _writer.CreateDirectory(path);
            created.Add(path);
        }

        var readme = Path.Combine(IsolatedGameDir, "README.txt");
        if (!File.Exists(readme))
        {
            await _writer.WriteTextAsync(readme, IsolatedDirectoryNote(), ct).ConfigureAwait(false);
            created.Add(readme);
        }

        return created;
    }

    private static string IsolatedDirectoryNote() =>
        "XsozClient isolated instance" + Environment.NewLine
        + "================================" + Environment.NewLine + Environment.NewLine
        + "This is the game directory the 'xsoz-client' installation in the official Minecraft" + Environment.NewLine
        + "Launcher uses. It is separate from " + MinecraftDirectory.Default.Root + Environment.NewLine
        + "on purpose." + Environment.NewLine + Environment.NewLine
        + "Your own saves, mods, options and configuration live under the .minecraft folder and" + Environment.NewLine
        + "are never read or written by this client. Everything this client needs at runtime - the" + Environment.NewLine
        + "mod layer, its configuration, and any world you start here - lives in this folder." + Environment.NewLine
        + Environment.NewLine
        + "Nothing here is signed in anywhere. The official launcher owns the account; see the" + Environment.NewLine
        + "launcher's own sign-in. This client reads no credentials of any kind." + Environment.NewLine;

    /// <summary>One library file the fetched version JSON asks for, resolved to a path.</summary>
    private sealed record FabricLibraryItem(string Url, string DestinationPath, long SizeBytes, string? Sha1Hex, bool AlreadyPresent);

    /// <summary>
    /// Resolves the version JSON's own libraries entries into concrete downloads. The Maven
    /// coordinate is turned into a path by the same rule every launcher uses, and the destination
    /// is <c>libraries\</c> - not the version folder. Fabric's loader jar living in the version
    /// folder is the single most common reason a hand-installed Fabric profile shows a broken
    /// installation in the official launcher.
    /// </summary>
    private static IReadOnlyList<FabricLibraryItem> FabricLibraryPlan(string versionJson, string librariesRoot)
    {
        var items = new List<FabricLibraryItem>();
        using var doc = JsonDocument.Parse(versionJson);
        if (!doc.RootElement.TryGetProperty("libraries", out var libraries))
        {
            return items;
        }

        foreach (var library in libraries.EnumerateArray())
        {
            if (!library.TryGetProperty("name", out var nameNode))
            {
                continue;
            }

            var path = FabricMeta.MavenPath(nameNode.GetString() ?? string.Empty);
            if (path is null)
            {
                continue;
            }

            var baseUrl = library.TryGetProperty("url", out var url) ? url.GetString() : null;
            baseUrl = string.IsNullOrWhiteSpace(baseUrl) ? "https://maven.fabricmc.net/" : baseUrl;

            var destination = Path.Combine(librariesRoot, path.Replace('/', Path.DirectorySeparatorChar));
            var size = library.TryGetProperty("size", out var s) && s.TryGetInt64(out var sizeValue) ? sizeValue : -1;
            var sha1 = library.TryGetProperty("sha1", out var h) ? h.GetString() : null;

            items.Add(new FabricLibraryItem(
                baseUrl.TrimEnd('/') + "/" + path,
                destination,
                size,
                sha1,
                File.Exists(destination)));
        }

        return items;
    }

    /// <summary>
    /// Re-reads from disk and checks the three facts the interface is about to assert: our version
    /// folder exists and carries the merged flags, our profile exists, and it points at the isolated
    /// game directory. The isolation is the one claim that must never be made on the strength of
    /// intent alone.
    /// </summary>
    private bool VerifyOnDisk(out string line)
    {
        try
        {
            var versionJsonPath = _minecraft.VersionJsonPath(VersionId);
            if (!File.Exists(versionJsonPath))
            {
                line = "The version definition is not on disk.";
                return false;
            }

            var text = File.ReadAllText(versionJsonPath);
            var flagsPresent = JvmFlagMerge.BuildInjectedFlags().All(f => text.Contains(f, StringComparison.Ordinal));
            var classpathSafe = text.Contains(JvmFlagMerge.FabricMainClass, StringComparison.Ordinal);

            if (LauncherProfilesWriter.ReadProfiles(_minecraft.ProfilesFilePath, out var profiles) < 0)
            {
                line = "The launcher's profile file could not be read back for verification.";
                return false;
            }

            if (!profiles.TryGetValue(ProfileKey, out var ours))
            {
                line = "The 'xsoz-client' installation is not registered in the launcher's profile list.";
                return false;
            }

            using var oursDoc = JsonDocument.Parse(ours);
            var profile = oursDoc.RootElement;

            var gameDirOk = profile.TryGetProperty("gameDir", out var gameDir)
                            && string.Equals(Path.GetFullPath(gameDir.GetString() ?? string.Empty),
                                Path.GetFullPath(IsolatedGameDir),
                                StringComparison.OrdinalIgnoreCase);

            var versionIdOk = profile.TryGetProperty("lastVersionId", out var lastVersionId)
                              && string.Equals(lastVersionId.GetString(), VersionId, StringComparison.Ordinal);

            var typeOk = profile.TryGetProperty("type", out var type)
                         && string.Equals(type.GetString(), "custom", StringComparison.Ordinal);

            // Heap sizing must come from the profile, and the version JSON must not carry an -Xms
            // the launcher's appended -Xmx could undercut (that was the exit-code-1 crash).
            var heapOk = !text.Contains("\"-Xms", StringComparison.Ordinal)
                         && profile.TryGetProperty("javaArgs", out var javaArgs)
                         && (javaArgs.GetString() ?? string.Empty).Contains("-Xmx", StringComparison.Ordinal);

            if (flagsPresent && classpathSafe && gameDirOk && versionIdOk && typeOk && heapOk)
            {
                line = $"Verified on disk: version '{VersionId}', main class intact, "
                       + $"{JvmFlagMerge.BuildInjectedFlags().Count} JVM flags present, "
                       + $"gameDir isolated to {IsolatedGameDir}.";
                return true;
            }

            line = "The installation was written but did not verify on disk "
                   + $"(flags {(flagsPresent ? "ok" : "missing")}, main class {(classpathSafe ? "ok" : "wrong")}, "
                   + $"gameDir {(gameDirOk ? "ok" : "not isolated")}, versionId {(versionIdOk ? "ok" : "wrong")}, "
                   + $"type {(typeOk ? "ok" : "wrong")}, heap {(heapOk ? "ok" : "wrong")}).";
            return false;
        }
        catch (Exception ex)
        {
            line = "Verification could not complete: " + ex.Message;
            return false;
        }
    }

    /// <summary>The refusal. One calm line, nothing written, a re-check offered by the caller.</summary>
    private static VanillaProvisionResult Refused(IReadOnlyList<string> written)
    {
        var hits = LauncherProcessWatch.Find(MinecraftDirectory.Default.Root);
        var detail = LauncherProcessWatch.Describe(MinecraftDirectory.Default.Root, hits);
        AppLog.Shared.Warn(detail + " Refusing to write into the official launcher's directory.");
        return new VanillaProvisionResult
        {
            Succeeded = false,
            BlockedByRunningLauncher = true,
            Message = LauncherProcessWatch.RefusalMessage,
            WrittenPaths = written,
        };
    }
}