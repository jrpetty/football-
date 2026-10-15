package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [caves] Players and the cave team.
 *
 * <ul>
 * <li><b>Asking</b> (talk): a player asks the team to look a way ("cave team, look east") or to find something ("find
 *     us diamonds"). The leader plans it into the next trip if it makes sense (a known cave that way, or out along that
 *     bearing to find one; that ore first on the list, and a cave that has it), and says so; an ore none of the team's
 *     picks (nor the stores') will take is declined, and why.</li>
 * <li><b>Going along</b> (join): a player asks to come on the next trip. The team waits for it at the lodge at dawn
 *     (or by the board with no lodge) till the morning is half gone; then it goes with the team: the leader waits for it
 *     as for any of the team, and goes back for it. By agreement the player's part of the haul goes to the town (the team
 *     thinks the better of it), or ("a share") an equal share of what the team brings up is kept for it at the
 *     storehouse, and handed over the next time it asks a cave dweller.</li>
 * <li><b>A map</b>: a copy of the team's cave map, bought at the lodge (Lodge.sellMap).</li>
 * <li><b>Quests</b>: a cave the team turned back from for the monsters in it goes up on the quest board, to be cleared
 *     (Quests.post, trouble).</li>
 * </ul>
 */
public final class CaveGuests {

    private CaveGuests() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** How long a player's ask stands, in days. */
    static final long ASK_DAYS = 5;
    /** The team waits at the lodge for a player going with it till this hour of the morning. */
    static final long WAIT_UNTIL = 3800;

    // ------------------------------------------------------------------ talk

    /** What a cave dweller says to a player about the caves: the report, or an ask, a join, a map or a share. */
    public static String talk(VillageFolkEntity f, Player p, String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        UUID village = f.ownerId();
        // A share kept for this player is handed over first, whatever else was said.
        String owed = village == null ? null : handOver(f, p);
        if (owed != null && (t.isEmpty() || t.contains("share") || t.contains("owe"))) return owed;
        String first = owed == null ? "" : owed + " ";
        if (t.contains("map")) return first + Lodge.sellMap(f, p);
        if (joining(t)) return first + join(f, p, t.contains("share"));
        String ore = oreIn(t);
        if (ore != null && (t.contains("find") || t.contains("look for") || t.contains("bring") || t.contains("dig") || t.contains("get us"))) {
            return first + askOre(f, p, ore);
        }
        int bearing = bearingIn(t);
        if (bearing >= 0 && (t.contains("look") || t.contains("explore") || t.contains("try") || t.contains("go"))) {
            return first + askWay(f, p, bearing, wayWord(t));
        }
        return first + CaveDwellers.tell(f);
    }

    /** "Can I come along?", "take me with you", "I'd like to join the team" (not "come along with me": a walk). */
    static boolean joining(String t) {
        if (t.contains("with me")) return false;
        return t.contains("come along") || t.contains("join") || t.contains("come with you") || t.contains("go with you")
            || t.contains("take me along") || t.contains("take me with") || t.contains("come caving") || t.contains("go caving")
            || t.contains("come down the caves");
    }

    /** Is a line said to one of the cave team for it (FolkTalk.answer): an ask ("look east", "find us diamonds"), going
     *  along ("can I come along?"), its map, a share? */
    public static boolean meant(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean map = t.contains("map") && (t.contains("cave") || t.contains("buy") || t.contains("copy") || t.contains("sell"));
        if (map || t.contains("my share") || joining(t)) return true;
        if (oreIn(t) != null && (t.contains("find") || t.contains("look for") || t.contains("bring") || t.contains("dig") || t.contains("get us"))) return true;
        return bearingIn(t) >= 0 && (t.contains("look") || t.contains("explore") || t.contains("try"));
    }

    /** The ore named in a line, or null. */
    @Nullable
    static String oreIn(String t) {
        String[][] words = { { "diamond", "diamond" }, { "emerald", "emerald" }, { "gold", "gold" }, { "iron", "iron" }, { "copper", "copper" },
            { "coal", "coal" }, { "redstone", "redstone" }, { "lapis", "lapis" }, { "obsidian", "obsidian" }, { "amethyst", "amethyst" } };
        for (String[] w : words) if (t.contains(w[0])) return w[1];
        return null;
    }

    private static final String[] WAYS = { "north-east", "north-west", "south-east", "south-west", "northeast", "northwest", "southeast",
        "southwest", "north", "south", "east", "west" };

    /** The way named in a line ("north-east"), or "". */
    static String wayWord(String t) {
        for (String w : WAYS) if (t.contains(w)) return w.length() == 9 && !w.contains("-") ? w.substring(0, 5) + "-" + w.substring(5) : w;
        return "";
    }

