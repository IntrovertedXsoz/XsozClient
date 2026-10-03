using System.Globalization;
using System.Text.Json.Serialization;

namespace Xsoz.Launcher.Models;

/// <summary>
/// The sensitivity model. This is the reason a raw percentage is never the primary control.
///
/// Minecraft's Tier A conversion is a cubic: <c>deg_per_count = 1.2 * (0.6 * s + 0.2)^3</c>.
/// The only physically reproducible quantity in the system is centimetres of mouse travel
/// for a 360 degree turn, so that is what a profile stores and what the UI leads with.
/// </summary>
public static class SensitivityModel
{
    /// <summary>Inches of travel for a full 360 degree turn at the given degrees-per-count.</summary>
    public const double InchesPer360Factor = 360.0;

    /// <summary>360 inches expressed in centimetres - the numerator of the cm/360 formula.</summary>
    public const double CentimetresPer360Numerator = 914.4;

    /// <summary>Lowest vanilla slider value the game exposes.</summary>
    public const double VanillaMin = 0.0;

    /// <summary>Highest vanilla slider value the game exposes.</summary>
    public const double VanillaMax = 1.0;

    /// <summary>Convert a vanilla sensitivity value into degrees of rotation per mouse count.</summary>
    public static double DegreesPerCount(double sensitivity) =>
        1.2 * Math.Pow((0.6 * sensitivity) + 0.2, 3);

    /// <summary>
    /// Invert the cubic. This is the step a linear "FOV-relative sensitivity" rule gets wrong,
    /// and getting it wrong inflates the error because the response is cubic.
    /// </summary>
    public static double SensitivityFromDegreesPerCount(double degreesPerCount)
    {
        var cubeRoot = Math.Cbrt(degreesPerCount / 1.2);
        return (cubeRoot - 0.2) / 0.6;
    }

    /// <summary>Centimetres of mouse travel for a 360 degree turn at a sensitivity and pointer DPI.</summary>
    public static double CentimetresPer360(double sensitivity, int dpi) =>
        dpi <= 0 ? double.NaN : CentimetresPer360Numerator / (DegreesPerCount(sensitivity) * dpi);

    /// <summary>
    /// Solve the cubic for the vanilla sensitivity that produces a target cm/360 at a given DPI.
    /// Returns null when the target is outside the vanilla slider - the caller must surface that
    /// rather than clamp it, because clamping silently hands the user a different sensitivity
    /// from the one they asked for.
    /// </summary>
    public static double? SensitivityForCentimetresPer360(double cmPer360, int dpi)
    {
        if (cmPer360 <= 0 || dpi <= 0)
        {
            return null;
        }

        var degreesPerCount = CentimetresPer360Numerator / (cmPer360 * dpi);
        var sensitivity = SensitivityFromDegreesPerCount(degreesPerCount);
        return sensitivity is < VanillaMin or > VanillaMax ? null : sensitivity;
    }

    /// <summary>True when the sensitivity value fits inside the vanilla slider.</summary>
    public static bool IsInVanillaRange(double sensitivity) =>
        sensitivity >= VanillaMin && sensitivity <= VanillaMax;

    /// <summary>The slowest cm/360 reachable at a DPI, i.e. vanilla sensitivity 1.0.</summary>
    public static double FastestCentimetresPer360(int dpi) => CentimetresPer360(VanillaMax, dpi);

    /// <summary>The slowest (largest) cm/360 reachable at a DPI, i.e. vanilla sensitivity 0.0.</summary>
    public static double SlowestCentimetresPer360(int dpi) => CentimetresPer360(0.0, dpi);

    /// <summary>Formats a sensitivity value the way the game shows it - a whole percentage.</summary>
    public static string FormatPercent(double sensitivity) =>
        (sensitivity * 100).ToString("0", CultureInfo.InvariantCulture) + "%";

    /// <summary>
    /// Formats cm/360 with enough precision to be actionable on a mouse mat. A 0.744 target
    /// and a 0.75 target are different settings, and rounding them together would be a lie.
    /// </summary>
    public static string FormatCentimetresPer360(double cmPer360) =>
        cmPer360.ToString("0.000", CultureInfo.InvariantCulture);

