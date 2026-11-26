package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.LeisureItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featlife] The town's clubs: the fishing club, the choir, the pub quiz, the village sports team and the ramblers. Each
 * meets at its own hour of the week (the weekend, or an evening after work) at its own place, its members joining by
 * what they are like (LifeWeights: an angler the fishing club, the musical and the devout the choir, the bookish and the
 * curious the quiz, the sporty the team, the restless the ramblers) and a player by the week page's Clubs tab (or by
 * asking a member). A club with too few members, or nowhere to meet (no water for the anglers, no tavern for the quiz),
 * does not meet, and the page says why.
 * <ul>
 * <li><b>The fishing club</b>, Saturday morning at the water's edge, rods out, the odd fish.</li>
 * <li><b>The choir</b>, Thursday evening at the chapel (or the square), practising the hymn a line at a time.</li>
 * <li><b>The pub quiz</b>, Friday evening at the tavern: the quizmaster's questions, the answers shouted, a winner.</li>
 * <li><b>The village sports team</b>: training on Wednesday evening and a match on Saturday afternoon on the green,
 *     a ball game between two sides of it on a marked pitch (the town's pitch, or the green marked out for the day with
 *     goals and corner flags, taken up after), the town's folk off for the day watching from the touchline and cheering.</li>
 * <li><b>The ramblers</b>, Sunday afternoon, a walk round the town's edge behind their leader.</li>
 * </ul>
 * What they get from it is small and real: a better mood the day after (they remember it) and friends (every meeting
 * warms the members present to each other: Friends.CLUB).
 */
public final class Clubs {

    private Clubs() {}

    public enum Club {
        FISHING("the fishing club", "Fishing club", Calendar.Day.SATURDAY, 1200L, 5000L, 3, "at the water's edge"),
        CHOIR("the choir", "Choir", Calendar.Day.THURSDAY, 12200L, 13400L, 3, "at the chapel"),
        QUIZ("the pub quiz", "Pub quiz", Calendar.Day.FRIDAY, 12400L, 13800L, 3, "at the tavern"),
        SPORTS("the village sports team", "Sports team", Calendar.Day.SATURDAY, 7000L, 9600L, 4, "on the green"),
        RAMBLERS("the ramblers", "Ramblers", Calendar.Day.SUNDAY, 8200L, 11000L, 3, "round the town");

        public final String words, title, where;
        public final Calendar.Day day;
        public final long from, to;
        public final int least;

        Club(String words, String title, Calendar.Day day, long from, long to, int least, String where) {
            this.words = words;
            this.title = title;
            this.day = day;
            this.from = from;
            this.to = to;
            this.least = least;
            this.where = where;
        }

        /** "Saturday 7:00–11:00". */
        public String when() {
            return day.word + " " + WorkHours.hour(from) + ":00–" + WorkHours.hour(to) + ":00";
        }

        /** How it pulls this folk: its marks' weights (LifeWeights.CLUB_*). */
        public double pull(VillageFolkEntity f) {
            EnumSet<LifeWeights.Mark> m = LifeWeights.marks(f);
            double s = 0;
            switch (this) {
                case FISHING -> {
                    if (m.contains(LifeWeights.Mark.ANGLER)) s += LifeWeights.CLUB_PULL_STRONG;
                    if (m.contains(LifeWeights.Mark.SHY)) s += LifeWeights.CLUB_PULL;
                    if (m.contains(LifeWeights.Mark.EASYGOING)) s += LifeWeights.CLUB_PULL;
                }
                case CHOIR -> {
                    if (m.contains(LifeWeights.Mark.MUSICAL)) s += LifeWeights.CLUB_PULL_STRONG;
                    if (m.contains(LifeWeights.Mark.DEVOUT)) s += LifeWeights.CLUB_PULL;
                    if (m.contains(LifeWeights.Mark.CHEERFUL)) s += LifeWeights.CLUB_PULL;
                    if (m.contains(LifeWeights.Mark.SHY)) s += LifeWeights.CLUB_PUSH;
                }
                case QUIZ -> {
                    if (m.contains(LifeWeights.Mark.BOOKISH)) s += LifeWeights.CLUB_PULL_STRONG;
                    if (m.contains(LifeWeights.Mark.CURIOUS)) s += LifeWeights.CLUB_PULL;
                    if (m.contains(LifeWeights.Mark.CHATTY)) s += LifeWeights.CLUB_PULL;
                    if (m.contains(LifeWeights.Mark.DEVOUT)) s -= 0.5;
                }
                case SPORTS -> {
                    if (m.contains(LifeWeights.Mark.SPORTY)) s += LifeWeights.CLUB_PULL_STRONG;
                    if (m.contains(LifeWeights.Mark.AMBITIOUS)) s += LifeWeights.CLUB_PULL;
                    if (m.contains(LifeWeights.Mark.CHEERFUL)) s += LifeWeights.CLUB_PULL;
                    if (f.ageYears() < 35) s += 1.0;
                    if (f.ageYears() > 55) s += LifeWeights.CLUB_PUSH;
                }
                case RAMBLERS -> {
                    if (m.contains(LifeWeights.Mark.WANDERER)) s += LifeWeights.CLUB_PULL_STRONG;
                    if (m.contains(LifeWeights.Mark.CURIOUS)) s += LifeWeights.CLUB_PULL;
                    if (m.contains(LifeWeights.Mark.HOMEBODY)) s += LifeWeights.CLUB_PUSH;
                    if (f.ageYears() > 50) s += 1.0;
                }
            }
            return s;
        }

