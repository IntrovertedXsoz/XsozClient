using System.Globalization;
using System.Text;
using System.Text.Json;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Services;

namespace Xsoz.Launcher.Diagnostics;

/// <summary>One preflight check and its verdict.</summary>
/// <param name="Name">What was checked.</param>
/// <param name="Passed">True when the check held.</param>
/// <param name="Detail">The evidence, as it will be printed.</param>
public sealed record PreflightCheck(string Name, bool Passed, string Detail);

/// <summary>
/// The verification that runs inside the headless capture.
///
/// WHY IT LIVES HERE. The only non-interactive runs this project is permitted to make are
/// <c>dotnet build</c>, <c>dotnet publish</c> and <c>XsozClient.exe --screenshot &lt;dir&gt;</c>. A
/// separate harness executable would be a fifth kind of run and would need its own permission. So
/// the evidence is gathered by the capture that is already permitted, against the real services,
/// and written into the capture's own report.
///
/// WHAT IT PROVES, and how each claim is checked rather than asserted:
///
///   1. THE READ-ONLY GATE. The real <see cref="VanillaProvisioner"/> is asked to provision, for
///      real, against the real <c>%APPDATA%\.minecraft</c> - and it is handed a
///      <see cref="NoOpGameDirectoryWriter"/>. It must come back having written nothing, the sink
///      must report zero write attempts, and the game directory is fingerprinted before and after so
///      "no write occurred" is a measurement rather than a promise. This replaced an older drill
///      that used to run the real provisioner with the real writer and hope the launcher-running
///      safety gate would catch it. That hope was well founded on a machine with the official
///      launcher open and worthless on one without, and the day it was worthless it wrote a version
///      folder, eight jars and a profile into a real installation from a verification step. The
///      gate was never the protection; the sink is.
///   2. THE MERGE, against a copy. The real file is copied byte-for-byte into the launcher's own
///      verification folder and the real merge runs against THAT. Profile counts before and after
///      are printed, and the number of pre-existing profiles whose raw text changed must be zero.
///   3. THE FABRIC FETCH. The real endpoint, the real response, the real <c>inheritsFrom</c> and
///      <c>mainClass</c>.
///   4. THE JVM MERGE. The real vanilla 1.21.11 JSON plus the real fetched Fabric JSON through the
///      real merge, with the assertions it makes about <c>-cp</c>, <c>java.library.path</c> and the
///      main class printed.
///   5. THE ISOLATION. The game directory this product would register, and the fact that it is
///      outside the official launcher's directory.
///
/// Every write here lands under <c>%LOCALAPPDATA%\XsozClient\verification</c> or
/// <c>...\backups</c>. Nothing inside the official launcher's directory is ever written by this
/// class, whatever <paramref name="writer"/> is handed in - and it is only ever handed a no-op sink,
/// which is asserted rather than assumed.
/// </summary>
public static class ProvisioningPreflight
{
    /// <summary>
    /// Runs every check. Never throws; a failed check is a result, not a crash.
    /// </summary>
    /// <param name="output">Where the running commentary goes.</param>
    /// <param name="writer">
    /// The write capability the drill is given. Required and with no default, for the same reason
    /// <see cref="VanillaProvisioner"/> requires one: a call site that forgets has to fail to
    /// compile rather than quietly inherit the ability to write.
    /// </param>
    public static IReadOnlyList<PreflightCheck> Run(TextWriter output, IGameDirectoryWriter writer)
    {
        ArgumentNullException.ThrowIfNull(writer);
        ArgumentNullException.ThrowIfNull(output);

        var checks = new List<PreflightCheck>();
        var minecraft = MinecraftDirectory.Default;

        // The whole-run fingerprint. Taken before anything here and compared after everything, so
        // the claim is about the entire preflight and not just about the one phase that was easy to
        // instrument.
        var fingerprintBefore = FingerprintGameDirectory(minecraft);

        try
        {
            checks.AddRange(RunReadOnlyProof(output, writer));
        }
        catch (Exception ex)
        {
            checks.Add(new PreflightCheck("Read-only mode refuses to provision", false, "threw " + ex.GetType().Name + ": " + ex.Message));
        }

        try
        {
            checks.AddRange(RunMergeAgainstCopy(output));
        }
        catch (Exception ex)
        {
            checks.Add(new PreflightCheck("Profile merge (copy)", false, "threw " + ex.GetType().Name + ": " + ex.Message));
        }

        try
        {
            checks.AddRange(RunFabricAndJvmProof(output));
        }
        catch (Exception ex)
        {
            checks.Add(new PreflightCheck("Fabric fetch + JVM merge", false, "threw " + ex.GetType().Name + ": " + ex.Message));
        }

        var fingerprintAfter = FingerprintGameDirectory(minecraft);
        checks.Add(new PreflightCheck(
            "Nothing inside the game directory changed during this preflight",
            string.Equals(fingerprintBefore, fingerprintAfter, StringComparison.Ordinal),
            $"fingerprint before {Shorten(fingerprintBefore)}, after {Shorten(fingerprintAfter)} "
            + $"(profiles file, versions\\{VanillaProvisioner.VersionId} and libraries\\net\\fabricmc, "
            + "by path, length and last-write time)"));

        return checks;
    }

