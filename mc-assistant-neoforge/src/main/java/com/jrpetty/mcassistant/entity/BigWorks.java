package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.RibbonBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [civic] The town's great works, built together: a stone bridge, an aqueduct, the town wall, a harbour, a great
 * road or a canal (drawn to the land by WorksPlans).
 *
 * <p><b>Put to the town.</b> A town of eight grown folk or more, in the Stone Age at least (an aqueduct, a harbour
 * and a great road the Iron Age), with nothing else of the kind under way, looks at what it could do: a bridge where
 * a river cuts it off from its fields or a neighbour, a wall after a raid or in a war, water for the square, a
 * harbour for a fishing town, a road to a friendly neighbour. Its leader (or, with none, the council) puts the most
 * wanted of them to the whole town when the stores hold most of what it costs (Referendums): the work, where it
 * goes, its cost out of the stores in stone and lanterns, the labour, and what it brings.
 *
 * <p><b>Built together.</b> Carried, it starts the next morning: the works day, when every grown folk who is not on
 * the watch or leading a building turns out and lends a hand from the morning assembly to the noon bell. After that
 * it goes on in everybody's own time: on a break, of an evening, on the day of rest, and with whoever has nothing to
 * do at its trade. A hand at the works walks to where the work stands and sets a piece every few seconds, paid for
 * out of the stores as it is set (the more hands, the quicker it goes); short of stone, the works wait for the
 * masons and the miners. Nothing anybody built is taken down for it.
 *
 * <p><b>Opened.</b> The last stone laid, a ribbon is strung across its end (the stores' ribbon, or one made there and
 * then of string and red dye) and that evening the town gathers there: the leader thanks the hands by number, says
 * what it cost and what it brings, and cuts the ribbon. The chronicle has it; everybody who lent a hand remembers it,
 * and is the prouder for a few days.
 */
public final class BigWorks {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private BigWorks() {}

    /** The great works there are, as folk name them, and what each is chiefly for. */
    public enum Work {
        BRIDGE("a stone bridge", "the bridge", Villages.Age.STONE),
        AQUEDUCT("an aqueduct", "the aqueduct", Villages.Age.IRON),
        WALL("the town wall", "the wall", Villages.Age.STONE),
        HARBOUR("a harbour", "the harbour", Villages.Age.IRON),
        ROAD("a great road", "the road", Villages.Age.IRON),
        CANAL("a canal", "the canal", Villages.Age.STONE);

        public final String a, the;
        final Villages.Age age;

        Work(String a, String the, Villages.Age age) {
            this.a = a;
            this.the = the;
            this.age = age;
        }

        @Nullable
        static Work named(String s) {
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** A work as it would be put to the town: what, drawn where, in which stone, at what cost, and in words. */
    public record Proposal(Work kind, WorksPlans.Plan plan, WorksPlans.Family family, int[] cost, String title, String costWords,
                           String labour, String brings) {}

    /** A grown town's least: below it, every hand is wanted at its trade. */
    static final int GROWN_AT_LEAST = 8;
    /** How long a hand takes over a piece (ticks): a stone set every five seconds. */
    static final int PIECE_TICKS = 100;
    /** The works day: from after the morning assembly to the noon bell. */
    static final long MUSTER_FROM = 1400L, MUSTER_TO = 6000L;
    /** How far from the piece it is setting a hand may stand (with a ladder, a rope and a boat: a bridge's far end). */
    static final double REACH = 48.0;

    /** Who is lending a hand just now, and since when (between hold's looks). */
    private record Held(String doing, int tick) {}

    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_SET = new ConcurrentHashMap<>();
    /** When each hand set out for the works this time (game time). */
    private static final Map<UUID, Long> SET_OUT = new ConcurrentHashMap<>();
    /** The current work's pieces, read from the record once (they are long). */
    private static final Map<UUID, List<WorksPlans.Piece>> PIECES = new ConcurrentHashMap<>();
    /** Days each hand last lent one, by folk (its spirits: proud of what it built). */
    private static final Map<UUID, Long> PROUD = new ConcurrentHashMap<>();

    static void resetForTests() {
        HELD.clear();
        LAST_SET.clear();
        SET_OUT.clear();
        PIECES.clear();
        PROUD.clear();
    }

    // ------------------------------------------------------------------ what the town wants

    /** The works this town could put to the vote now, each drawn to the land, the most wanted first. */
    static List<Proposal> proposals(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<Proposal> out = new ArrayList<>();
        Map<Work, Integer> want = new LinkedHashMap<>();
        for (Work w : Work.values()) {
            int s = wanted(level, v, w);
            if (s > 0) want.put(w, s);
        }
        List<Map.Entry<Work, Integer>> ranked = new ArrayList<>(want.entrySet());
        ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<Work, Integer> e : ranked) {
            WorksPlans.Plan p = plan(level, v, e.getKey());
            if (p == null || p.pieces().size() < 12) continue;
            Proposal pr = propose(level, v, p);
            if (pr != null) out.add(pr);
        }
        return out;
    }

    /** How much the town wants a work just now (0: not at all), by its age, its land, its troubles and its trades. */
    static int wanted(ServerLevel level, Villages.Village v, Work w) {
        UUID id = v.id();
        if (Villages.ageOf(id).ordinal() < w.age.ordinal()) return 0;
        long day = level.getDayTime() / 24000L;
        return switch (w) {
            case BRIDGE -> 30;
            case WALL -> {
                int s = 0;
                long raided = Raids.raidedOn(id);
                if (raided >= 0 && day - raided <= 21) s += 40;
                if (Wars.footing(id) != Wars.Footing.PEACE) s += 45;
                if (Villages.headcount(id) >= 30) s += 10;
                yield builtSides(id) == 15 ? 0 : s;
            }
            case AQUEDUCT -> done(id, Work.AQUEDUCT) ? 0 : 20 + (Villages.hasBuilt(id, "fountain") ? 10 : 0);
            case CANAL -> done(id, Work.CANAL) || Villages.fieldsSide(id) < 0 ? 0
                : 18 + (Leader.plan(id) == Leader.Plan.SHORT || Leader.plan(id) == Leader.Plan.FAMINE ? 15 : 0);
            case HARBOUR -> {
                int fishers = 0;
                for (AssistantEntity a : Villages.folkOf(id)) if (a.stationTask() == AssistantEntity.StationTask.FISH) fishers++;
                yield done(id, Work.HARBOUR) || fishers == 0 ? 0 : 15 + 5 * Math.min(4, fishers);
            }
            case ROAD -> done(id, Work.ROAD) ? 0 : 16;
        };
    }

