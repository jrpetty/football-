package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [biz30] The town's buildings made good once, inside, in the spirit of SquareMend: what a town built before its builders
 * cleared their lots and laid their floors as they do now, or what grew, fell or was left in it since, put right building
 * by building on the town's first land rounds:
 * <ul>
 * <li>grass, flowers, saplings, a wild tree's leaves and snow standing in a room taken up (into the stores where they are
 *     a thing to keep);</li>
 * <li>a floor of grass or bare earth, and a hole in a floor, laid in the building's own floor (what most of its laid floor
 *     is made of);</li>
 * <li>the ground standing up into a room (a bank, a hummock) dug out, and water or lava lying in one taken up;</li>
 * <li>a lump of earth or rough stone nobody drew left on its own in a room (a builder's pillar's last block) taken down
 *     (never a plank or a log: a shelf, a post);</li>
 * <li>a workstation laid as a block of a wall, floor or roof (a crafting table for a brick) taken out into the stores and
 *     the wall's own block laid in its place (a chest, a barrel or a furnace, with somebody's things in it, left);</li>
 * <li>scaffolding left round it struck.</li>
 * </ul>
 * Never a block a player put there (PlayerBlocks), never a building still going up, a player's own building (its floors
 * are its owner's), a building a player keeps a shop in, a folk's own house, nor the drawing's own cells that are as drawn. Done once a town (Ledger note
 * "buildings.mended"), a few buildings a round, and what it did written in the log and the note.
 */
public final class BuildingMend {

    private BuildingMend() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    public static final String DONE = "buildings.mended";
    /** Buildings a round. */
    static final int PER_ROUND = 2;

    /** What has been mended so far, by kind, per town (and where the round has got to). */
    private static final class Job {
        final List<Ledger.Building> todo;
        int cursor;
        final Map<String, Integer> fixed = new java.util.TreeMap<>();

        Job(List<Ledger.Building> todo) {
            this.todo = todo;
        }
    }

