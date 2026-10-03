using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace Xsoz.Launcher.Controls;

/// <summary>
/// True when a bound integer equals a fixed index.
///
/// A segmented control is a group of radio buttons, and a radio button is checked or it is not;
/// there is no "the model says the third one" state that the framework can do for you. Rather
/// than a MultiBinding per segment or a per-segment view model, each segment is given one of
/// these with its index set, and the group is wired by GroupName as normal.
///
/// The instances the segmented controls in the views use are declared in Themes/Controls.xaml
/// (CvIndex.Zero through CvIndex.Three).
/// </summary>
public sealed class IndexEqualsConverter : IValueConverter
{
    /// <summary>The index this instance compares against.</summary>
    public int Index { get; set; }

    /// <inheritdoc />
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is null)
        {
            return false;
        }

        var bound = value switch
        {
            int i => i,
            short s => (int)s,
            long l => (int)l,
            double d => (int)d,
            string text when int.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed) => parsed,
            _ => -1,
        };

        return bound == Index;
    }

    /// <inheritdoc />
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        // Unchecking a segment writes nothing: two radio buttons in a group briefly report
        // false during a change, and clearing the model on that would make the group flicker
        // between selections. Only a segment becoming checked writes, and it writes its own
        // index.
        return value is true ? Index : Binding.DoNothing;
    }
}
