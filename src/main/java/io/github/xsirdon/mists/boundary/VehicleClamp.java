package io.github.xsirdon.mists.boundary;

import io.github.xsirdon.mists.progression.LevelZBridge;
import io.github.xsirdon.mists.progression.TierTable;
import io.github.xsirdon.mists.worldgen.MistsWorldData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

public final class VehicleClamp {

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                Entity vehicle = player.getVehicle();
                if (vehicle == null) continue;
                // Find the lowest LevelZ level among all human passengers of this vehicle.
                int lowest = Integer.MAX_VALUE;
                for (Entity p : vehicle.getPassengerList()) {
                    if (p instanceof ServerPlayerEntity sp) {
                        lowest = Math.min(lowest, LevelZBridge.readOverallLevel(sp));
                    }
                }
                if (lowest == Integer.MAX_VALUE) continue;
                double radius = TierTable.levelToRadius(lowest);
                if (!(player.getWorld() instanceof ServerWorld serverWorld)) continue;
                MistsWorldData data = MistsWorldData.get(serverWorld);
                double cx = data.spawnX;
                double cz = data.spawnZ;
                Vec3d pos = vehicle.getPos();
                if (BoundaryMath.distanceFromCenter(pos.x, pos.z, cx, cz) > radius - 2.0) {
                    double[] clamped = BoundaryMath.clampToWall(pos.x, pos.z, cx, cz, radius);
                    vehicle.requestTeleport(clamped[0], pos.y, clamped[1]);
                    vehicle.setVelocity(0, vehicle.getVelocity().y, 0);
                    vehicle.velocityModified = true;
                }
            }
        });
    }

    private VehicleClamp() {}
}
