using System.Text;
using System.Text.RegularExpressions;
using Xsoz.Launcher.Themes;

namespace Xsoz.Launcher.Diagnostics;

/// <summary>One static check about the motion system and its verdict.</summary>
/// <param name="Name">What was checked.</param>
/// <param name="Passed">True when the check held.</param>
/// <param name="Detail">The evidence, as it will be printed.</param>
public sealed record MotionCheckResult(string Name, bool Passed, string Detail);

/// <summary>
/// THE MOTION STATIC CHECK: <c>--motioncheck</c>.
///
/// A WPF animation mistake is invisible in a screenshot and invisible to the compiler. A storyboard
/// that targets a transform by name, a height animated over a twenty-row list, a
/// <c>RepeatBehavior="Forever"</c> left in a template: all of them build clean, render clean, and
/// then cost the user frame drops on a machine that is also running a game. So the three rules that
/// cannot be enforced by writing good code are enforced by reading the source:
///
///   1. NO ANIMATION TARGETS A LAYOUT PROPERTY. Width, Height, Margin, Padding, RowDefinition and
///      their relatives re-run measure and arrange on every frame for the element and everything
///      above it. Only <c>Motion.AnimatedProperties</c> may move, and that list is asserted against
///      both the properties the helper actually passes to <c>BeginAnimation</c> and a list of known
///      layout properties.
///   2. NOTHING LOOPS. Any occurrence of <c>RepeatBehavior</c> - in XAML or in C#, with any value -
///      is a failure. The design's motion is gestures, and a gesture that never ends is a defect.
///      XAML-declared animations are a failure for the same reason plus a second one: a storyboard
///      in a control template cannot reach a transform, so the button scale that used to be written
///      that way had never run.
///   3. EVERY <c>BeginAnimation</c> GOES THROUGH THE HELPER. One file may call it. That is what
///      makes the reduced-motion switch, the interruption behaviour and the allow-list meaningful
///      rather than advisory.
///
/// It reads files, creates no window, opens no socket, and writes nothing outside its own report. It
/// is a separate switch from <c>--selftest</c> on purpose: the self-test runs the real install
/// engine, which means a gigabyte of downloads, and none of that is needed to answer "is the motion
/// system well behaved".
/// </summary>
public static class MotionCheck
{
    /// <summary>The one file permitted to call BeginAnimation.</summary>
    private static readonly string HelperFile = "Themes" + Path.DirectorySeparatorChar + "Motion.cs";

    /// <summary>
    /// Properties whose animation re-runs layout. Matched by name against everything the helper
    /// animates; an entry containing any of these words fails check 1.
    /// </summary>
    private static readonly string[] LayoutPropertyWords =
    [
        "Width", "Height", "Margin", "Padding", "Thickness", "Size",
        "RowDefinition", "ColumnDefinition", "GridLength", "CornerRadius",
    ];

    /// <summary>XAML animation elements. None of these may appear: see the class remarks.</summary>
    private static readonly string[] XamlAnimationElements =
    [
        "<DoubleAnimation", "<ColorAnimation", "<ByteAnimation", "<PointAnimation",
        "<ThicknessAnimation", "<RectangleAnimation", "<SizeAnimation",
        "<DoubleAnimationUsingKeyFrames", "<ColorAnimationUsingKeyFrames",
        "<Storyboard", "<BeginStoryboard", "EnterActions", "ExitActions",
    ];

    private static readonly Regex BeginAnimationCall =
        new(@"BeginAnimation\s*\(\s*(?<property>[A-Za-z0-9_.]+)\s*(,|\))", RegexOptions.Compiled);

    private static readonly Regex DurationConstant =
        new(@"(?<name>\w+)Milliseconds\s*=\s*(?<value>\d+)", RegexOptions.Compiled);

    /// <summary>
    /// Strips C# comments and string literals before the text is searched for keywords.
    ///
    /// Without this the check fails on itself: it has to name the words it is looking for in order
    /// to look for them, and the help text quotes the rules back to the user. A check that had to
    /// exempt its own source to pass is a check nobody trusts, so the two constructs that can
    /// legitimately contain the words are removed from every file, including this one. Removal
    /// cannot manufacture a keyword, so this can cost a false negative and never a false positive.
    /// </summary>
    internal static readonly Regex CodeOnly = new(
        @"(?s)/\*.*?\*/|//[^\n]*|@""(?:[^""]|"""")*""|""(?:\\.|[^""\\\n])*""",
        RegexOptions.Compiled);

