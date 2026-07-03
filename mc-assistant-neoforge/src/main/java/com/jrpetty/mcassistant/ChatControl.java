package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
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
 *   2. addressed by name  — "assistant, gather 128 logs then deposit"
 *   3. bare imperatives   — "gather 128 logs and put it in the chest"
 *      (only when the sentence clearly starts with an order; anything else
 *      is treated as normal chat and left alone)
 *
 * Sentences split on "and" / "then" / commas into a sequence of actions that
 * run through the job queue in order — this is also the voice path: any
 * speech-to-text that types into chat drives the assistant hands-free.
 * Non-owners are ignored.
 */
public final class ChatControl {

    // "hey assistant, ..." / "bot: ..." — strip the address, keep the order.
    private static final Pattern ADDRESSED = Pattern.compile(
        "^(?:hey|ok|okay|yo)?[\\s,]*(?:assistant|bot|buddy|helper)\\b[,:!]?\\s*(.*)$");

    // Politeness / filler a clause may start with.
    private static final Pattern LEAD_IN = Pattern.compile(
        "^(?:please\\s+|go\\s+(?:and\\s+)?|can\\s+you\\s+|could\\s+you\\s+|would\\s+you\\s+|now\\s+|also\\s+)+");

    // Clause separators: "and", "then", ", ", "; ", "after that", "next".
    private static final Pattern CLAUSE_SPLIT = Pattern.compile(
        "\\s*(?:,|;|\\band\\s+then\\b|\\bthen\\b|\\band\\b|\\bafter\\s+that\\b|\\bnext\\b)\\s*");

    private static final Pattern GATHER_VERB = Pattern.compile(
        "^(?:gather|get|mine|chop|collect|grab|fetch|harvest|cut|dig)\\b");
    private static final Pattern DEPOSIT_VERB = Pattern.compile(
        "^(?:deposit|stash|store|unload|dump)\\b");
    private static final Pattern PUT_IN_CHEST = Pattern.compile(
        "^(?:put|drop|empty)\\b.*\\b(?:chest|barrel)\\b");

    private static final Pattern N_STACKS = Pattern.compile("\\b(\\d+)\\s*stacks?\\b");
    private static final Pattern A_STACK = Pattern.compile("\\ba\\s+stack\\b");
    private static final Pattern NUMBER = Pattern.compile("\\b(\\d+)\\b");

    private ChatControl() {}

