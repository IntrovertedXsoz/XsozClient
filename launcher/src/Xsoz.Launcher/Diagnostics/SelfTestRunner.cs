using System.Text;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Services;

namespace Xsoz.Launcher.Diagnostics;

/// <summary>
/// The install-engine self-test: <c>--selftest</c>. Runs the real <see cref="InstallService"/>
/// against the real endpoints from the console, with the same progress feed the UI shows, and
/// then prints the launch plan the Play button would run. No window is created. This exists so
/// that the install path can be verified headlessly; it changes nothing about the interactive
/// path, which runs the identical service class.
///
/// Two environment knobs exist ONLY for this mode, never for the interactive app:
///   XSOZ_SELFTEST_CANCEL_MS  - cancel the run after N milliseconds (cancellation drill)
///   XSOZ_SELFTEST_MANIFEST   - override the version-manifest URL (breaks it, to drill the
///                              human-readable failure path)
/// </summary>
public static class SelfTestRunner
{
    /// <summary>Runs the self-test. Returns the process exit code.</summary>
    public static int Run(TextWriter output)
    {
        output.WriteLine("XsozClient install self-test");
        output.Flush();

        var net = new DownloadService();
        var install = new InstallService(net);

        var cts = new CancellationTokenSource();
        var cancelMs = Environment.GetEnvironmentVariable("XSOZ_SELFTEST_CANCEL_MS");
        if (int.TryParse(cancelMs, out var ms) && ms > 0)
        {
            cts.CancelAfter(ms);
            output.WriteLine($"[selftest] cancel scheduled after {ms} ms");
        }

        var manifest = Environment.GetEnvironmentVariable("XSOZ_SELFTEST_MANIFEST");
        if (!string.IsNullOrWhiteSpace(manifest))
        {
            output.WriteLine($"[selftest] manifest override in effect: {manifest}");
            InstallService.ManifestUrlOverride = manifest;
        }

        var lastLine = 0L;
        var lastLog = string.Empty;
        install.Tick += (_, tick) =>
        {
            var pct = tick.TotalBytes > 0 ? $" {tick.OverallFraction,6:P0}" : string.Empty;
            var line = $"[{tick.Stage,-12}]{pct}  {tick.ReceivedBytes / 1048576.0,9:0.0} MB"
                       + (tick.TotalBytes > 0 ? $" / {tick.TotalBytes / 1048576.0:0.0} MB" : string.Empty)
                       + $"  {tick.BytesPerSecond / 1048576.0,5:0.0} MB/s  {tick.CurrentFile}";
            if (line.Length != lastLine && Environment.TickCount64 % 16 == 0)
            {
                output.WriteLine(line);
                lastLine = line.Length;
            }

            if (!string.IsNullOrWhiteSpace(tick.LogLine) && tick.LogLine != lastLog)
            {
                lastLog = tick.LogLine;
                output.WriteLine("  · " + tick.LogLine);
            }
        };

        try
        {
            var result = install.RunAsync(cts.Token).GetAwaiter().GetResult();
            output.WriteLine();
            output.WriteLine(result.Succeeded ? "SELFTEST: INSTALL COMPLETE" : result.Cancelled ? "SELFTEST: CANCELLED CLEANLY" : "SELFTEST: INSTALL FAILED");
            output.WriteLine(result.Summary);
            output.WriteLine($"downloaded this run: {result.TotalBytesReceived / 1048576.0:0.0} MB");

            if (result.Succeeded)
            {
                var plan = new LaunchService(new InstanceScanner())
                    .Plan(
                        new ProfileService().SelectedVersion,
                        new Models.LauncherSettings { AcceptedDisclaimer = true },
                        new Models.Profile(),
                        Environment.UserName);
                output.WriteLine();
                output.WriteLine(plan.CanLaunch
                    ? "LAUNCH PLAN: assembled OK"
                    : $"LAUNCH PLAN: blocked - {plan.BlockerDetail}");
                if (plan.Command is { } cmd)
                {
                    output.WriteLine("program : " + cmd.Program);

                    // Redacted before it is printed: an online launch's argument list carries a
                    // real bearer token, and a support harness that prints it to a console is a
                    // token on someone's scrollback.
                    var args = AppLog.Redact(cmd.Arguments);
                    output.WriteLine("args    : " + (args.Length > 400 ? args[..400] + "…" : args));
                }

                // XSOZ_SELFTEST_LAUNCH=1 actually starts the game and watches it briefly. The
                // output lines the game process writes are the evidence the launch is real.
                if (string.Equals(Environment.GetEnvironmentVariable("XSOZ_SELFTEST_LAUNCH"), "1", StringComparison.Ordinal) && plan.CanLaunch)
                {
                    output.WriteLine();
                    output.WriteLine("LAUNCHING the game process for up to 90 seconds…");
                    var launch = new LaunchService(new InstanceScanner());
                    var started = launch.Start(plan);
                    if (started.ProcessId is null)
                    {
                        output.WriteLine("SELFTEST: process did not start - " + started.BlockerDetail);
                        return 1;
                    }

                    var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(90);
                    while (DateTime.UtcNow < deadline && launch.Running is not null)
                    {
                        System.Threading.Thread.Sleep(500);
                    }

                    var exitedNormally = launch.Running is null;
                    if (!exitedNormally)
                    {
                        try { launch.Running!.Kill(entireProcessTree: true); } catch (Exception) { /* closing shop */ }
                    }

                    var lines = AppLog.Shared.Snapshot().ToList();
                    output.WriteLine($"process {(exitedNormally ? "exited on its own" : "was still running; terminated by the self-test")}");
                    output.WriteLine("game output lines seen: " + lines.Count);
                    foreach (var entry in lines.TakeLast(12))
                    {
                        output.WriteLine("  " + entry.Message);
                    }

                    return lines.Count > 5 && !planError(plan) ? 0 : 3;
                }

                return plan.CanLaunch ? 0 : 2;
            }

            return result.Cancelled ? 130 : 1;
        }
        catch (Exception ex)
        {
            output.WriteLine("SELFTEST: FAILED WITH AN UNHANDLED ERROR");
            output.WriteLine(ex.ToString());
            return 1;
        }
    }

    private static bool planError(LaunchPlan plan) => plan.Blocker != LaunchBlocker.None;
}
