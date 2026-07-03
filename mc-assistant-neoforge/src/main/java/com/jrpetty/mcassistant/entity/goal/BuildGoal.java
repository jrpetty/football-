package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Building from blueprints, placed block-by-block (bottom-up) from what the
 * assistant actually carries — nothing is conjured. Structural blocks come
 * from any building material in its pack (planks, logs, cobble, stone,
 * dirt); functional parts (furnaces, chests, crafting tables, ladders,
 * torches) are real items it must have crafted or been given first.
 *
 * Blueprints (each on a 5x5 or 3x3 footprint, door facing the assistant):
 *   wall, platform, shelter               — structure only
 *   smeltery  — 3 furnaces along the back, 2 chests, crafting table, torch
 *   storage   — 4 chests lining the walls, torch
 *   workshop  — crafting table + furnace + chest, torch
 *   watchtower — 3x3 tower with a ladder shaft up to a walled platform
 */
public class BuildGoal extends Goal {

    public static final Set<String> STRUCTURES = Set.of(
        "wall", "platform", "shelter", "smeltery", "storage", "workshop", "watchtower");

    /** What goes in a blueprint cell. */
    public enum Part { BLOCK, FURNACE, CHEST, CRAFTING_TABLE, TORCH, LADDER }

    private record Placement(BlockPos pos, Part part) {}

    private final AssistantEntity assistant;
    @Nullable private Job job;
    private final List<Placement> plan = new ArrayList<>();
    private Direction facing = Direction.NORTH;
    private int cursor;
    private int placed;
    private int workTicks;
    private int stuckTicks;
    private int myGen;

