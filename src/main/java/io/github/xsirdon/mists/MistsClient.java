package io.github.xsirdon.mists;

import io.github.xsirdon.mists.client.MistState;
import io.github.xsirdon.mists.client.TierUnlockToast;
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
                client.execute(() -> {
                    int previousTier = MistState.currentTier;
                    MistState.apply(p.radius, p.animateFromRadius, p.centerX, p.centerZ, p.tierOrdinal);
                    if (p.tierOrdinal > previousTier && p.tierOrdinal >= 1) {
                        TierUnlockToast.fire(p.tierOrdinal);
                    }
                });
            });
    }
}
