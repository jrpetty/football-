package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.net.FolkReplyPayload;
import com.jrpetty.mcassistant.net.FolkSpeechPayload;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Talking with a folk.
 *
 * <p>Everything a folk says is made here, from who it is: its two traits colour how
 * it speaks (a grump is short with you, a shy one stumbles, a cheerful one cannot
 * help an exclamation mark, a curious one asks you things back), and what it says
 * comes from its own life — its mood and the reasons for it, its trade and what it
 * is doing, its partner, children and friends, its hobby, its quirk, its hopes,
 * what it remembers, the village's news, and what it thinks of you.
 *
 * <p>It is a person, not a servant: it can refuse. It will not follow somebody it
 * hardly knows, or leave its post, or go wandering when it is miserable, and it does
 * not forget being hit. What it thinks of you grows when you talk with it, bring it
 * things it likes and look after its village, and sours when you do it harm.
 *
 * <p>Folk never speak in the chat. A conversation happens in its own screen, and
 * whatever a folk says out loud appears in a bubble over its head for whoever is
 * near enough to hear.
 */
public final class FolkTalk {

    private FolkTalk() {}

    // ------------------------------------------------------------------ entry

    public static void handle(VillageFolkEntity f, ServerPlayer p, TalkTopic topic, String text) {
        TalkTopic meant = topic == TalkTopic.SAY ? understand(text) : topic;
        String said = answer(f, p, topic, text);
        if (meant == TalkTopic.BYE) {
            speak(f, said);
            send(f, p, false, said, "");
            return;
        }
        speak(f, firstSentence(said));
        send(f, p, meant == TalkTopic.OPEN && topic != TalkTopic.SAY, said, topic == TalkTopic.SAY ? text : topic.line);
    }

