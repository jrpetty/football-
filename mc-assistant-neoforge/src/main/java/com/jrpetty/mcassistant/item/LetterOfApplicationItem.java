package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [interviews] A letter of application: a paper and an ink sac, written in the candidate's own hand at its own cost out
 * of its town's stores (entity/Interviews). It names its writer, the post and the town, and its writer's particulars —
 * its trade and level, its years, the knacks of the trade it chose — and says in a line why it wants the post. The
 * candidate holds it while it waits its turn and hands it across the table; the chair holds it while it reads. Read
 * (used), it reads itself out to whoever holds it. One a player writes for itself is a blank sheet till it is used.
 */
public class LetterOfApplicationItem extends Item {

    /** Where its words are kept on it. */
    public static final String KEY = "mca_letter";

    public LetterOfApplicationItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide()) {
            CompoundTag t = words(held);
            if (t.isEmpty()) {
                player.displayClientMessage(Component.literal("A blank letter of application: nobody has written on it yet.")
                    .withStyle(ChatFormatting.GRAY), false);
            } else {
                player.displayClientMessage(Component.literal("To the panel, " + t.getString("Town") + ": " + t.getString("Words"))
                    .withStyle(ChatFormatting.GOLD), false);
                player.displayClientMessage(Component.literal("— " + t.getString("Name") + (t.getString("From").isEmpty() ? "" : ", of " + t.getString("From")))
                    .withStyle(ChatFormatting.GRAY), false);
            }
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        CompoundTag t = words(stack);
        if (t.isEmpty()) {
            lines.add(Component.literal("A blank sheet, and ink: for a post you want").withStyle(ChatFormatting.GRAY));
            return;
        }
        lines.add(Component.literal(t.getString("Name") + (t.getString("From").isEmpty() ? "" : " of " + t.getString("From")))
            .withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("For the post of " + t.getString("Post") + ", " + t.getString("Town")).withStyle(ChatFormatting.GRAY));
        String trade = t.getString("Trade");
        lines.add(Component.literal((trade.isEmpty() ? "No trade yet" : trade + ", level " + t.getInt("Level")) + " · " + t.getInt("Age")
            + " years" + (t.getInt("Knacks") > 0 ? " · " + t.getInt("Knacks") + (t.getInt("Knacks") == 1 ? " knack" : " knacks") + " of the trade" : ""))
            .withStyle(ChatFormatting.DARK_GRAY));
        if (!t.getString("Why").isEmpty()) lines.add(Component.literal("\"" + t.getString("Why") + "\"").withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
        lines.add(Component.literal("Written on day " + (t.getLong("Day") + 1)).withStyle(ChatFormatting.DARK_GRAY));
    }

    /** What is written on it, or an empty tag. */
    public static CompoundTag words(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? new CompoundTag() : d.copyTag().getCompound(KEY);
    }
}
