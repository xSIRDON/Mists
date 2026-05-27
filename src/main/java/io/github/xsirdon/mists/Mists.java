package io.github.xsirdon.mists;

import io.github.xsirdon.mists.block.MistsBlocks;
import io.github.xsirdon.mists.boundary.BoundarySystem;
import io.github.xsirdon.mists.boundary.PearlClamp;
import io.github.xsirdon.mists.boundary.PlayerJoinClamp;
import io.github.xsirdon.mists.boundary.VehicleClamp;
import io.github.xsirdon.mists.item.MistsItems;
import io.github.xsirdon.mists.worldgen.IslandPlacer;
import io.github.xsirdon.mists.worldgen.MistsBiomes;
import io.github.xsirdon.mists.worldgen.feature.MistsFeatures;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Mists implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger(MistsConstants.MOD_ID);

    @Override public void onInitialize() {
        LOG.info("Mists initialising (server/common)");

        // v0.21: content registration. Order matters only for the FEATURE
        // registry — feature types must exist before any datapack JSON that
        // references them is parsed, which happens during server start, so
        // we register here on common init.
        MistsBlocks.register();
        MistsItems.register();
        MistsFeatures.register();

        BoundarySystem.register();
        PearlClamp.register();
        VehicleClamp.register();
        PlayerJoinClamp.register();
        IslandPlacer.register();

        // v0.19: dynamic registries (including datapack biomes) aren't populated
        // until the server is starting. Cache our biome entry once they are,
        // and drop it on stop so a fresh integrated-server session re-resolves
        // against the new registry manager.
        ServerLifecycleEvents.SERVER_STARTING.register(MistsBiomes::resolveFor);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> MistsBiomes.clear());
    }
}
