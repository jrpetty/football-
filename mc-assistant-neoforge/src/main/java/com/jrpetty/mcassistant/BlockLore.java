package com.jrpetty.mcassistant;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What every block in the game is FOR.
 *
 * <p>A builder that knows only "cobblestone, planks, dirt" is a builder that
 * cannot use the deepslate it just dug, the bricks it just fired or the wool
 * it just sheared. This reads the whole block registry — every block the game
 * has, vanilla or added by another mod — and sorts each one into what a person
 * would actually use it FOR: something to build with, something to see by,
 * somewhere to put things, something that grows, something that will kill you.
 *
 * <p>Nothing here is a hand-written list of blocks, because a hand-written
 * list is out of date the moment anything is added. Every verdict comes from
 * the game's own tags, the block's own class, and its own properties — so a
 * block nobody here has ever heard of still gets a sensible answer.
 *
 * <p>Each use carries the three things worth knowing about it: what it DOES,
 * WHY you would put one down, and WHEN in the life of a settlement it starts
 * to matter. That last one is the same ladder the villages climb, so "when"
 * is a real answer and not a figure of speech.
 */
public final class BlockLore {

    private BlockLore() {}

    /** What a block is for, in the terms somebody building a base thinks in. */
    public enum Use {
        STRUCTURE("Building block",
            "Stacks, holds itself up and holds a roof up.",
            "Walls, floors and roofs — the body of anything you build.",
            "From the first shelter. Whatever you have most of is the right one.",
            "Wood age"),
        LIGHT("Light",
            "Gives off light, which stops mobs spawning around it.",
            "A lit base is a base nothing spawns inside. The cheapest defence there is.",
            "The moment you have a roof — before walls, before anything else.",
            "Wood age"),
        STORAGE("Storage",
            "Holds items you put in it, and keeps holding them.",
            "A full pack stops a trade dead. Somewhere to put the harvest IS the farm.",
            "First building. Nothing else works until output has somewhere to go.",
            "Wood age"),
        WORKSTATION("Workstation",
            "Turns what you have into what you need — bench, furnace, forge.",
            "The whole difference between gathering and making.",
            "Bench first, furnace as soon as there is stone and fuel.",
            "Wood age"),
        DOORWAY("Door or gate",
            "A gap you can pass and most things cannot.",
            "A doorway with no door is a hole in your wall you personally walk through.",
            "With the first walls.",
            "Wood age"),
        BED("Bed",
            "Skips the night, and sets where you come back to.",
            "Night is when a base is attacked. Sleeping through it is a defence.",
            "As soon as there is wool and a roof to put it under.",
            "Wood age"),
        LADDER("Ladder",
            "Climbed up and down; stops a fall being the way you leave.",
            "A shaft with no way back up is a hole you dug for yourself.",
            "Before the first shaft, not after.",
            "Stone age"),
        PARTIAL("Slab or stair",
            "Half a block: a step you can walk up without jumping.",
            "Floors, steps and roofs without losing the headroom a full block costs.",
            "Once the shell stands and you want to move around inside it.",
            "Stone age"),
        FENCE("Fence or wall part",
            "Blocks the way without blocking the view; mobs cannot jump it.",
            "Pens keep animals in. Walls keep everything else out.",
            "Pens with the first livestock, walls once there is stone to spare.",
            "Stone age"),
        WINDOW("Glass",
            "Lets light through and keeps everything else out.",
            "A lit room you can see out of, which is how you spot what is coming.",
            "Once there is sand and a furnace.",
            "Stone age"),
        SOIL("Soil",
            "What crops will grow in, once it is worked and watered.",
            "Every field starts as this and water within four blocks.",
            "First field, first day.",
            "Wood age"),
        CROP("Crop",
            "Grows over time and can be harvested and replanted.",
            "Food you do not have to go and find. The only supply that renews itself.",
            "Immediately — a crop takes days, so plant before you are hungry.",
            "Wood age"),
        SAPLING("Sapling",
            "Grows into a tree given light and room.",
            "Replanting is the difference between a wood and a clearing.",
            "Every time a tree comes down.",
            "Wood age"),
        FOLIAGE("Foliage",
            "Grows on its own; mostly in the way, sometimes worth keeping.",
            "Clearing it is how ground becomes a plot. Leaves also drop saplings.",
            "Cleared when a plot is staked.",
            "Wood age"),
        ORE("Ore",
            "Holds metal or fuel; needs the right pickaxe or it drops nothing.",
            "Everything past stone comes out of these.",
            "As soon as there is a shaft and a pick that will pay for itself.",
            "Stone age"),
        VALUABLE("Valuable block",
            "Stores a lot of worth in one block, or does something no other block does.",
            "Worth mining, worth keeping, not worth building a wall out of.",
            "Kept in the stores, spent deliberately.",
            "Iron age"),
        FUEL("Fuel",
            "Burns in a furnace.",
            "A forge with no fuel is a stone box. Fuel is the second half of every smelt.",
            "Stockpiled alongside the ore, never after.",
            "Stone age"),
        LIQUID("Liquid",
            "Flows, and changes whatever it reaches.",
            "Water makes farmland; lava is a wall you never have to build.",
            "Water at the first field. Lava, carefully, or never.",
            "Wood age"),
        HAZARD("Hazard",
            "Hurts anything that touches or falls into it.",
            "Worth knowing so you can walk round it — and, now and then, so you can use it.",
            "Route around it, always.",
            "Wood age"),
        REDSTONE("Redstone part",
            "Carries or reacts to a signal.",
            "Doors that open themselves, farms that harvest themselves.",
            "Once the basics stand and there is time to be clever.",
            "Iron age"),
        TRANSPORT("Transport",
            "Moves things or people faster than legs do.",
            "A long haul done once is a walk. Done fifty times it is a railway.",
            "Once the same route is walked every day.",
            "Iron age"),
        UTILITY("Utility",
            "Does one specific job well — brewing, enchanting, repairing, composting.",
            "Each replaces a whole afternoon of doing something the hard way.",
            "When the thing it does becomes a chore.",
            "Iron age"),
        DECORATION("Decoration",
            "Does nothing mechanical. Looks like somebody lives here.",
            "The difference between a base and a home, which is most of the point.",
            "Whenever there is a spare afternoon.",
            "Stone age"),
        BEDROCK("Indestructible",
            "Cannot be broken by any tool.",
            "The floor of the world and the edge of what you can change.",
            "Never. It marks the bottom of the shaft.",
            "Nether age"),
        NATURAL("Natural block",
            "Part of the landscape; no particular job.",
            "Usually something to dig through on the way somewhere.",
            "Cleared, not placed.",
            "Wood age");

