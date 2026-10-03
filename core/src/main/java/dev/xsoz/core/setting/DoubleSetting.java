package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

/**
 * A real-number setting with a declared range, step and unit (contracts.md C4.2).
 *
 * <p><strong>The unit is not optional.</strong> A numeric setting with a null or empty
 * {@code unit} does not compile - the constructor throws. This project has already been
 * burned twice by a unit error (a 5.8x sensitivity mistake, then a 6.667x one; C6.0), and an
 * unnamed number is how that happens a third time.</p>
 *
 * <p><strong>{@link #validate} refuses; it does not clamp.</strong> Coercion is the loader's
 * job (C4.4) and it reports what it did. {@link #coerce} is the loader's half: snap to the
 * step, then clamp to the range, and return the result so the loader can put it in its
 * report.</p>
 */
public final class DoubleSetting extends Setting {

    private final double defaultValue;
    private final double minInclusive;
    private final double maxInclusive;
    private final double step;
    private final String unit;

    /**
     * @param key          the globally unique key
     * @param label        the human label, no trailing colon
     * @param description  one sentence, no trailing period
     * @param defaultValue the declared default; must be inside the range
     * @param minInclusive the lower bound, inclusive
     * @param maxInclusive the upper bound, inclusive
     * @param step         the granularity; must be greater than zero
     * @param unit         a non-empty unit name, e.g. {@code "blocks"}, {@code "millis"},
     *                     {@code "cm/360"}, {@code "counts/second"}
     * @param sensitive    presentational only
     * @throws XsozContractException if the range is empty, the step is not positive, the unit
     *                                is absent, or the default is outside the range
     */
    public DoubleSetting(String key, String label, String description,
                         double defaultValue, double minInclusive, double maxInclusive,
                         double step, String unit, boolean sensitive) {
        super(key, label, description, sensitive);
        if (minInclusive > maxInclusive) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" has an empty range: minInclusive " + minInclusive
                            + " > maxInclusive " + maxInclusive + ".");
        }
        if (!(step > 0.0d)) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" has step " + step + ". C4.2: a step <= 0.0 throws at "
                            + "construction; a setting that can never advance has no step at all.");
        }
        if (unit == null || unit.trim().isEmpty()) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" has no unit. A numeric setting with a null or empty "
                            + "unit does not compile. Name it, e.g. \"blocks\", \"millis\", "
                            + "\"cm/360\", \"counts/second\".");
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
        return SettingKind.DOUBLE;
    }

    /** @return the declared default, boxed */
    @Override
    public Double defaultValue() {
        return Double.valueOf(defaultValue);
    }

    /** @return the declared default, unboxed */
    public double defaultDouble() {
        return defaultValue;
    }

    /** @return the lower bound, inclusive. Unit: the setting's unit. */
    public double minInclusive() {
        return minInclusive;
    }

    /** @return the upper bound, inclusive. Unit: the setting's unit. */
    public double maxInclusive() {
        return maxInclusive;
    }

    /** @return the granularity. Unit: the setting's unit. */
    public double step() {
        return step;
    }

    /** @return the unit name, e.g. {@code "cm/360"} */
    public String unit() {
        return unit;
    }

    /**
     * @param rawValue the value as read
     * @throws SettingValidationException if {@code rawValue} is {@code null}, is not a
     *                                   {@link Number}, is NaN or infinite, or is outside the
     *                                   declared range
     */
    @Override
    public void validate(Object rawValue) {
        if (rawValue == null) {
            throw new SettingValidationException(key(),
                    "expected a number, found nothing. A missing setting is a loader decision, not a "
                            + "value this setting can interpret.");
        }
        if (!(rawValue instanceof Number)) {
            throw new SettingValidationException(key(),
                    "expected a number, found " + rawValue.getClass().getSimpleName() + " ("
                            + rawValue + "). C4.1: validate throws, it does not coerce.");
        }
        double value = ((Number) rawValue).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new SettingValidationException(key(),
                    "expected a finite number, found " + value + ".");
        }
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
     * @param rawValue a value already accepted by {@link #validate}
     * @return the snapped, clamped value
     * @throws SettingValidationException if {@code rawValue} is not a finite number
     */
    public double coerce(double rawValue) {
        if (Double.isNaN(rawValue) || Double.isInfinite(rawValue)) {
            throw new SettingValidationException(key(),
                    "cannot coerce a non-finite value (" + rawValue + ").");
        }
        double snapped = minInclusive
                + Math.round((rawValue - minInclusive) / step) * step;
        if (snapped < minInclusive) {
            return minInclusive;
        }
        if (snapped > maxInclusive) {
            return maxInclusive;
        }
        return snapped;
    }
}