    /** One parsed clause of the sentence. */
    private record Action(Type type, @Nullable GatherGoal.Kind gatherKind, int amount,
                          @Nullable AssistantEntity.Mode mode) {
        enum Type { GATHER, DEPOSIT, MODE, COME, STOP, STATUS, JOBS, HELP }

        static Action gather(GatherGoal.Kind k, int n) { return new Action(Type.GATHER, k, n, null); }
        static Action mode(AssistantEntity.Mode m) { return new Action(Type.MODE, null, 0, m); }
        static Action of(Type t) { return new Action(t, null, 0, null); }
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        String raw = event.getMessage().getString().trim();
        if (raw.isEmpty()) return;
        ServerPlayer player = event.getPlayer();
        AssistantEntity a = AssistantCommands.findAssistant(player);
        if (a == null) return;

        String lower = raw.toLowerCase();
        boolean explicit; // '!' or addressed by name => always answer, even to nonsense
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
        if (text.isEmpty()) {
            if (explicit) a.say("Yes? Try \"gather 128 logs and deposit it into the chest\".");
            return;
        }

        // Never let one bad command permanently break command handling — every
        // future chat message re-enters this handler cleanly.
        try {
            dispatch(a, player, text, explicit);
        } catch (Exception ex) {
            if (explicit) a.say("Something went wrong with that one — try again.");
        }
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
                // A gather verb with a material I don't know deserves a
                // specific answer instead of a shrug.
                if (GATHER_VERB.matcher(c).find()) unknownMaterial = true;
            } else {
                actions.add(act);
                prev = act;
            }
        }

        if (actions.isEmpty()) {
            // Bare chat that isn't an order stays normal chat — only answer
            // when the message was clearly for us ('!' or addressed by name).
            if (explicit) {
                a.say(unknownMaterial
                    ? "I can gather logs, stone, or dirt (for now)."
                    : "Didn't catch that — try \"gather 32 logs then deposit\", !follow, !guard, !stop, !jobs, or right-click me.");
            }
            return;
        }

        execute(a, player, actions, unknownMaterial);
    }

    @Nullable
    private static Action parseClause(String c, @Nullable Action prev, boolean explicit) {
        // Instant / control words first.
        if (c.matches("^(?:stop|halt|cancel|never\\s?mind)\\b.*")) return Action.of(Action.Type.STOP);
        if (c.matches("^(?:status|report)\\b.*") || c.contains("how are you")) return Action.of(Action.Type.STATUS);
        if (c.matches("^(?:jobs|queue|tasks)\\b.*") || c.contains("what are you doing")) return Action.of(Action.Type.JOBS);
        if (c.matches("^help\\b.*") || c.contains("what can you do")) return Action.of(Action.Type.HELP);

        // Modes / movement. "wait"/"hold" are everyday words, so a bare
        // "wait, really?" in chat must NOT park the assistant — those two
        // only count when the message was clearly for us.
        if (c.matches("^follow\\b.*")) return Action.mode(AssistantEntity.Mode.FOLLOW);
        if (c.matches("^(?:stay|stand)\\b.*") || (explicit && c.matches("^(?:wait|hold)\\b.*"))) {
            return Action.mode(AssistantEntity.Mode.STAY);
        }
        if (c.matches("^(?:guard|protect|defend)\\b.*")) return Action.mode(AssistantEntity.Mode.GUARD);
        if (c.matches("^(?:come|here)\\b.*") || c.equals("to me")) return Action.of(Action.Type.COME);

        // Deposit: "deposit", "stash it", "put it in the chest", "unload".
        if (DEPOSIT_VERB.matcher(c).find() || PUT_IN_CHEST.matcher(c).find()) {
            return new Action(Action.Type.DEPOSIT, null, 0, null);
        }

        // Gather: explicit verb ("gather 128 logs"), or the verb carried over
        // from the previous clause ("gather 64 logs and 32 stone").
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

    /** "128" -> 128, "2 stacks" -> 128, "a stack" -> 64, nothing -> fallback. */
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
                case STATUS -> a.say(String.format("HP %.0f/%.0f, mode %s, carrying %d items, %d jobs queued.",
                    a.getHealth(), a.getMaxHealth(), a.getMode().name().toLowerCase(), a.countItems(), a.jobCount()));
                case JOBS -> reportJobs(a);
                case HELP -> a.say("Talk to me like a person: \"gather 128 logs and deposit it into the chest, then follow me\". "
                    + "I understand gather/get/mine/chop (logs, stone, dirt — plain numbers or \"2 stacks\"), deposit/stash, "
                    + "follow, stay, guard, come, stop, jobs, status. Orders queue and run in sequence. Right-click me to manage my gear.");
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
                case MODE -> {
                    if (single) {
                        // A lone mode order takes over right now (clears the queue).
                        a.clearQueue();
                        a.setMode(act.mode());
                        a.say(switch (act.mode()) {
                            case FOLLOW -> "Following you.";
                            case STAY -> "Holding here.";
                            case GUARD -> "Guard mode on — I'll watch your back.";
                        });
                    } else {
                        // Part of a sequence — do it in order, after the work.
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
            }
        }

        // One tidy confirmation for the whole sentence (a lone gather/deposit
        // on an empty queue stays quiet — the goal announces itself).
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
            a.say("No jobs queued — I'm on " + a.getMode().name().toLowerCase() + ".");
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
