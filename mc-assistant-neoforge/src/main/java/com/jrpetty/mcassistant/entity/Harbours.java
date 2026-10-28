package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [trade-world] The harbour: a town by water wide enough for a ship builds a harbour office on the bank, runs a stone
 * pier out from its door into deep water, and in time puts up a lighthouse beside it; then merchant ships from the lands
 * over the sea call (ShipCalls, ForeignShips), and a harbourmaster sees to the quay, the light and the dues.
 *
 * <ul>
 * <li><b>The shore.</b> Every couple of days a town of ten or more looks round its edge, the four ways and either side
 *     of them, for where it first meets water: a low bank, the pier's fourteen blocks of water out from it, and beyond
 *     the pier a lane fifty blocks long and five across, two deep down its middle, all open water, out into water that
 *     is wide on every side (a sea, a great lake: not a pond or a stream). And on the bank, a dry level place for the
 *     office, its door to the water, and another beside it for the light. The nearest such place to the town's heart is
 *     its harbour; a town with none is told why (the books say it), and asks again in a few days.</li>
 * <li><b>The office and the light</b> are buildings like any other (harbour.txt, seamark.txt): wanted, stocked and built
 *     by the town's builders, only on the bank where the shore was found (Villages.siteFor). The office is wanted from
 *     the Stone Age in a town of twelve, the light once the pier is out and the town is sixteen.</li>
 * <li><b>The pier</b> is laid from the office's door straight out over the water by a hand at the town's works, out of
 *     the stores: stone posts to the bottom every third block, a deck three wide of stone bricks (or cobblestone, or
 *     planks if the stores have no stone), stone bollards along the side the ships tie up at, lamp posts at its end, and
 *     the harbour's casks on the bank. Each column of a post is laid whole or not at all; a pier a player has broken is
 *     mended when it is next looked at.</li>
 * <li><b>The light.</b> Its beacon is a fire in the glass lamp room. At dusk the harbourmaster (or a hand) lights it with
 *     a coal or charcoal from the stores (two logs if there is none); at dawn it is put out. With nothing to burn it stands
 *     dark, and the books and the chronicle say so. A lit light brings the ships oftener (ShipCalls).</li>
 * <li><b>The harbourmaster</b> is a post, not a trade: the town's best fisher (else a steady hand), who keeps its trade
 *     and gives the harbour part of its day, keeps a quarter of the dues the ships pay, and is the one to ask when the
 *     next ship is due.</li>
 * </ul>
 * Everything is kept in the town's notes (Ledger), so a restart finds the shore, the pier, the light and any ship where
 * they were. Cost: the survey is a few hundred height-map reads once in two days; the pier a handful of blocks a visit;
 * the light two block reads a visit.
 */
public final class Harbours {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Harbours() {}

    public static final String OFFICE = "harbour", LIGHT = "seamark";
    /** The office is wanted in a town of this many (Stone Age on); the light once it is this many and the pier is out. */
    static final int FROM_FOLK = 12, LIGHT_FOLK = 16;
    /** How many blocks the pier runs out over the water. */
    static final int PIER = 14;
    /** How far past the town's edge the shore is looked for. */
    static final int REACH = 48;
    /** Pieces of the pier laid a visit. */
    static final int PIER_STEPS = 6;
    /** The works the pier and the light are (TownJobs). */
    static final String WORKS = "harbour";

    // ------------------------------------------------------------------ the shore

    /**
     * Where the harbour is: the water's edge the pier runs out from ({@code water}: the first block of water, at the
     * surface), which way is out to sea, the water's level, the pier's deck, and the office's and the light's places on
     * the bank (their backs to the land, their doors to the water).
     */
    public static final class Shore {
        BlockPos water = BlockPos.ZERO;
        Direction out = Direction.NORTH;
        int surface, deck;
        BlockPos office = BlockPos.ZERO;
        Direction officeBack = Direction.SOUTH;
        @Nullable BlockPos light;
        Direction lightBack = Direction.SOUTH;

        Direction side() { return out.getClockWise(); }

        public BlockPos water() { return water; }
        public Direction out() { return out; }
        public int surface() { return surface; }
        public BlockPos office() { return office; }
        @Nullable public BlockPos light() { return light; }

        /** A place on the harbour: k out from the shore, lat across, dy over the waterline. */
        BlockPos at(int k, int lat, int dy) {
            return ShipHull.at(this, k, lat, dy);
        }

        /** A place on the pier's deck (where one stands): k out, lat across. */
        BlockPos onDeck(int k, int lat) {
            BlockPos p = at(k, lat, 0);
            return new BlockPos(p.getX(), deck + 1, p.getZ());
        }

        String encode() {
            return water.getX() + "," + water.getY() + "," + water.getZ() + "," + out.get2DDataValue() + "," + surface + "," + deck + ","
                + office.getX() + "," + office.getY() + "," + office.getZ() + "," + officeBack.get2DDataValue() + ","
                + (light == null ? "-,-,-" : light.getX() + "," + light.getY() + "," + light.getZ()) + "," + lightBack.get2DDataValue();
        }

