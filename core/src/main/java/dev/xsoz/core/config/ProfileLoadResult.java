package dev.xsoz.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A profile plus everything the loader had to do to it.
 *
 * <p><strong>The notes are part of the result, not a side channel.</strong> A dropped
 * module, a coerced setting and a reset-to-default are all things the player has to be
 * able to see, and a loader that returns only the profile has thrown the evidence away
 * (C4.4, C5.4).</p>
 *
 * <p>Deeply immutable.</p>
 */
public final class ProfileLoadResult {

    private final Profile profile;
    private final List<String> notes;

    /**
     * @param profile the loaded profile
     * @param notes   what the loader had to do, in the order it did it
     */
    public ProfileLoadResult(Profile profile, List<String> notes) {
        this.profile = profile;
        this.notes = Collections.unmodifiableList(new ArrayList<String>(notes));
    }

    /** @return the loaded profile */
    public Profile profile() {
        return profile;
    }

    /** @return one line per coercion, drop, reset or migration applied */
    public List<String> notes() {
        return notes;
    }

    /** @return whether the document loaded exactly as written */
    public boolean isClean() {
        return notes.isEmpty();
    }

    @Override
    public String toString() {
        return "ProfileLoadResult[" + profile + ", " + notes.size() + " notes]";
    }
}
