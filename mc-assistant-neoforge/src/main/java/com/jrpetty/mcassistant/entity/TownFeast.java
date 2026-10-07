package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [culture2] A festival of the town's own, on top of the year's that every town keeps (Festivals): from what it is
 * known for, or its land.
 * <ul>
 * <li><b>The Herring Fair</b> (a fishing town): stalls of barrels and lanterns round the square; the contest for the best
 *     catch since the last fair.</li>
 * <li><b>Lantern Night</b> (a town that came through a fire or a great storm, or the long nights of the north): lanterns
 *     all round the square and fireworks; the contest for the best-kept house.</li>
 * <li><b>The Iron Fair</b> (a smithing town): forge fires and lanterns, the ring of anvils, fireworks; the contest for the
 *     finest smith of the year.</li>
 * <li><b>Bloom Day</b> (flowers and bees): flowers set out round the square, petals on the wind; the best garden.</li>
 * <li><b>The Stone Feast</b> (the mountains and the mines): stone posts with lanterns on them; the strongest miner, by
 *     the stone they brought up since the last feast.</li>
 * <li><b>The Night of Stars</b> (stargazers, a learned town, clear desert skies): candles low on the square, every face
 *     turned up to the sky; a star is named, and the town votes for whose name it takes.</li>
 * <li><b>Harvest Home</b> (a farming town): hay and lit pumpkins round the square; the ploughing match, for the most sown.</li>
 * </ul>
 * Each is a gathering at dusk on its own day of the town's year (kept the next evening if the rain puts it off, as the
 * year's festivals are): the decorations go out in the afternoon by a hand at the town's works, out of the stores, and
 * come in the next morning; the elder speaks; the town's own dish is eaten (Cuisine); a tune is played; and the contest
 * is judged, its winner given a purse out of the treasury and written into the chronicle. A player the town counts a
 * friend is told of it that morning, and one who comes is given a portion of the town's dish.
 */
public final class TownFeast {

    private TownFeast() {}

    public enum Feast {
        HERRING_FAIR("the Herring Fair", 15, "the best catch"),
        LANTERN_NIGHT("Lantern Night", 22, "the best-kept house"),
        IRON_FAIR("the Iron Fair", 8, "the finest smith"),
        BLOOM_DAY("Bloom Day", 4, "the best garden"),
        STONE_FEAST("the Stone Feast", 17, "the strongest miner"),
        NIGHT_OF_STARS("the Night of Stars", 26, "the naming of a star"),
        HARVEST_HOME("Harvest Home", 18, "the ploughing match");

        public final String words, contest;
        final int dayOfYear;

        Feast(String words, int dayOfYear, String contest) {
            this.words = words;
            this.dayOfYear = dayOfYear;
            this.contest = contest;
        }

