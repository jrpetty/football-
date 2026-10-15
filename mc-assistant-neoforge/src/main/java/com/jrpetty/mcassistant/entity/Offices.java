package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.LibraryRecords;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [roles-a] The town's offices, and the allowance each pays on top of its holder's wage. An office is not a trade: the
 * constable stays on the watch, the librarian at its own trade; but the office takes some of its day, and a town that
 * asks it of a folk pays for it. Who holds each is the office's own system's to say (Inquiry, CaveDwellers, Library,
 * Museum, Crier, Wardens, PlayerLeader, Auctions, PlayerTrades, Sexton; [roles-e] TownPlanner, Wheelwrights, WatchRanks);
 * this class only gathers them, once every five seconds a town, for the wage (JobWorth.payOf), the wage's breakdown
 * (Wealth.breakdown) and the books ("/village offices").
 *
 * <p>Each office's allowance is a fixed share of a day's unit at the town's pay level (so it grows with the town as its
 * wages do), at least a coin; a folk holding several is paid for its two best. The elder's coin and the teacher's
 * mornings are paid as before (JobWorth.Pay elder, teaching).
 */
public final class Offices {

    private Offices() {}

    /** An office: its words, and its share of a day's unit at the town's pay level. */
    public enum Office {
        CONSTABLE("the constable", 0.40),
        STEWARD("the leader's steward", 0.40),
        SEXTON("the sexton", 0.35),
        CAVE_LEADER("the cave team's leader", 0.30),
        LIBRARIAN("the librarian", 0.30),
        CURATOR("the museum's curator", 0.30),
        AUCTIONEER("the auctioneer", 0.30),
        MASTER("a master of its trade", 0.25),
        WARDEN("a quarter's warden", 0.20),
        CRIER("the town crier", 0.15),
        MILLER("the miller", 0.20),                                     // [roles-c] the windmill's keeper
        // [roles-e] The town planner at the hall, the wheelwright at its shed, and the watch's ranks (WatchRanks): the
        // captain who is not the constable (the constable is paid as the constable), and a sergeant for every six guards.
        PLANNER("the town planner", 0.35),
        WHEELWRIGHT("the wheelwright", 0.30),
        WATCH_CAPTAIN("the captain of the watch", 0.30),
        SERGEANT("a sergeant of the watch", 0.20);

        public final String words;
        public final double share;

        Office(String words, double share) {
            this.words = words;
            this.share = share;
        }
    }

    /** A folk is paid for this many of its offices at most, the best first. */
    static final int MOST_PAID = 2;
    /** How long a town's offices are kept before they are gathered again (ticks). */
    static final long KEPT = 100L;

    private record Book(long at, Map<UUID, Set<Office>> held) {}

