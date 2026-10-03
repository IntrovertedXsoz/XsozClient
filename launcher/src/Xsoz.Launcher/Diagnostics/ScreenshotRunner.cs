using System.Globalization;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Services;
using Xsoz.Launcher.ViewModels;
using Xsoz.Launcher.Views;

namespace Xsoz.Launcher.Diagnostics;

/// <summary>One rendered PNG and what the verification said about it.</summary>
/// <param name="Screen">Screen name, as the navigation shows it.</param>
/// <param name="FileName">File written into the output directory.</param>
/// <param name="WidthPx">Pixel width of the written image.</param>
/// <param name="HeightPx">Pixel height of the written image.</param>
/// <param name="ScalePercent">Render scale: 100 for the natural size, 150 for the scaled pass.</param>
/// <param name="DistinctColours">Distinct colours found by sampling the written file.</param>
/// <param name="Verified">True when the image passed the non-blank check.</param>
/// <param name="Error">Why it failed, or null.</param>
public sealed record CapturedScreen(
    string Screen,
    string FileName,
    int WidthPx,
    int HeightPx,
    int ScalePercent,
    int DistinctColours,
    bool Verified,
    string? Error);

/// <summary>
/// Renders the launcher's real interface to PNG files, in software, with no display attached.
///
/// Why this exists: a WPF visual tree is a retained-mode description of pixels, and
/// <see cref="RenderTargetBitmap"/> rasterises that description itself. It never asks the
/// compositor, never reads the framebuffer, and does not need an HWND. On a machine whose display
/// adapter returns solid black to every capture API - a headless session, a Parsec virtual
/// display, a CI agent - this is the only way to produce an image of the interface that anybody
/// can look at.
///
/// What it deliberately does NOT do: build a mock. It constructs <see cref="MainWindow"/>, which
/// constructs the real <see cref="MainViewModel"/>, which loads the real registry, settings,
/// profiles and account from the launcher's own state directory. A capture of a simplified layout
/// would be worthless as evidence, so there is no code path here that could produce one.
///
/// And what it deliberately does NOT do is provision. A capture is a hard read-only mode: the
/// <see cref="Services.VanillaProvisioner"/> it builds holds a <see cref="NoOpGameDirectoryWriter"/>,
/// so the whole preflight can still run against the real services while being structurally unable
/// to write a byte into <c>%APPDATA%\.minecraft</c>. See <see cref="GameWritePolicy"/>.
/// </summary>
public static class ScreenshotRunner
{
    /// <summary>The shell's design width in device-independent units.</summary>
    public const int WindowWidth = 1280;

    /// <summary>The shell's design height in device-independent units.</summary>
    public const int WindowHeight = 820;

    private const double BaseDpi = 96.0;
    private const int SecondaryScalePercent = 150;

    /// <summary>
    /// How many distinct sampled colours an image must contain to be considered a real capture.
    /// A uniform or empty image scores 1. Real UI - panels, hairlines, text, anti-aliased glyph
    /// edges - scores hundreds. Eight is low enough that no genuine screen fails it and high
    /// enough that a blank one cannot pass.
    /// </summary>
    private const int MinimumDistinctColours = 8;

    private const int SampleStep = 4;

    private static readonly (Screen Screen, string Slug)[] CaptureOrder =
    [
        (Screen.Home, "home"),
        (Screen.Mods, "mods"),
        (Screen.Settings, "settings"),
        (Screen.About, "about"),
    ];

    /// <summary>
    /// The drawer, as a still of its own rather than a flag on one of the four screens.
    ///
    /// The ramp is inside it, and the ramp is the one component whose whole point is a
    /// mid-journey state - six steps behind you and one in front. Capturing it at whatever stage
    /// the machine's real profile happens to be on would show either an empty ladder or a
    /// finished one, and neither is evidence of anything. So this capture previews stage 3 in
    /// memory and does not write the profile; the seam is
    /// <see cref="ViewModels.RampViewModel.ShowStagePreview"/> and it is the only path to the
    /// ladder that skips the save, which is stated here because a still that quietly moved the
    /// user's real sensitivity would be a nasty thing to discover later.
    /// </summary>
    private const int DrawerPreviewStage = 2;