    /// <summary>Millimetres of travel for a 90 degree turn - the number a player can actually feel.</summary>
    public static string FormatMillimetresPer90(double cmPer360) =>
        (cmPer360 / 4.0 * 10.0).ToString("0.00", CultureInfo.InvariantCulture) + " mm";
}

/// <summary>How a client should react when the player changes field of view.</summary>
public enum FovRelativeMode
{
    /// <summary>Mode 1. Change nothing. Degrees-per-count is FOV-independent, so this is the only mode that is true in vanilla. The default.</summary>
    Physical,

    /// <summary>Mode 2. Preserve on-screen image speed. A client-side convenience, not vanilla behaviour. Marked as such in the UI.</summary>
    ImageSpeed,
}

/// <summary>One rung of the staged sensitivity adaptation ramp.</summary>
/// <param name="Stage">Zero-based index. Stage 0 is the starting point; the last stage is the target feel.</param>
/// <param name="CentimetresPer360">The target travel distance for this stage.</param>
/// <param name="IsTarget">True for the final stage, which is deliberately partial rather than a clean geometric step.</param>
/// <param name="DwellNote">How long to stay on this stage before advancing.</param>
public sealed record RampStage(int Stage, double CentimetresPer360, bool IsTarget, string DwellNote);

/// <summary>
/// The staged adaptation ramp between the vanilla default's feel and the target feel.
///
/// A single jump across this gap is an 8.06x change in one step, which costs a player their aim
/// for a week or more. The ramp uses a 1.5x ratio per stage so each step lands inside the band
/// where adaptation is fast. The last stage is deliberately partial: the remaining gap from
/// 0.80 to 0.744 is far smaller than the 1.5x the earlier stages use, and overshooting it to
/// "complete" the ratio would make the target faster than it actually is.
/// </summary>
public static class SensitivityRamp
{
    /// <summary>The starting point: the game's own default feel at the ramp's anchor DPI.</summary>
    public const double StartCentimetresPer360 = 6.10;

    /// <summary>The target feel. FOV 90, 2000 DPI, 100% sensitivity, read from a published settings block.</summary>
    public const double TargetCentimetresPer360 = 0.744140625;

    /// <summary>The ramp, in order. The final step is partial on purpose.</summary>
    public static IReadOnlyList<RampStage> Stages { get; } =
    [
        new(0, 6.10, false, "3-5 days, until your clicks feel deliberate rather than fast"),
        new(1, 4.07, false, "3-5 days, until overshooting is your only mistake"),
        new(2, 2.71, false, "3-5 days, until you can hold a line without correcting"),
        new(3, 1.81, false, "3-5 days, until tracking a strafe needs no thought"),
        new(4, 1.20, false, "3-5 days, until head-crystal timing is the thing you think about"),
        new(5, 0.80, false, "3-5 days, until the only thing left is the target itself"),
        new(6, TargetCentimetresPer360, true, "Hold indefinitely. This is the published setting, not a recommendation to start here."),
    ];

    /// <summary>
    /// The geometric ratio the early stages use. Deliberately not applied to the final step -
    /// see the type remarks.
    /// </summary>
    public const double StageRatio = 1.5;

    /// <summary>How much faster the target is than the starting point.</summary>
    public static double TotalRatio => StartCentimetresPer360 / TargetCentimetresPer360;

    /// <summary>
    /// Returns the stage at or immediately above a cm/360 value, so a profile loaded from disk
    /// lands on a real rung rather than between two.
    /// </summary>
    public static RampStage StageFor(double cmPer360)
    {
        foreach (var stage in Stages)
        {
            if (cmPer360 >= stage.CentimetresPer360 - 0.0001)
            {
                return stage;
            }
        }

        return Stages[^1];
    }

    /// <summary>The next stage, or null when already at the target.</summary>
    public static RampStage? Next(double cmPer360)
    {
        var current = StageFor(cmPer360);
        return current.IsTarget ? null : Stages[current.Stage + 1];
    }

    /// <summary>The previous stage, or null when already at the start.</summary>
    public static RampStage? Previous(double cmPer360)
    {
        var current = StageFor(cmPer360);
        return current.Stage == 0 ? null : Stages[current.Stage - 1];
    }
}
