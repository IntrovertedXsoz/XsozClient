using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows.Input;
using Xsoz.Launcher.Core;
using Xsoz.Launcher.Models;
using Xsoz.Launcher.Services;

namespace Xsoz.Launcher.ViewModels;

/// <summary>Where a rung is in the ramp, which is all its colour has to say.</summary>
public enum RampRungState
{
    /// <summary>Already earned. Dark green.</summary>
    Passed,

    /// <summary>The stage the profile is on. Bright green, with the NOW flag above it.</summary>
    Current,

    /// <summary>Not yet reached. An empty outline.</summary>
    Upcoming,
}

/// <summary>
/// One rung of the drawn ladder.
///
/// The seven rungs are built once, from the frozen table in <see cref="SensitivityRamp"/>, and
/// they are NOT clickable. That is the whole safety property of this control: the only way to
/// change the sensitivity is one stage at a time, through the two buttons or the arrow keys,
/// so the 8x mistake the ramp exists to prevent has no single gesture that performs it.
/// </summary>
public sealed class RampRung : ObservableObject
{
    /// <summary>Creates a rung.</summary>
    public RampRung(int index, RampStage stage, double height, bool isPartial)
    {
        Index = index;
        Stage = stage;
        Height = height;
        IsPartial = isPartial;
    }

    /// <summary>Zero-based position in the ladder.</summary>
    public int Index { get; }

    /// <summary>The frozen stage this rung stands for.</summary>
    public RampStage Stage { get; }

    /// <summary>
    /// The rung's drawn height. Computed from the speed on a log scale - see
    /// <see cref="RampGeometry.HeightFor"/>.
    /// </summary>
    public double Height { get; }

    /// <summary>True for the last rung, whose top is hatched to mark it a partial step.</summary>
    public bool IsPartial { get; }

    /// <summary>The value as the labels print it: three decimals at the ends, two in between.</summary>
    public string Label => FormatValue(Stage.CentimetresPer360, Index);

    /// <summary>How this rung should be painted for the current stage.</summary>
    public RampRungState State
    {
        get => _state;
        internal set
        {
            if (_state == value)
            {
                return;
            }

            _state = value;
            Raise();
            Raise(nameof(IsCurrent));
            Raise(nameof(IsPassed));
        }
    }

    private RampRungState _state = RampRungState.Upcoming;

    /// <summary>True when this rung is the current stage.</summary>
    public bool IsCurrent => State == RampRungState.Current;

    /// <summary>True when this rung has been passed.</summary>
    public bool IsPassed => State == RampRungState.Passed;

    /// <summary>
    /// The design's own number format: the two end values carry three decimals because they
    /// are the ones a player is told to aim at, and the five full steps carry two.
    /// </summary>
    public static string FormatValue(double value, int index) =>
        (index == 0 || index == SensitivityRamp.Stages.Count - 1)
            ? value.ToString("0.000", CultureInfo.InvariantCulture)
            : value.ToString("0.00", CultureInfo.InvariantCulture);
}

/// <summary>
/// The drawn geometry of the ladder.
///
/// BLOCK HEIGHT IS SPEED ON A LOG SCALE. The speed of a setting is 360 / cm-per-360, so
/// height(i) = lo + (hi - lo) * ln(S0 / Si) / ln(S0 / S6), with lo = 30 and hi = 108 taken
/// from the mockup's own 132px ladder box less its 24px label gutter.
///
/// That formula is not a reinterpretation of the design: it is what produces the mockup's
/// numbers exactly. Each full step divides the distance by 1.5, so ln(1.5) is the same for
/// every one of them and the five full rungs are 15px apart - 30, 45, 60, 75, 90, 105. The
/// last step is 0.80 to 0.744, a factor of 1.075, and ln(1.075)/ln(1.5) is a fifth of a full
/// step, so the final rung is 3px taller than the one before it: 108. A partial step looks
/// like a partial step because it is one.
/// </summary>
public static class RampGeometry
{
    /// <summary>Height of the first (slowest) rung, in device-independent units.</summary>
    public const double MinHeight = 30;

    /// <summary>Height of the last (target) rung, in device-independent units.</summary>
    public const double MaxHeight = 108;

    /// <summary>The drawn height of the rung at <paramref name="index"/>.</summary>
    public static double HeightFor(int index)
    {
        var stages = SensitivityRamp.Stages;
        var first = stages[0].CentimetresPer360;
        var last = stages[^1].CentimetresPer360;
        var value = stages[Math.Clamp(index, 0, stages.Count - 1)].CentimetresPer360;

        var span = Math.Log(first / last);
        if (span <= 0)
        {
            return MinHeight;
        }

        return Math.Round(MinHeight + ((MaxHeight - MinHeight) * Math.Log(first / value) / span), MidpointRounding.AwayFromZero);
    }
}

