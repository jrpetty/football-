package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Two folk passing the time of day: a few words each, turn about, over their heads — and
 * about what is really going on: the building going up and who is building it, what the
 * village is short of, the weather, tonight's gathering, market day, each other's work,
 * family, a friend in common, how the village is getting on, a new rug at home. Each says
 * it its own way: the grumpy grumble, the cheerful exclaim, the shy hesitate.
 */
public final class Smalltalk {

    private Smalltalk() {}

    /** Three lines: the opener, the answer, the last word. */
    record Talk(String open, String answer, String last) {}

    /**
     * {@code a} starts a little conversation with {@code b}: a speaks now, b answers in a
     * moment, a has the last word. Returns whether they talked.
     */
    public static boolean chat(VillageFolkEntity a, VillageFolkEntity b, ServerLevel level) {
        if (a.isSleeping() || b.isSleeping() || a.isBaby() || b.isBaby()) return false;
        if (level.getNearestPlayer(a, 24.0) == null) return false;       // nobody to hear: no need to say it
        Talk t = topic(a, b, level);
        if (t == null) return false;
        a.getLookControl().setLookAt(b, 30.0F, 30.0F);
        b.getLookControl().setLookAt(a, 30.0F, 30.0F);
        FolkTalk.speak(a, voice(a, t.open()));
        b.sayLater(voice(b, t.answer()), 50 + a.getRandom().nextInt(20));
        if (!t.last().isEmpty()) a.sayLater(voice(a, t.last()), 110 + a.getRandom().nextInt(20));
        if (a.getRandom().nextInt(3) == 0) Manner.laugh(b);   // [individual] a laugh at it
        a.life().feel(b.getUUID(), b.displayNameCap(), 1);
        b.life().feel(a.getUUID(), a.displayNameCap(), 1);
        return true;
    }