    private static final Map<UUID, Book> BOOKS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        BOOKS.clear();
        LANTERNS.clear();
    }

    /** The offices this folk holds (none for a child or a folk of no town). */
    public static Set<Office> of(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby()) return Set.of();
        long now = f.level().getGameTime();
        Book b = BOOKS.get(id);
        if (b == null || now - b.at() >= KEPT || now < b.at()) {
            b = new Book(now, gather(id));
            BOOKS.put(id, b);
        }
        Set<Office> got = b.held().get(f.getUUID());
        return got == null ? Set.of() : got;
    }

    /** Tests: the town's offices gathered afresh at the next ask. */
    public static void forgetForTests(UUID village) {
        BOOKS.remove(village);
    }

    /** Who holds what in the town, now. */
    private static Map<UUID, Set<Office>> gather(UUID id) {
        Map<UUID, Set<Office>> out = new HashMap<>();
        give(out, Inquiry.constable(id), Office.CONSTABLE);
        give(out, CaveDwellers.leaderOf(id), Office.CAVE_LEADER);
        give(out, Sexton.holder(id), Office.SEXTON);
        if (LibraryRecords.has(id)) give(out, LibraryRecords.shelf(id).librarian, Office.LIBRARIAN);
        give(out, Crier.crier(id), Office.CRIER);
        give(out, Windmill.miller(id), Office.MILLER);                  // [roles-c]
        // The auctioneer paid as an office only when the panel chose one other than the elder, for whom calling the
        // auctions is part of leading (its own coin, JobWorth.Pay elder).
        VillageFolkEntity auctioneer = Auctions.house(id) != null ? Interviews.holder(id, "auctioneer") : null;
        if (auctioneer != null && !auctioneer.getUUID().equals(Villages.elder(id))) give(out, auctioneer, Office.AUCTIONEER);
        if (PlayerLeader.leaderId(id) != null) {
            String s = Ledger.note(id, "civic.steward");
            if (s != null && !s.isEmpty()) {
                try {
                    give(out, UUID.fromString(s.split("\\|", 2)[0]), Office.STEWARD);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        Set<StationTask> mastered = EnumSet.noneOf(StationTask.class);
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            if (Museum.isCurator(f)) give(out, f, Office.CURATOR);
            if (Wardens.quarterOf(f) != null) give(out, f, Office.WARDEN);
            StationTask t = f.stationTask();
            if (t != StationTask.NONE && f.tradeLevel(t) >= Lessons.MASTER && Lessons.of(t) != null) mastered.add(t);
        }
        for (StationTask t : mastered) give(out, PlayerTrades.masterOf(id, t), Office.MASTER);
        // [roles-e] The town planner, the wheelwright, and the watch's ranks.
        give(out, TownPlanner.holder(id), Office.PLANNER);
        give(out, Wheelwrights.holder(id), Office.WHEELWRIGHT);
        UUID captain = Police.captainId(id);
        if (captain != null && Inquiry.constable(id) == null) give(out, captain, Office.WATCH_CAPTAIN);
        for (UUID s : WatchRanks.sergeants(id)) give(out, s, Office.SERGEANT);
        return out;
    }

    private static void give(Map<UUID, Set<Office>> out, @Nullable VillageFolkEntity f, Office o) {
        if (f != null) give(out, f.getUUID(), o);
    }

    private static void give(Map<UUID, Set<Office>> out, @Nullable UUID f, Office o) {
        if (f != null) out.computeIfAbsent(f, k -> EnumSet.noneOf(Office.class)).add(o);
    }

    /** The offices it is paid for, the best first (two at most). */
    static List<Office> paid(VillageFolkEntity f) {
        List<Office> all = new ArrayList<>(of(f));
        all.sort(Comparator.comparingDouble((Office o) -> -o.share));
        return all.size() > MOST_PAID ? all.subList(0, MOST_PAID) : all;
    }

    /** One office's allowance at this day's unit (the town's pay level times the unit): at least a coin. */
    static int allowance(Office o, double dayUnit) {
        return Math.max(1, (int) Math.round(dayUnit * o.share));
    }

    /** What its offices pay it today, on top of its wage (JobWorth.payOf). */
    public static int allowance(VillageFolkEntity f, double dayUnit) {
        int n = 0;
        for (Office o : paid(f)) n += allowance(o, dayUnit);
        return n;
    }

    /** Its offices in the wage's breakdown: ", +2 as the constable, +1 as the sexton". */
    public static String words(VillageFolkEntity f, double dayUnit) {
        StringBuilder sb = new StringBuilder();
        for (Office o : paid(f)) sb.append(", +").append(allowance(o, dayUnit)).append(" as ").append(o.words);
        return sb.toString();
    }

    // ------------------------------------------------------------------ the warden's lantern

    /** Wardens holding a lantern out of the stores on their night round. */
    private static final Set<UUID> LANTERNS = ConcurrentHashMap.newKeySet();

    /**
     * [Wardens.hold, end] A warden's lantern on its round after dark: one out of the stores into its other hand (none in
     * the stores, none carried), and back into them when the round is done. A real lantern: it is never made from nothing.
     */
    static void lantern(VillageFolkEntity f, ServerLevel level, @Nullable Villages.Village v, boolean on) {
        net.minecraft.world.entity.EquipmentSlot hand = net.minecraft.world.entity.EquipmentSlot.OFFHAND;
        ItemStack off = f.getItemBySlot(hand);
        if (on) {
            if (LANTERNS.contains(f.getUUID()) || v == null || !off.isEmpty()) return;
            ItemStack l = Crafts.takeOne(level, v, s -> s.is(Items.LANTERN));
            if (l.isEmpty()) return;
            f.setItemSlot(hand, l.copyWithCount(1));
            LANTERNS.add(f.getUUID());
            return;
        }
        if (!LANTERNS.remove(f.getUUID()) || !off.is(Items.LANTERN)) return;
        f.setItemSlot(hand, ItemStack.EMPTY);
        if (v != null) {
            Crafts.store(level, v, off);
        } else {
            ItemStack left = f.insertItem(off);
            if (!left.isEmpty()) f.spawnAtLocation(left);
        }
    }

    // ------------------------------------------------------------------ the books

    /** "/village offices": who holds each office, and what it pays; the sexton's chapel and the lamplighter's round. */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        double unit = JobWorth.scale(level, id).payLevel * JobWorth.UNIT;
        List<String> out = new ArrayList<>();
        out.add("OFFICES in " + Villages.name(id) + " (an allowance on the wage, at " + String.format(java.util.Locale.ROOT, "%.2f", unit)
            + " a day's unit; two offices paid at most):");
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            Set<Office> held = of(f);
            if (held.isEmpty()) continue;
            List<String> w = new ArrayList<>();
            for (Office o : held) w.add(o.words + " (+" + allowance(o, unit) + ")");
            out.add("  " + f.displayNameCap() + ": " + String.join(", ", w));
            n++;
        }
        if (n == 0) out.add("  none held yet");
        out.addAll(Sexton.lines(level, id));
        out.addAll(Lamplighter.lines(level, v));
        return out;
    }

    /** Tests: how a witness describes a folk of this trade by its clothes (Mischief.outfit). */
    public static String clothesForTests(StationTask t) {
        return Mischief.outfit(t);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("offices")
            .then(Commands.literal("stage").requires(s -> s.hasPermission(2)).executes(ctx -> {
                // [roles-a] The posts set out for the pictures, here (RolesStage): "VIEW name x y z ax ay az" lines.
                List<String> lines = RolesStage.stage(ctx.getSource().getLevel(), net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition()));
                for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
                return lines.size();
            }))
            .executes(ctx -> {
            ServerLevel level = ctx.getSource().getLevel();
            Villages.Village v = PlayerServices.townAt(level, net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition()));
            if (v == null) {
                ctx.getSource().sendFailure(Component.literal("There's no village here."));
                return 0;
            }
            List<String> lines = lines(level, v);
            for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
            return lines.size();
        });
    }
}