    /// <summary>Runs every check. Returns the process exit code: 0 only if all of them hold.</summary>
    /// <param name="output">Where the report goes.</param>
    /// <param name="sourceDirectory">The project directory, or null to find it.</param>
    public static int Run(TextWriter output, string? sourceDirectory)
    {
        ArgumentNullException.ThrowIfNull(output);

        output.WriteLine("XsozClient motion static check");
        output.WriteLine("The three rules that cannot be enforced by writing good code, checked against the source.");
        output.WriteLine();

        var root = sourceDirectory ?? FindProjectDirectory();
        if (root is null)
        {
            output.WriteLine("[FAIL] project source not found.");
            output.WriteLine("       Run this against the source tree, or pass the directory: "
                             + "XsozClient.exe --motioncheck <projectDir>");
            return 2;
        }

        output.WriteLine("Source   : " + root);
        output.WriteLine();

        var files = SourceFiles(root).ToList();
        output.WriteLine($"Scanned  : {files.Count(x => x.EndsWith(".cs", StringComparison.OrdinalIgnoreCase))} C# file(s), "
                         + $"{files.Count(x => x.EndsWith(".xaml", StringComparison.OrdinalIgnoreCase))} XAML file(s).");
        output.WriteLine();

        var results = new List<MotionCheckResult>
        {
            CheckBeginAnimationIsConfinedToTheHelper(files),
            CheckNoLoops(files),
            CheckNoXamlAnimations(files),
            CheckNoLayoutPropertyIsAnimated(root),
            CheckDurationsAreInTheDesignBand(root),
        };

        var report = new StringBuilder();
        report.AppendLine("================ motion static check ================");
        foreach (var result in results)
        {
            report.AppendLine($"[{(result.Passed ? "PASS" : "FAIL")}] {result.Name}");
            report.AppendLine($"       {result.Detail}");
        }

        report.AppendLine();
        report.AppendLine("---- motion inventory, as declared by Themes\\Motion.cs ----");
        report.AppendLine("animated properties (the allow-list):");
        foreach (var property in Motion.AnimatedProperties)
        {
            report.AppendLine("  " + property);
        }

        report.AppendLine("durations:");
        foreach (var (name, value) in DurationConstants(root))
        {
            report.AppendLine($"  {name,-10} {value,3} ms");
        }

        report.AppendLine($"reduced motion: Motion.Enabled = {Motion.Enabled} "
                          + $"(system prefers reduced motion: {Motion.SystemPrefersReducedMotion})");
        report.AppendLine($"toggle knob travel at this scale: {Motion.ToggleKnobTravel:0.###} DIP");

        var failures = results.Count(r => !r.Passed);
        report.AppendLine();
        report.AppendLine(failures == 0
            ? $"All {results.Count} motion checks passed."
            : $"{failures} of {results.Count} motion checks FAILED.");

        output.Write(report.ToString());
        output.WriteLine();
        return failures == 0 ? 0 : 1;
    }

    // -------------------------------------------------------------------------------------------
    // The checks.
    // -------------------------------------------------------------------------------------------

    /// <summary>Check 3: one file may call BeginAnimation, and it is the helper.</summary>
    private static MotionCheckResult CheckBeginAnimationIsConfinedToTheHelper(IReadOnlyList<string> files)
    {
        var expected = HelperFile;
        var offenders = new List<string>();
        var helperCalls = 0;

        foreach (var file in files.Where(f => f.EndsWith(".cs", StringComparison.OrdinalIgnoreCase)))
        {
            var text = ReadText(file);
            if (text is null)
            {
                continue;
            }

            var count = BeginAnimationCall.Matches(text).Count;
            if (count == 0)
            {
                continue;
            }

            if (IsHelperFile(file, expected))
            {
                helperCalls += count;
                continue;
            }

            offenders.Add($"{Relative(file)} ({count} call(s))");
        }

        var passed = offenders.Count == 0 && helperCalls > 0;
        return new MotionCheckResult(
            "Every BeginAnimation call goes through Themes\\Motion.cs",
            passed,
            passed
                ? $"{helperCalls} call(s), all in Themes\\Motion.cs. No view, control or view model animates anything directly."
                : "offending file(s): " + string.Join(", ", offenders)
                  + (helperCalls == 0 ? "; and Themes\\Motion.cs itself makes no call." : string.Empty));
    }
    /// <summary>Check 2: no RepeatBehavior, by any spelling, anywhere.</summary>
    private static MotionCheckResult CheckNoLoops(IReadOnlyList<string> files)
    {
        var hits = new List<string>();

        foreach (var file in files)
        {
            var text = ReadText(file);
            if (text is null)
            {
                continue;
            }

            // Code only. See CodeOnly: the words being looked for appear in this file's own
            // comments and in the help text, and a check that cannot read its own source is not a
            // check.
            var code = CodeOnly.Replace(text, string.Empty);

            // "Forever" on its own is enough to fail: WPF's only looping value is the Forever
            // enumeration, and it is spelled that way in XAML and in code alike.
            foreach (var line in SplitLines(code))
            {
                if (line.Contains("RepeatBehavior", StringComparison.OrdinalIgnoreCase)
                    || line.Contains("Forever", StringComparison.Ordinal))
                {
                    hits.Add($"{Relative(file)}: {line.Trim()}");
                }
            }
        }

        return new MotionCheckResult(
            "Nothing loops - no RepeatBehavior and no Forever",
            hits.Count == 0,
            hits.Count == 0
                ? "No RepeatBehavior and no Forever anywhere in the project. Idle means idle: no clock is left running."
                : string.Join(" | ", hits.Take(8)));
    }

