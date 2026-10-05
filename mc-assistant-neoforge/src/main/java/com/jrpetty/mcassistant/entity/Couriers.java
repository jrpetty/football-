package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The storehouse runs the couriers. A village's couriers (StationTask.HAUL) are the storehouse's
 * staff: they work out of it, wait at its door between runs, and take their runs from it rather than
 * choosing for themselves — the storekeeper sends them out when it is on duty at the counter, and
 * with nobody at the counter the storehouse's run list does (so nothing stalls for want of one).
 *
 * <p>The run list, looked over every ten seconds, best first:
 * <ol>
 * <li><b>Kit out to a worker</b> far out on its plot that asked for it (a farmer's seed, a miner's
 *     torches): a request the storekeeper cannot hand over at the counter, so a courier packs it at
 *     the storehouse and walks it out (VillageFolkEntity.kitFromTheStores asks).</li>
 * <li><b>Ore and fuel out to the smelter</b> (or the makings of its mason's work) when it runs low.</li>
 * <li><b>The production chests</b>, every worker's one chest at the edge of its plot: the fullest
 *     first, and of two as full the one that has waited longest — a hungry village's food before
 *     anything.</li>
 * <li><b>A worker's load</b>, off the back of one far out with a heavy pack and no chest of its own.</li>
 * <li><b>The furnaces' output</b> and any other of the village's chests out on the plots.</li>
 * <li>With nothing else, <b>an old chest</b> to clear into the storehouse and take up (Retiring).</li>
 * </ol>
 * A courier takes the next run, does it, carries what it fetched into the storehouse (merged onto
 * the stacks there: Stacking), and reports back to the storehouse door; the run is booked in the
 * storehouse's books (Storekeeping). A run that cannot be got to is set aside a while and the courier
 * reports back for another. Several couriers share the list: each takes the next run nobody has.
 *
 * <p>Builders still draw their materials straight out of the stores (their pack is filled where they
 * stand, VillageFolkEntity), so there is no run to carry those out.
 */
public final class Couriers {

    private Couriers() {}

    /** What a run is. */
    public enum Kind { KIT, SMELTER, CHEST, LOAD, FURNACE, OLD_CHEST }

    /** Where a run has got to. */
    enum Stage {
        QUEUED("waiting for a courier"), PACKING("packing it at the storehouse"), OUT("on the way out"),
        LOADED("loading up"), IN("carrying it in"), BACK("reporting back to the storehouse");

        final String words;

        Stage(String words) {
            this.words = words;
        }
    }

    /** A production chest worth a run: this many goods worth carrying, or a hungry village's food. */
    static final int CHEST_WORTH = 16;
    /** How often the run list is looked over (ticks). */
    private static final long REFRESH = 200L;
    /** A run nobody has taken in this long is dropped (a request: its folk fetches it itself). */
    private static final long STALE = 4800L;
    /** A run under way this long is given up (its courier lost, or the world unloaded under it). */
    private static final long TOO_LONG = 9600L;

    /** One run on the list. */
    static final class Run {
        final int id;
        final Kind kind;
        /** What it is about, so it is never on the list twice: the chest, or the folk (and what). */
        final long key;
        @Nullable BlockPos at;
        @Nullable UUID folk;
        String folkName = "";
        String what = "";
        /** A kit run: what to take out, and how many. */
        @Nullable String ask;
        int count;
        int worth;
        int priority;
        long queued;
        @Nullable UUID courier;
        String courierName = "";
        long taken;
        Stage stage = Stage.QUEUED;
        int moved;
        String sentBy = "";
        double best = Double.MAX_VALUE;
        long progress;
        int tries;

        Run(int id, Kind kind, long key) {
            this.id = id;
            this.kind = kind;
            this.key = key;
        }
    }

    /** The storehouse's office: its run list, the runs under way, and the day's tallies of its staff. */
    static final class Office {
        final List<Run> queue = new ArrayList<>();
        final Map<UUID, Run> taken = new LinkedHashMap<>();
        /** When each production chest first wanted a run (the oldest first, of two as full). */
        final Map<Long, Long> since = new HashMap<>();
        /** Runs set aside, and until when: a chest nobody could get to, a request that failed. */
        final Map<Long, Long> avoid = new HashMap<>();
        /** Each courier's day: {runs, goods carried}. */
        final Map<UUID, int[]> staff = new LinkedHashMap<>();
        final Map<UUID, String> doing = new HashMap<>();
        long refreshed = -100000L;
        long day = -1L;
        long spoke = -100000L;
        int nextId = 1;
    }

    private static final Map<UUID, Office> OFFICES = new ConcurrentHashMap<>();

    public static void resetForTests() {
        OFFICES.clear();
    }

    static Office office(ServerLevel level, UUID village) {
        Office o = OFFICES.computeIfAbsent(village, k -> new Office());
        long today = level.getDayTime() / 24000L;
        if (o.day != today) {
            o.day = today;
            o.staff.clear();
        }
        return o;
    }

    // ------------------------------------------------------------------ who works here

    /** Is this courier one of the storehouse's staff: a village's courier, its village with a storehouse
     *  (or its storage building) to run from? Then it takes its runs from there (and does not lend
     *  itself out between them: it waits at the door). */
    public static boolean employed(VillageFolkEntity c) {
        UUID v = c.ownerId();
        return v != null && c.stationTask() == StationTask.HAUL && c.usesVillageStores() && !c.isBaby()
            && Villages.craftReady(v, StationTask.HAUL);
    }

    /** Is this courier out on one of the storehouse's runs? (It walks the whole village for it.) */
    public static boolean onARun(VillageFolkEntity c) {
        UUID v = c.ownerId();
        Office o = v == null ? null : OFFICES.get(v);
        return o != null && o.taken.containsKey(c.getUUID());
    }

    /** What this courier is doing for the storehouse, in words, or null. */
    @Nullable
    public static String doing(VillageFolkEntity c) {
        UUID v = c.ownerId();
        Office o = v == null ? null : OFFICES.get(v);
        if (o == null || c.stationTask() != StationTask.HAUL) return null;
        Run r = o.taken.get(c.getUUID());
        if (r != null) return r.stage.words + ": " + r.what;
        return o.doing.get(c.getUUID());
    }

    /** Where the couriers report and wait: the storehouse's door (where one stands to use it), else
     *  the storage building, else the first of the stores. */
    @Nullable
    static BlockPos base(ServerLevel level, UUID village) {
        BlockPos spot = Storehouses.standingSpot(level, village);
        if (spot != null) return spot;
        BlockPos built = Villages.builtAt(village, "storage");
        if (built != null) return built;
        List<BlockPos> stores = Villages.storeChests(level, village);
        if (!stores.isEmpty()) return stores.get(0);
        Villages.Village v = Villages.get(village);
        return v == null ? null : v.centre();
    }

    /** Where a load goes: the storehouse (or the next store with room). */
    @Nullable
    static BlockPos depot(ServerLevel level, UUID village) {
        BlockPos d = Villages.depot(level, village);
        return d != null ? d : base(level, village);
    }

    // ------------------------------------------------------------------ the run list

    /** Look the run list over (at most every ten seconds; {@code force} for now). */
    static void refresh(ServerLevel level, UUID village, Office o, boolean force) {
        long now = level.getGameTime();
        if (!force && now - o.refreshed < REFRESH && now >= o.refreshed) return;
        o.refreshed = now;
        Villages.Village v = Villages.get(village);
        if (v == null) return;
        // Runs under way whose courier is gone, or has another trade, or has been at it far too long.
        o.taken.entrySet().removeIf(e -> {
            net.minecraft.world.entity.Entity c = level.getEntity(e.getKey());
            return !(c instanceof VillageFolkEntity f) || !f.isAlive() || f.stationTask() != StationTask.HAUL
                || now - e.getValue().taken > TOO_LONG || now < e.getValue().taken;
        });
        o.avoid.values().removeIf(until -> until < now);
        // The list is made afresh each time; only the requests are carried over (until they go stale).
        List<Run> kept = new ArrayList<>();
        for (Run r : o.queue) {
            if (r.kind != Kind.KIT) continue;
            if (now - r.queued > STALE || now < r.queued) { o.avoid.put(r.key, now + 6000L); continue; }
            kept.add(r);
        }
        o.queue.clear();
        o.queue.addAll(kept);
        Set<Long> busy = new HashSet<>();
        for (Run r : o.taken.values()) busy.add(r.key);
        for (Run r : o.queue) busy.add(r.key);
        BlockPos heart = v.centre();
        BlockPos depot = depot(level, village);
        boolean hungry = Market.hungry(village);
        Set<Long> chests = new HashSet<>();

        // The production chests: the fullest first, the longest waiting of two as full.
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            BlockPos p = f.productionChest();
            if (p == null) continue;
            long key = p.asLong();
            chests.add(key);
            if (busy.contains(key) || o.avoid.containsKey(key)) continue;
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof Container box)) continue;
            int held = 0, food = 0;
            boolean room = false;
            for (int i = 0; i < box.getContainerSize(); i++) {
                ItemStack st = box.getItem(i);
                if (st.isEmpty()) { room = true; continue; }
                if (AssistantEntity.haulWeight(st) <= 0) continue;
                held += st.getCount();
                if (st.get(net.minecraft.core.component.DataComponents.FOOD) != null) food += st.getCount();
            }
            boolean feed = hungry && food >= 4;
            if (held < CHEST_WORTH && !feed && (room || held == 0)) { o.since.remove(key); continue; }
            Run r = new Run(o.nextId++, Kind.CHEST, key);
            r.at = p.immutable();
            r.folk = f.getUUID();
            r.folkName = f.displayNameCap();
            r.worth = held;
            r.priority = feed ? 2 : 3;
            r.queued = o.since.computeIfAbsent(key, k -> now);
            r.what = f.displayNameCap() + "'s chest (" + held + (feed ? ", food for a hungry village" : "") + ")";
            o.queue.add(r);
        }
        o.since.keySet().retainAll(chests);

        // The smelter running low: ore (or, with none, the makings of its mason's work) and fuel out to it.
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != StationTask.SMELT || !f.isAlive() || f.isBaby()) continue;
            long key = f.getUUID().getLeastSignificantBits() ^ 0x5E17L;
            if (busy.contains(key) || o.avoid.containsKey(key)) continue;
            if (f.countCarried(AssistantEntity.SMELTABLE_ORE) >= 8) continue;
            boolean ore = Market.stock(level, village, AssistantEntity.SMELTABLE_ORE) >= 8;
            boolean makings = !ore && f.countCarried(Masonry.MAKINGS) < 16 && !Masonry.makings(level, v).isEmpty();
            if (!ore && !makings) continue;
            Run r = new Run(o.nextId++, Kind.SMELTER, key);
            r.folk = f.getUUID();
            r.folkName = f.displayNameCap();
            r.priority = 1;
            r.queued = now;
            r.what = (ore ? "ore and fuel" : "stone and clay") + " out to " + f.displayNameCap() + " at the forge";
            o.queue.add(r);
        }

        // A worker far out with a heavy pack and no chest of its own: its load, off its back.
        if (depot != null) {
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || f.workZone() == null) continue;
                if (!VillageFolkEntity.producer(f.stationTask()) || f.productionChest() != null) continue;
                long key = f.getUUID().getLeastSignificantBits() ^ 0x10ADL;
                if (busy.contains(key) || o.avoid.containsKey(key)) continue;
                if (f.workZone().center().distSqr(depot) < 40 * 40) continue;      // near enough to bank its own
                int load = f.stashable();
                if (load <= 47) continue;
                Run r = new Run(o.nextId++, Kind.LOAD, key);
                r.folk = f.getUUID();
                r.folkName = f.displayNameCap();
                r.worth = load;
                r.priority = 4;
                r.queued = now;
                r.what = f.displayNameCap() + "'s load (" + load + ")";
                o.queue.add(r);
            }
        }

        // The furnaces' output, and the village's other chests out on the plots (not the stores, not a
        // household's, not a guest house, not a worker's own).
        int reach = Math.min(112, Math.max(48, Villages.storesRadius(village)));
        Set<Long> inUse = VillageFolkEntity.chestsInUse(village);
        boolean before = ZoneChests.askAs(true);
        try {
            for (ZoneChests.Found f : ZoneChests.around(level, heart, reach, 32)) {
                long key = f.pos().asLong();
                if (!f.stillThere() || chests.contains(key) || busy.contains(key) || o.avoid.containsKey(key)) continue;
                boolean furnace = f.blockEntity() instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
                if (!furnace && (!ZoneChests.isStashable(f) || Villages.inStoreArea(village, f.pos()))) continue;
                if (Villages.inAGuestHouse(village, f.pos()) || Homes.inAHome(village, f.pos())) continue;
                if (inUse.contains(key)) continue;
                int held = 0;
                Container box = f.container();
                if (furnace) {
                    if (box.getContainerSize() >= 3) held = box.getItem(2).getCount();
                } else {
                    for (int i = 0; i < box.getContainerSize(); i++) {
                        ItemStack st = box.getItem(i);
                        if (AssistantEntity.haulWeight(st) > 0) held += st.getCount();
                    }
                }
                if (held < (furnace ? 4 : 24)) continue;
                Run r = new Run(o.nextId++, Kind.FURNACE, key);
                r.at = f.pos().immutable();
                r.worth = held;
                r.priority = 5;
                r.queued = now;
                r.what = (furnace ? "a furnace's output" : "a chest out on the plots") + " (" + held + ")";
                o.queue.add(r);
            }
        } finally {
            ZoneChests.askAs(before);
        }
        o.queue.sort((a, b) -> a.priority != b.priority ? Integer.compare(a.priority, b.priority)
            : a.worth != b.worth ? Integer.compare(b.worth, a.worth) : Long.compare(a.queued, b.queued));
    }

    /** Tests: look the run list over now, and say what is on it, best first. */
    public static List<String> refreshForTests(ServerLevel level, UUID village) {
        Office o = office(level, village);
        refresh(level, village, o, true);
        List<String> out = new ArrayList<>();
        for (Run r : o.queue) out.add(r.kind + ":" + (r.at == null ? "" : r.at.toShortString()) + ":" + r.what);
        return out;
    }

    /** Tests: the run this courier has in hand (kind and where), or null. */
    @Nullable
    public static String runOfForTests(VillageFolkEntity c) {
        UUID v = c.ownerId();
        Office o = v == null ? null : OFFICES.get(v);
        Run r = o == null ? null : o.taken.get(c.getUUID());
        return r == null ? null : r.kind + ":" + (r.at == null ? "" : r.at.toShortString()) + ":" + r.stage;
    }

    /** Tests: this courier's day, {runs, goods carried}. */
    public static int[] staffForTests(VillageFolkEntity c) {
        UUID v = c.ownerId();
        Office o = v == null ? null : OFFICES.get(v);
        int[] s = o == null ? null : o.staff.get(c.getUUID());
        return s == null ? new int[2] : s.clone();
    }

    // ------------------------------------------------------------------ a worker's request

    /**
     * A worker far out on its plot is short of its kit: the storehouse sends it out with a courier
     * rather than have it walk in. True if a courier is (or will be) on the way with it; false if the
     * village has no courier, the worker is near enough to walk to the counter, or the last such run
     * came to nothing — then it fetches it itself, as before.
     */
    public static boolean sendOut(VillageFolkEntity worker, String ask, int count) {
        UUID v = worker.ownerId();
        if (v == null || !(worker.level() instanceof ServerLevel level) || worker.stationTask() == StationTask.HAUL) return false;
        boolean staffed = false;
        for (AssistantEntity a : Villages.folkOf(v)) {
            if (a instanceof VillageFolkEntity c && c != worker && c.isAlive() && employed(c)) { staffed = true; break; }
        }
        if (!staffed) return false;
        BlockPos base = base(level, v);
        if (base == null || worker.workZone() == null || worker.workZone().center().distSqr(base) < 48 * 48) return false;
        Office o = office(level, v);
        long key = worker.getUUID().getLeastSignificantBits() ^ ask.hashCode();
        for (Run r : o.queue) if (r.key == key) return true;
        for (Run r : o.taken.values()) if (r.key == key) return true;
        if (o.avoid.containsKey(key)) return false;
        Run r = new Run(o.nextId++, Kind.KIT, key);
        r.folk = worker.getUUID();
        r.folkName = worker.displayNameCap();
        r.ask = ask;
        r.count = count;
        r.priority = 0;
        r.queued = level.getGameTime();
        r.what = count + " " + plural(ask) + " out to " + worker.displayNameCap();
        o.queue.add(0, r);
        return true;
    }

    // ------------------------------------------------------------------ dispatch

    /** The next run for this courier, off the top of the list: given by the storekeeper on duty, or by
     *  the list itself with nobody at the counter. Null if there is none. */
    @Nullable
    static Run next(VillageFolkEntity c, ServerLevel level, UUID village, Office o) {
        refresh(level, village, o, o.queue.isEmpty() && level.getGameTime() - o.refreshed > 60L);
        Run r = o.queue.isEmpty() ? null : o.queue.remove(0);
        if (r == null && Storehouses.storeFor(level, village) != null) {
            // Nothing else: an old chest to clear into the storehouse and take up.
            BlockPos old = Retiring.next(c, level, village);
            if (old != null) {
                r = new Run(o.nextId++, Kind.OLD_CHEST, old.asLong());
                r.at = old;
                r.priority = 6;
                r.queued = level.getGameTime();
                r.what = "an old chest at " + old.getX() + ", " + old.getZ() + " to clear into the storehouse";
            }
        }
        if (r == null) return null;
        long now = level.getGameTime();
        r.courier = c.getUUID();
        r.courierName = c.displayNameCap();
        r.taken = now;
        r.stage = r.kind == Kind.KIT || r.kind == Kind.SMELTER ? Stage.PACKING : Stage.OUT;
        r.best = Double.MAX_VALUE;
        r.progress = now;
        o.taken.put(c.getUUID(), r);
        VillageFolkEntity keeper = Storekeeping.onDuty(level, village);
        r.sentBy = keeper != null && keeper != c ? keeper.displayNameCap() : "";
        if (now - o.spoke >= 100L || now < o.spoke) {
            o.spoke = now;
            if (keeper != null && keeper != c) {
                keeper.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                if (keeper.distanceToSqr(c) < 16.0 * 16.0) keeper.getLookControl().setLookAt(c, 30.0F, 30.0F);
                FolkTalk.speak(keeper, sendLine(level, r, c.displayNameCap()));
            } else if (level.getRandom().nextInt(3) == 0) {
                FolkTalk.speak(c, FolkTalk.pick(level.getRandom(), "Next on the list: " + r.what + ".", "Right — " + r.what + "."));
            }
        }
        c.brain("courier run: " + r.what + (r.sentBy.isEmpty() ? " (off the run list)" : " (sent by " + r.sentBy + ")"));
        return r;
    }

    /** A kit word in the plural: "torches", "wheat seeds", "potatoes", "wheat". */
    static String plural(@Nullable String ask) {
        if (ask == null || ask.isEmpty()) return "goods";
        if (ask.endsWith("s") || ask.equals("wheat")) return ask;
        if (ask.endsWith("ch") || ask.endsWith("sh") || ask.endsWith("o")) return ask + "es";
        return ask + "s";
    }

    /** What the storekeeper says sending a courier out. */
    private static String sendLine(ServerLevel level, Run r, String name) {
        return switch (r.kind) {
            case KIT -> name + ", " + Storekeeping.words(r.count) + " " + plural(r.ask) + " out to " + r.folkName + ", please.";
            case SMELTER -> name + ", take some ore out to " + r.folkName + " at the forge.";
            case CHEST -> FolkTalk.pick(level.getRandom(), r.folkName + "'s chest is filling up — off you go, " + name + ".",
                name + ", bring in " + r.folkName + "'s chest, would you?");
            case LOAD -> name + ", " + r.folkName + "'s carrying more than it should. Fetch it in.";
            case FURNACE -> name + ", there's " + r.what + " wants bringing in.";
            case OLD_CHEST -> name + ", there's an old chest wants clearing into the storehouse.";
        };
    }

    // ------------------------------------------------------------------ a courier's work

    /**
     * A beat of a courier's work (its station brain, when it has nothing queued): carry on with the
     * run it has, or take the next, or wait at the storehouse door for one. False if it is not the
     * storehouse's (then it carries for the trades the old way: VillageFolkEntity.haulerRound).
     */
    public static boolean work(VillageFolkEntity c, ServerLevel level) {
        UUID v = c.ownerId();
        if (v == null || !employed(c)) return false;
        Office o = office(level, v);
        BlockPos base = base(level, v);
        if (base == null) return false;
        Run r = o.taken.get(c.getUUID());
        // Its business at the far end done, with a horse out (Riding): back to the horse and home on it first.
        if ((r == null || r.stage == Stage.IN || r.stage == Stage.BACK) && Riding.homeFirst(c, level)) return true;
        if (r == null) {
            // Something on its back from before (a run cut short, a reload): into the stores first.
            if (c.stashable() > 0) {
                BlockPos depot = depot(level, v);
                if (depot != null) {
                    c.enqueue(Job.depositAt(depot));
                    o.doing.put(c.getUUID(), "bringing in what it was carrying");
                    return true;
                }
            }
            r = next(c, level, v, o);
            if (r == null) return waitAtTheStorehouse(c, o, base);
        }
        o.doing.remove(c.getUUID());
        return switch (r.kind) {
            case CHEST, FURNACE -> fetch(c, level, v, o, r, base);
            case OLD_CHEST -> clear(c, level, v, o, r, base);
            case LOAD -> load(c, level, v, o, r, base);
            case SMELTER, KIT -> deliver(c, level, v, o, r, base);
        };
    }

    /** No run: back to the storehouse door, and wait there. */
    private static boolean waitAtTheStorehouse(VillageFolkEntity c, Office o, BlockPos base) {
        double d = c.distanceToSqr(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
        if (d > 3.5 * 3.5) {
            if (c.getNavigation().isDone()) c.walkTo(base, 1.0D);
            o.doing.put(c.getUUID(), "walking back to the storehouse");
            c.brain("back to the storehouse to wait for a run");
            return true;
        }
        c.getNavigation().stop();
        o.doing.put(c.getUUID(), "waiting at the storehouse for a run");
        c.brain("waiting at the storehouse for a run");
        return true;
    }

    /** Empty a production chest (or a furnace's output, or a chest out on the plots) and carry it in. */
    private static boolean fetch(VillageFolkEntity c, ServerLevel level, UUID v, Office o, Run r, BlockPos base) {
        if (r.stage == Stage.OUT) {
            BlockPos at = r.at;
            if (at == null || (level.isLoaded(at) && !(level.getBlockEntity(at) instanceof Container))) {
                close(level, v, o, c, r, 0L);
                return true;
            }
            if (!reach(c, at)) return walk(c, level, v, o, r, at);
            if (!c.transferReady()) return true;                     // still handling the last load
            boolean all = c.can(AssistantEntity.Ability.HAUL_FULL_PACK);
            int n = c.loadFrom(at, all ? 512 : 256, s -> AssistantEntity.haulWeight(s) > 0);
            r.moved += n;
            if (r.moved <= 0) {                                       // nothing worth carrying after all
                close(level, v, o, c, r, 1200L);
                return true;
            }
            BlockPos depot = depot(level, v);
            if (depot == null) { r.stage = Stage.BACK; return true; }
            r.stage = Stage.IN;
            if (!Riding.homeFirst(c, level)) c.enqueue(Job.depositAt(depot));     // (on horseback: ridden home first)
            if (level.getRandom().nextInt(3) == 0 && r.folkName.length() > 0) {
                FolkTalk.speak(c, FolkTalk.pick(level.getRandom(), "That's " + r.folkName + "'s chest done. In it goes.",
                    Storekeeping.words(r.moved) + " for the storehouse."));
            }
            return true;
        }
        return carryIn(c, level, v, o, r, base);
    }

    /** An old chest: emptied into the pack and taken up (RetireGoal), and carried in. */
    private static boolean clear(VillageFolkEntity c, ServerLevel level, UUID v, Office o, Run r, BlockPos base) {
        if (r.stage == Stage.OUT) {
            if (r.at == null) { close(level, v, o, c, r, 0L); return true; }
            r.stage = Stage.LOADED;
            c.enqueue(Job.retire(r.at));
            return true;
        }
        if (r.stage == Stage.LOADED) {
            r.moved = c.stashable();
            BlockPos depot = depot(level, v);
            r.stage = Stage.IN;
            if (r.moved > 0 && depot != null) c.enqueue(Job.depositAt(depot));
            return true;
        }
        return carryIn(c, level, v, o, r, base);
    }

    /** A far worker's load, off its back and carried in. */
    private static boolean load(VillageFolkEntity c, ServerLevel level, UUID v, Office o, Run r, BlockPos base) {
        if (r.stage == Stage.OUT) {
            VillageFolkEntity w = r.folk == null ? null : level.getEntity(r.folk) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
            if (w == null || w.productionChest() != null) { close(level, v, o, c, r, 0L); return true; }
            if (c.distanceToSqr(w) > 3.0 * 3.0) return walk(c, level, v, o, r, w.blockPosition());
            int took = c.takeLoadFrom(w);
            r.moved += took;
            if (took <= 0) { close(level, v, o, c, r, 1200L); return true; }
            c.note(AssistantEntity.Deed.LOADS_HAULED, 1);
            FolkTalk.speak(c, FolkTalk.pick(level.getRandom(), "I'll take that off your hands, " + w.displayNameCap() + ".",
                "Give it here — the storehouse sent me."));
            BlockPos depot = depot(level, v);
            r.stage = Stage.IN;
            if (depot != null && !Riding.homeFirst(c, level)) c.enqueue(Job.depositAt(depot));   // (on horseback: ridden home first)
            return true;
        }
        return carryIn(c, level, v, o, r, base);
    }

    /** Ore out to the smelter, or a worker's kit out to its plot: packed at the storehouse, walked out,
     *  handed over, and back to the storehouse. */
    private static boolean deliver(VillageFolkEntity c, ServerLevel level, UUID v, Office o, Run r, BlockPos base) {
        VillageFolkEntity to = r.folk == null ? null : level.getEntity(r.folk) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
        Predicate<ItemStack> what = r.kind == Kind.KIT && r.ask != null
            ? com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor(r.ask) : VillageFolkEntity.FOR_THE_SMELTER;
        if (r.stage == Stage.PACKING) {
            if (to == null) { close(level, v, o, c, r, 0L); return true; }
            if (c.distanceToSqr(base.getX() + 0.5, base.getY(), base.getZ() + 0.5) > 5.0 * 5.0) return walk(c, level, v, o, r, base);
            BlockPos heart = c.villageCentre() != null ? c.villageCentre() : base;
            int radius = Math.min(112, Math.max(48, Villages.storesRadius(v)));
            int got;
            if (r.kind == Kind.KIT) {
                got = c.drawFrom(heart, what, Math.max(1, r.count), radius);
            } else {
                got = c.drawFrom(heart, AssistantEntity.SMELTABLE_ORE, 32, radius);
                Villages.Village vill = Villages.get(v);
                if (got <= 0 && vill != null) {
                    for (Masonry.Lot lot : Masonry.makings(level, vill)) got += c.drawFrom(heart, lot.what(), lot.n(), radius);
                }
                if (got > 0) c.drawFrom(heart, s -> s.is(net.minecraft.world.item.Items.COAL) || s.is(net.minecraft.world.item.Items.CHARCOAL), 8, radius);
            }
            if (got <= 0) {                                         // the stores had none after all
                close(level, v, o, c, r, 6000L);
                return true;
            }
            r.count = got;
            r.stage = Stage.OUT;
            r.best = Double.MAX_VALUE;
            r.progress = level.getGameTime();
            return true;
        }
        if (r.stage == Stage.OUT) {
            if (to == null) { r.stage = Stage.IN; r.tries = 0; return carryIn(c, level, v, o, r, base); }   // gone: back into the stores
            if (c.distanceToSqr(to) > 3.0 * 3.0) return walk(c, level, v, o, r, to.blockPosition());
            int given = handOver(c, to, what, r.kind == Kind.KIT ? r.count : Integer.MAX_VALUE);
            r.moved += given;
            c.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            c.note(AssistantEntity.Deed.LOADS_HAULED, 1);
            FolkTalk.speak(c, r.kind == Kind.KIT
                ? FolkTalk.pick(level.getRandom(), "From the storehouse, " + to.displayNameCap() + ": " + Storekeeping.words(given) + " " + plural(r.ask) + ".",
                    "Here — " + plural(r.ask) + ", sent out from the storehouse.")
                : FolkTalk.pick(level.getRandom(), "Ore for the furnaces, " + to.displayNameCap() + "!", "From the storehouse — keep those fires going."));
            r.stage = Stage.BACK;
            r.best = Double.MAX_VALUE;
            r.progress = level.getGameTime();
            // Whatever it could not hand over goes back in.
            if (c.stashable() > 0) { r.stage = Stage.IN; r.tries = 0; }
            return true;
        }
        return carryIn(c, level, v, o, r, base);
    }

    /** The load is in the stores (a deposit was queued and has run): again if some is left, then back
     *  to the storehouse door to report, and the run is booked. */
    private static boolean carryIn(VillageFolkEntity c, ServerLevel level, UUID v, Office o, Run r, BlockPos base) {
        if (r.stage == Stage.IN) {
            if (c.stashable() > 0 && r.tries++ < 2) {
                BlockPos depot = depot(level, v);
                if (depot != null) { c.enqueue(Job.depositAt(depot)); return true; }
            }
            r.stage = Stage.BACK;
            r.best = Double.MAX_VALUE;
            r.progress = level.getGameTime();
        }
        if (c.distanceToSqr(base.getX() + 0.5, base.getY(), base.getZ() + 0.5) > 4.5 * 4.5) return walk(c, level, v, o, r, base);
        finish(level, v, o, c, r);
        return true;
    }

    /** Walk on towards there, watching it gets nearer: forty-five seconds getting no nearer and it is put
     *  beside it (as any stuck village hand is), or failing that the run is set aside. */
    private static boolean walk(VillageFolkEntity c, ServerLevel level, UUID v, Office o, Run r, BlockPos to) {
        long now = level.getGameTime();
        // A long run on horseback (Riding): a horse from the stable first, ridden to near the place.
        if (Riding.courier(c, level, to, r)) {
            r.best = Double.MAX_VALUE;
            r.progress = now;
            return true;
        }
        double d = c.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        if (d < r.best - 1.0) {
            r.best = d;
            r.progress = now;
        } else if (now - r.progress > 900L || now < r.progress) {
            if (c.putBeside(to)) {
                r.best = Double.MAX_VALUE;
                r.progress = now;
                return true;
            }
            c.brain("could not get there: " + r.what);
            close(level, v, o, c, r, 2400L);
            return true;
        }
        if (c.getNavigation().isDone()) c.walkTo(to, 1.1D);
        return true;
    }

    /** Near enough to use a chest, as a player would. */
    private static boolean reach(VillageFolkEntity c, BlockPos at) {
        return c.getEyePosition().distanceToSqr(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5)
            <= AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH;
    }

    /** Up to {@code most} of what matches, out of this courier's pack into another's. */
    private static int handOver(VillageFolkEntity from, VillageFolkEntity to, Predicate<ItemStack> what, int most) {
        int moved = 0;
        var inv = from.getInventoryItems();
        for (int i = 0; i < inv.size() && moved < most; i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int give = Math.min(s.getCount(), most - moved);
            ItemStack left = to.insertGiven(s.copyWithCount(give));
            int taken = give - left.getCount();
            s.shrink(taken);
            if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
            moved += taken;
        }
        return moved;
    }

    /** The run done and reported at the storehouse door: booked, and the courier's day tallied. */
    private static void finish(ServerLevel level, UUID v, Office o, VillageFolkEntity c, Run r) {
        o.taken.remove(c.getUUID());
        int[] day = o.staff.computeIfAbsent(c.getUUID(), k -> new int[2]);
        day[0]++;
        day[1] += r.moved;
        Storekeeping.bookRun(level, v, c.displayNameCap(), r.what, r.moved, r.sentBy);
        c.brain("back at the storehouse: " + r.what + " done");
        if (r.moved > 0 && level.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(c, FolkTalk.pick(level.getRandom(), "Back. That's " + r.what + " seen to.",
                "Done — " + Storekeeping.words(r.moved) + " carried. What's next?"));
        }
    }

    /** The run ended short (nothing there, nobody to give it to, nowhere it could get to): set aside
     *  for a while if {@code aside} > 0, and booked if anything was carried. */
    private static void close(ServerLevel level, UUID v, Office o, VillageFolkEntity c, Run r, long aside) {
        o.taken.remove(c.getUUID());
        if (aside > 0) o.avoid.put(r.key, level.getGameTime() + aside);
        if (r.kind == Kind.OLD_CHEST && r.at != null) Retiring.release(r.at);
        if (r.moved > 0) {
            int[] day = o.staff.computeIfAbsent(c.getUUID(), k -> new int[2]);
            day[0]++;
            day[1] += r.moved;
            Storekeeping.bookRun(level, v, c.displayNameCap(), r.what, r.moved, r.sentBy);
        }
    }

    // ------------------------------------------------------------------ shown

    /** The storehouse's staff and its run list, into its report (Storekeeping.report). */
    static void report(ServerLevel level, Villages.Village v, CompoundTag t) {
        UUID id = v.id();
        Office o = office(level, id);
        refresh(level, id, o, false);
        VillageFolkEntity duty = Storekeeping.onDuty(level, id);
        ListTag staff = new ListTag();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            StationTask t0 = f.stationTask();
            if (t0 != StationTask.STORE && t0 != StationTask.HAUL) continue;
            CompoundTag s = new CompoundTag();
            s.putString("name", f.displayNameCap());
            s.putString("role", t0 == StationTask.STORE ? "storekeeper" : "courier");
            s.putInt("wage", Wealth.wage(f));
            int[] day = o.staff.get(f.getUUID());
            s.putInt("runs", day == null ? 0 : day[0]);
            s.putInt("moved", day == null ? 0 : day[1]);
            String doing;
            if (f.isSleeping()) doing = "asleep";
            else if (t0 == StationTask.STORE) doing = duty == f ? "at the counter" : f.offWorkNow() ? "off work" : "away from the counter";
            else {
                Run r = o.taken.get(f.getUUID());
                doing = r != null ? r.stage.words + ": " + r.what
                    : f.offWorkNow() ? "off work" : o.doing.getOrDefault(f.getUUID(), "waiting for a run");
                String ride = Riding.doing(f);                                  // on horseback (Riding)
                if (ride != null) doing = ride.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + ride.substring(1) + "; " + doing;
            }
            s.putString("doing", doing);
            staff.add(s);
        }
        t.put("staff", staff);
        ListTag running = new ListTag();
        for (Run r : o.taken.values()) {
            CompoundTag s = new CompoundTag();
            s.putString("what", r.what);
            s.putString("courier", r.courierName);
            s.putString("stage", r.stage.words);
            s.putString("sent_by", r.sentBy);
            running.add(s);
        }
        t.put("running", running);
        ListTag queued = new ListTag();
        for (int i = 0; i < Math.min(10, o.queue.size()); i++) {
            CompoundTag s = new CompoundTag();
            s.putString("what", o.queue.get(i).what);
            s.putInt("worth", o.queue.get(i).worth);
            queued.add(s);
        }
        t.put("queued", queued);
        t.putInt("queued_n", o.queue.size());
    }
}