        /** The marks that brought this folk in, in a word: "an angler". */
        String because(VillageFolkEntity f) {
            return switch (this) {
                case FISHING -> LifeWeights.why(f, LifeWeights.Mark.ANGLER, LifeWeights.Mark.SHY, LifeWeights.Mark.EASYGOING);
                case CHOIR -> LifeWeights.why(f, LifeWeights.Mark.MUSICAL, LifeWeights.Mark.DEVOUT, LifeWeights.Mark.CHEERFUL);
                case QUIZ -> LifeWeights.why(f, LifeWeights.Mark.BOOKISH, LifeWeights.Mark.CURIOUS, LifeWeights.Mark.CHATTY);
                case SPORTS -> LifeWeights.why(f, LifeWeights.Mark.SPORTY, LifeWeights.Mark.AMBITIOUS, LifeWeights.Mark.CHEERFUL);
                case RAMBLERS -> LifeWeights.why(f, LifeWeights.Mark.WANDERER, LifeWeights.Mark.CURIOUS);
            };
        }
    }

    /** Folk put in a club by hand (tests) and players who joined, by town and club ("life.club.<id>" in the town's notes). */
    private static final Map<String, Set<UUID>> JOINED = new ConcurrentHashMap<>();
    /** Folk who attended a meeting today ("club|village|day" to ids), and players. */
    private static final Map<String, Set<UUID>> PRESENT = new ConcurrentHashMap<>();
    private static final Map<String, Long> MEETING_TOLD = new ConcurrentHashMap<>();

    public static void resetForTests() {
        JOINED.clear();
        PRESENT.clear();
        MEETING_TOLD.clear();
        MATCHES.clear();
        QUIZZES.clear();
        forceForTests.clear();
    }

    /** Tests: a meeting of this club on now, whatever the clock says. */
    public static final Set<String> forceForTests = ConcurrentHashMap.newKeySet();

    public static void forceMeetingForTests(UUID village, Club c, boolean on) {
        if (on) forceForTests.add(key(village, c));
        else forceForTests.remove(key(village, c));
    }

    static String key(UUID village, Club c) {
        return village + "|" + c.name();
    }

    static Set<UUID> joined(UUID village, Club c) {
        return JOINED.computeIfAbsent(key(village, c), k -> {
            Set<UUID> s = ConcurrentHashMap.newKeySet();
            String kept = Ledger.note(village, "life.club." + c.name());
            if (kept != null && !kept.isEmpty()) for (String u : kept.split(",")) {
                try { s.add(UUID.fromString(u)); } catch (IllegalArgumentException ignored) { }
            }
            return s;
        });
    }

    static void keep(UUID village, Club c) {
        StringBuilder sb = new StringBuilder();
        for (UUID u : joined(village, c)) sb.append(sb.length() == 0 ? "" : ",").append(u);
        Ledger.note(village, "life.club." + c.name(), sb.toString());
    }

