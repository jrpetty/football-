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
            for (ServerLevel level : event.getServer().getAllLevels()) tick(level);
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
        List<ItemStack> cargo = load(level, from, to.id(), true);
        if (cargo.isEmpty()) return false;
        carrier.clearQueue();
        for (ItemStack s : cargo) {
            ItemStack left = carrier.insertItem(s);
            if (!left.isEmpty()) Market.intoStores(level, from.id(), left);
        }
        Trip t = new Trip(from.id(), to.id(), way(from, to));
        t.trade = true;
        t.gainedTick = carrier.tickCount;
        carrier.trip(t);
        long day = level.getDayTime() / 24000L;
        Villages.tell(from.id(), day, "a trade caravan set out for " + Villages.name(to.id()));
        FolkTalk.speak(carrier, "Off to " + Villages.name(to.id()) + " to trade!");
        LOG.info("[MCA-ENVOY] trade caravan {} -> {} ({} lots)",
            Villages.name(from.id()), Villages.name(to.id()), cargo.size());
        return true;
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
            ItemStack left = carrier.insertItem(s);
            if (!left.isEmpty()) Market.intoStores(level, mother.id(), left);
        }
        Trip t = new Trip(mother.id(), colony.id(), way(mother, colony));
        t.gainedTick = carrier.tickCount;
        carrier.trip(t);
        // No pack llama: the village keeps none, and one out of nowhere for every caravan was a
        // llama, a chest and a carpet from nothing. The carrier takes the load on its own back.
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
        int unloaded = 0;
        double worth = 0;
        java.util.Set<Market.Good> brought = new java.util.HashSet<>();
        StringBuilder what = new StringBuilder();
        if (here != null) {
            for (int i = 0; i < f.getInventoryItems().size(); i++) {
                ItemStack s = f.getInventoryItems().get(i);
                if (s.isEmpty() || Market.goodFor(s) == null) continue;
                int move = s.getCount() - carrierKeeps(f, s);
                if (move <= 0) continue;
                ItemStack lot = s.copyWithCount(move);
                ItemStack left = Market.intoStores(level, here.id(), lot);
                int moved = move - left.getCount();
                if (moved <= 0) continue;
                s.shrink(moved);
                unloaded += moved;
                brought.add(Market.goodFor(lot));
                worth += Market.goodFor(lot).value() * moved;
                if (what.length() < 60) what.append(what.length() == 0 ? "" : ", ").append(moved).append(' ')
                    .append(Market.goodFor(lot).name().toLowerCase());
            }
        }
        long day = level.getDayTime() / 24000L;
        if (here != null && unloaded > 0) {
            Villages.tell(here.id(), day, "a caravan from " + Villages.name(other == null ? t.from : other.id())
                + " brought " + what);
        }
        if (!t.back && here != null && other != null) {
            // Load what the colony has plenty of and the mother is short of, for the way home.
            double back = 0;
            for (ItemStack s : load(level, here, other.id(), true, true, brought)) {
                Market.Good g = Market.goodFor(s);
                ItemStack left = f.insertItem(s);
                if (g != null) back += g.value() * (s.getCount() - left.getCount());
                if (!left.isEmpty()) Market.intoStores(level, here.id(), left);
            }
            if (t.trade) {
                // Between trading partners, the difference is paid in coin, there and then.
                int owed = (int) Math.round(worth - back);
                if (owed > 0) {
                    int paid = Ledger.takeCoins(here.id(), owed);
                    t.purse += paid;
                    Villages.tell(here.id(), day, "we paid " + Villages.name(other.id()) + "'s traders " + paid + " coins for their goods");
                } else if (owed < 0) {
                    int paid = Ledger.takeCoins(other.id(), -owed);
                    Ledger.addCoins(here.id(), paid);
                    Villages.tell(here.id(), day, Villages.name(other.id()) + " paid us " + paid + " coins for our goods");
                }
                Ledger.relate(here.id(), other.id(), 3);
            }
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
        f.trip(null);
        if (t.errand != null) {
            Envoys.home(level, f, t);
            return;
        }
        if (t.purse > 0) {
            Ledger.addCoins(t.from, t.purse);
            Villages.tell(t.from, day, "our trade caravan came home from " + Villages.name(t.to) + " with " + t.purse + " coins");
        }
        f.say("Back from " + Villages.name(t.to) + "!");
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
        release(level, f, t);
        f.trip(null);
    }
}
