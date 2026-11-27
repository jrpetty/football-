package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [links12] The pictures' stage for the region (/village region stage, operators): two Iron Age towns raised beside where
 * it is run, a river cut across the ground between them, the road surveyed and both halves made gravel out of their
 * stores (the bridge with it), and the railway between them planned and laid for nothing with its stations. Also the
 * tests' way in to the railway's plan.
 */
public final class RegionStage {

    private RegionStage() {}

    /** [tests, the stage] The railway between two towns planned now (the iron not waited for); null if no way would do. */
    @Nullable
    public static Railways.Line planRail(ServerLevel level, Villages.Village a, Villages.Village b) {
        Villages.Village first = a.id().toString().compareTo(b.id().toString()) <= 0 ? a : b, second = first == a ? b : a;
        return Railways.planLink(level, first, second, level.getDayTime() / 24000L);
    }

    /** [tests, the stage] Both halves of the railway between two towns laid for nothing, both stations up: open. */
    public static boolean layRailFree(ServerLevel level, Villages.Village a, Villages.Village b) {
        Railways.Line la = Railways.linkTo(a.id(), b.id()), lb = Railways.linkTo(b.id(), a.id());
        if (la == null || lb == null) return false;
        Railways.layFreeForStage(level, a, la);
        Railways.layFreeForStage(level, b, lb);
        return Railways.linkOpen(a.id(), b.id());
    }

    /** [tests] Would these two lay a railway between them now (the iron and the mine's line not asked for)? */
    public static boolean wantsRailForTests(ServerLevel level, Villages.Village a, Villages.Village b) {
        Railways.planAnywayForTests(true);
        try {
            Villages.Village first = a.id().toString().compareTo(b.id().toString()) <= 0 ? a : b, second = first == a ? b : a;
            return Railways.wantsLink(level, first, second);
        } finally {
            Railways.planAnywayForTests(false);
        }
    }

    /** [tests] The way a caravan from one town to the other walks (Caravans.way). */
    public static List<BlockPos> wayForTests(Villages.Village from, Villages.Village to) {
        return Caravans.way(from, to);
    }

    /** [tests] A town's visit's catching up on what its road gang laid while it slept (Routes.catchUp). */
    public static void catchUpForTests(ServerLevel level, Villages.Village v) {
        Routes.catchUp(level, v, RoadWorks.workingOn(v));
    }

    /** [tests] The road gang set on the road to that town (as RoadWorks.tick sets it). */
    public static void workOnForTests(UUID me, UUID other) {
        Ledger.note(me, RoadWorks.WORKING, other.toString());
    }

