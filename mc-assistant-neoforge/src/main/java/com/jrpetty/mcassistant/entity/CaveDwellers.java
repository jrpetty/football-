package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [caves] The cave dwellers: an Iron Age trade, half miner and half guard, that goes down the caves round the town as a
 * small, highly skilled team. A town of twenty-five or more in the Iron Age, with miners at its mine and a watch on its
 * walls, keeps a team of two; three at sixty folk; four at a hundred, and never more.
 *
 * <p><b>The team.</b> The town picks its most skilled (tick, appoint): the best miners and guards by their level and
 * their years at it, never an idle hand of no skill for being idle, and never a hand of a trade the town is short of.
 * What they know of the rock and the blade stands them in good stead: a new cave dweller starts near its mining or
 * its guarding level, and learns the caves fast. The most experienced leads. They are as hardy as the watch (twice a
 * folk's health: AssistantEntity's level perks), and among the best paid in the town (JobWorth: dangerous, skilled
 * work underground; a small team). A team away on a trip is still the town's team: nobody is taken up in its place,
 * and its beds at home stay its own (awayBeds).
 *
 * <p><b>The plan</b> (CaveTrips.plan): before it sets out the leader reckons the trip from the work waiting on the
 * cave's list, the walk there and back, and what the town can spare: half a day to a near cave with a vein or two left,
 * a day's scouting to a cave nobody has mapped, two to four days to a big cave deep and far with a long list; cut down
 * by the food, the torches and the kit the town can spare, a day longer when the age waits on the ore. Told on the
 * board, in the chronicle and on the Caves page with its reckoning; kept with the town while the team is away.
 *
 * <p><b>The kit.</b> Every one of them goes to the stores first (kitUp): the armour, blade and shield the town gives
 * its watch (WatchKit.fit), the best pickaxe the stores hold, sixty-four torches a day out (the town's own make: the
 * smelter, the smith and the shop keep the stores topped up ahead of the team, and the team draws only what the town
 * can spare over its own lights, never the last of them: spareTorches), food for the days out and one over, cobblestone
 * for the nights' camps, and the makings of its crafting: a crafting table, a few planks and sticks, and three iron
 * ingots when the town can spare them. Short of torches, it goes with what can be spared and keeps the trip shorter;
 * with too few to go at all, it waits on the makers. The town's pieces carry the watch's mark and go back into the
 * stores when it leaves the trade (handBack).
 *
 * <p><b>Out together.</b> The whole team goes out as one party (a Party, each member on a Scouts.Expedition with a
 * Delve for its part): to a cave the town knows and has not worked out, or out on the way least looked along (a
 * hundred blocks in the Iron Age, a hundred and fifty in the Diamond, two hundred in the Nether) to the first way under
 * the rock it can walk. They walk in order, the leader first, each a couple of blocks behind the one ahead; the leader
 * waits for anybody who falls behind, and goes back for them. In a fight one takes on a lone monster while the others
 * work on, and they all join in against a group; a creeper sends them back. Never stuck: one that has got nowhere for a
 * while cuts itself a step.
 *
 * <p><b>Every vein.</b> In the cave the leader looks through the walls, the floor and the roof round it (scan): every
 * ore showing, and every vein up to three blocks of rock behind the cave's face, goes on the cave's list (kept with the
 * town: ore, place, size, how far behind, and how it stands), called out by the nearest of them ("Iron here!"). The
 * leader takes the veins one at a time, the town's most wanted ore first (what the age asks for: Villages.needs), then
 * the nearest; the way in to a hidden vein is cut as a short tunnel of rock the world made, and every member digs a
 * block of it, never the same block (claims), each with its own pick, never the ground under its feet, never into lava
 * or water, never in the town. A vein none of the team's picks will take (obsidian without diamond) waits on the list
 * for a better pick; a cave whose veins are all taken is worked out, and the team moves on to the next, and comes back
 * to the waiting ones when the town has the pick for them.
 *
 * <p><b>Light, and the dangers.</b> The torches cost the town, so they go where they count (light): one every fifteen
 * blocks or so along the way in, up to twenty in a straight passage and closer at a turn or where ways meet, and
 * wherever they mine or fight that is dark enough for monsters to spawn. Lava and running water in the way are walled
 * off with cobblestone, a hole in the floor patched, a member on fire doused with its water bucket, a spawner lit up all
 * round so nothing more comes out of it (and left standing, and noted).
 *
 * <p><b>Made down there</b> (CaveCraft): low on torches, it makes more of the coal it mines and its sticks; a pick or a
 * blade about to break, it sets its crafting table down and makes another, of iron, stone or wood (of diamonds only
 * when the cave's list waits on a better pick for something the town wants), and a spare pick before a long dig.
 *
 * <p><b>When to come back</b> (whyHome): when the plan's time is up (unless the work goes well and the supplies hold:
 * another half day, CaveTrips.stayOn); early, and saying why, when the torches are nearly gone and there is no coal to
 * make more, the food will not see them home, a tool is gone with nothing to make one, a pack is full, one of them is
 * badly hurt or lost, the cave is worked out, or there is more down there than they can take on (a warden, a crowd).
 * On a trip of more than a day they camp at dusk in a nook walled in with cobblestone, a torch inside, one on watch by
 * turns (CaveTrips.camp). A day overdue, the town sends a search party; after a restart, the trip is taken up again.
 *
 * <p><b>Old chests.</b> The leader opens the chests the world left: a mineshaft's carts, a dungeon's chests, the chests
 * of the temples, ruins and strongholds (never a named chest, a village's, or one near a town; never the deep dark's or
 * the trial chambers'; a desert temple's only once its trap is gone), and takes what is worth carrying.
 *
 * <p><b>Home together.</b> They come home along the leader's marks (climbing out by the miners' stairs when there is
 * no walking out, and calling for the search party when even that fails: never lifted out), the town comes out to
 * greet them, and every one of them walks to the town's storehouse and puts its whole haul in, booked in the
 * storehouse's books as brought in by it and as the team's work (Economy). With no storehouse, the stores at the heart
 * take it. What it took down and did not use (torches over a stack, planks) goes back too, not counted as found.
 *
 * <p><b>The report.</b> Kept with the town: the caves (where, how deep, how big) and each one's veins, the ravines,
 * the mineshafts, the dungeons and spawners, the structures and what was found in their chests, the lava, and each
 * trip's haul with the torches drawn and set. The trip told in the chronicle as a small story. Shown on the Caves page
 * of the town's books, by /village caves, on the board, on each cave dweller's card, in the gazette for the big finds,
 * at the morning assembly, and in talk.
 *
 * <p><b>Hooks</b> for what is built round the team: dwellers, leaderOf, partyOf and the Party's accessors (members,
 * leader, phase, plan, days, dayOf, camp, crafted, order, haul), report (the finds), caveNear, veins (a cave's list),
 * hauls, torchTotals, CaveTrips.trip (the trip under way) and CaveTrips.plan.
 */
public final class CaveDwellers {

    static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private CaveDwellers() {}

    /** A town takes up the trade from this many folk, in the Iron Age, with miners and a watch. */
    public static final int FROM = 25;
    /** And never keeps more than this many, however big the town. */
    public static final int MOST = 4;
    /** The miners and the guards a town has before it sends anybody into the caves. */
    static final int MINERS = 2, GUARDS = 1;
    /** Torches each carries out of the stores (at least, when the town can spare them); food for a day; cobblestone. */
    public static final int TORCHES = 64;
    static final int FOOD = 6, COBBLE = 8;
    /** The team goes home when it is down to this many torches a head (and no coal to make more); it does not go down
     *  with fewer than TORCHES_MIN a head. */
    static final int TORCHES_LOW = 8, TORCHES_MIN = 16;
    /** Torches along the way: about this far apart; up to STRAIGHT in a straight passage; from BEND at a turn or where ways meet. */
    static final int SPACING = 15, STRAIGHT = 20, BEND = 9;
    /** How many things the report keeps; how many veins a cave's list. */
    static final int KEEP = 160, VEINS_KEPT = 64;
    /** The most of one vein it goes after. */
    static final int VEIN_MOST = 32;
    /** How far behind a wall or a roof a vein is gone after (blocks of rock between it and the cave); a floor's, one. */
    static final int BEHIND = 3, BEHIND_FLOOR = 1;
    /** How far round the leader the walls are looked through for veins. */
    static final int SCAN = 10;
    /** The others keep within this of the leader; the leader waits for anybody further than WAIT. */
    static final int CLOSE = 5, WAIT = 8;
    /** Turn for home at this hour of the day (dusk is at twelve thousand), or after so long out. */
    static final long TURN = 7600, LONGEST = 9000;
    /** Never down past here: every open cave below Y-54 is full of lava (VillageFolkEntity's deepest mine). */
    static final int ABOVE_BOTTOM = 14;
    /** How far into a cave system it goes from where it went in. */
    static final int DEEPEST = 80;

    // ------------------------------------------------------------------ what it finds

    /** What a cave dweller can find. */
    public enum Kind {
        CAVE("a cave"), RAVINE("a ravine"), VEIN("ore"), MINESHAFT("a mineshaft"), DUNGEON("a dungeon"), SPAWNER("a spawner"),
        STRUCTURE("ruins"), CHEST("a chest"), LAVA("lava");

        public final String words;

        Kind(String words) { this.words = words; }
    }

    /**
     * One thing in the report: what, where, when, by whom, and two numbers that depend on what it is (a cave: how
     * deep under the surface and how big, in open blocks; a vein: how many blocks seen and how many mined; a
     * mineshaft or a structure: how many of its chests were looked in; a chest: how many things were taken out of it;
     * lava: how deep).
     */
    public record Find(Kind kind, String label, BlockPos at, long day, String by, int a, int b) {
        String encode() {
            return kind.name() + "|" + clean(label) + "|" + at.getX() + "|" + at.getY() + "|" + at.getZ() + "|" + day + "|" + clean(by)
                + "|" + a + "|" + b;
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

        private static String clean(String s) {
            return s.replace('|', '/').replace('\n', ' ');
        }
    }

    /**
     * A vein on a cave's list: its ore, its most reachable block, how many blocks, how many blocks of rock lay between
     * it and the cave, and how it stands: "todo", "mined", "waiting" (for a better pick) or "left" (lava or water behind
     * it, or no way to it).
     */
    public record Vein(String ore, BlockPos at, int size, int behind, String state) {
        String encode() {
            return ore + "|" + at.getX() + "|" + at.getY() + "|" + at.getZ() + "|" + size + "|" + behind + "|" + state;
        }

        @Nullable
        static Vein decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 7) return null;
            try {
                return new Vein(p[0], new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])),
                    Integer.parseInt(p[4]), Integer.parseInt(p[5]), p[6]);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Vein as(String s) {
            return new Vein(ore, at, size, behind, s);
        }

        /** "iron, 5 blocks, 2 behind the wall: mined". */
        public String words() {
            String how = switch (state) {
                case "mined" -> "mined";
                case "waiting" -> "waiting for a better pick";
                case "left" -> "left (no safe way to it)";
                default -> "still to do";
            };
            return ore + ", " + size + (size == 1 ? " block" : " blocks") + (behind > 0 ? ", " + behind + " behind the face" : "") + ": " + how;
        }
    }

    // ------------------------------------------------------------------ the team's day out

    /** The team's day out together: one leader, one cave at a time, what they found and brought out between them. */
    public static final class Party {
        enum Phase { OUT, IN, LEAVING, STORE, CAMP }

        final UUID village;
        final long day;
        final List<UUID> members = new ArrayList<>();
        UUID leader;
        Phase phase = Phase.OUT;
        boolean homeward;
        String why = "";
        /** The cave it is making for or in, where it went in, and the report's spot for it (its veins' list). */
        @Nullable BlockPos cave, mouth, caveKey;
        /** The leader's marks, from home; the one it is walking back to; the one where it went into the cave. */
        final List<BlockPos> trail = new ArrayList<>();
        int crumb = -1, mouthCrumb = -1;
        /** The cave's cells the team has stood in (four blocks a side); cells it could not get to; blocks passed over. */
        final Set<Long> visited = new HashSet<>(), passedCells = new HashSet<>(), passed = new HashSet<>();
        /** The vein in hand: its ore and spot, the blocks still to dig (the way in first), and who is digging which. */
        @Nullable String veinOf;
        @Nullable BlockPos veinAt;
        final List<BlockPos> work = new ArrayList<>();
        final Map<Long, UUID> claims = new HashMap<>();
        /** The floor's blocks cut to get at a vein under it, to be filled again once it is out. */
        final Set<Long> floorCuts = new HashSet<>();
        int veinDug;
        long workTick, surveyTick, lookTick, failTick = -1000, targetTick, chestTick, veinWalkTick, waitSince = -1;
        @Nullable UUID waitingFor;
        @Nullable BlockPos target, veinWalk;
        double targetBest = Double.MAX_VALUE, chestBest = Double.MAX_VALUE, veinWalkBest = Double.MAX_VALUE;
        int frontierFails, noWay;
        /** The chest or the cart the leader is making for. */
        @Nullable BlockPos chest;
        @Nullable UUID cart;
        /** The order the ore came out in; what they carried, what went into the stores; what they found. */
        final List<String> order = new ArrayList<>();
        final Map<String, Integer> haul = new LinkedHashMap<>(), stored = new LinkedHashMap<>();
        final List<Find> found = new ArrayList<>();
        final Set<String> left = new HashSet<>();
        int mined, opened, slain, torches, caves;
        boolean explored, reported;
        /** The furthest any of them got from the leader (the tests, and the log). */
        double spread;
        final Set<UUID> home = new HashSet<>();
        /** The last torch the team set; the torches it drew from the stores. */
        @Nullable BlockPos lastTorch;
        int drawn;
        /** The plan (CaveTrips): how long, in words; when it set out and when it turns for home (day time); the half
         *  days it stayed on; the nights out; the meals eaten; how many set out (and came back into the world). */
        @Nullable CaveTrips.Plan plan;
        String planWords = "";
        double days = 1.0;
        long startTime, turnAt;
        int extended, nights, meals, startSize;
        boolean searched;
        /** The night's camp: where, its walls, when it was pitched, what the team was at before it. */
        @Nullable BlockPos camp;
        final List<BlockPos> campWalls = new ArrayList<>();
        long campStart;
        @Nullable Phase campFrom;
        boolean campLit, campNoCobble;
        /** What it made down there (CaveCraft), in a line each; a spare pick wanted before a long dig. */
        final List<String> crafted = new ArrayList<>();
        int torchesMade, toolsMade;
        boolean spareWanted;
        /** The spawners it lit (and the one it is making for); veins cut in to; lava and water walled off; holes patched. */
        final Set<Long> spawnersLit = new HashSet<>();
        @Nullable BlockPos spawnerToLight;
        int lit, hidden, walled, patched;
        long spawnerTick;
        boolean greeted;
        /** The torches it brought back unused (into the storehouse). */
        int returned;

        Party(UUID village, long day) {
            this.village = village;
            this.day = day;
        }

        public List<UUID> members() { return List.copyOf(members); }
        public UUID leader() { return leader; }
        public String phase() { return homeward && phase != Phase.STORE ? "homeward" : phase.name().toLowerCase(Locale.ROOT); }
        public boolean homeward() { return homeward; }
        public List<String> order() { return List.copyOf(order); }
        public int mined() { return mined; }
        public int opened() { return opened; }
        public int slain() { return slain; }
        public int torches() { return torches; }
        public int drawn() { return drawn; }
        public double spread() { return spread; }
        @Nullable public BlockPos caveKey() { return caveKey; }
        public Map<String, Integer> haul() { return haul; }
        /** The plan it set out on (null for a lone walk home); its days; when it turns for home (day time). */
        @Nullable public CaveTrips.Plan plan() { return plan; }
        public double days() { return days; }
        public long turnAt() { return turnAt; }
        public long startTime() { return startTime; }
        public int nights() { return nights; }
        @Nullable public BlockPos camp() { return camp; }
        public List<BlockPos> campWalls() { return List.copyOf(campWalls); }
        public List<String> crafted() { return List.copyOf(crafted); }
        public int torchesMade() { return torchesMade; }
        public int toolsMade() { return toolsMade; }
        public int spawnersLit() { return lit; }
        public String why() { return why; }
        public UUID village() { return village; }
        @Nullable public BlockPos cave() { return cave; }
        /** "day 2 of 3" at this day time. */
        public String dayOf(long now) {
            long n = Math.max(1, now / 24000L - startTime / 24000L + 1);
            return "day " + n + " of " + Math.max(1, (int) Math.ceil(days));
        }
    }

    /** One member's part of the team's day out (on its Scouts.Expedition). */
    public static final class Delve {
        final Party party;
        final long day;
        @Nullable BlockPos digging;
        int dug, digNeeded, digTick;
        double reachBest = Double.MAX_VALUE;
        @Nullable UUID foe;
        int foeTick, ateTick = -1000, torchTick = -1000, spokeTick = -1000, followTick = -1000, climbs;
        /** Its crafting table set down (CaveCraft), when, and what it is making; when it last looked at its tools. */
        @Nullable BlockPos table;
        int tableTick, toolTick = -1000;
        @Nullable String making;
        /** Supper eaten in tonight's camp. */
        boolean ateCamp;
        /** Where it last stood still, since when (never stuck: a step cut); the water it poured on itself, when. */
        @Nullable BlockPos stillAt, water;
        int stillTick, stepsCut, waterTick, patchTick, wallTick;

        Delve(Party party) {
            this.party = party;
            this.day = party.day;
        }

        public Party party() { return party; }
        public String phase() { return party.phase(); }
        public int mined() { return party.mined; }
        public int opened() { return party.opened; }
        public int slain() { return party.slain; }
        public int torches() { return party.torches; }
        public boolean atTable() { return table != null; }
    }

    /** The day each cave dweller last went out. */
    private static final Map<UUID, Long> WENT = new ConcurrentHashMap<>();
    /** Cave dwellers that could not find the way out, and called for help (game time). */
    private static final Map<UUID, Long> LOST = new ConcurrentHashMap<>();
    /** What the cave dwellers came home with, for the next morning assembly. */
    private static final Map<UUID, List<String>> REPORTS = new ConcurrentHashMap<>();
    /** The beds at home of the team away on a trip, by town and member: theirs still, whoever else wants a bed. */
    private static final Map<UUID, Map<UUID, BlockPos>> AWAY_BEDS = new ConcurrentHashMap<>();
    /** When each town was last looked at for its team; when its team last waited on the makers for torches. */
    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>(), WAITED = new ConcurrentHashMap<>();
    /** Tests: a block dug in two seconds, whatever the pick; the ore the town wants, whatever its age asks. */
    private static boolean quick;
    @Nullable private static String wantedForTests;

    public static void resetForTests() {
        WENT.clear();
        LOST.clear();
        REPORTS.clear();
        TICKED.clear();
        WAITED.clear();
        AWAY_BEDS.clear();
        quick = false;
        wantedForTests = null;
        CaveTrips.resetForTests();
    }

    /** Tests: digging done quickly, so a test need not wait on the pace of an iron pick through a dozen ores. */
    public static void quickForTests(boolean on) {
        quick = on;
    }

    /** Tests: the ore the town wants most, whatever its age asks for (null: as the age asks). */
    public static void wantedForTests(@Nullable String ore) {
        wantedForTests = ore;
    }

    // ------------------------------------------------------------------ the team: who, and how many

    /** The town's cave dwellers: grown, alive, and no showcase. */
    public static List<VillageFolkEntity> dwellers(@Nullable UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.CAVE && !f.isBaby() && f.isAlive() && !f.isShowcase()) out.add(f);
        }
        return out;
    }

    /** Has the town what it takes to send anybody into the caves: a few miners and a watch (or a team already)? */
    public static boolean ready(@Nullable UUID village) {
        if (village == null) return false;
        int miners = 0, guards = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.isBaby() || !a.isAlive()) continue;
            StationTask t = a.stationTask();
            if (t == StationTask.CAVE) return true;
            if (t == StationTask.MINE) miners++;
            else if (t == StationTask.GUARD) guards++;
        }
        return miners >= MINERS && guards >= GUARDS;
    }

    /** The team a town of this many keeps: two from twenty-five, three at sixty, four at a hundred. */
    public static int team(int folk) {
        return folk < FROM ? 0 : Math.min(MOST, folk >= 100 ? 4 : folk >= 60 ? 3 : 2);
    }

    /** How many cave dwellers the town wants: none before the Iron Age, twenty-five folk, its miners and its watch. */
    public static int wanted(@Nullable UUID village) {
        if (village == null || Villages.ageOf(village).ordinal() < Villages.Age.IRON.ordinal()) return 0;
        int n = Villages.headcount(village);
        if (n < FROM || !ready(village)) return 0;
        return team(n);
    }

    /** May a hand be spared from this trade for the caves? Never from a trade the town is short of, a building's hands,
     *  the storehouse's or the scouts'. */
    static boolean spare(UUID village, StationTask t) {
        if (t == StationTask.NONE) return true;
        if (t == StationTask.CAVE || t.isCraft() || t == StationTask.STORE || t == StationTask.BANK || t == StationTask.SCOUT
            || t == StationTask.HAUL) return false;
        return Villages.share(village, t) >= 0.5;
    }

    /** What a folk knows that the caves want: its best level of the caves, the mines and the watch. */
    public static int skill(VillageFolkEntity f) {
        return Math.max(f.tradeLevel(StationTask.CAVE), Math.max(f.tradeLevel(StationTask.MINE), f.tradeLevel(StationTask.GUARD)));
    }

    /** How the town ranks a folk for the team: its skill first, its years at the rock and the blade after; its nature a little. */
    static int fitness(VillageFolkEntity f) {
        int s = skill(f) * 1000 + (f.tradeLevel(StationTask.CAVE) + f.tradeLevel(StationTask.MINE) + f.tradeLevel(StationTask.GUARD)) * 20
            + Math.min(19, f.lifetimeXp() / 1000);
        if (f.life().has(Social.Trait.CURIOUS)) s += 10;
        if (f.life().has(Social.Trait.SOCIABLE)) s -= 5;
        return s;
    }

    /** The team's leader: the most experienced of them at the caves (its years at it on a tie). */
    static boolean better(VillageFolkEntity a, VillageFolkEntity b) {
        int la = a.tradeLevel(StationTask.CAVE), lb = b.tradeLevel(StationTask.CAVE);
        return la != lb ? la > lb : a.lifetimeXp() > b.lifetimeXp();
    }

    /** The town's team's leader (at home or out), or null with no team. */
    @Nullable
    public static VillageFolkEntity leaderOf(UUID village) {
        VillageFolkEntity best = null;
        for (VillageFolkEntity f : dwellers(village)) if (best == null || better(f, best)) best = f;
        return best;
    }

    /**
     * Once in a while for each town (VillageFolkEntity, beside the bank's look): a town short of its team takes up the
     * best it can spare.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (last != null && now - last < 600L && now >= last) return;
        TICKED.put(id, now);
        long day = level.getDayTime() / 24000L;
        // The team away on a trip, its ground asleep, is still the town's team: nobody is taken up in its place.
        for (int i = 0; i < MOST && have(id) < wanted(id); i++) {
            if (appoint(level, v, day) == null) break;
        }
        // A day past its plan and not home: a search party (CaveTrips).
        CaveTrips.checkOverdue(level, v);
    }

    /** The town's team: those in the world, and those away on the trip whose ground is asleep. */
    static int have(UUID village) {
        List<VillageFolkEntity> here = dwellers(village);
        return here.size() + CaveTrips.away(village, here);
    }

    /**
     * One more for the team, if the town wants one: the most skilled hand it can spare (the best miner or guard by
     * level and years, an idle hand only if it is as good), given a head start at the caves for what it knows of the
     * rock and the blade. Returns who, or null.
     */
    @Nullable
    public static VillageFolkEntity appoint(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (have(id) >= wanted(id)) return null;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive() || f.isElder()) continue;
            if (f.trip() != null || f.expedition() != null) continue;
            if (!spare(id, f.stationTask())) continue;
            int age = f.ageYears();
            if (age < 18 || age > 50) continue;                       // fit for a day underground (JobMarket.ages)
            int score = fitness(f);
            if (score > bestScore) { bestScore = score; best = f; }
        }
        if (best == null) return null;
        StationTask was = best.stationTask();
        // A head start: a skilled miner or guard knows the most of what the caves want, and is a level or two short of it there.
        int knows = Math.max(best.tradeLevel(StationTask.MINE), best.tradeLevel(StationTask.GUARD));
        int has = AssistantEntity.xpForLevel(best.tradeLevel(StationTask.CAVE)), start = AssistantEntity.xpForLevel(Math.max(0, knows - 2));
        if (start > has) best.schoolXp(StationTask.CAVE, start - has);
        BlockPos post = post(v);
        best.setStation(post, StationTask.CAVE);
        best.assignPlot(WorkZone.around(post, 4, WorkZone.DEFAULT_DEPTH), "The Caves");
        int n = dwellers(id).size();
        Villages.tell(id, day, best.displayNameCap() + " (" + (was == StationTask.NONE ? "with no trade" : JobMarket.a(JobMarket.noun(was))
            + " of level " + best.tradeLevel(was)) + ") joined the town's cave dwellers, " + n + " of " + wanted(id));
        best.persona().remember(day, "I was picked for the town's cave dwellers", 5);
        FolkTalk.speak(best, FolkTalk.pick(level.getRandom(), "Picked for the caves! A pick in one hand and a blade in the other.",
            "The town wants its best down there. I'll not let it down.", "Into the dark with the team, then. I'll want plenty of torches."));
        LOG.info("[MCA-CAVES] {} of {} joined the cave dwellers (was {} level {}, skill {}), {} of {}", best.displayNameCap(), Villages.name(id),
            was, best.tradeLevel(was), skill(best), n, wanted(id));
        return best;
    }

    /** Where a cave dweller stands of a day at home: by the board, or the square. */
    static BlockPos post(Villages.Village v) {
        BlockPos board = VillageBoards.lectern(v.id());
        BlockPos at = board != null ? board : v.centre();
        return at.relative(Direction.EAST, 3);
    }

    // ------------------------------------------------------------------ the kit

    public static boolean isPickaxe(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof PickaxeItem;
    }

    /** A pickaxe's worth to a cave dweller: its metal first, how worn second. */
    static int pickScore(ItemStack s) {
        if (!isPickaxe(s)) return -1;
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        int metal = path.startsWith("netherite") ? 6 : path.startsWith("diamond") ? 5 : path.startsWith("iron") ? 4
            : path.startsWith("stone") ? 3 : path.startsWith("golden") ? 2 : 1;
        int left = s.getMaxDamage() - s.getDamageValue();
        return metal * 10000 + Math.min(9999, Math.max(0, left)) - (left < 16 ? 50000 : 0);
    }

    /** The best pick it carries, in hand or in its pack; empty if none. */
    static ItemStack bestPick(VillageFolkEntity f) {
        ItemStack best = ItemStack.EMPTY;
        if (isPickaxe(f.getMainHandItem())) best = f.getMainHandItem();
        for (ItemStack s : f.getInventoryItems()) if (pickScore(s) > pickScore(best)) best = s;
        return best;
    }

    /** Its best pick into its hand. */
    static void equipPick(VillageFolkEntity f) {
        ItemStack best = bestPick(f);
        if (best.isEmpty() || best == f.getMainHandItem()) return;
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            if (pack.get(i) != best) continue;
            ItemStack old = f.getMainHandItem();
            f.setItemSlot(EquipmentSlot.MAINHAND, best);
            pack.set(i, old);
            return;
        }
    }

    static boolean food(ItemStack s) {
        return s.get(DataComponents.FOOD) != null && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE) && !s.is(Items.POISONOUS_POTATO)
            && !valuable(s);
    }

    /** Its kit, kept when it banks its finds: what it wears and wields, its rations, and what the town marked as issued
     *  (its crafting table too). Its torches, cobble and makings it keeps up to so many (keepsOf). */
    static boolean kit(ItemStack s) {
        boolean found = valuable(s) && !WatchKit.issued(s);           // a diamond blade out of an old chest is the town's find
        return s.isDamageableItem() && !found || food(s) || WatchKit.issued(s) && !s.is(Items.IRON_INGOT);
    }

    /** How many of this it keeps in its pack over its kit: a stack of torches, sixteen cobble, the makings (CaveCraft). */
    static int keepsOf(ItemStack s) {
        if (s.is(Items.TORCH)) return TORCHES;
        if (s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE)) return 16;
        return CaveCraft.makings(s);
    }

    /** What of each of its pack's slots goes into the stores: all of it but its kit, its keepsakes, and what keepsOf
     *  allows. Banking its haul at home, the coal it mined goes in too (the town's makers make its torches). */
    static int[] spare(VillageFolkEntity f, boolean banking) {
        var pack = f.getInventoryItems();
        int[] out = new int[pack.size()];
        Map<net.minecraft.world.item.Item, Integer> kept = new HashMap<>();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || kit(s) || Homes.isKeepsake(s)) continue;
            int have = kept.getOrDefault(s.getItem(), 0);
            int keep = Math.max(0, Math.min(s.getCount(), (banking && CaveCraft.coal(s) ? 0 : keepsOf(s)) - have));
            kept.put(s.getItem(), have + keep);
            out[i] = s.getCount() - keep;
        }
        return out;
    }

    /**
     * Fitted out of the stores, free, as the watch is (WatchKit.fit: armour, a blade, a shield), with the best pick
     * the stores hold, torches (no more than its share of what the town can spare: torchAllowance), food for the days
     * out and one over, the cobble for its nights' camps, and the makings of its crafting (CaveCraft): a crafting
     * table, a few planks and sticks, and three iron ingots when the town can spare them. Returns what it was given.
     */
    public static List<String> kitUp(ServerLevel level, Villages.Village v, VillageFolkEntity f, int torchAllowance, int meals, int cobbleWanted) {
        List<String> got = new ArrayList<>();
        UUID id = v.id();
        String who = f.displayNameCap();
        // Travelling light: what is not its kit (a farmer's seed, a sapling, a bench) waits in the stores, so the pack
        // has room for what the caves give up. Its keepsakes stay with it, and its makings.
        var pack = f.getInventoryItems();
        int[] out = spare(f, false);
        for (int i = 0; i < pack.size(); i++) {
            if (out[i] <= 0) continue;
            ItemStack s = pack.get(i);
            Crafts.store(level, v, s.copyWithCount(out[i]));
            s.shrink(out[i]);
            if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
        }
        for (ItemStack s : WatchKit.fit(level, v, f)) got.add(Bench.words(s.getItem(), s.getCount()));
        // The best pickaxe the stores hold, if it beats its own; the town's old one back into the stores.
        ItemStack mine = bestPick(f);
        Workshop.Found better = Workshop.bestInStores(level, id, CaveDwellers::isPickaxe, CaveDwellers::pickScore, pickScore(mine));
        if (better != null) {
            ItemStack pick = WatchKit.mark(Workshop.takeOut(level, id, better, who));
            if (!mine.isEmpty() && WatchKit.issued(mine)) {
                ItemStack back = mine.copy();
                mine.setCount(0);
                Workshop.backIntoStores(level, v, WatchKit.unmarked(back), who);
            }
            ItemStack left = f.insertGiven(pick);
            if (!left.isEmpty()) Workshop.backIntoStores(level, v, WatchKit.unmarked(left), who);
            else got.add(Bench.words(pick.getItem(), 1));
        } else if (mine.isEmpty() && Crafts.stock(level, v, s -> s.is(Items.COBBLESTONE)) >= 3
                && Crafts.stock(level, v, s -> s.is(Items.STICK)) >= 2) {
            // None in the stores either: a stone one, of the stores' cobble and sticks, as a player makes it.
            if (Crafts.take(level, v, s -> s.is(Items.COBBLESTONE), 3) && Crafts.take(level, v, s -> s.is(Items.STICK), 2)) {
                f.insertGiven(WatchKit.mark(new ItemStack(Items.STONE_PICKAXE)));
                got.add("a stone pickaxe, of the stores' cobble");
            }
        }
        // Torches: the town's own make (its makers keep the stores in them: Masonry.keep, Links), and only its share of
        // what the town can spare over its own lights (spareTorches). Never made here out of nothing.
        int want = torchAllowance;
        if (want > 0) {
            int n = Math.min(want, Crafts.stock(level, v, s -> s.is(Items.TORCH)));
            if (n > 0 && Crafts.take(level, v, s -> s.is(Items.TORCH), n)) {
                ItemStack left = f.insertGiven(new ItemStack(Items.TORCH, n));
                if (!left.isEmpty()) Crafts.store(level, v, left);
                got.add((n - left.getCount()) + " torches");
            }
        }
        // Food for the days out and one over, as much as the town can spare (CaveTrips.spareFood).
        int ate = 0, spareFood = CaveTrips.spareFood(level, id);
        for (int i = 0; i < meals + 4 && f.countMatching(CaveDwellers::food) < meals && ate < spareFood; i++) {
            ItemStack s = Crafts.takeOne(level, v, CaveDwellers::food);
            if (s.isEmpty()) break;
            ItemStack left = f.insertGiven(s);
            if (!left.isEmpty()) Crafts.store(level, v, left);
            ate += s.getCount() - left.getCount();
        }
        if (ate > 0) got.add(ate + " things to eat");
        // Cobble: to wall off lava and water, patch a hole, fill a pit dug in a cave's floor, and wall in a night's camp.
        int cobble = cobbleWanted - f.countMatching(s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE));
        if (cobble > 0 && Crafts.stock(level, v, s -> s.is(Items.COBBLESTONE)) >= cobble + 16
                && Crafts.take(level, v, s -> s.is(Items.COBBLESTONE), cobble)) {
            f.insertGiven(new ItemStack(Items.COBBLESTONE, cobble));
            got.add(cobble + " cobblestone");
        }
        // The makings of its crafting (CaveCraft): a crafting table (the stores', or made of four of their planks), a few
        // planks and sticks, and three iron ingots when the town can spare them (two dozen put by, and its age not short
        // of iron).
        if (f.countMatching(s -> s.is(Items.CRAFTING_TABLE)) == 0
                && (Crafts.take(level, v, s -> s.is(Items.CRAFTING_TABLE), 1) || Crafts.usePlanks(level, v, 4))) {
            f.insertGiven(WatchKit.mark(new ItemStack(Items.CRAFTING_TABLE)));
            got.add("a crafting table");
        }
        int planks = 4 - f.countMatching(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) - 4 * f.countMatching(s -> s.is(net.minecraft.tags.ItemTags.LOGS));
        if (planks > 0) Crafts.planks(level, v, planks);
        for (int i = 0; i < planks; i++) {
            ItemStack s = Crafts.takeOne(level, v, x -> x.is(net.minecraft.tags.ItemTags.PLANKS));
            if (s.isEmpty()) break;
            ItemStack left = f.insertGiven(s);
            if (!left.isEmpty()) Crafts.store(level, v, left);
        }
        for (int i = f.countMatching(s -> s.is(Items.STICK)); i < 4; i++) {
            ItemStack s = Crafts.takeOne(level, v, x -> x.is(Items.STICK));
            if (s.isEmpty()) break;
            ItemStack left = f.insertGiven(s);
            if (!left.isEmpty()) Crafts.store(level, v, left);
        }
        int ingots = CaveCraft.INGOTS - f.countMatching(s -> s.is(Items.IRON_INGOT));
        if (ingots > 0 && !ironShort(level, id) && Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) >= 24
                && Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), ingots)) {
            f.insertGiven(WatchKit.mark(new ItemStack(Items.IRON_INGOT, ingots)));
            got.add(ingots + " iron ingots, for a new pick if it wants one");
        }
        if (f.countMatching(s -> s.is(Items.WATER_BUCKET)) == 0 && Crafts.take(level, v, s -> s.is(Items.WATER_BUCKET), 1)) {
            f.insertGiven(new ItemStack(Items.WATER_BUCKET));
            got.add("a bucket of water");
        }
        if (!got.isEmpty()) f.brain("fitted out for the caves by the town: " + String.join(", ", got));
        return got;
    }

    /** Tests: fitted out of the stores now, for a day, with what torches the town can spare. */
    public static List<String> kitUpForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return kitUp(level, v, f, Math.min(TORCHES, spareTorches(level, v.id())), CaveTrips.MEALS * 2, COBBLE);
    }

    /** Is the town short of iron for its age (its iron is not to be spared for the team's spare picks)? */
    static boolean ironShort(ServerLevel level, UUID village) {
        try {
            for (Villages.Need n : Villages.needs(level, village)) if (n.task() == Villages.Task.IRON) return true;
        } catch (RuntimeException ex) {
            LOG.debug("[MCA-CAVES] needs look failed: {}", ex.toString());
        }
        return false;
    }

    /** The torches the stores keep for the team's next day out, on top of the town's own (Masonry.keep): a stack each. */
    public static int torchesKept(@Nullable UUID village) {
        return village == null ? 0 : dwellers(village).size() * TORCHES;
    }

    /** The torches the town can spare the team: what the stores hold over what it keeps for its own lights, its miners
     *  and its lamps (Masonry.townTorches). Never the last of them. */
    public static int spareTorches(ServerLevel level, @Nullable UUID village) {
        if (village == null) return 0;
        return Math.max(0, Market.stock(level, village, s -> s.is(Items.TORCH)) - Masonry.townTorches(village));
    }

    /** Are the stores short of the team's torches (the smelter, the smith and the shop make some: Links)? */
    public static boolean torchesShort(ServerLevel level, @Nullable UUID village) {
        if (village == null || dwellers(village).isEmpty()) return false;
        return Market.stock(level, village, s -> s.is(Items.TORCH)) < Masonry.townTorches(village) + torchesKept(village);
    }

    /**
     * A cave dweller leaving the trade (another trade, another town) hands back what the town issued it, its pick
     * with the armour and the blade, into the stores. Not to the watch: the watch's kit is the same kit.
     */
    public static int handBack(VillageFolkEntity f, String why) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || !(f.level() instanceof ServerLevel level)) return 0;
        String who = f.displayNameCap();
        List<String> back = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = f.getItemBySlot(slot);
            if (!WatchKit.issued(s)) continue;
            back.add(Bench.words(s.getItem(), s.getCount()));
            Workshop.backIntoStores(level, v, WatchKit.unmarked(s.copy()), who);
            f.setItemSlot(slot, ItemStack.EMPTY);
        }
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (!WatchKit.issued(s)) continue;
            back.add(Bench.words(s.getItem(), s.getCount()));
            Workshop.backIntoStores(level, v, WatchKit.unmarked(s.copy()), who);
            pack.set(i, ItemStack.EMPTY);
        }
        if (back.isEmpty()) return 0;
        f.brain("handed the caves' kit back into the stores (" + why + "): " + String.join(", ", back));
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "The town's kit goes back to the stores. The next one into the caves will want it.",
            "Back it goes: " + String.join(", ", back) + ". It was never mine to keep."));
        return back.size();
    }

    // ------------------------------------------------------------------ at home

    /** How far out its day takes it: further as the town's age climbs. */
    public static int range(@Nullable UUID village) {
        if (village == null) return 100;
        return switch (Villages.ageOf(village)) {
            case WOOD, STONE, IRON -> 100;
            case DIAMOND -> 150;
            case NETHER -> 200;
        };
    }

    /** Is this folk down in the caves (or on its way there or back) with the team? */
    public static boolean caving(VillageFolkEntity f) {
        Scouts.Expedition e = f.expedition();
        return e != null && e.delve != null;
    }

    /** The team's day out this folk is on, or null. */
    @Nullable
    public static Party partyOf(VillageFolkEntity f) {
        Scouts.Expedition e = f.expedition();
        return e == null || e.delve == null ? null : e.delve.party;
    }

    /**
     * A cave dweller's day at home (its station brain): in the morning the team is fitted out and away together; the
     * rest of the day it is by the board with the report. Returns whether it did something.
     */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        if (f.expedition() != null) return true;
        long time = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        if (lost(f, level, v)) return true;
        int reach = Villages.townReach(id) + 40;
        // Out past the town with a trip under way (a restart, its ground woken again): the trip taken up where it is.
        if (Scouts.flat(f.blockPosition(), v.centre()) > (double) (reach - 24) * (reach - 24) && CaveTrips.resume(level, f, v)) return true;
        // Somebody left out past the town (a day cut short): home first.
        if (Scouts.flat(f.blockPosition(), v.centre()) > (double) reach * reach) {
            comeHome(level, f, v, "came home");
            return true;
        }
        boolean fit = f.getHealth() >= f.getMaxHealth() * 0.7F && !level.isThundering() && !Raids.underAlarm(id);
        boolean morning = time >= 1200 && time < 4200;
        if (morning && fit && WENT.getOrDefault(f.getUUID(), -1L) < day && !Assemblies.attending(f)) {
            Party p = setOut(level, v, day, null);
            if (p != null && p.members.contains(f.getUUID())) return true;
        }
        BlockPos spot = post(v);
        if (f.blockPosition().distSqr(spot) > 9) {
            if (f.getNavigation().isDone()) f.walkTo(spot, 0.8D);
        } else {
            BlockPos board = VillageBoards.lectern(id);
            BlockPos at = board != null ? board : v.centre();
            f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 1.5, at.getZ() + 0.5);
            f.hobbyNow = "going over what the caves gave up";
        }
        return true;
    }

    /** The beds at home of the team away on its trip (Villages.bedsClaimed): theirs still while their ground sleeps. */
    public static java.util.Collection<BlockPos> awayBeds(@Nullable UUID village) {
        Map<UUID, BlockPos> beds = village == null ? null : AWAY_BEDS.get(village);
        return beds == null ? List.of() : List.copyOf(beds.values());
    }

    /** Is this cave dweller missed by the town (SearchParties.seen): lost in the caves after calling for help, or one of a
     *  team a day overdue from its trip? */
    public static boolean missing(VillageFolkEntity f) {
        return f.stationTask() == StationTask.CAVE && (LOST.containsKey(f.getUUID()) || CaveTrips.overdue(f));
    }

    /** Lost underground after calling for help: the search party's to find it, and the miners' stairs to get it up. */
    private static boolean lost(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        Long since = LOST.get(f.getUUID());
        if (since == null) return false;
        int reach = Villages.townReach(v.id()) + 16;
        if (Scouts.flat(f.blockPosition(), v.centre()) <= (double) reach * reach || level.getGameTime() - since > 4 * 24000L) {
            LOST.remove(f.getUUID());
            return false;
        }
        // Up out of the ground on its own and nobody out looking for it: home over the land.
        if (!MineStairs.underground(level, f.blockPosition()) && !SearchParties.searchedFor(f.getUUID())) {
            LOST.remove(f.getUUID());
            comeHome(level, f, v, "got out on its own");
            return true;
        }
        f.hobbyNow = "lost in the caves, and waiting to be found";
        return true;
    }

    /** Home from wherever it is, on its own: a day out that is all way home. */
    static void comeHome(ServerLevel level, VillageFolkEntity f, Villages.Village v, String why) {
        Party p = new Party(v.id(), level.getDayTime() / 24000L);
        p.members.add(f.getUUID());
        p.leader = f.getUUID();
        p.homeward = true;
        p.why = why;
        p.crumb = -1;
        p.reported = true;                    // nothing found to tell: only what it carries, into the stores
        Scouts.Expedition e = new Scouts.Expedition(v.id(), v.centre(), 0, f.blockPosition());
        e.delve = new Delve(p);
        e.returning = true;
        e.crumb = -1;
        e.why = why;
        e.startedTick = e.gainedTick = e.surveyTick = f.tickCount;
        f.clearQueue();
        f.expedition(e);
    }

    /**
     * The team fitted out and off together: every cave dweller at home and fit for it, the most experienced leading,
     * on a plan (CaveTrips.plan: the work waiting, the walk, cut to what the town can spare), told on the board, in the
     * chronicle and on the Caves page. A cave given (the report's, a test's) is made for; else the leader looks. Too
     * few torches or too little food to go at all, it waits on the makers. Returns the party, or null if nobody went.
     */
    @Nullable
    static Party setOut(ServerLevel level, Villages.Village v, long day, @Nullable BlockPos cave) {
        UUID id = v.id();
        BlockPos home = v.centre();
        int reach = Villages.townReach(id) + 40;
        List<VillageFolkEntity> team = new ArrayList<>();
        for (VillageFolkEntity m : dwellers(id)) {
            if (m.expedition() != null || LOST.containsKey(m.getUUID()) || m.isSleeping() || m.trip() != null) continue;
            if (cave == null && (WENT.getOrDefault(m.getUUID(), -1L) >= day || m.getHealth() < m.getMaxHealth() * 0.7F
                || Assemblies.attending(m) || Scouts.flat(m.blockPosition(), home) > (double) reach * reach)) continue;
            team.add(m);
        }
        if (team.isEmpty()) return null;
        team.sort((a, b) -> better(a, b) ? -1 : better(b, a) ? 1 : 0);
        long now = level.getGameTime();
        VillageFolkEntity first = team.get(0);
        int range = range(id);
        BlockPos dest = cave != null ? cave : knownCave(level, first, v, range, day, null);
        // The plan.
        CaveTrips.Plan plan = CaveTrips.plan(level, v, team, dest);
        if (plan.days() <= 0 && cave == null) {
            Long waited = WAITED.get(id);
            if (waited == null || now - waited > 2400 || now < waited) {
                WAITED.put(id, now);
                boolean torches = plan.words().contains("torches");
                FolkTalk.speak(first, torches ? "Not torches enough to go down today. We'll wait on the makers." : "Nothing to eat to take down with us. Not today.");
                Villages.tell(id, day, "the cave team stayed home, waiting on " + (torches ? "the makers for torches" : "the stores for food"));
                LOG.info("[MCA-CAVES] the team of {} waits: {}", Villages.name(id), plan.words());
            }
            return null;
        }
        double days = Math.max(CaveTrips.SHORTEST, plan.days());
        // The kit for it: torches for the days (only the town's spare, shared out evenly), food for the days and one
        // over, the cobble for the nights' camps, the makings.
        Predicate<ItemStack> torch = s -> s.is(Items.TORCH);
        int spare = spareTorches(level, id), drawn = 0, shares = team.size();
        List<VillageFolkEntity> going = new ArrayList<>();
        for (VillageFolkEntity m : team) {
            int before = m.countMatching(torch);
            int want = Math.max(0, plan.torches() - before);
            kitUp(level, v, m, Math.min(want, shares > 0 ? spare / shares : 0), plan.meals(), plan.cobble());
            shares--;
            int drew = Math.max(0, m.countMatching(torch) - before);
            spare -= drew;
            drawn += drew;
            WENT.put(m.getUUID(), day);
            if (m.countMatching(CaveDwellers::food) == 0) {
                FolkTalk.speak(m, "Nothing in the stores to take down with us. Nobody goes into the dark hungry.");
                continue;
            }
            if (bestPick(m).isEmpty() && m.countCarried(CaveCraft::blade) == 0 && !CaveCraft.canMakePick(m)) {
                FolkTalk.speak(m, "No pick and no blade. I'm not going down there with my bare hands.");
                continue;
            }
            going.add(m);
        }
        if (going.isEmpty()) return null;
        VillageFolkEntity lead = going.get(0);
        int bearing;
        BlockPos target;
        if (dest != null) {
            bearing = Scouts.bearingOf(dest.getX() - home.getX(), dest.getZ() - home.getZ());
            target = dest;
        } else {
            bearing = leastLooked(id, lead, day);
            double ang = bearing * (2 * Math.PI / Scouts.BEARINGS);
            target = new BlockPos(home.getX() + (int) Math.round(Math.cos(ang) * range), home.getY(),
                home.getZ() + (int) Math.round(Math.sin(ang) * range));
        }
        Party p = new Party(id, day);
        p.leader = lead.getUUID();
        p.cave = dest;
        p.trail.add(lead.blockPosition().immutable());
        p.surveyTick = p.workTick = now;
        p.drawn = drawn;
        p.plan = plan;
        p.planWords = plan.words();
        p.days = days;
        p.startTime = level.getDayTime();
        p.turnAt = CaveTrips.turnAt(p.startTime, days);
        p.startSize = going.size();
        for (VillageFolkEntity m : going) {
            p.members.add(m.getUUID());
            if (m.bedPos() != null) AWAY_BEDS.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(m.getUUID(), m.bedPos().immutable());
            m.clearQueue();
            m.getNavigation().stop();
            Scouts.Expedition e = new Scouts.Expedition(id, home, bearing, target);
            e.delve = new Delve(p);
            e.startedTick = e.gainedTick = e.surveyTick = m.tickCount;
            e.trail.add(m.blockPosition().immutable());
            m.expedition(e);
        }
        markLooked(id, bearing, day);
        // The plan, told: on the board and the Caves page, in the chronicle; kept with the town while the team is away.
        Ledger.note(id, "caves.plan", plan.words());
        Ledger.note(id, "caves.reckoning", String.join("\n", plan.reckoning()));
        List<String> names = new ArrayList<>();
        for (VillageFolkEntity m : going) if (m != lead) names.add(m.displayNameCap());
        Villages.tell(id, day, "the cave team (" + lead.displayNameCap() + (names.isEmpty() ? "" : " leading " + JobMarket.join(names))
            + ") set out: " + plan.words());
        CaveTrips.keep(p);
        String when = days > 1.0 ? "Back in " + CaveTrips.numberWord((int) Math.ceil(days)) + " days." : days < 1.0 ? "Back by noon." : "Back by dusk!";
        FolkTalk.speak(lead, plan.words().replaceFirst("\\.$", "") + ". " + (names.isEmpty() ? "" : JobMarket.join(names) + ", with me. ") + when);
        LOG.info("[MCA-CAVES] the team of {} sets out: {} leading, {}; {} (range {}, bearing {}; {} torches drawn; turns at {})", Villages.name(id),
            lead.displayNameCap(), names, plan.words(), range, bearing, drawn, p.turnAt);
        return p;
    }

    /** Tests and the stage: the team (this one with it) into this cave now, whatever the hour, fitted out first. */
    public static boolean sendForTests(VillageFolkEntity f, ServerLevel level, BlockPos cave) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        for (VillageFolkEntity m : dwellers(id)) {
            Scouts.Expedition was = m.expedition();
            if (was != null) {
                Scouts.release(level, m, was);
                m.expedition(null);
            }
        }
        Party p = setOut(level, v, level.getDayTime() / 24000L, cave);
        return p != null && p.members.contains(f.getUUID());
    }

    /**
     * The nearest cave the report knows, within range: not worked out, or worked out with veins waiting on a pick the
     * team now has; not gone to in the last two days; not the one it has just left.
     */
    @Nullable
    static BlockPos knownCave(ServerLevel level, VillageFolkEntity f, Villages.Village v, int range, long day, @Nullable BlockPos not) {
        Find best = null;
        for (Find x : report(v.id())) {
            if (x.kind() != Kind.CAVE && x.kind() != Kind.RAVINE) continue;
            if (Scouts.flat(x.at(), v.centre()) > (double) range * range) continue;
            if (not != null && key(x.at()) == key(not)) continue;
            if (done(v.id(), x.at()) && !waitingFor(level, v.id(), x.at(), f)) continue;
            String went = Ledger.note(v.id(), "caves.went/" + key(x.at()));
            if (went != null) {
                try {
                    if (day - Long.parseLong(went) < 2) continue;
                } catch (NumberFormatException ignored) {
                    // an unreadable day: gone to long ago
                }
            }
            if (best == null || x.at().distSqr(v.centre()) < best.at().distSqr(v.centre())) best = x;
        }
        return best == null ? null : best.at();
    }

    /** Has a worked-out cave a vein waiting on a pick this one (or the stores) has now? */
    static boolean waitingFor(ServerLevel level, UUID village, BlockPos cave, VillageFolkEntity f) {
        ItemStack pick = bestPick(f);
        for (Vein vein : veins(village, cave)) {
            if (!"waiting".equals(vein.state())) continue;
            BlockState st = oreState(vein.ore());
            if (st != null && !pick.isEmpty() && pick.isCorrectToolForDrops(st)) return true;
            if (st != null && Market.stock(level, village, s -> isPickaxe(s) && s.isCorrectToolForDrops(st)) > 0) return true;
        }
        return false;
    }

    /** A block of an ore, to ask a pick whether it will take it. */
    @Nullable
    static BlockState oreState(String ore) {
        return switch (ore) {
            case "diamond" -> Blocks.DIAMOND_ORE.defaultBlockState();
            case "emerald" -> Blocks.EMERALD_ORE.defaultBlockState();
            case "gold" -> Blocks.GOLD_ORE.defaultBlockState();
            case "iron" -> Blocks.IRON_ORE.defaultBlockState();
            case "redstone" -> Blocks.REDSTONE_ORE.defaultBlockState();
            case "lapis" -> Blocks.LAPIS_ORE.defaultBlockState();
            case "copper" -> Blocks.COPPER_ORE.defaultBlockState();
            case "coal" -> Blocks.COAL_ORE.defaultBlockState();
            case "obsidian" -> Blocks.OBSIDIAN.defaultBlockState();
            case "amethyst" -> Blocks.AMETHYST_CLUSTER.defaultBlockState();
            default -> null;
        };
    }

    static long key(BlockPos p) {
        return BlockPos.asLong(p.getX() >> 3, 0, p.getZ() >> 3);
    }

    /** Has a cave been worked out (gone all through, every vein its team's picks allow taken)? */
    static boolean done(UUID village, BlockPos cave) {
        String s = Ledger.note(village, "caves.done");
        return s != null && s.contains("," + key(cave) + ",");
    }

    private static void markDone(UUID village, BlockPos cave) {
        String s = Ledger.note(village, "caves.done");
        if (s == null || s.isEmpty()) s = ",";
        if (s.contains("," + key(cave) + ",")) return;
        if (s.length() > 4000) s = "," + s.substring(s.indexOf(',', s.length() / 2) + 1);
        Ledger.note(village, "caves.done", s + key(cave) + ",");
    }

    /** The bearing the cave dwellers have looked along least lately. */
    static int leastLooked(UUID village, VillageFolkEntity f, long day) {
        long[] last = looked(village);
        java.util.Random rng = new java.util.Random(f.getUUID().getLeastSignificantBits() ^ day * 31L);
        int start = rng.nextInt(Scouts.BEARINGS), best = start;
        long oldest = Long.MAX_VALUE;
        for (int k = 0; k < Scouts.BEARINGS; k++) {
            int b = (start + k) % Scouts.BEARINGS;
            if (last[b] < oldest) { oldest = last[b]; best = b; }
        }
        return best;
    }

    private static long[] looked(UUID village) {
        long[] out = new long[Scouts.BEARINGS];
        java.util.Arrays.fill(out, -1L);
        String s = Ledger.note(village, "caves.looked");
        if (s == null || s.isEmpty()) return out;
        String[] p = s.split(",");
        for (int i = 0; i < Math.min(p.length, out.length); i++) {
            try {
                out[i] = Long.parseLong(p[i]);
            } catch (NumberFormatException ignored) {
                // never looked
            }
        }
        return out;
    }

    private static void markLooked(UUID village, int bearing, long day) {
        long[] last = looked(village);
        last[Math.floorMod(bearing, last.length)] = day;
        StringBuilder sb = new StringBuilder();
        for (long l : last) sb.append(sb.length() == 0 ? "" : ",").append(l);
        Ledger.note(village, "caves.looked", sb.toString());
    }

    // ------------------------------------------------------------------ a cave's veins, kept with the town

    /** A cave's list of veins (the report's spot for the cave). */
    public static List<Vein> veins(UUID village, BlockPos cave) {
        List<Vein> out = new ArrayList<>();
        String s = Ledger.note(village, "caves.veins/" + key(cave));
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            Vein v = Vein.decode(line);
            if (v != null) out.add(v);
        }
        return out;
    }

    static void saveVeins(UUID village, BlockPos cave, List<Vein> list) {
        while (list.size() > VEINS_KEPT) {
            int drop = 0;
            for (int i = 0; i < list.size(); i++) if ("mined".equals(list.get(i).state())) { drop = i; break; }
            list.remove(drop);
        }
        StringBuilder sb = new StringBuilder();
        for (Vein v : list) sb.append(sb.length() == 0 ? "" : "\n").append(v.encode());
        Ledger.note(village, "caves.veins/" + key(cave), sb.toString());
    }

    /** A vein's state set on its cave's list. */
    static void markVein(UUID village, BlockPos cave, BlockPos at, String ore, String state) {
        List<Vein> list = veins(village, cave);
        for (int i = 0; i < list.size(); i++) {
            Vein v = list.get(i);
            if (v.ore().equals(ore) && v.at().distSqr(at) <= 4) {
                list.set(i, v.as(state));
                saveVeins(village, cave, list);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ out: a step at a time (Scouts.drive, every five ticks)

    /** The team's members out on this day, alive and with it. */
    static List<VillageFolkEntity> members(ServerLevel level, Party p) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (UUID u : p.members) {
            if (level.getEntity(u) instanceof VillageFolkEntity m && m.isAlive() && onTrip(m, p)) out.add(m);
        }
        return out;
    }

    private static boolean onTrip(VillageFolkEntity m, Party p) {
        Scouts.Expedition e = m.expedition();
        return e != null && e.delve != null && e.delve.party == p;
    }

    /** The party's leader now: the one it set out with, or (fallen, or gone home) the most experienced still out. */
    static VillageFolkEntity lead(ServerLevel level, Party p, VillageFolkEntity f) {
        if (p.leader != null && level.getEntity(p.leader) instanceof VillageFolkEntity l && l.isAlive() && onTrip(l, p)) return l;
        VillageFolkEntity best = null;
        for (VillageFolkEntity m : members(level, p)) if (best == null || better(m, best)) best = m;
        if (best == null) best = f;
        p.leader = best.getUUID();
        best.brain("leading the cave dwellers now");
        return best;
    }

    /** A member of the team out on its day: walked, fought, dug and lit a step at a time. True while it is out. */
    static boolean drive(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e) {
        Delve d = e.delve;
        if (d == null) return false;
        Party p = d.party;
        keepAwake(level, f, e);
        long time = level.getDayTime() % 24000L, now = level.getGameTime();
        BlockPos feet = f.blockPosition();
        boolean below = MineStairs.underground(level, feet);
        if (p.phase == Party.Phase.IN || below) p.visited.add(cell(feet));
        VillageFolkEntity lead = lead(level, p, f);
        boolean leading = lead == f;
        e.returning = p.homeward;
        if (p.phase != Party.Phase.CAMP && f.isShiftKeyDown()) f.setShiftKeyDown(false);
        if (!leading && p.phase != Party.Phase.STORE && p.phase != Party.Phase.CAMP) p.spread = Math.max(p.spread, Math.sqrt(f.distanceToSqr(lead)));
        if (leading) p.startSize = Math.max(p.startSize, members(level, p).size());
        // Cutting its own stairs up out of the ground (MineGoal's "out", as a lost miner does): that is the work now.
        Job j = f.peekJob();
        if (j != null && j.type() == Job.Type.MINE) {
            e.gainedTick = f.tickCount;
            f.hobbyNow = "cutting its way up out of the caves";
            return true;
        }
        // Light: along the way (the leader), and where it works or fights in the dark.
        if (p.phase != Party.Phase.STORE) light(level, f, d, p, leading);
        if (douse(level, f, d)) return true;
        if (fight(level, f, e, d, p, lead)) return true;
        // Hurt: something to eat; one of them badly hurt, the whole team home.
        if (f.getHealth() < f.getMaxHealth() * 0.65F && f.tickCount - d.ateTick > 120 && f.eatFromPack()) d.ateTick = f.tickCount;
        if (!p.homeward) {
            if (f.getHealth() < f.getMaxHealth() * 0.4F) {
                turnBack(level, f, p, f.displayNameCap() + " got hurt");
            } else if (leading) {
                String why = whyHome(level, f, e, p, time);
                if (why != null) turnBack(level, f, p, why);
            }
        }
        // Its torches and its tools seen to, out of its own pack, down in the caves where it wants them (CaveCraft); lava
        // and water walled off, a hole patched.
        if (p.phase != Party.Phase.STORE && p.phase != Party.Phase.CAMP) {
            boolean down = below || p.phase == Party.Phase.IN || p.phase == Party.Phase.LEAVING;
            if (down && CaveCraft.torches(level, f, p) > 0) return true;
            if ((down || d.table != null) && CaveCraft.tools(level, f, d, p)) return true;
            if (wallOff(level, f, d, p)) return true;
            if (patch(level, f, d, p, leading)) return true;
        } else if (d.table != null) {
            CaveCraft.pickUpTable(level, f, d);
        }
        if (leading && p.phase != Party.Phase.CAMP && now - p.surveyTick >= 40) {
            p.surveyTick = now;
            survey(level, f, e, p);
            if (f.expedition() != e) return true;
        }
        if (leading) crumbs(level, f, p, below);
        if (p.phase == Party.Phase.STORE) return toTheStores(level, f, e, d, p);
        // The night's camp on a long trip (CaveTrips): pitched at dusk, broken at first light.
        if (p.phase == Party.Phase.CAMP) return CaveTrips.camp(level, f, d, p, leading) || follow(level, f, d, lead);
        if (leading && CaveTrips.nightfall(level, p)) {
            CaveTrips.pitch(level, f, p);
            return true;
        }
        if (unstick(level, f, d, p, lead)) return true;
        if (p.homeward) return leading ? walkHome(level, f, e, d, p) : follow(level, f, d, lead);
        if (leading) return leadOn(level, f, e, d, p);
        return help(level, f, e, d, p, lead);
    }

    /**
     * Why the leader takes the team home now, or null. The plan's hours up (unless the work is going well and the
     * supplies hold: CaveTrips.stayOn); other work; one of the team lost; a pack full of what they found; somebody hurt
     * with nothing to eat; no tool and nothing to make one; the torches nearly gone and no coal to make more; no food for
     * the walk home; too many of them down there. Said aloud ("We turn back: the torches are nearly gone").
     */
    @Nullable
    private static String whyHome(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Party p, long time) {
        if (f.stationTask() != StationTask.CAVE) return "I'd other work to go to";
        if (p.phase == Party.Phase.CAMP) return null;                 // the night's camp: home, if at all, in the morning
        long now = level.getDayTime();
        if (now >= p.turnAt && !CaveTrips.stayOn(level, f, p)) return p.days > 1.0 ? "the days we planned were up" : "it was time to head home";
        if (f.tickCount - e.startedTick > p.days * 24000L + LONGEST) return "we'd been out longer than we meant";
        List<VillageFolkEntity> team = members(level, p);
        if (team.size() < p.startSize) return "we'd lost one of the team";
        int torches = 0, heads = 0, food = 0;
        boolean canMake = false;
        for (VillageFolkEntity m : team) {
            heads++;
            torches += m.countMatching(s -> s.is(Items.TORCH));
            if (CaveCraft.canMakeTorches(m)) canMake = true;
            if (freeSlots(m) < 2) return (m == f ? "my" : m.displayNameCap() + "'s") + " pack was full of what we'd found";
            int meals = m.countMatching(CaveDwellers::food);
            food += meals;
            if (m.getHealth() < m.getMaxHealth() * 0.5F && meals == 0) return m.displayNameCap() + " was hurt, with nothing left to eat";
            if (bestPick(m).isEmpty() && m.countCarried(CaveCraft::blade) == 0 && !CaveCraft.canMakeTool(m)) {
                return m.displayNameCap() + " had no tool left, and nothing to make one";
            }
        }
        if (heads > 0 && torches < TORCHES_LOW * heads && !canMake) return "the torches were nearly gone";
        if (heads > 0 && food < heads && p.days > 1.0) return "the food was running low";
        int foes = level.getEntitiesOfClass(Mob.class, new AABB(f.blockPosition()).inflate(12), x -> x instanceof Enemy && x.isAlive()).size();
        if (foes >= 4 + 2 * heads) return "there were too many of them down there";
        return null;
    }

    /** The cave's cell a spot is in: four blocks a side. */
    static long cell(BlockPos p) {
        return BlockPos.asLong(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2);
    }

    private static int freeSlots(VillageFolkEntity f) {
        int n = 0;
        for (ItemStack s : f.getInventoryItems()) if (s.isEmpty()) n++;
        return n;
    }


    /** A mark to walk home by: every sixteen blocks on the land, every six in a cave. */
    private static void crumbs(ServerLevel level, VillageFolkEntity f, Party p, boolean below) {
        if (p.homeward || p.phase == Party.Phase.LEAVING || !f.onGround() || p.trail.isEmpty()) return;
        BlockPos last = p.trail.get(p.trail.size() - 1);
        double step = below || p.phase == Party.Phase.IN ? 6 : 16;
        if (f.blockPosition().distSqr(last) > step * step) p.trail.add(f.blockPosition().immutable());
    }

    /** The ground round it kept awake as it goes, as a scout's is (and let go by Scouts.release with it). */
    private static void keepAwake(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e) {
        BlockPos here = f.blockPosition();
        if (e.window != null && e.window.distSqr(here) < 16 * 16) return;
        UUID owner = UUID.nameUUIDFromBytes(("mca-scout-" + f.getUUID()).getBytes());     // Scouts.owner's
        if (e.window != null) ChunkLoad.setLoaded(level, owner, e.window, 2, false);
        ChunkLoad.setLoaded(level, owner, here, 2, true);
        e.window = here.immutable();
    }

    // ------------------------------------------------------------------ light

    /**
     * Torches where they count (they cost the town its coal and sticks): along the way in, the leader sets one about
     * every fifteen blocks (no light left from the last), up to twenty in a straight passage, and from nine at a turn
     * or where ways meet, so the way home is plain; and any of the team where it mines or fights when it is dark enough
     * there for monsters to spawn (no block light at all). Never in daylight, never carpeting a cave: a spot lit by
     * anything already (an old torch, lava) is left as it is. What is set stays for the town's later use.
     */
    static void light(ServerLevel level, VillageFolkEntity f, Delve d, Party p, boolean leading) {
        if (f.tickCount - d.torchTick < 20 || !f.onGround()) return;
        BlockPos at = f.blockPosition();
        if (level.getBrightness(LightLayer.SKY, at) >= 8 || f.countMatching(s -> s.is(Items.TORCH)) == 0) return;
        int block = level.getBrightness(LightLayer.BLOCK, at);
        LivingEntity foe = f.getTarget();
        boolean working = d.digging != null || foe != null && foe.isAlive() && foe.distanceToSqr(f) < 8 * 8;
        if (working) {
            if (block > 0) return;                                     // nothing spawns where it works
        } else {
            if (!leading || p.phase == Party.Phase.CAMP || p.phase == Party.Phase.STORE) return;
            boolean below = p.phase == Party.Phase.IN || p.phase == Party.Phase.LEAVING || MineStairs.underground(level, at);
            if (!below) return;
            double since = p.lastTorch == null ? Double.MAX_VALUE : Math.sqrt(p.lastTorch.distSqr(at));
            boolean due = switch (bend(level, f, p, at)) {
                case 2 -> since >= BEND && block <= 4;
                case 0 -> since >= STRAIGHT && block <= 1;
                default -> since >= SPACING - 1 && block <= 0;
            };
            if (!due) return;
        }
        if (placeTorch(level, f, p, at)) d.torchTick = f.tickCount;
    }

    /** How the way goes here: 0 a straight passage (on the line from the last torch), 2 a turn or ways meeting, 1 else. */
    static int bend(ServerLevel level, VillageFolkEntity f, Party p, BlockPos at) {
        if (junction(level, at)) return 2;
        if (p.lastTorch == null) return 1;
        Vec3 move = f.getDeltaMovement();
        double mx = move.x, mz = move.z, ml = Math.sqrt(mx * mx + mz * mz);
        double fx = at.getX() - p.lastTorch.getX(), fz = at.getZ() - p.lastTorch.getZ(), fl = Math.sqrt(fx * fx + fz * fz);
        if (ml < 0.02 || fl < 3) return 1;
        double cos = (mx * fx + mz * fz) / (ml * fl);
        return cos < 0.64 ? 2 : cos > 0.9 ? 0 : 1;
    }

    /** Ways meeting: three or four ways on from here, open three blocks out, and not the open floor of a hall (its
     *  corners open too). */
    static boolean junction(ServerLevel level, BlockPos at) {
        int ways = 0, corners = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            boolean open = true;
            for (int k = 2; k <= 4 && open; k++) open = passable(level, at.relative(dir, k));
            if (open) ways++;
            if (passable(level, at.relative(dir, 2).relative(dir.getClockWise(), 2))) corners++;
        }
        return ways >= 3 && corners <= 1;
    }

    /** Somewhere a folk could stand, a step up or down from this spot. */
    private static boolean passable(ServerLevel level, BlockPos q) {
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos b = q.above(dy);
            if (level.getBlockState(b).isAir() && level.getBlockState(b.above()).isAir() && !level.getBlockState(b.below()).isAir()) return true;
        }
        return false;
    }

    /** A torch out of its pack, set here (on the floor, else on the wall beside it, at its feet or its head). */
    static boolean placeTorch(ServerLevel level, VillageFolkEntity f, Party p, BlockPos at) {
        if (f.countMatching(s -> s.is(Items.TORCH)) == 0) return false;
        BlockPos spot = null;
        BlockState torch = null;
        BlockState floor = Blocks.TORCH.defaultBlockState();
        if (level.getBlockState(at).isAir() && level.getFluidState(at).isEmpty() && floor.canSurvive(level, at)) {
            spot = at;
            torch = floor;
        } else {
            for (BlockPos q : new BlockPos[]{ at.above(), at }) {
                if (!level.getBlockState(q).isAir() || !level.getFluidState(q).isEmpty()) continue;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockState wall = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, dir.getOpposite());
                    if (wall.canSurvive(level, q)) { spot = q; torch = wall; break; }
                }
                if (spot != null) break;
            }
        }
        if (spot == null || f.removeMatching(s -> s.is(Items.TORCH), 1) < 1) return false;
        level.setBlockAndUpdate(spot, torch);
        f.placeSound(spot);
        f.swing(InteractionHand.MAIN_HAND);
        Economy.usedGiven(f, new ItemStack(Items.TORCH), 1);
        p.torches++;
        p.lastTorch = spot.immutable();
        return true;
    }

    /** On fire (lava, a blaze of it): the water bucket it carries poured at its feet, and picked up again. */
    static boolean douse(ServerLevel level, VillageFolkEntity f, Delve d) {
        if (d.water != null && f.tickCount - d.waterTick > 10) {
            FluidState fl = level.getFluidState(d.water);
            if (fl.is(Fluids.WATER) && fl.isSource() && f.removeMatching(s -> s.is(Items.BUCKET), 1) == 1) {
                level.setBlockAndUpdate(d.water, Blocks.AIR.defaultBlockState());
                f.insertItem(new ItemStack(Items.WATER_BUCKET));
            }
            d.water = null;
        }
        if (!f.isOnFire() || d.water != null || f.countMatching(s -> s.is(Items.WATER_BUCKET)) == 0) return false;
        BlockPos at = f.blockPosition();
        if (!level.getBlockState(at).canBeReplaced()) return false;
        f.removeMatching(s -> s.is(Items.WATER_BUCKET), 1);
        f.insertItem(new ItemStack(Items.BUCKET));
        level.setBlockAndUpdate(at, Blocks.WATER.defaultBlockState());
        d.water = at.immutable();
        d.waterTick = f.tickCount;
        f.clearFire();
        FolkTalk.speak(f, "Water! — that's better.");
        return true;
    }

    /**
     * Lava or running water spilling where the team goes (round its feet, the floor's edge to its head): walled off with
     * a block of its cobblestone, a block at a time, twenty-four a day at most. Still water under the floor is left be.
     */
    static boolean wallOff(ServerLevel level, VillageFolkEntity f, Delve d, Party p) {
        if (f.tickCount - d.wallTick < 20 || p.walled >= 24) return false;
        d.wallTick = f.tickCount;
        BlockPos feet = f.blockPosition();
        if (inTown(p.village, feet)) return false;
        for (BlockPos q : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 1, 2))) {
            FluidState fl = level.getFluidState(q);
            if (fl.isEmpty() || q.equals(feet) || q.equals(feet.above())) continue;
            boolean lava = fl.is(net.minecraft.tags.FluidTags.LAVA);
            if (!lava && (fl.isSource() || q.getY() < feet.getY())) continue;
            if (!level.getBlockState(q).canBeReplaced()) continue;
            if (f.removeMatching(s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE), 1) < 1) return false;
            BlockPos at = q.immutable();
            level.setBlockAndUpdate(at, Blocks.COBBLESTONE.defaultBlockState());
            f.placeSound(at);
            f.swing(InteractionHand.MAIN_HAND);
            p.walled++;
            if (f.tickCount - d.spokeTick > 200) {
                d.spokeTick = f.tickCount;
                FolkTalk.speak(f, lava ? FolkTalk.pick(f.getRandom(), "Lava! Walling it off — keep back.", "Mind the lava! There, it's blocked.")
                    : "Water coming through. Blocked it.");
            }
            return true;
        }
        return false;
    }

    /** A hole in the floor ahead of the leader (two deep or more, the floor going on beyond it): patched with a block
     *  of its cobblestone so nobody falls in. */
    static boolean patch(ServerLevel level, VillageFolkEntity f, Delve d, Party p, boolean leading) {
        if (!leading || f.tickCount - d.patchTick < 10 || !f.onGround() || p.patched >= 24) return false;
        d.patchTick = f.tickCount;
        Vec3 m = f.getDeltaMovement();
        if (m.horizontalDistanceSqr() < 0.0004) return false;
        BlockPos feet = f.blockPosition();
        if (!MineStairs.underground(level, feet) || inTown(p.village, feet)) return false;
        Direction dir = Direction.getNearest(m.x, 0, m.z);
        BlockPos ahead = feet.relative(dir), floor = ahead.below();
        if (!level.getBlockState(ahead).isAir() || !level.getBlockState(floor).isAir() || !level.getBlockState(floor.below()).isAir()) return false;
        if (!level.getFluidState(floor).isEmpty() || level.getBlockState(floor.relative(dir)).isAir()) return false;
        if (f.removeMatching(s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE), 1) < 1) return false;
        level.setBlockAndUpdate(floor, Blocks.COBBLESTONE.defaultBlockState());
        f.placeSound(floor);
        f.swing(InteractionHand.MAIN_HAND);
        p.patched++;
        if (f.tickCount - d.spokeTick > 200) {
            d.spokeTick = f.tickCount;
            FolkTalk.speak(f, "Mind the hole — I've patched it.");
        }
        return true;
    }

    /**
     * Never stuck: one of the team that has had somewhere to go and not got there for ten seconds cuts itself a step
     * out of the rock toward it (SearchParties.cutAStep), four times a trip at most; else a fresh look at the way.
     */
    static boolean unstick(ServerLevel level, VillageFolkEntity f, Delve d, Party p, VillageFolkEntity lead) {
        BlockPos at = f.blockPosition();
        if (d.stillAt == null || at.distSqr(d.stillAt) > 2) {
            d.stillAt = at.immutable();
            d.stillTick = f.tickCount;
            return false;
        }
        if (f.getNavigation().isDone() || d.digging != null || d.table != null || f.tickCount - d.stillTick < 200) return false;
        d.stillTick = f.tickCount;
        Villages.Village v = Villages.get(p.village);
        Path path = f.getNavigation().getPath();
        BlockPos toward = path != null && path.getEndNode() != null ? path.getEndNode().asBlockPos() : lead.blockPosition();
        f.getNavigation().stop();
        if (v == null || d.stepsCut >= 4 || inTown(p.village, at)) return false;
        if (!SearchParties.cutAStep(level, v, f, toward)) return false;
        d.stepsCut++;
        LOG.info("[MCA-CAVES] {} was stuck at {}: cut a step toward {}", f.displayNameCap(), at.toShortString(), toward.toShortString());
        return true;
    }

    // ------------------------------------------------------------------ fighting together

    /**
     * For the auto-target goal (AssistantEntity.shouldAutoAttack) and its own look about: may this cave dweller take
     * this one on now? At home, anything near it. On the team's day out, its share of the fight: against a group of
     * them, everybody; against one, the nearest of the team takes it while the others work on, and another goes in if
     * that one is hurt or it is coming at it.
     */
    public static boolean mayTakeOn(AssistantEntity a, @Nullable LivingEntity target) {
        if (!(a instanceof VillageFolkEntity f) || target == null || !(f.level() instanceof ServerLevel level)) return true;
        Party p = partyOf(f);
        if (p == null || p.members.size() <= 1) return true;
        List<VillageFolkEntity> team = members(level, p);
        if (team.size() <= 1) return true;
        int foes = 0;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(target.blockPosition()).inflate(10), x -> x instanceof Enemy && x.isAlive())) foes++;
        if (foes >= 2) return true;
        if (target instanceof Mob mob && mob.getTarget() == f) return true;
        VillageFolkEntity nearest = null;
        double near = Double.MAX_VALUE;
        for (VillageFolkEntity m : team) {
            double dd = m.distanceToSqr(target);
            if (dd < near) { near = dd; nearest = m; }
        }
        if (nearest == f) return true;
        return nearest != null && (nearest.getHealth() < nearest.getMaxHealth() * 0.5F || nearest.getTarget() != target);
    }

    /**
     * Something hostile: what it is fighting is left to the guards' melee (MeleeAttackGoal), its sword in hand; what
     * comes near it is taken on as its share (mayTakeOn), to clear the way; not a creeper (it backs off from that,
     * toward the others), nor anything while it is too hurt to fight. A warden turns the whole team for home at once.
     */
    static boolean fight(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d, Party p, VillageFolkEntity lead) {
        LivingEntity t = f.getTarget();
        if (t != null && t.isAlive() && t.distanceToSqr(f) < 24 * 24) {
            if (d.foe == null || !d.foe.equals(t.getUUID())) {
                d.foe = t.getUUID();
                d.foeTick = f.tickCount;
            } else if (f.tickCount - d.foeTick > 600) {
                // Half a minute and it cannot get at it (a skeleton up on a ledge): it gives it up, and the team goes.
                if (!p.homeward) turnBack(level, f, p, "we couldn't get at what was down there");
                f.setTarget(null);
                d.foe = null;
                return false;
            }
            f.equipBestWeapon();
            e.gainedTick = f.tickCount;
            return true;
        }
        if (d.foe != null) {
            Entity was = level.getEntity(d.foe);
            if (was == null || !was.isAlive()) {
                p.slain++;
                f.awardXp(2);
                if (f.tickCount - d.spokeTick > 200) {
                    d.spokeTick = f.tickCount;
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "That's one less down here.", "Down it goes. On we go.", "Cleared."));
                }
            }
            d.foe = null;
            if (t != null && !t.isAlive()) f.setTarget(null);
        }
        if (f.isRetreating()) return true;
        for (Warden w : level.getEntitiesOfClass(Warden.class, new AABB(f.blockPosition()).inflate(24), Warden::isAlive)) {
            if (!p.homeward) {
                p.found.add(new Find(Kind.LAVA, "a warden in the deep dark", w.blockPosition(), p.day, f.displayNameCap(), depth(level, w.blockPosition()), 0));
                turnBack(level, f, p, "there was a warden down there");
            }
            break;
        }
        Mob m = foe(level, f, 8);
        if (m == null) return false;
        if (m instanceof Creeper || f.shouldDisengage()) {
            BlockPos back = lead != f ? lead.blockPosition()
                : p.trail.size() > 1 ? p.trail.get(p.trail.size() - 2) : BlockPos.containing(f.position().add(f.position().subtract(m.position()).normalize().scale(6)));
            f.getNavigation().moveTo(back.getX() + 0.5, back.getY(), back.getZ() + 0.5, 1.3D);
            if (f.tickCount - d.spokeTick > 200) {
                d.spokeTick = f.tickCount;
                FolkTalk.speak(f, m instanceof Creeper ? "Creeper! Back, everybody!" : "Too many of them. Back!");
            }
            return true;
        }
        if (!mayTakeOn(f, m)) return false;                  // another of the team has it: it goes on with its work
        f.setTarget(m);
        f.equipBestWeapon();
        d.foe = m.getUUID();
        d.foeTick = f.tickCount;
        if (f.tickCount - d.spokeTick > 200) {
            d.spokeTick = f.tickCount;
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Something down here. I've got it!", "Not past me, you don't.", "Cover me — going in!"));
        }
        return true;
    }

    /** The nearest hostile thing it can see, within so many blocks. */
    @Nullable
    private static Mob foe(ServerLevel level, VillageFolkEntity f, int r) {
        Mob best = null;
        double near = (double) r * r;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(f.blockPosition()).inflate(r), x -> x instanceof Enemy && x.isAlive())) {
            if (m instanceof Warden || m instanceof net.minecraft.world.entity.monster.EnderMan) continue;
            if (m.isInWater() && !f.isInWater()) continue;
            double dd = m.distanceToSqr(f);
            if (dd < near && f.hasLineOfSight(m)) { near = dd; best = m; }
        }
        return best;
    }

    // ------------------------------------------------------------------ keeping together

    /**
     * One of the team (not the leader), walking in order: behind the one ahead of it (the leader first), a couple of
     * blocks back, and quicker when it has dropped behind the leader.
     */
    static boolean follow(ServerLevel level, VillageFolkEntity f, Delve d, VillageFolkEntity lead) {
        if (lead == f) return true;
        Party p = d.party;
        VillageFolkEntity ahead = ahead(level, p, f, lead);
        double toAhead = f.distanceToSqr(ahead), toLead = f.distanceToSqr(lead);
        double gap = ahead == lead ? 2.5 : 2.0;
        if (toAhead > (gap + 1.0) * (gap + 1.0) || toLead > CLOSE * CLOSE) {
            if (f.getNavigation().isDone() || f.tickCount - d.followTick > 15) {
                f.getNavigation().moveTo(ahead, toLead > 10 * 10 ? 1.3D : toLead > CLOSE * CLOSE ? 1.2D : 1.05D);
                d.followTick = f.tickCount;
            }
        } else if (toAhead < gap * gap) {
            f.getNavigation().stop();
        }
        f.hobbyNow = p.homeward ? "on the way home from the caves with the team" : "with the cave dwellers, behind " + ahead.displayNameCap();
        return true;
    }

    /** The one it walks behind: the next of the team ahead of it in the order they set out in, the leader first. */
    static VillageFolkEntity ahead(ServerLevel level, Party p, VillageFolkEntity f, VillageFolkEntity lead) {
        VillageFolkEntity prev = lead;
        for (UUID u : p.members) {
            if (u.equals(f.getUUID())) return prev;
            if (u.equals(lead.getUUID())) continue;
            if (level.getEntity(u) instanceof VillageFolkEntity m && m.isAlive() && onTrip(m, p)) prev = m;
        }
        return lead;
    }

    /**
     * The leader waits for anybody fallen behind (further than WAIT), and after a while goes back for it. One that
     * cannot be got to in a minute (fallen down a shaft, cutting its own way up) is left to come home on its own.
     * True while it is waiting.
     */
    static boolean waiting(ServerLevel level, VillageFolkEntity lead, Party p) {
        long now = level.getGameTime();
        VillageFolkEntity behind = null;
        double far = WAIT * WAIT;
        for (VillageFolkEntity m : members(level, p)) {
            if (m == lead) continue;
            double dd = m.distanceToSqr(lead);
            if (dd > far) { far = dd; behind = m; }
        }
        if (behind == null) {
            p.waitingFor = null;
            p.waitSince = -1;
            return false;
        }
        if (!behind.getUUID().equals(p.waitingFor)) {
            p.waitingFor = behind.getUUID();
            p.waitSince = now;
        }
        if (now - p.waitSince > 1200) {
            separate(level, behind, p);
            return false;
        }
        if (now - p.waitSince > 300) {
            if (lead.getNavigation().isDone()) lead.getNavigation().moveTo(behind, 1.0D);    // back for it
        } else {
            lead.getNavigation().stop();
            lead.getLookControl().setLookAt(behind, 30.0F, 30.0F);
        }
        lead.hobbyNow = "waiting for " + behind.displayNameCap();
        return true;
    }

    /** One the team could not get back to: it comes home on its own (its marks the team's), what it carries with it. */
    static void separate(ServerLevel level, VillageFolkEntity m, Party p) {
        p.members.remove(m.getUUID());
        Scouts.Expedition e = m.expedition();
        if (e == null) return;
        Party alone = new Party(p.village, p.day);
        alone.members.add(m.getUUID());
        alone.leader = m.getUUID();
        alone.trail.addAll(p.trail);
        alone.homeward = true;
        alone.crumb = alone.trail.size() - 1;
        alone.why = "it got separated from the team";
        alone.reported = true;
        e.delve = new Delve(alone);
        e.returning = true;
        FolkTalk.speak(m, "I've lost the others! I'll make my own way home.");
        LOG.info("[MCA-CAVES] {} separated from the team at {}", m.displayNameCap(), m.blockPosition().toShortString());
    }

    // ------------------------------------------------------------------ the leader, and the others

    /** The leader's day: out to the caves, in, vein after vein, chest after chest, and on to the next cave. */
    static boolean leadOn(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d, Party p) {
        long now = level.getGameTime();
        if (p.phase == Party.Phase.OUT) {
            if (waiting(level, f, p)) return true;
            return walkOut(level, f, e, p);
        }
        if (p.phase == Party.Phase.LEAVING) {
            if (waiting(level, f, p)) return true;
            return leave(level, f, e, p);
        }
        if (p.chest != null || p.cart != null) return loot(level, f, e, p);
        if (p.spawnerToLight != null && p.work.isEmpty()) return lightSpawner(level, f, e, p);
        if (!p.work.isEmpty()) {
            if (now - p.workTick > 400) {
                finishVein(level, p, "stalled");
                return true;
            }
            BlockPos b = claim(level, f, p, f);
            if (b != null) return dig(level, f, e, d, p, b);
            f.getNavigation().stop();
            f.hobbyNow = "seeing to the " + p.veinOf + " vein";
            return true;
        }
        if (p.veinOf != null) finishVein(level, p, null);
        if (waiting(level, f, p)) return true;
        Vein v = nextVein(level, f, p);
        if (v != null) return toVein(level, f, e, p, v);
        return explore(level, f, e, p);
    }

    /** One of the others: a block of the vein in hand to dig, if there is one near the leader for its pick; else at the
     *  leader's side. */
    static boolean help(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d, Party p, VillageFolkEntity lead) {
        if (p.phase == Party.Phase.IN && !p.work.isEmpty() && f.distanceToSqr(lead) <= 12 * 12) {
            BlockPos b = claim(level, f, p, lead);
            if (b != null) return dig(level, f, e, d, p, b);
        }
        release(f, p);
        return follow(level, f, d, lead);
    }

    /** Its claim on a block of the vein let go. */
    private static void release(VillageFolkEntity f, Party p) {
        p.claims.values().removeIf(u -> u.equals(f.getUUID()));
    }

    /**
     * A block of the vein in hand for this one to dig: its own claim if it still stands, else the nearest block nobody
     * has claimed that shows (the way in is cut in order), that its own pick will take, near the leader. Claimed: no
     * two dig the same block.
     */
    @Nullable
    static BlockPos claim(ServerLevel level, VillageFolkEntity f, Party p, VillageFolkEntity lead) {
        for (Map.Entry<Long, UUID> en : p.claims.entrySet()) {
            if (!en.getValue().equals(f.getUUID())) continue;
            BlockPos b = BlockPos.of(en.getKey());
            if (p.work.contains(b)) return b;
        }
        release(f, p);
        ItemStack pick = bestPick(f);
        if (pick.isEmpty()) return null;
        BlockPos best = null;
        double near = Double.MAX_VALUE;
        for (BlockPos b : p.work) {
            if (p.claims.containsKey(b.asLong()) || !exposed(level, b)) continue;
            if (!pick.isCorrectToolForDrops(level.getBlockState(b))) continue;
            if (b.distSqr(lead.blockPosition()) > 12 * 12) continue;
            double dd = b.distSqr(f.blockPosition());
            if (dd < near) { near = dd; best = b; }
        }
        if (best != null) p.claims.put(best.asLong(), f.getUUID());
        return best;
    }

    // ------------------------------------------------------------------ out over the land, and in

    /** Out to the caves: in stages over the land as a scout goes, then by the path-finder into the cave it saw. */
    static boolean walkOut(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Party p) {
        BlockPos feet = f.blockPosition();
        int reach = Villages.townReach(p.village);
        if (MineStairs.underground(level, feet) && depth(level, feet) >= 4 && !p.visited.contains(cell(feet))
                && Scouts.flat(feet, e.home) > (double) reach * reach) {
            enter(level, f, e, p);
            return true;
        }
        if (p.cave != null && feet.distSqr(p.cave) <= 40 * 40) {
            // Near enough to see the way in: the path-finder's way, all the way down.
            if (feet.distSqr(p.cave) <= 2.5 * 2.5) {
                enter(level, f, e, p);
                return true;
            }
            if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 100) {
                Path path = f.getNavigation().createPath(p.cave, 1);
                e.walkTick = f.tickCount;
                if (path != null && path.canReach()) {
                    f.getNavigation().moveTo(path, 1.0D);
                    p.noWay = 0;
                } else if (++p.noWay > 4) {
                    p.passedCells.add(cell(p.cave));
                    f.brain("no way down to the cave from here");
                    p.cave = null;
                    p.noWay = 0;
                }
            }
            f.hobbyNow = "making for a way into the rock";
            return true;
        }
        BlockPos dest = p.cave != null ? p.cave : e.target;
        double flat = Scouts.flat(feet, dest);
        if (p.cave == null && flat <= 10 * 10) {
            turnBack(level, f, p, "there was no way under the rock out " + e.heading);
            return true;
        }
        if (flat < e.best - 2.0) {
            e.best = flat;
            e.gainedTick = f.tickCount;
        } else if (f.tickCount - e.gainedTick > 300) {
            e.detour++;
            e.waypoint = null;
            e.gainedTick = f.tickCount;
            if (e.detour > 5) {
                turnBack(level, f, p, "the way " + e.heading + " was blocked");
                return true;
            }
        }
        if (e.waypoint == null || Scouts.flat(feet, e.waypoint) <= 3 * 3) {
            e.waypoint = Scouts.chooseWaypoint(level, f, dest, e.detour);
            e.walkTick = -1000;
        }
        if (e.waypoint != null && (f.getNavigation().isDone() || f.tickCount - e.walkTick > 80)) {
            f.walkTo(e.waypoint, 1.0D);
            e.walkTick = f.tickCount;
        }
        f.hobbyNow = "leading the cave dwellers to the caves " + e.heading;
        return true;
    }

    /** In under the rock: the cave measured (how deep, how big, a ravine or a cave), noted, and its list of veins opened. */
    static void enter(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Party p) {
        p.phase = Party.Phase.IN;
        BlockPos at = f.blockPosition();
        p.mouth = at.immutable();
        p.target = null;
        p.frontierFails = 0;
        p.explored = false;
        e.waypoint = null;
        if (p.trail.isEmpty() || p.trail.get(p.trail.size() - 1).distSqr(at) > 4) p.trail.add(at.immutable());
        p.mouthCrumb = p.trail.size() - 1;
        int[] size = measure(level, at);
        boolean ravine = size[1] >= 12;
        int depth = depth(level, at);
        // The report's spot for the cave: one it has already (near enough), or here.
        BlockPos anchor = at.immutable();
        for (Find x : report(p.village)) {
            if ((x.kind() == Kind.CAVE || x.kind() == Kind.RAVINE) && x.at().distSqr(at) <= 20 * 20) { anchor = x.at(); break; }
        }
        if (p.cave != null && anchor.equals(at)) {
            for (Find x : report(p.village)) {
                if ((x.kind() == Kind.CAVE || x.kind() == Kind.RAVINE) && x.at().distSqr(p.cave) <= 20 * 20) { anchor = x.at(); break; }
            }
        }
        p.caveKey = anchor;
        String label = ravine ? "a ravine" : size[0] >= 600 ? "a great cave" : size[0] >= 200 ? "a big cave" : "a cave";
        note(f, p, new Find(ravine ? Kind.RAVINE : Kind.CAVE, label, anchor, p.day, f.displayNameCap(), depth, size[0]),
            ravine ? "A ravine! Look how far down it goes." : FolkTalk.pick(f.getRandom(), "In we go. Mind your heads.", "Dark as a cellar. Torches out."));
        Ledger.note(p.village, "caves.went/" + key(anchor), Long.toString(p.day));
        if (p.cave == null) p.cave = at.immutable();
        p.caves++;
        f.awardXp(4);
        CaveTrips.keep(p);                                       // the town knows which cave the team is in
    }

    /** How deep under the surface a spot is. */
    static int depth(ServerLevel level, BlockPos at) {
        return Math.max(0, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) - at.getY());
    }

    /** The open blocks round a spot (a box twelve across, nine high) and the tallest run of open air over it. */
    static int[] measure(ServerLevel level, BlockPos at) {
        int open = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                if (!loaded(level, at.getX() + dx, at.getZ() + dz)) continue;
                for (int dy = -2; dy <= 6; dy++) {
                    m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (level.getBlockState(m).isAir()) open++;
                }
            }
        }
        int tall = 0;
        for (int dy = 0; dy < 40 && level.getBlockState(m.set(at.getX(), at.getY() + dy, at.getZ())).isAir(); dy++) tall++;
        return new int[]{ open, tall };
    }

    private static boolean loaded(ServerLevel level, int x, int z) {
        return level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
    }

    /** Somewhere to stand: two open blocks over a solid floor, dry. */
    static boolean standable(ServerLevel level, BlockPos p) {
        BlockState at = level.getBlockState(p), head = level.getBlockState(p.above()), floor = level.getBlockState(p.below());
        if (!at.isAir() && !at.canBeReplaced() || !head.isAir() && !head.canBeReplaced()) return false;
        if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.above()).isEmpty()) return false;
        if (at.is(Blocks.POWDER_SNOW) || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.POINTED_DRIPSTONE)) return false;
        return floor.isFaceSturdy(level, p.below(), Direction.UP);
    }

    static boolean lavaNear(ServerLevel level, BlockPos p, int r) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-r, -1, -r), p.offset(r, 1, r))) {
            if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) return true;
        }
        return false;
    }

    /**
     * A way under the rock near it, that it can walk to: open ground with rock over it, the deepest and most open
     * of what it sees first, never a cave the team has been through today, and only where the path-finder says it can
     * get to. Null for none.
     */
    @Nullable
    static BlockPos cavern(ServerLevel level, VillageFolkEntity f, Party p, int r) {
        BlockPos feet = f.blockPosition();
        net.minecraft.util.RandomSource rnd = f.getRandom();
        int floor = level.getMinBuildHeight() + ABOVE_BOTTOM;
        List<BlockPos> found = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int x = feet.getX() + rnd.nextInt(2 * r + 1) - r, z = feet.getZ() + rnd.nextInt(2 * r + 1) - r;
            if (!loaded(level, x, z)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = Math.min(top - 4, feet.getY() + 4); y >= Math.max(floor, feet.getY() - 28); y--) {
                BlockPos q = new BlockPos(x, y, z);
                if (!standable(level, q)) continue;
                long c = cell(q);
                if (!MineStairs.underground(level, q) || p.passedCells.contains(c) || p.visited.contains(c) || lavaNear(level, q, 2)) continue;
                found.add(q);
                break;
            }
        }
        found.sort(Comparator.comparingDouble(q -> (double) q.getY() - open(level, q) * 2.0));
        int tries = 0;
        for (BlockPos q : found) {
            if (tries++ >= 3) break;
            Path path = f.getNavigation().createPath(q, 1);
            if (path != null && path.canReach()) return q;
            p.passedCells.add(cell(q));
        }
        return null;
    }

    private static int open(ServerLevel level, BlockPos p) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, 0, -2), p.offset(2, 2, 2))) if (level.getBlockState(q).isAir()) n++;
        return n;
    }

    /** In the cave with no vein to go to: on to somewhere in it not yet seen, further in; all seen, the cave is done. */
    static boolean explore(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Party p) {
        BlockPos feet = f.blockPosition();
        long now = level.getGameTime();
        if (p.target != null) {
            double dist = feet.distSqr(p.target);
            if (dist <= 2.5 * 2.5) {
                p.target = null;
            } else {
                if (dist < p.targetBest - 1.0) {
                    p.targetBest = dist;
                    p.targetTick = now;
                } else if (now - p.targetTick > 160) {
                    p.passedCells.add(cell(p.target));
                    p.target = null;
                    return true;
                }
                if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 80) {
                    f.getNavigation().moveTo(p.target.getX() + 0.5, p.target.getY(), p.target.getZ() + 0.5, 1.0D);
                    e.walkTick = f.tickCount;
                }
                f.hobbyNow = "leading the cave dwellers through the caves " + e.heading;
                return true;
            }
        }
        if (now < p.lookTick) return true;                       // (a look takes the path-finder a while: not every step)
        BlockPos next = frontier(level, f, e, p);
        if (next == null) {
            p.lookTick = now + 20;
            // Nowhere new it can get to, three looks running with a look about between each: it has been all through it.
            if (now - p.failTick >= 40) {
                p.failTick = now;
                if (++p.frontierFails >= 3) caveDone(level, f, e, p);
            }
            return true;
        }
        p.frontierFails = 0;
        p.target = next;
        p.targetBest = Double.MAX_VALUE;
        p.targetTick = now;
        return true;
    }

    /** Somewhere in the cave the team has not been, that it can walk to: further from the way in, and a little deeper, first. */
    @Nullable
    static BlockPos frontier(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Party p) {
        BlockPos feet = f.blockPosition();
        net.minecraft.util.RandomSource rnd = f.getRandom();
        int floor = level.getMinBuildHeight() + ABOVE_BOTTOM;
        BlockPos mouth = p.mouth != null ? p.mouth : feet;
        List<BlockPos> found = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            int dx = rnd.nextInt(29) - 14, dz = rnd.nextInt(29) - 14;
            if (dx * dx + dz * dz < 9) continue;
            int x = feet.getX() + dx, z = feet.getZ() + dz;
            if (!loaded(level, x, z)) continue;
            for (int y = feet.getY() + 5; y >= feet.getY() - 8 && y > floor; y--) {
                BlockPos q = new BlockPos(x, y, z);
                if (!standable(level, q)) continue;
                long c = cell(q);
                if (!MineStairs.underground(level, q) || p.visited.contains(c) || p.passedCells.contains(c)) break;
                if (q.distSqr(mouth) > (double) DEEPEST * DEEPEST || lavaNear(level, q, 2)) break;
                found.add(q);
                break;
            }
        }
        found.sort(Comparator.comparingDouble(q -> -(q.distSqr(mouth) + (feet.getY() - q.getY()) * 6.0)));
        int tries = 0;
        for (BlockPos q : found) {
            if (tries++ >= 4) break;
            Path path = f.getNavigation().createPath(q, 1);
            if (path != null && path.canReach()) {
                f.getNavigation().moveTo(path, 1.0D);
                e.walkTick = f.tickCount;
                return q;
            }
            p.passedCells.add(cell(q));
        }
        return null;
    }

    /**
     * Gone all through the cave and every vein on its list taken that the team's picks allow: worked out (the waiting
     * ones stay listed). On to the next cave while the day allows it; home otherwise.
     */
    static void caveDone(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Party p) {
        p.explored = true;
        BlockPos key = p.caveKey != null ? p.caveKey : p.cave;
        int todo = 0, waiting = 0;
        if (key != null) {
            for (Vein v : veins(p.village, key)) {
                if ("todo".equals(v.state())) todo++;
                if ("waiting".equals(v.state())) waiting++;
            }
            // What is still to do here could not be got to from the cave: it is left, and the cave is done.
            if (todo > 0) {
                List<Vein> list = veins(p.village, key);
                list.replaceAll(v -> "todo".equals(v.state()) ? v.as("left") : v);
                saveVeins(p.village, key, list);
            }
            markDone(p.village, key);
        }
        FolkTalk.speak(f, waiting > 0 ? "That's this one worked out — all but the " + waitingOres(p) + ". We'll be back with a better pick."
            : "That's this cave worked out. Every vein in it.");
        LOG.info("[MCA-CAVES] the team of {} worked out the cave at {} ({} waiting, {} left)", Villages.name(p.village),
            key == null ? "?" : key.toShortString(), waiting, todo);
        if (level.getDayTime() < p.turnAt - 2000 && p.caves < 3 && p.mouthCrumb > 0) {
            p.phase = Party.Phase.LEAVING;
            p.crumb = p.trail.size() - 1;
            p.target = null;
            e.best = Double.MAX_VALUE;
            e.gainedTick = f.tickCount;
            FolkTalk.speak(f, "On to the next one. This way, everybody.");
        } else {
            turnBack(level, f, p, "the cave was worked out");
        }
    }

    private static String waitingOres(Party p) {
        Set<String> ores = new java.util.TreeSet<>();
        BlockPos key = p.caveKey != null ? p.caveKey : p.cave;
        if (key != null) for (Vein v : veins(p.village, key)) if ("waiting".equals(v.state())) ores.add(v.ore());
        return ores.isEmpty() ? "hard stuff" : String.join(" and ", ores);
    }

    /** Back out of a worked-out cave along the marks to where it went in, then out over the land for the next. */
    static boolean leave(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Party p) {
        BlockPos feet = f.blockPosition();
        int outside = Math.max(0, p.mouthCrumb - 1);
        if (p.crumb < outside) p.crumb = outside;
        BlockPos dest = p.trail.get(Math.min(p.crumb, p.trail.size() - 1));
        if (feet.distSqr(dest) <= 3 * 3) {
            if (p.crumb <= outside) {
                // Out: the next cave, the report's or the first the leader sees on the way it was going.
                p.phase = Party.Phase.OUT;
                BlockPos was = p.caveKey;
                p.cave = knownCave(level, f, Villages.get(p.village), range(p.village), p.day, was);
                p.mouth = null;
                p.caveKey = null;
                p.work.clear();
                p.claims.clear();
                p.veinOf = null;
                e.best = Double.MAX_VALUE;
                e.gainedTick = f.tickCount;
                e.detour = 0;
                e.waypoint = null;
                return true;
            }
            p.crumb--;
            return true;
        }
        if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 60) {
            f.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0D);
            e.walkTick = f.tickCount;
        }
        f.hobbyNow = "leading the cave dwellers out to the next cave";
        return true;
    }

    // ------------------------------------------------------------------ every vein

    /** What it can see from here: ore in and behind the walls, old chests and carts, spawners, what was built, lava. */
    static void survey(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Party p) {
        BlockPos at = f.blockPosition();
        int reach = Villages.townReach(p.village);
        boolean out = Scouts.flat(at, e.home) > (double) (reach + 8) * (reach + 8);
        if (p.phase == Party.Phase.OUT && p.cave == null && !p.homeward && out) {
            BlockPos c = cavern(level, f, p, 20);
            if (c != null) {
                p.cave = c;
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A way down into the rock! This way, everybody.", "There — a cave mouth. Torches ready."));
                LOG.info("[MCA-CAVES] {} found a way in at {}", f.displayNameCap(), c.toShortString());
            }
        }
        if (!out) return;
        if (p.phase == Party.Phase.IN && !p.homeward) scan(level, f, p);
        containers(level, f, p);
        structures(level, f, p);
        if (p.phase == Party.Phase.IN || MineStairs.underground(level, at)) lava(level, f, p);
    }

    /** The ore it can name, by its block; null for anything else. */
    @Nullable
    public static String oreOf(BlockState st) {
        if (st.is(BlockTags.DIAMOND_ORES)) return "diamond";
        if (st.is(BlockTags.EMERALD_ORES)) return "emerald";
        if (st.is(BlockTags.GOLD_ORES)) return "gold";
        if (st.is(BlockTags.IRON_ORES)) return "iron";
        if (st.is(BlockTags.REDSTONE_ORES)) return "redstone";
        if (st.is(BlockTags.LAPIS_ORES)) return "lapis";
        if (st.is(BlockTags.COPPER_ORES)) return "copper";
        if (st.is(BlockTags.COAL_ORES)) return "coal";
        if (st.is(Blocks.OBSIDIAN)) return "obsidian";
        if (st.is(Blocks.AMETHYST_CLUSTER)) return "amethyst";
        return null;
    }

    private static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction dir : Direction.values()) {
            BlockState s = level.getBlockState(p.relative(dir));
            if (s.isAir() || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH)) return true;
        }
        return false;
    }

    /** Is there lava or water against this block (that would pour into the hole)? */
    static boolean wetBeside(ServerLevel level, BlockPos p) {
        for (Direction dir : Direction.values()) if (!level.getFluidState(p.relative(dir)).isEmpty()) return true;
        return false;
    }

    /**
     * How a block of ore can be got at from the cave: the fewest blocks out to the cave's open air (one: it shows; two
     * to four: one to three of rock behind the wall or the roof; a floor's ore one block under at most, so the pit is no
     * deeper than a step), every block between rock the world made and dry. {steps, the way out as a Direction's
     * ordinal}, or null for none.
     */
    @Nullable
    static int[] access(ServerLevel level, BlockPos b) {
        int[] best = null;
        for (Direction dir : Direction.values()) {
            int most = (dir == Direction.UP ? BEHIND_FLOOR : BEHIND) + 1;
            for (int k = 1; k <= most; k++) {
                BlockPos q = b.relative(dir, k);
                BlockState s = level.getBlockState(q);
                if (s.isAir() || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH)) {
                    if (!level.canSeeSky(q) && (best == null || k < best[0])) best = new int[]{ k, dir.ordinal() };
                    break;
                }
                if (!MineStairs.ground(s) || wetBeside(level, q)) break;
            }
        }
        return best;
    }

    /**
     * The leader's look through the walls, the floor and the roof round it: every vein showing, or up to three blocks
     * behind the cave's face, onto the cave's list (new ones "todo", or "waiting" when none of the team's picks will
     * take it; a waiting one the team now has the pick for back to "todo"), and into the report.
     */
    static void scan(ServerLevel level, VillageFolkEntity f, Party p) {
        BlockPos key = p.caveKey;
        if (key == null) return;
        BlockPos at = f.blockPosition();
        List<Vein> list = veins(p.village, key);
        List<ItemStack> picks = picks(level, p);
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Vein v = list.get(i);
            BlockState st = oreState(v.ore());
            if ("waiting".equals(v.state()) && st != null && takes(picks, st)) { list.set(i, v.as("todo")); changed = true; }
            else if ("todo".equals(v.state()) && st != null && !takes(picks, st)) { list.set(i, v.as("waiting")); changed = true; }
        }
        Set<Long> seen = new HashSet<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -5; dy <= 6; dy++) {
            for (int dx = -SCAN; dx <= SCAN; dx++) {
                for (int dz = -SCAN; dz <= SCAN; dz++) {
                    m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (seen.contains(m.asLong())) continue;
                    BlockState st = level.getBlockState(m);
                    String ore = oreOf(st);
                    if (ore == null) continue;
                    BlockPos b0 = m.immutable();
                    List<BlockPos> blocks = flood(level, b0, ore);
                    for (BlockPos q : blocks) seen.add(q.asLong());
                    int[] best = null;
                    BlockPos via = null;
                    for (BlockPos q : blocks) {
                        int[] a = access(level, q);
                        if (a != null && (best == null || a[0] < best[0])) { best = a; via = q; }
                    }
                    if (best == null) continue;                                   // nothing of it within reach of the cave
                    Vein known = null;
                    for (Vein v : list) {
                        if (!v.ore().equals(ore)) continue;
                        for (BlockPos q : blocks) if (q.distSqr(v.at()) <= 4) { known = v; break; }
                        if (known != null) break;
                    }
                    if (known != null) continue;
                    String state = takes(picks, st) ? "todo" : "waiting";
                    list.add(new Vein(ore, via, blocks.size(), best[0] - 1, state));
                    changed = true;
                    note(f, p, new Find(Kind.VEIN, ore, via, p.day, f.displayNameCap(), blocks.size(), 0), null);
                    callOut(level, p, f, via, ore, best[0] - 1);
                    if ("waiting".equals(state) && p.left.add(ore)) {
                        FolkTalk.speak(f, capital(ore) + " — but none of our picks will take it. It goes on the list for when we've a better one.");
                    }
                }
            }
        }
        if (changed) saveVeins(p.village, key, list);
    }

    /** A vein called out by whichever of the team is nearest it ("Iron here!"; "Diamonds! There, in the rock!"), a call
     *  every few seconds at most, so the cave is not all shouting. */
    static void callOut(ServerLevel level, Party p, VillageFolkEntity f, BlockPos at, String ore, int behind) {
        VillageFolkEntity caller = f;
        double near = Double.MAX_VALUE;
        for (VillageFolkEntity m : members(level, p)) {
            double dd = m.blockPosition().distSqr(at);
            if (dd < near) { near = dd; caller = m; }
        }
        Delve d = caller.expedition() == null ? null : caller.expedition().delve();
        boolean big = "diamond".equals(ore) || "emerald".equals(ore);
        if (d != null && !big && caller.tickCount - d.spokeTick < 80) return;
        if (d != null) d.spokeTick = caller.tickCount;
        FolkTalk.speak(caller, "diamond".equals(ore) ? "Diamonds! There, in the rock!" : "emerald".equals(ore) ? "An emerald! Over here!"
            : behind > 0 ? FolkTalk.pick(caller.getRandom(), capital(ore) + " behind the wall here — we'll cut in to it.",
                capital(ore) + "! Hidden in the rock, " + behind + (behind == 1 ? " block" : " blocks") + " in.")
            : FolkTalk.pick(caller.getRandom(), capital(ore) + " here!", capital(ore) + " — over here!", "Here's " + ore + "!"));
    }

    /** The team's picks, and the stores' best. */
    private static List<ItemStack> picks(ServerLevel level, Party p) {
        List<ItemStack> out = new ArrayList<>();
        for (VillageFolkEntity m : members(level, p)) {
            ItemStack s = bestPick(m);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static boolean takes(List<ItemStack> picks, BlockState st) {
        for (ItemStack s : picks) if (s.isCorrectToolForDrops(st)) return true;
        return false;
    }

    /** The vein: this ore and every block of the same joined to it, as far as VEIN_MOST and eight blocks out. */
    static List<BlockPos> flood(ServerLevel level, BlockPos start, String ore) {
        List<BlockPos> out = new ArrayList<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(start);
        seen.add(start.asLong());
        while (!q.isEmpty() && out.size() < VEIN_MOST) {
            BlockPos p = q.poll();
            out.add(p);
            for (Direction dir : Direction.values()) {
                BlockPos n = p.relative(dir);
                if (!seen.add(n.asLong()) || n.distSqr(start) > 64) continue;
                if (ore.equals(oreOf(level.getBlockState(n)))) q.add(n);
            }
        }
        return out;
    }

    /** The ores the town wants most, in order: what its age asks for (iron, diamonds, coal, obsidian). */
    static List<String> wantedOres(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        if (wantedForTests != null) out.add(wantedForTests);
        try {
            for (Villages.Need n : Villages.needs(level, village)) {
                String ore = switch (n.task()) {
                    case IRON -> "iron";
                    case DIAMOND -> "diamond";
                    case COAL -> "coal";
                    case OBSIDIAN -> "obsidian";
                    default -> null;
                };
                if (ore != null && !out.contains(ore)) out.add(ore);
            }
        } catch (RuntimeException ex) {
            LOG.debug("[MCA-CAVES] needs look failed: {}", ex.toString());
        }
        return out;
    }

    /** The next vein on the cave's list for the team: the town's most wanted ore first, then the nearest. */
    @Nullable
    static Vein nextVein(ServerLevel level, VillageFolkEntity f, Party p) {
        if (p.caveKey == null) return null;
        List<String> wanted = wantedOres(level, p.village);
        Vein best = null;
        double bestScore = Double.MAX_VALUE;
        for (Vein v : veins(p.village, p.caveKey)) {
            if (!"todo".equals(v.state()) || p.passed.contains(v.at().asLong())) continue;
            double d = v.at().distSqr(f.blockPosition());
            if (d > 32 * 32) continue;
            int w = wanted.indexOf(v.ore());
            double score = (w >= 0 ? -1_000_000.0 + w * 100_000.0 : 0.0) + d;
            if (score < bestScore) { bestScore = score; best = v; }
        }
        return best;
    }

    /** To the vein (the team with it); there, its work laid out. */
    static boolean toVein(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Party p, Vein v) {
        long now = level.getGameTime();
        double reach = f.getEyePosition().distanceToSqr(Vec3.atCenterOf(v.at()));
        if (reach <= 5.0 * 5.0) {
            take(level, p, v);
            p.veinWalk = null;
            return true;
        }
        if (p.veinWalk == null || !p.veinWalk.equals(v.at())) {
            p.veinWalk = v.at();
            p.veinWalkBest = Double.MAX_VALUE;
            p.veinWalkTick = now;
        }
        if (reach < p.veinWalkBest - 0.5) {
            p.veinWalkBest = reach;
            p.veinWalkTick = now;
        } else if (now - p.veinWalkTick > 200) {
            p.passed.add(v.at().asLong());
            markVein(p.village, p.caveKey, v.at(), v.ore(), "left");
            f.brain("no way to the " + v.ore() + " at " + v.at().toShortString());
            p.veinWalk = null;
            return true;
        }
        if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 60) {
            f.getNavigation().moveTo(v.at().getX() + 0.5, v.at().getY(), v.at().getZ() + 0.5, 1.0D);
            e.walkTick = f.tickCount;
        }
        f.hobbyNow = "leading the cave dwellers to the " + v.ore();
        return true;
    }

    /** A vein taken in hand: the way in to it (the rock between, nearest the cave first), then its blocks as they show. */
    static void take(ServerLevel level, Party p, Vein v) {
        p.veinOf = v.ore();
        p.veinAt = v.at();
        p.veinDug = 0;
        p.work.clear();
        p.claims.clear();
        p.workTick = level.getGameTime();
        int[] a = access(level, v.at());
        if (a != null && a[0] > 1) {
            Direction dir = Direction.values()[a[1]];
            for (int j = a[0] - 1; j >= 1; j--) {
                BlockPos cut = v.at().relative(dir, j);
                p.work.add(cut);
                if (dir == Direction.UP) p.floorCuts.add(cut.asLong());
            }
        }
        p.work.add(v.at());
        for (BlockPos b : flood(level, v.at(), v.ore())) if (!b.equals(v.at()) && exposed(level, b)) p.work.add(b);
        if (a != null && a[0] > 1) p.hidden++;
        p.spareWanted = v.size() >= 8;                         // a long dig: a spare pick first, if its own is half worn
    }

    /** The vein done (or given up): its state on the cave's list, and the work cleared. */
    static void finishVein(ServerLevel level, Party p, @Nullable String why) {
        if (p.veinOf != null && p.veinAt != null && p.caveKey != null) {
            int left = 0;
            for (BlockPos b : flood(level, p.veinAt, p.veinOf)) if (p.veinOf.equals(oreOf(level.getBlockState(b)))) left++;
            String state = left == 0 || p.veinDug > 0 && why == null ? "mined" : p.veinDug == 0 ? "left" : "mined";
            if (left > 0 && p.veinDug == 0) state = "left";
            markVein(p.village, p.caveKey, p.veinAt, p.veinOf, state);
            LOG.info("[MCA-CAVES] the {} vein at {}: {} dug, {} left, {}{}", p.veinOf, p.veinAt.toShortString(), p.veinDug, left, state,
                why == null ? "" : " (" + why + ")");
        }
        p.veinOf = null;
        p.veinAt = null;
        p.work.clear();
        p.claims.clear();
    }

    /** The block in hand: to it, then dug at its pick's pace; the vein's next blocks as the break lays them open. */
    static boolean dig(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d, Party p, BlockPos b) {
        BlockState st = level.getBlockState(b);
        String ore = oreOf(st);
        boolean cut = ore == null;
        // Gone already (fallen, dug by somebody, washed out).
        if (st.isAir() || !level.getFluidState(b).isEmpty()) {
            drop(p, b);
            d.digging = null;
            return true;
        }
        ItemStack pick = bestPick(f);
        String no = cut ? refuseCut(level, b, st, p.village) : refuse(level, b, st, pick, p.village);
        if (no != null) {
            drop(p, b);
            p.passed.add(b.asLong());
            d.digging = null;
            f.brain("left a block at " + b.toShortString() + ": " + no);
            return true;
        }
        BlockPos feet = f.blockPosition();
        if (b.getX() == feet.getX() && b.getZ() == feet.getZ() && b.getY() < feet.getY()) {
            // Never the ground it stands on: a step to one side first, and the block dug from there.
            BlockPos aside = aside(level, f, b);
            if (aside == null) {
                drop(p, b);
                p.passed.add(b.asLong());
                return true;
            }
            f.getNavigation().moveTo(aside.getX() + 0.5, aside.getY(), aside.getZ() + 0.5, 1.0D);
            return true;
        }
        double reach = f.getEyePosition().distanceToSqr(Vec3.atCenterOf(b));
        if (d.digging == null || !d.digging.equals(b)) {
            d.digging = b;
            d.digTick = f.tickCount;
            d.dug = 0;
            d.digNeeded = 0;
            d.reachBest = Double.MAX_VALUE;
        }
        if (reach > 4.3 * 4.3) {
            if (reach < d.reachBest - 0.5) {
                d.reachBest = reach;
                d.digTick = f.tickCount;
            } else if (f.tickCount - d.digTick > 160) {
                // Out of its reach from anywhere it can stand: one of the others may get at it; it lets it go.
                p.claims.remove(b.asLong());
                d.digging = null;
                d.digTick = f.tickCount;
                f.brain("couldn't get at a block at " + b.toShortString());
                if (members(level, p).size() <= 1) {
                    drop(p, b);
                    p.passed.add(b.asLong());
                }
                return false;
            }
            if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 60) {
                f.getNavigation().moveTo(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 1.0D);
                e.walkTick = f.tickCount;
            }
            f.hobbyNow = cut ? "cutting in to the " + p.veinOf : "going after the " + ore;
            return true;
        }
        f.getNavigation().stop();
        if (d.digNeeded <= 0) {
            equipPick(f);
            d.digNeeded = quick ? 10 : Math.max(10, f.workTicksFor(st));
        }
        f.getLookControl().setLookAt(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        d.dug += 5;
        if (d.dug % 10 == 0) {
            f.swing(InteractionHand.MAIN_HAND);
            f.workHit(b);
        }
        f.hobbyNow = cut ? "cutting in to the " + p.veinOf : "mining " + ore + " in the caves";
        if (d.dug < d.digNeeded) return true;
        if (cut) breakRock(level, f, b, st);
        else breakOre(level, f, p, b, st, ore);
        drop(p, b);
        d.digging = null;
        d.digNeeded = 0;
        p.workTick = level.getGameTime();
        if (!cut) {
            // What of the vein the break laid open.
            for (Direction dir : Direction.values()) {
                BlockPos n = b.relative(dir);
                if (p.work.contains(n) || p.passed.contains(n.asLong()) || p.veinDug >= VEIN_MOST) continue;
                if (ore.equals(oreOf(level.getBlockState(n)))) p.work.add(n);
            }
            // A vein under the floor: the pit filled again to a step, out of its pack's cobble.
            if (p.floorCuts.contains(b.above().asLong()) && level.getBlockState(b).isAir()
                    && f.removeMatching(s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE), 1) == 1) {
                level.setBlockAndUpdate(b, Blocks.COBBLESTONE.defaultBlockState());
                f.placeSound(b);
            }
        }
        return true;
    }

    private static void drop(Party p, BlockPos b) {
        p.work.remove(b);
        p.claims.remove(b.asLong());
    }

    /** Why it will not mine this ore, in a few words; null if it will. */
    @Nullable
    static String refuse(ServerLevel level, BlockPos p, BlockState st, ItemStack pick, UUID village) {
        if (pick.isEmpty()) return "I've no pick";
        if (!pick.isCorrectToolForDrops(st)) return "my " + BuiltInRegistries.ITEM.getKey(pick.getItem()).getPath().replace('_', ' ') + " won't take it";
        if (wetBeside(level, p)) return "there's lava or water behind it";
        if (inTown(village, p)) return "it's in the town";
        if (MineStairs.isFloor(level, p)) return "it's a step of the miners' stairs";
        if (st.is(Blocks.OBSIDIAN)) {
            for (Direction dir : Direction.values()) {
                if (level.getBlockState(p.relative(dir)).is(Blocks.NETHER_PORTAL)) return "it's a portal's frame";
            }
            if (!MineStairs.underground(level, p)) return "it's somebody's, out in the open";
        }
        return null;
    }

    /** Why it will not cut this block of rock on the way in to a vein; null if it will: only rock the world made, dry. */
    @Nullable
    static String refuseCut(ServerLevel level, BlockPos p, BlockState st, UUID village) {
        if (!MineStairs.ground(st)) return "it isn't rock the world made";
        if (wetBeside(level, p)) return "there's lava or water behind it";
        if (inTown(village, p)) return "it's in the town";
        if (MineStairs.isFloor(level, p)) return "it's a step of the miners' stairs";
        return null;
    }

    private static boolean inTown(UUID village, BlockPos p) {
        Villages.Village v = Villages.get(village);
        int reach = Villages.townReach(village);
        return v != null && Scouts.flat(p, v.centre()) <= (double) reach * reach;
    }

    /** A spot beside an ore under its feet to dig it from: open, with a floor, the same height. */
    @Nullable
    private static BlockPos aside(ServerLevel level, VillageFolkEntity f, BlockPos ore) {
        BlockPos feet = f.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int k = 1; k <= 2; k++) {
                BlockPos p = feet.relative(dir, k);
                if (p.getX() == ore.getX() && p.getZ() == ore.getZ()) continue;
                if (standable(level, p) && !p.below().equals(ore)) return p;
            }
        }
        return null;
    }

    /** A block of rock cut on the way in: its cobble into the pack if there is room, else left where it fell. */
    static void breakRock(ServerLevel level, VillageFolkEntity f, BlockPos b, BlockState st) {
        if (!isPickaxe(f.getMainHandItem())) equipPick(f);
        List<ItemStack> drops = Block.getDrops(st, level, b, level.getBlockEntity(b), f, f.getMainHandItem());
        level.destroyBlock(b, false, f);
        for (ItemStack drop : drops) {
            ItemStack left = f.countMatching(s -> s.is(drop.getItem())) < 32 ? f.insertItem(drop) : drop;
            if (!left.isEmpty()) Block.popResource(level, b, left);
        }
        f.damageHeldTool();
        f.note(AssistantEntity.Deed.BLOCKS_MINED, 1);
    }

    /** The ore out: what it drops for this pick, into its pack, booked as its work (Economy.gathered). */
    static void breakOre(ServerLevel level, VillageFolkEntity f, Party p, BlockPos b, BlockState st, String ore) {
        if (!isPickaxe(f.getMainHandItem())) equipPick(f);
        ItemStack tool = f.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(st, level, b, level.getBlockEntity(b), f, tool);
        level.destroyBlock(b, false, f);
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            ItemStack lot = drop.copy();
            ItemStack left = f.insertItem(drop);
            int in = lot.getCount() - left.getCount();
            if (in > 0) {
                Economy.gathered(f, lot, in);
                haul(p, lot, in);
            }
            if (!left.isEmpty()) Block.popResource(level, b, left);
        }
        f.damageHeldTool();
        f.note(AssistantEntity.Deed.BLOCKS_MINED, 1);
        f.note(AssistantEntity.Deed.ORE_FOUND, 1);
        f.awardXp(2);                                        // the caves are learned fast, working them with the team
        p.mined++;
        p.veinDug++;
        p.order.add(ore);
        BlockPos at = p.veinAt != null ? p.veinAt : b;
        merge(p.found, new Find(Kind.VEIN, ore, at, p.day, f.displayNameCap(), 0, 1));
        if (p.mined == 1 || "diamond".equals(ore) || "emerald".equals(ore)) {
            FolkTalk.speak(f, "diamond".equals(ore) ? "Diamonds! The smith will want these." : "emerald".equals(ore) ? "An emerald! That's a find."
                : FolkTalk.pick(f.getRandom(), capital(ore) + ", and plenty of it.", "Good " + ore + " here. Into the pack it goes."));
        }
        if ("diamond".equals(ore) || "emerald".equals(ore)) {
            int i = 0;
            for (VillageFolkEntity m : members(level, p)) {
                if (m == f) continue;
                m.sayLater(FolkTalk.pick(m.getRandom(), "Diamonds!", "Ha! Look at that!", "Well done! Into the pack with it!", "The town'll sing for that."),
                    12 + 14 * i++);
            }
        }
    }

    // ------------------------------------------------------------------ old chests

    /** Is this a container the world left (its loot table not yet opened, or inside a structure the world built), and
     *  nobody's: not named, not a village of villagers', not near any town? */
    static boolean worldChest(ServerLevel level, BlockEntity be) {
        if (!(be instanceof ChestBlockEntity) && !(be instanceof BarrelBlockEntity)) return false;
        RandomizableContainerBlockEntity box = (RandomizableContainerBlockEntity) be;
        BlockPos p = be.getBlockPos();
        if (box.hasCustomName() || nearAnyTown(level, p)) return false;
        String built = structureAt(level, p);
        if (!lootable(level, built, p)) return false;
        return box.getLootTable() != null || built != null && structureWords(built) != null;
    }

    /**
     * May a chest in this structure be looked into? Never a village of villagers' (theirs), an outpost's or a mansion's
     * (the pillagers'), the deep dark's or the trial chambers' (a warden, the breezes: no cave dweller comes home from
     * those), nor buried treasure (it would have to dig for it). A desert temple's only once its trap is gone: its
     * chests sit round a pressure plate over a cellar of TNT.
     */
    static boolean lootable(ServerLevel level, @Nullable String built, BlockPos at) {
        if (built == null) return true;
        if (built.startsWith("village") || built.equals("pillager_outpost") || built.equals("mansion") || built.equals("ancient_city")
            || built.equals("trial_chambers") || built.equals("buried_treasure")) return false;
        if (built.equals("desert_pyramid")) {
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-5, -3, -5), at.offset(5, 3, 5))) {
                if (level.getBlockState(q).is(Blocks.STONE_PRESSURE_PLATE)) return false;
            }
        }
        return true;
    }

    /** A mineshaft's cart (or another the world left): a chest cart, its loot unopened or down in the structure, nobody's. */
    static boolean worldCart(ServerLevel level, AbstractMinecartContainer cart) {
        if (!(cart instanceof MinecartChest) || cart.hasCustomName() || nearAnyTown(level, cart.blockPosition())
            || !lootable(level, structureAt(level, cart.blockPosition()), cart.blockPosition())) return false;
        if (cart.getLootTable() != null) return true;
        String built = structureAt(level, cart.blockPosition());
        return built != null && built.startsWith("mineshaft");
    }

    private static boolean nearAnyTown(ServerLevel level, BlockPos p) {
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            int r = Villages.townReach(v.id()) + 16;
            if (Scouts.flat(p, v.centre()) <= (double) r * r) return true;
        }
        return false;
    }

    /** Has the town's report a chest looked into here already? */
    static boolean looted(UUID village, Party p, BlockPos at) {
        for (Find x : p.found) if (x.kind() == Kind.CHEST && x.at().equals(at)) return true;
        for (Find x : report(village)) if (x.kind() == Kind.CHEST && x.at().equals(at)) return true;
        return false;
    }

    /** Old chests and carts near the leader (one taken in hand when the team has nothing else in hand), and the spawners. */
    static void containers(ServerLevel level, VillageFolkEntity f, Party p) {
        BlockPos at = f.blockPosition();
        boolean free = !p.homeward && p.chest == null && p.cart == null && p.work.isEmpty();
        for (int cx = (at.getX() - 16) >> 4; cx <= (at.getX() + 16) >> 4; cx++) {
            for (int cz = (at.getZ() - 16) >> 4; cz <= (at.getZ() + 16) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    BlockPos q = be.getBlockPos();
                    if (q.distSqr(at) > 16 * 16) continue;
                    if (be instanceof SpawnerBlockEntity sp) {
                        spawner(level, f, p, q, sp);
                        continue;
                    }
                    if (!free || p.passed.contains(q.asLong()) || !worldChest(level, be) || looted(p.village, p, q)) continue;
                    p.chest = q;
                    p.chestTick = level.getGameTime();
                    p.chestBest = Double.MAX_VALUE;
                    free = false;
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "An old chest! Let's see what's in it.", "A chest, left down here. Nobody's — not for years."));
                }
            }
        }
        if (!free) return;
        for (AbstractMinecartContainer cart : level.getEntitiesOfClass(AbstractMinecartContainer.class, new AABB(at).inflate(16), Entity::isAlive)) {
            if (p.passed.contains(cart.blockPosition().asLong()) || !worldCart(level, cart) || looted(p.village, p, cart.blockPosition())) continue;
            p.cart = cart.getUUID();
            p.chestTick = level.getGameTime();
            p.chestBest = Double.MAX_VALUE;
            FolkTalk.speak(f, "An old cart, still loaded. The miners that left it won't be back for it.");
            return;
        }
    }

    /** To the chest or the cart, opened (the world's loot rolled as it would be for a player), and the valuables taken. */
    static boolean loot(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Party p) {
        Container box;
        BlockPos at;
        boolean cart = p.cart != null;
        long now = level.getGameTime();
        if (cart) {
            Entity c = level.getEntity(p.cart);
            if (!(c instanceof AbstractMinecartContainer m) || !c.isAlive()) {
                p.cart = null;
                return true;
            }
            box = m;
            at = c.blockPosition();
        } else {
            BlockEntity be = level.getBlockEntity(p.chest);
            if (!(be instanceof Container c)) {
                p.chest = null;
                return true;
            }
            box = c;
            at = p.chest;
        }
        double reach = f.getEyePosition().distanceToSqr(Vec3.atCenterOf(at));
        if (reach > 4.0 * 4.0) {
            if (reach < p.chestBest - 0.5) {
                p.chestBest = reach;
                p.chestTick = now;
            } else if (now - p.chestTick > 200) {
                p.passed.add(at.asLong());
                p.chest = null;
                p.cart = null;
                f.brain("couldn't get to an old chest at " + at.toShortString());
                return true;
            }
            if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 60) {
                f.getNavigation().moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 1.0D);
                e.walkTick = f.tickCount;
            }
            f.hobbyNow = "making for an old chest";
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        if (box instanceof RandomizableContainerBlockEntity r) r.unpackLootTable(null);
        if (box instanceof ContainerEntity ce) ce.unpackChestVehicleLootTable(null);
        level.playSound(null, at, cart ? SoundEvents.CHEST_OPEN : level.getBlockState(at).getBlock() instanceof ChestBlock
            ? SoundEvents.CHEST_OPEN : SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
        f.swing(InteractionHand.MAIN_HAND);
        List<String> took = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty() || !valuable(s)) continue;
            ItemStack lot = s.copy();
            ItemStack left = f.insertItem(lot.copy());
            int in = lot.getCount() - left.getCount();
            if (in <= 0) continue;
            ItemStack rest = s.copy();
            rest.shrink(in);
            box.setItem(i, rest.isEmpty() ? ItemStack.EMPTY : rest);
            Economy.gathered(f, lot, in);
            haul(p, lot, in);
            took.add(Bench.words(lot.getItem(), in));
            n += in;
        }
        box.setChanged();
        String built = structureAt(level, at);
        String[] words = built == null ? null : structureWords(built);
        String label = cart ? "a mineshaft's cart" : words != null ? words[0] + "'s chest" : spawnerNear(level, at) ? "a dungeon's chest" : "an old chest";
        merge(p.found, new Find(Kind.CHEST, label, at.immutable(), p.day, f.displayNameCap(), n, 0));
        if (words != null) merge(p.found, new Find(words[1].equals("mineshaft") ? Kind.MINESHAFT : Kind.STRUCTURE, words[0], at.immutable(),
            p.day, f.displayNameCap(), 1, 0));
        p.opened++;
        p.chest = null;
        p.cart = null;
        f.awardXp(3);
        FolkTalk.speak(f, n > 0 ? "In " + label + ": " + String.join(", ", took.subList(0, Math.min(3, took.size()))) + (took.size() > 3 ? " and more" : "") + "!"
            : "Nothing in " + label + " worth carrying home.");
        LOG.info("[MCA-CAVES] {} opened {} at {}: took {}", f.displayNameCap(), label, at.toShortString(), took);
        return true;
    }

    /** What is worth carrying home out of an old chest: ore and metal, gems, books and gold apples, saddles, name tags,
     *  music discs, the smith's templates and the like. Not the bones, the string or the bread. */
    public static boolean valuable(ItemStack s) {
        if (s.isEmpty()) return false;
        if (s.is(Items.DIAMOND) || s.is(Items.EMERALD) || s.is(Items.LAPIS_LAZULI) || s.is(Items.REDSTONE) || s.is(Items.COAL)
            || s.is(Items.RAW_IRON) || s.is(Items.RAW_GOLD) || s.is(Items.RAW_COPPER) || s.is(Items.IRON_INGOT) || s.is(Items.GOLD_INGOT)
            || s.is(Items.COPPER_INGOT) || s.is(Items.NETHERITE_INGOT) || s.is(Items.NETHERITE_SCRAP) || s.is(Items.IRON_NUGGET)
            || s.is(Items.GOLD_NUGGET) || s.is(Items.AMETHYST_SHARD) || s.is(Items.OBSIDIAN) || s.is(Items.QUARTZ)) return true;
        if (s.is(Items.ENCHANTED_BOOK) || s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.GOLDEN_CARROT)
            || s.is(Items.SADDLE) || s.is(Items.NAME_TAG) || s.is(Items.IRON_HORSE_ARMOR) || s.is(Items.GOLDEN_HORSE_ARMOR)
            || s.is(Items.DIAMOND_HORSE_ARMOR) || s.is(Items.HEART_OF_THE_SEA) || s.is(Items.ECHO_SHARD) || s.is(Items.ENDER_PEARL)
            || s.is(Items.EXPERIENCE_BOTTLE) || s.is(Items.TOTEM_OF_UNDYING) || s.is(Items.DISC_FRAGMENT_5)) return true;
        if (s.get(DataComponents.JUKEBOX_PLAYABLE) != null) return true;                       // a music disc
        if (s.getItem() instanceof net.minecraft.world.item.SmithingTemplateItem) return true;
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return path.startsWith("diamond_") || path.startsWith("netherite_") || s.isEnchanted();
    }

    // ------------------------------------------------------------------ spawners, structures, lava

    /** A spawner: noted (a dungeon's, or a structure's), and left be. */
    static void spawner(ServerLevel level, VillageFolkEntity f, Party p, BlockPos at, SpawnerBlockEntity sp) {
        String mob = "monster";
        try {
            CompoundTag tag = sp.getSpawner().save(new CompoundTag());
            String id = tag.getCompound("SpawnData").getCompound("entity").getString("id");
            if (!id.isEmpty()) mob = id.substring(id.indexOf(':') + 1).replace('_', ' ');
        } catch (RuntimeException ex) {
            LOG.debug("[MCA-CAVES] spawner read failed: {}", ex.toString());
        }
        String built = structureAt(level, at);
        boolean dungeon = built == null && mossyNear(level, at);
        String label = dungeon ? "a dungeon with " + JobMarket.a(mob) + " spawner"
            : JobMarket.a(mob) + " spawner" + (built != null && built.startsWith("mineshaft") ? " in a mineshaft" : "");
        note(f, p, new Find(dungeon ? Kind.DUNGEON : Kind.SPAWNER, label, at.immutable(), p.day, f.displayNameCap(), depth(level, at), 0),
            "A " + mob + " spawner! Torches round it, everybody — nothing more comes out of that.");
        if (!p.homeward && p.spawnerToLight == null && !p.spawnersLit.contains(at.asLong()) && level.getBrightness(LightLayer.BLOCK, at.above()) < 8) {
            p.spawnerToLight = at.immutable();
            p.spawnerTick = level.getGameTime();
        }
    }

    /**
     * To the spawner, and torches set round it (on it, and on the floor at its sides), so nothing more spawns there: a
     * torch at a time, four at most, then on. Given up if it cannot be got to in ten seconds. The spawner stays.
     */
    static boolean lightSpawner(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Party p) {
        BlockPos sp = p.spawnerToLight;
        if (sp == null) return false;
        long now = level.getGameTime();
        if (!level.getBlockState(sp).is(Blocks.SPAWNER) || now - p.spawnerTick > 400) {
            p.spawnersLit.add(sp.asLong());
            p.spawnerToLight = null;
            return true;
        }
        double reach = f.getEyePosition().distanceToSqr(Vec3.atCenterOf(sp));
        if (reach > 3.5 * 3.5) {
            if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 40) {
                f.getNavigation().moveTo(sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, 1.0D);
                e.walkTick = f.tickCount;
            }
            f.hobbyNow = "making for the spawner, to light it up";
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(sp.getX() + 0.5, sp.getY() + 0.5, sp.getZ() + 0.5);
        int set = 0;
        BlockPos[] spots = { sp.above(), sp.north(2), sp.south(2), sp.east(2), sp.west(2) };
        for (BlockPos q : spots) if (level.getBlockState(q).is(Blocks.TORCH) || level.getBlockState(q).is(Blocks.WALL_TORCH)) set++;
        if (set < 4) {
            for (BlockPos q : spots) {
                if (!level.getBlockState(q).isAir() || !Blocks.TORCH.defaultBlockState().canSurvive(level, q)) continue;
                if (f.getEyePosition().distanceToSqr(Vec3.atCenterOf(q)) > 5.0 * 5.0) continue;
                if (placeTorch(level, f, p, q)) return true;                       // a torch at a time
            }
        }
        p.spawnersLit.add(sp.asLong());
        p.spawnerToLight = null;
        p.lit++;
        FolkTalk.speak(f, "There. Lit all round — nothing'll spawn from that now.");
        LOG.info("[MCA-CAVES] {} lit the spawner at {} ({} torches round it)", f.displayNameCap(), sp.toShortString(), set);
        return true;
    }

    private static boolean mossyNear(ServerLevel level, BlockPos p) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-4, -1, -4), p.offset(4, 0, 4))) {
            if (level.getBlockState(q).is(Blocks.MOSSY_COBBLESTONE)) return true;
        }
        return false;
    }

    private static boolean spawnerNear(ServerLevel level, BlockPos p) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-6, -2, -6), p.offset(6, 2, 6))) {
            if (level.getBlockState(q).is(Blocks.SPAWNER)) return true;
        }
        return false;
    }

    /** The structure the world built that this spot is inside a piece of (its id's path), or null. */
    @Nullable
    static String structureAt(ServerLevel level, BlockPos p) {
        try {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (Structure s : level.structureManager().getAllStructuresAt(p).keySet()) {
                StructureStart start = level.structureManager().getStructureWithPieceAt(p, s);
                if (start == null || !start.isValid()) continue;
                ResourceLocation key = registry.getKey(s);
                if (key != null) return key.getPath();
            }
        } catch (RuntimeException ex) {
            LOG.debug("[MCA-CAVES] structure look failed: {}", ex.toString());
        }
        return null;
    }

    /** What a structure is called, and whether it is a mineshaft or another of "the monuments": {words, "mineshaft" or
     *  "structure"}; null for one whose chests are not the caves' (a village's, an outpost's, a mansion's). */
    @Nullable
    static String[] structureWords(String path) {
        if (path.startsWith("mineshaft")) return new String[]{ "a mineshaft", "mineshaft" };
        if (path.startsWith("ruined_portal")) return new String[]{ "a ruined portal", "structure" };
        if (path.startsWith("ocean_ruin")) return new String[]{ "an ocean ruin", "structure" };
        if (path.startsWith("shipwreck")) return new String[]{ "a shipwreck", "structure" };
        return switch (path) {
            case "stronghold" -> new String[]{ "a stronghold", "structure" };
            case "ancient_city" -> new String[]{ "an ancient city", "structure" };
            case "desert_pyramid" -> new String[]{ "a desert temple", "structure" };
            case "jungle_pyramid" -> new String[]{ "a jungle temple", "structure" };
            case "trail_ruins" -> new String[]{ "old ruins", "structure" };
            case "buried_treasure" -> new String[]{ "buried treasure", "structure" };
            case "igloo" -> new String[]{ "an igloo", "structure" };
            case "trial_chambers" -> new String[]{ "the trial chambers", "structure" };
            default -> null;
        };
    }

    /** What was built down here: a mineshaft, a stronghold, a temple, an ancient city. */
    static void structures(ServerLevel level, VillageFolkEntity f, Party p) {
        String built = structureAt(level, f.blockPosition());
        if (built == null) return;
        String[] words = structureWords(built);
        if (words == null) return;
        boolean shaft = words[1].equals("mineshaft");
        note(f, p, new Find(shaft ? Kind.MINESHAFT : Kind.STRUCTURE, words[0], f.blockPosition(), p.day, f.displayNameCap(), 0, 0),
            shaft ? "A mineshaft! Old timbers, rails — somebody dug here long ago." : "Look at this — " + words[0] + "! There'll be treasure about.");
    }

    /** Lava round it: noted as a danger to the miners. */
    static void lava(ServerLevel level, VillageFolkEntity f, Party p) {
        BlockPos at = f.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -8; dx <= 8; dx += 2) {
            for (int dz = -8; dz <= 8; dz += 2) {
                for (int dy = -4; dy <= 3; dy++) {
                    m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    FluidState fl = level.getFluidState(m);
                    if (!fl.is(Fluids.LAVA) || !fl.isSource()) continue;
                    note(f, p, new Find(Kind.LAVA, "a pool of lava", m.immutable(), p.day, f.displayNameCap(), depth(level, m), 0),
                        "Lava! Keep well back from it, everybody.");
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ noting it

    /** Noted for the report, if new to this day and to the town's report; said out loud if it is. */
    static void note(VillageFolkEntity f, Party p, Find x, @Nullable String say) {
        boolean fresh = merge(p.found, x);
        if (!fresh) return;
        if (x.kind() != Kind.VEIN && known(report(p.village), x)) return;
        if (say != null) FolkTalk.speak(f, say);
        LOG.info("[MCA-CAVES] {} found {} ({}) at {}", f.displayNameCap(), x.label(), x.kind(), x.at().toShortString());
    }

    private static int near(Kind k) {
        return switch (k) {
            case CAVE, RAVINE -> 20;
            case VEIN -> 6;
            case MINESHAFT, STRUCTURE -> 64;
            case DUNGEON, SPAWNER -> 3;
            case CHEST -> 0;
            case LAVA -> 12;
        };
    }

    private static boolean same(Find x, Find y) {
        if (x.kind() != y.kind()) return false;
        int n = near(x.kind());
        if (x.at().distSqr(y.at()) > (double) n * n) return false;
        return switch (x.kind()) {
            case VEIN, MINESHAFT, STRUCTURE, DUNGEON, SPAWNER -> x.label().equals(y.label());
            default -> true;
        };
    }

    private static boolean known(List<Find> all, Find x) {
        for (Find y : all) if (same(y, x)) return true;
        return false;
    }

    /** Into a list of finds: new, or folded into the one already there. Returns whether it was new. */
    static boolean merge(List<Find> all, Find x) {
        for (int i = 0; i < all.size(); i++) {
            Find y = all.get(i);
            if (!same(y, x)) continue;
            Find m = switch (x.kind()) {
                case VEIN -> y.with(Math.max(y.a(), x.a()), y.b() + x.b());
                case CAVE, RAVINE -> y.with(Math.max(y.a(), x.a()), Math.max(y.b(), x.b()));
                case MINESHAFT, STRUCTURE, CHEST -> y.with(y.a() + x.a(), y.b() + x.b());
                default -> y;
            };
            all.set(i, m);
            return false;
        }
        all.add(x);
        return true;
    }

    private static void haul(Party p, ItemStack s, int n) {
        p.haul.merge(s.getHoverName().getString().toLowerCase(Locale.ROOT), n, Integer::sum);
    }

    // ------------------------------------------------------------------ home together

    /** The team turns for home, together, along the leader's marks. */
    static void turnBack(ServerLevel level, VillageFolkEntity f, Party p, String why) {
        if (p.homeward) return;
        p.homeward = true;
        p.why = why;
        p.crumb = p.trail.size() - 1;
        p.target = null;
        p.chest = null;
        p.cart = null;
        finishVein(level, p, "home");
        for (VillageFolkEntity m : members(level, p)) {
            Scouts.Expedition e = m.expedition();
            if (e == null) continue;
            e.returning = true;
            e.why = why;
            e.waypoint = null;
            e.detour = 0;
            e.best = Double.MAX_VALUE;
            e.gainedTick = m.tickCount;
            m.getNavigation().stop();
        }
        FolkTalk.speak(f, why.endsWith("got hurt") ? "Back, all of us — " + why.replace(" got hurt", "") + "'s hurt. Home, together."
            : why.startsWith("it was time") || why.startsWith("the days") ? (p.mined + p.opened > 0 ? "We turn back: " + why + ". A good "
                + (p.days > 1.0 ? "trip" : "day") + " — home, everybody, with full packs!" : "We turn back: " + why + ". Stay close.")
            : "We turn back: " + why + ". Home, all of us, together.");
        LOG.info("[MCA-CAVES] the team of {} turns for home: {}", Villages.name(p.village), why);
    }

    /** The leader home along its marks, the team behind it (waiting for any that fall behind); out of the ground by the
     *  miners' stairs when there is no walking out; a call for help when there is no getting out at all. */
    static boolean walkHome(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d, Party p) {
        BlockPos feet = f.blockPosition();
        long time = level.getDayTime() % 24000L;
        if (p.crumb < 0 && Scouts.flat(feet, e.home) <= 14 * 14) {
            p.phase = Party.Phase.STORE;
            greet(level, p, f);
            return true;
        }
        if (waiting(level, f, p)) return true;
        BlockPos dest = p.crumb >= 0 && p.crumb < p.trail.size() ? p.trail.get(p.crumb) : e.home;
        double dist = p.crumb >= 0 ? feet.distSqr(dest) : Scouts.flat(feet, dest);
        if (p.crumb >= 0 && dist <= 3 * 3) {
            p.crumb--;
            e.best = Double.MAX_VALUE;
            e.gainedTick = f.tickCount;
            e.waypoint = null;
            e.walkTick = -1000;
            return true;
        }
        if (dist < e.best - 1.5) {
            e.best = dist;
            e.gainedTick = f.tickCount;
        } else if (f.tickCount - e.gainedTick > 240) {
            e.gainedTick = f.tickCount;
            e.detour++;
            e.best = Double.MAX_VALUE;
            if (p.crumb >= 0 && e.detour <= 3) {
                p.crumb--;                                           // the next mark along
                return true;
            }
            if (MineStairs.underground(level, feet)) {
                if (d.climbs < 3) {
                    d.climbs++;
                    p.crumb = -1;
                    f.getNavigation().stop();
                    f.enqueueFront(Job.mine(feet.getY(), MineStairs.OUT));
                    FolkTalk.speak(f, "No way back the way we came. I'll cut us a way up — follow me.");
                    LOG.info("[MCA-CAVES] {} climbs out by its own stairs from {} ({})", f.displayNameCap(), feet.toShortString(), d.climbs);
                    return true;
                }
                callForHelp(level, f, e, p);
                return true;
            }
            p.crumb = -1;
            e.waypoint = null;
            if (e.detour > 14) {
                callForHelp(level, f, e, p);
                return true;
            }
        }
        if (p.crumb >= 0) {
            if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 60) {
                f.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, time >= 11000 ? 1.15D : 1.0D);
                e.walkTick = f.tickCount;
            }
        } else {
            if (e.waypoint == null || Scouts.flat(feet, e.waypoint) <= 3 * 3) {
                e.waypoint = Scouts.chooseWaypoint(level, f, e.home, Math.min(6, e.detour));
                e.walkTick = -1000;
            }
            if (e.waypoint != null && (f.getNavigation().isDone() || f.tickCount - e.walkTick > 80)) {
                f.walkTo(e.waypoint, time >= 11000 ? 1.15D : 1.0D);
                e.walkTick = f.tickCount;
            }
        }
        f.hobbyNow = "leading the cave dwellers home";
        return true;
    }

    /** Back in town: the townsfolk about notice the team come home, and say so (a long trip, the more so). */
    static void greet(ServerLevel level, Party p, VillageFolkEntity lead) {
        if (p.greeted || p.reported) return;
        p.greeted = true;
        int n = 0;
        for (VillageFolkEntity m : level.getEntitiesOfClass(VillageFolkEntity.class, lead.getBoundingBox().inflate(24),
                x -> x.isAlive() && !x.isBaby() && !x.isShowcase() && p.village.equals(x.ownerId()) && !p.members.contains(x.getUUID()))) {
            if (n >= 3) break;
            String say = p.days > 1.0 ? FolkTalk.pick(m.getRandom(), "They're back! " + CaveTrips.daysWords(p.days) + " down there!",
                    "The cave team's home! Look at those packs!", "Welcome home, all of you! We were starting to worry.")
                : FolkTalk.pick(m.getRandom(), "Welcome back! What did you find?", "Back from the caves — and all of you, too.", "There they are!");
            m.sayLater(say, 10 + 25 * n);
            m.getLookControl().setLookAt(lead, 30.0F, 30.0F);
            n++;
        }
    }

    /** No way out it can find: what the team found is told, the town's search party goes out for it, and it waits,
     *  cutting its way up as it can (MineStairs). Never lifted out. */
    static void callForHelp(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Party p) {
        UUID id = p.village;
        long day = level.getDayTime() / 24000L;
        for (Find x : p.found) record(id, x);
        p.members.remove(f.getUUID());
        Scouts.release(level, f, e);
        f.expedition(null);
        LOST.put(f.getUUID(), level.getGameTime());
        Villages.tell(id, day, f.displayNameCap() + " could not find the way out of the caves " + e.heading + " and called for help");
        FolkTalk.speak(f, "Help! Down here! I can't find the way out!");
        f.enqueueFront(Job.mine(f.blockPosition().getY(), MineStairs.OUT));
        Villages.Village v = Villages.get(id);
        boolean party = v != null && SearchParties.start(level, v, f, level.getGameTime());
        LOG.info("[MCA-CAVES] {} lost at {}, called for help (search party: {})", f.displayNameCap(), f.blockPosition().toShortString(), party);
    }

    /** Where the haul goes: the town's storehouse (the ground before its door), or the stores' chest nearest the heart. */
    @Nullable
    static BlockPos storeSpot(ServerLevel level, UUID village) {
        BlockPos door = Storehouses.doorFor(level, village);
        if (door != null) {
            BlockPos stand = Storehouses.standingSpot(level, village);
            return stand != null ? stand : door;
        }
        Villages.Village v = Villages.get(village);
        BlockPos best = null;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (v != null && (best == null || p.distSqr(v.centre()) < best.distSqr(v.centre()))) best = p;
        }
        return best != null ? best : v == null ? null : v.centre();
    }

    /** Home: every one of the team to the storehouse, and its whole haul in; the last one in, and the day is told. */
    static boolean toTheStores(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d, Party p) {
        BlockPos spot = storeSpot(level, p.village);
        if (spot == null || f.blockPosition().distSqr(spot) <= 3.5 * 3.5 || f.tickCount - e.gainedTick > 900) {
            putIn(level, f, p);
            Scouts.release(level, f, e);
            f.expedition(null);
            p.home.add(f.getUUID());
            if (members(level, p).isEmpty()) report(level, p);
            return false;
        }
        if (f.getNavigation().isDone() || f.tickCount - d.followTick > 40) {
            f.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0D);
            d.followTick = f.tickCount;
        }
        f.hobbyNow = "taking the caves' haul to the storehouse";
        return true;
    }

    /**
     * Its whole haul into the town's storehouse (with none, into the stores): booked in the storehouse's books as
     * brought in by it, and as its work (Economy). Its kit and its keepsakes stay with it.
     */
    static void putIn(ServerLevel level, VillageFolkEntity f, Party p) {
        Villages.Village v = Villages.get(p.village);
        if (v == null) return;
        StorehouseBlockEntity house = Storehouses.storeFor(level, p.village);
        String who = f.displayNameCap();
        var pack = f.getInventoryItems();
        List<ItemStack> lots = new ArrayList<>();
        int[] out = spare(f, true);
        for (int i = 0; i < pack.size(); i++) {
            if (out[i] <= 0) continue;
            ItemStack s = pack.get(i);
            ItemStack lot = s.copyWithCount(out[i]);
            s.shrink(out[i]);
            if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
            // What it took down and did not use (torches over a stack, planks, sticks) goes back: not a find.
            boolean back = lot.is(Items.TORCH) || CaveCraft.makings(lot) > 0 && !CaveCraft.coal(lot);
            if (back) {
                if (house != null) {
                    ItemStack left = house.insert(lot.copy());
                    if (!left.isEmpty()) Crafts.store(level, v, left);
                } else {
                    Crafts.store(level, v, lot.copy());
                }
                if (lot.is(Items.TORCH)) p.returned += lot.getCount();
                continue;
            }
            Economy.produced(f, lot.copy());
            if (house != null) {
                ItemStack left = house.insert(lot.copy());
                int in = lot.getCount() - left.getCount();
                if (in > 0) lots.add(lot.copyWithCount(in));
                if (!left.isEmpty()) Crafts.store(level, v, left);
            } else {
                Crafts.store(level, v, lot.copy());
            }
            p.stored.merge(lot.getHoverName().getString().toLowerCase(Locale.ROOT), lot.getCount(), Integer::sum);
        }
        if (house != null) {
            house.setChanged();
            if (!lots.isEmpty()) Storekeeping.bookIn(level, p.village, who, lots, false);
        }
        f.swing(InteractionHand.MAIN_HAND);
        f.brain("put the caves' haul into the " + (house != null ? "storehouse" : "stores"));
    }

    /**
     * The team's trip, told: the finds into the report, the haul booked to the team, the trip told in the chronicle as a
     * small story (and a long one home with a big haul as the town's news), the morning's word, each member's memory.
     */
    static void report(ServerLevel level, Party p) {
        if (p.reported) return;
        p.reported = true;
        UUID id = p.village;
        Villages.Village v = Villages.get(id);
        long day = level.getDayTime() / 24000L;
        List<String> names = new ArrayList<>();
        String leadName = null;
        for (UUID u : p.members) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity m)) continue;
            names.add(m.displayNameCap());
            if (u.equals(p.leader)) leadName = m.displayNameCap();
        }
        BlockPos home = v == null ? BlockPos.ZERO : v.centre();
        List<String> big = new ArrayList<>();
        int fresh = 0;
        for (Find x : p.found) {
            boolean isNew = record(id, x);
            if (isNew) fresh++;
            if (!isNew) continue;
            switch (x.kind()) {
                case MINESHAFT, DUNGEON, SPAWNER, STRUCTURE -> big.add(x.label() + " " + away(home, x.at()));
                case RAVINE -> big.add("a ravine " + away(home, x.at()));
                default -> { }
            }
        }
        for (Find x : p.found) {
            if (x.kind() == Kind.CHEST && x.a() > 0 && !x.label().startsWith("an old") && !x.label().startsWith("a mineshaft")
                    && !x.label().startsWith("a dungeon")) big.add(x.label().replace("'s chest", "'s treasure"));
        }
        if (p.stored.getOrDefault("diamond", 0) > 0) big.add(0, "diamonds");
        String haulWords = words(p.stored, 4);
        bookHaul(id, day, names, p.stored, p.torches, p.mined, p.drawn, p.crafted.size(), p.days);
        String heading = p.caveKey != null ? Guide.direction(home, p.caveKey) : "round about";
        String story = story(level, p, leadName, names, heading, big, haulWords);
        Villages.tell(id, day, story);
        int total = 0;
        for (int n : p.stored.values()) total += n;
        if (p.days > 1.0 && (total >= 32 || p.stored.getOrDefault("diamond", 0) > 0)) {
            Villages.tell(id, day, "a great homecoming: the cave team is back from " + CaveTrips.daysWords(p.days).toLowerCase(Locale.ROOT)
                + " in the caves " + heading + " with " + haulWords + ", and the town turned out to see it");
        }
        for (String b : big.subList(0, Math.min(2, big.size()))) {
            if (b.equals("diamonds") || b.contains("mineshaft") || b.contains("dungeon") || b.contains("treasure") || b.contains("spawner")) {
                Villages.tell(id, day, "the cave dwellers found " + b);
            }
        }
        String last = "day " + (day + 1) + ": " + (p.days > 1.0 ? CaveTrips.daysWords(p.days).toLowerCase(Locale.ROOT) + " in " : "")
            + "the caves " + heading + " with " + (names.size() <= 1 ? "nobody else" : JobMarket.join(names))
            + " — " + (p.stored.isEmpty() ? "nothing to bring home" : haulWords)
            + (big.isEmpty() ? "" : "; found " + String.join(", ", big.subList(0, Math.min(2, big.size()))))
            + (p.slain > 0 ? "; " + p.slain + (p.slain == 1 ? " monster" : " monsters") + " fought off" : "")
            + (p.crafted.isEmpty() ? "" : "; " + String.join(", ", p.crafted.subList(0, Math.min(2, p.crafted.size()))))
            + "; " + p.torches + " torches set of " + p.drawn + " drawn (" + p.why + ")";
        for (UUID u : p.members) {
            Ledger.note(id, "caves.last/" + u, last);
            if (level.getEntity(u) instanceof VillageFolkEntity m) {
                if (!big.isEmpty()) m.persona().remember(day, "We went into the caves " + heading + " and found " + big.get(0), 4);
                else if (p.days > 1.0) m.persona().remember(day, "We were " + CaveTrips.daysWords(p.days).toLowerCase(Locale.ROOT)
                    + " down the caves " + heading + ", and camped under the rock", 3);
            }
        }
        if (!big.isEmpty() || !p.stored.isEmpty()) {
            REPORTS.computeIfAbsent(id, k -> new ArrayList<>()).add("Our cave dwellers brought up " + (p.stored.isEmpty() ? "nothing" : haulWords)
                + (big.isEmpty() ? "" : " and found " + big.get(0)) + ".");
        }
        Entity lead = p.leader == null ? null : level.getEntity(p.leader);
        if (lead instanceof VillageFolkEntity l) {
            FolkTalk.speak(l, p.stored.isEmpty() ? "Home, all of us. Nothing worth the carrying today, but the list's the longer for it."
                : big.isEmpty() ? "Home, all of us, and " + haulWords + " in the storehouse." : "Home! We found " + big.get(0) + "!");
        }
        // The trip is over: the town's record of it closed (and any search for the team called off: SearchParties).
        Map<UUID, BlockPos> beds = AWAY_BEDS.get(id);
        if (beds != null) for (UUID u : p.members) beds.remove(u);
        CaveTrips.Trip t = CaveTrips.trip(id);
        if (t != null && t.start() == p.startTime) CaveTrips.end(id);
        LOG.info("[MCA-CAVES] the team of {} home ({}): {}; mined {}, chests {}, slain {}, torches {} set of {} drawn ({} back, {} made), "
            + "caves {}, nights {}, crafted {}, {} finds ({} new); stored {}; spread {}", Villages.name(id), p.why, names, p.mined, p.opened,
            p.slain, p.torches, p.drawn, p.returned, p.torchesMade, p.caves, p.nights, p.crafted, p.found.size(), fresh, p.stored,
            String.format(Locale.ROOT, "%.1f", p.spread));
    }

    /**
     * The trip as a small story for the chronicle: "Bram led Fen and Ada two days into the deep caves north-east: they
     * listed 9 veins and mined 31 ore, cut in to 2 veins hidden in the rock, fought off 3 monsters, lit a spawner up,
     * camped a night under the rock, and Fen made a stone pickaxe; home (the days we planned were up) with 14 raw iron,
     * 6 coal and a diamond, into the storehouse; 23 torches set of 64 drawn."
     */
    static String story(ServerLevel level, Party p, @Nullable String leadName, List<String> names, String heading, List<String> big, String haulWords) {
        String lead = leadName != null ? leadName : names.isEmpty() ? "the cave team" : names.get(0);
        List<String> others = new ArrayList<>(names);
        others.remove(lead);
        StringBuilder sb = new StringBuilder(lead);
        sb.append(others.isEmpty() ? " went alone" : " led " + JobMarket.join(others));
        sb.append(p.days > 1.0 ? " " + CaveTrips.daysWords(p.days).toLowerCase(Locale.ROOT) + " into " : " into ");
        Find cave = p.caveKey == null ? null : caveNear(p.village, p.caveKey);
        sb.append(cave == null ? "the caves " + heading : "the " + cave.label().replaceFirst("^an? ", "") + " " + heading);
        int listed = p.caveKey == null ? 0 : veins(p.village, p.caveKey).size();
        List<String> did = new ArrayList<>();
        if (listed > 0) did.add("listed " + listed + (listed == 1 ? " vein" : " veins"));
        if (p.mined > 0) did.add("mined " + p.mined + " ore");
        if (p.hidden > 0) did.add("cut in to " + (p.hidden == 1 ? "a vein" : p.hidden + " veins") + " hidden in the rock");
        if (p.slain > 0) did.add("fought off " + p.slain + (p.slain == 1 ? " monster" : " monsters"));
        if (p.lit > 0) did.add("lit " + (p.lit == 1 ? "a spawner" : p.lit + " spawners") + " up");
        if (p.opened > 0) did.add("opened " + (p.opened == 1 ? "an old chest" : p.opened + " old chests"));
        if (p.walled > 0) did.add("walled off the lava and water in their way");
        if (p.nights > 0) did.add("camped " + (p.nights == 1 ? "a night" : p.nights + " nights") + " under the rock");
        for (String c : p.crafted.subList(0, Math.min(2, p.crafted.size()))) did.add(c);
        if (!big.isEmpty()) did.add("found " + String.join(" and ", big.subList(0, Math.min(2, big.size()))));
        if (!did.isEmpty()) {
            sb.append(": they ");
            if (did.size() == 1) sb.append(did.get(0));
            else sb.append(String.join(", ", did.subList(0, did.size() - 1))).append(", and ").append(did.get(did.size() - 1));
        }
        sb.append("; home").append(p.why.isEmpty() ? "" : " (" + p.why + ")").append(" with ")
            .append(p.stored.isEmpty() ? "nothing worth the carrying" : haulWords + ", into the storehouse")
            .append("; ").append(p.torches).append(" torches set of ").append(p.drawn).append(" drawn");
        return sb.toString();
    }

    /** The report's cave or ravine within twenty blocks of a spot (its veins' list is kept by it), or null. */
    @Nullable
    public static Find caveNear(UUID village, BlockPos at) {
        Find best = null;
        for (Find x : report(village)) {
            if (x.kind() != Kind.CAVE && x.kind() != Kind.RAVINE || x.at().distSqr(at) > 20 * 20) continue;
            if (best == null || x.at().distSqr(at) < best.at().distSqr(at)) best = x;
        }
        return best;
    }

    private static String away(BlockPos home, BlockPos at) {
        return (int) Math.sqrt(Scouts.flat(home, at)) / 10 * 10 + " blocks " + Guide.direction(home, at);
    }

    /** "6 raw iron, 3 coal, a diamond": the most of a haul first. */
    static String words(Map<String, Integer> what, int most) {
        List<Map.Entry<String, Integer>> all = new ArrayList<>(what.entrySet());
        all.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> en : all) {
            if (out.size() >= most) break;
            out.add(en.getValue() == 1 ? JobMarket.a(en.getKey()) : en.getValue() + " " + en.getKey());
        }
        if (all.size() > most) out.add("more");
        return String.join(", ", out);
    }

    /** The trip's haul, booked to the team, kept with the town (the last ten trips): what went into the storehouse, the
     *  torches set and drawn, the ore mined, what it crafted, the days out. */
    private static void bookHaul(UUID village, long day, List<String> who, Map<String, Integer> stored, int torches, int mined, int drawn,
                                 int crafted, double days) {
        String s = Ledger.note(village, "caves.haul");
        List<String> lines = new ArrayList<>();
        if (s != null && !s.isEmpty()) lines.addAll(List.of(s.split("\n")));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> en : stored.entrySet()) sb.append(sb.length() == 0 ? "" : ",").append(en.getKey().replace(',', ' ')).append('*').append(en.getValue());
        lines.add(day + "|" + String.join(" and ", who).replace('|', '/') + "|" + sb + "|" + torches + "|" + mined + "|" + drawn + "|" + crafted + "|" + days);
        while (lines.size() > 10) lines.remove(0);
        Ledger.note(village, "caves.haul", String.join("\n", lines));
    }

    /** The hauls the town keeps, newest first: "Day 12, the team (Bram and Fen), two days: 6 raw iron, 3 coal; 9 ore
     *  mined; 14 torches set of 64 drawn; 2 things crafted". */
    public static List<String> hauls(UUID village) {
        List<String> out = new ArrayList<>();
        String s = Ledger.note(village, "caves.haul");
        if (s == null || s.isEmpty()) return out;
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String[] p = lines[i].split("\\|", -1);
            if (p.length < 3) continue;
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
            try {
                day = Long.parseLong(p[0]);
            } catch (NumberFormatException ex) {
                continue;
            }
            String days = "";
            if (p.length >= 8) {
                try {
                    double d = Double.parseDouble(p[7]);
                    days = d == 1.0 ? "" : ", " + CaveTrips.daysWords(d).toLowerCase(Locale.ROOT);
                } catch (NumberFormatException ignored) {
                    // a day, as the older lines were
                }
            }
            String extra = p.length >= 5 ? "; " + p[4] + " ore mined; " + p[3] + " torches set" + (p.length >= 6 ? " of " + p[5] + " drawn" : "") : "";
            if (p.length >= 7 && !p[6].equals("0")) extra += "; " + p[6] + (p[6].equals("1") ? " thing" : " things") + " crafted down there";
            out.add("Day " + (day + 1) + ", the team (" + p[1] + ")" + days + ": " + (m.isEmpty() ? "nothing worth the carrying" : words(m, 6)) + extra);
        }
        return out;
    }

    /** The torches the team has drawn and set over the trips the town keeps: {drawn, set}. */
    public static int[] torchTotals(UUID village) {
        int[] out = new int[2];
        String s = Ledger.note(village, "caves.haul");
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            String[] p = line.split("\\|", -1);
            try {
                if (p.length >= 6) out[0] += Integer.parseInt(p[5]);
                if (p.length >= 4) out[1] += Integer.parseInt(p[3]);
            } catch (NumberFormatException ignored) {
                // an unreadable line: left out
            }
        }
        return out;
    }

    /** For the morning assembly: what the cave dwellers brought up (said once). */
    public static List<String> reports(UUID village) {
        List<String> list = REPORTS.remove(village);
        return list == null ? List.of() : list;
    }

    // ------------------------------------------------------------------ the report

    /** Everything the town's cave dwellers have found, oldest first. */
    public static List<Find> report(UUID village) {
        List<Find> out = new ArrayList<>();
        String s = Ledger.note(village, "caves");
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            Find f = Find.decode(line);
            if (f != null) out.add(f);
        }
        return out;
    }

    static void save(UUID village, List<Find> finds) {
        while (finds.size() > KEEP) {
            // Forget the least of it first: lava, then the chests looked into, then the oldest veins.
            int drop = 0;
            for (Kind k : new Kind[]{ Kind.LAVA, Kind.CHEST, Kind.VEIN }) {
                int at = -1;
                for (int i = 0; i < finds.size(); i++) if (finds.get(i).kind() == k) { at = i; break; }
                if (at >= 0) { drop = at; break; }
            }
            finds.remove(drop);
        }
        StringBuilder sb = new StringBuilder();
        for (Find f : finds) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(f.encode());
        }
        Ledger.note(village, "caves", sb.toString());
    }

    /** Into the town's report: new, or folded into what it holds there already. Returns whether it was new. */
    static boolean record(UUID village, Find f) {
        List<Find> all = report(village);
        boolean fresh = merge(all, f);
        save(village, all);
        return fresh;
    }

    /** Counts by kind: {caves, ravines, veins, ore mined, mineshafts, dungeons, spawners, structures, chests, lava}. */
    static int[] counts(List<Find> all) {
        int[] n = new int[10];
        for (Find x : all) {
            switch (x.kind()) {
                case CAVE -> n[0]++;
                case RAVINE -> n[1]++;
                case VEIN -> { n[2]++; n[3] += x.b(); }
                case MINESHAFT -> n[4]++;
                case DUNGEON -> n[5]++;
                case SPAWNER -> n[6]++;
                case STRUCTURE -> n[7]++;
                case CHEST -> n[8]++;
                case LAVA -> n[9]++;
            }
        }
        return n;
    }

    /** "4 caves, 6 veins (31 ore mined), a mineshaft, 2 spawners": the report in a line. */
    static String summary(List<Find> all) {
        int[] n = counts(all);
        List<String> out = new ArrayList<>();
        if (n[0] > 0) out.add(n[0] + (n[0] == 1 ? " cave" : " caves"));
        if (n[1] > 0) out.add(n[1] + (n[1] == 1 ? " ravine" : " ravines"));
        if (n[2] > 0) out.add(n[2] + (n[2] == 1 ? " vein" : " veins") + " (" + n[3] + " ore mined)");
        if (n[4] > 0) out.add(n[4] + (n[4] == 1 ? " mineshaft" : " mineshafts"));
        if (n[5] > 0) out.add(n[5] + (n[5] == 1 ? " dungeon" : " dungeons"));
        if (n[6] > 0) out.add(n[6] + (n[6] == 1 ? " spawner" : " spawners"));
        if (n[7] > 0) out.add(n[7] + " old " + (n[7] == 1 ? "structure" : "structures"));
        if (n[8] > 0) out.add(n[8] + (n[8] == 1 ? " chest" : " chests") + " looked into");
        if (n[9] > 0) out.add(n[9] + " lava " + (n[9] == 1 ? "pool" : "pools"));
        return out.isEmpty() ? "nothing yet" : String.join(", ", out);
    }

    /** For the board: the report in a line, and the latest big find. Null for a town with no cave dwellers and no report. */
    @Nullable
    public static String boardLine(UUID village) {
        List<Find> all = report(village);
        List<VillageFolkEntity> out = dwellers(village);
        CaveTrips.Trip trip = CaveTrips.trip(village);
        if (trip != null && !out.isEmpty()) {
            long now = out.get(0).level().getDayTime();
            return "Caves: the team is out" + (trip.days() > 1.0 ? " (" + trip.dayOf(now) + ")" : "") + " — " + trip.words();
        }
        if (all.isEmpty() && out.isEmpty()) return null;
        if (all.isEmpty()) return "Caves: a team of " + out.size() + " cave " + (out.size() == 1 ? "dweller" : "dwellers") + " out exploring — nothing in the report yet.";
        Villages.Village v = Villages.get(village);
        BlockPos home = v == null ? BlockPos.ZERO : v.centre();
        String latest = null;
        for (int i = all.size() - 1; i >= 0 && latest == null; i--) {
            Find x = all.get(i);
            if (x.kind() == Kind.MINESHAFT || x.kind() == Kind.DUNGEON || x.kind() == Kind.STRUCTURE || x.kind() == Kind.SPAWNER
                || x.kind() == Kind.VEIN && ("diamond".equals(x.label()) || "emerald".equals(x.label()))) {
                latest = (x.kind() == Kind.VEIN ? x.label() + "s" : x.label()) + " " + away(home, x.at());
            }
        }
        return "Caves: " + summary(all) + (latest == null ? "" : "; lately " + latest) + ".";
    }

    /** "What have the cave dwellers found?": anybody can say; a cave dweller gives the very spot. */
    public static String tell(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return "I've no town to keep a report for.";
        List<Find> all = report(village);
        boolean me = f.stationTask() == StationTask.CAVE;
        if (all.isEmpty()) {
            if (wanted(village) == 0 && dwellers(village).isEmpty()) {
                return "Nobody goes down the caves for us yet: that's for an Iron Age town of " + FROM + " or more, with miners and a watch.";
            }
            return me ? "Nothing in the report yet. Give the team a day or two down there." : "The cave dwellers haven't come back with anything yet.";
        }
        BlockPos from = f.blockPosition();
        List<Find> worth = new ArrayList<>();
        for (Find x : all) if (x.kind() != Kind.CHEST) worth.add(x);
        worth.sort(Comparator.comparingInt((Find x) -> rank(x)).thenComparingDouble(x -> x.at().distSqr(from)));
        StringBuilder sb = new StringBuilder(me ? "From the caves' report: " : "The cave dwellers have found: ");
        int n = 0;
        for (Find x : worth) {
            if (n == 5) break;
            if (n > 0) sb.append("; ");
            String what = x.kind() == Kind.VEIN ? x.label() + " (" + x.b() + " of " + x.a() + " mined)" : x.label();
            sb.append(what).append(", ").append((int) Math.sqrt(x.at().distSqr(from)) / 10 * 10).append(" blocks ").append(Guide.direction(from, x.at()));
            if (me) sb.append(" (").append(x.at().getX()).append(", ").append(x.at().getY()).append(", ").append(x.at().getZ()).append(")");
            n++;
        }
        sb.append(". All told: ").append(summary(all)).append('.');
        Scouts.Expedition e = f.expedition();
        if (e != null && e.delve != null) sb.append(" I'm down in the caves ").append(e.heading).append(" with the team right now.");
        return sb.toString();
    }

    private static int rank(Find x) {
        return switch (x.kind()) {
            case MINESHAFT, DUNGEON, STRUCTURE -> 0;
            case VEIN -> "diamond".equals(x.label()) || "emerald".equals(x.label()) ? 0 : 2;
            case SPAWNER, RAVINE, CAVE -> 1;
            case LAVA -> 3;
            case CHEST -> 4;
        };
    }

    /** What it is doing, for "What are you up to?". */
    public static String doing(VillageFolkEntity f, net.minecraft.util.RandomSource r) {
        Scouts.Expedition e = f.expedition();
        if (e != null && e.delve != null) {
            Party p = e.delve.party;
            if (p.homeward) return "On my way home from the caves " + e.heading + " with the team" + (p.mined > 0 ? ", " + p.mined + " ore between us." : ".");
            if (p.phase == Party.Phase.OUT) return "Off to the caves " + e.heading + " with the team. Torches, picks and blades — all here.";
            if (p.veinOf != null) return "Mining " + p.veinOf + " with the team. Stand back from the wall.";
            return FolkTalk.pick(r, "Down in the caves " + e.heading + " with the team. Mind your head — and keep your voice down.",
                "Exploring. " + (p.torches > 0 ? p.torches + " torches set so far: that's the way home." : "It's dark as a cellar down here."));
        }
        if (LOST.containsKey(f.getUUID())) return "Lost! I couldn't find the way out of the caves. Somebody's coming for me — I hope.";
        String last = Ledger.note(f.ownerId(), "caves.last/" + f.getUUID());
        return last != null ? "Resting my legs. Last time down: " + last.substring(last.indexOf(':') + 1).trim() + "."
            : FolkTalk.pick(r, "Getting my kit ready for the caves. Out at first light with the team.", "Going over the report. There's a lot of dark under this town.");
    }

    /** The cave dweller's card: its level and place in the team, where it went today and what it found, and its kit. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.CAVE || f.isBaby()) return null;
        StringBuilder sb = new StringBuilder();
        UUID village = f.ownerId();
        VillageFolkEntity leader = village == null ? null : leaderOf(village);
        sb.append("level ").append(f.tradeLevel(StationTask.CAVE)).append(leader == f ? ", leads the team" : leader != null ? ", in " + leader.displayNameCap() + "'s team" : "");
        Scouts.Expedition e = f.expedition();
        if (e != null && e.delve != null) {
            Party p = e.delve.party;
            sb.append("; ").append(p.days > 1.0 ? "on an expedition, " + p.dayOf(f.level().getDayTime()) + ", " : "")
                .append(p.homeward ? "on the way home from the caves " : "out in the caves ").append(e.heading).append(" (").append(p.phase()).append(")");
            if (p.mined + p.opened + p.slain > 0) sb.append(": ").append(p.mined).append(" ore mined, ").append(p.opened).append(" chests, ").append(p.slain).append(" fought off");
            sb.append(", ").append(p.torches).append(" torches set of ").append(p.drawn).append(" drawn");
            if (!p.haul.isEmpty()) sb.append("; carrying ").append(words(p.haul, 3));
        } else {
            String last = village == null ? null : Ledger.note(village, "caves.last/" + f.getUUID());
            sb.append("; ").append(last != null ? "last out " + last : "not been down yet");
        }
        String kit = kitWords(f);
        if (!kit.isEmpty()) sb.append(". Kit: ").append(kit).append(", the town's");
        return sb.toString();
    }

    /** What it has of its kit, in a line: "4 pieces of iron armour, iron sword, iron pickaxe, a shield, 64 torches". */
    static String kitWords(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        int armour = 0;
        String metal = null;
        for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            ItemStack s = f.getItemBySlot(slot);
            if (s.getItem() instanceof ArmorItem) {
                armour++;
                if (metal == null) metal = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().split("_")[0];
            }
        }
        if (armour > 0) out.add(armour + (armour == 1 ? " piece" : " pieces") + " of " + metal + " armour");
        ItemStack blade = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) if (s.getItem() instanceof SwordItem && Workshop.blade(s) > Workshop.blade(blade)) blade = s;
        if (f.getMainHandItem().getItem() instanceof SwordItem && Workshop.blade(f.getMainHandItem()) > Workshop.blade(blade)) blade = f.getMainHandItem();
        if (!blade.isEmpty()) out.add(blade.getHoverName().getString().toLowerCase(Locale.ROOT));
        ItemStack pick = bestPick(f);
        if (!pick.isEmpty()) out.add(pick.getHoverName().getString().toLowerCase(Locale.ROOT));
        if (f.countCarried(s -> s.getItem() instanceof ShieldItem) > 0 || f.getOffhandItem().getItem() instanceof ShieldItem) out.add("a shield");
        int torches = f.countMatching(s -> s.is(Items.TORCH));
        if (torches > 0) out.add(torches + " torches");
        return String.join(", ", out);
    }

    /** The Caves page of the town's books (CityScreen's "Caves", client/CavesPage). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        List<Find> all = report(id);
        out.putInt("range", range(id));
        out.putInt("wanted", wanted(id));
        out.putInt("from", FROM);
        out.putString("age", Villages.ageOf(id).label);
        out.putString("summary", summary(all));
        // The plan (the one under way, or the last), its reckoning, and the trip as it stands.
        String plan = Ledger.note(id, "caves.plan");
        out.putString("plan", plan == null ? "" : plan);
        ListTag reck = new ListTag();
        String r = Ledger.note(id, "caves.reckoning");
        if (r != null && !r.isEmpty()) for (String line : r.split("\n")) reck.add(StringTag.valueOf(line));
        out.put("reckoning", reck);
        CaveTrips.Trip trip = CaveTrips.trip(id);
        out.putString("trip", trip == null ? "" : "out now" + (trip.days() > 1.0 ? ", on an expedition, " + trip.dayOf(level.getDayTime()) : ", a "
            + (trip.days() < 1.0 ? "half-day" : "day") + " trip"));
        int[] torches = torchTotals(id);
        out.putInt("torchesDrawn", torches[0]);
        out.putInt("torchesSet", torches[1]);
        ListTag finds = new ListTag();
        ListTag caves = new ListTag();
        for (Find x : all) {
            CompoundTag t = new CompoundTag();
            t.putString("kind", x.kind().name());
            t.putString("label", x.label());
            t.putInt("x", x.at().getX());
            t.putInt("y", x.at().getY());
            t.putInt("z", x.at().getZ());
            t.putInt("dx", x.at().getX() - v.centre().getX());
            t.putInt("dz", x.at().getZ() - v.centre().getZ());
            t.putInt("a", x.a());
            t.putInt("b", x.b());
            t.putLong("day", x.day());
            t.putString("by", x.by());
            finds.add(t);
            if (x.kind() != Kind.CAVE && x.kind() != Kind.RAVINE) continue;
            // The cave's veins: mined, waiting for a better pick, still to do.
            CompoundTag c = new CompoundTag();
            c.putString("label", x.label() + " " + away(v.centre(), x.at()) + " (" + x.at().getX() + " " + x.at().getY() + " " + x.at().getZ() + ")"
                + (done(id, x.at()) ? ", worked out" : ""));
            ListTag vl = new ListTag();
            int mined = 0, waiting = 0, todo = 0;
            for (Vein vein : veins(id, x.at())) {
                vl.add(StringTag.valueOf(vein.words()));
                switch (vein.state()) {
                    case "mined" -> mined++;
                    case "waiting" -> waiting++;
                    case "todo" -> todo++;
                    default -> { }
                }
            }
            c.put("veins", vl);
            c.putInt("mined", mined);
            c.putInt("waiting", waiting);
            c.putInt("todo", todo);
            caves.add(c);
        }
        out.put("finds", finds);
        out.put("caves", caves);
        ListTag folk = new ListTag();
        VillageFolkEntity leader = leaderOf(id);
        for (VillageFolkEntity f : dwellers(id)) {
            CompoundTag t = new CompoundTag();
            t.putString("name", f.displayNameCap());
            t.putInt("level", f.tradeLevel(StationTask.CAVE));
            t.putBoolean("leader", f == leader);
            String card = cardLine(f);
            t.putString("card", card == null ? "" : card);
            Scouts.Expedition e = f.expedition();
            t.putBoolean("out", e != null && e.delve != null);
            if (e != null && e.delve != null) {
                t.putInt("dx", f.blockPosition().getX() - v.centre().getX());
                t.putInt("dz", f.blockPosition().getZ() - v.centre().getZ());
            }
            folk.add(t);
        }
        out.put("dwellers", folk);
        ListTag hauls = new ListTag();
        for (String h : hauls(id)) hauls.add(StringTag.valueOf(h));
        out.put("hauls", hauls);
        return out;
    }

    /** The whole of it, for /village caves: the team, each cave's veins, the finds with their spots, the hauls. */
    public static List<String> page(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        List<VillageFolkEntity> folk = dwellers(id);
        int want = wanted(id);
        out.add("The caves of " + Villages.name(id) + " (" + Villages.ageOf(id).label + ", " + Villages.headcount(id) + " folk): a team of "
            + folk.size() + ", " + want + " wanted" + (want == 0 ? " (from " + FROM + " folk in the Iron Age, with " + MINERS + " miners and a guard)" : "")
            + "; out to " + range(id) + " blocks.");
        for (VillageFolkEntity f : folk) out.add("  " + f.displayNameCap() + ": " + cardLine(f));
        CaveTrips.Trip trip = CaveTrips.trip(id);
        String plan = Ledger.note(id, "caves.plan");
        if (plan != null && !plan.isEmpty()) {
            out.add((trip != null ? "Out now" + (trip.days() > 1.0 ? " (" + trip.dayOf(level.getDayTime()) + ")" : "") + ": " : "The last plan: ") + plan);
            String r = Ledger.note(id, "caves.reckoning");
            if (r != null && !r.isEmpty()) for (String line : r.split("\n")) out.add("    " + line);
        }
        int[] torches = torchTotals(id);
        if (torches[0] + torches[1] > 0) out.add("Torches, the last ten trips: " + torches[0] + " drawn, " + torches[1] + " set.");
        List<Find> all = report(id);
        out.add("The report: " + summary(all) + ".");
        for (Find x : all) {
            if (x.kind() != Kind.CAVE && x.kind() != Kind.RAVINE) continue;
            List<Vein> vs = veins(id, x.at());
            out.add("  " + x.label() + " at " + x.at().getX() + " " + x.at().getY() + " " + x.at().getZ() + (done(id, x.at()) ? ", worked out" : "")
                + (vs.isEmpty() ? ": no veins listed" : ":"));
            for (Vein vein : vs) out.add("    " + vein.words() + " (" + vein.at().getX() + " " + vein.at().getY() + " " + vein.at().getZ() + ")");
        }
        List<Find> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt(CaveDwellers::rank).thenComparingDouble(x -> x.at().distSqr(v.centre())));
        int n = 0;
        for (Find x : sorted) {
            if (n++ >= 24) { out.add("  ... and " + (sorted.size() - 24) + " more"); break; }
            String extra = switch (x.kind()) {
                case CAVE, RAVINE -> ", " + x.a() + " deep, " + x.b() + " open blocks";
                case VEIN -> ": " + x.a() + " seen, " + x.b() + " mined";
                case CHEST -> ": " + x.a() + " things taken";
                case MINESHAFT, STRUCTURE -> x.a() > 0 ? ": " + x.a() + " chests looked into" : "";
                case LAVA -> ", " + x.a() + " deep";
                default -> "";
            };
            out.add("  " + x.kind().name().toLowerCase(Locale.ROOT) + " " + x.label() + extra + " at " + x.at().getX() + " " + x.at().getY() + " "
                + x.at().getZ() + " (" + away(v.centre(), x.at()) + ", day " + (x.day() + 1) + ", " + x.by() + ")");
        }
        List<String> h = hauls(id);
        if (!h.isEmpty()) {
            out.add("The hauls:");
            for (String s : h.subList(0, Math.min(5, h.size()))) out.add("  " + s);
        }
        return out;
    }

    /** Tests and /village: the report in a line. */
    public static String debug(UUID village) {
        StringBuilder sb = new StringBuilder("caves report " + report(village).size());
        for (Find x : report(village)) sb.append("; ").append(x.kind().name().toLowerCase(Locale.ROOT)).append(' ').append(x.label())
            .append(" (").append(x.a()).append('/').append(x.b()).append(')');
        return sb.toString();
    }

    /** Tests: a cave's veins in a line ("iron 5 mined; obsidian 2 waiting"). */
    public static String veinsForTests(UUID village, BlockPos cave) {
        StringBuilder sb = new StringBuilder();
        for (Vein v : veins(village, cave)) sb.append(sb.length() == 0 ? "" : "; ").append(v.ore()).append(' ').append(v.size()).append(' ').append(v.state());
        return sb.toString();
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the command

    /**
     * /village caves: the town's team, each cave's veins, the report with its spots, the hauls. {@code books} opens the
     * town's books at the Caves page. Operators: {@code now} sends the team into the caves now (taking it up first, if
     * the town wants one and has none); {@code stage} cuts a small cave out beside where you stand, with ore in and
     * behind its walls and an old chest, and sends the town's team into it, for the pictures.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("caves")
            .executes(CaveDwellers::cmdPage)
            .then(Commands.literal("books").executes(CaveDwellers::cmdBooks))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                List<String> said = new ArrayList<>();
                long day = level.getDayTime() / 24000L;
                for (int i = 0; i < MOST && dwellers(v.id()).size() < wanted(v.id()); i++) {
                    VillageFolkEntity f = appoint(level, v, day);
                    if (f == null) break;
                    said.add(f.displayNameCap() + " joined the team.");
                }
                for (VillageFolkEntity f : dwellers(v.id())) WENT.remove(f.getUUID());
                Party p = setOut(level, v, day, null);
                said.add(p == null ? "Nobody could go (" + dwellers(v.id()).size() + " in the team, " + wanted(v.id()) + " wanted)."
                    : "The team set out: " + p.members.size() + " of them.");
                ctx.getSource().sendSuccess(() -> Component.literal("CAVES " + String.join(" ", said)), false);
                return said.size();
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                List<String> out = stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int cmdPage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        List<String> lines = page(ctx.getSource().getLevel(), v);
        String text = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    private static int cmdBooks(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return cmdPage(ctx);
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Caves");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    // ------------------------------------------------------------------ the stage

    /** The tag on the stage's cave dweller in its kit at the cave mouth (a showcase's kit, for nothing). */
    static final String LINEUP = "caves_lineup";

    /**
     * The pictures' stage: a small cave cut into a block of stone twelve blocks along from where it is run (east), a
     * ramp down into it from the west, ore in its walls (iron, coal, copper, a diamond, a little obsidian, and iron two
     * blocks behind the south wall) and an old chest with the world's dungeon loot in it; a cave dweller in the watch's
     * iron kit stood at its mouth (a showcase's, for nothing); and the town's own team (taken up if it has none) sent
     * into it. Returns the views to photograph ("VIEW name x y z lookx looky lookz") and where the cave is.
     */
    static List<String> stage(ServerLevel level, BlockPos at) {
        List<Entity> old = new ArrayList<>();
        for (Entity en : level.getAllEntities()) if (en.getTags().contains(LINEUP)) old.add(en);
        for (Entity en : old) en.discard();
        int x0 = at.getX() + 12, z0 = at.getZ();
        int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + 12, z0);
        // The rock: stone from the surface down, twenty-five along and seventeen across.
        for (int x = x0; x <= x0 + 24; x++) {
            for (int z = z0 - 8; z <= z0 + 8; z++) {
                for (int y = g - 13; y <= g - 1; y++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = g; y <= g + 6; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        cut(level, x0, z0, g);
        BlockPos mouth = new BlockPos(x0 - 1, g, z0);
        BlockPos chamber = new BlockPos(x0 + 14, g - 7, z0);
        List<String> out = new ArrayList<>();
        out.add("CAVE " + chamber.getX() + " " + chamber.getY() + " " + chamber.getZ() + " mouth " + mouth.getX() + " " + mouth.getY() + " " + mouth.getZ());
        // The cave dweller in its kit at the mouth, for the first picture.
        VillageFolkEntity show = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (show != null) {
            show.moveTo(mouth.getX() - 1.5, mouth.getY(), mouth.getZ() + 1.5, -90.0F, 0.0F);
            show.setYHeadRot(-90.0F);
            show.setYBodyRot(-90.0F);
            show.makeShowcase(StationTask.CAVE);
            show.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            show.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            show.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            show.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            show.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
            show.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH));
            show.rename("A cave dweller");
            show.addTag(LINEUP);
            level.addFreshEntity(show);
        }
        out.add("VIEW caves-1-mouth " + (mouth.getX() - 6) + " " + (mouth.getY() + 2) + " " + (mouth.getZ() + 5) + " "
            + (mouth.getX() - 1) + " " + (mouth.getY() + 1) + " " + (mouth.getZ() + 1));
        out.add("VIEW caves-2-vein " + (x0 + 14) + " " + (g - 6) + " " + (z0 + 3) + " " + (x0 + 21) + " " + (g - 6) + " " + z0);
        out.add("VIEW caves-3-chest " + (x0 + 12) + " " + (g - 6) + " " + (z0 + 1) + " " + (x0 + 10) + " " + (g - 7) + " " + (z0 - 5));
        // The town's team, into it.
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        if (v != null) {
            long day = level.getDayTime() / 24000L;
            for (int i = 0; i < MOST && dwellers(v.id()).size() < wanted(v.id()); i++) if (appoint(level, v, day) == null) break;
            if (dwellers(v.id()).isEmpty()) {
                // A town too small to want a team: the nearest grown hand not on the watch goes, for the pictures.
                for (AssistantEntity a : Villages.folkOf(v.id())) {
                    if (a instanceof VillageFolkEntity c && !c.isBaby() && !c.isShowcase() && c.stationTask() != StationTask.GUARD && c.expedition() == null) {
                        BlockPos post = post(v);
                        c.setStation(post, StationTask.CAVE);
                        c.assignPlot(WorkZone.around(post, 4, WorkZone.DEFAULT_DEPTH), "The Caves");
                        break;
                    }
                }
            }
            List<VillageFolkEntity> team = dwellers(v.id());
            if (!team.isEmpty()) {
                boolean sent = sendForTests(team.get(0), level, chamber);
                out.add("TEAM " + team.size() + (sent ? " sent into the cave" : " could not go") + ", " + team.get(0).displayNameCap() + " from "
                    + team.get(0).blockPosition().getX() + " " + team.get(0).blockPosition().getY() + " " + team.get(0).blockPosition().getZ());
            }
        }
        return out;
    }

    /** The cave itself: a ramp down from the west, a chamber thirteen by eleven and four high, ore in and behind its walls,
     *  an old chest by its north wall. */
    private static void cut(ServerLevel level, int x0, int z0, int g) {
        BlockState air = Blocks.AIR.defaultBlockState();
        // A level way up to the mouth from the west, whatever the ground does there.
        for (int x = x0 - 6; x <= x0 - 1; x++) {
            for (int z = z0 - 2; z <= z0 + 1; z++) {
                level.setBlock(new BlockPos(x, g - 1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = g; y <= g + 3; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
            }
        }
        // The ramp: seven steps down, two wide, three high.
        for (int k = 1; k <= 7; k++) {
            int x = x0 - 1 + k;
            for (int z = z0 - 1; z <= z0; z++) {
                for (int y = g - k; y <= g - k + 2; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
            }
        }
        // The chamber.
        for (int x = x0 + 7; x <= x0 + 19; x++) {
            for (int z = z0 - 5; z <= z0 + 5; z++) {
                for (int y = g - 7; y <= g - 4; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
            }
        }
        // Ore in the walls: iron on the east, coal and copper on the south (and iron two behind it), a diamond and
        // obsidian on the north.
        int e = x0 + 20;
        for (int[] o : new int[][]{ { e, g - 6, z0 - 1 }, { e, g - 6, z0 }, { e, g - 5, z0 }, { e, g - 5, z0 + 1 }, { e + 1, g - 6, z0 } }) {
            level.setBlock(new BlockPos(o[0], o[1], o[2]), Blocks.IRON_ORE.defaultBlockState(), 2);
        }
        for (int x = x0 + 9; x <= x0 + 11; x++) level.setBlock(new BlockPos(x, g - 6, z0 + 6), Blocks.COAL_ORE.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x0 + 14, g - 5, z0 + 6), Blocks.COPPER_ORE.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x0 + 17, g - 6, z0 + 8), Blocks.IRON_ORE.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x0 + 16, g - 6, z0 - 6), Blocks.DIAMOND_ORE.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x0 + 13, g - 6, z0 - 6), Blocks.OBSIDIAN.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x0 + 14, g - 6, z0 - 6), Blocks.OBSIDIAN.defaultBlockState(), 2);
        // The old chest, with the world's dungeon loot in it still to be rolled.
        BlockPos chest = new BlockPos(x0 + 10, g - 7, z0 - 5);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), 2);
        if (level.getBlockEntity(chest) instanceof ChestBlockEntity c) c.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, level.getRandom().nextLong());
    }

    /** A trip under way, taken up again after a restart (CaveTrips.resume): the party as the town kept it. */
    static Party resumedParty(ServerLevel level, Villages.Village v, CaveTrips.Trip t) {
        Party p = new Party(v.id(), t.start() / 24000L);
        p.leader = t.leader();
        p.members.addAll(t.members());
        p.startTime = t.start();
        p.turnAt = t.turnAt();
        p.days = t.days();
        p.nights = t.nights();
        p.searched = t.searched();
        p.planWords = t.words();
        p.caveKey = t.caveKey();
        p.cave = t.cave() != null ? t.cave() : t.caveKey();
        p.surveyTick = p.workTick = level.getGameTime();
        return p;
    }

    /** One of the team back on the trip it was on, from where it stands. */
    static void rejoin(ServerLevel level, VillageFolkEntity f, Villages.Village v, Party p) {
        BlockPos at = f.blockPosition();
        if (p.trail.isEmpty()) {
            p.trail.add(at.immutable());
            if (MineStairs.underground(level, at) && p.caveKey != null) {
                p.phase = Party.Phase.IN;
                p.mouth = at.immutable();
                p.mouthCrumb = 0;
            }
        }
        BlockPos target = p.cave != null ? p.cave : at;
        Scouts.Expedition e = new Scouts.Expedition(v.id(), v.centre(),
            Scouts.bearingOf(target.getX() - v.centre().getX(), target.getZ() - v.centre().getZ()), target);
        e.delve = new Delve(p);
        e.startedTick = e.gainedTick = e.surveyTick = f.tickCount;
        e.trail.add(at.immutable());
        f.clearQueue();
        f.expedition(e);
        p.startSize = Math.max(p.startSize, members(level, p).size());
        f.brain("took the cave team's trip up again where it left off");
    }

    /** Tests: a cave (or a ravine) into the town's report, as if the team had found it. */
    public static void recordForTests(UUID village, BlockPos at, String label, int deep, int open) {
        record(village, new Find(label.contains("ravine") ? Kind.RAVINE : Kind.CAVE, label, at, 0, "a test", deep, open));
    }

    /** Tests: a cave's list of veins, as if the team had looked it over. */
    public static void listForTests(UUID village, BlockPos cave, List<Vein> list) {
        saveVeins(village, cave, new ArrayList<>(list));
    }

    /** Tests: the trip a day overdue (its turn for home put back a day and a half). */
    public static void overdueForTests(ServerLevel level, VillageFolkEntity f) {
        Party p = partyOf(f);
        if (p == null) return;
        p.turnAt = level.getDayTime() - 36000L;
        CaveTrips.keep(p);
    }

    /** Tests: the stage's cave cut at this spot (its ramp from the west at x0, the surface at g). */
    public static void cutForTests(ServerLevel level, int x0, int z0, int g) {
        cut(level, x0, z0, g);
    }
}
