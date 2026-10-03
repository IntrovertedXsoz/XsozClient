using System.Diagnostics;
using System.Windows;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>
/// The Vanilla Tweaks link-out. Per docs/resource-pack-profile.md the pack is link-only: its own
/// "Terms and Conditions" do not grant redistribution, so the launcher never bundles, mirrors or
/// caches a byte of it. What a launcher can honestly do is open the generator and hand the user
/// the note about which option set the profile wants. Both actions are here, and neither of them
/// downloads anything.
/// </summary>
public sealed class ModrinthLinkService
{
    private const string GeneratorUrl = "https://vanillatweaks.net/picker/resource-packs";

    /// <summary>Opens the generator in the default browser, on the canonical host only.</summary>
    public void OpenGenerator()
    {
        try
        {
            Process.Start(new ProcessStartInfo(GeneratorUrl) { UseShellExecute = true })?.Dispose();
            AppLog.Shared.Info("Opened the Vanilla Tweaks generator in the browser. The pack itself is never downloaded or bundled by this launcher.");
        }
        catch (Exception ex)
        {
            AppLog.Shared.Warn("Could not open the browser: " + ex.Message);
        }
    }

    /// <summary>
    /// Copies the share note to the clipboard. Vanilla Tweaks share codes are a client-side SPA
    /// payload that the site itself never decodes upstream, so the launcher cannot generate one -
    /// the clipboard gets the option-set pointer instead, in a form the user can work from.
    /// </summary>
    public void CopyOptionNote()
    {
        try
        {
            Clipboard.SetText(
                "XsozClient competitive resource-pack set: the 110-option Vanilla Tweaks profile documented in "
                + "docs/resource-pack-profile.md (88 always-on, 20 default-off, 2 declared). Apply it yourself at "
                + GeneratorUrl);
            AppLog.Shared.Success("Copied the Vanilla Tweaks note to the clipboard.");
        }
        catch (Exception ex)
        {
            AppLog.Shared.Warn("Could not write to the clipboard: " + ex.Message);
        }
    }
}