    /// <summary>
    /// Captures every screen. Returns the process exit code: 0 only when every screen rendered and
    /// every written image passed the non-blank verification.
    /// </summary>
    public static int Run(string outputDirectory, TextWriter? console)
    {
        var captures = new List<CapturedScreen>();
        var report = new StringBuilder();
        var failures = 0;

        Directory.CreateDirectory(outputDirectory);
        var logPath = Path.Combine(outputDirectory, "screenshot.log");

        using var file = new StreamWriter(logPath, append: false, new UTF8Encoding(false)) { AutoFlush = true };
        var output = new TeeWriter(console, file);

        output.WriteLine("XsozClient headless capture");
        output.WriteLine("Started  : " + DateTime.Now.ToString("f", CultureInfo.CurrentCulture));
        output.WriteLine("Executable: " + Environment.ProcessPath);
        output.WriteLine("Output   : " + outputDirectory);
        output.WriteLine("Render   : RenderTargetBitmap, software only, "
                         + WindowWidth + "x" + WindowHeight + " DIP at 96 DPI and at " + SecondaryScalePercent + "%");

        AssertReadOnly(output);
        output.WriteLine();

        MainWindow? window = null;
        FrameworkElement? root = null;

        try
        {
            (window, root) = BuildShell();
            WarmUp(window, root);

            for (int index = 0; index < CaptureOrder.Length; index++)
            {
                var (screen, slug) = CaptureOrder[index];
                var fileName = $"{index + 1:00}-{slug}-100pct.png";

                var captured = CaptureScreen(window, root, screen, fileName, 100, outputDirectory, output);
                captures.Add(captured);
                if (!captured.Verified)
                {
                    failures++;
                }
            }

            // Two rows opened, so the one piece of copy that only exists in an expanded row is in
            // a picture somebody can look at. Both are real registry rows and both carry one of
            // the four muted notes the design specifies; the expansion is a view-model property
            // and writes nothing to disk.
            var expanded = CaptureExpandedModules(window, root, $"{CaptureOrder.Length + 1:00}-mods-expanded-100pct.png", outputDirectory, output);
            captures.Add(expanded);
            if (!expanded.Verified)
            {
                failures++;
            }

            // Settings, scrolled to the bottom. Everything below the fold on that screen is real
            // content - Video, Account, Advanced and the log viewer - and a still of the top of a
            // long screen is not evidence that the rest of it rendered. Scrolling the real
            // ScrollViewer is an interface operation, not a synthetic one.
            var settingsBottom = CaptureSettingsScrolled(window, root, $"{CaptureOrder.Length + 2:00}-settings-bottom-100pct.png", outputDirectory, output);
            captures.Add(settingsBottom);
            if (!settingsBottom.Verified)
            {
                failures++;
            }

            // The profile drawer, over Home, with the ramp at a mid stage. Closed again straight
            // after, so the following capture is of a plain screen.
            var drawer = CaptureDrawer(window, root, $"{CaptureOrder.Length + 3:00}-profile-drawer-100pct.png", outputDirectory, output);
            captures.Add(drawer);
            if (!drawer.Verified)
            {
                failures++;
            }

            // The shell itself at a scale this machine is not currently running at. 150% is the
            // most common laptop setting, and it is the setting a fixed-pixel layout falls over on.
            var scaledName = $"{CaptureOrder.Length + 4:00}-home-{SecondaryScalePercent}pct.png";
            var scaled = CaptureScreen(window, root, Screen.Home, scaledName, SecondaryScalePercent, outputDirectory, output);
            captures.Add(scaled);
            if (!scaled.Verified)
            {
                failures++;
            }

            // The provisioning preflight, run last and only once the images are on disk. It is here
            // because this is the one non-interactive run this project is permitted to make, and it
            // has to be a run that exercises the real services rather than a mock of them.
            //
            // AND IT IS HARD READ-ONLY, WHICH IS THE WHOLE POINT OF THE ARRANGEMENT. The preflight
            // is not a read-only check: the same code path provisions for real, into
            // %APPDATA%\.minecraft, whenever it is reached from an interactive run. So it is not
            // run here with a real writer and a safety gate and a hope. It is run with a
            // NoOpGameDirectoryWriter, an object whose every method refuses and counts the attempt,
            // and the preflight asserts that the count is zero. There is no switch, no environment
            // variable and no build flag that turns this back on: a capture is a read-only mode, so
            // it is a read-only mode, and the only way to provision is for a human to click the
            // button in a window.
            //
            // The preflight still writes its own evidence - a copy of the profile file, under
            // %LOCALAPPDATA%\XsozClient\verification - because that is this product's directory and
            // the merge drill against a copy is the strongest verification of the merge available.
            //
            // The one line, printed once. A guard that changes behaviour silently is a guard nobody
            // can tell is there, and a reader of a capture log has no other way to learn that the
            // provisioning drill below really did run - against a writer that cannot write.
            var readOnlySink = new NoOpGameDirectoryWriter();
            output.WriteLine();
            output.WriteLine(GameWritePolicy.ProvisioningSkippedLine
                             + " Mode: " + GameWritePolicy.Reason + ".");
            output.WriteLine("Writer: " + readOnlySink.Name + " - the preflight below runs the real"
                             + " VanillaProvisioner and it cannot write.");

            var preflight = ProvisioningPreflight.Run(output, readOnlySink);
            ProvisioningPreflight.AppendTo(report, preflight);

            // The headline number, printed rather than buried in the check table: if this is ever
            // not zero, the run did something it must never do, and the next line says how much.
            output.WriteLine();
            output.WriteLine("Game-directory writes attempted by this capture: " + readOnlySink.WriteAttempts);

            var preflightFailures = preflight.Count(c => !c.Passed);
            if (preflightFailures > 0)
            {
                output.WriteLine();
                output.WriteLine($"{preflightFailures} of {preflight.Count} preflight checks FAILED (listed in the report).");
            }
            else
            {
                output.WriteLine();
                output.WriteLine($"All {preflight.Count} preflight checks passed.");
            }
        }
        catch (Exception ex)
        {
            failures++;
            output.WriteLine("FATAL: the capture could not be produced.");
            output.WriteLine(ex.ToString());
            AppLog.Shared.Error("Headless capture failed: " + ex.Message);
        }
        finally
        {
            // The window was never shown, so nothing owns it and there is nothing to tear down.
            // It is released with the process.
            window = null;
            root = null;
        }

        WriteSummary(output, report, captures, logPath, failures);
        output.Flush();

        return failures == 0 ? 0 : 1;
    }