        @Nullable
        static Shore decode(@Nullable String s) {
            if (s == null || s.isEmpty() || s.startsWith("none")) return null;
            String[] p = s.split(",");
            if (p.length < 14) return null;
            try {
                Shore o = new Shore();
                o.water = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                o.out = Direction.from2DDataValue(Integer.parseInt(p[3]));
                o.surface = Integer.parseInt(p[4]);
                o.deck = Integer.parseInt(p[5]);
                o.office = new BlockPos(Integer.parseInt(p[6]), Integer.parseInt(p[7]), Integer.parseInt(p[8]));
                o.officeBack = Direction.from2DDataValue(Integer.parseInt(p[9]));
                o.light = p[10].equals("-") ? null : new BlockPos(Integer.parseInt(p[10]), Integer.parseInt(p[11]), Integer.parseInt(p[12]));
                o.lightBack = Direction.from2DDataValue(Integer.parseInt(p[13]));
                return o;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    static final String SHORE = "tw.shore", PIER_NOTE = "tw.pier", MASTER = "tw.master", LIT = "tw.lit", DARK = "tw.dark",
        LIGHTS = "tw.lights";

    private static final Map<UUID, Shore> SHORES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> MASTER_DAY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SHORES.clear();
        MASTER_DAY.clear();
        ShipCalls.resetForTests();
    }

    /** The town's harbour shore, if it has found one. */
    @Nullable
    public static Shore shore(@Nullable UUID village) {
        if (village == null) return null;
        Shore s = SHORES.get(village);
        if (s != null) return s;
        s = Shore.decode(Ledger.note(village, SHORE));
        if (s != null) SHORES.put(village, s);
        return s;
    }

    /** The shore forgotten from memory, to be read again from the notes (the stage sets one down). */
    static void resetShore(UUID village) {
        SHORES.remove(village);
    }

    /** Why the town has no harbour, in words ("" if it has, or has not looked yet). */
    static String noShore(UUID village) {
        String n = Ledger.note(village, SHORE);
        if (n == null || !n.startsWith("none")) return "";
        String[] p = n.split("\\|", 3);
        return p.length > 2 ? p[2] : "";
    }

    /** The day the town last looked for its shore and found none (-1: it has not looked, or found one). */
    private static long lookedNone(UUID village) {
        String n = Ledger.note(village, SHORE);
        if (n == null || !n.startsWith("none")) return -1;
        String[] p = n.split("\\|", 3);
        try {
            return p.length > 1 ? Long.parseLong(p[1]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Look round the town's edge for its harbour (see the class's words). Keeps what it found in the town's notes; returns
     * it, or null (and why, in the notes) if there is none. Ground not loaded is passed over (and looked at again later).
     */
    @Nullable
    static Shore survey(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        BlockPos c = v.centre();
        int reach = Math.min(160, Villages.townReach(id) + REACH);
        Shore best = null;
        double bestD = Double.MAX_VALUE;
        int waters = 0, unloaded = 0;
        String why = "no water wide and deep enough for a ship within reach of the town";
        int[] offs = { 0, 8, -8, 16, -16, 24, -24 };
        for (Direction d : Direction.Plane.HORIZONTAL) {
            Direction side = d.getClockWise();
            for (int o : offs) {
                for (int r = 6; r <= reach; r++) {
                    int x = c.getX() + d.getStepX() * r + side.getStepX() * o, z = c.getZ() + d.getStepZ() * r + side.getStepZ() * o;
                    if (!level.hasChunk(x >> 4, z >> 4)) {
                        unloaded++;
                        break;
                    }
                    Roads.Ground g = Roads.ground(level, x, z);
                    if (g == null) break;
                    if (!g.water()) continue;
                    waters++;
                    String[] whyNot = new String[1];
                    Shore s = fit(level, id, x, z, d, g.y(), whyNot);
                    if (s != null) {
                        double dd = s.water.distSqr(c);
                        if (dd < bestD) {
                            bestD = dd;
                            best = s;
                        }
                    } else if (whyNot[0] != null && waters > 0) {
                        why = whyNot[0];
                    }
                    break;
                }
            }
        }
        if (best == null) {
            if (unloaded > 0 && waters == 0) return null;                      // half the land unseen: ask again later
            Ledger.note(id, SHORE, "none|" + day + "|" + (waters == 0 ? "no water within reach of the town" : why));
            SHORES.remove(id);
            return null;
        }
        boolean fresh = shore(id) == null;
        SHORES.put(id, best);
        Ledger.note(id, SHORE, best.encode());
        if (fresh) {
            LOG.info("[MCA-HARBOUR] {}: a harbour on the {} shore at {} (the water at {}, the office at {}, the light at {})",
                Villages.name(id), best.out.getName(), best.water.toShortString(), best.surface, best.office.toShortString(),
                best.light == null ? "nowhere" : best.light.toShortString());
        }
        return best;
    }

    /**
     * Is this the place for a harbour: water {@code (x, z)} at {@code surface}, out to sea {@code d}? The bank, the pier's
     * water, the ships' lane and the open water about it, and the places for the office and the light. Null if not, and
     * the reason in whyNot[0].
     */
    @Nullable
    static Shore fit(ServerLevel level, UUID village, int x, int z, Direction d, int surface, String[] whyNot) {
        Shore s = new Shore();
        s.water = new BlockPos(x, surface, z);
        s.out = d;
        s.surface = surface;
        BlockPos bankAt = s.at(-1, 0, 0);
        if (!level.hasChunk(bankAt.getX() >> 4, bankAt.getZ() >> 4)) return null;
        Roads.Ground bank = Roads.ground(level, bankAt.getX(), bankAt.getZ());
        if (bank == null || bank.water() || bank.y() < surface - 1 || bank.y() > surface + 2) {
            whyNot[0] = "the banks are too steep for a pier";
            return null;
        }
        s.deck = Math.max(surface, Math.min(surface + 1, bank.y()));
        // The pier's water.
        for (int k = 0; k < PIER; k++) {
            for (int lat = -1; lat <= 1; lat++) {
                if (!water(level, s.at(k, lat, 0))) {
                    whyNot[0] = "the water by the bank is too narrow for a pier";
                    return null;
                }
            }
        }
        // The ships' lane: open water five across all the way out, two deep down its middle.
        int laneEnd = ShipHull.OFFING + ShipHull.LENGTH - 1;
        for (int k = ShipHull.BERTH; k <= laneEnd; k++) {
            for (int lat = ShipHull.PLANK_LAT; lat <= ShipHull.MIDDLE + 2; lat++) {
                if (lat == ShipHull.PLANK_LAT && k > PIER) continue;
                BlockPos p = s.at(k, lat, 0);
                if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return null;
                if (!water(level, p) || !level.getBlockState(p.above()).isAir()) {
                    whyNot[0] = "no clear water out to sea for a ship to come in by";
                    return null;
                }
                if (Math.abs(lat - ShipHull.MIDDLE) <= 1 && k >= ShipHull.BERTH && !level.getBlockState(p.below()).is(Blocks.WATER)) {
                    whyNot[0] = "the water off the bank is too shallow for a ship";
                    return null;
                }
            }
        }
        // Wide water about the lane's far half: a sea or a great lake, not a stream.
        int wet = 0, all = 0;
        for (int k = 20; k <= 52; k += 4) {
            for (int lat = -12; lat <= 20; lat += 4) {
                BlockPos p = s.at(k, lat, 0);
                if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) continue;
                all++;
                Roads.Ground g = Roads.ground(level, p.getX(), p.getZ());
                if (g != null && g.water()) wet++;
            }
        }
        if (all == 0 || wet * 10 < all * 7) {
            whyNot[0] = "the water is not wide enough for ships from over the sea";
            return null;
        }
        // The office on the bank, its door to the water: its front step on the bank, its walls behind.
        int[] oh = BuildGoal.footprint(OFFICE);
        BlockPos office = place(level, village, s, -(oh[1] + 1), 0, oh[0], oh[1]);
        if (office == null) {
            whyNot[0] = "no dry, level ground on the bank for a harbour office";
            return null;
        }
        s.office = office;
        s.officeBack = d.getOpposite();
        // The light beside it, along the bank, away from the ships' side.
        int[] lh = BuildGoal.footprint(LIGHT);
        for (int lat : new int[]{ -(oh[0] + lh[0] + 2), -(oh[0] + lh[0] + 4), -(oh[0] + lh[0] + 6), oh[0] + lh[0] + 2,
                oh[0] + lh[0] + 4, oh[0] + lh[0] + 6 }) {
            BlockPos l = place(level, village, s, -(lh[1] + 2), lat, lh[0], lh[1]);
            if (l != null) {
                s.light = l;
                s.lightBack = d.getOpposite();
                break;
            }
        }
        return s;
    }

    /** Open water at the surface here (and nothing growing in it)? */
    private static boolean water(ServerLevel level, BlockPos p) {
        return level.hasChunk(p.getX() >> 4, p.getZ() >> 4) && level.getBlockState(p).is(Blocks.WATER);
    }

    /**
     * A building's place on the bank: its anchor k back from the water and lat across, its footprint (half across, half
     * deep, a block's margin round it) dry, standable and level within a block of the anchor, nothing of the town's near
     * it and no village of villagers. The anchor (its feet), or null.
     */
    @Nullable
    private static BlockPos place(ServerLevel level, UUID village, Shore s, int k, int lat, int ha, int hd) {
        BlockPos a = s.at(k, lat, 0);
        BlockPos anchor = Divers.standable(level, a.getX(), a.getZ());
        if (anchor == null) return null;
        for (int dk = -hd - 1; dk <= hd + 1; dk++) {
            for (int dl = -ha - 1; dl <= ha + 1; dl++) {
                BlockPos c = s.at(k + dk, lat + dl, 0);
                if (k + dk >= 0) continue;                                       // the water's side of the front step
                BlockPos f = Divers.standable(level, c.getX(), c.getZ());
                if (f == null || Math.abs(f.getY() - anchor.getY()) > 1) return null;
            }
        }
        int r = Math.max(ha, hd) + 4;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (Math.abs(b.anchor().getX() - anchor.getX()) <= r + 3 && Math.abs(b.anchor().getZ() - anchor.getZ()) <= r + 3) return null;
        }
        BlockPos board = VillageBoards.boardOf(village);
        if (board != null && Math.abs(board.getX() - anchor.getX()) <= r && Math.abs(board.getZ() - anchor.getZ()) <= r) return null;
        if (VanillaVillages.meets(level, anchor.getX() - r, anchor.getZ() - r, anchor.getX() + r, anchor.getZ() + r, VanillaVillages.MARGIN)) return null;
        return anchor;
    }

    // ------------------------------------------------------------------ the buildings

    /** [Villages.siteFor] The office's and the light's places on the bank, or null for anything else (or with no shore). */
    @Nullable
    public static Villages.Site site(UUID village, String project) {
        Shore s = shore(village);
        if (s == null) return null;
        if (project.equals(OFFICE)) return new Villages.Site(s.office, s.officeBack, 0);
        if (project.equals(LIGHT) && s.light != null) return new Villages.Site(s.light, s.lightBack, 0);
        return null;
    }

    /** [cross-link] May the town raise its lighthouse? (The inventions may want it learned first; here, always.) */
    public static boolean lightAllowed(UUID village) {
        return true;
    }

    /** Is the harbour office up, and the pier out? Then ships may call. */
    public static boolean ready(@Nullable UUID village) {
        return village != null && shore(village) != null && Villages.builtStructure(village, OFFICE) != null && pierDone(village);
    }

    /** [Villages.projectsWantedInOrder] The office, and the light after it, on the town's list (among the amenities). */
    public static void wanted(UUID village, int folk, List<String> extras) {
        if (Villages.ageOf(village).ordinal() < Villages.Age.STONE.ordinal()) return;
        Shore s = shore(village);
        if (s == null) return;
        if (Villages.builtStructure(village, OFFICE) == null) {
            if (folk >= FROM_FOLK) extras.add(OFFICE);
            return;
        }
        if (s.light != null && folk >= LIGHT_FOLK && pierDone(village) && lightAllowed(village)
                && Villages.builtStructure(village, LIGHT) == null) extras.add(LIGHT);
    }

    /** Why the builders put it up (the builders' list, the planner, the board). */
    public static String why(UUID village, String project) {
        return project.equals(LIGHT)
            ? "a lighthouse on the shore by the harbour, lit at dusk so the ships from over the sea can find the town in the dark"
            : "a harbour office on the bank, with a stone pier out into deep water, so merchant ships from over the sea can call";
    }

    // ------------------------------------------------------------------ the pier

    /** One piece of the pier: where, and what it is. */
    record Piece(BlockPos pos, Kind kind) {}

    enum Kind { POST, DECK, BOLLARD, LAMP_POST, CASK }

    /** The pier's pieces, in the order they are laid: out from the bank, posts before the deck over them, then the rest. */
    static List<Piece> plan(Shore s) {
        List<Piece> out = new ArrayList<>();
        for (int k = 0; k < PIER; k++) {
            if (k % 3 == 1) for (int lat : new int[]{ -1, 1 }) out.add(new Piece(new BlockPos(s.at(k, lat, 0).getX(), s.deck - 1, s.at(k, lat, 0).getZ()), Kind.POST));
            for (int lat = -1; lat <= 1; lat++) {
                BlockPos p = s.at(k, lat, 0);
                out.add(new Piece(new BlockPos(p.getX(), s.deck, p.getZ()), Kind.DECK));
            }
        }
        for (int k = 3; k < PIER; k += 4) out.add(new Piece(s.onDeck(k, 1), Kind.BOLLARD));
        out.add(new Piece(s.onDeck(PIER - 1, -1), Kind.LAMP_POST));
        out.add(new Piece(s.onDeck(PIER - 1, 1), Kind.LAMP_POST));
        for (int lat : new int[]{ -2, -3 }) {
            BlockPos p = s.at(-1, lat, 0);
            out.add(new Piece(p, Kind.CASK));
        }
        return out;
    }

    /** Has this piece still to be laid? */
    static boolean missing(ServerLevel level, Shore s, Piece p) {
        BlockState st = level.getBlockState(p.pos());
        return switch (p.kind()) {
            case POST, DECK -> st.isAir() || !st.getFluidState().isEmpty() && !st.isSolid() || st.canBeReplaced();
            case BOLLARD -> st.isAir();
            case LAMP_POST -> st.isAir();
            case CASK -> {
                BlockPos top = Visitors.surface(level, p.pos());
                yield level.getBlockState(top).isAir() && !level.getBlockState(top.below()).is(Blocks.BARREL)
                    && level.getBlockState(top.below()).getFluidState().isEmpty();
            }
        };
    }

    /** Has the pier been laid to its end? Kept in the notes once it is. */
    public static boolean pierDone(@Nullable UUID village) {
        return village != null && "done".equals(Ledger.note(village, PIER_NOTE));
    }

    /** Stone for the pier out of the stores: stone bricks, else cobblestone (all or nothing). The block, or null. */
    @Nullable
    static Block stone(ServerLevel level, Villages.Village v, int n) {
        if (TownWork.take(level, v, s -> s.is(Items.STONE_BRICKS), n)) return Blocks.STONE_BRICKS;
        if (TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), n)) return Blocks.COBBLESTONE;
        return null;
    }

