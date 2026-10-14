package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Renovation;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import com.jrpetty.mcassistant.entity.Themes;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.ThemeData;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * [styles] /village theme — the nearest town's look (entity/Themes) and its renovation (entity/Renovation).
 *
 * <pre>
 *   /village theme               the town's theme, the themes on offer, and its buildings waiting to be renovated
 *   /village theme set &lt;id&gt;      (ops) give the town a theme; its builders take to it at once and renovate the rest
 *   /village theme stage         (ops) the pictures' scene where it is run: an old makeshift house of the town's, earth
 *                                and gold in its walls, a gap in its roof, a pillar left inside (VIEW lines say where to
 *                                look from)
 *   /village theme stage renovate  (ops) that house renovated now, for nothing, as the builders would
 * </pre>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class ThemeCommands {

    private ThemeCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("theme").executes(ThemeCommands::status)
                .then(Commands.literal("set").requires(src -> src.hasPermission(2))
                    .then(Commands.argument("theme", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(offeredIds(), b))
                        .executes(ThemeCommands::set)))
                .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ThemeCommands::stage)
                    .then(Commands.literal("renovate").executes(ThemeCommands::stageRenovate)))));
    }

    private static List<String> offeredIds() {
        List<String> out = new ArrayList<>();
        for (ThemeData t : Themes.offered()) out.add(t.id);
        return out;
    }

    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        return Villages.nearest(ctx.getSource().getLevel(), here, Villages.VILLAGE_RANGE * 4);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near here."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        List<String> lines = new ArrayList<>(Renovation.lines(level, v));
        List<String> offered = new ArrayList<>();
        for (ThemeData t : Themes.offered()) offered.add(t.id + " (" + t.about() + ")");
        lines.add("Themes on offer: " + String.join(", ", offered) + ".");
        String text = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    private static int set(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near here."));
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "theme");
        ThemeData t = Themes.get(id);
        if (t == null || !t.selectable()) {
            ctx.getSource().sendFailure(Component.literal("No theme \"" + id + "\" on offer. Try: " + String.join(", ", offeredIds()) + "."));
            return 0;
        }
        if (Themes.idOf(v.id()).equals(id)) {
            ctx.getSource().sendSuccess(() -> Component.literal(Villages.name(v.id()) + " already builds in the " + t.name() + " look."), false);
            return 1;
        }
        Themes.set(v.id(), id);
        ServerLevel level = ctx.getSource().getLevel();
        Villages.tell(v.id(), level.getDayTime() / 24000L, Villages.name(v.id()) + " took to the " + t.name()
            + " look: its builders will renovate its older buildings to it, a few at a time");
        ctx.getSource().sendSuccess(() -> Component.literal(Villages.name(v.id()) + " now builds in the " + t.name()
            + " look; its older buildings will be renovated to it."), true);
        return 1;
    }

    /** The stage's house, once laid (the renovate step finds it). */
    private static BlockPos staged;

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near here."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        java.util.List<BuildGoal.Placement> plan = BuildGoal.plan("house", at, Direction.NORTH, 13);
        BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        // An older town's makeshift: earth and a block of gold in the walls, a gap in the roof, a pillar left inside.
        int walls = 0, roof = 0;
        for (BuildGoal.Placement p : plan) {
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            if (p.style() == Blueprints.Style.WALL && p.pos().getY() == at.getY() + 2 && walls < 4) {
                level.setBlock(p.pos(), (walls++ == 1 ? Blocks.GOLD_BLOCK : Blocks.DIRT).defaultBlockState(), 3);
            }
            if (p.style() == Blueprints.Style.ROOF_STAIR && p.pos().getY() == at.getY() + 4 && roof++ < 4) {
                level.setBlock(p.pos(), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        level.setBlock(at.offset(0, 0, 0), Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(at.offset(0, 1, 0), Blocks.COBBLESTONE.defaultBlockState(), 3);
        Ledger.built(v.id(), "house", at, Direction.NORTH);
        long day = level.getDayTime() / 24000L;
        Renovation.Stamp now = Renovation.current(v.id(), new Ledger.Building("house", at, Direction.NORTH), day);
        Renovation.stampAs(v.id(), at, new Renovation.Stamp(now.version() ^ 1L, "house", now.theme(), now.age(), now.style(), day - 30));
        staged = at.immutable();
        String text = "THEME STAGE | an old house at " + at.toShortString()
            + " | VIEW house " + (at.getX() - 9) + " " + (at.getY() + 6) + " " + (at.getZ() + 12) + " " + at.getX() + " " + (at.getY() + 3) + " " + at.getZ()
            + " | VIEW inside " + (at.getX() + 2) + " " + (at.getY() + 1) + " " + (at.getZ() - 2) + " " + at.getX() + " " + (at.getY() + 1) + " " + (at.getZ() + 2);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int stageRenovate(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null || staged == null) {
            ctx.getSource().sendFailure(Component.literal("No staged house: run /village theme stage first."));
            return 0;
        }
        int n = Renovation.renovateNow(ctx.getSource().getLevel(), v, new Ledger.Building("house", staged, Direction.NORTH));
        ctx.getSource().sendSuccess(() -> Component.literal("THEME STAGE | renovated: " + n + " blocks"), false);
        return 1;
    }
}
