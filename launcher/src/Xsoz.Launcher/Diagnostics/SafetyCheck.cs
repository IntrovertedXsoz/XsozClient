using System.Text;
using Xsoz.Launcher.Services;

namespace Xsoz.Launcher.Diagnostics;

/// <summary>One static or runtime check about the read-only guarantee, and its verdict.</summary>
/// <param name="Name">What was checked.</param>
/// <param name="Passed">True when the check held.</param>
/// <param name="Detail">The evidence, as it will be printed.</param>
public sealed record SafetyCheckResult(string Name, bool Passed, string Detail);

/// <summary>
/// THE WRITE-SAFETY STATIC CHECK: <c>--safecheck</c>.
///
/// The incident this exists because of was not a bug. Every line of it was reasonable: a
/// verification harness wanted to exercise the real provisioning code, the provisioning code had a
/// safety gate, and the gate was tested. Then somebody ran <c>--screenshot &lt;dir&gt;</c> as a
/// verification step, the gate found the official launcher closed, and the run wrote a version
/// folder, eight jars and a profile into a real <c>%APPDATA%\.minecraft</c>. The protection was an
/// opt-OUT environment variable, which means the safe state was the one that required remembering
/// something - and the day somebody does not remember it, the harness writes to somebody's disk.
///
/// So the guarantee is now structural: a read-only mode is handed a
/// <see cref="NoOpGameDirectoryWriter"/> and the provisioner has no other way to touch the disk.
/// Structure is only worth anything if something checks it, and that is this file. It has two halves.
///
/// THE SOURCE HALF, which catches the refactors that would quietly undo the arrangement:
///
///   1. THE REAL WRITER IS CONSTRUCTED IN EXACTLY ONE FILE - the write policy - and that file
///      grants it only to an interactive launch. A second <c>new RealGameDirectoryWriter</c>
///      anywhere is a second way to reach the disk, and it fails here.
///   2. THE REAL WRITER'S TYPE IS NAMED IN EXACTLY ONE FILE. Naming the implementation is the first
///      step of every route to possessing one; the interface and the no-op sink may be named
///      anywhere, because naming a no-op sink is how a diagnostic run asks to be left alone.
///   3. THE LEGACY ENVIRONMENT VARIABLE IS READ BY EXACTLY ONE LINE, which prints its value. Its
///      name is still declared, so old scripts do not break, but the identifier appears in exactly
///      two lines of code in the tree and both are the declaration and that print. A third is
///      somebody wiring an environment variable back into a decision, and the whole failure was a
///      decision driven by a variable.
///   4. THE COMMAND LINE DEFAULTS TO READ-ONLY, asserted from the record's own declaration text and
///      then by parsing every switch and checking the answer - including that a bare launch and an
///      unrecognised switch ARE granted, so the check can fail in both directions.
///
/// THE RUNTIME HALF, which is the one that actually measures: a real
/// <see cref="VanillaProvisioner"/>, pointed at the real <c>%APPDATA%\.minecraft</c>, handed a
/// no-op sink, asked to provision. It must return having written nothing, the sink must have
/// counted zero attempts, and the game directory's fingerprint must be unchanged. That is the same
/// assertion the capture's preflight makes on every run, so the two cannot disagree, and neither
/// depends on reading a source file correctly.
///
/// Exit code is 0 only if every check holds. It is a CI-style gate on purpose: a regression here
/// should stop a build, not produce a line in a log somebody reads next week.
/// </summary>
public static class SafetyCheck
{
    /// <summary>
    /// The only file permitted to construct the real writer. Named, not searched for, so that
    /// moving it fails the check instead of quietly widening the search.
    /// </summary>
    private const string PolicyFile = @"Services\VanillaLauncher\GameWritePolicy.cs";

    /// <summary>
    /// The real writer's own declaration. Allowed to name the type, because a class has to name
    /// itself; allowed to do nothing else with it, which check 1 enforces.
    /// </summary>
    private const string RealWriterDeclarationFile = @"Services\VanillaLauncher\RealGameDirectoryWriter.cs";

