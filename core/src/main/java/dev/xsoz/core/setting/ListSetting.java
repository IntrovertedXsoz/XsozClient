package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An ordered list with a declared maximum size (contracts.md C4.2).
 *
 * <p><strong>An over-length list on load is TRUNCATED FROM THE END and reported, not
 * rejected.</strong> C4.2 is explicit. A player who has trimmed a list of drill markers
 * down to fit, and then hands the file to a client with a smaller {@code maxSize}, must
 * still get a working profile with their first {@code maxSize} entries - not a reset.</p>
 *
 * <p>Truncating from the end rather than the front is the whole point: the first entries
 * are the ones the player put there first, and a prefix preserves their priority order.</p>
 *
 * <p>The optional {@code elementSchema} validates each element. When present, every
 * element must be a {@code Map} and must pass {@link SettingsSchema#validateAll(Map)} -
 * the same strict pass the whole-schema loader uses - so a list element cannot be a
 * well-formed map of nonsense.</p>
 *
 * @param <E> the element type; in practice a {@code Map<String, Object>} when an element
 *            schema is supplied
 */
public final class ListSetting<E> extends Setting {

    private final List<E> defaultValue;
    private final int maxSize;
    private final SettingsSchema elementSchema;

    /**
     * @param key           the globally unique key
     * @param label         the human label, no trailing colon
     * @param description   one sentence, no trailing period
     * @param defaultValue  the declared default; must not be longer than {@code maxSize}
     * @param maxSize       the maximum number of entries; must not be negative
     * @param elementSchema a schema every element must satisfy, or {@code null} when the
     *                      elements are opaque
     * @param sensitive     presentational only
     * @throws XsozContractException if {@code maxSize} is negative, the default is longer
     *                                than {@code maxSize}, or an element is {@code null}
     */
    public ListSetting(String key, String label, String description,
                       List<E> defaultValue, int maxSize, SettingsSchema elementSchema,
                       boolean sensitive) {
        super(key, label, description, sensitive);
        if (maxSize < 0) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" has maxSize " + maxSize + ". A negative maximum list "
                            + "size means the list can never hold anything, which is a range, not a "
                            + "list.");
        }
        if (defaultValue == null) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" needs a default list. Pass an empty list for a list "
                            + "that starts empty; null is not a list.");
        }
        for (E element : defaultValue) {
            if (element == null) {
                throw new XsozContractException(
                        "Setting \"" + key() + "\" has a null entry in its default list. A list with "
                                + "a hole in it has no defined meaning, and null is not usable as a "
                                + "list entry here.");
            }
        }
        if (defaultValue.size() > maxSize) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" has " + defaultValue.size() + " default entries but a "
                            + "maxSize of " + maxSize + ". A default its own setting would refuse is "
                            + "not a default.");
        }
        this.defaultValue = Collections.unmodifiableList(new ArrayList<E>(defaultValue));
        this.maxSize = maxSize;
        this.elementSchema = elementSchema;
    }

    @Override
    public SettingKind kind() {
        return SettingKind.LIST;
    }

    /** @return the declared default, unmodifiable */
    @Override
    public List<E> defaultValue() {
        return defaultValue;
    }

    /** @return the maximum number of entries */
    public int maxSize() {
        return maxSize;
    }

    /** @return the element schema, or {@code null} when elements are opaque */
    public SettingsSchema elementSchema() {
        return elementSchema;
    }

    /**
     * @param rawValue the value as read, a {@code List}
     * @throws SettingValidationException if {@code rawValue} is {@code null}, is not a
     *                                   list, holds a non-map element while an element
     *                                   schema is declared, holds an element the schema
     *                                   refuses, or is longer than {@link #maxSize()}
     */
    @Override
    public void validate(Object rawValue) {
        if (rawValue == null) {
            throw new SettingValidationException(key(),
                    "expected a list, found nothing. A missing setting is a loader decision, not a "
                            + "value this setting can interpret.");
        }
        if (!(rawValue instanceof List)) {
            throw new SettingValidationException(key(),
                    "expected a list, found " + rawValue.getClass().getSimpleName() + " ("
                            + rawValue + ").");
        }
        List<?> list = (List<?>) rawValue;
        if (list.size() > maxSize) {
            throw new SettingValidationException(key(),
                    "expected at most " + maxSize + " entries, found " + list.size()
                            + ". C4.2: the loader truncates an over-length list from the end and "
                            + "reports it; it never rejects the whole profile for this.");
        }
        if (elementSchema == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            if (!(element instanceof Map)) {
                throw new SettingValidationException(key(),
                        "entry " + i + " is a " + (element == null ? "null" : element.getClass()
                                .getSimpleName()) + ", not the map this setting's element schema "
                                + "declares.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> elementMap = (Map<String, Object>) element;
            elementSchema.validateAll(elementMap);
        }
    }

    /**
     * The loader's half of C4.2: keeps the first {@link #maxSize()} entries.
     *
     * @param rawList the list as read
     * @return the retained prefix, never longer than {@link #maxSize()}
     */
    public List<Object> truncateToMaxSize(List<?> rawList) {
        if (rawList == null || rawList.size() <= maxSize) {
            return rawList == null ? null : new ArrayList<Object>(rawList);
        }
        return new ArrayList<Object>(rawList.subList(0, maxSize));
    }

    /**
     * How many entries {@link #truncateToMaxSize(List)} would drop.
     *
     * @param rawList the list as read
     * @return the number of entries beyond {@link #maxSize()}, or zero
     */
    public int droppedCount(List<?> rawList) {
        return rawList == null || rawList.size() <= maxSize ? 0 : rawList.size() - maxSize;
    }
}
