using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace Xsoz.Launcher.Controls;

/// <summary>
/// Maps a string to <see cref="Visibility"/>: an empty or whitespace string is
/// <see cref="Visibility.Collapsed"/>, anything else is <see cref="Visibility.Visible"/>.
///
/// Used for the status banners. A banner whose text is empty should not leave a 40-DIP strip of
/// empty bordered box behind it, and a visibility converter is cheaper and less fragile than a
/// trigger per banner.
/// </summary>
[ValueConversion(typeof(string), typeof(Visibility))]
public sealed class StringToVisibility : IValueConverter
{
    /// <summary>Collapses on empty, shows otherwise. Pass "invert" as the parameter to flip it.</summary>
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var hasText = !string.IsNullOrWhiteSpace(value as string);
        var invert = string.Equals(parameter as string, "invert", StringComparison.OrdinalIgnoreCase);
        return hasText != invert ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>Not supported; this converter is one-way.</summary>
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException("StringToVisibility is a one-way converter.");
}
