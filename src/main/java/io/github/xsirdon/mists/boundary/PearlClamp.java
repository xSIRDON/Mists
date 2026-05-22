package io.github.xsirdon.mists.boundary;

import io.github.xsirdon.mists.progression.LevelZBridge;
import io.github.xsirdon.mists.progression.TierTable;
import io.github.xsirdon.mists.worldgen.MistsWorldData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;

public final class PearlClamp {

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof EnderPearlEntity pearl)) return;
            if (!(pearl.getOwner() instanceof ServerPlayerEntity player)) return;
            if (BoundarySystem.isCreativeOrSpectator(player)) return; // creative bypass
            int level = LevelZBridge.readOverallLevel(player);
            ServerWorld serverWorld = (ServerWorld) world;
            MistsWorldData data = MistsWorldData.get(serverWorld);
            double radius = TierTable.levelToRadius(level, data);
            double cx = data.spawnX;
            double cz = data.spawnZ;

            // Project pearl's velocity ~3s forward to find an estimated destination.
            double estX = pearl.getX() + pearl.getVelocity().x * 60;
            double estZ = pearl.getZ() + pearl.getVelocity().z * 60;
            if (BoundaryMath.distanceFromCenter(estX, estZ, cx, cz) > radius - 4) {
                // Refund and remove.
                pearl.discard();
                player.getInventory().offerOrDrop(new net.minecraft.item.ItemStack(
                    net.minecraft.item.Items.ENDER_PEARL));
                player.sendMessage(net.minecraft.text.Text.literal("The pearl is swallowed by the mist."), true);
            }
        });
    }

    private PearlClamp() {}
}
