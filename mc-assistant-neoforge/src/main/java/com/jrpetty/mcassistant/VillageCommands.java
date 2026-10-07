package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /village — the blunt tool for standing a settlement up and asking how it
 * is doing. Always registered: this is what you reach for when the item or
 * the block is not behaving, so it must not itself be behind a switch.
 *
 * <pre>
 *   /village spawn            one settler where you stand
 *   /village spawn 12         twelve — a full starting village
 *   /village found 40 [x z]   a village of forty, founded as the board's founding screen does (ops)
 *   /village status           who lives here, what age, what they are short of
 *   /village top              every village in the world, by its folk: age, worth, renown
 *   /village lineup           one folk of every trade, dressed, to look at (ops)
 *   /village talk [words]     talk with the nearest folk, as a right-click would (ops)
 *   /village chronicle        the nearest village's history, as a book
 *   /village museum           the nearest village's museum: its finds on show, its archive; museum work | stage (ops)
 *   /village standing         what every village you have met thinks of you
 *   /village house            the village's houses; house buy | house let N | house rent
 *   /village stall            the players' market stalls; stall rent | screen | till | books | price N item
 *   /village decor            how each home is furnished; decor now | decor showcase (ops)
 *   /village bank             the bank's books; bank deposit|withdraw|repay N, bank mortgage; bank week|showcase (ops)
 *   /village knacks [name]    the knacks each folk chose for itself; knacks grant <name> <key> (ops)
 *   /village stats            the town's books in full: the analytics screen (as the village board)
 *   /village research         the city's research: what the leader has the town studying, and the tree
 *   /village research pick|grant &lt;civic&gt;   study this civic now, or have it done (ops; for tests)
 *   /village bell             the town bell: where it hangs, its frame, today's bells; bell ring|call dawn|noon|dusk (ops)
 *   /village founding         the next Founding Day; founding now (ops) keeps it this minute
 *   /village birthdays        the week's birthdays; birthdays now &lt;name&gt; (ops) keeps one now
 *   /village speed 16|max|normal   time runs faster, to watch a village grow (ops / world owner)
 *   /village jobs [why|books|post|decide|look|want|pact]   the job market between towns (JobMarketCommands)
 * </pre>
 */
public final class VillageCommands {

