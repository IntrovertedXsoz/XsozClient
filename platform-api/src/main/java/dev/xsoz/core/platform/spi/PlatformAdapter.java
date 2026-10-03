package dev.xsoz.core.platform.spi;

/**
 * The one interface a Minecraft pole implements (contracts.md C10.1).
 *
 * <p>Exactly one implementation ships per pole jar, registered as a loader entrypoint in
 * that pole's mod metadata and discovered by core through
 * {@code java.util.ServiceLoader} over
 * {@code META-INF/services/dev.xsoz.core.platform.spi.PlatformAdapter}. Core therefore
 * never names a platform class, and compiling core alone needs no pole on the classpath.</p>
 *
 * <p><strong>CONTAINS NO GAME TYPES.</strong> The contract says it so; the reason is that
 * the two poles share no mapping system (one is fully obfuscated behind a third-party
 * mapping, the other is unobfuscated), so a shared surface that mentioned a game's own
 * type would be unbuildable across the range.</p>
 *
 * <p><strong>SCOPE NOTE, read this before adding a method.</strong> The complete frozen
 * signature set from contracts.md C10.1 is reproduced verbatim below. Only the three
 * lifecycle methods are declared as members today, because the accessor methods return
 * C1/C7/C8 value types ({@code PlatformIdentity}, {@code Capability},
 * {@code GameAccessFacade}, {@code HudSurface}, {@code DrawList}, {@code ScreenModel})
 * that no agent has written yet. Declaring those types here would pre-empt the agent that
 * owns {@code dev.xsoz.core.platform} and turn a missing file into a merge fight. The
 * accessors are declared in the same commit that adds the types they reference; their
 * signatures are already frozen and must not be re-derived.</p>
 *
 * <pre>
 * public interface PlatformAdapter {
 *     PlatformIdentity identity();
 *     Set&lt;Capability&gt; capabilities();
 *     GameAccessFacade facade();
 *     HudRenderer hudRenderer();
 *     ScreenHost screenHost();
 *     OptOutTransport optOutTransport();
 *     void install();       // register hooks. MUST NOT read game state. MUST NOT post events.
 *     void start();         // begin the tick and render loops. Begins posting events.
 *     void shutdown();      // stop loops, flush, unregister. MUST be idempotent.
 * }
 * </pre>
 *
 * <p>{@code HudRenderer}, {@code ScreenHost} and {@code OptOutTransport} are declared in
 * the same contract section and land with the value types they reference. Their frozen
 * shapes are:</p>
 *
 * <pre>
 * public interface HudRenderer {
 *     void submit(DrawList list, HudSurface surface);   // replay our command list
 * }
 * public interface ScreenHost {
 *     void openSettings(ScreenModel model);             // opens OUR screen. Nothing else.
 *     void close();
 *     boolean isOpen();
 * }
 * </pre>
 */
public interface PlatformAdapter {

    /**
     * Registers hooks. <strong>MUST NOT read game state. MUST NOT post events.</strong>
     *
     * <p>Called once during bootstrap, before identity and capabilities are resolved.</p>
     */
    void install();

    /**
     * Begins the tick and render loops. This is where the adapter starts posting events.
     *
     * <p>Called once, after the module registry is frozen and the enable gate has been
     * evaluated.</p>
     */
    void start();

    /**
     * Stops loops, flushes and unregisters. <strong>MUST be idempotent.</strong>
     *
     * <p>Called once on shutdown, after every module has been disabled in descending id
     * order.</p>
     */
    void shutdown();
}