        public final String label;
        public final String does;
        public final String why;
        public final String when;
        public final String age;

        Use(String label, String does, String why, String when, String age) {
            this.label = label;
            this.does = does;
            this.why = why;
            this.when = when;
            this.age = age;
        }
    }

    private static final Map<Block, Use> CACHE = new ConcurrentHashMap<>();

    /** What this block is for. Worked out once per block, then remembered. */
    public static Use of(Block block) {
        Use known = CACHE.get(block);
        if (known != null) return known;
        Use worked = classify(block);
        CACHE.put(block, worked);
        return worked;
    }

    public static Use of(ItemStack stack) {
        Block b = Block.byItem(stack.getItem());
        return b == Blocks.AIR ? Use.NATURAL : of(b);
    }

    /**
     * Is this worth building a wall out of? Deliberately narrow: it must stack
     * and stay put, so sand and gravel are out (they fall), leaves are out
     * (they rot), and anything with a job of its own is out — a wall made of
     * chests is a wall that costs you your chests.
     */
    public static boolean structural(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Block b = Block.byItem(stack.getItem());
        if (b == Blocks.AIR) return false;
        Use use = of(b);
        if (use == Use.STRUCTURE) return true;
        // Dirt is filed as soil, because that is what it is FOR — but a full
        // cube of it still bridges a gap and plugs a hole, and dirt is what
        // anybody actually bridges with. Only the solid ones: grass and podzol
        // yes, a sapling growing in it no.
        BlockState st = b.defaultBlockState();
        return use == Use.SOIL && st.isSolid() && st.canOcclude();
    }

    /** Does putting this down light the place up? */
    public static boolean lightSource(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Block b = Block.byItem(stack.getItem());
        return b != Blocks.AIR && of(b) == Use.LIGHT;
    }

