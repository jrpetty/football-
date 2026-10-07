package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Caravans between a village and the colony it founded. Every couple of days, by day, one
 * of the mother village's hands sets out down the road with a pack llama and the surplus
 * of its stores — food, timber, stone, iron — walks it to the colony, unloads it into the
 * colony's stores, loads what the colony has plenty of, and walks it home. You can meet it
 * on the road, ask the carrier where it is going, and trade with it.
 */
public final class Caravans {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Caravans() {}

    /** Two game days between caravans on any one road. */
    public static final long INTERVAL = 48000L;
    private static final int EVERY = 200;

    /** A caravan on the road: who it is going to, what leg it is on, how far along. */
    public static final class Trip {
        final UUID from, to;
        boolean back;
        final List<BlockPos> way;
        int at;
        @Nullable UUID llama;
        int walkTick = -1000, gainedTick;
        double best = Double.MAX_VALUE;
        @Nullable BlockPos window;
        int spokeTick = -100000;
        /** An envoy's trip: what it has come about (Envoys); null for a caravan. */
        @Nullable Envoys.Errand errand;
        /** A trade caravan between villages with a pact: the goods are paid for. */
        boolean trade;
        /** An envoy there, waiting to be heard. */
        boolean waiting;
        long waitSince;
        /** Coin it carries: a peace offering, a tribute, or what goods were sold for. */
        int purse;
        /** What it came back with, for the envoy's report. */
        @Nullable String outcome;
        /** What the village it went to could not pay for: the sender's own, carried home again. */
        final List<ItemStack> unsold = new ArrayList<>();
        /** [econ-trade] A trade deal's delivery (TradeDeals): the pair it runs between; null for any other trip. */
        @Nullable String deal;
        /** [econ-trade] Of the coin in its purse, what the other town paid for goods (the rest is its own, going home). */
        int earned;

        Trip(UUID from, UUID to, List<BlockPos> way) {
            this.from = from;
            this.to = to;
            this.way = way;
        }

