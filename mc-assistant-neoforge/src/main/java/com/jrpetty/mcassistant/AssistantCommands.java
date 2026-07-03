package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.util.List;

/**
 * /assistant spawn | follow | stay | guard | come | status | deposit
 * /assistant gather <logs|stone|dirt> [amount]
 * /assistant dismiss
 */
public final class AssistantCommands {

    private AssistantCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("assistant")
            .then(Commands.literal("spawn").executes(ctx -> spawn(ctx)))
            .then(Commands.literal("follow").executes(ctx -> setMode(ctx, AssistantEntity.Mode.FOLLOW, "Following you.")))
            .then(Commands.literal("stay").executes(ctx -> setMode(ctx, AssistantEntity.Mode.STAY, "Holding position.")))
            .then(Commands.literal("guard").executes(ctx -> setMode(ctx, AssistantEntity.Mode.GUARD, "Guard mode on — I'll watch your back.")))
            .then(Commands.literal("come").executes(AssistantCommands::come))
            .then(Commands.literal("stop").executes(AssistantCommands::stop))
            .then(Commands.literal("home").executes(AssistantCommands::home))
            .then(Commands.literal("sethome").executes(AssistantCommands::sethome))
            .then(Commands.literal("jobs").executes(AssistantCommands::jobs))
            .then(Commands.literal("open").executes(AssistantCommands::open))
            .then(Commands.literal("status").executes(AssistantCommands::status))
            .then(Commands.literal("deposit").executes(AssistantCommands::deposit))
            .then(Commands.literal("dismiss").executes(AssistantCommands::dismiss))
            .then(Commands.literal("farm").executes(AssistantCommands::farm))
            .then(Commands.literal("smelt")
                .then(Commands.argument("what", StringArgumentType.word())
                    .executes(ctx -> smelt(ctx, 8))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 256))
                        .executes(ctx -> smelt(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))
            .then(Commands.literal("craft")
                .then(Commands.argument("what", StringArgumentType.greedyString())
                    .executes(AssistantCommands::craft)))
            .then(Commands.literal("build")
                .then(Commands.argument("structure", StringArgumentType.word())
                    .executes(AssistantCommands::build)))
            .then(Commands.literal("withdraw")
                .then(Commands.argument("item", StringArgumentType.word())
                    .executes(ctx -> withdraw(ctx, 8))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, com.jrpetty.mcassistant.entity.Job.MAX_AMOUNT))
                        .executes(ctx -> withdraw(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))
            .then(Commands.literal("role")
                .then(Commands.argument("role", StringArgumentType.word())
                    .executes(AssistantCommands::role)))
            .then(Commands.literal("auto")
                .then(Commands.argument("state", StringArgumentType.word())
                    .executes(AssistantCommands::auto)))
            .then(Commands.literal("rename")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(AssistantCommands::rename)))
            .then(Commands.literal("gather")
                .then(Commands.argument("what", StringArgumentType.word())
                    .executes(ctx -> gather(ctx, 8))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, com.jrpetty.mcassistant.entity.Job.MAX_AMOUNT))
                        .executes(ctx -> gather(ctx, IntegerArgumentType.getInteger(ctx, "amount")))))));
    }

    @Nullable
    public static AssistantEntity findAssistant(ServerPlayer player) {
        // Primary: the owner->crew registry — pick the nearest one in the
        // player's dimension, no matter how far it has wandered.
        AssistantEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (AssistantEntity a : AssistantEntity.allFor(player.getUUID())) {
            if (a.level() != player.level()) continue;
            double d = a.distanceToSqr(player);
            if (d < bestDist) { bestDist = d; best = a; }
        }
        if (best != null) return best;
        // Fallback: a generous proximity scan (covers the tick before the
        // registry is populated, e.g. immediately after /assistant spawn).
        ServerLevel level = player.serverLevel();
        List<AssistantEntity> found = level.getEntitiesOfClass(
            AssistantEntity.class,
            player.getBoundingBox().inflate(256.0),
            a -> a.isAlive() && a.isOwner(player));
        for (AssistantEntity a : found) {
            double d = a.distanceToSqr(player);
            if (d < bestDist) { bestDist = d; best = a; }
        }
        return best;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        if (AssistantEntity.allFor(player.getUUID()).size() >= AssistantEntity.MAX_PER_OWNER) {
            ctx.getSource().sendFailure(Component.literal("You already run a full crew — dismiss one first."));
            return 0;
        }
        AssistantEntity a = spawnFor(player);
        if (a == null) return 0;
        a.say("Assistant online. Just talk to me (\"gather 32 logs then follow me\") or use /assistant.");
        return 1;
    }

    /** Create + bind a fresh assistant next to the player. Shared by the
     *  command and by chat/voice ("spawn a miner named bob"). Caller greets. */
    @Nullable
    public static AssistantEntity spawnFor(ServerPlayer player) {
        AssistantEntity assistant = McAssistantMod.ASSISTANT.get().create(player.serverLevel());
        if (assistant == null) return null;
        // Pick a collision-free spot — a hardcoded offset could embed the
        // 2-block-tall entity in a wall next to the player and suffocate it
        // (a dead assistant answers no commands, ever).
        double sx = player.getX();
        double sz = player.getZ();
        double[][] offsets = { {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0, 0} };
        for (double[] off : offsets) {
            net.minecraft.world.phys.AABB box = McAssistantMod.ASSISTANT.get().getDimensions()
                .makeBoundingBox(player.getX() + off[0], player.getY(), player.getZ() + off[1]);
            if (player.serverLevel().noCollision(box)) {
                sx = player.getX() + off[0];
                sz = player.getZ() + off[1];
                break;
            }
        }
        assistant.moveTo(sx, player.getY(), sz, player.getYRot(), 0);
        assistant.setOwner(player);
        player.serverLevel().addFreshEntity(assistant);
        return assistant;
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx, AssistantEntity.Mode mode, String reply) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.clearQueue(); // a direct mode order takes over now
        a.setMode(mode);
        a.say(reply);
        return 1;
    }

    private static int jobs(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        java.util.List<String> labels = a.jobLabels();
        a.say(labels.isEmpty() ? "No jobs queued." : "Jobs: " + String.join(", ", labels));
        return 1;
    }

    private static int come(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        AssistantEntity a = requireAssistant(ctx);
        if (a == null || player == null) return 0;
        a.clearQueue();
        a.setMode(AssistantEntity.Mode.FOLLOW); // come over, then keep following
        a.getNavigation().moveTo(player, 1.25D);
        a.say("Coming to you.");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.say(String.format("HP %.0f/%.0f, mode %s, role %s%s, %d items (%d food), %d jobs, %d standing orders, at %d %d %d.",
            a.getHealth(), a.getMaxHealth(), a.getMode().name().toLowerCase(),
            a.getRole().name().toLowerCase(), a.isAutonomous() ? " (auto)" : "",
            a.countItems(), a.countFood(), a.jobCount(), a.standingOrders().size(),
            a.blockPosition().getX(), a.blockPosition().getY(), a.blockPosition().getZ()));
        return 1;
    }

    private static int farm(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.farm());
        return 1;
    }

    private static int smelt(CommandContext<CommandSourceStack> ctx, int amount) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        String word = com.jrpetty.mcassistant.entity.goal.SmeltGoal.canonical(
            StringArgumentType.getString(ctx, "what").toLowerCase());
        if (word == null) {
            ctx.getSource().sendFailure(Component.literal("I can smelt: "
                + com.jrpetty.mcassistant.entity.goal.SmeltGoal.smeltableList()));
            return 0;
        }
        a.enqueue(com.jrpetty.mcassistant.entity.Job.smelt(word, amount));
        return 1;
    }

    private static int craft(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        String what = StringArgumentType.getString(ctx, "what").toLowerCase();
        String recipe = com.jrpetty.mcassistant.entity.goal.CraftGoal.matchRecipe(what);
        if (recipe == null) {
            ctx.getSource().sendFailure(Component.literal("I can craft: "
                + String.join(", ", com.jrpetty.mcassistant.entity.goal.CraftGoal.RECIPES.keySet())));
            return 0;
        }
        int amount = 1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d+)\\b").matcher(what);
        if (m.find()) amount = Integer.parseInt(m.group(1));
        a.enqueue(com.jrpetty.mcassistant.entity.Job.craft(recipe, amount));
        return 1;
    }

    private static int build(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        String structure = ChatControl.normalizeStructure(
            StringArgumentType.getString(ctx, "structure").toLowerCase());
        if (!com.jrpetty.mcassistant.entity.goal.BuildGoal.STRUCTURES.contains(structure)) {
            ctx.getSource().sendFailure(Component.literal("I can build: "
                + String.join(", ", com.jrpetty.mcassistant.entity.goal.BuildGoal.STRUCTURES)));
            return 0;
        }
        a.enqueue(com.jrpetty.mcassistant.entity.Job.build(structure));
        return 1;
    }

    private static int withdraw(CommandContext<CommandSourceStack> ctx, int amount) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.withdraw(
            StringArgumentType.getString(ctx, "item").toLowerCase(), amount));
        return 1;
    }

    private static int role(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        AssistantEntity.Role role = AssistantEntity.Role.fromWord(
            StringArgumentType.getString(ctx, "role").toLowerCase());
        if (role == null) {
            ctx.getSource().sendFailure(Component.literal("Roles: miner, lumberjack, farmer, builder."));
            return 0;
        }
        a.setRole(role);
        a.say("I'm your " + role.name().toLowerCase() + " now.");
        return 1;
    }

    private static int auto(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        boolean on = StringArgumentType.getString(ctx, "state").equalsIgnoreCase("on");
        a.setAutonomous(on);
        a.say(on ? "I'll work on my own when idle." : "Taking a break — I'll wait for orders.");
        return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.rename(StringArgumentType.getString(ctx, "name"));
        a.say("Call me " + a.displayNameCap() + ".");
        return 1;
    }

    private static int deposit(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.requestDeposit();
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.requestStop();
        return 1;
    }

    private static int open(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        AssistantEntity a = requireAssistant(ctx);
        if (a == null || player == null) return 0;
        player.openMenu(
            new net.minecraft.world.SimpleMenuProvider(
                (id, inv, p) -> new com.jrpetty.mcassistant.menu.AssistantMenu(id, inv, a),
                Component.literal("Assistant")),
            buf -> buf.writeVarInt(a.getId()));
        return 1;
    }

    private static int home(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.clearQueue();
        a.goHome();
        return 1;
    }

    private static int sethome(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.setHome(a.blockPosition());
        a.say("Home set — " + a.blockPosition().getX() + " " + a.blockPosition().getY() + " " + a.blockPosition().getZ() + ".");
        return 1;
    }

    private static int dismiss(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        AssistantEntity a = requireAssistant(ctx);
        if (a == null || player == null) return 0;
        a.say("Heading out — your gear's on the ground here.");
        a.dropEverything(); // discard() deletes held items, so drop them first
        a.discard();
        return 1;
    }

    private static int gather(CommandContext<CommandSourceStack> ctx, int amount) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        String what = StringArgumentType.getString(ctx, "what");
        GatherGoal.Kind kind = GatherGoal.Kind.fromWord(what);
        if (kind == null) {
            ctx.getSource().sendFailure(Component.literal("I can gather: logs, stone, dirt, iron, coal."));
            return 0;
        }
        a.requestGather(kind, amount);
        return 1;
    }

    @Nullable
    private static AssistantEntity requireAssistant(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Players only."));
            return null;
        }
        AssistantEntity a = findAssistant(player);
        if (a == null) {
            ctx.getSource().sendFailure(Component.literal("No assistant nearby — /assistant spawn first."));
        }
        return a;
    }
}
