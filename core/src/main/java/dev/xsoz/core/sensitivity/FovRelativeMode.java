package dev.xsoz.core.sensitivity;

/**
 * How a model responds to a change of vertical field of view (contracts.md C6.4).
 *
 * <p>This is a closed enum. Adding a constant is a change request, because the mode
 * is persisted inside a profile and is user-visible on the settings screen.</p>
 */
public enum FovRelativeMode {

    /**
     * Mode 1, the default, and the only mode that is true in vanilla.
     *
     * <p>Changing FOV changes nothing: {@code sens} stays, {@code cmPer360} stays.
     * Vanilla's degrees-per-count contains no FOV term at all (C6.0).</p>
     */
    PHYSICAL,

    /**
     * Mode 2, a client-side convenience that is NOT vanilla behaviour.
     *
     * <p>Holds constant the pixels of world-sliding per millimetre of mouse travel,
     * which gives {@code k_new = k_old * tan(fovNew/2) / tan(fovOld/2)} on
     * {@code k = degPerCount}. Widening the FOV therefore <em>raises</em>
     * degrees-per-count. No server can observe this.</p>
     */
    SCREEN_SPEED
}
