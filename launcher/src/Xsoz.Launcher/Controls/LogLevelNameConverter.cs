using System.Globalization;
using System.Windows.Data;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Controls;

/// <summary>
/// True when a level name is the one this converter stands for.
///
/// The log filter is two radio buttons over a nullable severity, and the view model holds the
/// severity as a string so the combo in the old layout and the segmented control in the new one
/// can share it. This is the same trick as <see cref="IndexEqualsConverter"/>, for a string
/// instead of an index: only a segment becoming checked writes, and it writes its own name.
/// </summary>
public sealed class LogLevelNameConverter : IValueConverter
{
    /// <summary>The level name this instance stands for: "ALL", "WARN" and so on.</summary>
    public string LevelName { get; set; } = "ALL";

    /// <inheritdoc />
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        string.Equals(value as string, LevelName, StringComparison.OrdinalIgnoreCase);

    /// <inheritdoc />
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is true ? LevelName : System.Windows.Data.Binding.DoNothing;
}
