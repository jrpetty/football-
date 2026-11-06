package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [borders-map] Land disputes: two towns that both want the same ground, and how they settle it (or do not).
 *
 * <p><b>How one starts.</b> Where two towns' claims overlap and no line is agreed (Borders: contested ground), and more
 * so where there is something worth having on it (an ore vein the scouts or the miners found there, a wood, a lake, good
 * open farmland), the town that holds less of it complains. So does a town whose land a neighbour has dug a mine into,
 * staked fields on, or sent its woodcutters over the line to fell (their work zones in its land); and a town whose border
 * banner a neighbour's folk pulled up, or that finds a neighbour's banner planted on its land (BorderMarkers). It goes
 * into both towns' chronicles and memories (as a quarrel over the land: WarAndPeace counts it a grievance).
 *
 * <p><b>Envoys argue it.</b> The town that complains sends an envoy with its complaint (Envoys, COMPLAINT), and a shrewd
 * or open-handed one carries an offer for the ground in its purse. The other town's elder hears it before its board and
 * answers in its own way:
 * <ul>
 * <li><b>a treaty</b>: the line agreed where it falls between them, each keeping what lies nearer its own heart, with
 *     stones to mark it (Bonds.agreeBorder);</li>
 * <li><b>a sale</b>: it takes the coin in the envoy's purse and the ground is the other's;</li>
 * <li><b>a buy-off</b>: it pays the envoy to carry home, and keeps the ground;</li>
 * <li><b>an arbiter</b>: a third town that both get on with is asked, and its elder gives the ground to whichever it
 *     thinks better of (or the nearer);</li>
 * <li><b>a refusal</b>: "it's ours, and always was". Refused twice, or between towns already on bad terms, the quarrel
 *     sours into bad blood (the relation falls every day it stands, toward a feud: Diplomacy), and a feud over land is a
 *     cause, and a goal, of war (WarAndPeace: a border where we say it runs). A war won takes the land (Borders.afterWar).</li>
 * </ul>
 * With nobody free to walk over, the word goes by mouth and is answered the same way, minus the coin (nothing passes
 * between towns that nobody carries).
 *
 * <p>Kept with the world (Borders); shown on the Lands page, the map table (a flag on the ground in dispute), the board
 * and in what folk say of their neighbours.
 */
public final class BorderDisputes {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private BorderDisputes() {}

    /** Contested chunks it takes before two towns quarrel over them. */
    static final int ENOUGH = 3;
    /** Days after a dispute between two towns is over before another may start. */
    static final int QUIET_DAYS = 6;
    /** Days an envoy has to come back with an answer before the word goes by mouth instead. */
    static final int ENVOY_DAYS = 3;
    /** Coins an offer for the ground is worth a chunk. */
    static final int COIN_A_CHUNK = 3;

    public enum Cause {
        OVERLAP("the ground between them"), RESOURCE("what lies on the line"), WORKINGS("work dug over the line"), BANNER("a border banner");

        public final String words;
        Cause(String words) { this.words = words; }
    }

    public enum State {
        OPEN("open"), ENVOY("an envoy sent"), FEUD("soured into bad blood"), SETTLED("settled"), LAPSED("lapsed");

        public final String words;
        State(String words) { this.words = words; }
    }

    /** One dispute: who complains of whom, over what, where, and how it stands. */
    public static final class Dispute {
        int id;
        UUID a, b;
        Cause cause;
        String subject = "";
        BlockPos at = BlockPos.ZERO;
        long[] chunks = new long[0];
        String dim = "";
        long began, next, ended = -1;
        State state = State.OPEN;
        int refusals;
        long envoyDay = -1;
        String outcome = "";

        public UUID other(UUID town) { return town.equals(a) ? b : a; }
        public boolean live() { return state == State.OPEN || state == State.ENVOY || state == State.FEUD; }
        public String subject() { return subject; }
        public State state() { return state; }
        public BlockPos at() { return at; }
        public UUID claimant() { return a; }
        public UUID against() { return b; }
        public String outcome() { return outcome; }
        public Cause cause() { return cause; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("id", id);
            t.putString("a", a.toString());
            t.putString("b", b.toString());
            t.putString("cause", cause.name());
            t.putString("subject", subject);
            t.putLong("at", at.asLong());
            t.putLongArray("chunks", chunks);
            t.putString("dim", dim);
            t.putLong("began", began);
            t.putLong("next", next);
            t.putLong("ended", ended);
            t.putString("state", state.name());
            t.putInt("refusals", refusals);
            t.putLong("envoy", envoyDay);
            t.putString("outcome", outcome);
            return t;
        }

