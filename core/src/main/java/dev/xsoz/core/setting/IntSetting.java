package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

/**
 * A whole-number setting with a declared range, step and unit (contracts.md C4.2).
 *
 * <p>Same rules as {@link DoubleSetting}, and for the same reason: <strong>{@link #validate}
 * throws and does not clamp</strong>, {@link #coerce(int)} is the loader's half, and a
 * null or empty {@code unit} does not compile.</p>
 *
 * <p><strong>A whole number is required, but not a boxed {@code Integer}.</strong> A
 * hand-edited file or a JSON document carries {@code 8.0}; that is the same value and
 * rejecting it would drop a setting the player can see. A value that is not integral -
 * {@code 8.5} - is refused, because a setting of kind {@code INT} that silently accepted
 * {@code 8.5} would be lying about its own kind.</p>
 */
public final class IntSetting extends Setting {

    private final int defaultValue;
    private final int minInclusive;
    private final int maxInclusive;
    private final int step;
    private final String unit;

    /**
     * @param key          the globally unique key
     * @param label        the human label, no trailing colon
     * @param description  one sentence, no trailing period
     * @param defaultValue the declared default; must be inside the range
     * @param minInclusive the lower bound, inclusive
     * @param maxInclusive the upper bound, inclusive
     * @param step         the granularity; must be greater than zero
     * @param unit         a non-empty unit name, e.g. {@code "blocks"}, {@code "ticks"},
     *                     {@code "millis"}, {@code "percent"}
     * @param sensitive    presentational only
     * @throws XsozContractException if the range is empty, the step is not positive, the
     *                                unit is absent, or the default is outside the range
     */
    public IntSetting(String key, String label, String description,
                      int defaultValue, int minInclusive, int maxInclusive,
                      int step, String unit, boolean sensitive) {
        super(key, label, description, sensitive);
        if (minInclusive > maxInclusive) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" has an empty range: minInclusive " + minInclusive
                            + " > maxInclusive " + maxInclusive + ".");
        }
        if (step <= 0) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" has step " + step + ". C4.2: a step <= 0 throws at "
                            + "construction; a setting that can never advance has no step at all.");
        }
        if (unit == null || unit.trim().isEmpty()) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" has no unit. A numeric setting with a null or empty "
                            + "unit does not compile. Name it, e.g. \"blocks\", \"ticks\", "
                            + "\"millis\", \"percent\".");
        }
        if (defaultValue < minInclusive || defaultValue > maxInclusive) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" has default " + defaultValue + ", outside its range ["
                            + minInclusive + ", " + maxInclusive + "]. A default outside the range is "
                            + "a value the setting itself would refuse.");
        }
        this.defaultValue = defaultValue;
        this.minInclusive = minInclusive;
        this.maxInclusive = maxInclusive;
        this.step = step;
        this.unit = unit.trim();
    }

    @Override
    public SettingKind kind() {
        return SettingKind.INT;
    }

    /** @return the declared default, boxed */
    @Override
    public Integer defaultValue() {
        return Integer.valueOf(defaultValue);
    }

    /** @return the declared default, unboxed */
    public int defaultInt() {
        return defaultValue;
    }

    /** @return the lower bound, inclusive. Unit: the setting's unit. */
    public int minInclusive() {
        return minInclusive;
    }

    /** @return the upper bound, inclusive. Unit: the setting's unit. */
    public int maxInclusive() {
        return maxInclusive;
    }

    /** @return the granularity. Unit: the setting's unit. */
    public int step() {
        return step;
    }

    /** @return the unit name, e.g. {@code "ticks"} */
    public String unit() {
        return unit;
    }

    /**
     * @param rawValue the value as read
     * @throws SettingValidationException if {@code rawValue} is {@code null}, is not a
     *                                   number, is not a whole number, or is outside the
     *                                   declared range
     */
    @Override
    public void validate(Object rawValue) {
        if (rawValue == null) {
            throw new SettingValidationException(key(),
                    "expected a whole number, found nothing. A missing setting is a loader "
                            + "decision, not a value this setting can interpret.");
        }
        if (!(rawValue instanceof Number)) {
            throw new SettingValidationException(key(),
                    "expected a whole number, found " + rawValue.getClass().getSimpleName() + " ("
                            + rawValue + "). C4.1: validate throws, it does not coerce.");
        }
        double asDouble = ((Number) rawValue).doubleValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
            throw new SettingValidationException(key(), "expected a finite number, found " + asDouble + ".");
        }
        if (asDouble != Math.rint(asDouble)) {
            throw new SettingValidationException(key(),
                    "expected a whole number, found " + rawValue + ". Setting " + key()
                            + " is an INT; a fractional value here is a kind mismatch, not a "
                            + "value to round.");
        }
        if (asDouble < Integer.MIN_VALUE || asDouble > Integer.MAX_VALUE) {
            throw new SettingValidationException(key(),
                    "expected a value that fits in a 32-bit integer, found " + rawValue + ".");
        }
        int value = (int) asDouble;
        if (value < minInclusive || value > maxInclusive) {
            throw new SettingValidationException(key(),
                    "expected a value in [" + minInclusive + ", " + maxInclusive + "] " + unit
                            + ", found " + value + ". C4.1: validate throws, it does not clamp.");
        }
    }

    /**
     * The loader's half of C4.4: snap to the step, then clamp to the range.
     * {@code minInclusive + round((v - minInclusive) / step) * step}, then clamp.
     *
     * <p>Computed in {@code long} arithmetic and narrowed at the end, so a range spanning
     * the whole {@code int} domain cannot overflow into a wrong answer.</p>
     *
     * @param rawValue a value already accepted by {@link #validate}
     * @return the snapped, clamped value
     */
    public int coerce(int rawValue) {
        long min = minInclusive;
        long stepCount = Math.round((double) (rawValue - min) / (double) step);
        long snapped = min + stepCount * step;
        if (snapped < min) {
            return minInclusive;
        }
        if (snapped > maxInclusive) {
            return maxInclusive;
        }
        return (int) snapped;
    }
}
