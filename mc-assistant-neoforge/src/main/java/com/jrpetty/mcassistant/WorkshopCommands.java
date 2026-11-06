package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Bench;
import com.jrpetty.mcassistant.entity.RecipeBook;
import com.jrpetty.mcassistant.entity.Tiers;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Workshop;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /village workshop — the shop's workshop (Workshop): its makers, what they made today and of what, its
 * order book against the stock, and what the village's age lets it make and the next will.
 * <ul>
 * <li>{@code /village workshop} — the report;</li>
 * <li>{@code /village workshop blueprints [word]} — the blueprints the makers know (every recipe in the
 *     game), how many the age opens, and the ones a word names with the age each belongs to;</li>
 * <li>{@code /village workshop order <item> [count]} — put a thing on the workshop's order book for you;</li>
 * <li>{@code /village workshop books} — the town's books opened at the shop's workshop;</li>
 * <li>{@code /village workshop hire}, {@code work} and {@code stage} (ops, the client smoke) — the nearest grown
 *     folk taken on as a hand now; every maker does a piece of work now; a shop put up by the spot if the
 *     village has none, with a keeper and a hand at work in it.</li>
 * </ul>
 */
public final class WorkshopCommands {

    private WorkshopCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("workshop")
            .executes(WorkshopCommands::report)
            .then(Commands.literal("blueprints").executes(ctx -> blueprints(ctx, ""))
                .then(Commands.argument("word", StringArgumentType.greedyString())
                    .executes(ctx -> blueprints(ctx, StringArgumentType.getString(ctx, "word")))))
            .then(Commands.literal("order")
                .then(Commands.argument("item", ResourceLocationArgument.id())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggestResource(craftable(ctx.getSource().getLevel()), b))
                    .executes(ctx -> order(ctx, 1))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
                        .executes(ctx -> order(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
            .then(Commands.literal("books").executes(WorkshopCommands::books))
            .then(Commands.literal("hire").requires(src -> src.hasPermission(2)).executes(WorkshopCommands::hire))
            .then(Commands.literal("work").requires(src -> src.hasPermission(2)).executes(WorkshopCommands::work))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(WorkshopCommands::stage));
    }

    /** The village the command means: the nearest, or the first there is. */
    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static List<ResourceLocation> craftable(ServerLevel level) {
        List<ResourceLocation> out = new ArrayList<>();
        for (Item it : Tiers.blueprints(level).keySet()) out.add(BuiltInRegistries.ITEM.getKey(it));
        return out;
    }

    private static int report(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        String text = Villages.name(v.id()) + " — the shop's workshop\n" + Workshop.page(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int blueprints(CommandContext<CommandSourceStack> ctx, String word) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Age age = Villages.ageOf(v.id());
        int[] counts = Tiers.counts(level, age);
        StringBuilder sb = new StringBuilder(Villages.name(v.id()) + "'s makers know " + counts[0] + " blueprints (every recipe in the game"
            + " and its mods); " + age.label + " lets them make " + counts[1] + " of them.\n");
        String w = word.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (w.isEmpty()) {
            for (String line : Tiers.rule()) sb.append("  ").append(line).append('\n');
            List<String> next = Tiers.nextAge(level, age, 8);
            if (!next.isEmpty()) {
                sb.append("The next age will let them make ").append(String.join(", ", next)).append(" and ")
                    .append(Math.max(0, Tiers.nextAgeCount(level, age) - next.size())).append(" more.");
            }
        } else {
            int shown = 0;
            for (Map.Entry<Item, Villages.Age> e : Tiers.blueprints(level).entrySet()) {
                String path = BuiltInRegistries.ITEM.getKey(e.getKey()).getPath();
                if (!path.contains(w)) continue;
                if (shown++ >= 12) { sb.append("  …and more\n"); break; }
                RecipeBook.Way way = RecipeBook.waysFor(level, e.getKey()).get(0);
                List<String> parts = new ArrayList<>();
                for (RecipeBook.Part p : way.parts()) parts.add(p.count() + " " + p.label());
                boolean open = e.getValue().ordinal() <= age.ordinal();
                sb.append("  ").append(new ItemStack(e.getKey()).getHoverName().getString()).append(" — ").append(e.getValue().label)
                    .append(open ? "" : " (not yet)").append(": ").append(String.join(", ", parts))
                    .append(way.fire() == RecipeBook.Fire.NONE ? "" : ", " + new Bench.Step(e.getKey(), 1, way.yield(), way.fire(), false).where())
                    .append('\n');
            }
            if (shown == 0) sb.append("  No blueprint is called anything like \"").append(word).append("\".");
        }
        String text = sb.toString().stripTrailing();
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int order(CommandContext<CommandSourceStack> ctx, int count) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "item");
        Item it = BuiltInRegistries.ITEM.get(id);
        if (it == Items.AIR) {
            ctx.getSource().sendFailure(Component.literal("There's no such thing as " + id + "."));
            return 0;
        }
        String who = ctx.getSource().getPlayer() != null ? ctx.getSource().getPlayer().getName().getString() : ctx.getSource().getTextName();
        String said = Workshop.order(ctx.getSource().getLevel(), v, it, count, who);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return said.startsWith("On the workshop's book") ? 1 : 0;
    }

    /** A hand taken on now: "HAND name x y z" (the client smoke reads it). */
    private static int hire(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        VillageFolkEntity f = Workshop.hireNow(level, v, BlockPos.containing(ctx.getSource().getPosition()));
        if (f == null) {
            ctx.getSource().sendFailure(Component.literal("No shop in " + Villages.name(v.id()) + ", or nobody grown to take on."));
            return 0;
        }
        String text = "HAND " + f.displayNameCap() + " " + f.getBlockX() + " " + f.getBlockY() + " " + f.getBlockZ()
            + (Workshop.isHand(f) ? " (a hand at the shop's bench)" : " (keeps the shop)");
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** The town's books, opened at the Shops page's workshop (as clicking the board and the tab does). */
    private static int books(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer p)) {
            ctx.getSource().sendFailure(Component.literal("Only a player can open the books."));
            return 0;
        }
        net.minecraft.nbt.CompoundTag books = com.jrpetty.mcassistant.entity.Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putInt("tab", 4);                                     // the Shops page
        books.putString("shopSeller", "workshop");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    /** The client smoke's scene: "WORKSHOP", "DOOR", "BENCH" and "HAND" lines, then what each maker made. */
    private static int stage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        List<String> lines = Workshop.stage(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition()));
        String text = lines.isEmpty() ? "No shop could be put up there." : String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    /** Every maker at the shop's bench does a piece of work now. */
    private static int work(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        List<String> made = Workshop.workNow(ctx.getSource().getLevel(), v);
        String text = made.isEmpty() ? "Nobody at the shop's bench, or nothing it can make just now." : String.join("\n", made);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return made.size();
    }
}
