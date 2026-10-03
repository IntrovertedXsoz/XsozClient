package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

/**
 * The closed setting kinds (contracts.md C4.1).
 *
 * <p>Closed enum. {@code :core} builds {@link BooleanSetting} and {@link DoubleSetting} only;
 * the remaining kinds are named here because the enum is frozen and another agent's schema
 * code refers to them by constant.</p>
 */
public enum SettingKind {

    /** A true/false flag. {@link BooleanSetting} carries it. */
    BOOLEAN,

    /** A whole number in a declared range. */
    INT,

    /** A real number in a declared range with a declared step and unit. {@link DoubleSetting} carries it. */
    DOUBLE,

    /** One constant of an enum, serialised by name and never by ordinal. */
    ENUM,

    /** Text with a declared maximum length and an allowed-characters pattern. */
    STRING,

    /** A packed {@code 0xAARRGGBB} colour. */
    COLOR,

    /** A keybind, validated by {@code BindPolicy} on every load. */
    KEYBIND,

    /** An ordered list with a declared maximum size. */
    LIST
}
