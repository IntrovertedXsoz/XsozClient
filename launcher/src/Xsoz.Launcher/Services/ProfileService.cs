using Xsoz.Launcher.Core;
using Xsoz.Launcher.Models;

namespace Xsoz.Launcher.Services;

/// <summary>
/// Owns the profile set and the module registry, and persists both.
///
/// Two invariants are enforced here rather than in the UI, because a UI is not a security boundary:
/// there is always at least one profile, and there is always exactly one default. Both survive a
/// reload of a damaged file.
/// </summary>
public sealed class ProfileService
{
    private const string ProfilesFile = "profiles.json";
    private const string SettingsFile = "settings.json";
    private const string AccountFile = "account.json";

    private readonly List<Profile> _profiles = [];
    private readonly Dictionary<string, ModuleDescriptor> _modules = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, TierInfo> _tiers = new(StringComparer.OrdinalIgnoreCase);
    private List<ModuleCategory> _categories = [];
    private List<GameVersionDescriptor> _versions = [];

    /// <summary>Creates the service and loads everything from disk.</summary>
    public ProfileService()
    {
        AppPaths.EnsureCreated();
        LoadRegistry();
        LoadSettings();
        LoadProfiles();
        LoadAccount();
        EnforceInvariants();
    }

    /// <summary>Launcher settings. Mutating a property does not save; call <see cref="SaveSettings"/>.</summary>
    public LauncherSettings Settings { get; private set; } = new();

    /// <summary>The local offline account.</summary>
    public LocalAccount Account { get; private set; } = new();

    /// <summary>All profiles, default first then alphabetically.</summary>
    public IReadOnlyList<Profile> Profiles => _profiles;

    /// <summary>Module registry, keyed by id.</summary>
    public IReadOnlyDictionary<string, ModuleDescriptor> Modules => _modules;

    /// <summary>Module categories, in display order.</summary>
    public IReadOnlyList<ModuleCategory> Categories => _categories;

    /// <summary>Compliance tiers, keyed by id.</summary>
    public IReadOnlyDictionary<string, TierInfo> Tiers => _tiers;

    /// <summary>Known game versions, primary first.</summary>
    public IReadOnlyList<GameVersionDescriptor> Versions => _versions;

    /// <summary>The active profile. Never null: the invariants guarantee at least one exists.</summary>
    public Profile Active => _profiles.FirstOrDefault(p => p.Id == Settings.ActiveProfileId) ?? _profiles[0];

    /// <summary>The selected game version. Falls back to the recommended one.</summary>
    public GameVersionDescriptor SelectedVersion =>
        _versions.FirstOrDefault(v => v.Id == Settings.SelectedVersionId)
        ?? _versions.FirstOrDefault(v => v.Recommended)
        ?? (_versions.Count > 0 ? _versions[0] : new GameVersionDescriptor());

    private void LoadRegistry()
    {
        var rules = JsonStore.LoadPackaged("rules.json", new RulesFile());
        _tiers.Clear();
        foreach (var tier in rules.Tiers)
        {
            _tiers[tier.Id] = tier;
        }

        var registry = JsonStore.LoadPackaged("modules.json", new ModuleRegistryFile());
        _modules.Clear();
        foreach (var module in registry.Modules)
        {
            if (string.IsNullOrWhiteSpace(module.Id))
            {
                continue;
            }

            // A tier C module is never toggleable, whatever the registry file says. Refusing to
            // enable one is a compliance property of the client, not a data value.
            if (module.ParsedTier == ComplianceTier.Unavailable)
            {
                module.Toggleable = false;
            }

            _modules[module.Id] = module;
        }

        _categories = rules.Categories.Count > 0 ? rules.Categories : registry.Categories;

        var versions = JsonStore.LoadPackaged("versions.json", new VersionCatalogFile());
        _versions = versions.Versions
            .OrderByDescending(v => v.Recommended)
            .ThenBy(v => v.Id, StringComparer.OrdinalIgnoreCase)
            .ToList();

        AppLog.Shared.Info($"Loaded {_modules.Count} modules, {_categories.Count} categories, {_versions.Count} versions.");
    }

