package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Districts;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The town's look [batchE]: what makes a town of a hundred look like a town and not a camp that grew.
 * <ul>
 * <li><b>Tree-lined avenues</b> (Avenues): a tree on the verge midway between each pair of lamp posts,
 *     out of the stores' saplings, and the leaves kept off the heads of whoever walks under them.</li>
 * <li><b>Street furniture</b> (StreetFurniture): a bench at the street corners, a box of flowers under
 *     the windows of the houses that can afford one, and a notice board by the square with the day's news.</li>
 * <li><b>The allotments</b> (Allotments): plots by the fields for the households with no garden, worked
 *     of an evening, the vegetables carried home.</li>
 * <li><b>The orchard</b> (Orchard): oaks by the fields, planted and picked by the farmers.</li>
 * <li><b>The windmill</b> (Windmill): the landmark of the farmland, where the town's grain is kept.</li>
 * <li><b>The bakery</b> (Bakery): bread, cookies, pies and cakes for the café, the shop and the feasts.</li>
 * <li><b>The inn</b> (Inn): rooms by the road for whoever is passing through, folk or player.</li>
 * </ul>
 * Each is a little at a time, by a hand at the town's works (TownJobs), out of the stores: nothing is
 * planted, set out or baked that the stores did not hold first or the world did not give.
 */
public final class TownLook {

    private TownLook() {}

    public static final String WINDMILL = "windmill", BAKERY = "bakery", INN = "inn", ORCHARD = "orchard", ALLOTMENTS = "allotments";
    /** The buildings this batch adds to what the builders can raise. */
    public static final List<String> BUILDINGS = List.of(WINDMILL, BAKERY, INN, ORCHARD, ALLOTMENTS);

    /** Everything that is only in memory forgotten (the tests share one JVM). */
    public static void resetForTests() {
        Avenues.resetForTests();
        StreetFurniture.resetForTests();
        Allotments.resetForTests();
        Orchard.resetForTests();
        Windmill.resetForTests();
        Bakery.resetForTests();
        Inn.resetForTests();
    }

    /**
     * From the town's rounds (TownWork.tick, a quarter of a minute apart for each village): a look at each
     * of them in turn. Each does at most one thing a visit, and nothing while the bell is ringing.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        if (Raids.underAlarm(v.id()) || !level.isLoaded(v.centre())) return;
        Avenues.tick(level, v);
        StreetFurniture.tick(level, v);
        Orchard.tick(level, v);
        Allotments.tick(level, v);
        Windmill.tick(level, v);
        Bakery.tick(level, v);
        Inn.tick(level, v);
    }

    // ------------------------------------------------------------------ what the builders are asked for

    /**
     * The look's buildings a village wants now, onto its list of amenities (Villages.projectsWantedInOrder,
     * from the Stone Age): the windmill, the orchard and the bakery for a town that farms (a bakery bakes the
     * farmers' wheat), the allotments once it has households with no garden of their own, and the inn from
     * the Iron Age (or in a town of thirty) once it has somebody to keep it: a cook or a shopkeeper.
     */
    public static void wanted(UUID village, int folk, Villages.Age at, List<String> extras, Predicate<String> notBuilt) {
        boolean farming = farmers(village) > 0;
        if (farming && folk >= Windmill.FROM && notBuilt.test(WINDMILL)) extras.add(WINDMILL);
        if (farming && folk >= Bakery.FROM && notBuilt.test(BAKERY)) extras.add(BAKERY);
        if (farming && folk >= Orchard.FROM && notBuilt.test(ORCHARD)) extras.add(ORCHARD);
        if (folk >= Allotments.FROM && Allotments.wanted(village) && notBuilt.test(ALLOTMENTS)) extras.add(ALLOTMENTS);
        if ((at.ordinal() >= Villages.Age.IRON.ordinal() || folk >= Inn.FROM_FOLK) && folk >= Inn.FROM_FOLK_IRON
                && Inn.keeper(village) != null && notBuilt.test(INN)) {
            extras.add(INN);
        }
    }

