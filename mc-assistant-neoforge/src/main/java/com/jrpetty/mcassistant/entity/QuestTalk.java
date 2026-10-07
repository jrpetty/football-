package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.QuestBook.Kind;
import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.entity.QuestBook.State;
import com.jrpetty.mcassistant.entity.QuestBook.Step;
import com.jrpetty.mcassistant.entity.QuestBook.StepType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [quests] Quests in the folk's own words.
 *
 * <ul>
 * <li><b>"Any work for me?"</b> (the talk screen's Ask tab, or say it): a folk with something to ask tells you
 *     what, why, and what it can give for it, in its own way (a grump is short, a shy one hesitates, a cheerful
 *     one cannot hide how glad it is you asked), and "I'll do it" and "Not now" come up beside it. A folk with
 *     nothing to ask says so, and who it knows has (the nearest folk with a "!" over it), and that there is the board.</li>
 * <li><b>A step it waits on.</b> Talk to a folk a step of yours waits on ("?" over it) and it is done: it tells
 *     you what it knows, takes what you brought, or asks you to choose.</li>
 * <li><b>The talk of the town.</b> The folk talk about what players have done for them and the town, and the
 *     stories going on (*"They say somebody's been at the stores again."*); the ones you helped thank you when
 *     they see you, and the ones you let down remind you of it.</li>
 * </ul>
 */
public final class QuestTalk {

    private QuestTalk() {}

    /** When each player last asked each folk for work: the "I'll do it" button stands for two minutes after. */
    private static final Map<String, Long> ASKED = new ConcurrentHashMap<>();
    private static final Map<String, Long> HINTED = new ConcurrentHashMap<>();
    private static final Map<String, Long> SEEN = new ConcurrentHashMap<>();

    static void resetForTests() {
        ASKED.clear();
        HINTED.clear();
        SEEN.clear();
    }

    static boolean asked(VillageFolkEntity f, Player p) {
        Long t = ASKED.get(f.getUUID() + "/" + p.getUUID());
        return t != null && f.level().getGameTime() - t < 2400L;
    }

    /** Tests: as if the player had asked this folk for work just now. */
    public static void askedForTests(VillageFolkEntity f, Player p) {
        ASKED.put(f.getUUID() + "/" + p.getUUID(), f.level().getGameTime());
    }

    // ------------------------------------------------------------------ its way of putting it

    /** One of four ways of saying a thing, by the folk's nature: plain, grumpy, shy, cheerful. */
    static String voice(VillageFolkEntity f, String plain, String grumpy, String shy, String cheerful) {
        Social.Life life = f.life();
        if (life.has(Social.Trait.GRUMPY)) return grumpy;
        if (life.has(Social.Trait.SHY)) return shy;
        if (life.has(Social.Trait.CHEERFUL)) return cheerful;
        return plain;
    }

    static String pick(RandomSource r, String... options) {
        return FolkTalk.pick(r, options);
    }

    // ------------------------------------------------------------------ hearing it

    /** A typed line about work or an offer: what the player meant, or null to leave it to the usual understanding. */
    @Nullable
    public static TalkTopic heard(VillageFolkEntity f, Player p, String text) {
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", " ") + " ";
        boolean offer = QuestBook.offerOf(f.getUUID()) != null;
        Object[] step = QuestRun.stepWith(f, p);
        if (step != null && ((Step) step[1]).type == StepType.CHOOSE && choiceKey((Step) step[1], text) != null) return TalkTopic.QUEST_CHOICE;
        if (offer && asked(f, p)) {
            if (has(t, " yes ", " i'll do it ", " ill do it ", " i will ", " accept ", " count me in ", " deal ", " all right ", " alright ",
                    " okay ", " ok ", " sure ", " gladly ", " of course ", " leave it to me ")) return TalkTopic.QUEST_YES;
            if (has(t, " not now ", " not just now ", " no thanks ", " decline ", " maybe later ", " another time ", " can't ", " cannot ", " no ",
                    " not today ")) {
                return TalkTopic.QUEST_NO;
            }
        }
        if ((offer || step != null) && has(t, " any work ", " any jobs ", " a job for me ", " work for me ", " need a hand ", " any quests ",
                " jobs going ", " anything i can do ", " can i help ", " need help ", " what's wrong ", " whats wrong ", " i'm here about ",
                " im here about ")) return TalkTopic.JOBS;
        if (step != null && has(t, " here you are ", " i've brought ", " ive brought ", " brought it ", " here it is ", " i have it ")) return TalkTopic.JOBS;
        return null;
    }

    private static boolean has(String t, String... words) {
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    /** A choice's key from what was said: the key itself, or words of its label. */
    @Nullable
    static String choiceKey(Step s, String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        for (String[] o : s.options) if (o[0].equalsIgnoreCase(t)) return o[0];
        for (String[] o : s.options) {
            String label = o[1].toLowerCase(Locale.ROOT);
            if (t.length() >= 4 && (label.contains(t) || t.contains(label))) return o[0];
        }
        return null;
    }

    // ------------------------------------------------------------------ answering

    /**
     * [FolkTalk.answer] Anything about quests: work asked for, an offer taken or turned down, a choice, or a step
     * waiting on this folk done by talking to it. Null when it is none of those (the conversation goes on as ever).
     */
    @Nullable
    public static String answer(VillageFolkEntity f, Player p, TalkTopic topic, String text) {
        if (!(f.level() instanceof ServerLevel level) || f.isShowcase()) return null;
        String said;
        switch (topic) {
            case QUEST_YES -> said = QuestRun.accept(level, f, p);
            case QUEST_NO -> said = QuestRun.decline(f, p);
            case QUEST_CHOICE -> {
                Object[] step = QuestRun.stepWith(f, p);
                String key = step == null ? null : choiceKey((Step) step[1], text);
                said = step == null ? "Choose what? There's nothing to choose." : QuestRun.talk(level, f, p, key);
                if (said == null) said = "What will it be?";
            }
            case JOBS -> {
                said = QuestRun.talk(level, f, p, null);
                if (said == null) said = jobs(level, f, p);
            }
            case OPEN, DELIVER -> said = QuestRun.talk(level, f, p, null);
            case HELP -> {
                said = QuestRun.talk(level, f, p, null);
                if (said == null && QuestBook.offerOf(f.getUUID()) != null) said = jobs(level, f, p);
            }
            default -> said = null;
        }
        if (said != null) QuestRun.sendChoices(f, p);
        return said;
    }

    /** "Any work for me?": the folk's offer in its own words, or how its quest is getting on, or who else has work. */
    static String jobs(ServerLevel level, VillageFolkEntity f, Player p) {
        ASKED.put(f.getUUID() + "/" + p.getUUID(), level.getGameTime());
        Quest offer = QuestBook.offerOf(f.getUUID());
        if (offer != null) {
            return offer.offer + " " + rewardLine(f, offer) + " " + voice(f, "Will you?", "Well? Yes or no.", "Would you… could you?",
                "Say you will!");
        }
        for (Quest q : QuestBook.open(f.ownerId() == null ? new UUID(0, 0) : f.ownerId())) {
            if (!f.getUUID().equals(q.giver) || q.state != State.ACTIVE) continue;
            if (p.getUUID().equals(q.player)) {
                Step s = q.current();
                return voice(f, "How are you getting on? ", "Well? ", "Um — how is it going? ", "How's it going? ")
                    + (s == null ? "" : "You were to " + QuestRun.lower(s.text) + ".");
            }
            return voice(f, q.playerName + "'s helping me with that already.", q.playerName + "'s got it in hand.",
                "Oh — " + q.playerName + " is already helping me. Thank you, though.", q.playerName + " beat you to it! Thank you all the same!");
        }
        // Nothing of its own: who it knows that has.
        UUID village = f.ownerId();
        if (village == null) return "Work? I've no village to give it out for.";
        VillageFolkEntity near = null;
        double best = Double.MAX_VALUE;
        for (Quest q : QuestBook.offers(village)) {
            VillageFolkEntity g = Civics.find(level, q.giver);
            if (g == null || g == f) continue;
            double d = g.distanceToSqr(f);
            if (d < best) { best = d; near = g; }
        }
        String board = Quests.postings(village).isEmpty() ? "" : " And there's the quest board on the hall.";
        if (near != null) {
            return voice(f, "Not from me — but " + near.displayNameCap() + " was asking after a hand. " + where(f, near) + board,
                "Not from me. Try " + near.displayNameCap() + ". " + where(f, near) + board,
                "I — no, not me. But " + near.displayNameCap() + " was asking. " + where(f, near) + board,
                "Not me, but " + near.displayNameCap() + " was after some help! " + where(f, near) + board);
        }
        return voice(f, "Nothing just now, thank you." + board, "No." + board, "Oh — no, nothing. Thank you for asking." + board,
            "Nothing today — but thanks for asking!" + board);
    }

    private static String where(VillageFolkEntity from, VillageFolkEntity to) {
        int dx = to.getBlockX() - from.getBlockX(), dz = to.getBlockZ() - from.getBlockZ();
        int far = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
        return far < 6 ? "Right there." : "About " + Math.max(5, far / 5 * 5) + " blocks " + QuestRun.way(dx, dz) + " of here.";
    }

    /** What the giver can give for it, in its words. */
    static String rewardLine(VillageFolkEntity f, Quest q) {
        String extra = q.flag("reward");
        if (q.coins <= 0) return extra.isEmpty() ? voice(f, "I can't pay much — only my thanks.", "There's no money in it.",
            "I've n-nothing to pay you with, I'm afraid.", "I can't pay, but I'll owe you one!") : "I'd give you " + extra + ".";
        String coin = q.coins + (q.coins == 1 ? " coin" : " coins");
        String from = q.payer.equals("purse") ? voice(f, "I'll give you " + coin + " for it, out of my own savings.",
            coin + ". Out of my own pocket, mind.", "I could give you " + coin + "… it's what I have saved.",
            "I'll give you " + coin + " — every penny I've saved!")
            : "The treasury will pay " + coin + ".";
        if (!q.goods.isEmpty()) from += " And " + q.rewardWords().replaceFirst("^[^,]*, ", "") + ".";
        if (!extra.isEmpty()) from += " And " + extra + ".";
        return from;
    }

    /** The giver's thanks when a plain quest is taken on, with the first thing to do. */
    static String taken(VillageFolkEntity giver, Quest q) {
        Step s = q.current();
        String first = s == null ? "" : " " + capFirst(s.text) + ".";
        return voice(giver, "Thank you. I mean it." + first, "Good. Don't let me down." + first, "Oh — thank you, thank you." + first,
            "Wonderful! You're a star!" + first);
    }

    /** The pay, as the giver hands it over. */
    static String paidLine(Quest q, String paid) {
        return q.payer.equals("purse") ? "Here: " + paid + ". You've earned it." : "From the treasury: " + paid + ", with the town's thanks.";
    }

    static String capFirst(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ after the usual answer

    /**
     * [FolkTalk.answer, at the end] The buttons for the talk screen sent; and a folk with work to give, greeted, lets
     * it be known (once a day), so a player who did not see its "!" hears of it.
     */
    public static String hint(VillageFolkEntity f, Player p, TalkTopic topic, String said) {
        QuestRun.sendChoices(f, p);
        if (topic != TalkTopic.OPEN || said == null) return said;
        Quest offer = QuestBook.offerOf(f.getUUID());
        if (offer == null) return said;
        long day = f.level().getDayTime() / 24000L;
        String k = f.getUUID() + "/" + p.getUUID();
        if (HINTED.getOrDefault(k, -1L) == day) return said;
        HINTED.put(k, day);
        return said + " " + voice(f, "Mind — I could use a hand with something, if you've a moment.",
            "And while you're here: I've a job wants doing.", "Um… I was w-wondering if you might help me with something.",
            "Oh! And I've a favour to ask, if you're free!");
    }

    /** [FolkTalk.send] The line over the conversation: the step this folk waits on, or the work it has to give. */
    public static String banner(VillageFolkEntity f, Player p) {
        Object[] found = QuestRun.stepWith(f, p);
        if (found != null) return "Quest: " + ((Quest) found[0]).title + " — " + ((Step) found[1]).text;
        Quest offer = QuestBook.offerOf(f.getUUID());
        if (offer != null) return "Has work for you: " + offer.title + " (ask \"Any work for me?\")";
        return "";
    }

    // ------------------------------------------------------------------ the talk of the town

    /** [FolkTalk.gossipFor] What the town is saying about players' deeds and the story going on. */
    public static List<String> gossip(VillageFolkEntity f, Player p) {
        List<String> out = new ArrayList<>();
        UUID village = f.ownerId();
        if (village == null) return out;
        long day = f.level().getDayTime() / 24000L;
        for (Quest q : QuestBook.deeds(village, day - 6)) {
            if (f.getUUID().equals(q.giver)) continue;
            String g = q.flag("gossip");
            String who = p.getUUID().equals(q.player) ? "you" : q.playerName;
            if (!g.isEmpty()) out.add(g.replace("{who}", who));
            else if (q.kind != Kind.BOARD) out.add("Did you hear? " + capFirst(who) + " " + (who.equals("you") ? "did" : "did") + " "
                + q.giverName + " a good turn: " + QuestRun.lower(q.title) + ".");
        }
        Quest story = QuestBook.story(village);
        if (story != null) {
            String rumour = story.flag("rumour");
            if (!rumour.isEmpty() && !castOf(story, f)) out.add(rumour);
        }
        return out;
    }

    private static boolean castOf(Quest q, VillageFolkEntity f) {
        return QuestRun.castOf(q).contains(f.getUUID());
    }

    /**
     * Every five seconds for each player: the folk near it who it helped thank it (once a day each), the ones it let
     * down remind it, and a child it brought home waves.
     */
    static void reactions(ServerLevel level, ServerPlayer p) {
        long day = QuestRun.day(level);
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, p.getBoundingBox().inflate(6.0), VillageFolkEntity::isAlive)) {
            if (f.isSleeping() || f.isShowcase()) continue;
            String k = f.getUUID() + "/" + p.getUUID();
            if (SEEN.getOrDefault(k, -1L) == day) continue;
            String line = reaction(f, p, day);
            if (line == null) continue;
            SEEN.put(k, day);
            f.getLookControl().setLookAt(p, 30.0F, 30.0F);
            FolkTalk.speak(f, line);
        }
        if (SEEN.size() > 4096) SEEN.clear();
    }

    @Nullable
    static String reaction(VillageFolkEntity f, Player p, long day) {
        String name = p.getName().getString();
        for (Quest q : QuestBook.of(p.getUUID())) {
            if (q.finished < day - 10) continue;
            String thanks = q.flag("thanks." + f.getUUID());
            if (q.state == State.DONE && !thanks.isEmpty()) return thanks.replace("{who}", name);
            if (!f.getUUID().equals(q.giver)) continue;
            if (q.state == State.DONE) {
                return voice(f, "Thank you again, " + name + ". I've not forgotten.", "Hmph. " + name + ". Still grateful, you know.",
                    "Oh — " + name + "! Thank you, again, for… for everything.", name + "! My hero!");
            }
            if (q.state == State.ABANDONED || q.state == State.FAILED) {
                return voice(f, "You said you'd help me, " + name + ".", "Some help you were.", "I… I waited for you, you know.",
                    "Oh — it's you. I did wait, you know.");
            }
        }
        return null;
    }
}
