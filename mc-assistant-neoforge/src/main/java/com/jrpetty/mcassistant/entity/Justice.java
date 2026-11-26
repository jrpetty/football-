package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.JusticeConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpe] Justice: crimes by players (and the serious ones by folk), as the watch comes to know of them, and only so.
 *
 * <p><b>Nobody knows without a witness or a clue.</b> A deed is a case from the moment it is done, but the town knows
 * nothing of it until somebody tells: a folk that saw it with its own eyes (Witness.sees) goes to the watch when its
 * nature lets it (an honest or sociable one soon, a shy or shifty one late, the victim at once), and may tell a neighbour
 * first. A body is found; a householder misses its things. What a witness knows lives in that folk's own head: killed
 * before it has told the watch or anybody, it takes it to the grave; what it told a neighbour survives as that
 * neighbour's hearsay, at half the weight. The watch weighs what it has (a witness three, hearsay one and a half, who the
 * dead was last seen with, boot prints by the body, the weapon, a motive, stolen goods found on a suspect) and, when it
 * is enough, goes for the culprit: a summons with a date for the lesser charges, a warrant and an arrest, walked, for
 * the graver. Not enough, and the case goes unsolved.
 *
 * <p><b>Accidents.</b> A small hurt to a folk, once, with no pattern of intent, is no crime if the player says sorry in
 * time (the talk menu's Apologise, for {@link JusticeConfig#apologySeconds}); the folk forgives it or not by its nature
 * and the hurt, and paying for the healer goes a long way. Unapologised, or again, it is a complaint; complaints become
 * an assault charge. A killing is never let go as an accident without the court: the court can find it one.
 *
 * <p>Arrest (the guard's walk with the warrant), restraint (Restraint), the jail (Jail) and the court (Assize) follow.
 * The record (convictions, complaints, fines) is the player's for good, and weighs on its standing, its jobs and its
 * credit ({@link #recordScore}). Kept with the world.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Justice extends SavedData {

    private static final String ID = "mc_assistant_justice";
    /** Case numbers start here, clear of the folk's petty cases (Crime) the jury shares its seats with. */
    static final int FIRST_ID = 500000;
    /** The mark on stolen goods: the case they were taken in. */
    static final String STOLEN = "mca_jstolen";

    // ------------------------------------------------------------------ what can be done

    public enum Charge {
        THEFT("theft", 1, true),
        BURGLARY("burglary", 2, false),
        VANDALISM("vandalism", 1, true),
        FRAUD("fraud", 2, true),
        ASSAULT("assault", 3, false),
        MANSLAUGHTER("manslaughter", 5, false),
        MURDER("murder", 8, false),
        RESISTING("resisting arrest", 1, false),
        JAILBREAK("jail-break", 2, false),
        UNPAID_FINE("failing to pay a fine", 1, true);

        public final String word;
        /** How grave, 1 to 8. */
        public final int weight;
        /** A summons with a date rather than an arrest (a first offence). */
        public final boolean summons;

        Charge(String word, int weight, boolean summons) {
            this.word = word;
            this.weight = weight;
            this.summons = summons;
        }

        public boolean killing() { return this == MANSLAUGHTER || this == MURDER; }

        @Nullable
        static Charge named(String s) {
            try { return valueOf(s); } catch (IllegalArgumentException e) { return null; }
        }
    }

    public enum Stage {
        SEEN("not yet reported"), REPORTED("the watch is asking questions"), SUMMONED("summoned to court"),
        WARRANT("a warrant is out"), ARREST("under arrest"), REMAND("held for trial"), TRIAL("before the court"),
        CONVICTED("convicted"), ACQUITTED("acquitted"), UNSOLVED("unsolved"), EXCUSED("forgiven");

        public final String words;

        Stage(String words) { this.words = words; }

        public boolean open() { return ordinal() <= TRIAL.ordinal(); }

        /** The watch knows a name and is acting on it. */
        public boolean named() { return ordinal() >= SUMMONED.ordinal() && open(); }
    }

    /** A witness: who, its own eyes or another's word, when it will go to the watch, and whom it has told. */
    public static final class Wit {
        public final UUID id;
        public final String name;
        /** Saw it itself (else it heard it from {@link #from}). */
        public final boolean own;
        public final String from;
        public final boolean victim;
        long reportAt, tellAt;
        public boolean reported, lost;
        final List<UUID> told = new ArrayList<>();

        Wit(UUID id, String name, boolean own, String from, boolean victim, long reportAt, long tellAt) {
            this.id = id;
            this.name = name;
            this.own = own;
            this.from = from;
            this.victim = victim;
            this.reportAt = reportAt;
            this.tellAt = tellAt;
        }

        public double weight() { return own ? 3.0 : 1.5; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putString("n", name);
            t.putBoolean("own", own);
            t.putString("from", from);
            t.putBoolean("victim", victim);
            t.putLong("rep", reportAt);
            t.putLong("tell", tellAt);
            t.putBoolean("done", reported);
            t.putBoolean("lost", lost);
            ListTag l = new ListTag();
            for (UUID u : told) l.add(StringTag.valueOf(u.toString()));
            t.put("told", l);
            return t;
        }

        static Wit load(CompoundTag t) {
            Wit w = new Wit(t.getUUID("id"), t.getString("n"), t.getBoolean("own"), t.getString("from"), t.getBoolean("victim"),
                t.getLong("rep"), t.getLong("tell"));
            w.reported = t.getBoolean("done");
            w.lost = t.getBoolean("lost");
            for (Tag x : t.getList("told", Tag.TAG_STRING)) w.told.add(UUID.fromString(x.getAsString()));
            return w;
        }
    }

    /** What the watch found: what kind, in words, how much it weighs, and whether it has been found yet. */
    public static final class Ev {
        public final String kind, text;
        public final double weight;
        public boolean found;

        Ev(String kind, String text, double weight, boolean found) {
            this.kind = kind;
            this.text = text;
            this.weight = weight;
            this.found = found;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("k", kind);
            t.putString("t", text);
            t.putDouble("w", weight);
            t.putBoolean("f", found);
            return t;
        }

        static Ev load(CompoundTag t) {
            return new Ev(t.getString("k"), t.getString("t"), t.getDouble("w"), t.getBoolean("f"));
        }
    }

    /** A case: the deed, the witnesses, the evidence, where it has got to, and how it ended. */
    public static final class JCase {
        public final int id;
        public final UUID village;
        public Charge charge;
        public final UUID accused;
        public String accusedName;
        public final boolean player;
        @Nullable public final UUID victim;
        public final String victimName;
        public final boolean victimPlayer;
        public final String place;
        public final BlockPos where;
        public final long day, at;
        public Stage stage = Stage.SEEN;
        public final List<Wit> witnesses = new ArrayList<>();
        public final List<Ev> evidence = new ArrayList<>();
        /** Charges added on (resisting, a jail-break), and other cases tried with this one. */
        public final List<Charge> extra = new ArrayList<>();
        public final List<Integer> joined = new ArrayList<>();
        public int joinedTo;
        /** Marks of intent (blows before, a motive, a weapon drawn): murder rather than manslaughter. */
        public int intent;
        public int worth;
        public boolean minor;
        public String goods = "";
        public final List<String> notes = new ArrayList<>();
        public final List<String> transcript = new ArrayList<>();
        long noticeAt, inquiryUntil;
        public long courtAt;
        @Nullable public UUID officer;
        public String officerName = "";
        public String plea = "", defence = "", account = "", lawyerName = "";
        public String verdict = "", finding = "", sentence = "";
        public int fine, comp, jailDays, sweeps, stocksMinutes, banishDays;
        public long fineDue;
        public boolean finePaid;

        JCase(int id, UUID village, Charge charge, UUID accused, String accusedName, boolean player, @Nullable UUID victim,
              String victimName, boolean victimPlayer, String place, BlockPos where, long day, long at) {
            this.id = id;
            this.village = village;
            this.charge = charge;
            this.accused = accused;
            this.accusedName = accusedName;
            this.player = player;
            this.victim = victim;
            this.victimName = victimName;
            this.victimPlayer = victimPlayer;
            this.place = place;
            this.where = where.immutable();
            this.day = day;
            this.at = at;
        }

        void linkedNote(JCase o) {
            notes.add("linked " + o.id);
        }

        /** "theft at Hartwell Farm". */
        public String title() {
            return charge.word + " at " + place;
        }

        /** Every charge, the case's and those added. */
        public List<Charge> charges() {
            List<Charge> out = new ArrayList<>();
            out.add(charge);
            for (Charge c : extra) if (!out.contains(c)) out.add(c);
            return out;
        }

        public String chargeWords() {
            List<String> w = new ArrayList<>();
            for (Charge c : charges()) w.add(c.word);
            return String.join(" and ", w);
        }

        /** The weight of what the watch has on file: witnesses who told, hearsay, the evidence found. */
        public double score() {
            double s = 0;
            for (Wit w : witnesses) if (w.reported && !w.lost) s += w.weight();
            for (Ev e : evidence) if (e.found) s += e.weight;
            return s;
        }

        /** The first witness on file, for the notice ("witness: Bramble"), or "". */
        public String firstWitness() {
            for (Wit w : witnesses) if (w.reported && !w.lost) return w.name + (w.own ? "" : " (on " + w.from + "'s word)");
            for (Ev e : evidence) if (e.found) return e.kind.equals("GOODS") ? "stolen goods found on you" : e.kind.toLowerCase(Locale.ROOT).replace('_', ' ');
            return "";
        }

        /** Witnesses alive who have not yet told the watch. */
        boolean pending() {
            for (Wit w : witnesses) if (!w.reported && !w.lost) return true;
            return false;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("id", id);
            t.putUUID("v", village);
            t.putString("c", charge.name());
            t.putUUID("a", accused);
            t.putString("an", accusedName);
            t.putBoolean("p", player);
            if (victim != null) t.putUUID("vic", victim);
            t.putString("vn", victimName);
            t.putBoolean("vp", victimPlayer);
            t.putString("pl", place);
            t.putLong("w", where.asLong());
            t.putLong("d", day);
            t.putLong("at", at);
            t.putString("st", stage.name());
            ListTag ws = new ListTag();
            for (Wit w : witnesses) ws.add(w.save());
            t.put("wit", ws);
            ListTag es = new ListTag();
            for (Ev e : evidence) es.add(e.save());
            t.put("ev", es);
            ListTag xs = new ListTag();
            for (Charge c : extra) xs.add(StringTag.valueOf(c.name()));
            t.put("x", xs);
            t.putIntArray("j", joined.stream().mapToInt(Integer::intValue).toArray());
            t.putInt("jt", joinedTo);
            t.putInt("in", intent);
            t.putInt("wo", worth);
            t.putBoolean("mi", minor);
            t.putString("go", goods);
            ListTag ns = new ListTag();
            for (String n : notes) ns.add(StringTag.valueOf(n));
            t.put("n", ns);
            ListTag tr = new ListTag();
            for (String n : transcript) tr.add(StringTag.valueOf(n));
            t.put("tr", tr);
            t.putLong("na", noticeAt);
            t.putLong("iu", inquiryUntil);
            t.putLong("ca", courtAt);
            if (officer != null) t.putUUID("o", officer);
            t.putString("on", officerName);
            t.putString("plea", plea);
            t.putString("def", defence);
            t.putString("acc", account);
            t.putString("law", lawyerName);
            t.putString("ver", verdict);
            t.putString("fin", finding);
            t.putString("sen", sentence);
            t.putIntArray("nums", new int[]{ fine, comp, jailDays, sweeps, stocksMinutes, banishDays });
            t.putLong("due", fineDue);
            t.putBoolean("paid", finePaid);
            return t;
        }

        static JCase load(CompoundTag t) {
            Charge ch = Charge.named(t.getString("c"));
            JCase c = new JCase(t.getInt("id"), t.getUUID("v"), ch == null ? Charge.THEFT : ch, t.getUUID("a"), t.getString("an"), t.getBoolean("p"),
                t.hasUUID("vic") ? t.getUUID("vic") : null, t.getString("vn"), t.getBoolean("vp"), t.getString("pl"), BlockPos.of(t.getLong("w")),
                t.getLong("d"), t.getLong("at"));
            try { c.stage = Stage.valueOf(t.getString("st")); } catch (IllegalArgumentException e) { c.stage = Stage.UNSOLVED; }
            for (Tag x : t.getList("wit", Tag.TAG_COMPOUND)) c.witnesses.add(Wit.load((CompoundTag) x));
            for (Tag x : t.getList("ev", Tag.TAG_COMPOUND)) c.evidence.add(Ev.load((CompoundTag) x));
            for (Tag x : t.getList("x", Tag.TAG_STRING)) { Charge e = Charge.named(x.getAsString()); if (e != null) c.extra.add(e); }
            for (int j : t.getIntArray("j")) c.joined.add(j);
            c.joinedTo = t.getInt("jt");
            c.intent = t.getInt("in");
            c.worth = t.getInt("wo");
            c.minor = t.getBoolean("mi");
            c.goods = t.getString("go");
            for (Tag x : t.getList("n", Tag.TAG_STRING)) c.notes.add(x.getAsString());
            for (Tag x : t.getList("tr", Tag.TAG_STRING)) c.transcript.add(x.getAsString());
            c.noticeAt = t.getLong("na");
            c.inquiryUntil = t.getLong("iu");
            c.courtAt = t.getLong("ca");
            if (t.hasUUID("o")) c.officer = t.getUUID("o");
            c.officerName = t.getString("on");
            c.plea = t.getString("plea");
            c.defence = t.getString("def");
            c.account = t.getString("acc");
            c.lawyerName = t.getString("law");
            c.verdict = t.getString("ver");
            c.finding = t.getString("fin");
            c.sentence = t.getString("sen");
            int[] n = t.getIntArray("nums");
            if (n.length >= 6) { c.fine = n[0]; c.comp = n[1]; c.jailDays = n[2]; c.sweeps = n[3]; c.stocksMinutes = n[4]; c.banishDays = n[5]; }
            c.fineDue = t.getLong("due");
            c.finePaid = t.getBoolean("paid");
            return c;
        }
    }

    // ------------------------------------------------------------------ the books

    private final Map<Integer, JCase> cases = new LinkedHashMap<>();
    /** Each player's (or folk's) record, by id: convictions, complaints, forgiven hurts, warnings. */
    private CompoundTag records = new CompoundTag();
    /** Each player's custody (Jail), by id. */
    CompoundTag custody = new CompoundTag();
    /** Small claims (Court.smallClaim), by id. */
    CompoundTag claims = new CompoundTag();
    private int next = FIRST_ID;
    @Nullable private static Justice loose;

    public Justice() {}

    static Justice of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Justice();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Justice::new, Justice::load, null), ID);
    }

    public static Justice load(CompoundTag tag, HolderLookup.Provider registries) {
        Justice j = new Justice();
        for (Tag x : tag.getList("cases", Tag.TAG_COMPOUND)) {
            try {
                JCase c = JCase.load((CompoundTag) x);
                j.cases.put(c.id, c);
            } catch (RuntimeException e) {
                com.mojang.logging.LogUtils.getLogger().warn("[wpe] a case could not be read", e);
            }
        }
        j.records = tag.getCompound("records");
        j.custody = tag.getCompound("custody");
        j.claims = tag.getCompound("claims");
        j.next = Math.max(FIRST_ID, tag.getInt("next"));
        return j;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag l = new ListTag();
        for (JCase c : cases.values()) l.add(c.save());
        tag.put("cases", l);
        tag.put("records", records.copy());
        tag.put("custody", custody.copy());
        tag.put("claims", claims.copy());
        tag.putInt("next", next);
        return tag;
    }

    static void changed() {
        of().setDirty();
    }

    static int nextId() {
        Justice j = of();
        j.next++;
        j.setDirty();
        return j.next;
    }

    @Nullable
    public static JCase get(int id) {
        return of().cases.get(id);
    }

    /** A town's cases, the latest first. */
    public static List<JCase> cases(UUID village) {
        List<JCase> out = new ArrayList<>();
        for (JCase c : of().cases.values()) if (c.village.equals(village)) out.add(0, c);
        return out;
    }

    /** Every case against this one (player or folk), the latest first. */
    public static List<JCase> against(UUID who) {
        List<JCase> out = new ArrayList<>();
        for (JCase c : of().cases.values()) if (c.accused.equals(who)) out.add(0, c);
        return out;
    }

    /** The open cases with this one named (summoned, wanted, held, on trial), the oldest first. */
    public static List<JCase> namedAgainst(UUID who) {
        List<JCase> out = new ArrayList<>();
        for (JCase c : of().cases.values()) if (c.accused.equals(who) && c.stage.named()) out.add(c);
        return out;
    }

    /** A player's (or folk's) record. */
    public static CompoundTag record(UUID who) {
        Justice j = of();
        String k = who.toString();
        if (!j.records.contains(k)) j.records.put(k, new CompoundTag());
        return j.records.getCompound(k);
    }

    /** The convictions on a record, the latest last: "day|charge|sentence". */
    public static List<String> convictions(UUID who) {
        List<String> out = new ArrayList<>();
        for (Tag t : record(who).getList("conv", Tag.TAG_STRING)) out.add(t.getAsString());
        return out;
    }

    /**
     * How heavily a record weighs (0 clean): each conviction by its charge's gravity, each complaint a little. For the
     * employers who will not take on a convict (jobs) and the lenders who think twice (credit): above 6, a serious record.
     */
    public static int recordScore(UUID who) {
        CompoundTag r = record(who);
        int s = r.getInt("complaints");
        for (Tag t : r.getList("conv", Tag.TAG_STRING)) {
            String[] p = t.getAsString().split("\\|");
            Charge c = p.length > 1 ? Charge.named(p[1]) : null;
            s += c == null ? 2 : 1 + c.weight;
        }
        return s;
    }

    /** Is the record serious enough that an employer of good standing would turn the player away? */
    public static boolean seriousRecord(UUID who) {
        return recordScore(who) > 6;
    }

    static void convicted(JCase c, String words) {
        CompoundTag r = record(c.accused);
        ListTag l = r.getList("conv", Tag.TAG_STRING);
        for (Charge ch : c.charges()) l.add(StringTag.valueOf(c.day + "|" + ch.name() + "|" + words + "|" + Villages.name(c.village)));
        r.put("conv", l);
        changed();
    }

    // ------------------------------------------------------------------ pace

    /** Tests: a test's stand-in player (always "creative") under the law all the same. */
    public static volatile boolean creativeUnderLawForTests;

    /** Tests: the witnesses, the watch and the court without the long waits. */
    static volatile boolean hurry;

    public static void hurryForTests(boolean on) {
        hurry = on;
    }

    static long pause(long ticks) {
        return hurry ? Math.max(10, ticks / 20) : ticks;
    }

    // ------------------------------------------------------------------ the deed done: a case

    static JCase open(ServerLevel level, UUID village, Charge charge, UUID accused, String accusedName, boolean player,
                      @Nullable UUID victim, String victimName, boolean victimPlayer, String place, BlockPos where) {
        long now = level.getGameTime();
        JCase c = new JCase(nextId(), village, charge, accused, accusedName, player, victim, victimName, victimPlayer, place, where,
            level.getDayTime() / 24000L, now);
        c.noticeAt = now + pause(2400);
        of().cases.put(c.id, c);
        changed();
        return c;
    }

    /** How soon this folk goes to the watch with what it saw (ticks), by its nature; and when it tells a neighbour. */
    static long[] delays(VillageFolkEntity f, boolean victim, RandomSource r) {
        int honesty = Mischief.honesty(f);
        Social.Life life = f.life();
        long rep;
        if (victim) rep = 200 + r.nextInt(200);
        else if (honesty >= 60 || life.has(Social.Trait.SOCIABLE)) rep = 400 + r.nextInt(600);
        else if (honesty < 35 || life.has(Social.Trait.SHY)) rep = 2400 + r.nextInt(2400);
        else rep = 1000 + r.nextInt(1400);
        long tell = life.has(Social.Trait.SOCIABLE) || life.has(Social.Trait.CURIOUS) ? rep / 3 : rep * 2 / 3;
        return new long[]{ pause(rep), pause(tell) };
    }

    /** The folk that saw this: each a witness, its report on its way. */
    static void witnesses(ServerLevel level, JCase c, Player p, @Nullable VillageFolkEntity victim, @Nullable VillageFolkEntity also) {
        long now = level.getGameTime();
        List<VillageFolkEntity> seen = new ArrayList<>();
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, p.getBoundingBox().inflate(Witness.SIGHT),
                f -> f != victim && !f.isBaby() && !f.isShowcase() && Witness.sees(f, p))) seen.add(f);
        if (also != null && also != victim && !seen.contains(also) && also.isAlive()) seen.add(also);
        for (VillageFolkEntity f : seen) addWitness(c, f, false, now, level.getRandom());
        if (victim != null && victim.isAlive()) addWitness(c, victim, true, now, level.getRandom());
    }

    static void addWitness(JCase c, VillageFolkEntity f, boolean victim, long now, RandomSource r) {
        for (Wit w : c.witnesses) if (w.id.equals(f.getUUID())) return;
        long[] d = delays(f, victim, r);
        c.witnesses.add(new Wit(f.getUUID(), f.displayNameCap(), true, "", victim, now + d[0], now + d[1]));
        f.persona().remember(c.day, "I saw " + c.accusedName + " - " + c.charge.word, 6);
        changed();
    }

    // ------------------------------------------------------------------ accidental harm

    /** A hurt just done, waiting on an apology: to whom, how badly, when, where. */
    static final class Hurt {
        final UUID folk, village;
        final String name;
        final float amount;
        final long at;
        boolean offered;

        Hurt(UUID folk, UUID village, String name, float amount, long at) {
            this.folk = folk;
            this.village = village;
            this.name = name;
            this.amount = amount;
            this.at = at;
        }
    }

    private static final Map<UUID, Hurt> HURTS = new ConcurrentHashMap<>();
    /** By player: its blows on folk lately (folk, game time), for a pattern of intent. */
    private static final Map<UUID, List<long[]>> BLOWS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_BLOW = new ConcurrentHashMap<>();

    static boolean enabled() {
        return JusticeConfig.enabled();
    }

    /**
     * A player hurt a folk (PlayerLaw.struck). Returns true if justice has it: an accident waits on an apology; a pattern
     * of blows, or a heavy one, is an assault.
     */
    static boolean harm(ServerLevel level, Villages.Village v, ServerPlayer p, VillageFolkEntity f, float amount) {
        if (!enabled()) return false;
        long now = level.getGameTime();
        Long last = LAST_BLOW.get(p.getUUID());
        if (last != null && now - last < 10 && now >= last) return true;            // one swing, one blow
        LAST_BLOW.put(p.getUUID(), now);
        List<long[]> blows = BLOWS.computeIfAbsent(p.getUUID(), k -> new ArrayList<>());
        long fid = f.getUUID().getLeastSignificantBits();
        blows.removeIf(b -> now - b[1] > 12000 || now < b[1]);
        int before = 0;
        for (long[] b : blows) if (b[0] == fid) before++;
        blows.add(new long[]{ fid, now });
        Hurt pending = HURTS.get(p.getUUID());
        boolean again = pending != null && pending.folk.equals(f.getUUID());
        boolean heavy = amount >= 6.0F || f.getHealth() - amount <= 0;
        if (before == 0 && !heavy && !again) {
            HURTS.put(p.getUUID(), new Hurt(f.getUUID(), v.id(), f.displayNameCap(), amount, now));
            String n = Belonging.called(p);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Ow! Watch where you swing that.", "Hey! That hurt, " + n + ".",
                "Careful! You nearly had my eye out.", "Ow. Was that meant?", "Mind yourself! That stung.", "What was that for?"));
            FolkAction.mark(f, FolkAction.Mark.EXCLAIM);
            p.sendSystemMessage(Component.literal("You hurt " + f.displayNameCap() + ". If it was an accident, say sorry: talk to them and choose "
                + "Apologise within " + JusticeConfig.apologySeconds() + " seconds. Left alone, it becomes a complaint.").withStyle(ChatFormatting.YELLOW));
            Witness.dealt(f, p, Witness.Kind.HIT, "");
            return true;
        }
        // Again, or hard: an assault, the victim and whoever saw it its witnesses.
        if (pending != null && pending.folk.equals(f.getUUID())) HURTS.remove(p.getUUID());
        if (!f.isAlive() || f.getHealth() - amount <= 0) return true;               // a killing: the death has it (onDeath)
        for (JCase c : namedAgainst(p.getUUID())) {
            if (c.charge == Charge.ASSAULT && f.getUUID().equals(c.victim) && now - c.at < 2400) return true;   // the same fight
        }
        JCase c = open(level, v.id(), Charge.ASSAULT, p.getUUID(), Belonging.called(p), true, f.getUUID(), f.displayNameCap(), false,
            placeOf(level, v, p.blockPosition()), p.blockPosition());
        c.intent = before + (heavy ? 1 : 0);
        witnesses(level, c, p, f, null);
        c.notes.add(f.displayNameCap() + " was struck " + (before + 1) + " times" + (heavy ? ", hard" : ""));
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "That's it. I'm going to the watch.", "You'll answer for that.", "Help! Watch!",
            "Get away from me!", "I'll have the law on you for that.", "Somebody fetch the watch!"));
        Witness.dealt(f, p, Witness.Kind.HIT, "");
        Standing.stir(v.id(), p.getUUID());
        return true;
    }

    /** The hurt this player owes an apology for to this folk, or null. */
    @Nullable
    static Hurt hurtOwed(UUID player, UUID folk) {
        Hurt h = HURTS.get(player);
        return h != null && h.folk.equals(folk) ? h : null;
    }

    /** What healing a hurt costs: two coins a heart of it, at least two. */
    static int healingCost(float amount) {
        return Math.max(2, (int) Math.ceil(amount));
    }

    /** "I'm sorry" (or paying the healer, {@code pay}): forgiven or not, by its nature and the hurt. Returns its answer. */
    static String apologise(VillageFolkEntity f, ServerPlayer p, boolean pay) {
        Hurt h = hurtOwed(p.getUUID(), f.getUUID());
        if (h == null) return null;
        ServerLevel level = (ServerLevel) f.level();
        int cost = healingCost(h.amount);
        if (pay) {
            if (Market.coinsHeld(p) < cost) return "That's kind, but you've not " + cost + " coins on you.";
            Market.payOut(p, cost);
            f.earn(cost);
            f.heal(h.amount);
            h.offered = true;
        }
        HURTS.remove(p.getUUID());
        int chance = 65;
        Social.Life life = f.life();
        if (life.has(Social.Trait.CHEERFUL)) chance += 12;
        if (life.has(Social.Trait.GENEROUS)) chance += 12;
        if (life.has(Social.Trait.EASYGOING)) chance += 10;
        if (life.has(Social.Trait.GRUMPY)) chance -= 25;
        if (life.has(Social.Trait.SHY)) chance -= 5;
        chance -= (int) (h.amount / Math.max(1.0F, f.getMaxHealth()) * 60.0F);
        chance += Math.max(-20, Math.min(20, f.persona().affinity(p.getUUID()) / 3));
        chance -= 15 * record(p.getUUID()).getInt("complaints");
        if (h.offered) chance += 40;
        boolean forgiven = hurry ? chance >= 40 : level.getRandom().nextInt(100) < chance;
        CompoundTag r = record(p.getUUID());
        String n = Belonging.first(p);
        if (forgiven) {
            r.putInt("forgiven", r.getInt("forgiven") + 1);
            f.persona().feelFor(p.getUUID(), p.getName().getString(), h.offered ? 2 : 0);
            changed();
            return h.offered ? FolkTalk.pick(f.getRandom(), "There. The healer's paid and no harm done.", "That's decent of you. We'll say no more.",
                "Paid for, and forgiven. Mind your swing.", "Very well. I've had worse from a goat.", "Thank you. Accidents happen.", "Fair. Forgotten.")
                : FolkTalk.pick(f.getRandom(), "Accidents happen, " + n + ". Forget it.", "All right. Just be more careful.", "Apology accepted.",
                "I'll live. Watch where you swing next time.", "No harm meant, no harm kept.", "Fine. We'll say no more about it.");
        }
        complaint(level, f, p, "would not accept " + Belonging.called(p) + "'s apology");
        return FolkTalk.pick(f.getRandom(), "Sorry doesn't mend a bruise. I'm telling the watch.", "No. You meant that.",
            "Save it. The watch will hear of this.", "I don't believe a word of it.", "An apology? After that?", "Keep your sorry.");
    }

    /** A complaint against a player on the books; two and it is a charge of assault. */
    static void complaint(ServerLevel level, VillageFolkEntity f, Player p, String why) {
        CompoundTag r = record(p.getUUID());
        int n = r.getInt("complaints") + 1;
        r.putInt("complaints", n);
        ListTag l = r.getList("cmpl", Tag.TAG_STRING);
        l.add(StringTag.valueOf((level.getDayTime() / 24000L) + "|" + f.displayNameCap() + "|" + why));
        r.put("cmpl", l);
        changed();
        f.persona().feelFor(p.getUUID(), p.getName().getString(), -6);
        UUID village = f.ownerId();
        if (village != null) Standing.stir(village, p.getUUID());
        if (p instanceof ServerPlayer sp) {
            notice(sp, "A complaint against you", f.displayNameCap() + " has complained to the watch about you: " + why + ".",
                n >= 2 ? "That is your second complaint: the watch is treating it as an assault." : "One more complaint and it becomes an assault charge.");
        }
        if (n >= 2 && village != null) {
            Villages.Village v = Villages.get(village);
            if (v == null) return;
            JCase c = open(level, village, Charge.ASSAULT, p.getUUID(), Belonging.called(p), true, f.getUUID(), f.displayNameCap(), false,
                placeOf(level, v, f.blockPosition()), f.blockPosition());
            c.minor = true;
            c.notes.add("complaints: " + n);
            addWitness(c, f, true, level.getGameTime(), level.getRandom());
            r.putInt("complaints", 0);
        }
    }

    // ------------------------------------------------------------------ a killing

    /** Where a player was last seen with a folk, and by whom: folk to player to {time, the onlooker}. */
    private static final Map<UUID, Map<UUID, Object[]>> SEEN_WITH = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity().level() instanceof ServerLevel level)) return;
        LivingEntity dead = e.getEntity();
        if (!(dead instanceof VillageFolkEntity) && !(dead instanceof ServerPlayer)) return;
        com.jrpetty.mcassistant.Guard.run("a death and the law", () -> died(level, dead, e.getSource().getEntity(), e.getSource().getDirectEntity()));
    }

    static void died(ServerLevel level, LivingEntity dead, @Nullable Entity killer, @Nullable Entity direct) {
        if (!enabled()) return;
        // What it knew dies with it, save what it already told.
        if (dead instanceof VillageFolkEntity f) silenced(level, f);
        if (!(killer instanceof ServerPlayer p) || Laws.exempt(p) || p == dead) return;
        if (dead instanceof ServerPlayer && !JusticeConfig.playerKillsCount()) return;
        Villages.Village v = Villages.nearest(level, dead.blockPosition(), Villages.VILLAGE_RANGE);
        if (v == null) return;
        if (dead instanceof VillageFolkEntity f && (f.isShowcase() || f.isHired() || !v.id().equals(f.ownerId()))) return;
        int reach = Villages.townReach(v.id());
        if (dead instanceof ServerPlayer && Math.max(Math.abs(dead.getX() - v.centre().getX()), Math.abs(dead.getZ() - v.centre().getZ())) > reach + 16) return;
        HURTS.remove(p.getUUID());
        String victimName = dead instanceof ServerPlayer vp ? Belonging.called(vp) : ((VillageFolkEntity) dead).displayNameCap();
        long now = level.getGameTime();
        // Intent: blows before the last, a motive, a heavy weapon.
        int intent = 0;
        List<long[]> blows = BLOWS.get(p.getUUID());
        long fid = dead.getUUID().getLeastSignificantBits();
        if (blows != null) for (long[] b : blows) if (b[0] == fid && now - b[1] < 2400 && now - b[1] > 5) intent++;
        List<JCase> motive = new ArrayList<>();
        for (JCase o : of().cases.values()) {
            if (!o.accused.equals(p.getUUID()) || o.stage.ordinal() > Stage.TRIAL.ordinal() && o.stage != Stage.UNSOLVED) continue;
            for (Wit w : o.witnesses) if (w.id.equals(dead.getUUID())) { motive.add(o); break; }
        }
        boolean projectile = direct instanceof Projectile;
        ItemStack held = p.getMainHandItem();
        if (!projectile && held.getItem() instanceof SwordItem) intent++;
        Charge charge = intent >= 2 ? Charge.MURDER : Charge.MANSLAUGHTER;
        JCase c = open(level, v.id(), charge, p.getUUID(), Belonging.called(p), true, dead.getUUID(), victimName, dead instanceof ServerPlayer,
            placeOf(level, v, dead.blockPosition()), dead.blockPosition());
        c.intent = intent;
        c.notes.add(victimName + " was found dead" + (projectile ? ", an arrow in them" : ""));
        if (dead instanceof VillageFolkEntity vf && vf.life().partner() != null) c.notes.add("family:" + vf.life().partner() + ":" + vf.life().partnerName());
        witnesses(level, c, p, null, null);
        // The evidence, there to be found when the watch looks.
        Map<UUID, Object[]> with = SEEN_WITH.get(dead.getUUID());
        Object[] last = with == null ? null : with.get(p.getUUID());
        if (last != null && now - (long) last[0] < 6000) {
            c.evidence.add(new Ev("LAST_SEEN", last[2] + " saw " + c.accusedName + " with " + victimName + " not long before", 1.5, false));
            c.notes.add("last seen with " + c.accusedName + " (by " + last[2] + ")");
        }
        BlockState under = level.getBlockState(p.blockPosition().below());
        boolean soft = under.is(BlockTags.DIRT) || under.is(BlockTags.SAND) || under.is(net.minecraft.world.level.block.Blocks.MUD)
            || under.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK) || under.is(net.minecraft.world.level.block.Blocks.FARMLAND)
            || level.getBlockState(p.blockPosition()).is(net.minecraft.world.level.block.Blocks.SNOW);
        if (soft && p.distanceTo(dead) <= 4.5) c.evidence.add(new Ev("FOOTPRINTS", "boot prints in the soft ground by the body, a big man's boots", 1.0, false));
        if (projectile && carries(p, s -> s.getItem() instanceof BowItem || s.getItem() instanceof CrossbowItem)) c.evidence.add(new Ev("WEAPON", "an arrow in the body", 0.5, false));
        else if (!projectile && held.getItem() instanceof SwordItem) c.evidence.add(new Ev("WEAPON", "a blade wound", 0.5, false));
        for (JCase o : motive) {
            c.evidence.add(new Ev("MOTIVE:" + o.id + ":" + dead.getUUID(), victimName + " had seen " + c.accusedName + "'s " + o.charge.word + " at " + o.place, 1.0, false));
            o.notes.add("its witness " + victimName + " was killed");
            c.linkedNote(o);
        }
        if (dead instanceof VillageFolkEntity f) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity o && o != f) o.persona().feelFor(p.getUUID(), p.getName().getString(), c.witnesses.isEmpty() ? 0 : -15);
            }
        }
        changed();
    }

    static boolean carries(Player p, java.util.function.Predicate<ItemStack> what) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (what.test(p.getInventory().getItem(i))) return true;
        return false;
    }

    /**
     * A folk died: what it saw and had not told dies with it. What it told a neighbour lives on as that neighbour's word,
     * weaker; what is already on the watch's books stands.
     */
    static void silenced(ServerLevel level, VillageFolkEntity f) {
        long now = level.getGameTime();
        for (JCase c : of().cases.values()) {
            if (!c.stage.open() && c.stage != Stage.UNSOLVED) continue;
            List<Wit> heirs = new ArrayList<>();
            for (Wit w : c.witnesses) {
                if (!w.id.equals(f.getUUID()) || w.reported || w.lost) continue;
                w.lost = true;
                c.notes.add(w.name + " died before telling the watch what they saw");
                for (UUID u : w.told) {
                    VillageFolkEntity h = Civics.find(level, u);
                    if (h == null || !h.isAlive()) continue;
                    boolean has = false;
                    for (Wit o : c.witnesses) if (o.id.equals(u)) has = true;
                    if (!has) heirs.add(new Wit(u, h.displayNameCap(), false, w.name, false, now + pause(400), now + pause(4000)));
                }
            }
            if (!heirs.isEmpty()) {
                c.witnesses.addAll(heirs);
                c.notes.add("what was seen lives on as hearsay");
            }
            changed();
        }
    }

    // ------------------------------------------------------------------ theft, burglary, vandalism

    /**
     * A player seen breaking the law (Laws.offence, through PlayerLaw): justice takes the graver part of it (a burglary of a
     * house, a theft from a shop or a stall, a house broken). True if it has it; the stores and the town's buildings stay
     * with the watch's ladder.
     */
    static boolean offence(ServerLevel level, Villages.Village v, Player p, String what, int fine, @Nullable java.util.Map<net.minecraft.world.item.Item, Integer> took) {
        if (!enabled() || !(p instanceof ServerPlayer sp)) return false;
        Charge charge = null;
        String place;
        if (what.startsWith("taking") && what.contains(" from a house")) { charge = Charge.BURGLARY; place = "a house"; }
        else if (what.startsWith("taking") && !what.contains("the stores") && what.contains(" from ")
                && !what.contains("field") && !what.contains("crop")) {                 // the fields' crops: the watch's ladder (wp-b)
            charge = Charge.THEFT;
            place = what.substring(what.lastIndexOf(" from ") + 6);
        } else if (what.startsWith("damaging") && (what.contains("house") || what.contains("home"))) { charge = Charge.VANDALISM; place = what.substring(9); }
        else return false;
        String at = placeOf(level, v, p.blockPosition());
        JCase c = open(level, v.id(), charge, p.getUUID(), Belonging.called(p), true, null, place, false, at, p.blockPosition());
        c.worth = Math.max(1, fine / 2);
        c.goods = what.replaceFirst("^taking ", "").replaceFirst(" from .*$", "");
        c.notes.add(Belonging.called(p) + " was " + what);
        witnesses(level, c, sp, null, null);
        if (took != null && !took.isEmpty()) markStolen(sp, took, c.id);
        if (c.witnesses.isEmpty()) c.notes.add("nobody saw it");
        changed();
        return true;
    }

    /** What it took marked in its pack as this case's (stolen goods, should the watch ever search it). */
    static void markStolen(Player p, java.util.Map<net.minecraft.world.item.Item, Integer> took, int caseId) {
        for (var e : took.entrySet()) {
            int left = e.getValue();
            for (int i = 0; i < p.getInventory().getContainerSize() && left > 0; i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (!s.is(e.getKey()) || stolenCase(s) != 0) continue;
                ItemStack mark = s.getCount() > left ? s.split(left) : s;
                CustomData.update(DataComponents.CUSTOM_DATA, mark, t -> t.putInt(STOLEN, caseId));
                if (mark != s && !p.getInventory().add(mark)) p.drop(mark, false);
                left -= mark.getCount();
            }
        }
        p.getInventory().setChanged();
    }

    public static int stolenCase(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? 0 : d.copyTag().getInt(STOLEN);
    }

    // ------------------------------------------------------------------ a special constable's badge shown to a wanted player

    @SubscribeEvent
    public static void onBadge(PlayerInteractEvent.EntityInteract e) {
        if (!(e.getTarget() instanceof ServerPlayer target) || !(e.getEntity() instanceof ServerPlayer p) || !enabled()) return;
        if (!e.getItemStack().is(com.jrpetty.mcassistant.item.PoliceItems.CONSTABLE_BADGE.get())) return;
        e.setCanceled(true);
        e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        com.jrpetty.mcassistant.Guard.run("a constable's arrest of a player", () -> {
            String said = Arrests.constableArrest((ServerLevel) p.level(), p, target);
            if (said != null) p.displayClientMessage(Component.literal(said), false);
        });
    }

    // ------------------------------------------------------------------ fraud: a forged coin passed

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (!(e.getTarget() instanceof VillageFolkEntity f) || !(e.getEntity() instanceof ServerPlayer p) || Laws.exempt(p)) return;
        ItemStack s = e.getItemStack();
        if (!s.is(McAssistantMod.FORGED_COIN.get()) || !enabled() || f.isBaby() || f.ownerId() == null) return;
        e.setCanceled(true);
        e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        com.jrpetty.mcassistant.Guard.run("a forged coin passed", () -> fraud((ServerLevel) p.level(), f, p, s));
    }

    /** A forged coin passed to a folk as good: it gives change; it finds it out soon (or there and then, if sharp). */
    static void fraud(ServerLevel level, VillageFolkEntity f, ServerPlayer p, ItemStack s) {
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return;
        int n = Math.min(s.getCount(), 5);
        s.shrink(n);
        PlayerLaw.giveCoins(p, n);
        f.earn(0);
        boolean sharp = f.life().has(Social.Trait.CURIOUS) || Mischief.honesty(f) >= 70;
        FolkTalk.speak(f, sharp ? FolkTalk.pick(f.getRandom(), "Hang on. This coin's wrong.", "That's not good coin.", "Funny weight to this...")
            : FolkTalk.pick(f.getRandom(), "There's your change.", "Pleasure doing business.", "Thank you kindly."));
        JCase c = open(level, v.id(), Charge.FRAUD, p.getUUID(), Belonging.called(p), true, f.getUUID(), f.displayNameCap(), false,
            placeOf(level, v, p.blockPosition()), p.blockPosition());
        c.worth = n;
        c.goods = n + " forged coin" + (n == 1 ? "" : "s");
        c.notes.add(f.displayNameCap() + " took " + c.goods + " from " + c.accusedName + " and gave good change");
        witnesses(level, c, p, f, null);
        if (!sharp) for (Wit w : c.witnesses) if (w.victim) w.reportAt = level.getGameTime() + pause(1800);
        changed();
    }

    // ------------------------------------------------------------------ the round

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        int tick = e.getServer().getTickCount();
        if (tick % 20 != 7) return;
        com.jrpetty.mcassistant.Guard.run("justice", () -> {
            for (ServerLevel level : e.getServer().getAllLevels()) {
                if (tick % 40 == 7) seenWith(level);
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    round(level, v);
                }
                apologies(level);
                Arrests.constableRound(level);
                Jail.tick(level);
                Assize.sittings(level);
            }
        });
    }

    /** Who is about with whom: a folk near a player, seen together by another (for "last seen with"). */
    static void seenWith(ServerLevel level) {
        long now = level.getGameTime();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            List<VillageFolkEntity> near = level.getEntitiesOfClass(VillageFolkEntity.class, p.getBoundingBox().inflate(Witness.SIGHT), f -> !f.isBaby());
            for (VillageFolkEntity f : near) {
                if (f.distanceTo(p) > 6.0) continue;
                for (VillageFolkEntity o : near) {
                    if (o == f || !Witness.sees(o, p) || o.distanceTo(p) < 1.0) continue;
                    SEEN_WITH.computeIfAbsent(f.getUUID(), k -> new ConcurrentHashMap<>()).put(p.getUUID(), new Object[]{ now, o.getUUID(), o.displayNameCap() });
                    break;
                }
            }
        }
    }

    /** The apologies not given in time: complaints. */
    static void apologies(ServerLevel level) {
        long now = level.getGameTime();
        long window = hurry ? 200 : JusticeConfig.apologySeconds() * 20L;
        for (Map.Entry<UUID, Hurt> e : HURTS.entrySet()) {
            Hurt h = e.getValue();
            if (now - h.at < window && now >= h.at) continue;
            HURTS.remove(e.getKey());
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
            VillageFolkEntity f = Civics.find(level, h.folk);
            if (p == null || f == null || !f.isAlive()) continue;
            complaint(level, f, p, "hurt them and never said sorry");
        }
    }

    /** A town's cases moved on: witnesses tell, the watch weighs what it has, warrants and summonses go out. */
    static void round(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        for (JCase c : new ArrayList<>(of().cases.values())) {
            if (!c.village.equals(v.id())) continue;
            if (c.stage == Stage.SEEN || c.stage == Stage.REPORTED) {
                tellings(level, v, c, now);
                weigh(level, v, c, now);
            } else if (c.stage == Stage.WARRANT) {
                Arrests.warrant(level, v, c);
            }
        }
        Arrests.fines(level, v);
        if (!TownJobs.instantNow()) {                                       // never in another feature's test
            FolkCrimes.daily(level, v);
            FolkCrimes.night(level, v);
        }
        if (level.getGameTime() % 1200 < 20) prune(v.id(), level.getDayTime() / 24000L);
    }

    /** Old closed cases let go (the record keeps the convictions): at most a hundred and fifty a town, none older than sixty days. */
    static void prune(UUID village, long day) {
        List<JCase> mine = cases(village);
        int kept = 0;
        for (JCase c : mine) {
            boolean closed = !c.stage.open() && Jail.custody(c.accused) == null;
            if (closed && (++kept > 150 || day - c.day > 60) && (c.fine <= 0 || c.finePaid)) of().cases.remove(c.id);
        }
        changed();
    }

    /** The witnesses' part: a word to a neighbour, then to the watch. */
    static void tellings(ServerLevel level, Villages.Village v, JCase c, long now) {
        for (Wit w : c.witnesses) {
            if (w.reported || w.lost) continue;
            VillageFolkEntity f = Civics.find(level, w.id);
            if (f == null) continue;
            if (!f.isAlive()) { w.lost = true; changed(); continue; }
            if (w.own && w.told.isEmpty() && now >= w.tellAt) {
                for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(12.0), o -> o != f && !o.isBaby())) {
                    if (o.getUUID().equals(c.accused)) continue;
                    w.told.add(o.getUUID());
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "You'll never guess what I just saw.", "Keep this to yourself, but...",
                        "I saw " + c.accusedName + " at it, plain as day.", "Did you hear? " + c.accusedName + ", of all people.",
                        "I've seen something I can't unsee.", "Between you and me, there's been " + c.charge.word + "."));
                    changed();
                    break;
                }
            }
            if (now < w.reportAt || f.isSleeping()) continue;
            w.reported = true;
            if (c.stage == Stage.SEEN) {
                c.stage = Stage.REPORTED;
                c.inquiryUntil = now + pause(1200);
            }
            c.notes.add(w.name + (w.own ? " told the watch what they saw" : " told the watch what " + w.from + " had said"));
            FolkTalk.speak(f, w.own ? FolkTalk.pick(f.getRandom(), "I've told the watch what I saw.", "The watch knows. It's out of my hands.",
                "I said what I saw. That's my duty done.", "Somebody had to tell the watch.", "I gave the watch a name.", "It's with the watch now.")
                : FolkTalk.pick(f.getRandom(), "I told the watch what I heard. It's not my word, mind.", "Hearsay, but the watch should know it.",
                "I passed it on. Make of it what they will.", "The watch has it now. Second-hand, but there.", "I told them what I was told.",
                "They wanted to know. I told them."));
            Police.log(v.id(), level.getDayTime(), "report", w.name + " reported " + c.title() + (w.own ? "" : " (hearsay from " + w.from + ")"), f, 0);
            changed();
        }
    }

    /** The watch weighs a case: enough for a summons or a warrant, or not; and, with nothing more coming, unsolved. */
    static void weigh(ServerLevel level, Villages.Village v, JCase c, long now) {
        // A body is found, a theft missed: reported even with no witness.
        if (c.stage == Stage.SEEN && now >= c.noticeAt && (c.charge.killing() || c.charge == Charge.BURGLARY || c.charge == Charge.THEFT
                || c.charge == Charge.VANDALISM || c.charge == Charge.FRAUD)) {
            c.stage = Stage.REPORTED;
            c.inquiryUntil = now + pause(1200);
            c.notes.add(c.charge.killing() ? "the body was found" : "it was missed and reported");
            if (c.charge.killing()) Villages.tell(v.id(), c.day, c.victimName + " was found dead at " + c.place);
            changed();
        }
        if (c.stage != Stage.REPORTED) return;
        // The watch looks: what there is to be found is found. A motive only if the watch knows the dead had seen
        // something (it told, or somebody told on its word); else the watch has no reason to look for one.
        for (Ev e : c.evidence) {
            if (e.found) continue;
            if (e.kind.startsWith("MOTIVE:")) {
                if (!motiveKnown(e.kind)) continue;
                if (c.charge == Charge.MANSLAUGHTER) c.charge = Charge.MURDER;   // killed to silence a witness
            }
            e.found = true;
            changed();
        }
        double need = warrantNeed();
        if (c.score() >= need) {
            name(level, v, c);
            return;
        }
        if (now >= c.inquiryUntil && !c.pending()) {
            c.stage = Stage.UNSOLVED;
            c.notes.add("the watch could not name anybody");
            Police.log(v.id(), level.getDayTime(), "unsolved", c.title() + ": unsolved", null, 0);
            changed();
        }
    }

    /** "MOTIVE:case:witness": does the watch know that witness saw that case (its own report, or hearsay of it)? */
    static boolean motiveKnown(String kind) {
        String[] p = kind.split(":");
        if (p.length < 3) return false;
        JCase o;
        try { o = get(Integer.parseInt(p[1])); } catch (NumberFormatException e) { return false; }
        if (o == null) return false;
        for (Wit w : o.witnesses) {
            if (!w.reported) continue;
            if (w.id.toString().equals(p[2]) || !w.own && o.witnesses.stream().anyMatch(x -> x.id.toString().equals(p[2]) && x.name.equals(w.from))) return true;
        }
        return false;
    }

    /** What the watch wants before it names somebody (harsher towns want less). */
    static double warrantNeed() {
        return 3.0 / Math.sqrt(Math.max(0.25, JusticeConfig.harshness()));
    }

    /** Named: a summons for a first lesser offence, a warrant for anything graver. */
    static void name(ServerLevel level, Villages.Village v, JCase c) {
        if (!c.player) {                                                    // a folk: the watch's own way (FolkCrimes)
            FolkCrimes.named(level, v, c);
            return;
        }
        long now = level.getGameTime();
        boolean summons = c.charge.summons && c.extra.isEmpty() && recordScore(c.accused) < 3 && c.player;
        if (!c.player) summons = false;
        VillageFolkEntity off = Arrests.officerFor(level, v, c.where);
        c.officer = off == null ? null : off.getUUID();
        c.officerName = off == null ? "the watch" : off.displayNameCap();
        if (summons) {
            c.stage = Stage.SUMMONED;
            c.courtAt = now + Math.max(pause(1200), (long) (JusticeConfig.courtDelayMinutes() * 1200 * (hurry ? 0.05 : 2.0)));
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(c.accused);
            BlockPos court = Assize.courtPlace(level, v);
            if (p != null) {
                long when = level.getDayTime() + (c.courtAt - now);
                Planner.add(p, when, "court", "Court: " + c.title(), court);
                notice(p, "A summons to court", "You are summoned to answer a charge of " + c.title() + " (witness: " + nonEmpty(c.firstWitness(), "the watch's evidence") + ").",
                    "Be at " + Assize.courtWords(level, v) + " in " + minutes(c.courtAt - now) + ". It is on your planner. Prepare your defence on the Record page; not coming is a warrant.");
            }
            Police.log(v.id(), level.getDayTime(), "summons", c.accusedName + " summoned for " + c.title(), off, 0);
        } else {
            c.stage = Stage.WARRANT;
            Police.log(v.id(), level.getDayTime(), "warrant", "a warrant for " + c.accusedName + ": " + c.title(), off, 0);
            Villages.tell(v.id(), c.day, "the watch has a warrant out for " + c.accusedName + " for " + c.title());
        }
        changed();
    }

    // ------------------------------------------------------------------ the folk's part, and a prisoner pulling away

    /** The law's folk (VillageFolkEntity's round): a guard with a warrant or a prisoner, the court's folk called to it. */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (!enabled()) return false;
        return Arrests.hold(f, level) || Assize.hold(f, level);
    }

    /** What the law has it doing (its card), or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        return Arrests.doing(f);
    }

    static void brokeAway(ServerLevel level, ServerPlayer p, @Nullable UUID leader) {
        Arrests.brokeAway(level, p, leader);
    }

    /** The board's lines: the wanted, the summoned, those held for trial, the court's next sitting. */
    public static List<String> boardLines(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (JCase c : cases(village)) {
            if (c.joinedTo != 0) continue;
            if (c.stage == Stage.WARRANT && c.notes.stream().anyMatch(n -> n.startsWith("wanted"))) out.add("RB|WANTED: " + c.accusedName + ", for " + c.chargeWords() + " at " + c.place + ". Fled the watch.");
            else if (c.stage == Stage.WARRANT) out.add("RW|Warrant: " + c.accusedName + ", for " + c.chargeWords() + ".");
            else if (c.stage == Stage.SUMMONED) out.add("RG|Summoned to court: " + c.accusedName + ", for " + c.title() + ".");
            else if (c.stage == Stage.REMAND) out.add("RG|Held for trial: " + c.accusedName + ", for " + c.title() + ".");
        }
        return out;
    }

    // ------------------------------------------------------------------ the player's side: notices and talk

    /** The last notice for each player (the Record page's Notice tab). */
    static final Map<UUID, String[]> NOTICES = new ConcurrentHashMap<>();

    /** A notice on the player's screen (and in chat): what happened, why, what's next. */
    public static void notice(ServerPlayer p, String title, String what, String next) {
        NOTICES.put(p.getUUID(), new String[]{ title, what, next, String.valueOf(p.level().getGameTime()) });
        p.sendSystemMessage(Component.literal(title + ": " + what + " " + next).withStyle(ChatFormatting.GOLD));
        PlayerLife.open(p, "record", LifePage.args("tab", "notice"));
    }

    /** The talk screen's flags: "hurtme" (an apology owed this folk), "wantedme", "accusedme" (a defence to make), "jailedme". */
    public static String contextFlags(VillageFolkEntity f, ServerPlayer p) {
        StringBuilder sb = new StringBuilder();
        if (hurtOwed(p.getUUID(), f.getUUID()) != null) sb.append(" hurtme");
        boolean wanted = false, accused = false;
        for (JCase c : namedAgainst(p.getUUID())) {
            if (c.stage == Stage.WARRANT) wanted = true;
            if (c.stage.ordinal() >= Stage.SUMMONED.ordinal() && c.stage.ordinal() <= Stage.REMAND.ordinal()) accused = true;
        }
        if (wanted) sb.append(" wantedme");
        if (accused) sb.append(" accusedme");
        if (Jail.held(p.getUUID())) sb.append(" jailedme");
        return sb.toString();
    }

    /** The talk menu's justice phrases. Null for anything else. */
    @Nullable
    public static String heard(VillageFolkEntity f, ServerPlayer p, String t) {
        if ((t.equals("i'm sorry") || t.equals("sorry")) && hurtOwed(p.getUUID(), f.getUUID()) != null) return apologise(f, p, false);
        if (t.contains("sorry i hurt you") || t.equals("i apologise") || t.contains("apologise for hurting")) {
            String a = apologise(f, p, false);
            return a != null ? a : FolkTalk.pick(f.getRandom(), "Sorry for what?", "No harm done that I know of.", "You've nothing to be sorry for.");
        }
        if (t.contains("pay for the healer") || t.contains("pay for your healing")) {
            String a = apologise(f, p, true);
            return a != null ? a : "There's nothing to pay for.";
        }
        if (t.contains("my record") || t.contains("my case")) {
            PlayerLife.open(p, "record", null);
            return FolkTalk.pick(f.getRandom(), "The watch keeps the books. Here's yours.", "Let's see what's written against you.",
                "Your record? Here.", "Everything the watch has on you, in black and white.", "Have a look. Clean, I hope.", "The record's the record.");
        }
        if (t.contains("come quietly") || t.contains("i give myself up") || t.contains("i surrender")) {
            String s = Arrests.surrender(f, p);
            if (s != null) return s;
        }
        if (t.contains("i need a lawyer") || t.contains("hire a lawyer")) {
            PlayerLife.open(p, "record", LifePage.args("tab", "case"));
            return FolkTalk.pick(f.getRandom(), "A lawyer? The Record page has who'll take your case.", "You'll want somebody who knows the law. Have a look.",
                "If you can't pay, the town gives you one.", "Lawyers cost. Being found guilty costs more.", "Here: who's free to speak for you.", "Get your story straight first.");
        }
        return null;
    }

    // ------------------------------------------------------------------ words

    /** Where it was, by what stands nearest: "Hartwell Farm", "the market", "the square". */
    static String placeOf(ServerLevel level, Villages.Village v, BlockPos at) {
        String best = null;
        double bd = 24.0 * 24.0;
        for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(v.id())) {
            double d = b.anchor().distSqr(at);
            if (d < bd) { bd = d; best = Villages.spoken(b.structure()); }
        }
        if (best == null) best = at.distSqr(v.centre()) < 20 * 20 ? "the square" : "the edge of " + Villages.name(v.id());
        return best;
    }

    static String minutes(long ticks) {
        long s = Math.max(0, ticks / 20);
        if (s < 60) return s + " seconds";
        long m = (s + 30) / 60;
        return m == 1 ? "a minute" : m + " minutes";
    }

    static String nonEmpty(String s, String or) {
        return s == null || s.isEmpty() ? or : s;
    }

    // ------------------------------------------------------------------ tests

    public static void resetForTests() {
        HURTS.clear();
        BLOWS.clear();
        LAST_BLOW.clear();
        SEEN_WITH.clear();
        NOTICES.clear();
        hurry = false;
        creativeUnderLawForTests = false;
        FolkCrimes.resetForTests();
        Arrests.resetForTests();
        Assize.resetForTests();
        Jail.resetForTests();
    }

    /** Tests: a player's custody and cases forgotten (its record too). */
    public static void forgetForTests(UUID who) {
        Justice j = of();
        j.records.remove(who.toString());
        j.custody.remove(who.toString());
        j.cases.values().removeIf(c -> c.accused.equals(who));
        j.setDirty();
    }

    /** Tests: a case opened against a player as if the deed were done here (no witnesses). */
    public static int openForTests(ServerLevel level, Villages.Village v, Charge charge, ServerPlayer p, @Nullable VillageFolkEntity victim) {
        JCase c = open(level, v.id(), charge, p.getUUID(), Belonging.called(p), true, victim == null ? null : victim.getUUID(),
            victim == null ? "the town" : victim.displayNameCap(), false, placeOf(level, v, p.blockPosition()), p.blockPosition());
        return c.id;
    }

    /** Tests: what the officer is about ("GO", "LEAD", ...), or "". */
    public static String officerTaskForTests(VillageFolkEntity g) {
        return Arrests.taskName(g);
    }

    public static void roundForTests(ServerLevel level, Villages.Village v) {
        round(level, v);
    }

    public static boolean hurtOwedForTests(UUID player, UUID folk) {
        return hurtOwed(player, folk) != null;
    }

    public static String apologiseForTests(VillageFolkEntity f, ServerPlayer p, boolean pay) {
        return apologise(f, p, pay);
    }

    public static void expireApologiesForTests(ServerLevel level) {
        long now = level.getGameTime();
        for (Map.Entry<UUID, Hurt> e : HURTS.entrySet()) {
            Hurt h = e.getValue();
            HURTS.put(e.getKey(), new Hurt(h.folk, h.village, h.name, h.amount, now - 100000));
        }
        apologies(level);
    }

    /** Tests: every witness on its way to the watch goes now. */
    public static void reportNowForTests(UUID village) {
        for (JCase c : of().cases.values()) {
            if (!c.village.equals(village)) continue;
            for (Wit w : c.witnesses) { w.reportAt = 0; w.tellAt = Long.MAX_VALUE; }
            c.noticeAt = 0;
            c.inquiryUntil = 0;
        }
    }

    /** Tests: a witness tells this neighbour what it saw (hearsay, should it die). */
    public static void tellForTests(int caseId, UUID witness, UUID neighbour) {
        JCase c = get(caseId);
        if (c == null) return;
        for (Wit w : c.witnesses) if (w.id.equals(witness)) w.told.add(neighbour);
    }

    /** Tests: the witnesses hold their tongues for now. */
    public static void holdTonguesForTests(UUID village) {
        for (JCase c : of().cases.values()) {
            if (!c.village.equals(village)) continue;
            for (Wit w : c.witnesses) if (!w.reported) { w.reportAt = Long.MAX_VALUE; w.tellAt = Long.MAX_VALUE; }
            c.noticeAt = Long.MAX_VALUE;
        }
    }

    public static String caseLine(JCase c) {
        StringBuilder sb = new StringBuilder("#" + c.id + " " + c.chargeWords() + " at " + c.place + " [" + c.stage + "] accused " + c.accusedName
            + " score " + String.format(Locale.ROOT, "%.1f", c.score()) + " intent " + c.intent);
        for (Wit w : c.witnesses) sb.append("; wit ").append(w.name).append(w.own ? "" : "(hearsay)").append(w.reported ? " told" : "").append(w.lost ? " LOST" : "");
        for (Ev e : c.evidence) sb.append("; ev ").append(e.kind).append(e.found ? "" : "(hidden)");
        if (!c.verdict.isEmpty()) sb.append("; verdict ").append(c.verdict).append(" ").append(c.finding).append("; sentence ").append(c.sentence);
        return sb.toString();
    }
}
