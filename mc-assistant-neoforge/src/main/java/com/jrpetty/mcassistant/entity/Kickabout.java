package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.LeisureItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [leisure] The children's kickabout, with the town's leather football (item/FootballItem, FootballEntity).
 *
 * <p>Some afternoons (Pastimes.afternoon) the children take the town's football out of the stores to the park, or the
 * square with no park, and kick it about: the one nearest the ball runs at it and, coming up behind it, passes it to
 * another (a long pass to a far one, a short one to a near one: the ball is a real ball, and rolls and bounces and
 * stops where it stops), and the others spread round in a ring, facing the ball, the one it is passed to going to meet
 * it. A player who joins in is passed to as well, and cheered when it kicks the ball. When the afternoon's kickabout is
 * over the ball goes back into the stores (and if it is left lying anywhere, it takes itself back: FootballEntity).
 *
 * <p>The children are the happier for a kickabout the next day, and remember it; the gazette has it among the day's
 * football, with the league's match (Football: the league's matches are played with the leather football too).
 *
 * <p><b>Made</b> by the tailor (the shop's workshop where there is no tailor): four leather round a wool. The town keeps
 * one for the kickabouts once it has two children, and one more for the pitch once it has one.
 */
public final class Kickabout {

    private Kickabout() {}

    /** A child this near the ball can kick it; a pass no sooner than this after the last (ticks). */
    static final double REACH = 1.3;
    static final long BETWEEN = 12L;

    /** An afternoon's kickabout: the ball, where, who is playing, whom the ball was last passed to, and the tally. */
    static final class Game {
        final UUID village;
        final long day;
        final BlockPos ground;
        final String where;
        UUID ball;
        final List<UUID> players = new ArrayList<>();
        @Nullable UUID passedTo;
        long lastKick = -1000L;
        int passes, playerKicks;
        final Map<UUID, Long> spoke = new ConcurrentHashMap<>();

        Game(UUID village, long day, BlockPos ground, String where) {
            this.village = village;
            this.day = day;
            this.ground = ground;
            this.where = where;
        }
    }

    private static final Map<UUID, Game> GAMES = new ConcurrentHashMap<>();
    /** Each child's last kickabout (the day). */
    private static final Map<UUID, Long> PLAYED = new ConcurrentHashMap<>();

    static void resetForTests() {
        GAMES.clear();
        PLAYED.clear();
    }

    static boolean isFootball(ItemStack s) {
        return !s.isEmpty() && s.is(LeisureItems.LEATHER_FOOTBALL.get());
    }

    /** Is a kickabout on in this town now? */
    static boolean on(UUID village) {
        return GAMES.containsKey(village);
    }

    @Nullable
    static FootballEntity ball(ServerLevel level, Game g) {
        return g.ball != null && level.getEntity(g.ball) instanceof FootballEntity b && b.isAlive() ? b : null;
    }

    // ------------------------------------------------------------------ the child's part (Pastimes.hold)

    /**
     * A child's afternoon at the kickabout, on an afternoon for one: the ball out of the stores if nobody has it out yet;
     * after the ball if it is the nearest (and a pass on to another when it gets there), else at its place in the ring,
     * facing the ball. What it is doing, or null.
     */
    @Nullable
    static String hold(ServerLevel level, VillageFolkEntity child, UUID village, long t, long day) {
        if (Pastimes.afternoon(level, village, day, t) != Pastimes.BALL || !Families.childFree(child) || School.doing(child) != null) return null;
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        Game g = GAMES.get(village);
        if (g == null || g.day != day) {
            g = start(level, v, day);
            if (g == null) return null;
        }
        FootballEntity ball = ball(level, g);
        if (ball == null) {
            end(level, g, "the ball was lost");
            return null;
        }
        long now = level.getGameTime();
        ball.inUse(now);
        if (!g.players.contains(child.getUUID())) {
            if (child.distanceToSqr(Vec3.atCenterOf(g.ground)) > 64.0 * 64.0) return null;
            g.players.add(child.getUUID());
        }
        if (PLAYED.getOrDefault(child.getUUID(), -1L) != day) {
            PLAYED.put(child.getUUID(), day);
            child.persona().remember(day, "we had a kickabout in " + g.where, 2);
        }
        child.lastLeisureTick = child.tickCount;
        child.getLookControl().setLookAt(ball, 30.0F, 30.0F);
        RandomSource r = level.getRandom();
        if (nearest(level, g, ball) == child) {
            Vec3 bp = ball.position();
            Entity to = target(level, g, child, ball);
            Vec3 aim = to != null ? to.position() : Vec3.atCenterOf(g.ground);
            // Up behind the ball, the way it means to send it.
            Vec3 dir = new Vec3(aim.x - bp.x, 0.0, aim.z - bp.z);
            if (dir.lengthSqr() < 1.0E-4) dir = new Vec3(1, 0, 0);
            dir = dir.normalize();
            Vec3 behind = bp.subtract(dir.scale(0.6));
            double dx = child.getX() - bp.x, dz = child.getZ() - bp.z;
            if (dx * dx + dz * dz <= REACH * REACH && now - g.lastKick >= BETWEEN) {
                double dist = Math.sqrt((aim.x - bp.x) * (aim.x - bp.x) + (aim.z - bp.z) * (aim.z - bp.z));
                double power = Mth.clamp(dist * 0.068, 0.18, 0.62) * (0.92 + r.nextDouble() * 0.16);
                double off = (r.nextDouble() - 0.5) * 0.25;                       // a child's pass is not dead straight
                Vec3 kick = new Vec3(dir.x * Math.cos(off) - dir.z * Math.sin(off), 0.0, dir.x * Math.sin(off) + dir.z * Math.cos(off));
                ball.kick(new Vec3(kick.x * power, 0.06 + r.nextDouble() * 0.08 + (dist > 8 ? 0.12 : 0.0), kick.z * power), child);
                child.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                g.lastKick = now;
                g.passes++;
                g.passedTo = to == null ? null : to.getUUID();
                if (r.nextInt(4) == 0) say(level, g, child, to instanceof Player p
                    ? FolkTalk.pick(r, "To you, " + p.getName().getString() + "!", "Your ball, " + p.getName().getString() + "!")
                    : FolkTalk.pick(r, "Pass!", "To you!", "Catch!", "Here it comes!", "Go on, kick it back!"));
                return "playing football in " + g.where + " — passing";
            }
            if (child.getNavigation().isDone() || child.tickCount - child.hobbyTick > 10 || child.tickCount < child.hobbyTick) {
                child.getNavigation().moveTo(behind.x, behind.y, behind.z, 1.25D);
                child.hobbyTick = child.tickCount;
            }
            return "playing football in " + g.where + " — after the ball";
        }
        // Its place in the ring round the middle; the one the ball was passed to comes to meet it.
        BlockPos at = place(level, g, child);
        if (child.getUUID().equals(g.passedTo) && ball.getDeltaMovement().horizontalDistanceSqr() > 1.0E-3) {
            Vec3 bp = ball.position();
            if (child.getNavigation().isDone() || child.tickCount - child.hobbyTick > 10) {
                child.getNavigation().moveTo(bp.x, bp.y, bp.z, 1.1D);
                child.hobbyTick = child.tickCount;
            }
        } else if (child.blockPosition().distSqr(at) > 1.5 * 1.5) {
            if (child.getNavigation().isDone() || child.tickCount - child.hobbyTick > 40 || child.tickCount < child.hobbyTick) {
                child.walkTo(at, 1.0D);
                child.hobbyTick = child.tickCount;
            }
        } else {
            child.getNavigation().stop();
            if (r.nextInt(300) == 0) say(level, g, child, FolkTalk.pick(r, "Over here!", "Pass it to me!", "My go!", "Kick it here!"));
        }
        return "playing football in " + g.where;
    }

    /** The kickabout begun: the town's football out of the stores and down in the middle of the green. Null with no ball. */
    @Nullable
    static Game start(ServerLevel level, Villages.Village v, long day) {
        Object[] pg = Pastimes.playground(v.id(), v);
        BlockPos ground = (BlockPos) pg[0];
        ItemStack ball = Crafts.takeOne(level, v, Kickabout::isFootball);
        if (ball.isEmpty()) return null;
        BlockPos floor = Watch.floorAt(level, ground.getX() + 2, ground.getZ() + 2, ground.getY());
        BlockPos spot = floor != null ? floor : ground;
        FootballEntity e = FootballEntity.setDown(level, spot.getX() + 0.5, spot.getY() + 0.3, spot.getZ() + 0.5, ball, v.id());
        if (e == null) {
            Crafts.store(level, v, ball);
            return null;
        }
        Game g = new Game(v.id(), day, ground, (String) pg[1]);
        g.ball = e.getUUID();
        GAMES.put(v.id(), g);
        Pastimes.LOG.info("[MCA-LEISURE] the children of {} take the football out to {} for a kickabout", Villages.name(v.id()), g.where);
        return g;
    }

    /** The kickabout over: the ball back into the stores, the day's football told. */
    static void end(ServerLevel level, Game g, @Nullable String why) {
        if (!GAMES.remove(g.village, g)) return;
        FootballEntity ball = ball(level, g);
        Villages.Village v = Villages.get(g.village);
        if (ball != null && v != null) {
            Crafts.store(level, v, ball.getItem().copy());
            ball.discard();
        }
        int kids = 0;
        for (UUID u : g.players) if (level.getEntity(u) instanceof VillageFolkEntity) kids++;
        if (g.passes > 0) {
            Pastimes.news(g.village, g.day, "football:" + kids + (kids == 1 ? " child" : " children") + " had a kickabout in " + g.where
                + " with the leather football, " + g.passes + " passes" + (g.playerKicks > 0 ? ", and a visitor joined in" : ""));
        }
        Pastimes.LOG.info("[MCA-LEISURE] the kickabout at {} is over ({} passes){}", Villages.name(g.village), g.passes, why == null ? "" : ": " + why);
    }

    /** Which of the players is nearest the ball (the one who goes for it). */
    @Nullable
    static VillageFolkEntity nearest(ServerLevel level, Game g, FootballEntity ball) {
        VillageFolkEntity best = null;
        double bd = Double.MAX_VALUE;
        for (UUID u : g.players) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity f) || !f.isAlive() || !f.isBaby()) continue;
            double d = f.distanceToSqr(ball);
            if (d < bd) { bd = d; best = f; }
        }
        return best;
    }

    /** Whom to pass to: another child (the ones not passed to last, by turns), or a player who has joined in. */
    @Nullable
    static Entity target(ServerLevel level, Game g, VillageFolkEntity from, FootballEntity ball) {
        List<Entity> options = new ArrayList<>();
        for (UUID u : g.players) {
            if (u.equals(from.getUUID())) continue;
            Entity e = level.getEntity(u);
            if (e instanceof VillageFolkEntity f && f.isAlive() && f.distanceToSqr(ball) < 20.0 * 20.0) options.add(f);
        }
        for (Player p : level.players()) {
            if (!p.isSpectator() && p.distanceToSqr(Vec3.atCenterOf(g.ground)) < 14.0 * 14.0) options.add(p);
        }
        if (options.isEmpty()) return null;
        int pick = Math.floorMod((int) (g.passes * 31L) + from.getUUID().hashCode(), options.size());
        return options.get(pick);
    }

    /** Its place in the ring round the middle of the green. */
    static BlockPos place(ServerLevel level, Game g, VillageFolkEntity child) {
        int i = Math.max(0, g.players.indexOf(child.getUUID()));
        int n = Math.max(2, g.players.size());
        double a = i * Mth.TWO_PI / n + Math.floorMod(g.day, 6L);
        double rad = 4.5 + (i % 2) * 1.5;
        int x = g.ground.getX() + (int) Math.round(Math.cos(a) * rad), z = g.ground.getZ() + (int) Math.round(Math.sin(a) * rad);
        BlockPos floor = Watch.floorAt(level, x, z, g.ground.getY());
        return floor != null ? floor : g.ground;
    }

    private static void say(ServerLevel level, Game g, VillageFolkEntity f, String text) {
        long now = level.getGameTime();
        Long last = g.spoke.get(f.getUUID());
        if (last != null && now - last < 160L && now >= last) return;
        g.spoke.put(f.getUUID(), now);
        FolkTalk.speak(f, text);
    }

    /**
     * A player kicked (or tapped) a ball: at a kickabout the children are delighted and pass to it from now on; the
     * league's match counts the touch as the player's (Football, by the ball's last kicker).
     */
    static void playerKicked(ServerLevel level, FootballEntity ball, Player p) {
        if (ball.town() == null) return;
        Game g = GAMES.get(ball.town());
        if (g == null || !ball.getUUID().equals(g.ball)) return;
        g.playerKicks++;
        g.lastKick = level.getGameTime();
        ball.inUse(level.getGameTime());
        VillageFolkEntity near = nearest(level, g, ball);
        if (near != null && level.getRandom().nextInt(2) == 0) {
            say(level, g, near, FolkTalk.pick(level.getRandom(), "Nice kick, " + p.getName().getString() + "!", "You're playing? Yay!",
                "Ooh, " + p.getName().getString() + " can kick!"));
        }
        if (g.playerKicks == 1) {
            p.displayClientMessage(Component.literal("You've joined the children's kickabout."), true);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, ball.getX(), ball.getY() + 0.6, ball.getZ(), 6, 0.4, 0.2, 0.4, 0.0);
        }
    }

    /** The town's round (every second): a kickabout whose afternoon is over (or the children gone home) ended. */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        Game g = GAMES.get(v.id());
        if (g == null) return;
        if (g.day != day || Pastimes.afternoon(level, v.id(), day, t) != Pastimes.BALL) end(level, g, null);
    }

    // ------------------------------------------------------------------ made, spirits, the card, the books

    /** A football for the children's kickabouts once there are two of them, and one for the pitch. */
    static int wanted(ServerLevel level, Villages.Village v) {
        int children = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a.isBaby() && a.isAlive()) children++;
        int n = children >= 2 ? 1 : 0;
        if (Pitch.of(v.id()) != null) n++;
        return n;
    }

    static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Long d = PLAYED.get(f.getUUID());
        if (d == null || day - d > 1) return m;
        why.add(new Object[]{ "kickabout", 4 });
        return m + 4;
    }

    /** The gazette's football: the league's match (with its goals) and the children's kickabout, yesterday. */
    static List<String> gazette(UUID village, long day) {
        List<String> out = new ArrayList<>();
        for (String l : Pastimes.newsOf(village, day - 1, "football:")) {
            out.add(Character.toUpperCase(l.charAt(0)) + l.substring(1) + ".");
            if (out.size() >= 3) break;
        }
        return out;
    }

    static String status(ServerLevel level, Villages.Village v) {
        Game g = GAMES.get(v.id());
        int balls = Market.stock(level, v.id(), Kickabout::isFootball);
        return balls + (balls == 1 ? " football" : " footballs") + " in the stores" + (g != null ? "; a kickabout on now in " + g.where + ", "
            + g.players.size() + " playing, " + g.passes + " passes" : "") + (Pitch.of(v.id()) != null ? "; the league plays with the leather ball" : "");
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a child's look at the kickabout now (as Pastimes.hold calls it). */
    @Nullable
    public static String holdForTests(ServerLevel level, VillageFolkEntity child) {
        long dt = level.getDayTime();
        return child.ownerId() == null ? null : hold(level, child, child.ownerId(), dt % 24000L, dt / 24000L);
    }

    /** Tests: the kickabout's ball, or null. */
    @Nullable
    public static FootballEntity ballForTests(ServerLevel level, UUID village) {
        Game g = GAMES.get(village);
        return g == null ? null : ball(level, g);
    }

    /** Tests: {passes, players, a player's kicks}, or null with none on. */
    @Nullable
    public static int[] tallyForTests(UUID village) {
        Game g = GAMES.get(village);
        return g == null ? null : new int[]{ g.passes, g.players.size(), g.playerKicks };
    }

    /** Tests: the kickabout over now. */
    public static void endForTests(ServerLevel level, UUID village) {
        Game g = GAMES.get(village);
        if (g != null) end(level, g, null);
    }
}
