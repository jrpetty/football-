package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * [kitchen] A drink in its own bottle (KitchenItems): the brewer's mead and cider, the healer's herbal tea. Drunk
 * from the bottle like a potion, whatever the hunger, and the bottle comes back. No food to the game's eye, so the folk
 * never take a bottle out of the stores for a meal: they drink it where it is poured (entity/Kitchen: the tavern's bar,
 * a toast, the café's counter, the healer at a bedside).
 */
public class KitchenDrinkItem extends Item {

    private final Supplier<List<MobEffectInstance>> effects;
    private final String does, made;

    public KitchenDrinkItem(Properties properties, Supplier<List<MobEffectInstance>> effects, String does, String made) {
        super(properties);
        this.effects = effects;
        this.does = does;
        this.made = made;
    }

    /** The good a bottle does whoever drinks it, fresh each time. */
    public List<MobEffectInstance> effects() {
        return effects.get();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return ItemUtils.startUsingInstantly(level, player, hand);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity who) {
        if (!level.isClientSide) {
            for (MobEffectInstance e : effects()) who.addEffect(e);
        }
        if (who instanceof ServerPlayer sp) {
            CriteriaTriggers.CONSUME_ITEM.trigger(sp, stack);
            sp.awardStat(Stats.ITEM_USED.get(this));
        }
        who.gameEvent(GameEvent.DRINK);
        if (who instanceof Player p && p.getAbilities().instabuild) return stack;
        stack.shrink(1);
        if (stack.isEmpty()) return new ItemStack(Items.GLASS_BOTTLE);
        if (who instanceof Player p && !p.getInventory().add(new ItemStack(Items.GLASS_BOTTLE))) {
            p.drop(new ItemStack(Items.GLASS_BOTTLE), false);
        }
        return stack;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity who) {
        return 32;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.DRINK;
    }

    @Override
    public SoundEvent getDrinkingSound() {
        return SoundEvents.GENERIC_DRINK;
    }

    @Override
    public SoundEvent getEatingSound() {
        return SoundEvents.GENERIC_DRINK;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal(does).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(made).withStyle(ChatFormatting.DARK_GRAY));
    }
}