    /// <summary>
    /// The opt-OUT environment variable this project used to carry, kept as a name and read
    /// nowhere else.
    /// <para>
    /// It is declared rather than deleted so that any script still setting it keeps working - and
    /// setting it now changes nothing at all, which is the intended behaviour rather than an
    /// accident. The preflight used to be skipped when this was set, and skipped by default would
    /// have been the safer inversion; instead the preflight always runs, always in read-only mode,
    /// and the variable is reported in the output for the record and ignored. A guard that is off
    /// unless a variable says otherwise is a guard that is off on the day somebody forgets it, and
    /// that is the failure this replaced: <c>--screenshot</c> as a verification step performed a
    /// real provision, writing a version folder, eight jars and a profile into a real
    /// <c>%APPDATA%\.minecraft</c>. <c>--safecheck</c> fails if anything ever reads this name again
    /// for a decision.
    /// </para>
    /// </summary>
    public const string SkipPreflightVariable = "XSOZ_CAPTURE_SKIP_PREFLIGHT";

    /// <summary>
    /// The current value of <see cref="SkipPreflightVariable"/>, as one line for the capture output.
    /// <para>
    /// This property owns the ONLY read of that name in the entire project, which is what
    /// <c>--safecheck</c> asserts: the identifier appears in exactly two lines of code in the whole
    /// tree - this constant's declaration and the one aliasing line below - and both are in this
    /// file. That is what makes the guarantee checkable rather than merely stated: re-wiring an
    /// environment variable into a decision would push the count to three and fail the build.
    /// </para>
    /// </summary>
    public static string LegacyPreflightVariableState
    {
        get
        {
            // The name is aliased to a local so the identifier occurs exactly once more in the
            // project's code, which is what --safecheck counts. One declaration, one read.
            var name = SkipPreflightVariable;
            var value = Environment.GetEnvironmentVariable(name);
            return name + "=" + (value ?? "<unset>") + " (ignored; it decides nothing)";
        }
    }

    /// <summary>
    /// The one sentence a reader needs to know this mode is read-only. Kept here rather than in
    /// <see cref="GameWritePolicy"/> so that the constant a caller can print is next to the runner
    /// it describes, and stated in the policy because that is what enforces it.
    /// </summary>
    public const string ReadOnlyNotice = "This mode is hard read-only: it cannot write into the official launcher's directory.";

