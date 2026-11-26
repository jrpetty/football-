package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * [wpa] A player's belonging to a town, kept with the world (SavedData "mc_assistant_belonging"): the register, the
 * player's role-play identity, and the small things the Town Hall remembers for each player.
 * <ul>
 * <li><b>The register.</b> A player who presses "Join village" (the arrival card, the Town Hall's desk) signs the town's
 *     book: a resident from that day, its respawn point at the hall, and its name in the chronicle. Citizenship
 *     (Citizens) is the next step and needs the register.</li>
 * <li><b>The identity.</b> Unlocked by the first register, never before: a first and a last name of the player's own
 *     (not another player's, nor anyone's username), a short bio, an age, a trade and a look for the portrait. One per
 *     server, the same in every town. The nameplate over the player's head and its name in chat show it, unless the
 *     player turns the plate off. Changing it later is done at a Town Hall for a fee, and the old name is kept on record.</li>
 * <li><b>Per town:</b> the arrival card seen, the jury roll, the militia, when the papers were issued.</li>
 * <li><b>Per player:</b> the first-time hints dismissed, and the first week's steps (FirstWeek).</li>
 * </ul>
 */
public final class Belonging {

    private Belonging() {}

    /** What a new name costs after the first, at the Town Hall's desk; what replacement papers cost. */
    public static final int RENAME_FEE = 10, PAPERS_FEE = 2;
    /** The longest bio, and a name's least and most letters. */
    public static final int BIO_MAX = 120, NAME_MIN = 2, NAME_MAX = 16;

    // ------------------------------------------------------------------ kept with the world

    static final class Store extends SavedData {
        CompoundTag root = new CompoundTag();

        static Store load(CompoundTag tag, HolderLookup.Provider p) {
            Store s = new Store();
            s.root = tag.getCompound("root");
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider p) {
            tag.put("root", root.copy());
            return tag;
        }
    }

    @Nullable private static Store loose;

    static Store store() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Store();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Store::new, Store::load, null), "mc_assistant_belonging");
    }

    static void dirty() {
        store().setDirty();
    }

    static CompoundTag sub(CompoundTag t, String key) {
        if (!t.contains(key, Tag.TAG_COMPOUND)) t.put(key, new CompoundTag());
        return t.getCompound(key);
    }

    /** The player's own compound. */
    static CompoundTag me(UUID player) {
        return sub(sub(store().root, "players"), player.toString());
    }

    /** The player's compound for one town. */
    static CompoundTag town(UUID player, UUID village) {
        return sub(sub(me(player), "towns"), village.toString());
    }

    @Nullable
    static CompoundTag peekTown(UUID player, UUID village) {
        CompoundTag p = sub(store().root, "players");
        if (!p.contains(player.toString())) return null;
        CompoundTag towns = p.getCompound(player.toString()).getCompound("towns");
        return towns.contains(village.toString()) ? towns.getCompound(village.toString()) : null;
    }

    // ------------------------------------------------------------------ the register

    public static boolean registered(@Nullable UUID village, UUID player) {
        if (village == null) return false;
        CompoundTag t = peekTown(player, village);
        return t != null && t.contains("reg");
    }

    /** The day the player signed the town's book, or -1. */
    public static long registeredDay(UUID village, UUID player) {
        CompoundTag t = peekTown(player, village);
        return t == null || !t.contains("reg") ? -1 : t.getLong("reg");
    }

    /** Every town whose book the player signed. */
    public static List<UUID> townsOf(UUID player) {
        List<UUID> out = new ArrayList<>();
        CompoundTag towns = me(player).getCompound("towns");
        for (String k : towns.getAllKeys()) {
            if (!towns.getCompound(k).contains("reg")) continue;
            try { out.add(UUID.fromString(k)); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    /** Every player who signed this town's book (the residents, citizens or not). */
    public static List<UUID> residents(UUID village) {
        List<UUID> out = new ArrayList<>();
        CompoundTag players = sub(store().root, "players");
        for (String k : players.getAllKeys()) {
            CompoundTag t = players.getCompound(k).getCompound("towns").getCompound(village.toString());
            if (!t.contains("reg")) continue;
            try { out.add(UUID.fromString(k)); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    /**
     * Sign the town's book: a resident from today, the respawn point at the hall (or the heart), the chronicle told and
     * the folk a little warmer. Returns what happened, for the screen.
     */
    public static String register(ServerPlayer p, Villages.Village v) {
        UUID id = v.id();
        String town = Villages.name(id);
        if (registered(id, p.getUUID())) return "You're already in " + town + "'s book, since day " + registeredDay(id, p.getUUID()) + ".";
        ServerLevel level = p.serverLevel();
        long day = level.getDayTime() / 24000L;
        if (Laws.banished(id, p.getUUID(), day)) return "!" + town + " has banished you. Its book stays shut to you till that's over.";
        CompoundTag t = town(p.getUUID(), id);
        t.putLong("reg", day);
        me(p.getUUID()).putString("user", p.getGameProfile().getName());
        dirty();
        BlockPos home = TownHallDesk.spawnFor(level, v);
        if (home != null) p.setRespawnPosition(level.dimension(), home, p.getYRot(), true, false);
        Villages.tell(id, day, called(p) + " signed the town's book and lives in " + town + " now");
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.persona().knows(p.getUUID())) f.persona().feelFor(p.getUUID(), p.getGameProfile().getName(), 2);
        }
        Standing.stir(id, p.getUUID());
        FirstWeek.begin(p, id);
        return "Signed: you live in " + town + " now, from day " + day + ". You wake at the Town Hall if you fall, till you sleep in a bed of your own."
            + (rp(p.getUUID()) == null ? " Next: tell the town who you are." : "");
    }

    // ------------------------------------------------------------------ the identity

    /** A role-play identity: the names, the bio, the age, the trade, the look, the plate, and since when. */
    public record Rp(String first, String last, String bio, int age, String trade, String look, boolean plate, long since) {
        public String full() {
            return (first + " " + last).trim();
        }
    }

    /** The looks a portrait can take. */
    public static final String[] LOOKS = { "plain", "townsfolk", "worker", "merchant", "scholar", "traveller", "noble" };
    /** The trades a player may give, for its card (what it does, not a job it holds). */
    public static final String[] TRADES = { "", "farmer", "builder", "miner", "smith", "merchant", "cook", "carpenter", "scholar",
        "fisher", "hunter", "healer", "artist", "musician", "traveller" };

    @Nullable
    public static Rp rp(UUID player) {
        CompoundTag p = sub(store().root, "players");
        if (!p.contains(player.toString())) return null;
        CompoundTag i = p.getCompound(player.toString()).getCompound("id");
        if (!i.contains("first")) return null;
        return new Rp(i.getString("first"), i.getString("last"), i.getString("bio"), i.getInt("age"), i.getString("trade"),
            i.getString("look"), !i.getBoolean("noplate"), i.getLong("since"));
    }

    /** What the town calls the player: its role-play name if it has one, else its username. */
    public static String called(Player p) {
        Rp r = rp(p.getUUID());
        return r == null ? p.getGameProfile().getName() : r.full();
    }

    /** The first name the folk greet the player by (its username, if it has no identity). */
    public static String first(Player p) {
        Rp r = rp(p.getUUID());
        return r == null ? p.getGameProfile().getName() : r.first();
    }

    /** The name shown over the player's head and in chat: its role-play name with the plate on, else null. */
    @Nullable
    public static String plate(UUID player) {
        Rp r = rp(player);
        return r == null || !r.plate() ? null : r.full();
    }

    /** The old names, newest first ("day 12: Ada Marsh"). */
    public static List<String> formerNames(UUID player) {
        List<String> out = new ArrayList<>();
        ListTag l = me(player).getCompound("id").getList("was", Tag.TAG_STRING);
        for (int i = l.size() - 1; i >= 0; i--) out.add(l.getString(i));
        return out;
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z'\\-]*");
    private static final String[] RESERVED = { "admin", "moderator", "owner", "server", "operator", "staff", "console", "herobrine" };

    /** Why this name won't do, or null: letters, an apostrophe or a hyphen; not another player's, nor anyone's username. */
    @Nullable
    public static String whyNotName(MinecraftServer server, UUID self, String first, String last) {
        for (String n : new String[]{ first, last }) {
            if (n.length() < NAME_MIN || n.length() > NAME_MAX) return "A name is " + NAME_MIN + " to " + NAME_MAX + " letters: \"" + n + "\" isn't.";
            if (!NAME.matcher(n).matches()) return "Letters only (an apostrophe or a hyphen are fine): \"" + n + "\" won't do.";
            String low = n.toLowerCase(Locale.ROOT);
            for (String r : RESERVED) if (low.contains(r)) return "\"" + n + "\" reads like a title, not a name.";
        }
        String full = (first + " " + last).toLowerCase(Locale.ROOT);
        CompoundTag players = sub(store().root, "players");
        for (String k : players.getAllKeys()) {
            if (k.equals(self.toString())) continue;
            CompoundTag i = players.getCompound(k).getCompound("id");
            if ((i.getString("first") + " " + i.getString("last")).toLowerCase(Locale.ROOT).equals(full)) return "Somebody in these parts already goes by " + capWords(full) + ".";
        }
        if (server != null) {
            for (ServerPlayer o : server.getPlayerList().getPlayers()) {
                if (o.getUUID().equals(self)) continue;
                String u = o.getGameProfile().getName().toLowerCase(Locale.ROOT);
                if (u.equals(first.toLowerCase(Locale.ROOT)) || u.equals(last.toLowerCase(Locale.ROOT)) || u.equals(full.replace(" ", "")))
                    return "That's " + o.getGameProfile().getName() + "'s name. Pick one of your own.";
            }
        }
        // Every player the register has seen, online or not (the profile cache would make one up for any name offline).
        for (String k : players.getAllKeys()) {
            if (k.equals(self.toString())) continue;
            String u = players.getCompound(k).getString("user").toLowerCase(Locale.ROOT);
            if (u.isEmpty()) continue;
            if (u.equals(first.toLowerCase(Locale.ROOT)) || u.equals(last.toLowerCase(Locale.ROOT)) || u.equals(full.replace(" ", "")))
                return "That's another player's username. Pick a name of your own.";
        }
        return null;
    }

    /**
     * Set the player's identity (validated; a change after the first keeps the old name on record). Returns what
     * happened, "!" first for a refusal. The fee is the Town Hall's to take before calling this.
     */
    public static String setIdentity(ServerPlayer p, String first, String last, String bio, int age, String trade, String look) {
        first = tidy(first);
        last = tidy(last);
        String why = whyNotName(p.server, p.getUUID(), first, last);
        if (why != null) return "!" + why;
        bio = clean(bio);
        if (bio.length() > BIO_MAX) bio = bio.substring(0, BIO_MAX);
        CompoundTag i = sub(me(p.getUUID()), "id");
        long day = p.level().getDayTime() / 24000L;
        boolean renamed = i.contains("first") && !(i.getString("first") + " " + i.getString("last")).equals(first + " " + last);
        if (renamed) {
            ListTag was = i.getList("was", Tag.TAG_STRING);
            was.add(StringTag.valueOf("till day " + day + ": " + i.getString("first") + " " + i.getString("last")));
            while (was.size() > 8) was.remove(0);
            i.put("was", was);
        }
        boolean fresh = !i.contains("first");
        me(p.getUUID()).putString("user", p.getGameProfile().getName());
        i.putString("first", first);
        i.putString("last", last);
        i.putString("bio", bio);
        i.putInt("age", Math.max(0, Math.min(120, age)));
        i.putString("trade", trade == null ? "" : trade);
        i.putString("look", look == null || look.isEmpty() ? "plain" : look);
        if (fresh) i.putLong("since", day);
        dirty();
        p.refreshDisplayName();
        Citizens.refresh(p);
        BelongingSync.broadcast(p.server);
        if (renamed) for (UUID t : townsOf(p.getUUID())) Villages.tell(t, day, p.getGameProfile().getName() + " goes by " + first + " " + last + " now");
        return fresh ? "Written in: " + first + " " + last + ". That's who the town knows you as."
            : renamed ? "The register now says " + first + " " + last + ". Your old name stays on record." : "Your details are updated.";
    }

    /** The name over the head on or off. */
    public static String setPlate(ServerPlayer p, boolean on) {
        CompoundTag i = sub(me(p.getUUID()), "id");
        if (!i.contains("first")) return "!You've no town name yet: sign a town's book first.";
        i.putBoolean("noplate", !on);
        dirty();
        p.refreshDisplayName();
        BelongingSync.broadcast(p.server);
        return on ? "Your town name shows over your head and in chat." : "Your town name is hidden: others see your username.";
    }

    static String tidy(String s) {
        s = clean(s).replace(" ", "");
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** No formatting codes, no line breaks, trimmed. */
    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("§.", "").replace('\n', ' ').replace('\r', ' ').trim();
    }

    static String capWords(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ per town

    /** Has the player had this town's arrival card? */
    public static boolean arrived(UUID player, UUID village) {
        CompoundTag t = peekTown(player, village);
        return t != null && t.getBoolean("arrived");
    }

    public static void markArrived(UUID player, UUID village) {
        town(player, village).putBoolean("arrived", true);
        dirty();
    }

    public static boolean onJuryRoll(UUID village, UUID player) {
        CompoundTag t = peekTown(player, village);
        return t != null && t.getBoolean("jury");
    }

    public static void setJuryRoll(UUID village, UUID player, boolean on) {
        town(player, village).putBoolean("jury", on);
        dirty();
    }

    public static boolean inMilitia(UUID village, UUID player) {
        CompoundTag t = peekTown(player, village);
        return t != null && t.getBoolean("militia");
    }

    public static void setMilitia(UUID village, UUID player, boolean on) {
        town(player, village).putBoolean("militia", on);
        dirty();
    }

    /** The day the papers were last issued here, or -1. */
    public static long papersDay(UUID village, UUID player) {
        CompoundTag t = peekTown(player, village);
        return t == null || !t.contains("papers") ? -1 : t.getLong("papers");
    }

    public static void papersIssued(UUID village, UUID player, long day) {
        town(player, village).putLong("papers", day);
        dirty();
    }

    // ------------------------------------------------------------------ the player's own

    public static boolean hintSeen(UUID player, String id) {
        return me(player).getCompound("hints").getBoolean(id);
    }

    public static void dismissHint(UUID player, String id) {
        sub(me(player), "hints").putBoolean(id, true);
        dirty();
    }

    /** The first week's compound (FirstWeek). */
    static CompoundTag week(UUID player) {
        return sub(me(player), "week");
    }

    // ------------------------------------------------------------------ tests

    public static void resetForTests(UUID player) {
        sub(store().root, "players").remove(player.toString());
        dirty();
    }

    public static void resetAllForTests() {
        store().root = new CompoundTag();
        dirty();
    }

    /** Tests: saved and read back. */
    public static void roundTripForTests(ServerLevel level) {
        Store s = store();
        CompoundTag saved = s.save(new CompoundTag(), level.registryAccess());
        s.root = Store.load(saved, level.registryAccess()).root;
    }
}
