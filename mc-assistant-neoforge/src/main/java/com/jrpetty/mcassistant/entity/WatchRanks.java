package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [roles-e] The watch's ranks in peacetime, the same ranks it takes into the field (Armies): a <b>captain</b> (the
 * roster's: the constable once the town has one, else the most seasoned guard; Roster.captain) and a <b>sergeant</b>
 * for every six guards, the most seasoned of the rest.
 *
 * <ul>
 * <li><b>Drawn with the roster</b> each morning (Roster.readOrDraw), kept in the watch's books (Police's town record),
 *     so a restart keeps them; a sergeant keeps its stripes while it is on the watch and the watch is big enough, so
 *     the ranks do not shuffle from day to day. A new sergeant goes into the chronicle.</li>
 * <li><b>A pay step.</b> An office's allowance on the wage (Offices): a sergeant's, and the captain's when the captain is
 *     not the constable (who is paid as the constable).</li>
 * <li><b>A kit marker.</b> A brass armband on a sergeant's right arm, on the walls as on the beat; the captain who is
 *     not the constable (who has its coat) a brass armband on each arm (Police.flags, client/WatchLayer).</li>
 * <li><b>War.</b> When the town takes the field its sergeants are the peacetime sergeants first (Armies.raise), then
 *     the veterans of most battles; the Captain is the roster's captain as before.</li>
 * </ul>
 * Cost: drawn once a day with the roster; a look-up in the watch's books otherwise.
 */
public final class WatchRanks {

    private WatchRanks() {}

    /** A sergeant for every so many guards. */
    public static final int PER_SERGEANT = 6;

    /** The sergeants a watch of this many wants: one for every six. */
    public static int wanted(int guards) {
        return Math.max(0, guards / PER_SERGEANT);
    }

    /**
     * [Roster.readOrDraw] The day's sergeants drawn with the roster, into the watch's books: those who have their
     * stripes already first (while they are on the watch), then the most seasoned of the rest; never the captain.
     */
    static void draw(UUID village, CompoundTag t, List<VillageFolkEntity> watch, @Nullable VillageFolkEntity captain, long day) {
        Set<UUID> before = sergeants(village);
        List<VillageFolkEntity> pool = new ArrayList<>();
        for (VillageFolkEntity g : watch) if (g != captain) pool.add(g);
        pool.sort(Comparator.comparingInt((VillageFolkEntity g) -> before.contains(g.getUUID()) ? 0 : 1)
            .thenComparing(Comparator.comparingInt((VillageFolkEntity g) -> g.tradeLevel(StationTask.GUARD)).reversed())
            .thenComparing(Comparator.comparingInt((VillageFolkEntity g) -> Armies.battles(village, g.getUUID())).reversed())
            .thenComparing(g -> g.getUUID()));
        int want = Math.min(pool.size(), wanted(watch.size()));
        ListTag ids = new ListTag();
        CompoundTag names = new CompoundTag();
        for (int i = 0; i < want; i++) {
            VillageFolkEntity g = pool.get(i);
            ids.add(StringTag.valueOf(g.getStringUUID()));
            names.putString(g.getStringUUID(), g.displayNameCap());
            if (!before.contains(g.getUUID())) {
                Villages.tell(village, day, g.displayNameCap() + " was made a sergeant of the watch");
                if (g.persona().rolled()) g.persona().remember(day, "I was made a sergeant of the watch", 6);
            }
        }
        t.put("sergeants", ids);
        t.put("sergeantNames", names);
    }

