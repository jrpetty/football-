package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.block.PlaceSettingBlock;
import com.jrpetty.mcassistant.block.PlaceSettingBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [dining] A plate or a cup (glazed clay, fired: three bricks make three plates, two bricks and a stick two cups): set
 * down it is a place setting (PlaceSettingBlock). On a table's carpet it is laid on a white cloth in the carpet's place;
 * set on a setting with no plate (or no cup) it joins it. A dish held to a bare plate goes on it, a drink to an empty cup;
 * crouch and right-click an empty one to take it back up.
 */
public class PlaceSettingItem extends BlockItem {

    /** Which of the two it is. */
    public enum Kind { PLATE, CUP }

    private final Kind kind;

    public PlaceSettingItem(Block block, Kind kind, Item.Properties properties) {
        super(block, properties);
        this.kind = kind;
    }

    public Kind kind() { return kind; }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        BlockState at = level.getBlockState(pos);
        // Joined to a setting that lacks it: a cup by a plate, a plate under a cup.
        if (at.getBlock() instanceof PlaceSettingBlock && !at.getValue(PlaceSettingBlock.HOUSE)) {
            boolean lacks = kind == Kind.PLATE ? !at.getValue(PlaceSettingBlock.DISH).plate() : at.getValue(PlaceSettingBlock.CUP) == PlaceSettingBlock.Cup.NONE;
            if (!lacks) return InteractionResult.PASS;
            if (!level.isClientSide) {
                level.setBlock(pos, kind == Kind.PLATE ? at.setValue(PlaceSettingBlock.DISH, PlaceSettingBlock.Dish.EMPTY)
                    : at.setValue(PlaceSettingBlock.CUP, PlaceSettingBlock.Cup.EMPTY), 3);
                ctx.getItemInHand().consume(1, ctx.getPlayer());
                level.playSound(null, pos, SoundEvents.DECORATED_POT_PLACE, SoundSource.BLOCKS, 0.7F, 1.3F);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        // On a table's carpet: laid on a cloth in the carpet's place.
        if (PlaceSettingBlock.isCloth(at) && PlaceSettingBlock.onATable(level, pos)) {
            if (!level.isClientSide) {
                level.setBlock(pos, placed().setValue(PlaceSettingBlock.CLOTH, true), 3);
                if (level.getBlockEntity(pos) instanceof PlaceSettingBlockEntity be) be.layOn(at);
                ctx.getItemInHand().consume(1, ctx.getPlayer());
                level.playSound(null, pos, SoundEvents.DECORATED_POT_PLACE, SoundSource.BLOCKS, 0.7F, 1.3F);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.useOn(ctx);
    }

    @Nullable
    @Override
    protected BlockState getPlacementState(BlockPlaceContext ctx) {
        BlockState s = super.getPlacementState(ctx);
        return s == null ? null : placed();
    }

    /** The setting this puts down on its own: a bare plate, or an empty cup. */
    private BlockState placed() {
        BlockState s = getBlock().defaultBlockState();
        return kind == Kind.PLATE ? s.setValue(PlaceSettingBlock.DISH, PlaceSettingBlock.Dish.EMPTY).setValue(PlaceSettingBlock.CUP, PlaceSettingBlock.Cup.NONE)
            : s.setValue(PlaceSettingBlock.DISH, PlaceSettingBlock.Dish.NONE).setValue(PlaceSettingBlock.CUP, PlaceSettingBlock.Cup.EMPTY);
    }

    @Override
    public String getDescriptionId() {
        return getOrCreateDescriptionId();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal(kind == Kind.PLATE ? "Set it on a table and put a dish on it" : "Set it on a table and pour a drink in it")
            .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("The town's cafés, taverns and restaurants serve on these").withStyle(ChatFormatting.DARK_GRAY));
    }
}
