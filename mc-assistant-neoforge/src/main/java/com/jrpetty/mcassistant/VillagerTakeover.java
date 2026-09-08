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

    /** How far out we look for what the village already has. */
    private static final int SURVEY = 48;

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!AssistantConfig.replaceVillagers()) return;
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

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
            if (Villages.nearest(level, where) == null) {
                // The first one to be converted founds the settlement where it
                // stands, then reads off what the place already has.
                Villages.Village village = Villages.found(level, where);
                creditWhatStands(level, village.id(), where);
            }
            java.util.UUID id = village(level, where);
            VillageFolkEntity folk = McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (folk == null) return;
            folk.moveTo(where.getX() + 0.5, where.getY(), where.getZ() + 0.5, yaw, 0.0F);
            folk.rename(com.jrpetty.mcassistant.entity.Names.freeFor(id));
            VillageSpawner.childKit(folk);
            // A villager that had a trade keeps doing roughly what it did. One
            // that never picked one takes whatever the village is short of,
            // which is what every other folk does.
            AssistantEntity.StationTask took = tradeFor(trade);
            level.addFreshEntity(folk);
            if (took != AssistantEntity.StationTask.NONE && !baby) {
                folk.setStation(folk.blockPosition(), took);
            }
        });
    }

    /** The settlement this spot belongs to, if any. */
    private static java.util.UUID village(ServerLevel level, BlockPos where) {
        Villages.Village v = Villages.nearest(level, where);
        return v == null ? java.util.UUID.randomUUID() : v.id();
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
     */
    private static void creditWhatStands(ServerLevel level, java.util.UUID village, BlockPos heart) {
        int beds = 0, chests = 0, furnaces = 0;
        long now = level.getGameTime();
        for (BlockPos p : BlockPos.betweenClosed(
                heart.offset(-SURVEY, -12, -SURVEY), heart.offset(SURVEY, 12, SURVEY))) {
            if (!level.isLoaded(p)) continue;
            BlockState st = level.getBlockState(p);
            if (st.is(net.minecraft.tags.BlockTags.BEDS)) {
                // Only the head, so a bed is not counted twice.
                if (st.getValue(net.minecraft.world.level.block.BedBlock.PART)
                        == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                    beds++;
                }
                continue;
            }
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof AbstractFurnaceBlockEntity) furnaces++;
            else if (be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                || be instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity) {
                chests++;
            }
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
