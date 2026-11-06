package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.PlaceSettingBlock;
import com.jrpetty.mcassistant.block.PlaceSettingBlockEntity;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.DiningItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * [dining] A scene for the pictures (/village dining stage; tools/realworld/smoke.py dining_stage): the nearest town's
 * restaurant (built beside here, to its drawing, if it has none), its staff taken on out of its folk, the evening's diners
 * sat at its tables ordering what the stores hold (a few roasts' makings and bottles of cider put in for the camera, said
 * so), the kitchen set to work. Says where to look from (VIEW name x y z ax ay az: the eyes' feet and what they look at):
 * the dining room from its door, a table close up, the kitchen from the pass. The close-up's table is the first diner's,
 * its place laid at once with a roast and a cider for the camera (the evening's own plates come as the kitchen gets to
 * them, later than the picture can wait). "menu" opens the order screen at a free table for the player, the staff
 * called back to their posts first (a host at the counter: the screen is open, not "no host"); "done" lets everyone go
 * home.
 */
public final class DiningStage {

    private DiningStage() {}

    /** How many diners the scene seats (the restaurant's six tables, one kept for the player). */
    static final int DINERS = 5;

    public static List<String> stage(ServerLevel level, BlockPos at, String which, ServerPlayer player) {
        List<String> out = new ArrayList<>();
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        if (v == null) {
            out.add("No village near enough.");
            return out;
        }
        if (which.equals("done")) {
            for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && Diners.dining(f)) Seats.stand(f, null);
            Diners.DINERS.clear();
            out.add("The diners have gone home.");
            return out;
        }
        DiningPlaces.Place p = restaurant(level, v, at, out);
        if (p == null) return out;
        if (which.equals("menu")) {
            staff(level, v, p, out);                                   // on duty for the shot: the town may have moved them on
            DiningPlaces.Table t = Diners.freeTable(level, p, null);
            if (t != null) player.teleportTo(level, t.seat().getX() + 0.5, t.seat().getY() + 0.5, t.seat().getZ() + 0.5,
                t.looks().toYRot(), 20.0F);
            ServicePoints.openDining(player, p, t);
            out.add("The menu open" + (t == null ? "" : " at table " + t.number()));
            return out;
        }
        staff(level, v, p, out);
        Crafts.store(level, v, new ItemStack(Items.BEEF, 6));
        Crafts.store(level, v, new ItemStack(Items.COD, 3));
        Crafts.store(level, v, Cafe.drink(Cafe.DRINKS.get(0)).copyWithCount(4));
        out.add("For the camera: six raw beef, three cod and four bottles of cider into the stores");
        int seated = 0;
        DiningPlaces.Table close = null;
        VillageFolkEntity closeDiner = null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (seated >= DINERS) break;
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.stationTask() == AssistantEntity.StationTask.COOK || Diners.dining(f)) continue;
            if (f.purse() < Diners.MIN_PURSE) continue;
            if (Diners.start(level, f, p) == null) break;
            DiningPlaces.Table t = p.table(Diners.DINERS.get(f.getUUID()).table);
            f.teleportTo(t.seat().getX() + 0.5, t.seat().getY() + 0.5, t.seat().getZ() + 0.5);
            out.add("DINER " + f.displayNameCap() + " at table " + t.number());
            if (close == null) {
                close = t;
                closeDiner = f;
            }
            seated++;
        }
        if (close == null && !p.tables.isEmpty()) close = p.tables.get(0);
        if (close != null && cameraPlate(level, p, close, closeDiner)) out.add("For the camera: a roast and a cider laid at table " + close.number());
        BlockPos door = p.door != null ? p.door : p.centre();
        Direction out0 = StoreFloor.front(p.centre(), door);
        BlockPos eye = door.relative(out0.getOpposite(), 1).above();
        BlockPos back = p.centre().relative(out0.getOpposite(), 4);
        out.add(view("dining-room", eye.getX(), eye.getY() + 1, eye.getZ(), back.getX(), back.getY(), back.getZ()));
        if (close != null) {
            BlockPos c = close.cell();
            out.add(view("plate", c.getX() + 1, c.getY() + 1, c.getZ() + 1, c.getX(), c.getY(), c.getZ()));
        }
        BlockPos stove = p.station(DiningPlaces.Station.SMOKER);
        out.add(view("kitchen", p.pass.getX(), p.pass.getY() + 2, p.pass.getZ(), stove.getX(), stove.getY(), stove.getZ()));
        out.add("The evening's service is on: " + seated + " diners at " + p.words());
        return out;
    }

    private static String view(String name, int x, int y, int z, int ax, int ay, int az) {
        return "VIEW " + name + " " + x + " " + y + " " + z + " " + ax + " " + ay + " " + az;
    }

    /** The town's restaurant, or one built here to its drawing (for the camera: a building of the town from then on). */
    private static DiningPlaces.Place restaurant(ServerLevel level, Villages.Village v, BlockPos at, List<String> out) {
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (b.structure().equals(DiningPlaces.RESTAURANT) && level.isLoaded(b.anchor())) {
                out.add("RESTAURANT " + b.anchor().getX() + " " + b.anchor().getY() + " " + b.anchor().getZ());
                return DiningPlaces.place(level, v.id(), b);
            }
        }
        BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.offset(12, 0, 0));
        BuildGoal.stamp(level, DiningPlaces.RESTAURANT, ground, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(v.id(), DiningPlaces.RESTAURANT, ground, Direction.NORTH);
        Villages.noteProject(v.id(), DiningPlaces.RESTAURANT, level.getGameTime());
        ServicePoints.fitOutForTests(level, v);
        DiningPlaces.forget();
        out.add("RESTAURANT built " + ground.getX() + " " + ground.getY() + " " + ground.getZ());
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (b.structure().equals(DiningPlaces.RESTAURANT) && b.anchor().equals(ground)) return DiningPlaces.place(level, v.id(), b);
        }
        return null;
    }

    /**
     * For the camera: a table's place laid with a roast on its plate and a cider in its cup, the diner's there (it eats
     * them as it would its own order). False if the table cannot take a setting.
     */
    private static boolean cameraPlate(ServerLevel level, DiningPlaces.Place p, DiningPlaces.Table t, @javax.annotation.Nullable VillageFolkEntity diner) {
        BlockPos cell = t.cell();
        net.minecraft.world.level.block.state.BlockState here = level.getBlockState(cell);
        net.minecraft.world.level.block.state.BlockState laid;
        if (here.getBlock() instanceof PlaceSettingBlock) {
            laid = here.setValue(PlaceSettingBlock.HOUSE, true).setValue(PlaceSettingBlock.DISH, PlaceSettingBlock.Dish.EMPTY)
                .setValue(PlaceSettingBlock.CUP, PlaceSettingBlock.Cup.FULL);
        } else {
            boolean cloth = PlaceSettingBlock.isCloth(here);
            if (!cloth && !here.isAir() || !PlaceSettingBlock.onATable(level, cell)) return false;
            laid = DiningItems.PLACE_SETTING.get().defaultBlockState().setValue(PlaceSettingBlock.CLOTH, cloth).setValue(PlaceSettingBlock.HOUSE, true)
                .setValue(PlaceSettingBlock.DISH, PlaceSettingBlock.Dish.EMPTY).setValue(PlaceSettingBlock.CUP, PlaceSettingBlock.Cup.FULL);
        }
        level.setBlock(cell, laid, 3);
        if (!(level.getBlockEntity(cell) instanceof PlaceSettingBlockEntity be)) return false;
        if (!(here.getBlock() instanceof PlaceSettingBlock)) be.layOn(here);
        ItemStack roast = new ItemStack(Items.COOKED_BEEF);
        java.util.UUID who = diner == null ? null : diner.getUUID();
        String name = diner == null ? "" : diner.displayNameCap();
        be.serveFood(roast, PlaceSettingBlock.bitesFor(roast), p.village, who, name, 0, 1);
        be.serveDrink(Cafe.drink(Cafe.DRINKS.get(0)), PlaceSettingBlock.SIPS, p.village, who, name, 0);
        PlaceSettingBlock.refresh(level, cell);
        return true;
    }

    /** The host, the head cook, a waiter and a pot washer: the town's own, else taken on out of its folk; put in the
     *  restaurant (the host at its counter). */
    private static void staff(ServerLevel level, Villages.Village v, DiningPlaces.Place p, List<String> out) {
        Hospitality.Role[] want = { Hospitality.Role.HOST, Hospitality.Role.HEAD_COOK, Hospitality.Role.WAITER, Hospitality.Role.POT_WASHER };
        ServicePoints.Point counter = ServicePoints.nearest(level, v.id(), ServiceRole.RESTAURANT, p.centre());
        for (Hospitality.Role r : want) {
            VillageFolkEntity f = Hospitality.first(v.id(), r);
            if (f == null) {
                for (AssistantEntity a : Villages.folkOf(v.id())) {
                    if (a instanceof VillageFolkEntity c && !c.isBaby() && Hospitality.role(c) == Hospitality.Role.NONE
                            && c.stationTask() != AssistantEntity.StationTask.GUARD) { f = c; break; }
                }
                if (f == null) continue;
                Hospitality.appointForTests(level, v, f, r);
            } else if (f.stationTask() != AssistantEntity.StationTask.COOK) {
                Hospitality.appointForTests(level, v, f, r);                      // moved on to other work meanwhile: back
            }
            BlockPos at = r == Hospitality.Role.HOST && counter != null && counter.stand() != null ? counter.stand() : p.centre();
            f.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            out.add("STAFF " + r.title + " " + f.displayNameCap());
        }
    }
}
