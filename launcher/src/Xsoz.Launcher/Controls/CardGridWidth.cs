using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace Xsoz.Launcher.Controls;

/// <summary>
/// Turns the width available to a card grid into the width of one card.
///
/// The module grid used to hard-code 440 DIP cards inside a WrapPanel, which is one card per row
/// with a 200-pixel dead gutter down the right-hand side at the default window size. A
/// WrapPanel measures its children against infinite width, so a percentage or a star width cannot
/// be used to fix it, and the card has to be told its width from outside - which is what this
/// converter is for. It reads the ItemsControl's own ActualWidth from inside the item template.
///
/// One card per row below <see cref="TwoColumnThreshold"/>; two above it. A card never comes out
/// narrower than <see cref="MinimumCardWidth"/>, so the two-column case always has a readable
/// measure, and the narrow case hands the full width to a single column.
///
/// An input of zero or a non-number means the host has not been measured yet. That returns NaN,
/// which WPF reads as "size to content", so the first pass is harmless and the second pass - once
/// a real width exists - sets the final one. Returning a literal zero here would collapse every
/// card to nothing and leave the grid blank.
/// </summary>
[ValueConversion(typeof(double), typeof(double))]
public sealed class CardGridWidth : IValueConverter
{
    /// <summary>The horizontal gap between two cards, matching the margin in the card template.</summary>
    public const double Gutter = 10;

    /// <summary>Smallest card width the two-column layout will produce.</summary>
    public const double MinimumCardWidth = 300;

    /// <summary>
    /// Available width at and above which two cards fit per row. Derived from the two constants
    /// above rather than written as a third number, because the arithmetic is easy to get wrong:
    /// a WrapPanel measures each child against infinite width, so a card's Margin counts towards
    /// the row, and two cards need two card widths plus two margins, not one.
    /// </summary>
    public const double TwoColumnThreshold = (MinimumCardWidth + Gutter) * 2;

    /// <summary>Computes the card width.</summary>
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is not double available || double.IsNaN(available) || available <= 0)
        {
            return double.NaN;
        }

        return available >= TwoColumnThreshold
            ? Math.Floor((available / 2) - Gutter)
            : available;
    }

    /// <summary>Not supported; this is a one-way measure.</summary>
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException("CardGridWidth is a one-way converter.");
}
