package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * [dining] Food and hospitality: the cook's trade (StationTask.COOK) split into the jobs a town's kitchens, bars, inns
 * and dining rooms want, as the shop's trade is split (ShopRoles, StoreStaff). Each job is a post of its own on the
 * books (JobWorth "COOK/WAITER"...), paid by what it does, shown on the nameplate and the card.
 * <ul>
 * <li><b>Head cook</b> runs the kitchen: plans the day's menu from the stock and the season, cooks the special, and the
 *     orders with the line cooks. <b>Line cooks</b> (the trade's plain "cook") cook the orders and the café's shelf.</li>
 * <li><b>Baker</b>: the bakery's ovens in the early morning (bread, cookies, pies, cakes: Bakery).</li>
 * <li><b>Barkeep</b>: behind the tavern's bar through its hours, pouring, the rounds and the music nights.</li>
 * <li><b>Innkeeper</b>: the inn's (or the guest house's) rooms, with a share of each room's fee.</li>
 * <li><b>Waiter</b>: takes the orders at the tables, carries the trays from the pass, clears and resets the tables.</li>
 * <li><b>Pot washer</b>: washes the plates and the cups; nothing goes back on the shelf unwashed.</li>
 * <li><b>Host</b>: greets and seats, takes the payment and runs the till; orders the kitchen's supplies.</li>
 * </ul>
 * <b>How many.</b> By the town and its places (want): a head cook wherever there is a kitchen; a barkeep with a tavern;
 * a baker with a bakery; an innkeeper with an inn; waiters with the dining rooms, one more for every thirty-five folk
 * past twenty; a pot washer and a host once a restaurant stands; line cooks past the head cook, one for every forty
 * folk with a restaurant. The town's shape counts them in the cook's share (Villages.target), so it staffs them as it
 * staffs every trade. <b>Small places combine the jobs:</b> with no waiter the cook carries the plates, with no pot
 * washer the waiter washes, with no host the head cook takes the money (duty).
 * <p>Who takes a job: whoever had it keeps it while it is wanted; a free hand of the trade is chosen by its fit
 * (scored by job: a sociable, cheerful face at the tables and the bar, the most practised hand at the stove). The tags
 * are kept on the folk in the world, so a restart keeps everybody in their job.
 */
public final class Hospitality {

    private Hospitality() {}

    /** The jobs of the cook's trade. LINE_COOK is the trade's plain "cook" (no tag). */
    public enum Role {
        NONE("—", "", ""),
        HEAD_COOK("Head cook", "Head cook", "mca_head_cook"),
        LINE_COOK("Cook", "Cook", ""),
        BAKER("Baker", "Baker", "mca_baker"),
        BARKEEP("Barkeep", "Barkeep", "mca_barkeep"),
        INNKEEPER("Innkeeper", "Innkeeper", "mca_innkeeper"),
        WAITER("Waiter", "Waiter", "mca_waiter"),
        POT_WASHER("Pot washer", "Pot washer", "mca_pot_washer"),
        HOST("Host", "Host", "mca_host");

        public final String title, badge, tag;

        Role(String title, String badge, String tag) {
            this.title = title;
            this.badge = badge;
            this.tag = tag;
        }

        /** The kitchen's jobs (at the stove). */
        public boolean kitchen() { return this == HEAD_COOK || this == LINE_COOK || this == BAKER; }

        /** The front of the house (with the customers). */
        public boolean front() { return this == WAITER || this == HOST || this == BARKEEP || this == INNKEEPER; }
    }

    /** What has to be done at a place, each by whoever the place's staff has for it (duty). */
    public enum Duty { COOK, SERVE, WASH, TILL }

    /** The order the jobs are filled in (the first wanted first). */
    static final Role[] FILL_ORDER = { Role.HEAD_COOK, Role.BARKEEP, Role.WAITER, Role.BAKER, Role.INNKEEPER, Role.POT_WASHER, Role.HOST };
    /** Who does each duty, best first: the jobs that are for it, and the jobs that stand in when the town has none of
     *  those at all (a café's one cook carries and washes; a busy waiter is waited for, not replaced by the cook). */
    private static final Map<Duty, Role[]> FOR = new EnumMap<>(Duty.class), STANDS_IN = new EnumMap<>(Duty.class);