    public BuildGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public static boolean isBuildingBlock(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) return false;
        return s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS)
            || s.is(Blocks.COBBLESTONE.asItem()) || s.is(Blocks.COBBLED_DEEPSLATE.asItem())
            || s.is(Blocks.STONE.asItem()) || s.is(Blocks.DIRT.asItem());
    }

    private static Predicate<ItemStack> itemFor(Part part) {
        return switch (part) {
            case BLOCK -> BuildGoal::isBuildingBlock;
            case FURNACE -> s -> s.is(Items.FURNACE);
            case CHEST -> s -> s.is(Items.CHEST);
            case CRAFTING_TABLE -> s -> s.is(Items.CRAFTING_TABLE);
            case TORCH -> s -> s.is(Items.TORCH);
            case LADDER -> s -> s.is(Items.LADDER);
        };
    }

    private static String craftHint(Part part) {
        return switch (part) {
            case FURNACE -> "furnace (\"craft a furnace\" — 8 cobblestone)";
            case CHEST -> "chest (\"craft a chest\" — 8 planks)";
            case CRAFTING_TABLE -> "crafting table (\"craft a crafting table\")";
            case LADDER -> "ladders (\"craft ladders\" — 7 sticks makes 3)";
            case TORCH -> "torches";
            case BLOCK -> "building blocks";
        };
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.BUILD && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null && assistant.taskGen() == myGen;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.cursor = 0;
        this.placed = 0;
        this.workTicks = 0;
        this.stuckTicks = 0;
        this.plan.clear();

        String structure = job != null ? job.arg() : null;
        if (structure == null || !STRUCTURES.contains(structure)) {
            finish("I can build: " + String.join(", ", STRUCTURES) + ".");
            return;
        }
        this.facing = Direction.fromYRot(assistant.getYRot());
        layout(structure, assistant.feetPos(), facing, plan);
        // Bottom-up so nothing floats while we work.
        this.plan.sort(java.util.Comparator
            .comparingInt((Placement p) -> p.pos().getY())
            .thenComparingDouble(p -> p.pos().distSqr(assistant.feetPos())));

        // Tally what's still needed vs what we carry — no cheating: every
        // furnace/chest/table/ladder is a real item from the pack.
        Map<Part, Integer> pending = new EnumMap<>(Part.class);
        for (Placement p : plan) {
            if (assistant.level().getBlockState(p.pos()).canBeReplaced()) {
                pending.merge(p.part(), 1, Integer::sum);
            }
        }
        int totalPending = pending.values().stream().mapToInt(Integer::intValue).sum();
        if (totalPending == 0) {
            finish("Looks already built.");
            return;
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<Part, Integer> e : pending.entrySet()) {
            Part part = e.getKey();
            if (part == Part.TORCH) continue; // decorative: skipped if absent
            int have = assistant.countMatching(itemFor(part));
            if (part == Part.BLOCK) {
                if (have == 0) missing.add(e.getValue() + " building blocks (planks/cobble/dirt)");
            } else if (have < e.getValue()) {
                missing.add((e.getValue() - have) + " " + craftHint(part));
            }
        }
        if (!missing.isEmpty()) {
            finish("Before I can build the " + structure + " I still need: " + String.join(", ", missing) + ".");
            return;
        }
        assistant.say("Building a " + structure + " — " + totalPending + " parts to place.");
    }

    @Override
    public void stop() {
        this.job = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(placed > 0);
        assistant.pollJob();
        this.job = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null) return;

        // Advance to the next cell that still needs its part.
        Placement target = null;
        while (cursor < plan.size()) {
            Placement p = plan.get(cursor);
            BlockState st = assistant.level().getBlockState(p.pos());
            if (st.canBeReplaced()
                && !assistant.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p.pos()))) {
                target = p;
                break;
            }
            cursor++;
            stuckTicks = 0;
        }
        if (target == null) {
            finish("Done — placed " + placed + " parts.");
            return;
        }

        BlockPos pos = target.pos();
        double distSq = assistant.getEyePosition().distanceToSqr(
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        assistant.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 120) {
                cursor++; // can't get there — skip that cell
                stuckTicks = 0;
            }
            return;
        }

        if (++workTicks < 6) {
            return;
        }
        workTicks = 0;

        BlockState state;
        if (target.part() == Part.BLOCK) {
            state = takeStructureBlock();
            if (state == null) {
                finish("Ran out of building blocks — placed " + placed + " parts so far.");
                return;
            }
        } else {
            if (assistant.removeMatching(itemFor(target.part()), 1) < 1) {
                if (target.part() == Part.TORCH) {
                    cursor++; // no torches: skip the decoration, keep building
                    return;
                }
                finish("Ran out of " + craftHint(target.part()) + " — placed " + placed + " parts so far.");
                return;
            }
            state = stateFor(target.part());
        }
        assistant.level().setBlockAndUpdate(pos, state);
        assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        placed++;
        cursor++;
    }

    private BlockState stateFor(Part part) {
        Direction toDoor = facing.getOpposite(); // face the entrance = accessible
        return switch (part) {
            case BLOCK -> Blocks.COBBLESTONE.defaultBlockState(); // unreachable (handled inline)
            case FURNACE -> Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, toDoor);
            case CHEST -> Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, toDoor);
            case CRAFTING_TABLE -> Blocks.CRAFTING_TABLE.defaultBlockState();
            case TORCH -> Blocks.TORCH.defaultBlockState();
            case LADDER -> Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, toDoor);
        };
    }

    /** Consume one structural item from the pack; its block is what we place. */
    @Nullable
    private BlockState takeStructureBlock() {
        var inv = assistant.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (isBuildingBlock(s)) {
                BlockState state = ((BlockItem) s.getItem()).getBlock().defaultBlockState();
                s.shrink(1);
                if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                return state;
            }
        }
        return null;
    }

    // ------------------------------ blueprints ------------------------------

    private static void layout(String structure, BlockPos feet, Direction facing, List<Placement> out) {
        Direction right = facing.getClockWise();
        switch (structure) {
            case "platform" -> {
                BlockPos center = feet.relative(facing, 3);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        out.add(new Placement(cell(center, right, facing, dx, dz).below(), Part.BLOCK));
                    }
                }
            }
            case "wall" -> {
                BlockPos base = feet.relative(facing, 2);
                for (int w = -2; w <= 2; w++) {
                    for (int h = 0; h <= 2; h++) {
                        out.add(new Placement(base.relative(right, w).above(h), Part.BLOCK));
                    }
                }
            }
            case "shelter" -> {
                BlockPos center = feet.relative(facing, 4);
                shell(out, center, right, facing);
            }
            case "smeltery" -> {
                BlockPos center = feet.relative(facing, 4);
                shell(out, center, right, facing);
                // Furnaces along the back interior wall, mouths toward the door.
                for (int dx = -1; dx <= 1; dx++) {
                    out.add(new Placement(cell(center, right, facing, dx, 1), Part.FURNACE));
                }
                // A chest on each side wall, a crafting table by the door.
                out.add(new Placement(cell(center, right, facing, -1, 0), Part.CHEST));
                out.add(new Placement(cell(center, right, facing, 1, 0), Part.CHEST));
                out.add(new Placement(cell(center, right, facing, 1, -1), Part.CRAFTING_TABLE));
                out.add(new Placement(cell(center, right, facing, -1, -1), Part.TORCH));
            }
            case "storage" -> {
                BlockPos center = feet.relative(facing, 4);
                shell(out, center, right, facing);
                out.add(new Placement(cell(center, right, facing, -1, 1), Part.CHEST));
                out.add(new Placement(cell(center, right, facing, 1, 1), Part.CHEST));
                out.add(new Placement(cell(center, right, facing, -1, 0), Part.CHEST));
                out.add(new Placement(cell(center, right, facing, 1, 0), Part.CHEST));
                out.add(new Placement(cell(center, right, facing, 0, 1), Part.TORCH));
            }
            case "workshop" -> {
                BlockPos center = feet.relative(facing, 4);
                shell(out, center, right, facing);
                out.add(new Placement(cell(center, right, facing, -1, 1), Part.CRAFTING_TABLE));
                out.add(new Placement(cell(center, right, facing, 0, 1), Part.FURNACE));
                out.add(new Placement(cell(center, right, facing, 1, 1), Part.CHEST));
                out.add(new Placement(cell(center, right, facing, -1, -1), Part.TORCH));
            }
            case "watchtower" -> {
                BlockPos base = feet.relative(facing, 3);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        boolean edge = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                        BlockPos col = cell(base, right, facing, dx, dz);
                        if (edge) {
                            for (int h = 0; h <= 4; h++) {
                                if (dx == 0 && dz == -1 && h <= 1) continue; // doorway
                                out.add(new Placement(col.above(h), Part.BLOCK));
                            }
                            out.add(new Placement(col.above(6), Part.BLOCK)); // rim
                        }
                        if (!(dx == 0 && dz == 0)) {
                            out.add(new Placement(col.above(5), Part.BLOCK)); // deck (shaft stays open)
                        }
                    }
                }
                // Ladder shaft up the middle, hanging on the back wall.
                for (int h = 0; h <= 5; h++) {
                    out.add(new Placement(base.above(h), Part.LADDER));
                }
                out.add(new Placement(cell(base, right, facing, -1, -1).above(7), Part.TORCH));
                out.add(new Placement(cell(base, right, facing, 1, -1).above(7), Part.TORCH));
            }
            default -> { }
        }
    }

    /** 5x5 hollow room: walls 3 high with a doorway facing the assistant, flat roof. */
    private static void shell(List<Placement> out, BlockPos center, Direction right, Direction facing) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                BlockPos col = cell(center, right, facing, dx, dz);
                if (edge) {
                    boolean isDoor = dx == 0 && dz == -2;
                    for (int h = 0; h <= 2; h++) {
                        if (isDoor && h <= 1) continue;
                        out.add(new Placement(col.above(h), Part.BLOCK));
                    }
                }
                out.add(new Placement(col.above(3), Part.BLOCK)); // roof
            }
        }
    }

    private static BlockPos cell(BlockPos center, Direction right, Direction facing, int dx, int dz) {
        return center.relative(right, dx).relative(facing, dz);
    }
}
