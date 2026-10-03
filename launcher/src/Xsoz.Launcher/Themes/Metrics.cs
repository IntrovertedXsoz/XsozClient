using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace Xsoz.Launcher.Themes;

/// <summary>
/// The one place that knows how thick a hairline is on this display.
///
/// DESIGN-NOTES line 107: "Draw every hairline as exactly one device pixel:
/// <c>BorderThickness = 1 / dpiScale</c>." A hardcoded 1 DIP is a two-pixel
/// line at 200% and a one-and-a-quarter-pixel line at 125%, which on a
/// hairline-only interface is the difference between a grid that reads as
/// drawn and a grid that reads as blurry. So the value is computed from the
/// window's own scale factor, not typed in.
///
/// The resources are written into <see cref="Application.Current"/>'s dictionary rather
/// than the window's, because a Style that references <c>H.Hair</c> is resolved against
/// the element's own resource chain and every view inherits from the application. They
/// are re-evaluated on <see cref="HwndSource.DpiChanged"/>, so moving the window between
/// a 100% and a 150% display re-lays the grid out rather than blurring it.
/// </summary>
public static class DpiMetrics
{
    private static double _scale = 1.0;
    private static HwndSource? _source;

    /// <summary>The current scale factor. 1.0 until a window has been realised.</summary>
    public static double Scale => _scale;

    /// <summary>One device pixel, in device-independent units.</summary>
    public static double Pixel => 1.0 / _scale;

    /// <summary>
    /// Snaps a design measurement to the device pixel grid.
    ///
    /// The design is specified in CSS pixels and every one of its steps is a whole number of
    /// them, so at 125% a 96px PLAY button has to become 120 device pixels, not 96 rounded to
    /// the nearest 1.25. Without this the block under the green button stops being exactly
    /// 6px and the whole flat look softens.
    /// </summary>
    public static double Snap(double dip) => Math.Round(dip * _scale, MidpointRounding.AwayFromZero) / _scale;

    /// <summary>Begins tracking a window's DPI. Safe to call more than once.</summary>
    public static void Attach(Window window)
    {
        if (window.IsLoaded)
        {
            AttachToSource(window);
        }

        window.SourceInitialized += OnSourceInitialized;
    }

    private static void OnSourceInitialized(object? sender, EventArgs e)
    {
        if (sender is Window window)
        {
            AttachToSource(window);
        }
    }

    private static void AttachToSource(Window window)
    {
        var source = PresentationSource.FromVisual(window) as HwndSource;
        if (source is null || ReferenceEquals(source, _source))
        {
            return;
        }

        _source = source;

        // A lambda rather than a named handler: the DpiChanged event's argument type is not
        // the same DpiChangedEventArgs that Visual.OnDpiChanged raises, and the lambda lets
        // the compiler bind the right one without either type being named here.
        source.DpiChanged += (_, args) => Apply(args.NewDpi.DpiScaleX);
        Apply(source.CompositionTarget.TransformToDevice.M11);
    }

    private static void Apply(double scale)
    {
        var safe = scale > 0.05 && scale < 20 ? scale : 1.0;
        if (Math.Abs(safe - _scale) < 0.0001)
        {
            return;
        }

        _scale = safe;

        var resources = Application.Current?.Resources;
        if (resources is null)
        {
            return;
        }

        var hair = Pixel;
        resources["H.Hair"] = new Thickness(hair);
        resources["H.HairTop"] = new Thickness(hair, 0, hair, 0);
        resources["H.HairBottom"] = new Thickness(0, 0, 0, hair);
        resources["H.HairLeft"] = new Thickness(0, hair, 0, hair);
        resources["H.HairRight"] = new Thickness(hair, hair, hair, 0);
        resources["H.RuleTop"] = new Thickness(hair, 0, hair, 0);
        resources["H.RuleBottom"] = new Thickness(0, 0, 0, hair);
        resources["H.PixelColumn"] = new GridLength(hair, GridUnitType.Pixel);
        resources["H.None"] = new Thickness(0);
    }
}
