using System.Runtime.InteropServices;
using System.Text;

namespace Xsoz.Launcher.Diagnostics;

/// <summary>
/// Console plumbing for a GUI subsystem executable.
///
/// XsozClient.exe is a WinExe, so a double-clicked copy has no console to print to. A console-mode
/// flag run from a terminal, however, is exactly the case where a person wants to read the
/// output, so the parent console is attached on demand and stdout is reopened against it. The
/// probe write is deliberate: on a machine with no console at all the inherited stdout handle is
/// invalid, and writing to it throws. Catching that here means the caller always has a TextWriter
/// it can use without a try/catch of its own - it simply goes to the report file instead.
/// </summary>
internal static class HostConsole
{
    private const int AttachParentProcess = -1;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AttachConsole(int processId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AllocConsole();

    /// <summary>
    /// A writer that is attached to the parent console if one is there, or null when this process
    /// has nowhere to print. The caller must treat null as normal, not as an error.
    /// </summary>
    public static TextWriter? TryAttach()
    {
        try
        {
            if (!Console.IsOutputRedirected)
            {
                AttachConsole(AttachParentProcess);
            }

            var stdout = Console.OpenStandardOutput();
            var writer = new StreamWriter(stdout, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false))
            {
                AutoFlush = true,
            };

            // Probe. A WinExe started with no console at all gets an unusable stdout handle, and
            // this is the only reliable way to find out without swallowing every later write.
            writer.WriteLine();
            return writer;
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>Opens a private console window. Used only by --help when nothing else is available.</summary>
    public static void TryAllocate()
    {
        try
        {
            AllocConsole();
        }
        catch (Exception)
        {
            // A diagnostic tool that cannot show its own help text is not worth failing over.
        }
    }
}

/// <summary>Writes every line to several writers, so console and report file stay in step.</summary>
internal sealed class TeeWriter : TextWriter
{
    private readonly TextWriter[] _targets;

    /// <summary>Creates a tee over the given targets. Null targets are dropped.</summary>
    public TeeWriter(params TextWriter?[] targets) =>
        _targets = Array.FindAll(targets, t => t is not null)!;

    /// <inheritdoc />
    public override Encoding Encoding => Encoding.UTF8;

    /// <inheritdoc />
    public override void Write(char value)
    {
        foreach (var target in _targets)
        {
            target.Write(value);
        }
    }

    /// <inheritdoc />
    public override void Write(string? value)
    {
        foreach (var target in _targets)
        {
            target.Write(value);
        }
    }

    /// <inheritdoc />
    public override void WriteLine(string? value)
    {
        foreach (var target in _targets)
        {
            target.WriteLine(value);
        }
    }

    /// <inheritdoc />
    public override void Flush()
    {
        foreach (var target in _targets)
        {
            try
            {
                target.Flush();
            }
            catch (Exception)
            {
                // Output is best effort. A broken console handle must not fail the capture.
            }
        }
    }
}