/// <summary>
/// The sensitivity ramp, as it behaves: a ladder of seven, two buttons, an arrow-key pair, and
/// one line of guidance.
///
/// It is a ladder rather than a slider because the value is not continuous. It is seven fixed
/// stages you earn one at a time, and the only thing that makes that safe is that there is no
/// gesture which can jump. So the rungs are not buttons, the value is not editable here, and
/// both ends of the ladder disable the button that has nowhere left to go.
/// </summary>
public sealed class RampViewModel : ObservableObject
{
    private readonly ProfileService _store;
    private int _stageIndex;
    private bool _preview;

    /// <summary>Creates the control's view model for the active profile.</summary>
    public RampViewModel(ProfileService store)
    {
        _store = store;
        SlowerCommand = RelayCommand.Create(Slower, () => StageIndex > 0);
        FasterCommand = RelayCommand.Create(Faster, () => StageIndex < SensitivityRamp.Stages.Count - 1);

        Rungs = new ObservableCollection<RampRung>(
            SensitivityRamp.Stages.Select((stage, index) => new RampRung(index, stage, RampGeometry.HeightFor(index), stage.IsTarget)));

        Refresh();
    }

    /// <summary>The seven rungs, in order. Never empty.</summary>
    public ObservableCollection<RampRung> Rungs { get; }

    /// <summary>One stage slower.</summary>
    public RelayCommand SlowerCommand { get; }

    /// <summary>One stage faster.</summary>
    public RelayCommand FasterCommand { get; }

    /// <summary>
    /// The zero-based current stage. Setting it moves exactly one stage and writes the profile.
    ///
    /// The guard here is the whole safety property of this control: the setter clamps to the
    /// seven rungs and nothing else in the interface can reach a value between them, so the 8x
    /// jump the ramp exists to prevent has no gesture that performs it. The only escape hatch is
    /// <see cref="ShowStagePreview"/>, which is diagnostics-only and does not write the profile.
    /// </summary>
    public int StageIndex
    {
        get => _stageIndex;
        private set
        {
            var clamped = Math.Clamp(value, 0, SensitivityRamp.Stages.Count - 1);
            if (_stageIndex == clamped)
            {
                return;
            }

            _stageIndex = clamped;

            if (!_preview)
            {
                Commit();
            }

            RaiseAll();
        }
    }

    /// <summary>
    /// Moves the ladder without writing the profile. Diagnostics seam, used by the headless
    /// capture so a still can show a mid-ramp stage on a machine whose real profile is at the
    /// start. It is the ONLY way to move the ladder without persisting, and it exists for that
    /// one reason.
    /// </summary>
    public void ShowStagePreview(int stage)
    {
        _preview = true;
        try
        {
            StageIndex = stage;
        }
        finally
        {
            _preview = false;
        }
    }

    private void Commit()
    {
        var profile = _store.Active;
        profile.CentimetresPer360 = SensitivityRamp.Stages[_stageIndex].CentimetresPer360;
        profile.Touch();
        _store.SaveProfiles();
        AppLog.Shared.Info(
            $"Profile '{profile.Name}' moved to ramp stage {_stageIndex + 1} of {SensitivityRamp.Stages.Count} "
            + $"({CurrentValue} cm/360).");
    }

    /// <summary>"Stage 3 of 7".</summary>
    public string StageText => $"Stage {StageIndex + 1} of {SensitivityRamp.Stages.Count}";

    /// <summary>The current value in centimetres per 360, at the format the labels use.</summary>
    public string CurrentValue => RampRung.FormatValue(CurrentStage.CentimetresPer360, StageIndex);

    /// <summary>The current value to three decimals, for the chip elsewhere in the interface.</summary>
    public string CurrentValuePrecise =>
        CurrentStage.CentimetresPer360.ToString("0.000", CultureInfo.InvariantCulture);

    /// <summary>The stage the ladder is on.</summary>
    public RampStage CurrentStage => SensitivityRamp.Stages[StageIndex];

    /// <summary>True at the first rung, where SLOWER has nowhere to go.</summary>
    public bool CanSlower => StageIndex > 0;

    /// <summary>True at the last rung, where FASTER has nowhere to go.</summary>
    public bool CanFaster => StageIndex < SensitivityRamp.Stages.Count - 1;

