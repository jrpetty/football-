package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.QuestBook.Kind;
import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.entity.QuestBook.State;
import com.jrpetty.mcassistant.entity.QuestBook.Step;
import com.jrpetty.mcassistant.entity.QuestBook.StepType;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [quests] The Stolen Heirloom: a family's ring or locket, gone from its chest.
 *
 * <ol>
 * <li><b>Gone.</b> A family of the town (a home with two or more grown in it) has an heirloom: a ring or a locket,
 *     the smith's work out of the stores' gold, paid for out of the family's own purses into the treasury long ago
 *     as the story has it, and kept in the family's chest (else its eldest's pack). It is taken, really taken, by
 *     one of three: a jealous neighbour (into its own pack or chest), the family's own grown child, who pawned it at
 *     the store for coin (it is in the stores, and the treasury paid the child), or a pedlar who sold it on in the
 *     next town (it is in that town's stores).</li>
 * <li><b>The chest.</b> Look it over: the latch forced from outside, or not forced at all, or a pedlar's ribbon.</li>
 * <li><b>The suspects.</b> The neighbour, the child, and, when there is a next town, the innkeeper's word about the
 *     pedlar: each says where it was, and the one who did it gives itself away.</li>
 * <li><b>Who took it?</b> Accuse one, before the family. Wrongly, and it lasts: the accused and the family fall out,
 *     the accused does not forgive you, and you must think again.</li>
 * <li><b>Getting it back.</b> The neighbour: ask for it quietly (it gives it back, ashamed, and the families mend a
 *     little), or shame it before the town. The child: buy it back from the store yourself and keep its secret, make
 *     it buy it back out of its own purse, or tell the family (who buy it back, and are cold to it a while). The
 *     pedlar's town: buy it back from its stores, or demand it as stolen goods (it gives it up if the towns are on
 *     good terms, else you buy it).</li>
 * <li><b>The reunion.</b> Carry it home; it goes back into the family's chest.</li>
 * </ol>
 * The family's gratitude (coin from their purses), the chronicle (naming the thief, or nobody, as you chose), and the
 * ties between them all changed for good.
 */
final class StoryHeirloom implements QuestStories.Story {

    static final StoryHeirloom STORY = new StoryHeirloom();

    @Override
    public String key() {
        return "heirloom";
    }

    // ------------------------------------------------------------------ the theft

