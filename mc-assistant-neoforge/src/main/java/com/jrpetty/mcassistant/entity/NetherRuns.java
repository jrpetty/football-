package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [nether] The Nether runs: the town's runners really go through the gateway, work the Nether on the far side, and come
 * back through it with what they found.
 *
 * <p><b>Through the gateway.</b> The team walks to the gateway, the leader first, and each steps into the portal and
 * goes through (Entity.changeDimension, by the portal's own rule: NetherPortalBlock.getPortalDestination finds the
 * portal on the far side, or makes one, as it would for a player). The ones through wait by the portal for the rest; one
 * that cannot get to the gateway in a minute stays home. The ground round each runner in the Nether is kept awake
 * (ChunkLoad, as for the town's travellers) while it is there, and the outpost's with it, and let go when it leaves.
 * No other folk of the town ever goes through a portal (VillageFolkEntity.canUsePortal).
 *
 * <p><b>While they are there</b> (hold): the run is a runner's whole day, on either side of the portal. Nothing of the
 * town's that would send it to bed, the square or its post reaches it (VillageFolkEntity.aiStep), and the town knows
 * where it is: away through the gateway (Nether.away: not lost, not idle, not homeless; its bed kept: awayBeds). On the
 * far side, the outpost first on a first run (NetherOutpost), then the work (NetherWork), a night in the outpost on a
 * long run, and home when the plan's time is up, or early, saying why: hurt, out of food or arrows, a pack full, one of
 * the team lost.
 *
 * <p><b>Risks.</b> A runner on fire drinks its fire resistance; in lava it gets out; badly hurt it falls back to the
 * outpost (RetreatGoal goes there: safety) and the team turns for home. A runner can die: the town is told where, the
 * team takes up what it dropped if it can (a Runner's Satchel floats on lava), and goes home. One that has dropped out
 * of sight of the team a minute is missing: the team goes back to where it was last seen and calls; not found, the
 * team goes home and the town sends a rescue party through (the remaining runners and two of the watch) next morning.
 *
 * <p><b>Home.</b> Back through the portal (the gateway), to the storehouse with everything, booked as the runners' work;
 * what they took and did not use goes back, not counted. The haul is counted and told: the chronicle, the gazette, the
 * board, the morning assembly, each runner's card, and the Nether page of the town's books.
 */
public final class NetherRuns {

    static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private NetherRuns() {}

    /** A runner thinks this often, in ticks; its fireballs it watches for every other tick. */
    static final long STEP = 5;
    /** The chunks kept awake round a runner in the Nether, and round the outpost while any of them is through. */
    static final int WINDOW = 2, OUTPOST_WINDOW = 1;
    /** How long the team waits at either side of the portal for the rest of it. */
    static final long GATE_WAIT = 1200;
    /** Out of sight of the team this long, a runner is missed; the team searches this long before it gives up. */
    static final long MISSED = 1200, SEARCH_FOR = 2400;
    /** The team keeps within this of its leader; the leader waits for anybody further. */
    static final double CLOSE = 5.0, WAIT = 12.0;

    // ------------------------------------------------------------------ what the runners find, kept with the town

    /** What the runners note in the Nether. */
    public enum Kind {
        OUTPOST("the outpost"), QUARTZ("quartz"), GLOWSTONE("glowstone"), GOLD("nether gold ore"), DEBRIS("ancient debris"),
        WART("nether wart"), SOUL("soul sand"), SPAWNER("a blaze spawner"), FORTRESS("a fortress"), BASTION("a bastion"),
        PIGLINS("piglins"), LAVA("a sea of lava"), LOST("where a runner was lost");

        public final String words;

        Kind(String words) { this.words = words; }
    }

    /** One thing in the Nether report: what, where (in the Nether), when, by whom, and two numbers (how many seen and
     *  taken; for the fortress, how far from the outpost and how far the path has got). */
    public record Find(Kind kind, String label, BlockPos at, long day, String by, int a, int b) {
        String encode() {
            return kind.name() + "|" + label.replace('|', '/') + "|" + at.getX() + "|" + at.getY() + "|" + at.getZ() + "|" + day + "|"
                + by.replace('|', '/') + "|" + a + "|" + b;
        }

        @Nullable
        static Find decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 9) return null;
            try {
                return new Find(Kind.valueOf(p[0]), p[1], new BlockPos(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])),
                    Long.parseLong(p[5]), p[6], Integer.parseInt(p[7]), Integer.parseInt(p[8]));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        Find with(int na, int nb) {
            return new Find(kind, label, at, day, by, na, nb);
        }
    }

    /** Everything the runners have noted, oldest first. */
    public static List<Find> report(@Nullable UUID village) {
        List<Find> out = new ArrayList<>();
        String s = village == null ? null : Ledger.note(village, "nether.finds");
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            Find f = Find.decode(line);
            if (f != null) out.add(f);
        }
        return out;
    }

    static void saveReport(UUID village, List<Find> all) {
        while (all.size() > 120) {
            int drop = 0;
            for (int i = 0; i < all.size(); i++) if (all.get(i).kind() == Kind.QUARTZ || all.get(i).kind() == Kind.GLOWSTONE) { drop = i; break; }
            all.remove(drop);
        }
        StringBuilder sb = new StringBuilder();
        for (Find f : all) sb.append(sb.length() == 0 ? "" : "\n").append(f.encode());
        Ledger.note(village, "nether.finds", sb.toString());
    }

    /** Into the report: new, or folded into the one of its kind within so many blocks (its counts added). True if new. */
    static boolean record(UUID village, Find x) {
        List<Find> all = report(village);
        int near = switch (x.kind()) {
            case FORTRESS, BASTION -> 64;
            case OUTPOST -> 8;
            case SPAWNER -> 2;
            default -> 6;
        };
        for (int i = 0; i < all.size(); i++) {
            Find y = all.get(i);
            if (y.kind() != x.kind() || y.at().distSqr(x.at()) > near * near) continue;
            all.set(i, x.kind() == Kind.FORTRESS || x.kind() == Kind.BASTION || x.kind() == Kind.OUTPOST
                ? y.with(Math.max(y.a(), x.a()), Math.max(y.b(), x.b())) : y.with(Math.max(y.a(), x.a()), y.b() + x.b()));
            saveReport(village, all);
            return false;
        }
        all.add(x);
        saveReport(village, all);
        return true;
    }

    /** The fortress the runners know of, or null. */
    @Nullable
    public static Find fortress(@Nullable UUID village) {
        for (Find f : report(village)) if (f.kind() == Kind.FORTRESS) return f;
        return null;
    }

    /** The bastion the runners know of, or null. */
    @Nullable
    public static Find bastion(@Nullable UUID village) {
        for (Find f : report(village)) if (f.kind() == Kind.BASTION) return f;
        return null;
    }

    // ------------------------------------------------------------------ a run

    /** The team's run: who, where it stands, what it has found and brought, why it turned for home. */
    public static final class Run {
        public enum Phase { GATE, ARRIVE, OUTPOST, WORK, CAMP, HOMEWARD, STORE, DONE }

        final UUID village;
        final long day;
        final List<UUID> members = new ArrayList<>();
        UUID leader;
        Phase phase = Phase.GATE;
        NetherPlan.Plan plan;
        String planWords = "";
        double days = 1.0;
        long startTime, turnAt, phaseSince;
        /** The gateway's portal block it goes through at home; the portal on the far side. */
        @Nullable BlockPos gate, netherPortal;
        /** Who is through (in the Nether), who is back home; who stayed home at the start; who is lost. */
        final Set<UUID> through = new HashSet<>(), back = new HashSet<>(), stored = new HashSet<>();
        final List<String> lost = new ArrayList<>(), fallen = new ArrayList<>(), stayed = new ArrayList<>();
        final Map<UUID, Leg> legs = new ConcurrentHashMap<>();
        /** The work in hand (NetherWork), and what it has noted this run. */
        @Nullable NetherWork.Task task;
        final List<Find> found = new ArrayList<>();
        final Set<Long> passed = new HashSet<>();
        /** What came into the packs out of the Nether, by name (the story) and by item (the share, the trophies). */
        final Map<String, Integer> haul = new LinkedHashMap<>(), stored0 = new LinkedHashMap<>();
        final Map<Item, Integer> storedItems = new LinkedHashMap<>();
        /** The risks met, in words, and the tallies. */
        final List<String> events = new ArrayList<>();
        int mined, slain, blazes, ghasts, deflected, barters, placed, cut, torches, potions, retreats;
        boolean outpostBuilt, homeward, reported, rescue;
        String why = "";
        long camped = -1;
        int nights;
        /** A player along (NetherGuests): who, its name, a share by agreement, since when it was waited for. */
        @Nullable UUID guest;
        String guestName = "";
        boolean guestShare;
        long guestWait = -1;
        String asked = "";
        /** The search: for whom, where it was last seen, since when. */
        @Nullable UUID missing;
        @Nullable BlockPos missingAt;
        long missingSince = -1;
        String missingName = "";
        /** The lost one a rescue run goes for, and where. */
        @Nullable UUID rescuing;
        @Nullable BlockPos rescueAt;

        Run(UUID village, long day) {
            this.village = village;
            this.day = day;
        }

        public List<UUID> members() { return List.copyOf(members); }
        public String planWords() { return planWords; }
        public UUID leader() { return leader; }
        public Phase phase() { return phase; }
        public String phaseWords() { return homeward && phase != Phase.STORE ? "homeward" : phase.name().toLowerCase(Locale.ROOT); }
        public double days() { return days; }
        public long turnAt() { return turnAt; }
        @Nullable public BlockPos gate() { return gate; }
        @Nullable public BlockPos netherPortal() { return netherPortal; }
        public Set<UUID> through() { return Set.copyOf(through); }
        public Map<String, Integer> haul() { return haul; }
        public Map<Item, Integer> storedItems() { return storedItems; }
        public List<String> events() { return List.copyOf(events); }
        public List<Find> found() { return List.copyOf(found); }
        public int mined() { return mined; }
        public int barters() { return barters; }
        public int blazes() { return blazes; }
        public int deflected() { return deflected; }
        public int retreats() { return retreats; }
        public int potions() { return potions; }
        public int slain() { return slain; }
        public int placed() { return placed; }
        public int cut() { return cut; }
        public String guestName() { return guestName; }
        @Nullable public UUID guest() { return guest; }
        public List<String> fallen() { return List.copyOf(fallen); }
        public List<String> lost() { return List.copyOf(lost); }
        public boolean homeward() { return homeward; }
        public boolean reported() { return reported; }
        public String why() { return why; }
        public boolean outpostBuilt() { return outpostBuilt; }
        @Nullable public UUID missing() { return missing; }
        public boolean rescue() { return rescue; }
        @Nullable public NetherWork.Task task() { return task; }

        /** "day 2 of 3" at this day time. */
        public String dayOf(long now) {
            long n = Math.max(1, now / 24000L - startTime / 24000L + 1);
            return "day " + n + " of " + Math.max(1, (int) Math.ceil(days));
        }

        void event(String what) {
            if (events.size() < 24 && !events.contains(what)) events.add(what);
        }

        void phase(Phase p, long now) {
            if (phase != p) {
                phase = p;
                phaseSince = now;
            }
        }
    }

    /** One runner's part of the run: its clocks, what it is digging or placing, what came into its pack out of the Nether. */
    static final class Leg {
        long next, stillTick, spoke = -100000, ate = -100000, drank = -100000, shield = -100000, deflect = -100000, crossed = -100000,
            seen, digTick, waitFrom = -1, scan = -100000, collect = -100000, called = -100000, gateSince = -1;
        @Nullable BlockPos stillAt, digging, claim, lastSeen, pillarFrom;
        int dug, digNeeded, pillar, cutSteps, stuck;
        boolean retreating, arrived, cutting;
        /** Where it is making for (NetherWork.makeFor), and when it started cutting its way there. */
        @Nullable BlockPos goal;
        long cutSince = -1, threw = -100000, shot = -100000, shielding = -1;
        @Nullable UUID foe;
        /** What came into its pack out of the Nether (the haul it carries), by item. */
        final Map<Item, Integer> got = new HashMap<>();
    }

    /** The runs under way, by town; and the run each runner is on. */
    private static final Map<UUID, Run> RUNS = new ConcurrentHashMap<>();
    private static final Map<UUID, Run> MEMBER = new ConcurrentHashMap<>();
    /** The ground kept awake in the Nether round each runner (where it was last forced), and round each town's outpost. */
    private static final Map<UUID, BlockPos> WINDOWS = new ConcurrentHashMap<>(), OUTPOST_WINDOWS = new ConcurrentHashMap<>();
    /** The beds at home of the runners away through the gateway: theirs still. */
    private static final Map<UUID, Map<UUID, BlockPos>> AWAY_BEDS = new ConcurrentHashMap<>();
    /** The runners lost in the Nether, waiting to be found: who, of which town, where, since when. */
    record Lost(UUID village, String name, BlockPos at, long since) {}
    private static final Map<UUID, Lost> LOST = new ConcurrentHashMap<>();
    /** The day each runner last went through. */
    private static final Map<UUID, Long> WENT = new ConcurrentHashMap<>();
    /** The gateway's portal at home, by town (found again when it is not there any more). */
    private static final Map<UUID, BlockPos> GATES = new ConcurrentHashMap<>();
    /** What the runners came home with, for the next morning assembly. */
    private static final Map<UUID, List<String>> REPORTS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        RUNS.clear();
        MEMBER.clear();
        WINDOWS.clear();
        OUTPOST_WINDOWS.clear();
        AWAY_BEDS.clear();
        LOST.clear();
        WENT.clear();
        GATES.clear();
        REPORTS.clear();
        NetherWork.resetForTests();
        NetherOutpost.resetForTests();
        NetherPlan.resetForTests();
        NetherGuests.resetForTests();
        NetherHome.resetForTests();
    }

    // ------------------------------------------------------------------ who is where

    /** The run a runner is on, or null. */
    @Nullable
    public static Run runOf(VillageFolkEntity f) {
        Run r = MEMBER.get(f.getUUID());
        return r != null && !r.reported && r.members.contains(f.getUUID()) ? r : null;
    }

    /** The town's run under way, or null. */
    @Nullable
    public static Run run(@Nullable UUID village) {
        Run r = village == null ? null : RUNS.get(village);
        return r == null || r.reported ? null : r;
    }

    /** Is this folk on a Nether run (on either side of the portal)? */
    public static boolean on(VillageFolkEntity f) {
        return runOf(f) != null;
    }

    /** Is this folk out of its town's world (in the Nether)? */
    public static boolean elsewhere(AssistantEntity f) {
        if (!(f instanceof VillageFolkEntity v) || f.ownerId() == null) return false;
        Villages.Village home = Villages.get(f.ownerId());
        Level here = f.level();
        return home != null ? !home.dim().equals(here.dimension()) : here.dimension() == Level.NETHER && v.stationTask() == StationTask.NETHER;
    }

    /** Is this entity in the Nether? */
    public static boolean inNether(Entity e) {
        return e.level().dimension() == Level.NETHER;
    }

    /** Away through the gateway, as the town sees it (Nether.away): on a run, or in the Nether, or lost there. */
    public static boolean away(VillageFolkEntity f) {
        return on(f) || elsewhere(f) || LOST.containsKey(f.getUUID());
    }

    /** A Nether runner loaded in the Nether (VillageFolkEntity.readAdditionalSaveData): its town is not put on the map of
     *  this world from it. */
    public static boolean loadedAway(VillageFolkEntity f) {
        return f.stationTask() == StationTask.NETHER && f.level().dimension() == Level.NETHER;
    }

    /** The beds at home of the runners away through the gateway (Villages.bedsClaimed). */
    public static java.util.Collection<BlockPos> awayBeds(@Nullable UUID village) {
        Map<UUID, BlockPos> beds = village == null ? null : AWAY_BEDS.get(village);
        return beds == null ? List.of() : List.copyOf(beds.values());
    }

    /** Is this runner lost in the Nether, waiting to be found? */
    public static boolean lost(UUID folk) {
        return LOST.containsKey(folk);
    }

    /** The runners of a town lost in the Nether (the Nether page, the rescue). */
    static List<Lost> lostOf(UUID village) {
        List<Lost> out = new ArrayList<>();
        for (Lost l : LOST.values()) if (l.village().equals(village)) out.add(l);
        return out;
    }

    /**
     * For the auto-target goal (AssistantEntity.shouldAutoAttack): may this runner take this one on? Never a piglin or a
     * zombified piglin that has not turned on it (the gold it wears keeps the one off it, and the other's whole crowd comes
     * at whoever strikes one); anything else hostile near it, at home or through the gateway.
     */
    public static boolean mayTakeOn(AssistantEntity a, @Nullable LivingEntity target) {
        if (target == null) return true;
        if (target instanceof AbstractPiglin || target instanceof ZombifiedPiglin) {
            return target instanceof net.minecraft.world.entity.Mob m && m.getTarget() == a;
        }
        return true;
    }

    /** Where a hurt runner in the Nether falls back to (RetreatGoal): the outpost's room, or the portal; null elsewhere. */
    @Nullable
    public static BlockPos safety(AssistantEntity a) {
        if (!(a instanceof VillageFolkEntity f) || !inNether(f)) return null;
        Run r = runOf(f);
        UUID village = f.ownerId();
        if (village == null) return null;
        NetherOutpost.Room room = NetherOutpost.room(village);
        if (room != null && room.built()) return room.inside();
        return r != null && r.netherPortal != null ? r.netherPortal : null;
    }

    // ------------------------------------------------------------------ the gateway

    /** A portal block of the town's gateway, lit (found again when it is not there any more), or null. */
    @Nullable
    public static BlockPos gatePortal(ServerLevel home, UUID village) {
        BlockPos known = GATES.get(village);
        if (known != null && home.isLoaded(known) && home.getBlockState(known).is(Blocks.NETHER_PORTAL)) return known;
        BlockPos gate = Villages.builtAt(village, "gateway");
        if (gate == null || !home.isLoaded(gate)) return null;
        BlockPos best = null;
        for (BlockPos p : BlockPos.betweenClosed(gate.offset(-6, -2, -6), gate.offset(6, 7, 6))) {
            if (!home.getBlockState(p).is(Blocks.NETHER_PORTAL)) continue;
            if (best == null || p.getY() < best.getY() || p.getY() == best.getY() && p.distSqr(gate) < best.distSqr(gate)) best = p.immutable();
        }
        if (best != null) GATES.put(village, best);
        return best;
    }

    /** The side of a portal (its normal) that faces this spot. */
    static Direction faceToward(BlockState portal, BlockPos at, BlockPos toward) {
        Direction.Axis axis = portal.hasProperty(NetherPortalBlock.AXIS) ? portal.getValue(NetherPortalBlock.AXIS) : Direction.Axis.X;
        if (axis == Direction.Axis.X) return toward.getZ() >= at.getZ() ? Direction.SOUTH : Direction.NORTH;
        return toward.getX() >= at.getX() ? Direction.EAST : Direction.WEST;
    }

    /** Where the runners stand to wait before the gateway: two out from the portal on the town's side, on the ground. */
    @Nullable
    static BlockPos beforeTheGate(ServerLevel home, Villages.Village v, BlockPos portal) {
        Direction out = faceToward(home.getBlockState(portal), portal, v.centre());
        BlockPos bottom = portal;
        while (home.getBlockState(bottom.below()).is(Blocks.NETHER_PORTAL)) bottom = bottom.below();
        for (int k = 2; k <= 4; k++) {
            for (int dy = 0; dy >= -2; dy--) {
                BlockPos q = bottom.relative(out, k).above(dy);
                if (CaveDwellers.standable(home, q)) return q;
            }
        }
        return bottom.relative(out, 2);
    }

    // ------------------------------------------------------------------ setting out

    /**
     * The team fitted out and off through the gateway, the most experienced leading, on a plan (NetherPlan: what the town
     * needs, the walk, cut to what it can spare), told on the board, in the chronicle and on the Nether page. Too little
     * to go with, it waits, and says why. Returns the run, or null if nobody went.
     */
    @Nullable
    static Run setOut(ServerLevel home, Villages.Village v, long day, boolean forced) {
        UUID id = v.id();
        if (run(id) != null) return null;
        BlockPos portal = gatePortal(home, id);
        if (portal == null) {
            if (!forced) return null;
            if (!Nether.relight(home, v)) return null;
            portal = gatePortal(home, id);
            if (portal == null) return null;
        }
        int reach = Villages.townReach(id) + 40;
        List<VillageFolkEntity> team = new ArrayList<>();
        for (VillageFolkEntity m : NetherRunners.runners(id)) {
            if (on(m) || elsewhere(m) || LOST.containsKey(m.getUUID()) || m.isSleeping() || m.trip() != null || m.expedition() != null) continue;
            if (!forced && (WENT.getOrDefault(m.getUUID(), -1L) >= day || m.getHealth() < m.getMaxHealth() * 0.75F || Assemblies.attending(m)
                || Health.laidUp(m))) continue;
            if (Scouts.flat(m.blockPosition(), v.centre()) > (double) reach * reach) continue;
            team.add(m);
        }
        // [nether] A rescue: the watch sends two of its best with them, for the lost one.
        List<Lost> lostOnes = lostOf(id);
        if (team.isEmpty()) return null;
        team.sort((a, b) -> NetherRunners.better(a, b) ? -1 : NetherRunners.better(b, a) ? 1 : 0);
        NetherPlan.Plan plan = NetherPlan.plan(home, v, team);
        VillageFolkEntity first = team.get(0);
        if (plan.days() <= 0) {
            NetherRunners.waited(home, v, first, plan);
            return null;
        }
        Run r = new Run(id, day);
        r.plan = plan;
        r.planWords = plan.words();
        r.days = Math.max(NetherPlan.SHORTEST, plan.days());
        r.startTime = home.getDayTime();
        r.turnAt = NetherPlan.turnAt(r.startTime, r.days);
        r.gate = portal;
        r.phaseSince = home.getGameTime();
        if (!lostOnes.isEmpty()) {
            Lost l = lostOnes.get(0);
            r.rescue = true;
            r.rescuing = null;
            for (Map.Entry<UUID, Lost> e : LOST.entrySet()) if (e.getValue() == l) r.rescuing = e.getKey();
            r.rescueAt = l.at();
            r.missingName = l.name();
            for (VillageFolkEntity g : NetherRunners.rescuers(home, v, 2)) team.add(g);
        }
        List<VillageFolkEntity> going = new ArrayList<>();
        int goldLeft = plan.gold() * team.size();
        for (VillageFolkEntity m : team) {
            List<String> got = NetherRunners.kitUp(home, v, m, plan, m == first, goldLeft > 0);
            goldLeft -= m.countMatching(s -> s.is(Items.GOLD_INGOT));
            WENT.put(m.getUUID(), day);
            if (m.countMatching(CaveDwellers::food) == 0) {
                FolkTalk.speak(m, "Nothing in the stores to take through with us. Nobody goes through that gateway hungry.");
                continue;
            }
            going.add(m);
            LOG.info("[MCA-NETHER] {} fitted out: {}", m.displayNameCap(), got);
        }
        if (going.isEmpty()) return null;
        VillageFolkEntity lead = going.get(0);
        r.leader = lead.getUUID();
        long now = home.getGameTime();
        for (VillageFolkEntity m : going) {
            r.members.add(m.getUUID());
            MEMBER.put(m.getUUID(), r);
            Leg leg = new Leg();
            leg.seen = now;
            r.legs.put(m.getUUID(), leg);
            if (m.bedPos() != null) AWAY_BEDS.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(m.getUUID(), m.bedPos().immutable());
            m.clearQueue();
            m.getNavigation().stop();
            m.brain("off through the gateway with the Nether runners");
        }
        RUNS.put(id, r);
        NetherGuests.setOut(home, r, lead);
        Ledger.note(id, "nether.plan", plan.words() + (r.asked.isEmpty() ? "" : " (" + r.asked + ")"));
        Ledger.note(id, "nether.reckoning", String.join("\n", plan.reckoning()));
        keep(r);
        List<String> names = new ArrayList<>();
        for (VillageFolkEntity m : going) if (m != lead) names.add(m.displayNameCap());
        String who = lead.displayNameCap() + (names.isEmpty() ? "" : " leading " + JobMarket.join(names)) + (r.guestName.isEmpty() ? "" : ", with " + r.guestName + " along");
        Villages.tell(id, day, r.rescue ? "the Nether runners (" + who + ") went back through the gateway to look for " + r.missingName
            : "the Nether runners (" + who + ") went through the gateway: " + plan.words());
        if (r.guestName.isEmpty()) {
            String when = r.days > 1.0 ? "Back in " + CaveTrips.numberWord((int) Math.ceil(r.days)) + " days." : r.days < 1.0 ? "Back by noon." : "Back by dusk.";
            FolkTalk.speak(lead, r.rescue ? "We're going back for " + r.missingName + ". Stay close, and keep your eyes open."
                : plan.words().replaceFirst("\\.$", "") + ". " + (names.isEmpty() ? "" : JobMarket.join(names) + ", with me. ") + when);
        }
        LOG.info("[MCA-NETHER] the runners of {} set out: {} leading {}; {} (gate {}, turns at {})", Villages.name(id), lead.displayNameCap(), names,
            plan.words(), portal.toShortString(), r.turnAt);
        return r;
    }

    /** Tests and the stage: the town's runners off through the gateway now, whatever the hour or the day. */
    @Nullable
    public static Run sendForTests(ServerLevel home, Villages.Village v) {
        for (VillageFolkEntity m : NetherRunners.runners(v.id())) WENT.remove(m.getUUID());
        return setOut(home, v, home.getDayTime() / 24000L, true);
    }

    /** The run kept with the town (a restart: the runners come home with what they have). */
    static void keep(Run r) {
        if (r.reported) return;
        StringBuilder m = new StringBuilder();
        for (UUID u : r.members) m.append(m.length() == 0 ? "" : ",").append(u);
        Ledger.note(r.village, "nether.run", r.startTime + "|" + r.turnAt + "|" + r.days + "|" + r.leader + "|" + m + "|"
            + r.planWords.replace('|', '/').replace('\n', ' '));
    }

    /** The town's run as kept (members, when it turns home, its plan), or null: {start, turnAt, days, leader, members, words}. */
    @Nullable
    static String[] kept(UUID village) {
        String s = Ledger.note(village, "nether.run");
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|", -1);
        return p.length < 6 ? null : p;
    }

    /** How many of the town's runners are away on the run kept with it and not in the world just now. */
    static int awayUnseen(UUID village, List<VillageFolkEntity> here) {
        String[] k = kept(village);
        if (k == null) return 0;
        Set<UUID> seen = new HashSet<>();
        for (VillageFolkEntity f : here) seen.add(f.getUUID());
        int n = 0;
        for (String u : k[4].split(",")) {
            try {
                if (!u.isEmpty() && !seen.contains(UUID.fromString(u))) n++;
            } catch (IllegalArgumentException ignored) {
                // an unreadable id: not counted
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ a runner's step

    /**
     * A runner on a run (VillageFolkEntity.aiStep, every tick): its fireballs watched every other tick, its step every
     * five. True while the run is its day. A folk of the town in the Nether with no run (a restart, a stray) makes its way
     * home through the nearest portal.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Run r = MEMBER.get(f.getUUID());
        if (r == null || r.reported || !r.members.contains(f.getUUID())) {
            if (r != null) MEMBER.remove(f.getUUID());
            if (f.isShowcase() || f.ownerId() == null || f.isHired()) return false;
            if (!elsewhere(f)) return false;
            return stray(f, level);
        }
        Leg leg = r.legs.computeIfAbsent(f.getUUID(), k -> new Leg());
        long now = level.getGameTime();
        if (inNether(f) && (now & 1L) == 0) NetherWork.watchTheSky(level, f, r, leg);
        if (now < leg.next) return true;
        leg.next = now + STEP;
        try {
            drive(level, f, r, leg);
        } catch (RuntimeException ex) {
            LOG.warn("[MCA-NETHER] {}'s step: {}", f.displayNameCap(), ex.toString(), ex);
        }
        return true;
    }

    /** The town's world, from any. */
    @Nullable
    static ServerLevel home(MinecraftServer server, UUID village) {
        Villages.Village v = Villages.get(village);
        return v == null ? null : server.getLevel(v.dim());
    }

    /** The Nether, from any world. */
    @Nullable
    static ServerLevel nether(MinecraftServer server) {
        return server.getLevel(Level.NETHER);
    }

    /** A runner, wherever it is (the town's world or the Nether), alive; or null. */
    @Nullable
    static VillageFolkEntity find(MinecraftServer server, UUID village, UUID u) {
        ServerLevel h = home(server, village), n = nether(server);
        for (ServerLevel l : new ServerLevel[]{ h, n }) {
            if (l == null) continue;
            if (l.getEntity(u) instanceof VillageFolkEntity f && f.isAlive()) return f;
        }
        return null;
    }

    /** The team, wherever each is, alive and on the run. */
    static List<VillageFolkEntity> members(MinecraftServer server, Run r) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (UUID u : r.members) {
            VillageFolkEntity f = find(server, r.village, u);
            if (f != null) out.add(f);
        }
        return out;
    }

    /** The team members in this world. */
    static List<VillageFolkEntity> here(ServerLevel level, Run r) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (UUID u : r.members) if (level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive()) out.add(f);
        return out;
    }

    /** The run's leader now: the one it set out with, or (fallen, lost) the most experienced still on it. */
    static VillageFolkEntity lead(MinecraftServer server, Run r, VillageFolkEntity f) {
        if (r.leader != null && r.members.contains(r.leader)) {
            VillageFolkEntity l = find(server, r.village, r.leader);
            if (l != null) return l;
        }
        VillageFolkEntity best = null;
        for (VillageFolkEntity m : members(server, r)) if (best == null || NetherRunners.better(m, best)) best = m;
        if (best == null) best = f;
        r.leader = best.getUUID();
        best.brain("leading the Nether runners now");
        return best;
    }

    /** One runner's step: where it is, and the run's phase. */
    static void drive(ServerLevel level, VillageFolkEntity f, Run r, Leg leg) {
        MinecraftServer server = level.getServer();
        Villages.Village v = Villages.get(r.village);
        ServerLevel home = home(server, r.village);
        if (v == null || home == null) {
            end(level, r, "the town was gone");
            return;
        }
        long now = level.getGameTime();
        boolean nether = inNether(f);
        if (nether) {
            keepAwake(level, f);
            r.through.add(f.getUUID());
            r.back.remove(f.getUUID());
            keepOutpostAwake(level, r);
        }
        VillageFolkEntity lead = lead(server, r, f);
        boolean leading = lead == f;
        if (f.isShiftKeyDown() && r.phase != Run.Phase.CAMP) f.setShiftKeyDown(false);
        // Safety first, on either side: fire, lava, a fall, hunger, a fight (NetherWork).
        if (nether && NetherWork.safety(level, f, r, leg)) return;
        if (NetherWork.fight(level, f, r, leg)) return;
        // Badly hurt: back to the outpost to heal; the whole team turns for home (NetherWork.safety did the eating).
        if (nether && !r.homeward && f.getHealth() < f.getMaxHealth() * 0.35F) {
            turnBack(server, r, f, f.displayNameCap() + " was badly hurt");
        }
        switch (r.phase) {
            case GATE -> gatePhase(server, home, level, v, f, r, leg, lead);
            case ARRIVE -> arrive(server, level, f, r, leg, lead);
            case OUTPOST -> {
                if (!nether) { toGate(home, v, f, r, leg, lead); return; }
                if (NetherOutpost.work(level, f, r, leg, leading)) return;
                if (leading) {
                    r.phase(Run.Phase.WORK, now);
                    keep(r);
                }
            }
            case WORK -> {
                if (!nether) { toGate(home, v, f, r, leg, lead); return; }
                if (leading) {
                    String why = whyHome(level, f, r);
                    if (why != null) {
                        turnBack(server, r, f, why);
                        return;
                    }
                    if (NetherOutpost.nightfall(level, r)) {
                        NetherOutpost.pitch(level, f, r);
                        return;
                    }
                    if (searching(level, f, r, leg)) return;
                    if (NetherGuests.waitForPlayer(level, f, r)) return;
                    NetherWork.lead(level, f, r, leg);
                } else {
                    NetherWork.help(level, f, r, leg, lead);
                }
            }
            case CAMP -> {
                if (!nether) { toGate(home, v, f, r, leg, lead); return; }
                NetherOutpost.camp(level, f, r, leg, leading);
            }
            case HOMEWARD -> homeward(server, home, level, v, f, r, leg, lead);
            case STORE -> {
                if (nether) { homeward(server, home, level, v, f, r, leg, lead); return; }
                toTheStores(home, v, f, r, leg);
            }
            case DONE -> MEMBER.remove(f.getUUID());
        }
    }

    // ------------------------------------------------------------------ through the gateway

    /** The gate phase: on the town's side, to the portal and through (the leader first); through, wait for the rest. */
    static void gatePhase(MinecraftServer server, ServerLevel home, ServerLevel level, Villages.Village v, VillageFolkEntity f, Run r, Leg leg,
                          VillageFolkEntity lead) {
        long now = level.getGameTime();
        if (inNether(f)) {
            arrived(level, f, r, leg);
            // All through (or the ones that could not get to the gateway left at home): on.
            boolean all = true;
            for (UUID u : new ArrayList<>(r.members)) {
                if (r.through.contains(u)) continue;
                VillageFolkEntity m = find(server, r.village, u);
                if (m == null) {
                    r.members.remove(u);
                    continue;
                }
                Leg ml = r.legs.get(u);
                long since = ml == null || ml.gateSince < 0 ? r.phaseSince : ml.gateSince;
                if (now - since > GATE_WAIT && !inNether(m)) {
                    dropOut(m, r, "could not get to the gateway");
                    continue;
                }
                all = false;
            }
            if (!all) {
                f.hobbyNow = "through the gateway, waiting for the others";
                return;
            }
            // A player along: waited for on the far side till it comes through after them (NetherGuests).
            if (f == lead && NetherGuests.waitForPlayer(level, f, r)) return;
            if (f == lead) {
                r.phase(Run.Phase.ARRIVE, now);
                keep(r);
            }
            return;
        }
        toGate(home, v, f, r, leg, lead);
    }

    /** One that stays home after all (it could not get to the gateway): off the run, its kit kept for the next. */
    static void dropOut(VillageFolkEntity m, Run r, String why) {
        r.members.remove(m.getUUID());
        MEMBER.remove(m.getUUID());
        r.stayed.add(m.displayNameCap());
        Map<UUID, BlockPos> beds = AWAY_BEDS.get(r.village);
        if (beds != null) beds.remove(m.getUUID());
        m.getNavigation().stop();
        FolkTalk.speak(m, "I can't get to the gateway — go on without me!");
        LOG.info("[MCA-NETHER] {} stayed home: {}", m.displayNameCap(), why);
    }

    /** On the town's side: to the gateway and into its portal; through, when it is in it (the leader first). */
    static void toGate(ServerLevel home, Villages.Village v, VillageFolkEntity f, Run r, Leg leg, VillageFolkEntity lead) {
        long now = home.getGameTime();
        if (leg.gateSince < 0) leg.gateSince = now;
        BlockPos portal = r.gate != null && home.getBlockState(r.gate).is(Blocks.NETHER_PORTAL) ? r.gate : gatePortal(home, r.village);
        if (portal == null) {
            // The gateway gone dark (a ghast's fireball came through, or a player): lit again with the leader's flint and steel.
            if (f == lead && !NetherOutpost.relight(home, f, Villages.builtAt(r.village, "gateway"))) {
                turnBack(home.getServer(), r, f, "the gateway had gone dark, and we had nothing to light it with");
                r.phase(Run.Phase.STORE, now);
            }
            return;
        }
        r.gate = portal;
        // The others wait before the gateway till the leader has gone through.
        boolean mayGo = f == lead || r.through.contains(r.leader) || inNether(lead);
        BlockPos stand = beforeTheGate(home, v, portal);
        if (!mayGo) {
            if (stand != null && f.blockPosition().distSqr(stand) > 9) {
                if (f.getNavigation().isDone()) f.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 1.0D);
            } else {
                f.getNavigation().stop();
                f.getLookControl().setLookAt(portal.getX() + 0.5, portal.getY() + 1.0, portal.getZ() + 0.5);
            }
            f.hobbyNow = "at the gateway, waiting for " + lead.displayNameCap() + " to go through";
            return;
        }
        BlockPos in = portalNear(home, f.blockPosition(), 1);
        if (in != null) {
            cross(home, f, in, r);
            return;
        }
        // Into it: a step at a time, and the last stride straight in.
        double d = f.position().distanceToSqr(portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5);
        if (d < 2.6 * 2.6 || now - leg.gateSince > 600 && d < 5 * 5) {
            f.getMoveControl().setWantedPosition(portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5, 1.0D);
            if (d < 1.2 * 1.2) cross(home, f, portal, r);
        } else if (f.getNavigation().isDone() || now - leg.stillTick > 60) {
            f.getNavigation().moveTo(portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5, 1.05D);
            leg.stillTick = now;
        }
        f.hobbyNow = r.homeward ? "home through the gateway" : "off through the gateway into the Nether";
    }

    /** A portal block at or within so many blocks of this spot (the feet and the head), or null. */
    @Nullable
    static BlockPos portalNear(ServerLevel level, BlockPos at, int r) {
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-r, 0, -r), at.offset(r, 1, r))) {
            if (level.getBlockState(q).is(Blocks.NETHER_PORTAL)) return q.immutable();
        }
        return null;
    }

    /**
     * Through the portal it stands in, by the portal's own rule (the far side's portal found, or made, as for a player):
     * really into the other world, the same folk (Entity.changeDimension keeps who it is), its travelling window of
     * ground let go here first. Returns the folk on the far side, or null.
     */
    @Nullable
    static VillageFolkEntity cross(ServerLevel from, VillageFolkEntity f, BlockPos portal, Run r) {
        BlockState st = from.getBlockState(portal);
        if (!(st.getBlock() instanceof NetherPortalBlock block)) return null;
        DimensionTransition t = block.getPortalDestination(from, f, portal);
        if (t == null) {
            LOG.warn("[MCA-NETHER] no way through the portal at {} for {}", portal.toShortString(), f.displayNameCap());
            return null;
        }
        boolean leavingNether = from.dimension() == Level.NETHER;
        if (!leavingNether) f.letGoOfChunkWindow();
        else letGo(from, f.getUUID());
        f.getNavigation().stop();
        f.stopUsingItem();
        Leg leg = r.legs.get(f.getUUID());
        Entity e = f.changeDimension(t);
        if (!(e instanceof VillageFolkEntity nf)) return null;
        nf.setPortalCooldown();
        nf.clearFire();
        if (leg != null) {
            leg.crossed = from.getGameTime();
            leg.next = 0;
            leg.stillAt = null;
            leg.arrived = false;
        }
        if (leavingNether) {
            r.back.add(nf.getUUID());
            r.through.remove(nf.getUUID());
            nf.brain("back through the gateway, home from the Nether");
        } else {
            r.through.add(nf.getUUID());
            BlockPos at = portalNear(t.newLevel(), BlockPos.containing(t.pos()), 2);
            if (at != null && r.netherPortal == null) r.netherPortal = at;
            nf.brain("through the gateway into the Nether");
            if (nf.getUUID().equals(r.leader)) FolkTalk.speak(nf, FolkTalk.pick(nf.getRandom(), "Through! Mind the heat — and stay off the edges.",
                "The Nether. Keep together, keep your gold on, and don't strike a piglin."));
        }
        LOG.info("[MCA-NETHER] {} went through the portal at {} into {} at {}", nf.displayNameCap(), portal.toShortString(),
            t.newLevel().dimension().location(), BlockPos.containing(t.pos()).toShortString());
        return nf;
    }

    /** Just through: off the portal (two steps out, on the outpost's side if it has one), and waiting for the rest. */
    static void arrived(ServerLevel level, VillageFolkEntity f, Run r, Leg leg) {
        if (r.netherPortal == null) r.netherPortal = portalNear(level, f.blockPosition(), 2);
        if (leg.arrived) {
            if (f.getNavigation().isDone()) f.getLookControl().setLookAt(f.getX() + 3, f.getEyeY(), f.getZ());
            return;
        }
        leg.arrived = true;
        BlockPos out = NetherOutpost.stepOut(level, r, f.blockPosition());
        if (out != null) f.getNavigation().moveTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 1.0D);
    }

    /** All through: the outpost first on a first run (or a portal that is not the outpost's), else on to the work. */
    static void arrive(MinecraftServer server, ServerLevel level, VillageFolkEntity f, Run r, Leg leg, VillageFolkEntity lead) {
        if (!inNether(f)) return;
        arrived(level, f, r, leg);
        if (f != lead) return;
        long now = level.getGameTime();
        if (r.rescue && r.rescueAt != null) {
            r.missing = r.rescuing;
            r.missingAt = r.rescueAt;
            r.missingSince = now;
        }
        NetherOutpost.Room room = r.netherPortal == null ? null : NetherOutpost.room(r.village);
        boolean ours = room != null && r.netherPortal != null && room.has(r.netherPortal);
        if (r.netherPortal != null && (!ours || !room.built())) {
            if (NetherOutpost.begin(level, f, r)) {
                r.phase(Run.Phase.OUTPOST, now);
                keep(r);
                return;
            }
        } else if (ours) {
            r.outpostBuilt = true;
            NetherOutpost.mend(level, f, r);
        }
        r.phase(Run.Phase.WORK, now);
        keep(r);
    }

    /** The ground round a runner in the Nether kept awake as it goes (and let go behind it), owned by the runner's run. */
    static void keepAwake(ServerLevel level, VillageFolkEntity f) {
        BlockPos here = f.blockPosition();
        BlockPos was = WINDOWS.get(f.getUUID());
        if (was != null && (was.getX() >> 4) == (here.getX() >> 4) && (was.getZ() >> 4) == (here.getZ() >> 4)) return;
        UUID owner = owner(f.getUUID());
        if (was != null) ChunkLoad.setLoaded(level, owner, was, WINDOW, false);
        ChunkLoad.setLoaded(level, owner, here, WINDOW, true);
        WINDOWS.put(f.getUUID(), here.immutable());
    }

    /** The outpost's ground kept awake while any of the town's runners is through. */
    static void keepOutpostAwake(ServerLevel level, Run r) {
        BlockPos at = r.netherPortal;
        if (at == null || OUTPOST_WINDOWS.containsKey(r.village)) return;
        ChunkLoad.setLoaded(level, owner(r.village), at, OUTPOST_WINDOW, true);
        OUTPOST_WINDOWS.put(r.village, at.immutable());
    }

    /** A runner's window in the Nether let go (it went home, or died). */
    static void letGo(ServerLevel level, UUID folk) {
        BlockPos was = WINDOWS.remove(folk);
        ServerLevel nether = nether(level.getServer());
        if (was != null && nether != null) ChunkLoad.setLoaded(nether, owner(folk), was, WINDOW, false);
    }

    /** The outpost's window let go: nobody of the town is through any more. */
    static void letGoOutpost(MinecraftServer server, UUID village) {
        BlockPos was = OUTPOST_WINDOWS.remove(village);
        ServerLevel nether = nether(server);
        if (was != null && nether != null) ChunkLoad.setLoaded(nether, owner(village), was, OUTPOST_WINDOW, false);
    }

    /** The ticket owner for a runner's (or a town's outpost's) ground in the Nether. */
    static UUID owner(UUID u) {
        return UUID.nameUUIDFromBytes(("mca-nether-" + u).getBytes());
    }

    /** Tests: is this runner's ground in the Nether kept awake (its window, and where)? */
    @Nullable
    public static BlockPos windowForTests(UUID folk) {
        return WINDOWS.get(folk);
    }

    // ------------------------------------------------------------------ when to come home

    /** Why the leader takes the team home now, or null. Said aloud. */
    @Nullable
    static String whyHome(ServerLevel level, VillageFolkEntity f, Run r) {
        if (f.stationTask() != StationTask.NETHER && !r.rescue) return "I'd other work to go to";
        long now = level.getDayTime();
        if (now >= r.turnAt && !NetherOutpost.stayOn(level, f, r)) return r.days > 1.0 ? "the days we planned were up" : "it was time to head home";
        MinecraftServer server = level.getServer();
        List<VillageFolkEntity> team = members(server, r);
        int heads = 0, food = 0, arrows = 0, free = 0;
        for (VillageFolkEntity m : team) {
            heads++;
            food += m.countMatching(CaveDwellers::food);
            arrows += m.countMatching(s -> s.is(Items.ARROW));
            int slots = 0;
            for (ItemStack s : m.getInventoryItems()) if (s.isEmpty()) slots++;
            free += slots;
            if (slots < 2 && !NetherWork.roomInSatchel(m)) return (m == f ? "my" : m.displayNameCap() + "'s") + " pack was full of what we'd found";
            if (m.getHealth() < m.getMaxHealth() * 0.5F && m.countMatching(CaveDwellers::food) == 0) return m.displayNameCap() + " was hurt, with nothing left to eat";
        }
        if (heads > 0 && food < heads) return "the food was running out";
        if (heads > 0 && arrows == 0 && r.plan != null && r.plan.wants("blaze")) return "the arrows were gone";
        if (r.task == null && NetherWork.nothingLeft(level, f, r)) return "there was nothing more near the outpost the town wants";
        if (r.rescue && r.rescuing == null) return "we had found " + r.missingName;
        return null;
    }

    /** The team turns for home, together, back to the portal. */
    static void turnBack(MinecraftServer server, Run r, VillageFolkEntity f, String why) {
        if (r.homeward) return;
        r.homeward = true;
        r.why = why;
        r.task = null;
        long now = server.overworld().getGameTime();
        r.phase(Run.Phase.HOMEWARD, now);
        for (VillageFolkEntity m : members(server, r)) {
            m.getNavigation().stop();
            m.setShiftKeyDown(false);
            Leg leg = r.legs.get(m.getUUID());
            if (leg != null) {
                leg.digging = null;
                leg.claim = null;
            }
        }
        keep(r);
        FolkTalk.speak(f, why.endsWith("hurt") ? "Back, all of us — " + why.replace(" was badly hurt", "") + "'s hurt. To the portal, together."
            : why.startsWith("it was time") || why.startsWith("the days") ? (r.haul.isEmpty() ? "We go home: " + why + ". Stay close."
                : "We go home: " + why + ". A good run — to the portal, everybody!")
            : "We go home: " + why + ". To the portal, all of us.");
        LOG.info("[MCA-NETHER] the runners of {} turn for home: {}", Villages.name(r.village), why);
    }

    /** Homeward: in the Nether, back to the portal and through; at home, before the gateway till the rest are back. */
    static void homeward(MinecraftServer server, ServerLevel home, ServerLevel level, Villages.Village v, VillageFolkEntity f, Run r, Leg leg,
                         VillageFolkEntity lead) {
        long now = level.getGameTime();
        if (inNether(f)) {
            BlockPos portal = r.netherPortal != null && level.getBlockState(r.netherPortal).is(Blocks.NETHER_PORTAL) ? r.netherPortal
                : portalNear(level, r.netherPortal != null ? r.netherPortal : f.blockPosition(), 3);
            if (portal == null) {
                // The portal broken (a ghast): lit again with the flint and steel the leader carries.
                BlockPos frame = r.netherPortal != null ? r.netherPortal : f.blockPosition();
                if (!NetherOutpost.relight(level, f, frame)) {
                    if (f == lead && now - r.phaseSince > 2400) lose(level, f, r, "the portal on the far side had gone dark");
                    f.hobbyNow = "at the dark portal, with nothing to light it";
                    return;
                }
                r.event("the portal went dark, and " + f.displayNameCap() + " lit it again with flint and steel");
                portal = portalNear(level, frame, 3);
                if (portal == null) return;
            }
            r.netherPortal = portal;
            // Out of sight of the others? Back along the way it came first (NetherWork walks it there).
            if (NetherWork.makeFor(level, f, r, leg, portal, 1.15D)) {
                f.hobbyNow = "on the way back to the portal";
                if (now - r.phaseSince > 6000 && f.blockPosition().distSqr(portal) > 24 * 24) lose(level, f, r, "it could not find the way back to the portal");
                return;
            }
            BlockPos in = portalNear(level, f.blockPosition(), 1);
            if (in == null) {
                f.getMoveControl().setWantedPosition(portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5, 1.0D);
                return;
            }
            cross(level, f, in, r);
            return;
        }
        // Home: before the gateway till the rest are back (a while), then all to the storehouse.
        r.back.add(f.getUUID());
        boolean all = true;
        for (UUID u : r.members) {
            if (r.back.contains(u)) continue;
            VillageFolkEntity m = find(server, r.village, u);
            if (m != null && inNether(m)) all = false;
        }
        if (!all && now - leg.crossed < GATE_WAIT) {
            BlockPos stand = r.gate != null ? beforeTheGate(home, v, r.gate) : null;
            if (stand != null && f.blockPosition().distSqr(stand) > 9 && f.getNavigation().isDone()) f.walkTo(stand, 0.9D);
            f.hobbyNow = "home through the gateway, waiting for the others";
            return;
        }
        if (r.phase != Run.Phase.STORE) {
            r.phase(Run.Phase.STORE, now);
            greet(home, r, f);
        }
        toTheStores(home, v, f, r, leg);
    }

    /** Back in town: the townsfolk about see the runners come home through the gateway, and say so. */
    static void greet(ServerLevel level, Run r, VillageFolkEntity lead) {
        int n = 0;
        for (VillageFolkEntity m : level.getEntitiesOfClass(VillageFolkEntity.class, lead.getBoundingBox().inflate(24),
                x -> x.isAlive() && !x.isBaby() && !x.isShowcase() && r.village.equals(x.ownerId()) && !r.members.contains(x.getUUID()))) {
            if (n >= 3) break;
            String say = r.haul.isEmpty() ? FolkTalk.pick(m.getRandom(), "They're back! All in one piece?", "Welcome home!")
                : FolkTalk.pick(m.getRandom(), "The runners are back! Look at those packs!", "Back from the Nether — and singed, by the look of them!",
                    "Welcome home! What's it like down there?");
            m.sayLater(say, 10 + 25 * n);
            m.getLookControl().setLookAt(lead, 30.0F, 30.0F);
            n++;
        }
    }

    // ------------------------------------------------------------------ home with the haul

    /** Where the haul goes: the storehouse's door, or the stores' chest nearest the heart. */
    @Nullable
    static BlockPos storeSpot(ServerLevel level, UUID village) {
        return CaveDwellers.storeSpot(level, village);
    }

    /** Every runner to the storehouse with its haul; the last one in, and the run is told. */
    static void toTheStores(ServerLevel home, Villages.Village v, VillageFolkEntity f, Run r, Leg leg) {
        if (r.stored.contains(f.getUUID())) {
            finish(home, f, r);
            return;
        }
        BlockPos spot = storeSpot(home, r.village);
        long now = home.getGameTime();
        if (spot == null || f.blockPosition().distSqr(spot) <= 3.5 * 3.5 || now - leg.crossed > 1500 && leg.crossed > 0) {
            putIn(home, f, r);
            r.stored.add(f.getUUID());
            finish(home, f, r);
            return;
        }
        if (f.getNavigation().isDone() || now - leg.stillTick > 40) {
            f.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0D);
            leg.stillTick = now;
        }
        f.hobbyNow = "taking the Nether's haul to the storehouse";
    }

    /** One runner done with the run: off it, its window let go; the last of them, and the run is reported. */
    static void finish(ServerLevel home, VillageFolkEntity f, Run r) {
        MEMBER.remove(f.getUUID());
        letGo(home, f.getUUID());
        Map<UUID, BlockPos> beds = AWAY_BEDS.get(r.village);
        if (beds != null) beds.remove(f.getUUID());
        f.brain("home from the Nether, the haul in the storehouse");
        boolean all = true;
        for (UUID u : r.members) if (MEMBER.get(u) == r) all = false;
        if (all) report(home, r);
    }

    /** What a runner keeps of what it carries when it banks the haul: its kit, its keepsakes; the rest goes in. */
    static boolean keepsAtHome(ItemStack s) {
        return WatchKit.issued(s) || Homes.isKeepsake(s);
    }

    /** What it took through and did not use, that goes back into the stores without being counted as found. */
    static boolean taken(ItemStack s) {
        return s.is(Items.COBBLESTONE) || s.is(Items.ARROW) || s.is(Items.TORCH) || s.is(Items.SOUL_TORCH) || s.is(Items.SOUL_LANTERN)
            || s.is(Items.LANTERN) || s.is(Items.GOLD_INGOT) || s.is(Items.FLINT_AND_STEEL) || s.is(net.minecraft.tags.ItemTags.PLANKS)
            || s.is(Items.STICK) || s.is(Items.CRAFTING_TABLE) || s.is(Items.OAK_DOOR) || s.is(net.minecraft.tags.ItemTags.WOODEN_DOORS)
            || s.is(Items.IRON_INGOT) || s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(Items.GLASS_BOTTLE);
    }

    /**
     * Its whole haul into the storehouse (with none, the stores), booked as brought in by it, and as its work (Economy);
     * the satchel emptied into it first. What it took and did not use goes back, not counted.
     */
    static void putIn(ServerLevel level, VillageFolkEntity f, Run r) {
        Villages.Village v = Villages.get(r.village);
        if (v == null) return;
        List<ItemStack> satchels = NetherWork.unpackSatchels(f);
        StorehouseBlockEntity house = Storehouses.storeFor(level, r.village);
        String who = f.displayNameCap();
        Leg leg = r.legs.get(f.getUUID());
        Map<Item, Integer> got = leg == null ? new HashMap<>() : new HashMap<>(leg.got);
        List<ItemStack> lots = new ArrayList<>();
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || keepsAtHome(s)) continue;
            // Food kept to eat: what it is carrying past its rations goes in.
            int keep = CaveDwellers.food(s) && !got.containsKey(s.getItem()) ? Math.min(s.getCount(), 4) : 0;
            int n = s.getCount() - keep;
            if (n <= 0) continue;
            ItemStack lot = s.copyWithCount(n);
            s.shrink(n);
            if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
            bank(level, v, f, r, house, got, lots, lot);
        }
        // What the satchel carried: all of it the haul.
        for (ItemStack lot : satchels) bank(level, v, f, r, house, got, lots, lot);
        if (house != null) {
            house.setChanged();
            if (!lots.isEmpty()) Storekeeping.bookIn(level, r.village, who, lots, false);
        }
        f.swing(InteractionHand.MAIN_HAND);
        f.brain("put the Nether's haul into the " + (house != null ? "storehouse" : "stores"));
    }

    /** One lot of what it carried into the storehouse: what of it was found in the Nether booked as found (its work, the
     *  run's haul, the trophies), what it took and did not use back without being counted. */
    private static void bank(ServerLevel level, Villages.Village v, VillageFolkEntity f, Run r, @javax.annotation.Nullable StorehouseBlockEntity house,
                             Map<Item, Integer> got, List<ItemStack> lots, ItemStack lot) {
        String who = f.displayNameCap();
        int n = lot.getCount();
        int found = Math.min(n, got.getOrDefault(lot.getItem(), 0));
        if (found > 0) got.merge(lot.getItem(), -found, Integer::sum);
        if (found > 0) {
            ItemStack mine = lot.copyWithCount(found);
            Economy.produced(f, mine.copy());
            r.stored0.merge(mine.getHoverName().getString().toLowerCase(Locale.ROOT), found, Integer::sum);
            r.storedItems.merge(mine.getItem(), found, Integer::sum);
            NetherHome.broughtHome(r.village, mine, who, level.getDayTime() / 24000L);
        }
        ItemStack left = house != null ? house.insert(lot.copy()) : lot.copy();
        int in = lot.getCount() - (house != null ? left.getCount() : 0);
        if (house != null && in > 0 && found > 0) lots.add(lot.copyWithCount(Math.min(in, found)));
        if (!left.isEmpty() || house == null) Crafts.store(level, v, house == null ? lot.copy() : left);
    }

    /** "6 quartz, 3 blaze rods, a ghast tear": the most of a haul first. */
    static String words(Map<String, Integer> what, int most) {
        return CaveDwellers.words(what, most);
    }

    /**
     * The run, told: the finds into the report, the haul booked to the team, the run told in the chronicle as a small
     * story (and a great homecoming as the town's news), the morning's word, each runner's memory.
     */
    static void report(ServerLevel level, Run r) {
        if (r.reported) return;
        r.reported = true;
        r.phase = Run.Phase.DONE;
        UUID id = r.village;
        long day = level.getDayTime() / 24000L;
        letGoOutpost(level.getServer(), id);
        RUNS.remove(id, r);
        Ledger.note(id, "nether.run", "");
        Ledger.note(id, "nether.last", Long.toString(day));
        List<String> names = new ArrayList<>();
        String leadName = null;
        for (UUID u : r.members) {
            VillageFolkEntity m = find(level.getServer(), id, u);
            if (m == null) continue;
            names.add(m.displayNameCap());
            if (u.equals(r.leader)) leadName = m.displayNameCap();
        }
        List<String> big = new ArrayList<>();
        for (Find x : r.found) {
            boolean fresh = record(id, x);
            if (fresh && (x.kind() == Kind.FORTRESS || x.kind() == Kind.BASTION || x.kind() == Kind.SPAWNER || x.kind() == Kind.DEBRIS)) {
                big.add(x.label());
            }
        }
        for (String rare : new String[]{ "ghast tear", "wither skeleton skull", "ancient debris" }) {
            if (r.stored0.containsKey(rare)) big.add(0, JobMarket.a(rare));
        }
        String haulWords = r.stored0.isEmpty() ? "" : words(r.stored0, 5);
        bookHaul(id, day, names, r);
        String story = story(r, leadName, names, big, haulWords);
        Villages.tell(id, day, story);
        // The gazette's headline: "The runners are back from the Nether with 14 blaze rods and a ghast tear."
        if (!r.stored0.isEmpty()) {
            List<String> best = new ArrayList<>();
            for (String k : new String[]{ "blaze rod", "ghast tear", "ender pearl", "nether wart", "quartz", "glowstone dust", "obsidian",
                "wither skeleton skull", "ancient debris" }) {
                Integer n = r.stored0.get(k);
                if (n != null && n > 0 && best.size() < 2) best.add(n == 1 ? JobMarket.a(k) : n + " " + (k.endsWith("s") || k.equals("quartz") || k.endsWith("dust")
                    || k.equals("obsidian") || k.equals("nether wart") || k.equals("ancient debris") ? k : k + "s"));
            }
            if (best.isEmpty()) best.add(haulWords);
            Villages.tell(id, day, "the runners are back from the Nether with " + String.join(" and ", best));
        }
        NetherGuests.home(level, r, day);
        String last = "day " + (day + 1) + ": " + (r.days > 1.0 ? CaveTrips.daysWords(r.days).toLowerCase(Locale.ROOT) + " " : "") + "in the Nether with "
            + (names.size() <= 1 ? "nobody else" : JobMarket.join(names)) + " — " + (haulWords.isEmpty() ? "nothing to bring home" : haulWords)
            + (r.events.isEmpty() ? "" : "; " + r.events.get(0)) + " (" + r.why + ")";
        for (UUID u : r.members) {
            Ledger.note(id, "nether.lastrun/" + u, last);
            VillageFolkEntity m = find(level.getServer(), id, u);
            if (m != null) {
                m.persona().remember(day, big.isEmpty() ? "We went through the gateway into the Nether, and came home" : "We went into the Nether and found " + big.get(0), 4);
                m.awardXp(6);
            }
        }
        if (!r.stored0.isEmpty() || !big.isEmpty()) {
            REPORTS.computeIfAbsent(id, k -> new ArrayList<>()).add("Our Nether runners brought home " + (haulWords.isEmpty() ? "nothing" : haulWords)
                + (big.isEmpty() ? "" : " and found " + big.get(0)) + ".");
        }
        Entity lead = r.leader == null ? null : find(level.getServer(), id, r.leader);
        if (lead instanceof VillageFolkEntity l) {
            FolkTalk.speak(l, r.stored0.isEmpty() ? "Home, all of us. Nothing worth the carrying, but we know the way better."
                : big.isEmpty() ? "Home, and " + haulWords + " in the storehouse. The brewer'll be glad." : "Home! We found " + big.get(0) + "!");
        }
        Map<UUID, BlockPos> beds = AWAY_BEDS.get(id);
        if (beds != null) for (UUID u : r.members) beds.remove(u);
        for (UUID u : r.members) MEMBER.remove(u);
        NetherRunners.tally(id, r);
        LOG.info("[MCA-NETHER] the runners of {} home ({}): {}; mined {}, slain {} ({} blazes, {} ghasts), {} fireballs turned, {} barters, {} placed, {} cut, "
            + "{} retreats; stored {}; events {}", Villages.name(id), r.why, names, r.mined, r.slain, r.blazes, r.ghasts, r.deflected, r.barters, r.placed,
            r.cut, r.retreats, r.stored0, r.events);
    }

    /** The run as a small story for the chronicle. */
    static String story(Run r, @Nullable String leadName, List<String> names, List<String> big, String haulWords) {
        String lead = leadName != null ? leadName : names.isEmpty() ? "the Nether runners" : names.get(0);
        List<String> others = new ArrayList<>(names);
        others.remove(lead);
        if (!r.guestName.isEmpty()) others.add(r.guestName);
        StringBuilder sb = new StringBuilder(lead);
        sb.append(others.isEmpty() ? " went alone" : " led " + JobMarket.join(others));
        sb.append(r.days > 1.0 ? " " + CaveTrips.daysWords(r.days).toLowerCase(Locale.ROOT) + " through the gateway into the Nether" : " through the gateway into the Nether");
        if (!r.asked.isEmpty()) sb.append(" (").append(r.asked).append(")");
        List<String> did = new ArrayList<>();
        if (r.outpostBuilt && r.placed > 0) did.add("walled in the outpost round the portal");
        if (r.cut > 0) did.add("cut and bridged " + r.cut + " blocks of way");
        if (r.mined > 0) did.add("dug " + r.mined + " blocks of quartz, glowstone and the rest");
        if (r.blazes > 0) did.add("shot down " + r.blazes + (r.blazes == 1 ? " blaze" : " blazes"));
        if (r.ghasts > 0) did.add("brought down " + r.ghasts + (r.ghasts == 1 ? " ghast" : " ghasts"));
        if (r.deflected > 0) did.add("turned " + (r.deflected == 1 ? "a ghast's fireball" : r.deflected + " fireballs") + " back on their ghasts");
        if (r.barters > 0) did.add("bartered " + r.barters + " gold with the piglins");
        if (r.nights > 0) did.add("slept " + (r.nights == 1 ? "a night" : r.nights + " nights") + " in the outpost");
        if (!big.isEmpty()) did.add("found " + String.join(" and ", big.subList(0, Math.min(2, big.size()))));
        if (!did.isEmpty()) {
            sb.append(": they ");
            if (did.size() == 1) sb.append(did.get(0));
            else sb.append(String.join(", ", did.subList(0, did.size() - 1))).append(", and ").append(did.get(did.size() - 1));
        }
        if (!r.fallen.isEmpty()) sb.append("; ").append(JobMarket.join(r.fallen)).append(r.fallen.size() == 1 ? " never came back" : " never came back");
        if (!r.lost.isEmpty()) sb.append("; ").append(JobMarket.join(r.lost)).append(" was lost there, and is waited for");
        sb.append("; home").append(r.why.isEmpty() ? "" : " (" + r.why + ")").append(" with ")
            .append(haulWords.isEmpty() ? "nothing worth the carrying" : haulWords + ", into the storehouse");
        return sb.toString();
    }

    /** The run's haul booked to the team, kept with the town (the last ten runs). */
    private static void bookHaul(UUID village, long day, List<String> who, Run r) {
        String s = Ledger.note(village, "nether.hauls");
        List<String> lines = new ArrayList<>();
        if (s != null && !s.isEmpty()) lines.addAll(List.of(s.split("\n")));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> en : r.stored0.entrySet()) {
            sb.append(sb.length() == 0 ? "" : ",").append(en.getKey().replace(',', ' ').replace('*', ' ')).append('*').append(en.getValue());
        }
        lines.add(day + "|" + String.join(" and ", who).replace('|', '/') + "|" + sb + "|" + r.days + "|" + r.blazes + "|" + r.barters + "|"
            + String.join("; ", r.events).replace('|', '/').replace('\n', ' ') + "|" + r.why.replace('|', '/'));
        while (lines.size() > 10) lines.remove(0);
        Ledger.note(village, "nether.hauls", String.join("\n", lines));
    }

    /** The runs' hauls the town keeps, newest first, in a line each. */
    public static List<String> hauls(UUID village) {
        List<String> out = new ArrayList<>();
        String s = Ledger.note(village, "nether.hauls");
        if (s == null || s.isEmpty()) return out;
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String[] p = lines[i].split("\\|", -1);
            if (p.length < 4) continue;
            Map<String, Integer> m = new LinkedHashMap<>();
            for (String item : p[2].split(",")) {
                int star = item.lastIndexOf('*');
                if (star <= 0) continue;
                try {
                    m.merge(item.substring(0, star), Integer.parseInt(item.substring(star + 1)), Integer::sum);
                } catch (NumberFormatException ignored) {
                    // an unreadable count: left out
                }
            }
            long day;
            double days = 1.0;
            try {
                day = Long.parseLong(p[0]);
                days = Double.parseDouble(p[3]);
            } catch (NumberFormatException ex) {
                continue;
            }
            String extra = p.length >= 7 && !p[6].isEmpty() ? "; " + p[6] : "";
            out.add("Day " + (day + 1) + ", " + p[1] + (days == 1.0 ? "" : ", " + CaveTrips.daysWords(days).toLowerCase(Locale.ROOT)) + ": "
                + (m.isEmpty() ? "nothing worth the carrying" : words(m, 6)) + extra);
        }
        return out;
    }

    /** For the morning assembly: what the runners brought home (said once). */
    public static List<String> reports(UUID village) {
        List<String> list = REPORTS.remove(village);
        return list == null ? List.of() : list;
    }

    // ------------------------------------------------------------------ lost, and found

    /**
     * A runner lost to the Nether (VillageFolkEntity.die): the town is told where and how, the team notes it and goes
     * home (what it dropped picked up by the others if they can get to it: NetherWork.collect), its ground let go.
     */
    public static void fell(VillageFolkEntity f, DamageSource cause) {
        if (!(f.level() instanceof ServerLevel level)) return;
        Run r = MEMBER.remove(f.getUUID());
        LOST.remove(f.getUUID());
        letGo(level, f.getUUID());
        if (r == null || !r.members.contains(f.getUUID())) return;
        r.members.remove(f.getUUID());
        String how = cause.getMsgId();
        String words = how.contains("lava") ? "in the lava" : how.contains("fire") || how.contains("inFire") || how.contains("onFire") ? "in the fire"
            : how.contains("fall") ? "in a fall" : how.contains("fireball") ? "by a ghast's fireball" : "fighting";
        r.fallen.add(f.displayNameCap());
        r.event(f.displayNameCap() + " died " + words);
        long day = level.getDayTime() / 24000L;
        if (inNether(f)) {
            r.found.add(new Find(Kind.LOST, f.displayNameCap() + " died here, " + words, f.blockPosition(), day, f.displayNameCap(), 0, 0));
            Villages.tell(r.village, day, f.displayNameCap() + " of the Nether runners died in the Nether, " + words);
        }
        Map<UUID, BlockPos> beds = AWAY_BEDS.get(r.village);
        if (beds != null) beds.remove(f.getUUID());
        MinecraftServer server = level.getServer();
        for (VillageFolkEntity m : members(server, r)) {
            m.persona().remember(day, "We lost " + f.displayNameCap() + " in the Nether", 9);
            if (m.distanceToSqr(f) < 32 * 32) m.sayLater(FolkTalk.pick(m.getRandom(), f.displayNameCap() + "! No!", "We've lost " + f.displayNameCap() + "..."), 6);
        }
        if (!r.homeward && !r.members.isEmpty()) {
            VillageFolkEntity lead = lead(server, r, f);
            NetherWork.pickUpAfter(level, r, f.blockPosition());
            turnBack(server, r, lead, "we lost " + f.displayNameCap());
        }
        if (r.members.isEmpty()) {
            Villages.tell(r.village, day, "none of the Nether runners came home");
            report(level, r);
        }
    }

    /**
     * The leader misses one of the team (out of sight of it a minute, in the Nether): back to where it was last seen,
     * calling; found, on; not found in two minutes, the team goes home without it, and it is lost (lose: the town sends a
     * rescue party through in the morning). True while it searches.
     */
    static boolean searching(ServerLevel level, VillageFolkEntity lead, Run r, Leg leadLeg) {
        long now = level.getGameTime();
        MinecraftServer server = level.getServer();
        // The lost one a rescue came for (not one of the team till it is found), seen by the leader: found.
        if (r.missing != null && !r.members.contains(r.missing)) {
            VillageFolkEntity m = find(server, r.village, r.missing);
            if (m != null && inNether(m) && m.distanceToSqr(lead) <= 32 * 32 && (m.distanceToSqr(lead) <= 6 * 6 || lead.hasLineOfSight(m))) {
                found(level, lead, r, m);
            }
        }
        // Each of the team seen by the leader (in the Nether, within sight and reach) is seen now.
        for (UUID u : r.members) {
            Leg l = r.legs.computeIfAbsent(u, k -> new Leg());
            VillageFolkEntity m = find(server, r.village, u);
            if (m == null) continue;
            if (m == lead || !inNether(m) || m.distanceToSqr(lead) <= 32 * 32 && (m.distanceToSqr(lead) <= 6 * 6 || lead.hasLineOfSight(m))) {
                l.seen = now;
                l.lastSeen = m.blockPosition().immutable();
                if (u.equals(r.missing)) found(level, lead, r, m);
            }
        }
        if (r.missing == null) {
            for (UUID u : r.members) {
                Leg l = r.legs.get(u);
                if (l == null || u.equals(lead.getUUID()) || now - l.seen < MISSED) continue;
                VillageFolkEntity m = find(server, r.village, u);
                if (m == null || !inNether(m)) continue;
                r.missing = u;
                r.missingAt = l.lastSeen != null ? l.lastSeen : m.blockPosition().immutable();
                r.missingSince = now;
                r.missingName = m.displayNameCap();
                r.event(m.displayNameCap() + " went missing, and the others went back for " + m.displayNameCap());
                FolkTalk.speak(lead, "Where's " + m.displayNameCap() + "? Back, all of you — we find " + m.displayNameCap() + " before anything else.");
                LOG.info("[MCA-NETHER] {} of {} is missing, last seen at {}", m.displayNameCap(), Villages.name(r.village), r.missingAt.toShortString());
                break;
            }
        }
        if (r.missing == null || r.missingAt == null) return false;
        if (now - r.missingSince > SEARCH_FOR) {
            VillageFolkEntity m = find(server, r.village, r.missing);
            if (m != null) lose(level, m, r, "the others searched for it, and could not find it");
            else r.missing = null;
            if (!r.homeward) turnBack(server, r, lead, "we could not find " + r.missingName);
            return false;
        }
        // To where it was last seen, calling (cutting a way there if there is no walking it: NetherWork.makeFor).
        if (now - leadLeg.called > 140) {
            leadLeg.called = now;
            FolkTalk.speak(lead, FolkTalk.pick(lead.getRandom(), r.missingName + "! " + r.missingName + "! Can you hear me?", "Over here, " + r.missingName + "!",
                r.missingName + "! Shout if you can hear us!"));
            VillageFolkEntity m = find(server, r.village, r.missing);
            if (m != null && inNether(m) && m.distanceToSqr(lead) <= 40 * 40) {
                m.sayLater(FolkTalk.pick(m.getRandom(), "Here! I'm over here!", "I hear you! This way!"), 20);
                r.missingAt = m.blockPosition().immutable();           // it calls back: they go to the voice
            }
        }
        NetherWork.makeFor(level, lead, r, leadLeg, r.missingAt, 1.1D);
        lead.hobbyNow = "searching the Nether for " + r.missingName;
        return true;
    }

    /** The missing one found: back with the team. */
    static void found(ServerLevel level, VillageFolkEntity lead, Run r, VillageFolkEntity m) {
        r.event(lead.displayNameCap() + " found " + m.displayNameCap() + " again");
        r.missing = null;
        r.missingAt = null;
        LOST.remove(m.getUUID());
        if (r.rescue && m.getUUID().equals(r.rescuing)) {
            r.rescuing = null;
            if (!r.members.contains(m.getUUID())) {
                r.members.add(m.getUUID());
                MEMBER.put(m.getUUID(), r);
                Leg leg = new Leg();
                leg.seen = level.getGameTime();
                r.legs.put(m.getUUID(), leg);
            }
            Villages.tell(r.village, level.getDayTime() / 24000L, lead.displayNameCap() + " found " + m.displayNameCap() + " in the Nether, and brought it home");
        }
        FolkTalk.speak(lead, "There you are! Stay close now.");
        m.sayLater(FolkTalk.pick(m.getRandom(), "I thought I'd never see you again!", "I lost the way — thank you for coming back."), 20);
        m.persona().remember(level.getDayTime() / 24000L, lead.displayNameCap() + " came back for me in the Nether", 6);
        LOG.info("[MCA-NETHER] {} found {} at {}", lead.displayNameCap(), m.displayNameCap(), m.blockPosition().toShortString());
    }

    /** A runner lost in the Nether: off the team, waiting there to be found (a rescue run goes through for it), told. */
    static void lose(ServerLevel level, VillageFolkEntity m, Run r, String why) {
        r.members.remove(m.getUUID());
        MEMBER.remove(m.getUUID());
        r.lost.add(m.displayNameCap());
        if (r.missing != null && r.missing.equals(m.getUUID())) r.missing = null;
        LOST.put(m.getUUID(), new Lost(r.village, m.displayNameCap(), m.blockPosition().immutable(), level.getGameTime()));
        long day = level.getDayTime() / 24000L;
        r.found.add(new Find(Kind.LOST, m.displayNameCap() + " was lost here", m.blockPosition(), day, m.displayNameCap(), 0, 0));
        Villages.tell(r.village, day, m.displayNameCap() + " was lost in the Nether (" + why + "); the town will send a party through for it");
        FolkTalk.speak(m, "I've lost the others... I'll wait where I can. They'll come.");
        m.getNavigation().stop();
        LOG.info("[MCA-NETHER] {} lost in the Nether at {}: {}", m.displayNameCap(), m.blockPosition().toShortString(), why);
    }

    /** Tests: this runner out of sight of the team since so long ago (the leader misses it at its next look). */
    public static void outOfSightForTests(VillageFolkEntity m, long ticksAgo) {
        Run r = runOf(m);
        if (r == null) return;
        Leg l = r.legs.computeIfAbsent(m.getUUID(), k -> new Leg());
        l.seen = m.level().getGameTime() - ticksAgo;
        l.lastSeen = m.blockPosition().immutable();
    }

    /**
     * A runner of the town in the Nether with no run (a restart; the one lost, waiting; a stray through a portal): it makes
     * its way back to the nearest portal and through it. One lost waits where it is for its rescue (a day), then tries.
     */
    static boolean stray(VillageFolkEntity f, ServerLevel level) {
        long now = level.getGameTime();
        if ((now + f.getId()) % STEP != 0) return true;
        if (inNether(f)) keepAwake(level, f);
        Lost l = LOST.get(f.getUUID());
        if (l != null && now - l.since < 24000L) {
            f.getNavigation().stop();
            f.hobbyNow = "lost in the Nether, and waiting to be found";
            if (f.getHealth() < f.getMaxHealth() * 0.6F) f.eatFromPack();
            return true;
        }
        // A run kept with the town takes it up: home with what it has.
        Run r = resume(level, f);
        if (r != null) return true;
        BlockPos portal = portalNear(level, f.blockPosition(), 8);
        UUID village = f.ownerId();
        if (portal == null && village != null) {
            NetherOutpost.Room room = NetherOutpost.room(village);
            if (room != null) portal = room.portal();
        }
        if (portal == null) {
            f.hobbyNow = "in the Nether, looking for the way home";
            return true;
        }
        if (f.blockPosition().distSqr(portal) <= 2) {
            Run tmp = new Run(village, level.getDayTime() / 24000L);
            tmp.members.add(f.getUUID());
            VillageFolkEntity back = cross(level, f, portal, tmp);
            if (back != null) {
                LOST.remove(back.getUUID());
                letGo(level, back.getUUID());
            }
            return true;
        }
        if (f.getNavigation().isDone()) f.getNavigation().moveTo(portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5, 1.0D);
        f.hobbyNow = "making for the portal, and home";
        return true;
    }

    /** A run kept with the town, taken up again after a restart by one of its runners: home, with what it has. */
    @Nullable
    static Run resume(ServerLevel level, VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return null;
        Run r = run(village);
        if (r == null) {
            String[] k = kept(village);
            if (k == null || !k[4].contains(f.getUUID().toString())) return null;
            r = new Run(village, level.getDayTime() / 24000L);
            try {
                r.startTime = Long.parseLong(k[0]);
                r.turnAt = Long.parseLong(k[1]);
                r.days = Double.parseDouble(k[2]);
                r.leader = UUID.fromString(k[3]);
            } catch (IllegalArgumentException e) {
                return null;
            }
            r.planWords = k[5];
            r.homeward = true;
            r.why = "the run was taken up again, and home";
            r.phase = Run.Phase.HOMEWARD;
            r.phaseSince = level.getGameTime();
            NetherOutpost.Room room = NetherOutpost.room(village);
            if (room != null) r.netherPortal = room.portal();
            RUNS.put(village, r);
        }
        if (!r.members.contains(f.getUUID())) r.members.add(f.getUUID());
        MEMBER.put(f.getUUID(), r);
        r.legs.computeIfAbsent(f.getUUID(), x -> new Leg()).seen = level.getGameTime();
        LOG.info("[MCA-NETHER] {} took the run up again in the Nether: home", f.displayNameCap());
        return r;
    }

    /** End a run outright (the town gone; tests). */
    static void end(ServerLevel level, Run r, String why) {
        r.why = why;
        r.reported = true;
        RUNS.remove(r.village, r);
        for (UUID u : r.members) {
            MEMBER.remove(u);
            letGo(level, u);
        }
        letGoOutpost(level.getServer(), r.village);
        Ledger.note(r.village, "nether.run", "");
    }

    /** Tests: the run's haul as it stands, by item, carried by the team. */
    public static Map<Item, Integer> carriedForTests(Run r, VillageFolkEntity f) {
        Leg l = r.legs.get(f.getUUID());
        return l == null ? Map.of() : Map.copyOf(l.got);
    }

    /** Tests: the run turns for home now. */
    public static void homeForTests(ServerLevel level, Run r, String why) {
        VillageFolkEntity lead = find(level.getServer(), r.village, r.leader);
        if (lead != null) turnBack(level.getServer(), r, lead, why);
    }

    /** Tests: the run's phase set. */
    public static void phaseForTests(Run r, Run.Phase p, long now) {
        r.phase(p, now);
    }

    /** Tests: this runner lost in the Nether now (the search given up). */
    public static void loseForTests(ServerLevel level, VillageFolkEntity m, Run r) {
        lose(level, m, r, "the others searched for it, and could not find it");
    }

    /** Tests: is this runner falling back to the outpost, hurt? */
    public static boolean retreatingForTests(Run r, UUID folk) {
        Leg l = r.legs.get(folk);
        return l != null && l.retreating;
    }

    /** Tests: the run under way by town, whoever asks (the stage too). */
    @Nullable
    public static Run runForTests(UUID village) {
        return run(village);
    }

    /** Tests: who went through today forgotten. */
    public static void forgetWentForTests() {
        WENT.clear();
    }
}