    /// <summary>
    /// One line under the buttons. At the end it says the ramp is finished; otherwise it names
    /// the next value, the ratio it is a step of, and the instruction to stay put.
    /// </summary>
    public string Hint
    {
        get
        {
            if (!CanFaster)
            {
                return "Target reached. This is your profile's sensitivity.";
            }

            var next = SensitivityRamp.Stages[StageIndex + 1];
            var ratio = (CurrentStage.CentimetresPer360 / next.CentimetresPer360)
                .ToString("0.00", CultureInfo.InvariantCulture)
                .TrimEnd('0')
                .TrimEnd('.');

            return next.IsTarget
                ? $"Next: {RampRung.FormatValue(next.CentimetresPer360, StageIndex + 1)} · a partial step to your target."
                : $"Next: {RampRung.FormatValue(next.CentimetresPer360, StageIndex + 1)} · {ratio}× faster. Stay here until it feels normal.";
        }
    }

    /// <summary>How long the design says to stay on this stage before advancing.</summary>
    public string DwellNote => CurrentStage.DwellNote;

    /// <summary>One stage slower. Bound to the button and to the left arrow key.</summary>
    public void Slower()
    {
        if (StageIndex > 0)
        {
            StageIndex--;
        }
    }

    /// <summary>One stage faster. Bound to the button and to the right arrow key.</summary>
    public void Faster()
    {
        if (StageIndex < SensitivityRamp.Stages.Count - 1)
        {
            StageIndex++;
        }
    }

    /// <summary>
    /// Re-reads the profile and repaints the ladder. Called on open and after any change, and
    /// safe to call while the drawer is closed.
    /// </summary>
    public void Refresh()
    {
        _stageIndex = SensitivityRamp.StageFor(_store.Active.CentimetresPer360).Stage;
        RaiseAll();
    }

    private void RaiseAll()
    {
        foreach (var rung in Rungs)
        {
            rung.State = rung.Index < StageIndex
                ? RampRungState.Passed
                : rung.Index == StageIndex ? RampRungState.Current : RampRungState.Upcoming;
        }

        Raise(nameof(Rungs));
        Raise(nameof(StageIndex));
        Raise(nameof(StageText));
        Raise(nameof(CurrentValue));
        Raise(nameof(CurrentValuePrecise));
        Raise(nameof(CurrentStage));
        Raise(nameof(CanSlower));
        Raise(nameof(CanFaster));
        Raise(nameof(Hint));
        Raise(nameof(DwellNote));
        SlowerCommand.RaiseCanExecuteChanged();
        FasterCommand.RaiseCanExecuteChanged();
    }
}

/// <summary>One key-value chip in the drawer's header.</summary>
/// <param name="Key">The value, or the label when the pair reads label-first.</param>
/// <param name="Value">The quiet half, in the muted colour.</param>
public sealed record ProfileChip(string Key, string Value);

/// <summary>One profile in the drawer's list.</summary>
public sealed class ProfileListRow
{
    /// <summary>Wraps a profile.</summary>
    public ProfileListRow(Profile profile, bool isActive)
    {
        Profile = profile;
        IsActive = isActive;
    }

    /// <summary>The profile.</summary>
    public Profile Profile { get; }

    /// <summary>True when the game would launch with this profile.</summary>
    public bool IsActive { get; }

    /// <summary>Display name.</summary>
    public string Name => Profile.Name;

    /// <summary>"Default · FOV 90 · 2000 DPI", or the same without the first part.</summary>
    public string Detail => (Profile.IsDefault ? "Default" : string.Empty) is { Length: > 0 } lead
        ? $"{lead} · FOV {Profile.VerticalFov:0} · {Profile.MouseDpi} DPI"
        : $"FOV {Profile.VerticalFov:0} · {Profile.MouseDpi} DPI";
}

/// <summary>
/// The profile drawer: a right-side overlay, not a navigation destination.
///
/// It holds the profile list, the key-value chips and the ramp. The ramp used to live on Home
/// as two arrows in a strip, which made the thing the ramp exists to protect - taking it one
/// stage at a time - into a pair of buttons nobody would read before clicking. Home now shows
/// the profile chip and nothing else about input, and the ladder is here, where the number it
/// changes is also written down.
/// </summary>
public sealed class ProfilePanelViewModel : ObservableObject, IDisposable
{
    private readonly ProfileService _store;
    private bool _disposed;

    /// <summary>Creates the drawer.</summary>
    public ProfilePanelViewModel(ProfileService store)
    {
        _store = store;
        Ramp = new RampViewModel(store);

        // The cm/360 chip is derived from the ramp, so a stage change has to reach the drawer.
        // One subscription, on the only object that can change it.
        Ramp.PropertyChanged += (_, _) => RefreshRampOnly();

        UseCommand = RelayCommand.CreateWithParameter(p => Use(p as ProfileListRow));
        CreateCommand = RelayCommand.Create(Create);
        DuplicateCommand = RelayCommand.Create(Duplicate);
        ExportCommand = RelayCommand.Create(Export);
        Refresh();
    }

