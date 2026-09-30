package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The villages that are already there.
 *
 * <p>Minecraft builds villages far better than this mod ever will — houses
 * with beds in them, a farm with the water already dug, chests, furnaces, a
 * bell, paths between the lot. And it fills them with people who stand about
 * waiting to be traded with. Every one of those is a pair of hands, a roof
 * they already own and a field somebody else ploughed.
 *
 * <p>So the villagers become folk. They take over the village they were
 * standing in, sleep in its beds, store in its chests, smelt at its furnaces
 * and farm its fields, and the settlement is credited with the buildings it
 * plainly already has rather than setting out to build a storehouse next to
 * the storehouse it is standing in.
 *
 * <p>What this costs is honest and worth saying: THE VILLAGERS ARE GONE. There
 * is nobody left to trade with in a taken-over village. Wandering traders are
 * untouched, and the whole thing is one switch in the config.
 */
public final class VillagerTakeover {

    private VillagerTakeover() {}

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** How far a converted villager will look for a settlement to join. A
     *  vanilla village can be two hundred blocks across; the ninety-six a
     *  founded one uses would cut it into two or three rival settlements. */
    private static final int JOIN_RANGE = Villages.VILLAGE_RANGE * 2;

    /** Test hook: while set, neither path converts anything. Lets a test put a
     *  villager into the world exactly the way a chunk load or world generation
     *  would — without the join event getting to it first — and then prove the
     *  periodic sweep finds it anyway. */
    public static volatile boolean suspended = false;

    /** Everything about a villager that the folk which replaces it needs. */
    private record Snapshot(BlockPos where, float yaw, boolean baby, VillagerProfession trade,
                            @javax.annotation.Nullable BlockPos bed) {}

    /** Reads the villager's own fields and nothing else: this is called from
     *  the join event, where the world must not be touched at all. */
    private static Snapshot snap(Villager v) {
        return new Snapshot(v.blockPosition(), v.getYRot(), v.isBaby(),
            v.getVillagerData().getProfession(), v.getSleepingPos().orElse(null));
    }

    /** A villager somebody is trading with is left alone until they are done. */
    private static boolean busy(Villager v) {
        return v.getTradingPlayer() != null;
    }

    /**
     * Villagers the join event has taken off the board, waiting to become folk.
     *
     * <p>This is a queue and not a callback because of WHERE the event fires:
     * for a village's own villagers it fires inside the chunk's FULL step of
     * world generation, where reading or loading any other chunk can deadlock
     * the server. And {@code server.execute(...)} does not defer anything from
     * the server thread — it runs the task on the spot — so the "next tick"
     * this used to promise was never coming. The event now writes down who was
     * standing where and touches nothing; the tick handler does the rest.
     */
    private record Pending(ServerLevel level, Snapshot snap) {}

    private static final java.util.concurrent.ConcurrentLinkedQueue<Pending> QUEUE =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** How many are turned into folk per tick — a whole village generating at
     *  once should not all be built in one. */
    private static final int PER_TICK = 6;

    /**
     * Should this villager become folk? By default: every one of them — that
     * is what was asked for, and a swap that skips the villagers you have
     * actually met (which in an existing world means the ones with trade
     * experience, a name or gossip) looks exactly like a swap that does not
     * work. Protecting the ones you have traded with is a switch in the
     * config, and it is off.
     */
    private static boolean eligible(Villager villager) {
        if (!AssistantConfig.protectTradedVillagers()) return true;
        if (villager.getVillagerXp() > 0 || villager.hasCustomName()) return false;
        return villager.getGossips().getGossipEntries().isEmpty();
    }

