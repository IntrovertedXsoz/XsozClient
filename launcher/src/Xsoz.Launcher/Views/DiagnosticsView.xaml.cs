using System.Windows.Controls;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Views;

/// <summary>
/// The log viewer at the foot of Settings.
///
/// The filter is two radio buttons over the view model's own level name, so there is no selection
/// event to handle here and no second source of truth for "which lines am I looking at".
/// </summary>
public partial class DiagnosticsView : UserControl
{
    /// <summary>Creates the view.</summary>
    public DiagnosticsView()
    {
        InitializeComponent();
        Loaded += (_, _) => AppLog.Shared.Info("Log viewer opened.");
    }
}
