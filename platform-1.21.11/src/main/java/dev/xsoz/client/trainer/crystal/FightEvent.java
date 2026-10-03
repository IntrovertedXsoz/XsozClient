package dev.xsoz.client.trainer.crystal;

/**
 * One thing that happened in a recorded fight. {@code a} and {@code b} carry event-specific
 * numbers (an entity id, ticks, HP lost, a damage-type code) - see {@link Type}.
 */
public record FightEvent(long tick, Type type, double a, double b) {

    public enum Type {
        /** a = crystal entity id */
        SELF_CRYSTAL_PLACE,
        /** a = crystal entity id */
        SELF_CRYSTAL_BREAK,
        OPP_CRYSTAL_PLACE,
        SELF_ANCHOR_PLACE,
        SELF_ANCHOR_CHARGE,
        SELF_ANCHOR_BLOW,
        /** a = damage code (see DamageCode) */
        DAMAGE_DEALT,
        /** a = damage code, b = HP lost (0 when absorbed by a pop) */
        DAMAGE_TAKEN,
        /** a = HP lost to your own explosion */
        SELF_DAMAGE,
        SELF_POP,
        OPP_POP,
        SELF_PEARL,
        OPP_PEARL,
        /** a = distance from you to where the pearl landed */
        OPP_PEARL_LAND,
        SELF_XP,
        OPP_XP,
        OPP_SHIELD_UP,
        OPP_SHIELD_DOWN,
        OPP_EAT_START,
        SELF_EAT_START,
        /** b = totems left in inventory when the offhand emptied */
        OFFHAND_EMPTY,
        OFFHAND_TOTEM,
        MELEE_HIT,
        SELF_DEATH,
        OPP_DEATH,
        /** a = cue ordinal */
        CUE_SHOWN,
        /** A left-click on empty air (it locks your attacks for a moment). */
        MISS_CLICK
    }

    /** Damage source codes stored in DAMAGE_DEALT / DAMAGE_TAKEN. */
    public static final int DMG_MELEE = 0;
    public static final int DMG_CRYSTAL = 1;
    public static final int DMG_ANCHOR = 2;
    public static final int DMG_OTHER = 3;
}