    private static final Map<UUID, Job> JOBS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        JOBS.clear();
    }

    /** From the land's round for each town (Land): once, its buildings made good inside. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        String done = Ledger.note(id, DONE);
        if (done != null && !done.isEmpty()) return;
        if (Founding.near(level, v.centre(), 64) || Founding.settling(level, v.centre(), 64)) return;
        Job job = JOBS.computeIfAbsent(id, k -> new Job(new ArrayList<>(Ledger.buildings(id))));
        int n = 0;
        while (job.cursor < job.todo.size() && n < PER_ROUND) {
            Ledger.Building b = job.todo.get(job.cursor);
            if (!Land.areaLoaded(level, b.anchor(), 12)) return;             // come back to it when its ground is here
            job.cursor++;
            n++;
            mend(level, v, b, job.fixed);
        }
        if (job.cursor >= job.todo.size()) {
            String line = "mended " + job.todo.size() + " buildings: " + job.fixed;
            Ledger.note(id, DONE, line);
            JOBS.remove(id);
            LOG.info("[MCA-BUILD] [biz30] {}: its buildings made good inside once: {}", Villages.name(id), line);
        }
    }

    /** Tests: the whole mend now; what it did. */
    public static Map<String, Integer> nowForTests(ServerLevel level, Villages.Village v) {
        Map<String, Integer> fixed = new java.util.TreeMap<>();
        for (Ledger.Building b : Ledger.buildings(v.id())) mend(level, v, b, fixed);
        Ledger.note(v.id(), DONE, "mended for a test: " + fixed);
        return fixed;
    }

    /** May the mend touch this building at all. */
    static boolean mayMend(Villages.Village v, Ledger.Building b) {
        UUID id = v.id();
        if (Ledger.raising(id, b.anchor()) || b.structure().equals("fortify")) return false;
        if (HousingMarket.isPrivate(id, b.anchor()) || HomeMaking.householdsOwn(id, b.anchor())) return false;   // a folk's own house
        net.minecraft.nbt.CompoundTag prop = PropertyMarket.get(id, PropertyMarket.keyAt(b.anchor()));
        if (prop != null && "player".equals(prop.getString("ownerKind"))) return false;   // a player's own building: its owner's to see to
        // [life28's caution] a player's shop in it (its counter, its shelves, its chests are the player's to arrange)
        int r = BuildGoal.halfOf(b.structure()) + 1;
        for (PlayerShops.Shop sh : PlayerShops.all(id)) {
            if (Math.abs(sh.anchor.getX() - b.anchor().getX()) <= r && Math.abs(sh.anchor.getZ() - b.anchor().getZ()) <= r
                    && Math.abs(sh.anchor.getY() - b.anchor().getY()) <= 8) return false;
        }
        return true;
    }

    /** A lump the mend may take down when it stands alone in a room: earth or rough stone, never planks, logs or anybody's
     *  fittings (a lone plank may be a shelf, a log a post: SquareDress learnt that the hard way). */
    static boolean lump(BlockState st) {
        return Terraform.earth(st) && !st.is(Blocks.BEDROCK) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE)
            || st.is(Blocks.COBBLED_DEEPSLATE) || st.is(Blocks.GRAVEL);
    }

    /** One building made good inside. Adds what it did to {@code fixed}. */
    static void mend(ServerLevel level, Villages.Village v, Ledger.Building b, Map<String, Integer> fixed) {
        if (!mayMend(v, b)) return;
        String design = TownBlocks.designOf(v.id(), b);
        List<BuildGoal.Placement> plan = BuildGoal.plan(design, b.anchor(), b.facing(), 13);
        Map<Long, BuildGoal.Placement> mine = new HashMap<>();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (BuildGoal.Placement p : plan) {
            if (p.part() == BuildGoal.Part.CLEAR) continue;
            mine.put(p.pos().asLong(), p);
            minY = Math.min(minY, p.pos().getY());
            maxY = Math.max(maxY, p.pos().getY());
        }
        if (mine.isEmpty()) return;
        boolean open = BuildingAudit.OPEN.contains(b.structure()) || BuildingAudit.OPEN.contains(design);
        // Its floor: what most of its laid floor cells are.
        Map<Block, Integer> floors = new HashMap<>();
        Map<Blueprints.Style, Map<Block, Integer>> byStyle = new HashMap<>();
        for (BuildGoal.Placement p : mine.values()) {
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            BlockState st = level.getBlockState(p.pos());
            if (Restore.judge(st, p) != null || BuildingAudit.fixture(st)) continue;   // as drawn: what its builders laid
            byStyle.computeIfAbsent(p.style(), k -> new HashMap<>()).merge(st.getBlock(), 1, Integer::sum);
            if (p.pos().getY() == minY && p.style() != Blueprints.Style.SOIL && !Restore.earthy(st)) floors.merge(st.getBlock(), 1, Integer::sum);
        }
        Block floor = most(floors);
        Set<Long> footed = new HashSet<>();
        for (BuildGoal.Placement p : mine.values()) if (p.pos().getY() == minY) footed.add(BlockPos.asLong(p.pos().getX(), 0, p.pos().getZ()));
        Map<Long, Integer> ceiling = new HashMap<>();
        for (BuildGoal.Placement p : mine.values()) {
            if (p.pos().getY() < minY + 3 || p.part() != BuildGoal.Part.BLOCK && p.part() != BuildGoal.Part.WINDOW) continue;
            ceiling.merge(BlockPos.asLong(p.pos().getX(), 0, p.pos().getZ()), p.pos().getY(), Math::min);
        }
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (long c : footed) {
            x0 = Math.min(x0, BlockPos.getX(c)); x1 = Math.max(x1, BlockPos.getX(c));
            z0 = Math.min(z0, BlockPos.getZ(c)); z1 = Math.max(z1, BlockPos.getZ(c));
        }
        for (int x = x0 + 1; x < x1; x++) {
            for (int z = z0 + 1; z < z1; z++) {
                long col = BlockPos.asLong(x, 0, z);
                if (!footed.contains(col) || !footed.contains(BlockPos.asLong(x + 1, 0, z)) || !footed.contains(BlockPos.asLong(x - 1, 0, z))
                        || !footed.contains(BlockPos.asLong(x, 0, z + 1)) || !footed.contains(BlockPos.asLong(x, 0, z - 1))) continue;
                Integer roof = ceiling.get(col);
                boolean indoor = !open && roof != null;
                // The floor.
                BlockPos f = new BlockPos(x, minY, z);
                BuildGoal.Placement fp = mine.get(f.asLong());
                boolean drawnFloor = fp != null && fp.part() == BuildGoal.Part.BLOCK && fp.style() != Blueprints.Style.SOIL;
                if (!open && (indoor || drawnFloor) && floor != null && !PlayerBlocks.placed(level, f)) {
                    BlockState fs = level.getBlockState(f);
                    boolean grassy = fs.is(Blocks.GRASS_BLOCK) || fs.is(Blocks.MYCELIUM) || fs.is(Blocks.PODZOL) || drawnFloor && Restore.earthy(fs);
                    boolean hole = drawnFloor && !BuildingAudit.holds(fs) && fs.getFluidState().isEmpty() && mine.get(f.above().asLong()) == null;
                    if (grassy || hole) {
                        if (grassy && TownBlocks.mayBuild(level, v, f, b.anchor(), TownBlocks.Why.REPAIR)) {
                            Crafts.store(level, v, new ItemStack(fs.getBlock().asItem()));
                            level.setBlock(f, floor.defaultBlockState(), 3);
                            fixed.merge("floor_relaid", 1, Integer::sum);
                        } else if (hole && TownBlocks.mayBuild(level, v, f, b.anchor(), TownBlocks.Why.REPAIR)) {
                            level.setBlock(f, floor.defaultBlockState(), 3);
                            fixed.merge("floor_hole", 1, Integer::sum);
                        }
                    }
                }
                if (open) continue;
                int top = indoor ? roof : minY + 3;
                for (int y = minY + 1; y < Math.min(top, minY + 12); y++) {
                    BlockPos c = new BlockPos(x, y, z);
                    if (mine.containsKey(c.asLong()) || PlayerBlocks.placed(level, c)) continue;
                    BlockState st = level.getBlockState(c);
                    if (st.isAir()) continue;
                    String kind = null;
                    if (st.is(Blocks.GRASS_BLOCK) || BuildingAudit.plantish(level, c, st)) kind = "plants";
                    else if (indoor && Terraform.earth(st) && !st.is(Blocks.BEDROCK)) kind = "earth";
                    else if (indoor && (st.getFluidState().is(FluidTags.WATER) || st.getFluidState().is(FluidTags.LAVA)) && st.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) kind = "fluid";
                    else if (lump(st) && lone(level, c)) kind = "stray";
                    if (kind == null) continue;
                    if (!kind.equals("fluid") && !kind.equals("plants")) {
                        ItemStack it = new ItemStack(st.getBlock().asItem());
                        if (!it.isEmpty()) Crafts.store(level, v, it);
                    }
                    level.setBlock(c, Blocks.AIR.defaultBlockState(), 3);
                    if (st.is(Blocks.TALL_GRASS) || st.is(Blocks.LARGE_FERN)) {   // its other half
                        BlockPos o = level.getBlockState(c.above()).is(st.getBlock()) ? c.above() : c.below();
                        if (level.getBlockState(o).is(st.getBlock())) level.setBlock(o, Blocks.AIR.defaultBlockState(), 2 | 16);
                    }
                    fixed.merge(kind, 1, Integer::sum);
                }
            }
        }
        // A fixture laid as a block of its walls: out into the stores, the wall's own block in its place.
        for (BuildGoal.Placement p : mine.values()) {
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            BlockState st = level.getBlockState(p.pos());
            if (!BuildingAudit.fixture(st) || PlayerBlocks.placed(level, p.pos())) continue;
            if (level.getBlockEntity(p.pos()) instanceof net.minecraft.world.Container) continue;   // a chest, a barrel, a furnace: somebody's things in it, left
            Block own = most(byStyle.getOrDefault(p.style(), Map.of()));
            if (own == null) own = floor;
            if (own == null) continue;
            Crafts.store(level, v, new ItemStack(st.getBlock().asItem()));
            level.setBlock(p.pos(), BuildGoal.orient(own.defaultBlockState(), p, b.facing(), level, p.pos()), 3);
            fixed.merge("fixture_out_of_wall", 1, Integer::sum);
        }
        // Scaffolding left round it.
        for (BlockPos q : BlockPos.betweenClosed(x0 - 3, minY - 1, z0 - 3, x1 + 3, maxY + 2, z1 + 3)) {
            if (!level.getBlockState(q).is(Blocks.SCAFFOLDING) || PlayerBlocks.placed(level, q)) continue;
            level.setBlock(q.immutable(), Blocks.AIR.defaultBlockState(), 2 | 16);
            fixed.merge("scaffolding", 1, Integer::sum);
        }
    }

    @Nullable
    private static Block most(Map<Block, Integer> m) {
        Block best = null;
        int n = 0;
        for (Map.Entry<Block, Integer> e : m.entrySet()) if (e.getValue() > n) { n = e.getValue(); best = e.getKey(); }
        return best;
    }

    /** Touching nothing solid on any side. */
    private static boolean lone(ServerLevel level, BlockPos c) {
        for (Direction d : Direction.values()) {
            BlockState n = level.getBlockState(c.relative(d));
            if (!n.isAir() && !(n.canBeReplaced() && n.getFluidState().isEmpty())) return false;
        }
        return true;
    }
}