    /** The work drawn to the town's land, or null where the land has no place for it. */
    @Nullable
    static WorksPlans.Plan plan(ServerLevel level, Villages.Village v, Work w) {
        UUID id = v.id();
        BlockPos c = v.centre();
        return switch (w) {
            case BRIDGE -> WorksPlans.bridge(level, id, c);
            case AQUEDUCT -> WorksPlans.aqueduct(level, id, c);
            case CANAL -> WorksPlans.canal(level, id, c);
            case HARBOUR -> WorksPlans.harbour(level, id, c);
            case ROAD -> WorksPlans.road(level, id, c);
            case WALL -> {
                int built = builtSides(id);
                int side = wallSide(v);
                if (side < 0) yield null;
                yield WorksPlans.wall(level, id, c, side, built);
            }
        };
    }

    /** Which side of the town the next stretch of wall goes on: toward the enemy, else away from the fields; -1 if all stand. */
    static int wallSide(Villages.Village v) {
        UUID id = v.id();
        int built = builtSides(id);
        List<Integer> order = new ArrayList<>();
        for (UUID e : Wars.enemies(id)) {
            Villages.Village ev = Villages.get(e);
            if (ev != null) order.add(sideIndex(WorksPlans.toward(v.centre(), ev.centre())));
        }
        int fields = Villages.fieldsSide(id);
        if (fields >= 0) order.add(Math.floorMod(fields + 2, 4));
        for (int s = 0; s < 4; s++) order.add(s);
        for (int s : order) if ((built & (1 << s)) == 0 && s != fields) return s;
        return fields >= 0 && (built & (1 << fields)) == 0 ? fields : -1;
    }

