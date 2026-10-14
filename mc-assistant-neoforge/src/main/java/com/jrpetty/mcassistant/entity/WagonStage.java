package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.WagonRoute;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Variant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [wagons] Scenes for the pictures (/village wagon stage, tools/realworld/smoke.py wagons_stage): a town's wagon set
 * out where the camera can be, held still for it, with a mixed load showing (logs, wheat, iron, bread, stone, roots,
 * a crate), its animal in the shafts and its driver on the bench:
 * <ul>
 * <li>road: the open wagon on a stretch of worn road laid out past the town, from close by and from the side;</li>
 * <li>loading: the covered wagon in the town's own yard by the trading post, its back to the camera, the driver
 *     carrying crates out to it and the load rising;</li>
 * <li>night: the covered wagon on the stretch of road after dark, its two lanterns lit.</li>
 * </ul>
 * Each prints where to look from (VIEW name x y z ax ay az). /village wagon stage done lets the scene go: the load
 * back into the stores it was made for, the wagon parked, the driver and the animal free. The load is set down for
 * the picture (a stage, like the others, not a trip): it goes into the town's stores when the scene is let go.
 */
public final class WagonStage {

    private WagonStage() {}

    private static final String STAGED = "wagon.staged";

    /** A stage, or "done". Returns its lines (VIEW and what was set). */
    public static List<String> stage(ServerLevel level, BlockPos at, String what) {
        List<String> out = new ArrayList<>();
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        if (v == null) { out.add("WAGON none: no town near"); return out; }
        if (what.equals("done")) { done(level, v, out); return out; }
        WagonKind k = what.equals("road") ? WagonKind.WAGON : WagonKind.COVERED;
        TradeWagon w = Wagons.wagonOf(level, v.id());
        if (w != null && w.held) release(level, w);
        w = Wagons.giveForTests(level, v.id(), k);
        if (w == null) { out.add("WAGON none: it could not be set down"); return out; }
        VillageFolkEntity d = Caravans.choose(v);
        if (d == null) { out.add("WAGON none: nobody to drive it"); return out; }
        AbstractHorse h = horse(level, v, w);
        w.held = true;
        w.driver = d.getUUID();
        Wagons.DRIVING.put(d.getUUID(), w.getUUID());
        if (h != null) {
            w.animal = h.getUUID();
            Wagons.HARNESSED.put(h.getUUID(), w.getUUID());
        }
        Ledger.note(v.id(), STAGED, "1");
        for (ItemStack s : List.of(new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.WHEAT, 64), new ItemStack(Items.IRON_INGOT, 32),
                new ItemStack(Items.BREAD, 48), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.POTATO, 40),
                new ItemStack(Items.COAL, 32), new ItemStack(Items.SPRUCE_LOG, 32))) {
            Wagons.put(w, s);
        }
        w.cargoChanged();
        if (what.equals("loading")) {
            Wagons.Spot p = Wagons.park(level, v);
            w.moveTo(p.pos().getX() + 0.5, p.pos().getY(), p.pos().getZ() + 0.5, p.yaw(), 0.0F);
            w.from = v.id();
            w.to = v.id();
            w.setShownFill(0);
            w.setPhase(TradeWagon.Phase.LOADING);
            Vec3 f = w.forward();
            // From behind and to one side, a little above: into the open back, the driver coming with a crate.
            view(out, "wagon-3-loading", w.getX() - f.x * 9 + f.z * 4, w.getY() + 3, w.getZ() - f.z * 9 - f.x * 4, w.getX(), w.getY() + 1, w.getZ());
        } else {
            BlockPos start = strip(level, at, 48);
            double[] xs = { start.getX() + 0.5, start.getX() + 48.5 }, zs = { start.getZ() + 0.5, start.getZ() + 0.5 };
            w.route = new WagonRoute(xs, zs, new int[]{ -1, -1 });
            w.s = 20;
            w.lane = 0;
            w.setPhase(TradeWagon.Phase.ROAD);
            w.setShownFill(w.realFill());
            w.place(level);
            w.setYRot(-90.0F);
            w.place(level);
            double x = w.getX(), y = w.getY(), z = w.getZ();
            if (what.equals("road")) {
                view(out, "wagon-1-road-close", x - 2.5, y + 4.2, z + 4.5, x, y + 1.0, z);
                view(out, "wagon-2-road-side", x + 1, y + 1.8, z + 10, x + 1, y + 1.2, z);
            } else {
                view(out, "wagon-4-night", x + 8, y + 2.2, z + 6, x + 1.5, y + 1.6, z);
            }
        }
        if (h != null) {
            Vec3 hitch = w.spot(level, TradeWagon.HITCH, 0);
            h.moveTo(hitch.x, hitch.y, hitch.z, w.getYRot(), 0.0F);
        }
        d.stopRiding();
        if (!what.equals("loading")) {
            d.startRiding(w, true);
            d.setPose(Pose.SITTING);
        }
        out.add("WAGON " + k.label + " of " + Villages.name(v.id()) + " at " + w.blockPosition().toShortString() + " driven by " + d.displayNameCap()
            + (h == null ? "" : ", pulled by " + Wagons.animalWord(level, w)) + ", " + w.realFill() + "% full: " + w.cargoSummary());
        return out;
    }

    private static void view(List<String> out, String name, double x, double y, double z, double ax, double ay, double az) {
        out.add("VIEW " + name + " " + (int) Math.floor(x) + " " + (int) Math.floor(y) + " " + (int) Math.floor(z) + " " + (int) Math.floor(ax)
            + " " + (int) Math.floor(ay) + " " + (int) Math.floor(az));
    }

    /** A horse of the town's for the scene: one from its stable if it has one free, else a bay brought in for it. */
    @Nullable
    private static AbstractHorse horse(ServerLevel level, Villages.Village v, TradeWagon w) {
        AbstractHorse h = Wagons.draught(level, v, w);
        if (h != null) return h;
        Horse n = EntityType.HORSE.create(level);
        if (n == null) return null;
        n.setTamed(true);
        n.setOwnerUUID(v.id());
        n.setVariant(Variant.CHESTNUT);
        n.moveTo(w.getX() + 3, w.getY(), w.getZ(), 0, 0);
        n.addTag("mca_stage");
        return level.addFreshEntity(n) ? n : null;
    }

    /** A stretch of worn road three wide and this long, east from here, on levelled ground with air above. */
    private static BlockPos strip(ServerLevel level, BlockPos at, int length) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) - 1;
        for (int dx = -2; dx <= length + 2; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos p = new BlockPos(at.getX() + dx, y, at.getZ() + dz);
                level.setBlock(p, Math.abs(dz) <= 1 ? Blocks.DIRT_PATH.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                for (int up = 1; up <= 5; up++) level.setBlock(p.above(up), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(p.below(), Blocks.DIRT.defaultBlockState(), 3);
            }
        }
        return new BlockPos(at.getX(), y + 1, at.getZ());
    }

    /** The scene let go: the load into the town's stores, the wagon parked, the crew free. */
    private static void done(ServerLevel level, Villages.Village v, List<String> out) {
        TradeWagon w = Wagons.wagonOf(level, v.id());
        if (w == null || !w.held) { out.add("WAGON no scene to let go"); return; }
        release(level, w);
        out.add("WAGON scene let go; the wagon parked in its yard");
    }

    private static void release(ServerLevel level, TradeWagon w) {
        Villages.Village v = Villages.get(w.town);
        for (int i = 0; i < w.cargo.getContainerSize(); i++) {
            ItemStack s = w.cargo.removeItemNoUpdate(i);
            if (!s.isEmpty() && v != null) Crafts.store(level, v, s);
        }
        w.held = false;
        AbstractHorse h = Wagons.animal(level, w);
        Wagons.home(w, level);
        if (h != null && h.getTags().contains("mca_stage")) h.discard();
        if (v != null) Ledger.note(v.id(), STAGED, "");
    }

    /** Was the scene's horse brought in for it (and so goes again)? */
    static boolean staged(UUID town) {
        return "1".equals(Ledger.note(town, STAGED));
    }
}
