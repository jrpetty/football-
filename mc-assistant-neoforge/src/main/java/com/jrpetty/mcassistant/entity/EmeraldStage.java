package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [emerald] The pictures' little village of villagers (/village emerald stage, for the smoke run): beside where it is
 * run, a bell on a stone post, three stalls' workstations (a farmer's composter, a librarian's lectern, a cleric's
 * brewing stand) with a bed each behind them, and three real villagers of those trades, their offers the game's own.
 * The town knows it by its bell (VanillaVillages), and its trader is set down at it with the town's goods, and trades.
 *
 * <p>A stage, so what it puts down is free (as the other stages' are), and so are the goods it hands the trader to sell:
 * the trading itself is the real thing, offer by offer, as on any trip.
 */
public final class EmeraldStage {

    private EmeraldStage() {}

    /** Set the little village down round {@code at}, and the town's trader to work at it. Lines to report. */
    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        BlockPos ground = new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
        if (TwoPeoples.townGround(level, ground)) {
            out.add("EMERALD-STAGE this is the town's own ground: villagers here would be kept off its beds and job blocks."
                + " Stand further out (" + (Villages.townReach(v.id()) + 24) + " blocks from the heart).");
        }
        // A level floor of grass, cleared above, nine across.
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                BlockPos p = ground.offset(dx, -1, dz);
                level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                for (int up = 1; up <= 5; up++) level.setBlock(p.above(up), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        // The bell, on a stone post: the villagers' meeting place, and how the town knows them.
        level.setBlock(ground, Blocks.COBBLESTONE_WALL.defaultBlockState(), 3);
        BlockPos bell = ground.above();
        level.setBlock(bell, Blocks.BELL.defaultBlockState().setValue(BellBlock.FACING, Direction.NORTH)
            .setValue(BellBlock.ATTACHMENT, BellAttachType.FLOOR), 3);
        // The three stalls: a workstation each, a bed behind each.
        Object[][] stalls = {
            { VillagerProfession.FARMER, Blocks.COMPOSTER, new int[]{ 5, 0 }, Blocks.RED_BED },
            { VillagerProfession.LIBRARIAN, Blocks.LECTERN, new int[]{ -5, 0 }, Blocks.BLUE_BED },
            { VillagerProfession.CLERIC, Blocks.BREWING_STAND, new int[]{ 0, 5 }, Blocks.PURPLE_BED },
        };
        List<Villager> folk = new ArrayList<>();
        for (Object[] s : stalls) {
            int[] o = (int[]) s[2];
            BlockPos station = ground.offset(o[0], 0, o[1]);
            level.setBlock(station, ((Block) s[1]).defaultBlockState(), 3);
            BlockPos bedFoot = station.offset(Integer.signum(o[0]) * 2, 0, Integer.signum(o[1]) * 2);
            Direction lie = o[0] != 0 ? (o[0] > 0 ? Direction.EAST : Direction.WEST) : Direction.SOUTH;
            BlockState bed = ((Block) s[3]).defaultBlockState().setValue(BedBlock.FACING, lie);
            level.setBlock(bedFoot, bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
            level.setBlock(bedFoot.relative(lie), bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
            Villager vg = EntityType.VILLAGER.create(level);
            if (vg == null) continue;
            BlockPos stand = station.offset(-Integer.signum(o[0]), 0, -Integer.signum(o[1]));
            vg.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, level.getRandom().nextFloat() * 360F, 0F);
            vg.finalizeSpawn(level, level.getCurrentDifficultyAt(stand), MobSpawnType.COMMAND, null);
            vg.setVillagerData(vg.getVillagerData().setProfession((VillagerProfession) s[0]).setLevel(1));
            vg.setVillagerXp(0);
            vg.getOffers();                                       // the game's own first offers for the trade
            level.addFreshEntity(vg);
            employ(level, vg, station, bell);
            folk.add(vg);
            out.add("EMERALD-STAGE " + EmeraldTrader.villagerName(vg.getUUID()) + " the " + EmeraldTrader.tradeOf(vg) + ": "
                + EmeraldTrader.deals(vg).size() + " offers");
        }
        VanillaVillages.Known k = VanillaVillages.recordForTests(level, bell, 14);
        long day = level.getDayTime() / 24000L;
        EmeraldTrader.Hamlet h = EmeraldTrader.knowForTests(v, k, day);
        // The town's trader (taken on if it has none), set down at the village with goods to sell and emeralds to spend.
        List<VillageFolkEntity> have = EmeraldTrader.traders(v.id(), true);
        VillageFolkEntity f = have.isEmpty() ? EmeraldTrader.appoint(level, v) : have.get(0);
        if (f == null) {
            out.add("EMERALD-STAGE the town has nobody to send");
            return out;
        }
        if (f.expedition() != null) Scouts.abandon(level, f);
        EmeraldTrader.stageTrip(level, v, f, h, Map.of(Items.WHEAT, 60, Items.POTATO, 48, Items.CARROT, 48, Items.BEETROOT, 30,
            Items.PAPER, 48, Items.ROTTEN_FLESH, 32), 40, List.of(new ItemStack(Items.BOOK, 2)));
        BlockPos near = ground.offset(2, 0, -3);
        f.teleportTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
        EmeraldTrader.arriveForTests(level, f);
        out.add("EMERALD-STAGE " + f.displayNameCap() + " is at " + h.name() + " (" + bell.toShortString() + ") with the town's goods: "
            + folk.size() + " villagers to trade with");
        // Where the pictures are taken from, and what they look at (smoke.py's emerald_stage reads these).
        out.add(view("emerald-1-village", ground.offset(-11, 7, -13), ground.above()));
        out.add(view("emerald-2-farmer", ground.offset(6, 3, -6), ground.offset(4, 1, 0)));
        out.add(view("emerald-3-librarian", ground.offset(-6, 3, -6), ground.offset(-4, 1, 0)));
        return out;
    }

    private static String view(String name, BlockPos from, BlockPos at) {
        return "VIEW " + name + " " + from.getX() + " " + from.getY() + " " + from.getZ() + " " + at.getX() + " " + at.getY() + " " + at.getZ();
    }

    /**
     * A villager's place in its village, as its own brain takes it: its workstation for its job site and the bell for its
     * meeting place, each claimed at the game's point of interest there (a ticket taken, the memory set). A villager of a
     * trade, at its first level and never traded with, that has no job site gives up its trade (the game's own rule), so
     * a villager set down by hand is given its workstation at once, as one the world built has it.
     */
    public static void employ(ServerLevel level, Villager v, BlockPos station, @javax.annotation.Nullable BlockPos bell) {
        BlockPos at = station.immutable(), meet = bell == null ? null : bell.immutable();
        v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE,
            net.minecraft.core.GlobalPos.of(level.dimension(), at));
        if (meet != null) {
            v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT,
                net.minecraft.core.GlobalPos.of(level.dimension(), meet));
        }
        // The tickets after the blocks' points of interest are in: a block set from a command is registered as one a
        // moment later (the game queues it), and a ticket asked for before then finds nothing to take. Queued behind
        // them, it is taken once they are there; from a tick, at once.
        level.getServer().execute(() -> {
            var poi = level.getPoiManager();
            if (poi.exists(at, h -> true)) poi.take(h -> true, (h, p) -> p.equals(at), at, 1);
            if (meet != null && poi.exists(meet, h -> true)) poi.take(h -> true, (h, p) -> p.equals(meet), meet, 1);
        });
    }
}
