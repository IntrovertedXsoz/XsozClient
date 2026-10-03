package dev.xsoz.client.trainer.crystal;

/**
 * A snapshot taken every {@link FightRecord#SAMPLE_EVERY} ticks. Everything about "you" is your
 * own state. The opponent fields are what was on your screen: distance (-1 when they were not
 * loaded) and whether they were in view. Their health, armour and totem count are never recorded.
 */
public record FightSample(
        long tick,
        float hp,
        float absorption,
        boolean offhandTotem,
        int totems,
        int crystals,
        float armor,
        float distance,
        boolean opponentVisible,
        float heightDiff) {
}
