using System.Diagnostics;
using System.Text;
using System.Text.Json;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Models;

namespace Xsoz.Launcher.Services;

/// <summary>Raised when the launch cannot be assembled from the instance's own files.</summary>
public sealed class LaunchAssemblyException(string message) : Exception(message);

/// <summary>Why a launch cannot proceed, when it cannot.</summary>
public enum LaunchBlocker
{
    /// <summary>Nothing is wrong.</summary>
    None,

    /// <summary>The managed instance exists in part but the install never finished.</summary>
    InstanceMissing,

    /// <summary>The instance exists but has no copy of the requested game version.</summary>
    VersionMissing,

    /// <summary>The instance launches, but the mod layer on disk is not the manifest the launcher ships.</summary>
    ModLayerIncomplete,

    /// <summary>The instance is real but the mod loader for this version is not installed in it.</summary>
    LoaderNotInstalled,

    /// <summary>No Java runtime has been provisioned and none was pointed at.</summary>
    JavaMissing,

    /// <summary>The disclaimer has not been accepted.</summary>
    DisclaimerNotAccepted,
}

/// <summary>One launch line, as it would be run. Shown before the game starts so nothing is hidden.</summary>
/// <param name="Program">The executable.</param>
/// <param name="Arguments">The full argument string.</param>
/// <param name="WorkingDirectory">The process working directory.</param>
public readonly record struct LaunchCommand(string Program, string Arguments, string WorkingDirectory);

/// <summary>Everything the launch path produces, whether or not it succeeded.</summary>
public sealed class LaunchPlan
{
    /// <summary>What stopped the launch, if anything.</summary>
    public LaunchBlocker Blocker { get; init; } = LaunchBlocker.None;

    /// <summary>Plain-English explanation of the blocker.</summary>
    public string BlockerDetail { get; init; } = string.Empty;

    /// <summary>The exact loader version that would be needed, for the missing-loader message.</summary>
    public string RequiredLoader { get; init; } = string.Empty;

    /// <summary>The JVM flags assembled for this launch.</summary>
    public JvmFlagSet Jvm { get; init; } = new();

    /// <summary>The full command, when one could be built.</summary>
    public LaunchCommand? Command { get; init; }

    /// <summary>True when the launch can actually proceed.</summary>
    public bool CanLaunch => Blocker == LaunchBlocker.None && Command is not null;

    /// <summary>Process id of the running game, once started.</summary>
    public int? ProcessId { get; set; }
}

/// <summary>
/// Builds and starts the game process from the instance the installer wrote.
///
/// THIS IS THE FALLBACK PATH, and it is kept deliberately. The primary architecture hands off to
/// the official Minecraft launcher, which authenticates and launches. This path exists for the case
/// where that is not wanted or not available: it provisions its own Java 21, its own Fabric, its own
/// mod layer and its own runtime under %LOCALAPPDATA%\XsozClient, and starts the JVM itself.
///
/// It launches OFFLINE, and that is now the whole of its account story. There is no session
/// parameter, no bearer token, no identity exchange and no credential store behind it any more: the
/// identity is derived from a name by <see cref="OfflineUuidFor"/> and nothing else. That is a
/// smaller capability and an honest one - it cannot play online, and it does not pretend to.
///
/// The launch is assembled FROM the version JSONs on disk, not from a template in this file: the
/// vanilla version JSON supplies the rule-evaluated argument blocks and the asset index, the
/// Fabric profile contributes the loader libraries and the KnotClient main class, and the merges
/// are exactly the ones every third-party launcher implements - classpath is vanilla libraries
/// plus loader libraries, arguments are vanilla rule-evaluated blocks followed by the loader's
/// additions, and every ${...} placeholder is substituted before the process starts. An
/// unverifiable state (missing client jar, missing natives dir, unresolved placeholder) is a
/// readable blocker, not a stack trace.
/// </summary>
public sealed class LaunchService
{
    private readonly InstanceScanner _scanner;

    /// <summary>Creates the service.</summary>
    public LaunchService(InstanceScanner scanner) => _scanner = scanner;

    /// <summary>The running game process, if any. The launcher's lifetime never depends on it.</summary>
    public Process? Running { get; private set; }

    /// <summary>Raised when the game process exits, with the exit code.</summary>
    public event EventHandler<int>? GameExited;