    /**
     * The fast path: a villager joining the world. It is taken off the board
     * before it ever ticks and a folk is stood up in its place on the next
     * tick — adding an entity from inside the event that is adding an entity
     * is how you corrupt a chunk you are only trying to move into.
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (suspended || !AssistantConfig.replaceVillagers()) return;
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!eligible(villager) || busy(villager)) return;

        Snapshot snap = snap(villager);
        event.setCanceled(true);
        QUEUE.add(new Pending(level, snap));
    }

    /**
     * The safety net: a periodic sweep over every villager already standing in
     * the world. The join event is not a reliable way to meet every villager —
     * whether it fires at all depends on HOW the entity got into the level
     * (freshly spawned, loaded from a saved chunk, or placed by a structure
     * during world generation), and a villager that slips past it simply
     * stays a villager for ever. Nothing here depends on that: whoever is in
     * the world, whichever way they arrived, is found within five seconds.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!suspended) {
            int done = 0;
            Pending next;
            while (done < PER_TICK && (next = QUEUE.poll()) != null) {
                done++;
                // A level from a world that has since been closed is not ours.
                boolean ours = false;
                for (ServerLevel l : event.getServer().getAllLevels()) {
                    if (l == next.level()) { ours = true; break; }
                }
                if (ours) convert(next.level(), next.snap());
            }
        }
        if (event.getServer().getTickCount() % 100 != 0) return;
        for (ServerLevel level : event.getServer().getAllLevels()) sweep(level);
    }

    /** Convert every eligible villager in this level right now. */
    public static int sweep(ServerLevel level) {
        if (suspended || !AssistantConfig.replaceVillagers()) return 0;
        java.util.List<Villager> found = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (e instanceof Villager v && v.isAlive() && eligible(v) && !busy(v)) found.add(v);
        }
        for (Villager v : found) {
            Snapshot snap = snap(v);
            // Out of its bed first: a villager taken while asleep left the bed
            // marked occupied for good, and nobody could ever sleep in it.
            if (v.isSleeping()) v.stopSleeping();
            v.discard();
            convert(level, snap);
        }
        return found.size();
    }

    /** Stand a folk up where the villager was, in the village it belonged to. */
    private static void convert(ServerLevel level, Snapshot snap) {
        // THE FOLK FIRST. The villager is already gone; anything that can
        // throw runs after its replacement is safely standing.
        VillageFolkEntity folk = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (folk == null) return;
        freeBed(level, snap.bed());
        BlockPos where = snap.where();
        folk.moveTo(where.getX() + 0.5, where.getY(), where.getZ() + 0.5, snap.yaw(), 0.0F);

        Villages.Village village = Villages.nearest(level, where, JOIN_RANGE);
        if (village == null) village = Villages.found(level, where);
        // Named, then joined: joining files it on the register under that
        // name at once, so the next villager converted in this same tick
        // asks for a free name and does not get this one.
        folk.rename(com.jrpetty.mcassistant.entity.Names.freeFor(village.id()));
        // A grown villager is sent out like any other adult: with its tools, not
        // a child's two loaves. A converted mason given no pickaxe had to make
        // one out of logs it could not find on a stony plot.
        VillageSpawner.starterKit(folk);
        folk.joinVillage(village.id(), village.centre());
        level.addFreshEntity(folk);
        Villages.recordBirth(village.id());
        keepAwake(level, village);
        // Its own chunk and the ones round it, read off NOW, while it is
        // certainly loaded — each chunk once per village, ever.
        creditWhatStands(level, village.id(), where);
        // A villager that had a trade keeps doing roughly what it did. One
        // that never picked one takes whatever the village is short of,
        // which is what every other folk does.
        AssistantEntity.StationTask took = tradeFor(snap.trade());
        if (took != AssistantEntity.StationTask.NONE && !snap.baby()) {
            folk.setStation(folk.blockPosition(), took);
        }
    }

    /** Mark a bed a villager was saved asleep in as free again. */
    private static void freeBed(ServerLevel level, @javax.annotation.Nullable BlockPos bed) {
        if (bed == null || !level.isLoaded(bed)) return;
        BlockState st = level.getBlockState(bed);
        if (st.getBlock() instanceof net.minecraft.world.level.block.BedBlock
            && st.hasProperty(net.minecraft.world.level.block.BedBlock.OCCUPIED)
            && st.getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED)) {
            level.setBlock(bed, st.setValue(net.minecraft.world.level.block.BedBlock.OCCUPIED, false), 3);
        }
    }

    /** Forget everything remembered about villages. For tests. */
    public static void resetForTests() {
        QUEUE.clear();
        RING_TAKEN.clear();
        READ.clear();
        TALLY.clear();
        suspended = false;
    }

    /** The ring each village has been given so far, so a chunk's worth of
     *  villagers converting at once do not each re-take eighty-one tickets. */
    private static final java.util.Map<java.util.UUID, Integer> RING_TAKEN =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Keep the ground awake the way a founded village does — taken when the
     *  ring first exists and again only when the roll has grown it. */
    private static void keepAwake(ServerLevel level, Villages.Village village) {
        int ring = VillageSpawner.loadedRadiusFor(Villages.headcount(village.id()));
        Integer had = RING_TAKEN.get(village.id());
        if (had != null && had >= ring) return;
        ChunkLoad.setLoaded(level, village.id(), village.centre(), ring, true);
        RING_TAKEN.put(village.id(), ring);
    }

    /**
     * What a vanilla villager was doing, in this mod's terms. A village that
     * had a farmer, a mason and a shepherd should still have them the moment
     * after it changes hands.
     */
    private static AssistantEntity.StationTask tradeFor(VillagerProfession p) {
        if (p == VillagerProfession.FARMER) return AssistantEntity.StationTask.FARM;
        if (p == VillagerProfession.MASON) return AssistantEntity.StationTask.MINE;
        if (p == VillagerProfession.FLETCHER) return AssistantEntity.StationTask.WOOD;
        if (p == VillagerProfession.ARMORER || p == VillagerProfession.TOOLSMITH) {
            return AssistantEntity.StationTask.SMELT;
        }
        if (p == VillagerProfession.WEAPONSMITH) return AssistantEntity.StationTask.GUARD;
        if (p == VillagerProfession.SHEPHERD) return AssistantEntity.StationTask.RANCH;
        if (p == VillagerProfession.FISHERMAN) return AssistantEntity.StationTask.FISH;
        if (p == VillagerProfession.LIBRARIAN) return AssistantEntity.StationTask.STORE;
        if (p == VillagerProfession.CARTOGRAPHER) return AssistantEntity.StationTask.HAUL;
        return AssistantEntity.StationTask.NONE;
    }

    /** Chunks already read for each village, so a chunk with ten villagers in
     *  it is read once and not ten times. Memory only, and that is enough:
     *  after a restart the villagers are already folk, so nothing converts
     *  and nothing is read again. What was credited is on the folk's save. */
    private static final java.util.Map<java.util.UUID, java.util.Set<Long>> READ =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Running totals per village — beds, chests, furnaces, and how many of
     *  each building has been credited off them so far. */
    private static final java.util.Map<java.util.UUID, int[]> TALLY =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Read off what stands around a converted villager and write it down as
     * built.
     *
     * <p>Without this the plan looks at a finished village, sees nothing on
     * its own list, and sets about building a storehouse ten feet from the
     * storehouse. A settlement that moves into somewhere already standing
     * should start from what is standing.
     *
     * <p>Read chunk by chunk, around each villager as it converts, which is
     * the one moment its ground is certainly loaded — a radius read from the
     * heart at first-chunk-load saw two chunks of a twelve-chunk village, and
     * one deferred to later could be lost to a restart. This way every chunk
     * with a villager in it is read exactly once, whatever shape the village
     * is, and a chunk with nobody in it was never going to have a house.
     */
    public static void creditWhatStands(ServerLevel level, java.util.UUID village, BlockPos near) {
        java.util.Set<Long> read = READ.computeIfAbsent(
            village, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        int[] tally = TALLY.computeIfAbsent(village, k -> new int[6]);
        // [0] beds, [1] chests, [2] furnaces, [3] houses credited,
        // [4] storage+shelter credited, [5] smeltery credited
        int cx0 = near.getX() >> 4, cz0 = near.getZ() >> 4;
        try {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = cx0 + dx, cz = cz0 + dz;
                    if (!level.hasChunk(cx, cz)) continue;
                    if (!read.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) continue;
                    net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                    for (BlockEntity be : new java.util.ArrayList<>(chunk.getBlockEntities().values())) {
                        if (be instanceof AbstractFurnaceBlockEntity) tally[2]++;
                        else if (be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                            || be instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity) {
                            tally[1]++;
                        }
                    }
                    // Beds have no block entity, so the ground is read — this
                    // chunk's slice, near the villager's own height, and only
                    // the head so a bed is not counted twice.
                    int x0 = cx << 4, z0 = cz << 4;
                    for (BlockPos p : BlockPos.betweenClosed(
                            x0, near.getY() - 12, z0, x0 + 15, near.getY() + 12, z0 + 15)) {
                        BlockState st = level.getBlockState(p);
                        if (!st.is(net.minecraft.tags.BlockTags.BEDS)) continue;
                        if (!st.hasProperty(net.minecraft.world.level.block.BedBlock.PART)) continue;
                        if (st.getValue(net.minecraft.world.level.block.BedBlock.PART)
                                == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                            tally[0]++;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // A survey is a convenience. A village that cannot be read builds
            // from what it has already been credited with, which is only what
            // it would have done.
            LOGGER.warn("Village survey near {} failed: {}", near, e.toString());
        }
        long now = level.getGameTime();
        // Two beds is a house, by this mod's own blueprint. Credited as the
        // totals cross each line, so beds split across chunks still pair up.
        int perHouse = com.jrpetty.mcassistant.village.VillageMath.BEDS_PER_HOUSE;
        while (tally[0] / perHouse > tally[3]) {
            Villages.noteProject(village, "house", now);
            tally[3]++;
        }
        if (tally[4] == 0 && tally[1] >= 1) {
            Villages.noteProject(village, "shelter", now);
            tally[4] = 1;
        }
        if (tally[4] == 1 && tally[1] >= 2) {
            Villages.noteProject(village, "storage", now);
            tally[4] = 2;
        }
        if (tally[5] == 0 && tally[2] >= 1) {
            Villages.noteProject(village, "smeltery", now);
            tally[5] = 1;
        }
    }
}
