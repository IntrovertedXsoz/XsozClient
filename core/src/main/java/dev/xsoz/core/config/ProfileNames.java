package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;

import java.util.Collection;
import java.util.Locale;

/**
 * The profile name and id rules (contracts.md C5.2, C5.6).
 *
 * <p>A profile name is the first thing a mod-list screenshot shows, and it becomes a
 * filename. The rules are therefore about the filesystem and about duplicate detection,
 * not about taste:</p>
 * <ul>
 *   <li>1..48 <strong>code points</strong> after trimming - code points, because a name
 *       with an emoji in it is one character to the player and two {@code char}s to
 *       Java, and a 48-{@code char} cap would refuse half the world's names.</li>
 *   <li>No control characters, and none of {@code / \ : * ? " < > |} - the last nine are
 *       the Windows-forbidden set plus the forward slash, and this client writes profile
 *       files on Windows.</li>
 *   <li>Uniqueness is on the <strong>trimmed, case-folded</strong> name. "Chosen One" and
 *       "chosen one" are the same name to a player scanning a list, and two profiles
 *       differing only by case is a profile picker nobody can use.</li>
 * </ul>
 *
 * <p>An id is separate and never changes: renaming a profile does not rename its id
 * (C5.2), because the id is what the files, the bindings and the {@code lastProfileId}
 * pointer are keyed by.</p>
 */
public final class ProfileNames {

    /** The grammar for a profile id, from C5.2. */
    public static final String ID_GRAMMAR = "^[a-z0-9][a-z0-9-]{0,62}$";

    /** The minimum name length in code points, after trimming. */
    public static final int MIN_NAME_CODE_POINTS = 1;

    /** The maximum name length in code points, after trimming. */
    public static final int MAX_NAME_CODE_POINTS = 48;

    /** The characters C5.2 forbids in a profile name, because a profile becomes a file. */
    public static final String FORBIDDEN_CHARACTERS = "/\\:*?\"<>|";

    private ProfileNames() {
        throw new AssertionError("ProfileNames is a rule holder and is not instantiable.");
    }

    /**
     * Trims and checks a name, without any uniqueness question.
     *
     * @param rawName the name as typed or read
     * @return the trimmed, valid name
     * @throws ProfileNameInvalidException if the name is empty once trimmed, too long, or
     *                                     carries a control or forbidden character
     */
    public static String requireValidName(String rawName) {
        if (rawName == null) {
            throw new ProfileNameInvalidException("null",
                    "a profile name must be a string. Null is not a name.");
        }
        String trimmed = rawName.trim();
        if (trimmed.isEmpty()) {
            throw new ProfileNameInvalidException(trimmed,
                    "a profile name must not be blank. A profile with no name is a row in the "
                            + "picker nobody can point at.");
        }
        int codePoints = codePointCount(trimmed);
        if (codePoints > MAX_NAME_CODE_POINTS) {
            throw new ProfileNameInvalidException(trimmed,
                    "a profile name is at most " + MAX_NAME_CODE_POINTS + " code points; this one "
                            + "is " + codePoints + ". The cap is in code points, not characters, "
                            + "so an emoji counts once.");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isISOControl(c)) {
                throw new ProfileNameInvalidException(trimmed,
                        "a profile name must not carry the control character U+"
                                + String.format("%04X", Integer.valueOf(c))
                                + ". A control character in a name is invisible in the picker and "
                                + "permanent in the file.");
            }
            if (FORBIDDEN_CHARACTERS.indexOf(c) >= 0) {
                throw new ProfileNameInvalidException(trimmed,
                        "a profile name must not carry '" + c + "'. A profile becomes a file, and "
                                + "these are the characters that cannot be one: " + FORBIDDEN_CHARACTERS
                                + ".");
            }
        }
        return trimmed;
    }

    /**
     * Checks a name against the names already in use.
     *
     * @param rawName        the name as typed or read
     * @param existingNames  the names already taken, in any case; the name of the profile
     *                       being renamed must NOT be in this collection, or a rename would
     *                       collide with itself
     * @return the trimmed, valid, unique name
     * @throws ProfileNameInvalidException if the name is invalid or already taken
     */
    public static String requireUniqueName(String rawName, Collection<String> existingNames) {
        String trimmed = requireValidName(rawName);
        String folded = fold(trimmed);
        for (String existing : existingNames) {
            if (existing != null && fold(existing).equals(folded)) {
                throw new ProfileNameInvalidException(trimmed,
                        "a profile already called \"" + existing + "\" exists. Duplicate detection "
                                + "is on the trimmed, case-folded name, because two profiles "
                                + "differing only by case is a picker nobody can use.");
            }
        }
        return trimmed;
    }

    /**
     * Whether two names collide.
     *
     * @param left  the first name, any case
     * @param right the second name, any case
     * @return whether they are the same name once trimmed and case-folded
     */
    public static boolean sameName(String left, String right) {
        return left != null && right != null && fold(left.trim()).equals(fold(right.trim()));
    }

    /**
     * @param rawId the id to check
     * @return whether it matches {@link #ID_GRAMMAR}
     */
    public static boolean isValidId(String rawId) {
        return rawId != null && rawId.trim().matches(ID_GRAMMAR);
    }

    /**
     * @param rawId the id to check
     * @return the trimmed id
     * @throws XsozContractException if the id does not match {@link #ID_GRAMMAR}
     */
    public static String requireValidId(String rawId) {
        if (!isValidId(rawId)) {
            throw new XsozContractException(
                    "\"" + rawId + "\" is not a legal profile id. C5.2 grammar: " + ID_GRAMMAR
                            + ". An id is lower-case, dash-separated, and at most 63 characters "
                            + "because it is a filename stem.");
        }
        return rawId.trim();
    }

    /**
     * Reduces a name to a filename-safe slug for use as an id stem.
     *
     * <p>Best-effort and lossy: the id is a machine handle, the name is the human one,
     * and a name that slugs to nothing still gets a valid id from the fallback.</p>
     *
     * @param name    the profile name
     * @param ordinal a fallback discriminator, used when the slug is empty
     * @return a slug matching {@link #ID_GRAMMAR} when appended with a 4-hex suffix
     */
    public static String slugify(String name, int ordinal) {
        String lower = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        StringBuilder slug = new StringBuilder(lower.length());
        boolean lastWasDash = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                slug.append(c);
                lastWasDash = false;
            } else if (!lastWasDash && slug.length() > 0) {
                slug.append('-');
                lastWasDash = true;
            }
        }
        String trimmed = slug.toString();
        while (trimmed.endsWith("-")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            trimmed = "profile";
        }
        if (trimmed.length() > 40) {
            trimmed = trimmed.substring(0, 40);
            while (trimmed.endsWith("-")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
        }
        if (trimmed.isEmpty() || !Character.isLetterOrDigit(trimmed.charAt(0))) {
            trimmed = "p" + trimmed;
        }
        return trimmed + "-" + String.format(Locale.ROOT, "%04x", Integer.valueOf(ordinal & 0xFFFF));
    }

    /**
     * @param text the string to measure
     * @return its length in code points
     */
    public static int codePointCount(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String fold(String text) {
        return text.trim().toLowerCase(Locale.ROOT);
    }
}