    @Override
    @Nullable
    public Quest begin(ServerLevel level, Villages.Village v, long day, Map<String, Object> given, boolean forced) {
        UUID id = v.id();
        VillageFolkEntity giver = QuestStories.folk(given, "giver");
        VillageFolkEntity child = QuestStories.folk(given, "child"), neighbour = QuestStories.folk(given, "neighbour");
        List<VillageFolkEntity> family = new ArrayList<>();
        Homes.Home home = null;
        if (giver == null) {
            // A family: a home with two or more grown in it, and a parent among them.
            for (Homes.Home h : Homes.homes(id).values()) {
                List<VillageFolkEntity> grown = new ArrayList<>();
                for (VillageFolkEntity f : Homes.loadedMembers(id, h)) if (!f.isBaby() && !f.isShowcase()) grown.add(f);
                if (grown.size() < 2) continue;
                VillageFolkEntity head = null;
                for (VillageFolkEntity f : grown) {
                    boolean parent = false;
                    for (VillageFolkEntity o : grown) parent |= o.parentIds().contains(f.getUUID());
                    if (parent && QuestMaker.free(f) && !QuestStories.cast(f.getUUID())) { head = f; break; }
                }
                if (head == null) for (VillageFolkEntity f : grown) if (QuestMaker.free(f) && !QuestStories.cast(f.getUUID())) { head = f; break; }
                if (head == null) continue;
                giver = head;
                home = h;
                family.addAll(grown);
                break;
            }
        } else {
            family.add(giver);
            if (child != null) family.add(child);
            home = Homes.homeOf(id, giver.getUUID());
        }
        if (!QuestMaker.free(giver)) {
            QuestStories.why(id, "no family settled enough to own an heirloom");
            return null;
        }
        if (child == null) {
            for (VillageFolkEntity f : family) if (f != giver && f.parentIds().contains(giver.getUUID()) && !QuestStories.cast(f.getUUID())) child = f;
        }
        if (neighbour == null) {
            // Somebody who has it in for one of them; else whoever lives nearest.
            double near = Double.MAX_VALUE;
            for (VillageFolkEntity f : Civics.grown(id)) {
                if (family.contains(f) || f == child || QuestStories.cast(f.getUUID()) || !QuestMaker.free(f)) continue;
                boolean rival = false;
                for (VillageFolkEntity m : family) rival |= f.life().affinity(m.getUUID()) <= Social.RIVAL || m.life().affinity(f.getUUID()) <= Social.RIVAL;
                double d = f.distanceToSqr(giver) - (rival ? 1_000_000 : 0);
                if (d < near) { near = d; neighbour = f; }
            }
        }
        Villages.Village nextTown = null;
        for (Villages.Village o : Diplomacy.neighboursOf(id)) {
            if (!Wars.atWar(id, o.id()) && level.isLoaded(o.centre()) && !Villages.storeChests(level, o.id()).isEmpty()) { nextTown = o; break; }
        }
        if (QuestStories.given(given, "pedlar") instanceof Villages.Village o) nextTown = o;
        int suspects = (neighbour != null ? 1 : 0) + (child != null ? 1 : 0) + (nextTown != null ? 1 : 0);
        if (suspects < 2) {
            QuestStories.why(id, "not suspects enough for a mystery");
            return null;
        }
        // Who did it.
        String truth = QuestStories.text(given, "truth");
        if (truth.isEmpty()) {
            List<String> could = new ArrayList<>();
            if (neighbour != null) { could.add("neighbour"); could.add("neighbour"); }
            if (child != null && child.purse() < 25) { could.add("child"); could.add("child"); }
            if (nextTown != null) could.add("pedlar");
            if (could.isEmpty()) {
                QuestStories.why(id, "nobody who would take it");
                return null;
            }
            truth = could.get(level.getRandom().nextInt(could.size()));
        }
        // The heirloom: the smith's work out of the stores' gold, paid for out of the family's purses.
        boolean ring = (giver.getUUID().getLeastSignificantBits() & 1L) == 0L;
        Item kind = ring ? McAssistantMod.HEIRLOOM_RING.get() : McAssistantMod.HEIRLOOM_LOCKET.get();
        String word = ring ? "ring" : "locket";
        if (!QuestItems.shortFor(level, v, QuestRewards.maker(id), kind).isEmpty()) {
            Item other = ring ? McAssistantMod.HEIRLOOM_LOCKET.get() : McAssistantMod.HEIRLOOM_RING.get();
            if (!QuestItems.shortFor(level, v, QuestRewards.maker(id), other).isEmpty()) {
                QuestStories.why(id, "not the gold in the stores for an heirloom: " + QuestItems.shortFor(level, v, QuestRewards.maker(id), kind));
                return null;
            }
            kind = other;
            ring = !ring;
            word = ring ? "ring" : "locket";
        }
        int worth = Math.max(4, (int) Math.round(Prices.each(kind)));
        int purses = 0;
        for (VillageFolkEntity f : family) purses += f.purse();
        if (purses < worth / 2 && !forced) {
            QuestStories.why(id, "the family could never have afforded an heirloom");
            return null;
        }
        String gn = giver.displayNameCap();
        String owner = ring ? "Gran's ring" : "Mother's locket";
        Quest q = QuestMaker.newQuest("story.heirloom", Kind.STORY, v, giver, "The Stolen Heirloom",
            QuestTalk.voice(giver,
                owner + " is gone! It's been in the family longer than anybody can remember — it was in our chest last night, and this morning it's not. Will you find out who took it?",
                "Somebody's taken " + owner + ". Out of our own chest. Find out who.",
                "It's — " + owner + "… it's gone. Out of the chest. I don't know who would… would you help?",
                "Somebody's pinched " + owner + "! Out of our chest! Will you find the rotter for us?"));
        q.days = 5;
        q.warmth = 20;
        q.flags.put("standing", Standing.Title.VISITOR.name());
        QuestStories.cast(q, "giver", giver);
        if (child != null) QuestStories.cast(q, "child", child);
        if (neighbour != null) QuestStories.cast(q, "neighbour", neighbour);
        if (nextTown != null) {
            q.flags.put("town", nextTown.id().toString());
            UUID theirElder = Villages.elder(nextTown.id());
            if (theirElder != null) q.flags.put("townElder", theirElder.toString());
        }
        q.flags.put("truth", truth);
        q.flags.put("word", word);
        q.flags.put("owner", owner);
        q.flags.put("worth", Integer.toString(worth));
        List<String> payers = new ArrayList<>();
        for (VillageFolkEntity f : family) if (f != giver) payers.add(f.getStringUUID());
        q.flags.put("payers", String.join(",", payers));
        q.payer = "purse";
        int coins = 0;
        for (VillageFolkEntity f : family) coins += QuestRewards.afford(f, 8);
        q.coins = Math.min(20, coins);
        BlockPos chest = home == null ? null : Homes.chestOf(level, id, home);
        BlockPos spot = chest != null ? chest : home != null ? home.anchor : giver.blockPosition();
        QuestStories.place(q, "chest", spot);
        q.flags.put("rumour", "Have you heard? Somebody's been at " + gn + "'s family chest. " + owner + ", gone!");
        // Made (the smith's work, out of the stores' gold), and paid for: the family's purses into the treasury.
        ItemStack heirloom = QuestItems.make(level, v, QuestRewards.maker(id), kind);
        if (heirloom.isEmpty()) {
            QuestStories.why(id, "the heirloom could not be made");
            return null;
        }
        if (!QuestRun.offer(level, q)) {
            Crafts.store(level, v, heirloom);
            QuestStories.why(id, gn + " is asking about something else");
            return null;
        }
        int paid = 0;
        for (VillageFolkEntity f : family) {
            int k = Math.min(f.purse(), Math.max(0, worth - paid));
            if (k > 0 && f.spend(k)) paid += k;
        }
        if (paid > 0) Ledger.addCoins(id, paid);
        QuestItems.stamp(heirloom, q.id, owner, "The " + gn + " family's " + word + ", handed down", "longer than anybody can remember.");
        QuestItems.mark(heirloom, "family", giver.getStringUUID());
        // The theft.
        String hidden;
        switch (truth) {
            case "child" -> {
                // Pawned at the store: the heirloom in the stores, the treasury's coin in the child's purse.
                ItemStack left = Market.intoStores(level, id, heirloom);
                if (!left.isEmpty()) QuestItems.toFolk(child, left);
                int pawn = Math.min(Ledger.coins(id), Math.max(2, worth / 2));
                if (pawn > 0) {
                    Ledger.takeCoins(id, pawn);
                    child.earn(pawn);
                }
                q.flags.put("pawn", Integer.toString(pawn));
                hidden = "the stores (pawned by " + child.displayNameCap() + ")";
            }
            case "pedlar" -> {
                ItemStack left = Market.intoStores(level, nextTown.id(), heirloom);
                if (!left.isEmpty() && neighbour != null) QuestItems.toFolk(neighbour, left);
                hidden = Villages.name(nextTown.id()) + "'s stores (sold on by a pedlar)";
            }
            default -> {
                Homes.Home theirs = Homes.homeOf(id, neighbour.getUUID());
                BlockPos theirChest = theirs == null ? null : Homes.chestOf(level, id, theirs);
                if (theirChest == null || !QuestItems.intoChest(level, theirChest, heirloom)) QuestItems.toFolk(neighbour, heirloom);
                else QuestStories.place(q, "stash", theirChest);
                hidden = neighbour.displayNameCap() + "'s " + (theirChest == null ? "pack" : "chest");
            }
        }
        q.flags.put("hidden", hidden);
        Villages.tell(id, day, gn + "'s family found " + owner + " gone from their chest");
        com.mojang.logging.LogUtils.getLogger().info("[MCA-QUESTS] the {} heirloom ({}) is in {}", gn, word, hidden);
        return q;
    }

