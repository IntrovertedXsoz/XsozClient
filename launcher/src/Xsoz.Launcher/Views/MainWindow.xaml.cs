using System.ComponentModel;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Themes;
using Xsoz.Launcher.ViewModels;

namespace Xsoz.Launcher.Views;

/// <summary>
/// The application window. Owns the custom chrome, the navigation, the global shortcuts, the
/// screen transition and the profile drawer.
///
/// The two things that move are the ones the design names: a 140 ms fade when the screen changes,
/// and the 180 ms slide of the profile drawer with its scrim. Both go through
/// <see cref="Motion"/>, which owns the durations, the easing, the interruption behaviour and the
/// reduced-motion switch, so no property in this file is animated with a hand-written curve and
/// nothing here can animate a layout property.
/// </summary>
public partial class MainWindow : Window
{
    /// <summary>The drawer's width, and therefore how far off-screen it starts.</summary>
    private const double DrawerWidth = 480;

    private MainViewModel? _viewModel;
    private Screen _shownScreen = Screen.Home;
    private bool _disposed;

    /// <summary>Creates the window.</summary>
    public MainWindow()
    {
        InitializeComponent();
        DataContext = new MainViewModel();
        _viewModel = (MainViewModel)DataContext;

        SourceInitialized += OnSourceInitialized;
        StateChanged += OnStateChanged;
        Closing += OnClosing;

        // Reduced motion is honoured before anything animates. Motion reads the system preference
        // itself and keeps following it, so this is a re-read rather than the only read: the
        // preference may have changed since the type was first touched, and the design says all of
        // it turns off under reduced motion. Nothing in this app needs an animation to be usable,
        // so this costs nothing.
        Motion.Enabled = Motion.SystemPrefersReducedMotion;

        // Closed, not Closing: Closing is cancelled whenever the preference is close-to-tray, and
        // the shell in that case has not gone anywhere. Disposal belongs to the window that has
        // actually gone.
        Closed += OnClosed;
    }

