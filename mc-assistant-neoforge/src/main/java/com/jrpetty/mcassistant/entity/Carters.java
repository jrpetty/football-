package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.RoadItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [roles-e] The carter: a post, not a trade, in a town with a wagon. A steady hand chosen at interview (InterviewPosts,
 * kind CARTER; reckoned each day from the best suited till then: a courier, a rancher who knows horses, a hard worker)
 * who drives the town's wagon: the carrier picked for a caravan or a shipment is the carter whenever it is free
 * (Caravans.choose), else a courier or the nearest hand as before.
 *
 * <ul>
 * <li><b>Paid by the trip.</b> Home from a run (a caravan's or a shipment's, by wagon or on foot), the carter is paid
 *     its fee out of the treasury: a share of a day's unit at the town's pay level, more for a longer road, two days'
 *     at most. Its runs and fees are kept in the town's books.</li>
 * <li><b>Its dress.</b> A long driving coat and a felt hat (Weave), and on the bench its driving whip in its other hand:
 *     made once out of the stores (a stick, string and leather: RoadItems), kept in its pack between runs.</li>
 * <li><b>Its talk.</b> "Where's the wagon bound?" (Trade, Services): out on the road, home in the yard, its runs.</li>
 * </ul>
 * Cost: once a second a look at each loaded town's carter (a cached look-up) for its whip; a trip's end is a few sums.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Carters {

    private Carters() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The post's key in the interviews' books. */
    static final String POST = "carter";
    /** A trip's fee: this share of a day's unit, and as much again for every so many blocks of road; two days' at most. */
    static final double TRIP_SHARE = 0.4, ROAD_UNIT = 400.0, MOST_DAYS = 2.0;
    private static final String BOOK = "carter.book";                 // runs|coins|lastDay|lastTo

    /** Each town's carter, looked up at most every five seconds: {when, who}. */
    private static final Map<UUID, Object[]> HOLDER = new ConcurrentHashMap<>();

    public static void resetForTests() {
        HOLDER.clear();
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        resetForTests();
    }

    // ------------------------------------------------------------------ who

    /** Does the town keep a carter: a wagon of its own to drive? */
    public static boolean stands(@Nullable UUID id) {
        return id != null && Villages.get(id) != null && Wagons.owned(id) != null;
    }

    /** Free for the post: grown and of the town, not the elder, nor a hand whose day is the watch's or out of the town. */
    static boolean fit(VillageFolkEntity f, UUID id) {
        if (!f.isAlive() || f.isBaby() || f.isShowcase() || f.isHired() || f.isElder() || !id.equals(f.ownerId())) return false;
        if (f.expedition() != null || f.ageYears() < 18 || f.ageYears() > 64) return false;
        StationTask t = f.stationTask();
        return t != StationTask.GUARD && t != StationTask.NETHER && t != StationTask.CAVE && t != StationTask.SCOUT
            && t != StationTask.EMERALD && t != StationTask.FERRY && t != StationTask.DIVER && t != StationTask.HEAL
            && t != StationTask.STORE && t != StationTask.BANK;
    }

    /** How well suited: a courier (the roads its work), a rancher (it knows horses), a steady, hard-working nature. */
    static int score(VillageFolkEntity f) {
        StationTask t = f.stationTask();
        int s = f.tradeLevel(StationTask.HAUL) + f.tradeLevel(StationTask.RANCH);
        if (t == StationTask.HAUL) s += 30;
        if (t == StationTask.RANCH) s += 20;
        if (f.life().has(Social.Trait.HARDWORKING)) s += 10;
        if (f.life().has(Social.Trait.EASYGOING)) s += 5;
        if (f.ageYears() >= 22 && f.ageYears() <= 50) s += 5;
        return s;
    }

    /** The town's carter: the interview's choice while it is fit, else the best suited; null with no wagon. */
    @Nullable
    public static VillageFolkEntity holder(@Nullable UUID id) {
        if (id == null) return null;
        Object[] was = HOLDER.get(id);
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        long now = server == null ? 0L : server.getTickCount();
        if (was != null && now - (Long) was[0] < 100L && now >= (Long) was[0]) {
            VillageFolkEntity f = (VillageFolkEntity) was[1];
            if (f == null || f.isAlive() && fit(f, id)) return f;
        }
        VillageFolkEntity got = reckon(id);
        HOLDER.put(id, new Object[]{ now, got });
        return got;
    }

    @Nullable
    private static VillageFolkEntity reckon(UUID id) {
        if (!stands(id)) return null;
        VillageFolkEntity chosen = Interviews.holder(id, POST);
        if (chosen != null && fit(chosen, id)) return chosen;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !fit(f, id)) continue;
            int s = score(f);
            if (s > bestScore) { bestScore = s; best = f; }
        }
        return best;
    }

    /** The town's carter looked up afresh at the next ask (a new one chosen at interview). */
    static void forget(UUID id) {
        HOLDER.remove(id);
    }

    /** Is this folk its town's carter? */
    public static boolean is(VillageFolkEntity f) {
        return f.ownerId() != null && f == holder(f.ownerId());
    }

    /** [Caravans.choose] The carter first, before the couriers and the nearest hand. */
    static double lean(VillageFolkEntity f) {
        return is(f) ? -2.0e6 : 0.0;
    }

    // ------------------------------------------------------------------ paid by the trip

    /** A trip's fee at this day's unit, for a road this long. */
    static int fee(double dayUnit, double road) {
        double days = Math.min(MOST_DAYS, TRIP_SHARE * (1.0 + road / ROAD_UNIT));
        return Math.max(1, (int) Math.round(dayUnit * days));
    }

    /**
     * [Caravans.arrive, Wagons.shipmentLeg] Home from a run: the carter paid its fee out of the treasury (what it holds,
     * if less), and the run written in the books. Nothing for anybody else, nor for an envoy's errand.
     */
    static void tripHome(ServerLevel level, VillageFolkEntity f, Caravans.Trip t) {
        if (t == null || t.errand != null || !is(f)) return;
        UUID home = t.from;
        Villages.Village a = Villages.get(t.from), b = Villages.get(t.to);
        double road = a == null || b == null ? 0.0 : 2.0 * Math.sqrt(a.centre().distSqr(b.centre()));
        int want = fee(JobWorth.dayUnit(f), road);
        int paid = Ledger.takeCoins(home, want);
        if (paid > 0) {
            f.earn(paid);
            Economy.spent(home, paid);
        }
        long day = level.getDayTime() / 24000L;
        String[] book = book(home);
        int runs = parse(book[0]) + 1, coins = parse(book[1]) + paid;
        Ledger.note(home, BOOK, runs + "|" + coins + "|" + day + "|" + Villages.name(t.to).replace("|", " "));
        FolkTalk.speak(f, paid > 0 ? FolkTalk.pick(f.getRandom(), "Home from " + Villages.name(t.to) + ", and " + paid + (paid == 1 ? " coin" : " coins")
                + " for the run.", "That's the run done. " + paid + " for the carter!")
            : "Home from " + Villages.name(t.to) + ". Nothing in the treasury for the run, mind.");
        LOG.info("[MCA-CARTER] {} home from {} ({} blocks there and back): paid {} of {}", f.displayNameCap(), Villages.name(t.to), (int) road, paid, want);
    }

    private static String[] book(UUID id) {
        String s = Ledger.note(id, BOOK);
        String[] p = s == null || s.isEmpty() ? new String[0] : s.split("\\|", -1);
        String[] out = { "0", "0", "-1", "" };
        System.arraycopy(p, 0, out, 0, Math.min(p.length, out.length));
        return out;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The town's runs and the fees paid for them: {runs, coins}. */
    public static int[] record(UUID id) {
        String[] b = book(id);
        return new int[]{ parse(b[0]), parse(b[1]) };
    }

    // ------------------------------------------------------------------ the whip

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 15 || Wagons.DRIVING.isEmpty() && WHIPPED.isEmpty()) return;
        com.jrpetty.mcassistant.Guard.run("carter", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (UUID u : new ArrayList<>(Wagons.DRIVING.keySet())) {
                    if (level.getEntity(u) instanceof VillageFolkEntity f && is(f)) whip(level, Villages.get(f.ownerId()), f);
                }
                for (UUID u : new ArrayList<>(WHIPPED)) {
                    if (level.getEntity(u) instanceof VillageFolkEntity f && !Wagons.DRIVING.containsKey(u)) whip(level, f.ownerId() == null ? null : Villages.get(f.ownerId()), f);
                }
            }
        });
    }

    /** Carters holding their whip on the bench just now. */
    private static final java.util.Set<UUID> WHIPPED = ConcurrentHashMap.newKeySet();

    /** On the bench, its whip in its other hand (out of its pack, or made from the stores); off it, back into its pack. */
    static void whip(ServerLevel level, @Nullable Villages.Village v, VillageFolkEntity c) {
        ItemStack off = c.getItemBySlot(EquipmentSlot.OFFHAND);
        boolean driving = Wagons.DRIVING.containsKey(c.getUUID());
        if (driving) {
            if (!off.isEmpty()) return;
            ItemStack w = fromPack(c);
            if (w.isEmpty() && v != null) w = make(level, v);
            if (w.isEmpty()) return;
            c.setItemSlot(EquipmentSlot.OFFHAND, w);
            WHIPPED.add(c.getUUID());
            return;
        }
        if (!WHIPPED.remove(c.getUUID()) || !off.is(RoadItems.DRIVING_WHIP.get())) return;
        c.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        ItemStack left = c.insertItem(off);
        if (!left.isEmpty()) {
            if (v != null) Crafts.store(level, v, left);
            else c.spawnAtLocation(left);
        }
    }

    private static ItemStack fromPack(VillageFolkEntity c) {
        var inv = c.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.is(RoadItems.DRIVING_WHIP.get())) {
                ItemStack one = s.split(1);
                if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    /** A driving whip made from the stores: a stick for the stock, string for the lash, leather for the grip. */
    private static ItemStack make(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Market.stock(level, id, s -> s.is(Items.STICK)) < 1 || Market.stock(level, id, s -> s.is(Items.STRING)) < 1
            || Market.stock(level, id, s -> s.is(Items.LEATHER)) < 1) return ItemStack.EMPTY;
        if (!Crafts.take(level, v, s -> s.is(Items.STICK), 1)) return ItemStack.EMPTY;
        if (!Crafts.take(level, v, s -> s.is(Items.STRING), 1)) {
            Crafts.store(level, v, new ItemStack(Items.STICK));
            return ItemStack.EMPTY;
        }
        if (!Crafts.take(level, v, s -> s.is(Items.LEATHER), 1)) {
            Crafts.store(level, v, new ItemStack(Items.STICK));
            Crafts.store(level, v, new ItemStack(Items.STRING));
            return ItemStack.EMPTY;
        }
        return new ItemStack(RoadItems.DRIVING_WHIP.get());
    }

    // ------------------------------------------------------------------ the books and the talk

    /** "/village roads": the carter and its runs. */
    static List<String> lines(Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        VillageFolkEntity c = holder(id);
        int[] r = record(id);
        String[] b = book(id);
        out.add("Carter: " + (c == null ? (stands(id) ? "none yet" : "not kept (no wagon)") : c.displayNameCap() + " (" + c.stationTask().label + ")"
            + (Wagons.busy(c) ? ", out on the road" : "")) + "; " + r[0] + (r[0] == 1 ? " run" : " runs") + ", " + coins(r[1]) + " in fees"
            + (b[3].isEmpty() ? "" : "; last to " + b[3]));
        return out;
    }

    /** Its card (FolkTalk.card): the carter's post and its runs. */
    static String cardLine(VillageFolkEntity f) {
        if (!is(f)) return "";
        int[] r = record(f.ownerId());
        return "the town's carter: " + r[0] + (r[0] == 1 ? " run" : " runs") + ", " + coins(r[1]) + " in fees";
    }

    /** "Where's the wagon bound?": out on the road (where, and with what), or home in the yard, and its runs. */
    static String talk(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || !stands(id)) return "We've no wagon. Whatever goes out of here goes on somebody's back.";
        VillageFolkEntity c = holder(id);
        WagonKind k = Wagons.owned(id);
        TradeWagon w = Wagons.wagonOf(level, id);
        String where;
        if (w != null && w.phase != TradeWagon.Phase.PARKED) {
            UUID to = w.back ? w.from : w.to;
            String load = w.cargoSummary().isEmpty() ? "empty" : "laden";
            where = "The " + k.label + "'s " + (w.back ? "on its way home" : "bound for " + (to == null ? "the road" : Villages.name(to))) + ", " + load + ".";
        } else if (w == null) {
            where = "The " + k.label + "'s out on the road, out of sight.";
        } else {
            where = "The " + k.label + "'s in the yard, waiting for its next load.";
        }
        int[] r = record(id);
        if (c != f) return where + (c == null ? "" : " " + c.displayNameCap() + " drives it: ask it.");
        return where + " I've driven her " + r[0] + (r[0] == 1 ? " run" : " runs") + " for the town, " + coins(r[1]) + " in fees all told.";
    }

    private static String coins(int n) {
        return n + (n == 1 ? " coin" : " coins");
    }

    /** Typed words that ask the carter about the wagon's road. */
    static boolean meant(String lower) {
        return lower.contains("wagon") && (lower.contains("bound") || lower.contains("where") || lower.contains("going") || lower.contains("next load"));
    }

    // ------------------------------------------------------------------ for the tests

    public static int feeForTests(double dayUnit, double road) {
        return fee(dayUnit, road);
    }

    /** Tests: the trip home, as the carrier ends it there (Caravans.arrive): paid if it is the carter. */
    public static void tripHomeForTests(ServerLevel level, VillageFolkEntity f, UUID from, UUID to) {
        Caravans.Trip t = new Caravans.Trip(from, to, new ArrayList<>());
        t.back = true;
        tripHome(level, f, t);
    }

    public static void forgetForTests(UUID id) {
        HOLDER.remove(id);
    }

    public static String talkForTests(VillageFolkEntity f) {
        return f.level() instanceof ServerLevel level ? talk(f, level) : "";
    }

    /** Tests: the carrier the town picks for a caravan or a shipment now (Caravans.choose). */
    @Nullable
    public static VillageFolkEntity chooseForTests(Villages.Village v) {
        return Caravans.choose(v);
    }

    /** Tests: this folk on the wagon's bench (as Wagons has a driver), or off it. */
    public static void drivingForTests(VillageFolkEntity c, TradeWagon w, boolean on) {
        if (on) Wagons.DRIVING.put(c.getUUID(), w.getUUID());
        else Wagons.DRIVING.remove(c.getUUID());
    }

    public static void whipForTests(ServerLevel level, VillageFolkEntity c) {
        whip(level, c.ownerId() == null ? null : Villages.get(c.ownerId()), c);
    }
}
