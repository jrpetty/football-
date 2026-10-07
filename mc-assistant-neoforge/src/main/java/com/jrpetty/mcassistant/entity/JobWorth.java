package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What every job is worth, and so what it pays. [econ-wages]
 *
 * <p>A folk's day's wage is the town's pay level times its job's worth, times its own hand at the job.
 *
 * <p><b>The pay level</b> is what the place is (a hamlet pays the unit, a village half as much again, a town twice,
 * a city two and a half times and a capital three: Wealth.standing) at what its treasury can afford. Each morning
 * the day's wage bill is set against what has been coming in (the day's work taken into the treasury, the traders'
 * and the players' coin, the tax, the tithe, the rent, the houses sold and what the folk spent in town, a few days
 * smoothed). While the bill runs over seventeen twentieths of it, the rate eases down, never under thirteen
 * twentieths of the full rate; while it runs well under, and the treasury has a week's wages put by, it eases up
 * again, as far as six fifths. A town paying out more than it took in used to find out the morning its treasury
 * was empty, and then paid everybody short (Market.payWages); now it pays a little less in good time.
 *
 * <p><b>A job's worth</b> is three things, each shown on the wages page:
 * <ul>
 * <li><b>Its value to the town.</b> What one hand of the trade brought in a day, at today's prices in this town
 *     (PriceIndex.each over what the books say it made: Economy), against what a hand brings in on average. A maker
 *     counts half of what it made as its own: the ore, the wool or the wheat it worked was another trade's work,
 *     already counted there. The work that makes nothing to sell is valued for its service instead, a fixed share
 *     of what a hand brings in that grows a little with the town (a city leans on its watch and its storehouse
 *     more than a hamlet does): the watch keeps folk alive, the couriers and the storekeeper keep the goods moving,
 *     the banker keeps the savings and the loans, the teacher the children, a healer (if the town has one) its
 *     folk well, the shop's keeper its sales, its assistants the counter and its stock keeper the shelves full. The
 *     square root of the one against the other, so a trade that brings in four times the rest is paid twice as
 *     well, not four times.</li>
 * <li><b>How hard it is to fill.</b> The hands it has against the town's share for it (Villages.share), a notice
 *     on the board nobody has answered (JobMarket), and how few in the town have the skill for it. Short-handed
 *     trades are paid more and over-staffed ones less, eased toward the morning's figure a third of the way a day
 *     and never more than a tenth in a day, so a trade's pay rises over a few days as it stays short, and does not
 *     jump about with every comer and goer.</li>
 * <li><b>How hard it is, and the skill and learning it takes.</b> Fixed for each job: the fields, the water and
 *     the couriers' rounds are easy work; the mines, the watch and the furnaces hard and dangerous; the smith, the
 *     enchanter, the banker, the teacher and the shop's stock keeper need much skill. At the shop (ShopRoles), the
 *     keeper runs the place, the assistants serve at the counter, the crafters make, and the stock keeper's is a
 *     hard, skilled job.</li>
 * </ul>
 *
 * <p><b>Its own hand</b> at the job: its level on a smooth curve (four fifths of the rate new to it, the rate at
 * level five, half as much again for a master, with no steps at ten and twenty-five), a little more for a maker
 * whose marks say good, fine or a master's work (Craftsmanship), and for each knack of the trade it chose
 * (FolkSkills).
 *
 * <p>On top, as before: a coin for the elder, up to two for a hard day's work, a Haggler's twentieth (Wealth.wage);
 * the teacher half a teacher's day for its mornings at the school; and at payday the leader's rate (Leader.payRate)
 * and the tax, and an even share for everybody when the treasury is short (Market.payWages).
 *
 * <p><b>Bounds.</b> The lowest wage is a living wage: it covers two meals and the rent of a house at today's prices
 * (Purchases.costOfLiving when the town has its shop's prices; two loaves at the town's price and a plain house's
 * rent otherwise), whatever the job is worth and whatever the leader's rate. Top pay is held to five times the
 * lowest, so a master enchanter in a capital does not drain the treasury.
 *
 * <p>The figures are kept in the village's notes (the Ledger), so they outlast a restart; the day's scale is drawn
 * up each morning before the wages are paid (Market.tick) and kept for the day, so a folk's pay is the same all
 * day however often it is asked.
 */
public final class JobWorth {

    private JobWorth() {}

    /** A journeyman field hand's day in a hamlet at the full rate: the unit every job's worth is a multiple of. */
    public static final double UNIT = 1.5;
    /** Top pay is held to this many times the lowest. */
    public static final int CAP_TIMES = 5;
    /** What the treasury can afford, as a share of the full rate: never under, never over. */
    public static final double AFFORD_LO = 0.65, AFFORD_HI = 1.2;
    /** The day's wage bill is kept to this share of what comes in; the rest is for buying in and a rainy day. */
    public static final double MARGIN = 0.85;
    /** The value and the scarcity a job is reckoned at, at the least and at the most. */
    static final double VALUE_LO = 0.7, VALUE_HI = 1.6, SCARCE_LO = 0.75, SCARCE_HI = 1.5;
    /** What a hand brings in a day before the books know better (a town's first morning). */
    static final double DEFAULT_REF = 4.0;
    /** How far the figures move toward each morning's in a day: a third of the way, or near enough. */
    static final double SMOOTH = 0.3, SMOOTH_SCARCE = 0.35, SMOOTH_AFFORD = 0.3;
    /** At most this much change in a day: a trade's scarcity, and what the treasury can afford. */
    static final double SCARCE_STEP = 0.1, AFFORD_STEP = 0.06;
    /** The posted rate is for a hand of this much experience at the trade, its own hand reckoned at one (the curve
     *  passes one at about level five). */
    public static final int JOURNEYMAN = 5;
    static final double JOURNEYMAN_HAND = 1.0;
    /** The teacher's mornings at the school: half a teacher's day, on top of its own trade. */
    static final double TEACHING_SHARE = 0.5;
    /** The teacher's job, by its key. */
    public static final String TEACH = "TEACH";

    // ------------------------------------------------------------------ the jobs

    /**
     * A job: a trade, one of the shop's jobs, or the teacher's. What is fixed about it: how hard and dangerous it
     * is (nought to three), how much skill and learning it takes (nought to four), the service it does the town
     * that nothing sold measures (a share of what a hand brings in), and how much of its trade's output is its own.
     */
    public static final class Post {
        public final String key, title;
        @Nullable public final StationTask trade;
        @Nullable public final ShopRoles.Role role;
        public final int hard, learned;
        final double service, makes;
        /** "hard and dangerous work"; "keeps folk alive" (empty: no service beyond what it makes). */
        final String hardWords, serviceWords;

        Post(String key, String title, @Nullable StationTask trade, @Nullable ShopRoles.Role role, int hard, int learned,
             double service, double makes, String hardWords, String serviceWords) {
            this.key = key;
            this.title = title;
            this.trade = trade;
            this.role = role;
            this.hard = hard;
            this.learned = learned;
            this.service = service;
            this.makes = makes;
            this.hardWords = hardWords;
            this.serviceWords = serviceWords;
        }

        /** How hard the job is, and the skill it takes, as a multiple of the unit: a field hand's 1, a smith's 1.78. */
        public double difficulty() {
            return 1.0 + 0.12 * hard + 0.18 * learned;
        }

        /** "miner", "stock keeper", "teacher": one who does it. */
        public String noun() {
            if (role != null) return role.title.toLowerCase(Locale.ROOT);
            if (trade == null) return "teacher";
            return JobMarket.noun(trade);
        }
    }

