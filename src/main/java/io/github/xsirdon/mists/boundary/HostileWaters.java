package io.github.xsirdon.mists.boundary;

import io.github.xsirdon.mists.MistsConstants;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;

public final class HostileWaters {

    /** Re-applied every tick — short durations keep the effect tightly bound to the band. */
    private static final int EFFECT_DURATION_TICKS = 40; // 2 seconds

    public static void applyDebuffs(ServerPlayerEntity player, double x, double z, double radius) {
        double d = BoundaryMath.distanceFromSpawn(x, z);
        double wallInner = radius - MistsConstants.HARD_WALL_INSET;
        double hostileInner = wallInner - MistsConstants.HOSTILE_BAND_THICKNESS;
        if (d < hostileInner) return;
        double depth01 = Math.min(1.0, (d - hostileInner) / MistsConstants.HOSTILE_BAND_THICKNESS);

        int slownessAmp = depth01 > 0.5 ? 1 : 0;        // Slowness I → II
        int nauseaAmp = 0;
        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.SLOWNESS, EFFECT_DURATION_TICKS, slownessAmp, false, false, true));
        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.NAUSEA, EFFECT_DURATION_TICKS, nauseaAmp, false, false, true));

        // Drowning damage that scales 0 → 2 hearts/sec as depth01 ramps 0 → 1.
        // Tick rate is 20Hz, so apply (depth01 * 0.2) damage per tick.
        float dmg = (float) (depth01 * 0.2);
        if (dmg > 0) {
            player.damage(player.getDamageSources().drown(), dmg);
        }
    }

    private HostileWaters() {}
}
