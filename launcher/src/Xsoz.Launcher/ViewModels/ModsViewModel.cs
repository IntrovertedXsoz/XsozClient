using System.Windows.Input;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Models;
using Xsoz.Launcher.Services;

namespace Xsoz.Launcher.ViewModels;

/// <summary>
/// One flat row on the Mods screen: toggle, name, one line, caret, and an optional body.
///
/// The body is part of the row, not a dialog. The design says so twice - "there is no animation
/// and no dialog" - and it is also the only version of this that can be read at 1280 without a
/// window that steals the keyboard mid-game-setup.
/// </summary>
public sealed class ModRow : ObservableObject
{
    private readonly ModsViewModel _owner;
    private readonly ModuleDescriptor _descriptor;
    private bool _isExpanded;

    /// <summary>Creates the row.</summary>
    public ModRow(ModsViewModel owner, ModuleDescriptor descriptor)
    {
        _owner = owner;
        _descriptor = descriptor;
    }

    /// <summary>The registry entry.</summary>
    public ModuleDescriptor Descriptor => _descriptor;

    /// <summary>Module name.</summary>
    public string Name => _descriptor.Name;

    /// <summary>The one-line description shown in the collapsed row.</summary>
    public string Summary => _descriptor.Summary;

    /// <summary>The long description shown in the expanded row.</summary>
    public string Detail => _descriptor.Detail;

    /// <summary>Whether the module can run on this game version.</summary>
    public bool SupportsVersion => _owner.SelectedVersion is null
        || _descriptor.SupportsVersion(_owner.SelectedVersion);

    /// <summary>False for a row the registry marks as not toggleable. True for everything that ships.</summary>
    public bool CanToggle => _descriptor.Toggleable && SupportsVersion;

    /// <summary>Whether the module is on in the active profile.</summary>
    public bool IsEnabled
    {
        get => _owner.GetState(_descriptor.Id);
        set
        {
            if (_owner.SetState(_descriptor.Id, value))
            {
                Raise();
                _owner.RaiseSummary();
            }
        }
    }

    /// <summary>
    /// Whether the body is open. Two-way, because the caret is a button and Enter or Space on
    /// the row header has to reach the same place.
    /// </summary>
    public bool IsExpanded
    {
        get => _isExpanded;
        set
        {
            if (Set(ref _isExpanded, value))
            {
                Raise(nameof(BodyVisibility));
            }
        }
    }

    /// <summary>Visible only when the row is open. A visibility rather than a template swap, so
    /// the row keeps its height in the item container and the list does not reflow around it.</summary>
    public System.Windows.Visibility BodyVisibility => _isExpanded
        ? System.Windows.Visibility.Visible
        : System.Windows.Visibility.Collapsed;

    /// <summary>
    /// The one quiet sentence four of the modules carry, and only these four.
    ///
    /// It appears after the row is opened and nowhere else in the product. It exists because the
    /// project's own rules say these four modules are contested, and a player who flips one on
    /// and gets removed from a server would otherwise blame the client for it. The design is
    /// explicit that there is no compliance panel, no tier badge and no legal copy anywhere else;
    /// this is the entire extent of it, and deleting the four lines in the mockups removes it
    /// without changing anything else.
    /// </summary>
    public string Note => ModsViewModel.NoteFor(_descriptor.Id);
}

/// <summary>
/// The Mods screen. A search box, a plain count, and flat rows that open in place.
///
/// No categories, no tabs, no tier badges, and no "N of M declared" tally. The count is a count.
/// </summary>
public sealed class ModsViewModel : ObservableObject
{
    /// <summary>
    /// The four muted notes, keyed by module id. Frozen here rather than in modules.json because
    /// they are copy about server policy, not a property of the module, and because putting them
    /// in the registry is how they end up being read as configuration.
    /// </summary>
    private static readonly Dictionary<string, string> Notes = new(StringComparer.OrdinalIgnoreCase)
    {
        ["perf.chunk_replay"] = "Off by default. Some servers don't allow this.",
        ["coach.drill_prompts"] = "Off by default. Some servers don't allow this.",
        ["visual.transparency_passthrough"] = "Off by default. Some servers don't allow this.",
        ["visual.opaque_leaves"] = "Some servers don't allow this one. Check yours.",
    };

    private readonly ProfileService _store;
    private readonly ModrinthLinkService _vanillaTweaks = new();
    private string _filter = string.Empty;

    /// <summary>Creates the screen.</summary>
    public ModsViewModel(ProfileService store)
    {
        _store = store;
        SelectedVersion = store.Versions.Count > 0 ? store.SelectedVersion.Id : null;

        OpenVanillaTweaksCommand = RelayCommand.Create(_vanillaTweaks.OpenGenerator);
        CopyVanillaTweaksCommand = RelayCommand.Create(_vanillaTweaks.CopyOptionNote);
        ToggleRowCommand = RelayCommand.CreateWithParameter(p =>
        {
            if (p is ModRow row)
            {
                row.IsExpanded = !row.IsExpanded;
            }
        });

        Rebuild();
    }