    /// <summary>
    /// Releases the shell. The screens behind it hold subscriptions to things that outlive a window
    /// - the process-wide log, the game process, an in-flight download - and each of them can call
    /// back after the visual tree has gone. This is the point where they are told not to.
    /// </summary>
    private void OnClosed(object? sender, EventArgs e)
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _viewModel?.Dispose();
    }

    private void OnSourceInitialized(object? sender, EventArgs e)
    {
        WindowChromeNative.ApplyDarkChrome(this);

        // Hairlines become one device pixel from here on, and are re-evaluated whenever the
        // window moves to a display with a different scale.
        DpiMetrics.Attach(this);

        // The chip's skin head is rescaled to the chip's exact device-pixel size, so a move to a
        // display with a different scale factor means the pixels have to be re-sampled. DpiMetrics
        // owns the value; this only asks for the redraw. The head itself is unchanged - same name,
        // same cache file - so this is not a network refresh, and it goes through the same gate as
        // every other head request.
        if (PresentationSource.FromVisual(this) is HwndSource source)
        {
            source.DpiChanged += (_, _) => _viewModel?.RefreshAccountHeadForDisplay();
        }
    }

    private void OnStateChanged(object? sender, EventArgs e)
    {
        MaxButton.ToolTip = WindowState == WindowState.Maximized ? "Restore" : "Maximise";
        System.Windows.Automation.AutomationProperties.SetName(MaxButton, MaxButton.ToolTip as string);
    }

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        // Close-to-tray only hides the window when there IS a tray icon to bring it back from.
        // Before the icon existed this hid the launcher with no way back, and every later start
        // added another invisible copy.
        if (_viewModel?.Store.Settings.CloseToTray == true && !App.QuitRequested && App.Tray is { IsVisible: true })
        {
            // The game is a separate process either way, so nothing about a running game is affected.
            e.Cancel = true;
            Hide();
            AppLog.Shared.Info("Closed to tray. Click the XsozClient icon in the notification area to reopen it.");
        }

        // Otherwise the window really closes; it is the last window (the tray is not one), so the
        // process ends with it.
    }

    private void OnMinimize(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private void OnToggleMaximize(object sender, RoutedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void OnClose(object sender, RoutedEventArgs e) => Close();

    /// <summary>
    /// The drag-region handler. With WindowChrome CaptionHeight=40 the top strip already answers
    /// WM_NCHITTEST with HTCAPTION, which is the Windows-native drag; this handler is the explicit
    /// DragMove fallback for the one case hit-testing cannot cover - the label text sitting inside
    /// the region, which is hit-test-visible and would otherwise swallow the press.
    /// </summary>
    private void OnTitleBarMouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton == MouseButton.Left && e.ButtonState == MouseButtonState.Pressed)
        {
            try
            {
                DragMove();
                e.Handled = true;
            }
            catch (InvalidOperationException)
            {
                // DragMove only runs off a real mouse press; a synthetic call lands nowhere.
            }
        }
    }

    /// <inheritdoc />
    protected override void OnPreviewKeyDown(KeyEventArgs e)
    {
        base.OnPreviewKeyDown(e);

        // Escape closes the drawer before it does anything else. The drawer is an overlay, and
        // the one key everybody expects to leave an overlay.
        if (e.Key == Key.Escape && _viewModel is { IsProfilePanelOpen: true })
        {
            _viewModel.CloseProfilePanelCommand.Execute(null);
            e.Handled = true;
            return;
        }

        // Plain digits navigate, but only when nothing that consumes text has focus, so typing
        // "1" into a numeric field does not teleport the user to another screen.
        if (e.Key is Key.D1 or Key.D2 or Key.D3 or Key.D4)
        {
            if (Keyboard.FocusedElement is System.Windows.Controls.TextBox
                or System.Windows.Controls.ComboBox
                or System.Windows.Controls.Primitives.TextBoxBase)
            {
                return;
            }

            if (_viewModel?.HandleShortcut(e.Key, Keyboard.Modifiers == ModifierKeys.Control) == true)
            {
                e.Handled = true;
            }
        }
    }

    /// <inheritdoc />
    protected override void OnContentRendered(EventArgs e)
    {
        base.OnContentRendered(e);
        EstablishFirstRender();
    }

    /// <summary>
    /// Whether the shell moves. Turned off by the headless capture mode, so a capture taken during
    /// an entrance window cannot be a half-faded screen or a half-slid drawer.
    ///
    /// It is separate from <see cref="Motion.Enabled"/>, which is the user's reduced-motion
    /// preference. This one belongs to the tool that takes the picture.
    /// </summary>
    internal bool MotionEnabled { get; set; } = true;

    /// <summary>
    /// Identifies the <see cref="IsDrawerShown"/> property.
    ///
    /// The drawer layer's visibility, owned here rather than bound straight to the view model. The
    /// view model knows whether the drawer is open; only the window knows how long it takes to
    /// arrive, and a layer that collapsed the instant the flag went false would cut the slide off
    /// after one frame.
    /// </summary>
    public static readonly DependencyProperty IsDrawerShownProperty = DependencyProperty.Register(
        nameof(IsDrawerShown), typeof(bool), typeof(MainWindow), new PropertyMetadata(false));

    /// <summary>True while the drawer layer should be on screen - which includes the slide out.</summary>
    public bool IsDrawerShown
    {
        get => (bool)GetValue(IsDrawerShownProperty);
        set => SetValue(IsDrawerShownProperty, value);
    }

    /// <summary>
    /// The Settings screen's own ScrollViewer. Exposed so the headless capture can scroll the
    /// real one rather than re-arranging a copy, which is the only way a still of the bottom of a
    /// long screen is evidence of anything.
    /// </summary>
    public System.Windows.Controls.ScrollViewer SettingsScroller => SettingsScreen.Scroller;

    /// <summary>
    /// Brings the shell to the state it reaches after its first paint, without needing a window to
    /// have been presented. Shared by <see cref="OnContentRendered"/> and the offscreen capture.
    /// </summary>
    internal void EstablishFirstRender()
    {
        if (_viewModel is not null)
        {
            ShowScreen(_viewModel.Current, animate: false);

            // The head is started here rather than in the view model's constructor because the
            // image is rescaled to the chip's device-pixel size, and this is the first moment the
            // window is realised and DpiMetrics knows the monitor's scale factor. In the headless
            // capture this is also the only path into it, and that path reads the disk cache rather
            // than the network.
            _viewModel.BeginAccountHead();
        }

        AppLog.Shared.Info("Main window rendered.");
    }

    private void NavigateTo(Screen screen)
    {
        if (_viewModel is null)
        {
            return;
        }

        ShowScreen(screen, animate: screen != _shownScreen);
        _shownScreen = screen;
    }

    /// <summary>Makes exactly one screen visible, in one place.</summary>
    private void ShowScreen(Screen screen, bool animate)
    {
        foreach (var (candidate, element) in Screens)
        {
            element.Visibility = candidate == screen ? Visibility.Visible : Visibility.Collapsed;
            if (candidate == screen && animate)
            {
                // 140 ms, ease-out, opacity only, on the design's single navigation transition.
                // No slide and no direction: the design asks for a fade, and a directional
                // transition would have to invent a rule for the Back key.
                Motion.FadeIn(element);
            }
        }
    }

    /// <summary>
    /// Opens or closes the profile drawer, and owns the travel.
    ///
    /// Opening: the layer goes up immediately (the panel has to be in the tree to be measured),
    /// the panel is parked one full drawer-width off the right edge, and the slide starts on the
    /// next render pass. Parking before the first frame is what stops the panel appearing at its
    /// resting place for one frame and then jumping backwards.
    ///
    /// Closing: the scrim goes at once, because a scrim that lingers reads as a second clickable
    /// layer, and the panel's layer stays up until the slide has finished.
    /// </summary>
    private void SetDrawerOpen(bool open)
    {
        if (_disposed)
        {
            return;
        }

        if (!MotionEnabled)
        {
            // The capture path. No animation at all, in either direction, so the still is the
            // drawer in its resting place rather than a panel caught 40 ms into a slide.
            Motion.ParkTranslate(Drawer, open ? 0 : DrawerWidth);
            DrawerScrim.Opacity = 1;
            IsDrawerShown = open;
            return;
        }

        if (open)
        {
            if (IsDrawerShown)
            {
                // Already up - a second open while a close is still in flight. Land it rather than
                // leaving two slides fighting over the same property.
                Motion.ParkTranslate(Drawer, 0);
                Motion.FadeTo(DrawerScrim, 1);
                return;
            }

            IsDrawerShown = true;
            Motion.ParkTranslate(Drawer, DrawerWidth);
            Motion.FadeTo(DrawerScrim, 0);

            // DispatcherPriority.Render: after layout, so the park is the state the first painted
            // frame sees, and before input, so the drawer is not yet clickable mid-slide-in.
            Dispatcher.BeginInvoke(DispatcherPriority.Render, StartDrawerEntrance);
            return;
        }

        if (!IsDrawerShown)
        {
            return;
        }

        Motion.FadeTo(DrawerScrim, 0);
        Motion.SlideOut(Drawer, DrawerWidth, () =>
        {
            if (!_disposed)
            {
                IsDrawerShown = false;
            }
        });
    }

    private void StartDrawerEntrance()
    {
        if (_disposed || !IsDrawerShown)
        {
            return;
        }

        Motion.SlideIn(Drawer, DrawerWidth);
        Motion.FadeTo(DrawerScrim, 1);
    }

    /// <summary>Every screen and its element, in navigation order.</summary>
    private IEnumerable<(Screen Screen, UIElement Element)> Screens
    {
        get
        {
            yield return (Screen.Home, HomeScreen);
            yield return (Screen.Mods, ModsScreen);
            yield return (Screen.Settings, SettingsScreen);
            yield return (Screen.About, AboutScreen);
        }
    }

    /// <inheritdoc />
    protected override void OnPropertyChanged(DependencyPropertyChangedEventArgs e)
    {
        base.OnPropertyChanged(e);

        if (e.Property == DataContextProperty && e.NewValue is MainViewModel vm)
        {
            vm.PropertyChanged += OnViewModelPropertyChanged;
        }
    }

    /// <summary>
    /// Queues the screen change rather than performing it inside the notification.
    ///
    /// The original used <c>Dispatcher.Invoke(Delegate, object)</c>, which is the *asynchronous*
    /// overload and so already deferred - but at normal priority, and with no guard for a shell that
    /// has been disposed. A queued navigation that lands after close would reparent the screens of a
    /// window that no longer exists; and at normal priority it can be overtaken by the layout pass it
    /// was raised from, which is the other half of the arrangement this fix removes. Background
    /// priority puts it after the render pass, the same rule the log pane's appends follow.
    /// </summary>
    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (_disposed || sender is not MainViewModel vm)
        {
            return;
        }

        if (args.PropertyName == nameof(MainViewModel.Current))
        {
            Dispatcher.BeginInvoke(NavigateTo, DispatcherPriority.Background, vm.Current);
        }
        else if (args.PropertyName == nameof(MainViewModel.IsProfilePanelVisible))
        {
            SetDrawerOpen(vm.IsProfilePanelOpen);
        }
        else if (args.PropertyName == nameof(MainViewModel.AccountHead))
        {
            FadeInAccountHead();
        }
    }

    /// <summary>
    /// Fades the skin head in over the design's own 140 ms, once, when one arrives.
    /// <para>
    /// Through <see cref="Motion.FadeIn"/> like every other fade in this product, and for the same
    /// reason: Opacity is a composition property, the duration and curve live in one file, and the
    /// reduced-motion switch turns it off. Nothing here calls BeginAnimation.
    /// </para>
    /// </summary>
    private void FadeInAccountHead()
    {
        if (_disposed || !MotionEnabled || !AccountHeadImage.IsVisible)
        {
            return;
        }

        Motion.FadeIn(AccountHeadImage);
    }
}
