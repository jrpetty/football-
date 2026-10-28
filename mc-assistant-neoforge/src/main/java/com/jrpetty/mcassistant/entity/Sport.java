package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sport and play [batchC]: what a town does with its day of rest besides the service, the games on the square
 * and walking out, and what its watch does of a working morning. The pieces are in their own classes:
 * <ul>
 * <li><b>the football pitch</b> (Pitch) and <b>football on the rest day</b> (Football): a league match between
 *     two of the town's ends every rest day, in the early afternoon;</li>
 * <li><b>the league and the cup</b> (League): the season's table, the champions at the town's year's end, and
 *     the cup in the hall;</li>
 * <li><b>friendlies between towns</b> (Friendlies): a side walks to a neighbour's pitch now and then;</li>
 * <li><b>the fishing contest</b> and <b>the children's sports day</b> (Contests), by turns of the rest days;</li>
 * <li><b>the archery range</b> (Archery): the watch's practice of a morning, and its contest on the rest day.</li>
 * </ul>
 * This class keeps the day's programme (what starts when), hands each folk to whichever of them has it, and
 * says where the player sees it all: the board, the town's books, /village sport.
 *
 * <p>The rest day: the archery contest from mid-morning (the watch keeps no service); the fishing contest or
 * the children's race in the late morning, by turns; the football from one o'clock, the visitors' if a neighbour's
 * side has come, else the league's.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Sport {

    private Sport() {}

    /** The tag a match's ball carries, so a ball left lying by a restart is known and put back in the stores. */
    public static final String BALL = "mca_football";

    /** The day each town's events last started, by "town/event". */
    private static final Map<String, Long> DONE = new ConcurrentHashMap<>();
    /** The day each town's daily look was last taken. */
    private static final Map<UUID, Long> DAILY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        Football.resetForTests();
        Friendlies.resetForTests();
        Contests.resetForTests();
        Archery.resetForTests();
        Pitch.resetForTests();
        League.resetForTests();
        DONE.clear();
        DAILY.clear();
    }

    // ------------------------------------------------------------------ the folk

    /**
     * From each folk's tick (VillageFolkEntity.aiStep): a match to play or watch, an away day on the road, a
     * contest, the butts. True while one of them has it in hand.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (f.isShowcase() || f.ownerId() == null || f.isHired()) return false;
        return Football.hold(f, level) || Friendlies.hold(f, level) || Contests.hold(f, level) || Archery.hold(f, level);
    }

    /** Is this folk playing, on an away day, in a contest or at the butts? (The town's works leave it be: TownJobs.) */
    public static boolean busy(VillageFolkEntity f) {
        return Football.busy(f) || Friendlies.busy(f) || Contests.busy(f) || Archery.busy(f);
    }

    /** What a folk is about for sport just now, for the top of its card (FolkTalk.nowDoing): "Playing football for the North End". */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        String h = f.hobbyNow();
        if (h == null || h.isEmpty()) return null;
        if (!busy(f) && !Football.watching(f) && !Contests.watching(f)) return null;
        return Character.toUpperCase(h.charAt(0)) + h.substring(1);
    }

    /** About any of it but this one (so that one does not take a folk from another). */
    static boolean busyElsewhere(VillageFolkEntity f, String mine) {
        return !mine.equals("football") && Football.busy(f) || !mine.equals("friendlies") && Friendlies.busy(f)
            || !mine.equals("contests") && Contests.busy(f) || !mine.equals("archery") && Archery.busy(f);
    }

    // ------------------------------------------------------------------ the clock

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        com.jrpetty.mcassistant.Guard.run("sport", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                Football.tick(level);                     // every tick: the ball, the kicks, the goals
                Contests.tick(level);                     // the races' starts and finishes; the fishing's hour
                Archery.tick(level);                      // the contest judged
                if (tick % 20 != 9) continue;
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    programme(level, v);
                }
            }
        });
    }

    /** True the first time it is asked on a day, for a town and an event. */
    static boolean once(UUID village, long day, String what) {
        String key = village + "/" + what;
        if (DONE.getOrDefault(key, -1L) >= day) return false;
        DONE.put(key, day);
        return true;
    }

    /** Every second, for each town in sight: the keepers' work, an away day, and the rest day's programme. */
    static void programme(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L, t = Math.floorMod(level.getDayTime(), 24000L);
        UUID id = v.id();
        if (DAILY.getOrDefault(id, -1L) < day) {
            DAILY.put(id, day);
            League.daily(level, v, day);                 // a year turned: the champions, the cup
            Friendlies.sweep(day);
            sweepBalls(level, v);
        }
        Pitch.tend(level, v);
        Archery.tend(level, v);
        Friendlies.consider(level, v, day);
        if (!RestDay.today(id, day) || Raids.underAlarm(id)) return;
        Friendlies.host(level, v, day);
        if (t >= Archery.CONTEST_AT && t < Archery.CONTEST_AT + 2000 && Archery.of(id) != null && once(id, day, "archery")) {
            Archery.startContest(level, v);
        }
        if (t >= Contests.FISH_AT && t < Contests.FISH_AT + 1500) {
            if (Contests.turn(day) == 0 && once(id, day, "fishing")) Contests.startFishing(level, v);
            if (Contests.turn(day) == 1 && once(id, day, "race")) Contests.startRace(level, v);
        }
        if (t >= Football.FROM && t < Football.LAST_KICK_OFF) Football.fixture(level, v, day);
    }

    /** A ball left lying on a pitch by a restart (its match forgotten): back into the stores. */
    static void sweepBalls(ServerLevel level, Villages.Village v) {
        Ledger.Building pitch = Pitch.of(v.id());
        if (pitch == null || Football.on(v.id()) || !Land.areaLoaded(level, pitch.anchor(), 12)) return;
        for (net.minecraft.world.entity.item.ItemEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(pitch.anchor()).inflate(8, 5, 12), x -> x.isAlive() && x.getTags().contains(BALL))) {
            Crafts.store(level, v, e.getItem().copy());
            e.discard();
        }
    }

    // ------------------------------------------------------------------ where the player sees it

    /** The board's lines (the right-hand column): what is on now, a side away, the last result and the cup. */
    public static List<String> board(ServerLevel level, UUID id) {
        List<String> out = new ArrayList<>();
        String now = Football.now(id);
        if (now == null) now = Contests.now(id);
        if (now == null) now = Archery.now(id);
        if (now != null) out.add("RG|Now: " + now + " — come and watch!");
        String away = Friendlies.line(id);
        if (away != null) out.add("RN|Football: " + away + ".");
        if (Pitch.of(id) == null && League.table(id).isEmpty() && League.cupHolder(id) == null) return out;
        List<String> bits = new ArrayList<>();
        League.Town t = League.town(id);
        if (!t.results.isEmpty()) bits.add("last time, " + t.results.get(0).replaceFirst("^Day \\d+: ", ""));
        List<League.Row> rows = League.table(id);
        if (!rows.isEmpty()) bits.add("top of the league, " + rows.get(0).team + " (" + rows.get(0).points() + " pts)");
        if (t.cupHolder != null) bits.add("the cup held by " + t.cupHolder);
        if (!bits.isEmpty()) out.add("RN|Football: " + String.join("; ", bits) + ".");
        return out;
    }

    /** The town's books (the News page): the pitch and the range, the programme, the league, the cup, the contests. */
    public static List<String> book(ServerLevel level, UUID id) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        out.add(Pitch.status(level, id));
        out.add(Archery.status(level, id));
        String now = Football.now(id);
        if (now == null) now = Contests.now(id);
        if (now != null) out.add("Now: " + now + ".");
        String away = Friendlies.line(id);
        if (away != null) out.add("Away: " + away + ".");
        long next = day;
        while (next < day + 8 && !RestDay.today(id, next)) next++;
        if (next < day + 8) {
            String extra = switch (Contests.turn(next)) {
                case 0 -> "the fishing contest";
                case 1 -> "the children's sports day";
                default -> "no contest";
            };
            out.add("The next rest day" + (next == day ? " (today)" : ", day " + next) + ": " + extra
                + (Pitch.of(id) != null ? ", football in the afternoon" : "") + (Archery.of(id) != null ? ", the archery contest in the morning" : "") + ".");
        }
        out.addAll(League.book(id, day));
        return out;
    }

    // ------------------------------------------------------------------ the command

    /**
     * /village sport: the nearest town's pitch, range, league, cup and contests. For operators (the photographs,
     * a look at once): /village sport pitch now (the pitch put up on its lot at once, its lines laid), match now
     * (a match begun now), range now (the range put up, its butts made targets, the watch sent to practise),
     * fishing now, race now, archery now, cup now (the league crowned now, the cup put up).
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("sport").executes(Sport::tell)
            .then(Commands.literal("pitch").requires(src -> src.hasPermission(2)).then(Commands.literal("now").executes(Sport::pitchNow)))
            .then(Commands.literal("match").requires(src -> src.hasPermission(2)).then(Commands.literal("now").executes(Sport::matchNow)))
            .then(Commands.literal("range").requires(src -> src.hasPermission(2)).then(Commands.literal("now").executes(Sport::rangeNow)))
            .then(Commands.literal("fishing").requires(src -> src.hasPermission(2)).then(Commands.literal("now").executes(ctx ->
                say(ctx, "FISHING " + withTown(ctx, (level, v) -> Contests.startFishing(level, v))))))
            .then(Commands.literal("race").requires(src -> src.hasPermission(2)).then(Commands.literal("now").executes(ctx ->
                say(ctx, "RACE " + withTown(ctx, (level, v) -> Contests.startRace(level, v))))))
            .then(Commands.literal("archery").requires(src -> src.hasPermission(2)).then(Commands.literal("now").executes(ctx ->
                say(ctx, "ARCHERY " + withTown(ctx, (level, v) -> Archery.startContest(level, v))))))
            .then(Commands.literal("cup").requires(src -> src.hasPermission(2)).then(Commands.literal("now").executes(ctx ->
                say(ctx, "CUP " + withTown(ctx, (level, v) -> {
                    String champs = League.crown(level, v, level.getDayTime() / 24000L);
                    return champs == null ? "no league table to crown" : champs + " crowned; " + book(level, v.id()).stream()
                        .filter(l -> l.contains(" Cup")).findFirst().orElse("");
                })))));
    }

    @Nullable
    static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        return v;
    }

    interface TownJob { String run(ServerLevel level, Villages.Village v); }

    static String withTown(CommandContext<CommandSourceStack> ctx, TownJob job) {
        Villages.Village v = near(ctx);
        return v == null ? "no village yet" : job.run(ctx.getSource().getLevel(), v);
    }

    static int say(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int tell(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        return say(ctx, "SPORT " + Villages.name(v.id()) + ": " + String.join(" | ", book(ctx.getSource().getLevel(), v.id())));
    }

    private static int pitchNow(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return say(ctx, "No village yet.");
        ServerLevel level = ctx.getSource().getLevel();
        Ledger.Building b = Pitch.of(v.id());
        if (b == null) {
            Villages.Site site = Villages.siteFor(level, v.id(), Pitch.STRUCTURE);
            if (site == null) return say(ctx, "No lot for a pitch yet: the ground is still coming in, or every long lot is taken.");
            b = Pitch.putUp(level, v.id(), site.anchor(), site.facing());
        }
        Pitch.linesNow(level, b);
        return say(ctx, "PITCH " + b.anchor().getX() + " " + b.anchor().getY() + " " + b.anchor().getZ() + " facing " + b.facing().getName()
            + " in " + Quarters.districtOf(v.id(), v.centre(), b).words);
    }

    private static int matchNow(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return say(ctx, "No village yet.");
        ServerLevel level = ctx.getSource().getLevel();
        Ledger.Building b = Pitch.of(v.id());
        if (b == null) return say(ctx, "No pitch: /village sport pitch now first.");
        String r = Football.fixtureForTests(level, v, level.getDayTime() / 24000L);
        return say(ctx, "MATCH " + b.anchor().getX() + " " + b.anchor().getY() + " " + b.anchor().getZ() + " facing " + b.facing().getName()
            + ": " + (r == null ? "a match is on already" : r));
    }

    private static int rangeNow(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return say(ctx, "No village yet.");
        ServerLevel level = ctx.getSource().getLevel();
        Ledger.Building b = Archery.of(v.id());
        if (b == null) {
            Villages.Site site = Villages.siteFor(level, v.id(), Archery.STRUCTURE);
            if (site == null) return say(ctx, "No lot for a range yet.");
            com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, Archery.STRUCTURE, site.anchor(), site.facing(), 0,
                com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
            Ledger.built(v.id(), Archery.STRUCTURE, site.anchor(), site.facing());
            b = Archery.of(v.id());
        }
        // For the photograph: targets on the butts at once.
        for (Archery.Lane l : Archery.lanes(b)) {
            level.setBlock(l.hay(), Blocks.HAY_BLOCK.defaultBlockState(), 3);
            level.setBlock(l.target(), Blocks.TARGET.defaultBlockState(), 3);
        }
        int sent = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (sent < 3 && a instanceof VillageFolkEntity f && f.stationTask() == AssistantEntity.StationTask.GUARD
                    && Archery.practiseForTests(f, level)) sent++;
        }
        return say(ctx, "RANGE " + b.anchor().getX() + " " + b.anchor().getY() + " " + b.anchor().getZ() + " facing " + b.facing().getName()
            + "; " + sent + " of the watch sent to practise");
    }
}
