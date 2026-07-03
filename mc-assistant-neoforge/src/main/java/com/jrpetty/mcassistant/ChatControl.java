package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.entity.goal.CraftGoal;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat control, natural-language edition. The assistant obeys the owner's
 * chat three ways:
 *
 *   1. "!" commands       — "!gather logs 16" (always gets a reply)
 *   2. addressed by name  — "assistant, ..." or a personal name: "bob, ..."
 *   3. bare imperatives   — "gather 128 logs and put it in the chest"
 *
 * Sentences split on "and" / "then" / commas into a sequence of queued jobs.
 * This is also the voice path (push-to-talk types recognized speech into
 * chat), so: RULE — every capability MUST land here with natural phrasings
 * the same day it lands as a command.
 */
public final class ChatControl {

    // Generic address words route to the nearest assistant.
    private static final Pattern ADDRESSED = Pattern.compile(
        "^(?:hey|ok|okay|yo)?[\\s,]*(?:assistant|bot|buddy|helper)\\b[,:!]?\\s*(.*)$");
    // "bob, gather stone" — a personal name routes to that assistant.
    private static final Pattern NAME_ROUTE = Pattern.compile(
        "^([a-z0-9_]{2,16})[,:!]?\\s+(.*)$");

    private static final Pattern LEAD_IN = Pattern.compile(
        "^(?:please\\s+|go\\s+(?:and\\s+)?|can\\s+you\\s+|could\\s+you\\s+|would\\s+you\\s+|now\\s+|also\\s+)+");
    private static final Pattern CLAUSE_SPLIT = Pattern.compile(
        "\\s*(?:,|;|\\band\\s+then\\b|\\bthen\\b|\\band\\b|\\bafter\\s+that\\b|\\bnext\\b)\\s*");

    private static final Pattern GATHER_VERB = Pattern.compile(
        "^(?:gather|get|mine|chop|collect|grab|fetch|harvest|cut|dig)\\b");
    private static final Pattern DEPOSIT_VERB = Pattern.compile(
        "^(?:deposit|stash|store|unload|dump)\\b");
    private static final Pattern PUT_IN_CHEST = Pattern.compile(
        "^(?:put|drop|empty)\\b.*\\b(?:chest|barrel)\\b");
    private static final Pattern WITHDRAW_FROM = Pattern.compile(
        "^(?:grab|take|get|withdraw|fetch|bring)\\s+(?:me\\s+)?(?:some\\s+)?(?:(\\d+)\\s+)?([a-z_ ]+?)\\s+from\\b.*\\b(?:chest|barrel|storage|stockpile)\\b");

    private static final Pattern N_STACKS = Pattern.compile("\\b(\\d+)\\s*stacks?\\b");
    private static final Pattern A_STACK = Pattern.compile("\\ba\\s+stack\\b");
    private static final Pattern NUMBER = Pattern.compile("\\b(\\d+)\\b");

    private static final Pattern SPAWN_WORD = Pattern.compile("^(?:spawn|summon)\\b");
    private static final Pattern ROLE_WORD = Pattern.compile("\\b(miner|lumberjack|logger|woodcutter|farmer|builder)\\b");
    private static final Pattern NAMED = Pattern.compile("\\b(?:named|called)\\s+([a-z0-9_]{2,16})\\b");
    private static final Pattern RENAME = Pattern.compile(
        "^(?:your name is|call yourself|i'?ll call you|rename(?:\\s+yourself)?(?:\\s+to)?)\\s+([a-z0-9_]{2,16})\\b");
    private static final Pattern BUILD_WORD = Pattern.compile("\\b(wall|platform|shelter|hut|house)\\b");

    private ChatControl() {}

