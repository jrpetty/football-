package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Street musicians [arms] (Culture).
 *
 * <p><b>Who.</b> The town's musicians (Leisure's, whose pastime is music: Music.musician) busk: on its busking
 * evenings (two in three, the first two hours after work) and on market day in its time off, each at a pitch of
 * its own, by the well, on a corner of the square where an avenue comes in, or outside the market. Not in the
 * rain, not while the town is gathered, never the watch. A visiting bard still busks on the square by day (Bard).
 *
 * <p><b>The music.</b> A real tune on a real instrument: [leisure] its lute (its own, one bought out of its hat, or one
 * lent out of the stores for the evening: Lutes), held in its hand; or with no lute to be had, a note block, its own or
 * the stores' lent for the evening and put back after (a town with neither has no buskers, and its books say so). The tavern's jig and its slow air, the
 * wedding march, a reel of the street's own, on the busker's own voice (a harp, a flute, a guitar...), the notes
 * rising over its head. A poor hand slips: a note a semitone out now and then, with a puff of smoke for it.
 *
 * <p><b>The hat.</b> Passers-by in their time off stop to listen (more for a good busker, up to five at once),
 * stand round it a while and go on; as they go, a listener with a few coins put by may drop one in its hat: out of
 * its own purse, into the busker's, once an evening, likelier the better the playing (and for a generous listener,
 * less for a grumpy one). A player drops a coin in by right-clicking the busker with a village coin in hand.
 *
 * <p><b>Getting better.</b> Each evening's playing is practice: a busker's skill (from a talent of its own) grows
 * with every evening and every few hundred notes, and is kept in the town's books with what its hat has taken. A
 * busker who has played five evenings and still draws next to nothing, and plays badly, gives up for a fortnight
 * ("Nobody stops for my tunes"). The tavern keeper hears of the good ones: the best that plays well and draws a
 * hat (skill 45, two coins an evening of late) is asked to play the tavern every day of rest, for a fee out of the
 * treasury (three coins) and a full house (the whole town comes, as for a bard), from dusk until the band takes
 * over, or all evening with no band. The chronicle tells it: "Pip, who played for coppers by the well, now plays
 * the tavern every rest day".
 *
 * <p><b>Seen.</b> On the busker's card; in the gazette (yesterday's hats, and who plays the tavern); in the folk's
 * small talk ("Have you heard Pip play by the well?"); and on the Culture page of the town's books.
 */
public final class Buskers {

    private Buskers() {}

    /** The busking evening: from the end of work for two hours. */
    static final long EVENING_FROM = 12000L, EVENING_TO = 14000L;
    /** On market day, in its time off. */
    static final long MARKET_FROM = 2000L, MARKET_TO = 11500L;
    /** The tavern's turn on the day of rest. */
    static final long TAVERN_FROM = 12000L, TAVERN_TO = 17000L;
    /** The tavern's fee for its evening, out of the treasury. */
    public static final int FEE = 3;
    /** What the tavern looks for: this good, and a hat of this many coins an evening of late. */
    static final int BOOK_SKILL = 45, BOOK_TIPS = 2;
    /** Evenings played before a busker may give up, and how long it stays given up. */
    static final int TRIES = 5;
    static final long GIVE_UP_DAYS = 14;
    /** Listeners at once, at most. */
    static final int CROWD = 5;

    /** A reel of the street's own, a beat to a note (-1 a rest). */
    static final int[] REEL = { 12, 14, 16, 17, 19, 17, 16, 14, 12, -1, 12, 16, 19, -1, 24, -1, 21, 19, 17, 16, 14, 12, 14, 16,
        17, -1, 14, -1, 12, -1, -1, -1 };
    private static final int[][] TUNES = { Tavern.JIG, REEL, Tavern.AIR, Music.MARCH };
    /** Its voice, by who it is. */
    private static final List<Holder.Reference<SoundEvent>> VOICES = List.of(SoundEvents.NOTE_BLOCK_HARP, SoundEvents.NOTE_BLOCK_FLUTE,
        SoundEvents.NOTE_BLOCK_GUITAR, SoundEvents.NOTE_BLOCK_BANJO, SoundEvents.NOTE_BLOCK_BELL, SoundEvents.NOTE_BLOCK_XYLOPHONE);
    private static final String[] VOICE_WORDS = { "harp", "flute", "guitar", "banjo", "bells", "xylophone" };

    // ------------------------------------------------------------------ the books

    /** A busker's record, kept in the town's books: how good, how many evenings, what its hat has taken. */
    static final class Record {
        int skill, stints, tips, avg10 = 10, tavern;
        long gaveUp = -1;
        String where = "", name = "";

        String encode() {
            return skill + "," + stints + "," + tips + "," + avg10 + "," + gaveUp + "," + tavern + "|" + where.replace("|", "") + "|" + name.replace("|", "");
        }

        static Record decode(@Nullable String s, UUID folk) {
            Record r = new Record();
            r.skill = talent(folk);
            if (s == null || s.isEmpty()) return r;
            String[] p = s.split("\\|", -1);
            String[] n = p[0].split(",");
            try {
                r.skill = Integer.parseInt(n[0]);
                r.stints = Integer.parseInt(n[1]);
                r.tips = Integer.parseInt(n[2]);
                r.avg10 = Integer.parseInt(n[3]);
                r.gaveUp = Long.parseLong(n[4]);
                r.tavern = Integer.parseInt(n[5]);
            } catch (RuntimeException ignored) {
                // a record from another build: what could be read is kept
            }
            if (p.length > 1) r.where = p[1];
            if (p.length > 2) r.name = p[2];
            return r;
        }
    }

