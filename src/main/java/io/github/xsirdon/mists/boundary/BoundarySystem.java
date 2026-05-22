package io.github.xsirdon.mists.boundary;

import io.github.xsirdon.mists.network.MistRadiusPayload;
import io.github.xsirdon.mists.progression.LevelZBridge;
import io.github.xsirdon.mists.progression.Tier;
import io.github.xsirdon.mists.progression.TierTable;
import io.github.xsirdon.mists.worldgen.MistsWorldData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BoundarySystem {

    /** UUID → last-known radius. Used to detect transitions and animate the client. */
    private static final Map<UUID, Double> lastRadius = new HashMap<>();
    /** UUID → last-known tier ordinal. Used to detect tier-unlock transitions. */
    private static final Map<UUID, Integer> lastTier = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                tickPlayer(player);
            }
        });
    }

    private static void tickPlayer(ServerPlayerEntity player) {
        int level = LevelZBridge.readOverallLevel(player);
        Tier currentTier = TierTable.levelToTier(level);

        if (!(player.getWorld() instanceof ServerWorld serverWorld)) return;
        MistsWorldData data = MistsWorldData.get(serverWorld);
        double cx = data.spawnX;
        double cz = data.spawnZ;
        double radius = TierTable.tierToRadius(currentTier, data);

        Double prev = lastRadius.get(player.getUuid());
        Integer prevTier = lastTier.get(player.getUuid());
        boolean radiusChanged = prev == null || Math.abs(prev - radius) > 0.001;
        boolean tierChanged = prevTier == null || prevTier != currentTier.ordinal();

        if (radiusChanged || tierChanged) {
            double from = prev == null ? radius : prev;
            MistRadiusPayload.sendTo(player, radius, from, cx, cz, currentTier.ordinal());
            lastRadius.put(player.getUuid(), radius);
            lastTier.put(player.getUuid(), currentTier.ordinal());
        }

        // Creative and spectator players bypass the boundary entirely — no
        // hostile-waters debuff, no hard clamp. The radius packet still gets
        // sent so the client can adjust mist rendering, but the client-side
        // fog mixin also opts out when the local player is creative/spectator.
        if (isCreativeOrSpectator(player)) return;

        Vec3d pos = player.getPos();
        BoundaryBand band = BoundaryMath.classify(pos.x, pos.z, cx, cz, radius);
        switch (band) {
            case HOSTILE -> HostileWaters.applyDebuffs(player, pos.x, pos.z, cx, cz, radius);
            case WALL, VISUAL, BEYOND -> hardClamp(player, cx, cz, radius);
            default -> {}
        }
    }

    /** True if the player should be exempt from boundary enforcement (creative or spectator). */
    public static boolean isCreativeOrSpectator(ServerPlayerEntity player) {
        return player.isCreative() || player.isSpectator();
    }

    private static void hardClamp(ServerPlayerEntity player, double cx, double cz, double radius) {
        Vec3d pos = player.getPos();
        double[] clamped = BoundaryMath.clampToWall(pos.x, pos.z, cx, cz, radius);
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