    /** The bearing (Scouts' sixteen) of the way named in a line, or -1. */
    static int bearingIn(String t) {
        String w = wayWord(t);
        if (w.isEmpty()) return -1;
        int dx = w.contains("east") ? 1 : w.contains("west") ? -1 : 0;
        int dz = w.contains("south") ? 1 : w.contains("north") ? -1 : 0;
        return Scouts.bearingOf(dx * 100, dz * 100);
    }

    // ------------------------------------------------------------------ asks

    /** A player's ask of the team: a way to look (a bearing, or -1) and an ore to find (or ""), who asked, the day. */
    public record Ask(int bearing, String way, String ore, String by, UUID player, long day) {
        String encode() {
            return bearing + "|" + way + "|" + ore + "|" + by.replace('|', '/') + "|" + player + "|" + day;
        }

        @Nullable
        static Ask decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 6) return null;
            try {
                return new Ask(Integer.parseInt(p[0]), p[1], p[2], p[3], UUID.fromString(p[4]), Long.parseLong(p[5]));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** The ask standing for the team (not yet honoured, and not stale), or null. */
    @Nullable
    public static Ask ask(@Nullable UUID village, long day) {
        if (village == null) return null;
        String s = Ledger.note(village, "caves.ask");
        Ask a = s == null || s.isEmpty() ? null : Ask.decode(s);
        return a == null || day - a.day() > ASK_DAYS ? null : a;
    }

    static void keepAsk(UUID village, Ask a) {
        Ledger.note(village, "caves.ask", a.encode());
    }

    /** The ask honoured (the trip set out on it): the way forgotten if the trip went that way; the ore stays wanted
     *  till the team is home (done). */
    static void honoured(UUID village, Ask a, boolean way) {
        Ask left = new Ask(way ? -1 : a.bearing(), way ? "" : a.way(), a.ore(), a.by(), a.player(), a.day());
        if (left.bearing() < 0 && left.ore().isEmpty()) Ledger.note(village, "caves.ask", "");
        else keepAsk(village, left);
    }

    /** The trip that went for the ore home again: the ore asked for done with (a way still to look kept). */
    static void done(UUID village) {
        String s = Ledger.note(village, "caves.ask");
        Ask a = s == null || s.isEmpty() ? null : Ask.decode(s);
        if (a == null || a.bearing() < 0) Ledger.note(village, "caves.ask", "");
        else keepAsk(village, new Ask(a.bearing(), a.way(), "", a.by(), a.player(), a.day()));
    }

    /** The ore a player has asked the team to find, while the ask stands (CaveDwellers.wantedOres), or null. */
    @Nullable
    static String askedOre(ServerLevel level, UUID village) {
        Ask a = ask(village, level.getDayTime() / 24000L);
        return a == null || a.ore().isEmpty() ? null : a.ore();
    }

    /** "Cave team, look east": into the next trip, if it makes sense. */
    static String askWay(VillageFolkEntity f, Player p, int bearing, String way) {
        UUID village = f.ownerId();
        if (village == null || CaveDwellers.dwellers(village).isEmpty()) return "There's no cave team here to send.";
        long day = f.level().getDayTime() / 24000L;
        Ask had = ask(village, day);
        keepAsk(village, new Ask(bearing, way, had == null ? "" : had.ore(), p.getName().getString(), p.getUUID(), day));
        VillageFolkEntity lead = CaveDwellers.leaderOf(village);
        String who = lead == null || lead == f ? "" : " I'll tell " + lead.displayNameCap() + " — the team goes where the leader says.";
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 1);
        LOG.info("[MCA-CAVES] {} asked the team of {} to look {}", p.getName().getString(), Villages.name(village), way);
        return capital(way) + "? We've not looked that way much. Next trip, then — " + (lead == f ? "I'll take us " + way + "." : "out " + way + ".") + who;
    }