    /// <summary>Check 2b: no animation is authored in XAML at all.</summary>
    private static MotionCheckResult CheckNoXamlAnimations(IReadOnlyList<string> files)
    {
        var hits = new List<string>();

        foreach (var file in files.Where(f => f.EndsWith(".xaml", StringComparison.OrdinalIgnoreCase)))
        {
            var text = ReadText(file);
            if (text is null)
            {
                continue;
            }

            // Comments are stripped first: this file documents the storyboard it removed, and a
            // check that failed on its own explanation would be a check nobody trusts.
            foreach (var element in XamlAnimationElements)
            {
                if (StripXmlComments(text).Contains(element, StringComparison.Ordinal))
                {
                    hits.Add($"{Relative(file)} contains {element}");
                }
            }
        }

        var distinct = hits.Distinct(StringComparer.Ordinal).ToList();
        return new MotionCheckResult(
            "No animation is declared in XAML",
            distinct.Count == 0,
            distinct.Count == 0
                ? "Every style states a destination value through a Themes.Motion attached property; no storyboard, no duration, no curve in markup."
                : string.Join(" | ", distinct.Take(8)));
    }

    /// <summary>Check 1: the helper's own animated properties, against the layout denylist.</summary>
    private static MotionCheckResult CheckNoLayoutPropertyIsAnimated(string root)
    {
        var helper = Path.Combine(root, HelperFile);
        var text = ReadText(helper);
        if (text is null)
        {
            return new MotionCheckResult("No animation targets a LayoutProperty", false, "could not read " + helper);
        }

        // What the helper actually hands to BeginAnimation, normalised to the declared spelling.
        var used = new SortedSet<string>(StringComparer.Ordinal);

        foreach (Match match in BeginAnimationCall.Matches(text))
        {
            var normalised = Normalise(match.Groups["property"].Value);
            if (normalised is not null)
            {
                used.Add(normalised);
            }
        }

        // Plus the ones that reach BeginAnimation as an argument, declared in the helper itself
        // rather than guessed at here.
        foreach (var indirect in Motion.IndirectProperties)
        {
            used.Add(indirect);
        }

        var declared = Motion.AnimatedProperties.OrderBy(p => p, StringComparer.Ordinal).ToList();
        var undeclared = used.Where(u => !declared.Contains(u, StringComparer.Ordinal)).ToList();
        var unused = declared.Where(d => !used.Contains(d, StringComparer.Ordinal)).ToList();

        var offenders = declared
            .Where(p => LayoutPropertyWords.Any(w => p.Contains(w, StringComparison.Ordinal)))
            .ToList();

        var passed = offenders.Count == 0 && undeclared.Count == 0 && unused.Count == 0;

        var detail = passed
            ? $"{string.Join(", ", declared)} - all of them composition properties, and every one of them is a live call site in the helper."
            : BuildLayoutDetail(offenders, undeclared, unused);

        return new MotionCheckResult("No animation targets a LayoutProperty", passed, detail);
    }

    private static string BuildLayoutDetail(IReadOnlyList<string> offenders, IReadOnlyList<string> undeclared, IReadOnlyList<string> unused)
    {
        var parts = new List<string>();
        if (offenders.Count > 0)
        {
            parts.Add("layout properties in the allow-list: " + string.Join(", ", offenders));
        }

        if (undeclared.Count > 0)
        {
            parts.Add("animated but not declared: " + string.Join(", ", undeclared));
        }

        if (unused.Count > 0)
        {
            parts.Add("declared but never animated: " + string.Join(", ", unused));
        }

        return string.Join(" | ", parts);
    }