        public UUID destination() { return back ? from : to; }
        public boolean homeward() { return back; }
        @Nullable public Envoys.Errand errand() { return errand; }
        public boolean trading() { return trade; }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % EVERY != 71) return;
        Guard.run("caravans", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                TradeDeals.tick(level);                         // [econ-trade] trips kept, the trade books' mornings, the deals' caravans
                tick(level);
            }
        });
    }

    static void tick(ServerLevel level) {
        long now = level.getGameTime();
        long time = level.getDayTime() % 24000L;
        if (time < 1000 || time > 7000) return;                 // they set out in the morning
        for (Map.Entry<UUID, UUID> link : Ledger.links().entrySet()) {
            Villages.Village colony = Villages.get(link.getKey());
            Villages.Village mother = Villages.get(link.getValue());
            if (colony == null || mother == null || !mother.dim().equals(level.dimension())) continue;
            long last = Ledger.caravanAt(colony.id());
            if (last >= 0 && now - last < INTERVAL && now >= last) continue;
            if (onTheRoad(colony.id())) continue;
            if (setOut(level, mother, colony)) Ledger.caravanAt(colony.id(), now);
        }
        // Trade caravans between villages with a pact (Envoys): every three days, each way in turn.
        List<Villages.Village> here = new ArrayList<>();
        for (Villages.Village v : Villages.every()) if (v.dim().equals(level.dimension())) here.add(v);
        for (int i = 0; i < here.size(); i++) {
            for (int j = i + 1; j < here.size(); j++) {
                Villages.Village a = here.get(i), b = here.get(j);
                if (!Envoys.pact(a.id(), b.id()) || !Diplomacy.neighbours(a, b)) continue;
                if (TradeDeals.live(a.id(), b.id())) continue;   // [econ-trade] a deal's caravans keep its own days (TradeDeals)
                String key = "trade/" + b.id();
                long last = parse(Ledger.note(a.id(), key));
                if (last >= 0 && now - last < INTERVAL * 3 / 2 && now >= last) continue;
                if (between(a.id(), b.id())) continue;
                boolean aFirst = last < 0 || (last / 1000) % 2 == 0;
                if (aFirst ? setOutTrade(level, a, b) || setOutTrade(level, b, a) : setOutTrade(level, b, a) || setOutTrade(level, a, b)) {
                    Ledger.note(a.id(), key, Long.toString(now - now % 1000 + (aFirst ? 1000 : 0)));
                }
            }
        }
    }

    private static long parse(@Nullable String s) {
        if (s == null || s.isEmpty()) return -1;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }

    /** Is a caravan (or an envoy) already on the road between these two? */
    static boolean between(UUID a, UUID b) {
        for (UUID v : new UUID[]{ a, b }) {
            for (AssistantEntity x : Villages.folkOf(v)) {
                if (x instanceof VillageFolkEntity f && f.trip() != null
                        && (f.trip().to.equals(a) && f.trip().from.equals(b) || f.trip().to.equals(b) && f.trip().from.equals(a))) return true;
            }
        }
        return false;
    }

    /** A trade caravan: what one village has to spare and the other is short of, to be paid for there. */
    static boolean setOutTrade(ServerLevel level, Villages.Village from, Villages.Village to) {
        VillageFolkEntity carrier = choose(from);
        if (carrier == null) return false;
        // [econ-trade] What the other is short of, as far as this town can spare it (Budget.spare), not a rule of thumb.
        List<ItemStack> cargo = TradeDeals.pactLoad(level, from, to.id());
        if (cargo.isEmpty()) return false;
        carrier.clearQueue();
        for (ItemStack s : cargo) {
            ItemStack left = carrier.insertGiven(s);
            if (!left.isEmpty()) Market.intoStores(level, from.id(), left);
        }
        Cuisine.packDelicacy(level, from, carrier, to.id());              // [culture2] a couple of the town's own dish, a delicacy there
        Trip t = new Trip(from.id(), to.id(), way(from, to));
        t.trade = true;
        t.gainedTick = carrier.tickCount;
        carrier.trip(t);
        Riding.packFor(level, from, carrier);                    // a donkey from the stable to carry it (Riding)
        roadMoney(level, from, carrier, to);
        long day = level.getDayTime() / 24000L;
        Villages.tell(from.id(), day, "a trade caravan set out for " + Villages.name(to.id()));
        FolkTalk.speak(carrier, "Off to " + Villages.name(to.id()) + " to trade!");
        LOG.info("[MCA-ENVOY] trade caravan {} -> {} ({} lots)",
            Villages.name(from.id()), Villages.name(to.id()), cargo.size());
        return true;
    }

    /**
     * What a caravan costs the village that sends it, besides the goods: the carrier's road money
     * (a coin for every hundred blocks of the way, two at least, into its own purse) and its
     * provisions out of the stores (a loaf a hundred blocks). Trading is never free.
     */
    static int roadMoney(ServerLevel level, Villages.Village from, VillageFolkEntity carrier, Villages.Village to) {
        int far = (int) Math.sqrt(from.centre().distSqr(to.centre()));
        int coins = Ledger.takeCoins(from.id(), Math.max(2, far / 100));
        if (coins > 0) {
            carrier.earn(coins);
            Economy.spent(from.id(), coins);
        }
        int loaves = Math.max(2, Math.min(8, far / 100 + 1));
        java.util.function.Predicate<ItemStack> food = s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null;
        for (ItemStack s : takeOut(level, from, food, loaves)) {
            ItemStack left = carrier.insertGiven(s);
            if (!left.isEmpty()) Market.intoStores(level, from.id(), left);
        }
        return coins;
    }

    /** Is a caravan already on this colony's road? */
    private static boolean onTheRoad(UUID colony) {
        for (Villages.Village v : Villages.every()) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity f && f.trip() != null
                        && (f.trip().to.equals(colony) || f.trip().from.equals(colony))) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ setting out

    /** Who goes, with what, and the way there. Returns whether a caravan set out. */
    public static boolean setOut(ServerLevel level, Villages.Village mother, Villages.Village colony) {
        VillageFolkEntity carrier = choose(mother);
        if (carrier == null) return false;
        // The load: what the mother has more than plenty of, the colony's needs first.
        List<ItemStack> cargo = load(level, mother, colony.id(), false);
        if (cargo.isEmpty()) return false;
        carrier.clearQueue();
        for (ItemStack s : cargo) {
            ItemStack left = carrier.insertGiven(s);
            if (!left.isEmpty()) Market.intoStores(level, mother.id(), left);
        }
        Cuisine.packDelicacy(level, mother, carrier, colony.id());        // [culture2] a taste of home for the colony
        Trip t = new Trip(mother.id(), colony.id(), way(mother, colony));
        t.gainedTick = carrier.tickCount;
        carrier.trip(t);
        Riding.packFor(level, mother, carrier);                  // a donkey from the stable to carry it (Riding)
        roadMoney(level, mother, carrier, colony);
        // No pack llama: the village keeps none, and one out of nowhere for every caravan was a
        // llama, a chest and a carpet from nothing. The carrier takes the load on its own back —
        // or, if the stable has a donkey with a chest on it and there is a lead, in the donkey's
        // chest (Riding.packFor, above).
        long day = level.getDayTime() / 24000L;
        Villages.tell(mother.id(), day, "a caravan set out for " + Villages.name(colony.id()));
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().closerThan(mother.centre(), 128)) {
                p.displayClientMessage(Component.literal("A caravan sets out from " + Villages.name(mother.id())
                    + " for " + Villages.name(colony.id()) + "."), true);
            }
        }
        carrier.say("Off to " + Villages.name(colony.id()) + " with the caravan!");
        return true;
    }

    private static final Item[] CARPETS = { Items.RED_CARPET, Items.BLUE_CARPET, Items.GREEN_CARPET, Items.YELLOW_CARPET,
        Items.PURPLE_CARPET, Items.ORANGE_CARPET, Items.CYAN_CARPET, Items.MAGENTA_CARPET };

    /** A carrier if there is one at the heart, else any grown hand that is not keeping watch or leading a build. */
    @Nullable
    static VillageFolkEntity choose(Villages.Village v) {
        VillageFolkEntity best = null;
        double bestScore = Double.MAX_VALUE;
        long now = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive() || f.isBaby() || f.isSleeping() || f.trip() != null) continue;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD) continue;
            now = f.level().getGameTime();
            if (Villages.holdsTheLead(v.id(), f.getUUID(), now)) continue;
            double score = f.blockPosition().distSqr(v.centre());
            if (f.stationTask() == AssistantEntity.StationTask.HAUL) score -= 1e6;
            if (score < bestScore) { bestScore = score; best = f; }
        }
        return best;
    }

    /**
     * What a caravan carries from these stores: of the market's goods, what the stores hold
     * more than plenty of (keeping plenty back), the other village's needs first, up to a
     * stack of each and four kinds.
     */
    static List<ItemStack> load(ServerLevel level, Villages.Village from, UUID to, boolean neededOnly) {
        return load(level, from, to, neededOnly, true);
    }

    /** As above; {@code take} false only says what it would be, and takes nothing. */
    static List<ItemStack> load(ServerLevel level, Villages.Village from, UUID to, boolean neededOnly, boolean take) {
        return load(level, from, to, neededOnly, take, java.util.Set.of());
    }

    /**
     * As above, leaving out the goods the caravan has just brought: the carrier that unloaded the
     * mother's spare bread in the colony took it all straight back again, because the mother, once
     * the bread was gone, was short of bread.
     */
    static List<ItemStack> load(ServerLevel level, Villages.Village from, UUID to, boolean neededOnly, boolean take,
                                java.util.Set<Market.Good> brought) {
        java.util.Set<Villages.Task> wanted = java.util.EnumSet.noneOf(Villages.Task.class);
        for (Villages.Need n : Villages.needs(level, to)) wanted.add(n.task());
        List<Market.Good> order = new ArrayList<>();
        for (Market.Good g : Market.GOODS) if (wanted.contains(g.need())) order.add(g);
        if (!neededOnly) {
            for (Market.Good g : Market.GOODS) if (!order.contains(g) && g.need() != Villages.Task.NONE) order.add(g);
        }
        List<ItemStack> out = new ArrayList<>();
        for (Market.Good g : order) {
            if (out.size() >= 4) break;
            if (brought.contains(g)) continue;
            int have = Market.stock(level, from.id(), g.what());
            int plenty = g.bundle() * 4;
            int spare = Math.min(64, have - plenty);
            if (spare < g.bundle()) continue;
            if (!take) {
                out.add(ItemStack.EMPTY);
                continue;
            }
            out.addAll(takeOut(level, from, g.what(), spare));
        }
        return out;
    }

    /** Take so many of a thing out of a village's stores, as stacks. */
    static List<ItemStack> takeOut(ServerLevel level, Villages.Village v, java.util.function.Predicate<ItemStack> what, int n) {
        List<ItemStack> out = new ArrayList<>();
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (n <= 0) break;
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && n > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                int k = Math.min(n, s.getCount());
                out.add(s.copyWithCount(k));
                s.shrink(k);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                n -= k;
            }
            c.setChanged();
        }
        return out;
    }

    /** The way from heart to heart: out along the avenue, down the road, in along the other avenue. */
    static List<BlockPos> way(Villages.Village from, Villages.Village to) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos a = Roads.exit(from.centre(), to.centre());
        BlockPos b = Roads.exit(to.centre(), from.centre());
        straight(out, from.centre(), a);
        List<int[]> road = Roads.bresenham(a.getX(), a.getZ(), b.getX(), b.getZ());
        for (int i = 10; i < road.size(); i += 10) out.add(new BlockPos(road.get(i)[0], from.centre().getY(), road.get(i)[1]));
        out.add(b);
        straight(out, b, to.centre().offset(2, 0, 2));
        return out;
    }

    private static void straight(List<BlockPos> out, BlockPos a, BlockPos b) {
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(a.distSqr(b)) / 10.0));
        for (int i = 1; i <= steps; i++) {
            out.add(new BlockPos(a.getX() + (b.getX() - a.getX()) * i / steps, a.getY(), a.getZ() + (b.getZ() - a.getZ()) * i / steps));
        }
    }

    // ------------------------------------------------------------------ on the road

    /** A folk on a caravan walks it, step by step. Returns true while it is on the road. */
    public static boolean drive(VillageFolkEntity f, ServerLevel level) {
        Trip t = f.trip();
        if (t == null) return false;
        keepAwake(level, f, t);
        if (t.waiting) {
            Envoys.waitThere(level, f, t);
            return f.trip() != null;
        }
        if (RailCarts.caravan(f, level, t)) return true;        // [transport] by the line between the two towns, where there is one
        // The pack donkey fetched from the stable, tied and loaded, and kept up with (Riding).
        if (Riding.caravan(f, level, t)) return true;
        Llama llama = llama(level, t);
        if (llama != null) {
            if (!llama.isLeashed() || llama.distanceToSqr(f) > 12.0 * 12.0) {
                if (llama.distanceToSqr(f) > 8.0 * 8.0) llama.moveTo(f.getX() + 1.0, f.getY(), f.getZ() + 1.0, llama.getYRot(), 0.0F);
                llama.setLeashedTo(f, true);
            }
        }
        if (t.at >= t.way.size()) {
            arrive(level, f, t);
            return f.trip() != null;
        }
        BlockPos wp = t.way.get(t.at);
        BlockPos onGround = surface(level, wp);
        double d = flat(f.blockPosition(), onGround);
        if (d <= 9.0) {
            t.at++;
            t.best = Double.MAX_VALUE;
            t.gainedTick = f.tickCount;
            return true;
        }
        if (d < t.best - 1.0) { t.best = d; t.gainedTick = f.tickCount; }
        if (f.getNavigation().isDone() || f.tickCount - t.walkTick > 100) {
            f.walkTo(onGround, 0.75D);
            t.walkTick = f.tickCount;
        }
        // No nearer for half a minute (a river with no bridge yet, a cliff): set down at the next step.
        if (f.tickCount - t.gainedTick > 600) {
            f.moveTo(onGround.getX() + 0.5, onGround.getY(), onGround.getZ() + 0.5, f.getYRot(), 0.0F);
            if (llama != null) llama.moveTo(onGround.getX() + 1.5, onGround.getY(), onGround.getZ() + 0.5, llama.getYRot(), 0.0F);
            Riding.bringAlong(f, level, onGround.getX() + 0.5, onGround.getY(), onGround.getZ() + 0.5);
            t.gainedTick = f.tickCount;
            t.best = Double.MAX_VALUE;
        }
        if (f.tickCount - t.spokeTick > 2400) {
            t.spokeTick = f.tickCount;
            f.say(t.errand != null && !t.back ? "On my way to " + Villages.name(t.destination()) + " for the elder."
                : "On the road to " + Villages.name(t.destination()) + ".");
        }
        return true;
    }

    private static double flat(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static BlockPos surface(ServerLevel level, BlockPos p) {
        if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return p;
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return new BlockPos(p.getX(), y, p.getZ());
    }

    /** At the end of a leg: unload into the stores there; going out, load up for home; home, done. */
    static void arrive(ServerLevel level, VillageFolkEntity f, Trip t) {
        if (t.errand != null && !t.back) {
            Envoys.arrived(level, f, t);
            return;
        }
        Villages.Village here = Villages.get(t.destination());
        Villages.Village other = Villages.get(t.back ? t.to : t.from);
        long day = level.getDayTime() / 24000L;
        Riding.unpack(f, level);                                 // the load out of the donkey's chest, to be sold (Riding)
        // [econ-trade] A deal's delivery: the agreed goods and the agreed coin only, exchanged in person (TradeDeals).
        boolean dealt = t.deal != null && here != null && other != null && TradeDeals.exchange(level, f, t);
        // The village that sent for the goods buys them off the caravan as they come off its back:
        // at the market's worth from a trading partner, at the family price (half) between a mother
        // village and its colony. What its treasury cannot pay for stays on the carrier's back and
        // goes home again.
        double rate = t.trade ? 1.0 : 0.5;
        int unloaded = 0, paidAll = 0, refused = 0;
        java.util.Set<Market.Good> brought = new java.util.HashSet<>();
        StringBuilder what = new StringBuilder();
        if (here != null && !dealt) {
            if (t.back) returnUnsold(level, f, t, here);
            for (int i = 0; i < f.getInventoryItems().size(); i++) {
                ItemStack s = f.getInventoryItems().get(i);
                Market.Good g = s.isEmpty() ? null : Market.goodFor(s);
                if (g == null) continue;
                int move = s.getCount() - carrierKeeps(f, s);
                if (move <= 0) continue;
                // [econ-trade] Home again, the load was bought at the other town, out of the purse, in person: nothing to pay
                // here. Between pact towns, the price is halfway between the two towns' own (TradeDeals.pactPrice).
                double unit = t.back ? 0.0 : t.trade && other != null ? TradeDeals.pactPrice(level, here.id(), other.id(), s.copyWithCount(1))
                    : g.value() * rate;
                if (other != null && unit > 0) {
                    int afford = (int) Math.floor(Ledger.coins(here.id()) / unit);
                    if (afford < move) {
                        int no = move - Math.max(0, afford);
                        refused += no;
                        if (!t.back) t.unsold.add(s.copyWithCount(no));
                        move = Math.max(0, afford);
                    }
                }
                if (move <= 0) continue;
                ItemStack lot = s.copyWithCount(move);
                ItemStack left = Market.intoStores(level, here.id(), lot);
                int moved = move - left.getCount();
                if (moved <= 0) continue;
                s.shrink(moved);
                int price = other == null ? 0 : (int) Math.round(unit * moved);
                if (price > 0) paidAll += Ledger.takeCoins(here.id(), price);
                unloaded += moved;
                brought.add(g);
                if (what.length() < 60) what.append(what.length() == 0 ? "" : ", ").append(moved).append(' ')
                    .append(g.name().toLowerCase());
            }
            // An escort who walked with it is paid; a chartered route's holder takes a tenth (Commerce).
            if (other != null && !t.back) Commerce.caravanArrived(level, f, other.id(), here.id(), t.trade, paidAll);
            if (paidAll > 0 && other != null) {
                Economy.spent(here.id(), paidAll);
                // Carried home to the seller. ([econ-trade] Home again there is nothing to pay: the load was bought at the
                // other town, out of the purse. It used to be paid for here, into the other town's treasury, by nobody.)
                t.purse += paidAll;
            }
        }
        if (here != null && unloaded > 0) {
            Villages.tell(here.id(), day, "bought " + what + " off the caravan from " + Villages.name(other == null ? t.from : other.id())
                + (paidAll > 0 ? " for " + paidAll + " coins" + (t.trade ? "" : " (the family price)") : ""));
        }
        if (here != null && refused > 0) {
            Villages.tell(here.id(), day, "could not pay for " + refused + " more of the caravan's goods: they went home with the carrier");
            FolkTalk.speak(f, "They couldn't pay for all of it. The rest comes home with me.");
        }
        if (!t.back && here != null && other != null) {
            // Load what the colony has plenty of and the mother is short of, for the way home: [econ-trade] bought here,
            // out of the coin the carrier holds, and paid for in person (TradeDeals.buyForHome).
            if (!dealt) {
                for (ItemStack s : load(level, here, other.id(), true, true, brought)) TradeDeals.buyForHome(level, f, t, here, other, s, rate);
            }
            Riding.pack(f, level);                               // and the goods for home back into it
            if (t.trade) Ledger.relate(here.id(), other.id(), 3);
            t.back = true;
            List<BlockPos> home = new ArrayList<>(way(here, other));
            t.way.clear();
            t.way.addAll(home);
            t.at = 0;
            t.best = Double.MAX_VALUE;
            t.gainedTick = f.tickCount;
            f.say("Unloaded in " + Villages.name(here.id()) + ". Home again now.");
            return;
        }
        // Home: the llama back to its pasture, and the carrier back to its work.
        Llama llama = llama(level, t);
        if (llama != null) llama.discard();
        release(level, f, t);
        TradeTrips.over(f);                                      // [econ-trade] the written trip let go
        f.trip(null);
        Riding.caravanHome(f, level);                            // the donkey led back to the stable (Riding)
        if (t.errand != null) {
            Envoys.home(level, f, t);
            return;
        }
        if (t.purse > 0) {
            Ledger.addCoins(t.from, t.purse);
            Economy.sold(t.from, t.purse);
            Villages.tell(t.from, day, "our caravan came home from " + Villages.name(t.to) + " with " + t.purse + " coins for the goods");
        }
        f.say("Back from " + Villages.name(t.to) + "!");
    }

    /** Home again with goods the other village could not pay for: they are the village's own, back into its stores. */
    private static void returnUnsold(ServerLevel level, VillageFolkEntity f, Trip t, Villages.Village home) {
        for (ItemStack want : t.unsold) {
            int n = want.getCount();
            for (int i = 0; i < f.getInventoryItems().size() && n > 0; i++) {
                ItemStack s = f.getInventoryItems().get(i);
                if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, want)) continue;
                int k = Math.min(n, s.getCount());
                ItemStack left = Market.intoStores(level, home.id(), s.copyWithCount(k));
                int in = k - left.getCount();
                s.shrink(in);
                n -= in;
                if (in < k) break;                                                 // the stores are full: it keeps the rest
            }
        }
        t.unsold.clear();
    }

    /**
     * What a carrier keeps back when it unloads: a bite for the road and a few blocks, not
     * its whole everyday reserve — the cargo was the village's, and a carrier that kept its
     * usual sixty-odd loaves delivered sixteen of the eighty it set out with.
     */
    static int carrierKeeps(VillageFolkEntity f, ItemStack s) {
        int reserve = Math.max(0, f.depositReserve(s));
        return Math.min(reserve, s.get(net.minecraft.core.component.DataComponents.FOOD) != null ? 4 : 8);
    }

    @Nullable
    private static Llama llama(ServerLevel level, Trip t) {
        if (t.llama == null) return null;
        return level.getEntity(t.llama) instanceof Llama l && l.isAlive() ? l : null;
    }

    private static UUID owner(VillageFolkEntity f) {
        return UUID.nameUUIDFromBytes(("mca-caravan-" + f.getUUID()).getBytes());
    }

    /** The ground round the caravan kept awake as it goes. */
    private static void keepAwake(ServerLevel level, VillageFolkEntity f, Trip t) {
        BlockPos here = f.blockPosition();
        if (t.window != null && t.window.distSqr(here) < 16 * 16) return;
        if (t.window != null) ChunkLoad.setLoaded(level, owner(f), t.window, 1, false);
        ChunkLoad.setLoaded(level, owner(f), here, 1, true);
        t.window = here.immutable();
    }

    private static void release(ServerLevel level, VillageFolkEntity f, Trip t) {
        if (t.window != null) ChunkLoad.setLoaded(level, owner(f), t.window, 1, false);
        t.window = null;
    }

    /** Tests: the caravan reaches the end of the leg it is on. */
    public static void arriveForTests(ServerLevel level, VillageFolkEntity f) {
        if (f.trip() != null) arrive(level, f, f.trip());
    }

    /** A carrier that dies or is lost on the road: let its llama go, release the ground. */
    public static void abandon(ServerLevel level, VillageFolkEntity f) {
        Trip t = f.trip();
        if (t == null) return;
        Llama llama = llama(level, t);
        if (llama != null) llama.dropLeash(true, false);
        Riding.caravanLost(f, level);                            // its donkey let go, for the stable to bring in
        release(level, f, t);
        TradeTrips.over(f);                                      // [econ-trade] the written trip let go
        f.trip(null);
    }
}
