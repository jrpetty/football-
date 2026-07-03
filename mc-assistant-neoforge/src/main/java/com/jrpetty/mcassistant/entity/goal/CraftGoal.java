package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Crafting: a compact survival recipe book (planks, sticks, tools, chest,
 * torches, bread...). Recipes that need a crafting table make the assistant
 * walk to one — and if there's none around but it has planks, it crafts and
 * PLACES its own table first. Combined with tool wear, this closes the
 * self-maintenance loop: gather logs -> planks -> sticks -> new tools.
 */
public class CraftGoal extends Goal {

    /** What a recipe consumes: n items matching a predicate. */
    private record Need(Predicate<ItemStack> what, int count, String label) {}

    /** A craftable: needs -> output, maybe requiring a table. */
    public record Recipe(List<Need> needs, java.util.function.Supplier<ItemStack> output, boolean needsTable) {}

    private static final Predicate<ItemStack> LOG = s -> s.is(ItemTags.LOGS);
    private static final Predicate<ItemStack> PLANK = s -> s.is(ItemTags.PLANKS);
    private static final Predicate<ItemStack> STICK = s -> s.is(Items.STICK);
    private static final Predicate<ItemStack> COBBLE = s -> s.is(Blocks.COBBLESTONE.asItem())
        || s.is(Blocks.COBBLED_DEEPSLATE.asItem());
    private static final Predicate<ItemStack> COAL = s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
    private static final Predicate<ItemStack> WHEAT = s -> s.is(Items.WHEAT);

    public static final Map<String, Recipe> RECIPES = new LinkedHashMap<>();

    static {
        RECIPES.put("planks", new Recipe(List.of(new Need(LOG, 1, "log")),
            () -> new ItemStack(Items.OAK_PLANKS, 4), false));
        RECIPES.put("sticks", new Recipe(List.of(new Need(PLANK, 2, "planks")),
            () -> new ItemStack(Items.STICK, 4), false));
        RECIPES.put("crafting table", new Recipe(List.of(new Need(PLANK, 4, "planks")),
            () -> new ItemStack(Items.CRAFTING_TABLE), false));
        RECIPES.put("chest", new Recipe(List.of(new Need(PLANK, 8, "planks")),
            () -> new ItemStack(Items.CHEST), true));
        RECIPES.put("torches", new Recipe(List.of(new Need(COAL, 1, "coal"), new Need(STICK, 1, "stick")),
            () -> new ItemStack(Items.TORCH, 4), false));
        RECIPES.put("furnace", new Recipe(List.of(new Need(COBBLE, 8, "cobblestone")),
            () -> new ItemStack(Items.FURNACE), true));
        RECIPES.put("ladders", new Recipe(List.of(new Need(STICK, 7, "sticks")),
            () -> new ItemStack(Items.LADDER, 3), true));
        RECIPES.put("bread", new Recipe(List.of(new Need(WHEAT, 3, "wheat")),
            () -> new ItemStack(Items.BREAD), true));
        RECIPES.put("wooden pickaxe", tool(PLANK, 3, "planks", Items.WOODEN_PICKAXE));
        RECIPES.put("wooden axe", tool(PLANK, 3, "planks", Items.WOODEN_AXE));
        RECIPES.put("wooden shovel", new Recipe(List.of(new Need(PLANK, 1, "plank"), new Need(STICK, 2, "sticks")),
            () -> new ItemStack(Items.WOODEN_SHOVEL), true));
        RECIPES.put("wooden hoe", new Recipe(List.of(new Need(PLANK, 2, "planks"), new Need(STICK, 2, "sticks")),
            () -> new ItemStack(Items.WOODEN_HOE), true));
        RECIPES.put("wooden sword", new Recipe(List.of(new Need(PLANK, 2, "planks"), new Need(STICK, 1, "stick")),
            () -> new ItemStack(Items.WOODEN_SWORD), true));
        RECIPES.put("stone pickaxe", tool(COBBLE, 3, "cobblestone", Items.STONE_PICKAXE));
        RECIPES.put("stone axe", tool(COBBLE, 3, "cobblestone", Items.STONE_AXE));
        RECIPES.put("stone shovel", new Recipe(List.of(new Need(COBBLE, 1, "cobblestone"), new Need(STICK, 2, "sticks")),
            () -> new ItemStack(Items.STONE_SHOVEL), true));
        RECIPES.put("stone hoe", new Recipe(List.of(new Need(COBBLE, 2, "cobblestone"), new Need(STICK, 2, "sticks")),
            () -> new ItemStack(Items.STONE_HOE), true));
        RECIPES.put("stone sword", new Recipe(List.of(new Need(COBBLE, 2, "cobblestone"), new Need(STICK, 1, "stick")),
            () -> new ItemStack(Items.STONE_SWORD), true));
    }

