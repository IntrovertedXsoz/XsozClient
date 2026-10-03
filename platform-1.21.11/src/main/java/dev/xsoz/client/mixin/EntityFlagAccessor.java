package dev.xsoz.client.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the sparring bot show the elytra gliding pose (entity flag 7). */
@Mixin(Entity.class)
public interface EntityFlagAccessor {
    @Invoker("setFlag")
    void xsoz$setFlag(int index, boolean value);
}