    /**
     * Lay up to so many pieces of the pier, a hand at the works there first. Returns how many went down; what is laid is
     * paid for out of the stores as it goes, and a piece the stores cannot pay for waits.
     */
    static int layPier(ServerLevel level, Villages.Village v, Shore s, int budget) {
        UUID id = v.id();
        if (Villages.builtStructure(id, OFFICE) == null) return 0;
        List<Piece> plan = plan(s);
        int laid = 0;
        boolean waiting = false;
        for (Piece p : plan) {
            if (laid >= budget) break;
            if (!level.hasChunk(p.pos().getX() >> 4, p.pos().getZ() >> 4)) return laid;
            if (!missing(level, s, p)) continue;
            if (!TownJobs.atWork(level, v, WORKS, p.pos(), "laying the harbour's pier", StationTask.FISH)) return laid;
            if (!layPiece(level, v, s, p)) {
                waiting = true;
                break;
            }
            laid++;
        }
        if (!waiting && laid < budget) {
            boolean any = false;
            for (Piece p : plan) {
                if (level.hasChunk(p.pos().getX() >> 4, p.pos().getZ() >> 4) && missing(level, s, p) && p.kind() != Kind.CASK) { any = true; break; }
            }
            if (!any && !pierDone(id)) {
                String was = Ledger.note(id, PIER_NOTE);
                boolean mended = was != null && was.startsWith("mending");          // [realm-polish] a mended pier is not finished anew
                Ledger.note(id, PIER_NOTE, "done");
                long day = level.getDayTime() / 24000L;
                if (mended) {
                    Villages.tell(id, day, "the harbour's pier was mended where it had been broken");
                    LOG.info("[MCA-HARBOUR] {}: the pier is mended", Villages.name(id));
                } else {
                    Villages.tell(id, day, "the harbour's pier was finished: " + PIER + " blocks out into deep water, where the ships can tie up");
                    LOG.info("[MCA-HARBOUR] {}: the pier is finished", Villages.name(id));
                    ShipCalls.harbourOpened(level, v);
                }
            }
        }
        if (waiting) {
            String was = Ledger.note(id, PIER_NOTE);                            // [realm-polish] mending, it stays mending while it waits
            Ledger.note(id, PIER_NOTE, was != null && was.startsWith("mending") ? "mending, waiting on stone" : "waiting on stone");
        }
        return laid;
    }

