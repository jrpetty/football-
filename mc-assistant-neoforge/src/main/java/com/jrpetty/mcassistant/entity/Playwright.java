package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [culture-wonders] The town's playwright: a play written out of a line of its own chronicle, and out of the war
 * books, the musters and the roll of the fallen that stand round that line.
 *
 * <p>A play has a title and its kind ("The Battle of Ashby Field, a tragedy in three acts"), its parts (the teller
 * who carries the story, and the real folk of it: the Captain who led, the lad who fell, the bride and the groom, the
 * raider at the gate), each with the costume it is played in (Troupe's wardrobe: a soldier in a helmet and a tunic in
 * the town's colours with a wooden sword, the enemy in a helmet, the bride with a flower), and its beats in order: a
 * line said, a player to a mark on the stage, a mock fight, a death on the boards, a getting up again. The house's
 * reaction goes with each (a cheer, a murmur, a hush, a boo for the enemy).
 *
 * <p>It is only what the books say. A battle the town won is a history; one where its folk fell is played as their
 * deaths, by name; one it lost is a tragedy, the field given up and the town's heart kept. The Captain is whoever the
 * muster put in command; the dead are the chronicle's own; the counts are the battle's. Nothing is made up but the
 * words in their mouths.
 */
public final class Playwright {

    private Playwright() {}

    /** What sort of story a line of the chronicle is. */
    public enum Kind { RAID, WEDDING, BIRTH, DEATH, OPENED, AGE, FIND, STORM, FOUNDING, TALE,
        BATTLE, SIEGE, FIRE, FLOOD, ILLNESS, ELECTION, HERO }

    /** What a beat of a play is. */
    public enum Do { SAY, MOVE, FIGHT, FALL, RISE }

    /** The costume a part is played in (Troupe.dress). */
    public enum Kit { PLAIN, TELLER, SOLDIER, FOE, BRIDE, GROOM, ELDER, HERO }

    /** A part: its name, its costume, the real folk it is (if one of the town, alive), and whether it dies on stage. */
    public record Part(String name, Kit kit, @Nullable UUID real) {}

    /**
     * One beat: what, by which part, the words (SAY), the mark it goes to (MOVE), the part it fights (FIGHT), and how
     * the house takes it: '!' a cheer, '?' a murmur, '~' a hush or tears, 'b' a boo, ' ' nothing.
     */
    public record Beat(Do what, int part, String text, int mark, int other, char react) {
        static Beat say(int part, String text, char react) { return new Beat(Do.SAY, part, text, -1, -1, react); }
        static Beat move(int part, int mark) { return new Beat(Do.MOVE, part, "", mark, -1, ' '); }
        static Beat fight(int part, int other) { return new Beat(Do.FIGHT, part, "", -1, other, '!'); }
        static Beat fall(int part) { return new Beat(Do.FALL, part, "", -1, -1, '~'); }
        static Beat rise(int part) { return new Beat(Do.RISE, part, "", -1, -1, ' '); }
    }

    /**
     * A play: its title and its kind ("a tragedy in three acts"), the chronicle's line it tells and that line's day,
     * its parts and its beats, whether it ends in grief, and (a player's own deeds) whose.
     */
    public record Play(String title, String genre, String story, long storyDay, Kind kind, List<Part> parts, List<Beat> beats,
                       boolean tragedy, String about) {
        /** "The Battle of Ashby Field, a tragedy in three acts". */
        public String bill() {
            return title + ", " + genre;
        }

        /** The spoken lines, in order. */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            for (Beat b : beats) if (b.what() == Do.SAY) out.add(b.text());
            return out;
        }

        /** The acts as the teller announces them ("Act two: the field of Ashby"). */
        public List<String> acts() {
            List<String> out = new ArrayList<>();
            for (Beat b : beats) if (b.what() == Do.SAY && b.part() == 0 && b.text().startsWith("Act ")) out.add(b.text());
            return out;
        }

        public int deaths() {
            int n = 0;
            for (Beat b : beats) if (b.what() == Do.FALL) n++;
            return n;
        }

        public int fights() {
            int n = 0;
            for (Beat b : beats) if (b.what() == Do.FIGHT) n++;
            return n;
        }
    }

    // ------------------------------------------------------------------ reading the chronicle

    static final Pattern BATTLE = Pattern.compile("^(the battle of [^:]+): (.+?) against (.+?) \\((\\d+) of ours down, (\\d+) of theirs(?:; captives: (.+))?\\)$",
        Pattern.CASE_INSENSITIVE);
    static final Pattern MUSTER = Pattern.compile("the army mustered on the square: (\\d+) strong(?:, (.+?) in command)?", Pattern.CASE_INSENSITIVE);
    static final Pattern TAKEN_US = Pattern.compile("^(.+?) took the town, (.+?), and the elder made peace on its terms$", Pattern.CASE_INSENSITIVE);
    static final Pattern TOOK_THEM = Pattern.compile("^our army took (.+?), (.+)$", Pattern.CASE_INSENSITIVE);
    static final Pattern ELECTED = Pattern.compile("^(.+?) was elected (.+?) for (.+?) \\(", Pattern.CASE_INSENSITIVE);

    /** What kind of story a line of the chronicle is, or null for one no play is made of (the town's own doings at the theatre). */
    @Nullable
    public static Kind kind(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("players put on") || t.contains("players rehearsed") || t.contains("resolved to keep") || t.contains("motto")
            || t.contains("banner") || t.contains("plaque") || t.contains("painted") || t.contains("play was put off")
            || t.contains("play was stopped") || t.contains("wonder fund") || t.contains("the levy")) return null;
        if (BATTLE.matcher(text).find()) return Kind.BATTLE;
        if (TAKEN_US.matcher(text).find() || TOOK_THEM.matcher(text).find()) return Kind.SIEGE;
        if (t.contains("raiders came")) return Kind.RAID;
        if (t.endsWith(" were wed")) return Kind.WEDDING;
        if (t.contains(" had a child, ")) return Kind.BIRTH;
        if (t.startsWith("fire ")) return Kind.FIRE;
        if (t.contains(" is rising")) return Kind.FLOOD;
        if (t.contains("a cold is going round")) return Kind.ILLNESS;
        if (ELECTED.matcher(text).find()) return Kind.ELECTION;
        if (t.contains(" died") || t.contains(" fell at ")) return Kind.DEATH;
        if (t.endsWith(" was opened")) return Kind.OPENED;
        if (t.contains("came into the ")) return Kind.AGE;
        if (t.contains("the town's first ")) return Kind.FIND;
        if (t.contains("a great storm broke")) return Kind.STORM;
        if (t.contains("was founded") || t.contains("founders came to")) return Kind.FOUNDING;
        return Kind.TALE;
    }

    /** How much more a story of this kind is worth telling than the weight of its words alone (Theatre.story). */
    static int extra(Kind k) {
        return switch (k) {
            case BATTLE, SIEGE -> 9;
            case FIRE, FLOOD -> 5;
            case TALE -> 0;
            default -> 3;
        };
    }

    /** One of the town's folk by name, alive and loaded, for a part that is a real person; null if none. */
    @Nullable
    static UUID real(UUID village, String name) {
        if (name == null || name.isEmpty()) return null;
        String bare = name.replaceFirst("^(Captain|Sergeant) ", "").replaceFirst(" the standard-bearer$", "");
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && f.displayNameCap().equalsIgnoreCase(bare)) return f.getUUID();
        }
        return null;
    }

    /** The muster before a battle on this day: {strength, the Captain}, or null. */
    @Nullable
    static String[] muster(UUID village, long day) {
        String[] best = null;
        for (Chronicle.Entry e : Chronicle.of(village)) {
            if (e.day() > day || e.day() < day - 6) continue;
            Matcher m = MUSTER.matcher(e.text());
            if (m.find()) best = new String[]{ m.group(1), m.group(2) == null ? "" : m.group(2) };
        }
        return best;
    }

    /** The town's folk who fell at this battle, by the chronicle ("Sergeant Tom fell at the Battle of Ashby Field"). */
    static List<String> fallen(UUID village, String battle) {
        List<String> out = new ArrayList<>();
        String at = " fell at " + battle.toLowerCase(Locale.ROOT);
        for (Chronicle.Entry e : Chronicle.of(village)) {
            String t = e.text().toLowerCase(Locale.ROOT);
            int i = t.indexOf(at);
            if (i > 0) out.add(e.text().substring(0, i));
        }
        return out;
    }

    // ------------------------------------------------------------------ writing

    /** A play out of a line of the town's chronicle. */
    public static Play write(UUID village, Chronicle.Entry e) {
        Kind k = kind(e.text());
        if (k == null) k = Kind.TALE;
        return switch (k) {
            case BATTLE -> battle(village, e);
            case SIEGE -> siege(village, e);
            default -> plain(village, e, k);
        };
    }

    /** The parts and beats being written, a little at a time. */
    private static final class Pen {
        final List<Part> parts = new ArrayList<>();
        final List<Beat> beats = new ArrayList<>();

        int part(String name, Kit kit, @Nullable UUID real) {
            parts.add(new Part(name, kit, real));
            return parts.size() - 1;
        }

        Pen say(int part, String text, char react) {
            beats.add(Beat.say(part, text, react));
            return this;
        }

        Pen move(int part, int mark) {
            beats.add(Beat.move(part, mark));
            return this;
        }

        Pen fight(int a, int b) {
            beats.add(Beat.fight(a, b));
            return this;
        }

        Pen fall(int part) {
            beats.add(Beat.fall(part));
            return this;
        }

        Pen rise(int part) {
            beats.add(Beat.rise(part));
            return this;
        }
    }

    /**
     * The Battle of so-and-so, in three acts: the muster (the Captain and the young soldier), the field (the enemy's
     * champion, the fighting, the deaths of the town's own fallen by name), and home (the counts, and what it cost).
     */
    static Play battle(UUID village, Chronicle.Entry e) {
        String town = Villages.name(village);
        Matcher m = BATTLE.matcher(e.text());
        if (!m.find()) return plain(village, e, Kind.TALE);
        String name = Culture.capital(m.group(1));
        String ground = name.replaceFirst("(?i)^the battle of ", "");
        String how = m.group(2).toLowerCase(Locale.ROOT);
        String enemy = m.group(3);
        int ours = Integer.parseInt(m.group(4)), theirs = Integer.parseInt(m.group(5));
        boolean won = how.contains("held the field"), drawn = how.contains("neither side");
        long d = e.day() + 1;
        String[] muster = muster(village, e.day());
        String captain = muster != null && !muster[1].isEmpty() ? muster[1] : "the Captain";
        List<String> fell = fallen(village, name);
        Pen p = new Pen();
        int teller = p.part("the teller", Kit.TELLER, null);
        int cap = p.part(captain, Kit.SOLDIER, real(village, captain));
        int foe = p.part("a soldier of " + enemy, Kit.FOE, null);
        String young = fell.isEmpty() ? "a young soldier" : fell.get(0);
        int lad = p.part(young, Kit.SOLDIER, fell.isEmpty() ? null : real(village, young));
        String capName = captain.equals("the Captain") ? "Captain" : captain;
        // Act one: the muster.
        p.say(teller, "Act one: the muster. Day " + d + ", and " + town + " goes to war with " + enemy + ".", '?');
        p.move(cap, 0);
        p.say(cap, "To me, " + town + "! " + (muster != null ? muster[0] + " of us" : "Every one of us") + " — and every one of us coming home!", '!');
        p.move(lad, 3);
        p.say(lad, "I've never held a sword in anger, " + capName + ".", '?');
        p.say(cap, "Stand by me, " + (fell.isEmpty() ? "lad" : young) + ", and do as I do.", ' ');
        // Act two: the field.
        p.say(teller, "Act two: the field of " + ground + ".", '?');
        p.move(foe, 2);
        p.say(foe, "Yield, " + town + "! " + enemy + " will have this field!", 'b');
        p.say(cap, "Not while I stand! At them!", '!');
        p.fight(cap, foe);
        if (!fell.isEmpty()) {
            p.fight(lad, foe);
            p.fall(lad);
            p.say(lad, "Tell them at home... tell them I held my ground.", '~');
            p.say(cap, "No! " + young + "!", '~');
        }
        if (won) {
            p.fight(cap, foe);
            p.say(foe, "Enough — enough! " + enemy + " yields the field!", '!');
            p.move(foe, 7);
        } else if (drawn) {
            p.say(foe, "Neither of us will give an inch, will we?", '?');
            p.say(cap, "Then we'll both go home and count our dead.", '~');
            p.move(foe, 7);
        } else {
            p.say(cap, "There are too many of them! Fall back — back to the town!", '~');
            p.say(foe, "The field is ours! Run home, " + town + "!", 'b');
            p.move(cap, 6);
        }
        // Act three: home.
        p.say(teller, "Act three: home. " + ours + " of ours down that day, and " + theirs + " of theirs.", '~');
        if (!fell.isEmpty()) p.rise(lad);
        p.move(cap, 0);
        if (won) p.say(cap, fell.isEmpty() ? "We held the field, and every one of us came home." : "We held the field. But we did not all come home.", fell.isEmpty() ? '!' : '~');
        else if (drawn) p.say(cap, "Nobody won. Everybody lost a little.", '~');
        else p.say(cap, "We lost the field. Not our town, and not our hearts.", '~');
        if (fell.size() > 1) p.say(teller, "Remember them: " + JobMarket.join(fell) + ".", '~');
        else if (fell.size() == 1) p.say(teller, "Remember " + young + ".", '~');
        p.say(teller, won ? "So " + town + " held the field of " + ground + " on day " + d + ". Huzzah!" : drawn
            ? "So ended the day at " + ground + ", and the war went on." : "A sad day for " + town + ", friends — and we tell it so we don't forget it.", won ? '!' : '~');
        boolean tragedy = !won && !drawn || !fell.isEmpty() && !won;
        String genre = !won && !drawn ? "a tragedy in three acts" : !fell.isEmpty() ? "an heroic history in three acts" : "a history in three acts";
        return new Play(name, genre, e.text(), e.day(), Kind.BATTLE, List.copyOf(p.parts), List.copyOf(p.beats), tragedy || !won && !drawn, "");
    }

    /** A town taken: ours by an enemy (a tragedy of the siege), or theirs by our army (a triumph). */
    static Play siege(UUID village, Chronicle.Entry e) {
        String town = Villages.name(village);
        long d = e.day() + 1;
        Matcher lost = TAKEN_US.matcher(e.text()), won = TOOK_THEM.matcher(e.text());
        Pen p = new Pen();
        int teller = p.part("the teller", Kit.TELLER, null);
        if (lost.find()) {
            String enemy = lost.group(1), how = lost.group(2);
            int guard = p.part("a guard on the gate", Kit.SOLDIER, null);
            int foe = p.part("a captain of " + enemy, Kit.FOE, null);
            int elder = p.part("the elder", Kit.ELDER, null);
            p.say(teller, "Act one: the siege. Day " + d + ", and " + enemy + "'s army is at our gate.", '~');
            p.move(guard, 0);
            p.say(guard, "Hold the gate! Hold it!", '!');
            p.move(foe, 2);
            p.say(foe, "Open up, " + town + ", or we'll open it for you!", 'b');
            p.fight(guard, foe);
            p.fall(guard);
            p.say(teller, "Act two: the gate. It was " + how + ".", '~');
            p.move(elder, 3);
            p.say(elder, "Enough. No more of ours will fall today. We'll talk.", '~');
            p.say(foe, "Then you'll talk on our terms.", 'b');
            p.rise(guard);
            p.say(teller, "So " + town + " was taken on day " + d + ". But a town is its folk, and its folk are still here.", '~');
            return new Play("The Siege of " + town, "a tragedy in two acts", e.text(), e.day(), Kind.SIEGE, List.copyOf(p.parts),
                List.copyOf(p.beats), true, "");
        }
        String them = won.find() ? won.group(1) : "the enemy";
        int cap = p.part("our Captain", Kit.SOLDIER, null);
        int foe = p.part("the last guard of " + them, Kit.FOE, null);
        p.say(teller, "Act one: the march. Day " + d + ", and our army stands before " + them + ".", '?');
        p.move(cap, 0);
        p.say(cap, "Their gate, friends. One more push!", '!');
        p.move(foe, 2);
        p.say(foe, "You'll not pass while I stand!", 'b');
        p.fight(cap, foe);
        p.say(teller, "Act two: the gate.", '?');
        p.fight(cap, foe);
        p.say(foe, "I yield — " + them + " yields!", '!');
        p.say(cap, "Then the war's done. Home, all of you — home!", '!');
        p.say(teller, "So " + town + " took " + them + " on day " + d + ", and made the peace.", '!');
        return new Play("The Taking of " + them, "a history in two acts", e.text(), e.day(), Kind.SIEGE, List.copyOf(p.parts),
            List.copyOf(p.beats), false, "");
    }

    /** A player's own deeds, from every line of the chronicle with its name in it (Playbill.request). */
    public static Play hero(UUID village, String player, List<Chronicle.Entry> deeds) {
        String town = Villages.name(village);
        Pen p = new Pen();
        int teller = p.part("the teller", Kit.TELLER, null);
        int hero = p.part(player, Kit.HERO, null);
        int other = p.part("a doubter", Kit.PLAIN, null);
        boolean fought = false;
        p.say(teller, "Act one: a stranger comes to " + town + ". Their name? " + player + ".", '?');
        p.move(hero, 0);
        p.say(hero, "Good day! Is there anything wants doing round here?", ' ');
        p.say(other, "Who's this, then? We've not seen the likes of you before.", '?');
        p.say(teller, "Act two: the deeds.", '?');
        int n = 0;
        for (Chronicle.Entry e : deeds) {
            if (n >= 3) break;
            String t = e.text();
            p.say(teller, "Day " + (e.day() + 1) + ": " + Culture.capital(t) + (t.endsWith(".") ? "" : "."), '!');
            String low = t.toLowerCase(Locale.ROOT);
            if (!fought && (low.contains("raiders") || low.contains("fight") || low.contains("battle") || low.contains("stood with"))) {
                p.move(other, 2);
                p.say(other, "Grr! Out of my way!", 'b');
                p.fight(hero, other);
                p.say(other, "All right, all right — I'm going!", '!');
                fought = true;
            }
            n++;
        }
        p.say(other, "Well. I take it all back.", '!');
        p.say(hero, "It was nothing. Anybody would have done the same.", '!');
        p.say(teller, "And that, friends, is the ballad of " + player + ", a friend of " + town + ".", '!');
        return new Play("The Ballad of " + player, "a heroic tale in two acts", deeds.isEmpty() ? "" : deeds.get(0).text(),
            deeds.isEmpty() ? 0 : deeds.get(0).day(), Kind.HERO, List.copyOf(p.parts), List.copyOf(p.beats), false, player);
    }

    /** The town's other stories, as the theatre has always told them; and the fire, the flood, the colds, the election. */
    static Play plain(UUID village, Chronicle.Entry e, Kind k) {
        String town = Villages.name(village);
        String t = e.text();
        long d = e.day() + 1;
        Pen p = new Pen();
        String title;
        String genre;
        boolean grief = false;
        switch (k) {
            case RAID -> {
                String gate = between(t, "at the ", " gate");
                boolean lost = t.contains("of the village fell");
                title = "The Night at the " + (gate.isEmpty() || gate.contains(" ") ? "" : Culture.capital(gate) + " ") + "Gate";
                genre = "a history in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part("the guard", Kit.SOLDIER, null);
                p.part("the raider", Kit.FOE, null);
                p.say(0, "Day " + d + " in " + town + ". The lamps are out, the town asleep... or is it?", '?');
                p.move(1, 0);
                p.say(1, "Who goes there? Show yourselves at the gate!", ' ');
                p.move(2, 2);
                p.say(2, "Open up, little town, or we'll have your gate off its hinges!", 'b');
                p.say(1, "Ring the bell! To the wall, everybody — to the wall!", '!');
                p.fight(1, 2);
                p.say(0, "And the bell rang out over " + town + ", and the watch came running.", ' ');
                p.say(2, "There's more of them than we were told! Fall back!", '?');
                p.move(2, 7);
                p.say(1, lost ? "They're running... but not all of ours are standing." : "They're running! The gate holds!", lost ? '~' : '!');
                p.say(0, lost ? "So the gate held on day " + d + ", and we remember those who held it."
                    : "So the gate held on day " + d + ", and nobody lost. That's our watch!", lost ? '~' : '!');
                grief = lost;
            }
            case WEDDING -> {
                String names = t.substring(0, t.length() - " were wed".length());
                int and = names.indexOf(" and ");
                String a = and > 0 ? names.substring(0, and) : names, b = and > 0 ? names.substring(and + 5) : "my love";
                title = "The Wedding of " + a + " and " + b;
                genre = "a comedy in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part(a, Kit.GROOM, real(village, a));
                p.part(b, Kit.BRIDE, real(village, b));
                p.say(0, "Day " + d + ". Two folk in " + town + ", and everybody knew it before they did.", '?');
                p.say(1, b + "... I've something to ask you, and I've practised it all week.", ' ');
                p.say(2, "Then ask it, " + a + ", before the bell goes!", '?');
                p.move(1, 3).move(2, 4);
                p.say(1, "Will you have me — for better or worse, and the washing on a wet day?", ' ');
                p.say(2, "I will! A hundred times, I will!", '!');
                p.say(0, "And the whole town came to the board to see them wed.", '!');
                p.say(1, "To us!", '!');
                p.say(2, "And to " + town + "!", '!');
            }
            case BIRTH -> {
                int had = t.indexOf(" had a child, ");
                String parents = t.substring(0, had), child = t.substring(had + " had a child, ".length());
                int and = parents.indexOf(" and ");
                String a = and > 0 ? parents.substring(0, and) : parents, b = and > 0 ? parents.substring(and + 5) : "the midwife";
                title = child + " Comes Into the World";
                genre = "a comedy in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part(a, Kit.PLAIN, real(village, a));
                p.part(b, Kit.PLAIN, real(village, b));
                p.say(0, "Day " + d + ", and a long night at a little house in " + town + ".", '~');
                p.move(1, 3);
                p.say(1, "Is it here? Is it here yet?", '?');
                p.say(2, "Not yet! Sit down, " + a + ", you'll wear a hole in the floor.", '?');
                p.move(1, 4);
                p.say(0, "And then, at last, a cry.", '~');
                p.say(2, "Look — it's " + child + "!", '!');
                p.say(1, "Welcome to " + town + ", little one. It's a good place.", '!');
            }
            case DEATH -> {
                int at = t.indexOf(" died");
                if (at < 0) at = t.indexOf(" fell at ");
                String name = at > 0 ? t.substring(0, at) : t;
                title = name + ", Remembered";
                genre = "a lament in one act";
                grief = true;
                p.part("the teller", Kit.TELLER, null);
                p.part(name, Kit.PLAIN, null);
                p.part("a friend", Kit.PLAIN, null);
                p.say(0, "This is the story of " + name + ", who lived among us.", '~');
                p.move(1, 0);
                p.say(1, "Morning! Lovely day for it, whatever it is.", ' ');
                p.say(2, "That was " + name + " all over. Never a day without a word for you.", '?');
                p.say(1, "Look after the place for me, will you? It's a good town.", '~');
                p.fall(1);
                p.say(0, name + " left us on day " + d + ". We don't forget.", '~');
                p.rise(1);
            }
            case OPENED -> {
                String what = t.substring(0, t.length() - " was opened".length());
                title = "The Opening of " + Culture.capital(what.startsWith("the ") ? what.substring(4) : what);
                genre = "a comedy in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part("the builder", Kit.PLAIN, null);
                p.part("a neighbour", Kit.PLAIN, null);
                p.say(0, "Day " + d + ". Planks, stone and sore backs: " + what + " is going up.", ' ');
                p.say(1, "Mind that beam! And who's had the last of the nails?", '?');
                p.move(2, 4);
                p.say(2, "Is it finished yet? You said it'd be done by Tuesday.", '?');
                p.say(1, "We haven't got Tuesdays! It's done when it's done.", '!');
                p.say(0, "And on day " + d + ", " + what + " was opened, and the town came to see.", '!');
                p.say(2, "Well. It was worth the wait.", '!');
            }
            case AGE -> {
                String age = t.substring(t.indexOf("came into ") + "came into ".length());
                title = "Into " + Culture.capital(age);
                genre = "a history in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part("an old hand", Kit.ELDER, null);
                p.part("a youngster", Kit.PLAIN, null);
                p.say(0, "Day " + d + ": the day " + town + " came into " + age + ".", '!');
                p.say(1, "When I was young we had sticks and stones, and were glad of them.", '?');
                p.say(2, "And now look at us!", '!');
                p.say(1, "Aye. Now look at us.", '~');
                p.say(0, "And " + town + " never looked back.", '!');
            }
            case FIND -> {
                int the = t.indexOf(" the ");
                String finder = the > 0 ? t.substring(0, the) : "a miner";
                String thing = t.substring(t.indexOf("the town's first ") + "the town's first ".length());
                title = Culture.capital(finder) + " and the " + Culture.capital(thing);
                genre = "a comedy in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part(finder, Kit.PLAIN, real(village, finder));
                p.part("the foreman", Kit.PLAIN, null);
                p.say(0, "Day " + d + ". Deep under " + town + ", " + finder + " swings a pick, as on any other day.", ' ');
                p.say(1, "Wait... what's that, glinting in the rock?", '?');
                p.say(2, "More coal. It's always more coal.", '?');
                p.say(1, "It's not coal. Look at it!", '!');
                p.say(2, "Well, I'll be... it's " + (thing.isEmpty() ? "treasure" : "a " + thing) + "!", '!');
                p.say(0, "And that is how " + town + " found its first " + thing + ".", '!');
            }
            case STORM -> {
                title = "The Great Storm";
                genre = "a history in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part("a farmer", Kit.PLAIN, null);
                p.part("a guard", Kit.SOLDIER, null);
                p.say(0, "Day " + d + ". The sky over " + town + " goes black at noon.", '~');
                p.say(1, "My wheat! The wind'll have it flat!", '?');
                p.say(2, "Indoors, everyone! Leave it — indoors!", '!');
                p.say(0, "The thunder rolled all day, and the lightning went for the tallest roof.", '~');
                p.say(1, "And in the morning... the sun, and every one of us still here.", '!');
                p.say(0, "So we light the lanterns, every year, against the dark.", '~');
            }
            case FOUNDING -> {
                title = "How " + town + " Began";
                genre = "a history in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part("a founder", Kit.ELDER, null);
                p.part("another founder", Kit.PLAIN, null);
                p.say(0, "Long ago — well, day " + d + " — there was nothing here but grass.", '~');
                p.move(1, 0);
                p.say(1, "This is the place. Water, wood, good ground.", ' ');
                p.say(2, "It's a field.", '?');
                p.say(1, "It's a field today. Give it time.", '!');
                p.say(0, "And they gave it time, and here we all are.", '!');
            }
            case FIRE -> {
                title = "The Fire";
                genre = "a history in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part("a neighbour", Kit.PLAIN, null);
                p.part("the fire watch", Kit.PLAIN, null);
                p.say(0, "Day " + d + ". " + Culture.capital(t) + (t.endsWith("!") || t.endsWith(".") ? "" : "."), '~');
                p.say(1, "Fire! Fire! Bring the buckets!", '!');
                p.move(2, 3);
                p.say(2, "A chain, all of you — hand to hand, to the well and back!", '!');
                p.say(1, "It's going... it's going out!", '?');
                p.say(0, "Soot to the eyebrows, every one of them. But the town stood.", '!');
            }
            case FLOOD -> {
                String river = t.substring(0, Math.max(0, t.indexOf(" is rising")));
                title = "When " + (river.isEmpty() ? "the River" : river.startsWith("the ") ? river : Culture.capital(river)) + " Rose";
                genre = "a history in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part("a farmer", Kit.PLAIN, null);
                p.part("a child", Kit.PLAIN, null);
                p.say(0, "Day " + d + ". It rained, and it rained, and then it rained some more.", '~');
                p.say(2, "The water's at the door! It's in the kitchen!", '?');
                p.say(1, "Up the hill, all of you, and bring the hens!", '!');
                p.say(0, "And when the water went down, " + town + " swept out its houses and carried on.", '!');
            }
            case ILLNESS -> {
                title = "The Winter of Colds";
                genre = "a comedy in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part("a patient", Kit.PLAIN, null);
                p.part("the healer", Kit.PLAIN, null);
                p.say(0, "Day " + d + ". " + Culture.capital(t) + ".", '?');
                p.say(1, "Achoo! I'm dying. I'm surely dying.", '?');
                p.say(2, "You've a cold. Bed, broth, and stop your fussing.", '!');
                p.say(1, "...I feel a bit better, actually.", '!');
                p.say(0, "And they all got better, which is the best kind of ending.", '!');
            }
            case ELECTION -> {
                Matcher m = ELECTED.matcher(t);
                String who = m.find() ? m.group(1) : "the winner", office = m.groupCount() >= 2 && m.group(2) != null ? m.group(2) : "leader";
                title = "The Election of " + who;
                genre = "a comedy in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part(who, Kit.ELDER, real(village, who));
                p.part("a voter", Kit.PLAIN, null);
                p.say(0, "Day " + d + ", and " + town + " must choose its " + office + ".", '?');
                p.move(1, 0);
                p.say(1, "Vote for me, and there'll be bread on every table and no puddles in the square!", '!');
                p.say(2, "No puddles? I'll believe that when I see it.", '?');
                p.say(0, "The votes were counted, and " + who + " was elected " + office + ".", '!');
                p.say(2, "...There's still a puddle in the square.", '!');
            }
            default -> {
                title = "A Tale of Day " + d;
                genre = "a tale in one act";
                p.part("the teller", Kit.TELLER, null);
                p.part("a witness", Kit.PLAIN, null);
                p.part("a doubter", Kit.PLAIN, null);
                p.say(0, "Gather round for a true tale of day " + d + ".", ' ');
                p.say(1, Culture.capital(t) + (t.endsWith(".") || t.endsWith("!") ? "" : "."), '?');
                p.say(2, "No! Truly?", '?');
                p.say(1, "As sure as I stand on this stage.", '!');
                p.say(0, "And that's how it happened — near enough.", '!');
            }
        }
        return new Play(title, genre, t, e.day(), k, List.copyOf(p.parts), List.copyOf(p.beats), grief, "");
    }

    static String between(String s, String from, String to) {
        int a = s.indexOf(from);
        if (a < 0) return "";
        int b = s.indexOf(to, a + from.length());
        return b < 0 ? "" : s.substring(a + from.length(), b);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a play out of a line ("title, genre", then each beat as "SAY part: words", "FIGHT a b", "FALL a"...). */
    public static List<String> writeForTests(UUID village, long day, String line) {
        Play p = write(village, new Chronicle.Entry(day, line));
        List<String> out = new ArrayList<>();
        out.add(p.bill());
        for (Beat b : p.beats()) {
            String who = p.parts().get(b.part()).name();
            out.add(switch (b.what()) {
                case SAY -> "SAY " + who + ": " + b.text();
                case MOVE -> "MOVE " + who + " " + b.mark();
                case FIGHT -> "FIGHT " + who + " v " + p.parts().get(b.other()).name();
                case FALL -> "FALL " + who;
                case RISE -> "RISE " + who;
            });
        }
        for (Part part : p.parts()) out.add("PART " + part.name() + " " + part.kit() + (part.real() != null ? " real" : ""));
        return out;
    }
}
