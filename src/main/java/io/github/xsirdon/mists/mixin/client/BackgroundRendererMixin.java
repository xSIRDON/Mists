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

@Mixin(BackgroundRenderer.class)
public class BackgroundRendererMixin {

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

        // Past the wall (shouldn't happen since the server clamps players, but safe path
        // for camera observers, F5 third-person, etc.): pin to dense fog.
        if (wallDist <= 0) {
            RenderSystem.setShaderFogStart(0f);
            RenderSystem.setShaderFogEnd(1f);
            RenderSystem.setShaderFogShape(FogShape.SPHERE);
            return;
        }

        // Within 80 blocks of the wall, start closing fog in. Linear ramp:
        //  wallDist = 80 → fog start = 64, fog end = view distance (no change)
        //  wallDist = 0  → fog start = 0,  fog end = 0
        if (wallDist < 80) {
            float newEnd   = (float) (wallDist + 4);
            float newStart = (float) Math.max(0, wallDist - 24);
            RenderSystem.setShaderFogStart(newStart);
            RenderSystem.setShaderFogEnd(newEnd);
            RenderSystem.setShaderFogShape(FogShape.SPHERE);
        }
    }
}
