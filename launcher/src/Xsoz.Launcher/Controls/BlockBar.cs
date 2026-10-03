using System.Windows;
using System.Windows.Media;
using Xsoz.Launcher.Themes;

namespace Xsoz.Launcher.Controls;

/// <summary>
/// The install progress bar: 10px blocks with 2px gaps, on a 20px band.
///
/// The design builds it from <c>repeating-linear-gradient(90deg, #252A35 0 10px, transparent
/// 10px 12px)</c> for the empty state and the same pattern in the accent for the fill, with
/// the fill element's width set to the real fraction. WPF's ProgressBar cannot express a
/// repeating pattern, and a continuous bar would be a different component - the whole point of
/// the block bar is that a partial step is legible as a partial block rather than as a smear.
///
/// So: the empty blocks are drawn across the full width, and the filled blocks are drawn from
/// the same origin and clipped to <paramref name="Fraction"/> of the width. The fill does not
/// slide; it is the same pattern with a different right-hand edge, which is exactly what the
/// CSS does.
///
/// THE FRACTION IS SMOOTHED, THE PROGRESS IS NOT. Each tick the bar moves to the fraction the
/// install service has actually reported, over 60 ms and strictly linearly, so a byte-rate that
/// ticks 60 times a second reads as one continuous fill instead of a flicker. The transition is
/// linear and one-directional on purpose: it arrives AT the reported value and never past it, so
/// at no moment does the bar claim more progress than exists. It animates the render-only
/// <c>Fraction</c> property - never a layout property - so the whole cost is one small redraw.
/// </summary>
public sealed class BlockBar : FrameworkElement
{
    /// <summary>Progress, 0..1.</summary>
    public static readonly DependencyProperty FractionProperty = DependencyProperty.Register(
        nameof(Fraction), typeof(double), typeof(BlockBar),
        new FrameworkPropertyMetadata(0d, FrameworkPropertyMetadataOptions.AffectsRender, OnFractionChanged));

    /// <summary>Colour of a completed block.</summary>
    public static readonly DependencyProperty BlockBrushProperty = DependencyProperty.Register(
        nameof(BlockBrush), typeof(Brush), typeof(BlockBar),
        new FrameworkPropertyMetadata(Brushes.Transparent, FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>Colour of an empty block.</summary>
    public static readonly DependencyProperty EmptyBrushProperty = DependencyProperty.Register(
        nameof(EmptyBrush), typeof(Brush), typeof(BlockBar),
        new FrameworkPropertyMetadata(Brushes.Transparent, FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>Block width in device pixels.</summary>
    public static readonly DependencyProperty BlockSizeProperty = DependencyProperty.Register(
        nameof(BlockSize), typeof(double), typeof(BlockBar),
        new FrameworkPropertyMetadata(10d, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>Gap between blocks in device pixels.</summary>
    public static readonly DependencyProperty BlockGapProperty = DependencyProperty.Register(
        nameof(BlockGap), typeof(double), typeof(BlockBar),
        new FrameworkPropertyMetadata(2d, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>Progress, 0..1.</summary>
    public double Fraction
    {
        get => (double)GetValue(FractionProperty);
        set => SetValue(FractionProperty, Math.Clamp(value, 0, 1));
    }

    /// <summary>
    /// Smooths the move to each new fraction, over 60 ms and linearly, through the shared motion
    /// helper - so the duration, the curve and the reduced-motion switch are not restated here.
    ///
    /// The value being animated FROM is the effective value, which while a previous catch-up is
    /// still running is the in-flight number rather than the last reported one. That is what makes
    /// a fast byte-rate read as one continuous bar: each new tick picks the fill up where it
    /// currently is instead of snapping it back to the last whole percentage first. Each tick
    /// replaces the previous animation on the same property, so a hundred ticks a second still
    /// leaves exactly one clock attached.
    /// </summary>
    private static void OnFractionChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var bar = (BlockBar)d;
        var target = (double)e.NewValue;

        if (bar.ActualWidth <= 0)
        {
            // Never measured, so there is nothing to catch up towards and no cost to skipping.
            return;
        }

        var from = (double)bar.GetValue(FractionProperty);
        if (Math.Abs(from - target) < 0.0005)
        {
            return;
        }

        Motion.CatchUp(bar, FractionProperty, from, target);
    }

    /// <summary>Colour of a completed block.</summary>
    public Brush? BlockBrush
    {
        get => (Brush?)GetValue(BlockBrushProperty);
        set => SetValue(BlockBrushProperty, value);
    }

    /// <summary>Colour of an empty block.</summary>
    public Brush? EmptyBrush
    {
        get => (Brush?)GetValue(EmptyBrushProperty);
        set => SetValue(EmptyBrushProperty, value);
    }

    /// <summary>Block width in device pixels.</summary>
    public double BlockSize
    {
        get => (double)GetValue(BlockSizeProperty);
        set => SetValue(BlockSizeProperty, value);
    }

    /// <summary>Gap between blocks in device pixels.</summary>
    public double BlockGap
    {
        get => (double)GetValue(BlockGapProperty);
        set => SetValue(BlockGapProperty, value);
    }

    /// <inheritdoc />
    protected override Size MeasureOverride(Size availableSize) =>
        new(double.IsInfinity(availableSize.Width) ? 0 : availableSize.Width, BlockSize);

    /// <inheritdoc />
    protected override void OnRender(DrawingContext dc)
    {
        var scale = DpiMetrics.Scale;
        var period = Math.Max(1.0, (BlockSize + BlockGap) * scale);
        var block = BlockSize * scale;
        var w = Math.Round(ActualWidth * scale, MidpointRounding.AwayFromZero);
        var h = Math.Round(ActualHeight * scale, MidpointRounding.AwayFromZero);

        if (EmptyBrush is { } empty)
        {
            for (var x = 0.0; x < w; x += period)
            {
                var width = Math.Min(block, w - x);
                if (width > 0)
                {
                    dc.DrawRectangle(empty, null, new Rect(Snap(x, scale), 0, Snap(width, scale), h));
                }
            }
        }

        if (BlockBrush is not { } filled)
        {
            return;
        }

        // The filled pattern is clipped rather than re-laid-out, so its blocks stay aligned
        // with the empty ones and the bar does not shimmer as the fraction moves.
        var limit = Math.Round(w * Fraction, MidpointRounding.AwayFromZero);
        if (limit <= 0)
        {
            return;
        }

        dc.PushClip(new RectangleGeometry(new Rect(0, 0, Snap(limit, scale) / scale, ActualHeight)));
        for (var x = 0.0; x < limit; x += period)
        {
            var width = Math.Min(block, limit - x);
            if (width > 0)
            {
                dc.DrawRectangle(filled, null, new Rect(Snap(x, scale), 0, Snap(width, scale), h));
            }
        }

        dc.Pop();
    }

    private static double Snap(double devicePixels, double scale) =>
        Math.Round(devicePixels, MidpointRounding.AwayFromZero) / scale;
}