    @Nullable
    static Talk topic(VillageFolkEntity a, VillageFolkEntity b, ServerLevel level) {
        RandomSource r = a.getRandom();
        UUID village = a.ownerId();
        if (village == null) return null;
        List<Talk> options = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        // The building going up.
        for (AssistantEntity x : Villages.folkOf(village)) {
            Job j = x.peekJob();
            if (j != null && j.type() == Job.Type.BUILD && j.arg() != null) {
                String what = Villages.spoken(j.arg().split("\\|", 2)[0]);
                String who = x == a ? "me" : x == b ? "you" : x.displayNameCap();
                options.add(new Talk(capital(what) + "'s coming on, isn't it?",
                    who.equals("you") ? "Slowly! My back knows all about it." : "It is. " + capital(who) + " works all hours on it.",
                    "We'll be glad of it, come winter."));
                break;
            }
        }
        // What the village is short of.
        for (Villages.Need n : Villages.needs(level, village)) {
            if (n.task() == Villages.Task.BUILD || n.task() == Villages.Task.NONE) continue;
            options.add(new Talk("Short of " + n.what() + " again, I hear.",
                pick(r, "The " + plural(n.task()) + " are on it, I'm told.", "Always something, isn't it?",
                    "We'll manage. We always do."),
                pick(r, "Let's hope so.", "True enough.", "")));
            break;
        }
        // The election (Elections): who each of them is voting for, and why. Talked of above all else.
        String[] vote = Elections.gossip(a, b);
        if (vote != null) {
            Talk t = new Talk(vote[0], vote[1], vote[2]);
            options.add(t);
            options.add(t);
        }
        String[] word = PlayerLeader.gossip(a, b);           // [player-civic] the leader's promises, kept and broken
        if (word != null) options.add(new Talk(word[0], word[1], word[2]));
        String[] civic = Referendums.gossip(a, b);           // [civic] the town's vote: a great work, or newcomers
        if (civic != null) options.add(new Talk(civic[0], civic[1], civic[2]));
        // The weather.
        if (level.isRaining()) {
            options.add(new Talk(pick(r, "Wet one today.", "Will this rain never stop?"),
                pick(r, "Good for the crops, mind.", "Soaked to the skin, I am."), pick(r, "Mm.", "Aye.", "")));
        } else if (!level.isNight()) {
            options.add(new Talk(pick(r, "Lovely day for it.", "Fine weather, this."),
                pick(r, "Too good to be working!", "Make the most of it, I say."), ""));
        }
        // Tonight.
        Gatherings.Kind tonight = Gatherings.tonight(village, day);
        if (tonight != null) {
            options.add(new Talk("Are you going to " + Gatherings.describe(tonight, village) + " tonight?",
                tonight == Gatherings.Kind.VIGIL ? "Of course. We all will." : pick(r, "Wouldn't miss it!", "If my feet hold out."),
                tonight == Gatherings.Kind.VIGIL ? "" : "See you there, then."));
        }
        // Market day.
        if (Market.marketDay(village, day)) {
            options.add(new Talk("Market day! Got your coins?", b.purse() >= 5 ? "A few saved. Fancy something sweet." : "Barely two to rub together.",
                pick(r, "Ha! Me neither.", "Treat yourself.", "")));
        }
        // Each other's work.
        if (b.stationTask() != AssistantEntity.StationTask.NONE) {
            String work = b.stationTask().label;
            String busy = b.veteranLevel() >= 10 ? "Busy! I could do it in my sleep, mind." : "Learning all the time.";
            options.add(new Talk("How's the " + work + "?", busy, pick(r, "Good for you.", "Rather you than me!", "")));
        }
        // Family.
        if (b.life().children() > 0) {
            options.add(new Talk("How are the little ones?", pick(r, "Growing like weeds!", "Into everything, the pair of them."),
                pick(r, "Bless them.", "Enjoy it while it lasts.")));
        }
        // A friend in common.
        for (Social.Bond friend : a.life().friends()) {
            if (friend.name == null || friend.name.isEmpty() || friend.name.equals(b.displayNameCap())) continue;
            options.add(new Talk("Have you seen " + friend.name + " lately?",
                pick(r, "This morning. Off to work as usual.", "Not since the feast.", "Works too hard, that one."),
                pick(r, "Give my best if you do.", "")));
            break;
        }
        // How the village is getting on.
        int content = Contentment.score(village);
        if (content < 40) {
            options.add(new Talk("Things aren't what they were.", pick(r, "No. Something has to change.", "Don't I know it."), ""));
        } else if (content >= 75) {
            options.add(new Talk("Good times, these.", "Best I can remember.", pick(r, "Long may it last.", "")));
        }
        // A comfort at home.
        if (b.comforts() > 0) {
            options.add(new Talk("I hear you've done your place up.", "A few nice things. I've earned them!",
                pick(r, "That you have.", "I'll have to come and see.")));
        }
        // Comings and goings between the towns.
        String abroad = Envoys.latest(village);
        if (abroad != null) {
            options.add(new Talk("Did you hear? " + capital(abroad) + ".",
                pick(r, "I did! What do you make of it?", "Never! Well I never.", "The elder knows what it's doing. I hope."),
                pick(r, "We'll see what comes of it.", "Time will tell.", "")));
        }
        // What the scouts have found.
        List<Scouts.Find> atlas = Scouts.atlas(village);
        if (!atlas.isEmpty()) {
            Scouts.Find x = atlas.get(atlas.size() - 1 - r.nextInt(Math.min(4, atlas.size())));
            if (x.kind() != Scouts.Kind.BLOCKED && x.kind() != Scouts.Kind.LAND) {
                String where = Guide.direction(a.blockPosition(), x.at());
                options.add(new Talk("The scouts found " + x.label() + ", off to the " + where + ".",
                    x.kind() == Scouts.Kind.DANGER ? "I'll not be going that way, then." : x.kind() == Scouts.Kind.ORE
                        ? "The miners will be after that." : pick(r, "I'd love to see it one day.", "What a world it is out there."),
                    pick(r, "Me too.", "Brave souls, those scouts.", "")));
            }
        }
        for (String[] t : Seasons.talk(a, b, level, r)) options.add(new Talk(t[0], t[1], t[2]));   // [batchB] the season, now and then
        for (String[] t : Pets.chat(a, b, level, r)) options.add(new Talk(t[0], t[1], t[2]));      // [pets] the dog, the cat, the litter
        for (String[] t : Disasters.talk(a, b, level, r)) options.add(new Talk(t[0], t[1], t[2]));   // [disasters] the fire, the flood, the drought
        for (String[] t : Library.talk(a, b, level, r)) options.add(new Talk(t[0], t[1], t[2]));   // [library] the new edition, a new poem
        if (options.isEmpty()) return null;
        return options.get(r.nextInt(options.size()));
    }

    /** Said its own way. */
    static String voice(VillageFolkEntity f, String line) {
        if (line.isEmpty()) return line;
        Social.Life life = f.life();
        RandomSource r = f.getRandom();
        if (life.has(Social.Trait.GRUMPY) && r.nextInt(3) == 0) return "Hmph. " + line;
        if (life.has(Social.Trait.SHY) && r.nextInt(3) == 0) return "Oh — " + Character.toLowerCase(line.charAt(0)) + line.substring(1);
        if (life.has(Social.Trait.CHEERFUL) && line.endsWith(".") && r.nextBoolean()) return line.substring(0, line.length() - 1) + "!";
        return line;
    }

    private static String plural(Villages.Task t) {
        return switch (t) {
            case FOOD -> "farmers";
            case LOGS -> "woodcutters";
            case STONE, COAL, IRON, DIAMOND, OBSIDIAN -> "miners";
            default -> "folk";
        };
    }

    private static String pick(RandomSource r, String... options) {
        return options[r.nextInt(options.length)];
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