    // ------------------------------------------------------------------ the clues

    @Override
    public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
        q.steps.add(new Step(StepType.GO, "chest", "Look over the family's chest").chapter("The chest").at(QuestStories.place(q, "chest"), 3));
        return QuestTalk.voice(giver, "Thank you. Come and see the chest — maybe you'll see something we didn't.",
            "Come and look at the chest, then.", "Th-thank you. The chest is just in here…", "Thank you! Come and look — maybe there's a clue!");
    }

    @Override
    public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
        String truth = q.flag("truth"), word = q.flag("word");
        String cn = QuestStories.name(q, "child"), nn = QuestStories.name(q, "neighbour"), gn = QuestStories.name(q, "giver");
        switch (s.key) {
            case "chest" -> {
                s.note = switch (truth) {
                    case "child" -> "The latch isn't forced. Whoever took it had the key, or lived here.";
                    case "pedlar" -> "A scrap of bright ribbon caught on the latch: the kind a pedlar sells.";
                    default -> "Muddy prints by the chest, and the shutter forced from outside.";
                };
                suspects(q);
                return "";
            }
            case "ask_neighbour" -> {
                boolean did = truth.equals("neighbour");
                s.note = nn + (did ? " says it was at home all night — and asked what was missing before you'd said." : " was at the tavern till late; plenty saw it there.");
                next(q);
                return did ? "Me? I was here, at home, all night. Why — has something gone missing? Is it the " + word + "?"
                    : "Last night? At the tavern till they threw us out. Ask anybody. Why, what's happened?";
            }
            case "ask_child" -> {
                boolean did = truth.equals("child");
                s.note = cn + (did ? " says it was at the shop — just looking — and won't meet your eye." : " was asleep, and is as upset as anybody.");
                next(q);
                return did ? "I — I was at the shop. Just looking. I didn't buy anything. Why are you asking me?"
                    : "Asleep! Where else? I can't believe somebody would — that " + word + " is the only thing we've got of Gran's.";
            }
            case "ask_inn" -> {
                boolean did = truth.equals("pedlar");
                String town = Villages.name(UUID.fromString(q.flag("town")));
                s.note = did ? "A pedlar stayed, and left for " + town + " at first light, pack rattling." : "A pedlar stayed, but left with an empty pack.";
                next(q);
                return did ? "A pedlar, aye — stayed the night, and was off to " + town + " at first light. Pack rattling, it was. Pleased with itself."
                    : "A pedlar stayed, aye. Sold nothing, bought nothing, left with an empty pack and a sore head.";
            }
            case "town" -> {
                // In the pedlar's town: whoever speaks for its stores (its elder).
                UUID town = UUID.fromString(q.flag("town"));
                UUID who = q.flagId("townElder");
                if (who == null) {
                    VillageFolkEntity keeper = Storekeeping.keeper(town);
                    who = keeper == null ? null : keeper.getUUID();
                }
                String whoName = who == null ? "" : Villages.elderName(town);
                VillageFolkEntity there = who == null ? null : Civics.find(level, who);
                if (there != null) whoName = there.displayNameCap();
                s.note = "It's in " + Villages.name(town) + "'s stores, bought off the pedlar.";
                q.steps.add(new Step(StepType.CHOOSE, "town_back", "Ask " + whoName + " of " + Villages.name(town) + " for the " + word).chapter("Getting it back")
                    .who(who, whoName).option("buy", "Buy it back").option("demand", "Demand it as stolen goods"));
                return "";
            }
            case "home" -> {
                return reunion(level, q, f, p);
            }
            default -> {
                return "";
            }
        }
    }

    /** The suspects to question, one step each, in order. */
    private static void suspects(Quest q) {
        List<String> ask = new ArrayList<>();
        if (!q.flag("cast.neighbour").isEmpty()) ask.add("neighbour");
        if (!q.flag("cast.child").isEmpty()) ask.add("child");
        if (!q.flag("town").isEmpty()) ask.add("inn");
        q.flags.put("toAsk", String.join(",", ask));
        next(q);
    }

    /** The next suspect to question, or (all asked) the accusation. */
    private static void next(Quest q) {
        List<String> left = new ArrayList<>(List.of(q.flag("toAsk").split(",")));
        left.removeIf(String::isEmpty);
        if (!left.isEmpty()) {
            String who = left.remove(0);
            q.flags.put("toAsk", String.join(",", left));
            switch (who) {
                case "neighbour" -> q.steps.add(new Step(StepType.TALK, "ask_neighbour", "Ask " + QuestStories.name(q, "neighbour") + " where it was last night")
                    .chapter("The suspects").who(QuestStories.roleId(q, "neighbour"), QuestStories.name(q, "neighbour")));
                case "child" -> q.steps.add(new Step(StepType.TALK, "ask_child", "Ask " + QuestStories.name(q, "child") + " where it was last night")
                    .chapter("The suspects").who(QuestStories.roleId(q, "child"), QuestStories.name(q, "child")));
                default -> q.steps.add(new Step(StepType.TALK, "ask_inn", "Ask " + QuestStories.name(q, "giver") + " about the pedlar who stayed")
                    .chapter("The suspects").who(QuestStories.roleId(q, "giver"), QuestStories.name(q, "giver")));
            }
            return;
        }
        accuse(q, "Who took it?");
    }

    private static void accuse(Quest q, String text) {
        Step s = new Step(StepType.CHOOSE, "accuse" + q.steps.size(), text).chapter("Who took it?").who(QuestStories.roleId(q, "giver"), QuestStories.name(q, "giver"));
        String wrong = q.flag("wrong");
        if (!q.flag("cast.neighbour").isEmpty() && !wrong.contains("neighbour")) s.option("neighbour", QuestStories.name(q, "neighbour"));
        if (!q.flag("cast.child").isEmpty() && !wrong.contains("child")) s.option("child", QuestStories.name(q, "child"));
        if (!q.flag("town").isEmpty() && !wrong.contains("pedlar")) s.option("pedlar", "The pedlar");
        q.steps.add(s);
    }

    @Override
    @Nullable
    public String waiting(ServerLevel level, Quest q, Step s, VillageFolkEntity f, Player p) {
        if (s.key.startsWith("accuse")) return "Well? Who was it? Say it to my face.";
        if (s.key.equals("neighbour_back")) return "What do you want? … You know, don't you.";
        if (s.key.equals("child_back")) return "Please… please don't tell them. I'll put it right. I will.";
        if (s.key.equals("town_back")) return "An heirloom of " + Villages.name(q.village) + "'s, in our stores? Well, it was bought fair. What would you have us do?";
        return null;
    }

    @Override
    public String chose(ServerLevel level, Quest q, Step s, String option, VillageFolkEntity f, Player p) {
        String truth = q.flag("truth"), word = q.flag("word");
        String name = p.getName().getString();
        long day = QuestRun.day(level);
        String gn = QuestStories.name(q, "giver");
        if (s.key.startsWith("accuse")) {
            if (!option.equals(truth)) return wrongly(level, q, option, p);
            q.flags.put("blamed", option);
            switch (option) {
                case "neighbour" -> {
                    String nn = QuestStories.name(q, "neighbour");
                    q.steps.add(new Step(StepType.CHOOSE, "neighbour_back", "Get the " + word + " back from " + nn).chapter("Getting it back")
                        .who(QuestStories.roleId(q, "neighbour"), nn).option("quiet", "Ask for it quietly").option("expose", "Shame " + nn + " before the town"));
                    return nn + "? I might have known. Go on, then — get it back.";
                }
                case "child" -> {
                    String cn = QuestStories.name(q, "child");
                    q.steps.add(new Step(StepType.CHOOSE, "child_back", cn + " confesses: it pawned the " + word + " at the store").chapter("Getting it back")
                        .who(QuestStories.roleId(q, "child"), cn).option("buy", "Buy it back yourself, and keep quiet")
                        .option("repay", "Make " + cn + " buy it back").option("tell", "Tell the family"));
                    return cn + "? Our own " + cn + "? No… no, you're wrong. … Go and ask " + cn + ". I can't.";
                }
                default -> {
                    String town = Villages.name(UUID.fromString(q.flag("town")));
                    Villages.Village there = Villages.get(UUID.fromString(q.flag("town")));
                    q.steps.add(new Step(StepType.GO, "town", "Go to " + town + ", where the pedlar sold it").chapter("Getting it back")
                        .at(there == null ? QuestStories.place(q, "chest") : there.centre(), 48));
                    return "The pedlar! Sold it on in " + town + ", I'll be bound. Will you go after it?";
                }
            }
        }
        Villages.Village v = Villages.get(q.village);
        Predicate<ItemStack> heirloom = QuestItems.matcher("token:" + (word.equals("ring") ? "heirloom_ring" : "heirloom_locket"), q.id);
        int worth = QuestRewards.num(q.flag("worth"));
        switch (s.key) {
            case "neighbour_back" -> {
                VillageFolkEntity nb = QuestStories.role(level, q, "neighbour");
                String nn = QuestStories.name(q, "neighbour");
                ItemStack got = recover(level, q, nb, heirloom);
                if (got.isEmpty()) return "I — I haven't got it any more. I swear.";
                QuestItems.give(p, got);
                q.flags.put("recovered", option);
                homeStep(q);
                if (option.equals("quiet")) {
                    if (nb != null) {
                        nb.persona().feelFor(p.getUUID(), name, 10);
                        nb.persona().remember(day, name + " found I'd taken the " + gn + " " + word + ", and let me give it back quietly", 6);
                        VillageFolkEntity g = QuestStories.role(level, q, "giver");
                        if (g != null) {
                            nb.life().feel(g.getUUID(), gn, 10);
                            g.life().feel(nb.getUUID(), nn, 5);
                        }
                    }
                    return "Here. Take it. I don't know what came over me — we were never friends, but… Tell them I'm sorry. Or don't. Thank you for doing it quietly.";
                }
                disgrace(level, q, nb, -15, -30);
                if (nb != null) {
                    nb.persona().feelFor(p.getUUID(), name, -40);
                    nb.persona().remember(day, name + " shamed me before the whole town", 7);
                }
                return "Fine! FINE. Take it. Tell the whole town, why don't you. You've done that already.";
            }
            case "child_back" -> {
                VillageFolkEntity child = QuestStories.role(level, q, "child");
                String cn = QuestStories.name(q, "child");
                int price = Math.max(1, (int) Math.round(worth * Budget.PLAYER_MARKUP));
                switch (option) {
                    case "buy" -> {
                        if (Market.coinsHeld(p) < price) {
                            again(q, s);
                            return "It'd be " + price + " coins at the store. You've " + Market.coinsHeld(p) + ". Come back when you have.";
                        }
                        if (v == null || !moveOutOfStores(level, q, v, heirloom, p)) return "It's not in the stores any more…";
                        Market.payOut(p, price);
                        com.jrpetty.mcassistant.village.Ledger.addCoins(q.village, price);
                        homeStep(q);
                        if (child != null) {
                            child.persona().feelFor(p.getUUID(), name, 30);
                            child.persona().remember(day, name + " bought back the " + word + " I pawned, and never told", 8);
                        }
                        q.flags.put("recovered", "bought");
                        return "You bought it back? For me? And you won't tell? I'll pay you back. Every coin. I swear it.";
                    }
                    case "repay" -> {
                        int pawn = QuestRewards.num(q.flag("pawn"));
                        int own = child == null ? 0 : Math.min(child.purse(), Math.max(pawn, price / 2));
                        if (child != null && own > 0 && child.spend(own)) com.jrpetty.mcassistant.village.Ledger.addCoins(q.village, own);
                        if (v == null || !moveOutOfStores(level, q, v, heirloom, p)) return "It's… it's not in the stores any more!";
                        homeStep(q);
                        if (child != null) {
                            child.persona().feelFor(p.getUUID(), name, 5);
                            child.persona().remember(day, "I had to buy back the " + word + " I pawned, out of my own purse. I'll not do that again", 7);
                        }
                        q.flags.put("recovered", "repaid");
                        return "All right. All right! Out of my own purse — there. Here's the " + word + ". You'll not tell them?";
                    }
                    default -> {
                        int paid = 0;
                        for (String u : (QuestStories.roleId(q, "giver") + "," + q.flag("payers")).split(",")) {
                            VillageFolkEntity g = u.isEmpty() ? null : Civics.find(level, UUID.fromString(u));
                            if (g == null || g == child || paid >= price) continue;
                            int k = Math.min(price - paid, g.purse());
                            if (k > 0 && g.spend(k)) paid += k;
                        }
                        if (paid > 0) com.jrpetty.mcassistant.village.Ledger.addCoins(q.village, paid);
                        if (v == null || !moveOutOfStores(level, q, v, heirloom, p)) return "It's not in the stores any more!";
                        homeStep(q);
                        if (child != null) {
                            child.persona().feelFor(p.getUUID(), name, -10);
                            child.persona().remember(day, "the family found out I pawned the " + word + "; " + name + " told them", 7);
                            for (String u : (QuestStories.roleId(q, "giver") + "," + q.flag("payers")).split(",")) {
                                VillageFolkEntity g = u.isEmpty() ? null : Civics.find(level, UUID.fromString(u));
                                if (g != null && g != child) g.life().feel(child.getUUID(), cn, -15);
                            }
                        }
                        q.flags.put("recovered", "told");
                        return "You'll tell them? … I suppose I deserve it. Here — they'll have to buy it back. I'm sorry. I'm so sorry.";
                    }
                }
            }
            case "town_back" -> {
                Villages.Village there = Villages.get(UUID.fromString(q.flag("town")));
                if (there == null) return "";
                if (option.equals("demand")) {
                    boolean friends = Ledger.relation(q.village, there.id()) >= 0
                        || Standing.of(there.id(), p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.FRIEND);
                    if (!friends) {
                        Step again = new Step(StepType.CHOOSE, "town_back", "They will not give it up for nothing").chapter("Getting it back")
                            .who(s.who, s.whoName).option("buy", "Buy it back");
                        q.steps.add(again);
                        return "Stolen? Says who? It was bought fair and square. You'll pay for it like anybody else.";
                    }
                    if (!moveOutOfStores(level, q, there, heirloom, p)) return "It's gone from our stores, I'm afraid.";
                    Ledger.relate(q.village, there.id(), -3);
                    q.flags.put("recovered", "demanded");
                    homeStep(q);
                    return "Stolen, was it? Then it's not ours to keep. Take it home — and tell your elder we'll want a word about pedlars.";
                }
                int price = Math.max(1, (int) Math.round(worth * Budget.PLAYER_MARKUP));
                if (Market.coinsHeld(p) < price) {
                    again(q, s);
                    return "It'll cost you " + price + " coins. You've " + Market.coinsHeld(p) + ".";
                }
                if (!moveOutOfStores(level, q, there, heirloom, p)) return "It's gone from our stores, I'm afraid.";
                Market.payOut(p, price);
                Ledger.addCoins(there.id(), price);
                q.flags.put("recovered", "bought back from " + Villages.name(there.id()));
                homeStep(q);
                return price + " coins, and it's yours. A pretty thing. I hope it gets home.";
            }
            default -> {
                return "";
            }
        }
    }

    /** Accused wrongly: the accused and the family fall out, the accused does not forgive, and think again. */
    private static String wrongly(ServerLevel level, Quest q, String option, Player p) {
        String name = p.getName().getString();
        long day = QuestRun.day(level);
        q.flags.put("wrong", (q.flag("wrong") + "," + option).replaceFirst("^,", ""));
        VillageFolkEntity giver = QuestStories.role(level, q, "giver");
        String said;
        if (option.equals("pedlar")) {
            said = "The pedlar? It left with an empty pack — the innkeeper said so. No. Think again.";
        } else {
            VillageFolkEntity accused = QuestStories.role(level, q, option);
            String an = QuestStories.name(q, option);
            if (accused != null) {
                accused.persona().feelFor(p.getUUID(), name, -25);
                accused.persona().remember(day, name + " called me a thief, before " + QuestStories.name(q, "giver") + "'s whole family", 6);
                List<String> fam = new ArrayList<>(List.of(q.flag("payers").split(",")));
                if (giver != null) fam.add(giver.getStringUUID());
                for (String u : fam) {
                    VillageFolkEntity g = u.isEmpty() ? null : Civics.find(level, UUID.fromString(u));
                    if (g == null || g == accused) continue;
                    g.life().feel(accused.getUUID(), an, -30);
                    accused.life().feel(g.getUUID(), g.displayNameCap(), -30);
                }
                q.flags.put("thanks." + accused.getUUID(), "You. You called me a thief, {who}. I've not forgotten.");
            }
            Villages.tell(q.village, day, QuestStories.name(q, "giver") + "'s family accused " + an + " of taking " + q.flag("owner") + ", wrongly");
            said = option.equals("child") ? an + "? Our own " + an + "? … No. " + an + " was asleep, I'd swear to it — and now " + an + " won't speak to us. How could you?"
                : an + "? We've had words with " + an + " now, and " + an + " was at the tavern all night. Half the town saw. We'll never be friends again, and it's your doing.";
        }
        accuse(q, "Who took it, then? Think carefully this time");
        return said;
    }

    /** The heirloom out of wherever it was hidden: the neighbour's chest or pack. */
    private static ItemStack recover(ServerLevel level, Quest q, @Nullable VillageFolkEntity holder, Predicate<ItemStack> heirloom) {
        if (!q.flag("stash").isEmpty()) {
            ItemStack s = QuestItems.outOfChest(level, QuestStories.place(q, "stash"), heirloom);
            if (!s.isEmpty()) return s;
        }
        return holder == null ? ItemStack.EMPTY : QuestItems.fromFolk(holder, heirloom);
    }

    /** Out of a town's stores (where it was pawned or sold) and into the player's hands. */
    private static boolean moveOutOfStores(ServerLevel level, Quest q, Villages.Village v, Predicate<ItemStack> heirloom, Player p) {
        if (holds(p, heirloom)) return true;
        ItemStack s = QuestItems.outOfStores(level, v, heirloom);
        if (s.isEmpty()) return false;
        QuestItems.give(p, s);
        return true;
    }

    private static boolean holds(Player p, Predicate<ItemStack> heirloom) {
        return QuestItems.count(p, heirloom) > 0;
    }

    /** Not the coin for it: the same choice put again, for when you have. */
    private static void again(Quest q, Step s) {
        Step again = new Step(StepType.CHOOSE, s.key, s.text).chapter(s.chapter).who(s.who, s.whoName);
        again.options.addAll(s.options);
        q.steps.add(again);
    }

    /** Shamed before the town: everybody the colder to it, and the family coldest. */
    private static void disgrace(ServerLevel level, Quest q, @Nullable VillageFolkEntity who, int town, int family) {
        if (who == null) return;
        List<String> fam = new ArrayList<>(List.of(q.flag("payers").split(",")));
        fam.add(q.flag("cast.giver"));
        for (AssistantEntity a : Villages.folkOf(q.village)) {
            if (!(a instanceof VillageFolkEntity g) || g == who || g.isShowcase()) continue;
            g.life().feel(who.getUUID(), who.displayNameCap(), fam.contains(g.getStringUUID()) ? family : town);
        }
    }

    // ------------------------------------------------------------------ the reunion

    private static void homeStep(Quest q) {
        if (q.step("home") != null) return;
        String gn = QuestStories.name(q, "giver");
        q.steps.add(new Step(StepType.DELIVER, "home", "Bring the " + q.flag("word") + " home to " + gn).chapter("The reunion")
            .who(QuestStories.roleId(q, "giver"), gn).item("token:" + (q.flag("word").equals("ring") ? "heirloom_ring" : "heirloom_locket"), 1));
    }

    private static String reunion(ServerLevel level, Quest q, @Nullable VillageFolkEntity f, Player p) {
        // Back where it belongs: the family's chest, else the head of the family's own pack (where DELIVER put it).
        String word = q.flag("word");
        Predicate<ItemStack> heirloom = QuestItems.matcher("token:" + (word.equals("ring") ? "heirloom_ring" : "heirloom_locket"), q.id);
        BlockPos chest = QuestStories.place(q, "chest");
        if (f != null && QuestItems.chest(level, chest) != null) {
            ItemStack h = QuestItems.fromFolk(f, heirloom);
            if (!h.isEmpty() && !QuestItems.intoChest(level, chest, h)) QuestItems.toFolk(f, h);
        }
        return "Oh — oh, it's " + q.flag("owner") + ". It's really it. Look at it. I never thought we'd see it again.";
    }

    // ------------------------------------------------------------------ the end

    @Override
    public String ending(ServerLevel level, Quest q, Player p) {
        String name = p.getName().getString();
        long day = QuestRun.day(level);
        String gn = QuestStories.name(q, "giver"), word = q.flag("word"), owner = q.flag("owner");
        String blamed = q.flag("blamed"), how = q.flag("recovered");
        String thief = switch (blamed) {
            case "child" -> QuestStories.name(q, "child");
            case "neighbour" -> QuestStories.name(q, "neighbour");
            default -> "a pedlar";
        };
        boolean named = how.equals("expose") || how.equals("told") || blamed.equals("pedlar");
        List<String> fam = new ArrayList<>(List.of(q.flag("payers").split(",")));
        fam.add(q.flag("cast.giver"));
        for (String u : fam) {
            VillageFolkEntity g = u.isEmpty() ? null : Civics.find(level, UUID.fromString(u));
            if (g == null || (blamed.equals("child") && g.getUUID().equals(QuestStories.roleId(q, "child")))) continue;
            g.persona().feelFor(p.getUUID(), name, 20);
            g.persona().remember(day, name + " brought " + owner + " home", 7);
            q.flags.put("thanks." + g.getUUID(), "Every time I look at " + owner + ", I think of you, {who}.");
        }
        if (!q.flag("wrong").isEmpty()) q.flags.put("reward", "");
        QuestBook.title(p.getUUID(), q.village, "Finder");
        q.outcome = "brought home" + (named ? "; " + thief + " named" : "; nobody named") + (q.flag("wrong").isEmpty() ? "" : "; somebody wrongly accused first");
        q.flags.put("chronicle", "the " + gn + " family's " + word + ", taken from their chest, was brought home by " + name
            + (named ? " (" + (blamed.equals("pedlar") ? "a pedlar had sold it on" : thief + " had taken it") + ")" : ""));
        q.flags.put("gossip", switch (how) {
            case "expose" -> "Did you hear? " + thief + " stole " + owner + "! {who} found it out, and told the whole town.";
            case "told" -> thief + " pawned " + owner + " at the store! Their own family's! {who} found it out.";
            case "bought", "repaid" -> "{who} got " + gn + "'s " + word + " back. Won't say from whom. Very close, {who}.";
            case "quiet" -> "{who} found " + gn + "'s " + word + ". Nobody knows where. Somebody knows.";
            default -> "{who} went all the way to the next town for " + gn + "'s " + word + "!";
        });
        q.flags.put("memory", "brought " + owner + " home");
        String wrong = q.flag("wrong").isEmpty() ? "" : " Not everybody came out of it well: " + wrongNames(q) + " was wrongly accused, and has not forgotten.";
        String body = switch (how) {
            case "quiet" -> QuestStories.name(q, "neighbour") + " gave it back, ashamed, and nobody else need ever know. The two houses nod to each other in the street now.";
            case "expose" -> "The whole town knows " + thief + " took it, and " + thief + " walks with its head down. The two houses do not speak.";
            case "bought" -> "You bought it back from the store yourself and kept " + thief + "'s secret. The family will never know — and " + thief + " will never forget.";
            case "repaid" -> thief + " bought it back out of its own purse, and learned a hard lesson. The family need never know.";
            case "told" -> "The family know now that their own " + thief + " pawned it. They bought it back, and are cold to " + thief + " for a while; they will mend.";
            default -> "It had gone to " + Villages.name(UUID.fromString(q.flag("town"))) + " with a pedlar, and you brought it all the way home.";
        };
        return gn + " held " + owner + " for a long time without a word, and then put it back in the chest where it belongs. " + body + wrong;
    }

    private static String wrongNames(Quest q) {
        List<String> out = new ArrayList<>();
        for (String w : q.flag("wrong").split(",")) {
            if (w.equals("child")) out.add(QuestStories.name(q, "child"));
            else if (w.equals("neighbour")) out.add(QuestStories.name(q, "neighbour"));
        }
        return out.isEmpty() ? "nobody" : String.join(" and ", out);
    }

    @Override
    public void ended(ServerLevel level, Quest q) {
        if (q.state == State.DONE) return;
        Villages.tell(q.village, QuestRun.day(level), QuestStories.name(q, "giver") + "'s family gave " + q.flag("owner") + " up for lost");
    }
}