    /** Why the village wants one of the look's buildings (Villages.whyBuild). */
    public static String why(UUID village, String project) {
        int folk = Villages.headcount(village);
        return switch (project) {
            case WINDMILL -> "a windmill by the fields, where the town's grain is kept and the bakery's wheat is ground: the landmark of the farmland";
            case BAKERY -> "a bakery: bread, cookies, pies and cakes for the café, the shop and the feasts, now there are " + folk + " to feed";
            case INN -> "an inn by the road into town: rooms for the caravans, the envoys, the folk on the road and any player passing through";
            case ORCHARD -> "an orchard by the fields: oaks for apples, and the timber and saplings that come of picking them";
            case ALLOTMENTS -> "allotments by the fields, for the " + Allotments.gardenless(village) + " households with no garden of their own";
            default -> "the " + project;
        };
    }

    /** How many farmers the village has. */
    static int farmers(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (!a.isBaby() && a.stationTask() == AssistantEntity.StationTask.FARM) n++;
        return n;
    }

    // ------------------------------------------------------------------ where they go

    /** Does this building go by the fields (the windmill, the orchard, the allotments)? */
    public static boolean byTheFields(String project) {
        return project.equals(WINDMILL) || project.equals(ORCHARD) || project.equals(ALLOTMENTS);
    }

    /**
     * The plan's lots for a building that goes by the fields (Quarters.candidates): the ordinary lots, the
     * nearest the farm gate first (where the avenue goes out between the fields as a farm track), so the
     * windmill, the orchard and the allotments stand together at the edge of the farmland. The lots on the
     * farmland itself are passed over as for anything else (Villages.lotKeptOff). A village with no fields
     * yet takes them the plan's own way, out toward the edge.
     */
    public static List<TownPlan.Lot> fieldLots(UUID village, List<TownPlan.Lot> plan) {
        int side = Villages.fieldsSide(village);
        if (side < 0) return plan;
        int[] gate = Villages.offset(side, Villages.FIRST_BLOCK, 0);
        List<TownPlan.Lot> out = new ArrayList<>();
        for (TownPlan.Lot l : plan) {
            if (l.kind() != TownPlan.Kind.LOT || Districts.keptFor(l, null)) continue;
            if (!out.contains(l)) out.add(l);
        }
        out.sort(Comparator.comparingLong((TownPlan.Lot l) -> (long) (l.x() - gate[0]) * (l.x() - gate[0])
            + (long) (l.z() - gate[1]) * (l.z() - gate[1])).thenComparingInt(TownPlan.Lot::x).thenComparingInt(TownPlan.Lot::z));
        for (TownPlan.Lot l : plan) if (!out.contains(l)) out.add(l);
        return out;
    }

    // ------------------------------------------------------------------ shared

    /** The village's building of this kind, or null. */
    @Nullable
    static Ledger.Building building(UUID village, String structure) {
        return Villages.builtStructure(village, structure);
    }

    /** A cell of a building's drawing in the world: across (right is +), up, and toward the back. */
    static BlockPos cell(Ledger.Building b, int dx, int h, int dz) {
        Direction right = b.facing().getClockWise();
        return b.anchor().relative(right, dx).relative(b.facing(), dz).above(h);
    }

