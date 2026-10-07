package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * [crime] Petty crime among the folk, and the watch's detective work.
 *
 * <p>A folk that is poor, in debt, hungry, low or bitter, and not honest enough to let it go, may one day
 * do something about it (Mischief): lift a few coins from a purse at the market or the tavern, take a thing
 * from a neighbour's chest or from the stores, break a window, a lamp or a fence, take a beast from the pen,
 * or (rarer) pass a forged coin or slip a lot of the stores' goods out of the town's books. It waits till
 * nobody is near enough to see, and then really does it: the coin moves between purses, the thing goes into
 * its pack and home to its chest, the window is broken. Never anybody hurt, and never more than a few coins'
 * worth. It leaves what any deed leaves: footprints from the scene, sometimes something dropped, the hour,
 * whoever was about, a purse fuller than its wages, the stolen thing in a chest. And now and then somebody
 * saw it, from further off than the culprit thought, and not as well as it would like.
 *
 * <p>The victim notices and the board posts it. The watch takes the case (Inquiry): a guard, or the town's
 * constable once it has come to the Iron Age, walks to the scene, reads it, questions whoever was about
 * (each answers by what it saw, by its honesty and by whom it loves or hates), searches the likeliest, and
 * names the one the evidence points to, or gives it up. The council tries the accused at the hall (Trial):
 * the evidence is heard, the witnesses speak, and the verdict goes by its weight. Paying back and a fine, the
 * stocks on the square for a day, community work (mending what was broken, sweeping the streets) or, for a
 * third offence, banishment. The victim is made whole from the culprit's purse or its goods; the chronicle,
 * the gazette, the crier and the folk's own talk tell of it; and some who are caught turn over a new leaf.
 *
 * <p>Lights, the night watch and a contented town all mean less of it; the town's books (the Cases page)
 * keep the case files, the detective's notes and the crime rate. Players can be witnesses, help the watch
 * (ask the folk what they saw, bring in what was dropped) and report what they saw; they remain under the
 * town's own Laws.
 *
 * <p>This class keeps the records with the world (every case, each folk's record, each town's tallies),
 * runs the round of the towns, and is where the rest of the mod meets it: the folk's tick, its spirits,
 * its card and talk, the board, the gazette, the crier, the books and the commands.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Crime extends SavedData {

    private static final String ID = "mc_assistant_crime";
    /** The round of the towns: every five seconds. */
    static final int EVERY = 100;
    /** The marks: on a thing stolen (its case), and on what a culprit dropped at the scene (its case). */
    static final String STOLEN = "mca_stolen", CLUE = "mca_clue";
    /** How long a town's books keep a closed case (days), and the most cases kept for a town. */
    static final int KEEP_DAYS = 56, KEEP_MOST = 40;

    // ------------------------------------------------------------------ what can be done

    /** The kinds of crime. Never violence: nobody is ever hurt by any of them. */
    public enum Kind {
        PICKPOCKET("pickpocketing", "Theft", "lifted coins from a purse"),
        BURGLARY("theft from a house", "Theft", "took something from a house"),
        STORES("theft from the stores", "Theft", "took something from the stores"),
        VANDALISM("vandalism", "Damage", "broke"),
        POACHING("poaching", "Poaching", "took a beast from the pen"),
        FORGERY("passing a forged coin", "Forgery", "passed a forged coin"),
        SMUGGLING("smuggling", "Smuggling", "slipped goods out of the stores");

        /** "pickpocketing". */
        public final String word;
        /** For the board: "Theft". */
        public final String headline;
        /** What the culprit did, for a witness's words. */
        public final String did;

        Kind(String word, String headline, String did) {
            this.word = word;
            this.headline = headline;
            this.did = did;
        }

        /** Done after dark: the lamps and the night watch keep it down. */
        boolean night() {
            return this == STORES || this == VANDALISM || this == POACHING || this == SMUGGLING;
        }

        /** The graver ones, for a first offence the stocks and not only a fine. */
        boolean grave() {
            return this == FORGERY || this == SMUGGLING;
        }
    }

    /** Where a case has got to. */
    public enum Stage {
        UNNOTICED("not yet noticed"), REPORTED("reported to the watch"), SCENE("the watch at the scene"),
        QUESTIONING("the watch asking questions"), SEARCHING("the watch searching"), ACCUSED("awaiting trial"),
        TRIAL("before the council"), CONVICTED("solved: convicted"), ACQUITTED("acquitted"), UNSOLVED("unsolved");

        public final String words;

        Stage(String words) { this.words = words; }

        public boolean open() { return ordinal() <= TRIAL.ordinal(); }

        public boolean investigating() { return this == SCENE || this == QUESTIONING || this == SEARCHING; }
    }

    // ------------------------------------------------------------------ what the books keep

    /** Something the watch found that points at somebody: the folk it points at and how strongly at each. */
    public static final class Clue {
        final String kind;
        final String text;
        final List<UUID> points = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final double weight;
        final long day;

        Clue(String kind, String text, double weight, long day) {
            this.kind = kind;
            this.text = text;
            this.weight = weight;
            this.day = day;
        }

        Clue at(UUID id, String name) {
            if (!points.contains(id)) { points.add(id); names.add(name); }
            return this;
        }

        public String text() { return text; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("kind", kind);
            t.putString("text", text);
            t.putDouble("weight", weight);
            t.putLong("day", day);
            ListTag l = new ListTag();
            for (int i = 0; i < points.size(); i++) {
                CompoundTag p = new CompoundTag();
                p.putUUID("id", points.get(i));
                p.putString("name", names.get(i));
                l.add(p);
            }
            t.put("points", l);
            return t;
        }

        static Clue load(CompoundTag t) {
            Clue c = new Clue(t.getString("kind"), t.getString("text"), t.getDouble("weight"), t.getLong("day"));
            for (Tag x : t.getList("points", Tag.TAG_COMPOUND)) {
                CompoundTag p = (CompoundTag) x;
                if (p.hasUUID("id")) c.at(p.getUUID("id"), p.getString("name"));
            }
            return c;
        }
    }

    /**
     * Somebody who saw the deed: how far off, how well (a hundred is as plain as day), what it saw, and who it
     * thinks it was (perhaps wrongly, perhaps nobody). What it says when asked is a Statement, and may differ.
     */
    public static final class Witness {
        final UUID id;
        final String name;
        final boolean player;
        final int dist, certainty;
        final String saw;
        @Nullable final UUID thinks;
        final String thinksName;
        /** The culprit's trade as it saw its clothes, or "" (too far, too dark). */
        final String outfit;

        Witness(UUID id, String name, boolean player, int dist, int certainty, String saw, @Nullable UUID thinks, String thinksName,
                String outfit) {
            this.id = id;
            this.name = name;
            this.player = player;
            this.dist = dist;
            this.certainty = certainty;
            this.saw = saw;
            this.thinks = thinks;
            this.thinksName = thinksName;
            this.outfit = outfit;
        }

        public String name() { return name; }

        public int certainty() { return certainty; }

        @Nullable public UUID thinks() { return thinks; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putString("name", name);
            t.putBoolean("player", player);
            t.putInt("dist", dist);
            t.putInt("certainty", certainty);
            t.putString("saw", saw);
            if (thinks != null) t.putUUID("thinks", thinks);
            t.putString("thinksName", thinksName);
            t.putString("outfit", outfit);
            return t;
        }

        static Witness load(CompoundTag t) {
            return new Witness(t.getUUID("id"), t.getString("name"), t.getBoolean("player"), t.getInt("dist"), t.getInt("certainty"),
                t.getString("saw"), t.hasUUID("thinks") ? t.getUUID("thinks") : null, t.getString("thinksName"), t.getString("outfit"));
        }
    }

    /** What somebody told the watch (or a player who asked): who it named, how sure it said it was, and what it was keeping back. */
    public static final class Statement {
        final UUID from;
        final String fromName;
        final boolean player;
        final String text;
        @Nullable final UUID named;
        final String namedName;
        final int claimed;
        /** The trade whose clothes it described, or "". */
        final String outfit;
        /** Said nothing to shield somebody it loves; said it was elsewhere when it was not. */
        final boolean covering, lie;
        final String askedBy;
        final long day;
        /** How far the watch trusts it on who it named: less from a known rival of theirs, a convicted folk or a child. */
        double trust = 1.0;

        Statement(UUID from, String fromName, boolean player, String text, @Nullable UUID named, String namedName, int claimed, String outfit,
                  boolean covering, boolean lie, String askedBy, long day) {
            this.from = from;
            this.fromName = fromName;
            this.player = player;
            this.text = text;
            this.named = named;
            this.namedName = namedName;
            this.claimed = claimed;
            this.outfit = outfit;
            this.covering = covering;
            this.lie = lie;
            this.askedBy = askedBy;
            this.day = day;
        }

        public String text() { return text; }

        @Nullable public UUID named() { return named; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("from", from);
            t.putString("fromName", fromName);
            t.putBoolean("player", player);
            t.putString("text", text);
            if (named != null) t.putUUID("named", named);
            t.putString("namedName", namedName);
            t.putInt("claimed", claimed);
            t.putString("outfit", outfit);
            t.putBoolean("covering", covering);
            t.putBoolean("lie", lie);
            t.putString("askedBy", askedBy);
            t.putLong("day", day);
            t.putDouble("trust", trust);
            return t;
        }

        static Statement load(CompoundTag t) {
            Statement s = new Statement(t.getUUID("from"), t.getString("fromName"), t.getBoolean("player"), t.getString("text"),
                t.hasUUID("named") ? t.getUUID("named") : null, t.getString("namedName"), t.getInt("claimed"), t.getString("outfit"),
                t.getBoolean("covering"), t.getBoolean("lie"), t.getString("askedBy"), t.getLong("day"));
            if (t.contains("trust")) s.trust = t.getDouble("trust");
            return s;
        }
    }

    /** Somebody of the town who was about at the time: how far off, what was in its purse, what it was doing. */
    record Near(UUID id, String name, int dist, int purse, String doing, String trade) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putString("name", name);
            t.putInt("dist", dist);
            t.putInt("purse", purse);
            t.putString("doing", doing);
            t.putString("trade", trade);
            return t;
        }

        static Near load(CompoundTag t) {
            return new Near(t.getUUID("id"), t.getString("name"), t.getInt("dist"), t.getInt("purse"), t.getString("doing"), t.getString("trade"));
        }
    }

    /** One the watch suspects, with how heavily the evidence points at it and why. */
    public static final class Suspect {
        final UUID id;
        final String name;
        double weight;
        final List<String> why = new ArrayList<>();

        Suspect(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public UUID id() { return id; }

        public String name() { return name; }

        public double weight() { return weight; }
    }

    /** A case: the deed as it was (the truth, kept from everybody till it is proven), what the watch found, and how it ended. */
    public static final class Case {
        public final int id;
        public final UUID village;
        public final Kind kind;
        /** The day it was done, and the hour (the time of day). */
        public final long day, hour;
        public final BlockPos where;
        /** "the market", "Bree's house", "the stores", "the lamp post on the east avenue". */
        public final String place;
        final UUID culprit;
        final String culpritName;
        @Nullable final UUID victim;
        final String victimName;
        int worth, coins, goodsCount;
        String goods = "", goodsId = "", broke = "", brokeWhat = "";
        boolean mended;
        final List<Near> near = new ArrayList<>();
        final List<Witness> witnesses = new ArrayList<>();
        final List<Statement> statements = new ArrayList<>();
        final List<BlockPos> trail = new ArrayList<>();
        /** The trail read by the watch, washed out by the rain; the trail's end (the culprit's door, or where it gave out). */
        boolean trailRead, trailWashed;
        @Nullable UUID droppedEntity, droppedOwner;
        String dropped = "", droppedTrade = "";
        boolean droppedFound;
        /** What was dropped, as the watch holds it till the case is closed (the stack, saved). */
        CompoundTag evidence = new CompoundTag();
        Stage stage = Stage.UNNOTICED;
        /** When it will be noticed (the day time). */
        long noticeAt;
        long reportedDay = -1, closedDay = -1, progressDay = -1;
        String reportedBy = "";
        @Nullable UUID investigator;
        String investigatorName = "";
        final List<Clue> clues = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final Set<UUID> cleared = new HashSet<>();
        final Set<UUID> searched = new HashSet<>();
        @Nullable UUID accused;
        String accusedName = "";
        String verdict = "", sentence = "";
        boolean confessed, retried;
        /** Players who helped: asked a witness, brought in a clue, told what they saw. */
        final List<UUID> helpers = new ArrayList<>();
        final List<String> helperNames = new ArrayList<>();

        Case(int id, UUID village, Kind kind, long day, long hour, BlockPos where, String place, UUID culprit, String culpritName,
             @Nullable UUID victim, String victimName) {
            this.id = id;
            this.village = village;
            this.kind = kind;
            this.day = day;
            this.hour = hour;
            this.where = where;
            this.place = place;
            this.culprit = culprit;
            this.culpritName = culpritName;
            this.victim = victim;
            this.victimName = victimName;
        }

        // For the tests and the quests (who did it is the books' secret till the court has it).
        public Stage stage() { return stage; }
        public UUID culprit() { return culprit; }
        public String culpritName() { return culpritName; }
        @Nullable public UUID victim() { return victim; }
        public String victimName() { return victimName; }
        public int coins() { return coins; }
        public int worth() { return worth; }
        public String goods() { return goods; }
        @Nullable public UUID accused() { return accused; }
        public String accusedName() { return accusedName; }
        @Nullable public UUID investigator() { return investigator; }
        public String investigatorName() { return investigatorName; }
        public String verdict() { return verdict; }
        public String sentence() { return sentence; }
        public List<Witness> witnesses() { return List.copyOf(witnesses); }
        public List<Statement> statements() { return List.copyOf(statements); }
        public List<Clue> clues() { return List.copyOf(clues); }
        public List<String> notes() { return List.copyOf(notes); }
        public List<BlockPos> trail() { return List.copyOf(trail); }
        public boolean confessed() { return confessed; }
        public Set<UUID> cleared() { return Set.copyOf(cleared); }

        /** "Theft at the market". */
        public String title() {
            return kind.headline + (kind == Kind.VANDALISM && !brokeWhat.isEmpty() ? " (" + brokeWhat + ")" : "") + " at " + place;
        }

        /** What was taken or broken, in a few words: "3 coins from Bree's purse", "a window of Ash's house". */
        public String what() {
            return switch (kind) {
                case PICKPOCKET -> coins + (coins == 1 ? " coin" : " coins") + " from " + victimName + "'s purse";
                case BURGLARY -> goods + " from " + victimName + "'s chest";
                case STORES, SMUGGLING -> goods + " from the stores";
                case VANDALISM -> brokeWhat.isEmpty() ? "something broken" : brokeWhat + " broken";
                case POACHING -> goods.isEmpty() ? "a beast from the pen" : goods + " from the pen";
                case FORGERY -> "a forged coin passed" + (goods.isEmpty() ? "" : " for " + goods);
            };
        }

        void note(long d, String text) {
            notes.add("Day " + d + ": " + text);
            while (notes.size() > 24) notes.remove(0);
        }

        @Nullable Statement statementFrom(UUID who) {
            for (Statement s : statements) if (s.from.equals(who)) return s;
            return null;
        }

        @Nullable Witness witness(UUID who) {
            for (Witness w : witnesses) if (w.id.equals(who)) return w;
            return null;
        }

        @Nullable Near nearOf(UUID who) {
            for (Near n : near) if (n.id.equals(who)) return n;
            return null;
        }

        void helped(Player p) {
            if (helpers.contains(p.getUUID())) return;
            helpers.add(p.getUUID());
            helperNames.add(p.getName().getString());
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("id", id);
            t.putUUID("village", village);
            t.putString("kind", kind.name());
            t.putLong("day", day);
            t.putLong("hour", hour);
            t.put("where", NbtUtils.writeBlockPos(where));
            t.putString("place", place);
            t.putUUID("culprit", culprit);
            t.putString("culpritName", culpritName);
            if (victim != null) t.putUUID("victim", victim);
            t.putString("victimName", victimName);
            t.putInt("worth", worth);
            t.putInt("coins", coins);
            t.putInt("goodsCount", goodsCount);
            t.putString("goods", goods);
            t.putString("goodsId", goodsId);
            t.putString("broke", broke);
            t.putString("brokeWhat", brokeWhat);
            t.putBoolean("mended", mended);
            ListTag l = new ListTag();
            for (Near n : near) l.add(n.save());
            t.put("near", l);
            l = new ListTag();
            for (Witness w : witnesses) l.add(w.save());
            t.put("witnesses", l);
            l = new ListTag();
            for (Statement s : statements) l.add(s.save());
            t.put("statements", l);
            l = new ListTag();
            for (BlockPos p : trail) l.add(NbtUtils.writeBlockPos(p));
            t.put("trail", l);
            t.putBoolean("trailRead", trailRead);
            t.putBoolean("trailWashed", trailWashed);
            if (droppedEntity != null) t.putUUID("droppedEntity", droppedEntity);
            if (droppedOwner != null) t.putUUID("droppedOwner", droppedOwner);
            t.putString("dropped", dropped);
            t.putString("droppedTrade", droppedTrade);
            t.putBoolean("droppedFound", droppedFound);
            t.put("evidence", evidence.copy());
            t.putString("stage", stage.name());
            t.putLong("noticeAt", noticeAt);
            t.putLong("reportedDay", reportedDay);
            t.putLong("closedDay", closedDay);
            t.putLong("progressDay", progressDay);
            t.putString("reportedBy", reportedBy);
            if (investigator != null) t.putUUID("investigator", investigator);
            t.putString("investigatorName", investigatorName);
            l = new ListTag();
            for (Clue c : clues) l.add(c.save());
            t.put("clues", l);
            l = new ListTag();
            for (String n : notes) l.add(StringTag.valueOf(n));
            t.put("notes", l);
            l = new ListTag();
            for (UUID u : cleared) l.add(NbtUtils.createUUID(u));
            t.put("cleared", l);
            l = new ListTag();
            for (UUID u : searched) l.add(NbtUtils.createUUID(u));
            t.put("searched", l);
            if (accused != null) t.putUUID("accused", accused);
            t.putString("accusedName", accusedName);
            t.putString("verdict", verdict);
            t.putString("sentence", sentence);
            t.putBoolean("confessed", confessed);
            t.putBoolean("retried", retried);
            l = new ListTag();
            for (int i = 0; i < helpers.size(); i++) {
                CompoundTag h = new CompoundTag();
                h.putUUID("id", helpers.get(i));
                h.putString("name", helperNames.get(i));
                l.add(h);
            }
            t.put("helpers", l);
            return t;
        }

        static Case load(CompoundTag t) {
            Kind kind;
            try { kind = Kind.valueOf(t.getString("kind")); } catch (IllegalArgumentException e) { kind = Kind.PICKPOCKET; }
            Case c = new Case(t.getInt("id"), t.getUUID("village"), kind, t.getLong("day"), t.getLong("hour"),
                NbtUtils.readBlockPos(t, "where").orElse(BlockPos.ZERO), t.getString("place"), t.getUUID("culprit"), t.getString("culpritName"),
                t.hasUUID("victim") ? t.getUUID("victim") : null, t.getString("victimName"));
            c.worth = t.getInt("worth");
            c.coins = t.getInt("coins");
            c.goodsCount = t.getInt("goodsCount");
            c.goods = t.getString("goods");
            c.goodsId = t.getString("goodsId");
            c.broke = t.getString("broke");
            c.brokeWhat = t.getString("brokeWhat");
            c.mended = t.getBoolean("mended");
            for (Tag x : t.getList("near", Tag.TAG_COMPOUND)) c.near.add(Near.load((CompoundTag) x));
            for (Tag x : t.getList("witnesses", Tag.TAG_COMPOUND)) c.witnesses.add(Witness.load((CompoundTag) x));
            for (Tag x : t.getList("statements", Tag.TAG_COMPOUND)) c.statements.add(Statement.load((CompoundTag) x));
            for (Tag x : t.getList("trail", Tag.TAG_INT_ARRAY)) {
                int[] a = ((IntArrayTag) x).getAsIntArray();
                if (a.length == 3) c.trail.add(new BlockPos(a[0], a[1], a[2]));
            }
            c.trailRead = t.getBoolean("trailRead");
            c.trailWashed = t.getBoolean("trailWashed");
            c.droppedEntity = t.hasUUID("droppedEntity") ? t.getUUID("droppedEntity") : null;
            c.droppedOwner = t.hasUUID("droppedOwner") ? t.getUUID("droppedOwner") : null;
            c.dropped = t.getString("dropped");
            c.droppedTrade = t.getString("droppedTrade");
            c.droppedFound = t.getBoolean("droppedFound");
            c.evidence = t.getCompound("evidence");
            try { c.stage = Stage.valueOf(t.getString("stage")); } catch (IllegalArgumentException e) { c.stage = Stage.UNSOLVED; }
            c.noticeAt = t.getLong("noticeAt");
            c.reportedDay = t.getLong("reportedDay");
            c.closedDay = t.getLong("closedDay");
            c.progressDay = t.getLong("progressDay");
            c.reportedBy = t.getString("reportedBy");
            c.investigator = t.hasUUID("investigator") ? t.getUUID("investigator") : null;
            c.investigatorName = t.getString("investigatorName");
            for (Tag x : t.getList("clues", Tag.TAG_COMPOUND)) c.clues.add(Clue.load((CompoundTag) x));
            for (Tag x : t.getList("notes", Tag.TAG_STRING)) c.notes.add(x.getAsString());
            for (Tag x : t.getList("cleared", Tag.TAG_INT_ARRAY)) c.cleared.add(NbtUtils.loadUUID(x));
            for (Tag x : t.getList("searched", Tag.TAG_INT_ARRAY)) c.searched.add(NbtUtils.loadUUID(x));
            c.accused = t.hasUUID("accused") ? t.getUUID("accused") : null;
            c.accusedName = t.getString("accusedName");
            c.verdict = t.getString("verdict");
            c.sentence = t.getString("sentence");
            c.confessed = t.getBoolean("confessed");
            c.retried = t.getBoolean("retried");
            for (Tag x : t.getList("helpers", Tag.TAG_COMPOUND)) {
                CompoundTag h = (CompoundTag) x;
                if (!h.hasUUID("id")) continue;
                c.helpers.add(h.getUUID("id"));
                c.helperNames.add(h.getString("name"));
            }
            return c;
        }
    }

    // ------------------------------------------------------------------ the record

    private final Map<Integer, Case> cases = new LinkedHashMap<>();
    /** Each folk's record (convictions, debts, the stocks...), and each town's tallies (what put crime off). */
    private CompoundTag folk = new CompoundTag(), towns = new CompoundTag();
    private int next;
    /** With no server about (never in a game; a safeguard), a record that lives as long as the class. */
    @Nullable private static Crime loose;

    public Crime() {}

    static Crime of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Crime();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Crime::new, Crime::load, null), ID);
    }

    public static Crime load(CompoundTag tag, HolderLookup.Provider registries) {
        Crime c = new Crime();
        for (Tag x : tag.getList("cases", Tag.TAG_COMPOUND)) {
            try {
                Case k = Case.load((CompoundTag) x);
                c.cases.put(k.id, k);
            } catch (RuntimeException e) {
                com.mojang.logging.LogUtils.getLogger().warn("[MCA-CRIME] a case could not be read", e);
            }
        }
        c.folk = tag.getCompound("folk");
        c.towns = tag.getCompound("towns");
        c.next = tag.getInt("next");
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag l = new ListTag();
        for (Case k : cases.values()) l.add(k.save());
        tag.put("cases", l);
        tag.put("folk", folk.copy());
        tag.put("towns", towns.copy());
        tag.putInt("next", next);
        return tag;
    }

    static void changed() {
        of().setDirty();
    }

    /**
     * What is only memory forgotten (Villages.resetForTests: between the tests, and when a world opens or closes). The
     * books kept with the world are not touched: SessionReset calls this as every world starts, and a casebook wiped
     * then would be a town's whole history of crime lost at each restart. Every case and record is the town's or the
     * folk's own by its id, so one test's never touches another's.
     */
    public static void resetForTests() {
        HELD.clear();
        HONESTY_FOR_TESTS.clear();
        hurry = false;
        Mischief.resetForTests();
        Inquiry.resetForTests();
        Trial.resetForTests();
    }

    static int nextId() {
        Crime c = of();
        c.next++;
        c.setDirty();
        return c.next;
    }

    static void add(Case k) {
        Crime c = of();
        c.cases.put(k.id, k);
        c.setDirty();
    }

    @Nullable
    static Case get(int id) {
        return of().cases.get(id);
    }

    /** A town's cases, the latest first. */
    static List<Case> cases(UUID village) {
        List<Case> out = new ArrayList<>();
        for (Case k : of().cases.values()) if (k.village.equals(village)) out.add(k);
        out.sort((a, b) -> Integer.compare(b.id, a.id));
        return out;
    }

    /** A town's open cases, the oldest first. */
    static List<Case> open(UUID village) {
        List<Case> out = new ArrayList<>();
        for (Case k : of().cases.values()) if (k.village.equals(village) && k.stage.open()) out.add(k);
        out.sort((a, b) -> Integer.compare(a.id, b.id));
        return out;
    }

    /** A folk's own record (made the first time it is asked for). */
    static CompoundTag folk(UUID id) {
        Crime c = of();
        String key = id.toString();
        if (!c.folk.contains(key, Tag.TAG_COMPOUND)) c.folk.put(key, new CompoundTag());
        return c.folk.getCompound(key);
    }

    /** Is there anything on a folk's record? */
    static boolean known(UUID id) {
        return of().folk.contains(id.toString(), Tag.TAG_COMPOUND);
    }

    static int convictions(UUID id) {
        return known(id) ? folk(id).getInt("convictions") : 0;
    }

    static boolean reformed(UUID id) {
        return known(id) && folk(id).getLong("reformed") > 0;
    }

    /** A town's own tallies. */
    static CompoundTag town(UUID village) {
        Crime c = of();
        String key = village.toString();
        if (!c.towns.contains(key, Tag.TAG_COMPOUND)) c.towns.put(key, new CompoundTag());
        return c.towns.getCompound(key);
    }

    /** A deed put off, and by what ("the watch", "the lamps", "eyes"): the books count them. */
    static void deterred(UUID village, long day, String why) {
        CompoundTag t = town(village);
        ListTag l = t.getList("deterred", Tag.TAG_COMPOUND);
        CompoundTag one = new CompoundTag();
        one.putLong("day", day);
        one.putString("why", why);
        l.add(one);
        while (l.size() > 64) l.remove(0);
        t.put("deterred", l);
        changed();
    }

    /** The deeds put off in the last so many days, by what put them off. */
    static Map<String, Integer> deterredSince(UUID village, long since) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Tag x : town(village).getList("deterred", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) x;
            if (one.getLong("day") >= since) out.merge(one.getString("why"), 1, Integer::sum);
        }
        return out;
    }

    /** Old closed cases let go, a town's books kept to a readable length. */
    static void prune(UUID village, long day) {
        Crime c = of();
        List<Case> mine = cases(village);
        int kept = 0;
        for (Case k : mine) {
            kept++;
            if (k.stage.open()) continue;
            if (kept > KEEP_MOST || k.closedDay >= 0 && day - k.closedDay > KEEP_DAYS) c.cases.remove(k.id);
        }
    }

    // ------------------------------------------------------------------ pace

    /** The tests (and the pictures' stage) have the watch and the court move without the long pauses of a real day. */
    static volatile boolean hurry;

    public static void hurryForTests(boolean on) {
        hurry = on;
    }

    /** A pause, shortened when hurried. */
    static int pause(int ticks) {
        return hurry ? Math.max(10, ticks / 4) : ticks;
    }

    // ------------------------------------------------------------------ the round of the towns

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 == 11) {
            com.jrpetty.mcassistant.Guard.run("footprints", () -> {
                for (ServerLevel level : event.getServer().getAllLevels()) footprints(level);
            });
            com.jrpetty.mcassistant.Guard.run("the court's sittings", () -> {
                for (ServerLevel level : event.getServer().getAllLevels()) Trial.sittings(level);
            });
        }
        if (tick % EVERY != 59) return;
        com.jrpetty.mcassistant.Guard.run("crime and the watch", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    tick(level, v);
                }
            }
        });
    }

    /** One look at a town: the morning's temptations and debts, the deeds noticed, the watch's cases, the court. */
    public static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getDayTime(), day = now / 24000L, t = now % 24000L;
        UUID id = v.id();
        CompoundTag town = town(id);
        if (t >= 1000L && (!town.contains("morning") || town.getLong("morning") != day)) {
            town.putLong("morning", day);            // kept with the world: a restart does not roll the day's temptations twice
            changed();
            com.jrpetty.mcassistant.Guard.run("the day's temptations", () -> Mischief.daily(level, v, day));
            com.jrpetty.mcassistant.Guard.run("debts paid back", () -> Trial.debts(level, v, day));
            prune(id, day);
        }
        com.jrpetty.mcassistant.Guard.run("plans", () -> Mischief.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("deeds noticed", () -> notice(level, v));
        com.jrpetty.mcassistant.Guard.run("the watch's cases", () -> Inquiry.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("the court", () -> Trial.tick(level, v));
    }

    /**
     * The deeds nobody has noticed yet, noticed when their time comes: the victim finds its purse light, its
     * chest short, the window broken; the storekeeper the stores short; the rancher a beast gone. A witness who
     * saw it plainly and has nobody to shield goes to the watch at once.
     */
    static void notice(ServerLevel level, Villages.Village v) {
        long now = level.getDayTime(), day = now / 24000L;
        for (Case c : open(v.id())) {
            if (c.stage != Stage.UNNOTICED) continue;
            String by = null;
            if (now >= c.noticeAt) by = c.victim != null ? c.victimName : null;
            if (by == null && now < c.noticeAt) {
                // A plain sight of it, and nothing to keep it quiet for: told at once.
                for (Witness w : c.witnesses) {
                    if (w.player || w.thinks == null || w.certainty < 60) continue;
                    VillageFolkEntity f = Civics.find(level, w.id);
                    if (f == null || f.isSleeping()) continue;
                    if (Inquiry.covers(f, c, w)) continue;
                    by = w.name;
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I saw something I didn't like. The watch should hear of it.",
                        "Somebody fetch the watch — I saw what happened."));
                    break;
                }
            }
            if (by == null && now >= c.noticeAt) by = c.victim == null ? "the town" : c.victimName;
            if (by == null) continue;
            report(level, v, c, by);
        }
    }

    /** A case reported: on the board, in the chronicle, and the watch's to look into. */
    static void report(ServerLevel level, Villages.Village v, Case c, String by) {
        if (c.stage != Stage.UNNOTICED) return;
        long day = level.getDayTime() / 24000L;
        c.stage = Stage.REPORTED;
        c.reportedDay = day;
        c.progressDay = day;
        c.reportedBy = by;
        c.note(day, "Reported by " + by + ": " + c.what() + (c.day != day ? ", done on day " + c.day : "") + ".");
        VillageFolkEntity victim = c.victim == null ? null : Civics.find(level, c.victim);
        if (victim != null) {
            victim.persona().remember(day, "I was robbed: " + c.what() + " on day " + c.day, 5);
            CompoundTag r = folk(victim.getUUID());
            r.putLong("robbedDay", day);
            r.putInt("robbedCase", c.id);
            FolkTalk.speak(victim, switch (c.kind) {
                case PICKPOCKET -> FolkTalk.pick(victim.getRandom(), "My purse! Somebody's had " + c.coins + (c.coins == 1 ? " coin" : " coins") + " out of it!",
                    "I've been robbed — my purse is light!", "Thief! My coins have gone!");
                case BURGLARY -> FolkTalk.pick(victim.getRandom(), "Somebody's been in my chest! " + Mischief.capital(c.goods) + ", gone!",
                    "My things — somebody's been in the house!");
                case VANDALISM -> FolkTalk.pick(victim.getRandom(), "Who did this? Look at it!", "Vandals! Somebody's broken it!");
                default -> FolkTalk.pick(victim.getRandom(), "We've been robbed!", "Somebody's been at it in the night.");
            });
            victim.refreshMood();
        }
        Villages.tell(v.id(), day, switch (c.kind) {
            case PICKPOCKET -> c.victimName + "'s purse was picked at " + c.place + ": " + c.coins + (c.coins == 1 ? " coin" : " coins") + " gone";
            case BURGLARY -> c.victimName + "'s house was robbed: " + c.goods + " taken";
            case STORES -> "the stores were robbed: " + c.goods + " taken";
            case VANDALISM -> c.brokeWhat + " was broken at " + c.place;
            case POACHING -> "a beast was taken from the pen";
            case FORGERY -> "a forged coin turned up in the market's takings";
            case SMUGGLING -> "the stores' books came up short: " + c.goods + " missing";
        } + "; the watch is looking into it");
        changed();
    }

    // ------------------------------------------------------------------ the footprints

    /** Muddy footprints from the scene of a deed the watch has not yet read, for anybody to see, till the rain takes them. */
    static void footprints(ServerLevel level) {
        if (level.players().isEmpty()) return;
        long day = level.getDayTime() / 24000L;
        for (Case c : of().cases.values()) {
            if (!c.stage.open() || c.trailRead || c.trailWashed || c.trail.isEmpty() || day - c.day > 2) continue;
            Villages.Village v = Villages.get(c.village);
            if (v == null || !v.dim().equals(level.dimension())) continue;
            if (level.isRaining() && level.canSeeSky(c.where.above())) {
                c.trailWashed = true;
                changed();
                continue;
            }
            DustParticleOptions mud = new DustParticleOptions(new Vector3f(0.36F, 0.25F, 0.13F), 0.9F);
            for (BlockPos p : c.trail) {
                level.sendParticles(mud, p.getX() + 0.5, p.getY() + 0.06, p.getZ() + 0.5, 2, 0.18, 0.0, 0.18, 0.0);
            }
        }
    }

    // ------------------------------------------------------------------ the folk's part

    private record Held(String doing, int tick, boolean law) {}

    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();

    /**
     * From the folk's tick (VillageFolkEntity.aiStep), every few ticks: a folk sentenced (in the stocks, at its
     * community work) or called to a trial; a guard on a case; a folk up to no good. True while one of them has it;
     * its own day waits.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (f.isShowcase() || f.isHired() || f.ownerId() == null || !f.isAlive()) return release(f);
        String doing = Trial.hold(f, level);
        boolean law = doing != null;
        if (doing == null) {
            doing = Inquiry.hold(f, level);
            law = doing != null;
        }
        if (doing == null) doing = Mischief.hold(f, level);
        if (doing == null) return release(f);
        HELD.put(f.getUUID(), new Held(doing, f.tickCount, law));
        f.hobbyNow = doing;
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    /** Is the law (or mischief) keeping this folk busy just now (between hold's looks)? */
    public static boolean busy(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        return h != null && f.tickCount >= h.tick() && f.tickCount - h.tick() <= 8;
    }

    /**
     * Called away from its work (and its bed) by the law: on a case, at a trial, in the stocks, at its community work; or
     * about its own wicked business, which waits for nothing.
     */
    public static boolean calledAway(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        return h != null && f.tickCount >= h.tick() && f.tickCount - h.tick() <= 40;
    }

    /** The law (or mischief) takes a folk in hand: whatever it had queued up to do is dropped, the first time. */
    static void takeOver(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        if (h == null || f.tickCount < h.tick() || f.tickCount - h.tick() > 40) f.clearQueue();
    }

    private static boolean release(VillageFolkEntity f) {
        HELD.remove(f.getUUID());
        return false;
    }

    /** What the law has it doing, for its card and "What are you up to?" (a culprit's errand is its own secret), or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        if (h == null || !h.law() || f.tickCount - h.tick() > 40) return null;
        return h.doing();
    }

    /** Sat in the stocks (the park leaves it sat). */
    public static boolean inStocks(VillageFolkEntity f) {
        return Trial.seated(f);
    }

    // ------------------------------------------------------------------ its spirits

    /** Its spirits (VillageFolkEntity.refreshMood): robbed, paid back, shamed before the town, wrongly accused and cleared. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        if (!known(f.getUUID())) return m;
        CompoundTag r = folk(f.getUUID());
        long robbed = r.contains("robbedDay") ? r.getLong("robbedDay") : -100, repaid = r.contains("repaidDay") ? r.getLong("repaidDay") : -100;
        if (day - robbed <= 2 && repaid < robbed) { m -= 8; why.add(new Object[]{ "robbed", 9 }); }
        if (day - repaid <= 1) { m += 4; why.add(new Object[]{ "repaid", 5 }); }
        long shamed = r.contains("shamedDay") ? r.getLong("shamedDay") : -100;
        if (day - shamed <= 2) { m -= 10; why.add(new Object[]{ "shamed", 10 }); }
        long cleared = r.contains("clearedDay") ? r.getLong("clearedDay") : -100;
        if (day - cleared <= 2) { m -= 4; why.add(new Object[]{ "cleared", 6 }); }
        return m;
    }

    /** How it puts it, asked how it is (FolkTalk.reason). */
    public static String moodWords(VillageFolkEntity f, String why) {
        CompoundTag r = folk(f.getUUID());
        Case c = get(r.getInt("robbedCase"));
        return switch (why) {
            case "robbed" -> c == null ? "I was robbed, and I can't stop thinking about it." : "Somebody took " + c.what().replace(f.displayNameCap() + "'s", "my")
                + ". I won't rest till the watch has them.";
            case "repaid" -> "I was paid back what was taken. The council saw me right.";
            case "shamed" -> FolkTalk.pick(f.getRandom(), "Everybody knows what I did. I can't look them in the eye.",
                "I'm ashamed of myself, if you must know.");
            case "cleared" -> "They accused me of something I never did. Cleared, but it rankles.";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ where the player sees it

    /** Its card (FolkTalk.card): the law's business with it. */
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isShowcase()) return "";
        List<String> parts = new ArrayList<>();
        String now = doing(f);
        if (now != null) parts.add(Mischief.capital(now));
        if (Inquiry.isConstable(f)) parts.add("the town's constable");
        Case mine = Inquiry.caseOf(f);
        if (mine != null && now == null) parts.add("on the case of the " + mine.title().toLowerCase(Locale.ROOT));
        if (known(f.getUUID())) {
            CompoundTag r = folk(f.getUUID());
            int conv = r.getInt("convictions");
            if (conv > 0) parts.add("convicted " + (conv == 1 ? "once" : conv + " times") + ", last of " + r.getString("lastCrime") + " (day "
                + r.getLong("lastConvicted") + ")");
            if (r.getInt("owes") > 0) parts.add("owes " + r.getInt("owes") + " to " + r.getString("owesToName"));
            if (r.getInt("owesTown") > 0) parts.add("owes the town " + r.getInt("owesTown"));
            if (r.getLong("reformed") > 0) parts.add("turned over a new leaf (day " + r.getLong("reformed") + ")");
            if (r.contains("clearedDay")) parts.add("wrongly accused and cleared (day " + r.getLong("clearedDay") + ")");
            Case robbed = get(r.getInt("robbedCase"));
            if (robbed != null && robbed.village.equals(village)) {
                parts.add("robbed on day " + robbed.day + (robbed.stage == Stage.CONVICTED ? ", and the culprit caught" : robbed.stage.open()
                    ? ", the watch on it" : ", never solved"));
            }
            int solved = r.getInt("solved");
            if (solved > 0) parts.add("solved " + solved + (solved == 1 ? " case" : " cases"));
        }
        return String.join("; ", parts);
    }

    /** The board's lines: the open cases, today's trial, who is in the stocks, and the month's crime. */
    public static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        int shown = 0;
        for (Case c : open(village)) {
            if (c.stage == Stage.UNNOTICED) continue;
            if (c.stage == Stage.ACCUSED || c.stage == Stage.TRIAL) {
                out.add("RB|" + (c.stage == Stage.TRIAL ? "Before the council now: " : "To be tried: ") + c.accusedName + ", for " + c.kind.word + " at "
                    + c.place + ".");
                continue;
            }
            if (shown++ >= 2) continue;
            out.add("RW|" + c.title() + ": " + c.what() + ". " + (c.investigator == null ? "Reported to the watch." : c.investigatorName
                + " of the watch is looking into it" + (c.stage == Stage.QUESTIONING ? ", asking questions" : c.stage == Stage.SEARCHING ? ", searching" : "") + "."));
        }
        String stocks = Trial.boardLine(level, village);
        if (stocks != null) out.add("RM|" + stocks);
        int[] month = month(village, day);
        if (month[0] > 0) {
            Map<String, Integer> off = deterredSince(village, day - 27);
            int put = 0;
            for (int n : off.values()) put += n;
            out.add("RM|Crime this month: " + month[0] + " (" + month[1] + " solved)" + (put > 0 ? "; " + put + " more put off by the watch and the lamps" : "") + ".");
        }
        return out;
    }

    /** The last 28 days: crimes reported, solved, unsolved. */
    static int[] month(UUID village, long day) {
        int n = 0, solved = 0, unsolved = 0;
        for (Case c : cases(village)) {
            if (c.stage == Stage.UNNOTICED || day - c.day > 27) continue;
            n++;
            if (c.stage == Stage.CONVICTED) solved++;
            else if (c.stage == Stage.UNSOLVED) unsolved++;
        }
        return new int[]{ n, solved, unsolved };
    }

    /** The gazette's section (Gazette.issueOf): yesterday's crimes and verdicts, the open cases and the month. */
    @Nullable
    public static String gazette(ServerLevel level, UUID village, long day) {
        List<String> items = new ArrayList<>();
        for (Case c : cases(village)) {
            if (c.stage == Stage.UNNOTICED) continue;
            if (c.reportedDay == day - 1) items.add(c.title() + ": " + c.what() + ".");
            if (c.closedDay == day - 1 && !c.verdict.isEmpty()) items.add(Mischief.capital(c.verdict) + ".");
        }
        int open = 0;
        for (Case c : open(village)) if (c.stage != Stage.UNNOTICED) open++;
        int[] month = month(village, day);
        if (items.isEmpty() && open == 0 && month[0] == 0) return null;
        StringBuilder sb = new StringBuilder("§lThe watch and the court§r");
        for (int i = 0; i < Math.min(4, items.size()); i++) sb.append('\n').append(items.get(i));
        if (open > 0) sb.append('\n').append(open).append(open == 1 ? " case" : " cases").append(" open. Seen anything? Tell the watch.");
        sb.append("\nThis month: ").append(month[0]).append(month[0] == 1 ? " crime, " : " crimes, ").append(month[1]).append(" solved.");
        return sb.toString();
    }

    /** The crier's appeal (Crier.script): anybody who saw the open case's deed, come forward. */
    public static List<String> crierLines(ServerLevel level, Villages.Village v, long day) {
        List<String> out = new ArrayList<>();
        for (Case c : open(v.id())) {
            if (!c.stage.investigating() && c.stage != Stage.REPORTED) continue;
            out.add("The watch asks: did anybody see who " + c.kind.did + (c.kind == Kind.VANDALISM ? " " + c.brokeWhat : "") + " at " + c.place
                + " on day " + c.day + "? Speak to " + (c.investigator == null ? "the watch" : c.investigatorName) + "!");
            break;
        }
        for (Case c : cases(v.id())) {
            if (c.closedDay == day && c.stage == Stage.CONVICTED) {
                out.add("By the council's judgement: " + c.accusedName + ", guilty of " + c.kind.word + ". " + Mischief.capital(c.sentence) + ".");
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the town's books

    /** The Cases page of the town's books (client/CasesPage): the crime rate, what keeps it down, and the case files. */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        CompoundTag out = new CompoundTag();
        int[] month = month(id, day);
        int head = Math.max(1, Villages.headcount(id));
        out.putInt("month", month[0]);
        out.putInt("solved", month[1]);
        out.putInt("unsolved", month[2]);
        out.putInt("rate", Math.round(month[0] * 100.0F / head));
        int[] weeks = new int[8], solvedWeeks = new int[8], offWeeks = new int[8];
        for (Case c : cases(id)) {
            if (c.stage == Stage.UNNOTICED) continue;
            int w = (int) ((day - c.day) / 7);
            if (w < 0 || w >= 8) continue;
            weeks[7 - w]++;
            if (c.stage == Stage.CONVICTED) solvedWeeks[7 - w]++;
        }
        for (Tag x : town(id).getList("deterred", Tag.TAG_COMPOUND)) {
            int w = (int) ((day - ((CompoundTag) x).getLong("day")) / 7);
            if (w >= 0 && w < 8) offWeeks[7 - w]++;
        }
        out.put("weeks", new IntArrayTag(weeks));
        out.put("solved_weeks", new IntArrayTag(solvedWeeks));
        out.put("off_weeks", new IntArrayTag(offWeeks));
        ListTag keeps = new ListTag();
        for (String s : Inquiry.keeps(level, v)) keeps.add(StringTag.valueOf(s));
        out.put("keeps", keeps);
        VillageFolkEntity constable = Inquiry.constable(id);
        out.putString("constable", constable != null ? constable.displayNameCap() + " is the town's constable: every case is the constable's."
            : Villages.ageOf(id).ordinal() >= Villages.Age.IRON.ordinal() ? "No constable: the town has no watch to take one from."
            : "A guard takes each case. In the Iron Age the town names a constable.");
        ListTag list = new ListTag();
        int n = 0;
        for (Case c : cases(id)) {
            if (c.stage == Stage.UNNOTICED) continue;
            if (n++ >= 16) break;
            list.add(file(level, c));
        }
        out.put("cases", list);
        return out;
    }

    /** One case file: what was done, the clues, the witnesses, the suspects and the detective's notes. */
    static CompoundTag file(ServerLevel level, Case c) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", c.id);
        t.putString("title", c.title());
        t.putLong("day", c.day);
        t.putString("hour", hour(c.hour));
        t.putString("stage", c.stage.name());
        t.putString("status", c.stage.words);
        t.putString("what", Mischief.capital(c.what()));
        t.putString("victim", c.victimName);
        t.putString("investigator", c.investigatorName);
        t.putString("accused", c.accusedName);
        t.putString("verdict", c.verdict);
        t.putString("sentence", c.sentence);
        ListTag clues = new ListTag();
        if (c.stage != Stage.REPORTED) clues.add(StringTag.valueOf("The hour: " + hour(c.hour) + " on day " + c.day + "."));
        for (Clue k : c.clues) clues.add(StringTag.valueOf(k.text));
        t.put("clues", clues);
        ListTag wit = new ListTag();
        for (Statement s : c.statements) wit.add(StringTag.valueOf(s.fromName + (s.player ? " (a player)" : "") + ": \"" + s.text + "\""
            + (s.askedBy.isEmpty() ? "" : " — asked by " + s.askedBy)));
        t.put("witnesses", wit);
        ListTag sus = new ListTag();
        if (c.stage.ordinal() >= Stage.SEARCHING.ordinal()) {
            List<Suspect> ranked = Inquiry.ranked(c);
            for (int i = 0; i < Math.min(5, ranked.size()); i++) {
                Suspect s = ranked.get(i);
                if (s.weight < 0.75) break;
                sus.add(StringTag.valueOf(s.name + " — " + String.format(Locale.ROOT, "%.1f", s.weight) + (s.why.isEmpty() ? "" : ": " + String.join(", ", s.why))));
            }
        }
        for (UUID u : c.cleared) {
            Near n = c.nearOf(u);
            sus.add(StringTag.valueOf((n != null ? n.name : "one accused") + " — cleared by the council"));
        }
        t.put("suspects", sus);
        ListTag notes = new ListTag();
        for (String s : c.notes) notes.add(StringTag.valueOf(s));
        t.put("notes", notes);
        if (!c.helperNames.isEmpty()) t.putString("helpers", String.join(", ", c.helperNames));
        return t;
    }

    /** "mid-morning", "after dark". */
    static String hour(long t) {
        t = Math.floorMod(t, 24000L);
        if (t < 1000) return "first light";
        if (t < 4000) return "early morning";
        if (t < 6000) return "mid-morning";
        if (t < 7000) return "around noon";
        if (t < 10000) return "the afternoon";
        if (t < 12000) return "early evening";
        if (t < 13500) return "dusk";
        if (t < 18000) return "after dark";
        return "the dead of night";
    }

    // ------------------------------------------------------------------ talk

    /**
     * "Seen anything amiss?" A guard: the case it is on, and what a player can do; handed what was dropped at the
     * scene, it takes it in. A witness: what it saw, by its honesty and what it thinks of the player and of the
     * culprit; that goes to the watch as if the watch had asked. A player who saw a deed and names who did it is
     * believed. The culprit, asked, protests too much.
     */
    public static String talk(VillageFolkEntity f, ServerPlayer p, String text) {
        UUID village = f.ownerId();
        if (village == null) return "I'm not from round here.";
        ServerLevel level = p.serverLevel();
        Villages.Village v = Villages.get(village);
        if (v == null) return "Amiss? Not that I know of.";
        long day = level.getDayTime() / 24000L;
        boolean watch = f.stationTask() == AssistantEntity.StationTask.GUARD || f.getUUID().equals(Villages.elder(village));
        // Handing in what was dropped at a scene.
        ItemStack held = p.getItemInHand(InteractionHand.MAIN_HAND);
        int clueOf = clueCase(held);
        if (clueOf > 0 && watch) {
            Case c = get(clueOf);
            if (c != null && c.village.equals(village)) {
                String said = Inquiry.handIn(level, c, f, p, held);
                listeners(l -> l.helped(p, village, c.id, "brought in what was dropped"));
                return said;
            }
        }
        String lower = " " + text.toLowerCase(Locale.ROOT) + " ";
        // A player who names somebody.
        VillageFolkEntity named = FolkTalk.mentioned(f, lower.trim());
        if (named != null && (lower.contains(" saw ") || lower.contains(" it was ") || lower.contains(" did it") || lower.contains(" stole")
                || lower.contains(" broke") || lower.contains(" thief") || lower.contains(" took "))) {
            String said = Inquiry.playerReport(level, v, f, p, named);
            if (said != null) return said;
        }
        // A witness, or one who was about.
        for (Case c : open(village)) {
            boolean involved = c.witness(f.getUUID()) != null || c.nearOf(f.getUUID()) != null || f.getUUID().equals(c.victim);
            if (!involved || f.getUUID().equals(c.investigator)) continue;
            // Before anybody has noticed, only one who saw it (or did it) has anything to say about it.
            if (c.stage == Stage.UNNOTICED && c.witness(f.getUUID()) == null && !f.getUUID().equals(c.culprit)) continue;
            String said = Inquiry.answerPlayer(level, v, c, f, p);
            if (said != null) return said;
        }
        if (watch) return Inquiry.guardTalk(level, v, f, p);
        // What the town is saying about it.
        List<Case> mine = cases(village);
        for (Case c : mine) {
            if (c.stage == Stage.UNNOTICED) continue;
            if (c.stage == Stage.CONVICTED && day - c.closedDay <= 5) {
                return FolkTalk.pick(f.getRandom(), "Did you hear? " + c.accusedName + " was had up before the council for " + c.kind.word + ". " + Mischief.capital(c.sentence) + ".",
                    "It was " + c.accusedName + " all along, at " + c.place + ". Who'd have thought it?");
            }
            if (c.stage.open()) {
                return "There's been " + c.kind.word + " — " + c.what() + ". " + (c.investigator == null ? "The watch hasn't got to it yet."
                    : c.investigatorName + " is looking into it.") + " I didn't see anything myself.";
            }
        }
        int[] month = month(village, day);
        return month[0] == 0 ? FolkTalk.pick(f.getRandom(), "Amiss? Not round here. It's a decent town.", "Nothing that I've seen. The watch keeps the streets quiet.")
            : "Not that I saw. There's been " + month[0] + (month[0] == 1 ? " bit" : " bits") + " of trouble this month, mind.";
    }

    /** What the town whispers (FolkTalk.gossipFor): a thief about, who was had up before the council, who sat in the stocks. */
    public static List<String> gossip(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        UUID village = f.ownerId();
        if (village == null) return out;
        long day = f.level().getDayTime() / 24000L;
        for (Case c : cases(village)) {
            if (c.stage == Stage.UNNOTICED || day - c.day > 6) continue;
            if (c.stage.open() && !f.getUUID().equals(c.culprit)) {
                out.add(c.kind == Kind.VANDALISM ? "Somebody's been breaking things — " + c.brokeWhat + " at " + c.place + ". Who'd do a thing like that?"
                    : "There's a thief about. Keep your purse close" + (c.kind == Kind.PICKPOCKET ? ", 'specially at " + c.place : "") + ".");
            } else if (c.stage == Stage.CONVICTED && c.accused != null && !f.getUUID().equals(c.accused)) {
                out.add(c.sentence.contains("stocks") ? c.accusedName + " sat in the stocks for it. Couldn't look anybody in the eye."
                    : "It was " + c.accusedName + " all along, at " + c.place + ". I'd never have thought it.");
            } else if (c.stage == Stage.ACQUITTED && c.cleared.size() > 0) {
                out.add("They had somebody up before the council over " + c.place + ", and the council cleared them. Whoever did it is still about.");
            }
            if (out.size() >= 2) break;
        }
        return out;
    }

    /** The case a thing was dropped at, or 0. */
    static int clueCase(ItemStack s) {
        if (s.isEmpty()) return 0;
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? 0 : d.copyTag().getInt(CLUE);
    }

    /** The case a thing was stolen in, or 0. */
    static int stolenCase(ItemStack s) {
        if (s.isEmpty()) return 0;
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? 0 : d.copyTag().getInt(STOLEN);
    }

    /** A thing marked (or unmarked, with 0) as this case's. */
    static void mark(ItemStack s, String key, int caseId) {
        if (s.isEmpty()) return;
        CompoundTag t = s.has(DataComponents.CUSTOM_DATA) ? s.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        if (caseId <= 0) t.remove(key);
        else t.putInt(key, caseId);
        if (t.isEmpty()) s.remove(DataComponents.CUSTOM_DATA);
        else s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
    }

    // ------------------------------------------------------------------ the seam for the quests

    /**
     * A case the watch would welcome help with: for a quest board (Quests) to offer "help the watch solve the
     * theft at the market". What is wanted says what a player can do.
     */
    public record Wanted(int caseId, String title, String what, BlockPos where, long day, String investigator, String wanted) {}

    /** Somebody to be told when a player helps with a case, and when a case is closed (solved or not). */
    public interface Listener {
        default void helped(ServerPlayer p, UUID village, int caseId, String how) {}
        default void closed(UUID village, int caseId, boolean solved, List<UUID> helpers) {}
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    /** Listen for help and for cases closed (a quest's reward is the quests' business). */
    public static void listen(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    static void listeners(java.util.function.Consumer<Listener> call) {
        for (Listener l : LISTENERS) {
            try {
                call.accept(l);
            } catch (RuntimeException e) {
                com.mojang.logging.LogUtils.getLogger().warn("[MCA-CRIME] a listener failed", e);
            }
        }
    }

    /** The open cases a player could help with, and how. */
    public static List<Wanted> helpWanted(UUID village) {
        List<Wanted> out = new ArrayList<>();
        for (Case c : open(village)) {
            if (c.stage == Stage.UNNOTICED || c.stage == Stage.ACCUSED || c.stage == Stage.TRIAL) continue;
            List<String> ways = new ArrayList<>();
            int unasked = 0;
            for (Near n : c.near) if (c.statementFrom(n.id) == null && !n.id.equals(c.investigator)) unasked++;
            if (unasked > 0) ways.add("ask the " + unasked + " who were about what they saw");
            if (c.droppedEntity != null && !c.droppedFound) ways.add("find what was dropped at the scene");
            if (!c.trailRead && !c.trailWashed && !c.trail.isEmpty()) ways.add("follow the footprints");
            ways.add("tell " + (c.investigator == null ? "the watch" : c.investigatorName) + " what you know");
            out.add(new Wanted(c.id, c.title(), c.what(), c.where, c.day, c.investigatorName, String.join(", ", ways)));
        }
        return out;
    }

    /** A case closed: the listeners told. */
    static void closed(Case c, boolean solved) {
        List<UUID> helpers = List.copyOf(c.helpers);
        listeners(l -> l.closed(c.village, c.id, solved, helpers));
    }

    // ------------------------------------------------------------------ the commands

    /**
     * /village crime: the town's casebook. {@code books} opens the town's books at the Cases page. Operators:
     * {@code now} sends the town's most tempted folk off to do something about it now; {@code stage} has a purse
     * picked at once among the folk at hand, in front of whoever is about, and the watch on it at the hurry; {@code
     * try} has the council sit on the oldest accused case now; {@code stocks} puts up the stocks and sits whoever was
     * sentenced to them.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("crime")
            .executes(ctx -> say(ctx, v -> String.join("\n", page(ctx.getSource().getLevel(), v))))
            .then(Commands.literal("books").executes(Crime::cmdBooks))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> "CRIME " + Mischief.tempt(ctx.getSource().getLevel(), v))))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> String.join("\n", CrimeStage.scene(ctx.getSource().getLevel(), v,
                    BlockPos.containing(ctx.getSource().getPosition()))))))
            .then(Commands.literal("try").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> String.join("\n", CrimeStage.court(ctx.getSource().getLevel(), v)))))
            .then(Commands.literal("stocks").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> String.join("\n", CrimeStage.stocks(ctx.getSource().getLevel(), v)))));
    }

    /** The casebook in lines: the month, what keeps crime down, and each case. */
    static List<String> page(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        int[] month = month(v.id(), day);
        out.add("Crime in " + Villages.name(v.id()) + ": " + month[0] + " in the last 28 days, " + month[1] + " solved, " + month[2] + " given up.");
        out.addAll(Inquiry.keeps(level, v));
        for (Case c : cases(v.id())) {
            if (out.size() > 30) break;
            if (c.stage == Stage.UNNOTICED) continue;
            out.add("#" + c.id + " day " + c.day + ", " + c.title() + ": " + c.what() + ". " + Mischief.capital(c.stage.words)
                + (c.investigator == null ? "" : "; " + c.investigatorName + " on it") + (c.accused == null ? "" : "; accused " + c.accusedName)
                + (c.verdict.isEmpty() ? "" : "; " + c.verdict) + ".");
        }
        int unnoticed = 0;
        for (Case c : open(v.id())) if (c.stage == Stage.UNNOTICED) unnoticed++;
        if (unnoticed > 0) out.add("(" + unnoticed + " not yet noticed.)");
        return out;
    }

    private static int cmdBooks(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", page(ctx.getSource().getLevel(), v))), false);
            return 1;
        }
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Cases");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, java.util.function.Function<Villages.Village, String> what) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        String out = what.apply(v);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    /** Words to a player, in grey: something it saw for itself. */
    static void tellPlayer(Player p, String text) {
        p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    // ------------------------------------------------------------------ the tests

    /** Honesty set for a test (the rest of the time it is the folk's own nature and record). */
    static final Map<UUID, Integer> HONESTY_FOR_TESTS = new HashMap<>();

    public static void honestyForTests(VillageFolkEntity f, @Nullable Integer honesty) {
        if (honesty == null) HONESTY_FOR_TESTS.remove(f.getUUID());
        else HONESTY_FOR_TESTS.put(f.getUUID(), honesty);
    }

    public static void tickForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
    }

    /** The day's temptations rolled for this town now, as if it were that day's morning. Returns the plans made. */
    public static int dailyForTests(ServerLevel level, Villages.Village v, long day) {
        return Mischief.daily(level, v, day);
    }

    public static int temptationForTests(ServerLevel level, VillageFolkEntity f) {
        return Mischief.temptation(level, f);
    }

    public static int honestyOfForTests(VillageFolkEntity f) {
        return Mischief.honesty(f);
    }

    /** A plan set for this folk now, to be done as soon as it can (not waiting for its free time). */
    public static boolean planForTests(ServerLevel level, VillageFolkEntity f, Kind kind, @Nullable VillageFolkEntity victim) {
        return Mischief.force(level, f, kind, victim, null);
    }

    public static boolean planningForTests(VillageFolkEntity f) {
        return Mischief.planning(f);
    }

    public static int plansForTests(UUID village) {
        return Mischief.plans(village);
    }

    /** The deed done at once where the culprit stands (the same deed as a plan's, without the walk). The case, or null. */
    @Nullable
    public static Case commitForTests(ServerLevel level, VillageFolkEntity f, Kind kind, @Nullable VillageFolkEntity victim, @Nullable BlockPos target) {
        return Mischief.commitNow(level, f, kind, victim, target);
    }

    public static List<Case> casesForTests(UUID village) {
        return cases(village);
    }

    @Nullable
    public static Case caseForTests(int id) {
        return get(id);
    }

    public static void reportForTests(ServerLevel level, Case c) {
        Villages.Village v = Villages.get(c.village);
        if (v != null) report(level, v, c, c.victim == null ? "the town" : c.victimName);
    }

    /** The case put to the council now, against this folk, whatever the watch found. */
    public static void accuseForTests(Case c, VillageFolkEntity f) {
        c.accused = f.getUUID();
        c.accusedName = f.displayNameCap();
        c.stage = Stage.ACCUSED;
        changed();
    }

    /** The watch's case finished at once (the scene read, everybody about asked, the likeliest searched) and somebody named, or not. */
    public static void investigateNowForTests(ServerLevel level, Case c) {
        Villages.Village v = Villages.get(c.village);
        if (v != null) Inquiry.finishNow(level, v, c);
    }

    /** The court sat now on the oldest accused case. True if it sits. */
    public static boolean trialNowForTests(ServerLevel level, Villages.Village v) {
        return Trial.openNow(level, v) != null;
    }

    public static boolean sittingForTests(UUID village) {
        return Trial.sitting(village) != null;
    }

    /** A record set for a test: so many convictions already. */
    public static void recordForTests(VillageFolkEntity f, int convictions) {
        folk(f.getUUID()).putInt("convictions", convictions);
        changed();
    }

    /** What the folk is serving ("stocks", "work" or ""). */
    public static String sentenceForTests(VillageFolkEntity f) {
        return known(f.getUUID()) ? folk(f.getUUID()).getString("sentence") : "";
    }

    public static boolean clearedForTests(VillageFolkEntity f) {
        return known(f.getUUID()) && folk(f.getUUID()).contains("clearedDay");
    }

    public static boolean repaidForTests(VillageFolkEntity f) {
        return known(f.getUUID()) && folk(f.getUUID()).contains("repaidDay");
    }

    public static boolean mendedForTests(Case c) {
        return c.mended;
    }

    public static List<String> suspectsForTests(Case c) {
        List<String> out = new ArrayList<>();
        for (Suspect s : Inquiry.ranked(c)) out.add(s.name + " " + String.format(Locale.ROOT, "%.1f", s.weight) + " " + s.why);
        return out;
    }

    public static boolean inStocksForTests(VillageFolkEntity f) {
        return Trial.seated(f);
    }

    @Nullable
    public static BlockPos stocksForTests(ServerLevel level, Villages.Village v) {
        return Trial.stocksAt(level, v);
    }

    public static int convictionsForTests(VillageFolkEntity f) {
        return convictions(f.getUUID());
    }

    public static int owesForTests(VillageFolkEntity f) {
        return known(f.getUUID()) ? folk(f.getUUID()).getInt("owes") + folk(f.getUUID()).getInt("owesTown") : 0;
    }

    @Nullable
    public static Vec3 sitForTests(ServerLevel level, Villages.Village v) {
        BlockPos at = Trial.stocksAt(level, v);
        return at == null ? null : Trial.sitSpot(level, at);
    }
}
