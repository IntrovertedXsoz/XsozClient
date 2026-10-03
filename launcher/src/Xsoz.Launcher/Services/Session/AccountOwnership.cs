namespace Xsoz.Launcher.Services;

/// <summary>
/// Who owns the account, stated once so every screen says the same thing.
///
/// WHY THIS FILE EXISTS INSTEAD OF THE CODE IT REPLACED. This product used to read the official
/// launcher's DPAPI-protected Microsoft credentials, decrypt them, and exchange them through the
/// Xbox Live and Minecraft Services authentication chain to obtain a bearer token of its own. That
/// code is deleted. Not disabled, not moved: deleted, along with the interface, the abstracting
/// implementation, the module initializer that installed it, and the encrypted store it wrote to.
///
/// The architecture that replaced it does not authenticate. There is nothing here that opens a
/// credential file, calls an auth endpoint, holds a token, or writes one to disk. The official
/// Minecraft launcher signs the user in, with the user's own Microsoft account, and launches the
/// installation this product provisions. That is also why the "unable to find login token"
/// message is gone: this launcher never looks for a token, so it can never fail to find one.
///
/// What remains is a description of who owns the account, so the interface can say one true thing
/// instead of four apologetic ones.
/// </summary>
public static class AccountOwnership
{
    /// <summary>
    /// What the account chip's first line reads. Not a player name: this launcher does not know one,
    /// does not ask for one, and must not display a name it has not verified.
    /// <para>
    /// Deliberately two words. The chip is 224px wide with a 32px initial square in it, and a
    /// longer string here is not read - it is ellipsised into "Mi…", which tells the user nothing
    /// and looks like a bug. The full sentence lives on the quiet line below the chip, where there
    /// is room for it.
    /// </para>
    /// </summary>
    public const string ChipName = "Minecraft";

    /// <summary>
    /// The chip's second line. Who owns the account. Kept to two words for the same layout reason
    /// as <see cref="ChipName"/>; the full sentence is on the quiet line under the chip.
    /// </summary>
    public const string ChipSubtext = "Via launcher";

    /// <summary>
    /// The chip's action. It opens the official Minecraft launcher; it does not sign anything in.
    /// <para>
    /// One word, for the same layout reason as <see cref="ChipName"/>: the chip is a ~200px box
    /// holding a 32px initial square, a star-sized name column and an Auto-width button, and a
    /// longer label here starves the name until the name is what gets ellipsised. "Log In" would
    /// also have been a lie - there is no log-in here to perform.
    /// </para>
    /// </summary>
    public const string ChipAction = "Launch";

    /// <summary>The longer form, used where there is room for it - the Settings screen and the status line.</summary>
    public const string ChipNameLong = "Minecraft account";

    /// <summary>The one honest line about accounts, in place of the old token-unavailable apology. One
    /// sentence, no apology, no API-approval story, because there is no longer anything this
    /// launcher is waiting for approval to do.
    /// </summary>
    public const string Notice =
        "The official Minecraft Launcher signs you in when you launch from it, so there is nothing to sign in to here.";

    /// <summary>
    /// The disclaimer that replaced the account message. This launcher reads no credentials, holds
    /// no token, and contacts no authentication endpoint.
    /// </summary>
    public const string CredentialStatement =
        "This launcher reads no account credentials and holds no access token. Authentication is the "
        + "official Minecraft Launcher's job.";

    /// <summary>The label for the hand-off button.</summary>
    public const string HandOffLabel = "OPEN IN MINECRAFT LAUNCHER";

    /// <summary>
    /// The label for the documented fallback: this launcher's own self-provisioned instance, which
    /// owns its own runtime and runs offline. It stays because it is proven on this machine and
    /// because a launcher whose only path forward is somebody else's GUI is a single point of
    /// failure.
    /// </summary>
    public const string FallbackLabel = "SELF-MANAGED FALLBACK";

    /// <summary>The one line under the fallback button.</summary>
    public const string FallbackNotice =
        "Fallback: a separate instance this launcher provisions and starts itself. Runs offline, and "
        + "does not load your Minecraft account.";

    /// <summary>
    /// The name the launcher ships with before anybody has typed one. It is the game's own default
    /// placeholder, not a player, and it is treated as "nobody has told us a name" everywhere that
    /// matters - most visibly by <c>Services\HeadService</c>, which draws the default head rather
    /// than asking an endpoint to render a face for somebody called "Player".
    /// </summary>
    public const string UnknownPlayerName = "Player";

    /// <summary>
    /// The rule the account chip's skin head is under, stated once.
    /// <para>
    /// The head is a picture. It says which skin somebody chose; it says nothing about whether
    /// they are signed in, because they are not - the official Minecraft Launcher is. Nothing about
    /// the chip's wording, its button, or its quiet line below changes when a head appears, and the
    /// head never appears beside a name this launcher has not been given.
    /// </para>
    /// </summary>
    public const string HeadNotice =
        "The head is a skin picture, not a sign-in. You are still signed in through the Minecraft "
        + "Launcher, and the launcher says so.";
}