package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [war-after] The pictures (/village afterwar stage): out past the town, on ground of its own, the town's watch house with
 * two prisoners of war in its cells and a stockade with two more in its pen (real folk of a small town founded 220 blocks
 * off for the purpose, taken prisoner the admin's way); a third town's ruins, 220 blocks the other way, given up and five
 * days gone to decay, its stores open; and, when a player is named, that player made an outlaw of the town, its poster on
 * the board. Returns
 * "VIEW name x y z ax ay az" lines (where a camera stands, and what it looks at) and what was done.
 */
public final class AfterWarStage {

    private AfterWarStage() {}

    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at, @Nullable ServerPlayer outlaw) {
        List<String> out = new ArrayList<>();
        int x = at.getX(), z = at.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // 1. The watch house and the stockade, side by side, the town's own.
        Showcase.stage(level, x - 8, x + 24, z - 8, z + 16, y);
        BlockPos house = new BlockPos(x, y, z), pen = new BlockPos(x + 16, y, z);
        BuildGoal.stamp(level, WatchHouse.STRUCTURE, house, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(v.id(), WatchHouse.STRUCTURE, house, Direction.NORTH);
        Villages.builtAtForTests(v.id(), WatchHouse.STRUCTURE, house);
        BuildGoal.stamp(level, Prisoners.STOCKADE, pen, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(v.id(), Prisoners.STOCKADE, pen, Direction.NORTH);
        Villages.builtAtForTests(v.id(), Prisoners.STOCKADE, pen);
        // 2. A small town of its own, far enough off to be one (a founding party joins any town within 192 blocks), to take
        //    prisoners from; or the nearest other town there is.
        Villages.Village from = AdminTools.foeOf(v);
        if (from == null) {
            int px = x, pz = z + 220, py = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
            Showcase.stage(level, px - 6, px + 6, pz - 6, pz + 6, py);
            VillageFolkSpawnerBlock.raiseParty(level, new BlockPos(px, py, pz), 0.0F, 6);
            from = Villages.nearest(level, new BlockPos(px, py, pz), 48);
        }
        if (from != null && !from.id().equals(v.id())) {
            int took = Prisoners.takeNow(level, v, from, 4);
            out.add("prisoners: " + took + " of " + Villages.name(from.id()) + " taken by " + Villages.name(v.id()));
            for (Prisoners.Pow p : Prisoners.heldBy(v.id())) {
                if (!(level.getEntity(p.who()) instanceof VillageFolkEntity f)) continue;
                Prisoners.Place place = Prisoners.placeFor(level, v, p);
                f.getNavigation().stop();
                f.moveTo(place.stand().getX() + 0.5, place.stand().getY(), place.stand().getZ() + 0.5, 180.0F, 0.0F);
                if (place.cell() != null) WatchHouse.setDoor(level, place.cell().door(), false);
            }
        }
        Ledger.Building wh = new Ledger.Building(WatchHouse.STRUCTURE, house, Direction.NORTH);
        BlockPos inside = WatchHouse.at(wh, 0, 1, -3), cells = WatchHouse.at(wh, 0, 1, 2);
        out.add("VIEW cells " + inside.getX() + " " + (inside.getY() + 1) + " " + inside.getZ() + " " + cells.getX() + " " + cells.getY() + " " + cells.getZ());
        out.add("VIEW stockade " + pen.getX() + " " + (y + 6) + " " + (pen.getZ() + 12) + " " + pen.getX() + " " + (y + 1) + " " + pen.getZ());
        // 3. A third town, as far off again, gone to ruin: its watch house, its stores, five days of decay.
        int rx = x + 220, rz = z, ry = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rx, rz);
        Showcase.stage(level, rx - 10, rx + 10, rz - 10, rz + 12, ry);
        BlockPos rheart = new BlockPos(rx, ry, rz + 8);
        VillageFolkSpawnerBlock.raiseParty(level, rheart, 0.0F, 3);
        Villages.Village dying = Villages.nearest(level, rheart, 48);
        if (dying != null && !dying.id().equals(v.id()) && (from == null || !dying.id().equals(from.id()))) {
            BlockPos ruinHouse = new BlockPos(rx, ry, rz - 2);
            BuildGoal.stamp(level, WatchHouse.STRUCTURE, ruinHouse, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(dying.id(), WatchHouse.STRUCTURE, ruinHouse, Direction.NORTH);
            List<BlockPos> stores = Villages.storeChests(level, dying.id());
            if (!stores.isEmpty() && level.getBlockEntity(stores.get(0)) instanceof Container c) {
                c.setItem(c.getContainerSize() - 1, new ItemStack(Items.BEEF, 12));
                c.setItem(c.getContainerSize() - 2, new ItemStack(Items.BREAD, 9));
                c.setChanged();
            }
            out.add("ruin: " + Ruins.ruinNow(level, dying));
            out.add("decay: " + Ruins.decayNow(level, dying.id(), 5) + " blocks");
            out.add("VIEW ruin " + (rx + 9) + " " + (ry + 7) + " " + (rz + 12) + " " + rx + " " + (ry + 2) + " " + (rz - 2));
        }
        // 4. The board, with an outlaw's poster on it.
        if (outlaw != null) out.add(AfterWar.outlawNow(level, v, outlaw));
        BlockPos board = VillageBoards.lectern(v.id());
        Direction facing = VillageBoards.facingOf(v.id());
        if (board != null && facing != null) {
            BlockPos eye = board.relative(facing, 4).above(2), face = board.relative(facing.getOpposite(), 2).above(2);
            out.add("VIEW board " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " " + face.getX() + " " + face.getY() + " " + face.getZ());
        }
        return out;
    }
}
