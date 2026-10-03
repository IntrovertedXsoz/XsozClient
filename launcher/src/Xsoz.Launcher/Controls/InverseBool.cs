using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Markup;

namespace Xsoz.Launcher.Controls;

/// <summary>
/// A <see cref="BooleanToVisibilityConverter"/> with the mapping reversed: true becomes
/// <see cref="Visibility.Collapsed"/> and false becomes <see cref="Visibility.Visible"/>.
///
/// The framework converter only goes one way, and the pattern it cannot express without a
/// second binding is common enough in this UI — "show this label while the button is idle" —
/// that it is worth the thirty lines rather than a pile of DataTriggers.
/// </summary>
[ValueConversion(typeof(bool), typeof(Visibility))]
public sealed class InverseBool : MarkupExtension, IValueConverter
{
    /// <summary>Inverts the mapping.</summary>
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is true ? Visibility.Collapsed : Visibility.Visible;

    /// <summary>Inverts the mapping back.</summary>
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is Visibility.Collapsed;

    /// <summary>Returns a shared instance; the converter is stateless.</summary>
    public override object ProvideValue(IServiceProvider serviceProvider) => this;
}
