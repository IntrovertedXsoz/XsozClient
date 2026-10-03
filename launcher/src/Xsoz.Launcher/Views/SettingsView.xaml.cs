using System.Windows.Controls;

namespace Xsoz.Launcher.Views;

/// <summary>
/// The Settings screen. Flat sections, label left and control right, and the launcher's own log at
/// the foot of Advanced. No code-behind beyond the constructor: the heap slider writes its value
/// as it moves, so there is no Apply button and nothing to apply it.
/// </summary>
public partial class SettingsView : UserControl
{
    /// <summary>Creates the view.</summary>
    public SettingsView() => InitializeComponent();

    /// <summary>
    /// The screen's own ScrollViewer. Public so the headless capture can scroll the real one to
    /// the bottom and photograph it; a copy laid out by the capture tool would be a layout only
    /// the capture tool can produce, which is worth nothing as evidence.
    /// </summary>
    public System.Windows.Controls.ScrollViewer Scroller => SettingsScroller;
}
