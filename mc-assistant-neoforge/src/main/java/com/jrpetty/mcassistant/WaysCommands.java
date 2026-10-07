package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Architecture;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Beliefs;
import com.jrpetty.mcassistant.entity.Cuisine;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.TownFeast;
import com.jrpetty.mcassistant.entity.TownSpeech;
import com.jrpetty.mcassistant.entity.TownWays;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.DishItem;
import com.jrpetty.mcassistant.item.DishItems;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /village ways [culture2] — the town's own ways (entity/TownWays): its dish, its tongue, its building style, its
 * festival and its faith.
 *
 * <pre>
 *   /village ways                 the nearest village's ways, all of it: where each came from and what it does
 *   /village ways now             (ops) its ways worked out now, its houses dressed in its style (out of the stores, a
 *                                 house a turn), its shrine hung and its tavern's board put up
 *   /village ways set &lt;what&gt; &lt;name&gt;   (ops) its style, faith, festival or dish set so (style HILL_FORT, belief SEA,
 *                                 feast HERRING_FAIR, dish FISH_STEW)
 *   /village ways feast           (ops) its own festival called now on the square, whatever the day
 *   /village ways speak           (ops) the folk nearest you say hello their town's way, and one of its sayings
 *   /village ways stage           (ops) a street of six houses where you stand, one in each style, and a wall of the
 *                                 eight dishes in frames; says where to look from (VIEW lines)
 * </pre>
 */
public final class WaysCommands {

