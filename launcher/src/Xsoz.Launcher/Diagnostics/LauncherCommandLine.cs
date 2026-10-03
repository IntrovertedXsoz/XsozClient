using System.Text;

namespace Xsoz.Launcher.Diagnostics;

/// <summary>What the launcher's command line asked for.</summary>
public enum CommandLineMode
{
    /// <summary>No switch: start the interactive launcher, exactly as a double-click does.</summary>
    Interactive,

    /// <summary>Print the help text and exit.</summary>
    Help,

    /// <summary>Render every screen to PNG and exit. No window, no display, no user input.</summary>
    Screenshot,

    /// <summary>Run the real install engine from the console, then exit. A support harness.</summary>
    SelfTest,

    /// <summary>
    /// Check the motion system against the project's own three rules, by reading the source.
    /// No window, no display, no network, no user input, nothing written.
    /// </summary>
    MotionCheck,

    /// <summary>
    /// Check by reading the source that no non-interactive mode can reach the disk writes behind
    /// provisioning, and prove at runtime that a read-only writer records zero attempts.
    /// No window, no display, no network, nothing written.
    /// </summary>
    SafeCheck,

    /// <summary>Print a message and exit non-zero. Only produced by a malformed known switch.</summary>
    Error,
}

/// <summary>The parsed command line.</summary>
/// <param name="Mode">What to do.</param>
/// <param name="OutputDirectory">Destination for the screenshot mode, or null.</param>
/// <param name="SourceDirectory">Project directory for the motion and safety checks, or null to find it.</param>
/// <param name="Message">
/// A line to show the user, or null. In <see cref="CommandLineMode.Interactive"/> this is a
/// warning about an unrecognised switch, never a stop: an unrecognised argument does not stop
/// somebody from starting their launcher.
/// </param>
/// <param name="AllowsGameWrites">
/// Whether this run may write into the official Minecraft launcher's directory.
/// <para>
/// THE DEFAULT IS <c>false</c>, and that is the entire design: a non-interactive mode gets the
/// safe state by omitting an argument rather than by remembering to pass one. There is exactly one
/// place in the parse that ever passes <c>true</c> - <see cref="Interactive"/>, below - so no
/// future switch added to this file can accidentally acquire write permission the way
/// <c>--screenshot</c> once did, through an environment variable nobody set.
/// </para>
/// </param>
public sealed record CommandLineParse(
    CommandLineMode Mode,
    string? OutputDirectory,
    string? SourceDirectory,
    string? Message,
    bool AllowsGameWrites = false);

/// <summary>
/// The launcher's command line.
///
/// This is a support tool surface, not a scripting surface. There is one non-interactive mode,
/// and it is the one a machine with no capturable desktop needs in order for anyone to see the
/// interface: it renders the real visual tree to PNG in software, with no window and no display.
///
/// Nothing here is consulted on the ordinary path except a single prefix test, so an interactive
/// launch is byte-for-byte the launch it was before this file existed.
///
/// EVERY MODE EXCEPT A BARE LAUNCH IS READ-ONLY, and that is a property of this file's shape rather
/// than of any argument: <see cref="CommandLineParse.AllowsGameWrites"/> defaults to false, and the
/// one method that passes true is <see cref="Interactive"/>. Adding a new diagnostic switch to this
/// file therefore cannot grant it the ability to write into somebody's Minecraft installation,
/// which is the failure a screenshot run once produced when its protection was an environment
/// variable that had to be set in the right shell session.
/// </summary>
public static class LauncherCommandLine
{
    /// <summary>Switch that starts the headless capture.</summary>
    public const string ScreenshotSwitch = "--screenshot";

    /// <summary>Switch that runs the install engine end-to-end from the console.</summary>
    public const string SelfTestSwitch = "--selftest";

    /// <summary>Switch that checks the motion system against the source.</summary>
    public const string MotionCheckSwitch = "--motioncheck";

    /// <summary>Switch that checks the read-only guarantee against the source and at runtime.</summary>
    public const string SafeCheckSwitch = "--safecheck";

    /// <summary>The help text, also used by the console attachment path.</summary>
    public static string HelpText { get; } = BuildHelpText();

    /// <summary>
    /// The one interactive result. Every other mode is read-only, and says so by not passing the
    /// flag - which is why there is exactly one call site in this file that can grant write
    /// permission and it is this line.
    /// </summary>
    private static CommandLineParse Interactive(string? message) =>
        new(CommandLineMode.Interactive, null, null, message, AllowsGameWrites: true);

