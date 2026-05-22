package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import io.github.xsirdon.mists.MistsConstants;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;

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
        openOceanEntry = server.getRegistryManager()
            .get(RegistryKeys.BIOME)
            .getEntry(OPEN_OCEAN)
            .map(ref -> (RegistryEntry<Biome>) ref)
            .orElse(null);
        if (openOceanEntry == null) {
            Mists.LOG.warn("Mists: {} biome not registered — datapack JSON missing or invalid?",
                OPEN_OCEAN_ID);
        } else {
            Mists.LOG.info("Mists: {} biome resolved", OPEN_OCEAN_ID);
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
