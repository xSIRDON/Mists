package io.github.xsirdon.mists.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.gen.feature.FeatureConfig;

/**
 * Configuration for the {@link TallKelpFeature}. Pulled from datapack JSON via
 * {@link #CODEC}.
 *
 * @param minHeight     minimum number of blocks this kelp stalk will be tall
 *                      (including the top KELP tip block). Must be &ge; 1.
 * @param maxHeight     maximum number of blocks tall. Picked uniformly in
 *                      [minHeight, maxHeight].
 * @param spreadChance  unused by the core placement (kept for future use as a
 *                      neighbour-spread probability); a value in [0, 1].
 */
public record TallKelpFeatureConfig(int minHeight, int maxHeight, float spreadChance)
        implements FeatureConfig {

    public static final Codec<TallKelpFeatureConfig> CODEC =
        RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(1, 64).fieldOf("min_height").forGetter(TallKelpFeatureConfig::minHeight),
            Codec.intRange(1, 64).fieldOf("max_height").forGetter(TallKelpFeatureConfig::maxHeight),
            Codec.floatRange(0.0f, 1.0f).fieldOf("spread_chance").forGetter(TallKelpFeatureConfig::spreadChance)
        ).apply(instance, TallKelpFeatureConfig::new));
}
