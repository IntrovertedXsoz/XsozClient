package dev.xsoz.client.mixin;

import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The press counter handleInputEvents drains with wasPressed(). */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor("timesPressed")
    int xsoz$getTimesPressed();

    @Accessor("timesPressed")
    void xsoz$setTimesPressed(int value);
}
