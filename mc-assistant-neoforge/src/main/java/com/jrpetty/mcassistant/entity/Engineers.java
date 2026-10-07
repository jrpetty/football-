package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * [redstone] The redstone engineer: a late-game trade whose machines are the game's own redstone, built block by block.
 *
 * <p><b>When.</b> Redstone is late by nature: its dust is deep ore, its observers, comparators and daylight sensors want
 * quartz from the Nether, its lamps glowstone, its sticky pistons slime. So a town takes up an engineer in the Diamond
 * Age or after, once its stores hold redstone and quartz both (ready); one engineer, a second at a hundred folk. The
 * town picks the hand (appoint): its best at metal and the rock, a curious one for choice, from a trade that can spare it.
 *
 * <p><b>Its works.</b> It draws a machine from its drawing (Machines), finds it a site (flat open ground away from the
 * streets, the fields and the buildings; the sorter against the storehouse; the gate in the wall's main gate; the lamps
 * on the streets' lamp posts), makes the parts it lacks at the bench from the stores by the game's own recipes (Bench),
 * and lays the machine stage by stage, a few blocks a second, every block out of the stores: the frame, then the
 * hoppers and the pistons, the redstone and the observers last, and the crop when the machine is whole. Then the game
 * runs it: nothing is put in a chest by anybody but a hopper.
 *
 * <p><b>Its rounds.</b> It looks each machine over (a block a creeper took, a piston a player broke: put back from the
 * stores), clears what has fallen into it, keeps the auto-smelter in ore and fuel, keeps the sorter's filters at their
 * twenty-two, and keeps the books: what each machine made, when it was last looked at, what it waits for (Works).
 *
 * <p><b>The town's own.</b> The guards throw the gate's lever as they shut the gates at dusk (gateWatch), the street
 * lamps light by the sky alone, and the couriers empty their packs into the sorter's delivery chest (delivery).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.jrpetty.mcassistant.McAssistantMod.MODID)
public final class Engineers {

    private Engineers() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The engineer's building: a workbench, a lectern of plans, its chests (blueprints/redstoneworks.txt). */
    public static final String WORKSHOP = "redstoneworks";
    /** The town's size before it wants an engineer at all, and the size at which it wants a second. */
    public static final int FROM = 16, SECOND_AT = 100;
    /** What the stores must hold to open the trade: redstone (its dust) and quartz (the observers' and comparators'). */
    public static final int REDSTONE_TO_OPEN = 32, QUARTZ_TO_OPEN = 8;
    /** Blocks laid a second at a machine (the tests lay a machine whole). */
    static final int PER_SECOND = 3;
    /** How often a machine is looked over on the rounds. */
    static final long LOOK_EVERY = 2400L;

    // ------------------------------------------------------------------ the machines

    /** What the engineer builds, in the order a town wants them. */
    public enum Kind {
        LAMP("lamp_post", "a street lamp", "lamps"),
        CANE("cane_farm", "the sugar cane farm", "cane"),
        MELON("melon_farm", "the melon and pumpkin farm", "fruit"),
        SMELTER("smelter", "the auto-smelter", "ingots"),
        SORTER("sorter", "the sorter by the storehouse", "sorted"),
        GATE("piston_gate", "the piston gate", "throws");

        public final String drawing, words, counts;

        Kind(String drawing, String words, String counts) {
            this.drawing = drawing;
            this.words = words;
            this.counts = counts;
        }
    }

    public enum State { BUILDING, WORKING }

    /** One of a town's machines and its books. */
    public static final class Machine {
        public final Kind kind;
        public final BlockPos origin;
        public final Direction back;
        State state = State.BUILDING;
        /** How far through its drawing it is laid (an index into its plan). */
        int placed;
        long builtDay = -1L;
        /** What it has made all told, and on its last day of making. */
        int output, today;
        long outputDay = -1L;
        /** Blocks put back on the rounds. */
        int mended;
        /** When it was last looked over (game time). */
        long checked = -100000L;
        /** Its stone, and (the melon farm) its crop: "melon", "pumpkin" or "both". */
        String casing = "stone_bricks";
        String crop = "both";
        /** The sorter, laid against the storehouse: its overflow goes straight in. */
        boolean docked;
        /** What it waits for ("8 hoppers"), or null. */
        @Nullable String shortOf;
        /** What its output chest held at the last look, item by item. */
        final Map<Item, Integer> seen = new HashMap<>();
        /** Whether {@code seen} is a true count (not kept over a restart: the first look after one only counts). */
        transient boolean seenKnown;
        /** The gate: the lever's last throws. */
        int throwsAll;
        /** The blocks there only for their looks that were laid in its stone instead (the stores had too few when it was
         *  first drawn up): "glass", "bricks", or both. Decided once and kept, so a look-over never undoes it. */
        @Nullable String plain;
        @Nullable transient List<Machines.Placement> plan;

        Machine(Kind kind, BlockPos origin, Direction back) {
            this.kind = kind;
            this.origin = origin.immutable();
            this.back = back;
        }

        public State state() { return state; }
        public int output() { return output; }
        public int mended() { return mended; }
        public int placed() { return placed; }
        public boolean docked() { return docked; }
        public int throwsAll() { return throwsAll; }
        public String crop() { return crop; }
        @Nullable public String shortOf() { return shortOf; }
    }

    /** A town's works: its machines, and what its engineers are about. */
    static final class Works {
        final List<Machine> machines = new ArrayList<>();
        /** Parts made at the bench, all told, by name. */
        final Map<String, Integer> made = new LinkedHashMap<>();
        @Nullable Machine current;
        String doing = "";
        long nextPlan = -100000L;
        /** Sites looked for and not found, by kind, and when: not looked for again for a while. */
        final Map<Kind, Long> noSite = new EnumMap<>(Kind.class);
        /** The gate's throw that is wanted, who is going to throw it, and since when. */
        @Nullable Boolean wantShut;
        @Nullable UUID thrower;
        long throwSince;
        /** Why the bench could not make the last part it was asked for ("2 quartz", "put by for the age"). */
        String lastWant = "";
    }

    private static final Map<UUID, Works> WORKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> OPEN = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** When each engineer last worked (game time). */
    private static final Map<UUID, Long> LAST_WORK = new ConcurrentHashMap<>();

    public static void resetForTests() {
        WORKS.clear();
        inputs = null;
        OPEN.clear();
        LOOKED.clear();
        LAST_WORK.clear();
    }

    static Works works(UUID id) {
        Works w = WORKS.get(id);
        if (w != null) return w;
        inputs = null;
        return WORKS.computeIfAbsent(id, Engineers::load);
    }

    /** A town's machines as the books have them. */
    public static List<Machine> machines(UUID id) {
        return List.copyOf(works(id).machines);
    }

    @Nullable
    public static Machine machine(UUID id, Kind kind) {
        for (Machine m : works(id).machines) if (m.kind == kind) return m;
        return null;
    }

    // ------------------------------------------------------------------ the trade: when, how many, who

    /** Does the town want an engineer at all (Villages.craftReady): the Diamond Age or later, and once its stores held
     *  redstone and quartz (lookAtStores opened it; it stays open). */
    public static boolean ready(@Nullable UUID id) {
        if (id == null || Villages.ageOf(id).ordinal() < Villages.Age.DIAMOND.ordinal()) return false;
        return OPEN.computeIfAbsent(id, k -> "1".equals(Ledger.note(k, "redstone.open")));
    }

    /** How many engineers the town wants: none before it is ready and sixteen strong, one, two from a hundred. */
    public static int wanted(@Nullable UUID id) {
        if (id == null || !ready(id)) return 0;
        int n = Villages.headcount(id);
        return n < FROM ? 0 : n >= SECOND_AT ? 2 : 1;
    }

    /** The town's first engineer, or null (a machine's output is booked to it). */
    @Nullable
    static VillageFolkEntity first(UUID id) {
        List<VillageFolkEntity> all = engineers(id);
        return all.isEmpty() ? null : all.get(0);
    }

    public static List<VillageFolkEntity> engineers(UUID id) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && f.stationTask() == StationTask.REDSTONE) out.add(f);
        }
        return out;
    }

    /** The trade opens: the stores hold redstone and quartz enough, in the Diamond Age or after. True if it is open. */
    static boolean lookAtStores(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (ready(id)) return true;
        if (Villages.ageOf(id).ordinal() < Villages.Age.DIAMOND.ordinal()) return false;
        int red = Market.stock(level, id, s -> s.is(Items.REDSTONE)) + 9 * Market.stock(level, id, s -> s.is(Items.REDSTONE_BLOCK));
        int quartz = Market.stock(level, id, s -> s.is(Items.QUARTZ)) + 4 * Market.stock(level, id, s -> s.is(Items.QUARTZ_BLOCK));
        if (red < REDSTONE_TO_OPEN || quartz < QUARTZ_TO_OPEN) return false;
        OPEN.put(id, true);
        Ledger.note(id, "redstone.open", "1");
        long day = level.getDayTime() / 24000L;
        Villages.tell(id, day, "with redstone and Nether quartz in the stores, the town wants a redstone engineer");
        LOG.info("[MCA-REDSTONE] {}: the trade opens ({} redstone, {} quartz, {})", Villages.name(id), red, quartz, Villages.ageOf(id));
        return true;
    }

    /** May a hand be spared from this trade for the works? Never from a craft, the storehouse, the bank, the scouts, the
     *  caves or the ferry, nor from a trade the town is short of. */
    static boolean spare(UUID id, StationTask t) {
        if (t == StationTask.NONE) return true;
        if (t == StationTask.REDSTONE || t.isCraft() || t == StationTask.STORE || t == StationTask.BANK || t == StationTask.SCOUT
            || t == StationTask.HAUL || t == StationTask.CAVE || t == StationTask.FERRY) return false;
        return Villages.share(id, t) >= 0.5;
    }

    /** What a folk knows that the works want: the smith's metal and the miner's rock (redstone is ore), and its own. */
    public static int fitness(VillageFolkEntity f) {
        int s = f.tradeLevel(StationTask.REDSTONE) * 1000 + Math.max(f.tradeLevel(StationTask.SMITH), f.tradeLevel(StationTask.MINE)) * 100
            + f.tradeLevel(StationTask.SMELT) * 30 + Math.min(19, f.lifetimeXp() / 1000);
        if (f.life().has(Social.Trait.CURIOUS)) s += 60;              // it wants to know how a thing works
        if (f.life().has(Social.Trait.HARDWORKING)) s += 25;
        return s;
    }

    /**
     * The hands who could take up the works, the fittest first: grown, at home, not the elder, and from a trade the town
     * can spare them from.
     *
     * <p>[redstone] The interviews' seam. The town fills the post itself here (appoint takes the first). When the town
     * comes to hold interviews for its posts (an Interviews of its own, not in this branch yet), this is the list it
     * interviews from, and appoint takes whoever the interviews choose instead of the first.
     */
    public static List<VillageFolkEntity> candidates(UUID id) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive() || f.isElder()) continue;
            if (f.trip() != null || f.expedition() != null || f.ageYears() < 18) continue;
            if (!spare(id, f.stationTask())) continue;
            out.add(f);
        }
        out.sort((x, y) -> Integer.compare(fitness(y), fitness(x)));
        return out;
    }

    /** One more engineer, if the town wants one: the fittest hand it can spare. Returns who, or null. */
    @Nullable
    public static VillageFolkEntity appoint(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (engineers(id).size() >= wanted(id)) return null;
        List<VillageFolkEntity> all = candidates(id);
        if (all.isEmpty()) return null;
        VillageFolkEntity best = all.get(0);
        int bestScore = fitness(best);
        StationTask was = best.stationTask();
        // A head start: a smith or a miner knows the metal and the rock, and is a few levels short of the works.
        int knows = Math.max(best.tradeLevel(StationTask.SMITH), best.tradeLevel(StationTask.MINE));
        int has = AssistantEntity.xpForLevel(best.tradeLevel(StationTask.REDSTONE)), start = AssistantEntity.xpForLevel(Math.max(0, knows - 3));
        if (start > has) best.schoolXp(StationTask.REDSTONE, start - has);
        BlockPos post = post(level, v);
        best.setStation(post, StationTask.REDSTONE);
        best.assignPlot(WorkZone.around(post, 5, WorkZone.DEFAULT_DEPTH), "The Works");
        long day = level.getDayTime() / 24000L;
        Villages.tell(id, day, best.displayNameCap() + " (" + (was == StationTask.NONE ? "with no trade" : JobMarket.a(JobMarket.noun(was))
            + " of level " + best.tradeLevel(was)) + ") became the town's redstone engineer");
        best.persona().remember(day, "I became the town's redstone engineer", 5);
        FolkTalk.speak(best, FolkTalk.pick(level.getRandom(), "Redstone! I've wanted to know how it all works since I first saw a lamp light.",
            "The town's engineer, me. Pistons, observers, the lot. I'll want the bench and a lot of quartz.",
            "Machines that work while we sleep. Leave it with me."));
        LOG.info("[MCA-REDSTONE] {} of {} became the redstone engineer (was {} level {}, fitness {})", best.displayNameCap(),
            Villages.name(id), was, best.tradeLevel(was), bestScore);
        return best;
    }

    /** Where an engineer stands between jobs: in its workshop once it stands, else by the board or on the square. */
    static BlockPos post(ServerLevel level, Villages.Village v) {
        BlockPos shop = Villages.builtAt(v.id(), WORKSHOP);
        if (shop != null) return shop;
        BlockPos board = VillageBoards.lectern(v.id());
        return (board != null ? board : v.centre()).relative(Direction.WEST, 3);
    }

    // ------------------------------------------------------------------ the workshop

    /** The redstone workshop on the wish list: once the town has its engineer (or is ready for one). */
    public static boolean wantsWorkshop(UUID id) {
        return ready(id) && wanted(id) > 0 && Villages.builtAt(id, WORKSHOP) == null && !Villages.hasBuilt(id, WORKSHOP);
    }

    public static String why(UUID id) {
        return "a redstone workshop: a bench, a lectern of plans and chests of parts, for the town's engineer and its machines";
    }

    // ------------------------------------------------------------------ the town's tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 != 13) return;
        com.jrpetty.mcassistant.Guard.run("redstone", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    tick(level, v);
                }
            }
        });
    }

    /** The town's side of the works, every second: the stores looked at (once a minute), and the gate at dusk and dawn. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (now - LOOKED.getOrDefault(id, -100000L) >= 1200L || now < LOOKED.getOrDefault(id, 0L)) {
            LOOKED.put(id, now);
            if (lookAtStores(level, v)) appoint(level, v);
        }
        if (!WORKS.containsKey(id) && Ledger.note(id, "redstone.works") == null) return;
        gateWatch(level, v);
        // The output chests counted every five seconds, so what the couriers carry off to the stores (they come for a
        // chest of two dozen or more) has been counted to the machine before it goes.
        if ((now / 20L) % 5L == 0L) {
            for (Machine m : works(id).machines) {
                if (m.state != State.WORKING) continue;
                BlockPos chest = outputChest(m);
                if (chest != null && level.isLoaded(chest)) tally(level, v, m, null);
            }
        }
    }

    // ------------------------------------------------------------------ the engineer's day

    /**
     * The engineer's day (its trade's work: AssistantEntity's station work): out to the machine it is building and at it,
     * or round the machines it keeps. True through its working hours (at its post, drawing up plans, when nothing is to
     * hand); false outside them.
     */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || f.isSleeping() || f.isBaby()) return false;
        long t = level.getDayTime() % 24000L;
        if (t < 900L || t > 12300L) return false;
        Villages.Village v = Villages.get(id);
        if (v == null) return false;
        // At most once a second, however often its day asks (every two seconds near a player, every six away from
        // one); and as many blocks laid as the seconds since allow, three a second, so it builds at the same pace.
        long now = level.getGameTime();
        Long last = LAST_WORK.get(f.getUUID());
        if (last != null && now >= last && now - last < 20L) return true;
        int most = last == null || now < last ? PER_SECOND : (int) Math.min(4L * PER_SECOND, Math.max(1L, PER_SECOND * (now - last) / 20L));
        LAST_WORK.put(f.getUUID(), now);
        Works w = works(id);
        Machine m = w.current;
        if (m == null || !worthDoing(level, v, m)) {
            m = next(level, v, w, f);
            w.current = m;
        }
        if (m == null) {
            w.doing = "drawing up plans at the lectern";
            BlockPos post = post(level, v);
            if (f.distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5) > 16 && f.getNavigation().isDone()) f.walkTo(post, 0.9D);
            return true;                                  // at its post, as a stationed hand is, not backed off
        }
        BlockPos at = m.origin;
        if (f.distanceToSqr(at.getX() + 0.5, at.getY(), at.getZ() + 0.5) > 7.5 * 7.5) {
            if (f.getNavigation().isDone()) f.walkTo(at, 1.0D);
            w.doing = (m.state == State.BUILDING ? "on the way to build " : "on the way to see to ") + m.kind.words;
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5);
        if (m.state == State.BUILDING) {
            Step s = build(level, v, m, f, most);
            if (s == Step.DONE) finished(level, v, m, f);
            if (s == Step.SHORT) { w.current = null; w.nextPlan = level.getGameTime() + 1200L; }
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        } else {
            round(level, v, m, f);
            w.current = null;
        }
        return true;
    }

    /** Is the machine it has in hand still worth its time: building, or due a look. */
    /** Out of its own patch on the town's works (VillageFolkEntity.walksAbroad): an engineer with a machine in hand, a
     *  hand sent to throw the gate's lever. The patch's leash would otherwise walk it straight back. */
    public static boolean abroad(VillageFolkEntity f) {
        UUID id = f.ownerId();
        Works w = id == null ? null : WORKS.get(id);
        if (w == null) return false;
        if (f.stationTask() == StationTask.REDSTONE && w.current != null) return true;
        return f.getUUID().equals(w.thrower);
    }

    private static boolean worthDoing(ServerLevel level, Villages.Village v, Machine m) {
        if (!works(v.id()).machines.contains(m)) return false;
        if (m.state == State.BUILDING) return m.shortOf == null;
        return level.getGameTime() - m.checked >= LOOK_EVERY;
    }

    /** What to do next: a machine half built first, then one due its look, then a new one the town wants. */
    @Nullable
    static Machine next(ServerLevel level, Villages.Village v, Works w, @Nullable VillageFolkEntity f) {
        long now = level.getGameTime();
        for (Machine m : w.machines) {
            if (m.state == State.BUILDING && (m.shortOf == null || now >= w.nextPlan)) { m.shortOf = null; return m; }
        }
        for (Machine m : w.machines) if (m.state == State.WORKING && now - m.checked >= LOOK_EVERY) return m;
        if (now < w.nextPlan) return null;
        w.nextPlan = now + 2400L;
        Kind k = wantNext(level, v, w);
        if (k == null) {
            w.doing = "drawing up plans: the stores cannot spare a machine's makings yet";
            return null;
        }
        Machine m = site(level, v, k);
        if (m == null) {
            w.noSite.put(k, now);
            LOG.info("[MCA-REDSTONE] {}: no site for {} (not looked for again for a day)", Villages.name(v.id()), k.words);
            return null;
        }
        w.machines.add(m);
        save(v.id());
        long day = level.getDayTime() / 24000L;
        LOG.info("[MCA-REDSTONE] {}: {} planned at {} facing {}", Villages.name(v.id()), m.kind.words, m.origin.toShortString(), m.back);
        if (f != null) FolkTalk.speak(f, "Next: " + m.kind.words + ". I've the drawing; now for the parts.");
        Villages.tell(v.id(), day, (f != null ? f.displayNameCap() + " began " : "the engineer began ") + m.kind.words);
        return m;
    }

    /** The machine the town wants next, if its stores can run to it: the cane farm (paper for the library and the
     *  quests), the street lamps (four at a time, then another machine before the next four), the melon farm, the
     *  auto-smelter, the sorter and the gate. */
    @Nullable
    static Kind wantNext(ServerLevel level, Villages.Village v, Works w) {
        long now = level.getGameTime();
        int lamps = 0, others = 0;
        for (Machine m : w.machines) if (m.kind == Kind.LAMP) lamps++; else others++;
        for (Kind k : new Kind[]{ Kind.CANE, Kind.LAMP, Kind.MELON, Kind.SMELTER, Kind.SORTER, Kind.GATE }) {
            Long failed = w.noSite.get(k);
            if (failed != null && now - failed < 24000L) continue;
            if (k == Kind.LAMP) {
                if (lamps < 4 * (1 + others) && lampsWanted(level, v) > 0 && affordable(level, v, k)) return k;
                continue;
            }
            if (machine(v.id(), k) != null) continue;
            if (k == Kind.GATE && Watch.wall(v.id()) == null) continue;
            if (k == Kind.SORTER && Storehouses.doorFor(level, v.id()) == null) continue;
            if (!affordable(level, v, k)) continue;
            return k;
        }
        return null;
    }

    /**
     * Can the stores run to this machine now: its crop, and the iron, redstone, quartz, glowstone and slime its parts
     * take (parts already made and in the stores counted as made)? A builder's reckoning before it starts, so the
     * engineer does not begin a machine it cannot finish and leave it standing half built.
     */
    static boolean affordable(ServerLevel level, Villages.Village v, Kind k) {
        Map<String, Integer> n = new HashMap<>();
        for (Machines.Cell c : Machines.drawing(k.drawing).cells()) n.merge(c.key().block(), 1, Integer::sum);
        // What the stores can spare: what they hold, less what the town keeps back (the smith's iron, the masons'
        // stone, the builders' timber, everything the age is short of), as the bench reckons it.
        Map<Item, Integer> free = spareStock(level, v);
        int hoppers = toMake(free, Items.HOPPER, n.get("hopper")), pistons = toMake(free, Items.PISTON, n.get("piston"));
        int sticky = toMake(free, Items.STICKY_PISTON, n.get("sticky_piston")), observers = toMake(free, Items.OBSERVER, n.get("observer"));
        int comparators = toMake(free, Items.COMPARATOR, n.get("comparator")), repeaters = toMake(free, Items.REPEATER, n.get("repeater"));
        int torches = toMake(free, Items.REDSTONE_TORCH, n.get("redstone_wall_torch"));
        int sensors = toMake(free, Items.DAYLIGHT_DETECTOR, n.get("daylight_detector"));
        int lamps = toMake(free, Items.REDSTONE_LAMP, n.get("redstone_lamp"));
        int iron = 5 * hoppers + pistons + sticky;
        int red = n.getOrDefault("redstone_wire", 0) + pistons + sticky + 2 * observers + 3 * comparators + 3 * repeaters + torches + 4 * lamps;
        int quartz = observers + comparators + 3 * sensors;
        if (free.getOrDefault(Items.IRON_INGOT, 0) + 9 * free.getOrDefault(Items.IRON_BLOCK, 0) < iron) return false;
        if (free.getOrDefault(Items.REDSTONE, 0) + 9 * free.getOrDefault(Items.REDSTONE_BLOCK, 0) < red) return false;
        if (free.getOrDefault(Items.QUARTZ, 0) + 4 * free.getOrDefault(Items.QUARTZ_BLOCK, 0) < quartz) return false;
        if (free.getOrDefault(Items.GLOWSTONE, 0) < lamps) return false;
        if (free.getOrDefault(Items.SLIME_BALL, 0) + 9 * free.getOrDefault(Items.SLIME_BLOCK, 0) < sticky) return false;
        // Its stone: the casing, and what is cut from it (a slab is half a block, a wall a block).
        int stone = n.getOrDefault("casing", 0) + (n.getOrDefault("casing_slab", 0) + 1) / 2 + n.getOrDefault("casing_wall", 0);
        if (casingSpare(free) < stone) return false;
        if (k == Kind.CANE) return free.getOrDefault(Items.SUGAR_CANE, 0) >= n.getOrDefault("sugar_cane", 0);
        if (k == Kind.MELON) return free.getOrDefault(Items.MELON_SEEDS, 0) + free.getOrDefault(Items.PUMPKIN_SEEDS, 0) >= n.getOrDefault("stem", 0);
        return true;
    }

    private static int toMake(Map<Item, Integer> free, Item part, @Nullable Integer drawn) {
        return drawn == null ? 0 : Math.max(0, drawn - free.getOrDefault(part, 0));
    }

    /** What the stores can spare of each thing (Bench.keepBook): held, less what the town keeps back. */
    static Map<Item, Integer> spareStock(ServerLevel level, Villages.Village v) {
        Map<Item, Integer> out = new HashMap<>();
        for (Map.Entry<Item, int[]> e : Bench.keepBook(level, v, new HashMap<>()).entrySet()) out.put(e.getKey(), e.getValue()[1]);
        return out;
    }

    /** The stones a machine may be built of, the engineer's choice first. */
    private static final List<Item> CASINGS = List.of(Items.STONE_BRICKS, Items.COBBLESTONE, Items.STONE, Items.POLISHED_ANDESITE,
        Items.DEEPSLATE_BRICKS, Items.COBBLED_DEEPSLATE, Items.ANDESITE);

    /** The most of any one casing stone the stores can spare. */
    private static int casingSpare(Map<Item, Integer> free) {
        int most = 0;
        for (Item it : CASINGS) most = Math.max(most, free.getOrDefault(it, 0));
        return most;
    }

    private static int stock(ServerLevel level, UUID id, Item it) {
        return Market.stock(level, id, s -> s.is(it));
    }

    /** The melon farm's crop, as the stores' seeds have it: both turn about where there are four of each, else the one. */
    static String cropFor(ServerLevel level, Villages.Village v) {
        int melon = stock(level, v.id(), Items.MELON_SEEDS), pumpkin = stock(level, v.id(), Items.PUMPKIN_SEEDS);
        if (melon >= 4 && pumpkin >= 4) return "both";
        return pumpkin > melon ? "pumpkin" : "melon";
    }

    // ------------------------------------------------------------------ building

    public enum Step { LAID, DONE, SHORT }

    /** A machine's drawing laid out where it stands, in the order it goes up (made once, kept). */
    static List<Machines.Placement> plan(ServerLevel level, Villages.Village v, Machine m) {
        if (m.plan != null) return m.plan;
        Machines.Drawing d = Machines.drawing(m.kind.drawing);
        List<Machines.Placement> raw = Machines.plan(d, m.origin, m.back, materials(level, v, m));
        List<Machines.Placement> out = new ArrayList<>();
        int stem = 0;
        for (Machines.Placement p : raw) {
            BlockState s = p.state();
            // The melon farm's stems: melon and pumpkin turn about where the stores have both seeds.
            if (s.is(Blocks.MELON_STEM) || s.is(Blocks.PUMPKIN_STEM)) {
                boolean pumpkin = m.crop.equals("pumpkin") || (m.crop.equals("both") && stem++ % 2 == 1);
                s = (pumpkin ? Blocks.PUMPKIN_STEM : Blocks.MELON_STEM).defaultBlockState();
                p = new Machines.Placement(p.pos(), s, p.cell());
            }
            out.add(p);
        }
        m.plan = List.copyOf(out);
        return m.plan;
    }

    /** The engineer's own materials for a machine: its stone and what is cut from it, the wall's own block for the gate,
     *  and a stand-in of its stone for a block that is there for its looks when the stores have none. */
    static Function<String, BlockState> materials(ServerLevel level, Villages.Village v, Machine m) {
        Block casing = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.withDefaultNamespace(m.casing)).orElse(Blocks.STONE_BRICKS);
        return name -> switch (name) {
            case "casing" -> casing.defaultBlockState();
            case "casing_slab" -> slabOf(casing).defaultBlockState();
            case "casing_wall" -> wallOf(casing).defaultBlockState();
            case "door" -> doorBlock(level, v, m, casing).defaultBlockState();
            case "stem" -> Blocks.MELON_STEM.defaultBlockState();
            case "or:glass" -> plain(level, v, m, "glass", Items.GLASS) ? casing.defaultBlockState() : null;
            case "or:bricks" -> plain(level, v, m, "bricks", Items.BRICKS) ? casing.defaultBlockState() : null;
            default -> null;
        };
    }

    /** Is this look-only block laid in the machine's stone instead? Decided the first time it is asked (the stores short
     *  of two dozen of it), and kept on the machine's books. */
    private static boolean plain(ServerLevel level, Villages.Village v, Machine m, String what, Item it) {
        if (m.plain == null) m.plain = "";
        if (m.plain.contains("+" + what)) return false;                  // "+glass": the glass itself
        if (m.plain.contains(what)) return true;                         // "glass": stone in its place
        boolean short_ = spareStock(level, v).getOrDefault(it, 0) < 24;
        m.plain += (short_ ? "" : "+") + what + "/";
        return short_;
    }

    static Block slabOf(Block b) {
        if (b == Blocks.COBBLESTONE) return Blocks.COBBLESTONE_SLAB;
        if (b == Blocks.STONE) return Blocks.STONE_SLAB;
        if (b == Blocks.ANDESITE) return Blocks.ANDESITE_SLAB;
        if (b == Blocks.POLISHED_ANDESITE) return Blocks.POLISHED_ANDESITE_SLAB;
        if (b == Blocks.DEEPSLATE_BRICKS) return Blocks.DEEPSLATE_BRICK_SLAB;
        if (b == Blocks.COBBLED_DEEPSLATE) return Blocks.COBBLED_DEEPSLATE_SLAB;
        if (b == Blocks.BRICKS) return Blocks.BRICK_SLAB;
        return Blocks.STONE_BRICK_SLAB;
    }

    static Block wallOf(Block b) {
        if (b == Blocks.COBBLESTONE || b == Blocks.STONE) return Blocks.COBBLESTONE_WALL;
        if (b == Blocks.ANDESITE || b == Blocks.POLISHED_ANDESITE) return Blocks.ANDESITE_WALL;
        if (b == Blocks.DEEPSLATE_BRICKS) return Blocks.DEEPSLATE_BRICK_WALL;
        if (b == Blocks.COBBLED_DEEPSLATE) return Blocks.COBBLED_DEEPSLATE_WALL;
        if (b == Blocks.BRICKS) return Blocks.BRICK_WALL;
        return Blocks.STONE_BRICK_WALL;
    }

    /** The gate's leaves are the wall's own stone (whatever the masons built it of), else the engineer's: read off the
     *  wall just past the gate's lever post, which the gate never touches (so it reads the same after a restart). */
    private static Block doorBlock(ServerLevel level, Villages.Village v, Machine m, Block casing) {
        BlockState w = level.getBlockState(m.origin.relative(Machines.right(m.back), -4).above(1));
        return Watch.masonry(w) && w.isCollisionShapeFullBlock(level, m.origin) && !w.hasBlockEntity() ? w.getBlock() : casing;
    }

    /** The stone a machine is built of: stone bricks if the stores run to it, else the commonest building stone they hold. */
    static String casingFor(ServerLevel level, Villages.Village v, int need) {
        Map<Item, Integer> free = spareStock(level, v);
        String best = "stone_bricks";
        int most = -1;
        for (Item it : CASINGS) {
            int n = free.getOrDefault(it, 0);
            if (it == Items.STONE_BRICKS && n >= need) return "stone_bricks";
            if (n > most) { most = n; best = BuiltInRegistries.ITEM.getKey(it).getPath(); }
        }
        return best;
    }

    /** The item a block of a machine is laid from: dust from redstone, a wall torch from a torch, a stem from its seeds,
     *  farmland from dirt (it is tilled), water from a bucket of it. */
    static Item itemFor(BlockState s) {
        if (s.is(Blocks.REDSTONE_WIRE)) return Items.REDSTONE;
        if (s.is(Blocks.REDSTONE_WALL_TORCH)) return Items.REDSTONE_TORCH;
        if (s.is(Blocks.MELON_STEM)) return Items.MELON_SEEDS;
        if (s.is(Blocks.PUMPKIN_STEM)) return Items.PUMPKIN_SEEDS;
        if (s.is(Blocks.FARMLAND)) return Items.DIRT;
        if (s.is(Blocks.WATER)) return Items.WATER_BUCKET;
        return s.getBlock().asItem();
    }

    /**
     * Lay up to {@code most} blocks of a machine, each out of the stores (a part the stores lack is made at the bench
     * first, by its recipe, from what they hold). A block already as drawn is passed; a cell drawn open is cleared of
     * what grows or lies there (its stone or earth back into the stores). SHORT when the stores cannot run to the next
     * block, DONE when the last is down.
     */
    public static Step build(ServerLevel level, Villages.Village v, Machine m, @Nullable VillageFolkEntity f, int most) {
        List<Machines.Placement> plan = plan(level, v, m);
        int laid = 0;
        while (m.placed < plan.size() && laid < most) {
            Machines.Placement p = plan.get(m.placed);
            BlockState here = level.getBlockState(p.pos());
            if (!level.isLoaded(p.pos())) return Step.LAID;
            if (here.getBlock() instanceof StorehouseBlock) { m.placed++; continue; }        // the sorter against the storehouse
            if (p.cell().key().air()) {
                if (!here.isAir() && !keepOpen(here)) clear(level, v, p.pos(), here);
                m.placed++;
                continue;
            }
            if (Machines.asDrawn(here, p)) { m.placed++; continue; }
            if (!fetch(level, v, m, p.state(), f)) return Step.SHORT;
            if (!here.isAir()) clear(level, v, p.pos(), here);
            Machines.place(level, p);
            m.placed++;
            laid++;
        }
        if (m.placed % 16 == 0 || m.placed >= plan.size()) save(v.id());
        return m.placed >= plan.size() ? Step.DONE : Step.LAID;
    }

    /** What may stand in a cell drawn open: a piston's head coming and going, the crop and its fruit growing into it. */
    private static boolean keepOpen(BlockState s) {
        return s.is(Blocks.PISTON_HEAD) || s.is(Blocks.MOVING_PISTON) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.MELON)
            || s.is(Blocks.PUMPKIN) || s.getBlock() instanceof StorehouseBlock;
    }

    /** One block's worth out of the stores (made at the bench if it can be); its bucket back for water. */
    static boolean fetch(ServerLevel level, Villages.Village v, Machine m, BlockState s, @Nullable VillageFolkEntity f) {
        if (s.is(Blocks.WATER)) {
            if (TownWork.take(level, v, st -> st.is(Items.WATER_BUCKET), 1)) {
                TownWork.give(level, v, new ItemStack(Items.BUCKET));
                return true;
            }
            // An empty bucket, filled at the town's own water (a well, the pond, the river) and carried back full.
            if (water(level, v, m.origin) && TownWork.take(level, v, st -> st.is(Items.BUCKET), 1)) {
                TownWork.give(level, v, new ItemStack(Items.BUCKET));
                return true;
            }
            m.shortOf = "a bucket of water";
            return false;
        }
        if (s.is(Blocks.MUD)) {
            if (TownWork.take(level, v, st -> st.is(Items.MUD), 1)) return true;
            // Mud is earth with water poured on it (a bottle of water over dirt: the game's own way of making it), the
            // bottle filled at the town's water and kept.
            if (Market.stock(level, v.id(), st -> st.is(Items.GLASS_BOTTLE) || st.is(Items.POTION)) > 0
                    && (water(level, v, m.origin) || Market.stock(level, v.id(), st -> st.is(Items.WATER_BUCKET)) > 0)
                    && TownWork.take(level, v, st -> st.is(Items.DIRT), 1)) return true;
            m.shortOf = Market.stock(level, v.id(), st -> st.is(Items.DIRT)) == 0 ? "dirt for the mud" : "a bottle to wet the mud";
            return false;
        }
        Item it = itemFor(s);
        if (it == Items.AIR) return true;
        if (TownWork.take(level, v, st -> st.is(it) && st.getComponentsPatch().isEmpty(), 1)) return true;
        if (make(level, v, f, it, partsWanted(level, v, m, it)) && TownWork.take(level, v, st -> st.is(it), 1)) return true;
        if (m.shortOf == null) {
            String want = works(v.id()).lastWant;
            m.shortOf = Bench.words(it, 1) + (want.isEmpty() ? "" : " (short of " + want + ")");
        }
        return false;
    }

    /** How many of this part the rest of the machine wants (so the bench makes them in one go). */
    private static int partsWanted(ServerLevel level, Villages.Village v, Machine m, Item it) {
        int n = 0;
        List<Machines.Placement> plan = plan(level, v, m);
        for (int i = m.placed; i < plan.size(); i++) if (itemFor(plan.get(i).state()) == it) n++;
        return Math.max(1, Math.min(64, n));
    }

    /** Is there water within reach of a site to fill a bucket at? */
    private static boolean water(ServerLevel level, Villages.Village v, BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-24, -4, -24), at.offset(24, 2, 24))) {
            if (!level.isLoaded(p)) continue;
            if (level.getFluidState(p).isSource() && level.getBlockState(p).is(Blocks.WATER)) return true;
        }
        return false;
    }

    /** Make parts at the bench, from the stores, by the game's recipe; the parts into the stores. */
    public static boolean make(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f, Item it, int n) {
        Bench.Hand hand = Bench.handOf(level, v, f, WORKSHOP);
        Bench.Plan plan = Bench.plan(level, v, it, n, hand);
        // The stores not running to all the machine still wants of it: as many as they will, one at the least.
        if ((!plan.ok() || plan.made <= 0) && n > 1) plan = Bench.plan(level, v, it, 1, hand);
        if (!plan.ok() || plan.made <= 0) {
            works(v.id()).lastWant = plan.shortOf == null ? "" : plan.shortOf + (plan.why.isEmpty() ? "" : ", " + plan.why);
            return false;
        }
        // Its making booked as a craft's is (Crafts.now): what went out of the stores and what came back in, netted,
        // so the parts are the trade's made and the redstone, quartz and iron in them its used.
        ItemStack made;
        Economy.openCraft(v.id(), StationTask.REDSTONE);
        try {
            made = Bench.make(level, v, plan, f, hand);
        } finally {
            Economy.closeCraft();
        }
        if (made.isEmpty()) return false;
        works(v.id()).made.merge(BuiltInRegistries.ITEM.getKey(it).getPath(), made.getCount(), Integer::sum);
        if (f != null) {
            f.brain("made " + Bench.words(it, made.getCount()) + " at the bench");
            if (level.getRandom().nextInt(3) == 0) FolkTalk.speak(f, FolkTalk.pick(level.getRandom(),
                Bench.words(it, made.getCount()).substring(0, 1).toUpperCase(Locale.ROOT) + Bench.words(it, made.getCount()).substring(1) + ", made.",
                "That's " + Bench.words(it, made.getCount()) + " off the bench."));
        }
        save(v.id());
        return true;
    }

    /** What stands in a cell the machine wants is taken up: a door back into the stores whole, earth and stone as what
     *  a spade or a pick would give, what grows wild for nothing. */
    private static void clear(ServerLevel level, Villages.Village v, BlockPos pos, BlockState here) {
        lift(level, v, pos, here);
        // What stood on it and cannot stand without it comes away with it, into the stores: a lantern on a post, a
        // torch, a door's upper half. (Taken up quietly, so nothing is knocked off and left lying in the street.)
        BlockPos up = pos.above();
        for (int i = 0; i < 4; i++, up = up.above()) {
            BlockState s = level.getBlockState(up);
            if (s.isAir() || s.canSurvive(level, up)) break;
            lift(level, v, up, s);
        }
    }

    /** One block taken up into the stores, as what a spade or a pick would give; what a chest held goes in too. */
    private static void lift(ServerLevel level, Villages.Village v, BlockPos pos, BlockState here) {
        if (level.getBlockEntity(pos) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) TownWork.give(level, v, c.getItem(i).copy());
            c.clearContent();
        }
        Item back = Items.AIR;
        if (here.getBlock() instanceof DoorBlock) {
            if (here.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) back = here.getBlock().asItem();
        } else if (here.is(Blocks.GRASS_BLOCK) || here.is(Blocks.DIRT_PATH) || here.is(Blocks.FARMLAND) || here.is(Blocks.PODZOL)
                || here.is(Blocks.MYCELIUM)) {
            back = Items.DIRT;
        } else if (here.is(Blocks.STONE)) {
            back = Items.COBBLESTONE;
        } else if (here.is(Blocks.DEEPSLATE)) {
            back = Items.COBBLED_DEEPSLATE;
        } else if (!here.canBeReplaced() && here.getFluidState().isEmpty() && !here.is(BlockTags.LEAVES)) {
            back = here.getBlock().asItem();
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        if (back != Items.AIR) TownWork.give(level, v, new ItemStack(back));
    }

    /** A machine laid whole: on the books, in the chronicle, and (the gate) shut or open as the watch has its gates. */
    static void finished(ServerLevel level, Villages.Village v, Machine m, @Nullable VillageFolkEntity f) {
        m.state = State.WORKING;
        m.seen.clear();                     // its chests new and empty: everything in them from now on is its making
        m.seenKnown = true;
        m.shortOf = null;
        m.builtDay = level.getDayTime() / 24000L;
        m.checked = level.getGameTime();
        works(v.id()).current = null;
        save(v.id());
        String who = f != null ? f.displayNameCap() : "the engineer";
        Villages.tell(v.id(), m.builtDay, who + " finished " + m.kind.words + (m.kind == Kind.LAMP ? " at " + m.origin.toShortString() : "")
            + ": it runs on redstone, by itself");
        if (f != null) {
            f.persona().remember(m.builtDay, "I built " + m.kind.words, m.kind == Kind.LAMP ? 1 : 3);
            FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "There: " + m.kind.words + ". Watch it go.",
                m.kind.words.substring(0, 1).toUpperCase(Locale.ROOT) + m.kind.words.substring(1) + "'s done. Not a hand on it from now on.",
                "Finished. I'll look it over on my rounds."));
        }
        LOG.info("[MCA-REDSTONE] {}: {} finished at {} ({} blocks)", Villages.name(v.id()), m.kind.words, m.origin.toShortString(),
            plan(level, v, m).size());
    }

    // ------------------------------------------------------------------ the rounds

    /**
     * A machine looked over: anything not as drawn put back from the stores (a piston a player broke, a block a creeper
     * took), what has fallen into its open cells cleared, its output counted, and the smelter's ore and fuel and the
     * sorter's filters kept up.
     */
    public static int round(ServerLevel level, Villages.Village v, Machine m, @Nullable VillageFolkEntity f) {
        m.checked = level.getGameTime();
        List<Machines.Placement> plan = plan(level, v, m);
        int put = 0;
        for (Machines.Placement p : plan) {
            if (!level.isLoaded(p.pos())) continue;
            BlockState here = level.getBlockState(p.pos());
            if (here.getBlock() instanceof StorehouseBlock) continue;
            if (p.cell().key().air()) {
                if (!here.isAir() && !keepOpen(here) && !here.canBeReplaced()) { clear(level, v, p.pos(), here); put++; }
                continue;
            }
            if (Machines.asDrawn(here, p)) continue;
            if (here.is(Blocks.PISTON_HEAD) || here.is(Blocks.MOVING_PISTON)) continue;
            if (!fetch(level, v, m, p.state(), f)) break;
            if (!here.isAir()) clear(level, v, p.pos(), here);
            Machines.place(level, p);
            put++;
        }
        long day = level.getDayTime() / 24000L;
        if (put > 0) {
            m.mended += put;
            Villages.tell(v.id(), day, (f != null ? f.displayNameCap() : "the engineer") + " mended " + m.kind.words + " (" + put
                + (put == 1 ? " block" : " blocks") + " put back)");
            if (f != null) FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Somebody's had a block out of " + m.kind.words + ". Back in it goes.",
                "Mended. A machine is only as good as its last piston."));
        }
        tally(level, v, m, f);
        if (m.kind == Kind.SMELTER) feedSmelter(level, v, m);
        if (m.kind == Kind.SORTER) stockSorter(level, v, m);
        save(v.id());
        return put;
    }

    /** Its output chest counted: what is new in it since the last look is what the machine made. */
    static int tally(ServerLevel level, Villages.Village v, Machine m, @Nullable VillageFolkEntity f) {
        BlockPos chest = outputChest(m);
        if (chest == null || !(level.getBlockEntity(chest) instanceof Container c)) return 0;
        Map<Item, Integer> now = new HashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) now.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        int fresh = 0;
        long day = level.getDayTime() / 24000L;
        for (Map.Entry<Item, Integer> e : now.entrySet()) {
            if (!m.seenKnown) break;                                     // the first look after a restart: only counted
            int n = e.getValue() - m.seen.getOrDefault(e.getKey(), 0);
            if (n <= 0) continue;
            fresh += n;
            Economy.machineMade(v.id(), f != null ? f : first(v.id()), new ItemStack(e.getKey()), n);
        }
        m.seen.clear();
        m.seen.putAll(now);
        m.seenKnown = true;
        if (fresh > 0) {
            if (m.outputDay != day) { m.outputDay = day; m.today = 0; }
            m.today += fresh;
            m.output += fresh;
        }
        return fresh;
    }

    /** Where a machine's output lands: the farms' chests, the smelter's chest below. */
    @Nullable
    static BlockPos outputChest(Machine m) {
        char ch = switch (m.kind) {
            case CANE, MELON -> 'C';
            case SMELTER -> 'K';
            default -> 0;
        };
        if (ch == 0) return null;
        List<Machines.Cell> cells = Machines.drawing(m.kind.drawing).of(ch);
        return cells.isEmpty() ? null : Machines.at(m.origin, m.back, cells.get(0));
    }

    /** The smelter's ore and fuel chests: the great chests over the furnaces (ore), and behind them (fuel). */
    static List<BlockPos> smelterChests(Machine m, boolean ore) {
        List<BlockPos> out = new ArrayList<>();
        for (Machines.Cell c : Machines.drawing(m.kind.drawing).of('A')) if ((c.h() == 2) == ore) out.add(Machines.at(m.origin, m.back, c));
        return out;
    }

    /** Ore and fuel out of the stores into the auto-smelter, so the smelters' load is lightened: raw iron, gold and
     *  copper; dried kelp blocks first (the kelp diver's), else coal or charcoal. */
    static void feedSmelter(ServerLevel level, Villages.Village v, Machine m) {
        // The ore shared between the great chests, so all four furnaces burn: up to sixteen a chest, eight of each kind
        // kept back in the stores for the smelter's own furnaces.
        List<BlockPos> ores = smelterChests(m, true);
        for (int i = 0; i < ores.size(); i++) {
            if (!(level.getBlockEntity(ores.get(i)) instanceof Container c)) continue;
            int held = count(c, s -> s.is(Items.RAW_IRON) || s.is(Items.RAW_GOLD) || s.is(Items.RAW_COPPER));
            for (Item ore : List.of(Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD)) {
                if (held >= 16) break;
                int spare = Market.stock(level, v.id(), s -> s.is(ore)) - 8;
                int give = Math.min(16 - held, spare / (ores.size() - i));
                if (give <= 0 || !TownWork.take(level, v, s -> s.is(ore), give)) continue;
                insert(c, new ItemStack(ore, give));
                held += give;
            }
        }
        // And the fuel the same way: the kelp diver's dried kelp blocks first (twenty smeltings each), else coal or
        // charcoal with sixteen kept back.
        List<BlockPos> fuels = smelterChests(m, false);
        for (int i = 0; i < fuels.size(); i++) {
            if (!(level.getBlockEntity(fuels.get(i)) instanceof Container c)) continue;
            if (count(c, s -> s.is(Items.DRIED_KELP_BLOCK) || s.is(Items.COAL) || s.is(Items.CHARCOAL)) >= 4) continue;
            for (Item fuel : List.of(Items.DRIED_KELP_BLOCK, Items.COAL, Items.CHARCOAL)) {
                int spare = Market.stock(level, v.id(), s -> s.is(fuel)) - (fuel == Items.DRIED_KELP_BLOCK ? 0 : 16);
                int give = Math.min(8, spare / (fuels.size() - i));
                if (give <= 0 || !TownWork.take(level, v, s -> s.is(fuel), give)) continue;
                insert(c, new ItemStack(fuel, give));
                break;
            }
        }
    }

    /** The sorter's filters stocked: each holds twenty-two of its good (eighteen, and one in each other slot). */
    static void stockSorter(ServerLevel level, Villages.Village v, Machine m) {
        List<Item> goods = sorterGoods(level, v, m);
        List<Machines.Cell> filters = sortedCells(m, 'f');
        for (int i = 0; i < filters.size() && i < goods.size(); i++) {
            if (!(level.getBlockEntity(Machines.at(m.origin, m.back, filters.get(i))) instanceof Container f)) continue;
            Item it = goods.get(i);
            int[] want = { 18, 1, 1, 1, 1 };
            boolean right = true;
            for (int s = 0; s < 5; s++) {
                ItemStack st = f.getItem(s);
                if (!st.isEmpty() && !st.is(it)) right = false;
            }
            if (!right) continue;                                  // a filter set by a player is its own business
            for (int s = 0; s < 5; s++) {
                ItemStack st = f.getItem(s);
                int lack = want[s] - st.getCount();
                if (st.isEmpty()) lack = want[s];
                if (lack <= 0 || (s == 0 && st.getCount() >= 15)) continue;      // the first slot comes and goes by a few
                if (!TownWork.take(level, v, x -> x.is(it) && x.getComponentsPatch().isEmpty(), lack)) break;
                f.setItem(s, new ItemStack(it, (st.isEmpty() ? 0 : st.getCount()) + lack));
            }
        }
    }

    /** The goods a town's sorter sorts: the ten commonest in its stores that stack to sixty-four (a filter counts in
     *  sixty-fourths), fixed when the sorter is first stocked. */
    static List<Item> sorterGoods(ServerLevel level, Villages.Village v, Machine m) {
        String kept = Ledger.note(v.id(), "redstone.sorts");
        List<Item> out = new ArrayList<>();
        if (kept != null && !kept.isEmpty()) {
            for (String k : kept.split(",")) {
                BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(k)).ifPresent(out::add);
            }
            return out;
        }
        Map<Item, Integer> counts = new HashMap<>();
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || s.getMaxStackSize() != 64 || !s.getComponentsPatch().isEmpty()) continue;
                counts.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        counts.entrySet().stream().filter(e -> e.getValue() >= 44).sorted((a, b) -> b.getValue() - a.getValue())
            .limit(10).forEach(e -> out.add(e.getKey()));
        if (out.size() == 10) {
            StringBuilder sb = new StringBuilder();
            for (Item it : out) sb.append(sb.length() == 0 ? "" : ",").append(BuiltInRegistries.ITEM.getKey(it));
            Ledger.note(v.id(), "redstone.sorts", sb.toString());
        }
        return out;
    }

    /** A drawing's cells of one mark, left to right. */
    static List<Machines.Cell> sortedCells(Machine m, char ch) {
        List<Machines.Cell> out = new ArrayList<>(Machines.drawing(m.kind.drawing).of(ch));
        out.sort(java.util.Comparator.comparingInt(Machines.Cell::dx));
        return out;
    }

    private static int count(Container c, java.util.function.Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    private static void insert(Container c, ItemStack s) {
        for (int i = 0; i < c.getContainerSize() && !s.isEmpty(); i++) {
            ItemStack in = c.getItem(i);
            if (in.isEmpty()) { c.setItem(i, s.copy()); s.setCount(0); break; }
            if (ItemStack.isSameItemSameComponents(in, s) && in.getCount() < in.getMaxStackSize()) {
                int move = Math.min(s.getCount(), in.getMaxStackSize() - in.getCount());
                in.grow(move);
                s.shrink(move);
            }
        }
        c.setChanged();
    }

    // ------------------------------------------------------------------ the sorter's delivery

    /**
     * Where a courier empties a mixed load (Couriers): the sorter's delivery chest, when the town's sorter is working, its
     * ten filters stocked and the chest has room; else the storehouse as before.
     */
    public static BlockPos delivery(ServerLevel level, UUID id, BlockPos depot) {
        Machine m = machine(id, Kind.SORTER);
        if (m == null || m.state != State.WORKING) return depot;
        List<Machines.Cell> d = Machines.drawing(m.kind.drawing).of('D');
        if (d.isEmpty()) return depot;
        BlockPos chest = Machines.at(m.origin, m.back, d.get(0));
        if (!(level.getBlockEntity(chest) instanceof Container c)) return depot;
        int free = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) free++;
        if (free < 6 || !filtersStocked(level, m)) return depot;
        return chest;
    }

    /** Every filter of the sorter holding its good (none empty, which would take anything). */
    static boolean filtersStocked(ServerLevel level, Machine m) {
        for (Machines.Cell c : Machines.drawing(m.kind.drawing).of('f')) {
            if (!(level.getBlockEntity(Machines.at(m.origin, m.back, c)) instanceof Container f)) return false;
            for (int s = 0; s < f.getContainerSize(); s++) if (f.getItem(s).isEmpty()) return false;
        }
        return true;
    }

    /** The working sorter's own chests, a good to each, and its overflow chest when it stands apart: the town's stores
     *  (Villages.storeChests), wherever along its line they are. */
    public static List<BlockPos> sortedChests(ServerLevel level, UUID id) {
        Machine m = machine(id, Kind.SORTER);
        if (m == null || m.state != State.WORKING) return List.of();
        List<BlockPos> out = new ArrayList<>();
        for (Machines.Cell c : Machines.drawing(m.kind.drawing).cells()) {
            char ch = c.key().ch();
            if (ch != 'K' && ch != 'X') continue;
            BlockPos p = Machines.at(m.origin, m.back, c);
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof Container) out.add(p.immutable());
        }
        return out;
    }

    /** Is this chest a machine's input (the smelter's ore and fuel, the sorter's delivery chest), which is no store of the
     *  town's to take from or put into (ZoneChests)? */
    public static boolean machineInput(@Nullable UUID id, BlockPos pos) {
        if (id == null || WORKS.get(id) == null) return false;
        for (Machine m : WORKS.get(id).machines) {
            if (m.origin.distManhattan(pos) > 32) continue;
            if (m.kind == Kind.SMELTER && (smelterChests(m, true).contains(pos) || smelterChests(m, false).contains(pos))) return true;
            if (m.kind == Kind.SORTER) {
                for (Machines.Cell c : Machines.drawing(m.kind.drawing).of('D')) if (Machines.at(m.origin, m.back, c).equals(pos)) return true;
            }
        }
        return false;
    }

    /** As machineInput, for any town: the chests every town's machines feed from. */
    public static boolean anyMachineInput(BlockPos pos) {
        java.util.Set<Long> in = inputs;
        if (in == null) {
            in = new java.util.HashSet<>();
            for (Works w : WORKS.values()) {
                for (Machine m : List.copyOf(w.machines)) {
                    if (m.kind == Kind.SMELTER) {
                        for (BlockPos p : smelterChests(m, true)) in.add(p.asLong());
                        for (BlockPos p : smelterChests(m, false)) in.add(p.asLong());
                    }
                    if (m.kind == Kind.SORTER) for (Machines.Cell c : Machines.drawing(m.kind.drawing).of('D')) in.add(Machines.at(m.origin, m.back, c).asLong());
                }
            }
            inputs = in;
        }
        return in.contains(pos.asLong());
    }

    /** Every town's machine inputs by position (ZoneChests asks for each chest it finds): made afresh when the books change. */
    @Nullable private static volatile java.util.Set<Long> inputs;

    // ------------------------------------------------------------------ siting

    /** A site for a new machine of this kind, or null. */
    @Nullable
    static Machine site(ServerLevel level, Villages.Village v, Kind k) {
        Machine m = switch (k) {
            case LAMP -> lampSite(level, v);
            case GATE -> gateSite(level, v);
            case SORTER -> sorterSite(level, v);
            default -> openSite(level, v, k, anchorFor(level, v, k), 12, 60);
        };
        if (m != null && k == Kind.MELON) m.crop = cropFor(level, v);
        return m;
    }

    private static BlockPos anchorFor(ServerLevel level, Villages.Village v, Kind k) {
        if (k == Kind.SMELTER) {
            BlockPos s = Villages.builtAt(v.id(), "smeltery");
            if (s != null) return s;
        }
        return v.centre();
    }

    /** Flat open ground for a machine: off the streets, the square, the fields and every building and machine, its
     *  ground earth or stone, nothing standing on it but grass and flowers, nearest the anchor first; its front (the
     *  glass of a farm) toward the town. */
    @Nullable
    static Machine openSite(ServerLevel level, Villages.Village v, Kind k, BlockPos anchor, int near, int far) {
        Machines.Drawing d = Machines.drawing(k.drawing);
        int[] b = d.bounds();
        for (int r = near; r <= far; r += 3) {
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8;
                int x = anchor.getX() + (int) Math.round(Math.cos(a) * r), z = anchor.getZ() + (int) Math.round(Math.sin(a) * r);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos origin = new BlockPos(x, y, z);
                Direction away = Direction.getNearest(x - v.centre().getX(), 0, z - v.centre().getZ());
                for (Direction back : new Direction[]{ away, away.getClockWise(), away.getCounterClockWise(), away.getOpposite() }) {
                    if (back.getAxis().isVertical()) continue;
                    if (fits(level, v, d, b, origin, back)) {
                        Machine m = new Machine(k, origin, back);
                        m.casing = casingFor(level, v, d.count('#') + 8);
                        return m;
                    }
                }
            }
        }
        return null;
    }

    /** Does a machine's drawing fit here: every column flat ground of earth or stone with nothing on it, clear of the
     *  town's plan and of what is built? */
    static boolean fits(ServerLevel level, Villages.Village v, Machines.Drawing d, int[] b, BlockPos origin, Direction back) {
        java.util.Set<Long> cols = new java.util.HashSet<>();
        for (Machines.Cell c : d.cells()) cols.add(((long) c.dx() << 32) ^ (c.dz() & 0xffffffffL));
        BlockPos heart = v.centre();
        for (long col : cols) {
            int dx = (int) (col >> 32), dz = (int) col;
            BlockPos p = Machines.at(origin, back, dx, 0, dz);
            if (!level.isLoaded(p)) return false;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
            if (y != origin.getY()) return false;
            BlockState ground = level.getBlockState(p.below());
            if (!(ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(Blocks.GRAVEL) || ground.is(Blocks.STONE)
                || ground.is(Blocks.ANDESITE) || ground.is(Blocks.DIORITE) || ground.is(Blocks.GRANITE) || ground.is(Blocks.CLAY))) return false;
            for (int h = 0; h <= b[3]; h++) {
                BlockState s = level.getBlockState(p.above(h));
                if (!s.isAir() && !(s.canBeReplaced() && s.getFluidState().isEmpty())) return false;
            }
            int rx = p.getX() - heart.getX(), rz = p.getZ() - heart.getZ();
            if (TownPlan.isStreet(rx, rz) || TownPlan.isSquare(rx, rz)) return false;
            if (Villages.onFarmland(v.id(), heart, p, 1)) return false;
            if (taken(level, v, p)) return false;
        }
        return true;
    }

    /** Is this spot within a building of the town's (with a margin), or another of its machines? */
    static boolean taken(ServerLevel level, Villages.Village v, BlockPos p) {
        for (Ledger.Building bl : Ledger.buildings(v.id())) {
            int[] h = com.jrpetty.mcassistant.entity.goal.Blueprints.fullHalf(bl.structure());
            int r = Math.max(h[0], h[1]) + 3;
            if (Math.abs(bl.anchor().getX() - p.getX()) <= Math.max(r, 6) && Math.abs(bl.anchor().getZ() - p.getZ()) <= Math.max(r, 6)) return true;
        }
        Works w = WORKS.get(v.id());
        if (w != null) {
            for (Machine m : w.machines) {
                int[] b = Machines.drawing(m.kind.drawing).bounds();
                int r = Math.max(b[0], b[1]) + 2;
                if (Math.abs(m.origin.getX() - p.getX()) <= r && Math.abs(m.origin.getZ() - p.getZ()) <= r) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the street lamps

    /** The lamp posts on the streets, where the town has them (TownWork's posts along the avenues and the ring), and
     *  how many still burn a lantern or a torch rather than a lamp that lights itself. */
    static int lampsWanted(ServerLevel level, Villages.Village v) {
        int n = 0;
        for (BlockPos top : lampSpots(level, v)) if (lampable(level, top) && machineAt(v.id(), top.above()) == null) n++;
        return n;
    }

    static List<BlockPos> lampSpots(ServerLevel level, Villages.Village v) {
        List<BlockPos> out = new ArrayList<>();
        int reach = Math.min(48, Villages.townReach(v.id()) + 4);
        BlockPos c = v.centre();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (!TownWork.lampSpot(dx, dz)) continue;
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                out.add(groundUnderPost(level, new BlockPos(x, y - 1, z)));
            }
        }
        out.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(c)));
        return out;
    }

    /**
     * The ground under whatever stands at a lamp spot, from the top of the column: the town's lamp posts are two blocks
     * of the town's own post (fences, a wall, logs or stone, as its style has it: TownWork, Architecture) under their
     * light (a lantern, a torch, the Nether Age's glowstone), the older ones a torch on a fence, and the engineer's own
     * a lamp on two walls with its sensor on top.
     */
    static BlockPos groundUnderPost(ServerLevel level, BlockPos top) {
        BlockState s = level.getBlockState(top);
        if (s.is(Blocks.DAYLIGHT_DETECTOR)) return top.below(4);
        if ((s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH)) && level.getBlockState(top.below()).is(BlockTags.FENCES)
                && !level.getBlockState(top.below(2)).is(BlockTags.FENCES)) return top.below(2);
        if (light(s)) return top.below(3);
        return top;
    }

    /** The light on top of a town's lamp post. */
    private static boolean light(BlockState s) {
        return s.is(Blocks.LANTERN) || s.is(Blocks.SOUL_LANTERN) || s.is(Blocks.TORCH) || s.is(Blocks.GLOWSTONE)
            || s.is(Blocks.SEA_LANTERN) || s.is(Blocks.SHROOMLIGHT) || s.is(Blocks.JACK_O_LANTERN) || s.is(Blocks.REDSTONE_LAMP);
    }

    /** A post with a light on it (to be given a lamp: any of the town's styles), or bare level ground (a new post). */
    static boolean lampable(ServerLevel level, BlockPos top) {
        BlockState a = level.getBlockState(top.above()), b = level.getBlockState(top.above(2)), l = level.getBlockState(top.above(3));
        if (l.is(Blocks.REDSTONE_LAMP) || b.is(Blocks.REDSTONE_LAMP)) return false;
        // The pre-Iron Age post: a fence and a torch on it. Grown into a proper post.
        if (a.is(BlockTags.FENCES) && b.is(Blocks.TORCH)) return true;
        // An Iron Age post of whatever the town builds its posts of, its light on top.
        boolean fenced = (a.is(BlockTags.FENCES) || a.is(BlockTags.WALLS)) && (b.is(BlockTags.FENCES) || b.is(BlockTags.WALLS));
        if (fenced) return light(l) || l.isAir();
        // (Of logs or stone, only with its light on: two blocks of either with nothing on top are somebody's, not a post.)
        boolean post = !a.isAir() && !b.isAir() && !a.hasBlockEntity() && !b.hasBlockEntity() && !light(a) && !light(b);
        if (post) return light(l);
        return a.isAir() && b.isAir() && l.isAir() && level.getBlockState(top).isSolid();
    }

    @Nullable
    static Machine lampSite(ServerLevel level, Villages.Village v) {
        for (BlockPos top : lampSpots(level, v)) {
            if (!lampable(level, top) || machineAt(v.id(), top.above()) != null) continue;
            Machine m = new Machine(Kind.LAMP, top.above(), Direction.NORTH);
            m.casing = casingFor(level, v, 2);
            // An old post stays as it is: only its light changes. Its fence posts are the drawing's posts.
            BlockState post = level.getBlockState(top.above());
            if (post.is(BlockTags.FENCES) || post.is(BlockTags.WALLS)) m.crop = BuiltInRegistries.BLOCK.getKey(post.getBlock()).getPath();
            return m;
        }
        return null;
    }

    @Nullable
    static Machine machineAt(UUID id, BlockPos origin) {
        for (Machine m : works(id).machines) if (m.origin.equals(origin)) return m;
        return null;
    }

    // ------------------------------------------------------------------ the gate

    /**
     * The piston gate goes into the wall's main gate: the first gate the watch hung (its doors go back into the stores,
     * the other gates keep theirs, a fallback). The drawing's door stands where the gate's doors were, its pistons in the
     * gate's posts and the pillars beside them, its lever on the inside of the wall.
     */
    @Nullable
    static Machine gateSite(ServerLevel level, Villages.Village v) {
        BlockPos a = Watch.wall(v.id());
        if (a == null) return null;
        for (Watch.Gate g : Watch.gates(level, v.id())) {
            if (g.doors().isEmpty()) continue;
            Direction side = g.out();
            int y = g.doors().get(0).getY();
            BlockPos at = Watch.cell(a, side, -1);
            Machine m = new Machine(Kind.GATE, new BlockPos(at.getX(), y, at.getZ()), side);
            m.casing = casingFor(level, v, 24);
            return m;
        }
        return null;
    }

    /** The gate's lever, if the town has its piston gate. */
    @Nullable
    public static BlockPos lever(UUID id) {
        Machine m = machine(id, Kind.GATE);
        if (m == null || m.state != State.WORKING) return null;
        List<Machines.Cell> l = Machines.drawing(m.kind.drawing).of('L');
        return l.isEmpty() ? null : Machines.at(m.origin, m.back, l.get(0));
    }

    /**
     * The watch's habit, kept at the piston gate: when the watch shuts the gates at dusk (or the bell rings), a guard goes
     * to the lever and throws it, and the gate shuts; in the morning it is thrown back. The nearest guard is sent; if none
     * comes in a minute, whoever is nearest throws it.
     */
    static void gateWatch(ServerLevel level, Villages.Village v) {
        BlockPos lever = lever(v.id());
        if (lever == null || !level.isLoaded(lever)) return;
        BlockState st = level.getBlockState(lever);
        if (!st.is(Blocks.LEVER)) return;
        boolean shut = Watch.knows(v.id()) ? Watch.isShut(v.id()) : isNight(level);
        boolean thrown = st.getValue(LeverBlock.POWERED);
        Works w = works(v.id());
        if (thrown == shut) { w.wantShut = null; w.thrower = null; return; }
        long now = level.getGameTime();
        if (w.wantShut == null || w.wantShut != shut) { w.wantShut = shut; w.thrower = null; w.throwSince = now; }
        VillageFolkEntity g = w.thrower == null ? null : level.getEntity(w.thrower) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
        if (g == null) {
            g = nearest(v, lever, StationTask.GUARD, 64);
            if (g == null && now - w.throwSince > 1200L) g = nearest(v, lever, null, 24);
            w.thrower = g == null ? null : g.getUUID();
        }
        if (g == null) return;
        if (g.distanceToSqr(lever.getX() + 0.5, lever.getY(), lever.getZ() + 0.5) > 3.5 * 3.5) {
            if (g.getNavigation().isDone() || now % 100 == 0) g.walkTo(lever, 1.1D);
            return;
        }
        throwLever(level, v, g, lever, shut);
    }

    static boolean isNight(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        return t >= 13000L && t < 23000L;
    }

    /** The lever thrown, by a hand: the game's own lever, so the gate's own redstone does the rest. */
    static void throwLever(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by, BlockPos lever, boolean shut) {
        BlockState st = level.getBlockState(lever);
        if (!st.is(Blocks.LEVER) || st.getValue(LeverBlock.POWERED) == shut) return;
        ((LeverBlock) Blocks.LEVER).pull(st, level, lever, null);
        Works w = works(v.id());
        w.wantShut = null;
        w.thrower = null;
        Machine m = machine(v.id(), Kind.GATE);
        if (m != null) { m.throwsAll++; save(v.id()); }
        if (by != null) {
            by.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            by.brain((shut ? "shut" : "opened") + " the piston gate");
            FolkTalk.speak(by, shut ? FolkTalk.pick(level.getRandom(), "Gate's shutting. In you come, if you're coming.", "Lever's thrown. Shut tight for the night.")
                : FolkTalk.pick(level.getRandom(), "Morning! Gate's open.", "And open she goes."));
        }
    }

    /** The nearest folk of a trade (or any, with null) within so far of a spot. */
    @Nullable
    static VillageFolkEntity nearest(Villages.Village v, BlockPos at, @Nullable StationTask trade, int within) {
        VillageFolkEntity best = null;
        double bd = within * (double) within;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive() || f.isBaby() || f.isSleeping()) continue;
            if (trade != null && f.stationTask() != trade) continue;
            double d = f.distanceToSqr(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            if (d < bd) { bd = d; best = f; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the sorter's site

    /**
     * The sorter goes against the storehouse if it can (its line runs into the storehouse's side, so what no filter wants
     * goes straight into the stores: the storehouse takes from a hopper, StoreIntake), else on open ground near it with an
     * overflow chest of its own (which, so near the storehouse, is one of the town's stores).
     */
    @Nullable
    static Machine sorterSite(ServerLevel level, Villages.Village v) {
        BlockPos door = Storehouses.doorFor(level, v.id());
        if (door == null) return null;
        BlockState ds = level.getBlockState(door);
        Machines.Drawing d = Machines.drawing(Kind.SORTER.drawing);
        int[] b = d.bounds();
        if (StorehouseBlock.isFormed(ds)) {
            BlockPos o = StorehouseBlock.origin(door, ds);
            List<Machines.Cell> tail = d.of('X');
            Machines.Cell x = tail.isEmpty() ? null : tail.get(0);
            if (x != null) {
                for (Direction right : new Direction[]{ Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH }) {
                    Direction back = right.getCounterClockWise();
                    for (int k = 0; k < 3; k++) {
                        // The cube's cell the line runs into: on its near face, at the line's height (one up from its floor).
                        BlockPos target = switch (right) {
                            case EAST -> o.offset(0, 1, k);
                            case WEST -> o.offset(2, 1, k);
                            case SOUTH -> o.offset(k, 1, 0);
                            default -> o.offset(k, 1, 2);
                        };
                        BlockPos origin = target.relative(right, -x.dx()).relative(back, -x.dz()).below(x.h());
                        if (fitsDocked(level, v, d, b, origin, back)) {
                            Machine m = new Machine(Kind.SORTER, origin, back);
                            m.docked = true;
                            m.casing = casingFor(level, v, d.count('#') + 8);
                            return m;
                        }
                    }
                }
            }
        }
        Machine m = openSite(level, v, Kind.SORTER, door, 6, 28);
        return m != null && Villages.inStoreArea(v.id(), m.origin) ? m : null;
    }

    /** As fits, for a sorter laid against the storehouse: its cells in the storehouse are the storehouse's own. */
    private static boolean fitsDocked(ServerLevel level, Villages.Village v, Machines.Drawing d, int[] b, BlockPos origin, Direction back) {
        BlockPos heart = v.centre();
        for (Machines.Cell c : d.cells()) {
            BlockPos p = Machines.at(origin, back, c);
            if (!level.isLoaded(p)) return false;
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof StorehouseBlock) continue;
            int rx = p.getX() - heart.getX(), rz = p.getZ() - heart.getZ();
            if (c.h() >= 0 && (TownPlan.isStreet(rx, rz) || TownPlan.isSquare(rx, rz))) return false;   // never across a street
            if (c.h() >= 0 && !s.isAir() && !(s.canBeReplaced() && s.getFluidState().isEmpty())) return false;
            if (c.h() == -1 && !(s.is(BlockTags.DIRT) || s.is(Blocks.STONE) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.COBBLESTONE) || s.is(Blocks.STONE_BRICKS) || s.is(Blocks.DIRT_PATH))) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ talk and the books

    /** "What do you do?" from the engineer (FolkTalk). */
    static String doing(VillageFolkEntity f, RandomSource r) {
        UUID id = f.ownerId();
        if (id == null) return "I build machines. Redstone ones.";
        Works w = works(id);
        Machine m = w.current;
        if (m != null && m.state == State.BUILDING) {
            int all = m.plan == null ? 0 : m.plan.size();
            String how = all > 0 ? " — " + m.placed + " of " + all + " blocks in" : "";
            if (m.shortOf != null) return "Building " + m.kind.words + how + ", but I'm waiting on " + m.shortOf + ".";
            return FolkTalk.pick(r, "Building " + m.kind.words + how + ". Redstone last, so nothing fires half done.",
                "At " + m.kind.words + how + ". Every block out of the stores, every piston the right way round.");
        }
        int working = 0, lamps = 0;
        for (Machine x : w.machines) if (x.state == State.WORKING) { working++; if (x.kind == Kind.LAMP) lamps++; }
        if (working == 0) return FolkTalk.pick(r, "Drawing up plans. A cane farm first, I think: paper for the library, and no hands needed.",
            "I'm the town's redstone engineer. Pistons, observers, hoppers: give me the parts and I'll give you machines.");
        Machine best = null;
        for (Machine x : w.machines) if (x.kind != Kind.LAMP && (best == null || x.output > best.output)) best = x;
        String brag = best != null && best.output > 0 ? " " + cap(best.kind.words) + " has made " + best.output + " " + best.kind.counts + " so far." : "";
        return FolkTalk.pick(r, "Round my machines: " + (working - lamps) + " of them" + (lamps > 0 ? ", and " + lamps + " street lamps" : "") + "." + brag,
            "Looking the works over. A machine that's minded is a machine that runs." + brag);
    }

    static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** The town's works in lines, for /village redstone, the board and the trade's book. */
    public static List<String> report(ServerLevel level, UUID id) {
        List<String> out = new ArrayList<>();
        Works w = works(id);
        List<VillageFolkEntity> eng = engineers(id);
        out.add("Redstone works of " + Villages.name(id) + ": " + (ready(id) ? "open" : "not yet (wants the Diamond Age, "
            + REDSTONE_TO_OPEN + " redstone and " + QUARTZ_TO_OPEN + " quartz in the stores)") + "; engineers " + eng.size() + " of " + wanted(id));
        int lamps = 0;
        for (Machine m : w.machines) {
            if (m.kind == Kind.LAMP) { if (m.state == State.WORKING) lamps++; continue; }
            Villages.Village v = Villages.get(id);
            String state = m.state == State.WORKING ? "working since day " + m.builtDay
                : "building, " + m.placed + (v != null ? " of " + plan(level, v, m).size() : "") + (m.shortOf != null ? ", waiting on " + m.shortOf : "");
            out.add(cap(m.kind.words) + " at " + m.origin.toShortString() + ": " + state
                + (m.output > 0 ? "; made " + m.output + " " + m.kind.counts + " (" + m.today + " on day " + m.outputDay + ")" : "")
                + (m.kind == Kind.GATE ? "; lever thrown " + m.throwsAll + " times" : "")
                + (m.mended > 0 ? "; " + m.mended + " blocks mended" : "")
                + (m.checked > 0 ? "; looked over " + Math.max(0, (level.getGameTime() - m.checked) / 1200L) + " min ago" : ""));
        }
        if (lamps > 0) out.add(lamps + " street lamps that light themselves at dusk");
        if (!w.made.isEmpty()) {
            StringBuilder sb = new StringBuilder("Parts made at the bench:");
            w.made.forEach((k, n) -> sb.append(' ').append(n).append(' ').append(k.replace('_', ' ')).append(','));
            out.add(sb.substring(0, sb.length() - 1));
        }
        return out;
    }

    /** The engineer's card line (FolkTalk): what it is building or minding, and what its machines have made. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.REDSTONE || f.isBaby() || f.ownerId() == null) return null;
        Works w = works(f.ownerId());
        int working = 0, lamps = 0, made = 0;
        for (Machine m : w.machines) {
            if (m.state != State.WORKING) continue;
            if (m.kind == Kind.LAMP) lamps++; else working++;
            made += m.output;
        }
        StringBuilder sb = new StringBuilder("level ").append(f.tradeLevel(StationTask.REDSTONE));
        Machine m = w.current;
        if (m != null && m.state == State.BUILDING) {
            sb.append("; building ").append(m.kind.words).append(" (").append(m.placed).append(m.plan != null ? " of " + m.plan.size() : "")
                .append(" blocks)").append(m.shortOf != null ? ", waiting on " + m.shortOf : "");
        }
        sb.append("; ").append(working == 1 ? "one machine" : working + " machines").append(" working");
        if (lamps > 0) sb.append(", ").append(lamps).append(lamps == 1 ? " street lamp" : " street lamps");
        if (made > 0) sb.append("; ").append(made).append(" made by them all told");
        int parts = 0;
        for (int n : w.made.values()) parts += n;
        if (parts > 0) sb.append("; ").append(parts).append(" parts made at the bench");
        return sb.toString();
    }

    /**
     * What the engineer writes in the trade's book (TradeBooks.notes): its machines and their real numbers, the parts
     * it has made, and what it has learned of redstone, in its own words.
     */
    public static List<String> bookNotes(UUID id) {
        List<String> out = new ArrayList<>();
        Works w = works(id);
        List<String> built = new ArrayList<>();
        int lamps = 0, mended = 0;
        for (Machine m : w.machines) {
            mended += m.mended;
            if (m.state != State.WORKING) continue;
            if (m.kind == Kind.LAMP) { lamps++; continue; }
            built.add(m.kind.words + " (day " + m.builtDay + (m.output > 0 ? ", " + m.output + " " + m.kind.counts + " so far" : "") + ")");
        }
        if (!built.isEmpty()) out.add("Our machines: " + String.join("; ", built) + ".");
        if (lamps > 0) out.add(lamps + (lamps == 1 ? " street lamp lights" : " street lamps light") + " themselves at dusk, by a daylight sensor turned"
            + " the other way about. Nobody has lit one since.");
        int parts = 0;
        String most = "";
        int mostN = 0;
        for (Map.Entry<String, Integer> e : w.made.entrySet()) {
            parts += e.getValue();
            if (e.getValue() > mostN) { mostN = e.getValue(); most = e.getKey().replace('_', ' '); }
        }
        if (parts > 0) {
            Item mostItem = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(most.replace(' ', '_')));
            out.add("I've made " + parts + " parts at the bench from the stores, " + Bench.words(mostItem, mostN) + " of them. Every one"
                + " by its recipe: a hopper is five iron ingots round a chest; a piston three planks, four cobblestone, an ingot and a"
                + " redstone; an observer six cobblestone, two redstone and a quartz; a comparator three torches, a quartz and stone.");
        }
        if (mended > 0) out.add("I've put back " + mended + (mended == 1 ? " block" : " blocks") + " on my rounds. A machine is only as good as its"
            + " last piston.");
        out.add("Build the redstone last of all, and the crop after that: a machine half built fires at nothing, or at you.");
        out.add("Never let an observer watch the place a piston's head comes into. It sees the head, fires the piston again, and"
            + " the machine runs itself ragged for ever.");
        out.add("Leave the bottom cane: break the second block and the rest falls, and the root grows again. Mud under it, not"
            + " dirt: a hopper reaches through mud for what falls on it.");
        out.add("A sorter's filter holds twenty-two: eighteen of its good and one in each other slot. One more and it lets the"
            + " good past; one fewer and it holds it back. A four-tick repeater after each torch, or a steady stream of goods"
            + " burns the torch out.");
        return out;
    }

    /** The board's line (VillageBoards): the machines at work and what each has made, the lamps, and what is going up. */
    @Nullable
    public static String boardLine(UUID id) {
        Works w = WORKS.get(id);
        if (w == null && Ledger.note(id, "redstone.works") == null) return null;
        w = works(id);
        if (w.machines.isEmpty()) return engineers(id).isEmpty() ? null : "Works: the engineer is drawing up plans.";
        List<String> parts = new ArrayList<>();
        int lamps = 0;
        String building = null;
        for (Machine m : w.machines) {
            if (m.state == State.BUILDING) { building = m.kind.words; continue; }
            if (m.kind == Kind.LAMP) { lamps++; continue; }
            parts.add(m.kind.words + (m.kind == Kind.GATE ? "" : " (" + m.output + " " + m.kind.counts + ")"));
        }
        if (lamps > 0) parts.add(lamps + (lamps == 1 ? " street lamp" : " street lamps"));
        String line = "Works: " + (parts.isEmpty() ? "nothing working yet" : String.join(", ", parts));
        return line + (building != null ? "; building " + building : "") + ".";
    }

    /** All the machines' output, all told: for the trade's book and the town's books. */
    public static int outputAllTold(UUID id) {
        int n = 0;
        for (Machine m : works(id).machines) n += m.output;
        return n;
    }

    // ------------------------------------------------------------------ the books kept

    /** The works written into the town's ledger (they outlast a restart). */
    static void save(UUID id) {
        Works w = WORKS.get(id);
        if (w == null) return;
        inputs = null;
        StringBuilder sb = new StringBuilder();
        for (Machine m : w.machines) {
            if (sb.length() > 0) sb.append(';');
            sb.append(m.kind.name()).append(',').append(m.origin.getX()).append(',').append(m.origin.getY()).append(',').append(m.origin.getZ())
                .append(',').append(m.back.getName()).append(',').append(m.state.name()).append(',').append(m.placed).append(',').append(m.builtDay)
                .append(',').append(m.output).append(',').append(m.mended).append(',').append(m.casing).append(',').append(m.crop)
                .append(',').append(m.docked ? 1 : 0).append(',').append(m.throwsAll).append(',').append(m.plain == null ? "" : m.plain);
        }
        Ledger.note(id, "redstone.works", sb.toString());
        StringBuilder made = new StringBuilder();
        w.made.forEach((k, n) -> made.append(made.length() == 0 ? "" : ",").append(k).append('=').append(n));
        Ledger.note(id, "redstone.made", made.toString());
    }

    private static Works load(UUID id) {
        Works w = new Works();
        String s = Ledger.note(id, "redstone.works");
        if (s != null && !s.isEmpty()) {
            for (String one : s.split(";")) {
                String[] f = one.split(",");
                try {
                    Machine m = new Machine(Kind.valueOf(f[0]), new BlockPos(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3])),
                        Direction.byName(f[4]));
                    m.state = State.valueOf(f[5]);
                    m.placed = Integer.parseInt(f[6]);
                    m.builtDay = Long.parseLong(f[7]);
                    m.output = Integer.parseInt(f[8]);
                    m.mended = Integer.parseInt(f[9]);
                    m.casing = f[10];
                    m.crop = f[11];
                    m.docked = f.length > 12 && f[12].equals("1");
                    m.throwsAll = f.length > 13 ? Integer.parseInt(f[13]) : 0;
                    m.plain = f.length > 14 ? f[14] : null;
                    w.machines.add(m);
                } catch (RuntimeException e) {
                    LOG.warn("[MCA-REDSTONE] a damaged machine in the books of {}: {}", id, one);
                }
            }
        }
        String made = Ledger.note(id, "redstone.made");
        if (made != null && !made.isEmpty()) {
            for (String one : made.split(",")) {
                int eq = one.indexOf('=');
                if (eq > 0) {
                    try { w.made.put(one.substring(0, eq), Integer.parseInt(one.substring(eq + 1))); } catch (NumberFormatException ignored) { }
                }
            }
        }
        return w;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the trade open now (as if the stores had been looked at). */
    public static void openForTests(UUID id) {
        OPEN.put(id, true);
        Ledger.note(id, "redstone.open", "1");
    }

    /** Tests: the stores looked at now. */
    public static boolean lookAtStoresForTests(ServerLevel level, Villages.Village v) {
        LOOKED.remove(v.id());
        return lookAtStores(level, v);
    }

    /** Tests: a machine of this kind put on the town's books at a chosen site (the site as siting would find it). */
    public static Machine planForTests(ServerLevel level, Villages.Village v, Kind k, BlockPos origin, Direction back) {
        Machine m = new Machine(k, origin, back);
        Machines.Drawing d = Machines.drawing(k.drawing);
        m.casing = casingFor(level, v, d.count('#') + 8);
        if (k == Kind.MELON) m.crop = cropFor(level, v);
        return bookForTests(v, m);
    }

    /** Tests: a site found by siting (siteForTests) put on the town's books as it is. */
    public static Machine bookForTests(Villages.Village v, Machine m) {
        works(v.id()).machines.add(m);
        save(v.id());
        return m;
    }

    /** Tests: what the stores can spare of each thing, by name (only what they can spare some of). */
    public static Map<String, Integer> spareForTests(ServerLevel level, Villages.Village v) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        spareStock(level, v).forEach((it, n) -> { if (n > 0) out.put(BuiltInRegistries.ITEM.getKey(it).getPath(), n); });
        return out;
    }

    /** Tests: may the stores run to a machine of this kind now? */
    public static boolean affordableForTests(ServerLevel level, Villages.Village v, Kind k) {
        return affordable(level, v, k);
    }

    /** Tests: where siting would put a machine of this kind (not booked). */
    @Nullable
    public static Machine siteForTests(ServerLevel level, Villages.Village v, Kind k) {
        return site(level, v, k);
    }

    /** Tests: a machine laid as the engineer lays it, but all at once (every block out of the stores, its parts made at
     *  the bench). The step it stopped at. */
    public static Step buildForTests(ServerLevel level, Villages.Village v, Machine m, @Nullable VillageFolkEntity f) {
        Step s = Step.LAID;
        for (int i = 0; i < 4000 && s == Step.LAID; i++) s = build(level, v, m, f, 64);
        if (s == Step.DONE) finished(level, v, m, f);
        return s;
    }

    /** Tests: the next thing the engineer would take up (planning a machine if it would). */
    @Nullable
    public static Machine nextForTests(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f) {
        Works w = works(v.id());
        w.nextPlan = -100000L;
        w.noSite.clear();
        return next(level, v, w, f);
    }

    /** Tests: a machine's round now. */
    public static int roundForTests(ServerLevel level, Villages.Village v, Machine m, @Nullable VillageFolkEntity f) {
        return round(level, v, m, f);
    }

    /** Tests: the watch's look at the gate now. */
    public static void gateWatchForTests(ServerLevel level, Villages.Village v) {
        gateWatch(level, v);
    }

    /** Tests: a machine's planned blocks. */
    public static List<Machines.Placement> planOfForTests(ServerLevel level, Villages.Village v, Machine m) {
        return plan(level, v, m);
    }

    /** Tests: the lamp a lamp machine lights. */
    public static boolean litForTests(ServerLevel level, Machine m) {
        BlockState s = level.getBlockState(m.origin.above(2));
        return s.is(Blocks.REDSTONE_LAMP) && s.getValue(RedstoneLampBlock.LIT);
    }

    /** Tests: the town's tick now (the stores looked at, an engineer appointed). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        LOOKED.remove(v.id());
        tick(level, v);
    }

    /** The sorter's chests (for the tests and the stage): left to right. */
    public static List<BlockPos> cellsOf(Machine m, char ch) {
        List<BlockPos> out = new ArrayList<>();
        for (Machines.Cell c : sortedCells(m, ch)) out.add(Machines.at(m.origin, m.back, c));
        return out;
    }

    /** Tests: the engineer plans nothing new of its own (it still builds what is booked and keeps its rounds). */
    public static void quietForTests(UUID id) {
        works(id).nextPlan = Long.MAX_VALUE / 4;
    }

    /** Tests: a machine's blocks not as drawn (the storehouse's own cells, where the sorter lies against it, passed over). */
    public static List<Machines.Placement> faultsForTests(ServerLevel level, Villages.Village v, Machine m) {
        List<Machines.Placement> out = new ArrayList<>();
        for (Machines.Placement p : Machines.faults(level, plan(level, v, m))) {
            if (!(level.getBlockState(p.pos()).getBlock() instanceof StorehouseBlock)) out.add(p);
        }
        return out;
    }

    /** Tests: the parts made at the bench, all told, by name. */
    public static Map<String, Integer> partsMadeForTests(UUID id) {
        return Map.copyOf(works(id).made);
    }

    /** Tests: the street's lamp spots (the ground under each post), nearest the square first. */
    public static List<BlockPos> lampSpotsForTests(ServerLevel level, Villages.Village v) {
        return lampSpots(level, v);
    }

    /** Tests: the machine the engineer has in hand, if any. */
    @Nullable
    public static Machine currentForTests(UUID id) {
        return works(id).current;
    }
}
