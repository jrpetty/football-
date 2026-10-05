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
 *   /village economy fields     its farmers' fields: where each lies, how fast it grows and why, how
 *                               ripe it was at the farmer's last look, and the growth ticks it was given
 * </pre>
 */
public final class EconomyCommands {

    private EconomyCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("economy")
            .executes(EconomyCommands::status)
            .then(Commands.literal("charcoal").requires(src -> src.hasPermission(2)).executes(EconomyCommands::charcoal))
            .then(Commands.literal("fields").executes(EconomyCommands::fields));
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
        lines.add("Put away: " + com.jrpetty.mcassistant.entity.PutAway.line(id, level.getDayTime() / 24000L) + ".");
        // The food in by where it came from, the fields' pace and each farmer's care, the week's deaths and the watch.
        String foodIn = Larder.inLine(id);
        lines.add("Food in: " + (foodIn == null ? "no day closed yet" : foodIn.substring(1)) + " Today so far: " + Larder.todayLine(id) + ".");
        List<String> care = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == AssistantEntity.StationTask.FARM && care.size() < 6) {
                String c = com.jrpetty.mcassistant.entity.Fields.careLine(f);
                if (c != null) care.add(f.displayNameCap() + ": " + c);
            }
        }
        lines.add("Fields: " + com.jrpetty.mcassistant.entity.Fields.word(id) + (care.isEmpty() ? "" : "; " + String.join("; ", care)) + ".");
        String dead = com.jrpetty.mcassistant.entity.Mishap.line(id, level.getDayTime() / 24000L - 6, "over the last 7 days");
        lines.add("Deaths: " + (dead == null ? "none in the last 7 days" : dead.substring(1, dead.length() - 1))
            + "; the watch wanted: at least " + String.format(java.util.Locale.ROOT, "%.1f",
                com.jrpetty.mcassistant.entity.Mishap.watch(id, 0.0, folk, level.getGameTime())) + " guards.");
        lines.add("Builders' stock carried about: " + (carriers.isEmpty() ? "none" : String.join(", ", carriers.subList(0, Math.min(8, carriers.size())))) + ".");
        Map<String, Integer> causes = new LinkedHashMap<>();
        for (Ledger.Grave g : Ledger.graves(id)) causes.merge(g.cause(), 1, Integer::sum);
        lines.add("The dead: " + (causes.isEmpty() ? "none" : causes.toString()) + ".");
        // The last few by name, trade and day: what kills folk, and at what work (a miner in lava, a woodcutter in a fall).
        List<Ledger.Grave> all = Ledger.graves(id);
        List<String> last = new ArrayList<>();
        for (int i = Math.max(0, all.size() - 8); i < all.size(); i++) {
            Ledger.Grave g = all.get(i);
            last.add(g.name() + (g.trade() == null || g.trade().isEmpty() ? "" : " the " + g.trade().toLowerCase(java.util.Locale.ROOT))
                + ", day " + g.died() + ", " + g.cause());
        }
        if (!last.isEmpty()) lines.add("Lately: " + String.join("; ", last) + ".");
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
            // Where it works (its furnaces), not wherever it happens to be standing: a smelter off down the
            // mine for ore put the smoke's camera inside the hill.
            BlockPos p = f.workZone() != null ? f.workZone().center() : f.blockPosition();
            // And the furnace itself if one stands near (the middle of its ground can be the founders' camp).
            BlockPos furnace = nearestFurnace(ctx.getSource().getLevel(), p, 10);
            if (furnace != null) p = furnace;
            String line = "SMELTER " + f.displayNameCap() + " " + p.getX() + " " + p.getY() + " " + p.getZ() + ": "
                + (burning ? "burning logs into charcoal" : "not now (charcoal "
                    + (Fuel.charcoalWanted(ctx.getSource().getLevel(), v.id()) ? "wanted, but no logs to spare or looked a moment ago" : "not wanted") + ")");
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            return burning ? 1 : 0;
        }
        ctx.getSource().sendFailure(Component.literal("The village has no smelter."));
        return 0;
    }

    /** One line a farmer: "FIELD Name x y z r4: its field grows at 3.25x, well watered; ripe 20%; 2 growth ticks a
     *  second; tilled 40 at x y z" (the middle of its farmland, which is seldom the middle of its plot). */
    private static int fields(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        List<String> lines = new ArrayList<>();
        lines.add("FIELDS " + Villages.name(v.id()) + ": " + com.jrpetty.mcassistant.entity.Fields.word(v.id()));
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.FARM || f.workZone() == null) continue;
            BlockPos c = f.workZone().center();
            lines.add("FIELD " + f.displayNameCap() + " " + c.getX() + " " + c.getY() + " " + c.getZ() + " r" + f.workZone().radius() + ": "
                + com.jrpetty.mcassistant.entity.Fields.careLine(f) + "; ripe " + f.ripePercentNow() + "%; "
                + com.jrpetty.mcassistant.entity.Fields.picksForTests(f) + " growth ticks a second; "
                + tilled(ctx.getSource().getLevel(), c, f.workZone().radius()));
            if (lines.size() > 12) break;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size() - 1;
    }

    /** The furnace (or blast furnace or smoker) nearest this spot within r, or null. */
    @org.jetbrains.annotations.Nullable
    private static BlockPos nearestFurnace(net.minecraft.server.level.ServerLevel level, BlockPos c, int r) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-r, -4, -r), c.offset(r, 4, r))) {
            if (!level.isLoaded(q)) continue;
            var st = level.getBlockState(q);
            if (!st.is(net.minecraft.world.level.block.Blocks.FURNACE) && !st.is(net.minecraft.world.level.block.Blocks.BLAST_FURNACE)
                    && !st.is(net.minecraft.world.level.block.Blocks.SMOKER)) continue;
            double d = q.distSqr(c);
            if (d < bd) { bd = d; best = q.immutable(); }
        }
        return best;
    }

    /** "tilled N at x y z": the farmland in a plot, and the middle of it. */
    private static String tilled(net.minecraft.server.level.ServerLevel level, BlockPos c, int r) {
        int n = 0;
        long sx = 0, sy = 0, sz = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -4, -r), c.offset(r, 4, r))) {
            if (!level.isLoaded(p) || !level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.FARMLAND)) continue;
            n++;
            sx += p.getX();
            sy += p.getY();
            sz += p.getZ();
        }
        return n == 0 ? "tilled 0" : "tilled " + n + " at " + (sx / n) + " " + (sy / n) + " " + (sz / n);
    }
}
