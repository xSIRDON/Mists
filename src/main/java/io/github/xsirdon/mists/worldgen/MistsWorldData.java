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
    public final List<IslandRecord> islands = new ArrayList<>();

    @Override public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putBoolean("placed", placed);
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
