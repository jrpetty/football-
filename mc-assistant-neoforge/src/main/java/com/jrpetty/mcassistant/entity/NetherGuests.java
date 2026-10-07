package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [nether] Players and the Nether runners.
 *
 * <ul>
 * <li><b>Asking</b>: "bring us blaze rods" (wart, quartz, glowstone, soul sand, pearls, obsidian, magma cream): that need
 *     goes to the head of the next run's plan (NetherPlan.needs), and the run says whose ask it went on.</li>
 * <li><b>Going along</b> ("can I come through with you?"): booked for the next run; the team waits for the player before
 *     the gateway of a morning (waitFor), and on the far side for it to come through after them, then goes with it: the
 *     leader waits for the player as for one of the team. By agreement its part of the haul goes to the town, or ("a
 *     share") an equal share of what the team brings home is kept for it at the storehouse, handed over when it next asks
 *     a runner.</li>
 * <li><b>The chart</b> ("sell me your chart"): a copy of the runners' chart for a few coins: a map of the Nether round the
 *     outpost with their finds marked (it fills in as you walk it), and a book of where each lies, in the Nether and at
 *     home.</li>
 * <li><b>Watching a barter</b>: a player along is told when the leader throws a piglin its gold, and what came back.</li>
 * </ul>
 */
public final class NetherGuests {

    private NetherGuests() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** How long a player's ask stands, in days; the hour the team gives up waiting for a player at the gateway. */
    static final long ASK_DAYS = 5, WAIT_UNTIL = NetherRunners.MORNING_TO - 300;
    /** What a copy of the chart costs a player. */
    public static final int CHART_PRICE = 8;

    public static void resetForTests() {
        // Everything is kept with the town (Ledger), which the tests reset.
    }

    // ------------------------------------------------------------------ talk

    /** The things a player can ask the runners for, by the words for them, and the plan's name for each. */
    private static final String[][] ASKS = {
        { "blaze", "blaze rods" }, { "wart", "nether wart" }, { "quartz", "quartz" }, { "glowstone", "glowstone dust" },
        { "soul sand", "soul sand" }, { "pearl", "ender pearls" }, { "obsidian", "obsidian" }, { "magma", "magma cream" } };

    /** Is a line said to a runner for it (FolkTalk.answer): an ask, going along, the chart, a share, the Nether? */
    public static boolean meant(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (joining(t) || t.contains("my share") || chartAsked(t) || t.contains("nether") || t.contains("piglin") || t.contains("barter")) return true;
        return askIn(t) != null && (t.contains("bring") || t.contains("get us") || t.contains("fetch") || t.contains("find"));
    }

    static boolean joining(String t) {
        if (t.contains("with me")) return false;
        return t.contains("come along") || t.contains("come through") || t.contains("come with you") || t.contains("go with you")
            || t.contains("take me") || t.contains("join you") || t.contains("join the runners");
    }

    static boolean chartAsked(String t) {
        return (t.contains("chart") || t.contains("map")) && (t.contains("buy") || t.contains("sell") || t.contains("copy") || t.contains("your"));
    }

    @Nullable
    static String askIn(String t) {
        for (String[] a : ASKS) if (t.contains(a[0])) return a[1];
        return null;
    }

    /** What a runner says to a player about the Nether: a share handed over, the chart, going along, an ask, the report. */
    public static String talk(VillageFolkEntity f, Player p, String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String owed = handOver(f, p);
        if (owed != null && (t.isEmpty() || t.contains("share") || t.contains("owe"))) return owed;
        String first = owed == null ? "" : owed + " ";
        if (chartAsked(t)) return first + sellChart(f, p);
        if (joining(t)) return first + join(f, p, t.contains("share"));
        String what = askIn(t);
        if (what != null && (t.contains("bring") || t.contains("get us") || t.contains("fetch") || t.contains("find"))) return first + ask(f, p, what);
        if (t.contains("what's it like") || t.contains("what is it like") || t.contains("down there") || t.contains("through there")) {
            return first + FolkTalk.pick(f.getRandom(), "Hot as a forge, and the ceiling's a hundred blocks up. Ghasts crying somewhere above you. You never stop listening.",
                "Like walking into an oven — and everything in it wants you dead, but the piglins, if you've gold on.",
                "Red. Everything's red, and the lava's everywhere. But the quartz! Walls of it.");
        }
        if (t.contains("piglin") || t.contains("barter")) {
            return first + "Wear gold — a charm will do — and never strike one. Throw a piglin an ingot and it'll throw you something back: pearls, obsidian, "
                + "string, sometimes a potion. Come through with us and watch.";
        }
        return first + NetherRunners.tell(f);
    }

    // ------------------------------------------------------------------ asks

    /** "Bring us blaze rods": that need to the head of the next run's plan. */
    static String ask(VillageFolkEntity f, Player p, String what) {
        UUID village = f.ownerId();
        if (village == null || NetherRunners.runners(village).isEmpty()) return "There are no Nether runners here to send.";
        long day = f.level().getDayTime() / 24000L;
        Ledger.note(village, "nether.ask", what + "|" + p.getName().getString().replace('|', '/') + "|" + day);
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 1);
        LOG.info("[MCA-NETHER] {} asked the runners of {} for {}", p.getName().getString(), Villages.name(village), what);
        return capital(what) + "? Next run, first thing on the list. " + (what.equals("blaze rods") ? "That means the fortress — and fire resistance." : "");
    }

    /** The thing a player has asked the runners for, while the ask stands (NetherPlan.needs), or null. */
    @Nullable
    static String askedItem(ServerLevel level, UUID village) {
        String[] a = asked(village, level.getDayTime() / 24000L);
        return a == null ? null : a[0];
    }

    /** {what, by, day} of the ask standing, or null. */
    @Nullable
    static String[] asked(UUID village, long day) {
        String s = Ledger.note(village, "nether.ask");
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|", -1);
        if (p.length < 3) return null;
        try {
            if (day - Long.parseLong(p[2]) > ASK_DAYS) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        return p;
    }

    // ------------------------------------------------------------------ going along

    /** A player booked to go through with the runners: who, from what day, and whether a share of the haul is its. */
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

    @Nullable
    public static Guest guest(@Nullable UUID village) {
        if (village == null) return null;
        String s = Ledger.note(village, "nether.guest");
        return s == null || s.isEmpty() ? null : Guest.decode(s);
    }

    /** "Can I come through with you?": booked for the next run, if the runners will have it. */
    static String join(VillageFolkEntity f, Player p, boolean share) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level) || NetherRunners.runners(village).isEmpty()) {
            return "There are no runners here to go with.";
        }
        long day = level.getDayTime() / 24000L;
        if (Laws.banished(village, p.getUUID(), day) || Standing.of(village, p.getUUID(), level.getGameTime()).title() == Standing.Title.OUTCAST) {
            return "Through the gateway with you at my back? Not likely.";
        }
        if (f.persona().affinity(p.getUUID()) < 5) return "Into the Nether with somebody I hardly know? Talk to me a while first.";
        Guest had = guest(village);
        if (had != null && !had.player().equals(p.getUUID())) return "We've " + had.name() + " coming with us already. Another time.";
        long time = level.getDayTime() % 24000L;
        long from = time < NetherRunners.MORNING_FROM ? day : day + 1;
        Ledger.note(village, "nether.guest", new Guest(p.getUUID(), p.getName().getString(), from, share).encode());
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 2);
        f.persona().remember(day, p.getName().getString() + " asked to come through the gateway with us", 2);
        LOG.info("[MCA-NETHER] {} booked to go with the runners of {} from day {} ({})", p.getName().getString(), Villages.name(village), from + 1,
            share ? "a share" : "for the town");
        return "Come through with us, then! Be at the gateway in the morning" + (from > day ? " tomorrow" : "") + " — we'll wait a while, not all morning. "
            + (share ? "A share of what we bring home is yours, same as any of us." : "What we bring home goes to the town, mind: that's the work.")
            + " Wear gold, bring a bow, and drink your fire resistance before you need it.";
    }

    /** A runner with a player booked and not at the gateway yet waits for it there till the morning is half gone. */
    static boolean waitFor(ServerLevel level, VillageFolkEntity f, UUID village, long day, long time, BlockPos at) {
        Guest g = guest(village);
        if (g == null || g.day() > day) return false;
        if (time >= WAIT_UNTIL || g.day() < day - 1) {
            Ledger.note(village, "nether.guest", "");
            FolkTalk.speak(f, "No sign of " + g.name() + ". We'll go without — another time.");
            return false;
        }
        Player p = level.getPlayerByUUID(g.player());
        if (p != null && p.level() == level && p.blockPosition().distSqr(at) <= 16 * 16) return false;
        if (f.blockPosition().distSqr(at) > 9 && f.getNavigation().isDone()) f.walkTo(at, 0.9D);
        f.hobbyNow = "waiting at the gateway for " + g.name();
        if (f.getRandom().nextInt(160) == 0) FolkTalk.speak(f, "Where's " + g.name() + " got to? We'll give it a little longer.");
        return true;
    }

    /** The run set out: the booked player goes with it, if it is there; and the ask it goes on, if a player asked. */
    static void setOut(ServerLevel level, NetherRuns.Run r, VillageFolkEntity lead) {
        String[] a = asked(r.village, r.day);
        if (a != null) {
            r.asked = "as " + a[1] + " asked, for " + a[0];
            Ledger.note(r.village, "nether.ask", "");
        }
        Guest g = guest(r.village);
        if (g == null || g.day() > r.day) return;
        Player pl = level.getPlayerByUUID(g.player());
        if (pl == null || pl.distanceToSqr(lead) > 24 * 24) return;
        r.guest = g.player();
        r.guestName = g.name();
        r.guestShare = g.share();
        Ledger.note(r.village, "nether.guest", "");
        FolkTalk.speak(lead, g.name() + ", with us! Through after me, stay in the outpost till we're all there, and don't strike a piglin.");
        pl.sendSystemMessage(Component.literal("You're with the Nether runners: follow " + lead.displayNameCap() + " through the gateway. "
            + (g.share() ? "An equal share of the haul is yours." : "What the runners bring home goes to the town.")).withStyle(ChatFormatting.GOLD));
        LOG.info("[MCA-NETHER] {} set out with the runners of {}", g.name(), Villages.name(r.village));
    }

    /** The player going with the runners, if it is still with them (in the world, alive, in the Nether with them), or null. */
    @Nullable
    static Player with(ServerLevel level, NetherRuns.Run r) {
        if (r.guest == null) return null;
        Player pl = level.getServer().getPlayerList().getPlayer(r.guest);
        return pl != null && pl.isAlive() ? pl : null;
    }

    /**
     * Through, the leader waits on the far side for the player to come through after them (a minute), and while they
     * work, for it as for one of the team (a while, then back for it; after a minute on without it). True while it waits.
     */
    static boolean waitForPlayer(ServerLevel level, VillageFolkEntity lead, NetherRuns.Run r) {
        Player pl = with(level, r);
        if (pl == null) {
            if (r.guest != null && level.getServer().getPlayerList().getPlayer(r.guest) == null) r.guest = null;
            return false;
        }
        long now = level.getGameTime();
        boolean through = pl.level().dimension() == Level.NETHER;
        if (through && pl.distanceToSqr(lead) <= 18 * 18) {
            r.guestWait = -1;
            return false;
        }
        if (r.guestWait < 0) r.guestWait = now;
        if (now - r.guestWait > 1200) {
            FolkTalk.speak(lead, through ? "We'll go on — " + r.guestName + " knows the way to the portal." : "No " + r.guestName + ". On without, then.");
            r.guest = null;
            return false;
        }
        if (through && now - r.guestWait > 300) {
            if (lead.getNavigation().isDone()) lead.getNavigation().moveTo(pl, 1.0D);
        } else {
            lead.getNavigation().stop();
            if (through) lead.getLookControl().setLookAt(pl, 30.0F, 30.0F);
        }
        lead.hobbyNow = "waiting for " + r.guestName + (through ? "" : " to come through");
        return true;
    }

    /** A barter thrown with a player along and near: it is told, to watch. */
    static void watching(ServerLevel level, NetherRuns.Run r, VillageFolkEntity f, Piglin p) {
        Player pl = with(level, r);
        if (pl == null || pl.level() != level || pl.distanceToSqr(p) > 24 * 24) return;
        pl.displayClientMessage(Component.literal(f.displayNameCap() + " throws the piglin a gold ingot. Watch it turn it over...")
            .withStyle(ChatFormatting.GOLD), true);
    }

    /**
     * The run home with a player who went along: a share of what went into the storehouse kept for it (by agreement),
     * else the team's thanks. Said in the chronicle either way.
     */
    static String home(ServerLevel level, NetherRuns.Run r, long day) {
        if (r.guestName.isEmpty()) return "";
        UUID village = r.village;
        Villages.Village v = Villages.get(village);
        for (UUID u : r.members) {
            VillageFolkEntity m = NetherRuns.find(level.getServer(), village, u);
            if (m != null && r.guest != null) {
                m.persona().feelFor(r.guest, r.guestName, r.guestShare ? 4 : 8);
                m.persona().remember(day, r.guestName + " came through the gateway with us", 3);
            }
        }
        if (!r.guestShare || v == null || r.guest == null) {
            Villages.tell(village, day, r.guestName + " went through the gateway with the Nether runners, and what came home went to the town");
            return r.guestName;
        }
        int heads = r.members.size() + 1;
        Map<Item, Integer> share = new LinkedHashMap<>();
        ServerLevel home = level.getServer().getLevel(v.dim());
        for (Map.Entry<Item, Integer> e : r.storedItems.entrySet()) {
            int n = e.getValue() / heads;
            if (n <= 0 || home == null) continue;
            Item it = e.getKey();
            if (Crafts.take(home, v, s -> s.is(it), n)) share.put(it, n);
        }
        if (share.isEmpty()) return r.guestName;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Item, Integer> e : share.entrySet()) {
            sb.append(sb.length() == 0 ? "" : ",").append(BuiltInRegistries.ITEM.getKey(e.getKey())).append('*').append(e.getValue());
        }
        String key = "nether.owed/" + r.guest;
        String had = Ledger.note(village, key);
        Ledger.note(village, key, had == null || had.isEmpty() ? sb.toString() : had + "," + sb);
        Player pl = level.getServer().getPlayerList().getPlayer(r.guest);
        if (pl != null) pl.sendSystemMessage(Component.literal("Your share of the runners' haul is kept for you: ask any of them for it.").withStyle(ChatFormatting.GOLD));
        Villages.tell(village, day, r.guestName + " went through the gateway with the Nether runners, and has a share of the haul kept for it");
        return r.guestName;
    }

    /** Tests: this player along on the run, for a share or for the town. */
    public static void alongForTests(NetherRuns.Run r, Player p, boolean share) {
        r.guest = p.getUUID();
        r.guestName = p.getName().getString();
        r.guestShare = share;
    }

    /** A share kept for this player handed over, if there is one. What the runner says, or null. */
    @Nullable
    static String handOver(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return null;
        String key = "nether.owed/" + p.getUUID();
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
                int total = n;
                while (n > 0) {
                    int k = Math.min(n, it.getDefaultMaxStackSize());
                    ItemStack st = new ItemStack(it, k);
                    if (!p.getInventory().add(st)) p.drop(st, false);
                    n -= k;
                }
                got.add(Bench.words(it, total));
            } catch (RuntimeException ignored) {
                // an unreadable line: left out
            }
        }
        Ledger.note(village, key, "");
        return got.isEmpty() ? null : "Here's your share of the haul: " + String.join(", ", got) + ". You earned it down there.";
    }

    // ------------------------------------------------------------------ the chart

    /**
     * A copy of the runners' chart for a player: a map of the Nether round the outpost with what they have found marked
     * on it (it fills in as the player walks it), on nine of the stores' paper, and with a book out of the stores, the
     * chart's book: each find, where it lies in the Nether and over it at home. Paid for in coin.
     */
    public static String sellChart(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "A chart of what? There's no town here.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "A chart of what? There's no town here.";
        if (f.stationTask() != StationTask.NETHER) return "The chart's the Nether runners' to copy. Ask one of them, by the gateway.";
        NetherOutpost.Room room = NetherOutpost.room(village);
        if (room == null) return "We've nothing worth charting yet. Give us a run through the gateway first.";
        int coins = Market.coinsHeld(p);
        if (coins < CHART_PRICE) return "A copy of the chart's " + CHART_PRICE + " coins — the paper's the town's and the knowing's ours. You've " + coins + ".";
        if (Crafts.stock(level, v, s -> s.is(Items.PAPER)) < 9 || !Crafts.take(level, v, s -> s.is(Items.PAPER), 9)) {
            return "I'd copy it gladly, but there's not paper enough in the stores. Nine sheets a chart.";
        }
        Market.payOut(p, CHART_PRICE);
        f.earn(CHART_PRICE);
        long day = level.getDayTime() / 24000L;
        ItemStack map = chartMap(level, village, room, "The Nether, by the runners of " + Villages.name(village),
            "Copied by " + f.displayNameCap() + " of the Nether runners, day " + (day + 1));
        if (!p.getInventory().add(map)) p.drop(map, false);
        boolean book = Crafts.take(level, v, s -> s.is(Items.BOOK), 1);
        if (book) {
            ItemStack b = chartBook(village, f.displayNameCap());
            if (!p.getInventory().add(b)) p.drop(b, false);
        }
        f.persona().remember(day, "I copied " + p.getName().getString() + " the runners' chart", 1);
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 2);
        LOG.info("[MCA-NETHER] {} sold {} the runners' chart", f.displayNameCap(), p.getName().getString());
        return "Here — the outpost, and everything we've found round it, marked." + (book ? " The book says where each lies, and over it at home." : "")
            + " One block there is eight here. Wear gold.";
    }

    /** The chart's map: the Nether round the outpost, the finds marked. */
    static ItemStack chartMap(ServerLevel level, UUID village, NetherOutpost.Room room, String name, String lore) {
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        ServerLevel at = nether != null ? nether : level;
        byte scale = 1;
        int cx = room.portal().getX(), cz = room.portal().getZ();
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", Level.NETHER.location().toString());
        tag.putInt("xCenter", cx);
        tag.putInt("zCenter", cz);
        tag.putByte("scale", scale);
        tag.putBoolean("trackingPosition", true);
        tag.putBoolean("unlimitedTracking", false);
        tag.putBoolean("locked", false);
        tag.putByteArray("colors", new byte[128 * 128]);
        tag.put("banners", new ListTag());
        tag.put("frames", new ListTag());
        MapItemSavedData data = MapItemSavedData.load(tag, at.registryAccess());
        MapId id = at.getFreeMapId();
        at.setMapData(id, data);
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, id);
        map.set(DataComponents.ITEM_NAME, Component.literal(name));
        MapItemSavedData.addTargetDecoration(map, room.portal(), "outpost", MapDecorationTypes.PLAYER_OFF_MAP);
        int half = 64 << scale, i = 0;
        for (NetherRuns.Find x : NetherRuns.report(village)) {
            var type = switch (x.kind()) {
                case FORTRESS -> MapDecorationTypes.RED_X;
                case BASTION -> MapDecorationTypes.TARGET_X;
                case SPAWNER -> MapDecorationTypes.RED_MARKER;
                case DEBRIS -> MapDecorationTypes.BLUE_MARKER;
                case LOST -> MapDecorationTypes.TARGET_POINT;
                default -> null;
            };
            if (type == null || Math.abs(x.at().getX() - cx) > half || Math.abs(x.at().getZ() - cz) > half) continue;
            MapItemSavedData.addTargetDecoration(map, x.at(), "nether" + (i++), type);
        }
        map.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore).withStyle(ChatFormatting.GRAY),
            Component.literal(i + (i == 1 ? " find" : " finds") + " of the runners marked; the outpost at the centre").withStyle(ChatFormatting.GRAY))));
        return map;
    }

    /** The chart's book: NetherOutpost.chart's pages, signed by the runner who copied it. */
    static ItemStack chartBook(UUID village, String by) {
        ItemStack b = new ItemStack(Items.WRITTEN_BOOK);
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String page : NetherOutpost.chart(village)) pages.add(Filterable.passThrough(Component.literal(page)));
        b.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("The Runners' Chart"), by, 0, pages, true));
        return b;
    }

    /** Tests: the chart's book, its pages in a line. */
    public static String chartBookForTests(UUID village) {
        return String.join(" | ", NetherOutpost.chart(village));
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
