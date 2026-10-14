package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.PlaceSettingBlock;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [dining] The town's places to eat out, and what is in each, read off the building as it stands (and kept a while):
 * the café (breakfast, cakes and tea, a light lunch), the tavern (drinks, pies and stews of an evening) and the
 * restaurant (the Stone Age town's dining room, once it has a café, a cook and two dozen folk). In each:
 * <ul>
 * <li><b>the tables</b>: a post with a cloth on it (the carpet, or a setting laid in its place), numbered, each with the
 *     chair that faces it (a stair the right way up beside it, looking at it): one diner to a table's place;</li>
 * <li><b>the kitchen</b>: its stations (the smoker and the furnace for roasts and fish, the hearth for stews, the bench
 *     for cold plates, the brewing stand for tea) and the spot a cook stands at each;</li>
 * <li><b>the pass</b> (the service counter, else the first counter cask, else the bench), where a finished plate waits
 *     for the waiter; <b>the wash</b> (a cauldron, else the pass); and <b>the menu board</b> by the door.</li>
 * </ul>
 */
public final class DiningPlaces {

    private DiningPlaces() {}

    /** The restaurant's drawing (blueprints/restaurant.txt). */
    public static final String RESTAURANT = "restaurant";
    /** How long a place's layout is kept before it is read again (ticks): ten minutes, or at once when it is found wrong. */
    static final long KEEP = 12000L;
    /** The folk a Stone Age town wants before it builds a restaurant (it has its café first). */
    public static final int RESTAURANT_FROM = 24;

    /** The kinds of place, each served as its trade (ServiceRole: its hours and its keeper). */
    public enum Kind {
        CAFE("cafe", ServiceRole.CAFE), TAVERN("tavern", ServiceRole.TAVERN), RESTAURANT(DiningPlaces.RESTAURANT, ServiceRole.RESTAURANT);

        public final String structure;
        public final ServiceRole role;

        Kind(String structure, ServiceRole role) {
            this.structure = structure;
            this.role = role;
        }

        @Nullable
        static Kind of(String structure) {
            for (Kind k : values()) if (k.structure.equals(structure)) return k;
            return null;
        }
    }

    /** What a kitchen works at. */
    public enum Station { SMOKER, FURNACE, HEARTH, BENCH, BREWING }

    /** A table's place: its number, where the setting goes (the cloth on the post), the chair that faces it. */
    public record Table(int number, BlockPos cell, BlockPos seat, Direction looks) {}

    /** A place to eat out, as it stands. */
    public static final class Place {
        public final Kind kind;
        public final Ledger.Building building;
        public final UUID village;
        public final List<Table> tables;
        public final Map<Station, BlockPos> stations;
        public final BlockPos pass, wash;
        @Nullable public final BlockPos door, board;
        final long read;

        Place(Kind kind, Ledger.Building building, UUID village, List<Table> tables, Map<Station, BlockPos> stations, BlockPos pass,
              BlockPos wash, @Nullable BlockPos door, @Nullable BlockPos board, long read) {
            this.kind = kind;
            this.building = building;
            this.village = village;
            this.tables = tables;
            this.stations = stations;
            this.pass = pass;
            this.wash = wash;
            this.door = door;
            this.board = board;
            this.read = read;
        }

        /** Where a dish is cooked: its station, else the nearest the kitchen has to it, else the pass. */
        public BlockPos station(Station want) {
            BlockPos s = stations.get(want);
            if (s != null) return s;
            Station[] instead = switch (want) {
                case SMOKER -> new Station[]{ Station.FURNACE, Station.HEARTH };
                case FURNACE -> new Station[]{ Station.SMOKER, Station.HEARTH };
                case HEARTH -> new Station[]{ Station.SMOKER, Station.FURNACE };
                case BREWING -> new Station[]{ Station.HEARTH, Station.BENCH };
                case BENCH -> new Station[]{ Station.BREWING };
            };
            for (Station o : instead) if (stations.containsKey(o)) return stations.get(o);
            return pass;
        }

        /** The table whose place this is: its cloth, its post, or its chair. */
        @Nullable
        public Table table(BlockPos p) {
            for (Table t : tables) if (t.cell.equals(p) || t.cell.below().equals(p) || t.seat.equals(p)) return t;
            return null;
        }

        /** The table by this number. */
        @Nullable
        public Table table(int number) {
            for (Table t : tables) if (t.number == number) return t;
            return null;
        }

        /** "the restaurant", "the café". */
        public String words() {
            return kind == Kind.CAFE ? "the café" : "the " + kind.structure;
        }

        /** The middle of the place: for who is in it. */
        public BlockPos centre() {
            return building.anchor();
        }
    }

    private static final Map<Ledger.Building, Place> PLACES = new ConcurrentHashMap<>();

    public static void resetForTests() {
        PLACES.clear();
    }

    /** Read every place again next time it is asked for (a test that built one; a renovation). */
    public static void forget() {
        PLACES.clear();
    }

    // ------------------------------------------------------------------ finding them

    /** Every place to eat out in a town that is loaded, the restaurant first. */
    public static List<Place> of(ServerLevel level, UUID village) {
        List<Place> out = new ArrayList<>();
        for (Kind k : new Kind[]{ Kind.RESTAURANT, Kind.CAFE, Kind.TAVERN }) {
            for (Ledger.Building b : Ledger.buildings(village)) {
                if (!b.structure().equals(k.structure) || !level.isLoaded(b.anchor())) continue;
                Place p = place(level, village, b);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    /** This building as a place to eat out (read off it now, or kept from a while ago), or null if it is none. */
    @Nullable
    public static Place place(ServerLevel level, UUID village, Ledger.Building b) {
        Kind kind = Kind.of(b.structure());
        if (kind == null) return null;
        Place p = PLACES.get(b);
        long now = level.getGameTime();
        if (p != null && now - p.read < KEEP && now >= p.read) return p;
        p = read(level, village, b, kind, now);
        PLACES.put(b, p);
        return p;
    }

    /** The place this block is in (a table, a chair, the counter, the menu board), or null. */
    @Nullable
    public static Place at(ServerLevel level, BlockPos pos) {
        Villages.Village v = Villages.nearest(level, pos, 128);
        if (v == null) return null;
        Place best = null;
        double bestD = Double.MAX_VALUE;
        for (Place p : of(level, v.id())) {
            int half = BuildGoal.halfOf(p.building.structure()) + 2;
            BlockPos a = p.building.anchor();
            if (Math.abs(pos.getX() - a.getX()) > half || Math.abs(pos.getZ() - a.getZ()) > half || Math.abs(pos.getY() - a.getY()) > 6) continue;
            double d = a.distSqr(pos);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    /** The place of this kind nearest here in a town, or null. */
    @Nullable
    public static Place nearest(ServerLevel level, UUID village, @Nullable Kind kind, BlockPos near) {
        Place best = null;
        double bestD = Double.MAX_VALUE;
        for (Place p : of(level, village)) {
            if (kind != null && p.kind != kind) continue;
            double d = p.centre().distSqr(near);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    /** Does a town want a restaurant: from the Stone Age, once it has its café, a cook and two dozen folk. */
    public static boolean wanted(UUID village, int folk) {
        if (folk < RESTAURANT_FROM || Villages.ageOf(village).ordinal() < Villages.Age.STONE.ordinal()) return false;
        if (!stands(village, "cafe")) return false;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == AssistantEntity.StationTask.COOK) return true;
        return false;
    }

    /** Why, for the board: "a restaurant, ...". */
    public static String why(UUID village) {
        return "a restaurant, a dining room of " + Villages.headcount(village) + " folk's evenings out: tables by the windows, a kitchen"
            + " behind the pass, and waiters to bring it to you";
    }

    /** How many tables the town's places have between them (for its waiters: Hospitality.want). Read off the drawings:
     *  no world needed. */
    static int tablesIn(UUID village) {
        int n = 0;
        for (Place p : PLACES.values()) if (p.village.equals(village)) n += p.tables.size();
        if (n > 0) return n;
        for (Ledger.Building b : Ledger.buildings(village)) {
            Kind k = Kind.of(b.structure());
            if (k != null) n += k == Kind.RESTAURANT ? 6 : 2;
        }
        return n;
    }

    /** The trade a town's floor staff serve: its restaurant's, else its café's, else its tavern's. */
    static ServiceRole servedBy(UUID village) {
        if (stands(village, RESTAURANT)) return ServiceRole.RESTAURANT;
        if (stands(village, "cafe")) return ServiceRole.CAFE;
        return ServiceRole.TAVERN;
    }

    /** Does the town have one of these standing: finished (Villages) or on its books (the ledger the places are read
     *  from: a renovated or an older town's building counts too). */
    static boolean stands(UUID village, String structure) {
        if (Villages.hasBuilt(village, structure)) return true;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(structure)) return true;
        return false;
    }

    // ------------------------------------------------------------------ reading a place

    private static Place read(ServerLevel level, UUID village, Ledger.Building b, Kind kind, long now) {
        List<BuildGoal.Placement> plan = BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13);
        List<Table> tables = new ArrayList<>();
        Map<Station, BlockPos> stations = new EnumMap<>(Station.class);
        BlockPos cauldron = null, counter = null;
        for (BuildGoal.Placement pl : plan) {
            BlockPos p = pl.pos();
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof FenceBlock) {
                BlockState top = level.getBlockState(p.above());
                if (PlaceSettingBlock.isCloth(top) || top.getBlock() instanceof PlaceSettingBlock) {
                    BlockPos seat = seatFacing(level, p);
                    if (seat != null) {
                        Direction looks = Direction.getNearest(p.getX() - seat.getX(), 0, p.getZ() - seat.getZ());
                        tables.add(new Table(tables.size() + 1, p.above().immutable(), seat, looks));
                    }
                }
            } else if (s.getBlock() instanceof SmokerBlock) stations.putIfAbsent(Station.SMOKER, p.immutable());
            else if (s.getBlock() instanceof FurnaceBlock) stations.putIfAbsent(Station.FURNACE, p.immutable());
            else if (s.getBlock() instanceof CampfireBlock) stations.putIfAbsent(Station.HEARTH, p.immutable());
            else if (s.getBlock() instanceof CraftingTableBlock) stations.putIfAbsent(Station.BENCH, p.immutable());
            else if (s.getBlock() instanceof BrewingStandBlock) stations.putIfAbsent(Station.BREWING, p.immutable());
            else if (s.getBlock() instanceof AbstractCauldronBlock && cauldron == null) cauldron = p.immutable();
            else if (s.getBlock() instanceof com.jrpetty.mcassistant.block.ServiceCounterBlock && counter == null) counter = p.immutable();
        }
        if (counter == null) {
            List<BlockPos> casks = Cafe.counters(b);
            if (!casks.isEmpty()) counter = casks.get(0);
        }
        BlockPos pass = counter != null ? counter : stations.getOrDefault(Station.BENCH, b.anchor());
        BlockPos door = TownLife.fittings(b).door();
        return new Place(kind, b, village, List.copyOf(tables), stations, pass, cauldron != null ? cauldron : pass, door,
            boardSpot(level, b, door), now);
    }

    /** The chair beside this post: a stair the right way up, its back away from the table if one is (else any). */
    @Nullable
    private static BlockPos seatFacing(ServerLevel level, BlockPos post) {
        BlockPos any = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos c = post.relative(d);
            BlockState s = level.getBlockState(c);
            if (!(s.getBlock() instanceof StairBlock) || s.getValue(StairBlock.HALF) != Half.BOTTOM) continue;
            if (c.relative(s.getValue(StairBlock.FACING).getOpposite()).equals(post)) return c.immutable();
            if (any == null) any = c.immutable();
        }
        return any;
    }

    /** Where the menu board hangs: on the front wall beside the door, at head height, facing the street. */
    @Nullable
    private static BlockPos boardSpot(ServerLevel level, Ledger.Building b, @Nullable BlockPos door) {
        if (door == null) return null;
        Direction out = StoreFloor.front(b.anchor(), door);
        for (int side : new int[]{ 1, -1, 2, -2 }) {
            BlockPos wall = door.relative(out.getClockWise(), side).above();
            BlockPos at = wall.relative(out);
            BlockState w = level.getBlockState(wall), here = level.getBlockState(at);
            if (here.getBlock() instanceof WallSignBlock) return at.immutable();
            if (w.isFaceSturdy(level, wall, out) && here.isAir()) return at.immutable();
        }
        return null;
    }

    // ------------------------------------------------------------------ the menu board

    /**
     * The menu board by the door, written up for the day (Dining's round, once a day and when the special changes): the
     * place's name, today's special and two dishes with their prices, the hours. A new board is a sign out of the stores
     * (Crafts.sign: a sign, or two planks), put up through the town's one gate for its blocks.
     */
    static void writeBoard(ServerLevel level, Villages.Village v, Place p, String[] lines) {
        if (p.board == null || !level.isLoaded(p.board)) return;
        BlockState here = level.getBlockState(p.board);
        if (!(here.getBlock() instanceof WallSignBlock)) {
            if (!here.isAir() || !TownBlocks.mayBuild(level, v, p.board, p.building.anchor(), TownBlocks.Why.SIGN)) return;
            if (!Crafts.sign(level, v)) return;
            Direction out = p.door == null ? Direction.NORTH : StoreFloor.front(p.building.anchor(), p.door);
            level.setBlock(p.board, net.minecraft.world.level.block.Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, out), 3);
        }
        if (level.getBlockEntity(p.board) instanceof SignBlockEntity sign) TownLife.write(sign, lines);
    }

    /** Is this the menu board of a place? */
    static boolean isBoard(ServerLevel level, BlockPos pos) {
        Place p = at(level, pos);
        return p != null && pos.equals(p.board);
    }

    // ------------------------------------------------------------------ the front of the house at its post

    private static final Map<UUID, Long> WALKING = new ConcurrentHashMap<>();

    /**
     * The front of the house at its post while its place is open (Hospitality.work): the barkeep behind the bar, the
     * host at the counter, the waiter by the pass looking over the tables, the pot washer at the wash, the innkeeper at
     * the inn's door. Stuck a long while at a doorway, it is put at its post. False when it has no post open now.
     */
    static boolean standAtPost(VillageFolkEntity f, ServerLevel level, Hospitality.Role r) {
        UUID id = f.ownerId();
        if (id == null) return false;
        BlockPos post;
        String what;
        if (r == Hospitality.Role.INNKEEPER) {
            Ledger.Building inn = Hospitality.placeFor(id, r);
            if (inn == null) return false;
            BlockPos door = TownLife.fittings(inn).door();
            post = door != null ? door.relative(StoreFloor.front(inn.anchor(), door).getOpposite(), 2) : inn.anchor();
            what = "Keeping the inn's rooms";
        } else {
            Kind kind = r == Hospitality.Role.BARKEEP ? Kind.TAVERN : null;
            Place p = nearest(level, id, kind, f.blockPosition());
            if (p == null || !ServicePoints.HOURS_OFF_FOR_TESTS && !p.kind.role.hours.isOpen(level.getDayTime() % 24000L)) return false;
            BlockPos at = r == Hospitality.Role.POT_WASHER ? p.wash : p.pass;
            post = behind(level, at, p.door);
            what = switch (r) {
                case BARKEEP -> "Behind the bar at " + p.words();
                case HOST -> "Greeting folk at " + p.words();
                case POT_WASHER -> "At the sink at " + p.words();
                default -> "Waiting tables at " + p.words();
            };
        }
        double d = f.distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5);
        Dining.DOING.put(f.getUUID(), what);
        if (d > 2.0) {
            long started = WALKING.computeIfAbsent(f.getUUID(), k -> level.getGameTime());
            if (level.getGameTime() - started > Dining.STUCK) {
                f.teleportTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5);
                WALKING.remove(f.getUUID());
            } else if (f.getNavigation().isDone()) {
                f.walkTo(post, 0.8D);
            }
            return true;
        }
        WALKING.remove(f.getUUID());
        f.getNavigation().stop();
        return true;
    }

    /** Where somebody stands to work at a block: the side away from the door (behind a counter), else any side clear. */
    static BlockPos behind(ServerLevel level, BlockPos at, @Nullable BlockPos door) {
        Direction front = StoreFloor.front(at, door);
        for (Direction d : new Direction[]{ front.getOpposite(), front.getClockWise(), front.getCounterClockWise(), front }) {
            BlockPos s = at.relative(d);
            if (StoreFloor.standable(level, s)) return s.immutable();
        }
        return at.relative(front).immutable();
    }
}