    /// <summary>Opens or closes one row. Bound to the row header, which is a real button so that
    /// Enter and Space reach it without any key handling in the view.</summary>
    public RelayCommand ToggleRowCommand { get; }

    /// <summary>The muted note for a module, or empty when it carries none.</summary>
    public static string NoteFor(string moduleId) => Notes.TryGetValue(moduleId, out var note) ? note : string.Empty;

    /// <summary>The flat mod rows.</summary>
    public ResetableCollection<ModRow> Rows { get; } = [];

    /// <summary>Opens the Vanilla Tweaks generator in the default browser. Nothing is downloaded.</summary>
    public RelayCommand OpenVanillaTweaksCommand { get; }

    /// <summary>Copies the option-set note to the clipboard for use in the generator.</summary>
    public RelayCommand CopyVanillaTweaksCommand { get; }

    /// <summary>Free-text filter over name and summary.</summary>
    public string Filter
    {
        get => _filter;
        set
        {
            if (Set(ref _filter, value ?? string.Empty))
            {
                Rebuild();
            }
        }
    }

    /// <summary>
    /// The header count line: "13 of 20 modules enabled", with the number in the bright colour
    /// and the rest muted. A plain count, and that is the whole sentence.
    /// </summary>
    public string Summary
    {
        get
        {
            var all = _store.Modules.Values;
            var toggleable = all.Count(m => m.Toggleable && (SelectedVersion is null || m.SupportsVersion(SelectedVersion)));
            var on = all.Count(m => m.Toggleable && _store.GetModuleState(m.Id));
            return $"{on} of {toggleable} modules enabled";
        }
    }

    /// <summary>How many of the listed modules are on. Rendered in the bright colour.</summary>
    public string EnabledCount
    {
        get
        {
            var all = _store.Modules.Values;
            return all.Count(m => m.Toggleable && _store.GetModuleState(m.Id)).ToString(System.Globalization.CultureInfo.InvariantCulture);
        }
    }

    /// <summary>The rest of the count line. Muted.</summary>
    public string CountRemainder
    {
        get
        {
            var all = _store.Modules.Values;
            var toggleable = all.Count(m => m.Toggleable && (SelectedVersion is null || m.SupportsVersion(SelectedVersion)));
            var on = all.Count(m => m.Toggleable && _store.GetModuleState(m.Id));
            return $" of {toggleable} modules enabled";
        }
    }

    /// <summary>True when the search matched nothing, so the empty state should show.</summary>
    public bool IsEmpty => Rows.Count == 0;

    /// <summary>The search text, echoed in the empty state so the reason is obvious.</summary>
    public string EmptyQuery => _filter.Trim();

    /// <summary>The selected game version, used to hide a row the version does not support.</summary>
    public string? SelectedVersion { get; }

    /// <summary>Reads a module's state.</summary>
    public bool GetState(string moduleId) => _store.GetModuleState(moduleId);

    /// <summary>Writes a module's state, honouring the registry's own refusal.</summary>
    public bool SetState(string moduleId, bool enabled) => _store.SetModuleState(moduleId, enabled);

    /// <summary>Recomputes the header count after a toggle.</summary>
    public void RaiseSummary()
    {
        Raise(nameof(Summary));
        Raise(nameof(EnabledCount));
        Raise(nameof(CountRemainder));
    }

    /// <summary>
    /// Refills the list for the current filter, keeping any row that is still listed open.
    ///
    /// One Reset, not a Clear followed by twenty Adds. The filter box drives this on every
    /// keystroke, and the incremental form made the list announce twenty separate changes to
    /// whatever was watching it - which is both the slow version and the one where the list is
    /// briefly empty between the Clear and the first Add. ReplaceAll leaves no such gap to observe.
    ///
    /// The open rows are carried across by module id, because a filter that refills the list and
    /// closes everything under the user's hands is a filter that loses their place.
    /// </summary>
    private void Rebuild()
    {
        var needle = _filter.Trim();
        var open = Rows.Where(r => r.IsExpanded).Select(r => r.Descriptor.Id).ToHashSet(StringComparer.OrdinalIgnoreCase);

        IEnumerable<ModRow> matches = _store.Modules.Values
            .OrderBy(m => m.Name, StringComparer.OrdinalIgnoreCase)
            .Where(module => needle.Length == 0
                             || module.Name.Contains(needle, StringComparison.OrdinalIgnoreCase)
                             || module.Summary.Contains(needle, StringComparison.OrdinalIgnoreCase))
            .Select(module =>
            {
                var row = new ModRow(this, module);
                row.IsExpanded = open.Contains(module.Id);
                return row;
            });

        Rows.ReplaceAll(matches);
        Raise(nameof(Rows));
        Raise(nameof(IsEmpty));
        Raise(nameof(EmptyQuery));
    }
}
