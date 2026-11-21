package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [nether] The Gold Charm: a gold ingot hammered into a brow band, three nuggets for its clasps and a string to tie it.
 * Worn on the head (right-click, or into the helmet's place), it is gold the piglins can see: they leave its wearer be,
 * as they leave anybody in gold armour (IItemExtension.makesPiglinsNeutral, the game's own rule). An ingot and a third
 * of gold where a gold helmet takes five; a point of armour where the helmet gives two; and it never wears out. The
 * town's smith makes one for each Nether runner that has no gold to wear (NetherKit); the folk count it as their piece
 * of gold, and so does every piglin.
 */
public class GoldCharmItem extends Item implements Equipable {

    public GoldCharmItem(Properties properties) {
        super(properties);
    }

    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    @Override
    public Holder<SoundEvent> getEquipSound() {
        return SoundEvents.ARMOR_EQUIP_GOLD;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return this.swapWithEquipmentSlot(this, level, player, hand);
    }

    /** Gold where a piglin looks for it: worn, it keeps them off as gold armour does. */
    @Override
    public boolean makesPiglinsNeutral(ItemStack stack, LivingEntity wearer) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Worn on the brow: piglins leave the wearer be.").withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("Cheaper than gold armour, and it never wears out.").withStyle(ChatFormatting.GRAY));
    }
}
