package io.github.xsirdon.mists.progression;

import io.github.xsirdon.mists.Mists;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;

/**
 * Reads a player's LevelZ total/overall level by reflection.
 *
 * The True Survival fork of LevelZ exposes a duck-typed interface
 * {@code net.levelz.access.PlayerStatsManagerAccess} on PlayerEntity with a
 * no-argument {@code getPlayerStatsManager()} method. That manager exposes a
 * public field {@code int overallLevel} as well as a getter
 * {@code int getOverallLevel()}. We use the getter.
 *
 * Signatures verified via {@code javap} against {@code levelz-true-survival-1.4.13.jar}:
 * <pre>
 *   public interface PlayerStatsManagerAccess {
 *     public abstract PlayerStatsManager getPlayerStatsManager();
 *   }
 *   public class PlayerStatsManager {
 *     public int overallLevel;
 *     public int getOverallLevel();
 *     ...
 *   }
 * </pre>
 *
 * If LevelZ is missing or has a different signature, we log once and return 0
 * so the player is treated as Tier 1.
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
            Object mgr = getStatsManager.invoke(player);
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
                Method get = iface.getMethod("getPlayerStatsManager");
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
