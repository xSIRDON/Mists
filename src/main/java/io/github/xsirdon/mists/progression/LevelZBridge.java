package io.github.xsirdon.mists.progression;

import io.github.xsirdon.mists.Mists;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;

/**
 * Reads a player's LevelZ total/overall level by reflection.
 *
 * LevelZ adds a duck interface {@code net.levelz.access.PlayerStatsManagerAccess}
 * to {@code PlayerEntity}, with method
 * {@code PlayerStatsManager getPlayerStatsManager(PlayerEntity)}.
 * That manager exposes {@code int getOverallLevel()}.
 *
 * We resolve both classes lazily and cache the methods. If LevelZ is missing
 * (it shouldn't be — it's a required dep), we log once and return 0 so the
 * player is treated as Tier 1.
 */
public final class LevelZBridge {

    private static volatile boolean attempted = false;
    private static volatile Class<?>  accessIface;
    private static volatile Method    getStatsManager;
    private static volatile Method    getOverallLevel;

    public static int readOverallLevel(ServerPlayerEntity player) {
        ensureResolved();
        if (accessIface == null) return 0;
        try {
            if (!accessIface.isInstance(player)) return 0;
            Object mgr = getStatsManager.invoke(player, player);
            return (int) getOverallLevel.invoke(mgr);
        } catch (ReflectiveOperationException e) {
            Mists.LOG.warn("LevelZBridge: reflective level read failed", e);
            return 0;
        }
    }

    private static void ensureResolved() {
        if (attempted) return;
        synchronized (LevelZBridge.class) {
            if (attempted) return;
            attempted = true;
            try {
                Class<?> iface  = Class.forName("net.levelz.access.PlayerStatsManagerAccess");
                Class<?> mgrCls = Class.forName("net.levelz.stats.PlayerStatsManager");
                Method get = iface.getMethod("getPlayerStatsManager",
                                              Class.forName("net.minecraft.entity.player.PlayerEntity"));
                Method lvl = mgrCls.getMethod("getOverallLevel");
                accessIface = iface;
                getStatsManager = get;
                getOverallLevel = lvl;
                Mists.LOG.info("LevelZBridge: linked to LevelZ at runtime");
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                Mists.LOG.error("LevelZBridge: LevelZ not found at runtime", e);
            }
        }
    }

    private LevelZBridge() {}
}
