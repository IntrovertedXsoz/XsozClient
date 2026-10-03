package dev.xsoz.core.config;

import java.util.List;

/**
 * Notified after a profile switch has been applied, so the bootstrap can do the parts of
 * C5.6's {@code switchTo} that live outside the config package.
 *
 * <p>C5.6's switch order is: pause the tick loop, snapshot the effective module state,
 * apply the new profile, re-run the enable gate for every module, resume, emit
 * {@code ProfileSwitchedEvent}. <strong>The config store owns "apply the new profile"
 * and nothing else</strong> - it does not know what a tick loop or a module gate is, and
 * a store that reached for them would couple {@code :core}'s config package to two
 * packages it does not own.</p>
 *
 * <p>Listeners are called <strong>after</strong> the new profile is in place and
 * <strong>before</strong> the store returns, so a listener that throws leaves the store
 * consistent rather than half-switched.</p>
 */
public interface ProfileSwitchListener {

    /**
     * @param previous the profile that was active, never {@code null}
     * @param current  the profile that is now active, never {@code null}
     * @param reason   why the switch happened, e.g. {@code "user"} or {@code "delete"}
     */
    void onProfileSwitched(Profile previous, Profile current, String reason);

    /**
     * @return an adapter that does nothing, for callers with nothing to do on a switch
     */
    static ProfileSwitchListener noop() {
        return new ProfileSwitchListener() {
            @Override
            public void onProfileSwitched(Profile previous, Profile current, String reason) {
                // Deliberately empty: a listener with nothing to do is not a missing listener.
            }

            @Override
            public String toString() {
                return "ProfileSwitchListener[noop]";
            }
        };
    }

    /**
     * @return a listener that records the last switch, for tests and for the About screen
     */
    static RecordingProfileSwitchListener recording() {
        return new RecordingProfileSwitchListener();
    }

    /** A {@link ProfileSwitchListener} that keeps the switches it saw. */
    final class RecordingProfileSwitchListener implements ProfileSwitchListener {

        private final List<ProfileSwitch> switches = new java.util.ArrayList<ProfileSwitch>();

        @Override
        public void onProfileSwitched(Profile previous, Profile current, String reason) {
            switches.add(new ProfileSwitch(previous, current, reason));
        }

        /** @return the switches seen, in order */
        public List<ProfileSwitch> switches() {
            return java.util.Collections.unmodifiableList(switches);
        }

        /** @return the number of switches seen */
        public int count() {
            return switches.size();
        }
    }

    /** One recorded switch. */
    final class ProfileSwitch {

        private final Profile previous;
        private final Profile current;
        private final String reason;

        ProfileSwitch(Profile previous, Profile current, String reason) {
            this.previous = previous;
            this.current = current;
            this.reason = reason;
        }

        /** @return the profile that was active before */
        public Profile previous() {
            return previous;
        }

        /** @return the profile that is active now */
        public Profile current() {
            return current;
        }

        /** @return why the switch happened */
        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            return "ProfileSwitch[" + previous.id() + " -> " + current.id() + " (" + reason + ")]";
        }
    }
}
