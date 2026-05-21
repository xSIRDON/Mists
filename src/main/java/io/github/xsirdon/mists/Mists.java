package io.github.xsirdon.mists;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Mists implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger(MistsConstants.MOD_ID);

    @Override public void onInitialize() {
        LOG.info("Mists initialising (server/common)");
        // Subsystem registrations are added in later tasks:
        //   - worldgen/IslandPlacer.register()
        //   - boundary/BoundarySystem.register()
        //   - boundary/PearlClamp.register()
        //   - network packet codec registration
    }
}
