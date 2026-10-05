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
 *   /village status           who lives here, what age, what they are short of
 *   /village lineup           one folk of every trade, dressed, to look at (ops)
 *   /village talk [words]     talk with the nearest folk, as a right-click would (ops)
 *   /village chronicle        the nearest village's history, as a book
 *   /village standing         what every village you have met thinks of you
 *   /village speed 16|max|normal   time runs faster, to watch a village grow (ops / world owner)
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
            .then(Commands.literal("folk").executes(VillageCommands::folk))
            .then(Commands.literal("people").executes(VillageCommands::people))
            .then(Commands.literal("list").executes(VillageCommands::list))
            .then(Commands.literal("anchors").requires(src -> src.hasPermission(2))
                .executes(VillageCommands::anchors))
            .then(Commands.literal("status").executes(VillageCommands::status))
            .then(Commands.literal("wages").executes(ctx -> page(ctx, 2)))
            .then(Commands.literal("economy").executes(ctx -> page(ctx, 3)))
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
            // What every village you have met thinks of you.
            .then(Commands.literal("standing").executes(VillageCommands::standing))
            // How the villages stand with each other: allies, feuds, tribute.
            .then(Commands.literal("relations").executes(VillageCommands::relations))
            // The nearest village's town ledger, as a book: stores, residents, building, shortages.
            .then(Commands.literal("ledger").executes(VillageCommands::ledger))
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
                // A raid on the staged town at night: the watch on the wall, a band at the gate.
                .then(Commands.literal("raid").executes(VillageCommands::showcaseRaid))
                // The staged town's windows lit, as they are after dark, or put out.
                .then(Commands.literal("lights")
                    .then(Commands.literal("on").executes(ctx -> showcaseLights(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> showcaseLights(ctx, false))))));
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
                + (f.persona().ambitionMet() ? " (and did)" : "") + ".";
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
        return raiseMany(ctx, player.serverLevel(), groundAt(player.serverLevel(), ahead.getX(), ahead.getZ()),
            player.getYRot(), count);
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
            : com.jrpetty.mcassistant.entity.Economy.page(level, v);
        String title = Villages.name(v.id()) + (which == 2 ? " — wages" : " — economy");
        ctx.getSource().sendSuccess(() -> Component.literal(title + "\n" + text), false);
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
        sb.append(". Short of:");
        java.util.List<Villages.Need> needs = Villages.needs(level, v.id());
        if (needs.isEmpty()) sb.append(" nothing — about to come of age.");
        for (Villages.Need n : needs) sb.append(' ').append(n.what()).append(';');
        return sb.toString();
    }
}
