package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Houses that grow up with their village, so that an old village looks old.
 * <ul>
 * <li><b>Gardens and fences</b> (from the Stone Age): a fence round each house's plot with a
 *     gate to the street, and flowers either side of the path.</li>
 * <li><b>Wood, then stone, then brick.</b> In the Stone Age the timber walls are rebuilt in
 *     stone; in the Iron Age, in brick. By the Diamond Age moss has got into the old
 *     footings.</li>
 * <li><b>A second storey</b> (from the Iron Age), one house at a time, oldest first: the old
 *     roof comes off, a floor of bedrooms goes on (two more beds, a ladder up), and a new
 *     slate roof over it, with the chimney carried up. It wants all its makings out of the
 *     stores before it starts.</li>
 * </ul>
 * The village's builders do it a few blocks at a time, as town work, and out of the village's
 * stores: nothing goes in that the stores did not pay for, and what comes out goes back in.
 */
public final class Grow {

    private Grow() {}

    /** The materials of a house by its village's age. */
    static final Showcase.Palette STONE = new Showcase.Palette(Blocks.STONE_BRICKS, Blocks.SPRUCE_LOG, Blocks.DARK_OAK_STAIRS,
        Blocks.DARK_OAK_SLAB, Blocks.DARK_OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE,
        Blocks.SPRUCE_FENCE_GATE, Blocks.RED_BED, Blocks.RED_CARPET);
    static final Showcase.Palette BRICK = new Showcase.Palette(Blocks.BRICKS, Blocks.DARK_OAK_LOG, Blocks.DEEPSLATE_TILE_STAIRS,
        Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILES, Blocks.OAK_PLANKS, Blocks.DARK_OAK_DOOR, Blocks.DARK_OAK_FENCE,
        Blocks.DARK_OAK_FENCE_GATE, Blocks.BLUE_BED, Blocks.BLUE_CARPET);

