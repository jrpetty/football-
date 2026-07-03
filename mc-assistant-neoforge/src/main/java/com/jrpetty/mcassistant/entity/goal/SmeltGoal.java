package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Smelting: load a REAL furnace with input + fuel from the backpack, tend it
 * while vanilla does the cooking (real smelt times, real fuel burn), pull the
 * output, top it up until the order is done. This is the tech-progression
 * unlock: raw iron -> ingots -> iron tools and armor.
 *
 * Everything is legit: input and fuel come from the pack, output comes from
 * the furnace's own recipe logic. If it can't smelt (wrong item), the goal
 * notices the furnace never lights, reclaims its items, and says so.
 */
public class SmeltGoal extends Goal {

    /** What we know how to smelt: canonical word -> input matcher. */
    private static final Map<String, Predicate<ItemStack>> SMELTABLES = new LinkedHashMap<>();

    static {
        SMELTABLES.put("iron", s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE));
        SMELTABLES.put("gold", s -> s.is(Items.RAW_GOLD) || s.is(Items.GOLD_ORE)
            || s.is(Items.DEEPSLATE_GOLD_ORE) || s.is(Items.NETHER_GOLD_ORE));
        SMELTABLES.put("copper", s -> s.is(Items.RAW_COPPER) || s.is(Items.COPPER_ORE) || s.is(Items.DEEPSLATE_COPPER_ORE));
        SMELTABLES.put("logs", s -> s.is(ItemTags.LOGS)); // -> charcoal
        SMELTABLES.put("stone", s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE));
        SMELTABLES.put("sand", s -> s.is(Items.SAND) || s.is(Items.RED_SAND)); // -> glass
        SMELTABLES.put("beef", s -> s.is(Items.BEEF));
        SMELTABLES.put("porkchop", s -> s.is(Items.PORKCHOP));
        SMELTABLES.put("chicken", s -> s.is(Items.CHICKEN));
        SMELTABLES.put("mutton", s -> s.is(Items.MUTTON));
        SMELTABLES.put("rabbit", s -> s.is(Items.RABBIT));
        SMELTABLES.put("fish", s -> s.is(Items.COD) || s.is(Items.SALMON));
        SMELTABLES.put("potato", s -> s.is(Items.POTATO));
    }

    public static String smeltableList() {
        return "iron, gold, copper, logs (charcoal), stone, sand (glass), and raw food (beef, porkchop, chicken, mutton, rabbit, fish, potato)";
    }

    /** Map a spoken clause to a canonical smeltable ("cook the steak" -> "beef"). */
    @Nullable
    public static String canonical(String clause) {
        if (clause.contains("iron")) return "iron";
        if (clause.contains("gold")) return "gold";
        if (clause.contains("copper")) return "copper";
        if (clause.contains("charcoal") || clause.contains("log") || clause.contains("wood")) return "logs";
        if (clause.contains("stone") || clause.contains("cobble")) return "stone";
        if (clause.contains("sand") || clause.contains("glass")) return "sand";
        if (clause.contains("beef") || clause.contains("steak")) return "beef";
        if (clause.contains("pork")) return "porkchop";
        if (clause.contains("chicken")) return "chicken";
        if (clause.contains("mutton")) return "mutton";
        if (clause.contains("rabbit")) return "rabbit";
        if (clause.contains("fish") || clause.contains("cod") || clause.contains("salmon")) return "fish";
        if (clause.contains("potato")) return "potato";
        return null;
    }

    // Fuels we'll burn, in preference order — never tools, never the good stuff.
    private static final java.util.List<Predicate<ItemStack>> FUEL_PRIORITY = java.util.List.of(
        s -> s.is(Items.COAL) || s.is(Items.CHARCOAL),
        s -> s.is(ItemTags.PLANKS),
        s -> s.is(Items.STICK),
        s -> s.is(ItemTags.LOGS));

    // Foods cook in a furnace or smoker; ores in a furnace or blast furnace;
    // stone/sand/logs only in a plain furnace.
    private static final Set<String> FOODS = Set.of(
        "beef", "porkchop", "chicken", "mutton", "rabbit", "fish", "potato");

    private static boolean isOreWord(String c) {
        return c.equals("iron") || c.equals("gold") || c.equals("copper");
    }

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private Predicate<ItemStack> input;
    @Nullable private BlockPos furnacePos;
    private int remainingToLoad;
    private int collected;
    private int stuckTicks;
    private int lastProgressTick;
    private int myGen;

    public SmeltGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.SMELT && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null && assistant.taskGen() == myGen;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.collected = 0;
        this.stuckTicks = 0;
        this.lastProgressTick = assistant.tickCount;
        this.furnacePos = null;
        this.input = job != null && job.arg() != null ? SMELTABLES.get(job.arg()) : null;
        if (input == null) {
            finish("I can smelt: " + smeltableList() + ".");
            return;
        }
        int have = assistant.countMatching(input);
        if (have == 0) {
            finish("I don't have any " + job.arg() + " to smelt — \"gather iron\" or \"grab " + job.arg() + " from the chest\" first.");
            return;
        }
        this.furnacePos = findFurnace(job.arg());
        if (furnacePos == null) {
            finish("No usable furnace within 16 blocks — \"craft a furnace\" and place it, or \"build a furnace building\".");
            return;
        }
        // We can smelt if we carry fuel OR the furnace already has fuel / is
        // burning — so a furnace you've already lit works even with an empty pack.
        if (countFuel() == 0 && !furnaceHasFuel(furnacePos)) {
            finish("I have no fuel and the furnace isn't lit — give me coal/planks/sticks/logs, or fuel the furnace yourself.");
            return;
        }
        this.remainingToLoad = Math.min(job.amount(), have);
        assistant.say("Smelting " + remainingToLoad + " " + job.arg() + ".");
    }

    @Override
    public void stop() {
        // Interrupted (combat/retreat/stop): the furnace keeps burning what's
        // loaded; the job stays at the head of the queue and resumes.
        this.job = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(collected > 0);
        assistant.pollJob();
        this.job = null;
        this.furnacePos = null;
        assistant.getNavigation().stop();
    }

    private int countFuel() {
        int n = 0;
        for (Predicate<ItemStack> f : FUEL_PRIORITY) n += assistant.countMatching(f);
        return n;
    }

    @Override
    public void tick() {
        if (job == null || input == null || furnacePos == null) return;

        if (!isFurnaceBlock(assistant.level().getBlockState(furnacePos))) {
            finish("The furnace is gone.");
            return;
        }

        double distSq = assistant.getEyePosition().distanceToSqr(
            furnacePos.getX() + 0.5, furnacePos.getY() + 0.5, furnacePos.getZ() + 0.5);
        assistant.getLookControl().setLookAt(
            furnacePos.getX() + 0.5, furnacePos.getY() + 0.5, furnacePos.getZ() + 0.5);
        if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(
                    furnacePos.getX() + 0.5, furnacePos.getY(), furnacePos.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 140) {
                finish("I couldn't reach the furnace.");
            }
            return;
        }
        stuckTicks = 0;

        if (assistant.tickCount % 10 != 0) return; // tend twice a second
        if (!(assistant.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
            finish("The furnace is gone.");
            return;
        }

        // 1) Pull finished output.
        ItemStack out = furnace.getItem(2);
        if (!out.isEmpty()) {
            int before = out.getCount();
            ItemStack leftover = assistant.insertItem(out.copy());
            int taken = before - leftover.getCount();
            if (taken > 0) {
                out.shrink(taken);
                if (out.isEmpty()) furnace.setItem(2, ItemStack.EMPTY);
                furnace.setChanged();
                collected += taken;
                lastProgressTick = assistant.tickCount;
                assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
        }

        // 2) Top up the input slot from the pack.
        if (remainingToLoad > 0) {
            ItemStack slot0 = furnace.getItem(0);
            var inv = assistant.getInventoryItems();
            for (int i = 0; i < inv.size() && remainingToLoad > 0; i++) {
                ItemStack s = inv.get(i);
                if (s.isEmpty() || !input.test(s)) continue;
                if (!slot0.isEmpty() && !ItemStack.isSameItemSameComponents(slot0, s)) continue;
                int room = slot0.isEmpty() ? s.getMaxStackSize() : slot0.getMaxStackSize() - slot0.getCount();
                int mv = Math.min(Math.min(room, s.getCount()), remainingToLoad);
                if (mv <= 0) continue;
                if (slot0.isEmpty()) {
                    furnace.setItem(0, s.copyWithCount(mv));
                    slot0 = furnace.getItem(0);
                } else {
                    slot0.grow(mv);
                }
                s.shrink(mv);
                if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                remainingToLoad -= mv;
                furnace.setChanged();
                lastProgressTick = assistant.tickCount;
            }
        }

        // 3) Keep it fueled — but only when the furnace has actually gone dark
        //    (not currently burning) and there's still input to smelt. A lit
        //    furnace is already cooking on the fuel it consumed, so leave it be
        //    and don't waste ours (and never bail while it's still burning).
        if (!isLit(furnacePos) && furnace.getItem(1).isEmpty() && !furnace.getItem(0).isEmpty()) {
            for (Predicate<ItemStack> fuelType : FUEL_PRIORITY) {
                var inv = assistant.getInventoryItems();
                boolean loaded = false;
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack s = inv.get(i);
                    // FUEL_PRIORITY only lists guaranteed burnables (coal,
                    // charcoal, planks, sticks, logs), so no extra fuel check.
                    if (s.isEmpty() || !fuelType.test(s)) continue;
                    int mv = Math.min(4, s.getCount());
                    furnace.setItem(1, s.copyWithCount(mv));
                    s.shrink(mv);
                    if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                    furnace.setChanged();
                    lastProgressTick = assistant.tickCount;
                    loaded = true;
                    break;
                }
                if (loaded) break;
            }
            if (furnace.getItem(1).isEmpty()) {
                reclaim(furnace);
                finish("Out of fuel and the furnace went cold — smelted " + collected + " so far.");
                return;
            }
        }

        // 4) Done? Nothing left to load, cook, or collect.
        if (remainingToLoad <= 0 && furnace.getItem(0).isEmpty() && furnace.getItem(2).isEmpty()) {
            finish("Smelting done — collected " + collected + " " + (job != null ? resultName(job.arg()) : "items") + ".");
            return;
        }

        // 5) Watchdog: if nothing has moved in ~60s, this probably can't smelt.
        if (assistant.tickCount - lastProgressTick > 1200) {
            reclaim(furnace);
            finish("That furnace isn't making progress — took my materials back (got " + collected + ").");
        }
    }

    /** Pull our input (and any output) back out so nothing is lost. */
    private void reclaim(AbstractFurnaceBlockEntity furnace) {
        for (int slot : new int[] {0, 2}) {
            ItemStack s = furnace.getItem(slot);
            if (!s.isEmpty()) {
                ItemStack leftover = assistant.insertItem(s.copy());
                furnace.setItem(slot, leftover);
            }
        }
        furnace.setChanged();
    }

    private static String resultName(@Nullable String word) {
        if (word == null) return "items";
        return switch (word) {
            case "iron", "gold", "copper" -> word + " ingots";
            case "logs" -> "charcoal";
            case "stone" -> "stone";
            case "sand" -> "glass";
            default -> "cooked " + word;
        };
    }

    /** Nearest furnace-type block that can smelt the given item. */
    @Nullable
    private BlockPos findFurnace(String canonical) {
        BlockPos feet = assistant.feetPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-16, -4, -16), feet.offset(16, 4, 16))) {
            if (!furnaceAccepts(assistant.level().getBlockState(pos), canonical)) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** A plain furnace smelts anything; a blast furnace only ores/metals; a
     *  smoker only food. */
    private static boolean furnaceAccepts(BlockState state, String canonical) {
        if (state.is(Blocks.FURNACE)) return true;
        if (state.is(Blocks.BLAST_FURNACE)) return isOreWord(canonical);
        if (state.is(Blocks.SMOKER)) return FOODS.contains(canonical);
        return false;
    }

    private static boolean isFurnaceBlock(BlockState state) {
        return state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER);
    }

    /** Is the furnace currently burning (fuel lit)? */
    private boolean isLit(BlockPos pos) {
        BlockState state = assistant.level().getBlockState(pos);
        return state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
    }

    /** True if the furnace can burn right now — already lit, or with fuel in
     *  its fuel slot — so we don't need to supply our own. */
    private boolean furnaceHasFuel(BlockPos pos) {
        if (isLit(pos)) return true;
        return assistant.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity f
            && !f.getItem(1).isEmpty();
    }
}