    private VillageCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("spawn")
                .executes(ctx -> spawn(ctx, 1))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
            // For the console and for scripts, where there is no player to
            // stand near: /village spawnat <x> <z> [count]
            .then(Commands.literal("spawnat")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> spawnAt(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                            .executes(ctx -> spawnAt(ctx,
                                IntegerArgumentType.getInteger(ctx, "count")))))))
            // Found a village of a chosen size, as the board's founding screen does: the board put
            // up (if no board waits there already) and Confirm and spawn, the ground made level and
            // the folk brought in over the next few seconds. /village found <count> [x z]. And the
            // parts of it, for scripts: the board alone, as setting a spawner down puts it up
            // (found board [x z]); the founding screen of the nearest waiting board at a count, as
            // right-clicking it opens it (found screen [count]); how the foundings are getting on
            // (found status).
            .then(Commands.literal("found").requires(src -> src.hasPermission(2))
                .then(Commands.literal("status").executes(VillageCommands::foundStatus))
                .then(Commands.literal("ground")
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .then(Commands.argument("radius", IntegerArgumentType.integer(1, 96))
                                .executes(VillageCommands::foundGround)))))
                .then(Commands.literal("board")
                    .executes(ctx -> foundBoard(ctx, false))
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(ctx -> foundBoard(ctx, true)))))
                .then(Commands.literal("screen")
                    .executes(ctx -> foundScreen(ctx, 0))
                    .then(Commands.argument("count", IntegerArgumentType.integer(
                            com.jrpetty.mcassistant.village.FoundingPlan.MIN_FOLK, com.jrpetty.mcassistant.village.FoundingPlan.MAX_FOLK))
                        .executes(ctx -> foundScreen(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.argument("count", IntegerArgumentType.integer(
                        com.jrpetty.mcassistant.village.FoundingPlan.MIN_FOLK, com.jrpetty.mcassistant.village.FoundingPlan.MAX_FOLK))
                    .executes(ctx -> found(ctx, false))
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(ctx -> found(ctx, true))))))
            .then(Commands.literal("folk").executes(VillageCommands::folk))
            // The town's quarters, the homes in the crafts' smoke and by the park, and the park (Quarters, Park).
            .then(com.jrpetty.mcassistant.entity.Quarters.command())
            .then(Commands.literal("people").executes(VillageCommands::people))
            // The knacks each folk chose for itself (FolkSkills): every folk of the nearest village, or one
            // by name; and, for operators and tests, a knack given to a folk as though it chose it.
            .then(Commands.literal("knacks")
                .executes(ctx -> knacks(ctx, ""))
                .then(Commands.literal("grant").requires(src -> src.hasPermission(2))
                    .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.string())
                        .then(Commands.argument("key", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .executes(ctx -> grantKnack(ctx,
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name"),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "key"))))))
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .executes(ctx -> knacks(ctx, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("list").executes(VillageCommands::list))
            // Every village in the world side by side, the biggest first (PlayerServices).
            .then(com.jrpetty.mcassistant.entity.PlayerServices.topCommand())
            .then(Commands.literal("anchors").requires(src -> src.hasPermission(2))
                .executes(VillageCommands::anchors))
            .then(Commands.literal("status").executes(VillageCommands::status))
            // The town's books in full, on the analytics screen (as clicking the village board does).
            .then(Commands.literal("stats").executes(ctx -> stats(ctx, -1))
                .then(Commands.literal("close").executes(ctx -> {
                    // Shut the books on the screen of whoever asks (the client smoke, between its stages).
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
                    net.minecraft.nbt.CompoundTag shut = new net.minecraft.nbt.CompoundTag();
                    shut.putBoolean("close", true);
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(shut));
                    return 1;
                }))
                .then(Commands.argument("page", IntegerArgumentType.integer(0, 40))
                    .executes(ctx -> stats(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
            // The city's research (CityTree): the tree and what the leader has the town studying; and,
            // for ops and tests, a civic set to study now (pick) or done at once (grant), each only
            // once the civic before it in its branch is done.
            // The town's calendar (TownCalendar): the town bell — where it hangs, today's bells and who rang
            // them; for ops and tests, one rung now (bell ring dawn|noon|dusk). Founding Day: the next, or kept
            // now (founding now). Birthdays: the week's, or one kept now (birthdays now <name>).
            .then(Commands.literal("bell").executes(VillageCommands::bell)
                .then(Commands.literal("ring").requires(src -> src.hasPermission(2))
                    .then(Commands.argument("peal", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(java.util.List.of("dawn", "noon", "dusk"), b))
                        .executes(ctx -> ringBell(ctx, false))))
                // ...or called for now: its ringer walks to the bell and rings it there.
                .then(Commands.literal("call").requires(src -> src.hasPermission(2))
                    .then(Commands.argument("peal", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(java.util.List.of("dawn", "noon", "dusk"), b))
                        .executes(ctx -> ringBell(ctx, true)))))
            .then(Commands.literal("founding").executes(ctx -> foundingDay(ctx, false))
                .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> foundingDay(ctx, true))))
            .then(Commands.literal("birthdays").executes(ctx -> birthdays(ctx, null))
                .then(Commands.literal("now").requires(src -> src.hasPermission(2))
                    .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(ctx -> birthdays(ctx, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name"))))))
            // [ageing] Each folk's years, the age it will live to and the day that falls on (Lifespans).
            .then(Commands.literal("lifespans").executes(ctx -> {
                Villages.Village v = villageHere(ctx);
                if (v == null) {
                    ctx.getSource().sendFailure(Component.literal("No village yet."));
                    return 0;
                }
                java.util.List<String> lines = com.jrpetty.mcassistant.entity.Lifespans.lines(v.id(), ctx.getSource().getLevel().getDayTime() / 24000L);
                for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
                return lines.size();
            }))
            // The town's newer sights and where they stand (entity/Sights): for operators looking round and
            // for the pictures; "now" does what the town would have done later today, with the same stores.
            .then(Commands.literal("sights").requires(src -> src.hasPermission(2)).executes(ctx -> sights(ctx, null))
                .then(Commands.argument("what", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(com.jrpetty.mcassistant.entity.Sights.kinds(), b))
                    .executes(ctx -> sights(ctx, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "what")))))
            // The ground of a town already standing levelled round it, as a founding at a board levels it (a town
            // the world founded, or one from before its ground was levelled). An operator, or the world's owner.
            .then(Commands.literal("level").executes(VillageCommands::levelGround))
            // Which version of the mod is loaded: its number and the newest change in it.
            .then(Commands.literal("version").executes(ctx -> {
                String v = "MC Assistant " + com.jrpetty.mcassistant.McAssistantMod.version() + " — "
                    + com.jrpetty.mcassistant.entity.AssistantEntity.latestBuild();
                ctx.getSource().sendSuccess(() -> Component.literal(v), false);
                return 1;
            }))
            .then(Commands.literal("research").executes(VillageCommands::research)
                .then(Commands.literal("pick").requires(src -> src.hasPermission(2))
                    .then(Commands.argument("civic", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(civicKeys(), b))
                        .executes(ctx -> research(ctx, false))))
                .then(Commands.literal("grant").requires(src -> src.hasPermission(2))
                    .then(Commands.argument("civic", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(civicKeys(), b))
                        .executes(ctx -> research(ctx, true)))))
            // The village school (entity/School): its teacher, its pupils and what each leans to, in chat; the
            // books opened at its page; and, for operators and the pictures, a lesson called now, a teacher's
            // line said out loud, or a schoolhouse set out on a stage mid-lesson.
            .then(Commands.literal("school").executes(VillageCommands::school)
                .then(Commands.literal("page").executes(VillageCommands::schoolPage))
                .then(Commands.literal("lesson").requires(src -> src.hasPermission(2)).executes(VillageCommands::schoolLesson))
                .then(Commands.literal("say").requires(src -> src.hasPermission(2)).executes(ctx -> {
                    String said = com.jrpetty.mcassistant.entity.School.sayNow(ctx.getSource().getLevel(),
                        net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition()));
                    ctx.getSource().sendSuccess(() -> Component.literal("SAID " + said), false);
                    return 1;
                }))
                .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                    java.util.List<String> lines = com.jrpetty.mcassistant.entity.School.stage(ctx.getSource().getLevel(),
                        net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition()));
                    ctx.getSource().sendSuccess(() -> Component.literal(String.join(" | ", lines)), false);
                    return lines.size();
                })))
            // The village's houses: who lives where, what is for sale; buy one, let it out, take the rent.
            .then(Commands.literal("house")
                .executes(VillageCommands::houses)
                .then(Commands.literal("buy").executes(ctx -> house(ctx, "buy", 0)))
                .then(Commands.literal("rent").executes(ctx -> house(ctx, "rent", 0)))
                .then(Commands.literal("let")
                    .then(Commands.argument("coins", IntegerArgumentType.integer(0, 20))
                        .executes(ctx -> house(ctx, "let", IntegerArgumentType.getInteger(ctx, "coins"))))))
            // The bank (entity/Bank): its books; your account (deposit, withdraw), a mortgage on a house, repay it.
            .then(com.jrpetty.mcassistant.entity.Bank.command())
            // [flats] The village's blocks of flats: each flat, who lives there, on what terms. `stage`
            // sets a furnished block out on a stage at the spot, for the pictures (from a palette, not the stores).
            .then(Commands.literal("flats")
                .executes(VillageCommands::flats)
                .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(VillageCommands::flatsStage)))
            // The job market between towns: the notices, the applications, who came and went (JobMarketCommands).
            .then(JobMarketCommands.node())
            .then(Commands.literal("wages").executes(ctx -> page(ctx, 2)))
            .then(Commands.literal("economy").executes(ctx -> page(ctx, 3)))
            // The sellers' books: what the shop, the café, the tavern and the stores have, sell and make.
            .then(Commands.literal("shop").executes(ctx -> page(ctx, 4)))
            // A player's market stall: the stalls here; rent, screen, till, books, price; market day now, a lapse (ops).
            .then(com.jrpetty.mcassistant.entity.PlayerStalls.command())
            // The shop's workshop (Workshop): its makers, its order book, the blueprints and the age's say; orders.
            .then(WorkshopCommands.node())
            // [econ-store] The stock book: every ware's cover, its reorder point, the orders in flight, the stock-outs.
            .then(StoreCommands.node())
            // The storehouse: its books for the day, its storekeeper and couriers, and its run list.
            .then(Commands.literal("stores").executes(ctx -> page(ctx, 5)))
            // The town's mine (TownMine): where it was opened, the faces worked out, who works which face and how deep.
            .then(Commands.literal("mine").executes(VillageCommands::mine))
            // The street sweeper (Sweepers): what it swept in today and what lies about the town; and, for ops
            // and the client smoke, the nearest grown folk made the storehouse's sweeper now.
            .then(Commands.literal("sweeper").executes(ctx -> sweeper(ctx, false))
                .then(Commands.literal("appoint").requires(src -> src.hasPermission(2)).executes(ctx -> sweeper(ctx, true))))
            // The stable: the village's horses, donkeys and mules, who has one out, the saddles (Stables).
            // `horses showcase` (operators): a stable stood up where you are, with horses in its stalls
            // and a courier on horseback at its door, to be looked at (cleared with the line-up's tag).
            .then(Commands.literal("horses").executes(ctx -> page(ctx, 6))
                .then(Commands.literal("showcase").requires(src -> src.hasPermission(2)).executes(ctx -> {
                    java.util.List<String> views = com.jrpetty.mcassistant.entity.Stables.showcase(ctx.getSource().getLevel(),
                        net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition()));
                    ctx.getSource().sendSuccess(() -> Component.literal("STABLE | " + String.join(" | ", views)), false);
                    return views.size();
                })))
            // The morning news of the villages near you, in chat, once a morning: on or off.
            .then(Commands.literal("news")
                .then(Commands.literal("on").executes(ctx -> news(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> news(ctx, false))))
            // Fast time, to watch a village grow: /village speed 16 | max | normal, or no word to ask.
            .then(Commands.literal("speed")
                .executes(VillageCommands::speedNow)
                .then(Commands.literal("normal").executes(ctx -> speed(ctx, 1)))
                .then(Commands.literal("max").executes(ctx -> speed(ctx, TimeSpeed.MAX)))
                .then(Commands.argument("times", IntegerArgumentType.integer(1, TimeSpeed.MAX))
                    .executes(ctx -> speed(ctx, IntegerArgumentType.getInteger(ctx, "times")))))
            // The nearest village's history, as a book.
            .then(Commands.literal("chronicle").executes(VillageCommands::chronicle))
            // The museum and its archive: what is on show, who found it, the volumes (MuseumCommands).
            .then(MuseumCommands.build())
            // [economy] The larder against the mouths, the coal floor and charcoal, the builders' stock carried about.
            .then(EconomyCommands.build())
            // What every village you have met thinks of you.
            .then(Commands.literal("standing").executes(VillageCommands::standing))
            // How the villages stand with each other: allies, feuds, tribute.
            .then(Commands.literal("relations").executes(VillageCommands::relations))
            // The nearest village's town ledger, as a book: stores, residents, building, shortages.
            .then(Commands.literal("ledger").executes(VillageCommands::ledger))
            // How each home is furnished: its trades' things, its colours, the luxuries its folk bought
            // (Decor, Luxuries); "now" furnishes them as far as the stores run to and sees to the candles
            // (ops); "showcase" sets a furnished home out where you stand, for the pictures (ops).
            .then(Commands.literal("decor").executes(ctx -> decor(ctx, ""))
                .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> decor(ctx, "now")))
                .then(Commands.literal("showcase").requires(src -> src.hasPermission(2)).executes(ctx -> decor(ctx, "showcase"))))
            // Talk with the nearest folk, as a right-click would (for scripts and tests).
            .then(Commands.literal("talk").requires(src -> src.hasPermission(2))
                .executes(ctx -> talk(ctx, ""))
                .then(Commands.argument("words", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .executes(ctx -> talk(ctx, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "words")))))
            // Have the nearest folk say something out loud, in its bubble (for scripts and tests).
            .then(Commands.literal("say").requires(src -> src.hasPermission(2))
                .then(Commands.argument("words", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .executes(ctx -> sayAloud(ctx, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "words")))))
            // One folk of every trade, in its clothes and with its tool, stood in a
            // row to be looked at. Clear them with /kill @e[tag=folk_lineup].
            .then(Commands.literal("lineup").requires(src -> src.hasPermission(2))
                .executes(VillageCommands::lineup))
            // Every building a village raises, set out on a stage to be looked at; or a whole
            // town laid out to the plan. For the pictures; builds from a palette, not the stores.
            .then(Commands.literal("showcase").requires(src -> src.hasPermission(2))
                .then(Commands.literal("buildings").executes(ctx -> showcase(ctx, false)))
                .then(Commands.literal("town").executes(ctx -> showcase(ctx, true)))
                .then(Commands.literal("ages").executes(VillageCommands::showcaseAges))
                // A raid on the staged town at night: the watch on the wall, a band at the gate.
                .then(Commands.literal("raid").executes(VillageCommands::showcaseRaid))
                // The staged town's windows lit, as they are after dark, or put out.
                .then(Commands.literal("lights")
                    .then(Commands.literal("on").executes(ctx -> showcaseLights(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> showcaseLights(ctx, false))))));
    }

    /** /village decor [now|showcase]: the homes' furnishing, a line a house (Decor); or a home set out for the pictures. */
    private static int decor(CommandContext<CommandSourceStack> ctx, String what) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        if (what.equals("showcase")) {
            java.util.List<String> views = com.jrpetty.mcassistant.entity.Luxuries.showcase(level, here);
            ctx.getSource().sendSuccess(() -> Component.literal("DECOR | " + String.join(" | ", views)), false);
            return views.size();
        }
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        if (what.equals("now")) {
            com.jrpetty.mcassistant.entity.Decor.workNow(level, v, 200);
            com.jrpetty.mcassistant.entity.Luxuries.candles(level, v);
        }
        java.util.List<String> lines = com.jrpetty.mcassistant.entity.Decor.lines(level, v);
        String said = "HOMES of " + Villages.name(v.id()) + (lines.isEmpty() ? ": no household has a house yet" : " | " + String.join(" | ", lines));
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return lines.size();
    }

    private static int showcaseRaid(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        java.util.List<String> views = Showcase.raid(ctx.getSource().getLevel(), at);
        ctx.getSource().sendSuccess(() -> Component.literal("RAID | " + String.join(" | ", views)), false);
        return views.size();
    }

    private static int showcaseLights(CommandContext<CommandSourceStack> ctx, boolean on) {
        com.jrpetty.mcassistant.entity.TownLife.lightsNow(ctx.getSource().getLevel(), Showcase.STAGED, on);
        ctx.getSource().sendSuccess(() -> Component.literal("LIGHTS " + (on ? "on" : "off") + " in "
            + Showcase.STAGED.size() + " buildings"), false);
        return Showcase.STAGED.size();
    }

    private static int showcaseAges(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        java.util.List<String> lines = Showcase.ages(level, at);
        ctx.getSource().sendSuccess(() -> Component.literal("AGES " + String.join(" | ", lines)), false);
        return lines.size();
    }

    private static int showcase(CommandContext<CommandSourceStack> ctx, boolean town) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        if (town) {
            int n = Showcase.town(level, at);
            ctx.getSource().sendSuccess(() -> Component.literal("TOWN " + at.getX() + " " + at.getY() + " " + at.getZ()
                + " with " + n + " buildings | " + String.join(" | ", Showcase.VIEWS)), false);
            return n;
        }
        java.util.List<String> lines = Showcase.buildings(level, at);
        ctx.getSource().sendSuccess(() -> Component.literal("SHOWCASE " + String.join(" | ", lines)), false);
        return lines.size();
    }

    private static int talk(CommandContext<CommandSourceStack> ctx, String words)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        java.util.List<VillageFolkEntity> near = player.level().getEntitiesOfClass(VillageFolkEntity.class,
            player.getBoundingBox().inflate(8.0), VillageFolkEntity::isAlive);
        near.sort(java.util.Comparator.comparingDouble(f -> f.distanceToSqr(player)));
        if (near.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Nobody within eight blocks to talk to."));
            return 0;
        }
        if (words.isBlank()) com.jrpetty.mcassistant.entity.FolkTalk.open(near.get(0), player);
        else com.jrpetty.mcassistant.entity.FolkTalk.handle(near.get(0), player,
            com.jrpetty.mcassistant.entity.TalkTopic.SAY, words);
        return 1;
    }

    private static int sayAloud(CommandContext<CommandSourceStack> ctx, String words)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        java.util.List<VillageFolkEntity> near = player.level().getEntitiesOfClass(VillageFolkEntity.class,
            player.getBoundingBox().inflate(8.0), VillageFolkEntity::isAlive);
        near.sort(java.util.Comparator.comparingDouble(f -> f.distanceToSqr(player)));
        if (near.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Nobody within eight blocks to say it."));
            return 0;
        }
        com.jrpetty.mcassistant.entity.FolkTalk.speak(near.get(0), words);
        return 1;
    }

    private static int chronicle(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Villages.Village v = Villages.nearest(player.level(), player.blockPosition(), Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough to have a history."));
            return 0;
        }
        net.minecraft.world.item.ItemStack book = com.jrpetty.mcassistant.entity.Chronicles.book(v.id(),
            player.level().getDayTime() / 24000L);
        if (!player.getInventory().add(book)) player.drop(book, false);
        ctx.getSource().sendSuccess(() -> Component.literal("The chronicle of " + Villages.name(v.id()) + "."), false);
        return 1;
    }

    private static int mine(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), at, Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough to have a mine."));
            return 0;
        }
        java.util.List<String> lines = com.jrpetty.mcassistant.entity.TownMine.report(v.id(), v.centre());
        ctx.getSource().sendSuccess(() -> Component.literal("The mine of " + Villages.name(v.id()) + " | " + String.join(" | ", lines)), false);
        return lines.size();
    }

    private static int ledger(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Villages.Village v = Villages.nearest(player.level(), player.blockPosition(), Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough to keep a ledger."));
            return 0;
        }
        net.minecraft.world.item.ItemStack book = com.jrpetty.mcassistant.entity.Services.ledger(player.serverLevel(), v.id());
        if (!player.getInventory().add(book)) player.drop(book, false);
        ctx.getSource().sendSuccess(() -> Component.literal("The town ledger of " + Villages.name(v.id()) + "."), false);
        return 1;
    }

    private static int relations(CommandContext<CommandSourceStack> ctx) {
        java.util.List<String> lines = com.jrpetty.mcassistant.entity.Diplomacy.report();
        if (lines.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No two villages near enough to have dealings yet."), false);
            return 0;
        }
        for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
        return lines.size();
    }

    private static int standing(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int n = 0;
        for (Villages.Village v : Villages.every()) {
            com.jrpetty.mcassistant.entity.Standing.View view = com.jrpetty.mcassistant.entity.Standing.of(
                v.id(), player.getUUID(), player.level().getGameTime());
            if (view.knownBy() == 0) continue;
            n++;
            final String line = com.jrpetty.mcassistant.entity.Standing.titleIn(v.id(), view.title())
                + " — known to " + view.knownBy() + ", warmth " + view.score()
                + (view.bestFriend().isEmpty() ? "" : ", dearest to " + view.bestFriend())
                + (view.worstCritic().isEmpty() ? "" : ", distrusted by " + view.worstCritic());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        if (n == 0) ctx.getSource().sendSuccess(() -> Component.literal("No village knows you yet. Go and say hello."), false);
        return n;
    }

    private static int lineup(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.world.phys.Vec3 at = ctx.getSource().getPosition();
        float yaw = ctx.getSource().getRotation().y;
        // Four blocks ahead of whoever asked, across their line of sight, facing them.
        double fx = -Math.sin(Math.toRadians(yaw)), fz = Math.cos(Math.toRadians(yaw));
        double sx = -fz, sz = fx;
        AssistantEntity.StationTask[] trades = AssistantEntity.StationTask.values();
        int stood = 0;
        for (int i = 0; i < trades.length; i++) {
            double off = (i - (trades.length - 1) / 2.0) * 1.4;
            double x = at.x + fx * 4.0 + sx * off;
            double z = at.z + fz * 4.0 + sz * off;
            net.minecraft.core.BlockPos ground = groundAt(level, (int) Math.floor(x), (int) Math.floor(z));
            VillageFolkEntity folk = McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (folk == null) continue;
            float face = yaw + 180.0F;
            folk.moveTo(x, ground.getY(), z, face, 0.0F);
            folk.setYHeadRot(face);
            folk.setYBodyRot(face);
            folk.makeShowcase(trades[i]);
            folk.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(
                switch (trades[i]) {
                    case FARM -> net.minecraft.world.item.Items.IRON_HOE;
                    case WOOD -> net.minecraft.world.item.Items.IRON_AXE;
                    case MINE -> net.minecraft.world.item.Items.IRON_PICKAXE;
                    case RANCH -> net.minecraft.world.item.Items.SHEARS;
                    case GUARD -> net.minecraft.world.item.Items.IRON_SWORD;
                    case SMELT -> net.minecraft.world.item.Items.IRON_INGOT;
                    case FISH -> net.minecraft.world.item.Items.FISHING_ROD;
                    case STORE -> net.minecraft.world.item.Items.BOOK;
                    case HAUL -> net.minecraft.world.item.Items.CHEST;
                    case SMITH -> net.minecraft.world.item.Items.MACE;
                    case TAILOR -> net.minecraft.world.item.Items.STRING;
                    case BEEKEEP -> net.minecraft.world.item.Items.HONEYCOMB;
                    case BREW -> net.minecraft.world.item.Items.POTION;
                    case ENCHANT -> net.minecraft.world.item.Items.ENCHANTED_BOOK;
                    case COOK -> net.minecraft.world.item.Items.BREAD;
                    case SHOP -> McAssistantMod.VILLAGE_COIN.get();
                    case SCOUT -> net.minecraft.world.item.Items.COMPASS;
                    case HUNT -> net.minecraft.world.item.Items.BOW;
                    case BANK -> net.minecraft.world.item.Items.GOLD_INGOT;
                    case NONE -> net.minecraft.world.item.Items.AIR;
                }));
            folk.rename(switch (trades[i]) {
                case FARM -> "Farmer";
                case WOOD -> "Lumberjack";
                case MINE -> "Miner";
                case RANCH -> "Rancher";
                case GUARD -> "Guard";
                case SMELT -> "Smelter";
                case FISH -> "Fisher";
                case STORE -> "Storekeeper";
                case HAUL -> "Hauler";
                case NONE -> "Newcomer";
                default -> trades[i].title;
            });
            folk.addTag("folk_lineup");
            if (level.addFreshEntity(folk)) stood++;
        }
        final int n = stood;
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Stood " + n + " folk up, one of every trade. /kill @e[tag=folk_lineup] clears them."), false);
        return n;
    }

    /** The free ground-level spot a couple of blocks ahead of this position. */
    private static net.minecraft.core.BlockPos groundAt(net.minecraft.server.level.ServerLevel level,
                                                        int x, int z) {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new net.minecraft.core.BlockPos(x, y, z);
    }

    private static int spawnAt(CommandContext<CommandSourceStack> ctx, int count) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        // Make sure the ground is really there — and the ground round about, where the party may
        // choose to make its camp instead (VillageSpawner.campSite).
        for (int cx = -3; cx <= 3; cx++) {
            for (int cz = -3; cz <= 3; cz++) level.getChunk((x >> 4) + cx, (z >> 4) + cz);
        }
        return raiseMany(ctx, level, groundAt(level, x, z), 0.0F, count);
    }

    // ------------------------------------------------------------------ founding a village of a chosen size

    /** Where a founding goes: the coordinates given, or where the command was run; the ground there loaded. */
    private static net.minecraft.core.BlockPos foundingSpot(CommandContext<CommandSourceStack> ctx, boolean given) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        int x, z;
        if (given) {
            x = IntegerArgumentType.getInteger(ctx, "x");
            z = IntegerArgumentType.getInteger(ctx, "z");
        } else {
            net.minecraft.core.BlockPos here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
            x = here.getX();
            z = here.getZ();
        }
        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 2; cz++) level.getChunk((x >> 4) + cx, (z >> 4) + cz);
        }
        return groundAt(level, x, z);
    }

    private static float yawOf(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getEntity() == null ? 0.0F : ctx.getSource().getEntity().getYRot();
    }

    /** /village found board [x z]: the board of a village to be founded, as setting a spawner down puts it up. */
    private static int foundBoard(CommandContext<CommandSourceStack> ctx, boolean given) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos spot = foundingSpot(ctx, given);
        if (Villages.nearest(level, spot, Villages.VILLAGE_RANGE * 2) != null) {
            ctx.getSource().sendFailure(Component.literal("There is a village near there already."));
            return 0;
        }
        com.jrpetty.mcassistant.entity.Founding.Outcome o =
            com.jrpetty.mcassistant.entity.Founding.propose(level, spot, ctx.getSource().getPlayer(), yawOf(ctx));
        if (!o.ok() || o.board() == null) {
            ctx.getSource().sendFailure(Component.literal(o.message()));
            return 0;
        }
        net.minecraft.core.BlockPos b = o.board();
        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(b);
        String facing = st.hasProperty(com.jrpetty.mcassistant.block.VillageBoardBlock.FACING)
            ? st.getValue(com.jrpetty.mcassistant.block.VillageBoardBlock.FACING).getName() : "south";
        ctx.getSource().sendSuccess(() -> Component.literal("FOUND-BOARD " + b.getX() + " " + b.getY() + " " + b.getZ()
            + " facing " + facing + " for a village at " + spot.getX() + " " + spot.getY() + " " + spot.getZ() + ". "
            + o.message()), false);
        return 1;
    }

    /** /village found screen [count]: the founding screen of the waiting board nearest you, as right-clicking it opens it. */
    private static int foundScreen(CommandContext<CommandSourceStack> ctx, int count) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Only a player can be shown the founding screen."));
            return 0;
        }
        net.minecraft.core.BlockPos b = com.jrpetty.mcassistant.entity.Founding.boardNear(player.serverLevel(),
            player.blockPosition(), Villages.VILLAGE_RANGE);
        if (b == null || !(player.serverLevel().getBlockEntity(b) instanceof com.jrpetty.mcassistant.block.VillageBoardBlockEntity be)
                || be.founding() != com.jrpetty.mcassistant.entity.Founding.PENDING) {
            ctx.getSource().sendFailure(Component.literal("No board near you is waiting for a village to be founded."));
            return 0;
        }
        com.jrpetty.mcassistant.entity.Founding.offer(player, be, count);
        ctx.getSource().sendSuccess(() -> Component.literal("The founding screen of the board at "
            + b.getX() + " " + b.getY() + " " + b.getZ() + "."), false);
        return 1;
    }

    /**
     * /village found <count> [x z]: exactly what the founding screen's Confirm and spawn does — on the
     * board waiting there, or on one put up for it now — so scripts and tests found a village the way
     * a player does.
     */
    private static int found(CommandContext<CommandSourceStack> ctx, boolean given) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        int count = IntegerArgumentType.getInteger(ctx, "count");
        net.minecraft.core.BlockPos spot = foundingSpot(ctx, given);
        if (Villages.nearest(level, spot, Villages.VILLAGE_RANGE * 2) != null) {
            ctx.getSource().sendFailure(Component.literal("There is a village near there already."));
            return 0;
        }
        net.minecraft.core.BlockPos board = com.jrpetty.mcassistant.entity.Founding.boardNear(level, spot, Villages.VILLAGE_RANGE * 2);
        if (board == null) {
            com.jrpetty.mcassistant.entity.Founding.Outcome o =
                com.jrpetty.mcassistant.entity.Founding.propose(level, spot, ctx.getSource().getPlayer(), yawOf(ctx));
            if (!o.ok()) {
                ctx.getSource().sendFailure(Component.literal(o.message()));
                return 0;
            }
            board = o.board();
        }
        com.jrpetty.mcassistant.entity.Founding.Outcome c =
            com.jrpetty.mcassistant.entity.Founding.confirm(level, board, count, null);
        if (!c.ok()) {
            ctx.getSource().sendFailure(Component.literal(c.message()));
            return 0;
        }
        int folk = com.jrpetty.mcassistant.entity.Founding.allowed(count);
        int across = 2 * com.jrpetty.mcassistant.village.FoundingPlan.coreRadius(folk) + 1;
        net.minecraft.core.BlockPos b = board;
        ctx.getSource().sendSuccess(() -> Component.literal("FOUNDING " + spot.getX() + " " + spot.getY() + " " + spot.getZ()
            + ": " + folk + " folk, about " + across + " by " + across + " blocks made level; the board at "
            + b.getX() + " " + b.getY() + " " + b.getZ() + ". " + c.message()), false);
        return folk;
    }

    /** /village found status: every founding waiting or under way, one line each. */
    private static int foundStatus(CommandContext<CommandSourceStack> ctx) {
        java.util.List<String> lines = com.jrpetty.mcassistant.entity.Founding.status(ctx.getSource().getServer());
        if (lines.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No village is waiting to be founded, or being founded."), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.join(" | ", lines)), false);
        return lines.size();
    }

    /**
     * How flat the ground is round a spot, measured: the top of the earth in every column within
     * the radius (looking through plants), how many stand at the commonest height and how many off
     * it, and the first few that are off with what tops them. For the smoke's check of a founding.
     */
    private static int foundGround(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        int cx = IntegerArgumentType.getInteger(ctx, "x"), cz = IntegerArgumentType.getInteger(ctx, "z");
        int r = IntegerArgumentType.getInteger(ctx, "radius");
        java.util.TreeMap<Integer, Integer> heights = new java.util.TreeMap<>();
        java.util.Map<Long, Integer> at = new java.util.HashMap<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (com.jrpetty.mcassistant.village.FoundingPlan.reach(dx, dz) > r) continue;
                int y = com.jrpetty.mcassistant.entity.Terraform.groundY(level, cx + dx, cz + dz);
                if (y == Integer.MIN_VALUE) continue;
                heights.merge(y, 1, Integer::sum);
                at.put(net.minecraft.core.BlockPos.asLong(cx + dx, 0, cz + dz), y);
            }
        }
        if (heights.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("None of that ground is loaded."));
            return 0;
        }
        int mode = heights.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).get().getKey();
        int all = at.size(), on = heights.get(mode);
        StringBuilder sb = new StringBuilder("GROUND " + all + " columns, " + on + " at y=" + mode + ", " + (all - on) + " off it");
        StringBuilder spread = new StringBuilder();
        for (java.util.Map.Entry<Integer, Integer> e : heights.entrySet()) {
            if (spread.length() > 0) spread.append(' ');
            spread.append("y").append(e.getKey()).append(':').append(e.getValue());
        }
        sb.append(" | heights ").append(spread);
        int shown = 0;
        for (java.util.Map.Entry<Long, Integer> e : at.entrySet()) {
            if (e.getValue() == mode || shown >= 6) continue;
            net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.of(e.getKey());
            net.minecraft.core.BlockPos top = new net.minecraft.core.BlockPos(p.getX(), e.getValue(), p.getZ());
            sb.append(shown == 0 ? " | off: " : "; ").append(p.getX()).append(',').append(p.getZ()).append(" y=").append(e.getValue())
                .append(' ').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(top).getBlock()).getPath());
            shown++;
        }
        String out = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return all - on;
    }

    private static int raiseMany(CommandContext<CommandSourceStack> ctx,
                                 net.minecraft.server.level.ServerLevel level,
                                 net.minecraft.core.BlockPos ground, float yaw, int count) {
        // Scattered, not stacked: see VillageFolkSpawnerBlock.raiseParty.
        int stood = VillageFolkSpawnerBlock.raiseParty(level, ground, yaw, count);
        if (stood == 0) {
            ctx.getSource().sendFailure(Component.literal(
                "Nobody could be settled here — the village is at its cap."));
            return 0;
        }
        Villages.Village v = Villages.nearest(level, ground, Villages.VILLAGE_RANGE * 2);
        final int raised = stood;
        final int total = v == null ? stood : Villages.headcount(v.id());
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Stood " + raised + " up. This village is now " + total + " strong."), false);
        return stood;
    }

    /** One line per folk of the nearest village: who, doing what, and why not more. */
    private static int folk(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("No village yet."), false);
            return 0;
        }
        java.util.List<AssistantEntity> crew = Villages.folkOf(v.id());
        ctx.getSource().sendSuccess(() -> Component.literal(
            crew.size() + " folk of the village at " + v_centre(crew, level)), false);
        for (AssistantEntity a : crew) {
            final String line = a.debugLine();
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return crew.size();
    }

    /**
     * Who lives here as people rather than as workers: each folk's trade and
     * temperament, its partner, its friends and anyone it does not get on with, and
     * its family — and above them, how the village hangs together.
     */
    private static int stats(CommandContext<CommandSourceStack> ctx, int page) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
            net.minecraft.nbt.CompoundTag books = com.jrpetty.mcassistant.entity.Annals.snapshot(level, v);
            if (page >= 0) books.putInt("tab", page);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
            return 1;
        }
        // From the console: the reading of what drives it, in words.
        net.minecraft.nbt.CompoundTag t = com.jrpetty.mcassistant.entity.Annals.snapshot(level, v);
        net.minecraft.nbt.ListTag d = t.getList("drivers", net.minecraft.nbt.Tag.TAG_STRING);
        StringBuilder sb = new StringBuilder("STATS " + Villages.name(v.id()) + " (" + t.getIntArray("days").length + " days in the books)");
        for (int i = 0; i < d.size(); i++) sb.append(" | ").append(d.getString(i));
        net.minecraft.nbt.CompoundTag so = t.getCompound("society");
        sb.append(" | Society: ").append(so.getInt("grown")).append(" grown, ").append(so.getInt("children")).append(" children, ")
            .append(so.getInt("old")).append(" old; ").append(so.getInt("couples")).append(" couples, ").append(so.getInt("households"))
            .append(" households; purses spread Gini ").append(so.getInt("gini")).append(", middle purse ").append(so.getInt("median")).append('c');
        java.util.List<net.minecraft.nbt.CompoundTag> jobs = new java.util.ArrayList<>();
        net.minecraft.nbt.ListTag jl = t.getList("jobs", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < jl.size(); i++) jobs.add(jl.getCompound(i));
        jobs.sort((a, b) -> Integer.compare(b.getInt("week"), a.getInt("week")));
        sb.append(" | Week's output by trade:");
        for (int i = 0; i < Math.min(8, jobs.size()); i++) {
            net.minecraft.nbt.CompoundTag j = jobs.get(i);
            sb.append(i == 0 ? " " : ", ").append(j.getString("title")).append(' ').append(j.getInt("share")).append("% (")
                .append(j.getInt("hands")).append(" hands, ").append(j.getInt("per_head")).append("c a hand a day)");
        }
        sb.append(" | Buildings: ").append(t.getList("buildings", net.minecraft.nbt.Tag.TAG_COMPOUND).size())
            .append("; next: ").append(String.join(", ", t.getList("queue", net.minecraft.nbt.Tag.TAG_STRING).stream().limit(4).map(net.minecraft.nbt.Tag::getAsString).toList()));
        String line = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return d.size();
    }

    /** Every civic's key, for the command's suggestions: "common_tools", "crop_rotation"... */
    private static java.util.List<String> civicKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (com.jrpetty.mcassistant.entity.CityTree.Civic c : com.jrpetty.mcassistant.entity.CityTree.Civic.values()) keys.add(c.key());
        return keys;
    }

    /** The village the command means: the nearest, or the first there is. */
    @javax.annotation.Nullable
    private static Villages.Village villageHere(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        return v;
    }

    /** /village level: the ground round the nearest town levelled, its folk kept from harm while it moves. */
    private static int levelGround(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p != null ? !TimeSpeed.mayChange(p) : !src.hasPermission(2)) {
            src.sendFailure(Component.literal("Only an operator (or the owner of this world) can have a town's ground levelled."));
            return 0;
        }
        Villages.Village v = villageHere(ctx);
        if (v == null) {
            src.sendFailure(Component.literal("No village yet."));
            return 0;
        }
        com.jrpetty.mcassistant.entity.Founding.Outcome o =
            com.jrpetty.mcassistant.entity.Founding.levelExisting(src.getLevel(), v, p);
        if (!o.ok()) {
            src.sendFailure(Component.literal(o.message()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(o.message()), true);
        return 1;
    }

    /** /village sights [what]: the newer sights of the town and where they are; with a what, that one made now. */
    private static int sights(CommandContext<CommandSourceStack> ctx, @javax.annotation.Nullable String what) {
        Villages.Village v = villageHere(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        java.util.List<String> lines = what == null ? com.jrpetty.mcassistant.entity.Sights.lines(level, v)
            : com.jrpetty.mcassistant.entity.Sights.now(level, v, what);
        for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
        return lines.size();
    }

    // ------------------------------------------------------------------ the town's calendar

    /** /village bell: where the town bell hangs (a "BELL-AT x y z" for scripts), today's bells and who rang them. */
    private static int bell(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = villageHere(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        String said = com.jrpetty.mcassistant.entity.TownBell.status(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }

    /** /village bell ring dawn|noon|dusk (ops): the bell rung now by whoever would ring it, and the town answers it. */
    private static int ringBell(CommandContext<CommandSourceStack> ctx, boolean walk) {
        Villages.Village v = villageHere(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        String which = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "peal").toUpperCase(java.util.Locale.ROOT);
        com.jrpetty.mcassistant.entity.TownBell.Peal peal;
        try {
            peal = com.jrpetty.mcassistant.entity.TownBell.Peal.valueOf(which);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Which bell? dawn, noon or dusk."));
            return 0;
        }
        String said = walk ? com.jrpetty.mcassistant.entity.TownBell.callNow(ctx.getSource().getLevel(), v, peal)
            : com.jrpetty.mcassistant.entity.TownBell.ringNow(ctx.getSource().getLevel(), v, peal);
        ctx.getSource().sendSuccess(() -> Component.literal("BELL " + Villages.name(v.id()) + ": " + said), true);
        return 1;
    }

    /** /village founding: the town's next Founding Day; founding now (ops): kept this minute, before the board. */
    private static int foundingDay(CommandContext<CommandSourceStack> ctx, boolean now) {
        Villages.Village v = villageHere(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        long day = level.getDayTime() / 24000L;
        if (!now) {
            String line = com.jrpetty.mcassistant.entity.FoundingDay.founded(v.id()) < 0 ? "No history yet, so no Founding Day."
                : "FOUNDING " + Villages.name(v.id()) + ": founded on day " + (com.jrpetty.mcassistant.entity.FoundingDay.founded(v.id()) + 1)
                + "; the town's year is " + com.jrpetty.mcassistant.entity.TownCalendar.YEAR_DAYS + " days. "
                + com.jrpetty.mcassistant.entity.TownCalendar.book(level, v.id()).stream().filter(l -> l.startsWith("Founding Day"))
                    .findFirst().orElse("");
            java.util.List<String> read = com.jrpetty.mcassistant.entity.FoundingDay.readOut(v.id());
            String all = line + (read.isEmpty() ? "" : " Read out last time: " + String.join(" / ", read));
            ctx.getSource().sendSuccess(() -> Component.literal(all), false);
            return 1;
        }
        boolean started = com.jrpetty.mcassistant.entity.Assemblies.startNow(level, v,
            com.jrpetty.mcassistant.entity.Assemblies.Kind.FOUNDING);
        if (!started) {
            ctx.getSource().sendFailure(Component.literal("FOUNDING " + Villages.name(v.id()) + ": nobody to gather (or something under way): "
                + com.jrpetty.mcassistant.entity.Assemblies.debug(v.id())));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("FOUNDING " + Villages.name(v.id()) + ": Founding Day is called, before the board."), true);
        return 1;
    }

    /** /village birthdays: whose birthday falls this week; birthdays now &lt;name&gt; (ops): that folk's kept now. */
    private static int birthdays(CommandContext<CommandSourceStack> ctx, @javax.annotation.Nullable String name) {
        Villages.Village v = villageHere(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        long day = level.getDayTime() / 24000L;
        if (name == null) {
            java.util.List<String> week = com.jrpetty.mcassistant.entity.Birthdays.thisWeek(level, v.id(), day);
            String said = "BIRTHDAYS " + Villages.name(v.id()) + ": " + (week.isEmpty() ? "none this week." : String.join("; ", week) + ".");
            ctx.getSource().sendSuccess(() -> Component.literal(said), false);
            return week.size();
        }
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.displayNameCap().equalsIgnoreCase(name.trim())) continue;
            com.jrpetty.mcassistant.entity.Birthdays.celebrate(level, v, f, day);
            ctx.getSource().sendSuccess(() -> Component.literal("BIRTHDAYS " + f.displayNameCap() + " is keeping a birthday today."), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("Nobody called " + name + " in " + Villages.name(v.id()) + "."));
        return 0;
    }

    /** /village research: the city's research, the whole tree, a line a branch. */
    private static int research(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = villageHere(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        java.util.List<String> lines = com.jrpetty.mcassistant.entity.CityTree.lines(v.id());
        for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
        return lines.size();
    }

    /** /village research pick|grant &lt;civic&gt; (ops): study it now, or have it done at once. */
    private static int research(CommandContext<CommandSourceStack> ctx, boolean grant) {
        Villages.Village v = villageHere(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        String key = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "civic");
        com.jrpetty.mcassistant.entity.CityTree.Civic c = com.jrpetty.mcassistant.entity.CityTree.byKey(key);
        if (c == null) {
            ctx.getSource().sendFailure(Component.literal("No civic called " + key + ". One of: " + String.join(", ", civicKeys())));
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        long day = level.getDayTime() / 24000L;
        String said = grant ? com.jrpetty.mcassistant.entity.CityTree.grant(level, v.id(), c, day)
            : com.jrpetty.mcassistant.entity.CityTree.pick(level, v.id(), c, day);
        boolean ok = grant ? com.jrpetty.mcassistant.entity.CityTree.has(v.id(), c) : c == com.jrpetty.mcassistant.entity.CityTree.current(v.id());
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("RESEARCH " + Villages.name(v.id()) + ": " + said));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("RESEARCH " + Villages.name(v.id()) + ": " + said), true);
        return 1;
    }

    private static int houses(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
            ctx.getSource().sendFailure(Component.literal("Only a player can ask after houses."));
            return 0;
        }
        java.util.List<String> lines = com.jrpetty.mcassistant.entity.Homes.list(ctx.getSource().getLevel(), p);
        ctx.getSource().sendSuccess(() -> Component.literal("HOUSES " + String.join(" | ", lines)), false);
        return lines.size();
    }

    /** [flats] /village flats: the nearest village's blocks of flats and who lives in each flat. */
    private static int flats(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        java.util.List<String> lines = com.jrpetty.mcassistant.entity.Flats.list(ctx.getSource().getLevel(), at);
        ctx.getSource().sendSuccess(() -> Component.literal("FLATS " + String.join(" | ", lines)), false);
        return lines.size();
    }

    /** [flats] /village flats stage: a furnished block of flats set out at the spot, its door to the south. */
    private static int flatsStage(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        String where = com.jrpetty.mcassistant.entity.Flats.stage(ctx.getSource().getLevel(), at);
        ctx.getSource().sendSuccess(() -> Component.literal("FLATSTAGE " + where), false);
        return 1;
    }

    private static int house(CommandContext<CommandSourceStack> ctx, String what, int coins) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
            ctx.getSource().sendFailure(Component.literal("Only a player can buy or let a house."));
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        String said = switch (what) {
            case "buy" -> com.jrpetty.mcassistant.entity.Homes.playerBuys(level, p);
            case "let" -> com.jrpetty.mcassistant.entity.Homes.playerLets(level, p, coins);
            default -> com.jrpetty.mcassistant.entity.Homes.playerCollects(level, p);
        };
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }

    // ------------------------------------------------------------------ knacks (FolkSkills)

    /**
     * /village knacks: every folk of the nearest village, a line each (its points, what it chose, on
     * what day and why); /village knacks Tansy: that folk, and the knacks still open to it, the one it
     * wants most first.
     */
    private static int knacks(CommandContext<CommandSourceStack> ctx, String name) {
        if (!name.isBlank()) {
            VillageFolkEntity f = folkNamed(ctx, name);
            if (f == null) {
                ctx.getSource().sendFailure(Component.literal("No folk called " + name.trim() + " is about."));
                return 0;
            }
            final String line = com.jrpetty.mcassistant.entity.FolkSkills.describe(f);
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            StringBuilder open = new StringBuilder("Open to it: ");
            int n = 0;
            for (com.jrpetty.mcassistant.entity.FolkSkills.Pick p : com.jrpetty.mcassistant.entity.FolkSkills.ranked(f)) {
                if (n++ >= 8) break;
                if (n > 1) open.append("; ");
                open.append(p.knack().title).append(" (").append(p.knack().key).append(", ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", p.score())).append(": ").append(p.why()).append(')');
            }
            final String opens = n == 0 ? "Nothing more open to it." : open.toString();
            ctx.getSource().sendSuccess(() -> Component.literal(opens), false);
            return 1;
        }
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        java.util.List<VillageFolkEntity> folk = new java.util.ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && !f.isShowcase()) folk.add(f);
        int chosen = 0;
        for (VillageFolkEntity f : folk) chosen += com.jrpetty.mcassistant.entity.FolkSkills.spent(f);
        final String head = "Knacks in " + Villages.name(v.id()) + ": " + folk.size() + " folk, " + chosen + " knacks chosen";
        ctx.getSource().sendSuccess(() -> Component.literal(head), false);
        for (VillageFolkEntity f : folk) {
            final String line = com.jrpetty.mcassistant.entity.FolkSkills.describe(f);
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return folk.size();
    }

    /** /village knacks grant Tansy nest_egg (operators): the knack given, as though it chose it, with what comes of it. */
    private static int grantKnack(CommandContext<CommandSourceStack> ctx, String name, String key) {
        VillageFolkEntity f = folkNamed(ctx, name);
        if (f == null || !(f.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("No folk called " + name.trim() + " is about."));
            return 0;
        }
        com.jrpetty.mcassistant.entity.FolkSkills.Knack k = com.jrpetty.mcassistant.entity.FolkSkills.Knack.byKey(key);
        if (k == null) {
            StringBuilder keys = new StringBuilder();
            for (com.jrpetty.mcassistant.entity.FolkSkills.Knack n : com.jrpetty.mcassistant.entity.FolkSkills.Knack.values()) {
                if (keys.length() > 0) keys.append(", ");
                keys.append(n.key);
            }
            ctx.getSource().sendFailure(Component.literal("No knack called " + key + ". The knacks: " + keys));
            return 0;
        }
        if (!com.jrpetty.mcassistant.entity.FolkSkills.grant(level, f, k)) {
            ctx.getSource().sendFailure(Component.literal(f.displayNameCap() + " has " + k.title + " already."));
            return 0;
        }
        final String line = "Granted " + k.title + ". " + com.jrpetty.mcassistant.entity.FolkSkills.describe(f);
        ctx.getSource().sendSuccess(() -> Component.literal(line), true);
        return 1;
    }

    /** The folk of that name (the nearest, if more than one), in any village the game knows; or null. */
    @javax.annotation.Nullable
    private static VillageFolkEntity folkNamed(CommandContext<CommandSourceStack> ctx, String name) {
        String want = name.trim().toLowerCase(java.util.Locale.ROOT);
        net.minecraft.world.phys.Vec3 here = ctx.getSource().getPosition();
        VillageFolkEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Villages.Village v : Villages.every()) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (!(a instanceof VillageFolkEntity f) || !f.isAlive()) continue;
                String n = f.displayNameCap().toLowerCase(java.util.Locale.ROOT);
                if (!n.equals(want) && !n.replace('_', ' ').equals(want)) continue;
                double d = f.position().distanceToSqr(here);
                if (d < bestD) { bestD = d; best = f; }
            }
        }
        return best;
    }

    private static int people(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("No village yet."), false);
            return 0;
        }
        java.util.List<VillageFolkEntity> folk = new java.util.ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f) folk.add(f);
        final String summary = community(folk);
        ctx.getSource().sendSuccess(() -> Component.literal(summary), false);
        for (VillageFolkEntity f : folk) {
            f.ensurePersona();
            final String line = f.life().describe(f.displayNameCap(), f.stationTask().title)
                + " Feeling " + com.jrpetty.mcassistant.entity.Persona.moodWord(f.persona().mood())
                + "; loves " + f.persona().hobby().doing + "; hopes " + f.persona().ambition().hope
                + (f.persona().ambitionMet() ? " (and did)" : "") + "."
                + (f.isBaby() ? "" : " " + capitalFirst(com.jrpetty.mcassistant.entity.Values.describe(f)) + ".");
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return folk.size();
    }

    /** "12 folk: 3 couples, 9 friendships, 1 rivalry; 4 children born here." */
    public static String community(java.util.List<VillageFolkEntity> folk) {
        int couples = 0, friendships = 0, rivalries = 0, born = 0;
        java.util.Set<java.util.UUID> here = new java.util.HashSet<>();
        for (VillageFolkEntity f : folk) here.add(f.getUUID());
        for (VillageFolkEntity f : folk) {
            com.jrpetty.mcassistant.entity.Social.Life l = f.life();
            if (l.partner() != null && here.contains(l.partner())
                    && f.getUUID().compareTo(l.partner()) < 0) couples++;
            friendships += l.friends().size();
            rivalries += l.rivals().size();
            if (!l.parents().isEmpty()) born++;
        }
        return folk.size() + " folk: " + couples + (couples == 1 ? " couple, " : " couples, ")
            + friendships / 2 + (friendships / 2 == 1 ? " friendship, " : " friendships, ")
            + rivalries / 2 + (rivalries / 2 == 1 ? " rivalry" : " rivalries")
            + "; " + born + " born here.";
    }

    private static String v_centre(java.util.List<AssistantEntity> crew,
                                   net.minecraft.server.level.ServerLevel level) {
        if (crew.isEmpty()) return "?";
        Villages.Village v = Villages.get(crew.get(0).ownerId());
        return v == null ? "?" : v.centre().getX() + ", " + v.centre().getZ();
    }

    /** Where this world will found villages of its own near here. */
    private static int anchors(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here =
            net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        java.util.List<net.minecraft.core.BlockPos> sites =
            com.jrpetty.mcassistant.VillageSpawner.anchorsNear(level, here, 12);
        if (sites.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No village sites within reach."), false);
            return 0;
        }
        int shown = 0;
        for (net.minecraft.core.BlockPos p : sites) {
            if (shown++ >= 8) break;
            final String line = "Village site at " + p.getX() + ", " + p.getZ()
                + " (" + (int) Math.sqrt(p.distSqr(new net.minecraft.core.BlockPos(here.getX(), p.getY(), here.getZ())))
                + " blocks away" + (AssistantConfig.naturalVillages() ? "" : "; natural villages are OFF") + ")";
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return sites.size();
    }

    /** Every village the game knows of, one line each — where they are, how many
     *  live there, how far along they are. */
    private static int list(CommandContext<CommandSourceStack> ctx) {
        java.util.List<Villages.Village> all = Villages.every();
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No villages yet."), false);
            return 0;
        }
        for (Villages.Village v : all) {
            final String line = "Village at " + v.centre().getX() + ", " + v.centre().getZ()
                + " (" + Villages.name(v.id()) + ") — " + Villages.headcount(v.id()) + " folk (" + Villages.loadedCount(v.id())
                + " loaded), " + Villages.ageOf(v.id()).label + ", built " + Villages.builtList(v.id());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return all.size();
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, int count) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal(
                "Only a player can do that — from the console use /village spawnat <x> <z> [count]."));
            return 0;
        }
        // A couple of blocks AHEAD of the player, on the ground — never on
        // top of them, and never at head height where a chest would go.
        net.minecraft.core.BlockPos ahead = player.blockPosition().relative(player.getDirection(), 2);
        net.minecraft.core.BlockPos ground = groundAt(player.serverLevel(), ahead.getX(), ahead.getZ());
        if (Villages.nearest(player.serverLevel(), ground, Villages.VILLAGE_RANGE * 2) == null) {
            // A new village has its ground made ready first, as one founded at a board does (entity/Founding);
            // its folk come when the heart of it is level. (spawnat, for the console and scripts, founds at once.)
            com.jrpetty.mcassistant.entity.Founding.Outcome o = com.jrpetty.mcassistant.entity.Founding.foundNow(
                player.serverLevel(), ground, Math.max(com.jrpetty.mcassistant.village.FoundingPlan.MIN_FOLK, count),
                player.getYRot(), player);
            if (!o.ok()) {
                ctx.getSource().sendFailure(Component.literal(o.message()));
                return 0;
            }
            ctx.getSource().sendSuccess(() -> Component.literal(o.message()), false);
            return count;
        }
        return raiseMany(ctx, player.serverLevel(), ground, player.getYRot(), count);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        // From wherever the command was run — a player, a command block, or
        // the console (which has no player and used to get NOTHING back).
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here =
            net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && ctx.getSource().getPlayer() == null && !Villages.every().isEmpty()) {
            v = Villages.every().get(0);
        }
        if (v == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("No village within reach."), false);
            return 0;
        }
        String text = statusText(level, v);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** /village wages (who is paid what, best paid first) and /village economy (what it makes, sells, is worth). */
    /** The nearest village (any, from the console), or null with a word said. */
    @javax.annotation.Nullable
    private static Villages.Village schoolVillage(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && ctx.getSource().getPlayer() == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    /** /village school: the nearest village's school, in chat. */
    private static int school(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = schoolVillage(ctx);
        if (v == null) return 0;
        java.util.List<String> lines = com.jrpetty.mcassistant.entity.School.lines(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    /** /village school page: the town's books, opened at the School page. */
    private static int schoolPage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = schoolVillage(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return school(ctx);
        net.minecraft.nbt.CompoundTag books = com.jrpetty.mcassistant.entity.Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "School");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    /** /village school lesson: lessons now at the nearest village's school, whatever the hour, for two minutes. */
    private static int schoolLesson(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = schoolVillage(ctx);
        if (v == null) return 0;
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        if (!com.jrpetty.mcassistant.entity.School.stands(v.id())) {
            ctx.getSource().sendFailure(Component.literal(Villages.name(v.id()) + " has no schoolhouse yet."));
            return 0;
        }
        com.jrpetty.mcassistant.entity.School.callLesson(level, v.id(), 2400);
        com.jrpetty.mcassistant.entity.VillageFolkEntity t = com.jrpetty.mcassistant.entity.School.teacher(level, v.id(), true);
        ctx.getSource().sendSuccess(() -> Component.literal("LESSON at " + Villages.name(v.id()) + "'s school for two minutes; teacher "
            + (t == null ? "none fit to teach" : t.displayNameCap())), false);
        return 1;
    }

    private static int page(CommandContext<CommandSourceStack> ctx, int which) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && ctx.getSource().getPlayer() == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("No village within reach."), false);
            return 0;
        }
        String text = which == 2 ? com.jrpetty.mcassistant.entity.Wealth.wagesPage(level, v)
            : which == 4 ? com.jrpetty.mcassistant.entity.Stockroom.page(level, v)
            : which == 5 ? com.jrpetty.mcassistant.entity.Storekeeping.page(level, v)
            : which == 6 ? com.jrpetty.mcassistant.entity.Stables.page(level, v)
            : com.jrpetty.mcassistant.entity.Economy.page(level, v);
        String title = Villages.name(v.id()) + (which == 2 ? " — wages" : which == 4 ? " — the sellers' books"
            : which == 5 ? " — the storehouse's books" : which == 6 ? " — the stable" : " — economy");
        ctx.getSource().sendSuccess(() -> Component.literal(title + "\n" + text), false);
        return 1;
    }

    /** /village sweeper [appoint]: the town's sweeping in words; with appoint, the nearest grown folk made its
     *  sweeper first ("SWEEPER name x y z", for the client smoke). */
    private static int sweeper(CommandContext<CommandSourceStack> ctx, boolean appoint) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = villageHere(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village within reach."));
            return 0;
        }
        String said = "";
        if (appoint) {
            com.jrpetty.mcassistant.entity.VillageFolkEntity f = com.jrpetty.mcassistant.entity.Sweepers.appointNow(level, v, here);
            if (f == null) {
                ctx.getSource().sendFailure(Component.literal("Nobody grown in " + Villages.name(v.id()) + " to take up the broom."));
                return 0;
            }
            said = "SWEEPER " + f.displayNameCap() + " " + f.getBlockX() + " " + f.getBlockY() + " " + f.getBlockZ()
                + (com.jrpetty.mcassistant.entity.Storehouses.stands(v.id()) ? "" : " (no storehouse yet: nowhere to sweep to)") + "\n";
        }
        String text = said + com.jrpetty.mcassistant.entity.Sweepers.page(level, v);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int speedNow(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.MinecraftServer server = ctx.getSource().getServer();
        int f = TimeSpeed.factor(server);
        int a = TimeSpeed.actualX10();
        ctx.getSource().sendSuccess(() -> Component.literal(f == 1
            ? "Time runs at its normal pace. /village speed 16 (or max) makes it run faster; so do the ] and [ keys."
            : "Time is set to run " + TimeSpeed.label(f) + "; the server is managing " + (a / 10) + "." + (a % 10)
                + "\u00d7. /village speed normal puts it back."), false);
        return f;
    }

    private static int speed(CommandContext<CommandSourceStack> ctx, int times) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p != null && !TimeSpeed.mayChange(p)) {
            src.sendFailure(Component.literal("Only an operator (or the owner of this world) can change how fast time runs."));
            return 0;
        }
        if (p == null && !src.hasPermission(2)) {
            src.sendFailure(Component.literal("Only an operator can change how fast time runs."));
            return 0;
        }
        TimeSpeed.set(src.getServer(), times, p == null ? null : p.getName().getString());
        src.sendSuccess(() -> Component.literal(times == 1 ? "Time runs at its normal pace."
            : "Time now runs " + TimeSpeed.label(times) + ". Watch the corner of the screen for how fast the server really manages."), true);
        return times;
    }

    private static int news(CommandContext<CommandSourceStack> ctx, boolean on) {
        net.minecraft.server.level.ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal("Only a player can take the morning news."));
            return 0;
        }
        com.jrpetty.mcassistant.entity.News.choose(p, on);
        ctx.getSource().sendSuccess(() -> Component.literal(on
            ? "You'll hear the morning news of any village within 256 blocks, once a morning."
            : "No more morning news."), false);
        return 1;
    }

    /** Everything /village status says about a village, as one line (the village screen shows it too). */
    public static String statusText(net.minecraft.server.level.ServerLevel level, Villages.Village v) {
        StringBuilder sb = new StringBuilder();
        sb.append("Village at ").append(v.centre().getX()).append(", ").append(v.centre().getZ())
          .append(" (").append(Villages.name(v.id()))
          .append(Villages.elderName(v.id()).isEmpty() ? "" : ", elder " + Villages.elderName(v.id())).append(") — ").append(Villages.headcount(v.id())).append(" folk (")
          .append(Villages.loadedCount(v.id())).append(" loaded), ")
          .append(Villages.ageOf(v.id()).label).append('.');
        java.util.Map<AssistantEntity.StationTask, Integer> trades =
            new java.util.EnumMap<>(AssistantEntity.StationTask.class);
        int idle = 0, children = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.isBaby()) children++;
            else if (a.stationTask() == AssistantEntity.StationTask.NONE) idle++;
            else trades.merge(a.stationTask(), 1, Integer::sum);
        }
        sb.append(" Trades:");
        trades.forEach((t, n) -> sb.append(' ').append(n).append(' ').append(t.title.toLowerCase()));
        if (idle > 0) sb.append(", ").append(idle).append(" still choosing");
        if (children > 0) sb.append(", ").append(children).append(children == 1 ? " child" : " children");
        sb.append(". Stores:");
        for (Villages.Task t : Villages.Task.values()) {
            if (t == Villages.Task.BUILD || t == Villages.Task.HANDS || t == Villages.Task.NONE) continue;
            sb.append(' ').append(t.name().toLowerCase()).append(' ').append(
                Villages.stock(level, v.centre(), t, Villages.storesRadius(v.id())));
        }
        sb.append(". Built: ").append(Villages.builtList(v.id()));
        sb.append(". Rank: ").append(Villages.rank(v.id()).label).append(" (next, ").append(Villages.nextRankNote(v.id())).append(")");
        sb.append(". Land: ").append(com.jrpetty.mcassistant.entity.Homeland.line(v.id()));
        sb.append(". Room for ").append(Villages.housing(v.id()));
        // Who has a bed, and (at night) who is in it.
        int folkNow = 0, bedded = 0, asleep = 0;
        java.util.List<String> late = new java.util.ArrayList<>();
        boolean night = level.isNight();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            folkNow++;
            net.minecraft.core.BlockPos bed = a.bedPos();
            boolean has = bed != null && level.getBlockState(bed).getBlock() instanceof net.minecraft.world.level.block.BedBlock;
            if (has) bedded++;
            if (a.isSleeping()) asleep++;
            else if (night && has && late.size() < 6) {
                // Who is up with a bed to go to, and why.
                String why = a.onShift() ? "on watch"
                    : a.getTarget() != null ? "fighting"
                    : a.peekJob() != null ? a.peekJob().label()
                    : bed.distSqr(a.blockPosition()) > 9 ? "on the way, " + (int) Math.sqrt(bed.distSqr(a.blockPosition())) + " off"
                    : "by its bed";
                late.add(a.displayNameCap() + " (" + why + ")");
            }
        }
        sb.append(". Beds: ").append(bedded).append(" of ").append(folkNow).append(" have one, ")
          .append(asleep).append(" asleep, homes for ").append(Villages.bedsMadeUp(level, v.id()))
          .append(" made up of ").append(Villages.bedsPlanned(v.id()))
          .append(", camp ").append(com.jrpetty.mcassistant.VillageSpawner.campBeds(level, v.centre()).size());
        if (!late.isEmpty()) sb.append("; up: ").append(String.join(", ", late));
        sb.append(". Growing: ").append(Villages.growthNote(level, v.id()));
        {
            long today = level.getDayTime() / 24000L;
            int saved = 0;
            for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f) saved += f.purse();
            int toMarket = com.jrpetty.mcassistant.entity.Market.daysToMarket(v.id(), today);
            sb.append(". Treasury: ").append(com.jrpetty.mcassistant.village.Ledger.coins(v.id())).append(" coins, ")
              .append(saved).append(" in purses; market ").append(toMarket == 0 ? "today" : "in " + toMarket + " days");
            sb.append(". Economy: ").append(com.jrpetty.mcassistant.entity.Economy.line(v.id()));
            String best = com.jrpetty.mcassistant.entity.Wealth.bestPaid(v.id(), 3);
            if (!best.isEmpty()) sb.append(". Best paid: ").append(best).append(" a day (").append(
                com.jrpetty.mcassistant.entity.Wealth.standingWords(com.jrpetty.mcassistant.entity.Wealth.standing(v.id()))).append(")");
        }
        sb.append(". Contentment: ").append(com.jrpetty.mcassistant.entity.Contentment.line(level, v.id()));
        {
            long today = level.getDayTime() / 24000L;
            int rest = 0;
            while (rest < 7 && !com.jrpetty.mcassistant.entity.RestDay.today(v.id(), today + rest)) rest++;
            sb.append(". Day of rest: ").append(rest == 0 ? "today" : rest < 7 ? "in " + rest + " days" : "not yet (from the Stone Age, after a week)");
        }
        {
            String alarm = com.jrpetty.mcassistant.entity.Raids.why(v.id());
            int gates = com.jrpetty.mcassistant.entity.Watch.gates(level, v.id()).size();
            int posts = com.jrpetty.mcassistant.entity.Watch.posts(level, v.id()).size();
            sb.append(". Watch: ").append(alarm != null ? "THE BELL IS RINGING (" + alarm + ")" : "quiet")
              .append(", ").append(gates).append(" gates ").append(com.jrpetty.mcassistant.entity.Watch.isShut(v.id()) ? "shut" : "open")
              .append(", ").append(posts).append(" posts on the wall");
            if ((gates == 0 || posts == 0) && com.jrpetty.mcassistant.entity.Villages.hasBuilt(v.id(), "fortify")) {
                sb.append(" [").append(com.jrpetty.mcassistant.entity.Watch.trouble(level, v.id())).append(']');
            }
        }
        {
            java.util.UUID id = v.id();
            java.util.List<String> council = new java.util.ArrayList<>();
            for (VillageFolkEntity m : com.jrpetty.mcassistant.entity.Council.members(id)) council.add(m.displayNameCap());
            sb.append(". Council: ").append(council.isEmpty() ? "none" : String.join(", ", council));
            com.jrpetty.mcassistant.entity.Orders.Order order = com.jrpetty.mcassistant.entity.Orders.current(id);
            sb.append(". Elder's orders: ").append(order == null ? "none yet" : order.title);
            sb.append(". Leader: ").append(com.jrpetty.mcassistant.entity.Leader.line(id));
            String escort = com.jrpetty.mcassistant.entity.Patrols.escortLine(id);
            if (!escort.isEmpty()) sb.append(", ").append(escort);
            sb.append(". Election: ").append(com.jrpetty.mcassistant.entity.Elections.line(id, level.getDayTime() / 24000L));
            sb.append(". Homes: ").append(com.jrpetty.mcassistant.entity.Homes.line(level, id));
            sb.append(". Look: ").append(com.jrpetty.mcassistant.entity.Palettes.line(id));
            java.util.Map<java.util.UUID, String> citizens = com.jrpetty.mcassistant.village.Ledger.citizens(id);
            if (!citizens.isEmpty()) sb.append("; citizens ").append(String.join(", ", citizens.values()));
            String n = com.jrpetty.mcassistant.entity.Diplomacy.status(id);
            if (n != null) sb.append(". Neighbours: ").append(n);
            java.util.List<String> board = new java.util.ArrayList<>();
            for (var q : com.jrpetty.mcassistant.entity.Quests.postings(id)) board.add(q.words() + " (" + q.reward + ")");
            sb.append(". Quest board: ").append(board.isEmpty() ? "nothing posted" : String.join("; ", board));
            var lent = com.jrpetty.mcassistant.entity.Services.onLoan(id);
            if (!lent.isEmpty()) sb.append(". On loan: ").append(lent);
            sb.append(". Budget: ").append(com.jrpetty.mcassistant.entity.Budget.line(level, id));
            sb.append(". Diplomacy: ").append(com.jrpetty.mcassistant.entity.Envoys.debug(id));
            String abroad = com.jrpetty.mcassistant.entity.Envoys.latest(id);
            if (abroad != null) sb.append("; latest ").append(abroad);
            sb.append(". Scouts: ").append(com.jrpetty.mcassistant.entity.Scouts.debug(id));
            String open = com.jrpetty.mcassistant.entity.Cafe.openLine(level, id);
            if (open != null) sb.append(". Open: ").append(open);
            java.util.List<String> hands = new java.util.ArrayList<>();
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f) {
                    String w = com.jrpetty.mcassistant.entity.TownJobs.doing(f);
                    if (w != null) hands.add(f.displayNameCap() + " " + w);
                }
            }
            sb.append(". Town works: ").append(hands.isEmpty() ? "nobody on them just now" : String.join(", ", hands));
            java.util.Map<String, Integer> tiers = new java.util.TreeMap<>();
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && !f.isBaby()) tiers.merge(com.jrpetty.mcassistant.entity.Wealth.tier(f).name().toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
            }
            sb.append(". Wealth: ").append(tiers);
            String gathering = com.jrpetty.mcassistant.entity.Assemblies.now(id);
            if (gathering != null) sb.append(". Gathering: ").append(gathering);
        }
        java.util.List<VillageFolkEntity> people = new java.util.ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f) people.add(f);
        sb.append(". Community: ").append(community(people));
        if (Villages.renown(v.id()) > 0) sb.append(". Renown ").append(Villages.renown(v.id()));
        long colonies = Villages.builtList(v.id()).stream().filter("colony"::equals).count();
        if (colonies > 0) sb.append(". Colonies founded: ").append(colonies);
        for (java.util.Map.Entry<java.util.UUID, java.util.UUID> link : com.jrpetty.mcassistant.village.Ledger.links().entrySet()) {
            java.util.UUID other = link.getValue().equals(v.id()) ? link.getKey() : link.getKey().equals(v.id()) ? link.getValue() : null;
            if (other == null) continue;
            double done = com.jrpetty.mcassistant.entity.Roads.progress(link.getKey());
            sb.append(". Road to ").append(Villages.name(other)).append(": ")
              .append(done < 0 ? "not begun" : done >= 1 ? "finished" : (int) (done * 100) + "%");
        }
        String next = Villages.nextProject(v.id());
        sb.append(". Next: ").append(Villages.whyBuild(v.id(), next));
        String aside = Villages.setAside(v.id());
        if (!aside.isEmpty()) sb.append(". Set aside: ").append(aside);
        sb.append(". Short of:");
        java.util.List<Villages.Need> needs = Villages.needs(level, v.id());
        if (needs.isEmpty()) sb.append(" nothing — about to come of age.");
        for (Villages.Need n : needs) sb.append(' ').append(n.what()).append(';');
        return sb.toString();
    }

    private static String capitalFirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
