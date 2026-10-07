package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [cartographer] The pictures' stage (/village maps stage, an operator's): the map room set up on ground of its own
 * where it is run (out of a palette, as a showcase's buildings are) and put on the town's books if the town has none;
 * a hall beside it if the town has none either; the town's cartographer chosen; the makings put in the stores for the
 * pictures (paper, panes, frames, wool); the hall's map walked and hung at once, every sheet filled by the game's own
 * map update from the stations it stands at, the town's places marked with their banners; and the player given a
 * real ocean explorer map, the world searched as a cartographer villager searches it, to the nearest monument. The
 * views: the wall, the cartographer at its table, a banner, and where to stand with the map in hand.
 */
public final class MapStage {

    private MapStage() {}

    static List<String> stage(ServerLevel level, BlockPos at, Villages.Village v, @Nullable ServerPlayer player) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        // The map room (and a hall, if the town has none), side by side on a stage of their own.
        BlockPos ground = new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
        if (Cartographers.mapRoom(id) == null) {
            Showcase.stage(level, ground.getX() - 6, ground.getX() + 6, ground.getZ() - 6, ground.getZ() + 8, ground.getY());
            BuildGoal.stamp(level, Cartographers.STRUCTURE, ground, Direction.NORTH, 13, Showcase.painter(Showcase.SPRUCE));
            Ledger.built(id, Cartographers.STRUCTURE, ground, Direction.NORTH);
            out.add("STAGE the map room put up at " + ground.toShortString());
        }
        if (MapRoom.hall(id) == null) {
            BlockPos h = ground.offset(18, 0, 0);
            h = new BlockPos(h.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, h.getX(), h.getZ()), h.getZ());
            Showcase.stage(level, h.getX() - 7, h.getX() + 7, h.getZ() - 11, h.getZ() + 11, h.getY());
            BuildGoal.stamp(level, "hall", h, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "hall", h, Direction.NORTH);
            out.add("STAGE a hall put up at " + h.toShortString());
        }
        // The cartographer.
        VillageFolkEntity f = Cartographers.cartographer(id);
        if (f == null) f = Cartographers.appointForTests(level, v);
        if (f == null) {
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity g && !g.isBaby() && g.isAlive()) { f = g; break; }
            }
            if (f != null) {
                Ledger.Building room = Cartographers.mapRoom(id);
                f.setStation(room.anchor(), AssistantEntity.StationTask.CARTOGRAPHER);
                f.assignPlot(WorkZone.around(room.anchor(), 4, WorkZone.DEFAULT_DEPTH), "The Map Room");
            }
        }
        if (f == null) {
            out.add("STAGE nobody to be the cartographer");
            return out;
        }
        out.add("STAGE cartographer " + f.displayNameCap());
        // The makings, for the pictures.
        for (ItemStack s : new ItemStack[]{ new ItemStack(Items.PAPER, 64), new ItemStack(Items.GLASS_PANE, 24), new ItemStack(Items.ITEM_FRAME, 12),
            new ItemStack(Items.WHITE_WOOL, 48), new ItemStack(Items.OAK_PLANKS, 32), new ItemStack(Items.COMPASS, 4),
            new ItemStack(Items.CARTOGRAPHY_TABLE), new ItemStack(Items.OAK_SIGN, 2) }) {
            Market.intoStores(level, id, s);
        }
        Villages.forgetStock();
        Villages.forgetStores(id);
        BlockPos table = Cartographers.tableAt(level, id);
        if (table == null) {
            String t = Cartographers.setTableForTests(level, v, f);
            table = Cartographers.tableAt(level, id);
            out.add("STAGE table " + (t == null ? "none" : t));
        }
        // The hall's wall, walked and hung now.
        MapSurveys.forget(id);
        MapSurveys.Survey s = MapSurveys.beginWall(level, v, f, level.getDayTime() / 24000L);
        if (s != null) {
            MapSurveys.runToEnd(level, v, f, s);
            int filled = 0;
            for (ItemStack m : MapRoom.mapsForTests(level, id)) filled += MapSurveys.coloured(net.minecraft.world.item.MapItem.getSavedData(m, level));
            out.add("STAGE wall hung: " + MapRoom.framesForTests(id).size() + " sheets, " + filled + " pixels filled");
        } else {
            out.add("STAGE no wall: " + MapSurveys.WANTS.getOrDefault(id, "?"));
        }
        // The cartographer back at its table, the camera by the door looking past it at the table and the bare wall over
        // it (the table stands at the back wall, so the room's middle is towards the door from it).
        Ledger.Building room = Cartographers.mapRoom(id);
        if (table != null && room != null) {
            int dx = room.anchor().getX() - table.getX(), dz = room.anchor().getZ() - table.getZ();
            Direction front = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
            BlockPos stand = table.relative(front);
            float yaw = front.getOpposite().toYRot();
            f.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, yaw, 0.0F);
            f.setYHeadRot(yaw);
            f.getNavigation().stop();
            out.add(view("maps-table", table.relative(front, 4).relative(front.getClockWise()), table));
        }
        // The wall's view: out in the hall before its middle, the eye level with it.
        List<BlockPos> frames = MapRoom.framesForTests(id);
        if (!frames.isEmpty()) {
            net.minecraft.world.entity.decoration.ItemFrame fr = MapRoom.frame(level, frames.get(0));
            Direction face = fr == null ? Direction.SOUTH : fr.getDirection();
            // The middle of the wall: the middle frame of a three-by-three; for a two-by-two its bottom-right sheet, half a
            // block off its middle, which from four blocks out still has the whole wall in the picture.
            BlockPos mid = frames.size() == 9 ? frames.get(4) : frames.get(frames.size() - 1);
            BlockPos cam = mid.relative(face, frames.size() == 9 ? 5 : 4).below();
            out.add(view("maps-wall", cam, mid));
            out.add("HELD " + cam.getX() + " " + cam.getY() + " " + cam.getZ() + " " + mid.getX() + " " + mid.getY() + " " + mid.getZ());
        }
        // The hall's banner from the street: the camera out from the hall past it, a little to one side.
        BlockPos banner = MapSurveys.keptBanner(id, "Hall");
        Ledger.Building hall = MapRoom.hall(id);
        if (banner != null && hall != null) {
            double bx = banner.getX() - hall.anchor().getX(), bz = banner.getZ() - hall.anchor().getZ(), len = Math.max(1.0, Math.hypot(bx, bz));
            BlockPos cam = banner.offset((int) Math.round(bx / len * 5 - bz / len * 2), 1, (int) Math.round(bz / len * 5 + bx / len * 2));
            cam = new BlockPos(cam.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cam.getX(), cam.getZ()) + 1, cam.getZ());
            out.add(view("maps-banner", cam, banner.above()));
        }
        // A real ocean explorer map for the player: the world's own search, as far as a cartographer villager looks.
        if (player != null) {
            BlockPos mon = level.findNearestMapStructure(StructureTags.ON_OCEAN_EXPLORER_MAPS, v.centre(), 100, false);
            if (mon != null) {
                Holder<Structure> h = null;
                ItemStack map = MapFinds.explorerMap(level, v, MapFinds.Place.MONUMENT, new MapFinds.Spot(mon, h), f.displayNameCap(),
                    level.getDayTime() / 24000L);
                player.setItemSlot(EquipmentSlot.MAINHAND, map);
                out.add("STAGE an ocean explorer map in hand, to the monument at " + mon.toShortString() + " ("
                    + (int) Math.sqrt(Scouts.flat(v.centre(), mon)) + " blocks " + Guide.direction(v.centre(), mon) + ")");
            } else {
                out.add("STAGE no monument within a hundred chunks");
            }
        }
        return out;
    }

    /**
     * The trade at work, for the pictures (/village maps stage walk): the cartographer sent out now on the region's
     * walk, a fresh sheet in its pack (a sheet of paper put in the stores for it if they have none), so it is seen out
     * on the road with the sheet held up, filling in as it goes. "WALKER uuid": whom the camera follows.
     */
    static List<String> walk(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        VillageFolkEntity f = Cartographers.cartographer(id);
        if (f == null) {
            out.add("STAGE no cartographer: run /village maps stage first");
            return out;
        }
        MapSurveys.Survey s = MapSurveys.of(id);
        if (s == null) {
            if (Crafts.stock(level, v, x -> x.is(Items.PAPER)) < 1) Market.intoStores(level, id, new ItemStack(Items.PAPER, 4));
            Villages.forgetStores(id);
            s = MapSurveys.beginRegion(level, v, f, level.getDayTime() / 24000L);
        }
        if (s == null) {
            out.add("STAGE no walk: " + MapSurveys.WANTS.getOrDefault(id, "?"));
            return out;
        }
        out.add("STAGE " + f.displayNameCap() + " out on the " + s.kind().name().toLowerCase(java.util.Locale.ROOT) + " walk, " + s.stops()
            + " stops" + (MapSurveys.daylight(level.getDayTime()) ? "" : " (it waits for the morning)"));
        out.add("WALKER " + f.getStringUUID());
        return out;
    }

    static String view(String name, BlockPos cam, BlockPos at) {
        return "VIEW " + name + " " + cam.getX() + " " + cam.getY() + " " + cam.getZ() + " " + at.getX() + " " + at.getY() + " " + at.getZ();
    }
}
