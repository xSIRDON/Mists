package io.github.xsirdon.mists.network;

import io.github.xsirdon.mists.MistsConstants;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class MistRadiusPayload {
    public final double radius;
    public final double animateFromRadius;   // same as radius if no animation desired

    public MistRadiusPayload(double radius, double animateFromRadius) {
        this.radius = radius;
        this.animateFromRadius = animateFromRadius;
    }

    public static MistRadiusPayload decode(PacketByteBuf buf) {
        double r  = buf.readDouble();
        double af = buf.readDouble();
        return new MistRadiusPayload(r, af);
    }

    public PacketByteBuf encode() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeDouble(radius);
        buf.writeDouble(animateFromRadius);
        return buf;
    }

    public static void sendTo(ServerPlayerEntity player, double radius, double animateFromRadius) {
        ServerPlayNetworking.send(player, MistsConstants.MIST_RADIUS_PACKET,
            new MistRadiusPayload(radius, animateFromRadius).encode());
    }

    private MistRadiusPayload() { this(0, 0); }
}
