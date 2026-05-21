package io.github.xsirdon.mists.mixin.compat;

import io.github.xsirdon.mists.Mists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the empty-forecast crash in Enhanced Celestials.
 *
 * EC has a defensive {@code checkEmptyForecastOrThrow} that aborts the game with
 * "Forecast cannot be empty.... this should be impossible.... crashing game...."
 * whenever its lunar-event forecast list happens to be empty during a server tick.
 *
 * In the True Survival modpack this fires on roughly 80% of fresh world creations
 * (verified: 4/5 crashes without our mod present), regardless of seed. EC's
 * forecast is supposed to be populated on world creation but a race in its init
 * leaves it empty often enough to be a permanent blocker.
 *
 * We can't fix EC's init, but we can cancel the throw. An empty forecast is
 * recoverable — EC will repopulate it on the next daily roll, so blood moons
 * just won't fire on the first in-game day. Acceptable trade-off versus
 * crashing 4/5 worlds.
 *
 * Pseudo-targeted so the build doesn't depend on EC being on the compile
 * classpath (we reference it by FQCN string).
 */
@Pseudo
@Mixin(targets = "dev.corgitaco.enhancedcelestials.lunarevent.EnhancedCelestialsLunarForecastWorldData",
       remap = false)
public class EnhancedCelestialsForecastFixMixin {

    @Inject(method = "checkEmptyForecastOrThrow", at = @At("HEAD"), cancellable = true,
            require = 0, remap = false)
    private void mists$silenceEmptyForecast(CallbackInfo ci) {
        Mists.LOG.debug("Mists: suppressed EC empty-forecast assertion (forecast will repopulate on next daily roll)");
        ci.cancel();
    }
}
