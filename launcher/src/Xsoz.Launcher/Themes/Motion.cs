using System.Windows;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace Xsoz.Launcher.Themes;

/// <summary>
/// THE MOTION BUDGET. The one place that knows how long things take, what curve they use, and
/// which properties are allowed to move.
///
/// DESIGN-NOTES line 57, verbatim intent: "only two things move. Navigation fades (140 ms). The
/// primary button scales to 1.02 on hover and 0.99 on press. The toggle square jumps, rows expand
/// instantly, ramp stages change instantly, and the progress bar follows real progress. All of it
/// turns off under reduced motion."
///
/// Everything here obeys four rules, and every one of them is checked by
/// <c>XsozClient.exe --motioncheck</c>:
///
///   1. ONLY <see cref="AnimatedProperties"/> MOVE. Opacity and RenderTransform (Scale, Translate)
///      are composition properties: the compositor can re-run them on the GPU without re-running
///      measure, arrange or paint. A LayoutProperty - Width, Height, Margin, Padding, RowDefinition -
///      re-runs layout for the element and everything above it, on every single frame, and this
///      launcher's module list is twenty rows deep on a machine that shares its GPU with the game.
///   2. NOTHING LOOPS. No <c>RepeatBehavior</c> anywhere, and no animation here is longer than the
///      longest gesture. When the launcher is idle nothing on screen is moving, which is the only
///      way to be sure of it.
///   3. EVERY ANIMATION IS REPLACEABLE, NOT STACKABLE. Each entry point removes the previous
///      animation on the same property first (<c>BeginAnimation(prop, null)</c>) and writes the
///      destination value as the property's BASE value before animating, with
///      <see cref="FillBehavior.Stop"/>. A 140 ms fade that gets interrupted at 60 ms therefore
///      restarts from where it was, and when it ends there is no clock left attached to the element.
///   4. REDUCED MOTION IS A HARD OFF, NOT A SHORTER DURATION. <see cref="Enabled"/> is initialised
///      from the OS preference and every method below checks it; when it is false each method
///      writes its destination value and returns. Nothing is left half-applied, and a completion
///      callback still fires, so a view that defers a collapse on the animation's completion
///      collapses immediately rather than never.
///
/// THE CURVE. WPF has no <c>cubic-bezier()</c> and there is no way to add one:
/// <see cref="EasingFunction"/> is internal, so a custom easing function cannot be derived, and the
/// built-in set has no member that takes control points (there is no SplineEase, and CubicEase
/// exposes no key spline - it is the fixed t^3 polynomial). <see cref="EaseOut"/> is the closest
/// built-in: it passes through (0.5, 0.875) where the design's <c>cubic-bezier(.2,.7,.2,1)</c>
/// passes through (0.5, 0.93). Over 140 ms that is a fraction of a frame, and both curves are
/// "fast out of the gate, settling at the end", which is the whole intent. Documented rather than
/// hidden, and unchanged from the value the design was ported with.
///
/// NOTHING IN THIS FILE IS A DESIGN TOKEN. Colour, spacing, type and radius are untouched; the only
/// numbers here are durations, two scales and one distance, all taken from the design's own motion
/// rules.
/// </summary>
public static class Motion
{
    // ---------------------------------------------------------------------------------------------
    // Durations. The design's own number where it gives one, and the shortest value that still
    // reads as movement where it does not.
    // ---------------------------------------------------------------------------------------------

    /// <summary>--fade, in milliseconds. Navigation, and the primary button's scale.</summary>
    public const double FadeMilliseconds = 140;

    /// <summary>The toggle knob's travel. Shorter than --fade because the knob is 21px wide and a
    /// 140 ms traverse of it reads as a swipe.</summary>
    public const double KnobMilliseconds = 120;

    /// <summary>The profile drawer's slide and its scrim. A 480px panel needs longer than a fade to
    /// arrive without looking dropped.</summary>
    public const double DrawerMilliseconds = 180;