    /** The watch's sergeants as last drawn (by id). */
    public static Set<UUID> sergeants(@Nullable UUID village) {
        if (village == null) return Set.of();
        ListTag l = Police.town(village).getList("sergeants", Tag.TAG_STRING);
        if (l.isEmpty()) return Set.of();
        Set<UUID> out = new LinkedHashSet<>();
        for (int i = 0; i < l.size(); i++) {
            try {
                out.add(UUID.fromString(l.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // a hand-edited book: that line left out
            }
        }
        return out;
    }

    /** Is this guard a sergeant of its town's watch? */
    public static boolean isSergeant(VillageFolkEntity g) {
        return g.stationTask() == StationTask.GUARD && sergeants(g.ownerId()).contains(g.getUUID());
    }

    /** Is this guard the watch's captain, and not the constable (who is paid and dressed as the constable)? */
    static boolean captainOnly(VillageFolkEntity g) {
        UUID id = g.ownerId();
        return id != null && g.stationTask() == StationTask.GUARD && g.getUUID().equals(Police.captainId(id)) && !Inquiry.isConstable(g);
    }

    /** The sergeants' names, for the roster's words: "Bram, Wren"; "" with none. */
    static String names(UUID village) {
        CompoundTag names = Police.town(village).getCompound("sergeantNames");
        List<String> out = new ArrayList<>();
        for (UUID s : sergeants(village)) {
            String n = names.getString(s.toString());
            out.add(n.isEmpty() ? "a guard" : n);
        }
        return String.join(", ", out);
    }

    /** " Sergeants: Bram, Wren." for the roster's words, or "". */
    static String rosterWords(UUID village) {
        String n = names(village);
        return n.isEmpty() ? "" : (sergeants(village).size() == 1 ? " Sergeant: " : " Sergeants: ") + n + ".";
    }

    /** [Armies.raise] The order the field's sergeants are picked in: the peacetime sergeants first. */
    static Comparator<Armies.Fighter> peacetimeFirst(UUID village) {
        Set<UUID> s = new HashSet<>(sergeants(village));
        return Comparator.comparingInt(f -> s.contains(f.id) ? 0 : 1);
    }

    /** Its card (Police.cardLine): its rank, if it has one. */
    static String rank(VillageFolkEntity g) {
        if (isSergeant(g)) return "a sergeant of the watch";
        return "";
    }

    // ------------------------------------------------------------------ for the tests

    /** Tests: the roster drawn afresh now (the captain and the sergeants with it); each guard's duty by name. */
    public static Map<UUID, String> drawForTests(ServerLevel level, Villages.Village v) {
        Police.town(v.id()).remove("roster");
        Roster.forget(v.id());
        Map<UUID, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<UUID, Roster.Duty> e : Roster.today(level, v).entrySet()) out.put(e.getKey(), e.getValue().name());
        return out;
    }

    /** Tests: the day's duties for a watch of n on that day, by name (Roster.duties). */
    public static List<String> dutiesForTests(ServerLevel level, Villages.Village v, int n, long day) {
        List<String> out = new ArrayList<>();
        for (Roster.Duty d : Roster.duties(level, v, n, day)) out.add(d.name());
        return out;
    }

    /** Tests: the roster for a given day, drawn (Roster.draw), by name. */
    public static Map<UUID, String> rosterOnForTests(ServerLevel level, Villages.Village v, long day) {
        Map<UUID, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<UUID, Roster.Duty> e : Roster.draw(level, v, day).entrySet()) out.put(e.getKey(), e.getValue().name());
        return out;
    }

    /** Tests: the roster's captain, and the town's constable. */
    @Nullable public static UUID captainForTests(UUID village) { return Police.captainId(village); }
    @Nullable public static VillageFolkEntity constableForTests(UUID village) { return Inquiry.constable(village); }

    /** Tests: has this guard had both kinds of duty (the walls and the streets) in the week kept? */
    public static boolean bothKindsForTests(UUID village, UUID guard) { return Roster.bothKinds(village, guard); }

    /** Tests: the case given out now (Inquiry.assign); who took it, or null. */
    @Nullable
    public static UUID assignForTests(ServerLevel level, Villages.Village v, Crime.Case c) {
        Inquiry.assign(level, v, c);
        return c.investigator;
    }

    /** Tests: these of the town's watch in the order the army picks its sergeants from them (Armies.raise): the peacetime
     *  sergeants first. */
    public static List<UUID> fieldOrderForTests(UUID village, List<UUID> ids) {
        List<Armies.Fighter> fs = new ArrayList<>();
        for (UUID u : ids) fs.add(new Armies.Fighter(u, "", village, com.jrpetty.mcassistant.village.BattleModel.Role.LINE));
        fs.sort(peacetimeFirst(village));
        List<UUID> out = new ArrayList<>();
        for (Armies.Fighter f : fs) out.add(f.id);
        return out;
    }

    /** Tests: the flags a guard's clothes are drawn by (Police.flags). */
    public static int flagsForTests(ServerLevel level, VillageFolkEntity g) { return Police.flags(level, g); }

    /** Tests: the roster's words, as the talk and the board have them. */
    public static String rosterTalkForTests(VillageFolkEntity g, net.minecraft.server.level.ServerPlayer p) {
        String s = Police.talk(g, p, "Who's on the roster today?");
        return s == null ? "" : s;
    }
}
