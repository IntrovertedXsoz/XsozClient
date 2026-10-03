package dev.xsoz.client.trainer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * The opponent-presence gate. A cue may only react to what the player could see on their own
 * screen: inside a narrowed cone around the crosshair, within range, and not behind blocks.
 * Occluded means absent - the trainer would rather miss a line than read through a wall.
 */
public final class Visibility {
    public static final double HALF_HORIZONTAL_DEG = 35.0;
    public static final double HALF_VERTICAL_DEG = 20.0;
    public static final double MAX_DISTANCE = 24.0;
    public static final double MAX_VERTICAL_DELTA = 8.0;

    private Visibility() { }

    public static boolean opponentVisible(MinecraftClient mc, Entity target) {
        return visible(mc, target, HALF_HORIZONTAL_DEG, HALF_VERTICAL_DEG, MAX_DISTANCE, MAX_VERTICAL_DELTA);
    }

    /** Projectiles get the full on-screen cone: a pearl arcs high and you track it with your eyes. */
    public static boolean projectileVisible(MinecraftClient mc, Entity projectile) {
        double fov = mc.options.getFov().getValue();
        return visible(mc, projectile, fov * 0.8, fov * 0.5, 48.0, 32.0);
    }

    public static boolean visible(MinecraftClient mc, Entity target, double halfH, double halfV, double maxDist, double maxDy) {
        if (mc.player == null || mc.world == null || target == null || target == mc.player) return false;
        Vec3d eye = mc.player.getEyePos();
        Vec3d targetPos = new Vec3d(target.getX(), target.getY() + target.getStandingEyeHeight() * 0.6, target.getZ());
        Vec3d d = targetPos.subtract(eye);
        double dist = d.length();
        if (dist > maxDist || dist < 1e-4) return false;
        if (Math.abs(target.getY() - mc.player.getY()) > maxDy) return false;

        double yawTo = Math.toDegrees(Math.atan2(-d.x, d.z));
        double dyaw = Math.abs(MathHelper.wrapDegrees(yawTo - mc.player.getYaw()));
        if (dyaw > halfH) return false;
        double pitchTo = Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        if (Math.abs(pitchTo - mc.player.getPitch()) > halfV) return false;

        // Two sample points (body and head): a player standing in a hole is still visible by head.
        Vec3d head = new Vec3d(target.getX(), target.getY() + target.getStandingEyeHeight(), target.getZ());
        return clear(mc, eye, targetPos) || clear(mc, eye, head);
    }

    private static boolean clear(MinecraftClient mc, Vec3d from, Vec3d to) {
        var hit = mc.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player));
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }
}
