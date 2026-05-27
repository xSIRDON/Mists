package io.github.xsirdon.mists.item;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * v0.21: a key ingredient in mist-flavoured crafting (currently consumed by
 * {@code mists:mist_lantern}). Always glints, ships with two lines of flavour
 * text drawn from the lang file so localisation works out of the box.
 */
public final class MistCrystalItem extends Item {

    public MistCrystalItem(Settings settings) {
        super(settings);
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world,
                              List<Text> tooltip, TooltipContext context) {
        super.appendTooltip(stack, world, tooltip, context);
        tooltip.add(Text.translatable("item.mists.mist_crystal.tooltip.line1"));
        tooltip.add(Text.translatable("item.mists.mist_crystal.tooltip.line2"));
    }
}
