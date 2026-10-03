package dev.xsoz.client.gametest;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.world.ServerWorld;

/** Fabric's FakePlayer ignores all damage; drills that pop totems need one that can be hurt. */
final class VulnerableFakePlayer extends FakePlayer {
    VulnerableFakePlayer(ServerWorld world, GameProfile profile) {
        super(world, profile);
    }

    @Override
    public boolean isInvulnerableTo(ServerWorld world, DamageSource source) {
        return false;
    }
}