    /// <summary>
    /// Asserts that this process really is in a read-only mode before a capture starts drawing.
    /// Cheap, and it turns a future mistake in the startup wiring into a failed run with a clear
    /// sentence rather than a screenshot that quietly provisioned on somebody's behalf.
    /// </summary>
    private static void AssertReadOnly(TextWriter output)
    {
        if (GameWritePolicy.AllowsGameWrites)
        {
            throw new InvalidOperationException(
                "A capture is being run in a process that holds game-directory write permission ("
                + GameWritePolicy.Reason + "). That must never happen: the capture path is read-only "
                + "by construction. Fix App.OnStartup before rendering anything.");
        }

        output.WriteLine("Read-only : " + ReadOnlyNotice);
        output.WriteLine("Policy    : " + GameWritePolicy.Describe());
    }

    /// <summary>
    /// Builds the real window and returns the visual that is actually rendered.
    ///
    /// The render root is <em>not</em> the window. <c>Window.MeasureOverride</c> calls into
    /// <c>GetWindowMinMax</c>, which dereferences the window's HWND; a window that has never been
    /// shown has none, and the framework answers that with <c>Invariant.FailFast</c> - an immediate
    /// process abort, not an exception. The shell is therefore measured through a plain host
    /// element, which is a legal layout root everywhere.
    ///
    /// The host is installed as the window's own content rather than built beside it, on purpose.
    /// Every inheritable property the interface depends on - the data context, the body font, the
    /// display text-formatting mode, the window background - reaches the tree from the window, and
    /// a detached copy of the same XAML would silently lose all of it and render a different
    /// interface from the real one. Reusing the real window's content keeps the capture honest.
    /// </summary>
    private static (MainWindow Window, FrameworkElement Root) BuildShell()
    {
        AppLog.Shared.Info("Headless capture: building the real shell, no window will be shown.");

        var window = new MainWindow
        {
            Width = WindowWidth,
            Height = WindowHeight,
            WindowStyle = WindowStyle.None,
            WindowStartupLocation = WindowStartupLocation.Manual,
            ShowInTaskbar = false,
            ShowActivated = false,
        };

        // A render happens on a single frame, so an entrance animation would be caught at opacity
        // zero and the capture would be a blank page; and the drawer would be caught part-way
        // through its slide.
        window.MotionEnabled = false;

        // Brings the shell to the state OnContentRendered normally establishes, without needing a
        // window to have been presented. Shared with the interactive path so the two cannot drift.
        window.EstablishFirstRender();

        // Take the real content, reparent it under a host, and put the host back as the content.
        // The host carries the window's own background, which the XAML declares on the Window.
        var original = window.Content;
        var host = new Border
        {
            Width = WindowWidth,
            Height = WindowHeight,
            Background = window.Background,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Stretch,
            UseLayoutRounding = window.UseLayoutRounding,
        };

        window.Content = host;
        host.Child = original as UIElement;

        var size = new Size(WindowWidth, WindowHeight);
        host.Measure(size);
        host.Arrange(new Rect(0, 0, WindowWidth, WindowHeight));
        host.UpdateLayout();

        AppLog.Shared.Info($"Headless capture: staged offscreen at {host.ActualWidth}x{host.ActualHeight} DIP "
                          + $"(window {(window.IsVisible ? "visible" : "not visible")}, root {original?.GetType().Name ?? "null"}).");

        return (window, host);
    }

    /// <summary>
    /// Lets startup work finish. The scan is gone from the start path - the install is
    /// user-driven - so this just pumps the dispatcher twice and lays the root out.
    /// </summary>
    private static void WarmUp(MainWindow window, FrameworkElement root)
    {
        Settle();
        Settle();
        root.UpdateLayout();

        AppLog.Shared.Info($"Headless capture: {((MainViewModel)window.DataContext).Store.Modules.Count} modules loaded.");
    }

