package dev.xsoz.core.setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the loader had to do to turn stored values into a usable view (contracts.md C4.3).
 *
 * <p>Four lists, four different fates, so a caller can tell them apart at a glance:</p>
 * <ul>
 *   <li>{@link #unknownKeysDropped()} - a key the schema does not declare. The entry is
 *       gone. A hand-edited file or an older client leaves these behind.</li>
 *   <li>{@link #valuesCoerced()} - the value is real but not in the stored form:
 *       a whole number for a double, a translucent colour for an opaque-only setting, a
 *       number clamped into range. {@code key -> reason}.</li>
 *   <li>{@link #valuesResetToDefault()} - the value could not be used at all: a string
 *       where an enum belongs, text over its length, a keybind the policy refused. The
 *       declared default is now in force.</li>
 *   <li>{@link #valuesTruncated()} - a list longer than its {@code maxSize}, shortened
 *       from the end.</li>
 * </ul>
 *
 * <p><strong>The order matters and is a decision, not an accident: drop, coerce, reset,
 * truncate.</strong> A key that is not in the schema is dropped before anything looks at
 * its value, because coercing a value nothing will read is work spent on a lie.</p>
 *
 * <p><strong>A {@code sensitive} setting's value is never printed into a report.</strong>
 * The report is shown in a settings screen and written to a recovery log; the key is named
 * and the reason is given, and the value itself goes through {@link Setting#redact}.</p>
 *
 * <p>Deeply immutable. A report with no problems is empty, and {@link #isClean()} says so
 * without the caller counting four lists.</p>
 */
public final class SettingsMutationReport {

    private final List<String> unknownKeysDropped;
    private final Map<String, String> valuesCoerced;
    private final List<String> valuesResetToDefault;
    private final List<String> valuesTruncated;

    /**
     * @param unknownKeysDropped  dropped keys, each with a reason
     * @param valuesCoerced       coerced keys mapped to their reason
     * @param valuesResetToDefault reset keys, each with a reason
     * @param valuesTruncated     truncated keys, each with a reason
     */
    public SettingsMutationReport(List<String> unknownKeysDropped,
                                  Map<String, String> valuesCoerced,
                                  List<String> valuesResetToDefault,
                                  List<String> valuesTruncated) {
        this.unknownKeysDropped = Collections.unmodifiableList(
                new ArrayList<String>(unknownKeysDropped));
        this.valuesCoerced = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(valuesCoerced));
        this.valuesResetToDefault = Collections.unmodifiableList(
                new ArrayList<String>(valuesResetToDefault));
        this.valuesTruncated = Collections.unmodifiableList(
                new ArrayList<String>(valuesTruncated));
    }

    /** @return an empty report, the normal result of loading an exactly-valid file */
    public static SettingsMutationReport clean() {
        return new SettingsMutationReport(Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
    }

    /** @return the keys the schema does not declare; every one was dropped */
    public List<String> unknownKeysDropped() {
        return unknownKeysDropped;
    }

    /** @return coerced keys mapped to the reason, in load order */
    public Map<String, String> valuesCoerced() {
        return valuesCoerced;
    }

    /** @return keys whose value could not be used and was replaced by the default */
    public List<String> valuesResetToDefault() {
        return valuesResetToDefault;
    }

    /** @return keys whose list was shortened from the end */
    public List<String> valuesTruncated() {
        return valuesTruncated;
    }

    /** @return whether nothing at all had to be done to the stored values */
    public boolean isClean() {
        return unknownKeysDropped.isEmpty() && valuesCoerced.isEmpty()
                && valuesResetToDefault.isEmpty() && valuesTruncated.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SettingsMutationReport)) {
            return false;
        }
        SettingsMutationReport that = (SettingsMutationReport) other;
        return unknownKeysDropped.equals(that.unknownKeysDropped)
                && valuesCoerced.equals(that.valuesCoerced)
                && valuesResetToDefault.equals(that.valuesResetToDefault)
                && valuesTruncated.equals(that.valuesTruncated);
    }

    @Override
    public int hashCode() {
        return ((unknownKeysDropped.hashCode() * 31 + valuesCoerced.hashCode()) * 31
                + valuesResetToDefault.hashCode()) * 31 + valuesTruncated.hashCode();
    }

    @Override
    public String toString() {
        return "SettingsMutationReport[dropped=" + unknownKeysDropped.size()
                + ", coerced=" + valuesCoerced.size()
                + ", reset=" + valuesResetToDefault.size()
                + ", truncated=" + valuesTruncated.size() + "]";
    }
}
