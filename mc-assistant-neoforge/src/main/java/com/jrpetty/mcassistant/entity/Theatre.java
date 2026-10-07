package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.StairBlock;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The theatre [batchD] (Culture): an open-air stage (blueprints/theatre.txt), and the plays put on on it.
 *
 * <p><b>The building.</b> An Iron Age town of twenty with two or more folk who love a story, a tune or a bit
 * of carving (reading, music, whittling: Leisure's pastimes) wants one among its amenities: a raised wooden
 * stage with a back wall and the town's banner either side over it (Heraldry), steps up at either end, and
 * two rows of benches before it.
 *
 * <p><b>The play.</b> On the evening of the day of rest, after supper and unless it rains, two or three of
 * those players put on a short play, and the town comes to watch. The play is the town's own: a story out of
 * its chronicle, the weightiest it has not played yet — the night the raiders came at the gate, a wedding, a
 * birth, a death remembered, a building opened, a new age, the first diamond, the great storm, or how the town
 * began — told by its players in turn, in character (a teller, a guard and a raider; the bride and the groom),
 * a line every few seconds over their heads. The audience take the benches (the rest stand behind), face the
 * stage and react as a crowd does, and at the end the players bow and the town cheers. A player can come and
 * watch too. Into the town's history, everybody's memory and the books.
 */
public final class Theatre {

    private Theatre() {}

    public static final String STRUCTURE = "theatre";
    /** The town it takes: twenty folk, the Iron Age, and players enough. */
    static final int FROM_FOLK = 20;
    /** The play begins after supper, and not after this. */
    static final long START = 13000L, LATEST = 14000L;
    /** How long the players are given to get to their marks. */
    static final long CALL = 600L;
    /** The bows and the cheering. */
    static final long BOWS = 160L;

    /** The town's banner on the back wall, either side over the stage (in the drawing's terms: across, up, toward the back). */
    static final int[][] BANNERS = { { -2, 3, 4 }, { 2, 3, 4 } };
    /** The players' marks on the stage. */
    static final int[][] MARKS = { { 0, 1, 3 }, { -2, 1, 3 }, { 2, 1, 3 } };
    /** The benches, front row first: stairs with their backs to the street. */
    static final int[][] SEATS = { { -1, 0, -1 }, { 1, 0, -1 }, { -2, 0, -1 }, { 2, 0, -1 }, { -3, 0, -1 }, { 3, 0, -1 },
        { -1, 0, -3 }, { 1, 0, -3 }, { -2, 0, -3 }, { 2, 0, -3 }, { -3, 0, -3 }, { 3, 0, -3 } };

    // ------------------------------------------------------------------ wanting one

    /** A player at heart: a reader (a story), a musician (a performer), a whittler (an artist's hands). */
    static boolean player(VillageFolkEntity f) {
        if (f.isBaby() || !f.persona().rolled()) return false;
        Persona.Hobby h = f.persona().hobby();
        return h == Persona.Hobby.READING || h == Persona.Hobby.MUSIC || h == Persona.Hobby.WHITTLING;
    }

    static int players(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && player(f)) n++;
        return n;
    }

    /** Does the town want a theatre: twenty folk, the Iron Age or later, and two players at least? */
    public static boolean wanted(UUID village, int folk) {
        return folk >= FROM_FOLK && Villages.ageOf(village).ordinal() >= Villages.Age.IRON.ordinal() && players(village) >= 2;
    }

    /** Why the town wants it, in a line (Villages.whyBuild). */
    public static String why(UUID village) {
        return "a theatre, an open stage with benches before it, so the town's " + players(village)
            + " players can put on its own stories on the evening of the day of rest";
    }

    @Nullable
    static Ledger.Building of(UUID village) {
        return Culture.building(village, STRUCTURE);
    }

    // ------------------------------------------------------------------ the play

    /** One line of a play: whose (the part, counted from the teller), what, and how the house takes it. */
    record Line(int part, String text, char react) {}

    /** A play: its title, the story it tells (the chronicle's line and its day), its parts and its lines. */
    record Play(String title, String story, long storyDay, String[] parts, List<Line> lines) {}

    enum Story { RAID, WEDDING, BIRTH, DEATH, OPENED, AGE, FIND, STORM, FOUNDING, TALE }

    /** What kind of story a line of the chronicle is, or null for one no play is made of (the town's own doings at the theatre). */
    @Nullable
    static Story kind(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("players put on") || t.contains("resolved to keep") || t.contains("motto") || t.contains("banner")
            || t.contains("plaque") || t.contains("painted")) return null;
        if (t.contains("raiders came")) return Story.RAID;
        if (t.endsWith(" were wed")) return Story.WEDDING;
        if (t.contains(" had a child, ")) return Story.BIRTH;
        if (t.contains(" died")) return Story.DEATH;
        if (t.endsWith(" was opened")) return Story.OPENED;
        if (t.contains("came into the ")) return Story.AGE;
        if (t.contains("the town's first ")) return Story.FIND;
        if (t.contains("a great storm broke")) return Story.STORM;
        if (t.contains("was founded") || t.contains("founders came to")) return Story.FOUNDING;
        return Story.TALE;
    }

    /** The story tonight: the weightiest line of the chronicle not played yet, the latest of those; the founding if none. */
    static Chronicle.Entry story(UUID village, long day) {
        Set<String> played = new HashSet<>();
        for (String[] r : Culture.rows(village, "culture.plays")) if (r.length > 4) played.add(r[4]);
        Chronicle.Entry best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Chronicle.Entry e : Chronicle.of(village)) {
            Story k = kind(e.text());
            if (k == null) continue;
            int w = FoundingDay.weight(e.text());
            if (k == Story.TALE && w < 6) continue;
            double score = w + (k == Story.TALE ? 0 : 3) - Math.max(0, day - e.day()) / 14.0;
            if (played.contains(key(e))) score -= 100;
            if (score > bestScore) { bestScore = score; best = e; }
        }
        if (best == null) {
            long f = Math.max(0, FoundingDay.founded(village));
            best = new Chronicle.Entry(f, Villages.name(village) + " was founded");
        }
        return best;
    }

    static String key(Chronicle.Entry e) {
        return e.day() + ":" + Integer.toHexString(e.text().hashCode());
    }

    /** A play written out of a line of the town's chronicle. */
    static Play write(UUID village, Chronicle.Entry e) {
        String town = Villages.name(village);
        String t = e.text();
        long d = e.day() + 1;
        Story k = kind(t);
        if (k == null) k = Story.TALE;
        List<Line> l = new ArrayList<>();
        String title;
        String[] parts;
        switch (k) {
            case RAID -> {
                String gate = between(t, "at the ", " gate");
                boolean lost = t.contains("of the village fell");
                title = "The Night at the " + (gate.isEmpty() || gate.contains(" ") ? "" : Culture.capital(gate) + " ") + "Gate";
                parts = new String[]{ "the teller", "the guard", "the raider" };
                l.add(new Line(0, "Day " + d + " in " + town + ". The lamps are out, the town asleep... or is it?", '?'));
                l.add(new Line(1, "Who goes there? Show yourselves at the gate!", ' '));
                l.add(new Line(2, "Open up, little town, or we'll have your gate off its hinges!", '~'));
                l.add(new Line(1, "Ring the bell! To the wall, everybody — to the wall!", '!'));
                l.add(new Line(0, "And the bell rang out over " + town + ", and the watch came running.", ' '));
                l.add(new Line(2, "There's more of them than we were told! Fall back!", '?'));
                l.add(new Line(1, lost ? "They're running... but not all of ours are standing." : "They're running! The gate holds!", lost ? '~' : '!'));
                l.add(new Line(0, lost ? "So the gate held on day " + d + ", and we remember those who held it."
                    : "So the gate held on day " + d + ", and nobody lost. That's our watch!", lost ? '~' : '!'));
            }
            case WEDDING -> {
                String names = t.substring(0, t.length() - " were wed".length());
                int and = names.indexOf(" and ");
                String a = and > 0 ? names.substring(0, and) : names, b = and > 0 ? names.substring(and + 5) : "my love";
                title = "The Wedding of " + a + " and " + b;
                parts = new String[]{ "the teller", a, b };
                l.add(new Line(0, "Day " + d + ". Two folk in " + town + ", and everybody knew it before they did.", '?'));
                l.add(new Line(1, b + "... I've something to ask you, and I've practised it all week.", ' '));
                l.add(new Line(2, "Then ask it, " + a + ", before the bell goes!", '?'));
                l.add(new Line(1, "Will you have me — for better or worse, and the washing on a wet day?", ' '));
                l.add(new Line(2, "I will! A hundred times, I will!", '!'));
                l.add(new Line(0, "And the whole town came to the board to see them wed.", '!'));
                l.add(new Line(1, "To us!", '!'));
                l.add(new Line(2, "And to " + town + "!", '!'));
            }
            case BIRTH -> {
                int had = t.indexOf(" had a child, ");
                String parents = t.substring(0, had), child = t.substring(had + " had a child, ".length());
                int and = parents.indexOf(" and ");
                String a = and > 0 ? parents.substring(0, and) : parents, b = and > 0 ? parents.substring(and + 5) : "the midwife";
                title = child + " Comes Into the World";
                parts = new String[]{ "the teller", a, b };
                l.add(new Line(0, "Day " + d + ", and a long night at a little house in " + town + ".", '~'));
                l.add(new Line(1, "Is it here? Is it here yet?", '?'));
                l.add(new Line(2, "Not yet! Sit down, " + a + ", you'll wear a hole in the floor.", '?'));
                l.add(new Line(0, "And then, at last, a cry.", '~'));
                l.add(new Line(2, "Look — it's " + child + "!", '!'));
                l.add(new Line(1, "Welcome to " + town + ", little one. It's a good place.", '!'));
            }
            case DEATH -> {
                String name = t.substring(0, t.indexOf(" died"));
                title = name + ", Remembered";
                parts = new String[]{ "the teller", name, "a friend" };
                l.add(new Line(0, "This is the story of " + name + ", who lived among us.", '~'));
                l.add(new Line(1, "Morning! Lovely day for it, whatever it is.", ' '));
                l.add(new Line(2, "That was " + name + " all over. Never a day without a word for you.", '?'));
                l.add(new Line(1, "Look after the place for me, will you? It's a good town.", '~'));
                l.add(new Line(0, name + " left us on day " + d + ". We don't forget.", '~'));
            }
            case OPENED -> {
                String what = t.substring(0, t.length() - " was opened".length());
                title = "The Opening of " + Culture.capital(what.startsWith("the ") ? what.substring(4) : what);
                parts = new String[]{ "the teller", "the builder", "a neighbour" };
                l.add(new Line(0, "Day " + d + ". Planks, stone and sore backs: " + what + " is going up.", ' '));
                l.add(new Line(1, "Mind that beam! And who's had the last of the nails?", '?'));
                l.add(new Line(2, "Is it finished yet? You said it'd be done by Tuesday.", '?'));
                l.add(new Line(1, "We haven't got Tuesdays! It's done when it's done.", '!'));
                l.add(new Line(0, "And on day " + d + ", " + what + " was opened, and the town came to see.", '!'));
                l.add(new Line(2, "Well. It was worth the wait.", '!'));
            }
            case AGE -> {
                String age = t.substring(t.indexOf("came into ") + "came into ".length());
                title = "Into " + Culture.capital(age);
                parts = new String[]{ "the teller", "an old hand", "a youngster" };
                l.add(new Line(0, "Day " + d + ": the day " + town + " came into " + age + ".", '!'));
                l.add(new Line(1, "When I was young we had sticks and stones, and were glad of them.", '?'));
                l.add(new Line(2, "And now look at us!", '!'));
                l.add(new Line(1, "Aye. Now look at us.", '~'));
                l.add(new Line(0, "And " + town + " never looked back.", '!'));
            }
            case FIND -> {
                int the = t.indexOf(" the ");
                String finder = the > 0 ? t.substring(0, the) : "a miner";
                String thing = t.substring(t.indexOf("the town's first ") + "the town's first ".length());
                title = Culture.capital(finder) + " and the " + Culture.capital(thing);
                parts = new String[]{ "the teller", finder, "the foreman" };
                l.add(new Line(0, "Day " + d + ". Deep under " + town + ", " + finder + " swings a pick, as on any other day.", ' '));
                l.add(new Line(1, "Wait... what's that, glinting in the rock?", '?'));
                l.add(new Line(2, "More coal. It's always more coal.", '?'));
                l.add(new Line(1, "It's not coal. Look at it!", '!'));
                l.add(new Line(2, "Well, I'll be... it's " + (thing.isEmpty() ? "treasure" : "a " + thing) + "!", '!'));
                l.add(new Line(0, "And that is how " + town + " found its first " + thing + ".", '!'));
            }
            case STORM -> {
                title = "The Great Storm";
                parts = new String[]{ "the teller", "a farmer", "a guard" };
                l.add(new Line(0, "Day " + d + ". The sky over " + town + " goes black at noon.", '~'));
                l.add(new Line(1, "My wheat! The wind'll have it flat!", '?'));
                l.add(new Line(2, "Indoors, everyone! Leave it — indoors!", '!'));
                l.add(new Line(0, "The thunder rolled all day, and the lightning went for the tallest roof.", '~'));
                l.add(new Line(1, "And in the morning... the sun, and every one of us still here.", '!'));
                l.add(new Line(0, "So we light the lanterns, every year, against the dark.", '~'));
            }
            case FOUNDING -> {
                title = "How " + town + " Began";
                parts = new String[]{ "the teller", "a founder", "another founder" };
                l.add(new Line(0, "Long ago — well, day " + d + " — there was nothing here but grass.", '~'));
                l.add(new Line(1, "This is the place. Water, wood, good ground.", ' '));
                l.add(new Line(2, "It's a field.", '?'));
                l.add(new Line(1, "It's a field today. Give it time.", '!'));
                l.add(new Line(0, "And they gave it time, and here we all are.", '!'));
            }
            default -> {
                title = "A Tale of Day " + d;
                parts = new String[]{ "the teller", "a witness", "a doubter" };
                l.add(new Line(0, "Gather round for a true tale of day " + d + ".", ' '));
                l.add(new Line(1, Culture.capital(t) + (t.endsWith(".") || t.endsWith("!") ? "" : "."), '?'));
                l.add(new Line(2, "No! Truly?", '?'));
                l.add(new Line(1, "As sure as I stand on this stage.", '!'));
                l.add(new Line(0, "And that's how it happened — near enough.", '!'));
            }
        }
        return new Play(title, t, e.day(), parts, List.copyOf(l));
    }

    private static String between(String s, String from, String to) {
        int a = s.indexOf(from);
        if (a < 0) return "";
        int b = s.indexOf(to, a + from.length());
        return b < 0 ? "" : s.substring(a + from.length(), b);
    }

    // ------------------------------------------------------------------ the evening

    enum Phase { CALL, PLAY, BOWS, DONE }

    /** Tonight's play in one town: its players, who came, and how far on it is. */
    static final class Show {
        final long day;
        final Play play;
        final List<UUID> cast = new ArrayList<>();
        final List<String> castNames = new ArrayList<>();
        final Map<UUID, Integer> seats = new HashMap<>();
        final Set<UUID> watched = new HashSet<>();
        final List<String> said = new ArrayList<>();
        Phase phase = Phase.CALL;
        long phaseAt, nextAt;
        int next;
        boolean closed;

        Show(long day, Play play) {
            this.day = day;
            this.play = play;
        }

        /** Who says a part: the teller is the first player; with two players the third part is doubled by the teller. */
        int actorFor(int part) {
            return Math.min(part % Math.max(1, cast.size()), cast.size() - 1);
        }
    }

    private static final Map<UUID, Show> SHOWS = new ConcurrentHashMap<>();
    /** The rest day each town last had (or let go) its play. */
    private static final Map<UUID, Long> PLAYED = new ConcurrentHashMap<>();

    static void resetForTests() {
        SHOWS.clear();
        PLAYED.clear();
    }

    /** Is a play on in this town just now (from the call to the bows)? The band waits for it (Music). */
    static boolean on(UUID village) {
        Show s = SHOWS.get(village);
        return s != null && s.phase != Phase.DONE;
    }

    /** Is tonight's play on, or still to come (a theatre standing, the day of rest, before the latest it begins)? The band waits for it. */
    static boolean onOrToCome(ServerLevel level, UUID village) {
        if (on(village)) return true;
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        return of(village) != null && RestDay.today(village, day) && t < LATEST && PLAYED.getOrDefault(village, -1L) != day;
    }

    /** The players tonight: two or three of those who love a story, a tune or a carving, the readers first. */
    static List<VillageFolkEntity> cast(ServerLevel level, UUID village, long day) {
        List<VillageFolkEntity> all = new ArrayList<>();
        Map<VillageFolkEntity, Integer> score = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || !player(f) || f.isShowcase() || !Culture.free(f, level)) continue;
            int s = switch (f.persona().hobby()) {
                case READING -> 30;
                case MUSIC -> 25;
                default -> 20;
            };
            if (f.life().has(Social.Trait.SOCIABLE)) s += 15;
            if (f.life().has(Social.Trait.CHEERFUL)) s += 10;
            if (f.life().has(Social.Trait.SHY)) s -= 25;
            s += Math.floorMod((int) (day * 31L) + f.getUUID().hashCode(), 12);
            score.put(f, s);
            all.add(f);
        }
        all.sort(Comparator.comparingInt((VillageFolkEntity f) -> -score.get(f)).thenComparing(f -> f.getUUID()));
        return all.subList(0, Math.min(3, all.size()));
    }

    /** Tonight's play cast and written; null if there are not two players to put it on. */
    @Nullable
    static Show begin(ServerLevel level, Villages.Village v, long day) {
        List<VillageFolkEntity> cast = cast(level, v.id(), day);
        if (cast.size() < 2) return null;
        Show s = new Show(day, write(v.id(), story(v.id(), day)));
        for (VillageFolkEntity f : cast) {
            s.cast.add(f.getUUID());
            s.castNames.add(f.displayNameCap());
        }
        s.phaseAt = level.getGameTime();
        SHOWS.put(v.id(), s);
        return s;
    }

    /** The town's look at the theatre (Culture, each second): tonight's play begun, and moved on. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L, now = level.getGameTime();
        Show s = SHOWS.get(id);
        if (s != null && s.day != day) {
            SHOWS.remove(id, s);
            s = null;
        }
        if (s == null) {
            Ledger.Building b = of(id);
            if (b == null || !level.isLoaded(b.anchor()) || !RestDay.today(id, day) || t < START || t >= LATEST) return;
            if (PLAYED.getOrDefault(id, -1L) == day || level.isRaining() || Raids.underAlarm(id)) return;
            PLAYED.put(id, day);
            s = begin(level, v, day);
            if (s == null) return;
        }
        step(level, v, s, now, false);
    }

    /** The play moved on: the players to their marks, a line at a time, the bows. {@code force}: no waiting (the tests). */
    static void step(ServerLevel level, Villages.Village v, Show s, long now, boolean force) {
        Ledger.Building b = of(v.id());
        switch (s.phase) {
            case CALL -> {
                int there = 0;
                for (int i = 0; i < s.cast.size(); i++) {
                    if (b != null && level.getEntity(s.cast.get(i)) instanceof VillageFolkEntity f
                        && f.blockPosition().distSqr(mark(b, i)) <= 2.5 * 2.5) there++;
                }
                boolean ready = there >= s.cast.size() || now - s.phaseAt > CALL && there >= 2;
                if (force || ready) {
                    s.phase = Phase.PLAY;
                    s.phaseAt = now;
                    s.nextAt = now + 40;
                    if (b != null) level.playSound(null, mark(b, 0), SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 1.5F, 1.4F);
                } else if (now - s.phaseAt > CALL * 2) {
                    s.phase = Phase.DONE;                                   // the players never came: no play tonight
                }
            }
            case PLAY -> {
                while (s.next < s.play.lines().size() && (force || now >= s.nextAt)) {
                    Line l = s.play.lines().get(s.next++);
                    int who = s.actorFor(l.part());
                    VillageFolkEntity f = level.getEntity(s.cast.get(who)) instanceof VillageFolkEntity g ? g : null;
                    if (f != null) {
                        FolkTalk.speak(f, l.text());
                        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    }
                    s.said.add(s.play.parts()[Math.min(l.part(), s.play.parts().length - 1)] + ": " + l.text());
                    react(level, s, l.react());
                    s.nextAt = now + Math.max(70, 40 + l.text().length() * 2L);
                    if (!force) break;
                }
                if (s.next >= s.play.lines().size() && (force || now >= s.nextAt)) {
                    s.phase = Phase.BOWS;
                    s.phaseAt = now;
                    close(level, v, s);
                }
            }
            case BOWS -> {
                if (force || now - s.phaseAt > BOWS) s.phase = Phase.DONE;
            }
            case DONE -> { }
        }
    }

    /** How the house takes a line: a cheer, a murmur, a hush. */
    private static void react(ServerLevel level, Show s, char how) {
        RandomSource r = level.getRandom();
        int voices = 0;
        for (UUID u : s.watched) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
            switch (how) {
                case '!' -> {
                    if (voices < 2 && r.nextInt(3) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(r, "Hooray!", "Bravo!", "Ha! I remember that!", "Well said!"));
                        voices++;
                    }
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 1.6, f.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
                }
                case '?' -> {
                    if (voices < 1 && r.nextInt(4) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(r, "Ooh.", "Ha!", "That's right, it was!", "Shh!"));
                        voices++;
                    }
                }
                case '~' -> f.setXRot(20.0F);
                default -> { }
            }
        }
    }

    /** The bows: the house cheers; into the town's history, the players' and the audience's memories and the books. */
    private static void close(ServerLevel level, Villages.Village v, Show s) {
        if (s.closed) return;
        s.closed = true;
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        RandomSource r = level.getRandom();
        List<String> as = new ArrayList<>();
        for (int i = 0; i < s.cast.size(); i++) {
            int part = Math.min(i, s.play.parts().length - 1);
            if (!(level.getEntity(s.cast.get(i)) instanceof VillageFolkEntity f)) continue;
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            f.persona().remember(day, "I played " + s.play.parts()[part] + " in “" + s.play.title() + "” at the theatre", 4);
            f.persona().enjoyedHobby(day);
            as.add(f.displayNameCap() + " as " + s.play.parts()[part]);
            if (i == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Thank you, thank you all!", "That's our play. Thank you for coming!"));
        }
        for (UUID u : s.watched) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
            f.persona().remember(day, "I saw “" + s.play.title() + "” at the theatre", 2);
            f.swing(net.minecraft.world.InteractionHand.OFF_HAND);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 2.0, f.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
            for (UUID c : s.cast) {
                if (level.getEntity(c) instanceof VillageFolkEntity p) f.life().feel(p.getUUID(), p.displayNameCap(), 1);
            }
        }
        Ledger.Building b = of(id);
        if (b != null) level.playSound(null, mark(b, 0), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.8F, 1.2F);
        Villages.tell(id, day, "the players put on “" + s.play.title() + "” at the theatre (" + String.join(", ", as) + "), and "
            + s.watched.size() + (s.watched.size() == 1 ? " came" : " came") + " to watch");
        List<String[]> rows = Culture.rows(id, "culture.plays");
        rows.add(new String[]{ Long.toString(day), s.play.title(), String.join(", ", as), Integer.toString(s.watched.size()), key(new Chronicle.Entry(s.play.storyDay(), s.play.story())) });
        while (rows.size() > 8) rows.remove(0);
        Culture.rows(id, "culture.plays", rows);
    }

    // ------------------------------------------------------------------ the folk's part

    static BlockPos mark(Ledger.Building b, int i) {
        int[] m = MARKS[Math.min(i, MARKS.length - 1)];
        return Culture.at(b, m[0], m[1], m[2]);
    }

    /** A folk's part in tonight's play: a player to its mark, the rest to a bench (or behind them), facing the stage. */
    static boolean hold(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        Show s = SHOWS.get(v.id());
        if (s == null || s.phase == Phase.DONE) return false;
        Ledger.Building b = of(v.id());
        if (b == null) return false;
        UUID me = f.getUUID();
        BlockPos front = Culture.at(b, 0, 1, -5);
        int part = s.cast.indexOf(me);
        if (part >= 0) {
            int p = Math.min(part, s.play.parts().length - 1);
            Culture.mark(f, Culture.Role.ACTOR, "playing " + s.play.parts()[p] + " in “" + s.play.title() + "” at the theatre");
            if (Culture.arrive(f, mark(b, part), 0.8, 1.0)) f.getLookControl().setLookAt(front.getX() + 0.5, front.getY() + 1.0, front.getZ() + 0.5);
            return true;
        }
        if (Assemblies.attending(f) || f.blockPosition().distSqr(b.anchor()) > 80.0 * 80.0) return false;
        Culture.Held h = Culture.mark(f, Culture.Role.AUDIENCE, "watching “" + s.play.title() + "” at the theatre");
        Integer seat = s.seats.get(me);
        if (seat == null) {
            seat = freeSeat(level, b, s);
            s.seats.put(me, seat);
        }
        BlockPos stage = mark(b, 0);
        float yaw = b.facing().toYRot();
        if (seat >= 0) {
            BlockPos p = Culture.at(b, SEATS[seat][0], SEATS[seat][1], SEATS[seat][2]);
            if (!h.seated && Culture.arrive(f, p, 1.2, 0.9) && Seats.seat(level, p, level.getBlockState(p))) Culture.sit(f, h, p, yaw);
        } else {
            int n = -seat - 1;
            BlockPos p = Culture.at(b, (n % 7) - 3, 0, -5 - n / 7);
            if (Culture.arrive(f, p, 1.0, 0.9)) f.getLookControl().setLookAt(stage.getX() + 0.5, stage.getY() + 1.5, stage.getZ() + 0.5);
        }
        if (h.seated) f.getLookControl().setLookAt(stage.getX() + 0.5, stage.getY() + 1.5, stage.getZ() + 0.5);
        if (s.phase == Phase.PLAY || s.phase == Phase.BOWS) s.watched.add(me);
        return true;
    }

    /** A bench for one more of the audience (its index), or a place standing behind them (counted down from -1). */
    private static int freeSeat(ServerLevel level, Ledger.Building b, Show s) {
        Set<Integer> taken = new HashSet<>(s.seats.values());
        for (int i = 0; i < SEATS.length; i++) {
            if (taken.contains(i)) continue;
            BlockPos p = Culture.at(b, SEATS[i][0], SEATS[i][1], SEATS[i][2]);
            if (level.getBlockState(p).getBlock() instanceof StairBlock) return i;
        }
        int standing = 0;
        for (int i : taken) if (i < 0) standing++;
        return -1 - standing;
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: tonight's play begun now, whatever the day or the hour (the players cast, the play written). Its title, or null. */
    @Nullable
    public static String beginForTests(ServerLevel level, Villages.Village v) {
        SHOWS.remove(v.id());
        Show s = begin(level, v, level.getDayTime() / 24000L);
        return s == null ? null : s.play.title();
    }

    /** Tests: the play under way moved on now, as the town's look moves it (force: the whole of it at once). */
    public static void stepForTests(ServerLevel level, Villages.Village v, boolean force) {
        Show s = SHOWS.get(v.id());
        if (s != null) step(level, v, s, level.getGameTime(), force);
    }

    /** Tests: the players cast tonight, by name. */
    public static List<String> castForTests(UUID village) {
        Show s = SHOWS.get(village);
        return s == null ? List.of() : List.copyOf(s.castNames);
    }

    /** Tests: every line said so far, "part: words", in order. */
    public static List<String> saidForTests(UUID village) {
        Show s = SHOWS.get(village);
        return s == null ? List.of() : List.copyOf(s.said);
    }

    /** Tests: the play's lines, in order, as written. */
    public static List<String> scriptForTests(UUID village) {
        Show s = SHOWS.get(village);
        List<String> out = new ArrayList<>();
        if (s != null) for (Line l : s.play.lines()) out.add(l.text());
        return out;
    }

    /** Tests: the play's phase ("CALL", "PLAY", "BOWS", "DONE") and how many watched, or null with none. */
    @Nullable
    public static String phaseForTests(UUID village) {
        Show s = SHOWS.get(village);
        return s == null ? null : s.phase.name() + " watched=" + s.watched.size();
    }

    /** Tests: a play written out of this line of the chronicle: its title, then its lines. */
    public static List<String> writeForTests(UUID village, long day, String line) {
        Play p = write(village, new Chronicle.Entry(day, line));
        List<String> out = new ArrayList<>();
        out.add(p.title());
        for (Line l : p.lines()) out.add(l.text());
        return out;
    }

    // ------------------------------------------------------------------ the stage

    /**
     * /village culture stage: a theatre set out where the operator stands, the town's banners on its back wall,
     * three players on the stage in the middle of a play out of the town's chronicle and an audience on the
     * benches; says where to look from.
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at) {
        int x = at.getX(), y = at.getY(), z = at.getZ();
        com.jrpetty.mcassistant.Showcase.stage(level, x - 7, x + 7, z - 9, z + 14, y);
        Direction back = Direction.NORTH;
        BuildGoal.stamp(level, STRUCTURE, at, back, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.Building b = new Ledger.Building(STRUCTURE, at.immutable(), back);
        Heraldry.choose(level, v);
        Heraldry.Design d = Heraldry.design(v.id());
        if (d == null) d = Heraldry.draw(v.id());
        for (int[] c : BANNERS) Decor.hangBanner(level, Culture.at(b, c[0], c[1], c[2]), back.getOpposite(), Heraldry.item(level, v.id(), d));
        Play play = write(v.id(), story(v.id(), level.getDayTime() / 24000L));
        String[] names = { "Wren", "Tom", "Pip", "Ada", "Nell", "Kit", "Bram", "Sal", "Ivy", "Hal", "Moss" };
        List<VillageFolkEntity> players = new ArrayList<>();
        float toAudience = back.getOpposite().toYRot();
        for (int i = 0; i < 3; i++) {
            VillageFolkEntity f = standIn(level, mark(b, i), toAudience, false, names[i]);
            if (f != null) players.add(f);
        }
        for (int i = 0; i < 8 && i < SEATS.length; i++) {
            BlockPos p = Culture.at(b, SEATS[i][0], SEATS[i][1], SEATS[i][2]);
            VillageFolkEntity f = standIn(level, p, back.toYRot(), i % 4 == 3, names[3 + i]);
            if (f != null) f.setPose(Pose.SITTING);
        }
        if (!players.isEmpty() && !play.lines().isEmpty()) FolkTalk.speak(players.get(0), play.lines().get(0).text());
        List<String> out = new ArrayList<>();
        out.add("THEATRE " + x + " " + y + " " + z + " playing “" + play.title() + "”");
        BlockPos stage = mark(b, 0);
        // From the street behind the benches, over the audience's heads to the stage and its banners.
        out.add("VIEW theatre-house " + x + " " + (y + 3) + " " + (z + 11) + " " + stage.getX() + " " + (stage.getY() + 1) + " " + stage.getZ());
        // From the side of the stage, along the players to the benches.
        out.add("VIEW theatre-stage " + (x + 7) + " " + (y + 3) + " " + (z - 4) + " " + x + " " + (y + 1) + " " + (z + 1));
        return out;
    }

    @Nullable
    private static VillageFolkEntity standIn(ServerLevel level, BlockPos pos, float yaw, boolean child, String name) {
        VillageFolkEntity f = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        double up = level.getBlockState(pos).getBlock() instanceof StairBlock ? 0.5 : 0.0;
        f.moveTo(pos.getX() + 0.5, pos.getY() + up, pos.getZ() + 0.5, yaw, 0.0F);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.makeShowcase(AssistantEntity.StationTask.NONE);
        if (child) f.setChild(true);
        f.rename(name);
        f.addTag("folk_lineup");
        return level.addFreshEntity(f) ? f : null;
    }

    // ------------------------------------------------------------------ what the player reads

    /** The folk's card: the last part it played. */
    @Nullable
    static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return null;
        String me = f.displayNameCap() + " as ";
        List<String[]> rows = Culture.rows(id, "culture.plays");
        for (int i = rows.size() - 1; i >= 0; i--) {
            String[] r = rows.get(i);
            if (r.length < 3) continue;
            for (String one : r[2].split(", ")) {
                if (one.startsWith(me)) return "played " + one.substring(me.length()) + " in “" + r[1] + "” on day " + (Culture.num(r[0], 0) + 1);
            }
        }
        return player(f) && of(id) != null ? "one of the town's players" : null;
    }

    /** The board's line: tonight's play, or the one on now. */
    @Nullable
    static String boardLine(ServerLevel level, UUID village) {
        Show s = SHOWS.get(village);
        if (s != null && s.phase != Phase.DONE) {
            return "At the theatre now: “" + s.play.title() + "”, with " + String.join(", ", s.castNames) + " — come and watch!";
        }
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        if (of(village) != null && RestDay.today(village, day) && t < LATEST && PLAYED.getOrDefault(village, -1L) != day) {
            return "At the theatre tonight, after supper: a play by the town's players.";
        }
        return null;
    }

    /** The Culture page's theatre: whether it stands (or why it is wanted), its players, the play tonight, the plays so far. */
    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        CompoundTag out = new CompoundTag();
        Ledger.Building b = of(id);
        out.putBoolean("built", b != null);
        int folk = Villages.headcount(id);
        out.putBoolean("wanted", b == null && wanted(id, folk));
        List<String> players = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && player(f)) players.add(f.displayNameCap() + " (" + f.persona().hobby().word + ")");
        }
        out.put("players", Culture.strings(players));
        long next = -1;
        for (long d = day; d < day + 8; d++) if (RestDay.today(id, d)) { next = d; break; }
        out.putLong("next", next < 0 ? -1 : next + 1);
        Show s = SHOWS.get(id);
        out.putString("now", s != null && s.phase != Phase.DONE ? "“" + s.play.title() + "” with " + String.join(", ", s.castNames) : "");
        List<String> plays = new ArrayList<>();
        List<String[]> rows = Culture.rows(id, "culture.plays");
        for (int i = rows.size() - 1; i >= 0; i--) {
            String[] r = rows.get(i);
            if (r.length < 4) continue;
            plays.add("Day " + (Culture.num(r[0], 0) + 1) + ": “" + r[1] + "” — " + r[2] + "; " + r[3] + " watched");
        }
        out.put("plays", Culture.strings(plays));
        List<String> lines = new ArrayList<>();
        if (b != null) lines.add("the theatre stands" + (next >= 0 ? "; the next play on day " + (next + 1) + ", the day of rest" : ""));
        else lines.add(wanted(id, folk) ? "wanted: " + why(id) : "not yet: it wants twenty folk in the Iron Age and two players ("
            + players.size() + " now)");
        if (!out.getString("now").isEmpty()) lines.add("on now: " + out.getString("now"));
        lines.addAll(plays);
        out.put("lines", Culture.strings(lines));
        return out;
    }
}
