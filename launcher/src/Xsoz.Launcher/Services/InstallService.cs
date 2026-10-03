using System.Collections.Concurrent;
using System.IO.Compression;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Models;

namespace Xsoz.Launcher.Services;

/// <summary>The five install steps, in order.</summary>
public enum InstallStage
{
    /// <summary>Nothing has happened yet.</summary>
    NotStarted,

    /// <summary>Provisioning the per-version JRE 21 from Adoptium.</summary>
    Java,

    /// <summary>The vanilla client jar, rule-evaluated libraries, windows natives and assets.</summary>
    GameFiles,

    /// <summary>Resolving the Fabric loader and writing the instance version profile.</summary>
    FabricLoader,

    /// <summary>The mod layer from Modrinth, licence-read-first.</summary>
    ModStack,

    /// <summary>options.txt defaults and the JVM flag template.</summary>
    Config,

    /// <summary>Everything verified. This state is what makes the button read PLAY.</summary>
    Done,
}

/// <summary>A progress snapshot, raised from a worker thread and safe to read on any thread.</summary>
public sealed class InstallTick
{
    /// <summary>Current stage.</summary>
    public InstallStage Stage { get; init; }

    /// <summary>Stage title for the header line.</summary>
    public string StageTitle { get; init; } = string.Empty;

    /// <summary>Current file name, or the current sub-action.</summary>
    public string CurrentFile { get; init; } = string.Empty;

    /// <summary>Rolling log line.</summary>
    public string LogLine { get; init; } = string.Empty;

    /// <summary>Bytes received across the run so far.</summary>
    public long ReceivedBytes { get; init; }

    /// <summary>Total expected bytes across the run, when known. -1 when not.</summary>
    public long TotalBytes { get; init; } = -1;

    /// <summary>Current rolling transfer rate.</summary>
    public double BytesPerSecond { get; init; }

    /// <summary>0..1 across the whole install.</summary>
    public double OverallFraction { get; init; }

    /// <summary>Estimated seconds remaining, when the total is known. Null otherwise.</summary>
    public double? EtaSeconds { get; init; }
}

/// <summary>The end state of an install run.</summary>
public sealed class InstallResult
{
    /// <summary>True when every step completed and verified.</summary>
    public bool Succeeded { get; init; }

    /// <summary>True when the user cancelled. Partial files were cleaned up.</summary>
    public bool Cancelled { get; init; }

    /// <summary>Human-readable failure, never a stack trace.</summary>
    public string ErrorMessage { get; init; } = string.Empty;

    /// <summary>Total bytes downloaded in this run.</summary>
    public long TotalBytesReceived { get; init; }

    /// <summary>How the log described the result.</summary>
    public string Summary { get; init; } = string.Empty;
}

/// <summary>
/// The install engine. One instance, five steps, always idempotent: every completed file is
/// hash-verified before it is counted as present, so re-running after a failure downloads only
/// what is actually missing, and the finished state is checked by files on disk, never by a flag
/// file the launcher owns.
///
/// Layout on disk (all under %LOCALAPPDATA%\XsozClient):
///   instances\Xsoz-1.21.11\.minecraft\   ← the game root
///     versions\1.21.11\1.21.11.json + .jar
///     versions\Xsoz-1.21.11\Xsoz-1.21.11.json   ← the Fabric profile, written by meta
///     libraries\… assets\… natives\xsoz-1.21.11\… mods\… config\ options.txt
///   runtimes\21\…                          ← the Temurin JRE 21 the game runs on
/// </summary>
public sealed class InstallService
{
    /// <summary>The version id of the instance this launcher builds and launches.</summary>
    public const string InstanceVersionId = "Xsoz-1.21.11";

    /// <summary>
    /// Self-test seam: when set, forwarded to <see cref="MojangApi.ManifestUrlOverride"/> at the
    /// start of a run. The interactive app never sets it.
    /// </summary>
    public static string? ManifestUrlOverride { get; set; }

    /// <summary>The vanilla game version the instance is built on.</summary>
    public const string VanillaVersionId = "1.21.11";