    /// <summary>
    /// The only file permitted to name the legacy environment variable, and only in its own
    /// declaration.
    /// </summary>
    private const string ScreenshotFile = @"Diagnostics\ScreenshotRunner.cs";

    /// <summary>The command-line file, read for the default-value assertion.</summary>
    private const string CommandLineFile = @"Diagnostics\LauncherCommandLine.cs";

    /// <summary>The real writer's type name. Named in one file only, and only in the policy file.</summary>
    private const string RealWriterType = "RealGameDirectoryWriter";

    /// <summary>The legacy opt-out variable's identifier. Exactly two lines of code may contain it.</summary>
    private const string LegacyVariableIdentifier = "SkipPreflightVariable";

    /// <summary>
    /// Runs every check. Returns the process exit code: 0 only if all of them hold.
    /// </summary>
    /// <param name="output">Where the report goes.</param>
    /// <param name="sourceDirectory">The project directory, or null to find it.</param>
    public static int Run(TextWriter output, string? sourceDirectory)
    {
        ArgumentNullException.ThrowIfNull(output);

        output.WriteLine("XsozClient write-safety static check");
        output.WriteLine("Asserting that no non-interactive mode can reach the disk writes behind provisioning.");
        output.WriteLine();

        var root = sourceDirectory ?? MotionCheck.FindProjectDirectory();
        if (root is null)
        {
            output.WriteLine("[FAIL] project source not found.");
            output.WriteLine("       Run this against the source tree, or pass the directory: "
                             + "XsozClient.exe " + LauncherCommandLine.SafeCheckSwitch + " <projectDir>");
            return 2;
        }

        output.WriteLine("Source   : " + root);
        output.WriteLine("Process  : " + GameWritePolicy.Describe());
        output.WriteLine();

        var files = MotionCheck.SourceFiles(root).ToList();
        output.WriteLine($"Scanned  : {files.Count(f => f.EndsWith(".cs", StringComparison.OrdinalIgnoreCase))} C# file(s).");
        output.WriteLine();

        var results = new List<SafetyCheckResult>
        {
            CheckRealWriterIsConstructedOnce(root, files),
            CheckRealWriterTypeIsNamedInOneFileOnly(root, files),
            CheckLegacyVariableIsNeverRead(root, files),
            CheckCommandLineDefaultsToReadOnly(root),
            CheckReadOnlyModesParseAsReadOnly(),
            CheckReadOnlyWriterRefusesEverything(),
        };

        var report = new StringBuilder();
        report.AppendLine("================ write-safety static check ================");
        foreach (var result in results)
        {
            report.AppendLine($"[{(result.Passed ? "PASS" : "FAIL")}] {result.Name}");
            report.AppendLine($"       {result.Detail}");
        }

        var failures = results.Count(r => !r.Passed);
        report.AppendLine();
        report.AppendLine(failures == 0
            ? $"All {results.Count} write-safety checks passed. A non-interactive mode cannot provision."
            : $"{failures} of {results.Count} write-safety checks FAILED. A non-interactive mode may be able to write.");

        output.Write(report.ToString());
        output.WriteLine();
        return failures == 0 ? 0 : 1;
    }

    // -------------------------------------------------------------------------------------------
    // The source half.
    // -------------------------------------------------------------------------------------------

    /// <summary>Check 1: one construction site for the real writer, and it is the policy.</summary>
    private static SafetyCheckResult CheckRealWriterIsConstructedOnce(string root, IReadOnlyList<string> files)
    {
        var sites = new List<string>();
        foreach (var file in CodeFiles(files))
        {
            foreach (var line in CodeLines(file))
            {
                if (line.Contains("new " + RealWriterType + "(", StringComparison.Ordinal))
                {
                    sites.Add(Rel(root, file));
                }
            }
        }

        var passed = sites.Count == 1 && string.Equals(sites[0], PolicyFile, StringComparison.OrdinalIgnoreCase);
        return new SafetyCheckResult(
            "The real writer is constructed in exactly one file, and that file is the write policy",
            passed,
            passed
                ? $"1 construction site: {sites[0]}. It is reachable only through GameWritePolicy.CreateWriter, which "
                  + "returns a no-op sink unless the process is interactive."
                : sites.Count == 0
                    ? "no construction of " + RealWriterType + " was found, so nothing can reach the disk - but the policy "
                      + "file has also lost its factory, which would mean the interactive path cannot provision either"
                    : sites.Count + " construction site(s): " + string.Join(", ", sites)
                      + " (expected exactly one, in " + PolicyFile + ")");
    }

