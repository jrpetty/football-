package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.QuestBook.Kind;
import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.entity.QuestBook.State;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [quests] What a quest pays, and what a player earns in a town by them.
 *
 * <ul>
 * <li><b>Coin from somewhere real.</b> A favour is paid out of its giver's own purse (and, for a family's, out of
 *     the family's purses), a town's quest out of the treasury, with the elder making up a little out of its own
 *     purse when the treasury is short, as the quest board does. Nobody promises what it has not got: the reward
 *     is set at the offer from what the payer holds, and paid at the end from what it holds then (all it has, if it
 *     has spent some since).</li>
 * <li><b>Goods out of the stores.</b> A town's quest may pay in its stores' goods as well: taken out of them, if
 *     they are still there.</li>
 * <li><b>Warmth.</b> The giver thinks the better of you, and for the town's quests and its stories the whole town
 *     a little; the chronicle tells of it, and the giver remembers it.</li>
 * <li><b>Honours.</b> At a story's end the town strikes its Medal for you; after two stories, or ten quests done
 *     for it, and the town counting you a friend, it gives you the Key to the Town, and a feast in your honour that
 *     night. Both are made by the smith (else the shop's bench) out of the stores' gold: a town short of it owes
 *     you the honour till the gold comes in. They are real things with a real use: carried, the town's folk warm
 *     to you faster (one more a day for the medal, two for the key), and whatever the town sells you is cheaper (a
 *     twentieth off with the medal, a tenth with the key).</li>
 * <li><b>Let down.</b> A quest given up or left past its day: the giver is disappointed (and says so when it
 *     sees you), and for a town's quest or a story the town thinks a little less of you.</li>
 * </ul>
 */
public final class QuestRewards {

    private QuestRewards() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** Stories done, or quests done, in a town before it gives a player its key. */
    static final int KEY_STORIES = 2, KEY_QUESTS = 10;

    // ------------------------------------------------------------------ paying

    /** The reward paid: coin, goods, warmth, the chronicle. What was paid, in words. */
    static String pay(ServerLevel level, Quest q, Player p) {
        if (q.kind == Kind.BOARD) return "";                          // the board pays its own, and tells the chronicle itself
        long day = QuestRun.day(level);
        String name = p.getName().getString();
        List<String> parts = new ArrayList<>();
        Villages.Village v = Villages.get(q.village);
        if (q.coins > 0) {
            int paid = "purse".equals(q.payer) ? fromPurses(level, q) : fromTreasury(level, q.village, q.coins);
            q.flags.put("paid", Integer.toString(paid));
            if (paid > 0) {
                Dealings.giveCoins(p, paid);
                parts.add(paid + (paid == 1 ? " coin" : " coins") + (paid < q.coins ? " (all that could be found)" : ""));
            }
        }
        if (!q.goods.isEmpty() && v != null) {
            String[] g = q.goods.split("\\*");
            ResourceLocation id = g.length == 2 ? ResourceLocation.tryParse(g[0]) : null;
            Item it = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            int n = g.length == 2 ? num(g[1]) : 0;
            if (it != Items.AIR && n > 0) {
                int have = Math.min(n, Market.stock(level, q.village, s -> s.is(it)));
                if (have > 0 && TownWork.take(level, v, s -> s.is(it), have)) {
                    QuestItems.give(p, new ItemStack(it, have));
                    parts.add(Bench.words(it, have));
                }
            }
        }
        // Warmth: the giver; the town, for the town's own quests and its stories.
        VillageFolkEntity giver = Civics.find(level, q.giver);
        if (giver != null) {
            giver.persona().feelFor(p.getUUID(), name, q.warmth);
            giver.persona().remember(day, name + " " + q.flags.getOrDefault("memory", "helped me: " + QuestRun.lower(q.title)), 5);
        }
        if (q.kind != Kind.FAVOUR) warmTown(q.village, p, q.story() ? 3 : 2);
        Standing.stir(q.village, p.getUUID());
        p.giveExperiencePoints(Math.min(60, 8 + q.coins / 2 + (q.story() ? 20 : 0)));
        String line = q.flags.getOrDefault("chronicle", name + " " + (q.kind == Kind.FAVOUR ? "did " + q.giverName + " a good turn: " : "answered the town's call: ")
            + QuestRun.lower(q.title));
        Villages.tell(q.village, day, line);
        return String.join(", ", parts);
    }

    /** The giver's purse, and any other payer's in the quest's "payers" (a family's): what was paid. */
    static int fromPurses(ServerLevel level, Quest q) {
        List<UUID> payers = new ArrayList<>();
        payers.add(q.giver);
        for (String s : q.flag("payers").split(",")) {
            try {
                if (!s.isEmpty()) payers.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // not an id
            }
        }
        int left = q.coins, paid = 0;
        for (UUID u : payers) {
            VillageFolkEntity f = Civics.find(level, u);
            if (f == null || left <= 0) continue;
            int k = Math.min(left, f.purse());
            if (k > 0 && f.spend(k)) {
                paid += k;
                left -= k;
            }
        }
        return paid;
    }

    /** Out of the treasury, the elder making up what it can of a shortfall out of its own purse (as the board's). */
    static int fromTreasury(ServerLevel level, UUID village, int coins) {
        int paid = Ledger.takeCoins(village, coins);
        if (paid < coins) {
            UUID elder = Villages.elder(village);
            VillageFolkEntity e = elder == null ? null : Civics.find(level, elder);
            if (e != null) {
                int own = Math.min(e.purse(), Math.min(coins - paid, Math.max(2, coins / 2)));
                if (own > 0 && e.spend(own)) paid += own;
            }
        }
        if (paid > 0) Economy.spent(village, paid);
        return paid;
    }

    /** What a payer can promise: what it holds, up to what the work is worth. */
    static int afford(@Nullable VillageFolkEntity payer, int worth) {
        return payer == null ? 0 : Math.max(0, Math.min(worth, payer.purse()));
    }

    static int affordTreasury(UUID village, int worth) {
        return Math.max(0, Math.min(worth, Ledger.coins(village)));
    }

    /** The whole town a little warmer (or cooler) toward a player. */
    static void warmTown(UUID village, Player p, int by) {
        String name = p.getName().getString();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isShowcase()) f.persona().feelFor(p.getUUID(), name, by);
        }
        Standing.stir(village, p.getUUID());
    }

    /** Given up, or left past its day: the giver let down; the town too, for its own quests and its stories. */
    static void letDown(ServerLevel level, Quest q, int by) {
        if (q.player == null) return;
        long day = QuestRun.day(level);
        VillageFolkEntity giver = Civics.find(level, q.giver);
        if (giver != null) {
            giver.persona().feelFor(q.player, q.playerName, -by);
            giver.persona().remember(day, q.playerName + " said they'd help me, and didn't", 2);
        }
        if (q.kind != Kind.FAVOUR && q.kind != Kind.BOARD) {
            for (AssistantEntity a : Villages.folkOf(q.village)) {
                if (a instanceof VillageFolkEntity f && !f.isShowcase() && f.persona().knows(q.player)) {
                    f.persona().feelFor(q.player, q.playerName, -(q.story() ? 3 : 2));
                }
            }
        }
        Standing.stir(q.village, q.player);
    }

    public static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ the first journal

    /** A player's first quest from a town: a journal for it, out of the stores (one the shop made, or made now). */
    static void firstJournal(ServerLevel level, VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v != null) firstJournal(level, v, f, p, f.displayNameCap());
    }

    /** As firstJournal, for a posting taken off the board: the elder's gift, out of the stores. */
    static void firstJournalFromBoard(ServerLevel level, Villages.Village v, Player p) {
        firstJournal(level, v, null, p, "The town");
    }

    private static void firstJournal(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f, Player p, String who) {
        UUID village = v.id();
        Item journal = McAssistantMod.QUEST_JOURNAL.get();
        if (QuestItems.count(p, s -> s.is(journal)) > 0) return;
        String key = "questjournal/" + p.getUUID();
        String given = Ledger.note(village, key);
        if (given != null && !given.isEmpty()) return;
        ItemStack book = TownWork.take(level, v, s -> s.is(journal), 1) ? new ItemStack(journal) : QuestItems.make(level, v, f, journal);
        if (book.isEmpty()) return;
        Ledger.note(village, key, Long.toString(QuestRun.day(level)));
        QuestItems.give(p, book);
        QuestRun.tell(p, Component.literal(who + " gives you a Quest Journal out of the stores: use it to see your quests.")
            .withStyle(ChatFormatting.GOLD));
    }

    // ------------------------------------------------------------------ honours

    /** A story done: the town's medal. Enough done: its key. */
    static void honours(ServerLevel level, Quest q, Player p) {
        if (q.player == null) return;
        UUID village = q.village;
        if (q.story() && !QuestBook.honoured(q.player, village, "medal") && !QuestBook.owed(q.player, village, "medal")) {
            award(level, village, p, "medal", q.title);
        }
        if (QuestBook.honoured(q.player, village, "key") || QuestBook.owed(q.player, village, "key")) return;
        int stories = 0;
        for (Quest o : QuestBook.of(q.player)) if (o.story() && o.state == State.DONE && village.equals(o.village)) stories++;
        Standing.Title t = Standing.of(village, q.player, level.getGameTime()).title();
        if ((stories >= KEY_STORIES || QuestBook.doneIn(q.player, village) >= KEY_QUESTS) && t.atLeast(Standing.Title.FRIEND)) {
            award(level, village, p, "key", stories >= KEY_STORIES ? stories + " stories" : QuestBook.doneIn(q.player, village) + " quests");
        }
    }

    /** The smith strikes it out of the stores' gold and the town gives it; a town short of the gold owes it. */
    static boolean award(ServerLevel level, UUID village, Player p, String what, String why) {
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        long day = QuestRun.day(level);
        String town = Villages.name(village), name = p.getName().getString();
        Item item = what.equals("key") ? McAssistantMod.TOWN_KEY.get() : McAssistantMod.TOWN_MEDAL.get();
        ItemStack made = QuestItems.make(level, v, maker(village), item);
        if (made.isEmpty()) {
            if (!QuestBook.owed(p.getUUID(), village, what)) {
                QuestBook.owe(p.getUUID(), village, what, true);
                Villages.tell(village, day, "the town voted " + name + " its " + (what.equals("key") ? "key" : "medal")
                    + "; it will be struck when the stores have the gold for it");
                QuestRun.tell(p, Component.literal(town + " has voted you its " + (what.equals("key") ? "Key to the Town" : "Medal")
                    + ". The smith will make it when the stores have the makings: " + QuestItems.shortFor(level, v, maker(village), item) + ".")
                    .withStyle(ChatFormatting.GOLD));
            }
            return false;
        }
        String title = what.equals("key") ? "Key to " + town : "Medal of " + town;
        QuestItems.stamp(made, 0, title, "Given to " + name + " by the folk of " + town + ", day " + (day + 1),
            what.equals("key") ? "The freedom of the town: its folk trust you, and its stores sell to you a tenth cheaper"
                : "For " + why + ": the town's folk think the better of you, and its stores sell a twentieth cheaper");
        QuestItems.mark(made, QuestItems.TOWN, village.toString());
        QuestItems.mark(made, QuestItems.HONOUR, p.getUUID().toString());
        made.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        QuestItems.give(p, made);
        QuestBook.honour(p.getUUID(), village, what);
        QuestBook.owe(p.getUUID(), village, what, false);
        if (what.equals("key")) {
            QuestBook.title(p.getUUID(), village, "Freeman");
            Gatherings.honour(village, name, day);
            Villages.tell(village, day, name + " was given the Key to " + town + ", and the town held a feast in their honour");
        } else {
            Villages.tell(village, day, name + " was given the Medal of " + town + " for " + QuestRun.lower(why));
        }
        QuestRun.tell(p, Component.literal("You are given the " + title + ".").withStyle(ChatFormatting.GOLD));
        LOG.info("[MCA-QUESTS] {} given the {} ({})", name, title, why);
        return true;
    }

    /** For the game tests: strike and give an honour straight away. */
    public static boolean awardForTests(ServerLevel level, UUID village, Player p, String what) {
        return award(level, village, p, what, "the tests");
    }

    /** Who strikes the town's honours: its smith, else the shop's keeper, else the elder. */
    @Nullable
    static VillageFolkEntity maker(UUID village) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == AssistantEntity.StationTask.SMITH && !f.isBaby()) return f;
        }
        VillageFolkEntity keeper = Workshop.keeper(village);
        if (keeper != null) return keeper;
        UUID elder = Villages.elder(village);
        for (AssistantEntity a : Villages.folkOf(village)) if (a.getUUID().equals(elder) && a instanceof VillageFolkEntity f) return f;
        return null;
    }

    /** The town's look (QuestRun): honours owed made when the gold has come in; secrets that come out. */
    static void look(ServerLevel level, Villages.Village v, long day) {
        for (Map.Entry<String, String> e : QuestBook.allOwed().entrySet()) {
            String[] k = e.getKey().split("/");
            if (k.length != 2 || !k[1].equals(v.id().toString())) continue;
            ServerPlayer p;
            try {
                p = level.getServer().getPlayerList().getPlayer(UUID.fromString(k[0]));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (p == null) continue;
            for (String what : e.getValue().split(",")) if (!what.isEmpty()) award(level, v.id(), p, what, "what they did for the town");
        }
        QuestStories.secrets(level, v, day);
    }

    // ------------------------------------------------------------------ what the honours do

    /** [FolkTalk] A day's warmth the more, for the town's medal or key carried: the folk trust it. */
    public static int warmth(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return 0;
        if (QuestBook.honoured(p.getUUID(), village, "key") && QuestItems.carries(p, village, McAssistantMod.TOWN_KEY.get())) return 2;
        if (QuestBook.honoured(p.getUUID(), village, "medal") && QuestItems.carries(p, village, McAssistantMod.TOWN_MEDAL.get())) return 1;
        return 0;
    }

    /** [Dealings.haggled] What the town sells a player, the cheaper for its key (a tenth) or its medal (a twentieth), carried. */
    public static int discount(UUID village, UUID player, int price) {
        if (price <= 1) return price;
        int off = 0;
        try {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
            if (p == null) return price;
            if (QuestBook.honoured(player, village, "key") && QuestItems.carries(p, village, McAssistantMod.TOWN_KEY.get())) off = 10;
            else if (QuestBook.honoured(player, village, "medal") && QuestItems.carries(p, village, McAssistantMod.TOWN_MEDAL.get())) off = 5;
        } catch (RuntimeException e) {
            return price;
        }
        return off == 0 ? price : Math.max(1, price - Math.max(1, (int) Math.round(price * off / 100.0)));
    }

    /** As discount, for a player in hand (the tests). */
    public static int discountFor(UUID village, Player p, int price) {
        int off = QuestBook.honoured(p.getUUID(), village, "key") && QuestItems.carries(p, village, McAssistantMod.TOWN_KEY.get()) ? 10
            : QuestBook.honoured(p.getUUID(), village, "medal") && QuestItems.carries(p, village, McAssistantMod.TOWN_MEDAL.get()) ? 5 : 0;
        return off == 0 || price <= 1 ? price : Math.max(1, price - Math.max(1, (int) Math.round(price * off / 100.0)));
    }

    /** "Thief-taker of Oakford, Medal of Oakford": what a player is to this town, for the talk screen's header. */
    public static String titleLine(UUID village, UUID player) {
        List<String> t = new ArrayList<>(QuestBook.titles(player, village));
        if (QuestBook.honoured(player, village, "key")) t.add("Key to the Town");
        else if (QuestBook.honoured(player, village, "medal")) t.add("the town's Medal");
        return String.join(", ", t);
    }
}
