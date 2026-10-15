package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.CivicLand;
import com.jrpetty.mcassistant.entity.KnightManors;
import com.jrpetty.mcassistant.entity.Knights;
import com.jrpetty.mcassistant.entity.PlayerLeader;
import com.jrpetty.mcassistant.entity.Retinue;
import com.jrpetty.mcassistant.entity.TaxBook;
import com.jrpetty.mcassistant.entity.TaxUnrest;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * [civic-land] The taxes and the knighthood, by command (the same as saying it to any folk, or the buttons on the books'
 * Knights page).
 * <pre>
 *   /village taxes                          the town's taxes, what you owe, how the town takes them
 *   /village taxes pay                      pay what you owe the town you stand in
 *   /village taxes book | knight book       the town's books open at the Taxes page, or the Knights page
 *   /village taxes urge lower|raise         urge the council (the councillors who think well of you hear it)
 *   /village taxes set &lt;lever&gt; &lt;0-3&gt;      (the player who leads, or ops) head, wage, toll, levy
 *   /village taxes collect | sit            (ops) tax day's round now; the council sits on the taxes now
 *   /village taxes protest | revolt | calm  (ops) a protest now; the town risen now; all calm
 *   /village knight                         your service to the town and your knighthood
 *   /village knight style sir|dame|knight   how you are addressed as a knight
 *   /village knight vote lower|keep|raise   your vote on the taxes, as a knight (or a seated player, or the leader)
 *   /village knight retinue call|dismiss|hire|release
 *   /village knight manor [grand]           where your manor is; pay sixty coins for a grander hall
 *   /village knight dub|strip|grant &lt;player&gt;  (ops) knight now, strip, grant the land now
 *   /village knight build &lt;player&gt; [free]   (ops) the manor raised now, out of the stores (or free)
 *   /village realm stage                    (ops, as a player) the smoke stage: a knight, its manor, a protest
 * </pre>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class RealmCommands {

    private RealmCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("taxes").executes(RealmCommands::taxes)
                .then(Commands.literal("pay").executes(RealmCommands::pay))
                .then(Commands.literal("book").executes(ctx -> book(ctx, "Taxes")))
                .then(Commands.literal("urge").then(Commands.argument("way", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("lower", "raise"), b))
                    .executes(ctx -> urge(ctx, StringArgumentType.getString(ctx, "way")))))
                .then(Commands.literal("set").then(Commands.argument("lever", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("head", "wage", "toll", "levy"), b))
                    .then(Commands.argument("rate", IntegerArgumentType.integer(0, 3))
                        .executes(ctx -> set(ctx, StringArgumentType.getString(ctx, "lever"), IntegerArgumentType.getInteger(ctx, "rate"))))))
                .then(Commands.literal("collect").requires(s -> s.hasPermission(2)).executes(ctx -> op(ctx, "collect")))
                .then(Commands.literal("sit").requires(s -> s.hasPermission(2)).executes(ctx -> op(ctx, "sit")))
                .then(Commands.literal("protest").requires(s -> s.hasPermission(2)).executes(ctx -> op(ctx, "protest")))
                .then(Commands.literal("revolt").requires(s -> s.hasPermission(2)).executes(ctx -> op(ctx, "revolt")))
                .then(Commands.literal("calm").requires(s -> s.hasPermission(2)).executes(ctx -> op(ctx, "calm"))))
            .then(Commands.literal("knight").executes(RealmCommands::knight)
                .then(Commands.literal("book").executes(ctx -> book(ctx, "Knights")))
                .then(Commands.literal("style").then(Commands.argument("style", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("sir", "dame", "knight"), b))
                    .executes(ctx -> style(ctx, StringArgumentType.getString(ctx, "style")))))
                .then(Commands.literal("vote").then(Commands.argument("way", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("lower", "keep", "raise"), b))
                    .executes(ctx -> vote(ctx, StringArgumentType.getString(ctx, "way")))))
                .then(Commands.literal("retinue").then(Commands.argument("what", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("call", "dismiss", "hire", "release"), b))
                    .executes(ctx -> retinue(ctx, StringArgumentType.getString(ctx, "what")))))
                .then(Commands.literal("manor").executes(ctx -> manor(ctx, false))
                    .then(Commands.literal("grand").executes(ctx -> manor(ctx, true))))
                .then(Commands.literal("dub").requires(s -> s.hasPermission(2)).then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> admin(ctx, "dub", EntityArgument.getPlayer(ctx, "player"), false))))
                .then(Commands.literal("strip").requires(s -> s.hasPermission(2)).then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> admin(ctx, "strip", EntityArgument.getPlayer(ctx, "player"), false))))
                .then(Commands.literal("grant").requires(s -> s.hasPermission(2)).then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> admin(ctx, "grant", EntityArgument.getPlayer(ctx, "player"), false))))
                .then(Commands.literal("build").requires(s -> s.hasPermission(2)).then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> admin(ctx, "build", EntityArgument.getPlayer(ctx, "player"), false))
                    .then(Commands.literal("free").executes(ctx -> admin(ctx, "build", EntityArgument.getPlayer(ctx, "player"), true))))))
            .then(Commands.literal("realm").then(Commands.literal("stage").requires(s -> s.hasPermission(2)).executes(RealmCommands::stage))));
    }

    // ------------------------------------------------------------------ which town

    /** The town the speaker stands in (or near). */
    @Nullable
    static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), at, Villages.VILLAGE_RANGE * 2);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No town near enough."));
        return v;
    }

    /** For a knight's business: the town it stands in if it is a knight of it, else the first town it is a knight of. */
    @Nullable
    static Villages.Village knightsTown(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
        BlockPos at = p.blockPosition();
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), at, Villages.VILLAGE_RANGE * 2);
        if (v != null && (Knights.isKnight(v.id(), p.getUUID()) || !Knights.isKnightAnywhere(p.getUUID()))) return v;
        for (UUID t : Knights.townsOf(p.getUUID())) {
            Villages.Village k = Villages.get(t);
            if (k != null) return k;
        }
        if (v == null) ctx.getSource().sendFailure(Component.literal("No town near enough."));
        return v;
    }

    static int say(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    // ------------------------------------------------------------------ the taxes

    static int taxes(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        UUID id = v.id();
        ServerLevel level = ctx.getSource().getLevel();
        long day = level.getDayTime() / 24000L;
        StringBuilder sb = new StringBuilder(Villages.name(id)).append(" levies ").append(TaxBook.summary(id)).append(".");
        sb.append("\nTax day: day ").append(TaxBook.nextTaxDay(id, day)).append(". The town is ").append(TaxUnrest.word(id))
            .append(" (unrest ").append(TaxUnrest.level(id)).append(", the taxes take ").append(TaxUnrest.burden(id)).append(" in a hundred of a wage).");
        if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
            int owed = TaxBook.owedBy(id, p.getUUID());
            sb.append(owed > 0 ? "\nYou owe " + owed + " coins: /village taxes pay." : "\nYou owe nothing.");
        }
        if (!CivicLand.live()) sb.append("\n(The taxes are not at work in this world.)");
        return say(ctx, sb.toString());
    }

    static int pay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        return say(ctx, TaxBook.pay(ctx.getSource().getLevel(), v.id(), p));
    }

    static int urge(CommandContext<CommandSourceStack> ctx, String way) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        com.jrpetty.mcassistant.entity.VillageFolkEntity f = null;
        for (var a : Villages.folkOf(v.id())) if (a instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity x && !x.isBaby()) { f = x; break; }
        if (f == null) return say(ctx, "There's nobody of the town to tell.");
        String said = CivicLand.talk(f, p, way.startsWith("l") ? "The taxes should be lower" : "The taxes should be higher");
        return say(ctx, said == null ? "Nobody listens." : said);
    }

    static int set(CommandContext<CommandSourceStack> ctx, String lever, int rate) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        boolean leader = ctx.getSource().getEntity() instanceof ServerPlayer p && PlayerLeader.leads(v.id(), p.getUUID());
        if (!leader && !ctx.getSource().hasPermission(2)) {
            ctx.getSource().sendFailure(Component.literal("The council sets the taxes. Only the player who leads the town may set them by its word."));
            return 0;
        }
        TaxBook.Lever l = TaxBook.Lever.named(lever);
        if (l == null) {
            ctx.getSource().sendFailure(Component.literal("Which tax? head, wage, toll or levy."));
            return 0;
        }
        String by = leader ? "by the word of " + ctx.getSource().getTextName() : "by decree";
        int now = TaxBook.set(ctx.getSource().getLevel(), v, l, rate, by);
        return say(ctx, Villages.name(v.id()) + "'s " + l.words + " is " + TaxBook.words(l, now) + ".");
    }

    static int op(CommandContext<CommandSourceStack> ctx, String what) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        return say(ctx, switch (what) {
            case "collect" -> {
                TaxBook.collectNowForTests(level, v);
                int[] r = TaxBook.roundForTests(v.id());
                yield "Tax day held in " + Villages.name(v.id()) + ": " + r[1] + " coins taken from " + r[0] + " folk.";
            }
            case "sit" -> TaxBook.sit(level, v, level.getDayTime() / 24000L, "the council, sitting by decree,");
            case "protest" -> TaxUnrest.protestNow(level, v);
            case "revolt" -> TaxUnrest.revoltNow(level, v);
            default -> TaxUnrest.calmNow(level, v);
        });
    }

    /** The town's books, open at the Taxes or the Knights page (the page numbers past forty are not in /village stats). */
    static int book(CommandContext<CommandSourceStack> ctx, String page) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.nbt.CompoundTag books = com.jrpetty.mcassistant.entity.Annals.snapshot(level, v);
        CivicLand.viewer(books, level, v, p);
        books.putString("page", page);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    // ------------------------------------------------------------------ the knighthood

    static int knight(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Villages.Village v = knightsTown(ctx, p);
        if (v == null) return 0;
        com.jrpetty.mcassistant.entity.VillageFolkEntity f = null;
        for (var a : Villages.folkOf(v.id())) if (a instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity x && !x.isBaby()) { f = x; break; }
        String said = f == null ? null : CivicLand.talk(f, p, "What's my standing in the town's service as a knight?");
        return say(ctx, said == null ? "Your service to " + Villages.name(v.id()) + ": " + Knights.service(v.id(), p.getUUID()) + " of " + Knights.SERVICE + "." : said);
    }

    static int style(CommandContext<CommandSourceStack> ctx, String s) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Knights.Style st = Knights.Style.named(s);
        if (st == null) {
            ctx.getSource().sendFailure(Component.literal("Sir, Dame or Knight?"));
            return 0;
        }
        return say(ctx, Knights.setStyle(p, st));
    }

    static int vote(CommandContext<CommandSourceStack> ctx, String way) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Villages.Village v = knightsTown(ctx, p);
        if (v == null) return 0;
        int choice = way.startsWith("l") ? -1 : way.startsWith("r") ? 1 : 0;
        return say(ctx, Knights.vote(v.id(), p.getUUID(), choice));
    }

    static int retinue(CommandContext<CommandSourceStack> ctx, String what) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Villages.Village v = knightsTown(ctx, p);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        return say(ctx, switch (what) {
            case "dismiss" -> Retinue.dismiss(level, v.id(), p);
            case "hire" -> Retinue.hire(level, v.id(), p);
            case "release" -> Retinue.release(level, v.id(), p.getUUID(), "released");
            default -> Retinue.call(level, v.id(), p);
        });
    }

    static int manor(CommandContext<CommandSourceStack> ctx, boolean grand) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Villages.Village v = knightsTown(ctx, p);
        if (v == null) return 0;
        com.jrpetty.mcassistant.entity.VillageFolkEntity f = null;
        for (var a : Villages.folkOf(v.id())) if (a instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity x && !x.isBaby()) { f = x; break; }
        if (f == null) return say(ctx, "There's nobody of the town to ask.");
        String said = CivicLand.talk(f, p, grand ? "I'll pay for a grander manor" : "Where's my manor?");
        return say(ctx, said == null ? "You have no land here." : said);
    }

    static int admin(CommandContext<CommandSourceStack> ctx, String what, ServerPlayer target, boolean free) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        String name = target.getName().getString();
        return say(ctx, switch (what) {
            case "dub" -> Knights.dubNow(level, v, target);
            case "strip" -> Knights.strip(level, v.id(), target.getUUID(), "by decree");
            case "grant" -> Knights.isKnight(v.id(), target.getUUID()) ? KnightManors.grantNow(level, v, target.getUUID())
                : name + " is no knight of " + Villages.name(v.id()) + ".";
            default -> KnightManors.buildNow(level, v, target.getUUID(), !free);
        });
    }

    /**
     * The smoke stage (an operator, as a player): the town you stand in knights you now (its stores given the makings of
     * the banner), raises your manor at once, and the angry gather at the hall to protest a heavy head tax.
     */
    static int stage(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        return say(ctx, CivicLand.stage(level, v, p));
    }
}
