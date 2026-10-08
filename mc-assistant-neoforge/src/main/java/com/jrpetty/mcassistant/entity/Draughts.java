package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.DraughtsBoardBlock;
import com.jrpetty.mcassistant.block.DraughtsBoardBlockEntity;
import com.jrpetty.mcassistant.item.LeisureItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [leisure] Draughts (block/DraughtsBoardBlock): real games on a real board.
 *
 * <p><b>The boards.</b> The town sets a board out on a table of the tavern's (a post with a cloth on it: the cloth goes
 * into the stores while the board is out) and on a table it puts up in the park (a fence post and a bench either side
 * of it, out of the stores), as soon as it has a board to set out; the shop's workshop makes them (Pastimes).
 *
 * <p><b>The games.</b> On an evening after supper at the tavern, and on the day of rest in the park, two folk free of
 * an evening (a friend with a friend, if it can be) sit down across a board and play: real draughts, move by move, the
 * pieces moving on the board as they play (a move every two or three seconds, a man jumping and taking, a man crowned at
 * the far side, a capture taken when there is one), till one side has nothing left to move, or after ninety moves the
 * side with the more on the board. Each picks its move as well as it can: the cleverer (curious, hardworking, a thinker,
 * a hand at the game already; not the easygoing nor the chatterbox) sees the best move more often, and so wins more
 * often. The winner is the happier for it a day, both remember it, and the two grow friendlier over a game. Each folk's
 * games won and lost are kept in the town's books.
 *
 * <p><b>The fair's tournament.</b> At the town fair the town's best players (by games won) play it out, two rounds and
 * a final, and the champion is in the chronicle and given a purse out of the treasury.
 *
 * <p><b>A player's challenge.</b> Right-click a board and whoever of the town is nearest sits down to a short game with
 * you: a dozen moves, then it is settled by the folk's skill and a roll of the dice, and the result said aloud.
 */
public final class Draughts {

    private Draughts() {}

    public static final int RED = 1, BLACK = 2;
    /** A game's longest, in moves (both sides'), before it is called on what is left on the board. */
    public static final int LONGEST = 90;
    /** A player's challenge: so many moves, then the dice. */
    static final int SHORT_GAME = 12;
    /** The tavern's evening games and the park's rest-day games. */
    static final long EVENING_FROM = 12600L, EVENING_TO = 14200L, REST_FROM = 7000L, REST_TO = 11500L;
    /** A game started at a board at most this often (ticks). */
    static final long BETWEEN = 1800L;
    /** The purse for the fair's champion, out of the treasury. */
    static final int PURSE = 3;

    // ------------------------------------------------------------------ the board's own reckoning

    /** The thirty-two dark squares, row by row from black's side: row and column of each. */
    static int row(int i) {
        return i / 4;
    }

    static int col(int i) {
        return 2 * (i % 4) + (row(i) + 1) % 2;
    }

    /** The dark square at this row and column, or -1 off the board (or a light square). */
    static int at(int r, int c) {
        if (r < 0 || r > 7 || c < 0 || c > 7 || (r + c) % 2 == 0) return -1;
        return r * 4 + c / 2;
    }

    static int owner(int p) {
        return p == 1 || p == 3 ? RED : p == 2 || p == 4 ? BLACK : 0;
    }

    static boolean king(int p) {
        return p >= 3;
    }

    static int other(int side) {
        return side == RED ? BLACK : RED;
    }

    /** A move: the squares it goes through (the first the piece's own), the pieces it takes. */
    record Move(int[] path, int[] taken) {
        int from() { return path[0]; }
        int to() { return path[path.length - 1]; }
    }

    /** The ways a piece goes: a man forward (red up the board, black down), a king both ways. */
    private static int[][] dirs(int p) {
        if (king(p)) return new int[][]{ { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 } };
        return owner(p) == RED ? new int[][]{ { -1, -1 }, { -1, 1 } } : new int[][]{ { 1, -1 }, { 1, 1 } };
    }

    private static boolean crownRow(int p, int r) {
        return !king(p) && (owner(p) == RED ? r == 0 : r == 7);
    }