    /// <summary>Resolves the java executable for the chosen Java source, or null when there is none.</summary>
    public string? ResolveJava(LauncherSettings settings, GameVersionDescriptor version)
    {
        if (settings.JavaSource == JavaSource.Custom)
        {
            if (string.IsNullOrWhiteSpace(settings.CustomJavaPath))
            {
                return null;
            }

            return File.Exists(settings.CustomJavaPath) ? settings.CustomJavaPath : null;
        }

        // Per-version runtime first: never one global JDK, and never java.exe from PATH implicitly.
        var root = Path.Combine(AppPaths.Runtimes, version.JavaMajor.ToString(System.Globalization.CultureInfo.InvariantCulture));
        return JavaProvisioner.FindJavaw(root);
    }

    /// <summary>Back-compat shim: probes a runtime root for a java.exe.</summary>
    public static string? FindProvisionedJava(string runtimeRoot)
    {
        try
        {
            var candidates = Directory
                .EnumerateFiles(runtimeRoot, "java.exe", new EnumerationOptions
                {
                    RecurseSubdirectories = true,
                    IgnoreInaccessible = true,
                    MaxRecursionDepth = 4,
                })
                .Where(p => p.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}", StringComparison.OrdinalIgnoreCase))
                .ToArray();

            if (candidates.Length == 0)
            {
                return null;
            }

            Array.Sort(candidates, StringComparer.OrdinalIgnoreCase);
            return candidates[^1];
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>
    /// Assembles a launch plan without starting anything.
    /// <para>
    /// Offline path only. There is no session argument and there is no longer a session type: this
    /// launcher does not hold a bearer token, so the only identity available here is the offline one
    /// derived from <paramref name="accountName"/>.
    /// </para>
    /// </summary>
    public LaunchPlan Plan(
        GameVersionDescriptor version,
        LauncherSettings settings,
        Profile profile,
        string accountName)
    {
        var jvm = JvmFlagBuilder.Build(version, settings);

        if (!settings.AcceptedDisclaimer)
        {
            return new LaunchPlan
            {
                Blocker = LaunchBlocker.DisclaimerNotAccepted,
                BlockerDetail =
                    "The Minecraft EULA and usage guidelines have not been accepted yet. Accepting them takes one click "
                    + "on the Launch screen and is recorded against this name.",
                Jvm = jvm,
            };
        }

        var install = new InstallService(new DownloadService());
        var gameRoot = install.GameRoot;

        if (!Directory.Exists(gameRoot))
        {
            return new LaunchPlan
            {
                Blocker = LaunchBlocker.InstanceMissing,
                BlockerDetail = "Nothing has been installed yet. INSTALL & PLAY on the Home screen downloads and verifies everything, once.",
                Jvm = jvm,
            };
        }

        var vanillaJsonPath = Path.Combine(gameRoot, "versions", InstallService.VanillaVersionId, InstallService.VanillaVersionId + ".json");
        var fabricJsonPath = Path.Combine(gameRoot, "versions", InstallService.InstanceVersionId, InstallService.InstanceVersionId + ".json");

        if (!File.Exists(vanillaJsonPath))
        {
            return new LaunchPlan
            {
                Blocker = LaunchBlocker.VersionMissing,
                BlockerDetail = $"The instance does not contain the {InstallService.VanillaVersionId} client. Run the install.",
                Jvm = jvm,
            };
        }

        if (!File.Exists(fabricJsonPath))
        {
            return new LaunchPlan
            {
                Blocker = LaunchBlocker.LoaderNotInstalled,
                RequiredLoader = $"{version.LoaderName} for {version.Id}",
                BlockerDetail =
                    $"{version.LoaderName} is not installed in this instance, so the game would start vanilla and the "
                    + "mod layer would not load. Run the install; it completes the loader and the mod layer in one pass.",
                Jvm = jvm,
            };
        }

        var java = ResolveJava(settings, version);
        if (java is null)
        {
            return new LaunchPlan
            {
                Blocker = LaunchBlocker.JavaMissing,
                BlockerDetail =
                    settings.JavaSource == JavaSource.Custom
                        ? "The custom Java path is empty or does not point at a file. Set it in Settings."
                        : $"No Java {version.JavaMajor} runtime has been provisioned. The install downloads Temurin 21 from Adoptium, once.",
                Jvm = jvm,
            };
        }

        // The mod layer is part of the product, not an optional extra: a launch off an incomplete
        // layer starts a game that is quietly missing modules the user has switched on, and the
        // only honest moment to say so is before the process starts. One line, no stack trace.
        if (!install.IsInstalled())
        {
            var modsDir = Path.Combine(gameRoot, "mods");
            var present = Directory.Exists(modsDir) ? Directory.EnumerateFiles(modsDir, "*.jar").Count() : 0;
            return new LaunchPlan
            {
                Blocker = LaunchBlocker.ModLayerIncomplete,
                BlockerDetail =
                    $"The mod layer is incomplete: {present} of {ModrinthApi.Stack.Count} mods are installed. "
                    + "Run the install - it downloads only what is missing.",
                Jvm = jvm,
            };
        }

        try
        {
            // Re-apply the managed option block every launch: the game rewrites options.txt on
            // exit and drops anything it does not know, so the template is the source of truth.
            GameConfigWriter.ApplyDefaultsAsync(gameRoot, CancellationToken.None).GetAwaiter().GetResult();

            var command = BuildCommand(gameRoot, install, java, version, settings, profile, jvm, accountName);
            return new LaunchPlan { Blocker = LaunchBlocker.None, Jvm = jvm, Command = command };
        }
        catch (LaunchAssemblyException ex)
        {
            return new LaunchPlan { Blocker = LaunchBlocker.InstanceMissing, BlockerDetail = ex.Message, Jvm = jvm };
        }
    }

    /// <summary>Starts the game. Never throws: a launch failure is a state, not a crash.</summary>
    public LaunchPlan Start(LaunchPlan plan)
    {
        if (!plan.CanLaunch || plan.Command is not { } command)
        {
            return plan;
        }

        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = command.Program,
                Arguments = command.Arguments,
                WorkingDirectory = command.WorkingDirectory,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };

            var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
            process.OutputDataReceived += (_, e) => { if (e.Data is not null) { AppLog.Shared.Write(LogLevel.Trace, e.Data); } };
            process.ErrorDataReceived += (_, e) => { if (e.Data is not null) { AppLog.Shared.Write(LogLevel.Warn, e.Data); } };
            process.Exited += (_, _) =>
            {
                AppLog.Shared.Warn($"The game process exited with code {process.ExitCode}.");
                Running = null;
                GameExited?.Invoke(this, process.ExitCode);
                process.Dispose();
            };

            process.Start();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();

            Running = process;
            AppLog.Shared.Success($"Started the game process (pid {process.Id}).");
            plan.ProcessId = process.Id;
            return plan;
        }
        catch (Exception ex)
        {
            AppLog.Shared.Error($"Could not start the game process: {ex.Message}");
            return new LaunchPlan
            {
                Blocker = LaunchBlocker.JavaMissing,
                BlockerDetail = $"The runtime at '{command.Program}' could not be started: {ex.Message}",
                Jvm = plan.Jvm,
            };
        }
    }

