package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

/**
 * A packed {@code 0xAARRGGBB} colour (contracts.md C4.2).
 *
 * <p><strong>{@code 0x00FFFFFF} is the classic vanilla "invisible" value</strong> - the
 * transparent-white a texture pack or a broken config leaves behind. C4.2: a setting
 * declared {@code allowAlpha == false} <em>refuses</em> that value, because a colour that
 * cannot be seen is not a colour setting, it is a row the player cannot use and cannot
 * work out how to fix.</p>
 *
 * <p>Alpha is a <strong>validity</strong> question here, not a formatting one. C4.2 also
 * says {@code allowAlpha == false} forces alpha to {@code 0xFF} on write; that forcing is
 * the loader's job ({@link #forceAlpha}) and it is reported. Validation refuses the
 * invisible case outright and accepts a merely translucent one so the loader has
 * something to report.</p>
 */
public final class ColorSetting extends Setting {

    /** The alpha byte of a fully opaque colour. Unit: dimensionless 8-bit channel. */
    public static final int OPAQUE_ALPHA = 0xFF;

    /** The alpha byte of the classic vanilla "invisible" colour. Unit: dimensionless 8-bit channel. */
    public static final int INVISIBLE_ALPHA = 0x00;

    /** The mask isolating the alpha channel. Unit: dimensionless 8-bit channel. */
    public static final int ALPHA_MASK = 0xFF000000;

    /**
     * The unit name C4.2 associates with colour. Exposed as a constant rather than a
     * constructor argument because C4.2's {@code ColorSetting} signature has no
     * {@code unit} parameter, and a colour without a named format is a colour someone
     * will print as a decimal.
     */
    public static final String UNIT = "hex #AARRGGBB";

    private final int defaultArgb;
    private final boolean allowAlpha;

    /**
     * @param key         the globally unique key
     * @param label       the human label, no trailing colon
     * @param description one sentence, no trailing period
     * @param defaultArgb the declared default, {@code 0xAARRGGBB}
     * @param allowAlpha  whether a stored colour may carry a translucent alpha
     * @param sensitive   presentational only
     * @throws XsozContractException if the default is invisible while alpha is forbidden
     */
    public ColorSetting(String key, String label, String description,
                        int defaultArgb, boolean allowAlpha, boolean sensitive) {
        super(key, label, description, sensitive);
        if (!allowAlpha && alphaOf(defaultArgb) == INVISIBLE_ALPHA) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" forbids alpha but its default is 0x"
                            + String.format("%08X", Integer.valueOf(defaultArgb))
                            + ", the vanilla invisible value. A colour the player cannot see is "
                            + "not a default.");
        }
        this.defaultArgb = defaultArgb;
        this.allowAlpha = allowAlpha;
    }

    @Override
    public SettingKind kind() {
        return SettingKind.COLOR;
    }

    /** @return the declared default, packed {@code 0xAARRGGBB} */
    @Override
    public Integer defaultValue() {
        return Integer.valueOf(defaultArgb);
    }

    /** @return the declared default, unboxed */
    public int defaultArgb() {
        return defaultArgb;
    }

    /** @return whether a stored colour may carry a translucent alpha */
    public boolean allowAlpha() {
        return allowAlpha;
    }

    /** @return the unit name, always {@link #UNIT} */
    public String unit() {
        return UNIT;
    }

    /**
     * @param rawValue the value as read, a whole number in {@code 0xAARRGGBB}
     * @throws SettingValidationException if {@code rawValue} is {@code null}, is not a
     *                                   whole number, is outside the unsigned 32-bit
     *                                   range, or is the invisible value while alpha is
     *                                   forbidden
     */
    @Override
    public void validate(Object rawValue) {
        if (rawValue == null) {
            throw new SettingValidationException(key(),
                    "expected a packed 0xAARRGGBB colour, found nothing. A missing setting is a "
                            + "loader decision, not a value this setting can interpret.");
        }
        if (!(rawValue instanceof Number)) {
            throw new SettingValidationException(key(),
                    "expected a packed 0xAARRGGBB colour, found "
                            + rawValue.getClass().getSimpleName() + " (" + rawValue + ").");
        }
        int argb = unsignedArgbOf(key(), rawValue);
        if (!allowAlpha && alphaOf(argb) == INVISIBLE_ALPHA) {
            throw new SettingValidationException(key(),
                    "0x" + String.format("%08X", Integer.valueOf(argb)) + " is the vanilla invisible "
                            + "colour (alpha 0x00) and this setting forbids alpha. Pick a colour you "
                            + "can see.");
        }
    }

    /**
     * Reinterprets a stored colour as an unsigned 32-bit {@code 0xAARRGGBB}.
     *
     * <p><strong>A packed ARGB is not a signed int, but Java has only signed ones.</strong>
     * {@code 0xFF112233} is the {@code int} {@code -16711936}, and every opaque colour
     * begins with a high alpha byte, so refusing negative values would refuse the entire
     * useful range of the type. A boxed integral type is therefore read as unsigned 32
     * bits; only a floating-point value must fall inside the unsigned range explicitly.</p>
     *
     * @param key      the setting key, for the error message
     * @param rawValue the value as read
     * @return the packed colour
     * @throws SettingValidationException if the value is not a whole number in the unsigned
     *                                   32-bit range
     */
    static int unsignedArgbOf(String key, Object rawValue) {
        if (rawValue instanceof Byte || rawValue instanceof Short || rawValue instanceof Integer) {
            return ((Number) rawValue).intValue();
        }
        if (rawValue instanceof Long) {
            long value = ((Number) rawValue).longValue();
            if (value < 0L || value > 0xFFFFFFFFL) {
                throw new SettingValidationException(key,
                        "expected an unsigned 32-bit 0xAARRGGBB colour; found " + value + ".");
            }
            return (int) value;
        }
        double asDouble = ((Number) rawValue).doubleValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble) || asDouble != Math.rint(asDouble)
                || asDouble < 0.0d || asDouble > 4294967295.0d) {
            throw new SettingValidationException(key,
                    "expected a whole number in [0, 4294967295] packed as 0xAARRGGBB, found "
                            + rawValue + ".");
        }
        return (int) (long) asDouble;
    }

    /**
     * The loader's half of C4.2: forces alpha to {@code 0xFF} when {@link #allowAlpha()} is
     * false.
     *
     * @param argb the colour as read
     * @return the colour with alpha forced, or the colour unchanged when alpha is allowed
     */
    public int forceAlpha(int argb) {
        return allowAlpha ? argb : (argb | ALPHA_MASK);
    }

    /** @return the alpha channel of a packed colour, 0..255 */
    public static int alphaOf(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /** @return the red channel of a packed colour, 0..255 */
    public static int redOf(int argb) {
        return (argb >>> 16) & 0xFF;
    }

    /** @return the green channel of a packed colour, 0..255 */
    public static int greenOf(int argb) {
        return (argb >>> 8) & 0xFF;
    }

    /** @return the blue channel of a packed colour, 0..255 */
    public static int blueOf(int argb) {
        return argb & 0xFF;
    }

    /**
     * @param argb a packed colour
     * @return the colour as {@code #AARRGGBB}, the form C4.2's unit names
     */
    public static String toHexString(int argb) {
        return String.format("#%08X", Integer.valueOf(argb));
    }
}
