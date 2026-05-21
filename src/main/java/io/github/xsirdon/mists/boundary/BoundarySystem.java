package io.github.xsirdon.mists.boundary;

import io.github.xsirdon.mists.MistsConstants;
import io.github.xsirdon.mists.network.MistRadiusPayload;
import io.github.xsirdon.mists.progression.LevelZBridge;
import io.github.xsirdon.mists.progression.TierTable;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BoundarySystem {

    /** UUID → last-known radius. Used to detect transitions and animate the client. */
    private static final Map<UUID, Double> lastRadius = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                tickPlayer(player);
            }
        });
    }

    private static void tickPlayer(ServerPlayerEntity player) {
        int level = LevelZBridge.readOverallLevel(player);
        double radius = TierTable.levelToRadius(level);

        Double prev = lastRadius.get(player.getUuid());
        if (prev == null || Math.abs(prev - radius) > 0.001) {
            double from = prev == null ? radius : prev;
            MistRadiusPayload.sendTo(player, radius, from);
            lastRadius.put(player.getUuid(), radius);
        }

        Vec3d pos = player.getPos();
        BoundaryBand band = BoundaryMath.classify(pos.x, pos.z, radius);
        switch (band) {
            case HOSTILE -> HostileWaters.applyDebuffs(player, pos.x, pos.z, radius);
            case WALL, VISUAL, BEYOND -> hardClamp(player, radius);
            default -> {}
        }
    }

    private static void hardClamp(ServerPlayerEntity player, double radius) {
        Vec3d pos = player.getPos();
        double[] clamped = BoundaryMath.clampToWall(pos.x, pos.z, radius);
        // Preserve y; cancel outward velocity.
        Vec3d v = player.getVelocity();
        double dx = clamped[0] - pos.x;
        double dz = clamped[1] - pos.z;
        player.requestTeleport(clamped[0], pos.y, clamped[1]);
        player.setVelocity(Math.signum(dx) == 0 ? v.x : 0, v.y, Math.signum(dz) == 0 ? v.z : 0);
        player.velocityModified = true;
    }

    private BoundarySystem() {}
}
