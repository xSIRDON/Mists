package io.github.xsirdon.mists.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * Fires a tier-unlock notification: title + subtitle, chat message, and a pickup-orb sound.
 *
 * <p>Called from the network receiver thread; we dispatch the actual UI updates onto the
 * client's main thread via {@link MinecraftClient#execute}.
 *
 * <p>tierOrdinal == 0 (ONE) is the spawn state and never triggers a notification.
 */
public final class TierUnlockToast {

    public static void fire(int tierOrdinal) {
        if (tierOrdinal <= 0) return;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> show(client, tierOrdinal));
    }

    private static void show(MinecraftClient client, int tierOrdinal) {
        if (client.player == null || client.inGameHud == null) return;

        // Yarn 1.20.1 enum ordering: ONE=0, TWO=1, THREE=2, FOUR=3, OPEN=4.
        // Map ordinal -> language key suffix (the 1-based tier index, or "open" for OPEN).
        String suffix = switch (tierOrdinal) {
            case 1 -> "2";
            case 2 -> "3";
            case 3 -> "4";
            case 4 -> "open";
            default -> null;
        };
        if (suffix == null) return;

        String titleKey = "mists.title.tier_" + suffix;
        String subtitleKey = "mists.toast.tier_" + suffix;

        // Title: fade-in 0.5s (10t), hold 4s (80t), fade-out 1.5s (30t).
        // Yarn 1.20.1 names this setTitleTicks(fadeIn, stay, fadeOut).
        client.inGameHud.setTitleTicks(10, 80, 30);
        client.inGameHud.setTitle(Text.translatable(titleKey));
        client.inGameHud.setSubtitle(Text.translatable(subtitleKey));

        // Chat history (actionBar=false → goes to chat, not action bar overlay).
        client.player.sendMessage(Text.translatable(subtitleKey), false);

        // Sound: experience-orb pickup, slightly pitched up.
        client.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.4f);
    }

    private TierUnlockToast() {}
}
