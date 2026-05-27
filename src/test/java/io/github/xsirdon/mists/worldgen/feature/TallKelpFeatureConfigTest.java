package io.github.xsirdon.mists.worldgen.feature;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TallKelpFeatureConfigTest {

    @Test void recordPreservesValues() {
        TallKelpFeatureConfig cfg = new TallKelpFeatureConfig(12, 16, 0.6f);
        assertEquals(12, cfg.minHeight());
        assertEquals(16, cfg.maxHeight());
        assertEquals(0.6f, cfg.spreadChance(), 1e-6);
    }

    @Test void codecRoundTripsJson() {
        TallKelpFeatureConfig original = new TallKelpFeatureConfig(18, 24, 0.3f);
        DataResult<com.google.gson.JsonElement> encoded =
            TallKelpFeatureConfig.CODEC.encodeStart(JsonOps.INSTANCE, original);
        assertTrue(encoded.result().isPresent(), () -> "encode error: " + encoded.error());

        DataResult<TallKelpFeatureConfig> decoded =
            TallKelpFeatureConfig.CODEC.parse(JsonOps.INSTANCE, encoded.result().get());
        assertTrue(decoded.result().isPresent(), () -> "decode error: " + decoded.error());

        TallKelpFeatureConfig restored = decoded.result().get();
        assertEquals(original.minHeight(), restored.minHeight());
        assertEquals(original.maxHeight(), restored.maxHeight());
        assertEquals(original.spreadChance(), restored.spreadChance(), 1e-6);
    }

    @Test void codecRejectsOutOfRangeHeight() {
        // min_height must be >= 1 per the codec definition.
        JsonObject obj = new JsonObject();
        obj.add("min_height", new JsonPrimitive(0));
        obj.add("max_height", new JsonPrimitive(5));
        obj.add("spread_chance", new JsonPrimitive(0.5f));
        DataResult<TallKelpFeatureConfig> r =
            TallKelpFeatureConfig.CODEC.parse(JsonOps.INSTANCE, obj);
        assertFalse(r.result().isPresent(), "min_height=0 should fail the codec range check");
    }

    @Test void codecRejectsOutOfRangeSpreadChance() {
        JsonObject obj = new JsonObject();
        obj.add("min_height", new JsonPrimitive(5));
        obj.add("max_height", new JsonPrimitive(10));
        obj.add("spread_chance", new JsonPrimitive(1.5f));
        DataResult<TallKelpFeatureConfig> r =
            TallKelpFeatureConfig.CODEC.parse(JsonOps.INSTANCE, obj);
        assertFalse(r.result().isPresent(), "spread_chance>1 should fail the codec range check");
    }
}
