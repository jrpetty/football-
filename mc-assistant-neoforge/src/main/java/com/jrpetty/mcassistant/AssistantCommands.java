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
            .then(Commands.literal("mine")
                .executes(ctx -> mine(ctx, 12))
                .then(Commands.argument("level", IntegerArgumentType.integer(-58, 100))
                    .executes(ctx -> mine(ctx, IntegerArgumentType.getInteger(ctx, "level")))))
            .then(Commands.literal("hunt")
                .executes(ctx -> hunt(ctx, null, 3))
                .then(Commands.argument("animal", StringArgumentType.word())
                    .executes(ctx -> hunt(ctx, StringArgumentType.getString(ctx, "animal"), 3))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
                        .executes(ctx -> hunt(ctx, StringArgumentType.getString(ctx, "animal"),
                            IntegerArgumentType.getInteger(ctx, "count"))))))
            .then(Commands.literal("shear").executes(AssistantCommands::shear))
            .then(Commands.literal("patrol")
                .then(Commands.argument("placeA", StringArgumentType.word())
                    .then(Commands.argument("placeB", StringArgumentType.word())
                        .executes(AssistantCommands::patrol))))
            .then(Commands.literal("clear")
                .executes(ctx -> clearArea(ctx, 8))
                .then(Commands.argument("size", IntegerArgumentType.integer(2, 24))
                    .executes(ctx -> clearArea(ctx, IntegerArgumentType.getInteger(ctx, "size")))))
            .then(Commands.literal("lightup")
                .executes(ctx -> lightup(ctx, 12))
                .then(Commands.argument("radius", IntegerArgumentType.integer(4, 24))
                    .executes(ctx -> lightup(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
            .then(Commands.literal("bridge").executes(AssistantCommands::bridge))
            .then(Commands.literal("breed")
                .executes(ctx -> breed(ctx, null))
                .then(Commands.argument("animal", StringArgumentType.word())
                    .executes(ctx -> breed(ctx, StringArgumentType.getString(ctx, "animal")))))
            .then(Commands.literal("herd")
                .then(Commands.argument("animal", StringArgumentType.word())
                    .executes(ctx -> herd(ctx, 2))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 2))
                        .executes(ctx -> herd(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
            .then(Commands.literal("fish")
                .executes(ctx -> fish(ctx, 5))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 32))
                    .executes(ctx -> fish(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
            .then(Commands.literal("cleanup").executes(AssistantCommands::cleanup))
            .then(Commands.literal("sort").executes(AssistantCommands::sort))
            .then(Commands.literal("enchant")
                .executes(ctx -> enchant(ctx, null))
                .then(Commands.argument("what", StringArgumentType.word())
                    .executes(ctx -> enchant(ctx, StringArgumentType.getString(ctx, "what")))))
            .then(Commands.literal("stock")
                .then(Commands.argument("item", StringArgumentType.word())
                    .executes(AssistantCommands::stock)))
            .then(Commands.literal("nether")
                .then(Commands.argument("target", StringArgumentType.word())
                    .executes(ctx -> nether(ctx, 16))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 256))
                        .executes(ctx -> nether(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))
            .then(Commands.literal("boat")
                .then(Commands.argument("coords", StringArgumentType.greedyString())
                    .executes(AssistantCommands::boat)))
            .then(Commands.literal("make")
                .then(Commands.argument("what", StringArgumentType.greedyString())
                    .executes(AssistantCommands::make)))
            .then(Commands.literal("needs").executes(AssistantCommands::needs))
            .then(Commands.literal("night")
                .then(Commands.argument("state", StringArgumentType.word())
                    .executes(AssistantCommands::night)))
            .then(Commands.literal("give")
                .then(Commands.argument("item", StringArgumentType.word())
                    .executes(ctx -> give(ctx, 8))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 1024))
                        .executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
            .then(Commands.literal("goto")
                .then(Commands.argument("place", StringArgumentType.greedyString())
                    .executes(AssistantCommands::gotoPlace)))
            .then(Commands.literal("mark")
                .then(Commands.argument("place", StringArgumentType.greedyString())
                    .executes(AssistantCommands::mark)))
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
        a.say(String.format("HP %.0f/%.0f, mode %s, role %s%s, %d items (%d food), %d xp, %d jobs, %d standing orders, at %d %d %d.",
            a.getHealth(), a.getMaxHealth(), a.getMode().name().toLowerCase(),
            a.getRole().name().toLowerCase(), a.isAutonomous() ? " (auto)" : "",
            a.countItems(), a.countFood(), a.getXp(), a.jobCount(), a.standingOrders().size(),
            a.blockPosition().getX(), a.blockPosition().getY(), a.blockPosition().getZ()));
        return 1;
    }

    private static int farm(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.farm());
        return 1;
    }

    private static int mine(CommandContext<CommandSourceStack> ctx, int level) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.mine(level));
        return 1;
    }

    private static int hunt(CommandContext<CommandSourceStack> ctx, @Nullable String animal, int count) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.hunt(animal, count));
        return 1;
    }

    private static int shear(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.shear(8));
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> ctx, int count) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.give(
            StringArgumentType.getString(ctx, "item").toLowerCase(), count));
        return 1;
    }

    private static int gotoPlace(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.clearQueue();
        a.enqueue(com.jrpetty.mcassistant.entity.Job.goTo(
            StringArgumentType.getString(ctx, "place").toLowerCase().trim()));
        return 1;
    }

    private static int mark(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        String place = StringArgumentType.getString(ctx, "place").toLowerCase().trim();
        a.setWaypoint(place, a.blockPosition());
        a.say("Got it — this spot is \"" + place + "\" now.");
        return 1;
    }

    private static int patrol(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.clearQueue();
        a.enqueue(com.jrpetty.mcassistant.entity.Job.patrol(
            StringArgumentType.getString(ctx, "placeA").toLowerCase(),
            StringArgumentType.getString(ctx, "placeB").toLowerCase()));
        return 1;
    }

    private static int clearArea(CommandContext<CommandSourceStack> ctx, int size) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.clear(size + "x" + size));
        return 1;
    }

    private static int lightup(CommandContext<CommandSourceStack> ctx, int radius) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.torchArea(radius));
        return 1;
    }

    private static int bridge(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.bridge());
        return 1;
    }

    private static int breed(CommandContext<CommandSourceStack> ctx, @Nullable String animal) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.breed(animal, 2));
        return 1;
    }

    private static int herd(CommandContext<CommandSourceStack> ctx, int count) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.herd(
            StringArgumentType.getString(ctx, "animal").toLowerCase(), count));
        return 1;
    }

    private static int fish(CommandContext<CommandSourceStack> ctx, int count) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.fish(count));
        return 1;
    }

    private static int cleanup(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.cleanup());
        return 1;
    }

    private static int sort(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.sort());
        return 1;
    }

    private static int enchant(CommandContext<CommandSourceStack> ctx, @Nullable String what) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.enchant(what));
        return 1;
    }

    private static int stock(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        String item = StringArgumentType.getString(ctx, "item").toLowerCase();
        int n = a.countAcrossStorage(
            com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor(item));
        a.say(n > 0 ? "We have " + n + " " + item + " across my pack and known chests."
            : "No " + item + " in my pack or any chest I've seen.");
        return 1;
    }

    private static int make(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        String clause = StringArgumentType.getString(ctx, "what").toLowerCase();
        String tgt = com.jrpetty.mcassistant.entity.CraftPlanner.matchTarget(clause);
        if (tgt == null) {
            ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal(
                "I don't know how to make that. Try a tool, sword, chest, furnace, torches, planks..."));
            return 0;
        }
        String what = com.jrpetty.mcassistant.entity.CraftPlanner.pretty(tgt);
        var plan = com.jrpetty.mcassistant.entity.CraftPlanner.plan(a, tgt, 1);
        if (plan.jobs().isEmpty() && plan.blockers().isEmpty()) {
            a.say("You've already got " + what + ".");
        } else if (plan.jobs().isEmpty()) {
            a.say("To make " + what + " I'd need " + String.join(", ", plan.blockers())
                + " — none nearby. Put some in a chest or hand it to me.");
        } else {
            for (var j : plan.jobs()) a.enqueue(j);
            a.say("To make " + what + ": " + String.join(", ", plan.narration()) + ". On it.");
            if (!plan.blockers().isEmpty()) {
                a.say("(Still missing " + String.join(", ", plan.blockers()) + " — I'll get as far as I can.)");
            }
        }
        return 1;
    }

    private static int needs(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.say("Right now: " + String.join("; ", a.assessNeeds()) + ".");
        return 1;
    }

    private static int nether(CommandContext<CommandSourceStack> ctx, int amount) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.nether(
            StringArgumentType.getString(ctx, "target").toLowerCase(), amount));
        return 1;
    }

    private static int boat(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.enqueue(com.jrpetty.mcassistant.entity.Job.boat(
            StringArgumentType.getString(ctx, "coords").trim()));
        return 1;
    }

    private static int night(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        boolean on = StringArgumentType.getString(ctx, "state").equalsIgnoreCase("on");
        a.setNightHome(on);
        a.say(on ? "Home at dusk, back at dawn." : "I'll keep working after dark.");
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
        String recipe = com.jrpetty.mcassistant.entity.CraftPlanner.matchTarget(what);
        if (recipe == null) {
            ctx.getSource().sendFailure(Component.literal(
                "I don't know that one — name any craftable item, e.g. iron_sword, piston, hopper."));
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
