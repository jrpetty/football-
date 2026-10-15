package com.jrpetty.mcassistant.item;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [fields] The copper sickle: three copper ingots drawn into a curved blade on a stick, two hundred cuts in it. Swept
 * through a ripe crop it takes the ripe crops of the three-by-three round it in the one swing. The swing's drops are
 * gathered together before anything is sown again, so that a wheat that happened to give no seed of its own is sown from
 * its neighbours' spare (as a reaper sows from the apron, not plant by plant); what is left over is the harvest. The
 * farmer with one reaps three by three (FieldTools.reap); a player breaking a ripe crop with it reaps the ripe crops
 * round it, and the one it broke is sown again too.
 */
public class SickleItem extends TieredItem {

    /** The crop each ripe crop is sown again with (others: the crop's own seed, as the pick-block gives it). */
    static final Map<Block, Item> SEED_OF = Map.of(Blocks.WHEAT, Items.WHEAT_SEEDS, Blocks.CARROTS, Items.CARROT,
        Blocks.POTATOES, Items.POTATO, Blocks.BEETROOTS, Items.BEETROOT_SEEDS);

    public SickleItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    /** A crop ready for the blade. */
    public static boolean ripe(BlockState st) {
        return st.getBlock() instanceof CropBlock crop && crop.isMaxAge(st);
    }

    /** What this crop is sown with, or null if it is not one sown from a seed. */
    @Nullable
    public static Item seedOf(Level level, BlockPos at, Block crop) {
        Item s = SEED_OF.get(crop);
        if (s != null || !(crop instanceof CropBlock)) return s;
        ItemStack clone = crop.getCloneItemStack(level, at, crop.defaultBlockState());
        return clone.isEmpty() ? null : clone.getItem();
    }

    /** The ripe crops of the three-by-three round this one (a level up or down), not counting it. */
    public static List<BlockPos> ripeAround(Level level, BlockPos centre) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue;
                    BlockPos p = centre.offset(dx, dy, dz);
                    if (ripe(level.getBlockState(p))) out.add(p.immutable());
                }
            }
        }
        return out;
    }

    /** A square cut and not yet sown again (the swing's seed did not run to it): where, and what grew there. */
    public record Bare(BlockPos at, Block crop) {}

    /** One swing: how many were cut, the squares the swing's own seed did not run to, and the harvest left over. */
    public record Swing(int cut, List<Bare> bare, List<ItemStack> drops) {}

    /**
     * The ripe crops here cut in one swing: each one's drops (as the game gives them to whoever cut it with this blade)
     * gathered together, then every square on farmland sown again from them, and the rest handed back.
     */
    public static Swing reap(ServerLevel level, List<BlockPos> crops, Entity who, ItemStack blade) {
        List<ItemStack> pool = new ArrayList<>();
        Map<BlockPos, Block> cut = new LinkedHashMap<>();
        for (BlockPos p : crops) {
            BlockState st = level.getBlockState(p);
            if (!ripe(st)) continue;
            pool.addAll(Block.getDrops(st, level, p, null, who, blade));
            level.levelEvent(2001, p, Block.getId(st));                    // the crop's own breaking puff
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            level.gameEvent(who, GameEvent.BLOCK_DESTROY, p);
            cut.put(p.immutable(), st.getBlock());
        }
        List<Bare> bare = new ArrayList<>();
        for (Map.Entry<BlockPos, Block> e : cut.entrySet()) {
            if (!onFarmland(level, e.getKey())) continue;                    // nothing to sow it in
            if (!sowFrom(level, pool, e.getKey(), e.getValue())) bare.add(new Bare(e.getKey(), e.getValue()));
        }
        pool.removeIf(ItemStack::isEmpty);
        return new Swing(cut.size(), bare, pool);
    }

    /** Is this an empty square of farmland to sow? */
    public static boolean onFarmland(Level level, BlockPos at) {
        return level.getBlockState(at).isAir() && level.getBlockState(at.below()).getBlock() instanceof FarmBlock;
    }

    /** This square sown again with its crop, one seed out of these. False if there is no seed for it among them. */
    public static boolean sowFrom(Level level, List<ItemStack> pool, BlockPos at, Block crop) {
        Item seed = seedOf(level, at, crop);
        if (seed == null || !onFarmland(level, at)) return false;
        for (ItemStack s : pool) {
            if (s.isEmpty() || !s.is(seed)) continue;
            s.shrink(1);
            level.setBlock(at, crop.defaultBlockState(), 3);
            return true;
        }
        return false;
    }

    /**
     * A player's swing through a ripe crop: the ripe crops round it cut too, the lot sown again from the swing's own seed
     * (the one it broke as well, its own drops still to fall), then from the player's pockets; the harvest dropped where
     * it swung.
     */
    @Override
    public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity miner) {
        if (!(level instanceof ServerLevel server) || !ripe(state)) return super.mineBlock(stack, level, state, pos, miner);
        Swing swing = reap(server, ripeAround(level, pos), miner, stack);
        List<ItemStack> pool = new ArrayList<>(swing.drops());
        List<Bare> bare = new ArrayList<>(swing.bare());
        if (onFarmland(level, pos)) bare.add(new Bare(pos.immutable(), state.getBlock()));
        for (Bare b : bare) {
            if (sowFrom(level, pool, b.at(), b.crop())) continue;
            if (!(miner instanceof Player p)) continue;
            Item seed = seedOf(level, b.at(), b.crop());
            if (seed == null) continue;
            int slot = p.getInventory().findSlotMatchingItem(new ItemStack(seed));
            if (slot < 0) continue;
            p.getInventory().removeItem(slot, 1);
            level.setBlock(b.at(), b.crop().defaultBlockState(), 3);
        }
        for (ItemStack d : pool) if (!d.isEmpty()) Block.popResource(level, pos, d);
        if (swing.cut() > 0) level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.PLAYERS, 0.8F, 1.3F);
        stack.hurtAndBreak(1, miner, EquipmentSlot.MAINHAND);
        return true;
    }

    /** Grass and the like give way to the blade quickly; anything else it is no tool for. */
    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        return state.getBlock() instanceof CropBlock || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
            || state.is(Blocks.HAY_BLOCK) ? 4.0F : 1.0F;
    }
}
