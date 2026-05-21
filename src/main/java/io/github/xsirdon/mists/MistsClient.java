package io.github.xsirdon.mists;

import io.github.xsirdon.mists.client.MistState;
import io.github.xsirdon.mists.network.MistRadiusPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class MistsClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        Mists.LOG.info("Mists initialising (client)");

        ClientPlayNetworking.registerGlobalReceiver(
            MistsConstants.MIST_RADIUS_PACKET,
            (client, handler, buf, sender) -> {
                MistRadiusPayload p = MistRadiusPayload.decode(buf);
                client.execute(() -> MistState.apply(p.radius, p.animateFromRadius));
            });
    }
}
