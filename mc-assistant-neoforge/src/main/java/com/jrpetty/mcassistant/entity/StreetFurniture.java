package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Street furniture [batchE]: the small things a town puts out on its streets, a few at a time, out of the
 * stores, by a hand at the town's works.
 * <ul>
 * <li><b>Benches</b> at the street corners, the first of them where the avenues leave the square: a wooden
 *     stair set the right way up on the corner of the lot, its back to the lot and its seat to the street
 *     (the game takes it for a seat: folk on their break sit on it, Seats). Six planks of one wood made into
 *     four, the three left over back into the stores for the roofs; only planks the builders can spare
 *     (TownLook.wooden), so none in the Wood Age.</li>
 * <li><b>Window boxes</b> under two windows of each house whose household is well off (Wealth), the front
 *     ones where the lamp posts by the door leave room for them, else the sides: a trapdoor fixed under
 *     the sill for a ledge and a pot of flowers on it. The trapdoor and the pot out of
 *     the stores, or made there (six planks, two trapdoors; three bricks, a pot), and a flower from the
 *     stores or a wild one dug up and brought.</li>
 * <li><b>A notice board</b> by the square, where the avenue nearest the village board comes out through
 *     the gate: two signs out of the stores side by side, the first with the town's name, the day and how
 *     many live there, the second with the latest from the town's chronicle (the gazette's news), pinned up
 *     afresh each day.</li>
 * </ul>
 * Never on a street, never in anybody's doorway, never on the farmland, and never where something
 * already stands.
 */
public final class StreetFurniture {

    private StreetFurniture() {}

    /** At most this many window boxes to a house. */
    static final int BOXES = 2;
    /**
     * What the stores keep back from the window boxes: a pot for the shop's shelf (a household buys one for
     * its sill: Luxuries), a few bricks, and the flowers for a household's garden and a grave (Families).
     */
    static final int POTS_KEPT = 1, BRICKS_KEPT = 12, FLOWERS_KEPT = 4;

    private static final Map<UUID, Integer> TURN = new ConcurrentHashMap<>();
    /** The day each village's notice was last pinned up. */
    private static final Map<UUID, Long> PINNED = new ConcurrentHashMap<>();

    static void resetForTests() {
        TURN.clear();
        PINNED.clear();
    }