    /** The first free block over the ground at this column (leaves not counted as ground). */
    static BlockPos ground(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    /** A door within so many blocks across (and two up or down) of here: nothing is put in anybody's way in. */
    static boolean doorNear(ServerLevel level, BlockPos at, int r) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -2, -r), at.offset(r, 2, r))) {
            if (level.getBlockState(p).getBlock() instanceof DoorBlock) return true;
        }
        return false;
    }

    /** A post of the town's within a block of here: a lamp post, a street sign, a fence, a light. */
    static boolean postNear(ServerLevel level, BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, 0, -1), at.offset(1, 3, 1))) {
            BlockState st = level.getBlockState(p);
            if (st.is(BlockTags.FENCES) || st.is(BlockTags.WALLS) || st.is(Blocks.LANTERN) || st.is(Blocks.TORCH)
                    || st.is(Blocks.WALL_TORCH) || st.is(BlockTags.ALL_SIGNS)) return true;
        }
        return false;
    }

    /** Clear to put something here: air (or grass and the like) with nothing running in it. */
    static boolean open(BlockState st) {
        return st.isAir() || (st.canBeReplaced() && st.getFluidState().isEmpty());
    }

    /** The woods a town's planks come in, the commonest first, for a thing of a wood (a stair, a trapdoor). */
    private static final String[] WOODS = { "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry" };

    /** A wooden thing of this kind ("stairs", "trapdoor") made of this wood, or null. */
    @Nullable
    static Item ofWood(String wood, String thing) {
        Item it = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(wood + "_" + thing));
        return it == Items.AIR ? null : it;
    }

    /**
     * Of a thing already made, what the stores hold past this many is the town's to put out on its streets:
     * a stack of stairs or trapdoors in the stores is a roof or a house's door the builders mean to use.
     */
    static final int MADE_KEPT = 64;

    /**
     * Planks of this wood a maker may use: what the stores hold past what the village keeps back from any
     * maker (Bench: the builders' working timber, or all of it while the age is short of timber).
     */
    static int sparePlanks(ServerLevel level, Villages.Village v, Item plank) {
        int[] book = Bench.keepBook(level, v, new java.util.HashMap<>()).get(plank);
        return book == null ? 0 : book[1];
    }

    /**
     * A wooden thing out of the stores for the streets: one already made ({@code made}) if the stores hold
     * more of them than the builders might want (MADE_KEPT), else made here of {@code planks} of one wood by
     * the game's recipe, {@code makes} of them, the rest back into the stores (six planks are four stairs, or
     * two trapdoors), out of the planks the builders can spare. Returns the block it is, or null.
     */
    @Nullable
    static Block wooden(ServerLevel level, Villages.Village v, net.minecraft.tags.TagKey<Item> made, String thing, int planks, int makes) {
        if (Market.stock(level, v.id(), s -> s.is(made)) > MADE_KEPT) {
            ItemStack one = Crafts.takeOne(level, v, s -> s.is(made));
            if (!one.isEmpty()) {
                Block b = Block.byItem(one.getItem());
                if (b != Blocks.AIR) return b;
                Crafts.store(level, v, one);
            }
        }
        for (String wood : WOODS) {
            Item plank = ofWood(wood, "planks"), it = ofWood(wood, thing);
            if (plank == null || it == null) continue;
            if (sparePlanks(level, v, plank) < planks) continue;                       // the builders' boards first
            if (!Crafts.take(level, v, s -> s.is(plank), planks)) continue;
            if (makes > 1) Crafts.store(level, v, new ItemStack(it, makes - 1));
            return Block.byItem(it);
        }
        return null;
    }

    /** Could the stores run to one of these wooden things (wooden, above)? */
    static boolean canWooden(ServerLevel level, Villages.Village v, net.minecraft.tags.TagKey<Item> made, String thing, int planks) {
        if (Market.stock(level, v.id(), s -> s.is(made)) > MADE_KEPT) return true;
        for (String wood : WOODS) {
            Item plank = ofWood(wood, "planks"), it = ofWood(wood, thing);
            if (plank != null && it != null && sparePlanks(level, v, plank) >= planks) return true;
        }
        return false;
    }

    /** A sign out of the stores for the town's notices: a sign put by, else two of the planks the builders can spare. */
    static boolean sign(ServerLevel level, Villages.Village v) {
        if (Crafts.take(level, v, s -> s.is(ItemTags.SIGNS), 1)) return true;
        for (String wood : WOODS) {
            Item plank = ofWood(wood, "planks");
            if (plank != null && sparePlanks(level, v, plank) >= 2 && Crafts.take(level, v, s -> s.is(plank), 2)) return true;
        }
        return false;
    }

    /** Could the stores run to so many signs (sign, above)? */
    static boolean canSign(ServerLevel level, Villages.Village v, int n) {
        int have = Market.stock(level, v.id(), s -> s.is(ItemTags.SIGNS));
        if (have >= n) return true;
        for (String wood : WOODS) {
            Item plank = ofWood(wood, "planks");
            if (plank != null && sparePlanks(level, v, plank) >= 2 * (n - have)) return true;
        }
        return false;
    }

    /** A sapling that grows on its own into one tree (not a dark oak, which wants four; not a mangrove). */
    static boolean plantable(ItemStack s) {
        return s.is(ItemTags.SAPLINGS) && !s.is(Items.DARK_OAK_SAPLING) && !s.is(Items.MANGROVE_PROPAGULE);
    }

    /** "the twenty-third", the town's year's day: the fourth week of it is the picking week (Orchard). */
    static int dayOfYear(UUID village, long day) {
        long founded = com.jrpetty.mcassistant.village.Chronicle.foundedOn(village);
        return (int) Math.floorMod(day - Math.max(0, founded), (long) TownCalendar.YEAR_DAYS);
    }

    // ------------------------------------------------------------------ where a player sees it

    /** One line for the village board (VillageBoards): the bakery's shelves, the inn's rooms, the mill, the trees. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        List<String> parts = new ArrayList<>();
        String bakery = Bakery.boardPart(level, village);
        if (bakery != null) parts.add(bakery);
        String inn = Inn.boardPart(level, village);
        if (inn != null) parts.add(inn);
        String mill = Windmill.boardPart(level, village);
        if (mill != null) parts.add(mill);
        int trees = Avenues.trees(level, village);
        if (trees > 0) parts.add(trees + (trees == 1 ? " tree" : " trees") + " along the avenues");
        return parts.isEmpty() ? null : "The town: " + String.join("; ", parts) + ".";
    }

    /** A line for a folk's card (FolkTalk): its allotment, its part at the bakery, the inn or the mill. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        List<String> parts = new ArrayList<>();
        String plot = Allotments.cardPart(f);
        if (plot != null) parts.add(plot);
        String inn = Inn.cardPart(f);
        if (inn != null) parts.add(inn);
        String bakery = Bakery.cardPart(f);
        if (bakery != null) parts.add(bakery);
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    // ------------------------------------------------------------------ /village townlook

    /** /village townlook: the town's look, in chat; and for operators, a showcase of it set out where you stand. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("townlook").executes(TownLook::tell)
            .then(Commands.literal("showcase").requires(src -> src.hasPermission(2)).executes(ctx -> {
                List<String> views = TownLookShowcase.showcase(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal("TOWNLOOK | " + String.join(" | ", views)), false);
                return views.size();
            }))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = Quarters.near(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                // The town's works done on the spot, a few rounds of them (the pictures): no hand walked over.
                TownJobs.instantForTests(true);
                try {
                    for (int i = 0; i < 8; i++) tick(level, v);
                } finally {
                    TownJobs.instantForTests(false);
                }
                return tell(ctx);
            }));
    }

    private static int tell(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = Quarters.near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        UUID id = v.id();
        StringBuilder sb = new StringBuilder("THE TOWN'S LOOK " + Villages.name(id) + ":");
        sb.append(" | avenues: ").append(Avenues.line(level, v));
        sb.append(" | furniture: ").append(StreetFurniture.line(level, v));
        sb.append(" | allotments: ").append(Allotments.line(level, v));
        sb.append(" | orchard: ").append(Orchard.line(level, v));
        sb.append(" | windmill: ").append(Windmill.line(level, v));
        sb.append(" | bakery: ").append(Bakery.line(level, v));
        sb.append(" | inn: ").append(Inn.line(level, v));
        String text = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
