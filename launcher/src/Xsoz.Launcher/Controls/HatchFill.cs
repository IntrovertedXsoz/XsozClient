using System.Windows;
using System.Windows.Media;
using Xsoz.Launcher.Themes;

namespace Xsoz.Launcher.Controls;

/// <summary>
/// A diagonal-stripe fill, used for exactly one thing: the hatched cap that marks the
/// sensitivity ramp's final rung as a partial step.
///
/// The design calls it <c>repeating-linear-gradient(135deg, --text-2 0 2px, transparent
/// 2px 5px)</c>, which is a stripe where <c>(x + y) mod 5 &lt; 2</c> - a 2px band every 5px,
/// running down-left at 45 degrees. WPF has no repeating gradient, and the nearest thing it
/// has (<see cref="DrawingBrush"/>) tiles a drawing on a rectangular viewport, which cannot
/// produce a 45-degree stripe that meets itself cleanly at the tile edge.
///
/// So the stripes are emitted as parallel 45-degree lines, one every 5 device pixels,
/// clipped to the element. A hatch is a hatch because it reads as "not solid" from a metre
/// away; the exact phase of the first stripe is not load-bearing, and every coordinate here
/// is snapped to the device pixel grid so the band is exactly 2px rather than 2px of
/// anti-aliased almost-2px.
/// </summary>
public sealed class HatchFill : FrameworkElement
{
    /// <summary>Colour of the stripes.</summary>
    public static readonly DependencyProperty HatchBrushProperty = DependencyProperty.Register(
        nameof(HatchBrush), typeof(Brush), typeof(HatchFill),
        new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>Width of one stripe, in device pixels.</summary>
    public static readonly DependencyProperty StripeWidthProperty = DependencyProperty.Register(
        nameof(StripeWidth), typeof(double), typeof(HatchFill),
        new FrameworkPropertyMetadata(2d, FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>Distance between stripes, in device pixels.</summary>
    public static readonly DependencyProperty PeriodProperty = DependencyProperty.Register(
        nameof(Period), typeof(double), typeof(HatchFill),
        new FrameworkPropertyMetadata(5d, FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>Colour of the stripes.</summary>
    public Brush? HatchBrush
    {
        get => (Brush?)GetValue(HatchBrushProperty);
        set => SetValue(HatchBrushProperty, value);
    }

    /// <summary>Width of one stripe, in device pixels.</summary>
    public double StripeWidth
    {
        get => (double)GetValue(StripeWidthProperty);
        set => SetValue(StripeWidthProperty, value);
    }

    /// <summary>Distance between stripes, in device pixels.</summary>
    public double Period
    {
        get => (double)GetValue(PeriodProperty);
        set => SetValue(PeriodProperty, value);
    }

    /// <inheritdoc />
    protected override void OnRender(DrawingContext dc)
    {
        var brush = HatchBrush;
        if (brush is null)
        {
            return;
        }

        var w = ActualWidth;
        var h = ActualHeight;
        if (w <= 0 || h <= 0)
        {
            return;
        }

        var scale = DpiMetrics.Scale;
        var period = Math.Max(1.0, Period);
        var pen = new Pen(brush, StripeWidth)
        {
            StartLineCap = PenLineCap.Square,
            EndLineCap = PenLineCap.Square,
        };
        pen.Freeze();

        dc.PushClip(new RectangleGeometry(new Rect(0, 0, w, h)));

        // The line x + y = c, in device pixels, so the band lands on the device grid.
        var reach = w + h;
        for (var c = -period; c <= reach; c += period)
        {
            var offset = c + (StripeWidth / 2.0);
            var x0 = Math.Round(offset * scale, MidpointRounding.AwayFromZero) / scale - h;
            var y0 = h;
            var x1 = Math.Round(offset * scale, MidpointRounding.AwayFromZero) / scale;
            var y1 = 0.0;
            dc.DrawLine(pen, new Point(x0, y0), new Point(x1, y1));
        }

        dc.Pop();
    }
}
