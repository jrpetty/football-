package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.WagonRoute;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wagons] The trade wagons: who has one, what it carries, and the trip it makes (TradeWagon is the thing itself).
 *
 * <p><b>A town's wagon.</b> A town that trades builds a wagon at the bench once a day can spare the makings
 * (WagonKind): a handcart in the Wood Age, an open wagon from the Stone Age, a covered wagon from the Iron Age, the
 * old one broken up for its planks when the new one is made. A drawn wagon needs a stable or a trading post (its
 * shed), and a horse, a donkey, a mule or a llama of the town's own to pull it on the day. Idle, it stands parked by
 * the trading post, square to the street.
 *
 * <p><b>A trip.</b> It takes two errands: a caravan's (Caravans: a colony's load, a pact's trade, a deal's delivery:
 * the carrier's goods go into the wagon instead of a pack donkey's chest, Riding.packFor) and a shipment's
 * (Shipments: the economy's order, the goods already out of the stores; the wagon is the carrier and reports it
 * delivered or lost, and does none of the settling). Either way: the driver and the animal come to the wagon (and a
 * guard or two on a long or rich run); the goods are carried out to it a crate at a time; it drives the road to the
 * other town's yard; the goods are carried off it; the trip's own business is done there (Caravans.arrive, or
 * Shipments.delivered); and home again, unloaded the same way, it is parked and the animal led back.
 *
 * <p><b>Out of sight.</b> With no player near, a wagon on the road drops its window of loaded ground, takes its
 * animal and guards aboard (so they are kept together with it) and goes quiet; Wagons moves it on along the route
 * once a second in a few arithmetic steps, and when a player comes near or it reaches the end, its ground is loaded,
 * it is put down where it has got to, and it drives on. A restart while it is out of sight is the same.
 *
 * <p><b>Trouble.</b> A blocked road is waited out, driven round, backed out of and tried on the other side, and at
 * last left behind with a short safe nudge (unstick). Monsters near halt it and its guards get down and fight; on a
 * long run, now and then, a band waylays it. A wagon wrecked (by a band, a player, a blast) or lost with its driver
 * spills its load on the road, and its shipment is reported lost; the town builds another.
 *
 * <p>Costs: a wagon in sight does a few heightmap reads a tick, an entity query each five ticks and a monster query
 * each second; out of sight it costs a few additions a second; a parked one returns at once. The town's upkeep looks
 * at one town every five seconds.
 */
public final class Wagons {

    private Wagons() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    // ------------------------------------------------------------------ the numbers, and why

    /** No player this near (flat): the wagon goes quiet and is moved on out of sight. */
    static final double FAR = 128.0;
    /** A player this near a quiet wagon's place (or where it was left): it is put back into the world. */
    static final double NEAR = 112.0;
    /** Quiet checks in a row (one a second) before it goes quiet: not on the first second nobody is about. */
    static final int QUIET_CHECKS = 3;
    /** A quiet wagon is moved on at this share of its road pace (no hills, but no hurry either). */
    static final double FAR_PACE = 0.9;
    /** A quiet wagon that cannot be found where it was left for this many seconds is lost, with its load. */
    static final int FAR_LOST_SECONDS = 60;
    /** How long the driver, the animal and the guards have to come to the wagon before they are brought. */
    static final int FETCH_TICKS = 600;
    /** A run this long (blocks), or worth this much (coin), takes a guard; twice as long or rich, two. */
    static final double GUARD_ROAD = 250.0;
    static final int GUARD_WORTH = 64;
    /** The chance in a leg of a band on the road, at most (longer roads nearer it). */
    static final double AMBUSH_CHANCE = 0.2;
    /** Store chests further than this from the wagon: the crates are carried from a stack beside it instead. */
    static final double CARRY_REACH = 20.0;
    /** The driver missing this long (ticks): the wagon is abandoned (its load home or reported lost). */
    static final int DRIVER_GRACE = 600;
    /** Kept to so the record in the town's ledger is never more than five seconds behind. */
    static final int RECORD_EVERY = 100;

    private static final String OWN = "wagon";                // KIND|uuid
    private static final String AT = "wagon.at";              // phase|s|x|y|z|from|to|back|shipment
    private static final String LEG = "wagon.leg";            // the route, encoded
    private static final String PARK = "wagon.park";          // x,y,z,yaw
    private static final String VISIT = "wagon.visit";        // x,y,z,yaw: where visiting wagons stand
    private static final String DAY = "wagon.day";            // the last day its upkeep was looked at

    /** Who is driving which wagon, guarding which, and which animals are in harness. */
    static final Map<UUID, UUID> DRIVING = new ConcurrentHashMap<>();
    static final Map<UUID, UUID> GUARDING = new ConcurrentHashMap<>();
    static final Map<UUID, UUID> HARNESSED = new ConcurrentHashMap<>();
    /** Wagons out of sight on the road, by wagon id. */
    static final Map<UUID, Far> QUIET = new ConcurrentHashMap<>();
    /** Wagons on the road in sight, by town (to notice one whose ground went away under it, as at a restart). */
    private static final Map<UUID, Long> ON_ROAD_SEEN = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> READ = ConcurrentHashMap.newKeySet();
    private static int upkeepIndex;
    /** Tests: as if a player were always near (a wagon is never quiet). */
    private static boolean nearForTests;
    /** Tests: a band on every long leg, or never. */
    private static Boolean ambushForTests;

    /** A wagon out of sight on its route: where it has got to, and where it was left. */
    static final class Far {
        final UUID wagon, town;
        final WagonRoute route;
        double s;
        final double speed;
        final BlockPos left;
        int waiting = -1;
        /** The crew's beds at home (kept theirs while they are out of the world). */
        final List<BlockPos> beds = new ArrayList<>(3);

        Far(UUID wagon, UUID town, WagonRoute route, double s, double speed, BlockPos left) {
            this.wagon = wagon;
            this.town = town;
            this.route = route;
            this.s = s;
            this.speed = speed;
            this.left = left;
        }
    }

    public static void reset() {
        DRIVING.clear();
        GUARDING.clear();
        HARNESSED.clear();
        QUIET.clear();
        ON_ROAD_SEEN.clear();
        READ.clear();
        upkeepIndex = 0;
    }

    public static void resetForTests() {
        reset();
        nearForTests = false;
        ambushForTests = null;
    }

