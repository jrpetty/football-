package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The travelling bard. [batchG] Every few days a town of fifteen or more with a tavern has a bard walk in
 * from the edge of the world, a stranger with a bedroll on its back and a head full of songs.
 * <ul>
 * <li>It makes for the tavern, says who it is, and the chronicle takes it down.</li>
 * <li><b>Of an evening</b> it stands by the tavern's hearth and plays (a phrase at a time, on its own
 *     instrument, the notes rising over its head) and between tunes tells what it has seen: the news of the
 *     towns round about, real lines out of their own chronicles ("From Oakford, three hundred blocks east:
 *     the smithy went up there, on day 12"), or, with no town near, one of its tales. Word goes round: with
 *     a bard in, the whole town comes to the tavern, not two evenings in five (Tavern.evening).</li>
 * <li>Whoever hears it is the happier for it, that day and the next ("There's a bard at the tavern — what
 *     songs!"), and remembers it. Somebody always calls out for another.</li>
 * <li>By day it sees the town and busks on the square; a folk who likes the song may put a coin in its hat,
 *     out of its own purse, once a visit.</li>
 * <li>It takes a room at the town's inn, if it has one with a keeper and a bed free, and pays for it as any
 *     traveller does (Inn: three coins a night, out of the price of its rooms it brings); else it sleeps at the
 *     tavern on its own bedroll by the wall, rolled up again in the morning.</li>
 * <li>After two or three nights it goes on its way, and the chronicle says so.</li>
 * </ul>
 */
public final class Bard {

    private Bard() {}

    /** The least a town is for a bard to come. */
    static final int TOWN = 15;
    /** The bard's tunes: the tavern's own jig, and the slow air for late. */
    private static final int[][] TUNES = { Tavern.JIG, Tavern.AIR };

    /** When each bard last played a phrase, last spoke, and the listeners it has had this visit (by bard). */
    private static final Map<UUID, Long> PLAYED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SPOKE = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<UUID>> HEARD_BY = new ConcurrentHashMap<>();
    /** Who has put a coin in the bard's hat this visit, and how many it has had today. */
    private static final Map<UUID, Set<UUID>> TIPPED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> BEAT = new ConcurrentHashMap<>();
    /** Where each bard is strolling to by day. */
    private static final Map<UUID, BlockPos> STROLL = new ConcurrentHashMap<>();

    static void resetForTests() {
        PLAYED.clear();
        SPOKE.clear();
        HEARD_BY.clear();
        TIPPED.clear();
        BEAT.clear();
        STROLL.clear();
    }

    private static final String[] TALES = {
        "the tale of the miner who dug so deep he heard the Nether singing under his feet",
        "the song of the shepherd who counted her sheep wrong for forty years and never lost one",
        "the story of the town that built its well on a hill, and carried water up it for a hundred days",
        "the ballad of the guard who stood his post through three storms and a creeper",
        "the tale of the baker whose bread was so good the zombies queued for it",
        "the song of the two smiths who married, and the anvil they made between them"
    };

    // ------------------------------------------------------------------ coming

    /** Should a bard come today (the town's round)? A town of fifteen with a tavern, every four to six days. */
    static void consider(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        if (t < 2000L || t > 8000L) return;
        if (Villages.headcount(id) < TOWN || Tavern.of(id) == null) return;
        if (Raids.underAlarm(id) || Weather.stormy(level)) return;
        long last = parse(Ledger.note(id, "visit/bard"));
        if (last >= 0 && day >= last && day - last < gap(id, last)) return;
        if (!Visitors.inTown(level, id, Visitors.Kind.BARD).isEmpty()) return;
        come(level, v, day);
    }

    /** Days between bards: four to six, as it falls. */
    static int gap(UUID id, long last) {
        return 4 + Math.floorMod(id.hashCode() * 31 + (int) last, 3);
    }

    static long parse(@Nullable String s) {
        if (s == null || s.isEmpty()) return -1;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * A bard comes in from the edge: two or three nights, with its bedroll, a loaf or two of its own, and the price of a
     * room at an inn for each night (Inn.ROOM): a traveller's few coins, from outside, as a tourist's purse is.
     */
    @Nullable
    static VillageFolkEntity come(ServerLevel level, Villages.Village v, long day) {
        int nights = 2 + Math.floorMod(v.id().hashCode() + (int) day, 2);
        List<ItemStack> kit = List.of(new ItemStack(Items.RED_BED), new ItemStack(Items.BREAD, 3));
        VillageFolkEntity f = Visitors.arrive(level, v, Visitors.Kind.BARD, day, nights, Inn.ROOM * nights, kit,
            "a travelling bard from far away");
        if (f != null) Ledger.note(v.id(), "visit/bard", Long.toString(day));
        return f;
    }

    /** /village visitors bard, and the game tests: a bard comes now, whatever the day. */
    @Nullable
    static VillageFolkEntity comeForTests(ServerLevel level, Villages.Village v) {
        return come(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: a bard comes to this town now (it still walks in from the edge). */
    @Nullable
    public static VillageFolkEntity arriveNowForTests(ServerLevel level, Villages.Village v) {
        return comeForTests(level, v);
    }

    /** At the tavern: it says who it is, and the town hears of it. */
    static void arrived(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, long day) {
        String name = Villages.name(town.id());
        Villages.tell(town.id(), day, v.name + ", a travelling bard, came to " + name + " and took a seat at the tavern");
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Good folk of " + name + "! I've songs and news for anybody who'll sit with me by the fire tonight.",
            "A bard, come a long way! Tonight, at the tavern: tunes, tales and the news from the towns about."));
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().closerThan(town.centre(), 128)) {
                p.displayClientMessage(Component.literal("A travelling bard has come to " + name + ": " + v.name
                    + " plays at the tavern tonight."), true);
            }
        }
        HEARD_BY.remove(f.getUUID());
        TIPPED.remove(f.getUUID());
    }

    /** Is a bard in this town and playing its evenings (Tavern.evening: the whole town comes)? */
    public static boolean playing(ServerLevel level, @Nullable UUID town) {
        return town != null && Visitors.here(town, Visitors.Kind.BARD, level.getGameTime());
    }

    // ------------------------------------------------------------------ its stay

    /**
     * A look at the bard's stay, every half second (Visitors): its evenings at the tavern, its nights at the
     * inn, its days about the town. True when it is time it was going (the morning of its last day).
     */
    static boolean stay(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, long day, long t) {
        Ledger.Building tav = Tavern.of(town.id());
        if (tav == null) return true;                          // the tavern is gone: nothing to stay for
        if (day >= v.leave && t >= 1000L && t < 11000L) return true;
        if (t >= 12500L && t < 17500L) {
            Visitors.rise(level, f, v);
            perform(level, town, f, v, tav, day, t);
            return false;
        }
        if (t >= 17500L && t < 23000L) {
            // A room at the inn, paid for out of what it brought (Inn); with no inn, no room or no coin, its bedroll
            // at the tavern; with no room for that, it sits up by the fire.
            if (Visitors.lodge(level, f)) return false;
            if (!Visitors.bedDown(level, f, v, tav)) {
                // No bed and no room for its bedroll: it sits up by the fire.
                BlockPos fire = tav.anchor().relative(tav.facing(), 2);
                Visitors.walk(f, level, fire, 1.5, 0.7D, v.walk);
            }
            return false;
        }
        Visitors.rise(level, f, v);
        stroll(level, town, f, v, day);
        return false;
    }

    /** Where the bard stands to play: by the hearth, facing down the room to the door. */
    static BlockPos stage(Ledger.Building tav) {
        return tav.anchor().relative(tav.facing(), 2);
    }

    /** An evening at the tavern: a phrase of a tune every few seconds, a story or the news between, the room listening. */
    static void perform(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, Ledger.Building tav, long day, long t) {
        BlockPos at = stage(tav);
        if (!Visitors.walk(f, level, at, 1.5, 0.8D, v.walk)) return;
        BlockPos door = tav.anchor().relative(tav.facing().getOpposite(), 3);
        f.getLookControl().setLookAt(door.getX() + 0.5, door.getY() + 1.5, door.getZ() + 0.5);
        long now = level.getGameTime();
        if (now - PLAYED.getOrDefault(f.getUUID(), -1000L) >= 40L) {
            PLAYED.put(f.getUUID(), now);
            phrase(level, f, t);
            listen(level, town, f, v, day);
        }
        if (now - SPOKE.getOrDefault(f.getUUID(), -1000L) >= 500L) {
            SPOKE.put(f.getUUID(), now);
            RandomSource r = f.getRandom();
            String said = r.nextInt(3) == 0 ? "Here's one for you: " + TALES[r.nextInt(TALES.length)] + "!" : newsLine(level, town, r);
            FolkTalk.speak(f, said);
            answer(level, town, f, r);
        }
    }

    /** Four notes of its tune, on its own instrument (the jig early, the air late), the notes rising over its head. */
    static void phrase(ServerLevel level, VillageFolkEntity f, long t) {
        int[] tune = TUNES[t < 15000L ? 0 : 1];
        int beat = BEAT.merge(f.getUUID(), 4, Integer::sum);
        Holder<SoundEvent> voice = switch (Math.floorMod(f.getUUID().hashCode(), 3)) {
            case 0 -> SoundEvents.NOTE_BLOCK_HARP;
            case 1 -> SoundEvents.NOTE_BLOCK_GUITAR;
            default -> SoundEvents.NOTE_BLOCK_FLUTE;
        };
        for (int i = 0; i < 4; i++) {
            int note = tune[Math.floorMod(beat + i, tune.length)];
            if (note < 0) continue;
            float pitch = (float) Math.pow(2.0, (note - 12) / 12.0);
            level.playSound(null, f.blockPosition(), voice.value(), SoundSource.RECORDS, 1.0F, pitch);
            level.sendParticles(ParticleTypes.NOTE, f.getX(), f.getY() + 2.2 + i * 0.15, f.getZ(), 0, note / 24.0, 0.0, 0.0, 1.0);
        }
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    /** The town's folk within earshot (awake, in the room) hear it: the better for it, and they remember. */
    static int listen(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, long day) {
        int n = 0;
        Set<UUID> heard = HEARD_BY.computeIfAbsent(f.getUUID(), k -> ConcurrentHashMap.newKeySet());
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(9.0, 3.0, 9.0),
                o -> o != f && o.isAlive() && !o.isSleeping() && town.id().equals(o.ownerId()))) {
            Visitors.heard(o, day);
            n++;
            if (heard.add(o.getUUID()) && o.persona().rolled()) {
                o.persona().remember(day, "heard " + v.name + " the bard play at the tavern", 3);
            }
        }
        return n;
    }

    /** Somebody in the room has a word to say to it. */
    private static void answer(ServerLevel level, Villages.Village town, VillageFolkEntity f, RandomSource r) {
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(8.0, 3.0, 8.0),
                o -> o != f && o.isAlive() && !o.isSleeping() && town.id().equals(o.ownerId()))) {
            o.sayLater(FolkTalk.pick(r, "Another! Play another!", "Is that true? Never!", "I've cousins there, you know.",
                "Ha! Tell it again!", "That's a sad one.", "Play the one about the creeper!"), 40 + r.nextInt(40));
            return;
        }
    }

    /**
     * A line of news from the towns round about: the nearest three, a real line out of one's chronicle, its
     * day, and how far and which way it lies. With no town near, a line of the town's own past, and with
     * none of that, one of its tales.
     */
    static String newsLine(ServerLevel level, @Nullable Villages.Village town, RandomSource r) {
        if (town == null) return "Tales, I've plenty. News, not today.";
        List<Villages.Village> near = new ArrayList<>();
        for (Villages.Village o : Villages.every()) {
            if (!o.id().equals(town.id()) && o.dim().equals(town.dim())) near.add(o);
        }
        near.sort(Comparator.comparingDouble(o -> o.centre().distSqr(town.centre())));
        if (near.size() > 3) near = new ArrayList<>(near.subList(0, 3));
        java.util.Collections.shuffle(near, new java.util.Random(r.nextLong()));
        for (Villages.Village o : near) {
            // Its latest news, the last six lines (its founding is old news, unless that is all there is).
            List<Chronicle.Entry> past = new ArrayList<>(Chronicle.of(o.id()));
            if (past.size() > 1) past.removeIf(x -> x.text().endsWith(" was founded"));
            if (past.isEmpty()) continue;
            int from = Math.max(0, past.size() - 6);
            Chronicle.Entry e = past.get(from + r.nextInt(past.size() - from));
            int far = (int) Math.sqrt(o.centre().distSqr(town.centre()));
            return FolkTalk.pick(r, "News from " + Villages.name(o.id()) + ", " + far + " blocks " + Guide.direction(town.centre(), o.centre())
                    + ": " + e.text() + ", on day " + (e.day() + 1) + ".",
                "I came by way of " + Villages.name(o.id()) + ". Did you hear? " + FolkTalk.cap(e.text()) + "!",
                "In " + Villages.name(o.id()) + " they're still talking of it: " + e.text() + ".");
        }
        List<Chronicle.Entry> own = Chronicle.of(town.id());
        if (!own.isEmpty()) {
            Chronicle.Entry e = own.get(r.nextInt(own.size()));
            return "They sing of it on the road already: how " + e.text() + ", here in " + Villages.name(town.id()) + "!";
        }
        return "Here's one for you: " + TALES[r.nextInt(TALES.length)] + "!";
    }

    // ------------------------------------------------------------------ by day

    /** By day: about the square and the town's sights, busking now and then, a coin in its hat from whoever likes it. */
    static void stroll(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, long day) {
        BlockPos to = STROLL.get(f.getUUID());
        RandomSource r = f.getRandom();
        if (to == null || Visitors.walk(f, level, to, 2.0, 0.6D, v.walk) && r.nextInt(12) == 0) {
            if (to != null && r.nextInt(3) == 0) busk(level, town, f, v, day);
            BlockPos c = town.centre();
            BlockPos next = Visitors.surface(level, c.offset(r.nextInt(17) - 8, 0, r.nextInt(17) - 8));
            STROLL.put(f.getUUID(), next);
        }
    }

    /** A tune on the square: whoever stops to listen hears it, and one of them may give it a coin. */
    private static void busk(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, long day) {
        phrase(level, f, 13000L);
        listen(level, town, f, v, day);
        Set<UUID> tipped = TIPPED.computeIfAbsent(f.getUUID(), k -> ConcurrentHashMap.newKeySet());
        if (tipped.size() >= 5) return;
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(8.0, 3.0, 8.0),
                o -> o != f && o.isAlive() && !o.isBaby() && town.id().equals(o.ownerId()) && o.purse() >= 6)) {
            if (!tipped.add(o.getUUID())) continue;
            // A coin from its own purse into the bard's hat: the folk's to give, once a visit.
            if (o.spend(1)) {
                f.earn(1);
                Buskers.bardTook(level, town.id(), f);                  // [arms] in the gazette's street music
                o.sayLater(FolkTalk.pick(f.getRandom(), "Here — that one was lovely.", "A coin for the song!"), 20);
            }
            return;
        }
    }

    /** What it is doing, for its card. */
    static String doing(VillageFolkEntity f, Visitors.Visit v) {
        long t = f.level().getDayTime() % 24000L;
        if (t >= 12500L && t < 17500L) return "Playing and telling tales at the tavern";
        if (t >= 17500L && t < 23000L) return "Off to bed at the inn";
        return "Seeing the town between evenings at the tavern";
    }

    /** Tests: the bard plays a phrase to the room and tells a line now; the residents who heard it. */
    public static int performForTests(ServerLevel level, VillageFolkEntity f) {
        Visitors.Visit v = Visitors.visit(f);
        Villages.Village town = v == null ? null : Villages.get(v.town);
        if (town == null) return 0;
        long day = level.getDayTime() / 24000L;
        phrase(level, f, level.getDayTime() % 24000L);
        FolkTalk.speak(f, newsLine(level, town, f.getRandom()));
        return listen(level, town, f, v, day);
    }

    /** Tests: a line of the bard's news for this town. */
    public static String newsForTests(ServerLevel level, Villages.Village town) {
        return newsLine(level, town, level.getRandom());
    }

    /** Tests: where the bard stands to play in this town's tavern. */
    @Nullable
    public static BlockPos stageForTests(UUID town) {
        Ledger.Building tav = Tavern.of(town);
        return tav == null ? null : stage(tav);
    }

    /** Tests: the head of the bard's bedroll, if it has laid it out. */
    @Nullable
    public static BlockPos bedrollForTests(VillageFolkEntity f) {
        Visitors.Visit v = Visitors.visit(f);
        return v == null ? null : v.bedroll;
    }
}