        @Nullable
        static Dispute load(CompoundTag t) {
            Dispute d = new Dispute();
            d.a = Borders.uuid(t.getString("a"));
            d.b = Borders.uuid(t.getString("b"));
            if (d.a == null || d.b == null) return null;
            try {
                d.cause = Cause.valueOf(t.getString("cause"));
                d.state = State.valueOf(t.getString("state"));
            } catch (IllegalArgumentException e) {
                return null;
            }
            d.id = t.getInt("id");
            d.subject = t.getString("subject");
            d.at = BlockPos.of(t.getLong("at"));
            d.chunks = t.getLongArray("chunks");
            d.dim = t.getString("dim");
            d.began = t.getLong("began");
            d.next = t.getLong("next");
            d.ended = t.getLong("ended");
            d.refusals = t.getInt("refusals");
            d.envoyDay = t.getLong("envoy");
            d.outcome = t.getString("outcome");
            return d;
        }
    }

    private static final Map<String, Long> LOOKED = new HashMap<>();

    static void resetForTests() {
        LOOKED.clear();
    }

    // ------------------------------------------------------------------ asking

    /** The live dispute between the two (either way round), or null. */
    @Nullable
    public static Dispute open(UUID x, UUID y) {
        Borders b = Borders.data();
        if (b == null) return null;
        for (Dispute d : b.disputes) {
            if (d.live() && (d.a.equals(x) && d.b.equals(y) || d.a.equals(y) && d.b.equals(x))) return d;
        }
        return null;
    }

    @Nullable
    static Dispute firstOpen(Borders b, UUID town) {
        for (Dispute d : b.disputes) if (d.live() && (d.a.equals(town) || d.b.equals(town))) return d;
        return null;
    }

    /** Every dispute, for the map table (live ones, and the last few settled). */
    static List<Dispute> all(Borders b) {
        return new ArrayList<>(b.disputes);
    }

    @Nullable
    static Dispute lastBetween(Borders b, UUID x, UUID y) {
        Dispute last = null;
        for (Dispute d : b.disputes) {
            if (d.a.equals(x) && d.b.equals(y) || d.a.equals(y) && d.b.equals(x)) last = d;
        }
        return last;
    }

    // ------------------------------------------------------------------ the day's look

    /** Each dimension, every minute by day: new disputes looked for, and the live ones taken a step on (once a day each). */
    static void tick(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        if (t < 1000L || t > 11000L) return;
        long now = level.getGameTime();
        String d = Borders.dim(level);
        if (now - LOOKED.getOrDefault(d, -100000L) < 1200L) return;
        LOOKED.put(d, now);
        look(level);
        step(level);
    }

    /** Tests and commands: a look for disputes and a step for every live one, now. */
    public static void lookAndStepForTests(ServerLevel level) {
        look(level);
        step(level);
    }

