package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [featfolk] What one folk carries of the lines that run through a town: what it has seen of the players (Witness), its
 * place in a line of masters and apprentices (Apprenticeship), whom it is courting (Courtship), and what it handed down
 * or came into (Lineage). Kept on the folk and saved with it ("FolkLines"); an older save has none of it.
 */
public final class FolkLines {

    // ------------------------------------------------------------------ what it has seen of the players

    /**
     * One thing a folk knows a player did: seen with its own eyes or done with it ({@code source} null), or told by one
     * who saw it ({@code source} the witness, its weight halved, its words the witness's own: nothing added).
     */
    public static final class Deed {
        public final UUID player;
        public String playerName;
        public final Witness.Kind kind;
        /** What it was about: a monster, a load of wheat, a poppy ("" for none). */
        public final String detail;
        /** Who it was done to or for, by name ("" for none, and the folk itself where it was done to it). */
        public final String victim;
        public long day;
        public int weight;
        @Nullable public final UUID source;
        public final String sourceName;

        Deed(UUID player, String playerName, Witness.Kind kind, String detail, String victim, long day, int weight,
             @Nullable UUID source, String sourceName) {
            this.player = player;
            this.playerName = playerName;
            this.kind = kind;
            this.detail = detail;
            this.victim = victim;
            this.day = day;
            this.weight = weight;
            this.source = source;
            this.sourceName = sourceName;
        }

        public boolean firstHand() { return source == null; }

        /** The same deed, whoever knows it: what was done, about what, to whom. */
        public boolean sameAs(Witness.Kind k, String d, String v) {
            return kind == k && detail.equals(d) && victim.equals(v);
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("P", player);
            t.putString("N", playerName);
            t.putString("K", kind.name());
            t.putString("D", detail);
            t.putString("V", victim);
            t.putLong("Day", day);
            t.putInt("W", weight);
            if (source != null) {
                t.putUUID("S", source);
                t.putString("SN", sourceName);
            }
            return t;
        }

        @Nullable
        static Deed load(CompoundTag t) {
            if (!t.hasUUID("P")) return null;
            Witness.Kind k;
            try {
                k = Witness.Kind.valueOf(t.getString("K"));
            } catch (IllegalArgumentException e) {
                return null;
            }
            return new Deed(t.getUUID("P"), t.getString("N"), k, t.getString("D"), t.getString("V"), t.getLong("Day"),
                t.getInt("W"), t.hasUUID("S") ? t.getUUID("S") : null, t.getString("SN"));
        }
    }

    final List<Deed> deeds = new ArrayList<>();

    public List<Deed> deeds() { return List.copyOf(deeds); }

    // ------------------------------------------------------------------ apprenticeship

    /** "", "apprentice", "journeyman" or "master". */
    String rank = "";
    /** How far on it is under its master, 0 to 100 (an apprentice qualifies at Apprenticeship.QUALIFY). */
    int skill;
    @Nullable UUID master;
    String masterName = "";
    /** The trade it is learning or learned (a StationTask's name), "" for none. */
    String trade = "";
    /** The knack its master passed on to it (FolkSkills.Knack's key), "" for none. */
    String taught = "";
    long qualifiedDay = -1;
    /** As a master: the ones it has brought on, by name. */
    final List<String> pupils = new ArrayList<>();

    public String rank() { return rank; }

    public int skill() { return skill; }

    public String masterName() { return masterName; }

    public String trade() { return trade; }

    public String taught() { return taught; }

    public List<String> pupils() { return List.copyOf(pupils); }

    // ------------------------------------------------------------------ courtship

    @Nullable UUID courting;
    String courtingName = "";
    int walks, gifts;
    long walkDay = -1;

    @Nullable public UUID courting() { return courting; }

    public String courtingName() { return courtingName; }

    public int walks() { return walks; }

    public int gifts() { return gifts; }

    // ------------------------------------------------------------------ lineage

    boolean retired;
    long retiredDay = -1;
    /** The trade it gave up (a StationTask's title), and to whom. */
    String retiredFrom = "", handedTo = "";
    /** What it came into: "Bramble's farm", "the house on Mill Lane from Bramble". */
    final List<String> inherited = new ArrayList<>();

    public boolean retired() { return retired; }

    public String retiredFrom() { return retiredFrom; }

    public String handedTo() { return handedTo; }

    public List<String> inherited() { return List.copyOf(inherited); }

    // ------------------------------------------------------------------ saving

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag d = new ListTag();
        for (Deed x : deeds) d.add(x.save());
        t.put("Deeds", d);
        if (!rank.isEmpty()) t.putString("Rank", rank);
        t.putInt("Skill", skill);
        if (master != null) t.putUUID("Master", master);
        t.putString("MasterName", masterName);
        t.putString("Trade", trade);
        t.putString("Taught", taught);
        t.putLong("Qualified", qualifiedDay);
        t.putString("Pupils", String.join("|", pupils));
        if (courting != null) t.putUUID("Courting", courting);
        t.putString("CourtingName", courtingName);
        t.putInt("Walks", walks);
        t.putInt("Gifts", gifts);
        t.putLong("WalkDay", walkDay);
        t.putBoolean("Retired", retired);
        t.putLong("RetiredDay", retiredDay);
        t.putString("RetiredFrom", retiredFrom);
        t.putString("HandedTo", handedTo);
        t.putString("Inherited", String.join("|", inherited));
        return t;
    }

    public void load(CompoundTag t) {
        deeds.clear();
        ListTag d = t.getList("Deeds", Tag.TAG_COMPOUND);
        for (int i = 0; i < d.size(); i++) {
            Deed x = Deed.load(d.getCompound(i));
            if (x != null) deeds.add(x);
        }
        rank = t.getString("Rank");
        skill = t.getInt("Skill");
        master = t.hasUUID("Master") ? t.getUUID("Master") : null;
        masterName = t.getString("MasterName");
        trade = t.getString("Trade");
        taught = t.getString("Taught");
        qualifiedDay = t.contains("Qualified") ? t.getLong("Qualified") : -1;
        pupils.clear();
        for (String s : t.getString("Pupils").split("\\|")) if (!s.isEmpty()) pupils.add(s);
        courting = t.hasUUID("Courting") ? t.getUUID("Courting") : null;
        courtingName = t.getString("CourtingName");
        walks = t.getInt("Walks");
        gifts = t.getInt("Gifts");
        walkDay = t.contains("WalkDay") ? t.getLong("WalkDay") : -1;
        retired = t.getBoolean("Retired");
        retiredDay = t.contains("RetiredDay") ? t.getLong("RetiredDay") : -1;
        retiredFrom = t.getString("RetiredFrom");
        handedTo = t.getString("HandedTo");
        inherited.clear();
        for (String s : t.getString("Inherited").split("\\|")) if (!s.isEmpty()) inherited.add(s);
    }

    boolean isEmpty() {
        return deeds.isEmpty() && rank.isEmpty() && courting == null && !retired && inherited.isEmpty() && pupils.isEmpty()
            && skill == 0 && trade.isEmpty();
    }
}
