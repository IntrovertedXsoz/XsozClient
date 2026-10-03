package dev.xsoz.client.training.drill;

import dev.xsoz.client.training.DrillDef;

/** Best Crystal, but the dummy is in a crying-obsidian hole: the spot has to be built. */
public final class HoleBreakerDrill extends CrystalSpotDrill {
    public HoleBreakerDrill(Mode mode, double p, boolean hints) {
        super(DrillDef.FACE_PLACE, mode, p, hints, true);
    }
}
