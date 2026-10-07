package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageBoardBlock;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [batchF] Town life and governance: the business a town does as a town, beyond its trades and its
 * buildings, and what of it is kept with the world.
 * <ul>
 * <li>the post office and the letters folk write to family and friends in other towns, carried by
 *     whoever is walking that way with a caravan or an envoy's errand (Post);</li>
 * <li>petitions for a bench, a street light, a well or a road, signed at the board and put on the
 *     town's works by the council (Petitions);</li>
 * <li>the town meeting once a week, the elder's account of it and the folk's questions (TownMeeting);</li>
 * <li>a warden for each quarter, walking its streets of an evening (Wardens);</li>
 * <li>the public works fund, raised coin by coin for a statue on the square (PublicFund);</li>
 * <li>a search party for a folk not seen at home or at work for a whole day (SearchParties);</li>
 * <li>and the small favours neighbours do one another, and pay back (Favours).</li>
 * </ul>
 * This class is what they share: the record kept with the world (a compound for each town, one for each
 * folk, and the letters), the round of the towns every five seconds, the folk's part (one hook in its
 * tick, which hands it to whichever of them has it in hand), its spirits, its card, the board, the books,
 * the commands, and the player's right-clicks at the board and the post office.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Civics extends SavedData {

    private static final String ID = "mc_assistant_civics";
    /** The round of the towns: every five seconds. */
    static final int EVERY = 100;

    /** Everything kept: "towns" (a compound each), "folk" (a compound each) and "letters" (a list). */
    private CompoundTag root = new CompoundTag();
    /** With no server about (never in a game; a safeguard), a record that lives as long as the class. */
    @Nullable private static Civics loose;

    public Civics() {}

    // ------------------------------------------------------------------ the record

    static Civics of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Civics();
            return loose;
        }
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(Civics::new, Civics::load, null), ID);
    }

    /** A town's own compound (made the first time it is asked for). */
    static CompoundTag town(UUID village) {
        return sub(sub(of().root, "towns"), village.toString());
    }

    /** A folk's own compound (made the first time it is asked for). */
    static CompoundTag folk(UUID folk) {
        return sub(sub(of().root, "folk"), folk.toString());
    }

    /** Is there anything kept for this folk yet? */
    static boolean known(UUID folk) {
        return sub(of().root, "folk").contains(folk.toString(), Tag.TAG_COMPOUND);
    }

    /** Every folk with something kept, by id. */
    static CompoundTag allFolk() {
        return sub(of().root, "folk");
    }

    /** Every letter there is, wherever it is. */
    static ListTag letters() {
        return list(of().root, "letters");
    }

    static void changed() {
        of().setDirty();
    }

    /** A fresh number for a letter, a petition or a search: never the same twice in a world. */
    static int nextId() {
        CompoundTag root = of().root;
        int n = root.getInt("next") + 1;
        root.putInt("next", n);
        of().setDirty();
        return n;
    }

    static CompoundTag sub(CompoundTag t, String key) {
        if (!t.contains(key, Tag.TAG_COMPOUND)) t.put(key, new CompoundTag());
        return t.getCompound(key);
    }

    /**
     * The list kept under this key, itself (made the first time it is asked for), whatever it holds: compounds,
     * names or places. (CompoundTag.getList hands back a fresh empty list, kept nowhere, when the kept one holds
     * anything but what it was asked for: the wardens' lamps, a list of places, were all written into thin air.)
     */
    static ListTag list(CompoundTag t, String key) {
        if (!(t.get(key) instanceof ListTag)) t.put(key, new ListTag());
        return (ListTag) t.get(key);
    }

    public static Civics load(CompoundTag tag, HolderLookup.Provider registries) {
        Civics c = new Civics();
        c.root = tag.getCompound("Root");
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Root", root.copy());
        return tag;
    }

    /** Everything forgotten (the tests share one world; Villages.resetForTests). */
    public static void resetForTests() {
        of().root = new CompoundTag();
        of().setDirty();
        HELD.clear();
        PATHED.clear();
        GLAD.clear();
        Post.resetForTests();
        Petitions.resetForTests();
        TownMeeting.resetForTests();
        Wardens.resetForTests();
        PublicFund.resetForTests();
        SearchParties.resetForTests();
        Favours.resetForTests();
    }

    // ------------------------------------------------------------------ the round of the towns

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % EVERY != 37) return;
        com.jrpetty.mcassistant.Guard.run("town affairs", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    tick(level, v);
                }
            }
        });
    }

    /** One look at a town's affairs. */
    public static void tick(ServerLevel level, Villages.Village v) {
        com.jrpetty.mcassistant.Guard.run("the post", () -> Post.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("petitions", () -> Petitions.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("wardens", () -> Wardens.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("the public works fund", () -> PublicFund.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("search parties", () -> SearchParties.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("favours", () -> Favours.tick(level, v));
    }

    /**
     * What the town would build (Villages.projectsWanted), with the post office (Post) and the statue its own fund
     * paid for (PublicFund) at the end of the list once they are wanted: after everything else, so they never hold
     * up a house or what an age asks for. (The statue, asked for when the fund is raised, comes first all the same.)
     */
    public static List<String> wanted(UUID village, List<String> out) {
        boolean post = Post.wanted(village, Villages.headcount(village)), statue = PublicFund.wanted(village);
        if (!post && !statue) return out;
        List<String> all = new ArrayList<>(out);
        if (post && !all.contains(Post.STRUCTURE)) all.add(Post.STRUCTURE);
        if (statue && !all.contains(PublicFund.STRUCTURE)) all.add(PublicFund.STRUCTURE);
        return all;
    }

    // ------------------------------------------------------------------ the folk's part

    private record Held(String doing, int tick) {}

    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PATHED = new ConcurrentHashMap<>();

    /**
     * From the folk's tick (VillageFolkEntity.aiStep), every few ticks: a search party out looking, the
     * postman's round or a letter to post, a warden's evening walk, a name put to a petition, a favour
     * asked or paid back. True while one of them has it in hand; its own day waits till it is done.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (f.isShowcase() || f.isHired() || f.ownerId() == null || !f.isAlive()) return release(f);
        String doing = SearchParties.hold(f, level);
        if (doing == null && !f.isSleeping()) doing = Post.hold(f, level);
        if (doing == null && !f.isSleeping()) doing = Wardens.hold(f, level);
        if (doing == null && !f.isSleeping()) doing = Petitions.hold(f, level);
        if (doing == null && !f.isSleeping()) doing = Favours.hold(f, level);
        if (doing == null) return release(f);
        HELD.put(f.getUUID(), new Held(doing, f.tickCount));
        f.hobbyNow = doing;
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    /** Is one of the town's affairs keeping this folk busy just now (between hold's looks)? */
    public static boolean busy(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        return h != null && f.tickCount >= h.tick() && f.tickCount - h.tick() <= 8;
    }

    /** What it is about, for its card, or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        return h == null || f.tickCount - h.tick() > 40 ? null : h.doing();
    }

    private static boolean release(VillageFolkEntity f) {
        HELD.remove(f.getUUID());
        return false;
    }

    /**
     * Free for the town's affairs: grown, awake, off work (its break, the evening, the day of rest), and
     * nothing else in hand — not the town's works, a gathering, the school, the family, the road.
     */
    static boolean free(VillageFolkEntity f) {
        if (f.isBaby() || f.isSleeping() || !f.isAlive() || f.isShowcase() || f.isHired()) return false;
        if (f.trip() != null || f.expedition() != null || Nether.away(f) || JobSeekers.busy(f) || Drover.busy(f)
                || Stables.busy(f) || Patrols.escorting(f)) return false;
        if (Raids.underAlarm(f.ownerId())) return false;
        return f.offWorkNow() && !TownJobs.busy(f) && !Assemblies.attending(f) && !School.teaching(f)
            && !Families.busy(f) && !TownCalendar.busy(f) && f.getTarget() == null;
    }

    /** On its way somewhere on the town's business: true once it is within {@code reach} of the spot. */
    static boolean goTo(VillageFolkEntity f, BlockPos to, double reach, double speed) {
        double dx = f.getX() - (to.getX() + 0.5), dz = f.getZ() - (to.getZ() + 0.5);
        if (dx * dx + dz * dz <= reach * reach && Math.abs(f.getY() - to.getY()) <= 3.0) {
            f.getNavigation().stop();
            return true;
        }
        Integer last = PATHED.get(f.getUUID());
        if (last == null || f.tickCount - last > 40 || f.tickCount < last || f.getNavigation().isDone()) {
            f.walkTo(to, speed);
            PATHED.put(f.getUUID(), f.tickCount);
        }
        return false;
    }

    /** Flat distance, squared, between a folk and a spot. */
    static double flat(VillageFolkEntity f, BlockPos p) {
        double dx = f.getX() - (p.getX() + 0.5), dz = f.getZ() - (p.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    /** A loaded folk of any town by its id, or null. */
    @Nullable
    static VillageFolkEntity find(ServerLevel level, @Nullable UUID id) {
        if (id == null) return null;
        return level.getEntity(id) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    /** The grown folk of a town that are loaded (not the showcase's stand-ins). */
    static List<VillageFolkEntity> grown(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isShowcase()) out.add(f);
        }
        return out;
    }

    /** Where it lives: its bed, else its household's house, else null. */
    @Nullable
    static BlockPos home(VillageFolkEntity f) {
        if (f.bedPos() != null) return f.bedPos();
        UUID village = f.ownerId();
        if (village == null) return null;
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        return h == null ? null : h.anchor;
    }

    static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "Ash", "Ash and Bree", "Ash, Bree and Cole". */
    static String names(List<String> names) {
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    // ------------------------------------------------------------------ its spirits

    /** The days things went well for it, by folk: a letter read, the town meeting, a favour, found and home. */
    static final Map<UUID, long[]> GLAD = new ConcurrentHashMap<>();
    static final int LETTER = 0, MEETING = 1, FAVOUR = 2, FOUND = 3;
    private static final String[] GLAD_KEYS = { "letter", "meeting", "favour", "found" };
    /** How much each lifts its spirits, and how high it comes in what it speaks of (a letter from home before the rain). */
    private static final int[] GLAD_LIFT = { 4, 3, 3, 6 }, GLAD_WEIGHT = { 9, 5, 6, 12 };

    static void glad(VillageFolkEntity f, int what, long day) {
        long[] d = GLAD.computeIfAbsent(f.getUUID(), k -> new long[]{ -100, -100, -100, -100 });
        d[what] = day;
        f.refreshMood();
    }

    /** Its spirits (VillageFolkEntity.refreshMood): a letter from home, the town meeting, a good turn, found. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        long[] d = GLAD.get(f.getUUID());
        if (d == null) return m;
        for (int i = 0; i < d.length; i++) {
            if (day - d[i] <= 1) {
                m += GLAD_LIFT[i];
                why.add(new Object[]{ GLAD_KEYS[i], GLAD_WEIGHT[i] });
            }
        }
        return m;
    }

    /** Tests: is its spirits lifted today by this ("letter", "meeting", "favour", "found")? */
    public static boolean gladForTests(VillageFolkEntity f, String key) {
        List<Object[]> why = new ArrayList<>();
        mood(f, f.level().getDayTime() / 24000L, 0, why);
        for (Object[] w : why) if (key.equals(w[0])) return true;
        return false;
    }

    /** How it puts it, asked how it is (FolkTalk.reason). */
    public static String moodWords(VillageFolkEntity f, String why) {
        CompoundTag t = folk(f.getUUID());
        return switch (why) {
            case "letter" -> t.getString("lastLetter").isEmpty() ? "I had a letter!" : "I had a letter from " + t.getString("lastLetter") + "!";
            case "meeting" -> "I was at the town meeting. It's good to know how we stand.";
            case "favour" -> FolkTalk.pick(f.getRandom(), "A neighbour did me a good turn.", "There's nothing like good neighbours.");
            case "found" -> "I was lost, and they came looking for me. I won't forget it.";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ where the player sees it

    /** Its card (FolkTalk.card): its letters, its quarter if it is a warden, the good turns it has done. */
    public static String cardLine(VillageFolkEntity f) {
        if (f.ownerId() == null || f.isShowcase()) return "";
        List<String> parts = new ArrayList<>();
        String now = doing(f);
        if (now != null) parts.add(cap(now));
        String warden = Wardens.cardLine(f);
        if (!warden.isEmpty()) parts.add(warden);
        String post = Post.cardLine(f);
        if (!post.isEmpty()) parts.add(post);
        String favours = Favours.cardLine(f);
        if (!favours.isEmpty()) parts.add(favours);
        String petition = Petitions.cardLine(f);
        if (!petition.isEmpty()) parts.add(petition);
        CompoundTag t = folk(f.getUUID());
        if (t.getInt("gave") > 0) parts.add("gave " + t.getInt("gave") + " to the " + PublicFund.word(f.ownerId()) + " fund");
        if (t.getInt("searched") > 0) parts.add("went out with " + t.getInt("searched") + (t.getInt("searched") == 1 ? " search party" : " search parties"));
        return String.join("; ", parts);
    }

    /** The board's lines: the post, the petitions, the fund, the next town meeting, the wardens. */
    public static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Guard g = new Guard(out);
        g.add(() -> Post.board(level, village));
        g.add(() -> Petitions.board(level, village));
        g.add(() -> PublicFund.board(level, village));
        g.add(() -> TownMeeting.board(level, village));
        g.add(() -> SearchParties.board(level, village));
        return out;
    }

    /** The town's books (the News page): the town's affairs, a line each. */
    public static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Guard g = new Guard(out);
        g.add(() -> TownMeeting.book(level, village));
        g.add(() -> Petitions.book(level, village));
        g.add(() -> PublicFund.book(level, village));
        g.add(() -> Post.book(level, village));
        g.add(() -> Wardens.book(level, village));
        g.add(() -> SearchParties.book(level, village));
        g.add(() -> Favours.book(level, village));
        return out;
    }

    /** Lines gathered so that one part going wrong never costs the board the rest. */
    private record Guard(List<String> out) {
        void add(java.util.function.Supplier<List<String>> part) {
            try {
                out.addAll(part.get());
            } catch (RuntimeException e) {
                com.mojang.logging.LogUtils.getLogger().warn("[MCA-CIVICS] a line failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ the player's right-clicks

    /**
     * At the board with coin in hand: given to the public works fund (PublicFund). At the post office's
     * sign: a letter (a book and quill, or a written book) posted to a folk by name, or the letters
     * waiting for the player handed over (Post).
     */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p)) return;
        BlockPos pos = e.getPos();
        ItemStack held = p.getItemInHand(e.getHand());
        if (level.getBlockState(pos).getBlock() instanceof VillageBoardBlock && Market.isCoin(held)) {
            Villages.Village v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE);
            if (v == null) return;
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            if (e.getHand() != InteractionHand.MAIN_HAND) return;
            int n = p.isShiftKeyDown() ? held.getCount() : 1;
            p.displayClientMessage(Component.literal(PublicFund.donate(level, v, p, n)).withStyle(ChatFormatting.GOLD), false);
            return;
        }
        Villages.Village v = Post.officeAt(level, pos);
        if (v == null) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        p.displayClientMessage(Component.literal(Post.counter(level, v, p)).withStyle(ChatFormatting.YELLOW), false);
    }

    /** The postman, with a letter held out to it: posted, as at the counter (and not taken for a present). */
    @SubscribeEvent
    public static void onUseFolk(PlayerInteractEvent.EntityInteract e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (!(e.getTarget() instanceof VillageFolkEntity f) || f.ownerId() == null) return;
        if (!Post.isPostman(f) || !Post.isLetter(p.getItemInHand(e.getHand()))) return;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        String said = Post.counter(level, v, p);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'll see it goes.", "Leave it with me.", "Into the bag it goes."));
        p.displayClientMessage(Component.literal(said).withStyle(ChatFormatting.YELLOW), false);
    }

    // ------------------------------------------------------------------ the commands

    /** "/village donate <coins>": coin given to the nearest town's public works fund. */
    public static LiteralArgumentBuilder<CommandSourceStack> donateCommand() {
        return Commands.literal("donate")
            .then(Commands.argument("coins", IntegerArgumentType.integer(1, 10000)).executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                Villages.Village v = Villages.nearest(p.serverLevel(), p.blockPosition(), 160);
                if (v == null) {
                    ctx.getSource().sendFailure(Component.literal("No village near enough to give to."));
                    return 0;
                }
                String said = PublicFund.donate(p.serverLevel(), v, p, IntegerArgumentType.getInteger(ctx, "coins"));
                ctx.getSource().sendSuccess(() -> Component.literal(said), false);
                return 1;
            }));
    }

    /**
     * "/village civic": the town's affairs, in the nearest town — the post, the petitions, the town
     * meeting, the wardens, the fund, the searches and the good neighbours; and for an operator, each of
     * them set going now, and a stage for the pictures.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("civic")
            .executes(ctx -> say(ctx, near -> String.join("\n", book(level(ctx), near.id()))))
            .then(Commands.literal("meeting").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, near -> Assemblies.startNow(level(ctx), near, Assemblies.Kind.MEETING)
                    ? "MEETING the town meeting is called at " + Villages.name(near.id()) : "MEETING nobody to hold it")))
            .then(Commands.literal("petition").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, near -> "PETITION " + Petitions.raiseNow(level(ctx), near))))
            .then(Commands.literal("wardens").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, near -> "WARDENS " + Wardens.appointNow(level(ctx), near))))
            .then(Commands.literal("letters").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, near -> "LETTERS " + Post.writeNow(level(ctx), near))))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, near -> String.join("\n", CivicStage.set(level(ctx), near,
                    BlockPos.containing(ctx.getSource().getPosition()))))));
    }

    private static ServerLevel level(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getLevel();
    }

    private static int say(CommandContext<CommandSourceStack> ctx, java.util.function.Function<Villages.Village, String> what) {
        Vec3 at = ctx.getSource().getPosition();
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), BlockPos.containing(at), 200);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        String out = what.apply(v);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    /** Coin out of a player's pack, if they have it: true when paid. */
    static boolean takeCoins(Player p, int n) {
        if (n <= 0 || Market.coinsHeld(p) < n) return false;
        Market.payOut(p, n);
        return true;
    }
}
