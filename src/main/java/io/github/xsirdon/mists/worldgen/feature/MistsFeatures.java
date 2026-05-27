package io.github.xsirdon.mists.worldgen.feature;

import io.github.xsirdon.mists.MistsConstants;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.feature.Feature;

/**
 * Central registration for {@link Feature}s defined by Mists. Called once from
 * {@code Mists.onInitialize()}.
 */
public final class MistsFeatures {

    public static final Feature<TallKelpFeatureConfig> TALL_KELP =
        new TallKelpFeature(TallKelpFeatureConfig.CODEC);

    private MistsFeatures() {}

    public static void register() {
        Registry.register(Registries.FEATURE,
            new Identifier(MistsConstants.MOD_ID, "tall_kelp"), TALL_KELP);
    }
}