    /// <summary>
    /// Parses <paramref name="args"/>. Never throws and never returns null.
    /// <para>
    /// Only <see cref="Interactive"/> carries write permission. <c>--help</c>, <c>--screenshot</c>,
    /// <c>--selftest</c>, <c>--motioncheck</c>, <c>--safecheck</c>, a malformed switch and an
    /// unrecognised one all fall through to the record's read-only default.
    /// </para>
    /// </summary>
    public static CommandLineParse Parse(string[] args)
    {
        if (args is null || args.Length == 0)
        {
            return Interactive(null);
        }

        for (int i = 0; i < args.Length; i++)
        {
            var arg = args[i];
            if (string.IsNullOrWhiteSpace(arg))
            {
                continue;
            }

            if (IsHelp(arg))
            {
                return new CommandLineParse(CommandLineMode.Help, null, null, null);
            }

            if (string.Equals(arg, ScreenshotSwitch, StringComparison.OrdinalIgnoreCase))
            {
                return ReadScreenshotArgument(args, i);
            }

            if (string.Equals(arg, SelfTestSwitch, StringComparison.OrdinalIgnoreCase))
            {
                return new CommandLineParse(CommandLineMode.SelfTest, null, null, null);
            }

            if (string.Equals(arg, MotionCheckSwitch, StringComparison.OrdinalIgnoreCase))
            {
                return ReadOptionalDirectoryArgument(args, i, MotionCheckSwitch, CommandLineMode.MotionCheck);
            }

            if (string.Equals(arg, SafeCheckSwitch, StringComparison.OrdinalIgnoreCase))
            {
                return ReadOptionalDirectoryArgument(args, i, SafeCheckSwitch, CommandLineMode.SafeCheck);
            }

            // Unknown switches are reported and ignored. Refusing to start because of an argument
            // this build does not recognise would be a worse failure than starting normally. Note
            // that it is still INTERACTIVE: somebody double-clicking the executable must get the
            // launcher, and the warning is the whole response.
            return Interactive(
                "Unrecognised command-line argument '" + arg + "'. Starting normally. Run XsozClient.exe --help for the switches this build understands.");
        }

        return Interactive(null);
    }

    private static bool IsHelp(string arg) =>
        arg is "--help" or "-h" or "-?" or "/?" or "/h"
        || string.Equals(arg, "--usage", StringComparison.OrdinalIgnoreCase);

    private static CommandLineParse ReadScreenshotArgument(string[] args, int index)
    {
        if (args.Length <= index + 1 || string.IsNullOrWhiteSpace(args[index + 1]))
        {
            return new CommandLineParse(
                CommandLineMode.Error,
                null,
                null,
                ScreenshotSwitch + " needs an output directory, for example: XsozClient.exe " + ScreenshotSwitch + " C:\\Temp\\xsoz-shots");
        }

        var directory = args[index + 1].Trim().Trim('"');
        return string.IsNullOrWhiteSpace(directory)
            ? new CommandLineParse(CommandLineMode.Error, null, null, ScreenshotSwitch + " was given an empty output directory.")
            : new CommandLineParse(CommandLineMode.Screenshot, Path.GetFullPath(directory), null, null);
    }

    /// <summary>
    /// The check modes' directory is optional, unlike the screenshot's: it walks up from the
    /// executable looking for the project file, which is right when it is run from the publish
    /// folder and wrong when it is run from a copy of the binary somewhere else. A blank or absent
    /// argument is therefore a normal, documented invocation rather than an error.
    /// </summary>
    private static CommandLineParse ReadOptionalDirectoryArgument(string[] args, int index, string switchName, CommandLineMode mode)
    {
        var next = index + 1 < args.Length ? args[index + 1].Trim().Trim('"') : string.Empty;
        if (next.Length == 0 || next.StartsWith('-'))
        {
            return new CommandLineParse(mode, null, null, null);
        }

        return Directory.Exists(next)
            ? new CommandLineParse(mode, null, Path.GetFullPath(next), null)
            : new CommandLineParse(
                CommandLineMode.Error,
                null,
                null,
                switchName + " was given '" + next + "', which is not a directory. Run it with no argument to search upward from the executable instead.");
    }

