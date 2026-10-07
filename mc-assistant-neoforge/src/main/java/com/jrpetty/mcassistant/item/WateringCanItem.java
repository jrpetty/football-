package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.block.RainBarrelBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * [fields] The copper watering can: five copper ingots beaten into a can with a long spout and a rose on it. It holds
 * sixteen waterings (the bar under it, blue, is the water in it), filled at any water, at the well, a cauldron or a rain
 * barrel. Tipped over a crop it waters the three-by-three round it: the farmland soaked through, and each crop given a
 * few growth ticks more (two of the game's own, each a chance to grow, as the light and the soil allow: a gentle
 * help, nothing like bone meal). In a drought a soaked field grows at its full pace again (Droughts). Saplings and the
 * flowers of a garden or a window box are watered the same way. The folk's use of it is FieldTools.
 */
public class WateringCanItem extends Item {

    /** Waterings a can holds. */
    public static final int CAPACITY = 16;
    /** The game's growth ticks a watered crop is given. */
    public static final int GROWTH_TICKS = 2;
    private static final int WATER_BLUE = 0x3F76E4;

    public WateringCanItem(Properties properties) {
        super(properties);
    }

    public static int water(ItemStack can) {
        return Mth.clamp(can.getOrDefault(FieldItems.WATER.get(), 0), 0, CAPACITY);
    }

    public static void setWater(ItemStack can, int n) {
        can.set(FieldItems.WATER.get(), Mth.clamp(n, 0, CAPACITY));
    }

    /** Filled to the brim. */
    public static void fill(ItemStack can) {
        setWater(can, CAPACITY);
    }

    // ------------------------------------------------------------------ the bar

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13.0F * water(stack) / CAPACITY);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return WATER_BLUE;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        int w = water(stack);
        lines.add(Component.literal(w == 0 ? "Empty: fill it at water or a rain barrel" : w + " of " + CAPACITY + " waterings")
            .withStyle(w == 0 ? ChatFormatting.GRAY : ChatFormatting.BLUE));
    }

    // ------------------------------------------------------------------ a player's use

    /** At water in the open: filled, the way a bottle is (the water stays). */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack can = player.getItemInHand(hand);
        if (water(can) >= CAPACITY) return InteractionResultHolder.pass(can);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK || !level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) return InteractionResultHolder.pass(can);
        if (!level.isClientSide) {
            fill(can);
            level.playSound(null, player.blockPosition(), SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 0.8F, 1.3F);
            level.gameEvent(player, GameEvent.FLUID_PICKUP, hit.getBlockPos());
        }
        return InteractionResultHolder.sidedSuccess(can, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        BlockState st = level.getBlockState(pos);
        ItemStack can = ctx.getItemInHand();
        Player player = ctx.getPlayer();
        // A cauldron of water: a level of it into the can. (A rain barrel fills it itself: RainBarrelBlock.)
        if (st.is(Blocks.WATER_CAULDRON) && water(can) < CAPACITY) {
            if (!level.isClientSide) {
                LayeredCauldronBlock.lowerFillLevel(st, level, pos);
                fill(can);
                level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.8F, 1.3F);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (st.getBlock() instanceof RainBarrelBlock) return InteractionResult.PASS;
        if (!waterable(level, pos)) return InteractionResult.PASS;
        if (water(can) <= 0) {
            if (player != null && !level.isClientSide) {
                player.displayClientMessage(Component.literal("The can is empty: fill it at water or a rain barrel."), true);
            }
            return InteractionResult.FAIL;
        }
        if (level instanceof ServerLevel server) {
            Poured p = pour(server, pos);
            if (p.any()) {
                setWater(can, water(can) - 1);
                if (player != null) player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(this));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // ------------------------------------------------------------------ the watering (the folk's too)

    /** What a watering did: the farmland soaked, the plants given their growth ticks, and how many of them grew. */
    public record Poured(int soaked, int plants, int grew) {
        public boolean any() {
            return soaked > 0 || plants > 0;
        }
    }

    /** Is this something a can waters: farmland, a crop or a stem, a sapling, a flower, a potted flower? */
    public static boolean waterable(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        return st.getBlock() instanceof FarmBlock || plant(st) || st.is(BlockTags.FLOWER_POTS)
            || level.getBlockState(pos.above()).getBlock() instanceof CropBlock;
    }

    private static boolean plant(BlockState st) {
        return st.getBlock() instanceof CropBlock || st.getBlock() instanceof StemBlock || st.getBlock() instanceof SaplingBlock
            || st.is(BlockTags.FLOWERS);
    }

    /**
     * The can tipped over this spot: the three-by-three round it (a level up and down) soaked and given its growth.
     * Farmland is wetted through; each growing crop, stem and sapling has the game's own growth tick twice over
     * (each a chance to grow, as its light, its soil and the season allow, and a dry field in a drought only once it is
     * wet). Water drips from the rose.
     */
    public static Poured pour(ServerLevel level, BlockPos at) {
        BlockState here = level.getBlockState(at);
        // The ground's height: the farmland itself, or under the plant that was aimed at.
        BlockPos ground = here.getBlock() instanceof FarmBlock || !plant(here) && !here.is(BlockTags.FLOWER_POTS) ? at : at.below();
        int soaked = 0, plants = 0, grew = 0;
        var rnd = level.getRandom();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos g = ground.offset(dx, dy, dz);
                    BlockState gs = level.getBlockState(g);
                    if (gs.getBlock() instanceof FarmBlock && gs.getValue(FarmBlock.MOISTURE) < FarmBlock.MAX_MOISTURE) {
                        level.setBlock(g, gs.setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), 2);
                        soaked++;
                    }
                    BlockPos p = g.above();
                    BlockState ps = level.getBlockState(p);
                    if (!plant(ps)) continue;
                    boolean growing = ps.getBlock() instanceof CropBlock crop ? !crop.isMaxAge(ps)
                        : ps.getBlock() instanceof StemBlock || ps.getBlock() instanceof SaplingBlock;
                    if (!growing) {
                        if (ps.is(BlockTags.FLOWERS)) plants++;                    // a garden's flowers: watered, and that is all
                        continue;
                    }
                    plants++;
                    for (int i = 0; i < GROWTH_TICKS; i++) {
                        BlockState now = level.getBlockState(p);
                        if (!plant(now)) break;
                        now.randomTick(level, p, rnd);
                    }
                    if (level.getBlockState(p) != ps) grew++;
                }
            }
        }
        // A potted flower in a window box: watered.
        if (here.is(BlockTags.FLOWER_POTS) && here.getBlock() != Blocks.FLOWER_POT) plants++;
        if (soaked > 0 || plants > 0) {
            level.sendParticles(ParticleTypes.SPLASH, ground.getX() + 0.5, ground.getY() + 1.2, ground.getZ() + 0.5, 30, 1.0, 0.15, 1.0, 0.15);
            level.sendParticles(ParticleTypes.FALLING_WATER, ground.getX() + 0.5, ground.getY() + 1.9, ground.getZ() + 0.5, 14, 0.9, 0.2, 0.9, 0.0);
            level.playSound(null, ground, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.45F, 1.7F);
            level.gameEvent(null, GameEvent.FLUID_PLACE, ground);
        }
        return new Poured(soaked, plants, grew);
    }

    /** Does a crop on this square want water (its farmland not wet through, or it still growing)? Used to pick a patch. */
    public static boolean thirsty(Level level, BlockPos farmland) {
        BlockState gs = level.getBlockState(farmland);
        return gs.getBlock() instanceof FarmBlock && gs.getValue(FarmBlock.MOISTURE) < FarmBlock.MAX_MOISTURE;
    }
}