    /** Is this folk a member: by its character, or put in by hand? */
    public static boolean member(VillageFolkEntity f, Club c) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || Visitors.is(f)) return false;
        return joined(village, c).contains(f.getUUID()) || c.pull(f) >= LifeWeights.CLUB_JOIN_AT;
    }

    /** Its clubs. */
    public static List<Club> clubsOf(VillageFolkEntity f) {
        List<Club> out = new ArrayList<>();
        for (Club c : Club.values()) if (member(f, c)) out.add(c);
        return out;
    }

    /** A club's folk members in town, loaded. */
    public static List<VillageFolkEntity> members(UUID village, Club c) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && member(f, c)) out.add(f);
        return out;
    }

    /** Its player members. */
    public static List<UUID> players(UUID village, Club c) {
        List<UUID> out = new ArrayList<>();
        for (UUID u : joined(village, c)) if (Homes.loaded(village, u) == null) out.add(u);
        return out;
    }

    public static boolean playerIn(UUID village, Club c, UUID player) {
        return joined(village, c).contains(player);
    }

    /** Tests: a folk in a club. */
    public static void joinForTests(VillageFolkEntity f, Club c) {
        joined(f.ownerId(), c).add(f.getUUID());
    }

    /** A player joins, from the week page or a word with a member. */
    public static String join(ServerPlayer p, UUID village, Club c) {
        String no = whyNot(p.serverLevel(), village, c);
        if (joined(village, c).contains(p.getUUID())) return "!You're already in " + c.words + ". It meets " + c.when() + ", " + c.where + ".";
        joined(village, c).add(p.getUUID());
        keep(village, c);
        long day = p.level().getDayTime() / 24000L;
        long next = nextMeeting(c, day);
        BlockPos at = place(p.serverLevel(), Villages.get(village), c);
        Planner.add(p, next * 24000L + c.from, "club", c.title + " meets, " + c.where, at);
        return "You've joined " + c.words + ". It meets " + c.when() + ", " + c.where + "; the next is " + Calendar.when(day, next)
            + " (on your planner). Turn up and the members will get to know you." + (no == null ? "" : " For now, though: " + no);
    }

    public static String leave(ServerPlayer p, UUID village, Club c) {
        if (!joined(village, c).remove(p.getUUID())) return "!You're not in " + c.words + ".";
        keep(village, c);
        return "You've left " + c.words + ". No hard feelings; you can join again any time.";
    }

    /** Why a club cannot meet, or null if it can. */
    @Nullable
    public static String whyNot(ServerLevel level, UUID village, Club c) {
        int n = members(village, c).size() + players(village, c).size();
        if (n < c.least) return c.title + " needs " + c.least + " members to meet; it has " + n + ".";
        Villages.Village v = Villages.get(village);
        if (v == null) return "There's no town here.";
        if (place(level, v, c) == null) return switch (c) {
            case FISHING -> "There's no water near enough the town to fish.";
            case QUIZ -> "There's no tavern to hold it in yet.";
            default -> "There's nowhere for it to meet yet.";
        };
        return null;
    }

    /** The next day this club meets, from today (today if it is the day and not over). */
    public static long nextMeeting(Club c, long day) {
        return day + Calendar.daysTo(day, c.day);
    }

    /** Is the club meeting now? */
    public static boolean meetingNow(UUID village, Club c, long day, long t) {
        if (forceForTests.contains(key(village, c))) return true;
        if (c == Club.SPORTS && Calendar.day(day) == Calendar.Day.WEDNESDAY && t >= 12200L && t < 13400L) return true;   // training
        return Calendar.day(day) == c.day && t >= c.from && t < c.to;
    }

    /** Where it meets: the water's edge, the chapel, the tavern, the green, the square. Null if there is nowhere. */
    @Nullable
    public static BlockPos place(ServerLevel level, @Nullable Villages.Village v, Club c) {
        if (v == null) return null;
        UUID id = v.id();
        return switch (c) {
            case FISHING -> water(level, v);
            case CHOIR -> {
                BlockPos ch = Church.font(level, id);
                yield ch != null ? ch : v.centre();
            }
            case QUIZ -> {
                Ledger.Building t = Tavern.of(id);
                yield t == null ? null : t.anchor();
            }
            case SPORTS -> green(level, v).centre;
            case RAMBLERS -> v.centre();
        };
    }

    private static final Map<UUID, BlockPos> WATER = new ConcurrentHashMap<>();

    /** The water's edge nearest the town: a dry block beside water, within forty-eight blocks of the heart. */
    @Nullable
    static BlockPos water(ServerLevel level, Villages.Village v) {
        BlockPos known = WATER.get(v.id());
        if (known != null && level.isLoaded(known) && waterBeside(level, known)) return known;
        BlockPos c = v.centre();
        for (int r = 8; r <= 48; r += 4) {
            for (int k = 0; k < 24; k++) {
                double a = Math.toRadians(k * 15.0);
                int x = c.getX() + (int) Math.round(Math.sin(a) * r), z = c.getZ() + (int) Math.round(Math.cos(a) * r);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (!level.getFluidState(top.below()).isEmpty()) continue;
                if (waterBeside(level, top)) {
                    WATER.put(v.id(), top);
                    return top;
                }
            }
        }
        return null;
    }

    static boolean waterBeside(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) if (level.getFluidState(p.relative(d).below()).is(net.minecraft.tags.FluidTags.WATER)) return true;
        return false;
    }

    // ------------------------------------------------------------------ the town's round

    static void round(ServerLevel level, Villages.Village v) {
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        for (Club c : Club.values()) {
            if (!meetingNow(v.id(), c, day, t)) {
                if (c == Club.SPORTS) endMatch(level, v, day, "time");
                if (c == Club.QUIZ) QUIZZES.remove(v.id());
                continue;
            }
            String told = key(v.id(), c);
            if (!Long.valueOf(day).equals(MEETING_TOLD.get(told)) && whyNot(level, v.id(), c) == null) {
                MEETING_TOLD.put(told, day);
                BlockPos at = place(level, v, c);
                for (UUID u : players(v.id(), c)) {
                    if (level.getPlayerByUUID(u) instanceof ServerPlayer p) {
                        p.displayClientMessage(Component.literal(c.title + " is meeting now, " + c.where + "."), true);
                        Planner.add(p, (day + 7) * 24000L + c.from, "club", c.title + " meets, " + c.where, at);
                    }
                }
            }
            // Players there: the members warm to them.
            BlockPos at = place(level, v, c);
            if (at == null) continue;
            for (UUID u : players(v.id(), c)) {
                if (!(level.getPlayerByUUID(u) instanceof ServerPlayer p) || p.distanceToSqr(at.getX(), at.getY(), at.getZ()) > 16 * 16) continue;
                if (!present(v.id(), c, day).add(u)) continue;
                for (VillageFolkEntity f : members(v.id(), c)) if (f.distanceToSqr(p) < 24 * 24) f.persona().feelFor(u, p.getGameProfile().getName(), Friends.CLUB * 2);
                p.sendSystemMessage(Component.literal("You're at " + c.words + ". The members are glad of you; they'll think the better of you for coming."));
            }
            if (c == Club.QUIZ) quizRound(level, v, day);
        }
    }

    static Set<UUID> present(UUID village, Club c, long day) {
        return PRESENT.computeIfAbsent(c.name() + "|" + village + "|" + day, k -> ConcurrentHashMap.newKeySet());
    }

    /** How many came to this club's meeting today (tests, the week page). */
    public static int attended(UUID village, Club c, long day) {
        Set<UUID> s = PRESENT.get(c.name() + "|" + village + "|" + day);
        return s == null ? 0 : s.size();
    }

    // ------------------------------------------------------------------ a member at its meeting

    /** Its club's meeting, a beat of it (LifeTick.hold). True while it is there. */
    static boolean hold(ServerLevel level, VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || Visitors.is(f)) return false;
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        for (Club c : Club.values()) {
            if (!meetingNow(village, c, day, t)) continue;
            boolean in = member(f, c);
            if (c == Club.SPORTS && !in) {
                if (spectate(level, v, f, day)) return true;
                continue;
            }
            if (!in || f.onShift() && !f.offWorkNow() || whyNot(level, village, c) != null) continue;
            BlockPos at = place(level, v, c);
            if (at == null) continue;
            if (present(village, c, day).add(f.getUUID())) {
                LifeLog.count(f, LifeLog.Stat.CLUB, 1);
                LifeLog.note(f, day, "Went to " + c.words, c.because(f));
                f.persona().remember(day, "a good meeting of " + c.words, 2);
            }
            f.lastLeisureTick = f.tickCount;
            switch (c) {
                case FISHING -> fish(level, f, at);
                case CHOIR -> sing(level, f, at);
                case QUIZ -> quiz(level, v, f, at, day);
                case SPORTS -> play(level, v, f, day);
                case RAMBLERS -> ramble(level, v, f, day);
            }
            if (f.tickCount % 200 < 4) {
                for (VillageFolkEntity o : members(village, c)) if (o != f && o.distanceToSqr(f) < 100) { Friends.chatted(f, o, Friends.CLUB); break; }
            }
            return true;
        }
        return false;
    }

    static BlockPos spot(BlockPos at, VillageFolkEntity f, int r) {
        return Church.near(at, f, r);
    }

    static void fish(ServerLevel level, VillageFolkEntity f, BlockPos at) {
        f.hobbyNow = "at the fishing club, rod out";
        BlockPos s = spot(at, f, 2);
        if (!RestDay.arrive(f, s)) return;
        if (!f.getMainHandItem().is(Items.FISHING_ROD)) f.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
        Vec3 look = Vec3.atCenterOf(at);
        for (Direction d : Direction.Plane.HORIZONTAL) if (level.getFluidState(at.relative(d).below()).is(net.minecraft.tags.FluidTags.WATER)) look = Vec3.atCenterOf(at.relative(d, 3).below());
        f.getLookControl().setLookAt(look.x, look.y, look.z);
        if (f.getRandom().nextInt(90) == 0) {
            level.sendParticles(ParticleTypes.FISHING, look.x, look.y + 0.6, look.z, 6, 0.3, 0.0, 0.3, 0.05);
            f.swing(InteractionHand.MAIN_HAND);
            if (f.getRandom().nextInt(3) == 0) {
                f.insertItem(new ItemStack(Items.COD));
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "One.", "Small, but it counts.", "That's tea.", "Don't tell me it's the same fish as last week.",
                    "Put it in the book.", "Nobody saw that one get away."));
            } else if (f.getRandom().nextInt(4) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Nothing.", "They're laughing at us down there.", "Quiet today.", "Wrong bait.",
                    "It's the wind.", "Patience. Mostly patience."));
            }
        }
    }

    static void sing(ServerLevel level, VillageFolkEntity f, BlockPos at) {
        f.hobbyNow = "at choir practice";
        if (!RestDay.arrive(f, spot(at, f, 2))) return;
        f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 1.5, at.getZ() + 0.5);
        long now = level.getGameTime();
        if (now % 12 == Math.floorMod(f.getUUID().hashCode(), 12)) {
            int n = Church.HYMN[(int) ((now / 12) % Church.HYMN.length)] + (Math.floorMod(f.getUUID().hashCode(), 3) == 0 ? 4 : 0);
            level.playSound(null, f.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.RECORDS, 0.8F, (float) Math.pow(2.0, (n - 12) / 12.0));
            level.sendParticles(ParticleTypes.NOTE, f.getX(), f.getEyeY() + 0.6, f.getZ(), 1, 0.1, 0.1, 0.1, n / 24.0);
        }
        if (f.getRandom().nextInt(160) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "From the top.", "Altos, that was you.", "Again, and this time together.",
                "Breathe where it says breathe.", "Lovely. Wrong, but lovely.", "Who's humming? We don't hum."));
        }
    }

    // ------------------------------------------------------------------ the quiz

    static final String[][] QUESTIONS = {
        { "How many days are there in a week?", "Seven." },
        { "What do you get if you leave wheat in a furnace?", "Nothing useful." },
        { "Which day is market day?", "Saturday." },
        { "How many blocks of iron make a golem, give or take?", "Four, and a pumpkin." },
        { "What does a creeper want?", "Nobody knows." },
        { "What colour is the sky at noon?", "Blue. Usually." },
        { "How many sides has a square?", "Four. Unless it's ours." },
        { "What sits on the well's roof?", "Pigeons." },
        { "Who rings the bell at dusk?", "The sexton. Or whoever's nearest." },
        { "What's the fourth Saturday of the month?", "Fair day." }
    };

    private static final Map<UUID, int[]> QUIZZES = new ConcurrentHashMap<>();   // village: {question, teamA, teamB, lastAt(low)}

    static void quizRound(ServerLevel level, Villages.Village v, long day) {
        int[] q = QUIZZES.computeIfAbsent(v.id(), k -> new int[]{ 0, 0, 0, 0 });
        List<VillageFolkEntity> there = new ArrayList<>();
        for (UUID u : present(v.id(), Club.QUIZ, day)) if (level.getEntity(u) instanceof VillageFolkEntity f) there.add(f);
        if (there.size() < 2) return;
        if (++q[3] % 5 != 0) return;                                  // a question every five seconds or so
        VillageFolkEntity master = there.get(0);
        for (VillageFolkEntity f : there) if (LifeWeights.has(f, LifeWeights.Mark.BOOKISH)) { master = f; break; }
        if (q[0] < QUESTIONS.length) {
            String[] qa = QUESTIONS[q[0]];
            FolkTalk.speak(master, "Question " + (q[0] + 1) + ": " + qa[0]);
            VillageFolkEntity answers = there.get(1 + Math.floorMod(q[0], there.size() - 1));
            if (answers == master) answers = there.get(0);
            boolean right = level.getRandom().nextInt(3) != 0;
            answers.sayLater(right ? qa[1] : FolkTalk.pick(answers.getRandom(), "Pass.", "Is it a trick?", "Twelve?", "We'll come back to that."), 40);
            if (right) q[1 + Math.floorMod(answers.getUUID().hashCode(), 2)]++;
            q[0]++;
        } else if (q[0] == QUESTIONS.length) {
            q[0]++;
            String win = q[1] == q[2] ? "a draw, which pleases nobody" : (q[1] > q[2] ? "the Know-Alls" : "the Last Orders") + ", " + Math.max(q[1], q[2]) + " to " + Math.min(q[1], q[2]);
            FolkTalk.speak(master, "And the winners are " + win + ". Same time next week.");
            Villages.tell(v.id(), day, "the pub quiz: " + win);
            for (VillageFolkEntity f : there) f.persona().remember(day, "the pub quiz, " + win, 2);
        }
    }

    static void quiz(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos at, long day) {
        f.hobbyNow = "at the pub quiz";
        if (!RestDay.arrive(f, spot(at, f, 2))) return;
        if (f.getRandom().nextInt(200) == 0) Tavern.drink(f, level, v, day);
    }

    // ------------------------------------------------------------------ the ramblers

    static void ramble(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        f.hobbyNow = "out with the ramblers";
        List<VillageFolkEntity> all = members(v.id(), Club.RAMBLERS);
        if (all.isEmpty()) return;
        VillageFolkEntity leader = all.get(0);
        if (leader == f) {
            if (f.getNavigation().isDone()) {
                int reach = Math.max(20, Villages.townReach(v.id()));
                double a = Math.toRadians((level.getGameTime() / 200L) * 30 % 360);
                BlockPos to = f.surfaceAt(v.centre().getX() + (int) (Math.sin(a) * reach), v.centre().getZ() + (int) (Math.cos(a) * reach));
                if (to != null) f.walkTo(to, 0.6D);
            }
        } else if (f.distanceToSqr(leader) > 9 && (f.getNavigation().isDone() || f.tickCount % 40 == 0)) {
            f.walkTo(leader.blockPosition(), 0.65D);
        }
        if (f.getRandom().nextInt(300) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Is this a path or isn't it?", "Lovely view. Of the same field.", "Mind the cow.",
                "Who's got the map?", "We came this way last week.", "Keep up at the back."));
        }
    }

    // ------------------------------------------------------------------ the sports team

    /** The field: its centre and the way its length runs, and whether it is the town's own pitch or the green marked for the day. */
    record Field(BlockPos centre, Direction along, boolean pitch) {
        Vec3 point(double u, double w) {
            Direction r = along.getClockWise();
            return new Vec3(centre.getX() + 0.5 + r.getStepX() * u + along.getStepX() * w, centre.getY(),
                centre.getZ() + 0.5 + r.getStepZ() * u + along.getStepZ() * w);
        }

        double[] local(Vec3 p) {
            Direction r = along.getClockWise();
            double x = p.x - (centre.getX() + 0.5), z = p.z - (centre.getZ() + 0.5);
            return new double[]{ x * r.getStepX() + z * r.getStepZ(), x * along.getStepX() + z * along.getStepZ() };
        }

        BlockPos at(int u, int w) {
            return centre.relative(along.getClockWise(), u).relative(along, w);
        }
    }

    static final int HALF_WIDE = Pitch.HALF_WIDE, HALF_LONG = Pitch.HALF_LONG;

    /** The green: the town's pitch if it has one; else open, level ground near the park or the edge of the square. */
    static Field green(ServerLevel level, Villages.Village v) {
        Ledger.Building p = Pitch.of(v.id());
        if (p != null) return new Field(p.anchor(), p.facing(), true);
        Object[] pg = Pastimes.playground(v.id(), v);
        BlockPos base = (BlockPos) pg[0];
        for (int r = 0; r <= 40; r += 6) {
            for (int k = 0; k < (r == 0 ? 1 : 8); k++) {
                double a = Math.toRadians(k * 45.0);
                int x = base.getX() + (int) Math.round(Math.sin(a) * (r + 14)), z = base.getZ() + (int) Math.round(Math.cos(a) * (r + 14));
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos c = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (flat(level, c, Direction.EAST)) return new Field(c, Direction.EAST, false);
            }
        }
        return new Field(base, Direction.EAST, false);
    }

    static boolean flat(ServerLevel level, BlockPos c, Direction along) {
        Field f = new Field(c, along, false);
        for (int u = -HALF_WIDE - 1; u <= HALF_WIDE + 1; u += 2) {
            for (int w = -HALF_LONG - 1; w <= HALF_LONG + 1; w += 2) {
                BlockPos p = f.at(u, w);
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) != c.getY()) return false;
                if (!level.getFluidState(p.below()).isEmpty()) return false;
            }
        }
        return true;
    }

    /** A match on: the field, the ball, the sides, the score, what was put up to mark the green, when it kicked off. */
    static final class Match {
        final Field field;
        final UUID ball;
        final List<UUID> greens = new ArrayList<>(), golds = new ArrayList<>();
        final Set<UUID> crowd = new LinkedHashSet<>();
        final List<BlockPos> marks = new ArrayList<>();
        final long day, kickoff;
        final boolean fromStores;
        int greenGoals, goldGoals;
        @Nullable String lastScorer;

        Match(Field field, UUID ball, long day, long kickoff, boolean fromStores) {
            this.field = field;
            this.ball = ball;
            this.day = day;
            this.kickoff = kickoff;
            this.fromStores = fromStores;
        }
    }

    private static final Map<UUID, Match> MATCHES = new ConcurrentHashMap<>();
    static final long MATCH_TICKS = 2400L;

    @Nullable
    public static String score(UUID village) {
        Match m = MATCHES.get(village);
        return m == null ? null : "Greens " + m.greenGoals + ", Golds " + m.goldGoals;
    }

    public static boolean matchOn(UUID village) {
        return MATCHES.containsKey(village);
    }

    /** The match, begun by the first player to turn up: the green marked, the ball down, the sides picked. */
    @Nullable
    static Match start(ServerLevel level, Villages.Village v, long day) {
        Match have = MATCHES.get(v.id());
        if (have != null) return have.day == day ? have : null;
        List<VillageFolkEntity> squad = members(v.id(), Club.SPORTS);
        squad.removeIf(f -> f.onShift() && !f.offWorkNow() || f.isSleeping());
        if (squad.size() < Club.SPORTS.least) return null;
        Field field = green(level, v);
        ItemStack ball = Crafts.takeOne(level, v, Kickabout::isFootball);
        boolean stores = !ball.isEmpty();
        Vec3 c = field.point(0, 0);
        FootballEntity e = FootballEntity.setDown(level, c.x, c.y + 0.3, c.z, stores ? ball : new ItemStack(LeisureItems.LEATHER_FOOTBALL.get()), v.id());
        if (e == null) {
            if (stores) Crafts.store(level, v, ball);
            return null;
        }
        Match m = new Match(field, e.getUUID(), day, level.getGameTime(), stores);
        for (int i = 0; i < squad.size() && i < 10; i++) (i % 2 == 0 ? m.greens : m.golds).add(squad.get(i).getUUID());
        if (!field.pitch()) mark(level, v.id(), m);
        MATCHES.put(v.id(), m);
        Villages.tell(v.id(), day, "the village sports team took to " + (field.pitch() ? "the pitch" : "the green") + " for a match, Greens against Golds");
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().closerThan(v.centre(), 160)) {
                p.displayClientMessage(Component.literal("Match on the green in " + Villages.name(v.id()) + ": Greens against Golds."), true);
            }
        }
        return m;
    }

    /** The green marked for the day: a goal of two posts at each end and a flag at each corner (taken up after). */
    static void mark(ServerLevel level, UUID village, Match m) {
        Field f = m.field;
        List<BlockPos> goals = new ArrayList<>();
        for (int end = -1; end <= 1; end += 2) {
            goals.add(f.at(-2, end * (HALF_LONG + 1)));
            goals.add(f.at(2, end * (HALF_LONG + 1)));
        }
        for (BlockPos g : goals) {
            BlockPos at = new BlockPos(g.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g.getX(), g.getZ()), g.getZ());
            if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.above()).canBeReplaced()) continue;
            level.setBlock(at, Blocks.BIRCH_FENCE.defaultBlockState(), 2);
            level.setBlock(at.above(), Blocks.BIRCH_FENCE.defaultBlockState(), 2);
            m.marks.add(at);
            m.marks.add(at.above());
        }
        for (int u = -1; u <= 1; u += 2) {
            for (int w = -1; w <= 1; w += 2) {
                BlockPos g = f.at(u * (HALF_WIDE + 1), w * (HALF_LONG + 1));
                BlockPos at = new BlockPos(g.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g.getX(), g.getZ()), g.getZ());
                if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.above()).canBeReplaced()) continue;
                level.setBlock(at, Blocks.BIRCH_FENCE.defaultBlockState(), 2);
                level.setBlock(at.above(), Blocks.RED_WOOL.defaultBlockState(), 2);
                m.marks.add(at);
                m.marks.add(at.above());
            }
        }
        // The halfway line and the touchlines, chalked in white carpet on the grass.
        for (int w = -HALF_LONG; w <= HALF_LONG; w += 1) {
            for (int u : new int[]{ -HALF_WIDE - 1, HALF_WIDE + 1 }) chalk(level, m, f.at(u, w));
        }
        for (int u = -HALF_WIDE; u <= HALF_WIDE; u++) chalk(level, m, f.at(u, 0));
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : m.marks) sb.append(sb.length() == 0 ? "" : ";").append(p.asLong());
        Ledger.note(village, "life.green", sb.toString());
    }

    static void chalk(ServerLevel level, Match m, BlockPos g) {
        BlockPos at = new BlockPos(g.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g.getX(), g.getZ()), g.getZ());
        if (!level.getBlockState(at).isAir() || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) return;
        level.setBlock(at, Blocks.WHITE_CARPET.defaultBlockState(), 2);
        m.marks.add(at);
    }

    /** Full time (or called off): the score into the chronicle, the green cleared, the ball away. */
    static void endMatch(ServerLevel level, Villages.Village v, long day, String why) {
        Match m = MATCHES.get(v.id());
        if (m == null) {
            String kept = Ledger.note(v.id(), "life.green");
            if (kept != null && !kept.isEmpty()) clearMarks(level, v.id(), kept);
            return;
        }
        MATCHES.remove(v.id());
        clearMarks(level, v.id(), null);
        for (BlockPos p : m.marks) {
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.BIRCH_FENCE) || st.is(Blocks.RED_WOOL) || st.is(Blocks.WHITE_CARPET)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        if (level.getEntity(m.ball) instanceof FootballEntity b) {
            if (m.fromStores) Crafts.store(level, v, b.getItem());
            b.discard();
        }
        String result = m.greenGoals == m.goldGoals ? "the Greens and the Golds drew " + m.greenGoals + "–" + m.goldGoals
            : (m.greenGoals > m.goldGoals ? "the Greens beat the Golds " + m.greenGoals + "–" + m.goldGoals : "the Golds beat the Greens " + m.goldGoals + "–" + m.greenGoals);
        Villages.tell(v.id(), day, "On the green, " + result + (m.lastScorer == null ? "" : "; the last goal " + m.lastScorer + "'s"));
        LAST_RESULT.put(v.id(), result);
        for (UUID u : m.greens) if (level.getEntity(u) instanceof VillageFolkEntity f) f.persona().remember(day, "the match on the green: " + result, 3);
        for (UUID u : m.golds) if (level.getEntity(u) instanceof VillageFolkEntity f) f.persona().remember(day, "the match on the green: " + result, 3);
    }

    private static final Map<UUID, String> LAST_RESULT = new ConcurrentHashMap<>();

    @Nullable
    public static String lastResult(UUID village) {
        return LAST_RESULT.get(village);
    }

    static void clearMarks(ServerLevel level, UUID village, @Nullable String kept) {
        if (kept == null) kept = Ledger.note(village, "life.green");
        if (kept != null && !kept.isEmpty()) {
            for (String s : kept.split(";")) {
                try {
                    BlockPos p = BlockPos.of(Long.parseLong(s));
                    if (!level.isLoaded(p)) continue;
                    BlockState st = level.getBlockState(p);
                    if (st.is(Blocks.BIRCH_FENCE) || st.is(Blocks.RED_WOOL) || st.is(Blocks.WHITE_CARPET)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                } catch (RuntimeException ignored) { }
            }
        }
        Ledger.note(village, "life.green", "");
    }

    /** A player of the match, a beat: to the ball if it is nearest its side's, a kick at the other goal; else its place. */
    static void play(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        Match m = start(level, v, day);
        if (m == null) {
            f.hobbyNow = "waiting for enough of the team to turn up";
            BlockPos at = green(level, v).centre;
            RestDay.arrive(f, spot(at, f, 3));
            return;
        }
        if (level.getGameTime() - m.kickoff > MATCH_TICKS) {
            endMatch(level, v, day, "full time");
            return;
        }
        if (!(level.getEntity(m.ball) instanceof FootballEntity ball)) {
            endMatch(level, v, day, "the ball lost");
            return;
        }
        ball.inUse(level.getGameTime());
        boolean green = m.greens.contains(f.getUUID());
        if (!green && !m.golds.contains(f.getUUID())) {
            (m.greens.size() <= m.golds.size() ? m.greens : m.golds).add(f.getUUID());
            green = m.greens.contains(f.getUUID());
        }
        int attack = green ? 1 : -1;
        f.hobbyNow = "playing for the " + (green ? "Greens" : "Golds") + " on the green";
        f.lastLeisureTick = f.tickCount;
        Vec3 bp = ball.position();
        double[] uw = m.field.local(bp);
        // A goal?
        if (Math.abs(uw[1]) > HALF_LONG + 0.5 && Math.abs(uw[0]) < 2.0 && bp.y < m.field.centre().getY() + 2.5) {
            boolean greensScored = uw[1] > 0;
            if (greensScored) m.greenGoals++;
            else m.goldGoals++;
            VillageFolkEntity scorer = ball.lastKicker() != null && level.getEntity(ball.lastKicker()) instanceof VillageFolkEntity s ? s : null;
            m.lastScorer = scorer != null ? scorer.displayNameCap() : null;
            Vec3 c = m.field.point(0, 0);
            ball.moveTo(c.x, c.y + 0.3, c.z);
            ball.setDeltaMovement(Vec3.ZERO);
            level.playSound(null, BlockPos.containing(c), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.8F, 1.4F);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, bp.x, bp.y + 1, bp.z, 12, 0.6, 0.6, 0.6, 0.0);
            if (scorer != null) FolkTalk.speak(scorer, FolkTalk.pick(scorer.getRandom(), "Get in.", "Meant that.", "Did you see that?", "One-nil. Or whatever it is.",
                "Practice, that.", "Shoot, they said. So I did."));
            cheer(level, m, greensScored);
            return;
        }
        // Out of play: back on the field at the line.
        if (Math.abs(uw[0]) > HALF_WIDE + 2.5 || Math.abs(uw[1]) > HALF_LONG + 3.0) {
            Vec3 back = m.field.point(Math.max(-HALF_WIDE, Math.min(HALF_WIDE, uw[0])), Math.max(-HALF_LONG + 1, Math.min(HALF_LONG - 1, uw[1])));
            ball.moveTo(back.x, back.y + 0.3, back.z);
            ball.setDeltaMovement(Vec3.ZERO);
        }
        // Am I my side's nearest to the ball?
        List<UUID> side = green ? m.greens : m.golds;
        VillageFolkEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (UUID u : side) {
            if (level.getEntity(u) instanceof VillageFolkEntity o && o.isAlive()) {
                double d = o.distanceToSqr(bp);
                if (d < best) { best = d; nearest = o; }
            }
        }
        if (nearest == f) {
            if (f.distanceToSqr(bp) > 1.6 * 1.6) {
                if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 10) {
                    f.getNavigation().moveTo(bp.x, bp.y, bp.z, 1.15D);
                    f.hobbyTick = f.tickCount;
                }
            } else {
                Vec3 goal = m.field.point((f.getRandom().nextDouble() - 0.5) * 2.4, attack * (HALF_LONG + 1));
                Vec3 to = goal.subtract(bp);
                double dist = Math.sqrt(to.x * to.x + to.z * to.z);
                Vec3 dir = new Vec3(to.x / dist, 0, to.z / dist);
                double power = dist > 8 ? 0.55 : 0.4 + f.getRandom().nextDouble() * 0.15;
                double spread = (f.getRandom().nextDouble() - 0.5) * 0.5;
                Vec3 kick = new Vec3(dir.x + dir.z * spread, 0, dir.z - dir.x * spread);
                ball.kick(new Vec3(kick.x * power, 0.08 + f.getRandom().nextDouble() * 0.1, kick.z * power), f);
                f.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            int i = Math.max(0, side.indexOf(f.getUUID()));
            double u = -HALF_WIDE + 1 + (i * 3) % (2 * HALF_WIDE - 1);
            double w = -attack * 2 + attack * (uw[1] * attack > 0 ? 2 : -1) + (i % 2 == 0 ? -attack * 3 : 0);
            Vec3 at = m.field.point(u, Math.max(-HALF_LONG + 1, Math.min(HALF_LONG - 1, w + uw[1] * 0.4)));
            if (f.position().distanceToSqr(at) > 4 && (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 30)) {
                f.getNavigation().moveTo(at.x, at.y, at.z, 0.9D);
                f.hobbyTick = f.tickCount;
            }
            f.getLookControl().setLookAt(bp.x, bp.y, bp.z);
        }
    }

    /** The crowd on its feet. */
    static void cheer(ServerLevel level, Match m, boolean greens) {
        for (UUID u : m.crowd) {
            if (level.getEntity(u) instanceof VillageFolkEntity o && o.isAlive()) {
                o.getJumpControl().jump();
                if (o.getRandom().nextInt(2) == 0) o.sayLater(FolkTalk.pick(o.getRandom(), "Goal!", "About time!", "Offside, surely.", "Lovely!",
                    "Again!", greens ? "Come on, Greens!" : "Come on, Golds!"), 5 + o.getRandom().nextInt(25));
            }
        }
    }

    /** A folk with the afternoon off: to the touchline to watch (and cheer). True while it is watching. */
    static boolean spectate(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        Match m = MATCHES.get(v.id());
        if (m == null || m.day != day || !WeekendLife.free(f) || f.isBaby() && f.getRandom().nextInt(2) == 0) return false;
        if (!m.crowd.contains(f.getUUID())) {
            boolean keen = LifeWeights.has(f, LifeWeights.Mark.CHATTY) || LifeWeights.has(f, LifeWeights.Mark.CHEERFUL) || LifeWeights.has(f, LifeWeights.Mark.SPORTY);
            if (m.crowd.size() >= 12 || !keen && LifeWeights.roll(f, day, 55) > 0.6 || f.distanceToSqr(Vec3.atCenterOf(m.field.centre())) > 96 * 96) return false;
            m.crowd.add(f.getUUID());
            LifeLog.note(f, day, "Watched the match on the green", LifeWeights.why(f, LifeWeights.Mark.SPORTY, LifeWeights.Mark.CHEERFUL, LifeWeights.Mark.CHATTY));
        }
        int i = new ArrayList<>(m.crowd).indexOf(f.getUUID());
        int side = i % 2 == 0 ? 1 : -1;
        Vec3 at = m.field.point(side * (HALF_WIDE + 3), -HALF_LONG + 2 + (i / 2) * 2.5 % (2 * HALF_LONG - 3));
        BlockPos stand = BlockPos.containing(at);
        f.hobbyNow = "watching the match on the green";
        f.lastLeisureTick = f.tickCount;
        if (RestDay.arrive(f, stand) && level.getEntity(m.ball) instanceof FootballEntity b) {
            f.getLookControl().setLookAt(b, 30.0F, 30.0F);
            if (f.getRandom().nextInt(120) == 0) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Pass it!", "Man on!", "Ref!", "Use your feet!",
                "My nan could have scored that.", "Hold your line!"));
        }
        return true;
    }

    /** Tests: the match now, begun if it can be. */
    @Nullable
    public static String matchForTests(ServerLevel level, Villages.Village v) {
        Match m = start(level, v, level.getDayTime() / 24000L);
        return m == null ? null : "Greens " + m.greens.size() + " v Golds " + m.golds.size() + (m.field.pitch() ? " on the pitch" : " on the green");
    }

    public static void endForTests(ServerLevel level, Villages.Village v) {
        endMatch(level, v, level.getDayTime() / 24000L, "tests");
    }

    public static int crowd(UUID village) {
        Match m = MATCHES.get(village);
        return m == null ? 0 : m.crowd.size();
    }

    public static int goals(UUID village) {
        Match m = MATCHES.get(village);
        return m == null ? 0 : m.greenGoals + m.goldGoals;
    }

    /** Clubs in a line for a folk's card: "Fishing club (an angler); Pub quiz (bookish)". */
    public static String cardLine(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        for (Club c : clubsOf(f)) {
            String why = c.because(f);
            out.add(c.title + (why.isEmpty() ? "" : " (" + why + ")"));
        }
        return String.join("; ", out);
    }

    /** Every club in the town: its members, when and where it meets, and why it can't if it can't (the week page). */
    public static Map<Club, String> summary(ServerLevel level, UUID village) {
        Map<Club, String> out = new EnumMap<>(Club.class);
        for (Club c : Club.values()) {
            String no = whyNot(level, village, c);
            int n = members(village, c).size() + players(village, c).size();
            out.put(c, n + (n == 1 ? " member" : " members") + "; meets " + c.when() + ", " + c.where + (no == null ? "" : ". " + no));
        }
        return out;
    }
}