    /** What a busker has to start with: its talent, eight to thirty-two. */
    static int talent(UUID folk) {
        return 8 + Math.floorMod(folk.hashCode() * 7 + 3, 25);
    }

    static Record record(UUID village, UUID folk) {
        return Record.decode(Ledger.note(village, "busk/" + folk), folk);
    }

    static void save(UUID village, UUID folk, Record r) {
        Ledger.note(village, "busk/" + folk, r.encode());
    }

    /** Who the tavern has booked for the day of rest, or null. */
    @Nullable
    static UUID booked(UUID village) {
        String s = Ledger.note(village, "busk.tavern");
        if (s == null || s.isEmpty()) return null;
        try {
            return UUID.fromString(s.split("\\|")[0]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ who is playing, who is listening

    /** A busker playing now: where, since when, its tune and its hat. */
    static final class Stint {
        final UUID folk, town;
        final BlockPos pitch;
        final String where;
        final boolean tavern;
        boolean lent;
        /** [leisure] Playing a lute (and the stores' lute, lent for the evening). */
        boolean lute, luteLent;
        int skill, tune, beat, notes, take, fee;
        long since, lastHeld, spoke;
        final Set<UUID> tipped = new HashSet<>(), heard = new HashSet<>();

        Stint(UUID folk, UUID town, BlockPos pitch, String where, boolean tavern) {
            this.folk = folk;
            this.town = town;
            this.pitch = pitch;
            this.where = where;
            this.tavern = tavern;
        }
    }

    /** A passer-by stopped to listen: to whom, till when, whether it has made up its mind about a coin. */
    static final class Ear {
        final UUID busker;
        final long since, until;
        boolean decided;

        Ear(UUID busker, long since, long until) {
            this.busker = busker;
            this.since = since;
            this.until = until;
        }
    }

    private static final Map<UUID, Stint> PLAYING = new ConcurrentHashMap<>();
    private static final Map<UUID, Ear> LISTENING = new ConcurrentHashMap<>();
    /** Why a town has no buskers out, in words (the books). */
    private static final Map<UUID, String> SHORT = new ConcurrentHashMap<>();

    static void resetForTests() {
        PLAYING.clear();
        LISTENING.clear();
        SHORT.clear();
        PITCHES.clear();
        PITCHES_UNTIL.clear();
    }

    /** May this folk busk at all: one of the town's musicians (grown, music its pastime, not the watch)? */
    static boolean busker(VillageFolkEntity f) {
        return Music.musician(f);
    }

    /** A pitch: where, and what it is called. */
    record Pitch(BlockPos at, String words) {}

    /** Each town's pitches as last looked over, and when to look again (the ground is read, so not every few ticks). */
    private static final Map<UUID, List<Pitch>> PITCHES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PITCHES_UNTIL = new ConcurrentHashMap<>();

    /** The town's pitches: by the well, the corners of the square where the avenues come in, outside the market. */
    static List<Pitch> pitches(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        List<Pitch> known = PITCHES.get(v.id());
        if (known != null && now < PITCHES_UNTIL.getOrDefault(v.id(), 0L)) return known;
        List<Pitch> out = lookOver(level, v);
        PITCHES.put(v.id(), out);
        PITCHES_UNTIL.put(v.id(), now + 600L);
        return out;
    }

    private static List<Pitch> lookOver(ServerLevel level, Villages.Village v) {
        BlockPos c = v.centre();
        List<Pitch> out = new ArrayList<>();
        int[][] spots = { { 3, 6 } };
        for (int[] s : spots) add(level, out, c.offset(s[0], 0, s[1]), c.getY(), "by the well");
        int in = TownPlan.PLAZA - 3, side = TownPlan.AVENUE + 3;
        for (Direction d : new Direction[]{ Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST }) {
            add(level, out, c.relative(d, in).relative(d.getClockWise(), side), c.getY(), "on the corner by the " + d.getName() + " gate");
        }
        Ledger.Building market = Culture.building(v.id(), "market");
        if (market != null) add(level, out, Culture.at(market, 4, 0, -3), market.anchor().getY(), "outside the market");
        return out;
    }

    private static void add(ServerLevel level, List<Pitch> out, BlockPos p, int y, String words) {
        if (!level.isLoaded(p)) return;
        BlockPos floor = Watch.floorAt(level, p.getX(), p.getZ(), y);
        if (floor != null) out.add(new Pitch(floor, words));
    }

    /** Its own pitch: the town's buskers in a fixed order, a pitch each, round again when there are more of them. */
    @Nullable
    static Pitch pitchFor(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        List<Pitch> all = pitches(level, v);
        if (all.isEmpty()) return null;
        List<UUID> buskers = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity g && busker(g)) buskers.add(g.getUUID());
        buskers.sort(Comparator.naturalOrder());
        int i = Math.max(0, buskers.indexOf(f.getUUID()));
        return all.get(i % all.size());
    }

    /** Where this busker should be playing now, or null for nowhere: the tavern on its night, else its pitch. */
    @Nullable
    static Stint wanted(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        Record r = record(id, f.getUUID());
        if (r.gaveUp >= 0 && day - r.gaveUp < GIVE_UP_DAYS) return null;
        if (Assemblies.underWay(id) != null) return null;              // the town is gathered: everybody is there
        Ledger.Building tav = Tavern.of(id);
        if (f.getUUID().equals(booked(id)) && tav != null && RestDay.today(id, day) && t >= TAVERN_FROM && t < TAVERN_TO
                && !Music.playing(id) && level.isLoaded(tav.anchor())) {
            return new Stint(f.getUUID(), id, Bard.stage(tav), "at the tavern", true);
        }
        if (!f.offWorkNow() || level.isRaining()) return null;
        boolean evening = t >= EVENING_FROM && t < EVENING_TO && Math.floorMod(day * 31 + f.getUUID().hashCode(), 3) != 0;
        boolean market = Market.marketDay(id, day) && t >= MARKET_FROM && t < MARKET_TO;
        if (!evening && !market) return null;
        Pitch p = pitchFor(level, v, f);
        return p == null ? null : new Stint(f.getUUID(), id, p.at(), p.words(), false);
    }

    // ------------------------------------------------------------------ the folk's part

    /** Every few ticks for each folk (Culture.hold): a passer-by stopped to listen, or a busker at its pitch. */
    static boolean hold(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        return listen(f, level, v) || busk(f, level, v);
    }

    /** A busker's evening: to its pitch, its note block up, and playing; the passers-by drawn to it. */
    static boolean busk(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        Stint s = PLAYING.get(f.getUUID());
        if (!busker(f)) {
            if (s != null) end(level, s);
            return false;
        }
        Stint want = wanted(level, v, f);
        if (want == null) {
            if (s != null) end(level, s);
            return false;
        }
        if (s == null || !s.pitch.equals(want.pitch) || s.tavern != want.tavern) {
            if (s != null) end(level, s);
            s = start(level, v, f, want);
            if (s == null) return false;
        }
        long now = level.getGameTime();
        s.lastHeld = now;
        Culture.mark(f, Culture.Role.BUSK, s.tavern ? "playing the tavern for the day of rest" : "busking " + s.where);
        if (Culture.arrive(f, s.pitch, 1.0, 0.9)) {
            // Facing the street (the heart of the town), or down the tavern's room to its door.
            Ledger.Building tav = s.tavern ? Tavern.of(v.id()) : null;
            BlockPos look = tav != null ? tav.anchor().relative(tav.facing().getOpposite(), 3) : v.centre();
            f.getLookControl().setLookAt(look.getX() + 0.5, f.getEyeY(), look.getZ() + 0.5);
            if (s.lute) Lutes.inHand(f);                          // [leisure] the lute in its hand
            else Culture.prop(f, Items.NOTE_BLOCK);
            play(level, s, f);
            if (now % 20 < 4) {
                if (s.tavern) room(level, v, s, f);
                else gather(level, v, s, f);
            }
            if (now - s.spoke > 900L && f.getRandom().nextInt(4) == 0) {
                s.spoke = now;
                FolkTalk.speak(f, s.tavern ? FolkTalk.pick(f.getRandom(), "Here's one you'll all know!", "A request? Shout it out!",
                        "Thank you, thank you. One more, then!")
                    : FolkTalk.pick(f.getRandom(), "A tune for a coin, good folk!", "Stop a while, this one's a cheerful one.",
                        "Any coin for the hat?", "Here's one I've been practising."));
            }
        }
        return true;
    }

    /**
     * Out to play: its own note block, or one lent out of the stores for the evening; at the tavern the fee paid,
     * once a day of rest, out of the treasury. Null if it has no note block and the stores have none.
     */
    @Nullable
    static Stint start(ServerLevel level, Villages.Village v, VillageFolkEntity f, Stint s) {
        UUID id = v.id();
        boolean[] lent = { false };
        s.lute = Lutes.ready(level, v, f, lent);                     // [leisure] a lute first: its own, bought, or the stores'
        s.luteLent = lent[0];
        if (!s.lute && f.countCarried(st -> st.is(Items.NOTE_BLOCK) && !Leisure.isProp(st)) == 0) {
            if (Crafts.stock(level, v, st -> st.is(Items.NOTE_BLOCK)) <= 0 || !Crafts.take(level, v, st -> st.is(Items.NOTE_BLOCK), 1)) {
                SHORT.put(id, "a lute or a note block to play: the stores have neither to lend (the shop's workshop makes lutes)");
                return null;
            }
            ItemStack left = f.insertItem(new ItemStack(Items.NOTE_BLOCK));
            if (!left.isEmpty()) {
                Crafts.store(level, v, left);
                return null;
            }
            s.lent = true;
        }
        SHORT.remove(id);
        Record r = record(id, f.getUUID());
        s.skill = r.skill;
        long now = level.getGameTime(), day = level.getDayTime() / 24000L;
        s.since = now;
        s.lastHeld = now;
        s.spoke = now - 600L;
        s.tune = Math.floorMod((int) day + f.getUUID().hashCode(), TUNES.length);
        if (s.tavern && !String.valueOf(day).equals(Ledger.note(id, "busk.tavern.paid"))) {
            int fee = Ledger.takeCoins(id, FEE);
            if (fee > 0) {
                f.earn(fee);
                Economy.spent(id, fee);
            }
            s.fee = fee;
            Ledger.note(id, "busk.tavern.paid", Long.toString(day));
            if (r.tavern == 0) {
                Villages.tell(id, day, f.displayNameCap() + " played the tavern for the first time" + (fee > 0 ? ", for " + fee + " coins and a full house"
                    : ", for a full house (the treasury was empty)"));
            }
        }
        PLAYING.put(f.getUUID(), s);
        return s;
    }

    /**
     * Home from its pitch: the lent note block back into the stores, the prop put away; the evening's practice and
     * its hat into its record; a poor one may give up, and the tavern hears of a good one.
     */
    static void end(ServerLevel level, Stint s) {
        PLAYING.remove(s.folk, s);
        Villages.Village v = Villages.get(s.town);
        VillageFolkEntity f = level.getEntity(s.folk) instanceof VillageFolkEntity g ? g : null;
        if (f != null) {
            if (s.lent && f.isAlive() && f.removeMatching(st -> st.is(Items.NOTE_BLOCK) && !Leisure.isProp(st), 1) == 1 && v != null) {
                Crafts.store(level, v, new ItemStack(Items.NOTE_BLOCK));
            }
            if (s.lute) Lutes.putAway(level, v, f, s.luteLent);           // [leisure] the lute put away; the stores' back to them
            ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
            if (off.is(Items.NOTE_BLOCK) && Leisure.isProp(off)) {
                f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                f.propInHand = false;
            }
        }
        LISTENING.values().removeIf(e -> e.busker.equals(s.folk));
        if (s.notes < 40 || v == null) return;                          // hardly started: no evening of it
        long day = level.getDayTime() / 24000L;
        Record r = record(s.town, s.folk);
        r.stints++;
        r.skill = Math.min(100, r.skill + 1 + s.notes / 400);
        r.tips += s.take;
        r.avg10 = (r.avg10 * 3 + s.take * 10) / 4;
        if (s.tavern) r.tavern++;
        else r.where = s.where;
        if (f != null) r.name = f.displayNameCap();
        String name = r.name.isEmpty() ? "a busker" : r.name;
        takings(s.town, day, name, s.take, s.where);
        if (f != null && f.persona().rolled()) {
            f.persona().remember(day, (s.tavern ? "I played the tavern" : "I busked " + s.where) + (s.take > 0 ? " and took " + s.take
                + (s.take == 1 ? " coin" : " coins") : ", and nobody gave a coin"), s.take > 2 || s.tavern ? 3 : 1);
        }
        if (!s.tavern && r.stints >= TRIES && r.avg10 < 5 && r.skill < 30) {
            r.gaveUp = day;
            if (f != null) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Nobody stops for my tunes. I'll keep them for the well.",
                "That's it, I'm hanging up my hat. Nobody wants to hear me."));
            Villages.tell(s.town, day, name + " gave up busking " + s.where + ": nobody stopped to listen");
        }
        save(s.town, s.folk, r);
        consider(level, v);
    }

