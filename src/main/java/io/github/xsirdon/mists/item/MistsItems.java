package io.github.xsirdon.mists.item;

import io.github.xsirdon.mists.MistsConstants;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * Central registration for items owned by Mists. Pure items (i.e. items that
 * are not paired with a block) live here; block items are registered alongside
 * their block in {@link io.github.xsirdon.mists.block.MistsBlocks}.
 */
public final class MistsItems {

    /** Registry key for vanilla "Ingredients" tab; see comment in
     *  {@code MistsBlocks#FUNCTIONAL_GROUP} for why we reconstruct the key. */
    private static final RegistryKey<ItemGroup> INGREDIENTS_GROUP =
        RegistryKey.of(RegistryKeys.ITEM_GROUP, new Identifier("minecraft", "ingredients"));

    public static final MistCrystalItem MIST_CRYSTAL =
        new MistCrystalItem(new Item.Settings());

    private MistsItems() {}

    public static void register() {
        Identifier crystalId = new Identifier(MistsConstants.MOD_ID, "mist_crystal");
        Registry.register(Registries.ITEM, crystalId, MIST_CRYSTAL);

        ItemGroupEvents.modifyEntriesEvent(INGREDIENTS_GROUP).register(entries ->
            entries.add(MIST_CRYSTAL));
    }
}
