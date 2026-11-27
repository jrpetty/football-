package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The orchard [batchE]: a fenced square of grass by the fields (its own building, orchard.txt) with four oaks
 * in it, planted by the farmers from the stores' oak saplings (an oak is the tree that bears apples), and
 * a little bone meal on them now and then if the stores can spare it.
 *
 * <p>In the last week of the town's year (its autumn: TownCalendar's twenty-eight days) the farmers pick it.
 * An oak gives up its apples only as its leaves come down, one leaf in two hundred, so a tree is picked the
 * way an orchard of oaks has to be: its crown taken down leaf by leaf and what falls gathered (the apples,
 * the saplings, the sticks), then its trunk felled for the stores' timber, and a new oak put straight into
 * the same ground from the saplings it dropped. Once a year each. Any apple lying in the grass between
 * times is picked up and taken in with the rest. The apples go to the stores, for the café's cider (Cafe)
 * and anybody's lunch.
 */
public final class Orchard {

    private Orchard() {}

    /** A farming town of this many plants an orchard. */
    static final int FROM = 14;
    /** The four trees, in the drawing: two blocks in from the fence each way. */
    static final int[][] TREES = { { -2, 2 }, { 2, 2 }, { -2, -2 }, { 2, -2 } };
    /** The picking week: the town's year's last seven days. */
    static final int PICKING_FROM = TownCalendar.YEAR_DAYS - 7;
    /** How far round a trunk its crown spreads, and how high. */
    static final int CROWN = 3, CROWN_HIGH = 12;

    static void resetForTests() {
    }