        @Nullable
        static Feast byName(@Nullable String s) {
            if (s == null) return null;
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** The prize to the contest's winner, out of the treasury. */
    static final int PRIZE = 5;
    /** The gathering's subject begins so (Festivals tells its own from ours by it). */
    static final String OURS = "own|";

    /** The festival called now (tests, /village ways feast now). */
    private static final Map<UUID, Boolean> CALLED = new ConcurrentHashMap<>();
    /** Rockets sent up at each town's festival tonight. */
    private static final Map<UUID, Integer> ROCKETS = new ConcurrentHashMap<>();
    /** The day each folk was last at its town's own festival (its mood). */
    private static final Map<UUID, Long> WAS_THERE = new ConcurrentHashMap<>();
    /** The day the town's friends were told of tonight's festival (one telling a day). */
    private static final Map<UUID, Long> INVITED = new ConcurrentHashMap<>();

    static void resetForTests() {
        CALLED.clear();
        ROCKETS.clear();
        WAS_THERE.clear();
        INVITED.clear();
    }

    /** The town's festival, once chosen; null before. */
    @Nullable
    public static Feast of(@Nullable UUID village) {
        return village == null ? null : Feast.byName(TownWays.note(village, "feast"));
    }

    /** Tests: this town's festival set so. */
    public static void setForTests(UUID village, Feast f) {
        TownWays.note(village, "feast", f.name());
    }

    // ------------------------------------------------------------------ choosing it

    /** What the town is known for, festival by festival. */
    static Map<Feast, Integer> scores(UUID village) {
        Map<Feast, Integer> s = new EnumMap<>(Feast.class);
        for (Feast f : Feast.values()) s.put(f, 0);
        Map<AssistantEntity.StationTask, Integer> t = TownWays.trades(village);
        Homeland.Land land = Homeland.of(village);
        Values.Value heart = TownWays.heart(village);
        int fishers = t.getOrDefault(AssistantEntity.StationTask.FISH, 0), smiths = t.getOrDefault(AssistantEntity.StationTask.SMITH, 0)
            + t.getOrDefault(AssistantEntity.StationTask.SMELT, 0), bees = t.getOrDefault(AssistantEntity.StationTask.BEEKEEP, 0),
            miners = t.getOrDefault(AssistantEntity.StationTask.MINE, 0) + t.getOrDefault(AssistantEntity.StationTask.CAVE, 0),
            farmers = t.getOrDefault(AssistantEntity.StationTask.FARM, 0);
        s.merge(Feast.HERRING_FAIR, fishers * 2 + (land == Homeland.Land.COAST ? 5 : land == Homeland.Land.RIVER || land == Homeland.Land.SWAMP ? 3 : 0), Integer::sum);
        s.merge(Feast.IRON_FAIR, smiths * 3 + (Villages.ageOf(village).ordinal() >= Villages.Age.IRON.ordinal() ? 2 : 0), Integer::sum);
        s.merge(Feast.BLOOM_DAY, bees * 3 + (land == Homeland.Land.MEADOW ? 6 : land == Homeland.Land.JUNGLE ? 2 : 0), Integer::sum);
        s.merge(Feast.STONE_FEAST, miners * 2 + (land == Homeland.Land.MOUNTAIN || land == Homeland.Land.BADLANDS ? 5 : 0), Integer::sum);
        s.merge(Feast.HARVEST_HOME, farmers * 3 / 2 + (land == Homeland.Land.PLAINS || land == Homeland.Land.SAVANNA ? 4 : 0)
            + (heart == Values.Value.FOOD ? 2 : 0), Integer::sum);
        int stargazers = 0, flowers = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || !f.persona().rolled()) continue;
            if (f.persona().hobby() == Persona.Hobby.STARGAZING) stargazers++;
            flowers += f.persona().flowersPlanted();
        }
        s.merge(Feast.BLOOM_DAY, Math.min(4, flowers / 6), Integer::sum);
        s.merge(Feast.NIGHT_OF_STARS, stargazers * 2 + (heart == Values.Value.PROGRESS ? 3 : 0)
            + (Villages.hasBuilt(village, "library") ? 2 : 0) + (land == Homeland.Land.DESERT ? 3 : 0), Integer::sum);
        // A town that came through a fire or a great storm lights its lanterns against the dark; the north does anyway.
        int fires = 0;
        for (Chronicle.Entry e : Chronicle.of(village)) {
            String x = e.text();
            if (x.startsWith("Fire ") || x.startsWith("the fire burnt") || x.contains("a great storm broke")) fires++;
        }
        s.merge(Feast.LANTERN_NIGHT, Math.min(6, fires * 3) + (land == Homeland.Land.SNOW || land == Homeland.Land.TAIGA ? 4 : 0), Integer::sum);
        return s;
    }

    /**
     * The festival chosen (TownWays.daily): once, three days on from the founding, from what the town is known for by
     * then; and looked at again each year after, when what the town has become known for since may give it another (it
     * must be known for it by three more). Into its chronicle.
     */
    static void choose(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Feast now = of(id);
        long founded = FoundingDay.founded(id);
        if (now == null && founded >= 0 && day - founded < 3) return;
        Map<Feast, Integer> s = scores(id);
        Feast best = Feast.HARVEST_HOME;
        for (Feast f : Feast.values()) if (s.get(f) > s.get(best)) best = f;
        if (now == null) {
            TownWays.note(id, "feast", best.name());
            TownWays.note(id, "feast.since", Long.toString(day));
            Villages.tell(id, day, "the town resolved to keep " + best.words + " every year, on the " + ordinal(best.dayOfYear + 1)
                + " day of its year");
            return;
        }
        long since = Culture.num(String.valueOf(TownWays.note(id, "feast.since")), day);
        if (best == now || day - since < TownCalendar.YEAR_DAYS || s.get(best) < s.get(now) + 3) return;
        TownWays.note(id, "feast", best.name());
        TownWays.note(id, "feast.since", Long.toString(day));
        Villages.tell(id, day, "the town will keep " + best.words + " now, in place of " + now.words + ": it is known for other things these days");
    }

    /** Tests: the festival chosen now (whatever the day), from what the town is known for. */
    public static Feast chooseForTests(ServerLevel level, Villages.Village v) {
        TownWays.note(v.id(), "feast", null);
        choose(level, v, Math.max(level.getDayTime() / 24000L, FoundingDay.founded(v.id()) + 3));
        return of(v.id());
    }