    private void LoadSettings()
    {
        Settings = JsonStore.Load(Path.Combine(AppPaths.State, SettingsFile), new LauncherSettings());
        if (Settings.PhysicalMemoryMb <= 0)
        {
            Settings.PhysicalMemoryMb = MachineInfo.PhysicalMemoryMb();
        }

        if (_versions.Count > 0 && !_versions.Any(v => v.Id == Settings.SelectedVersionId))
        {
            var fallback = _versions.FirstOrDefault(v => v.Recommended) ?? _versions[0];
            Settings.SelectedVersionId = fallback.Id;
        }
    }

    private void LoadProfiles()
    {
        var loaded = JsonStore.Load(Path.Combine(AppPaths.State, ProfilesFile), new List<Profile>());
        _profiles.Clear();
        foreach (var profile in loaded)
        {
            if (!string.IsNullOrWhiteSpace(profile.Id) && !string.IsNullOrWhiteSpace(profile.Name))
            {
                _profiles.Add(profile);
            }
        }

        if (_profiles.Count == 0)
        {
            _profiles.Add(CreateDefaultProfile());
            AppLog.Shared.Info("No profile file found; created the shipped default profile.");
        }
    }

    private void LoadAccount()
    {
        Account = JsonStore.Load(Path.Combine(AppPaths.State, AccountFile), new LocalAccount
        {
            Name = string.IsNullOrWhiteSpace(Settings.OfflineName) ? "Player" : Settings.OfflineName,
        });

        if (string.IsNullOrWhiteSpace(Account.OfflineUuid))
        {
            Account.OfflineUuid = LaunchService.OfflineUuidFor(Account.Name);
        }
    }

    /// <summary>
    /// The shipped default profile. Every value here is sourced or explicitly estimated, and the
    /// estimate is labelled as one rather than dressed up as a measurement.
    /// </summary>
    private static Profile CreateDefaultProfile()
    {
        var profile = new Profile
        {
            Id = "chosen-one-default",
            Name = "Chosen One",
            IsDefault = true,
            VerticalFov = 90.0,
            MouseDpi = 2000,
            CentimetresPer360 = SensitivityRamp.StartCentimetresPer360,
            FovRelativeMode = FovRelativeMode.Physical,
            DamageTilt = 0.35,
            ViewBobbing = false,
            MaxFramerate = 0,
            VSync = false,
            RenderDistance = 8,
            Note = "Sourced FOV and DPI; the starting travel distance is the game's own default feel.",
        };

        return profile;
    }

    /// <summary>Restores every invariant: at least one profile, exactly one default, a valid active id.</summary>
    private void EnforceInvariants()
    {
        if (_profiles.Count == 0)
        {
            _profiles.Add(CreateDefaultProfile());
        }

        var defaults = _profiles.Where(p => p.IsDefault).ToList();
        if (defaults.Count == 0)
        {
            _profiles[0].IsDefault = true;
        }
        else if (defaults.Count > 1)
        {
            foreach (var extra in defaults.Skip(1))
            {
                extra.IsDefault = false;
            }
        }

        if (string.IsNullOrEmpty(Settings.ActiveProfileId) || _profiles.All(p => p.Id != Settings.ActiveProfileId))
        {
            Settings.ActiveProfileId = _profiles.First(p => p.IsDefault).Id;
        }
    }

    /// <summary>Persists the profile set.</summary>
    public bool SaveProfiles() => JsonStore.Save(Path.Combine(AppPaths.State, ProfilesFile), _profiles);

    /// <summary>Persists the launcher settings.</summary>
    public bool SaveSettings() => JsonStore.Save(Path.Combine(AppPaths.State, SettingsFile), Settings);

    /// <summary>Persists the local account.</summary>
    public bool SaveAccount()
    {
        Settings.OfflineName = Account.Name;
        return JsonStore.Save(Path.Combine(AppPaths.State, AccountFile), Account);
    }

    /// <summary>Switches the active profile and persists the choice.</summary>
    public void SetActive(string profileId)
    {
        if (_profiles.All(p => p.Id != profileId))
        {
            return;
        }

        Settings.ActiveProfileId = profileId;
        SaveSettings();
        AppLog.Shared.Info($"Active profile: {_profiles.First(p => p.Id == profileId).Name}");
    }

    /// <summary>Creates a profile seeded from the active one and makes it active.</summary>
    public Profile Create(string name, bool seedFromActive = true)
    {
        var source = Active;
        var profile = seedFromActive
            ? source.Clone(UniqueName(name))
            : new Profile { Name = UniqueName(name), VerticalFov = 90, MouseDpi = 800, CentimetresPer360 = 6.10 };

        _profiles.Add(profile);
        SaveProfiles();
        SetActive(profile.Id);
        AppLog.Shared.Success($"Created profile '{profile.Name}'.");
        return profile;
    }