    private static string BuildHelpText()
    {
        var text = new StringBuilder();
        text.AppendLine("XsozClient launcher");
        text.AppendLine();
        text.AppendLine("Usage:");
        text.AppendLine("  XsozClient.exe                            Start the launcher.");
        text.AppendLine("  XsozClient.exe " + ScreenshotSwitch + " <outputDir>   Capture every screen to PNG, then exit.");
        text.AppendLine("  XsozClient.exe " + SelfTestSwitch + "               Run the install engine end-to-end from the console, then exit.");
        text.AppendLine("  XsozClient.exe " + MotionCheckSwitch + " [<projectDir>]  Check the motion system against the source, then exit.");
        text.AppendLine("  XsozClient.exe " + SafeCheckSwitch + " [<projectDir>]  Check the read-only guarantee, then exit.");
        text.AppendLine("  XsozClient.exe --help                     Show this text.");
        text.AppendLine();
        text.AppendLine("READ-ONLY MODES");
        text.AppendLine("  Every switch above except a bare launch is a hard read-only mode. A read-only");
        text.AppendLine("  mode cannot write into the official launcher's directory (%APPDATA%\\.minecraft),");
        text.AppendLine("  cannot provision, and cannot be made to do either by any environment variable,");
        text.AppendLine("  build flag or extra argument. This is structural, not conditional: the");
        text.AppendLine("  provisioner is handed a write-permission object whose every method refuses and");
        text.AppendLine("  counts the attempt, so there is no code path from these modes to the disk.");
        text.AppendLine();
        text.AppendLine("  The older XSOZ_CAPTURE_SKIP_PREFLIGHT=1 variable is still accepted as a name and");
        text.AppendLine("  is reported in the capture output, but it decides nothing. The preflight always");
        text.AppendLine("  runs; it simply cannot write. Setting it to 0 no longer opts you in to anything.");
        text.AppendLine();
        text.AppendLine(ScreenshotSwitch + " <outputDir>  (support / diagnostic mode)");
        text.AppendLine("  Builds the real window, the real view models, the real configuration and the real");
        text.AppendLine("  module registry, then rasterises the whole visual tree to PNG with");
        text.AppendLine("  RenderTargetBitmap. That path never asks the compositor or the display adapter for a");
        text.AppendLine("  frame, so it works on a headless session, a build agent, or any machine whose");
        text.AppendLine("  framebuffer cannot be captured - the same machine where CopyFromScreen and");
        text.AppendLine("  PrintWindow return solid black.");
        text.AppendLine();
        text.AppendLine("  Writes, into <outputDir>:");
        text.AppendLine("    <nn>-<screen>-<scale>pct.png   one per screen, plus the shell at 150%");
        text.AppendLine("    screenshot.log                 what was captured, the per-image verification");
        text.AppendLine("                                result, and the launcher's own log for the run");
        text.AppendLine();
        text.AppendLine("  Every image is verified after it is written by sampling it and counting distinct");
        text.AppendLine("  colours; a blank capture scores 1 and fails the run. Exit code 0 means every");
        text.AppendLine("  screen rendered and every image was verified. Any other code means at least one");
        text.AppendLine("  screen failed, and the log names it.");
        text.AppendLine();
        text.AppendLine("  Reads the launcher's own configuration under %LOCALAPPDATA%\\XsozClient, exactly");
        text.AppendLine("  as a normal launch does. It starts nothing, uploads nothing, and writes only into");
        text.AppendLine("  <outputDir> and %LOCALAPPDATA%\\XsozClient. It writes no game files: the");
        text.AppendLine("  provisioning preflight at the end of the capture runs the real provisioner with a");
        text.AppendLine("  read-only writer, and prints the number of write attempts it made, which is zero.");
        text.AppendLine();
        text.AppendLine(MotionCheckSwitch + " [<projectDir>]  (support / diagnostic mode)");
        text.AppendLine("  Reads the project's own C# and XAML and asserts the three rules that neither the");
        text.AppendLine("  compiler nor a screenshot can catch:");
        text.AppendLine("    1. no animation targets a LayoutProperty (Width, Height, Margin, Padding, ...)");
        text.AppendLine("    2. nothing loops - no RepeatBehavior and no Forever anywhere");
        text.AppendLine("    3. every BeginAnimation call is inside Themes\\Motion.cs");
        text.AppendLine("  It creates no window, opens no socket, downloads nothing and writes nothing.");
        text.AppendLine("  The project directory is optional; with no argument it searches upward from the");
        text.AppendLine("  executable for Xsoz.Launcher.csproj. Exit code 0 means every check holds.");
        text.AppendLine();
        text.AppendLine(SafeCheckSwitch + " [<projectDir>]  (support / diagnostic mode)");
        text.AppendLine("  The guard on the guard. It fails - non-zero exit, and every reason printed - if");
        text.AppendLine("  any of the following stops being true:");
        text.AppendLine("    1. the real writer is constructed in exactly one file, and that file is the");
        text.AppendLine("       write policy, which grants it only to an interactive launch");
        text.AppendLine("    2. the legacy capture environment variable is declared and read nowhere else,");
        text.AppendLine("       so no environment variable can re-enable a write");
        text.AppendLine("    3. the command line's write permission defaults to false and every");
        text.AppendLine("       non-interactive switch really does parse as read-only");
        text.AppendLine("    4. a provisioner built with a read-only writer records ZERO write attempts and");
        text.AppendLine("       returns having written nothing, against the real game directory");
        text.AppendLine("  It reads source and one hash table, creates no window, opens no socket and");
        text.AppendLine("  writes nothing. Exit code 0 means the guarantee still holds.");
        return text.ToString();
    }
}