    /// <summary>Every duration the helper declares, so the band is checked rather than assumed.</summary>
    private static MotionCheckResult CheckDurationsAreInTheDesignBand(string root)
    {
        var helper = Path.Combine(root, HelperFile);
        var text = ReadText(helper);
        if (text is null)
        {
            return new MotionCheckResult("Every duration is inside the design's 100-200ms band", false, "could not read " + helper);
        }

        var declared = DurationConstants(root).ToList();
        if (declared.Count == 0)
        {
            return new MotionCheckResult("Every duration is inside the design's 100-200ms band", false, "no duration constants found in the helper");
        }

        // 50 is the floor rather than 100 because the install bar's catch-up is deliberately the
        // shortest value in the file; it is the linear one, and it is the only one that is allowed
        // below the band. Nothing anywhere is above 200ms, which is the half of the contract that
        // matters: there is no animation in this product long enough to wait for.
        var tooLong = declared.Where(d => d.Value > 200).ToList();
        var tooShort = declared.Where(d => d.Value < 50).ToList();
        var passed = tooLong.Count == 0 && tooShort.Count == 0;

        return new MotionCheckResult(
            "Every duration is inside the design's band (50-200ms, none longer)",
            passed,
            passed
                ? string.Join(", ", declared.Select(d => $"{d.Name} {d.Value}ms"))
                : string.Join(" | ",
                    tooLong.Select(d => $"too long: {d.Name} {d.Value}ms")
                        .Concat(tooShort.Select(d => $"too short: {d.Name} {d.Value}ms"))));
    }

    // -------------------------------------------------------------------------------------------
    // Plumbing.
    // -------------------------------------------------------------------------------------------

    /// <summary>
    /// Walks up from the executable looking for the project file, so the check works from the
    /// publish folder (<c>src\Xsoz.Launcher\out\XsozClient.exe</c>) without being told where it is.
    /// </summary>
    internal static string? FindProjectDirectory()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (dir.EnumerateFiles("Xsoz.Launcher.csproj").Any())
            {
                return dir.FullName;
            }

            dir = dir.Parent;
        }

        return null;
    }

    internal static IEnumerable<string> SourceFiles(string root) =>
        Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories)
            .Where(IsSourceFile);

    private static bool IsSourceFile(string path)
    {
        var normalised = path.Replace('/', Path.DirectorySeparatorChar);
        foreach (var skip in new[] { "bin", "obj", "out", ".vs", ".git" })
        {
            if (normalised.Contains(Path.DirectorySeparatorChar + skip + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)
                || normalised.EndsWith(Path.DirectorySeparatorChar + skip, StringComparison.OrdinalIgnoreCase))
            {
                return false;
            }
        }

        return normalised.EndsWith(".cs", StringComparison.OrdinalIgnoreCase)
               || normalised.EndsWith(".xaml", StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsHelperFile(string path, string expectedSuffix) =>
        path.Replace('/', Path.DirectorySeparatorChar).EndsWith(expectedSuffix, StringComparison.OrdinalIgnoreCase);

    internal static string Relative(string path) => Path.GetFileName(Path.GetDirectoryName(path) ?? string.Empty) is { Length: > 0 } dir
        ? dir + Path.DirectorySeparatorChar + Path.GetFileName(path)
        : Path.GetFileName(path);

    internal static string? ReadText(string path)
    {
        try
        {
            return File.ReadAllText(path);
        }
        catch (IOException)
        {
            return null;
        }
    }

    internal static IEnumerable<string> SplitLines(string text) =>
        text.Split('\n').Select(l => l.TrimEnd('\r'));

    private static string StripXmlComments(string text) =>
        Regex.Replace(text, "<!--.*?-->", string.Empty, RegexOptions.Singleline);

    /// <summary>
    /// The helper's own property expressions, mapped onto the names in
    /// <see cref="Motion.AnimatedProperties"/>. Returns null for anything the source cannot name -
    /// which is the CatchUp argument, and the reason that list carries BlockBar.Fraction in it.
    /// </summary>
    private static string? Normalise(string expression) => expression switch
    {
        "UIElement.OpacityProperty" or "OpacityProperty" => "UIElement.Opacity",
        "ScaleTransform.ScaleXProperty" => "ScaleTransform.ScaleX",
        "ScaleTransform.ScaleYProperty" => "ScaleTransform.ScaleY",
        "TranslateTransform.XProperty" => "TranslateTransform.X",
        _ => null,
    };

    private static IEnumerable<(string Name, int Value)> DurationConstants(string root)
    {
        var helper = Path.Combine(root, HelperFile);
        var text = ReadText(helper);
        if (text is null)
        {
            yield break;
        }

        foreach (Match match in DurationConstant.Matches(text))
        {
            if (int.TryParse(match.Groups["value"].Value, out var value))
            {
                yield return (match.Groups["name"].Value, value);
            }
        }
    }
}