    /// <summary>
    /// The offline-mode UUID the game derives from a player name, formatted undashed.
    /// </summary>
    public static string OfflineUuidFor(string name)
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes("OfflinePlayer:" + name);
        var hash = System.Security.Cryptography.MD5.HashData(bytes);
        hash[6] = (byte)((hash[6] & 0x0F) | 0x30);
        hash[8] = (byte)((hash[8] & 0x3F) | 0x80);
        return Convert.ToHexStringLower(hash);
    }

    /// <summary>
    /// Builds the full process command from the version JSONs. Every ${...} placeholder is
    /// substituted; an unresolved one is a readable error, not garbage on a command line.
    /// </summary>
    private static LaunchCommand BuildCommand(
        string gameRoot,
        InstallService install,
        string java,
        GameVersionDescriptor version,
        LauncherSettings settings,
        Profile profile,
        JvmFlagSet jvm,
        string accountName)
    {
        var vanillaJson = JsonDocument.Parse(File.ReadAllText(Path.Combine(gameRoot, "versions", InstallService.VanillaVersionId, InstallService.VanillaVersionId + ".json")));
        var fabricJson = JsonDocument.Parse(File.ReadAllText(Path.Combine(gameRoot, "versions", InstallService.InstanceVersionId, InstallService.InstanceVersionId + ".json")));

        var vanilla = vanillaJson.RootElement;
        var fabric = fabricJson.RootElement;

        var libraries = ResolveLibraryClasspath(vanilla, fabric, gameRoot);
        var missing = libraries.Where(p => !File.Exists(p)).ToList();
        if (missing.Count > 0)
        {
            throw new LaunchAssemblyException(
                missing.Count + " librar" + (missing.Count == 1 ? "y is" : "ies are") + " missing from the instance - the first is "
                + Path.GetFileName(missing[0]) + ". Run the install again; it downloads only what fails verification.");
        }

        var mainClass = fabric.TryGetProperty("mainClass", out var fabricMain)
            ? fabricMain.GetString()!
            : vanilla.GetProperty("mainClass").GetString()!;

        var nativesDir = Path.Combine(gameRoot, "natives", InstallService.InstanceVersionId.ToLowerInvariant());
        var assetIndex = vanilla.TryGetProperty("assetIndex", out var ai) && ai.TryGetProperty("id", out var aiId)
            ? aiId.GetString() ?? "26"
            : "26";

        if (!Directory.Exists(Path.Combine(gameRoot, "assets", "indexes")))
        {
            throw new LaunchAssemblyException("The assets index is missing. Run the install again; it re-fetches what failed verification.");
        }

        // Identity. This path is offline and is the whole of the account story: a uuid derived from
        // the display name and a zero token. There is no bearer token anywhere in this product for
        // this code to carry, because none is acquired and none is stored.
        var uuid = OfflineUuidFor(accountName);
        const string token = "0";

        var substitutions = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["auth_player_name"] = accountName,
            ["version_name"] = InstallService.InstanceVersionId,
            ["game_directory"] = gameRoot,
            ["assets_root"] = Path.Combine(gameRoot, "assets"),
            ["assets_index_name"] = assetIndex,
            ["auth_uuid"] = uuid,
            ["auth_access_token"] = token,
            ["accessToken"] = token,
            ["auth_session"] = token,
            ["clientid"] = "0",
            ["auth_xuid"] = "0",
            ["user_properties"] = "{}",
            ["quickPlayPath"] = string.Empty,
            ["quickPlaySingleplayer"] = string.Empty,
            ["quickPlayMultiplayer"] = string.Empty,
            ["quickPlayRealms"] = string.Empty,
            ["version_type"] = "xsoz",
            ["user_type"] = "legacy",
            ["launcher_name"] = "xsoz",
            ["launcher_version"] = typeof(LaunchService).Assembly.GetName().Version?.ToString(3) ?? "0.1.0",
            ["classpath"] = string.Join(Path.PathSeparator, libraries),
            ["classpath_separator"] = Path.PathSeparator.ToString(),
            ["library_directory"] = Path.Combine(gameRoot, "libraries"),
            ["natives_directory"] = nativesDir,
            ["resolution_width"] = settings.WindowWidth.ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["resolution_height"] = settings.WindowHeight.ToString(System.Globalization.CultureInfo.InvariantCulture),
        };

        var arguments = new StringBuilder();

        // JVM block: vanilla's rule-evaluated entries, then ours, then Fabric's.
        var jvmStrings = EvaluateArguments(vanilla, "jvm", substitutions);
        jvmStrings.AddRange(jvm.Flags);
        if (fabric.TryGetProperty("arguments", out var fabricArgs) && fabricArgs.TryGetProperty("jvm", out var fabricJvm))
        {
            jvmStrings.AddRange(EvaluateArgumentList(fabricJvm, substitutions));
        }

        arguments.Append(string.Join(' ', jvmStrings.Select(QuoteIfNeeded)));
        arguments.Append(' ');
        arguments.Append(mainClass);
        arguments.Append(' ');

        var gameStrings = EvaluateArguments(vanilla, "game", substitutions);
        if (fabric.ValueKind != JsonValueKind.Undefined && fabricArgs.ValueKind != JsonValueKind.Undefined && fabricArgs.TryGetProperty("game", out var fabricGame))
        {
            gameStrings.AddRange(EvaluateArgumentList(fabricGame, substitutions));
        }

        arguments.Append(string.Join(' ', gameStrings.Select(QuoteIfNeeded)));

        return new LaunchCommand(java, arguments.ToString(), gameRoot);
    }

    private static List<string> ResolveLibraryClasspath(JsonElement vanilla, JsonElement fabric, string gameRoot)
    {
        var paths = new List<string>();

        if (vanilla.TryGetProperty("libraries", out var vanillaLibs))
        {
            foreach (var lib in vanillaLibs.EnumerateArray())
            {
                if (!MojangApi.RulesAllow(lib))
                {
                    continue;
                }

                if (lib.TryGetProperty("downloads", out var downloads) && downloads.TryGetProperty("artifact", out var artifact))
                {
                    var path = artifact.GetProperty("path").GetString()!;
                    paths.Add(Path.Combine(gameRoot, "libraries", path.Replace('/', Path.DirectorySeparatorChar)));
                }
            }
        }

        if (fabric.TryGetProperty("libraries", out var fabricLibs))
        {
            foreach (var lib in fabricLibs.EnumerateArray())
            {
                var path = FabricMeta.MavenPath(lib.GetProperty("name").GetString()!);
                if (path is not null)
                {
                    paths.Add(Path.Combine(gameRoot, "libraries", path.Replace('/', Path.DirectorySeparatorChar)));
                }
            }
        }

        // The client jar itself is always the last classpath entry on the vanilla layout.
        var clientJar = Path.Combine(gameRoot, "versions", InstallService.VanillaVersionId, InstallService.VanillaVersionId + ".jar");
        paths.Add(clientJar);
        return paths;
    }

    /// <summary>Evaluates one arguments block (jvm or game) of a version JSON, rule-aware.</summary>
    private static List<string> EvaluateArguments(JsonElement root, string key, Dictionary<string, string> substitutions)
    {
        if (!root.TryGetProperty("arguments", out var args) || !args.TryGetProperty(key, out var list))
        {
            return [];
        }

        return EvaluateArgumentList(list, substitutions);
    }

    /// <summary>Evaluates a raw argument list element: strings pass through, objects are rule-gated.</summary>
    private static List<string> EvaluateArgumentList(JsonElement list, Dictionary<string, string> substitutions)
    {
        var result = new List<string>();
        foreach (var entry in list.EnumerateArray())
        {
            if (entry.ValueKind == JsonValueKind.String)
            {
                result.Add(Substitute(entry.GetString()!, substitutions));
                continue;
            }

            if (entry.ValueKind == JsonValueKind.Object)
            {
                if (entry.TryGetProperty("rules", out var rules) && !MojangApi.ArgumentRuleMatches(rules))
                {
                    continue;
                }

                // Feature-gated entries (demo, custom resolution, quickplay) only match when the
                // feature applies. ArgumentRuleMatches answers that; when it does, the value is
                // either one string or a one-element array.
                if (entry.TryGetProperty("value", out var value))
                {
                    if (value.ValueKind == JsonValueKind.String)
                    {
                        result.Add(Substitute(value.GetString()!, substitutions));
                    }
                    else if (value.ValueKind == JsonValueKind.Array)
                    {
                        foreach (var v in value.EnumerateArray())
                        {
                            result.Add(Substitute(v.GetString()!, substitutions));
                        }
                    }
                }
            }
        }

        // An unresolved placeholder on a real command line means the game reads the literal text
        // - the launch must say which one rather than hand Mojang's parser a mystery.
        foreach (var arg in result)
        {
            if (arg.Contains("${", StringComparison.Ordinal))
            {
                throw new LaunchAssemblyException(
                    $"The version JSON's argument '{arg}' still contains an unsubstituted placeholder. "
                    + "That is a launcher bug; nothing was started, and the instance was not changed.");
            }
        }

        return result;
    }

    private static string Substitute(string text, Dictionary<string, string> substitutions)
    {
        // Substituting longest-first so "assets_index_name" wins over any prefix it shares.
        foreach (var (key, value) in substitutions.OrderByDescending(kv => kv.Key.Length))
        {
            text = text.Replace("${" + key + "}", value, StringComparison.Ordinal);
        }

        return text;
    }

    private static string QuoteIfNeeded(string value) =>
        value.Contains(' ', StringComparison.Ordinal) && !(value.StartsWith('"') && value.EndsWith('"'))
            ? $"\"{value}\""
            : value;

    /// <summary>A build problem phrased for a human, not a stack trace.</summary>
    private sealed class LaunchAssemblyException : Exception
    {
        public LaunchAssemblyException(string message) : base(message)
        {
        }
    }
}
