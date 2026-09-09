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

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!AssistantConfig.replaceVillagers()) return;
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // A villager somebody has TRADED with, named, or CURED is somebody's —
        // a mending librarian or a cured-discount farmer is hours of a
        // player's work, and this must never eat it. A cure leaves no trade
        // experience behind, only gossip, so gossip is checked too. Only the
        // ones standing about become folk.
        if (villager.getVillagerXp() > 0 || villager.hasCustomName()) return;
        if (!villager.getGossips().getGossipEntries().isEmpty()) return;

        // Take the villager off the board before it ever ticks, and stand a
        // folk up in its place on the next tick — adding an entity from
        // inside the event that is adding an entity is how you corrupt a
        // chunk you are only trying to move into.
        BlockPos where = villager.blockPosition();
        float yaw = villager.getYRot();
        boolean baby = villager.isBaby();
        VillagerProfession trade = villager.getVillagerData().getProfession();
        event.setCanceled(true);

        level.getServer().execute(() -> {
            // THE FOLK FIRST. The villager is already gone; anything that can
            // throw runs after its replacement is safely standing.
            VillageFolkEntity folk = McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (folk == null) return;
            folk.moveTo(where.getX() + 0.5, where.getY(), where.getZ() + 0.5, yaw, 0.0F);

            Villages.Village village = Villages.nearest(level, where, JOIN_RANGE);
            if (village == null) village = Villages.found(level, where);
            // Named, then joined: joining files it on the register under that
            // name at once, so the next villager converted in this same tick
            // asks for a free name and does not get this one.
            folk.rename(com.jrpetty.mcassistant.entity.Names.freeFor(village.id()));
            VillageSpawner.childKit(folk);
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
            AssistantEntity.StationTask took = tradeFor(trade);
            if (took != AssistantEntity.StationTask.NONE && !baby) {
                folk.setStation(folk.blockPosition(), took);
            }
        });
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