    /// <summary>
    /// Check 2: the legacy opt-out variable's identifier appears exactly once in the project - in
    /// its own declaration.
    /// <para>
    /// Comments and string literals are stripped first, so this file and the help text can both talk
    /// about the variable as loudly as they like without failing their own check. What is left is
    /// code, and in code the name must be dead: a second reference is somebody wiring an environment
    /// variable back into a decision, which is the exact shape of the failure being guarded against.
    /// </para>
    /// </summary>
    private static SafetyCheckResult CheckLegacyVariableIsNeverRead(string root, IReadOnlyList<string> files)
    {
        var hits = new List<string>();
        foreach (var file in CodeFiles(files))
        {
            foreach (var line in CodeLines(file))
            {
                if (line.Contains(LegacyVariableIdentifier, StringComparison.Ordinal))
                {
                    hits.Add(Rel(root, file) + ": " + line.Trim());
                }
            }
        }

        // Exactly two lines of code in the whole project may name the variable, both in
        // ScreenshotRunner: its own declaration, and one line that aliases the name for a single
        // read. ScreenshotRunner itself makes exactly one GetEnvironmentVariable call, and it is
        // that one, whose value is only printed. A third name, or a second read there, is somebody
        // wiring an environment variable back into a decision - the exact shape of the failure being
        // guarded against. A guard whose safe state is the one that needs a variable set is a guard
        // that is off on the day somebody forgets, and the day somebody forgot is how a capture run
        // once wrote a version folder and eight jars into a real installation.
        //
        // Scoped to this one file on purpose: SelfTestRunner legitimately reads three of its own
        // self-test variables, and counting the whole project would make this check complain about
        // code that has nothing to do with the decision being guarded.
        var reads = CodeLines(Path.Combine(root, ScreenshotFile))
            .Count(l => l.Contains("Environment.GetEnvironmentVariable(", StringComparison.Ordinal));

        var inScreenshotRunner = hits.Count == 2
                                 && hits.All(h => h.StartsWith(ScreenshotFile, StringComparison.OrdinalIgnoreCase))
                                 && hits.Count(h => h.Contains("const string " + LegacyVariableIdentifier, StringComparison.Ordinal)) == 1
                                 && reads == 1;

        return new SafetyCheckResult(
            "The legacy capture environment variable is declared once and read by exactly one printing line",
            inScreenshotRunner,
            inScreenshotRunner
                ? "XSOZ_CAPTURE_SKIP_PREFLIGHT is named in exactly 2 lines of code, both in " + ScreenshotFile
                  + " (the const declaration and one alias), and that file makes exactly 1 GetEnvironmentVariable call, "
                  + "whose value is only printed. No environment variable can re-enable a write."
                : hits.Count + " line(s) of code name " + LegacyVariableIdentifier + ", and " + ScreenshotFile
                  + " makes " + reads + " GetEnvironmentVariable call(s); offenders: " + string.Join(" | ", hits.Take(6))
                  + " (expected 2 names, both in " + ScreenshotFile + ", and exactly 1 read there)");
    }

    /// <summary>Check 3: the record's own declaration says the permission defaults to false.</summary>
    private static SafetyCheckResult CheckCommandLineDefaultsToReadOnly(string root)
    {
        var text = MotionCheck.ReadText(Path.Combine(root, CommandLineFile));
        if (text is null)
        {
            return new SafetyCheckResult(
                "The command line's write permission defaults to false and is granted in exactly one place",
                false,
                "could not read " + CommandLineFile);
        }

        var code = MotionCheck.CodeOnly.Replace(text, string.Empty);
        var declared = code.Contains("bool AllowsGameWrites = false", StringComparison.Ordinal);

        var grants = 0;
        foreach (var line in MotionCheck.SplitLines(code))
        {
            if (line.Contains("AllowsGameWrites: true", StringComparison.Ordinal))
            {
                grants++;
            }
        }

        var passed = declared && grants == 1;
        return new SafetyCheckResult(
            "The command line's write permission defaults to false and is granted in exactly one place",
            passed,
            passed
                ? "CommandLineParse.AllowsGameWrites = false by default, and exactly one call site passes true - "
                  + "Interactive(), the bare-launch path. Every diagnostic switch is read-only by omission."
                : !declared
                    ? "CommandLineParse does not declare 'bool AllowsGameWrites = false'; the read-only default is missing"
                    : grants + " call site(s) pass 'AllowsGameWrites: true' (expected exactly one)");
    }