    static String ordinal(int n) {
        int m = n % 100;
        String suffix = m >= 11 && m <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    // ------------------------------------------------------------------ its day

    /** The day of the town's year it falls on: its own, or the day after if a festival of the year's has that one. */
    static int dayOfYear(UUID village, long day, Feast f) {
        int d = f.dayOfYear;
        if (d == Festivals.dayOfYear(village, day, Festivals.Feast.MAYPOLE)) d++;
        return d;
    }

    /** The world's day the town's festival falls on in the year that holds {@code day}. */
    static long dayThisYear(UUID village, long day, Feast f) {
        return Seasons.dayOf(village, day, dayOfYear(village, day, f));
    }

    /** Is today the town's festival? */
    public static boolean today(UUID village, long day) {
        Feast f = of(village);
        return f != null && dayThisYear(village, day, f) == day;
    }

    /** The next day it is kept (today, if today and not yet kept). */
    public static long next(UUID village, long day) {
        Feast f = of(village);
        if (f == null) return -1;
        long d = dayThisYear(village, day, f);
        if (d > day || d == day && keptFor(village) != d) return d;
        return dayThisYear(village, day + TownCalendar.YEAR_DAYS, f);
    }

    static long keptFor(UUID village) {
        return Culture.num(String.valueOf(TownWays.note(village, "feast.kept")), Long.MIN_VALUE);
    }

    // ------------------------------------------------------------------ the decorations

    /** A thing set out on the square for the festival: what, and what pays for it out of the stores. */
    record Decor(BlockState block, Predicate<ItemStack> pay, String words) {}

    static List<Decor> decor(Feast f) {
        return switch (f) {
            case HERRING_FAIR -> List.of(new Decor(Blocks.BARREL.defaultBlockState(), s -> s.is(Items.BARREL), "stalls"),
                new Decor(Blocks.LANTERN.defaultBlockState(), s -> s.is(Items.LANTERN), "lanterns"));
            case LANTERN_NIGHT -> List.of(new Decor(Blocks.LANTERN.defaultBlockState(), s -> s.is(Items.LANTERN), "lanterns"),
                new Decor(Blocks.TORCH.defaultBlockState(), s -> s.is(Items.TORCH), "torches"));
            case IRON_FAIR -> List.of(new Decor(Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), s -> s.is(Items.CAMPFIRE), "forge fires"),
                new Decor(Blocks.LANTERN.defaultBlockState(), s -> s.is(Items.LANTERN), "lanterns"));
            case BLOOM_DAY -> List.of(new Decor(Blocks.AIR.defaultBlockState(), s -> s.is(ItemTags.SMALL_FLOWERS), "flowers"));
            case STONE_FEAST -> List.of(new Decor(Blocks.COBBLESTONE_WALL.defaultBlockState(), s -> s.is(Items.COBBLESTONE_WALL) || s.is(Items.COBBLESTONE), "stone posts"),
                new Decor(Blocks.LANTERN.defaultBlockState(), s -> s.is(Items.LANTERN), "lanterns"));
            case NIGHT_OF_STARS -> List.of(new Decor(Blocks.CANDLE.defaultBlockState().setValue(net.minecraft.world.level.block.CandleBlock.LIT, true),
                s -> s.is(Items.CANDLE), "candles"));
            case HARVEST_HOME -> List.of(new Decor(Blocks.HAY_BLOCK.defaultBlockState(), s -> s.is(Items.HAY_BLOCK), "hay"),
                new Decor(Blocks.JACK_O_LANTERN.defaultBlockState(), s -> s.is(Items.JACK_O_LANTERN) || s.is(Items.CARVED_PUMPKIN), "lit pumpkins"));
        };
    }

    /** Eight places round the square, six blocks out. */
    static final int[][] RING = { { 6, 0 }, { -6, 0 }, { 0, 6 }, { 0, -6 }, { 4, 4 }, { -4, 4 }, { 4, -4 }, { -4, -4 } };

    private static final String PLACED = "feast.placed";

