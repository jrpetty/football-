package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [realm-polish] The fishing fleet (Fleet) at the harbour's pier (Harbours).
 *
 * <p>Once a town's pier is finished, it is the fleet's quay: the boats tie up along the pier's landward side, every other
 * block from its end and off its end (the ships lie along the other side, by the bollards), the fishers walk out along the
 * deck to board, and come in to it to land the catch, at the fish market, which the town puts up again by the pier's foot
 * if it stood by the old jetty (its barrels, its awning and its smoker taken down into the stores, and set up afresh out of
 * them), else at the pier's foot into the stores. The fleet moves the morning after the pier is done, once the boats are all
 * in: the boats at the old moorings are brought in and hauled up into the stores, and put in again at the pier. A town
 * with no harbour, or whose pier is not done, keeps its fleet as it was.
 *
 * <p>The harbourmaster counts the catch as it comes ashore: the Harbour page of the town's books has the boats at the pier,
 * the fish landed today and this week and in all, and who counted them.
 */
public final class PierFleet {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private PierFleet() {}

    static final String OLD_QUAY = "rp.oldquay", OLD_STALL = "rp.oldstall", TALLY = "rp.pier.log", TOTAL = "rp.pier.total";
    /** The old moorings looked at so often (ticks) till they are tidied away. */
    static final long TIDY_EVERY = 600L;

