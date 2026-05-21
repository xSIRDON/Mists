package io.github.xsirdon.mists;

import net.fabricmc.api.ClientModInitializer;

public final class MistsClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        Mists.LOG.info("Mists initialising (client)");
        // Wired up in later tasks:
        //   - client/MistRenderer.register()
        //   - client/MistSounds.register()
        //   - network packet receiver registration
    }
}