    /**
     * The town's minute (TownWays, every second): the morning's word to its friends among the players; the decorations
     * out in the afternoon of its day; taken in again the next morning.
     */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        Feast f = of(id);
        if (f == null) return;
        if (t >= 1000L && t < 12000L) takeDown(level, v, day);
        if (Raids.underAlarm(id)) return;
        long due = dayThisYear(id, day, f);
        boolean ourDay = (day == due || day == due + 1) && keptFor(id) != due;
        if (!ourDay) return;
        if (t >= 6000L && t < 12000L && INVITED.getOrDefault(id, -1L) != day) {
            INVITED.put(id, day);
            invite(level, v, f);
        }
        if (t >= 8000L && t < 12000L && level.getGameTime() % 100L == 13L) setOut(level, v, f, due);
    }

    /** The decorations set out round the square, one a turn by a hand at the town's works, out of the stores. */
    static int setOut(ServerLevel level, Villages.Village v, Feast f, long due) {
        UUID id = v.id();
        List<String[]> placed = Culture.rows(id, TownWays.PREFIX + PLACED);
        if (placed.size() >= RING.length) return 0;
        int done = 0;
        for (int i = placed.size(); i < RING.length; i++) {
            Decor d = decor(f).get(i % decor(f).size());
            BlockPos at = Festivals.groundAt(level, v.centre().getX() + RING[i][0], v.centre().getZ() + RING[i][1]);
            if (at == null || !Festivals.open(level, at) || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) continue;
            ItemStack paid = Crafts.takeOne(level, v, d.pay());
            if (paid.isEmpty()) {
                // A wall post of a cobblestone, for the Stone Feast; nothing else is made for a night.
                continue;
            }
            if (!TownJobs.atWork(level, v, "festival", at, "setting out the " + d.words + " for " + f.words)) {
                Crafts.store(level, v, paid);
                return done;
            }
            BlockState state = d.block();
            if (f == Feast.BLOOM_DAY) state = Block.byItem(paid.getItem()).defaultBlockState();
            if (!state.canSurvive(level, at)) {
                Crafts.store(level, v, paid);
                continue;
            }
            level.setBlock(at, state, 3);
            placed.add(new String[]{ Long.toString(at.asLong()), Long.toString(due), BuiltInRegistries.ITEM.getKey(paid.getItem()).toString() });
            // A lantern on the Stone Feast's posts.
            if (f == Feast.STONE_FEAST && state.is(Blocks.COBBLESTONE_WALL) && level.getBlockState(at.above()).isAir()) {
                ItemStack lamp = Crafts.takeOne(level, v, s -> s.is(Items.LANTERN) || s.is(Items.TORCH));
                if (!lamp.isEmpty()) {
                    level.setBlock(at.above(), (lamp.is(Items.LANTERN) ? Blocks.LANTERN : Blocks.TORCH).defaultBlockState(), 3);
                    placed.add(new String[]{ Long.toString(at.above().asLong()), Long.toString(due), BuiltInRegistries.ITEM.getKey(lamp.getItem()).toString() });
                }
            }
            done++;
            break;
        }
        Culture.rows(id, TownWays.PREFIX + PLACED, placed);
        return done;
    }

    /** Tests and the stage: every decoration the stores run to set out now. How many went out. */
    public static int setOutForTests(ServerLevel level, Villages.Village v) {
        Feast f = of(v.id());
        if (f == null) return 0;
        int n = 0;
        for (int i = 0; i < RING.length; i++) n += setOut(level, v, f, dayThisYear(v.id(), level.getDayTime() / 24000L, f));
        return n;
    }

    /** What is set out for the festival, the morning after (or any morning it is still out): taken in, into the stores. */
    static int takeDown(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<String[]> placed = Culture.rows(id, TownWays.PREFIX + PLACED);
        if (placed.isEmpty()) return 0;
        long due = Culture.num(placed.get(0)[1], day);
        if (day <= due + 1 && keptFor(id) != due) return 0;           // still to be kept
        if (day <= due) return 0;
        int n = 0;
        // Top first (the Stone Feast's lanterns off their posts before the posts).
        placed.sort((a, b) -> Integer.compare(BlockPos.of(Culture.num(b[0], 0)).getY(), BlockPos.of(Culture.num(a[0], 0)).getY()));
        for (String[] r : placed) {
            BlockPos at = BlockPos.of(Culture.num(r[0], 0));
            if (!level.isLoaded(at)) continue;
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(r[2]));
            BlockState s = level.getBlockState(at);
            if (!s.isAir() && (s.getBlock().asItem() == it || s.getBlock() == Block.byItem(it) || it == Items.COBBLESTONE && s.is(Blocks.COBBLESTONE_WALL)
                    || it == Items.CARVED_PUMPKIN && s.is(Blocks.JACK_O_LANTERN))) {
                level.removeBlock(at, false);
                Crafts.store(level, v, new ItemStack(it));
                n++;
            }
        }
        Culture.rows(id, TownWays.PREFIX + PLACED, new ArrayList<>());
        return n;
    }

    /** Tests: the decorations taken in now, whatever the morning. */
    public static int takeDownForTests(ServerLevel level, Villages.Village v) {
        TownWays.note(v.id(), "feast.kept", Long.toString(level.getDayTime() / 24000L - 2));
        List<String[]> placed = Culture.rows(v.id(), TownWays.PREFIX + PLACED);
        for (String[] r : placed) r[1] = Long.toString(level.getDayTime() / 24000L - 2);
        Culture.rows(v.id(), TownWays.PREFIX + PLACED, placed);
        return takeDown(level, v, level.getDayTime() / 24000L);
    }

    /** The decorations out now, by where they stand (tests, the stage). */
    public static List<BlockPos> placedForTests(UUID village) {
        List<BlockPos> out = new ArrayList<>();
        for (String[] r : Culture.rows(village, TownWays.PREFIX + PLACED)) out.add(BlockPos.of(Culture.num(r[0], 0)));
        return out;
    }

    /** The town's friends among the players near it told of tonight's festival (once that day). */
    static int invite(ServerLevel level, Villages.Village v, Feast f) {
        int n = 0;
        for (ServerPlayer p : level.players()) {
            if (!p.blockPosition().closerThan(v.centre(), 160)) continue;
            if (!Standing.of(v.id(), p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.FRIEND)) continue;
            Cuisine.Dish d = Cuisine.dishOf(v.id());
            p.sendSystemMessage(Component.literal(Villages.name(v.id()) + " keeps " + f.words + " tonight on the square, and you're invited: "
                + f.contest + (d == null ? "" : ", and " + d.words + " for all") + ".").withStyle(net.minecraft.ChatFormatting.GOLD));
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ the gathering (Festivals, Assemblies)

    /** Is this gathering's subject ours? */
    public static boolean ours(@Nullable String subject) {
        return subject != null && subject.startsWith(OURS);
    }

    /**
     * Tonight's festival of the town's own, if today is its day or the evening after a wet one (Festivals.evening, after
     * the year's own): a ring on the square round its heart.
     */
    @Nullable
    static Assemblies.Assembly evening(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Feast f = of(id);
        if (f == null) return null;
        long due = dayThisYear(id, day, f);
        if (day != due && day != due + 1 || keptFor(id) == due) return null;
        if (level.isRaining() && day == due) return null;
        return gathering(v, due, day);
    }

    static Assemblies.Assembly gathering(Villages.Village v, long due, long day) {
        return new Assemblies.Assembly(v.id(), Assemblies.Kind.FESTIVAL, OURS + due, day, v.centre(), Direction.SOUTH, Assemblies.Layout.RING);
    }

    /** Called now (Festivals.calledNow, for callNow): tonight's festival, whatever the hour. */
    @Nullable
    static Assemblies.Assembly calledNow(ServerLevel level, Villages.Village v, long day) {
        if (CALLED.remove(v.id()) == null || of(v.id()) == null) return null;
        return gathering(v, day, day);
    }

    /** For the tests and /village ways feast now: the town's festival called now, whatever the hour. True if it began. */
    public static boolean callNow(ServerLevel level, Villages.Village v) {
        CALLED.put(v.id(), true);
        try {
            return Assemblies.startNow(level, v, Assemblies.Kind.FESTIVAL);
        } finally {
            CALLED.remove(v.id());
        }
    }

    static long dueOf(String subject) {
        return Culture.num(subject.substring(OURS.length()), -1);
    }

    /** "the Herring Fair". */
    static String describe(String subject) {
        Feast f = null;
        return "the town's own festival";
    }

    /** "the Herring Fair" (Assemblies.describe): the village's own. */
    static String describe(UUID village) {
        Feast f = of(village);
        return f == null ? "the town's own festival" : f.words;
    }

    /** What the elder says (Assemblies.script). */
    static void script(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        Feast f = of(a.village);
        if (f == null) return;
        String name = Villages.name(a.village);
        Cuisine.Dish d = Cuisine.dishOf(a.village);
        s.add(new Assemblies.Line(null, "Welcome, all, to " + f.words + "! " + name + "'s own day.", '!', null));
        s.add(new Assemblies.Line(null, switch (f) {
            case HERRING_FAIR -> "The boats are in, the stalls are up. Let's see who brought in the most this year!";
            case LANTERN_NIGHT -> "We light the lanterns for every night we came through. Let them burn bright!";
            case IRON_FAIR -> "Hear the anvils ring! To our smiths, who keep this town in iron.";
            case BLOOM_DAY -> "The flowers are out and the bees are busy. Who has the finest garden in " + name + "?";
            case STONE_FEAST -> "To the stone under our feet, and the miners who know it best!";
            case NIGHT_OF_STARS -> "Look up, all of you. Tonight we give a star a name.";
            case HARVEST_HOME -> "The fields are sown and the barns are filling. Who sowed the most this year?";
        }, '!', null));
        if (d != null) s.add(new Assemblies.Line(null, "There's " + d.words + " for everybody. Eat, and be glad!", '!', null));
        s.add(new Assemblies.Line(null, "The prize for " + f.contest + " is given at the end. Now — enjoy yourselves!", '!', null));
    }

    private static final float[] TUNE = { 0.71F, 0.84F, 0.94F, 1.06F, 0.94F, 0.84F, 0.71F, 0.63F, 0.71F, 0.94F, 1.19F, 1.06F };

    private static SoundEvent instrument(Feast f) {
        return switch (f) {
            case HERRING_FAIR -> SoundEvents.NOTE_BLOCK_FLUTE.value();
            case LANTERN_NIGHT -> SoundEvents.NOTE_BLOCK_BELL.value();
            case IRON_FAIR -> SoundEvents.NOTE_BLOCK_IRON_XYLOPHONE.value();
            case BLOOM_DAY -> SoundEvents.NOTE_BLOCK_CHIME.value();
            case STONE_FEAST -> SoundEvents.NOTE_BLOCK_BASS.value();
            case NIGHT_OF_STARS -> SoundEvents.NOTE_BLOCK_HARP.value();
            case HARVEST_HOME -> SoundEvents.NOTE_BLOCK_BANJO.value();
        };
    }

    /**
     * A folk's part once the words are said (Festivals.mingle): the town's dish eaten (once), the tune, the festival's
     * own doings (the stalls, the lanterns gazed at, the anvils' ring, the petals, faces turned to the stars), a word with
     * whoever is beside it.
     */
    static boolean mingle(VillageFolkEntity f, ServerLevel level, Assemblies.Assembly a, RandomSource r) {
        Feast feast = of(a.village);
        if (feast == null) return false;
        UUID me = f.getUUID();
        long now = level.getGameTime();
        Villages.Village v = Villages.get(a.village);
        WAS_THERE.put(me, level.getDayTime() / 24000L);
        if (!a.ate.contains(me) && r.nextInt(25) == 0 && v != null) {
            a.ate.add(me);
            ItemStack dish = Cuisine.feast(level, v, f);
            if (!dish.isEmpty()) {
                level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, dish),
                    f.getX(), f.getEyeY(), f.getZ(), 6, 0.15, 0.1, 0.15, 0.03);
                f.playSound(SoundEvents.GENERIC_EAT, 0.6F, 0.9F + r.nextFloat() * 0.2F);
                f.heal(2.0F);
            }
        }
        if (me.equals(a.host) && now % 40L == 0L) {
            int beat = (int) ((now / 40L) % TUNE.length);
            level.playSound(null, a.focus, instrument(feast), SoundSource.RECORDS, 1.1F, TUNE[beat]);
            level.sendParticles(ParticleTypes.NOTE, a.focus.getX() + 0.5, a.focus.getY() + 2.0, a.focus.getZ() + 0.5, 0, beat / 24.0, 0, 0, 1);
            if ((feast == Feast.LANTERN_NIGHT || feast == Feast.IRON_FAIR) && v != null && ROCKETS.getOrDefault(a.village, 0) < 6
                    && (now / 40L) % 3L == 0L) rocket(level, v, a.focus, r);
        }
        switch (feast) {
            case HERRING_FAIR -> {
                if (r.nextInt(240) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Fresh herring! Who'll buy?", "That's a fine catch, that is.",
                    "Two for a coin, and I'm robbing myself!", "Smell that sea air!"));
            }
            case LANTERN_NIGHT -> {
                f.getLookControl().setLookAt(a.focus.getX() + 0.5, a.focus.getY() + 3.0, a.focus.getZ() + 0.5);
                if (r.nextInt(240) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Look how they shine!", "For the ones we lost.", "Bright as day!"));
            }
            case IRON_FAIR -> {
                if (r.nextInt(120) == 0) level.playSound(null, f.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.4F, 1.2F);
                if (r.nextInt(240) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Listen to them anvils!", "Finest iron for a hundred miles.", "Strike while it's hot!"));
            }
            case BLOOM_DAY -> {
                if (r.nextInt(40) == 0) level.sendParticles(ParticleTypes.CHERRY_LEAVES, f.getX(), f.getEyeY() + 1.0, f.getZ(), 3, 0.6, 0.3, 0.6, 0.0);
                if (r.nextInt(240) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Smell the roses!", "The bees love it.", "Petals everywhere!"));
            }
            case STONE_FEAST -> {
                if (r.nextInt(150) == 0) level.playSound(null, f.blockPosition(), SoundEvents.STONE_HIT, SoundSource.NEUTRAL, 0.6F, 0.8F);
                if (r.nextInt(240) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Steady stone!", "To the mountain!", "Arm-wrestle you for it."));
            }
            case NIGHT_OF_STARS -> {
                f.getLookControl().setLookAt(f.getX() + r.nextInt(5) - 2, f.getEyeY() + 20.0, f.getZ() + r.nextInt(5) - 2);
                if (r.nextInt(60) == 0) level.sendParticles(ParticleTypes.END_ROD, f.getX() + r.nextInt(9) - 4, f.getY() + 12.0, f.getZ() + r.nextInt(9) - 4,
                    1, 0.2, 0.2, 0.2, 0.01);
                if (r.nextInt(240) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "There — a shooting star!", "So many of them.", "I can see the whole sky."));
            }
            case HARVEST_HOME -> {
                if (r.nextInt(30) == 0 && f.onGround()) f.getJumpControl().jump();
                if (r.nextInt(240) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Harvest home!", "To the fields!", "Another dance!"));
            }
        }
        if (r.nextInt(300) == 0 && !a.chatted.contains(me)) {
            for (UUID u : a.seated.keySet()) {
                if (u.equals(me) || !(level.getEntity(u) instanceof VillageFolkEntity o) || o.distanceToSqr(f) > 9.0) continue;
                if (Smalltalk.chat(f, o, level)) {
                    a.chatted.add(me);
                    a.chatted.add(u);
                }
                break;
            }
        }
        return true;
    }

    /** A rocket of the stores' gunpowder and paper (the fireworks, as a celebration's). */
    private static void rocket(ServerLevel level, Villages.Village v, BlockPos at, RandomSource r) {
        if (!Crafts.take(level, v, s -> s.is(Items.GUNPOWDER), 1)) {
            ROCKETS.put(v.id(), 99);
            return;
        }
        if (!Crafts.take(level, v, s -> s.is(Items.PAPER), 1)) {
            Crafts.store(level, v, new ItemStack(Items.GUNPOWDER));
            ROCKETS.put(v.id(), 99);
            return;
        }
        Gatherings.launch(level, at, r);
        ROCKETS.merge(v.id(), 1, Integer::sum);
    }

    /**
     * At its close (Festivals.closed): the contest judged, its winner's purse paid out of the treasury and the day written
     * into the chronicle; every folk there remembers it; the friends among the players who came given a portion of the
     * town's dish.
     */
    static void closed(ServerLevel level, Assemblies.Assembly a) {
        UUID id = a.village;
        Feast f = of(id);
        long day = level.getDayTime() / 24000L, due = dueOf(a.subject);
        TownWays.note(id, "feast.kept", Long.toString(due));
        ROCKETS.remove(id);
        if (f == null) return;
        int n = 0;
        for (UUID u : a.seated.keySet()) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity folk)) continue;
            folk.persona().feasted(day);
            folk.persona().remember(day, "I was at " + f.words, 3);
            WAS_THERE.put(u, day);
            n++;
        }
        String result = judge(level, id, f, day);
        Villages.tell(id, day, Villages.name(id) + " kept " + f.words + (n > 1 ? ", " + n + " of us on the square" : "") + ": " + result);
        Villages.Village v = Villages.get(id);
        Cuisine.Dish d = Cuisine.dishOf(id);
        if (v == null || d == null) return;
        for (ServerPlayer p : level.players()) {
            if (!p.blockPosition().closerThan(a.focus, 14)) continue;
            if (!Standing.of(id, p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.FRIEND)) continue;
            Item it = d.item();
            ItemStack s = Crafts.takeOne(level, v, x -> x.is(it));
            if (s.isEmpty()) break;
            if (!p.getInventory().add(s)) p.drop(s, false);
            p.displayClientMessage(Component.literal("A portion of " + Villages.name(id) + "'s " + d.words + ", for coming to " + f.words + "."), true);
        }
    }

    /** Tests: the festival's close held now (the contest judged, the chronicle written). Its result, in words. */
    public static String closeForTests(ServerLevel level, Villages.Village v) {
        Assemblies.Assembly a = Assemblies.underWay(v.id());
        if (a == null || !ours(a.subject)) a = gathering(v, level.getDayTime() / 24000L, level.getDayTime() / 24000L);
        closed(level, a);
        return lastResult(v.id());
    }

    /** What the deed counted for each contest is, by folk (and its baseline, kept from the last festival). */
    private static int measure(VillageFolkEntity f, Feast feast) {
        return switch (feast) {
            case HERRING_FAIR -> f.deedCount(AssistantEntity.Deed.FISH_CAUGHT);
            case LANTERN_NIGHT -> f.comforts();
            case IRON_FAIR -> f.stationTask() == AssistantEntity.StationTask.SMITH ? f.deedCount(AssistantEntity.Deed.THINGS_MADE) : 0;
            case BLOOM_DAY -> f.persona().flowersPlanted();
            case STONE_FEAST -> f.deedCount(AssistantEntity.Deed.BLOCKS_MINED);
            case NIGHT_OF_STARS -> 0;
            case HARVEST_HOME -> f.deedCount(AssistantEntity.Deed.CROPS_PLANTED);
        };
    }

    /** Whether the contest counts what was done since the last festival (a catch, a year's sowing) or what stands now. */
    private static boolean sinceLast(Feast feast) {
        return feast != Feast.LANTERN_NIGHT;
    }

    /**
     * The contest judged: the most since the last festival (the catch, the stone, the sowing, the smith's pieces, the
     * flowers planted), the best-kept house now, or, on the Night of Stars, the name the town likes best for its star
     * (its stargazers' and its curious, by how well the town likes each). The winner's purse paid out of the treasury.
     */
    static String judge(ServerLevel level, UUID village, Feast feast, long day) {
        Map<String, Integer> base = new HashMap<>();
        for (String[] r : Culture.rows(village, TownWays.PREFIX + "feast.base")) if (r.length >= 2) base.put(r[0], (int) Culture.num(r[1], 0));
        VillageFolkEntity best = null;
        int bestScore = 0;
        List<String[]> nextBase = new ArrayList<>();
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && !f.isShowcase() && f.persona().rolled()) folk.add(f);
        for (VillageFolkEntity f : folk) {
            if (feast == Feast.NIGHT_OF_STARS) {
                if (f.isBaby() || f.persona().hobby() != Persona.Hobby.STARGAZING && !f.life().has(Social.Trait.CURIOUS)) continue;
                int liked = 0;
                for (VillageFolkEntity o : folk) if (o != f) liked += Math.max(0, o.life().affinity(f.getUUID()));
                int score = 1 + liked;
                if (score > bestScore) { bestScore = score; best = f; }
                continue;
            }
            int m = measure(f, feast);
            nextBase.add(new String[]{ f.getUUID().toString(), Integer.toString(m) });
            int score = sinceLast(feast) ? m - base.getOrDefault(f.getUUID().toString(), 0) : m;
            if (score > bestScore) { bestScore = score; best = f; }
        }
        if (feast != Feast.NIGHT_OF_STARS) Culture.rows(village, TownWays.PREFIX + "feast.base", nextBase);
        String result;
        if (best == null) {
            result = "nobody came forward for " + feast.contest + " this year";
        } else {
            int paid = Ledger.takeCoins(village, PRIZE);
            if (paid > 0) best.earn(paid);
            best.persona().remember(day, "I won " + feast.contest + " at " + feast.words, 8);
            String who = best.displayNameCap();
            result = switch (feast) {
                case HERRING_FAIR -> who + " won the best catch, " + bestScore + " fish since the last fair";
                case LANTERN_NIGHT -> who + "'s was judged the best-kept house, with " + bestScore + " things in it";
                case IRON_FAIR -> who + " was judged the finest smith, " + bestScore + " pieces off the anvil this year";
                case BLOOM_DAY -> who + " won the best garden, " + bestScore + " flowers planted";
                case STONE_FEAST -> who + " was the strongest miner, " + bestScore + " blocks of stone since the last feast";
                case NIGHT_OF_STARS -> "a star was named " + who + "'s Lantern, the town's choice";
                case HARVEST_HOME -> who + " won the ploughing match, " + bestScore + " sown since last year";
            } + (paid > 0 ? ", and a purse of " + paid + " coins" : "");
            FolkTalk.speak(best, FolkTalk.pick(best.getRandom(), "Me? Really? Thank you all!", "I'll treasure this.", "Next year I'll do better still!"));
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, best.getX(), best.getY() + 2.0, best.getZ(), 10, 0.4, 0.4, 0.4, 0.0);
        }
        TownWays.note(village, "feast.last", result);
        return result;
    }

    /** The last festival's result, in words. */
    public static String lastResult(UUID village) {
        String s = TownWays.note(village, "feast.last");
        return s == null ? "" : s;
    }

    /** Was this folk at its town's own festival today or yesterday: five to its mood. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Long was = WAS_THERE.get(f.getUUID());
        if (was == null || day - was > 1) return m;
        why.add(new Object[]{ "ownfeast", 5 });
        return m + 5;
    }

    // ------------------------------------------------------------------ words

    static List<String> lines(UUID village) {
        List<String> out = new ArrayList<>();
        Feast f = of(village);
        if (f == null) {
            out.add("No festival of its own yet: chosen once it is known for something.");
            return out;
        }
        out.add("Its own festival: " + f.words + ", on the " + ordinal(f.dayOfYear + 1) + " day of its year; the contest for " + f.contest + ".");
        String what = switch (f) {
            case HERRING_FAIR -> "stalls of barrels and lanterns, a flute, the town's dish";
            case LANTERN_NIGHT -> "lanterns all round the square, bells and fireworks";
            case IRON_FAIR -> "forge fires, the ring of the anvils, fireworks";
            case BLOOM_DAY -> "flowers round the square, petals on the wind, chimes";
            case STONE_FEAST -> "stone posts with lanterns on them, the bass drum";
            case NIGHT_OF_STARS -> "candles, the harp, every face turned to the sky";
            case HARVEST_HOME -> "hay and lit pumpkins, the banjo and dancing";
        };
        out.add("It has " + what + "; friends of the town are invited, and given a portion of its dish.");
        String last = lastResult(village);
        if (!last.isEmpty()) out.add("Last time: " + last + ".");
        return out;
    }

    /** "What do you celebrate here?" */
    static String talk(VillageFolkEntity f) {
        Feast feast = of(f.ownerId());
        if (feast == null) return "The year's festivals, like everybody. We've not one of our own yet.";
        long day = f.level().getDayTime() / 24000L;
        long next = next(f.ownerId(), day);
        String last = lastResult(f.ownerId());
        return "Our own is " + feast.words + "! " + (next == day ? "It's tonight — come to the square." : "It's in " + (next - day) + " days.")
            + (last.isEmpty() ? "" : " Last time " + last + ".");
    }
}
