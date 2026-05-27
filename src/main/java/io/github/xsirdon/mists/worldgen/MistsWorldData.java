package io.github.xsirdon.mists.worldgen;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.List;

public final class MistsWorldData extends PersistentState {

    public static final String KEY = "mists";

    public static final class IslandRecord {
        public final int tier;
        public final double cx, cz, radius;
        public final long seed;
        public IslandRecord(int tier, double cx, double cz, double radius, long seed) {
            this.tier = tier; this.cx = cx; this.cz = cz; this.radius = radius; this.seed = seed;
        }
    }

    public boolean placed = false;
    /** v0.21: true once {@link io.github.xsirdon.mists.worldgen.SpawnHutPlacer}
     *  has constructed the spawn hut for this world. Idempotency guard so a
     *  server restart or chunk-reload doesn't re-stamp the structure. */
    public boolean hutPlaced = false;
    public double spawnX = 0.0;
    public double spawnZ = 0.0;
    /** Tier-1 mist radius override (blocks). 0 = use the static MistsConstants.TIER_1_RADIUS.
     *  Set by IslandPlacer after measuring the actual spawn island's extent so the
     *  initial boundary wraps the island closely instead of being a fixed 120 blocks. */
    public double tier1RadiusOverride = 0.0;
    public final List<IslandRecord> islands = new ArrayList<>();

    @Override public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putBoolean("placed", placed);
        nbt.putBoolean("hut_placed", hutPlaced);
        nbt.putDouble("spawn_x", spawnX);
        nbt.putDouble("spawn_z", spawnZ);
        nbt.putDouble("tier1_radius_override", tier1RadiusOverride);
        NbtList list = new NbtList();
        for (IslandRecord r : islands) {
            NbtCompound tag = new NbtCompound();
            tag.putInt("tier", r.tier);
            tag.putDouble("cx", r.cx);
            tag.putDouble("cz", r.cz);
            tag.putDouble("radius", r.radius);
            tag.putLong("seed", r.seed);
            list.add(tag);
        }
        nbt.put("islands", list);
        return nbt;
    }

    public static MistsWorldData fromNbt(NbtCompound nbt) {
        MistsWorldData d = new MistsWorldData();
        d.placed = nbt.getBoolean("placed");
        d.hutPlaced = nbt.getBoolean("hut_placed");
        d.spawnX = nbt.contains("spawn_x", NbtElement.DOUBLE_TYPE) ? nbt.getDouble("spawn_x") : 0.0;
        d.spawnZ = nbt.contains("spawn_z", NbtElement.DOUBLE_TYPE) ? nbt.getDouble("spawn_z") : 0.0;
        d.tier1RadiusOverride = nbt.contains("tier1_radius_override", NbtElement.DOUBLE_TYPE)
            ? nbt.getDouble("tier1_radius_override") : 0.0;
        NbtList list = nbt.getList("islands", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound t = list.getCompound(i);
            d.islands.add(new IslandRecord(
                t.getInt("tier"), t.getDouble("cx"), t.getDouble("cz"),
                t.getDouble("radius"), t.getLong("seed")));
        }
        return d;
    }

    public static MistsWorldData get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
            MistsWorldData::fromNbt, MistsWorldData::new, KEY);
    }
}
