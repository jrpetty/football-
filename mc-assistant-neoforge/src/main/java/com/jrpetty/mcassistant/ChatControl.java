package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.CraftPlanner;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.jrpetty.mcassistant.entity.goal.SmeltGoal;
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
    private static final Pattern BUILD_WORD = Pattern.compile(
        "\\b(wall|platform|shelter|hut|house|home|room|smeltery|forge|furnace|storage|warehouse|"
        + "workshop|watchtower|tower|pen|enclosure|barn|pasture|fence)\\b");
    private static final Pattern MINE_LEVEL = Pattern.compile("\\b(?:level|y)\\s*(-?\\d+)\\b");
    // Anchored to the end so "mine diamonds" hits but "make a diamond sword" (an
    // item to craft, not raw ore) does not.
    private static final Pattern ORE_ANY = Pattern.compile("\\b(diamond|iron|gold|redstone|coal|copper|lapis)s?(?:\\s+ore)?\\s*$");
    private static final Pattern ORE_DEEP = Pattern.compile("\\b(diamond|redstone|lapis|gold)s?(?:\\s+ore)?\\s*$");

    /** Best Y level to branch-mine for an ore. */
    private static int oreY(String ore) {
        return switch (ore) {
            case "diamond", "redstone", "lapis" -> -59;
            case "gold" -> -16;
            case "copper" -> 48;
            case "coal" -> 50;
            default -> 16; // iron
        };
    }
    private static final Pattern ANIMAL_WORD = Pattern.compile("\\b(cows?|pigs?|chickens?|sheep|rabbits?)\\b");
    private static final Pattern WAYPOINT_AS = Pattern.compile(
        "^(?:remember|save|mark)\\b.*?\\bas\\s+(?:the\\s+)?([a-z0-9_ ]{2,24})$");
    private static final Pattern WAYPOINT_CALL = Pattern.compile(
        "^call\\s+this\\s+(?:place|spot)\\s+(?:the\\s+)?([a-z0-9_ ]{2,24})$");
    private static final Pattern GOTO_PLACE = Pattern.compile(
        "^(?:travel\\s+|head\\s+|walk\\s+)?to\\s+(?:the\\s+)?([a-z0-9_ ]{2,24})$");
    private static final Pattern GIVE_ME = Pattern.compile(
        "^(?:give|hand|pass|toss)(?:\\s+(?:me|us))?\\s+(?:the\\s+|some\\s+|a\\s+)?(?:(\\d+)\\s+)?([a-z_ ]+?)\\s*$");
    private static final Pattern PATROL_BETWEEN = Pattern.compile(
        "^!?patrol\\s+(?:between\\s+)?(?:the\\s+)?([a-z0-9_ ]+?)\\s+and\\s+(?:the\\s+)?([a-z0-9_ ]+?)\\s*$");
    private static final Pattern AREA_DIM = Pattern.compile("(\\d+)\\s*(?:x|by|×)\\s*(\\d+)");
    private static final Pattern REPAIR_WORD = Pattern.compile(
        "^(?:repair|fix|mend)\\b(?:\\s+(?:your|my|the))?\\s*([a-z_ ]*)$");
    private static final Pattern LOCATE_WORD = Pattern.compile(
        "^(?:find|locate)\\b.*\\b(village|mineshaft|shipwreck|stronghold|portal)\\b");
    private static final Pattern COORDS = Pattern.compile("(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)");
    private static final Pattern ENCHANT_WORD = Pattern.compile(
        "^enchant(?:\\s+(?:your|my|the))?\\s*([a-z ]*)$");
    private static final Pattern STOCK_QUERY = Pattern.compile(
        "^how\\s+(?:much|many)\\s+([a-z ]+?)\\s+(?:do|have|did|does)\\b.*");
    private static final Pattern NETHER_WORD = Pattern.compile("\\bnether\\b");
    private static final Pattern NETHER_TARGET = Pattern.compile(
        "\\b(glowstone|quartz|netherrack|soul\\s*sand|magma|nether\\s*gold|gold)\\b");
    // Scheduled chores: "every 20 minutes ...", "... every 2 hours".
    private static final Pattern EVERY = Pattern.compile(
        "\\bevery\\s+(\\d+)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|[hms])\\b");
    private static final Pattern ROUTINE_WORD = Pattern.compile(
        "\\b(farm|farming|crops|field|harvest|deposit|stash|store|unload|dropoff"
        + "|sort|organize|organise|tidy|cleanup|clean|pickup|hunt|hunting|food"
        + "|light|torch|lighting|lights)\\b");

    // Spoken-number words -> value. Voice recognition writes numbers as words
    // ("gather twenty logs"), but the parser reads digits, so we convert first.
    private static final java.util.Map<String, Integer> NUM_SMALL = java.util.Map.ofEntries(
        java.util.Map.entry("zero", 0), java.util.Map.entry("one", 1), java.util.Map.entry("two", 2),
        java.util.Map.entry("three", 3), java.util.Map.entry("four", 4), java.util.Map.entry("five", 5),
        java.util.Map.entry("six", 6), java.util.Map.entry("seven", 7), java.util.Map.entry("eight", 8),
        java.util.Map.entry("nine", 9), java.util.Map.entry("ten", 10), java.util.Map.entry("eleven", 11),
        java.util.Map.entry("twelve", 12), java.util.Map.entry("thirteen", 13), java.util.Map.entry("fourteen", 14),
        java.util.Map.entry("fifteen", 15), java.util.Map.entry("sixteen", 16), java.util.Map.entry("seventeen", 17),
        java.util.Map.entry("eighteen", 18), java.util.Map.entry("nineteen", 19));
    private static final java.util.Map<String, Integer> NUM_TENS = java.util.Map.of(
        "twenty", 20, "thirty", 30, "forty", 40, "fifty", 50,
        "sixty", 60, "seventy", 70, "eighty", 80, "ninety", 90);
    // Naming cues: a number word right after one of these is a proper NAME
    // (rename / waypoint / go-to target), not a count, so it stays a word —
    // otherwise "your name is seven" would become "your name is 7".
    private static final java.util.Set<String> NAME_CUES = java.util.Set.of(
        "as", "is", "to", "you", "yourself", "rename", "named", "called", "spot", "place", "the");

    private ChatControl() {}

    /** One parsed clause of the sentence. `arg` is the type-specific payload. */
    private record Action(Type type, @Nullable GatherGoal.Kind gatherKind, int amount,
                          @Nullable AssistantEntity.Mode mode, @Nullable String arg) {
        enum Type { GATHER, DEPOSIT, MODE, COME, STOP, STATUS, JOBS, HELP,
                    DISMISS, OPEN, GO_HOME, SET_HOME,
                    CRAFT, WITHDRAW, FARM, BUILD, SMELT,
                    MINE, HUNT, SHEAR, GIVE, GOTO, WAYPOINT_SET, PLACES, INVENTORY, LOOK,
                    CLEAR_AREA, TORCH_AREA, BRIDGE, BREED, HERD, FISH, CLEANUP,
                    REPAIR, LOCATE, NIGHT_ON, NIGHT_OFF,
                    SORT, ENCHANT, STOCK, NETHER, BOAT, MAKE, NEEDS, TOWN_GATHER,
                    ROLE, RENAME, AUTO_ON, AUTO_OFF, STANDING_ADD, STANDING_CLEAR,
                    ROUTINE_ADD, ROUTINE_CLEAR }

        static Action gather(GatherGoal.Kind k, int n) { return new Action(Type.GATHER, k, n, null, null); }
        static Action townGather(GatherGoal.Kind k, int n) { return new Action(Type.TOWN_GATHER, k, n, null, null); }
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
        boolean explicit = false;
        String text = lower;
        if (text.startsWith("!")) {
            explicit = true;
            text = text.substring(1).trim();
        }
        // Strip a generic address word ("hey assistant, ...") in ALL cases,
        // whether or not the message was "!"-prefixed. Voice always prefixes
        // with "!", so "hey assistant gather stone" must still be recognized
        // as addressed and stripped before name-routing.
        Matcher addr = ADDRESSED.matcher(text);
        if (addr.matches()) {
            explicit = true;
            text = addr.group(1).trim();
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
        // Spoken numbers -> digits first ("gather twenty logs" -> "gather 20
        // logs"), before any splitting, since "and" can be part of a number.
        text = spokenNumbers(text);

        // "patrol between X and Y" contains "and", which the clause splitter
        // would cut in half — handle the whole sentence up front.
        Matcher patrol = PATROL_BETWEEN.matcher(text);
        if (patrol.matches()) {
            a.clearQueue();
            a.enqueue(Job.patrol(patrol.group(1).trim(), patrol.group(2).trim()));
            return;
        }

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
                    ? "I can gather logs, stone, dirt, iron, or coal."
                    : "Didn't catch that — try \"gather 32 logs then deposit\", \"smelt 8 iron\", \"craft an iron pickaxe\", \"build a shelter\", or !help.");
            }
            return;
        }

        execute(a, player, actions, unknownMaterial, explicit);
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
        // Cancel scheduled chores — before the plain "stop" so it isn't eaten.
        if (c.matches("^(?:stop|cancel|clear|drop|end|no\\s+more)\\b.*\\broutines?\\b.*")
            || (c.matches("^(?:stop|cancel)\\b.*") && c.contains("every"))
            || c.contains("stop the schedule") || c.contains("stop scheduling")) {
            return Action.of(Action.Type.ROUTINE_CLEAR);
        }
        if (c.contains("take a break") || c.matches("^auto\\s*off\\b.*")
            || c.matches("^stop\\b.*\\bworking\\b.*") || c.contains("wait for orders")) {
            return Action.of(Action.Type.AUTO_OFF);
        }
        if (c.matches("^(?:stop|halt|cancel|never\\s?mind)\\b.*")) return Action.of(Action.Type.STOP);
        if (c.matches("^(?:status|report)\\b.*") || c.contains("how are you")) return Action.of(Action.Type.STATUS);
        if (c.matches("^(?:jobs|queue|tasks)\\b.*") || c.contains("what are you doing")) return Action.of(Action.Type.JOBS);
        if (c.matches("^help\\b.*") || c.contains("what can you do")) return Action.of(Action.Type.HELP);
        if (c.contains("what do you have") || c.contains("what are you carrying")
            || c.contains("what's in your") || c.matches("^inventory\\b.*")) {
            return Action.of(Action.Type.INVENTORY);
        }
        if (c.contains("what do you see") || c.contains("anything nearby")
            || c.matches("^look\\s+around\\b.*")) {
            return Action.of(Action.Type.LOOK);
        }
        if (c.matches("^(?:places|waypoints)\\b.*") || c.contains("where can you go")) {
            return Action.of(Action.Type.PLACES);
        }
        // Stock report: "how much iron do we have?", "how many logs have we got?"
        Matcher sq = STOCK_QUERY.matcher(c);
        if (sq.matches()) return Action.with(Action.Type.STOCK, sq.group(1).trim(), 0);
        // Self-assessment (rung 1 of autonomy): "what do you need?"
        if (c.contains("what do you need") || c.contains("what do you require")
            || c.matches("^(?:assess|check)\\s+yourself\\b.*") || c.contains("how are you doing")) {
            return Action.of(Action.Type.NEEDS);
        }
        if (c.contains("work on your own") || c.matches("^auto\\s*on\\b.*")
            || c.contains("be autonomous") || c.contains("do your own thing")
            || c.contains("survive") || c.contains("fend for yourself")
            || c.contains("look after yourself") || c.contains("stay alive")
            || c.contains("keep yourself alive")) {
            return Action.of(Action.Type.AUTO_ON);
        }

        // Scheduled chores: "tend the farm every 20 minutes", "sort the storage
        // every 10 min", "deposit surplus every hour". An interval word plus a
        // chore word makes a routine; it re-fires whenever idle and due.
        Matcher every = EVERY.matcher(c);
        if (every.find()) {
            Matcher rw = ROUTINE_WORD.matcher(c);
            if (rw.find()) {
                AssistantEntity.RoutineKind rk = AssistantEntity.RoutineKind.fromWord(rw.group(1));
                if (rk != null) {
                    int ticks = intervalTicks(Integer.parseInt(every.group(1)), every.group(2));
                    return Action.with(Action.Type.ROUTINE_ADD, rk.name(), ticks);
                }
            }
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

        // Crew order (multi-AI): "everyone gather 128 iron", "team, get 64 logs".
        // Splits the order across the whole crew, each hauling its share to the
        // shared depot — divided labor toward a common goal.
        if (c.matches("^(?:everyone|everybody|all of you|all|team|crew|squad|town|group|guys)\\b.*")) {
            GatherGoal.Kind crewKind = kindIn(c);
            if (crewKind != null) return Action.townGather(crewKind, parseAmount(c, 64));
        }

        // Targeted ore mining: "mine for diamonds" / "dig for iron" (any ore), or
        // "get me / find diamonds" for the deep ores you can't just gather. Digs
        // a mine to that ore's best depth and chases the veins.
        boolean mineVerb = c.matches("^(?:mine|dig)\\b.*") || c.contains("mine for") || c.contains("dig for");
        Matcher oreAny = ORE_ANY.matcher(c);
        if (mineVerb && oreAny.find()) {
            return Action.with(Action.Type.MINE, null, oreY(oreAny.group(1)));
        }
        Matcher oreDeep = ORE_DEEP.matcher(c);
        if ((c.contains("get me") || c.matches("^find\\b.*")) && oreDeep.find()) {
            return Action.with(Action.Type.MINE, null, oreY(oreDeep.group(1)));
        }

        // Problem-solving crafter: "make me an iron sword", "build me a chest",
        // "get me a stone pickaxe" — the planner sources every missing part.
        // ("give me X" is handing over what it carries; "build a <structure>"
        // stays a structure, so "build" only makes an ITEM when no structure word.)
        boolean makeVerb = c.matches("^(?:make|craft|forge|get)\\b.*")
            || c.contains("i need") || c.contains("i want")
            || (c.matches("^build\\b.*") && !BUILD_WORD.matcher(c).find());
        if (makeVerb) {
            // Match the item that's the OBJECT of the verb, not an incidental
            // noun in a "... from the chest/storage" tail (that's a WITHDRAW).
            String makeClause = c.split("\\b(?:from|out of|in the|in my|inside)\\b")[0];
            String tgt = CraftPlanner.matchTarget(makeClause);
            if (tgt != null) return Action.with(Action.Type.MAKE, tgt, parseAmount(c, 1));
        }

        // Nether expedition: "go to the nether and get glowstone", "nether run for quartz".
        // Standalone word only, so "netherite"/"netherrack" in other orders don't misfire.
        if (NETHER_WORD.matcher(c).find()) {
            String t = "glowstone";
            Matcher nt = NETHER_TARGET.matcher(c);
            if (nt.find()) {
                t = nt.group(1).replaceAll("\\s+", " ").trim();
                if (t.equals("nether gold")) t = "gold";
            }
            return Action.with(Action.Type.NETHER, t, parseAmount(c, 16));
        }

        // Boat crossing: "boat to 200 63 -400", "sail to ...", "take a boat to ...".
        if (c.matches("^(?:boat|sail)\\b.*") || (c.matches("^take\\b.*") && c.contains("boat"))) {
            Matcher co = COORDS.matcher(c.replaceAll("\\b(?:minus|negative)\\s+(\\d)", "-$1"));
            if (co.find()) {
                return Action.with(Action.Type.BOAT,
                    co.group(1) + " " + co.group(2) + " " + co.group(3), 0);
            }
            return explicit ? Action.with(Action.Type.BOAT, null, 0) : null;
        }

        // Go to raw coordinates: "meet me at 120 64 -300", "go to 100 70 -50".
        // (LEAD_IN strips a leading "go ", leaving "to 100 ...", so accept "to"/"at" too.)
        if (c.matches("^(?:go|come|walk|travel|head|meet|to|at)\\b.*")) {
            Matcher co = COORDS.matcher(c.replaceAll("\\b(?:minus|negative)\\s+(\\d)", "-$1"));
            if (co.find()) {
                return Action.with(Action.Type.GOTO,
                    co.group(1) + " " + co.group(2) + " " + co.group(3), 0);
            }
        }

        // Auto-sort storage: "sort the chests", "organize the storage".
        if (c.matches("^(?:sort|organize|organise)\\b.*")
            && (explicit || c.contains("chest") || c.contains("storage") || c.contains("item"))) {
            return Action.of(Action.Type.SORT);
        }

        // Enchanting: "enchant your gear/pickaxe/armor".
        Matcher ew = ENCHANT_WORD.matcher(c);
        if (ew.matches()) {
            String what = ew.group(1).trim();
            return Action.with(Action.Type.ENCHANT, what.isEmpty() ? null : what, 0);
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
        // Night routine toggles — must outrank the plain "home" patterns
        // ("go home at night" is a toggle, not an order to walk home now).
        if (c.contains("at night")) {
            if (c.contains("work") || c.matches("^(?:stop|don'?t|quit|no)\\b.*")) {
                return Action.of(Action.Type.NIGHT_OFF);
            }
            if (c.contains("home")) return Action.of(Action.Type.NIGHT_ON);
        }
        if (c.contains("night shift") || c.contains("work nights")) {
            return Action.of(Action.Type.NIGHT_OFF);
        }

        if (c.matches("^(?:set|make)\\b.*\\bhome\\b.*") || c.contains("this is home")) {
            return Action.of(Action.Type.SET_HOME);
        }
        if (c.matches("^(?:(?:head|return|walk)\\s+(?:back\\s+)?)?home\\b.*")) {
            return Action.of(Action.Type.GO_HOME);
        }

        // Repair, locate, and area work.
        Matcher rp = REPAIR_WORD.matcher(c);
        if (rp.matches()) {
            String w = rp.group(1).trim();
            // "fix it later dude" is chat; "repair your pickaxe" is an order.
            if (explicit || w.isEmpty()
                || w.matches(".*(?:pick|axe|shovel|sword|hoe|tool|shear|rod|helmet|plate|legging|boot|gear).*")) {
                return Action.with(Action.Type.REPAIR, w, 0);
            }
        }
        Matcher lc = LOCATE_WORD.matcher(c);
        if (lc.find()) return Action.with(Action.Type.LOCATE, lc.group(1), 0);
        if (c.matches("^(?:clear|flatten)\\b.*")
            && (explicit || c.contains("area") || c.startsWith("flatten") || AREA_DIM.matcher(c).find())) {
            Matcher dim = AREA_DIM.matcher(c);
            String size = dim.find() ? dim.group(1) + "x" + dim.group(2) : "8x8";
            return Action.with(Action.Type.CLEAR_AREA, size, 0);
        }
        if (c.matches("^(?:light|torch)\\b.*\\b(?:up|area|place|around|here)\\b.*")) {
            return Action.with(Action.Type.TORCH_AREA, null, parseAmount(c, 12));
        }
        if (c.matches("^bridge\\b.*")
            || (c.matches("^(?:build|construct)\\b.*") && c.contains("bridge"))) {
            return Action.of(Action.Type.BRIDGE);
        }
        if (c.matches("^(?:breed|mate)\\b.*")) {
            Matcher aw = ANIMAL_WORD.matcher(c);
            return Action.with(Action.Type.BREED, aw.find() ? aw.group(1) : null, parseAmount(c, 2));
        }
        if (c.matches("^(?:bring|herd|lead)\\b.*\\b(?:home|pen|back|in)\\b.*")) {
            Matcher aw = ANIMAL_WORD.matcher(c);
            if (aw.find()) {
                return Action.with(Action.Type.HERD, aw.group(1), parseAmount(c, 2));
            }
        }
        if (c.matches("^(?:fish(?:ing)?|catch)\\b.*") && (explicit || c.contains("fish"))) {
            return Action.with(Action.Type.FISH, null, parseAmount(c, 5));
        }
        if ((c.matches("^(?:pick|clean|tidy)\\s+up\\b.*") || c.contains("pick up the items"))
            && (explicit || c.contains("item") || c.contains("stuff") || c.contains("loot") || c.contains("drop"))) {
            return Action.of(Action.Type.CLEANUP);
        }

        // Waypoints: "remember this spot as the mine" / "go to the mine".
        Matcher wpAs = WAYPOINT_AS.matcher(c);
        if (wpAs.matches()) return Action.with(Action.Type.WAYPOINT_SET, wpAs.group(1).trim(), 0);
        Matcher wpCall = WAYPOINT_CALL.matcher(c);
        if (wpCall.matches()) return Action.with(Action.Type.WAYPOINT_SET, wpCall.group(1).trim(), 0);
        Matcher gp = GOTO_PLACE.matcher(c);
        if (gp.matches()) return Action.with(Action.Type.GOTO, gp.group(1).trim(), 0);

        // Real mining: "dig a mine", "mine down to level 12" (a bare
        // "mine 20 stone" stays a gather).
        if (c.matches("^(?:dig|mine)\\b.*\\b(?:mine|tunnel|shaft|down|level)\\b.*")) {
            Matcher lvl = MINE_LEVEL.matcher(c);
            int targetY = lvl.find() ? Integer.parseInt(lvl.group(1)) : 12;
            return Action.with(Action.Type.MINE, null, targetY);
        }

        // Hunting & shearing: "hunt 3 cows", "shear the sheep".
        if (c.matches("^(?:hunt|slaughter)\\b.*")
            || (c.matches("^kill\\b.*") && ANIMAL_WORD.matcher(c).find())) {
            Matcher aw = ANIMAL_WORD.matcher(c);
            String animal = aw.find() ? aw.group(1) : null;
            return Action.with(Action.Type.HUNT, animal, parseAmount(c, 3));
        }
        if (c.matches("^shear\\b.*")) {
            return Action.with(Action.Type.SHEAR, null, parseAmount(c, 8));
        }

        // Handing things over: "give me 10 torches", "hand me the logs".
        // ("...from the chest" is a withdraw; "...in the chest" is a deposit.)
        if (!c.contains("from") && !c.contains("chest") && !c.contains("barrel")) {
            Matcher gm = GIVE_ME.matcher(c);
            if (gm.matches()) {
                int n = gm.group(1) != null ? clamp(Integer.parseInt(gm.group(1))) : 8;
                return Action.with(Action.Type.GIVE, gm.group(2).trim(), n);
            }
        }

        // Farming: "farm", "tend the crops"; "harvest" only when it isn't
        // "harvest 20 logs" (that's a gather).
        if (c.matches("^(?:farm|tend)\\b.*")
            || (c.matches("^harvest\\b.*") && kindIn(c) == null)) {
            return Action.of(Action.Type.FARM);
        }

        // Building: "build a shelter", "build a furnace building", "construct a wall".
        if (c.matches("^(?:build|construct)\\b.*")) {
            Matcher bw = BUILD_WORD.matcher(c);
            if (bw.find()) {
                return Action.with(Action.Type.BUILD, normalizeStructure(bw.group(1)), 0);
            }
            return explicit ? Action.with(Action.Type.BUILD, null, 0) : null;
        }

        // Smelting: "smelt 16 iron", "cook the beef", "smelt logs" (charcoal).
        if (c.matches("^(?:smelt|cook)\\b.*")) {
            String word = SmeltGoal.canonical(c);
            if (word != null) return Action.with(Action.Type.SMELT, word, parseAmount(c, 8));
            return explicit ? Action.with(Action.Type.SMELT, null, 0) : null;
        }

        // Withdraw (before gather — "take"/"get"/"grab" overlap): needs
        // "... from the chest/barrel/storage".
        Matcher wd = WITHDRAW_FROM.matcher(c);
        if (wd.find()) {
            int n = wd.group(1) != null ? clamp(Integer.parseInt(wd.group(1))) : 8;
            String item = wd.group(2).trim().replaceAll("^(?:the|a|an|some)\\s+", "");
            return Action.with(Action.Type.WITHDRAW, item, n);
        }

        // Crafting fallback: "craft/make X" that the planner above didn't match
        // (planner handles the sourcing; this only fires for a bare/unknown arg).
        if (c.matches("^(?:craft|make)\\b.*")) {
            String recipe = CraftPlanner.matchTarget(
                c.split("\\b(?:from|out of|in the|in my|inside)\\b")[0]);
            if (recipe != null) return Action.with(Action.Type.MAKE, recipe, parseAmount(c, 1));
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

    /** Spoken structure words -> blueprint names. */
    public static String normalizeStructure(String word) {
        return switch (word) {
            case "hut" -> "shelter";
            case "house", "home" -> "house";
            case "forge", "furnace" -> "smeltery";
            case "warehouse" -> "storage";
            case "tower" -> "watchtower";
            case "enclosure", "barn", "pasture", "fence" -> "pen";
            default -> word;
        };
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

    /** "every N <unit>" -> tick interval, clamped to 1 min .. 1 hour. Units:
     *  h/hr/hour, m/min/minute (default), s/sec/second. */
    private static int intervalTicks(int n, String unit) {
        int perUnit = unit.startsWith("h") ? 72000
            : unit.startsWith("s") ? 20
            : 1200; // minute
        long ticks = (long) n * perUnit;
        return (int) Math.max(1200L, Math.min(72000L, ticks));
    }

    /** Ticks -> a friendly duration for the confirmation line. */
    private static String describeInterval(int ticks) {
        int minutes = Math.max(1, Math.round(ticks / 1200.0F));
        if (minutes % 60 == 0) {
            int h = minutes / 60;
            return h + " hour" + (h == 1 ? "" : "s");
        }
        return minutes + " minute" + (minutes == 1 ? "" : "s");
    }

    /**
     * Convert spoken number words into digits so voice amounts work:
     * "twenty" -> "20", "one hundred twenty eight" -> "128", "a hundred" ->
     * "100". Runs before clause-splitting because "and" can be part of a
     * number ("hundred and twenty"). Digits already in the text pass through,
     * and "a stack" is left for the stack patterns to handle.
     */
    static String spokenNumbers(String text) {
        String[] tok = text.split("\\s+");
        StringBuilder out = new StringBuilder();
        long[] val = new long[1];
        int i = 0;
        while (i < tok.length) {
            if (out.length() > 0) out.append(' ');
            // A number word right after a naming cue is a proper name, not a
            // count — leave it alone ("your name is seven", "go to seven").
            if (i > 0 && NAME_CUES.contains(tok[i - 1]) && isNumberWord(tok[i])) {
                out.append(tok[i]);
                i++;
                continue;
            }
            int consumed = consumeNumber(tok, i, val);
            if (consumed > 0) {
                out.append(val[0]);
                i += consumed;
            } else {
                out.append(tok[i]);
                i++;
            }
        }
        return out.toString();
    }

    /** Tokens forming a number starting at `start` (0 = none); value in val[0]. */
    private static int consumeNumber(String[] tok, int start, long[] val) {
        long result = 0;
        long current = 0;
        int i = start;
        boolean any = false;
        while (i < tok.length) {
            String w = tok[i];
            if (NUM_SMALL.containsKey(w)) {
                current += NUM_SMALL.get(w);
                any = true;
                i++;
            } else if (NUM_TENS.containsKey(w)) {
                current += NUM_TENS.get(w);
                any = true;
                i++;
            } else if (w.equals("hundred") && any) {
                current = (current == 0 ? 1 : current) * 100;
                i++;
            } else if (w.equals("thousand") && any) {
                result += (current == 0 ? 1 : current) * 1000;
                current = 0;
                i++;
            } else if ((w.equals("a") || w.equals("an")) && i + 1 < tok.length
                    && (tok[i + 1].equals("hundred") || tok[i + 1].equals("thousand"))) {
                current += 1;
                any = true;
                i++;
            } else if (w.equals("and") && any && i + 1 < tok.length && isNumberWord(tok[i + 1])) {
                i++; // connective inside a number: "hundred and twenty"
            } else {
                break;
            }
        }
        if (!any) return 0;
        val[0] = result + current;
        return i - start;
    }

    private static boolean isNumberWord(String w) {
        return NUM_SMALL.containsKey(w) || NUM_TENS.containsKey(w)
            || w.equals("hundred") || w.equals("thousand");
    }

    private static void execute(AssistantEntity a, ServerPlayer player, List<Action> actions,
                                boolean unknownMaterial, boolean explicit) {
        boolean single = actions.size() == 1;
        int queuedBefore = a.jobCount();
        List<String> queuedLabels = new ArrayList<>();

        for (Action act : actions) {
            switch (act.type()) {
                case STOP -> a.requestStop();
                case STATUS -> a.say(String.format(
                    "HP %.0f/%.0f, mode %s, role %s%s, %d items (%d food), %d xp, %d jobs queued, %d standing orders, %d routines.",
                    a.getHealth(), a.getMaxHealth(), a.getMode().name().toLowerCase(),
                    a.getRole().name().toLowerCase(), a.isAutonomous() ? " (working autonomously)" : "",
                    a.countItems(), a.countFood(), a.getXp(), a.jobCount(), a.standingOrders().size(),
                    a.routines().size()));
                case JOBS -> reportJobs(a);
                case HELP -> a.say("Talk to me like a person — orders chain with \"and\"/\"then\" and queue up. I understand: "
                    + "gather/mine/chop (logs, stone, dirt, iron, coal), \"dig a mine (down to level 12)\", deposit, "
                    + "\"grab X from the chest\", \"give me 10 torches\", smelt/cook, craft/make (tools, armor, bow, arrows...), "
                    + "\"build a wall/shelter/smeltery/storage/workshop/watchtower\", \"bridge forward\", \"clear a 10x10 area\", "
                    + "\"light up the area\", farm/harvest, \"hunt 3 cows\", \"breed the cows\", \"bring 2 cows home\", "
                    + "\"shear the sheep\", \"catch 5 fish\", \"pick up the items\", \"repair your pickaxe\", "
                    + "\"find the nearest village\", \"patrol between home and the mine\", \"head home at night\", "
                    + "follow/stay/guard/come/stop, go home, \"go to 120 64 -300\" (coordinates), "
                    + "\"remember this spot as the mine\" / \"go to the mine\", \"sort the storage\", "
                    + "\"how much iron do we have?\", \"enchant your gear\" (spends earned xp + lapis), "
                    + "\"what do you see/have?\", open (my gear), \"keep the chest stocked with 64 logs\", "
                    + "\"tend the farm every 20 minutes\" (recurring chores; \"stop routines\" cancels), "
                    + "\"be a miner/farmer/lumberjack/builder\" + \"work on your own\", \"your name is <name>\", spawn, dismiss. "
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
                        a.say("Tell me what to craft — I can make just about anything in the game, "
                            + "like \"make an iron sword\", \"craft a piston\", or \"make me a hopper\".");
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
                case SMELT -> {
                    if (act.arg() == null) {
                        a.say("I can smelt: " + SmeltGoal.smeltableList() + ".");
                    } else {
                        Job job = Job.smelt(act.arg(), act.amount());
                        a.enqueue(job);
                        queuedLabels.add(job.label());
                    }
                }
                case MINE -> {
                    Job job = Job.mine(act.amount());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case HUNT -> {
                    Job job = Job.hunt(act.arg(), act.amount());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case SHEAR -> {
                    Job job = Job.shear(act.amount());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case GIVE -> {
                    // Bare chat like "give up dude" must not trigger a reply —
                    // only hand over things we actually carry (unless addressed).
                    if (!explicit && a.countMatching(
                            com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor(act.arg())) == 0) {
                        break;
                    }
                    Job job = Job.give(act.arg(), act.amount());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case GOTO -> {
                    boolean coords = com.jrpetty.mcassistant.entity.goal.TravelGoal.parseCoords(act.arg()) != null;
                    boolean known = coords
                        || ("home".equals(act.arg()) ? a.getHome() != null : a.getWaypoint(act.arg()) != null);
                    if (!known && !explicit) {
                        break; // "to be honest..." is chat, not a travel order
                    }
                    if (single) a.clearQueue(); // a lone travel order takes over now
                    Job job = Job.goTo(act.arg());
                    a.enqueue(job);
                    if (!single) queuedLabels.add(job.label());
                }
                case WAYPOINT_SET -> {
                    a.setWaypoint(act.arg(), a.blockPosition());
                    a.say("Got it — this spot is \"" + act.arg() + "\" now. Say \"go to " + act.arg() + "\" anytime.");
                }
                case PLACES -> {
                    var names = a.waypointNames();
                    if (a.getHome() != null) names.add("home");
                    a.say(names.isEmpty()
                        ? "I don't know any places yet — say \"remember this spot as the mine\"."
                        : "I know: " + String.join(", ", names) + ".");
                }
                case INVENTORY -> sayInventory(a);
                case LOOK -> sayLook(a);
                case CLEAR_AREA -> {
                    Job job = Job.clear(act.arg());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case TORCH_AREA -> {
                    Job job = Job.torchArea(act.amount());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case BRIDGE -> {
                    Job job = Job.bridge();
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case BREED -> {
                    Job job = Job.breed(act.arg(), act.amount());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case HERD -> {
                    Job job = Job.herd(act.arg(), act.amount());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case FISH -> {
                    Job job = Job.fish(act.amount());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case CLEANUP -> {
                    Job job = Job.cleanup();
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case SORT -> {
                    Job job = Job.sort();
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case ENCHANT -> {
                    Job job = Job.enchant(act.arg());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case STOCK -> {
                    String word = act.arg();
                    int n = a.countAcrossStorage(
                        com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor(word));
                    a.say(n > 0
                        ? "We have " + n + " " + word + " across my pack and the chests I know."
                        : "None that I know of — no " + word + " in my pack or any chest I've seen.");
                }
                case MAKE -> {
                    String what = CraftPlanner.pretty(act.arg());
                    var plan = CraftPlanner.plan(a, act.arg(), act.amount());
                    if (plan.jobs().isEmpty() && plan.blockers().isEmpty()) {
                        a.say("You've already got " + what + " — it's in my pack or a chest here.");
                    } else if (plan.jobs().isEmpty()) {
                        a.say("To make " + what + " I'd need " + String.join(", ", plan.blockers())
                            + " — I can't gather that or find it in a nearby chest. Put some in a chest or hand it to me.");
                    } else {
                        for (Job j : plan.jobs()) a.enqueue(j);
                        a.say("To make " + what + ": " + String.join(", ", plan.narration()) + ". On it.");
                        if (!plan.blockers().isEmpty()) {
                            a.say("(Heads up — I'm still missing " + String.join(", ", plan.blockers())
                                + "; I'll get as far as I can.)");
                        }
                    }
                }
                case NEEDS -> a.say("Right now: " + String.join("; ", a.assessNeeds()) + ".");
                case TOWN_GATHER -> {
                    java.util.UUID owner = a.getOwnerId();
                    java.util.List<AssistantEntity> crew = new java.util.ArrayList<>();
                    if (owner != null) {
                        for (AssistantEntity m : AssistantEntity.allFor(owner)) {
                            if (m.level() == a.level()) crew.add(m);
                        }
                    }
                    if (crew.isEmpty()) crew.add(a);
                    int per = Math.max(1, act.amount() / crew.size());
                    for (AssistantEntity m : crew) {
                        m.enqueue(Job.gather(act.gatherKind(), per));
                        m.enqueue(Job.deposit());
                    }
                    a.say("Rallying the crew — " + crew.size() + " of us on it, gathering "
                        + act.amount() + " " + act.gatherKind().label + " (~" + per
                        + " each) and stashing to the depot.");
                }
                case NETHER -> {
                    Job job = Job.nether(act.arg(), act.amount());
                    a.enqueue(job);
                    queuedLabels.add(job.label());
                }
                case BOAT -> {
                    if (act.arg() == null) {
                        a.say("Boat to where? Give me coordinates — \"boat to 200 63 -400\".");
                    } else {
                        Job job = Job.boat(act.arg());
                        a.enqueue(job);
                        queuedLabels.add(job.label());
                    }
                }
                case REPAIR -> repairItems(a, act.arg() != null ? act.arg() : "");
                case LOCATE -> locateStructure(a, act.arg());
                case NIGHT_ON -> {
                    a.setNightHome(true);
                    a.say(a.getHome() == null
                        ? "I'll head home at night — but I need a home first (Spawner block or \"set home here\")."
                        : "Understood — home at dusk, back to it at dawn.");
                }
                case NIGHT_OFF -> {
                    a.setNightHome(false);
                    a.say("Night shift it is — I'll keep working after dark.");
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
                        ? "I'll fend for myself now — I'll keep fed, keep a working pickaxe, repair my gear, and take initiative when I'm idle. Give me a role (miner/lumberjack/farmer/builder) if you want me stockpiling too."
                        : "On it — I'll look after myself and keep doing " + a.getRole().name().toLowerCase() + " work whenever I'm idle.");
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
                case ROUTINE_ADD -> {
                    AssistantEntity.RoutineKind rk = act.arg() == null ? null
                        : AssistantEntity.RoutineKind.valueOf(act.arg());
                    if (rk == null) {
                        a.say("Tell me a chore and a time — e.g. \"tend the farm every 20 minutes\".");
                    } else {
                        a.addRoutine(rk, act.amount());
                        a.say("Scheduled: I'll " + rk.label + " every " + describeInterval(act.amount())
                            + " whenever I'm free. \"stop routines\" cancels it.");
                    }
                }
                case ROUTINE_CLEAR -> {
                    int n = a.clearRoutines();
                    a.say(n > 0 ? "Cleared " + n + " routine" + (n == 1 ? "" : "s") + "." : "No routines scheduled.");
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
            a.say("(One part asked for a material I can't gather yet — I know logs, stone, dirt, iron, and coal.)");
        }
    }

    /** Grindstone-style repair: two damaged same-type tools become one better one. */
    private static void repairItems(AssistantEntity a, String word) {
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> match =
            word.isEmpty() || word.startsWith("tool") || word.startsWith("gear")
                ? net.minecraft.world.item.ItemStack::isDamageableItem
                : com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor(word);
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            var s1 = inv.get(i);
            if (s1.isEmpty() || !s1.isDamageableItem() || s1.getDamageValue() == 0 || !match.test(s1)) continue;
            for (int k = i + 1; k < inv.size(); k++) {
                var s2 = inv.get(k);
                if (s2.isEmpty() || !s2.is(s1.getItem()) || !s2.isDamageableItem()) continue;
                int max = s1.getMaxDamage();
                int remaining = (max - s1.getDamageValue()) + (max - s2.getDamageValue()) + max * 5 / 100;
                s1.setDamageValue(Math.max(0, max - Math.min(max, remaining)));
                inv.set(k, net.minecraft.world.item.ItemStack.EMPTY);
                a.say("Combined two " + s1.getHoverName().getString() + "s — now at "
                    + (100 * (max - s1.getDamageValue()) / max) + "% durability.");
                return;
            }
        }
        a.say("I need two damaged copies of the same tool to combine"
            + (word.isEmpty() ? "" : " (" + word + ")") + ".");
    }

    /** "find the nearest village" — real structure locator + a saved waypoint. */
    private static void locateStructure(AssistantEntity a, String word) {
        if (!(a.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        var tag = switch (word) {
            case "village" -> net.minecraft.tags.StructureTags.VILLAGE;
            case "mineshaft" -> net.minecraft.tags.StructureTags.MINESHAFT;
            case "shipwreck" -> net.minecraft.tags.StructureTags.SHIPWRECK;
            case "stronghold" -> net.minecraft.tags.StructureTags.EYE_OF_ENDER_LOCATED;
            default -> net.minecraft.tags.StructureTags.RUINED_PORTAL;
        };
        a.say("Scanning for the nearest " + word + " — one moment...");
        net.minecraft.core.BlockPos found =
            level.findNearestMapStructure(tag, a.blockPosition(), 100, false);
        if (found == null) {
            a.say("No " + word + " anywhere near here.");
            return;
        }
        double dx = found.getX() - a.getX();
        double dz = found.getZ() - a.getZ();
        int dist = (int) Math.sqrt(dx * dx + dz * dz);
        String ew = dx > 0 ? "east" : "west";
        String ns = dz > 0 ? "south" : "north";
        String dir = Math.abs(dx) > 2 * Math.abs(dz) ? ew
            : Math.abs(dz) > 2 * Math.abs(dx) ? ns : ns + ew;
        a.setWaypoint(word, found);
        a.say("Found a " + word + " about " + dist + " blocks " + dir
            + " of here — say \"go to " + word + "\" and I'll lead the way.");
    }

    /** "what are you carrying?" — itemized pack contents. */
    private static void sayInventory(AssistantEntity a) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (var stack : a.getInventoryItems()) {
            if (stack.isEmpty()) continue;
            counts.merge(stack.getHoverName().getString(), stack.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) {
            a.say("Pack's empty.");
            return;
        }
        var sorted = counts.entrySet().stream()
            .sorted((x, y) -> y.getValue() - x.getValue()).toList();
        StringBuilder sb = new StringBuilder("Carrying: ");
        int shown = 0;
        for (var e : sorted) {
            if (shown++ == 8) {
                sb.append(" (+").append(sorted.size() - 8).append(" more kinds)");
                break;
            }
            if (shown > 1) sb.append(", ");
            sb.append(e.getValue()).append(" ").append(e.getKey());
        }
        a.say(sb + ".");
    }

    /** "what do you see?" — nearby threats, animals, and loose loot. */
    private static void sayLook(AssistantEntity a) {
        var box = a.getBoundingBox().inflate(16.0);
        java.util.Map<String, Integer> hostiles = new java.util.TreeMap<>();
        for (var m : a.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, box,
                net.minecraft.world.entity.LivingEntity::isAlive)) {
            hostiles.merge(m.getName().getString(), 1, Integer::sum);
        }
        java.util.Map<String, Integer> animals = new java.util.TreeMap<>();
        for (var an : a.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, box,
                net.minecraft.world.entity.LivingEntity::isAlive)) {
            animals.merge(an.getName().getString(), 1, Integer::sum);
        }
        int loot = a.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box).size();

        StringBuilder sb = new StringBuilder();
        if (hostiles.isEmpty()) {
            sb.append("No hostiles in sight.");
        } else {
            sb.append("Hostiles: ");
            sb.append(String.join(", ", hostiles.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey()).toList()));
            sb.append(".");
        }
        if (!animals.isEmpty()) {
            sb.append(" Animals: ").append(String.join(", ", animals.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey()).toList())).append(".");
        }
        if (loot > 0) {
            sb.append(" ").append(loot).append(" item drop").append(loot == 1 ? "" : "s").append(" on the ground.");
        }
        a.say(sb.toString());
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