    /** One parsed clause of the sentence. `arg` is the type-specific payload. */
    private record Action(Type type, @Nullable GatherGoal.Kind gatherKind, int amount,
                          @Nullable AssistantEntity.Mode mode, @Nullable String arg) {
        enum Type { GATHER, DEPOSIT, MODE, COME, STOP, STATUS, JOBS, HELP,
                    DISMISS, OPEN, GO_HOME, SET_HOME,
                    CRAFT, WITHDRAW, FARM, BUILD,
                    ROLE, RENAME, AUTO_ON, AUTO_OFF, STANDING_ADD, STANDING_CLEAR }

        static Action gather(GatherGoal.Kind k, int n) { return new Action(Type.GATHER, k, n, null, null); }
        static Action mode(AssistantEntity.Mode m) { return new Action(Type.MODE, null, 0, m, null); }
        static Action of(Type t) { return new Action(t, null, 0, null, null); }
        static Action with(Type t, String arg, int n) { return new Action(t, null, n, null, arg); }
        static Action standing(GatherGoal.Kind k, int n) { return new Action(Type.STANDING_ADD, k, n, null, null); }
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        String raw = event.getMessage().getString().trim();
        if (raw.isEmpty()) return;
        ServerPlayer player = event.getPlayer();

        String lower = raw.toLowerCase();
        boolean explicit;
        String text;
        if (lower.startsWith("!")) {
            explicit = true;
            text = lower.substring(1).trim();
        } else {
            Matcher addr = ADDRESSED.matcher(lower);
            if (addr.matches()) {
                explicit = true;
                text = addr.group(1).trim();
            } else {
                explicit = false;
                text = lower;
            }
        }
        if (text.isEmpty()) return;

        // Personal-name routing: "bob, gather stone" / "!bob follow".
        AssistantEntity routed = null;
        Matcher nm = NAME_ROUTE.matcher(text);
        if (nm.matches()) {
            AssistantEntity named = AssistantEntity.byName(player.getUUID(), nm.group(1));
            if (named != null) {
                routed = named;
                text = nm.group(2).trim();
                explicit = true;
            }
        }

        try {
            // Spawning is handled up front: it's how you get an assistant (or
            // another one), so it can't depend on one being around to hear it.
            if (SPAWN_WORD.matcher(text).find()) {
                boolean clearlyMeant = explicit
                    || text.matches("^(?:spawn|summon)\\s*$")
                    || text.matches("^(?:spawn|summon)\\b.*\\b(?:assistant|bot|buddy|helper)\\b.*")
                    || NAMED.matcher(text).find()
                    || ROLE_WORD.matcher(text).find();
                if (clearlyMeant) {
                    handleSpawn(player, text);
                }
                return;
            }

            AssistantEntity a = routed != null ? routed : AssistantCommands.findAssistant(player);
            if (a == null) {
                if (explicit) {
                    player.sendSystemMessage(Component.literal(
                        "<Assistant> (no assistant yet — say \"spawn\" or use /assistant spawn)"));
                }
                return;
            }
            dispatch(a, player, text, explicit);
        } catch (Exception ex) {
            player.sendSystemMessage(Component.literal("<Assistant> Something went wrong with that one — try again."));
        }
    }

    /** "spawn", "spawn a miner named bob", "summon another helper". */
    private static void handleSpawn(ServerPlayer player, String text) {
        List<AssistantEntity> existing = AssistantEntity.allFor(player.getUUID());
        if (existing.size() >= AssistantEntity.MAX_PER_OWNER) {
            player.sendSystemMessage(Component.literal(
                "<Assistant> You already run a crew of " + existing.size() + " — dismiss one first."));
            return;
        }
        Matcher rw = ROLE_WORD.matcher(text);
        AssistantEntity.Role role = rw.find()
            ? AssistantEntity.Role.fromWord(rw.group(1)) : AssistantEntity.Role.NONE;
        if (role == null) role = AssistantEntity.Role.NONE;

        Matcher nmd = NAMED.matcher(text);
        String name = nmd.find() ? nmd.group(1) : null;
        if (name != null && AssistantEntity.byName(player.getUUID(), name) != null) {
            player.sendSystemMessage(Component.literal(
                "<Assistant> There's already one named " + name + " — pick another name."));
            return;
        }
        if (name == null) {
            String base = role != AssistantEntity.Role.NONE ? role.name().toLowerCase()
                : (existing.isEmpty() ? "assistant" : "helper");
            name = base;
            int n = 2;
            while (AssistantEntity.byName(player.getUUID(), name) != null) {
                name = base + n++;
            }
        }

        AssistantEntity fresh = AssistantCommands.spawnFor(player);
        if (fresh == null) return;
        fresh.rename(name);
        if (role != AssistantEntity.Role.NONE) fresh.setRole(role);
        fresh.say((role != AssistantEntity.Role.NONE ? "Your " + role.name().toLowerCase() + " is" : "I'm")
            + " ready. Call me by name — \"" + name + ", gather logs\" — or just talk for the nearest of us.");
    }

