package dev.xsoz.client.training;

/**
 * Vanilla 1.21.11 explosion and armour maths, as pure functions (unit tested), used to score
 * placements in the Fight IQ drills.
 *
 * <ul>
 *   <li>Explosion (ExplosionImpl.damageEntities): {@code q = dist / (2*power)}; if q &lt;= 1,
 *   {@code impact = (1 - q) * exposure} and {@code damage = (impact^2 + impact) / 2 * 7 * 2*power + 1}.
 *   {@code dist} is from the explosion centre to the entity's feet; exposure (0..1) is the share
 *   of rays reaching the entity's box, computed in-world by ExplosionImpl.calculateReceivedDamage.</li>
 *   <li>Armour (DamageUtil.getDamageLeft): {@code f = 2 + toughness/4},
 *   {@code g = clamp(armor - damage/f, armor*0.2, 20)}, {@code damage * (1 - g/25)}.</li>
 *   <li>Enchantment protection (DamageUtil.getInflictedDamage): {@code damage * (1 - clamp(epf,0,20)/25)}.</li>
 * </ul>
 * "Effective" numbers assume the standard crystal PvP kit on both sides: full netherite
 * (armour 20, toughness 12) with Blast Protection IV on every piece (EPF 32, capped at 20).
 */
public final class CombatMath {
    public static final float CRYSTAL_POWER = 6f;
    public static final float ANCHOR_POWER = 5f;

    private CombatMath() { }

    public static float rawExplosion(double distanceToFeet, float power, double exposure) {
        double range = power * 2.0;
        double q = distanceToFeet / range;
        if (q > 1.0 || exposure <= 0) return 0f;
        double impact = (1.0 - q) * exposure;
        return (float) ((impact * impact + impact) / 2.0 * 7.0 * range + 1.0);
    }

    public static float afterArmor(float damage, float armor, float toughness) {
        float f = 2.0f + toughness / 4.0f;
        float g = Math.max(armor * 0.2f, Math.min(armor - damage / f, 20.0f));
        return damage * (1.0f - g / 25.0f);
    }

    public static float afterProtection(float damage, float epf) {
        float p = Math.max(0f, Math.min(epf, 20f));
        return damage * (1.0f - p / 25.0f);
    }

    /** Damage against full netherite with Blast Protection IV on every piece. */
    public static float effectiveVsCrystalKit(float raw) {
        if (raw <= 0) return 0f;
        return afterProtection(afterArmor(raw, 20f, 12f), 32f);
    }

    /**
     * Player-only difficulty scaling of explosion (and mob) damage, PlayerEntity.damage:
     * peaceful 0, easy {@code min(d/2+1, d)}, normal d, hard {@code d*1.5}. difficulty: 0..3.
     */
    public static float scaleForDifficulty(float damage, int difficulty) {
        return switch (difficulty) {
            case 0 -> 0f;
            case 1 -> Math.min(damage / 2f + 1f, damage);
            case 3 -> damage * 1.5f;
            default -> damage;
        };
    }

    /** Full chain for a player: difficulty, armour, then enchantment protection. */
    public static float effective(float raw, int difficulty, float armor, float toughness, float epf) {
        if (raw <= 0) return 0f;
        return afterProtection(afterArmor(scaleForDifficulty(raw, difficulty), armor, toughness), epf);
    }

    /**
     * How an anchor's self-damage reads to the player (effective HP after armour):
     * 0 = just right (under 3 HP), 1 = slightly too close (3-6), 2 = too close (over 6).
     */
    public static int anchorSelfRating(float selfDamage) {
        if (selfDamage > 6f) return 2;
        if (selfDamage >= 3f) return 1;
        return 0;
    }

    public static final String[] ANCHOR_RATING = {"Just right", "Slightly too close", "Too close"};

    /**
     * Whether a raised shield blocks an explosion at (srcX, srcZ) for an entity at (x, z) facing
     * yaw degrees: blocked when the source is in the front half (vanilla's dot-product test).
     */
    public static boolean shieldBlocks(double x, double z, float yawDeg, double srcX, double srcZ) {
        double lookX = -Math.sin(Math.toRadians(yawDeg));
        double lookZ = Math.cos(Math.toRadians(yawDeg));
        double rx = x - srcX;
        double rz = z - srcZ;
        double len = Math.sqrt(rx * rx + rz * rz);
        if (len < 1e-6) return false;
        return (rx / len) * lookX + (rz / len) * lookZ < 0;
    }
}