    private static Recipe tool(Predicate<ItemStack> head, int n, String label, net.minecraft.world.item.Item out) {
        return new Recipe(List.of(new Need(head, n, label), new Need(STICK, 2, "sticks")),
            () -> new ItemStack(out), true);
    }

    /** Match a spoken clause to a recipe key ("make a stone pickaxe" -> "stone pickaxe"). */
    @Nullable
    public static String matchRecipe(String clause) {
        String best = null;
        for (String key : RECIPES.keySet()) {
            boolean all = true;
            for (String word : key.split(" ")) {
                // plural/singular slop: "sticks"/"stick", "torches"/"torch"
                String noS = word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
                String noEs = word.endsWith("es") ? word.substring(0, word.length() - 2) : noS;
                if (!clause.contains(word) && !clause.contains(noS) && !clause.contains(noEs)) {
                    all = false;
                    break;
                }
            }
            if (all && (best == null || key.length() > best.length())) best = key;
        }
        return best;
    }

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private Recipe recipe;
    private int crafted;
    private int workTicks;
    private int stuckTicks;
    @Nullable private BlockPos tablePos;
    private int myGen;

    public CraftGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.CRAFT && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null && assistant.taskGen() == myGen;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.crafted = 0;
        this.workTicks = 0;
        this.stuckTicks = 0;
        this.tablePos = null;
        this.recipe = job != null && job.arg() != null ? RECIPES.get(job.arg()) : null;
        if (recipe == null) {
            finish("I don't know how to craft that. I know: " + String.join(", ", RECIPES.keySet()) + ".");
        }
    }

    @Override
    public void stop() {
        this.job = null;
        this.recipe = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.pollJob();
        this.job = null;
        this.recipe = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null || recipe == null) return;

        if (crafted >= job.amount()) {
            finish("Crafted " + crafted + " " + job.arg() + ".");
            return;
        }

        // Have the materials?
        for (Need need : recipe.needs()) {
            if (assistant.countMatching(need.what()) < need.count()) {
                finish("I need " + need.count() + " " + need.label() + " to craft " + job.arg()
                    + (crafted > 0 ? " (made " + crafted + " so far)" : "")
                    + " — I can gather or you can hand it to me.");
                return;
            }
        }

        // Table logistics.
        if (recipe.needsTable()) {
            if (tablePos == null || !assistant.level().getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
                tablePos = findTable();
                stuckTicks = 0;
                if (tablePos == null && !placeOwnTable()) {
                    finish("I need a crafting table for " + job.arg()
                        + " — none nearby, and I lack 4 planks to make one.");
                    return;
                }
                if (tablePos == null) return; // table just placed; re-resolve next tick
            }
            double distSq = assistant.getEyePosition().distanceToSqr(
                tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5);
            assistant.getLookControl().setLookAt(
                tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5);
            if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
                if (assistant.getNavigation().isDone()) {
                    assistant.getNavigation().moveTo(
                        tablePos.getX() + 0.5, tablePos.getY(), tablePos.getZ() + 0.5, 1.1D);
                }
                if (++stuckTicks > 140) {
                    finish("I couldn't reach the crafting table.");
                }
                return;
            }
        }

        // Put in the work, then produce.
        if (++workTicks < 20) {
            if (workTicks % 6 == 0) assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return;
        }
        workTicks = 0;
        for (Need need : recipe.needs()) {
            assistant.removeMatching(need.what(), need.count());
        }
        ItemStack out = recipe.output().get();
        ItemStack leftover = assistant.insertItem(out);
        if (!leftover.isEmpty()) assistant.spawnAtLocation(leftover);
        crafted++;
    }

    @Nullable
    private BlockPos findTable() {
        BlockPos feet = assistant.feetPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-16, -4, -16), feet.offset(16, 4, 16))) {
            if (!assistant.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** No table around: craft one from planks and put it down next to us. */
    private boolean placeOwnTable() {
        if (assistant.countMatching(PLANK) < 4) return false;
        BlockPos feet = assistant.feetPos();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos pos = feet.relative(dir);
            if (assistant.level().getBlockState(pos).canBeReplaced()
                && assistant.level().getBlockState(pos.below()).isSolid()) {
                assistant.removeMatching(PLANK, 4);
                assistant.level().setBlockAndUpdate(pos, Blocks.CRAFTING_TABLE.defaultBlockState());
                assistant.say("Setting up a crafting table here.");
                return true;
            }
        }
        return false;
    }
}
