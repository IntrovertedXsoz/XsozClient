using System.Windows;
using System.Windows.Controls;

namespace Xsoz.Launcher.Views;

/// <summary>
/// The profile drawer, hosted as an overlay by the shell.
///
/// Its one behaviour is taking focus when it appears, so that the ramp's left and right arrow
/// keys work the moment the drawer opens rather than after the user has tabbed their way to a
/// button. Opening a panel and having to tab once before the keyboard does anything is the sort
/// of thing a player does not notice consciously and then works around.
/// </summary>
public partial class ProfilePanelView : UserControl
{
    /// <summary>Creates the drawer.</summary>
    public ProfilePanelView()
    {
        InitializeComponent();
    }

    private void OnIsVisibleChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (e.NewValue is true)
        {
            // Deferred to Loaded rather than set here: at the moment the visibility changes the
            // drawer may not be connected to a presentation source yet, and Focus() on an
            // element that is in no tree is silently discarded.
            Loaded += OnDrawerLoaded;
        }
        else
        {
            Loaded -= OnDrawerLoaded;
        }
    }

    private void OnDrawerLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnDrawerLoaded;
        Ramp.TakeRampFocus();
    }
}