    static int sideIndex(Direction d) {
        return switch (d) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    /** The sides of the town wall that stand, as bits (north 1, east 2, south 4, west 8). */
    static int builtSides(UUID village) {
        return CivicRecord.town(village).getInt("wallSides");
    }

    /** Has the town built one of these already (all but the wall and bridges are one to a town)? */
    static boolean done(UUID village, Work w) {
        for (Tag t : CivicRecord.list(CivicRecord.town(village), "worksDone")) {
            if (t instanceof CompoundTag c && w.name().equals(c.getString("kind"))) return true;
        }
        return false;
    }

    /** A plan priced: the stone the stores have most of (dressed stone first), and the cost and labour in words. */
    @Nullable
    static Proposal propose(ServerLevel level, Villages.Village v, WorksPlans.Plan p) {
        int[] cost = WorksPlans.cost(p.pieces());
        WorksPlans.Family family = family(level, v, cost[0]);
        if (family == null) return null;
        List<String> parts = new ArrayList<>();
        parts.add(cost[0] + " " + family.words + (family == WorksPlans.Family.BRICKS ? "s" : ""));
        if (cost[1] > 0) parts.add(cost[1] + (cost[1] == 1 ? " lantern" : " lanterns"));
        if (cost[2] > 0) parts.add(cost[2] + (cost[2] == 1 ? " fence post" : " fence posts"));
        if (cost[3] > 0) parts.add(cost[3] + " planks");
        if (cost[4] > 0) parts.add("a bucket to fill it");
        String costWords = String.join(", ", parts);
        int pieces = p.pieces().size();
        int hours = Math.max(1, Math.round(pieces * (float) PIECE_TICKS / 1000F));
        String labour = "some " + hours + " hand-hours: " + (hours <= 12 ? "a morning's work for a dozen hands"
            : hours <= 30 ? "a works day for the whole town, and a few evenings" : "a works day and a week of evenings");
        String title = p.kind().a + " " + p.where();
        return new Proposal(p.kind(), p, family, cost, title, costWords, labour, p.brings());
    }

    /**
     * The stone to build in: the family the stores could pay for in full, dressed stone before rough; failing that
     * the one they hold most of, if it is three in five of what is wanted; else none (nothing to put to the vote yet).
     */
    @Nullable
    static WorksPlans.Family family(ServerLevel level, Villages.Village v, int units) {
        WorksPlans.Family most = null;
        int mostHave = 0;
        for (WorksPlans.Family f : WorksPlans.Family.values()) {
            int have = Market.stock(level, v.id(), f.payment());
            if (have >= units) return f;
            if (have > mostHave) { mostHave = have; most = f; }
        }
        return most != null && mostHave * 5 >= units * 3 ? most : null;
    }

    // ------------------------------------------------------------------ the work under way

    /** The work under way in a town (being built, or built and waiting to be opened), or null. */
    @Nullable
    static CompoundTag current(UUID village) {
        CompoundTag t = CivicRecord.town(village);
        return t.contains("work", Tag.TAG_COMPOUND) ? t.getCompound("work") : null;
    }

    /** Is a great work being built or waiting to be opened in this town (no other is put to the vote meanwhile)? */
    public static boolean underWay(UUID village) {
        return current(village) != null;
    }

    static List<WorksPlans.Piece> pieces(UUID village, CompoundTag work) {
        return PIECES.computeIfAbsent(village, k -> WorksPlans.load(work.getCompound("plan")));
    }

    /**
     * What is put to the vote, in full, so that what is built is what was voted for: the work, its words, its stone,
     * and the plan drawn to the land on the day it was called (its pieces, ribbon, gathering place and stands).
     */
    static CompoundTag spec(Villages.Village v, Proposal p) {
        CompoundTag w = new CompoundTag();
        w.putString("kind", p.kind().name());
        w.putString("title", p.title());
        w.putString("brings", p.brings());
        w.putString("costWords", p.costWords());
        w.putString("labour", p.labour());
        w.putInt("units", p.cost()[0]);
        w.putString("family", p.family().name());
        w.putString("where", p.plan().where());
        w.put("plan", WorksPlans.save(p.plan().pieces()));
        long[] ribbon = new long[p.plan().ribbon().size()];
        for (int i = 0; i < ribbon.length; i++) ribbon[i] = p.plan().ribbon().get(i).asLong();
        w.put("ribbon", new LongArrayTag(ribbon));
        w.putString("axis", p.plan().ribbonAxis().getName());
        w.putLong("focus", p.plan().focus().asLong());
        w.putString("audience", p.plan().audience().getName());
        long[] stands = new long[p.plan().stands().size()];
        for (int i = 0; i < stands.length; i++) stands[i] = p.plan().stands().get(i).asLong();
        w.put("stands", new LongArrayTag(stands));
        w.putLong("site", p.plan().site().asLong());
        if (p.kind() == Work.WALL) w.putInt("wallSide", wallSide(v));
        return w;
    }

    /** The town voted for it: written down, the works day called for tomorrow, the ribbon ordered from the workshop. */
    static void start(ServerLevel level, Villages.Village v, CompoundTag spec, long day) {
        UUID id = v.id();
        CompoundTag w = spec.copy();
        w.putInt("next", 0);
        w.putInt("credit", 0);
        w.putInt("placed", 0);
        w.putInt("spared", 0);
        w.putString("stage", "building");
        w.putString("waiting", "");
        w.putLong("started", day);
        w.putLong("muster", day + 1);
        w.put("hands", new CompoundTag());
        CivicRecord.town(id).put("work", w);
        CivicRecord.changed();
        PIECES.remove(id);
        Villages.tell(id, day, "work on " + w.getString("title") + " starts tomorrow, the whole town lending a hand");
        Market.assemblyNews(id, "Today we start on " + w.getString("title") + ". Everybody who can, lend a hand till the noon bell!");
        LOG.info("[MCA-CIVIC] {}: {} to be built ({} pieces, {})", Villages.name(id), w.getString("title"),
            WorksPlans.load(w.getCompound("plan")).size(), w.getString("costWords"));
    }

    /** Every five seconds for each town (Referendums.tick): the last stone laid, the ribbon strung, the opening kept. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag w = current(id);
        if (w == null) return;
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        String stage = w.getString("stage");
        if (stage.equals("building")) {
            List<WorksPlans.Piece> pieces = pieces(id, w);
            if (w.getInt("next") >= pieces.size()) finished(level, v, w, day, t);
            else makeDo(level, v, w, day);
        } else if (stage.equals("opening") && day > w.getLong("openOn") + 1) {
            // Nobody gathered for it (rain, a raid, a busy evening): opened quietly in the morning all the same.
            opened(level, v, null);
        }
    }

    /**
     * Two days waiting for the stone it was begun in (a town whose masons cut no more bricks, say): the rest goes up in
     * whatever stone the stores hold most of, so a bridge begun in brick is finished in cobble rather than never.
     */
    static void makeDo(ServerLevel level, Villages.Village v, CompoundTag w, long day) {
        if (w.getString("waiting").isEmpty() || w.getString("waiting").equals("a bucket")) {
            w.remove("waitingSince");
            return;
        }
        if (!w.contains("waitingSince")) {
            w.putLong("waitingSince", day);
            CivicRecord.changed();
            return;
        }
        if (day - w.getLong("waitingSince") < 2) return;
        WorksPlans.Family was = WorksPlans.Family.named(w.getString("family"));
        WorksPlans.Family best = null;
        int most = 15;
        for (WorksPlans.Family f : WorksPlans.Family.values()) {
            int have = Market.stock(level, v.id(), f.payment());
            if (f != was && have > most) { most = have; best = f; }
        }
        if (best == null) return;
        w.putString("family", best.name());
        w.putString("waiting", "");
        w.remove("waitingSince");
        w.putInt("credit", 0);
        CivicRecord.changed();
        Villages.tell(v.id(), day, "short of " + (was == null ? "stone" : was.words) + ", the town goes on with " + w.getString("title")
            + " in " + best.words);
    }

    /** The last stone is laid: the ribbon strung across its end, and the town to gather there this evening. */
    static void finished(ServerLevel level, Villages.Village v, CompoundTag w, long day, long t) {
        UUID id = v.id();
        w.putString("stage", "opening");
        w.putLong("finishedOn", day);
        w.putLong("openOn", t < 11500L ? day : day + 1);
        boolean strung = stringRibbon(level, v, w);
        w.putBoolean("strung", strung);
        CivicRecord.changed();
        Work kind = Work.named(w.getString("kind"));
        String the = kind == null ? "the work" : kind.the;
        Villages.tell(id, day, "the last stone of " + w.getString("title") + " was laid" + (strung ? ", and a ribbon strung across it" : ""));
        Market.assemblyNews(id, capital(the) + " is finished! We open it this evening — come one, come all.");
        LOG.info("[MCA-CIVIC] {}: {} finished ({} set, {} passed over); ribbon {}", Villages.name(id), w.getString("title"),
            w.getInt("placed"), w.getInt("spared"), strung ? "strung" : "none");
    }

    /**
     * The tailor's piece (Crafts.tailor), while a great work is under way and the stores have not ribbon enough for its
     * opening: three lengths of opening ribbon, of two of the stores' string and a red dye, as at the bench. Null if no
     * ribbon is wanted or the makings are not in the stores.
     */
    @Nullable
    static String tailorRibbon(ServerLevel level, Villages.Village v) {
        CompoundTag w = current(v.id());
        if (w == null) return null;
        int want = w.getLongArray("ribbon").length;
        Item ribbon = McAssistantMod.RIBBON_ITEM.get();
        if (want == 0 || Market.stock(level, v.id(), s -> s.is(ribbon)) >= want) return null;
        if (Market.stock(level, v.id(), s -> s.is(Items.STRING)) < 2 || Market.stock(level, v.id(), s -> s.is(Items.RED_DYE)) < 1) return null;
        if (!TownWork.take(level, v, s -> s.is(Items.STRING), 2)) return null;
        if (!TownWork.take(level, v, s -> s.is(Items.RED_DYE), 1)) {
            TownWork.give(level, v, new ItemStack(Items.STRING, 2));
            return null;
        }
        TownWork.give(level, v, new ItemStack(ribbon, 3));
        Work kind = Work.named(w.getString("kind"));
        return "three lengths of opening ribbon, for " + (kind == null ? "the town's great work" : kind.the);
    }

    /**
     * The ribbon across its end: the stores' ribbons, one a block; failing them, made there and then of the stores'
     * string and red dye (two string and a dye make three, as at the bench). True if it is strung.
     */
    static boolean stringRibbon(ServerLevel level, Villages.Village v, CompoundTag w) {
        long[] at = w.getLongArray("ribbon");
        if (at.length == 0) return false;
        Item ribbon = McAssistantMod.RIBBON_ITEM.get();
        int n = at.length;
        if (Market.stock(level, v.id(), s -> s.is(ribbon)) < n) {
            int batches = (n - Market.stock(level, v.id(), s -> s.is(ribbon)) + 2) / 3;
            for (int b = 0; b < batches; b++) {
                if (!TownWork.take(level, v, s -> s.is(Items.STRING), 2)) return false;
                if (!TownWork.take(level, v, s -> s.is(Items.RED_DYE), 1)) {
                    TownWork.give(level, v, new ItemStack(Items.STRING, 2));
                    return false;
                }
                TownWork.give(level, v, new ItemStack(ribbon, 3));
            }
        }
        if (!TownWork.take(level, v, s -> s.is(ribbon), n)) return false;
        Direction.Axis axis = "z".equals(w.getString("axis")) ? Direction.Axis.Z : Direction.Axis.X;
        BlockState st = McAssistantMod.RIBBON.get().defaultBlockState().setValue(RibbonBlock.AXIS, axis);
        int hung = 0;
        for (long l : at) {
            BlockPos p = BlockPos.of(l);
            if (!WorksPlans.room(level.getBlockState(p)) || !level.getFluidState(p).isEmpty()) continue;
            level.setBlock(p, st, 3);
            hung++;
        }
        if (hung < n) TownWork.give(level, v, new ItemStack(ribbon, n - hung));
        return hung > 0;
    }

    /**
     * [itemaudit] A player's shears at a ribbon (block/RibbonBlock): a player opening something of its own, as the leader
     * opens the town's. The ribbon is snipped and gone, and the folk near enough to see clap, the nearest calling out.
     * A town's own ribbon across a great work is its leader's to cut at the opening, and stays. Returns what the player
     * is told, and whether it was cut.
     */
    public static String cutByPlayer(ServerLevel level, BlockPos pos, net.minecraft.world.entity.player.Player p, boolean[] cut) {
        cut[0] = false;
        Villages.Village v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE * 2);
        CompoundTag w = v == null ? null : current(v.id());
        if (w != null) {
            for (long l : w.getLongArray("ribbon")) {
                if (l == pos.asLong()) return "That's " + Villages.name(v.id()) + "'s ribbon: its leader cuts it when the work is opened.";
            }
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, McAssistantMod.RIBBON.get().defaultBlockState()),
            pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, 10, 0.3, 0.1, 0.3, 0.05);
        level.playSound(null, pos, SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 1.0F, 1.1F);
        cut[0] = true;
        int clapping = 0;
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(12.0),
                x -> x.isAlive() && !x.isSleeping() && !x.isShowcase())) {
            f.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5);
            f.swing(InteractionHand.MAIN_HAND);
            if (clapping++ == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Hooray! It's open!", "Well done, " + p.getName().getString() + "!",
                    "A ribbon cut! What's the occasion?"));
            }
            if (clapping >= 6) break;
        }
        return clapping > 0 ? "Snip! The ribbon's cut, and the folk about give a cheer." : "Snip! The ribbon's cut.";
    }

    /** The ribbon cut and the work opened (from the gathering's line, or quietly): into the chronicle and the folk's memories. */
    static void opened(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity cutter) {
        UUID id = v.id();
        CompoundTag w = current(id);
        if (w == null) return;
        long day = level.getDayTime() / 24000L;
        // The ribbon cut: the pieces of it are the town's keepsakes now, and nothing goes back to the stores.
        for (long l : w.getLongArray("ribbon")) {
            BlockPos p = BlockPos.of(l);
            if (level.getBlockState(p).is(McAssistantMod.RIBBON.get())) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, McAssistantMod.RIBBON.get().defaultBlockState()),
                    p.getX() + 0.5, p.getY() + 0.8, p.getZ() + 0.5, 10, 0.3, 0.1, 0.3, 0.05);
                level.playSound(null, p, SoundEvents.SHEEP_SHEAR, SoundSource.NEUTRAL, 1.0F, 1.1F);
            }
        }
        Work kind = Work.named(w.getString("kind"));
        CompoundTag hands = w.getCompound("hands");
        int n = hands.getAllKeys().size();
        long days = Math.max(1, day - w.getLong("started"));
        String title = w.getString("title");
        Villages.tell(id, day, title + " was opened" + (cutter != null ? ", " + cutter.displayNameCap() + " cutting the ribbon" : "")
            + ": " + n + (n == 1 ? " hand" : " hands") + " laid its " + w.getInt("placed") + " stones in " + days + (days == 1 ? " day" : " days"));
        for (String k : hands.getAllKeys()) {
            UUID u;
            try {
                u = UUID.fromString(k);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (level.getEntity(u) instanceof VillageFolkEntity f && f.persona().rolled()) {
                f.persona().remember(day, "I helped build " + (kind == null ? "the town's work" : kind.the) + ", and saw it opened", 6);
                PROUD.put(u, day);
                f.refreshMood();
            }
            CompoundTag folk = CivicRecord.folk(u);
            folk.putInt("worksHands", folk.getInt("worksHands") + hands.getInt(k));
            folk.putString("worksLast", kind == null ? "" : kind.the);
        }
        CompoundTag doneOne = new CompoundTag();
        doneOne.putString("kind", w.getString("kind"));
        doneOne.putString("title", title);
        doneOne.putLong("day", day);
        doneOne.putInt("hands", n);
        doneOne.putInt("placed", w.getInt("placed"));
        doneOne.putLong("site", w.getLong("site"));
        ListTag doneList = CivicRecord.list(CivicRecord.town(id), "worksDone");
        doneList.add(doneOne);
        while (doneList.size() > 16) doneList.remove(0);
        if (kind == Work.WALL && w.contains("wallSide")) {
            CompoundTag town = CivicRecord.town(id);
            town.putInt("wallSides", town.getInt("wallSides") | (1 << w.getInt("wallSide")));
        }
        CivicRecord.town(id).remove("work");
        CivicRecord.changed();
        PIECES.remove(id);
        LOG.info("[MCA-CIVIC] {}: {} opened ({} hands, {} stones)", Villages.name(id), title, n, w.getInt("placed"));
    }

    // ------------------------------------------------------------------ lending a hand

    /**
     * From the folk's tick (VillageFolkEntity.aiStep): on the works day every fit grown folk, and after it any with
     * time on its hands (off work, or nothing to do at its trade), goes to the works and sets a piece every few
     * seconds out of the stores. True while it is at it.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        CompoundTag w = id == null ? null : current(id);
        if (w == null || !"building".equals(w.getString("stage")) || !called(f, level, w)) return release(f);
        Villages.Village v = Villages.get(id);
        if (v == null) return release(f);
        List<WorksPlans.Piece> pieces = pieces(id, w);
        int next = w.getInt("next");
        if (next >= pieces.size()) return release(f);
        if (!w.getString("waiting").isEmpty() && level.getGameTime() - w.getLong("waitedAt") < 1200L) return release(f);
        Work kind = Work.named(w.getString("kind"));
        String doing = "building " + (kind == null ? "the town's work" : kind.the);
        BlockPos piece = pieces.get(next).pos();
        // To its place at the works first; a hand that cannot get there (a ditch, a fence) works from where it got to,
        // if that is near enough the piece, after half a minute of trying.
        BlockPos stand = standFor(w, piece, f);
        long now = level.getGameTime();
        Long since = SET_OUT.get(f.getUUID());
        if (since == null) {
            // Down tools: whatever it had in hand waits (its queue let go, as for the town's other works: TownJobs).
            f.clearQueue();
            f.getNavigation().stop();
            SET_OUT.put(f.getUUID(), now);
            since = now;
            if (f.getRandom().nextInt(4) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'll lend a hand at " + (kind == null ? "the works" : kind.the) + ".",
                    "Off to the works!", "Many hands make light work."));
            }
        }
        boolean there = Civics.flat(f, stand) <= 5.0 * 5.0 && Math.abs(f.getY() - stand.getY()) <= 4.0;
        boolean nearEnough = now - since > 600L && Civics.flat(f, piece) <= REACH * REACH;
        if (!there && !nearEnough) {
            Civics.goTo(f, stand, 2.5, 0.95);
        } else {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(piece.getX() + 0.5, piece.getY() + 0.5, piece.getZ() + 0.5);
            Integer last = LAST_SET.get(f.getUUID());
            if (last == null || f.tickCount - last >= PIECE_TICKS || f.tickCount < last) {
                LAST_SET.put(f.getUUID(), f.tickCount);
                setNext(level, v, w, f);
            }
        }
        HELD.put(f.getUUID(), new Held(doing, f.tickCount));
        f.hobbyNow = doing;
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    /** Lending a hand at the works just now (between hold's looks)? */
    public static boolean busy(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        return h != null && f.tickCount >= h.tick() && f.tickCount - h.tick() <= 8;
    }

    /** What it is at, for its card, or null. */
    @Nullable
    static String doing(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        return h == null || f.tickCount - h.tick() > 40 ? null : h.doing();
    }

    private static boolean release(VillageFolkEntity f) {
        HELD.remove(f.getUUID());
        SET_OUT.remove(f.getUUID());
        return false;
    }

    /**
     * Is this folk called to the works now: by day, fit for it (TownJobs' own test: awake, home, not at a gathering,
     * the watch on its watch, the builder at its build), not on the watch at all; and either the works day's morning
     * (everybody), or its own time (off work), or nothing to do at its trade.
     */
    static boolean called(VillageFolkEntity f, ServerLevel level, CompoundTag w) {
        if (f.isBaby() || f.isShowcase() || !f.isAlive() || f.stationTask() == AssistantEntity.StationTask.GUARD) return false;
        UUID id = f.ownerId();
        if (id == null || Raids.underAlarm(id)) return false;
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        if (t < 1000L || t >= 12500L || Weather.stormy(level)) return false;
        if (!TownJobs.fit(f, "works") || TownJobs.busy(f) || JobSeekers.busy(f) || School.teaching(f) || Civics.busy(f)) return false;
        if (day == w.getLong("muster") && t >= MUSTER_FROM && t < MUSTER_TO) return true;
        if (f.offWorkNow()) return true;
        // A hand whose trade has run dry lends one too (not one still choosing its trade: that comes first).
        return f.workedOut();
    }

    /** Where a hand stands to work at this piece: the stand nearest it, a step or two apart from the next hand. */
    static BlockPos standFor(CompoundTag w, BlockPos piece, VillageFolkEntity f) {
        long[] stands = w.getLongArray("stands");
        BlockPos best = piece;
        double bd = Double.MAX_VALUE;
        for (long l : stands) {
            BlockPos s = BlockPos.of(l);
            double d = s.distSqr(piece);
            if (d < bd) { bd = d; best = s; }
        }
        int spread = Math.floorMod(f.getUUID().hashCode(), 5) - 2;
        return best.offset(spread, 0, Math.floorMod(f.getUUID().hashCode() >> 4, 5) - 2);
    }

    /**
     * The next piece set, by this hand: paid for out of the stores as it goes (a block of the work's stone, half for a
     * slab, a block and a half for stairs; a lantern, else a torch; a fence post; a bucket to carry the water, not
     * used up). A piece whose place is taken by anything built is passed over. Returns whether a piece was set; false
     * when the stores are short, and the works wait for them.
     */
    static boolean setNext(ServerLevel level, Villages.Village v, CompoundTag w, @Nullable VillageFolkEntity f) {
        UUID id = v.id();
        List<WorksPlans.Piece> pieces = pieces(id, w);
        WorksPlans.Family family = WorksPlans.Family.named(w.getString("family"));
        if (family == null) family = WorksPlans.Family.COBBLESTONE;
        int next = w.getInt("next");
        while (next < pieces.size()) {
            WorksPlans.Piece p = pieces.get(next);
            if (!level.isLoaded(p.pos())) {
                w.putInt("next", next);
                return false;                                       // its ground asleep: the works wait for somebody there
            }
            BlockState here = level.getBlockState(p.pos());
            if (p.part() == WorksPlans.Part.DIG) {
                next++;
                if (WorksPlans.openGround(here)) {
                    level.setBlock(p.pos(), Blocks.AIR.defaultBlockState(), 3);
                    w.putInt("next", next);
                    swing(level, f, p.pos(), here);
                    return true;
                }
                continue;
            }
            boolean ground = p.ground() && WorksPlans.openGround(here);
            if (!WorksPlans.room(here) && !ground) {
                w.putInt("spared", w.getInt("spared") + 1);
                next++;
                continue;
            }
            BlockState put = paid(level, v, w, family, p);
            if (put == null) {
                // Short of it: the works wait (a lantern or a post is passed over rather than held up for).
                if (p.part() == WorksPlans.Part.LANTERN || p.part() == WorksPlans.Part.LANTERN_HUNG || p.part() == WorksPlans.Part.FENCE
                        || p.part() == WorksPlans.Part.PLANKS) {
                    w.putInt("spared", w.getInt("spared") + 1);
                    next++;
                    continue;
                }
                w.putInt("next", next);
                w.putString("waiting", p.part() == WorksPlans.Part.WATER ? "a bucket" : family.words);
                w.putLong("waitedAt", level.getGameTime());
                CivicRecord.changed();
                return false;
            }
            if (!level.getFluidState(p.pos()).isEmpty() && level.getFluidState(p.pos()).isSource()
                    && put.hasProperty(BlockStateProperties.WATERLOGGED)) {
                put = put.setValue(BlockStateProperties.WATERLOGGED, true);
            }
            put = Block.updateFromNeighbourShapes(put, level, p.pos());
            level.setBlock(p.pos(), put, 3);
            next++;
            w.putInt("next", next);
            w.putInt("placed", w.getInt("placed") + 1);
            w.putString("waiting", "");
            if (f != null) {
                CompoundTag hands = w.getCompound("hands");
                hands.putInt(f.getUUID().toString(), hands.getInt(f.getUUID().toString()) + 1);
                w.put("hands", hands);
                f.note(AssistantEntity.Deed.BLOCKS_BUILT, 1);
            }
            CivicRecord.changed();
            swing(level, f, p.pos(), put);
            return true;
        }
        w.putInt("next", next);
        CivicRecord.changed();
        return false;
    }

    private static void swing(ServerLevel level, @Nullable VillageFolkEntity f, BlockPos at, BlockState st) {
        if (f != null) f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, at, st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.7F, 0.9F + level.getRandom().nextFloat() * 0.2F);
    }

    /** What goes in a piece's place, paid for out of the stores now; null if the stores cannot pay for it. */
    @Nullable
    static BlockState paid(ServerLevel level, Villages.Village v, CompoundTag w, WorksPlans.Family family, WorksPlans.Piece p) {
        WorksPlans.Part part = p.part();
        if (part.stone()) {
            int credit = w.getInt("credit");
            while (credit < part.halves) {
                if (!TownWork.take(level, v, family.payment(), 1)) {
                    w.putInt("credit", credit);
                    return null;
                }
                credit += 2;
            }
            w.putInt("credit", credit - part.halves);
            return switch (part) {
                case SLAB -> family.slab.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
                case SLAB_TOP -> family.slab.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
                case STAIRS -> family.stairs.defaultBlockState().setValue(StairBlock.FACING, p.facing()).setValue(StairBlock.HALF, Half.BOTTOM);
                case STAIRS_TOP -> family.stairs.defaultBlockState().setValue(StairBlock.FACING, p.facing()).setValue(StairBlock.HALF, Half.TOP);
                case WALL -> family.wall.defaultBlockState();
                default -> family.block.defaultBlockState();
            };
        }
        return switch (part) {
            case LANTERN -> TownWork.take(level, v, s -> s.is(Items.LANTERN), 1) ? Blocks.LANTERN.defaultBlockState()
                : TownWork.take(level, v, s -> s.is(Items.TORCH), 1) ? Blocks.TORCH.defaultBlockState() : null;
            case LANTERN_HUNG -> TownWork.take(level, v, s -> s.is(Items.LANTERN), 1)
                ? Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true) : null;
            case WATER -> Market.stock(level, v.id(), s -> s.is(Items.WATER_BUCKET) || s.is(Items.BUCKET)) > 0
                ? Blocks.WATER.defaultBlockState() : null;
            case FENCE -> wood(level, v, ItemTags.WOODEN_FENCES);
            case PLANKS -> wood(level, v, ItemTags.PLANKS);
            default -> null;
        };
    }

    /** One of the stores' wooden things of this kind, taken, and the block it is: an oak fence for an oak fence post. */
    @Nullable
    private static BlockState wood(ServerLevel level, Villages.Village v, net.minecraft.tags.TagKey<Item> tag) {
        for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
            Item it = h.value();
            if (Market.stock(level, v.id(), s -> s.is(it)) <= 0) continue;
            Block b = Block.byItem(it);
            if (b == Blocks.AIR) continue;
            if (TownWork.take(level, v, s -> s.is(it), 1)) return b.defaultBlockState();
        }
        return null;
    }

    // ------------------------------------------------------------------ the opening

    /** Is the opening due this evening (the work finished, the ribbon to be cut)? */
    static boolean openingDue(UUID village, long day) {
        CompoundTag w = current(village);
        return w != null && "opening".equals(w.getString("stage")) && w.getLong("openOn") <= day;
    }

    /** Where the town gathers to open it: before the ribbon, the crowd on the town's side. */
    static Assemblies.Assembly opening(ServerLevel level, Villages.Village v, long day) {
        CompoundTag w = current(v.id());
        BlockPos focus = w == null ? v.centre() : BlockPos.of(w.getLong("focus"));
        Direction audience = w == null ? Direction.SOUTH : Direction.byName(w.getString("audience"));
        if (audience == null || audience.getAxis().isVertical()) audience = Direction.SOUTH;
        return new Assemblies.Assembly(v.id(), Assemblies.Kind.REFERENDUM, "open", day, focus, audience, Assemblies.Layout.ARC);
    }

    /** The opening's lines: the work and its hands, what it cost and brings, and the ribbon cut. */
    static void openingScript(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        Villages.Village v = Villages.get(a.village);
        CompoundTag w = v == null ? null : current(v.id());
        if (w == null) return;
        Work kind = Work.named(w.getString("kind"));
        String the = kind == null ? "the work" : kind.the;
        int n = w.getCompound("hands").getAllKeys().size();
        s.add(new Assemblies.Line(null, "Friends! " + capital(w.getString("title")) + " is finished.", '!', null));
        s.add(new Assemblies.Line(null, n + " of you laid its " + w.getInt("placed")
            + " stones, and every one of you voted on it. It's ours — we built it together.", '!', null));
        s.add(new Assemblies.Line(null, "It cost the stores " + w.getString("costWords") + ". It brings us " + w.getString("brings") + ".", '?', null));
        String best = bestHand(level, w);
        if (best != null) s.add(new Assemblies.Line(null, "And a word of thanks to " + best + ", who laid more of it than anybody.", '!', null));
        boolean strung = w.getBoolean("strung");
        Villages.Village vv = v;
        s.add(new Assemblies.Line(null, strung ? "I cut this ribbon, and declare " + the + " open!" : "I declare " + the + " open!", '!', () -> {
            VillageFolkEntity cutter = a.host != null && level.getEntity(a.host) instanceof VillageFolkEntity h ? h : null;
            if (cutter != null) cutter.swing(InteractionHand.MAIN_HAND);
            opened(level, vv, cutter);
        }));
        s.add(new Assemblies.Line(null, FolkTalk.pick(r, "Off you go — try it out!", "Three cheers for " + Villages.name(a.village) + "!",
            "Well done, all of you."), '!', null));
    }

    @Nullable
    private static String bestHand(ServerLevel level, CompoundTag w) {
        CompoundTag hands = w.getCompound("hands");
        String best = null;
        int most = 0;
        for (String k : hands.getAllKeys()) {
            if (hands.getInt(k) <= most) continue;
            try {
                if (level.getEntity(UUID.fromString(k)) instanceof VillageFolkEntity f) {
                    most = hands.getInt(k);
                    best = f.displayNameCap();
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return most >= 3 ? best : null;
    }

    // ------------------------------------------------------------------ its spirits, its card, the board, the books

    /** Proud of what it built: three days after the opening. */
    static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Long d = PROUD.get(f.getUUID());
        if (d == null || day - d > 3) return m;
        why.add(new Object[]{ "builtit", 6 });
        return m + 4;
    }

    static String moodWords(VillageFolkEntity f) {
        String what = CivicRecord.known(f.getUUID()) ? CivicRecord.folk(f.getUUID()).getString("worksLast") : "";
        return FolkTalk.pick(f.getRandom(), "We built " + (what.isEmpty() ? "it" : what) + " ourselves, all of us. I laid my share of it!",
            "Have you seen " + (what.isEmpty() ? "what we built" : what) + "? I helped build that.");
    }

    /** Its card: what it is building now, and what it has lent a hand to. */
    static String cardLine(VillageFolkEntity f) {
        List<String> parts = new ArrayList<>();
        String now = doing(f);
        if (now != null) parts.add(capital(now));
        if (CivicRecord.known(f.getUUID())) {
            CompoundTag t = CivicRecord.folk(f.getUUID());
            if (t.getInt("worksHands") > 0) parts.add("laid " + t.getInt("worksHands") + " stones of the town's great works"
                + (t.getString("worksLast").isEmpty() ? "" : ", lately " + t.getString("worksLast")));
        }
        return String.join("; ", parts);
    }

    /** The board's lines: the work under way, how far on, what it waits for; and the last opened. */
    static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        CompoundTag w = current(village);
        long day = level.getDayTime() / 24000L;
        if (w != null) {
            List<WorksPlans.Piece> pieces = pieces(village, w);
            int pct = pieces.isEmpty() ? 100 : Math.min(100, 100 * w.getInt("next") / pieces.size());
            String stage = w.getString("stage");
            if (stage.equals("building")) {
                String waiting = w.getString("waiting");
                out.add((waiting.isEmpty() ? "RG" : "RW") + "|Building together: " + w.getString("title") + " — " + pct + "% done, "
                    + w.getCompound("hands").getAllKeys().size() + " hands so far"
                    + (day == w.getLong("muster") ? ". TODAY IS THE WORKS DAY: everybody lend a hand till noon!" : "")
                    + (waiting.isEmpty() ? "." : "; waiting for " + waiting + "."));
            } else {
                out.add("RG|Finished: " + w.getString("title") + ". Opened " + (w.getLong("openOn") <= day ? "this evening" : "tomorrow evening")
                    + " — come and see the ribbon cut!");
            }
        }
        return out;
    }

    /** The books' lines: the work under way and the works done. */
    static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        CompoundTag w = current(village);
        if (w != null) {
            List<WorksPlans.Piece> pieces = pieces(village, w);
            out.add("Under way: " + w.getString("title") + " (" + w.getString("stage") + "): " + w.getInt("next") + " of " + pieces.size()
                + " pieces, " + w.getInt("placed") + " stones laid by " + w.getCompound("hands").getAllKeys().size() + " hands; cost "
                + w.getString("costWords") + (w.getString("waiting").isEmpty() ? "" : "; waiting for " + w.getString("waiting")) + ".");
        }
        for (Tag t : CivicRecord.list(CivicRecord.town(village), "worksDone")) {
            if (!(t instanceof CompoundTag c)) continue;
            out.add("Day " + (c.getLong("day") + 1) + ": " + c.getString("title") + " opened, " + c.getInt("placed") + " stones by "
                + c.getInt("hands") + " hands.");
        }
        return out;
    }

    // ------------------------------------------------------------------ tests and the stage

    /** Tests: the hands who would come to the works now (it being the works day's morning), by name. */
    public static List<VillageFolkEntity> helpersForTests(ServerLevel level, UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        CompoundTag w = current(village);
        if (w == null) return out;
        w.putLong("muster", level.getDayTime() / 24000L);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && called(f, level, w)) out.add(f);
        }
        return out;
    }

    /**
     * Tests: these hands at the works, in turn, each setting a piece as it would at its spot (no walking), till the
     * work is done or {@code rounds} go by. Returns how many pieces each laid, by name.
     */
    public static Map<String, Integer> buildForTests(ServerLevel level, Villages.Village v, List<VillageFolkEntity> hands, int rounds) {
        Map<String, Integer> laid = new LinkedHashMap<>();
        CompoundTag w = current(v.id());
        if (w == null) return laid;
        for (int r = 0; r < rounds && w.getInt("next") < pieces(v.id(), w).size(); r++) {
            boolean any = false;
            for (VillageFolkEntity f : hands) {
                if (setNext(level, v, w, f)) {
                    laid.merge(f.displayNameCap(), 1, Integer::sum);
                    any = true;
                }
            }
            if (!any) break;
        }
        return laid;
    }

    /** Tests: one look at the work (finished, the ribbon strung), then opened as at the gathering, by its leader. */
    public static String finishAndOpenForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
        CompoundTag w = current(v.id());
        if (w == null) return "none";
        String stage = w.getString("stage");
        boolean strung = w.getBoolean("strung");
        opened(level, v, null);
        return stage + (strung ? " strung" : " unstrung");
    }

    /** Tests: the work under way ("kind|stage|next|pieces|placed|waiting"), or "". */
    public static String stateForTests(UUID village) {
        CompoundTag w = current(village);
        if (w == null) return "";
        return w.getString("kind") + "|" + w.getString("stage") + "|" + w.getInt("next") + "|" + pieces(village, w).size() + "|"
            + w.getInt("placed") + "|" + w.getString("waiting");
    }

    /** Tests and the stage: the pieces of the work under way (their places, in order). */
    public static List<BlockPos> piecesForTests(UUID village) {
        CompoundTag w = current(village);
        List<BlockPos> out = new ArrayList<>();
        if (w != null) for (WorksPlans.Piece p : pieces(village, w)) out.add(p.pos());
        return out;
    }

    /** Tests: the works this town would put to the vote now ("KIND title | cost"). */
    public static List<String> proposalsForTests(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (Proposal p : proposals(level, v)) out.add(p.kind().name() + " " + p.title() + " | " + p.costWords());
        return out;
    }

    /** Tests: a work drawn to the town's land now, as "PART x y z" a piece, in order (empty if the land has no place for it). */
    public static List<String> planForTests(ServerLevel level, Villages.Village v, Work kind) {
        List<String> out = new ArrayList<>();
        WorksPlans.Plan p = plan(level, v, kind);
        if (p != null) for (WorksPlans.Piece pc : p.pieces()) out.add(pc.part().name() + " " + pc.pos().getX() + " " + pc.pos().getY() + " " + pc.pos().getZ());
        return out;
    }

    /** Tests: the opened works, by kind. */
    public static List<String> doneForTests(UUID village) {
        List<String> out = new ArrayList<>();
        for (Tag t : CivicRecord.list(CivicRecord.town(village), "worksDone")) if (t instanceof CompoundTag c) out.add(c.getString("kind"));
        return out;
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