    /** The town's look at its buskers (Culture.tick, each second): one not held for a while has gone home. */
    static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        for (Stint s : new ArrayList<>(PLAYING.values())) {
            if (s.town.equals(v.id()) && now - s.lastHeld > 60L) end(level, s);
        }
        LISTENING.entrySet().removeIf(e -> now > e.getValue().until + 200L);
    }

    // ------------------------------------------------------------------ the music

    /** A note of its tune, on its own voice; a poor hand slips now and then. */
    static void play(ServerLevel level, Stint s, VillageFolkEntity f) {
        if (s.lute) {                                                  // [leisure] the lute: a better instrument, fewer slips
            int beat = s.beat++;
            s.notes++;
            Lutes.strum(level, f, Lutes.TUNES[Math.floorMod(s.tune, Lutes.TUNES.length)], beat, Math.min(100, s.skill + 20), s.tavern ? 1.1F : 0.95F);
            if (beat % 4 == 0) f.swing(InteractionHand.MAIN_HAND);
            return;
        }
        int[] tune = TUNES[s.tune];
        int beat = s.beat++;
        int note = tune[Math.floorMod(beat, tune.length)];
        s.notes++;
        if (note < 0) return;
        boolean slip = f.getRandom().nextInt(100) >= 70 + s.skill * 3 / 10;
        if (slip) note += f.getRandom().nextBoolean() ? 1 : -1;
        note = Math.max(0, Math.min(24, note));
        float pitch = (float) Math.pow(2.0, (note - 12) / 12.0);
        Holder.Reference<SoundEvent> voice = VOICES.get(Math.floorMod(s.folk.hashCode(), VOICES.size()));
        level.playSound(null, f.getX(), f.getY() + 1.0, f.getZ(), voice.value(), SoundSource.RECORDS, s.tavern ? 1.1F : 0.9F, pitch);
        level.sendParticles(ParticleTypes.NOTE, f.getX(), f.getY() + 2.2, f.getZ(), 0, note / 24.0, 0.0, 0.0, 1.0);
        if (slip) level.sendParticles(ParticleTypes.SMOKE, f.getX(), f.getY() + 2.0, f.getZ(), 2, 0.1, 0.1, 0.1, 0.01);
        if (beat % 8 == 0) f.swing(InteractionHand.OFF_HAND);
    }

    /** Passers-by drawn to stop: more for a better busker, and only folk in their own time with nothing on. */
    static void gather(ServerLevel level, Villages.Village v, Stint s, VillageFolkEntity f) {
        int want = Math.min(CROWD, 1 + s.skill / 20) + (s.lute ? 1 : 0), have = 0;      // [leisure] a lute draws one more
        for (Ear e : LISTENING.values()) if (e.busker.equals(s.folk)) have++;
        if (have >= want) return;
        long now = level.getGameTime();
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(14.0, 4.0, 14.0),
                o -> o != f && o.isAlive() && !o.isSleeping() && v.id().equals(o.ownerId()) && !o.isShowcase())) {
            if (have >= want) return;
            if (LISTENING.containsKey(o.getUUID()) || PLAYING.containsKey(o.getUUID()) || Culture.role(o) != null) continue;
            if (!o.offWorkNow() || !Culture.free(o, level) || Assemblies.attending(o)) continue;
            if (o.life().has(Social.Trait.SHY) && o.getRandom().nextInt(3) != 0) continue;
            if (o.getRandom().nextDouble() > 0.3 + s.skill / 150.0 + (s.lute ? 0.15 : 0.0)) continue;
            LISTENING.put(o.getUUID(), new Ear(s.folk, now, now + 300L + o.getRandom().nextInt(300)));
            have++;
        }
    }

    /** At the tavern: the room is its crowd; each of it hears it, and may drop a coin in its hat. */
    static void room(ServerLevel level, Villages.Village v, Stint s, VillageFolkEntity f) {
        long day = level.getDayTime() / 24000L;
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(8.0, 3.0, 8.0),
                o -> o != f && o.isAlive() && !o.isSleeping() && v.id().equals(o.ownerId()))) {
            if (s.heard.add(o.getUUID()) && o.persona().rolled()) {
                o.persona().remember(day, "heard " + f.displayNameCap() + " play the tavern", 2);
            }
            if (level.getGameTime() - s.since > 400L && !s.tipped.contains(o.getUUID()) && o.getRandom().nextInt(6) == 0) {
                tip(level, s, f, o, o.getRandom().nextDouble());
            }
        }
    }

    /** A passer-by's part: to a place in the ring round the busker, facing it; a coin as it goes, perhaps. */
    static boolean listen(VillageFolkEntity o, ServerLevel level, Villages.Village v) {
        Ear e = LISTENING.get(o.getUUID());
        if (e == null) return false;
        Stint s = PLAYING.get(e.busker);
        VillageFolkEntity b = s != null && level.getEntity(e.busker) instanceof VillageFolkEntity g ? g : null;
        long now = level.getGameTime();
        if (s == null || b == null || now > e.until || !o.offWorkNow()) {
            LISTENING.remove(o.getUUID(), e);
            if (s != null && b != null && !e.decided && now - e.since > 100L) tip(level, s, b, o, o.getRandom().nextDouble());
            return false;
        }
        Culture.mark(o, Culture.Role.LISTEN, "listening to " + b.displayNameCap() + " play " + s.where);
        int slot = Math.floorMod(o.getUUID().hashCode(), 8);
        double a = slot * Math.PI / 4.0;
        BlockPos stand = s.pitch.offset((int) Math.round(Math.cos(a) * 3.0), 0, (int) Math.round(Math.sin(a) * 3.0));
        if (Culture.arrive(o, stand, 1.4, 0.8)) {
            o.getLookControl().setLookAt(b, 30.0F, 30.0F);
            if (s.heard.add(o.getUUID()) && o.persona().rolled()) {
                o.persona().remember(level.getDayTime() / 24000L, "stopped to hear " + b.displayNameCap() + " play " + s.where, 1);
            }
            if (o.getRandom().nextInt(40) == 0) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, o.getX(), o.getY() + 2.0, o.getZ(), 2, 0.2, 0.2, 0.2, 0.0);
                o.life().feel(b.getUUID(), b.displayNameCap(), s.skill >= 40 ? 2 : 1);
            }
            if (!e.decided && now - e.since > 200L && o.getRandom().nextInt(10) == 0) {
                e.decided = true;
                tip(level, s, b, o, o.getRandom().nextDouble());
            }
        }
        return true;
    }

    /**
     * A listener's coin, if the playing moves it ({@code roll} under the busker's chance): out of its own purse,
     * into the busker's, once an evening. Not a child, nor one with fewer than three coins put by. Whether it gave.
     */
    static boolean tip(ServerLevel level, Stint s, VillageFolkEntity busker, VillageFolkEntity o, double roll) {
        if (o.isBaby() || o.purse() < 3 || !s.tipped.add(o.getUUID())) return false;
        double chance = 0.15 + s.skill / 120.0 + (s.lute ? 0.1 : 0.0);          // [leisure] a lute fills the hat the quicker
        if (o.life().has(Social.Trait.GENEROUS)) chance += 0.2;
        if (o.life().has(Social.Trait.GRUMPY)) chance -= 0.15;
        if (roll >= chance || !o.spend(1)) return false;
        busker.earn(1);
        s.take++;
        coin(level, busker);
        FolkTalk.speak(o, FolkTalk.pick(o.getRandom(), "A coin for the song!", "Here, that one was lovely.", "For your hat, " + busker.displayNameCap() + ".",
            "Play it again sometime!"));
        if (o.persona().rolled()) o.life().feel(busker.getUUID(), busker.displayNameCap(), 2);
        return true;
    }

    /** A coin into the hat, for the eye and the ear. */
    private static void coin(ServerLevel level, VillageFolkEntity busker) {
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get())),
            busker.getX(), busker.getY() + 0.4, busker.getZ(), 5, 0.15, 0.1, 0.15, 0.03);
        level.playSound(null, busker.blockPosition(), SoundEvents.CHAIN_PLACE, SoundSource.NEUTRAL, 0.5F, 1.8F);
    }

    /**
     * A player's coin in the hat: right-clicking a busker who is playing, with a village coin in hand. One coin, out
     * of the player's pack, into the busker's purse. False (and nothing done) if it is not busking or that is no coin.
     */
    public static boolean tipFrom(VillageFolkEntity f, Player p, ItemStack held) {
        Stint s = PLAYING.get(f.getUUID());
        if (s == null || !Market.isCoin(held) || Market.coinsHeld(p) < 1 || !(f.level() instanceof ServerLevel level)) return false;
        Market.payOut(p, 1);
        f.earn(1);
        s.take++;
        coin(level, f);
        String name = p.getName().getString();
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Thank you kindly, " + name + "!", "Bless you, " + name + ". This one's for you.",
            "A coin from " + name + "! Now that's a fine audience."));
        f.persona().feelFor(p.getUUID(), name, 2);
        if (s.tipped.add(p.getUUID())) f.persona().remember(level.getDayTime() / 24000L, name + " dropped a coin in my hat", 2);
        p.displayClientMessage(Component.literal("You dropped a coin in " + f.displayNameCap() + "'s hat."), true);
        return true;
    }

    // ------------------------------------------------------------------ the tavern

    /**
     * The tavern keeper hears of the good ones (each evening's end, and the books' look): with nobody booked (or
     * the one booked gone, given up or no longer playing), the best of the town's buskers that plays well and
     * draws a hat is asked to play the tavern every day of rest. The chronicle tells it.
     */
    static void consider(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Tavern.of(id) == null) return;
        long day = level.getDayTime() / 24000L;
        UUID now = booked(id);
        VillageFolkEntity best = null;
        Record bestR = null;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !busker(f)) continue;
            Record r = record(id, f.getUUID());
            if (f.getUUID().equals(now) && r.gaveUp < 0) return;             // the tavern has its player
            if (r.gaveUp >= 0 && day - r.gaveUp < GIVE_UP_DAYS) continue;
            if (r.skill < BOOK_SKILL || r.avg10 < BOOK_TIPS * 10) continue;
            if (best == null || r.skill > bestR.skill) {
                best = f;
                bestR = r;
            }
        }
        if (best == null) {
            if (now != null) Ledger.forget(id, "busk.tavern");               // its player gone: the tavern looks again
            return;
        }
        Ledger.note(id, "busk.tavern", best.getUUID() + "|" + day);
        VillageFolkEntity keeper = Inn.keeper(id);
        String where = bestR.where.isEmpty() ? "in the street" : bestR.where;
        Villages.tell(id, day, best.displayNameCap() + ", who played for coppers " + where + ", now plays the tavern every rest day"
            + (keeper != null && keeper != best ? ", asked by " + keeper.displayNameCap() : ""));
        if (best.persona().rolled()) best.persona().remember(day, "the tavern asked me to play every rest day", 6);
        FolkTalk.speak(best, FolkTalk.pick(best.getRandom(), "The tavern wants me! Every rest day!", "Me, at the tavern? I'd better practise."));
    }

    /** Is the booked busker playing the tavern now (its own tune waits: Tavern.play)? */
    public static boolean atTheTavern(UUID village) {
        for (Stint s : PLAYING.values()) if (s.tavern && s.town.equals(village)) return true;
        return false;
    }

    /** Is it a night the tavern has its busker (the town comes, as for a bard: Tavern.evening)? */
    public static boolean tavernTonight(ServerLevel level, @Nullable UUID village) {
        if (village == null || booked(village) == null) return false;
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        return RestDay.today(village, day) && t >= TAVERN_FROM && t < TAVERN_TO;
    }

    // ------------------------------------------------------------------ what the player reads

    /** Yesterday's hats in the books, for the gazette: "name:take:where" by the day. */
    private static void takings(UUID village, long day, String name, int take, String where) {
        String key = "busk.takings/" + day;
        String was = Ledger.note(village, key);
        Ledger.note(village, key, (was == null || was.isEmpty() ? "" : was + ";") + name.replace(":", "").replace(";", "") + ":" + take + ":" + where);
        Ledger.forget(village, "busk.takings/" + (day - 3));
    }

    /** A visiting bard's coin on the square (Bard.busk), into the day's hats for the gazette. */
    static void bardTook(ServerLevel level, UUID village, VillageFolkEntity bard) {
        takings(village, level.getDayTime() / 24000L, bard.displayNameCap() + " the bard", 1, "on the square");
    }

    /** The gazette's street music: yesterday's buskers and what their hats took, and who plays the tavern. Null with nothing. */
    @Nullable
    public static String gazette(ServerLevel level, UUID village, long day) {
        List<String> lines = new ArrayList<>();
        String y = Ledger.note(village, "busk.takings/" + (day - 1));
        if (y != null && !y.isEmpty()) {
            Map<String, int[]> by = new java.util.LinkedHashMap<>();
            Map<String, String> at = new java.util.HashMap<>();
            for (String e : y.split(";")) {
                String[] p = e.split(":", 3);
                if (p.length < 3) continue;
                by.computeIfAbsent(p[0], k -> new int[1])[0] += (int) Culture.num(p[1], 0);
                at.put(p[0], p[2]);
            }
            for (Map.Entry<String, int[]> e : by.entrySet()) {
                int n = e.getValue()[0];
                lines.add(e.getKey() + " played " + at.get(e.getKey()) + (n == 0 ? ": not a coin." : " and took " + n + (n == 1 ? " coin." : " coins.")));
                if (lines.size() >= 3) break;
            }
        }
        UUID b = booked(village);
        if (b != null) {
            Record r = record(village, b);
            if (!r.name.isEmpty()) lines.add(r.name + " plays the tavern on the day of rest.");
        }
        if (lines.isEmpty()) return null;
        return "§lStreet music§r\n" + String.join("\n", lines);
    }

    /** The folk's card: what it is about as a busker, how good it is, what its hat has taken. Null for a folk that does not busk. */
    @Nullable
    static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return null;
        Ear e = LISTENING.get(f.getUUID());
        if (e != null && PLAYING.get(e.busker) != null) return "stopped to listen to a busker";
        if (!busker(f)) return null;
        Record r = record(id, f.getUUID());
        Stint s = PLAYING.get(f.getUUID());
        String how = r.skill >= 70 ? "a fine player" : r.skill >= BOOK_SKILL ? "a good player" : r.skill >= 25 ? "getting better" : "still learning";
        String bit = (s != null ? (s.tavern ? "playing the tavern now" : "busking " + s.where + " now, " + s.take + " in the hat") : f.getUUID().equals(booked(id))
            ? "plays the tavern every rest day" : r.gaveUp >= 0 ? "gave up busking for now" : r.stints == 0 ? "busks of an evening" : "busks " + r.where);
        return bit + " (" + how + ", skill " + r.skill + "; " + r.tips + (r.tips == 1 ? " coin" : " coins") + " in tips over " + r.stints
            + (r.stints == 1 ? " evening)" : " evenings)");
    }

    /** "Have you heard Pip play by the well?": what the town says of its buskers, for its small talk (FolkTalk.smallTalk). */
    public static void smallTalk(VillageFolkEntity a, List<String> options) {
        UUID id = a.ownerId();
        if (id == null) return;
        UUID tavern = booked(id);
        for (AssistantEntity x : Villages.folkOf(id)) {
            if (!(x instanceof VillageFolkEntity f) || f == a || !busker(f)) continue;
            Record r = record(id, f.getUUID());
            if (r.stints == 0) continue;
            String name = f.displayNameCap();
            if (f.getUUID().equals(tavern)) options.add("Did you know " + name + " plays the tavern now? Every rest day!");
            else if (r.skill >= BOOK_SKILL) options.add("Have you heard " + name + " play " + r.where + "? Lovely playing.");
            else if (r.skill < 25) options.add("Somebody ought to tell " + name + " that's not how the tune goes.");
            else options.add(name + " was out " + r.where + " again, playing for coppers.");
            return;
        }
    }

    /** The Culture page's buskers: each with how good it is and what its hat has taken, who plays the tavern. */
    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        List<String> lines = new ArrayList<>();
        UUID tavern = booked(id);
        List<VillageFolkEntity> all = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && busker(f)) all.add(f);
        all.sort(Comparator.comparing(VillageFolkEntity::getUUID));
        for (VillageFolkEntity f : all) {
            Record r = record(id, f.getUUID());
            Stint s = PLAYING.get(f.getUUID());
            String voice = f.countCarried(Lutes::isLute) > 0 ? "lute" : VOICE_WORDS[Math.floorMod(f.getUUID().hashCode(), VOICE_WORDS.length)];   // [leisure]
            lines.add(f.displayNameCap() + " (" + voice + ", skill " + r.skill + "): " + (s != null ? (s.tavern ? "on now at the tavern"
                : "on now " + s.where + ", " + s.take + " in the hat") : f.getUUID().equals(tavern) ? "plays the tavern every rest day"
                : r.gaveUp >= 0 ? "gave up busking on day " + r.gaveUp : r.stints == 0 ? "has not busked yet" : "busks " + r.where)
                + "; " + r.tips + (r.tips == 1 ? " coin" : " coins") + " over " + r.stints + (r.stints == 1 ? " evening" : " evenings"));
        }
        if (all.isEmpty()) lines.add("no buskers: a folk who loves music busks on the street corners of an evening and on market day");
        else if (tavern == null && Tavern.of(id) != null) {
            lines.add("the tavern books the best for its day of rest: skill " + BOOK_SKILL + " and a hat of " + BOOK_TIPS + " coins an evening");
        }
        String s = SHORT.get(id);
        if (s != null) lines.add("waiting on " + s);
        out.put("lines", Culture.strings(lines));
        out.putInt("buskers", all.size());
        return out;
    }

    // ------------------------------------------------------------------ the tests and the pictures

    /** Tests: this busker out to play where it stands now (its own note block, or the stores'). The stint's words, or null. */
    @Nullable
    public static String startForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Stint old = PLAYING.get(f.getUUID());
        if (old != null) end(level, old);
        Stint s = start(level, v, f, new Stint(f.getUUID(), v.id(), f.blockPosition(), "by the well", false));
        return s == null ? null : s.where;
    }

    /** Tests: the busker's own look now (Culture.hold's): where it wants to be and whether it is out. */
    public static boolean holdForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return busk(f, level, v);
    }

    /** Tests: so many notes of the busker's tune, where it stands. How many notes it has played this stint. */
    public static int playForTests(ServerLevel level, VillageFolkEntity f, int notes) {
        Stint s = PLAYING.get(f.getUUID());
        if (s == null) return 0;
        for (int i = 0; i < notes; i++) play(level, s, f);
        s.lastHeld = level.getGameTime();
        return s.notes;
    }

    /** Tests: this folk stops to listen to the busker, and makes up its mind about a coin with this roll (0: it gives). */
    public static boolean listenForTests(ServerLevel level, VillageFolkEntity busker, VillageFolkEntity o, double roll) {
        Stint s = PLAYING.get(busker.getUUID());
        if (s == null) return false;
        long now = level.getGameTime();
        LISTENING.put(o.getUUID(), new Ear(busker.getUUID(), now, now + 400L));
        s.heard.add(o.getUUID());
        return tip(level, s, busker, o, roll);
    }

    /** Tests: the stint's hat, its notes and where it is, or null with the busker not out. */
    @Nullable
    public static String stintForTests(UUID folk) {
        Stint s = PLAYING.get(folk);
        return s == null ? null : (s.tavern ? "TAVERN" : "STREET") + " at=" + s.pitch.toShortString() + " take=" + s.take + " notes=" + s.notes
            + " fee=" + s.fee + " lent=" + s.lent + " lute=" + s.lute + " luteLent=" + s.luteLent;
    }

    /** Tests: the busker home now (its record kept). */
    public static void endForTests(ServerLevel level, VillageFolkEntity f) {
        Stint s = PLAYING.get(f.getUUID());
        if (s != null) end(level, s);
    }

    /** Tests: a busker's record as if it had busked a while (skill, evenings, its hat of late in coins an evening). */
    public static void recordForTests(UUID village, VillageFolkEntity f, int skill, int stints, int avgTips, String where) {
        Record r = record(village, f.getUUID());
        r.skill = skill;
        r.stints = stints;
        r.avg10 = avgTips * 10;
        r.tips = Math.max(r.tips, avgTips * stints);
        r.where = where;
        r.name = f.displayNameCap();
        save(village, f.getUUID(), r);
    }

    /** Tests: a busker's skill as the books have it. */
    public static int skillForTests(UUID village, UUID folk) {
        return record(village, folk).skill;
    }

    /** Tests: the tavern keeper's look for a busker now; whom it has booked, or null. */
    @Nullable
    public static UUID considerForTests(ServerLevel level, Villages.Village v) {
        consider(level, v);
        return booked(v.id());
    }

    /**
     * /village busk now (ops): every musician of the nearest town out to busk at its pitch now, whatever the hour,
     * and a few passers-by stopped round the first. Returns "VIEW busker ex ey ez ax ay az" for the first, and who.
     */
    public static List<String> now(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        VillageFolkEntity first = null;
        Stint firstS = null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !busker(f)) continue;
            Stint old = PLAYING.get(f.getUUID());
            if (old != null) end(level, old);
            Pitch p = pitchFor(level, v, f);
            if (p == null) continue;
            Stint s = start(level, v, f, new Stint(f.getUUID(), v.id(), p.at(), p.words(), false));
            if (s == null) continue;
            f.moveTo(p.at().getX() + 0.5, p.at().getY(), p.at().getZ() + 0.5, f.getYRot(), 0.0F);
            Culture.mark(f, Culture.Role.BUSK, "busking " + s.where);
            if (s.lute) Lutes.inHand(f);                               // [leisure]
            else Culture.prop(f, Items.NOTE_BLOCK);
            out.add(f.displayNameCap() + " " + s.where);
            if (first == null) {
                first = f;
                firstS = s;
            }
        }
        if (first == null) {
            out.add(0, "busk none: " + (SHORT.containsKey(v.id()) ? SHORT.get(v.id()) : "the town has no musicians (folk whose pastime is music)"));
            return out;
        }
        long now = level.getGameTime();
        int stood = 0;
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, first.getBoundingBox().inflate(40.0, 6.0, 40.0),
                o -> o.isAlive() && !o.isSleeping() && v.id().equals(o.ownerId()) && !PLAYING.containsKey(o.getUUID()))) {
            if (stood >= 3) break;
            LISTENING.put(o.getUUID(), new Ear(first.getUUID(), now, now + 1200L));
            int slot = Math.floorMod(o.getUUID().hashCode(), 8);
            double ang = slot * Math.PI / 4.0;
            BlockPos at = firstS.pitch.offset((int) Math.round(Math.cos(ang) * 3.0), 0, (int) Math.round(Math.sin(ang) * 3.0));
            BlockPos floor = Watch.floorAt(level, at.getX(), at.getZ(), at.getY());
            if (floor != null) o.moveTo(floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5, o.getYRot(), 0.0F);
            stood++;
        }
        BlockPos p = firstS.pitch;
        out.add(0, String.format(Locale.ROOT, "VIEW busker %.2f %.2f %.2f %.2f %.2f %.2f", p.getX() + 0.5 - 5.0, p.getY() + 3.2, p.getZ() + 0.5 + 5.0,
            p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5));
        return out;
    }

    /** /village busk: the town's buskers, their pitches, skill and hats, and who plays the tavern. */
    public static List<String> status(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        out.add("Buskers of " + Villages.name(v.id()) + ":");
        for (String l : Culture.strings(report(level, v), "lines")) out.add("  " + l);
        for (Pitch p : pitches(level, v)) out.add("  pitch " + p.words() + " at " + p.at().toShortString());
        return out;
    }
}
