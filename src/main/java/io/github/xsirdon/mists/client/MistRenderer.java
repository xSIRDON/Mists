package io.github.xsirdon.mists.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;

public final class MistRenderer {

    private static int tick = 0;

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> tickRender());
    }

    private static void tickRender() {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;
        ClientWorld world = mc.world;
        if (player == null || world == null) return;
        if (++tick % 2 != 0) return;          // spawn at 10Hz max
        double radius = MistState.effectiveRadius();
        if (!Double.isFinite(radius) || radius > 25_000) return;

        Vec3d pos = player.getPos();
        double playerAngle = Math.atan2(pos.z, pos.x);
        // Only spawn arc particles within ±60° of the player's view direction from spawn.
        double arcHalf = Math.toRadians(60);
        int slices = 24;
        for (int i = -slices; i <= slices; i++) {
            double a = playerAngle + (i / (double) slices) * arcHalf;
            double x = Math.cos(a) * radius;
            double z = Math.sin(a) * radius;
            // Three vertical bands: low, mid, high — purely for visual depth.
            for (double dy : new double[]{ 56, 70, 90 }) {
                if (Math.random() > 0.35) continue;
                world.addParticle(ParticleTypes.CLOUD, x, dy, z, 0, 0.005, 0);
            }
        }
    }

    private MistRenderer() {}
}