    /**
     * What the folk says to that — and whatever comes of it (a gift taken, a favour
     * given, a walk agreed to). Nothing is sent anywhere: {@link #handle} does that.
     */
    public static String answer(VillageFolkEntity f, net.minecraft.world.entity.player.Player p, TalkTopic topic, String text) {
        f.ensurePersona();
        Persona me = f.persona();
        long day = f.level().getDayTime() / 24000L;
        Persona.Opinion op = me.opinionOf(p.getUUID(), p.getName().getString());
        if (topic == TalkTopic.SAY) topic = understand(text);
        boolean firstMeeting = op.lastTalkDay < 0 && op.lastGiftDay < 0;
        String heard = op.heardFrom;
        me.met(p.getUUID());
        // A word a day goes a long way.
        if (op.lastTalkDay != day && topic != TalkTopic.BYE) {
            op.lastTalkDay = day;
            Social.Life life = f.life();
            int warm = life.has(Social.Trait.SOCIABLE) ? 3 : life.has(Social.Trait.SHY) || life.has(Social.Trait.GRUMPY) ? 1 : 2;
            if (op.affinity > -40) me.feelFor(p.getUUID(), p.getName().getString(), warm);
        }
        if (topic == TalkTopic.BYE) {
            f.stopTalking();
            return bye(f, p);
        }
        f.startTalking(p);
        // A village that has turned against you closes ranks.
        UUID village = f.ownerId();
        if (village != null && me.affinity(p.getUUID()) < 0 && topic != TalkTopic.GIFT
                && Standing.of(village, p.getUUID(), f.level().getGameTime()).title() == Standing.Title.OUTCAST) {
            return manner(f, pick(f.getRandom(), "We don't want you here.", "Nobody here will talk to you. Go away.",
                "After what you've done? Leave."));
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (!text.isEmpty() && (lower.contains("sorry") || lower.contains("apolog"))) {
            return manner(f, apology(f, p, op, day));
        }
        if (!text.isEmpty()) {
            VillageFolkEntity other = mentioned(f, lower);
            if (other != null) return manner(f, opinionOf(f, other));
        }
        if (f.isBaby() && topic != TalkTopic.GIFT) return child(f, p, topic, op);
        String kept = topic == TalkTopic.OPEN ? Welcome.handOver(f, p) : "";
        if (!kept.isEmpty()) return manner(f, kept.trim());
        String said = switch (topic) {
            case OPEN -> greet(f, p, op, firstMeeting, heard);
            case HOW -> howAreYou(f);
            case DOING -> doing(f, p);
            case ABOUT -> aboutMe(f);
            case PEOPLE -> people(f);
            case VILLAGE -> news(f);
            case DREAMS -> dreams(f);
            case GIFT -> gift(f, p);
            case FAVOUR -> favour(f, p);
            case FOLLOW -> follow(f, p);
            case STAY -> stay(f, p);
            case HOBBY -> hobby(f);
            case JOKE -> joke(f);
            case HELP -> Errands.offer(f, p);
            case DELIVER -> Errands.deliver(f, p);
            case MEMORY -> memories(f);
            case REPUTE -> repute(f, p);
            case CHRONICLE -> chronicle(f, p, op, day);
            case CENSUS -> census(f, p);
            default -> puzzled(f);
        };
        // Somebody who can't stand you says as little as it can.
        if (me.affinity(p.getUUID()) <= -50 && topic != TalkTopic.GIFT && topic != TalkTopic.STAY) {
            said = pick(f.getRandom(), "I've nothing to say to you.", "Leave me be.",
                "Go away. I haven't forgotten.", "Hmph.");
        }
        return manner(f, said);
    }

    /** Right-click: open the talk screen with a greeting. */
    public static void open(VillageFolkEntity f, ServerPlayer p) {
        handle(f, p, TalkTopic.OPEN, "");
    }

    private static void send(VillageFolkEntity f, ServerPlayer p, boolean open, String said, String asked) {
        Persona me = f.persona();
        int aff = me.affinity(p.getUUID());
        String about = (f.isElder() ? "Elder · " : "") + (f.isBaby() ? "Child" : f.stationTask().title) + " · "
            + f.life().traitsLabel().toLowerCase(Locale.ROOT) + " · loves " + me.hobby().doing;
        String where = "";
        if (f.ownerId() != null) {
            Standing.View v = Standing.of(f.ownerId(), p.getUUID(), f.level().getGameTime());
            where = Villages.name(f.ownerId()) + " · " + Villages.ageOf(f.ownerId()).label + " · you: "
                + Standing.titleIn(f.ownerId(), v.title());
        }
        String errand = Errands.live(f, p.getUUID()) ? "Asked you to " + Errands.describe(me) : "";
        PacketDistributor.sendToPlayer(p, new FolkReplyPayload(f.getId(), open, f.displayNameCap(), about, said,
            me.mood(), Persona.moodWord(me.mood()), aff, Persona.standing(aff), f.isFollowing(p), asked,
            where, errand, Errands.canDeliver(f, p)));
    }

    /** Words said out loud: a bubble over the folk's head for whoever is near. */
    public static void speak(VillageFolkEntity f, String text) {
        if (text == null || text.isBlank() || !(f.level() instanceof ServerLevel)) return;
        if (f.level().getNearestPlayer(f, 24.0) == null) return;        // nobody to hear it
        String said = text.length() > 170 ? text.substring(0, 167) + "…" : text;
        int ticks = Math.min(200, 60 + said.length() * 2);
        PacketDistributor.sendToPlayersTrackingEntity(f, new FolkSpeechPayload(f.getId(), said, ticks));
    }

    // ------------------------------------------------------------------ manner

    static String pick(RandomSource r, String... options) {
        return options[r.nextInt(options.length)];
    }

    /** The folk's own way of putting things. */
    static String manner(VillageFolkEntity f, String said) {
        Social.Life life = f.life();
        RandomSource r = f.getRandom();
        if (said.isEmpty()) return said;
        if (life.has(Social.Trait.GRUMPY) && r.nextInt(3) == 0) {
            said = pick(r, "Hmph. ", "Well. ", "If you must know: ") + said;
        } else if (life.has(Social.Trait.SHY) && r.nextInt(3) == 0) {
            said = pick(r, "Oh — um. ", "I… well. ", "Oh! Sorry. ") + said;
        } else if (life.has(Social.Trait.CHEERFUL) && r.nextInt(3) == 0) {
            said = pick(r, "Ha! ", "Oh, wonderful. ", "Well now! ") + said;
        }
        if (life.has(Social.Trait.CHEERFUL) && said.endsWith(".") && r.nextBoolean()) {
            said = said.substring(0, said.length() - 1) + "!";
        }
        if (life.has(Social.Trait.CURIOUS) && r.nextInt(4) == 0) {
            said += " " + pick(r, "Where do you come from, anyway?", "Have you been far? Seen the ocean?",
                "Do you ever wonder what's under the bedrock?", "What's it like, out there?",
                "Have you ever seen a Nether fortress?");
        }
        if (life.has(Social.Trait.GENEROUS) && r.nextInt(5) == 0) {
            said += " " + pick(r, "If you ever need anything, just ask.", "Have you eaten? There's bread in the stores.");
        }
        return said;
    }

    private static String firstSentence(String s) {
        int cut = -1;
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && s.charAt(i + 1) == ' ' && i > 12) { cut = i + 1; break; }
        }
        return cut > 0 ? s.substring(0, cut) : s;
    }

    private static String timeOfDay(VillageFolkEntity f) {
        long t = f.level().getDayTime() % 24000L;
        if (t < 3000) return "Morning";
        if (t < 9000) return "Good day";
        if (t < 13000) return "Evening";
        return "You're out late";
    }

    // ------------------------------------------------------------------ topics

    static String greet(VillageFolkEntity f, net.minecraft.world.entity.player.Player p, Persona.Opinion op, boolean first,
                        String heard) {
        RandomSource r = f.getRandom();
        String you = p.getName().getString();
        Persona me = f.persona();
        int aff = op.affinity;
        Social.Life life = f.life();
        if (f.isSleeping()) return "Zzz… mm? Oh — it's the middle of the night. Talk in the morning.";
        // Never spoken, but heard about you: your name has gone ahead of you.
        if (first && !heard.isEmpty()) {
            if (aff < 0) return pick(r, "So you're " + you + ". " + heard + " told me about you.",
                "You're " + you + "? I've heard about you from " + heard + ". Hm.",
                "Ah. " + you + ". " + heard + " warned me about you.");
            return pick(r, "So you're " + you + "! " + heard + "'s told me all about you. I'm " + f.displayNameCap() + ".",
                "You must be " + you + " — " + heard + " speaks well of you. I'm " + f.displayNameCap() + ".",
                you + "! At last. " + heard + " never stops talking about you. I'm " + f.displayNameCap() + ".");
        }
        if (aff <= -50) return pick(r, "Oh. You.", "What do you want?", "Stay back.");
        if (aff <= -15) return pick(r, "Hm. " + you + ".", "Oh. It's you.", "What is it?");
        if (first) {
            String job = f.stationTask() == AssistantEntity.StationTask.NONE ? "new here"
                : "the village " + f.stationTask().title.toLowerCase(Locale.ROOT);
            if (f.ownerId() != null) job += ", here in " + Villages.name(f.ownerId());
            if (life.has(Social.Trait.GRUMPY)) return "Name's " + f.displayNameCap() + ". I'm " + job + ". What do you want?";
            if (life.has(Social.Trait.SHY)) return "Oh — hello. I'm " + f.displayNameCap() + ". I'm " + job + ".";
            if (life.has(Social.Trait.SOCIABLE)) return "A new face! Welcome, welcome. I'm " + f.displayNameCap()
                + ", " + job + ". And you are " + you + "? Lovely.";
            return "Hello, stranger. I'm " + f.displayNameCap() + ", " + job + ".";
        }
        // What the two of you have done lately.
        long day = f.level().getDayTime() / 24000L;
        if (Errands.live(f, p.getUUID()) && r.nextBoolean()) {
            return pick(r, "Hello, " + you + ". ", "Ah, " + you + "! ") + "Any luck? I asked you to "
                + Errands.describe(me) + ".";
        }
        for (Persona.Memory m : me.memories()) {
            if (day - m.day() > 2 || !m.text().startsWith(you + " ")) continue;
            String did = m.text().substring(you.length() + 1);
            if (m.weight() < 0) return pick(r, "Oh. You. I've not forgotten — you " + did.replace(" me", " me") + ".",
                "Keep your distance. You " + did + ".");
            if (r.nextInt(3) == 0) break;
            return pick(r, "Hello again, " + you + "! ", you + "! ") + "Thank you again — you " + did + ".";
        }
        if (aff >= 55) return pick(r, you + "! Good to see you.", "There you are, " + you + "!",
            "Ah, my friend " + you + ".", you + "! I was hoping you'd come by.");
        return pick(r, timeOfDay(f) + ", " + you + ".", "Hello again, " + you + ".", "Oh, hello " + you + ".",
            timeOfDay(f) + ". What can I do for you?");
    }

    static String howAreYou(VillageFolkEntity f) {
        Persona me = f.persona();
        RandomSource r = f.getRandom();
        int mood = me.mood();
        String head;
        if (mood >= 85) head = pick(r, "Never better.", "Wonderful, thank you.", "I could sing.");
        else if (mood >= 70) head = pick(r, "Grand, thank you.", "Very well.", "Happy, as it happens.");
        else if (mood >= 55) head = pick(r, "Can't complain.", "Well enough.", "Not bad at all.");
        else if (mood >= 40) head = pick(r, "So-so.", "Middling.", "I've had better days.");
        else if (mood >= 25) head = pick(r, "Fed up, if I'm honest.", "Not great.", "Tired of it all.");
        else head = pick(r, "Miserable.", "Awful. Thanks for asking.", "Don't ask.");
        StringBuilder sb = new StringBuilder(head);
        for (String why : me.moodWhy()) {
            String line = reason(f, why);
            if (!line.isEmpty()) sb.append(' ').append(line);
        }
        return sb.toString();
    }

    /** A reason for a mood, as the folk would put it. */
    static String reason(VillageFolkEntity f, String why) {
        Persona me = f.persona();
        RandomSource r = f.getRandom();
        return switch (why) {
            case "slept" -> pick(r, "I slept in my own bed last night.", "A good night's sleep does wonders.");
            case "rough" -> pick(r, "I didn't get a proper bed last night.", "Slept rough. My back knows it.");
            case "hungry" -> pick(r, "I'm hungry, and that's the truth.", "My stomach's been growling all day.");
            case "fed" -> "There's food in my pack.";
            case "friends" -> pick(r, "I've good friends here.", "The people here are good to me.");
            case "lonely" -> pick(r, "I don't really have anyone here.", "It's a lonely sort of life, some days.");
            case "partner" -> f.life().partnerName().isEmpty() ? "" : f.life().partnerName() + " and I are doing well.";
            case "gift" -> me.giftFrom.isEmpty() ? "Somebody brought me a present." : me.giftFrom + " brought me a present!";
            case "hurt" -> "Somebody hit me, and I haven't forgotten it.";
            case "rain" -> pick(r, "This rain gets into my bones.", "Wet again. I hate the rain.");
            case "rainlove" -> "I like the rain, myself.";
            case "stuck" -> {
                List<String> missing = f.missingEssentials();
                yield missing.isEmpty() ? "I can't get on with my work." : "I can't work — I've no " + missing.get(0) + ".";
            }
            case "hobby" -> "I had time for some " + me.hobby().word + " lately.";
            case "aged" -> "The village came of age — did you hear?";
            case "short" -> "The village is short of food, though.";
            case "dream" -> "And I did it, you know — " + me.ambition().done + ".";
            case "busy" -> "Busy, mind. Always busy.";
            case "feast" -> pick(r, "What a night that was at the heart of the village!", "I'm still full from the feast.");
            default -> "";
        };
    }

    static String doing(VillageFolkEntity f, net.minecraft.world.entity.player.Player p) {
        RandomSource r = f.getRandom();
        Persona me = f.persona();
        if (f.isFollowing(p)) return pick(r, "Walking with you, of course!", "Following you. Where are we off to?");
        if (f.isSleeping()) return "Sleeping, until you woke me.";
        String hobby = f.hobbyNow();
        if (hobby != null) return pick(r, "My own time now — ", "Day's work's done, so ") + hobby + ".";
        if (f.offWorkNow() && f.onShift()) return pick(r, "Taking a breather. ", "A short break. ") + "Back to work in a bit.";
        if (!f.onShift()) return pick(r, "The day's done. ", "Work's over for today. ") + "I'll be off to bed soon.";
        String place = f.patchName().isEmpty() ? "" : " at " + f.patchName();
        String work = switch (f.stationTask()) {
            case FARM -> pick(r, "Tending the fields" + place + ".", "Farming" + place + " — bread doesn't grow on trees.");
            case WOOD -> "Felling timber" + place + ".";
            case MINE -> {
                WorkZone z = f.workZone();
                String depth = z == null ? "" : " — down at height " + z.depth();
                yield "Digging" + place + depth + ", after " + (Villages.ageOf(f.ownerId()).ordinal() >= Villages.Age.DIAMOND.ordinal()
                    ? "diamonds" : "iron") + ".";
            }
            case RANCH -> "Minding the animals" + place + ".";
            case GUARD -> f.level().isNight() ? "The night watch. Quiet so far." : "Keeping watch. Nothing gets past me.";
            case SMELT -> "Running the furnaces" + place + ".";
            case FISH -> "Fishing for the village" + place + ".";
            case STORE -> "Keeping the stores in order. You wouldn't believe the mess.";
            case HAUL -> "Carrying for everyone. My back knows all about it.";
            case NONE -> "Looking for a trade. The village will tell me what it needs.";
        };
        if (!f.missingEssentials().isEmpty()) work += " Or I would be, if I had " + f.missingEssentials().get(0) + ".";
        if (f.life().has(Social.Trait.HARDWORKING) && r.nextBoolean()) work += " Can't stop long.";
        if (f.life().has(Social.Trait.EASYGOING) && r.nextBoolean()) work += " No rush, though.";
        return work;
    }

    static String aboutMe(VillageFolkEntity f) {
        Persona me = f.persona();
        Social.Life life = f.life();
        long day = f.level().getDayTime() / 24000L;
        long days = me.since() < 0 ? 0 : Math.max(0, day - me.since());
        StringBuilder sb = new StringBuilder("I'm ").append(f.displayNameCap()).append(". ");
        if (!me.origin().isEmpty()) sb.append("I'm ").append(me.origin()).append(", ");
        else sb.append("I've been ");
        sb.append(days <= 1 ? "and I'm new to all this. " : "and I've been here " + days + " days now. ");
        if (life.rolled()) sb.append("People say I'm ").append(life.traitsLabel().toLowerCase(Locale.ROOT)).append(". ");
        sb.append("I ").append(me.quirkOfMine()).append(". ");
        sb.append("When I've time to myself it's ").append(me.hobby().doing).append(", ");
        sb.append("and I'd do anything for ").append(foodWords(me.food())).append('.');
        return sb.toString();
    }

    static String hobby(VillageFolkEntity f) {
        Persona me = f.persona();
        RandomSource r = f.getRandom();
        String base = "For fun? " + cap(me.hobby().doing) + ". Nothing beats " + me.hobby().love + ".";
        return switch (me.hobby()) {
            case GARDENING -> base + (me.flowersPlanted() > 0 ? " I've planted " + me.flowersPlanted()
                + " flowers round the village so far." : " I mean to plant flowers all round the village.");
            case MUSIC -> base + " Come to the well of an evening and you'll hear me.";
            case STARGAZING -> base + pick(r, " Clear nights are the best nights.", " I've named half of them.");
            case FISHING -> base + " Bring me a fish sometime and we'll be friends for life.";
            case CARDS -> base + " Fancy a hand? I warn you, I cheat.";
            case READING -> base + " A book makes a lovely present, hint hint.";
            case WHITTLING -> base + " Give me a stick and an evening and I'll make you something.";
            case WALKING -> base + " I know every path round here.";
        };
    }

    static String people(VillageFolkEntity f) {
        Social.Life life = f.life();
        RandomSource r = f.getRandom();
        StringBuilder sb = new StringBuilder();
        if (life.partner() != null) {
            sb.append(life.partnerName()).append(" and I are together");
            sb.append(life.children() > 0 ? ", and we've " + life.children() + (life.children() == 1 ? " child. " : " children. ") : ". ");
        } else if (life.children() > 0) {
            sb.append("I've ").append(life.children()).append(life.children() == 1 ? " child. " : " children. ");
        } else {
            sb.append(pick(r, "No partner yet. ", "Nobody special, not yet. "));
        }
        if (!life.parents().isEmpty()) sb.append("My parents are ").append(life.parents()).append(". ");
        List<Social.Bond> friends = life.friends();
        if (friends.isEmpty()) sb.append(pick(r, "I've not made many friends yet.", "Friends? Working on it."));
        else if (friends.size() == 1) sb.append("My best friend is ").append(friends.get(0).name).append('.');
        else sb.append("My closest friends are ").append(friends.get(0).name).append(" and ").append(friends.get(1).name)
            .append(friends.size() > 2 ? ", and " + (friends.size() - 2) + " more." : ".");
        List<Social.Bond> rivals = life.rivals();
        if (!rivals.isEmpty()) {
            sb.append(' ').append(life.has(Social.Trait.GRUMPY)
                ? "And don't get me started on " + rivals.get(0).name + "."
                : rivals.get(0).name + " and I don't see eye to eye.");
        }
        return sb.toString();
    }

    static String news(VillageFolkEntity f) {
        UUID village = f.ownerId();
        RandomSource r = f.getRandom();
        if (village == null) return "News? I don't even have a village yet.";
        int folk = Villages.headcount(village);
        StringBuilder sb = new StringBuilder(Villages.name(village)).append("'s in ").append(Villages.ageOf(village).label)
            .append(", ").append(folk <= 1 ? "and it's just me so far. " : folk + " of us. ");
        if (f.level() instanceof ServerLevel server) {
            List<Villages.Need> needs = Villages.needs(server, village);
            if (!needs.isEmpty()) sb.append("What we need now is ").append(needs.get(0).what()).append(". ");
        }
        List<Villages.News> all = Villages.news(village);
        List<Villages.News> fresh = new ArrayList<>();
        long day = f.level().getDayTime() / 24000L;
        for (Villages.News n : all) if (day - n.day() <= 6 && !n.text().startsWith(f.displayNameCap() + " ")) fresh.add(n);
        String elder = Villages.elderName(village);
        if (!elder.isEmpty() && !f.isElder() && r.nextInt(3) == 0) sb.append(elder).append(" is our elder. ");
        if (!fresh.isEmpty()) {
            Villages.News n = fresh.get(r.nextInt(Math.min(3, fresh.size())));
            sb.append(pick(r, "Did you hear? ", "Have you heard? ", "Big news: ")).append(cap(n.text())).append('.');
            if (f.life().has(Social.Trait.SOCIABLE) && fresh.size() > 1) {
                Villages.News m = fresh.get((fresh.indexOf(n) + 1) % fresh.size());
                sb.append(" And ").append(m.text()).append(", too.");
            }
        } else {
            sb.append(pick(r, "Quiet, otherwise.", "Nothing much else to tell."));
        }
        return sb.toString();
    }

    static String dreams(VillageFolkEntity f) {
        Persona me = f.persona();
        RandomSource r = f.getRandom();
        if (me.ambitionMet()) return "I've done what I always wanted — " + me.ambition().done + ". "
            + pick(r, "Now I just want to enjoy it.", "Now? Now I'll find a new dream.");
        String progress = switch (me.ambition()) {
            case MASTER -> " I'm level " + f.veteranLevel() + " at my trade. Ten will do.";
            case FAMILY -> " " + (f.life().children() == 0 ? "No children yet." : f.life().children() + " so far.");
            case FRIENDS -> f.life().friends().isEmpty() ? " No real friends yet, but I'm working on it."
                : " I've " + f.life().friends().size() + " good " + (f.life().friends().size() == 1 ? "friend" : "friends") + " so far.";
            case DIAMOND -> " Not found one yet. They say they're deep down by the lava.";
            case NETHER, GREAT_WORK -> " We're in " + (f.ownerId() == null ? "no age at all" : Villages.ageOf(f.ownerId()).label) + " now.";
            case GARDEN -> " " + me.flowersPlanted() + " flowers planted so far.";
            case WELL_FED -> " Full stores, that's all I ask.";
        };
        return pick(r, "Me? I want ", "One day I'd like ", "What I really want is ") + me.ambition().hope + "." + progress;
    }

    /** A child talks like a child. */
    static String child(VillageFolkEntity f, net.minecraft.world.entity.player.Player p, TalkTopic topic, Persona.Opinion op) {
        RandomSource r = f.getRandom();
        long day = f.level().getDayTime() / 24000L;
        long age = f.bornDay() == VillageFolkEntity.UNKNOWN ? 0 : Math.max(0, day - f.bornDay());
        String parents = f.life().parents();
        if (op.affinity < 5 && topic == TalkTopic.OPEN) {
            return pick(r, "Mum says I'm not to talk to strangers.", "…Who are you?", "Hello! Are you a giant?");
        }
        String[] trades = {"miner", "farmer", "guard", "fisher", "builder", "smelter"};
        String dream = trades[Math.floorMod(f.getUUID().hashCode(), trades.length)];
        return switch (topic) {
            case HOW -> pick(r, "I'm good! I found a really shiny rock!", "Hungry. When's supper?",
                "Great! I'm winning at tag!");
            case DOING -> pick(r, "Playing tag! You can't catch me!", "Following " + (parents.isEmpty() ? "the grown-ups" : parents.split(" and ")[0]) + " around.",
                "Exploring! Don't tell anyone.");
            case ABOUT -> "I'm " + f.displayNameCap() + "! I'm " + (age <= 0 ? "brand new" : age + (age == 1 ? " day" : " days") + " old")
                + "! When I grow up I'm going to be a " + dream + "!";
            case PEOPLE -> parents.isEmpty() ? "I don't know who my mum and dad are." : "My mum and dad are " + parents + "! They're the best.";
            case JOKE -> pick(r, "Knock knock! …You're supposed to say who's there!", "What's brown and sticky? A stick! Hee hee!");
            case DREAMS -> "I want to be a " + dream + " like the big ones!";
            case BYE -> "Bye bye!";
            default -> pick(r, "Wanna see my rock?", "Hee hee!", "Are you a hero? You look like a hero.",
                "Mum says I have to be in bed when it's dark.", "Can you do a handstand?");
        };
    }

    static String memories(VillageFolkEntity f) {
        Persona me = f.persona();
        RandomSource r = f.getRandom();
        long day = f.level().getDayTime() / 24000L;
        StringBuilder sb = new StringBuilder();
        Persona.Memory fond = me.fondest();
        Persona.Memory last = me.latest();
        if (fond == null && last == null) return "Remember? I've not been here long enough to have much to remember.";
        if (fond != null) {
            sb.append(pick(r, "The best day I've had here? ", "I'll never forget the day ", "Fondest memory? "))
                .append(memoryWords(fond, day)).append(". ");
        }
        if (last != null && last != fond) {
            sb.append(last.weight() < 0 ? "And lately — " : "Just lately, ").append(memoryWords(last, day)).append('.');
        }
        // The old ones tell you how it all began.
        if (f.ownerId() != null && me.since() >= 0 && day - me.since() >= 20) {
            List<com.jrpetty.mcassistant.village.Chronicle.Entry> past = com.jrpetty.mcassistant.village.Chronicle.of(f.ownerId());
            if (past.size() > 2) {
                com.jrpetty.mcassistant.village.Chronicle.Entry early = past.get(1 + r.nextInt(Math.min(4, past.size() - 1)));
                sb.append(" I'm old enough to remember when ").append(early.text()).append(", on day ")
                    .append(early.day()).append(". Things were different then.");
            }
        }
        return sb.toString().trim();
    }

    private static String memoryWords(Persona.Memory m, long today) {
        long ago = today - m.day();
        String when = ago <= 0 ? "today" : ago == 1 ? "yesterday" : ago + " days ago";
        String text = m.text();
        if (text.startsWith("I ") || text.startsWith("I'")) text = "when " + text;
        return text + " — " + when;
    }

    static String repute(VillageFolkEntity f, net.minecraft.world.entity.player.Player p) {
        RandomSource r = f.getRandom();
        UUID village = f.ownerId();
        if (village == null) return "People? I don't know anybody well enough to say.";
        Standing.View v = Standing.of(village, p.getUUID(), f.level().getGameTime());
        String name = Villages.name(village);
        StringBuilder sb = new StringBuilder();
        if (f.isElder()) sb.append("As the elder of ").append(name).append(", I can tell you how the village sees you. ");
        switch (v.title()) {
            case HERO -> sb.append("You? You're the hero of ").append(name).append("! Everybody says so.");
            case HONOURED -> sb.append("You're an honoured guest in ").append(name).append(". People speak well of you.");
            case FRIEND -> sb.append("Folk here count you a friend of ").append(name).append('.');
            case VISITOR -> sb.append("Around ").append(name).append("? You're a visitor. People are making their minds up.");
            case STRANGER -> sb.append("Nobody here really knows you yet.");
            case UNWELCOME -> sb.append("Honestly? You're not welcome in ").append(name).append(". People talk.");
            case OUTCAST -> sb.append("You're an outcast here, and you know why.");
        }
        sb.append(' ').append(v.knownBy()).append(v.knownBy() == 1 ? " of us knows you" : " of us know you");
        if (!v.bestFriend().isEmpty()) sb.append(", and ").append(v.bestFriend()).append(" thinks the world of you");
        sb.append('.');
        if (!v.worstCritic().isEmpty()) sb.append(' ').append(v.worstCritic()).append(" doesn't trust you, mind.");
        if (v.title() == Standing.Title.FRIEND && r.nextBoolean()) {
            sb.append(" Keep helping and they'll make you an honoured guest.");
        }
        return sb.toString();
    }

    /** The village's history, as a book. */
    static String chronicle(VillageFolkEntity f, net.minecraft.world.entity.player.Player p, Persona.Opinion op, long day) {
        UUID village = f.ownerId();
        if (village == null) return "There's no history to read — there's no village yet.";
        if (op.lastBookDay == day) return "I gave you a copy already today!";
        ItemStack book = Chronicles.book(village, f.level().getDayTime() / 24000L);
        if (!p.getInventory().add(book)) p.drop(book, false);
        op.lastBookDay = day;
        return pick(f.getRandom(), "Here — our chronicle. Everything that's happened in " + Villages.name(village) + ".",
            "Of course! Here's a copy of " + Villages.name(village) + "'s history. Mind the pages.");
    }

    /** Who lives here: the village register, as a book. */
    static String census(VillageFolkEntity f, net.minecraft.world.entity.player.Player p) {
        UUID village = f.ownerId();
        if (village == null) return "Nobody lives here yet but me.";
        ItemStack book = Chronicles.register(village, f.level().getDayTime() / 24000L);
        if (!p.getInventory().add(book)) p.drop(book, false);
        return pick(f.getRandom(), "Everybody in " + Villages.name(village) + "? Here — the register. Every name, and how they're doing.",
            "Here's the register. " + Villages.headcount(village) + " of us, all written down.");
    }

    static String apology(VillageFolkEntity f, net.minecraft.world.entity.player.Player p, Persona.Opinion op, long day) {
        if (op.affinity >= 0 && day - f.persona().hurtDay > 3) return "Sorry? Whatever for?";
        if (op.lastSorryDay == day) return "You've said sorry. Now show me.";
        op.lastSorryDay = day;
        f.persona().feelFor(p.getUUID(), p.getName().getString(), f.life().has(Social.Trait.GRUMPY) ? 2 : 5);
        return pick(f.getRandom(), "…Apology accepted. Just don't do it again.", "Hmm. I'll think about it.",
            "Thank you for saying so.");
    }

    /** Somebody in the village it was asked about by name. */
    @Nullable
    static VillageFolkEntity mentioned(VillageFolkEntity f, String lower) {
        if (f.ownerId() == null) return null;
        for (AssistantEntity a : Villages.folkOf(f.ownerId())) {
            if (!(a instanceof VillageFolkEntity g) || g == f) continue;
            String name = g.displayNameCap().toLowerCase(Locale.ROOT);
            if (name.length() >= 3 && (" " + lower.replaceAll("[^a-z ]", " ") + " ").contains(" " + name + " ")) return g;
        }
        return null;
    }

    /** "What do you think of Bryn?" */
    static String opinionOf(VillageFolkEntity f, VillageFolkEntity g) {
        RandomSource r = f.getRandom();
        Social.Life life = f.life();
        String name = g.displayNameCap();
        if (g.getUUID().equals(life.partner())) {
            return pick(r, name + "? " + name + " is my partner. I'd be lost without them.",
                name + " and I are together. Best thing that ever happened to me.");
        }
        if (life.parents().contains(name)) return name + "'s my " + pick(r, "mother", "father", "parent") + ". I owe them everything.";
        if (g.life().parents().contains(f.displayNameCap())) return name + " is my child. Growing up so fast.";
        int warmth = life.affinity(g.getUUID());
        StringBuilder sb = new StringBuilder();
        if (warmth >= Social.CLOSE) sb.append(name).append("? One of my closest friends.");
        else if (warmth >= Social.FRIEND) sb.append(name).append("'s a good friend.");
        else if (warmth <= Social.RIVAL) sb.append(life.has(Social.Trait.GRUMPY) ? "Don't talk to me about " + name + "."
            : name + " and I don't get along, I'm afraid.");
        else if (warmth > 5) sb.append(name).append("? Nice enough. We say hello.");
        else sb.append(pick(r, "I don't really know " + name + ".", name + "? We've hardly spoken."));
        if (g.persona().rolled() && r.nextBoolean()) {
            sb.append(' ').append(pick(r, name + " " + g.persona().quirk() + ", you know.",
                "Spends every evening " + g.persona().hobby().doing + ".",
                "Works as the " + g.stationTask().title.toLowerCase(Locale.ROOT) + "."));
        }
        return sb.toString();
    }

    static String bye(VillageFolkEntity f, net.minecraft.world.entity.player.Player p) {
        RandomSource r = f.getRandom();
        int aff = f.persona().affinity(p.getUUID());
        if (aff <= -15) return pick(r, "Good.", "Finally.");
        if (f.life().has(Social.Trait.GRUMPY)) return pick(r, "Right. Back to it.", "Mm. Bye.");
        if (aff >= 55) return pick(r, "Come back soon, " + p.getName().getString() + "!", "Take care of yourself!");
        return pick(r, "Goodbye!", "See you around.", "Safe travels.", "Mind how you go.");
    }

    static String puzzled(VillageFolkEntity f) {
        return pick(f.getRandom(), "Hmm? I'm not sure what you mean.", "Sorry — say that again?",
            "I don't follow. Ask me how I am, what I'm doing, about my friends, or the village news.");
    }

    static String joke(VillageFolkEntity f) {
        RandomSource r = f.getRandom();
        if (f.life().has(Social.Trait.GRUMPY) && r.nextBoolean()) return "Do I look like a jester?";
        return pick(r,
            "Why did the creeper cross the road? To get to the other sssside.",
            "What's a miner's favourite music? Rock.",
            "I told a zombie a joke once. It didn't get it. No brains.",
            "Why don't skeletons fight each other? They haven't the guts.",
            "What do you call a sheep with no legs? A cloud.",
            "I'd tell you a joke about gravel, but it'd fall flat.",
            "How does a farmer count his cows? With a cow-culator.",
            "Why was the furnace so popular? It had a warm personality.");
    }

    // ------------------------------------------------------------------ gifts

    /** What kind of present this is, or null for nothing in particular. */
    @Nullable
    static Persona.Gift kindOf(ItemStack s) {
        if (s.is(ItemTags.FLOWERS)) return Persona.Gift.FLOWERS;
        if (s.is(Items.COOKIE) || s.is(Items.CAKE) || s.is(Items.PUMPKIN_PIE) || s.is(Items.HONEY_BOTTLE)
            || s.is(Items.SWEET_BERRIES) || s.is(Items.GLOW_BERRIES)) return Persona.Gift.SWEETS;
        if (s.is(Items.DIAMOND) || s.is(Items.EMERALD) || s.is(Items.AMETHYST_SHARD) || s.is(Items.LAPIS_LAZULI)
            || s.is(Items.QUARTZ)) return Persona.Gift.GEMS;
        if (s.is(Items.BOOK) || s.is(Items.WRITABLE_BOOK) || s.is(Items.WRITTEN_BOOK) || s.is(Items.ENCHANTED_BOOK)) {
            return Persona.Gift.BOOKS;
        }
        if (s.get(DataComponents.JUKEBOX_PLAYABLE) != null || s.is(Items.NOTE_BLOCK) || s.is(Items.JUKEBOX)
            || s.is(Items.GOAT_HORN)) return Persona.Gift.MUSIC;
        if (s.is(ItemTags.FISHES)) return Persona.Gift.FISH;
        if (s.is(Items.GOLD_INGOT) || s.is(Items.GOLD_NUGGET) || s.is(Items.GOLDEN_APPLE)
            || s.is(Items.GOLDEN_CARROT)) return Persona.Gift.GOLD;
        if (s.is(ItemTags.WOOL)) return Persona.Gift.WOOL;
        if (s.is(Items.SPIDER_EYE) || s.is(Items.FERMENTED_SPIDER_EYE)) return Persona.Gift.SPOOKY;
        if (s.is(Items.ROTTEN_FLESH) || s.is(Items.POISONOUS_POTATO)) return Persona.Gift.ROTTEN;
        if (s.getItem() instanceof net.minecraft.world.item.TieredItem tiered
            && tiered.getTier().getAttackDamageBonus() >= net.minecraft.world.item.Tiers.IRON.getAttackDamageBonus()) {
            return Persona.Gift.TOOLS;
        }
        return null;
    }

    static String gift(VillageFolkEntity f, net.minecraft.world.entity.player.Player p) {
        RandomSource r = f.getRandom();
        Persona me = f.persona();
        ItemStack held = p.getMainHandItem();
        String you = p.getName().getString();
        if (held.isEmpty()) return pick(r, "There's nothing in your hand!", "A gift of… air? How thoughtful.");
        long day = f.level().getDayTime() / 24000L;
        Persona.Opinion op = me.opinionOf(p.getUUID(), you);
        if (op.lastGiftDay != day) { op.lastGiftDay = day; op.giftsToday = 0; }
        if (op.giftsToday >= 3) return pick(r, "You've given me plenty today. Keep it!", "No, no — that's too much. Keep it.");
        Persona.Gift kind = kindOf(held);
        String what = held.getHoverName().getString();
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
        int delta;
        String said;
        if (kind == me.hates() || kind == Persona.Gift.ROTTEN) {
            me.feelFor(p.getUUID(), you, -8);
            return pick(r, "Ugh. " + what + "? Why would you give me that?", "Is this a joke? Keep your " + what + ".");
        } else if (kind != null && kind == me.loves()) {
            delta = 15;
            said = pick(r, what + "! I love " + me.loves().words + " — how did you know?",
                "Oh! " + what + "! That's the nicest thing anyone's done for me.");
        } else if (id.equals(me.food())) {
            delta = 12;
            said = pick(r, what + "! My favourite! Thank you!", "You remembered! " + what + " is my favourite.");
        } else if (held.get(DataComponents.FOOD) != null || kind != null || held.is(ItemTags.LOGS)
            || held.is(Items.IRON_INGOT) || held.is(Items.COAL) || held.is(Items.TORCH)) {
            delta = 5;
            said = pick(r, "That's kind of you. Thank you.", "For me? Thank you, " + you + ".", "How thoughtful. I'll put it to use.");
        } else {
            delta = 1;
            said = pick(r, "Oh. Er — thank you, I suppose.", "A " + what + ". Well. That's… something.");
        }
        ItemStack one = held.split(1);
        ItemStack left = f.insertItem(one);
        if (!left.isEmpty()) f.spawnAtLocation(left);
        op.giftsToday++;
        me.feelFor(p.getUUID(), you, delta);
        me.gotAGift(day, you);
        if (delta >= 12) me.remember(day, you + " gave me " + what.toLowerCase(Locale.ROOT), 6);
        if (f.level() instanceof ServerLevel server) {
            server.sendParticles(delta >= 12 ? net.minecraft.core.particles.ParticleTypes.HEART
                    : net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                f.getX(), f.getY() + 2.1, f.getZ(), delta >= 12 ? 4 : 3, 0.3, 0.2, 0.3, 0.0);
        }
        f.playSound(net.minecraft.sounds.SoundEvents.VILLAGER_YES, 0.8F, 1.0F + r.nextFloat() * 0.2F);
        f.refreshMood();
        return said;
    }

    // ------------------------------------------------------------------ favours

    private record Spare(Predicate<ItemStack> what, int count, String words) {}

    private static List<Spare> spares(AssistantEntity.StationTask trade) {
        List<Spare> out = new ArrayList<>();
        switch (trade) {
            case FARM -> { out.add(new Spare(s -> s.is(Items.BREAD), 3, "bread"));
                out.add(new Spare(s -> s.is(Items.CARROT) || s.is(Items.POTATO), 4, "vegetables")); }
            case WOOD -> out.add(new Spare(s -> s.is(ItemTags.LOGS), 8, "logs"));
            case MINE -> { out.add(new Spare(s -> s.is(Items.COAL), 6, "coal"));
                out.add(new Spare(s -> s.is(Items.RAW_COPPER), 4, "copper")); }
            case RANCH -> { out.add(new Spare(s -> s.is(ItemTags.WOOL), 3, "wool"));
                out.add(new Spare(s -> s.is(Items.LEATHER), 2, "leather")); }
            case GUARD -> out.add(new Spare(s -> s.is(Items.ARROW), 8, "arrows"));
            case SMELT -> { out.add(new Spare(s -> s.is(Items.TORCH), 8, "torches"));
                out.add(new Spare(s -> s.is(Items.CHARCOAL) || s.is(Items.COAL), 4, "fuel")); }
            case FISH -> { out.add(new Spare(s -> s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON), 3, "cooked fish"));
                out.add(new Spare(s -> s.is(Items.COD) || s.is(Items.SALMON), 3, "fish")); }
            case HAUL -> out.add(new Spare(s -> s.is(Items.TORCH), 8, "torches"));
            default -> { }
        }
        out.add(new Spare(s -> s.is(Items.BREAD), 2, "bread"));
        out.add(new Spare(s -> s.is(Items.APPLE), 2, "apples"));
        return out;
    }

    static String favour(VillageFolkEntity f, net.minecraft.world.entity.player.Player p) {
        RandomSource r = f.getRandom();
        Persona me = f.persona();
        String you = p.getName().getString();
        Persona.Opinion op = me.opinionOf(p.getUUID(), you);
        long day = f.level().getDayTime() / 24000L;
        int need = f.life().has(Social.Trait.GENEROUS) ? 10 : 25;
        if (f.ownerId() != null && Standing.of(f.ownerId(), p.getUUID(), f.level().getGameTime()).title()
                .atLeast(Standing.Title.FRIEND)) need -= 10;          // a friend of the village is everybody's friend
        if (op.affinity < need) {
            return f.life().has(Social.Trait.GRUMPY) ? "Earn it first." : pick(r,
                "I don't know you well enough for that, " + you + ".", "Maybe when we know each other better.");
        }
        if (op.lastFavourDay == day) return pick(r, "I gave you something already today!", "Again? Tomorrow, maybe.");
        if (me.mood() < 25) return "Not today. I've nothing left to give today, in any sense.";
        int radius = f.ownerId() == null ? 0 : Villages.storesRadius(f.ownerId());
        for (Spare s : spares(f.stationTask())) {
            int have = f.countCarried(s.what());
            int got = have >= s.count() || f.villageCentre() == null ? 0
                : f.drawFrom(f.villageCentre(), s.what(), s.count() - have, radius);
            if (have + got <= 0) continue;
            int give = Math.min(s.count(), have + got);
            List<ItemStack> stacks = new ArrayList<>();
            int left = give;
            for (ItemStack st : f.getInventoryItems()) {
                if (left <= 0) break;
                if (st.isEmpty() || !s.what().test(st)) continue;
                int n = Math.min(left, st.getCount());
                stacks.add(st.split(n));
                left -= n;
            }
            for (ItemStack st : stacks) {
                if (!p.getInventory().add(st)) p.drop(st, false);
            }
            op.lastFavourDay = day;
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            me.remember(day, "I gave " + you + " some " + s.words(), 2);
            return f.life().has(Social.Trait.GENEROUS) || f.life().has(Social.Trait.CHEERFUL)
                ? pick(r, "Of course! Here — " + give + " " + s.words() + ".", "Take these " + s.words() + ", with my blessing.")
                : pick(r, "Here. " + give + " " + s.words() + ". Don't say I never gave you anything.",
                    "I can spare " + give + " " + s.words() + ". There.");
        }
        return pick(r, "I'm afraid I've nothing to spare right now.", "Sorry — my pack's empty and so are the stores.");
    }

    // ------------------------------------------------------------------ requests

    static String follow(VillageFolkEntity f, net.minecraft.world.entity.player.Player p) {
        RandomSource r = f.getRandom();
        Persona me = f.persona();
        Social.Life life = f.life();
        int aff = me.affinity(p.getUUID());
        if (f.isFollowing(p)) return "I'm right behind you.";
        int need = life.has(Social.Trait.SOCIABLE) || life.has(Social.Trait.CURIOUS) ? 20 : 30;
        if (f.ownerId() != null && Standing.of(f.ownerId(), p.getUUID(), f.level().getGameTime()).title()
                .atLeast(Standing.Title.HONOURED)) need -= 15;
        if (aff < need) return pick(r, "Go with you? I hardly know you.", "Where? With a stranger? No, thank you.");
        if (me.mood() < 30) return pick(r, "I'm not in the mood for wandering.", "Not today. I'd be poor company.");
        if (f.isSleeping() || f.level().isNight() && f.stationTask() != AssistantEntity.StationTask.GUARD) {
            return "At this hour? I'm for my bed.";
        }
        if (f.stationTask() == AssistantEntity.StationTask.GUARD && f.onShift()) return "I'm on watch. I can't leave my post.";
        if (life.has(Social.Trait.HARDWORKING) && f.onShift() && !f.offWorkNow()) {
            return "After my shift, perhaps. The " + f.stationTask().label + " won't do itself.";
        }
        f.startFollowing(p);
        long day = f.level().getDayTime() / 24000L;
        me.remember(day, "I went walking with " + p.getName().getString(), 3);
        if (life.has(Social.Trait.GRUMPY)) return "Fine. But not far, and not for long.";
        if (life.has(Social.Trait.SHY)) return "All right… I'll come.";
        if (life.has(Social.Trait.CURIOUS)) return "An adventure? Lead the way!";
        return pick(r, "Lead the way!", "All right, let's go.", "Why not? Where to?");
    }

    static String stay(VillageFolkEntity f, net.minecraft.world.entity.player.Player p) {
        RandomSource r = f.getRandom();
        if (!f.isFollowing(p)) return pick(r, "Back to it, then.", "Right you are.");
        f.stopFollowing();
        me(f).feelFor(p.getUUID(), p.getName().getString(), 4);
        return pick(r, "That was a fine walk. Back to my day, then.", "Thanks for the company. I'll head home.",
            "Right. You know where to find me.");
    }

    private static Persona me(VillageFolkEntity f) { return f.persona(); }

    // ------------------------------------------------------------------ typed words

    /** What a typed line is about, by the words in it. */
    public static TalkTopic understand(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", " ") + " ";
        if (has(t, "joke", "funny", "make me laugh")) return TalkTopic.JOKE;
        if (has(t, "can i help", "need anything", "anything i can do", "help you", "any work", "a task", "a job for me")) return TalkTopic.HELP;
        if (has(t, "here's what", "here is what", "brought you", "i brought", "i've got the", "hand over", "i did it", "done it")) return TalkTopic.DELIVER;
        if (has(t, "remember", "memory", "memories", "the old days", "a story", "tell me a story")) return TalkTopic.MEMORY;
        if (has(t, "think of me", "about me", "my reputation", "people say", "do they like me", "am i welcome")) return TalkTopic.REPUTE;
        if (has(t, "history", "chronicle", "the past", "founded")) return TalkTopic.CHRONICLE;
        if (has(t, "who lives", "everyone here", "everybody here", "register", "census", "residents", "all the people")) return TalkTopic.CENSUS;
        if (has(t, "how are you", "how're you", "you ok", "feeling", "how do you feel", "mood", "happy", "sad")) return TalkTopic.HOW;
        if (has(t, "follow", "come with", "come along", "join me", "adventure", "walk with")) return TalkTopic.FOLLOW;
        if (has(t, " stay ", "go back", "go home", "you can go", "wait here", "back to work", "dismiss")) return TalkTopic.STAY;
        if (has(t, "spare", "have anything", "give me", "favour", "favor", "can i have")) return TalkTopic.FAVOUR;
        if (has(t, "for you", "a gift", "present", "take this", "here you go")) return TalkTopic.GIFT;
        if (has(t, "hobby", "for fun", "free time", "spare time", "evenings", "like to do")) return TalkTopic.HOBBY;
        if (has(t, "doing", "up to", " job", " work", "busy")) return TalkTopic.DOING;
        if (has(t, "friend", "family", "partner", "wife", "husband", "children", " kids", "parents", "love", "married")) return TalkTopic.PEOPLE;
        if (has(t, "news", "gossip", "heard", "happening", "village", "need")) return TalkTopic.VILLAGE;
        if (has(t, "dream", "hope", "wish", "ambition", "goal", "want")) return TalkTopic.DREAMS;
        if (has(t, "who are you", "your name", "about you", "yourself", "tell me about", "where are you from")) return TalkTopic.ABOUT;
        if (has(t, "bye", "goodbye", "see you", "farewell", "later")) return TalkTopic.BYE;
        if (has(t, " hello", " hi ", " hey", "morning", "evening", "greetings", "howdy")) return TalkTopic.OPEN;
        return TalkTopic.SAY;
    }

    private static boolean has(String text, String... words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    // ------------------------------------------------------------------ out loud

    /** A word for a player walking past. */
    public static String passing(VillageFolkEntity f, net.minecraft.world.entity.player.Player p) {
        RandomSource r = f.getRandom();
        String you = p.getName().getString();
        int aff = f.persona().affinity(p.getUUID());
        if (aff <= -50) return pick(r, "…", "Keep walking.", "Hmph.");
        if (aff <= -15) return pick(r, "Oh. You.", "Hm.");
        if (f.life().has(Social.Trait.GRUMPY) && aff < 30) return pick(r, "Hm.", "Mm.", "'Lo.");
        if (aff >= 55) return pick(r, you + "! Lovely to see you.", "Hello, " + you + "!", "There's my friend " + you + "!");
        if (aff >= 20) return pick(r, timeOfDay(f) + ", " + you + ".", "Hello, " + you + ".", "Oh, hi " + you + ".");
        return pick(r, timeOfDay(f) + ".", "Hello there.", "Afternoon, stranger.", "Hello!");
    }

    /** A player has just killed a monster near the village. */
    public static String thanks(VillageFolkEntity f, net.minecraft.world.entity.player.Player p) {
        RandomSource r = f.getRandom();
        String you = p.getName().getString();
        if (f.life().has(Social.Trait.GRUMPY)) return pick(r, "Hm. Good riddance.", "About time somebody did.");
        return pick(r, "Thank you, " + you + "!", "Well fought!", "One less of those, thank goodness.",
            "Our hero, " + you + "!", "Phew! Thank you.");
    }

    /** Being hit. */
    public static String ouch(VillageFolkEntity f) {
        RandomSource r = f.getRandom();
        if (f.life().has(Social.Trait.GRUMPY)) return pick(r, "You'll regret that.", "Oi! Hit me again and see.");
        if (f.life().has(Social.Trait.SHY)) return pick(r, "Please — don't!", "Ow… why?");
        return pick(r, "Ow! What was that for?", "Hey! Stop that!", "Ouch! I'll remember that.");
    }

    // ------------------------------------------------------------------ small talk

    /** Two folk passing the time of day, out loud. */
    public static void smallTalk(VillageFolkEntity a, VillageFolkEntity b) {
        RandomSource r = a.getRandom();
        int warmth = a.life().affinity(b.getUUID());
        boolean partners = b.getUUID().equals(a.life().partner());
        String line;
        if (warmth <= Social.RIVAL) {
            line = pick(r, "Hmph.", "Oh. It's you.", "Out of my way, " + b.displayNameCap() + ".", "Must you stand there?");
        } else if (partners) {
            line = pick(r, "There you are, love.", "Long day?", "Shall we turn in soon?", "Supper's on me tonight.");
        } else {
            List<String> options = new ArrayList<>(List.of(
                "Lovely evening, " + b.displayNameCap() + ".", "How's the " + b.stationTask().label + " going?",
                "My back's killing me.", "Have you tried " + foodWords(a.persona().food()) + "? Delicious.",
                "Did you hear the news?", "Busy day?", "Not long till bed."));
            if (a.level().isRaining()) options.add("Wet one, isn't it?");
            if (a.persona().hobby() == Persona.Hobby.CARDS) options.add("Cards later, " + b.displayNameCap() + "?");
            if (a.persona().hobby() == Persona.Hobby.MUSIC) options.add("I'll be playing at the well tonight.");
            if (a.life().has(Social.Trait.CURIOUS)) options.add("Do you think there's anything under the bedrock?");
            if (a.ownerId() != null) {
                List<Villages.News> n = Villages.news(a.ownerId());
                if (!n.isEmpty()) options.add("Did you hear? " + cap(n.get(0).text()) + ".");
            }
            line = options.get(r.nextInt(options.size()));
        }
        speak(a, line);
        String back;
        if (warmth <= Social.RIVAL) back = pick(r, "Hmph yourself.", "Likewise, I'm sure.");
        else back = pick(r, "Aye.", "It is, isn't it?", "Ha! True enough.", "Mm-hm.", "Tell me about it.");
        b.sayLater(back, 40);
    }

    /** One folk telling another what it thinks of a player, out loud — they may be listening. */
    public static void gossip(VillageFolkEntity a, VillageFolkEntity b, String player, int mine, boolean news) {
        RandomSource r = a.getRandom();
        String other = b.displayNameCap();
        String line, back;
        if (mine >= 0) {
            line = news ? pick(r, "Have you met " + player + ", " + other + "? Ever so kind.",
                    "If " + player + " comes by, make them welcome.",
                    "There's a traveller called " + player + " — one of the good ones.")
                : pick(r, "I'd trust " + player + " with anything.", player + " was good to me, you know.",
                    "Say what you like, " + player + "'s all right.");
            back = pick(r, "Is that so? I'll look out for them.", "Good to know.", "So I've heard!");
        } else {
            line = news ? pick(r, "Keep an eye on " + player + ", " + other + ". Trouble, that one.",
                    "If " + player + " comes by, lock your chest.", "Watch out for " + player + ".")
                : pick(r, "Don't trust " + player + ". Just don't.", player + "? Don't get me started.",
                    "I've not forgotten what " + player + " did.");
            back = pick(r, "Noted. I'll keep my distance.", "Really? Well, I never.", "Hm. I'd wondered.");
        }
        speak(a, line);
        b.sayLater(back, 40);
    }

    // ------------------------------------------------------------------ words

    static String foodWords(String id) {
        return switch (id) {
            case "baked_potato" -> "a baked potato";
            case "cookie" -> "a cookie";
            case "pumpkin_pie" -> "pumpkin pie";
            case "apple" -> "a crisp apple";
            case "cooked_cod" -> "fresh cod";
            case "cooked_salmon" -> "salmon";
            case "sweet_berries" -> "sweet berries";
            case "honey_bottle" -> "honey";
            case "golden_carrot" -> "a golden carrot";
            case "cake" -> "cake";
            case "mushroom_stew" -> "mushroom stew";
            default -> "fresh bread";
        };
    }

    /** "a poppy", "an allium", "a loaf of bread". */
    public static String article(String what) {
        if (what.equals("bread")) return "a loaf of bread";
        if (what.equals("coal")) return "a lump of coal";
        return ("aeiou".indexOf(what.isEmpty() ? 'x' : what.charAt(0)) >= 0 ? "an " : "a ") + what;
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
