using System.Windows;
using System.Windows.Controls;

namespace Xsoz.Launcher.Views;

/// <summary>
/// The 72px bar every screen carries: the page title on the left, the profile chip and a compact
/// PLAY on the right.
///
/// The title is a dependency property rather than a literal so that Home can use the same bar with
/// nothing in it. Home's heading is the 48px version number inside its own card, and putting a
/// 32px "HOME" above that would be two headings for one screen.
/// </summary>
public partial class TopBarView : UserControl
{
    /// <summary>Identifies the <see cref="Title"/> property.</summary>
    public static readonly DependencyProperty TitleProperty = DependencyProperty.Register(
        nameof(Title), typeof(string), typeof(TopBarView),
        new PropertyMetadata(string.Empty));

    /// <summary>Identifies the <see cref="ShowCompactPlay"/> property.</summary>
    public static readonly DependencyProperty ShowCompactPlayProperty = DependencyProperty.Register(
        nameof(ShowCompactPlay), typeof(bool), typeof(TopBarView),
        new PropertyMetadata(true));

    /// <summary>Creates the bar.</summary>
    public TopBarView()
    {
        InitializeComponent();
    }

    /// <summary>The page title, in the display face. Empty on Home.</summary>
    public string Title
    {
        get => (string)GetValue(TitleProperty);
        set => SetValue(TitleProperty, value);
    }

    /// <summary>
    /// Whether the compact PLAY is shown. False on Home only.
    ///
    /// The design's rule is that every screen which is not Home carries one, so that no screen is
    /// ever more than a click from launching. Home already has the 96px version of the same
    /// command directly under the top bar, and a second green block forty pixels above it would be
    /// the loudest thing on a screen whose whole point is one button.
    /// </summary>
    public bool ShowCompactPlay
    {
        get => (bool)GetValue(ShowCompactPlayProperty);
        set => SetValue(ShowCompactPlayProperty, value);
    }
}
