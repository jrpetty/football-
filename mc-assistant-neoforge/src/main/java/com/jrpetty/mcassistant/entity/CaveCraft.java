package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * [caves] The cave dwellers' crafting, on the fly and out of their own packs, as a player does it underground: every
 * recipe the game's own (RecipeBook.waysFor), every making out of the pack, nothing out of nothing.
 *
 * <ul>
 * <li><b>Torches.</b> Down to its last few, a cave dweller makes more in the hand (two by two: no table), a coal or a
 *     charcoal and a stick to four, the sticks of its planks (a plank to two) and the planks of its logs (a log to
 *     four). So the coal it mines turns into light on the spot, and the team goes home for torches only when there is
 *     neither coal nor wood left among it.</li>
 * <li><b>Tools.</b> A pick or a blade about to break (or broken) is made again, the best its pack runs to: of the iron
 *     ingots the town gave it, else of the cobblestone it has cut, else of wood. Three by three, so it sets its crafting
 *     table down beside it, makes it, and picks the table up again. A diamond pick only when the cave's list has a vein
 *     of something the town wants that none of the team's picks will take, and it carries the diamonds: the town wants
 *     its diamonds in the storehouse otherwise. Before a long dig, a spare pick if its own is half worn.</li>
 * <li><b>The makings.</b> Its kit carries a crafting table, a few planks, a few sticks and, when the town can spare
 *     them, three iron ingots (CaveDwellers.kitUp); and it keeps a little coal it mines (makings).</li>
 * </ul>
 * What it makes goes into the team's day ("crafted 8 torches from 2 coal; made a stone pickaxe"), the chronicle's
 * story of the trip and its brain line.
 */
public final class CaveCraft {

    private CaveCraft() {}

    /** The makings a cave dweller keeps with its kit (what it does not put into the stores at home). */
    static final int COAL = 16, PLANKS = 8, LOGS = 4, STICKS = 8, INGOTS = 3;
    /** It makes torches when it is down to LOW, up to MAKE. */
    static final int LOW = 8, MAKE = 24;
    /** Uses left on a pick or a blade when it makes another (a pick this worn is one it no longer reaches for:
     *  CaveDwellers.pickScore). */
    static final int WORN = 15;
    /** How long its crafting table stands before the thing is made and the table picked up again. */
    static final int AT_TABLE = 14;

    static boolean coal(ItemStack s) {
        return s.is(Items.COAL) || s.is(Items.CHARCOAL);
    }

    /** How many of this its pack keeps as the makings of its kit, whatever goes into the stores (0: none). */
    static int makings(ItemStack s) {
        if (coal(s)) return COAL;
        if (s.is(ItemTags.PLANKS)) return PLANKS;
        if (s.is(ItemTags.LOGS)) return LOGS;
        if (s.is(Items.STICK)) return STICKS;
        if (s.is(Items.CRAFTING_TABLE)) return 1;
        if (s.is(Items.IRON_INGOT) && WatchKit.issued(s)) return INGOTS;
        if (s.is(Items.WATER_BUCKET)) return 1;
        return 0;
    }

    // ------------------------------------------------------------------ the game's recipes, out of the pack

    /** What a recipe's part may take out of its pack: anything the part accepts but its keepsakes. */
    private static Predicate<ItemStack> usable(Ingredient ing) {
        return s -> ing.test(s) && !Homes.isKeepsake(s);
    }

    private static int count(VillageFolkEntity f, Ingredient ing) {
        return f.countMatching(usable(ing));
    }

    private static boolean have(VillageFolkEntity f, RecipeBook.Way w) {
        for (RecipeBook.Part part : w.parts()) if (count(f, part.ingredient()) < part.count()) return false;
        return true;
    }

    /**
     * One making of this, by the game's own recipe, out of the pack: its parts made first if it has their makings
     * (sticks of planks, planks of logs), depth deep. Three by three only with a table to hand. True if it was made.
     */
    static boolean once(ServerLevel level, VillageFolkEntity f, Item target, boolean table, int depth, Map<String, Integer> made) {
        for (RecipeBook.Way w : RecipeBook.waysFor(level, target)) {
            if (w.fire() != RecipeBook.Fire.NONE || w.needsTable() && !table) continue;
            if (!have(f, w) && depth > 0) {
                for (RecipeBook.Part part : w.parts()) {
                    int guard = 0;
                    while (count(f, part.ingredient()) < part.count() && guard++ < 6) {
                        if (!makeOne(level, f, part.ingredient(), table, depth - 1, made)) break;
                    }
                }
            }
            if (!have(f, w)) continue;
            for (RecipeBook.Part part : w.parts()) f.removeMatching(usable(part.ingredient()), part.count());
            ItemStack out = new ItemStack(w.result(), w.yield());
            ItemStack left = f.insertItem(out);
            if (!left.isEmpty()) f.spawnAtLocation(left);
            made.merge(words(w.result()), w.yield(), Integer::sum);
            return true;
        }
        return false;
    }