    /** Every move this side has: a capture if it has one (it must take), else every step. */
    static List<Move> moves(byte[] b, int side) {
        List<Move> jumps = new ArrayList<>();
        for (int i = 0; i < 32; i++) if (owner(b[i]) == side) jumps(b, b[i], new int[]{ i }, new int[0], jumps);
        if (!jumps.isEmpty()) return jumps;
        List<Move> steps = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            if (owner(b[i]) != side) continue;
            for (int[] d : dirs(b[i])) {
                int t = at(row(i) + d[0], col(i) + d[1]);
                if (t >= 0 && b[t] == 0) steps.add(new Move(new int[]{ i, t }, new int[0]));
            }
        }
        return steps;
    }

    /** The jumps on from here (a man crowned on the far row stops there). */
    private static void jumps(byte[] b, int p, int[] path, int[] taken, List<Move> out) {
        int sq = path[path.length - 1];
        boolean any = false;
        for (int[] d : dirs(p)) {
            int mid = at(row(sq) + d[0], col(sq) + d[1]), land = at(row(sq) + 2 * d[0], col(sq) + 2 * d[1]);
            if (mid < 0 || land < 0 || owner(b[mid]) != other(owner(p)) || contains(taken, mid)) continue;
            if (b[land] != 0 && land != path[0]) continue;
            int[] np = Arrays.copyOf(path, path.length + 1);
            np[path.length] = land;
            int[] nt = Arrays.copyOf(taken, taken.length + 1);
            nt[taken.length] = mid;
            any = true;
            if (crownRow(p, row(land))) out.add(new Move(np, nt));
            else jumps(b, p, np, nt, out);
        }
        if (!any && taken.length > 0) out.add(new Move(path, taken));
    }

    private static boolean contains(int[] a, int x) {
        for (int y : a) if (y == x) return true;
        return false;
    }

    /** The board after a move: the piece moved, what it took off, crowned if it reached the far row. */
    static byte[] apply(byte[] b, Move m) {
        byte[] c = b.clone();
        byte p = c[m.from()];
        c[m.from()] = 0;
        for (int t : m.taken()) c[t] = 0;
        if (crownRow(p, row(m.to()))) p = (byte) (owner(p) == RED ? DraughtsBoardBlockEntity.RED_KING : DraughtsBoardBlockEntity.BLACK_KING);
        c[m.to()] = p;
        return c;
    }

    /** What a side has on the board, a king worth a man and a half. */
    static double material(byte[] b, int side) {
        double n = 0;
        for (byte p : b) if (owner(p) == side) n += king(p) ? 1.5 : 1.0;
        return n;
    }

    /**
     * How good a move looks to a player who thinks one move on: what it takes and crowns, less what the other can take
     * back at once; a man pushed on, the middle held, the back row kept while it can be.
     */
    static double value(byte[] b, Move m, int side) {
        double v = 10.0 * m.taken().length;
        byte p = b[m.from()];
        if (crownRow(p, row(m.to()))) v += 7.0;
        byte[] after = apply(b, m);
        int back = 0;
        for (Move r : moves(after, other(side))) back = Math.max(back, r.taken().length);
        v -= 9.0 * back;
        if (!king(p)) {
            v += 0.6;                                                      // on toward the crown
            int home = owner(p) == RED ? 7 : 0;
            if (row(m.from()) == home) v -= 0.8;                           // the back row kept while it can be
        }
        int c = col(m.to());
        if (c >= 2 && c <= 5) v += 0.3;
        return v;
    }

    /**
     * The move a player of this cleverness makes: the best it can see, more often the cleverer it is (one in four for a
     * dullard, nineteen in twenty for the town's sharpest), else whatever comes to hand. Null with nothing to move.
     */
    @Nullable
    static Move choose(byte[] b, int side, int clever, RandomSource r) {
        List<Move> ms = moves(b, side);
        if (ms.isEmpty()) return null;
        double p = Math.min(0.95, 0.25 + clever * 0.05);
        if (r.nextDouble() >= p) return ms.get(r.nextInt(ms.size()));
        Move best = null;
        double bv = -1e9;
        for (Move m : ms) {
            double v = value(b, m, side) + r.nextDouble() * 0.4;
            if (v > bv) { bv = v; best = m; }
        }
        return best;
    }

    /** How sharp a player this is: its nature, its years, its trade, and the games it has won. Nought to fourteen. */
    static int cleverness(VillageFolkEntity f) {
        int c = 4;
        Social.Life l = f.life();
        if (l.has(Social.Trait.CURIOUS)) c += 4;
        if (l.has(Social.Trait.HARDWORKING)) c += 2;
        if (l.has(Social.Trait.SHY)) c += 1;                               // the quiet ones think it through
        if (l.has(Social.Trait.EASYGOING)) c -= 2;
        if (l.has(Social.Trait.SOCIABLE)) c -= 1;                          // too busy chatting to look at the board
        if (f.isOld()) c += 2;
        c += Math.min(2, f.veteranLevel() / 20);
        if (f.ownerId() != null) c += Math.min(3, record(f.ownerId(), f.getUUID())[0] / 3);
        return Math.max(0, Math.min(14, c));
    }

    /**
     * A whole game played out, move by move, between these two (red first) without a board: who won (RED, BLACK, or 0
     * for a draw) and in how many moves. The fair's tournament, and the tests.
     */
    static int[] playOut(int redClever, int blackClever, RandomSource r) {
        byte[] b = DraughtsBoardBlockEntity.start();
        int side = RED;
        for (int ply = 0; ply < LONGEST; ply++) {
            Move m = choose(b, side, side == RED ? redClever : blackClever, r);
            if (m == null) return new int[]{ other(side), ply };
            b = apply(b, m);
            side = other(side);
        }
        double red = material(b, RED), black = material(b, BLACK);
        return new int[]{ red > black ? RED : black > red ? BLACK : 0, LONGEST };
    }

    // ------------------------------------------------------------------ the town's boards

    /** A board the town set out: where, and where (the tavern or the park). */
    record Board(BlockPos pos, String where) {}

    static List<Board> boards(UUID village) {
        List<Board> out = new ArrayList<>();
        String s = Ledger.note(village, "draughts.boards");
        if (s == null || s.isEmpty()) return out;
        for (String e : s.split(";")) {
            String[] p = e.split("\\|");
            if (p.length < 2) continue;
            try {
                out.add(new Board(BlockPos.of(Long.parseLong(p[0])), p[1]));
            } catch (NumberFormatException ignored) {
                // a board from another build: left off
            }
        }
        return out;
    }

    static void saveBoards(UUID village, List<Board> boards) {
        StringBuilder sb = new StringBuilder();
        for (Board b : boards) sb.append(sb.length() == 0 ? "" : ";").append(b.pos().asLong()).append('|').append(b.where());
        Ledger.note(village, "draughts.boards", sb.toString());
    }

    @Nullable
    static Board boardAt(UUID village, String where) {
        for (Board b : boards(village)) if (b.where().equals(where)) return b;
        return null;
    }

    static boolean standing(ServerLevel level, BlockPos p) {
        return level.isLoaded(p) && level.getBlockState(p).getBlock() instanceof DraughtsBoardBlock;
    }

    /** The boards the town wants: one for the tavern's table and one for the park, till each is out. */
    static int wanted(ServerLevel level, Villages.Village v) {
        int n = 0;
        if (Tavern.of(v.id()) != null && boardAt(v.id(), "tavern") == null) n++;
        if (!Park.parks(v.id()).isEmpty() && boardAt(v.id(), "park") == null) n++;
        return n;
    }

    /** The seat either side of a table post (a stair, the right way up), or null: {one side, the other}. */
    @Nullable
    static BlockPos[] seats(ServerLevel level, BlockPos post) {
        for (Direction d : new Direction[]{ Direction.EAST, Direction.SOUTH }) {
            BlockPos a = post.relative(d), b = post.relative(d.getOpposite());
            if (seat(level, a) && seat(level, b)) return new BlockPos[]{ a, b };
        }
        return null;
    }

    private static boolean seat(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getBlock() instanceof StairBlock && s.getValue(StairBlock.HALF) == Half.BOTTOM;
    }

    /** A table at the tavern for a board: a post with a stair either side and its cloth (or nothing) on it. */
    @Nullable
    static BlockPos tavernTable(ServerLevel level, Ledger.Building tav) {
        BlockPos a = tav.anchor();
        for (BlockPos p : BlockPos.betweenClosed(a.offset(-6, -1, -6), a.offset(6, 2, 6))) {
            if (!(level.getBlockState(p).getBlock() instanceof FenceBlock)) continue;
            BlockState top = level.getBlockState(p.above());
            if (!top.isAir() && !top.is(BlockTags.WOOL_CARPETS)) continue;
            if (seats(level, p) != null) return p.immutable();
        }
        return null;
    }

    /**
     * The boards set out, when the town has a board in its stores: on a table of the tavern's (its cloth into the stores
     * while the board is out), and on a table put up in the park, a post and a bench either side of it out of the
     * stores. A hand at the town's works does it (TownJobs).
     */
    static void setUp(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<Board> list = boards(id);
        boolean changed = list.removeIf(b -> level.isLoaded(b.pos()) && !standing(level, b.pos()));
        if (changed) saveBoards(id, list);
        if (Market.stock(level, id, s -> s.is(LeisureItems.DRAUGHTS_BOARD_ITEM.get())) <= 0) return;
        Ledger.Building tav = Tavern.of(id);
        if (tav != null && boardAt(id, "tavern") == null && level.isLoaded(tav.anchor())) {
            BlockPos post = tavernTable(level, tav);
            if (post != null && TownJobs.atWork(level, v, "draughts", post, "setting a draughts board on the tavern's table")) {
                if (place(level, v, post, "tavern")) return;
            }
        }
        List<Ledger.Building> parks = Park.parks(id);
        if (!parks.isEmpty() && boardAt(id, "park") == null && level.isLoaded(parks.get(0).anchor())) {
            BlockPos post = parkTable(level, v, parks.get(0));
            if (post != null && TownJobs.atWork(level, v, "draughts", post, "putting a draughts table up in the park")) place(level, v, post, "park");
        }
    }

    /** The board set on this post (its cloth, if any, into the stores), facing one of its seats. Whether it is out. */
    static boolean place(ServerLevel level, Villages.Village v, BlockPos post, String where) {
        BlockPos[] seats = seats(level, post);
        if (seats == null) return false;
        BlockPos at = post.above();
        BlockState top = level.getBlockState(at);
        if (!top.isAir() && !top.is(BlockTags.WOOL_CARPETS)) return false;
        if (!Crafts.take(level, v, s -> s.is(LeisureItems.DRAUGHTS_BOARD_ITEM.get()), 1)) return false;
        if (top.is(BlockTags.WOOL_CARPETS)) Crafts.store(level, v, new ItemStack(top.getBlock().asItem()));
        Direction red = Direction.getNearest(seats[0].getX() - post.getX(), 0, seats[0].getZ() - post.getZ());
        level.setBlock(at, LeisureItems.DRAUGHTS_BOARD.get().defaultBlockState().setValue(DraughtsBoardBlock.FACING, red), Block.UPDATE_ALL);
        level.playSound(null, at, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.2F);
        List<Board> list = boards(v.id());
        list.add(new Board(at.immutable(), where));
        saveBoards(v.id(), list);
        Pastimes.LOG.info("[MCA-LEISURE] {} set a draughts board out at the {} ({})", Villages.name(v.id()), where, at.toShortString());
        return true;
    }

    /**
     * A table in the park: a fence post on the lawn off the paths, a bench (a stair) either side facing it, out of the
     * stores (a post and two stairs; made at the bench if the stores have none). The post, or null with no room or none.
     */
    @Nullable
    static BlockPos parkTable(ServerLevel level, Villages.Village v, Ledger.Building park) {
        Park.Layout lay = Park.layout(park);
        java.util.Set<BlockPos> busy = new java.util.HashSet<>(lay.paths());
        busy.addAll(lay.trees());
        busy.addAll(lay.flowers());
        busy.addAll(lay.lights());
        busy.addAll(lay.water());
        busy.addAll(lay.stone());
        for (Park.Seat s : lay.seats()) busy.add(s.at());
        Direction right = park.facing().getClockWise(), along = park.facing();
        // A table already put up (a post with its two benches): the one to use.
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                BlockPos p = park.anchor().relative(right, dx).relative(along, dz);
                if (level.getBlockState(p).getBlock() instanceof FenceBlock && level.getBlockState(p.above()).isAir() && seats(level, p) != null) return p;
            }
        }
        int[][] spots = { { -4, 2 }, { 4, 2 }, { -4, -2 }, { 4, -2 }, { -2, 4 }, { 2, 4 }, { -2, -4 }, { 2, -4 } };
        for (int[] s : spots) {
            BlockPos post = park.anchor().relative(right, s[0]).relative(along, s[1]);
            Direction across = Math.abs(s[0]) > Math.abs(s[1]) ? along : right;
            BlockPos a = post.relative(across), b = post.relative(across.getOpposite());
            boolean clear = true;
            for (BlockPos p : new BlockPos[]{ post, a, b }) {
                BlockPos floor = Watch.floorAt(level, p.getX(), p.getZ(), park.anchor().getY());
                if (floor == null || floor.getY() != post.getY() || busy.contains(p) || busy.contains(p.below())
                        || !level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()
                        || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) { clear = false; break; }
            }
            if (!clear) continue;
            ItemStack fence = Crafts.takeOne(level, v, st -> st.is(ItemTags.WOODEN_FENCES) && st.getItem() instanceof BlockItem);
            if (fence.isEmpty()) fence = madeOne(level, v, Items.OAK_FENCE, st -> st.is(ItemTags.WOODEN_FENCES) && st.getItem() instanceof BlockItem);
            if (fence.isEmpty()) return null;
            ItemStack s1 = Crafts.takeOne(level, v, st -> st.is(ItemTags.WOODEN_STAIRS) && st.getItem() instanceof BlockItem);
            if (s1.isEmpty()) s1 = madeOne(level, v, Items.OAK_STAIRS, st -> st.is(ItemTags.WOODEN_STAIRS) && st.getItem() instanceof BlockItem);
            ItemStack s2 = s1.isEmpty() ? ItemStack.EMPTY : Crafts.takeOne(level, v, st -> st.is(ItemTags.WOODEN_STAIRS) && st.getItem() instanceof BlockItem);
            if (s1.isEmpty() || s2.isEmpty()) {
                Crafts.store(level, v, fence);
                if (!s1.isEmpty()) Crafts.store(level, v, s1);
                return null;
            }
            level.setBlock(post, ((BlockItem) fence.getItem()).getBlock().defaultBlockState(), Block.UPDATE_ALL);
            // The benches face the table: a sitter's back to the lawn.
            level.setBlock(a, ((BlockItem) s1.getItem()).getBlock().defaultBlockState().setValue(StairBlock.FACING, across), Block.UPDATE_ALL);
            level.setBlock(b, ((BlockItem) s2.getItem()).getBlock().defaultBlockState().setValue(StairBlock.FACING, across.getOpposite()), Block.UPDATE_ALL);
            return post;
        }
        return null;
    }

    /** One of this made at the bench out of the stores, and taken out again: what was made, or empty. */
    private static ItemStack madeOne(ServerLevel level, Villages.Village v, net.minecraft.world.item.Item it, java.util.function.Predicate<ItemStack> what) {
        Bench.Hand hand = Bench.handOf(level, v, null, null);
        Bench.Plan p = Bench.plan(level, v, it, 1, hand);
        if (!p.ok() || Bench.make(level, v, p, null, hand).isEmpty()) return ItemStack.EMPTY;
        return Crafts.takeOne(level, v, what);
    }

    // ------------------------------------------------------------------ a game

    /** A game on a board: who plays red and black, the board as it stands, whose move, how far on. */
    static final class Game {
        final BlockPos board;
        final UUID village;
        final String where;
        final UUID red, black;
        final String redName, blackName;
        final BlockPos redSeat, blackSeat;
        final boolean challenge;
        byte[] cells = DraughtsBoardBlockEntity.start();
        int toMove = RED, plies;
        long started, nextAt, seatedAt = -1;
        boolean over;

        Game(BlockPos board, UUID village, String where, UUID red, String redName, UUID black, String blackName,
             BlockPos redSeat, BlockPos blackSeat, boolean challenge) {
            this.board = board;
            this.village = village;
            this.where = where;
            this.red = red;
            this.redName = redName;
            this.black = black;
            this.blackName = blackName;
            this.redSeat = redSeat;
            this.blackSeat = blackSeat;
            this.challenge = challenge;
        }

        UUID mover() { return toMove == RED ? red : black; }
        BlockPos seatOf(UUID u) { return u.equals(red) ? redSeat : blackSeat; }
        String nameOf(UUID u) { return u.equals(red) ? redName : blackName; }
        UUID otherOf(UUID u) { return u.equals(red) ? black : red; }
    }

    /** The games on now, by their board. */
    private static final Map<Long, Game> GAMES = new ConcurrentHashMap<>();
    /** The game each player is in. */
    private static final Map<UUID, Game> PLAYING = new ConcurrentHashMap<>();
    /** Each board's last game's start (game time). */
    private static final Map<Long, Long> LAST = new ConcurrentHashMap<>();
    /** Each folk's last win (the day), for its spirits. */
    private static final Map<UUID, Long> WON = new ConcurrentHashMap<>();
    /** Each town's last look at setting its boards out. */
    private static final Map<UUID, Long> SET = new ConcurrentHashMap<>();
    /** Folk sat at a board (Park leaves them sat). */
    private static final java.util.Set<UUID> SEATED = ConcurrentHashMap.newKeySet();

    static void resetForTests() {
        GAMES.clear();
        PLAYING.clear();
        LAST.clear();
        WON.clear();
        SET.clear();
        SEATED.clear();
    }

    /** Sat at a draughts board (Park and the rest leave it sat). */
    public static boolean seated(VillageFolkEntity f) {
        return SEATED.contains(f.getUUID());
    }

    /** Each folk's games: {won, lost, drawn}. */
    static int[] record(UUID village, UUID folk) {
        String s = Ledger.note(village, "draughts/" + folk);
        int[] r = new int[3];
        if (s == null || s.isEmpty()) return r;
        String[] p = s.split(",");
        for (int i = 0; i < Math.min(3, p.length); i++) {
            try {
                r[i] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException ignored) {
                // a record from another build: what could be read is kept
            }
        }
        return r;
    }

    static void tally(UUID village, UUID folk, int which) {
        int[] r = record(village, folk);
        r[which]++;
        Ledger.note(village, "draughts/" + folk, r[0] + "," + r[1] + "," + r[2]);
    }

    @Nullable
    static VillageFolkEntity live(ServerLevel level, @Nullable UUID u) {
        return u != null && level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    // ------------------------------------------------------------------ the town's round, every second

    /** The boards set out (once a minute); a game begun at a free board when it is the hour for one; each game's next move. */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long set = SET.get(id);
        if (set == null || now - set >= 1200L || now < set) {
            SET.put(id, now);
            setUp(level, v);
        }
        for (Game g : new ArrayList<>(GAMES.values())) if (g.village.equals(id)) step(level, g, now);
        boolean evening = t >= EVENING_FROM && t < EVENING_TO;
        boolean rest = RestDay.today(id, day) && t >= REST_FROM && t < REST_TO;
        if (!evening && !rest || Raids.underAlarm(id) || Assemblies.underWay(id) != null) return;
        for (Board b : boards(id)) {
            if (GAMES.containsKey(b.pos().asLong()) || !standing(level, b.pos())) continue;
            if (evening && !b.where().equals("tavern") && !rest) continue;
            if (!evening && !b.where().equals("park")) continue;
            if (b.where().equals("park") && level.isRaining()) continue;
            Long last = LAST.get(b.pos().asLong());
            if (last != null && now - last < BETWEEN && now >= last) continue;
            start(level, v, b);
        }
    }

    /** Two folk sat down to a game at this board: free, grown, near, a friend with a friend if it can be. The game, or null. */
    @Nullable
    static Game start(ServerLevel level, Villages.Village v, Board b) {
        BlockPos post = b.pos().below();
        BlockPos[] seats = seats(level, post);
        if (seats == null) return null;
        LAST.put(b.pos().asLong(), level.getGameTime());
        List<VillageFolkEntity> free = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || PLAYING.containsKey(f.getUUID())) continue;
            if (!Families.free(f) || Families.busy(f) || Culture.role(f) != null || Sport.busy(f) || f.isHired()) continue;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD && f.onWatch()) continue;
            if (f.distanceToSqr(Vec3.atCenterOf(b.pos())) > 48.0 * 48.0) continue;
            free.add(f);
        }
        if (free.size() < 2) return null;
        long day = level.getDayTime() / 24000L;
        free.sort(java.util.Comparator.comparingInt(f -> Math.floorMod((int) (day * 2654435761L) ^ f.getUUID().hashCode(), 1 << 20)));
        VillageFolkEntity first = free.get(0), second = null;
        int warmest = Integer.MIN_VALUE;
        for (int i = 1; i < free.size(); i++) {
            int a = first.life().affinity(free.get(i).getUUID());
            if (a > warmest) { warmest = a; second = free.get(i); }
        }
        if (second == null) return null;
        Game g = begin(level, v.id(), b.pos(), b.where(), first, second, seats);
        FolkTalk.speak(first, FolkTalk.pick(level.getRandom(), "Fancy a game of draughts, " + second.displayNameCap() + "?",
            "Draughts? I'll be red.", "Come on, " + second.displayNameCap() + " — best of one."));
        return g;
    }

    /** A game begun between two folk: the pieces set out, the two to their seats (red at the board's red side). */
    static Game begin(ServerLevel level, UUID village, BlockPos board, String where, VillageFolkEntity red, VillageFolkEntity black, BlockPos[] seats) {
        BlockState st = level.getBlockState(board);
        Direction redSide = st.getBlock() instanceof DraughtsBoardBlock ? st.getValue(DraughtsBoardBlock.FACING) : Direction.SOUTH;
        BlockPos post = board.below();
        BlockPos redSeat = seats[0].equals(post.relative(redSide)) ? seats[0] : seats[1].equals(post.relative(redSide)) ? seats[1] : seats[0];
        BlockPos blackSeat = redSeat.equals(seats[0]) ? seats[1] : seats[0];
        Game g = new Game(board.immutable(), village, where, red.getUUID(), red.displayNameCap(), black.getUUID(), black.displayNameCap(),
            redSeat, blackSeat, false);
        g.started = level.getGameTime();
        g.nextAt = g.started + 100L;
        GAMES.put(board.asLong(), g);
        PLAYING.put(red.getUUID(), g);
        PLAYING.put(black.getUUID(), g);
        show(level, g, -1, -1);
        for (VillageFolkEntity f : new VillageFolkEntity[]{ red, black }) {
            f.clearQueue();
            f.getNavigation().stop();
        }
        Pastimes.LOG.info("[MCA-LEISURE] {} and {} sit down to draughts at the {} of {}", red.displayNameCap(), black.displayNameCap(), where,
            Villages.name(village));
        return g;
    }

    /** The board as the game has it, for everybody near to see. */
    static void show(ServerLevel level, Game g, int from, int to) {
        if (level.isLoaded(g.board) && level.getBlockEntity(g.board) instanceof DraughtsBoardBlockEntity be) {
            be.show(g.cells, from, to, g.redName, g.blackName);
        }
    }

    /** A move, when it is due and both are sat (or have had long enough to get there); the end of the game when it is over. */
    static void step(ServerLevel level, Game g, long now) {
        if (!standing(level, g.board)) {
            finish(level, g, -1, "the board was taken away");
            return;
        }
        if (g.challenge) {
            stepChallenge(level, g, now);
            return;
        }
        VillageFolkEntity red = live(level, g.red), black = live(level, g.black);
        if (red == null || black == null) {
            finish(level, g, -1, "one of them had to go");
            return;
        }
        boolean seated = sat(red, g.redSeat) && sat(black, g.blackSeat);
        if (seated) {
            g.seatedAt = now;
        } else if (g.seatedAt < 0 ? now - g.started > 900L : now - g.seatedAt > 400L) {
            finish(level, g, -1, "they were called away");          // never got to the table, or got up from it and did not come back
            return;
        }
        if (!seated || now < g.nextAt) return;
        move(level, g, g.toMove == RED ? red : black, now);
    }

    static boolean sat(VillageFolkEntity f, BlockPos seat) {
        return f.blockPosition().distSqr(seat) <= 2.0 && SEATED.contains(f.getUUID());
    }

    /** The side to move makes its move (as clever as it is): the board shown, a word now and then; or, with none, it has lost. */
    static void move(ServerLevel level, Game g, @Nullable VillageFolkEntity mover, long now) {
        RandomSource r = level.getRandom();
        int clever = mover != null ? cleverness(mover) : 6;
        Move m = choose(g.cells, g.toMove, clever, r);
        if (m == null) {
            finish(level, g, other(g.toMove), null);
            return;
        }
        byte piece = g.cells[m.from()];
        g.cells = apply(g.cells, m);
        g.plies++;
        show(level, g, m.from(), m.to());
        level.playSound(null, g.board, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.35F, 1.7F + r.nextFloat() * 0.2F);
        if (mover != null) {
            mover.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            mover.getLookControl().setLookAt(g.board.getX() + 0.5, g.board.getY() + 0.1, g.board.getZ() + 0.5);
            boolean crowned = !king(piece) && king(g.cells[m.to()]);
            if (m.taken().length >= 2 && r.nextInt(2) == 0) say(mover, FolkTalk.pick(r, "Two at once! Ha!", "And that one, and that one!"));
            else if (m.taken().length == 1 && r.nextInt(5) == 0) say(mover, FolkTalk.pick(r, "Got you!", "I'll have that, thank you.", "Snap!"));
            else if (crowned && r.nextInt(2) == 0) say(mover, FolkTalk.pick(r, "King me!", "Crown it, crown it!"));
            if (m.taken().length > 0) level.sendParticles(ParticleTypes.CRIT, g.board.getX() + 0.5, g.board.getY() + 0.3, g.board.getZ() + 0.5, 4,
                0.2, 0.05, 0.2, 0.05);
        }
        g.toMove = other(g.toMove);
        g.nextAt = now + (g.challenge ? 20L : 40L + r.nextInt(30));
        if (!g.challenge && g.plies >= LONGEST) {
            double red = material(g.cells, RED), black = material(g.cells, BLACK);
            finish(level, g, red > black ? RED : black > red ? BLACK : 0, null);
        }
    }

    private static void say(VillageFolkEntity f, String text) {
        FolkTalk.speak(f, text);
    }

    /**
     * The game over: RED or BLACK won (0 a draw; -1 left unfinished, {@code why}). The record kept, the winner's
     * spirits up, both the friendlier, a word from each; the gazette's line. Both get up from the table.
     */
    static void finish(ServerLevel level, Game g, int winner, @Nullable String why) {
        if (g.over) return;
        g.over = true;
        GAMES.remove(g.board.asLong(), g);
        PLAYING.remove(g.red, g);
        PLAYING.remove(g.black, g);
        VillageFolkEntity red = live(level, g.red), black = live(level, g.black);
        for (VillageFolkEntity f : new VillageFolkEntity[]{ red, black }) if (f != null) standUp(f);
        if (g.challenge) return;
        long day = level.getDayTime() / 24000L;
        if (winner < 0) {
            Pastimes.LOG.info("[MCA-LEISURE] the draughts game between {} and {} was left unfinished: {}", g.redName, g.blackName, why);
            return;
        }
        RandomSource r = level.getRandom();
        String where = g.where.equals("tavern") ? "at the tavern" : "in the park";
        if (winner == 0) {
            tally(g.village, g.red, 2);
            tally(g.village, g.black, 2);
            for (VillageFolkEntity f : new VillageFolkEntity[]{ red, black }) {
                if (f == null) continue;
                f.persona().remember(day, "I drew a game of draughts with " + g.nameOf(g.otherOf(f.getUUID())) + " " + where, 1);
            }
            if (red != null) say(red, FolkTalk.pick(r, "A draw! Same again tomorrow?", "Nothing in it. Good game."));
            Pastimes.news(g.village, day, "draughts:" + g.redName + " and " + g.blackName + " drew at draughts " + where + " (" + g.plies + " moves)");
        } else {
            UUID w = winner == RED ? g.red : g.black, l = g.otherOf(w);
            String wn = g.nameOf(w), ln = g.nameOf(l);
            tally(g.village, w, 0);
            tally(g.village, l, 1);
            WON.put(w, day);
            VillageFolkEntity wf = live(level, w), lf = live(level, l);
            if (wf != null) {
                wf.persona().remember(day, "I beat " + ln + " at draughts " + where, 2);
                say(wf, FolkTalk.pick(r, "And that's the game! Well played, " + ln + ".", "Ha! Crowned and cornered. My game!",
                    "Another one to me."));
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, wf.getX(), wf.getY() + 1.8, wf.getZ(), 5, 0.3, 0.2, 0.3, 0.0);
            }
            if (lf != null) {
                lf.persona().remember(day, ln.isEmpty() ? "I lost at draughts" : wn + " beat me at draughts " + where, 1);
                if (r.nextInt(2) == 0) lf.sayLater(FolkTalk.pick(r, "Again tomorrow. I'll have you then.", "Well played. You had me from the middle.",
                    "Hmph. Lucky."), 30);
            }
            Pastimes.news(g.village, day, "draughts:" + wn + " beat " + ln + " at draughts " + where + " (" + g.plies + " moves)");
            Pastimes.LOG.info("[MCA-LEISURE] {} beat {} at draughts at the {} in {} moves", wn, ln, g.where, g.plies);
        }
        // A game shared: the two the friendlier, the more for a close one.
        int close = Math.abs(material(g.cells, RED) - material(g.cells, BLACK)) <= 2.0 ? 1 : 0;
        if (red != null) red.life().feel(g.black, g.blackName, 3 + close);
        if (black != null) black.life().feel(g.red, g.redName, 3 + close);
    }

    // ------------------------------------------------------------------ the folk's part (Pastimes.hold)

    /** A folk in a game: to its seat, sat down facing the board, watching it. What it is doing, or null. */
    @Nullable
    static String hold(ServerLevel level, VillageFolkEntity f, UUID village, long t, long day) {
        Game g = PLAYING.get(f.getUUID());
        if (g == null || g.over || g.challenge && !g.black.equals(f.getUUID())) {
            if (SEATED.contains(f.getUUID())) standUp(f);
            return null;
        }
        BlockPos seat = g.seatOf(f.getUUID());
        String with = g.nameOf(g.otherOf(f.getUUID()));
        String where = g.where.equals("tavern") ? "at the tavern" : g.where.equals("park") ? "in the park" : "";
        if (!seat(level, seat)) {
            // A board with no bench by it (a player's, on a post): it stands to play.
            if (f.blockPosition().distSqr(seat) > 2.0 * 2.0 && (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 60)) {
                f.walkTo(seat, 0.9D);
                f.hobbyTick = f.tickCount;
            }
            f.getLookControl().setLookAt(g.board.getX() + 0.5, g.board.getY() + 0.1, g.board.getZ() + 0.5);
            return "playing draughts with " + with;
        }
        if (!SEATED.contains(f.getUUID())) {
            if (f.blockPosition().distSqr(seat) > 1.5 * 1.5 || Math.abs(f.getY() - seat.getY()) > 1.2) {
                if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 60 || f.tickCount < f.hobbyTick) {
                    f.walkTo(seat, 0.9D);
                    f.hobbyTick = f.tickCount;
                }
                return "off to play draughts with " + with + " " + where;
            }
            sit(f, seat, g.board);
        } else {
            f.setPose(Pose.SITTING);
            Vec3 spot = spot(seat, g.board);
            if (f.position().distanceToSqr(spot) > 0.3) f.moveTo(spot.x, spot.y, spot.z, f.getYRot(), 0.0F);
        }
        f.getLookControl().setLookAt(g.board.getX() + 0.5, g.board.getY() + 0.1, g.board.getZ() + 0.5);
        int mine = (int) material(g.cells, g.red.equals(f.getUUID()) ? RED : BLACK), theirs = (int) material(g.cells, g.red.equals(f.getUUID()) ? BLACK : RED);
        return "playing draughts with " + with + " " + where + " (" + g.plies + " moves; " + mine + " pieces to " + theirs + ")";
    }

    /** Where a sitter sits on its seat, a little toward the table. */
    static Vec3 spot(BlockPos seat, BlockPos board) {
        double dx = Math.signum(board.getX() - seat.getX()), dz = Math.signum(board.getZ() - seat.getZ());
        return new Vec3(seat.getX() + 0.5 + dx * 0.15, seat.getY() + 0.5, seat.getZ() + 0.5 + dz * 0.15);
    }

    static void sit(VillageFolkEntity f, BlockPos seat, BlockPos board) {
        f.getNavigation().stop();
        Vec3 spot = spot(seat, board);
        float yaw = (float) (Math.atan2(board.getZ() - seat.getZ(), board.getX() - seat.getX()) * (180.0 / Math.PI)) - 90.0F;
        f.moveTo(spot.x, spot.y, spot.z, yaw, 0.0F);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.setPose(Pose.SITTING);
        SEATED.add(f.getUUID());
    }

    static void standUp(VillageFolkEntity f) {
        if (SEATED.remove(f.getUUID()) && f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
    }

    // ------------------------------------------------------------------ a player's challenge

    /**
     * A player's challenge at a board: the nearest of the town's folk sits down with it to a short game (a dozen moves
     * on the board), then it is settled by the folk's skill and a roll of the dice, and said aloud. What the player is told.
     */
    public static String challenge(ServerLevel level, BlockPos board, Player p) {
        Game on = GAMES.get(board.asLong());
        if (on != null) return "A game's on: " + on.redName + " against " + on.blackName + ".";
        VillageFolkEntity best = null;
        double bd = 8.0 * 8.0;
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, new net.minecraft.world.phys.AABB(board).inflate(8.0),
                x -> x.isAlive() && !x.isBaby() && !x.isSleeping() && x.ownerId() != null && !x.isShowcase() && !PLAYING.containsKey(x.getUUID()))) {
            double d = f.distanceToSqr(Vec3.atCenterOf(board));
            if (d < bd) { bd = d; best = f; }
        }
        if (best == null) return "There's nobody about to play you. Bring a friend of the town's over.";
        Villages.Village v = Villages.get(best.ownerId());
        if (v == null) return "";
        BlockPos[] seats = seats(level, board.below());
        BlockState st = level.getBlockState(board);
        Direction redSide = st.getBlock() instanceof DraughtsBoardBlock ? st.getValue(DraughtsBoardBlock.FACING) : Direction.SOUTH;
        BlockPos near = board.relative(redSide).below(), far = board.relative(redSide.getOpposite()).below();
        if (seats != null) {
            near = seats[0];
            far = seats[1];
        }
        Game g = new Game(board.immutable(), v.id(), "board", p.getUUID(), p.getName().getString(), best.getUUID(), best.displayNameCap(), near, far, true);
        g.started = level.getGameTime();
        g.nextAt = g.started + 30L;
        GAMES.put(board.asLong(), g);
        PLAYING.put(best.getUUID(), g);
        best.clearQueue();
        best.getNavigation().stop();
        if (seats != null && best.blockPosition().distSqr(far) <= 9.0 * 9.0) sit(best, far, board);
        else best.getLookControl().setLookAt(board.getX() + 0.5, board.getY(), board.getZ() + 0.5);
        show(level, g, -1, -1);
        FolkTalk.speak(best, FolkTalk.pick(level.getRandom(), "A game? Go on, then. You're red.", "Draughts with " + p.getName().getString()
            + "? Set them up!", "I'll warn you, I'm good at this."));
        return best.displayNameCap() + " sits down to play you. You're red.";
    }

    /** A challenge's moves, a second apart, then the dice. */
    private static void stepChallenge(ServerLevel level, Game g, long now) {
        VillageFolkEntity folk = live(level, g.black);
        Player p = level.getPlayerByUUID(g.red);
        if (folk == null || p == null || p.distanceToSqr(Vec3.atCenterOf(g.board)) > 16.0 * 16.0) {
            if (folk != null) say(folk, "Oh — gone, have they? Never mind.");
            finish(level, g, -1, "the player left the table");
            return;
        }
        if (now < g.nextAt) return;
        if (g.plies >= SHORT_GAME || moves(g.cells, g.toMove).isEmpty()) {
            settle(level, g, folk, p);
            return;
        }
        move(level, g, g.toMove == BLACK ? folk : null, now);
    }

    /**
     * The challenge settled: the folk's dice and its skill against the player's dice and the board as it stands; the
     * result said aloud, the folk's record kept, and it thinks the better of a player who sat down with it.
     */
    static int settle(ServerLevel level, Game g, VillageFolkEntity folk, Player p) {
        RandomSource r = level.getRandom();
        int fd = 1 + r.nextInt(6), pd = 1 + r.nextInt(6);
        int skill = cleverness(folk) / 3;
        int board = (int) Math.round(material(g.cells, RED) - material(g.cells, BLACK));
        int folkScore = fd + skill, playerScore = pd + 2 + Math.max(-2, Math.min(2, board));
        long day = level.getDayTime() / 24000L;
        String name = p.getName().getString();
        int result;
        if (folkScore > playerScore) {
            result = BLACK;
            tally(g.village, folk.getUUID(), 0);
            WON.put(folk.getUUID(), day);
            say(folk, FolkTalk.pick(r, "My game! Better luck next time, " + name + ".", "Ha! You'll have to practise, " + name + ".",
                "That's me won. Another, " + name + "?"));
            folk.persona().remember(day, "I beat " + name + " at draughts", 2);
        } else if (playerScore > folkScore) {
            result = RED;
            tally(g.village, folk.getUUID(), 1);
            say(folk, FolkTalk.pick(r, "You've beaten me, " + name + ", fair and square!", "Well, I never. Your game, " + name + ".",
                "Beaten! I'll want a rematch."));
            folk.persona().remember(day, name + " beat me at draughts", 2);
        } else {
            result = 0;
            tally(g.village, folk.getUUID(), 2);
            say(folk, FolkTalk.pick(r, "A draw! Nothing between us, " + name + ".", "Honours even. Good game."));
        }
        folk.persona().feelFor(p.getUUID(), name, 2);
        p.sendSystemMessage(Component.literal("Draughts with " + folk.displayNameCap() + ": you rolled " + pd + " (and the board "
            + (board >= 0 ? "+" : "") + Math.max(-2, Math.min(2, board)) + "), " + folk.displayNameCap() + " rolled " + fd + " and its skill +" + skill
            + " — " + (result == RED ? "you win!" : result == BLACK ? folk.displayNameCap() + " wins." : "a draw.")).withStyle(ChatFormatting.GOLD));
        finish(level, g, -1, null);
        return result;
    }

    // ------------------------------------------------------------------ the fair's tournament (Fair.script)

    /**
     * The draughts tournament at the fair: the town's best four players (by games won; any four of its sharpest if it has
     * not played yet), two semi-finals and a final, each a game played out move by move (playOut); the champion in the
     * chronicle, with a purse out of the treasury.
     */
    static void tournament(ServerLevel level, Villages.Village v, List<Assemblies.Line> s, RandomSource r, long d) {
        UUID id = v.id();
        List<VillageFolkEntity> all = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && !f.isBaby() && f.isAlive() && !f.isShowcase()) all.add(f);
        if (all.size() < 4 || boards(id).isEmpty() && Market.stock(level, id, x -> x.is(LeisureItems.DRAUGHTS_BOARD_ITEM.get())) <= 0) return;
        all.sort((a, b) -> {
            int[] ra = record(id, a.getUUID()), rb = record(id, b.getUUID());
            if (ra[0] != rb[0]) return Integer.compare(rb[0], ra[0]);
            return Integer.compare(cleverness(b), cleverness(a));
        });
        List<VillageFolkEntity> four = all.subList(0, 4);
        VillageFolkEntity a1 = four.get(0), a2 = four.get(3), b1 = four.get(1), b2 = four.get(2);
        VillageFolkEntity f1 = winner(a1, a2, r), f2 = winner(b1, b2, r);
        VillageFolkEntity champ = winner(f1, f2, r), runner = champ == f1 ? f2 : f1;
        s.add(new Assemblies.Line(null, "And now the draughts tournament! " + a1.displayNameCap() + ", " + b1.displayNameCap() + ", "
            + b2.displayNameCap() + " and " + a2.displayNameCap() + " at the boards.", '!', null));
        s.add(new Assemblies.Line(null, "In the first round, " + f1.displayNameCap() + " beat " + (f1 == a1 ? a2 : a1).displayNameCap() + ", and "
            + f2.displayNameCap() + " beat " + (f2 == b1 ? b2 : b1).displayNameCap() + ".", '?', null));
        s.add(new Assemblies.Line(null, "The final: " + champ.displayNameCap() + " against " + runner.displayNameCap() + " — and "
            + champ.displayNameCap() + " takes it! Our draughts champion!", '!', () -> crown(level, v, champ, runner, d)));
    }

    /** One of two, by a game played out between them. */
    private static VillageFolkEntity winner(VillageFolkEntity a, VillageFolkEntity b, RandomSource r) {
        int[] res = playOut(cleverness(a), cleverness(b), r);
        if (res[0] == RED) return a;
        if (res[0] == BLACK) return b;
        return r.nextBoolean() ? a : b;                                     // a draw: the toss of a coin
    }

    /** The champion crowned: the purse, the chronicle, its pride, the gazette. */
    static void crown(ServerLevel level, Villages.Village v, VillageFolkEntity champ, VillageFolkEntity runner, long d) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        int coins = Ledger.takeCoins(id, PURSE);
        if (coins > 0) {
            champ.earn(coins);
            Economy.spent(id, coins);
        }
        tally(id, champ.getUUID(), 0);
        tally(id, runner.getUUID(), 1);
        WON.put(champ.getUUID(), day);
        champ.persona().remember(day, "I won the draughts tournament at the fair", 5);
        runner.persona().remember(day, "I came second in the draughts tournament at the fair", 2);
        Villages.tell(id, day, champ.displayNameCap() + " won the draughts tournament at the fair, beating " + runner.displayNameCap() + " in the final");
        Pastimes.news(id, day, "draughts:" + champ.displayNameCap() + " is the fair's draughts champion" + (coins > 0 ? ", and " + coins + " coins the richer" : ""));
        FolkTalk.speak(champ, FolkTalk.pick(level.getRandom(), "Champion! I can hardly believe it!", "All those evenings at the tavern paid off!"));
    }

    // ------------------------------------------------------------------ spirits, the card, the books

    static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Long w = WON.get(f.getUUID());
        if (w == null || day - w > 1) return m;
        why.add(new Object[]{ "draughts", 4 });
        return m + 4;
    }

    @Nullable
    static String card(VillageFolkEntity f) {
        if (f.ownerId() == null) return null;
        Game g = PLAYING.get(f.getUUID());
        int[] r = record(f.ownerId(), f.getUUID());
        if (g == null && r[0] + r[1] + r[2] == 0) return null;
        String rec = "draughts: " + r[0] + " won, " + r[1] + " lost" + (r[2] > 0 ? ", " + r[2] + " drawn" : "");
        return g == null ? rec : rec + " (playing " + g.nameOf(g.otherOf(f.getUUID())) + " now)";
    }

    static List<String> gazette(UUID village, long day) {
        List<String> out = new ArrayList<>();
        for (String l : Pastimes.newsOf(village, day - 1, "draughts:")) {
            out.add(Character.toUpperCase(l.charAt(0)) + l.substring(1) + ".");
            if (out.size() >= 2) break;
        }
        return out;
    }

    static String status(ServerLevel level, Villages.Village v) {
        List<String> bits = new ArrayList<>();
        for (Board b : boards(v.id())) bits.add("a board " + (b.where().equals("tavern") ? "on the tavern's table" : "in the park") + " at "
            + b.pos().toShortString());
        for (Game g : GAMES.values()) if (g.village.equals(v.id())) bits.add(g.redName + " against " + g.blackName + " now, " + g.plies + " moves in");
        if (bits.isEmpty()) bits.add("no board set out yet" + (wanted(level, v) > 0 ? " (the town wants " + wanted(level, v) + ")" : ""));
        return String.join("; ", bits);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a game begun now at this board between these two (their seats the table's), or null with no seats. */
    @Nullable
    public static String beginForTests(ServerLevel level, BlockPos board, VillageFolkEntity red, VillageFolkEntity black) {
        BlockPos[] seats = seats(level, board.below());
        if (seats == null || red.ownerId() == null) return null;
        Game g = begin(level, red.ownerId(), board, "tavern", red, black, seats);
        return g.redName + " v " + g.blackName;
    }

    /** Tests: both sat down at their seats now, and the game's moves made one after another to its end (at most this many). */
    public static int playForTests(ServerLevel level, BlockPos board, int most) {
        Game g = GAMES.get(board.asLong());
        if (g == null) return -1;
        VillageFolkEntity red = live(level, g.red), black = live(level, g.black);
        if (red != null) sit(red, g.redSeat, g.board);
        if (black != null) sit(black, g.blackSeat, g.board);
        int n = 0;
        while (!g.over && n < most) {
            g.nextAt = 0;
            g.seatedAt = level.getGameTime();
            step(level, g, level.getGameTime());
            n++;
        }
        return g.plies;
    }

    /** Tests: the game at this board: {plies, whose move, over (1/0), red's pieces, black's}, or null. */
    @Nullable
    public static int[] gameForTests(BlockPos board) {
        Game g = GAMES.get(board.asLong());
        if (g == null) return null;
        return new int[]{ g.plies, g.toMove, g.over ? 1 : 0, (int) material(g.cells, RED), (int) material(g.cells, BLACK) };
    }

    /** Tests: whole games played out between players of these cleverness, so many times: {red's wins, black's wins, draws}. */
    public static int[] seriesForTests(int redClever, int blackClever, int games, long seed) {
        RandomSource r = RandomSource.create(seed);
        int[] out = new int[3];
        for (int i = 0; i < games; i++) {
            int[] res = i % 2 == 0 ? playOut(redClever, blackClever, r) : flip(playOut(blackClever, redClever, r));
            out[res[0] == RED ? 0 : res[0] == BLACK ? 1 : 2]++;
        }
        return out;
    }

    private static int[] flip(int[] res) {
        return new int[]{ res[0] == RED ? BLACK : res[0] == BLACK ? RED : 0, res[1] };
    }

    /** Tests: how sharp a player this folk is. */
    public static int clevernessForTests(VillageFolkEntity f) {
        return cleverness(f);
    }

    /** Tests: this folk's record {won, lost, drawn}. */
    public static int[] recordForTests(UUID village, UUID folk) {
        return record(village, folk);
    }

    /** Tests: a board set out at this post for the town now, out of the stores. */
    public static boolean placeForTests(ServerLevel level, Villages.Village v, BlockPos post, String where) {
        return place(level, v, post, where);
    }

    /** Tests: the town's look at setting its boards out, now. */
    public static void setUpForTests(ServerLevel level, Villages.Village v) {
        setUp(level, v);
    }

    /** Tests: the tavern's table for a board. */
    @Nullable
    public static BlockPos tavernTableForTests(ServerLevel level, Ledger.Building tav) {
        return tavernTable(level, tav);
    }

    /** Tests: a challenge's game run to its end now (its moves, then the dice). What the dice said: RED (the player), BLACK, or 0. */
    public static int challengeOutForTests(ServerLevel level, BlockPos board) {
        Game g = GAMES.get(board.asLong());
        if (g == null || !g.challenge) return -1;
        VillageFolkEntity folk = live(level, g.black);
        Player p = level.getPlayerByUUID(g.red);
        if (folk == null || p == null) return -1;
        while (g.plies < SHORT_GAME && !moves(g.cells, g.toMove).isEmpty()) move(level, g, g.toMove == BLACK ? folk : null, level.getGameTime());
        return settle(level, g, folk, p);
    }

    /** Tests: the tournament's lines at the fair now, and their effects. */
    public static List<String> tournamentForTests(ServerLevel level, Villages.Village v) {
        List<Assemblies.Line> s = new ArrayList<>();
        tournament(level, v, s, level.getRandom(), level.getDayTime() / 24000L);
        List<String> out = new ArrayList<>();
        for (Assemblies.Line l : s) {
            out.add(l.text());
            if (l.effect() != null) l.effect().run();
        }
        return out;
    }
}
