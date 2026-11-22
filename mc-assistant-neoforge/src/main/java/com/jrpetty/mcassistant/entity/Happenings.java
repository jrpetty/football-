package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.FolkAction.Act;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [vision27] "Show me something happening": the camera screen's button (client/CameraScreen) asks the server where in
 * town something worth watching is going on just now, and the camera goes there.
 *
 * <p>It is read off what the folk are doing with their bodies (FolkAction's act, the same number their animations play
 * from), so whatever it finds is really happening and really looks it: builders at a building going up, a football
 * match (a ball on the ground with players round it), dancing and music at a festival, a sale at a counter, children at
 * play, the smith at the anvil. Folk at the same kind of thing near each other are counted together, and the busiest
 * gathering wins; a gathering already shown to the same player lately counts for less, so asking again shows another.
 */
public final class Happenings {

    private Happenings() {}

    /** How far round the player it looks (blocks). */
    public static final int RANGE = 96;
    /** Folk this near each other at the same kind of thing are one gathering. */
    static final double CLUSTER = 10.0;
    /** A gathering shown to a player in the last so many ticks counts for less. */
    static final long FORGET = 1200L;

    /** One thing going on: its kind, its words, where, the folk (or ball) to keep in the picture, and its weight. */
    public record Happening(String kind, String label, Vec3 at, int focus, int folk, double score) {}

