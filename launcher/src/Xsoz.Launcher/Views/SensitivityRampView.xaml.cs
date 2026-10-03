using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Xsoz.Launcher.ViewModels;

namespace Xsoz.Launcher.Views;

/// <summary>
/// The sensitivity ramp, as a control.
///
/// The keyboard handling is here rather than in the view model for one reason: the design asks
/// for left and right arrows to step the ramp "while the ramp has focus", and WPF's focus lands
/// on whichever of the two buttons the tab order reaches first. Handling it on
/// <see cref="UIElement.PreviewKeyDown"/> at the control means the keys work identically whether
/// the container itself has focus or one of the buttons inside it does, and there is exactly one
/// place where that rule is written down.
///
/// Both keys move exactly one stage. There is no repeat-with-acceleration, no page step, and no
/// way to reach the end without pressing the key the number of times it takes - which is the
/// entire point of the control.
/// </summary>
public partial class SensitivityRampView : UserControl
{
    /// <summary>Creates the control.</summary>
    public SensitivityRampView()
    {
        InitializeComponent();
    }

    /// <summary>
    /// Moves the keyboard focus onto the control, so the arrow keys work the moment the drawer
    /// opens without the user having to tab to anything. Called by the drawer when it opens.
    /// </summary>
    public void TakeRampFocus() => Focus();

    private void OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (DataContext is not RampViewModel ramp)
        {
            return;
        }

        switch (e.Key)
        {
            case Key.Left:
                ramp.Slower();
                e.Handled = true;
                break;

            case Key.Right:
                ramp.Faster();
                e.Handled = true;
                break;
        }
    }
}
