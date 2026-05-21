package io.github.xsirdon.mists;

import io.github.xsirdon.mists.boundary.BoundarySystem;
import io.github.xsirdon.mists.boundary.PearlClamp;
import io.github.xsirdon.mists.boundary.PlayerJoinClamp;
import io.github.xsirdon.mists.boundary.VehicleClamp;
import io.github.xsirdon.mists.worldgen.IslandPlacer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Mists implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger(MistsConstants.MOD_ID);

    @Override public void onInitialize() {
        LOG.info("Mists initialising (server/common)");
        BoundarySystem.register();
        PearlClamp.register();
        VehicleClamp.register();
        PlayerJoinClamp.register();
        IslandPlacer.register();
    }
}
