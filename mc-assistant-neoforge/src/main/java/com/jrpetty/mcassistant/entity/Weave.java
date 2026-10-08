package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.QuestBook.Kind;
import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.entity.QuestBook.State;
import com.jrpetty.mcassistant.entity.QuestBook.Step;
import com.jrpetty.mcassistant.entity.QuestBook.StepType;
import com.jrpetty.mcassistant.item.Garment;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.LibraryRecords;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.Quill;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [weave] How the town's systems feed each other: the threads between the watch and the quests, the pets and the quests,
 * fire and flood and the quests and the newcomers, the library and everything new, the auction and the stores, and
 * fashion and the new offices. Each system works on its own; this is where one hears of another. The shared classes
 * call in here with a line each, tagged "[weave]".
 *
 * <ul>
 * <li><b>Help the watch.</b> The guard or constable on a case (Crime.helpWanted) offers a quest whose steps are the real
 *     ways a player can help: follow the footprints, find what was dropped, ask those who were about, tell the
 *     investigator. It is paid out of the treasury when the council convicts the right one (Crime.Listener); a wrong
 *     accusation the player's own word brought about costs the player its standing in the town.</li>
 * <li><b>Lost pets.</b> The quest board's "lost pet" asks after the pets that are really lost (Pets.lostIn); now and then
 *     (a fortnight apart at the least) a town's dog goes off after a rabbit for it; the pet waits out there while the
 *     quest stands, follows its finder home (Pets.onFound completes the step), and makes its own way home when the
 *     quest is given up, fails or nobody takes it (Pets.release).</li>
 * <li><b>Fire and flood.</b> While a fire burns or the river is up, the town asks for help at once: water buckets for the
 *     chain (poured on the fire, the empty bucket handed back), a flooded household's things carried up out of its
 *     chest to the high ground, and what the rebuilding waits for in the stores. A household a fire or a flood leaves with
 *     nowhere to sleep (no neighbour's bed, no inn, no hall) goes as refugees to the nearest town at peace
 *     (Newcomers.displaced). A drought's short rations are hunger, and hunger tempts (Mischief).</li>
 * <li><b>The auction and the shop.</b> Nothing stolen and no forged coin is ever auctioned or sold; the tailor's finest
 *     (a master's coat, a gold brooch) and the cave team's finds the lodge's six frames have no room for go under the
 *     hammer; a garment won is worn at once, and counts as the height of fashion for the season.</li>
 * <li><b>Fashion and the new offices.</b> The constable, the librarian, the auctioneer, a player leader's steward and a
 *     cave dweller home from the caves each have a garment of their office they want before anything else (the season's
 *     own thing apart); the steward and the auctioneer set fashions; newcomers come in their old town's colours, and
 *     come round to the new town's fashion the quicker for wanting to fit in.</li>
 * <li><b>And more.</b> A player leader's "more guards" counts guards in their kit; the gazette's biggest story leads its
 *     front page; a dog lies at the feet of the child it follows to the library; the trade books of the cave dwellers,
 *     the watch, the fishers, the tailor and the librarian write of their new work.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Weave {

    private Weave() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The quests' scripts. */
    static final String WATCH = "town.watch", FIRE = "town.fire", FLOOD = "town.flood", REBUILD = "town.rebuild";
    /** Days between one lost pet sent off for the quest board and the next, in a town. */
    static final int PET_GAP = 14;
    /** The most buckets one player brings to one fire. */
    static final int MOST_BUCKETS = 3;
    /** A household's things, the most stacks carried up out of a flooded chest. */
    static final int THINGS_MOST = 6;
    /** Marks: a garment won at the auction (its season), and a newcomer's old town and the day it left. */
    static final String WON = "mca_auction_won", FROM = "mca_weave_from";

    // ------------------------------------------------------------------ live, and the tests

    /** Running the game tests: none of the town-wide responses unless a test asks for them (as Disasters does). */
    static final boolean GAME_TESTS = System.getProperty("neoforge.enabledGameTestNamespaces") != null;
    private static volatile boolean liveForTests;

    static boolean live() {
        return !GAME_TESTS || liveForTests;
    }

    /** Tests: the weave's own round (the urgent quests, the lost pet, the refugees) on, or off as in the other tests. */
    public static void liveForTests(boolean on) {
        liveForTests = on;
    }

    private static volatile boolean inited;
    private static final Map<UUID, Long> DAILY = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<UUID, String>> HOMELESS = new ConcurrentHashMap<>();
    private static final Set<UUID> SENT = ConcurrentHashMap.newKeySet();

    public static void resetForTests() {
        DAILY.clear();
        HOMELESS.clear();
        SENT.clear();
        liveForTests = false;
    }

    /** The quests' scripts and the listeners registered (QuestMaker.register): once. */
    static void init() {
        if (inited) return;
        QuestRun.script(new Quest());           // QuestRun.ensure: QuestMaker.register, and so registerQuests
    }

    // ------------------------------------------------------------------ the round

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        if (tick % 40 != 23) return;
        com.jrpetty.mcassistant.Guard.run("the weave", () -> {
            init();
            for (Villages.Village v : List.copyOf(Villages.every())) {
                ServerLevel level = server.getLevel(v.dim());
                if (level == null || !level.isLoaded(v.centre())) continue;
                tick(level, v);
            }
        });
    }

    /** Every two seconds a town: its homeless sent on, its urgent quests offered; once a day its pets looked at. */
    static void tick(ServerLevel level, Villages.Village v) {
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        refugees(level, v);
        if (!live()) return;
        urgent(level, v, day);
        if (t >= 3000L && t < 9000L && DAILY.getOrDefault(v.id(), -1L) != day) {
            DAILY.put(v.id(), day);
            petsDaily(level, v, day);
        }
    }

    /** [QuestMaker.register] The quests the weave adds to the town's: their makers, scripts and listeners. */
    static void registerQuests(Map<String, QuestMaker.Maker> makers) {
        makers.put("watch", Weave::watch);
        makers.put("fire", Weave::fire);
        makers.put("flood", Weave::flood);
        makers.put("rebuild", Weave::rebuild);
        QuestRun.script(WATCH, WATCH_SCRIPT);
        QuestRun.script(FIRE, FIRE_SCRIPT);
        QuestRun.script(FLOOD, FLOOD_SCRIPT);
        QuestRun.script(REBUILD, REBUILD_SCRIPT);
        QuestMaker.lostPets(Weave::lostPets);
        if (!inited) {
            inited = true;
            Crime.listen(LISTENER);
            Pets.onFound(Weave::found);
        }
    }

    // ================================================================== quests: small things

    static String lowerFirst(String s) {
        return s == null || s.isEmpty() ? "" : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Over, quietly: nobody let down (a case closed without the player, a fire out before the water came). */
    static void endQuietly(ServerLevel level, Quest q, String why) {
        if (!q.open()) return;
        boolean offered = q.state == State.OFFERED;
        q.state = State.FAILED;
        q.finished = QuestRun.day(level);
        q.outcome = why;
        try {
            QuestRun.script(q).ended(level, q);
        } catch (RuntimeException e) {
            LOG.warn("[MCA-WEAVE] {} #{} end: {}", q.script, q.id, e.toString());
        }
        if (offered) {
            QuestBook.remove(q.id);
        } else if (q.player != null) {
            QuestBook.trim(q.player);
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(q.player);
            if (p != null) QuestRun.tell(p, Component.literal("Quest over: " + q.title + " — " + why + ".").withStyle(ChatFormatting.GRAY));
        }
        QuestBook.changed();
        LOG.info("[MCA-WEAVE] \"{}\" (#{}) over: {}", q.title, q.id, why);
    }

    /** A step done by something other than its own check: the current one as if reached (the journal told), else marked. */
    static void complete(ServerLevel level, Quest q, Step s, @Nullable Player p, String note) {
        if (s.done || q.state != State.ACTIVE) return;
        if (!note.isEmpty()) s.note = note;
        if (s == q.current() && p != null) {
            QuestRun.reached(level, q, s, null, p);
        } else {
            s.done = true;
            QuestBook.changed();
        }
    }

    @Nullable
    static ServerPlayer online(ServerLevel level, @Nullable UUID player) {
        return player == null ? null : level.getServer().getPlayerList().getPlayer(player);
    }

    @Nullable
    static ServerLevel levelOf(UUID village) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        Villages.Village v = Villages.get(village);
        return server == null || v == null ? null : server.getLevel(v.dim());
    }

    static int num(String s) {
        return QuestRewards.num(s);
    }

    // ================================================================== 1. help the watch

    /**
     * The guard (or the constable) on a case asks for help with it: the steps are the ways the case can really be
     * helped as it stands (Crime.helpWanted): the footprints while they are fresh, what was dropped while it lies there,
     * those who were about and have not been asked, and then a word with the investigator; and the council's verdict.
     */
    @Nullable
    static Quest watch(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        for (Crime.Wanted w : Crime.helpWanted(id)) {
            Crime.Case c = Crime.get(w.caseId());
            if (c == null || QuestMaker.already(id, WATCH, "case", Integer.toString(c.id))) continue;
            VillageFolkEntity giver = c.investigator == null ? null : Civics.find(level, c.investigator);
            if (giver == null || giver.stationTask() != StationTask.GUARD) giver = Inquiry.constable(id);
            if (!QuestMaker.free(giver) || giver.stationTask() != StationTask.GUARD) continue;
            String g = giver.displayNameCap(), title = lowerFirst(c.title());
            boolean constable = Inquiry.isConstable(giver);
            List<Step> steps = new ArrayList<>();
            if (!c.trailRead && !c.trailWashed && !c.trail.isEmpty()) {
                steps.add(new Step(StepType.WAIT, "trail", "Follow the muddy footprints from " + c.place + " to wherever they lead, before the rain takes them")
                    .at(c.where, 8));
            }
            if (c.droppedEntity != null && !c.droppedFound) {
                steps.add(new Step(StepType.WAIT, "dropped", "Find what was dropped about " + c.place + ", and hand it to " + g + " (or any of the watch)")
                    .at(c.where, 10));
            }
            List<String> unasked = unasked(c);
            if (!unasked.isEmpty()) {
                int n = Math.min(2, unasked.size());
                steps.add(new Step(StepType.WAIT, "ask", "Ask " + (n == unasked.size() ? "" : n + " of ") + Civics.names(unasked)
                    + " what they saw (\"Seen anything amiss?\")").item("", n));
            }
            steps.add(new Step(StepType.TALK, "tell", "Tell " + g + " what you found").who(giver));
            steps.add(new Step(StepType.WAIT, "verdict", "Wait for the council's verdict"));
            int ways = steps.size() - 2;
            Quest q = QuestMaker.newQuest(WATCH, Kind.TOWN, v, giver, "Help the watch: the " + title,
                QuestTalk.voice(giver,
                    (constable ? "As the town's constable, " : "") + "I'm on the " + title + " — " + c.what() + ". I could use another pair of eyes. "
                        + (ways > 0 ? "There's footwork in it, and folk who'll talk to you sooner than to me. " : "") + "Help me and the treasury pays, when the council has the right one.",
                    "The " + title + ". I've not the hands to chase every lead. Help, and the treasury pays — when the right one's convicted, not before.",
                    "There's been " + c.kind.word + " — " + c.what() + "… I could use help with it. The treasury would pay, if we catch the right one.",
                    "A mystery! The " + title + " — " + c.what() + "! Help me crack it and the treasury pays when the council convicts!"));
            q.steps.addAll(steps);
            q.coins = QuestRewards.affordTreasury(id, 8 + 4 * ways);
            q.payer = "treasury";
            q.warmth = 8;
            q.days = 4;
            q.flags.put("case", Integer.toString(c.id));
            q.flags.put("memory", "helped me with the " + title);
            q.flags.put("chronicle", "{who} helped the watch with the " + title);
            return q;
        }
        return null;
    }

    /** The names of those who were about and have not yet said what they saw. */
    static List<String> unasked(Crime.Case c) {
        List<String> out = new ArrayList<>();
        for (Crime.Near n : c.near) {
            if (c.statementFrom(n.id()) != null || n.id().equals(c.investigator) || n.id().equals(c.culprit)) continue;
            out.add(n.name());
        }
        return out;
    }

    static final QuestRun.Script WATCH_SCRIPT = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            Crime.Case c = Crime.get(num(q.flag("case")));
            if (c == null || !c.stage.open()) {
                q.flags.put("refused", "the case is closed");
                return null;
            }
            c.note(QuestRun.day(level), p.getName().getString() + " offered to help the watch with it.");
            Crime.changed();
            return QuestTalk.taken(giver, q);
        }

        @Override
        public void tick(ServerLevel level, Quest q, Player p) {
            syncWatch(level, q, p);
        }

        @Override
        public void townTick(ServerLevel level, Villages.Village v, Quest q) {
            if (q.state != State.ACTIVE) return;
            Crime.Case c = Crime.get(num(q.flag("case")));
            if (c == null) endQuietly(level, q, "the case is gone from the books");
            else if (!q.flag("verdict").isEmpty() && !"right".equals(q.flag("verdict"))) endQuietly(level, q, verdictWords(q, c));
            else if (q.current() != null && q.current().key.equals("verdict") && c.stage.open() && q.deadline <= QuestRun.day(level)) {
                q.deadline = QuestRun.day(level) + 1;                      // waiting on the council, not on the player
                QuestBook.changed();
            }
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            Crime.Case c = Crime.get(num(q.flag("case")));
            if (c == null) return "";
            long day = QuestRun.day(level);
            String name = p.getName().getString();
            switch (s.key) {
                case "trail" -> {
                    if (p instanceof ServerPlayer sp) Inquiry.followed(level, c, sp);
                    for (Crime.Clue k : c.clues) if (k.kind.equals("footprints")) s.note = k.text;
                }
                case "tell" -> {
                    c.helped(p);
                    c.note(day, name + " went over what they had found with " + c.investigatorName + ".");
                    Crime.changed();
                    s.note = "Told " + (f == null ? "the watch" : f.displayNameCap()) + ".";
                    return f == null ? "" : QuestTalk.voice(f, "Good work. " + Inquiry.suspectsWords(c) + ". Leave it with me now; the council will hear it.",
                        "Hm. " + Inquiry.suspectsWords(c) + ". That'll do. I'll take it from here.",
                        "Oh — that helps. " + Inquiry.suspectsWords(c) + ". Thank you.",
                        "Brilliant! " + Inquiry.suspectsWords(c) + ". We'll have them yet!");
                }
                case "verdict" -> {
                    s.note = Mischief.capital(c.verdict) + ".";
                    q.flags.put("chronicle", name + " helped the watch with the " + lowerFirst(c.title()) + ", and " + c.accusedName + " was convicted");
                    q.flags.put("gossip", "{who} helped the watch catch " + c.accusedName + ". Sharp, that one.");
                }
                default -> { }
            }
            return "";
        }

        @Override
        public String ending(ServerLevel level, Quest q, Player p) {
            Crime.Case c = Crime.get(num(q.flag("case")));
            return c == null ? "" : "The council heard the " + lowerFirst(c.title()) + " and found " + c.accusedName + " guilty: " + c.sentence + ". "
                + c.investigatorName + " put your part of it in the case file.";
        }

        @Override
        public boolean stands(ServerLevel level, Quest q) {
            Crime.Case c = Crime.get(num(q.flag("case")));
            return c != null && c.stage.open() && c.stage != Crime.Stage.ACCUSED && c.stage != Crime.Stage.TRIAL;
        }
    };

    static String verdictWords(Quest q, Crime.Case c) {
        if ("1".equals(q.flag("misnamed"))) return "your word named the wrong one, and the council cleared them";
        return switch (q.flag("verdict")) {
            case "wrong" -> c.stage == Crime.Stage.CONVICTED ? "the council convicted the wrong one" : "the case closed with nobody convicted";
            default -> "the case was closed";
        };
    }

    /** Once a second while the player is about: the steps the watch has seen to itself crossed off, the verdict paid. */
    static void syncWatch(ServerLevel level, Quest q, Player p) {
        if (q.state != State.ACTIVE) return;
        Crime.Case c = Crime.get(num(q.flag("case")));
        if (c == null) {
            endQuietly(level, q, "the case is gone from the books");
            return;
        }
        String verdict = q.flag("verdict");
        if (!verdict.isEmpty()) {
            settleWatch(level, q, c, p);
            return;
        }
        Step now = q.current();
        if (now != null && now.key.equals("verdict") && c.stage.open() && q.deadline <= QuestRun.day(level)) {
            q.deadline = QuestRun.day(level) + 1;                          // its part done: the council's pace is not the player's fault
            QuestBook.changed();
        }
        for (Step s : q.steps) {
            if (s.done || q.state != State.ACTIVE) continue;
            switch (s.key) {
                case "trail" -> {
                    if (c.trailRead) complete(level, q, s, p, "The watch followed them first.");
                    else if (c.trailWashed) complete(level, q, s, p, "The rain had washed them out.");
                    else if (s == q.current() && !c.trail.isEmpty()) {
                        BlockPos end = c.trail.get(c.trail.size() - 1);
                        if (QuestRun.near(p, end, 3) && p instanceof ServerPlayer sp) {
                            Inquiry.followed(level, c, sp);            // the listener crosses the step off
                        }
                    }
                }
                case "dropped" -> {
                    if (c.droppedFound) complete(level, q, s, p, "Found: " + c.dropped + ".");
                }
                case "ask" -> {
                    if (unasked(c).isEmpty()) complete(level, q, s, p, "Everybody who was about has said what they saw.");
                }
                case "tell" -> {
                    if (!c.stage.investigating() && c.stage != Crime.Stage.REPORTED) complete(level, q, s, p, "The watch had named somebody by then.");
                }
                default -> { }
            }
        }
    }

    /** The case closed: paid if the right one was convicted and the player helped; else over, quietly. */
    static void settleWatch(ServerLevel level, Quest q, Crime.Case c, Player p) {
        if (q.state != State.ACTIVE) return;
        if (!"right".equals(q.flag("verdict"))) {
            endQuietly(level, q, verdictWords(q, c));
            return;
        }
        if (!c.helpers.contains(p.getUUID())) {
            endQuietly(level, q, "the watch saw to it without you");
            return;
        }
        Step verdict = q.step("verdict");
        for (Step s : q.steps) {
            if (s != verdict && !s.done) {
                s.done = true;
                if (s.note.isEmpty()) s.note = "The case closed first.";
            }
        }
        if (verdict != null && !verdict.done) QuestRun.reached(level, q, verdict, null, p);
    }

    /** [Crime.Listener] What a player did for a case: the step it was, crossed off. */
    static void helped(ServerPlayer p, UUID village, int caseId, String how) {
        ServerLevel level = p.serverLevel();
        String key = switch (how) {
            case "asked a witness", "got the truth out of a witness" -> "ask";
            case "brought in what was dropped" -> "dropped";
            case "followed the footprints" -> "trail";
            case "told the watch what they saw" -> "tell";
            default -> "";
        };
        if (key.isEmpty()) return;
        for (Quest q : QuestBook.active(p.getUUID())) {
            if (!WATCH.equals(q.script) || num(q.flag("case")) != caseId || q.state != State.ACTIVE) continue;
            Step s = q.step(key);
            if (s == null || s.done) continue;
            if (key.equals("ask")) {
                s.progress++;
                QuestBook.changed();
                if (s.progress < s.count) {
                    QuestRun.tell(p, Component.literal(q.title + ": asked " + s.progress + " of " + s.count + ".").withStyle(ChatFormatting.YELLOW));
                    continue;
                }
                complete(level, q, s, p, "Asked.");
            } else {
                complete(level, q, s, p, switch (key) {
                    case "dropped" -> "Handed in.";
                    case "trail" -> "Followed.";
                    default -> "Told the watch what you saw.";
                });
            }
        }
    }

    /** [Crime.Listener] A case closed: the quests on it told how (and a wrong conviction the player's word brought about paid for). */
    static void closed(UUID village, int caseId, boolean solved) {
        ServerLevel level = levelOf(village);
        Crime.Case c = Crime.get(caseId);
        if (level == null) return;
        if (c != null && !solved && c.stage == Crime.Stage.CONVICTED && c.accused != null && !c.accused.equals(c.culprit)) {
            misnamed(level, c, c.accused);
        }
        long day = QuestRun.day(level);
        for (Quest q : QuestBook.open(village)) {
            if (!WATCH.equals(q.script) || num(q.flag("case")) != caseId) continue;
            if (q.state == State.OFFERED) {
                QuestRun.withdraw(level, q, "the case is closed");
                continue;
            }
            q.flags.put("verdict", solved ? "right" : "wrong");
            q.deadline = Math.max(q.deadline, day + 2);                 // the pay waits for the player's return a day or two
            QuestBook.changed();
            ServerPlayer p = online(level, q.player);
            if (p != null && c != null) settleWatch(level, q, c, p);
            else if (!solved && c != null) endQuietly(level, q, verdictWords(q, c));
        }
    }

    /** [Trial.acquit] One accused cleared: if it was a player's word that put it before the council, the player pays for it. */
    static void acquitting(ServerLevel level, Crime.Case c, UUID accused) {
        if (accused.equals(c.culprit)) return;
        misnamed(level, c, accused);
    }

    /**
     * An innocent before the council on a player's word: the player's statement named it, and without it the evidence
     * would not have named anybody (not even a hasty guard). The accused never forgets it, the town thinks the less of the
     * player (its standing), and the chronicle has it.
     */
    static void misnamed(ServerLevel level, Crime.Case c, UUID accused) {
        long day = QuestRun.day(level);
        double all = Inquiry.against(c, accused);
        for (Crime.Statement st : List.copyOf(c.statements)) {
            if (!st.player || !accused.equals(st.named)) continue;
            double word = st.claimed / 20.0 * st.trust * 1.1;
            if (all - word >= Inquiry.HASTY) continue;                    // the watch had enough without it
            String player = st.fromName, name = st.namedName.isEmpty() ? Mischief.nameOf(level, c.village, accused) : st.namedName;
            String noted = name + " was before the council on " + player + "'s word";
            boolean paid = false;
            for (String n : c.notes) paid |= n.contains(noted);
            if (paid) continue;                                             // once for one word, however often it is tried
            VillageFolkEntity wronged = Civics.find(level, accused);
            if (wronged != null) {
                wronged.persona().feelFor(st.from, player, -20);
                wronged.persona().remember(day, player + " told the watch it was me, and it wasn't", 6);
            }
            for (AssistantEntity a : Villages.folkOf(c.village)) {
                if (a instanceof VillageFolkEntity f && !f.isShowcase() && f != wronged && f.persona().knows(st.from)) f.persona().feelFor(st.from, player, -4);
            }
            Standing.stir(c.village, st.from);
            c.note(day, noted + ", and " + name + " never did it.");
            Villages.tell(c.village, day, name + " was wrongly accused of " + c.kind.word + " on " + player + "'s word");
            for (Quest q : QuestBook.active(st.from)) {
                if (!WATCH.equals(q.script) || num(q.flag("case")) != c.id) continue;
                q.flags.put("misnamed", "1");
                endQuietly(level, q, verdictWords(q, c));                   // the watch wants no more of that help, and pays nothing for it
            }
            ServerPlayer p = online(level, st.from);
            if (p != null) {
                p.sendSystemMessage(Component.literal("Your word put " + name + " before the council, and " + name + " never did it. "
                    + Villages.name(c.village) + " thinks the less of you.").withStyle(ChatFormatting.RED));
            }
            Crime.changed();
            LOG.info("[MCA-WEAVE] {} wrongly accused on {}'s word (case #{})", name, player, c.id);
        }
    }

    static final Crime.Listener LISTENER = new Crime.Listener() {
        @Override
        public void helped(ServerPlayer p, UUID village, int caseId, String how) {
            Weave.helped(p, village, caseId, how);
        }

        @Override
        public void closed(UUID village, int caseId, boolean solved, List<UUID> helpers) {
            Weave.closed(village, caseId, solved);
        }
    };

    // ================================================================== 2. lost pets

    /** [QuestMaker.lostPets] The pets lost now (Pets), each with the household folk who will ask after it. */
    static List<QuestMaker.LostPet> lostPets(ServerLevel level, Villages.Village v) {
        List<QuestMaker.LostPet> out = new ArrayList<>();
        for (Pets.Lost l : Pets.lostIn(level, v.id())) {
            Homes.Home h = Homes.homes(v.id()).get(l.home().asLong());
            UUID owner = null;
            if (l.owner() != null && QuestMaker.free(Civics.find(level, l.owner()))) owner = l.owner();
            if (owner == null && h != null) {
                for (UUID m : h.members) {
                    VillageFolkEntity f = Civics.find(level, m);
                    if (QuestMaker.free(f)) { owner = m; break; }
                }
            }
            if (owner == null) continue;
            out.add(new QuestMaker.LostPet(l.pet(), l.name(), l.kind(), owner, l.home()));
        }
        return out;
    }

    /** [QuestMaker.pet] A lost pet's quest: held out there for it, and found the way Pets finds a pet (it follows you home). */
    static void petQuest(Quest q, QuestMaker.LostPet lp) {
        if (!Pets.isLost(lp.pet())) return;
        q.flags.put("pets", "1");
        Pets.Care c = Pets.care(q.village, lp.pet());
        if (!c.held) {
            c.held = true;                                                 // it waits out there for whoever takes the quest on
            Pets.save(q.village, c);
        }
        String kind = lp.kind().toLowerCase(Locale.ROOT).contains("cat") ? "cat" : "dog";
        Step find = q.step("find"), home = q.step("home");
        if (find != null) find.text = "Find " + lp.name() + ", the " + kind + ", out past the town's edge, last seen about here";
        if (home != null) home.text = "Lead " + lp.name() + " home: once it knows you, it follows you";
        q.offer = q.offer.replace(" A lead would help.", " It'll follow you home once it knows you.").replace(" Take a lead.", "")
            .replace(" A lead might help…", "").replace(" Bring a lead!", "");
    }

    static boolean petsOwn(Quest q) {
        return "1".equals(q.flag("pets"));
    }

    static boolean petStillLost(Quest q) {
        return !petsOwn(q) || Pets.isLost(UUID.fromString(q.flag("pet")));
    }

    /** [QuestMaker.PET.reached] Found: it knows the player means home, and follows (as a click on it does). */
    static void petStep(ServerLevel level, Quest q, Step s, Player p) {
        if (!petsOwn(q) || !s.key.equals("find")) return;
        UUID pet = UUID.fromString(q.flag("pet"));
        Pets.Care c = Pets.care(q.village, pet);
        if (c.lostDay < 0) return;
        c.finder = p.getUUID();
        Pets.save(q.village, c);
        if (level.getEntity(pet) instanceof TamableAnimal a) level.broadcastEntityEvent(a, (byte) 7);
    }

    /** [QuestMaker.PET.ended] Given up, failed, or offered and never taken: it makes its own way home. */
    static void petQuestEnded(Quest q) {
        if (!petsOwn(q)) return;
        try {
            UUID pet = UUID.fromString(q.flag("pet"));
            if (Pets.isLost(pet)) Pets.release(q.village, pet);
        } catch (IllegalArgumentException ignored) {
            // not a pet's id
        }
    }

    /** [Pets.onFound] A lost pet brought home: the quest's step done (if it was this player's), or the offer taken down. */
    static void found(Pets.Lost lost, ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        for (Quest q : QuestBook.open(lost.village())) {
            if (!"favour.pet".equals(q.script) || !lost.pet().toString().equals(q.flag("pet"))) continue;
            if (q.state == State.OFFERED) {
                QuestRun.withdraw(level, q, p.getName().getString() + " brought " + lost.name() + " home");
                continue;
            }
            if (!p.getUUID().equals(q.player)) {
                endQuietly(level, q, p.getName().getString() + " brought " + lost.name() + " home first");
                continue;
            }
            Step home = q.step("home");
            for (Step s : q.steps) {
                if (s == home) break;
                if (!s.done) {
                    s.done = true;
                    s.note = "Found.";
                }
            }
            if (home != null && !home.done) {
                home.note = "Home, at your heels.";
                QuestRun.reached(level, q, home, null, p);
            }
        }
    }

    /**
     * Once a day for a town: now and then a dog goes off after a rabbit and is lost for the quest board (a town with pets
     * and none lost, a fortnight since the last at the least), and the household asks for help at once; and a pet held out
     * there three days with no quest standing for it is let make its own way home.
     */
    static void petsDaily(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<Pets.Lost> lost = Pets.lostIn(level, id);
        for (Pets.Lost l : lost) {
            if (!l.held() || day - l.day() < 3) continue;
            boolean asked = false;
            for (Quest q : QuestBook.open(id)) if ("favour.pet".equals(q.script) && l.pet().toString().equals(q.flag("pet"))) asked = true;
            if (!asked) Pets.release(id, l.pet());
        }
        if (!lost.isEmpty()) return;
        long last = parseLong(Ledger.note(id, "weave.petLost"), -1000L);
        if (day - last < PET_GAP || level.getRandom().nextInt(4) != 0) return;
        sendAPetOff(level, v, day);
    }

    /** A dog (else a cat) of the town's off after a rabbit, out past the edge, and its household asking after it. */
    @Nullable
    static Quest sendAPetOff(ServerLevel level, Villages.Village v, long day) {
        Pets.Lost l = Pets.lost(level, v.id());
        if (l == null) return null;
        Ledger.note(v.id(), "weave.petLost", Long.toString(day));
        Quest q = QuestMaker.pet(level, v, day);
        return q != null && QuestRun.offer(level, q) ? q : null;
    }

    static long parseLong(@Nullable String s, long or) {
        if (s == null || s.isEmpty()) return or;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return or;
        }
    }

    // ================================================================== 3. fire, flood and the rebuilding

    /** While a fire burns, the river is up or the rebuilding waits: the town asks for help at once (not on the quests' usual round). */
    static void urgent(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        boolean fire = FireBrigade.burningNow(id) != null;
        Disasters.Town t = Disasters.known(id);
        boolean flood = t != null && t.flood != null && !t.flood.homes.isEmpty() && !t.flood.draining;
        boolean waits = t != null && !t.rebuilds.isEmpty() && !t.rebuilds.get(0).waits.isEmpty();
        if (!fire && !flood && !waits) return;
        if (Villages.headcount(id) < 3) return;
        if (fire) offer(level, fire(level, v, day));
        if (flood) offer(level, flood(level, v, day));
        if (waits) offer(level, rebuild(level, v, day));
    }

    private static void offer(ServerLevel level, @Nullable Quest q) {
        if (q != null) QuestRun.offer(level, q);
    }

    /** The fire brigade's chief: the head of the bucket chain (at the water, who called the line), else the elder. */
    @Nullable
    static VillageFolkEntity chief(ServerLevel level, UUID village) {
        UUID head = BucketChain.head(village);
        VillageFolkEntity f = head == null ? null : Civics.find(level, head);
        if (QuestMaker.free(f)) return f;
        VillageFolkEntity e = QuestMaker.elder(level, village);
        return QuestMaker.free(e) ? e : null;
    }

    // ------------------------------------------------------------------ water for the fire

    @Nullable
    static Quest fire(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        String where = FireBrigade.burningNow(id);
        if (where == null || QuestMaker.already(id, FIRE, "fire", "1")) return null;
        VillageFolkEntity chief = chief(level, id);
        if (chief == null) return null;
        String c = chief.displayNameCap();
        boolean chain = BucketChain.head(id) != null && chief.getUUID().equals(BucketChain.head(id));
        Quest q = QuestMaker.newQuest(FIRE, Kind.TOWN, v, chief, "Water for the fire " + where,
            QuestTalk.voice(chief,
                "Fire " + where + "! " + (chain ? "The chain's short of buckets. " : "") + "Bring me a bucket of water — quick as you can — and another after it. The treasury pays.",
                "Fire " + where + ". Water. Now. A bucket at a time, as fast as you can carry it.",
                "There's a fire " + where + "! Could you — could you bring water? A bucket, anything…",
                "FIRE " + where.toUpperCase(Locale.ROOT) + "! Every bucket counts — bring me water and the treasury pays!"));
        q.coins = QuestRewards.affordTreasury(id, 3 * MOST_BUCKETS);
        q.payer = "treasury";
        q.warmth = 8;
        q.days = 1;
        q.flags.put("fire", "1");
        q.flags.put("where", where);
        q.flags.put("give.water1", "keep");
        q.flags.put("chronicle", "{who} carried water to the fire " + where);
        q.flags.put("memory", "carried water to the fire " + where);
        q.steps.add(new Step(StepType.GIVE, "water1", "Bring " + c + " a bucket of water for the fire " + where).who(chief).item("minecraft:water_bucket", 1));
        return q;
    }

    static final QuestRun.Script FIRE_SCRIPT = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            if (!s.key.startsWith("water")) return "";
            int poured = num(q.flag("poured")) + 1;
            q.flags.put("poured", Integer.toString(poured));
            int out = douse(level, q.village, f != null ? f.blockPosition() : p.blockPosition());
            QuestItems.give(p, new ItemStack(Items.BUCKET));                   // the empty bucket back: the water is on the fire
            s.note = out > 0 ? "Poured: " + out + (out == 1 ? " flame" : " flames") + " out." : "Poured on the embers.";
            q.flags.remove("kept." + s.key);
            if (FireBrigade.burningNow(q.village) != null && poured < MOST_BUCKETS) {
                String key = "water" + (poured + 1);
                q.flags.put("give." + key, "keep");
                q.steps.add(new Step(StepType.GIVE, key, "Another bucket of water for " + q.giverName + ", quick!").who(q.giver, q.giverName)
                    .item("minecraft:water_bucket", 1));
            }
            return f == null ? "" : QuestTalk.voice(f, "Down the line with it! " + (out > 0 ? "That's " + out + " flames out." : "Keep them coming."),
                "Good. More.", "Oh — thank you! It's working!", "SPLASH! Ha! Another!");
        }

        @Override
        public void tick(ServerLevel level, Quest q, Player p) {
            settleFire(level, q, p);
        }

        @Override
        public void townTick(ServerLevel level, Villages.Village v, Quest q) {
            if (q.state == State.ACTIVE && FireBrigade.burningNow(q.village) == null && num(q.flag("poured")) == 0) {
                handBack(level, q);
                endQuietly(level, q, "the fire was out before the water came");
            }
        }

        @Override
        public String ending(ServerLevel level, Quest q, Player p) {
            int poured = num(q.flag("poured"));
            q.coins = Math.min(q.coins, 3 * poured);
            return poured + (poured == 1 ? " bucket" : " buckets") + " of water on the fire " + q.flag("where") + ", and the empties back in your hands.";
        }

        @Override
        public boolean stands(ServerLevel level, Quest q) {
            return FireBrigade.burningNow(q.village) != null;
        }
    };

    /** The fire out with a bucket still wanted: done with what was poured (paid for it), or over if none was. */
    static void settleFire(ServerLevel level, Quest q, Player p) {
        if (q.state != State.ACTIVE || FireBrigade.burningNow(q.village) != null) return;
        Step s = q.current();
        if (s == null || !s.key.startsWith("water")) return;
        handBack(level, q);
        if (num(q.flag("poured")) == 0) {
            endQuietly(level, q, "the fire was out before the water came");
            return;
        }
        s.done = true;
        s.note = "The fire was out by then.";
        QuestRun.finish(level, q, p);
    }

    /** A bucket handed over and not yet poured (the fire went out): back to the player, still full. */
    static void handBack(ServerLevel level, Quest q) {
        for (Step s : q.steps) {
            int kept = num(q.flag("kept." + s.key));
            if (kept <= 0) continue;
            q.flags.remove("kept." + s.key);
            ServerPlayer p = online(level, q.player);
            Villages.Village v = Villages.get(q.village);
            if (p != null) QuestItems.give(p, new ItemStack(Items.WATER_BUCKET, kept));
            else if (v != null) Crafts.store(level, v, new ItemStack(Items.WATER_BUCKET, kept));
        }
    }

    /** A bucket of water thrown on the town's fire, at the flames nearest this spot: the flames out about where it lands. */
    static int douse(ServerLevel level, UUID village, BlockPos near) {
        List<BlockPos> burning = FireBrigade.burningBlocks(village);
        BlockPos target = null;
        double best = Double.MAX_VALUE;
        for (BlockPos b : burning) {
            if (!level.getBlockState(b).is(BlockTags.FIRE)) continue;
            double d = b.distSqr(near);
            if (d < best) { best = d; target = b; }
        }
        if (target == null) return 0;
        int out = 0;
        for (BlockPos b : BlockPos.betweenClosed(target.offset(-3, -2, -3), target.offset(3, 3, 3))) {
            if (b.distSqr(target) > FireBrigade.SPLASH * FireBrigade.SPLASH + 1 || !level.getBlockState(b).is(BlockTags.FIRE)) continue;
            level.removeBlock(b, false);
            out++;
        }
        level.playSound(null, target, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.SPLASH, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 20, 0.8, 0.4, 0.8, 0.1);
        return out;
    }

    // ------------------------------------------------------------------ a flooded household's things

    @Nullable
    static Quest flood(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Disasters.Town t = Disasters.known(id);
        if (t == null || t.flood == null || t.flood.draining) return null;
        for (long anchor : List.copyOf(t.flood.homes)) {
            if (QuestMaker.already(id, FLOOD, "home", Long.toString(anchor))) continue;
            Homes.Home h = Homes.homes(id).get(anchor);
            if (h == null || h.members.isEmpty()) continue;
            BlockPos chest = Homes.chestOf(level, id, h);
            if (chest == null || stacks(level, chest) == 0) continue;
            VillageFolkEntity member = null;
            for (VillageFolkEntity m : Homes.loadedMembers(id, h)) if (!m.isBaby() && m.isAlive()) { member = m; break; }
            if (member == null) continue;
            VillageFolkEntity elder = QuestMaker.elder(level, id);
            VillageFolkEntity giver = QuestMaker.free(elder) ? elder : QuestMaker.free(member) ? member : null;
            if (giver == null) continue;
            String fam = family(member), m = member.displayNameCap();
            Quest q = QuestMaker.newQuest(FLOOD, Kind.TOWN, v, giver, "Save the " + fam + " family's things",
                giver == member ? QuestTalk.voice(giver,
                    "The water's in our house, and everything we have is in the chest by the wall. Would you wade in and bring it up to me on the high ground?",
                    "Our things are under the flood. Fetch them up here. Please.",
                    "Our house is full of water… our things are in the chest. Could you… bring them up to me?",
                    "Our house is a pond! Everything's in the chest — would you be a hero and fetch it up here?")
                    : QuestTalk.voice(giver,
                        "The " + fam + "s' house is under the flood, and everything they own is in the chest by the wall. Wade in and carry it up to " + m
                            + " on the high ground. The treasury pays.",
                        "The " + fam + "s' things are under water. Get them out and up to " + m + ".",
                        "The water's in the " + fam + "s' house… their things are in the chest. Would you take them up to " + m + "?",
                        "Quick! The " + fam + "s' things are going under! Carry them up to " + m + " on the high ground!"));
            q.coins = QuestRewards.affordTreasury(id, 10);
            q.payer = "treasury";
            q.warmth = 10;
            q.days = 2;
            q.flags.put("home", Long.toString(anchor));
            q.flags.put("member", member.getStringUUID());
            q.flags.put("chest", Long.toString(chest.asLong()));
            q.flags.put("give.things", "folk");
            q.flags.put("chronicle", "{who} carried the " + fam + " family's things out of the flood");
            q.flags.put("memory", "saved our things from the flood");
            q.steps.add(new Step(StepType.GO, "house", "Wade into the " + fam + "s' house and take their things out of the chest").at(chest, 3));
            q.steps.add(new Step(StepType.GIVE, "things", "Carry the " + fam + "s' things up to " + m + " on the high ground").who(member).item("quest", 1));
            return q;
        }
        return null;
    }

    static String family(VillageFolkEntity f) {
        String n = f.displayNameCap();
        int sp = n.lastIndexOf(' ');
        return sp > 0 ? n.substring(sp + 1) : n;
    }

    static int stacks(ServerLevel level, BlockPos chest) {
        int n = 0;
        if (level.getBlockEntity(chest) instanceof Container c) for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) n++;
        return n;
    }

    static final QuestRun.Script FLOOD_SCRIPT = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            Step things = q.step("things");
            if (s.key.equals("house")) {
                BlockPos chest = BlockPos.of(Long.parseLong(q.flag("chest")));
                int taken = 0, n = 0;
                if (level.getBlockEntity(chest) instanceof Container c) {
                    for (int i = 0; i < c.getContainerSize() && n < THINGS_MOST; i++) {
                        ItemStack st = c.getItem(i);
                        if (st.isEmpty()) continue;
                        ItemStack out = st.copy();
                        c.setItem(i, ItemStack.EMPTY);
                        CustomData.update(DataComponents.CUSTOM_DATA, out, tag -> tag.putInt(QuestItems.QUEST, q.id));
                        taken += out.getCount();
                        n++;
                        QuestItems.give(p, out);
                    }
                    c.setChanged();
                }
                if (things != null) {
                    if (taken == 0) {
                        things.done = true;
                        things.note = "There was nothing left in the chest to save.";
                    } else {
                        things.count = taken;
                    }
                }
                s.note = taken == 0 ? "The chest was empty." : "You have their things: " + n + (n == 1 ? " lot" : " lots") + ", " + taken + " in all.";
                QuestBook.changed();
                return "";
            }
            if (s.key.equals("things") && f != null) {
                // Theirs again: its own keepsakes, carried home to the chest when the water is down (Homes).
                for (ItemStack st : f.getInventoryItems()) {
                    if (QuestItems.questOf(st) != q.id) continue;
                    CustomData.update(DataComponents.CUSTOM_DATA, st, tag -> tag.remove(QuestItems.QUEST));
                }
                f.persona().remember(QuestRun.day(level), p.getName().getString() + " brought our things up out of the flood", 5);
                s.note = "All of it safe on the high ground.";
                return QuestTalk.voice(f, "Our things! Oh, thank you — I thought we'd lost the lot.", "Hm. Dry, mostly. Thanks.",
                    "Oh… our things. Thank you. Thank you so much.", "You saved it all! You're a marvel!");
            }
            return "";
        }

        @Override
        public boolean stands(ServerLevel level, Quest q) {
            Disasters.Town t = Disasters.known(q.village);
            return t != null && t.flood != null && !t.flood.draining && t.flood.homes.contains(Long.parseLong(q.flag("home")));
        }

        /** Given up or run out with their things in the player's arms: handed back to the household, never kept. */
        @Override
        public void ended(ServerLevel level, Quest q) {
            if (q.state == State.DONE) return;
            ServerPlayer p = online(level, q.player);
            if (p == null) return;
            List<ItemStack> back = QuestItems.take(p, QuestItems.matcher("quest", q.id), 64 * 36);
            if (back.isEmpty()) return;
            VillageFolkEntity m = Civics.find(level, q.flagId("member"));
            Villages.Village v = Villages.get(q.village);
            for (ItemStack s : back) {
                CustomData.update(DataComponents.CUSTOM_DATA, s, tag -> tag.remove(QuestItems.QUEST));
                if (m != null && m.isAlive()) QuestItems.toFolk(m, s);
                else if (v != null) Crafts.store(level, v, s);
            }
            QuestRun.tell(p, Component.literal("You hand the " + q.title.replaceFirst("^Save the ", "") + " back.").withStyle(ChatFormatting.GRAY));
        }
    };

    // ------------------------------------------------------------------ what the rebuilding waits for

    /** What a burnt block is put back with, as a quest asks for it: {item key, how many a block, the words}. */
    @Nullable
    static String[] material(BlockState was) {
        if (was.getBlock() instanceof BedBlock) return new String[]{ "wool", "3" };
        if (was.is(BlockTags.WOOL) || was.is(BlockTags.WOOL_CARPETS)) return new String[]{ "wool", "1" };
        if (was.is(BlockTags.LOGS)) return new String[]{ "logs", "1" };
        if (was.is(Blocks.HAY_BLOCK)) return new String[]{ "minecraft:wheat", "9" };
        if (was.is(Blocks.BOOKSHELF)) return new String[]{ "minecraft:book", "3" };
        if (Rebuilding.wooden(was)) return new String[]{ "planks", "1" };
        Item it = was.getBlock().asItem();
        if (it == Items.AIR) return null;
        return new String[]{ BuiltInRegistries.ITEM.getKey(it).toString(), "1" };
    }

    @Nullable
    static Quest rebuild(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Disasters.Town t = Disasters.known(id);
        if (t == null || t.rebuilds.isEmpty()) return null;
        Disasters.Rebuild r = t.rebuilds.get(0);
        if (r.waits.isEmpty() || r.cells.isEmpty() || QuestMaker.already(id, REBUILD, "anchor", Long.toString(r.anchor.asLong()))) return null;
        String[] mat = material(r.cells.values().iterator().next());
        if (mat == null) return null;
        int need = 0;
        for (BlockState st : r.cells.values()) {
            String[] m = material(st);
            if (m != null && m[0].equals(mat[0])) need += num(m[1]);
        }
        int count = Math.max(4, Math.min(64, need));
        VillageFolkEntity elder = QuestMaker.elder(level, id);
        if (!QuestMaker.free(elder)) return null;
        String what = QuestItems.words(mat[0], count), e = elder.displayNameCap();
        Item it = sample(mat[0]);
        int worth = (int) Math.max(6, Math.min(40, Math.round(Prices.each(it) * count * 1.3)));
        Quest q = QuestMaker.newQuest(REBUILD, Kind.TOWN, v, elder, QuestTalk.capFirst(mat[0].replaceFirst("^[a-z_]+:", "").replace('_', ' '))
                + " to rebuild " + r.where,
            QuestTalk.voice(elder,
                "We're putting " + r.where + " back after the fire, but the stores have run dry of " + r.waits + ". Bring me " + what + " and the treasury will see you right.",
                "The rebuilding's stuck: no " + r.waits + ". " + QuestTalk.capFirst(what) + ". The treasury pays.",
                "The rebuilding… it's waiting on " + r.waits + ". Could you bring " + what + "?",
                "Let's get " + r.where + " standing again! We need " + what + " — bring them and the treasury pays!"));
        q.coins = QuestRewards.affordTreasury(id, worth);
        q.payer = "treasury";
        q.warmth = 8;
        q.days = 3;
        q.flags.put("anchor", Long.toString(r.anchor.asLong()));
        q.flags.put("key", mat[0]);
        q.flags.put("chronicle", "{who} brought " + what + " for the rebuilding of " + r.where);
        q.steps.add(new Step(StepType.GIVE, "bring", "Bring " + e + " " + what + " for the rebuilding of " + r.where).who(elder).item(mat[0], count));
        return q;
    }

    /** One of what a key stands for, for its price. */
    static Item sample(String key) {
        return switch (key) {
            case "planks" -> Items.OAK_PLANKS;
            case "wool" -> Items.WHITE_WOOL;
            case "logs" -> Items.OAK_LOG;
            default -> {
                net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(key);
                yield rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
            }
        };
    }

    static final QuestRun.Script REBUILD_SCRIPT = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            q.flags.put("chronicle", q.flag("chronicle").replace("{who}", p.getName().getString()));
            Villages.Village v = Villages.get(q.village);
            Disasters.Town t = Disasters.known(q.village);
            int put = v == null || t == null ? 0 : Rebuilding.work(level, v, t, Rebuilding.STEP);   // the work goes on at once
            s.note = put > 0 ? put + " blocks back up already." : "Into the stores.";
            return f == null ? "" : QuestTalk.voice(f, "Into the stores, and the rebuilding goes on. Thank you.", "Good. Back to work, all of you.",
                "Oh, that's the rebuilding moving again. Thank you.", "Hammers out, everybody! We're building again!");
        }

        @Override
        public boolean stands(ServerLevel level, Quest q) {
            Disasters.Town t = Disasters.known(q.village);
            if (t == null) return false;
            for (Disasters.Rebuild r : t.rebuilds) if (Long.toString(r.anchor.asLong()).equals(q.flag("anchor"))) return !r.waits.isEmpty();
            return false;
        }
    };

    // ------------------------------------------------------------------ the homeless go as refugees

    /** [Rebuilding.lodging] A burnt- or flooded-out folk found no neighbour's bed, no inn and no hall: it may go as a refugee. */
    static void noBed(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !live() || SENT.contains(f.getUUID())) return;
        HOMELESS.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(f.getUUID(), Floods.homeFlooded(f) ? "flood" : "fire");
    }

    /** The town's homeless with nowhere to sleep, sent with their households to the nearest town at peace (Newcomers). */
    static void refugees(ServerLevel level, Villages.Village v) {
        Map<UUID, String> who = HOMELESS.remove(v.id());
        if (who == null || who.isEmpty()) return;
        List<VillageFolkEntity> homeless = new ArrayList<>();
        String cause = "fire";
        for (Map.Entry<UUID, String> e : who.entrySet()) {
            VillageFolkEntity f = Civics.find(level, e.getKey());
            if (f == null || !f.isAlive() || !v.id().equals(f.ownerId()) || !Rebuilding.displaced(f)) continue;
            // The whole household, children and all, goes together.
            for (VillageFolkEntity m : JobSeekers.household(f)) if (!homeless.contains(m)) homeless.add(m);
            if (!homeless.contains(f)) homeless.add(f);
            if (e.getValue().equals("flood")) cause = "flood";
        }
        if (homeless.isEmpty()) return;
        int went = Newcomers.displaced(level, v, homeless, cause);
        if (went > 0) {
            for (VillageFolkEntity f : homeless) SENT.add(f.getUUID());
            LOG.info("[MCA-WEAVE] {}: {} homeless after the {} went as refugees", Villages.name(v.id()), went, cause);
        }
    }

    // ================================================================== 5. what may be sold, and the auction's lots

    /** Never auctioned or sold: anything stolen (its case's mark on it, Mischief) and a forged coin. */
    public static boolean unsellable(ItemStack s) {
        return !s.isEmpty() && (Crime.stolenCase(s) > 0 || s.is(McAssistantMod.FORGED_COIN.get()));
    }

    /**
     * [Auctions.townLots] Beyond the auction's own rare finds: the tailor's finest (a fine or a master's garment, a gold
     * brooch) that nobody has on order, and a find of the cave team's the lodge's six frames have no room for.
     */
    static boolean lot(ServerLevel level, UUID village, ItemStack s) {
        if (unsellable(s)) return false;
        Garment g = Garment.of(s);
        if (g != null) {
            if (g == Garment.ROSETTE || Fashion.secondHand(s)) return false;
            Craftsmanship.Grade grade = Craftsmanship.gradeOf(s);
            boolean finest = g == Garment.BROOCH || grade == Craftsmanship.Grade.FINE || grade == Craftsmanship.Grade.MASTER;
            if (!finest) return false;
            int colour = Garment.colourOf(s);
            for (Tailoring.Order o : Tailoring.book(village)) if (o.kind == g && (o.colour == colour || !g.dyeable())) return false;
            return true;
        }
        return trophyPastSix(level, village, s);
    }

    /** A kind of find the cave team brought up, noted for the lodge, that its full trophy wall has no room for. */
    static boolean trophyPastSix(ServerLevel level, UUID village, ItemStack s) {
        Item it = Lodge.trophyOf(s);
        if (it == null || !Lodge.trophies(village).containsKey(Lodge.key(it))) return false;
        List<String> shown = Lodge.shown(level, village);
        return shown.size() >= Lodge.TROPHIES.length && !shown.contains(Lodge.key(it));
    }

    /** [Auctions.provenance] Where one of the weave's lots came from, in words; null for the auction's own. */
    @Nullable
    static String provenance(ServerLevel level, UUID village, ItemStack s) {
        Item it = Lodge.trophyOf(s);
        if (it != null) {
            String noted = Lodge.trophies(village).get(Lodge.key(it));
            if (noted != null) {
                String[] p = noted.split("\\|", -1);
                return "brought up from the caves by " + p[0] + (p.length > 1 && !p[1].isEmpty() ? " from " + p[1] : "")
                    + (trophyPastSix(level, village, s) ? ", one the lodge's trophy wall had no room for" : "");
            }
        }
        Garment g = Garment.of(s);
        if (g != null) {
            CustomData d = s.get(DataComponents.CUSTOM_DATA);
            String by = d == null ? "" : d.copyTag().getCompound("mca_made").getString("by");
            Craftsmanship.Grade grade = Craftsmanship.gradeOf(s);
            return (grade == null ? "the tailor's" : grade.words) + (by.isEmpty() ? "" : " by " + by) + ", from the shop's finest";
        }
        return null;
    }

    /** [Auctions.want] How much more a fine garment is to the vain and the tailor; old finds to the cave team. */
    static double wants(VillageFolkEntity f, ItemStack s) {
        if (Garment.of(s) != null) {
            double w = Fashion.vain(f) ? 1.45 : 1.0;
            if (f.stationTask() == StationTask.TAILOR) w *= 1.25;
            if (Fashion.traditional(f)) w *= 0.7;
            if (!role(f).isEmpty()) w *= 1.15;                          // an office to dress for
            return w;
        }
        if (Lodge.trophyOf(s) != null && f.stationTask() == StationTask.CAVE) return 1.3;
        return 1.0;
    }

    /** [Auctions.deliver] A garment won at the auction is put on at once, marked for the season it was won in. True if worn. */
    static boolean wearWon(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack item, long day) {
        Garment g = Garment.of(item);
        if (g == null || f.isBaby()) return false;
        ItemStack one = item.copyWithCount(1);
        int season = Fashion.seasonOf(v.id(), day);
        CustomData.update(DataComponents.CUSTOM_DATA, one, tag -> tag.putInt(WON, season + 1));
        Fashion.wear(level, v, f, one, "won");                             // (the auction's own memory of it is enough)
        // On at once, there by the rostrum, to the crowd's delight.
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 1.1, f.getZ(), 14, 0.45, 0.55, 0.45, 0.02);
        level.playSound(null, f.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.NEUTRAL, 0.9F, 1.1F);
        String what = g.a(Garment.colourOf(one)).replaceFirst("^an? ", "");
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Mine! And on it goes, here and now.", "Won it — and I'm wearing it home. Well? How do I look?",
            "The " + what + "! I've wanted one all season."));
        return true;
    }

    /** [Fashion.inFashion] Wearing a garment won at the auction this season: the talk of the town, whatever its colour. */
    static boolean wonFinery(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return false;
        int season = Fashion.seasonOf(id, f.level().getDayTime() / 24000L) + 1;
        for (Garment.Slot slot : Garment.Slot.values()) {
            ItemStack s = f.style().worn(slot);
            CustomData d = s.isEmpty() ? null : s.get(DataComponents.CUSTOM_DATA);
            if (d != null && d.copyTag().getInt(WON) == season) return true;
        }
        return false;
    }

    // ================================================================== 6. fashion and the offices

    /**
     * The office a folk dresses for: the steward of a player leader, the constable, the librarian, the auctioneer, a
     * cave dweller home from the caves; "" for none.
     */
    public static String role(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby()) return "";
        if (PlayerLeader.leaderId(id) != null) {
            String s = Ledger.note(id, "civic.steward");
            if (s != null && s.startsWith(f.getStringUUID())) return "steward";
        }
        if (f.stationTask() == StationTask.GUARD && Inquiry.isConstable(f)) return "constable";
        if (LibraryRecords.has(id) && f.getUUID().equals(LibraryRecords.shelf(id).librarian)) return "librarian";
        if (Auctions.holds(id) && f.getUUID().equals(Villages.elder(id))) return "auctioneer";
        if (f.stationTask() == StationTask.CAVE && !CaveDwellers.caving(f)) return "caver";
        return "";
    }

    /** What each office wears, the first it can run to: its body's garment, then its hat. */
    static Garment[] office(String role) {
        return switch (role) {
            case "librarian" -> new Garment[]{ Garment.WAISTCOAT, Garment.WOOL_SHAWL, Garment.FLAT_CAP };
            case "constable" -> new Garment[]{ Garment.FELT_HAT, Garment.LONG_COAT };
            case "auctioneer" -> new Garment[]{ Garment.TOP_HAT, Garment.FELT_HAT, Garment.WAISTCOAT };
            case "steward" -> new Garment[]{ Garment.LONG_COAT, Garment.WAISTCOAT, Garment.FELT_HAT };
            case "caver" -> new Garment[]{ Garment.LEATHER_JACKET, Garment.FLAT_CAP };
            default -> new Garment[0];
        };
    }

    /**
     * [Fashion.choose] Its office's garment, in the season's colour, when the season's own thing is beyond it: an office
     * stretches a purse a step (a librarian getting by still wants its waistcoat). Null with no office, or nothing it can
     * run to that it has not got already.
     */
    @Nullable
    static Garment roleGarment(VillageFolkEntity f, int colour, Villages.Age age, int reach) {
        for (Garment g : office(role(f))) {
            if (g.age.ordinal() > age.ordinal() || g.rank > reach + 1 || !g.dyeable()) continue;
            if (f.style().garment(g.slot) == g && f.style().colour(g.slot) == colour) continue;
            return g;
        }
        return null;
    }

    /** [Fashion.look] Its office keeps its own hat on: the constable on a case, the auctioneer at its stand, the librarian at the desk. */
    static boolean dressedForRole(VillageFolkEntity f) {
        return switch (role(f)) {
            case "constable" -> Inquiry.caseOf(f) != null;
            case "auctioneer" -> Auctions.busy(f);
            case "librarian" -> Library.busy(f);
            case "steward" -> true;
            default -> false;
        };
    }

    /** [Fashion.setters] How much the town looks to an office for its look, and why. */
    static double admired(VillageFolkEntity f, List<String> why) {
        String r = role(f);
        double s = switch (r) {
            case "steward" -> 1.5;
            case "auctioneer" -> 1.0;
            case "constable", "librarian", "caver" -> 0.5;
            default -> 0.0;
        };
        if (s > 0) why.add(switch (r) {
            case "steward" -> "the leader's steward";
            case "caver" -> "back from the caves";
            default -> "the " + r;
        });
        return s;
    }

    /** [Newcomers.send] Newcomers set out in their old town's colours (its banner's field and first charge). */
    static void oldColours(VillageFolkEntity f, Villages.Village from) {
        Heraldry.Design d = Heraldry.design(from.id());
        Style s = f.style();
        Fashion.roll(f);
        if (d != null) {
            s.main = d.field().getId();
            if (!d.layers().isEmpty() && d.layers().get(0).colour().getId() != s.main) s.accent = d.layers().get(0).colour().getId();
        }
        CompoundTag t = new CompoundTag();
        t.putString("town", Villages.name(from.id()));
        t.putLong("day", f.level().getDayTime() / 24000L);
        f.getPersistentData().put(FROM, t);
    }

    /** [Fashion.nature] A newcomer settled in a new town comes round to its fashion the quicker for wanting to fit in. */
    static double fitIn(VillageFolkEntity f) {
        CompoundTag t = f.getPersistentData().getCompound(FROM);
        if (t.isEmpty() || Newcomers.is(f) || f.ownerId() == null) return 1.0;
        long day = f.level().getDayTime() / 24000L;
        return day - t.getLong("day") <= 28 ? 2.0 : 1.0;
    }

    // ================================================================== 7. and more

    /** [Pledges.guards] A guard counts toward "more guards" in its kit: a blade (or a bow) and armour on its back. */
    static boolean kitted(AssistantEntity a) {
        if (!(a instanceof VillageFolkEntity g)) return true;
        boolean blade = Workshop.bestBlade(g) > 0;
        for (ItemStack s : g.getInventoryItems()) if (s.getItem() instanceof BowItem || s.getItem() instanceof CrossbowItem) blade = true;
        if (g.getMainHandItem().getItem() instanceof BowItem || g.getMainHandItem().getItem() instanceof CrossbowItem) blade = true;
        boolean armour = false;
        for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            if (g.getItemBySlot(slot).getItem() instanceof ArmorItem) armour = true;
        }
        return blade && armour;
    }

    /** How big a story a section of the gazette is: a death or a disaster leads, the prices come last. */
    static int weight(String section) {
        String s = section.toLowerCase(Locale.ROOT);
        String title = s.contains("§r") ? s.substring(0, s.indexOf("§r")) : s;
        String body = s.contains("§r") ? s.substring(s.indexOf("§r") + 2).trim() : "";
        if (body.isEmpty() || body.startsWith("nobody") || body.startsWith("nothing new") || body.startsWith("none given")) return 0;
        if (title.contains("fire, flood")) return body.contains("day ") || body.contains("fire") || body.contains("flood") ? 10 : 4;
        if (title.contains("war and peace")) return 9;
        if (title.contains("died")) return 8;
        if (title.contains("the watch and the court")) return body.contains("guilty") ? 7 : 5;
        if (title.contains("born") || title.contains("built")) return 6;
        if (title.contains("promises")) return 5;
        if (title.contains("the auction")) return 5;
        if (title.contains("quests and deeds") || title.contains("from the library") || title.contains("fashion")) return 4;
        if (title.contains("the quay") || title.contains("trade") || title.contains("street music")) return 3;
        if (title.contains("elder's order")) return 2;
        return 1;
    }

    /** [Gazette.issueOf] The day's sections, the biggest story first (the rest as they were). */
    static List<String> frontPage(List<String> entries) {
        List<String> out = new ArrayList<>(entries);
        out.sort((a, b) -> Integer.compare(weight(b), weight(a)));         // stable: ties keep their order
        return out;
    }

    /** [Gazette.issueOf] The headline under the masthead: the lead story's first line. */
    static String headline(List<String> entries) {
        if (entries.isEmpty() || weight(entries.get(0)) < 5) return "";
        String lead = entries.get(0);
        String body = lead.contains("§r") ? lead.substring(lead.indexOf("§r") + 2).trim() : lead;
        int nl = body.indexOf('\n');
        String first = nl > 0 ? body.substring(0, nl) : body;
        if (first.length() > 56) {                                          // three lines at most: the front page is one page
            int stop = Math.max(first.lastIndexOf("; ", 55), first.lastIndexOf(". ", 55));
            if (stop > 24) {
                first = first.substring(0, stop) + ".";                     // the first clause, a sentence of its own
            } else {
                int cut = first.lastIndexOf(' ', 55);
                first = first.substring(0, cut > 30 ? cut : 55).replaceAll("[,;:]+$", "").trim() + "…";
            }
        }
        return first.isEmpty() ? "" : "\n§lFront page:§r " + first;
    }

    /** [Pets.dogDay] The child the dog follows is reading at the library: it lies down at its feet, quiet as a mouse. */
    @Nullable
    static String library(TamableAnimal dog, VillageFolkEntity child, String petName) {
        if (!Library.seated(child)) return null;
        String kid = child.displayNameCap();
        double d = dog.distanceToSqr(child);
        if (d > 24.0 * 24.0) {
            dog.getNavigation().stop();
            dog.teleportTo(child.getX(), child.getY(), child.getZ());
        } else if (d > 1.6 * 1.6) {
            dog.setOrderedToSit(false);
            dog.setInSittingPose(false);
            dog.getNavigation().moveTo(child, 0.9D);
            return "padding after " + kid + " into the library";
        }
        dog.getNavigation().stop();
        dog.setOrderedToSit(true);
        dog.setInSittingPose(true);
        dog.getLookControl().setLookAt(child, 10.0F, dog.getMaxHeadXRot());
        if (dog.getRandom().nextInt(240) == 0) FolkTalk.speak(child, FolkTalk.pick(child.getRandom(), "Shh, " + petName + ". This is the good bit.",
            "Good " + petName + ". Lie still, and I'll read it to you."));
        return "lying at " + kid + "'s feet in the library while " + kid + " reads";
    }

    // ================================================================== 4. the trades' books

    /**
     * [TradeBooks.notes] What the new work has taught each trade, out of the town's own books: the cave team's caves, veins,
     * hauls and losses; the watch's cases; the fleet's catches and storms; the tailor's season and what it made.
     */
    static List<String> notes(ServerLevel level, Villages.Village v, StationTask t, VillageFolkEntity master, List<VillageFolkEntity> hands) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        try {
            switch (t) {
                case CAVE -> caveNotes(out, id, master);
                case GUARD -> watchNotes(out, level, id, master);
                case FISH -> fleetNotes(out, id);
                case TAILOR -> tailorNotes(out, level, id);
                default -> { }
            }
        } catch (RuntimeException e) {
            LOG.warn("[MCA-WEAVE] notes for the {}'s book: {}", t.label, e.toString());
        }
        return out;
    }

    private static void caveNotes(List<String> out, UUID id, VillageFolkEntity master) {
        List<CaveDwellers.Find> all = CaveDwellers.report(id);
        int[] n = CaveDwellers.counts(all);
        if (n[0] + n[1] > 0) {
            out.add("Between us we've found " + Quill.count(n[0] + n[1], "cave", "caves") + (n[2] > 0 ? " and " + Quill.count(n[2], "vein of ore", "veins of ore")
                + ", " + Quill.number(n[3]) + " of it mined and brought home" : "") + ".");
        }
        CaveDwellers.Find rich = null;
        for (CaveDwellers.Find f : all) {
            if (f.kind() != CaveDwellers.Kind.VEIN) continue;
            if (rich == null || rank(f.label()) > rank(rich.label()) || rank(f.label()) == rank(rich.label()) && f.a() > rich.a()) rich = f;
        }
        if (rich != null && rank(rich.label()) >= 2) {
            out.add("The richest vein we know is " + rich.label() + ", " + Quill.count(rich.a(), "block", "blocks") + " of it, found by " + rich.by() + " on day "
                + (rich.day() + 1) + ".");
        }
        if (n[5] + n[6] > 0) out.add("We've come on " + Quill.count(n[5] + n[6], "spawner", "spawners") + " down there. Break it or light it up: never leave one as you found it.");
        List<String> hauls = CaveDwellers.hauls(id);
        if (!hauls.isEmpty()) out.add("Our last haul, as the books have it: " + Quill.stop(lowerFirst(hauls.get(0).replaceFirst("^Day \\d+, ", ""))));
        int lost = 0;
        for (Chronicle.Entry e : Chronicle.of(id)) if (e.text().contains("was lost in the caves")) lost++;
        if (lost > 0) out.add("We have lost " + Quill.count(lost, "of us", "of us") + " below. Never go down alone, and never past your torches.");
        int[] torches = CaveDwellers.torchTotals(id);
        out.add("Light every fifteen blocks: a torch on your right going in, so the way out is on your left coming home."
            + (torches[1] > 0 ? " We've set " + Quill.number(torches[1]) + " torches so far" + (torches[0] > 0 ? " of the " + Quill.number(torches[0]) + " we drew" : "") + "." : ""));
    }

    private static int rank(String ore) {
        return switch (ore) {
            case "diamond", "emerald" -> 4;
            case "gold" -> 3;
            case "lapis", "redstone" -> 2;
            case "iron" -> 1;
            default -> 0;
        };
    }

    private static void watchNotes(List<String> out, ServerLevel level, UUID id, VillageFolkEntity master) {
        int solved = 0, given = 0, all = 0;
        for (Crime.Case c : Crime.cases(id)) {
            if (c.stage == Crime.Stage.UNNOTICED) continue;
            all++;
            if (c.stage == Crime.Stage.CONVICTED) solved++;
            if (c.stage == Crime.Stage.UNSOLVED) given++;
        }
        int mine = Crime.known(master.getUUID()) ? Crime.folk(master.getUUID()).getInt("solved") : 0;
        if (all > 0) {
            out.add("The watch has had " + Quill.count(all, "case", "cases") + " on its books, and " + Quill.number(solved) + " of them solved before the council"
                + (given > 0 ? "; " + Quill.number(given) + " we had to give up" : "") + "." + (mine > 0 ? " " + Quill.cap(Quill.count(mine, "of them was", "of them were")) + " mine." : ""));
        }
        VillageFolkEntity constable = Inquiry.constable(id);
        if (constable != null) out.add(constable.displayNameCap() + " is the town's constable, and every case is the constable's.");
        out.add("On a case, go to the scene first: footprints wash out in the rain, and somebody always picks up what was dropped.");
        out.add("Ask everybody who was about, and mark who says nothing. A friend of the culprit saw nothing; a friend of yours may tell you more.");
        out.add("Search the likeliest: a purse fuller than its wages, a pack, a chest at home. Never name anybody on one word alone; the council clears the innocent, and they don't forget.");
    }

    private static void fleetNotes(List<String> out, UUID id) {
        List<String[]> log = Fleet.log(id);
        if (log.isEmpty()) return;
        long fish = 0;
        int days = 0, storms = 0, rain = 0, best = 0;
        long bestOn = -1;
        for (String[] p : log) {
            if (!p[3].isEmpty()) {
                if (p[3].contains("storm")) storms++;
                else if (p[3].contains("rain")) rain++;
                continue;
            }
            int f = num(p[2]);
            days++;
            fish += f;
            if (f > best) { best = f; bestOn = parseLong(p[0], -1); }
        }
        if (days > 0) {
            out.add("The fleet has been out " + Quill.count(days, "day", "days") + " the books keep, and landed " + Quill.number(fish) + " fish at the market"
                + (best > 0 ? "; the best day was day " + bestOn + ", with " + Quill.number(best) : "") + ".");
        }
        out.add("The best grounds are out where the water's wide, ten to forty blocks off the quay: the fish bite quicker from a boat than from the bank, and quicker still with the fleet's net.");
        if (storms + rain > 0) {
            out.add("The weather kept us in " + Quill.count(storms + rain, "day", "days") + (storms > 0 ? ", " + Quill.number(storms) + " of them for storms" : "")
                + ". When the sky goes black, row for the quay: no catch is worth a life.");
        }
    }

    private static void tailorNotes(List<String> out, ServerLevel level, UUID id) {
        long day = level.getDayTime() / 24000L;
        Fashion.Trend t = Fashion.trend(id);
        if (t.set()) {
            int[] n = Fashion.counts(id);
            out.add("This " + Fashion.seasonWord(id, day) + " it's " + Garment.colourWord(t.colour) + (t.kind != null ? " " + t.kind.plural() : "")
                + (t.setter.isEmpty() ? "" : ", since " + t.setter + " took to it") + ": " + Quill.number(n[0]) + " of " + Quill.number(n[2]) + " wear it now."
                + (t.lastColour >= 0 ? " Last season it was " + Garment.colourWord(t.lastColour) + "." : ""));
        }
        int made = 0;
        for (int w : Tailoring.week(id, day)) made += w;
        int[] passed = Fashion.passedThisWeek(id, day);
        int handed = passed.length > 0 ? passed[0] : 0;
        if (made > 0 || handed > 0) {
            out.add("This week the loom turned out " + Quill.count(made, "garment", "garments") + " for the shop"
                + (handed > 0 ? ", and " + Quill.count(handed, "old one", "old ones") + " went on to folk with less" : "") + ".");
        }
        int orders = Tailoring.book(id).size();
        if (orders > 0) out.add("There " + (orders == 1 ? "is one garment" : "are " + Quill.number(orders) + " garments") + " on the book. The setter's comes first, then the oldest.");
    }

    // ================================================================== tests

    /** Tests: the case given to the watch now (a guard, or the constable). */
    public static boolean assignForTests(ServerLevel level, Crime.Case c) {
        Villages.Village v = Villages.get(c.village);
        return v != null && Inquiry.assign(level, v, c);
    }

    /** Tests: the council's verdict on the accused now, without the sitting's speeches. */
    public static void verdictForTests(ServerLevel level, Crime.Case c, boolean guilty) {
        Villages.Village v = Villages.get(c.village);
        VillageFolkEntity f = c.accused == null ? null : Civics.find(level, c.accused);
        if (v == null || f == null) return;
        Trial.Sitting s = new Trial.Sitting(c.id, v.id(), v.centre(), v.centre(), net.minecraft.core.Direction.NORTH, false, level.getGameTime());
        s.ayes = guilty ? 3 : 0;
        s.noes = guilty ? 0 : 3;
        s.guilty = guilty;
        c.stage = Crime.Stage.TRIAL;
        if (guilty) Trial.convict(level, v, c, f, s);
        else Trial.acquit(level, v, c, f, s);
    }

    /** Tests: a player says it saw this folk do it (a player's statement in the case, as Inquiry.playerReport writes one). */
    public static void wordForTests(Crime.Case c, Player p, VillageFolkEntity named, int claimed) {
        c.statements.add(new Crime.Statement(p.getUUID(), p.getName().getString(), true, "I saw " + named.displayNameCap() + " do it.", named.getUUID(),
            named.displayNameCap(), claimed, "", false, false, "the watch", c.day));
        c.helped(p);
    }

    /** Tests: is this pet held out there for the quest board? */
    public static boolean heldForTests(UUID village, UUID pet) {
        return Pets.care(village, pet).held;
    }

    /** Tests: a pet sent off after a rabbit now, and its household's quest offered. */
    @Nullable
    public static Quest lostPetForTests(ServerLevel level, Villages.Village v) {
        init();
        return sendAPetOff(level, v, QuestRun.day(level));
    }

    /** Tests: the town's drought and short rations, on or off. */
    public static void rationsForTests(UUID village, boolean on) {
        Disasters.Town t = Disasters.town(village);
        t.drought = on;
        t.rationing = on;
        Disasters.dirty();
    }

    /** Tests: the weave's round for a town now. */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        init();
        tick(level, v);
    }

    /** Tests: a burnt- or flooded-out folk looks for a bed tonight (Rebuilding's own lodging). True if it found one. */
    public static boolean lodgingForTests(VillageFolkEntity f, ServerLevel level) {
        return Rebuilding.lodging(f, level);
    }

    /** Tests: the gazette's sections in front-page order. */
    public static List<String> frontPageForTests(List<String> entries) {
        return frontPage(entries);
    }

    /** Tests: the front page's headline for these sections ("" when nothing is big enough to lead). */
    public static String headlineForTests(List<String> entries) {
        return headline(frontPage(entries));
    }

    /** Tests: who in the town dresses for an office, and what it wants of the season. */
    public static String roleForTests(VillageFolkEntity f) {
        return role(f);
    }

    @Nullable
    public static Garment roleGarmentForTests(VillageFolkEntity f) {
        UUID id = f.ownerId();
        return id == null ? null : roleGarment(f, Fashion.trendColour(id), Villages.ageOf(id), Fashion.reach(f));
    }

    /** Tests: may the town's auction take this lot, and where it came from. */
    public static boolean lotForTests(ServerLevel level, UUID village, ItemStack s) {
        return lot(level, village, s);
    }

    /** Tests: the trophies on the lodge's wall now, as the weave reads them (a full wall has six). */
    public static List<String> trophyWallForTests(ServerLevel level, UUID village) {
        return Lodge.shown(level, village);
    }

    /** Tests: where the auction says one of the weave's lots came from (null for the auction's own). */
    @Nullable
    public static String provenanceForTests(ServerLevel level, UUID village, ItemStack s) {
        return provenance(level, village, s);
    }

    /** Tests: how much the town looks to this folk for its look by its office, and why ("1.5 the leader's steward"). */
    public static String admiredForTests(VillageFolkEntity f) {
        List<String> why = new ArrayList<>();
        double s = admired(f, why);
        return s + (why.isEmpty() ? "" : " " + String.join(", ", why));
    }

    /** Tests: a lot won at the auction, delivered to the folk that won it. */
    public static void deliverForTests(ServerLevel level, VillageFolkEntity f, ItemStack item, int price) {
        Villages.Village v = Villages.get(f.ownerId());
        if (v != null) Auctions.deliver(level, v, f, item, price, level.getDayTime() / 24000L);
    }

    /** Tests: the trade book notes the weave adds for this trade. */
    public static List<String> notesForTests(ServerLevel level, Villages.Village v, StationTask t, VillageFolkEntity master) {
        return notes(level, v, t, master, List.of(master));
    }

    /** Tests: the newcomer's colours set as it leaves its old town. */
    public static void oldColoursForTests(VillageFolkEntity f, Villages.Village from) {
        oldColours(f, from);
    }

    public static double fitInForTests(VillageFolkEntity f) {
        return fitIn(f);
    }

    public static boolean kittedForTests(AssistantEntity a) {
        return kitted(a);
    }

    /** Tests: the guards a player leader's "more guards" counts (Pledges). */
    public static int guardsForTests(UUID village) {
        return Pledges.guards(village);
    }

    /** Tests: what the culprit dropped at the scene, lying there still, or null. */
    @Nullable
    public static net.minecraft.world.entity.item.ItemEntity droppedForTests(ServerLevel level, Crime.Case c) {
        return c.droppedEntity == null || c.droppedFound ? null
            : level.getEntity(c.droppedEntity) instanceof net.minecraft.world.entity.item.ItemEntity e ? e : null;
    }

    /** Tests: the river up over this house (its anchor), as a flood that has risen leaves it; null takes the flood away. */
    public static void floodForTests(UUID village, @Nullable BlockPos home) {
        Disasters.Town t = Disasters.town(village);
        if (home == null) {
            t.flood = null;
        } else {
            if (t.flood == null) t.flood = new Disasters.Flood();
            t.flood.risen = true;
            t.flood.homes.add(home.asLong());
        }
        Disasters.dirty();
    }

    /** Tests: a great work opened, in the town's civic record, as BigWorks writes one. */
    public static void workDoneForTests(UUID village, String kind, long day, int hands, int placed) {
        CompoundTag w = new CompoundTag();
        w.putString("kind", kind);
        BigWorks.Work k = BigWorks.Work.named(kind);
        w.putString("title", k == null ? kind : k.a);
        w.putLong("day", day);
        w.putInt("hands", hands);
        w.putInt("placed", placed);
        CivicRecord.list(CivicRecord.town(village), "worksDone").add(w);
        CivicRecord.changed();
    }
}