    /** The places shown to each player lately: {x, z, game time}. */
    private static final Map<UUID, List<double[]>> SHOWN = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SHOWN.clear();
    }

    /** The kind of thing a folk doing this act is at, or null for nothing worth a look (asleep, stood about). */
    @Nullable
    static String kindOf(Act a) {
        return switch (a) {
            case BUILD, SAW, CHISEL -> "build";
            case DANCE, LUTE, DRUM, CHEER, CLAP -> "festival";
            case HAND_OVER, SERVE, COUNT_COINS, STOCK, POUR, WIPE -> "market";
            case TAG, HOPSCOTCH -> "play";
            case HAMMER, STOKE, REPAIR, TINKER, FUSE -> "smithy";
            case CHOP -> "wood";
            case PICK, DIG -> "mine";
            case HOE, PLANT, HARVEST, WATER -> "fields";
            case FISH -> "fishing";
            case STIR_POT, KNEAD, BREW -> "kitchen";
            case MILK, SHEAR, LEAD, FEED -> "herd";
            case TEACH, PREACH, READ, WRITE -> "learning";
            case TALK, LISTEN, HANDSHAKE, QUARREL, PROTEST, WAVE, BOW -> "talk";
            case MOURN, PRAY -> "chapel";
            case EAT, DRINK -> "meal";
            case SEW, FLETCH, ENCHANT, MAP -> "craft";
            case CARRY, SACK -> "haul";
            default -> null;
        };
    }

    /** How much a folk at this kind of thing is worth watching. */
    static double weightOf(String kind) {
        return switch (kind) {
            case "match" -> 6.0;
            case "festival" -> 4.0;
            case "build" -> 3.0;
            case "market", "play" -> 2.2;
            case "smithy", "wood", "mine", "kitchen", "herd", "fishing", "fields", "learning", "craft" -> 1.5;
            case "meal", "chapel" -> 1.2;
            case "haul" -> 0.8;
            default -> 0.6;                                     // talk
        };
    }

    static String labelOf(String kind, int n) {
        boolean many = n > 1;
        return switch (kind) {
            case "match" -> "A football match";
            case "festival" -> "Music and dancing at the festival";
            case "build" -> many ? "Builders raising a building" : "A builder at work";
            case "market" -> many ? "Buying and selling at the market" : "A sale at the counter";
            case "play" -> "Children at play";
            case "smithy" -> "Hammer and anvil at the smithy";
            case "wood" -> many ? "Woodcutters felling trees" : "A woodcutter felling a tree";
            case "mine" -> many ? "Miners at the rock face" : "A miner at the rock face";
            case "fields" -> many ? "Farmers in the fields" : "A farmer in the fields";
            case "fishing" -> "Fishing at the water";
            case "kitchen" -> "Cooking in the kitchen";
            case "herd" -> "Tending the animals";
            case "learning" -> "A lesson going on";
            case "chapel" -> "At the chapel";
            case "meal" -> "A meal together";
            case "craft" -> "A craftsman at the bench";
            case "haul" -> "Goods carried through town";
            default -> many ? "Folk talking together" : "A conversation";
        };
    }

    /** Everything worth a look round this spot, the busiest first. */
    public static List<Happening> around(ServerLevel level, BlockPos from, int range, @Nullable UUID forPlayer) {
        AABB box = new AABB(from).inflate(range, 48, range);
        List<VillageFolkEntity> folk = level.getEntitiesOfClass(VillageFolkEntity.class, box, f -> f.isAlive() && !f.isInvisible());
        List<Entity> balls = new ArrayList<>(level.getEntitiesOfClass(FootballEntity.class, box, Entity::isAlive));
        int n = folk.size();
        String[] kind = new String[n];
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = folk.get(i);
            Act a = FolkAction.now(f);
            kind[i] = kindOf(a);
            // A player near a ball in play, whatever its act says: the match (or the children's kickabout).
            if (!balls.isEmpty() && kind[i] != null && !"build".equals(kind[i])) {
                for (Entity b : balls) if (b.distanceToSqr(f) < 14 * 14 && b.getDeltaMovement().lengthSqr() > 1.0E-4) { kind[i] = "match"; break; }
            }
            if (kind[i] == null) {
                for (Entity b : balls) if (b.distanceToSqr(f) < 12 * 12) { kind[i] = "match"; break; }
            }
        }
        boolean[] used = new boolean[n];
        List<Happening> out = new ArrayList<>();
        List<double[]> shown = forPlayer == null ? List.of() : SHOWN.getOrDefault(forPlayer, List.of());
        long now = level.getGameTime();
        for (int i = 0; i < n; i++) {
            if (used[i] || kind[i] == null) continue;
            used[i] = true;
            List<VillageFolkEntity> group = new ArrayList<>();
            group.add(folk.get(i));
            double sx = folk.get(i).getX(), sy = folk.get(i).getY(), sz = folk.get(i).getZ();
            for (int j = i + 1; j < n; j++) {
                if (used[j] || !kind[i].equals(kind[j])) continue;
                if (folk.get(j).distanceToSqr(sx / group.size(), sy / group.size(), sz / group.size()) > CLUSTER * CLUSTER) continue;
                used[j] = true;
                group.add(folk.get(j));
                sx += folk.get(j).getX();
                sy += folk.get(j).getY();
                sz += folk.get(j).getZ();
            }
            int k = group.size();
            if ("match".equals(kind[i]) && k < 2) continue;              // a ball lying by one folk is no match
            Vec3 at = new Vec3(sx / k, sy / k, sz / k);
            double score = weightOf(kind[i]) * (1.0 + 0.35 * (k - 1));
            for (double[] s : shown) {
                if (now - (long) s[2] < FORGET && Math.abs(s[0] - at.x) < 16 && Math.abs(s[1] - at.z) < 16) score *= 0.25;
            }
            // Keep in the picture the one nearest the middle of it (or the ball, at a match).
            Entity focus = group.get(0);
            double best = Double.MAX_VALUE;
            for (VillageFolkEntity f : group) {
                double d = f.distanceToSqr(at);
                if (d < best) { best = d; focus = f; }
            }
            if ("match".equals(kind[i])) {
                for (Entity b : balls) if (b.distanceToSqr(at) < 20 * 20) { focus = b; break; }
            }
            out.add(new Happening(kind[i], labelOf(kind[i], k), at, focus.getId(), k, score));
        }
        out.sort(Comparator.comparingDouble(Happening::score).reversed());
        return out;
    }

    /** The one to show this player now (remembered, so the next ask shows another), or null for a quiet town. */
    @Nullable
    public static Happening pick(ServerLevel level, BlockPos from, UUID player) {
        List<Happening> all = around(level, from, RANGE, player);
        if (all.isEmpty()) return null;
        Happening h = all.get(0);
        List<double[]> shown = SHOWN.computeIfAbsent(player, k -> new ArrayList<>());
        long now = level.getGameTime();
        shown.removeIf(s -> now - (long) s[2] >= FORGET || now < (long) s[2]);
        shown.add(new double[] {h.at().x, h.at().z, now});
        while (shown.size() > 6) shown.remove(0);
        return h;
    }
}
