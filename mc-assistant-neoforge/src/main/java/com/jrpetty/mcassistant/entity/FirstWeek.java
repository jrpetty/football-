package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * [wpa] "Your first week in town": a gentle, skippable path for a newcomer, a step at a time, each teaching one system in
 * one line and paying a little for it (a couple of coins from the treasury, and the folk warmer to you). It starts when
 * the player signs a town's book and follows that town. Each step has its "Show me" (the arrow to where it is done) and
 * a button to the screen it is done on; it ticks itself off when it is done, however it was done.
 * <ol>
 * <li>Sign the town's book at the Town Hall.</li>
 * <li>Find a room: a night at the inn, a house of your own, or a home with family.</li>
 * <li>Read the job adverts and take a first job or shift.</li>
 * <li>Work it, and draw your first wage.</li>
 * <li>Put some coin by: the bank, or the savings club before there is one.</li>
 * <li>Buy something in the town: the shop, the café, the market.</li>
 * <li>Meet the neighbours: three folk who know you by name.</li>
 * <li>Pay a first bill: taxes, rent, a fee.</li>
 * </ol>
 * After those, a second chapter for when the player is ready: become a citizen, own a house, start a business.
 */
@EventBusSubscriber(modid = com.jrpetty.mcassistant.McAssistantMod.MODID)
public final class FirstWeek {

    private FirstWeek() {}

    /** Each step's coin from the treasury, and the warmth from those who know the player. */
    public static final int REWARD_COINS = 2, REWARD_WARMTH = 2;

    public enum Step {
        REGISTER("Sign the town's book", "Registering makes you a resident: you wake here if you fall, and the town counts you one of its own.",
            Items.WRITABLE_BOOK, false),
        ROOM("Find a room", "A bed of your own for the night: a room at the inn, or a house. Rest is where your day starts.",
            Items.RED_BED, false),
        JOB("Take a first job", "The job adverts list what the town needs doing, the pay and the hours. Take a shift or an opening.",
            Items.PAPER, false),
        WAGE("Draw your first wage", "Work the shift to the end: your pay comes out of the town's treasury, into your pack.",
            Items.CLOCK, false),
        SAVE("Put some coin by", "Savings earn a little each week. The bank keeps them, or the savings club at the hall before it opens.",
            Items.GOLD_NUGGET, false),
        BUY("Buy something in town", "The shop, the café and the market sell the town's goods at the board's prices.",
            Items.BREAD, false),
        NEIGHBOUR("Meet the neighbours", "Three folk who know you by name. Talk with them; a gift helps. Their word is your respect.",
            Items.POPPY, false),
        BILL("Pay a first bill", "Taxes, rent and fees keep the town going. Paying on time is half of a good name.",
            Items.GOLD_INGOT, false),
        // ---------------------------------------------------------------- chapter two, once the week is done
        CITIZEN("Become a citizen", "A vote, a say, a tenth off at the shop and the café, and a place on the jury.", Items.BLUE_BANNER, true),
        HOUSE("Own a house", "Buy the one you stand in, or one on the market. It's yours to live in or let.", Items.OAK_DOOR, true),
        BUSINESS("Start a business", "A charter, premises and hands of your own: the town's books will carry your name.", Items.WRITABLE_BOOK, true);

        public final String label, why;
        public final net.minecraft.world.item.Item icon;
        public final boolean later;

        Step(String label, String why, net.minecraft.world.item.Item icon, boolean later) {
            this.label = label;
            this.why = why;
            this.icon = icon;
            this.later = later;
        }
    }

    // ------------------------------------------------------------------ the record

    /** The town the week follows, or null if it hasn't begun. */
    @Nullable
    public static UUID town(UUID player) {
        CompoundTag w = Belonging.week(player);
        if (!w.contains("town")) return null;
        try { return UUID.fromString(w.getString("town")); } catch (IllegalArgumentException e) { return null; }
    }

    public static boolean done(UUID player, Step s) {
        return (Belonging.week(player).getInt("done") & (1 << s.ordinal())) != 0;
    }

    public static boolean skipped(UUID player) {
        return Belonging.week(player).getBoolean("skip");
    }

    /** How many of the first eight are done. */
    public static int count(UUID player) {
        int n = 0;
        for (Step s : Step.values()) if (!s.later && done(player, s)) n++;
        return n;
    }

    public static int firstEight() {
        int n = 0;
        for (Step s : Step.values()) if (!s.later) n++;
        return n;
    }

    /** The next step not done (the first chapter first), or null. */
    @Nullable
    public static Step next(UUID player) {
        for (Step s : Step.values()) if (!done(player, s)) return s;
        return null;
    }

