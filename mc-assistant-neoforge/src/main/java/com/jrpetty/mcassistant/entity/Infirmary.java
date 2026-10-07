package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [batchA] The infirmary: a modest room on a stone footing, four beds down its sides with the aisle between
 * them, a cauldron and the brewer's brewing stand at the back between two barrels, a lantern on each barrel and
 * one hung over the beds on each side (blueprints/infirmary.txt).
 *
 * <p>A Stone Age town of twenty or more wants one once its meeting hall stands: one of its amenities, built after
 * what its age asks for (Villages.projectsWantedInOrder). Its beds are made up from the stores like a house's
 * (Grow.furnish), and they are kept for the sick and the hurt: nobody takes one for its own bed (VillageFolkEntity
 * .bedOnOffer). Who lies in them, and why, is Health's.
 */
public final class Infirmary {

    private Infirmary() {}

    public static final String STRUCTURE = "infirmary";
    /** The town it takes to want one. */
    public static final int FROM_FOLK = 20;

    /** Is an infirmary wanted (Villages.projectsWantedInOrder): twenty folk, the meeting hall up, and none yet? */
    public static boolean wanted(UUID village, int folk) {
        return folk >= FROM_FOLK && Villages.hasBuilt(village, "hall") && !Villages.hasBuilt(village, STRUCTURE);
    }

    /** Why the town wants one (Villages.whyBuild). */
    public static String why(UUID village) {
        int folk = Villages.headcount(village);
        if (folk < FROM_FOLK) return "a town of " + FROM_FOLK + " builds one, and this is a town of " + folk;
        if (!Villages.hasBuilt(village, "hall")) return "the meeting hall comes first";
        return "an infirmary: beds for the sick and the hurt, a cauldron and a brewing stand, now there are " + folk
            + " folk to look after";
    }

    /** The town's infirmary (the first, if it has built more), or null. */
    @Nullable
    public static Ledger.Building of(@Nullable UUID village) {
        return village == null ? null : Villages.builtStructure(village, STRUCTURE);
    }