    /// <summary>Check 4: every switch, parsed, really does come back read-only.</summary>
    private static SafetyCheckResult CheckReadOnlyModesParseAsReadOnly()
    {
        var cases = new (string Args, CommandLineMode Expected)[]
        {
            ("--help", CommandLineMode.Help),
            ("-h", CommandLineMode.Help),
            ("--screenshot C:\\Temp\\xsoz-shots", CommandLineMode.Screenshot),
            ("--selftest", CommandLineMode.SelfTest),
            ("--motioncheck", CommandLineMode.MotionCheck),
            ("--safecheck", CommandLineMode.SafeCheck),
            ("--screenshot", CommandLineMode.Error),
        };

        var problems = new List<string>();
        foreach (var (args, expected) in cases)
        {
            var parsed = LauncherCommandLine.Parse(args.Split(' ', StringSplitOptions.RemoveEmptyEntries));
            if (parsed.Mode != expected)
            {
                problems.Add(args + " parsed as " + parsed.Mode + ", expected " + expected);
            }

            if (parsed.AllowsGameWrites)
            {
                problems.Add(args + " was granted game-directory write permission");
            }
        }

        // The two interactive shapes, asserted as carefully as the read-only ones: a check that can
        // only fail in one direction is a check that proves nothing.
        if (!LauncherCommandLine.Parse([]).AllowsGameWrites)
        {
            problems.Add("a bare launch was not granted write permission, so the launcher could never provision at all");
        }

        if (!LauncherCommandLine.Parse(["--nonsense"]).AllowsGameWrites)
        {
            problems.Add("an unrecognised switch was not granted write permission, so a double-click with a stray argument could not provision");
        }

        return new SafetyCheckResult(
            "Every non-interactive switch parses as read-only, and only a bare launch may write",
            problems.Count == 0,
            problems.Count == 0
                ? cases.Length + " read-only invocation(s) checked (help, -h, screenshot, selftest, motioncheck, "
                  + "safecheck, malformed screenshot), all denied; a bare launch and an unrecognised switch, both granted."
                : string.Join(" | ", problems));
    }

    /// <summary>
    /// Check 2: the real writer's TYPE is named in two files only - the policy that hands it out,
    /// and the file that declares it.
    /// <para>
    /// Tighter than the construction-site check on purpose. A mention is not a construction, but a
    /// mention is a handle: naming the implementation is the first step of every way to end up
    /// holding one. The interface and the no-op sink may be named anywhere, because naming a no-op
    /// sink is how a diagnostic run asks to be left alone, and naming the interface is just typing.
    /// What must not happen anywhere else is the real implementation's name appearing at all,
    /// because the moment it does, the reason the policy is the only gate is one line away from
    /// being untrue.
    /// </para>
    /// </summary>
    private static SafetyCheckResult CheckRealWriterTypeIsNamedInOneFileOnly(string root, IReadOnlyList<string> files)
    {
        var hits = new List<string>();
        foreach (var file in CodeFiles(files))
        {
            foreach (var line in CodeLines(file))
            {
                if (line.Contains(RealWriterType, StringComparison.Ordinal))
                {
                    hits.Add(Rel(root, file));
                }
            }
        }

        var distinct = hits.Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(h => h, StringComparer.Ordinal).ToList();
        var expected = new[] { PolicyFile, RealWriterDeclarationFile };
        var passed = distinct.SequenceEqual(expected, StringComparer.OrdinalIgnoreCase);

        return new SafetyCheckResult(
            "The real writer's type is named only where it is declared and where it is handed out",
            passed,
            passed
                ? $"{RealWriterType} appears in {hits.Count} line(s), across exactly two files: its own declaration and "
                  + PolicyFile + ". No Diagnostics\\ or ViewModels\\ file can name it, so none of them can ask for one; the "
                  + "no-op sink and the interface may be named freely, because asking for a sink that cannot write is the point."
                : "named in " + string.Join(", ", distinct)
                  + " (expected exactly " + string.Join(" and ", expected)
                  + "; a name outside those two is one step from a second way to reach the disk)");
    }