    /// <summary>
    /// The install bar's catch-up. Deliberately the shortest value in this file, and LINEAR.
    ///
    /// The bar follows real progress, so the only honest thing it can do is arrive AT the real
    /// value and never past it. A linear 60 ms catch-up is monotonic and never overshoots: at no
    /// point does the fill claim more than the service has actually reported. Easing is linear
    /// rather than ease-out precisely because ease-out would rush the last few pixels and read as
    /// more progress than exists.
    /// </summary>
    public const double ProgressMilliseconds = 60;

    /// <summary>--fade as a duration. Navigation fade, and the primary button's 1.02 / 0.99.</summary>
    public static Duration Fade => new(TimeSpan.FromMilliseconds(FadeMilliseconds));

    /// <summary>The toggle knob's travel as a duration.</summary>
    public static Duration Knob => new(TimeSpan.FromMilliseconds(KnobMilliseconds));

    /// <summary>The drawer slide and scrim as a duration.</summary>
    public static Duration Drawer => new(TimeSpan.FromMilliseconds(DrawerMilliseconds));

    // ---------------------------------------------------------------------------------------------
    // The reduced-motion switch.
    // ---------------------------------------------------------------------------------------------

    /// <summary>
    /// Whether anything in this class may animate. Default: on.
    ///
    /// Initialised from the OS preference and kept in sync with it, so turning off
    /// <c>Animate windows in Windows</c> (Settings &gt; Accessibility &gt; Visual effects) takes
    /// effect in a running launcher rather than at the next start. The sync is one-way - once the
    /// system has asked for less motion this flag does not turn itself back on - because a
    /// preference the user has expressed outranks whatever was in force when the process started.
    ///
    /// It is public and settable, which is the documented way to turn motion off from code
    /// (<c>Motion.Enabled = false;</c>), and it is honoured by every method below including the
    /// attached-property callbacks, so a XAML-authored scale and a code-driven slide obey the same
    /// switch. Nothing in this app needs an animation to be usable.
    /// </summary>
    public static bool Enabled { get; set; }

    /// <summary>
    /// True when the OS is asking for reduced motion.
    ///
    /// WPF exposes no "prefers-reduced-motion" media query, so the documented proxy is
    /// <see cref="SystemParameters.ClientAreaAnimation"/> - the system-wide "animate windows"
    /// switch, which is the same setting every high-contrast / animation-effects control in
    /// Windows maps to.
    /// </summary>
    public static bool SystemPrefersReducedMotion
    {
        get
        {
            try
            {
                return !SystemParameters.ClientAreaAnimation;
            }
            catch (Exception)
            {
                // No dispatcher yet - a static initialiser reached from a thread that has not
                // pumped one. Motion stays on, which is the documented default, and the value is
                // corrected the first time MainWindow asks again.
                return false;
            }
        }
    }

    static Motion()
    {
        Enabled = !SystemPrefersReducedMotion;

        try
        {
            SystemParameters.StaticPropertyChanged += OnSystemParameterChanged;
        }
        catch (Exception)
        {
            // No dispatcher to receive the broadcast. The initial read above still stands, and the
            // app also reads the preference explicitly at startup, so nothing is silently animated
            // on the strength of a failed subscription.
        }
    }

