package io.github.xsirdon.mists.network;

import io.github.xsirdon.mists.MistsConstants;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class MistRadiusPayload {
    public final double radius;
    public final double animateFromRadius;   // same as radius if no animation desired
    public final double centerX;
    public final double centerZ;
    public final int    tierOrdinal;

    public MistRadiusPayload(double radius, double animateFromRadius,
                             double centerX, double centerZ, int tierOrdinal) {
        this.radius = radius;
        this.animateFromRadius = animateFromRadius;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.tierOrdinal = tierOrdinal;
    }

    public static MistRadiusPayload decode(PacketByteBuf buf) {
        double r  = buf.readDouble();
        double af = buf.readDouble();
        double cx = buf.readDouble();
        double cz = buf.readDouble();
        int t     = buf.readInt();
        return new MistRadiusPayload(r, af, cx, cz, t);
    }

    public PacketByteBuf encode() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeDouble(radius);
        buf.writeDouble(animateFromRadius);
        buf.writeDouble(centerX);
        buf.writeDouble(centerZ);
        buf.writeInt(tierOrdinal);
        return buf;
    }

    public static void sendTo(ServerPlayerEntity player, double radius, double animateFromRadius,
                              double centerX, double centerZ, int tierOrdinal) {
        ServerPlayNetworking.send(player, MistsConstants.MIST_RADIUS_PACKET,
            new MistRadiusPayload(radius, animateFromRadius, centerX, centerZ, tierOrdinal).encode());
    }

    private MistRadiusPayload() { this(0, 0, 0, 0, 0); }
}