    private static Post make(String key, String title, @Nullable StationTask trade, @Nullable ShopRoles.Role role) {
        return switch (key) {
            case "FARM" -> new Post(key, title, trade, role, 0, 0, 0.0, 1.0, "easy work", "");
            case "FISH" -> new Post(key, title, trade, role, 0, 0, 0.0, 1.0, "easy work", "");
            case "WOOD" -> new Post(key, title, trade, role, 1, 0, 0.0, 1.0, "heavy work", "");
            // The couriers make nothing of their own, but nothing would reach the storehouse without them.
            case "HAUL" -> new Post(key, title, trade, role, 0, 0, 0.9, 1.0, "easy work", "keep the goods moving");
            case "STORE" -> new Post(key, title, trade, role, 0, 1, 1.0, 1.0, "steady work and a head for figures",
                "keeps the storehouse and its books");
            case "RANCH" -> new Post(key, title, trade, role, 0, 1, 0.0, 1.0, "a way with beasts", "");
            case "BEEKEEP" -> new Post(key, title, trade, role, 1, 1, 0.0, 1.0, "stings and smoke", "");
            case "HUNT" -> new Post(key, title, trade, role, 2, 1, 0.0, 1.0, "long days out, and risky", "");
            case "SCOUT" -> new Post(key, title, trade, role, 2, 1, 0.7, 1.0, "long days out, and risky", "map the land beyond the fields");
            case "MINE" -> new Post(key, title, trade, role, 3, 1, 0.0, 1.0, "hard and dangerous work", "");
            // The watch makes nothing but what the night's monsters drop; it keeps the town's folk alive.
            case "GUARD" -> new Post(key, title, trade, role, 3, 1, 1.2, 1.0, "hard and dangerous work", "keeps folk alive");
            case "SMELT" -> new Post(key, title, trade, role, 3, 1, 0.0, 1.0, "hot and dangerous work", "");
            case "COOK" -> new Post(key, title, trade, role, 1, 1, 0.0, 1.0, "hot work and a knack", "");
            case "TAILOR" -> new Post(key, title, trade, role, 0, 2, 0.0, 1.0, "skilled work", "");
            case "BREW" -> new Post(key, title, trade, role, 0, 2, 0.0, 1.0, "skilled work", "");
            case "SMITH" -> new Post(key, title, trade, role, 2, 3, 0.0, 1.0, "hard work that takes much skill", "");
            case "ENCHANT" -> new Post(key, title, trade, role, 0, 4, 0.0, 1.0, "learned work", "");
            case "BANK" -> new Post(key, title, trade, role, 0, 4, 1.2, 1.0, "learned work", "keeps the town's savings and its loans");
            // [caves] Underground, armed, among the monsters and the lava: as hard as the watch, as learned as the enchanter,
            // and a small team: among the best paid in the town.
            case "CAVE" -> new Post(key, title, trade, role, 3, 4, 1.0, 1.0, "dangerous, skilled work underground; a small team",
                "finds the town its ore, and its dangers");
            // [transport] Out on the water in all weathers but the worst: it makes nothing, and carries everybody over.
            case "FERRY" -> new Post(key, title, trade, role, 1, 1, 0.9, 0.0, "out on the water, and steady",
                "carries the town's folk over the water");
            // [fletcher] Skilled bench work, a craftsman's: the watch's arrows and bows, and the range's practice.
            case "FLETCHER" -> new Post(key, title, trade, role, 0, 2, 0.6, 1.0, "skilled work at the bench",
                "keeps the watch's quivers full");
            // [golems] Iron blocks are heavy and a golem's fists are heavier: hard, learned work that keeps the town.
            case "GOLEMS" -> new Post(key, title, trade, role, 2, 3, 1.1, 0.0, "heavy, skilled work with the town's iron",
                "keeps the town's golems at the gates");
            // The shop's jobs (ShopRoles): the keeper makes a little and runs the place; the assistants and the
            // stock keeper make nothing, and are paid for what they do for the shop.
            case "SHOP/KEEPER" -> new Post(key, title, trade, role, 1, 2, 1.0, 0.5, "runs the place", "keeps the shop and its sales");
            case "SHOP/ASSISTANT" -> new Post(key, title, trade, role, 0, 1, 0.8, 0.0, "serving at the counter",
                "serves the customers face to face");
            case "SHOP/CRAFTER" -> new Post(key, title, trade, role, 0, 2, 0.0, 1.0, "skilled work at the bench", "");
            case "SHOP/STOCK_KEEPER" -> new Post(key, title, trade, role, 2, 3, 1.15, 0.0, "a hard, skilled job",
                "keeps the shop's shelves full");
            case TEACH -> new Post(key, title, trade, role, 0, 4, 1.2, 0.0, "learned work", "teaches the children");
            default -> key.contains("HEAL")
                ? new Post(key, title, trade, role, 1, 4, 1.3, 1.0, "learned work", "keeps folk well")     // a healer, if the town has one
                : new Post(key, title, trade, role, 1, 1, 0.0, 1.0, "", "");
        };
    }

    /** How much of what the trade makes is its own work: a maker's half (the rest was another trade's goods). */
    static double own(StationTask t) {
        return switch (t.name()) {
            case "SMELT", "COOK", "TAILOR", "BREW", "SMITH", "ENCHANT", "SHOP" -> 0.5;
            case "FLETCHER" -> 0.5;                                     // [fletcher] the flint, feathers and sticks are others' work
            default -> 1.0;
        };
    }

    /** What a hand of the trade is reckoned to bring in before the books know better, against an average hand. */
    static double prior(StationTask t) {
        String n = t.name();
        if (n.contains("HEAL") || n.contains("TEACH")) return 0.0;
        return switch (n) {
            case "GUARD" -> 0.15;
            case "SCOUT" -> 0.1;
            case "HAUL", "STORE", "BANK", "FERRY" -> 0.0;                 // [transport] the ferryman makes nothing
            case "GOLEMS" -> 0.0;                                         // [golems] its golems are kept, not sold
            default -> 1.0;
        };
    }

    private static List<Post> POSTS;

    /** Every job there is: each trade, the shop's four, and the teacher's. */
    public static List<Post> posts() {
        if (POSTS == null) {
            List<Post> out = new ArrayList<>();
            for (StationTask t : StationTask.values()) {
                if (t == StationTask.NONE) continue;
                if (t == StationTask.SHOP) {
                    for (ShopRoles.Role r : new ShopRoles.Role[]{ ShopRoles.Role.KEEPER, ShopRoles.Role.STOCK_KEEPER, ShopRoles.Role.CRAFTER,
                            ShopRoles.Role.ASSISTANT }) {
                        out.add(make("SHOP/" + r.name(), r.title, t, r));
                    }
                } else {
                    out.add(make(t.name(), t == StationTask.HAUL ? "Courier" : t.title, t, null));
                }
            }
            out.add(make(TEACH, "Teacher", null, null));
            POSTS = List.copyOf(out);
        }
        return POSTS;
    }

    /** A job by its key ("MINE", "SHOP/STOCK_KEEPER", "TEACH"), or null. */
    @Nullable
    public static Post post(String key) {
        for (Post p : posts()) if (p.key.equals(key)) return p;
        return null;
    }

    /** The job of a trade, and at the shop the job it does there (an assistant if it is nobody's yet). */
    public static Post postFor(StationTask t, @Nullable ShopRoles.Role role) {
        String key = t != StationTask.SHOP ? t.name()
            : "SHOP/" + (role == null || role == ShopRoles.Role.NONE ? ShopRoles.Role.ASSISTANT : role).name();
        Post p = post(key);
        return p != null ? p : posts().get(0);
    }