    /** Begun when the player signs a town's book (its first; a second town doesn't start it again). */
    static void begin(ServerPlayer p, UUID village) {
        CompoundTag w = Belonging.week(p.getUUID());
        if (!w.contains("town")) {
            w.putString("town", village.toString());
            w.putLong("from", p.level().getDayTime() / 24000L);
            Belonging.dirty();
        }
        check(p);
    }

    public static void skip(UUID player, boolean skip) {
        Belonging.week(player).putBoolean("skip", skip);
        Belonging.dirty();
    }

    // ------------------------------------------------------------------ what counts

    /** Is this step done now, by what the town's books say? */
    static boolean met(ServerPlayer p, UUID village, Step s) {
        UUID me = p.getUUID();
        ServerLevel level = p.serverLevel();
        long day = level.getDayTime() / 24000L;
        Map<PlayerFinance.Cat, Long> books = PlayerFinance.spanTown(me, village.toString(), day, 30);
        return switch (s) {
            case REGISTER -> Belonging.registered(village, me);
            case ROOM -> Homes.playerHouses(village, me)[0] > 0 || PlayerFamily.homeOf(village, me) != null
                || Inn.roomForTests(level, village, me) != null || some(books, PlayerFinance.Cat.RENT_OUT) || ownBed(p, village);
            case JOB -> !PlayerJobs.line(p).isEmpty() || Shifts.onShift(p) || some(books, PlayerFinance.Cat.WAGES) || some(books, PlayerFinance.Cat.SHIFTS);
            case WAGE -> some(books, PlayerFinance.Cat.WAGES) || some(books, PlayerFinance.Cat.SHIFTS);
            case SAVE -> FolkCredit.balance(village, me) > 0 || some(books, PlayerFinance.Cat.SAVED);
            case BUY -> some(books, PlayerFinance.Cat.PURCHASES) || some(books, PlayerFinance.Cat.FOOD);
            case NEIGHBOUR -> Standing.of(village, me, level.getGameTime()).knownBy() >= 3;
            case BILL -> some(books, PlayerFinance.Cat.TAXES) || some(books, PlayerFinance.Cat.FEES) || some(books, PlayerFinance.Cat.RENT_OUT)
                || some(books, PlayerFinance.Cat.FINES);
            case CITIZEN -> Citizens.is(village, me);
            case HOUSE -> Homes.playerHouses(village, me)[0] > 0;
            case BUSINESS -> !MyBusinesses.mine(village, me).isEmpty() || PlayerShops.of(village, me) != null;
        };
    }

    private static boolean some(Map<PlayerFinance.Cat, Long> m, PlayerFinance.Cat c) {
        Long v = m.get(c);
        return v != null && v != 0;
    }

    /** A bed the player sleeps in within the town (its respawn point at a bed there). */
    private static boolean ownBed(ServerPlayer p, UUID village) {
        BlockPos r = p.getRespawnPosition();
        if (r == null || !p.level().dimension().equals(p.getRespawnDimension())) return false;
        Villages.Village v = Villages.get(village);
        if (v == null || r.distSqr(v.centre()) > (double) Villages.VILLAGE_RANGE * Villages.VILLAGE_RANGE) return false;
        return p.level().getBlockState(r).getBlock() instanceof net.minecraft.world.level.block.BedBlock;
    }

    /** Tick off whatever is done now, paying for each the first time. Returns the steps newly done. */
    public static int check(ServerPlayer p) {
        UUID village = town(p.getUUID());
        if (village == null || Villages.get(village) == null) return 0;
        CompoundTag w = Belonging.week(p.getUUID());
        int doneBits = w.getInt("done"), n = 0;
        for (Step s : Step.values()) {
            int bit = 1 << s.ordinal();
            if ((doneBits & bit) != 0) continue;
            if (s.later && count(p.getUUID()) < firstEight()) continue;       // chapter two waits for the week
            if (!met(p, village, s)) continue;
            doneBits |= bit;
            w.putInt("done", doneBits);
            Belonging.dirty();
            n++;
            reward(p, village, s);
        }
        return n;
    }

