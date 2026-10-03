using System.Windows.Controls;

namespace Xsoz.Launcher.Views;

/// <summary>
/// The Home screen. A version card, the one primary button, the install block that replaces it,
/// and a status line. The code-behind is empty on purpose: every interaction routes through the
/// view model's commands, so the install state and the launch state can never disagree with what
/// the user sees.
/// </summary>
public partial class HomeView : UserControl
{
    /// <summary>Creates the view.</summary>
    public HomeView() => InitializeComponent();
}
