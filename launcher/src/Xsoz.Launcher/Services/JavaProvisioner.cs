using System.IO.Compression;
using System.Text.Json;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>
/// Provisions a per-version Java runtime into the launcher's own runtimes tree.
///
/// The machine has Java 25 on PATH, which 1.21.11 does not ask for, and JDK 17 for the tool
/// tree - neither is a target runtime. The launcher therefore never touches PATH and never
/// installs system-wide: JRE 21 comes from Adoptium's v3 binary endpoint as a zip, verifies
/// nothing from PATH, and lands under %LOCALAPPDATA%\XsozClient\runtimes\21\. The system Java
/// is left exactly as it was found.
/// </summary>
public sealed class JavaProvisioner
{
    private const string AdoptiumBinaryUrl =
        "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk";

    private readonly DownloadService _net;

    /// <summary>Creates the provisioner.</summary>
    public JavaProvisioner(DownloadService net) => _net = net;

    /// <summary>True when a usable javaw.exe already exists under the runtime root.</summary>
    public static bool IsProvisioned(string runtimeRoot) => FindJavaw(runtimeRoot) is not null;

    /// <summary>Finds the javaw.exe (preferred) or java.exe under a runtime root.</summary>
    public static string? FindJavaw(string runtimeRoot)
    {
        try
        {
            if (!Directory.Exists(runtimeRoot))
            {
                return null;
            }

            var javaws = Directory
                .EnumerateFiles(runtimeRoot, "javaw.exe", new EnumerationOptions
                {
                    RecurseSubdirectories = true,
                    IgnoreInaccessible = true,
                    MaxRecursionDepth = 4,
                })
                .Where(p => p.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}", StringComparison.OrdinalIgnoreCase))
                .OrderBy(p => p, StringComparer.OrdinalIgnoreCase)
                .ToArray();

            if (javaws.Length > 0)
            {
                return javaws[^1];
            }

            return LaunchService.FindProvisionedJava(runtimeRoot);
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>
    /// Downloads and extracts the Temurin 21 JRE. Reports progress through
    /// <paramref name="progress"/> and writes the README marker so a repair pass can explain
    /// what the folder is.
    /// </summary>
    /// <returns>The resolved javaw.exe path.</returns>
    public async Task<string> EnsureJava21Async(
        string runtimeRoot, Action<TransferProgress>? progress, Action<string>? log, CancellationToken ct)
    {
        var existing = FindJavaw(runtimeRoot);
        if (existing is not null)
        {
            log?.Invoke("Java 21 is already provisioned at " + existing);
            return existing;
        }

        // Primary path: the Adoptium v3 binary endpoint, which redirects to the asset. Fallback:
        // the version-pinned endpoint, once, then fail with a readable message.
        var zipPath = Path.Combine(runtimeRoot, "temurin-21-jre.zip");
        var downloaded = false;
        Exception? error = null;
        foreach (var url in new[]
                 {
                     AdoptiumBinaryUrl,
                     "https://api.adoptium.net/v3/binary/version/jdk-21+35/windows/x64/jre/hotspot/normal/eclipse?project=jdk",
                 })
        {
            try
            {
                log?.Invoke("Downloading Temurin 21 JRE from " + url);
                await _net.DownloadAsync(url, zipPath, -1, null, progress, ct).ConfigureAwait(false);
                downloaded = true;
                break;
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                error = ex;
                log?.Invoke("The Adoptium download failed (" + ex.Message + "); trying the pinned endpoint.");
            }
        }

        if (!downloaded)
        {
            throw new InvalidOperationException(
                "The Java 21 runtime could not be downloaded: " + (error?.Message ?? "no download succeeded."),
                error);
        }

        log?.Invoke("Extracting the runtime (this is 60-70 MB of zip; it takes a few seconds).");
        var extractRoot = Path.Combine(runtimeRoot, "jre21");
        Directory.CreateDirectory(extractRoot);

        await Task.Run(() =>
        {
            using (var zip = ZipFile.OpenRead(zipPath))
            {
                foreach (var entry in zip.Entries)
                {
                    ct.ThrowIfCancellationRequested();
                    if (string.IsNullOrEmpty(entry.Name))
                    {
                        continue; // directory entry
                    }

                    var dest = Path.Combine(extractRoot, entry.FullName.Replace('/', Path.DirectorySeparatorChar));
                    if (!AppPaths.IsInside(extractRoot, dest))
                    {
                        continue; // zip-slip guard: refuse any entry that escapes the extraction root
                    }

                    Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                    entry.ExtractToFile(dest, overwrite: true);
                }
            }
        }, ct).ConfigureAwait(false);

        DownloadService.TryDelete(zipPath);

        var resolved = FindJavaw(runtimeRoot)
                       ?? throw new InvalidOperationException(
                           "The runtime zip extracted but no javaw.exe was found inside it. "
                           + "The archive is not what Adoptium was expected to serve; the install cannot continue.");

        var versionBanner = await ProbeVersionTextAsync(resolved, ct).ConfigureAwait(false);
        log?.Invoke("Java ready: " + resolved + (versionBanner is null ? string.Empty : " (" + versionBanner + ")"));

        await File.WriteAllTextAsync(
            Path.Combine(runtimeRoot, "README.txt"),
            "This folder is owned by the XsozClient launcher.\r\n"
            + "It contains an Eclipse Temurin (Adoptium) JRE 21, downloaded from\r\n"
            + "  " + AdoptiumBinaryUrl + "\r\n"
            + "Temurin is GPL-2.0 with Classpath Exception; see the license files inside the\r\n"
            + "runtime tree. The system Java installation was not modified.\r\n",
            ct).ConfigureAwait(false);

        return resolved;
    }

    /// <summary>Runs <c>java -version</c> against a binary and returns its one-line banner.</summary>
    public static async Task<string?> ProbeVersionTextAsync(string javaExe, CancellationToken ct)
    {
        try
        {
            var psi = new System.Diagnostics.ProcessStartInfo
            {
                FileName = javaExe,
                Arguments = "-version",
                RedirectStandardError = true,
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };

            using var process = System.Diagnostics.Process.Start(psi);
            if (process is null)
            {
                return null;
            }

            var lines = new List<string>();
            while (!process.StandardError.EndOfStream)
            {
                var line = await process.StandardError.ReadLineAsync(ct).ConfigureAwait(false);
                if (line is not null)
                {
                    lines.Add(line);
                }
            }

            await process.WaitForExitAsync(ct).ConfigureAwait(false);
            return lines.Count > 0 ? lines[0] : null;
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>
    /// A Java installation found on this machine, with the version actually reported by the
    /// binary rather than by its directory name.
    /// </summary>
    /// <param name="ExecutablePath">The java.exe or javaw.exe.</param>
    /// <param name="Banner">The first line of <c>java -version</c>.</param>
    /// <param name="MajorVersion">The parsed feature release, or 0 when it could not be read.</param>
    public sealed record JavaInstallation(string ExecutablePath, string Banner, int MajorVersion);

    /// <summary>
    /// Finds every Java on this machine whose <c>-version</c> reports feature release
    /// <paramref name="requiredMajor"/>, and deliberately does not trust the system <c>java</c>.
    ///
    /// WHY THE MAJOR VERSION IS CHECKED RATHER THAN THE PATH. This machine has Oracle JDK 25 first
    /// on PATH, and 1.21.11 declares <c>javaVersion.majorVersion = 21</c>. Java 25 happens to run a
    /// Fabric profile, so a check that only asked "is there a java.exe" would pass it, and a check
    /// that trusted the directory name would be fooled by a folder called <c>jdk21</c> containing
    /// something else. So the binary is asked, and the number it prints is the number that counts.
    ///
    /// Nothing is installed and nothing is written. This is a read-only survey; provisioning a
    /// runtime from Adoptium into <c>%LOCALAPPDATA%\XsozClient\runtimes</c> is
    /// <see cref="EnsureJava21Async"/>'s job and is only reached when this returns nothing.
    ///
    /// The official launcher bundles its own <c>java-runtime-delta</c> JRE and defaults to it, so
    /// for the primary hand-off path no Java is needed from us at all. This exists for the
    /// self-provisioned fallback path and for the diagnostics screen.
    /// </summary>
    public static async Task<IReadOnlyList<JavaInstallation>> FindJavaMajorAsync(
        int requiredMajor, CancellationToken ct)
    {
        var found = new List<JavaInstallation>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var executable in EnumerateJavaExecutables())
        {
            ct.ThrowIfCancellationRequested();

            if (!seen.Add(executable))
            {
                continue;
            }

            var banner = await ProbeVersionTextAsync(executable, ct).ConfigureAwait(false);
            if (banner is null)
            {
                continue;
            }

            var major = ParseMajorVersion(banner);
            if (major == requiredMajor)
            {
                found.Add(new JavaInstallation(executable, banner, major));
            }
        }

        return found;
    }

    /// <summary>
    /// The feature release out of a <c>java -version</c> banner, or 0.
    /// Handles both spellings the JDK has used: <c>"21.0.2"</c> and the modern quoted
    /// <c>"21" 2023-09-19</c>, with or without a vendor prefix.
    /// </summary>
    public static int ParseMajorVersion(string banner)
    {
        if (string.IsNullOrWhiteSpace(banner))
        {
            return 0;
        }

        var open = banner.IndexOf('"');
        if (open < 0)
        {
            return 0;
        }

        var close = banner.IndexOf('"', open + 1);
        if (close <= open + 1)
        {
            return 0;
        }

        var token = banner[(open + 1)..close];
        var dot = token.IndexOf('.');
        var head = dot > 0 ? token[..dot] : token;

        // A pre-release or early-access build ("21-ea") is not a 21 release.
        return int.TryParse(head, out var major) ? major : 0;
    }

    /// <summary>
    /// Every plausible java.exe on this machine: the JDK/JRE roots Windows itself installs to and
    /// whatever PATH resolves to. Enumeration only - PATH is never executed, because an unqualified
    /// name on PATH is the one thing this method exists not to trust.
    /// </summary>
    public static IReadOnlyList<string> EnumerateJavaExecutables()
    {
        var paths = new List<string>();

        foreach (var root in ProgramFilesRoots())
        {
            foreach (var vendor in new[] { "Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft" })
            {
                var vendorRoot = Path.Combine(root, vendor);
                if (!Directory.Exists(vendorRoot))
                {
                    continue;
                }

                paths.AddRange(JavaExecutablesUnder(vendorRoot));
            }
        }

        var pathVariable = Environment.GetEnvironmentVariable("PATH") ?? string.Empty;
        foreach (var directory in pathVariable.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            var trimmed = directory.Trim().Trim('"');
            if (trimmed.Length == 0)
            {
                continue;
            }

            foreach (var name in new[] { "java.exe", "javaw.exe" })
            {
                var candidate = Path.Combine(trimmed, name);
                if (File.Exists(candidate))
                {
                    paths.Add(candidate);
                }
            }
        }

        return paths;
    }

    private static IEnumerable<string> JavaExecutablesUnder(string root)
    {
        try
        {
            return Directory
                .EnumerateFiles(root, "java.exe", new EnumerationOptions
                {
                    RecurseSubdirectories = true,
                    IgnoreInaccessible = true,
                    MaxRecursionDepth = 3,
                })
                .Where(p => p.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}", StringComparison.OrdinalIgnoreCase))
                .ToArray();
        }
        catch (Exception)
        {
            return [];
        }
    }

    private static IEnumerable<string> ProgramFilesRoots()
    {
        var roots = new List<string>();
        foreach (var variable in new[] { "ProgramFiles", "ProgramFiles(x86)" })
        {
            var value = Environment.GetEnvironmentVariable(variable);
            if (!string.IsNullOrWhiteSpace(value))
            {
                roots.Add(value);
            }
        }

        return roots;
    }
}
