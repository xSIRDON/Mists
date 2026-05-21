package io.github.xsirdon.mists;

import net.minecraft.util.Identifier;

public final class MistsConstants {
    public static final String MOD_ID = "mists";

    // Boundary radii (blocks from world spawn at 0,0)
    public static final double TIER_1_RADIUS =  120.0;
    public static final double TIER_2_RADIUS =  350.0;
    public static final double TIER_3_RADIUS =  650.0;
    public static final double TIER_4_RADIUS = 1000.0;
    public static final double TIER_OPEN_RADIUS = 30_000_000.0; // effectively infinite

    // LevelZ total-level thresholds that grant each tier
    public static final int TIER_2_REQUIRED_LEVEL =  5;
    public static final int TIER_3_REQUIRED_LEVEL = 10;
    public static final int TIER_4_REQUIRED_LEVEL = 15;
    public static final int TIER_OPEN_REQUIRED_LEVEL = 30;

    // Boundary band geometry (depths measured inward from the visual mist line)
    public static final double VISUAL_BAND_THICKNESS  = 30.0;
    public static final double HOSTILE_BAND_THICKNESS = 15.0;
    public static final double HARD_WALL_INSET        =  2.0;

    // Network channel
    public static final Identifier MIST_RADIUS_PACKET =
        new Identifier(MOD_ID, "mist_radius");

    private MistsConstants() {}
}