    /// <summary>The Java feature release the game version requires.</summary>
    public const int RequiredJavaMajor = 21;

    private readonly DownloadService _net;

    /// <summary>Creates the engine.</summary>
    public InstallService(DownloadService net) => _net = net;

    /// <summary>The instance root the launcher manages.</summary>
    public string InstanceRoot => Path.Combine(AppPaths.Instances, InstanceVersionId);

    /// <summary>The game root inside it - the directory the JVM is handed as --gameDir.</summary>
    public string GameRoot => Path.Combine(InstanceRoot, ".minecraft");

    /// <summary>The runtime root for this version's Java.</summary>
    public string RuntimeRoot => Path.Combine(AppPaths.Runtimes, RequiredJavaMajor.ToString(System.Globalization.CultureInfo.InvariantCulture));

    /// <summary>Progress while a run is active. Raised from a worker thread.</summary>
    public event EventHandler<InstallTick>? Tick;

    /// <summary>True when the instance directory contains everything a launch needs.</summary>
    public bool IsInstalled()
    {
        try
        {
            if (!JavaProvisioner.IsProvisioned(RuntimeRoot))
            {
                return false;
            }

            var profileJson = Path.Combine(GameRoot, "versions", InstanceVersionId, InstanceVersionId + ".json");
            if (!File.Exists(profileJson))
            {
                return false;
            }

            var text = File.ReadAllText(profileJson);
            if (!text.Contains("net.fabricmc.loader.impl.launch.knot", StringComparison.Ordinal)
                || !text.Contains("fabric-loader", StringComparison.Ordinal))
            {
                return false;
            }

            var clientJar = Path.Combine(GameRoot, "versions", VanillaVersionId, VanillaVersionId + ".jar");
            if (!File.Exists(clientJar))
            {
                return false;
            }

            var modsDir = Path.Combine(GameRoot, "mods");
            if (!Directory.Exists(modsDir)
                || Directory.EnumerateFiles(modsDir, "*.jar").Count() < ModrinthApi.Stack.Count)
            {
                return false;
            }

            // The vanilla version JSON has to be readable AND has to actually declare the client
            // download. A truncated one parses but cannot produce a launch, and the launch path is
            // where that would otherwise be discovered - after the button says PLAY.
            var vanillaJson = Path.Combine(GameRoot, "versions", VanillaVersionId, VanillaVersionId + ".json");
            if (!File.Exists(vanillaJson))
            {
                return false;
            }

            using (var vanilla = JsonDocument.Parse(File.ReadAllText(vanillaJson)))
            {
                if (!vanilla.RootElement.TryGetProperty("downloads", out var downloads)
                    || !downloads.TryGetProperty("client", out _))
                {
                    return false;
                }
            }

            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>
    /// Runs the whole install. Progress through <see cref="Tick"/>; failures return as a result,
    /// never as an exception. Cancellation deletes only <c>.part</c> files — every completed file
    /// is hash-verified and kept, which is precisely what makes the next run cheap.
    /// </summary>
    public async Task<InstallResult> RunAsync(CancellationToken ct)
    {
        var stopwatch = System.Diagnostics.Stopwatch.StartNew();
        var tracker = new ProgressTracker(this);
        MojangApi.ManifestUrlOverride = ManifestUrlOverride;

        try
        {
            tracker.SetStage(InstallStage.Java, "Java 21 runtime", "Checking the provisioned JRE");
            var java = await EnsureJavaAsync(tracker, ct).ConfigureAwait(false);
            tracker.Log("Java: " + java);

            // Game files: resolve the version JSON, then plan the full download.
            tracker.SetStage(InstallStage.GameFiles, "Game files", "Reading Mojang's version manifest");
            var mojang = new MojangApi(_net);
            var versionJsonUrl = await mojang.FindVersionJsonUrlAsync(VanillaVersionId, ct).ConfigureAwait(false);
            var (version, gameItems) = await mojang.PlanGameDownloadAsync(VanillaVersionId, versionJsonUrl, GameRoot, ct).ConfigureAwait(false);

            tracker.SetExpectedTotal(gameItems, knownWeightShare: 0.02, shareLabel: "manifests");
            tracker.Log($"Mojang metadata for {VanillaVersionId}: {gameItems.Count} files resolved.");
            await DownloadAllAsync(gameItems, tracker, maxConcurrent: 8, ct).ConfigureAwait(false);
            tracker.Log($"Game files verified on disk: {gameItems.Count} files, {version.LibraryPaths.Count} classpath entries.");

            // Fabric: resolve the current stable loader, write the instance profile, fetch its
            // libraries from maven.fabricmc.net.
            tracker.SetStage(InstallStage.FabricLoader, "Fabric loader", "Asking meta.fabricmc.net for the current stable loader");
            var fabric = new FabricMeta(_net);
            var (loaderVersion, _, _) = await fabric.ResolveLoaderAsync(VanillaVersionId, ct).ConfigureAwait(false);
            tracker.Log($"Fabric meta reports loader {loaderVersion} as stable for {VanillaVersionId}.");
            var (_, profileJson) = await fabric.FetchProfileAsync(VanillaVersionId, loaderVersion, InstanceVersionId, GameRoot, ct).ConfigureAwait(false);
            var fabricItems = FabricMeta.PlanFabricLibraries(profileJson, GameRoot, "Fabric loader");
            tracker.SetExpectedTotal(fabricItems, knownWeightShare: 0.0, shareLabel: "fabric");
            await DownloadAllAsync(fabricItems, tracker, maxConcurrent: 4, ct).ConfigureAwait(false);

            var profilePath = Path.Combine(GameRoot, "versions", InstanceVersionId, InstanceVersionId + ".json");
            tracker.Log(File.Exists(profilePath)
                ? $"Instance version written: versions\\{InstanceVersionId}\\{InstanceVersionId}.json (loader {loaderVersion})."
                : "The instance version JSON did not land where expected.");

            // The stack: licence is read BEFORE anything downloads, and written into the
            // instance README alongside the note that the user's machine fetched the bytes.
            tracker.SetStage(InstallStage.ModStack, "Mod layer", "Reading licences and resolving builds on Modrinth");
            var mods = await ResolveModsAsync(tracker, ct).ConfigureAwait(false);
            tracker.SetExpectedTotal(mods.SelectMany(m => m.Items).ToList(), 0.0, "mods");
            await DownloadAllAsync(mods.SelectMany(m => m.Items).ToList(), tracker, maxConcurrent: 3, ct).ConfigureAwait(false);
            await WriteReadmeAsync(mods.Select(m => m.Resolved).ToList(), ct).ConfigureAwait(false);
            XsozModPayload.InstallDirect(Path.Combine(GameRoot, "mods"));

            // Config: the options template and the managed block inside options.txt.
            tracker.SetStage(InstallStage.Config, "Config", "Writing vanilla defaults and the JVM flag template");
            await GameConfigWriter.ApplyDefaultsAsync(GameRoot, ct).ConfigureAwait(false);
            tracker.Log("options.txt written: FOV 90, vsync on, particles minimal, brightness 1.0, render distance 8.");

            tracker.SetStage(InstallStage.Done, "Done", string.Empty);
            var sizeLine = FinalSizeLine();
            AppLog.Shared.Success($"Install complete in {stopwatch.Elapsed:mm\\:ss}. {sizeLine}");
            return new InstallResult
            {
                Succeeded = true,
                TotalBytesReceived = tracker.TotalReceived,
                Summary = $"Installed {VanillaVersionId} + Fabric {loaderVersion} with {mods.Count} mods. {sizeLine}",
            };
        }
        catch (OperationCanceledException)
        {
            CleanPartials();
            AppLog.Shared.Warn("Install cancelled by the user. Partial downloads were deleted; completed files were kept.");
            return new InstallResult
            {
                Cancelled = true,
                TotalBytesReceived = tracker.TotalReceived,
                Summary = "Cancelled. Nothing was left half-written; re-downloading resumes where completed files already verify.",
            };
        }
        catch (Exception ex)
        {
            CleanPartials();
            var message = Humanize(ex);
            AppLog.Shared.Error("Install failed: " + message);
            return new InstallResult
            {
                ErrorMessage = message,
                TotalBytesReceived = tracker.TotalReceived,
                Summary = "Install failed: " + message,
            };
        }
    }

    private async Task<string> EnsureJavaAsync(ProgressTracker tracker, CancellationToken ct)
    {
        var provisioner = new JavaProvisioner(_net);
        return await provisioner.EnsureJava21Async(
            RuntimeRoot,
            p => tracker.ReportItem(p.Url, p.TotalBytes, p),
            line => tracker.Log(line),
            ct).ConfigureAwait(false);
    }

    private async Task<List<(ResolvedMod Resolved, List<DownloadItem> Items)>> ResolveModsAsync(ProgressTracker tracker, CancellationToken ct)
    {
        var api = new ModrinthApi(_net);
        var modsDir = Path.Combine(GameRoot, "mods");
        Directory.CreateDirectory(modsDir);

        var results = new List<(ResolvedMod Resolved, List<DownloadItem> Items)>();
        foreach (var definition in ModrinthApi.Stack)
        {
            ct.ThrowIfCancellationRequested();
            tracker.SetFile(definition.Slug + " (Modrinth)");
            var resolved = await api.ResolveAsync(definition.Slug, VanillaVersionId, ct).ConfigureAwait(false);
            tracker.Log(
                $"{resolved.Title} {resolved.VersionNumber} — licence: {resolved.LicenceId}"
                + (resolved.UsedFallbackVersion ? $" (no 1.21.11 build; using the {resolved.ResolvedGameVersion} build)" : string.Empty));

            results.Add((resolved,
            [
                new DownloadItem(
                    resolved.Url,
                    Path.Combine(modsDir, resolved.FileName),
                    resolved.SizeBytes,
                    resolved.Sha1Hex.Length > 0 ? resolved.Sha1Hex : null,
                    "Mod layer"),
            ]));
        }

        return results;
    }

    private async Task WriteReadmeAsync(IReadOnlyList<ResolvedMod> mods, CancellationToken ct)
    {
        var text = new StringBuilder();
        text.AppendLine("XsozClient instance — what is in this folder");
        text.AppendLine("================================================");
        text.AppendLine();
        text.AppendLine("This instance was assembled by the XsozClient launcher on " + DateTime.Now.ToString("yyyy-MM-dd") + ".");
        text.AppendLine("Every file inside it was downloaded from Mojang, Fabric or Modrinth directly to this");
        text.AppendLine("machine. The launcher does not redistribute any of it; the download was yours.");
        text.AppendLine();
        text.AppendLine("The mod layer, with the licence each project declares on its Modrinth page (read before");
        text.AppendLine("any download happened):");
        foreach (var mod in mods)
        {
            var note = mod.UsedFallbackVersion
                ? $" — no 1.21.11 build exists; the {mod.ResolvedGameVersion} build is installed"
                : string.Empty;
            text.AppendLine($"  {mod.Title} {mod.VersionNumber} — {mod.LicenceId}{note}");
            if (mod.LicenceUrl.Length > 0)
            {
                text.AppendLine($"    {mod.LicenceUrl}");
            }
        }

        text.AppendLine();
        text.AppendLine("Sodium's licence (PolyForm Shield 1.0.0) forbids bundling its jar into a competing");
        text.AppendLine("client, which is exactly why it arrived here by your own download rather than inside");
        text.AppendLine("the launcher's package. Lithium, ImmediatelyFast and Cloth Config are LGPL; the licence");
        text.AppendLine("binds the person it reaches, and no combined-work obligations arise from a launcher that");
        text.AppendLine("never ships the bytes.");
        text.AppendLine();
        text.AppendLine("Flashback and NPC Studio are All Rights Reserved. Redistribution of either is not");
        text.AppendLine("permitted under any terms, so both are fetched from Modrinth by this launcher to this");
        text.AppendLine("folder and are never bundled, mirrored or embedded in the launcher's own package.");
        text.AppendLine();
        text.AppendLine("The game files under versions\\ belong to Mojang AB and are governed by the Minecraft");
        text.AppendLine("EULA. They are not the launcher's to give you, which is why they came from Mojang.");

        await File.WriteAllTextAsync(Path.Combine(GameRoot, "README.txt"), text.ToString(), ct).ConfigureAwait(false);
    }

    private string FinalSizeLine()
    {
        try
        {
            long total = 0;
            foreach (var file in Directory.EnumerateFiles(InstanceRoot, "*", SearchOption.AllDirectories))
            {
                total += new FileInfo(file).Length;
            }

            return $"{total / 1024 / 1024} MB on disk at {InstanceRoot}";
        }
        catch (Exception)
        {
            return string.Empty;
        }
    }

    /// <summary>Deletes .part files under the instance. Completed, hash-verified files stay.</summary>
    private void CleanPartials()
    {
        try
        {
            foreach (var part in Directory.EnumerateFiles(InstanceRoot, "*.part", SearchOption.AllDirectories))
            {
                DownloadService.TryDelete(part);
            }

            foreach (var part in Directory.EnumerateFiles(RuntimeRoot, "*.part", SearchOption.AllDirectories))
            {
                DownloadService.TryDelete(part);
            }
        }
        catch (Exception)
        {
            // Cleanup failure is not a crash.
        }
    }

    private async Task DownloadAllAsync(List<DownloadItem> items, ProgressTracker tracker, int maxConcurrent, CancellationToken ct)
    {
        if (items.Count == 0)
        {
            return;
        }

        using var gate = new SemaphoreSlim(maxConcurrent);
        var pending = items.Where(i => !DownloadService.IsCompleteAsync(i.DestinationPath, i.SizeBytes, i.Sha1Hex, ct).Result).ToList();
        tracker.Log($"{items.Count - pending.Count} of {items.Count} files already verified; {pending.Count} to download.");

        await Task.WhenAll(pending.Select(async item =>
        {
            await gate.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                await _net.DownloadAsync(
                    item.Url, item.DestinationPath, item.SizeBytes, item.Sha1Hex,
                    p => tracker.ReportItem(item.DestinationPath, item.SizeBytes, p),
                    ct).ConfigureAwait(false);

                // A natives jar is also unpacked for the game to load its DLLs.
                if (item.NativesJar && File.Exists(item.DestinationPath))
                {
                    await ExtractNativeJarAsync(item.DestinationPath, Path.Combine(GameRoot, "natives", InstanceVersionId.ToLowerInvariant()), ct).ConfigureAwait(false);
                }
            }
            finally
            {
                gate.Release();
            }
        })).ConfigureAwait(false);
    }

    /// <summary>
    /// Extracts a natives jar into the natives directory. This is what the game actually loads;
    /// Mojang's own launcher does the identical extract, for the identical reason.
    /// </summary>
    private static Task ExtractNativeJarAsync(string jarPath, string nativesDir, CancellationToken ct)
    {
        return Task.Run(() =>
        {
            Directory.CreateDirectory(nativesDir);
            using var zip = System.IO.Compression.ZipFile.OpenRead(jarPath);
            foreach (var entry in zip.Entries)
            {
                ct.ThrowIfCancellationRequested();
                if (string.IsNullOrEmpty(entry.Name)
                    || entry.FullName.StartsWith("META-INF", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                var dest = Path.Combine(nativesDir, entry.FullName.Replace('/', Path.DirectorySeparatorChar));
                if (!AppPaths.IsInside(nativesDir, dest))
                {
                    continue;
                }

                Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                entry.ExtractToFile(dest, overwrite: true);
            }
        }, ct);
    }

    /// <summary>Turns an exception into a sentence the user can act on.</summary>
    private static string Humanize(Exception ex) => ex switch
    {
        HttpRequestException http when http.StatusCode is { } code =>
            $"The server answered with HTTP {(int)code}. {ex.Message} Check your connection and try again; nothing was left half-written.",
        HttpRequestException =>
            $"The download could not reach its server. {ex.Message} Check your connection and try again.",
        InvalidDataException data => data.Message,
        IOException io => "A file could not be written: " + io.Message,
        _ => ex.Message,
    };

    /// <summary>
    /// Rolls the per-file transfer callbacks into the install-wide tick. Thread-safe: downloads
    /// run in parallel, so all mutation happens under a gate.
    /// </summary>
    private sealed class ProgressTracker
    {
        private readonly InstallService _owner;
        private readonly object _gate = new();
        private InstallStage _stage = InstallStage.NotStarted;
        private string _stageTitle = "Starting";
        private string _currentFile = string.Empty;
        private string _logLine = string.Empty;
        private long _receivedTotal;
        private long _expectedTotal = -1;
        private double _bytesPerSecond;
        private readonly Dictionary<string, long> _receivedByKey = new(StringComparer.Ordinal);
        private readonly Dictionary<string, long> _expectedByKey = new(StringComparer.Ordinal);
        private readonly System.Diagnostics.Stopwatch _clock = System.Diagnostics.Stopwatch.StartNew();

        public ProgressTracker(InstallService owner) => _owner = owner;

        public long TotalReceived
        {
            get { lock (_gate) { return _receivedTotal; } }
        }

        public void SetStage(InstallStage stage, string title, string log)
        {
            lock (_gate)
            {
                _stage = stage;
                _stageTitle = title;
                _currentFile = string.Empty;
                if (log.Length > 0)
                {
                    _logLine = log;
                }
            }

            Raise();
        }

        public void SetFile(string name)
        {
            lock (_gate)
            {
                _currentFile = name;
            }

            Raise();
        }

        public void Log(string line)
        {
            lock (_gate)
            {
                _logLine = line;
            }

            AppLog.Shared.Info(line);
            Raise();
        }

        public void SetExpectedTotal(List<DownloadItem> items, double knownWeightShare, string shareLabel)
        {
            lock (_gate)
            {
                foreach (var item in items)
                {
                    _expectedByKey[item.DestinationPath] = item.SizeBytes;
                }

                _expectedTotal = _expectedByKey.Count == 0 ? -1 : _expectedByKey.Values.Sum();
            }
        }

        public void ReportItem(string key, long expected, TransferProgress p)
        {
            lock (_gate)
            {
                _receivedByKey[key] = p.ReceivedBytes;
                var known = expected > 0 ? expected : p.TotalBytes;
                if (known > 0)
                {
                    _expectedByKey[key] = known;
                }

                _receivedTotal = _receivedByKey.Values.Sum();
                _expectedTotal = _expectedByKey.Values.Sum(v => Math.Max(v, 0));
                if (_expectedTotal <= 0)
                {
                    _expectedTotal = -1;
                }

                _bytesPerSecond = p.BytesPerSecond > 0 ? p.BytesPerSecond : _bytesPerSecond;
                _currentFile = p.FileName;
            }

            Raise();
        }

        private void Raise()
        {
            double fraction;
            double? eta = null;
            long expected;
            long received;
            double rate;
            string file;
            string log;
            InstallStage stage;
            string title;

            lock (_gate)
            {
                expected = _expectedTotal;
                received = _receivedTotal;
                rate = _bytesPerSecond;
                file = _currentFile;
                log = _logLine;
                stage = _stage;
                title = _stageTitle;

                fraction = expected > 0 ? Math.Clamp(received / (double)expected, 0, 0.999) : 0;
                if (stage == InstallStage.Done)
                {
                    fraction = 1;
                }

                if (rate > 0 && expected > received && received > 0)
                {
                    eta = (expected - received) / rate;
                }
            }

            _owner.Tick?.Invoke(_owner, new InstallTick
            {
                Stage = stage,
                StageTitle = title,
                CurrentFile = file,
                LogLine = log,
                ReceivedBytes = received,
                TotalBytes = expected,
                BytesPerSecond = rate,
                OverallFraction = fraction,
                EtaSeconds = eta,
            });
        }
    }
}
