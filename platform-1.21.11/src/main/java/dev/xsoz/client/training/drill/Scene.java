package dev.xsoz.client.training.drill;

import dev.xsoz.client.training.CombatMath;
import dev.xsoz.client.training.session.Session;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.explosion.ExplosionImpl;

/** Server-thread helpers shared by the arena drills: dummies, particles, damage evaluation. */
public final class Scene {
    private Scene() { }

    /** A player-shaped training dummy (vanilla Mannequin), invulnerable, facing yaw. */
    public static MannequinEntity spawnDummy(Session s, Vec3d at, float yaw) {
        MannequinEntity m = EntityType.MANNEQUIN.create(s.world, SpawnReason.COMMAND);
        if (m == null) return null;
        m.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0f);
        m.setHeadYaw(yaw);
        m.setBodyYaw(yaw);
        m.setInvulnerable(true);
        m.setCustomName(Text.literal("Dummy"));
        m.setCustomNameVisible(true);
        s.world.spawnEntity(m);
        s.track(m);
        return m;
    }

    /** Keeps a static dummy exactly where it was put (players can push mannequins). */
    public static void pin(Entity e, Vec3d at, float yaw) {
        if (e == null || e.isRemoved()) return;
        if (e.squaredDistanceTo(at) > 0.0004) e.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0f);
        e.setVelocity(Vec3d.ZERO);
        e.setYaw(yaw);
        e.setHeadYaw(yaw);
        e.setBodyYaw(yaw);
    }

    public static void particles(ServerWorld w, ParticleEffect type, Vec3d at, int count, double spread) {
        w.spawnParticles(type, at.x, at.y, at.z, count, spread, spread, spread, 0.0);
    }

    /** Centre of a crystal standing on block b (the explosion centre). */
    public static Vec3d crystalCenter(BlockPos b) { return new Vec3d(b.getX() + 0.5, b.getY() + 1.0, b.getZ() + 0.5); }

    /** The box a crystal on block b occupies (EndCrystalItem's own entity check). */
    public static Box crystalBox(BlockPos b) { return new Box(b.getX(), b.getY() + 1, b.getZ(), b.getX() + 1, b.getY() + 3, b.getZ() + 1); }

    /**
     * The protection a fighter wears: armour points, toughness, explosion EPF and the world
     * difficulty. Training dummies "wear" the player's own kit (a mirror match), so every damage
     * number compares like with like.
     */
    public record Armour(float armor, float toughness, float blastEpf, int difficulty) {
        public static final Armour CRYSTAL_KIT = new Armour(20f, 12f, 20f, 2);
    }

    /** Reads the real armour of e right now (server thread). */
    public static Armour armourOf(ServerWorld w, net.minecraft.entity.LivingEntity e) {
        if (e == null) return Armour.CRYSTAL_KIT;
        // Read from the worn items: the entity's attributes only catch up on its next tick, and
        // drills score scenes right after handing out the kit.
        float[] fromItems = new float[2];
        for (net.minecraft.entity.EquipmentSlot slot : new net.minecraft.entity.EquipmentSlot[] {
                net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
                net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET}) {
            e.getEquippedStack(slot).applyAttributeModifiers(slot, (attr, mod) -> {
                if (mod.operation() != net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADD_VALUE) return;
                if (attr.equals(net.minecraft.entity.attribute.EntityAttributes.ARMOR)) fromItems[0] += (float) mod.value();
                if (attr.equals(net.minecraft.entity.attribute.EntityAttributes.ARMOR_TOUGHNESS)) fromItems[1] += (float) mod.value();
            });
        }
        float armor = Math.max(e.getArmor(), fromItems[0]);
        float tough = Math.max((float) e.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.ARMOR_TOUGHNESS), fromItems[1]);
        float epf = net.minecraft.enchantment.EnchantmentHelper.getProtectionAmount(w, e, w.getDamageSources().explosion(null, null));
        return new Armour(armor, tough, epf, w.getDifficulty().getId());
    }

    /**
     * Exact damage an explosion of power at center deals to target, wearing armour a: the game's
     * own exposure raycast (blocks between block rays, whatever they are made of - stone shields
     * this blast too, it just breaks afterwards), the vanilla falloff, difficulty, armour and
     * protection. Absorption hearts are not subtracted.
     */
    public static float damage(Vec3d center, float power, Entity target, Armour a) {
        if (target == null) return 0f;
        float exposure = ExplosionImpl.calculateReceivedDamage(center, target);
        float raw = CombatMath.rawExplosion(Math.sqrt(target.squaredDistanceTo(center)), power, exposure);
        return CombatMath.effective(raw, a.difficulty(), a.armor(), a.toughness(), a.blastEpf());
    }

    /**
     * Vanilla's exposure (ExplosionImpl.calculateReceivedDamage) for any box: the share of a grid
     * of points on the box that see the explosion centre with nothing solid in between.
     */
    public static float exposure(net.minecraft.world.World w, Vec3d center, Box box, Entity ctx) {
        double d = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double e = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double f = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double g = (1.0 - Math.floor(1.0 / d) * d) / 2.0;
        double h = (1.0 - Math.floor(1.0 / f) * f) / 2.0;
        if (d < 0 || e < 0 || f < 0) return 0f;
        int hit = 0;
        int all = 0;
        for (double k = 0; k <= 1; k += d) {
            for (double l = 0; l <= 1; l += e) {
                for (double m = 0; m <= 1; m += f) {
                    Vec3d v = new Vec3d(MathHelper.lerp(k, box.minX, box.maxX) + g, MathHelper.lerp(l, box.minY, box.maxY),
                            MathHelper.lerp(m, box.minZ, box.maxZ) + h);
                    var ctxRay = new net.minecraft.world.RaycastContext(v, center, net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                            net.minecraft.world.RaycastContext.FluidHandling.NONE, ctx);
                    if (w.raycast(ctxRay).getType() == net.minecraft.util.hit.HitResult.Type.MISS) hit++;
                    all++;
                }
            }
        }
        return all == 0 ? 0f : (float) hit / all;
    }

    /** Damage to a player-sized body standing with its feet at feet (no entity needs to be there). */
    public static float damageAt(ServerWorld w, Vec3d center, float power, Vec3d feet, Entity ctx, Armour a) {
        Box box = new Box(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
        float exp = exposure(w, center, box, ctx);
        float raw = CombatMath.rawExplosion(feet.distanceTo(center), power, exp);
        return CombatMath.effective(raw, a.difficulty(), a.armor(), a.toughness(), a.blastEpf());
    }

    /**
     * Blocks a crystal-sized blast would most likely destroy around center (blast resistance under
     * 10 and within 3.5 blocks): stone, dirt, glowstone... but never obsidian, crying obsidian,
     * anchors, bedrock. Used to show what cover is "one-use".
     */
    public static java.util.List<BlockPos> weakBlocksNear(ServerWorld w, Vec3d center) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        BlockPos c = BlockPos.ofFloored(center);
        for (BlockPos p : BlockPos.iterate(c.add(-4, -4, -4), c.add(4, 4, 4))) {
            var st = w.getBlockState(p);
            if (st.isAir() || st.getBlock().getBlastResistance() >= 10f) continue;
            if (Vec3d.ofCenter(p).distanceTo(center) > 3.5) continue;
            out.add(p.toImmutable());
        }
        return out;
    }

    /** Damage the NEXT blast at center would do once this one has broken the weak cover. */
    public static float damageAfterWeakCoverBreaks(ServerWorld w, Vec3d center, float power, Entity target, Armour a) {
        java.util.List<BlockPos> weak = weakBlocksNear(w, center);
        java.util.List<net.minecraft.block.BlockState> saved = new java.util.ArrayList<>();
        for (BlockPos p : weak) {
            saved.add(w.getBlockState(p));
            w.setBlockState(p, net.minecraft.block.Blocks.AIR.getDefaultState(), 0);
        }
        try {
            return damage(center, power, target, a);
        } finally {
            for (int i = 0; i < weak.size(); i++) w.setBlockState(weak.get(i), saved.get(i), 0);
        }
    }

    /** A spot a player can stand on: solid floor, two air blocks for the body. */
    public static boolean standable(ServerWorld w, BlockPos feet) {
        var floor = w.getBlockState(feet.down());
        return !floor.isAir() && floor.isSolidBlock(w, feet.down())
                && w.getBlockState(feet).isAir() && w.getBlockState(feet.up()).isAir();
    }

    /** Distance from a point to the nearest point of a block (vanilla's reach test). */
    public static double reachTo(Vec3d eye, BlockPos b) {
        return Math.sqrt(new Box(b).squaredMagnitude(eye));
    }

    /** Effective damage (vs the standard kit) an explosion at center would deal to e, with real ray exposure. */
    public static float effective(Vec3d center, float power, Entity e) {
        if (e == null) return 0f;
        float exposure = ExplosionImpl.calculateReceivedDamage(center, e);
        float raw = CombatMath.rawExplosion(Math.sqrt(e.squaredDistanceTo(center)), power, exposure);
        return CombatMath.effectiveVsCrystalKit(raw);
    }

    public static float yawTowards(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** Teleports position only - the player keeps looking where they were looking. */
    public static void teleportKeepLook(ServerPlayerEntity p, Vec3d at) {
        p.teleport(p.getEntityWorld(), at.x, at.y, at.z,
                java.util.EnumSet.of(net.minecraft.network.packet.s2c.play.PositionFlag.X_ROT, net.minecraft.network.packet.s2c.play.PositionFlag.Y_ROT),
                0f, 0f, true);
        p.setVelocity(Vec3d.ZERO);
        p.fallDistance = 0;
    }

    public static void teleport(ServerPlayerEntity p, Vec3d at, float yaw, float pitch) {
        p.teleport(p.getEntityWorld(), at.x, at.y, at.z, java.util.Set.of(), yaw, pitch, true);
        p.refreshPositionAndAngles(at.x, at.y, at.z, yaw, pitch);
        p.setVelocity(Vec3d.ZERO);
    }
}
