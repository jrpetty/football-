package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.VillageSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [life28] A town's own camp, taken up while its ground is levelled and pitched again on the level ground.
 *
 * <p>A village founded at once (the console's {@code spawnat}: the party stood up, its camp pitched and its stores set
 * down, and only then its ground levelled as {@code /village level} levels a town already standing) had its ground read
 * with the founders' own beds and stores chest on it, and the levelling took them for somebody's building: some six
 * hundred and fifty columns of the square round them were left as the world made them, the square came out a pit, and
 * the wall round it was laid on the broken ground. A founding at a board pitches its camp after the camp's ground is
 * level (Founding.found); this does the same for a town levelled where it stands: its camp beds (every bed this near the
 * heart is the camp's: VillageSpawner.CAMP_REACH) and its stores chests by the heart, outside any building, are taken up
 * before the ground is read (the beds counted, the chests' goods kept), and when the camp's ground is level the camp is
 * pitched again round the heart on it, the stores' goods back in their chests.
 */
public final class FoundingCamp {

    private FoundingCamp() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** How near the heart a village's stores chest is the founding's (VillageSpawner: the first at it, the rest two out). */
    static final int STORES = 3;
    /** How far up or down from the heart the camp is looked for. */
    static final int RISE = 6;

    /**
     * The village's own camp taken up before its ground is read: its beds and its stores chests by the heart, outside any
     * building. What was taken (beds by kind, each chest's goods) for {@link #pitch}, or null if it had none.
     */
    @Nullable
    static CompoundTag strike(ServerLevel level, Villages.Village v, BlockPos heart) {
        int reach = VillageSpawner.CAMP_REACH + 1;
        ListTag beds = new ListTag(), chests = new ListTag();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = heart.getX() + dx, z = heart.getZ() + dz;
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                for (int y = heart.getY() - RISE; y <= heart.getY() + RISE; y++) {
                    m.set(x, y, z);
                    BlockState s = level.getBlockState(m);
                    if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.FOOT) {
                        if (TownBlocks.buildingAt(level, v, m) != null) continue;
                        BlockPos head = m.relative(s.getValue(BedBlock.FACING));
                        level.setBlock(head, Blocks.AIR.defaultBlockState(), 2 | 16);
                        level.setBlock(m.immutable(), Blocks.AIR.defaultBlockState(), 2 | 16);
                        CompoundTag b = new CompoundTag();
                        b.putString("Bed", BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
                        beds.add(b);
                    } else if (s.getBlock() instanceof ChestBlock && Math.abs(dx) <= STORES && Math.abs(dz) <= STORES) {
                        BlockEntity be = level.getBlockEntity(m);
                        if (be == null || !ZoneChests.isVillageStore(be) || !(be instanceof Container c)) continue;
                        if (TownBlocks.buildingAt(level, v, m) != null) continue;
                        NonNullList<ItemStack> goods = NonNullList.withSize(c.getContainerSize(), ItemStack.EMPTY);
                        for (int i = 0; i < c.getContainerSize(); i++) goods.set(i, c.getItem(i).copy());
                        CompoundTag t = new CompoundTag();
                        ContainerHelper.saveAllItems(t, goods, level.registryAccess());
                        t.putInt("Dx", dx);
                        t.putInt("Dz", dz);
                        chests.add(t);
                        c.clearContent();
                        level.setBlock(m.immutable(), Blocks.AIR.defaultBlockState(), 2 | 16);
                    }
                }
            }
        }
        if (beds.isEmpty() && chests.isEmpty()) return null;
        CompoundTag camp = new CompoundTag();
        camp.put("Beds", beds);
        camp.put("Chests", chests);
        LOG.info("[MCA-FOUND] [life28] {}'s own camp taken up while its ground is levelled: {} beds, {} stores chests",
            Villages.name(v.id()), beds.size(), chests.size());
        return camp;
    }

    /** The camp pitched again round the heart on the levelled ground: the stores' chests, then the beds. */
    static void pitch(ServerLevel level, @Nullable java.util.UUID village, BlockPos heartColumn, CompoundTag camp) {
        BlockPos heart = standing(level, heartColumn.getX(), heartColumn.getZ());
        ListTag chests = camp.getList("Chests", Tag.TAG_COMPOUND);
        List<ItemStack> spare = new ArrayList<>();
        int set = 0;
        for (int i = 0; i < chests.size(); i++) {
            CompoundTag t = chests.getCompound(i);
            NonNullList<ItemStack> goods = NonNullList.withSize(27, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(t, goods, level.registryAccess());
            BlockPos at = standing(level, heart.getX() + t.getInt("Dx"), heart.getZ() + t.getInt("Dz"));
            Container c = chestAt(level, at, heart);
            if (c == null) {
                for (ItemStack s : goods) if (!s.isEmpty()) spare.add(s);
                continue;
            }
            for (int k = 0; k < goods.size() && k < c.getContainerSize(); k++) c.setItem(k, goods.get(k));
            set++;
        }
        ListTag beds = camp.getList("Beds", Tag.TAG_COMPOUND);
        int laid = VillageSpawner.pitchCamp(level, heart, beds.size());
        // Beds the level ground had no room for (or goods with no chest) go into the stores, to be laid as houses go up.
        for (int i = laid; i < beds.size(); i++) {
            Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(beds.getCompound(i).getString("Bed")));
            if (b instanceof BedBlock) spare.add(new ItemStack(b.asItem()));
        }
        if (!spare.isEmpty()) {
            Container c = null;
            int[][] ring = { {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}, {0, 0} };
            for (ItemStack s : spare) {
                ItemStack left = s;
                for (int r = 0; r < ring.length && !left.isEmpty(); r++) {
                    if (c == null) c = chestAt(level, standing(level, heart.getX() + ring[r][0], heart.getZ() + ring[r][1]), heart);
                    if (c == null) continue;
                    left = put(c, left);
                    if (!left.isEmpty()) c = null;
                }
                if (!left.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, heart.getX() + 0.5, heart.getY() + 0.5, heart.getZ() + 0.5, left);
            }
        }
        LOG.info("[MCA-FOUND] [life28] {}'s camp pitched again on the level ground at {}: {} of {} beds, {} stores chests",
            village == null ? "the town" : Villages.name(village), heart.toShortString(), laid, beds.size(), set);
    }

    /** A stores chest at this spot (one put down, named the village's), or the one already there; null if no room. */
    @Nullable
    private static Container chestAt(ServerLevel level, BlockPos at, BlockPos heart) {
        if (Math.abs(at.getY() - heart.getY()) > 1) return null;
        BlockState s = level.getBlockState(at);
        if (s.getBlock() instanceof ChestBlock && level.getBlockEntity(at) instanceof Container c) return c;
        if (!s.canBeReplaced() || !level.getFluidState(at).isEmpty()) return null;
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        ZoneChests.mark(level, at);
        return level.getBlockEntity(at) instanceof Container c ? c : null;
    }

    private static ItemStack put(Container c, ItemStack s) {
        ItemStack left = s.copy();
        for (int i = 0; i < c.getContainerSize() && !left.isEmpty(); i++) {
            ItemStack in = c.getItem(i);
            if (in.isEmpty()) {
                c.setItem(i, left);
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameComponents(in, left) && in.getCount() < in.getMaxStackSize()) {
                int n = Math.min(left.getCount(), in.getMaxStackSize() - in.getCount());
                in.grow(n);
                left.shrink(n);
            }
        }
        c.setChanged();
        return left;
    }

    /** The free ground-level spot at this column. */
    private static BlockPos standing(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }
}