    /** The piers that are fleets' quays, with their shores (for where a hand stands on the deck). */
    private static final Map<Waterfront.Dock, Harbours.Shore> PIERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TIDIED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        PIERS.clear();
        TIDIED.clear();
    }

    /** The pier as a quay: its landward edge (the deck's side away from the ships), at the water's level, out to its end. */
    static Waterfront.Dock dock(Harbours.Shore s) {
        BlockPos p = s.at(0, -1, 0);
        return new Waterfront.Dock(new BlockPos(p.getX(), s.surface, p.getZ()), s.out, Harbours.PIER);
    }

    /**
     * [Fleet.quay] The town's finished pier, if it has one: the fleet's quay from now on (moved to it the first time, once its
     * boats are all in). Null to go on as before: no harbour, the pier not finished, or the boats still out.
     */
    @Nullable
    static Waterfront.Dock quay(ServerLevel level, Villages.Village v, Fleet.Town t) {
        UUID id = v.id();
        if (!Harbours.pierDone(id)) return null;
        Harbours.Shore s = Harbours.shore(id);
        if (s == null) return null;
        Waterfront.Dock pier = dock(s);
        PIERS.put(pier, s);
        if (pier.equals(t.quay)) {
            tidy(level, v);
            return pier;
        }
        if (!t.crew.isEmpty() || !level.isLoaded(pier.start())) return null;        // the boats come home to the old quay first
        Waterfront.Dock old = t.quay != null ? t.quay : Fleet.quayNote(id);
        t.quay = pier;
        t.chart = null;
        Fleet.keepQuay(id, pier);
        if (old != null && !old.equals(pier)) Ledger.note(id, OLD_QUAY, encode(old));
        FishMarket.Mart m = FishMarket.mart(id);
        if (m.stall != null && m.stall.stand().distSqr(pier.start()) > 20 * 20) {
            Ledger.note(id, OLD_STALL, m.stall.stand().getX() + "," + m.stall.stand().getY() + "," + m.stall.stand().getZ() + ","
                + m.stall.out().getName() + "," + m.stall.along().getName());
            m.stall = null;
            m.looked = -100000L;
            Ledger.forget(id, "fleet.stall");
        }
        long day = level.getDayTime() / 24000L;
        Villages.tell(id, day, old == null ? "the fishing fleet will moor at the harbour's pier, and land its catch there"
            : "the fishing fleet moved its moorings to the harbour's pier: the boats tie up along its side, and the catch is landed there");
        TIDIED.remove(id);
        tidy(level, v);
        LOG.info("[MCA-FLEET] {}: the fishing fleet moors at the harbour's pier from {} (was {})", Villages.name(id), pier.start().toShortString(),
            old == null ? "none" : old.start().toShortString());
        return pier;
    }

    /**
     * The old moorings tidied away when their ground is about: the town's boats left there brought in and hauled up into the
     * stores (to be put in again at the pier), the old fish stall taken down into the stores (to be put up again by the pier).
     */
    static void tidy(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TIDIED.get(id);
        if (last != null && now - last < TIDY_EVERY && now >= last) return;
        TIDIED.put(id, now);
        Waterfront.Dock old = decode(Ledger.note(id, OLD_QUAY));
        if (old != null && level.isLoaded(old.end())) {
            Harbours.Shore s = Harbours.shore(id);
            Waterfront.Dock pier = s == null ? null : dock(s);
            List<BlockPos> berths = pier == null ? List.of() : Fleet.berths(level, pier);
            int hauled = 0;
            for (Boat b : Fleet.boats(level, id, old)) {
                if (!b.getPassengers().isEmpty()) continue;
                boolean atPier = false;
                for (BlockPos p : berths) if (Fleet.flat(b.position(), p) <= 3.5) atPier = true;
                if (atPier) continue;
                Crafts.store(level, v, new ItemStack(b.getDropItem()));
                b.discard();
                hauled++;
            }
            Ledger.forget(id, OLD_QUAY);
            if (hauled > 0) LOG.info("[MCA-FLEET] {}: {} boats brought in from the old moorings and hauled up into the stores", Villages.name(id), hauled);
        }
        FishMarket.Stall stall = stallOf(Ledger.note(id, OLD_STALL));
        if (stall != null && level.isLoaded(stall.stand())) {
            takeDown(level, v, stall);
            Ledger.forget(id, OLD_STALL);
        }
    }

    /** The old fish stall taken down: what its barrels and its smoker held, and the blocks themselves, into the stores. */
    static void takeDown(ServerLevel level, Villages.Village v, FishMarket.Stall s) {
        List<BlockPos> cells = new ArrayList<>(s.barrels());
        for (int a = -1; a <= 1; a++) {
            if (a != 0) cells.add(s.counter().relative(s.along(), a).above());
            cells.add(s.stand().relative(s.along(), a).above(2));
            cells.add(s.counter().relative(s.along(), a).above(2));
        }
        cells.add(s.sign());
        cells.add(s.smoker());
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(s.counter().above()))) {
            if (f.getTags().contains(FishMarket.FRAME_TAG)) f.discard();               // the fish of the day was only shown there
        }
        int n = 0;
        for (BlockPos p : cells) {
            BlockState st = level.getBlockState(p);
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            boolean ours = st.is(Blocks.BARREL) || st.is(Blocks.SMOKER) || st.is(Blocks.SPRUCE_FENCE) || st.is(Blocks.SPRUCE_SLAB) || st.is(Blocks.SPRUCE_SIGN);
            if (!ours) continue;
            if (level.getBlockEntity(p) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack it = c.getItem(i);
                    if (!it.isEmpty()) Crafts.store(level, v, it.copy());
                    c.setItem(i, ItemStack.EMPTY);
                }
            }
            Crafts.store(level, v, new ItemStack(st.getBlock().asItem()));
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            n++;
        }
        LOG.info("[MCA-FLEET] {}: the old fish stall at {} taken down into the stores ({} pieces)", Villages.name(v.id()), s.stand().toShortString(), n);
    }

    /** [Fleet.standFor] Where a hand stands on the pier's deck to step into the boat at this berth; null for any other quay. */
    @Nullable
    static BlockPos standFor(Waterfront.Dock q, BlockPos berth) {
        Harbours.Shore s = PIERS.isEmpty() ? null : PIERS.get(q);
        if (s == null) return null;
        int k = (berth.getX() - s.water.getX()) * s.out.getStepX() + (berth.getZ() - s.water.getZ()) * s.out.getStepZ();
        return s.onDeck(Math.max(0, Math.min(Harbours.PIER - 2, k)), -1);            // short of the lamp posts at its end
    }

    /** [FishMarket.landingSpot] With no fish stall up, where the catch comes ashore at a pier: its foot, by the harbour office. */
    @Nullable
    static BlockPos landing(Waterfront.Dock q) {
        Harbours.Shore s = PIERS.isEmpty() ? null : PIERS.get(q);
        if (s == null) return null;
        BlockPos p = s.at(-1, 0, 0);
        return new BlockPos(p.getX(), s.deck + 1, p.getZ());
    }

    /** Is this quay a harbour's pier? */
    static boolean isPier(@Nullable Waterfront.Dock q) {
        return q != null && PIERS.containsKey(q);
    }

    /**
     * [Fleet.landing] A hand landed its catch at the pier: the harbourmaster counts it into the harbour's book (today's
     * fish and landings, the total), and says so if it is by.
     */
    static void landed(ServerLevel level, Villages.Village v, VillageFolkEntity f, @Nullable Waterfront.Dock q, int fish) {
        if (!isPier(q)) return;
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        List<long[]> log = log(id);
        long[] today = null;
        for (long[] e : log) if (e[0] == day) today = e;
        if (today == null) {
            today = new long[]{ day, 0, 0 };
            log.add(today);
        }
        today[1] += fish;
        today[2]++;
        while (log.size() > 7) log.remove(0);
        StringBuilder sb = new StringBuilder();
        for (long[] e : log) sb.append(sb.length() == 0 ? "" : "\n").append(e[0]).append('|').append(e[1]).append('|').append(e[2]);
        Ledger.note(id, TALLY, sb.toString());
        Ledger.note(id, TOTAL, Long.toString(WarBooks.num(Ledger.note(id, TOTAL), 0) + fish));
        VillageFolkEntity m = Harbours.holder(level, id);
        if (m != null && m != f && m.distanceToSqr(f) < 24 * 24 && !m.isSleeping()) {
            FolkTalk.speak(m, fish > 0 ? FolkTalk.pick(m.getRandom(), fish + " from " + f.displayNameCap() + ". In the book.",
                    "That's " + today[1] + " landed today. Good work.", fish + " fish, counted.")
                : FolkTalk.pick(m.getRandom(), "Nothing today, " + f.displayNameCap() + "? I'll write it down all the same.", "An empty boat. It happens."));
        }
    }

    /** The harbour's book of the fleet's catch: {day, fish, landings}, oldest first. */
    static List<long[]> log(UUID village) {
        List<long[]> out = new ArrayList<>();
        String s = Ledger.note(village, TALLY);
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            String[] p = line.split("\\|");
            if (p.length < 3) continue;
            out.add(new long[]{ WarBooks.num(p[0], -1), WarBooks.num(p[1], 0), WarBooks.num(p[2], 0) });
        }
        return out;
    }

    /** [Harbours.lines] The fleet at the pier, for the Harbour page: the boats, the catch counted, by whom. */
    static List<String> lines(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        if (!Harbours.pierDone(id)) return out;
        Harbours.Shore s = Harbours.shore(id);
        if (s == null) return out;
        Fleet.Town t = Fleet.town(id);
        Waterfront.Dock pier = dock(s);
        if (!Fleet.big(id)) {
            out.add("M|No fishing fleet yet: a town of " + Fleet.from() + " fits one out, and it would moor at the pier.");
            return out;
        }
        if (!pier.equals(t.quay)) {
            out.add("M|The fishing fleet will move its moorings to the pier when its boats are next all in.");
            return out;
        }
        int boats = Fleet.boats(level, id, pier).size();
        long day = level.getDayTime() / 24000L, week = 0, todayFish = 0, todayIn = 0;
        for (long[] e : log(id)) {
            if (e[0] > day - 7) week += e[1];
            if (e[0] == day) { todayFish = e[1]; todayIn = e[2]; }
        }
        out.add("L|The fishing fleet moors along the pier: " + boats + (boats == 1 ? " boat" : " boats") + "; " + Fleet.today(level, id) + ".");
        VillageFolkEntity m = Harbours.holder(level, id);
        out.add("L|The catch landed at the pier: " + todayFish + " fish today" + (todayIn > 0 ? " (" + todayIn + (todayIn == 1 ? " boat" : " boats") + " in)" : "")
            + ", " + week + " this week, " + WarBooks.num(Ledger.note(id, TOTAL), 0) + " in all" + (m != null ? "; counted by " + m.displayNameCap() : "") + ".");
        return out;
    }

    static String encode(Waterfront.Dock d) {
        return d.start().getX() + "," + d.start().getY() + "," + d.start().getZ() + "," + d.out().getName() + "," + d.length();
    }

    @Nullable
    static Waterfront.Dock decode(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length < 5) return null;
        try {
            Direction d = Direction.byName(p[3]);
            if (d == null || d.getAxis().isVertical()) return null;
            return new Waterfront.Dock(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), d, Integer.parseInt(p[4]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    static FishMarket.Stall stallOf(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length < 5) return null;
        try {
            Direction out = Direction.byName(p[3]), along = Direction.byName(p[4]);
            if (out == null || along == null) return null;
            return new FishMarket.Stall(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), out, along);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ an operator's hand

    /** [admin] The fleet moved to the harbour's pier now, if the pier is finished and the boats are in. What is said. */
    public static String moveNow(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Harbours.shore(id) == null || !Harbours.pierDone(id)) return Villages.name(id) + " has no finished pier.";
        if (!Fleet.big(id)) return Villages.name(id) + " is too small for a fishing fleet (" + Fleet.from() + " folk).";
        Fleet.Town t = Fleet.town(id);
        if (!t.crew.isEmpty()) return "The fishing fleet is out; it moves to the pier when its boats are in.";
        TIDIED.remove(id);
        Waterfront.Dock q = Fleet.quay(level, v, t);
        if (!isPier(q)) return "The pier's ground is not about just now.";
        return "The fishing fleet moors at the pier of " + Villages.name(id) + ": " + Fleet.boats(level, id, q).size() + " boats there now.";
    }

    // ------------------------------------------------------------------ the pictures

    /**
     * [TradeWorld.harbourStage] The fishing fleet moored at the staged pier (a town big enough for a fleet): moved there now,
     * its boats put in at the berths for nothing. "VIEW fleet ..." from over the water on the pier's landward side.
     */
    static List<String> stage(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        Harbours.Shore s = Harbours.shore(v.id());
        if (s == null || !Fleet.big(v.id())) {
            out.add("no fishing fleet to moor at the pier (a town of " + Fleet.from() + " fits one out)");
            return out;
        }
        Fleet.Town t = Fleet.town(v.id());
        if (!isPier(Fleet.quay(level, v, t))) {
            out.add("the fishing fleet is out: it moors at the pier when its boats are next in");
            return out;
        }
        int boats = Fleet.boatsForTests(level, v);
        BlockPos eye = s.at(9, -9, 5), mark = s.at(8, -2, 0);
        out.add("VIEW fleet " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " " + mark.getX() + " " + mark.getY() + " " + mark.getZ());
        out.add("the fishing fleet moored along the pier: " + boats + " boats");
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the fleet's quay looked at now (moved to the pier if it is due). True if it is the pier. */
    public static boolean quayForTests(ServerLevel level, Villages.Village v) {
        Fleet.Town t = Fleet.town(v.id());
        TIDIED.remove(v.id());
        Waterfront.Dock q = Fleet.quay(level, v, t);
        return isPier(q);
    }

    /** Tests: the pier as the fleet's quay. */
    @Nullable
    public static Waterfront.Dock pierForTests(UUID village) {
        Harbours.Shore s = Harbours.shore(village);
        return s == null ? null : dock(s);
    }

    /** Tests: where a hand stands to board at this berth (any quay). */
    public static BlockPos standForTests(Waterfront.Dock q, BlockPos berth) {
        return Fleet.standFor(q, berth);
    }

    /** Tests: the berths along a quay. */
    public static List<BlockPos> berthsForTests(ServerLevel level, Waterfront.Dock q) {
        return Fleet.berths(level, q);
    }

    /** Tests: today's tally at the pier {fish, landings} and the total. */
    public static long[] tallyForTests(ServerLevel level, UUID village) {
        long day = level.getDayTime() / 24000L;
        for (long[] e : log(village)) if (e[0] == day) return new long[]{ e[1], e[2], WarBooks.num(Ledger.note(village, TOTAL), 0) };
        return new long[]{ 0, 0, WarBooks.num(Ledger.note(village, TOTAL), 0) };
    }

    /** Tests: a fisher lands this catch at the town's quay as it would coming in (FishMarket.land, then the count). Fish landed. */
    public static int landForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Fleet.Town t = Fleet.town(v.id());
        int n = FishMarket.land(level, v, f, new java.util.HashMap<>());
        landed(level, v, f, t.quay, n);
        return n;
    }

    /** Tests: where the catch is brought ashore at the town's quay now. */
    public static BlockPos landingForTests(UUID village) {
        Fleet.Town t = Fleet.town(village);
        return t.quay == null ? BlockPos.ZERO : FishMarket.landingSpot(village, t.quay);
    }
}
