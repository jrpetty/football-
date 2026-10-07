package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.world.entity.player.Player;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a player may ask of a village in plain words (not the crew's request board, {@link Requests}): a folk to take up another trade ("could
 * you be a miner?"), the elder to put a building up next ("build a market"). Asked of a folk
 * that thinks well of you (or by a citizen), for something the village has a use for; once a
 * day each.
 */
public final class Asks {

    private Asks() {}

    /** The trades by the words a player would use. */
    static final Map<String, StationTask> TRADES = new LinkedHashMap<>();
    static {
        for (String w : new String[]{ "farmer", "farming", "farm" }) TRADES.put(w, StationTask.FARM);
        for (String w : new String[]{ "woodcutter", "lumberjack", "forester", "woodsman" }) TRADES.put(w, StationTask.WOOD);
        for (String w : new String[]{ "miner", "mining" }) TRADES.put(w, StationTask.MINE);
        for (String w : new String[]{ "rancher", "herder", "shepherd", "herdsman" }) TRADES.put(w, StationTask.RANCH);
        for (String w : new String[]{ "guard", "watchman", "the watch" }) TRADES.put(w, StationTask.GUARD);
        for (String w : new String[]{ "smelter" }) TRADES.put(w, StationTask.SMELT);
        for (String w : new String[]{ "fisher", "fisherman", "fishing" }) TRADES.put(w, StationTask.FISH);
        for (String w : new String[]{ "storekeeper" }) TRADES.put(w, StationTask.STORE);
        for (String w : new String[]{ "carrier", "hauler", "porter" }) TRADES.put(w, StationTask.HAUL);
        for (String w : new String[]{ "blacksmith", "smith" }) TRADES.put(w, StationTask.SMITH);
        for (String w : new String[]{ "tailor", "weaver" }) TRADES.put(w, StationTask.TAILOR);
        for (String w : new String[]{ "beekeeper" }) TRADES.put(w, StationTask.BEEKEEP);
        for (String w : new String[]{ "brewer" }) TRADES.put(w, StationTask.BREW);
        for (String w : new String[]{ "enchanter" }) TRADES.put(w, StationTask.ENCHANT);
        for (String w : new String[]{ "cook", "baker" }) TRADES.put(w, StationTask.COOK);
        for (String w : new String[]{ "shopkeeper" }) TRADES.put(w, StationTask.SHOP);
    }

    /** The buildings the elder may be asked for (the crafts' amenities are the council's). */
    static final Map<String, String> BUILDINGS = new LinkedHashMap<>();
    static {
        BUILDINGS.put("storehouse", "storage");
        BUILDINGS.put("shelter", "shelter");
        BUILDINGS.put("house", "house");
        BUILDINGS.put("well", "well");
        BUILDINGS.put("wall", "fortify");
        BUILDINGS.put("smeltery", "smeltery");
        BUILDINGS.put("meeting hall", "hall");
        BUILDINGS.put("town hall", "townhall");
        BUILDINGS.put("leader's hall", "townhall");
        BUILDINGS.put("elder's hall", "townhall");
        BUILDINGS.put("courtyard", "court");
        BUILDINGS.put("hall", "hall");
        BUILDINGS.put("workshop", "workshop");
        BUILDINGS.put("watchtower", "watchtower");
        BUILDINGS.put("market", "market");
        BUILDINGS.put("pen", "pen");
        BUILDINGS.put("lighthouse", "lighthouse");
        BUILDINGS.put("chapel", "chapel");
        BUILDINGS.put("gateway", "gateway");
        BUILDINGS.put("granary", "granary");
        BUILDINGS.put("barracks", "barracks");
        BUILDINGS.put("monument", "monument");
        BUILDINGS.put("museum", "museum");
        BUILDINGS.put("infirmary", "infirmary");          // [batchA] (Infirmary)
        BUILDINGS.put("hospital", "infirmary");
        BUILDINGS.put("lodge", "lodge");                  // [caves] the Delvers' Lodge (Lodge)
        BUILDINGS.put("delvers", "lodge");
        BUILDINGS.put("map room", "maproom");             // [cartographer] the map room (Cartographers)
        BUILDINGS.put("maproom", "maproom");
        BUILDINGS.put("cartographer", "maproom");
        BUILDINGS.put("trading", "tradingpost");          // [emerald] the Trading Post (EmeraldTrader)
        BUILDINGS.put("tradingpost", "tradingpost");
        BUILDINGS.put("diver", "divershed");              // [diver] the diver's shed (Divers)
        BUILDINGS.put("kelp", "divershed");
    }

    /** The trade named in a line, or null. */
    public static StationTask tradeNamed(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        for (Map.Entry<String, StationTask> e : TRADES.entrySet()) if (t.contains(" " + e.getKey() + " ")
                || t.contains(" " + e.getKey() + "s ") || t.contains(" " + e.getKey() + "?") || t.contains(" " + e.getKey() + ".")) return e.getValue();
        return null;
    }

    /** The building named in a line, or null. */
    public static String buildingNamed(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        for (Map.Entry<String, String> e : BUILDINGS.entrySet()) if (t.contains(" " + e.getKey())) return e.getValue();
        return null;
    }

    private static final Map<UUID, Long> RETRADED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ASKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        RETRADED.clear();
        ASKED.clear();
    }

    /** Does this folk listen to this player on its village's business? */
    private static boolean listens(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return false;
        if (Ledger.citizens(village).containsKey(p.getUUID())) return f.persona().affinity(p.getUUID()) >= 0;
        return f.persona().affinity(p.getUUID()) >= 20;
    }

    /** "Could you be a miner?" — a folk asked to take up another trade. */
    public static String retrade(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null) return "I've no village to work for.";
        if (f.isBaby()) return "I'm too young for a trade! Ask me when I'm grown.";
        StationTask want = tradeNamed(text);
        if (want == null) return "Take up what? A farmer, a miner, a woodcutter, a guard...?";
        StationTask mine = f.stationTask();
        if (want == mine) return "I'm a " + mine.title.toLowerCase(Locale.ROOT) + " already!";
        if (!listens(f, p)) return "I hardly know you. I'll keep to my own work, thanks.";
        if (!Villages.wants(village, want)) return "We've no call for a " + want.title.toLowerCase(Locale.ROOT) + " yet — the village is too small or too young.";
        if (!Villages.craftReady(village, want)) return "There's nowhere for a " + want.title.toLowerCase(Locale.ROOT) + " to work yet. Build it first!";
        long day = f.level().getDayTime() / 24000L;
        if (RETRADED.getOrDefault(f.getUUID(), -1L) == day) return "I've changed my work once today already. Ask me tomorrow.";
        if (mine != StationTask.NONE && !Villages.overStaffed(village, mine) && Villages.share(village, mine) < 0) {
            return "We're short of " + mine.label + " as it is. Who'd do my work if I left it?";
        }
        if (!f.takeUpTrade(want)) return "I'd do it, but I can't find any ground for it near here just now.";
        RETRADED.put(f.getUUID(), day);
        Villages.tell(village, day, f.displayNameCap() + " gave up " + mine.label + " for " + want.label + ", at " + p.getName().getString() + "'s asking");
        return "All right — a " + want.title.toLowerCase(Locale.ROOT) + " it is. Wish me luck!";
    }

    /** "Build a market next" — the elder asked to put a building up first. */
    public static String build(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null) return "I've no village to build for.";
        String what = buildingNamed(text);
        if (what == null) return "Build what? A house, a well, a wall, a market...?";
        if (!f.isElder()) {
            String elder = Villages.elderName(village);
            return "That's for the elder to say" + (elder.isEmpty() ? "." : " — ask " + elder + ".");
        }
        if (!listens(f, p)) return "I'll build what the village needs, thank you.";
        if (!Villages.projectsWanted(village).contains(what)) {
            return Villages.hasBuilt(village, what) && !what.equals("house")
                ? "We have one already." : "We've no call for that yet. In good time.";
        }
        long day = f.level().getDayTime() / 24000L;
        if (ASKED.getOrDefault(village, -1L) == day) return "I've already changed our plans once today.";
        ASKED.put(village, day);
        Villages.request(village, what);
        return "Very well — " + Villages.spoken(what) + " next. I'll tell the builders.";
    }
}
