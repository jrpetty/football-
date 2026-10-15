package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shop's staff: who works where, how many of each the shop wants, and who is taken on.
 * <ul>
 * <li><b>The jobs</b> (ShopRoles): one keeper, who runs it and sets its prices with the stock keeper; the
 *     assistants at its counters, who serve the customers face to face; the crafters at its bench (Workshop's
 *     hands, as many as its order book has work for: one for every fifteen folk, four at most); and the stock
 *     keeper, who counts the stock every morning and orders it in.</li>
 * <li><b>How many.</b> The little shop takes on an assistant in a town of twenty-four (before that the keeper
 *     serves between pieces of work) and a stock keeper in a town of thirty. The store wants a stock keeper
 *     from the day it opens, an assistant, and another at fifty-five folk and a third at eighty-five (or a
 *     store selling two hundred and fifty things a week), never more than it has counters.</li>
 * <li><b>Who.</b> Out of the shop's own hands first, when the bench has more than its book wants; then, as the
 *     bench's hands are taken on (Workshop.hire), out of the hands the village can spare (between trades, or
 *     idle in a trade with more than it wants) — never the storehouse's staff, the watch, a scout, a hired
 *     hand or one on the town's works. The stock keeper's is the hardest job, and goes to the one best fitted
 *     for it: experienced, hard-working, curious (a quick study), a practised hand at the storehouse's shelves;
 *     an assistant's to a cheerful, sociable face (a Friendly Face most of all), not a shy or a grumpy one.</li>
 * </ul>
 * The town's shape counts the shop's trade as its keeper, its crafters and these (Villages.target), so the town
 * staffs it as it staffs every trade.
 */
public final class StoreStaff {

    private StoreStaff() {}