    /** This folk's job. */
    public static Post postOf(VillageFolkEntity f) {
        StationTask t = f.stationTask();
        return postFor(t, t == StationTask.SHOP ? ShopRoles.role(f) : null);
    }

    /** The job a notice for the trade would be for: at the shop, its keeper while it has none, then a hand at its bench. */
    static Post wantedPost(StationTask t, @Nullable UUID village) {
        if (t != StationTask.SHOP) return postFor(t, null);
        return postFor(t, Workshop.keeper(village) == null ? ShopRoles.Role.KEEPER : ShopRoles.Role.CRAFTER);
    }

    // ------------------------------------------------------------------ the figures kept

    /** What the village's books say of its jobs, kept in its notes: smoothed from morning to morning. */
    static final class State {
        long day = -1;
        double afford = 1.0, ref = -1, income = -1;
        /** By trade: {what a hand brings in a day (its own share, at the town's prices), its scarcity}. */
        final Map<String, double[]> trades = new HashMap<>();
        /** Each job's posted rate last morning (for the news of a rise). */
        final Map<String, Integer> rates = new HashMap<>();

        double val(StationTask t, double ref) {
            double[] a = trades.get(t.name());
            return a != null && a[0] >= 0 ? a[0] : ref * prior(t);
        }

        double scar(StationTask t) {
            double[] a = trades.get(t.name());
            return a == null ? 1.0 : a[1];
        }
    }

    static State state(UUID id) {
        State s = new State();
        s.day = parseLong(Ledger.note(id, "wg.day"), -1);
        s.afford = clamp(parse(Ledger.note(id, "wg.afford"), 1.0), AFFORD_LO, AFFORD_HI);
        s.ref = parse(Ledger.note(id, "wg.ref"), -1);
        s.income = parse(Ledger.note(id, "wg.income"), -1);
        String t = Ledger.note(id, "wg.trades");
        if (t != null && !t.isEmpty()) {
            for (String e : t.split(",")) {
                String[] p = e.split(":");
                if (p.length < 3) continue;
                s.trades.put(p[0], new double[]{ parse(p[1], -1), clamp(parse(p[2], 1.0), SCARCE_LO, SCARCE_HI) });
            }
        }
        String r = Ledger.note(id, "wg.rates");
        if (r != null && !r.isEmpty()) {
            for (String e : r.split(",")) {
                String[] p = e.split(":");
                if (p.length == 2) s.rates.put(p[0], (int) parse(p[1], 0));
            }
        }
        return s;
    }

