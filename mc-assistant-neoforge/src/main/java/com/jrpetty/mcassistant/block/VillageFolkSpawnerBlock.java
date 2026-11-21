package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.VillageSpawner;
import com.jrpetty.mcassistant.entity.Names;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * The Village Folk Spawner: place it, and a village stands up.
 *
 * <p>Exactly the Assistant Spawner's shape — a block you craft, place and
 * spend — because that is the one way of getting a hand into the world here
 * that has been proven to work. The first one placed puts the village board
 * up on the spot and waits for you to say, at the board, how many folk are to
 * found the village (entity/Founding): two of them or five hundred. Once you
 * have, the ground round about is made level for them and they come, the
 * founding stores where the block stood. Every one placed after that within
 * reach of a village adds a settler to it. No charter to right-click, no
 * command to remember, and nothing more to do.
 */
public class VillageFolkSpawnerBlock extends Block {

    public VillageFolkSpawnerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level instanceof ServerLevel server)
            || !(placer instanceof ServerPlayer player)) {
            return;
        }
        placed(server, pos, state, player, player.getYRot());
    }

    /**
     * The spawner set down at {@code pos} (by a player, or by a test standing in for one). Within
     * reach of a village it adds one settler to it, as it always has. Anywhere else it puts up the
     * board of a village to be founded, and nobody comes until somebody at the board says how many.
     */
    public static void placed(ServerLevel server, BlockPos pos, BlockState state, @Nullable ServerPlayer player, float yaw) {
        // The block is spent by being placed. Take it away FIRST: the founding
        // stores stand where it stood, and removing it afterwards would have
        // taken the chest with it.
        server.levelEvent(2001, pos, Block.getId(state));
        server.removeBlock(pos, false);
        if (Villages.nearest(server, pos, Villages.VILLAGE_RANGE * 2) == null) {
            com.jrpetty.mcassistant.entity.Founding.Outcome asked =
                com.jrpetty.mcassistant.entity.Founding.propose(server, pos, player, yaw);
            if (player == null) return;
            if (!asked.ok() && !player.getAbilities().instabuild) {
                // Give the spawner back rather than eating it.
                ItemStack back = new ItemStack(McAssistantMod.FOLK_SPAWNER_ITEM.get());
                if (!player.getInventory().add(back)) player.drop(back, false);
            }
            player.sendSystemMessage(Component.literal("<Village> " + asked.message()));
            return;
        }
        int stood = raiseParty(server, pos, yaw, 1);
        if (stood == 0) {
            if (player != null) player.sendSystemMessage(Component.literal(
                "<Village> That settlement is full. Found another further out."));
            // Give the block back rather than eating it.
            server.setBlockAndUpdate(pos, state);
            return;
        }
        Villages.Village v = Villages.nearest(server, pos, Villages.VILLAGE_RANGE * 2);
        int total = v == null ? stood : Villages.headcount(v.id());
        if (player != null) player.displayClientMessage(Component.literal(
            total + " live here now. They will run it themselves."), true);
    }

    /**
     * How many stand up when a charter FOUNDS a village, and how many the founding
     * screen offers first when a spawner is set down: seventy, unless the server says
     * otherwise ({@code villageCharterFolk}), and never more than a village may be founded
     * with ({@code villageFoundingMost}). The player may choose any number from two up
     * at the board before confirming. It was the eight a village the world grows by
     * itself starts with: a hamlet that took weeks to become a town, where a charter is
     * the player's own town, founded to be one. (Villages the world grows, and colonies,
     * keep their own sizes: villageMinFolk to villageMaxFolk.)
     */
    public static int foundingParty() {
        return com.jrpetty.mcassistant.entity.Founding.allowed(com.jrpetty.mcassistant.AssistantConfig.villageCharterFolk());
    }

    /**
     * Stand {@code count} settlers up around {@code at}, the first exactly there
     * (founding the village and its stores if there is none), the rest scattered
     * on a sunflower spiral so nobody stands on anybody. Returns how many stood.
     */
    public static int raiseParty(ServerLevel server, BlockPos at, float yaw, int count) {
        int stood = 0;
        boolean founding = Villages.nearest(server, at, Villages.VILLAGE_RANGE * 2) == null;
        // A party founding a village makes its camp on good ground near where it was set down.
        if (founding) at = com.jrpetty.mcassistant.VillageSpawner.campSite(server, at);
        for (int i = 0; i < count; i++) {
            // A hundred folk stood up on one square make a crowd, and a crowd of
            // more than twenty-four in one place is crushed by the game's own
            // entity-cramming rule. The golden angle puts each on ground of its
            // own, about a block and a half apart.
            BlockPos spot = at;
            if (i > 0) {
                double angle = i * 2.399963229728653;
                double reach = 1.5 + 1.1 * Math.sqrt(i);
                int x = at.getX() + (int) Math.round(Math.cos(angle) * reach);
                int z = at.getZ() + (int) Math.round(Math.sin(angle) * reach);
                if (server.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) {
                    spot = new BlockPos(x, server.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                }
            }
            if (raise(server, spot, yaw) == null) break;
            stood++;
        }
        // A party pitches camp round the village's stores: a bed each. (Founding it, round the
        // stores it brings; joining one — a vanilla village taken over, say — round its heart.)
        if (stood > 0) {
            Villages.Village home = Villages.nearest(server, at, Villages.VILLAGE_RANGE * 2);
            BlockPos heart = founding || home == null ? at : home.centre();
            com.jrpetty.mcassistant.VillageSpawner.pitchCamp(server, heart, stood);
        }
        return stood;
    }

    /**
     * Stand one settler up beside this spot, founding a village if there is
     * none within reach. {@code at} must be a FREE ground-level position — air
     * over something solid. Shared with the /village command, so both do
     * exactly the same thing. Returns null when the settlement is at its cap.
     */
    @Nullable
    public static VillageFolkEntity raise(ServerLevel server, BlockPos at, float yaw) {
        return raise(server, at, yaw, com.jrpetty.mcassistant.AssistantConfig.villageGrowthCap());
    }

    /** As raise, up to {@code cap} folk in the village: a founding party (Founding) may be bigger than the
     *  growth cap, which is on children. */
    @Nullable
    public static VillageFolkEntity raise(ServerLevel server, BlockPos at, float yaw, int cap) {
        Villages.Village village = Villages.nearest(server, at, Villages.VILLAGE_RANGE * 2);
        boolean founding = village == null;
        if (founding) village = Villages.found(server, at);
        if (Villages.headcount(village.id()) >= cap) return null;

        VillageFolkEntity folk = McAssistantMod.VILLAGE_FOLK.get().create(server);
        if (folk == null) return null;
        Vec3 spot = safeSpot(server, at);
        folk.moveTo(spot.x, spot.y, spot.z, yaw, 0.0F);
        folk.rename(Names.freshFor(village.id(), server.getRandom()));
        VillageSpawner.starterKit(folk);
        folk.joinVillage(village.id(), village.centre());
        // The founding party, and whoever comes on the day the village was founded: the houses the village
        // builds them are theirs rent-free until they can afford the rent (Homes).
        long founded = com.jrpetty.mcassistant.village.Chronicle.foundedOn(village.id());
        if (founding || founded >= 0 && server.getDayTime() / 24000L - founded <= 1) folk.rentFree(true);
        server.addFreshEntity(folk);
        Villages.recordBirth(village.id());
        // [batchA] One stood up in a town already standing (not the founders): somebody comes to welcome it (Neighbourly).
        if (!folk.rentFree()) com.jrpetty.mcassistant.entity.Neighbourly.arrived(server, village.id(), folk, "stood up in the town");
        if (founding) {
            // The founding stores, and the ground kept awake — the same start
            // a village the world grew gets. `at` is a free ground-level spot,
            // so that is exactly where a chest stands.
            VillageSpawner.supplyChest(server, at);
        }
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(server, village.id(), village.centre(),
            VillageSpawner.loadedRadiusFor(Villages.headcount(village.id())), true);
        return folk;
    }

    /** Somewhere beside the block a two-block-tall settler can actually stand
     *  without being embedded in a wall. */
    private static Vec3 safeSpot(ServerLevel level, BlockPos pos) {
        double[][] offsets = { {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {0, 0} };
        for (double[] off : offsets) {
            double x = pos.getX() + 0.5 + off[0];
            double z = pos.getZ() + 0.5 + off[1];
            AABB box = McAssistantMod.VILLAGE_FOLK.get().getDimensions()
                .makeBoundingBox(x, pos.getY(), z);
            if (level.noCollision(box)) return new Vec3(x, pos.getY(), z);
        }
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
    }
}