    private static void dispatch(AssistantEntity a, ServerPlayer player, String text, boolean explicit) {
        List<Action> actions = new ArrayList<>();
        boolean unknownMaterial = false;
        Action prev = null;
        for (String clause : CLAUSE_SPLIT.split(text)) {
            String c = LEAD_IN.matcher(clause.trim()).replaceFirst("").trim();
            if (c.isEmpty()) continue;
            Action act = parseClause(c, prev, explicit);
            if (act == null) {
                if (GATHER_VERB.matcher(c).find()) unknownMaterial = true;
            } else {
                actions.add(act);
                prev = act;
            }
        }

        if (actions.isEmpty()) {
            if (explicit) {
                a.say(unknownMaterial
                    ? "I can gather logs, stone, or dirt (for now)."
                    : "Didn't catch that — try \"gather 32 logs then deposit\", \"craft a stone pickaxe\", \"build a shelter\", or !help.");
            }
            return;
        }

        execute(a, player, actions, unknownMaterial);
    }

    @Nullable
    private static Action parseClause(String c, @Nullable Action prev, boolean explicit) {
        // Config / control — these outrank the plain "stop" so phrases like
        // "stop keeping the chest stocked" and "stop working on your own"
        // hit the right thing.
        if ((c.matches("^(?:clear|drop)\\b.*") && c.contains("standing"))
            || c.matches("^(?:stop|cancel)\\b.*\\b(?:keeping|standing)\\b.*")) {
            return Action.of(Action.Type.STANDING_CLEAR);
        }
        if (c.contains("take a break") || c.matches("^auto\\s*off\\b.*")
            || c.matches("^stop\\b.*\\bworking\\b.*") || c.contains("wait for orders")) {
            return Action.of(Action.Type.AUTO_OFF);
        }
        if (c.matches("^(?:stop|halt|cancel|never\\s?mind)\\b.*")) return Action.of(Action.Type.STOP);
        if (c.matches("^(?:status|report)\\b.*") || c.contains("how are you")) return Action.of(Action.Type.STATUS);
        if (c.matches("^(?:jobs|queue|tasks)\\b.*") || c.contains("what are you doing")) return Action.of(Action.Type.JOBS);
        if (c.matches("^help\\b.*") || c.contains("what can you do")) return Action.of(Action.Type.HELP);
        if (c.contains("work on your own") || c.matches("^auto\\s*on\\b.*")
            || c.contains("be autonomous") || c.contains("do your own thing")) {
            return Action.of(Action.Type.AUTO_ON);
        }

        // Standing supply orders: "keep the chest stocked with 64 logs".
        if (c.matches("^keep\\b.*")) {
            GatherGoal.Kind kind = kindIn(c);
            if (kind != null) return Action.standing(kind, parseAmount(c, 32));
            return explicit ? Action.with(Action.Type.STANDING_ADD, null, 0) : null;
        }

        // Role & identity.
        Matcher rn = RENAME.matcher(c);
        if (rn.find()) return Action.with(Action.Type.RENAME, rn.group(1), 0);
        if (c.matches("^(?:be|become|act as|you're|you are)\\b.*") || c.matches("^your role is\\b.*")) {
            Matcher rw = ROLE_WORD.matcher(c);
            if (rw.find()) return Action.with(Action.Type.ROLE, rw.group(1), 0);
        }

        // Modes / movement.
        if (c.matches("^follow\\b.*")) return Action.mode(AssistantEntity.Mode.FOLLOW);
        if (c.matches("^(?:stay|stand)\\b.*") || (explicit && c.matches("^(?:wait|hold)\\b.*"))) {
            return Action.mode(AssistantEntity.Mode.STAY);
        }
        if (c.matches("^(?:guard|protect|defend)\\b.*")) return Action.mode(AssistantEntity.Mode.GUARD);
        if (c.matches("^(?:come|here)\\b.*") || c.equals("to me")) return Action.of(Action.Type.COME);

        // Lifecycle / GUI / home ("go away" arrives as "away": LEAD_IN strips "go ").
        if (c.matches("^dismiss\\b.*")
            || (explicit && c.matches("^(?:away|leave|goodbye|bye)\\b.*"))) {
            return Action.of(Action.Type.DISMISS);
        }
        if (c.matches("^(?:open|show)\\b.*\\b(?:inventory|pack|backpack|bag|gear|items|stuff)\\b.*")
            || c.equals("open") || c.equals("open up")) {
            return Action.of(Action.Type.OPEN);
        }
        if (c.matches("^(?:set|make)\\b.*\\bhome\\b.*") || c.contains("this is home")) {
            return Action.of(Action.Type.SET_HOME);
        }
        if (c.matches("^(?:(?:head|return|walk)\\s+(?:back\\s+)?)?home\\b.*")) {
            return Action.of(Action.Type.GO_HOME);
        }

        // Farming: "farm", "tend the crops"; "harvest" only when it isn't
        // "harvest 20 logs" (that's a gather).
        if (c.matches("^(?:farm|tend)\\b.*")
            || (c.matches("^harvest\\b.*") && kindIn(c) == null)) {
            return Action.of(Action.Type.FARM);
        }

        // Building: "build a shelter", "construct a wall".
        if (c.matches("^(?:build|construct)\\b.*")) {
            Matcher bw = BUILD_WORD.matcher(c);
            if (bw.find()) {
                String s = bw.group(1);
                if (s.equals("hut") || s.equals("house")) s = "shelter";
                return Action.with(Action.Type.BUILD, s, 0);
            }
            return explicit ? Action.with(Action.Type.BUILD, null, 0) : null;
        }

        // Withdraw (before gather — "take"/"get"/"grab" overlap): needs
        // "... from the chest/barrel/storage".
        Matcher wd = WITHDRAW_FROM.matcher(c);
        if (wd.find()) {
            int n = wd.group(1) != null ? clamp(Integer.parseInt(wd.group(1))) : 8;
            String item = wd.group(2).trim().replaceAll("^(?:the|a|an|some)\\s+", "");
            return Action.with(Action.Type.WITHDRAW, item, n);
        }

        // Crafting: "craft 4 sticks", "make a stone pickaxe".
        if (c.matches("^(?:craft|make)\\b.*")) {
            String recipe = CraftGoal.matchRecipe(c);
            if (recipe != null) return Action.with(Action.Type.CRAFT, recipe, parseAmount(c, 1));
            return explicit ? Action.with(Action.Type.CRAFT, null, 0) : null;
        }

        // Deposit.
        if (DEPOSIT_VERB.matcher(c).find() || PUT_IN_CHEST.matcher(c).find()) {
            return Action.of(Action.Type.DEPOSIT);
        }

        // Gather (explicit verb, or verb carried over: "gather 64 logs and 32 stone").
        GatherGoal.Kind kind = kindIn(c);
        boolean gatherVerb = GATHER_VERB.matcher(c).find();
        if (kind != null && (gatherVerb || (prev != null && prev.type() == Action.Type.GATHER))) {
            return Action.gather(kind, parseAmount(c, 8));
        }
        return null;
    }