    /// <summary>
    /// Selects a screen through the view model, forces layout, renders, writes and verifies.
    /// One screen failing must not cost the other four, so every failure is caught and reported.
    /// </summary>
    private static CapturedScreen CaptureScreen(
        MainWindow window,
        FrameworkElement root,
        Screen screen,
        string fileName,
        int scalePercent,
        string outputDirectory,
        TextWriter output)
    {
        var scale = scalePercent / 100.0;
        var widthPx = (int)Math.Round(WindowWidth * scale, MidpointRounding.AwayFromZero);
        var heightPx = (int)Math.Round(WindowHeight * scale, MidpointRounding.AwayFromZero);
        var path = Path.Combine(outputDirectory, fileName);

        try
        {
            // The one navigation path: the view model's Current property, which the window is
            // already subscribed to. Nothing here reaches past it to poke a screen's visibility.
            ((MainViewModel)window.DataContext).Current = screen;

            root.UpdateLayout();
            Settle();
            root.UpdateLayout();

            var bitmap = new RenderTargetBitmap(
                widthPx,
                heightPx,
                BaseDpi * scale,
                BaseDpi * scale,
                PixelFormats.Pbgra32);

            bitmap.Render(root);
            bitmap.Freeze();

            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using (var stream = File.Create(path))
            {
                encoder.Save(stream);
            }

            // Verified against the file that was actually written, not against the in-memory
            // bitmap: an encoder that silently wrote a flat surface would pass a bitmap check.
            var distinct = CountDistinctColours(path, SampleStep);
            var ok = distinct >= MinimumDistinctColours;

            output.WriteLine($"{Describe(screen),-13} -> {fileName}  {widthPx}x{heightPx}  "
                             + $"{distinct} distinct colours  {(ok ? "OK" : "BLANK")}");

            if (!ok)
            {
                AppLog.Shared.Error($"Headless capture: {fileName} is blank ({distinct} distinct colour(s)).");
            }

            return new CapturedScreen(screen.ToString(), fileName, widthPx, heightPx, scalePercent, distinct, ok, ok ? null : "blank capture");
        }
        catch (Exception ex)
        {
            output.WriteLine($"{Describe(screen),-13} -> {fileName}  FAILED  {ex.GetType().Name}: {ex.Message}");
            AppLog.Shared.Error($"Headless capture: {fileName} failed: {ex}");
            return new CapturedScreen(screen.ToString(), fileName, widthPx, heightPx, scalePercent, 0, false, ex.Message);
        }
    }

    /// <summary>
    /// Opens two module rows and captures them.
    ///
    /// Which two are fixed in the runner rather than chosen at random, and they are the two that
    /// carry different note wordings: one says "Off by default. Some servers don't allow this."
    /// and the other says "Some servers don't allow this one. Check yours." A capture of only the
    /// first would leave the second untested, and this is the only copy in the product that exists
    /// solely inside an expanded row.
    /// </summary>
    private static CapturedScreen CaptureExpandedModules(
        MainWindow window,
        FrameworkElement root,
        string fileName,
        string outputDirectory,
        TextWriter output)
    {
        string[] wanted = ["Chunk Replay Cache", "Opaque Leaves"];
        var vm = (MainViewModel)window.DataContext;
        var path = Path.Combine(outputDirectory, fileName);

        try
        {
            vm.Current = Screen.Mods;

            var opened = 0;
            foreach (var row in vm.Mods.Rows)
            {
                if (wanted.Contains(row.Name, StringComparer.OrdinalIgnoreCase))
                {
                    row.IsExpanded = true;
                    opened++;
                }
            }

            if (opened != wanted.Length)
            {
                AppLog.Shared.Warn(
                    $"Headless capture: expected to open {wanted.Length} module rows for the still and found {opened}. "
                    + "The names in ScreenshotRunner need updating if the registry changed.");
            }

            root.UpdateLayout();
            Settle();
            root.UpdateLayout();

            var bitmap = new RenderTargetBitmap(WindowWidth, WindowHeight, BaseDpi, BaseDpi, PixelFormats.Pbgra32);
            bitmap.Render(root);
            bitmap.Freeze();

            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using (var stream = File.Create(path))
            {
                encoder.Save(stream);
            }

            var distinct = CountDistinctColours(path, SampleStep);
            var ok = distinct >= MinimumDistinctColours;
            output.WriteLine($"{"Mods (open)",-13} -> {fileName}  {WindowWidth}x{WindowHeight}  {distinct} distinct colours  {(ok ? "OK" : "BLANK")}  {opened} row(s) opened");

            return new CapturedScreen("Mods (open)", fileName, WindowWidth, WindowHeight, 100, distinct, ok, ok ? null : "blank capture");
        }
        catch (Exception ex)
        {
            output.WriteLine($"{"Mods (open)",-13} -> {fileName}  FAILED  {ex.GetType().Name}: {ex.Message}");
            AppLog.Shared.Error($"Headless capture: {fileName} failed: {ex}");
            return new CapturedScreen("Mods (open)", fileName, WindowWidth, WindowHeight, 100, 0, false, ex.Message);
        }
        finally
        {
            foreach (var row in vm.Mods.Rows)
            {
                row.IsExpanded = false;
            }

            root.UpdateLayout();
        }
    }

