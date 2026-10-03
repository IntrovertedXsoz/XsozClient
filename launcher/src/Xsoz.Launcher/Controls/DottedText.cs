using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using Xsoz.Launcher.Themes;

namespace Xsoz.Launcher.Controls;

/// <summary>One segment of a dotted status line, and whether it is dimmed.</summary>
/// <param name="Text">The words.</param>
/// <param name="Dim">True for the quiet parts, which the design renders in the muted colour.</param>
public readonly record struct DottedSegment(string Text, bool Dim);

/// <summary>
/// A one-line status read-out whose separators are quieter than its content.
///
/// The design writes the Home status line as <c>1.21.11 · Fabric · Guest · not installed</c>
/// with the dots in <c>--text-3</c> and the words in <c>--text-2</c>. That needs two colours inside
/// one TextBlock, which means Inlines rather than Text, which means the view has to assemble
/// the runs - and a view that assembles its own runs is a view that has a string in it.
///
/// So the segments arrive as data and the runs are built here, once, in one place. Nothing is
/// concatenated into a single string, which means the line still reads correctly to a screen
/// reader and still trims correctly when the window is narrow.
/// </summary>
public sealed class DottedText : TextBlock
{
    /// <summary>The segments, in order. Joined with a middle dot between them.</summary>
    public static readonly DependencyProperty SegmentsProperty = DependencyProperty.Register(
        nameof(Segments), typeof(IEnumerable<DottedSegment>), typeof(DottedText),
        new PropertyMetadata(null, (d, _) => ((DottedText)d).Rebuild()));

    /// <summary>The separator drawn between segments.</summary>
    public static readonly DependencyProperty SeparatorProperty = DependencyProperty.Register(
        nameof(Separator), typeof(string), typeof(DottedText),
        new PropertyMetadata("· ", (d, _) => ((DottedText)d).Rebuild()));

    /// <summary>The colour of the segments that are not dimmed.</summary>
    public static readonly DependencyProperty SegmentBrushProperty = DependencyProperty.Register(
        nameof(SegmentBrush), typeof(Brush), typeof(DottedText),
        new PropertyMetadata(null, (d, _) => ((DottedText)d).Rebuild()));

    /// <summary>The colour of the separators and of the dimmed segments.</summary>
    public static readonly DependencyProperty DimBrushProperty = DependencyProperty.Register(
        nameof(DimBrush), typeof(Brush), typeof(DottedText),
        new PropertyMetadata(null, (d, _) => ((DottedText)d).Rebuild()));

    /// <summary>The segments, in order.</summary>
    public IEnumerable<DottedSegment> Segments
    {
        get => (IEnumerable<DottedSegment>?)GetValue(SegmentsProperty) ?? [];
        set => SetValue(SegmentsProperty, value);
    }

    /// <summary>The separator drawn between segments.</summary>
    public string Separator
    {
        get => (string)GetValue(SeparatorProperty);
        set => SetValue(SeparatorProperty, value);
    }

    /// <summary>The colour of the segments that are not dimmed.</summary>
    public Brush? SegmentBrush
    {
        get => (Brush?)GetValue(SegmentBrushProperty);
        set => SetValue(SegmentBrushProperty, value);
    }

    /// <summary>The colour of the separators and of the dimmed segments.</summary>
    public Brush? DimBrush
    {
        get => (Brush?)GetValue(DimBrushProperty);
        set => SetValue(DimBrushProperty, value);
    }

    private void Rebuild()
    {
        Inlines.Clear();

        // Application.Current rather than FindResource: the runs are built the first time the
        // Segments binding lands, which can be before this element is connected to a tree,
        // and a resource lookup that walks the tree finds nothing at that moment.
        var resources = Application.Current?.Resources;
        var normal = SegmentBrush ?? Foreground ?? resources?["B.Text2"] as Brush ?? Brushes.Gray;
        var dim = DimBrush ?? resources?["B.Text3"] as Brush ?? Brushes.DimGray;
        var first = true;

        foreach (var segment in Segments)
        {
            if (segment.Text.Length == 0)
            {
                continue;
            }

            if (!first)
            {
                Inlines.Add(new Run(Separator) { Foreground = dim });
            }

            Inlines.Add(new Run(segment.Text) { Foreground = segment.Dim ? dim : normal });
            first = false;
        }
    }
}
