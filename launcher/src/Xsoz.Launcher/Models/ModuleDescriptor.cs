namespace Xsoz.Launcher.Models;

/// <summary>How closely a module or a game version sits inside the target server's rules.</summary>
public enum ComplianceTier
{
    /// <summary>Named on the allowlist, or a vanilla setting the launcher merely surfaces.</summary>
    Allowlisted,

    /// <summary>Legal but visible: a change to what you can see, which has to be declared to staff.</summary>
    DeclareFirst,

    /// <summary>Fails the rules test. Never toggleable in any build.</summary>
    Unavailable,
}

/// <summary>One module in the registry. The launcher UI is a shell over this list.</summary>
public sealed class ModuleDescriptor
{
    /// <summary>Stable id, also the key used in a profile's module state dictionary.</summary>
    public string Id { get; set; } = string.Empty;

    /// <summary>Display name.</summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>Owning category id.</summary>
    public string Category { get; set; } = string.Empty;

    /// <summary>Compliance tier id from the rules metadata file.</summary>
    public string Tier { get; set; } = "A";

    /// <summary>Whether the module is on in a freshly created profile.</summary>
    public bool DefaultEnabled { get; set; }

    /// <summary>
    /// Whether the toggle can be operated at all. False for tier C: an unavailable module is a
    /// refusal, not a default-off, and the control renders as disabled with its reason attached.
    /// </summary>
    public bool Toggleable { get; set; } = true;

    /// <summary>Why a non-toggleable module is unavailable. Shown next to the disabled control.</summary>
    public string? UnavailableReason { get; set; }

    /// <summary>One line, shown in the collapsed row.</summary>
    public string Summary { get; set; } = string.Empty;

    /// <summary>Longer explanation, shown in the expanded row.</summary>
    public string Detail { get; set; } = string.Empty;

    /// <summary>Game version ids this module exists for. Empty means all.</summary>
    public List<string> RequiresVersions { get; set; } = [];

    /// <summary>Parsed tier. Falls back to Allowlisted for an unknown id, which fails safe toward availability.</summary>
    public ComplianceTier ParsedTier => Tier switch
    {
        "B" => ComplianceTier.DeclareFirst,
        "C" => ComplianceTier.Unavailable,
        _ => ComplianceTier.Allowlisted,
    };

    /// <summary>True when the module can run on a given game version.</summary>
    public bool SupportsVersion(string versionId) =>
        RequiresVersions.Count == 0 || RequiresVersions.Contains(versionId, StringComparer.OrdinalIgnoreCase);
}

/// <summary>A module category and its display metadata.</summary>
public sealed class ModuleCategory
{
    /// <summary>Stable id.</summary>
    public string Id { get; set; } = string.Empty;

    /// <summary>Display name.</summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>One-line description of what belongs in this category.</summary>
    public string Blurb { get; set; } = string.Empty;
}

/// <summary>One compliance tier and how to present it.</summary>
public sealed class TierInfo
{
    /// <summary>Tier id, matching <see cref="ModuleDescriptor.Tier"/>.</summary>
    public string Id { get; set; } = "A";

    /// <summary>Long name.</summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>Single glyph drawn in the badge.</summary>
    public string Glyph { get; set; } = "?";

    /// <summary>Palette key for the badge's fill.</summary>
    public string ColourKey { get; set; } = "tierA";

    /// <summary>What this tier actually means, in plain English.</summary>
    public string Meaning { get; set; } = string.Empty;
}

/// <summary>Root of modules.json.</summary>
public sealed class ModuleRegistryFile
{
    /// <summary>Schema version.</summary>
    public int Schema { get; set; } = 1;

    /// <summary>Provenance note. Not user-facing.</summary>
    public string Note { get; set; } = string.Empty;

    /// <summary>Category list, in display order.</summary>
    public List<ModuleCategory> Categories { get; set; } = [];

    /// <summary>Module list.</summary>
    public List<ModuleDescriptor> Modules { get; set; } = [];
}

/// <summary>Root of rules.json.</summary>
public sealed class RulesFile
{
    /// <summary>Schema version.</summary>
    public int Schema { get; set; } = 1;

    /// <summary>Provenance note. Not user-facing.</summary>
    public string Note { get; set; } = string.Empty;

    /// <summary>Tier definitions, A through C.</summary>
    public List<TierInfo> Tiers { get; set; } = [];

    /// <summary>Category list, mirrored from the registry so the rules file stands alone.</summary>
    public List<ModuleCategory> Categories { get; set; } = [];
}

/// <summary>A game version the launcher knows about, with the mod layer it expects.</summary>
public sealed class GameVersionDescriptor
{
    /// <summary>Version id, e.g. 1.21.11. Used as the directory name for instances.</summary>
    public string Id { get; set; } = string.Empty;

    /// <summary>Label for the version selector.</summary>
    public string Label { get; set; } = string.Empty;

    /// <summary>Either 'primary' or 'secondary'.</summary>
    public string Role { get; set; } = "secondary";

    /// <summary>'Full', 'Partial' or 'Unsupported'.</summary>
    public string Support { get; set; } = "Partial";

    /// <summary>Why the support grade is what it is.</summary>
    public string SupportNote { get; set; } = string.Empty;

    /// <summary>Whether this is the recommended version.</summary>
    public bool Recommended { get; set; }

    /// <summary>Java feature release the version needs.</summary>
    public int JavaMajor { get; set; } = 21;

    /// <summary>Loader id, for launcher-internal verification. Never shown as a user choice.</summary>
    public string LoaderId { get; set; } = "fabric";

    /// <summary>Human loader name.</summary>
    public string LoaderName { get; set; } = "Fabric";

    /// <summary>Pinned loader version.</summary>
    public string LoaderVersion { get; set; } = "0.19.5";

    /// <summary>Pinned Fabric API version for this game version.</summary>
    public string ApiVersion { get; set; } = string.Empty;

    /// <summary>How many jars the mod layer holds for this version.</summary>
    public int ModLayerCount { get; set; }

    /// <summary>Mojang asset index id.</summary>
    public string AssetIndex { get; set; } = string.Empty;

    /// <summary>Short support grade used on cards and chips.</summary>
    public string AccentNote { get; set; } = string.Empty;
}

/// <summary>Root of versions.json.</summary>
public sealed class VersionCatalogFile
{
    /// <summary>Schema version.</summary>
    public int Schema { get; set; } = 1;

    /// <summary>Provenance note. Not user-facing.</summary>
    public string Note { get; set; } = string.Empty;

    /// <summary>Known versions, primary first.</summary>
    public List<GameVersionDescriptor> Versions { get; set; } = [];
}