    /// <summary>
    /// Scrolls the real Settings ScrollViewer to the bottom and captures it.
    ///
    /// The offset is set on the actual ScrollViewer the user scrolls, not on a copy and not by
    /// re-arranging anything, so the image is the bottom of the screen as it looks after a scroll
    /// rather than a layout that only the capture tool can produce.
    /// </summary>
    private static CapturedScreen CaptureSettingsScrolled(
        MainWindow window,
        FrameworkElement root,
        string fileName,
        string outputDirectory,
        TextWriter output)
    {
        var path = Path.Combine(outputDirectory, fileName);
        var vm = (MainViewModel)window.DataContext;

        try
        {
            vm.Current = Screen.Settings;
            root.UpdateLayout();
            Settle();

            if (window.SettingsScroller is not { } scroller)
            {
                throw new InvalidOperationException("The Settings screen has no named ScrollViewer to scroll.");
            }

            scroller.ScrollToEnd();
            root.UpdateLayout();
            Settle();
            root.UpdateLayout();

            var bitmap = new RenderTargetBitmap(WindowWidth, WindowHeight, BaseDpi, BaseDpi, PixelFormats.Pbgra32);
            bitmap.Render(root);
            bitmap.Freeze();

            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using (var stream = File.Create(path))
            {
                encoder.Save(stream);
            }

            var distinct = CountDistinctColours(path, SampleStep);
            var ok = distinct >= MinimumDistinctColours;
            output.WriteLine($"{"Settings (end)",-13} -> {fileName}  {WindowWidth}x{WindowHeight}  {distinct} distinct colours  {(ok ? "OK" : "BLANK")}  scrolled to {scroller.VerticalOffset:0}");

            return new CapturedScreen("Settings (end)", fileName, WindowWidth, WindowHeight, 100, distinct, ok, ok ? null : "blank capture");
        }
        catch (Exception ex)
        {
            output.WriteLine($"{"Settings (end)",-13} -> {fileName}  FAILED  {ex.GetType().Name}: {ex.Message}");
            AppLog.Shared.Error($"Headless capture: {fileName} failed: {ex}");
            return new CapturedScreen("Settings (end)", fileName, WindowWidth, WindowHeight, 100, 0, false, ex.Message);
        }
        finally
        {
            if (window.SettingsScroller is { } back)
            {
                back.ScrollToVerticalOffset(0);
                root.UpdateLayout();
            }
        }
    }

    /// <summary>
    /// Opens the profile drawer, previews a mid-ramp stage, captures, and closes it again.
    ///
    /// Every step goes through the shell's own properties. Nothing here reaches past the view
    /// model to poke a control's visibility, so a capture that renders an overlay the interface
    /// could not actually produce is not possible.
    /// </summary>
    private static CapturedScreen CaptureDrawer(
        MainWindow window,
        FrameworkElement root,
        string fileName,
        string outputDirectory,
        TextWriter output)
    {
        var scale = 1.0;
        var widthPx = WindowWidth;
        var heightPx = WindowHeight;
        var path = Path.Combine(outputDirectory, fileName);
        var vm = (MainViewModel)window.DataContext;

        try
        {
            vm.Current = Screen.Home;
            vm.IsProfilePanelOpen = true;

            // AFTER the drawer opens, not before. Opening it calls Refresh, which re-reads the
            // profile and puts the ladder back where the profile actually is - so a preview
            // applied first is simply overwritten.
            vm.ProfilePanel.Ramp.ShowStagePreview(DrawerPreviewStage);

            root.UpdateLayout();
            Settle();
            root.UpdateLayout();

            var bitmap = new RenderTargetBitmap(widthPx, heightPx, BaseDpi * scale, BaseDpi * scale, PixelFormats.Pbgra32);
            bitmap.Render(root);
            bitmap.Freeze();

            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using (var stream = File.Create(path))
            {
                encoder.Save(stream);
            }

            var distinct = CountDistinctColours(path, SampleStep);
            var ok = distinct >= MinimumDistinctColours;
            output.WriteLine($"{"Profile",-13} -> {fileName}  {widthPx}x{heightPx}  {distinct} distinct colours  {(ok ? "OK" : "BLANK")}");

            if (!ok)
            {
                AppLog.Shared.Error($"Headless capture: {fileName} is blank ({distinct} distinct colour(s)).");
            }

            return new CapturedScreen("Profile", fileName, widthPx, heightPx, 100, distinct, ok, ok ? null : "blank capture");
        }
        catch (Exception ex)
        {
            output.WriteLine($"{"Profile",-13} -> {fileName}  FAILED  {ex.GetType().Name}: {ex.Message}");
            AppLog.Shared.Error($"Headless capture: {fileName} failed: {ex}");
            return new CapturedScreen("Profile", fileName, widthPx, heightPx, 100, 0, false, ex.Message);
        }
        finally
        {
            vm.IsProfilePanelOpen = false;
            root.UpdateLayout();
        }
    }