    /** New disputes: overlapping claims (with whatever is worth having on them), and work dug over the line. */
    static void look(ServerLevel level) {
        Borders b = Borders.data(level.getServer());
        Borders.Land l = b.lands.get(Borders.dim(level));
        if (l == null) return;
        long day = level.getDayTime() / 24000L;
        // Overlapping claims, pair by pair.
        Map<String, List<Long>> between = new HashMap<>();
        for (Long2ObjectOpenHashMap.Entry<UUID> e : l.contested.long2ObjectEntrySet()) {
            UUID o = l.owner.get(e.getLongKey());
            if (o == null) continue;
            between.computeIfAbsent(Ledger.pair(o, e.getValue()), k -> new ArrayList<>()).add(e.getLongKey());
        }
        for (Map.Entry<String, List<Long>> e : between.entrySet()) {
            if (e.getValue().size() < ENOUGH) continue;
            long c0 = e.getValue().get(0);
            UUID x = l.owner.get(c0), y = l.contested.get(c0);
            if (x == null || y == null || Villages.get(x) == null || Villages.get(y) == null) continue;
            if (!quietOver(b, x, y, day)) continue;
            if (!Ledger.knowEachOther(x, y)) continue;                   // strangers: they meet first (Diplomacy)
            overlap(level, b, l, x, y, e.getValue(), day);
        }
        // Work dug over the line: a town's mine, fields or woods in land another holds outright.
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            for (BlockPos s : workings(v.id())) {
                long c = ChunkPos.asLong(s.getX() >> 4, s.getZ() >> 4);
                UUID o = l.owner.get(c);
                if (o == null || o.equals(v.id()) || l.contested.containsKey(c) || Villages.get(o) == null) continue;
                if (!quietOver(b, o, v.id(), day) || !Ledger.knowEachOther(o, v.id())) continue;
                String what = what(v.id(), s);
                raise(level, b, o, v.id(), Cause.WORKINGS, Villages.name(v.id()) + "'s " + what + " over the line", s, new long[]{ c }, day);
                break;
            }
        }
    }

    /** No live dispute between the two, and none ended these last few days. */
    static boolean quietOver(Borders b, UUID x, UUID y, long day) {
        Dispute last = lastBetween(b, x, y);
        if (last == null) return true;
        if (last.live()) return false;
        return day - last.ended >= QUIET_DAYS;
    }

    /** A town's work out in the land: its mine, and its folk's fields, woods and pens. */
    static List<BlockPos> workings(UUID town) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos mine = TownMine.siteOf(town);
        if (mine != null) out.add(mine);
        for (AssistantEntity a : Villages.folkOf(town)) {
            WorkZone z = a.workZone();
            if (z == null) continue;
            AssistantEntity.StationTask t = a.stationTask();
            if (t == AssistantEntity.StationTask.FARM || t == AssistantEntity.StationTask.WOOD || t == AssistantEntity.StationTask.MINE) out.add(z.center());
        }
        return out;
    }

    static String what(UUID town, BlockPos s) {
        BlockPos mine = TownMine.siteOf(town);
        if (mine != null && mine.equals(s)) return "mine";
        for (AssistantEntity a : Villages.folkOf(town)) {
            WorkZone z = a.workZone();
            if (z == null || !z.center().equals(s)) continue;
            return switch (a.stationTask()) {
                case FARM -> "fields";
                case WOOD -> "woodcutters";
                case MINE -> "pit";
                default -> "work";
            };
        }
        return "work";
    }

    /** Two towns' claims overlap: what lies there decides what they quarrel over; the town that holds less complains. */
    static void overlap(ServerLevel level, Borders b, Borders.Land l, UUID x, UUID y, List<Long> chunks, long day) {
        int ofX = 0;
        for (long c : chunks) if (x.equals(l.owner.get(c))) ofX++;
        UUID claimant = ofX * 2 < chunks.size() ? x : ofX * 2 > chunks.size() ? y
            : com.jrpetty.mcassistant.village.Chronicle.foundedOn(x) <= com.jrpetty.mcassistant.village.Chronicle.foundedOn(y) ? x : y;
        UUID other = claimant.equals(x) ? y : x;
        String[] res = resource(level, b, chunks);
        long[] cs = new long[Math.min(64, chunks.size())];
        for (int i = 0; i < cs.length; i++) cs[i] = chunks.get(i);
        long mid = chunks.get(chunks.size() / 2);
        BlockPos at = res[1] != null ? BlockPos.of(Long.parseLong(res[1]))
            : new BlockPos(ChunkPos.getX(mid) * 16 + 8, 64, ChunkPos.getZ(mid) * 16 + 8);
        raise(level, b, claimant, other, res[0] == null ? Cause.OVERLAP : Cause.RESOURCE, res[0] == null ? "the land between "
            + Villages.name(x) + " and " + Villages.name(y) : res[0], at, cs, day);
    }

    /** What is worth having on these chunks, in words, and where (or nulls): ore, a lake, a wood, good open farmland. */
    static String[] resource(ServerLevel level, Borders b, List<Long> chunks) {
        MapTerrain terrain = MapTerrain.of(level);
        // Ore the scouts or the miners found there.
        for (Villages.Village v : Villages.every()) {
            for (Scouts.Find f : Scouts.atlas(v.id())) {
                if (f.kind() != Scouts.Kind.ORE) continue;
                long c = ChunkPos.asLong(f.at().getX() >> 4, f.at().getZ() >> 4);
                if (chunks.contains(c)) return new String[]{ "the " + vein(f.label().replace(" showing in the rock", "")) + " vein", Long.toString(f.at().asLong()) };
            }
        }
        for (long c : chunks) {
            BlockPos mid = new BlockPos(ChunkPos.getX(c) * 16 + 8, 48, ChunkPos.getZ(c) * 16 + 8);
            if (!level.isLoaded(mid)) continue;
            List<BlockPos> ore = OreNotes.near(level, mid, 12);
            if (!ore.isEmpty()) {
                String kind = Scouts.ore(level.getBlockState(ore.get(0)));
                return new String[]{ "the " + (kind == null ? "ore" : vein(kind)) + " vein", Long.toString(ore.get(0).asLong()) };
            }
        }
        int water = 0, wood = 0, open = 0;
        long w = 0, f = 0, o = 0;
        for (long c : chunks) {
            switch (terrain.ground(ChunkPos.getX(c), ChunkPos.getZ(c))) {
                case WATER -> { water++; w = c; }
                case FOREST -> { wood++; f = c; }
                case OPEN -> { open++; o = c; }
                default -> { }
            }
        }
        if (water >= 2) return new String[]{ "the lake", Long.toString(centre(w).asLong()) };
        if (wood >= 3) return new String[]{ "the wood", Long.toString(centre(f).asLong()) };
        if (open >= 4) return new String[]{ "the good farmland", Long.toString(centre(o).asLong()) };
        return new String[]{ null, null };
    }

    /** "diamonds" -> "diamond": the vein's word. */
    static String vein(String ore) {
        return ore.endsWith("s") && !ore.equals("lapis") ? ore.substring(0, ore.length() - 1) : ore;
    }

    static BlockPos centre(long c) {
        return new BlockPos(ChunkPos.getX(c) * 16 + 8, 64, ChunkPos.getZ(c) * 16 + 8);
    }

    /**
     * A dispute begun: in both towns' chronicles and memories (a quarrel over the land, which their war books count), the
     * relation the worse for it, on both towns' Lands pages; the claimant's envoy goes today. Returns the dispute.
     */
    static Dispute raise(ServerLevel level, Borders b, UUID claimant, UUID other, Cause cause, String subject, BlockPos at, long[] chunks, long day) {
        Dispute live = open(claimant, other);
        if (live != null) return live;
        Dispute d = new Dispute();
        d.id = b.nextDispute++;
        d.a = claimant;
        d.b = other;
        d.cause = cause;
        d.subject = subject;
        d.at = at.immutable();
        d.chunks = chunks;
        d.dim = Borders.dim(level);
        d.began = day;
        d.next = day;
        b.disputes.add(d);
        while (b.disputes.size() > 48) {
            int drop = -1;
            for (int i = 0; i < b.disputes.size(); i++) if (!b.disputes.get(i).live()) { drop = i; break; }
            if (drop < 0) break;
            b.disputes.remove(drop);
        }
        String an = Villages.name(claimant), bn = Villages.name(other);
        String line = switch (cause) {
            case OVERLAP -> an + " and " + bn + " both claim " + subject + ", and neither will give way";
            case RESOURCE -> an + " and " + bn + " both claim " + subject + " on the line between them";
            case WORKINGS -> an + " complains of " + subject;
            case BANNER -> an + " complains of " + bn + ": " + subject;
        };
        Villages.tell(claimant, day, line);
        Villages.tell(other, day, line);
        Bonds.remember(claimant, other, day, -3, "we quarrelled over the land: " + subject);
        Ledger.relate(claimant, other, -3);
        Borders.note(b, claimant, day, "a dispute with " + bn + " over " + subject, false);
        Borders.note(b, other, day, "a dispute with " + an + " over " + subject, false);
        b.version++;
        b.setDirty();
        LOG.info("[MCA-BORDERS] dispute #{}: {} against {} over {} ({})", d.id, an, bn, subject, cause);
        return d;
    }

    /** [borders-map] A dispute begun from outside (a banner pulled up or planted: BorderMarkers; an admin; a test). */
    public static Dispute raise(ServerLevel level, UUID claimant, UUID other, Cause cause, String subject, BlockPos at) {
        Borders b = Borders.data(level.getServer());
        long day = level.getDayTime() / 24000L;
        LongOpenHashSet cs = chunksBetween(b, level, claimant, other);
        cs.add(ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4));
        long[] arr = cs.toLongArray();
        if (arr.length > 64) arr = java.util.Arrays.copyOf(arr, 64);
        return raise(level, b, claimant, other, cause, subject, at, arr, day);
    }

    // ------------------------------------------------------------------ a step a day

    static void step(ServerLevel level) {
        Borders b = Borders.data(level.getServer());
        long day = level.getDayTime() / 24000L;
        String d0 = Borders.dim(level);
        for (Dispute d : new ArrayList<>(b.disputes)) {
            if (!d.live() || !d.dim.equals(d0) || d.next > day) continue;
            d.next = day + 1;
            stepOne(level, b, d, day);
        }
    }

    static void stepOne(ServerLevel level, Borders b, Dispute d, long day) {
        Villages.Village va = Villages.get(d.a), vb = Villages.get(d.b);
        if (va == null || vb == null) {
            end(b, d, State.LAPSED, day, "the matter lapsed: one of the towns is no more");
            return;
        }
        if (Wars.atWar(d.a, d.b)) return;                                 // the war settles it (Borders.afterWar)
        // A quarrel over contested ground that is not contested any more (the line agreed, a town shrunk): over.
        if ((d.cause == Cause.OVERLAP || d.cause == Cause.RESOURCE) && !stillContested(b, level, d)) {
            end(b, d, State.SETTLED, day, "the matter settled itself: the ground is no longer claimed by both");
            return;
        }
        switch (d.state) {
            case OPEN -> {
                if (sendEnvoy(level, b, d, va, vb, day)) return;
                if (day - d.began >= 2) decide(level, b, d, day, null);      // nobody free to walk over: by word of mouth
            }
            case ENVOY -> {
                if (day - d.envoyDay > ENVOY_DAYS) decide(level, b, d, day, null);
            }
            case FEUD -> {
                // Bad blood over the land: worse every day it stands, until a truce or a war.
                if (!Bonds.truce(d.a, d.b, day)) Ledger.relate(d.a, d.b, -2);
                if (day % 4 == 0) Bonds.remember(d.a, d.b, day, -2, "the quarrel over " + d.subject + " goes on (the land)");
                // A truce, or the terms mended: talks again.
                if (Ledger.relation(d.a, d.b) > Diplomacy.UNEASY && day % 3 == 0) {
                    d.state = State.OPEN;
                    d.refusals = 0;
                    b.setDirty();
                }
            }
            default -> { }
        }
    }

    static boolean stillContested(Borders b, ServerLevel level, Dispute d) {
        Borders.Land l = b.lands.get(d.dim);
        if (l == null) return false;
        for (long c : d.chunks) {
            UUID o = l.owner.get(c), r = l.contested.get(c);
            if (o != null && r != null && (o.equals(d.a) && r.equals(d.b) || o.equals(d.b) && r.equals(d.a))) return true;
        }
        return false;
    }

    /** The claimant's envoy sets out with the complaint (and, from a shrewd or open-handed elder with coin, an offer). */
    static boolean sendEnvoy(ServerLevel level, Borders b, Dispute d, Villages.Village va, Villages.Village vb, long day) {
        if (Envoys.travelling(d.a, d.b)) return false;
        if (!Envoys.send(level, va, vb, Envoys.Errand.COMPLAINT, day)) return false;
        d.state = State.ENVOY;
        d.envoyDay = day;
        Envoys.Temper t = Envoys.temper(d.a);
        int offer = price(d);
        if ((d.cause == Cause.RESOURCE || d.cause == Cause.OVERLAP) && (t == Envoys.Temper.SHREWD || t == Envoys.Temper.GENEROUS || t == Envoys.Temper.WARM)
                && Ledger.coins(d.a) >= offer * 3) {
            Caravans.Trip trip = tripOf(d.a, d.b);
            if (trip != null) trip.purse += Ledger.takeCoins(d.a, offer);
        }
        b.setDirty();
        return true;
    }

    /** What the ground is worth in coin. */
    static int price(Dispute d) {
        return Math.max(6, Math.min(40, d.chunks.length * COIN_A_CHUNK + (d.cause == Cause.RESOURCE ? 8 : 0)));
    }

    @Nullable
    static Caravans.Trip tripOf(UUID from, UUID to) {
        for (AssistantEntity x : Villages.folkOf(from)) {
            if (x instanceof VillageFolkEntity f && f.trip() != null && f.trip().errand == Envoys.Errand.COMPLAINT
                && f.trip().from.equals(from) && f.trip().to.equals(to)) return f.trip();
        }
        return null;
    }

    // ------------------------------------------------------------------ the envoy heard

    /** [borders-map] What the claimant's envoy says before the other's board (Envoys.asks), or null if it is not about land. */
    @Nullable
    static String asks(UUID from, UUID host, Envoys.Errand errand, Caravans.Trip t) {
        if (errand != Envoys.Errand.COMPLAINT) return null;
        Dispute d = open(from, host);
        if (d == null || d.state != State.ENVOY || !d.a.equals(from)) return null;
        String elder = Villages.elderName(from), fn = Villages.name(from);
        String sender = elder.isEmpty() ? "The folk of " + fn : "Elder " + elder + " of " + fn;
        return sender + (elder.isEmpty() ? " say " : " says ") + d.subject + " is ours by right" + (t.purse > 0 ? ", and offers " + t.purse + " coins for your claim to it" : "")
            + ". Will you agree the line?";
    }

    /** [borders-map] The answer to it (Envoys.answer), or null if it is not about land. */
    @Nullable
    static Envoys.Answer answer(ServerLevel level, UUID host, UUID from, Envoys.Errand errand, VillageFolkEntity envoy, Caravans.Trip t) {
        if (errand != Envoys.Errand.COMPLAINT) return null;
        Dispute d = open(from, host);
        if (d == null || d.state != State.ENVOY || !d.a.equals(from)) return null;
        Borders b = Borders.data(level.getServer());
        long day = level.getDayTime() / 24000L;
        Outcome o = weigh(level, d, t);
        return new Envoys.Answer(o.kind == Kind.TREATY || o.kind == Kind.SOLD || o.kind == Kind.ARBITER, o.said, () -> {
            apply(level, b, d, o, day, t);
            t.outcome = o.report;
        });
    }

    enum Kind { TREATY, SOLD, BOUGHT_OFF, ARBITER, REFUSED }

    record Outcome(Kind kind, String said, String report, @Nullable UUID arbiter, @Nullable UUID winner, int coins) {}

    /** How the other town's elder answers: by its temper, the terms they are on, what binds them, and any offer. */
    static Outcome weigh(ServerLevel level, Dispute d, @Nullable Caravans.Trip t) {
        UUID host = d.b, from = d.a;
        Envoys.Temper ht = Envoys.temper(host);
        int r = Ledger.relation(host, from);
        int score = r + (ht.kindly() ? 15 : 0) + (ht == Envoys.Temper.PRICKLY ? -15 : 0) + (ht == Envoys.Temper.WARY ? -10 : 0)
            + Envoys.chemistry(host, from) * 10 + Math.min(15, Bonds.ties(host, from) * 5)
            + (Envoys.pact(host, from) ? 10 : 0) + (Envoys.allied(host, from) ? 25 : 0)
            - (d.cause == Cause.RESOURCE ? 10 : 0) + (d.cause == Cause.WORKINGS || d.cause == Cause.BANNER ? 5 : 0);
        String fn = Villages.name(from), hn = Villages.name(host);
        int purse = t == null ? 0 : t.purse;
        if (purse > 0 && ht != Envoys.Temper.PRICKLY && score + purse * 2 >= 0) {
            return new Outcome(Kind.SOLD, "Coin for it? Then " + d.subject + " is " + fn + "'s, and welcome.",
                hn + " sold us its claim to " + d.subject + " for " + purse + " coins", null, from, purse);
        }
        if (score >= 10) {
            return new Outcome(Kind.TREATY, "Let's walk the line together and set the stones. Each keeps what lies nearer home.",
                "we agreed the line with " + hn + ", each keeping what lies nearer home", null, null, 0);
        }
        if (score >= -30) {
            Villages.Village arb = arbiter(host, from);
            if (arb != null) {
                UUID a = arb.id();
                int ra = Ledger.relation(a, from), rb = Ledger.relation(a, host);
                UUID winner = ra > rb ? from : rb > ra ? host : nearer(d, from, host);
                return new Outcome(Kind.ARBITER, "We'll put it to the elder of " + Villages.name(a) + ", and abide by what they say.",
                    "we put " + d.subject + " to the elder of " + Villages.name(a) + ", who gave it to " + Villages.name(winner), a, winner, 0);
            }
        }
        int buy = Math.max(4, price(d) / 2);
        if (t != null && (ht == Envoys.Temper.STEADY || ht == Envoys.Temper.GENEROUS || ht == Envoys.Temper.SHREWD)
                && Ledger.coins(host) >= buy * 4 && score >= -25) {
            return new Outcome(Kind.BOUGHT_OFF, "We'll keep it, but we'll not have bad blood over it. Take this for your trouble.",
                hn + " kept " + d.subject + " and paid us " + buy + " coins to drop our claim", null, host, buy);
        }
        return new Outcome(Kind.REFUSED, FolkTalk.pick(level.getRandom(), "Your land? It's ours, and always was!",
            "Tell " + fn + " to keep to their own side of the line.", "Not a stone of it. Go home."),
            hn + " told us " + d.subject + " is theirs", null, null, 0);
    }

    /** A third town that both get on with and know: its elder is asked. */
    @Nullable
    static Villages.Village arbiter(UUID x, UUID y) {
        Villages.Village vx = Villages.get(x);
        Villages.Village best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Villages.Village c : Villages.every()) {
            if (c.id().equals(x) || c.id().equals(y) || vx == null || !c.dim().equals(vx.dim())) continue;
            if (!Ledger.knowEachOther(x, c.id()) || !Ledger.knowEachOther(y, c.id())) continue;
            int rx = Ledger.relation(x, c.id()), ry = Ledger.relation(y, c.id());
            if (rx < Diplomacy.FRIENDLY || ry < Diplomacy.FRIENDLY) continue;
            if (Math.min(rx, ry) > bestScore) {
                bestScore = Math.min(rx, ry);
                best = c;
            }
        }
        return best;
    }

    /** The town whose heart is nearer the middle of the ground. */
    static UUID nearer(Dispute d, UUID x, UUID y) {
        Villages.Village vx = Villages.get(x), vy = Villages.get(y);
        if (vx == null) return y;
        if (vy == null) return x;
        return vx.centre().distSqr(d.at) <= vy.centre().distSqr(d.at) ? x : y;
    }

    /** Settled, or not, by word of mouth (nobody carried it, so no coin passes). */
    static void decide(ServerLevel level, Borders b, Dispute d, long day, @Nullable Caravans.Trip t) {
        Outcome o = weigh(level, d, t);
        if (t == null && (o.kind == Kind.SOLD || o.kind == Kind.BOUGHT_OFF)) o = new Outcome(Kind.REFUSED, "", Villages.name(d.b) + " would not give way", null, null, 0);
        apply(level, b, d, o, day, t);
    }

    /** Tests: the dispute heard and answered now by word of mouth, as the other town's elder would answer it. */
    public static String decideForTests(ServerLevel level, Dispute d) {
        Borders b = Borders.data(level.getServer());
        decide(level, b, d, level.getDayTime() / 24000L, null);
        return d.state.name() + ": " + d.outcome;
    }

    /**
     * Tests: the claimant's envoy heard before the other town's board, carrying this much coin as its offer, as Envoys
     * would hear it (the answer, then what it does). The dispute's state, what the envoy brings home, and its purse.
     */
    public static String hearForTests(ServerLevel level, Dispute d, int offer) {
        Caravans.Trip t = new Caravans.Trip(d.a, d.b, List.of());
        t.errand = Envoys.Errand.COMPLAINT;
        t.purse = Ledger.takeCoins(d.a, offer);
        d.state = State.ENVOY;
        d.envoyDay = level.getDayTime() / 24000L;
        String said = asks(d.a, d.b, t.errand, t);
        Envoys.Answer a = answer(level, d.b, d.a, t.errand, null, t);
        if (a == null) return "not heard";
        if (a.effect() != null) a.effect().run();
        Ledger.addCoins(d.a, t.purse);                                  // the envoy home with its purse
        return d.state.name() + " | asked: " + said + " | answered: " + a.said() + " | brings home: " + t.outcome + " | purse " + t.purse;
    }

    /** What the answer does: the ground given, fixed and marked; the coin carried; or the quarrel the worse. */
    static void apply(ServerLevel level, Borders b, Dispute d, Outcome o, long day, @Nullable Caravans.Trip t) {
        String an = Villages.name(d.a), bn = Villages.name(d.b);
        switch (o.kind) {
            case TREATY -> {
                // The line where it falls between them: each chunk to the town that holds it more easily.
                Borders.Land l = b.lands.get(d.dim);
                List<Long> toA = new ArrayList<>(), toB = new ArrayList<>();
                for (long c : d.chunks) {
                    float ha = hold(l, d.a, c), hb = hold(l, d.b, c);
                    (ha <= hb ? toA : toB).add(c);
                }
                Borders.give(b, d.dim, toA, d.a);
                Borders.give(b, d.dim, toB, d.b);
                Ledger.relate(d.a, d.b, 5);
                end(b, d, State.SETTLED, day, "by treaty: the line agreed between " + an + " and " + bn);
                if (!Bonds.border(d.a, d.b)) Bonds.agreeBorder(d.a, d.b, day);
            }
            case SOLD -> {
                if (t != null && t.purse > 0) {
                    Ledger.addCoins(d.b, t.purse);
                    t.purse = 0;
                }
                Borders.give(b, d.dim, list(d.chunks), d.a);
                Bonds.remember(d.a, d.b, day, 3, bn + " sold " + an + " its claim to " + d.subject);
                Ledger.relate(d.a, d.b, 4);
                end(b, d, State.SETTLED, day, "by payment: " + bn + " sold its claim to " + an + " for " + o.coins + " coins");
            }
            case BOUGHT_OFF -> {
                int paid = t == null ? 0 : Ledger.takeCoins(d.b, o.coins);
                if (t != null) t.purse += paid;
                Borders.give(b, d.dim, list(d.chunks), d.b);
                Bonds.remember(d.a, d.b, day, 2, bn + " paid " + an + " to drop its claim to " + d.subject);
                Ledger.relate(d.a, d.b, 3);
                end(b, d, State.SETTLED, day, "by payment: " + bn + " kept it and paid " + an + " " + paid + " coins");
            }
            case ARBITER -> {
                UUID w = o.winner == null ? nearer(d, d.a, d.b) : o.winner;
                Borders.give(b, d.dim, list(d.chunks), w);
                String cn = o.arbiter == null ? "a neighbour" : Villages.name(o.arbiter);
                if (o.arbiter != null) {
                    Villages.tell(o.arbiter, day, "our elder settled the quarrel between " + an + " and " + bn + " over " + d.subject
                        + ", in " + Villages.name(w) + "'s favour");
                    Ledger.relate(d.a, o.arbiter, w.equals(d.a) ? 3 : -1);
                    Ledger.relate(d.b, o.arbiter, w.equals(d.b) ? 3 : -1);
                }
                Bonds.remember(d.a, d.b, day, 2, cn + " settled our quarrel over " + d.subject);
                Ledger.relate(d.a, d.b, 3);
                end(b, d, State.SETTLED, day, "by arbitration: the elder of " + cn + " gave " + d.subject + " to " + Villages.name(w));
            }
            case REFUSED -> {
                d.refusals++;
                Ledger.relate(d.a, d.b, -6);
                Bonds.remember(d.a, d.b, day, -4, bn + " told us the land is theirs: " + d.subject);
                if (d.refusals >= 2 || Ledger.relation(d.a, d.b) <= Diplomacy.FEUD + 10) {
                    d.state = State.FEUD;
                    Ledger.relate(d.a, d.b, -10);
                    Diplomacy.announce(d.a, d.b, Ledger.relation(d.a, d.b), day);
                    String line = "the quarrel between " + an + " and " + bn + " over " + d.subject + " soured into bad blood";
                    Villages.tell(d.a, day, line);
                    Villages.tell(d.b, day, line);
                    d.outcome = "refused twice: bad blood";
                } else {
                    d.state = State.OPEN;
                    d.next = day + 3;
                    Villages.tell(d.a, day, bn + " would not give way over " + d.subject);
                    d.outcome = "refused";
                }
                Borders.note(b, d.a, day, bn + " refused our claim to " + d.subject, false);
                b.setDirty();
            }
        }
        b.version++;
    }

    static float hold(@Nullable Borders.Land l, UUID town, long c) {
        if (l == null) return 9F;
        it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap h = l.hold.get(town);
        return h == null ? 9F : h.getOrDefault(c, 9F);
    }

    static List<Long> list(long[] a) {
        List<Long> out = new ArrayList<>();
        for (long x : a) out.add(x);
        return out;
    }

    /** Over: its outcome in both chronicles and on both Lands pages; the markers looked to again. */
    static void end(Borders b, Dispute d, State s, long day, String how) {
        d.state = s;
        d.ended = day;
        d.outcome = how;
        String line = "the dispute between " + Villages.name(d.a) + " and " + Villages.name(d.b) + " over " + d.subject + " was " + how;
        if (Villages.get(d.a) != null) Villages.tell(d.a, day, line);
        if (Villages.get(d.b) != null) Villages.tell(d.b, day, line);
        Borders.note(b, d.a, day, "over " + d.subject + ": " + how, false);
        Borders.note(b, d.b, day, "over " + d.subject + ": " + how, false);
        BorderMarkers.swapSoon(d.a, d.b);
        b.version++;
        b.setDirty();
        LOG.info("[MCA-BORDERS] dispute #{} {}: {}", d.id, s, how);
    }

    // ------------------------------------------------------------------ closing from outside

    /** Every live dispute of a town closed (it fell to ruin). */
    static void closeAll(Borders b, UUID town, String how) {
        long day = Bonds.today();
        for (Dispute d : b.disputes) {
            if (!d.live() || !(d.a.equals(town) || d.b.equals(town))) continue;
            d.state = State.LAPSED;
            d.ended = day;
            d.outcome = how;
        }
        b.setDirty();
    }

    static void closeBetween(Borders b, UUID x, UUID y, long day, String how) {
        for (Dispute d : b.disputes) {
            if (d.live() && (d.a.equals(x) && d.b.equals(y) || d.a.equals(y) && d.b.equals(x))) end(b, d, State.SETTLED, day, how);
        }
    }

    /** Their elders agreed the border (Bonds.agreeBorder): a quarrel over the ground between them is over. */
    static void settledByLine(UUID x, UUID y, long day) {
        Borders b = Borders.data();
        if (b == null) return;
        for (Dispute d : b.disputes) {
            if (!d.live() || !(d.a.equals(x) && d.b.equals(y) || d.a.equals(y) && d.b.equals(x))) continue;
            if (d.cause == Cause.OVERLAP || d.cause == Cause.RESOURCE) end(b, d, State.SETTLED, day, "settled: the border was agreed, with stones to mark it");
        }
    }

    /** The chunks in dispute between two towns: every live dispute's, and the ground both claim now. */
    static LongOpenHashSet chunksBetween(Borders b, ServerLevel level, UUID x, UUID y) {
        LongOpenHashSet out = new LongOpenHashSet();
        for (Dispute d : b.disputes) {
            if (d.live() && (d.a.equals(x) && d.b.equals(y) || d.a.equals(y) && d.b.equals(x))) for (long c : d.chunks) out.add(c);
        }
        Borders.Land l = b.lands.get(Borders.dim(level));
        if (l != null) {
            for (Long2ObjectOpenHashMap.Entry<UUID> e : l.contested.long2ObjectEntrySet()) {
                UUID o = l.owner.get(e.getLongKey());
                if (o != null && (o.equals(x) && e.getValue().equals(y) || o.equals(y) && e.getValue().equals(x))) out.add(e.getLongKey());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the books

    static ListTag report(Borders b, UUID town) {
        ListTag out = new ListTag();
        List<Dispute> mine = new ArrayList<>();
        for (Dispute d : b.disputes) if (d.a.equals(town) || d.b.equals(town)) mine.add(d);
        mine.sort(Comparator.comparing((Dispute d) -> !d.live()).thenComparing(d -> -d.began));
        for (Dispute d : mine) {
            if (out.size() >= 8) break;
            CompoundTag t = new CompoundTag();
            UUID o = d.other(town);
            t.putString("with", Villages.name(o));
            t.putString("subject", d.subject);
            t.putString("cause", d.cause.words);
            t.putString("state", d.state.words);
            t.putBoolean("live", d.live());
            t.putBoolean("ours", d.a.equals(town));
            t.putLong("began", d.began + 1);
            t.putString("outcome", d.outcome);
            t.putInt("chunks", d.chunks.length);
            out.add(t);
        }
        return out;
    }

    /** /village borders disputes: every dispute in a line. */
    public static List<String> lines() {
        List<String> out = new ArrayList<>();
        Borders b = Borders.data();
        if (b == null) return out;
        for (Dispute d : b.disputes) {
            out.add(String.format(Locale.ROOT, "#%d %s v %s over %s (%s, %d chunks) since day %d: %s%s", d.id, Villages.name(d.a), Villages.name(d.b),
                d.subject, d.cause.name().toLowerCase(Locale.ROOT), d.chunks.length, d.began + 1, d.state.words,
                d.outcome.isEmpty() ? "" : " — " + d.outcome));
        }
        return out;
    }

    /** Admin and the pictures: every live dispute of this town settled now, by treaty. */
    public static int settleAll(ServerLevel level, UUID town) {
        Borders b = Borders.data(level.getServer());
        long day = level.getDayTime() / 24000L;
        int n = 0;
        for (Dispute d : new ArrayList<>(b.disputes)) {
            if (!d.live() || !(d.a.equals(town) || d.b.equals(town))) continue;
            apply(level, b, d, new Outcome(Kind.TREATY, "", "", null, null, 0), day, null);
            n++;
        }
        return n;
    }
}