    /** The tags on the shop's assistants and its stock keeper (kept with them in the world). */
    public static final String ASSISTANT = "mca_shop_assistant", STOCK_KEEPER = "mca_stock_keeper";
    /** How often the staff are looked over and somebody taken on (ticks). */
    static final long LOOK = 1200L;

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKED.clear();
    }

    // ------------------------------------------------------------------ how many

    /** How many assistants the shop wants at its counters. */
    public static int assistantsWanted(UUID village) {
        if (!Workshop.stands(village) && !Store.stands(village)) return 0;
        boolean store = Store.stands(village);
        Ledger.Building b = store ? Store.main(village) : null;
        int counters = b == null ? 0 : Store.layout(b).counters().size();
        return assistantsFor(Villages.headcount(village), store, Store.weekSales(village), counters);
    }

    /** How many assistants a shop wants by the town's size and its sales (the class comment): the little shop one
     *  from twenty-four folk; the store one, two at fifty-five, three at eighty-five or two hundred and fifty
     *  sales a week, never more than its counters. */
    public static int assistantsFor(int folk, boolean store, int weekSales, int counters) {
        if (!store) return folk >= 24 ? 1 : 0;
        int n = 1 + (folk >= 55 ? 1 : 0) + (folk >= 85 || weekSales >= 250 ? 1 : 0);
        return Math.max(1, Math.min(n, Math.max(1, counters)));
    }

    /** How many stock keepers the shop wants: one, at the store, or at the little shop of a town of thirty. */
    public static int stockKeepersWanted(UUID village) {
        if (!Workshop.stands(village) && !Store.stands(village)) return 0;
        return Store.stands(village) || Villages.headcount(village) >= 30 ? 1 : 0;
    }

    // ------------------------------------------------------------------ who

    /** The shop's trade, grown, alive. */
    private static List<VillageFolkEntity> trade(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.SHOP && !f.isBaby() && f.isAlive()) out.add(f);
        }
        return out;
    }

    /** Those of the shop's staff in this job. */
    public static List<VillageFolkEntity> in(UUID village, ShopRoles.Role role) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity f : trade(village)) if (ShopRoles.role(f) == role) out.add(f);
        out.sort(Comparator.comparing(VillageFolkEntity::getUUID));
        return out;
    }

    /** The stock keeper at work, if the shop has one. */
    @Nullable
    public static VillageFolkEntity stockKeeper(UUID village) {
        List<VillageFolkEntity> all = in(village, ShopRoles.Role.STOCK_KEEPER);
        return all.isEmpty() ? null : all.get(0);
    }

    /** How well suited a folk is to keeping the stock: its years, its nature, its feel for shelves. */
    static double stockScore(VillageFolkEntity f) {
        double s = f.veteranLevel() + Math.min(10, f.xpInTrade(StationTask.STORE) / 200.0);
        Social.Life life = f.life();
        if (life.has(Social.Trait.HARDWORKING)) s += 4;
        if (life.has(Social.Trait.CURIOUS)) s += 3;
        if (life.has(Social.Trait.EASYGOING)) s -= 3;
        if (FolkSkills.active(f, FolkSkills.Knack.TIDY_SHELVES)) s += 4;
        if (FolkSkills.active(f, FolkSkills.Knack.QUICK_STUDY)) s += 2;
        return s;
    }

    /** How well suited a folk is to the counter: a cheerful, sociable face. */
    static double counterScore(VillageFolkEntity f) {
        double s = f.veteranLevel() / 4.0;
        Social.Life life = f.life();
        if (life.has(Social.Trait.SOCIABLE)) s += 4;
        if (life.has(Social.Trait.CHEERFUL)) s += 3;
        if (life.has(Social.Trait.SHY)) s -= 4;
        if (life.has(Social.Trait.GRUMPY)) s -= 3;
        if (FolkSkills.active(f, FolkSkills.Knack.FRIENDLY_FACE)) s += 5;
        return s;
    }

    // ------------------------------------------------------------------ the round

    /**
     * The staff looked over (every minute): a tag on one who left the trade comes off, the keeper is nobody's
     * assistant, and one too many in a job goes back to the bench; then the shop takes on the assistant or the
     * stock keeper it is short of, out of its own spare hands or out of the town.
     */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = LOOKED.get(id);
        if (last != null && now - last < LOOK && now >= last) return;
        LOOKED.put(id, now);
        lookOver(level, v);
        fill(level, v, ShopRoles.Role.STOCK_KEEPER, stockKeepersWanted(id));
        fill(level, v, ShopRoles.Role.ASSISTANT, assistantsWanted(id));
    }

    /** Tags put right: off anybody who left the trade, off the keeper, and the extra in a job back to the bench. */
    static void lookOver(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            if (f.stationTask() != StationTask.SHOP && (f.getTags().contains(ASSISTANT) || f.getTags().contains(STOCK_KEEPER))) {
                f.removeTag(ASSISTANT);
                f.removeTag(STOCK_KEEPER);
            }
        }
        VillageFolkEntity keeper = Workshop.keeper(id);
        if (keeper != null && (keeper.getTags().contains(ASSISTANT) || keeper.getTags().contains(STOCK_KEEPER))) {
            // The keeper's own job comes first: with nobody else of the trade to keep the shop, it keeps it.
            boolean other = false;
            for (VillageFolkEntity f : trade(id)) if (f != keeper && !ShopRoles.underTheKeeper(f)) { other = true; break; }
            if (!other) {
                keeper.removeTag(ASSISTANT);
                keeper.removeTag(STOCK_KEEPER);
                keeper.removeTag(Workshop.HAND);
            }
        }
        trim(level, v, ShopRoles.Role.STOCK_KEEPER, STOCK_KEEPER, stockKeepersWanted(id));
        trim(level, v, ShopRoles.Role.ASSISTANT, ASSISTANT, assistantsWanted(id));
    }

    private static void trim(ServerLevel level, Villages.Village v, ShopRoles.Role role, String tag, int wanted) {
        List<VillageFolkEntity> have = in(v.id(), role);
        if (have.size() <= wanted) return;
        have.sort(Comparator.comparingDouble(f -> role == ShopRoles.Role.STOCK_KEEPER ? stockScore(f) : counterScore(f)));
        for (int i = 0; i < have.size() - wanted; i++) {
            VillageFolkEntity f = have.get(i);
            f.removeTag(tag);
            f.addTag(Workshop.HAND);
            f.brain("back to the shop's bench: the shop has " + (role == ShopRoles.Role.ASSISTANT ? "assistants" : "a stock keeper") + " enough");
        }
    }

    /** One more in this job if the shop is short of it: a spare hand off its own bench, or one out of the town. */
    @Nullable
    static VillageFolkEntity fill(ServerLevel level, Villages.Village v, ShopRoles.Role role, int wanted) {
        UUID id = v.id();
        if (Workshop.keeper(id) == null || in(id, role).size() >= wanted) return null;
        String building = Store.buildingForShop(id);
        BlockPos at = Villages.builtAt(id, building);
        if (at == null) return null;
        java.util.function.ToDoubleFunction<VillageFolkEntity> score = role == ShopRoles.Role.STOCK_KEEPER ? StoreStaff::stockScore : StoreStaff::counterScore;
        // A hand off the bench, if the bench has more than its book wants (or the job is the stock keeper's,
        // which the shop would sooner have than a fourth pair of hands).
        List<VillageFolkEntity> hands = Workshop.hands(id);
        if (!hands.isEmpty() && (hands.size() > Workshop.handsWanted(id) || role == ShopRoles.Role.STOCK_KEEPER && hands.size() > 1)) {
            VillageFolkEntity best = null;
            for (VillageFolkEntity h : hands) if (best == null || score.applyAsDouble(h) > score.applyAsDouble(best)) best = h;
            if (best != null) {
                appoint(level, v, best, role, null);
                return best;
            }
        }
        // Out of the town: within its shape (a hand short of the shop's share), as the bench's hands are.
        if (Villages.wants(id, StationTask.SHOP) && Villages.share(id, StationTask.SHOP) > -1.0) return null;
        VillageFolkEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || f.isHired() || f.isShowcase()) continue;
            if (TownJobs.busy(f) || f.isSleeping()) continue;
            StationTask t = f.stationTask();
            double s;
            if (t == StationTask.NONE) s = 100;
            else if (t == StationTask.HAUL || t == StationTask.STORE || t == StationTask.GUARD || t == StationTask.SCOUT || t.isCraft()) continue;
            else if (Villages.overStaffed(id, t) && f.workedOut()) s = 50;
            else continue;
            s += score.applyAsDouble(f) * 4 - Math.sqrt(f.blockPosition().distSqr(at)) / 16.0;
            if (s > bestScore) { bestScore = s; best = f; }
        }
        if (best == null) return null;
        appoint(level, v, best, role, at);
        return best;
    }

    /** This folk in this job at the shop: of the shop's trade, its tag on, its ground the shop, and said so. */
    static void appoint(ServerLevel level, Villages.Village v, VillageFolkEntity f, ShopRoles.Role role, @Nullable BlockPos stationAt) {
        StationTask was = f.stationTask();
        if (stationAt != null) {
            f.setStation(stationAt, StationTask.SHOP);
            f.assignPlot(WorkZone.around(stationAt, 7, WorkZone.DEFAULT_DEPTH), Store.stands(v.id()) ? "the store" : "the shop");
        }
        f.removeTag(Workshop.HAND);
        f.removeTag(role == ShopRoles.Role.ASSISTANT ? STOCK_KEEPER : ASSISTANT);
        f.addTag(role == ShopRoles.Role.ASSISTANT ? ASSISTANT : STOCK_KEEPER);
        String place = Store.stands(v.id()) ? "the store" : "the shop";
        VillageFolkEntity keeper = Workshop.keeper(v.id());
        if (role == ShopRoles.Role.ASSISTANT) {
            f.brain("taken on as an assistant at " + place + "'s counter" + (keeper != null && keeper != f ? ", under " + keeper.displayNameCap() : ""));
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Behind the counter at " + place + " now. What can I get you?",
                "I'm to serve at " + place + ". I'll learn every price by heart.", "An assistant at " + place + " — good morning, good morning!"));
        } else {
            f.brain("made the stock keeper at " + place + (keeper != null && keeper != f ? ", under " + keeper.displayNameCap() : ""));
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'm keeping the stock at " + place + ". Every loaf and every nail, counted.",
                "Stock keeper! Nothing will run out on my watch.", "The stock book's mine now. Right — where are we short?"));
        }
        Villages.tell(v.id(), level.getDayTime() / 24000L, f.displayNameCap() + " became " + place + "'s "
            + (role == ShopRoles.Role.ASSISTANT ? "assistant" : "stock keeper")
            + (was == StationTask.SHOP || was == StationTask.NONE ? "" : ", from " + was.label));
    }

    // ------------------------------------------------------------------ at work

    /**
     * A beat of the shop's staff's work (VillageFolkEntity.craftWork, before the bench's): an assistant to its
     * counter, the stock keeper to its stock. False for the keeper and the crafters (their work is the bench's
     * and the counter's as it always was: Crafts, Cafe.keepShop).
     */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() != StationTask.SHOP || f.ownerId() == null || !ShopStock.open(f.ownerId())) return false;
        UUID id = f.ownerId();
        return switch (ShopRoles.role(f)) {
            case ASSISTANT -> StoreFloor.work(f, level);
            case STOCK_KEEPER -> StockKeeper.work(f, level);
            // With no stock keeper and no courier in the town, the keeper fetches what it noticed run out itself.
            case KEEPER -> stockKeeper(id) == null && !StoreDeliveries.couriers(id) && StoreDeliveries.fetch(f, level, 0L);
            default -> false;
        };
    }

    /** What it is doing for the shop just now, for the top of its card, or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        String delivery = StoreDeliveries.doing(f);
        if (delivery != null) return delivery;
        return switch (ShopRoles.role(f)) {
            case ASSISTANT -> StoreFloor.doing(f);
            case STOCK_KEEPER -> StockKeeper.doing(f);
            default -> null;
        };
    }

    /** Its trade on its card: "Stock keeper (the store's stock book)". */
    public static String title(VillageFolkEntity f) {
        String place = f.ownerId() != null && Store.stands(f.ownerId()) ? "the store" : "the shop";
        return switch (ShopRoles.role(f)) {
            case KEEPER -> "Shopkeeper (runs " + place + ")";
            case ASSISTANT -> "Shop assistant (at " + place + "'s counter)";
            case STOCK_KEEPER -> "Stock keeper (" + place + "'s stock book)";
            case CRAFTER -> "Shop hand (a crafter at " + place + "'s bench)";
            case NONE -> f.stationTask().title;
        };
    }

    /** Its work at the shop, a line for its card. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !ShopStock.open(id)) return null;
        String place = Store.stands(id) ? "the store" : "the shop";
        return switch (ShopRoles.role(f)) {
            case KEEPER -> "runs " + place + ": " + in(id, ShopRoles.Role.ASSISTANT).size() + " at the counter, "
                + Workshop.hands(id).size() + " at the bench, " + (stockKeeper(id) == null ? "no stock keeper (it notices what runs out itself)"
                    : "the stock keeper " + stockKeeper(id).displayNameCap()) + "; sets the prices with the stock keeper";
            case ASSISTANT -> StoreFloor.cardLine(f);
            case STOCK_KEEPER -> StockKeeper.cardLine(f);
            default -> null;
        };
    }

    /** The staff in a line each, for the books and the smoke: "Stock keeper Ada: counting the stock". */
    public static List<String> lines(UUID village) {
        List<String> out = new ArrayList<>();
        for (VillageFolkEntity f : trade(village)) {
            ShopRoles.Role r = ShopRoles.role(f);
            String doing = doing(f);
            if (doing == null) doing = Workshop.doing(f);
            out.add(r.title + " " + f.displayNameCap() + ": " + (doing == null ? f.isSleeping() ? "asleep" : f.offWorkNow() ? "off work" : "at work" : doing)
                + " " + f.getBlockX() + " " + f.getBlockY() + " " + f.getBlockZ());
        }
        return out;
    }

    // ------------------------------------------------------------------ the smoke and the tests

    /** The shop staffed now (the smoke): a keeper, its assistants and its stock keeper, out of the nearest grown
     *  folk, and a crafter; what each became. */
    static List<String> staffNow(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        BlockPos at = Villages.builtAt(id, Store.buildingForShop(id));
        if (at == null) return out;
        if (Workshop.keeper(id) == null) Workshop.hireNow(level, v, at);
        if (Workshop.hands(id).isEmpty()) Workshop.hireNow(level, v, at);
        int want = Math.max(2, assistantsWanted(id));
        for (int i = in(id, ShopRoles.Role.ASSISTANT).size(); i < want; i++) {
            VillageFolkEntity f = nearestFree(id, at);
            if (f == null) break;
            appoint(level, v, f, ShopRoles.Role.ASSISTANT, at);
            out.add("ASSISTANT " + f.displayNameCap());
        }
        if (stockKeeper(id) == null) {
            VillageFolkEntity f = nearestFree(id, at);
            if (f != null) {
                appoint(level, v, f, ShopRoles.Role.STOCK_KEEPER, at);
                out.add("STOCKKEEPER " + f.displayNameCap());
            }
        }
        return out;
    }

    @Nullable
    private static VillageFolkEntity nearestFree(UUID village, BlockPos near) {
        VillageFolkEntity keeper = Workshop.keeper(village), best = null;
        double bestD = Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || f.isShowcase() || f == keeper) continue;
            if (f.stationTask() == StationTask.SHOP) continue;
            double d = f.blockPosition().distSqr(near);
            if (d < bestD) { bestD = d; best = f; }
        }
        return best;
    }

    /** Tests: this folk in this job at the shop, now (stationed at the shop's building). */
    public static void appointForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f, ShopRoles.Role role) {
        BlockPos at = Villages.builtAt(v.id(), Store.buildingForShop(v.id()));
        if (role == ShopRoles.Role.CRAFTER) {
            f.setJob(StationTask.SHOP);
            f.removeTag(ASSISTANT);
            f.removeTag(STOCK_KEEPER);
            f.addTag(Workshop.HAND);
            return;
        }
        if (role == ShopRoles.Role.KEEPER) {
            f.setJob(StationTask.SHOP);
            f.removeTag(ASSISTANT);
            f.removeTag(STOCK_KEEPER);
            f.removeTag(Workshop.HAND);
            return;
        }
        f.setJob(StationTask.SHOP);
        appoint(level, v, f, role, at);
    }

    /** Tests: the staff looked over and filled now, whatever the hour. */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        LOOKED.remove(v.id());
        tick(level, v);
    }
}