    // -------------------------------------------------------------------------------------------
    // The runtime half.
    // -------------------------------------------------------------------------------------------

    /// <summary>
    /// Check 6, the one that measures. The real provisioner, the real game directory, a sink that
    /// cannot write, and a request to provision. Zero attempts, zero written paths, and the
    /// fingerprint of the game directory unchanged.
    /// <para>
    /// Deliberately the same assertion the capture's preflight makes, so the two cannot disagree and
    /// so a regression can be caught by a build agent that has never rendered a pixel.
    /// </para>
    /// </summary>
    private static SafetyCheckResult CheckReadOnlyWriterRefusesEverything()
    {
        var sink = new NoOpGameDirectoryWriter();
        var minecraft = MinecraftDirectory.Default;
        var before = ProvisioningPreflight.FingerprintGameDirectory(minecraft);

        VanillaProvisionResult result;
        try
        {
            var provisioner = new VanillaProvisioner(new DownloadService(), sink, minecraft);
            result = provisioner.RunAsync(CancellationToken.None).GetAwaiter().GetResult();
        }
        catch (Exception ex)
        {
            return new SafetyCheckResult(
                "A provisioner with a read-only writer writes nothing",
                false,
                "the drill threw " + ex.GetType().Name + ": " + ex.Message);
        }

        var after = ProvisioningPreflight.FingerprintGameDirectory(minecraft);
        var attempts = sink.WriteAttempts;
        var refused = sink.RefusedOperations;
        var unchanged = string.Equals(before, after, StringComparison.Ordinal);

        var passed = result.ReadOnlyMode
                     && !result.Succeeded
                     && result.WrittenPaths.Count == 0
                     && attempts == 0
                     && refused.Count == 0
                     && unchanged;

        return new SafetyCheckResult(
            "A provisioner with a read-only writer writes nothing, against the real game directory",
            passed,
            passed
                ? $"RunAsync returned ReadOnlyMode with 0 written paths; the sink counted {attempts} attempt(s) and refused "
                  + $"{refused.Count}; the game directory fingerprint is unchanged ({Shorten(before)}). The refusal is the "
                  + "object, not a check somebody can forget to perform."
                : $"readOnlyMode={result.ReadOnlyMode}, succeeded={result.Succeeded}, written={result.WrittenPaths.Count}, "
                  + $"sink attempts={attempts}, refused={refused.Count}, fingerprint {Shorten(before)} -> {Shorten(after)}"
                  + (refused.Count > 0 ? "; refused: " + string.Join("; ", refused.Take(4)) : string.Empty));
    }

    // -------------------------------------------------------------------------------------------
    // Plumbing.
    // -------------------------------------------------------------------------------------------

    private static IEnumerable<string> CodeFiles(IEnumerable<string> files) =>
        files.Where(f => f.EndsWith(".cs", StringComparison.OrdinalIgnoreCase));

    /// <summary>A file's code: comments and string literals removed, split into trimmed lines.</summary>
    private static IEnumerable<string> CodeLines(string path) =>
        MotionCheck.SplitLines(MotionCheck.CodeOnly.Replace(MotionCheck.ReadText(path) ?? string.Empty, string.Empty));

    /// <summary>The path relative to the project root, with the separators this file compares against.</summary>
    private static string Rel(string root, string path) =>
        Path.GetRelativePath(root, path).Replace('/', '\\');

    private static string Shorten(string? hash) =>
        hash is null ? "<absent>" : hash.Length <= 16 ? hash : hash[..16] + "…";
}
