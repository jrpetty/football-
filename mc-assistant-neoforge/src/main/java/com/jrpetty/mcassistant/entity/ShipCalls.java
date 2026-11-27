package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [trade-world] The merchant ships from over the sea (ForeignShips' lands) calling at a town's harbour (Harbours).
 *
 * <ul>
 * <li><b>When.</b> The first a day or two after the pier is finished; then one every week or so: two days sooner while
 *     the lighthouse burns of a night, a day or two sooner as the lands come to think well of the town (a visit that
 *     traded leaves its land the warmer), never oftener than every third day. A ship due in a thunderstorm is held in
 *     port a day; one due with the harbour iced over waits for the thaw; while the town is at war no merchant sails into
 *     it at all; a land whose people were killed in the town's harbour sends no ship for a long while.</li>
 * <li><b>Coming in.</b> A sail is sighted far out down the harbour's lane and the hull comes in a block at a time (ShipHull)
 *     to lie alongside the pier. Anything in its way (ice, a boat, a swimmer) holds it; held a long while, it comes
 *     alongside if its berth is clear, or else lies at anchor where it is and its people come ashore in the ship's boat.
 *     With nobody near to see (the ground asleep) it is simply there when the harbour is next looked at.</li>
 * <li><b>In harbour</b>, a day or two: the gangplank down, the office's bell rung, the harbour dues paid out of the ship's
 *     purse (into the treasury, a quarter to the harbourmaster), and its captain and a sailor ashore in their land's
 *     colours (Seafarers). The captain trades from the quay: the town's hands buy what it needs off the ship out of the
 *     treasury and carry it to the stores, and load the town's surplus aboard for coin; you can trade with the captain too,
 *     at the trading table (ShipTrade). Every price is the town's own (PriceIndex), a far land's goods at a markup.</li>
 * <li><b>Away.</b> The morning it is due to sail, its people go aboard, the gangplank comes up, it turns about and sails
 *     out down the lane, and is gone. The chronicle tells it all; the Harbour page keeps the ships due, in harbour and gone.</li>
 * </ul>
 * A ship's cargo and its purse come from abroad, as the game's wandering trader's do: the one thing in a harbour town that
 * is not out of somebody's pack, chest or stores. It is finite (a few lots a visit, counted, gone when sold), and what it
 * buys goes away with it. The ship is written in the town's notes at every change, so a restart finds it where it lay.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class ShipCalls {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private ShipCalls() {}

    static final String SHIP = "tw.ship", NEXT = "tw.next", VISITS = "tw.visits", DUES = "tw.dues", LOGBOOK = "tw.log",
        REGARD = "tw.regard.", HELD = "tw.held", WAR = "tw.war";
    /** Harbour dues: so much a ship, and a coin more for every two lots in its hold. */
    static final int DUES_BASE = 4;
    /** A ship moves a block every so many ticks. */
    static final int STEP_TICKS = 10;
    /** How long a ship may be held up on its way before it comes alongside (or anchors), and ashore before it sails. */
    static final long HELD_UP = 600L, WAIT_ABOARD = 1200L;
    /** The days between ships, at the most and the least. */
    static final int USUAL_GAP = 7, LEAST_GAP = 3;

    /** One lot in a ship's hold: what, how many there are, how many to a lot, its price a lot, and in words. */
    static final class Lot {
        String item = "";
        int count, lot, price;
        String words = "";
        /** "ware", "disc", "trim", or "treatise:topic". */
        String kind = "ware";

        Item item() {
            return BuiltInRegistries.ITEM.get(ResourceLocation.parse(item));
        }

        ItemStack one(@Nullable ForeignShips.Land land) {
            if (kind.startsWith("treatise:") && land != null) return ForeignShips.treatiseOn(land, kind.substring(9));
            return new ItemStack(item(), lot);
        }

        int lotsLeft() {
            return lot <= 0 ? 0 : count / lot;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("i", item);
            t.putInt("c", count);
            t.putInt("l", lot);
            t.putInt("p", price);
            t.putString("w", words);
            t.putString("k", kind);
            return t;
        }

        static Lot load(CompoundTag t) {
            Lot o = new Lot();
            o.item = t.getString("i");
            o.count = t.getInt("c");
            o.lot = t.getInt("l");
            o.price = t.getInt("p");
            o.words = t.getString("w");
            o.kind = t.getString("k").isEmpty() ? "ware" : t.getString("k");
            return o;
        }
    }

    /** A ship at (or coming to, or leaving) a town's harbour. */
    static final class Ship {
        String land = "", name = "";
        int visit;
        /** "in" (sailing in), "docked", "out" (sailing away). */
        String stage = "in";
        int pos = ShipHull.OFFING;
        boolean bowIn = true, drawn;
        long sighted = -1, arrived = -1, leave = -1;
        int purse, purse0;
        final List<Lot> hold = new ArrayList<>();
        /** What it takes home: item id to count. */
        final Map<String, Integer> bought = new LinkedHashMap<>();
        int dues, townPaid, townGot, playerPaid, playerGot, lotsSold;
        final List<UUID> crew = new ArrayList<>();
        /** Lying at anchor off the pier, held from its berth. */
        boolean anchored;
        /** Leaving: its people called aboard, since (game time). */
        long called = -1;
        /** The day's dealings with the town: which day, and how many bought and sold. */
        long dealt = -1;
        int buys, sells;
        // Not saved: what holds it up.
        long blockedSince = -1, waitedSince = -1;

        @Nullable ForeignShips.Land land() { return ForeignShips.land(land); }

        Block flag() {
            ForeignShips.Land l = land();
            return l == null ? Blocks.WHITE_WOOL : l.flag();
        }

        boolean docked() { return "docked".equals(stage); }

        String save() {
            CompoundTag t = new CompoundTag();
            t.putString("land", land);
            t.putString("name", name);
            t.putInt("visit", visit);
            t.putString("stage", stage);
            t.putInt("pos", pos);
            t.putBoolean("bowIn", bowIn);
            t.putBoolean("drawn", drawn);
            t.putLong("sighted", sighted);
            t.putLong("arrived", arrived);
            t.putLong("leave", leave);
            t.putInt("purse", purse);
            t.putInt("purse0", purse0);
            ListTag h = new ListTag();
            for (Lot l : hold) h.add(l.save());
            t.put("hold", h);
            CompoundTag b = new CompoundTag();
            for (Map.Entry<String, Integer> e : bought.entrySet()) b.putInt(e.getKey(), e.getValue());
            t.put("bought", b);
            t.putInt("dues", dues);
            t.putInt("townPaid", townPaid);
            t.putInt("townGot", townGot);
            t.putInt("playerPaid", playerPaid);
            t.putInt("playerGot", playerGot);
            t.putInt("lotsSold", lotsSold);
            ListTag c = new ListTag();
            for (UUID u : crew) c.add(net.minecraft.nbt.StringTag.valueOf(u.toString()));
            t.put("crew", c);
            t.putBoolean("anchored", anchored);
            t.putLong("called", called);
            t.putLong("dealt", dealt);
            t.putInt("buys", buys);
            t.putInt("sells", sells);
            return t.toString();
        }

        @Nullable
        static Ship load(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            try {
                CompoundTag t = TagParser.parseTag(s);
                Ship o = new Ship();
                o.land = t.getString("land");
                o.name = t.getString("name");
                o.visit = t.getInt("visit");
                o.stage = t.getString("stage");
                o.pos = t.getInt("pos");
                o.bowIn = t.getBoolean("bowIn");
                o.drawn = t.getBoolean("drawn");
                o.sighted = t.getLong("sighted");
                o.arrived = t.getLong("arrived");
                o.leave = t.getLong("leave");
                o.purse = t.getInt("purse");
                o.purse0 = t.getInt("purse0");
                for (Tag x : t.getList("hold", Tag.TAG_COMPOUND)) o.hold.add(Lot.load((CompoundTag) x));
                CompoundTag b = t.getCompound("bought");
                for (String k : b.getAllKeys()) o.bought.put(k, b.getInt(k));
                o.dues = t.getInt("dues");
                o.townPaid = t.getInt("townPaid");
                o.townGot = t.getInt("townGot");
                o.playerPaid = t.getInt("playerPaid");
                o.playerGot = t.getInt("playerGot");
                o.lotsSold = t.getInt("lotsSold");
                for (Tag x : t.getList("crew", Tag.TAG_STRING)) {
                    try {
                        o.crew.add(UUID.fromString(x.getAsString()));
                    } catch (IllegalArgumentException ignored) { }
                }
                o.anchored = t.getBoolean("anchored");
                o.called = t.getLong("called");
                o.dealt = t.getLong("dealt");
                o.buys = t.getInt("buys");
                o.sells = t.getInt("sells");
                return o.land() == null ? null : o;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** The ships about, by the town whose harbour they are at (read from the notes the first time a town is looked at). */
    private static final Map<UUID, Ship> SHIPS = new ConcurrentHashMap<>();

    static void resetForTests() {
        SHIPS.clear();
    }

    @Nullable
    static Ship ship(UUID village) {
        Ship s = SHIPS.get(village);
        if (s != null) return s;
        s = Ship.load(Ledger.note(village, SHIP));
        if (s != null) SHIPS.put(village, s);
        return s;
    }

    static void save(UUID village, Ship s) {
        Ledger.note(village, SHIP, s.save());
    }

    // ------------------------------------------------------------------ when ships come

    /** The pier is finished: the first ship is due a day or two on. */
    static void harbourOpened(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        if (Harbours.parse(Ledger.note(v.id(), NEXT)) < 0) Ledger.note(v.id(), NEXT, Long.toString(day + 1));
    }

    /** What a land thinks of the town: up a point a visit that traded (ten at most), down hard for its people killed here. */
    static int regard(UUID village, ForeignShips.Land l) {
        String raw = Ledger.note(village, REGARD + l.id());
        if (raw == null || raw.isEmpty()) return 0;
        try {
            return Math.max(-50, Math.min(10, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void regard(UUID village, ForeignShips.Land l, int by) {
        Ledger.note(village, REGARD + l.id(), Integer.toString(Math.max(-50, Math.min(10, regard(village, l) + by))));
    }

    /** The lands' good opinion of the town, all told (what brings the ships sooner). */
    static int reputation(UUID village) {
        int n = 0;
        for (ForeignShips.Land l : ForeignShips.LANDS) n += Math.max(0, regard(village, l));
        return n;
    }

    /** The days to the next ship: a week, two days sooner with the light burning, sooner again with navigation and a good name. */
    static int gap(UUID village, long day, RandomSource r) {
        int g = USUAL_GAP;
        if (Harbours.litLately(village, day)) g -= 2;
        if (InventionWorks.openSea(village)) g--;                                // [realm] navigation known: the open-sea traders come too
        int rep = reputation(village);
        if (rep >= 6) g--;
        if (rep >= 15) g--;
        return Math.max(LEAST_GAP, g) + r.nextInt(2);
    }

    /** The land whose ship calls next: one that will come (thinks no ill of the town), the one longest away first. */
    @Nullable
    static ForeignShips.Land nextLand(UUID village) {
        int visits = (int) Math.max(0, Harbours.parse(Ledger.note(village, VISITS)));
        for (int i = 0; i < ForeignShips.LANDS.size(); i++) {
            ForeignShips.Land l = ForeignShips.LANDS.get((visits + i) % ForeignShips.LANDS.size());
            if (regard(village, l) >= 0) return l;
        }
        return null;
    }

    /**
     * The town's visit (Harbours.tick, every ten seconds): a ship due sighted (or held up, and why), a ship in harbour
     * crewed, traded with and sent on its way.
     */
    static void townTick(ServerLevel level, Villages.Village v, Harbours.Shore s) {
        UUID id = v.id();
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        Ship ship = ship(id);
        if (ship == null) {
            if (!Harbours.ready(id)) return;
            long next = Harbours.parse(Ledger.note(id, NEXT));
            if (next < 0) {
                Ledger.note(id, NEXT, Long.toString(day + 1));
                return;
            }
            if (day < next || t < 1000L || t > 7000L) return;
            String held = heldUp(level, v, s);
            if (held != null) {
                if (Harbours.parse(Ledger.note(id, HELD)) != day) {
                    Ledger.note(id, HELD, Long.toString(day));
                    Villages.tell(id, day, held);
                }
                return;
            }
            ForeignShips.Land land = nextLand(id);
            if (land == null) {
                if (Harbours.parse(Ledger.note(id, HELD)) != day) {
                    Ledger.note(id, HELD, Long.toString(day));
                    Villages.tell(id, day, "no ship came: the lands over the sea want nothing to do with the town after what was done to their people");
                }
                Ledger.note(id, NEXT, Long.toString(day + USUAL_GAP));
                return;
            }
            sight(level, v, s, land);
            return;
        }
        if (ship.docked()) inHarbour(level, v, s, ship, day, t);
    }

    /** Why the ship due today has not come, in the chronicle's words, or null if nothing holds it. */
    @Nullable
    static String heldUp(ServerLevel level, Villages.Village v, Harbours.Shore s) {
        UUID id = v.id();
        ForeignShips.Land l = nextLand(id);
        String from = l == null ? "over the sea" : "from " + l.name();
        if (!Wars.enemies(id).isEmpty()) return "no merchant ship will sail into a war: the ship " + from + " stayed away";
        if (Weather.stormy(level)) return "the ship " + from + " due today was held in port by the storm";
        BlockPos berth = s.at(ShipHull.BERTH + ShipHull.LENGTH / 2, ShipHull.MIDDLE, 0);
        if (level.isLoaded(berth) && (level.getBlockState(berth).is(Blocks.ICE) || level.getBlockState(berth).is(Blocks.PACKED_ICE))) {
            return "the harbour was iced over: the ship " + from + " waits for the thaw";
        }
        if (Raids.underAlarm(id)) return "the ship " + from + " stood off while the bell rang";
        return null;
    }

    /** A sail sighted: the ship made, its hold filled abroad, and it comes in from far out down the lane. */
    static Ship sight(ServerLevel level, Villages.Village v, Harbours.Shore s, ForeignShips.Land land) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        RandomSource r = level.getRandom();
        int visits = (int) Math.max(0, Harbours.parse(Ledger.note(id, VISITS)));
        Ship ship = new Ship();
        ship.land = land.id();
        ship.visit = visits;
        ship.name = ForeignShips.shipName(land, visits / ForeignShips.LANDS.size());
        ship.sighted = day;
        ship.purse = ship.purse0 = 40 + 10 * r.nextInt(5);           // its own coin, from abroad
        ship.hold.addAll(cargo(level, v, land, r));
        SHIPS.put(id, ship);
        save(id, ship);
        Ledger.note(id, VISITS, Integer.toString(visits + 1));
        String words = "a sail off " + Villages.name(id) + ": " + ship.name + " of " + land.name() + ", " + land.coast() + ", bound for the harbour";
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().closerThan(v.centre(), 192)) p.displayClientMessage(Component.literal(FolkTalk.cap(words) + "."), true);
        }
        LOG.info("[MCA-HARBOUR] {}: {} of {} sighted ({} lots, {} coins)", Villages.name(id), ship.name, land.name(), ship.hold.size(), ship.purse);
        return ship;
    }

    /**
     * What a ship of this land carries to this town: four to six of its land's goods, none the town's stores hold plenty
     * of already, each priced by the town's own price of it with the far land's markup (a lot never dearer than a stack
     * of coin); and now and then one rarity, a music disc, an armour trim or a treatise.
     */
    static List<Lot> cargo(ServerLevel level, Villages.Village v, ForeignShips.Land land, RandomSource r) {
        List<ForeignShips.Ware> pick = new ArrayList<>(land.goods());
        java.util.Collections.shuffle(pick, new java.util.Random(r.nextLong()));
        List<Lot> out = new ArrayList<>();
        int want = 4 + r.nextInt(3);
        for (ForeignShips.Ware w : pick) {
            if (out.size() >= want) break;
            if (Market.stock(level, v.id(), st -> st.is(w.item())) >= w.lot() * 2) continue;
            Lot l = new Lot();
            l.item = BuiltInRegistries.ITEM.getKey(w.item()).toString();
            l.lot = w.lot();
            l.words = w.words();
            double each = PriceIndex.each(level, v.id(), w.item());
            l.price = (int) Math.ceil(Math.max(0.1, each) * w.lot() * w.markup());
            while (l.price > 64 && l.lot > 1) {
                l.lot = Math.max(1, l.lot / 2);
                l.price = (int) Math.ceil(Math.max(0.1, each) * l.lot * w.markup());
            }
            l.price = Math.max(1, Math.min(64, l.price));
            l.count = l.lot * (1 + r.nextInt(3));
            out.add(l);
        }
        if (r.nextInt(100) < 35) {
            Lot rare = new Lot();
            rare.lot = 1;
            rare.count = 1;
            switch (r.nextInt(3)) {
                case 0 -> {
                    Item disc = ForeignShips.DISCS.get(r.nextInt(ForeignShips.DISCS.size()));
                    rare.item = BuiltInRegistries.ITEM.getKey(disc).toString();
                    rare.kind = "disc";
                    rare.words = "a music disc of " + land.people() + " songs";
                    rare.price = 32;
                }
                case 1 -> {
                    Item trim = ForeignShips.TRIMS.get(r.nextInt(ForeignShips.TRIMS.size()));
                    rare.item = BuiltInRegistries.ITEM.getKey(trim).toString();
                    rare.kind = "trim";
                    rare.words = "a smith's pattern for armour, " + land.people() + " work";
                    rare.price = 40;
                }
                default -> {
                    String topic = land.treatises().get(r.nextInt(land.treatises().size()));
                    rare.item = BuiltInRegistries.ITEM.getKey(Items.WRITTEN_BOOK).toString();
                    rare.kind = "treatise:" + topic;
                    rare.words = "a treatise on " + topic;
                    rare.price = 24;
                }
            }
            out.add(rare);
        }
        return out;
    }

    // ------------------------------------------------------------------ the ship's way

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % STEP_TICKS != 7 || SHIPS.isEmpty()) return;
        Guard.run("ships", () -> {
            for (Map.Entry<UUID, Ship> e : SHIPS.entrySet()) {
                Villages.Village v = Villages.get(e.getKey());
                if (v == null) continue;
                ServerLevel level = event.getServer().getLevel(v.dim());
                Harbours.Shore s = Harbours.shore(v.id());
                if (level == null || s == null) continue;
                sail(level, v, s, e.getValue());
            }
        });
    }

    /** A step of the ship's way: in to its berth, or out to sea, a block at a time. */
    static void sail(ServerLevel level, Villages.Village v, Harbours.Shore s, Ship ship) {
        UUID id = v.id();
        long now = level.getGameTime();
        switch (ship.stage) {
            case "in" -> {
                if (!ship.drawn) {
                    ShipHull.Move m = ShipHull.move(level, s, ship.flag(), false, 0, true, ship.pos, true);
                    if (m == ShipHull.Move.DONE) {
                        ship.drawn = true;
                        save(id, ship);
                    } else if (waited(ship, now, m) > WAIT_ABOARD) {
                        // Nobody to see it come in (or nowhere to show it): it is simply alongside when the harbour is looked at.
                        ship.pos = ShipHull.BERTH;
                        moored(level, v, s, ship);
                    }
                    return;
                }
                if (ship.pos <= ShipHull.BERTH) {
                    moored(level, v, s, ship);
                    return;
                }
                ShipHull.Move m = ShipHull.move(level, s, ship.flag(), true, ship.pos, true, ship.pos - 1, true);
                if (m == ShipHull.Move.DONE) {
                    ship.pos--;
                    ship.blockedSince = -1;
                    if (ship.pos % 6 == 0 || ship.pos <= ShipHull.BERTH) save(id, ship);
                    if (ship.pos <= ShipHull.BERTH) moored(level, v, s, ship);
                    return;
                }
                if (m == ShipHull.Move.BLOCKED) {
                    if (ship.blockedSince < 0) ship.blockedSince = now;
                    if (now - ship.blockedSince > HELD_UP) {
                        // Held up a long while: alongside if the berth is clear, else at anchor where it lies.
                        if (ShipHull.move(level, s, ship.flag(), true, ship.pos, true, ShipHull.BERTH, true) == ShipHull.Move.DONE) {
                            ship.pos = ShipHull.BERTH;
                        } else {
                            ship.anchored = true;
                        }
                        moored(level, v, s, ship);
                    }
                }
            }
            case "out" -> {
                if (!ship.drawn) {
                    gone(level, v, ship);
                    return;
                }
                if (ship.pos >= ShipHull.OFFING) {
                    if (ShipHull.erase(level, s, ship.flag(), ship.pos, ship.bowIn)) gone(level, v, ship);
                    return;
                }
                ShipHull.Move m = ShipHull.move(level, s, ship.flag(), true, ship.pos, ship.bowIn, ship.pos + 1, ship.bowIn);
                if (m == ShipHull.Move.DONE) {
                    ship.pos++;
                    ship.blockedSince = -1;
                    if (ship.pos % 6 == 0) save(id, ship);
                } else if (m == ShipHull.Move.BLOCKED) {
                    if (ship.blockedSince < 0) ship.blockedSince = now;
                    // Held up going out: it slips away beyond the headland, and the sea is put back where it lay.
                    if (now - ship.blockedSince > HELD_UP && ShipHull.erase(level, s, ship.flag(), ship.pos, ship.bowIn)) gone(level, v, ship);
                }
            }
            default -> {
                // In harbour: drawn at its berth when the ground there next wakes, if it was moored unseen.
                if (!ship.drawn && ShipHull.move(level, s, ship.flag(), false, 0, true, ship.pos, ship.bowIn) == ShipHull.Move.DONE) {
                    ship.drawn = true;
                    if (!ship.anchored && ship.called < 0) ShipHull.gangplank(level, s, ship.pos, ship.bowIn, true);
                    save(id, ship);
                }
            }
        }
    }

    /** How long a move has gone undone for want of ground to draw it on (or room), in ticks. */
    private static long waited(Ship ship, long now, ShipHull.Move m) {
        if (m == ShipHull.Move.DONE) {
            ship.waitedSince = -1;
            return 0;
        }
        if (ship.waitedSince < 0) ship.waitedSince = now;
        return now - ship.waitedSince;
    }

    /** Alongside (or at anchor): the gangplank down, the bell rung, the dues paid, its people ashore. */
    static void moored(ServerLevel level, Villages.Village v, Harbours.Shore s, Ship ship) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        ForeignShips.Land land = ship.land();
        ship.stage = "docked";
        ship.arrived = day;
        ship.leave = day + 1 + level.getRandom().nextInt(2);
        ship.blockedSince = -1;
        if (ship.drawn && !ship.anchored) ShipHull.gangplank(level, s, ship.pos, ship.bowIn, true);
        ringTheBell(level, id);
        // The harbour dues, out of the ship's purse: three quarters into the treasury, a quarter to the harbourmaster.
        int dues = Math.min(ship.purse, DUES_BASE + ship.hold.size() / 2);
        ship.purse -= dues;
        ship.dues = dues;
        VillageFolkEntity master = Harbours.master(level, v);
        int share = master == null ? 0 : dues / 4;
        if (master != null && share > 0) master.earn(share);
        Ledger.addCoins(id, dues - share);
        CoinCensus.flow(id, CoinCensus.Flow.VISITOR_IN, dues);          // [business-books] the ship's coin into the town
        Economy.sold(id, dues - share);
        Ledger.note(id, DUES, Long.toString(Math.max(0, Harbours.parse(Ledger.note(id, DUES))) + dues));
        List<String> goods = new ArrayList<>();
        for (Lot l : ship.hold) goods.add(l.words);
        Villages.tell(id, day, ship.name + ", a merchant ship of " + land.name() + ", " + (ship.anchored ? "anchored off the pier" : "came into harbour")
            + " with " + Merchants.list(goods) + "; it paid " + Visitors.coins(dues) + " in harbour dues");
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().closerThan(v.centre(), 192)) {
                p.displayClientMessage(Component.literal(ship.name + " of " + land.name() + " is in " + Villages.name(id)
                    + "'s harbour: talk to its captain on the quay to trade."), true);
            }
        }
        Seafarers.comeAshore(level, v, s, ship);
        save(id, ship);
        LOG.info("[MCA-HARBOUR] {}: {} of {} {} at k={} (dues {}, purse {})", Villages.name(id), ship.name, land.name(),
            ship.anchored ? "anchored" : "moored", ship.pos, dues, ship.purse);
    }

    /** The office's bell rung for a sail (or the sound of it, with the bell gone). */
    static void ringTheBell(ServerLevel level, UUID village) {
        Ledger.Building b = Villages.builtStructure(village, Harbours.OFFICE);
        if (b == null || !level.isLoaded(b.anchor())) return;
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p
                : com.jrpetty.mcassistant.entity.goal.BuildGoal.plan(Harbours.OFFICE, b.anchor(), b.facing(), 13)) {
            if (p.part() != com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.BELL) continue;
            if (level.getBlockState(p.pos()).getBlock() instanceof net.minecraft.world.level.block.BellBlock bell) {
                bell.attemptToRing(level, p.pos(), b.facing().getOpposite());
                return;
            }
        }
        level.playSound(null, b.anchor(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.0F, 1.0F);
    }

    /** In harbour, on the town's visit: its people kept ashore, its trade with the town, and its sailing when the day comes. */
    static void inHarbour(ServerLevel level, Villages.Village v, Harbours.Shore s, Ship ship, long day, long t) {
        UUID id = v.id();
        if (!Wars.enemies(id).isEmpty() && ship.leave > day) {
            ship.leave = day;                                          // war broke out: it sails at once
            Villages.tell(id, day, ship.name + " made ready to sail at once: no ship lies in a harbour at war");
            save(id, ship);
        }
        boolean due = day > ship.leave || day == ship.leave && t >= 1500L && t < 12000L;
        if (!due) {
            Seafarers.keepAshore(level, v, s, ship);
            dealings(level, v, s, ship, day, t);
            return;
        }
        long now = level.getGameTime();
        if (ship.called < 0) {
            ship.called = now;
            ShipTrade.closeAll(id);
            save(id, ship);
        }
        if (Seafarers.ashore(level, ship) > 0 && now - ship.called < WAIT_ABOARD) return;
        castOff(level, v, s, ship);
    }

    /** Its people aboard: the gangplank up, turned about, and out it goes. */
    static void castOff(ServerLevel level, Villages.Village v, Harbours.Shore s, Ship ship) {
        UUID id = v.id();
        Seafarers.allAboard(level, ship);
        if (ship.drawn) {
            ShipHull.gangplank(level, s, ship.pos, ship.bowIn, false);
            ShipHull.Move m = ShipHull.move(level, s, ship.flag(), true, ship.pos, ship.bowIn, ship.pos, false);
            if (m == ShipHull.Move.UNLOADED) return;                    // nobody to see it go: it goes when somebody can
            if (m == ShipHull.Move.DONE) ship.bowIn = false;
        }
        ship.stage = "out";
        save(id, ship);
    }

    /** Over the horizon: the chronicle, the books, its land's regard, and the next ship's day. */
    static void gone(ServerLevel level, Villages.Village v, Ship ship) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        ForeignShips.Land land = ship.land();
        SHIPS.remove(id);
        Ledger.forget(id, SHIP);
        ShipTrade.closeAll(id);
        List<String> home = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ship.bought.entrySet()) {
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(e.getKey()));
            home.add(e.getValue() + " " + words(it));
        }
        boolean traded = ship.lotsSold > 0 || !ship.bought.isEmpty();
        if (land != null && traded) regard(id, land, 1);
        // An offended land comes round in time: word of the ships that were well treated reaches it.
        for (ForeignShips.Land l : ForeignShips.LANDS) if (l != land && regard(id, l) < 0) regard(id, l, 2);
        Villages.tell(id, day, ship.name + " sailed for " + (land == null ? "home" : land.name())
            + (home.isEmpty() ? " with nothing of ours aboard" : " with " + Merchants.list(home)) + "; it sold " + ship.lotsSold
            + (ship.lotsSold == 1 ? " lot" : " lots") + " here");
        logVisit(id, day, ship);
        int gap = gap(id, day, level.getRandom());
        Ledger.note(id, NEXT, Long.toString(day + gap));
        LOG.info("[MCA-HARBOUR] {}: {} gone (sold {} lots, town paid {}, got {}; players paid {}, got {}); next in {} days",
            Villages.name(id), ship.name, ship.lotsSold, ship.townPaid, ship.townGot, ship.playerPaid, ship.playerGot, gap);
    }

    /** A visit, for the books: "day 12: the Saffron Star of Qadira; sold 4 lots for 31c, bought 48 goods for 22c; dues 6c". */
    static void logVisit(UUID village, long day, Ship ship) {
        ForeignShips.Land land = ship.land();
        int goods = 0;
        for (int n : ship.bought.values()) goods += n;
        String line = "day " + (day + 1) + ": " + ship.name + " of " + (land == null ? "?" : land.name()) + "; sold " + ship.lotsSold
            + " lots for " + (ship.townPaid + ship.playerPaid) + "c, bought " + goods + " goods for " + (ship.townGot + ship.playerGot)
            + "c; dues " + ship.dues + "c";
        String was = Ledger.note(village, LOGBOOK);
        List<String> all = new ArrayList<>();
        if (was != null && !was.isEmpty()) all.addAll(List.of(was.split("\n")));
        all.add(0, line);
        while (all.size() > 6) all.remove(all.size() - 1);
        Ledger.note(village, LOGBOOK, String.join("\n", all));
    }

    // ------------------------------------------------------------------ the town's trade with it

    /** A thing's name in plain words, as the server has it ("cobblestone", "oak log"): no language file on a server. */
    static String words(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
    }

    /** What the town keeps in the treasury whatever it buys: the morning's wages, about. */
    static int keep(UUID village) {
        return Math.max(10, 3 * Villages.headcount(village));
    }

    /** Why the town wants this lot off the ship, or null: its own needs, read off its stores and its buildings. */
    @Nullable
    static String need(ServerLevel level, Villages.Village v, Lot l) {
        UUID id = v.id();
        Item i = l.item();
        if (l.kind.startsWith("treatise:")) {
            return Villages.builtStructure(id, "library") != null || Villages.builtStructure(id, "school") != null
                || Villages.builtStructure(id, "townlibrary") != null ? "for the scholars to study" : null;
        }
        if (l.kind.equals("disc") || l.kind.equals("trim")) {
            return Villages.builtStructure(id, "museum") != null && Ledger.coins(id) > keep(id) * 3 ? "a curiosity for the museum" : null;
        }
        ItemStack one = new ItemStack(i);
        if (one.is(ItemTags.LOGS)) return Market.stock(level, id, s -> s.is(ItemTags.LOGS)) < 32 ? "timber for the builders" : null;
        if (i == Items.GLASS || i == Items.GLASS_PANE || i == Items.TINTED_GLASS || one.is(net.minecraft.tags.ItemTags.create(
                ResourceLocation.withDefaultNamespace("stained_glass")))) {
            return Market.stock(level, id, s -> s.is(Items.GLASS) || s.is(Items.GLASS_PANE)) < 8 ? "glass for the windows" : null;
        }
        if (i == Items.BOOK || i == Items.PAPER) {
            return (Villages.builtStructure(id, "library") != null || Villages.builtStructure(id, "townlibrary") != null)
                && Market.stock(level, id, s -> s.is(i)) < (i == Items.BOOK ? 4 : 18) ? "for the library" : null;
        }
        if (one.get(net.minecraft.core.component.DataComponents.FOOD) != null || i == Items.SUGAR || i == Items.COCOA_BEANS) {
            for (Villages.Need n : Villages.needs(level, id)) if (n.task() == Villages.Task.FOOD) return "the larder is low";
            return Villages.builtStructure(id, "cafe") != null && (i == Items.SUGAR || i == Items.COCOA_BEANS) ? "the café's cakes and cocoa" : null;
        }
        if (one.is(net.minecraft.tags.ItemTags.create(ResourceLocation.parse("c:dyes")))
                || BuiltInRegistries.ITEM.getKey(i).getPath().endsWith("_dye")) {
            return Decor.wants(id).isEmpty() ? null : "dye for the houses' rugs and hangings";
        }
        return null;
    }

    /**
     * The town's dealings with the ship in harbour, by day, a lot at a time with a hand at the quay (TownJobs): what it
     * needs off the ship (two lots a day at most, out of the treasury over what it keeps for the wages, into the stores),
     * and its surplus aboard for coin (three lots a day, as far as the ship's purse goes: Budget's spare, never what the
     * town needs itself).
     */
    static void dealings(ServerLevel level, Villages.Village v, Harbours.Shore s, Ship ship, long day, long t) {
        if (t < 1000L || t > 11000L || ship.called >= 0) return;
        UUID id = v.id();
        if (ship.dealt != day) {
            ship.dealt = day;
            ship.buys = 0;
            ship.sells = 0;
        }
        ForeignShips.Land land = ship.land();
        BlockPos quay = s.onDeck(1, 0);
        if (ship.buys < 2) {
            for (Lot l : ship.hold) {
                if (l.lotsLeft() <= 0) continue;
                String why = need(level, v, l);
                if (why == null || Ledger.coins(id) < l.price + keep(id)) continue;
                if (!TownJobs.atWork(level, v, Harbours.WORKS, quay, "unloading " + ship.name)) return;
                int paid = Ledger.takeCoins(id, l.price);
                if (paid < l.price) {
                    Ledger.addCoins(id, paid);
                    return;
                }
                ItemStack lot = l.one(land);
                l.count -= l.lot;
                ship.purse += paid;
                ship.townPaid += paid;
                ship.lotsSold++;
                ship.buys++;
                Economy.spent(id, paid);
                CoinCensus.flow(id, CoinCensus.Flow.VISITOR_OUT, paid);     // [business-books] away with the ship
                Businesses.storesIn(id, lot, lot.getCount(), paid * 100L);   // [business-books] the stores' cost: what was paid
                Crafts.store(level, v, lot);
                Villages.tell(id, day, "the town bought " + l.words + " (" + lot.getCount() + ") off " + ship.name + " for "
                    + Visitors.coins(paid) + ": " + why);
                save(id, ship);
                return;
            }
            ship.buys = 2;                                             // nothing it needs aboard today
        }
        if (ship.sells < 3 && ship.purse > 0) {
            for (Budget.Offer o : Budget.forSale(level, id, 8)) {
                ItemStack one = new ItemStack(o.item());
                boolean wanted = land != null && land.wants().contains(o.item());
                int n = Math.min(o.count(), Math.min(32, one.getMaxStackSize()));
                double each = PriceIndex.each(level, id, one) * (wanted ? 1.0 : 0.75);
                int price = (int) Math.floor(each * n);
                if (n <= 0 || price < 1) continue;
                if (price > ship.purse) {
                    n = (int) Math.floor(ship.purse / Math.max(0.01, each));
                    price = (int) Math.floor(each * n);
                    if (n <= 0 || price < 1) continue;
                }
                if (!TownJobs.atWork(level, v, Harbours.WORKS, quay, "loading " + ship.name)) return;
                List<ItemStack> took = Caravans.takeOut(level, v, st -> st.is(o.item()) && !st.isDamageableItem() && !st.isEnchanted(), n);
                int got = 0;
                for (ItemStack st : took) got += st.getCount();
                if (got <= 0) continue;
                price = Math.min(ship.purse, (int) Math.floor(each * got));
                ship.bought.merge(BuiltInRegistries.ITEM.getKey(o.item()).toString(), got, Integer::sum);
                ship.purse -= price;
                ship.townGot += price;
                ship.sells++;
                if (price > 0) {
                    Ledger.addCoins(id, price);
                    Economy.sold(id, price);
                    CoinCensus.flow(id, CoinCensus.Flow.VISITOR_IN, price);  // [business-books] the ship's coin
                    Businesses.service(level, id, Business.Type.HARBOUR, new ItemStack(o.item(), got), got, price * 100L, true);   // [business-books] the stores' sale, booked
                }
                Villages.tell(id, day, "sold " + got + " " + words(o.item()) + " to " + ship.name + " for " + Visitors.coins(price)
                    + (wanted ? " (" + (land == null ? "they" : "the " + land.people()) + " want it at home)" : ""));
                save(id, ship);
                return;
            }
            ship.sells = 3;
        }
    }

    // ------------------------------------------------------------------ its people

    /** One of its people killed in the town's harbour: their land will send no ship for a long while; the ship sails. */
    static void crewLost(ServerLevel level, UUID village, VillageFolkEntity f, boolean byHand) {
        Ship ship = ship(village);
        if (ship == null) return;
        ForeignShips.Land land = ship.land();
        long day = level.getDayTime() / 24000L;
        ship.crew.remove(f.getUUID());
        if (land != null) regard(village, land, byHand ? -12 : -1);
        if (ship.docked() && ship.leave > day) ship.leave = day;
        save(village, ship);
        Villages.tell(village, day, byHand
            ? f.displayNameCap() + " of " + ship.name + " was killed on the quay; the " + (land == null ? "foreign" : land.people())
                + " ship made ready to sail, and " + (land == null ? "its land" : land.name()) + " will send no ship here for a long while"
            : f.displayNameCap() + " of " + ship.name + " was lost ashore; the ship sails sooner for it");
    }

    // ------------------------------------------------------------------ the books

    /** The Harbour page's ships: due, in harbour, the visits past, the lands' regard. */
    static List<String> lines(ServerLevel level, Villages.Village v, Harbours.Shore s) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        Ship ship = ship(id);
        out.add("H|Ships");
        if (ship != null) {
            ForeignShips.Land land = ship.land();
            String where = switch (ship.stage) {
                case "in" -> "coming in, " + Math.max(0, ship.pos - ShipHull.BERTH) + " blocks out";
                case "out" -> "sailing away";
                default -> (ship.anchored ? "at anchor off the pier" : "alongside the pier") + ", sails "
                    + (ship.leave <= day ? "this morning" : "in " + (ship.leave - day) + (ship.leave - day == 1 ? " day" : " days"));
            };
            out.add("L|" + ship.name + " of " + (land == null ? "?" : land.name() + ", " + land.coast()) + ": " + where + ".");
            for (Lot l : ship.hold) {
                if (l.lotsLeft() <= 0) continue;
                out.add("M|  " + l.words + ": " + l.lotsLeft() + (l.lotsLeft() == 1 ? " lot" : " lots") + " of " + l.lot + " at " + l.price + "c");
            }
            out.add("M|  Its purse: " + ship.purse + "c (came with " + ship.purse0 + "); dues paid " + ship.dues + "c.");
            if (!ship.bought.isEmpty()) {
                List<String> b = new ArrayList<>();
                for (Map.Entry<String, Integer> e : ship.bought.entrySet()) b.add(e.getValue() + " " + ResourceLocation.parse(e.getKey()).getPath().replace('_', ' '));
                out.add("M|  Bought here: " + String.join(", ", b) + ".");
            }
        } else {
            long next = Harbours.parse(Ledger.note(id, NEXT));
            if (!Harbours.ready(id)) out.add("M|No ships until the harbour office stands and the pier is out.");
            else if (next >= 0) {
                ForeignShips.Land l = nextLand(id);
                out.add("L|Next ship due " + (next <= day ? "today" : next == day + 1 ? "tomorrow" : "in " + (next - day) + " days")
                    + (l == null ? "" : ", from " + l.name()) + ".");
            }
            if (Harbours.parse(Ledger.note(id, HELD)) == day) out.add("W|Held up today (see the chronicle).");
            if (!Wars.enemies(id).isEmpty()) out.add("W|No ship will call while the town is at war.");
        }
        out.add("M|Ships every " + gap(id, day, RandomSource.create(0)) + " days or so" + (Harbours.litLately(id, day) ? " (the light burning helps)" : "")
            + "; harbour dues taken in all: " + Math.max(0, Harbours.parse(Ledger.note(id, DUES))) + "c.");
        String log = Ledger.note(id, LOGBOOK);
        if (log != null && !log.isEmpty()) {
            out.add("H|Ships gone");
            for (String l : log.split("\n")) out.add("M|" + l);
        }
        StringBuilder sb = new StringBuilder();
        for (ForeignShips.Land l : ForeignShips.LANDS) {
            if (sb.length() > 0) sb.append(", ");
            int r = regard(id, l);
            sb.append(l.name()).append(' ').append(r < 0 ? "offended" : r >= 6 ? "warm" : r > 0 ? "friendly" : "unknown");
        }
        out.add("M|The lands: " + sb + ".");
        return out;
    }

    // ------------------------------------------------------------------ tests and the admin

    /** Tests, the admin and the stage: a ship of this land (or the next to come) sighted now, coming in. Null with no harbour. */
    @Nullable
    public static String sightNow(ServerLevel level, Villages.Village v, @Nullable String landId) {
        Harbours.Shore s = Harbours.shore(v.id());
        if (s == null) return null;
        Ship have = ship(v.id());
        if (have != null) return have.name;
        ForeignShips.Land l = landId == null ? nextLand(v.id()) : ForeignShips.land(landId);
        if (l == null) l = ForeignShips.LANDS.get(0);
        return sight(level, v, s, l).name;
    }

    /** Tests: the ship's stage, its place on the lane and whether it is drawn ("in 44 drawn"), or "" with none. */
    public static String stateForTests(UUID village) {
        Ship s = ship(village);
        return s == null ? "" : s.stage + " " + s.pos + (s.drawn ? " drawn" : "") + (s.anchored ? " anchored" : "") + (s.bowIn ? "" : " turned");
    }

    /** Tests: a step of the ship's way now. */
    public static void sailForTests(ServerLevel level, Villages.Village v) {
        Ship s = ship(v.id());
        Harbours.Shore sh = Harbours.shore(v.id());
        if (s != null && sh != null) sail(level, v, sh, s);
    }

    /** Tests: the town's visit to its ship now. */
    public static void townTickForTests(ServerLevel level, Villages.Village v) {
        Harbours.Shore sh = Harbours.shore(v.id());
        if (sh != null) townTick(level, v, sh);
    }

    /** Tests: the ship's hull blocks standing where it lies. */
    public static int hullForTests(ServerLevel level, Villages.Village v) {
        Ship s = ship(v.id());
        Harbours.Shore sh = Harbours.shore(v.id());
        return s == null || sh == null ? 0 : ShipHull.standing(level, sh, s.flag(), s.pos, s.bowIn);
    }

    /** Tests: the ship's purse, and the lots left in its hold. */
    public static int[] purseAndLotsForTests(UUID village) {
        Ship s = ship(village);
        if (s == null) return new int[]{ -1, -1 };
        int lots = 0;
        for (Lot l : s.hold) lots += l.lotsLeft();
        return new int[]{ s.purse, lots };
    }

    /** Tests: the ship sails today (its leaving day brought forward). */
    public static void leaveNowForTests(UUID village, long day) {
        Ship s = ship(village);
        if (s != null) {
            s.leave = day;
            save(village, s);
        }
    }

    /** Tests: the ship forgotten from memory (as after a restart), to be read again from the notes. */
    public static void forgetForTests(UUID village) {
        SHIPS.remove(village);
    }

    /** Tests: one of the ship's lots made something the town needs (a lot of glass, its stores bare of glass). */
    public static void holdForTests(UUID village, Item item, int lot, int price) {
        Ship s = ship(village);
        if (s == null) return;
        Lot l = new Lot();
        l.item = BuiltInRegistries.ITEM.getKey(item).toString();
        l.lot = lot;
        l.count = lot * 2;
        l.price = price;
        l.words = words(item);
        s.hold.add(0, l);
        save(village, s);
    }

    /** Tests: the ship called aboard and cast off now (its people gone with it, whoever is still ashore). */
    public static void castOffForTests(ServerLevel level, Villages.Village v) {
        Ship s = ship(v.id());
        Harbours.Shore sh = Harbours.shore(v.id());
        if (s == null || sh == null) return;
        s.called = level.getGameTime();
        castOff(level, v, sh, s);
    }

    /** Tests: what would hold up a ship due today ("" if nothing). */
    public static String heldUpForTests(ServerLevel level, Villages.Village v) {
        Harbours.Shore sh = Harbours.shore(v.id());
        String s = sh == null ? "no harbour" : heldUp(level, v, sh);
        return s == null ? "" : s;
    }

    /** Tests: the trading table a player sees at the ship: "sell id n for c", "buy id n for c". */
    public static List<String> tableForTests(ServerLevel level, UUID village) {
        return ShipTrade.tableForTests(level, village);
    }

    /** Tests: a player takes the n-th offer on the table, as the trading screen takes it. */
    public static boolean tradeForTests(ServerPlayer p, UUID village, int n) {
        return ShipTrade.tradeForTests(p, village, n);
    }

    /** Tests: the land's regard for the town. */
    public static int regardForTests(UUID village, String land) {
        ForeignShips.Land l = ForeignShips.land(land);
        return l == null ? 0 : regard(village, l);
    }

    /** Tests: the day the next ship is due. */
    public static long nextForTests(UUID village) {
        return Harbours.parse(Ledger.note(village, NEXT));
    }

    /** Tests: the ship's crew uuids. */
    public static List<UUID> crewForTests(UUID village) {
        Ship s = ship(village);
        return s == null ? List.of() : List.copyOf(s.crew);
    }
}