    /// <summary>Duplicates a profile, including its module state, under a new name.</summary>
    public Profile Duplicate(string profileId)
    {
        var source = _profiles.FirstOrDefault(p => p.Id == profileId);
        if (source is null)
        {
            throw new InvalidOperationException("No such profile.");
        }

        var copy = source.Clone(UniqueName(source.Name + " Copy"));
        _profiles.Add(copy);
        SaveProfiles();
        SetActive(copy.Id);
        AppLog.Shared.Success($"Duplicated '{source.Name}' to '{copy.Name}'.");
        return copy;
    }

    /// <summary>
    /// Renames a profile. Names are trimmed, non-empty, at most 40 characters and unique.
    /// Returns an error string, or null on success.
    /// </summary>
    public string? Rename(string profileId, string newName)
    {
        var profile = _profiles.FirstOrDefault(p => p.Id == profileId);
        if (profile is null)
        {
            return "That profile no longer exists.";
        }

        var cleaned = NormalizeName(newName);
        if (cleaned is null)
        {
            return "A profile name has to be between 1 and 40 characters.";
        }

        if (_profiles.Any(p => p.Id != profileId && string.Equals(p.Name, cleaned, StringComparison.OrdinalIgnoreCase)))
        {
            return $"There is already a profile called '{cleaned}'.";
        }

        profile.Name = cleaned;
        profile.Touch();
        SaveProfiles();
        AppLog.Shared.Info($"Renamed profile to '{cleaned}'.");
        return null;
    }

    /// <summary>
    /// Deletes a profile.
    ///
    /// Two guards, both of which the UI reflects rather than discovering after the fact: the last
    /// profile cannot be deleted, and the default cannot be deleted without first promoting
    /// another profile to default.
    /// </summary>
    public string? Delete(string profileId, bool makeReplacementDefault)
    {
        if (_profiles.Count <= 1)
        {
            return "This is the last profile. There has to be at least one - create another before deleting this one.";
        }

        var profile = _profiles.FirstOrDefault(p => p.Id == profileId);
        if (profile is null)
        {
            return "That profile no longer exists.";
        }

        if (profile.IsDefault)
        {
            var replacement = _profiles.FirstOrDefault(p => p.Id != profileId);
            if (replacement is null)
            {
                return "There is no other profile to make default.";
            }

            if (!makeReplacementDefault)
            {
                return $"'{profile.Name}' is the default profile. Promote another profile to default first.";
            }

            replacement.IsDefault = true;
        }

        _profiles.Remove(profile);
        if (Settings.ActiveProfileId == profileId)
        {
            Settings.ActiveProfileId = _profiles.First(p => p.IsDefault).Id;
            SaveSettings();
        }

        SaveProfiles();
        AppLog.Shared.Warn($"Deleted profile '{profile.Name}'.");
        return null;
    }

    /// <summary>Makes a profile the default, demoting the previous one.</summary>
    public void SetDefault(string profileId)
    {
        foreach (var profile in _profiles)
        {
            profile.IsDefault = profile.Id == profileId;
        }

        SaveProfiles();
        AppLog.Shared.Info($"'{_profiles.First(p => p.Id == profileId).Name}' is now the default profile.");
    }

    /// <summary>Imports a profile from a JSON file the user chose. Returns an error string, or null.</summary>
    public string? Import(string path)
    {
        try
        {
            var imported = JsonStore.Load(path, (Profile?)null);
            if (imported is null)
            {
                return "That file is not a profile this client can read.";
            }

            var profile = new Profile
            {
                Id = Guid.NewGuid().ToString("N"),
                Name = UniqueName(imported.Name),
                VerticalFov = Math.Clamp(imported.VerticalFov, 30, 170),
                MouseDpi = Math.Clamp(imported.MouseDpi, 100, 26000),
                CentimetresPer360 = Math.Clamp(imported.CentimetresPer360, 0.05, 500),
                FovRelativeMode = imported.FovRelativeMode,
                DamageTilt = Math.Clamp(imported.DamageTilt, 0, 1),
                ViewBobbing = imported.ViewBobbing,
                MaxFramerate = Math.Max(0, imported.MaxFramerate),
                VSync = imported.VSync,
                RenderDistance = Math.Clamp(imported.RenderDistance, 2, 32),
                Modules = new Dictionary<string, bool>(imported.Modules, StringComparer.OrdinalIgnoreCase),
                Note = imported.Note,
            };

            _profiles.Add(profile);
            SaveProfiles();
            SetActive(profile.Id);
            AppLog.Shared.Success($"Imported profile '{profile.Name}'.");
            return null;
        }
        catch (Exception ex)
        {
            return $"Could not import that file: {ex.Message}";
        }
    }

