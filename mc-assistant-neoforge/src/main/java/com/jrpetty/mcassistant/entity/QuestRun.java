package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.entity.QuestBook.State;
import com.jrpetty.mcassistant.entity.QuestBook.Step;
import com.jrpetty.mcassistant.entity.QuestBook.StepType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [quests] The quests as they are played: offered, taken on, done step by step in the world, and paid for.
 *
 * <ul>
 * <li><b>Offered.</b> A folk with a real need (QuestMaker: its own favours, the town's wants, the war, the caves),
 *     or a story the town's state brings about (QuestStories), offers a quest to whoever asks it for work. It shows
 *     a gold "!" over its head while it does; ask it "Any work for me?" (the talk screen's Ask tab) and it tells
 *     you, in its own words, and you take it on or say not now. Nobody else can take the same favour once you have.
 *     An offer nobody takes is withdrawn after three days.</li>
 * <li><b>Done.</b> Each step is a real thing: go to a place, find somebody, pick something up or take it out of a
 *     chest, hand something over, talk to somebody, kill what is there, break a spawner, light a dark place, or make
 *     a choice. What is done by being there is noticed once a second; what is done by talking is done when you talk
 *     to them (a "?" over their head says so). A step's note goes into the journal.</li>
 * <li><b>Paid.</b> When the last step is done the giver pays what it promised, out of its own purse or the
 *     treasury, with goods out of the stores; it and the town think the better of you, the chronicle tells of it,
 *     and a story's end may bring a title, the town's medal or its key (QuestRewards). Given up, or left past its
 *     day, the giver is let down and says so, and the town remembers.</li>
 * </ul>
 * Everything is kept with the world (QuestBook). /village quests lists a player's quests; the Quest Journal shows
 * them on a page.
 */
public final class QuestRun {

    private QuestRun() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The most quests a player carries at once. */
    static final int MOST_AT_ONCE = 6;
    /** An offer stands this many days. */
    static final int OFFER_DAYS = 3;
    /** How far off a quest giver's mark is seen. */
    static final double MARKS = 40.0;

    // ------------------------------------------------------------------ scripts

    /** What a kind of quest does at each turn. Every method but {@link #accepted} has a plain default. */
    interface Script {
        /**
         * Taken on: the steps written, its things handed over. Returns what the giver says; null when it cannot be
         * done after all (the makings gone), with the reason in the quest's "refused" flag.
         */
        @Nullable
        String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p);

        /** A step done: what is said of it (by the folk, if one is there), and the next steps written. */
        default String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            return "";
        }

        /** A choice made at a CHOOSE step: what is said, and what comes of it. */
        default String chose(ServerLevel level, Quest q, Step s, String option, VillageFolkEntity f, Player p) {
            return reached(level, q, s, f, p);
        }

        /** The folk a step waits on, talked to before it is done: what it says. */
        @Nullable
        default String waiting(ServerLevel level, Quest q, Step s, VillageFolkEntity f, Player p) {
            return null;
        }

        /** Once a second while its player is about: the script's own watching. */
        default void tick(ServerLevel level, Quest q, Player p) {}

        /** Once in a while for the town, its player about or not (a child's tracks fade, the smugglers' nights). */
        default void townTick(ServerLevel level, Villages.Village v, Quest q) {}

        /** Every step done: the ending's page. The reward is paid after it. */
        default String ending(ServerLevel level, Quest q, Player p) {
            return "";
        }

        /** Over, however it ended: what it holds let go. */
        default void ended(ServerLevel level, Quest q) {}

        /** A folk with a part in it, held to its part. True while it is. */
        default boolean hold(VillageFolkEntity f, ServerLevel level, Quest q) {
            return false;
        }

        /** Can it still be done? (The friend it was for is dead, the war is over.) An offer that cannot is withdrawn. */
        default boolean stands(ServerLevel level, Quest q) {
            return true;
        }
    }

    private static final Map<String, Script> SCRIPTS = new ConcurrentHashMap<>();

    static void script(String key, Script s) {
        SCRIPTS.put(key, s);
    }

    /** The script a quest runs by: its own, or the plain one (steps done, paid at the end). */
    static Script script(Quest q) {
        ensure();
        Script s = SCRIPTS.get(q.script);
        return s != null ? s : PLAIN;
    }

    private static volatile boolean registered;

    private static void ensure() {
        if (registered) return;
        synchronized (SCRIPTS) {
            if (registered) return;
            QuestMaker.register();
            QuestStories.register();
            // The shop's workshop keeps a couple of journals in the stores once the town has quests going.
            Workshop.demand("the quest journals", (level, v, want) -> {
                if (QuestBook.hasGivers(v.id())) want.accept(com.jrpetty.mcassistant.McAssistantMod.QUEST_JOURNAL.get(), 2);
            });
            registered = true;
        }
    }

    /** A quest whose steps are all written when it is offered: done, and paid. */
    static final Script PLAIN = (level, q, giver, p) -> QuestTalk.taken(giver, q);

    // ------------------------------------------------------------------ the clock

    private static final Map<UUID, String> MARKS_SENT = new ConcurrentHashMap<>();
    /** The folk a story has a part for, and its quest: rebuilt every second. */
    private static volatile Map<UUID, Integer> cast = Map.of();
    private static final Map<String, Long> THANKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** Givers put up for the pictures (/village quests stage), stood still till this game time. */
    private static final Map<UUID, Long> STAGED = new ConcurrentHashMap<>();
    /** Tests: the towns' own look round (offers, stories, honours) held off, so a test's offers are its own. */
    private static volatile boolean quiet;

    public static void quietForTests(boolean on) {
        quiet = on;
    }

    public static void resetForTests() {
        MARKS_SENT.clear();
        cast = Map.of();
        THANKED.clear();
        LOOKED.clear();
        STAGED.clear();
        QuestStories.resetForTests();
        QuestMaker.resetForTests();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int t = event.getServer().getTickCount();
        if (t % 20 != 13 && t % 100 != 57) return;
        com.jrpetty.mcassistant.Guard.run("quests", () -> {
            if (t % 20 == 13) {
                recast();
                for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) check(p.serverLevel(), p);
            }
            if (t % 100 == 57) {
                for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
                    marks(p);
                    QuestTalk.reactions(p.serverLevel(), p);
                }
                for (ServerLevel level : event.getServer().getAllLevels()) {
                    if (quiet) break;                       // the tests make their own offers, and nobody else's
                    for (Villages.Village v : Villages.every()) {
                        if (v.dim().equals(level.dimension()) && level.isLoaded(v.centre())) look(level, v);
                    }
                }
            }
        });
    }

    /** A town's quests looked over, every half a minute: offers withdrawn or made, a story begun, honours made. */
    public static void look(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        if (now - LOOKED.getOrDefault(v.id(), -100000L) < 600L) return;
        LOOKED.put(v.id(), now);
        lookNow(level, v);
    }

    static void lookNow(ServerLevel level, Villages.Village v) {
        ensure();
        long day = day(level);
        for (Quest q : QuestBook.open(v.id())) {
            Script s = script(q);
            try {
                s.townTick(level, v, q);
            } catch (RuntimeException e) {
                LOG.warn("[MCA-QUESTS] {} #{}: {}", q.script, q.id, e.toString());
            }
            if (q.state != State.OFFERED) continue;
            if (day - q.posted > OFFER_DAYS || !s.stands(level, q)) withdraw(level, q, day - q.posted > OFFER_DAYS ? "nobody took it" : "it was no longer wanted");
        }
        QuestMaker.look(level, v, day);
        QuestStories.look(level, v, day);
        QuestRewards.look(level, v, day);
    }

    /** An offer nobody took, or that cannot be done any more: taken down. */
    static void withdraw(ServerLevel level, Quest q, String why) {
        q.state = State.FAILED;
        q.finished = day(level);
        q.outcome = "withdrawn: " + why;
        script(q).ended(level, q);
        QuestBook.remove(q.id);
        LOG.info("[MCA-QUESTS] {} withdrew {} ({}): {}", q.giverName, q.title, q.script, why);
    }

    public static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    // ------------------------------------------------------------------ offering

    /** A new offer: kept, if the giver is not already giving one. Returns whether it was. */
    static boolean offer(ServerLevel level, Quest q) {
        if (QuestBook.giving(q.giver)) return false;
        q.state = State.OFFERED;
        q.posted = day(level);
        QuestBook.add(q);
        LOG.info("[MCA-QUESTS] {} of {} offers \"{}\" ({}, {} coins from the {})", q.giverName, Villages.name(q.village), q.title,
            q.script, q.coins, q.payer);
        return true;
    }

    /** A quest taken on: it is this player's now. What the giver says. */
    public static String accept(ServerLevel level, VillageFolkEntity f, Player p) {
        ensure();
        Quest q = QuestBook.offerOf(f.getUUID());
        if (q == null) {
            for (Quest o : QuestBook.open(f.ownerId() == null ? new UUID(0, 0) : f.ownerId())) {
                if (f.getUUID().equals(o.giver) && o.state == State.ACTIVE && !p.getUUID().equals(o.player)) {
                    return QuestTalk.voice(f, o.playerName + "'s already seeing to that for me. Kind of you, though.",
                        o.playerName + "'s already on it. You're too late.", "Oh — " + o.playerName + " is already helping me. Sorry!",
                        o.playerName + " already beat you to it! But thank you!");
                }
            }
            return "I've nothing for you just now. Ask me again another day.";
        }
        long day = day(level);
        UUID village = q.village;
        Standing.Title title = Standing.of(village, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST || Laws.banished(village, p.getUUID(), day)) {
            return "From you? No. I'd sooner do without.";
        }
        if (QuestBook.active(p.getUUID()).size() >= MOST_AT_ONCE) {
            return "You've enough on your plate already. See those through first.";
        }
        String need = q.flag("standing");
        if (!need.isEmpty()) {
            try {
                if (!title.atLeast(Standing.Title.valueOf(need))) {
                    return QuestTalk.voice(f, "I'd not trust this to a stranger. Get to know us first.",
                        "This is town business. Not for strangers.", "I… I don't know you well enough for this. Sorry.",
                        "Oh, I wish I could ask you — but folk here hardly know you yet!");
                }
            } catch (IllegalArgumentException ignored) {
                // a standing from a later build: no bar
            }
        }
        q.player = p.getUUID();
        q.playerName = p.getName().getString();
        q.state = State.ACTIVE;
        q.taken = day;
        q.deadline = day + q.days;
        String said;
        try {
            said = script(q).accepted(level, q, f, p);
        } catch (RuntimeException e) {
            LOG.warn("[MCA-QUESTS] {} #{} would not start: {}", q.script, q.id, e.toString());
            said = null;
        }
        if (said == null) {
            q.player = null;
            q.playerName = "";
            q.state = State.OFFERED;
            q.taken = -1;
            String why = q.flag("refused");
            QuestBook.changed();
            return "Oh — I can't ask it of you after all" + (why.isEmpty() ? "." : ": " + why + ".");
        }
        f.persona().remember(day, q.playerName + " said they'd help me: " + lower(q.title), 2);
        Cartographers.questMap(level, q, p);                             // [cartographer] a map to a far place, from the map room
        QuestRewards.firstJournal(level, f, p);
        QuestBook.changed();
        Step next = q.current();
        tell(p, Component.literal("Quest taken: " + q.title + ". ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(next == null ? "" : next.text).withStyle(ChatFormatting.YELLOW)));
        LOG.info("[MCA-QUESTS] {} took on \"{}\" (#{}) from {} of {}", q.playerName, q.title, q.id, q.giverName, Villages.name(village));
        return said;
    }

    /** "Not just now." The offer stands for somebody else. */
    public static String decline(VillageFolkEntity f, Player p) {
        Quest q = QuestBook.offerOf(f.getUUID());
        if (q == null) return "Not to worry.";
        f.persona().feelFor(p.getUUID(), p.getName().getString(), -1);
        return QuestTalk.voice(f, "Another time, then.", "Suit yourself.", "Oh. That's — that's all right.", "No harm in asking! Another time!");
    }

    // ------------------------------------------------------------------ the steps, once a second

    /** A player's quests looked at: what it has done by being there, and what is past its day. */
    static void check(ServerLevel level, Player p) {
        ensure();
        long day = day(level);
        for (Quest q : QuestBook.active(p.getUUID())) {
            if (q.state != State.ACTIVE) continue;
            if (day > q.deadline) {
                fail(level, q, p, "ran out of time");
                continue;
            }
            Script s = script(q);
            try {
                s.tick(level, q, p);
            } catch (RuntimeException e) {
                LOG.warn("[MCA-QUESTS] {} #{} tick: {}", q.script, q.id, e.toString());
            }
            if (q.state != State.ACTIVE) continue;
            Step step = q.current();
            if (step == null) {
                finish(level, q, p);
                continue;
            }
            if (!step.dim.isEmpty() && !level.dimension().location().toString().equals(step.dim)
                    && step.type != StepType.GET) continue;
            switch (step.type) {
                case GO -> {
                    if (step.at != null && near(p, step.at, step.radius) && (!step.night || night(level))) reached(level, q, step, null, p);
                }
                case FIND -> {
                    Entity e = step.who == null ? null : level.getEntity(step.who);
                    if (e != null && e.isAlive() && p.distanceToSqr(e) <= (double) step.radius * step.radius) {
                        reached(level, q, step, e instanceof VillageFolkEntity f ? f : null, p);
                    }
                }
                case GET -> {
                    if (QuestItems.count(p, QuestItems.matcher(step.item, q.id)) >= step.count) reached(level, q, step, null, p);
                }
                case SEAL -> {
                    if (step.at != null && level.isLoaded(step.at) && near(p, step.at, 24)
                            && !level.getBlockState(step.at).is(Blocks.SPAWNER)) reached(level, q, step, null, p);
                }
                case LIGHT -> {
                    if (step.at != null && level.isLoaded(step.at) && near(p, step.at, 16)) {
                        int lit = lights(level, step.at, step.radius);
                        if (lit != step.progress) {
                            step.progress = lit;
                            QuestBook.changed();
                        }
                        if (lit >= step.count) reached(level, q, step, null, p);
                    }
                }
                default -> { }
            }
        }
    }

    /** Tests: the once-a-second look at a player's quests, for a player the server does not tick. */
    public static void checkForTests(ServerLevel level, Player p) {
        recast();
        check(level, p);
    }

    static boolean near(Player p, BlockPos at, int r) {
        double dx = p.getX() - (at.getX() + 0.5), dy = p.getY() - at.getY(), dz = p.getZ() - (at.getZ() + 0.5);
        return dx * dx + dz * dz <= (double) r * r && Math.abs(dy) <= Math.max(6, r);
    }

    static boolean night(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        return t >= 13000L && t < 23000L;
    }

    /** Torches, lanterns and the like within so far of a place. */
    static int lights(ServerLevel level, BlockPos at, int r) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-r, -3, -r), at.offset(r, 3, r))) {
            BlockState s = level.getBlockState(q);
            if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.LANTERN) || s.is(Blocks.SOUL_TORCH)
                    || s.is(Blocks.SOUL_WALL_TORCH) || s.is(Blocks.SOUL_LANTERN) || s.is(Blocks.GLOWSTONE) || s.is(Blocks.SEA_LANTERN)
                    || s.is(Blocks.SHROOMLIGHT) || s.is(Blocks.JACK_O_LANTERN)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ monsters

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || !(event.getEntity() instanceof Enemy)) return;
        if (!(event.getSource().getEntity() instanceof Player p)) return;
        killed(level, p, event.getEntity());
    }

    /** A monster a player killed: does it count toward a step of its? */
    public static void killed(ServerLevel level, Player p, LivingEntity dead) {
        for (Quest q : QuestBook.active(p.getUUID())) {
            Step s = q.current();
            if (s == null || s.type != StepType.KILL || s.at == null) continue;
            if (!s.mob.isEmpty() && !s.mob.equals("monsters") && !s.mob.equals(Quests.kind(dead))) continue;
            if (dead.blockPosition().distSqr(s.at) > (double) s.radius * s.radius) continue;
            s.progress++;
            QuestBook.changed();
            if (s.progress >= s.count) reached(level, q, s, null, p);
            else tell(p, Component.literal(q.title + ": " + s.progress + " of " + s.count).withStyle(ChatFormatting.YELLOW));
        }
    }

    // ------------------------------------------------------------------ talking

    /** The step of this player's waiting on this folk, if any (and its quest). */
    @Nullable
    static Object[] stepWith(VillageFolkEntity f, Player p) {
        for (Quest q : QuestBook.active(p.getUUID())) {
            Step s = q.current();
            if (s == null || !f.getUUID().equals(s.who)) continue;
            if (s.type == StepType.TALK || s.type == StepType.GIVE || s.type == StepType.DELIVER || s.type == StepType.CHOOSE) {
                return new Object[]{ q, s };
            }
        }
        return null;
    }

    /**
     * Talking to a folk a step waits on: the step done (a word, a hand-over), or the choice made. What the folk
     * says, or null when no step of this player's waits on it.
     */
    @Nullable
    public static String talk(ServerLevel level, VillageFolkEntity f, Player p, @Nullable String choice) {
        Object[] found = stepWith(f, p);
        if (found == null) return null;
        Quest q = (Quest) found[0];
        Step s = (Step) found[1];
        Script sc = script(q);
        switch (s.type) {
            case TALK -> {
                return reached(level, q, s, f, p);
            }
            case GIVE -> {
                Predicate<ItemStack> want = QuestItems.matcher(s.item, q.id);
                int need = s.count - s.progress;
                List<ItemStack> got = QuestItems.take(p, want, need);
                int n = 0;
                for (ItemStack g : got) n += g.getCount();
                if (n == 0) {
                    String w = sc.waiting(level, q, s, f, p);
                    return w != null ? w : "Have you brought " + QuestItems.words(s.item, need) + "? I'll wait.";
                }
                s.progress += n;
                String to = q.flags.getOrDefault("give." + s.key, "stores");
                Villages.Village v = Villages.get(q.village);
                for (ItemStack g : got) {
                    switch (to) {
                        case "folk" -> QuestItems.toFolk(f, g);
                        case "keep" -> q.flags.merge("kept." + s.key, Integer.toString(g.getCount()),
                            (a, b) -> Integer.toString(Integer.parseInt(a) + Integer.parseInt(b)));
                        default -> {
                            UUID into = f.ownerId() != null ? f.ownerId() : q.village;
                            ItemStack left = Market.intoStores(level, into, g);
                            if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, f.blockPosition(), left);
                        }
                    }
                }
                QuestBook.changed();
                if (s.progress < s.count) {
                    return "That's " + s.progress + " of " + s.count + ". Bring the rest when you can.";
                }
                return reached(level, q, s, f, p);
            }
            case DELIVER -> {
                Predicate<ItemStack> want = QuestItems.matcher(s.item, q.id);
                List<ItemStack> got = QuestItems.take(p, want, 1);
                if (got.isEmpty()) {
                    String w = sc.waiting(level, q, s, f, p);
                    return w != null ? w : "You were to bring me " + QuestItems.words(s.item, 1) + " — have you lost it?";
                }
                // Delivered: theirs now (the script may take it on from there).
                for (ItemStack g : got) {
                    if (!"gone".equals(q.flags.get("deliver." + s.key))) QuestItems.toFolk(f, g);
                }
                return reached(level, q, s, f, p);
            }
            case CHOOSE -> {
                if (choice == null || s.optionLabel(choice) == null) {
                    String w = sc.waiting(level, q, s, f, p);
                    return w != null ? w : "What will it be?";
                }
                q.flags.put("chose." + s.key, choice);
                s.note = (s.note.isEmpty() ? "" : s.note + " ") + "You chose: " + s.optionLabel(choice) + ".";
                s.done = true;
                String said = sc.chose(level, q, s, choice, f, p);
                return after(level, q, said, p);
            }
            default -> {
                return null;
            }
        }
    }

    /** A step done: the script told, the next step written, the end come if it has. What is said. */
    static String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
        s.done = true;
        String said;
        try {
            said = script(q).reached(level, q, s, f, p);
        } catch (RuntimeException e) {
            LOG.warn("[MCA-QUESTS] {} #{} step {}: {}", q.script, q.id, s.key, e.toString());
            said = "";
        }
        LOG.info("[MCA-QUESTS] {}: \"{}\" step {} ({}) done", q.playerName, q.title, s.key, s.type);
        return after(level, q, said, p);
    }

    private static String after(ServerLevel level, Quest q, String said, Player p) {
        QuestBook.changed();
        if (q.state != State.ACTIVE) return said;
        Step next = q.current();
        if (next == null) return join(said, finish(level, q, p));
        // Done by being there, not by a word: told, so the player knows what is next.
        tell(p, Component.literal(q.title + ": ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(next.text).withStyle(ChatFormatting.YELLOW)));
        return said;
    }

    // ------------------------------------------------------------------ the end

    /** Every step done: the ending, the pay, the town's thanks. What is said of it. */
    static String finish(ServerLevel level, Quest q, Player p) {
        if (q.state != State.ACTIVE) return "";
        long day = day(level);
        String ending;
        try {
            ending = script(q).ending(level, q, p);
        } catch (RuntimeException e) {
            LOG.warn("[MCA-QUESTS] {} #{} ending: {}", q.script, q.id, e.toString());
            ending = "";
        }
        q.state = State.DONE;
        q.finished = day;
        if (q.outcome.isEmpty()) q.outcome = "done";
        if (!ending.isEmpty() && q.ending.isEmpty()) q.ending = ending;
        String paid = QuestRewards.pay(level, q, p);
        QuestBook.tally(q.player, q.village);
        QuestRewards.honours(level, q, p);
        script(q).ended(level, q);
        if (q.story()) QuestBook.storyEnded(q.village, day);
        QuestBook.trim(q.player);
        QuestBook.changed();
        tell(p, Component.literal("Quest done: " + q.title + ". ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(paid.isEmpty() ? "" : "Paid: " + paid + ".").withStyle(ChatFormatting.YELLOW)));
        LOG.info("[MCA-QUESTS] {} finished \"{}\" (#{}): {}; paid {}", q.playerName, q.title, q.id, q.outcome, paid);
        return paid.isEmpty() ? "" : QuestTalk.paidLine(q, paid);
    }

    /** Given up: the giver let down, the town the cooler for it. */
    public static String abandon(ServerLevel level, Quest q, Player p) {
        if (q.state != State.ACTIVE || !p.getUUID().equals(q.player)) return "That's not yours to give up.";
        end(level, q, State.ABANDONED, "given up", 12);
        LOG.info("[MCA-QUESTS] {} gave up \"{}\" (#{})", q.playerName, q.title, q.id);
        return "You gave up " + q.title + ". " + q.giverName + " will not be pleased.";
    }

    /** Left past its day. */
    static void fail(ServerLevel level, Quest q, @Nullable Player p, String why) {
        end(level, q, State.FAILED, why, 8);
        if (p != null) tell(p, Component.literal("Quest failed: " + q.title + " — " + why + ".").withStyle(ChatFormatting.RED));
        LOG.info("[MCA-QUESTS] \"{}\" (#{}) failed: {}", q.title, q.id, why);
    }

    private static void end(ServerLevel level, Quest q, State how, String why, int letDown) {
        long day = day(level);
        q.state = how;
        q.finished = day;
        q.outcome = why;
        QuestRewards.letDown(level, q, letDown);
        try {
            script(q).ended(level, q);
        } catch (RuntimeException e) {
            LOG.warn("[MCA-QUESTS] {} #{} end: {}", q.script, q.id, e.toString());
        }
        if (q.story()) {
            QuestBook.storyEnded(q.village, day);
            Villages.tell(q.village, day, q.playerName + " gave up on " + lower(q.title) + (how == State.FAILED ? " (" + why + ")" : ""));
        }
        if (q.player != null) QuestBook.trim(q.player);
        QuestBook.changed();
    }

    // ------------------------------------------------------------------ the cast

    /** Rebuilt every second: the folk with a part in an open quest. */
    static void recast() {
        Map<UUID, Integer> m = new HashMap<>();
        for (Quest q : QuestBook.all()) {
            if (!q.open()) continue;
            for (Map.Entry<String, String> e : q.flags.entrySet()) {
                if (!e.getKey().startsWith("cast.")) continue;
                try {
                    m.put(UUID.fromString(e.getValue()), q.id);
                } catch (IllegalArgumentException ignored) {
                    // not an id
                }
            }
        }
        cast = m;
    }

    /**
     * [VillageFolkEntity.aiStep] A folk with a part in a quest, held to it: a lost child where it is lost, a miner
     * that will not go down, a smuggler at the camp by night. True while it is.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Long staged = STAGED.get(f.getUUID());
        if (staged != null) {
            if (level.getGameTime() < staged && QuestBook.giving(f.getUUID())) {
                // Stood where the stage found it, facing whoever comes to ask, for the pictures.
                f.getNavigation().stop();
                Player near = level.getNearestPlayer(f, 8.0);
                if (near != null) f.getLookControl().setLookAt(near, 30.0F, 30.0F);
                return true;
            }
            STAGED.remove(f.getUUID());
        }
        Integer id = cast.get(f.getUUID());
        if (id == null) return false;
        Quest q = QuestBook.get(id);
        if (q == null || !q.open()) return false;
        try {
            return script(q).hold(f, level, q);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ marks over heads

    /** "!" over a folk with work to give, "?" over one a step of yours waits on. */
    static void marks(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        StringBuilder sb = new StringBuilder();
        Map<UUID, Character> mine = marksFor(p);
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, p.getBoundingBox().inflate(MARKS), VillageFolkEntity::isAlive)) {
            Character c = mine.get(f.getUUID());
            if (c == null && QuestBook.offerOf(f.getUUID()) != null) c = '!';
            if (c == null) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(f.getId()).append(':').append(c);
        }
        String now = sb.toString();
        if (now.equals(MARKS_SENT.get(p.getUUID()))) return;
        MARKS_SENT.put(p.getUUID(), now);
        send(p, new com.jrpetty.mcassistant.net.QuestMarksPayload(now));
    }

    /**
     * Only to a client that has this mod: a vanilla client, or a test's stand-in player, cannot hear it, and sending
     * it anyway throws (and takes down whatever else was being done for that player, a conversation or a test).
     */
    static void send(ServerPlayer p, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        try {
            if (p.connection != null && p.connection.hasChannel(payload.type())) PacketDistributor.sendToPlayer(p, payload);
        } catch (RuntimeException ignored) {
            // A connection going away mid-send: nothing to show.
        }
    }

    /** The folk this player's steps wait on: "?". */
    public static Map<UUID, Character> marksFor(Player p) {
        Map<UUID, Character> out = new HashMap<>();
        for (Quest q : QuestBook.active(p.getUUID())) {
            Step s = q.current();
            if (s != null && s.who != null && (s.type == StepType.TALK || s.type == StepType.GIVE || s.type == StepType.DELIVER
                    || s.type == StepType.CHOOSE)) out.put(s.who, '?');
        }
        return out;
    }

    /** The mark a player sees over this folk: '!', '?' or none (the tests, the stage). */
    public static char markFor(VillageFolkEntity f, Player p) {
        Character c = marksFor(p).get(f.getUUID());
        if (c != null) return c;
        return QuestBook.offerOf(f.getUUID()) != null ? '!' : ' ';
    }

    // ------------------------------------------------------------------ buttons on the talk screen

    /**
     * What a player can say to this folk about its quests just now, as buttons for the talk screen: "Accept" and
     * "Not now" for an offer, a button a choice for a choice, "Hand it over" for a step it waits on. Sent before the
     * folk's answer, so the screen built from the answer has them.
     */
    public static void sendChoices(VillageFolkEntity f, Player p) {
        if (!(p instanceof ServerPlayer sp)) return;
        send(sp, new com.jrpetty.mcassistant.net.QuestOfferPayload(f.getId(), choices(f, p)));
    }

    /** The buttons, one a line: "label|TOPIC|text|tip". */
    public static String choices(VillageFolkEntity f, Player p) {
        List<String> out = new ArrayList<>();
        Object[] found = stepWith(f, p);
        if (found != null) {
            Quest q = (Quest) found[0];
            Step s = (Step) found[1];
            // Said as typed words (the conversation shows what you said); the folk understands them (QuestTalk.heard).
            if (s.type == StepType.CHOOSE) {
                for (String[] o : s.options) out.add(o[1] + "|SAY|" + o[1] + "|" + q.title + ": " + o[1]);
            } else if (s.type == StepType.GIVE || s.type == StepType.DELIVER) {
                out.add("Hand it over|SAY|Here you are — I've brought it.|" + q.title + ": " + s.text);
            } else {
                out.add("About " + shortTitle(q.title) + "|SAY|I'm here about " + lower(q.title) + ".|" + q.title + ": " + s.text);
            }
        }
        Quest offer = QuestBook.offerOf(f.getUUID());
        if (offer != null && QuestTalk.asked(f, p)) {
            out.add("I'll do it|SAY|I'll do it.|Take on: " + offer.title + " (" + offer.rewardWords() + ")");
            out.add("Not now|SAY|Not just now.|Leave it for now: somebody else may take it");
        }
        return String.join("\n", out);
    }

    private static String shortTitle(String t) {
        return t.length() <= 16 ? t : t.substring(0, 15) + "…";
    }

    // ------------------------------------------------------------------ the journal

    private static final char US = '\u001f';

    /** A player's journal, as the screen reads it (client/QuestJournalScreen). */
    public static String journal(Player p, long today) {
        StringBuilder sb = new StringBuilder();
        List<String> titles = new ArrayList<>();
        for (Villages.Village v : Villages.every()) {
            for (String t : QuestBook.titles(p.getUUID(), v.id())) titles.add(t + " of " + Villages.name(v.id()));
            if (QuestBook.honoured(p.getUUID(), v.id(), "medal")) titles.add("Medal of " + Villages.name(v.id()));
            if (QuestBook.honoured(p.getUUID(), v.id(), "key")) titles.add("Key to " + Villages.name(v.id()));
        }
        line(sb, "H", p.getName().getString(), String.join(" · ", titles), Long.toString(today));
        for (Quest q : QuestBook.of(p.getUUID())) {
            line(sb, "Q", Integer.toString(q.id), q.state.name(), q.kind.label, q.title, q.giverName, Villages.name(q.village),
                q.rewardWords(), Long.toString(q.deadline), q.outcome, q.story() ? "1" : "0", clip(q.offer, 600));
            boolean seenCurrent = false;
            for (Step s : q.steps) {
                int status = s.done ? 0 : seenCurrent ? 2 : 1;
                if (!s.done) seenCurrent = true;
                String note = s.note;
                if (s.type == StepType.KILL || s.type == StepType.GIVE || s.type == StepType.LIGHT) {
                    if (!s.done && s.progress > 0) note = (note.isEmpty() ? "" : note + " ") + "So far " + s.progress + " of " + s.count + ".";
                }
                if (status == 2 && q.story()) continue;           // a story's next chapters are not known till they come
                line(sb, "S", Integer.toString(status), s.chapter, s.text,
                    s.at == null ? "" : Integer.toString(s.at.getX()), s.at == null ? "" : Integer.toString(s.at.getY()),
                    s.at == null ? "" : Integer.toString(s.at.getZ()), s.dim, Integer.toString(s.radius), note, s.whoName);
            }
            if (!q.ending.isEmpty()) line(sb, "E", clip(q.ending, 1500));
            if (sb.length() > 30000) break;
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String... parts) {
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(US);
            sb.append(parts[i] == null ? "" : parts[i].replace('\n', ' ').replace(US, ' '));
        }
        sb.append('\n');
    }

    private static String clip(String s, int most) {
        return s == null ? "" : s.length() <= most ? s : s.substring(0, most - 1) + "…";
    }

    /** The journal opened: its pages sent to the player. */
    public static void openJournal(ServerPlayer p) {
        String text = journal(p, day(p.serverLevel()));
        send(p, new com.jrpetty.mcassistant.net.QuestJournalPayload(text));
    }

    /** The journal's buttons: give a quest up (1), or read the pages again (2). */
    public static void action(ServerPlayer p, int what, int id) {
        if (what == 1) {
            Quest q = QuestBook.get(id);
            if (q != null) p.sendSystemMessage(Component.literal(abandon(p.serverLevel(), q, p)).withStyle(ChatFormatting.YELLOW));
        }
        openJournal(p);
    }

    // ------------------------------------------------------------------ /village quests

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("quests")
            .executes(ctx -> list(ctx))
            .then(Commands.literal("journal").executes(ctx -> {
                openJournal(ctx.getSource().getPlayerOrException());
                return 1;
            }).then(Commands.literal("close").executes(ctx -> {
                // Shut the journal on the asker's screen (the client smoke, between its stages).
                send(ctx.getSource().getPlayerOrException(), new com.jrpetty.mcassistant.net.QuestJournalPayload(""));
                return 1;
            })))
            .then(Commands.literal("abandon")
                .then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        Quest q = QuestBook.get(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "id"));
                        ctx.getSource().sendSuccess(() -> Component.literal(q == null ? "No such quest." : abandon(p.serverLevel(), q, p)), false);
                        return 1;
                    })))
            .then(Commands.literal("offers").executes(QuestRun::offers))
            .then(Commands.literal("look").requires(src -> src.hasPermission(2)).executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                Villages.Village v = Villages.nearest(p.serverLevel(), p.blockPosition(), Villages.VILLAGE_RANGE * 2);
                if (v == null) return 0;
                QuestMaker.eager(true);
                try {
                    lookNow(p.serverLevel(), v);
                } finally {
                    QuestMaker.eager(false);
                }
                return offers(ctx);
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> stage(ctx, "")))
            .then(Commands.literal("story").requires(src -> src.hasPermission(2))
                .then(Commands.argument("which", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(ctx -> stage(ctx, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "which")))));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        for (String l : describe(p)) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
        return 1;
    }

    /** The player's quests in words, a line a quest and a line a step: /village quests. */
    public static List<String> describe(Player p) {
        List<String> out = new ArrayList<>();
        List<Quest> all = QuestBook.of(p.getUUID());
        long today = p.level().getDayTime() / 24000L;
        if (all.isEmpty()) {
            out.add("You have no quests. Look for a gold \"!\" over a folk's head, and ask it \"Any work for me?\".");
            return out;
        }
        out.add("QUESTS of " + p.getName().getString() + ":");
        for (Quest q : all) {
            String when = q.state == State.ACTIVE ? "due day " + q.deadline + (q.deadline - today <= 0 ? " (today!)" : " (" + (q.deadline - today) + " days)")
                : q.state.name().toLowerCase(Locale.ROOT) + " day " + q.finished;
            out.add("#" + q.id + " " + q.title + " [" + q.kind.label + "] from " + q.giverName + " of " + Villages.name(q.village) + " — "
                + when + "; reward: " + q.rewardWords() + (q.outcome.isEmpty() ? "" : "; " + q.outcome));
            if (q.state != State.ACTIVE) continue;
            for (Step s : q.steps) {
                if (!s.done && s != q.current()) break;
                String where = "";
                if (s.at != null) {
                    int dx = s.at.getX() - p.getBlockX(), dz = s.at.getZ() - p.getBlockZ();
                    where = " @ " + s.at.getX() + " " + s.at.getY() + " " + s.at.getZ() + " (" + (int) Math.sqrt((double) dx * dx + (double) dz * dz)
                        + " blocks " + way(dx, dz) + ")";
                }
                out.add("   " + (s.done ? "[x] " : "[ ] ") + (s.chapter.isEmpty() ? "" : s.chapter + ": ") + s.text + where
                    + (s.note.isEmpty() ? "" : " — " + s.note));
            }
        }
        return out;
    }

    /** "north-east", from a step along x and z. */
    public static String way(int dx, int dz) {
        if (dx * dx + dz * dz < 9) return "here";
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        String[] w = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
        return w[(int) Math.floorMod(Math.round(a / 45.0), 8L)];
    }

    private static int offers(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Villages.Village v = Villages.nearest(src.getLevel(), BlockPos.containing(src.getPosition()), Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            src.sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        List<Quest> open = QuestBook.open(v.id());
        src.sendSuccess(() -> Component.literal("QUESTS in " + Villages.name(v.id()) + ": " + open.size() + " open"), false);
        for (Quest q : open) {
            VillageFolkEntity g = Civics.find(src.getLevel(), q.giver);
            String at = g == null ? "" : " at " + g.blockPosition().getX() + " " + g.blockPosition().getY() + " " + g.blockPosition().getZ();
            src.sendSuccess(() -> Component.literal("  OFFER #" + q.id + " " + q.state + " \"" + q.title + "\" (" + q.script + ") by " + q.giverName + at
                + (q.player == null ? "" : ", taken by " + q.playerName) + "; " + q.rewardWords()), false);
        }
        return open.size();
    }

    /**
     * For the pictures and for trying it out (ops): a quest giver made of the nearest folk, with a real offer of what
     * the town can offer now, or the named story begun if the town's state allows it. "GIVER <name> x y z" for the
     * stage to find it by.
     */
    private static int stage(CommandContext<CommandSourceStack> ctx, String story) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        ServerLevel level = p.serverLevel();
        Villages.Village v = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        ensure();
        Quest q = story.isEmpty() ? QuestMaker.stage(level, v, p) : QuestStories.begin(level, v, story, p, true);
        if (q == null) {
            ctx.getSource().sendFailure(Component.literal("Nothing the town can offer just now"
                + (story.isEmpty() ? "" : " for the story \"" + story + "\": " + QuestStories.why(v.id()))));
            return 0;
        }
        VillageFolkEntity g = Civics.find(level, q.giver);
        BlockPos at = g == null ? v.centre() : g.blockPosition();
        if (g != null) STAGED.put(g.getUUID(), level.getGameTime() + 1200L);   // a minute stood still, for the pictures
        String line = "GIVER " + q.giverName.replace(' ', '_') + " " + at.getX() + " " + at.getY() + " " + at.getZ() + " \"" + q.title + "\" (" + q.script + ")";
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    // ------------------------------------------------------------------ the quest board

    /** A town quest's posting number on the board: its own, well clear of the board's. */
    static final int PINNED = 100000;

    /**
     * [Quests.look] The town's own quests (its elder's, the war's, the caves', its story), pinned on the quest board
     * beside the postings while they are offered: two at most, room allowing. Whether the board changed.
     */
    public static boolean pin(ServerLevel level, Villages.Village v, List<Quests.Posting> board, int room) {
        boolean changed = board.removeIf(p -> "quest".equals(p.kind) && !pinnable(p));
        int pins = 0;
        Set<String> up = new HashSet<>();
        for (Quests.Posting p : board) {
            if (!"quest".equals(p.kind)) continue;
            pins++;
            up.add(p.item);
        }
        long day = day(level);
        for (Quest q : QuestBook.offers(v.id())) {
            if (pins >= 2 || board.size() >= room) break;
            if (q.kind == QuestBook.Kind.FAVOUR || up.contains(Integer.toString(q.id))) continue;
            board.add(new Quests.Posting(PINNED + q.id, "quest", Integer.toString(q.id), 1, q.title, "", null, q.giverName, q.coins, day));
            pins++;
            changed = true;
        }
        return changed;
    }

    private static boolean pinnable(Quests.Posting p) {
        Quest q = QuestBook.get(QuestRewards.num(p.item));
        return q != null && q.state == State.OFFERED;
    }

    /** [Quests.lines] A pinned quest, as its sign reads: "TOWN QUEST / Scout Ashford / see Bram / 20 coins". */
    public static String[] boardLines(Quests.Posting p) {
        Quest q = QuestBook.get(QuestRewards.num(p.item));
        String head = q == null ? "QUEST" : q.story() ? "A MYSTERY" : q.kind == QuestBook.Kind.WAR ? "WAR QUEST" : q.kind == QuestBook.Kind.CAVES ? "BELOW GROUND"
            : "TOWN QUEST";
        return new String[]{ head, cut(p.purpose), cut("see " + p.placeName), cut(p.reward > 0 ? p.reward + " coins" : "for thanks") };
    }

    private static String cut(String s) {
        return s.length() <= 15 ? s : s.substring(0, 15);
    }

    /** [Quests.use] A pinned quest right-clicked: whose it is, and where to find them. */
    public static String boardUse(ServerLevel level, Villages.Village v, Player p, Quests.Posting post) {
        Quest q = QuestBook.get(QuestRewards.num(post.item));
        if (q == null || q.state != State.OFFERED) return "That one's been taken.";
        VillageFolkEntity g = Civics.find(level, q.giver);
        String where = g == null ? "" : " (" + QuestMaker.where(p.blockPosition(), g.blockPosition()) + " of here)";
        return q.giverName + " has this one: " + q.title + ". Find " + q.giverName + where + " and ask \"Any work for me?\" — "
            + q.rewardWords() + ".";
    }

    /** [Quests.use] A posting taken off the board: into the taker's journal, done at the board as ever. */
    public static void fromBoard(ServerLevel level, Villages.Village v, Player p, Quests.Posting post) {
        ensure();
        Quest q = QuestBook.create();
        q.script = "board";
        q.kind = QuestBook.Kind.BOARD;
        q.state = State.ACTIVE;
        q.village = v.id();
        UUID elder = Villages.elder(v.id());
        q.giver = elder != null ? elder : v.id();
        q.giverName = "the quest board";
        q.player = p.getUUID();
        q.playerName = p.getName().getString();
        q.title = QuestTalk.capFirst(post.words());
        q.posted = day(level);
        q.taken = q.posted;
        q.days = Quests.LASTS_DAYS;
        q.deadline = q.taken + q.days;
        q.warmth = 0;
        q.flags.put("reward", post.reward + " coins from the treasury, at the board");
        q.flags.put("posting", Integer.toString(post.id));
        Step s = new Step(StepType.WAIT, "board", "bring".equals(post.kind) ? "Bring " + post.words() + " to the quest board, a few at a time"
            : QuestTalk.capFirst(post.words()) + ", then claim it at the board").item("", post.count);
        Ledger.Building hall = Quests.hall(v.id());
        if ("clear".equals(post.kind) && post.place != null) s.at(post.place, 32);
        else if (hall != null) s.at(hall.anchor(), 6);
        q.steps.add(s);
        QuestBook.add(q);
        QuestRewards.firstJournalFromBoard(level, v, p);
    }

    /** [Quests.claim] A posting claimed: done in the journal too (the board has paid it). */
    public static void boardDone(ServerLevel level, Player p, Quests.Posting post) {
        for (Quest q : QuestBook.active(p.getUUID())) {
            if (!"board".equals(q.script) || QuestRewards.num(q.flag("posting")) != post.id) continue;
            for (Step s : q.steps) {
                s.done = true;
                s.progress = s.count;
                s.note = "Claimed at the board.";
            }
            q.outcome = "claimed at the board";
            finish(level, q, p);
        }
    }

    /** [Gazette] The town's quests in its paper: help wanted, the story everybody is talking about, and the week's deeds. */
    @Nullable
    public static String gazette(UUID village, long day) {
        List<Quest> offers = QuestBook.offers(village);
        List<Quest> deeds = QuestBook.deeds(village, day - 7);
        deeds.removeIf(q -> q.kind == QuestBook.Kind.BOARD);
        Quest story = QuestBook.story(village);
        if (offers.isEmpty() && deeds.isEmpty() && story == null) return null;
        StringBuilder sb = new StringBuilder("§lQuests and deeds§r");
        int n = 0;
        for (Quest q : offers) {
            if (q.story() || n >= 3) continue;
            sb.append("\nWanted: ").append(q.title.toLowerCase(Locale.ROOT)).append(" — see ").append(q.giverName)
                .append(q.coins > 0 ? ", " + q.coins + " coins" : "").append('.');
            n++;
        }
        if (story != null) {
            sb.append("\nThe talk of the town: ").append(story.title).append(story.player == null ? " — will anybody help?" : " — " + story.playerName + " is looking into it.");
        }
        for (int i = 0; i < Math.min(3, deeds.size()); i++) {
            Quest q = deeds.get(i);
            sb.append("\n").append(q.playerName).append(": ").append(q.title).append(q.outcome.isEmpty() || q.outcome.equals("done") ? "" : " (" + q.outcome + ")").append('.');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ small things

    static void tell(Player p, Component c) {
        p.displayClientMessage(c, false);
    }

    static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + " " + b;
    }

    static String lower(String s) {
        return s == null || s.isEmpty() ? "" : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Every folk of every town by its id, loaded. */
    @Nullable
    static VillageFolkEntity folk(ServerLevel level, @Nullable UUID id) {
        return Civics.find(level, id);
    }

    /** The ids a quest's flags hold, as a set (the cast). */
    static Set<UUID> castOf(Quest q) {
        Set<UUID> out = new HashSet<>();
        for (Map.Entry<String, String> e : q.flags.entrySet()) {
            if (!e.getKey().startsWith("cast.")) continue;
            try {
                out.add(UUID.fromString(e.getValue()));
            } catch (IllegalArgumentException ignored) {
                // not an id
            }
        }
        return out;
    }
}