    // ------------------------------------------------------------------ 1. the read-only gate

    private static IEnumerable<PreflightCheck> RunReadOnlyProof(TextWriter output, IGameDirectoryWriter writer)
    {
        var minecraft = MinecraftDirectory.Default;
        var profilePath = minecraft.ProfilesFilePath;

        output.WriteLine();
        output.WriteLine("======== provisioning preflight (read-only) ========");
        output.WriteLine("game directory      : " + minecraft.Root + (minecraft.Exists ? "  (exists)" : "  (ABSENT)"));
        output.WriteLine("profile file        : " + Path.GetFileName(profilePath) + (minecraft.UsesLegacyStoreVariant ? "  (legacy store variant)" : string.Empty));
        output.WriteLine("installed versions  : " + minecraft.InstalledVersions.Count.ToString(CultureInfo.InvariantCulture));
        output.WriteLine("process policy      : " + GameWritePolicy.Describe());
        output.WriteLine("writer handed over  : " + writer.Name + "  (CanWrite=" + writer.CanWrite + ")");
        output.WriteLine("legacy env var      : " + ScreenshotRunner.LegacyPreflightVariableState
                          + "  (read for the record only)");

        // The launcher-running gate is still reported, because it is still a real piece of the
        // interactive path's behaviour and somebody reading this report should be able to see it.
        var hits = LauncherProcessWatch.Find(minecraft.Root);
        output.WriteLine("launcher-running gate: " + LauncherProcessWatch.Describe(minecraft.Root, hits)
                          + "  -> WritesAllowed()=" + VanillaProvisioner.WritesAllowed());
        foreach (var hit in hits.Take(8))
        {
            output.WriteLine("    pid " + hit.ProcessId + "  " + hit.ProcessName + "  (" + hit.Reason + ")");
        }

        // ---- the hash that makes "nothing was written" measurable.
        var before = HashOf(profilePath);
        output.WriteLine("launcher_profiles.json sha256 BEFORE : " + (before ?? "<absent>"));

        // ---- the real provisioner, asked to provision, given a sink that cannot.
        var net = new DownloadService();
        var provisioner = new VanillaProvisioner(net, writer, minecraft);
        var result = provisioner.RunAsync(CancellationToken.None).GetAwaiter().GetResult();

        var after = HashOf(profilePath);
        output.WriteLine("launcher_profiles.json sha256 AFTER  : " + (after ?? "<absent>"));
        output.WriteLine("provision result    : " + (result.ReadOnlyMode ? "REFUSED - read-only mode" : result.Succeeded ? "SUCCEEDED" : "refused/failed")
                          + "  readOnlyMode=" + result.ReadOnlyMode
                          + "  blockedByRunningLauncher=" + result.BlockedByRunningLauncher);
        output.WriteLine("provision message   : " + result.Message);
        output.WriteLine("paths written       : " + (result.WrittenPaths.Count == 0 ? "none" : string.Join("; ", result.WrittenPaths)));
        output.WriteLine("sink write attempts : " + writer.WriteAttempts);

        if (writer.RefusedOperations.Count > 0)
        {
            foreach (var refused in writer.RefusedOperations)
            {
                output.WriteLine("  REFUSED: " + refused);
            }
        }

        var refusedByMode = result.ReadOnlyMode && !result.Succeeded && result.WrittenPaths.Count == 0;
        var zeroAttempts = writer.WriteAttempts == 0 && writer.RefusedOperations.Count == 0;
        var hashUnchanged = string.Equals(before, after, StringComparison.Ordinal);

        yield return new PreflightCheck(
            "Read-only mode refuses to provision, before any read, request or write",
            refusedByMode,
            refusedByMode
                ? "VanillaProvisioner.RunAsync returned ReadOnlyMode with 0 written paths against a "
                  + "writer that cannot write: \"" + result.Message + "\""
                : "the run was not refused by the read-only writer: readOnlyMode=" + result.ReadOnlyMode
                  + ", succeeded=" + result.Succeeded + ", written=" + result.WrittenPaths.Count
                  + " (" + result.Message + ")");

        yield return new PreflightCheck(
            "The read-only sink recorded zero write attempts",
            zeroAttempts,
            zeroAttempts
                ? "NoOpGameDirectoryWriter counted 0 mutating calls and refused 0. The provisioner returned "
                  + "before it made any, so the guarantee is structural rather than conditional."
                : writer.WriteAttempts + " write attempt(s) and " + writer.RefusedOperations.Count
                  + " refusal(s) reached the sink: " + string.Join("; ", writer.RefusedOperations.Take(4)));

        yield return new PreflightCheck(
            "launcher_profiles.json is byte-identical across the refused run",
            hashUnchanged,
            hashUnchanged ? "sha256 unchanged: " + Shorten(before) : "sha256 CHANGED: " + Shorten(before) + " -> " + Shorten(after));

        yield return new PreflightCheck(
            "The read-only decision does not come from an environment variable",
            !GameWritePolicy.AllowsGameWrites,
            $"GameWritePolicy.AllowsGameWrites={GameWritePolicy.AllowsGameWrites} (source: {GameWritePolicy.Reason}). "
            + "The permission is a process fact derived from the command line, and it starts denied.");
    }

