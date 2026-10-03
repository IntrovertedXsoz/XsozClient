package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Text with a declared maximum length and an allowed-characters pattern
 * (contracts.md C4.2).
 *
 * <p><strong>{@code maxLength} is counted in CODE POINTS, not UTF-16 units.</strong> An
 * emoji is one code point and two {@code char}s, so a length limit counted in
 * {@code char}s silently halves the allowance for any text a player actually types.</p>
 *
 * <p><strong>{@code maxLength} is mandatory.</strong> C4.2: a chat log or a server name is
 * never stored through a {@code StringSetting} that lacks one, because an unbounded
 * string in a config file is a file that grows without a limit the player can see.</p>
 *
 * <p><strong>{@code allowedChars} is matched against the WHOLE string</strong>, via
 * {@link java.util.regex.Matcher#matches()} and not {@code find()}. A pattern that
 * matches a substring is a pattern that admits every string containing one legal
 * character, which is not what "allowed characters" means.</p>
 *
 * <p>A {@code null} pattern means "every character is allowed", and is documented as such
 * rather than being represented by {@code .*}: a caller that means "unrestricted" should
 * say so.</p>
 */
public final class StringSetting extends Setting {

    private final String defaultValue;
    private final int maxLength;
    private final Pattern allowedChars;

    /**
     * @param key          the globally unique key
     * @param label        the human label, no trailing colon
     * @param description  one sentence, no trailing period
     * @param defaultValue the declared default; must satisfy {@code maxLength} and the
     *                     pattern
     * @param maxLength    the maximum length in code points; must be positive
     * @param allowedChars the whole-string pattern, or {@code null} for unrestricted text
     * @param sensitive    presentational only; true for anything that could be a chat line
     * @throws XsozContractException if {@code maxLength} is not positive, or the default
     *                                does not satisfy the declared constraints
     */
    public StringSetting(String key, String label, String description,
                         String defaultValue, int maxLength, Pattern allowedChars, boolean sensitive) {
        super(key, label, description, sensitive);
        if (maxLength <= 0) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" has maxLength " + maxLength + ". C4.2: chat text and "
                            + "server names are never stored through a StringSetting that lacks a "
                            + "length bound, so a non-positive one is refused here.");
        }
        this.defaultValue = defaultValue;
        this.maxLength = maxLength;
        this.allowedChars = allowedChars;
        if (defaultValue != null) {
            validate(defaultValue);
        }
    }

    @Override
    public SettingKind kind() {
        return SettingKind.STRING;
    }

    /** @return the declared default, possibly {@code null} */
    @Override
    public String defaultValue() {
        return defaultValue;
    }

    /** @return the maximum length in code points */
    public int maxLength() {
        return maxLength;
    }

    /** @return the whole-string pattern, or {@code null} when text is unrestricted */
    public Pattern allowedChars() {
        return allowedChars;
    }

    /**
     * @param rawValue the value as read
     * @throws SettingValidationException if {@code rawValue} is {@code null}, is longer
     *                                   than {@link #maxLength()} code points, or does not
     *                                   match {@link #allowedChars()} as a whole
     */
    @Override
    public void validate(Object rawValue) {
        if (rawValue == null) {
            throw new SettingValidationException(key(),
                    "expected text, found nothing. A missing setting is a loader decision, not a "
                            + "value this setting can interpret.");
        }
        if (!(rawValue instanceof String)) {
            throw new SettingValidationException(key(),
                    "expected text, found " + rawValue.getClass().getSimpleName() + " (" + rawValue
                            + "). C4.1: validate throws, it does not coerce.");
        }
        String text = (String) rawValue;
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints > maxLength) {
            throw new SettingValidationException(key(),
                    "expected at most " + maxLength + " code points, found " + codePoints
                            + " (\"" + text + "\").");
        }
        if (allowedChars != null && !allowedChars.matcher(text).matches()) {
            throw new SettingValidationException(key(),
                    "\"" + text + "\" does not match the allowed pattern "
                            + allowedChars.pattern() + ". The pattern is matched against the whole "
                            + "string, not a substring of it.");
        }
    }

    /**
     * The loader's half of C4.4: truncate to {@link #maxLength()} code points.
     *
     * <p>Truncation happens on a code-point boundary, so a surrogate pair is never split
     * into a lone half and a replacement character on the next save.</p>
     *
     * @param rawText the text as read
     * @return the text, shortened to at most {@link #maxLength()} code points
     */
    public String truncateToMaxLength(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return rawText;
        }
        int codePoints = rawText.codePointCount(0, rawText.length());
        if (codePoints <= maxLength) {
            return rawText;
        }
        int endIndex = rawText.offsetByCodePoints(0, maxLength);
        return rawText.substring(0, endIndex);
    }

    /**
     * Compiles a whole-string pattern, naming the setting when the pattern is broken.
     *
     * @param key     the setting key, for the error message
     * @param regex   the pattern source
     * @return the compiled pattern
     * @throws XsozContractException if the pattern does not compile
     */
    public static Pattern compileAllowedChars(String key, String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new XsozContractException(
                    "Setting \"" + key + "\" was given a pattern that does not compile: " + regex, e);
        }
    }
}