    /** "Find us diamonds": that ore first on the list, if any pick the team has (or the stores have) will take it. */
    static String askOre(VillageFolkEntity f, Player p, String ore) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level) || CaveDwellers.dwellers(village).isEmpty()) {
            return "There's no cave team here to send.";
        }
        var st = CaveDwellers.oreState(ore);
        boolean pick = false;
        if (st != null) {
            for (VillageFolkEntity m : CaveDwellers.dwellers(village)) if (CaveDwellers.bestPick(m).isCorrectToolForDrops(st)) pick = true;
            if (!pick) pick = Market.stock(level, village, s -> CaveDwellers.isPickaxe(s) && s.isCorrectToolForDrops(st)) > 0;
        }
        if (!pick) {
            String need = "diamond".equals(ore) || "emerald".equals(ore) || "gold".equals(ore) || "redstone".equals(ore) ? "an iron pick"
                : "obsidian".equals(ore) ? "a diamond pick" : "a stone pick";
            return capital(ore) + "? Not with the picks we've got — that wants " + need + " at the least. Get the smith on it, and ask again.";
        }
        long day = level.getDayTime() / 24000L;
        Ask had = ask(village, day);
        keepAsk(village, new Ask(had == null ? -1 : had.bearing(), had == null ? "" : had.way(), ore, p.getName().getString(), p.getUUID(), day));
        BlockPos known = caveWith(village, ore);
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 1);
        LOG.info("[MCA-CAVES] {} asked the team of {} to find {}", p.getName().getString(), Villages.name(village), ore);
        return known != null ? capital(ore) + "? There's a vein of it on our list, in the cave " + Guide.direction(Villages.get(village).centre(), known)
            + ". We'll go for it first, next trip." : capital(ore) + "? We'll keep our eyes open for it, and take it first when we see it.";
    }

    /** A cave on the town's list with a vein of this ore still to take, or null. */
    @Nullable
    static BlockPos caveWith(UUID village, String ore) {
        for (CaveDwellers.Find x : CaveDwellers.report(village)) {
            if (x.kind() != CaveDwellers.Kind.CAVE && x.kind() != CaveDwellers.Kind.RAVINE) continue;
            for (CaveDwellers.Vein v : CaveDwellers.veins(village, x.at())) {
                if (v.ore().equals(ore) && ("todo".equals(v.state()) || "waiting".equals(v.state()))) return x.at();
            }
        }
        return null;
    }

    /** Does this known cave lie that way from the town (within a sixteenth either side)? */
    static boolean thatWay(BlockPos home, BlockPos cave, int bearing) {
        int b = Scouts.bearingOf(cave.getX() - home.getX(), cave.getZ() - home.getZ());
        int d = Math.floorMod(b - bearing, Scouts.BEARINGS);
        return d <= 1 || d >= Scouts.BEARINGS - 1;
    }

    // ------------------------------------------------------------------ going along

    /** A player booked to go with the team: who, from what day, and whether a share of the haul is its by agreement. */
    public record Guest(UUID player, String name, long day, boolean share) {
        String encode() {
            return player + "|" + name.replace('|', '/') + "|" + day + "|" + (share ? 1 : 0);
        }

        @Nullable
        static Guest decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 4) return null;
            try {
                return new Guest(UUID.fromString(p[0]), p[1], Long.parseLong(p[2]), "1".equals(p[3]));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** The player booked to go on the team's next trip, or null. */
    @Nullable
    public static Guest guest(@Nullable UUID village) {
        if (village == null) return null;
        String s = Ledger.note(village, "caves.guest");
        return s == null || s.isEmpty() ? null : Guest.decode(s);
    }

    /** "Can I come along?": booked for the next trip, if the team will have it. */
    static String join(VillageFolkEntity f, Player p, boolean share) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level) || CaveDwellers.dwellers(village).isEmpty()) {
            return "There's no cave team here to go with.";
        }
        long day = level.getDayTime() / 24000L;
        if (Laws.banished(village, p.getUUID(), day) || Standing.of(village, p.getUUID(), level.getGameTime()).title() == Standing.Title.OUTCAST) {
            return "Down there with you at my back? Not likely.";
        }
        if (f.persona().affinity(p.getUUID()) < 5) return "Into the dark with somebody I hardly know? Talk to me a while first.";
        Guest had = guest(village);
        if (had != null && !had.player().equals(p.getUUID())) return "We've " + had.name() + " coming with us already. Another time.";
        long time = level.getDayTime() % 24000L;
        long from = time < 1200 ? day : day + 1;                       // before the morning: today; else tomorrow
        Ledger.note(village, "caves.guest", new Guest(p.getUUID(), p.getName().getString(), from, share).encode());
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 2);
        f.persona().remember(day, p.getName().getString() + " asked to come down the caves with us", 2);
        BlockPos lodge = Lodge.hall(village);
        String where = lodge != null ? "at the lodge" : "by the board";
        LOG.info("[MCA-CAVES] {} booked to go with the cave team of {} from day {} ({})", p.getName().getString(), Villages.name(village), from + 1,
            share ? "a share" : "for the town");
        return "Come along, then! Be " + where + " at first light" + (from > day ? " tomorrow" : "") + " — we'll wait a while, not all morning. "
            + (share ? "A share of what we bring up is yours, same as any of us." : "What we bring up goes to the town, mind: that's the work.")
            + " Bring a sword and some torches of your own.";
    }

    /**
     * The morning's gathering (CaveDwellers.gathering): one of the team with a player booked to go and not here yet,
     * waits for it at this spot (the lodge, or the board) till the morning is half gone. True while it waits.
     */
    static boolean waitFor(ServerLevel level, VillageFolkEntity f, UUID village, long day, long time, BlockPos at) {
        Guest g = guest(village);
        if (g == null || g.day() > day) return false;
        if (time >= WAIT_UNTIL || g.day() < day - 1) {
            Ledger.note(village, "caves.guest", "");
            FolkTalk.speak(f, "No sign of " + g.name() + ". We'll go without — another time.");
            LOG.info("[MCA-CAVES] the cave team of {} gave up waiting for {}", Villages.name(village), g.name());
            return false;
        }
        Player p = level.getPlayerByUUID(g.player());
        if (p != null && p.blockPosition().distSqr(at) <= 16 * 16) return false;   // here: off we go
        f.hobbyNow = "waiting " + (Lodge.hall(village) != null ? "at the lodge" : "by the board") + " for " + g.name();
        if (f.getRandom().nextInt(160) == 0) FolkTalk.speak(f, "Where's " + g.name() + " got to? We'll give it a little longer.");
        return true;
    }

    /** The team set out: the booked player goes with it, if it is here. */
    static void setOut(ServerLevel level, CaveDwellers.Party p, VillageFolkEntity lead) {
        Guest g = guest(p.village);
        if (g == null || g.day() > p.day) return;
        Player pl = level.getPlayerByUUID(g.player());
        if (pl == null || pl.distanceToSqr(lead) > 24 * 24) return;
        p.guest = g.player();
        p.guestName = g.name();
        p.guestShare = g.share();
        Ledger.note(p.village, "caves.guest", "");
        FolkTalk.speak(lead, g.name() + ", with us! Keep close, keep a torch handy, and don't dig under your own feet.");
        pl.sendSystemMessage(Component.literal("You're with the cave team: keep up with " + lead.displayNameCap() + ". "
            + (g.share() ? "An equal share of the haul is yours." : "What the team brings up goes to the town.")).withStyle(ChatFormatting.GOLD));
        LOG.info("[MCA-CAVES] {} set out with the cave team of {}", g.name(), Villages.name(p.village));
    }

    /** The player going with the team, if it is still with it (in the world, alive), or null. */
    @Nullable
    static Player with(ServerLevel level, CaveDwellers.Party p) {
        if (p.guest == null) return null;
        Player pl = level.getPlayerByUUID(p.guest);
        return pl != null && pl.isAlive() && pl.level() == level ? pl : null;
    }

    /**
     * The leader waits for the player going with it as for one of the team: a while, then back for it, and after a
     * minute it goes on without it ("it knows the way home"). True while it waits.
     */
    static boolean waitForPlayer(ServerLevel level, VillageFolkEntity lead, CaveDwellers.Party p) {
        Player pl = with(level, p);
        if (pl == null) {
            if (p.guest != null && level.getPlayerByUUID(p.guest) == null) p.guest = null;      // gone from the world
            return false;
        }
        if (pl.distanceToSqr(lead) <= (CaveDwellers.WAIT + 6) * (CaveDwellers.WAIT + 6)) {
            p.guestWait = -1;
            return false;
        }
        long now = level.getGameTime();
        if (p.guestWait < 0) p.guestWait = now;
        if (now - p.guestWait > 1200) {
            FolkTalk.speak(lead, "We'll go on — " + p.guestName + " knows the way home.");
            p.guest = null;
            return false;
        }
        if (now - p.guestWait > 300) {
            if (lead.getNavigation().isDone()) lead.getNavigation().moveTo(pl, 1.0D);
        } else {
            lead.getNavigation().stop();
            lead.getLookControl().setLookAt(pl, 30.0F, 30.0F);
        }
        lead.hobbyNow = "waiting for " + p.guestName;
        return true;
    }

    /**
     * The team home with a player who went along: a share of what went into the storehouse kept for it (by agreement),
     * else the team's thanks for its help to the town. Said in the chronicle either way.
     */
    static String home(ServerLevel level, CaveDwellers.Party p, long day) {
        if (p.guestName.isEmpty()) return "";
        UUID village = p.village;
        Villages.Village v = Villages.get(village);
        for (UUID u : p.members) {
            if (level.getEntity(u) instanceof VillageFolkEntity m && p.guest != null) {
                m.persona().feelFor(p.guest, p.guestName, p.guestShare ? 4 : 8);
                m.persona().remember(day, p.guestName + " came down the caves with us", 3);
            }
        }
        if (!p.guestShare || v == null || p.guest == null) {
            Villages.tell(village, day, p.guestName + " went down the caves with the cave team, and what it brought up went to the town");
            return p.guestName;
        }
        int heads = p.members.size() + 1;
        Map<Item, Integer> share = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : p.storedItems.entrySet()) {
            int n = e.getValue() / heads;
            if (n <= 0) continue;
            Item it = e.getKey();
            if (Crafts.take(level, v, s -> s.is(it), n)) share.put(it, n);
        }
        if (share.isEmpty()) return p.guestName;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Item, Integer> e : share.entrySet()) {
            sb.append(sb.length() == 0 ? "" : ",").append(BuiltInRegistries.ITEM.getKey(e.getKey())).append('*').append(e.getValue());
        }
        String key = "caves.owed/" + p.guest;
        String had = Ledger.note(village, key);
        Ledger.note(village, key, had == null || had.isEmpty() ? sb.toString() : had + "," + sb);
        Player pl = level.getPlayerByUUID(p.guest);
        if (pl != null) {
            pl.sendSystemMessage(Component.literal("Your share of the cave team's haul is kept for you: ask any of the team for it.")
                .withStyle(ChatFormatting.GOLD));
        }
        Villages.tell(village, day, p.guestName + " went down the caves with the cave team, and has a share of the haul kept for it");
        return p.guestName;
    }

    /** Tests: the team home with this player along (for a share, or for the town), this put into the storehouse. */
    public static String homeForTests(ServerLevel level, CaveDwellers.Party p, Player player, boolean share, Map<Item, Integer> stored) {
        p.guest = player.getUUID();
        p.guestName = player.getName().getString();
        p.guestShare = share;
        p.storedItems.putAll(stored);
        return home(level, p, level.getDayTime() / 24000L);
    }

    /** A share kept for this player handed over, if there is one. What the cave dweller says, or null. */
    @Nullable
    static String handOver(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return null;
        String key = "caves.owed/" + p.getUUID();
        String s = Ledger.note(village, key);
        if (s == null || s.isEmpty()) return null;
        List<String> got = new ArrayList<>();
        for (String part : s.split(",")) {
            int star = part.lastIndexOf('*');
            if (star <= 0) continue;
            try {
                Item it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(part.substring(0, star)));
                int n = Integer.parseInt(part.substring(star + 1));
                if (it == Items.AIR || n <= 0) continue;
                while (n > 0) {
                    int k = Math.min(n, it.getDefaultMaxStackSize());
                    ItemStack st = new ItemStack(it, k);
                    if (!p.getInventory().add(st)) p.drop(st, false);
                    n -= k;
                }
                got.add(Bench.words(it, Integer.parseInt(part.substring(star + 1))));
            } catch (RuntimeException ignored) {
                // an unreadable line: left out
            }
        }
        Ledger.note(village, key, "");
        return got.isEmpty() ? null : "Here's your share of the haul: " + String.join(", ", got) + ". Earned, every bit.";
    }

    // ------------------------------------------------------------------ the quest board's caves

    /** The team turned back from a cave for the monsters in it: kept for the quest board (trouble). */
    static void troubleAt(ServerLevel level, CaveDwellers.Party p, BlockPos at) {
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (Monster m : level.getEntitiesOfClass(Monster.class, new AABB(at).inflate(16), LivingEntity::isAlive)) kinds.merge(Quests.kind(m), 1, Integer::sum);
        String mob = "monsters";
        int most = 0;
        for (Map.Entry<String, Integer> k : kinds.entrySet()) if (k.getValue() > most) { most = k.getValue(); mob = k.getKey(); }
        BlockPos key = p.caveKey != null ? p.caveKey : at;
        Villages.Village v = Villages.get(p.village);
        String where = v == null ? "the caves" : "the cave " + Guide.direction(v.centre(), key);
        Ledger.note(p.village, "caves.trouble", mob + "|" + Math.max(4, Math.min(10, most + 2)) + "|" + where + "|" + at.getX() + "|" + at.getY() + "|"
            + at.getZ() + "|" + p.day);
    }

    /**
     * For the quest board (Quests.post): a cave the team turned back from, to be cleared: {mob, count, the place in
     * words, x, y, z}; or null. Taken off once posted.
     */
    @Nullable
    public static String[] trouble(UUID village, Set<String> already) {
        String s = Ledger.note(village, "caves.trouble");
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|", -1);
        if (p.length < 6 || already.contains("clear:" + p[2])) return null;
        Ledger.note(village, "caves.trouble", "");
        return p;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
