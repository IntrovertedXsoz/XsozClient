namespace Xsoz.Launcher.Models;

/// <summary>Which garbage collector the JVM should be launched with.</summary>
public enum GarbageCollector
{
    /// <summary>G1. The recommended collector on every version for this machine. Default.</summary>
    G1,

    /// <summary>ZGC. Concurrent, low-pause, but costs throughput and memory headroom. Experiment only, and unavailable on Java 8.</summary>
    Zgc,

    /// <summary>Shenandoah. OpenJDK builds only, never in Java 8. Experiment only.</summary>
    Shenandoah,
}

/// <summary>Where the JVM executable should come from.</summary>
public enum JavaSource
{
    /// <summary>Use a per-version runtime under %LOCALAPPDATA%\XsozClient\runtimes, provisioning it if absent.</summary>
    Auto,

    /// <summary>Use a runtime the user points at explicitly. Probed with 'java -version' before use.</summary>
    Custom,
}

/// <summary>
/// A named snapshot of a play configuration: camera, input, module states, JVM settings.
/// Profiles are the launcher's unit of 'switching things over'. One hotkey cycles them.
/// </summary>
public sealed class Profile
{
    /// <summary>Stable identifier. Never shown; survives rename.</summary>
    public string Id { get; set; } = Guid.NewGuid().ToString("N");

    /// <summary>Display name. Unique within the profile set and validated on write.</summary>
    public string Name { get; set; } = "New Profile";

    /// <summary>
    /// True for the one profile that always exists and cannot be deleted. There is always
    /// exactly one default; deleting it requires reassigning first.
    /// </summary>
    public bool IsDefault { get; set; }

    /// <summary>When the profile was created, local time.</summary>
    public DateTimeOffset CreatedUtc { get; set; } = DateTimeOffset.UtcNow;

    /// <summary>When the profile was last edited, local time.</summary>
    public DateTimeOffset ModifiedUtc { get; set; } = DateTimeOffset.UtcNow;

    /// <summary>
    /// Vertical field of view in degrees. Vanilla's own option; the client stores it so a profile
    /// can restore a look without touching options.txt.
    /// </summary>
    public double VerticalFov { get; set; } = 90.0;

    /// <summary>Mouse pointer DPI, as configured in the mouse's own driver.</summary>
    public int MouseDpi { get; set; } = 2000;

    /// <summary>
    /// The profile's input unit: centimetres of mouse travel for a 360 degree turn.
    /// This is the stored quantity, not a sensitivity percentage. The percentage is derived
    /// from this and the DPI, and the derivation is lossy at the ends of the vanilla range -
    /// which is exactly why the percentage is a readout and not the control.
    /// </summary>
    public double CentimetresPer360 { get; set; } = SensitivityRamp.StartCentimetresPer360;

    /// <summary>
    /// How the client should respond to a FOV change. <see cref="FovRelativeMode.Physical"/>
    /// is the default and the only mode that is true in vanilla.
    /// </summary>
    public FovRelativeMode FovRelativeMode { get; set; } = FovRelativeMode.Physical;

    /// <summary>Damage tilt as a fraction, 0-1. A vanilla accessibility option.</summary>
    public double DamageTilt { get; set; } = 0.35;

    /// <summary>Whether the world moves under the crosshair while walking. Off by default in the shipped profile.</summary>
    public bool ViewBobbing { get; set; }

    /// <summary>Maximum frame rate. 0 means uncapped, which is the competitive default.</summary>
    public int MaxFramerate { get; set; }

    /// <summary>Vertical sync. Off by default: it caps your frame rate at the refresh rate and adds input latency.</summary>
    public bool VSync { get; set; }

    /// <summary>Render distance in chunks.</summary>
    public int RenderDistance { get; set; } = 8;

    /// <summary>Per-module enable state, keyed by module id.</summary>
    public Dictionary<string, bool> Modules { get; set; } = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Free-text note shown under the profile name in the list.</summary>
    public string Note { get; set; } = string.Empty;

    /// <summary>Deep copy, used by the duplicate action.</summary>
    public Profile Clone(string newName, bool isDefault = false)
    {
        return new Profile
        {
            Id = Guid.NewGuid().ToString("N"),
            Name = newName,
            IsDefault = isDefault,
            CreatedUtc = DateTimeOffset.UtcNow,
            ModifiedUtc = DateTimeOffset.UtcNow,
            VerticalFov = VerticalFov,
            MouseDpi = MouseDpi,
            CentimetresPer360 = CentimetresPer360,
            FovRelativeMode = FovRelativeMode,
            DamageTilt = DamageTilt,
            ViewBobbing = ViewBobbing,
            MaxFramerate = MaxFramerate,
            VSync = VSync,
            RenderDistance = RenderDistance,
            Modules = new Dictionary<string, bool>(Modules, StringComparer.OrdinalIgnoreCase),
            Note = Note,
        };
    }

    /// <summary>Stamps the modified time. Call after any mutation.</summary>
    public void Touch() => ModifiedUtc = DateTimeOffset.UtcNow;
}
