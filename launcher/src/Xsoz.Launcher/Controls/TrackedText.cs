using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Xsoz.Launcher.Themes;

namespace Xsoz.Launcher.Controls;

/// <summary>
/// A text element that can set letter-spacing, because WPF cannot.
///
/// WHY THIS EXISTS. The design specifies <c>letter-spacing: .1em</c> on the PLAY
/// buttons, <c>.08em</c> on the brand wordmark, <c>.12em</c> on every 12px eyebrow and
/// <c>.06em</c> on the ramp buttons. WPF's TextBlock has no equivalent property and no
/// equivalent markup, and the two things that are sometimes used instead are both wrong:
/// inserting hair spaces changes the string that is copied out of the control and the
/// string a screen reader announces, and putting the text in a scaled transform stretches
/// the glyphs as well as the gaps. So the glyphs are laid out here, one child TextBlock per
/// character, and only the gaps are widened.
///
/// One real TextBlock per character, rather than a hand-rolled glyph loop, is deliberate: it
/// means every glyph still goes through the framework's own text layout, so
/// TextOptions.TextFormattingMode="Display" (which is what keeps Monocraft legible), font
/// fallback, ClearType and DPI rounding all keep working. Drawing them by hand through
/// FormattedText would have meant losing the Display formatting mode, because FormattedText
/// has no property for it.
///
/// It is used for DISPLAY text only - Monocraft headings and button labels, which are short
/// and few. Running text stays a real TextBlock, so it wraps, selects, copies and announces
/// exactly as the framework intends.
/// </summary>
public sealed class TrackedText : Panel
{
    /// <summary>The text to set.</summary>
    public static readonly DependencyProperty TextProperty = DependencyProperty.Register(
        nameof(Text), typeof(string), typeof(TrackedText),
        new FrameworkPropertyMetadata(string.Empty, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>Extra advance between glyphs, in thousandths of an em. 100 is 0.1em.</summary>
    public static readonly DependencyProperty TrackingProperty = DependencyProperty.Register(
        nameof(Tracking), typeof(double), typeof(TrackedText),
        new FrameworkPropertyMetadata(0d, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>The face. Monocraft for display text.</summary>
    public static readonly DependencyProperty DisplayFontFamilyProperty = DependencyProperty.Register(
        nameof(DisplayFontFamily), typeof(FontFamily), typeof(TrackedText),
        new FrameworkPropertyMetadata(new FontFamily("Monocraft"), FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>The size in device-independent units.</summary>
    public static readonly DependencyProperty DisplayFontSizeProperty = DependencyProperty.Register(
        nameof(DisplayFontSize), typeof(double), typeof(TrackedText),
        new FrameworkPropertyMetadata(16d, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>The weight.</summary>
    public static readonly DependencyProperty DisplayFontWeightProperty = DependencyProperty.Register(
        nameof(DisplayFontWeight), typeof(FontWeight), typeof(TrackedText),
        new FrameworkPropertyMetadata(FontWeights.Bold, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>
    /// The fill. This is <see cref="TextBlock.Foreground"/> with a second owner, not a new
    /// property, so <c>Foreground</c> set on this element, or inherited from a window or a
    /// control template, reaches every glyph exactly the way it would on a TextBlock.
    /// </summary>
    public static readonly DependencyProperty ForegroundProperty =
        TextBlock.ForegroundProperty.AddOwner(typeof(TrackedText));

    /// <summary>Horizontal placement inside the arranged box.</summary>
    public static readonly DependencyProperty TextAlignmentProperty = DependencyProperty.Register(
        nameof(TextAlignment), typeof(TextAlignment), typeof(TrackedText),
        new FrameworkPropertyMetadata(TextAlignment.Left, FrameworkPropertyMetadataOptions.AffectsArrange));

    /// <summary>The text to set.</summary>
    public string Text
    {
        get => (string)GetValue(TextProperty);
        set => SetValue(TextProperty, value);
    }

    /// <summary>Extra advance between glyphs, in thousandths of an em.</summary>
    public double Tracking
    {
        get => (double)GetValue(TrackingProperty);
        set => SetValue(TrackingProperty, value);
    }

    /// <summary>The face.</summary>
    public FontFamily DisplayFontFamily
    {
        get => (FontFamily)GetValue(DisplayFontFamilyProperty);
        set => SetValue(DisplayFontFamilyProperty, value);
    }

    /// <summary>The size.</summary>
    public double DisplayFontSize
    {
        get => (double)GetValue(DisplayFontSizeProperty);
        set => SetValue(DisplayFontSizeProperty, value);
    }

    /// <summary>The weight.</summary>
    public FontWeight DisplayFontWeight
    {
        get => (FontWeight)GetValue(DisplayFontWeightProperty);
        set => SetValue(DisplayFontWeightProperty, value);
    }

    /// <summary>The fill.</summary>
    public Brush Foreground
    {
        get => (Brush)GetValue(ForegroundProperty);
        set => SetValue(ForegroundProperty, value);
    }

    /// <summary>Horizontal placement inside the arranged box.</summary>
    public TextAlignment TextAlignment
    {
        get => (TextAlignment)GetValue(TextAlignmentProperty);
        set => SetValue(TextAlignmentProperty, value);
    }

    private double _extra;
    private double _naturalWidth;

    /// <inheritdoc />
    protected override void OnPropertyChanged(DependencyPropertyChangedEventArgs e)
    {
        base.OnPropertyChanged(e);

        // One rebuild path for all five inputs, rather than a property-changed callback on
        // each: DependencyProperty.Register's callback overloads are easy to get wrong in a
        // way the compiler catches, and this is the same amount of code.
        if (e.Property == TextProperty
            || e.Property == TrackingProperty
            || e.Property == DisplayFontFamilyProperty
            || e.Property == DisplayFontSizeProperty
            || e.Property == DisplayFontWeightProperty
            || e.Property == ForegroundProperty)
        {
            Rebuild();
        }
    }

    private void Rebuild()
    {
        InternalChildren.Clear();
        var text = Text ?? string.Empty;
        if (text.Length == 0)
        {
            _naturalWidth = 0;
            InvalidateMeasure();
            return;
        }

        foreach (var character in text)
        {
            var glyph = new TextBlock
            {
                Text = character.ToString(),
                FontFamily = DisplayFontFamily,
                FontSize = DisplayFontSize,
                FontWeight = DisplayFontWeight,
                TextWrapping = TextWrapping.NoWrap,
                TextTrimming = TextTrimming.None,

                // line-height: 1, so a 48px version number occupies exactly 48px and the
                // card's 24px padding is 24px.
                LineHeight = DisplayFontSize,
            };

            // Set rather than assigned, so the Display formatting mode the design asks for
            // survives the copy from this element into each glyph.
            TextOptions.SetTextFormattingMode(glyph, TextFormattingMode.Display);
            InternalChildren.Add(glyph);
        }

        InvalidateMeasure();
    }

    /// <inheritdoc />
    protected override Size MeasureOverride(Size availableSize)
    {
        var width = 0.0;
        var height = 0.0;
        var infinite = new Size(double.PositiveInfinity, double.PositiveInfinity);

        foreach (UIElement child in InternalChildren)
        {
            child.Measure(infinite);
            width += child.DesiredSize.Width;
            height = Math.Max(height, child.DesiredSize.Height);
        }

        _extra = InternalChildren.Count > 1 ? Tracking / 1000.0 * DisplayFontSize * (InternalChildren.Count - 1) : 0;
        _naturalWidth = width + _extra;

        // Height follows one line only. This control exists for short display strings; a
        // heading that needs to wrap is a heading that should have been a TextBlock.
        return new Size(double.IsInfinity(availableSize.Width) ? _naturalWidth : Math.Min(_naturalWidth, availableSize.Width), height);
    }

    /// <inheritdoc />
    protected override Size ArrangeOverride(Size finalSize)
    {
        var scale = DpiMetrics.Scale;
        var gap = InternalChildren.Count > 1 ? _extra / (InternalChildren.Count - 1) : 0;
        var x = TextAlignment switch
        {
            TextAlignment.Center => (finalSize.Width - _naturalWidth) / 2.0,
            TextAlignment.Right => finalSize.Width - _naturalWidth,
            _ => 0.0,
        };

        // Every glyph origin lands on a device pixel. At 150% that is the difference
        // between a wordmark whose stems are exactly 1.5px wide and one whose stems
        // shimmer as the window is dragged.
        x = Math.Round(x * scale, MidpointRounding.AwayFromZero) / scale;

        foreach (UIElement child in InternalChildren)
        {
            var w = child.DesiredSize.Width;
            child.Arrange(new Rect(Math.Round(x * scale, MidpointRounding.AwayFromZero) / scale, 0, w, child.DesiredSize.Height));
            x += w + gap;
        }

        return finalSize;
    }
}