    // ------------------------------------------------------------------ 2. the merge, on a copy

    private static IEnumerable<PreflightCheck> RunMergeAgainstCopy(TextWriter output)
    {
        var minecraft = MinecraftDirectory.Default;
        if (!minecraft.ProfilesFileExists)
        {
            yield return new PreflightCheck("Profile merge against a copy", false,
                "no launcher_profiles.json on this machine to copy");
            yield break;
        }

        Directory.CreateDirectory(AppPaths.Verification);
        var copyPath = Path.Combine(AppPaths.Verification, "launcher_profiles.copy.json");
        File.Copy(minecraft.ProfilesFilePath, copyPath, overwrite: true);

        var beforeCount = LauncherProfilesWriter.ReadProfiles(File.ReadAllBytes(copyPath), out var before);

        // The gate here is deliberately OPEN, because the point of this check is the merge's own
        // behaviour, not the gate's. The gate's refusal is check 1 above, on the real file.
        var outcome = LauncherProfilesWriter.Merge(
            copyPath,
            new VanillaProfileSpec(
                VanillaProvisioner.ProfileKey,
                VanillaProvisioner.ProfileKey,
                VanillaProvisioner.VersionId,
                VanillaProvisioner.IsolatedGameDir,
                DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:sszzz", CultureInfo.InvariantCulture)),
            static () => true,
            Path.Combine(AppPaths.Verification, "backups"));

        var afterCount = LauncherProfilesWriter.ReadProfiles(File.ReadAllBytes(copyPath), out var after);

        output.WriteLine();
        output.WriteLine("merge drill, against a COPY at " + copyPath);
        output.WriteLine("  profiles BEFORE   : " + beforeCount);
        output.WriteLine("  profiles AFTER    : " + afterCount);
        output.WriteLine("  existing changed  : " + outcome.ExistingChanged);
        output.WriteLine("  backup taken      : " + (outcome.BackupPath ?? "none"));
        output.WriteLine("  outcome           : " + outcome.Message);

        // A sample of the user's real profiles, printed before/after, so the claim is legible and
        // not just a number somebody has to trust. Our own key is excluded from the sample for the
        // same reason LauncherProfilesWriter excludes it from ExistingChanged: the merge rewriting
        // OUR member is the merge doing its job, and printing it as CHANGED would put a red word
        // next to the one line the user is supposed to be able to trust.
        output.WriteLine("  sample of preserved profiles (this launcher's own key excluded):");
        foreach (var key in before.Keys.Where(k => k != VanillaProvisioner.ProfileKey).Take(5))
        {
            var unchanged = after.TryGetValue(key, out var raw) && string.Equals(raw, before[key], StringComparison.Ordinal);
            output.WriteLine("    " + (unchanged ? "unchanged" : "CHANGED  ") + "  " + key
                             + "  lastVersionId=" + LastVersionIdOf(raw ?? before[key]));
        }

        // ---- idempotency: run it again on the SAME copy with the SAME content, and assert the file
        // ---- came out byte-identical rather than gaining a second member of the same name.
        var bytesAfterFirst = File.ReadAllBytes(copyPath);
        var second = LauncherProfilesWriter.Merge(
            copyPath,
            new VanillaProfileSpec(
                VanillaProvisioner.ProfileKey,
                VanillaProvisioner.ProfileKey,
                VanillaProvisioner.VersionId,
                VanillaProvisioner.IsolatedGameDir,
                DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:sszzz", CultureInfo.InvariantCulture)),
            static () => true,
            Path.Combine(AppPaths.Verification, "backups"));

        var bytesAfterSecond = File.ReadAllBytes(copyPath);
        var thirdCount = LauncherProfilesWriter.ReadProfiles(bytesAfterSecond, out _);
        var duplicateMembers = CountKeyMembers(copyPath, VanillaProvisioner.ProfileKey);

        output.WriteLine("  idempotency rerun : profiles AFTER = " + thirdCount
                         + "  noOp=" + second.NoOp
                         + "  byte-identical=" + bytesAfterFirst.SequenceEqual(bytesAfterSecond)
                         + "  '" + VanillaProvisioner.ProfileKey + "' members=" + duplicateMembers);

        // The isolation, read as a VALUE rather than matched as text: the raw JSON has its
        // backslashes escaped, so a substring test against a Windows path would be wrong in the
        // one direction that matters.
        var gameDirValue = after.TryGetValue(VanillaProvisioner.ProfileKey, out var ours)
            ? ReadGameDir(ours)
            : null;
        var gameDirOk = gameDirValue is not null
                        && string.Equals(
                            Path.GetFullPath(gameDirValue),
                            Path.GetFullPath(VanillaProvisioner.IsolatedGameDir),
                            StringComparison.OrdinalIgnoreCase);

        output.WriteLine("  merged gameDir     : " + (gameDirValue ?? "<absent>"));

        yield return new PreflightCheck(
            "The merge drill's copy lives outside the game directory",
            !AppPaths.IsInside(minecraft.Root, copyPath),
            copyPath + " is inside " + minecraft.Root + ": " + AppPaths.IsInside(minecraft.Root, copyPath));

        // The expected count depends on whether our own profile was already there, and getting that
        // wrong would leave this check permanently red on any machine where the install has already
        // happened - which is every machine that has used the product. A guard that is always failing
        // is not a guard. The property being asserted is the one that actually matters: the merge
        // changes the profile count by at most one, and only by adding the member it owns.
        var oursWasAlreadyPresent = before.ContainsKey(VanillaProvisioner.ProfileKey);
        var expectedCount = beforeCount + (oursWasAlreadyPresent ? 0 : 1);

        yield return new PreflightCheck(
            "Merge adds exactly one profile to the copy, or refreshes ours without changing the count",
            outcome.Succeeded && afterCount == expectedCount,
            $"{beforeCount} -> {afterCount} profiles, expected {expectedCount} ('{VanillaProvisioner.ProfileKey}' was "
            + (oursWasAlreadyPresent
                ? "already registered, so the count must not change and the member must be refreshed in place"
                : "absent, so exactly one member is added")
            + "); merge reported " + outcome.Succeeded + (outcome.Refreshed ? ", refreshed" : outcome.NoOp ? ", no-op" : string.Empty));

        yield return new PreflightCheck(
            "Zero existing profiles changed",
            outcome.ExistingChanged == 0,
            outcome.ExistingChanged + " of " + beforeCount + " pre-existing profiles differ byte-for-byte");

        yield return new PreflightCheck(
            "gameDir isolation is present in the merged profile",
            gameDirOk,
            "profiles['" + VanillaProvisioner.ProfileKey + "'].gameDir = " + (gameDirValue ?? "<absent>"));

        yield return new PreflightCheck(
            "Merge is idempotent (re-run does not duplicate)",
            thirdCount == afterCount && second.NoOp && duplicateMembers == 1
                          && bytesAfterFirst.SequenceEqual(bytesAfterSecond),
            $"second run left {thirdCount} profiles; noOp={second.NoOp}; "
            + $"'{VanillaProvisioner.ProfileKey}' appears as {duplicateMembers} member(s); "
            + $"file byte-identical={bytesAfterFirst.SequenceEqual(bytesAfterSecond)}");
    }

    /// <summary>
    /// How many times a profile key appears as a MEMBER of the profiles object. The parsed profile
    /// count cannot see a duplicate, because a dictionary keyed by name collapses it - so a merge
    /// that accidentally appended a second member would look like a clean run. This counts member
    /// names instead: an occurrence of the quoted key immediately followed by a colon is a member
    /// name, and one followed by a comma is a value. Our own profile has the key as its
    /// <c>name</c> value as well as as its key, so counting every occurrence would report two.
    /// </summary>
    private static int CountKeyMembers(string path, string key)
    {
        try
        {
            var text = File.ReadAllText(path);
            var profiles = text.IndexOf("\"profiles\"", StringComparison.Ordinal);
            if (profiles < 0)
            {
                return 0;
            }

            var needle = "\"" + key + "\"";
            var count = 0;

            for (var i = text.IndexOf(needle, profiles, StringComparison.Ordinal);
                 i >= 0;
                 i = text.IndexOf(needle, i + needle.Length, StringComparison.Ordinal))
            {
                var j = i + needle.Length;
                while (j < text.Length && char.IsWhiteSpace(text[j]))
                {
                    j++;
                }

                if (j < text.Length && text[j] == ':')
                {
                    count++;
                }
            }

            return count;
        }
        catch (Exception)
        {
            return 0;
        }
    }

    private static string? ReadGameDir(string rawJson)
    {
        try
        {
            using var doc = JsonDocument.Parse(rawJson);
            return doc.RootElement.TryGetProperty("gameDir", out var gameDir) ? gameDir.GetString() : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    // ------------------------------------------------------------------ 3 + 4. the fetch and the JVM merge

    private static IEnumerable<PreflightCheck> RunFabricAndJvmProof(TextWriter output)
    {
        var net = new DownloadService();
        var url = VanillaProvisioner.FabricProfileUrl;
        var json = net.GetStringAsync(url, CancellationToken.None).GetAwaiter().GetResult();

        string inheritsFrom;
        string mainClass;
        int libraryCount;
        using (var probe = JsonDocument.Parse(json))
        {
            inheritsFrom = probe.RootElement.GetProperty("inheritsFrom").GetString() ?? string.Empty;
            mainClass = probe.RootElement.GetProperty("mainClass").GetString() ?? string.Empty;
            libraryCount = probe.RootElement.GetProperty("libraries").GetArrayLength();
        }

        output.WriteLine();
        output.WriteLine("fabric fetch, " + url);
        output.WriteLine("  inheritsFrom : " + inheritsFrom);
        output.WriteLine("  mainClass    : " + mainClass);
        output.WriteLine("  libraries    : " + libraryCount);
        output.WriteLine("  version id   : " + VanillaProvisioner.VersionId);
        output.WriteLine("  profile key  : " + VanillaProvisioner.ProfileKey);
        output.WriteLine("  isolated dir : " + VanillaProvisioner.IsolatedGameDir);
        output.WriteLine("  java runtime : none required - the official launcher uses its own bundled JRE");

        var vanillaJsonPath = MinecraftDirectory.Default.VersionJsonPath(inheritsFrom);
        string? merged = null;
        JvmMergeReport? report = null;
        if (File.Exists(vanillaJsonPath))
        {
            using var vanillaDoc = JsonDocument.Parse(
                File.ReadAllText(vanillaJsonPath),
                new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip });

            merged = JvmFlagMerge.Merge(
                json, vanillaDoc.RootElement, VanillaProvisioner.VersionId, VanillaProvisioner.IsolatedGameDir, out report);
        }

        if (report is null)
        {
            yield return new PreflightCheck("Fabric fetch", json.Length > 0,
                "Fetched " + json.Length + " bytes; inheritsFrom=" + inheritsFrom + ", mainClass=" + mainClass);
            yield return new PreflightCheck("JVM flag merge", false,
                "the vanilla " + inheritsFrom + " JSON is not installed here, so the merge could not be asserted");
            yield break;
        }

        output.WriteLine("  jvm merge    : " + report.Message);
        output.WriteLine("    vanilla args kept (" + report.VanillaJvmArgs.Count + "):");
        foreach (var arg in report.VanillaJvmArgs.Take(6))
        {
            output.WriteLine("      " + arg);
        }

        output.WriteLine("    fabric args kept (" + report.FabricJvmArgs.Count + "):");
        foreach (var arg in report.FabricJvmArgs)
        {
            output.WriteLine("      " + arg);
        }

        output.WriteLine("    injected (" + report.InjectedFlags.Count + "):");
        foreach (var arg in report.InjectedFlags)
        {
            output.WriteLine("      " + arg);
        }

        yield return new PreflightCheck(
            "Fabric endpoint returns inheritsFrom " + VanillaProvisioner.VanillaVersionId,
            inheritsFrom == VanillaProvisioner.VanillaVersionId,
            "GET " + url + " -> inheritsFrom=" + inheritsFrom);

        yield return new PreflightCheck(
            "Fabric endpoint returns mainClass KnotClient",
            mainClass == JvmFlagMerge.FabricMainClass,
            "mainClass=" + mainClass);

        var classpathEntries = report.EffectiveJvmArgs.Count(a =>
            a.StartsWith("-cp", StringComparison.Ordinal) || a.StartsWith("-classpath", StringComparison.Ordinal));

        yield return new PreflightCheck(
            "JVM merge keeps exactly one -cp entry",
            classpathEntries == 1,
            classpathEntries + " classpath entr(y/ies) in " + report.EffectiveJvmArgs.Count + " effective arguments");

        yield return new PreflightCheck(
            "JVM merge keeps Mojang's -Djava.library.path",
            report.EffectiveJvmArgs.Any(a => a.StartsWith("-Djava.library.path=", StringComparison.Ordinal)),
            report.EffectiveJvmArgs.FirstOrDefault(a => a.StartsWith("-Djava.library.path=", StringComparison.Ordinal)) ?? "absent");

        yield return new PreflightCheck(
            "JVM merge keeps Fabric's main class",
            report.MainClass == JvmFlagMerge.FabricMainClass,
            report.MainClass);

        yield return new PreflightCheck(
            "No injected flag touches timing or Java-25-only features",
            JvmFlagMerge.FindForbiddenFlag(report.InjectedFlags) is null,
            "reviewed flag list checked against tick-rate / input-timing / packet fragments and UseCompactObjectHeaders");

        var versionGameDir = merged is null ? null : ReadTopLevelGameDir(merged);
        var versionGameDirOk = versionGameDir is not null
                               && string.Equals(
                                   Path.GetFullPath(versionGameDir),
                                   Path.GetFullPath(VanillaProvisioner.IsolatedGameDir),
                                   StringComparison.OrdinalIgnoreCase);

        yield return new PreflightCheck(
            "Version JSON to write carries the gameDir",
            merged is not null && versionGameDirOk,
            merged is null
                ? "merge failed: " + report.Message
                : $"merged document is {merged.Length} bytes; gameDir = {versionGameDir ?? "<absent>"}");

        yield return new PreflightCheck(
            "Version JSON appends flags to arguments.jvm without losing Fabric's own entry",
            merged is not null && merged.Contains("-DFabricMcEmu=", StringComparison.Ordinal)
                                 && JvmFlagMerge.BuildInjectedFlags().All(f => merged.Contains(f, StringComparison.Ordinal)),
            "arguments.jvm is appended to, not replaced");
    }

    private static string? ReadTopLevelGameDir(string versionJson)
    {
        try
        {
            using var doc = JsonDocument.Parse(versionJson);
            return doc.RootElement.TryGetProperty("gameDir", out var gameDir) ? gameDir.GetString() : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static string? HashOf(string path)
    {
        try
        {
            if (!File.Exists(path))
            {
                return null;
            }

            using var stream = File.OpenRead(path);
            return Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(stream));
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>
    /// A fingerprint of everything inside the official launcher's directory that provisioning is
    /// capable of touching: the profile file, the version folder this product owns, and the whole
    /// <c>libraries\net\fabricmc</c> tree. Path, length and last-write time for each, in a stable
    /// order, hashed.
    /// <para>
    /// Content rather than mtime alone, and every file rather than the handful the merge mentions,
    /// because the incident this guards against was invisible to a check that looked at one file's
    /// hash: thirteen profiles were byte-identical and eight jars had been written. A fingerprint
    /// that cannot see a new jar is not evidence of anything.
    /// </para>
    /// <para>
    /// Absent folders contribute their absence rather than throwing, so this is also a valid
    /// fingerprint on a machine that has never run the official launcher at all.
    /// </para>
    /// <para>
    /// Internal rather than private because <c>--safecheck</c> measures the same thing without
    /// rendering a screenshot. One implementation, two callers, so the build agent and the capture
    /// are provably looking at the same evidence.
    /// </para>
    /// </summary>
    internal static string FingerprintGameDirectory(MinecraftDirectory minecraft)
    {
        var lines = new List<string>();
        AppendFileFingerprint(lines, minecraft.ProfilesFilePath);
        AppendTreeFingerprint(lines, minecraft.VersionJsonPath(VanillaProvisioner.VersionId), includeParents: true);
        AppendTreeFingerprint(lines, Path.Combine(minecraft.LibrariesRoot, "net", "fabricmc"), includeParents: false);
        lines.Sort(StringComparer.Ordinal);

        return Convert.ToHexStringLower(
            System.Security.Cryptography.SHA256.HashData(
                System.Text.Encoding.UTF8.GetBytes(string.Join('\n', lines))));
    }

    private static void AppendFileFingerprint(List<string> lines, string path)
    {
        try
        {
            if (!File.Exists(path))
            {
                lines.Add("absent " + path);
                return;
            }

            var info = new FileInfo(path);
            lines.Add($"{info.Length} {info.LastWriteTimeUtc.Ticks} {HashOf(path)} {path}");
        }
        catch (Exception ex)
        {
            lines.Add("unreadable " + path + " " + ex.GetType().Name);
        }
    }

    /// <summary>
    /// Fingerprints a folder recursively. <paramref name="includeParents"/> decides whether the
    /// folder's own existence is part of the fingerprint - which matters for the version folder,
    /// whose mere appearance is the first thing a provisioning run does.
    /// </summary>
    private static void AppendTreeFingerprint(List<string> lines, string leaf, bool includeParents)
    {
        try
        {
            var directory = includeParents ? Path.GetDirectoryName(leaf) ?? leaf : leaf;
            if (!Directory.Exists(directory))
            {
                lines.Add("absent " + directory);
                return;
            }

            foreach (var file in Directory.EnumerateFiles(directory, "*", SearchOption.AllDirectories)
                         .OrderBy(p => p, StringComparer.OrdinalIgnoreCase))
            {
                AppendFileFingerprint(lines, file);
            }
        }
        catch (Exception ex)
        {
            lines.Add("unreadable " + leaf + " " + ex.GetType().Name);
        }
    }

    private static string Shorten(string? hash) =>
        hash is null ? "<absent>" : hash.Length <= 16 ? hash : hash[..16] + "…";

    private static string LastVersionIdOf(string rawJson)
    {
        try
        {
            using var doc = JsonDocument.Parse(rawJson);
            return doc.RootElement.TryGetProperty("lastVersionId", out var v) ? v.GetString() ?? string.Empty : string.Empty;
        }
        catch (JsonException)
        {
            return string.Empty;
        }
    }

    /// <summary>Writes the check table into the capture report.</summary>
    public static void AppendTo(StringBuilder report, IReadOnlyList<PreflightCheck> checks)
    {
        if (checks.Count == 0)
        {
            return;
        }

        report.AppendLine();
        report.AppendLine("================ provisioning preflight ================");
        foreach (var check in checks)
        {
            report.AppendLine($"[{(check.Passed ? "PASS" : "FAIL")}] {check.Name}");
            report.AppendLine("       " + check.Detail);
        }

        var failed = checks.Count(c => !c.Passed);
        report.AppendLine();
        report.AppendLine(failed == 0
            ? $"All {checks.Count} preflight checks passed."
            : $"{failed} of {checks.Count} preflight checks failed.");
    }
}