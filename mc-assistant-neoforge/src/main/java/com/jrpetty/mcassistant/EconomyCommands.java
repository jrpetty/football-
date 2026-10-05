package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Fuel;
import com.jrpetty.mcassistant.entity.Larder;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Strays;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /village economy — the larder, the fuel and the builders' stock (entity/Larder, Fuel, Strays).
 *
 * <pre>
 *   /village economy            the nearest village: food grown a day against what its mouths eat, and
 *                               whether a child may be raised and why; coal and charcoal in the stores
 *                               against the floor it keeps, and whether charcoal is wanted; who carries
 *                               the builders' stock about; and what its dead died of
 *   /village economy charcoal   (ops) its smelter burns logs into charcoal now, if the village wants it;
 *                               says where the smelter stands
 * </pre>
 */
public final class EconomyCommands {

    private EconomyCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("economy")
            .executes(EconomyCommands::status)
            .then(Commands.literal("charcoal").requires(src -> src.hasPermission(2)).executes(EconomyCommands::charcoal));
    }

    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        return Villages.nearest(ctx.getSource().getLevel(), at, Villages.VILLAGE_RANGE * 2);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        UUID id = v.id();
        int folk = Villages.headcount(id);
        int food = Villages.stock(level, v.centre(), Villages.Task.FOOD, Villages.storesRadius(id));
        List<String> lines = new ArrayList<>();
        lines.add("ECONOMY " + Villages.name(id) + ": " + folk + " folk, " + Villages.ageOf(id).label + ", food in the stores " + food);
        String books = Larder.line(id);
        lines.add("Larder: " + (books.isEmpty() ? "no books kept yet" : books) + "; the leader's plan: " + Leader.plan(id).word
            + "; a child: " + Larder.word(id, food) + ".");
        lines.add("Fuel: " + Fuel.inStores(level, id) + " coal and charcoal in the stores, " + Fuel.floor(folk) + " kept whatever the age"
            + (Fuel.low(level, id) ? " (low: the smelter saves coal and burns wood)" : "")
            + "; charcoal " + (Fuel.charcoalWanted(level, id) ? "wanted" : "not wanted") + ".");
        List<String> carriers = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            int n = Strays.carried(f);
            if (n > 0) carriers.add(f.displayNameCap() + " the " + f.stationTask().title.toLowerCase(java.util.Locale.ROOT) + " " + n);
        }
        lines.add("Builders' stock carried about: " + (carriers.isEmpty() ? "none" : String.join(", ", carriers.subList(0, Math.min(8, carriers.size())))) + ".");
        Map<String, Integer> causes = new LinkedHashMap<>();
        for (Ledger.Grave g : Ledger.graves(id)) causes.merge(g.cause(), 1, Integer::sum);
        lines.add("The dead: " + (causes.isEmpty() ? "none" : causes.toString()) + ".");
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    private static int charcoal(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.SMELT) continue;
            boolean burning = f.burnCharcoal();
            BlockPos p = f.blockPosition();
            String line = "SMELTER " + f.displayNameCap() + " " + p.getX() + " " + p.getY() + " " + p.getZ() + ": "
                + (burning ? "burning logs into charcoal" : "not now (charcoal "
                    + (Fuel.charcoalWanted(ctx.getSource().getLevel(), v.id()) ? "wanted, but no logs to spare or looked a moment ago" : "not wanted") + ")");
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            return burning ? 1 : 0;
        }
        ctx.getSource().sendFailure(Component.literal("The village has no smelter."));
        return 0;
    }
}
