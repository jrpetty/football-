package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.RoadItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Variant;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [roles-e] "/village roads stage": the roads' posts and the watch's ranks set out for the pictures (tools/realworld/smoke.py
 * roles_e_stage) from palettes, stand-ins and prop wagons (nobody's, never driven: TradeWagon.prop), not the town's own:
 * the wheelwright's shed with an open wagon on the bench's floor before it and the wheelwright at its bench; a battered
 * covered wagon with the wheelwright mending it, planks in hand; a covered wagon in the shafts with the carter on the
 * bench in its driving coat, its whip in hand; the posts in their dress in a row (wheelwright, carter, town planner); and
 * the watch's ranks in a row (the captain with a brass band on each arm, two sergeants with theirs, three of the watch).
 * Returns "VIEW name x y z ax ay az" lines: where a camera stands, and what it looks at.
 */
public final class RoadsStage {

    private RoadsStage() {}

    public static List<String> stage(ServerLevel level, BlockPos at) {
        List<String> out = new ArrayList<>();
        int x = at.getX(), z = at.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // 1. The wheelwright's shed, its open bay to the south, a wagon before it and the wheelwright at its bench.
        Showcase.stage(level, x - 9, x + 9, z - 8, z + 14, y);
        BlockPos shed = new BlockPos(x, y, z);
        BuildGoal.stamp(level, Wheelwrights.SHED, shed, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        BlockPos bench = OutdoorStage.part(Wheelwrights.SHED, shed, BuildGoal.Part.CRAFTING_TABLE);
        if (bench != null) {
            VillageFolkEntity w = posted(level, bench.south(), StationTask.SHOP, "Wheelwright", 180.0F, "wheelwright", DyeColor.BROWN);
            if (w != null) {
                w.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
                w.getLookControl().setLookAt(bench.getX() + 0.5, bench.getY() + 0.5, bench.getZ() + 0.5);
            }
        }
        prop(level, new Vec3(x + 0.5, y, z + 7.5), 90.0F, WagonKind.WAGON, 1.0F);
        out.add("VIEW shed " + (x + 7) + " " + (y + 3) + " " + (z + 13) + " " + x + " " + (y + 2) + " " + z);
        if (bench != null) out.add("VIEW bench " + x + " " + (y + 1) + " " + (z + 2) + " " + bench.getX() + " " + bench.getY() + " " + bench.getZ());
        // 2. A battered covered wagon in a yard, the wheelwright at its side with planks.
        int mx = x + 26;
        Showcase.stage(level, mx - 7, mx + 7, z - 5, z + 9, y);
        TradeWagon battered = prop(level, new Vec3(mx + 0.5, y, z + 0.5), 0.0F, WagonKind.COVERED, 0.45F);
        if (battered != null) {
            VillageFolkEntity m = posted(level, Wheelwrights.side(level, battered), StationTask.SHOP, "Wheelwright", 270.0F, "wheelwright", DyeColor.BROWN);
            if (m != null) {
                m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.SPRUCE_PLANKS, 2));
                m.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.IRON_INGOT));
                m.getLookControl().setLookAt(battered.getX(), battered.getY() + 1.0, battered.getZ());
            }
        }
        out.add("VIEW mend " + (mx - 6) + " " + (y + 2) + " " + (z + 6) + " " + mx + " " + (y + 1) + " " + z);
        // 3. The carter on the bench of a laden covered wagon, a horse in the shafts, its whip in hand.
        int cx = x - 28;
        Showcase.stage(level, cx - 9, cx + 9, z - 6, z + 10, y);
        TradeWagon w = prop(level, new Vec3(cx + 0.5, y, z + 0.5), -90.0F, WagonKind.COVERED, 1.0F);
        if (w != null) {
            for (ItemStack s : List.of(new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.WHEAT, 64), new ItemStack(Items.IRON_INGOT, 32),
                    new ItemStack(Items.BREAD, 48))) Wagons.put(w, s);
            w.cargoChanged();
            w.setShownFill(w.realFill());
            Vec3 hitch = w.spot(level, TradeWagon.HITCH, 0);
            Horse h = EntityType.HORSE.create(level);
            if (h != null) {
                h.setVariant(Variant.CHESTNUT);
                h.setTamed(true);
                h.moveTo(hitch.x, hitch.y, hitch.z, w.getYRot(), 0.0F);
                h.setYHeadRot(w.getYRot());
                h.setYBodyRot(w.getYRot());
                h.setNoAi(true);
                h.addTag("folk_lineup");
                level.addFreshEntity(h);
            }
            VillageFolkEntity c = posted(level, BlockPos.containing(w.position()), StationTask.HAUL, "Carter", w.getYRot(), "carter", DyeColor.GREEN);
            if (c != null) {
                w.driver = c.getUUID();
                c.startRiding(w, true);
                c.setPose(Pose.SITTING);
                c.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(RoadItems.DRIVING_WHIP.get()));
            }
            Vec3 f = w.forward();
            // From ahead and to the side, a little above: the horse, the bench and its carter, the canvas behind.
            out.add("VIEW carter " + (int) Math.floor(w.getX() + f.x * 7 + f.z * 5) + " " + (y + 3) + " " + (int) Math.floor(w.getZ() + f.z * 7 - f.x * 5)
                + " " + (int) Math.floor(w.getX() + f.x * 1.5) + " " + (y + 2) + " " + (int) Math.floor(w.getZ() + f.z * 1.5));
        }
        // 4. The posts in their dress, in a row facing south.
        int pz = z + 26;
        Showcase.stage(level, x - 8, x + 8, pz - 4, pz + 10, y);
        posted(level, new BlockPos(x - 3, y, pz), StationTask.SHOP, "Wheelwright", 0.0F, "wheelwright", DyeColor.BROWN);
        VillageFolkEntity carter = posted(level, new BlockPos(x, y, pz), StationTask.HAUL, "Carter", 0.0F, "carter", DyeColor.GREEN);
        if (carter != null) carter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(RoadItems.DRIVING_WHIP.get()));
        VillageFolkEntity planner = posted(level, new BlockPos(x + 3, y, pz), StationTask.SHOP, "Town planner", 0.0F, "planner", DyeColor.BLUE);
        if (planner != null) planner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FILLED_MAP));
        out.add("VIEW posts " + x + " " + (y + 1) + " " + (pz + 8) + " " + x + " " + (y + 1) + " " + pz);
        // 5. The watch's ranks in a row facing south: the captain, two sergeants, three of the watch on the beat.
        int wz = z + 46;
        Showcase.stage(level, x - 10, x + 10, wz - 4, wz + 10, y);
        int[] flags = { 1 | 1 << 5, 1 | 1 << 8, 2 | 1 << 7 | 1 << 8, 2 | 1 << 7, 1, 2 | 1 << 7 };
        String[] names = { "Captain", "Sergeant", "Sergeant", "Guard", "Guard", "Guard" };
        for (int i = 0; i < flags.length; i++) {
            VillageFolkEntity g = OutdoorStage.standIn(level, new BlockPos(x - 5 + i * 2, y, wz), StationTask.GUARD, names[i], 0.0F);
            if (g == null) continue;
            g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            g.showPolice(flags[i]);
        }
        out.add("VIEW watch " + x + " " + (y + 1) + " " + (wz + 9) + " " + x + " " + (y + 1) + " " + wz);
        return out;
    }

    /** A stand-in in its trade's clothes and its post's dress (Weave.office), as the pictures want it. */
    @Nullable
    private static VillageFolkEntity posted(ServerLevel level, BlockPos pos, StationTask trade, String name, float yaw, String post, DyeColor colour) {
        VillageFolkEntity f = OutdoorStage.standIn(level, pos, trade, name, yaw);
        if (f != null) RolesStage.dress(f, post, colour);
        return f;
    }

    /** A prop wagon for the pictures: nobody's, never driven (TradeWagon.prop), in a town's colours, at this share of its strength. */
    @Nullable
    private static TradeWagon prop(ServerLevel level, Vec3 at, float yaw, WagonKind k, float strength) {
        TradeWagon w = WagonRegistry.TRADE_WAGON.get().create(level);
        if (w == null) return null;
        w.prop = true;
        w.held = true;
        w.setKind(k);
        w.hp = k.health * strength;
        w.setColours(0x2F5DA8, 0xC9A13B);
        w.moveTo(at.x, at.y, at.z, yaw, 0.0F);
        w.setPhase(TradeWagon.Phase.PARKED);
        w.addTag("folk_lineup");
        return level.addFreshEntity(w) ? w : null;
    }
}