    static List<Ledger.Building> orchards(UUID village) {
        List<Ledger.Building> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(TownLook.ORCHARD)) out.add(b);
        return out;
    }

    static BlockPos tree(Ledger.Building b, int i) {
        return TownLook.cell(b, TREES[i][0], 0, TREES[i][1]);
    }

    /** Is it the picking week? */
    static boolean picking(UUID village, long day) {
        return TownLook.dayOfYear(village, day) >= PICKING_FROM;
    }

    /** A grown tree on this spot: a trunk standing on it. */
    static boolean grown(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).is(BlockTags.LOGS) && level.getBlockState(p.above()).is(BlockTags.LOGS);
    }

    /** The day the tree on this spot was last picked, or a long time ago. */
    static long picked(UUID village, Ledger.Building b, int i) {
        String s = Ledger.note(village, "orchard/" + b.anchor().asLong() + "/" + i);
        try {
            return s == null || s.isEmpty() ? -1000L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1000L;
        }
    }

    /** One visit to the orchard: an apple off the grass, a tree picked in its week, a sapling fed, or one planted. */
    static boolean tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        for (Ledger.Building b : orchards(id)) {
            if (!Land.areaLoaded(level, b.anchor(), 8)) continue;
            if (gather(level, v, b) > 0) return true;
            for (int i = 0; i < TREES.length; i++) {
                BlockPos p = tree(b, i);
                if (grown(level, p) && picking(id, day) && day - picked(id, b, i) >= PICKING_FROM) {
                    if (!TownJobs.atWork(level, v, "orchard", p, "picking the orchard", AssistantEntity.StationTask.FARM)) return false;
                    pick(level, v, b, i, day);
                    return true;
                }
            }
            for (int i = 0; i < TREES.length; i++) {
                BlockPos p = tree(b, i);
                BlockState here = level.getBlockState(p);
                if (here.getBlock() instanceof SaplingBlock sapling) {
                    if (Market.stock(level, id, s -> s.is(Items.BONE_MEAL)) < 8) continue;
                    if (!TownJobs.atWork(level, v, "orchard", p, "tending the orchard's young trees", AssistantEntity.StationTask.FARM)) return false;
                    if (!Crafts.take(level, v, s -> s.is(Items.BONE_MEAL), 1)) return false;
                    if (sapling.isValidBonemealTarget(level, p, here) && sapling.isBonemealSuccess(level, level.getRandom(), p, here)) {
                        sapling.performBonemeal(level, level.getRandom(), p, here);
                    }
                    level.levelEvent(1505, p, 15);
                    return true;
                }
                if (grown(level, p) || !TownLook.open(here) || !level.getBlockState(p.below()).is(BlockTags.DIRT)) continue;
                if (Market.stock(level, id, s -> s.is(Items.OAK_SAPLING)) < 1) break;
                if (!TownJobs.atWork(level, v, "orchard", p, "planting the orchard", AssistantEntity.StationTask.FARM)) return false;
                if (plant(level, v, p)) {
                    String first = Ledger.note(id, "orchard.first");
                    if (first == null || first.isEmpty()) {
                        Ledger.note(id, "orchard.first", Long.toString(day));
                        Villages.tell(id, day, "the first oak went into the orchard");
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** An oak sapling out of the stores into this spot. */
    static boolean plant(ServerLevel level, Villages.Village v, BlockPos p) {
        if (!Crafts.take(level, v, s -> s.is(Items.OAK_SAPLING), 1)) return false;
        if (!level.getBlockState(p).isAir()) level.destroyBlock(p, false);
        level.setBlock(p, Blocks.OAK_SAPLING.defaultBlockState(), 3);
        level.playSound(null, p, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    /** The apples lying in the orchard's grass, into the stores. Returns how many. */
    static int gather(ServerLevel level, Villages.Village v, Ledger.Building b) {
        AABB box = new AABB(b.anchor()).inflate(5, 3, 5);
        List<ItemEntity> lying = level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && e.getItem().is(Items.APPLE));
        if (lying.isEmpty()) return 0;
        if (!TownJobs.atWork(level, v, "orchard", lying.get(0).blockPosition(), "picking up windfalls", AssistantEntity.StationTask.FARM)) return 0;
        int n = 0;
        for (ItemEntity e : lying) {
            n += e.getItem().getCount();
            Crafts.store(level, v, e.getItem().copy());
            e.discard();
        }
        return n;
    }

    /**
     * A tree picked: its crown taken down leaf by leaf, everything that falls into the stores, its trunk
     * felled into the stores, and an oak sapling (one it dropped, else the stores') into the ground where it
     * stood. Returns {leaves, apples, logs, replanted 0/1}.
     */
    static int[] pick(ServerLevel level, Villages.Village v, Ledger.Building b, int i, long day) {
        BlockPos foot = tree(b, i);
        int leaves = 0, apples = 0, logs = 0;
        List<ItemStack> fell = new ArrayList<>();
        for (BlockPos q : BlockPos.betweenClosed(foot.offset(-CROWN, 0, -CROWN), foot.offset(CROWN, CROWN_HIGH, CROWN))) {
            BlockState st = level.getBlockState(q);
            if (!(st.getBlock() instanceof LeavesBlock) || st.getValue(LeavesBlock.PERSISTENT)) continue;
            fell.addAll(Block.getDrops(st, level, q, null));
            level.removeBlock(q, false);
            leaves++;
        }
        // The trunk, and any bough a big oak threw out sideways (the neighbours' trunks stand four away).
        for (BlockPos q : BlockPos.betweenClosed(foot.offset(-CROWN, 0, -CROWN), foot.offset(CROWN, CROWN_HIGH, CROWN))) {
            BlockState st = level.getBlockState(q);
            if (!st.is(BlockTags.LOGS)) continue;
            if ((q.getX() != foot.getX() || q.getZ() != foot.getZ()) && q.getY() - foot.getY() < 3) continue;   // a bough is high up
            fell.addAll(Block.getDrops(st, level, q, null));
            level.removeBlock(q, false);
            logs++;
        }
        boolean replanted = false;
        for (ItemStack s : fell) {
            if (s.isEmpty()) continue;
            if (!replanted && s.is(Items.OAK_SAPLING) && level.getBlockState(foot.below()).is(BlockTags.DIRT)) {
                s.shrink(1);
                level.setBlock(foot, Blocks.OAK_SAPLING.defaultBlockState(), 3);
                replanted = true;
            }
            if (s.is(Items.APPLE)) apples += s.getCount();
            if (!s.isEmpty()) Crafts.store(level, v, s);
        }
        if (!replanted && level.getBlockState(foot.below()).is(BlockTags.DIRT)) replanted = plant(level, v, foot);
        Ledger.note(v.id(), "orchard/" + b.anchor().asLong() + "/" + i, Long.toString(day));
        level.playSound(null, foot, SoundEvents.AXE_STRIP, SoundSource.BLOCKS, 0.8F, 1.0F);
        if (apples > 0) Villages.tell(v.id(), day, "the orchard was picked: " + apples + (apples == 1 ? " apple" : " apples") + " for the stores");
        return new int[]{ leaves, apples, logs, replanted ? 1 : 0 };
    }

    /** {grown trees, saplings} in the village's orchards. */
    static int[] count(ServerLevel level, UUID village) {
        int grown = 0, young = 0;
        for (Ledger.Building b : orchards(village)) {
            if (!level.isLoaded(b.anchor())) continue;
            for (int i = 0; i < TREES.length; i++) {
                BlockPos p = tree(b, i);
                if (grown(level, p)) grown++;
                else if (level.getBlockState(p).getBlock() instanceof SaplingBlock) young++;
            }
        }
        return new int[]{ grown, young };
    }

    static String line(ServerLevel level, Villages.Village v) {
        if (orchards(v.id()).isEmpty()) return "none yet";
        int[] c = count(level, v.id());
        long day = level.getDayTime() / 24000L;
        int doy = TownLook.dayOfYear(v.id(), day);
        return c[0] + " trees grown, " + c[1] + " saplings; " + (picking(v.id(), day) ? "the picking week"
            : "picking in " + (PICKING_FROM - doy) + (PICKING_FROM - doy == 1 ? " day" : " days"));
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the orchard's rounds so many times over (planting, feeding, picking in its week). Returns how many did something. */
    public static int roundsForTests(ServerLevel level, Villages.Village v, int rounds) {
        int n = 0;
        for (int i = 0; i < rounds; i++) if (tick(level, v)) n++;
        return n;
    }

    /** Tests: the orchard's tree spots, in the world. */
    public static List<BlockPos> treesForTests(UUID village) {
        List<BlockPos> out = new ArrayList<>();
        for (Ledger.Building b : orchards(village)) for (int i = 0; i < TREES.length; i++) out.add(tree(b, i));
        return out;
    }

    /** Tests: each sapling in the orchard grown into its tree, as the game grows one. Returns how many grew. */
    public static int growForTests(ServerLevel level, UUID village) {
        int n = 0;
        for (BlockPos p : treesForTests(village)) {
            for (int k = 0; k < 20 && level.getBlockState(p).getBlock() instanceof SaplingBlock s; k++) {
                s.advanceTree(level, p, level.getBlockState(p), level.getRandom());
            }
            if (grown(level, p)) n++;
        }
        return n;
    }

    /** Tests: every grown tree picked now, whatever the week. Returns {leaves, apples, logs, replanted}. */
    public static int[] pickForTests(ServerLevel level, Villages.Village v) {
        int[] all = new int[4];
        long day = level.getDayTime() / 24000L;
        for (Ledger.Building b : orchards(v.id())) {
            for (int i = 0; i < TREES.length; i++) {
                if (!grown(level, tree(b, i))) continue;
                int[] got = pick(level, v, b, i, day);
                for (int k = 0; k < 4; k++) all[k] += got[k];
            }
        }
        return all;
    }
}