    static void save(UUID id, State s) {
        Ledger.note(id, "wg.day", Long.toString(s.day));
        Ledger.note(id, "wg.afford", fmt3(s.afford));
        Ledger.note(id, "wg.ref", fmt3(s.ref));
        Ledger.note(id, "wg.income", fmt3(s.income));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, double[]> e : s.trades.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(fmt3(e.getValue()[0])).append(':').append(fmt3(e.getValue()[1]));
        }
        Ledger.note(id, "wg.trades", sb.toString());
        StringBuilder rs = new StringBuilder();
        for (Map.Entry<String, Integer> e : s.rates.entrySet()) {
            if (rs.length() > 0) rs.append(',');
            rs.append(e.getKey()).append(':').append(e.getValue());
        }
        Ledger.note(id, "wg.rates", rs.toString());
    }

    // ------------------------------------------------------------------ the day's scale

    /** One job's worth in the town today, part by part, and its posted rate (a journeyman's day). */
    public record Worth(Post post, int have, double target, double perHand, double service, double value, double scarcity,
                        double difficulty, double factor, int rate) {
        /** How many hands short of the town's share (negative: over it). */
        public double shortBy() {
            return target - have;
        }
    }

    /** The day's pay scale in a village: its pay level, its bounds, and what every job is worth. */
    public static final class Scale {
        @Nullable public final UUID village;
        public final long day;
        /** The place's standing (1 for a hamlet ... 3 for a capital), what the treasury can afford, and the two together. */
        public final double standing, afford, payLevel;
        /** What a producing hand brings in a day on average, and the service figures' tuning to the town's size. */
        public final double ref, tune;
        /** Two meals and the rent today, and how that was reckoned. */
        public final double costOfLiving;
        public final String costWords;
        /** The living wage (the cost of living, in whole coins), the lowest wage on the scale, and the top. */
        public final int floor;
        int lowest, cap;
        /** The day's bill as it will be paid (at the leader's rate) and the money coming in a day; -1 unknown. */
        int bill = -1, income = -1;
        final boolean fromBooks;
        final Map<String, Worth> worth = new LinkedHashMap<>();

        Scale(@Nullable UUID village, long day, double standing, double afford, double ref, double tune, double cost, String costWords,
              boolean fromBooks) {
            this.village = village;
            this.day = day;
            this.standing = standing;
            this.afford = afford;
            this.payLevel = standing * afford;
            this.ref = ref;
            this.tune = tune;
            this.costOfLiving = cost;
            this.costWords = costWords;
            this.floor = Math.max(1, (int) Math.ceil(cost - 1e-6));
            this.lowest = floor;
            this.cap = CAP_TIMES * floor;
            this.fromBooks = fromBooks;
        }

        public int lowest() { return lowest; }

        public int cap() { return cap; }

        public int bill() { return bill; }

        public int income() { return income; }

        @Nullable
        public Worth worth(String key) {
            return worth.get(key);
        }

        public java.util.Collection<Worth> all() {
            return worth.values();
        }
    }

    /** The day's scale for each village, drawn up once a day. */
    private static final Map<UUID, Scale> SCALES = new ConcurrentHashMap<>();
    /** The villages whose scale is being drawn up right now (the rent it reckons asks for a field hand's wage). */
    private static final Set<UUID> BUILDING = ConcurrentHashMap.newKeySet();
    /** While a village's scale is drawn up: the field hand's wage its rents are being reckoned at. */
    private static final Map<UUID, Integer> FARM_GUESS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SCALES.clear();
        BUILDING.clear();
        FARM_GUESS.clear();
    }

    /** Today's scale in this village, drawn up now if it has not been today. */
    public static Scale scale(@Nullable ServerLevel level, @Nullable UUID id) {
        if (id == null) return light(null, 0L, new State());
        Scale had = SCALES.get(id);
        // Asked while it is being drawn up (the rent asks for a field hand's wage): yesterday's, or the plain rates.
        if (BUILDING.contains(id)) return had != null ? had : light(id, had == null ? 0L : had.day, state(id));
        ServerLevel lvl = level != null ? level : levelOf(id);
        if (lvl == null) return had != null ? had : light(id, 0L, state(id));
        long day = lvl.getDayTime() / 24000L;
        // Kept for the day, unless the place has come up (or down) in the world since: a village made a town at
        // noon pays as a town from then on, as it always did.
        if (had != null && had.day == day && Math.round(had.standing * 10) == Wealth.standing(id)) return had;
        State st = state(id);
        Scale s = build(lvl, id, day, st, st.afford);
        // A town nobody is near (a notice of its read far away) is reckoned without its folk or its stores in view:
        // good for the asking, not kept for the day.
        if (!AssistantEntity.allFor(id).isEmpty()) SCALES.put(id, s);
        return s;
    }

    /** A folk's town's scale today. */
    static Scale scale(VillageFolkEntity f) {
        return scale(f.level() instanceof ServerLevel sl ? sl : null, f.ownerId());
    }

    @Nullable
    private static ServerLevel levelOf(UUID id) {
        for (AssistantEntity a : AssistantEntity.allFor(id)) if (a.level() instanceof ServerLevel sl) return sl;
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.overworld();
    }

    /**
     * The plain scale, without the cost of living or anything that asks for a wage: the place's standing and the
     * figures kept, the floor a coin. Only while the real one is being drawn up, and for a folk of no village.
     */
    private static Scale light(@Nullable UUID id, long day, State st) {
        double standing = Wealth.standing(id) / 10.0;
        Scale s = new Scale(id, day, standing, st.afford, st.ref > 0 ? st.ref : DEFAULT_REF, 1.0, 0.0, "", st.ref > 0);
        s.cap = 1000;                                            // no cost of living reckoned here: no bound but the coin
        for (Post p : posts()) {
            double perHand = p.trade == null ? 0.0 : st.val(p.trade, s.ref) * p.makes;
            double service = p.service * s.ref;
            double value = clamp(Math.sqrt(Math.max(0.0, perHand + service) / s.ref), VALUE_LO, VALUE_HI);
            double scar = p.trade == null ? 1.0 : st.scar(p.trade);
            double factor = value * scar * p.difficulty();
            s.worth.put(p.key, new Worth(p, 0, 0, perHand, service, value, scar, p.difficulty(), factor,
                Math.max(1, (int) Math.round(s.payLevel * UNIT * factor * JOURNEYMAN_HAND))));
        }
        return s;
    }

    /** The day's scale, from the figures kept: the cost of living, every job's worth, the bounds, the bill. */
    static Scale build(ServerLevel level, UUID id, long day, State st, double afford) {
        BUILDING.add(id);
        try {
            List<AssistantEntity> folk = Villages.folkOf(id);
            int head = Math.max(1, Villages.headcount(id));
            // The service figures grow a little with the town: fifteen in the hundred less in a hamlet of ten than
            // in a village of twenty, as much more again at forty, and so on, at most nine twentieths more.
            double tune = clamp(0.85 + 0.15 * Math.log(Math.max(1.0, head) / 10.0) / Math.log(2.0), 0.85, 1.45);
            double ref = st.ref > 0 ? st.ref : DEFAULT_REF;
            double payLevel = Wealth.standing(id) / 10.0 * afford;
            // The living wage and the field hand's day settle together: a house's rent is reckoned by a field hand's
            // wage (Homes.baseRent), and the lowest wage must cover the rent. Each round the rent at the field hand's
            // last wage, then the field hand's wage at least the cost of living; the rent moves half as much as the
            // wage, so two or three rounds settle it.
            Post farm = postFor(StationTask.FARM, null);
            double farmFactor = clamp(Math.sqrt(Math.max(0.0, st.val(StationTask.FARM, ref) * farm.makes) / ref), VALUE_LO, VALUE_HI)
                * st.scar(StationTask.FARM) * farm.difficulty();
            int farmRate = Math.max(1, (int) Math.round(payLevel * UNIT * farmFactor * JOURNEYMAN_HAND));
            Cost cost = null;
            for (int round = 0; round < 6; round++) {
                FARM_GUESS.put(id, farmRate);
                cost = costOfLiving(level, id);
                int next = Math.max(Math.max(1, (int) Math.ceil(cost.coins() - 1e-6)),
                    (int) Math.round(payLevel * UNIT * farmFactor * JOURNEYMAN_HAND));
                if (next == farmRate) break;
                farmRate = next;
            }
            FARM_GUESS.remove(id);
            Scale s = new Scale(id, day, Wealth.standing(id) / 10.0, afford, ref, tune, cost.coins(), cost.words(), st.ref > 0);
            Map<StationTask, Integer> have = new EnumMap<>(StationTask.class);
            for (AssistantEntity a : folk) {
                if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() != StationTask.NONE) have.merge(f.stationTask(), 1, Integer::sum);
            }
            Map<StationTask, Double> target = new EnumMap<>(StationTask.class);
            for (StationTask t : StationTask.values()) {
                if (t != StationTask.NONE) target.put(t, have.getOrDefault(t, 0) - Villages.share(id, t));
            }
            List<Worth> made = new ArrayList<>();
            for (Post p : posts()) {
                StationTask t = p.trade;
                int n = t == null ? 0 : have.getOrDefault(t, 0);
                double perHand = t == null ? 0.0 : st.val(t, ref) * p.makes;
                double service = p.service * ref * tune;
                double value = clamp(Math.sqrt(Math.max(0.0, perHand + service) / ref), VALUE_LO, VALUE_HI);
                double scar = t == null ? 1.0 : st.scar(t);
                double factor = value * scar * p.difficulty();
                made.add(new Worth(p, n, t == null ? 0 : target.getOrDefault(t, (double) n), perHand, service, value, scar,
                    p.difficulty(), factor, 0));
            }
            // The lowest wage: the living wage, or a novice at the least of the jobs the town has or wants, if more.
            int novice = Integer.MAX_VALUE;
            for (Worth w : made) {
                StationTask t = w.post().trade;
                if (t == null || w.have() == 0 && !Villages.wants(id, t)) continue;
                novice = Math.min(novice, (int) Math.round(s.payLevel * UNIT * w.factor() * skillCurve(0)));
            }
            s.lowest = Math.max(s.floor, novice == Integer.MAX_VALUE ? s.floor : novice);
            s.cap = CAP_TIMES * s.lowest;
            for (Worth w : made) {
                int rate = bound(s, s.payLevel * UNIT * w.factor() * JOURNEYMAN_HAND);
                s.worth.put(w.post().key, new Worth(w.post(), w.have(), w.target(), w.perHand(), w.service(), w.value(), w.scarcity(),
                    w.difficulty(), w.factor(), rate));
            }
            // The day's bill, as payday will pay it.
            int bill = 0;
            for (AssistantEntity a : folk) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.stationTask() == StationTask.NONE) continue;
                int earned = payOf(s, f).total();
                bill += paidAt(s, id, earned + FolkSkills.haggled(f, earned));
            }
            s.bill = bill;
            s.income = st.income < 0 ? -1 : (int) Math.round(st.income);
            return s;
        } finally {
            BUILDING.remove(id);
            FARM_GUESS.remove(id);
        }
    }

    /** A wage between the living wage and the top of the scale. */
    static int bound(Scale s, double raw) {
        return Math.max(s.floor, Math.min(s.cap, (int) Math.round(raw)));
    }

    /** A wage at the leader's rate (Leader.payRate), never under the living wage. */
    static int paidAt(Scale s, @Nullable UUID id, int wage) {
        if (wage <= 0) return 0;
        return Math.max(s.floor, (int) Math.round(wage * Leader.payRate(id) / 100.0));
    }

    /** [econ-wages] A wage as payday pays it: at the leader's rate, never under the living wage (Market.payWages). */
    public static int atLeadersRate(@Nullable UUID village, int wage) {
        return paidAt(scale(null, village), village, wage);
    }

    // ------------------------------------------------------------------ a folk's own hand

    /** Its level on a smooth curve: four fifths of the rate new to it, the rate at five, half as much again a master. */
    public static double skillCurve(int level) {
        return 0.80 + 0.70 * (1.0 - Math.exp(-Math.max(0, level) / 14.0));
    }

    /** Does a maker's mark go on what this job makes (Craftsmanship)? */
    static boolean marked(Post p) {
        return p.role == ShopRoles.Role.CRAFTER || p.trade == StationTask.SMITH || p.trade == StationTask.TAILOR
            || p.trade == StationTask.ENCHANT;
    }

    /** Its own hand at the job: its level, its maker's mark, the knacks of the trade it chose. */
    static double skill(VillageFolkEntity f, Post p, int level) {
        double k = skillCurve(level);
        if (marked(p)) {
            k *= switch (Craftsmanship.grade(level)) {
                case GOOD -> 1.03;
                case FINE -> 1.06;
                case MASTER -> 1.10;
                default -> 1.0;
            };
        }
        int knacks = p.trade == null ? 0 : JobMarket.knacks(f, p.trade);
        k *= 1.0 + 0.04 * Math.min(2, knacks);
        return Math.min(1.65, k);
    }

    /** What its work earns today, part by part (Wealth.earned): its job's worth, the teaching, the elder's coin, the day's work. */
    public record Pay(Post post, int level, double skill, double raw, int worthPart, boolean floored, boolean capped, int teaching,
                      int elder, int deeds) {
        public int total() {
            return worthPart + teaching + elder + deeds;
        }
    }

    static Pay payOf(Scale s, VillageFolkEntity f) {
        Post p = postOf(f);
        int lv = f.veteranLevel();
        Worth w = s.worth.get(p.key);
        double k = skill(f, p, lv);
        double raw = s.payLevel * UNIT * (w == null ? 1.0 : w.factor()) * k;
        int r = (int) Math.round(raw);
        int part = bound(s, raw);
        int teaching = 0;
        if (School.pay(f) > 0) {
            Worth tw = s.worth.get(TEACH);
            teaching = Math.max(1, (int) Math.round(s.payLevel * UNIT * (tw == null ? 1.7 : tw.factor())
                * skillCurve(School.bestLevel(f)) * TEACHING_SHARE));
        }
        return new Pay(p, lv, k, raw, part, r < s.floor, r > s.cap, teaching, f.isElder() ? 1 : 0, Wealth.bonus(f));
    }

    /** What its work earns today, part by part. */
    public static Pay payOf(VillageFolkEntity f) {
        return payOf(scale(f), f);
    }

    // ------------------------------------------------------------------ rates

    /** [econ-wages] A trade's posted rate in this village today: a journeyman's day (Wealth.tradeWage). */
    public static int rate(StationTask t, @Nullable UUID village) {
        if (t == StationTask.NONE) return 0;
        if (t == StationTask.FARM && village != null) {
            Integer guess = FARM_GUESS.get(village);                     // the rents asked while the scale is drawn up
            if (guess != null) return guess;
        }
        Worth w = scale(null, village).worth.get(wantedPost(t, village).key);
        return w == null ? 1 : w.rate();
    }

    /** What a hand of this level would be paid at this job here today (before the elder's coin and a hard day). */
    static int rateAt(Scale s, Post p, VillageFolkEntity f, int level) {
        Worth w = s.worth.get(p.key);
        return bound(s, s.payLevel * UNIT * (w == null ? 1.0 : w.factor()) * skill(f, p, level));
    }

    /** What this folk would be paid at that job here today, at its own level at its trade (before the elder's coin and a hard day). */
    public static int payAs(VillageFolkEntity f, Post p) {
        return rateAt(scale(f), p, f, p.trade == null ? School.bestLevel(f) : f.tradeLevel(p.trade));
    }

    /**
     * [econ-wages] What a town's notice offers (JobMarket.offer): the job's worth for a hand of the experience the
     * notice asks for, at the leader's rate. A short-handed trade's notice offers more, as its hands are paid more.
     */
    public static int offer(@Nullable StationTask t, UUID town) {
        Scale s = scale(null, town);
        Post p = t == null || t == StationTask.NONE ? postFor(StationTask.FARM, null) : wantedPost(t, town);
        int lv = t == null ? 0 : JobMarket.wantsLevel(t, town);
        Worth w = s.worth.get(p.key);
        return paidAt(s, town, bound(s, s.payLevel * UNIT * (w == null ? 1.0 : w.factor()) * skillCurve(lv)));
    }

    /**
     * [econ-wages] The trades a hand might go to, the best paid for it first (its own level at each): a folk weighing
     * a change of trade looks at the pay. Ties keep the order they came in (the shortest of hands first).
     */
    public static List<StationTask> byPay(VillageFolkEntity f, List<StationTask> trades) {
        if (trades.size() < 2) return trades;
        Scale s = scale(f);
        Map<StationTask, Integer> pay = new EnumMap<>(StationTask.class);
        for (StationTask t : trades) pay.put(t, rateAt(s, wantedPost(t, f.ownerId()), f, f.tradeLevel(t)));
        List<StationTask> out = new ArrayList<>(trades);
        out.sort((a, b) -> Integer.compare(pay.get(b), pay.get(a)));
        return out;
    }

    /** [econ-wages] A hand gave up one trade for another the town was short of: if it pays better, it says so. */
    public static void tookUp(VillageFolkEntity f, StationTask from, StationTask to) {
        if (f.ownerId() == null || from == to || to == StationTask.NONE) return;
        Scale s = scale(f);
        int was = from == StationTask.NONE ? 0 : rateAt(s, postFor(from, null), f, f.tradeLevel(from));
        int now = rateAt(s, wantedPost(to, f.ownerId()), f, f.tradeLevel(to));
        if (now <= was) return;
        Worth w = s.worth.get(wantedPost(to, f.ownerId()).key);
        boolean scarce = w != null && w.scarcity() >= 1.08;
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(),
            (scarce ? "They're short of hands at " : "There's better pay at ") + at(to) + " — " + now + " a day against my " + was
                + ". I'll take it.",
            capital(at(to)) + " pay " + now + " a day" + (scarce ? " while they're short" : "") + ". Off I go."));
    }

    // ------------------------------------------------------------------ the morning

    /**
     * [econ-wages] The morning's reckoning (Market.tick, before the traders and the wages): yesterday's books into
     * each trade's value, the hands against the town's shape into its scarcity, the money that came in against the
     * bill into what the treasury can afford; then the day's scale, and any rise told. Once a day.
     */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        State st = state(v.id());
        if (st.day == day) return;                       // (a clock set back counts as a new morning too)
        advance(level, v, day, st, null);
    }

    /** The morning's reckoning now, whatever the hour and whether it was done today (/village wages reckon). */
    public static Scale reckonNow(ServerLevel level, Villages.Village v) {
        return advance(level, v, level.getDayTime() / 24000L, state(v.id()), null);
    }

    /** Tests: a morning's reckoning now, as on this day, with this much coming in (null: what the books say). */
    public static Scale morningForTests(ServerLevel level, Villages.Village v, long day, @Nullable Integer income) {
        return advance(level, v, day, state(v.id()), income);
    }

    private static Scale advance(ServerLevel level, Villages.Village v, long day, State st, @Nullable Integer incomeToday) {
        UUID id = v.id();
        List<AssistantEntity> folk = Villages.folkOf(id);
        Map<StationTask, Integer> hands = new EnumMap<>(StationTask.class);
        int makers = 0;                                          // at the shop: its keeper and the hands at its bench
        for (AssistantEntity a : folk) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.stationTask() == StationTask.NONE) continue;
            hands.merge(f.stationTask(), 1, Integer::sum);
            if (f.stationTask() == StationTask.SHOP && postOf(f).makes > 0) makers++;
        }
        if (makers > 0) hands.put(StationTask.SHOP, makers);
        // Value: what a hand of each trade brought in yesterday, at today's prices here.
        Economy.Day books = Economy.yesterdayBooks(id);
        if (books != null) {
            Map<StationTask, Double> out = outputYesterday(level, id);
            double sum = 0;
            int producing = 0;
            for (Map.Entry<StationTask, Integer> e : hands.entrySet()) {
                if (prior(e.getKey()) < 0.5) continue;              // the services' own few drops do not set the average
                sum += out.getOrDefault(e.getKey(), 0.0);
                producing += e.getValue();
            }
            if (producing > 0 && sum > 0) {
                double raw = sum / producing;
                st.ref = Math.max(1.0, st.ref <= 0 ? raw : st.ref + SMOOTH * (raw - st.ref));
            }
            double ref = st.ref > 0 ? st.ref : DEFAULT_REF;
            if (sum > 0) {
                for (Map.Entry<StationTask, Integer> e : hands.entrySet()) {
                    StationTask t = e.getKey();
                    double raw = out.getOrDefault(t, 0.0) / Math.max(1, e.getValue());
                    double was = st.val(t, ref);
                    double[] a = st.trades.computeIfAbsent(t.name(), k -> new double[]{ -1, 1.0 });
                    a[0] = Math.max(0.0, was + SMOOTH * (raw - was));
                }
            }
        }
        // Scarcity: only on a full view of the town (the shape is judged from who is loaded: JobMarket.post).
        if (Villages.loadedCount(id) * 5 >= Villages.headcount(id) * 4) {
            List<JobMarket.Opening> notices = JobMarket.openings(id);
            for (StationTask t : StationTask.values()) {
                if (t == StationTask.NONE) continue;
                double raw = scarcityToday(id, t, folk, notices, day);
                double[] a = st.trades.computeIfAbsent(t.name(), k -> new double[]{ -1, 1.0 });
                double move = clamp(SMOOTH_SCARCE * (raw - a[1]), -SCARCE_STEP, SCARCE_STEP);
                a[1] = clamp(a[1] + move, SCARCE_LO, SCARCE_HI);
            }
        }
        // What came in: the day's money in, smoothed.
        Integer in = incomeToday;
        if (in == null && books != null) in = books.takings + books.sold + books.tax + books.tithe + books.rent + books.houses + books.town;
        if (in != null && (in > 0 || st.income >= 0)) st.income = st.income < 0 ? in : st.income + SMOOTH * (in - st.income);
        // What the treasury can afford: the bill eased toward its share of what comes in.
        if (st.income > 0) {
            Scale now = build(level, id, day, st, st.afford);
            if (now.bill > 0) {
                double want = st.afford * MARGIN * st.income / now.bill;
                int spare = Ledger.coins(id) - Market.saved(id, level.getGameTime());
                if (spare >= 7 * now.bill) want *= 1.05;               // a week's wages put by: it can afford a little more
                else if (spare < now.bill) want *= 0.95;               // not a day's: a little less
                want = clamp(want, AFFORD_LO, AFFORD_HI);
                st.afford = clamp(st.afford + clamp(SMOOTH_AFFORD * (want - st.afford), -AFFORD_STEP, AFFORD_STEP), AFFORD_LO, AFFORD_HI);
            }
        }
        Scale s = build(level, id, day, st, st.afford);
        tell(level, id, day, s, st);
        for (Worth w : s.all()) st.rates.put(w.post().key, w.rate());
        st.day = day;
        save(id, st);
        SCALES.put(id, s);
        LOG.info("[MCA-WAGES] {} day {}: pay level {} (afford {}), living wage {} (cost {}), lowest {}, top {}, bill {} against {} coming in",
            Villages.name(id), day, fmt(s.payLevel), fmt(s.afford), s.floor, fmt(s.costOfLiving), s.lowest, s.cap, s.bill, s.income);
        return s;
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /**
     * How hard the trade is to fill today: the share it should have against the hands it has, more for a notice
     * nobody has answered, a little more for a skilled trade few here have the skill for; less for a trade with
     * nowhere to work at it.
     */
    static double scarcityToday(UUID id, StationTask t, List<AssistantEntity> folk, List<JobMarket.Opening> notices, long day) {
        int have = 0, skilled = 0;
        for (AssistantEntity a : folk) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            if (f.stationTask() == t) have++;
            if (f.tradeLevel(t) >= 10) skilled++;
        }
        if (have > 0 && !Villages.craftReady(id, t)) return 0.8;
        double target = have - Villages.share(id, t);
        double raw = Math.sqrt((target + 1.0) / (have + 1.0));
        for (JobMarket.Opening o : notices) {
            if (o.state() != JobMarket.State.OPEN || o.task() != t) continue;
            raw *= 1.0 + 0.03 * Math.min(5, Math.max(0, day - o.posted));
            break;
        }
        if (postFor(t, ShopRoles.Role.KEEPER).learned >= 2 && target >= 0.75 && skilled < Math.max(1, Math.round(target))) raw *= 1.08;
        if (t == StationTask.CAVE) raw = Math.max(raw, 1.3);    // [caves] a small, picked team: never to be had for the asking
        return clamp(raw, SCARCE_LO, SCARCE_HI);
    }

    /** What each trade made yesterday, at today's prices in the town, its own share of it (a maker's half). */
    static Map<StationTask, Double> outputYesterday(ServerLevel level, UUID id) {
        Map<StationTask, Double> out = new EnumMap<>(StationTask.class);
        Map<String, Double> priced = new HashMap<>();
        for (Map.Entry<String, Map<StationTask, Integer>> e : Economy.yesterdayMakers(id).entrySet()) {
            Double each = priced.get(e.getKey());
            if (each == null) {
                Item item = itemOf(e.getKey());
                each = item == null ? 0.0 : PriceIndex.each(level, id, item);
                priced.put(e.getKey(), each);
            }
            if (each <= 0) continue;
            for (Map.Entry<StationTask, Integer> m : e.getValue().entrySet()) {
                out.merge(m.getKey(), m.getValue() * each * own(m.getKey()), Double::sum);
            }
        }
        return out;
    }

    /** The item an entry in the books names ("oak_log", "mod:thing"), or null. */
    @Nullable
    static Item itemOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null) return null;
        Item it = BuiltInRegistries.ITEM.get(rl);
        return it == Items.AIR ? null : it;
    }

    // ------------------------------------------------------------------ the cost of living

    /** Two meals and the rent today, and how it was reckoned. */
    public record Cost(double coins, String words) {}

    /**
     * Two meals and the rent of a house at today's prices: the town's own reckoning where it has one
     * (Purchases.costOfLiving, from the shop's prices), and otherwise two loaves at the town's price for bread
     * (PriceIndex) and a plain house's rent (Homes.baseRent).
     */
    public static Cost costOfLiving(ServerLevel level, UUID village) {
        Double bought = fromPurchases(level, village);
        if (bought != null) return new Cost(bought, "two meals and the rent at today's prices");
        double loaf = PriceIndex.each(level, village, Items.BREAD);
        int rent = Homes.baseRent(village, new Homes.Home(BlockPos.ZERO, "house"));
        return new Cost(2 * loaf + rent, "two loaves at " + fmt(loaf) + " and a house's rent of " + rent);
    }

    /**
     * The town's cost of living as the buying and pricing work reckons it (Purchases.costOfLiving), or null where
     * it has nothing to go on yet.
     */
    @Nullable
    private static Double fromPurchases(ServerLevel level, UUID village) {
        double c = Purchases.costOfLiving(level, village);
        return c > 0 ? c : null;
    }

    // ------------------------------------------------------------------ telling it

    /** "the mines", "the watch": the trade as a place, and whether it takes "are". */
    static String at(StationTask t) {
        return switch (t.name()) {
            case "FARM" -> "the fields";
            case "WOOD" -> "the woods";
            case "MINE" -> "the mines";
            case "RANCH" -> "the pens";
            case "GUARD" -> "the watch";
            case "SMELT" -> "the furnaces";
            case "FISH" -> "the water";
            case "STORE" -> "the storehouse";
            case "HAUL" -> "the couriers";
            case "SMITH" -> "the smithy";
            case "TAILOR" -> "the tailor's";
            case "BEEKEEP" -> "the hives";
            case "BREW" -> "the brewery";
            case "ENCHANT" -> "the library";
            case "COOK" -> "the café";
            case "SHOP" -> "the shop";
            case "SCOUT" -> "the scouts";
            case "HUNT" -> "the hunters";
            case "BANK" -> "the bank";
            case "CAVE" -> "the caves";                // [caves]
            case "FERRY" -> "the ferry";               // [transport]
            case "FLETCHER" -> "the fletcher's";       // [fletcher]
            case "GOLEMS" -> "the golem yard";         // [golems]
            default -> "the " + JobMarket.noun(t) + "s";
        };
    }

    private static boolean plural(StationTask t) {
        return switch (t.name()) {
            case "FARM", "WOOD", "MINE", "RANCH", "SMELT", "HAUL", "BEEKEEP", "SCOUT", "HUNT" -> true;
            case "GUARD", "FISH", "STORE", "SMITH", "TAILOR", "BREW", "ENCHANT", "COOK", "SHOP", "BANK" -> false;
            default -> true;
        };
    }

    /** "the mines are two hands short", "the watch is short of hands". */
    static String shortWords(StationTask t, Worth w) {
        int n = (int) Math.round(w.shortBy());
        return at(t) + (plural(t) ? " are " : " is ") + (n >= 2 ? JobMarket.words(n) + " hands short" : "short of hands");
    }

    /** "the fields have hands to spare". */
    static String spareWords(StationTask t) {
        return at(t) + (plural(t) ? " have" : " has") + " hands to spare";
    }

    /** Who does the service, for its line: "the watch keeps folk alive", "the couriers keep the goods moving". */
    static String serviceLine(Post p) {
        if (p.trade == StationTask.GUARD) return "the watch " + p.serviceWords;
        if (p.trade == StationTask.HAUL || p.trade == StationTask.SCOUT) return at(p.trade) + " " + p.serviceWords;   // they keep, they map
        return "the " + p.noun() + " " + p.serviceWords;
    }

    /** Its own hand, in a few words: "a skilled miner", "a master smith", "new to the work". */
    static String handWords(VillageFolkEntity f, Pay pay, boolean me) {
        int lv = pay.level();
        String noun = pay.post().noun();
        String knack = pay.post().trade != null && JobMarket.knacks(f, pay.post().trade) > 0 ? " with a knack for it" : "";
        String what = lv >= 25 ? "a master " + noun : lv >= 10 ? "a skilled " + noun : lv >= 3 ? "a steady hand" : "new to the work";
        return (me ? "I'm " : "it is ") + what + knack;
    }

    /**
     * Why it is paid what it is, in plain words: "the mines are two hands short, hard and dangerous work, and it is a
     * skilled miner". In its own words with {@code me}: "... and I'm a skilled miner".
     */
    public static String why(VillageFolkEntity f, boolean me) {
        if (f.isBaby() || f.stationTask() == StationTask.NONE) return "";
        Scale s = scale(f);
        Pay pay = payOf(s, f);
        Post p = pay.post();
        Worth w = s.worth.get(p.key);
        List<String> r = new ArrayList<>();
        if (w != null && p.trade != null) {
            if (w.scarcity() >= 1.08 && w.shortBy() >= 0.5) r.add(shortWords(p.trade, w));
            else if (w.scarcity() <= 0.92) r.add(spareWords(p.trade));
        }
        if (!p.serviceWords.isEmpty()) r.add(serviceLine(p));
        else if (w != null && w.value() >= 1.12) r.add("a hand " + (p.trade == null ? "there" : "at " + at(p.trade)) + " brings in "
            + fmt(w.perHand()) + " a day");
        else if (w != null && w.value() <= 0.85) r.add("the work brings in little");
        if (p.hard >= 2 || p.learned >= 3) r.add(p.hardWords);
        r.add(handWords(f, pay, me));
        String out = join(r);
        if (pay.floored()) out = "the living wage (" + s.costWords + " come to " + fmt(s.costOfLiving) + "), more than the work alone: " + out;
        if (pay.capped()) out += "; held to the top of the scale, " + s.cap;
        return out;
    }

    /** [econ-wages] Its card's wage line (FolkTalk.card): "Paid 9 a day: the mines are two hands short ... skilled miner." */
    public static String cardLine(VillageFolkEntity f) {
        if (f.isBaby() || f.stationTask() == StationTask.NONE || f.ownerId() == null) return "";
        int w = Wealth.wage(f);
        return "Paid " + w + (w == 1 ? " coin" : " coins") + " a day: " + why(f, false) + ".";
    }

    /** How the worth part of its wage is made up, in figures: "6 as a miner (value ×1.00, scarcity ×1.27, ...)". */
    public static String figures(VillageFolkEntity f) {
        Scale s = scale(f);
        Pay pay = payOf(s, f);
        Worth w = s.worth.get(pay.post().key);
        StringBuilder sb = new StringBuilder();
        sb.append(pay.worthPart()).append(" as ").append(JobMarket.a(pay.post().noun()));
        if (w != null) {
            sb.append(" (").append(fmt(s.payLevel)).append(" pay level × ").append(fmt(UNIT)).append(" × value ").append(fmt(w.value()))
                .append(" × scarcity ").append(fmt(w.scarcity())).append(" × difficulty ").append(fmt(w.difficulty()))
                .append(" × its hand ").append(fmt(pay.skill())).append(" at level ").append(pay.level()).append(" = ").append(fmt(pay.raw()));
            if (pay.floored()) sb.append(", made up to the living wage of ").append(s.floor);
            if (pay.capped()) sb.append(", held to the top of the scale, ").append(s.cap);
            sb.append(")");
        }
        return sb.toString();
    }

    /** One job's line on the wages page: "Miner 6 a day (×2.1): value ×1.00 (9.2 a hand), scarcity ×1.27 (2 short), ...". */
    public static String jobLine(Scale s, Worth w) {
        Post p = w.post();
        StringBuilder sb = new StringBuilder();
        sb.append(p.title).append(' ').append(w.rate()).append(" a day (worth ×").append(fmt(UNIT * w.factor())).append("): value ×")
            .append(fmt(w.value()));
        List<String> v = new ArrayList<>();
        if (w.perHand() > 0.05) v.add(fmt(w.perHand()) + " a hand a day" + (p.trade != null && own(p.trade) < 1 ? ", its half of what it made" : ""));
        if (!p.serviceWords.isEmpty()) v.add(serviceLine(p));
        if (!v.isEmpty()) sb.append(" (").append(String.join("; ", v)).append(")");
        sb.append(", scarcity ×").append(fmt(w.scarcity()));
        if (p.trade != null) {
            double by = w.shortBy();
            sb.append(" (").append(w.have()).append(" at it, ").append(fmt1(Math.max(0, w.target()))).append(" wanted")
                .append(by >= 0.5 ? ", short" : by <= -0.5 ? ", over" : "").append(")");
        }
        sb.append(", difficulty ×").append(fmt(w.difficulty()));
        if (!p.hardWords.isEmpty()) sb.append(" (").append(p.hardWords).append(")");
        return sb.toString();
    }

    /** The jobs worth showing for a village: those it has hands at or wants, the shop's when it has a shop, the teacher's with a school. */
    public static List<Worth> shown(Scale s) {
        List<Worth> out = new ArrayList<>();
        UUID id = s.village;
        for (Worth w : s.all()) {
            Post p = w.post();
            if (p.trade == null) {
                if (id != null && School.stands(id)) out.add(w);
            } else if (p.trade == StationTask.SHOP) {
                if (w.have() > 0 || id != null && Workshop.stands(id)) out.add(w);
            } else if (w.have() > 0 || id != null && Villages.wants(id, p.trade)) {
                out.add(w);
            }
        }
        out.sort((a, b) -> a.rate() != b.rate() ? Integer.compare(b.rate(), a.rate()) : Double.compare(b.factor(), a.factor()));
        return out;
    }

    /** The head of the wages page: the pay level, the bounds, the bill against what comes in. */
    public static List<String> summary(Scale s) {
        List<String> out = new ArrayList<>();
        UUID id = s.village;
        int afford = (int) Math.round(s.afford * 100);
        int leader = Leader.payRate(id);
        out.add("Pay level ×" + fmt(s.payLevel) + ": " + Wealth.standingWords((int) Math.round(s.standing * 10))
            + (afford == 100 ? ", in full" : afford < 100 ? ", at " + afford + " in the hundred of it, all the treasury can afford"
                : ", and " + (afford - 100) + " in the hundred over it while the treasury is well off")
            + (leader != 100 ? "; the leader pays " + leader + " in the hundred of the scale" : "") + ".");
        out.add("Lowest wage " + s.lowest + " a day: the living wage is " + s.floor + " (" + s.costWords + " come to " + fmt(s.costOfLiving)
            + "). Top pay is held to " + s.cap + ", " + JobMarket.words(CAP_TIMES) + " times the lowest.");
        if (s.bill >= 0) {
            out.add("The day's wages come to " + s.bill + (s.income >= 0 ? " against " + s.income + " a day coming in"
                + (s.bill <= s.income ? " (within it)" : " (over it: the rate eases down each morning till it fits)")
                : "; nothing booked coming in yet") + ".");
        }
        return out;
    }

    /** The wages page's account of every job's worth (Wealth.wagesPage). */
    public static List<String> pageLines(ServerLevel level, UUID id) {
        Scale s = scale(level, id);
        List<String> out = new ArrayList<>(summary(s));
        out.add("");
        out.add("What every job is worth here: a journeyman's day (level " + JOURNEYMAN + "). A folk's own hand moves it, from ×"
            + fmt(skillCurve(0)) + " new to the work to ×" + fmt(skillCurve(40)) + " for a master, a little more for a maker's good marks and"
            + " for the knacks of its trade. Value is what a hand brings in at today's prices against the "
            + fmt(s.ref) + " an average hand does" + (s.fromBooks ? "" : " (a first guess: no books yet)")
            + ", or the service it does; scarcity, the hands it has against the share wanted; difficulty, how hard it is and the skill it takes.");
        for (Worth w : shown(s)) out.add(jobLine(s, w) + ".");
        String news = newsLine(id, s.day);
        if (news != null) out.add("News: " + news);
        return out;
    }

    // ------------------------------------------------------------------ the news

    /** The morning's rises, for a trade short of hands: the chronicle, the morning assembly and the gazette. */
    private static void tell(ServerLevel level, UUID id, long day, Scale s, State st) {
        List<String> lines = new ArrayList<>();
        for (Worth w : s.all()) {
            Post p = w.post();
            if (p.trade == null || p.role != null && p.role != ShopRoles.Role.ASSISTANT) continue;
            // Against last morning's rate; on the first morning, against what the job would pay with hands enough.
            Integer was = st.rates.get(p.key);
            if (was == null) was = bound(s, s.payLevel * UNIT * w.value() * w.difficulty() * JOURNEYMAN_HAND);
            if (w.rate() <= was || w.scarcity() < 1.08 || w.shortBy() < 0.75) continue;
            String who = p.role != null ? "the shop's assistants'" : JobMarket.noun(p.trade) + "s'";
            String line = who + " pay is up to " + w.rate() + " a day, from " + was + ": " + shortWords(p.trade, w);
            lines.add(capital(line) + ".");
            Villages.tell(id, day, (p.role != null ? "the shop's assistants'" : JobMarket.noun(p.trade) + "s'") + " pay rose to " + w.rate()
                + " a day: " + shortWords(p.trade, w));
            Market.assemblyNews(id, capital(shortWords(p.trade, w)) + ", so " + JobMarket.a(p.noun()) + "'s pay is up to "
                + JobMarket.words(w.rate()) + " a day. Anybody with the hands for it, the notice board's the place.");
            if (lines.size() >= 2) break;
        }
        if (lines.isEmpty()) return;
        Ledger.note(id, "wg.news", day + "|" + String.join("~", lines).replace('|', '/'));
    }

    /** The latest rises told, if they were told within so many days of this one; or null. */
    @Nullable
    static String newsLine(UUID id, long day, int within) {
        String n = Ledger.note(id, "wg.news");
        if (n == null || n.isEmpty()) return null;
        int bar = n.indexOf('|');
        if (bar < 0) return null;
        long told = parseLong(n.substring(0, bar), -10);
        if (day - told > within || day < told) return null;
        return n.substring(bar + 1).replace("~", " ");
    }

    @Nullable
    static String newsLine(UUID id, long day) {
        return newsLine(id, day, 1);
    }

    /**
     * [econ-wages] The gazette's wages column: the rises told this morning, or null with none. (Yesterday's are in
     * the chronicle, and so under "Also" in today's issue already.)
     */
    @Nullable
    public static String gazette(UUID id, long day) {
        String n = newsLine(id, day, 0);
        return n == null ? null : "§lWages§r\n" + n;
    }

    /** Tests: the rises told lately, or "". */
    public static String newsForTests(UUID id, long day) {
        String n = newsLine(id, day);
        return n == null ? "" : n;
    }

    // ------------------------------------------------------------------ the town's books

    /**
     * [econ-wages] Into the town's books (Annals.snapshot): the pay scale for the Jobs page, each trade's worth for
     * the mouse over its row, and the teacher's row at what teaching really pays.
     */
    public static void intoBooks(ServerLevel level, UUID id, CompoundTag out) {
        Scale s = scale(level, id);
        CompoundTag t = new CompoundTag();
        ListTag head = new ListTag();
        for (String line : summary(s)) head.add(StringTag.valueOf(line));
        t.put("summary", head);
        CompoundTag rows = new CompoundTag();
        for (Worth w : shown(s)) {
            String key = w.post().trade == null ? TEACH : w.post().trade.name();
            String line = jobLine(s, w);
            rows.putString(key, rows.contains(key) ? rows.getString(key) + "\n" + line : line);
        }
        t.put("rows", rows);
        t.putInt("lowest", s.lowest);
        t.putInt("cap", s.cap);
        t.putInt("bill", s.bill);
        t.putInt("income", s.income);
        t.putString("cost", fmt(s.costOfLiving));
        out.put("payscale", t);
        // The teacher's row: what its mornings at the school pay on top of its trade.
        ListTag jobs = out.getList("jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < jobs.size(); i++) {
            CompoundTag row = jobs.getCompound(i);
            if (!row.getString("id").equals(TEACH)) continue;
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && School.isTeacher(f) && School.pay(f) > 0) {
                    int pay = payOf(s, f).teaching();
                    row.putInt("wage", pay);
                    row.putInt("wage_bill", pay);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double parse(@Nullable String s, double or) {
        if (s == null || s.isEmpty()) return or;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return or;
        }
    }

    private static long parseLong(@Nullable String s, long or) {
        if (s == null || s.isEmpty()) return or;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return or;
        }
    }

    static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    static String fmt1(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    private static String fmt3(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    /** "a, b and c". */
    static String join(List<String> parts) {
        if (parts.isEmpty()) return "";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
