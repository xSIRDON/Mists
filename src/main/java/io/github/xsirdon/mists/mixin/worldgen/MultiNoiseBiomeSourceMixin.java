package io.github.xsirdon.mists.mixin.worldgen;

import io.github.xsirdon.mists.worldgen.MistsBiomes;
import io.github.xsirdon.mists.worldgen.density.BubbleProfile;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v0.19 hook: inside the v0.18 water-world bubble, replace whatever vanilla
 * ocean biome the multi-noise sampler would have selected with our custom
 * {@code mists:mists_open_ocean} biome. Outside the bubble, vanilla biome
 * selection runs unmodified.
 *
 * <p>Target: {@link MultiNoiseBiomeSource#getBiome(int, int, int,
 * MultiNoiseUtil.MultiNoiseSampler)}. This is the public entry point both the
 * chunk-gen biome lookup and the worldgen feature placement code use, and it
 * runs after {@link io.github.xsirdon.mists.mixin.worldgen.NoiseConfigMixin}'s
 * continents wrap. The {@code x}/{@code z} arguments are in biome coordinates
 * (block index >> 2), so they are multiplied by 4 before being compared to the
 * block-space {@link BubbleProfile#BUBBLE_RADIUS}.
 *
 * <p>The replacement biome is resolved once from the running server's dynamic
 * registry by {@link MistsBiomes#resolveFor}; if resolution failed (datapack
 * JSON missing, registry-load ordering issue), the holder returns {@code null}
 * and this mixin falls through to vanilla — no crash, just no aesthetic swap.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {

    @Inject(
        method = "getBiome(IIILnet/minecraft/world/biome/source/util/MultiNoiseUtil$MultiNoiseSampler;)Lnet/minecraft/registry/entry/RegistryEntry;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void mists$swapOceanInsideBubble(int x, int y, int z,
                                             MultiNoiseUtil.MultiNoiseSampler sampler,
                                             CallbackInfoReturnable<RegistryEntry<Biome>> cir) {
        RegistryEntry<Biome> mistsOcean = MistsBiomes.openOcean();
        if (mistsOcean == null) return;

        // Biome-source coords are block >> 2, so each unit is 4 blocks.
        double bx = x * 4.0;
        double bz = z * 4.0;
        double dist = Math.sqrt(bx * bx + bz * bz);
        if (dist <= BubbleProfile.BUBBLE_RADIUS) {
            cir.setReturnValue(mistsOcean);
        }
    }
}
