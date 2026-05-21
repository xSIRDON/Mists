package io.github.xsirdon.mists.mixin.client;

import io.github.xsirdon.mists.client.MistState;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.FogShape;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Smoothly closes vanilla terrain fog inward as the player approaches the mist boundary.
 *
 * The fog ramp is CONTINUOUS over the entire approach (no thresholds, no toggle):
 * at far distance the vanilla fog parameters are preserved untouched; from there the
 * fog start and fog end both interpolate linearly inward, reaching maximum density
 * right at the wall. There is no point at which the fog mod "turns on" — it just
 * gradually becomes denser as you get closer.
 */
@Mixin(BackgroundRenderer.class)
public class BackgroundRendererMixin {

    /** Distance at which we start blending toward dense fog. Beyond this, vanilla fog is untouched. */
    private static final double RAMP_DISTANCE = 240.0;

    @Inject(method = "applyFog", at = @At("TAIL"))
    private static void mists$applyMistFog(Camera camera, BackgroundRenderer.FogType fogType,
                                            float viewDistance, boolean thickFog,
                                            float tickDelta, CallbackInfo ci) {
        if (fogType != BackgroundRenderer.FogType.FOG_TERRAIN) return;

        Entity entity = camera.getFocusedEntity();
        if (entity == null) return;

        double radius = MistState.effectiveRadius();
        if (!Double.isFinite(radius) || radius > 25_000) return;

        double cx = MistState.centerX;
        double cz = MistState.centerZ;
        double dx = entity.getX() - cx;
        double dz = entity.getZ() - cz;
        double distFromCenter = Math.sqrt(dx * dx + dz * dz);
        double wallDist = radius - distFromCenter;

        // Past the wall (camera observers / F5 spectator). Pin to dense white.
        if (wallDist <= 0) {
            RenderSystem.setShaderFogStart(0f);
            RenderSystem.setShaderFogEnd(1f);
            RenderSystem.setShaderFogShape(FogShape.SPHERE);
            return;
        }

        // Past the ramp start — let vanilla fog stand. Far from the wall.
        if (wallDist >= RAMP_DISTANCE) return;

        // Smooth ramp 0..1: t=0 at the wall (densest fog), t=1 at RAMP_DISTANCE (vanilla).
        // Apply an ease-out curve so the transition feels organic rather than mathematical.
        double t = wallDist / RAMP_DISTANCE;
        double eased = t * t * (3.0 - 2.0 * t); // smoothstep — flat at both ends

        // Vanilla terrain fog end ~= viewDistance.
        // At t=1 (vanilla): start = viewDistance*0.75, end = viewDistance.
        // At t=0 (wall):    start = 0,                end = 4 (everything in front is white).
        float vanillaStart = viewDistance * 0.75f;
        float vanillaEnd   = viewDistance;
        float wallStart    = 0f;
        float wallEnd      = 4f;

        float newStart = (float) (wallStart + eased * (vanillaStart - wallStart));
        float newEnd   = (float) (wallEnd   + eased * (vanillaEnd   - wallEnd));

        RenderSystem.setShaderFogStart(newStart);
        RenderSystem.setShaderFogEnd(newEnd);
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
    }
}
