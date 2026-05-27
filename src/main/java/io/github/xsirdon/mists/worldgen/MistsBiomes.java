package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import io.github.xsirdon.mists.MistsConstants;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;

import java.util.Optional;

/**
 * Holder for biome registry entries owned by Mists. Populated once the server
 * has finished loading dynamic registries (datapack-defined biomes are not
 * available until SERVER_STARTING fires), then read by
 * {@code MultiNoiseBiomeSourceMixin} to swap vanilla ocean biomes inside the
 * v0.18 water-world bubble for our atmospheric variant.
 *
 * <p>The biome itself is shipped as a datapack JSON in the mod jar at
 * {@code data/mists/worldgen/biome/mists_open_ocean.json}; this class only
 * caches its {@link RegistryEntry} so the mixin's hot-path doesn't repeatedly
 * walk the registry.
 */
public final class MistsBiomes {

    public static final Identifier OPEN_OCEAN_ID =
        new Identifier(MistsConstants.MOD_ID, "mists_open_ocean");

    public static final RegistryKey<Biome> OPEN_OCEAN =
        RegistryKey.of(RegistryKeys.BIOME, OPEN_OCEAN_ID);

    /** v0.21: datapack tag listing the biome(s) that should appear inside the
     *  water-world bubble. For v0.21 it contains exactly {@link #OPEN_OCEAN_ID};
     *  modpacks can replace=false / add entries to swap or weight biomes. */
    public static final TagKey<Biome> BUBBLE_BIOMES = TagKey.of(RegistryKeys.BIOME,
        new Identifier(MistsConstants.MOD_ID, "bubble_biomes"));

    /** Resolved RegistryEntry, or {@code null} until SERVER_STARTING fires. */
    private static volatile RegistryEntry<Biome> openOceanEntry;

    private MistsBiomes() {}

    /**
     * Look up our biome in the running server's dynamic registry and cache it.
     * Logs a warning and leaves the entry unset if the registration is missing,
     * which makes the mixin fall through to vanilla behaviour gracefully rather
     * than crashing the world.
     */
    public static void resolveFor(MinecraftServer server) {
        Registry<Biome> biomeRegistry = server.getRegistryManager().get(RegistryKeys.BIOME);

        // v0.21: prefer the mists:bubble_biomes tag so modpacks can override the
        // bubble's biome roster without code changes. Fall back to the direct
        // registry lookup if the tag is empty / not loaded.
        Optional<RegistryEntryList.Named<Biome>> tagList = biomeRegistry.getEntryList(BUBBLE_BIOMES);
        RegistryEntry<Biome> resolved = null;
        if (tagList.isPresent() && tagList.get().size() > 0) {
            // First entry in the tag wins. The size() > 0 check protects against
            // an empty tag declaration.
            resolved = tagList.get().get(0);
            Mists.LOG.info("Mists: bubble biome resolved via tag {} → {} (tag entries: {})",
                BUBBLE_BIOMES.id(), resolved.getKey().map(k -> k.getValue().toString()).orElse("<unkeyed>"),
                tagList.get().size());
        } else {
            resolved = biomeRegistry
                .getEntry(OPEN_OCEAN)
                .map(ref -> (RegistryEntry<Biome>) ref)
                .orElse(null);
            if (resolved != null) {
                Mists.LOG.info("Mists: bubble biome resolved via direct lookup → {}", OPEN_OCEAN_ID);
            }
        }

        openOceanEntry = resolved;
        if (openOceanEntry == null) {
            Mists.LOG.warn("Mists: {} biome not registered — datapack JSON missing or invalid?",
                OPEN_OCEAN_ID);
        }
    }

    /** Drop the cached entry on server stop so a subsequent integrated-server
     *  session resolves fresh against the new registry manager. */
    public static void clear() {
        openOceanEntry = null;
    }

    /** {@code null} before the server has fully started or if the biome is missing. */
    public static RegistryEntry<Biome> openOcean() {
        return openOceanEntry;
    }
}