    /** One piece set down, paid for. False if the stores could not pay for it. */
    static boolean layPiece(ServerLevel level, Villages.Village v, Shore s, Piece p) {
        switch (p.kind()) {
            case POST -> {
                // The whole post at once, from the bottom up: a half-built post holds nothing up.
                List<BlockPos> col = new ArrayList<>();
                for (BlockPos q = p.pos(); q.getY() >= level.getMinBuildHeight() && col.size() < 24; q = q.below()) {
                    BlockState st = level.getBlockState(q);
                    if (!(st.isAir() || st.canBeReplaced() || !st.getFluidState().isEmpty() && !st.isSolid())) break;
                    col.add(q);
                }
                if (col.isEmpty()) return true;
                Block b = stone(level, v, col.size());
                if (b == null) {
                    if (!Crafts.usePlanks(level, v, col.size())) return false;
                    b = Blocks.SPRUCE_PLANKS;
                }
                for (int i = col.size() - 1; i >= 0; i--) level.setBlock(col.get(i), b.defaultBlockState(), Block.UPDATE_ALL);
            }
            case DECK -> {
                Block b = stone(level, v, 1);
                if (b == null) {
                    if (!Crafts.usePlanks(level, v, 1)) return false;
                    b = Blocks.SPRUCE_PLANKS;
                }
                level.setBlock(p.pos(), b.defaultBlockState(), Block.UPDATE_ALL);
                clearAbove(level, p.pos());
            }
            case BOLLARD -> {
                // A bollard dressed from a cobblestone at the mason's cutter (one to one), or a wall block put by.
                if (!TownWork.take(level, v, st -> st.is(Items.COBBLESTONE_WALL) || st.is(Items.STONE_BRICK_WALL), 1)
                        && !TownWork.take(level, v, st -> st.is(Items.COBBLESTONE), 1)) return false;
                level.setBlock(p.pos(), Blocks.COBBLESTONE_WALL.defaultBlockState(), Block.UPDATE_ALL);
            }
            case LAMP_POST -> {
                if (level.getBlockState(p.pos()).isAir()) {
                    if (!Crafts.fence(level, v)) return false;
                    level.setBlock(p.pos(), Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
                }
                Block light = Masonry.light(level, v);
                if (light != null && level.getBlockState(p.pos().above()).isAir()) level.setBlock(p.pos().above(), light.defaultBlockState(), Block.UPDATE_ALL);
            }
            case CASK -> {
                BlockPos top = Visitors.surface(level, p.pos());
                if (!level.getBlockState(top).isAir()) return true;
                if (!TownWork.take(level, v, st -> st.is(Items.BARREL), 1) && !Crafts.usePlanks(level, v, 7)) return false;
                level.setBlock(top, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        level.playSound(null, p.pos(), SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.7F, 0.9F);
        return true;
    }

    private static void clearAbove(ServerLevel level, BlockPos deck) {
        for (int h = 1; h <= 2; h++) {
            BlockPos q = deck.above(h);
            BlockState st = level.getBlockState(q);
            if (!st.isAir() && st.canBeReplaced() && st.getFluidState().isEmpty()) level.setBlock(q, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** How many of the pier's pieces are laid, and of how many (the books). */
    static int[] pierCount(ServerLevel level, Shore s) {
        int laid = 0, all = 0;
        for (Piece p : plan(s)) {
            if (p.kind() == Kind.CASK) continue;
            all++;
            if (level.hasChunk(p.pos().getX() >> 4, p.pos().getZ() >> 4) && !missing(level, s, p)) laid++;
        }
        return new int[]{ laid, all };
    }

    // ------------------------------------------------------------------ the light

    /** The beacon's fire in the lamp room of the town's light, or null. */
    @Nullable
    static BlockPos beacon(UUID village) {
        Ledger.Building b = Villages.builtStructure(village, LIGHT);
        if (b == null) return null;
        for (BuildGoal.Placement p : BuildGoal.plan(LIGHT, b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CAMPFIRE) return p.pos();
        }
        return null;
    }

    /** Is the light burning now? */
    public static boolean lit(ServerLevel level, UUID village) {
        BlockPos at = beacon(village);
        if (at == null || !level.isLoaded(at)) return false;
        BlockState st = level.getBlockState(at);
        return st.getBlock() instanceof CampfireBlock && st.getValue(CampfireBlock.LIT);
    }

    /** Was the light lit last night (or tonight)? Ships come oftener to a lit harbour. */
    public static boolean litLately(UUID village, long day) {
        return parse(Ledger.note(village, LIT)) >= day - 1;
    }

    /**
     * The keeper's round (the town's visit): at dusk, the beacon lit with a coal from the stores, a hand there to light
     * it (after dark, the harbourmaster lights it from inside without waiting on the works); at dawn, put out. With nothing
     * to burn, it stands dark, and the chronicle says so (once in three days).
     */
    static void keepLight(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos at = beacon(id);
        if (at == null || !level.isLoaded(at)) return;
        BlockState st = level.getBlockState(at);
        if (st.isAir() && !beaconMade(level, v, at)) return;                // the builders had no fire for it: made, or not yet
        st = level.getBlockState(at);
        if (!(st.getBlock() instanceof CampfireBlock)) return;
        long dt = level.getDayTime(), t = dt % 24000L, day = dt / 24000L;
        boolean lit = st.getValue(CampfireBlock.LIT);
        boolean dusk = t >= 11000L && t < 12400L, night = t >= 12400L && t < 23200L, dawn = t >= 23200L || t < 1800L;
        if (!lit && (dusk || night) && parse(Ledger.note(id, LIT)) != day) {
            Ledger.Building tower = Villages.builtStructure(id, LIGHT);
            if (dusk && (tower == null || !TownJobs.atWork(level, v, WORKS, tower.anchor(), "lighting the lighthouse", StationTask.FISH))) {
                return;
            }
            if (night && master(level, v) == null) return;
            light(level, v, at, st, day);
        } else if (lit && dawn) {
            level.setBlock(at, st.setValue(CampfireBlock.LIT, false), Block.UPDATE_ALL);
            level.playSound(null, at, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 1.0F);
        }
    }

    /**
     * The lamp room built without its fire (the builders leave out what the stores had none of): the keeper makes one,
     * a campfire put by, or three logs, a coal and three sticks (two planks) out of the stores, and sets it down unlit.
     */
    static boolean beaconMade(ServerLevel level, Villages.Village v, BlockPos at) {
        boolean paid = TownWork.take(level, v, s -> s.is(Items.CAMPFIRE), 1);
        if (!paid && Market.stock(level, v.id(), s -> s.is(ItemTags.LOGS)) >= 4
                && Market.stock(level, v.id(), s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) >= 2 && Crafts.planks(level, v, 2)) {
            paid = TownWork.take(level, v, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 1)
                && TownWork.take(level, v, s -> s.is(ItemTags.LOGS), 3) && Crafts.usePlanks(level, v, 2);
        }
        if (!paid) return false;
        level.setBlock(at, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false), Block.UPDATE_ALL);
        return true;
    }

    /** The beacon lit, a coal (or charcoal, or two logs) out of the stores; or, with nothing to burn, left dark. */
    static boolean light(ServerLevel level, Villages.Village v, BlockPos at, BlockState st, long day) {
        UUID id = v.id();
        boolean fuel = TownWork.take(level, v, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 1)
            || TownWork.take(level, v, s -> s.is(ItemTags.LOGS_THAT_BURN), 2);
        if (!fuel) {
            if (day - parse(Ledger.note(id, DARK)) >= 3) {
                Villages.tell(id, day, "the lighthouse stood dark tonight: there was no coal in the stores to burn");
                Ledger.note(id, DARK, Long.toString(day));
            }
            return false;
        }
        level.setBlock(at, st.setValue(CampfireBlock.LIT, true), Block.UPDATE_ALL);
        level.playSound(null, at, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        Ledger.note(id, LIT, Long.toString(day));
        int n = (int) parse(Ledger.note(id, LIGHTS));
        Ledger.note(id, LIGHTS, Integer.toString(Math.max(0, n) + 1));
        if (n <= 0) {
            VillageFolkEntity m = master(level, v);
            Villages.tell(id, day, "the lighthouse was lit for the first time" + (m == null ? "" : ", " + m.displayNameCap() + " its keeper"));
        }
        return true;
    }

    // ------------------------------------------------------------------ the harbourmaster

    /**
     * The harbourmaster: the town's best fisher, else a steady grown hand (never the elder, a guard or a child), chosen
     * once a day and kept while it is fit. Null with nobody to hold it, or no harbour.
     */
    @Nullable
    public static VillageFolkEntity master(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.builtStructure(id, OFFICE) == null) return null;
        long day = level.getDayTime() / 24000L;
        VillageFolkEntity have = holder(level, id);
        if (have != null && (MASTER_DAY.getOrDefault(id, -1L) == day || fitToHold(have))) {
            MASTER_DAY.put(id, day);
            return have;
        }
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !fitToHold(f)) continue;
            int score = f.tradeLevel(StationTask.FISH) * 10 + (f.stationTask() == StationTask.FISH ? 40 : 0)
                + (f.stationTask() == StationTask.NONE ? 10 : 0) + f.getUUID().hashCode() % 3;
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        if (best != null && (have == null || !have.getUUID().equals(best.getUUID()))) {
            Ledger.note(id, MASTER, best.getUUID().toString());
            Villages.tell(id, day, best.displayNameCap() + " was made harbourmaster");
        }
        MASTER_DAY.put(id, day);
        return best;
    }

    private static boolean fitToHold(VillageFolkEntity f) {
        return f.isAlive() && !f.isBaby() && !f.isElder() && f.stationTask() != StationTask.GUARD && !Visitors.is(f)
            && f.trip() == null;
    }

    /** Whoever the notes say holds the post, if it is about. */
    @Nullable
    static VillageFolkEntity holder(ServerLevel level, UUID village) {
        String s = Ledger.note(village, MASTER);
        if (s == null || s.isEmpty()) return null;
        try {
            return level.getEntity(UUID.fromString(s)) instanceof VillageFolkEntity f && f.isAlive() && village.equals(f.ownerId()) ? f : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Is this folk its town's harbourmaster? */
    public static boolean isMaster(VillageFolkEntity f) {
        UUID v = f.ownerId();
        return v != null && f.getUUID().toString().equals(Ledger.note(v, MASTER));
    }

    // ------------------------------------------------------------------ the town's visit

    /** [TownLife.tick, every ten seconds] The shore looked for, the pier laid, the light kept, the ships (ShipCalls). */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        int folk = Villages.headcount(id);
        Shore s = shore(id);
        if (s == null) {
            if (folk < 10 || Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal()) return;
            long looked = lookedNone(id);
            if (looked >= 0 && day - looked < 2) return;
            if (!Land.areaLoaded(level, v.centre(), 32)) return;
            s = survey(level, v);
            if (s == null) return;
        }
        if (Villages.builtStructure(id, OFFICE) == null) return;
        master(level, v);
        if (!pierDone(id)) layPier(level, v, s, PIER_STEPS);
        else if (level.getGameTime() % 2400L < 200L) {
            // Once in a while: a pier somebody has broken is mended.
            Piece end = new Piece(new BlockPos(s.at(PIER - 1, 0, 0).getX(), s.deck, s.at(PIER - 1, 0, 0).getZ()), Kind.DECK);
            if (level.isLoaded(end.pos()) && missing(level, s, end)) Ledger.note(id, PIER_NOTE, "mending");
        }
        keepLight(level, v);
        ShipCalls.townTick(level, v, s);
    }

    static long parse(@Nullable String s) {
        if (s == null || s.isEmpty()) return -1;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ the books

    /** The Harbour page (Annals: the town's books): rows of "H|heading", "L|line", "M|muted", "W|warning". */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag out = new CompoundTag();
        ListTag rows = new ListTag();
        for (String r : lines(level, v)) rows.add(StringTag.valueOf(r));
        out.put("rows", rows);
        return out;
    }

    /** The page's rows, and /village harbour's lines (without their marks). */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        Shore s = shore(id);
        out.add("H|The harbour");
        if (s == null) {
            String why = noShore(id);
            out.add("M|" + (why.isEmpty() ? "The town has not looked for a harbour yet (a Stone Age town of ten looks round its shores)."
                : "No harbour: " + why + "."));
            return out;
        }
        out.add("L|On the " + s.out.getName() + " shore, the water at " + s.water.getX() + ", " + s.water.getZ() + ".");
        Ledger.Building office = Villages.builtStructure(id, OFFICE);
        if (office == null) {
            out.add("M|The harbour office is " + (Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal() ? "for the Stone Age."
                : Villages.headcount(id) < FROM_FOLK ? "wanted once the town is " + FROM_FOLK + " strong." : "on the builders' list."));
        } else {
            int[] pc = pierCount(level, s);
            String state = pierDone(id) ? "out into deep water" : "being laid, " + pc[0] + " of " + pc[1] + " pieces"
                + (String.valueOf(Ledger.note(id, PIER_NOTE)).endsWith("waiting on stone") ? " (waiting on stone in the stores)" : "");
            out.add("L|The office stands; the pier is " + state + ".");
        }
        Ledger.Building light = Villages.builtStructure(id, LIGHT);
        if (light != null) {
            long last = parse(Ledger.note(id, LIT));
            out.add("L|The lighthouse: " + (lit(level, id) ? "burning now" : last >= day - 1 ? "lit last night" : "dark")
                + "; lit " + Math.max(0, parse(Ledger.note(id, LIGHTS))) + " nights in all.");
            if (parse(Ledger.note(id, DARK)) >= day - 1) out.add("W|Last dusk the light stood dark: no coal in the stores.");
        } else if (office != null) {
            out.add("M|No lighthouse yet" + (s.light == null ? " (no place for one on the bank)" : Villages.headcount(id) < LIGHT_FOLK
                ? ": wanted once the town is " + LIGHT_FOLK + " strong" : pierDone(id) ? ": on the builders' list" : ": after the pier") + ".");
        }
        VillageFolkEntity m = holder(level, id);
        if (m != null) out.add("L|Harbourmaster: " + m.displayNameCap() + " (" + m.stationTask().label + ").");
        out.addAll(PierFleet.lines(level, v));                                   // [realm-polish] the fishing fleet at the pier
        out.addAll(ShipCalls.lines(level, v, s));
        return out;
    }

    // ------------------------------------------------------------------ tests and the stage

    /** Tests: look for the shore now. */
    @Nullable
    public static Shore surveyForTests(ServerLevel level, Villages.Village v) {
        SHORES.remove(v.id());
        Ledger.forget(v.id(), SHORE);
        return survey(level, v);
    }

    /** Tests and the stage: the office set down where it goes, for nothing, as if built (the builders' work is their own). */
    public static void officeForTests(ServerLevel level, Villages.Village v) {
        Shore s = shore(v.id());
        if (s == null) return;
        BuildGoal.stamp(level, OFFICE, s.office, s.officeBack, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(v.id(), OFFICE, s.office, s.officeBack);
    }

    /** Tests and the stage: the light set down where it goes, for nothing, as if built. */
    public static boolean lightForTests(ServerLevel level, Villages.Village v) {
        Shore s = shore(v.id());
        if (s == null || s.light == null) return false;
        BuildGoal.stamp(level, LIGHT, s.light, s.lightBack, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(v.id(), LIGHT, s.light, s.lightBack);
        return true;
    }

    /** Tests: lay the pier now (a hand at once, as TownJobs' instant mode has it), out of the stores. Pieces laid. */
    public static int layPierForTests(ServerLevel level, Villages.Village v, int budget) {
        Shore s = shore(v.id());
        return s == null ? 0 : layPier(level, v, s, budget);
    }

    /** Tests: the keeper's round now. */
    public static void keepLightForTests(ServerLevel level, Villages.Village v) {
        keepLight(level, v);
    }

    /** Tests: where the beacon is. */
    @Nullable
    public static BlockPos beaconForTests(UUID village) {
        return beacon(village);
    }

    /** Tests: a place on the harbour (k out, lat across, dy over the waterline). */
    public static BlockPos atForTests(Shore s, int k, int lat, int dy) {
        return s.at(k, lat, dy);
    }

    /** Tests: the pier's deck height. */
    public static int deckForTests(Shore s) {
        return s.deck;
    }
}