    /// <summary>
    /// The three key-value chips under the profile's name: FOV, DPI and the travel distance.
    ///
    /// They are data rather than three hand-written Borders because the third one changes: the
    /// design says the cm/360 chip at the top of the panel follows the stage, and a chip that is
    /// hand-written into the view cannot follow anything.
    /// </summary>
    public IReadOnlyList<ProfileChip> Chips =>
    [
        new("FOV", FovText),
        new("DPI", DpiText),
        new(CentimetresText, "cm/360"),
    ];

    /// <summary>The ladder control's view model.</summary>
    public RampViewModel Ramp { get; }

    /// <summary>Every profile, default first.</summary>
    public ResetableCollection<ProfileListRow> Rows { get; } = [];

    /// <summary>Makes a profile active.</summary>
    public RelayCommand UseCommand { get; }

    /// <summary>Creates a profile seeded from the active one.</summary>
    public RelayCommand CreateCommand { get; }

    /// <summary>Duplicates the active profile.</summary>
    public RelayCommand DuplicateCommand { get; }

    /// <summary>Writes the active profile to the export folder.</summary>
    public RelayCommand ExportCommand { get; }

    /// <summary>The active profile's name, set in the display face by the drawer.</summary>
    public string ProfileName => _store.Active.Name;

    /// <summary>True when the active profile is the default one.</summary>
    public bool IsDefault => _store.Active.IsDefault;

    /// <summary>Vertical FOV, shown as a chip.</summary>
    public string FovText => _store.Active.VerticalFov.ToString("0", CultureInfo.InvariantCulture);

    /// <summary>Mouse DPI, shown as a chip.</summary>
    public string DpiText => _store.Active.MouseDpi.ToString(CultureInfo.InvariantCulture);

    /// <summary>
    /// The cm/360 chip. It follows the stage, so the number a player sees next to FOV and DPI is
    /// the one the ladder is actually on and not the one it was on when the drawer opened.
    ///
    /// It is formatted exactly as the ladder's own labels are - three decimals at the two ends,
    /// two in between - because a chip that printed 2.710 under a label that printed 2.71 would
    /// be two different renderings of one number, and the shorter one is the one the player is
    /// about to read.
    /// </summary>
    public string CentimetresText => Ramp.CurrentValue;

    /// <summary>Called by the shell after any change that could move the drawer.</summary>
    public void Refresh()
    {
        if (_disposed)
        {
            return;
        }

        Ramp.Refresh();
        Rows.ReplaceAll(_store.Profiles
            .OrderByDescending(p => p.IsDefault)
            .ThenBy(p => p.Name, StringComparer.OrdinalIgnoreCase)
            .Select(p => new ProfileListRow(p, p.Id == _store.Active.Id)));

        RaiseAll();
    }

    /// <summary>Re-reads only the ramp, which is what a stage change moves.</summary>
    public void RefreshRampOnly() => RaiseAll();

    private void RaiseAll()
    {
        Raise(nameof(ProfileName));
        Raise(nameof(IsDefault));
        Raise(nameof(FovText));
        Raise(nameof(DpiText));
        Raise(nameof(CentimetresText));
        Raise(nameof(Chips));
        Raise(nameof(Rows));
    }

    private void Use(ProfileListRow? row)
    {
        if (row is null)
        {
            return;
        }

        _store.SetActive(row.Profile.Id);
        AppLog.Shared.Info($"Active profile: {row.Name}");
        Refresh();
        ReraiseHome();
    }

    private void Create()
    {
        _store.Create("New Profile");
        Refresh();
        ReraiseHome();
    }

    private void Duplicate()
    {
        _store.Duplicate(_store.Active.Id);
        Refresh();
        ReraiseHome();
    }

    private void Export()
    {
        var path = _store.Export(_store.Active.Id);
        AppLog.Shared.Info(path is null
            ? "Profile export failed; see the log."
            : $"Exported the active profile to {path}. Nothing was uploaded.");
    }

    /// <summary>
    /// Tells the rest of the shell that the active profile changed, so the chip in the top bar
    /// and the status line on Home stop showing the previous one. Wired by the shell.
    /// </summary>
    public event EventHandler? ActiveProfileChanged;

    private void ReraiseHome() => ActiveProfileChanged?.Invoke(this, EventArgs.Empty);

    /// <inheritdoc />
    public void Dispose() => _disposed = true;
}