    static List<String> stage(ServerLevel level, BlockPos at) {
        List<String> out = new ArrayList<>();
        int x0 = at.getX() + 24, z0 = at.getZ(), x1 = x0 + 200, z1 = z0 + 36;
        for (int cx = (x0 - 80) >> 4; cx <= (x1 + 80) >> 4; cx++) {
            for (int cz = (z0 - 90) >> 4; cz <= (z1 + 90) >> 4; cz++) level.setChunkForced(cx, cz, true);
        }
        // The river: seven wide, three deep, across the ground between them.
        int rx = (x0 + x1) / 2 + 6;
        for (int z = z0 - 90; z <= z1 + 90; z++) {
            for (int x = rx; x < rx + 7; x++) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                int surface = Math.min(y, level.getSeaLevel() + 4);
                for (int dy = 0; dy < 24; dy++) level.setBlock(new BlockPos(x, surface + 1 + dy, z), Blocks.AIR.defaultBlockState(), 2 | 16);
                for (int dy = 0; dy < 3; dy++) level.setBlock(new BlockPos(x, surface - dy, z), Blocks.WATER.defaultBlockState(), 2 | 16);
                level.setBlock(new BlockPos(x, surface - 3, z), Blocks.SAND.defaultBlockState(), 2 | 16);
            }
        }
        Villages.Village a = town(level, x0, z0), b = town(level, x1, z1);
        if (a == null || b == null) {
            out.add("no towns raised");
            return out;
        }
        for (Villages.Village v : new Villages.Village[]{ a, b }) {
            Villages.ageForTests(v.id(), Villages.Age.IRON);
            Ledger.addCoins(v.id(), 400);
        }
        RoadWorks.Road r = RoadWorks.road(a, b);
        if (r == null) {
            out.add("no road between them");
            return out;
        }
        for (int i = 0; i < r.length(); i += 8) level.getChunk(r.line().get(i)[0] >> 4, r.line().get(i)[1] >> 4);   // the ground along it awake
        for (Villages.Village v : new Villages.Village[]{ a, b }) {
            UUID o = r.other(v.id());
            for (int g = 1; g <= 2; g++) {
                RoadWorks.setHalfForTests(v.id(), o, g - 1, 0, 20);
                RoadWorks.work(level, v, r, 2000, true);
            }
        }
        out.add("ROAD " + r.length() + " steps, grade " + RoadWorks.grade(a.id(), b.id()) + ", over water " + Routes.bridges(a.id(), b.id(), r.line()));
        Railways.Line rail = planRail(level, a, b);
        boolean open = rail != null && layRailFree(level, a, b);
        out.add("RAIL " + (rail == null ? "no plan" : rail.length() + " rails, open " + open));
        int[] mid = r.line().get(r.length() / 2);
        int my = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mid[0], mid[1]);
        out.add("VIEW road " + (mid[0] - 40) + " " + (my + 45) + " " + (mid[1] + 70) + " " + mid[0] + " " + my + " " + mid[1]);
        for (int i = 0; i < r.length(); i++) {
            int[] p = r.line().get(i);
            if (p[0] >= rx && p[0] < rx + 7) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p[0], p[1]);
                out.add("VIEW bridge " + (p[0] + 14) + " " + (y + 8) + " " + (p[1] + 16) + " " + p[0] + " " + y + " " + p[1]);
                break;
            }
        }
        if (rail != null) {
            BlockPos s = rail.rail(2);
            out.add("VIEW station " + (s.getX() + 9) + " " + (s.getY() + 6) + " " + (s.getZ() + 9) + " " + s.getX() + " " + s.getY() + " " + s.getZ());
        }
        out.add("TOWNS " + Villages.name(a.id()) + " " + a.centre().toShortString() + " / " + Villages.name(b.id()) + " " + b.centre().toShortString());
        return out;
    }

    /** The stage's railway, again (its plan waits on the ground being loaded the first time): from this town to its nearest neighbour. */
    static List<String> stageRail(ServerLevel level, @Nullable Villages.Village v) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        Villages.Village o = null;
        for (Villages.Village t : Villages.every()) {
            if (t.id().equals(v.id()) || !t.dim().equals(v.dim())) continue;
            if (o == null || t.centre().distSqr(v.centre()) < o.centre().distSqr(v.centre())) o = t;
        }
        if (o == null) return out;
        Railways.Line rail = Railways.linkTo(v.id(), o.id());
        if (rail == null) rail = planRail(level, v, o);
        boolean open = rail != null && layRailFree(level, v, o);
        out.add("RAIL " + (rail == null ? "no plan yet (the ground is being loaded: run it again)" : rail.length() + " rails, open " + open));
        if (rail != null) {
            BlockPos s = rail.rail(2);
            out.add("VIEW station " + (s.getX() + 9) + " " + (s.getY() + 6) + " " + (s.getZ() + 9) + " " + s.getX() + " " + s.getY() + " " + s.getZ());
            BlockPos m = rail.rail(rail.length() / 2);
            out.add("VIEW line " + (m.getX() + 20) + " " + (m.getY() + 18) + " " + (m.getZ() + 20) + " " + m.getX() + " " + m.getY() + " " + m.getZ());
        }
        return out;
    }

    @Nullable
    private static Villages.Village town(ServerLevel level, int x, int z) {
        BlockPos heart = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        if (first == null || first.ownerId() == null) return null;
        for (int i = 1; i < 8; i++) {
            BlockPos p = heart.offset(2 + i % 4, 0, 2 + i / 4);
            VillageFolkSpawnerBlock.raise(level, new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ()), 0.0F);
        }
        BlockPos c = heart.offset(-3, 0, -5);
        level.setBlock(c, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, c);
        if (level.getBlockEntity(c) instanceof Container box) {
            ItemStack[] goods = { new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.GRAVEL, 64),
                new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.GRAVEL, 64),
                new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.TORCH, 32), new ItemStack(com.jrpetty.mcassistant.item.WorkItems.MILESTONE_ITEM.get(), 6) };
            for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
            box.setChanged();
        }
        Villages.forgetStock();
        return Villages.get(first.ownerId());
    }
}