    @Nullable
    private static GatherGoal.Kind kindIn(String clause) {
        for (String word : clause.split("[^a-z]+")) {
            GatherGoal.Kind k = GatherGoal.Kind.fromWord(word);
            if (k != null) return k;
        }
        return null;
    }

    private static int parseAmount(String clause, int fallback) {
        Matcher stacks = N_STACKS.matcher(clause);
        if (stacks.find()) return clamp(Integer.parseInt(stacks.group(1)) * 64);
        if (A_STACK.matcher(clause).find()) return 64;
        Matcher num = NUMBER.matcher(clause);
        if (num.find()) return clamp(Integer.parseInt(num.group(1)));
        return fallback;
    }

    private static int clamp(int n) {
        return Math.max(1, Math.min(Job.MAX_AMOUNT, n));
    }

    private static void execute(AssistantEntity a, ServerPlayer player, List<Action> actions, boolean unknownMaterial) {
        boolean single = actions.size() == 1;
        int queuedBefore = a.jobCount();
        List<String> queuedLabels = new ArrayList<>();

        for (Action act : actions) {
            switch (act.type()) {
                case STOP -> a.requestStop();
                case STATUS -> a.say(String.format(
                    "HP %.0f/%.0f, mode %s, role %s%s, %d items (%d food), %d jobs queued, %d standing orders.",
                    a.getHealth(), a.getMaxHealth(), a.getMode().name().toLowerCase(),
                    a.getRole().name().toLowerCase(), a.isAutonomous() ? " (working autonomously)" : "",
                    a.countItems(), a.countFood(), a.jobCount(), a.standingOrders().size()));
                case JOBS -> reportJobs(a);
                case HELP -> a.say("Talk to me like a person — orders chain with \"and\"/\"then\" and queue up. I understand: "
                    + "gather/mine/chop (logs, stone, dirt), deposit, \"grab X from the chest\", craft/make ("
                    + "planks, sticks, tools, chest, torches, bread...), \"build a wall/platform/shelter\", "
                    + "farm/harvest, follow/stay/guard/come/stop, go home, set home here, open (my gear), "
                    + "\"keep the chest stocked with 64 logs\", \"be a miner/farmer/lumberjack/builder\", "
                    + "\"work on your own\" / \"take a break\", \"your name is <name>\", spawn (a crew!), dismiss. "
                    + "Hold the voice key (default V) to speak any of this.");
                case GATHER -> {
                    Job job = Job.gather(act.gatherKind(), act.amount());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case DEPOSIT -> {
                    Job job = Job.deposit();
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case CRAFT -> {
                    if (act.arg() == null) {
                        a.say("I can craft: " + String.join(", ", CraftGoal.RECIPES.keySet()) + ".");
                    } else {
                        Job job = Job.craft(act.arg(), act.amount());
                        a.enqueue(job);
                        queuedLabels.add(job.label());
                    }
                }
                case WITHDRAW -> {
                    Job job = Job.withdraw(act.arg(), act.amount());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case FARM -> {
                    Job job = Job.farm();
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case BUILD -> {
                    if (act.arg() == null) {
                        a.say("I can build: " + String.join(", ", BuildGoal.STRUCTURES) + ".");
                    } else {
                        Job job = Job.build(act.arg());
                        a.enqueue(job);
                        queuedLabels.add(job.label());
                    }
                }
                case MODE -> {
                    if (single) {
                        a.clearQueue();
                        a.setMode(act.mode());
                        a.say(switch (act.mode()) {
                            case FOLLOW -> "Following you.";
                            case STAY -> "Holding here.";
                            case GUARD -> "Guard mode on — I'll watch your back.";
                        });
                    } else {
                        Job job = Job.mode(act.mode());
                        a.enqueue(job);
                        queuedLabels.add(job.label());
                    }
                }
                case COME -> {
                    if (single) {
                        a.clearQueue();
                        a.setMode(AssistantEntity.Mode.FOLLOW);
                        a.getNavigation().moveTo(player, 1.25D);
                        a.say("Coming.");
                    } else {
                        a.enqueue(Job.mode(AssistantEntity.Mode.FOLLOW));
                        queuedLabels.add("come to you");
                    }
                }
                case DISMISS -> {
                    a.say("Heading out — your gear's on the ground here.");
                    a.dropEverything();
                    a.discard();
                    return;
                }
                case OPEN -> player.openMenu(
                    new net.minecraft.world.SimpleMenuProvider(
                        (id, inv, p) -> new com.jrpetty.mcassistant.menu.AssistantMenu(id, inv, a),
                        Component.literal(a.displayNameCap())),
                    buf -> buf.writeVarInt(a.getId()));
                case GO_HOME -> {
                    if (single) {
                        a.clearQueue();
                        a.goHome();
                    } else {
                        a.enqueue(Job.goHome());
                        queuedLabels.add("go home");
                    }
                }
                case SET_HOME -> {
                    a.setHome(a.blockPosition());
                    a.say("Home set — " + a.blockPosition().getX() + " "
                        + a.blockPosition().getY() + " " + a.blockPosition().getZ() + ".");
                }
                case ROLE -> {
                    AssistantEntity.Role role = AssistantEntity.Role.fromWord(act.arg());
                    if (role != null) {
                        a.setRole(role);
                        a.say("I'm your " + role.name().toLowerCase() + " now."
                            + (a.isAutonomous() ? "" : " Say \"work on your own\" and I'll keep at it whenever I'm idle."));
                    }
                }
                case RENAME -> {
                    a.rename(act.arg());
                    a.say("Call me " + a.displayNameCap() + ".");
                }
                case AUTO_ON -> {
                    a.setAutonomous(true);
                    a.say(a.getRole() == AssistantEntity.Role.NONE
                        ? "I'll work on my own — give me a role first (\"be a miner/lumberjack/farmer/builder\")."
                        : "On it — I'll keep doing " + a.getRole().name().toLowerCase() + " work whenever I'm idle.");
                }
                case AUTO_OFF -> {
                    a.setAutonomous(false);
                    a.say("Taking a break — I'll wait for orders.");
                }
                case STANDING_ADD -> {
                    if (act.gatherKind() == null) {
                        a.say("Tell me what to keep stocked — e.g. \"keep the chest stocked with 64 logs\".");
                    } else {
                        a.addStandingOrder(act.gatherKind(), act.amount());
                        a.say("Standing order: I'll keep ~" + act.amount() + " " + act.gatherKind().label
                            + " in the nearest chest. \"stop keeping\" cancels it.");
                    }
                }
                case STANDING_CLEAR -> {
                    int n = a.clearStandingOrders();
                    a.say(n > 0 ? "Dropped " + n + " standing order" + (n == 1 ? "" : "s") + "." : "No standing orders to drop.");
                }
            }
        }

        if (queuedLabels.size() > 1) {
            StringBuilder sb = new StringBuilder("Got it — " + queuedLabels.size() + " jobs: ");
            for (int i = 0; i < queuedLabels.size(); i++) {
                sb.append(i + 1).append(") ").append(queuedLabels.get(i));
                if (i < queuedLabels.size() - 1) sb.append("  ");
            }
            a.say(sb.toString());
        } else if (queuedLabels.size() == 1 && queuedBefore > 0) {
            a.say("Queued: " + queuedLabels.get(0) + " (#" + a.jobCount() + " in line).");
        }
        if (unknownMaterial) {
            a.say("(One part asked for a material I can't gather yet — I know logs, stone, and dirt.)");
        }
    }

    private static void reportJobs(AssistantEntity a) {
        List<String> labels = a.jobLabels();
        if (labels.isEmpty()) {
            a.say("No jobs queued — I'm on " + a.getMode().name().toLowerCase()
                + (a.isAutonomous() && a.getRole() != AssistantEntity.Role.NONE
                    ? ", working " + a.getRole().name().toLowerCase() + " jobs on my own" : "") + ".");
            return;
        }
        StringBuilder sb = new StringBuilder("Jobs: ");
        for (int i = 0; i < labels.size(); i++) {
            sb.append(i + 1).append(") ").append(labels.get(i));
            if (i < labels.size() - 1) sb.append("  ");
        }
        a.say(sb.toString());
    }
}