    private WaysCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("ways")
            .executes(WaysCommands::status)
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(WaysCommands::now))
            .then(Commands.literal("feast").requires(src -> src.hasPermission(2)).executes(WaysCommands::feast))
            .then(Commands.literal("speak").requires(src -> src.hasPermission(2)).executes(WaysCommands::speak))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(WaysCommands::stage))
            .then(Commands.literal("set").requires(src -> src.hasPermission(2))
                .then(Commands.argument("what", StringArgumentType.word())
                    .then(Commands.argument("name", StringArgumentType.word()).executes(WaysCommands::set))));
    }

    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        return Villages.nearest(ctx.getSource().getLevel(), at, Villages.VILLAGE_RANGE * 2);
    }

    private static int none(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.literal("No village near enough."));
        return 0;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return none(ctx);
        List<String> lines = TownWays.status(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    private static int now(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return none(ctx);
        ServerLevel level = ctx.getSource().getLevel();
        TownWays.workOutForTests(level, v);
        int dressed = 0;
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (b.structure().equals("house") && level.isLoaded(b.anchor())) dressed += Architecture.dress(level, v, b, 64, false);
        }
        boolean shrine = Beliefs.shrineForTests(level, v);
        boolean sign = Cuisine.tavernSign(level, v, false);
        int fd = dressed;
        ctx.getSource().sendSuccess(() -> Component.literal("WAYS " + Villages.name(v.id()) + ": " + TownWays.summary(v.id())
            + "; " + fd + " blocks dressed; shrine " + (shrine ? "hung" : "not yet") + "; tavern board " + (sign ? "up" : "not yet")), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return none(ctx);
        String what = StringArgumentType.getString(ctx, "what").toLowerCase(Locale.ROOT);
        String name = StringArgumentType.getString(ctx, "name").toUpperCase(Locale.ROOT);
        UUID id = v.id();
        try {
            switch (what) {
                case "style" -> Architecture.setForTests(id, Architecture.Style.valueOf(name));
                case "belief", "faith" -> Beliefs.setForTests(id, Beliefs.Belief.valueOf(name));
                case "feast", "festival" -> TownFeast.setForTests(id, TownFeast.Feast.valueOf(name));
                case "dish" -> Cuisine.setForTests(id, Cuisine.Dish.valueOf(name), null);
                default -> {
                    ctx.getSource().sendFailure(Component.literal("Set the style, the belief, the feast or the dish."));
                    return 0;
                }
            }
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("No such " + what + ": " + name));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("WAYS " + Villages.name(id) + ": " + what + " " + name), true);
        return 1;
    }

    private static int feast(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return none(ctx);
        ServerLevel level = ctx.getSource().getLevel();
        if (TownFeast.of(v.id()) == null) TownFeast.chooseForTests(level, v);
        int out = TownFeast.setOutForTests(level, v);
        boolean began = TownFeast.callNow(level, v);
        TownFeast.Feast f = TownFeast.of(v.id());
        ctx.getSource().sendSuccess(() -> Component.literal("FEAST " + (f == null ? "none" : f.words) + ": " + out + " decorations out; "
            + (began ? "the town gathers on the square" : "it could not begin (nobody free, or something else on)")), false);
        return began ? 1 : 0;
    }

    private static int speak(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        List<VillageFolkEntity> near = level.getEntitiesOfClass(VillageFolkEntity.class, new net.minecraft.world.phys.AABB(at).inflate(24),
            f -> f.isAlive() && !f.isBaby() && f.ownerId() != null);
        near.sort((a, b) -> Double.compare(a.distanceToSqr(at.getX(), at.getY(), at.getZ()), b.distanceToSqr(at.getX(), at.getY(), at.getZ())));
        List<String> said = new ArrayList<>();
        for (int i = 0; i < Math.min(3, near.size()); i++) {
            VillageFolkEntity f = near.get(i);
            UUID id = f.ownerId();
            String s = i == 1 ? TownSpeech.temperGreeting(id) + "! " : TownSpeech.greeting(id) + "! ";
            String saying = TownSpeech.saying(id, f.getRandom());
            if (saying != null) s += "As we say here: " + saying + ".";
            if (i == 2) s = "Is there " + (Cuisine.dishOf(id) == null ? "supper" : Cuisine.dishOf(id).words) + " at the feast? It's at the stores, I'm told.";
            FolkTalk.speak(f, s);
            said.add(f.displayNameCap() + ": " + TownSpeech.local(f, s));
        }
        if (!near.isEmpty()) {
            VillageFolkEntity f = near.get(0);
            said.add("VIEW ways-speak " + (int) Math.floor(f.getX() + 3) + " " + (int) Math.floor(f.getEyeY()) + " " + (int) Math.floor(f.getZ() + 3)
                + " " + (int) Math.floor(f.getX()) + " " + (int) Math.floor(f.getEyeY()) + " " + (int) Math.floor(f.getZ()));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(said.isEmpty() ? "Nobody near enough to speak." : String.join(" | ", said)), false);
        return said.size();
    }

    /** A street of the six styles, side by side, and the dishes on a wall: for the smoke test's pictures. */
    private static int stage(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, at, 300);
        UUID id = v == null ? new UUID(0, 1) : v.id();
        Architecture.Style[] styles = Architecture.Style.values();
        List<String> out = new ArrayList<>();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        for (int i = 0; i < styles.length; i++) {
            BlockPos anchor = new BlockPos(at.getX() + i * 12, y, at.getZ());
            // Level ground for it, clear air over it.
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -7; dz <= 7; dz++) {
                    for (int dy = -2; dy < 0; dy++) level.setBlock(anchor.offset(dx, dy, dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    for (int dy = 0; dy < 12; dy++) level.setBlock(anchor.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
            BuildGoal.stamp(level, "house", anchor, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.Building b = new Ledger.Building("house", anchor, Direction.NORTH);
            int n = Architecture.dressAs(level, id, b, styles[i], Villages.Age.IRON);
            out.add(styles[i].words + " " + n);
        }
        // The front of the street from across the way, and two close views.
        int mid = at.getX() + (styles.length - 1) * 6;
        out.add("VIEW ways-street " + mid + " " + (y + 9) + " " + (at.getZ() + 26) + " " + mid + " " + (y + 3) + " " + at.getZ());
        out.add("VIEW ways-fort-coast " + (at.getX() + 6) + " " + (y + 4) + " " + (at.getZ() + 14) + " " + (at.getX() + 6) + " " + (y + 3) + " " + at.getZ());
        out.add("VIEW ways-lodge-civic " + (at.getX() + 54) + " " + (y + 4) + " " + (at.getZ() + 14) + " " + (at.getX() + 54) + " " + (y + 3) + " " + at.getZ());
        // The dishes on a wall of stone bricks behind the street, in frames, facing away from it.
        int wy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ() - 22);
        BlockPos wall = new BlockPos(at.getX(), wy, at.getZ() - 22);
        List<DeferredItem<DishItem>> dishes = DishItems.all();
        for (int i = 0; i < dishes.size() + 2; i++) {
            for (int h = 0; h < 3; h++) level.setBlock(wall.offset(i, h, 0), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            for (int h = 0; h < 4; h++) for (int d = 1; d <= 6; d++) level.setBlock(wall.offset(i, h, -d), Blocks.AIR.defaultBlockState(), 2);
        }
        for (int i = 0; i < dishes.size(); i++) {
            BlockPos f = wall.offset(i + 1, 1, -1);
            ItemFrame frame = new ItemFrame(level, f, Direction.NORTH);
            frame.setItem(new ItemStack(dishes.get(i).get()), false);
            level.addFreshEntity(frame);
        }
        out.add("VIEW ways-dishes " + (wall.getX() + 5) + " " + (wy + 2) + " " + (wall.getZ() - 5) + " " + (wall.getX() + 5) + " " + (wy + 1) + " " + wall.getZ());
        ctx.getSource().sendSuccess(() -> Component.literal(String.join(" | ", out)), false);
        return out.size();
    }
}
