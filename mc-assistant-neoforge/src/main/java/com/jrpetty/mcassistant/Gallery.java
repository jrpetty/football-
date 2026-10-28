package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Ages;
import com.jrpetty.mcassistant.entity.Architecture;
import com.jrpetty.mcassistant.entity.Homeland;
import com.jrpetty.mcassistant.entity.Interiors;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Palettes;
import com.jrpetty.mcassistant.entity.TownJobs;
import com.jrpetty.mcassistant.entity.TownLife;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * [gallery] Every drawing in the mod (data/mc_assistant/blueprints/*.txt) put up as a town puts it up, to be
 * photographed one by one (tools/realworld/gallery.py, .github/workflows/building-gallery.yml):
 * <pre>
 *   /village showcase gallery list             (ops) "GALLERY n drawings in r rows of 12 | name name ..."
 *   /village showcase gallery &lt;row&gt; [age] [land] (ops) that row of twelve, built where the command stands, a line a
 *                                              building: where it is, its door, and where to stand to take it from
 *                                              outside and from just inside its door; the town of that land (plains
 *                                              unless asked: desert, taiga, coast, mountain...) and its style
 * </pre>
 * The showcase (/village showcase buildings) stamps a building out of a palette of its own and leaves it bare; this
 * builds each the way the folk of a town would have it. A town is founded behind the stage (no folk: nothing of it
 * moves or builds while the pictures are taken) and given an age (the Iron Age unless asked), and every building is
 * set down in that town's own woods and stones (Palettes, as its builders choose them), made over for its age (Ages:
 * the stone walls, the slate roofs, the lamps by the door), its houses dressed in its style (Architecture), furnished
 * out of its stores by the town's own furnishing
 * (Interiors: the rugs, the barrels, the shelves and the lamps, the stores filled first so nothing is short), and
 * dressed as a town dresses its buildings (TownLife: the chimneys, the house signs, the café's and the shop's
 * counters, the tavern's board). Nothing here decides where a block goes: that is the drawing's and those classes'.
 *
 * <p>Every row is put up on the same stage, the row before cleared away first, so the camera has the same ground under
 * it all through and the client never waits on new country. Each building's two camera spots are found here, on the
 * server, where the blocks can be asked: outside, three-quarters on from the front, far enough back to take the whole
 * of it roof and all, its eyes in open air and its front door in plain sight (swung round, raised or brought nearer
 * till it is); inside, a step or two in from the door looking into the room, its eyes in open air with room in front
 * of them (CameraCommands.sees, as /village camera asks it).
 */
public final class Gallery {

    private Gallery() {}

    /** Buildings to a row (and to a contact sheet). */
    public static final int PER_ROW = 12;
    /** Open ground between two buildings' eaves in a row: room for the three-quarter view past a neighbour. */
    static final int GAP = 10;
    /** The buildings face the camera: their backs to the north, their doors to the south. */
    public static final Direction BACK = Direction.NORTH;
    /** A camera's eyes over its feet (CameraCommands). */
    public static final double EYES = CameraCommands.EYES;

    /** One building of a row, and where to stand to photograph it. */
    public record Shot(String name, int row, BlockPos anchor, @Nullable BlockPos door,
                       Vec3 out, Vec3 outAim, Vec3 outCheck, boolean doorSeen,
                       Vec3 in, Vec3 inAim, Vec3 inCheck, String inHow, int pieces) {

        /**
         * "BUILDING name row x y z door x y z|- - - out x y z ax ay az cx cy cz seen|hidden|nodoor in x y z ax ay az cx cy
         * cz door|cramped|open|above pieces n": the camera's feet, what it looks at, what /village camera is to be asked
         * it sees from there; how the inside is taken; how many pieces the furnishing put in.
         */
        public String line() {
            return "BUILDING " + name + " " + row + " " + anchor.getX() + " " + anchor.getY() + " " + anchor.getZ()
                + " door " + (door == null ? "- - -" : door.getX() + " " + door.getY() + " " + door.getZ())
                + " out " + v(out) + " " + v(outAim) + " " + v(outCheck) + " " + (door == null ? "nodoor" : doorSeen ? "seen" : "hidden")
                + " in " + v(in) + " " + v(inAim) + " " + v(inCheck) + " " + inHow + " pieces " + pieces;
        }

        private static String v(Vec3 p) {
            return String.format(Locale.ROOT, "%.2f %.2f %.2f", p.x, p.y, p.z);
        }
    }

    /** The gallery's town (founded at the first row, kept while it stands), and how many rows have gone up. */
    @Nullable private static UUID town;
    private static int serial;

    // ------------------------------------------------------------------ the command

    /** "gallery list" and "gallery &lt;row&gt; [age]", under /village showcase. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("gallery")
            .then(Commands.literal("list").executes(Gallery::list))
            .then(Commands.argument("row", IntegerArgumentType.integer(0))
                .executes(ctx -> row(ctx, "iron", "plains"))
                .then(Commands.argument("age", StringArgumentType.word())
                    .executes(ctx -> row(ctx, StringArgumentType.getString(ctx, "age"), "plains"))
                    .then(Commands.argument("land", StringArgumentType.word())
                        .executes(ctx -> row(ctx, StringArgumentType.getString(ctx, "age"), StringArgumentType.getString(ctx, "land"))))));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<String> names = drawings(ctx.getSource().getServer());
        int rows = rows(names).size();
        ctx.getSource().sendSuccess(() -> Component.literal("GALLERY " + names.size() + " drawings in " + rows + " rows of "
            + PER_ROW + " | " + String.join(" ", names)), false);
        return names.size();
    }

    private static int row(CommandContext<CommandSourceStack> ctx, String ageWord, String landWord) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Age age = age(ageWord);
        Homeland.Land land = land(landWord);
        int k = IntegerArgumentType.getInteger(ctx, "row");
        List<Shot> shots = row(level, at, k, age, land);
        if (shots.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No row " + k + " in the gallery (see /village showcase gallery list)."));
            return 0;
        }
        List<String> lines = new ArrayList<>();
        for (Shot s : shots) lines.add(s.line());
        ctx.getSource().sendSuccess(() -> Component.literal("ROW " + k + " " + Villages.name(town) + " in " + age.name().toLowerCase(Locale.ROOT)
            + " " + land.name().toLowerCase(Locale.ROOT) + " | " + String.join(" | ", lines)), false);
        return shots.size();
    }

    /** An age by its word ("stone", "iron", ...): the Iron Age for anything else. Never the Wood Age's bare rooms. */
    static Villages.Age age(String word) {
        for (Villages.Age a : Villages.Age.values()) {
            if (a.name().equalsIgnoreCase(word) && a != Villages.Age.WOOD) return a;
        }
        return Villages.Age.IRON;
    }

    /** A land by its word ("plains", "desert", ...): the plains for anything else. */
    static Homeland.Land land(String word) {
        for (Homeland.Land l : Homeland.Land.values()) if (l.name().equalsIgnoreCase(word)) return l;
        return Homeland.Land.PLAINS;
    }

    /** The style a town on this land builds in, as its founders would most likely choose it (Architecture.scores). */
    static Architecture.Style style(Homeland.Land land) {
        return switch (land) {
            case PLAINS, MEADOW -> Architecture.Style.TIMBERED;
            case RIVER, COAST -> Architecture.Style.COASTAL;
            case MOUNTAIN, SNOW -> Architecture.Style.HILL_FORT;
            case DESERT, SAVANNA, BADLANDS -> Architecture.Style.DESERT_COURT;
            case FOREST, TAIGA, JUNGLE, SWAMP -> Architecture.Style.FOREST_LODGE;
        };
    }

    // ------------------------------------------------------------------ the drawings

    /** Every building drawing the mod has (not the machines in their own folder), by name. */
    public static List<String> drawings(@Nullable MinecraftServer server) {
        Set<String> out = new TreeSet<>();
        if (server != null) {
            String dir = "blueprints/";
            for (ResourceLocation rl : server.getResourceManager().listResources("blueprints", r -> r.getPath().endsWith(".txt")).keySet()) {
                if (!rl.getNamespace().equals(McAssistantMod.MODID)) continue;
                String name = rl.getPath().substring(dir.length(), rl.getPath().length() - ".txt".length());
                if (name.indexOf('/') < 0 && Blueprints.has(name)) out.add(name);
            }
        }
        // Should the data not be listed (no server), every building the builders know that has a drawing.
        if (out.isEmpty()) for (String s : BuildGoal.STRUCTURES) if (Blueprints.drawn(s)) out.add(s);
        return new ArrayList<>(out);
    }

    /** The drawings in rows of twelve, in order of name. */
    public static List<List<String>> rows(List<String> names) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i += PER_ROW) out.add(names.subList(i, Math.min(names.size(), i + PER_ROW)));
        return out;
    }

    // ------------------------------------------------------------------ a row

    /**
     * Row {@code k} of the gallery put up on the stage at {@code start} (the west end of the row's line, its ground
     * level), the row before cleared off it; returns a shot a building, or nothing for a row there is not.
     */
    public static List<Shot> row(ServerLevel level, BlockPos start, int k, Villages.Age age) {
        return row(level, start, k, age, Homeland.Land.PLAINS);
    }

    /** The same, the gallery's town a town of this land. */
    public static List<Shot> row(ServerLevel level, BlockPos start, int k, Villages.Age age, Homeland.Land land) {
        List<List<String>> rows = rows(drawings(level.getServer()));
        if (k < 0 || k >= rows.size()) return List.of();
        // The stage is the same for every row: long enough for the longest, deep and high enough for the biggest.
        int longest = 0, deepest = 0, tallest = 0;
        for (List<String> r : rows) longest = Math.max(longest, length(r));
        for (List<String> r : rows) for (String n : r) {
            deepest = Math.max(deepest, Blueprints.fullHalf(n)[1]);
            for (Blueprints.Cell c : Blueprints.cells(n)) tallest = Math.max(tallest, c.h());
        }
        int y = start.getY();
        int x0 = start.getX(), x1 = start.getX() + longest;
        // Each row a block further south than the one before, so no building of it stands on the very ground one of
        // another stood on: the furnishing and the ages keep their work by the ground it was done on.
        int shift = Math.floorMod(serial++, 16);
        int z = start.getZ() + shift;
        int zBack = start.getZ() - deepest - 8, zFront = start.getZ() + 16 + deepest + 12;
        clear(level, x0 - 8, x1 + 8, zBack, zFront, y, y + tallest + 8);
        // Green round them and well out in front, where the camera looks down past the buildings.
        ground(level, x0 - 20, x1 + 20, start.getZ() - deepest - 4, start.getZ() + 16 + deepest + 30, y);

        // The town behind the stage: founded once, its buildings forgotten each row, the age it is asked to be.
        BlockPos heart = new BlockPos((x0 + x1) / 2, y, start.getZ() - deepest - 32);
        Villages.Village v = town(level, heart, land);
        Villages.ageForTests(v.id(), age);
        Ledger.forget(v.id());
        Function<BuildGoal.Placement, BlockState> painter = painter(v.id());

        List<Ledger.Building> built = new ArrayList<>();
        int x = x0;
        for (String name : rows.get(k)) {
            int w = Blueprints.fullHalf(name)[0];
            x += w + GAP / 2;
            BlockPos anchor = new BlockPos(x, y, z);
            BuildGoal.stamp(level, name, anchor, BACK, 13, painter);
            Ledger.built(v.id(), name, anchor, BACK);
            built.add(new Ledger.Building(name, anchor, BACK));
            x += w + GAP / 2 + 1;
        }
        // Made over for the town's age, as the town's own masons would (free here: it is the look that is wanted).
        for (Ledger.Building b : built) {
            if (!AS_BUILT.contains(b.structure())) Ages.now(level, v, b, age);
            // [build-looks] The homes dressed in the town's style, as its dressers come round to them (Architecture).
            if (HOMES.contains(b.structure())) Architecture.dressForTests(level, v, b);
        }
        int[] pieces = new int[built.size()];
        for (int i = 0; i < built.size(); i++) pieces[i] = -furniture(level, built.get(i));
        furnish(level, v);
        for (int i = 0; i < built.size(); i++) pieces[i] += furniture(level, built.get(i));
        TownLife.dressNow(level, v.id(), heart, built, List.of(Items.BREAD, Items.APPLE, Items.CARROT, Items.PUMPKIN));

        List<Shot> out = new ArrayList<>();
        for (int i = 0; i < built.size(); i++) out.add(shoot(level, built.get(i), k, pieces[i]));
        return out;
    }

    /** How long a row of these is, east to west, the gaps between them in. */
    static int length(List<String> names) {
        int n = 0;
        for (String s : names) n += 2 * Blueprints.fullHalf(s)[0] + 1 + GAP;
        return n;
    }

    /** Ages.AS_BUILT, as Ages has it: what a town leaves as it was built (a well, a gateway, a field). */
    static final Set<String> AS_BUILT = Set.of("well", "gateway", "monument", "graveyard", "fortify", "pen", "platform",
        "wall", "column", "room", "fountain", "court", "park", "pitch", "range");

    /** Architecture.HOMES, as Architecture has it: the buildings a town dresses in its style. */
    static final Set<String> HOMES = Set.of("house", "manor", "villa");

    /**
     * The gallery's town: the one founded before, or a new one at {@code heart}, with its founding stores; a town of
     * this land, building in the style its founders would choose there.
     *
     * <p>[build-looks] Its land is set, not surveyed. Looked over, a stage up in the sky reads as the mountains (its
     * green a hundred blocks over the country round it: Homeland.read's thirty-two-block rise), and the first gallery
     * came out a mountain town's from end to end: footings of cobbled deepslate, deepslate brick dressings, a hill
     * fort's style, every building near black under its slates. A typical town is a plains town; any other is asked
     * for. The land's own walling (sandstone in the desert, andesite in the hills) is put in its stores, as a town of
     * that land has it, so its masons can wall its buildings in it (Ages: Masonry.canLand).
     */
    static Villages.Village town(ServerLevel level, BlockPos heart, Homeland.Land land) {
        Villages.Village v = town == null ? null : Villages.get(town);
        if (v == null || !v.dim().equals(level.dimension())) {
            ground(level, heart.getX() - 12, heart.getX() + 12, heart.getZ() - 12, heart.getZ() + 12, heart.getY());
            v = Villages.found(level, heart);
            VillageSpawner.supplyChest(level, heart.relative(Direction.EAST, 2));
            town = v.id();
        }
        if (Homeland.known(v.id()) != land || Architecture.of(v.id()) != style(land)) {
            Homeland.setForTests(v.id(), land);
            Architecture.setForTests(v.id(), style(land));               // forgets its look (Palettes) with it
            Homeland.Stone own = Homeland.walls(v.id());
            if (own != null) Market.intoStoresForTests(level, v.id(), new ItemStack(own.block().asItem(), 64));
        }
        return v;
    }

    /**
     * What a builder of this town lays in a block of each kind: the first of its look's own woods and stones for that
     * part (Palettes, as BuildGoal's builders reach for them); its doors of its walls' wood and its fences and gates of
     * its beams'; anything the look says nothing of as the showcase has it.
     */
    static Function<BuildGoal.Placement, BlockState> painter(UUID village) {
        Function<BuildGoal.Placement, BlockState> plain = Showcase.painter(Showcase.OAK);
        Palettes.Look look = Palettes.of(village);
        Block door = wood(look.walls(), "_door", Showcase.OAK.door());
        Block fence = wood(look.frame(), "_fence", Showcase.OAK.fence());
        Block gate = wood(look.frame(), "_fence_gate", Showcase.OAK.gate());
        return p -> switch (p.part()) {
            case BLOCK -> {
                for (Item it : Palettes.ranked(village, p.style())) {
                    if (it instanceof BlockItem bi) yield bi.getBlock().defaultBlockState();
                }
                yield plain.apply(p);
            }
            case DOOR -> door.defaultBlockState();
            case FENCE -> fence.defaultBlockState();
            case GATE -> gate.defaultBlockState();
            default -> plain.apply(p);
        };
    }

    private static Block wood(String kind, String suffix, Block otherwise) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(kind + suffix));
        return b == Blocks.AIR ? otherwise : b;
    }

    /**
     * The town's furnishing done, all of it at once: the stores filled with what Interiors puts in a room (two colours
     * of carpet for the rug and its border, barrels, shelves, lanterns, candles, pots and flowers), and its hands
     * standing ready (TownJobs, at once), till there is nothing left for it to do.
     */
    static void furnish(ServerLevel level, Villages.Village v) {
        ItemStack[] stock = { new ItemStack(Items.RED_CARPET, 64), new ItemStack(Items.RED_CARPET, 64), new ItemStack(Items.RED_CARPET, 64),
            new ItemStack(Items.YELLOW_CARPET, 64), new ItemStack(Items.BARREL, 48), new ItemStack(Items.BOOKSHELF, 64),
            new ItemStack(Items.LANTERN, 48), new ItemStack(Items.CANDLE, 48), new ItemStack(Items.FLOWER_POT, 48),
            new ItemStack(Items.POPPY, 24), new ItemStack(Items.DANDELION, 24), new ItemStack(Items.CORNFLOWER, 24) };
        for (ItemStack s : stock) Market.intoStoresForTests(level, v.id(), s);
        boolean was = TownJobs.instantNow();
        TownJobs.instantForTests(true);
        try {
            for (int i = 0; i < 12; i++) if (Interiors.work(level, v, 4000) == 0) break;
        } finally {
            TownJobs.instantForTests(was);
        }
    }

    /** The furnishing's kinds of thing standing in a building (rugs, barrels, shelves, lamps, candles, pots). */
    static int furniture(ServerLevel level, Ledger.Building b) {
        int n = 0;
        int[] box = box(b);
        for (BlockPos p : BlockPos.betweenClosed(box[0], box[1], box[2], box[3], box[4], box[5])) {
            BlockState s = level.getBlockState(p);
            if (s.is(BlockTags.WOOL_CARPETS) || s.is(Blocks.BARREL) || s.is(Blocks.BOOKSHELF) || s.is(Blocks.LANTERN)
                || s.is(BlockTags.CANDLES) || s.is(BlockTags.FLOWER_POTS)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ the stage

    /** Everything off the stage (the row before): its blocks, emptied first so nothing spills, and whatever stands on it. */
    static void clear(ServerLevel level, int x0, int x1, int z0, int z1, int y0, int y1) {
        AABB area = new AABB(x0, y0 - 2, z0, x1 + 1, y1 + 1, z1 + 1);
        for (Entity e : level.getEntitiesOfClass(Entity.class, area, e -> !(e instanceof Player))) e.discard();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y1; y >= y0; y--) {                         // from the top: nothing hangs from what is gone
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    if (level.getBlockEntity(p) instanceof Container c) c.clearContent();
                    level.setBlock(p, air, 2 | 16);
                }
            }
        }
    }

    /** A level green: grass over earth, the stage's floor. */
    static void ground(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                level.setBlock(new BlockPos(x, y - 2, z), Blocks.DIRT.defaultBlockState(), 2 | 16);
                level.setBlock(new BlockPos(x, y - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
            }
        }
    }

    // ------------------------------------------------------------------ the cameras

    /** Its drawing's extent in the world: {minX, minY, minZ, maxX, maxY, maxZ}. */
    static int[] box(Ledger.Building b) {
        int[] box = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CLEAR) continue;
            BlockPos q = p.pos();
            box[0] = Math.min(box[0], q.getX()); box[1] = Math.min(box[1], q.getY()); box[2] = Math.min(box[2], q.getZ());
            box[3] = Math.max(box[3], q.getX()); box[4] = Math.max(box[4], q.getY()); box[5] = Math.max(box[5], q.getZ());
        }
        if (box[0] == Integer.MAX_VALUE) {
            BlockPos a = b.anchor();
            return new int[]{ a.getX(), a.getY(), a.getZ(), a.getX(), a.getY(), a.getZ() };
        }
        return box;
    }

    /** The front door: the lowest of its doors, and of those the one nearest the front (a gate if it has no door). */
    @Nullable
    static BlockPos door(Ledger.Building b) {
        Direction front = b.facing().getOpposite();
        BlockPos best = null;
        for (BuildGoal.Part part : new BuildGoal.Part[]{ BuildGoal.Part.DOOR, BuildGoal.Part.GATE }) {
            for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
                if (p.part() != part) continue;
                BlockPos q = p.pos();
                if (best == null || q.getY() < best.getY()
                    || q.getY() == best.getY() && along(q, front) > along(best, front)) best = q;
            }
            if (best != null) return best;
        }
        return null;
    }

    private static int along(BlockPos p, Direction d) {
        return p.getX() * d.getStepX() + p.getZ() * d.getStepZ();
    }

    /** Both of a building's camera spots. */
    static Shot shoot(ServerLevel level, Ledger.Building b, int row, int pieces) {
        int[] box = box(b);
        Vec3 centre = new Vec3((box[0] + box[3] + 1) / 2.0, (box[1] + box[4] + 1) / 2.0, (box[2] + box[5] + 1) / 2.0);
        double w = box[3] - box[0] + 1, h = box[4] - box[1] + 1, d = box[5] - box[2] + 1;
        double radius = 0.5 * Math.sqrt(w * w + h * h + d * d);
        BlockPos door = door(b);

        // Outside: three-quarters on from the front (to the right of the door, else the left, else nearer straight
        // on), looking at the middle of it from far enough back that the whole of it, roof and all, is in the
        // picture (seventy degrees of the client's view up and down: a ball round it of this radius fits at 1.8 of
        // it), the eyes a little above it. The first spot whose eyes are in open air with the door in sight.
        Vec3 doorFace = door == null ? centre : faceOf(door, b.facing().getOpposite());
        double dist = Math.max(10.0, Math.min(70.0, 1.85 * radius + 2.0));
        Vec3 outEye = null, firstOpen = null;
        boolean seen = false;
        search:
        for (double rise : new double[]{ 18, 28, 40 }) {
            for (double turn : new double[]{ 35, -35, 50, -50, 20, -20, 0 }) {
                for (double near : new double[]{ 1.0, 0.75 }) {
                    double a = Math.toRadians(turn), e = Math.toRadians(rise), r = dist * near;
                    Vec3 eye = centre.add(r * Math.sin(a) * Math.cos(e), r * Math.sin(e), r * Math.cos(a) * Math.cos(e));
                    if (eye.y < box[1] + 2.0 || !openEyes(level, eye)) continue;
                    if (firstOpen == null) firstOpen = eye;
                    if (door == null || seesDoor(level, eye, door, doorFace)) {
                        outEye = eye;
                        seen = door != null;
                        break search;
                    }
                }
            }
        }
        if (outEye == null) outEye = firstOpen != null ? firstOpen : centre.add(dist * 0.57, dist * 0.31, dist * 0.82);
        Vec3 outFeet = outEye.subtract(0, CameraCommands.EYES, 0);
        // What /village camera is asked to see from there before the picture (smoke.py's clear_camera): the door when
        // it is in sight, else the spot itself (its eyes in open air), so the camera is never walked off its mark.
        Vec3 outCheck = seen ? doorFace : outEye.add(0, 0.05, 0);

        // Inside: a step or two in from the door, looking into the room, a little down, at whatever is furthest off
        // that it sees; the spot that sees furthest, the first step in if it sees four blocks or more.
        Vec3 inEye = null, inAim = null, inCheck = null;
        String how = "door";
        if (door != null) {
            Direction in = inward(door, centre);
            Direction side = in.getClockWise();
            double best = -1;
            for (int step = 1; step <= 3; step++) {
                for (int off : new int[]{ 0, -1, 1 }) {
                    BlockPos cell = door.relative(in, step).relative(side, off);
                    Vec3 eye = new Vec3(cell.getX() + 0.5, door.getY() + CameraCommands.EYES, cell.getZ() + 0.5);
                    if (!openEyes(level, eye)) continue;
                    // [build-looks] Not right under a lamp hung at head height: from an inch below it a lantern fills
                    // the top of the picture, a dark box with a glowing rim (the fletcher's, the harbour office's).
                    if (underLamp(level, cell.atY(door.getY()))) continue;
                    for (double swing : new double[]{ 0, -30, 30 }) {
                        Vec3 look = turn(Vec3.atLowerCornerOf(in.getNormal()), swing);
                        Vec3 far = eye.add(look.scale(Math.max(d, w) + 2)).add(0, -0.8, 0);
                        Vec3 hit = reach(level, eye, far);
                        double seenFar = hit.distanceTo(eye);
                        double score = seenFar - (step - 1) * 0.5 - Math.abs(off) * 0.25 - Math.abs(swing) / 60.0;
                        if (score > best) {
                            best = score;
                            inEye = eye;
                            inAim = far;
                            inCheck = eye.add(hit.subtract(eye).scale(Math.max(0.0, (seenFar - 0.3) / Math.max(seenFar, 1.0E-3))));
                        }
                    }
                    if (step == 1 && off == 0 && best >= 4.0) break;
                }
                if (best >= 4.0) break;
            }
            if (inEye == null) how = "above";
            else if (inEye.distanceTo(inCheck) < 2.0) how = "cramped";                  // a store room full to the door: as near as it gets
        } else {
            // No door (a well, a field, a stage, a yard): from its open front, as far in as there is open air, looking
            // to the back of it.
            how = "open";
            for (int zz = box[5]; zz >= (int) Math.floor(centre.z); zz--) {
                Vec3 eye = new Vec3(centre.x, box[1] + CameraCommands.EYES, zz + 0.5);
                if (!openEyes(level, eye)) continue;
                Vec3 far = new Vec3(centre.x, box[1] + 0.8, box[2]);
                Vec3 hit = reach(level, eye, far);
                if (hit.distanceTo(eye) < 2.5) continue;
                inEye = eye;
                inAim = far;
                inCheck = eye.add(hit.subtract(eye).scale(Math.max(0.0, (hit.distanceTo(eye) - 0.3) / hit.distanceTo(eye))));
                break;
            }
            if (inEye == null) how = "above";
        }
        if (inEye == null) {
            // Nowhere on the ground to stand: looking down into it from over its front.
            inEye = new Vec3(centre.x, box[4] + 4.0, box[5] + 3.5);
            inAim = centre;
            inCheck = inEye.add(0, 0.05, 0);
        }
        return new Shot(b.structure(), row, b.anchor(), door, outFeet, centre, outCheck, seen,
            inEye.subtract(0, CameraCommands.EYES, 0), inAim, inCheck, how, pieces);
    }

    /** A lantern hung (or standing) just over a camera standing here: in the two blocks over its head. */
    static boolean underLamp(ServerLevel level, BlockPos feet) {
        for (int up = 2; up <= 3; up++) {
            BlockState s = level.getBlockState(feet.above(up));
            if (s.is(Blocks.LANTERN) || s.is(Blocks.SOUL_LANTERN)) return true;
        }
        return false;
    }

    /** The middle of a door's outer face, halfway up its two halves. */
    static Vec3 faceOf(BlockPos door, Direction out) {
        return new Vec3(door.getX() + 0.5 + 0.45 * out.getStepX(), door.getY() + 1.0, door.getZ() + 0.5 + 0.45 * out.getStepZ());
    }

    /** Which way is into the building from its door: toward its middle, along whichever line is longer. */
    static Direction inward(BlockPos door, Vec3 centre) {
        double dx = centre.x - (door.getX() + 0.5), dz = centre.z - (door.getZ() + 0.5);
        return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    private static Vec3 turn(Vec3 v, double degrees) {
        double a = Math.toRadians(degrees), c = Math.cos(a), s = Math.sin(a);
        return new Vec3(v.x * c - v.z * s, v.y, v.x * s + v.z * c);
    }

    /** Eyes in open air (CameraCommands' own test, the eyes' cell and the edge of the picture round them). */
    static boolean openEyes(ServerLevel level, Vec3 eye) {
        Vec3 feet = eye.subtract(0, CameraCommands.EYES, 0);
        return CameraCommands.sees(level, feet, eye.add(0, 0.05, 0));
    }

    /** How far a look from {@code eye} toward {@code far} gets before it meets something: the point it stops. */
    static Vec3 reach(ServerLevel level, Vec3 eye, Vec3 far) {
        HitResult hit = level.clip(new ClipContext(eye, far, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS ? far : hit.getLocation();
    }

    /** Is the door in plain sight from these eyes: the eyes in open air, and a look at its face meets the door itself? */
    static boolean seesDoor(ServerLevel level, Vec3 eye, BlockPos door, Vec3 face) {
        if (!openEyes(level, eye)) return false;
        HitResult hit = level.clip(new ClipContext(eye, face, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return true;
        BlockPos at = ((BlockHitResult) hit).getBlockPos();
        return at.equals(door) || at.equals(door.above()) || hit.getLocation().distanceTo(face) <= 0.75;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the gallery's town forgotten (Kit.reset forgets every village). */
    public static void resetForTests() {
        town = null;
        serial = 0;
    }

    /** Tests: the gallery's town, once a row has gone up. */
    @Nullable
    public static UUID townForTests() {
        return town;
    }
}
