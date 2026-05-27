package io.github.xsirdon.mists.worldgen;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MistsWorldDataTest {

    @Test void hutPlacedRoundTripsThroughNbt() {
        MistsWorldData d = new MistsWorldData();
        d.placed = true;
        d.hutPlaced = true;
        d.spawnX = 314.0;
        d.spawnZ = -271.0;
        d.tier1RadiusOverride = 88.5;
        d.islands.add(new MistsWorldData.IslandRecord(1, 314.0, -271.0, 30.0, 42L));

        NbtCompound nbt = new NbtCompound();
        d.writeNbt(nbt);

        MistsWorldData restored = MistsWorldData.fromNbt(nbt);
        assertTrue(restored.placed);
        assertTrue(restored.hutPlaced, "hut_placed should survive NBT round-trip");
        assertEquals(314.0, restored.spawnX);
        assertEquals(-271.0, restored.spawnZ);
        assertEquals(88.5, restored.tier1RadiusOverride);
        assertEquals(1, restored.islands.size());
        assertEquals(42L, restored.islands.get(0).seed);
    }

    @Test void hutPlacedDefaultsToFalseForOldData() {
        // Simulate a v0.20 save that has no hut_placed key.
        NbtCompound legacy = new NbtCompound();
        legacy.putBoolean("placed", true);
        legacy.putDouble("spawn_x", 0.0);
        legacy.putDouble("spawn_z", 0.0);

        MistsWorldData restored = MistsWorldData.fromNbt(legacy);
        assertTrue(restored.placed);
        assertFalse(restored.hutPlaced,
            "hut_placed should default to false when key is absent (v0.20 → v0.21 migration)");
    }
}