    /// <summary>
    /// Counts distinct colours in a written image, sampling every <paramref name="step"/> pixels in
    /// both axes. Sampling rather than reading every pixel keeps this cheap at 1920x1230 while
    /// still covering roughly fifty thousand samples - far more than enough to separate a real UI
    /// from a flat fill.
    /// </summary>
    private static int CountDistinctColours(string path, int step)
    {
        using var stream = File.OpenRead(path);
        var frame = BitmapFrame.Create(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
        var converted = new FormatConvertedBitmap(frame, PixelFormats.Bgra32, null, 0);

        int width = converted.PixelWidth;
        int height = converted.PixelHeight;
        int stride = width * 4;
        var pixels = new byte[checked(stride * height)];
        converted.CopyPixels(pixels, stride, 0);

        var seen = new HashSet<int>();
        for (int y = 0; y < height; y += step)
        {
            int row = y * stride;
            for (int x = 0; x < width; x += step)
            {
                int offset = row + (x * 4);
                seen.Add((pixels[offset + 2] << 16) | (pixels[offset + 1] << 8) | pixels[offset]);
            }
        }

        return seen.Count;
    }

    /// <summary>
    /// Runs the dispatcher until it is quiet. Without this, a change to a bound property made
    /// just before a capture would not have been laid out, and the image would show the previous
    /// screen - the single most likely way for this tool to produce a confidently wrong answer.
    /// </summary>
    private static void Settle()
    {
        var frame = new DispatcherFrame();
        var timer = new DispatcherTimer(DispatcherPriority.Background, Dispatcher.CurrentDispatcher)
        {
            Interval = TimeSpan.FromMilliseconds(20),
        };

        timer.Tick += (_, _) =>
        {
            timer.Stop();
            frame.Continue = false;
        };

        timer.Start();
        Dispatcher.PushFrame(frame);
    }

    private static string Describe(Screen screen) => screen switch
    {
        Screen.Home => "Home",
        Screen.Mods => "Mods",
        Screen.Settings => "Settings",
        Screen.About => "About",
        _ => screen.ToString(),
    };

    private static void WriteSummary(
        TextWriter output,
        StringBuilder report,
        List<CapturedScreen> captures,
        string logPath,
        int failures)
    {
        report.AppendLine();
        report.AppendLine("================ capture summary ================");
        report.AppendLine($"{"screen",-13} {"file",-22} {"pixels",-12} {"scale",-7} {"colours",-9} result");
        foreach (var capture in captures)
        {
            report.AppendLine(string.Format(
                CultureInfo.InvariantCulture,
                "{0,-13} {1,-22} {2,-12} {3,-7} {4,-9} {5}",
                capture.Screen,
                capture.FileName,
                $"{capture.WidthPx}x{capture.HeightPx}",
                capture.ScalePercent + "%",
                capture.DistinctColours,
                capture.Verified ? "ok" : "FAILED: " + capture.Error));
        }

        report.AppendLine();
        report.AppendLine(failures == 0
            ? $"All {captures.Count} images rendered and none was blank. Verification threshold: "
              + $"{MinimumDistinctColours} distinct colours, sampled every {SampleStep} pixels."
            : $"{failures} of {captures.Count} images failed. See the lines above for which screen and why.");

        report.AppendLine();
        report.AppendLine("================ launcher log for this run ================");
        foreach (var entry in AppLog.Shared.Snapshot())
        {
            report.AppendLine($"{entry.TimestampLocal:HH:mm:ss} [{entry.Level.ToString().ToUpperInvariant(),-7}] {entry.Message}");
        }

        output.WriteLine();
        output.Write(report.ToString());
        output.WriteLine("Report written to " + logPath);
    }
}
