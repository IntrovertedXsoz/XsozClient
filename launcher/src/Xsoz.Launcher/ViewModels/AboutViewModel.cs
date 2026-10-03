using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Windows.Input;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Services;

namespace Xsoz.Launcher.ViewModels;

/// <summary>One row in the About screen's link list.</summary>
/// <param name="Label">The link's text.</param>
/// <param name="Note">The quiet half on the right.</param>
/// <param name="Command">What it does. Null for the entries that have nowhere to go yet.</param>
public sealed record AboutLink(string Label, string Note, RelayCommand? Command);

/// <summary>
/// The About screen. What this is, and where everything on disk lives.
///
/// It carries no tier badges, no compliance table, no score, and no account story. The account
/// surface is one chip in the rail; the legally required line is its own block at the top of
/// this screen; and the one sentence about not being a cheat client is the whole of the product
/// claim, in the first three paragraphs, in the plainest words available.
/// </summary>
public sealed class AboutViewModel : ObservableObject
{
    private readonly ModrinthLinkService _vanillaTweaks = new();

    /// <summary>Creates the screen.</summary>
    public AboutViewModel()
    {
        OpenPathsCommand = RelayCommand.Create(() => AppLog.Shared.OpenLogFolder());
        OpenVanillaTweaksCommand = RelayCommand.Create(_vanillaTweaks.OpenGenerator);
        OpenHeadRendererCommand = RelayCommand.Create(OpenHeadRenderer);

        Links =
        [
            // The first three point nowhere yet. The design shows them as a list with a muted
            // note on the right, and a link that goes nowhere is better than a link that lies
            // about where it goes - so the command is null and the row simply does nothing.
            new AboutLink("PvPHQ mod allowlist", "Sheet", null),
            new AboutLink("How the rules were researched", "Notes", null),
            new AboutLink("Third-party notices", "Fonts, libraries, mods", RelayCommand.Create(() => AppLog.Shared.OpenLogFolder())),

            // The Vanilla Tweaks generator lived in a card at the foot of the Mods screen in the
            // previous build. The design's Mods screen has no such card, so the feature moved
            // here rather than being dropped: it is third-party content, and this is the screen
            // that says what the third-party content is.
            new AboutLink("Vanilla Tweaks generator", "Resource pack options", OpenVanillaTweaksCommand),

            // The skin head in the account chip is a rendered image from somebody else's service,
            // so it gets a line here. Nothing requires it - mcheads.org asks for no attribution -
            // and it is here anyway, because a launcher that fetches a picture of your face from a
            // third party should say whose server drew it. One line, same list, same row.
            new AboutLink("Skin heads by mcheads.org", "Open source, unaffiliated", OpenHeadRendererCommand),
        ];
    }

    /// <summary>The service that renders the account chip's skin head, stated once.</summary>
    private const string HeadRendererUrl = "https://mcheads.org";

    /// <summary>Opens the head renderer in the default browser, on the canonical host only.</summary>
    private static void OpenHeadRenderer()
    {
        try
        {
            Process.Start(new ProcessStartInfo(HeadRendererUrl) { UseShellExecute = true })?.Dispose();
            AppLog.Shared.Info("Opened the mcheads.org skin-head renderer in the browser. Nothing was uploaded.");
        }
        catch (Exception ex)
        {
            AppLog.Shared.Warn("Could not open the browser: " + ex.Message);
        }
    }

    /// <summary>The link list at the foot of the screen.</summary>
    public ObservableCollection<AboutLink> Links { get; }

    /// <summary>Opens the launcher's log folder.</summary>
    public RelayCommand OpenPathsCommand { get; }

    /// <summary>Opens the Vanilla Tweaks generator.</summary>
    public RelayCommand OpenVanillaTweaksCommand { get; }

    /// <summary>Opens the mcheads.org skin-head renderer.</summary>
    public RelayCommand OpenHeadRendererCommand { get; }

    /// <summary>Version string.</summary>
    public string VersionText { get; } =
        "XsozClient " + (typeof(AboutViewModel).Assembly.GetName().Version?.ToString(3) ?? "0.1.0");

    /// <summary>"1.21.11 · Fabric 0.19.5 · Java 21".</summary>
    public string GameText { get; } = GameVersion();

    /// <summary>Where everything the launcher writes lives.</summary>
    public string DataText => "%LOCALAPPDATA%\\XsozClient";

    private static string GameVersion()
    {
        try
        {
            var version = new ProfileService().SelectedVersion;
            return $"{version.Label} · {version.LoaderName} {version.LoaderVersion} · Java {version.JavaMajor}";
        }
        catch (Exception)
        {
            // About is the screen a person opens when something else is already wrong. It must
            // render, whatever the data files say.
            return "1.21.11";
        }
    }
}
