package dev.xsoz.client.modules.visuals;

import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.setting.NumberSetting;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Hand;

/** Moves and scales your held items (lower totem, smaller sword, wider crystals...). */
public final class ViewModel extends Module {
    public static ViewModel INSTANCE;
    private final NumberSetting mainX = add(new NumberSetting("Main X", "Main hand left/right", 0, -1, 1, 0.05));
    private final NumberSetting mainY = add(new NumberSetting("Main Y", "Main hand up/down", 0, -1, 1, 0.05));
    private final NumberSetting mainZ = add(new NumberSetting("Main Z", "Main hand forward/back", 0, -1, 1, 0.05));
    private final NumberSetting mainScale = add(new NumberSetting("Main scale", "Main hand size", 1, 0.3, 1.5, 0.05, "x"));
    private final NumberSetting offX = add(new NumberSetting("Offhand X", "Offhand left/right", 0, -1, 1, 0.05));
    private final NumberSetting offY = add(new NumberSetting("Offhand Y", "Offhand up/down (lower totem)", -0.1, -1, 1, 0.05));
    private final NumberSetting offZ = add(new NumberSetting("Offhand Z", "Offhand forward/back", 0, -1, 1, 0.05));
    private final NumberSetting offScale = add(new NumberSetting("Offhand scale", "Offhand size", 1, 0.3, 1.5, 0.05, "x"));

    public ViewModel() {
        super("View Model", "Position and size of your held items", Category.VISUALS, false);
        INSTANCE = this;
    }

    public void apply(Hand hand, MatrixStack m) {
        if (!isEnabled()) return;
        boolean main = hand == Hand.MAIN_HAND;
        m.translate(main ? mainX.value() : offX.value(), main ? mainY.value() : offY.value(), main ? mainZ.value() : offZ.value());
        float s = main ? mainScale.floatValue() : offScale.floatValue();
        m.scale(s, s, s);
    }
}
