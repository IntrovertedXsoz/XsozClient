using System.Windows.Controls;

namespace Xsoz.Launcher.Views;

/// <summary>
/// The Mods screen. A search box, a count and flat rows that open in place. No code-behind beyond
/// the constructor: the header is a real button so Enter and Space open a row, and everything
/// else is a binding into the view model.
/// </summary>
public partial class ModsView : UserControl
{
    /// <summary>Creates the view.</summary>
    public ModsView() => InitializeComponent();
}