    static {
        FOR.put(Duty.COOK, new Role[]{ Role.HEAD_COOK, Role.LINE_COOK, Role.BAKER });
        STANDS_IN.put(Duty.COOK, new Role[]{ Role.WAITER, Role.HOST, Role.POT_WASHER, Role.BARKEEP });
        FOR.put(Duty.SERVE, new Role[]{ Role.WAITER, Role.HOST, Role.BARKEEP });
        STANDS_IN.put(Duty.SERVE, new Role[]{ Role.LINE_COOK, Role.POT_WASHER, Role.HEAD_COOK, Role.BAKER });
        FOR.put(Duty.WASH, new Role[]{ Role.POT_WASHER });
        STANDS_IN.put(Duty.WASH, new Role[]{ Role.WAITER, Role.LINE_COOK, Role.HOST, Role.BARKEEP, Role.HEAD_COOK, Role.BAKER });
        FOR.put(Duty.TILL, new Role[]{ Role.HOST, Role.BARKEEP });
        STANDS_IN.put(Duty.TILL, new Role[]{ Role.HEAD_COOK, Role.WAITER, Role.LINE_COOK, Role.INNKEEPER });
    }

    /** Folk past twenty for each further waiter; folk for each further line cook (with a restaurant). */
    static final int FOLK_PER_WAITER = 35, FOLK_PER_LINE_COOK = 40;
    /** How often the staff are looked over (ticks). */
    static final long LOOK = 1200L;
    /** The waiters a dining room keeps at most, for its tables: one to every three. */
    static final int TABLES_PER_WAITER = 3;

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKED.clear();
        TRADE.clear();
    }

    // ------------------------------------------------------------------ who is what

    /** This folk's job in the cook's trade (NONE for anybody of another trade). */
    public static Role role(@Nullable VillageFolkEntity f) {
        if (f == null || f.stationTask() != StationTask.COOK) return Role.NONE;
        for (Role r : Role.values()) if (!r.tag.isEmpty() && f.getTags().contains(r.tag)) return r;
        return Role.LINE_COOK;
    }

    private record Kept(int tick, List<VillageFolkEntity> folk) {}

    /** The trade, looked for once a tick a town at most (the dining pass asks it for every dish it hands out). */
    private static final Map<UUID, Kept> TRADE = new ConcurrentHashMap<>();

    /** The cook's trade in a town, grown and alive, in a steady order. */
    static List<VillageFolkEntity> trade(UUID village) {
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        int tick = server == null ? -1 : server.getTickCount();
        Kept k = TRADE.get(village);
        if (k != null && k.tick() == tick && tick >= 0) return k.folk();
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.COOK && !f.isBaby() && f.isAlive()) out.add(f);
        }
        out.sort(Comparator.comparing(VillageFolkEntity::getUUID));
        TRADE.put(village, new Kept(tick, out));
        return out;
    }

    /** Those of the trade in this job. */
    public static List<VillageFolkEntity> in(UUID village, Role role) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity f : trade(village)) if (role(f) == role) out.add(f);
        return out;
    }

    @Nullable
    static VillageFolkEntity first(UUID village, Role role) {
        List<VillageFolkEntity> all = in(village, role);
        return all.isEmpty() ? null : all.get(0);
    }

    /** The innkeeper (Inn.keeper asks first): the innkeeper, else the host. Null: as before (the cook, the shopkeeper). */
    @Nullable
    static VillageFolkEntity innkeeper(UUID village) {
        VillageFolkEntity k = first(village, Role.INNKEEPER);
        return k != null ? k : first(village, Role.HOST);
    }

    /** Who keeps a service point's trade (ServicePoints.keeper asks first): the barkeep at the tavern's bar, the host or
     *  the head cook at a dining room's counter. Null: whoever of the trade is nearest, as before. */
    @Nullable
    static VillageFolkEntity keeperFor(UUID village, ServiceRole role) {
        Role[] order = switch (role) {
            case TAVERN -> new Role[]{ Role.BARKEEP, Role.HOST };
            case CAFE -> new Role[]{ Role.HOST, Role.WAITER, Role.HEAD_COOK };
            case RESTAURANT -> new Role[]{ Role.HOST, Role.HEAD_COOK, Role.WAITER };
            default -> new Role[0];
        };
        for (Role r : order) {
            for (VillageFolkEntity f : in(village, r)) if (!f.isSleeping() && f.onShift()) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------ how many

    /** The jobs the town wants filled now, by its size and its places (the class comment). */
    static Map<Role, Integer> want(UUID village) {
        Map<Role, Integer> w = new EnumMap<>(Role.class);
        int folk = Villages.headcount(village);
        boolean cafe = Villages.hasBuilt(village, "cafe"), tavern = Villages.hasBuilt(village, "tavern");
        boolean restaurant = Villages.hasBuilt(village, DiningPlaces.RESTAURANT);
        boolean anyKitchen = cafe || tavern || restaurant;
        w.put(Role.HEAD_COOK, 1);
        if (tavern) w.put(Role.BARKEEP, 1);
        if (Villages.hasBuilt(village, "bakery")) w.put(Role.BAKER, 1);
        if (Villages.hasBuilt(village, "inn") || Villages.hasBuilt(village, "guesthouse")) w.put(Role.INNKEEPER, 1);
        if (anyKitchen && (restaurant || folk >= 20)) {
            int waiters = 1 + Math.max(0, folk - 20) / FOLK_PER_WAITER;
            int tables = DiningPlaces.tablesIn(village);
            waiters = Math.min(waiters, Math.max(1, (tables + TABLES_PER_WAITER - 1) / TABLES_PER_WAITER));
            w.put(Role.WAITER, waiters);
        }
        if (restaurant || anyKitchen && folk >= 40) w.put(Role.POT_WASHER, 1);
        if (restaurant && folk >= 30) w.put(Role.HOST, 1);
        if (restaurant) w.put(Role.LINE_COOK, folk / FOLK_PER_LINE_COOK);
        return w;
    }

    /** How many more of the cook's trade the town's shape should count, past its usual cooks (Villages.target): every
     *  job wanted but the head cook's, and the restaurant's line cooks. */
    public static int staffWanted(@Nullable UUID village) {
        if (village == null) return 0;
        int n = 0;
        for (Map.Entry<Role, Integer> e : want(village).entrySet()) if (e.getKey() != Role.HEAD_COOK) n += e.getValue();
        return n;
    }

    // ------------------------------------------------------------------ the round

    /** Once a minute (Dining.round): tags put right, then each job the town is short of filled. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = LOOKED.get(id);
        if (last != null && now - last < LOOK && now >= last) return;
        LOOKED.put(id, now);
        lookOver(level, v);
    }

    /** Tests: the staff looked over now. */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        LOOKED.remove(v.id());
        tick(level, v);
    }

    /** The jobs given out: a tag off anybody who left the trade; a job held past what is wanted given up (the least
     *  suited first); then each wanted job filled out of the trade's line cooks, the best suited first. */
    static void lookOver(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() != StationTask.COOK) untag(f);
        }
        Map<Role, Integer> want = want(id);
        for (Role r : FILL_ORDER) {
            List<VillageFolkEntity> have = in(id, r);
            int wanted = want.getOrDefault(r, 0);
            if (have.size() > wanted) {
                have.sort(Comparator.comparingDouble(score(r)));
                for (int i = 0; i < have.size() - wanted; i++) {
                    untag(have.get(i));
                    have.get(i).brain("back to the kitchen as a cook: the town has " + r.title.toLowerCase(java.util.Locale.ROOT) + "s enough");
                }
            }
        }
        for (Role r : FILL_ORDER) {
            int wanted = want.getOrDefault(r, 0);
            while (in(id, r).size() < wanted) {
                VillageFolkEntity best = null;
                double bestScore = -Double.MAX_VALUE;
                ToDoubleFunction<VillageFolkEntity> s = score(r);
                for (VillageFolkEntity f : in(id, Role.LINE_COOK)) {
                    double sc = s.applyAsDouble(f);
                    if (sc > bestScore) { bestScore = sc; best = f; }
                }
                if (best == null) break;
                appoint(level, v, best, r);
            }
        }
    }

    private static void untag(VillageFolkEntity f) {
        for (Role r : Role.values()) if (!r.tag.isEmpty()) f.removeTag(r.tag);
    }

    /** How well suited a folk is to a job: the practised hand at the stove, a cheerful face at the tables. */
    static ToDoubleFunction<VillageFolkEntity> score(Role r) {
        return f -> {
            double kitchen = f.veteranLevel() + Math.min(10, f.xpInTrade(StationTask.COOK) / 200.0);
            double face = StoreStaff.counterScore(f);
            return switch (r) {
                case HEAD_COOK, BAKER -> kitchen * 2 + (f.life().has(Social.Trait.HARDWORKING) ? 3 : 0);
                case WAITER, HOST, BARKEEP, INNKEEPER -> face * 2 + kitchen / 4;
                case POT_WASHER -> 10 - kitchen + (f.life().has(Social.Trait.HARDWORKING) ? 2 : 0);
                default -> 0;
            };
        };
    }

    /** This folk in this job: the tag on, its ground the job's place, said so, and in the town's news. */
    static void appoint(ServerLevel level, Villages.Village v, VillageFolkEntity f, Role role) {
        TRADE.remove(v.id());
        untag(f);
        if (!role.tag.isEmpty()) f.addTag(role.tag);
        Ledger.Building at = placeFor(v.id(), role);
        if (at != null) {
            f.setStation(at.anchor(), StationTask.COOK);
            f.assignPlot(WorkZone.around(at.anchor(), 8, WorkZone.DEFAULT_DEPTH), Villages.spoken(at.structure()));
        }
        String place = at == null ? "the kitchen" : Villages.spoken(at.structure());
        f.brain("taken on as " + JobMarket.a(role.title.toLowerCase(java.util.Locale.ROOT)) + " at " + place);
        FolkTalk.speak(f, switch (role) {
            case HEAD_COOK -> "Head cook! The menu's mine now. Right: what's in the larder?";
            case BAKER -> "I'm the baker now. Up before the sun, and the first loaves out by six.";
            case BARKEEP -> "Behind the bar at " + place + ". What'll it be?";
            case INNKEEPER -> "I keep the rooms at " + place + " now. Clean sheets, every bed.";
            case WAITER -> "I'm waiting tables at " + place + ". Table for two? This way.";
            case POT_WASHER -> "Pot washer at " + place + ". Not a plate goes out dirty on my watch.";
            case HOST -> "I'll be greeting folk at " + place + ". Welcome, welcome!";
            default -> "Back at the stove.";
        });
        Villages.tell(v.id(), level.getDayTime() / 24000L, f.displayNameCap() + " became the " + role.title.toLowerCase(java.util.Locale.ROOT)
            + " at " + place);
    }

    /** Tests: this folk in this job now. */
    public static void appointForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f, Role role) {
        f.setJob(StationTask.COOK);
        appoint(level, v, f, role);
    }

    /** Where a job is done: the tavern's bar, the bakery, the inn, the grandest dining room for the floor and the
     *  kitchen. Null if the town has none of them. */
    @Nullable
    static Ledger.Building placeFor(UUID village, Role role) {
        List<String> where = switch (role) {
            case BARKEEP -> List.of("tavern");
            case BAKER -> List.of("bakery", "cafe");
            case INNKEEPER -> List.of("inn", "guesthouse");
            default -> List.of(DiningPlaces.RESTAURANT, "cafe", "tavern");
        };
        for (String s : where) {
            for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(s)) return b;
        }
        return null;
    }

    // ------------------------------------------------------------------ duties

    /**
     * Who does this duty at a place now: the nearest free, awake hand of the first job that is for it; with nobody in
     * those jobs at all (awake), the jobs that stand in for them: small places combine the jobs this way (a café's one
     * cook cooks, carries and washes). {@code free} says which hands are free of other dining work. Null if nobody can
     * (the work waits for a hand).
     */
    @Nullable
    static VillageFolkEntity duty(ServerLevel level, UUID village, Duty d, BlockPos near, java.util.function.Predicate<VillageFolkEntity> free) {
        boolean anyFor = false;
        for (Role r : FOR.get(d)) for (VillageFolkEntity f : in(village, r)) if (!f.isSleeping()) { anyFor = true; break; }
        VillageFolkEntity got = nearestOf(level, village, FOR.get(d), near, free);
        return got != null || anyFor ? got : nearestOf(level, village, STANDS_IN.get(d), near, free);
    }

    @Nullable
    private static VillageFolkEntity nearestOf(ServerLevel level, UUID village, Role[] roles, BlockPos near,
                                               java.util.function.Predicate<VillageFolkEntity> free) {
        for (Role r : roles) {
            VillageFolkEntity best = null;
            double bestD = Double.MAX_VALUE;
            for (VillageFolkEntity f : in(village, r)) {
                if (f.isSleeping() || !free.test(f) || f.level() != level) continue;
                double dd = f.distanceToSqr(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
                if (dd < bestD) { bestD = dd; best = f; }
            }
            if (best != null) return best;
        }
        return null;
    }

    // ------------------------------------------------------------------ hooks: shifts, town works, the card

    /**
     * Its shift (VillageFolkEntity.onShift asks first): a hand with a dining job in hand is at work till it is done; the
     * barkeep keeps the tavern's hours (and sleeps the morning), the baker the small hours; the kitchen and the floor
     * work their dining room's hours (from half an hour before, to set the tables and stock the pass, to half an hour
     * after, to clear) on top of their usual day. Null for everybody else, and outside those hours: its usual day.
     */
    @Nullable
    public static Boolean shift(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.COOK) return null;
        if (Dining.busy(f)) return Boolean.TRUE;
        long t = f.level().getDayTime() % 24000L;
        return switch (role(f)) {
            case BARKEEP -> ServiceRole.TAVERN.hours.isOpen(Math.floorMod(t + PREP, 24000L)) || ServiceRole.TAVERN.hours.isOpen(Math.floorMod(t - PREP, 24000L));
            case BAKER -> t >= 22000L || t < 7000L;
            case WAITER, HOST, POT_WASHER, HEAD_COOK, LINE_COOK -> {
                ServiceRole served = f.ownerId() == null ? ServiceRole.CAFE : DiningPlaces.servedBy(f.ownerId());
                boolean open = served.hours.isOpen(Math.floorMod(t + PREP, 24000L)) || served.hours.isOpen(Math.floorMod(t - PREP, 24000L));
                yield open ? Boolean.TRUE : null;
            }
            default -> null;
        };
    }

    /** Half an hour before opening to set the tables and stock the pass; half an hour after closing to clear. */
    static final long PREP = 500L;

    /** The town's works (TownJobs.choose): the baker is first for the bakery's ovens; the rest of the front of the house
     *  are kept at their posts while their place is open. */
    public static double townWorkLean(VillageFolkEntity f, String works) {
        Role r = role(f);
        if (r == Role.NONE) return 0;
        if (works.equals("baking")) return r == Role.BAKER ? 200 : r == Role.HEAD_COOK ? -20 : 0;
        if (Dining.busy(f)) return -200;
        return r.front() ? -40 : 0;
    }

    /** Its job on its nameplate ("Waiter"), or "" for anybody not of the cook's trade. */
    public static String badge(VillageFolkEntity f) {
        Role r = role(f);
        return r == Role.NONE ? "" : r.badge;
    }

    /**
     * A beat of its job (VillageFolkEntity.craftWork, before the bench's): a dining task in hand first (Dining); then the
     * front of the house to its post while its place is open (the barkeep behind the bar, the waiter on the floor, the
     * host at the counter, the innkeeper at the inn). False for the kitchen: its work is the café's as it always was.
     */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() != StationTask.COOK || f.ownerId() == null) return false;
        if (Dining.work(f, level)) return true;
        Role r = role(f);
        if (!r.front() && r != Role.POT_WASHER) return false;
        return DiningPlaces.standAtPost(f, level, r);
    }

    /** Its trade on its card: "Waiter (the restaurant's floor)". */
    public static String title(VillageFolkEntity f) {
        Role r = role(f);
        Ledger.Building at = f.ownerId() == null ? null : placeFor(f.ownerId(), r);
        String place = at == null ? "" : " (" + Villages.spoken(at.structure()) + ")";
        return r == Role.NONE ? f.stationTask().title : r.title + place;
    }

    /** The staff in a line each, for the books and the smoke: "Waiter Ada: carrying a roast to table 3". */
    public static List<String> lines(UUID village) {
        List<String> out = new ArrayList<>();
        for (VillageFolkEntity f : trade(village)) {
            String doing = Dining.doing(f);
            out.add(role(f).title + " " + f.displayNameCap() + ": " + (doing != null ? doing : f.isSleeping() ? "asleep" : f.offWorkNow() ? "off work" : "at work"));
        }
        return out;
    }
}
