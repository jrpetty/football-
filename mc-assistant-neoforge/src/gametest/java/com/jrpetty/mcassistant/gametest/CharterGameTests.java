package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.VillageSpawner;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.FoundingPlan;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The Village Charter founds a town of seventy (villageCharterFolk), and every one of them is looked after
 * from the first night: one village, all seventy come, a bed each at the camp (the camp has room for about
 * eighty on level ground; it had twenty-four), the founding stores for a party that size, and a treasury
 * opened with the whole party's savings rather than the thirty-two coins a hamlet of eight brought.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CharterGameTests {

    private static final String EMPTY = "empty";

    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "ch01_charter_seventy")
    public static void ch01_charter_seventy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        level.setDayTime(7000);                                   // past the morning's wages: the treasury as opened
        int x = 750000, z = 64000;
        Kit.hold(level, x, z, 96);
        Kit.prepare(level, x, z, 96);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getAbilities().instabuild = false;
        int party = VillageFolkSpawnerBlock.foundingParty();
        Kit.log("ch01 the charter's party: " + party + " (villageCharterFolk " + AssistantConfig.villageCharterFolk()
            + "); the camp has room for " + VillageSpawner.campRoom() + "; a purse of " + Market.foundingPurse(party));
        helper.assertTrue(party == 70, "a charter founds seventy by default: " + party);
        helper.assertTrue(VillageSpawner.campRoom() >= 70, "the camp has room for seventy: " + VillageSpawner.campRoom());
        helper.assertTrue(Market.foundingPurse(party) == 280 && Market.foundingPurse(8) == Market.FOUNDING_PURSE,
            "four coins a head, thirty-two at the least: " + Market.foundingPurse(party));

        BlockPos ground = Kit.surface(level, x, z);
        ItemStack charter = new ItemStack(McAssistantMod.VILLAGE_CHARTER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, charter);
        charter.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(ground.below()), Direction.UP, ground.below(), false)));
        helper.assertTrue(Founding.near(level, ground, 16) && charter.isEmpty(), "the charter begins a founding, and is spent");

        final boolean[] done = { false };
        final long[] at = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (!done[0]) {
                if (Founding.near(level, ground, 16)) return;              // still being founded
                done[0] = true;
                at[0] = t;
                Villages.Village v = Villages.nearest(level, ground, 64);
                helper.assertTrue(v != null, "a village was founded");
                UUID id = v.id();
                BlockPos heart = v.centre();
                List<VillageFolkEntity> folk = level.getEntitiesOfClass(VillageFolkEntity.class,
                    new AABB(heart).inflate(64), f -> id.equals(f.ownerId()));
                int beds = VillageSpawner.campBeds(level, heart).size();
                int coins = Ledger.coins(id);
                int reach = 0;
                for (VillageFolkEntity f : folk) {
                    reach = Math.max(reach, Math.max(Math.abs(f.blockPosition().getX() - heart.getX()),
                        Math.abs(f.blockPosition().getZ() - heart.getZ())));
                }
                int bedding = 0;
                for (BlockPos p : BlockPos.betweenClosed(heart.offset(-3, -2, -3), heart.offset(3, 2, 3))) {
                    if (level.getBlockEntity(p) instanceof net.minecraft.world.Container c) {
                        for (int i = 0; i < c.getContainerSize(); i++) {
                            if (c.getItem(i).is(net.minecraft.tags.ItemTags.BEDS)) bedding += c.getItem(i).getCount();
                        }
                    }
                }
                Kit.log("ch01 founded at " + t + ": " + Villages.name(id) + ", " + folk.size() + " folk, headcount "
                    + Villages.headcount(id) + ", " + beds + " camp beds, " + bedding + " beds in the stores, treasury "
                    + coins + ", the farthest founder " + reach + " out (camp ground " + FoundingPlan.campRadius(party) + ")");
                helper.assertTrue(folk.size() == 70 && Villages.headcount(id) == 70, "all seventy came: " + folk.size());
                helper.assertTrue(beds + bedding >= 70, "a bed for every founder, at the camp or in the stores: " + (beds + bedding));
                helper.assertTrue(beds >= 70, "on level ground every founder has a bed laid at the camp: " + beds);
                helper.assertTrue(coins >= 250, "the treasury opened with the party's savings: " + coins);
                helper.assertTrue(reach <= FoundingPlan.campRadius(party) + 2, "every founder stands on the camp's ground: " + reach);
                helper.assertTrue(level.getBlockState(heart.offset(0, 0, 8)).canBeReplaced()
                        && level.getBlockState(heart.offset(8, 0, 0)).canBeReplaced(),
                    "the square's well and monument spots are left clear of beds");
                return;
            }
            // A while later every founder is still alive and standing in the open, not in a wall.
            if (t - at[0] < 200) return;
            Villages.Village v = Villages.nearest(level, ground, 64);
            UUID id = v.id();
            List<VillageFolkEntity> folk = level.getEntitiesOfClass(VillageFolkEntity.class,
                new AABB(v.centre()).inflate(96), f -> id.equals(f.ownerId()));
            // In a wall: the game's own reckoning, a block that suffocates where its eyes are (an open door's
            // upper half over its head is not one, and nor is a bed it stands on).
            int stuck = 0;
            for (VillageFolkEntity f : folk) {
                if (f.isInWall()) {
                    stuck++;
                    Kit.log("ch01 in a wall: " + f.debugLine());
                }
            }
            Kit.log("ch01 two hundred ticks on: " + folk.size() + " alive, " + stuck + " in a wall");
            helper.assertTrue(folk.size() == 70 && stuck == 0, "every founder alive and in the open: " + folk.size() + ", stuck " + stuck);
            helper.succeed();
        });
    }
}