    private static void OnSystemParameterChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (SystemPrefersReducedMotion)
        {
            Enabled = false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Easing. A fresh instance per call, on purpose.
    // ---------------------------------------------------------------------------------------------

    /// <summary>
    /// The close approximation of <c>cubic-bezier(.2,.7,.2,1)</c>. See the class remarks for why
    /// WPF cannot do better.
    ///
    /// A fresh instance each time: an <see cref="IEasingFunction"/> is a Freezable that WPF freezes
    /// the first time an animation that uses it plays, and a frozen Freezable cannot be re-parented,
    /// so a shared static instance fails on the second element that tries to use it.
    /// </summary>
    public static IEasingFunction EaseOut() => new CubicEase { EasingMode = EasingMode.EaseOut };

    /// <summary>
    /// No curve at all. Used only by the install bar, where an easing curve would make the fill
    /// arrive faster than the download it is reporting on.
    ///
    /// Returns null, which is not a missing feature: WPF has no <c>LinearEasingFunction</c>. A
    /// <see cref="DoubleAnimation"/> with no <see cref="AnimationTimeline.EasingFunction"/> set
    /// interpolates linearly, so "linear" in WPF is the absence of this member rather than an
    /// instance of it. Returning null makes that explicit instead of leaving every call site to
    /// remember not to set the property.
    /// </summary>
    public static IEasingFunction? Linear() => null;

    // ---------------------------------------------------------------------------------------------
    // The allow-list, consumed by the static check.
    // ---------------------------------------------------------------------------------------------

    /// <summary>
    /// The one property <see cref="CatchUp"/> is called with.
    ///
    /// <see cref="CatchUp"/> takes its property as an argument so this file never has to name
    /// <c>Xsoz.Launcher.Controls.BlockBar</c>, which means the call site inside the helper reads
    /// <c>property</c> rather than a name the static check can resolve. Declaring the value here,
    /// as a constant the allow-list is built from, is how that indirection is accounted for
    /// honestly rather than being quietly exempt.
    /// </summary>
    public const string CatchUpProperty = "BlockBar.Fraction";

    /// <summary>
    /// Every property this class is permitted to animate, named as it appears at a call site.
    ///
    /// It is a declaration rather than a comment so that <c>--motioncheck</c> can assert the
    /// contract instead of trusting it: the check fails if a name leaves this list, and it fails if
    /// a <c>BeginAnimation</c> appears anywhere in the project outside this file. Read it as the
    /// answer to "what is allowed to move in this product".
    /// </summary>
    public static readonly string[] AnimatedProperties =
    [
        "UIElement.Opacity",
        "ScaleTransform.ScaleX",
        "ScaleTransform.ScaleY",
        "TranslateTransform.X",
        CatchUpProperty,
    ];

    /// <summary>
    /// The subset of <see cref="AnimatedProperties"/> that reaches <c>BeginAnimation</c> as an
    /// argument rather than as a name written at the call site. See <see cref="CatchUpProperty"/>.
    /// </summary>
    public static readonly string[] IndirectProperties = [CatchUpProperty];

    // ---------------------------------------------------------------------------------------------
    // Geometry. The one number that has to be right at 125% and 150%.
    // ---------------------------------------------------------------------------------------------

    // The toggle's own measurements, from COMPONENTS.md section 2: a 40x22 track, a 14x14 square
    // knob, and a 3px inset at each end. They are the design's, and the knob's travel is derived
    // from them rather than typed in.
    private const double ToggleTrackWidth = 40;
    private const double ToggleKnobSize = 14;
    private const double ToggleKnobInset = 3;

    /// <summary>
    /// How far the toggle knob travels between its two rests, in device-independent units.
    ///
    /// Derived, not typed: the knob sits 3px from the left edge with the track's hairline inside
    /// that, and 3px from the right edge with the same hairline inside that, so the travel is
    /// <c>40 - 2*(1/dpiScale) - 6 - 14</c>. Hardcoding the 18px that is correct at 100% puts the
    /// knob's trailing edge one device pixel inside the track at 150%, which is the same
    /// off-by-a-hairline class of defect <see cref="DpiMetrics"/> exists to prevent.
    /// </summary>
    public static double ToggleKnobTravel =>
        ToggleTrackWidth - (2 * DpiMetrics.Pixel) - (2 * ToggleKnobInset) - ToggleKnobSize;

    // ---------------------------------------------------------------------------------------------
    // The two XAML-facing attached properties. These are how a control style expresses motion
    // without a single storyboard, and without a view re-implementing an easing curve.
    // ---------------------------------------------------------------------------------------------

    /// <summary>
    /// Uniform scale for an element, animated on change. The design's primary-button rule.
    ///
    /// Set it from a style trigger rather than from a storyboard:
    /// <code>
    /// &lt;Trigger Property="IsMouseOver" Value="True"&gt;
    ///   &lt;Setter Property="m:Motion.Scale" Value="1.02" /&gt;
    /// &lt;/Trigger&gt;
    /// </code>
    /// The previous implementation used <c>Trigger.EnterActions</c> with a storyboard whose target
    /// was a <c>ScaleTransform</c> named inside the template. That never worked: a storyboard target
    /// name resolves through the templated parent's namescope, and a transform is not a
    /// framework element, so the storyboard could not find it. A data trigger on an attached
    /// property is plain, ordered, interruptible, and states the destination value where a reader
    /// can see it.
    ///
    /// Scaling is about the centre (<see cref="UIElement.RenderTransformOrigin"/> is set to 0.5,0.5)
    /// so the block does not creep down and right as it grows.
    /// </summary>
    public static readonly DependencyProperty ScaleProperty =
        DependencyProperty.RegisterAttached(
            "Scale",
            typeof(double),
            typeof(Motion),
            new FrameworkPropertyMetadata(1.0, FrameworkPropertyMetadataOptions.AffectsRender, OnScaleChanged));

    /// <summary>Identifies the <see cref="Scale"/> attached property.</summary>
    public static double GetScale(DependencyObject element) => (double)element.GetValue(ScaleProperty);

    /// <summary>Sets the <see cref="Scale"/> attached property.</summary>
    public static void SetScale(DependencyObject element, double value) => element.SetValue(ScaleProperty, value);

    private static void OnScaleChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is UIElement element)
        {
            ApplyScale(element, (double)e.NewValue);
        }
    }

    /// <summary>
    /// Horizontal offset for an element, animated on change. The toggle knob's travel and, through
    /// <see cref="SlideIn"/> / <see cref="SlideOut"/>, the profile drawer.
    /// </summary>
    public static readonly DependencyProperty TranslateXProperty =
        DependencyProperty.RegisterAttached(
            "TranslateX",
            typeof(double),
            typeof(Motion),
            new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.AffectsRender, OnTranslateXChanged));

    /// <summary>Identifies the <see cref="TranslateX"/> attached property.</summary>
    public static double GetTranslateX(DependencyObject element) => (double)element.GetValue(TranslateXProperty);

    /// <summary>Sets the <see cref="TranslateX"/> attached property.</summary>
    public static void SetTranslateX(DependencyObject element, double value) => element.SetValue(TranslateXProperty, value);

    private static void OnTranslateXChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is FrameworkElement element)
        {
            ApplyTranslate(element, (double)e.NewValue, Knob);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The operations. This is the whole vocabulary; a view picks one and never a curve.
    // ---------------------------------------------------------------------------------------------

    /// <summary>
    /// Fades an element in over --fade on the design's easing. The screen change, and nothing else.
    ///
    /// There is no slide, no push and no directionality: the design asks for a fade, and a
    /// directional transition would have to invent a rule for going backwards.
    /// </summary>
    public static void FadeIn(UIElement element)
    {
        ArgumentNullException.ThrowIfNull(element);

        // Clear whatever is in flight first, so a navigation that lands 40 ms after the last one
        // restarts from where the fade had got to instead of stacking a second clock on the same
        // property.
        element.BeginAnimation(UIElement.OpacityProperty, null);

        // The base value is the destination, and the animation fills nothing, so the moment the
        // 140 ms are up the property falls back to exactly this and is no longer being animated.
        element.Opacity = 1.0;

        if (!Enabled)
        {
            return;
        }

        element.BeginAnimation(UIElement.OpacityProperty, Track(0.0, 1.0, Fade));
    }

    /// <summary>
    /// Fades an element to a value and optionally reports when it has arrived.
    /// The scrim behind the profile drawer.
    /// </summary>
    /// <param name="element">The element to fade.</param>
    /// <param name="to">Destination opacity, 0..1.</param>
    /// <param name="duration">How long. Defaults to the overlay duration.</param>
    /// <param name="completed">Run when the element has arrived, or immediately when motion is off.</param>
    public static void FadeTo(UIElement element, double to, Duration? duration = null, Action? completed = null)
    {
        ArgumentNullException.ThrowIfNull(element);

        var from = element.Opacity;
        element.BeginAnimation(UIElement.OpacityProperty, null);
        element.Opacity = Math.Clamp(to, 0.0, 1.0);

        if (!Enabled)
        {
            completed?.Invoke();
            return;
        }

        var animation = Track(from, element.Opacity, duration ?? Drawer);
        if (completed is not null)
        {
            animation.Completed += (_, _) => completed();
        }

        element.BeginAnimation(UIElement.OpacityProperty, animation);
    }

    /// <summary>
    /// Slides an element in from <paramref name="distance"/> device-independent units to its resting
    /// place. The profile drawer, from the right.
    ///
    /// The movement is a <see cref="TranslateTransform"/> and nothing else. Animating the panel's
    /// width instead would re-run measure and arrange on the whole 480px column for eight frames,
    /// which is the one thing this class exists to prevent.
    /// </summary>
    /// <param name="element">The element to slide.</param>
    /// <param name="distance">How far off its resting place it starts, in DIPs.</param>
    /// <param name="completed">Run when it has arrived, or immediately when motion is off.</param>
    public static void SlideIn(FrameworkElement element, double distance, Action? completed = null)
    {
        ArgumentNullException.ThrowIfNull(element);

        var translate = RequireTranslate(element);
        translate.BeginAnimation(TranslateTransform.XProperty, null);
        translate.X = 0.0;

        if (!Enabled)
        {
            completed?.Invoke();
            return;
        }

        var animation = Track(distance, 0.0, Drawer);
        if (completed is not null)
        {
            animation.Completed += (_, _) => completed();
        }

        translate.BeginAnimation(TranslateTransform.XProperty, animation);
    }

    /// <summary>
    /// Slides an element out to <paramref name="distance"/> and reports when it has gone.
    /// The profile drawer, back to the right.
    ///
    /// The destination is written as the transform's base value, so a close that is interrupted -
    /// by the drawer being reopened, say - leaves the panel off-screen rather than halfway.
    /// </summary>
    /// <param name="element">The element to slide.</param>
    /// <param name="distance">Where it ends up, in DIPs from its resting place.</param>
    /// <param name="completed">Run when it has gone, or immediately when motion is off.</param>
    public static void SlideOut(FrameworkElement element, double distance, Action? completed = null)
    {
        ArgumentNullException.ThrowIfNull(element);

        var translate = RequireTranslate(element);
        var from = translate.X;
        translate.X = distance;

        if (!Enabled)
        {
            translate.BeginAnimation(TranslateTransform.XProperty, null);
            completed?.Invoke();
            return;
        }

        var animation = Track(from, distance, Drawer);
        if (completed is not null)
        {
            animation.Completed += (_, _) => completed();
        }

        translate.BeginAnimation(TranslateTransform.XProperty, animation);
    }

    /// <summary>
    /// Puts an element at an offset with no animation at all.
    ///
    /// Used to park the drawer off-screen before a capture or before the first open, and to put it
    /// back after a close, so the panel's resting position is never the result of an animation
    /// frame that happened to be mid-flight.
    /// </summary>
    public static void ParkTranslate(FrameworkElement element, double x)
    {
        ArgumentNullException.ThrowIfNull(element);

        var translate = RequireTranslate(element);
        translate.BeginAnimation(TranslateTransform.XProperty, null);
        translate.X = x;
    }

    /// <summary>
    /// The install bar's catch-up: a short, strictly linear move to the fraction the service has
    /// actually reported.
    ///
    /// Takes the property rather than the control so this file does not have to know about
    /// <c>Xsoz.Launcher.Controls.BlockBar</c>, and so the allow-list in
    /// <see cref="AnimatedProperties"/> stays the only place that says what is allowed to move.
    /// </summary>
    public static void CatchUp(UIElement target, DependencyProperty property, double from, double to)
    {
        ArgumentNullException.ThrowIfNull(target);
        ArgumentNullException.ThrowIfNull(property);

        if (!Enabled)
        {
            target.BeginAnimation(property, null);
            return;
        }

        target.BeginAnimation(
            property,
            new DoubleAnimation(from, to, new Duration(TimeSpan.FromMilliseconds(ProgressMilliseconds)))
            {
                EasingFunction = Linear(),
                FillBehavior = FillBehavior.Stop,
            });
    }

    // ---------------------------------------------------------------------------------------------
    // Internals.
    // ---------------------------------------------------------------------------------------------

    /// <summary>
    /// The one animation this class constructs. Everything above is a name for a call to this.
    ///
    /// <see cref="FillBehavior.Stop"/> is the load-bearing part: the caller has already written
    /// <paramref name="to"/> as the property's base value, so stopping the fill at the end leaves
    /// the property showing exactly its base value and detaches the clock. Nothing is left
    /// attached to the element for the lifetime of the window.
    /// </summary>
    private static DoubleAnimation Track(double from, double to, Duration duration) => new(from, to, duration)
    {
        EasingFunction = EaseOut(),
        FillBehavior = FillBehavior.Stop,
    };

    private static void ApplyScale(UIElement element, double target)
    {
        var scale = RequireScale(element);
        var fromX = scale.ScaleX;
        var fromY = scale.ScaleY;

        scale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
        scale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
        scale.ScaleX = target;
        scale.ScaleY = target;

        if (!Enabled)
        {
            return;
        }

        scale.BeginAnimation(ScaleTransform.ScaleXProperty, Track(fromX, target, Fade));
        scale.BeginAnimation(ScaleTransform.ScaleYProperty, Track(fromY, target, Fade));
    }

    private static void ApplyTranslate(FrameworkElement element, double target, Duration duration)
    {
        var translate = RequireTranslate(element);
        var from = translate.X;

        translate.BeginAnimation(TranslateTransform.XProperty, null);
        translate.X = target;

        if (!Enabled)
        {
            return;
        }

        translate.BeginAnimation(TranslateTransform.XProperty, Track(from, target, duration));
    }

    private static ScaleTransform RequireScale(UIElement element)
    {
        if (element.RenderTransform is ScaleTransform existing)
        {
            element.RenderTransformOrigin = new Point(0.5, 0.5);
            return existing;
        }

        var scale = new ScaleTransform(1.0, 1.0);

        // Centre, always. A button that grew from its top-left corner would drift across the
        // layout by half its own delta on every hover, which on a 600px-wide block is 6px.
        element.RenderTransformOrigin = new Point(0.5, 0.5);

        // Preserved rather than replaced: an element that already carries a translation - the
        // drawer, hypothetically - gets both rather than losing one of them.
        element.RenderTransform = element.RenderTransform is Transform { } current && !ReferenceEquals(current, Transform.Identity)
            ? new TransformGroup { Children = { current, scale } }
            : scale;

        return scale;
    }

    private static TranslateTransform RequireTranslate(FrameworkElement element)
    {
        if (element.RenderTransform is TranslateTransform existing)
        {
            return existing;
        }

        var translate = new TranslateTransform();

        element.RenderTransform = element.RenderTransform is Transform { } current && !ReferenceEquals(current, Transform.Identity)
            ? new TransformGroup { Children = { current, translate } }
            : translate;

        return translate;
    }
}

/// <summary>
/// Supplies <see cref="Motion.ToggleKnobTravel"/> to XAML.
///
/// It exists so the toggle's travel is written down as a derived, DPI-aware number rather than as
/// the literal 18 that happens to be right at 100%. Without it a control template would have to
/// either hardcode a value that is wrong at 150% or call into code from a style setter, and the
/// second is not a thing XAML can do.
/// </summary>
[MarkupExtensionReturnType(typeof(double))]
public sealed class ToggleKnobTravelExtension : MarkupExtension
{
    /// <inheritdoc />
    public override object ProvideValue(IServiceProvider serviceProvider) => Motion.ToggleKnobTravel;
}