    /** The step's small reward and a word on the screen. */
    private static void reward(ServerPlayer p, UUID village, Step s) {
        int paid = s.later ? 0 : Earnings.fromTreasury(p, village, REWARD_COINS, Earnings.Source.QUEST, Villages.name(village), "first week: " + s.label.toLowerCase(java.util.Locale.ROOT));
        int warmed = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (warmed >= 3) break;
            if (a instanceof VillageFolkEntity f && f.persona().knows(p.getUUID())) {
                f.persona().feelFor(p.getUUID(), p.getGameProfile().getName(), REWARD_WARMTH);
                warmed++;
            }
        }
        Standing.stir(village, p.getUUID());
        Step next = next(p.getUUID());
        String line = "First week: " + s.label + ", done." + (paid > 0 ? " +" + paid + " coins from the town." : "")
            + (next == null ? " That's the lot. You live here now." : " Next: " + next.label + ".");
        if (!skipped(p.getUUID())) p.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.AQUA));
    }

    /** Every few seconds, for a player in its first week: what it has done since. */
    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.tickCount % 100 != 53) return;
        if (town(p.getUUID()) == null || next(p.getUUID()) == null) return;
        check(p);
    }

    // ------------------------------------------------------------------ on a page

    /** Where "Show me" points for this step, or null; and what it's called. */
    @Nullable
    static BlockPos where(ServerLevel level, Villages.Village v, Step s, StringBuilder label) {
        UUID id = v.id();
        switch (s) {
            case REGISTER, BILL, CITIZEN -> { label.append(TownHallDesk.showMeLabel(level, v)); return TownHallDesk.showMe(level, v); }
            case ROOM -> {
                BlockPos inn = Villages.builtAt(id, "inn");
                if (inn != null) { label.append("The inn"); return inn; }
                label.append("The village board (houses for sale)");
                return VillageBoards.lectern(id);
            }
            case JOB, WAGE -> {
                BlockPos board = VillageBoards.lectern(id);
                label.append("The village board (the job adverts)");
                return board;
            }
            case SAVE -> {
                java.util.List<ServicePoints.Point> bank = ServicePoints.of(level, id, ServiceRole.BANK);
                if (!bank.isEmpty()) { label.append("The bank's desk"); return bank.get(0).pos(); }
                label.append(TownHallDesk.showMeLabel(level, v) + " (the savings club)");
                return TownHallDesk.showMe(level, v);
            }
            case BUY -> {
                java.util.List<ServicePoints.Point> shop = ServicePoints.of(level, id, ServiceRole.SHOP);
                if (!shop.isEmpty()) { label.append("The shop's counter"); return shop.get(0).pos(); }
                BlockPos market = Villages.builtAt(id, "market");
                label.append(market != null ? "The market" : "The town's heart");
                return market != null ? market : v.centre();
            }
            case NEIGHBOUR -> { label.append("The town's heart"); return v.centre(); }
            case HOUSE -> { label.append("The village board (houses for sale)"); return VillageBoards.lectern(id); }
            case BUSINESS -> { label.append("The village board"); return VillageBoards.lectern(id); }
        }
        return null;
    }

    /** The page action that does a step (TownHall.act), or null. */
    @Nullable
    static String action(Step s) {
        return switch (s) {
            case REGISTER -> "tab:join";
            case ROOM -> "say:Have you a room for the night?";
            case JOB, WAGE -> "life:jobs";
            case SAVE, BILL -> "tab:money";
            case BUY -> "say:What have you got for sale?";
            case NEIGHBOUR -> null;
            case CITIZEN -> "tab:citizen";
            case HOUSE -> "say:Is there a house for sale?";
            case BUSINESS -> "life:start";
        };
    }

    /** The checklist on a page (the Town Hall's Week tab, the arrival card, Your life in town's card). */
    static void rows(ServerPlayer p, LifePage page, @Nullable Villages.Village here, boolean full) {
        UUID me = p.getUUID();
        UUID town = town(me);
        if (town == null) {
            page.text("Your first week starts when you sign a town's book at its Town Hall.", 4);
            return;
        }
        check(p);
        Villages.Village v = Villages.get(town);
        String name = Villages.name(town);
        int n = count(me), all = firstEight();
        page.bar("Your first week in " + name, n / (double) all, n + " of " + all);
        boolean chapterTwo = n >= all;
        for (Step s : Step.values()) {
            if (s.later && !chapterTwo) continue;
            boolean d = done(me, s);
            boolean isNext = s == next(me);
            if (!full && (d || !isNext)) continue;
            LifePage.Row row = page.row(new ItemStack(d ? Items.LIME_DYE : s.icon), (d ? "Done: " : isNext ? "Next: " : "") + s.label, s.why,
                d ? "" : s.later ? "" : "+" + REWARD_COINS + "c");
            if (d) continue;
            String a = action(s);
            if (a != null) row.button(a.startsWith("tab:") ? "Go to it" : a.startsWith("life:") ? "Open" : "Ask a folk", "week", LifePage.args("a", a));
            if (v != null) row.button("Show me", "weekshow", LifePage.args("s", s.name()));
        }
        if (n >= all && next(me) == null) page.text("All done. You know your way round " + name + " now.", 1);
        page.buttons().button(skipped(me) ? "Show the week's word in chat again" : "Hush the week's word in chat", "weekskip", null);
    }

    public static void resetForTests(UUID player) {
        Belonging.me(player).remove("week");
        Belonging.dirty();
    }
}