    static List<Ledger.Building> all(UUID village) {
        List<Ledger.Building> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(STRUCTURE)) out.add(b);
        return out;
    }

    /** Where each bed's head lies in the drawing (by the building's anchor and facing): worked out once. */
    private static final Map<String, List<BlockPos>> HEADS = new ConcurrentHashMap<>();

    static List<BlockPos> planned(Ledger.Building b) {
        return HEADS.computeIfAbsent(b.anchor().asLong() + "/" + b.facing(), k -> {
            List<BlockPos> out = new ArrayList<>();
            for (BuildGoal.Placement p : BuildGoal.plan(STRUCTURE, b.anchor(), b.facing(), 13)) {
                if (p.part() != BuildGoal.Part.BED) continue;
                Direction lie = p.way() == Blueprints.Way.UP ? b.facing() : Blueprints.world(p.way(), b.facing());
                out.add(p.pos().relative(lie).immutable());
            }
            return out;
        });
    }

    /** The beds made up in it (by their heads). */
    public static List<BlockPos> beds(ServerLevel level, Ledger.Building b) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos head : planned(b)) {
            if (!level.isLoaded(head)) continue;
            BlockPos h = Health.head(level, head);
            if (h != null && !out.contains(h)) out.add(h);
        }
        return out;
    }

    /** The nearest of the town's infirmary beds nobody is in or on the way to, for this folk; null if none. */
    @Nullable
    public static BlockPos freeBed(ServerLevel level, @Nullable UUID village, VillageFolkEntity f) {
        if (village == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Ledger.Building b : all(village)) {
            if (!level.isLoaded(b.anchor())) continue;
            for (BlockPos head : beds(level, b)) {
                BlockState st = level.getBlockState(head);
                boolean mine = head.equals(f.health().bed);
                if (!mine && st.getBlock() instanceof BedBlock && st.getValue(BedBlock.OCCUPIED)) continue;
                if (Health.takenByAnother(level, head, f)) continue;
                double d = head.distSqr(f.blockPosition());
                if (d < bestDist) {
                    bestDist = d;
                    best = head;
                }
            }
        }
        return best;
    }

    /** Is this bed one of an infirmary's (kept for the sick: nobody's own bed)? Read from where it stands, no world. */
    public static boolean isInfirmaryBed(@Nullable UUID village, BlockPos pos) {
        if (village == null) return false;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!b.structure().equals(STRUCTURE)) continue;
            BlockPos a = b.anchor();
            if (Math.abs(pos.getX() - a.getX()) <= 5 && Math.abs(pos.getZ() - a.getZ()) <= 5 && Math.abs(pos.getY() - a.getY()) <= 3) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the pictures

    /**
     * An infirmary set out on a stage at {@code at} (for the pictures; built from a palette, not the stores), its
     * four beds full: two with a cold, one hurt, one a child; and the healer at a bedside with a honey bottle.
     * Returns "INFIRMARY x y z" and the views, "VIEW name x y z ax ay az": from the street, and down the aisle
     * from just inside the door.
     */
    public static List<String> stage(ServerLevel level, BlockPos at) {
        int x = at.getX(), y = at.getY(), z = at.getZ();
        com.jrpetty.mcassistant.Showcase.stage(level, x - 9, x + 9, z - 10, z + 16, y);
        BlockPos anchor = new BlockPos(x, y, z);
        BuildGoal.stamp(level, STRUCTURE, anchor, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.Building b = new Ledger.Building(STRUCTURE, anchor, Direction.NORTH);
        HEADS.remove(anchor.asLong() + "/" + Direction.NORTH);
        List<BlockPos> beds = beds(level, b);
        String[] names = { "Wren", "Pip", "Ash", "Tansy" };
        for (int i = 0; i < beds.size() && i < names.length; i++) {
            VillageFolkEntity p = standIn(level, beds.get(i), i == 3, names[i]);
            if (p == null) continue;
            Health.State s = p.health();
            if (i != 2) {
                s.cold = 30000;
                s.caughtDay = level.getDayTime() / 24000L;
                s.how = i == 0 ? "caught out in the rain" : "worn out at its work";
            } else {
                p.setHealth(p.getMaxHealth() * 0.4F);
                s.wounded = true;
            }
            s.laidUntil = level.getGameTime() + 24000L * 3;        // kept in bed (VillageFolkEntity.onShift)
            s.laidFor = i == 2 ? "wound" : "cold";
            s.bed = beds.get(i);
            s.infirmary = true;
            s.lying = true;
            p.startSleeping(beds.get(i));
        }
        // The healer, in the aisle beside the first bed, a honey bottle in hand.
        BlockPos aisle = new BlockPos(x, y, beds.isEmpty() ? z + 2 : beds.get(0).getZ());
        VillageFolkEntity healer = standIn(level, aisle, false, "Bramble");
        if (healer != null) {
            healer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.HONEY_BOTTLE));
            if (!beds.isEmpty()) healer.getLookControl().setLookAt(beds.get(0).getX() + 0.5, y + 0.6, beds.get(0).getZ() + 0.5);
            FolkTalk.speak(healer, "Here — honey for that throat. You'll be right as rain.");
        }
        List<String> out = new ArrayList<>();
        out.add("INFIRMARY " + x + " " + y + " " + z);
        // (Its back to the north: the door and the street are to the south.)
        out.add("VIEW infirmary-front " + (x + 6) + " " + (y + 4) + " " + (z + 13) + " " + x + " " + (y + 2) + " " + z);
        out.add("VIEW infirmary-ward " + x + " " + (y + 1) + " " + (z + 3) + " " + x + " " + y + " " + (z - 3));
        return out;
    }

    @Nullable
    private static VillageFolkEntity standIn(ServerLevel level, BlockPos pos, boolean child, String name) {
        VillageFolkEntity f = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        f.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 180.0F, 0.0F);
        f.makeShowcase(child ? StationTask.NONE : StationTask.FARM);
        if (child) f.setChild(true);
        f.rename(name);
        f.addTag("folk_lineup");
        return level.addFreshEntity(f) ? f : null;
    }

    public static void resetForTests() {
        HEADS.clear();
    }
}
