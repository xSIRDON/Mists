package io.github.xsirdon.mists.block;

import io.github.xsirdon.mists.MistsConstants;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

/**
 * Central registration class for blocks owned by Mists. Mirrors the
 * registration pattern used by {@link io.github.xsirdon.mists.worldgen.MistsBiomes}:
 * one static field per registered object, lazy-initialised in {@link #register()},
 * which is called once from {@code Mists.onInitialize()}.
 */
public final class MistsBlocks {

    /** Registry key for the vanilla "Functional Blocks" creative tab. The
     *  vanilla {@code ItemGroups} class keeps these keys private, so we
     *  reconstruct the key from its identifier — Fabric's
     *  {@link ItemGroupEvents#modifyEntriesEvent} accepts any RegistryKey
     *  equal to the registered one. */
    private static final RegistryKey<ItemGroup> FUNCTIONAL_GROUP =
        RegistryKey.of(RegistryKeys.ITEM_GROUP, new Identifier("minecraft", "functional_blocks"));

    /** The Mist Lantern block — a slightly dimmer (light 11), atmospheric
     *  cousin of the vanilla lantern, waterloggable, hangable, with subtle
     *  particle emission. */
    public static final MistLanternBlock MIST_LANTERN = new MistLanternBlock(
        AbstractBlock.Settings
            .copy(Blocks.LANTERN)
            .sounds(BlockSoundGroup.LANTERN)
            .hardness(3.5f)
            .requiresTool()
            .luminance(state -> 11)
            .nonOpaque()
    );

    /** Block item paired with {@link #MIST_LANTERN}. */
    public static final BlockItem MIST_LANTERN_ITEM = new BlockItem(MIST_LANTERN,
        new Item.Settings());

    private MistsBlocks() {}

    public static void register() {
        Identifier lanternId = new Identifier(MistsConstants.MOD_ID, "mist_lantern");

        Registry.register(Registries.BLOCK, lanternId, MIST_LANTERN);
        Registry.register(Registries.ITEM,  lanternId, MIST_LANTERN_ITEM);

        ItemGroupEvents.modifyEntriesEvent(FUNCTIONAL_GROUP).register(entries ->
            entries.add(MIST_LANTERN_ITEM));
    }
}
