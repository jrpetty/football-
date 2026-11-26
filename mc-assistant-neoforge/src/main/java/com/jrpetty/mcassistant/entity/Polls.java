package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * [featcivic] An election you can watch happen (Elections decides who wins; this is how the town sees it).
 *
 * <p><b>The campaign.</b> When an election is called each candidate's posters go up round the town: a sign with its name,
 * the office, what it stands for and its party (a democracy's) beside a banner in its colour, by the board, by the well
 * and along the streets. Each campaign day each candidate speaks in the square at its own hour (a rally: Assemblies,
 * Kind.RALLY): the folk who lean its way and the undecided gather to listen, nod and call out ("Hear, hear.") or shake
 * their heads ("And who pays for that?"), and now and then somebody heckles and gets an answer.
 *
 * <p><b>Polling day.</b> The polling station is the ballot box ({@code mc_assistant:ballot_box}) set out before the board
 * or the hall. Each voter (the form's franchise: Government.voters) walks there at its own hour, joins the queue, steps up
 * when its turn comes, and drops its folded ballot through the slot (a sheet of paper you can see go in, a puff and the
 * rustle of paper). A floating notice over the box keeps the turnout: so many of so many have voted.
 *
 * <p><b>The count.</b> At dusk the town gathers at the board (Elections.script). The box is opened and the ballots are
 * read out one at a time: the notice over the box counts them up, a bar for each candidate in its colour. Then the bell,
 * the winner, its speech, a few of the town's rockets if it has any put by, the chain of office handed from the one who
 * led to the one who leads, and the losers' posters taken down (the winner's the next morning).
 *
 * <p><b>The edges.</b> Nobody stands: the one in office stays on as caretaker until the next, told on the board. A tie
 * keeps the one in office; between two others it is settled by drawing lots, and the chronicle says so. A citizen may
 * vote at the box or the Town Hall (the ballot screen) from the day the election is called, so a player away on polling
 * day votes early by post; one who did neither simply has no vote counted. A candidate away stands all the same.
 */
public final class Polls {

    private Polls() {}

    public static final String TAG = "mca_polls";
    /** How long a ballot takes to go in, in ticks (the step up, the arm, the paper through the slot). */
    static final int DROP_TICKS = 18;
    /** Ticks between one ballot read out and the next, at the count. */
    static final int COUNT_EVERY = 8;

    /** The queue at each town's box: folk in the order they joined. */
    private static final Map<UUID, List<UUID>> QUEUE = new ConcurrentHashMap<>();
    /** A folk at the slot: the tick its ballot started going in. */
    private static final Map<UUID, Long> DROPPING = new ConcurrentHashMap<>();
    /** When each folk joined its queue (game time). */
    private static final Map<UUID, Long> JOINED = new ConcurrentHashMap<>();
    /** The floating notice over each town's box. */
    private static final Map<UUID, UUID> NOTICE = new ConcurrentHashMap<>();
    /** Each town's count, ballot by ballot, while the evening's gathering lasts. */
    private static final Map<UUID, Count> COUNTING = new ConcurrentHashMap<>();
    /** The towns whose stale notices have been swept this session. */
    private static final java.util.Set<UUID> SWEPT = ConcurrentHashMap.newKeySet();

    /** The count, ballot by ballot: the names, the final votes, each one's colour, how many shown so far, the order they come out. */
    static final class Count {
        final List<String> names = new ArrayList<>();
        final List<Integer> finals = new ArrayList<>();
        final List<DyeColor> colours = new ArrayList<>();
        int[] shown;
        final List<Integer> order = new ArrayList<>();
        int next;
        long lastAt;
        String winner = "";
        String office = "";
        boolean finished;
    }

    public static void resetForTests() {
        QUEUE.clear();
        DROPPING.clear();
        JOINED.clear();
        NOTICE.clear();
        COUNTING.clear();
        SWEPT.clear();
    }

    // ------------------------------------------------------------------ the polling station

    /** The town's ballot box, set out if it has none (and {@code place}); null if there is nowhere to put it. */
    @Nullable
    public static BlockPos station(ServerLevel level, Villages.Village v, boolean place) {
        UUID id = v.id();
        BlockPos at = Regimes.parse(Ledger.note(id, "polls.box"));
        if (at != null && level.isLoaded(at) && level.getBlockState(at).is(McAssistantMod.BALLOT_BOX.get())) return at;
        if (at != null && !level.isLoaded(at)) return at;                     // unloaded: trust the books
        if (!place) return null;
        BlockPos spot = boxSpot(level, v);
        if (spot == null) return null;
        Direction face = queueDirection(level, v);
        level.setBlock(spot, McAssistantMod.BALLOT_BOX.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, face), Block.UPDATE_ALL);
        Ledger.note(id, "polls.box", spot.getX() + "," + spot.getY() + "," + spot.getZ());
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.0);
        return spot;
    }

    /** A ballot box set down by hand in a town: that is its polling station now (BallotBoxBlock). */
    public static void placedByHand(ServerLevel level, BlockPos at) {
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE);
        if (v != null) Ledger.note(v.id(), "polls.box", at.getX() + "," + at.getY() + "," + at.getZ());
    }

    /** Before the hall's door, else three steps out from the board to its side, else by the town's heart. */
    @Nullable
    static BlockPos boxSpot(ServerLevel level, Villages.Village v) {
        BlockPos board = VillageBoards.lectern(v.id());
        Direction face = board != null && VillageBoards.facingOf(v.id()) != null ? VillageBoards.facingOf(v.id()) : Direction.SOUTH;
        BlockPos base = board != null ? board : v.centre();
        Direction side = face.getCounterClockWise();
        for (int[] o : new int[][]{ { 3, 4 }, { 3, -4 }, { 4, 5 }, { 4, -5 }, { 2, 6 }, { 5, 3 }, { 5, -3 }, { 6, 0 } }) {
            BlockPos p = groundAt(level, base.relative(face, o[0]).relative(side, o[1]));
            if (p != null && roomForQueue(level, p, face)) return p;
        }
        return groundAt(level, base.relative(face, 3));
    }

    static boolean roomForQueue(ServerLevel level, BlockPos box, Direction face) {
        int ok = 0;
        for (int i = 2; i < 6; i++) if (groundAt(level, box.relative(face, i)) != null) ok++;
        return ok >= 3;
    }

    /** The way the queue runs out from the box: the box's own facing, else toward the board's front. */
    static Direction queueDirection(ServerLevel level, Villages.Village v) {
        BlockPos at = Regimes.parse(Ledger.note(v.id(), "polls.box"));
        if (at != null && level.isLoaded(at)) {
            BlockState s = level.getBlockState(at);
            if (s.hasProperty(HorizontalDirectionalBlock.FACING)) return s.getValue(HorizontalDirectionalBlock.FACING);
        }
        Direction face = VillageBoards.facingOf(v.id());
        return face != null ? face : Direction.SOUTH;
    }

    /** The place in the queue: the first a step out from the box's front, the rest behind, one to a block. */
    static BlockPos queueSpot(ServerLevel level, BlockPos box, Direction dir, int i) {
        BlockPos col = box.relative(dir, 1 + Math.min(i, 10));
        if (i > 10) col = col.relative(dir.getClockWise(), (i - 10) % 3);
        BlockPos g = groundAt(level, col);
        return g != null ? g : col;
    }

    /** Where the candidates speak: before the board (the gatherings' own place), else the town's heart. */
    public static BlockPos speechSpot(ServerLevel level, Villages.Village v) {
        BlockPos board = VillageBoards.lectern(v.id());
        return board != null ? board : v.centre();
    }

    /** Open ground at a column: a sturdy floor, the block and the one above it free. Null if none within four up or down. */
    @Nullable
    public static BlockPos groundAt(ServerLevel level, BlockPos col) {
        if (!level.isLoaded(col)) return null;
        for (int dy = 4; dy >= -4; dy--) {
            BlockPos p = col.offset(0, dy, 0);
            BlockState here = level.getBlockState(p), up = level.getBlockState(p.above()), below = level.getBlockState(p.below());
            if (!here.isAir() || !up.isAir()) continue;
            if (!below.isFaceSturdy(level, p.below(), Direction.UP) || !below.getFluidState().isEmpty()) continue;
            if (below.getBlock() instanceof net.minecraft.world.level.block.DirtPathBlock) continue;     // not on the road itself
            return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ the campaign: posters

    /** The election called (Elections.call): the box set out, the posters up, the notice over the box. */
    static void called(ServerLevel level, Villages.Village v, Elections.Campaign c, long day) {
        if (Identity.neutral() && !visualForTests) return;
        UUID id = v.id();
        BlockPos box = station(level, v, true);
        tearDown(level, v, k -> true, null);                                  // last time's, if any were left
        int i = 0;
        for (Elections.Candidate k : c.candidates) placePosters(level, v, k, colourOf(id, k, i++), c.candidates.size());
        if (box != null) notice(level, v, box, turnoutText(v.id(), c));
        Villages.tell(id, day, "posters went up round the town for the election on day " + c.voteDay
            + (box != null ? "; the ballot box was set out before the " + (Villages.builtAt(id, "hall") != null ? "hall" : "board") : ""));
    }

    /** Tests (and the pictures): the posters and the box even in a test's plain town. */
    static volatile boolean visualForTests;

    public static void visualForTests(boolean on) {
        visualForTests = on;
    }

    /** A candidate's colour: its party's in a democracy, else one of the poster colours by its place on the list. */
    public static DyeColor colourOf(UUID village, Elections.Candidate k, int i) {
        Factions.Party p = Government.of(village) == Government.Form.DEMOCRACY ? Factions.partyOfCandidate(village, k.id()) : null;
        if (p != null) return p.colour;
        DyeColor[] list = { DyeColor.RED, DyeColor.BLUE, DyeColor.GREEN, DyeColor.YELLOW, DyeColor.PURPLE, DyeColor.CYAN };
        return list[Math.floorMod(i, list.length)];
    }

    /** Two or three posters for one candidate: a sign and a banner, in a ring round the board and out along the streets. */
    static int placePosters(ServerLevel level, Villages.Village v, Elections.Candidate k, DyeColor colour, int of) {
        UUID id = v.id();
        BlockPos board = speechSpot(level, v);
        Random r = new Random(Objects.hash(id, k.id(), "posters"));
        int want = of <= 2 ? 3 : 2, placed = 0;
        List<String> saved = posterList(id);
        for (int tries = 0; tries < 40 && placed < want; tries++) {
            double ang = r.nextDouble() * Math.PI * 2;
            int rad = 5 + r.nextInt(placed == 0 ? 5 : 22);
            BlockPos col = board.offset((int) Math.round(Math.cos(ang) * rad), 0, (int) Math.round(Math.sin(ang) * rad));
            BlockPos signAt = groundAt(level, col);
            if (signAt == null || Math.abs(signAt.getY() - board.getY()) > 6 || nearPoster(saved, signAt)) continue;
            Direction toBoard = Direction.getNearest(board.getX() - signAt.getX(), 0, board.getZ() - signAt.getZ());
            if (toBoard.getAxis() == Direction.Axis.Y) toBoard = Direction.NORTH;
            BlockPos bannerAt = groundAt(level, signAt.relative(toBoard.getClockWise()));
            if (bannerAt == null || Math.abs(bannerAt.getY() - signAt.getY()) > 1) continue;
            level.setBlock(signAt, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(toBoard)), Block.UPDATE_ALL);
            if (level.getBlockEntity(signAt) instanceof SignBlockEntity s) {
                String office = Government.title(id);
                Factions.Party p = Government.of(id) == Government.Form.DEMOCRACY ? Factions.partyOfCandidate(id, k.id()) : null;
                SignText text = new SignText()
                    .setMessage(0, Component.literal(fit(k.name().toUpperCase(java.util.Locale.ROOT))).withStyle(ChatFormatting.BOLD))
                    .setMessage(1, Component.literal(fit("for " + office)))
                    .setMessage(2, Component.literal(fit(k.platform().word)))
                    .setMessage(3, Component.literal(fit(p != null ? p.shortName : Hustings.isPlayer(id, k.id()) ? "independent" : k.platform().type)))
                    .setColor(colour == DyeColor.WHITE ? DyeColor.BLACK : colour).setHasGlowingText(colour == DyeColor.BLUE || colour == DyeColor.PURPLE);
                s.setText(text, true);
                s.setText(text, false);
                s.setWaxed(true);
            }
            level.setBlock(bannerAt, BannerBlock.byColor(colour).defaultBlockState()
                .setValue(net.minecraft.world.level.block.BannerBlock.ROTATION, RotationSegment.convertToSegment(toBoard)), Block.UPDATE_ALL);
            level.playSound(null, signAt, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.7F, 1.0F);
            saved.add(k.id() + "@" + key(signAt) + "@" + key(bannerAt));
            placed++;
        }
        savePosters(id, saved);
        return placed;
    }

    static boolean nearPoster(List<String> saved, BlockPos p) {
        for (String s : saved) {
            String[] q = s.split("@");
            if (q.length < 3) continue;
            BlockPos a = Regimes.parse(q[1]), b = Regimes.parse(q[2]);
            if (a != null && a.distManhattan(p) < 4 || b != null && b.distManhattan(p) < 3) return true;
        }
        return false;
    }

    static String fit(String s) {
        return s.length() > 15 ? s.substring(0, 15) : s;
    }

    static String key(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    static List<String> posterList(UUID village) {
        String s = Ledger.note(village, "polls.posters");
        List<String> out = new ArrayList<>();
        if (s != null && !s.isEmpty()) for (String x : s.split(";")) if (!x.isEmpty()) out.add(x);
        return out;
    }

    static void savePosters(UUID village, List<String> list) {
        Ledger.note(village, "polls.posters", String.join(";", list));
    }

    /** How many posters stand for each candidate (for the board and the tests). */
    public static Map<UUID, Integer> posters(UUID village) {
        Map<UUID, Integer> out = new HashMap<>();
        for (String s : posterList(village)) {
            String[] q = s.split("@");
            try { out.merge(UUID.fromString(q[0]), 1, Integer::sum); } catch (RuntimeException ignored) { }
        }
        return out;
    }

    /**
     * Posters taken down (those whose candidate the test passes), only where our sign and banner still stand: a puff of
     * wood and wool, and the rustle. {@code why}: a line for the chronicle, or null for none. Returns how many came down.
     */
    static int tearDown(ServerLevel level, Villages.Village v, Predicate<UUID> which, @Nullable String why) {
        UUID id = v.id();
        List<String> kept = new ArrayList<>();
        int down = 0;
        for (String s : posterList(id)) {
            String[] q = s.split("@");
            UUID cand;
            try { cand = UUID.fromString(q[0]); } catch (RuntimeException e) { continue; }
            if (!which.test(cand)) { kept.add(s); continue; }
            for (int i = 1; i < q.length; i++) {
                BlockPos p = Regimes.parse(q[i]);
                if (p == null || !level.isLoaded(p)) continue;
                BlockState st = level.getBlockState(p);
                if (st.getBlock() instanceof StandingSignBlock || st.getBlock() instanceof BannerBlock) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), p.getX() + 0.5, p.getY() + 0.8, p.getZ() + 0.5, 18, 0.25, 0.4, 0.25, 0.05);
                    level.playSound(null, p, i == 1 ? SoundEvents.WOOD_BREAK : SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            down++;
        }
        savePosters(id, kept);
        if (why != null && down > 0) Villages.tell(id, level.getDayTime() / 24000L, down + " of " + why + " came down");
        return down;
    }

    /** Every poster of the town down (a change of government: the old order's posters torn down). */
    static int tearDownAll(ServerLevel level, Villages.Village v, String why) {
        return tearDown(level, v, k -> true, why);
    }

    // ------------------------------------------------------------------ polling day: the queue and the slot

    /**
     * A folk whose hour to vote has come (Elections.goVote): to the queue at the box, up to the slot in its turn, and its
     * ballot in. True while it is about it; false if the town has no box (Elections walks it to the board as of old).
     */
    static boolean vote(VillageFolkEntity f, ServerLevel level, Elections.Campaign c, long since) {
        if (Identity.neutral() && !visualForTests) return false;             // a test's plain town votes at the board, as ever
        Villages.Village v = Villages.get(c.village);
        if (v == null) return false;
        BlockPos box = station(level, v, true);
        if (box == null || !level.isLoaded(box)) return false;
        UUID me = f.getUUID(), id = v.id();
        long now = level.getGameTime();
        List<UUID> q = QUEUE.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
        q.removeIf(u -> c.ballots.containsKey(u) || !JOINED.containsKey(u) || now - JOINED.get(u) > 4000L);
        if (!q.contains(me)) {
            q.add(me);
            JOINED.put(me, now);
        }
        int i = q.indexOf(me);
        Direction dir = queueDirection(level, v);
        BlockPos spot = queueSpot(level, box, dir, i);
        f.hobbyNow = i == 0 ? "voting" : "queuing to vote";
        f.lastLeisureTick = f.tickCount;
        double dx = f.getX() - (spot.getX() + 0.5), dz = f.getZ() - (spot.getZ() + 0.5);
        boolean there = dx * dx + dz * dz <= (i == 0 ? 0.8 * 0.8 : 1.3 * 1.3) && Math.abs(f.getY() - spot.getY()) < 2.5;
        if (!there) {
            if (f.getNavigation().isDone() || f.tickCount % 30 == 0) f.walkTo(spot, i == 0 ? 0.7D : 0.85D);
            if (now - JOINED.get(me) > 3600L) {                                // could not reach the box: its vote goes by a neighbour
                Elections.cast(level, c, f, false);
                leaveQueue(id, me);
                return false;
            }
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(box.getX() + 0.5, box.getY() + 0.8, box.getZ() + 0.5);
        if (i != 0) {
            if (f.tickCount % 120 == 0 && level.getRandom().nextInt(5) == 0) FolkTalk.speak(f, FolkTalk.pick(level.getRandom(),
                "Busy today.", "Won't be long.", "Good turnout, this.", "I know who I'm voting for.", "Hm. Slow queue.", "My turn soon."));
            return true;
        }
        Long started = DROPPING.get(me);
        if (started == null) {
            DROPPING.put(me, now);
            f.swing(InteractionHand.MAIN_HAND);
            FolkAction.play(f, FolkAction.Act.HAND_OVER, DROP_TICKS + 6);
            dropBallot(level, f, box);
            return true;
        }
        if (now - started < DROP_TICKS) return true;
        DROPPING.remove(me);
        Elections.cast(level, c, f, true);
        leaveQueue(id, me);
        BlockPos b = box;
        level.sendParticles(ParticleTypes.POOF, b.getX() + 0.5, b.getY() + 1.05, b.getZ() + 0.5, 4, 0.1, 0.05, 0.1, 0.01);
        notice(level, v, box, turnoutText(id, c));
        f.hobbyNow = "voted";
        // Step aside, off the queue's line, so the next comes up.
        BlockPos aside = groundAt(level, box.relative(dir.getClockWise(), 3).relative(dir, 1));
        if (aside != null) f.walkTo(aside, 0.8D);
        return true;
    }

    static void leaveQueue(UUID village, UUID folk) {
        List<UUID> q = QUEUE.get(village);
        if (q != null) q.remove(folk);
        JOINED.remove(folk);
        DROPPING.remove(folk);
    }

    /** The ballot going in: a sheet of paper out of its hand, over and down into the slot, that cannot be picked up. */
    static void dropBallot(ServerLevel level, Entity voter, BlockPos box) {
        double x = voter.getX(), y = voter.getY() + 1.2, z = voter.getZ();
        ItemEntity paper = new ItemEntity(level, x, y, z, new ItemStack(Items.PAPER));
        paper.setNeverPickUp();
        paper.setNoGravity(true);
        double tx = box.getX() + 0.5 - x, ty = box.getY() + 1.1 - y, tz = box.getZ() + 0.5 - z;
        paper.setDeltaMovement(tx / 10.0, ty / 10.0 + 0.04, tz / 10.0);
        paper.lifespan = 12;
        paper.addTag(TAG);
        level.addFreshEntity(paper);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.PAPER)), box.getX() + 0.5, box.getY() + 1.1, box.getZ() + 0.5,
            5, 0.08, 0.05, 0.08, 0.02);
        level.playSound(null, box, SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 1.0F, 1.2F);
    }

    /** How many are in the queue now (the board, the tests). */
    public static int queued(UUID village) {
        List<UUID> q = QUEUE.get(village);
        return q == null ? 0 : q.size();
    }

    /** Tests: the folk in the queue, in order. */
    public static List<UUID> queueForTests(UUID village) {
        List<UUID> q = QUEUE.get(village);
        return q == null ? List.of() : new ArrayList<>(q);
    }

    // ------------------------------------------------------------------ the notice over the box

    /** The floating notice over the box, made if it is not there, its words changed if they differ. */
    static void notice(ServerLevel level, Villages.Village v, BlockPos box, Component text) {
        if (!level.isLoaded(box)) return;
        UUID id = v.id();
        sweep(level, v, box);
        Entity e = NOTICE.containsKey(id) ? level.getEntity(NOTICE.get(id)) : null;
        Display.TextDisplay td = e instanceof Display.TextDisplay x ? x : null;
        double x = box.getX() + 0.5, y = box.getY() + 1.9, z = box.getZ() + 0.5;
        if (td == null) {
            td = EntityType.TEXT_DISPLAY.create(level);
            if (td == null) return;
            td.addTag(TAG);
            td.moveTo(x, y, z);
            CompoundTag init = td.saveWithoutId(new CompoundTag());
            init.putString("billboard", "center");
            init.putInt("background", 0xB0141820);
            init.putInt("line_width", 220);
            td.load(init);
            level.addFreshEntity(td);
            NOTICE.put(id, td.getUUID());
        }
        CompoundTag t = td.saveWithoutId(new CompoundTag());
        String json = Component.Serializer.toJson(text, level.registryAccess());
        if (json.equals(t.getString("text"))) return;
        t.putString("text", json);
        td.load(t);
    }

    /** The town's notice gone. */
    static void clearNotice(ServerLevel level, UUID village) {
        UUID n = NOTICE.remove(village);
        Entity e = n == null ? null : level.getEntity(n);
        if (e != null) e.discard();
    }

    /** Once a session: notices left from before (a restart) cleared, so there is only ever the one. */
    static void sweep(ServerLevel level, Villages.Village v, BlockPos box) {
        if (!SWEPT.add(v.id())) return;
        for (Display.TextDisplay d : level.getEntitiesOfClass(Display.TextDisplay.class, new AABB(box).inflate(4))) {
            if (d.getTags().contains(TAG) && !d.getUUID().equals(NOTICE.get(v.id()))) d.discard();
        }
    }

    /** "POLLING STATION / Election for mayor, day 12 / ■■■■■□□□ 5 of 8 have voted / Queue: 2". */
    static Component turnoutText(UUID village, Elections.Campaign c) {
        int voters = Elections.voters(village).size() + players(c);
        int voted = c.ballots.size();
        MutableComponent t = Component.literal("POLLING STATION").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        t.append(Component.literal("\nElection for " + Government.title(village) + " · day " + c.voteDay).withStyle(ChatFormatting.WHITE));
        t.append(Component.literal("\n"));
        t.append(bar(voted, Math.max(1, voters), 16, ChatFormatting.GREEN));
        t.append(Component.literal(" " + voted + " of " + voters + " have voted").withStyle(ChatFormatting.GRAY));
        int q = queued(village);
        if (q > 1) t.append(Component.literal("\nIn the queue: " + q).withStyle(ChatFormatting.GRAY));
        t.append(Component.literal("\nThe count is at dusk, here").withStyle(ChatFormatting.DARK_GRAY));
        return t;
    }

    /** Players who have voted in this election (citizens' ballots are kept with the folk's). */
    static int players(Elections.Campaign c) {
        int n = 0;
        for (UUID u : c.ballots.keySet()) if (Elections.loaded(c.village, u) == null) n++;
        return n;
    }

    static MutableComponent bar(int n, int of, int width, ChatFormatting colour) {
        int full = (int) Math.round(width * Math.min(1.0, n / (double) of));
        return Component.literal("■".repeat(full)).withStyle(colour).append(Component.literal("■".repeat(width - full)).withStyle(ChatFormatting.DARK_GRAY));
    }

    // ------------------------------------------------------------------ the count, ballot by ballot

    /** The count begins (Elections.script): the box opened, the ballots read out one by one over the next seconds. */
    static void startCount(ServerLevel level, Villages.Village v, Elections.Result r) {
        if (Identity.neutral() && !visualForTests) return;
        UUID id = v.id();
        Count c = new Count();
        int i = 0;
        for (Map.Entry<Elections.Candidate, Integer> e : r.votes().entrySet()) {
            c.names.add(e.getKey().name());
            c.finals.add(e.getValue());
            c.colours.add(colourOf(id, e.getKey(), i++));
            for (int n = 0; n < e.getValue(); n++) c.order.add(c.names.size() - 1);
        }
        Collections.shuffle(c.order, new Random(Objects.hash(id, r.voted(), "count")));
        c.shown = new int[c.names.size()];
        c.winner = r.winner() == null ? "" : r.winner().name();
        c.office = Government.title(id);
        c.lastAt = level.getGameTime();
        COUNTING.put(id, c);
        BlockPos box = station(level, v, false);
        if (box != null) {
            level.playSound(null, box, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.8F, 1.3F);
            notice(level, v, box, countText(c, false));
        }
    }

    /** Every few ticks while a count is on (Politics.fast): the next ballot read out, and the notice moved on. */
    static void countTick(ServerLevel level, Villages.Village v) {
        Count c = COUNTING.get(v.id());
        if (c == null || c.finished) return;
        long now = level.getGameTime();
        if (now - c.lastAt < COUNT_EVERY) return;
        c.lastAt = now;
        BlockPos box = station(level, v, false);
        if (c.next >= c.order.size()) {
            c.finished = true;
            if (box != null) notice(level, v, box, countText(c, true));
            return;
        }
        int who = c.order.get(c.next++);
        c.shown[who]++;
        if (box != null) {
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.PAPER)), box.getX() + 0.5, box.getY() + 1.3, box.getZ() + 0.5,
                3, 0.15, 0.2, 0.15, 0.04);
            level.playSound(null, box, SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 0.6F, 0.9F + 0.2F * (who % 3));
            notice(level, v, box, countText(c, false));
        }
    }

    /** The towns counting now (Politics' fast beat). */
    static java.util.Set<UUID> countingTowns() {
        return COUNTING.keySet();
    }

    public static boolean counting(UUID village) {
        Count c = COUNTING.get(village);
        return c != null && !c.finished;
    }

    /** The count's notice: a bar for each, in its colour, rising ballot by ballot; at the end, the winner over it all. */
    static Component countText(Count c, boolean done) {
        MutableComponent t = done && !c.winner.isEmpty()
            ? Component.literal(c.winner.toUpperCase(java.util.Locale.ROOT) + " WINS").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            : Component.literal("THE COUNT").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        int most = 1, counted = 0, total = c.order.size();
        for (int i = 0; i < c.names.size(); i++) { most = Math.max(most, done ? c.finals.get(i) : c.shown[i]); counted += c.shown[i]; }
        for (int i = 0; i < c.names.size(); i++) {
            int n = done ? c.finals.get(i) : c.shown[i];
            ChatFormatting col = chat(c.colours.get(i));
            t.append(Component.literal("\n" + pad(c.names.get(i)) + " ").withStyle(ChatFormatting.WHITE));
            t.append(bar(n, most, 12, col));
            t.append(Component.literal(" " + n).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        }
        t.append(Component.literal(done ? "\n" + capital(c.office) + (c.winner.isEmpty() ? "" : ": " + c.winner) : "\n" + counted + " of " + total + " counted")
            .withStyle(ChatFormatting.GRAY));
        return t;
    }

    static String pad(String s) {
        String x = s.length() > 10 ? s.substring(0, 10) : s;
        return x;
    }

    static ChatFormatting chat(DyeColor d) {
        return switch (d) {
            case RED -> ChatFormatting.RED;
            case BLUE -> ChatFormatting.BLUE;
            case GREEN -> ChatFormatting.DARK_GREEN;
            case LIME -> ChatFormatting.GREEN;
            case YELLOW -> ChatFormatting.YELLOW;
            case ORANGE -> ChatFormatting.GOLD;
            case PURPLE, MAGENTA -> ChatFormatting.LIGHT_PURPLE;
            case CYAN, LIGHT_BLUE -> ChatFormatting.AQUA;
            case BLACK, GRAY -> ChatFormatting.GRAY;
            case BROWN -> ChatFormatting.GOLD;
            default -> ChatFormatting.WHITE;
        };
    }

    // ------------------------------------------------------------------ the result

    /**
     * The count is done (Elections.count): the result kept for the board's chart, a democracy's seats shared out, the
     * town's unrest let out at the polls. {@code how}: "", "tie kept the one in office", "drawn by lot", "unopposed".
     */
    static void counted(ServerLevel level, Villages.Village v, Elections.Result r, String how, long day) {
        UUID id = v.id();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        Map<UUID, Integer> byCand = new HashMap<>();
        for (Map.Entry<Elections.Candidate, Integer> e : r.votes().entrySet()) {
            Factions.Party p = Government.of(id) == Government.Form.DEMOCRACY ? Factions.partyOfCandidate(id, e.getKey().id()) : null;
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey().name().replace(":", " ").replace(";", " ")).append(':').append(e.getValue()).append(':')
                .append(colourOf(id, e.getKey(), i++).getName()).append(':').append(p == null ? "" : p.name()).append(':')
                .append(e.getKey().platform().name());
            byCand.put(e.getKey().id(), e.getValue());
        }
        String winner = r.winner() == null ? "" : r.winner().name().replace("|", " ");
        Ledger.note(id, "polls.last", day + "|" + winner + "|" + how.replace("|", " ") + "|" + r.voted() + "|" + r.voters() + "|" + sb + "|" + Government.title(id));
        if (Government.of(id) == Government.Form.DEMOCRACY) Factions.allocate(id, byCand, r.winner() == null ? null : r.winner().id());
        Ledger.note(id, "polls.downAfter", Long.toString(day + 1));
        Dissent.letOut(level, v, day);
    }

    /** The last result, for the board: {day, winner, how, voted, voters, office} and rows {name, votes, colour, party, platform}. */
    public static List<String[]> lastRows(UUID village) {
        List<String[]> out = new ArrayList<>();
        String s = Ledger.note(village, "polls.last");
        if (s == null || s.isEmpty()) return out;
        String[] p = s.split("\\|", -1);
        if (p.length < 6) return out;
        out.add(new String[]{ p[0], p[1], p[2], p[3], p[4], p.length > 6 ? p[6] : "" });
        if (!p[5].isEmpty()) for (String row : p[5].split(";")) out.add(row.split(":", -1));
        return out;
    }

    /** Nobody stood (Elections.call): the one in office stays on as caretaker until the next; told on the board. */
    static void nobodyStood(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        long next = day + Government.term(id, Elections.TERM);
        Ledger.note(id, "election.next", Long.toString(next));
        String who = Villages.elderName(id);
        String line = "nobody stood at the election for " + Government.title(id) + ": " + (who.isEmpty() ? "the council speaks for the town"
            : who + " stays on as caretaker") + " until the next, on day " + next;
        Ledger.note(id, "polls.last", day + "||nobody stood|0|" + Elections.voters(id).size() + "||" + Government.title(id));
        Villages.tell(id, day, line);
        Market.assemblyNews(id, Regimes.capital(line) + ".");
    }

    /** Each morning: the winner's posters down the day after, and the notice over the box put away. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        String after = Ledger.note(id, "polls.downAfter");
        long when = after == null || after.isEmpty() ? -1 : Long.parseLong(after);
        Elections.Campaign c = Elections.campaign(id);
        if (when >= 0 && day >= when && (c == null || c.counted)) {
            tearDown(level, v, k -> true, null);
            clearNotice(level, id);
            COUNTING.remove(id);
            Ledger.note(id, "polls.downAfter", "");
        }
    }

    // ------------------------------------------------------------------ rallies in the square

    /**
     * A few times a minute (Politics): on each day of a campaign, each candidate's hour to speak in the square comes round
     * (its place on the list sets it); the rally gathered if nothing else is on.
     */
    static void campaign(ServerLevel level, Villages.Village v, long day, long t) {
        if (Identity.neutral() && !visualForTests) return;
        UUID id = v.id();
        Elections.Campaign c = Elections.campaign(id);
        if (c == null || c.counted || c.candidates.isEmpty() || day >= c.voteDay || day < c.called) return;
        if (Raids.underAlarm(id) || level.isRaining() || Assemblies.underWay(id) != null) return;
        String held = Ledger.note(id, "polls.rallies");
        String today = day + ":";
        String done = held != null && held.startsWith(today) ? held.substring(today.length()) : "";
        for (int i = 0; i < c.candidates.size(); i++) {
            long at = 3000L + i * 2200L;
            if (t < at || t > at + 1800L || done.contains("," + i + ",")) continue;
            Elections.Candidate k = c.candidates.get(i);
            Ledger.note(id, "polls.rallies", today + (done.isEmpty() ? "," : done) + i + ",");
            if (Elections.loaded(id, k.id()) == null) return;                   // a player, or away: no rally of its own
            rally(level, v, k, day);
            return;
        }
    }

    /** A rally now: the candidate before the board, its leaners and the undecided gathered. True if it began. */
    public static boolean rally(ServerLevel level, Villages.Village v, Elections.Candidate k, long day) {
        UUID id = v.id();
        BlockPos at = speechSpot(level, v);
        Direction face = VillageBoards.facingOf(id);
        Assemblies.Assembly a = new Assemblies.Assembly(id, Assemblies.Kind.RALLY, k.id() + "|" + k.name() + "|" + k.platform().name(), day,
            at, face != null ? face : Direction.SOUTH, Assemblies.Layout.ARC);
        java.util.Set<UUID> invited = new java.util.HashSet<>();
        List<VillageFolkEntity> voters = new ArrayList<>(Elections.voters(id));
        voters.sort((x, y) -> Double.compare(x.distanceToSqr(at.getX(), at.getY(), at.getZ()), y.distanceToSqr(at.getX(), at.getY(), at.getZ())));
        for (VillageFolkEntity f : voters) {
            if (invited.size() >= 14) break;
            if (f.getUUID().equals(k.id())) continue;
            invited.add(f.getUUID());
        }
        invited.add(k.id());
        a.invited = invited;
        return Assemblies.beginNow(level, a);
    }

    /** The rally's host: the candidate (Assemblies.prepare). */
    static void prepareRally(ServerLevel level, Assemblies.Assembly a) {
        String[] p = a.subject.split("\\|", 3);
        try { a.host = UUID.fromString(p[0]); } catch (RuntimeException ignored) { }
        a.principals.clear();
    }

    /** What is said: the candidate's case in its own terms, the crowd's answer, a heckle and the reply, the close. */
    static void rallyScript(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        String[] p = a.subject.split("\\|", 3);
        if (p.length < 3) return;
        UUID cand;
        try { cand = UUID.fromString(p[0]); } catch (RuntimeException e) { return; }
        Values.Value platform;
        try { platform = Values.Value.valueOf(p[2]); } catch (IllegalArgumentException e) { platform = Values.Value.TRADITION; }
        String town = Villages.name(a.village), office = Government.title(a.village);
        s.add(new Assemblies.Line(cand, FolkTalk.pick(r, "Thank you for coming. I'll be brief.", "Good of you to stop. I'm standing for " + office + ".",
            "I won't keep you from your work long.", "I'm standing for " + office + ", and here's why.", "You know me. Here's what I'd do.",
            "Some of you asked what I stand for. This."), ' ', crowd(level, a, cand, false)));
        String[] lines = platformLines(platform, town);
        int first = r.nextInt(lines.length), second = (first + 1 + r.nextInt(lines.length - 1)) % lines.length;
        s.add(new Assemblies.Line(cand, lines[first], ' ', crowd(level, a, cand, false)));
        s.add(new Assemblies.Line(cand, lines[second], ' ', crowd(level, a, cand, true)));
        s.add(new Assemblies.Line(cand, FolkTalk.pick(r, "That's a fair question, and the answer is: carefully.", "We'd pay for it the way we pay for everything: honestly.",
            "I've heard that before. I've also seen it done.", "If I'm wrong, you can vote me out in ten days.", "Fair point. Come and see the sums.",
            "I'd rather be asked than nodded at."), ' ', null));
        s.add(new Assemblies.Line(cand, FolkTalk.pick(r, "Polling day, then. You know where the box is.", "That's all. Vote how you like, but vote.",
            "Thank you. The box is by the board.", "I'll be at the box with everyone else.", "Think it over. Then vote.",
            "That's my case. The rest is up to you."), ' ', crowd(level, a, cand, false)));
    }

    /** Six lines a candidate says for what it stands for, in its own plain words. */
    static String[] platformLines(Values.Value p, String town) {
        return switch (p) {
            case FOOD -> new String[]{ "Nobody in " + town + " should go to bed hungry. I'd widen the fields first.",
                "More hands on the water and the land. A full larder before anything else.", "We ran short last winter. That doesn't happen again.",
                "A town that eats well works well. It's that simple.", "Seed, tools and fields. The rest follows.", "I'd keep a month's food put by, always." };
            case HOMES -> new String[]{ "Every family under its own roof. Houses go up first.", "Too many sleep in borrowed beds. I'd fix that.",
                "Timber, masons, lots marked out. Homes before halls.", "A home is where a town starts.", "I'd build the next street before the next statue.",
                "Ask anyone without a house what matters. Then vote." };
            case PROGRESS -> new String[]{ "The next age won't come to us. We go to it.", "More picks in the mine, the furnaces kept hot.",
                "A school, a library, workshops. That's how a town grows up.", "Other towns are ahead of us. Not for long.", "Learning first, then everything else.",
                "I'd put the treasury into tools, not ornaments." };
            case SAFETY -> new String[]{ "Safe streets, day and night. The watch on every road.", "Walls first. You can't trade from a burnt house.",
                "When the raiders came, who was ready? We will be.", "I'd see the watch paid properly and armed properly.", "Lamps on the streets, guards at the gate.",
                "Nobody should be afraid to walk home." };
            case WEALTH -> new String[]{ "Better wages, a busier market, lower taxes.", "Let folk keep more of what they earn.",
                "Trade brings coin, coin brings everything else.", "Open the roads, fill the stalls, cut the toll.", "Every shop that opens is a job.",
                "A full purse spends; an empty one doesn't." };
            case LEISURE -> new String[]{ "We work hard. We should rest harder.", "Rest days, feasts, and a tavern worth the name.",
                "A town that never stops is a town that wears out.", "More festivals, fewer meetings.", "The commons kept green and open to all.",
                "Ask yourself when you last had a day off." };
            case TRADITION -> new String[]{ "Keep what works. Mend what doesn't. Leave the rest.", "Our customs made this town. I'd keep them.",
                "Steady hands, steady town.", "No grand schemes. Just doing things properly.", "We don't need to be the next anything.",
                "I'd listen to the eldest before the loudest." };
        };
    }

    /** The crowd's answer to a line: those leaning the candidate's way nod and call out, the rest frown; now and then a heckle. */
    static Runnable crowd(ServerLevel level, Assemblies.Assembly a, UUID cand, boolean heckle) {
        return () -> {
            Elections.Campaign c = Elections.campaign(a.village);
            if (c == null) return;
            Elections.Candidate k = Elections.find(c, cand);
            if (k == null) return;
            RandomSource r = level.getRandom();
            int voices = 0;
            boolean heckled = false;
            for (UUID u : a.seated.keySet()) {
                if (!(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
                boolean likes = leans(level, c, f, k);
                if (likes) {
                    FolkAction.play(f, r.nextInt(3) == 0 ? FolkAction.Act.CLAP : FolkAction.Act.CHEER, 30 + r.nextInt(20));
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 2.1, f.getZ(), 2, 0.2, 0.1, 0.2, 0.0);
                    if (voices < 2 && r.nextInt(3) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(r, "Hear, hear.", "Well put.", "That's the one.", "About time someone said it.", "Quite right.", "Good."));
                        voices++;
                    }
                } else {
                    FolkAction.play(f, r.nextBoolean() ? FolkAction.Act.SHRUG : FolkAction.Act.THINK, 40);
                    if (heckle && !heckled && r.nextInt(2) == 0) {
                        heckled = true;
                        FolkAction.play(f, FolkAction.Act.QUARREL, 50);
                        FolkAction.mark(f, FolkAction.Mark.ANGER);
                        FolkTalk.speak(f, FolkTalk.pick(r, "And who pays for that?", "Heard it all before.", "Easier said than done!",
                            "What about the rest of us?", "Not with my taxes!", "Where were you last winter?"));
                    } else if (voices < 3 && r.nextInt(4) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(r, "We'll see.", "Hm.", "Not likely.", "Easier said.", "Heard it before.", "Maybe."));
                        voices++;
                    }
                }
            }
        };
    }

    /** Does this folk lean to this candidate now (its own judgement of all who stand)? */
    static boolean leans(ServerLevel level, Elections.Campaign c, VillageFolkEntity f, Elections.Candidate k) {
        Elections.Candidate best = null;
        int top = Integer.MIN_VALUE;
        for (Elections.Candidate x : c.candidates) {
            int s = Elections.judge(level, f, x).score();
            if (s > top) { top = s; best = x; }
        }
        return best == k;
    }

    /** The rally over: into the chronicle, and its listeners a little more its way. */
    static void rallyClosed(ServerLevel level, Assemblies.Assembly a) {
        String[] p = a.subject.split("\\|", 3);
        if (p.length < 2) return;
        Villages.tell(a.village, level.getDayTime() / 24000L, p[1] + " spoke in the square to " + a.seated.size() + " folk, for the election");
        try {
            UUID cand = UUID.fromString(p[0]);
            for (UUID u : a.seated.keySet()) Hustings.sway(a.village, u, cand, 3);
        } catch (RuntimeException ignored) { }
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
