package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * "Show me the way to…": a folk walks a player to somewhere in its village — the
 * storehouse, the board, the elder, where it works, its home, any building the village has
 * raised, the player's own house — at a walking pace, waiting when the player falls behind
 * and coming back for them if they wander off (goal/GuideGoal), and says a word about the
 * place when they get there.
 */
public final class Guide {

    private Guide() {}

    /** Somewhere it can take you: a short key the screen sends back, what it is called, where. */
    public record Place(String key, String label, BlockPos at) {}

    /** How far a folk will walk somebody. */
    static final int FURTHEST = 200;

    /** Everywhere this folk could take this player, nearest first after the everyday places. */
    public static List<Place> places(VillageFolkEntity f, @Nullable Player p) {
        List<Place> out = new ArrayList<>();
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return out;
        BlockPos stores = f.storesSpot(level, village);
        if (stores != null) out.add(new Place("stores", Storehouses.stands(village) ? "the storehouse" : "the stores", stores));
        BlockPos board = VillageBoards.lectern(village);
        if (board != null) out.add(new Place("board", "the village board", board));
        UUID elder = Villages.elder(village);
        if (elder != null && !elder.equals(f.getUUID()) && level.getEntity(elder) instanceof VillageFolkEntity e) {
            out.add(new Place("elder", "Elder " + e.displayNameCap(), e.blockPosition()));
        }
        if (f.workZone() != null && f.stationTask() != AssistantEntity.StationTask.NONE) {
            out.add(new Place("work", "where I work", f.workZone().center()));
        }
        if (f.bedPos() != null) out.add(new Place("home", "my home", f.bedPos()));
        if (p != null) {
            com.jrpetty.mcassistant.village.Chronicle.Guest g = com.jrpetty.mcassistant.village.Chronicle.guest(village, p.getUUID());
            if (g != null && g.built) out.add(new Place("yours", "your house", new BlockPos((int) g.x, (int) g.y, (int) g.z)));
        }
        out.addAll(Scouts.places(f, 4));                 // a scout knows the way to what it has found
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Ledger.Building> buildings = new ArrayList<>(Ledger.buildings(village));
        buildings.sort(java.util.Comparator.comparingDouble(b -> b.anchor().distSqr(f.blockPosition())));
        for (Ledger.Building b : buildings) {
            String s = b.structure();
            if (s.equals("house") || s.equals("fortify") || s.equals("storage") || s.equals("storehouse") || !seen.add(s)) continue;
            out.add(new Place(s, Villages.spoken(s), b.anchor().relative(b.facing(), 3)));
            if (out.size() >= 14) break;
        }
        return out;
    }

    /** For the talk screen: "key=Label;key=Label". */
    public static String encode(List<Place> places) {
        StringBuilder sb = new StringBuilder();
        for (Place pl : places) {
            if (sb.length() > 0) sb.append(';');
            sb.append(pl.key()).append('=').append(capital(pl.label()));
        }
        return sb.toString();
    }

    /** "Show me the way to…" — `asked` is a place key from the screen, or the player's own words. */
    public static String ask(VillageFolkEntity f, Player p, String asked) {
        List<Place> places = places(f, p);
        if (places.isEmpty()) return "There's not much to show yet — we've hardly started!";
        Place to = match(places, asked);
        if (to == null) {
            List<String> names = new ArrayList<>();
            for (Place pl : places) names.add(pl.label());
            return "I can show you " + String.join(", ", names.subList(0, Math.min(8, names.size())))
                + (names.size() > 8 ? " and more" : "") + ". Where to?";
        }
        if (f.isSleeping() || f.level().isNight() && f.stationTask() != AssistantEntity.StationTask.GUARD) {
            return "At this hour? It's " + (to.at().distSqr(f.blockPosition()) < 40 * 40 ? "just over there" : "off that way")
                + " — " + direction(f.blockPosition(), to.at()) + ". I'm for my bed.";
        }
        if (f.stationTask() == AssistantEntity.StationTask.GUARD && f.onWatch()) {
            return "I can't leave my post. " + capital(to.label()) + "'s " + direction(f.blockPosition(), to.at()) + " of here.";
        }
        if (f.persona().affinity(p.getUUID()) < -20) {
            return "Find it yourself. It's " + direction(f.blockPosition(), to.at()) + ".";
        }
        double far = Math.sqrt(to.at().distSqr(f.blockPosition()));
        if (far < 4) return "You're standing at it!";
        if (far > FURTHEST) return capital(to.label()) + "? That's a long walk — " + direction(f.blockPosition(), to.at()) + ", a good way.";
        f.startGuiding(p, to.at(), to.label());
        long day = f.level().getDayTime() / 24000L;
        f.persona().remember(day, "I showed " + p.getName().getString() + " the way to " + to.label(), 2);
        return FolkTalk.pick(f.getRandom(), "Come on, I'll take you to " + to.label() + ". This way!",
            "Follow me — " + to.label() + " is " + direction(f.blockPosition(), to.at()) + ".",
            capital(to.label()) + "? Easy. Stay close.");
    }

    /** A word about a place, said on getting there. */
    public static String arrived(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        String tip = l.contains("storehouse") ? " Everything the village has is in there — right-click it to look."
            : l.contains("board") ? " Everything the village is up to is written up there."
            : l.contains("elder") ? " Mind your manners."
            : l.contains("your house") ? " Your very own. We built it for you."
            : l.contains("café") || l.contains("cafe") ? " Try the honey tea."
            : l.contains("tavern") ? " First round's on you, I hope!"
            : l.contains("shop") ? " Have a look at the counter."
            : l.contains("chapel") ? " It's quiet in there. Peaceful."
            : l.contains("smithy") ? " Hot work, smithing." : "";
        return "Here we are: " + label + "." + tip;
    }

    @Nullable
    static Place match(List<Place> places, String asked) {
        if (asked == null || asked.isBlank()) return null;
        String a = asked.toLowerCase(Locale.ROOT).trim();
        for (Place pl : places) if (pl.key().equals(a)) return pl;
        for (Place pl : places) {
            String label = pl.label().toLowerCase(Locale.ROOT);
            String last = label.substring(label.lastIndexOf(' ') + 1);
            if (a.contains(pl.key()) || a.contains(label) || last.length() > 3 && a.contains(last)) return pl;
        }
        if (a.contains("store") || a.contains("chest")) for (Place pl : places) if (pl.key().equals("stores")) return pl;
        if (a.contains("notice") || a.contains("board")) for (Place pl : places) if (pl.key().equals("board")) return pl;
        if (a.contains("house") || a.contains("home")) for (Place pl : places) if (pl.key().equals("home")) return pl;
        return null;
    }

    /** "north-east", from here to there. */
    public static String direction(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        String[] names = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
        return names[(int) Math.floorMod(Math.round(ang / 45.0), 8L)];
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
