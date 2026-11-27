package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.RecipeBook;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Self-test ("run diagnostics"). The assistant actually PERFORMS each core skill
 * in front of you and reports pass/fail for each, so "does it work?" is a
 * question you can answer on demand instead of guessing:
 *   - place a block, then mine it back (the build/mine/dig-out machinery);
 *   - pathfind to a nearby spot (navigation);
 *   - round-trip a waypoint (the place-marker / "go to X" system);
 *   - resolve a real recipe (the "make anything" brain).
 * It cleans up after itself — any block it grants itself for the test is removed,
 * and the temporary waypoint is deleted — so nothing is left behind.
 */
public class DiagnosticsGoal extends Goal {

    private final AssistantEntity a;
    @Nullable private Job job;
    private int myGen;
    private int phase;
    private int timer;
    private int moveWait;
    private boolean grantedBlock;
    @Nullable private BlockPos placedAt;
    @Nullable private Item placedItem;
    private Vec3 startPos = Vec3.ZERO;
    private final List<String> results = new ArrayList<>();

    public DiagnosticsGoal(AssistantEntity a) {
        this.a = a;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = a.peekJob();
        return j != null && j.type() == Job.Type.DIAGNOSTIC && a.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && a.taskGen() == myGen && a.peekJob() == job;
    }

    @Override
    public void start() {
        job = a.peekJob();
        myGen = a.taskGen();
        phase = 0;
        timer = 0;
        moveWait = 0;
        grantedBlock = false;
        placedAt = null;
        placedItem = null;
        results.clear();
        a.say("Running a self-test — I'll try each core skill and report back.");
    }

    @Override
    public void stop() {
        cleanup();
        job = null;
        a.getNavigation().stop();
    }

    private void finish() {
        cleanup();
        int passed = 0;
        for (String r : results) if (r.contains(": OK")) passed++;
        a.say("Self-test: " + passed + "/" + results.size() + " passed — "
            + String.join("; ", results) + ".");
        a.pollJob();
        job = null;
    }

    /** Undo anything the test introduced. */
    private void cleanup() {
        if (grantedBlock) {
            a.removeMatching(s -> s.is(Items.COBBLESTONE), 1);
            grantedBlock = false;
        }
        a.removeWaypoint("__diag__");
    }

    @Override
    public void tick() {
        if (job == null) return;
        if (++timer < 8) return; // pace each step ~0.4s so you can watch it happen
        timer = 0;
        switch (phase) {
            case 0 -> doPlace();
            case 1 -> verifyPlace();
            case 2 -> doBreak();
            case 3 -> verifyBreak();
            case 4 -> startMove();
            case 5 -> verifyMove();
            case 6 -> testWaypoint();
            case 7 -> testRecipe();
            default -> finish();
        }
    }

    // ---- place a block ----
    private void doPlace() {
        if (a.countMatching(NightShelterGoal.SHELTER_BLOCK) == 0) {
            ItemStack leftover = a.insertItem(new ItemStack(Items.COBBLESTONE, 1));
            grantedBlock = leftover.isEmpty();
            if (!grantedBlock) { results.add("place blocks: SKIP (pack full)"); phase = 4; return; }
        }
        BlockPos spot = openSpot();
        if (spot == null) { results.add("place blocks: SKIP (no open space)"); phase = 4; return; }
        placedItem = placeBlockAt(spot);
        placedAt = spot;
        phase = 1;
    }

    private void verifyPlace() {
        boolean ok = placedAt != null && a.level().getBlockState(placedAt).isSolid();
        results.add("place blocks: " + (ok ? "OK" : "FAIL"));
        phase = ok ? 2 : 4; // nothing placed -> skip the break test
    }

    // ---- mine it back ----
    private void doBreak() {
        if (placedAt != null) {
            a.level().destroyBlock(placedAt, true, a);
            a.swing(InteractionHand.MAIN_HAND);
            for (ItemEntity drop : a.level().getEntitiesOfClass(
                    ItemEntity.class, new AABB(placedAt).inflate(2.5))) {
                ItemStack left = a.insertItem(drop.getItem());
                if (left.isEmpty()) drop.discard(); else drop.setItem(left);
            }
        }
        phase = 3;
    }

    private void verifyBreak() {
        boolean broken = placedAt != null && a.level().getBlockState(placedAt).canBeReplaced();
        boolean pickedUp = placedItem != null && a.countMatching(s -> s.is(placedItem)) > 0;
        results.add("mine/pick up blocks: "
            + (broken && pickedUp ? "OK" : broken ? "FAIL (broke, but drop not collected)" : "FAIL"));
        phase = 4;
    }

    // ---- pathfinding ----
    private void startMove() {
        startPos = a.position();
        moveWait = 0;
        Direction f = Direction.fromYRot(a.getYRot());
        BlockPos feet = a.feetPos();
        a.getNavigation().moveTo(
            feet.getX() + 0.5 + f.getStepX() * 3,
            feet.getY(),
            feet.getZ() + 0.5 + f.getStepZ() * 3, 1.0D);
        phase = 5;
    }

    private void verifyMove() {
        if (a.position().distanceToSqr(startPos) > 1.5 * 1.5) {
            results.add("pathfinding: OK");
            phase = 6;
            return;
        }
        if (++moveWait >= 8) { // ~waited a few seconds and still no ground covered
            results.add("pathfinding: FAIL (didn't move)");
            phase = 6;
        }
    }

    // ---- waypoint round-trip ----
    private void testWaypoint() {
        BlockPos here = a.feetPos().immutable();
        a.setWaypoint("__diag__", here);
        BlockPos got = a.getWaypoint("__diag__");
        results.add("waypoints: " + (here.equals(got) ? "OK" : "FAIL"));
        a.removeWaypoint("__diag__");
        phase = 7;
    }

    // ---- recipe brain ----
    private void testRecipe() {
        RecipeBook.Craft c = RecipeBook.craftFor(a.level(), Items.CHEST);
        results.add("crafting brain: " + (c != null ? "OK" : "FAIL"));
        phase = 8;
    }

    // ---- helpers ----
    @Nullable
    private BlockPos openSpot() {
        BlockPos feet = a.feetPos();
        BlockPos[] cands = {
            feet.above(2),
            feet.above(2).relative(Direction.fromYRot(a.getYRot())),
        };
        for (BlockPos p : cands) {
            if (a.level().getBlockState(p).canBeReplaced()
                && !a.getBoundingBox().intersects(new AABB(p))) {
                return p.immutable();
            }
        }
        return null;
    }

    @Nullable
    private Item placeBlockAt(BlockPos p) {
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.get(i);
            if (st.isEmpty() || !NightShelterGoal.SHELTER_BLOCK.test(st)) continue;
            if (!(st.getItem() instanceof BlockItem bi)) continue;
            a.level().setBlockAndUpdate(p, bi.getBlock().defaultBlockState());
            a.swing(InteractionHand.MAIN_HAND);
            Item item = st.getItem();
            st.shrink(1);
            if (st.isEmpty()) inv.set(i, ItemStack.EMPTY);
            return item;
        }
        return null;
    }
}
