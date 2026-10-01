package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The village council: the elder and the four folk the village thinks most of (and, with a
 * vote each, the village's citizens). It decides which of the things the village could
 * build next (the café, the tavern, the smithy...) goes up first, and it sits in judgement
 * when a player is tried under the village's laws.
 * <ul>
 * <li>Each councillor votes for what suits it: a cook for the café, a sociable soul for the
 *     tavern, a guard for the smithy...</li>
 * <li>A player can put a building to the council — say "the village should build a tavern"
 *     to any folk. Councillors who think well of the player (gifts help) vote its way, and
 *     a citizen's vote counts for its own proposal.</li>
 * <li>The vote goes into the village's history ("the council voted 3 to 2 for a tavern, as
 *     Steve proposed").</li>
 * </ul>
 */
public final class Council {

    private Council() {}

    /** The building the council chose to go up first, by village, until it is built. */
    private static final Map<UUID, String> CHOSEN = new ConcurrentHashMap<>();
    /** What each player has put to each village's council. */
    private static final Map<UUID, Map<UUID, String>> PROPOSED = new ConcurrentHashMap<>();
    /** The last vote, in words, for the talk. */
    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();

    public static void resetForTests() {
        CHOSEN.clear();
        PROPOSED.clear();
        LAST.clear();
    }

    /** What can be put to the council, with the words a player might use. */
    static final Map<String, String[]> CHOICES = new LinkedHashMap<>();
    static {
        CHOICES.put("cafe", new String[]{ "cafe", "café", "coffee" });
        CHOICES.put("tavern", new String[]{ "tavern", "inn", "pub", "alehouse" });
        CHOICES.put("smithy", new String[]{ "smithy", "forge", "blacksmith" });
        CHOICES.put("shop", new String[]{ "shop", "store front", "general store" });
        CHOICES.put("brewery", new String[]{ "brewery", "brewhouse" });
        CHOICES.put("library", new String[]{ "library", "books" });
        CHOICES.put("graveyard", new String[]{ "graveyard", "cemetery" });
    }

    /** The councillors: the elder, then the four the village thinks most of. */
    public static List<VillageFolkEntity> members(UUID village) {
        List<VillageFolkEntity> adults = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isShowcase()) adults.add(f);
        }
        Map<UUID, Integer> esteem = new java.util.HashMap<>();
        for (VillageFolkEntity f : adults) {
            int sum = 0;
            for (VillageFolkEntity o : adults) if (o != f) sum += o.life().affinity(f.getUUID());
            esteem.put(f.getUUID(), sum + (f.isElder() ? 10000 : 0));
        }
        adults.sort(Comparator.comparingInt((VillageFolkEntity f) -> -esteem.get(f.getUUID())));
        return adults.size() > 5 ? new ArrayList<>(adults.subList(0, 5)) : adults;
    }

    /** The order the village's amenities go up in: the council's choice first. */
    public static List<String> order(UUID village, List<String> extras) {
        if (extras.size() < 2) return extras;
        String c = CHOSEN.get(village);
        if (c == null || !extras.contains(c)) {
            c = vote(village, extras);
            if (c == null) return extras;
        }
        List<String> out = new ArrayList<>(extras);
        out.remove(c);
        out.add(0, c);
        return out;
    }

    /** The council's choice was built: the next question is a new one. */
    public static void built(UUID village, String structure) {
        if (structure.equals(CHOSEN.get(village))) CHOSEN.remove(village);
        Map<UUID, String> p = PROPOSED.get(village);
        if (p != null) p.values().removeIf(structure::equals);
    }

    /** Put to the vote: which of these goes up first. Returns the choice, or null if there is no council. */
    @Nullable
    static String vote(UUID village, List<String> options) {
        List<VillageFolkEntity> council = members(village);
        if (council.isEmpty()) return null;
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (String o : options) tally.put(o, 0);
        Map<UUID, String> proposals = PROPOSED.getOrDefault(village, Map.of());
        for (VillageFolkEntity m : council) {
            String pick = preference(m, options, proposals, village);
            tally.merge(pick, 1, Integer::sum);
        }
        // The citizens' votes: for what they proposed.
        for (Map.Entry<UUID, String> c : Ledger.citizens(village).entrySet()) {
            String theirs = proposals.get(c.getKey());
            if (theirs != null && tally.containsKey(theirs)) tally.merge(theirs, 1, Integer::sum);
        }
        String chosen = null;
        int most = -1, second = 0;
        for (Map.Entry<String, Integer> e : tally.entrySet()) {
            if (e.getValue() > most) { second = Math.max(second, most); most = e.getValue(); chosen = e.getKey(); }
            else second = Math.max(second, e.getValue());
        }
        if (chosen == null) return null;
        CHOSEN.put(village, chosen);
        String by = null;
        for (Map.Entry<UUID, String> p : proposals.entrySet()) {
            if (p.getValue().equals(chosen)) { by = Ledger.citizens(village).getOrDefault(p.getKey(), proposer(village, p.getKey())); break; }
        }
        int against = 0;
        for (int n : tally.values()) against += n;
        against -= most;
        String line = "the council voted " + most + " to " + against + " for " + article(chosen) + (by == null ? "" : ", as " + by + " proposed");
        LAST.put(village, line);
        List<AssistantEntity> folk = Villages.folkOf(village);
        if (!folk.isEmpty()) Villages.tell(village, folk.get(0).level().getDayTime() / 24000L, line);
        return chosen;
    }

    private static final Map<UUID, String> NAMES = new ConcurrentHashMap<>();

    private static String proposer(UUID village, UUID player) {
        return NAMES.getOrDefault(player, "a friend of the village");
    }

    /** What a councillor wants built first. */
    static String preference(VillageFolkEntity m, List<String> options, Map<UUID, String> proposals, UUID village) {
        String best = options.get(0);
        int bestScore = Integer.MIN_VALUE;
        AssistantEntity.StationTask trade = m.stationTask();
        Social.Life life = m.life();
        for (String o : options) {
            int s = 0;
            switch (o) {
                case "cafe" -> s += (trade == AssistantEntity.StationTask.COOK ? 4 : 0) + (life.has(Social.Trait.SOCIABLE) ? 1 : 0)
                    + (life.has(Social.Trait.CHEERFUL) ? 1 : 0);
                case "tavern" -> s += (life.has(Social.Trait.SOCIABLE) ? 2 : 0) + (life.has(Social.Trait.CHEERFUL) ? 2 : 0)
                    + (life.has(Social.Trait.GRUMPY) ? -1 : 0) + (life.has(Social.Trait.HARDWORKING) ? -1 : 0);
                case "smithy" -> s += (trade == AssistantEntity.StationTask.SMITH ? 4 : 0) + (trade == AssistantEntity.StationTask.GUARD ? 2 : 0)
                    + (trade == AssistantEntity.StationTask.MINE ? 1 : 0) + (life.has(Social.Trait.HARDWORKING) ? 1 : 0);
                case "shop" -> s += (trade == AssistantEntity.StationTask.SHOP ? 4 : 0) + (life.has(Social.Trait.HARDWORKING) ? 1 : 0);
                case "brewery" -> s += (trade == AssistantEntity.StationTask.BREW ? 4 : 0) + (life.has(Social.Trait.CURIOUS) ? 1 : 0);
                case "library" -> s += (trade == AssistantEntity.StationTask.ENCHANT ? 4 : 0) + (life.has(Social.Trait.CURIOUS) ? 2 : 0)
                    + (life.has(Social.Trait.SHY) ? 1 : 0);
                case "graveyard" -> s += m.griefDay >= m.level().getDayTime() / 24000L - 7 ? 4 : 1;
                default -> { }
            }
            // Swayed by players it thinks well of: what they asked for.
            for (Map.Entry<UUID, String> p : proposals.entrySet()) {
                if (!p.getValue().equals(o)) continue;
                int warmth = m.persona().affinity(p.getKey());
                if (warmth >= 30) s += 3 + (warmth >= 60 ? 2 : 0);
                else if (warmth <= -20) s -= 2;
            }
            s = s * 10 + Math.floorMod((m.getUUID().hashCode() >> 3) + o.hashCode(), 7);   // a little of its own mind
            if (s > bestScore) { bestScore = s; best = o; }
        }
        return best;
    }

    /** "The village should build a tavern" — which building, if any, the words name. */
    @Nullable
    public static String named(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String[]> e : CHOICES.entrySet()) {
            for (String w : e.getValue()) if (t.contains(w)) return e.getKey();
        }
        return null;
    }

    /** A player puts a building to the council. Returns the folk's answer. */
    public static String propose(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null) return "I've no village to build anything for.";
        String what = named(text);
        if (what == null) return "Build what? A café, a tavern, a smithy, a shop, a brewery, a library?";
        if (Villages.hasBuilt(village, what) && !what.equals("graveyard")) return "We've " + article(what) + " already!";
        PROPOSED.computeIfAbsent(village, k -> new ConcurrentHashMap<>()).put(p.getUUID(), what);
        NAMES.put(p.getUUID(), p.getName().getString());
        CHOSEN.remove(village);                                       // the question is open again
        int warmth = f.persona().affinity(p.getUUID());
        boolean member = members(village).contains(f);
        String mine = warmth >= 30 ? "You'll have my vote, for what it's worth." : warmth >= 0 ? "I'll think about it." : "Hmph. We'll see.";
        return "I'll put " + article(what) + " to the council. " + (member ? mine : "I'm not on it myself, mind.");
    }

    /** "What's the council deciding?" */
    public static String news(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return "There's no council without a village.";
        List<VillageFolkEntity> council = members(village);
        StringBuilder sb = new StringBuilder("The council is ");
        List<String> names = new ArrayList<>();
        for (VillageFolkEntity m : council) names.add(m.displayNameCap() + (m.isElder() ? " (the elder)" : ""));
        sb.append(names.isEmpty() ? "nobody yet" : String.join(", ", names)).append(". ");
        int citizens = Ledger.citizens(village).size();
        if (citizens > 0) sb.append("And our ").append(citizens).append(citizens == 1 ? " citizen has" : " citizens have").append(" a vote too. ");
        String last = LAST.get(village);
        if (last != null) sb.append("Last time ").append(last).append(". ");
        String chosen = CHOSEN.get(village);
        if (chosen != null) sb.append("We're to build ").append(article(chosen)).append(" next of the extras. ");
        sb.append("Tell any of us what you think we should build, and we'll put it to the vote.");
        return sb.toString();
    }

    /**
     * A player on trial: the council hears it, and finds the player guilty unless most of the
     * councillors think well of it. Returns whether it was found guilty.
     */
    public static boolean tries(ServerLevel level, UUID village, Player p) {
        List<VillageFolkEntity> council = members(village);
        if (council.isEmpty()) return true;
        int forgive = 0;
        for (VillageFolkEntity m : council) if (m.persona().affinity(p.getUUID()) >= 25) forgive++;
        return forgive * 2 <= council.size();
    }

    static String article(String building) {
        return switch (building) {
            case "cafe" -> "a café";
            default -> (building.matches("^[aeiou].*") ? "an " : "a ") + building;
        };
    }
}