    /** One visit's work: the day's notice, else a bench, else a window box, turn and turn about. */
    static boolean tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.headcount(id) < TownJobs.SETTLED) return false;
        if (notice(level, v)) return true;
        int turn = TURN.merge(id, 1, Integer::sum);
        if (turn % 2 == 0) return bench(level, v) || windowBox(level, v);
        return windowBox(level, v) || bench(level, v);
    }

    // ------------------------------------------------------------------ benches

    /** A bench's place: an offset from the heart, and which way its back is. */
    record Seat(int dx, int dz, Direction back) {}

    /**
     * A bench for each street corner within this reach (TownLife.corners, the crossings of the town's
     * streets): on the corner beside the avenue, else on the corner across from the street sign's post,
     * looking at the street it sits beside.
     */
    static List<Seat> seats(int reach) {
        List<Seat> out = new ArrayList<>();
        for (int[] c : TownLife.corners(reach)) {
            int x = c[0], z = c[1];
            int hx = x == 0 ? TownPlan.AVENUE : 1, hz = z == 0 ? TownPlan.AVENUE : 1;
            int sx = x == 0 ? 1 : Integer.signum(x), sz = z == 0 ? 1 : Integer.signum(z);
            Seat s;
            if (x == 0) s = new Seat(-(hx + 1), z + sz * (hz + 1), Direction.WEST);            // by the north or south avenue
            else if (z == 0) s = new Seat(x + sx * (hx + 1), -(hz + 1), Direction.NORTH);    // by the east or west avenue
            else s = new Seat(x - sx * (hx + 1), z + sz * (hz + 1), sx > 0 ? Direction.WEST : Direction.EAST);
            if (TownPlan.isStreet(s.dx(), s.dz()) || TownPlan.isSquare(s.dx(), s.dz())) continue;
            out.add(s);
        }
        return out;
    }

    /** Is there a bench (any stair the right way up) on this spot? */
    static boolean benchAt(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return st.getBlock() instanceof StairBlock && st.getValue(StairBlock.HALF) == Half.BOTTOM;
    }

    /** Room for a bench here: open air for the seat and a head, firm ground, and nothing of the town's by it. */
    static boolean benchFits(ServerLevel level, Villages.Village v, Seat s, BlockPos p) {
        if (Villages.onFarmland(v.id(), s.dx(), s.dz(), 1, 1)) return false;
        if (Math.abs(p.getY() - v.centre().getY()) > 10) return false;
        BlockState ground = level.getBlockState(p.below());
        if (!ground.isSolid() || !ground.getFluidState().isEmpty() || ground.getBlock() instanceof StairBlock
                || ground.is(BlockTags.ALL_SIGNS)) return false;                 // (the game counts a sign as solid)
        if (!TownLook.open(level.getBlockState(p)) || !level.getBlockState(p.above()).isAir()) return false;
        if (!level.canSeeSky(p.above())) return false;
        return !TownLook.doorNear(level, p, 2) && !TownLook.postNear(level, p);
    }

    /** The next street corner with no bench, given one. True if a bench went down. */
    static boolean bench(ServerLevel level, Villages.Village v) {
        for (Seat s : seats(Villages.townReach(v.id()))) {
            BlockPos p = TownLook.ground(level, v.centre().getX() + s.dx(), v.centre().getZ() + s.dz());
            if (!level.isLoaded(p) || benchAt(level, p.below()) || benchAt(level, p) || !benchFits(level, v, s, p)) continue;
            if (!TownLook.canWooden(level, v, ItemTags.WOODEN_STAIRS, "stairs", 6)) return false;
            if (!TownJobs.atWork(level, v, "furniture", p, "putting a bench out on the corner")) return false;
            Block stair = TownLook.wooden(level, v, ItemTags.WOODEN_STAIRS, "stairs", 6, 4);
            if (!(stair instanceof StairBlock)) return false;
            if (!level.getBlockState(p).isAir()) level.destroyBlock(p, false);
            level.setBlock(p, stair.defaultBlockState().setValue(StairBlock.FACING, s.back()), 3);
            level.playSound(null, p, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            return true;
        }
        return false;
    }

    static int benches(ServerLevel level, Villages.Village v) {
        int n = 0;
        for (Seat s : seats(Villages.townReach(v.id()))) {
            BlockPos p = TownLook.ground(level, v.centre().getX() + s.dx(), v.centre().getZ() + s.dz());
            if (level.isLoaded(p) && (benchAt(level, p) || benchAt(level, p.below()))) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ window boxes

    /** Is anybody in this household well off (Wealth)? */
    static boolean wellOff(UUID village, Homes.Home h) {
        for (VillageFolkEntity f : Homes.loadedMembers(village, h)) {
            if (Wealth.tier(f).ordinal() >= Wealth.Tier.WELL_OFF.ordinal()) return true;
        }
        return false;
    }

    /** A window box's place: the window it is under, the ledge's cell and the pot's, and which way is out. */
    record Box(BlockPos window, BlockPos ledge, BlockPos pot, Direction out) {}

    /**
     * Where a house can have its boxes: under its lowest windows, the front ones first. Never where the
     * lamp posts by its door go (Ages: either side of the door, a step out), which is under the front
     * windows of a cottage: a cottage's boxes go under its side windows.
     */
    static List<Box> boxes(Ledger.Building b) {
        TownLife.Fittings fit = TownLife.fittings(b);
        int low = Integer.MAX_VALUE;
        for (BlockPos w : fit.windows()) low = Math.min(low, w.getY());
        List<BlockPos> lamps = Ages.lampSpots(b, b.structure());
        List<Box> front = new ArrayList<>(), side = new ArrayList<>();
        Direction street = b.facing().getOpposite();
        for (int i = 0; i < fit.windows().size(); i++) {
            BlockPos w = fit.windows().get(i), in = fit.insides().get(i);
            if (w.getY() != low || in == null) continue;
            Direction out = null;
            for (Direction d : Direction.Plane.HORIZONTAL) if (w.relative(d.getOpposite()).equals(in)) out = d;
            if (out == null) continue;
            Box box = new Box(w, w.relative(out).below(), w.relative(out), out);
            if (lamps.contains(box.ledge()) || lamps.contains(box.pot())) continue;
            (out == street ? front : side).add(box);
        }
        front.addAll(side);
        return front;
    }

    /** How many boxes this house has up. */
    static int boxedCount(ServerLevel level, Ledger.Building b) {
        int n = 0;
        for (Box box : boxes(b)) if (boxed(level, box)) n++;
        return n;
    }

    /** Is there a window box under this window? */
    static boolean boxed(ServerLevel level, Box box) {
        return level.getBlockState(box.pot()).getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock
            || level.getBlockState(box.ledge()).getBlock() instanceof com.jrpetty.mcassistant.block.WindowBoxBlock;   // [workitems]
    }

    /** Room for a box: both cells open, and not in anybody's doorway. */
    static boolean boxFits(ServerLevel level, Box box) {
        return level.getBlockState(box.ledge()).isAir() && level.getBlockState(box.pot()).isAir()
            && !TownLook.doorNear(level, box.ledge(), 1);
    }

    /** The next well-off house's window with no box under it, given one. True if one went up. */
    static boolean windowBox(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        for (Homes.Home h : Homes.homes(id).values()) {
            if (Flats.isFlat(h) || h.members.isEmpty() || !wellOff(id, h)) continue;
            Ledger.Building b = Homes.building(id, h.anchor);
            if (b == null || !level.isLoaded(b.anchor())) continue;
            if (boxedCount(level, b) >= BOXES) continue;
            for (Box box : boxes(b)) {
                if (boxed(level, box) || !boxFits(level, box)) continue;
                // [workitems] A window box of the household's own, carried home and hung by its gardener (WindowBoxes).
                if (WindowBoxes.hang(level, v, h, box.ledge(), box.out())) return true;
                if (!canFurnish(level, v)) return false;
                if (!TownJobs.atWork(level, v, "furniture", box.ledge(), "putting a window box up")) return false;
                return putUp(level, v, box);
            }
        }
        return false;
    }

    /** Whether the stores run to a window box: a trapdoor or the planks, a pot or the bricks, and a flower. */
    static boolean canFurnish(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        boolean ledge = TownLook.canWooden(level, v, ItemTags.WOODEN_TRAPDOORS, "trapdoor", 6);
        boolean pot = Market.stock(level, id, s -> s.is(Items.FLOWER_POT)) > POTS_KEPT
            || Market.stock(level, id, s -> s.is(Items.BRICK)) >= BRICKS_KEPT + 3;
        boolean flower = Market.stock(level, id, s -> s.is(ItemTags.SMALL_FLOWERS)) > FLOWERS_KEPT;
        return ledge && pot && flower;
    }

    /** The box made and put up: the ledge, the pot on it, the flower in the pot. All of it, or none. */
    static boolean putUp(ServerLevel level, Villages.Village v, Box box) {
        ItemStack flower = Crafts.takeOne(level, v, s -> s.is(ItemTags.SMALL_FLOWERS));
        if (flower.isEmpty()) return false;
        Block potted = potted(Block.byItem(flower.getItem()));
        if (potted == null) {
            Crafts.store(level, v, flower);
            return false;
        }
        boolean pot = Market.stock(level, v.id(), s -> s.is(Items.FLOWER_POT)) > POTS_KEPT && Crafts.take(level, v, s -> s.is(Items.FLOWER_POT), 1)
            || Market.stock(level, v.id(), s -> s.is(Items.BRICK)) >= BRICKS_KEPT + 3 && Crafts.take(level, v, s -> s.is(Items.BRICK), 3);
        if (!pot) {
            Crafts.store(level, v, flower);
            return false;
        }
        Block ledge = TownLook.wooden(level, v, ItemTags.WOODEN_TRAPDOORS, "trapdoor", 6, 2);
        if (!(ledge instanceof TrapDoorBlock)) {
            Crafts.store(level, v, flower);
            Crafts.store(level, v, new ItemStack(Items.FLOWER_POT));
            return false;
        }
        level.setBlock(box.ledge(), ledge.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.TOP)
            .setValue(TrapDoorBlock.FACING, box.out()).setValue(TrapDoorBlock.OPEN, false), 3);
        level.setBlock(box.pot(), potted.defaultBlockState(), 3);
        level.playSound(null, box.pot(), SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    /** The potted kind of a flower (a poppy's pot is a potted poppy), or null if there is none. */
    @Nullable
    static Block potted(Block flower) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(flower);
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(key.getNamespace(), "potted_" + key.getPath()));
        return b == Blocks.AIR ? null : b;
    }

    static int boxes(ServerLevel level, Villages.Village v) {
        int n = 0;
        for (Homes.Home h : Homes.homes(v.id()).values()) {
            if (Flats.isFlat(h)) continue;
            Ledger.Building b = Homes.building(v.id(), h.anchor);
            if (b == null || !level.isLoaded(b.anchor())) continue;
            n += boxedCount(level, b);
        }
        return n;
    }

    // ------------------------------------------------------------------ the notice board

    /** The notice board's two signs, in the world, and which way they face; or null if there is no room. */
    @Nullable
    static BlockPos[] board(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        BlockPos villageBoard = VillageBoards.boardOf(id);
        int first = villageBoard != null && villageBoard.getZ() < heart.getZ() ? -1 : 1;
        int out = TownPlan.RING + TownPlan.STREET;                         // the first block past the ring street
        for (int sz : new int[]{ first, -first }) {
            for (int sx : new int[]{ -1, 1 }) {
                BlockPos a = noticeSpot(level, heart.getX() + sx * (TownPlan.AVENUE + 3), heart.getZ() + sz * out);
                BlockPos b = noticeSpot(level, heart.getX() + sx * (TownPlan.AVENUE + 4), heart.getZ() + sz * out);
                if (!level.isLoaded(a) || !level.isLoaded(b)) continue;
                if (isNotice(level, a) && isNotice(level, b)) return new BlockPos[]{ a, b };
                if (a.getY() != b.getY() || Math.abs(a.getY() - heart.getY()) > 10) continue;
                if (!noticeFits(level, a) || !noticeFits(level, b) || TownLook.doorNear(level, a, 2) || TownLook.doorNear(level, b, 2)) continue;
                return new BlockPos[]{ a, b };
            }
        }
        return null;
    }

    private static boolean noticeFits(ServerLevel level, BlockPos p) {
        BlockState ground = level.getBlockState(p.below());
        return ground.isSolid() && ground.getFluidState().isEmpty() && !ground.is(BlockTags.ALL_SIGNS) && TownLook.open(level.getBlockState(p))
            && level.getBlockState(p.above()).isAir();
    }

    /**
     * Where a notice stands at this column: the first free block over the ground, or the sign standing there.
     * The game counts a sign as solid (the heightmap stands on it), so the ground's height alone would put the
     * board's place on top of its own signs, and a new board up there every visit.
     */
    static BlockPos noticeSpot(ServerLevel level, int x, int z) {
        BlockPos p = TownLook.ground(level, x, z);
        for (int i = 0; i < 4 && level.getBlockState(p.below()).getBlock() instanceof StandingSignBlock; i++) p = p.below();
        return p;
    }

    /** One of the town's notices: a standing sign it put up. */
    static boolean isNotice(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getBlock() instanceof StandingSignBlock;
    }

    /**
     * The notice board: put up if it is not there yet (two signs out of the stores), and the day's news
     * pinned up on it once a day. True if anything was done.
     */
    static boolean notice(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Long pinned = PINNED.get(id);
        BlockPos[] at = board(level, v);
        if (at == null) return false;
        boolean up = isNotice(level, at[0]) && isNotice(level, at[1]);
        if (up && pinned != null && pinned == day) return false;
        if (!up && !TownLook.canSign(level, v, 2)) return false;
        if (!TownJobs.atWork(level, v, "furniture", at[0], up ? "pinning up the day's news" : "putting up the notice board")) return false;
        if (!up) {
            if (!TownLook.sign(level, v)) return false;
            if (!TownLook.sign(level, v)) {
                Crafts.store(level, v, new ItemStack(Items.SPRUCE_SIGN));
                return false;
            }
            int toward = Integer.signum(v.centre().getZ() - at[0].getZ());       // facing the ring street, and the square
            int rotation = toward < 0 ? 8 : 0;
            for (BlockPos p : at) {
                if (!level.getBlockState(p).isAir()) level.destroyBlock(p, false);
                level.setBlock(p, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation), 3);
            }
            Villages.tell(id, day, "a notice board went up by the square, with the town's news on it");
        }
        String[][] text = noticeText(level, v, day);
        if (level.getBlockEntity(at[0]) instanceof SignBlockEntity s1) TownLife.write(s1, text[0]);
        if (level.getBlockEntity(at[1]) instanceof SignBlockEntity s2) TownLife.write(s2, text[1]);
        PINNED.put(id, day);
        return true;
    }

    /** What the notice says: the town and the day on the first sign, the latest news on the second. */
    static String[][] noticeText(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        String[] head = { "Town News", cut(Villages.name(id), 15), "Day " + day, Villages.headcount(id) + " folk" };
        String latest = null;
        List<Chronicle.Entry> all = Chronicle.of(id);
        for (int i = all.size() - 1; i >= 0; i--) {
            Chronicle.Entry e = all.get(i);
            if (e.day() >= day - 2 && !e.text().contains("notice board")) { latest = e.text(); break; }
        }
        if (latest == null) latest = "All quiet in " + Villages.name(id) + ".";
        return new String[][]{ head, wrap(capital(latest), 15, 4) };
    }

    /** Words wrapped onto so many lines of so many letters, the last cut short with "..." if it runs on. */
    static String[] wrap(String text, int width, int lines) {
        String[] out = new String[lines];
        java.util.Arrays.fill(out, "");
        int line = 0;
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            if (word.length() > width) word = word.substring(0, width);
            String next = out[line].isEmpty() ? word : out[line] + " " + word;
            if (next.length() <= width) {
                out[line] = next;
                continue;
            }
            if (line + 1 >= lines) {
                out[line] = cut(out[line] + " " + word, width);
                return out;
            }
            out[++line] = word;
        }
        return out;
    }

    private static String cut(String s, int width) {
        return s.length() <= width ? s : s.substring(0, width - 3) + "...";
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    static String line(ServerLevel level, Villages.Village v) {
        BlockPos[] at = board(level, v);
        boolean up = at != null && isNotice(level, at[0]);
        return benches(level, v) + " benches on the corners, " + boxes(level, v) + " window boxes, the notice board "
            + (up ? "up by the square" : "not up yet");
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the town's furniture rounds, so many times over. Returns how many did something. */
    public static int roundsForTests(ServerLevel level, Villages.Village v, int rounds) {
        int n = 0;
        for (int i = 0; i < rounds; i++) if (tick(level, v)) n++;
        return n;
    }

    /** Tests: where each bench goes, in the world (the first free block over the ground). */
    public static List<BlockPos> benchSpotsForTests(ServerLevel level, Villages.Village v) {
        List<BlockPos> out = new ArrayList<>();
        for (Seat s : seats(Villages.townReach(v.id()))) out.add(TownLook.ground(level, v.centre().getX() + s.dx(), v.centre().getZ() + s.dz()));
        return out;
    }

    /** Tests: {benches, window boxes} the town has out. */
    public static int[] countsForTests(ServerLevel level, Villages.Village v) {
        return new int[]{ benches(level, v), boxes(level, v) };
    }

    /** Tests: the window boxes a house of the village's can have (pot cells), front first. */
    public static List<BlockPos> boxSpotsForTests(UUID village, BlockPos anchor) {
        Ledger.Building b = Homes.building(village, anchor);
        List<BlockPos> out = new ArrayList<>();
        if (b != null) for (Box box : boxes(b)) out.add(box.pot());
        return out;
    }

    /** Tests: the notice board's signs, or null. */
    @Nullable
    public static BlockPos[] noticeForTests(ServerLevel level, Villages.Village v) {
        return board(level, v);
    }

    /** Tests: the day's notice pinned up again whatever was pinned up today. */
    public static boolean repinForTests(ServerLevel level, Villages.Village v) {
        PINNED.remove(v.id());
        return notice(level, v);
    }
}
