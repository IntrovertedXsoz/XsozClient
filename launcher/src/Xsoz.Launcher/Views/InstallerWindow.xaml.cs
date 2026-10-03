using System.IO;
using System.Windows;
using System.Windows.Media;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Services;

namespace Xsoz.Launcher.Views;

/// <summary>
/// The installer: finds the official Minecraft Launcher's game folder (or asks for it), adds
/// Xsoz Client to it as a new version, and then says, step by step, where to find it. The game
/// itself runs from the official launcher - signed in with the player's own account - in its own
/// folder, so every install is a fresh one (the intro plays on first start).
/// </summary>
public partial class InstallerWindow : Window
{
    private string _folder;
    private bool _busy;

    public InstallerWindow()
    {
        InitializeComponent();
        _folder = MinecraftDirectory.Default.Root;
        ShowFolder();
    }

    // ------------------------------------------------------------------ the Minecraft folder

    /// <summary>A folder the official launcher has used: it has its profile list or versions.</summary>
    private static bool LooksLikeMinecraft(string path) =>
        Directory.Exists(path)
        && (File.Exists(Path.Combine(path, MinecraftDirectory.DefaultProfilesFileName))
            || File.Exists(Path.Combine(path, MinecraftDirectory.StoreVariantProfilesFileName))
            || Directory.Exists(Path.Combine(path, "versions")));

    private void ShowFolder()
    {
        PathText.Text = _folder;
        if (LooksLikeMinecraft(_folder))
        {
            PathStatus.Foreground = (Brush)FindResource("B.Accent");
            PathStatus.Text = "Found your Minecraft folder.";
            InstallButton.IsEnabled = true;
        }
        else if (Directory.Exists(_folder))
        {
            PathStatus.Foreground = (Brush)FindResource("B.Warn");
            PathStatus.Text = "This folder doesn't look like Minecraft's game folder (there's no launcher_profiles.json in it). "
                              + "Pick your .minecraft folder - or open the Minecraft Launcher once so it sets itself up, then come back.";
            InstallButton.IsEnabled = false;
        }
        else
        {
            PathStatus.Foreground = (Brush)FindResource("B.Warn");
            PathStatus.Text = "We couldn't find Minecraft in the usual place. If you have the Minecraft Launcher, click Browse and "
                              + "choose your .minecraft folder - it's normally in %APPDATA%\\.minecraft (type %appdata% into the "
                              + "Windows search bar to open it). If you don't have the Minecraft Launcher yet, install it from "
                              + "minecraft.net, open it once, then run this installer again.";
            InstallButton.IsEnabled = false;
        }
    }

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        if (_busy)
        {
            return;
        }

        var dialog = new Microsoft.Win32.OpenFolderDialog
        {
            Title = "Choose your .minecraft folder",
            InitialDirectory = Directory.Exists(_folder) ? _folder : Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        };
        if (dialog.ShowDialog(this) == true)
        {
            _folder = dialog.FolderName;
            ErrorText.Text = string.Empty;
            ShowFolder();
        }
    }

    // ------------------------------------------------------------------ installing

    private async void Install_Click(object sender, RoutedEventArgs e)
    {
        if (_busy || !LooksLikeMinecraft(_folder))
        {
            return;
        }

        _busy = true;
        InstallButton.IsEnabled = false;
        BrowseButton.IsEnabled = false;
        ErrorText.Text = string.Empty;
        Progress.Visibility = Visibility.Visible;
        WorkText.Text = "Installing - downloading Fabric and Minecraft's files. This can take a minute...";
        try
        {
            var net = new DownloadService();
            var provisioner = new VanillaProvisioner(net, GameWritePolicy.CreateWriter(net), new MinecraftDirectory(_folder));
            var result = await provisioner.RunAsync(CancellationToken.None).ConfigureAwait(true);
            if (result.Succeeded)
            {
                SetupPanel.Visibility = Visibility.Collapsed;
                DonePanel.Visibility = Visibility.Visible;
                DoneDetail.Text = "Installed into " + _folder + ". Xsoz keeps its own settings and worlds separately, so your normal Minecraft stays exactly as it was.";
                return;
            }

            ErrorText.Text = result.BlockedByRunningLauncher
                ? "The Minecraft Launcher is open. Close it completely (check the system tray too), then press Install again."
                : result.Message;
        }
        catch (Exception ex)
        {
            AppLog.Shared.Error("Installer failed: " + ex.Message);
            ErrorText.Text = "The install stopped: " + ex.Message + " Check your internet connection and press Install again.";
        }
        finally
        {
            _busy = false;
            Progress.Visibility = Visibility.Collapsed;
            WorkText.Text = string.Empty;
            InstallButton.IsEnabled = LooksLikeMinecraft(_folder);
            BrowseButton.IsEnabled = true;
        }
    }

    private void OpenLauncher_Click(object sender, RoutedEventArgs e)
    {
        OpenText.Text = LauncherHandOff.Open();
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    /// <summary>Verification: renders the window as it is (or its "done" page) to a PNG.</summary>
    public void SaveShotForTests(string path, bool done)
    {
        SetupPanel.Visibility = done ? Visibility.Collapsed : Visibility.Visible;
        DonePanel.Visibility = done ? Visibility.Visible : Visibility.Collapsed;
        if (done) DoneDetail.Text = "Installed into " + _folder + ".";
        UpdateLayout();
        var root = (FrameworkElement)Content;
        var bmp = new System.Windows.Media.Imaging.RenderTargetBitmap((int)root.ActualWidth, (int)root.ActualHeight, 96, 96, PixelFormats.Pbgra32);
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            dc.DrawRectangle((Brush)FindResource("B.Bg"), null, new Rect(0, 0, root.ActualWidth, root.ActualHeight));
            dc.DrawRectangle(new VisualBrush(root), null, new Rect(0, 0, root.ActualWidth, root.ActualHeight));
        }
        bmp.Render(visual);
        var enc = new System.Windows.Media.Imaging.PngBitmapEncoder();
        enc.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(bmp));
        using var f = File.Create(path);
        enc.Save(f);
    }
}