    /** One of whatever this part takes, made out of the pack: the first of its choices that can be. */
    private static boolean makeOne(ServerLevel level, VillageFolkEntity f, Ingredient ing, boolean table, int depth, Map<String, Integer> made) {
        ItemStack[] choices = ing.getItems();
        for (int i = 0; i < choices.length && i < 24; i++) {
            if (once(level, f, choices[i].getItem(), table, depth, made)) return true;
        }
        return false;
    }

    static String words(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).getPath().replace('_', ' ');
    }

    // ------------------------------------------------------------------ torches

    /** Sticks it has, or can have of its wood: a plank is two, a log eight. */
    static int sticks(VillageFolkEntity f) {
        return f.countMatching(s -> s.is(Items.STICK)) + 2 * f.countMatching(s -> s.is(ItemTags.PLANKS)) + 8 * f.countMatching(s -> s.is(ItemTags.LOGS));
    }

    /** How many torches its pack could make: four to a coal, a stick each. */
    static int torchesToMake(VillageFolkEntity f) {
        return 4 * Math.min(f.countMatching(CaveCraft::coal), sticks(f));
    }

    /** Has it the makings of a torch (a coal and a stick, or the wood for one)? */
    static boolean canMakeTorches(VillageFolkEntity f) {
        return torchesToMake(f) > 0;
    }

    /**
     * Down to its last few torches: more made in the hand, of its coal and sticks, up to MAKE. Returns how many,
     * and writes it into the team's day.
     */
    static int torches(ServerLevel level, VillageFolkEntity f, CaveDwellers.Party p) {
        Predicate<ItemStack> torch = s -> s.is(Items.TORCH);
        int have = f.countMatching(torch);
        if (have > LOW || !canMakeTorches(f)) return 0;
        int coal0 = f.countMatching(CaveCraft::coal);
        Map<String, Integer> made = new LinkedHashMap<>();
        int guard = 0;
        while (f.countMatching(torch) < MAKE && guard++ < 16 && once(level, f, Items.TORCH, false, 2, made)) {
            // a making at a time: four torches a coal
        }
        int n = f.countMatching(torch) - have, coal = coal0 - f.countMatching(CaveCraft::coal);
        if (n <= 0) return 0;
        String line = "crafted " + n + " torches from " + coal + (coal == 1 ? " coal" : " coal");
        p.crafted.add(f.displayNameCap() + " " + line);
        p.torchesMade += n;
        f.brain(line + ", down in the caves");
        f.swing(InteractionHand.MAIN_HAND);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Low on torches — I'll make a few from the coal.", "Coal and a stick: there's light for a while yet.",
            "Hold on, making torches."));
        CaveDwellers.LOG.info("[MCA-CAVES] {} {}", f.displayNameCap(), line);
        return n;
    }

    // ------------------------------------------------------------------ tools

    /** Uses left on a tool. */
    static int left(ItemStack s) {
        return s.isEmpty() || !s.isDamageableItem() ? 0 : s.getMaxDamage() - s.getDamageValue();
    }

    /** Its picks that are not about to break. */
    static int goodPicks(VillageFolkEntity f) {
        int n = 0;
        if (CaveDwellers.isPickaxe(f.getMainHandItem()) && left(f.getMainHandItem()) > WORN) n++;
        for (ItemStack s : f.getInventoryItems()) if (CaveDwellers.isPickaxe(s) && left(s) > WORN) n++;
        return n;
    }

    static boolean blade(ItemStack s) {
        return s.getItem() instanceof SwordItem;
    }

    /** Its blades that are not about to break. */
    static int goodBlades(VillageFolkEntity f) {
        int n = 0;
        if (blade(f.getMainHandItem()) && left(f.getMainHandItem()) > WORN) n++;
        for (ItemStack s : f.getInventoryItems()) if (blade(s) && left(s) > WORN) n++;
        return n;
    }

    /** Has it the makings of a pick (a head of iron, stone or wood, and the sticks), with a table or the planks for one? */
    static boolean canMakePick(VillageFolkEntity f) {
        int planks = f.countMatching(s -> s.is(ItemTags.PLANKS)) + 4 * f.countMatching(s -> s.is(ItemTags.LOGS));
        boolean table = f.countMatching(s -> s.is(Items.CRAFTING_TABLE)) > 0 || planks >= 4;
        if (!table || sticks(f) < 2) return false;
        return f.countMatching(s -> s.is(Items.IRON_INGOT)) >= 3 || f.countMatching(s -> s.is(ItemTags.STONE_TOOL_MATERIALS)) >= 3
            || planks >= 4 + (f.countMatching(s -> s.is(Items.CRAFTING_TABLE)) > 0 ? 0 : 4);
    }

    /** The picks it would make, best first: diamond (when it may), iron, stone, wood. */
    private static List<Item> picksToMake(boolean diamond) {
        return diamond ? List.of(Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE)
            : List.of(Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE);
    }

    private static final List<Item> BLADES = List.of(Items.IRON_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD);

    /**
     * What it wants made now, or null: a pick when it has no good one (or a spare before a long dig, or a diamond one
     * the cave's list calls for), a blade when it has no good one.
     */
    @Nullable
    static String wanted(ServerLevel level, VillageFolkEntity f, CaveDwellers.Party p) {
        if (goodPicks(f) == 0 && canMakePick(f)) return "pick";
        if (goodBlades(f) == 0 && sticks(f) >= 1 && f.countMatching(s -> s.is(Items.CRAFTING_TABLE)) > 0
            && (f.countMatching(s -> s.is(Items.IRON_INGOT)) >= 2 || f.countMatching(s -> s.is(ItemTags.STONE_TOOL_MATERIALS)) >= 2)) return "blade";
        if (diamondCalledFor(level, f, p) && f.countMatching(s -> s.is(Items.CRAFTING_TABLE)) > 0 && sticks(f) >= 2) return "diamond";
        if (p.spareWanted && goodPicks(f) == 1 && canMakePick(f)) {
            ItemStack pick = CaveDwellers.bestPick(f);
            if (left(pick) * 2 < pick.getMaxDamage()) return "spare";
        }
        return null;
    }

    /** May a diamond pick be made of the diamonds it carries: a vein on the cave's list of something the town wants
     *  waits on a better pick than any of the team has? (The town wants its diamonds in the storehouse otherwise.) */
    static boolean diamondCalledFor(ServerLevel level, VillageFolkEntity f, CaveDwellers.Party p) {
        if (f.countMatching(s -> s.is(Items.DIAMOND)) < 3 || p.caveKey == null) return false;
        for (VillageFolkEntity m : CaveDwellers.members(level, p)) if (CaveDwellers.bestPick(m).is(Items.DIAMOND_PICKAXE)) return false;
        List<String> wanted = CaveDwellers.wantedOres(level, p.village);
        for (CaveDwellers.Vein v : CaveDwellers.veins(p.village, p.caveKey)) {
            if (!"waiting".equals(v.state()) || !wanted.contains(v.ore())) continue;
            BlockState st = CaveDwellers.oreState(v.ore());
            if (st != null && new ItemStack(Items.DIAMOND_PICKAXE).isCorrectToolForDrops(st)) return true;
        }
        return false;
    }

    /**
     * Its tools seen to (CaveDwellers.drive, between the fighting and the work): what it wants made, at its crafting
     * table set down beside it, made, and the table picked up again. True while it is at it.
     */
    static boolean tools(ServerLevel level, VillageFolkEntity f, CaveDwellers.Delve d, CaveDwellers.Party p) {
        if (d.table != null) {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(d.table.getX() + 0.5, d.table.getY() + 0.5, d.table.getZ() + 0.5);
            f.hobbyNow = "at its crafting table, making a new " + (d.making == null ? "tool" : d.making);
            if (f.tickCount - d.tableTick < AT_TABLE) return true;
            String what = d.making;
            make(level, f, p, what, true);
            pickUpTable(level, f, d);
            d.making = null;
            return true;
        }
        if (f.tickCount - d.toolTick < 100) return false;
        d.toolTick = f.tickCount;
        String what = wanted(level, f, p);
        if (what == null) return false;
        // A table to set down: its own, or one made of four of its planks (two by two, in the hand).
        if (f.countMatching(s -> s.is(Items.CRAFTING_TABLE)) == 0) {
            once(level, f, Items.CRAFTING_TABLE, false, 1, new LinkedHashMap<>());
            if (f.countMatching(s -> s.is(Items.CRAFTING_TABLE)) == 0) return false;
            p.crafted.add(f.displayNameCap() + " made a crafting table");
        }
        BlockPos spot = tableSpot(level, f);
        if (spot == null || f.removeMatching(s -> s.is(Items.CRAFTING_TABLE), 1) < 1) return false;
        level.setBlockAndUpdate(spot, Blocks.CRAFTING_TABLE.defaultBlockState());
        f.placeSound(spot);
        f.swing(InteractionHand.MAIN_HAND);
        d.table = spot;
        d.tableTick = f.tickCount;
        d.making = what;
        FolkTalk.speak(f, switch (what) {
            case "pick" -> FolkTalk.pick(f.getRandom(), "My pick's done for. Give me a moment — I'll make another.", "Pick's worn through. Table down, new one up.");
            case "blade" -> "My blade's had it. A new one, quick.";
            case "diamond" -> "We've the diamonds for a pick that'll take the obsidian. Let's make it.";
            default -> "A long dig ahead. I'll make a spare pick first.";
        });
        return true;
    }

    /** The thing made at the table: the best its pack runs to. */
    static void make(ServerLevel level, VillageFolkEntity f, CaveDwellers.Party p, @Nullable String what, boolean table) {
        if (what == null) return;
        List<Item> order = switch (what) {
            case "blade" -> BLADES;
            case "diamond" -> List.of(Items.DIAMOND_PICKAXE);
            default -> picksToMake(false);
        };
        for (Item it : order) {
            Map<String, Integer> made = new LinkedHashMap<>();
            int before = f.countMatching(s -> s.is(it));
            if (!once(level, f, it, table, 2, made) || f.countMatching(s -> s.is(it)) <= before) continue;
            // The town's materials, the town's tool: its mark on it, back to the stores when it leaves the trade.
            for (ItemStack s : f.getInventoryItems()) if (s.is(it) && !WatchKit.issued(s)) { WatchKit.mark(s); break; }
            String line = "made " + JobMarket.a(words(it));
            p.crafted.add(f.displayNameCap() + " " + line);
            p.toolsMade++;
            f.brain(line + " at its crafting table, down in the caves");
            if (it instanceof PickaxeItem) CaveDwellers.equipPick(f);
            else f.equipBestWeapon();
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There — " + JobMarket.a(words(it)) + ". Good as new.", "Done. Back to it."));
            CaveDwellers.LOG.info("[MCA-CAVES] {} {} at {}", f.displayNameCap(), line, f.blockPosition().toShortString());
            return;
        }
        f.brain("could not make a " + what + " of what it carries");
    }

    /** Beside it, open, on a floor, not where any of the team stands. */
    @Nullable
    private static BlockPos tableSpot(ServerLevel level, VillageFolkEntity f) {
        BlockPos feet = f.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos q = feet.relative(dir);
            if (!level.getBlockState(q).isAir() || !level.getFluidState(q).isEmpty()) continue;
            if (!level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)) continue;
            if (!level.getEntitiesOfClass(VillageFolkEntity.class, new net.minecraft.world.phys.AABB(q)).isEmpty()) continue;
            return q;
        }
        return null;
    }

    /** The table picked up again, into the pack. */
    static void pickUpTable(ServerLevel level, VillageFolkEntity f, CaveDwellers.Delve d) {
        BlockPos at = d.table;
        d.table = null;
        if (at == null) return;
        if (level.getBlockState(at).is(Blocks.CRAFTING_TABLE)) {
            level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            f.swing(InteractionHand.MAIN_HAND);
        }
        ItemStack left = f.insertItem(WatchKit.mark(new ItemStack(Items.CRAFTING_TABLE)));
        if (!left.isEmpty()) f.spawnAtLocation(left);
    }

    /** Could it make a tool at all (to go home for want of one, it cannot)? */
    static boolean canMakeTool(VillageFolkEntity f) {
        return canMakePick(f);
    }
}