    private static final Block[] FLOWERS = { Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.ALLIUM,
        Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.PINK_TULIP };

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    /** When each house was last found with all its beds in (by its anchor). */
    private static final Map<Long, Long> FURNISHED = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<Long>> GARDENED = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<Long>> STARTED = new ConcurrentHashMap<>();
    /** How many times each layer of a rising storey has been laid (anchor:y). */
    private static final Map<String, Integer> TRIED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LAST.clear();
        FURNISHED.clear();
        GARDENED.clear();
        STARTED.clear();
        TRIED.clear();
    }

    public static Showcase.Palette palette(Villages.Age age) {
        return age.ordinal() >= Villages.Age.IRON.ordinal() ? BRICK : STONE;
    }

    /** Every quarter of a minute: a little more done to the village's houses. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (now - LAST.getOrDefault(id, -100000L) < 300L) return;
        LAST.put(id, now);
        furnish(level, v);
        work(level, v, 24);
    }

    // ------------------------------------------------------------------ beds

    /**
     * A bed into a house that stands without one: from the stores, or made there and then of
     * three wool and three planks out of them. A house got its beds only on the day it went up,
     * out of whatever wool the stores held that morning, and none ever came after — the long
     * game's village of forty-eight had homes for thirty-six and four beds. One bed a turn.
     * Returns whether one was put in.
     */
    public static boolean furnish(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        for (Ledger.Building b : Ledger.buildings(id)) {
            String plan = switch (b.structure()) {
                case "house" -> Ledger.grown(id, b.anchor()) ? "house2" : "house";
                case "barracks" -> "barracks";
                case "manor" -> "manor";
                default -> null;
            };
            if (plan == null || !Land.areaLoaded(level, b.anchor(), 9)) continue;
            if (level.getGameTime() - FURNISHED.getOrDefault(b.anchor().asLong(), -100000L) < 6000L) continue;
            for (BuildGoal.Placement p : BuildGoal.plan(plan, b.anchor(), b.facing(), 13)) {
                if (p.part() != BuildGoal.Part.BED) continue;
                BlockPos foot = p.pos();
                Direction lie = p.way() == com.jrpetty.mcassistant.entity.goal.Blueprints.Way.UP ? b.facing()
                    : com.jrpetty.mcassistant.entity.goal.Blueprints.world(p.way(), b.facing());
                BlockPos head = foot.relative(lie);
                if (level.getBlockState(foot).is(BlockTags.BEDS)) continue;          // made up already
                if (!level.getBlockState(foot).canBeReplaced() || !level.getBlockState(head).canBeReplaced()) continue;
                if (!level.getBlockState(foot.below()).isSolid() || !level.getBlockState(head.below()).isSolid()) continue;
                // Carried in and made up by a hand from the village, once there is one to make up.
                // Or, with neither, one of the founders' beds carried in from the camp.
                boolean camp = !com.jrpetty.mcassistant.VillageSpawner.campBeds(level, v.centre()).isEmpty();
                if (Market.stock(level, id, s -> s.is(ItemTags.BEDS)) == 0 && Market.stock(level, id, s -> s.is(ItemTags.WOOL)) < 3 && !camp) return false;
                if (!TownJobs.atWork(level, v, "beds", foot, "making up a bed")) return false;
                BlockState bed = bedFromTheStores(level, v);
                if (bed == null && camp) {
                    Block lifted = com.jrpetty.mcassistant.VillageSpawner.liftCampBed(level, v.centre(), Villages.bedsClaimed(id));
                    if (lifted != null) bed = lifted.defaultBlockState();
                }
                if (bed == null) return false;                                        // nothing to make one of
                final BlockState laid = bed;
                BuildGoal.stampOnly(level, plan, b.anchor(), b.facing(), 13, x -> laid, x -> x.pos().equals(foot));
                return true;
            }
            FURNISHED.put(b.anchor().asLong(), level.getGameTime());                // every bed in: not looked at again for a while
        }
        return false;
    }

    /** A bed out of the stores, or one made of their wool and planks (a log is four planks). */
    @javax.annotation.Nullable
    private static BlockState bedFromTheStores(ServerLevel level, Villages.Village v) {
        net.minecraft.world.item.ItemStack bed = Crafts.takeOne(level, v, s -> s.is(ItemTags.BEDS));
        if (!bed.isEmpty() && Block.byItem(bed.getItem()) instanceof net.minecraft.world.level.block.BedBlock b) {
            return b.defaultBlockState();
        }
        if (Market.stock(level, v.id(), s -> s.is(ItemTags.WOOL)) < 3) return null;
        boolean planks = Market.stock(level, v.id(), s -> s.is(ItemTags.PLANKS)) >= 3;
        if (!planks && Market.stock(level, v.id(), s -> s.is(ItemTags.LOGS)) < 1) return null;
        net.minecraft.world.item.ItemStack wool = Crafts.takeOne(level, v, s -> s.is(ItemTags.WOOL));
        if (wool.isEmpty()) return null;
        if (!Crafts.take(level, v, s -> s.is(wool.getItem()), 2) && !Crafts.take(level, v, s -> s.is(ItemTags.WOOL), 2)) {
            Crafts.store(level, v, wool);
            return null;
        }
        if (planks) Crafts.take(level, v, s -> s.is(ItemTags.PLANKS), 3);
        else Crafts.take(level, v, s -> s.is(ItemTags.LOGS), 1);
        String colour = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(wool.getItem()).getPath().replace("_wool", "");
        Block made = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation.withDefaultNamespace(colour + "_bed"));
        return made instanceof net.minecraft.world.level.block.BedBlock ? made.defaultBlockState() : Blocks.WHITE_BED.defaultBlockState();
    }

    /** Up to so many blocks of work on the houses. Returns the blocks changed. */
    public static int work(ServerLevel level, Villages.Village v, int budget) {
        UUID id = v.id();
        Villages.Age age = Villages.ageOf(id);
        if (age.ordinal() < Villages.Age.STONE.ordinal()) return 0;
        int done = 0;
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (!b.structure().equals("house") || !Land.areaLoaded(level, b.anchor(), 7)) continue;
            done += garden(level, v, b, false);
            if (done >= budget) break;
            done += reface(level, v, b, age, budget - done, Ledger.grown(id, b.anchor()), false);
            if (done >= budget) break;
            if (age.ordinal() >= Villages.Age.IRON.ordinal() && !Ledger.grown(id, b.anchor())) {
                done += storey(level, v, b, budget - done, palette(age), false);
                break;                                                   // one house at a time
            }
        }
        // And every other building, made over for the age: stone, slate, copper, lamp posts (Ages).
        if (done < budget) done += Ages.work(level, v, budget - done);
        return done;
    }

    // ------------------------------------------------------------------ the garden

    /**
     * A fence round the plot with a gate to the street, and flowers by the path. Once a house. Out
     * of the village's stores now (for nothing only in the showcase): a length of fence or two
     * planks a post, a gate or four planks, and only flowers the stores hold. It goes up as far
     * as they pay, and the house is looked at again another visit for the rest.
     */
    public static int garden(ServerLevel level, Villages.Village v, Ledger.Building b, boolean free) {
        UUID village = v.id();
        Set<Long> gardened = GARDENED.computeIfAbsent(village, k -> ConcurrentHashMap.newKeySet());
        if (gardened.contains(b.anchor().asLong())) return 0;
        // Fenced and planted by a hand from the village, there at the plot (TownJobs).
        if (!free && (Market.stock(level, village, s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS) || s.is(ItemTags.FENCES)) < 4
                || !TownJobs.atWork(level, v, "gardens", b.anchor().relative(b.facing().getOpposite(), 5), "fencing a garden"))) return 0;
        gardened.add(b.anchor().asLong());
        Direction back = b.facing(), right = back.getClockWise(), front = back.getOpposite();
        int y = b.anchor().getY();
        int n = 0;
        boolean unpaid = false;
        List<BlockPos> fences = new ArrayList<>();
        ring:
        for (int a = -5; a <= 5; a++) {
            for (int c = -5; c <= 5; c++) {
                if (Math.max(Math.abs(a), Math.abs(c)) != 5) continue;
                BlockPos at = b.anchor().relative(right, a).relative(back, c).atY(y);
                if (!plot(level, village, b, at)) continue;
                if (c == -5 && a == 0) {
                    if (!free && !Crafts.wooden(level, v, Grow::isGate, 4)) { unpaid = true; break ring; }
                    level.setBlock(at, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, front), 3);
                } else if (c == -5 && Math.abs(a) == 1) {
                    continue;                                               // the path to the gate
                } else {
                    if (!free && !Crafts.fence(level, v)) { unpaid = true; break ring; }
                    level.setBlock(at, Blocks.OAK_FENCE.defaultBlockState(), 3);
                    fences.add(at);
                }
                n++;
            }
        }
        for (BlockPos f : fences) {
            BlockState st = level.getBlockState(f);
            BlockState joined = Block.updateFromNeighbourShapes(st, level, f);
            if (joined != st) level.setBlock(f, joined, 3);
        }
        int[][] beds = { { -3, -4 }, { -2, -4 }, { 2, -4 }, { 3, -4 }, { -4, -4 }, { 4, -4 } };
        for (int[] p : beds) {
            if (unpaid) break;
            BlockPos at = b.anchor().relative(right, p[0]).relative(back, p[1]).atY(y);
            if (!plot(level, village, b, at)) continue;
            boolean azalea = Math.abs(p[0]) == 4;
            BlockState plant;
            if (free) {
                Block flower = FLOWERS[Math.floorMod((int) (at.asLong() * 31), FLOWERS.length)];
                plant = azalea ? Blocks.FLOWERING_AZALEA.defaultBlockState() : flower.defaultBlockState();
            } else {
                // Planted out of the stores (an azalea at the corners if they have one), or not at all.
                net.minecraft.world.item.ItemStack one = azalea
                    ? Crafts.takeOne(level, v, s -> s.is(net.minecraft.world.item.Items.FLOWERING_AZALEA) || s.is(net.minecraft.world.item.Items.AZALEA))
                    : net.minecraft.world.item.ItemStack.EMPTY;
                if (one.isEmpty()) one = Crafts.takeOne(level, v, s -> s.is(ItemTags.SMALL_FLOWERS));
                if (one.isEmpty()) { unpaid = true; break; }
                Block grows = Block.byItem(one.getItem());
                if (grows == Blocks.AIR || !grows.defaultBlockState().canSurvive(level, at)) {
                    Crafts.store(level, v, one);
                    continue;
                }
                plant = grows.defaultBlockState();
            }
            level.setBlock(at, plant, 3);
            n++;
        }
        if (unpaid) gardened.remove(b.anchor().asLong());              // the rest when the stores have it
        return n;
    }

    /** A fence gate, of any wood. */
    private static boolean isGate(net.minecraft.world.item.ItemStack s) {
        return s.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() instanceof FenceGateBlock;
    }

    /** Somewhere in a house's plot to plant or fence: open air on plain ground, nobody else's building. */
    static boolean plot(ServerLevel level, UUID village, Ledger.Building b, BlockPos at) {
        if (!level.getBlockState(at).isAir()) return false;
        BlockState ground = level.getBlockState(at.below());
        if (!(ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.PODZOL))) return false;
        for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(village).entrySet()) {
            int[] half = BuildGoal.footprint(e.getKey());
            int r = Math.max(half[0], half[1]) + 1;
            BlockPos c = e.getValue().anchor();
            if (Math.abs(at.getX() - c.getX()) <= r && Math.abs(at.getZ() - c.getZ()) <= r) return false;   // going up there
        }
        for (Ledger.Building o : Ledger.buildings(village)) {
            if (o == b || o.anchor().equals(b.anchor())) continue;
            int[] half = BuildGoal.footprint(o.structure());
            int r = o.structure().equals("fortify") ? 0 : Math.max(half[0], half[1]) + 1;
            if (r > 0 && Math.abs(at.getX() - o.anchor().getX()) <= r && Math.abs(at.getZ() - o.anchor().getZ()) <= r) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ wood, stone, brick

    /**
     * The house's timber walls rebuilt in stone (Stone Age) or brick (Iron Age on). Returns blocks
     * changed. Each block of the new wall is paid out of the stores now (a block of stone bricks
     * or cobblestone for stone; a block of bricks, or four bricks, for brick), unless {@code free},
     * and what comes out of the wall goes back into them. It stops when they run short.
     */
    public static int reface(ServerLevel level, Villages.Village v, Ledger.Building b, Villages.Age age, int budget,
                             boolean grown, boolean free) {
        Block want = palette(age).walls();
        // The land's own stone, while the stores have it (Homeland): sandstone in the desert,
        // terracotta in the badlands, andesite in the hills, mossy stone in the jungle.
        Homeland.Stone local = Homeland.walls(v.id());
        if (local != null && Crafts.stock(level, v, local.pay()) >= 16) want = local.block();
        boolean old = age.ordinal() >= Villages.Age.DIAMOND.ordinal();
        if (!free) {
            // Only if there is wall to change, and a hand there to change it (TownJobs).
            boolean any = false;
            for (BuildGoal.Placement p : BuildGoal.plan(grown ? "house2" : "house", b.anchor(), b.facing(), 13)) {
                if (p.part() != BuildGoal.Part.BLOCK || p.style() != com.jrpetty.mcassistant.entity.goal.Blueprints.Style.WALL) continue;
                BlockState now = level.getBlockState(p.pos());
                if (now.is(BlockTags.PLANKS) || now.is(Blocks.STONE_BRICKS) && want != Blocks.STONE_BRICKS) { any = true; break; }
            }
            if (!any || !TownJobs.atWork(level, v, "walls", b.anchor(), want == Blocks.BRICKS ? "rebuilding a house in brick" : "rebuilding a house in stone")) return 0;
        }
        int n = 0;
        boolean stop = false;
        Map<net.minecraft.world.item.Item, Integer> back = new HashMap<>();
        for (BuildGoal.Placement p : BuildGoal.plan(grown ? "house2" : "house", b.anchor(), b.facing(), 13)) {
            if (n >= budget || stop) break;
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            BlockState now = level.getBlockState(p.pos());
            switch (p.style()) {
                case WALL -> {
                    boolean timber = now.is(BlockTags.PLANKS);
                    boolean stone = now.is(Blocks.STONE_BRICKS) && want != Blocks.STONE_BRICKS;
                    if (timber || stone) {
                        if (!free && !wallBlock(level, v, want)) {
                            stop = true;
                        } else {
                            if (!free) back.merge(now.getBlock().asItem(), 1, Integer::sum);
                            level.setBlock(p.pos(), want.defaultBlockState(), 3);
                            n++;
                        }
                    }
                }
                case FOUNDATION, WALL_LOW -> {
                    if (old && now.is(Blocks.COBBLESTONE) && Math.floorMod(p.pos().hashCode(), 3) == 0) {
                        level.setBlock(p.pos(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
                        n++;
                    }
                }
                default -> { }
            }
        }
        for (Map.Entry<net.minecraft.world.item.Item, Integer> e : back.entrySet()) Crafts.giveBack(level, v, e.getKey(), e.getValue());
        return n;
    }

    /** A block of the new wall out of the stores: brick for brick, else dressed stone or cobble. */
    private static boolean wallBlock(ServerLevel level, Villages.Village v, Block want) {
        Homeland.Stone local = Homeland.walls(v.id());
        if (local != null && want == local.block()) return Crafts.take(level, v, local.pay(), local.each());
        if (want == Blocks.BRICKS) {
            return Crafts.take(level, v, s -> s.is(net.minecraft.world.item.Items.BRICKS), 1)
                || Crafts.take(level, v, s -> s.is(net.minecraft.world.item.Items.BRICK), 4);
        }
        return Crafts.masonryForLooks(level, v);
    }

    // ------------------------------------------------------------------ the second storey

    /**
     * Raise the house a storey: the old roof off, then the new rooms and roof a layer at a time.
     * Paid for out of the stores before the roof comes off (unless {@code free}): every block the
     * new storey will put in, counted, a plank for each wooden one and a block of stone for each of
     * the rest; no storey till the stores can pay for all of it. The old roof goes into the stores,
     * and the new bedrooms' beds are left to be furnished out of the stores like any other (furnish).
     */
    public static int storey(ServerLevel level, Villages.Village v, Ledger.Building b, int budget, Showcase.Palette pal,
                             boolean free) {
        return raise(level, v, b, "house", "house2", budget, pal, free);
    }

    private static String named(String structure) {
        return structure.equals("house") ? "the house" : Villages.spoken(structure);
    }

    /** Is a second storey going up on this building now (begun, or its old roof off)? */
    public static boolean raisingNow(UUID village, BlockPos anchor) {
        Set<Long> started = STARTED.get(village);
        return (started != null && started.contains(anchor.asLong())) || Ledger.raising(village, anchor);
    }

    /** Has this building (not a house: Ledger.grown) had its second storey put on (Ages)? */
    public static boolean tall(UUID village, BlockPos anchor) {
        return "1".equals(Ledger.note(village, "tall/" + anchor.asLong()));
    }

    /**
     * Raise a building a storey, from one drawing of it to the next (a house to house2, a tavern to
     * tavern_tall): the old roof off, then the new storey and roof a layer at a time, paid for up
     * front. A house is counted grown (two more beds' room); anything else is marked tall.
     */
    public static int raise(ServerLevel level, Villages.Village v, Ledger.Building b, String from, String to, int budget,
                            Showcase.Palette pal, boolean free) {
        UUID id = v.id();
        boolean home = from.equals("house");
        Set<Long> started = STARTED.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
        List<BuildGoal.Placement> was = BuildGoal.plan(from, b.anchor(), b.facing(), 13);
        List<BuildGoal.Placement> will = BuildGoal.plan(to, b.anchor(), b.facing(), 13);
        if (will.isEmpty()) return 0;
        if (!started.contains(b.anchor().asLong()) && !Ledger.raising(id, b.anchor())) {
            // The makings for all of it, out of the stores, before the roof comes off.
            if (!free && !payForStorey(level, v, b, was, will, Showcase.painter(pal))) return 0;
            started.add(b.anchor().asLong());
            Villages.tell(id, level.getDayTime() / 24000L, "the builders began a second storey on " + named(from) + " at "
                + b.anchor().getX() + ", " + b.anchor().getZ());
        }
        Map<BlockPos, BuildGoal.Placement> next = new HashMap<>();
        for (BuildGoal.Placement p : will) next.put(p.pos(), p);
        // Raised by hand: the builders there at the house (TownJobs).
        if (!free && !TownJobs.atWork(level, v, "storeys", b.anchor(), "raising a second storey on " + named(from))) return 0;
        // What comes down to make way, for the stores.
        Map<net.minecraft.world.item.Item, Integer> back = new HashMap<>();
        // The old roof off, from the top down (once: after that, what stands there is the new storey).
        List<BuildGoal.Placement> off = new ArrayList<>();
        boolean stripped = Ledger.raising(id, b.anchor());
        for (BuildGoal.Placement p : stripped ? List.<BuildGoal.Placement>of() : was) {
            if (p.pos().getY() < b.anchor().getY() + 3 || p.part() == BuildGoal.Part.CLEAR) continue;
            BuildGoal.Placement q = next.get(p.pos());
            if (q != null && q.part() == p.part() && q.style() == p.style()) continue;
            if (level.getBlockState(p.pos()).isAir()) continue;
            off.add(p);
        }
        // Whatever stands where the new ladder goes up (the old chest): its contents into the stores.
        for (BuildGoal.Placement q : will) {
            if (q.part() != BuildGoal.Part.LADDER || q.pos().getY() >= b.anchor().getY() + 3) continue;
            BlockState there = level.getBlockState(q.pos());
            if (there.isAir() || there.is(Blocks.LADDER) || there.canBeReplaced()) continue;
            if (level.getBlockEntity(q.pos()) instanceof net.minecraft.world.Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    net.minecraft.world.item.ItemStack st = c.removeItemNoUpdate(i);
                    if (!st.isEmpty()) {
                        net.minecraft.world.item.ItemStack left = Market.intoStores(level, id, st);
                        if (!left.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, q.pos().getX() + 0.5,
                            q.pos().getY() + 0.5, q.pos().getZ() + 0.5, left);
                    }
                }
            }
            if (!free) back.merge(there.getBlock().asItem(), 1, Integer::sum);
            level.setBlock(q.pos(), Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        if (off.isEmpty() && !stripped) Ledger.raising(id, b.anchor(), true);
        if (!off.isEmpty()) {
            off.sort((x, y) -> y.pos().getY() - x.pos().getY());
            int n = 0;
            for (BuildGoal.Placement p : off) {
                if (n >= budget) break;
                if (!free) back.merge(level.getBlockState(p.pos()).getBlock().asItem(), 1, Integer::sum);
                level.setBlock(p.pos(), Blocks.AIR.defaultBlockState(), 2 | 16);
                n++;
            }
            for (Map.Entry<net.minecraft.world.item.Item, Integer> e : back.entrySet()) Crafts.giveBack(level, v, e.getKey(), e.getValue());
            return n;
        }
        for (Map.Entry<net.minecraft.world.item.Item, Integer> e : back.entrySet()) Crafts.giveBack(level, v, e.getKey(), e.getValue());
        // The new storey, a layer at a time from the bottom. A layer tried three times is done with:
        // whatever would not go in (something in the way) must not hold up the roof.
        int lowest = Integer.MAX_VALUE;
        Set<BlockPos> missing = new HashSet<>();
        for (BuildGoal.Placement p : will) {
            if (p.part() == BuildGoal.Part.CLEAR) continue;
            if (!free && p.part() == BuildGoal.Part.BED) continue;               // furnished out of the stores later
            BlockState now = level.getBlockState(p.pos());
            if (!now.isAir() && !(now.canBeReplaced() && now.getFluidState().isEmpty())) continue;
            if (TRIED.getOrDefault(b.anchor().asLong() + ":" + p.pos().getY(), 0) >= 3) continue;
            missing.add(p.pos());
            lowest = Math.min(lowest, p.pos().getY());
        }
        if (missing.isEmpty()) {
            TRIED.keySet().removeIf(k -> k.startsWith(b.anchor().asLong() + ":"));
            if (home) Ledger.grow(id, b.anchor());
            else Ledger.note(id, "tall/" + b.anchor().asLong(), "1");
            Ledger.raising(id, b.anchor(), false);
            started.remove(b.anchor().asLong());
            String spoken = named(from);
            Villages.tell(id, level.getDayTime() / 24000L, Character.toUpperCase(spoken.charAt(0)) + spoken.substring(1)
                + " at " + b.anchor().getX() + ", " + b.anchor().getZ() + " got its second storey");
            return 0;
        }
        final int layer = lowest;
        TRIED.merge(b.anchor().asLong() + ":" + layer, 1, Integer::sum);
        Set<BlockPos> now = new HashSet<>();
        for (BlockPos p : missing) if (p.getY() == layer && now.size() < Math.max(budget, 12)) now.add(p);
        return BuildGoal.stampOnly(level, to, b.anchor(), b.facing(), 13, Showcase.painter(pal),
            p -> now.contains(p.pos()));
    }

    /**
     * What a second storey will put in, counted against the stores and paid for all at once: a
     * plank for each wooden block (the floor, the beams, the ladder; the stores' logs are sawn if
     * they are short of planks) and a block of stone (cobble, stone bricks or bricks) for each of
     * the rest (the walls, the roof, the chimney, the glass). The beds are not counted: they come
     * out of the stores as any house's do. Nothing is taken unless all of it can be.
     */
    private static boolean payForStorey(ServerLevel level, Villages.Village v, Ledger.Building b,
                                        List<BuildGoal.Placement> was, List<BuildGoal.Placement> will,
                                        java.util.function.Function<BuildGoal.Placement, BlockState> paint) {
        Map<BlockPos, BuildGoal.Placement> before = new HashMap<>();
        for (BuildGoal.Placement p : was) before.put(p.pos(), p);
        int top = b.anchor().getY() + 3;
        int wood = 0, stone = 0;
        for (BuildGoal.Placement p : will) {
            if (p.part() == BuildGoal.Part.CLEAR || p.part() == BuildGoal.Part.BED) continue;
            BlockState now = level.getBlockState(p.pos());
            boolean open = now.isAir() || (now.canBeReplaced() && now.getFluidState().isEmpty());
            if (!open) {
                // Standing now, but coming off with the old roof (or, the ladder's foot, out of the
                // way below): it goes back in new.
                BuildGoal.Placement q = before.get(p.pos());
                boolean comesOff = p.pos().getY() >= top && q != null && q.part() != BuildGoal.Part.CLEAR
                    && !(q.part() == p.part() && q.style() == p.style());
                boolean ladderFoot = p.part() == BuildGoal.Part.LADDER && p.pos().getY() < top && !now.is(Blocks.LADDER);
                if (!comesOff && !ladderFoot) continue;
            }
            BlockState st = paint.apply(p);
            if (st == null) continue;
            if (st.is(BlockTags.MINEABLE_WITH_AXE)) wood++;
            else stone++;
        }
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> stoneWork = s -> s.is(net.minecraft.world.item.Items.COBBLESTONE)
            || s.is(net.minecraft.world.item.Items.STONE_BRICKS) || s.is(net.minecraft.world.item.Items.BRICKS)
            || s.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE);
        if (Crafts.stock(level, v, stoneWork) < stone) return false;
        if (Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS)) + 4 * Crafts.stock(level, v, s -> s.is(ItemTags.LOGS)) < wood) return false;
        if (!Crafts.take(level, v, stoneWork, stone)) return false;
        if (!Crafts.usePlanks(level, v, wood)) {
            Crafts.giveBack(level, v, net.minecraft.world.item.Items.COBBLESTONE, stone);
            return false;
        }
        return true;
    }

    /** Grow a house all at once (the showcase, tests): garden, walls and storey for this age, for nothing. */
    public static void now(ServerLevel level, Villages.Village v, Ledger.Building b, Villages.Age age) {
        garden(level, v, b, true);
        if (age.ordinal() >= Villages.Age.IRON.ordinal()) {
            STARTED.computeIfAbsent(v.id(), k -> ConcurrentHashMap.newKeySet()).add(b.anchor().asLong());
            for (int i = 0; i < 40 && !Ledger.grown(v.id(), b.anchor()); i++) storey(level, v, b, 400, palette(age), true);
        }
        reface(level, v, b, age, 1000, Ledger.grown(v.id(), b.anchor()), true);
    }
}
