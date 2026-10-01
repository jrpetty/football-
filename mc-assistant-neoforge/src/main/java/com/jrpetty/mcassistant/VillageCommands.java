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
            // Talk with the nearest folk, as a right-click would (for scripts and tests).
            .then(Commands.literal("talk").requires(src -> src.hasPermission(2))
                .executes(ctx -> talk(ctx, ""))
                .then(Commands.argument("words", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .executes(ctx -> talk(ctx, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "words")))))
            // One folk of every trade, in its clothes and with its tool, stood in a
            // row to be looked at. Clear them with /kill @e[tag=folk_lineup].
            .then(Commands.literal("lineup").requires(src -> src.hasPermission(2))
                .executes(VillageCommands::lineup)));
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
            double off = (i - (trades.length - 1) / 2.0) * 1.6;
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
        level.getChunk(x >> 4, z >> 4);          // make sure the ground is really there
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
                + " — " + Villages.headcount(v.id()) + " folk (" + Villages.loadedCount(v.id())
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
        StringBuilder sb = new StringBuilder();
        sb.append("Village at ").append(v.centre().getX()).append(", ").append(v.centre().getZ())
          .append(" — ").append(Villages.headcount(v.id())).append(" folk (")
          .append(Villages.loadedCount(v.id())).append(" loaded), ")
          .append(Villages.ageOf(v.id()).label).append('.');
        java.util.Map<AssistantEntity.StationTask, Integer> trades =
            new java.util.EnumMap<>(AssistantEntity.StationTask.class);
        int idle = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.stationTask() == AssistantEntity.StationTask.NONE) idle++;
            else trades.merge(a.stationTask(), 1, Integer::sum);
        }
        sb.append(" Trades:");
        trades.forEach((t, n) -> sb.append(' ').append(n).append(' ').append(t.title.toLowerCase()));
        if (idle > 0) sb.append(", ").append(idle).append(" still choosing");
        sb.append(". Stores:");
        for (Villages.Task t : Villages.Task.values()) {
            if (t == Villages.Task.BUILD || t == Villages.Task.HANDS || t == Villages.Task.NONE) continue;
            sb.append(' ').append(t.name().toLowerCase()).append(' ').append(
                Villages.stock(level, v.centre(), t, Villages.storesRadius(v.id())));
        }
        sb.append(". Built: ").append(Villages.builtList(v.id()));
        sb.append(". Room for ").append(Villages.housing(v.id()));
        sb.append(". Growing: ").append(Villages.growthNote(level, v.id()));
        java.util.List<VillageFolkEntity> people = new java.util.ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f) people.add(f);
        sb.append(". Community: ").append(community(people));
        if (Villages.renown(v.id()) > 0) sb.append(". Renown ").append(Villages.renown(v.id()));
        long colonies = Villages.builtList(v.id()).stream().filter("colony"::equals).count();
        if (colonies > 0) sb.append(". Colonies founded: ").append(colonies);
        String next = Villages.nextProject(v.id());
        sb.append(". Next: ").append(Villages.whyBuild(v.id(), next));
        sb.append(". Short of:");
        java.util.List<Villages.Need> needs = Villages.needs(level, v.id());
        if (needs.isEmpty()) sb.append(" nothing — about to come of age.");
        for (Villages.Need n : needs) sb.append(' ').append(n.what()).append(';');
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }
}