    public static void nearForTests(boolean on) { nearForTests = on; }
    public static void ambushForTests(@Nullable Boolean on) { ambushForTests = on; }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reset();
        Shipments.register(Wagons::carry);
    }

    // ================================================================== the server's tick: the quiet ones, the upkeep

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int t = event.getServer().getTickCount();
        if (t % 20 != 7) return;
        Guard.run("wagons", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                readQuiet(level);
                for (Far f : new ArrayList<>(QUIET.values())) quietStep(level, f);
                if (t % 100 == 7) upkeepOne(level);
                if (t % 200 == 7) noticeVanished(level);
            }
        });
    }

    // ------------------------------------------------------------------ ownership

    /** The kind of wagon this town has, or null. */
    @Nullable
    public static WagonKind owned(UUID town) {
        String s = Ledger.note(town, OWN);
        if (s == null || s.isEmpty()) return null;
        return WagonKind.byName(s.split("\\|")[0]);
    }

    @Nullable
    static UUID wagonIdOf(UUID town) {
        String s = Ledger.note(town, OWN);
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|");
        try {
            return p.length > 1 ? UUID.fromString(p[1]) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void own(UUID town, WagonKind k, UUID id) {
        Ledger.note(town, OWN, k.name() + "|" + id);
    }

    private static void disown(UUID town) {
        Ledger.note(town, OWN, "");
        Ledger.note(town, AT, "");
        Ledger.note(town, LEG, "");
    }

    /** The town's wagon, if it is in the world just now. */
    @Nullable
    static TradeWagon wagonOf(ServerLevel level, UUID town) {
        UUID id = wagonIdOf(town);
        return id == null ? null : level.getEntity(id) instanceof TradeWagon w && w.isAlive() ? w : null;
    }

    // ------------------------------------------------------------------ the yard: where it parks, where visitors stand

    /** Where the town's own wagon stands idle: by the trading post (or the stable, or the board), square to the street. */
    static Spot park(ServerLevel level, Villages.Village v) {
        return spot(level, v, PARK, null);
    }

    /** Where a wagon from elsewhere stands to unload: near the town's own yard, clear of its own wagon. */
    static Spot visit(ServerLevel level, Villages.Village v) {
        return spot(level, v, VISIT, park(level, v).pos());
    }

    record Spot(BlockPos pos, float yaw) {}

    private static Spot spot(ServerLevel level, Villages.Village v, String key, @Nullable BlockPos avoid) {
        String kept = Ledger.note(v.id(), key);
        if (kept != null && !kept.isEmpty()) {
            String[] p = kept.split(",");
            try {
                return new Spot(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), Float.parseFloat(p[3]));
            } catch (RuntimeException ignored) {
                // worked out again
            }
        }
        BlockPos anchor = yardAnchor(v);
        Spot found = null;
        if (level.isLoaded(anchor)) {
            outer:
            for (int r = 3; r <= 14; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        int x = anchor.getX() + dx, z = anchor.getZ() + dz;
                        if (avoid != null && Math.abs(x - avoid.getX()) < 4 && Math.abs(z - avoid.getZ()) < 6) continue;
                        for (boolean alongZ : new boolean[]{ true, false }) {
                            Integer y = clearPad(level, x, z, alongZ);
                            if (y == null) continue;
                            found = new Spot(new BlockPos(x, y, z), alongZ ? 0.0F : 90.0F);
                            break outer;
                        }
                    }
                }
            }
        }
        if (found == null) {
            BlockPos c = v.centre().offset(avoid == null ? 5 : -5, 0, 5);
            int y = level.isLoaded(c) ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()) : c.getY();
            return new Spot(new BlockPos(c.getX(), y, c.getZ()), 0.0F);          // not kept: tried again when the ground is in
        }
        Ledger.note(v.id(), key, found.pos().getX() + "," + found.pos().getY() + "," + found.pos().getZ() + "," + found.yaw());
        return found;
    }

    private static BlockPos yardAnchor(Villages.Village v) {
        Ledger.Building post = Villages.builtStructure(v.id(), EmeraldTrader.POST);
        if (post != null) return post.anchor();
        Stables.Stable st = Stables.stable(v.id());
        if (st != null) return st.anchor();
        return EmeraldTrader.post(v);
    }

    /** A pad three wide and five long, level within a block, dry, not a field, open above: the ground's height, or null. */
    @Nullable
    private static Integer clearPad(ServerLevel level, int x, int z, boolean alongZ) {
        int y0 = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int a = -1; a <= 1; a++) {
            for (int b = -2; b <= 2; b++) {
                int px = x + (alongZ ? a : b), pz = z + (alongZ ? b : a);
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
                if (Math.abs(y - y0) > 1) return null;
                BlockPos under = new BlockPos(px, y - 1, pz);
                BlockState g = level.getBlockState(under);
                if (!level.getFluidState(under).isEmpty() || g.getBlock() instanceof FarmBlock || g.is(Blocks.DIRT_PATH) && a == 0 && b == 0
                        || g.is(net.minecraft.tags.BlockTags.LEAVES) || g.getBlock() instanceof net.minecraft.world.level.block.FenceBlock
                        || g.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) return null;
                if (!level.getBlockState(new BlockPos(px, y + 1, pz)).isAir() || !level.getBlockState(new BlockPos(px, y + 2, pz)).isAir()) return null;
            }
        }
        return y0;
    }

    // ================================================================== building one

    /** Once a day, a town at a time: a wagon built (or the old one bettered) if it trades and can spare the makings. */
    private static void upkeepOne(ServerLevel level) {
        List<Villages.Village> here = new ArrayList<>();
        for (Villages.Village v : Villages.every()) if (v.dim().equals(level.dimension())) here.add(v);
        if (here.isEmpty()) return;
        long time = level.getDayTime() % 24000L;
        if (time < 1000 || time > 11000) return;                  // the wheelwright works by day
        Villages.Village v = here.get(Math.floorMod(upkeepIndex++, here.size()));
        long day = level.getDayTime() / 24000L;
        if (Long.toString(day).equals(Ledger.note(v.id(), DAY))) return;
        if (!level.isLoaded(v.centre())) return;
        Ledger.note(v.id(), DAY, Long.toString(day));
        build(level, v, day);
    }

    /** Does this town trade with anybody (a colony, a pact, a deal)? Only a trading town wants a wagon. */
    static boolean trades(Villages.Village v) {
        for (Map.Entry<UUID, UUID> l : Ledger.links().entrySet()) {
            if (l.getKey().equals(v.id()) || l.getValue().equals(v.id())) return true;
        }
        if (!TradeDeals.deals(v.id()).isEmpty()) return true;
        for (Villages.Village o : Villages.every()) if (!o.id().equals(v.id()) && Envoys.pact(v.id(), o.id())) return true;
        return false;
    }

    /** The best wagon this town may build now: by its age, and a drawn one only with a stable or a trading post. */
    static WagonKind buildable(Villages.Village v) {
        WagonKind best = WagonKind.forAge(Villages.age(v.id()));
        boolean shed = Stables.stable(v.id()) != null || Villages.builtStructure(v.id(), EmeraldTrader.POST) != null;
        if (best.drawn && !shed) best = WagonKind.HANDCART;
        return best;
    }

    /** Builds (or betters) the town's wagon if it should and can. Returns the wagon built, or null. */
    @Nullable
    static TradeWagon build(ServerLevel level, Villages.Village v, long day) {
        if (!trades(v)) return null;
        WagonKind want = buildable(v), have = owned(v.id());
        if (have != null && have.ordinal() >= want.ordinal()) {
            ensureParked(level, v, have);
            return null;
        }
        TradeWagon old = have == null ? null : wagonOf(level, v.id());
        if (have != null && (old == null || old.phase != TradeWagon.Phase.PARKED)) return null;     // out, or not to hand: another day
        for (WagonKind.Making m : want.makings) if (Market.stock(level, v.id(), m.what()) < m.count()) return null;
        for (WagonKind.Making m : want.makings) Crafts.take(level, v, m.what(), m.count());
        if (old != null) {
            Crafts.store(level, v, new ItemStack(Items.OAK_PLANKS, have.makings.get(0).count() / 2));   // the old one broken up
            old.discard();
        }
        TradeWagon w = spawn(level, v, want);
        Villages.tell(v.id(), day, (have == null ? "the wheelwright built the town a " : "the wheelwright built a ")
            + want.label + " for the trade road" + (have == null ? "" : ", and broke up the old " + have.label + " for its planks")
            + ", of " + want.makingsWords());
        LOG.info("[MCA-WAGON] {} built a {} ({})", Villages.name(v.id()), want.label, have == null ? "its first" : "instead of its " + have.label);
        return w;
    }

    /** A new wagon of this kind, the town's, parked in its yard. */
    @Nullable
    static TradeWagon spawn(ServerLevel level, Villages.Village v, WagonKind k) {
        TradeWagon w = WagonRegistry.TRADE_WAGON.get().create(level);
        if (w == null) return null;
        Spot p = park(level, v);
        w.town = v.id();
        w.setKind(k);
        colours(w, v.id());
        w.park = p.pos();
        w.parkYaw = p.yaw();
        w.moveTo(p.pos().getX() + 0.5, p.pos().getY(), p.pos().getZ() + 0.5, p.yaw(), 0.0F);
        w.setPhase(TradeWagon.Phase.PARKED);
        if (!level.addFreshEntity(w)) return null;
        own(v.id(), k, w.getUUID());
        return w;
    }

    /** The town's colours on the canvas and the trim: its arms' first two (Heraldry). */
    static void colours(TradeWagon w, UUID town) {
        List<DyeColor> c = FireworksMaker.townColours(town);
        int canvas = c.isEmpty() ? 0xF0EBDD : c.get(0).getTextureDiffuseColor() & 0xFFFFFF;
        int trim = c.size() > 1 ? c.get(1).getTextureDiffuseColor() & 0xFFFFFF : 0x6B4A2B;
        w.setColours(canvas, trim);
    }

    /** The wagon is the town's but not in the world where it should stand (and that ground is in): it is found there. */
    private static void ensureParked(ServerLevel level, Villages.Village v, WagonKind k) {
        UUID id = wagonIdOf(v.id());
        if (id == null || QUIET.containsKey(id) || onTheRoadRecord(v.id())) return;
        if (level.getEntity(id) != null) return;
        Spot p = park(level, v);
        if (!level.areEntitiesLoaded(ChunkPos.asLong(p.pos()))) return;
        LOG.info("[MCA-WAGON] {}'s {} was missing from its yard; set there again", Villages.name(v.id()), k.label);
        disown(v.id());
        spawn(level, v, k);
    }

    // ================================================================== a trip: setting out

    /**
     * Riding.packFor (a caravan setting out): the town's wagon carries the load, if it has one in its yard, a driver
     * (the carrier) and, for a drawn one, an animal. True if the wagon has it (no pack donkey then).
     */
    public static boolean hitch(ServerLevel level, Villages.Village v, VillageFolkEntity carrier) {
        Caravans.Trip t = carrier.trip();
        if (t == null || t.errand != null) return false;                    // an envoy goes on foot
        UUID going = t.destination();
        if (Railways.linkOpen(v.id(), going)) return false;                // the train takes it (RailCarts)
        TradeWagon w = parkedWagon(level, v);
        if (w == null) return false;
        if (!crew(level, v, w, carrier)) return false;
        w.shipment = null;
        begin(level, v, w, carrier, t);
        return true;
    }

    /** The Shipments carrier: the town's wagon takes the economy's load (already out of the stores) if it can. */
    static boolean carry(ServerLevel level, Shipments.Shipment s) {
        Villages.Village fv = Villages.get(s.from()), tv = Villages.get(s.to());
        if (fv == null || tv == null || !fv.dim().equals(level.dimension())) return false;
        TradeWagon w = parkedWagon(level, fv);
        if (w == null) return false;
        int stacks = 0;
        for (ItemStack m : s.manifest()) stacks += (m.getCount() + m.getMaxStackSize() - 1) / Math.max(1, m.getMaxStackSize());
        if (stacks > w.kind().slots) return false;                          // more than it holds: the road takes it
        VillageFolkEntity driver = Caravans.choose(fv);
        if (driver == null || !crew(level, fv, w, driver)) return false;
        for (ItemStack m : s.manifest()) {
            ItemStack rest = m.copy();
            while (!rest.isEmpty()) {
                ItemStack one = rest.split(rest.getMaxStackSize());
                ItemStack left = put(w, one);
                if (!left.isEmpty()) {                                       // cannot happen (counted above); never lost
                    Market.intoStores(level, fv.id(), left);
                }
            }
        }
        driver.clearQueue();
        Caravans.Trip t = new Caravans.Trip(fv.id(), tv.id(), new ArrayList<>(Caravans.way(fv, tv)));
        t.shipment = s.id();
        t.gainedTick = driver.tickCount;
        driver.trip(t);
        w.shipment = s.id();
        begin(level, fv, w, driver, t);
        FolkTalk.speak(driver, "A load for " + Villages.name(tv.id()) + ". I'll take the " + w.kind().label + ".");
        LOG.info("[MCA-WAGON] {}'s {} takes shipment {} to {}: {}", Villages.name(fv.id()), w.kind().label, s.id(),
            Villages.name(tv.id()), Shipments.words(s));
        return true;
    }

    /** The town's wagon, if it is in its yard and free. */
    @Nullable
    private static TradeWagon parkedWagon(ServerLevel level, Villages.Village v) {
        TradeWagon w = wagonOf(level, v.id());
        if (w == null || w.phase != TradeWagon.Phase.PARKED || w.driver != null) return null;
        return w;
    }

    /** The animal for a drawn wagon, found and held (and guards picked); false if there is none to pull it. */
    private static boolean crew(ServerLevel level, Villages.Village v, TradeWagon w, VillageFolkEntity driver) {
        if (w.kind().drawn) {
            AbstractHorse a = draught(level, v, w);
            if (a == null) {
                LOG.info("[MCA-WAGON] {}'s {} stays in the yard: no animal free to pull it", Villages.name(v.id()), w.kind().label);
                return false;
            }
            w.animal = a.getUUID();
            HARNESSED.put(a.getUUID(), w.getUUID());
        }
        w.driver = driver.getUUID();
        DRIVING.put(driver.getUUID(), w.getUUID());
        return true;
    }

    /** A horse, a mule, a donkey or a llama of the town's, grown, well and free: the strongest first. */
    @Nullable
    static AbstractHorse draught(ServerLevel level, Villages.Village v, TradeWagon w) {
        Stables.Stable st = Stables.stable(v.id());
        BlockPos around = st != null ? st.anchor() : w.blockPosition();
        AbstractHorse best = null;
        int bestRank = -1;
        for (AbstractHorse h : level.getEntitiesOfClass(AbstractHorse.class, new AABB(around).inflate(24, 6, 24),
                x -> (Stables.kind(x) || x instanceof Llama && !(x instanceof TraderLlama)) && Stables.ours(x, v.id()) && !x.isBaby()
                    && !x.isVehicle() && !x.isLeashed() && !Riding.taken(x.getUUID()) && !Stables.handled(x.getUUID())
                    && !HARNESSED.containsKey(x.getUUID()) && x.getHealth() > x.getMaxHealth() * 0.5F)) {
            int rank = h instanceof net.minecraft.world.entity.animal.horse.Horse ? 3
                : h instanceof net.minecraft.world.entity.animal.horse.Mule ? 2 : h instanceof Llama ? 0 : 1;
            if (rank > bestRank) { bestRank = rank; best = h; }
        }
        return best;
    }

    /** The trip begun: the leg's ends, a guard or two if it is long or rich, the way, and the crew sent for. */
    private static void begin(ServerLevel level, Villages.Village v, TradeWagon w, VillageFolkEntity driver, Caravans.Trip t) {
        w.from = t.from;
        w.to = t.to;
        w.back = t.back;
        Caravans.release(level, driver, t);                                // the wagon keeps its own ground awake
        Villages.Village tv = Villages.get(t.destination());
        double far = tv == null ? 0 : Math.sqrt(v.centre().distSqr(tv.centre()));
        int worth = worth(level, v.id(), w);
        int want = far > GUARD_ROAD * 2 || worth > GUARD_WORTH * 2 ? 2 : far > GUARD_ROAD || worth > GUARD_WORTH ? 1 : 0;
        if (!w.kind().drawn) want = Math.min(want, 1);
        pickGuards(v, w, want);
        w.setPhase(TradeWagon.Phase.FETCH);
        w.setRoute(null);
        record(w);
        driver.brain("to the yard for the " + w.kind().label + (w.animal != null ? ", and its " + animalWord(level, w) : ""));
        LOG.info("[MCA-WAGON] {}'s {} out for {} ({} blocks, worth {}, {} guard(s))", Villages.name(v.id()), w.kind().label,
            tv == null ? "?" : Villages.name(tv.id()), (int) far, worth, w.guards.size());
    }

    private static void pickGuards(Villages.Village v, TradeWagon w, int want) {
        if (want <= 0 || Raids.underAlarm(v.id())) return;
        List<VillageFolkEntity> free = new ArrayList<>();
        int all = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity g) || g.stationTask() != AssistantEntity.StationTask.GUARD) continue;
            all++;
            if (!g.isAlive() || g.isBaby() || g.isSleeping() || g.trip() != null || busy(g) || Patrols.escorting(g)
                    || g.getHealth() < g.getMaxHealth() * 0.7F) continue;
            free.add(g);
        }
        free.sort((a, b) -> Double.compare(a.distanceToSqr(w), b.distanceToSqr(w)));
        int spare = Math.max(0, all - 1);                                 // one stays at home
        for (VillageFolkEntity g : free) {
            if (w.guards.size() >= Math.min(want, spare)) break;
            w.guards.add(g.getUUID());
            GUARDING.put(g.getUUID(), w.getUUID());
            g.brain("riding guard on the " + w.kind().label);
        }
    }

    // ================================================================== the wagon's tick: each phase

    /** TradeWagon.tick (server): whatever its phase asks. */
    static void tick(ServerLevel level, TradeWagon w) {
        if (w.town == null) { w.discard(); return; }
        if (w.tickCount % 100 == 13 && !w.getUUID().equals(wagonIdOf(w.town))) {
            stale(level, w);
            return;
        }
        if (w.phase != TradeWagon.Phase.PARKED && w.tickCount % 20 == 5) crewKnown(w);
        if (w.held) { held(level, w); return; }
        switch (w.phase) {
            case PARKED -> parked(level, w);
            case FETCH -> fetch(level, w);
            case LOADING -> loading(level, w, true);
            case ROAD -> road(level, w);
            case UNLOADING -> loading(level, w, false);
            case FAR -> stillQuiet(level, w);
        }
    }

    /** Held for the pictures: the animal in the shafts, the driver on the bench, or (loading) carrying crates round and round. */
    private static void held(ServerLevel level, TradeWagon w) {
        AbstractHorse a = animal(level, w);
        if (a != null) holdInShafts(level, w, a);
        VillageFolkEntity d = driver(level, w);
        if (d == null) return;
        if (w.phase == TradeWagon.Phase.LOADING) {
            if (w.phaseTicks >= TradeWagon.LOAD_TICKS - 1) w.phaseTicks = TradeWagon.LOAD_TICKS / 2;   // the second half, over and over
            loadingWalk(level, w, d, true);
        } else if (d.getVehicle() != w && w.kind().drawn) {
            boardIfNear(w, d, true);
        }
    }

    /** Its crew written into the lists the folk's own ticks look at (empty after a restart till the wagon is back). */
    private static void crewKnown(TradeWagon w) {
        if (w.driver != null) DRIVING.putIfAbsent(w.driver, w.getUUID());
        if (w.animal != null) HARNESSED.putIfAbsent(w.animal, w.getUUID());
        for (UUID g : w.guards) GUARDING.putIfAbsent(g, w.getUUID());
    }

    /** A copy of the town's wagon that is not the one on its books (a duplicate after a mishap): its load home, and gone. */
    private static void stale(ServerLevel level, TradeWagon w) {
        Villages.Village v = Villages.get(w.town);
        for (int i = 0; i < w.cargo.getContainerSize(); i++) {
            ItemStack s = w.cargo.removeItemNoUpdate(i);
            if (!s.isEmpty() && v != null) Crafts.store(level, v, s);
        }
        LOG.info("[MCA-WAGON] a stray wagon of {} put away", v == null ? "?" : Villages.name(v.id()));
        w.discard();
    }

    private static void parked(ServerLevel level, TradeWagon w) {
        if (w.tickCount % 40 != 0) return;
        if (w.park != null && w.distanceToSqr(Vec3.atBottomCenterOf(w.park)) > 4.0 && !level.isClientSide) {
            w.moveTo(w.park.getX() + 0.5, w.park.getY(), w.park.getZ() + 0.5, w.parkYaw, 0.0F);
        }
        if (w.getYRot() != w.parkYaw) w.setYRot(w.parkYaw);
        if (w.shownFill() != w.realFill()) w.setShownFill(w.realFill());
        releaseWindow(level, w);
    }

    /** The driver, the animal and the guards come to it; when all are there (or brought), the loading begins. */
    private static void fetch(ServerLevel level, TradeWagon w) {
        VillageFolkEntity d = driver(level, w);
        if (d == null) { driverGone(level, w); return; }
        keepWindow(level, w);
        boolean late = w.phaseTicks > FETCH_TICKS;
        AbstractHorse a = animal(level, w);
        boolean pulled = !w.kind().drawn || a != null && harnessed(a);
        if (w.kind().drawn) {
            if (a == null) {
                abandonTrip(level, w, "the animal to pull it could not be found");
                return;
            }
            if (!harnessed(a)) {
                Vec3 hitch = w.spot(level, TradeWagon.HITCH, 0);
                if (late || a.position().distanceToSqr(hitch) < 3.0 * 3.0) {
                    harness(a, w);
                    pulled = true;
                } else if (w.phaseTicks % 20 == 1) {
                    a.getNavigation().moveTo(hitch.x, hitch.y, hitch.z, 1.0D);
                }
            } else {
                holdInShafts(level, w, a);
            }
        }
        boolean seated = atHisPlace(level, w, d, late);
        int aboard = 0;
        for (VillageFolkEntity g : guards(level, w)) if (g.getVehicle() == w || boardIfNear(w, g, late)) aboard++;
        if (pulled && seated && (aboard == guards(level, w).size() || w.phaseTicks > FETCH_TICKS + 200)) startLoading(level, w);
    }

    /** The driver in its place: on the bench (or between a handcart's handles); walked there, or brought if late. */
    private static boolean atHisPlace(ServerLevel level, TradeWagon w, VillageFolkEntity d, boolean late) {
        if (!w.kind().drawn) {
            Vec3 at = w.spot(level, TradeWagon.HANDLES, 0);
            if (late || d.position().distanceToSqr(at) < 2.0 * 2.0) {
                holdHandles(level, w, d);
                return true;
            }
            if (w.phaseTicks % 20 == 2) d.walkTo(BlockPos.containing(at), 0.9D);
            return false;
        }
        return d.getVehicle() == w || boardIfNear(w, d, late);
    }

    /** On, if it is at the wagon's side (or it is late: brought). */
    private static boolean boardIfNear(TradeWagon w, VillageFolkEntity f, boolean late) {
        if (f.getVehicle() == w) return true;
        if (late || f.distanceToSqr(w) < 3.0 * 3.0) {
            if (f.isPassenger()) f.stopRiding();
            boolean on = f.startRiding(w, true);
            if (on) {
                f.getNavigation().stop();
                f.setPose(Pose.SITTING);
            }
            return on;
        }
        if (w.phaseTicks % 20 == 3 || f.getNavigation().isDone()) f.walkTo(w.blockPosition(), 0.9D);
        return false;
    }

    private static void startLoading(ServerLevel level, TradeWagon w) {
        VillageFolkEntity d = driver(level, w);
        if (d != null && w.shipment == null) loadFromCarrier(d, w);
        w.cargoChanged();
        if (w.route == null) w.setRoute(routeFor(level, w));
        if (w.route == null) {
            abandonTrip(level, w, "no way could be found");
            return;
        }
        Ledger.note(w.town, LEG, w.route.encode());
        if (w.realFill() == 0) {                                         // coin only, nothing to carry out
            w.setPhase(TradeWagon.Phase.ROAD);
            return;
        }
        w.setShownFill(0);
        w.setPhase(TradeWagon.Phase.LOADING);
        if (d != null) {
            if (d.getVehicle() == w) d.stopRiding();
            d.setPose(Pose.STANDING);
            FolkTalk.speak(d, w.back ? "Their goods aboard, and home!" : "Load her up!");
        }
    }

    /**
     * Loading (or unloading): the driver walks to the stores (the nearest store chest, or a stack set beside the
     * wagon when the stores are far) and back with a crate, twice; the load in the bed rises (or falls) as it goes.
     * Then on the road; or, unloading, the leg's business done there.
     */
    private static void loading(ServerLevel level, TradeWagon w, boolean in) {
        VillageFolkEntity d = driver(level, w);
        if (d == null) { driverGone(level, w); return; }
        keepWindow(level, w);
        AbstractHorse a = animal(level, w);
        if (a != null) holdInShafts(level, w, a);
        int t = w.phaseTicks;
        int fill = w.realFill();
        loadingWalk(level, w, d, in);
        if (t < TradeWagon.LOAD_TICKS) return;
        w.setCarrying(null);
        if (in) {
            w.setShownFill(fill);
            if (!w.kind().drawn) holdHandles(level, w, d);
            else boardIfNear(w, d, true);
            w.setPhase(TradeWagon.Phase.ROAD);
            record(w);
        } else {
            w.setShownFill(0);
            legDone(level, w, d);
        }
    }

    /**
     * Unloaded at the end of a leg: the trip's business there (Caravans.arrive: a caravan's selling and buying or a
     * deal's exchange, or a shipment reported delivered), which turns the wagon for home or, home, parks it.
     */
    private static void legDone(ServerLevel level, TradeWagon w, VillageFolkEntity d) {
        Caravans.Trip t = d.trip();
        if (t == null) {
            mindTrip(level, w, d);
            t = d.trip();
            if (t == null) return;                                       // given up (mindTrip): parked
        }
        t.at = t.way.size();
        Caravans.arrive(level, d, t);
        if (w.isAlive() && w.phase == TradeWagon.Phase.UNLOADING) {
            // The trip went its own way at that end (an envoy's errand, a business with no way home): the wagon home.
            if (d.trip() == null) parkUp(level, w);
            else abandonTrip(level, w, "its trip went on without it");
        }
    }

    /** The driver's walks with the crates, and the load rising (or falling) a quarter at a time as each comes. */
    private static void loadingWalk(ServerLevel level, TradeWagon w, VillageFolkEntity d, boolean in) {
        int t = w.phaseTicks;
        int fill = w.realFill();
        int quarters = Math.min(4, t * 4 / TradeWagon.LOAD_TICKS);
        w.setShownFill(in ? fill * quarters / 4 : fill * (4 - quarters) / 4);
        BlockPos stores = storesNear(level, w);
        Vec3 tail = w.spot(level, -TradeWagon.HALF_LENGTH - 1.0, 0);
        int leg = t / (TradeWagon.LOAD_TICKS / 4);                       // four walks: there, back, there, back
        boolean toStores = leg % 2 == 0;
        // In: empty-handed to the stores, a crate back to the wagon. Out: a crate to the stores, empty-handed back.
        boolean laden = in != toStores;
        w.setCarrying(laden ? d : null);
        if (t % 10 == 0) d.walkTo(toStores ? stores : BlockPos.containing(tail), 0.9D);
    }

    /** The nearest store chest within reach of the wagon, or a spot beside it where the crates are stacked. */
    private static BlockPos storesNear(ServerLevel level, TradeWagon w) {
        UUID here = w.back ? w.from : w.to;
        if (w.phase == TradeWagon.Phase.LOADING) here = w.back ? w.to : w.from;
        BlockPos best = null;
        double bd = CARRY_REACH * CARRY_REACH;
        if (here != null) {
            for (BlockPos p : Villages.storeChests(level, here)) {
                double d = p.distSqr(w.blockPosition());
                if (d < bd) { bd = d; best = p; }
            }
        }
        return best != null ? best : BlockPos.containing(w.spot(level, -TradeWagon.HALF_LENGTH - 1.0, 4.0));
    }

    /** On the road: the crew kept in place, the drive itself, and the checks each second. */
    private static void road(ServerLevel level, TradeWagon w) {
        VillageFolkEntity d = driver(level, w);
        if (d == null) { driverGone(level, w); return; }
        AbstractHorse a = animal(level, w);
        if (w.kind().drawn && a == null) { animalLost(level, w, d); return; }
        if (a != null) {
            if (a.getVehicle() == w) a.stopRiding();                        // back from out of sight
            holdInShafts(level, w, a);
        }
        if (!w.kind().drawn) {
            if (d.getVehicle() == w) d.stopRiding();
            holdHandles(level, w, d);
        }
        boolean driverOn = !w.kind().drawn || d.getVehicle() == w || boardIfNear(w, d, w.phaseTicks % 200 == 199);
        List<VillageFolkEntity> gs = guards(level, w);
        if (w.tickCount % 20 == 0) {
            w.threatened = threat(level, w) != null;
            if (!w.threatened) for (VillageFolkEntity g : gs) boardIfNear(w, g, false);
            if (d.getVehicle() == w && d.getPose() != Pose.SITTING) d.setPose(Pose.SITTING);
            keepWindow(level, w);
            mindTrip(level, w, d);
            maybeAmbush(level, w, d);
            if (goQuiet(level, w)) return;
        }
        boolean guardsOn = true;
        for (VillageFolkEntity g : gs) if (g.getVehicle() != w) guardsOn = false;
        w.drive(level, driverOn && (guardsOn || !w.threatened && w.phaseTicks % 400 > 200));
        if (w.tickCount % RECORD_EVERY == 0) record(w);
        if (w.route != null && w.s >= w.route.length() - 0.05) {
            w.speedNow = 0;
            if (w.realFill() == 0) {                                     // nothing aboard to carry off: its business at once
                legDone(level, w, d);
                return;
            }
            w.setPhase(TradeWagon.Phase.UNLOADING);
            if (d.getVehicle() == w) d.stopRiding();
            d.setPose(Pose.STANDING);
            Villages.Village there = Villages.get(w.back ? w.from : w.to);
            if (there != null) FolkTalk.speak(d, w.back ? "Home! Let's get her unloaded." : "Whoa there. " + Villages.name(there.id()) + ", at last.");
        }
    }

    /** The trip's own count of steps kept up with the wagon, and the trip given back to a driver that lost it (a restart). */
    private static void mindTrip(ServerLevel level, TradeWagon w, VillageFolkEntity d) {
        Caravans.Trip t = d.trip();
        if (t == null) {
            String line = w.from == null ? null : Ledger.note(w.from, "trip/" + d.getUUID());
            if (line != null && !line.isEmpty()) TradeTrips.restore(level, d, line);
            t = d.trip();
            if (t == null) {
                abandonTrip(level, w, "its driver had forgotten where it was going");
                return;
            }
        }
        if (w.route != null) {
            int passed = w.route.stepPassed(w.s);
            if (passed >= 0) t.at = Math.max(t.at, Math.min(t.way.size() - 1, passed + 1));
            t.gainedTick = d.tickCount;
        }
    }

    // ------------------------------------------------------------------ the crew in place

    /** The animal walks in the shafts: set there each tick, facing the way the wagon does. */
    private static void holdInShafts(ServerLevel level, TradeWagon w, AbstractHorse a) {
        if (a.getVehicle() == w) return;
        Vec3 at = w.spot(level, TradeWagon.HITCH, 0);
        if (!a.isNoAi()) harness(a, w);
        a.moveTo(at.x, at.y, at.z, w.getYRot(), 0.0F);
        a.setYHeadRot(w.getYRot());
        a.yBodyRot = w.getYRot();
        a.setDeltaMovement(Vec3.ZERO);
    }

    /** The handcart's driver between its handles, walking it along. */
    private static void holdHandles(ServerLevel level, TradeWagon w, VillageFolkEntity d) {
        Vec3 at = w.spot(level, TradeWagon.HANDLES, 0);
        d.getNavigation().stop();
        d.moveTo(at.x, at.y, at.z, w.getYRot(), 0.0F);
        d.setYHeadRot(w.getYRot());
        d.yBodyRot = w.getYRot();
        if (d.getPose() == Pose.SITTING) d.setPose(Pose.STANDING);
    }

    private static boolean harnessed(AbstractHorse a) {
        return a.isNoAi() && a.getTags().contains("mca_harnessed");
    }

    /** Into the shafts: it walks where the wagon sets it now, not where it would wander. */
    private static void harness(AbstractHorse a, TradeWagon w) {
        a.getNavigation().stop();
        a.setNoAi(true);
        a.addTag("mca_harnessed");
        HARNESSED.put(a.getUUID(), w.getUUID());
    }

    /** Out of the shafts, its own again (and led towards the stable). */
    private static void unharness(ServerLevel level, @Nullable AbstractHorse a, @Nullable UUID town) {
        if (a == null) return;
        HARNESSED.remove(a.getUUID());
        if (a.getVehicle() instanceof TradeWagon) a.stopRiding();
        a.removeTag("mca_harnessed");
        a.setNoAi(false);
        Stables.Stable st = town == null ? null : Stables.stable(town);
        if (st != null) {
            Vec3 aisle = st.at(0, 1);
            a.getNavigation().moveTo(aisle.x, aisle.y, aisle.z, 1.0D);
        }
    }

    /** An animal that comes back into the world still marked as in harness, with no wagon holding it: let go. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractHorse a) || !a.getTags().contains("mca_harnessed")) return;
        if (HARNESSED.containsKey(a.getUUID()) || a.getVehicle() instanceof TradeWagon) return;
        // Its wagon may come in a moment later (the same chunk): it takes it back into the shafts on its next tick.
        a.removeTag("mca_harnessed");
        a.setNoAi(false);
    }

    // ------------------------------------------------------------------ the folk's side (VillageFolkEntity's tick)

    /** Caravans.drive: true while a wagon has this carrier (the wagon walks it, seats it and drives). */
    public static boolean drive(VillageFolkEntity f, ServerLevel level, Caravans.Trip t) {
        UUID id = DRIVING.get(f.getUUID());
        if (id == null) return false;
        if (level.getEntity(id) instanceof TradeWagon w && w.isAlive()) {
            w.driverMissingSince = -1;
            return true;
        }
        if (QUIET.containsKey(id)) return true;
        // The wagon has gone from the world under it (not quiet, not wrecked): the carrier walks on.
        DRIVING.remove(f.getUUID());
        LOG.info("[MCA-WAGON] {} lost its wagon; it walks on", f.displayNameCap());
        return false;
    }

    /** VillageFolkEntity's tick: a guard riding with a wagon. True while that is its work (it rides, or gets down to fight). */
    public static boolean escort(VillageFolkEntity g, ServerLevel level) {
        UUID id = GUARDING.get(g.getUUID());
        if (id == null) return false;
        if (!(level.getEntity(id) instanceof TradeWagon w) || !w.isAlive() || !w.guards.contains(g.getUUID())
                || w.phase == TradeWagon.Phase.PARKED) {
            if (!QUIET.containsKey(id)) {
                GUARDING.remove(g.getUUID());
                if (g.getVehicle() instanceof TradeWagon) g.stopRiding();
                return false;
            }
            return true;
        }
        if (w.threatened) {
            Monster m = threat(level, w);
            if (m != null) {
                if (g.getVehicle() == w) {
                    g.stopRiding();
                    g.setPose(Pose.STANDING);
                }
                if (g.getTarget() != m) g.setTarget(m);
            }
        }
        return true;
    }

    /** Is this folk driving a wagon or riding guard on one? */
    public static boolean busy(VillageFolkEntity f) {
        return DRIVING.containsKey(f.getUUID()) || GUARDING.containsKey(f.getUUID());
    }

    /** Is this folk riding guard on a wagon? */
    public static boolean guarding(VillageFolkEntity g) {
        return GUARDING.containsKey(g.getUUID());
    }

    /** The beds of a town's wagon crew while the wagon is out of sight (and they with it): still theirs. */
    public static java.util.Collection<BlockPos> awayBeds(@Nullable UUID village) {
        if (village == null || QUIET.isEmpty()) return List.of();
        List<BlockPos> out = new ArrayList<>();
        for (Far f : QUIET.values()) if (f.town.equals(village)) out.addAll(f.beds);
        return out;
    }

    /** Is this folk sat on a wagon's bench (Park leaves it sat)? */
    public static boolean seated(Entity f) {
        return f.getVehicle() instanceof TradeWagon;
    }

    /** Is this animal in a wagon's shafts (no other work for it)? */
    public static boolean harnessed(UUID animal) {
        return HARNESSED.containsKey(animal);
    }

    /** Is a wagon on the road between these two towns, in sight or out of it? */
    public static boolean between(UUID a, UUID b) {
        for (UUID town : new UUID[]{ a, b }) {
            String at = Ledger.note(town, AT);
            if (at == null || at.isEmpty()) continue;
            String[] p = at.split("\\|", -1);
            if (p.length <= 7 || p[0].equals("PARKED")) continue;
            String sa = a.toString(), sb = b.toString();
            if (p[5].equals(sa) && p[6].equals(sb) || p[5].equals(sb) && p[6].equals(sa)) return true;
        }
        return false;
    }

    /** Is something one of this wagon's own: its driver, its guards, its animal? */
    static boolean ofConvoy(TradeWagon w, Entity e) {
        UUID u = e.getUUID();
        return u.equals(w.driver) || u.equals(w.animal) || w.guards.contains(u);
    }

    // ------------------------------------------------------------------ Caravans.arrive's hooks

    /** The load out of the wagon onto the carrier's back, for the trip's business at this end (what does not fit stays aboard). */
    public static void unpack(VillageFolkEntity f, ServerLevel level) {
        TradeWagon w = wagonFor(level, f);
        if (w == null || w.shipment != null) return;
        for (int i = 0; i < w.cargo.getContainerSize(); i++) {
            ItemStack s = w.cargo.getItem(i);
            if (s.isEmpty()) continue;
            w.cargo.setItem(i, f.insertGiven(s.copy()));
        }
        w.cargoChanged();
    }

    /** The carrier's goods (what the market deals in, and its crates) into the wagon's bed, less what a carrier keeps by it. */
    static int loadFromCarrier(VillageFolkEntity f, TradeWagon w) {
        int moved = 0;
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || Market.goodFor(s) == null && !Crates.isCrate(s)) continue;
            int move = Crates.isCrate(s) ? s.getCount() : s.getCount() - Caravans.carrierKeeps(f, s);
            if (move <= 0) continue;
            ItemStack left = put(w, s.copyWithCount(move));
            int in = move - left.getCount();
            if (in <= 0) continue;
            s.shrink(in);
            if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
            moved += in;
        }
        w.cargoChanged();
        return moved;
    }

    /** Into the bed, onto part stacks first, within its kind's room. What does not fit. */
    static ItemStack put(TradeWagon w, ItemStack stack) {
        ItemStack s = stack.copy();
        int room = w.kind().slots;
        for (int i = 0; i < room && !s.isEmpty(); i++) {
            ItemStack in = w.cargo.getItem(i);
            if (in.isEmpty() || !ItemStack.isSameItemSameComponents(in, s)) continue;
            int k = Math.min(s.getCount(), in.getMaxStackSize() - in.getCount());
            if (k <= 0) continue;
            in.grow(k);
            s.shrink(k);
        }
        for (int i = 0; i < room && !s.isEmpty(); i++) {
            if (!w.cargo.getItem(i).isEmpty()) continue;
            w.cargo.setItem(i, s.split(s.getMaxStackSize()));
        }
        return s;
    }

    /**
     * Caravans.arrive, out at the other town and its business done: the goods for home (and what was not sold) into the
     * wagon and the way home taken; loaded the same way, crate by crate, if there is anything to load.
     */
    static void turnHome(ServerLevel level, VillageFolkEntity f, Caravans.Trip t) {
        TradeWagon w = wagonFor(level, f);
        if (w == null) return;
        if (w.shipment == null) loadFromCarrier(f, w);
        w.back = true;
        w.shipment = null;
        w.setRoute(routeFor(level, w));
        if (w.route == null) {
            abandonTrip(level, w, "no way home could be found");
            return;
        }
        Ledger.note(w.town, LEG, w.route.encode());
        w.cargoChanged();
        if (w.realFill() > 0) {
            w.setShownFill(0);
            w.setPhase(TradeWagon.Phase.LOADING);
        } else {
            if (w.kind().drawn) boardIfNear(w, f, true);
            w.setPhase(TradeWagon.Phase.ROAD);
        }
        record(w);
    }

    /** Caravans.arrive, home: the animal led back to the stable, the guards to their posts, the wagon parked. */
    static void home(VillageFolkEntity f, ServerLevel level) {
        TradeWagon w = wagonFor(level, f);
        if (w != null) home(w, level);
    }

    /** Home: anything left aboard into the stores, and parked. */
    static void home(TradeWagon w, ServerLevel level) {
        // Anything left aboard (the stores were full) goes into them now, or onto the ground at the heart: never lost.
        Villages.Village v = Villages.get(w.town);
        for (int i = 0; i < w.cargo.getContainerSize(); i++) {
            ItemStack s = w.cargo.removeItemNoUpdate(i);
            if (!s.isEmpty() && v != null) Crafts.store(level, v, s);
        }
        parkUp(level, w);
    }

    /** Off the trip: the crew let go, the wagon squared up in its yard. */
    private static void parkUp(ServerLevel level, TradeWagon w) {
        unharness(level, animal(level, w), w.town);
        for (Entity p : new ArrayList<>(w.getPassengers())) {
            p.stopRiding();
            if (p instanceof VillageFolkEntity vf) vf.setPose(Pose.STANDING);
        }
        if (w.driver != null) DRIVING.remove(w.driver);
        for (UUID g : w.guards) GUARDING.remove(g);
        w.guards.clear();
        w.driver = null;
        w.animal = null;
        w.shipment = null;
        w.from = w.to = null;
        w.back = false;
        w.setRoute(null);
        w.setCarrying(null);
        w.cargoChanged();
        w.setShownFill(w.realFill());
        w.setPhase(TradeWagon.Phase.PARKED);
        releaseWindow(level, w);
        if (w.town != null) {
            Villages.Village v = Villages.get(w.town);
            if (v != null) {
                Spot p = park(level, v);
                w.park = p.pos();
                w.parkYaw = p.yaw();
                w.moveTo(p.pos().getX() + 0.5, p.pos().getY(), p.pos().getZ() + 0.5, p.yaw(), 0.0F);
            }
            Ledger.note(w.town, LEG, "");
            record(w);
        }
    }

    /**
     * Caravans.arrive for a shipment's trip. Out at the other town: the load reported delivered (the economy stores
     * it and settles; the wagon only carried it), and home empty. Home: parked. One report, ever: a shipment already
     * settled (the road brought it in when the wagon was overdue) is not delivered twice.
     */
    static void shipmentLeg(ServerLevel level, VillageFolkEntity f, Caravans.Trip t) {
        TradeWagon w = wagonFor(level, f);
        long day = level.getDayTime() / 24000L;
        if (!t.back) {
            UUID id = t.shipment;
            List<ItemStack> aboard = new ArrayList<>();
            if (w != null) {
                for (int i = 0; i < w.cargo.getContainerSize(); i++) {
                    ItemStack s = w.cargo.removeItemNoUpdate(i);
                    if (!s.isEmpty()) aboard.add(s);
                }
                w.cargoChanged();
            }
            if (id != null && Shipments.get(id) != null) {
                Shipments.delivered(level, id, aboard);
                FolkTalk.speak(f, "Your load from " + Villages.name(t.from) + ", all present!");
            } else if (!aboard.isEmpty()) {
                LOG.info("[MCA-WAGON] shipment {} was settled already; the wagon's copy of it is not delivered twice", id);
            }
            Villages.Village here = Villages.get(t.to), home = Villages.get(t.from);
            t.back = true;
            if (here != null && home != null) {
                t.way.clear();
                t.way.addAll(Caravans.way(here, home));
            }
            t.at = 0;
            t.gainedTick = f.tickCount;
            if (w != null) turnHome(level, f, t);
            return;
        }
        TradeTrips.over(f);
        f.trip(null);
        home(f, level);
        Villages.tell(t.from, day, "our " + (w == null ? "wagon" : w.kind().label) + " came home from " + Villages.name(t.to));
        f.say("Back from " + Villages.name(t.to) + "!");
    }

    /** Caravans.abandon (the carrier died or was lost on the road): the wagon lost with it, its load spilt where it stands. */
    public static void lost(VillageFolkEntity f, ServerLevel level) {
        UUID id = DRIVING.remove(f.getUUID());
        if (id == null) return;
        if (level.getEntity(id) instanceof TradeWagon w && w.isAlive()) {
            wreck(level, w, "lost with its driver, " + f.displayNameCap());
            return;
        }
        Far q = QUIET.remove(id);
        if (q != null) lostQuiet(level, q, "lost with its driver, " + f.displayNameCap());
    }

    // ================================================================== the way

    /**
     * The leg's route: from where the wagon stands, by a found path out to the town's road (its avenue, then the road
     * between the towns: Caravans.way, each point tagged with its step), to the other town's yard. The last stretch in
     * town is found again (unstick) if the road to the yard is blocked.
     */
    @Nullable
    static WagonRoute routeFor(ServerLevel level, TradeWagon w) {
        Villages.Village a = Villages.get(w.back ? w.to : w.from), b = Villages.get(w.back ? w.from : w.to);
        if (a == null || b == null) return null;
        List<BlockPos> way = Caravans.way(a, b);
        Spot end = b.id().equals(w.town) ? park(level, b) : visit(level, b);
        List<double[]> pts = new ArrayList<>();
        List<Integer> steps = new ArrayList<>();
        pts.add(new double[]{ w.getX(), w.getZ() });
        steps.add(-1);
        // Join the way at its nearest point to the wagon (among the first stretch: the avenue), by a found path.
        int join = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < Math.min(way.size(), 8); i++) {
            double d = way.get(i).distSqr(w.blockPosition());
            if (d < bd) { bd = d; join = i; }
        }
        if (!way.isEmpty()) {
            for (double[] p : pathTo(level, w, way.get(join))) { pts.add(p); steps.add(-1); }
            for (int i = join; i < way.size() - 1; i++) {
                pts.add(new double[]{ way.get(i).getX() + 0.5, way.get(i).getZ() + 0.5 });
                steps.add(i);
            }
        }
        pts.add(new double[]{ end.pos().getX() + 0.5, end.pos().getZ() + 0.5 });
        steps.add(way.size() - 1);
        double[] xs = new double[pts.size()], zs = new double[pts.size()];
        int[] st = new int[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            xs[i] = pts.get(i)[0];
            zs[i] = pts.get(i)[1];
            st[i] = steps.get(i);
        }
        return new WagonRoute(xs, zs, st);
    }

    /** A walked path from the wagon to there (the animal's or the driver's own finding), as points; empty if none. */
    private static List<double[]> pathTo(ServerLevel level, TradeWagon w, BlockPos to) {
        List<double[]> out = new ArrayList<>();
        Mob finder = animal(level, w);
        if (finder == null) finder = driver(level, w);
        if (finder == null || to.distSqr(w.blockPosition()) > 64 * 64) return out;
        Path p = finder.getNavigation().createPath(to, 1);
        if (p == null || !p.canReach()) return out;
        for (int i = 2; i < p.getNodeCount(); i += 2) out.add(new double[]{ p.getNode(i).x + 0.5, p.getNode(i).z + 0.5 });
        return out;
    }

    /** No road for a wagon at (x, z) from ground at {@code y}: a wall or a house, an edge, open water, a field. */
    static boolean noRoad(ServerLevel level, double x, double z, double y) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return false;
        int gy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        if (gy - y > TradeWagon.STEP || y - gy > TradeWagon.DROP) return true;
        BlockPos under = new BlockPos(bx, gy - 1, bz);
        BlockState g = level.getBlockState(under);
        if (!level.getFluidState(under).isEmpty()) return true;              // no bridge
        if (g.getBlock() instanceof FarmBlock) return true;                  // a field
        BlockState on = level.getBlockState(under.above());
        return on.getBlock() instanceof net.minecraft.world.level.block.CropBlock || on.getBlock() instanceof net.minecraft.world.level.block.StemBlock
            || on.is(net.minecraft.tags.BlockTags.SAPLINGS);              // a crop or a young tree (flowers and grass are driven over)
    }

    /**
     * A wagon held up too long: the next thing to try. First a way round (a path found to the road beyond); then back
     * up and try the other side of the road; at last a short safe nudge on, to the first clear spot ahead.
     */
    static void unstick(ServerLevel level, TradeWagon w, String why) {
        if (w.route == null) return;
        long now = level.getGameTime();
        w.bestTick = now;
        w.blockedTicks = 0;
        w.giveWayTicks = 0;
        int stage = ++w.stage;
        String did;
        if (stage == 1 && reroute(level, w)) {
            did = "drove round";
        } else if (stage <= 2) {
            w.stage = 2;
            w.reverseUntil = now + 40;
            w.lane = -w.lane;
            w.otherLaneUntilS = (long) Math.min(w.route.length(), w.s + 16);
            did = "backed up to try the other side of the road";
        } else {
            nudge(level, w);
            w.stage = 0;
            did = "was nudged on past it";
        }
        LOG.info("[MCA-WAGON] {}'s {} held up ({}) at {}: {}", w.town == null ? "?" : Villages.name(w.town), w.kind().label, why,
            w.blockPosition().toShortString(), did);
    }

    /** A way round: a path found from here to the road sixteen blocks on, spliced in if it is a road for a wagon. */
    private static boolean reroute(ServerLevel level, TradeWagon w) {
        double toS = Math.min(w.route.length(), w.s + 16);
        BlockPos target = BlockPos.containing(w.route.xAt(toS), w.getY(), w.route.zAt(toS));
        List<double[]> path = pathTo(level, w, target);
        if (path.size() < 2) return false;
        double y = w.getY();
        for (double[] p : path) {
            double gy = TradeWagon.ground(level, p[0], p[1], y);
            if (noRoad(level, p[0], p[1], y)) return false;
            y = gy;
        }
        double[] xs = new double[path.size()], zs = new double[path.size()];
        for (int i = 0; i < path.size(); i++) { xs[i] = path.get(i)[0]; zs[i] = path.get(i)[1]; }
        double x = w.getX(), z = w.getZ();
        w.route = w.route.spliced(w.s, toS, xs, zs);
        w.s = w.route.nearest(x, z);
        w.bestS = w.s;
        if (w.town != null) Ledger.note(w.town, LEG, w.route.encode());
        return true;
    }

    /** On to the first clear spot within thirty-two blocks ahead (or the end of that), the crew with it. */
    private static void nudge(ServerLevel level, TradeWagon w) {
        double y = w.getY(), to = Math.min(w.route.length(), w.s + 32);
        for (double s = w.s + 4; s <= w.route.length(); s += 2) {
            if (s > w.s + 32) break;
            double x = w.route.xAt(s), z = w.route.zAt(s);
            double gy = TradeWagon.ground(level, x, z, y);
            if (!noRoad(level, x, z, gy) && !noRoad(level, w.route.xAt(s + 2), w.route.zAt(s + 2), gy)) { to = s; break; }
        }
        w.s = to;
        w.bestS = to;
        w.lane = TradeWagon.LANE;
        w.speedNow = 0;
        w.place(level);
        w.teleportTo(w.getX(), w.getY(), w.getZ());
    }

    // ================================================================== trouble on the road

    @Nullable
    private static Monster threat(ServerLevel level, TradeWagon w) {
        Monster best = null;
        double bd = TradeWagon.THREAT_RANGE * TradeWagon.THREAT_RANGE;
        for (Monster m : level.getEntitiesOfClass(Monster.class, w.getBoundingBox().inflate(TradeWagon.THREAT_RANGE, 6, TradeWagon.THREAT_RANGE),
                Monster::isAlive)) {
            double d = m.distanceToSqr(w);
            if (d < bd) { bd = d; best = m; }
        }
        return best;
    }

    /** Once a leg, a third of the way to two thirds, on a long road: now and then a band waylays it. */
    private static void maybeAmbush(ServerLevel level, TradeWagon w, VillageFolkEntity d) {
        if (w.ambushRolled || w.route == null || w.route.length() < 150) return;
        double frac = w.s / w.route.length();
        if (frac < 0.33 || frac > 0.67) return;
        w.ambushRolled = true;
        double chance = AMBUSH_CHANCE * Math.min(1.0, w.route.length() / 600.0);
        boolean comes = ambushForTests != null ? ambushForTests : level.getRandom().nextDouble() < chance;
        if (!comes) return;
        int n = 2 + w.guards.size();
        Villages.Age age = Villages.age(w.town);
        int set = 0;
        for (int i = 0; i < n; i++) {
            double s = Math.min(w.route.length(), w.s + 14 + i * 2);
            double side = (i % 2 == 0 ? 1 : -1) * (5 + i);
            double head = w.route.heading(s, 2.0);
            double x = w.route.xAt(s) - Math.sin(head) * side, z = w.route.zAt(s) + Math.cos(head) * side;
            Mob m = Raids.raider(level, age, i);
            if (m == null) continue;
            double y = TradeWagon.ground(level, x, z, w.getY());
            m.moveTo(x, y, z, level.getRandom().nextFloat() * 360.0F, 0.0F);
            m.addTag("mca_raider");
            m.addTag("mca_waylayer");
            m.setTarget(d);
            if (level.addFreshEntity(m)) set++;
        }
        if (set == 0) return;
        long day = level.getDayTime() / 24000L;
        Villages.tell(w.town, day, "our " + w.kind().label + " was waylaid on the road by " + set + " raiders"
            + (w.guards.isEmpty() ? "" : "; its guards got down to fight"));
        FolkTalk.speak(d, w.guards.isEmpty() ? "Raiders! Help, somebody!" : "Raiders on the road! Guards!");
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(w) < 64 * 64) {
                p.displayClientMessage(Component.literal("Raiders are waylaying " + Villages.name(w.town) + "'s " + w.kind().label + "!")
                    .withStyle(ChatFormatting.RED), true);
            }
        }
        LOG.info("[MCA-WAGON] {}'s {} waylaid by {} at {}", Villages.name(w.town), w.kind().label, set, w.blockPosition().toShortString());
    }

    /** The animal is gone (killed, or wandered off out of the world): the load onto the driver's back, the wagon left. */
    private static void animalLost(ServerLevel level, TradeWagon w, VillageFolkEntity d) {
        if (w.shipment == null) {
            for (int i = 0; i < w.cargo.getContainerSize(); i++) {
                ItemStack s = w.cargo.removeItemNoUpdate(i);
                if (s.isEmpty()) continue;
                ItemStack left = d.insertGiven(s);
                if (!left.isEmpty()) w.spawnAtLocation(left, 1.0F);
            }
            w.cargoChanged();
        }
        wreck(level, w, "left on the road, its animal lost");
    }

    /**
     * Wrecked or lost on the road: its load spilt where it stands (a shipment's reported lost; none of it saved), a
     * few planks salvaged, the crew let go, the town without a wagon until it builds another.
     */
    static void wreck(ServerLevel level, TradeWagon w, String why) {
        if (w.isRemoved()) return;
        List<ItemStack> spilt = new ArrayList<>();
        for (int i = 0; i < w.cargo.getContainerSize(); i++) {
            ItemStack s = w.cargo.removeItemNoUpdate(i);
            if (!s.isEmpty()) spilt.add(s);
        }
        for (ItemStack s : spilt) {
            ItemEntity e = new ItemEntity(level, w.getX() + (level.random.nextDouble() - 0.5) * 2, w.getY() + 0.6,
                w.getZ() + (level.random.nextDouble() - 0.5) * 2, s);
            e.setDefaultPickUpDelay();
            level.addFreshEntity(e);
        }
        if (w.shipment != null) Shipments.lost(level, w.shipment, List.of(), "the " + w.kind().label + " was " + why);
        w.spawnAtLocation(new ItemStack(Items.OAK_PLANKS, Math.max(2, w.kind().makings.get(0).count() / 4)), 0.5F);
        VillageFolkEntity d = driver(level, w);
        if (d != null && d.trip() != null && d.trip().shipment != null) {
            TradeTrips.over(d);                                         // a shipment's trip is over with its load
            d.trip(null);
        }
        long day = level.getDayTime() / 24000L;
        if (w.town != null) {
            Villages.tell(w.town, day, "our " + w.kind().label + " was " + why + (spilt.isEmpty() ? "" : ", and its load spilt on the road"));
            disown(w.town);
        }
        LOG.info("[MCA-WAGON] {}'s {} was {} at {}; {} stacks spilt", w.town == null ? "?" : Villages.name(w.town), w.kind().label, why,
            w.blockPosition().toShortString(), spilt.size());
        letGo(level, w);
        w.wrecked = true;
        w.discard();
    }

    /** Driver, guards and animal free of it (DRIVING, GUARDING, HARNESSED) and its ground let go. */
    private static void letGo(ServerLevel level, TradeWagon w) {
        unharness(level, animal(level, w), w.town);
        for (Entity p : new ArrayList<>(w.getPassengers())) {
            p.stopRiding();
            if (p instanceof VillageFolkEntity vf) vf.setPose(Pose.STANDING);
        }
        if (w.driver != null) DRIVING.remove(w.driver);
        for (UUID g : w.guards) GUARDING.remove(g);
        releaseWindow(level, w);
        QUIET.remove(w.getUUID());
    }

    /** TradeWagon.remove: a wagon gone from the world other than by unloading with its ground (a /kill): as a wreck. */
    static void removed(ServerLevel level, TradeWagon w, Entity.RemovalReason why) {
        if (w.wrecked || why == Entity.RemovalReason.UNLOADED_TO_CHUNK || why == Entity.RemovalReason.UNLOADED_WITH_PLAYER
                || why == Entity.RemovalReason.CHANGED_DIMENSION) {
            if (why != Entity.RemovalReason.UNLOADED_TO_CHUNK) return;
            releaseWindow(level, w);
            return;
        }
        if (w.town != null && w.getUUID().equals(wagonIdOf(w.town)) && why == Entity.RemovalReason.KILLED) wreck(level, w, "broken up");
        else letGo(level, w);
    }

    /** The driver is not to be found: after a grace, the trip given up (the load home, or the shipment reported lost). */
    private static void driverGone(ServerLevel level, TradeWagon w) {
        long now = level.getGameTime();
        if (w.driverMissingSince < 0) w.driverMissingSince = now;
        if (now - w.driverMissingSince < DRIVER_GRACE) return;
        abandonTrip(level, w, "its driver never came back to it");
    }

    /**
     * A trip that cannot go on (no driver, no way, no animal at the start): a caravan's goods back to the carrier if it
     * is by, else into the home stores; a shipment reported lost with everything saved (back to its origin's stores);
     * the wagon brought home to its yard.
     */
    static void abandonTrip(ServerLevel level, TradeWagon w, String why) {
        Villages.Village home = w.town == null ? null : Villages.get(w.town);
        VillageFolkEntity d = driver(level, w);
        List<ItemStack> aboard = new ArrayList<>();
        for (int i = 0; i < w.cargo.getContainerSize(); i++) {
            ItemStack s = w.cargo.removeItemNoUpdate(i);
            if (!s.isEmpty()) aboard.add(s);
        }
        if (w.shipment != null) {
            Shipments.lost(level, w.shipment, aboard, "the " + w.kind().label + " could not go on: " + why);
            if (d != null && d.trip() != null && w.shipment.equals(d.trip().shipment)) {
                TradeTrips.over(d);
                d.trip(null);
            }
        } else {
            for (ItemStack s : aboard) {
                ItemStack left = d != null && d.trip() != null ? d.insertGiven(s) : s;
                if (!left.isEmpty() && home != null) Crafts.store(level, home, left);
            }
        }
        LOG.info("[MCA-WAGON] {}'s {} gave up its trip: {}", home == null ? "?" : Villages.name(home.id()), w.kind().label, why);
        if (home != null) Villages.tell(home.id(), level.getDayTime() / 24000L, "our " + w.kind().label + " was brought home: " + why);
        parkUp(level, w);
    }

    /** VillageFolkEntity.die etc. call Caravans.abandon; a failure in a tick is logged once and the trip given up. */
    static void failed(TradeWagon w, RuntimeException e) {
        LOG.warn("[MCA-WAGON] a wagon's tick failed ({}); its trip is given up", e.toString(), e);
        if (w.level() instanceof ServerLevel sl) {
            try {
                abandonTrip(sl, w, "a mishap");
            } catch (RuntimeException again) {
                w.setPhase(TradeWagon.Phase.PARKED);
            }
        }
    }

    // ================================================================== out of sight

    /** Nobody near for a few seconds running: the wagon goes quiet (crew aboard, ground let go) and is moved on by number. */
    private static boolean goQuiet(ServerLevel level, TradeWagon w) {
        if (w.route == null || w.threatened || anyPlayerNear(level, w.getX(), w.getZ(), FAR)) {
            w.quietChecks = 0;
            return false;
        }
        if (++w.quietChecks < QUIET_CHECKS) return false;
        w.quietChecks = 0;
        AbstractHorse a = animal(level, w);
        if (a != null && a.getVehicle() != w) a.startRiding(w, true);
        VillageFolkEntity d = driver(level, w);
        if (d != null && d.getVehicle() != w) d.startRiding(w, true);
        for (VillageFolkEntity g : guards(level, w)) if (g.getVehicle() != w) g.startRiding(w, true);
        w.speedNow = 0;
        w.setPhase(TradeWagon.Phase.FAR);
        Far f = new Far(w.getUUID(), w.town, w.route, w.s, w.kind().speed * FAR_PACE, w.blockPosition());
        for (Entity p : w.getPassengers()) if (p instanceof AssistantEntity ae && ae.bedPos() != null) f.beds.add(ae.bedPos());
        QUIET.put(w.getUUID(), f);
        record(w);
        Ledger.note(w.town, LEG, w.route.encode());
        releaseWindow(level, w);
        return true;
    }

    /** A quiet wagon still in the world (its ground kept by something else): it waits for Wagons to bring it on. */
    private static void stillQuiet(ServerLevel level, TradeWagon w) {
        Far f = QUIET.get(w.getUUID());
        if (f == null) {
            w.setPhase(TradeWagon.Phase.ROAD);                               // let go of (a restart): it drives on from here
            return;
        }
        if (w.tickCount % 20 == 0 && (anyPlayerNear(level, w.getX(), w.getZ(), NEAR) || f.s >= f.route.length() - 0.05)) rejoin(level, w, f);
    }

    /** Once a second for each quiet wagon: on along its route; brought back into the world when somebody is near or it is there. */
    private static void quietStep(ServerLevel level, Far f) {
        Villages.Village v = Villages.get(f.town);
        if (v == null || !v.dim().equals(level.dimension())) return;
        f.s = Math.min(f.route.length(), f.s + f.speed * 20);
        double x = f.route.xAt(f.s), z = f.route.zAt(f.s);
        boolean there = f.s >= f.route.length() - 0.05;
        boolean wanted = there || anyPlayerNear(level, x, z, NEAR) || anyPlayerNear(level, f.left.getX(), f.left.getZ(), NEAR);
        if (f.waiting < 0 && f.s % 100 < f.speed * 20) recordQuiet(f, x, z);
        if (!wanted) return;
        Entity e = level.getEntity(f.wagon);
        if (e instanceof TradeWagon w && w.isAlive()) {
            rejoin(level, w, f);
            return;
        }
        // Its ground in, so it can be fetched; a minute and still not there: lost.
        if (f.waiting < 0) {
            f.waiting = 0;
            ChunkLoad.setLoaded(level, fetchOwner(f.wagon), f.left, 0, true);
        }
        if (++f.waiting > FAR_LOST_SECONDS) {
            ChunkLoad.setLoaded(level, fetchOwner(f.wagon), f.left, 0, false);
            QUIET.remove(f.wagon);
            lostQuiet(level, f, "never found again on the road");
        }
    }

    /** Back into the world where it has got to: the crew with it, its ground kept, and on. */
    private static void rejoin(ServerLevel level, TradeWagon w, Far f) {
        QUIET.remove(w.getUUID());
        if (f.waiting >= 0) ChunkLoad.setLoaded(level, fetchOwner(f.wagon), f.left, 0, false);
        w.route = f.route;
        w.s = f.s;
        w.bestS = f.s;
        w.bestTick = level.getGameTime();
        w.setPhase(TradeWagon.Phase.ROAD);
        w.quietChecks = -30;                                              // half a minute in sight before it may go quiet again
        double x = f.route.xAt(f.s), z = f.route.zAt(f.s);
        if (!TradeWagon.groundLoaded(level, x, z)) level.getChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4);   // the ground first
        w.place(level);
        w.teleportTo(w.getX(), w.getY(), w.getZ());
        keepWindow(level, w);
        record(w);
        LOG.info("[MCA-WAGON] {}'s {} comes back into sight at {} ({} of {} blocks)", Villages.name(w.town), w.kind().label,
            w.blockPosition().toShortString(), (int) f.s, (int) f.route.length());
    }

    /** A quiet wagon that could not be found: its shipment lost, the town without it. */
    private static void lostQuiet(ServerLevel level, Far f, String why) {
        String at = Ledger.note(f.town, AT);
        String[] p = at == null ? new String[0] : at.split("\\|", -1);
        if (p.length > 8 && !p[8].isEmpty()) {
            try {
                Shipments.lost(level, UUID.fromString(p[8]), List.of(), "the wagon was " + why);
            } catch (IllegalArgumentException ignored) {
                // no shipment
            }
        }
        WagonKind k = owned(f.town);
        Villages.tell(f.town, level.getDayTime() / 24000L, "our " + (k == null ? "wagon" : k.label) + " was " + why + ", with its load");
        disown(f.town);
        LOG.info("[MCA-WAGON] {}'s quiet wagon was {}", Villages.name(f.town), why);
    }

    /** After a restart: the wagons that were quiet on the road, read back from the towns' ledgers (once). */
    private static void readQuiet(ServerLevel level) {
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension()) || !READ.add(v.id())) continue;
            String at = Ledger.note(v.id(), AT);
            if (at == null || !at.startsWith("FAR|")) continue;
            UUID id = wagonIdOf(v.id());
            WagonRoute r = WagonRoute.decode(Ledger.note(v.id(), LEG));
            WagonKind k = owned(v.id());
            String[] p = at.split("\\|", -1);
            if (id == null || r == null || k == null || p.length < 5) continue;
            try {
                QUIET.put(id, new Far(id, v.id(), r, Double.parseDouble(p[1]), k.speed * FAR_PACE,
                    BlockPos.containing(Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]))));
            } catch (NumberFormatException ignored) {
                // left in the ledger; the wagon drives on when it is next in the world
            }
        }
    }

    /** A wagon the ledger has on the road, in sight, but whose ground has gone (a restart): it goes quiet from where it was. */
    private static void noticeVanished(ServerLevel level) {
        long now = level.getGameTime();
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            String at = Ledger.note(v.id(), AT);
            if (at == null || !(at.startsWith("ROAD|") || at.startsWith("LOADING|") || at.startsWith("UNLOADING|") || at.startsWith("FETCH|"))) {
                ON_ROAD_SEEN.remove(v.id());
                continue;
            }
            UUID id = wagonIdOf(v.id());
            if (id == null || QUIET.containsKey(id)) continue;
            if (level.getEntity(id) != null) { ON_ROAD_SEEN.put(v.id(), now); continue; }
            long seen = ON_ROAD_SEEN.computeIfAbsent(v.id(), k -> now);
            if (now - seen < 200) continue;
            WagonRoute r = WagonRoute.decode(Ledger.note(v.id(), LEG));
            WagonKind k = owned(v.id());
            String[] p = at.split("\\|", -1);
            if (r == null || k == null || p.length < 5) continue;
            try {
                BlockPos left = BlockPos.containing(Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]));
                QUIET.put(id, new Far(id, v.id(), r, Double.parseDouble(p[1]), k.speed * FAR_PACE, left));
                Ledger.note(v.id(), AT, "FAR|" + at.substring(at.indexOf('|') + 1));
                LOG.info("[MCA-WAGON] {}'s wagon is out of the world on the road; moved on out of sight", Villages.name(v.id()));
            } catch (NumberFormatException ignored) {
                // left as it is
            }
        }
    }

    private static UUID fetchOwner(UUID wagon) {
        return UUID.nameUUIDFromBytes(("mca-wagon-fetch-" + wagon).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static boolean anyPlayerNear(ServerLevel level, double x, double z, double range) {
        if (nearForTests) return true;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - x, dz = p.getZ() - z;
            if (dx * dx + dz * dz < range * range) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ its ground, and its record

    private static UUID windowOwner(TradeWagon w) {
        return UUID.nameUUIDFromBytes(("mca-wagon-" + w.getUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The ground round it kept loaded while it is out in sight: moved along when it has gone a chunk. */
    private static void keepWindow(ServerLevel level, TradeWagon w) {
        BlockPos here = w.blockPosition();
        if (w.window != null && w.window.distSqr(here) < 16 * 16) return;
        ChunkLoad.setLoaded(level, windowOwner(w), here, TradeWagon.WINDOW_RADIUS, true);
        if (w.window != null && (w.window.getX() >> 4 != here.getX() >> 4 || w.window.getZ() >> 4 != here.getZ() >> 4)) {
            ChunkLoad.setLoaded(level, windowOwner(w), w.window, TradeWagon.WINDOW_RADIUS, false);
        }
        w.window = here.immutable();
    }

    private static void releaseWindow(ServerLevel level, TradeWagon w) {
        if (w.window == null) return;
        ChunkLoad.setLoaded(level, windowOwner(w), w.window, TradeWagon.WINDOW_RADIUS, false);
        w.window = null;
    }

    /** Where it is and what it is doing, in its town's ledger (so a restart, or its going quiet, loses nothing). */
    static void record(TradeWagon w) {
        if (w.town == null) return;
        Far f = QUIET.get(w.getUUID());
        String line = String.join("|", w.phase.name(), Double.toString(Math.round((f != null ? f.s : w.s) * 10) / 10.0),
            Integer.toString(w.getBlockX()), Integer.toString(w.getBlockY()), Integer.toString(w.getBlockZ()),
            w.from == null ? "" : w.from.toString(), w.to == null ? "" : w.to.toString(), w.back ? "1" : "0",
            w.shipment == null ? "" : w.shipment.toString());
        if (!line.equals(Ledger.note(w.town, AT))) Ledger.note(w.town, AT, line);
    }

    /** A quiet wagon's progress written down now and then (every hundred blocks). */
    private static void recordQuiet(Far f, double x, double z) {
        String at = Ledger.note(f.town, AT);
        if (at == null || !at.startsWith("FAR|")) return;
        String[] p = at.split("\\|", -1);
        if (p.length < 9) return;
        p[1] = Double.toString(Math.round(f.s * 10) / 10.0);
        Ledger.note(f.town, AT, String.join("|", p));
    }

    private static boolean onTheRoadRecord(UUID town) {
        String at = Ledger.note(town, AT);
        return at != null && !at.isEmpty() && !at.startsWith("PARKED");
    }

    // ================================================================== the player: the manifest, and buying off it

    /** Right-click: the manifest (from, to, what is aboard, its worth, who drives); sneaking, buy a lot of the load. */
    static void interact(ServerLevel level, TradeWagon w, Player p) {
        if (p.isShiftKeyDown()) {
            p.displayClientMessage(buy(level, w, p), false);
            return;
        }
        for (Component line : manifest(level, w)) p.displayClientMessage(line, false);
    }

    /** The manifest, a line at a time. */
    public static List<Component> manifest(ServerLevel level, TradeWagon w) {
        List<Component> out = new ArrayList<>();
        String town = w.town == null ? "A town" : Villages.name(w.town);
        String where = switch (w.phase) {
            case PARKED -> "standing in its yard";
            case FETCH -> "being made ready";
            case LOADING -> "being loaded";
            case UNLOADING -> "being unloaded";
            default -> {
                UUID going = w.back ? w.from : w.to;
                UUID leaving = w.back ? w.to : w.from;
                yield going == null ? "on the road" : "from " + Villages.name(leaving) + " to " + Villages.name(going) + (w.back ? ", homeward" : "");
            }
        };
        out.add(Component.literal(town + "'s " + w.kind().label).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal("  " + where).withStyle(ChatFormatting.GRAY)));
        Map<Item, Integer> load = loadByItem(w);
        if (load.isEmpty()) {
            out.add(Component.literal("Aboard: nothing").withStyle(ChatFormatting.GRAY));
        } else {
            MutableComponent goods = Component.literal("Aboard: ").withStyle(ChatFormatting.GRAY);
            int i = 0, pieces = 0;
            for (Map.Entry<Item, Integer> e : load.entrySet()) {
                pieces += e.getValue();
                if (i++ > 0) goods.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                goods.append(Component.literal(e.getValue() + " " + new ItemStack(e.getKey()).getHoverName().getString().toLowerCase(java.util.Locale.ROOT))
                    .withStyle(ChatFormatting.WHITE));
            }
            goods.append(Component.literal(" (" + pieces + " in all, " + w.realFill() + "% full)").withStyle(ChatFormatting.DARK_GRAY));
            out.add(goods);
            UUID priced = w.town;
            out.add(Component.literal("Worth about " + worth(level, priced, w) + " coins at " + (priced == null ? "market" : Villages.name(priced))
                + "'s prices").withStyle(ChatFormatting.GRAY));
        }
        String what = w.shipment != null ? "a load sent to order" : w.phase == TradeWagon.Phase.PARKED ? "" : errandWords(level, w);
        StringBuilder crew = new StringBuilder();
        VillageFolkEntity d = driver(level, w);
        if (d != null) crew.append("Driver: ").append(d.displayNameCap());
        List<VillageFolkEntity> gs = guards(level, w);
        if (!gs.isEmpty()) {
            crew.append(crew.length() > 0 ? "; " : "").append(gs.size() == 1 ? "guard: " : "guards: ");
            for (int i = 0; i < gs.size(); i++) crew.append(i > 0 ? ", " : "").append(gs.get(i).displayNameCap());
        }
        if (w.animal != null) crew.append(crew.length() > 0 ? "; " : "").append("pulled by ").append(animalWord(level, w));
        if (!what.isEmpty() || crew.length() > 0) {
            out.add(Component.literal((what.isEmpty() ? "" : what.substring(0, 1).toUpperCase() + what.substring(1) + ". ") + crew)
                .withStyle(ChatFormatting.GRAY));
        }
        Component sale = forSale(level, w);
        if (sale != null) out.add(sale);
        return out;
    }

    private static String errandWords(ServerLevel level, TradeWagon w) {
        VillageFolkEntity d = driver(level, w);
        Caravans.Trip t = d == null ? null : d.trip();
        if (t == null) return "";
        if (t.deal != null) return "a delivery by a trade deal";
        return t.trade ? "a trade caravan's goods" : "a caravan between a town and its colony";
    }

    /** What a passer-by may buy off it, or why not: only a caravan's own goods out on the road, never a deal's or an order's. */
    @Nullable
    private static Component forSale(ServerLevel level, TradeWagon w) {
        ItemStack lot = lotForSale(level, w);
        if (lot == null) {
            if (w.realFill() > 0 && w.phase == TradeWagon.Phase.ROAD) {
                return Component.literal("Spoken for: not for sale.").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
            }
            return null;
        }
        int price = Market.askLot(level, w.town, lot, lot.getCount());
        return Component.literal("Sneak and use it to buy " + lot.getCount() + " " + lot.getHoverName().getString().toLowerCase(java.util.Locale.ROOT)
            + " for " + price + " coins.").withStyle(ChatFormatting.GREEN);
    }

    /** The lot a passer-by may buy: a bundle of the most plentiful good aboard a caravan's own wagon on the road. */
    @Nullable
    private static ItemStack lotForSale(ServerLevel level, TradeWagon w) {
        if (w.shipment != null || w.phase != TradeWagon.Phase.ROAD || w.town == null) return null;
        VillageFolkEntity d = driver(level, w);
        Caravans.Trip t = d == null ? null : d.trip();
        if (t == null || t.deal != null || t.errand != null) return null;
        ItemStack best = null;
        for (int i = 0; i < w.cargo.getContainerSize(); i++) {
            ItemStack s = w.cargo.getItem(i);
            if (s.isEmpty() || Crates.isCrate(s) || Market.goodFor(s) == null) continue;
            if (best == null || s.getCount() > best.getCount()) best = s;
        }
        if (best == null) return null;
        Market.Good g = Market.goodFor(best);
        int n = Math.min(best.getCount(), Math.max(1, g == null ? 8 : g.bundle()));
        return best.copyWithCount(n);
    }

    /** Sneak-use: the lot bought, the coin into the carrier's purse (it goes home with the trip and is booked there). */
    static Component buy(ServerLevel level, TradeWagon w, Player p) {
        ItemStack lot = lotForSale(level, w);
        if (lot == null) return Component.literal("Nothing aboard is for sale.").withStyle(ChatFormatting.GRAY);
        int price = Market.askLot(level, w.town, lot, lot.getCount());
        if (!Civics.takeCoins(p, price)) {
            return Component.literal("That's " + price + " coins, and you haven't got them.").withStyle(ChatFormatting.RED);
        }
        int need = lot.getCount();
        for (int i = 0; i < w.cargo.getContainerSize() && need > 0; i++) {
            ItemStack s = w.cargo.getItem(i);
            if (!ItemStack.isSameItemSameComponents(s, lot)) continue;
            int k = Math.min(need, s.getCount());
            s.shrink(k);
            need -= k;
        }
        ItemStack bought = lot.copyWithCount(lot.getCount() - need);
        if (!p.getInventory().add(bought)) p.drop(bought, false);
        VillageFolkEntity d = driver(level, w);
        if (d != null && d.trip() != null) {
            d.trip().purse += price;
            d.trip().earned += price;
            FolkTalk.speak(d, "A pleasure. Safe roads to you!");
        }
        w.cargoChanged();
        Villages.tell(w.town, level.getDayTime() / 24000L, "sold " + lot.getCount() + " " + lot.getHoverName().getString().toLowerCase(java.util.Locale.ROOT)
            + " off the " + w.kind().label + " on the road to " + p.getName().getString() + " for " + price + " coins");
        return Component.literal("Bought " + lot.getCount() + " " + lot.getHoverName().getString().toLowerCase(java.util.Locale.ROOT) + " for " + price + " coins.")
            .withStyle(ChatFormatting.GREEN);
    }

    /** The load by item, most first. */
    static Map<Item, Integer> loadByItem(TradeWagon w) {
        Map<Item, Integer> m = new HashMap<>();
        for (int i = 0; i < w.cargo.getContainerSize(); i++) {
            ItemStack s = w.cargo.getItem(i);
            if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        List<Map.Entry<Item, Integer>> es = new ArrayList<>(m.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        Map<Item, Integer> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : es) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** What the load is worth at a town's asking prices (the market's own price desk). */
    static int worth(ServerLevel level, @Nullable UUID town, TradeWagon w) {
        if (town == null) return 0;
        int sum = 0;
        for (Map.Entry<Item, Integer> e : loadByItem(w).entrySet()) sum += Market.askLot(level, town, new ItemStack(e.getKey()), e.getValue());
        return sum;
    }

    /** The load for the client: "id*count;..." (most first, six at most). */
    static String summary(net.minecraft.world.Container cargo) {
        Map<Item, Integer> m = new HashMap<>();
        for (int i = 0; i < cargo.getContainerSize(); i++) {
            ItemStack s = cargo.getItem(i);
            if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (m.isEmpty()) return "";
        List<Map.Entry<Item, Integer>> es = new ArrayList<>(m.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, es.size()); i++) {
            if (i > 0) sb.append(';');
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(es.get(i).getKey());
            sb.append(id).append('*').append(es.get(i).getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ small finders

    @Nullable
    static VillageFolkEntity driver(ServerLevel level, TradeWagon w) {
        if (w.driver == null) return null;
        return level.getEntity(w.driver) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    @Nullable
    static AbstractHorse animal(ServerLevel level, TradeWagon w) {
        if (w.animal == null) return null;
        return level.getEntity(w.animal) instanceof AbstractHorse a && a.isAlive() ? a : null;
    }

    static List<VillageFolkEntity> guards(ServerLevel level, TradeWagon w) {
        if (w.guards.isEmpty()) return List.of();
        List<VillageFolkEntity> out = new ArrayList<>(w.guards.size());
        for (java.util.Iterator<UUID> it = w.guards.iterator(); it.hasNext(); ) {
            UUID u = it.next();
            Entity e = level.getEntity(u);
            if (e instanceof VillageFolkEntity g && g.isAlive()) out.add(g);
            else if (e != null || w.tickCount % 200 == 0 && w.phase != TradeWagon.Phase.FAR) {
                it.remove();                                             // dead, or long gone
                GUARDING.remove(u);
            }
        }
        return out;
    }

    @Nullable
    private static TradeWagon wagonFor(ServerLevel level, VillageFolkEntity f) {
        UUID id = DRIVING.get(f.getUUID());
        return id != null && level.getEntity(id) instanceof TradeWagon w && w.isAlive() ? w : null;
    }

    static String animalWord(ServerLevel level, TradeWagon w) {
        AbstractHorse a = animal(level, w);
        if (a == null) return "animal";
        String kind = a instanceof Llama ? "a llama" : Stables.what(a);
        return a.hasCustomName() ? a.getCustomName().getString() + " (" + kind + ")" : kind;
    }

    // ================================================================== tests and the pictures

    /** Tests and the pictures: give the town a wagon of this kind now, in its yard (no makings taken). */
    @Nullable
    public static TradeWagon giveForTests(ServerLevel level, UUID town, WagonKind k) {
        Villages.Village v = Villages.get(town);
        if (v == null) return null;
        TradeWagon old = wagonOf(level, town);
        if (old != null) old.discard();
        disown(town);
        return spawn(level, v, k);
    }

    /** Tests: the town's wagon, its phase, where it has got to (distance along its route, and its length). */
    public static String stateForTests(ServerLevel level, UUID town) {
        TradeWagon w = wagonOf(level, town);
        UUID id = wagonIdOf(town);
        Far f = id == null ? null : QUIET.get(id);
        if (w == null && f == null) return "none";
        if (f != null) return "FAR " + (int) f.s + "/" + (int) f.route.length();
        return w.phase + " " + (int) w.s + "/" + (w.route == null ? 0 : (int) w.route.length()) + " at " + w.blockPosition().toShortString()
            + " fill " + w.realFill() + " shown " + w.shownFill() + " stage " + w.stage;
    }

    @Nullable
    public static TradeWagon wagonForTests(ServerLevel level, UUID town) {
        return wagonOf(level, town);
    }

    /** Tests: the goods aboard, counted. */
    public static int aboardForTests(TradeWagon w, Item item) {
        int n = 0;
        for (int i = 0; i < w.cargo.getContainerSize(); i++) if (w.cargo.getItem(i).is(item)) n += w.cargo.getItem(i).getCount();
        return n;
    }

    /** Tests: the wagon jumped along its route to this share of it. */
    public static void jumpForTests(ServerLevel level, TradeWagon w, double frac) {
        if (w.route == null) return;
        w.s = w.route.length() * frac;
        w.bestS = w.s;
        w.place(level);
        w.teleportTo(w.getX(), w.getY(), w.getZ());
    }

    public static double alongForTests(TradeWagon w) { return w.s; }

    /** Tests: the point of its route this far along, and the way the road runs there (radians). */
    public static double[] pointForTests(TradeWagon w, double along) {
        if (w.route == null) return new double[]{ w.getX(), w.getZ(), 0 };
        return new double[]{ w.route.xAt(along), w.route.zAt(along), w.route.heading(along, 2.0) };
    }
    public static double lengthForTests(TradeWagon w) { return w.route == null ? 0 : w.route.length(); }
    public static int stageForTests(TradeWagon w) { return w.stage; }
    public static String phaseForTests(TradeWagon w) { return w.phase.name(); }
    @Nullable public static UUID shipmentForTests(TradeWagon w) { return w.shipment; }
    @Nullable public static VillageFolkEntity driverForTests(ServerLevel level, TradeWagon w) { return driver(level, w); }

    /** The manifest as plain lines (tests, the command). */
    public static List<String> manifestText(ServerLevel level, TradeWagon w) {
        List<String> out = new ArrayList<>();
        for (Component c : manifest(level, w)) out.add(c.getString());
        return out;
    }
}
