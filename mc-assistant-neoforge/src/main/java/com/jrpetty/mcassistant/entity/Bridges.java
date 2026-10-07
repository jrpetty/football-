package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [transport] The stone bridge that replaces the ferry.
 * <ul>
 * <li><b>When.</b> Once the ferry has run a few days and the town has the stone to spare for a bridge (the builders'
 *     own stone kept back), the crossing is put to the town: a council vote here, or the town's referendum where there
 *     is one (the seam: {@link #pollBy}). Each councillor weighs it: one whose work is over the water is for it, a hard
 *     worker likes it, the elder likes it with money in the treasury, a grumbler grudges the stone, and the ferryman
 *     votes for its own trade. Voted down, it is put again a week on.</li>
 * <li><b>The bridge.</b> Beside the ferry's crossing (so the ferry runs while it goes up), from bank to bank: piers of
 *     stone from the river bed, an arch between each pair (its shoulders upside-down stairs), as many arches as the
 *     water is wide (one to every five blocks), a deck five wide two above the water, a wall either side and a lantern
 *     on the wall every six, and stairs down to the ground at each end. All of it the town's real stone out of the
 *     stores (stone bricks if the masons have made them, cobblestone else, and the lanterns the smith's), laid a
 *     dozen blocks a visit by a hand at the town's works.</li>
 * <li><b>Then.</b> The bridge open, the ferry rows its last crossing (Ferries.retire): the boat back into the stores,
 *     the ferryman to another trade. The chronicle tells it all.</li>
 * </ul>
 */
public final class Bridges {

    private Bridges() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** Where the bridge is: not asked, put to the town, voted, being built, open; or voted down for now. */
    public enum Stage { NONE, ASKED, BUILDING, OPEN, REJECTED }

    /** What the town said. */
    public enum Verdict { PENDING, FOR, AGAINST }

    /**
     * How a big work is put to the town. The council's vote is the default; the referendums (civic), once merged, put
     * the question to everybody instead by passing their own here. Asked once a visit until it says FOR or AGAINST.
     */
    public interface Poll {
        Verdict ask(ServerLevel level, Villages.Village v, String key, String question);
    }

    private static volatile Poll poll = Bridges::council;

    /** The referendums' seam: put the town's big works to its folk this way (null: back to the council). */
    public static void pollBy(@Nullable Poll p) {
        poll = p == null ? Bridges::council : p;
    }

    /** The ferry runs this many days before a bridge is asked for, and a no is asked again after this many. */
    static final long FERRY_DAYS = 2, ASK_AGAIN = 7;
    /** Blocks a visit. */
    static final int STEPS = 12;
    /** How far to the side of the ferry's crossing the bridge goes, tried in turn. */
    private static final int[] OFFSETS = { 5, -5, 7, -7, 4, -4, 9, -9 };

    /** The bridges' blocks, worked out once each, by village. */
    private static final Map<UUID, List<Place>> PLANS = new ConcurrentHashMap<>();
    /** Tests: the vote said this. */
    private static volatile Verdict forced;

    public static void resetForTests() {
        PLANS.clear();
        forced = null;
        poll = Bridges::council;
    }

    /** Tests: the next vote goes this way (null: as the council would have it). */
    public static void voteForTests(@Nullable Verdict v) {
        forced = v;
    }

    // ------------------------------------------------------------------ the town's day

    /** Every few seconds with the ferry (Ferries.work): the bridge asked for, voted, built and opened. */
    static void tick(ServerLevel level, Villages.Village v, Ferries.Crossing c) {
        long day = level.getDayTime() / 24000L;
        switch (c.bridge) {
            case NONE, REJECTED -> {
                if (c.state != Ferries.State.RUNNING) return;
                if (c.bridge == Stage.REJECTED && day - c.lastAsked < ASK_AGAIN) return;
                if (day - c.startedDay < FERRY_DAYS || !richEnough(level, v, c)) return;
                if (!site(level, c)) return;
                c.bridge = Stage.ASKED;
                c.lastAsked = day;
                Ferries.save(v.id(), c);
                ask(level, v, c);
            }
            case ASKED -> ask(level, v, c);
            case BUILDING -> build(level, v, c, STEPS, false);
            case OPEN -> {
                if (c.state != Ferries.State.RETIRED) Ferries.retire(level, v, c);
            }
        }
    }

    /** Has the town the stone for the bridge, over what its builders keep back? */
    static boolean richEnough(ServerLevel level, Villages.Village v, Ferries.Crossing c) {
        if (Villages.ageOf(v.id()).ordinal() < Villages.Age.STONE.ordinal()) return false;
        int need = c.bridgeA == null ? (c.width + 6) * 9 + 40 : plan(level, c).size();
        return stone(level, v.id()) >= need + Math.max(Masonry.BUILDERS_STONE, Villages.stoneHeldBack(v.id()));
    }

    /** The stores' stone that will do for a bridge: stone bricks, cobblestone and stone. */
    static int stone(ServerLevel level, UUID village) {
        return Market.stock(level, village, s -> s.is(Items.STONE_BRICKS) || s.is(Items.COBBLESTONE) || s.is(Items.STONE));
    }

    private static void ask(ServerLevel level, Villages.Village v, Ferries.Crossing c) {
        String question = "build a stone bridge over the water to " + c.toWhat + " at " + c.where() + ", in place of the ferry";
        Verdict said = forced != null ? forced : poll.ask(level, v, "bridge/" + c.where(), question);
        if (forced != null) {
            c.vote = said == Verdict.FOR ? "it was agreed" : "it was voted down";
            forced = null;
        }
        long day = level.getDayTime() / 24000L;
        switch (said) {
            case PENDING -> { }
            case FOR -> {
                c.bridge = Stage.BUILDING;
                c.votedDay = day;
                Ferries.save(v.id(), c);
                Villages.tell(v.id(), day, "the town resolved to " + question + " (" + c.vote + "); the ferry runs till it is open");
                LOG.info("[MCA-BRIDGE] {}: the bridge at {} voted ({})", Villages.name(v.id()), c.where(), c.vote);
            }
            case AGAINST -> {
                c.bridge = Stage.REJECTED;
                c.lastAsked = day;
                Ferries.save(v.id(), c);
                Villages.tell(v.id(), day, "a stone bridge in place of the ferry was voted down (" + c.vote + "); the ferry runs on");
            }
        }
    }

    /**
     * The council's vote on the bridge, each councillor for or against as it sees it: its own work over the water (for),
     * a hard worker (for), the elder with the treasury full (for), a grumbler (against the stone), the ferryman (against:
     * it is its trade). A tie is a no. Said at once, and the words kept for the chronicle.
     */
    static Verdict council(ServerLevel level, Villages.Village v, String key, String question) {
        Ferries.Crossing c = Ferries.crossing(v.id());
        List<VillageFolkEntity> members = Council.members(v.id());
        if (c == null || members.isEmpty()) return Verdict.PENDING;
        int yes = 0, no = 0;
        List<String> against = new ArrayList<>();
        int home = c.side(v.centre());
        boolean rich = Ledger.coins(v.id()) >= 40;
        for (VillageFolkEntity m : members) {
            int s = 0;
            WorkZone z = m.workZone();
            if (z != null && c.side(z.center()) != home && c.near(z.center(), 96)) s += 3;
            if (m.life().has(Social.Trait.HARDWORKING)) s += 2;
            if (m.life().has(Social.Trait.CURIOUS)) s += 1;
            if (m.isElder() && rich) s += 2;
            if (m.life().has(Social.Trait.GRUMPY)) s -= 2;
            if (m.stationTask() == AssistantEntity.StationTask.FERRY) s -= 5;
            if (c.crossings >= 30) s += 1;
            s += Math.floorMod(m.getUUID().hashCode() >> 5, 3) - 1;        // a little of its own mind
            if (s > 0) yes++;
            else {
                no++;
                against.add(m.displayNameCap() + (m.stationTask() == AssistantEntity.StationTask.FERRY ? " the ferryman" : ""));
            }
        }
        c.vote = "the council voted " + yes + " to " + no + (against.isEmpty() ? "" : ", " + String.join(" and ", against) + " against");
        return yes > no ? Verdict.FOR : Verdict.AGAINST;
    }

    // ------------------------------------------------------------------ where it goes

    /** Find the bridge's place beside the ferry: a straight crossing a few blocks along the river, with banks both sides. */
    static boolean site(ServerLevel level, Ferries.Crossing c) {
        if (c.bridgeA != null) return true;
        Vec mid = new Vec(c);
        Direction side = c.way.getClockWise();
        for (int off : OFFSETS) {
            int x = mid.x + side.getStepX() * off, z = mid.z + side.getStepZ() * off;
            Ferries.Crossing m = Ferries.measure(level, x, z, c.way);
            if (m == null || m.surface != c.surface) continue;
            c.bridgeA = m.bankA;
            c.bridgeB = m.bankB;
            c.bridgeSide = off;
            return true;
        }
        return false;
    }

    private record Vec(int x, int z) {
        Vec(Ferries.Crossing c) {
            this((c.bankA.getX() + c.bankB.getX()) / 2, (c.bankA.getZ() + c.bankB.getZ()) / 2);
        }
    }

    /** One block of the bridge: where, and what (a kind of stone work, a stair's way). */
    record Place(BlockPos pos, char what, Direction facing) {}

    /** The stone work: P a pier block, D deck, A an abutment, S an arch's shoulder (an upside-down stair), W the wall, R a ramp's stair, L a lantern. */
    static List<Place> plan(ServerLevel level, Ferries.Crossing c) {
        List<Place> out = new ArrayList<>();
        if (c.bridgeA == null || c.bridgeB == null) return out;
        Direction way = c.way, side = way.getClockWise();
        int deck = c.surface + 2;
        int span = (int) Math.round(Math.sqrt(c.bridgeA.atY(0).distSqr(c.bridgeB.atY(0))));    // bank to bank
        int water = span - 1;
        int arches = Math.max(1, (int) Math.round(water / 5.0));
        List<Integer> supports = new ArrayList<>();
        supports.add(0);
        for (int j = 1; j < arches; j++) {
            int p = (int) Math.round(j * (water + 1) / (double) arches);
            if (p > 1 && p < water && !supports.contains(p)) supports.add(p);
        }
        supports.add(span);
        BlockPos a = c.bridgeA.atY(deck);
        // The piers, from the bed up.
        for (int s = 1; s < supports.size() - 1; s++) {
            int p = supports.get(s);
            for (int l = -2; l <= 2; l++) {
                BlockPos top = a.relative(way, p).relative(side, l).below();
                int bed = bedBelow(level, top, c.surface);
                for (int y = bed; y <= top.getY(); y++) out.add(new Place(new BlockPos(top.getX(), y, top.getZ()), 'P', way));
            }
        }
        // The abutments at the banks, under the deck's ends.
        for (int end : new int[]{ 0, span }) {
            for (int l = -2; l <= 2; l++) {
                BlockPos under = a.relative(way, end).relative(side, l).below();
                for (int d = 0; d < 3; d++) {
                    BlockPos q = under.below(d);
                    BlockState st = level.getBlockState(q);
                    if (!st.isAir() && st.getFluidState().isEmpty() && !st.canBeReplaced() && !st.is(net.minecraft.tags.BlockTags.STONE_BRICKS)) break;
                    out.add(new Place(q, 'A', way));
                }
            }
        }
        // The arches' shoulders.
        for (int s = 0; s + 1 < supports.size(); s++) {
            int s0 = supports.get(s), s1 = supports.get(s + 1);
            if (s1 - s0 < 3) continue;
            for (int l = -2; l <= 2; l++) {
                out.add(new Place(a.relative(way, s0 + 1).relative(side, l).below(), 'S', way.getOpposite()));
                out.add(new Place(a.relative(way, s1 - 1).relative(side, l).below(), 'S', way));
            }
        }
        // The deck, the walls and the lanterns on them.
        for (int p = 0; p <= span; p++) {
            for (int l = -2; l <= 2; l++) out.add(new Place(a.relative(way, p).relative(side, l), 'D', way));
        }
        for (int p = 0; p <= span; p++) {
            for (int l : new int[]{ -2, 2 }) out.add(new Place(a.relative(way, p).relative(side, l).above(), 'W', way));
        }
        for (int p = 3; p < span; p += 6) {
            for (int l : new int[]{ -2, 2 }) out.add(new Place(a.relative(way, p).relative(side, l).above(2), 'L', way));
        }
        // The stairs down to the ground at each end.
        for (int end = 0; end <= 1; end++) {
            Direction out_ = end == 0 ? way.getOpposite() : way;
            BlockPos edge = end == 0 ? a : a.relative(way, span);
            int y = deck;
            for (int k = 1; k <= 4; k++) {
                BlockPos col = edge.relative(out_, k);
                Roads.Ground g = Roads.ground(level, col.getX(), col.getZ());
                if (g == null || g.water() || g.y() >= y) break;
                for (int l = -1; l <= 1; l++) {
                    BlockPos stair = col.relative(side, l).atY(y);
                    for (int f = g.y() + 1; f < y; f++) out.add(new Place(stair.atY(f), 'A', way));
                    out.add(new Place(stair, 'R', out_.getOpposite()));
                }
                y--;
            }
        }
        return out;
    }

    /** How deep the bed is under this column of water: the first solid block below the surface (or the deck's own pier). */
    private static int bedBelow(ServerLevel level, BlockPos top, int surface) {
        int y = surface;
        for (int d = 0; d < 24; d++) {
            BlockPos p = new BlockPos(top.getX(), y - 1, top.getZ());
            BlockState st = level.getBlockState(p);
            boolean open = st.isAir() || !st.getFluidState().isEmpty() || st.canBeReplaced() || st.is(net.minecraft.tags.BlockTags.STONE_BRICKS)
                || st.is(Blocks.COBBLESTONE) || st.is(net.minecraft.tags.BlockTags.FENCES);
            if (!open) break;
            y--;
        }
        return y;
    }

    // ------------------------------------------------------------------ building it

    /**
     * Up to so many more of the bridge's blocks, each out of the stores (stone bricks, else cobblestone, else stone; a
     * stair or a length of wall cut from a block; a lantern the smith's), by a hand at the works unless {@code free}.
     * The last of it laid, the bridge opens and the ferry retires. Returns blocks laid.
     */
    static int build(ServerLevel level, Villages.Village v, Ferries.Crossing c, int steps, boolean free) {
        List<Place> plan = PLANS.computeIfAbsent(v.id(), k -> plan(level, c));
        if (plan.isEmpty()) return 0;
        int laid = 0;
        int i = Math.max(0, Math.min(c.bridgeDone, plan.size()));
        while (i < plan.size() && laid < steps) {
            Place p = plan.get(i);
            if (!level.isLoaded(p.pos())) break;
            if (done(level, p)) { i++; continue; }
            if (!free && !TownJobs.atWork(level, v, "bridge", p.pos(), "building the stone bridge", AssistantEntity.StationTask.MINE)) break;
            BlockState st = paid(level, v, p, free);
            if (st == null && p.what() == 'L') { i++; continue; }    // no lantern to hand: the wall goes without
            if (st == null) break;                                   // the stores short: it waits there
            BlockState was = level.getBlockState(p.pos());
            if (!was.isAir() && was.getFluidState().isEmpty() && Railways.natural(was) && !free) {
                Item back = was.is(Blocks.STONE) ? Items.COBBLESTONE : was.getBlock().asItem();
                if (back != Items.AIR && !was.canBeReplaced()) Crafts.giveBack(level, v, back, 1);
            }
            level.setBlock(p.pos(), st, 3);
            laid++;
            i++;
        }
        boolean changed = i != c.bridgeDone;
        c.bridgeDone = i;
        if (i >= plan.size()) {
            boolean all = true;
            for (Place p : plan) if (p.what() != 'L' && level.isLoaded(p.pos()) && !done(level, p)) { all = false; c.bridgeDone = 0; break; }
            if (all) {
                open(level, v, c, plan);
                return laid;
            }
        }
        if (changed) Ferries.save(v.id(), c);
        return laid;
    }

    /** Is this block of the bridge there already? */
    private static boolean done(ServerLevel level, Place p) {
        BlockState st = level.getBlockState(p.pos());
        return switch (p.what()) {
            case 'S', 'R' -> st.getBlock() instanceof StairBlock;
            case 'W' -> st.getBlock() instanceof net.minecraft.world.level.block.WallBlock;
            case 'L' -> st.getBlock() instanceof net.minecraft.world.level.block.LanternBlock;
            default -> st.is(Blocks.STONE_BRICKS) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.STONE) || st.is(Blocks.CHISELED_STONE_BRICKS)
                || st.is(Blocks.MOSSY_STONE_BRICKS) || st.is(Blocks.CRACKED_STONE_BRICKS);
        };
    }

    /** The block for this place, paid for out of the stores; null if they cannot run to it. */
    @Nullable
    private static BlockState paid(ServerLevel level, Villages.Village v, Place p, boolean free) {
        if (p.what() == 'L') {
            if (free) return Blocks.LANTERN.defaultBlockState();
            Block light = Masonry.light(level, v);
            if (light instanceof net.minecraft.world.level.block.LanternBlock) return light.defaultBlockState();
            if (light != null) Masonry.unlight(level, v, light);
            return null;
        }
        Item from = free ? Items.STONE_BRICKS : take(level, v);
        if (from == null) return null;
        boolean bricks = from == Items.STONE_BRICKS;
        boolean cobble = from == Items.COBBLESTONE;
        return switch (p.what()) {
            case 'S' -> (bricks ? Blocks.STONE_BRICK_STAIRS : cobble ? Blocks.COBBLESTONE_STAIRS : Blocks.STONE_STAIRS).defaultBlockState()
                .setValue(StairBlock.FACING, p.facing()).setValue(StairBlock.HALF, Half.TOP);
            case 'R' -> (bricks ? Blocks.STONE_BRICK_STAIRS : cobble ? Blocks.COBBLESTONE_STAIRS : Blocks.STONE_STAIRS).defaultBlockState()
                .setValue(StairBlock.FACING, p.facing()).setValue(StairBlock.HALF, Half.BOTTOM);
            case 'W' -> (bricks ? Blocks.STONE_BRICK_WALL : Blocks.COBBLESTONE_WALL).defaultBlockState();
            default -> (bricks ? Blocks.STONE_BRICKS : cobble ? Blocks.COBBLESTONE : Blocks.STONE).defaultBlockState();
        };
    }

    /** A block of the stores' stone taken: stone bricks first, then cobblestone, then stone. */
    @Nullable
    private static Item take(ServerLevel level, Villages.Village v) {
        for (Item it : new Item[]{ Items.STONE_BRICKS, Items.COBBLESTONE, Items.STONE }) {
            if (TownWork.take(level, v, s -> s.is(it), 1)) return it;
        }
        return null;
    }

    /** Open: the walls joined up, the ferry retired, the chronicle told. */
    private static void open(ServerLevel level, Villages.Village v, Ferries.Crossing c, List<Place> plan) {
        for (Place p : plan) {
            if (p.what() != 'W') continue;
            BlockState st = level.getBlockState(p.pos());
            BlockState joined = Block.updateFromNeighbourShapes(st, level, p.pos());
            if (joined != st) level.setBlock(p.pos(), joined, 2);
        }
        long day = level.getDayTime() / 24000L;
        c.bridge = Stage.OPEN;
        c.bridgeOpenedDay = day;
        int arches = 0;
        for (Place p : plan) if (p.what() == 'S') arches++;
        arches = Math.max(1, arches / 10);
        Villages.tell(v.id(), day, "the stone bridge over the water to " + c.toWhat + " was opened: " + arches
            + (arches == 1 ? " arch, " : " arches, ") + plan.size() + " blocks of the town's own stone, voted on day " + c.votedDay);
        LOG.info("[MCA-BRIDGE] {}: the bridge at {} opened ({} blocks)", Villages.name(v.id()), c.where(), plan.size());
        Ferries.save(v.id(), c);
        Ferries.retire(level, v, c);
        PLANS.remove(v.id());
    }

    // ------------------------------------------------------------------ the books, the tests, the showcase

    /** The bridge in a line, or null if there is none to speak of. */
    @Nullable
    public static String report(ServerLevel level, UUID village) {
        Ferries.Crossing c = Ferries.crossing(village);
        if (c == null) return null;
        return switch (c.bridge) {
            case NONE -> null;
            case ASKED -> "A stone bridge in place of the ferry is before the town.";
            case REJECTED -> "A stone bridge was voted down (" + c.vote + "); to be put again.";
            case BUILDING -> {
                int all = PLANS.containsKey(village) ? PLANS.get(village).size() : 0;
                yield "The stone bridge is going up (" + c.vote + ", day " + c.votedDay + "): " + c.bridgeDone
                    + (all > 0 ? " of " + all : "") + " blocks of stone laid.";
            }
            case OPEN -> "The stone bridge at " + c.where() + " is open since day " + c.bridgeOpenedDay + ".";
        };
    }

    /** Tests: the bridge voted (or not) now, its site found. */
    public static boolean voteForTests(ServerLevel level, Villages.Village v, Verdict said) {
        Ferries.Crossing c = Ferries.crossing(v.id());
        if (c == null || !site(level, c)) return false;
        forced = said;
        c.bridge = Stage.ASKED;
        ask(level, v, c);
        return c.bridge == Stage.BUILDING;
    }

    /** Tests: the council's own vote on the bridge, now (who said what is in the books). */
    public static Verdict councilForTests(ServerLevel level, Villages.Village v) {
        return council(level, v, "bridge", "a bridge");
    }

    /** Tests: build as much of the bridge as the stores run to. */
    public static int buildForTests(ServerLevel level, Villages.Village v, int steps) {
        Ferries.Crossing c = Ferries.crossing(v.id());
        return c == null ? 0 : build(level, v, c, steps, false);
    }

    /** Tests: the bridge's blocks. */
    public static int planSizeForTests(ServerLevel level, Villages.Village v) {
        Ferries.Crossing c = Ferries.crossing(v.id());
        return c == null ? 0 : PLANS.computeIfAbsent(v.id(), k -> plan(level, c)).size();
    }

    /** The showcase: the bridge built at once, for nothing. */
    public static void buildFreeForStage(ServerLevel level, Villages.Village v) {
        Ferries.Crossing c = Ferries.crossing(v.id());
        if (c == null || !site(level, c)) return;
        c.bridge = Stage.BUILDING;
        c.vote = "agreed for the pictures";
        build(level, v, c, 100000, true);
    }
}
