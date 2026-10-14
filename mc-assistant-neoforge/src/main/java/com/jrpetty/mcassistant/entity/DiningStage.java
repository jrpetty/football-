package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
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
 * the dining room from its door, a table close up, the kitchen from the pass. "menu" opens the order screen at a free
 * table for the player; "done" lets everyone go home.
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
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (seated >= DINERS) break;
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.stationTask() == AssistantEntity.StationTask.COOK || Diners.dining(f)) continue;
            if (f.purse() < Diners.MIN_PURSE) continue;
            if (Diners.start(level, f, p) == null) break;
            DiningPlaces.Table t = p.table(Diners.DINERS.get(f.getUUID()).table);
            f.teleportTo(t.seat().getX() + 0.5, t.seat().getY() + 0.5, t.seat().getZ() + 0.5);
            out.add("DINER " + f.displayNameCap() + " at table " + t.number());
            seated++;
        }
        BlockPos door = p.door != null ? p.door : p.centre();
        Direction out0 = StoreFloor.front(p.centre(), door);
        BlockPos eye = door.relative(out0.getOpposite(), 1).above();
        BlockPos back = p.centre().relative(out0.getOpposite(), 4);
        out.add(view("dining-room", eye.getX(), eye.getY() + 1, eye.getZ(), back.getX(), back.getY(), back.getZ()));
        if (!p.tables.isEmpty()) {
            BlockPos c = p.tables.get(0).cell();
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

    /** The head cook, a waiter and a pot washer: the town's own, else taken on out of its folk; put in the restaurant. */
    private static void staff(ServerLevel level, Villages.Village v, DiningPlaces.Place p, List<String> out) {
        Hospitality.Role[] want = { Hospitality.Role.HEAD_COOK, Hospitality.Role.WAITER, Hospitality.Role.POT_WASHER };
        for (Hospitality.Role r : want) {
            VillageFolkEntity f = Hospitality.first(v.id(), r);
            if (f == null) {
                for (AssistantEntity a : Villages.folkOf(v.id())) {
                    if (a instanceof VillageFolkEntity c && !c.isBaby() && Hospitality.role(c) == Hospitality.Role.NONE
                            && c.stationTask() != AssistantEntity.StationTask.GUARD) { f = c; break; }
                }
                if (f == null) continue;
                Hospitality.appointForTests(level, v, f, r);
            }
            f.teleportTo(p.centre().getX() + 0.5, p.centre().getY(), p.centre().getZ() + 0.5);
            out.add("STAFF " + r.title + " " + f.displayNameCap());
        }
    }
}
