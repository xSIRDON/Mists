package io.github.xsirdon.mists.boundary;

import io.github.xsirdon.mists.Mists;
import io.github.xsirdon.mists.progression.LevelZBridge;
import io.github.xsirdon.mists.progression.TierTable;
import io.github.xsirdon.mists.worldgen.MistsWorldData;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * On player join, if the player has been placed outside their mist boundary
 * (e.g., vanilla "find safe spawn" landed them on natural terrain far from the
 * spawn island we built), teleport them to the spawn island so they aren't
 * spawned inside hostile waters.
 *
 * Only fires on the initial join; subsequent ticks rely on the regular
 * BoundarySystem clamp.
 */
public final class PlayerJoinClamp {

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            ServerWorld world = player.getServerWorld();
            MistsWorldData data = MistsWorldData.get(world);

            double cx = data.spawnX;
            double cz = data.spawnZ;
            int level = LevelZBridge.readOverallLevel(player);
            double radius = TierTable.levelToRadius(level, data);

            double dx = player.getX() - cx;
            double dz = player.getZ() - cz;
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist > radius - 4) {
                // Use the world's current spawn position (set by SpawnIsland.build to the
                // actual top of the built island, accounting for height variation).
                BlockPos worldSpawn = world.getSpawnPos();
                double safeY = worldSpawn.getY() + 1; // one block above the spawn block
                player.requestTeleport(cx, safeY, cz);
                Mists.LOG.info("Mists: rescued player {} from outside boundary (dist {} > {}) → teleported to spawn ({}, {}, {})",
                    player.getGameProfile().getName(), (int) dist, (int) radius,
                    (int) cx, (int) safeY, (int) cz);
            }
        });
    }

    private PlayerJoinClamp() {}
}
