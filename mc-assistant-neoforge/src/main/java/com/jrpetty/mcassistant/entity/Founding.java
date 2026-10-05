package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.VillageSpawner;
import com.jrpetty.mcassistant.block.VillageBoardBlock;
import com.jrpetty.mcassistant.block.VillageBoardBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.village.FoundingPlan;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Founding a village of a size the player chooses.
 *
 * <p>A Village Folk Spawner set down where there is no village puts the village board up on
 * the spot and waits: nobody comes until somebody goes to the board, says how many are to
 * start the village (two to five hundred, client/FoundingScreen) and presses Confirm and
 * spawn. Then the ground is made ready for them — walked, planned (village/FoundingPlan) and
 * cut, filled and cleared a few thousand blocks a tick (Terraform), the heart first and then
 * outwards — and the moment the camp ground is level the first of them founds the village
 * there and the rest come after, a few a tick, while the outer ground is still being shaped.
 *
 * <p>All of it is kept with the world: this is the saved data the foundings live in. It goes
 * on with nobody there, holding loaded the ground it is working for as long as it works it,
 * and after a restart it walks the ground again and carries on from the column it had got to.
 * Only the level it chose, the soil and how far it had got are kept; the ground itself is
 * read afresh from the world.
 */
public final class Founding extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String ID = "mc_assistant_foundings";

    /** A board waiting for its founders to be chosen; a founding under way. */
    public static final int PENDING = 1, UNDER_WAY = 2;
    /** What a founding under way is doing: waiting for its ground to load, walking it, shaping it. */
    static final int LOAD = 0, SURVEY = 1, SHAPE = 2;

    /** Blocks moved a tick, at most, and never more than eight milliseconds' work. */
    public static final int BLOCKS_PER_TICK = 4096;
    public static final long TICK_NANOS = 8_000_000L;
    /** Columns of ground walked a tick. */
    public static final int SURVEY_PER_TICK = 8192;
    /** Folk who come a tick: five hundred at once would stall the server for a second. */
    public static final int FOLK_PER_TICK = 8;
    /** Chunks asked for at once that are not loaded yet (they come from disk or the generator). */
    private static final int IN_FLIGHT = 6;
    /** How near the board somebody must stand to choose, and how near to hear how it goes. */
    public static final int NEAR = 24, TELL = 160;

    /** A plain ticket that keeps the ground being worked loaded, renewed while the work goes on. */
    private static final TicketType<ChunkPos> HOLD = TicketType.create(
        "mc_assistant:founding", Comparator.comparingLong(ChunkPos::toLong), 600);
    private static final int HOLD_EVERY = 200;

    private static final int UNSET = Integer.MIN_VALUE;

    /** One founding: a board waiting, or the ground being made ready and the folk coming. */
    static final class Site {
        ResourceKey<Level> dim;
        BlockPos board;
        Direction facing;
        BlockPos heart;
        float yaw;
        @Nullable UUID founder;
        int state = PENDING;
        int count;
        long seed;
        int phase = LOAD;
        int level = UNSET;
        int radius, outer;
        int cursor, spawned, nextSpot;
        @Nullable UUID village;
        String soil = "";
        // Not kept: the ground as read, and when it was last held or told about.
        @Nullable FoundingPlan.Ground ground;
        @Nullable byte[] tops;
        int surveyed, waited;
        long held = -100000L;
        Block surface = Blocks.GRASS_BLOCK;

        boolean founded() {
            return village != null;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Dim", dim.location().toString());
            t.putLong("Board", board.asLong());
            t.putInt("Facing", facing.get2DDataValue());
            t.putLong("Heart", heart.asLong());
            t.putFloat("Yaw", yaw);
            if (founder != null) t.putUUID("Founder", founder);
            t.putInt("State", state);
            t.putInt("Count", count);
            t.putLong("Seed", seed);
            t.putInt("Level", level);
            t.putInt("Radius", radius);
            t.putInt("Outer", outer);
            t.putInt("Cursor", cursor);
            t.putInt("Spawned", spawned);
            t.putInt("Next", nextSpot);
            if (village != null) t.putUUID("Village", village);
            t.putString("Soil", soil);
            return t;
        }

        static Site load(CompoundTag t) {
            Site s = new Site();
            ResourceLocation dim = ResourceLocation.tryParse(t.getString("Dim"));
            s.dim = ResourceKey.create(Registries.DIMENSION, dim == null ? Level.OVERWORLD.location() : dim);
            s.board = BlockPos.of(t.getLong("Board"));
            s.facing = Direction.from2DDataValue(t.getInt("Facing"));
            s.heart = BlockPos.of(t.getLong("Heart"));
            s.yaw = t.getFloat("Yaw");
            s.founder = t.hasUUID("Founder") ? t.getUUID("Founder") : null;
            s.state = t.getInt("State");
            s.count = t.getInt("Count");
            s.seed = t.getLong("Seed");
            s.level = t.getInt("Level");
            s.radius = t.getInt("Radius");
            s.outer = t.getInt("Outer");
            s.cursor = t.getInt("Cursor");
            s.spawned = t.getInt("Spawned");
            s.nextSpot = t.getInt("Next");
            s.village = t.hasUUID("Village") ? t.getUUID("Village") : null;
            s.soil = t.getString("Soil");
            // The ground as read is not kept: after a restart it is waited for and walked again, and
            // the work goes on from the column it had got to (the level and the soil are kept).
            s.phase = LOAD;
            return s;
        }
    }

    private final List<Site> sites = new ArrayList<>();

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Site s : sites) list.add(s.save());
        tag.put("Sites", list);
        return tag;
    }

    private static Founding load(CompoundTag tag, HolderLookup.Provider registries) {
        Founding f = new Founding();
        ListTag list = tag.getList("Sites", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) f.sites.add(Site.load(list.getCompound(i)));
        return f;
    }

    @Nullable
    private static Founding of(@Nullable MinecraftServer server) {
        if (server == null) return null;
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(Founding::new, Founding::load, null), ID);
    }

    /** Forget every founding (the tests, each on ground of its own). */
    public static void resetForTests(MinecraftServer server) {
        Founding f = of(server);
        if (f == null) return;
        for (Site s : new ArrayList<>(f.sites)) {
            ServerLevel level = server.getLevel(s.dim);
            if (level != null) release(level, s);
        }
        f.sites.clear();
        f.setDirty();
    }

    /** What happened when somebody asked: whether it went ahead, what to tell them, and the board. */
    public record Outcome(boolean ok, String message, @Nullable BlockPos board) {
        static Outcome no(String why) {
            return new Outcome(false, why, null);
        }
    }

    // ------------------------------------------------------------------ asking

    /** The most a village can be founded with on this server: five hundred, unless the server says fewer
     *  (villageFoundingMost). Not the growth cap: that is on children, and a founding may be bigger. */
    public static int most() {
        return Math.max(FoundingPlan.MIN_FOLK, Math.min(FoundingPlan.MAX_FOLK, AssistantConfig.villageFoundingMost()));
    }

    /** How many will come if this many are asked for: no more than the server lets a village be founded with. */
    public static int allowed(int asked) {
        return Math.max(FoundingPlan.MIN_FOLK, Math.min(most(), asked));
    }

    /** The founding (waiting or under way) nearest this spot, within so many blocks of its heart. */
    @Nullable
    static Site siteNear(ServerLevel level, BlockPos pos, int range) {
        Founding f = of(level.getServer());
        if (f == null) return null;
        Site best = null;
        double bestD = (double) range * range;
        for (Site s : new ArrayList<>(f.sites)) {
            if (!s.dim.equals(level.dimension())) continue;
            double dx = s.heart.getX() - pos.getX(), dz = s.heart.getZ() - pos.getZ();
            double d = dx * dx + dz * dz;
            if (d > bestD) continue;
            // A waiting board that is no longer there (blown up, set over by a command) waits for nobody.
            if (s.state == PENDING && level.isLoaded(s.board)
                    && !(level.getBlockEntity(s.board) instanceof VillageBoardBlockEntity be && be.founding() == PENDING)) {
                f.sites.remove(s);
                f.setDirty();
                continue;
            }
            bestD = d;
            best = s;
        }
        return best;
    }

    @Nullable
    private static Site siteAt(ServerLevel level, BlockPos board) {
        Founding f = of(level.getServer());
        if (f == null) return null;
        for (Site s : f.sites) if (s.dim.equals(level.dimension()) && s.board.equals(board)) return s;
        return null;
    }

    /** Is a founding waiting or under way within this range of here? (A spawner set down by it adds nobody.) */
    public static boolean near(ServerLevel level, BlockPos pos, int range) {
        return siteNear(level, pos, range) != null;
    }

    /** Where the board of the founding nearest here stands, if there is one within range. */
    @Nullable
    public static BlockPos boardNear(ServerLevel level, BlockPos pos, int range) {
        Site s = siteNear(level, pos, range);
        return s == null ? null : s.board;
    }

    /**
     * A spawner set down where there is no village: the village board goes up on the edge of what
     * will be the square, on the far side of the heart from whoever set it down so they can read
     * it, and waits for them to say how many. Nobody comes yet.
     */
    public static Outcome propose(ServerLevel level, BlockPos at, @Nullable ServerPlayer player, float yaw) {
        Site near = siteNear(level, at, Villages.VILLAGE_RANGE * 2);
        if (near != null) {
            BlockPos b = near.board;
            int d = (int) Math.sqrt(b.distSqr(at));
            return Outcome.no(near.state == PENDING
                ? "A village is already waiting to be founded here: its board stands " + d + " blocks " + bearing(at, b)
                    + ". Go to it to choose how many folk start the village."
                : "A village is being founded " + d + " blocks " + bearing(at, b) + " of here already.");
        }
        if (level.dimensionType().hasCeiling()) {
            // Under a roof of rock the top of the world is the roof: there is no ground to level from above.
            return Outcome.no("A village wants open sky over it. Found it in the overworld.");
        }
        Direction side = Direction.fromYRot(yaw);
        BlockPos anchor = VillageBoards.raisePending(level, at, side);
        if (anchor == null || !(level.getBlockEntity(anchor) instanceof VillageBoardBlockEntity board)) {
            return Outcome.no("There is no room round here for the village board. Try a little more open ground.");
        }
        Founding f = of(level.getServer());
        if (f == null) return Outcome.no("The world is not ready.");
        Site s = new Site();
        s.dim = level.dimension();
        s.board = anchor.immutable();
        s.facing = level.getBlockState(anchor).getValue(VillageBoardBlock.FACING);
        s.heart = at.immutable();
        s.yaw = yaw;
        s.founder = player == null ? null : player.getUUID();
        s.state = PENDING;
        f.sites.add(s);
        f.setDirty();
        board.waitFor(at);
        LOG.info("[MCA-FOUND] a founding waits at {}, its board at {}", at.toShortString(), anchor.toShortString());
        return new Outcome(true, "Go to the village board to choose how many folk start the village.", anchor);
    }

    /** The way from one place to another, as a compass word. */
    static String bearing(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        String[] words = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
        return words[(int) Math.floorMod(Math.round(a / 45.0), 8L)];
    }

    /**
     * Confirm and spawn: {@code count} folk to found the village at this board. Refused unless the
     * board is still waiting, the one asking stands by it, and the count is two to five hundred; a
     * count over what the server lets a village grow to is brought down to that.
     */
    public static Outcome confirm(ServerLevel level, BlockPos board, int count, @Nullable ServerPlayer player) {
        Site s = siteAt(level, board);
        if (s == null || s.state != PENDING
                || !(level.getBlockEntity(board) instanceof VillageBoardBlockEntity be) || be.founding() != PENDING) {
            return Outcome.no("That board is not waiting for a founding any more.");
        }
        if (count < FoundingPlan.MIN_FOLK || count > FoundingPlan.MAX_FOLK) {
            return Outcome.no("A village is founded with " + FoundingPlan.MIN_FOLK + " to " + FoundingPlan.MAX_FOLK + " folk.");
        }
        if (player != null) {
            BlockPos middle = VillageBoardBlock.cell(board, s.facing, VillageBoardBlock.WIDE / 2, 0);
            if (player.level() != level || player.distanceToSqr(middle.getX() + 0.5, middle.getY(), middle.getZ() + 0.5) > NEAR * NEAR) {
                return Outcome.no("Stand by the board to choose.");
            }
        }
        if (Villages.nearest(level, s.heart, Villages.VILLAGE_RANGE * 2) != null) {
            return Outcome.no("A village has been founded near here since. This one cannot be.");
        }
        int folk = allowed(count);
        Founding f = of(level.getServer());
        s.state = UNDER_WAY;
        s.count = folk;
        s.seed = level.getRandom().nextLong();
        s.phase = LOAD;
        s.radius = FoundingPlan.coreRadius(folk);
        s.outer = s.radius + FoundingPlan.BAND_MAX;
        s.founder = player != null ? player.getUUID() : s.founder;
        // Never into the ground of a village already there: the edge stops short of its town.
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            double d = Math.sqrt(Math.pow(v.centre().getX() - s.heart.getX(), 2) + Math.pow(v.centre().getZ() - s.heart.getZ(), 2));
            int room = (int) d - Villages.townReach(v.id()) - 8;
            if (room < s.outer) s.outer = Math.max(FoundingPlan.BARE + 8, room);
        }
        if (s.outer < s.radius + 8) s.radius = Math.max(FoundingPlan.BARE, s.outer - 8);
        if (f != null) f.setDirty();
        be.underWay(folk);
        closeScreens(level, board);
        LOG.info("[MCA-FOUND] founding at {}: {} folk (asked {}), the ground levelled {} round, worked to {}",
            s.heart.toShortString(), folk, count, s.radius, s.outer);
        String said = (folk < count ? "This server lets a village grow to " + folk + ", so " + folk + " are coming. " : "")
            + "The ground is being made ready for " + folk + " folk. They will come as soon as the heart of it is level.";
        return new Outcome(true, said, board);
    }

    /** The waiting board was taken down: the founding is called off. */
    public static void calledOff(ServerLevel level, BlockPos board) {
        Founding f = of(level.getServer());
        Site s = siteAt(level, board);
        if (f == null || s == null || s.state != PENDING) return;
        f.sites.remove(s);
        f.setDirty();
        LOG.info("[MCA-FOUND] the founding at {} was called off", s.heart.toShortString());
    }

    // ------------------------------------------------------------------ the board and the screen

    /** Right-clicking a waiting board: the founding screen, its count at {@code start} (0 for the usual party). */
    public static void offer(ServerPlayer player, VillageBoardBlockEntity board, int start) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new com.jrpetty.mcassistant.net.FoundingScreenPayload(board.getBlockPos(), true,
                start > 0 ? start : VillageFolkSpawnerBlock.foundingParty(), most(),
                Villages.worldHeadcount(), AssistantConfig.villageWorldCap()));
    }

    /** Close the founding screen of anybody who has this board's open: it has been answered. */
    private static void closeScreens(ServerLevel level, BlockPos board) {
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(board) > 64.0 * 64.0) continue;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                new com.jrpetty.mcassistant.net.FoundingScreenPayload(board, false, 0, 0, 0, 0));
        }
    }

    /**
     * Is this board still a founding's? The board and the saved foundings are kept apart (the one with
     * its chunk, the other with the world), so after a crash one can outlive the other: a board still
     * waiting with nothing written down for it is written down again; one that was under way with
     * nothing written down is an ordinary board again.
     */
    public static boolean stillFounding(ServerLevel level, VillageBoardBlockEntity board) {
        if (siteAt(level, board.getBlockPos()) != null) return true;
        if (board.founding() != PENDING || board.foundingHeart() == null) return false;
        Founding f = of(level.getServer());
        var st = board.getBlockState();
        if (f == null || !st.hasProperty(VillageBoardBlock.FACING)) return false;
        Site s = new Site();
        s.dim = level.dimension();
        s.board = board.getBlockPos().immutable();
        s.facing = st.getValue(VillageBoardBlock.FACING);
        s.heart = board.foundingHeart();
        s.state = PENDING;
        f.sites.add(s);
        f.setDirty();
        return true;
    }

    /** What a founding's board says while it waits, and while the ground is made ready. */
    public static List<String> boardLines(ServerLevel level, VillageBoardBlockEntity board) {
        List<String> out = new ArrayList<>();
        Site s = siteAt(level, board.getBlockPos());
        if (s == null || s.state == PENDING) {
            out.add("TH|A Village To Be Founded");
            out.add("SM|Right-click the board to choose how many folk start it — anywhere from "
                + FoundingPlan.MIN_FOLK + " to " + most() + ".");
            out.add("LH|What happens");
            out.add("LN|You choose how many folk come to found the village, and press Confirm and spawn.");
            out.add("LN|The ground round about is made level for them first: hills cut down, hollows filled, trees"
                + " cleared, with the edges sloped into the land round it.");
            out.add("LN|They come as soon as the heart of it is level, and run the village themselves from then on.");
            out.add("RH|Before you choose");
            out.add("RN|A few folk level a little ground; five hundred level the whole of a town's plan, nearly two hundred"
                + " blocks across.");
            out.add("RW|Hundreds of folk are heavy for a server: every one of them is a ticking creature.");
            out.add("RM|Nothing anybody built is touched.");
            out.add("FH|To call it off");
            out.add("FM|Take the board down: you get your spawner back.");
            return out;
        }
        out.add("TH|A Village Is Being Founded");
        out.add("SM|" + s.count + " folk are coming  ·  the ground " + percent(s) + "% ready");
        out.add("LH|The ground");
        out.add(switch (s.phase) {
            case LOAD -> "LN|Waiting for the ground round about to load.";
            case SURVEY -> "LN|Walking the ground: " + (s.ground == null ? 0
                : (int) (100L * s.surveyed / Math.max(1, s.ground.side * s.ground.side))) + "%.";
            default -> "LG|Levelling: " + percent(s) + "% done, " + (2 * s.radius + 1) + " blocks across.";
        });
        if (s.level != UNSET) out.add("LN|Level with y=" + s.level + ", the middle height of the land here.");
        out.add("RH|The folk");
        out.add("RN|They come as soon as the heart of it is level.");
        out.add("FH|Nothing anybody built is touched.");
        return out;
    }

    /** How far along the ground is, as a percentage. */
    private static int percent(Site s) {
        if (s.phase != SHAPE || s.ground == null || s.ground.order == null) return 0;
        return (int) (100L * s.cursor / Math.max(1, s.ground.order.length));
    }

    /** A line for a founded village's board while its founding is still going on, or null. */
    @Nullable
    public static String boardLine(MinecraftServer server, UUID village) {
        Founding f = of(server);
        if (f == null) return null;
        for (Site s : f.sites) {
            if (village.equals(s.village)) {
                return "Founding: " + s.spawned + " of " + s.count + " folk have come; the ground is " + percent(s) + "% level.";
            }
        }
        return null;
    }

    /** One line a founding, for /village found status. */
    public static List<String> status(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        Founding f = of(server);
        if (f == null) return out;
        for (Site s : f.sites) {
            out.add("FOUNDING " + s.heart.getX() + " " + (s.level == UNSET ? s.heart.getY() : s.level + 1) + " " + s.heart.getZ()
                + " " + (s.state == PENDING ? "waiting" : switch (s.phase) {
                    case LOAD -> "loading";
                    case SURVEY -> "walking";
                    default -> "levelling";
                }) + " folk " + s.spawned + "/" + s.count + " ground " + percent(s) + "% radius " + s.radius
                + " board " + s.board.getX() + " " + s.board.getY() + " " + s.board.getZ());
        }
        return out;
    }

    // ------------------------------------------------------------------ the work

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        com.jrpetty.mcassistant.Guard.run("founding", () -> tick(event.getServer()));
    }

    private static void tick(MinecraftServer server) {
        Founding f = of(server);
        if (f == null || f.sites.isEmpty()) return;
        for (Site s : new ArrayList<>(f.sites)) {
            if (s.state != UNDER_WAY) continue;
            ServerLevel level = server.getLevel(s.dim);
            if (level == null) continue;
            long deadline = System.nanoTime() + TICK_NANOS;
            work(level, f, s, deadline);
            f.setDirty();
        }
    }

    private static void work(ServerLevel level, Founding f, Site s, long deadline) {
        boolean renew = level.getGameTime() - s.held >= HOLD_EVERY;
        int[] loaded = hold(level, s, renew || s.phase == LOAD);
        if (renew) s.held = level.getGameTime();
        if (level.getGameTime() % 20 == 0) tell(level, s, loaded);
        switch (s.phase) {
            case LOAD -> {
                if (loaded[0] >= loaded[1]) {
                    s.phase = SURVEY;
                    s.ground = new FoundingPlan.Ground(s.outer);
                    s.tops = new byte[s.ground.side * s.ground.side];
                    s.surveyed = 0;
                }
            }
            case SURVEY -> survey(level, s, deadline);
            default -> shape(level, f, s, deadline);
        }
    }

    /** The chunks of the ground being worked, held loaded: {loaded, all}. */
    private static int[] hold(ServerLevel level, Site s, boolean renew) {
        int r = (s.outer >> 4) + 1;
        int cx = s.heart.getX() >> 4, cz = s.heart.getZ() >> 4;
        int loaded = 0, total = 0, asking = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                total++;
                ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
                boolean here = level.getChunkSource().getChunkNow(pos.x, pos.z) != null;
                if (here) loaded++;
                if (here ? renew : asking++ < IN_FLIGHT) level.getChunkSource().addRegionTicket(HOLD, pos, 0, pos);
            }
        }
        return new int[]{ loaded, total };
    }

    /** Let the ground go. */
    private static void release(ServerLevel level, Site s) {
        int r = (Math.max(s.outer, 1) >> 4) + 1;
        int cx = s.heart.getX() >> 4, cz = s.heart.getZ() >> 4;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
                level.getChunkSource().removeRegionTicket(HOLD, pos, 0, pos);
            }
        }
    }

    /** Walk the ground: a few thousand columns a tick. When it is all read, plan it. */
    private static void survey(ServerLevel level, Site s, long deadline) {
        FoundingPlan.Ground g = s.ground;
        if (g == null || s.tops == null) {
            s.phase = LOAD;
            return;
        }
        int n = g.side * g.side;
        int minY = level.getMinBuildHeight();
        java.util.Set<Long> board = boardColumns(s);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int done = 0;
        while (s.surveyed < n && done < SURVEY_PER_TICK && System.nanoTime() < deadline) {
            int i = s.surveyed;
            int dx = g.dx(i), dz = g.dz(i);
            if (FoundingPlan.reach(dx, dz) > g.outer) {
                g.kind[i] = FoundingPlan.OUTSIDE;
            } else {
                int x = s.heart.getX() + dx, z = s.heart.getZ() + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    s.phase = LOAD;                            // it went: wait for it again
                    s.ground = null;
                    return;
                }
                Terraform.survey(chunk, minY, x, z, g, i, s.tops, m);
                if (board.contains(BlockPos.asLong(x, 0, z))) g.kind[i] = FoundingPlan.BOARD;
            }
            s.surveyed++;
            done++;
        }
        if (s.surveyed < n) return;
        if (s.level == UNSET) {
            s.level = FoundingPlan.level(g, s.radius, level.getSeaLevel());
            s.soil = Terraform.name(Terraform.dominant(level, g, s.tops, s.radius));
            LOG.info("[MCA-FOUND] the ground at {} is levelled to y={} in {}", s.heart.toShortString(), s.level, s.soil);
        }
        s.surface = Terraform.byName(s.soil, Blocks.GRASS_BLOCK);
        long t0 = System.nanoTime();
        FoundingPlan.make(g, s.radius, s.level, FoundingPlan.campRadius(s.count), s.seed);
        LOG.info("[MCA-FOUND] planned {} columns in {} ms: {} ponds filled, {} columns of water kept, {} left round buildings;"
                + " {} columns to the camp, from column {}",
            g.order.length, (System.nanoTime() - t0) / 1_000_000L, g.filledPonds, g.keptWater, g.protectedColumns,
            g.campIndex, s.cursor);
        s.phase = SHAPE;
    }

    /** The columns the waiting board stands in (taken down, and levelled, when the village is founded). */
    private static java.util.Set<Long> boardColumns(Site s) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (int c = 0; c < VillageBoardBlock.WIDE; c++) {
            BlockPos p = VillageBoardBlock.cell(s.board, s.facing, c, 0);
            out.add(BlockPos.asLong(p.getX(), 0, p.getZ()));
        }
        return out;
    }

    /** Shape the ground, the heart first; found the village when the camp is level; bring the folk. */
    private static void shape(ServerLevel level, Founding f, Site s, long deadline) {
        FoundingPlan.Ground g = s.ground;
        if (g == null || g.order == null) {
            s.phase = LOAD;
            return;
        }
        if (!s.founded() && s.cursor >= g.campIndex) {
            if (!found(level, f, s)) return;
        }
        if (s.founded() && s.spawned < s.count) come(level, s, deadline);
        int moved = 0;
        while (s.cursor < g.order.length && moved < BLOCKS_PER_TICK && System.nanoTime() < deadline) {
            int i = g.order[s.cursor];
            byte k = g.kind[i];
            if (k == FoundingPlan.LAND || k == FoundingPlan.FILL) {
                int dx = g.dx(i), dz = g.dz(i);
                boolean levelled = FoundingPlan.levelled(dx, dz, s.radius, s.seed);
                if (levelled || k == FoundingPlan.FILL || g.target[i] != g.ground[i]) {
                    int x = s.heart.getX() + dx, z = s.heart.getZ() + dz;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) return;                 // held, but not back yet: next tick
                    // The square in the town's soil; the edge in whatever each column was (rock left rock).
                    boolean own = !levelled && k == FoundingPlan.LAND && s.tops != null && s.tops[i] > 0;
                    Block top = own ? Terraform.top(s.tops[i]) : s.surface;
                    int c = Terraform.shape(level, chunk, x, z, g.ground[i], g.target[i], levelled, top);
                    moved += Math.max(1, c);
                }
            }
            s.cursor++;
        }
        if (s.cursor >= g.order.length && s.founded() && s.spawned >= s.count) finish(level, f, s);
    }

    /**
     * The camp ground is level: the waiting board comes down (the village puts its own up on the
     * levelled square, on the same side), and the first of the founders stands up at the heart and
     * founds the village, with its stores, its camp and its bedding. False if it could not be.
     */
    private static boolean found(ServerLevel level, Founding f, Site s) {
        FoundingPlan.Ground g = s.ground;
        if (Villages.nearest(level, s.heart, Villages.VILLAGE_RANGE * 2) != null) {
            abandon(level, f, s, "A village was founded near here before this one could be.");
            return false;
        }
        takeDownBoard(level, s);
        if (g != null) {
            for (int i = 0; i < g.kind.length; i++) {
                if (g.kind[i] != FoundingPlan.BOARD) continue;
                int x = s.heart.getX() + g.dx(i), z = s.heart.getZ() + g.dz(i);
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk != null) Terraform.shape(level, chunk, x, z, g.ground[i], g.target[i], true, s.surface);
                g.kind[i] = FoundingPlan.OUTSIDE;                  // done: the cursor passes it by
            }
        }
        BlockPos heart = standing(level, s.heart.getX(), s.heart.getZ());
        VillageBoards.preferSide(heart, s.facing.getOpposite());
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, s.yaw, most());
        if (first == null || first.ownerId() == null) {
            abandon(level, f, s, "Nobody could be settled there.");
            return false;
        }
        s.village = first.ownerId();
        s.spawned = 1;
        s.nextSpot = 1;
        Villages.Village v = Villages.get(s.village);
        BlockPos centre = v == null ? heart : v.centre();
        // The founders' camp (a bed each, as many as it has room for) and the stores for a party this size.
        VillageSpawner.pitchCamp(level, centre, s.count);
        VillageSpawner.foundingStores(level, centre, s.count);
        f.setDirty();
        LOG.info("[MCA-FOUND] {} is founded at {}; {} more to come", Villages.name(s.village), centre.toShortString(), s.count - 1);
        return true;
    }

    /** The free ground-level spot at this column: where somebody stands, or a chest goes. */
    private static BlockPos standing(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** The waiting board, taken down (it is the village's to put up properly now). */
    private static void takeDownBoard(ServerLevel level, Site s) {
        if (!(level.getBlockEntity(s.board) instanceof VillageBoardBlockEntity be)) return;
        be.clearFounding();
        for (int r = VillageBoardBlock.HIGH - 1; r >= 0; r--) {
            for (int c = 0; c < VillageBoardBlock.WIDE; c++) {
                BlockPos p = VillageBoardBlock.cell(s.board, s.facing, c, r);
                if (level.getBlockState(p).getBlock() instanceof VillageBoardBlock) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** The rest of the founders, a few a tick, on the sunflower spiral round the heart, each on ground of its own. */
    private static void come(ServerLevel level, Site s, long deadline) {
        Villages.Village v = Villages.get(s.village);
        if (v == null) {
            // Not back on the map yet (after a restart it returns when its first folk load); and if it
            // never does, the village is gone and there is nobody left to join.
            if (++s.waited > 2400) s.spawned = s.count;
            return;
        }
        s.waited = 0;
        int camp = FoundingPlan.campRadius(s.count) + 2;
        int stood = 0;
        while (s.spawned < s.count && stood < FOLK_PER_TICK && System.nanoTime() < deadline) {
            int i = s.nextSpot++;
            if (i > s.count * 4 + 64) {
                // No room left on the spiral: those who have come are the village.
                LOG.info("[MCA-FOUND] {}: room for {} of {}", Villages.name(s.village), s.spawned, s.count);
                s.count = s.spawned;
                return;
            }
            int[] d = FoundingPlan.partySpot(i);
            if (Math.max(Math.abs(d[0]), Math.abs(d[1])) > camp) continue;
            BlockPos spot = safeGround(level, v.centre().getX() + d[0], v.centre().getZ() + d[1]);
            if (spot == null) continue;
            VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, spot, s.yaw, most());
            if (folk == null) {
                s.count = s.spawned;                           // the village is full: that is all who come
                return;
            }
            folk.rentFree(true);                               // one of the founders, however long the levelling took
            s.spawned++;
            stood++;
        }
    }

    /** Ground somebody can stand on here: dry, solid underfoot and clear overhead. Null if not. */
    @Nullable
    private static BlockPos safeGround(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return null;
        BlockPos at = standing(level, x, z);
        BlockPos below = at.below();
        var floor = level.getBlockState(below);
        if (!floor.getFluidState().isEmpty() || !floor.isFaceSturdy(level, below, Direction.UP)) return null;
        if (!level.getBlockState(at).canBeReplaced() || !level.getFluidState(at).isEmpty()) return null;
        if (!level.getBlockState(at.above()).canBeReplaced()) return null;
        return at;
    }

    /** All done: the ground let go, the village told, and the founding forgotten. */
    private static void finish(ServerLevel level, Founding f, Site s) {
        release(level, s);
        f.sites.remove(s);
        f.setDirty();
        UUID id = s.village;
        if (id == null) return;
        int folk = Villages.headcount(id);
        int across = 2 * s.radius + 1;
        long day = level.getDayTime() / 24000L;
        Villages.tell(id, day, folk + " founders came to " + Villages.name(id) + ", and the ground was levelled for them, "
            + across + " blocks across");
        LOG.info("[MCA-FOUND] {} is founded: {} folk, the ground levelled {} across", Villages.name(id), folk, across);
        Villages.Village v = Villages.get(id);
        if (v != null) {
            // The first of them says so, to whoever is there to hear it.
            VillageFolkEntity first = null;
            double best = Double.MAX_VALUE;
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (!(a instanceof VillageFolkEntity vf) || !vf.isAlive()) continue;
                double d = vf.distanceToSqr(v.centre().getX() + 0.5, v.centre().getY(), v.centre().getZ() + 0.5);
                if (d < best) { best = d; first = vf; }
            }
            if (first != null) {
                FolkTalk.speak(first, folk > 50 ? "Level ground, and room for all " + folk + " of us. We'll make a town of this."
                    : "Level ground and good company. We'll make something of this.");
            }
            String said = Villages.name(id) + " is founded: " + folk + " folk, on level ground " + across
                + " blocks across. They will run it themselves.";
            for (ServerPlayer p : level.players()) {
                if (p.blockPosition().distSqr(v.centre()) <= (double) TELL * TELL) {
                    p.displayClientMessage(Component.literal(said), true);
                }
            }
            ServerPlayer founder = s.founder == null ? null : level.getServer().getPlayerList().getPlayer(s.founder);
            if (founder != null) founder.sendSystemMessage(Component.literal("<Village> " + said));
        }
    }

    /** It cannot go on: the ground let go, and whoever began it told why. */
    private static void abandon(ServerLevel level, Founding f, Site s, String why) {
        release(level, s);
        f.sites.remove(s);
        f.setDirty();
        if (level.getBlockEntity(s.board) instanceof VillageBoardBlockEntity be) be.clearFounding();
        LOG.info("[MCA-FOUND] the founding at {} was given up: {}", s.heart.toShortString(), why);
        ServerPlayer founder = s.founder == null ? null : level.getServer().getPlayerList().getPlayer(s.founder);
        if (founder != null) founder.sendSystemMessage(Component.literal("<Village> " + why));
    }

    /** How it is going, on the action bar of everybody near enough to see it. */
    private static void tell(ServerLevel level, Site s, int[] loaded) {
        String name = s.founded() ? Villages.name(s.village) : "The new village";
        String said = switch (s.phase) {
            case LOAD -> name + ": the ground is coming in — " + loaded[0] + " of " + loaded[1] + " chunks";
            case SURVEY -> name + ": walking the ground — " + (s.ground == null ? 0
                : (int) (100L * s.surveyed / Math.max(1, s.ground.side * s.ground.side))) + "%";
            default -> s.founded()
                ? name + ": " + s.spawned + " of " + s.count + " folk have come — the ground " + percent(s) + "% level"
                : name + ": levelling the heart of it — " + percent(s) + "%";
        };
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - s.heart.getX(), dz = p.getZ() - s.heart.getZ();
            if (dx * dx + dz * dz <= (double) TELL * TELL) p.displayClientMessage(Component.literal(said), true);
        }
    }
}
