using System.Text.Json;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Models;

namespace Xsoz.Launcher.Services;

/// <summary>
/// The product rule that tier B ships default-off, and the check that enforces it.
///
/// WHY THIS EXISTS. <c>PROGRAM-DESCRIPTION.md</c> says a tier B module is legal but visible: it
/// has to be declared to staff, so the client must not turn it on for somebody. The registry had
/// Opaque Leaves - tier B, <c>defaultEnabled: true</c> - which is the product rule and the data
/// file disagreeing in the direction that hides a declaration from the player who has to make it.
/// The value is now false.
///
/// WHY A CHECK AND NOT A COMMENT. A comment in a JSON file is a request. This is the one rule in
/// the registry that a well-meaning hand edit can quietly break, and a launcher that turns on a
/// contested module by default is the kind of thing a player gets removed from a server for. So
/// the rule is enforced in two places, deliberately:
///
///   1. A BUILD-TIME check in Xsoz.Launcher.csproj, which fails the build with an error naming
///      the module. The build is where a registry edit is made, so that is where it is caught.
///   2. A STARTUP assertion here, which logs at ERROR and publishes through the shell's existing
///      unhandled-error channel. It cannot open a dialog: see App.OnDispatcherUnhandledException
///      for why a modal per fault is not an option here, and the rule is exactly as much of a
///      "loud" as this product allows.
///
/// The startup check reports rather than throws. Throwing would abort startup over one boolean in
/// a data file, and a launcher that will not open is a worse outcome than a launcher that opens
/// and says so in one line in the rail.
/// </summary>
public static class ModulePolicy
{
    /// <summary>The rule, stated once.</summary>
    public const string TierBDefaultRule =
        "Tier B modules ship default-off: they are legal but visible and have to be declared to "
        + "staff, so the client must not switch one on for the player.";

    /// <summary>
    /// The tier-B modules in a registry that claim to be on by default. Empty is the only
    /// acceptable result.
    /// </summary>
    public static IReadOnlyList<string> FindViolations(ModuleRegistryFile registry)
    {
        ArgumentNullException.ThrowIfNull(registry);

        return registry.Modules
            .Where(m => string.Equals(m.Tier, "B", StringComparison.OrdinalIgnoreCase) && m.DefaultEnabled)
            .Select(m => m.Id)
            .ToList();
    }

    /// <summary>
    /// Re-reads the packaged registry from disk and reports the violation. Deliberately does not
    /// use the already-loaded copy: the check is about the FILE, and a file that is fine but a
    /// cache that is stale is not the failure anybody would recognise.
    /// </summary>
    public static IReadOnlyList<string> CheckPackagedRegistry()
    {
        try
        {
            var registry = JsonStore.LoadPackaged("modules.json", new ModuleRegistryFile());
            return FindViolations(registry);
        }
        catch (Exception ex)
        {
            AppLog.Shared.Error("The tier B default check could not read the module registry: " + ex.Message);
            return [];
        }
    }

    /// <summary>
    /// Runs the check and reports it. Called once from the shell's constructor, before anything is
    /// shown, so the message is in the rail from the first frame rather than appearing later.
    /// </summary>
    public static void AssertAtStartup()
    {
        var violations = CheckPackagedRegistry();
        if (violations.Count == 0)
        {
            AppLog.Shared.Info("Module registry policy check passed: no tier B module ships default-on.");
            return;
        }

        var message =
            $"Module registry policy violation. {TierBDefaultRule} These are default-on: "
            + string.Join(", ", violations)
            + ". The build should have failed; this is the runtime backstop.";

        AppLog.Shared.Error(message);

        // Into the same one-line channel an unhandled interface error uses. Not a dialog, and not
        // a modal anything: a launcher that cannot be closed because a data file disagrees with a
        // policy is worse than a launcher that says so once and carries on.
        App.ReportUiLine(message);
    }
}
