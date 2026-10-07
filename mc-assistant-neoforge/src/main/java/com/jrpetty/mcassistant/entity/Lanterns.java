package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.PaperLanternBlock;
import com.jrpetty.mcassistant.item.LeisureItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [leisure] The festival's paper lanterns (block/PaperLanternBlock), hooked into the year's festivals (Festivals.tick).
 *
 * <p>On the evening of a festival the town gathers for (the May dance, the midsummer bonfire, the fair, the harvest
 * festival), before dusk a hand strings lanterns across the square out of the stores: a post at each end of a line (the
 * stores' fence posts, or logs), a line of string between their tops, and the paper lanterns hung from it every other
 * block, in all the colours the stores have; a second line across the first when there are lanterns enough. The square
 * glows with them all evening, and the folk who are there are the happier for a lit festival ("like stars, they were").
 * The morning after, a hand takes them down: lanterns, string and posts back into the stores. What is up is written in
 * the town's books, so a restart never leaves a lantern out of the stores.
 *
 * <p><b>Made</b> by the tailor or the shop's workshop: a sheet of paper and a torch and a dye make two, in the dye's
 * colour. The town keeps eight for its festivals, in eight colours.
 */
final class Lanterns {

    private Lanterns() {}

    /** A line's half-length: its posts this far either side of its middle. */
    static final int HALF = 5;
    /** The posts' height; the string on their tops; the lanterns hung a block under it. */
    static final int POST = 4;
    /** The festival's colours, eight lanterns kept for it. */
    static final DyeColor[] COLOURS = { DyeColor.RED, DyeColor.YELLOW, DyeColor.ORANGE, DyeColor.LIGHT_BLUE, DyeColor.LIME, DyeColor.PINK,
        DyeColor.MAGENTA, DyeColor.WHITE };
    /** Lanterns at least for a line. */
    static final int LEAST = 3;

    /** Folk who were by the lit lanterns on a festival evening (the day). */
    private static final Map<UUID, Long> SAW = new ConcurrentHashMap<>();

    static void resetForTests() {
        SAW.clear();
    }

    /** A thing put up for the evening: where, what block, and what goes back into the stores. */
    record Up(BlockPos pos, Block block, Item back, int n) {}

    static List<Up> up(UUID village, long[] day) {
        List<Up> out = new ArrayList<>();
        String s = Ledger.note(village, "lanterns.up");
        day[0] = -1;
        if (s == null || s.isEmpty()) return out;
        String[] head = s.split("\\|", 2);
        try {
            day[0] = Long.parseLong(head[0]);
        } catch (NumberFormatException e) {
            return out;
        }
        if (head.length < 2 || head[1].isEmpty()) return out;
        for (String e : head[1].split(";")) {
            String[] p = e.split(",");
            if (p.length < 4) continue;
            try {
                Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(p[1]));
                Item it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(p[2]));
                out.add(new Up(BlockPos.of(Long.parseLong(p[0])), b, it, Integer.parseInt(p[3])));
            } catch (RuntimeException ignored) {
                // a line from another build: left off
            }
        }
        return out;
    }

    static void save(UUID village, long day, List<Up> ups) {
        if (ups.isEmpty()) {
            Ledger.forget(village, "lanterns.up");
            return;
        }
        StringBuilder sb = new StringBuilder().append(day).append('|');
        for (int i = 0; i < ups.size(); i++) {
            Up u = ups.get(i);
            if (i > 0) sb.append(';');
            sb.append(u.pos().asLong()).append(',').append(BuiltInRegistries.BLOCK.getKey(u.block())).append(',')
                .append(BuiltInRegistries.ITEM.getKey(u.back())).append(',').append(u.n());
        }
        Ledger.note(village, "lanterns.up", sb.toString());
    }

    /** The festival the town gathers for tonight, or null. */
    @Nullable
    static Festivals.Feast tonight(UUID village, long day) {
        for (Festivals.Feast f : new Festivals.Feast[]{ Festivals.Feast.MAYPOLE, Festivals.Feast.BONFIRE, Festivals.Feast.FAIR, Festivals.Feast.HARVEST }) {
            if (Festivals.due(village, day, f)) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------ the town's round (Festivals.tick, every second)

    /** Up before dusk on a festival's evening; down the morning after; who is by them while they are lit. */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        long[] when = { -1 };
        List<Up> ups = up(id, when);
        if (!ups.isEmpty()) {
            if (when[0] < day && t >= 1000L && t < 12000L) {
                takeDown(level, v, ups, when[0], day);
                return;
            }
            if (when[0] == day && t >= 12000L && t < 15000L && level.getGameTime() % 40 < 20) enjoy(level, v, ups, day);
            return;
        }
        Festivals.Feast f = tonight(id, day);
        if (f == null || t < 10500L || t >= 12600L || Raids.underAlarm(id)) return;
        if (level.isRaining() && Festivals.dayThisYear(id, day, f) == day) return;          // put off with the festival
        stringUp(level, v, day, f, false);
    }

    /**
     * Lanterns strung across the square for tonight's festival, out of the stores: a line (a post each end, the string
     * between their tops, a lantern hung every other block under it), and a second line across it if there are lanterns
     * enough. Null when they are up; else why not, in a few words.
     */
    @Nullable
    static String stringUp(ServerLevel level, Villages.Village v, long day, @Nullable Festivals.Feast f, boolean now) {
        UUID id = v.id();
        List<ItemStack> lanterns = new ArrayList<>();
        int have = Market.stock(level, id, LeisureItems::isLantern);
        if (have < LEAST) return "lanterns: the stores have " + have + " (" + LEAST + " at least for a line)";
        int string = Market.stock(level, id, s -> s.is(Items.STRING));
        if (string < 2 * HALF - 1) return "string for the line: " + string + " in the stores";
        java.util.function.Predicate<ItemStack> fence = s -> s.is(ItemTags.WOODEN_FENCES) && s.getItem() instanceof BlockItem;
        java.util.function.Predicate<ItemStack> log = s -> s.is(ItemTags.LOGS) && s.getItem() instanceof BlockItem;
        if (Market.stock(level, id, fence) + Market.stock(level, id, log) < 2 * POST) return "posts for the line: fence posts or logs";
        BlockPos spot = Festivals.clearSpot(level, v, HALF, 0, POST + 2);
        if (spot == null) return "no clear ground across the square for a line";
        if (!now && !TownJobs.atWork(level, v, "lanterns", spot, "stringing lanterns across the square")) return "waiting for a hand";
        List<Up> ups = new ArrayList<>();
        int lines = line(level, v, spot, Direction.EAST, ups);
        if (lines > 0 && Market.stock(level, id, LeisureItems::isLantern) >= LEAST && Market.stock(level, id, s -> s.is(Items.STRING)) >= 2 * HALF - 1) {
            BlockPos second = Festivals.clearSpot(level, v, 0, HALF, POST + 2);
            if (second != null) line(level, v, second, Direction.SOUTH, ups);
        }
        if (ups.isEmpty()) return "the line would not go up";
        // Written down lanterns first: they come down before the string they hang from.
        List<Up> order = new ArrayList<>();
        for (Up u : ups) if (u.block() instanceof PaperLanternBlock) order.add(u);
        for (Up u : ups) if (!(u.block() instanceof PaperLanternBlock)) order.add(u);
        save(id, day, order);
        int n = 0;
        for (Up u : ups) if (u.block() instanceof PaperLanternBlock) n++;
        String what = f == null ? "the evening" : f.words;
        Villages.tell(id, day, "the square was hung with " + n + " paper lanterns for " + what);
        Pastimes.news(id, day, "lanterns:" + n + " paper lanterns were strung across the square for " + what);
        VillageFolkEntity by = Festivals.nearest(v, spot, 24);
        if (by != null) FolkTalk.speak(by, FolkTalk.pick(level.getRandom(), "There — the lanterns are up! Wait till it's dark.",
            "Lanterns all across the square. It'll be lovely tonight."));
        Pastimes.LOG.info("[MCA-LEISURE] {} strung {} paper lanterns across the square for {}", Villages.name(id), n, what);
        return null;
    }

    /** One line, along this way from its middle: the posts, the string, the lanterns. How many lanterns it hung. */
    private static int line(ServerLevel level, Villages.Village v, BlockPos mid, Direction along, List<Up> ups) {
        java.util.function.Predicate<ItemStack> fence = s -> s.is(ItemTags.WOODEN_FENCES) && s.getItem() instanceof BlockItem;
        java.util.function.Predicate<ItemStack> log = s -> s.is(ItemTags.LOGS) && s.getItem() instanceof BlockItem;
        List<ItemStack> posts = new ArrayList<>();
        for (int i = 0; i < 2 * POST; i++) {
            ItemStack p = Crafts.takeOne(level, v, fence);
            if (p.isEmpty()) p = Crafts.takeOne(level, v, log);
            if (p.isEmpty()) break;
            posts.add(p);
        }
        if (posts.size() < 2 * POST || !Crafts.take(level, v, s -> s.is(Items.STRING), 2 * HALF - 1)) {
            for (ItemStack p : posts) Crafts.store(level, v, p);
            return 0;
        }
        int k = 0;
        for (int end : new int[]{ -HALF, HALF }) {
            BlockPos foot = mid.relative(along, end);
            for (int y = 0; y < POST; y++) {
                ItemStack p = posts.get(k++);
                BlockPos at = foot.above(y);
                level.setBlock(at, ((BlockItem) p.getItem()).getBlock().defaultBlockState(), Block.UPDATE_ALL);
                ups.add(new Up(at.immutable(), ((BlockItem) p.getItem()).getBlock(), p.getItem(), 1));
            }
        }
        for (int i = -HALF + 1; i <= HALF - 1; i++) {
            BlockPos at = mid.relative(along, i).above(POST - 1);
            level.setBlock(at, Blocks.TRIPWIRE.defaultBlockState(), Block.UPDATE_ALL);
            ups.add(new Up(at.immutable(), Blocks.TRIPWIRE, Items.STRING, 1));
        }
        int hung = 0;
        for (int i = -HALF + 2; i <= HALF - 2; i += 2) {
            ItemStack l = Crafts.takeOne(level, v, LeisureItems::isLantern);
            if (l.isEmpty()) break;
            BlockPos at = mid.relative(along, i).above(POST - 2);
            Block b = ((BlockItem) l.getItem()).getBlock();
            BlockState s = b.defaultBlockState().setValue(PaperLanternBlock.HANGING, true);
            if (!level.getBlockState(at).isAir() || !s.canSurvive(level, at)) {
                Crafts.store(level, v, l);
                continue;
            }
            level.setBlock(at, s, Block.UPDATE_ALL);
            ups.add(new Up(at.immutable(), b, l.getItem(), 1));
            hung++;
        }
        level.playSound(null, mid, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.9F, 1.1F);
        return hung;
    }

    /** The morning after: a hand takes the lanterns down, then the string and the posts, all back into the stores. */
    static void takeDown(ServerLevel level, Villages.Village v, List<Up> ups, long upDay, long day) {
        for (Up u : ups) if (!level.isLoaded(u.pos())) return;                 // all of it in sight, or none of it yet
        if (!TownJobs.atWork(level, v, "lanterns", ups.get(0).pos(), "taking down the festival lanterns") && day <= upDay + 3) return;
        int back = 0;
        // In the order they were written: the lanterns first, so none is left hanging off a line that has gone (and dropped).
        for (Up u : ups) {
            if (!level.isLoaded(u.pos())) continue;
            if (level.getBlockState(u.pos()).is(u.block())) {
                level.setBlock(u.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                Crafts.store(level, v, new ItemStack(u.back(), u.n()));
                back++;
            }
        }
        save(v.id(), upDay, List.of());
        Pastimes.LOG.info("[MCA-LEISURE] {} took its festival lanterns down: {} things back into the stores", Villages.name(v.id()), back);
    }

    /** Folk by the lit lanterns of a festival evening: the evening the lovelier for it, and a word now and then. */
    static void enjoy(ServerLevel level, Villages.Village v, List<Up> ups, long day) {
        BlockPos mid = null;
        for (Up u : ups) if (u.block() instanceof PaperLanternBlock) { mid = u.pos(); break; }
        if (mid == null) return;
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, new net.minecraft.world.phys.AABB(mid).inflate(18.0, 8.0, 18.0),
                x -> x.isAlive() && !x.isSleeping() && v.id().equals(x.ownerId()))) {
            Long was = SAW.put(f.getUUID(), day);
            if (was == null || was != day) {
                if (f.getRandom().nextInt(4) == 0) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Aren't the lanterns lovely?",
                    "Look at all the colours!", "Like stars come down to the square."));
                if (f.persona().rolled()) f.persona().remember(day, "the square was all lit up with lanterns", 1);
            }
        }
        if (level.getRandom().nextInt(3) == 0) {
            level.sendParticles(ParticleTypes.END_ROD, mid.getX() + 0.5, mid.getY() + 0.2, mid.getZ() + 0.5, 1, 0.6, 0.2, 0.6, 0.002);
        }
    }

    // ------------------------------------------------------------------ made, spirits, the books

    /** Eight lanterns kept for the festivals, one of each of its colours, once the town is settled. */
    static Map<Item, Integer> wanted(ServerLevel level, Villages.Village v) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        if (Villages.headcount(v.id()) < TownJobs.SETTLED) return out;
        long[] when = { -1 };
        if (!up(v.id(), when).isEmpty()) return out;                        // they are out on the square
        for (DyeColor c : COLOURS) out.put(LeisureItems.lantern(c), 1);
        return out;
    }

    static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Long d = SAW.get(f.getUUID());
        if (d == null || day - d > 1) return m;
        why.add(new Object[]{ "lanterns", 4 });
        return m + 4;
    }

    static List<String> gazette(UUID village, long day) {
        List<String> l = Pastimes.newsOf(village, day - 1, "lanterns:");
        if (l.isEmpty()) return List.of();
        String s = l.get(0);
        return List.of(Character.toUpperCase(s.charAt(0)) + s.substring(1) + ".");
    }

    static String status(ServerLevel level, Villages.Village v) {
        long[] when = { -1 };
        List<Up> ups = up(v.id(), when);
        int lit = 0;
        for (Up u : ups) if (u.block() instanceof PaperLanternBlock) lit++;
        long day = level.getDayTime() / 24000L;
        Festivals.Feast f = tonight(v.id(), day);
        return Market.stock(level, v.id(), LeisureItems::isLantern) + " in the stores" + (lit > 0 ? "; " + lit + " up on the square since day " + when[0]
            : "") + (f != null ? "; tonight is " + f.words : "");
    }

    // ------------------------------------------------------------------ tests

    /** Tests: tonight's lanterns strung up now (a hand there at once). Null when up, else why not. */
    @Nullable
    public static String stringUpForTests(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        return stringUp(level, v, day, tonight(v.id(), day), true);
    }

    /** Tests: where the lanterns hang now. */
    public static List<BlockPos> hungForTests(UUID village) {
        long[] when = { -1 };
        List<BlockPos> out = new ArrayList<>();
        for (Up u : up(village, when)) if (u.block() instanceof PaperLanternBlock) out.add(u.pos());
        return out;
    }

    /** Tests: the evening's look at who is by the lanterns, now. */
    public static void enjoyForTests(ServerLevel level, Villages.Village v) {
        long[] when = { -1 };
        List<Up> ups = up(v.id(), when);
        if (!ups.isEmpty()) enjoy(level, v, ups, level.getDayTime() / 24000L);
    }

    /** Tests: the lanterns taken down now (a hand there at once). */
    public static void takeDownForTests(ServerLevel level, Villages.Village v) {
        long[] when = { -1 };
        List<Up> ups = up(v.id(), when);
        if (!ups.isEmpty()) takeDown(level, v, ups, when[0], when[0] + 1);
    }
}
