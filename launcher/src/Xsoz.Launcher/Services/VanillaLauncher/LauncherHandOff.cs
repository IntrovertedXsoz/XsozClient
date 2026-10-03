using System.Diagnostics;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>
/// Opens the official Minecraft launcher, and says honestly what it can and cannot do.
///
/// WHAT WORKS. Starting the launcher itself is supported and documented for both variants:
/// the Store build is a UWP package, and the only supported way for a non-packaged process to
/// start one is through the shell's AppsFolder; the legacy build is an ordinary executable.
///
/// WHAT DOES NOT WORK, and is not pretended to: telling it WHICH installation to launch. The
/// documented command-line surface (<c>--workDir</c>, <c>--launcherComponentDir</c>, the profile
/// and version selectors) applies to the legacy Unified Launcher only, and the Minecraft Wiki
/// says so explicitly: <c>"On Windows, these options only apply to the old Unified Minecraft
/// Launcher and NOT the Microsoft Store version."</c> Mojang discontinued that launcher in March
/// 2022, it is not installed on a current machine, and it cannot authenticate a Microsoft account
/// anyway. Quick Play exists in the current build and <c>launcher_settings.json</c> has
/// <c>quickPlayEnabled: true</c>, but no argument format for targeting an installation id is
/// documented anywhere, so nothing here guesses one.
///
/// So the hand-off is: open the launcher, and tell the user which installation to pick. The
/// installation is named <c>xsoz-client</c> precisely so it is findable in the list, and this
/// class refuses to invent a flag that might silently do nothing.
/// </summary>
public static class LauncherHandOff
{
    /// <summary>The Store launcher package family name.</summary>
    public const string StorePackageFamilyName = "Microsoft.4297127D64EC6";

    /// <summary>The Store launcher's application user model id, verified from its package manifest.</summary>
    public const string StoreAumid = StorePackageFamilyName + "_8wekyb3d8bbwe!Minecraft";

    /// <summary>How the interface is told, in one line, that the hand-off is two steps.</summary>
    public const string NoCliNotice =
        "The Minecraft Launcher has no documented way to start a chosen installation, so pick 'xsoz-client' from its list.";

    /// <summary>
    /// True when the Store launcher package is installed. Detected by path rather than by
    /// <c>Get-AppxPackage</c>, because that cmdlet is not available to a normal desktop
    /// application and because Store apps have no entry in the Uninstall registry - a registry
    /// scan for "Minecraft" returns nothing on a machine that unquestionably has it.
    /// </summary>
    public static bool IsStoreLauncherInstalled()
    {
        try
        {
            var packages = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Packages");

            if (!Directory.Exists(packages))
            {
                return false;
            }

            return Directory
                .EnumerateDirectories(packages, StorePackageFamilyName + "*")
                .Any();
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>The legacy launcher's executable path, or null when it is not installed.</summary>
    public static string? FindLegacyLauncher()
    {
        foreach (var programFiles in new[]
                 {
                     Environment.GetEnvironmentVariable("ProgramFiles"),
                     Environment.GetEnvironmentVariable("ProgramFiles(x86)"),
                 })
        {
            if (string.IsNullOrWhiteSpace(programFiles))
            {
                continue;
            }

            var candidate = Path.Combine(programFiles, "Minecraft Launcher", "MinecraftLauncher.exe");
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return null;
    }

    /// <summary>
    /// Which launcher variant is present, or none. Returns one of <c>store</c>, <c>legacy</c> or
    /// <c>none</c>.
    /// </summary>
    public static string DetectVariant() => IsStoreLauncherInstalled()
        ? "store"
        : FindLegacyLauncher() is not null ? "legacy" : "none";

    /// <summary>
    /// Opens the official launcher. Returns one sentence saying what happened; never throws, never
    /// shows a dialog, and never tries to focus or drive its window.
    /// </summary>
    public static string Open()
    {
        try
        {
            // The legacy launcher first when it is installed: it can be started by path, and it
            // accepts the documented --workDir that the Store build does not.
            var legacy = FindLegacyLauncher();
            if (legacy is not null)
            {
                using var process = Process.Start(new ProcessStartInfo(legacy) { UseShellExecute = true });
                if (process is not null)
                {
                    AppLog.Shared.Info("Vanilla install: started the legacy Minecraft Launcher from " + legacy);
                    return "The Minecraft Launcher is opening. Pick 'Xsoz Client' next to the green PLAY button, then press PLAY.";
                }
            }

            if (!IsStoreLauncherInstalled())
            {
                return "No official Minecraft Launcher was found on this machine. Install it from the Microsoft Store first.";
            }

            // The only supported way to start a Store app from a non-packaged process.
            using var explorer = Process.Start(new ProcessStartInfo("explorer.exe", "shell:AppsFolder\\" + StoreAumid)
            {
                UseShellExecute = true,
            });

            AppLog.Shared.Info("Vanilla install: started the Microsoft Store Minecraft Launcher through shell:AppsFolder.");
            return "The Minecraft Launcher is opening. Pick 'Xsoz Client' next to the green PLAY button, then press PLAY.";
        }
        catch (Exception ex)
        {
            AppLog.Shared.Warn("The Minecraft Launcher could not be started: " + ex.Message);
            return "The Minecraft Launcher could not be started: " + ex.Message;
        }
    }

    /// <summary>The launcher variant, as the interface names it.</summary>
    public static string VariantLabel => DetectVariant() switch
    {
        "store" => "Microsoft Store",
        "legacy" => "legacy Unified",
        _ => "not detected",
    };
}