    /** Everything the game has that is used this way, in registry order. */
    public static List<Block> byUse(Use use) {
        List<Block> out = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            if (of(b) == use) out.add(b);
        }
        return out;
    }

    /** How many blocks fall under each use — the book's contents page. */
    public static Map<Use, Integer> census() {
        Map<Use, Integer> out = new EnumMap<>(Use.class);
        for (Block b : BuiltInRegistries.BLOCK) {
            out.merge(of(b), 1, Integer::sum);
        }
        return out;
    }

    /**
     * Write down what this build believes about every block in the game, so
     * the belief can be checked against the game instead of taken on trust.
     *
     * <p>The sort below is rules over the registry, and rules over a thousand
     * blocks are wrong somewhere. This is how you find where: one line per
     * block, its id and the verdict, in a file you can read.
     */
    public static java.nio.file.Path audit(java.nio.file.Path dir) throws java.io.IOException {
        java.nio.file.Path out = dir.resolve("mcassistant-blocklore.txt");
        StringBuilder sb = new StringBuilder();
        Map<Use, Integer> counts = census();
        sb.append("MC Assistant — what every block is for\n");
        sb.append(BuiltInRegistries.BLOCK.size()).append(" blocks\n\n");
        for (Use u : Use.values()) {
            sb.append(String.format("%-18s %5d   %s%n", u.name(), counts.getOrDefault(u, 0), u.label));
        }
        sb.append('\n');
        for (Use u : Use.values()) {
            sb.append("\n== ").append(u.name()).append(" — ").append(u.label).append(" ==\n");
            sb.append("   does: ").append(u.does).append('\n');
            sb.append("   why:  ").append(u.why).append('\n');
            sb.append("   when: ").append(u.when).append("  (").append(u.age).append(")\n");
            for (Block b : byUse(u)) {
                sb.append("   ").append(BuiltInRegistries.BLOCK.getKey(b)).append('\n');
            }
        }
        java.nio.file.Files.writeString(out, sb.toString());
        return out;
    }

    // ---------------------------------------------------------------- the sort

    /**
     * The verdict, in the order a person would reach it: the things a block IS
     * beat the things it merely resembles. A furnace is a workstation before it
     * is a stone block; a jack o'lantern is a light before it is decoration.
     */
    private static Use classify(Block block) {
        BlockState state = block.defaultBlockState();
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        String path = key == null ? "" : key.getPath();

        if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
            return Use.NATURAL;
        }
        // Nothing can be done with a block nothing can break.
        if (block.defaultDestroyTime() < 0.0F) return Use.BEDROCK;

        // Light first: a lit block's job is lighting, whatever else it is made
        // of. This is read from the block's own emission, so a lamp added by
        // another mod is a light here without anybody being told.
        if (state.getLightEmission() >= 7 && !(block instanceof LiquidBlock)
            && !path.startsWith("redstone") && !path.contains("ore")
            && !path.equals("magma_block")) {
            return Use.LIGHT;
        }

        if (block instanceof LiquidBlock) return Use.LIQUID;
        if (block instanceof LeavesBlock || state.is(BlockTags.LEAVES)) return Use.FOLIAGE;
        if (block instanceof SaplingBlock || state.is(BlockTags.SAPLINGS)) return Use.SAPLING;
        if (block instanceof CropBlock || state.is(BlockTags.CROPS)) return Use.CROP;
        if (block instanceof FarmBlock || state.is(BlockTags.DIRT)
            || state.is(BlockTags.SAND) && path.contains("soul")) {
            return Use.SOIL;
        }
        if (state.is(BlockTags.BEDS)) return Use.BED;
        if (block instanceof LadderBlock || state.is(BlockTags.CLIMBABLE)) return Use.LADDER;
        // A stair or a slab is not a building block HERE, whatever it is to a
        // player: the builder places full cubes, and a wall with a half-block
        // in it is a wall with a hole in it.
        if (state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS)) return Use.PARTIAL;
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)
            || state.is(BlockTags.FENCE_GATES)) {
            return Use.DOORWAY;
        }
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)) return Use.FENCE;
        if (block instanceof ChestBlock || path.endsWith("shulker_box")
            || path.equals("barrel") || path.equals("chest") || path.equals("trapped_chest")
            || path.equals("ender_chest")) {
            return Use.STORAGE;
        }
        if (isWorkstation(path)) return Use.WORKSTATION;
        // Grown, not quarried: a melon or a pumpkin is a harvest that happens
        // to be a full cube, and both were reading as building material.
        if (path.equals("melon") || path.equals("pumpkin")
            || path.equals("attached_melon_stem") || path.equals("attached_pumpkin_stem")) {
            return Use.CROP;
        }
        if (isUtility(path)) return Use.UTILITY;
        if (isHazard(path)) return Use.HAZARD;
        if (state.is(BlockTags.RAILS) || path.contains("rail")) return Use.TRANSPORT;
        if (isRedstone(path)) return Use.REDSTONE;
        if (path.endsWith("_ore") || path.equals("ancient_debris")
            || path.equals("nether_gold_ore") || path.equals("gilded_blackstone")) {
            return Use.ORE;
        }
        if (isFuel(path)) return Use.FUEL;
        if (isValuable(path)) return Use.VALUABLE;
        if (isGlass(path)) return Use.WINDOW;
        if (isDecoration(state, path)) return Use.DECORATION;

        // A falling block is not something to build a wall out of, whatever it
        // looks like — the wall arrives on the floor.
        if (block instanceof FallingBlock || state.is(BlockTags.SAND)) return Use.NATURAL;

        // Whatever is left: if it stacks, stays put and fills its own cube, it
        // is something to build with. This is the rung that catches every
        // brick, tile, plank and stone the game has or ever will have.
        if (state.isSolid() && state.canOcclude()) return Use.STRUCTURE;
        return Use.NATURAL;
    }

    private static boolean isWorkstation(String path) {
        return path.equals("crafting_table") || path.contains("furnace")
            || path.equals("smoker") || path.equals("anvil") || path.endsWith("_anvil")
            || path.equals("smithing_table") || path.equals("stonecutter")
            || path.equals("loom") || path.equals("cartography_table")
            || path.equals("fletching_table") || path.equals("grindstone")
            || path.equals("brewing_stand") || path.equals("enchanting_table")
            || path.equals("campfire") || path.equals("soul_campfire");
    }

    private static boolean isUtility(String path) {
        return path.equals("composter") || path.equals("beehive") || path.equals("bee_nest")
            || path.equals("lectern") || path.equals("bell") || path.contains("bookshelf")
            || path.equals("sponge") || path.equals("wet_sponge")
            || path.contains("cauldron") || path.equals("hopper") || path.equals("dropper")
            || path.equals("dispenser") || path.equals("beacon") || path.equals("conduit")
            || path.equals("respawn_anchor") || path.equals("lodestone")
            || path.equals("jukebox") || path.equals("note_block")
            || path.equals("spawner") || path.equals("trial_spawner")
            || path.equals("vault") || path.equals("decorated_pot");
    }

    private static boolean isHazard(String path) {
        return path.equals("magma_block") || path.equals("cactus")
            || path.equals("fire") || path.equals("soul_fire")
            || path.equals("sweet_berry_bush") || path.equals("powder_snow")
            || path.equals("tnt") || path.contains("pointed_dripstone")
            || path.equals("wither_rose") || path.contains("portal")
            || path.equals("lava") || path.equals("lava_cauldron");
    }

    private static boolean isRedstone(String path) {
        return path.startsWith("redstone") || path.contains("piston")
            || path.equals("observer") || path.equals("lever") || path.contains("button")
            || path.contains("pressure_plate") || path.equals("repeater")
            || path.equals("comparator") || path.equals("target")
            || path.equals("daylight_detector") || path.contains("tripwire")
            || path.equals("sculk_sensor") || path.equals("calibrated_sculk_sensor")
            || path.equals("lightning_rod") || path.equals("copper_bulb")
            || path.contains("copper_bulb") || path.equals("crafter");
    }

    private static boolean isFuel(String path) {
        // A hay bale burns twenty items in a furnace, which makes it fuel
        // before it is anything else — it was falling through to "build a wall
        // out of it", which is a wall you could have smelted a stack with.
        return path.equals("coal_block") || path.equals("dried_kelp_block")
            || path.equals("charcoal_block") || path.equals("hay_block");
    }

    private static boolean isValuable(String path) {
        // Raw ore blocks are a stack of ore in one cube. They are solid and
        // they occlude, so they were coming out as ordinary building material
        // — a village would happily have walled itself in with its own iron.
        if (path.startsWith("raw_") && path.endsWith("_block")) return true;
        return path.equals("diamond_block") || path.equals("emerald_block")
            || path.equals("gold_block") || path.equals("iron_block")
            || path.equals("netherite_block") || path.equals("lapis_block")
            || path.equals("copper_block") || path.equals("redstone_block")
            || path.equals("obsidian") || path.equals("crying_obsidian")
            || path.equals("amethyst_block") || path.equals("budding_amethyst");
    }

    private static boolean isGlass(String path) {
        return path.contains("glass");
    }

    private static boolean isDecoration(BlockState state, String path) {
        return state.is(BlockTags.FLOWERS) || state.is(BlockTags.BANNERS)
            || state.is(BlockTags.SIGNS) || state.is(BlockTags.CANDLES)
            || state.is(BlockTags.WOOL_CARPETS) || state.is(BlockTags.FLOWER_POTS)
            || path.contains("head") || path.contains("skull")
            || path.equals("armor_stand") || path.contains("coral")
            || path.contains("sculk") || path.contains("moss")
            || path.contains("vine") || path.contains("hanging")
            || path.contains("azalea") || path.contains("bamboo_mosaic");
    }
}