    /// <summary>Writes a profile to the launcher's export folder. Returns the path, or null on failure.</summary>
    public string? Export(string profileId)
    {
        var profile = _profiles.FirstOrDefault(p => p.Id == profileId);
        if (profile is null)
        {
            return null;
        }

        try
        {
            Directory.CreateDirectory(AppPaths.Exports);
            var safeName = string.Concat(profile.Name.Select(c => char.IsLetterOrDigit(c) || c is ' ' or '-' or '_' ? c : '_')).Trim();
            var path = Path.Combine(AppPaths.Exports, $"{safeName.Trim()}.profile.json");
            return JsonStore.Save(path, profile) ? path : null;
        }
        catch (Exception ex)
        {
            AppLog.Shared.Error($"Export failed: {ex.Message}");
            return null;
        }
    }

    /// <summary>
    /// Sets a module's state in the active profile.
    ///
    /// A tier C module cannot be enabled, no matter what the caller asks for, and no matter what a
    /// hand-edited state file claims. Enabling one is logged as an integrity failure rather than
    /// quietly ignored, because a state file that enables an unavailable module is evidence of
    /// something other than this launcher having written it.
    /// </summary>
    public bool SetModuleState(string moduleId, bool enabled)
    {
        if (!_modules.TryGetValue(moduleId, out var module))
        {
            return false;
        }

        if (!module.Toggleable && enabled)
        {
            AppLog.Shared.Warn($"Refused to enable '{module.Name}': {module.UnavailableReason}");
            return false;
        }

        Active.Modules[moduleId] = enabled;
        Active.Touch();
        SaveProfiles();
        return true;
    }

    /// <summary>Reads a module's state in the active profile, falling back to the registry default.</summary>
    public bool GetModuleState(string moduleId)
    {
        if (Active.Modules.TryGetValue(moduleId, out var state))
        {
            return state;
        }

        return _modules.TryGetValue(moduleId, out var module) && module.DefaultEnabled;
    }

    /// <summary>
    /// Applies the registry defaults to a profile, and drops anything the registry no longer knows.
    ///
    /// The prune matters more than it looks. A state file is a record of what the user has switched
    /// on, and a record that names a module the product removed is a record that lies about its own
    /// contents - which is exactly the kind of drift that makes a state file useless as evidence.
    /// It is cheap to keep honest, so it is kept honest.
    /// </summary>
    public void ApplyRegistryDefaults()
    {
        foreach (var stale in Active.Modules.Keys.Where(id => !_modules.ContainsKey(id)).ToList())
        {
            Active.Modules.Remove(stale);
            AppLog.Shared.Info($"Removed '{stale}' from profile '{Active.Name}': it is no longer in the module registry.");
        }

        foreach (var module in _modules.Values)
        {
            if (!Active.Modules.ContainsKey(module.Id))
            {
                Active.Modules[module.Id] = module.Toggleable && module.DefaultEnabled;
            }
        }

        SaveProfiles();
    }

    private string UniqueName(string desired)
    {
        var baseName = NormalizeName(desired) ?? "Profile";
        if (_profiles.All(p => !string.Equals(p.Name, baseName, StringComparison.OrdinalIgnoreCase)))
        {
            return baseName;
        }

        for (int i = 2; i < 500; i++)
        {
            var candidate = $"{baseName} {i}";
            if (candidate.Length <= 40 && _profiles.All(p => !string.Equals(p.Name, candidate, StringComparison.OrdinalIgnoreCase)))
            {
                return candidate;
            }
        }

        return baseName + " " + Guid.NewGuid().ToString("N")[..4];
    }

    private static string? NormalizeName(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return null;
        }

        var trimmed = raw.Trim();
        return trimmed.Length is 0 or > 40 ? null : trimmed;
    }
}
