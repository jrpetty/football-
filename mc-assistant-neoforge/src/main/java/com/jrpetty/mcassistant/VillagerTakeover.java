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

    /** How far out we look for what the village already has. */
    private static final int SURVEY = 48;

    /** How far a converted villager will look for a settlement to join. A
     *  vanilla village can be two hundred blocks across; the ninety-six a
     *  founded one uses would cut it into two or three rival settlements. */
    private static final int JOIN_RANGE = Villages.VILLAGE_RANGE * 2;

    /**
     * Names handed out this session, per village. A chunk's worth of
     * villagers are all converted in the same tick, before any of them has
     * ticked far enough to be on the register — so asking the register for a
     * free name gave the same name to all ten, and the register is KEYED by
     * name, so nine of them then vanished from it. The roll read one folk
     * where ten stood.
     */
    private static final java.util.Map<java.util.UUID, java.util.Set<String>> HANDED_OUT =
        new java.util.concurrent.ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!AssistantConfig.replaceVillagers()) return;
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // A villager somebody has TRADED with, or named, is somebody's — a
        // mending librarian or a cured-discount farmer is hours of a player's
        // work, and this must never eat it. Only the ones standing about.
        if (villager.getVillagerXp() > 0 || villager.hasCustomName()) return;

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
            // throw runs after its replacement is safely standing, or a bad
            // bed block somewhere in the village would have deleted every
            // villager in it and stood nobody up in their place.
            VillageFolkEntity folk = McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (folk == null) return;
            folk.moveTo(where.getX() + 0.5, where.getY(), where.getZ() + 0.5, yaw, 0.0F);

            Villages.Village village = Villages.nearest(level, where, JOIN_RANGE);
            if (village == null) {
                // The first one converted founds the settlement where it
                // stands, keeps the ground awake the way a founded village
                // does, and books the look round for when the place is loaded.
                village = Villages.found(level, where);
                Villages.markUnsurveyed(village.id());
            }
            folk.rename(freeName(village.id()));
            VillageSpawner.childKit(folk);
            // Born into it, not left to go and look for it.
            folk.joinVillage(village.id(), village.centre());
            level.addFreshEntity(folk);
            Villages.recordBirth(village.id());
            // Keep the ground awake the way a founded village does, re-taken
            // as each villager converts so the ring grows with the roll.
            // Tickets are idempotent, so this is one call per conversion and
            // never a leak.
            ChunkLoad.setLoaded(level, village.id(), village.centre(),
                VillageSpawner.loadedRadiusFor(Villages.headcount(village.id())), true);
            // A villager that had a trade keeps doing roughly what it did. One
            // that never picked one takes whatever the village is short of,
            // which is what every other folk does.
            AssistantEntity.StationTask took = tradeFor(trade);
            if (took != AssistantEntity.StationTask.NONE && !baby) {
                folk.setStation(folk.blockPosition(), took);
            }
        });
    }

    /** A name nobody in this village has, counting the ones handed out in
     *  this same tick that are not on the register yet. */
    private static String freeName(java.util.UUID village) {
        java.util.Set<String> used = HANDED_OUT.computeIfAbsent(
            village, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        for (AssistantEntity a : Villages.folkOf(village)) {
            used.add(a.getAssistantName().toLowerCase());
        }
        for (String candidate : com.jrpetty.mcassistant.entity.Names.POOL) {
            if (used.add(candidate.toLowerCase())) return candidate;
        }
        for (int n = 2; n < 1000; n++) {
            String candidate = com.jrpetty.mcassistant.entity.Names.POOL.get(0) + n;
            if (used.add(candidate.toLowerCase())) return candidate;
        }
        return "folk_" + used.size();
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

    /**
     * Read off what the village already has and write it down as built.
     *
     * <p>Without this the plan looks at a finished village, sees nothing on
     * its own list, and sets about building a storehouse ten feet from the
     * storehouse — then houses beside the houses. A settlement that moves into
     * somewhere already standing should start from what is standing.
     *
     * <p>Run from a folk's own agenda once it is stood in the middle of the
     * place with the ground loaded — not at the instant the first chunk came
     * in, when {@code isLoaded} would have skipped most of the village and
     * credited it with two chunks' worth of beds. Chests and furnaces come
     * out of each chunk's own block-entity map, which is a handful of entries
     * per chunk; only the beds need the ground read.
     */
    public static void creditWhatStands(ServerLevel level, java.util.UUID village, BlockPos heart) {
        int beds = 0, chests = 0, furnaces = 0;
        long now = level.getGameTime();
        try {
            int minCx = (heart.getX() - SURVEY) >> 4, maxCx = (heart.getX() + SURVEY) >> 4;
            int minCz = (heart.getZ() - SURVEY) >> 4, maxCz = (heart.getZ() + SURVEY) >> 4;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    if (!level.hasChunk(cx, cz)) continue;
                    net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                    for (BlockEntity be : new java.util.ArrayList<>(chunk.getBlockEntities().values())) {
                        if (be instanceof AbstractFurnaceBlockEntity) furnaces++;
                        else if (be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                            || be instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity) {
                            chests++;
                        }
                    }
                    // Beds have no block entity, so the ground is read — but
                    // only this chunk's slice of it, and only the head, so a
                    // bed is not counted twice.
                    int x0 = cx << 4, z0 = cz << 4;
                    for (BlockPos p : BlockPos.betweenClosed(
                            x0, heart.getY() - 12, z0, x0 + 15, heart.getY() + 12, z0 + 15)) {
                        BlockState st = level.getBlockState(p);
                        if (!st.is(net.minecraft.tags.BlockTags.BEDS)) continue;
                        if (!st.hasProperty(net.minecraft.world.level.block.BedBlock.PART)) continue;
                        if (st.getValue(net.minecraft.world.level.block.BedBlock.PART)
                                == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                            beds++;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // A survey is a convenience. A village that cannot be surveyed
            // builds from nothing, which is only what it would have done.
            LOGGER.warn("Village survey at {} failed: {}", heart, e.toString());
        }
        // Two beds is a house, by this mod's own blueprint.
        for (int i = 0; i < beds / com.jrpetty.mcassistant.village.VillageMath.BEDS_PER_HOUSE; i++) {
            Villages.noteProject(village, "house", now);
        }
        if (chests >= 2) Villages.noteProject(village, "storage", now);
        if (chests >= 1) Villages.noteProject(village, "shelter", now);
        if (furnaces >= 1) Villages.noteProject(village, "smeltery", now);
    }
}
